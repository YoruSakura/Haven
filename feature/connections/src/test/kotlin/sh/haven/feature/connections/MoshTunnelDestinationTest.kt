package sh.haven.feature.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #539: a tunneled mosh session's UDP destination must be an IP literal.
 * A MagicDNS profile host falls back to the address the SSH bootstrap's
 * tunnel dial resolved; no literal at all must fail, not retry silently.
 */
class MoshTunnelDestinationTest {

    @Test
    fun `literal profile host is used as-is`() {
        assertEquals("100.64.0.7", moshTunnelDestination("100.64.0.7", null))
        assertEquals("fd7a::1234", moshTunnelDestination("fd7a::1234", "100.64.0.7"))
    }

    @Test
    fun `magicdns name falls back to the bootstrap-resolved peer`() {
        assertEquals("100.64.0.7", moshTunnelDestination("myhost.tailnet.ts.net", "100.64.0.7"))
    }

    @Test
    fun `no literal available yields null`() {
        assertNull(moshTunnelDestination("myhost.tailnet.ts.net", null))
        assertNull(moshTunnelDestination("myhost", "still-a-name.example"))
    }

    @Test
    fun `ip literal detection`() {
        assertTrue(isIpLiteral("192.168.1.1"))
        assertTrue(isIpLiteral("fd7a:115c:a1e0::1"))
        assertFalse(isIpLiteral("myhost.tailnet.ts.net"))
        assertFalse(isIpLiteral("localhost"))
    }
}
