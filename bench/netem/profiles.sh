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

PROFILES="lan-clean transcont starlink lte wifi-busy 5g-mmwave"

# loss <p%> [<r%>] -> netem loss tokens for $LOSS_MODEL (see header)
loss() {
  case "$LOSS_MODEL" in
    none)   ;;
    simple) echo "loss $1 25%" ;;
    *)      if [ $# -ge 2 ]; then echo "loss gemodel $1 $2"; else echo "loss $1"; fi ;;
  esac
}
show()   { "$TC" qdisc show dev "$DEV"; }
clear_() { $SUDO "$TC" qdisc del dev "$DEV" root 2>/dev/null || true; }
apply()  { clear_; $SUDO "$TC" qdisc add dev "$DEV" root netem "$@"; echo "applied on $DEV: netem $*"; show; }
rtt()    { ping -q -c "${PING_COUNT:-10}" -i 0.2 -W 2 127.0.0.1 | tail -n 2 || true; }

case "${1:-}" in
  clear)     clear_; show ;;
  show)      show ;;
  rtt)       rtt ;;
  list)      echo "$PROFILES" ;;
  version)   "$TC" -V ;;
  # control: no impairment at all (plain loopback, qdisc noqueue)
  lan-clean) clear_; echo "applied on $DEV: no impairment (lan-clean)"; show ;;
  # transcontinental fibre: ~180 ms RTT, tiny jitter, 0.1% random loss
  transcont) apply delay 90ms 2ms $(loss 0.1%) rate 1gbit ;;
  # LEO satellite: ~70 ms RTT, +-12 ms jitter, GE bursts p=0.5% r=30% (~1.6% average loss, ~3-packet bursts)
  starlink)  apply delay 35ms 12ms $(loss 0.5% 30%) rate 100mbit ;;
  # LTE: ~90 ms RTT, normal +-15 ms jitter, GE bursts p=1% r=20% (~4.8% average loss, ~5-packet bursts), 30 Mbit/s
  lte)       apply delay 45ms 15ms distribution normal $(loss 1% 20%) rate 30mbit ;;
  # busy Wi-Fi: ~16 ms nominal RTT, heavy-tailed (pareto) +-20 ms jitter, 3% random loss, 5% of packets reordered
  # (netem sends them without delay; measured RTT avg ~36 ms, min ~0.04 ms)
  wifi-busy) apply delay 8ms 20ms distribution pareto $(loss 3%) reorder 5% rate 80mbit ;;
  # 5G mmWave: ~24 ms RTT, heavy-tailed jitter, GE bursts p=2% r=40% (~4.8% average loss, ~2.5-packet bursts), 400 Mbit/s
  5g-mmwave) apply delay 12ms 8ms distribution pareto $(loss 2% 40%) rate 400mbit ;;
  *) echo "usage: $0 <$(echo "$PROFILES" | tr ' ' '|')|clear|show|rtt|list|version>   (env: DEV=$DEV LOSS_MODEL=$LOSS_MODEL)" >&2; exit 1 ;;
esac
