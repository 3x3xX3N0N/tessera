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
- `0x05 PathChallenge`, `0x06 Ping`, `0x80+` extension/grease (length-prefixed, skippable).

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
