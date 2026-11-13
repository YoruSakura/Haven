package sh.haven.feature.rdp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #504: Polish-programmers AltGr synthesis — non-ASCII letters become
 * AltGr(+Shift)+base-key sequences a scancodes-only server (VirtualBox
 * VRDP) delivers and the guest keymap resolves.
 */
class RdpAltGrSynthesisTest {

    private fun record(ch: Char, klid: UInt = POLISH_PROGRAMMERS_KLID): List<Pair<Int, Boolean>> {
        val events = mutableListOf<Pair<Int, Boolean>>()
        var unicodeUsed = false
        typeRdpChar(
            ch = ch,
            sendKey = { sc, pressed -> events.add(sc to pressed) },
            sendUnicode = { unicodeUsed = true },
            layoutKlid = klid,
        )
        if (unicodeUsed) events.add(-1 to false)
        return events
    }

    @Test
    fun `lowercase polish letter rides AltGr around the base key`() {
        assertEquals(
            listOf(
                SC_ALTGR_PUBLIC to true,
                0x1E to true, 0x1E to false,
                SC_ALTGR_PUBLIC to false,
            ),
            record('ą'),
        )
    }

    @Test
    fun `uppercase polish letter adds shift inside the AltGr hold`() {
        assertEquals(
            listOf(
                SC_ALTGR_PUBLIC to true,
                SC_SHIFT_L_PUBLIC to true,
                0x2C to true, 0x2C to false,
                SC_SHIFT_L_PUBLIC to false,
                SC_ALTGR_PUBLIC to false,
            ),
            record('Ż'),
        )
    }

    @Test
    fun `every polish national letter maps, both cases`() {
        for (ch in "ąćęłńóśźż" + "ĄĆĘŁŃÓŚŹŻ") {
            val events = record(ch)
            assertEquals("no unicode fallback for $ch", false, events.any { it.first == -1 })
            assertEquals("AltGr framed for $ch", SC_ALTGR_PUBLIC, events.first().first)
        }
    }

    @Test
    fun `ascii still uses the plain scancode path`() {
        val events = record('a')
        assertEquals(listOf(0x1E to true, 0x1E to false), events)
    }

    @Test
    fun `unlisted layout falls back to unicode`() {
        val events = record('ą', klid = 0x0409u)
        assertEquals(listOf(-1 to false), events)
        assertNull(altGrCharToRdpScancode('ą', 0x0409u))
    }

    @Test
    fun `chars outside the overlay fall back to unicode under polish too`() {
        assertEquals(listOf(-1 to false), record('é'))
    }
}
