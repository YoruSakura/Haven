package sh.haven.core.vnc.rendering

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import sh.haven.core.vnc.ColorDepth
import sh.haven.core.vnc.VncConfig
import sh.haven.core.vnc.VncSession
import sh.haven.core.vnc.protocol.Encoding
import sh.haven.core.vnc.protocol.FramebufferUpdate
import sh.haven.core.vnc.protocol.PixelFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream

/**
 * CopyRect bounds (#471): a server can emit a CopyRect whose source rect
 * exceeds the live framebuffer (stale rect racing a desktop resize, or a
 * plain server bug). That must clamp — blitting the in-bounds portion —
 * not throw the raw `IllegalArgumentException("y + height must be <=
 * bitmap.height()")` that then surfaces verbatim as the tab's error text.
 */
@RunWith(RobolectricTestRunner::class)
class FramebufferCopyRectTest {

    private lateinit var session: VncSession
    private lateinit var fb: Framebuffer
    private var lastBitmap: Bitmap? = null

    @Before
    fun setUp() {
        val config = VncConfig().apply {
            colorDepth = ColorDepth.BPP_24_TRUE
            onScreenUpdate = { bmp -> lastBitmap = bmp }
        }
        session = VncSession(config, InputStream.nullInputStream(), ByteArrayOutputStream())
        session.pixelFormat = PixelFormat(
            bitsPerPixel = 32, depth = 24, bigEndian = false, trueColor = true,
            redMax = 255, greenMax = 255, blueMax = 255,
            redShift = 16, greenShift = 8, blueShift = 0,
        )
        session.framebufferWidth = 64
        session.framebufferHeight = 64
        fb = Framebuffer(session)
    }

    @Test
    fun `out-of-bounds CopyRect clamps to the framebuffer instead of throwing`() {
        val red = 0xFFFF0000.toInt()
        val out = ByteArrayOutputStream()
        val d = DataOutputStream(out)

        // Rect 1 — RAW: paint a 16x16 red block at (48,48), the frame's
        // bottom-right corner. BGRX little-endian.
        d.writeShort(48); d.writeShort(48); d.writeShort(16); d.writeShort(16)
        d.writeInt(Encoding.RAW.code)
        repeat(16 * 16) { d.write(byteArrayOf(0, 0, 0xFF.toByte(), 0)) }

        // Rect 2 — COPYRECT: dest (0,0) 32x32 from src (48,48). The source
        // overruns the 64px frame by 16px in both axes — the exact shape
        // that threw before the clamp.
        d.writeShort(0); d.writeShort(0); d.writeShort(32); d.writeShort(32)
        d.writeInt(Encoding.COPYRECT.code)
        d.writeShort(48); d.writeShort(48)

        session.inputStream = ByteArrayInputStream(out.toByteArray())
        fb.processUpdate(FramebufferUpdate(numberOfRectangles = 2)) // must not throw

        // The in-bounds 16x16 portion was still copied to the destination…
        val bmp = lastBitmap!!
        assertEquals(red, bmp.getPixel(0, 0))
        assertEquals(red, bmp.getPixel(15, 15))
        // …and pixels beyond the clamped copy stay untouched (a fresh
        // ARGB_8888 frame is transparent 0).
        assertEquals(0, bmp.getPixel(20, 20))
    }
}
