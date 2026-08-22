//! Winsock implementation: `WSASendTo` / `WSARecvFrom` loops (one FFI crossing per batch),
//! `WSAPoll` for receive timeouts, and UDP Segmentation Offload — `UDP_SEND_MSG_SIZE`, the
//! Windows 10 2004+ equivalent of Linux `UDP_SEGMENT`; the stack segments in software where the
//! adapter (or loopback) does not — for `send_gso`. Windows has no `sendmmsg` or busy polling;
//! receive coalescing (`UDP_RECV_MAX_COALESCED_SIZE`) is not used yet.

use super::{decode_sockaddr, encode_sockaddr, recv_view, send_view, PacketDesc, RecvBackend, SockAddrBuf, SOCKADDR_MAX, SOCKET_BUFFER_BYTES};
use std::os::windows::io::{AsRawSocket, IntoRawSocket};
use std::ptr::null_mut;
use std::sync::atomic::{AtomicBool, Ordering};
use windows_sys::Win32::Networking::WinSock::{
    closesocket, getsockname, ioctlsocket, setsockopt, WSAGetLastError, WSAIoctl, WSARecvFrom, WSASendTo, FIONBIO, IPPROTO_UDP,
    SIO_UDP_CONNRESET, SOCKADDR, SOCKET, SOCKET_ERROR, SOL_SOCKET, SO_RCVBUF, SO_RCVTIMEO, SO_SNDBUF, UDP_SEND_MSG_SIZE, WSABUF,
    WSAECONNRESET, WSAEINVAL, WSAEMSGSIZE, WSAENOPROTOOPT, WSAEOPNOTSUPP, WSAETIMEDOUT, WSAEWOULDBLOCK,
};

pub(crate) const EINVAL_CODE: i32 = WSAEINVAL;
pub(crate) const EIO_CODE: i32 = WSAEINVAL;

/// Set once `UDP_SEND_MSG_SIZE` is refused with "no such option" (pre-2004 Windows): every later
/// `send_gso` segments in user space without retrying the option.
static USO_UNSUPPORTED: AtomicBool = AtomicBool::new(false);

fn last_error() -> i32 {
    // SAFETY: FFI; reads the calling thread's Winsock error slot.
    unsafe { WSAGetLastError() }
}

/// `-code` if nothing was done yet, otherwise the partial count.
fn fail(done: usize, code: i32) -> i32 {
    if done == 0 {
        -code
    } else {
        done as i32
    }
}

pub(crate) fn open(bind_addr: &str, port: u16) -> i64 {
    let sock = match super::bind_std(bind_addr, port) {
        Ok(s) => s,
        Err(code) => return -(code as i64),
    };
    let s = sock.as_raw_socket() as SOCKET;
    for opt in [SO_RCVBUF, SO_SNDBUF] {
        let v: i32 = SOCKET_BUFFER_BYTES;
        // SAFETY: FFI; `v` outlives the call. Failure is deliberately ignored.
        unsafe { setsockopt(s, SOL_SOCKET, opt, &v as *const i32 as *const u8, std::mem::size_of::<i32>() as i32) };
    }
    // Classic Windows UDP foot-gun: an ICMP port-unreachable for an earlier send makes a later
    // recv fail with WSAECONNRESET. Turn that behaviour off; failure here is harmless.
    let off: u32 = 0;
    let mut returned: u32 = 0;
    // SAFETY: FFI; in/out buffers are live locals of the advertised sizes, no overlapped I/O.
    unsafe {
        WSAIoctl(
            s,
            SIO_UDP_CONNRESET,
            &off as *const u32 as *const std::ffi::c_void,
            std::mem::size_of::<u32>() as u32,
            null_mut(),
            0,
            &mut returned,
            null_mut(),
            None,
        )
    };
    sock.into_raw_socket() as i64
}

pub(crate) fn close(fd: i64) -> i32 {
    // SAFETY: FFI; the handle came from `open`.
    if unsafe { closesocket(fd as SOCKET) } == SOCKET_ERROR {
        -last_error()
    } else {
        0
    }
}

pub(crate) fn local_port(fd: i64) -> i32 {
    let mut name = SockAddrBuf::zeroed();
    let mut len = SOCKADDR_MAX as i32;
    // SAFETY: FFI; `name` provides `SOCKADDR_MAX` writable bytes and `len` says so.
    if unsafe { getsockname(fd as SOCKET, name.0.as_mut_ptr() as *mut SOCKADDR, &mut len) } == SOCKET_ERROR {
        return -last_error();
    }
    let mut d = PacketDesc::empty();
    decode_sockaddr(&name.0, len as usize, &mut d);
    if d.family == super::FAMILY_NONE {
        -EINVAL_CODE
    } else {
        d.port as i32
    }
}

/// `pkts` in order with one `WSASendTo` each, under the socket's send lock (see `send_gso`).
pub(crate) fn send_batch(fd: i64, pkts: &[PacketDesc]) -> i32 {
    let st = super::state(fd);
    let _guard = st.send.lock().unwrap_or_else(|e| e.into_inner());
    send_locked(fd as SOCKET, pkts)
}

fn send_locked(s: SOCKET, pkts: &[PacketDesc]) -> i32 {
    let mut count = 0usize;
    for p in pkts {
        // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `len` bytes.
        let Some(data) = (unsafe { send_view(p) }) else {
            return fail(count, WSAEINVAL);
        };
        let mut name = SockAddrBuf::zeroed();
        let Some(name_len) = encode_sockaddr(p, &mut name) else {
            return fail(count, WSAEINVAL);
        };
        let wsabuf = WSABUF { len: data.len() as u32, buf: data.as_ptr() as *mut u8 };
        let mut sent = 0u32;
        // SAFETY: FFI; `data`, `name` and `sent` outlive the (synchronous, non-overlapped) call.
        let r = unsafe {
            WSASendTo(s, &wsabuf, 1, &mut sent, 0, name.0.as_ptr() as *const SOCKADDR, name_len as i32, null_mut(), None)
        };
        if r == SOCKET_ERROR {
            let e = last_error();
            if e == WSAEWOULDBLOCK {
                return count as i32;
            }
            return fail(count, e);
        }
        count += 1;
    }
    count as i32
}

/// One `WSARecvFrom` into `p`: `1` received, `0` would block / timed out, `-code`.
fn recv_into(s: SOCKET, p: &mut PacketDesc) -> i32 {
    // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `cap` bytes.
    let Some(buf) = (unsafe { recv_view(p) }) else {
        return -WSAEINVAL;
    };
    let cap = buf.len() as u32;
    let wsabuf = WSABUF { len: cap, buf: buf.as_mut_ptr() };
    loop {
        let mut received = 0u32;
        let mut flags = 0u32;
        let mut name = SockAddrBuf::zeroed();
        let mut name_len = SOCKADDR_MAX as i32;
        // SAFETY: FFI; every pointer references a live local or the caller's buffer for the
        // duration of the synchronous, non-overlapped call.
        let r = unsafe {
            WSARecvFrom(
                s,
                &wsabuf,
                1,
                &mut received,
                &mut flags,
                name.0.as_mut_ptr() as *mut SOCKADDR,
                &mut name_len,
                null_mut(),
                None,
            )
        };
        if r == SOCKET_ERROR {
            match last_error() {
                // Datagram larger than the slot: the first `cap` bytes were delivered, rest dropped.
                WSAEMSGSIZE => received = cap,
                WSAEWOULDBLOCK | WSAETIMEDOUT => return 0,
                // Stale ICMP unreachable report (should not happen with SIO_UDP_CONNRESET off).
                WSAECONNRESET => continue,
                e => return -e,
            }
        }
        p.len = received.min(cap);
        decode_sockaddr(&name.0, name_len as usize, p);
        return 1;
    }
}

struct Sock(SOCKET);

impl RecvBackend for Sock {
    /// Non-blocking drain: `WSARecvFrom` until the socket would block or `pkts` is full.
    fn drain(&self, pkts: &mut [PacketDesc]) -> i32 {
        let mut count = 0usize;
        while count < pkts.len() {
            match recv_into(self.0, &mut pkts[count]) {
                1 => count += 1,
                0 => break,
                e => return fail(count, -e),
            }
        }
        count as i32
    }

    fn recv_one(&self, pkt: &mut PacketDesc) -> i32 {
        recv_into(self.0, pkt)
    }

    fn set_nonblocking(&self, on: bool) -> i32 {
        let mut mode: u32 = if on { 1 } else { 0 };
        // SAFETY: FFI; `mode` outlives the call.
        if unsafe { ioctlsocket(self.0, FIONBIO, &mut mode) } == SOCKET_ERROR {
            -last_error()
        } else {
            0
        }
    }

    fn set_recv_timeout(&self, ms: i32) -> i32 {
        let v: u32 = ms.max(0) as u32; // 0 = no timeout
        // SAFETY: FFI; `v` outlives the call.
        if unsafe { setsockopt(self.0, SOL_SOCKET, SO_RCVTIMEO, &v as *const u32 as *const u8, std::mem::size_of::<u32>() as i32) } == SOCKET_ERROR {
            -last_error()
        } else {
            0
        }
    }
}

pub(crate) fn recv_batch(fd: i64, pkts: &mut [PacketDesc], timeout_ms: i32) -> i32 {
    super::recv_adaptive(fd, pkts, timeout_ms, &Sock(fd as SOCKET))
}

fn set_send_msg_size(s: SOCKET, size: u32) -> i32 {
    // SAFETY: FFI; `size` outlives the call.
    unsafe { setsockopt(s, IPPROTO_UDP, UDP_SEND_MSG_SIZE, &size as *const u32 as *const u8, std::mem::size_of::<u32>() as i32) }
}

/// USO: `UDP_SEND_MSG_SIZE = seg_size`, one `WSASendTo` of the whole buffer, option reset. The
/// option is socket state, so the socket's send lock is held throughout — a plain send from
/// another thread in between would be segmented as well. Anything the stack refuses falls back
/// to user-space segmentation (same bytes on the wire).
pub(crate) fn send_gso(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
    if seg_size == 0 || data.is_empty() {
        return -WSAEINVAL;
    }
    if data.len() <= seg_size as usize || USO_UNSUPPORTED.load(Ordering::Relaxed) {
        return super::send_segmented(fd, data, seg_size, dst);
    }
    let s = fd as SOCKET;
    let mut name = SockAddrBuf::zeroed();
    let Some(name_len) = encode_sockaddr(dst, &mut name) else {
        return -WSAEINVAL;
    };
    let st = super::state(fd);
    let err = {
        let _guard = st.send.lock().unwrap_or_else(|e| e.into_inner());
        if set_send_msg_size(s, seg_size as u32) == SOCKET_ERROR {
            let e = last_error();
            if e == WSAENOPROTOOPT || e == WSAEINVAL {
                USO_UNSUPPORTED.store(true, Ordering::Relaxed);
            }
            WSAEOPNOTSUPP
        } else {
            let wsabuf = WSABUF { len: data.len() as u32, buf: data.as_ptr() as *mut u8 };
            let mut sent = 0u32;
            // SAFETY: FFI; `data`, `name` and `sent` outlive the (synchronous, non-overlapped) call.
            let r = unsafe {
                WSASendTo(s, &wsabuf, 1, &mut sent, 0, name.0.as_ptr() as *const SOCKADDR, name_len as i32, null_mut(), None)
            };
            let e = if r == SOCKET_ERROR { last_error() } else { 0 };
            set_send_msg_size(s, 0); // always: ordinary datagrams must never be segmented
            if e == 0 {
                return sent as i32;
            }
            e
        }
    };
    match err {
        WSAEWOULDBLOCK => 0,
        WSAEINVAL | WSAEOPNOTSUPP | WSAEMSGSIZE | WSAENOPROTOOPT => super::send_segmented(fd, data, seg_size, dst),
        e => -e,
    }
}

pub(crate) fn busy_poll(_fd: i64, _on: bool) -> i32 {
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A USO send must leave the socket in its plain state: a later datagram larger than the
    /// previous segment size goes out whole.
    #[test]
    fn uso_does_not_leak_into_plain_sends() {
        let tx = super::super::open("127.0.0.1", 0);
        let rx = super::super::open("127.0.0.1", 0);
        assert!(tx >= 0 && rx >= 0);
        let port = super::super::local_port(rx) as u16;
        let mut dst = PacketDesc::empty();
        dst.set_socket_addr(format!("127.0.0.1:{port}").parse().unwrap());
        let big: Vec<u8> = (0..900u32).map(|i| i as u8).collect();
        assert_eq!(super::super::send_gso(tx, &big, 300, &dst), 900);
        let plain: Vec<u8> = (0..1000u32).map(|i| (i * 3) as u8).collect();
        let p = PacketDesc { buf: plain.as_ptr() as *mut u8, len: 1000, cap: 1000, ..dst };
        assert_eq!(super::super::send_batch(tx, std::slice::from_ref(&p)), 1);
        let mut slots: Vec<Vec<u8>> = (0..8).map(|_| vec![0u8; 2048]).collect();
        let mut descs: Vec<PacketDesc> =
            slots.iter_mut().map(|s| PacketDesc { buf: s.as_mut_ptr(), len: 0, cap: 2048, ..PacketDesc::empty() }).collect();
        let mut lens = Vec::new();
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
        while lens.len() < 4 && std::time::Instant::now() < deadline {
            let r = super::super::recv_batch(rx, &mut descs, 500);
            assert!(r >= 0, "recv_batch: {r}");
            lens.extend(descs[..r as usize].iter().map(|d| d.len));
        }
        lens.sort_unstable();
        assert_eq!(lens, vec![300, 300, 300, 1000], "three USO segments, then one whole 1000-byte datagram");
        super::super::close(tx);
        super::super::close(rx);
    }

    /// `SO_RCVTIMEO` must honour small timeouts (the idle regime relies on it): 5 ms, not 500.
    #[test]
    fn blocking_receive_timeout_is_fine_grained() {
        let rx = super::super::open("127.0.0.1", 0);
        assert!(rx >= 0);
        let mut slot = [0u8; 64];
        let mut d = [PacketDesc { buf: slot.as_mut_ptr(), len: 0, cap: 64, ..PacketDesc::empty() }];
        for _ in 0..3 {
            let t0 = std::time::Instant::now();
            assert_eq!(super::super::recv_batch(rx, &mut d, 5), 0);
            let el = t0.elapsed();
            assert!(el >= std::time::Duration::from_millis(4) && el < std::time::Duration::from_millis(100), "5 ms timeout took {el:?}");
        }
        super::super::close(rx);
    }
}
