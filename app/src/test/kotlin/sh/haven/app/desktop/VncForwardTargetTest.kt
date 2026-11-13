package sh.haven.app.desktop

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #538 vs #104: the VNC-over-SSH forward target must honour the configured
 * VNC host (a machine behind the jump host) but keep dialling loopback when
 * the VNC server IS the jump host.
 */
class VncForwardTargetTest {

    @Test
    fun `distinct machine behind the jump host is honoured verbatim`() {
        assertEquals("192.168.7.20", vncForwardTarget("192.168.7.20", "jump.example.com"))
    }

    @Test
    fun `vnc host equal to the jump host dials loopback`() {
        assertEquals("127.0.0.1", vncForwardTarget("jump.example.com", "jump.example.com"))
    }

    @Test
    fun `blank host falls back to loopback`() {
        assertEquals("127.0.0.1", vncForwardTarget("", "jump.example.com"))
        assertEquals("127.0.0.1", vncForwardTarget(null, null))
    }

    @Test
    fun `whitespace differences still match the jump host`() {
        assertEquals("127.0.0.1", vncForwardTarget(" jump.example.com ", "jump.example.com"))
    }

    @Test
    fun `no jump profile known honours the configured host`() {
        assertEquals("10.0.0.5", vncForwardTarget("10.0.0.5", null))
    }
}
