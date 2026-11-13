package sh.haven.core.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards #245.3: rebuilding the toolbar from the settings editor must preserve
 * the existing on-toolbar order. The old path rewrote rows in enum order, so
 * toggling one key reset a hand-arranged toolbar back to defaults.
 */
class ToolbarEditorOpsTest {

    private fun bi(k: ToolbarKey) = ToolbarItem.BuiltIn(k)
    private fun custom(label: String) = ToolbarItem.Custom(label, "$label\n")

    /** Convenience: every ToolbarKey off unless overridden. */
    private fun rows(vararg pairs: Pair<ToolbarKey, Int?>): Map<ToolbarKey, Int?> =
        ToolbarKey.entries.associateWith { null } + pairs

    @Test
    fun `existing order is preserved when an unrelated key is toggled`() {
        // A deliberately non-enum order on row 1.
        val previous = ToolbarLayout(
            listOf(
                listOf(bi(ToolbarKey.ESC_KEY), bi(ToolbarKey.TAB_KEY), bi(ToolbarKey.KEYBOARD)),
                listOf(bi(ToolbarKey.CTRL)),
            ),
        )
        // User turns ALT on (row 2); row 1 assignments unchanged.
        val builtinRows = rows(
            ToolbarKey.ESC_KEY to 0,
            ToolbarKey.TAB_KEY to 0,
            ToolbarKey.KEYBOARD to 0,
            ToolbarKey.CTRL to 1,
            ToolbarKey.ALT to 1,
        )
        val (row1, row2) = ToolbarEditorOps.rebuildRows(previous, builtinRows, emptyList())

        // Row 1 keeps Esc, Tab, Keyboard in that order (NOT enum order, which
        // would be Keyboard, Esc, Tab).
        assertEquals(listOf(bi(ToolbarKey.ESC_KEY), bi(ToolbarKey.TAB_KEY), bi(ToolbarKey.KEYBOARD)), row1)
        // Newly-added Alt is appended after the kept Ctrl.
        assertEquals(listOf(bi(ToolbarKey.CTRL), bi(ToolbarKey.ALT)), row2)
    }

    @Test
    fun `removing a key keeps the rest in order`() {
        val previous = ToolbarLayout(
            listOf(listOf(bi(ToolbarKey.ESC_KEY), bi(ToolbarKey.TAB_KEY), bi(ToolbarKey.PASTE)), emptyList()),
        )
        val builtinRows = rows(ToolbarKey.ESC_KEY to 0, ToolbarKey.PASTE to 0) // Tab off
        val (row1, _) = ToolbarEditorOps.rebuildRows(previous, builtinRows, emptyList())
        assertEquals(listOf(bi(ToolbarKey.ESC_KEY), bi(ToolbarKey.PASTE)), row1)
    }

    @Test
    fun `custom snippet order is preserved and OFF customs are dropped from rows`() {
        val deploy = custom("deploy")
        val restart = custom("restart")
        val previous = ToolbarLayout(
            listOf(listOf(deploy, bi(ToolbarKey.ESC_KEY), restart), emptyList()),
        )
        // restart toggled OFF (null); deploy stays on row 1; esc stays.
        val builtinRows = rows(ToolbarKey.ESC_KEY to 0)
        val customRows = listOf(deploy to 0, restart to null)
        val (row1, _) = ToolbarEditorOps.rebuildRows(previous, builtinRows, customRows)
        assertEquals(listOf(deploy, bi(ToolbarKey.ESC_KEY)), row1)
    }

    @Test
    fun `key moved between rows lands at the end of the target row, no duplicate`() {
        val previous = ToolbarLayout(
            listOf(listOf(bi(ToolbarKey.ESC_KEY), bi(ToolbarKey.CTRL)), listOf(bi(ToolbarKey.SHIFT))),
        )
        // Ctrl moves from row1 to row2.
        val builtinRows = rows(ToolbarKey.ESC_KEY to 0, ToolbarKey.SHIFT to 1, ToolbarKey.CTRL to 1)
        val (row1, row2) = ToolbarEditorOps.rebuildRows(previous, builtinRows, emptyList())
        assertEquals(listOf(bi(ToolbarKey.ESC_KEY)), row1)
        assertEquals(listOf(bi(ToolbarKey.SHIFT), bi(ToolbarKey.CTRL)), row2)
    }
}
