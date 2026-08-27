//! ChaCha20-Poly1305 AEAD, RFC 8439 §2.8.
//!
//! The construction, from the document:
//!   * the one-time Poly1305 key is the first 32 bytes of the ChaCha20 block at **counter 0**;
//!   * the ciphertext is ChaCha20 from **counter 1**;
//!   * the tag covers `aad || pad16(aad) || ciphertext || pad16(ciphertext) || le64(|aad|) || le64(|ct|)`.
//!
//! The length trailer is what stops an attacker shifting bytes between the AAD and the ciphertext,
//! so it is not optional padding — it is the separation.
//!
//! [`open`] compares tags with [`poly1305::verify`] (constant time) and, on failure, leaves the
//! output buffer untouched: a caller must never see partially decrypted bytes it might act on
//! before checking the return value.

use crate::chacha20;
use crate::poly1305;

/// Tag length in bytes. The transport may transmit a truncated prefix (its `tagLen` of 8), but the
/// tag is always computed in full and compared over the transmitted prefix.
pub const TAG_LEN: usize = 16;

/// Derives the one-time Poly1305 key for `nonce` (RFC 8439 §2.6).
fn poly_key(key: &[u8; 32], nonce: &[u8; 12]) -> [u8; 32] {
    let b = chacha20::block(key, 0, nonce);
    let mut k = [0u8; 32];
    k.copy_from_slice(&b[..32]);
    k
}

/// Absorbs the padded AAD/ciphertext and the length trailer (RFC 8439 §2.8.1).
fn mac(poly: &mut poly1305::Poly1305, aad: &[u8], ct: &[u8]) {
    const ZEROS: [u8; 16] = [0; 16];
    poly.update(aad);
    if aad.len() % 16 != 0 {
        poly.update(&ZEROS[..16 - aad.len() % 16]);
    }
    poly.update(ct);
    if ct.len() % 16 != 0 {
        poly.update(&ZEROS[..16 - ct.len() % 16]);
    }
    poly.update(&(aad.len() as u64).to_le_bytes());
    poly.update(&(ct.len() as u64).to_le_bytes());
}

/// Encrypts `buf` in place and returns the tag.
pub fn seal(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], buf: &mut [u8]) -> [u8; TAG_LEN] {
    let pk = poly_key(key, nonce);
    chacha20::apply_keystream(key, 1, nonce, buf);
    let mut poly = poly1305::Poly1305::new(&pk);
    mac(&mut poly, aad, buf);
    poly.finish()
}

/// Verifies `tag` and, only if it matches, decrypts `buf` in place. Returns false and leaves `buf`
/// unchanged otherwise. `tag` may be a truncated prefix of the full 16-byte tag (the transport's
/// 8-byte option); the comparison then covers exactly the bytes that were transmitted.
pub fn open(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], buf: &mut [u8], tag: &[u8]) -> bool {
    if tag.is_empty() || tag.len() > TAG_LEN {
        return false;
    }
    let pk = poly_key(key, nonce);
    let mut poly = poly1305::Poly1305::new(&pk);
    mac(&mut poly, aad, buf);
    let want = poly.finish();
    if !poly1305::verify(&want[..tag.len()], tag) {
        return false;
    }
    chacha20::apply_keystream(key, 1, nonce, buf);
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 8439 §2.8.2: the worked AEAD example, both directions.
    #[test]
    fn rfc8439_aead_vector() {
        let key: [u8; 32] = [
            0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
            0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
        ];
        let nonce: [u8; 12] = [0x07, 0x00, 0x00, 0x00, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47];
        let aad: [u8; 12] = [0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7];
        let plaintext = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";

        let mut buf = plaintext.to_vec();
        let tag = seal(&key, &nonce, &aad, &mut buf);

        let expect_ct: [u8; 114] = [
            0xd3, 0x1a, 0x8d, 0x34, 0x64, 0x8e, 0x60, 0xdb, 0x7b, 0x86, 0xaf, 0xbc, 0x53, 0xef, 0x7e, 0xc2,
            0xa4, 0xad, 0xed, 0x51, 0x29, 0x6e, 0x08, 0xfe, 0xa9, 0xe2, 0xb5, 0xa7, 0x36, 0xee, 0x62, 0xd6,
            0x3d, 0xbe, 0xa4, 0x5e, 0x8c, 0xa9, 0x67, 0x12, 0x82, 0xfa, 0xfb, 0x69, 0xda, 0x92, 0x72, 0x8b,
            0x1a, 0x71, 0xde, 0x0a, 0x9e, 0x06, 0x0b, 0x29, 0x05, 0xd6, 0xa5, 0xb6, 0x7e, 0xcd, 0x3b, 0x36,
            0x92, 0xdd, 0xbd, 0x7f, 0x2d, 0x77, 0x8b, 0x8c, 0x98, 0x03, 0xae, 0xe3, 0x28, 0x09, 0x1b, 0x58,
            0xfa, 0xb3, 0x24, 0xe4, 0xfa, 0xd6, 0x75, 0x94, 0x55, 0x85, 0x80, 0x8b, 0x48, 0x31, 0xd7, 0xbc,
            0x3f, 0xf4, 0xde, 0xf0, 0x8e, 0x4b, 0x7a, 0x9d, 0xe5, 0x76, 0xd2, 0x65, 0x86, 0xce, 0xc6, 0x4b,
            0x61, 0x16,
        ];
        let expect_tag: [u8; 16] = [
            0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a, 0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60, 0x06, 0x91,
        ];
        assert_eq!(&buf[..], &expect_ct[..], "ciphertext");
        assert_eq!(tag, expect_tag, "tag");

        assert!(open(&key, &nonce, &aad, &mut buf, &tag));
        assert_eq!(&buf[..], &plaintext[..], "round trip");
    }

    /// Every single-bit tamper of the ciphertext, the AAD or the tag must be refused, and a refused
    /// open must leave the buffer as it found it.
    #[test]
    fn tampering_is_refused_and_leaves_the_buffer_untouched() {
        let key: [u8; 32] = core::array::from_fn(|i| (i * 3) as u8);
        let nonce: [u8; 12] = core::array::from_fn(|i| (i * 5) as u8);
        let aad = [0xAAu8; 12];
        let mut sealed = vec![0x42u8; 64];
        let tag = seal(&key, &nonce, &aad, &mut sealed);

        for byte in 0..sealed.len() {
            let mut buf = sealed.clone();
            buf[byte] ^= 0x01;
            let before = buf.clone();
            assert!(!open(&key, &nonce, &aad, &mut buf, &tag), "ciphertext byte {byte} accepted");
            assert_eq!(buf, before, "refused open modified the buffer");
        }
        for byte in 0..aad.len() {
            let mut a = aad;
            a[byte] ^= 0x01;
            let mut buf = sealed.clone();
            assert!(!open(&key, &nonce, &a, &mut buf, &tag), "aad byte {byte} accepted");
        }
        for byte in 0..tag.len() {
            let mut t = tag;
            t[byte] ^= 0x01;
            let mut buf = sealed.clone();
            assert!(!open(&key, &nonce, &aad, &mut buf, &t), "tag byte {byte} accepted");
        }
        // a nonce that differs from the sealing one must also fail
        let mut n = nonce;
        n[0] ^= 0x01;
        let mut buf = sealed.clone();
        assert!(!open(&key, &n, &aad, &mut buf, &tag), "wrong nonce accepted");
    }

    /// Round-trips at every length across the 64-byte block and 16-byte MAC boundaries, with and
    /// without AAD, including the empty cases.
    #[test]
    fn round_trips_at_every_boundary() {
        let key: [u8; 32] = core::array::from_fn(|i| (i * 7 + 2) as u8);
        let nonce: [u8; 12] = core::array::from_fn(|i| (i + 1) as u8);
        for len in 0..150usize {
            for aad_len in [0usize, 1, 15, 16, 17] {
                let aad: Vec<u8> = (0..aad_len).map(|i| (i * 13) as u8).collect();
                let plain: Vec<u8> = (0..len).map(|i| (i * 3) as u8).collect();
                let mut buf = plain.clone();
                let tag = seal(&key, &nonce, &aad, &mut buf);
                assert!(open(&key, &nonce, &aad, &mut buf, &tag), "len {len} aad {aad_len}");
                assert_eq!(buf, plain, "len {len} aad {aad_len}");
            }
        }
    }

    /// Throughput probe, not a correctness test: `cargo test --release -- --ignored --nocapture
    /// aead_throughput`. Compare with `bench profile`'s JVM figure for the same 1200-byte packet.
    #[test]
    #[ignore]
    fn aead_throughput() {
        use std::time::Instant;
        let key = [7u8; 32];
        let nonce = [3u8; 12];
        let aad = [1u8; 9];
        let n = 200_000;
        let size = 1200usize;
        let mut buf = vec![0u8; size];
        for _ in 0..20_000 {
            seal(&key, &nonce, &aad, &mut buf);
        }
        let t0 = Instant::now();
        for _ in 0..n {
            std::hint::black_box(seal(&key, &nonce, &aad, &mut buf));
        }
        let seal_ns = t0.elapsed().as_nanos() as f64 / n as f64;

        let mut sealed = vec![0u8; size];
        let tag = seal(&key, &nonce, &aad, &mut sealed);
        let mut work = sealed.clone();
        let t1 = Instant::now();
        for _ in 0..n {
            work.copy_from_slice(&sealed);
            std::hint::black_box(open(&key, &nonce, &aad, &mut work, &tag));
        }
        let open_ns = t1.elapsed().as_nanos() as f64 / n as f64;
        println!(
            "aead {size} B: seal {:.2} us ({:.0} MB/s)  open {:.2} us (copy included)",
            seal_ns / 1e3,
            size as f64 / (seal_ns / 1e9) / 1e6,
            open_ns / 1e3
        );
    }

    /// A truncated tag must verify over exactly the transmitted prefix (the transport's tagLen 8).
    #[test]
    fn truncated_tag_verifies_over_its_prefix() {
        let key: [u8; 32] = core::array::from_fn(|i| i as u8);
        let nonce: [u8; 12] = [9; 12];
        let aad = [1u8; 4];
        let mut buf = vec![7u8; 40];
        let tag = seal(&key, &nonce, &aad, &mut buf);
        let mut b = buf.clone();
        assert!(open(&key, &nonce, &aad, &mut b, &tag[..8]));
        let mut wrong = tag;
        wrong[7] ^= 0x80;
        let mut b2 = buf.clone();
        assert!(!open(&key, &nonce, &aad, &mut b2, &wrong[..8]));
        // a difference beyond the transmitted prefix is by definition not transmitted, so an
        // 8-byte open must still accept it: this documents the truncation's actual guarantee.
        let mut beyond = tag;
        beyond[15] ^= 0x80;
        let mut b3 = buf.clone();
        assert!(open(&key, &nonce, &aad, &mut b3, &beyond[..8]));
    }
}
