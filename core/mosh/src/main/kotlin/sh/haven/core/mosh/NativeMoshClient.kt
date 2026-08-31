package sh.haven.core.mosh

import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.File

private const val NATIVE_TAG = "NativeMoshClient"
private const val NATIVE_CLIENT_NAME = "libmosh_client.so"
private const val TERMINFO_ASSET = "mosh/terminfo/x/xterm-256color"
private const val TERMINFO_VERSION = "v1"

/** Which implementation owns one Mosh data plane. */
internal enum class MoshBackendKind {
    /** Upstream mosh-client 1.4.0 in a standalone child PTY. */
    UPSTREAM_NATIVE,

    /** Haven's existing in-process SSP transport. */
    KOTLIN_SSP,
}

/**
 * Native mosh cannot use Haven's injected UDP socket abstraction, so tunneled
 * profiles stay on the Kotlin backend. Direct profiles use upstream whenever
 * the ABI-specific executable was packaged, and source/test builds without
 * native artifacts retain the old backend instead of losing Mosh entirely.
 */
internal fun selectMoshBackend(nativeAvailable: Boolean, tunneled: Boolean): MoshBackendKind =
    if (nativeAvailable && !tunneled) {
        MoshBackendKind.UPSTREAM_NATIVE
    } else {
        MoshBackendKind.KOTLIN_SSP
    }

internal fun nativeMoshArguments(serverIp: String, port: Int): Array<String> =
    arrayOf("mosh-client", serverIp, port.toString())

internal fun nativeMoshEnvironment(
    key: String,
    terminfoDir: String,
    filesDir: String,
): Array<String> = arrayOf(
    "MOSH_KEY=$key",
    "TERM=xterm-256color",
    "TERMINFO=$terminfoDir",
    "LANG=en_US.UTF-8",
    "LC_ALL=en_US.UTF-8",
    "LC_CTYPE=en_US.UTF-8",
    "HOME=$filesDir",
    "PATH=/system/bin",
)

/**
 * Runs upstream mosh-client as a standalone PIE executable behind Haven's
 * existing forkpty bridge. Communication with the app is only PTY bytes and
 * window-size ioctls; the GPL mosh executable is not linked into Haven.
 */
internal class NativeMoshClient(
    context: Context,
    sessionId: String,
    serverIp: String,
    port: Int,
    key: String,
    onDataReceived: (ByteArray, Int, Int) -> Unit,
    onExited: (exitCode: Int) -> Unit,
) : Closeable {

    private val appContext = context.applicationContext
    private val binary = executable(appContext)
        ?: throw IllegalStateException("Native mosh-client is not packaged for this ABI")
    private val terminfoDir = ensureTerminfo(appContext)
    private val localSession = MoshPtySession(
        sessionId = "mosh-native-$sessionId",
        command = binary.absolutePath,
        args = nativeMoshArguments(serverIp, port),
        env = nativeMoshEnvironment(
            key = key,
            terminfoDir = terminfoDir.absolutePath,
            filesDir = appContext.filesDir.absolutePath,
        ),
        onDataReceived = onDataReceived,
        onExited = onExited,
    )

    fun start(rows: Int, cols: Int) {
        Log.d(NATIVE_TAG, "Starting ${binary.name} in PTY: $serverIpForLog:$portForLog")
        localSession.start(rows = rows, cols = cols)
    }

    fun sendInput(data: ByteArray) = localSession.sendInput(data)

    fun resize(cols: Int, rows: Int) = localSession.resize(cols, rows)

    override fun close() = localSession.close()

    // Avoid retaining the actual endpoint solely for a log line. These values
    // are deliberately generic because hostnames/IPs can be sensitive.
    private val serverIpForLog = "<redacted>"
    private val portForLog = port

    companion object {
        fun isAvailable(context: Context): Boolean = executable(context.applicationContext) != null

        private fun executable(context: Context): File? {
            val nativeDir = context.applicationInfo.nativeLibraryDir ?: return null
            return File(nativeDir, NATIVE_CLIENT_NAME).takeIf { it.isFile }
        }

        private val terminfoLock = Any()

        private fun ensureTerminfo(context: Context): File = synchronized(terminfoLock) {
            val root = File(context.filesDir, "mosh/terminfo-$TERMINFO_VERSION")
            val entry = File(root, "x/xterm-256color")
            if (entry.isFile && entry.length() > 0L) return@synchronized root

            entry.parentFile?.mkdirs()
            val staged = File(entry.parentFile, "${entry.name}.part")
            context.assets.open(TERMINFO_ASSET).use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            }
            if (!staged.renameTo(entry)) {
                staged.inputStream().use { input ->
                    entry.outputStream().use { output -> input.copyTo(output) }
                }
                staged.delete()
            }
            check(entry.isFile && entry.length() > 0L) {
                "Could not prepare xterm-256color terminfo for native mosh-client"
            }
            root
        }
    }
}
