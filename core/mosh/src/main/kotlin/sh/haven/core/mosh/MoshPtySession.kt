package sh.haven.core.mosh

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.Closeable
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

private const val PTY_TAG = "MoshPtySession"
private const val PTY_READ_BUFFER_SIZE = 8192

/** Minimal PTY lifecycle for the standalone native mosh-client process. */
internal class MoshPtySession(
    private val sessionId: String,
    private val command: String,
    private val args: Array<String>,
    private val env: Array<String>,
    private val onDataReceived: (ByteArray, Int, Int) -> Unit,
    private val onExited: (exitCode: Int) -> Unit,
) : Closeable {

    @Volatile
    private var closed = false
    @Volatile
    private var masterFd = -1
    @Volatile
    private var childPid = -1
    private var descriptor: ParcelFileDescriptor? = null
    private var input: FileInputStream? = null
    private var output: FileOutputStream? = null
    private var reader: Thread? = null
    private val writer = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "mosh-pty-write-$sessionId").apply { isDaemon = true }
    }

    fun start(rows: Int, cols: Int) {
        check(!closed) { "Mosh PTY is already closed" }
        val result = MoshPtyBridge.nativeForkPty(command, args, env, rows, cols)
        require(result.size >= 2) { "forkpty returned an invalid result" }
        if (result[0] < 0) {
            throw IllegalStateException("forkpty failed: errno=${result[1]}")
        }
        masterFd = result[0]
        childPid = result[1]

        val pfd = ParcelFileDescriptor.adoptFd(masterFd)
        descriptor = pfd
        input = FileInputStream(pfd.fileDescriptor)
        output = FileOutputStream(pfd.fileDescriptor)
        val startedPid = childPid
        reader = Thread({ readLoop(startedPid) }, "mosh-pty-read-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop(startedPid: Int) {
        val buffer = ByteArray(PTY_READ_BUFFER_SIZE)
        try {
            while (!closed) {
                val count = input?.read(buffer) ?: break
                if (count <= 0) break
                try {
                    onDataReceived(buffer, 0, count)
                } catch (e: Exception) {
                    // A transient UI/emulator sink failure must not stop the
                    // PTY reader and strand a still-running mosh-client.
                    if (!closed) Log.w(PTY_TAG, "Output sink failed for $sessionId: ${e.message}")
                }
            }
        } catch (e: Exception) {
            // Linux PTY masters commonly return EIO after the slave closes.
            // waitpid below is the authoritative exit status.
            if (!closed && !ptyClosedNormally(e)) {
                Log.w(PTY_TAG, "PTY read failed for $sessionId: ${e.message}")
            }
        }

        // Always reap the child, including close() races. Otherwise a session
        // closed by the UI can leave a zombie process until the app exits.
        val exitCode = runCatching { MoshPtyBridge.nativeWaitPid(startedPid) }.getOrDefault(-1)
        val shouldNotify = synchronized(this) {
            childPid = -1
            val notify = !closed
            closed = true
            closePtyLocked()
            reader = null
            notify
        }
        writer.shutdown()
        if (shouldNotify) {
            Log.d(PTY_TAG, "Native mosh-client exited for $sessionId: $exitCode")
            onExited(exitCode)
        }
    }

    fun sendInput(data: ByteArray) {
        if (closed) return
        val copy = data.copyOf()
        try {
            writer.execute {
                try {
                    output?.write(copy)
                    output?.flush()
                } catch (e: Exception) {
                    if (!closed) Log.w(PTY_TAG, "PTY write failed for $sessionId: ${e.message}")
                }
            }
        } catch (_: RejectedExecutionException) {
            // close() raced with this input event.
        }
    }

    fun resize(cols: Int, rows: Int) {
        if (!closed && masterFd >= 0) {
            MoshPtyBridge.nativeSetSize(masterFd, rows, cols)
        }
    }

    override fun close() {
        val pidToKill = synchronized(this) {
            if (closed) return
            closed = true
            val pid = childPid
            childPid = -1
            closePtyLocked()
            pid
        }
        if (pidToKill > 0) runCatching { android.os.Process.killProcess(pidToKill) }
        writer.shutdown()
        reader = null
    }

    /** Caller must hold this instance's monitor. */
    private fun closePtyLocked() {
        runCatching { output?.close() }
        runCatching { input?.close() }
        runCatching { descriptor?.close() }
        output = null
        input = null
        descriptor = null
        masterFd = -1
    }
}

internal fun ptyClosedNormally(error: Throwable): Boolean =
    generateSequence(error as Throwable?) { it.cause }.any { cause ->
        val message = cause.message.orEmpty().uppercase()
        message.contains("EIO") || message.contains("I/O ERROR")
    }
