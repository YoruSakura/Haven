package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditModeControlsPlacementTest {

    @Test
    fun `fromId round-trips every entry`() {
        EditModeControlsPlacement.entries.forEach { placement ->
            assertEquals(placement, EditModeControlsPlacement.fromId(placement.id))
        }
    }

    @Test
    fun `ids are the stable persisted strings`() {
        assertEquals("split", EditModeControlsPlacement.SPLIT.id)
        assertEquals("left", EditModeControlsPlacement.LEFT.id)
        assertEquals("right", EditModeControlsPlacement.RIGHT.id)
    }

    @Test
    fun `fromId returns null for unknown id`() {
        assertNull(EditModeControlsPlacement.fromId("middle"))
        assertNull(EditModeControlsPlacement.fromId(""))
    }
}
