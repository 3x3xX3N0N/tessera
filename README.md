# Tessera

Kotlin transport protocol targeting fastest-in-class tail latency on lossy paths: PQ-hybrid 1-RTT handshake,
sliding-window RLNC loss recovery, receiver-driven congestion control, native multipath. See `docs/SPEC.md`.

```
./gradlew test                                   # core unit tests
./gradlew :bench:run --args="rawudp --lossSim 0.05"
./gradlew :bench:installDist && bench/build/install/bench/bin/bench connect   # wire 0-RTT, fresh vs resumed
bench/build/install/bench/bin/bench adapt                                    # adaptive FEC at 5% loss
bench/build/install/bench/bin/bench compress                                 # shared-dict codec
bench/build/install/bench/bin/bench native                                   # channel vs native datapath
bench/build/install/bench/bin/bench tessera --lossSim 0.05
sudo -E bench/netem/run-matrix.sh                # Linux/WSL: full link-profile matrix
```

Modules: `core` (wire format, FEC, CC, crypto, ACK/path, PMTUD, tracing, codec) · `transport` (connections) · `native` (Rust SIMD GF256 + batch UDP via FFM) · `bench` (harness + netem profiles).
