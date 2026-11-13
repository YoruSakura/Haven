package sh.haven.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #422 rung 1. `RdpSession` now hands the viewer ONE bitmap and mutates it in
 * place, which removed an 8.29 MB allocation and a full-frame copy per update.
 * The cost is that the bitmap's identity stops changing, so nothing downstream
 * would repaint on its own — a counter read inside the draw scope is what
 * invalidates it, and if that read is missing the picture silently freezes on
 * the first frame.
 *
 * This counts actual draw executions rather than comparing screenshots.
 * `captureToImage()` re-renders the hierarchy as a side effect of capturing, so
 * a screenshot always shows current pixels whether or not anything was
 * invalidated — a screenshot test here passes even with the invalidation
 * removed, which is exactly the false green this replaces.
 */
@RunWith(AndroidJUnit4::class)
class RdpStableFrameRedrawTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    /** Mirrors RdpViewer's arrangement: stable ImageBitmap + `State<Long>` read
     *  only inside the draw scope. [readSeqInDraw] false is the broken wiring. */
    private fun drawsAfterSeqBump(readSeqInDraw: Boolean): Pair<Int, Int> {
        val stable = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        stable.eraseColor(Color.RED)
        val seq = mutableStateOf(0L)
        val draws = AtomicInteger(0)

        composeTestRule.setContent {
            val image: ImageBitmap = remember(stable) { stable.asImageBitmap() }
            val seqState: State<Long> = seq
            Canvas(Modifier.size(64.dp)) {
                if (readSeqInDraw) {
                    @Suppress("UNUSED_EXPRESSION")
                    seqState.value
                }
                draws.incrementAndGet()
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(64, 64),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
        }
        composeTestRule.waitForIdle()
        val before = draws.get()

        // Exactly what refreshBitmap() does: same instance, new pixels, counter
        // bumped, no new bitmap published.
        stable.eraseColor(Color.BLUE)
        seq.value = seq.value + 1
        composeTestRule.waitForIdle()

        return before to draws.get()
    }

    @Test
    fun bumpingTheCounterRedrawsTheStableFrame() {
        val (before, after) = drawsAfterSeqBump(readSeqInDraw = true)
        assertTrue("the frame should have been drawn at least once initially", before > 0)
        assertTrue(
            "bumping frameSeq did not trigger a redraw (drew $before times, still $after) — " +
                "an in-place frame update would never reach the screen",
            after > before,
        )
    }

    @Test
    fun withoutTheDrawScopeReadTheFrameWouldFreeze() {
        // The converse, so the test above cannot pass for an unrelated reason:
        // drop the read and the redraw must stop happening.
        val (before, after) = drawsAfterSeqBump(readSeqInDraw = false)
        assertEquals(
            "without reading frameSeq in the draw scope there should be no redraw",
            before,
            after,
        )
    }

    @Test
    fun aMutatedBitmapIsNotServedFromAStaleTexture() {
        // Separately: when a redraw does happen, it must show the new pixels
        // rather than a cached upload of the old ones.
        val stable = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        stable.eraseColor(Color.RED)
        val seq = mutableStateOf(0L)

        composeTestRule.setContent {
            val image: ImageBitmap = remember(stable) { stable.asImageBitmap() }
            val seqState: State<Long> = seq
            Canvas(Modifier.size(64.dp)) {
                @Suppress("UNUSED_EXPRESSION")
                seqState.value
                drawImage(
                    image = image,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(64, 64),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            }
        }
        composeTestRule.waitForIdle()
        val before = composeTestRule.onRoot().captureToImage().toPixelMap()
        assertEquals(Color.RED, before[2, 2].toArgb())

        stable.eraseColor(Color.BLUE)
        seq.value = seq.value + 1
        composeTestRule.waitForIdle()

        val after = composeTestRule.onRoot().captureToImage().toPixelMap()
        assertEquals(
            "in-place Bitmap mutation was not visible — the renderer served a cached texture",
            Color.BLUE,
            after[2, 2].toArgb(),
        )
    }
}
