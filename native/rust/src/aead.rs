//! RFC 8439 ChaCha20-Poly1305, in place, for the packet datapath.
//!
//! The JVM's own provider is intrinsified and fast per byte, but its `Cipher` API costs a full
//! re-initialisation per packet (the Poly1305 state is rebuilt on every `init`) and its decrypt
//! buffers the whole ciphertext through a `ByteArrayOutputStream` before the tag check. Both were
//! top allocation sites in the bulk profile. This module does one in-place pass over the caller's
//! buffer and allocates nothing; the key schedule is a 32-byte copy, so there is nothing to cache.
//!
//! Same bytes as SunJCE and BouncyCastle — the JVM side pins all three against each other.

use chacha20poly1305::aead::{AeadInPlace, KeyInit};
use chacha20poly1305::{ChaCha20Poly1305, Key, Nonce, Tag};

/// Encrypts `buf[..pt_len]` in place under `key`/`nonce` with `aad`, writing the 16-byte tag to
/// `buf[pt_len..pt_len + 16]`. Returns `pt_len + 16`, or `-1` when `buf` is too short.
pub fn seal(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], buf: &mut [u8], pt_len: usize) -> i32 {
    if buf.len() < pt_len + 16 {
        return -1;
    }
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    let (pt, tag_out) = buf.split_at_mut(pt_len);
    match cipher.encrypt_in_place_detached(Nonce::from_slice(nonce), aad, pt) {
        Ok(tag) => {
            tag_out[..16].copy_from_slice(&tag);
            (pt_len + 16) as i32
        }
        Err(_) => -1,
    }
}

/// Verifies the tag at `buf[ct_len - 16..ct_len]` and decrypts `buf[..ct_len - 16]` in place.
/// Returns the plaintext length, or `-1` when the tag does not verify (the buffer is then
/// unspecified and must not be used).
pub fn open(key: &[u8; 32], nonce: &[u8; 12], aad: &[u8], buf: &mut [u8], ct_len: usize) -> i32 {
    if ct_len < 16 || buf.len() < ct_len {
        return -1;
    }
    let n = ct_len - 16;
    let cipher = ChaCha20Poly1305::new(Key::from_slice(key));
    let (ct, tag) = buf.split_at_mut(n);
    let tag = Tag::clone_from_slice(&tag[..16]);
    match cipher.decrypt_in_place_detached(Nonce::from_slice(nonce), aad, ct, &tag) {
        Ok(()) => n as i32,
        Err(_) => -1,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // RFC 8439 §2.8.2: the AEAD construction's own test vector.
    const KEY: [u8; 32] = [
        0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x8f,
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f,
    ];
    const NONCE: [u8; 12] = [0x07, 0x00, 0x00, 0x00, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47];
    const AAD: [u8; 12] = [0x50, 0x51, 0x52, 0x53, 0xc0, 0xc1, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7];
    const PLAINTEXT: &[u8] = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
    const TAG: [u8; 16] = [
        0x1a, 0xe1, 0x0b, 0x59, 0x4f, 0x09, 0xe2, 0x6a, 0x7e, 0x90, 0x2e, 0xcb, 0xd0, 0x60, 0x06, 0x91,
    ];
    const CT_HEAD: [u8; 16] = [
        0xd3, 0x1a, 0x8d, 0x34, 0x64, 0x8e, 0x60, 0xdb, 0x7b, 0x86, 0xaf, 0xbc, 0x53, 0xef, 0x7e, 0xc2,
    ];

    #[test]
    fn rfc8439_vector_seals_and_opens() {
        let mut buf = vec![0u8; PLAINTEXT.len() + 16];
        buf[..PLAINTEXT.len()].copy_from_slice(PLAINTEXT);
        let n = seal(&KEY, &NONCE, &AAD, &mut buf, PLAINTEXT.len());
        assert_eq!(n as usize, PLAINTEXT.len() + 16);
        assert_eq!(&buf[..16], &CT_HEAD);
        assert_eq!(&buf[PLAINTEXT.len()..], &TAG);
        let m = open(&KEY, &NONCE, &AAD, &mut buf, PLAINTEXT.len() + 16);
        assert_eq!(m as usize, PLAINTEXT.len());
        assert_eq!(&buf[..PLAINTEXT.len()], PLAINTEXT);
    }

    #[test]
    fn a_flipped_bit_is_refused_and_short_inputs_are_rejected() {
        let mut buf = vec![0u8; PLAINTEXT.len() + 16];
        buf[..PLAINTEXT.len()].copy_from_slice(PLAINTEXT);
        seal(&KEY, &NONCE, &AAD, &mut buf, PLAINTEXT.len());
        buf[3] ^= 1;
        assert_eq!(open(&KEY, &NONCE, &AAD, &mut buf, PLAINTEXT.len() + 16), -1);
        assert_eq!(open(&KEY, &NONCE, &AAD, &mut [0u8; 15], 15), -1);
        assert_eq!(seal(&KEY, &NONCE, &AAD, &mut [0u8; 20], 10), -1);
        // empty plaintext and empty AAD are legal: a 16-byte tag over nothing
        let mut empty = [0u8; 16];
        assert_eq!(seal(&KEY, &NONCE, &[], &mut empty, 0), 16);
        assert_eq!(open(&KEY, &NONCE, &[], &mut empty, 16), 0);
    }
}
