package sh.haven.core.mosh

/** JNI bridge used only to isolate upstream mosh-client in a child PTY. */
internal object MoshPtyBridge {
    init {
        System.loadLibrary("mosh_pty_bridge")
    }

    /** Returns [masterFd, childPid], or [-1, errno] on failure. */
    external fun nativeForkPty(
        command: String,
        args: Array<String>,
        environment: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray

    external fun nativeSetSize(fd: Int, rows: Int, cols: Int): Int

    external fun nativeWaitPid(pid: Int): Int
}
