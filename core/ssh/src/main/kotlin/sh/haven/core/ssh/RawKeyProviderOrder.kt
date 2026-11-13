package sh.haven.core.ssh

import android.util.Log
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.Provider
import java.security.Security

/**
 * Keeps AndroidKeyStore from winning JCE lookups for raw key material (#451).
 *
 * sshlib resolves its raw-key primitives without naming a provider:
 *
 *     KeyPairGenerator.getInstance("X25519")
 *     KeyFactory.getInstance("X25519")
 *
 * On some builds those two resolve to *different* providers, and the KeyFactory
 * lands on AndroidKeyStore — which by design only produces keys held in the
 * keystore. Converting a peer's ephemeral public key (or a server's ed25519 host
 * key) from an X509EncodedKeySpec is something it can never do, so the key
 * exchange dies with "To generate a key pair in Android Keystore, use
 * KeyPairGenerator initialized with android.security.keystore.KeyGenParameterSpec".
 * Reported upstream as connectbot/cbssh#246.
 *
 * AndroidKeyStore is never a correct answer for an *unpinned* raw-key lookup:
 * anything that reaches it that way was already going to fail. So when we detect
 * it winning one, move it to the end of the provider list. Callers that actually
 * want the keystore ask for it by name — `KeyStore.getInstance("AndroidKeyStore")`
 * and `getInstance(alg, "AndroidKeyStore")` resolve by name, not by order, and
 * are unaffected. Haven's own keystore-backed keys keep working.
 *
 * **Demotion alone is not always enough.** Being last still means being chosen
 * when nothing else offers the algorithm at all, which is what the v5.83.20
 * attempt ran into: the reorder fired and the connection failed anyway, because
 * on that ROM AndroidKeyStore was the only provider registering the KeyFactory.
 * For that case we register the bundled BouncyCastle, which does implement these
 * raw-key factories, and put it ahead of the keystore. That step runs *only*
 * when a device has already proved it cannot resolve the algorithm otherwise —
 * on a healthy device nothing here touches the provider list at all.
 *
 * A workaround, not a fix: the real repair is upstream naming its provider. This
 * exists because waiting on an upstream release leaves the engine unusable on
 * the affected hardware.
 */
object RawKeyProviderOrder {

    private const val TAG = "RawKeyProviderOrder"
    internal const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    /** Raw-key algorithms sshlib resolves without naming a provider. */
    internal val RAW_KEY_ALGORITHMS = listOf("X25519", "XDH", "Ed25519", "EC")

    /**
     * What [applyWith] did, so the caller can log it and a test can assert it.
     *
     * @property trigger algorithm whose lookup proved the keystore was winning,
     *   or null when the provider order was already sane and nothing was done.
     * @property fallbackRegistered true when a working provider had to be added
     *   because demotion alone left the keystore serving the lookup.
     * @property unresolved algorithms *still* served by the keystore afterwards.
     *   Non-empty means the connection will fail and we know it in advance.
     */
    data class Outcome(
        val trigger: String?,
        val fallbackRegistered: Boolean,
        val unresolved: List<String>,
    )

    /**
     * True when [providerName] must not be serving an unpinned lookup for raw
     * key material. Pure so the policy is testable without a live JCE.
     */
    internal fun shouldDemote(providerName: String?): Boolean = providerName == KEYSTORE_PROVIDER

    /**
     * Put the provider list into a state where no raw-key algorithm resolves to
     * AndroidKeyStore, if we can. Safe to call more than once; does nothing when
     * the order is already sane, which is the case on most devices.
     *
     * @param probe resolves an algorithm to the provider name that would serve
     *   it, or null when the algorithm is unavailable.
     * @param demote moves the named provider to the end of the list.
     * @param registerFallback adds a provider that implements these algorithms,
     *   returning whether one is now registered.
     */
    internal fun applyWith(
        probe: (String) -> String?,
        demote: (String) -> Boolean,
        registerFallback: () -> Boolean = { false },
    ): Outcome {
        val trigger = RAW_KEY_ALGORITHMS.firstOrNull { shouldDemote(probe(it)) }
            ?: return Outcome(trigger = null, fallbackRegistered = false, unresolved = emptyList())

        demote(KEYSTORE_PROVIDER)
        var unresolved = RAW_KEY_ALGORITHMS.filter { shouldDemote(probe(it)) }
        var fallbackRegistered = false

        if (unresolved.isNotEmpty()) {
            // Nothing else offers the algorithm, so last place is still first
            // place. Add an implementation, then put the keystore behind it.
            fallbackRegistered = registerFallback()
            if (fallbackRegistered) {
                demote(KEYSTORE_PROVIDER)
                unresolved = RAW_KEY_ALGORITHMS.filter { shouldDemote(probe(it)) }
            }
        }
        return Outcome(trigger, fallbackRegistered, unresolved)
    }

    /** Production entry point — call once during app start. */
    fun apply(): Outcome {
        val outcome = applyWith(
            probe = ::providerFor,
            demote = ::moveToEnd,
            registerFallback = ::registerBouncyCastle,
        )
        if (outcome.trigger == null) return outcome

        // Report where every raw-key algorithm ended up, not just that we acted.
        // The first attempt at this bug shipped a reorder that demonstrably ran
        // and demonstrably did not help, and the log could not say why (#451).
        val resolved = RAW_KEY_ALGORITHMS.joinToString(", ") { "$it=${providerFor(it) ?: "unavailable"}" }
        Log.w(
            TAG,
            "$KEYSTORE_PROVIDER was first for an unpinned ${outcome.trigger} KeyFactory (#451) — " +
                "reordered, fallbackRegistered=${outcome.fallbackRegistered}; now: $resolved",
        )
        if (outcome.unresolved.isNotEmpty()) {
            Log.e(
                TAG,
                "still served by $KEYSTORE_PROVIDER after reordering: ${outcome.unresolved} — " +
                    "sshlib connections using these will fail (#451)",
            )
        }
        return outcome
    }

    private fun providerFor(algorithm: String): String? =
        runCatching { KeyFactory.getInstance(algorithm).provider.name }.getOrNull()

    private fun moveToEnd(name: String): Boolean = runCatching {
        val provider: Provider = Security.getProvider(name) ?: return@runCatching false
        Security.removeProvider(name)
        // addProvider appends, so the provider stays available by name for
        // explicit requests while no longer winning unpinned ones.
        Security.addProvider(provider) != -1
    }.getOrDefault(false)

    /**
     * Register the bundled BouncyCastle so these algorithms have a real
     * implementation to resolve to. Android preinstalls a cut-down provider
     * under the same name, which is why the first add can be refused; replacing
     * it is deliberate and only ever happens on a device that has already shown
     * it cannot resolve the algorithm any other way.
     */
    private fun registerBouncyCastle(): Boolean = runCatching {
        val bundled = BouncyCastleProvider()
        if (Security.addProvider(bundled) != -1) return@runCatching true
        Security.removeProvider(bundled.name)
        Security.addProvider(bundled) != -1
    }.getOrDefault(false)
}
