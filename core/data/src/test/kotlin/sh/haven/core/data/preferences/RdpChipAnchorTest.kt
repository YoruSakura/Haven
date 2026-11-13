package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Snap-to-nearest-anchor math for the draggable fullscreen session chip
 * (#528 follow-up). Six anchors in thirds, unlike the terminal button's four
 * corners, so the top-centre default stays reachable after a drag. Pure — no
 * Compose, no device.
 */
class RdpChipAnchorTest {

    // A 900x600 box; thirds break at x=300 and x=600, the half at y=300.
    private val w = 900f
    private val h = 600f

    @Test
    fun `top thirds snap start, centre, end`() {
        assertEquals(RdpChipAnchor.TOP_START, RdpChipAnchor.nearest(150f, 100f, w, h))
        assertEquals(RdpChipAnchor.TOP_CENTER, RdpChipAnchor.nearest(450f, 100f, w, h))
        assertEquals(RdpChipAnchor.TOP_END, RdpChipAnchor.nearest(750f, 100f, w, h))
    }

    @Test
    fun `bottom thirds snap start, centre, end`() {
        assertEquals(RdpChipAnchor.BOTTOM_START, RdpChipAnchor.nearest(150f, 500f, w, h))
        assertEquals(RdpChipAnchor.BOTTOM_CENTER, RdpChipAnchor.nearest(450f, 500f, w, h))
        assertEquals(RdpChipAnchor.BOTTOM_END, RdpChipAnchor.nearest(750f, 500f, w, h))
    }

    @Test
    fun `boundaries resolve deterministically toward the later anchor`() {
        // x == w/3 is not < w/3 → centre; x == 2w/3 is not < 2w/3 → end;
        // y == h/2 is not < h/2 → bottom. Documents the boundary so a drop
        // exactly on a third-line is stable.
        assertEquals(RdpChipAnchor.BOTTOM_CENTER, RdpChipAnchor.nearest(300f, 300f, w, h))
        assertEquals(RdpChipAnchor.BOTTOM_END, RdpChipAnchor.nearest(600f, 300f, w, h))
    }

    @Test
    fun `ids round-trip through fromId and unknown ids are null`() {
        RdpChipAnchor.entries.forEach { assertEquals(it, RdpChipAnchor.fromId(it.id)) }
        assertEquals(null, RdpChipAnchor.fromId("middle_earth"))
    }
}
