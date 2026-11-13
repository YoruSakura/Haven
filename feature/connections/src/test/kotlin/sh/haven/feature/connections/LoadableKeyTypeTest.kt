package sh.haven.feature.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sh.haven.core.ssh.openkeychain.OpenKeychainKeyData

/**
 * #487: an OpenKeychain key was reaching JSch's agent repository, where it
 * could only ever be rejected — `JSchException: invalid privatekey` in a
 * reporter's log, once per connect.
 *
 * The rule existed and was correct at one call site; the other had been
 * written out separately and had lost the provider clause. This pins both
 * halves so a future copy cannot drift the same way.
 */
class LoadableKeyTypeTest {

    @Test
    fun `ordinary key types are loadable`() {
        listOf("ssh-rsa", "ssh-ed25519", "ecdsa-sha2-nistp256", "ssh-dss").forEach {
            assertTrue("$it should be loadable", holdsLoadablePrivateKey(it))
        }
    }

    @Test
    fun `fido sk key types are not loadable`() {
        listOf("sk-ssh-ed25519@openssh.com", "sk-ecdsa-sha2-nistp256@openssh.com").forEach {
            assertFalse("$it holds a credential handle, not a key", holdsLoadablePrivateKey(it))
        }
    }

    @Test
    fun `provider-held key types are not loadable`() {
        assertFalse(
            "an OpenKeychain key's bytes name a key in another app",
            holdsLoadablePrivateKey(OpenKeychainKeyData.KEY_TYPE),
        )
    }
}
