package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #504 follow-up: base-remapped layouts (German QWERTZ, French AZERTY, UK).
 * The announced KLID makes the server resolve scancodes through that layout,
 * so each layout carries its own full char→position table; chars needing a
 * dead-key composition fall to unicode, never to the US table.
 */
class RdpBaseRemapTest {

    private data class Typed(val events: List<Pair<Int, Boolean>>, val unicode: Boolean)

    private fun type(ch: Char, klid: UInt): Typed {
        val events = mutableListOf<Pair<Int, Boolean>>()
        var unicode = false
        typeRdpChar(
            ch = ch,
            sendKey = { sc, pressed -> events.add(sc to pressed) },
            sendUnicode = { unicode = true },
            layoutKlid = klid,
        )
        return Typed(events, unicode)
    }

    private fun plainTap(sc: Int) = listOf(sc to true, sc to false)

    private fun shiftTap(sc: Int) = listOf(
        SC_SHIFT_L_PUBLIC to true, sc to true, sc to false, SC_SHIFT_L_PUBLIC to false,
    )

    private fun altGrTap(sc: Int) = listOf(
        SC_ALTGR_PUBLIC to true, sc to true, sc to false, SC_ALTGR_PUBLIC to false,
    )

    // --- German QWERTZ ---

    @Test
    fun `german swaps y and z`() {
        assertEquals(plainTap(0x15), type('z', GERMAN_QWERTZ_KLID).events)
        assertEquals(plainTap(0x2C), type('y', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x15), type('Z', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german umlauts sit on their own keys`() {
        assertEquals(plainTap(0x28), type('ä', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x28), type('Ä', GERMAN_QWERTZ_KLID).events)
        assertEquals(plainTap(0x0C), type('ß', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german shifted digit row symbols`() {
        assertEquals(shiftTap(0x08), type('/', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x0B), type('=', GERMAN_QWERTZ_KLID).events)
        assertEquals(shiftTap(0x0C), type('?', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german altgr characters`() {
        assertEquals(altGrTap(0x10), type('@', GERMAN_QWERTZ_KLID).events)
        assertEquals(altGrTap(0x12), type('€', GERMAN_QWERTZ_KLID).events)
        assertEquals(altGrTap(0x09), type('[', GERMAN_QWERTZ_KLID).events)
        assertEquals(altGrTap(0x0C), type('\\', GERMAN_QWERTZ_KLID).events)
    }

    @Test
    fun `german dead-key chars fall to unicode, not US positions`() {
        for (ch in charArrayOf('`', '^', '´')) {
            val typed = type(ch, GERMAN_QWERTZ_KLID)
            assertTrue("$ch should use unicode", typed.unicode)
            assertTrue("$ch must not emit scancodes", typed.events.isEmpty())
        }
    }

    // --- French AZERTY ---

    @Test
    fun `french remaps a q z w m`() {
        assertEquals(plainTap(0x10), type('a', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x1E), type('q', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x11), type('z', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x2C), type('w', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x27), type('m', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french digits need shift and accents ride the plain row`() {
        assertEquals(shiftTap(0x02), type('1', FRENCH_AZERTY_KLID).events)
        assertEquals(shiftTap(0x0B), type('0', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x03), type('é', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x08), type('è', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x0A), type('ç', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x0B), type('à', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x28), type('ù', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french altgr characters`() {
        assertEquals(altGrTap(0x0B), type('@', FRENCH_AZERTY_KLID).events)
        assertEquals(altGrTap(0x0A), type('^', FRENCH_AZERTY_KLID).events)
        assertEquals(altGrTap(0x12), type('€', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french punctuation trio`() {
        assertEquals(plainTap(0x32), type(',', FRENCH_AZERTY_KLID).events)
        assertEquals(shiftTap(0x32), type('?', FRENCH_AZERTY_KLID).events)
        assertEquals(plainTap(0x35), type('!', FRENCH_AZERTY_KLID).events)
    }

    @Test
    fun `french capital accents and dead keys fall to unicode`() {
        for (ch in charArrayOf('É', 'È', 'Ç', 'À', 'Ù', '~', '`')) {
            val typed = type(ch, FRENCH_AZERTY_KLID)
            assertTrue("$ch should use unicode", typed.unicode)
            assertTrue("$ch must not emit scancodes", typed.events.isEmpty())
        }
    }

    // --- UK ---

    @Test
    fun `uk swaps at and quote, keeps letters at US positions`() {
        assertEquals(shiftTap(0x28), type('@', UK_ENGLISH_KLID).events)
        assertEquals(shiftTap(0x03), type('"', UK_ENGLISH_KLID).events)
        assertEquals(plainTap(0x2B), type('#', UK_ENGLISH_KLID).events)
        assertEquals(plainTap(SC_ISO_102), type('\\', UK_ENGLISH_KLID).events)
        assertEquals(shiftTap(0x04), type('£', UK_ENGLISH_KLID).events)
        assertEquals(plainTap(0x1E), type('a', UK_ENGLISH_KLID).events)
    }

    // --- Regressions on the existing paths ---

    @Test
    fun `us default keeps the pre-existing mapping`() {
        assertEquals(plainTap(0x2C), type('z', 0x0409u).events)
        assertEquals(shiftTap(0x03), type('@', 0x0409u).events)
    }

    @Test
    fun `polish overlay is untouched by the remap layer`() {
        val typed = type('ą', POLISH_PROGRAMMERS_KLID)
        assertEquals(
            listOf(
                SC_ALTGR_PUBLIC to true,
                0x1E to true, 0x1E to false,
                SC_ALTGR_PUBLIC to false,
            ),
            typed.events,
        )
    }

    @Test
    fun `control keys work in remapped layouts`() {
        assertEquals(plainTap(0x39), type(' ', GERMAN_QWERTZ_KLID).events)
        assertEquals(plainTap(0x1C), type('\n', FRENCH_AZERTY_KLID).events)
    }
}
