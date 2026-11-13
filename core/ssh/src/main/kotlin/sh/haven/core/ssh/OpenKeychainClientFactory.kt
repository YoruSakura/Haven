package sh.haven.core.ssh

import android.content.Context
import sh.haven.core.ssh.openkeychain.OpenKeychainClient

/**
 * Makes a client for the app holding a provider-backed key (#487).
 *
 * Exists so [SshConnection] can be handed the ability to reach that app
 * without taking a [Context] of its own, and so tests can stand in a fake
 * signer where a real provider cannot be installed.
 */
fun interface OpenKeychainClientFactory {
    fun create(providerPackage: String): OpenKeychainClient

    companion object {
        /** Binds to the real provider app. */
        fun from(context: Context): OpenKeychainClientFactory {
            val appContext = context.applicationContext
            return OpenKeychainClientFactory { pkg -> OpenKeychainClient(appContext, pkg) }
        }
    }
}
