package sh.haven.core.ssh.openkeychain

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parts of the SSH Authentication API client that can be checked without
 * a provider installed (#487).
 *
 * The hash mapping matters more than it looks: for RSA the hash Haven asks
 * for is what decides the signature algorithm name the provider encodes, so
 * getting it wrong yields a well-formed signature the server rejects, with no
 * error to explain it. Values verified against OpenKeychain's own
 * `SshSignatureConverter.getRsaSignatureFormatId`.
 */
class OpenKeychainApiTest {

    @Test
    fun `RSA hash choice matches the signature name the provider will encode`() {
        assertEquals(OpenKeychainApi.SHA1, OpenKeychainApi.hashAlgorithmFor("ssh-rsa"))
        assertEquals(OpenKeychainApi.SHA256, OpenKeychainApi.hashAlgorithmFor("rsa-sha2-256"))
        assertEquals(OpenKeychainApi.SHA512, OpenKeychainApi.hashAlgorithmFor("rsa-sha2-512"))
    }

    @Test
    fun `ECDSA takes the hash its curve requires`() {
        assertEquals(OpenKeychainApi.SHA256, OpenKeychainApi.hashAlgorithmFor("ecdsa-sha2-nistp256"))
        assertEquals(OpenKeychainApi.SHA384, OpenKeychainApi.hashAlgorithmFor("ecdsa-sha2-nistp384"))
        assertEquals(OpenKeychainApi.SHA512, OpenKeychainApi.hashAlgorithmFor("ecdsa-sha2-nistp521"))
    }

    @Test
    fun `ed25519 asks for a real hash even though the provider ignores it`() {
        // PureEdDSA ignores the value, but the provider still validates it and
        // answers INVALID_HASH_ALGORITHM to anything outside its enumeration.
        val hash = OpenKeychainApi.hashAlgorithmFor("ssh-ed25519")
        assertEquals(OpenKeychainApi.SHA512, hash)
    }

    @Test
    fun `an algorithm the API cannot sign is rejected rather than guessed`() {
        assertNull(OpenKeychainApi.hashAlgorithmFor("sk-ssh-ed25519@openssh.com"))
        assertNull(OpenKeychainApi.hashAlgorithmFor("ssh-dss-nonsense"))
        assertNull(OpenKeychainApi.hashAlgorithmFor(""))
    }

    @Test
    fun `a provider public key line parses into algorithm and blob`() {
        val blob = byteArrayOf(0, 0, 0, 11, 115, 115, 104, 45, 101, 100, 50, 53, 53, 49, 57)
        val encoded = java.util.Base64.getEncoder().encodeToString(blob)
        val parsed = OpenKeychainApi.parsePublicKey("ssh-ed25519 $encoded")!!
        assertEquals("ssh-ed25519", parsed.algorithm)
        assertArrayEquals(blob, parsed.blob)
    }

    @Test
    fun `a pasted authorized_keys line with a comment still parses`() {
        // The provider sends two fields, but a line copied from
        // authorized_keys has a trailing comment and is worth accepting.
        val encoded = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val parsed = OpenKeychainApi.parsePublicKey("ssh-rsa $encoded me@example.com")!!
        assertEquals("ssh-rsa", parsed.algorithm)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsed.blob)
    }

    @Test
    fun `surrounding whitespace does not defeat parsing`() {
        val encoded = java.util.Base64.getEncoder().encodeToString(byteArrayOf(9))
        assertEquals("ssh-rsa", OpenKeychainApi.parsePublicKey("  ssh-rsa   $encoded \n")?.algorithm)
    }

    @Test
    fun `an unusable public key is null rather than an empty blob`() {
        // Returning an empty blob here would surface much later as an
        // unexplained authentication failure.
        assertNull(OpenKeychainApi.parsePublicKey("ssh-ed25519"))
        assertNull(OpenKeychainApi.parsePublicKey(""))
        assertNull(OpenKeychainApi.parsePublicKey("ssh-ed25519 not!valid!base64!"))
        assertNull(OpenKeychainApi.parsePublicKey("ssh-ed25519 "))
    }
}
