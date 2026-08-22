//! Winsock implementation: `WSASendTo` / `WSARecvFrom` loops (one FFI crossing per batch),
//! `WSAPoll` for receive timeouts. Windows has no `sendmmsg`, UDP GSO or busy polling.

use super::{decode_sockaddr, encode_sockaddr, recv_view, send_view, PacketDesc, SockAddrBuf, SOCKADDR_MAX, SOCKET_BUFFER_BYTES};
use std::os::windows::io::{AsRawSocket, IntoRawSocket};
use std::ptr::null_mut;
use windows_sys::Win32::Networking::WinSock::{
    closesocket, getsockname, setsockopt, WSAGetLastError, WSAIoctl, WSAPoll, WSARecvFrom, WSASendTo, POLLRDNORM, SIO_UDP_CONNRESET,
    SOCKADDR, SOCKET, SOCKET_ERROR, SOL_SOCKET, SO_RCVBUF, SO_SNDBUF, WSABUF, WSAECONNRESET, WSAEINVAL, WSAEMSGSIZE, WSAEWOULDBLOCK,
    WSAPOLLFD,
};

pub(crate) const EINVAL_CODE: i32 = WSAEINVAL;
pub(crate) const EIO_CODE: i32 = WSAEINVAL;

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

/// `> 0` readable, `0` timed out, `< 0` error. Negative timeouts wait forever.
fn wait_readable(s: SOCKET, timeout_ms: i32) -> i32 {
    let mut pfd = WSAPOLLFD { fd: s, events: POLLRDNORM, revents: 0 };
    // SAFETY: FFI; exactly one pollfd is passed.
    let r = unsafe { WSAPoll(&mut pfd, 1, timeout_ms) };
    if r == SOCKET_ERROR {
        -last_error()
    } else {
        r
    }
}

pub(crate) fn send_batch(fd: i64, pkts: &[PacketDesc]) -> i32 {
    let s = fd as SOCKET;
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

pub(crate) fn recv_batch(fd: i64, pkts: &mut [PacketDesc], timeout_ms: i32) -> i32 {
    let s = fd as SOCKET;
    if timeout_ms != 0 {
        let r = wait_readable(s, timeout_ms);
        if r <= 0 {
            return r;
        }
    }
    let mut count = 0usize;
    while count < pkts.len() {
        let p = &mut pkts[count];
        // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `cap` bytes.
        let Some(buf) = (unsafe { recv_view(p) }) else {
            return fail(count, WSAEINVAL);
        };
        let cap = buf.len() as u32;
        let wsabuf = WSABUF { len: cap, buf: buf.as_mut_ptr() };
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
                WSAEWOULDBLOCK => break,
                // Stale ICMP unreachable report (should not happen with SIO_UDP_CONNRESET off).
                WSAECONNRESET => continue,
                e => return fail(count, e),
            }
        }
        p.len = received.min(cap);
        decode_sockaddr(&name.0, name_len as usize, p);
        count += 1;
    }
    count as i32
}

pub(crate) fn send_gso(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
    super::send_segmented(fd, data, seg_size, dst)
}

pub(crate) fn busy_poll(_fd: i64, _on: bool) -> i32 {
    0
}
