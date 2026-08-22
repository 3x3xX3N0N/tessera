# Aether

Kotlin transport protocol targeting fastest-in-class tail latency on lossy paths: PQ-hybrid 1-RTT handshake,
sliding-window RLNC loss recovery, receiver-driven congestion control, native multipath. See `docs/SPEC.md`.

```
./gradlew test                                   # core unit tests
./gradlew :bench:run --args="rawudp --lossSim 0.05"
./gradlew :bench:run --args="aether --lossSim 0.05"
sudo -E bench/netem/run-matrix.sh                # Linux/WSL: full link-profile matrix
```

Modules: `core` (wire format, FEC, CC, scheduler, handshake) · `transport` (UDP datapath) · `bench` (harness + netem profiles).
