package sh.haven.core.local

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors

private const val TAG = "LocalSession"
private const val READ_BUFFER_SIZE = 8192

/**
 * Bridges a local PTY process to the terminal emulator.
 *
 * Manages a child process (shell or proot) with a pseudoterminal,
 * reading output on a background thread and writing input from any thread.
 */
class LocalSession(
    val sessionId: String,
    val profileId: String,
    val label: String,
    private val command: String,
    private val args: Array<String>,
    private val env: Array<String>,
    onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onExited: ((exitCode: Int) -> Unit)? = null,
) : Closeable {

    // Swappable so the PTY can outlive the emulator: when the Activity (and its
    // TerminalViewModel) is torn down while the proot shell keeps running, the
    // new ViewModel rewires this to a fresh emulator instead of the shell being
    // killed and restarted blank (#272). Volatile: set on the main thread,
    // read on the PTY reader thread.
    @Volatile
    private var onData: (ByteArray, Int, Int) -> Unit = onDataReceived

    /** Rewire the PTY output sink (UI reattach after Activity recreation). */
    fun replaceDataCallback(callback: (ByteArray, Int, Int) -> Unit) {
        onData = callback
    }

    /**
     * Dispatch bytes through the current output sink as if the PTY reader thread
     * produced them. Exists so the callback-swap can be tested without a native
     * PTY (forkpty isn't available off-device).
     */
    @androidx.annotation.VisibleForTesting
    internal fun dispatchForTest(data: ByteArray, offset: Int, length: Int) {
        onData(data, offset, length)
    }

    @Volatile
    private var closed = false

    /**
     * Coordinates explicit teardown with the PTY reader's natural-EOF path.
     * A normal `exit` has already reaped the child with waitpid, so it must
     * release descriptors without signalling that (now reusable) PID again.
     */
    private val lifecycleLock = Any()
    private var resourcesReleased = false
    @Volatile
    private var waitingForExit = false

    private var masterFd: Int = -1
    private var childPid: Int = -1
    private var masterPfd: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var readerThread: Thread? = null

    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "local-pty-write-$sessionId").apply { isDaemon = true }
    }

    /**
     * Fork the child process and start reading PTY output.
     * @throws IllegalStateException if forkpty fails
     */
    fun start(rows: Int = 24, cols: Int = 80) {
        if (closed) return

        val result = PtyBridge.nativeForkPty(command, args, env, rows, cols)
        // On failure the native contract returns [-1, errno]; commit childPid
        // only after the success check so a later close() can't feed errno to
        // android.os.Process.killProcess(). (#208 finding 8)
        val fd = result[0]
        if (fd < 0) {
            throw IllegalStateException("forkpty failed: errno=${result[1]}")
        }
        masterFd = fd
        childPid = result[1]

        Log.d(TAG, "Started local process: pid=$childPid fd=$masterFd cmd=$command")

        val pfd = ParcelFileDescriptor.adoptFd(masterFd)
        masterPfd = pfd  // prevent GC from closing the fd
        inputStream = FileInputStream(pfd.fileDescriptor)
        outputStream = FileOutputStream(pfd.fileDescriptor)

        // Start reader thread
        // Keep the launched PID local to this reader. close() clears childPid;
        // reading the mutable field after an EOF/close race could otherwise
        // call waitpid(-1), which may reap a newly opened shell instead.
        val launchedPid = childPid
        readerThread = Thread({
            val buf = ByteArray(READ_BUFFER_SIZE)
            var readCount = 0
            try {
                while (!closed) {
                    val n = inputStream!!.read(buf)
                    if (n <= 0) break
                    readCount++
                    // Guard the sink: after the Activity is destroyed the old
                    // emulator callback may still be wired until reattach swaps
                    // it (#272). A throw there must not break the PTY reader and
                    // kill the shell.
                    try {
                        onData(buf, 0, n)
                    } catch (e: Exception) {
                        if (!closed) Log.w(TAG, "output sink threw: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (!closed) {
                    Log.d(TAG, "Read loop ended (after $readCount reads): ${e.message}")
                }
            }
            // Process exited — wait for exit code
            if (!closed) {
                synchronized(lifecycleLock) {
                    if (closed) return@Thread
                    // PTY EOF means the child is already on its way out. An
                    // explicit close racing waitpid must release resources but
                    // must not signal this PID after waitpid may have reaped it.
                    waitingForExit = true
                }
                val exitCode = try {
                    PtyBridge.nativeWaitPid(launchedPid)
                } catch (_: Exception) {
                    -1
                }
                Log.d(TAG, "Process $launchedPid exited: $exitCode")
                // This is a natural exit: waitpid above has already reaped the
                // child. Release the PTY without running close()'s kill path;
                // the numeric PID may be reused by the time the UI opens the
                // next PRoot shell.
                finishAfterProcessExit()
                onExited?.invoke(exitCode)
            }
        }, "local-pty-read-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * True while the child process and its reader thread are live. Goes false
     * once the PTY hits EOF (process exited) or [close] runs — used so callers
     * can fail a write loudly instead of silently dropping it into a dead fd.
     */
    fun isAlive(): Boolean = !closed && childPid > 0 && readerThread?.isAlive == true

    /**
     * Send keyboard input to the PTY.
     */
    fun sendInput(data: ByteArray) {
        if (closed) return
        try {
            writeExecutor.execute {
                try {
                    outputStream?.write(data)
                    outputStream?.flush()
                } catch (e: Exception) {
                    if (!closed) {
                        Log.e(TAG, "Write failed: ${e.message}")
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // close() raced between the `closed` check and execute() and shut the
            // executor down — drop the keystroke instead of crashing the caller
            // (the UI path runs on the main thread). (#208 finding 19)
        }
    }

    /**
     * Resize the PTY terminal.
     */
    fun resize(cols: Int, rows: Int) {
        if (closed || masterFd < 0) return
        PtyBridge.nativeSetSize(masterFd, rows, cols)
    }

    override fun close() {
        val pidToKill = synchronized(lifecycleLock) {
            if (closed) return
            closed = true
            childPid.takeUnless { waitingForExit } ?: -1
        }

        // Snapshot while the launcher still owns its tracees. Closing the PTY
        // can make PRoot exit and reparent them before /proc is scanned.
        val tracees = if (pidToKill > 0) {
            runCatching { descendantPidsOf(pidToKill) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        releaseResources()

        // Kill the child process AND its proot tracees. PRoot's --kill-on-exit
        // doesn't reap the ptrace tracees when the launcher is signalled, so
        // snapshot the descendants while the tree is intact, then kill the
        // launcher + every tracee. Killing only childPid (the old behaviour)
        // left the whole guest (libproot + bash + …) running — the "proot is
        // hard to kill" orphan (#409/#411).
        if (pidToKill > 0) {
            runCatching { android.os.Process.killProcess(pidToKill) }
            tracees.forEach { runCatching { android.os.Process.killProcess(it) } }
        }
    }

    /** Release a naturally exited process without signalling its reaped PID. */
    private fun finishAfterProcessExit() {
        synchronized(lifecycleLock) {
            if (closed) return
            closed = true
        }
        releaseResources()
    }

    /**
     * Idempotently release Java/Android PTY resources. Kept separate from the
     * process signal path so natural exit and explicit close cannot double-use
     * a child PID while still sharing exactly one descriptor cleanup routine.
     */
    private fun releaseResources() {
        synchronized(lifecycleLock) {
            if (resourcesReleased) return
            resourcesReleased = true

            // Closing the streams/PFD wakes a reader blocked in read().
            try { outputStream?.close() } catch (_: Exception) {}
            try { inputStream?.close() } catch (_: Exception) {}
            try { masterPfd?.close() } catch (_: Exception) {}

            writeExecutor.shutdown()
            readerThread = null
            inputStream = null
            outputStream = null
            masterPfd = null
            masterFd = -1
            childPid = -1
        }
    }
}
