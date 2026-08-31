package sh.haven.core.wayland

import org.junit.Assert.assertEquals
import org.junit.Test

class WaylandImeKeyRouterTest {
    private data class EvdevEvent(val key: Int, val pressed: Int)

    private fun fixture(): Pair<WaylandImeKeyRouter, MutableList<EvdevEvent>> {
        val events = mutableListOf<EvdevEvent>()
        return WaylandImeKeyRouter { key, pressed ->
            events += EvdevEvent(key, pressed)
        } to events
    }

    @Test
    fun `single IME key down is a bounded tap even without key up`() {
        val (router, events) = fixture()

        router.onImeKeyDown(30) // A

        assertEquals(listOf(EvdevEvent(30, 1), EvdevEvent(30, 0)), events)
    }

    @Test
    fun `navigation key cannot remain held when IME omits key up`() {
        val (router, events) = fixture()

        router.onImeKeyDown(106) // Right arrow

        assertEquals(listOf(EvdevEvent(106, 1), EvdevEvent(106, 0)), events)
    }

    @Test
    fun `later IME key up does not emit a second release`() {
        val (router, events) = fixture()

        router.onImeKeyDown(30)
        router.onImeKeyUp(30)

        assertEquals(listOf(EvdevEvent(30, 1), EvdevEvent(30, 0)), events)
    }

    @Test
    fun `raw key echo matching committed text is suppressed`() {
        val (router, events) = fixture()

        router.sendTextCharacter('a')
        router.onImeKeyDown(30)
        router.onImeKeyUp(30)

        assertEquals(listOf(EvdevEvent(30, 1), EvdevEvent(30, 0)), events)
    }

    @Test
    fun `different key after committed text is preserved`() {
        val (router, events) = fixture()

        router.sendTextCharacter('a')
        router.onImeKeyDown(48) // B

        assertEquals(
            listOf(
                EvdevEvent(30, 1), EvdevEvent(30, 0),
                EvdevEvent(48, 1), EvdevEvent(48, 0),
            ),
            events,
        )
    }

    @Test
    fun `identical key in a later looper turn is not suppressed`() {
        val (router, events) = fixture()

        router.sendTextCharacter('a')
        router.drainTextEchoes()
        router.onImeKeyDown(30)

        assertEquals(
            listOf(
                EvdevEvent(30, 1), EvdevEvent(30, 0),
                EvdevEvent(30, 1), EvdevEvent(30, 0),
            ),
            events,
        )
    }

    @Test
    fun `uppercase text keeps shift bounded too`() {
        val (router, events) = fixture()

        router.sendTextCharacter('A')

        assertEquals(
            listOf(
                EvdevEvent(42, 1),
                EvdevEvent(30, 1), EvdevEvent(30, 0),
                EvdevEvent(42, 0),
            ),
            events,
        )
    }

    @Test
    fun `repeated IME downs remain repeated bounded taps`() {
        val (router, events) = fixture()

        router.onImeKeyDown(30)
        router.onImeKeyDown(30)

        assertEquals(
            listOf(
                EvdevEvent(30, 1), EvdevEvent(30, 0),
                EvdevEvent(30, 1), EvdevEvent(30, 0),
            ),
            events,
        )
    }

    @Test
    fun `virtual keyboard device is routed as IME input`() {
        assertEquals(true, isImeGeneratedKeyEvent(deviceId = -1, flags = 0))
    }

    @Test
    fun `soft keyboard flag is routed as IME input`() {
        assertEquals(true, isImeGeneratedKeyEvent(deviceId = 4, flags = 0x2))
    }

    @Test
    fun `physical keyboard keeps stateful down up routing`() {
        assertEquals(false, isImeGeneratedKeyEvent(deviceId = 4, flags = 0))
    }
}
