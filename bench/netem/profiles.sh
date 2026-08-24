#!/usr/bin/env bash
# Link profiles for loopback via tc netem (Linux / WSL2 with CAP_NET_ADMIN).
#
#   usage: [sudo] bench/netem/profiles.sh <profile> | clear | show | rtt | list | version
#
# A profile is delay +- jitter, a loss model and a rate cap, installed as the root qdisc of $DEV (default lo).
# Loopback caveat: one netem on lo sits on the egress path of BOTH directions, so the effective RTT is about
# 2 x `delay` (one-way latency is as written) and the rate cap is shared by both directions. `rtt` measures it
# with ping. Numbers are from public measurements; tune as you collect your own.
#
# LOSS_MODEL selects how the loss column is rendered:
#   gemodel (default)  Gilbert-Elliott `loss gemodel p r` where given (p = good->bad, r = bad->good transition
#                      probability per packet; every packet in the bad state is lost, so the AVERAGE loss is
#                      p/(p+r) and the mean burst is 1/r packets), plain `loss p` otherwise
#   simple             `loss p 25%` (correlated random loss) for every profile: fallback for a tc without gemodel
#   none               no loss at all: delay / jitter / reorder / rate only (used with the bench's --lossSim)
#
# Validated 2026-08-22 on WSL2 (6.18.33.2-microsoft-standard-WSL2, tc iproute2-7.0.0): every line below, including
# `loss gemodel` and the normal/pareto distribution tables, is accepted as written.
#
# Calls sudo by itself when not root. tc is looked up outside sudo's secure_path as well (NixOS keeps it in
# /run/current-system/sw/bin).
set -euo pipefail
DEV=${DEV:-lo}
LOSS_MODEL=${LOSS_MODEL:-gemodel}
TC=${TC:-$(command -v tc 2>/dev/null || true)}
for c in /run/current-system/sw/bin/tc /usr/sbin/tc /sbin/tc; do [ -n "$TC" ] || [ ! -x "$c" ] || TC=$c; done
[ -n "$TC" ] || { echo "profiles.sh: tc not found" >&2; exit 1; }
SUDO=""; [ "$(id -u)" -eq 0 ] || SUDO=sudo

PROFILES="lan-clean transcont starlink starlink-lossy-only lte wifi-busy 5g-mmwave"

# --- scheduled outage (satellite handover) -------------------------------------------------------------------
# netem has no periodic outage: every impairment it models is stochastic. `starlink` therefore starts a background
# helper that flips the qdisc to `loss 100%` for HANDOVER_MS every HANDOVER_EVERY seconds and back, which is the
# closest tc equivalent of a handover gap. It is a real second process: `clear` (and applying any other profile)
# kills it, and the helper restores the un-outaged qdisc on SIGTERM so a kill mid-outage cannot leave the link
# blackholed. It runs in its own session (setsid) so killing the group also kills the `sleep` it is parked in.
# Cadence accuracy is `sleep`-grade (tens of ms), not the sim's sub-ms; use NetemSim for anything that measures the
# edge of the gap. Verify nothing survives with: bench/netem/profiles.sh clear && pgrep -fa 'profiles.sh __handover'
HANDOVER_EVERY=${HANDOVER_EVERY:-15}
HANDOVER_MS=${HANDOVER_MS:-200}
HANDOVER_PIDFILE=${HANDOVER_PIDFILE:-/tmp/tessera-netem-handover-$DEV.pid}
# setsid is optional: without it the helper shares our process group, so a TERM landing while it is parked in
# `sleep` is only handled when that sleep returns - handover_stop escalates to KILL after 2 s for that case.
SETSID=$(command -v setsid 2>/dev/null || true)

handover_stop() {
  [ -f "$HANDOVER_PIDFILE" ] || return 0
  pid=$(cat "$HANDOVER_PIDFILE" 2>/dev/null || true)
  $SUDO rm -f "$HANDOVER_PIDFILE"
  [ -n "${pid:-}" ] || return 0
  $SUDO kill -TERM -- "-$pid" 2>/dev/null || $SUDO kill -TERM "$pid" 2>/dev/null || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || { echo "handover helper $pid stopped"; return 0; }; sleep 0.2; done
  $SUDO kill -KILL -- "-$pid" 2>/dev/null || true
  echo "handover helper $pid killed (did not stop on TERM)" >&2
}

# handover_start <up-netem-args...> -- <down-netem-args...>
handover_start() {
  local up="" down="" seen=0
  for a in "$@"; do if [ "$a" = "--" ]; then seen=1; elif [ $seen -eq 0 ]; then up="$up $a"; else down="$down $a"; fi; done
  handover_stop
  ${SETSID:+"$SETSID"} "$0" __handover "$up" "$down" >/dev/null 2>&1 &
  echo $! > "$HANDOVER_PIDFILE"
  echo "handover helper $(cat "$HANDOVER_PIDFILE"): ${HANDOVER_MS}ms outage every ${HANDOVER_EVERY}s on $DEV"
}

# loss <p%> [<r%>] -> netem loss tokens for $LOSS_MODEL (see header)
loss() {
  case "$LOSS_MODEL" in
    none)   ;;
    simple) echo "loss $1 25%" ;;
    *)      if [ $# -ge 2 ]; then echo "loss gemodel $1 $2"; else echo "loss $1"; fi ;;
  esac
}
show()   { "$TC" qdisc show dev "$DEV"; }
clear_() { handover_stop; $SUDO "$TC" qdisc del dev "$DEV" root 2>/dev/null || true; }
apply()  { clear_; $SUDO "$TC" qdisc add dev "$DEV" root netem "$@"; echo "applied on $DEV: netem $*"; show; }
rtt()    { ping -q -c "${PING_COUNT:-10}" -i 0.2 -W 2 127.0.0.1 | tail -n 2 || true; }

case "${1:-}" in
  # internal: the handover loop, re-exec of this script under setsid (see handover_start)
  __handover)
    up=$2; down=$3
    trap '$SUDO "$TC" qdisc change dev "$DEV" root netem $up 2>/dev/null || true; exit 0' TERM INT
    dur=$(awk "BEGIN{printf \"%.3f\", $HANDOVER_MS/1000}")
    while :; do
      sleep "$HANDOVER_EVERY"
      $SUDO "$TC" qdisc change dev "$DEV" root netem $down 2>/dev/null || exit 0   # qdisc gone: profile cleared
      sleep "$dur"
      $SUDO "$TC" qdisc change dev "$DEV" root netem $up 2>/dev/null || exit 0
    done ;;
  clear)     clear_; show ;;
  show)      show ;;
  rtt)       rtt ;;
  list)      echo "$PROFILES" ;;
  version)   "$TC" -V ;;
  # control: no impairment at all (plain loopback, qdisc noqueue)
  lan-clean) clear_; echo "applied on $DEV: no impairment (lan-clean)"; show ;;
  # transcontinental fibre: ~180 ms RTT, tiny jitter, 0.1% random loss
  transcont) apply delay 90ms 2ms $(loss 0.1%) rate 1gbit ;;
  # LEO satellite WITH the handover that defines it: ~70 ms RTT, GE bursts, plus a 200 ms outage every 15 s
  # (Starlink reassigns terminals on the 15-second UTC boundaries; reported gaps run from tens of ms to several
  # hundred, 200 ms is the pessimistic-but-ordinary end). netem on lo cannot do the uplink/downlink asymmetry the
  # NetemSim preset models (one qdisc, both directions) - the rate below is the downlink cap for both.
  starlink)  apply delay 35ms 12ms $(loss 0.5% 30%) rate 100mbit
             handover_start delay 35ms 12ms $(loss 0.5% 30%) rate 100mbit -- delay 35ms 12ms loss 100% rate 100mbit ;;
  # the pre-handover starlink profile: what every starlink row in docs/BENCH-netem.md was measured with
  starlink-lossy-only) apply delay 35ms 12ms $(loss 0.5% 30%) rate 100mbit ;;
  # LTE: ~90 ms RTT, normal +-15 ms jitter, GE bursts p=1% r=20% (~4.8% average loss, ~5-packet bursts), 30 Mbit/s
  lte)       apply delay 45ms 15ms distribution normal $(loss 1% 20%) rate 30mbit ;;
  # busy Wi-Fi: ~16 ms nominal RTT, heavy-tailed (pareto) +-20 ms jitter, 3% random loss, 5% of packets reordered
  # (netem sends them without delay; measured RTT avg ~36 ms, min ~0.04 ms)
  wifi-busy) apply delay 8ms 20ms distribution pareto $(loss 3%) reorder 5% rate 80mbit ;;
  # 5G mmWave: ~24 ms RTT, heavy-tailed jitter, GE bursts p=2% r=40% (~4.8% average loss, ~2.5-packet bursts), 400 Mbit/s
  5g-mmwave) apply delay 12ms 8ms distribution pareto $(loss 2% 40%) rate 400mbit ;;
  *) echo "usage: $0 <$(echo "$PROFILES" | tr ' ' '|')|clear|show|rtt|list|version>   (env: DEV=$DEV LOSS_MODEL=$LOSS_MODEL)" >&2; exit 1 ;;
esac
