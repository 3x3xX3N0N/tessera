#!/usr/bin/env bash
# Full matrix: every profile x {aether, rawudp}. Run from repo root inside WSL/Linux: sudo -E bench/netem/run-matrix.sh
set -e
cd "$(dirname "$0")/../.."
./gradlew -q :bench:installDist
BIN=bench/build/install/bench/bin/bench
mkdir -p bench/results
for p in lte 5g-mmwave wifi-busy starlink transcont; do
  bench/netem/profiles.sh "$p" >/dev/null
  for m in rawudp aether; do
    echo -n "[$p] "; $BIN $m --n 5000 --gapUs 500 --out "bench/results/${p}_${m}.csv"
  done
done
bench/netem/profiles.sh clear
