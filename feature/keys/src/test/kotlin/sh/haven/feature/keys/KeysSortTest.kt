package sh.haven.feature.keys

import org.junit.Assert.assertEquals
import org.junit.Test
import sh.haven.core.data.db.entities.SshKey

/**
 * #460: sorting for the SSH-keys list. Sorting is a *view* over the
 * user-defined order (#238), which is why [KeySort.MANUAL] must pass the
 * list through untouched — the manual order is already applied upstream by
 * `KeysViewModel.keys`, and re-sorting it here would silently discard it.
 */
class KeysSortTest {

    private fun key(label: String, createdAt: Long) = SshKey(
        id = "$label-$createdAt",
        label = label,
        keyType = "ssh-ed25519",
        privateKeyBytes = ByteArray(0),
        publicKeyOpenSsh = "ssh-ed25519 AAAA",
        fingerprintSha256 = "SHA256:$label",
        createdAt = createdAt,
    )

    private val zeus = key("Zeus", 3_000L)
    private val acme = key("acme", 1_000L)
    private val bravo = key("Bravo", 2_000L)
    private val keys = listOf(zeus, acme, bravo)

    private fun labels(sort: KeySort) = sortKeys(keys, sort).map { it.label }

    @Test
    fun `manual sort leaves the user-defined order untouched`() {
        assertEquals(listOf("Zeus", "acme", "Bravo"), labels(KeySort.MANUAL))
    }

    /**
     * The case that makes this worth a comparator rather than `sortedBy`:
     * ASCII order puts every capital ahead of every lowercase letter, so a
     * naive sort yields Bravo, Zeus, acme — "acme" last, which reads as
     * broken to anyone who names keys casually.
     */
    @Test
    fun `label sort is case-insensitive in both directions`() {
        assertEquals(listOf("acme", "Bravo", "Zeus"), labels(KeySort.LABEL_ASC))
        assertEquals(listOf("Zeus", "Bravo", "acme"), labels(KeySort.LABEL_DESC))
    }

    @Test
    fun `date sort orders by creation time in both directions`() {
        assertEquals(listOf("Zeus", "Bravo", "acme"), labels(KeySort.NEWEST_FIRST))
        assertEquals(listOf("acme", "Bravo", "Zeus"), labels(KeySort.OLDEST_FIRST))
    }

    /** Sorting must not add or drop keys — a sort that loses a key loses a credential. */
    @Test
    fun `every sort returns every key exactly once`() {
        KeySort.entries.forEach { sort ->
            assertEquals(
                "$sort changed the key set",
                keys.map { it.id }.sorted(),
                sortKeys(keys, sort).map { it.id }.sorted(),
            )
        }
    }

    @Test
    fun `flipping a sort swaps its direction and is its own inverse`() {
        KeySort.entries.forEach { sort ->
            assertEquals("$sort should round-trip", sort, sort.flipped().flipped())
        }
        assertEquals(KeySort.LABEL_DESC, KeySort.LABEL_ASC.flipped())
        assertEquals(KeySort.OLDEST_FIRST, KeySort.NEWEST_FIRST.flipped())
        // MANUAL has no direction to flip.
        assertEquals(KeySort.MANUAL, KeySort.MANUAL.flipped())
    }

    /**
     * The menu shows one entry per field. Tapping a different field switches
     * to it ascending; tapping the active field flips it. Tapping the entry
     * while it reads "Name (Z–A)" lands back on A–Z, which is the toggle —
     * a field has two directions, so "switch to this field" and "flip it"
     * coincide there.
     */
    @Test
    fun `selecting a sort field switches to it or toggles it`() {
        assertEquals(KeySort.LABEL_ASC, KeySort.MANUAL.select(KeySort.LABEL_ASC))
        assertEquals(KeySort.LABEL_DESC, KeySort.LABEL_ASC.select(KeySort.LABEL_ASC))
        assertEquals(KeySort.LABEL_ASC, KeySort.LABEL_DESC.select(KeySort.LABEL_ASC))
        // Switching fields never inherits the other field's direction.
        assertEquals(KeySort.NEWEST_FIRST, KeySort.LABEL_DESC.select(KeySort.NEWEST_FIRST))
        assertEquals(KeySort.MANUAL, KeySort.OLDEST_FIRST.select(KeySort.MANUAL))
    }

    /**
     * The persisted value is the enum name. An unreadable or stale
     * preference must fall back to the documented default rather than
     * throwing on a screen the user cannot then open.
     */
    @Test
    fun `parse falls back to manual for unknown values`() {
        assertEquals(KeySort.LABEL_ASC, KeySort.parse("LABEL_ASC"))
        assertEquals(KeySort.MANUAL, KeySort.parse(null))
        assertEquals(KeySort.MANUAL, KeySort.parse(""))
        assertEquals(KeySort.MANUAL, KeySort.parse("BY_VIBES"))
    }
}
