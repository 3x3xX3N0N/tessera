#!/usr/bin/env bash
# Link profiles for loopback via tc netem (Linux / WSL2 with CAP_NET_ADMIN). Usage: sudo ./profiles.sh <profile|clear>
# Each profile = delay ± jitter, loss model, rate. Numbers are from public measurements; tune as you collect your own.
set -e
DEV=${DEV:-lo}
clear_() { tc qdisc del dev "$DEV" root 2>/dev/null || true; }
apply() { clear_; tc qdisc add dev "$DEV" root netem "$@"; echo "applied: $*"; }
case "$1" in
  clear)      clear_ ;;
  lte)        apply delay 45ms 15ms distribution normal loss gemodel 1% 20% rate 30mbit ;;
  5g-mmwave)  apply delay 12ms 8ms distribution pareto loss gemodel 2% 40% rate 400mbit ;;
  wifi-busy)  apply delay 8ms 20ms distribution pareto loss 3% reorder 5% rate 80mbit ;;
  starlink)   apply delay 35ms 12ms loss gemodel 0.5% 30% rate 100mbit ;;
  transcont)  apply delay 90ms 2ms loss 0.1% rate 1gbit ;;
  *) echo "profiles: lte 5g-mmwave wifi-busy starlink transcont clear"; exit 1 ;;
esac
