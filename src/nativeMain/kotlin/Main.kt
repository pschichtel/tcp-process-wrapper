
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.*
import io.ktor.util.collections.ConcurrentSet
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import io.ktor.utils.io.errors.*
import kotlinx.cinterop.*
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import platform.posix.*
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
private fun error(message: String): Nothing {
    val code = errno
    throw RuntimeException("$message: ${strerror(code)?.toKString()} ($code)")
}

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
private fun printBuffer(fd: Int, buffer: Pinned<ByteArray>, offset: Int, length: Int): Int {
    return writeFully(fd, buffer, offset, length)
}

private fun newBuffer(): ByteArray = ByteArray(8192)

@OptIn(ExperimentalForeignApi::class)
private inline fun <R> newPinnedBuffer(f: (ByteArray, Pinned<ByteArray>) -> R): R {
    return newBuffer().usePinned { pinned ->
        f(pinned.get(), pinned)
    }
}

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
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

@OptIn(ExperimentalForeignApi::class)
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

class ParsedArgs(val initialInput: String?, val bindAddress: String, val bindPort: Int, val processArgs: Array<String>) {
    override fun toString(): String {
        return "ParsedArgs(initialInput=$initialInput, processArgs=Array(${processArgs.joinToString(", ")}))"
    }
}

fun parseArguments(args: Array<String>): ParsedArgs {

    val index = args.indexOf("--")

    val (wrapperArgs, command) =
        if (index == -1) emptyArray<String>() to args
        else args.copyOfRange(fromIndex = 0, toIndex = index) to args.copyOfRange(fromIndex = index + 1, toIndex = args.size)

    val argParser = ArgParser("tcp-process-wrapper")
    val input by argParser.option(ArgType.String, shortName = "i", description = "Initial input to the sub process")
    val bindAddress by argParser.option(ArgType.String, shortName = "a", description = "Bind address of the wrapper")
    val bindPort by argParser.option(ArgType.Int, shortName = "p", description = "Bind port of the wrapper")

    argParser.parse(wrapperArgs)

    if (command.isEmpty()) {
        errorln("Command is missing!")
        exitProcess(1)
    }

    return ParsedArgs(
        input,
        bindAddress = bindAddress ?: "0.0.0.0",
        bindPort = bindPort ?: 8080,
        command,
    )
}

@OptIn(ExperimentalForeignApi::class)
suspend fun forwardPrintAndBroadcastData(
    process: Process,
    clients: Set<Client>,
    originClient: Client?,
    buffer: ByteArray,
    pinnedBuffer: Pinned<ByteArray>,
    length: Int,
) {
    if (length > 0) {
        printBuffer(STDOUT_FILENO, pinnedBuffer, 0, length)

        process.stdin.writeFully(buffer, 0, length)
        process.stdin.flush()

        val otherClients = clients.filter { it != originClient }
        if (otherClients.isNotEmpty()) {
            otherClients.map { otherClient ->
                otherClient.socket.async {
                    otherClient.outputChannel.writeFully(buffer, 0, length)
                    otherClient.outputChannel.flush()
                }
            }.awaitAll()
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
suspend fun actualMain(args: ParsedArgs): Unit = coroutineScope {
    val childProcess = subProcess(args.processArgs)

    launch(Dispatchers.IO) {
        for (signal in signals) {
            errorln("Processing signal $signal")
            kill(childProcess.pid, signal)
        }
    }

    val selectorManager = SelectorManager(Dispatchers.IO)
    val serverSocket = aSocket(selectorManager).tcp().bind(args.bindAddress, args.bindPort) {
        reuseAddress = true
    }

    launch(Dispatchers.Default) {
        val exitCode = childProcess.exitCode.await()
        errorln("Child process `${args.processArgs.joinToString(" ")}` exited: $exitCode")
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
                        forwardPrintAndBroadcastData(childProcess, clients, client, buffer, pinnedBuffer, bytesRead)
                    }
                }
            }
        }
    }

    args.initialInput?.let {
        val bytes = it.toByteArray()
        bytes.usePinned { pinnedBuffer ->
            forwardPrintAndBroadcastData(childProcess, clients, null, bytes, pinnedBuffer, bytes.size)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    val parsedArgs = parseArguments(args)

    val handleSignal = staticCFunction<Int, Unit> { signal: Int ->
        errorln("Received signal $signal")
        signals.trySend(signal)
    }
    signal(SIGINT, handleSignal)
    runBlocking {
        actualMain(parsedArgs)
    }
}