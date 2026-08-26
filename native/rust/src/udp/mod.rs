//! Batched UDP datapath behind the C ABI in `lib.rs`.
//!
//! Addresses cross the boundary inside [`PacketDesc`] as `(addr, port, family)`; the OS
//! `sockaddr` encoding is produced by safe byte-level code in this module and handed to the
//! kernel as an opaque buffer, so the only `unsafe` in the platform modules is the syscalls
//! themselves plus the two buffer-view helpers that form the FFI boundary.
//!
//! Error convention: every entry point returns `>= 0` on success and `-code` on failure, where
//! `code` is `errno` on Unix and the `WSAGetLastError()` value on Windows.
//!
//! Receive policy ([`recv_adaptive`]): a non-blocking batch receive always ends with one syscall
//! that answers "nothing more" (`EAGAIN`), and waiting for the first datagram costs a poll on top.
//! That is the right trade when a backlog has built up (N datagrams per N+1 syscalls) and a
//! waste when datagrams trickle in one at a time (three syscalls per datagram against the one
//! blocking `recvfrom` a classic receive loop pays). Each socket therefore remembers which regime
//! it is in. *Busy* sockets are non-blocking and drain eagerly. *Idle* sockets are switched to
//! blocking mode with `SO_RCVTIMEO` as the timeout and take one datagram per call — one syscall
//! per datagram, no poll, no probe — and every [`IDLE_PROBE_EVERY`]-th idle call drains
//! non-blocking instead, so that a building backlog is noticed (two or more queued) and the
//! socket goes busy; a busy drain that finds nothing goes idle again. Mode switches happen only
//! at regime changes, so their `FIONBIO` / `SO_RCVTIMEO` syscalls amortise to nothing.

use std::collections::HashMap;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicI32, AtomicU32, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

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
        p.port = u16::from_be_bytes([bytes[2], bytes[3]]);
        // A dual-stack socket reports an IPv4 peer as `::ffff:a.b.c.d`; the caller gets the IPv4 address it
        // would have seen on an AF_INET socket (what the JDK datapath does), and [`v4_mapped`] re-applies the
        // encoding for the reply.
        if bytes[8..18] == [0u8; 10] && bytes[18..20] == [0xff, 0xff] {
            p.family = FAMILY_IPV4;
            p.addr[..4].copy_from_slice(&bytes[20..24]);
        } else {
            p.family = FAMILY_IPV6;
            p.addr.copy_from_slice(&bytes[8..24]);
        }
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
    // `IPV6_V6ONLY` has to be cleared between `socket()` and `bind()`, which `UdpSocket::bind` does in one
    // step - hence the manual sequence for the one address where the option matters, matching the JVM
    // datapath's `AddressFamily.defaultBind()`. Any other IPv6 bind is a specific interface, where v4-mapped
    // traffic cannot arrive anyway.
    let sock = if ip == IpAddr::V6(Ipv6Addr::UNSPECIFIED) {
        imp::bind_dual_stack_wildcard(port)?
    } else {
        UdpSocket::bind(SocketAddr::new(ip, port)).map_err(os_error)?
    };
    sock.set_nonblocking(true).map_err(os_error)?;
    Ok(sock)
}

pub(crate) fn os_error(e: std::io::Error) -> i32 {
    e.raw_os_error().unwrap_or(imp::EIO_CODE)
}

/// Per-socket state kept on this side of the FFI boundary, keyed by descriptor / handle.
pub(crate) struct SockState {
    /// Held across a segmented send on Windows: `UDP_SEND_MSG_SIZE` is socket state, so another
    /// thread's plain send must not slip in between "set", "send" and "reset" (it would be
    /// segmented too). Unused on Unix, where GSO is a per-call control message.
    #[cfg_attr(not(windows), allow(dead_code))]
    pub send: Mutex<()>,
    /// Receive regime, see [`recv_adaptive`].
    pub busy: AtomicBool,
    /// Whether the descriptor is currently in non-blocking mode (it is after `open`).
    pub nonblocking: AtomicBool,
    /// The `SO_RCVTIMEO` currently set, in ms (`0` = none, the default after `open`).
    pub recv_timeout_ms: AtomicI32,
    /// Idle-regime calls so far (drives the periodic backlog probe).
    pub idle_calls: AtomicU32,
    /// Whether the socket is AF_INET6 (see [`bind_std`]): its `sendto` refuses a `sockaddr_in`, so an IPv4
    /// destination has to go out v4-mapped. False for a socket registered lazily rather than through [`open`].
    pub v6: AtomicBool,
}

fn registry() -> &'static Mutex<HashMap<i64, Arc<SockState>>> {
    static REGISTRY: OnceLock<Mutex<HashMap<i64, Arc<SockState>>>> = OnceLock::new();
    REGISTRY.get_or_init(|| Mutex::new(HashMap::new()))
}

/// The state of `fd`, created on first use (sockets opened through [`open`] register at open).
pub(crate) fn state(fd: i64) -> Arc<SockState> {
    let mut map = registry().lock().unwrap_or_else(|e| e.into_inner());
    map.entry(fd)
        .or_insert_with(|| {
            Arc::new(SockState {
                send: Mutex::new(()),
                busy: AtomicBool::new(false),
                nonblocking: AtomicBool::new(true),
                recv_timeout_ms: AtomicI32::new(0),
                idle_calls: AtomicU32::new(IDLE_PROBE_EVERY - 1), // the very first call probes: a cold burst drains in one go
                v6: AtomicBool::new(false),
            })
        })
        .clone()
}

/// Drops the state of `fd` (the descriptor number may be reused by the OS).
pub(crate) fn forget(fd: i64) {
    registry().lock().unwrap_or_else(|e| e.into_inner()).remove(&fd);
}

/// In the idle regime, every this-many-th call drains non-blocking instead of receiving one
/// datagram blocking, so a backlog is detected within that many datagrams.
pub(crate) const IDLE_PROBE_EVERY: u32 = 32;

/// What [`recv_adaptive`] needs from a platform socket. Every method returns `>= 0` on success
/// and `-code` on failure.
pub(crate) trait RecvBackend {
    /// Non-blocking receive of everything queued, up to `pkts.len()` (`0` when nothing is queued).
    fn drain(&self, pkts: &mut [PacketDesc]) -> i32;
    /// Blocking receive of one datagram under the socket's `SO_RCVTIMEO`: `1`, `0` on timeout.
    fn recv_one(&self, pkt: &mut PacketDesc) -> i32;
    fn set_nonblocking(&self, on: bool) -> i32;
    /// `SO_RCVTIMEO` in ms; `<= 0` means no timeout (block until a datagram arrives).
    fn set_recv_timeout(&self, ms: i32) -> i32;
}

fn ensure_nonblocking<B: RecvBackend>(st: &SockState, b: &B, on: bool) -> i32 {
    if st.nonblocking.load(Ordering::Relaxed) == on {
        return 0;
    }
    let r = b.set_nonblocking(on);
    if r >= 0 {
        st.nonblocking.store(on, Ordering::Relaxed);
    }
    r
}

fn ensure_recv_timeout<B: RecvBackend>(st: &SockState, b: &B, ms: i32) -> i32 {
    let ms = ms.max(0);
    if st.recv_timeout_ms.load(Ordering::Relaxed) == ms {
        return 0;
    }
    let r = b.set_recv_timeout(ms);
    if r >= 0 {
        st.recv_timeout_ms.store(ms, Ordering::Relaxed);
    }
    r
}

/// The receive policy described in the module docs. `timeout_ms > 0` waits that long for the
/// first datagram, `0` never waits, `< 0` waits indefinitely. Returns the count, or `-code`.
pub(crate) fn recv_adaptive<B: RecvBackend>(fd: i64, pkts: &mut [PacketDesc], timeout_ms: i32, b: &B) -> i32 {
    let st = state(fd);
    if st.busy.load(Ordering::Relaxed) {
        let e = ensure_nonblocking(&st, b, true);
        if e < 0 {
            return e;
        }
        let r = b.drain(pkts);
        if r != 0 {
            return r; // a backlog is still there (or an error): stay busy
        }
        st.busy.store(false, Ordering::Relaxed);
    }
    let probe = timeout_ms == 0 || st.idle_calls.fetch_add(1, Ordering::Relaxed) % IDLE_PROBE_EVERY == IDLE_PROBE_EVERY - 1;
    if probe {
        let e = ensure_nonblocking(&st, b, true);
        if e < 0 {
            return e;
        }
        let r = b.drain(pkts);
        if r >= 2 {
            st.busy.store(true, Ordering::Relaxed); // a backlog is building: drain eagerly from now on
        }
        if r != 0 || timeout_ms == 0 {
            return r;
        }
    }
    // idle: one blocking receive per datagram, no poll and no "nothing more" probe
    let e = ensure_nonblocking(&st, b, false);
    if e < 0 {
        return e;
    }
    let e = ensure_recv_timeout(&st, b, timeout_ms);
    if e < 0 {
        return e;
    }
    b.recv_one(&mut pkts[0])
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
    let fd = imp::open(bind_addr, port);
    if fd >= 0 {
        let v6 = matches!(bind_addr.trim().parse::<IpAddr>(), Ok(IpAddr::V6(_)));
        state(fd).v6.store(v6, Ordering::Relaxed);
    }
    fd
}

/// Closes a socket returned by [`open`].
pub fn close(fd: i64) -> i32 {
    let r = imp::close(fd);
    forget(fd);
    r
}

/// The locally bound port (useful after binding port 0).
pub fn local_port(fd: i64) -> i32 {
    imp::local_port(fd)
}

/// Sends `pkts` in order; returns how many were handed to the kernel (stops early if the socket
/// would block), or `-code` if the first one failed.
pub fn send_batch(fd: i64, pkts: &[PacketDesc]) -> i32 {
    if !needs_mapping(fd) || !pkts.iter().any(|p| p.family == FAMILY_IPV4) {
        return imp::send_batch(fd, pkts);
    }
    let mapped: Vec<PacketDesc> = pkts.iter().map(v4_mapped).collect();
    imp::send_batch(fd, &mapped)
}

/// Whether destinations for `fd` have to be rewritten v4-mapped (an AF_INET6 socket, see [`v4_mapped`]).
fn needs_mapping(fd: i64) -> bool {
    state(fd).v6.load(Ordering::Relaxed)
}

/// `a.b.c.d` as `::ffff:a.b.c.d`, the only encoding an AF_INET6 socket accepts for an IPv4 peer;
/// [`decode_sockaddr`] undoes it, so the address a caller sees is always the narrowest family.
fn v4_mapped(p: &PacketDesc) -> PacketDesc {
    if p.family != FAMILY_IPV4 {
        return *p;
    }
    let mut addr = [0u8; 16];
    addr[10] = 0xff;
    addr[11] = 0xff;
    addr[12..16].copy_from_slice(&p.addr[..4]);
    PacketDesc { addr, family: FAMILY_IPV6, ..*p }
}

/// Receives up to `pkts.len()` datagrams. `timeout_ms > 0` waits that long for the first one,
/// `0` never waits, `< 0` waits indefinitely. Returns the count received, or `-code`.
pub fn recv_batch(fd: i64, pkts: &mut [PacketDesc], timeout_ms: i32) -> i32 {
    if pkts.is_empty() {
        return 0;
    }
    imp::recv_batch(fd, pkts, timeout_ms)
}

/// Largest payload of one GSO / USO super-datagram: the 16-bit IP total length minus the IPv6
/// (40) and UDP (8) headers. Linux also caps the segment count at `UDP_MAX_SEGMENTS` (64 on
/// older kernels, 128 on recent ones); beyond either the kernel answers EINVAL or EMSGSIZE.
pub const GSO_MAX_BYTES: usize = 65_535 - 48;
/// Segments per super-datagram accepted by every kernel we run on (see [`GSO_MAX_BYTES`]).
pub const GSO_MAX_SEGMENTS: usize = 64;

/// Sends `data` as consecutive `seg_size`-byte datagrams to `dst`: kernel GSO (`UDP_SEGMENT`)
/// on Linux, USO (`UDP_SEND_MSG_SIZE`) on Windows, a sendto loop elsewhere. Input larger than
/// one super-datagram ([`GSO_MAX_BYTES`] / [`GSO_MAX_SEGMENTS`]) is split into several; whatever
/// the kernel refuses (any error but would-block) is segmented in user space, so the datagrams
/// are never silently dropped. Returns payload bytes handed to the kernel (short only when the
/// socket would block), or `-code`.
pub fn send_gso(fd: i64, data: &[u8], seg_size: u16, dst: &PacketDesc) -> i32 {
    if seg_size == 0 || data.is_empty() {
        return -imp::EINVAL_CODE;
    }
    let mapped;
    let dst = if needs_mapping(fd) && dst.family == FAMILY_IPV4 {
        mapped = v4_mapped(dst);
        &mapped
    } else {
        dst
    };
    let seg = seg_size as usize;
    let chunk = (GSO_MAX_BYTES / seg).min(GSO_MAX_SEGMENTS).max(1) * seg;
    if data.len() <= chunk {
        return imp::send_gso(fd, data, seg_size, dst);
    }
    let mut total = 0i32;
    for c in data.chunks(chunk) {
        let r = imp::send_gso(fd, c, seg_size, dst);
        if r < 0 {
            return if total == 0 { r } else { total };
        }
        total += r;
        if (r as usize) < c.len() {
            break; // would block: the caller retries the rest
        }
    }
    total
}

/// Sets `SO_BUSY_POLL` (Linux only; elsewhere a successful no-op).
pub fn busy_poll(fd: i64, on: bool) -> i32 {
    imp::busy_poll(fd, on)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The `::` wildcard must be dual-stack on every platform: a datagram sent to 127.0.0.1 from it arrives,
    /// and the receiver sees it. Without `IPV6_V6ONLY` cleared this send fails outright on Windows.
    #[test]
    fn ipv6_wildcard_reaches_ipv4_loopback() {
        let v6 = open("::", 0);
        let v4 = open("127.0.0.1", 0);
        assert!(v6 >= 0 && v4 >= 0, "open: v6={v6} v4={v4}");
        let port = local_port(v4);
        assert!(port > 0, "local_port: {port}");
        let mut dst = PacketDesc::empty();
        dst.set_socket_addr(format!("127.0.0.1:{port}").parse().unwrap());
        let payload = [0x2Au8; 8];
        let p = PacketDesc { buf: payload.as_ptr() as *mut u8, len: 8, cap: 8, ..dst };
        assert_eq!(send_batch(v6, std::slice::from_ref(&p)), 1, "send from :: to 127.0.0.1");
        let mut slot = [0u8; 64];
        let mut d = [PacketDesc { buf: slot.as_mut_ptr(), len: 0, cap: 64, ..PacketDesc::empty() }];
        assert_eq!(recv_batch(v4, &mut d, 1000), 1, "datagram from the dual-stack wildcard never arrived");
        assert_eq!(d[0].len, 8);
        assert_eq!(&slot[..8], &payload);
        assert_eq!(d[0].family, FAMILY_IPV4, "the wildcard socket must appear as an IPv4 peer");
        // ...and the echo back: the v4-mapped source the wildcard socket reports is narrowed to IPv4, so a
        // reply addressed to it goes out re-mapped and arrives.
        let back = PacketDesc { buf: payload.as_ptr() as *mut u8, len: 8, cap: 8, ..d[0] };
        assert_eq!(send_batch(v4, std::slice::from_ref(&back)), 1);
        let mut d6 = [PacketDesc { buf: slot.as_mut_ptr(), len: 0, cap: 64, ..PacketDesc::empty() }];
        assert_eq!(recv_batch(v6, &mut d6, 1000), 1, "reply to the dual-stack wildcard never arrived");
        assert_eq!(d6[0].family, FAMILY_IPV4, "an IPv4 sender must not surface as ::ffff:a.b.c.d");
        assert_eq!(d6[0].addr[..4], [127, 0, 0, 1]);
        close(v6);
        close(v4);
    }

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

    /// Drives `recv_adaptive` with a scripted backend: one blocking receive per idle call, eager
    /// drains while busy, the periodic probe, mode switches only at regime changes, per-fd state.
    #[test]
    fn adaptive_receive_policy() {
        use std::cell::Cell;
        struct Fake {
            queued: Cell<usize>, // datagrams "in the kernel"
            drains: Cell<usize>,
            ones: Cell<usize>,
            mode_switches: Cell<usize>,
            timeouts_set: Cell<usize>,
            nonblocking: Cell<bool>,
        }
        impl RecvBackend for Fake {
            fn drain(&self, pkts: &mut [PacketDesc]) -> i32 {
                assert!(self.nonblocking.get(), "drain only on a non-blocking socket");
                self.drains.set(self.drains.get() + 1);
                let n = self.queued.get().min(pkts.len());
                self.queued.set(self.queued.get() - n);
                n as i32
            }
            fn recv_one(&self, _pkt: &mut PacketDesc) -> i32 {
                assert!(!self.nonblocking.get(), "recv_one only on a blocking socket");
                self.ones.set(self.ones.get() + 1);
                if self.queued.get() > 0 {
                    self.queued.set(self.queued.get() - 1);
                    1
                } else {
                    0
                }
            }
            fn set_nonblocking(&self, on: bool) -> i32 {
                self.mode_switches.set(self.mode_switches.get() + 1);
                self.nonblocking.set(on);
                0
            }
            fn set_recv_timeout(&self, _ms: i32) -> i32 {
                self.timeouts_set.set(self.timeouts_set.get() + 1);
                0
            }
        }
        let fd = -4242i64; // never a real socket
        forget(fd);
        let b = Fake {
            queued: Cell::new(0),
            drains: Cell::new(0),
            ones: Cell::new(0),
            mode_switches: Cell::new(0),
            timeouts_set: Cell::new(0),
            nonblocking: Cell::new(true),
        };
        let run = |avail: usize, timeout: i32| -> (i32, usize, usize) {
            b.queued.set(avail);
            b.drains.set(0);
            b.ones.set(0);
            let mut pkts = vec![PacketDesc::empty(); 8];
            (recv_adaptive(fd, &mut pkts, timeout, &b), b.drains.get(), b.ones.get())
        };
        // first call on a fresh socket: the probe drains (nothing), then it goes blocking (one switch, one
        // timeout set) and one blocking receive times out
        assert_eq!(run(0, 10), (0, 1, 1));
        assert_eq!((b.mode_switches.get(), b.timeouts_set.get()), (1, 1));
        // idle, one datagram at a time: exactly one syscall each, no more switches
        for _ in 0..20 {
            assert_eq!(run(1, 10), (1, 0, 1));
        }
        assert_eq!((b.mode_switches.get(), b.timeouts_set.get()), (1, 1));
        assert!(!state(fd).busy.load(Ordering::Relaxed));
        // a backlog builds: the periodic probe (the IDLE_PROBE_EVERY-th idle call) drains it and flips to busy
        let mut calls = 0;
        loop {
            calls += 1;
            let (r, drains, _) = run(5, 10);
            if drains == 1 {
                assert_eq!(r, 5);
                break;
            }
            assert!(calls <= IDLE_PROBE_EVERY as usize, "probe must come within IDLE_PROBE_EVERY calls");
        }
        assert!(state(fd).busy.load(Ordering::Relaxed));
        // busy: drain first, nothing blocking
        assert_eq!(run(3, 10), (3, 1, 0));
        // busy but empty: the failed drain flips to idle and a blocking receive follows
        assert_eq!(run(0, 10), (0, 1, 1));
        assert!(!state(fd).busy.load(Ordering::Relaxed));
        // timeout 0 never blocks: non-blocking drain only; >= 2 datagrams make it busy
        assert_eq!(run(2, 0), (2, 1, 0));
        assert!(state(fd).busy.load(Ordering::Relaxed));
        assert_eq!(run(0, 0), (0, 2, 0)); // busy drain (empty) + the timeout-0 drain
        // another descriptor has its own state
        assert!(!state(fd - 1).busy.load(Ordering::Relaxed));
        forget(fd);
        forget(fd - 1);
    }
}
