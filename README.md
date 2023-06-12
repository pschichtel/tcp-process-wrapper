tcp-process-wrapper
===================

This is a simple program written in kotlin/native that wraps another process and exposes its standard streams
(stdin, stdout, stderr) via TCP to one or many clients.

The implementation is limited to POSIX APIs and should thus work on various operating systems.

Stdin and stderr are merged together and cannot be differentiated via TCP, however it is correctly relayed
to the wrapper's output. This was done to allow for easy access using tools like netcat.

The wrapper's only arguments is the command line of the subprocess. The executable will be looked up in the PATH
(execvp).

The wrapper itself prints very little information to its output besides the child process' outputs and the inputs
received from one of the TCP connections. All of these messages (e.g. new client connections) are printed to stderr.

Purpose
-------

The main purpose of this program is to be able to run interactive programs in container setups and allow them to be
accesses by multiple clients simultaneous.

