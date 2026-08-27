//! Poly1305 one-time authenticator, RFC 8439 §2.5.
//!
//! Implemented from the document. The accumulator is arithmetic modulo `2^130 - 5`, held as five
//! 26-bit limbs in `u32` with `u64` products — the standard portable representation, chosen here
//! because 26-bit limbs leave enough headroom that carries can be deferred to one pass per block,
//! and because it needs no 128-bit multiply.
//!
//! Constant-time: no data-dependent branch or index. The final conditional subtraction of
//! `2^130 - 5` and the tag comparison are both done with masks rather than `if`, since the branch
//! would leak the accumulator's high bits and the number of equal tag bytes respectively.
//!
//! One-time key, one message: `r` is clamped per §2.5 and the key must never be reused across
//! messages. The AEAD in [`crate::aead`] derives a fresh key per packet from the ChaCha20 block at
//! counter 0, which is exactly the §2.6 construction.

/// Poly1305 state: the clamped `r`, the additive `s`, and the 130-bit accumulator.
pub struct Poly1305 {
    r: [u32; 5],
    s: [u32; 4],
    a: [u32; 5],
    /// Partial block carried between [`Poly1305::update`] calls.
    buf: [u8; 16],
    buf_len: usize,
}

impl Poly1305 {
    /// `key` is `r || s`; `r` is clamped as RFC 8439 §2.5 requires (the top four bits of each
    /// 32-bit word cleared, and the low two bits of the upper three words), which is what keeps the
    /// limb products inside `u64`.
    pub fn new(key: &[u8; 32]) -> Self {
        let t: [u32; 4] = core::array::from_fn(|i| {
            u32::from_le_bytes([key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]])
        });
        // Repack the clamped 128-bit r into 26-bit limbs.
        let r = [
            t[0] & 0x03ff_ffff,
            ((t[0] >> 26) | (t[1] << 6)) & 0x03ff_ff03,
            ((t[1] >> 20) | (t[2] << 12)) & 0x03ff_c0ff,
            ((t[2] >> 14) | (t[3] << 18)) & 0x03f0_3fff,
            (t[3] >> 8) & 0x000f_ffff,
        ];
        let s = core::array::from_fn(|i| {
            u32::from_le_bytes([key[16 + 4 * i], key[17 + 4 * i], key[18 + 4 * i], key[19 + 4 * i]])
        });
        Poly1305 { r, s, a: [0; 5], buf: [0; 16], buf_len: 0 }
    }

    /// Absorbs one 16-byte block. `high` is the bit appended above the block: 1 for a full block
    /// (the `0x01` byte of §2.5.1), 0 for the final short block, which carries its pad byte inside
    /// `block` instead.
    fn block(&mut self, block: &[u8; 16], high: u32) {
        let t: [u32; 4] = core::array::from_fn(|i| {
            u32::from_le_bytes([block[4 * i], block[4 * i + 1], block[4 * i + 2], block[4 * i + 3]])
        });
        // a += n, where n is the block interpreted little-endian with the high bit appended
        self.a[0] += t[0] & 0x03ff_ffff;
        self.a[1] += ((t[0] >> 26) | (t[1] << 6)) & 0x03ff_ffff;
        self.a[2] += ((t[1] >> 20) | (t[2] << 12)) & 0x03ff_ffff;
        self.a[3] += ((t[2] >> 14) | (t[3] << 18)) & 0x03ff_ffff;
        self.a[4] += (t[3] >> 8) | (high << 24);

        // a *= r mod 2^130 - 5. Reducing 2^130 to 5 folds the overflow limbs back in as 5*r[i],
        // so the products above limb 4 are pre-multiplied here.
        let r = self.r;
        let s: [u64; 4] = core::array::from_fn(|i| 5u64 * r[i + 1] as u64);
        let a: [u64; 5] = core::array::from_fn(|i| self.a[i] as u64);

        let d0 = a[0] * r[0] as u64 + a[1] * s[3] + a[2] * s[2] + a[3] * s[1] + a[4] * s[0];
        let d1 = a[0] * r[1] as u64 + a[1] * r[0] as u64 + a[2] * s[3] + a[3] * s[2] + a[4] * s[1];
        let d2 = a[0] * r[2] as u64 + a[1] * r[1] as u64 + a[2] * r[0] as u64 + a[3] * s[3] + a[4] * s[2];
        let d3 = a[0] * r[3] as u64 + a[1] * r[2] as u64 + a[2] * r[1] as u64 + a[3] * r[0] as u64 + a[4] * s[3];
        let d4 = a[0] * r[4] as u64 + a[1] * r[3] as u64 + a[2] * r[2] as u64 + a[3] * r[1] as u64 + a[4] * r[0] as u64;

        // carry propagation, then one wrap of the 2^130 overflow back into limb 0
        let mut c = d0 >> 26;
        self.a[0] = (d0 & 0x03ff_ffff) as u32;
        let d1 = d1 + c;
        c = d1 >> 26;
        self.a[1] = (d1 & 0x03ff_ffff) as u32;
        let d2 = d2 + c;
        c = d2 >> 26;
        self.a[2] = (d2 & 0x03ff_ffff) as u32;
        let d3 = d3 + c;
        c = d3 >> 26;
        self.a[3] = (d3 & 0x03ff_ffff) as u32;
        let d4 = d4 + c;
        c = d4 >> 26;
        self.a[4] = (d4 & 0x03ff_ffff) as u32;
        self.a[0] += (c * 5) as u32;
        c = (self.a[0] >> 26) as u64;
        self.a[0] &= 0x03ff_ffff;
        self.a[1] += c as u32;
    }

    /// Absorbs `data`, buffering across calls so the caller may feed it in any chunking.
    pub fn update(&mut self, mut data: &[u8]) {
        if self.buf_len > 0 {
            let take = core::cmp::min(16 - self.buf_len, data.len());
            self.buf[self.buf_len..self.buf_len + take].copy_from_slice(&data[..take]);
            self.buf_len += take;
            data = &data[take..];
            if self.buf_len < 16 {
                return;
            }
            let b = self.buf;
            self.block(&b, 1);
            self.buf_len = 0;
        }
        let mut chunks = data.chunks_exact(16);
        for chunk in &mut chunks {
            let mut b = [0u8; 16];
            b.copy_from_slice(chunk);
            self.block(&b, 1);
        }
        let rem = chunks.remainder();
        self.buf[..rem.len()].copy_from_slice(rem);
        self.buf_len = rem.len();
    }

    /// Finishes the final (possibly short) block and returns the 16-byte tag.
    pub fn finish(mut self) -> [u8; 16] {
        if self.buf_len > 0 {
            let n = self.buf_len;
            self.buf[n] = 1; // the pad byte of §2.5.1, inside the block for a short final block
            for b in self.buf[n + 1..].iter_mut() {
                *b = 0;
            }
            let b = self.buf;
            self.block(&b, 0);
        }

        // full carry, then the constant-time conditional subtraction of p = 2^130 - 5
        let mut c = self.a[1] >> 26;
        self.a[1] &= 0x03ff_ffff;
        for i in 2..5 {
            self.a[i] += c;
            c = self.a[i] >> 26;
            self.a[i] &= 0x03ff_ffff;
        }
        self.a[0] += c * 5;
        c = self.a[0] >> 26;
        self.a[0] &= 0x03ff_ffff;
        self.a[1] += c;

        // g = a - p, computed as a + 5 with the top limb's borrow telling us whether a >= p
        let mut g = [0u32; 5];
        let mut carry = 5u32;
        for i in 0..5 {
            let v = self.a[i] + carry;
            carry = v >> 26;
            g[i] = v & 0x03ff_ffff;
        }
        // carry out of limb 4 (bit 2^130) means a + 5 >= 2^130, i.e. a >= p: select g, else a
        let mask = 0u32.wrapping_sub(carry); // all ones iff carry == 1
        for i in 0..5 {
            self.a[i] = (self.a[i] & !mask) | (g[i] & mask);
        }

        // repack 26-bit limbs into four 32-bit words, then add s mod 2^128
        let w: [u32; 4] = [
            self.a[0] | (self.a[1] << 26),
            (self.a[1] >> 6) | (self.a[2] << 20),
            (self.a[2] >> 12) | (self.a[3] << 14),
            (self.a[3] >> 18) | (self.a[4] << 8),
        ];
        let mut tag = [0u8; 16];
        let mut add = 0u64;
        for i in 0..4 {
            add += w[i] as u64 + self.s[i] as u64;
            tag[4 * i..4 * i + 4].copy_from_slice(&(add as u32).to_le_bytes());
            add >>= 32;
        }
        tag
    }
}

/// One-shot: tag of `data` under `key`.
pub fn auth(key: &[u8; 32], data: &[u8]) -> [u8; 16] {
    let mut p = Poly1305::new(key);
    p.update(data);
    p.finish()
}

/// Constant-time equality: compares every byte, returning without an early exit so the number of
/// matching leading bytes cannot be timed.
pub fn verify(a: &[u8], b: &[u8]) -> bool {
    if a.len() != b.len() {
        return false;
    }
    let mut diff = 0u8;
    for i in 0..a.len() {
        diff |= a[i] ^ b[i];
    }
    diff == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 8439 §2.5.2: the worked example.
    #[test]
    fn rfc8439_vector() {
        let key: [u8; 32] = [
            0x85, 0xd6, 0xbe, 0x78, 0x57, 0x55, 0x6d, 0x33, 0x7f, 0x44, 0x52, 0xfe, 0x42, 0xd5, 0x06, 0xa8,
            0x01, 0x03, 0x80, 0x8a, 0xfb, 0x0d, 0xb2, 0xfd, 0x4a, 0xbf, 0xf6, 0xaf, 0x41, 0x49, 0xf5, 0x1b,
        ];
        let tag = auth(&key, b"Cryptographic Forum Research Group");
        let expect: [u8; 16] = [
            0xa8, 0x06, 0x1d, 0xc1, 0x30, 0x51, 0x36, 0xc6, 0xc2, 0x2b, 0x8b, 0xaf, 0x0c, 0x01, 0x27, 0xa9,
        ];
        assert_eq!(tag, expect);
    }

    /// RFC 8439 §A.3 test vector #2: r = 0 means the tag is s alone, whatever the message.
    #[test]
    fn rfc8439_a3_zero_r() {
        let mut key = [0u8; 32];
        key[16..].copy_from_slice(&[
            0x36, 0xe5, 0xf6, 0xb5, 0xc5, 0xe0, 0x60, 0x70, 0xf0, 0xef, 0xca, 0x96, 0x22, 0x7a, 0x86, 0x3e,
        ]);
        let msg = [0x41u8; 375];
        assert_eq!(&auth(&key, &msg)[..], &key[16..]);
    }

    /// RFC 8439 §A.3 test vector #4.
    #[test]
    fn rfc8439_a3_vector4() {
        let key: [u8; 32] = [
            0x1c, 0x92, 0x40, 0xa5, 0xeb, 0x55, 0xd3, 0x8a, 0xf3, 0x33, 0x88, 0x86, 0x04, 0xf6, 0xb5, 0xf0,
            0x47, 0x39, 0x17, 0xc1, 0x40, 0x2b, 0x80, 0x09, 0x9d, 0xca, 0x5c, 0xbc, 0x20, 0x70, 0x75, 0xc0,
        ];
        let msg = b"'Twas brillig, and the slithy toves\nDid gyre and gimble in the wabe:\nAll mimsy were the borogoves,\nAnd the mome raths outgrabe.";
        let expect: [u8; 16] = [
            0x45, 0x41, 0x66, 0x9a, 0x7e, 0xaa, 0xee, 0x61, 0xe7, 0x08, 0xdc, 0x7c, 0xbc, 0xc5, 0xeb, 0x62,
        ];
        assert_eq!(auth(&key, msg), expect);
    }

    /// The chunked `update` path must agree with the one-shot path for every split, which is where
    /// the 16-byte buffering can go wrong without any vector noticing.
    #[test]
    fn chunked_update_matches_one_shot() {
        let key: [u8; 32] = core::array::from_fn(|i| (i * 5 + 3) as u8);
        let msg: Vec<u8> = (0..200u32).map(|i| (i * 7) as u8).collect();
        let want = auth(&key, &msg);
        for split in 0..msg.len() {
            let mut p = Poly1305::new(&key);
            p.update(&msg[..split]);
            p.update(&msg[split..]);
            assert_eq!(p.finish(), want, "split at {split}");
        }
    }

    /// A tag must change when any single bit of the message changes.
    #[test]
    fn tag_is_sensitive_to_every_bit() {
        let key: [u8; 32] = core::array::from_fn(|i| (i * 9 + 1) as u8);
        let msg = [0x5au8; 64];
        let base = auth(&key, &msg);
        for byte in 0..msg.len() {
            for bit in 0..8 {
                let mut m = msg;
                m[byte] ^= 1 << bit;
                assert_ne!(auth(&key, &m), base, "byte {byte} bit {bit} did not change the tag");
            }
        }
    }
}
