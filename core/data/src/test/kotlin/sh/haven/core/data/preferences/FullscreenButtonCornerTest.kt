package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The snap-to-nearest-corner math for the draggable fullscreen button (#445).
 * Pure — no Compose, no device — so the drop behaviour is verifiable in CI.
 */
class FullscreenButtonCornerTest {

    // A 1000x600 box; quarter points sit unambiguously in each corner.
    private val w = 1000f
    private val h = 600f

    @Test
    fun `top-left quarter snaps to TOP_START`() {
        assertEquals(FullscreenButtonCorner.TOP_START, FullscreenButtonCorner.nearest(250f, 150f, w, h))
    }

    @Test
    fun `top-right quarter snaps to TOP_END`() {
        assertEquals(FullscreenButtonCorner.TOP_END, FullscreenButtonCorner.nearest(750f, 150f, w, h))
    }

    @Test
    fun `bottom-left quarter snaps to BOTTOM_START`() {
        assertEquals(FullscreenButtonCorner.BOTTOM_START, FullscreenButtonCorner.nearest(250f, 450f, w, h))
    }

    @Test
    fun `bottom-right quarter snaps to BOTTOM_END`() {
        assertEquals(FullscreenButtonCorner.BOTTOM_END, FullscreenButtonCorner.nearest(750f, 450f, w, h))
    }

    @Test
    fun `exact centre resolves deterministically to the bottom-end`() {
        // center.x == w/2 is not < w/2 (end half); center.y == h/2 is not < h/2
        // (bottom half). Documents the boundary so a drop dead-centre is stable.
        assertEquals(FullscreenButtonCorner.BOTTOM_END, FullscreenButtonCorner.nearest(500f, 300f, w, h))
    }

    @Test
    fun `just across each midline flips the axis`() {
        assertEquals(FullscreenButtonCorner.TOP_START, FullscreenButtonCorner.nearest(499f, 299f, w, h))
        assertEquals(FullscreenButtonCorner.TOP_END, FullscreenButtonCorner.nearest(501f, 299f, w, h))
        assertEquals(FullscreenButtonCorner.BOTTOM_START, FullscreenButtonCorner.nearest(499f, 301f, w, h))
    }

    @Test
    fun `default is top-end (the original fixed position)`() {
        assertEquals(FullscreenButtonCorner.TOP_END, FullscreenButtonCorner.DEFAULT)
    }

    @Test
    fun `id round-trips and unknown ids are null`() {
        for (corner in FullscreenButtonCorner.entries) {
            assertEquals(corner, FullscreenButtonCorner.fromId(corner.id))
        }
        assertNull(FullscreenButtonCorner.fromId("nope"))
    }
}
