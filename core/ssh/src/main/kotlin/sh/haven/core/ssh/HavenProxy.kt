package sh.haven.core.ssh

import com.jcraft.jsch.Proxy

/** Opaque proxy wrapper shared by SSH jumps and userspace VPN transports. */
class HavenProxy(val jschProxy: Proxy?) {
    val tunnelPeerAddress: String?
        get() = (jschProxy as? TunnelPeerAware)?.tunnelPeerAddress
}

/** The peer IP reported by a userspace VPN after it opens the connection. */
interface TunnelPeerAware {
    val tunnelPeerAddress: String?
}
