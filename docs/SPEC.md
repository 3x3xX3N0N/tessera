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
- Known open: (1) on NativeUdpIo a few messages per 5000 are never delivered under FIVEG/WIFI seeds (residual ARQ gap —
  in progress); (2) grants are additive deltas, so a lost grant stalls until the re-send timer — switch to a cumulative
  credit advertisement; (3) p999 under long loss bursts is PTO backoff (~375 ms on 5g low-rate) — cap the first backoffs
  once the path RTT is known.
