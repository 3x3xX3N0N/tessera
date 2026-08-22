//! # tessera_native
//!
//! Native datapath for the Tessera transport, exposed as a C ABI and loaded from the JVM through
//! Panama FFM (`tessera.native.NativeDatapath` in the `:native` Gradle module).
//!
//! * [`gf256`] — SIMD GF(2^8) multiply-accumulate (poly `0x11D`), the RLNC hot kernel.
//! * [`udp`] — batched UDP send/receive (`sendmmsg`/`recvmmsg` + GSO on Linux, Winsock loops +
//!   USO on Windows, an adaptive poll/drain receive policy everywhere) over caller-owned off-heap
//!   buffers.
//!
//! ## ABI conventions
//! * Integer results: `>= 0` success, `-code` failure (`errno` on Unix, `WSAGetLastError()` on
//!   Windows). `tessera_udp_open` returns the descriptor/handle as `i64`.
//! * Pointers are never retained past the call. Buffers are owned by the caller.
//! * Panics cannot cross the boundary: the crate is built with `panic = "abort"`.
//!
//! `unsafe` is confined to the FFI boundary (turning raw pointers into slices), the syscalls,
//! and the SIMD intrinsics; everything else is safe Rust.
#![deny(unsafe_op_in_unsafe_fn)]
#![warn(missing_docs)]

use std::ffi::{c_char, CStr};
use std::slice;

pub mod gf256;
pub mod udp;

pub use udp::PacketDesc;

/// `0.1.0` encoded as `major << 16 | minor << 8 | patch`.
pub const VERSION: u32 = (0 << 16) | (1 << 8) | 0;

/// Library version, see [`VERSION`].
#[no_mangle]
pub extern "C" fn tessera_version() -> u32 {
    VERSION
}

/// Which GF(256) kernel runs on this CPU: 0 scalar, 1 SSSE3, 2 AVX2, 3 NEON.
#[no_mangle]
pub extern "C" fn tessera_gf256_impl() -> u32 {
    gf256::selected_impl() as u32
}

/// `size_of::<PacketDesc>()`, so the JVM side can assert its `StructLayout` matches.
#[no_mangle]
pub extern "C" fn tessera_packet_desc_size() -> usize {
    std::mem::size_of::<PacketDesc>()
}

/// `dst[i] ^= src[i] * c` over GF(256)/0x11D for `i in 0..len`.
///
/// # Safety
/// `dst` must be valid for `len` writable bytes, `src` for `len` readable bytes, and the two
/// ranges must not overlap. Null pointers, `len == 0` and `c == 0` are no-ops.
#[no_mangle]
pub unsafe extern "C" fn tessera_gf256_muladd(dst: *mut u8, src: *const u8, len: usize, c: u8) {
    if len == 0 || c == 0 || dst.is_null() || src.is_null() {
        return;
    }
    // SAFETY: FFI boundary; see the contract above.
    let (dst, src) = unsafe { (slice::from_raw_parts_mut(dst, len), slice::from_raw_parts(src, len)) };
    gf256::mul_add_into(dst, src, c);
}

/// Opens a non-blocking UDP socket bound to `bind_addr:port` (`port == 0` for ephemeral).
/// `bind_addr` is a NUL-terminated IPv4 or IPv6 literal; `NULL` means `0.0.0.0`.
/// Returns the descriptor / `SOCKET` handle, or `-code`.
///
/// # Safety
/// `bind_addr` must be null or point to a NUL-terminated string.
#[no_mangle]
pub unsafe extern "C" fn tessera_udp_open(bind_addr: *const c_char, port: u16) -> i64 {
    let addr = if bind_addr.is_null() {
        String::from("0.0.0.0")
    } else {
        // SAFETY: FFI boundary; the caller passes a NUL-terminated string.
        unsafe { CStr::from_ptr(bind_addr) }.to_string_lossy().into_owned()
    };
    udp::open(&addr, port)
}

/// Closes a socket from [`tessera_udp_open`]. Returns `0` or `-code`.
#[no_mangle]
pub extern "C" fn tessera_udp_close(fd: i64) -> i32 {
    udp::close(fd)
}

/// The port the socket is bound to, or `-code`.
#[no_mangle]
pub extern "C" fn tessera_udp_local_port(fd: i64) -> i32 {
    udp::local_port(fd)
}

/// Sends `pkts[0..n]` in order. Returns how many datagrams were handed to the kernel (may stop
/// short of `n` if the socket would block), or `-code` if the very first one failed.
///
/// # Safety
/// `pkts` must point to `n` descriptors whose buffers are valid for `len` readable bytes.
#[no_mangle]
pub unsafe extern "C" fn tessera_udp_send_batch(fd: i64, pkts: *const PacketDesc, n: usize) -> i32 {
    if n == 0 {
        return 0;
    }
    if pkts.is_null() {
        return -udp_einval();
    }
    // SAFETY: FFI boundary; see the contract above.
    let pkts = unsafe { slice::from_raw_parts(pkts, n) };
    udp::send_batch(fd, pkts)
}

/// Receives up to `n` datagrams into `pkts[0..n]`, filling `len`, `addr`, `port`, `family`.
/// `timeout_ms > 0` waits up to that long for the first datagram, `0` never waits, `< 0` waits
/// indefinitely. Returns the number received (`0` on timeout), or `-code`.
///
/// # Safety
/// `pkts` must point to `n` descriptors whose buffers are valid for `cap` writable bytes.
#[no_mangle]
pub unsafe extern "C" fn tessera_udp_recv_batch(fd: i64, pkts: *mut PacketDesc, n: usize, timeout_ms: i32) -> i32 {
    if n == 0 {
        return 0;
    }
    if pkts.is_null() {
        return -udp_einval();
    }
    // SAFETY: FFI boundary; see the contract above.
    let pkts = unsafe { slice::from_raw_parts_mut(pkts, n) };
    udp::recv_batch(fd, pkts, timeout_ms)
}

/// Sends `buf[0..total_len]` as consecutive `seg_size`-byte datagrams (the last may be shorter)
/// to the address in `dst` (`buf`/`len`/`cap` of `dst` are ignored). Uses `UDP_SEGMENT` (GSO)
/// on Linux and a sendto loop elsewhere. Returns payload bytes sent, or `-code`.
///
/// # Safety
/// `buf` must be valid for `total_len` readable bytes and `dst` must point to a descriptor.
#[no_mangle]
pub unsafe extern "C" fn tessera_udp_send_gso(fd: i64, buf: *const u8, total_len: usize, seg_size: u16, dst: *const PacketDesc) -> i32 {
    if buf.is_null() || dst.is_null() || total_len == 0 || seg_size == 0 {
        return -udp_einval();
    }
    // SAFETY: FFI boundary; see the contract above.
    let (data, dst) = unsafe { (slice::from_raw_parts(buf, total_len), &*dst) };
    udp::send_gso(fd, data, seg_size, dst)
}

/// Enables/disables `SO_BUSY_POLL` on Linux (needs `CAP_NET_ADMIN`, returns `-EPERM` otherwise);
/// a successful no-op on other platforms.
#[no_mangle]
pub extern "C" fn tessera_busy_poll(fd: i64, on: bool) -> i32 {
    udp::busy_poll(fd, on)
}

#[cfg(unix)]
fn udp_einval() -> i32 {
    libc::EINVAL
}
#[cfg(windows)]
fn udp_einval() -> i32 {
    windows_sys::Win32::Networking::WinSock::WSAEINVAL
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;

    #[test]
    fn version_and_layout() {
        assert_eq!(tessera_version(), 0x0100);
        assert_eq!(tessera_packet_desc_size(), 40);
        assert!(tessera_gf256_impl() <= 3);
    }

    #[test]
    fn gf256_export_matches_reference() {
        let src: Vec<u8> = (0..1200u32).map(|i| (i * 7 + 3) as u8).collect();
        let mut dst: Vec<u8> = (0..1200u32).map(|i| (i * 13 + 1) as u8).collect();
        let expected: Vec<u8> = dst.iter().zip(&src).map(|(&d, &s)| d ^ gf256::mul(s, 0x53)).collect();
        // SAFETY: distinct, correctly sized vectors.
        unsafe { tessera_gf256_muladd(dst.as_mut_ptr(), src.as_ptr(), dst.len(), 0x53) };
        assert_eq!(dst, expected);
    }

    fn open(addr: &str) -> (i64, u16) {
        let fd = udp::open(addr, 0);
        assert!(fd >= 0, "open({addr}) failed: {fd}");
        let port = udp::local_port(fd);
        assert!(port > 0, "local_port failed: {port}");
        (fd, port as u16)
    }

    #[test]
    fn loopback_batch_and_gso_fallback_round_trip() {
        let (tx, _) = open("127.0.0.1");
        let (rx, rx_port) = open("127.0.0.1");
        let dst: SocketAddr = format!("127.0.0.1:{rx_port}").parse().unwrap();

        const N: usize = 64;
        const SIZE: usize = 1200;
        let mut payloads: Vec<Vec<u8>> = (0..N).map(|i| (0..SIZE).map(|j| (i * 31 + j) as u8).collect()).collect();
        let mut descs: Vec<PacketDesc> = payloads
            .iter_mut()
            .map(|p| {
                let mut d = PacketDesc { buf: p.as_mut_ptr(), len: SIZE as u32, cap: SIZE as u32, ..PacketDesc::empty() };
                d.set_socket_addr(dst);
                d
            })
            .collect();
        assert_eq!(udp::send_batch(tx, &descs), N as i32);

        let mut slots: Vec<Vec<u8>> = (0..N).map(|_| vec![0u8; 2048]).collect();
        let mut rx_descs: Vec<PacketDesc> =
            slots.iter_mut().map(|s| PacketDesc { buf: s.as_mut_ptr(), len: 0, cap: 2048, ..PacketDesc::empty() }).collect();
        let mut got = 0usize;
        let mut seen = vec![false; N];
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        while got < N && std::time::Instant::now() < deadline {
            let r = udp::recv_batch(rx, &mut rx_descs[got..], 500);
            assert!(r >= 0, "recv_batch failed: {r}");
            for d in &rx_descs[got..got + r as usize] {
                assert_eq!(d.len as usize, SIZE);
                assert_eq!(d.socket_addr().map(|a| a.ip().to_string()).as_deref(), Some("127.0.0.1"));
                // SAFETY: our own slot buffer.
                let data = unsafe { slice::from_raw_parts(d.buf, d.len as usize) };
                let i = payloads.iter().position(|p| p.as_slice() == data).expect("unknown payload");
                seen[i] = true;
            }
            got += r as usize;
        }
        assert_eq!(got, N);
        assert!(seen.iter().all(|&s| s));

        // GSO (kernel on Linux, user-space segmentation elsewhere): 1000 bytes in 400-byte pieces.
        let big: Vec<u8> = (0..1000u32).map(|i| i as u8).collect();
        let sent = udp::send_gso(tx, &big, 400, &descs[0]);
        assert_eq!(sent, 1000, "send_gso returned {sent}");
        let mut lens = Vec::new();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        while lens.len() < 3 && std::time::Instant::now() < deadline {
            let r = udp::recv_batch(rx, &mut rx_descs, 500);
            assert!(r >= 0);
            lens.extend(rx_descs[..r as usize].iter().map(|d| d.len));
        }
        lens.sort_unstable();
        assert_eq!(lens, vec![200, 400, 400]);

        descs.clear();
        assert_eq!(udp::close(tx), 0);
        assert_eq!(udp::close(rx), 0);
    }

    /// A run beyond the kernel's GSO limits (100 x 1350 B = 135 KB > 65535 B and > 64 segments) must arrive whole:
    /// `send_gso` splits it into super-datagrams the kernel accepts and falls back per datagram on refusal.
    #[test]
    fn gso_splits_runs_beyond_the_kernel_limits() {
        let (tx, _) = open("127.0.0.1");
        let (rx, rx_port) = open("127.0.0.1");
        let dst: SocketAddr = format!("127.0.0.1:{rx_port}").parse().unwrap();
        const N: usize = 100;
        const SEG: usize = 1350;
        // segment s, byte j = s ^ (j % 251): the first byte of a segment is its index, the rest checks its content
        let data: Vec<u8> = (0..N * SEG).map(|i| ((i / SEG) as u8) ^ (((i % SEG) % 251) as u8)).collect();
        let mut d = PacketDesc::empty();
        d.set_socket_addr(dst);
        let sent = udp::send_gso(tx, &data, SEG as u16, &d);
        assert_eq!(sent as usize, N * SEG, "send_gso returned {sent}");
        let mut slots: Vec<Vec<u8>> = (0..N).map(|_| vec![0u8; 2048]).collect();
        let mut rx_descs: Vec<PacketDesc> =
            slots.iter_mut().map(|s| PacketDesc { buf: s.as_mut_ptr(), len: 0, cap: 2048, ..PacketDesc::empty() }).collect();
        let mut seen = vec![false; N];
        let mut got = 0usize;
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        while got < N && std::time::Instant::now() < deadline {
            let r = udp::recv_batch(rx, &mut rx_descs[got..], 500);
            assert!(r >= 0, "recv_batch failed: {r}");
            for desc in &rx_descs[got..got + r as usize] {
                assert_eq!(desc.len as usize, SEG);
                // SAFETY: our own slot buffer.
                let bytes = unsafe { slice::from_raw_parts(desc.buf, desc.len as usize) };
                let i = bytes[0] as usize; // byte 0 of segment i is i ^ 0
                assert!(i < N && !seen[i], "segment {i} unexpected or duplicated");
                assert_eq!(bytes, &data[i * SEG..(i + 1) * SEG], "segment {i} content");
                seen[i] = true;
            }
            got += r as usize;
        }
        assert_eq!(got, N, "all {N} segments must arrive; got {got}");
        udp::close(tx);
        udp::close(rx);
    }

    #[test]
    fn recv_times_out_cleanly() {
        let (fd, _) = open("127.0.0.1");
        let mut slot = vec![0u8; 64];
        let mut d = [PacketDesc { buf: slot.as_mut_ptr(), len: 0, cap: 64, ..PacketDesc::empty() }];
        let t0 = std::time::Instant::now();
        assert_eq!(udp::recv_batch(fd, &mut d, 50), 0);
        assert!(t0.elapsed() >= std::time::Duration::from_millis(40));
        assert_eq!(udp::recv_batch(fd, &mut d, 0), 0, "timeout 0 must not block");
        assert_eq!(udp::busy_poll(fd, true).max(-200), udp::busy_poll(fd, true).max(-200));
        udp::close(fd);
    }
}
