package sh.haven.core.ssh

/**
 * The trailing comment on an OpenSSH public key line (#449).
 *
 * A public key is `<type> <base64> [comment]`, and the comment is what makes an
 * `authorized_keys` file readable — it is how you tell which of six similar
 * lines belongs to which device. Haven generates software keys with one, but
 * builds the line without one for imported keys and for security-key (SK)
 * credentials read off the token, so copying those out produced a line with
 * nothing to identify it.
 */
object SshPublicKeyComment {

    /**
     * The comment already on [publicKeyOpenSsh], or empty when it has none.
     *
     * Everything after the base64 field is the comment, spaces included —
     * OpenSSH reads it to end-of-line — so this deliberately does not split
     * further.
     */
    fun commentOf(publicKeyOpenSsh: String): String =
        publicKeyOpenSsh.trim().split(Regex("\\s+"), limit = 3).getOrNull(2)?.trim().orEmpty()

    /**
     * [publicKeyOpenSsh] with [label] appended as its comment, when it has none
     * of its own. A line that already carries a comment is returned untouched:
     * the key's own comment is what the far end may already have in an
     * `authorized_keys` file, and overwriting it would be worse than useless.
     *
     * Newlines and tabs are collapsed to spaces because a public key is a
     * single line by definition, and a stray newline in a label would split it
     * into two entries — one of them invalid.
     */
    fun withComment(publicKeyOpenSsh: String, label: String?): String {
        val line = publicKeyOpenSsh.trim()
        if (line.isEmpty()) return line
        if (commentOf(line).isNotEmpty()) return line
        val comment = label?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (comment.isEmpty()) return line
        // A line with no base64 field is not a key; leave it alone rather than
        // inventing structure for it.
        if (line.split(Regex("\\s+")).size < 2) return line
        return "$line $comment"
    }
}
