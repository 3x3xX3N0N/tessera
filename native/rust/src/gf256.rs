//! GF(2^8) multiply-accumulate kernels for the RLNC datapath.
//!
//! Field polynomial is `0x11D` (x^8 + x^4 + x^3 + x^2 + 1) with generator `x`, exactly like
//! `aether.core.GF256` on the Kotlin side, so both produce bit-identical symbols.
//!
//! The vector kernels use the *split-nibble table* method: multiplication by a fixed coefficient
//! `c` is GF(2)-linear, so `c * s == LO_c[s & 0xF] ^ HI_c[s >> 4]` with two 16-entry tables.
//! Both lookups are a single byte shuffle per vector (`pshufb` / `vpshufb` on x86, `tbl` on
//! NEON), which makes the kernel memory-bound rather than table-bound.
//!
//! GFNI (`gf2p8mul`) is deliberately **not** used: it hard-wires the AES polynomial `0x11B`, and
//! emulating `0x11D` requires wrapping the multiply in two `gf2p8affine` field isomorphisms. That
//! path could not be validated on the development machine (no GFNI), so it was left out rather
//! than shipped untested.

use std::sync::OnceLock;

/// `(exp, log)` tables for generator 2 modulo 0x11D, built at compile time the same way the
/// Kotlin reference builds them (exp is doubled so `exp[log a + log b]` needs no reduction).
const EXP_LOG: ([u8; 512], [u8; 256]) = build_exp_log();

const fn build_exp_log() -> ([u8; 512], [u8; 256]) {
    let mut exp = [0u8; 512];
    let mut log = [0u8; 256];
    let mut x: u32 = 1;
    let mut i = 0;
    while i < 255 {
        exp[i] = x as u8;
        log[x as usize] = i as u8;
        x <<= 1;
        if x & 0x100 != 0 {
            x ^= 0x11D;
        }
        i += 1;
    }
    while i < 512 {
        exp[i] = exp[i - 255];
        i += 1;
    }
    (exp, log)
}

/// Multiplies two field elements (const-evaluable; used to build the kernel tables).
pub const fn mul(a: u8, b: u8) -> u8 {
    if a == 0 || b == 0 {
        return 0;
    }
    EXP_LOG.0[EXP_LOG.1[a as usize] as usize + EXP_LOG.1[b as usize] as usize]
}

/// Per-coefficient table: bytes `0..16` are `c * i`, bytes `16..32` are `c * (i << 4)`.
pub type NibbleTable = [u8; 32];

const fn build_nibble_tables() -> [NibbleTable; 256] {
    let mut t = [[0u8; 32]; 256];
    let mut c = 0;
    while c < 256 {
        let mut i = 0;
        while i < 16 {
            t[c][i] = mul(c as u8, i as u8);
            t[c][16 + i] = mul(c as u8, (i << 4) as u8);
            i += 1;
        }
        c += 1;
    }
    t
}

/// 8 KiB of split-nibble tables, indexed by coefficient.
pub static NIBBLE_TABLES: [NibbleTable; 256] = build_nibble_tables();

/// Which kernel the runtime dispatcher picked. The discriminants are part of the C ABI
/// (`aether_gf256_impl`).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u32)]
pub enum Impl {
    /// Portable byte loop (two table lookups per byte).
    Scalar = 0,
    /// x86-64 SSSE3 `pshufb`, 16 bytes per step.
    Ssse3 = 1,
    /// x86-64 AVX2 `vpshufb`, 32 bytes per step.
    Avx2 = 2,
    /// AArch64 NEON `tbl`, 16 bytes per step.
    Neon = 3,
}

/// Kernel signature. `dst.len() == src.len()` is a precondition; the table is the one for `c`.
/// Kernels are `unsafe` only because calling them requires the CPU feature they were compiled for.
type Kernel = unsafe fn(&mut [u8], &[u8], &NibbleTable);

static KERNEL: OnceLock<(Kernel, Impl)> = OnceLock::new();

fn select_kernel() -> (Kernel, Impl) {
    #[cfg(target_arch = "x86_64")]
    {
        if is_x86_feature_detected!("avx2") {
            return (x86::muladd_avx2 as Kernel, Impl::Avx2);
        }
        if is_x86_feature_detected!("ssse3") {
            return (x86::muladd_ssse3 as Kernel, Impl::Ssse3);
        }
    }
    #[cfg(target_arch = "aarch64")]
    {
        if std::arch::is_aarch64_feature_detected!("neon") {
            return (neon::muladd_neon as Kernel, Impl::Neon);
        }
    }
    (scalar::muladd_scalar as Kernel, Impl::Scalar)
}

fn kernel() -> (Kernel, Impl) {
    *KERNEL.get_or_init(select_kernel)
}

/// The kernel selected for this CPU.
pub fn selected_impl() -> Impl {
    kernel().1
}

/// `dst[i] ^= src[i] * c` for every `i`, using the fastest kernel available on this CPU.
///
/// # Panics
/// Panics if `dst.len() != src.len()`.
pub fn mul_add_into(dst: &mut [u8], src: &[u8], c: u8) {
    assert_eq!(dst.len(), src.len(), "dst/src length mismatch");
    if c == 0 || dst.is_empty() {
        return;
    }
    let (k, _) = kernel();
    // SAFETY: `kernel()` only ever returns a kernel whose CPU feature was detected at runtime.
    unsafe { k(dst, src, &NIBBLE_TABLES[c as usize]) }
}

pub mod scalar {
    //! Portable reference kernel; also used for vector tails.
    use super::NibbleTable;

    /// Two table lookups per byte. Safe and allocation-free.
    pub fn muladd_scalar(dst: &mut [u8], src: &[u8], t: &NibbleTable) {
        for (d, &s) in dst.iter_mut().zip(src) {
            *d ^= t[(s & 0x0F) as usize] ^ t[16 + (s >> 4) as usize];
        }
    }
}

#[cfg(target_arch = "x86_64")]
pub mod x86 {
    //! SSSE3 / AVX2 kernels. The only `unsafe` here is the intrinsics themselves.
    use super::scalar::muladd_scalar;
    use super::NibbleTable;
    use core::arch::x86_64::*;

    /// 16 bytes per step with `pshufb`.
    ///
    /// # Safety
    /// The CPU must support SSSE3.
    #[target_feature(enable = "ssse3")]
    pub unsafe fn muladd_ssse3(dst: &mut [u8], src: &[u8], t: &NibbleTable) {
        // SAFETY: `t` is 32 bytes; unaligned loads are used throughout.
        let (lo, hi, mask) = unsafe {
            (
                _mm_loadu_si128(t.as_ptr().cast()),
                _mm_loadu_si128(t.as_ptr().add(16).cast()),
                _mm_set1_epi8(0x0F),
            )
        };
        let mut d_chunks = dst.chunks_exact_mut(16);
        let mut s_chunks = src.chunks_exact(16);
        for (d, s) in (&mut d_chunks).zip(&mut s_chunks) {
            // SAFETY: every chunk is exactly 16 bytes; loads/stores are the unaligned variants.
            unsafe {
                let sv = _mm_loadu_si128(s.as_ptr().cast());
                let dv = _mm_loadu_si128(d.as_ptr().cast());
                let l = _mm_shuffle_epi8(lo, _mm_and_si128(sv, mask));
                let h = _mm_shuffle_epi8(hi, _mm_and_si128(_mm_srli_epi16(sv, 4), mask));
                _mm_storeu_si128(d.as_mut_ptr().cast(), _mm_xor_si128(dv, _mm_xor_si128(l, h)));
            }
        }
        muladd_scalar(d_chunks.into_remainder(), s_chunks.remainder(), t);
    }

    /// 32 bytes per step with `vpshufb` (which shuffles within 128-bit lanes, so the 16-entry
    /// tables are simply broadcast to both lanes).
    ///
    /// # Safety
    /// The CPU must support AVX2.
    #[target_feature(enable = "avx2")]
    pub unsafe fn muladd_avx2(dst: &mut [u8], src: &[u8], t: &NibbleTable) {
        // SAFETY: `t` is 32 bytes; unaligned loads are used throughout.
        let (lo, hi, mask) = unsafe {
            (
                _mm256_broadcastsi128_si256(_mm_loadu_si128(t.as_ptr().cast())),
                _mm256_broadcastsi128_si256(_mm_loadu_si128(t.as_ptr().add(16).cast())),
                _mm256_set1_epi8(0x0F),
            )
        };
        let mut d_chunks = dst.chunks_exact_mut(32);
        let mut s_chunks = src.chunks_exact(32);
        for (d, s) in (&mut d_chunks).zip(&mut s_chunks) {
            // SAFETY: every chunk is exactly 32 bytes; loads/stores are the unaligned variants.
            unsafe {
                let sv = _mm256_loadu_si256(s.as_ptr().cast());
                let dv = _mm256_loadu_si256(d.as_ptr().cast());
                let l = _mm256_shuffle_epi8(lo, _mm256_and_si256(sv, mask));
                let h = _mm256_shuffle_epi8(hi, _mm256_and_si256(_mm256_srli_epi16(sv, 4), mask));
                _mm256_storeu_si256(d.as_mut_ptr().cast(), _mm256_xor_si256(dv, _mm256_xor_si256(l, h)));
            }
        }
        // SAFETY: AVX2 implies SSSE3.
        unsafe { muladd_ssse3(d_chunks.into_remainder(), s_chunks.remainder(), t) }
    }
}

#[cfg(target_arch = "aarch64")]
pub mod neon {
    //! NEON kernel (`tbl` is the 16-entry byte shuffle). Compiled but only exercised on AArch64.
    use super::scalar::muladd_scalar;
    use super::NibbleTable;
    use core::arch::aarch64::*;

    /// 16 bytes per step with `vqtbl1q_u8`.
    ///
    /// # Safety
    /// The CPU must support NEON (always true on AArch64, kept explicit for symmetry).
    #[target_feature(enable = "neon")]
    pub unsafe fn muladd_neon(dst: &mut [u8], src: &[u8], t: &NibbleTable) {
        // SAFETY: `t` is 32 bytes.
        let (lo, hi, mask) =
            unsafe { (vld1q_u8(t.as_ptr()), vld1q_u8(t.as_ptr().add(16)), vdupq_n_u8(0x0F)) };
        let mut d_chunks = dst.chunks_exact_mut(16);
        let mut s_chunks = src.chunks_exact(16);
        for (d, s) in (&mut d_chunks).zip(&mut s_chunks) {
            // SAFETY: every chunk is exactly 16 bytes.
            unsafe {
                let sv = vld1q_u8(s.as_ptr());
                let dv = vld1q_u8(d.as_ptr());
                let l = vqtbl1q_u8(lo, vandq_u8(sv, mask));
                let h = vqtbl1q_u8(hi, vshrq_n_u8::<4>(sv));
                vst1q_u8(d.as_mut_ptr(), veorq_u8(dv, veorq_u8(l, h)));
            }
        }
        muladd_scalar(d_chunks.into_remainder(), s_chunks.remainder(), t);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Shift-and-add multiplication straight from the polynomial definition.
    fn mul_slow(mut a: u8, mut b: u8) -> u8 {
        let mut p = 0u8;
        while b != 0 {
            if b & 1 != 0 {
                p ^= a;
            }
            let carry = a & 0x80 != 0;
            a <<= 1;
            if carry {
                a ^= 0x1D;
            }
            b >>= 1;
        }
        p
    }

    struct XorShift(u64);
    impl XorShift {
        fn next(&mut self) -> u8 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            (self.0 >> 24) as u8
        }
        fn fill(&mut self, v: &mut [u8]) {
            v.iter_mut().for_each(|b| *b = self.next());
        }
    }

    #[test]
    fn mul_matches_polynomial_arithmetic() {
        for a in 0..=255u8 {
            for b in 0..=255u8 {
                assert_eq!(mul(a, b), mul_slow(a, b), "{a} * {b}");
            }
        }
        // Generator check: the Kotlin tables use x (= 2) as the generator.
        assert_eq!(EXP_LOG.0[1], 2);
        assert_eq!(EXP_LOG.0[8], 0x1D);
    }

    #[test]
    fn nibble_tables_decompose_multiplication() {
        for c in 0..=255u8 {
            let t = &NIBBLE_TABLES[c as usize];
            for s in 0..=255u8 {
                assert_eq!(mul(c, s), t[(s & 0xF) as usize] ^ t[16 + (s >> 4) as usize]);
            }
        }
    }

    fn available_kernels() -> Vec<(Kernel, Impl)> {
        let mut v: Vec<(Kernel, Impl)> = vec![(scalar::muladd_scalar as Kernel, Impl::Scalar)];
        #[cfg(target_arch = "x86_64")]
        {
            if is_x86_feature_detected!("ssse3") {
                v.push((x86::muladd_ssse3 as Kernel, Impl::Ssse3));
            }
            if is_x86_feature_detected!("avx2") {
                v.push((x86::muladd_avx2 as Kernel, Impl::Avx2));
            }
        }
        #[cfg(target_arch = "aarch64")]
        {
            v.push((neon::muladd_neon as Kernel, Impl::Neon));
        }
        v
    }

    #[test]
    fn every_available_kernel_matches_reference() {
        let mut rng = XorShift(0x9E37_79B9_7F4A_7C15);
        let lengths = [0usize, 1, 15, 16, 17, 31, 32, 33, 63, 64, 65, 100, 1200, 1201, 4096, 4097];
        for (k, which) in available_kernels() {
            for &len in &lengths {
                let mut src = vec![0u8; len];
                rng.fill(&mut src);
                for c in 0..=255u8 {
                    let mut dst = vec![0u8; len];
                    rng.fill(&mut dst);
                    let expected: Vec<u8> = dst.iter().zip(&src).map(|(&d, &s)| d ^ mul(s, c)).collect();
                    // SAFETY: only kernels whose features were detected are listed.
                    unsafe { k(&mut dst, &src, &NIBBLE_TABLES[c as usize]) };
                    assert_eq!(dst, expected, "{which:?} len={len} c={c}");
                }
            }
        }
    }

    #[test]
    fn dispatcher_matches_reference_and_handles_zero() {
        let mut rng = XorShift(42);
        let mut src = vec![0u8; 1200];
        let mut dst = vec![0u8; 1200];
        rng.fill(&mut src);
        rng.fill(&mut dst);
        let before = dst.clone();
        mul_add_into(&mut dst, &src, 0);
        assert_eq!(dst, before, "c = 0 must be a no-op");
        mul_add_into(&mut dst, &src, 0xA7);
        let expected: Vec<u8> = before.iter().zip(&src).map(|(&d, &s)| d ^ mul(s, 0xA7)).collect();
        assert_eq!(dst, expected);
        println!("selected kernel: {:?}", selected_impl());
    }
}
