#!/usr/bin/env bash
# Full link-profile matrix on loopback (Linux / WSL2), three sweeps over the profiles in bench/netem/profiles.sh:
#   1. matrix    rawudp, aether, adapt at --n 5000 --gapUs 500 (2000 msg/s) + the over-the-wire connect bench
#   2. lowrate   aether at --n 2000 --gapUs 20000 (50 msg/s) under the same (lossy) profiles: shows whether the
#                2000 msg/s failures are a rate problem or a loss problem
#   3. rttonly   rawudp vs aether at 50 msg/s with the profile's loss removed (LOSS_MODEL=none: delay / jitter /
#                reorder / rate only) and the bench's in-process 5% loss model (--lossSim 0.05) instead, which
#                drops the client's data packets but never grants or acks: FEC repair latency vs real RTT
# Writes
#   bench/results/env.txt                     kernel / tc / JDK versions and the parameters used
#   bench/results/baseline_<mode>.log         plain-loopback baseline: connect, adapt (5% in-process loss), aether --lossSim 0.05
#   bench/results/<label>_env.txt             `tc qdisc show` and ping RTT under that profile (label = profile[-lowrate|-rttonly])
#   bench/results/<label>_<mode>.csv          per-message latency (rawudp / aether / adapt)
#   bench/results/<label>_<mode>.log          full stdout of every run (connect has no csv)
#   bench/results/summary.txt                 every summary line, prefixed with [label]
#
#   sudo -E bench/netem/run-matrix.sh                                   # the whole thing, from any cwd (~25 min)
#   PROFILES="lte starlink" N=2000 sudo -E bench/netem/run-matrix.sh    # subset
#   SKIP_BUILD=1 BASELINE=0 LOWRATE=0 RTTONLY=0 sudo -E bench/netem/run-matrix.sh   # only sweep 1
#   SKIP_BUILD=1 BASELINE=0 MATRIX=0 sudo -E bench/netem/run-matrix.sh              # only sweeps 2 + 3
#
# Privileges: only tc needs root. Started via sudo, the script runs the Gradle build and every benchmark JVM as
# the invoking user ($SUDO_USER) so Gradle caches, build/ and results stay user-owned. Started unprivileged it
# works as well: profiles.sh then calls sudo for tc itself.
# JDK: when `java` is absent but nix is present (e.g. NixOS under WSL), a JDK is resolved once with
# `nix-shell -p $NIX_PKGS` and put on PATH (add cargo rustc to NIX_PKGS if :native ever joins the bench
# classpath; today bench depends on core + transport only).
# netem is removed from $DEV on exit: also on error, per-run timeout or Ctrl-C.
set -euo pipefail
SELF=$(readlink -f "$0"); cd "$(dirname "$SELF")/../.."

PROFILES=${PROFILES:-"lan-clean transcont starlink lte wifi-busy 5g-mmwave"}
MODES=${MODES:-"rawudp aether adapt"}
N=${N:-5000}; GAP_US=${GAP_US:-500}
ADAPT_LOSSSIM=${ADAPT_LOSSSIM:-0}   # adapt's in-process loss model is for hosts without netem: off under real netem
BASELINE=${BASELINE:-1}; MATRIX=${MATRIX:-1}; RUN_CONNECT=${RUN_CONNECT:-1}; SKIP_BUILD=${SKIP_BUILD:-0}
LOWRATE=${LOWRATE:-1}; RTTONLY=${RTTONLY:-1}
LOW_N=${LOW_N:-2000}; LOW_GAP_US=${LOW_GAP_US:-20000}; RTTONLY_LOSSSIM=${RTTONLY_LOSSSIM:-0.05}
RUN_TIMEOUT=${RUN_TIMEOUT:-900}     # seconds per bench invocation (connect under 180 ms RTT needs ~4 min)
RESULTS=${RESULTS:-bench/results}
NIX_PKGS=${NIX_PKGS:-jdk21 cargo rustc gcc}   # :native builds via cargo; gcc supplies the linker
export DEV=${DEV:-lo}
PROF=bench/netem/profiles.sh
BIN=bench/build/install/bench/bin/bench

# ---- privilege split: root only for tc ---------------------------------------------------------------------
if [ "$(id -u)" -eq 0 ] && [ -n "${SUDO_USER:-}" ] && [ "$SUDO_USER" != root ]; then
  as_user() { sudo -u "$SUDO_USER" -H env PATH="$PATH" JAVA_HOME="${JAVA_HOME:-}" CARGO="${CARGO:-}" NIX_PATH="${NIX_PATH:-}" "$@"; }
  own() { chown -R "$SUDO_USER" "$@" 2>/dev/null || true; }
else
  as_user() { "$@"; }
  own() { :; }
fi

# ---- JDK + cargo bootstrap (the :native module builds a Rust cdylib) ---------------------------------------
if ! command -v java >/dev/null 2>&1 || ! command -v cargo >/dev/null 2>&1; then
  command -v nix-shell >/dev/null 2>&1 || { echo "run-matrix.sh: java/cargo missing and no nix-shell to fetch them" >&2; exit 1; }
  echo "java/cargo not found: resolving with nix-shell -p $NIX_PKGS ..."
  NIX_BIN=$(as_user nix-shell -p $NIX_PKGS --run 'printf "%s:%s:%s" "$(dirname "$(readlink -f "$(command -v java)")")" "$(dirname "$(readlink -f "$(command -v cargo)")")" "$(dirname "$(readlink -f "$(command -v cc)")")"')
  export PATH="$NIX_BIN:$PATH"
  JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v java)")")"); export JAVA_HOME
  CARGO=$(command -v cargo); export CARGO
  echo "using java=$(command -v java) cargo=$CARGO cc=$(command -v cc)"
fi
export JAVA_HOME=${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}

# ---- never leave netem on lo -------------------------------------------------------------------------------
cleanup() { rc=$?; trap - EXIT; "$PROF" clear >/dev/null 2>&1 || true; echo "netem cleared: $("$PROF" show)"; own "$RESULTS"; exit $rc; }
trap cleanup EXIT; trap 'exit 130' INT TERM HUP

# ---- build -------------------------------------------------------------------------------------------------
if [ "$SKIP_BUILD" != 1 ]; then echo "building :bench:installDist with $(java -version 2>&1 | head -n 1) ..."; as_user ./gradlew --no-daemon -q :bench:installDist; fi
[ -x "$BIN" ] || { echo "run-matrix.sh: $BIN not found (build failed?)" >&2; exit 1; }
as_user mkdir -p "$RESULTS"
{
  echo "date: $(date -Is)"; echo "kernel: $(uname -srm)"; echo "os: $(. /etc/os-release 2>/dev/null && echo "${PRETTY_NAME:-?}")"
  echo "tc: $("$PROF" version)"; echo "java: $(java -version 2>&1 | head -n 1) ($JAVA_HOME)"; echo "cpus: $(nproc)"
  echo "params: PROFILES=\"$PROFILES\" MODES=\"$MODES\" N=$N GAP_US=$GAP_US ADAPT_LOSSSIM=$ADAPT_LOSSSIM RUN_CONNECT=$RUN_CONNECT DEV=$DEV"
  echo "sweeps: BASELINE=$BASELINE MATRIX=$MATRIX LOWRATE=$LOWRATE RTTONLY=$RTTONLY LOW_N=$LOW_N LOW_GAP_US=$LOW_GAP_US RTTONLY_LOSSSIM=$RTTONLY_LOSSSIM"
} | tee "$RESULTS/env.txt"
: > "$RESULTS/summary.txt"
FAILURES=0

# run <label> <mode> [bench args...]: full stdout -> <label>_<mode>.log, summary lines -> summary.txt
run() {
  local p=$1 m=$2; shift 2; local log=$RESULTS/${p}_${m}.log rc=0 t0=$SECONDS
  echo "--- [$p] bench $m $*"
  as_user timeout -k 10 "$RUN_TIMEOUT" "$BIN" "$m" "$@" >"$log" 2>&1 || rc=$?
  if [ $rc -eq 0 ]; then
    grep -E "^(rawudp|aether|adapt|connect) " "$log" | sed "s/^/[$p] /" | tee -a "$RESULTS/summary.txt" || true
    echo "--- [$p] $m done in $((SECONDS - t0))s"
  else
    echo "[$p] $m FAILED rc=$rc after $((SECONDS - t0))s: $(grep -m1 -E 'Exception|Error' "$log" || echo 'no exception line') (see $log)" | tee -a "$RESULTS/summary.txt"
    FAILURES=$((FAILURES + 1))
  fi
}

# sweep <label suffix> <loss model> <n> <gapUs> <connect 0|1> <modes> [extra bench args...]
sweep() {
  local suffix=$1 lossModel=$2 n=$3 gap=$4 connect=$5 modes=$6; shift 6; local p m label
  for p in $PROFILES; do
    label=$p$suffix
    echo "=== $label: $p with LOSS_MODEL=$lossModel, $n msgs at ${gap}us gap, modes: $modes $*"
    { LOSS_MODEL=$lossModel "$PROF" "$p"; echo "ping 127.0.0.1 under $label:"; "$PROF" rtt; } | tee "$RESULTS/${label}_env.txt"
    for m in $modes; do
      args=(--n "$n" --gapUs "$gap" --out "$RESULTS/${label}_${m}.csv" "$@")
      if [ "$m" = adapt ] && [ $# -eq 0 ]; then args+=(--lossSim "$ADAPT_LOSSSIM"); fi
      run "$label" "$m" "${args[@]}"
    done
    if [ "$connect" = 1 ]; then run "$label" connect; fi
    "$PROF" clear >/dev/null
  done
}

# ---- baseline on plain loopback (the README's three commands) ----------------------------------------------
if [ "$BASELINE" = 1 ]; then
  "$PROF" clear >/dev/null; echo "=== baseline: plain loopback ($("$PROF" show))"
  run baseline connect
  run baseline adapt --out "$RESULTS/baseline_adapt.csv"                   # defaults: n=5000 gap=500us lossSim=0.05
  run baseline aether --lossSim 0.05 --out "$RESULTS/baseline_aether.csv"   # defaults: n=5000 gap=1000us
fi

# ---- the three sweeps --------------------------------------------------------------------------------------
if [ "$MATRIX" = 1 ];  then sweep ""         gemodel "$N"     "$GAP_US"     "$RUN_CONNECT" "$MODES"; fi
if [ "$LOWRATE" = 1 ]; then sweep "-lowrate" gemodel "$LOW_N" "$LOW_GAP_US" 0 "aether"; fi
if [ "$RTTONLY" = 1 ]; then sweep "-rttonly" none    "$LOW_N" "$LOW_GAP_US" 0 "rawudp aether" --lossSim "$RTTONLY_LOSSSIM"; fi

echo; echo "=== summary ($RESULTS/summary.txt)"; cat "$RESULTS/summary.txt"
[ "$FAILURES" -eq 0 ] || { echo "$FAILURES run(s) failed" >&2; exit 1; }
