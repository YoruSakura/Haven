package sh.haven.core.mosh

import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMoshClientTest {

    @Test
    fun `direct profile prefers packaged upstream client`() {
        assertEquals(
            MoshBackendKind.UPSTREAM_NATIVE,
            selectMoshBackend(nativeAvailable = true, tunneled = false),
        )
    }

    @Test
    fun `tunneled profile keeps injectable Kotlin UDP backend`() {
        assertEquals(
            MoshBackendKind.KOTLIN_SSP,
            selectMoshBackend(nativeAvailable = true, tunneled = true),
        )
    }

    @Test
    fun `source build without native artifact keeps Mosh available`() {
        assertEquals(
            MoshBackendKind.KOTLIN_SSP,
            selectMoshBackend(nativeAvailable = false, tunneled = false),
        )
    }

    @Test
    fun `native argv follows upstream mosh-client contract`() {
        assertArrayEquals(
            arrayOf("mosh-client", "203.0.113.7", "60001"),
            nativeMoshArguments("203.0.113.7", 60001),
        )
    }

    @Test
    fun `native environment carries key and private app paths`() {
        val env = nativeMoshEnvironment(
            key = "abcdefghijklmnopqrstuv",
            terminfoDir = "/data/user/0/sh.haven.app/files/mosh/terminfo-v1",
            filesDir = "/data/user/0/sh.haven.app/files",
        )

        assertTrue("MOSH_KEY=abcdefghijklmnopqrstuv" in env)
        assertTrue("TERM=xterm-256color" in env)
        assertTrue(env.any { it.startsWith("TERMINFO=/data/user/0/") })
        assertTrue(env.any { it.startsWith("HOME=/data/user/0/") })
        assertFalse(env.any { it.startsWith("LD_LIBRARY_PATH=") })
    }

    @Test
    fun `pty EIO means the child closed its slave normally`() {
        assertTrue(ptyClosedNormally(IOException("read failed: EIO (I/O error)")))
        assertTrue(ptyClosedNormally(IOException("wrapped", IOException("I/O error"))))
        assertFalse(ptyClosedNormally(IOException("bad file descriptor")))
    }
}
