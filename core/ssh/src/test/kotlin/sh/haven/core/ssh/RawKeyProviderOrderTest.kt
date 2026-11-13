package sh.haven.core.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #451: on some Android builds an unpinned `KeyFactory.getInstance("X25519")`
 * resolves to AndroidKeyStore, which can only ever return keys held in the
 * keystore — so sshlib's key exchange dies converting the server's ephemeral
 * public key or its ed25519 host key. Upstream: connectbot/cbssh#246.
 */
class RawKeyProviderOrderTest {

    /**
     * A probe that answers differently once the keystore has been demoted —
     * the ordinary case, where another provider was there all along and simply
     * lost the race. Modelling this is the point: a probe with a fixed answer
     * cannot tell "demotion helped" from "demotion did nothing".
     */
    private class DemotableProbe(private val brokenFor: Set<String>) {
        var demoted = false
        operator fun invoke(algorithm: String): String =
            if (algorithm in brokenFor && !demoted) "AndroidKeyStore" else "AndroidOpenSSL"
    }

    @Test
    fun `AndroidKeyStore must not serve an unpinned raw-key lookup`() {
        assertTrue(RawKeyProviderOrder.shouldDemote("AndroidKeyStore"))
    }

    /** Every other provider is left alone — this must not reorder the JCE at large. */
    @Test
    fun `working providers are left where they are`() {
        assertFalse(RawKeyProviderOrder.shouldDemote("AndroidOpenSSL"))
        assertFalse(RawKeyProviderOrder.shouldDemote("BC"))
        assertFalse(RawKeyProviderOrder.shouldDemote("Conscrypt"))
        assertFalse(RawKeyProviderOrder.shouldDemote(null))
    }

    /** The ordinary affected device: demote once, naming the algorithm that proved it. */
    @Test
    fun `demotes when the keystore wins an X25519 lookup`() {
        val probe = DemotableProbe(setOf("X25519"))
        val demoted = mutableListOf<String>()
        val outcome = RawKeyProviderOrder.applyWith(
            probe = { probe(it) },
            demote = { demoted += it; probe.demoted = true; true },
        )
        assertEquals("X25519", outcome.trigger)
        assertEquals(listOf("AndroidKeyStore"), demoted)
        assertTrue("demotion sufficed; nothing more to do", outcome.unresolved.isEmpty())
        assertFalse("must not add a provider when demotion worked", outcome.fallbackRegistered)
    }

    /** Most devices: nothing is touched at all. */
    @Test
    fun `does nothing when the provider order is already sane`() {
        val demoted = mutableListOf<String>()
        val outcome = RawKeyProviderOrder.applyWith(
            probe = { "AndroidOpenSSL" },
            demote = { demoted += it; true },
            registerFallback = { error("must not register a provider on a healthy device") },
        )
        assertNull(outcome.trigger)
        assertTrue("must not reorder providers on a healthy device", demoted.isEmpty())
    }

    /** An algorithm the device lacks must not be mistaken for a broken one. */
    @Test
    fun `an unavailable algorithm is not a reason to reorder`() {
        val demoted = mutableListOf<String>()
        val outcome = RawKeyProviderOrder.applyWith(
            probe = { null },
            demote = { demoted += it; true },
        )
        assertNull(outcome.trigger)
        assertTrue(demoted.isEmpty())
    }

    /**
     * Slayerx96's device (#451, v5.83.20): the reorder ran and the lookup still
     * resolved to the keystore, because nothing else offered the algorithm.
     * Demotion cannot fix that — being last is still being chosen — so a
     * provider that implements it has to be added and placed ahead.
     */
    @Test
    fun `registers a fallback provider when the keystore is the only one offering the algorithm`() {
        var fallbackPresent = false
        val demoted = mutableListOf<String>()
        val outcome = RawKeyProviderOrder.applyWith(
            probe = { if (fallbackPresent) "BC" else "AndroidKeyStore" },
            demote = { demoted += it; true },
            registerFallback = { fallbackPresent = true; true },
        )
        assertEquals("X25519", outcome.trigger)
        assertTrue("a fallback was required here", outcome.fallbackRegistered)
        assertTrue("every algorithm resolves after the fallback", outcome.unresolved.isEmpty())
        assertEquals(
            "the keystore must be demoted again, behind the provider just added",
            listOf("AndroidKeyStore", "AndroidKeyStore"),
            demoted,
        )
    }

    /**
     * The state worth naming out loud: nothing we can do helped. Reporting it
     * beats a silent success, because the connection is going to fail and the
     * log should say so before the user finds out.
     */
    @Test
    fun `reports the algorithms still stuck on the keystore when nothing helps`() {
        val outcome = RawKeyProviderOrder.applyWith(
            probe = { "AndroidKeyStore" },
            demote = { true },
            registerFallback = { false },
        )
        assertEquals("X25519", outcome.trigger)
        assertFalse(outcome.fallbackRegistered)
        assertEquals(RawKeyProviderOrder.RAW_KEY_ALGORITHMS, outcome.unresolved)
    }

    /** A refused reorder is reported, not silently swallowed. */
    @Test
    fun `reports the trigger even when the reorder fails`() {
        val outcome = RawKeyProviderOrder.applyWith(
            probe = { "AndroidKeyStore" },
            demote = { false },
            registerFallback = { false },
        )
        assertEquals("X25519", outcome.trigger)
    }
}
