//! Batched UDP datapath behind the C ABI in `lib.rs`.
//!
//! Addresses cross the boundary inside [`PacketDesc`] as `(addr, port, family)`; the OS
//! `sockaddr` encoding is produced by safe byte-level code in this module and handed to the
//! kernel as an opaque buffer, so the only `unsafe` in the platform modules is the syscalls
//! themselves plus the two buffer-view helpers that form the FFI boundary.
//!
//! Error convention: every entry point returns `>= 0` on success and `-code` on failure, where
//! `code` is `errno` on Unix and the `WSAGetLastError()` value on Windows.

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};

#[cfg(unix)]
mod unix;
#[cfg(unix)]
use unix as imp;
#[cfg(windows)]
mod windows;
#[cfg(windows)]
use windows as imp;

/// No address: unfilled receive slot or decode failure.
pub const FAMILY_NONE: u8 = 0;
/// IPv4; `addr[0..4]` is the address.
pub const FAMILY_IPV4: u8 = 4;
/// IPv6; all 16 bytes of `addr` are the address.
pub const FAMILY_IPV6: u8 = 6;

/// Socket buffer size requested at open (the kernel may clamp it, e.g. `net.core.rmem_max`).
pub const SOCKET_BUFFER_BYTES: i32 = 4 << 20;

/// Upper bound on an OS `sockaddr`; `sockaddr_storage` is 128 bytes everywhere we care about.
pub const SOCKADDR_MAX: usize = 128;

/// One datagram slot shared with the JVM (`#[repr(C)]`, 40 bytes on 64-bit targets).
///
/// * send: `buf[..len]` is the payload, `(addr, port, family)` is the destination.
/// * receive: `buf[..cap]` is writable; on return `len` is the datagram size (clamped to `cap`)
///   and `(addr, port, family)` is the sender.
#[repr(C)]
#[derive(Clone, Copy, Debug)]
pub struct PacketDesc {
    /// Payload buffer (owned by the caller, typically an off-heap `Arena` slice).
    pub buf: *mut u8,
    /// Bytes in use.
    pub len: u32,
    /// Bytes available in `buf`.
    pub cap: u32,
    /// IPv4 in the first 4 bytes or a full IPv6 address.
    pub addr: [u8; 16],
    /// Host byte order.
    pub port: u16,
    /// One of `FAMILY_NONE` / `FAMILY_IPV4` / `FAMILY_IPV6`.
    pub family: u8,
}

impl PacketDesc {
    /// A descriptor with no buffer and no address.
    pub const fn empty() -> Self {
        PacketDesc { buf: std::ptr::null_mut(), len: 0, cap: 0, addr: [0; 16], port: 0, family: FAMILY_NONE }
    }

    /// The address carried by this descriptor, if any.
    pub fn socket_addr(&self) -> Option<SocketAddr> {
        let a = self.addr;
        match self.family {
            FAMILY_IPV4 => Some(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(a[0], a[1], a[2], a[3])), self.port)),
            FAMILY_IPV6 => Some(SocketAddr::new(IpAddr::V6(Ipv6Addr::from(a)), self.port)),
            _ => None,
        }
    }

    /// Stores `sa` into `(addr, port, family)`.
    pub fn set_socket_addr(&mut self, sa: SocketAddr) {
        self.addr = [0; 16];
        self.port = sa.port();
        match sa.ip() {
            IpAddr::V4(v4) => {
                self.addr[..4].copy_from_slice(&v4.octets());
                self.family = FAMILY_IPV4;
            }
            IpAddr::V6(v6) => {
                self.addr = v6.octets();
                self.family = FAMILY_IPV6;
            }
        }
    }
}

/// FFI boundary: the payload of a descriptor that is about to be sent.
///
/// # Safety
/// `p.buf` must be valid for `p.len` readable bytes for as long as the returned slice lives.
pub(crate) unsafe fn send_view(p: &PacketDesc) -> Option<&[u8]> {
    if p.buf.is_null() || p.len == 0 || p.len > p.cap {
        return None;
    }
    // SAFETY: guaranteed by the caller (see above).
    Some(unsafe { std::slice::from_raw_parts(p.buf as *const u8, p.len as usize) })
}

/// FFI boundary: the writable buffer of a descriptor that is about to receive.
///
/// # Safety
/// `p.buf` must be valid for `p.cap` writable bytes and not otherwise aliased for as long as the
/// returned slice lives.
pub(crate) unsafe fn recv_view(p: &PacketDesc) -> Option<&mut [u8]> {
    if p.buf.is_null() || p.cap == 0 {
        return None;
    }
    // SAFETY: guaranteed by the caller (see above).
    Some(unsafe { std::slice::from_raw_parts_mut(p.buf, p.cap as usize) })
}

/// Storage for an OS `sockaddr`, aligned like `sockaddr_storage`.
#[repr(C, align(8))]
#[derive(Clone, Copy)]
pub(crate) struct SockAddrBuf(pub [u8; SOCKADDR_MAX]);

impl SockAddrBuf {
    pub(crate) const fn zeroed() -> Self {
        SockAddrBuf([0; SOCKADDR_MAX])
    }
}

#[cfg(windows)]
const AF_INET_OS: u16 = windows_sys::Win32::Networking::WinSock::AF_INET;
#[cfg(windows)]
const AF_INET6_OS: u16 = windows_sys::Win32::Networking::WinSock::AF_INET6;
#[cfg(unix)]
const AF_INET_OS: u16 = libc::AF_INET as u16;
#[cfg(unix)]
const AF_INET6_OS: u16 = libc::AF_INET6 as u16;

/// BSD-derived systems put a one-byte `sa_len` in front of a one-byte family.
const SA_LEN_PREFIX: bool = cfg!(any(
    target_os = "macos",
    target_os = "ios",
    target_os = "tvos",
    target_os = "watchos",
    target_os = "visionos",
    target_os = "freebsd",
    target_os = "netbsd",
    target_os = "openbsd",
    target_os = "dragonfly",
));

const SOCKADDR_IN_LEN: usize = 16;
const SOCKADDR_IN6_LEN: usize = 28;

/// Encodes the destination of `p` as an OS `sockaddr_in` / `sockaddr_in6`; returns its length.
pub(crate) fn encode_sockaddr(p: &PacketDesc, out: &mut SockAddrBuf) -> Option<usize> {
    let out = &mut out.0;
    out.fill(0);
    let (len, family) = match p.family {
        FAMILY_IPV4 => (SOCKADDR_IN_LEN, AF_INET_OS),
        FAMILY_IPV6 => (SOCKADDR_IN6_LEN, AF_INET6_OS),
        _ => return None,
    };
    if SA_LEN_PREFIX {
        out[0] = len as u8;
        out[1] = family as u8;
    } else {
        out[0..2].copy_from_slice(&family.to_ne_bytes());
    }
    out[2..4].copy_from_slice(&p.port.to_be_bytes());
    if p.family == FAMILY_IPV4 {
        out[4..8].copy_from_slice(&p.addr[..4]);
    } else {
        // bytes 4..8: flowinfo = 0; bytes 24..28: scope id = 0
        out[8..24].copy_from_slice(&p.addr);
    }
    Some(len)
}

/// Decodes an OS `sockaddr` of `len` bytes into `(addr, port, family)` of `p`.
pub(crate) fn decode_sockaddr(bytes: &[u8; SOCKADDR_MAX], len: usize, p: &mut PacketDesc) {
    let family = if SA_LEN_PREFIX { bytes[1] as u16 } else { u16::from_ne_bytes([bytes[0], bytes[1]]) };
    p.addr = [0; 16];
    if family == AF_INET_OS && len >= 8 {
        p.family = FAMILY_IPV4;
        p.port = u16::from_be_bytes([bytes[2], bytes[3]]);
        p.addr[..4].copy_from_slice(&bytes[4..8]);
    } else if family == AF_INET6_OS && len >= 24 {
        p.family = FAMILY_IPV6;
        p.port = u16::from_be_bytes([bytes[2], bytes[3]]);
        p.addr.copy_from_slice(&bytes[8..24]);
    } else {
        p.family = FAMILY_NONE;
        p.port = 0;
    }
}

/// Portable part of `open`: parse, bind (std handles `WSAStartup` and the address family), and
/// switch to non-blocking mode. Errors are already negated OS codes.
pub(crate) fn bind_std(bind_addr: &str, port: u16) -> Result<UdpSocket, i32> {
    let text = bind_addr.trim();
    let ip: IpAddr = if text.is_empty() {
        IpAddr::V4(Ipv4Addr::UNSPECIFIED)
    } else {
        text.parse().map_err(|_| imp::EINVAL_CODE)?
    };
    let sock = UdpSocket::bind(SocketAddr::new(ip, port)).map_err(os_error)?;
    sock.set_nonblocking(true).map_err(os_error)?;
    Ok(sock)
}

pub(crate) fn os_error(e: std::io::Error) -> i32 {
    e.raw_os_error().unwrap_or(imp::EIO_CODE)
}

/// Fallback for platforms without UDP GSO: one datagram per `seg_size` slice, still a single
/// FFI crossing. Returns the number of payload bytes handed to the kernel.
pub(crate) fn send_segmented(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
    if seg_size == 0 || data.is_empty() {
        return -imp::EINVAL_CODE;
    }
    let descs: Vec<PacketDesc> = data
        .chunks(seg_size as usize)
        .map(|c| PacketDesc {
            buf: c.as_ptr() as *mut u8, // only ever read through `send_view`
            len: c.len() as u32,
            cap: c.len() as u32,
            addr: dst.addr,
            port: dst.port,
            family: dst.family,
        })
        .collect();
    let sent = imp::send_batch(fd, &descs);
    if sent < 0 {
        return sent;
    }
    descs[..sent as usize].iter().map(|d| d.len as i32).sum()
}

/// Opens a non-blocking UDP socket bound to `bind_addr:port` (4 MiB buffers requested).
pub fn open(bind_addr: &str, port: u16) -> i64 {
    imp::open(bind_addr, port)
}

/// Closes a socket returned by [`open`].
pub fn close(fd: i64) -> i32 {
    imp::close(fd)
}

/// The locally bound port (useful after binding port 0).
pub fn local_port(fd: i64) -> i32 {
    imp::local_port(fd)
}

/// Sends `pkts` in order; returns how many were handed to the kernel (stops early if the socket
/// would block), or `-code` if the first one failed.
pub fn send_batch(fd: i64, pkts: &[PacketDesc]) -> i32 {
    imp::send_batch(fd, pkts)
}

/// Receives up to `pkts.len()` datagrams. `timeout_ms > 0` waits that long for the first one,
/// `0` never waits, `< 0` waits indefinitely. Returns the count received, or `-code`.
pub fn recv_batch(fd: i64, pkts: &mut [PacketDesc], timeout_ms: i32) -> i32 {
    imp::recv_batch(fd, pkts, timeout_ms)
}

/// Sends `data` as consecutive `seg_size`-byte datagrams to `dst`: kernel GSO (`UDP_SEGMENT`)
/// on Linux, a sendto loop elsewhere. Returns payload bytes sent, or `-code`.
pub fn send_gso(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
    imp::send_gso(fd, data, seg_size, dst)
}

/// Sets `SO_BUSY_POLL` (Linux only; elsewhere a successful no-op).
pub fn busy_poll(fd: i64, on: bool) -> i32 {
    imp::busy_poll(fd, on)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn packet_desc_layout_is_stable() {
        assert_eq!(std::mem::size_of::<PacketDesc>(), 40);
        assert_eq!(std::mem::align_of::<PacketDesc>(), 8);
        assert_eq!(std::mem::offset_of!(PacketDesc, len), 8);
        assert_eq!(std::mem::offset_of!(PacketDesc, cap), 12);
        assert_eq!(std::mem::offset_of!(PacketDesc, addr), 16);
        assert_eq!(std::mem::offset_of!(PacketDesc, port), 32);
        assert_eq!(std::mem::offset_of!(PacketDesc, family), 34);
    }

    #[test]
    fn sockaddr_round_trips() {
        for sa in ["192.0.2.7:5353", "[2001:db8::1]:65535", "127.0.0.1:1", "[::1]:4242"] {
            let sa: SocketAddr = sa.parse().unwrap();
            let mut p = PacketDesc::empty();
            p.set_socket_addr(sa);
            let mut buf = SockAddrBuf::zeroed();
            let len = encode_sockaddr(&p, &mut buf).unwrap();
            assert_eq!(len, if sa.is_ipv4() { SOCKADDR_IN_LEN } else { SOCKADDR_IN6_LEN });
            let mut q = PacketDesc::empty();
            decode_sockaddr(&buf.0, len, &mut q);
            assert_eq!(q.socket_addr(), Some(sa));
        }
        let mut none = PacketDesc::empty();
        assert!(encode_sockaddr(&none, &mut SockAddrBuf::zeroed()).is_none());
        decode_sockaddr(&SockAddrBuf::zeroed().0, 0, &mut none);
        assert_eq!(none.family, FAMILY_NONE);
    }
}
