//! BT.601 limited-range I420 → RGBA8888.
//!
//! This used to live in Kotlin, on the session thread, and was the single
//! largest cost in an AVC420 frame: a reporter's 1080p session spent 27–109 ms
//! per frame here against 9–25 ms in the hardware decoder itself (#466).
//!
//! Moving it here is worth two separate things, which is why it was worth
//! doing at all:
//!
//!  * the conversion runs as compiled Rust rather than a JVM loop, and
//!  * what crosses the Kotlin/Rust boundary becomes the **I420 planes**
//!    rather than the finished RGBA — 3.11 MB instead of 8.29 MB at 1080p,
//!    a 2.67× cut in the bytes that have to be allocated and copied per
//!    frame. That crossing measured 87–112 ms per frame, doing no work.
//!
//! The arithmetic is a transcription of the Kotlin it replaces, deliberately
//! including the odd-looking parts (the `y < 16` floor, the `+128` rounding
//! bias, the `>> 8`), so the two produce identical pixels. Changing the
//! colours was not the point of this change, and a shift here would be
//! invisible in a test that only checked "some pixels arrived".

/// Bytes an I420 buffer of `width` × `height` occupies, or `None` on overflow.
///
/// Chroma planes are half-resolution rounded **up**, matching `YUV_420_888`:
/// a 1×1 frame still carries one U and one V sample.
pub fn i420_len(width: usize, height: usize) -> Option<usize> {
    let cw = width.checked_add(1)? / 2;
    let ch = height.checked_add(1)? / 2;
    let luma = width.checked_mul(height)?;
    let chroma = cw.checked_mul(ch)?;
    luma.checked_add(chroma.checked_mul(2)?)
}

/// Convert tightly-packed I420 in `src` to RGBA8888 in `out`.
///
/// Returns `false` — leaving `out` untouched — when `src` is too small for
/// `width` × `height`. A short buffer means the host decoder produced
/// something other than what it claimed, and painting whatever happened to be
/// in memory would be worse than dropping the tile.
pub fn i420_to_rgba(src: &[u8], width: usize, height: usize, out: &mut Vec<u8>) -> bool {
    let Some(need) = i420_len(width, height) else {
        return false;
    };
    if width == 0 || height == 0 || src.len() < need {
        return false;
    }
    let cw = (width + 1) / 2;
    let ch = (height + 1) / 2;
    let (y_plane, rest) = src.split_at(width * height);
    let (u_plane, v_plane) = rest.split_at(cw * ch);

    // Written through a slice rather than pushed byte by byte. `push` costs a
    // capacity check and a length bump per byte — 14.7 M of them per frame at
    // 2560x1440, which measured 24.5 ms against 7.9 ms for this loop on an
    // x86 desktop at the `opt-level = "z"` the shipped library is built with
    // (#466/#477). Same arithmetic, same bytes out.
    //
    // The buffer is reused across frames, so the resize is a no-op except on
    // the first frame and after a resolution change; every byte below is
    // written, so the zeroes it fills with are never read.
    let out_len = width * height * 4;
    if out.len() != out_len {
        out.clear();
        out.resize(out_len, 0);
    }

    for y in 0..height {
        let y_row = &y_plane[y * width..][..width];
        let c_row = (y / 2) * cw;
        let u_row = &u_plane[c_row..][..cw];
        let v_row = &v_plane[c_row..][..cw];
        let dst = &mut out[y * width * 4..][..width * 4];

        // Each chroma sample serves the two horizontal pixels that share it,
        // so its three scaled terms are computed once per pair rather than
        // twice — the same shape the Kotlin loop had.
        let mut chunks = dst.chunks_exact_mut(8);
        let mut x = 0;
        for px in &mut chunks {
            let cx = x >> 1;
            let uv = i32::from(u_row[cx]) - 128;
            let vv = i32::from(v_row[cx]) - 128;
            let r_c = 409 * vv + 128;
            let g_c = -100 * uv - 208 * vv + 128;
            let b_c = 516 * uv + 128;

            for i in 0..2 {
                let yv = i32::from(y_row[x + i]) - 16;
                let c = if yv < 0 { 0 } else { yv * 298 };
                px[i * 4] = clamp8((c + r_c) >> 8);
                px[i * 4 + 1] = clamp8((c + g_c) >> 8);
                px[i * 4 + 2] = clamp8((c + b_c) >> 8);
                px[i * 4 + 3] = 0xFF;
            }
            x += 2;
        }

        // An odd final column shares the last chroma pair's sample. An odd
        // width leaves exactly one pixel in the remainder; an even width
        // leaves none and this is skipped.
        let rem = chunks.into_remainder();
        if x < width {
            let cx = x >> 1;
            let uv = i32::from(u_row[cx]) - 128;
            let vv = i32::from(v_row[cx]) - 128;
            let yv = i32::from(y_row[x]) - 16;
            let c = if yv < 0 { 0 } else { yv * 298 };
            rem[0] = clamp8((c + 409 * vv + 128) >> 8);
            rem[1] = clamp8((c - 100 * uv - 208 * vv + 128) >> 8);
            rem[2] = clamp8((c + 516 * uv + 128) >> 8);
            rem[3] = 0xFF;
        }
    }
    true
}

#[inline]
fn clamp8(v: i32) -> u8 {
    v.clamp(0, 255) as u8
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The output buffer is reused across frames, and the resize is now
    /// conditional — so a second frame of a *different* size through the same
    /// buffer is the case that can leave a stale tail behind. Both directions,
    /// because shrinking and growing fail differently.
    #[test]
    fn a_reused_buffer_gives_the_same_bytes_as_a_fresh_one() {
        let big = flat(64, 32, 200, 90, 140);
        let small = flat(16, 8, 60, 200, 30);

        let mut fresh_big = Vec::new();
        let mut fresh_small = Vec::new();
        assert!(i420_to_rgba(&big, 64, 32, &mut fresh_big));
        assert!(i420_to_rgba(&small, 16, 8, &mut fresh_small));

        let mut reused = Vec::new();
        assert!(i420_to_rgba(&big, 64, 32, &mut reused));
        assert!(i420_to_rgba(&small, 16, 8, &mut reused));
        assert_eq!(fresh_small, reused, "shrinking left a stale tail");

        assert!(i420_to_rgba(&big, 64, 32, &mut reused));
        assert_eq!(fresh_big, reused, "growing did not rewrite every byte");

        assert!(i420_to_rgba(&big, 64, 32, &mut reused));
        assert_eq!(fresh_big, reused, "same size twice diverged");
    }

    /// Build an I420 buffer with uniform Y/U/V.
    fn flat(w: usize, h: usize, y: u8, u: u8, v: u8) -> Vec<u8> {
        let cw = (w + 1) / 2;
        let ch = (h + 1) / 2;
        let mut b = vec![y; w * h];
        b.extend(std::iter::repeat(u).take(cw * ch));
        b.extend(std::iter::repeat(v).take(cw * ch));
        b
    }

    /// Golden output captured from the Kotlin implementation this replaces.
    ///
    /// The point of the move was speed, not new colours, so the bar is that
    /// the pixels come out **identical** — and idealised textbook values will
    /// not establish that. This integer approximation puts pure green's blue
    /// channel at 1, not 0; a test written against the textbook would have
    /// failed on correct code and could have passed on a drifted one.
    ///
    /// So these numbers came out of `Avc420MediaCodecDecoder.yuvToRgba`
    /// itself, fed the deterministic 8x4 frame built below. If the arithmetic
    /// ever legitimately changes, regenerate them by driving that function
    /// with the same input — do not hand-edit them to match.
    #[test]
    fn output_is_identical_to_the_kotlin_implementation() {
        let w = 8usize;
        let h = 4usize;
        let (cw, ch) = ((w + 1) / 2, (h + 1) / 2);
        let mut src = Vec::new();
        src.extend((0..w * h).map(|i| ((i * 37 + 11) & 0xFF) as u8));
        src.extend((0..cw * ch).map(|i| ((i * 53 + 7) & 0xFF) as u8));
        src.extend((0..cw * ch).map(|i| ((i * 97 + 200) & 0xFF) as u8));

        const GOLDEN: [u8; 128] = [115,0,0,255,152,26,0,255,0,178,0,255,0,221,0,255,182,164,136,255,226,207,179,255,255,151,255,255,171,0,77,255,156,30,0,255,199,73,0,255,0,224,0,255,31,255,33,255,229,211,183,255,255,254,226,255,172,0,78,255,215,0,121,255,4,94,255,255,47,137,255,255,245,181,0,255,255,224,0,255,77,255,141,255,0,120,0,255,21,64,36,255,64,107,79,255,51,141,255,255,94,184,255,255,255,227,0,255,255,255,37,255,0,124,0,255,0,167,0,255,67,110,82,255,110,154,125,255];

        let mut out = Vec::new();
        assert!(i420_to_rgba(&src, w, h, &mut out));
        assert_eq!(out.len(), GOLDEN.len());
        for (i, (got, want)) in out.iter().zip(GOLDEN.iter()).enumerate() {
            assert_eq!(
                got, want,
                "byte {i} (pixel {}, channel {}) differs: Rust {got} vs Kotlin {want}",
                i / 4,
                ["R", "G", "B", "A"][i % 4],
            );
        }
    }

    /// The same identity check over a frame that actually reaches the rounding
    /// boundaries — 256x64, with luma sweeping the full 0..255 range against
    /// independently sweeping chroma.
    ///
    /// The 8x4 golden above turned out to be too small to notice a ±1 change
    /// in the rounding bias: mutating `+ 128` to `+ 127` left all 128 of its
    /// bytes unchanged, because none of them sat on a boundary. It catches a
    /// wrong *coefficient* but not a wrong *bias*, and only running the
    /// mutation showed that. This one covers 65536 bytes and is compared by
    /// digest, so any single differing byte fails it.
    #[test]
    fn a_full_range_frame_hashes_identically_to_the_kotlin_implementation() {
        let w = 256usize;
        let h = 64usize;
        let (cw, ch) = (w / 2, h / 2);
        let mut src = Vec::with_capacity(w * h + 2 * cw * ch);
        src.extend((0..w * h).map(|i| (i % 256) as u8));
        src.extend((0..cw * ch).map(|i| ((i * 7) % 256) as u8));
        src.extend((0..cw * ch).map(|i| ((i * 11 + 3) % 256) as u8));

        let mut out = Vec::new();
        assert!(i420_to_rgba(&src, w, h, &mut out));
        assert_eq!(out.len(), 65536, "frame size must match what Kotlin hashed");

        // FNV-1a 64, matching the generator.
        let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
        for &b in &out {
            hash ^= u64::from(b);
            hash = hash.wrapping_mul(0x100_0000_01b3);
        }
        assert_eq!(
            hash, 2537330221983784613,
            "Rust and Kotlin disagree somewhere in this frame",
        );
    }

    #[test]
    fn output_is_four_bytes_per_pixel_and_fully_written() {
        for (w, h) in [(2, 2), (1, 1), (3, 3), (7, 5), (64, 36)] {
            let src = flat(w, h, 128, 128, 128);
            let mut out = Vec::new();
            assert!(i420_to_rgba(&src, w, h, &mut out), "{w}x{h} should convert");
            assert_eq!(out.len(), w * h * 4, "{w}x{h}");
            assert!(out.iter().skip(3).step_by(4).all(|&a| a == 0xFF), "every alpha opaque");
        }
    }

    /// Odd widths exercise the tail: the last column has no pair partner and
    /// must still be written, or the right edge of an odd-width tile is left
    /// as whatever the buffer held.
    #[test]
    fn an_odd_width_still_writes_its_last_column() {
        let w = 5;
        let h = 2;
        let mut src = flat(w, h, 16, 128, 128); // all black
        // Make the final column of row 0 white.
        src[w - 1] = 235;
        let mut out = Vec::new();
        assert!(i420_to_rgba(&src, w, h, &mut out));
        let last = ((w - 1) * 4) as usize;
        assert_eq!(&out[last..last + 3], &[255, 255, 255], "final column converted");
    }

    #[test]
    fn a_short_buffer_is_refused_rather_than_read_past() {
        let mut out = vec![1, 2, 3];
        let src = vec![0u8; 10];
        assert!(!i420_to_rgba(&src, 64, 64, &mut out), "10 bytes is not a 64x64 frame");
        assert_eq!(out, vec![1, 2, 3], "out must be left alone on refusal");
        assert!(!i420_to_rgba(&[], 0, 0, &mut out), "a zero-sized frame is not convertible");
    }

    #[test]
    fn i420_len_matches_the_yuv_420_888_layout() {
        assert_eq!(i420_len(2, 2), Some(4 + 1 + 1));
        assert_eq!(i420_len(1920, 1080), Some(1920 * 1080 * 3 / 2));
        assert_eq!(i420_len(1, 1), Some(1 + 1 + 1), "a 1x1 frame still has chroma");
        assert_eq!(i420_len(3, 3), Some(9 + 4 + 4), "chroma rounds up");
    }
}
