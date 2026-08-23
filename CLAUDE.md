# Tessera — working notes for Claude Code

A UDP message transport in Kotlin: post-quantum 0-RTT connect, RLNC loss recovery, receiver-driven
congestion control, Rust SIMD datapath. Research prototype, v0 wire format, unaudited.

## Hard constraint

**No Google lineage.** No code derived from any QUIC implementation, and no BBR. Mechanisms come from Noise,
Homa, RLNC literature, CUBIC/HyStart++, and IETF specifications implemented from the documents. `docs/SPEC.md`
records what was deliberately borrowed and what was rejected; `NOTICE` records provenance per mechanism. Keep
that table current when adding anything.

## Layout

| Module | Contents |
|---|---|
| `core` | Wire format and frames, RLNC, congestion control (credit + CUBIC fallback), packet crypto, ACK/path tracking, PMTUD, tracing, dictionary codec |
| `transport` | `TesseraServer` / `TesseraClient` / `TesseraConnection`, the in-process link simulator `NetemSim` |
| `native` | Rust cdylib: SIMD GF(256) and batched UDP, bound through Panama FFM |
| `bench` | Single-process benchmarks and the `tc netem` matrix runner |
| `tools` | Standalone `tessera echo` / `tessera probe` for two-machine tests |

## Build and test

```bash
./gradlew test                                   # ~170 tests across core, transport, native
./gradlew :transport:nativeTest                  # the transport suite again on the native datapath
./gradlew :bench:installDist :tools:installDist
sudo -E bench/netem/run-matrix.sh                # Linux/WSL only: the full link-profile matrix
```

`:native` needs a Rust toolchain; everything degrades gracefully without one. JDK 21 is required
(`jvmToolchain(21)`).

## Working agreements that produced this code

- **Measure, then claim.** Every performance statement in the docs traces to a run in `docs/BENCH-netem.md`,
  including the runs that failed. The netem harness found roughly fifteen real defects; when a new one turns
  up, write down the symptom, the root cause, and the fix, not just the fix.
- **Loopback flatters.** Numbers from a single machine are a lower bound on latency and say nothing about NAT,
  middleboxes, or radios. See `docs/LIVE-TEST.md` for the two-machine setup.
- **Never claim a test passed without running it.** Gradle's build cache will happily restore a green result
  without executing anything — use `--no-build-cache` and `cleanTest` when a result matters.
- Parallel work happened on `agent/*` branches merged with real `--no-ff` merge commits; the history is meant
  to be readable.

## Known gaps

Multipath is designed but not built. Long loss bursts at low message rates fall back to the probe timeout
(LTE at 50 msg/s, p99 ≈ 208 ms). `NetemTest.twoThousandMessagesPerSecondDeliverEverythingOnTime` is a
real-time test and flakes under full-suite load; it passes in isolation. The full list is at the end of
`docs/SPEC.md`.
