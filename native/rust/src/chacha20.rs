//! ChaCha20 stream cipher, RFC 8439 §2.
//!
//! Implemented from the document, like the rest of this crate's protocol code. No third-party
//! crypto dependency: see `NOTICE` for the provenance entry.
//!
//! The state is 16 little-endian words — four constants, eight key words, one block counter, three
//! nonce words (the IETF variant of §2.3, not the original 64/64 counter/nonce split). Twenty
//! rounds are ten doubles of a column round and a diagonal round; the output block is the permuted
//! state added word-wise to the original, so the permutation stays invertible while the block
//! function does not.
//!
//! Constant-time by construction: every operation is a fixed sequence of wrapping adds, xors and
//! rotates on `u32`, with no data-dependent branch, index or shift.

/// `"expand 32-byte k"` as four little-endian words (RFC 8439 §2.3).
const CONSTANTS: [u32; 4] = [0x6170_7865, 0x3320_646e, 0x7962_2d32, 0x6b20_6574];

/// One quarter round on the state words at `a`, `b`, `c`, `d` (RFC 8439 §2.1).
#[inline(always)]
fn quarter_round(s: &mut [u32; 16], a: usize, b: usize, c: usize, d: usize) {
    s[a] = s[a].wrapping_add(s[b]);
    s[d] = (s[d] ^ s[a]).rotate_left(16);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] = (s[b] ^ s[c]).rotate_left(12);
    s[a] = s[a].wrapping_add(s[b]);
    s[d] = (s[d] ^ s[a]).rotate_left(8);
    s[c] = s[c].wrapping_add(s[d]);
    s[b] = (s[b] ^ s[c]).rotate_left(7);
}

/// Builds the initial state for `key`, `counter` and `nonce` (RFC 8439 §2.3).
#[inline]
fn state(key: &[u8; 32], counter: u32, nonce: &[u8; 12]) -> [u32; 16] {
    let mut s = [0u32; 16];
    s[0..4].copy_from_slice(&CONSTANTS);
    for i in 0..8 {
        s[4 + i] = u32::from_le_bytes([key[4 * i], key[4 * i + 1], key[4 * i + 2], key[4 * i + 3]]);
    }
    s[12] = counter;
    for i in 0..3 {
        s[13 + i] = u32::from_le_bytes([nonce[4 * i], nonce[4 * i + 1], nonce[4 * i + 2], nonce[4 * i + 3]]);
    }
    s
}

/// The ChaCha20 block function: 20 rounds over the state, then the word-wise add (RFC 8439 §2.3).
pub fn block(key: &[u8; 32], counter: u32, nonce: &[u8; 12]) -> [u8; 64] {
    let start = state(key, counter, nonce);
    let mut s = start;
    for _ in 0..10 {
        // column rounds
        quarter_round(&mut s, 0, 4, 8, 12);
        quarter_round(&mut s, 1, 5, 9, 13);
        quarter_round(&mut s, 2, 6, 10, 14);
        quarter_round(&mut s, 3, 7, 11, 15);
        // diagonal rounds
        quarter_round(&mut s, 0, 5, 10, 15);
        quarter_round(&mut s, 1, 6, 11, 12);
        quarter_round(&mut s, 2, 7, 8, 13);
        quarter_round(&mut s, 3, 4, 9, 14);
    }
    let mut out = [0u8; 64];
    for i in 0..16 {
        out[4 * i..4 * i + 4].copy_from_slice(&s[i].wrapping_add(start[i]).to_le_bytes());
    }
    out
}

/// XORs `data` in place with the keystream from `counter` onwards (RFC 8439 §2.4).
///
/// The counter is 32 bits and wraps, which caps one invocation at 256 GiB; every caller here works
/// on single datagrams, so the cap is unreachable rather than enforced.
pub fn apply_keystream(key: &[u8; 32], counter: u32, nonce: &[u8; 12], data: &mut [u8]) {
    for (i, chunk) in data.chunks_mut(64).enumerate() {
        let ks = block(key, counter.wrapping_add(i as u32), nonce);
        for (b, k) in chunk.iter_mut().zip(ks.iter()) {
            *b ^= *k;
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// RFC 8439 §2.3.2: the worked block-function example.
    #[test]
    fn rfc8439_block_vector() {
        let key: [u8; 32] = core::array::from_fn(|i| i as u8);
        let nonce: [u8; 12] = [0, 0, 0, 9, 0, 0, 0, 0x4a, 0, 0, 0, 0];
        let out = block(&key, 1, &nonce);
        let expect: [u8; 64] = [
            0x10, 0xf1, 0xe7, 0xe4, 0xd1, 0x3b, 0x59, 0x15, 0x50, 0x0f, 0xdd, 0x1f, 0xa3, 0x20, 0x71, 0xc4,
            0xc7, 0xd1, 0xf4, 0xc7, 0x33, 0xc0, 0x68, 0x03, 0x04, 0x22, 0xaa, 0x9a, 0xc3, 0xd4, 0x6c, 0x4e,
            0xd2, 0x82, 0x64, 0x46, 0x07, 0x9f, 0xaa, 0x09, 0x14, 0xc2, 0xd7, 0x05, 0xd9, 0x8b, 0x02, 0xa2,
            0xb5, 0x12, 0x9c, 0xd1, 0xde, 0x16, 0x4e, 0xb9, 0xcb, 0xd0, 0x83, 0xe8, 0xa2, 0x50, 0x3c, 0x4e,
        ];
        assert_eq!(out, expect);
    }

    /// RFC 8439 §2.4.2: encrypting the "Ladies and Gentlemen" plaintext at counter 1.
    #[test]
    fn rfc8439_encrypt_vector() {
        let key: [u8; 32] = core::array::from_fn(|i| i as u8);
        let nonce: [u8; 12] = [0, 0, 0, 0, 0, 0, 0, 0x4a, 0, 0, 0, 0];
        let mut data = *b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
        apply_keystream(&key, 1, &nonce, &mut data);
        let expect: [u8; 114] = [
            0x6e, 0x2e, 0x35, 0x9a, 0x25, 0x68, 0xf9, 0x80, 0x41, 0xba, 0x07, 0x28, 0xdd, 0x0d, 0x69, 0x81,
            0xe9, 0x7e, 0x7a, 0xec, 0x1d, 0x43, 0x60, 0xc2, 0x0a, 0x27, 0xaf, 0xcc, 0xfd, 0x9f, 0xae, 0x0b,
            0xf9, 0x1b, 0x65, 0xc5, 0x52, 0x47, 0x33, 0xab, 0x8f, 0x59, 0x3d, 0xab, 0xcd, 0x62, 0xb3, 0x57,
            0x16, 0x39, 0xd6, 0x24, 0xe6, 0x51, 0x52, 0xab, 0x8f, 0x53, 0x0c, 0x35, 0x9f, 0x08, 0x61, 0xd8,
            0x07, 0xca, 0x0d, 0xbf, 0x50, 0x0d, 0x6a, 0x61, 0x56, 0xa3, 0x8e, 0x08, 0x8a, 0x22, 0xb6, 0x5e,
            0x52, 0xbc, 0x51, 0x4d, 0x16, 0xcc, 0xf8, 0x06, 0x81, 0x8c, 0xe9, 0x1a, 0xb7, 0x79, 0x37, 0x36,
            0x5a, 0xf9, 0x0b, 0xbf, 0x74, 0xa3, 0x5b, 0xe6, 0xb4, 0x0b, 0x8e, 0xed, 0xf2, 0x78, 0x5e, 0x42,
            0x87, 0x4d,
        ];
        assert_eq!(&data[..], &expect[..]);
    }

    /// Keystream XOR is an involution: applying it twice restores the plaintext, at every length
    /// around the 64-byte block boundary (the chunking is the only place a length bug can hide).
    #[test]
    fn keystream_round_trips_at_every_boundary() {
        let key: [u8; 32] = core::array::from_fn(|i| (i * 3 + 1) as u8);
        let nonce: [u8; 12] = core::array::from_fn(|i| (i * 7) as u8);
        for len in 0..200usize {
            let original: Vec<u8> = (0..len).map(|i| (i * 11) as u8).collect();
            let mut buf = original.clone();
            apply_keystream(&key, 1, &nonce, &mut buf);
            if len > 0 {
                assert_ne!(buf, original, "len {len} was not encrypted at all");
            }
            apply_keystream(&key, 1, &nonce, &mut buf);
            assert_eq!(buf, original, "len {len} did not round-trip");
        }
    }
}
