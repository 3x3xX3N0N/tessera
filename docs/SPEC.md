# Tessera transport — spec v0 (draft)

Goal: fastest-in-class tail latency on lossy, variable last-mile paths (cellular, Wi-Fi, LEO sat), with
post-quantum 1-RTT setup and native multipath. Kotlin reference implementation. No Google lineage.

## Design targets (measured, CI-gated)
| Front | QUIC today | Tessera target |
|---|---|---|
| First byte (PQ era) | 1-RTT + cert chain may exceed initial budget | 1-RTT, first flight ≤ 1350 B, no X.509 in band |
| Single loss cost | ≥ 1 RTT (ARQ) | < ½ RTT via sliding-window RLNC |
| Queueing delay | CC probes build queues | receiver-driven credit; no standing queue |
| Path failover | MP-QUIC bolt-on, per-path stall | packets unordered across paths; repair striped cross-path |

## Packet
`flags(1) connId(8) pathId(1) pathPacketNumber(4)` then frames. Packet numbers are per path; message ids global.
Connection identity is `connId` (derived from handshake) — migration needs no signalling beyond path validation.

## Frames
- `0x01 Msg(msgId, offset, fin, data)` — messages, not streams. Ordering/streams are a library above transport.
- `0x02 Ack(path, largest, ranges, ecnCe, rxTimeUs)` — per-path, carries ECN and receiver timestamp (OWD estimation).
- `0x03 Grant(path, creditBytes, priority)` — receiver-driven CC.
- `0x04 Repair(windowBase, windowLen, seed, symbol)` — RLNC over GF(256); coefficients regenerated from seed.
- `0x05 PathChallenge`, `0x06 Ping`, `0x07 PathResponse`, `0x80+` extension/grease (length-prefixed, skippable).
- `0x08 Close(code, reason)` — connection close, v0.7 (was missing from this catalog until v0.8).
- `0x09 MaxData(limitBytes)` — connection flow control in app-payload bytes, v0.8. A reserved (`< 0x80`) type is a
  hard wire break for older peers, same precedent as `0x08`: v0 makes no cross-version promises.

Long-header flags: `0x80 F_INITIAL`, `0x40 F_HANDSHAKE` (server reply), `0x10 F_RESUME` (PSK initial),
`0x20 F_TOKEN` (see "Address validation"; `0x20` is `F_REPAIR` on short headers, which never carry `F_INITIAL`),
`0x0F` grease.

## Handshake
Noise-IK shape, hybrid X25519 + ML-KEM-768, HKDF-SHA256. Responder static keys are known a priori (pinned, TOFU,
or short-lived delegated credential signed out-of-band). 0-RTT via PSK with per-connection replay window (TODO).
Amplification: responder sends ≤ 3× bytes received until path validated.

## Address validation (v0.7)

`ZeroRtt.Server.accept` checked a timestamp window and a replay set — nanoseconds — and then ran an X25519
agreement **and an ML-KEM-768 decapsulation** before anything authenticated the sender. Measured here: ~0.5 ms of
one core per initial, i.e. **~2000 forced KEM operations per second per core for ~19 Mbit/s of garbage**. The
`--token` shared secret does not help — it lives inside the AEAD, behind the KEM. This is CPU exhaustion, which
the 3x amplification limit does not address at all.

The defence is adaptive, because an unconditional Retry would cost every client a round trip and destroy 0-RTT:

1. **Always on, cheap** (`core/AddressValidation.kt`, ~180 ns/initial, no allocation): a per-source token bucket
   (50 KEM/s, burst 100, for an un-validated address; a separate 200/s bucket for a validated one, so a busy NAT
   is not throttled by a neighbour) in a **fixed** table of 8192 slots × 32 B ≈ 260 KB. Slots are shared on hash
   collision rather than evicted, so an attacker cannot grow the table; the slot index is keyed with the server
   secret, so it cannot aim at a chosen victim's slot either. Plus a global ceiling of 200 un-authenticated KEM
   operations per second (~10 % of a core) and 5000 Retries per second.
2. **Under pressure only**: when un-authenticated initials exceed 200/s, or more than half of at least 32 of them
   fail to authenticate, an un-validated address gets a **Retry** instead of a KEM — header, `tokenLen(1)`, and a
   16-byte token that is `bucket(4) | truncated HMAC-SHA256(secret, ip|port|bucket)(12)`. The server keeps *no*
   per-attempt state. The client re-sends its initial byte-for-byte with `F_TOKEN` set and `tokenLen | token`
   prepended to the body; the server verifies with one HMAC (~3 µs) and only then does the KEM. Tokens are valid
   for their bucket and the previous one (15–30 s) and the secret derives from the ticket key, so they survive a
   restart. A spoofed source never receives the token, so it never reaches the KEM.
3. Retry therefore costs one round trip **only while under attack**, and only for a client with no valid token
   (measured: +254 µs p50 on loopback; 0 µs when not under pressure).

Wire cost: 17 bytes (`tokenLen | token`) reserved off the client's first-flight budget, so a retried initial still
fits `MAX_DATAGRAM` byte-for-byte — 184 → 167 B of fresh-PQ 0-RTT payload, ~1.29 → ~1.27 KB resumed. A Retry
packet is 31 B against a ≥1.2 KB initial, so it is not itself a reflector.

**Resumption tickets as prior validation.** A ticket is *not* proof of address ownership: it is a bearer token, and
a replayed one from a spoofed source looks identical. So it does not relax the anti-amplification limit, and
`PathValidation` still applies to a resumed connection exactly as before. It does relax the *CPU* gate, and only
because a resumed initial performs no KEM at all — one AEAD open of the ticket, a few microseconds — so there is
nothing expensive to protect. A resumed initial is therefore never answered with a Retry; it passes only the
per-source bucket, which bounds even that cheap work.

Open: the Retry is unauthenticated by construction, so an off-path attacker who can guess a ConnId can inject one.
The client accepts at most one Retry per connect, only from the address it sent the initial to, and its retransmit
train continues regardless, so the cost is one extra initial, not a failed connect.

## Loss recovery
Systematic sliding-window RLNC. Redundancy ratio per path = `lossRate + 2.3·σ` from a Kalman loss estimator,
capped at 0.5. Residual loss uses RACK-style time-based ARQ. Repair symbols are scheduled on a different path
than their sources when >1 path exists.

## Congestion control
Primary: receiver grants credit ≈ 1.1 × BDP at the receiver-observed delivery rate (Homa lineage).
Sender-side: L4S/ECN-CE gentle decrease; fixed initial window before the first grant. Loss-based fallback (TODO)
for fairness with CUBIC on shared bottlenecks.

## Multipath
Scheduler = earliest-completion-first using per-path `srtt/2 + bytes/bw` scaled by a loss penalty.

## Open items / v1
- AEAD (ChaCha20-Poly1305) wiring with handshake keys; header protection.
- Full Gaussian elimination in the decoder (v0 only solves rows with one unknown after substitution).
- DPLPMTUD, ACK frequency negotiation, 0-RTT anti-replay, loss-based CC fallback.
- Native datapath (sendmmsg/GSO/io_uring) via Panama FFM; Kotlin/Native targets.

## v0.2 additions (2026-08-22)
- **Short header** (`Compact.kt`): 1 flag byte | 4-byte server-assigned connId | 1–4 byte truncated PN. 6 B typical (was 14).
- **Compact Msg frame**: flag bits for offset/fin/length presence, delta msgId varint, implied length for last frame. 2 B typical (was 16).
- **ConnParams** TLV (`Params.kt`): negotiable `tagLen` (16|8), `dictId` for shared-dictionary payload codec, `maxDatagram`, `ackFreq`, `shortConnId`. Unknown tags skipped.
- **Resumption** (`Resumption.kt`): stateless encrypted ticket; resumed packet one carries **1288 B** of 0-RTT data (fresh PQ connect: 184 B). Replay window + ticket lifetime enforced.

## Borrowed from QUIC (the good parts — all IETF-standardized mechanisms, no Google code)
| Keep | Why |
|---|---|
| Varints, truncated PN + sliding-window decode | Smallest correct encoding; well-analyzed |
| Connection ID ≠ 4-tuple (migration, NAT rebinding); stateless reset token | Migration and NAT rebinding work; crash recovery via stateless reset (v0.7) lets a restarted server tear down a client that would otherwise retransmit into a black hole |
| Amplification limit (3×) before path validation | Only sane anti-reflection design |
| ACK ranges + ECN counts + ack-delay field | Cheap, precise loss/OWD signal |
| Transport-parameter TLV with grease | Extensibility that survives middleboxes |
| Version negotiation + greased versions | Ossification insurance |
| Header protection (encrypt PN) | Prevents middlebox PN inference; cheap |
| Key update via key-phase bit | Re-key without handshake |
| DPLPMTUD | Real MTU, fewer packets |
| qlog-style structured tracing | Debuggability; borrow the schema |
| MAX_DATA-shape connection flow control (RFC 9000 §4.1) | The congestion credit paces the network, not the peer's application; a cumulative payload-byte limit is the only sound receiver-memory bound (v0.8) |

| Leave behind | Why |
|---|---|
| TLS 1.3 + X.509 in-band | Cert chains kill PQ-era first flight; we pin keys / use tickets |
| Streams as the transport primitive | Head-of-line and flow-control complexity; messages + library streams instead |
| Pure ARQ loss recovery | ≥1 RTT per loss; RLNC instead |
| Sender-driven CC (CUBIC/BBR family) | Builds queues; receiver grants instead |
| Per-stream flow control windows | Triple bookkeeping; per-connection credit only (v0.8's `MaxData` stays per-connection — this row still holds) |
| *(was: Retry tokens / address validation dance)* | **Wrong, and corrected in v0.7 — see "Address validation".** The amplification limit and ticket binding bound *reflected bytes*, not *CPU*: the KEM ran before anything authenticated the sender. Retry is now implemented, but only under pressure, so 0 RTT survives the common case. |
| HTTP/3-shaped priorities | App-layer concern |
| Multipath as extension | Multipath is native: per-path PN space, cross-path repair |

## v0.3 (2026-08-22) — eight parallel modules merged, 74 tests
| Module | File | Status |
|---|---|---|
| Packet crypto: AEAD keys, header protection (pathId in clear), key-phase update | `core/PacketCrypto.kt` | done; transport HP hook still identity |
| ACK tracker (ranges, ECN, gap-ack, RACK loss) + path validation (3x amp, challenge) | `core/AckTracker.kt`, `core/PathValidation.kt` | done; transport still uses its own bitmap ack |
| DPLPMTUD | `core/Pmtud.kt` | done; transport still fixed at MAX_DATAGRAM |
| qlog tracing | `core/Trace.kt` | done; not yet called from transport |
| CUBIC + HyStart++ fallback, HybridCc arbiter | `core/CongestionControl.kt` | done; not yet wired |
| zstd shared-dictionary codec | `core/ZstdDictCodec.kt` | done; `dictId` negotiated but codec not applied in transport |
| Native datapath (Rust, AVX2/NEON GF256, batch UDP, GSO) | `native/` | done; transport still on DatagramChannel + scalar GF256 |
| Transport: connect/resume on the wire, adaptive + reactive FEC, credit | `transport/` | done |

### Measured (Windows 11 loopback, JDK 21, i7-10700K)
```
connect  wire resumed   0-RTT payload at server p50=322us p99=581us
connect  wire fresh-PQ  0-RTT payload at server p50=999us p99=2043us
connect  cpu  client-build p50=267us | server-accept p50=211us | first flight fresh=184B resumed=1232B
adapt    5% loss: delivered 100%, p50=77us p99=940us, redundancy settles 0.115 (v0: 0.50 constant, p99 2.4ms)
rawudp   5% loss: delivered 95.5%, p99=128us   (the floor; loss is the cost)
compress 135 B -> 56 B/msg with 16 KB trained dict (-59%), enc 1.6us dec 0.6us
gf256    native AVX2 0.031 ns/B vs scalar Kotlin 0.765 ns/B (24.5x)
```

### Next integration steps (each is a main commit, no new protocol design)
1. Transport uses `PacketKeys`/`PacketProtection` (real header protection) and `KeyPhaseState`.
2. Replace transport's bitmap acks with `AckTracker`; add `PathValidation` on unknown path / address change.
3. `Pmtud` drives packet size; feed `onPacketAcked(size)` for non-probe packets.
4. `HybridCc` gates sends; `ZstdDictCodec` applied when `dictId != 0`.
5. Transport datapath on `NativeUdp` + `Gf256Native` (off-heap symbols) — removes the 9x→24x copy gap.
6. Second path + cross-path repair striping; netem matrix on WSL.

## v0.4 (2026-08-22) — wave 2 merged: everything wired, netem-driven fixes, native datapath
Transport now uses every core module (PacketKeys/HP/key update, AckTracker, PathValidation, Pmtud, HybridCc,
ZstdDictCodec, Tracer) and runs on `NativeUdpIo` when `-Dtessera.native=on|auto` finds the Rust library.

### Defects found by the first netem matrix (docs/BENCH-netem.md) and their fixes
| # | Symptom (profile) | Root cause | Fix |
|---|---|---|---|
| 1 | ~23 packets in flight at any RTT; 6 % of load at 180 ms | receiver sized grants from acks of its *own* packets | `ReceiverCredit`: local rx-rate x RTT, grant slow-start when the sender drains credit |
| 2 | deadlock after 8–15 s on every lossy profile | lost grant never re-issued | timer-driven `tick`, `currentGrant()` re-send on silence, sender credit probe (0x82) |
| 3 | connect aborts on lost reply / lost first data | no retransmit of reply; 3 TLPs 1 ms apart in one burst | initial+reply retransmit with backoff; 100 ms initial RTT; PTO 2^n until idle timeout |
| 4 | 47 % authFail under reorder (wifi-busy) | 1-byte PN, ±128 window | `MIN_PN_LEN = 2` (header 7 B) |
| 5 | p99 = OWD + 141 ms at 50 msg/s | repairs emitted by count only | tail repair after T = clamp(srtt/8, 0.5, 5 ms) of silence |
| 6 | wifi-busy p50 70 ms at 2000 msg/s vs 4.5 ms at 50 | netem `rate`+jitter ratchets into a standing queue | harness note: compare latencies only within a rate |

### Loopback after wave 2 (Windows 11, JDK 21)
```
connect  wire resumed  0-RTT payload at server p50=288us p99=450us   fresh-PQ p50=790us p99=1250us
adapt    5% loss p50=76us p99=868us  redundancy 0.122  ccMode=UNLIMITED plpmtu=1350
50 msg/s 5% loss: p99 4955us -> 1677us (tail repair)
native   202.6k vs 78.4k pkt/s (2.59x), CPU 6.7 vs 18.4 us/pkt; repair() 16-17x off-heap
```
Next: rerun the netem matrix on this tree (the real verdict), then multipath (second path + cross-path repair),
then `deferSends` around the fragment loop on Windows, off-heap source symbols and PTO ring.

## v0.5 (2026-08-22) — wave 3: in-process link simulator, connect under loss, credit-primary CC
- `transport/NetemSim.kt`: delay/jitter (uniform, normal, pareto), correlation, reorder bypass, Gilbert-Elliott loss,
  rate serialisation, duplicates, netem's 1000-packet limit. Presets = bench/netem/profiles.sh one-way values.
  `ConnConfig.netem`, `conn.attachNetem()`, `bench --netem <preset>`. Every real-link failure now reproduces in seconds.
- Root causes fixed (from run 2): `close()` discarded state (retransmitted initials hit the replay filter; unacked first
  response never re-sent) → graceful close with linger + final ack; bursty loss killed single retransmits → initial/reply/
  PTO trains; PMTUD black-hole detector misfired on loss bursts → verification probe before dropping, 1 s backoff;
  reorder-bypass packets made RACK declare the standing queue lost → reordering window + residual ARQ; tail repair waits
  max(T, 2·gap) on steady streams; CUBIC fallback engages only on ECN-CE or loss with queueing delay (`HybridCc`).
- Bench: `late=` accounting with a generous deadline, `fail=` on connect lines.
- Known open (all addressed in v0.6): (1) on NativeUdpIo a few messages per 5000 are never delivered under FIVEG/WIFI
  seeds (residual ARQ gap); (2) grants are additive deltas, so a lost grant stalls until the re-send timer; (3) p999 under
  long loss bursts is PTO backoff (~375 ms on 5g low-rate).

## v0.6 (2026-08-22) — wave 4: loss-recovery latency under bursty loss, cumulative credit, exact residual ARQ

Target: starlink / lte / 5g-mmwave at 2000 msg/s (BENCH-netem run 3: p99 = floor + 1–2 RTT). Measured on this tree
(in-process NetemSim presets, Windows 11, native datapath; `bench adapt --netem <p>` and `RecoveryTest`): p99 within
one RTT of the link's own one-way p99 on every preset, 100 % delivered, `late=0` (numbers in BENCH-netem.md, run 4).

### What changed
| # | Item | Change |
|---|---|---|
| 1 | Ack-driven repair fires on the first hole | `repairDeficit` runs on every ack: a data packet unacked below the ack's largest pn is *missing* at once (no 3-packet threshold, no reorder window unless reordering has been observed on the path — a repair for a merely reordered packet is harmless, but netem `reorder 5 %` exposes ~100 spurious holes per overtaking packet), matched against covering packets (repair symbols whose window holds the seq, or a data packet carrying it) that are acked or in flight. Unmatched: a repair symbol while the seq is in the encoder window, the retained source re-sent verbatim once it has left it (token bucket, `ConnConfig.gapRepairFraction` = 0.25 of the source rate, burst 32). The scan looks back 5 windows of packet numbers (640 ≈ 270 ms at 2000 msg/s, more than an RTT plus the window) and ahead over everything in flight. `confirmLoss` no longer re-sends anything: it only feeds CC, PMTUD, the loss estimator and the burst statistics (blind re-sends at confirmation were 60 % spurious once the window covered the bursts). |
| 2 | Burst-aware FEC | `PathEstimator.onLoss(pn)` records runs of consecutive lost packet numbers (mean / p95 over the last 64 runs). `fecRedundancy = lossRate × (1 + burstMean/2) + 2.3σ` (cap 0.5; random loss → 1.5 lossRate + 2.3σ). The encoder window grew from 32 to 128 sources (`ConnConfig.fecWindow`, receiver bounds follow `MAX_FEC_WINDOW`): with a sliding window a burst of b losses needs b repair equations emitted while it is still inside the window, and every repair carries the window's other ~W·lossRate unknowns. The decoder is rotated with a 1024-seq overlap instead of a hard cut (the cut dropped the equations in progress and made every repair reaching below the re-fed range useless: ~6 sources per rotation were never recovered). |
| 3 | Cumulative credit | `Frame.Grant.creditBytes` (now 8 bytes) is the absolute limit: the total credit-charged bytes the sender may have sent on the path since setup (`SenderCredit.limit`/`sent`, `max(limit, grant)`). The receiver's limit is `received + target`, slides on every tick and rides on every ACK (`grantsPiggybacked`); standalone grants every target/4 of advance, on a credit probe and on silence. Loss no longer shrinks the target (random loss is FEC's job; congestion is the delay-gated CUBIC fallback's); ECN-CE does. Slow start: a send that runs dry sends a credit probe (at most one per srtt/2; the receiver doubles the target per probe, at most once per minRtt/4); the receive rate is measured over RTT windows (per-tick rates turned a stalled sender's burst into an 8 MB target). |
| 4 | PTO schedule | `PathEstimator.ptoUs(n)` = pto, 1.5·pto, 2·pto, then doubling (2^n before the first RTT sample), capped at 2 s, never stops. |
| 5 | Exact residual ARQ (new frame `0x83`) | Every ACK carries FEC feedback: `lowest16 largest16 bits(32)` — the lowest fec seq not yet delivered (everything below it is), the largest seen, and a 256-bit delivered map above the lowest (delivered = received or recovered; anchored at the oldest hole like SACK blocks above the cumulative ack). The sender never re-sends what is reported delivered, and re-sends a reported hole whose symbol last left more than a loss timeout plus the window span ago (`feedbackResends`): the rank safety net for what the greedy repair match cannot prove, and the only retry for a hole older than the packet-number scan. Without it a source that stayed undelivered through its repairs was stuck for the rest of the connection. |
| 6 | Datapath hardening | `NativeUdpIo`: a send failure is counted (`sendErrors`, `dropped`, first error in `ioStats`) and never thrown out of the rx / timer / app flush (an IOException used to end the endpoint's rx thread or timers for good); a refused GSO run goes out per datagram (`gsoFallback`); GSO runs stay within 64 segments and 65535 − 48 bytes. Rust `udp::send_gso` splits oversized input into super-datagrams the kernel accepts and falls back to user-space segmentation on any error but would-block (EMSGSIZE included); `ChannelUdpIo` counts send/tick errors the same way. Bench: the receiver waits max(10 s, 50 RTT) after the *last send actually went out* (the old deadline, n × gap from the start, excluded the warm-up and cut the last ~30 messages of every 50 msg/s run — the "Linux tail loss" of run 3/4 was the harness). |
| 7 | Ack size | ACKs carry at most `ConnConfig.maxAckRanges` = 16 ranges (was 32: 256 B per ACK, 2.6 Mbit/s of the lte profile's 30 Mbit/s) — all 32 for 2 s after any out-of-order arrival, since a late packet lands in an old range and a cap that drops it before the sender saw it acked turns reordering into spurious loss (wifi-busy). |

### Sizing the window and the ratio (simulated with the real codec, GE loss, acks sharing the chain, 60k sources)
`arq` = lost sources the window never recovered (a round trip), `p99` = delivery delay of *all* sources in sources (0.5 ms each at 2000 msg/s).

| preset (loss, mean burst) | W32 ρ0.12 | W64 ρ0.12 | W64 ρ0.20 | W128 ρ0.12 | W128 ρ0.16 | W128 ρ0.20 |
|---|---|---|---|---|---|---|
| starlink (1.6 %, 3.3) | arq 29 % of lost, p99 17 | 6 %, p99 18 | 0 %, 9 | 0 %, 18 | 0 %, 13 | 0 %, 9 |
| lte (4.8 %, 5) | 62 %, p99 = ARQ | 35 %, ARQ | 7.6 %, 57 | 13 %, 147 | 1.2 %, 88 | 0 %, 57 |
| 5g-mmwave (4.8 %, 2.5) | 31 %, ARQ | 5.5 %, 62 | 0 %, 24 | 0 %, 63 | 0 %, 39 | 0 %, 25 |

Grouped repairs (k back-to-back every m sources) were worse than spread ones at equal ρ in every cell (a burst is
covered by the repairs emitted while it is inside the window, not by their grouping), so repairs stay spread. The
burst-aware formula lands at ρ ≈ 0.20 / 0.13–0.15 / 0.10 on lte / 5g / starlink from the measured run lengths.

### Bytes overhead vs loss model (client wire bytes / payload bytes delivered, 2000 msg/s × 1200 B)
| preset | loss model | redundancy ratio | overhead v0.5 → v0.6 (bench adapt, seed 1) |
|---|---|---|---|
| starlink | GE 1.6 %, ~3-packet bursts (2.4 in pn space) | 0.09–0.10 | 1.149 → 1.147 |
| lte | GE 4.8 %, ~5-packet bursts (3.8) | 0.17–0.25 | 1.240 → 1.291 |
| 5g-mmwave | GE 4.8 %, ~2.5-packet bursts (2.6) | 0.13–0.16 | 1.214 → 1.245 |

The ratio buys the p99: lte's 30 Mbit/s carries 2000 × 1200 B at 67 % before any repair, ~90 % with the ratio, acks
and re-sends — the queueing that comes with it is the price of recovering 5-packet bursts without a 150 ms round trip.
Damping the burst term by queueing delay was tried and rejected: it starved 5g-mmwave and lte (their queueing is
netem's jitter ratchet, not our load) and every loss that then needed a round trip cost more than the bytes saved.

### Known open
- Slow start from the 10-packet initial window takes ~0.5 s (6 doublings at one per half RTT on lte); stalls inside a
  short warm-up land in the measured p999.
- The lte profile still engages the CUBIC fallback now and then (loss with queueing delay and an apparently starved
  window after a credit stall); a few cwnd stalls per run remain.
- The FEC feedback map covers 256 seqs above the oldest hole; holes further up are accounted optimistically until the
  edge moves (at 5 % loss the edge moves within ~60 ms).

### v0.6 addendum — RLNC decoder correctness (2026-08-22)
The ~1/5000 "wrong solve" seen in transport stats was a bug in `RlncDecoder.learn()` (kernel-independent): a re-sent
source for a seq that was already a pivot row's key left a stale row under the old key; a later repair could reduce it to
one unknown and learn `c·X` unnormalized, poisoning every row referencing it. Fixed by re-inserting such rows through
`insert()` (reduced-echelon invariants I1–I4 now hold after every call). `RlncDecoder(symbolSize, validator)` vets every
solved symbol; the transport's validator checks `len` and the `[0x80 02 fecSeq16]` prefix, which no GF-multiple can
preserve, and `inconsistent` counts zero-unknown repairs with a non-zero remainder. 200k-symbol harness (`RlncHarness`,
test fixtures): 115–9632 wrong per 200k before → 0. Known flaky test: `NetemTest.twoThousandMessagesPerSecondDeliverEverythingOnTime`
(real-time simulator under full-suite load; passes 3/3 in isolation).

### v0.6 corrections — where this document disagreed with the code

Pinning golden wire vectors (`core/src/test/.../WireVectorsTest.kt`) surfaced three places where this spec
described something the code does not do. The code is authoritative; the spec was wrong.

- **Short-header packet number is 2–4 bytes, not 1–4.** `ShortHeader.MIN_PN_LEN = 2` since the reorder fix, so a
  1-byte PN is parseable but never emitted. The short header is 7 bytes typical, not 6.
- **`0x81` is Padding**, carrying 2–257 bytes, used for PMTUD probes. Previously the `0x80+` range was described
  only as "extension/grease" with no assignments listed.
- **There is no version field on the wire.** "Version negotiation + greased versions" is listed among the
  mechanisms kept from QUIC, but v0 packets carry no version; `Wire.VERSION` only tags the build. It remains a
  design intention, not an implemented mechanism, and is listed under open items instead.

### Closed: native dual-stack on Windows

A native `::` socket used to be IPv6-only on Windows — Rust's `UdpSocket::bind` creates and binds in one step, so
`IPV6_V6ONLY` was left at the OS default (cleared on Linux, set on Windows and the BSDs). A native client bound
`0.0.0.0` could not reach `[::1]` and a native echo on `::` never heard 127.0.0.1, which cost the loopback control
arm of the 2026-08-24 live test.

`udp::bind_std` now takes the `::` wildcard down a manual `socket()` → `setsockopt(IPPROTO_IPV6, IPV6_V6ONLY, 0)`
→ `bind()` path per platform (`bind_dual_stack_wildcard` in `udp/unix.rs` and `udp/windows.rs`); a failing
`setsockopt` fails the open with its OS code like any other bind error. Clearing the option is only half of it: an
AF_INET6 socket refuses a `sockaddr_in`, so an IPv4 destination is rewritten `::ffff:a.b.c.d` on send and
`decode_sockaddr` narrows a v4-mapped source back to IPv4 — the address a caller sees is the same one the JDK
channel datapath reports. `NativeUdpIo.dualStackCapable` still *measures* the property at startup rather than
assuming it, since an older library or a host that refuses the option must still degrade to `0.0.0.0` rather than
fail.

### v0.7 — receive-side flow control (memory bounds)

`Msg` carries a wire-controlled `offset` and reassembly buffers grow to `offset + len`, so an authenticated peer
could force an arbitrary allocation from **one** crafted fragment (`offset ≈ 2^31`, `fin` set → a ~2 GB `ByteArray`
from a single packet) or pin memory with unboundedly many never-completed messages. `Reassembler` enforces three
local caps, reported via `ConnStats.oversizeDropped` / `reassemblyRefused` (the delivered-but-unread inbox got its
own wire mechanism in v0.8 — see "connection flow control" below):

- `ConnConfig.maxMessageBytes` (16 MiB): a fragment with `offset + len` over this is dropped before any buffer is
  sized. Computed in `Long`, so the `offset + len` sum cannot overflow past the check.
- `maxConcurrentReassembly` (64): fragments for a new message id beyond this many in-progress messages are dropped.
- `maxReassemblyBytes` (64 MiB, ≥ maxMessageBytes): the fragment that would breach the total drops its whole message.

Worst-case buffered memory is bounded by `maxReassemblyBytes` plus one in-flight grow increment (≤ maxMessageBytes).

The delivered-but-unconsumed `inbox` queue was still unbounded at v0.7 (an application that stops calling
`receive()` grew it without limit); v0.8's `MaxData` frame closed that. A first attempt clamped the *congestion*
credit by inbox headroom instead and is recorded here because the failure is instructive: the credit is monotonic
and counted in charged wire bytes, so the clamp was timing-dependent — it held on the channel datapath and let the
native (batched) datapath run the inbox to 7.7 MB of an 8 MB offered load. It was reverted unshipped.

### v0.7 — CONNECTION_CLOSE frame (0x08)

Before this the only way a peer learned a connection was gone was `idleTimeoutMs` (10 s) of silence, during which it
held all the connection's state. `Frame.Close` (`0x08 code(1) reasonLen(1) reason(reasonLen)`) is an authenticated,
in-band signal: the receiver frees at once. It is sent once, at `finishClose()` — i.e. only after any linger for
unacked data is done, so a server re-sending a lost reply does not tell the client to drop before that reply
arrives — best effort and non-eliciting (the sender is unregistering; a lost CLOSE falls back to the idle timeout).
Surfaced as `ConnStats.closeSent` / `closeReceived` / `peerCloseCode`.

### v0.7 — stateless reset (frame-less, lost-keys teardown)

CLOSE is the *both-sides-have-keys* case. It cannot cover a peer that has *lost* its keys (a restarted or crashed
server): with no key it cannot authenticate a frame, so a client keeps retransmitting into a black hole until its
idle timeout (10 s). A **stateless reset** (RFC 9000 §10.3 shape, `core/StatelessReset.kt`) closes that gap.

- **Token.** `token(secret, shortConnId)` = first 16 bytes of HMAC-SHA256(secret, the 4 big-endian connId bytes).
  The 4-byte short connId is the *only* per-connection input a restarted server has: it rides in the clear on every
  short packet (header protection masks the packet number and two flag bits, never the connId), so the server can
  recompute the token for a connection it has otherwise entirely forgotten.
- **Secret survives a restart.** `resetSecret = HKDF(ticketKey, "tessera stateless-reset")` — the operator-provided
  ticket key that already outlives a restart for resumption tickets and Retry tokens, under a distinct label.
- **Delivery is confidential.** The token cannot ride in a ConnParams TLV (those are varint Longs). Instead the
  server appends it to the **already-encrypted** handshake reply body: `params | ticketLen(2) | ticket | token(16)`.
  The client reads it (guarded: a reply with no trailing 16 bytes just means no token — an older server) into
  `peerResetToken`. An off-path observer never sees it and so cannot forge a reset.
- **Emission (server, on the demux miss).** `UdpIo` gained `onUnmatchedShort`, fired on both datapaths where a short
  packet's 4-byte id matches no connection. The server answers with a ~40-byte short-header-shaped packet
  (`F_INITIAL` clear) that is random except its last 16 bytes, which are the token for that id. Two safeguards
  against an attacker who floods unknown ids: it is **never** larger than the packet that provoked it (no
  reflection/amplification — a runt draws nothing), and a global token bucket caps emission (`RESET_PER_SEC`, 2000/s).
- **Detection (client, on the demux miss).** A restarted server does not know the client's short id, so its reset
  carries a random one and lands on the client's own `onUnmatchedShort`. The client compares the packet's trailing
  16 bytes (constant-time) against every connection's `peerResetToken`; on a match it tears that connection down
  (`onStatelessReset`, mirroring `onPeerClose`) instead of retransmitting to the idle timeout. No match: dropped, as
  before. Surfaced as `ConnStats.resetsReceived` and `TesseraServer.resetsSent`.

**Out of scope / still open.** Only the server-restart direction is covered (a client's short packets carry the
server's assigned id, which the server can recompute). The reverse — a client restart, where the server would need a
token for the client's id — is not implemented. As with QUIC, a stateless reset is unauthenticated by construction,
so an on-path attacker who observes the token could replay it; it is confidential in transit, which bounds this to
on-path adversaries, who can already do worse.

### v0.8 — connection flow control (`MaxData`, frame 0x09)

The reassembly caps (v0.7) bound partial messages; nothing bounded *complete* ones. `inbox`, the
delivered-but-unread queue, grew without limit under an application that stopped calling `receive()` — a
cooperative footgun rather than an attack (the sender is authenticated), but the last unbounded receiver memory.
The fix is a second, independent limit in the RFC 9000 §4.1 shape, deliberately not a tweak to the congestion
credit: the credit is monotonic, per-path, and counted in charged **wire** bytes (source + repair + re-sends), so
any receiver-memory bound piggybacked on it is timing-dependent — that experiment failed on the native datapath
and was reverted (see v0.7 above).

**Mechanism.** All units are app-payload bytes (pre-encode = post-decode, the unit of one `send()` call). The
receiver advertises `Frame.MaxData(consumed + ConnConfig.recvWindowBytes)` where `consumed` counts only what the
application has actually read out of the inbox; the advert is absolute and monotone, so the sender keeps
`max(limit, advert)` (`core/FlowSender`) and every copy is idempotent. `send()` charges the **whole message up
front** before its first fragment — a message either fits the window or waits whole, so a sender is never
stranded mid-message — and the client charges its 0-RTT first flight the same way at establishment. Re-sends and
repair are never re-charged (the FEC-seq delivery bitmap already guarantees each unique fragment is processed at
most once on receive). The invariant is structural, not paced: delivered ≤ charged ≤ limit ≤ consumed + window,
hence unread inbox ≤ `recvWindowBytes` in app bytes, with zero slack, on either datapath — no step references
time, rate, or batch boundaries.

**Advertisement.** Piggybacked on every ACK (9 bytes, like the Grant limit); standalone once at establishment —
a first `send()` above the sender's initial allowance (`FlowSender.INITIAL_WINDOW` = 10 × 1350 B, mirroring the
credit's initial window) blocks *before* emitting anything ack-eliciting, so no ACK would ever exist to carry the
limit — and from the timer when consumption has advanced a quarter window past the last advert.

**Blocked sender.** A flow-blocked `send()` waits with no `creditWaitMs` bound (a stalled reader is backpressure
by design, not an error) and the endpoint emits a flow probe (`0x84 0x00` + Ping, the credit probe's shape,
backed off to 1 s while unanswered); the probe's elicited ACK carries the current limit, so a lost advert heals
within a probe interval + RTT. The wait exits on close from either side and on **rx-silence** beyond
`idleTimeoutMs`: the probes refresh `lastTxUs` and the idle timeout keys on `max(lastRxUs, lastTxUs)`, so without
its own rx-silence check a sender blocked against a *dead* peer would keep itself alive and hang forever. A
stalled-but-alive reader acks the probes, so live backpressure holds indefinitely.

**Held-gap release, famine-proof (2026-08-25).** v0.9 held gap credits and released them `real/3` per window
while healthy, floor-quantum while dead. The healthy branch's missing floor was a deadlock: the sender's
uncharged-but-counted repair spend can overshoot the limit by megabytes, the healed link then reads healthy,
and `real/3` of zero flow releases nothing — a permanent GRANT_LIMITED stall against an audible peer (the
"high-BDP credit famine", BENCH; it is also what the live 5G "send blocked for 5000ms" errors were). The held
pool now DRAINS at `max(floorBytes, heldGap/8)` per window — but only when three keys align, each one added
after its absence was measured to re-arm a contested overload (BENCH carries the ladder): the window is
healthy AND **stall-shaped** (real arrivals under one floor quantum — a flowing window releases exactly
v0.9's real/3), AND the transport reported the receive side **fully caught up** (every source delivered,
nothing reassembling — a contested receiver almost never is), AND no new gap charge for 3 windows (**stale
deaths only** — fresh deaths mean contested recycling, where each drained slice funded a burst whose deaths
refilled the pool). Repairs stay credit-ungated by design: a hard overshoot gate was tried and deadlocked
tighter (arriving repairs are the credit engine).

**ECN end to end (2026-08-25).** The CE reaction machinery existed at every layer (AckTracker CE counts,
SenderCredit's 10% cut, ReceiverCredit's target shrink, HybridCc.onEcnCe engaging CUBIC) but the transport
never fed it: rx hardcoded `ecnCe=false` and a rising ACK CE count reached only SenderCredit. Now an arriving
CE mark shrinks the receiver's credit target and is echoed in the ACK CE count, and each rise of that count
engages the CUBIC fallback (`PathState.seenPeerEcnCe`). Real IP-header ECN is unreachable from JDK sockets, so
marks arrive via `NetemSim.EcnCe` (in-process side channel; `NetemSim.ecnThreshold` step-marks like an
L4S/DCTCP AQM) — the wiring is transport-real, the marking is sim-only until a raw-socket datapath carries TOS.
Measured (BENCH "F8 remainder"): a marking AQM makes bulk 3× faster with 23× fewer forced drops than the
identical drop-only queue.

**The reliability horizon (2026-08-25).** A new source at seq `f` overwrites the retained symbol of
`f − BODY_RING` (4096), the verbatim-re-send memory. Before W2, nothing stopped the sender outrunning it:
a confirmed loss older than the ring became permanently unrepairable (`resendEvicted`), the receiver's
cumulative edge froze, its `DELIVERED_BITS` (8192) wrap started misreading late arrivals as old deliveries,
and the connection deadlocked (measured: bulk on transcont delivered 5.9k/45.5k then wedged — BENCH
"W2 bulk local"). `send()` now waits on the invariant `nextFecSeq − peerLowestUndelivered < BODY_RING`:
the slot a source overwrites is only destroyed once the peer's cumulative delivered edge (FEC feedback,
piggybacked on every ACK) has passed it. Eviction is structurally impossible; silent loss became
backpressure; the receiver's DELIVERED_BITS assumption is sound by construction (BODY_RING < DELIVERED_BITS)
and tripwired (`horizonAssumedDelivered` counts any exercise of it — nonzero means the invariant broke).
Wait semantics mirror the flow-window wait: indefinite against an audible peer, rx-silence beyond
`idleTimeoutMs` throws; `horizonWaiters` joins the flow-probe trigger so a lull still elicits the ACKs the
feedback rides on. Throughput consequence: at most BODY_RING undelivered sources in flight (~5.5 MB), which
caps very-high-BDP paths; the correct future relief is a bigger (configurable) ring, never a wider horizon
than the ring. No wire change. Stats: `horizonStalls`/`horizonStallUs` in the stalls segment.

Credit and cwnd stalls share these semantics since the E5 `closed` fix (2026-08-25): a slow-granting receiver
is the congestion controller doing its job, and a radio stall shorter than `idleTimeoutMs` must be survivable
— a 6 s scheduler stall used to trip the unconditional 5 s `creditWaitMs` bound on both ends of a live 5G run
(BENCH-netem, "The `closed` mystery"). The `creditWaitMs` bound now fires only after that long of
*continuous refusal by the amplification budget with an audible peer* — the peer talks but validation keeps
failing, so the 3× budget is deliberately withheld: an anomaly, not backpressure. Neither an unvalidated
path per se (a rebind mid-stall leaves the new path unvalidated through the silence) nor a momentary amp
refusal as a link comes back qualifies; both wait like ordinary stalls.

**Behavior change.** `send()` now refuses a message larger than `maxMessageBytes` outright. Before v0.8 such a
message was silently black-holed — every fragment `oversizeDropped` at the peer — and under flow control it would
additionally have blocked its sender forever (the charge can never complete). Loud beats both.

**Deliberate non-goals.** (1) Receiver-side drops of charged messages leak window permanently: a
reassembly-refused fragment or a `codec.decode` failure kills a message the sender already charged, and the
receiver cannot credit back a size it never learned. Honest same-version peers hit neither (sequential sends keep
concurrent partials far below the caps); `oversizeDropped` / `reassemblyRefused` / `codecErrors` expose it.
(2) v0 has no negotiation: the contract is compatible configs on both ends, and `recvWindowBytes ≥
max(maxMessageBytes, INITIAL_WINDOW)` is enforced locally. A peer sending messages larger than *our* window
blocks forever on its side — and keeps being acked, so its rx-silence escape does not fire; that is a
misconfiguration, visible in its `flowStalls`/`flowStallUs`. (3) Per-message/stream windows: per-connection only,
per the borrowed-from-QUIC table.

Surfaced as `ConnStats.flowStalls` / `flowStallUs` / `flowProbes` / `maxDataSent` / `maxDataPiggybacked` and the
snapshots `flowLimitBytes` / `flowChargedBytes` / `flowConsumedBytes`. Covered by `core FlowControlTest` (unit),
`WireVectorsTest` (golden vector), fuzz corpus, and `transport FlowControlTest` — whose central test pins the
invariant on **both** datapaths in one run, because the reverted attempt's failure mode was exactly
datapath-dependence.

### v0.9 — credit growth governed by dead credit (the F8 collapse fix)

F8 (TEST-PLAN F8b) measured Tessera collapsing on any saturated tail-drop bottleneck: goodput ~0 at 56–82 %
self-inflicted drops, solo or contested. Two legs funded it, both in `ReceiverCredit`:

1. **Growth read "blocked sender" as demand.** On a saturated queue the sender always looks blocked — its
   packets leave; they die — and the slow-start doubling granted the 8 MB ceiling within ~150 ms of saturation.
2. **Gap credits made the sliding limit a rate-passthrough.** `limit = received + target`, and crediting dead
   bytes instantly advanced `received` at the death rate: the faster credit died, the faster the limit slid.
   No target policy can bind a sender through that.

The redesign introduces **dead credit** — gap credits are bytes the sender charged that died in flight — as the
receiver's one direct, uninflatable congestion observable (measured per rate window against real arrivals; a
lossy radio link shows its loss rate, a few percent; a collapsing bottleneck shows 50–80 %):

- **Real-only receive rate.** `rxBytesPerSec` (and the BDP floor built on it) counts actual arrivals; gap
  credits used to inflate it with the *offered* rate under loss.
- **Held-back death.** A gap amid continuous arrivals goes into a held pool, released per window at real/3
  while healthy and at a floor-quantum trickle while not (zero release deadlocked: a blocked sender makes no
  flow, and silence regenerates no evidence — the trickle's own deliveries do). A gap revealed after ≥3 silent
  windows is an **outage**, credited instantly — a post-handover drain burst stays funded (F9), and congestion
  never looks like that (its queue delivers continuously).
- **Reorder-corrected.** A late packet that fills a previously-credited gap reverses the charge, with the
  balance carried across window boundaries — without the carry, jitter reordering (wifi-busy) read as ~35 %
  dead and starved a healthy link.
- **Growth gating.** Doubling requires dead credit under 25 %, is capped at 4× the measured-real BDP
  (growth-only — a stale cap can never cut the target; cutting it is what broke grant-blackout recovery in a
  rejected variant), freezes on one ≥50 % window (a deep queue hides overshoot until it is full; the EWMA alone
  is 2–3 windows late), decays 10 %/tick to the BDP floor while dead credit persists, and probes at ×1.25
  instead of ×2 for 2 s after storm evidence.

Measured (CoexistenceTest, 20 Mbit / 40 ms / tail-drop): solo 0 → **2.01 MB/s of 2.5, zero drops** (asserted);
deep-buffer vs CUBIC 0.46–0.57 MB/s while the neighbour keeps ≥78 % and recovers fully; shallow-contested
Tessera yields to a trickle (scavenger posture — the safe side of the still-open F8 fairness policy; a
contested-shallow `send()` historically hit the 5 s creditWaitMs timeout; since the E5 `closed` fix that bound
applies only to unvalidated paths, and a contested send() simply waits against the audible peer). Radio profiles
unchanged: full suites green on both datapaths, and the in-process lte bench sits inside the v0.8 band
(p50 82–84.4 ms, p99 112.5–125.2 ms, 6 × 5000/5000 delivered; p999 126–560 ms vs baseline 128–351 ms — a
5-sample statistic, noise both sides). The engaged-CUBIC layer from the first campaign round (shortfall-driven
engagement, gated repairs, FEC freeze) remains as the backstop for regimes the credit governor misjudges.

### v0.9 — client rebind on rx-silence (NAT-mapping death)

The E5 live run (BENCH-netem) measured ~1/3 of cellular connections delivering nothing after a successful
handshake: the carrier CGNAT dropped the flow's mapping, the server's packets died at the stale entry, and the
client retransmitted into it for the rest of the run — the idle timeout keys on `max(lastRx, lastTx)`, so a
sending client never times out. The cure already existed (F4 migration: the server migrates on a non-probing
packet from a new address and revalidates by challenge/response); what was missing was the client-side trigger.

`ConnConfig.rebindSilenceMs` (default 2 s, 0 disables): when something *response-demanding* has been outstanding
that long with nothing heard at all since it went out, the client opens a fresh self-owned socket (fresh source
port = fresh mapping), moves the connection onto it (`rebind`, the `adopt` move — pn spaces, keys, credit and
tracker carry over untouched), closes the previous self-owned socket, and announces itself with an eliciting
Ping. The trigger measures the **unanswered-solicitation clock** — armed by the first unanswered eliciting send,
cleared by any authenticated rx — not raw rx-silence: raw silence accumulates across legitimate mutual idle and
fired on the first post-idle send, and latest-eliciting-send time resets on every retransmit so a dead mapping
never looked old (both misfires caught by `RebindTest.quietButAliveConnectionsNeverRebind`). Unanswered rebinds
back off exponentially (to 60 s); an answered one resets the backoff. The fresh socket carries its own
stateless-reset check, since the owning endpoint's unmatched-short hook no longer covers the connection.
Surfaced as `ConnStats.rebinds`; covered by `RebindTest` (a symmetric 5-tuple black hole via io wrappers —
messages sent into the dead mapping are recovered after the rebind by ordinary retransmission).
