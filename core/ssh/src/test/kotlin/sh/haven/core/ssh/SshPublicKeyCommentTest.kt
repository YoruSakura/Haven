package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #449: a security-key credential exported from Haven came out as
 * `sk-ssh-ed25519@openssh.com AAAA…` with no trailing comment, while the same
 * key exported from ConnectBot carried its label. Without it an
 * `authorized_keys` line has nothing identifying which device it belongs to.
 */
class SshPublicKeyCommentTest {

    private val sk = "sk-ssh-ed25519@openssh.com AAAAGnNrLXNzaC1lZDI1NTE5QG9wZW5zc2g="

    @Test
    fun `a key with no comment gains its label`() {
        assertEquals("$sk havenTest", SshPublicKeyComment.withComment(sk, "havenTest"))
    }

    /**
     * The key's own comment may already be what the far end has in its
     * authorized_keys; replacing it with Haven's label would be worse than
     * leaving it.
     */
    @Test
    fun `an existing comment is never overwritten`() {
        val withOwn = "$sk user@laptop"
        assertEquals(withOwn, SshPublicKeyComment.withComment(withOwn, "somethingElse"))
    }

    /** OpenSSH reads the comment to end-of-line, so spaces are legitimate. */
    @Test
    fun `a label containing spaces is kept as one comment`() {
        assertEquals("$sk haven test", SshPublicKeyComment.withComment(sk, "haven test"))
        assertEquals("haven test", SshPublicKeyComment.commentOf("$sk haven test"))
    }

    /** A newline would split one key into two authorized_keys entries. */
    @Test
    fun `newlines and tabs in a label are collapsed`() {
        assertEquals("$sk a b c", SshPublicKeyComment.withComment(sk, "a\nb\tc"))
    }

    @Test
    fun `a blank or missing label changes nothing`() {
        assertEquals(sk, SshPublicKeyComment.withComment(sk, ""))
        assertEquals(sk, SshPublicKeyComment.withComment(sk, "   "))
        assertEquals(sk, SshPublicKeyComment.withComment(sk, null))
    }

    /** Not a key — do not invent structure for it. */
    @Test
    fun `a malformed line is left alone`() {
        assertEquals("garbage", SshPublicKeyComment.withComment("garbage", "label"))
        assertEquals("", SshPublicKeyComment.withComment("   ", "label"))
    }

    @Test
    fun `commentOf reports absence as empty`() {
        assertEquals("", SshPublicKeyComment.commentOf(sk))
    }
}
