package sh.haven.core.ssh.openkeychain

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The SSH Authentication API, as spoken by OpenKeychain (#487).
 *
 * The provider holds the private key — commonly an OpenPGP authentication
 * subkey, often on a hardware token — and signs on our behalf, so the key
 * itself never reaches Haven. Structurally this is the same arrangement as
 * `sh.haven.core.fido.FidoIdentity`: we hold a public key and delegate every
 * signature.
 *
 * Only the constants and the AIDL interface are needed to speak the protocol,
 * so Haven does not depend on the API library
 * (`com.github.open-keychain.open-keychain:sshauthentication-api`), which is
 * published on JitPack only — an awkward dependency for an F-Droid build.
 * `ISshAuthenticationService.aidl` and `SshAuthenticationApiError.java` are
 * vendored under their original package because the binder and the Parcelable
 * unmarshaller both resolve them by exact name; everything else here is a
 * re-declaration of documented string constants. Values copied from
 * `org.openintents.ssh.authentication.SshAuthenticationApi` (Apache-2.0).
 */
object OpenKeychainApi {
    /** Action a provider's service must declare; also the bind intent. */
    const val SERVICE_INTENT = "org.openintents.ssh.authentication.ISshAuthenticationService"

    const val EXTRA_API_VERSION = "api_version"
    const val API_VERSION = 1

    const val EXTRA_RESULT_CODE = "result_code"
    const val RESULT_CODE_ERROR = 0
    const val RESULT_CODE_SUCCESS = 1
    const val RESULT_CODE_USER_INTERACTION_REQUIRED = 2

    /** Sign [EXTRA_CHALLENGE]; returns a complete SSH signature blob. */
    const val ACTION_SIGN = "org.openintents.ssh.action.SIGN"
    const val EXTRA_CHALLENGE = "challenge"
    const val EXTRA_HASH_ALGORITHM = "hash_algorithm"
    const val EXTRA_SIGNATURE = "signature"

    /** Show the provider's key chooser; returns an id and a description. */
    const val ACTION_SELECT_KEY = "org.openintents.ssh.action.SELECT_KEY"
    const val EXTRA_KEY_DESCRIPTION = "key_description"

    /** Fetch the public key in OpenSSH form: `"<type> <base64>"`. */
    const val ACTION_GET_SSH_PUBLIC_KEY = "org.openintents.ssh.action.GET_SSH_PUBLIC_KEY"
    const val EXTRA_SSH_PUBLIC_KEY = "ssh_public_key"

    const val EXTRA_KEY_ID = "key_id"

    /** A [org.openintents.ssh.authentication.SshAuthenticationApiError]. */
    const val EXTRA_ERROR = "error"

    /** A [android.app.PendingIntent] to run before retrying the request. */
    const val EXTRA_PENDING_INTENT = "intent"

    // Hash algorithms. For RSA the choice also selects the SSH signature
    // algorithm the provider encodes (SHA512 → rsa-sha2-512, SHA256 →
    // rsa-sha2-256, SHA1 → ssh-rsa), so it is not a free choice — see
    // [hashAlgorithmFor].
    const val SHA1 = 0
    const val SHA256 = 2
    const val SHA384 = 3
    const val SHA512 = 4

    /**
     * The hash to request for the SSH signature algorithm JSch has asked for.
     *
     * For RSA this decides how the provider names the signature, so asking
     * for the wrong one produces a signature the server rejects rather than
     * an error we could report. For ECDSA the curve fixes the hash. EdDSA
     * ignores it (PureEdDSA) but still validates it, so it gets a real value
     * rather than a placeholder the provider would reject.
     *
     * Returns null for an algorithm this API cannot serve, which is a
     * clearer failure than signing with the wrong hash.
     */
    fun hashAlgorithmFor(sshAlgorithm: String): Int? = when (sshAlgorithm) {
        "ssh-rsa" -> SHA1
        "rsa-sha2-256" -> SHA256
        "rsa-sha2-512" -> SHA512
        "ecdsa-sha2-nistp256" -> SHA256
        "ecdsa-sha2-nistp384" -> SHA384
        "ecdsa-sha2-nistp521" -> SHA512
        "ssh-ed25519" -> SHA512
        else -> null
    }

    /**
     * Split an OpenSSH public key line into its algorithm and key blob.
     *
     * The provider returns `"<type> <base64>"` with no comment, but a line
     * pasted from `authorized_keys` carries one, and both are worth
     * accepting. Returns null if it does not parse — a caller that gets null
     * has no usable key and should say so, rather than proceed with an empty
     * blob that fails later as an authentication error.
     */
    fun parsePublicKey(line: String): OpenKeychainPublicKey? {
        val fields = line.trim().split(Regex("\\s+"))
        if (fields.size < 2) return null
        // java.util.Base64, not android.util.Base64: identical behaviour from
        // API 26, and real behaviour under unit tests rather than a stub that
        // would make a test of this unable to fail.
        val blob = try {
            java.util.Base64.getDecoder().decode(fields[1])
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (blob.isEmpty()) return null
        return OpenKeychainPublicKey(algorithm = fields[0], blob = blob)
    }

    /**
     * Packages offering the API. Requires the `<queries>` entry in this
     * module's manifest, without which package visibility hides every
     * provider on Android 11 and up and this returns nothing.
     */
    fun providerPackages(context: Context): List<String> =
        context.packageManager
            .queryIntentServices(Intent(SERVICE_INTENT), 0)
            .map { it.serviceInfo.packageName }

    /** Provider packages with their user-visible app labels, for a chooser. */
    fun providers(context: Context): List<OpenKeychainProvider> {
        val pm = context.packageManager
        return pm.queryIntentServices(Intent(SERVICE_INTENT), 0).map {
            OpenKeychainProvider(
                packageName = it.serviceInfo.packageName,
                label = runCatching { pm.getApplicationLabel(it.serviceInfo.applicationInfo).toString() }
                    .getOrDefault(it.serviceInfo.packageName),
            )
        }
    }

    /** True if [packageName] is installed and still offers the API. */
    fun isProviderInstalled(context: Context, packageName: String): Boolean =
        try {
            providerPackages(context).contains(packageName)
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
}

/** An installed app that can sign SSH challenges. */
data class OpenKeychainProvider(val packageName: String, val label: String)

/** An OpenSSH public key split into the two parts JSch needs separately. */
data class OpenKeychainPublicKey(val algorithm: String, val blob: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenKeychainPublicKey) return false
        return algorithm == other.algorithm && blob.contentEquals(other.blob)
    }

    override fun hashCode(): Int = algorithm.hashCode() * 31 + blob.contentHashCode()
}
