package sh.haven.core.rdp

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.random.Random

/**
 * The plane repack that replaced the in-Kotlin BT.601 conversion (#466).
 *
 * The conversion itself moved to Rust and is checked there against a golden
 * digest generated from the Kotlin it replaced, so the *colours* are covered.
 * What did **not** move is the awkward part: real decoders hand back planes
 * with row strides wider than the image, chroma that may be planar or
 * interleaved, and an output smaller than the size Haven asked for. That logic
 * lives here now, and these are the cases the old conversion test covered.
 *
 * Each case is compared against a naive reference written independently of the
 * bulk-copy fast path, so "the fast path and the slow path agree" is the
 * assertion rather than "it did something".
 */
class Avc420I420PackTest {

    /** Straightforward per-sample transcription of the intended behaviour. */
    private fun reference(
        out: ByteArray,
        offset: Int,
        buf: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        dstW: Int,
        dstH: Int,
        srcW: Int,
        srcH: Int,
    ) {
        var o = offset
        for (y in 0 until dstH) {
            val sy = if (y < srcH) y else srcH - 1
            for (x in 0 until dstW) {
                val sx = if (x < srcW) x else srcW - 1
                out[o++] = buf.get(sy * rowStride + sx * pixelStride)
            }
        }
    }

    private fun check(dstW: Int, dstH: Int, srcW: Int, srcH: Int, pixelStride: Int, seed: Int) {
        val rnd = Random(seed)
        // Row stride deliberately wider than the plane, as real decoders emit.
        val rowStride = srcW * pixelStride + 7
        val buf = ByteBuffer.wrap(ByteArray(rowStride * (srcH + 2)).also { rnd.nextBytes(it) })

        val expected = ByteArray(dstW * dstH)
        reference(expected, 0, buf, rowStride, pixelStride, dstW, dstH, srcW, srcH)

        val actual = ByteArray(dstW * dstH)
        Avc420MediaCodecDecoder().packPlane(
            actual, 0, buf, rowStride, pixelStride, dstW, dstH, srcW, srcH,
        )
        assertArrayEquals(
            "dst=${dstW}x$dstH src=${srcW}x$srcH pixelStride=$pixelStride",
            expected,
            actual,
        )
    }

    @Test fun `planar plane, exact fit`() = check(64, 32, 64, 32, pixelStride = 1, seed = 1)

    @Test fun `semi-planar chroma, exact fit`() = check(32, 16, 32, 16, pixelStride = 2, seed = 2)

    @Test fun `odd width`() = check(33, 16, 33, 16, pixelStride = 2, seed = 3)

    @Test fun `requested wider than decoded replicates the edge column`() =
        check(64, 32, 60, 32, pixelStride = 1, seed = 4)

    @Test fun `requested taller than decoded replicates the edge row`() =
        check(64, 32, 64, 28, pixelStride = 1, seed = 5)

    @Test fun `odd decoded width with edge replication`() =
        check(64, 32, 31, 32, pixelStride = 2, seed = 6)

    @Test fun `single decoded column`() = check(16, 8, 1, 8, pixelStride = 2, seed = 7)

    /**
     * The bulk-copy path only applies when nothing needs replicating, so it
     * must agree with the per-sample path on the case it does take.
     */
    @Test
    fun `the bulk-copy path agrees with the per-sample path`() {
        val rnd = Random(99)
        val w = 40
        val h = 12
        val rowStride = w + 9
        val buf = ByteBuffer.wrap(ByteArray(rowStride * h).also { rnd.nextBytes(it) })

        val bulk = ByteArray(w * h)
        Avc420MediaCodecDecoder().packPlane(bulk, 0, buf, rowStride, 1, w, h, w, h)

        // Force the per-sample branch by asking for one fewer source column.
        val perSample = ByteArray(w * h)
        reference(perSample, 0, buf, rowStride, 1, w, h, w, h)

        assertArrayEquals(perSample, bulk)
    }

    /** Writing at an offset must not disturb what is already in the buffer. */
    @Test
    fun `packing at an offset leaves earlier bytes alone`() {
        val buf = ByteBuffer.wrap(ByteArray(64) { (it + 1).toByte() })
        val out = ByteArray(40) { 0x7F }
        Avc420MediaCodecDecoder().packPlane(out, 8, buf, 8, 1, 4, 4, 4, 4)
        assertArrayEquals(ByteArray(8) { 0x7F }, out.copyOfRange(0, 8))
        assertArrayEquals(ByteArray(16) { 0x7F }, out.copyOfRange(24, 40))
    }
}
