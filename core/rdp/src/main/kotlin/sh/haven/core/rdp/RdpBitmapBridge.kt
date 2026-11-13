package sh.haven.core.rdp

import android.graphics.Bitmap
import android.util.Log

/**
 * Copies a decoded frame region straight from the native framebuffer into a
 * locked Android bitmap, skipping the byte-array round trip (#466).
 *
 * The normal publish path hands every frame across the FFI: native builds a
 * `Vec`, UniFFI marshals it into a `ByteArray`, and Kotlin copies that into the
 * bitmap. Three copies of the region — 8.3 MB each at 1080p, every frame.
 * Measured at ~57 ms per frame against a ~138 ms budget.
 *
 * This is a hand-written JNI entry point rather than a UniFFI method because
 * `AndroidBitmap_lockPixels` needs the JNIEnv and the jobject, neither of which
 * UniFFI can pass. The [bridgeId] is how the native side finds the session
 * again: a raw JNI function cannot reach a UniFFI object, whose handle is
 * opaque, so the client registers its state and hands out this key.
 *
 * [blitRegion] returns false for anything the caller can recover from — an
 * unknown id, a bitmap of the wrong format or size, a refused lock — and the
 * caller falls back to the byte-array path. A refusal costs a frame's latency,
 * never correctness.
 */
internal object RdpBitmapBridge {

    /** Whether the native symbol is present; false on a build without it. */
    val available: Boolean = try {
        System.loadLibrary("rdp_transport")
        true
    } catch (e: UnsatisfiedLinkError) {
        // UniFFI normally loads this already, so reaching here means something
        // is badly wrong — but the caller has a working fallback, so degrade
        // rather than take the session down.
        Log.w(TAG, "rdp_transport not loadable; bitmap bridge disabled", e)
        false
    }

    /**
     * Copy `w`x`h` pixels at (`x`,`y`) from the session's framebuffer into
     * [bitmap], which must be ARGB_8888 and the same size as the framebuffer.
     *
     * @return true if the pixels were written; false to use the fallback.
     */
    @JvmStatic
    external fun blitRegion(
        bitmap: Bitmap,
        bridgeId: Long,
        x: Int,
        y: Int,
        w: Int,
        h: Int,
    ): Boolean

    private const val TAG = "RdpBitmapBridge"
}
