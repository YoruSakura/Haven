package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural invariants for the terminal colour schemes.
 *
 * The palettes are hand-transcribed 16-entry arrays, so the failure this guards
 * is a transcription slip rather than a logic bug: a 15-entry array survives
 * compilation and only throws when someone selects that scheme and
 * `ansiPaletteArgb()` walks off the end. Adding a scheme (#516 added three) is
 * exactly when that happens, and it would ship green.
 *
 * Deliberately not asserted: that any particular colour is "right". Nobody can
 * unit-test taste, and the upstream sources are cited at each entry instead.
 */
class TerminalColorSchemeTest {

    private val schemes = UserPreferencesRepository.TerminalColorScheme.entries

    @Test
    fun `every scheme carries a full 16-colour ANSI palette`() {
        for (scheme in schemes) {
            assertEquals(
                "${scheme.name} has the wrong ANSI palette size",
                16,
                scheme.ansi.size,
            )
            assertEquals(
                "${scheme.name} lost entries converting to ARGB",
                16,
                scheme.ansiPaletteArgb().size,
            )
        }
    }

    /**
     * Alpha is what makes a colour visible at all: 0x00RRGGBB is fully
     * transparent, and a dropped `FF` prefix is an easy slip to make in a
     * column of hex literals.
     */
    @Test
    fun `every colour is fully opaque`() {
        for (scheme in schemes) {
            val channels = scheme.ansi.toMutableList().apply {
                add(scheme.background)
                add(scheme.foreground)
            }
            for ((index, colour) in channels.withIndex()) {
                assertEquals(
                    "${scheme.name} colour $index is not opaque: 0x${colour.toString(16)}",
                    0xFFL,
                    (colour ushr 24) and 0xFF,
                )
            }
        }
    }

    /**
     * A scheme whose text matches its background renders an invisible terminal.
     * MATERIAL_YOU is exempt: its longs are sentinels and the real pair comes
     * from the live system theme (see [UserPreferencesRepository.TerminalColorScheme.isDynamic]).
     */
    @Test
    fun `foreground differs from background`() {
        for (scheme in schemes.filterNot { it.isDynamic }) {
            assertNotEquals(
                "${scheme.name} is unreadable — foreground equals background",
                scheme.background,
                scheme.foreground,
            )
        }
    }

    @Test
    fun `labels are unique and non-blank`() {
        val labels = schemes.map { it.label }
        assertEquals(
            "two schemes share a label, so the picker shows a duplicate row",
            labels.size,
            labels.toSet().size,
        )
        assertTrue("a scheme has a blank label", labels.none { it.isBlank() })
    }

    /**
     * Persistence stores `scheme.name`, so an unknown or absent value has to
     * fall back rather than throw — a renamed scheme must not brick startup.
     */
    @Test
    fun `fromString round-trips every scheme and falls back otherwise`() {
        for (scheme in schemes) {
            assertEquals(
                scheme,
                UserPreferencesRepository.TerminalColorScheme.fromString(scheme.name),
            )
        }
        for (bogus in listOf(null, "", "NOPE", "campbell")) {
            assertEquals(
                "unknown value $bogus should fall back to HAVEN",
                UserPreferencesRepository.TerminalColorScheme.HAVEN,
                UserPreferencesRepository.TerminalColorScheme.fromString(bogus),
            )
        }
    }

    /** #516 asked for these by name; the request is only met if they exist. */
    @Test
    fun `the schemes requested in 516 are present`() {
        val labels = schemes.map { it.label }
        for (wanted in listOf("Campbell", "Modern Dark", "Modern Light")) {
            assertTrue("#516 asked for $wanted, which is missing", wanted in labels)
        }
    }
}
