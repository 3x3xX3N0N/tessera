//! libc implementation.
//!
//! * Linux / Android: `sendmmsg` / `recvmmsg`, `UDP_SEGMENT` (GSO) and `SO_BUSY_POLL`.
//! * Other Unix (macOS, BSDs): `sendto` / `recvfrom` loops — still one FFI crossing per batch.
//!
//! Validated on Linux (WSL2, kernel 6.18: `cargo test` incl. the GSO round trip); the non-Linux
//! Unix path is compile-checked only.

use super::{decode_sockaddr, encode_sockaddr, recv_view, send_view, PacketDesc, RecvBackend, SockAddrBuf, SOCKADDR_MAX, SOCKET_BUFFER_BYTES};
use libc::{c_int, c_void, socklen_t};
use std::os::fd::{AsRawFd, IntoRawFd};

pub(crate) const EINVAL_CODE: i32 = libc::EINVAL;
pub(crate) const EIO_CODE: i32 = libc::EIO;

fn errno() -> i32 {
    std::io::Error::last_os_error().raw_os_error().unwrap_or(libc::EIO)
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
    let fd = sock.as_raw_fd();
    for opt in [libc::SO_RCVBUF, libc::SO_SNDBUF] {
        let v: c_int = SOCKET_BUFFER_BYTES;
        // SAFETY: FFI; `v` outlives the call. Failure (kernel clamps) is deliberately ignored.
        unsafe {
            libc::setsockopt(
                fd,
                libc::SOL_SOCKET,
                opt,
                &v as *const c_int as *const c_void,
                std::mem::size_of::<c_int>() as socklen_t,
            )
        };
    }
    sock.into_raw_fd() as i64
}

pub(crate) fn close(fd: i64) -> i32 {
    // SAFETY: FFI; the descriptor came from `open`.
    if unsafe { libc::close(fd as c_int) } < 0 {
        -errno()
    } else {
        0
    }
}

pub(crate) fn local_port(fd: i64) -> i32 {
    let mut name = SockAddrBuf::zeroed();
    let mut len = SOCKADDR_MAX as socklen_t;
    // SAFETY: FFI; `name` provides `SOCKADDR_MAX` writable bytes and `len` says so.
    if unsafe { libc::getsockname(fd as c_int, name.0.as_mut_ptr().cast(), &mut len) } < 0 {
        return -errno();
    }
    let mut d = PacketDesc::empty();
    decode_sockaddr(&name.0, len as usize, &mut d);
    if d.family == super::FAMILY_NONE {
        -libc::EAFNOSUPPORT
    } else {
        d.port as i32
    }
}

/// One `recvfrom` into `p` with `flags`: `1` received, `0` would block / timed out / interrupted,
/// `-code`.
fn recv_into(fd: c_int, p: &mut PacketDesc, flags: c_int) -> i32 {
    // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `cap` bytes.
    let Some(buf) = (unsafe { recv_view(p) }) else {
        return -libc::EINVAL;
    };
    let cap = buf.len();
    let mut name = SockAddrBuf::zeroed();
    let mut name_len = SOCKADDR_MAX as socklen_t;
    // SAFETY: FFI; `buf` and `name` outlive the call and `name_len` bounds `name`.
    let r = unsafe { libc::recvfrom(fd, buf.as_mut_ptr().cast(), cap, flags, name.0.as_mut_ptr().cast(), &mut name_len) };
    if r < 0 {
        let e = errno();
        return if e == libc::EAGAIN || e == libc::EWOULDBLOCK || e == libc::EINTR { 0 } else { -e };
    }
    p.len = (r as usize).min(cap) as u32;
    decode_sockaddr(&name.0, name_len as usize, p);
    1
}

struct Fd(c_int);

impl RecvBackend for Fd {
    fn drain(&self, pkts: &mut [PacketDesc]) -> i32 {
        drain(self.0, pkts)
    }

    fn recv_one(&self, pkt: &mut PacketDesc) -> i32 {
        recv_into(self.0, pkt, 0)
    }

    fn set_nonblocking(&self, on: bool) -> i32 {
        // SAFETY: FFI; plain fcntl on our own descriptor.
        let flags = unsafe { libc::fcntl(self.0, libc::F_GETFL) };
        if flags < 0 {
            return -errno();
        }
        let wanted = if on { flags | libc::O_NONBLOCK } else { flags & !libc::O_NONBLOCK };
        // SAFETY: FFI; plain fcntl on our own descriptor.
        if wanted != flags && unsafe { libc::fcntl(self.0, libc::F_SETFL, wanted) } < 0 {
            return -errno();
        }
        0
    }

    fn set_recv_timeout(&self, ms: i32) -> i32 {
        let ms = ms.max(0) as libc::time_t;
        let tv = libc::timeval { tv_sec: ms / 1000, tv_usec: ((ms % 1000) * 1000) as libc::suseconds_t };
        // SAFETY: FFI; `tv` outlives the call.
        let r = unsafe {
            libc::setsockopt(
                self.0,
                libc::SOL_SOCKET,
                libc::SO_RCVTIMEO,
                &tv as *const libc::timeval as *const c_void,
                std::mem::size_of::<libc::timeval>() as socklen_t,
            )
        };
        if r < 0 {
            -errno()
        } else {
            0
        }
    }
}

pub(crate) fn recv_batch(fd: i64, pkts: &mut [PacketDesc], timeout_ms: i32) -> i32 {
    super::recv_adaptive(fd, pkts, timeout_ms, &Fd(fd as c_int))
}

pub(crate) fn busy_poll(fd: i64, on: bool) -> i32 {
    #[cfg(any(target_os = "linux", target_os = "android"))]
    {
        // Microseconds the kernel may spin in recv/poll before sleeping. Needs CAP_NET_ADMIN
        // to raise above net.core.busy_read; the caller gets -EPERM otherwise.
        let v: c_int = if on { 50 } else { 0 };
        // SAFETY: FFI; `v` outlives the call.
        let r = unsafe {
            libc::setsockopt(
                fd as c_int,
                libc::SOL_SOCKET,
                SO_BUSY_POLL,
                &v as *const c_int as *const c_void,
                std::mem::size_of::<c_int>() as socklen_t,
            )
        };
        if r < 0 {
            -errno()
        } else {
            0
        }
    }
    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    {
        let _ = (fd, on);
        0
    }
}

#[cfg(target_os = "linux")]
const SO_BUSY_POLL: c_int = libc::SO_BUSY_POLL;
#[cfg(target_os = "android")]
const SO_BUSY_POLL: c_int = 46;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) use mmsg::{drain, send_batch, send_gso};
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub(crate) use simple::{drain, send_batch, send_gso};

#[cfg(any(target_os = "linux", target_os = "android"))]
mod mmsg {
    use super::*;

    /// Descriptors per syscall; bounds the stack arrays (~13 KiB) used below.
    const MAX_MMSG: usize = 64;
    const UDP_SEGMENT: c_int = 103;

    const EMPTY_IOV: libc::iovec = libc::iovec { iov_base: std::ptr::null_mut(), iov_len: 0 };

    pub(crate) fn send_batch(fd: i64, pkts: &[PacketDesc]) -> i32 {
        let fd = fd as c_int;
        let mut total = 0usize;
        for chunk in pkts.chunks(MAX_MMSG) {
            let n = chunk.len();
            let mut iov = [EMPTY_IOV; MAX_MMSG];
            let mut names = [SockAddrBuf::zeroed(); MAX_MMSG];
            // SAFETY: mmsghdr/msghdr are plain C structs for which all-zero is a valid value.
            let mut msgs: [libc::mmsghdr; MAX_MMSG] = unsafe { std::mem::zeroed() };
            for i in 0..n {
                let p = &chunk[i];
                // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `len` bytes.
                let Some(data) = (unsafe { send_view(p) }) else {
                    return fail(total, libc::EINVAL);
                };
                let Some(name_len) = encode_sockaddr(p, &mut names[i]) else {
                    return fail(total, libc::EINVAL);
                };
                iov[i] = libc::iovec { iov_base: data.as_ptr() as *mut c_void, iov_len: data.len() };
                let h = &mut msgs[i].msg_hdr;
                h.msg_iov = &mut iov[i];
                h.msg_iovlen = 1;
                h.msg_name = names[i].0.as_mut_ptr().cast();
                h.msg_namelen = name_len as socklen_t;
            }
            let mut sent = 0usize;
            while sent < n {
                // SAFETY: FFI; every header points into locals that outlive the call.
                let r = unsafe { libc::sendmmsg(fd, msgs[sent..].as_mut_ptr(), (n - sent) as libc::c_uint, 0) };
                if r < 0 {
                    let e = errno();
                    if e == libc::EINTR {
                        continue;
                    }
                    if e == libc::EAGAIN || e == libc::EWOULDBLOCK {
                        return (total + sent) as i32;
                    }
                    return fail(total + sent, e);
                }
                sent += r as usize;
            }
            total += sent;
        }
        total as i32
    }

    /// Non-blocking drain: `recvmmsg(MSG_DONTWAIT)` per 64-slot chunk until the queue is empty or
    /// `pkts` is full.
    pub(crate) fn drain(fd: c_int, pkts: &mut [PacketDesc]) -> i32 {
        let mut total = 0usize;
        for chunk in pkts.chunks_mut(MAX_MMSG) {
            let n = chunk.len();
            let mut iov = [EMPTY_IOV; MAX_MMSG];
            let mut names = [SockAddrBuf::zeroed(); MAX_MMSG];
            // SAFETY: mmsghdr/msghdr are plain C structs for which all-zero is a valid value.
            let mut msgs: [libc::mmsghdr; MAX_MMSG] = unsafe { std::mem::zeroed() };
            for i in 0..n {
                // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `cap` bytes.
                let Some(buf) = (unsafe { recv_view(&chunk[i]) }) else {
                    return fail(total, libc::EINVAL);
                };
                iov[i] = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };
                let h = &mut msgs[i].msg_hdr;
                h.msg_iov = &mut iov[i];
                h.msg_iovlen = 1;
                h.msg_name = names[i].0.as_mut_ptr().cast();
                h.msg_namelen = SOCKADDR_MAX as socklen_t;
            }
            let got = loop {
                // SAFETY: FFI; every header points into locals / caller buffers that outlive the call.
                let r = unsafe { libc::recvmmsg(fd, msgs.as_mut_ptr(), n as libc::c_uint, libc::MSG_DONTWAIT, std::ptr::null_mut()) };
                if r < 0 {
                    let e = errno();
                    if e == libc::EINTR {
                        continue;
                    }
                    if e == libc::EAGAIN || e == libc::EWOULDBLOCK {
                        return total as i32;
                    }
                    return fail(total, e);
                }
                break r as usize;
            };
            for i in 0..got {
                chunk[i].len = msgs[i].msg_len.min(chunk[i].cap);
                decode_sockaddr(&names[i].0, msgs[i].msg_hdr.msg_namelen as usize, &mut chunk[i]);
            }
            total += got;
            if got < n {
                break;
            }
        }
        total as i32
    }

    pub(crate) fn send_gso(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
        if seg_size == 0 || data.is_empty() {
            return -libc::EINVAL;
        }
        if data.len() <= seg_size as usize {
            return super::super::send_segmented(fd, data, seg_size, dst);
        }
        let mut name = SockAddrBuf::zeroed();
        let Some(name_len) = encode_sockaddr(dst, &mut name) else {
            return -libc::EINVAL;
        };
        let mut iov = libc::iovec { iov_base: data.as_ptr() as *mut c_void, iov_len: data.len() };
        #[repr(C, align(8))]
        struct Control([u8; 32]);
        let mut control = Control([0u8; 32]);
        // SAFETY: pure arithmetic on a constant.
        let space = unsafe { libc::CMSG_SPACE(2) } as usize;
        debug_assert!(space <= 32);
        // SAFETY: msghdr is a plain C struct for which all-zero is a valid value.
        let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
        msg.msg_name = name.0.as_mut_ptr().cast();
        msg.msg_namelen = name_len as socklen_t;
        msg.msg_iov = &mut iov;
        msg.msg_iovlen = 1;
        msg.msg_control = control.0.as_mut_ptr().cast();
        msg.msg_controllen = space as _;
        // SAFETY: the control buffer is 8-aligned and at least CMSG_SPACE(2) bytes, so the
        // header and its two-byte payload are in bounds.
        unsafe {
            let c = libc::CMSG_FIRSTHDR(&msg);
            (*c).cmsg_level = libc::SOL_UDP;
            (*c).cmsg_type = UDP_SEGMENT;
            (*c).cmsg_len = libc::CMSG_LEN(2) as _;
            std::ptr::write_unaligned(libc::CMSG_DATA(c).cast::<u16>(), seg_size);
        }
        loop {
            // SAFETY: FFI; everything `msg` points to outlives the call.
            let r = unsafe { libc::sendmsg(fd as c_int, &msg, 0) };
            if r >= 0 {
                return r as i32;
            }
            let e = errno();
            match e {
                libc::EINTR => continue,
                // Would block (EWOULDBLOCK == EAGAIN on Linux): nothing sent; the caller retries (per datagram, with its own backoff).
                libc::EAGAIN => return 0,
                // Kernel without UDP GSO (pre-4.18), a device that rejects it (EIO / EINVAL / EOPNOTSUPP /
                // ENOPROTOOPT), a super-datagram the kernel finds too large (EMSGSIZE: the caller's run
                // exceeded UDP_MAX_SEGMENTS or the IP length on this kernel) or anything else: segment in
                // user space rather than drop the run. A failure there is reported by send_segmented itself.
                _ => return super::super::send_segmented(fd, data, seg_size, dst),
            }
        }
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
mod simple {
    use super::*;

    pub(crate) fn send_batch(fd: i64, pkts: &[PacketDesc]) -> i32 {
        let fd = fd as c_int;
        let mut count = 0usize;
        for p in pkts {
            // SAFETY: FFI boundary — the caller guarantees `buf` is valid for `len` bytes.
            let Some(data) = (unsafe { send_view(p) }) else {
                return fail(count, libc::EINVAL);
            };
            let mut name = SockAddrBuf::zeroed();
            let Some(name_len) = encode_sockaddr(p, &mut name) else {
                return fail(count, libc::EINVAL);
            };
            loop {
                // SAFETY: FFI; `data` and `name` outlive the call.
                let r = unsafe {
                    libc::sendto(fd, data.as_ptr().cast(), data.len(), 0, name.0.as_ptr().cast(), name_len as socklen_t)
                };
                if r >= 0 {
                    break;
                }
                let e = errno();
                if e == libc::EINTR {
                    continue;
                }
                if e == libc::EAGAIN || e == libc::EWOULDBLOCK {
                    return count as i32;
                }
                return fail(count, e);
            }
            count += 1;
        }
        count as i32
    }

    /// Non-blocking drain: `recvfrom(MSG_DONTWAIT)` until the socket would block or `pkts` is full.
    pub(crate) fn drain(fd: c_int, pkts: &mut [PacketDesc]) -> i32 {
        let mut count = 0usize;
        while count < pkts.len() {
            match super::recv_into(fd, &mut pkts[count], libc::MSG_DONTWAIT) {
                1 => count += 1,
                0 => break,
                e => return fail(count, -e),
            }
        }
        count as i32
    }

    pub(crate) fn send_gso(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
        super::super::send_segmented(fd, data, seg_size, dst)
    }
}
