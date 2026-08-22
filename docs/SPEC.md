# Aether transport — spec v0 (draft)

Goal: fastest-in-class tail latency on lossy, variable last-mile paths (cellular, Wi-Fi, LEO sat), with
post-quantum 1-RTT setup and native multipath. Kotlin reference implementation. No Google lineage.

## Design targets (measured, CI-gated)
| Front | QUIC today | Aether target |
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

## Handshake
Noise-IK shape, hybrid X25519 + ML-KEM-768, HKDF-SHA256. Responder static keys are known a priori (pinned, TOFU,
or short-lived delegated credential signed out-of-band). 0-RTT via PSK with per-connection replay window (TODO).
Amplification: responder sends ≤ 3× bytes received until path validated.

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
| Connection ID ≠ 4-tuple; stateless reset token | Migration, NAT rebinding, crash recovery for free |
| Amplification limit (3×) before path validation | Only sane anti-reflection design |
| ACK ranges + ECN counts + ack-delay field | Cheap, precise loss/OWD signal |
| Transport-parameter TLV with grease | Extensibility that survives middleboxes |
| Version negotiation + greased versions | Ossification insurance |
| Header protection (encrypt PN) | Prevents middlebox PN inference; cheap |
| Key update via key-phase bit | Re-key without handshake |
| DPLPMTUD | Real MTU, fewer packets |
| qlog-style structured tracing | Debuggability; borrow the schema |

| Leave behind | Why |
|---|---|
| TLS 1.3 + X.509 in-band | Cert chains kill PQ-era first flight; we pin keys / use tickets |
| Streams as the transport primitive | Head-of-line and flow-control complexity; messages + library streams instead |
| Pure ARQ loss recovery | ≥1 RTT per loss; RLNC instead |
| Sender-driven CC (CUBIC/BBR family) | Builds queues; receiver grants instead |
| Per-stream flow control windows | Triple bookkeeping; per-connection credit only |
| Retry tokens / address validation dance | Amplification limit + ticket binding cover it at 0 RTT |
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
ZstdDictCodec, Tracer) and runs on `NativeUdpIo` when `-Daether.native=on|auto` finds the Rust library.

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
