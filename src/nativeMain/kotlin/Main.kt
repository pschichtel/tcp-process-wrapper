import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.util.collections.ConcurrentSet
import io.ktor.utils.io.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.posix.*
import kotlin.system.exitProcess

private fun error(message: String): Nothing {
    val code = errno
    throw RuntimeException("$message: ${strerror(code)?.toKString()} ($code)")
}

private fun errorln(message: String) {
    if (fprintf(stderr, "$message\n") == -1) {
        error("Failed to fprintf to stderr")
    }
}

data class Pipe(val readEnd: Int, val writeEnd: Int) {
    private fun closeFd(fd: Int) {
        val status = close(fd)
        if (status != 0) {
            error("Failed to close fd $fd")
        }
    }

    fun closeRead() {
        closeFd(readEnd)
    }

    fun closeWrite() {
        closeFd(writeEnd)
    }

    fun close() {
        closeRead()
        closeWrite()
    }
}

private fun mkpipe(): Pipe {
    val fds = IntArray(2)
    val status = fds.usePinned {
        pipe(it.addressOf(0))
    }
    if (status != 0) {
        error("Failed to create pipe")
    }
    return Pipe(fds[0], fds[1])
}

private fun redirect(old: Int, new: Int) {
    if (dup2(old, new) == -1) {
        error("Failed to dup2 $old to $new")
    }
    if (close(old) == -1) {
        error("Failed to close old fd $old")
    }
}

enum class StdStream(val fd: Int) {
    In(STDIN_FILENO),
    Out(STDOUT_FILENO),
    Err(STDERR_FILENO),
}

private fun redirectPipe(pipe: Pipe, stream: StdStream) {
    when (stream) {
        StdStream.In -> {
            pipe.closeWrite()
            redirect(pipe.readEnd, stream.fd)
        }
        StdStream.Out, StdStream.Err -> {
            pipe.closeRead()
            redirect(pipe.writeEnd, stream.fd)
        }
    }
}

data class Process(val pid: Int, val stdin: ByteWriteChannel, val stdout: ByteReadChannel, val stderr: ByteReadChannel, val exitCode: Deferred<Int>)

private fun writeFully(fd: Int, buffer: Pinned<ByteArray>, offset: Int, length: Int): Int {
    var bytesWritten = 0
    while (bytesWritten < length) {
        val newBytesWritten = write(
            fd,
            buffer.addressOf(offset + bytesWritten),
            (length - bytesWritten).convert()
        ).toInt()
        if (newBytesWritten == -1) {
            break
        }
        bytesWritten += newBytesWritten
    }
    return bytesWritten
}

private fun printBuffer(fd: Int, buffer: Pinned<ByteArray>, offset: Int, length: Int): Int {
    return writeFully(fd, buffer, offset, length)
}

private fun newBuffer(): ByteArray = ByteArray(8192)

private inline fun <R> newPinnedBuffer(f: (ByteArray, Pinned<ByteArray>) -> R): R {
    return newBuffer().usePinned { pinned ->
        f(pinned.get(), pinned)
    }
}

private fun CoroutineScope.subProcess(cmdLine: Array<String>): Process {
    val stdinPipe = mkpipe()
    val stdoutPipe = mkpipe()
    val stderrPipe = mkpipe()

    val childPid = fork()
    if (childPid == -1) {
        error("Failed to fork!")
    }
    if (childPid == 0) {
        execSubprocess(cmdLine, stdinPipe, stdoutPipe, stderrPipe)
    } else {
        val deferredExitCode = CompletableDeferred<Int>()

        stdinPipe.closeRead()
        stdoutPipe.closeWrite()
        stderrPipe.closeWrite()

        fun CoroutineScope.forwardOutput(pipe: Pipe, channel: ByteWriteChannel) {
            launch(Dispatchers.IO) {
                val buffer = ByteArray(8192)
                buffer.usePinned {
                    while (isActive) {
                        val bytesRead = read(pipe.readEnd, it.addressOf(0), buffer.size.convert())
                        if (bytesRead == -1L || channel.isClosedForWrite) {
                            break
                        }
                        channel.writeFully(buffer, 0, bytesRead.toInt())
                        channel.flush()
                    }
                }
                errorln("Output forwarder stopped")
            }
        }

        fun CoroutineScope.forwardInput(channel: ByteReadChannel, pipe: Pipe) {
            launch(Dispatchers.IO) {
                newPinnedBuffer { buffer, pinnedBuffer ->
                    while (isActive) {
                        val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                        if (bytesRead == -1) {
                            errorln("input channel closed")
                            break
                        }
                        val bytesWritten = writeFully(pipe.writeEnd, pinnedBuffer, 0, bytesRead)
                        if (bytesWritten != bytesRead) {
                            errorln("Not all bytes have been written, expected $bytesRead, but wrote $bytesWritten")
                            break
                        }
                    }
                }
                errorln("Input forwarder stopped")
            }
        }

        val stdinChannel = ByteChannel()
        val stdoutChannel = ByteChannel()
        val stderrChannel = ByteChannel()

        forwardInput(stdinChannel, stdinPipe)
        forwardOutput(stdoutPipe, stdoutChannel)
        forwardOutput(stderrPipe, stderrChannel)

        launch(Dispatchers.IO) {
            val exitCode = memScoped {
                val status = alloc<Int>(0)
                waitpid(childPid, status.ptr, 0)
                (status.value and 0xFF00) shr 8
            }

            deferredExitCode.complete(exitCode)

            stdinChannel.close()
            stdinPipe.close()
            stdoutChannel.close()
            stdoutPipe.close()
            stderrChannel.close()
            stderrPipe.close()
        }

        return Process(childPid, stdinChannel, stdoutChannel, stderrChannel, deferredExitCode)
    }
}

private fun execSubprocess(cmdLine: Array<String>, stdin: Pipe, stdout: Pipe, stderr: Pipe): Nothing {
    redirectPipe(stdin, StdStream.In)
    redirectPipe(stdout, StdStream.Out)
    redirectPipe(stderr, StdStream.Err)

    val status = memScoped {
        val argv = allocArray<CPointerVar<ByteVar>>(cmdLine.size + 1)
        for (i in cmdLine.indices) {
            argv[i] = cmdLine[i].cstr.ptr
        }
        argv[cmdLine.size] = null
        execvp(cmdLine[0], argv)
    }

    if (status != 0) {
        error("Failed to execv ${cmdLine.joinToString(" ")}")
    }
    exitProcess(status)
}

data class Client(val socket: Socket, val outputChannel: ByteWriteChannel)

val signals = Channel<Int>()

fun CoroutineScope.outputBroadcaster(channel: ByteReadChannel, echoFd: Int, clients: Set<Client>) {
    launch(Dispatchers.IO) {
        newPinnedBuffer { buffer, pinnedBuffer ->
            while (isActive) {
                val bytesRead = channel.readAvailable(buffer, 0, buffer.size)
                if (bytesRead == -1) {
                    break
                }
                if (bytesRead > 0) {
                    printBuffer(echoFd, pinnedBuffer, 0, bytesRead)

                    if (clients.isNotEmpty()) {
                        coroutineScope {
                            for (client in clients) {
                                launch(Dispatchers.IO) {
                                    client.outputChannel.writeFully(buffer, 0, bytesRead)
                                    client.outputChannel.flush()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        errorln("Command is missing!")
        exitProcess(1)
    }

    val handleSignal = staticCFunction<Int, Unit> { signal: Int ->
        errorln("Received signal $signal")
        signals.trySend(signal)
    }
    signal(SIGINT, handleSignal)

    runBlocking {
        val childProcess = subProcess(args)

        launch(Dispatchers.IO) {
            for (signal in signals) {
                errorln("Processing signal $signal")
                kill(childProcess.pid, signal)
            }
        }

        val selectorManager = SelectorManager(Dispatchers.IO)
        val serverSocket = aSocket(selectorManager).tcp().bind("0.0.0.0", 8080) {
            reuseAddress = true
        }

        launch(Dispatchers.Default) {
            val exitCode = childProcess.exitCode.await()
            errorln("Child process `${args.joinToString(" ")}` exited: $exitCode")
            selectorManager.cancel()
            signals.close()
        }

        val clients = ConcurrentSet<Client>()

        outputBroadcaster(childProcess.stdout, STDOUT_FILENO, clients)
        outputBroadcaster(childProcess.stderr, STDERR_FILENO, clients)

        launch(Dispatchers.IO) {
            while (isActive && !serverSocket.isClosed) {
                errorln("Awaiting socket...")
                val socket = try {
                    serverSocket.accept()
                } catch (e: IOException) {
                    errorln(e.message ?: "Unknown error during accept")
                    break
                }
                errorln("Client connected: ${socket.remoteAddress}")

                socket.launch {
                    val readChannel = socket.openReadChannel()
                    val writeChannel = socket.openWriteChannel(autoFlush = false)
                    val client = Client(socket, writeChannel)
                    clients.add(client)

                    newPinnedBuffer { buffer, pinnedBuffer ->
                        while (isActive) {
                            readChannel.awaitContent()
                            if (readChannel.isClosedForRead) {
                                errorln("Client disconnected: ${socket.remoteAddress}")
                                clients.remove(client)
                                break
                            }
                            val bytesRead = readChannel.readAvailable(buffer)
                            if (bytesRead > 0) {
                                printBuffer(STDOUT_FILENO, pinnedBuffer, 0, bytesRead)

                                childProcess.stdin.writeFully(buffer, 0, bytesRead)
                                childProcess.stdin.flush()

                                val otherClients = clients.filter { it != client }
                                if (otherClients.isNotEmpty()) {
                                    otherClients.map { otherClient ->
                                        otherClient.socket.async {
                                            otherClient.outputChannel.writeFully(buffer, 0, bytesRead)
                                            otherClient.outputChannel.flush()
                                        }
                                    }.awaitAll()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}