#!/usr/bin/env python3
"""Apply / clear a real `tc` qdisc on a mesh node's egress.

Why this exists: every profile number in docs/BENCH-netem.md comes from `NetemSim`, the in-process simulator,
because the netem matrix is Linux-only and this project has been developed on Windows. The mesh nodes are Linux
boxes with iproute2, sitting on real 100-358 ms paths — so a shaped node gives what neither half had alone: a
REAL long path carrying a REAL tail-drop bottleneck, with real timers and a real NIC underneath.

It matters because the two open items both stalled on exactly that gap. The repair-clock A/B cannot run on the
unshaped mesh (no leg loses a packet, BENCH "E4 at ten regions"), and the credit growth rule has only ever been
compared against a model of a queue (`CreditGrowthSweepTest`).

Shaping is EGRESS-ONLY, and it is applied **only to the probe's UDP ports** (51820/51821) through a `prio`
band. The first version shaped the root qdisc instead: a `tbf rate 2mbit` there throttled the node's own SSH
and locked it out, twice, and recovery was an API reboot — tc is not persistent, which is the one mercy.
Management traffic now rides the unshaped bands.

A **watchdog** also clears the qdisc after `--for` seconds (default 1800) whether or not anyone is still
connected, so a shaping mistake costs a wait rather than a reboot. `clear` kills it early and is idempotent.

usage:
  shape.py apply <region> [--loss "1% 20%"] [--rate 20mbit] [--limit 64] [--delay 0ms] [--for 1800]
  shape.py clear <region|all>
  shape.py show  <region|all>
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mesh  # noqa: E402  (same directory, shares ssh/state)

PORTS = (51820, 51821)   # the echo's tessera and udp listeners; everything else stays unshaped


def nodes():
    st = mesh.load()
    return {n["region"]: n for n in st["nodes"]}


def dev(ip):
    _, out, _ = mesh.ssh(ip, "ls /sys/class/net | grep -v lo | head -1", timeout=60)
    return out.strip() or "enp1s0"


def apply(region, loss, rate, limit, delay, hold):
    n = nodes()[region]
    d = dev(n["ip"])
    # prio root: band 3 is shaped, bands 1-2 carry everything else (SSH included). netem does loss/delay, tbf
    # hangs beneath it for the rate cap and the tail-drop queue — the queue depth is the variable that decides
    # whether a growth rule sprays, so it is explicit rather than left to netem's own policing.
    parts = ["tc qdisc del dev %s root 2>/dev/null || true" % d,
             "tc qdisc add dev %s root handle 1: prio bands 3" % d]
    netem = "tc qdisc add dev %s parent 1:3 handle 30: netem" % d
    if delay and delay != "0ms":
        netem += " delay %s" % delay
    if loss:
        netem += " loss gemodel %s" % loss
    netem += " limit 1000"       # netem's own backlog; the tbf below is the queue under test
    parts.append(netem)
    if rate:
        parts.append("tc qdisc add dev %s parent 30: handle 31: tbf rate %s burst 32kbit limit %d"
                     % (d, rate, limit * 1500))
    # Match BOTH directions of the probe's traffic. dport alone shapes only the prober (whose packets are
    # addressed TO the listener); the echo's replies leave FROM 51820 to an ephemeral port, so a dport-only
    # filter matched nothing on the echo node and the shaping silently did nothing — the arms came back
    # byte-identical to unshaped, which is what gave it away. Verify with `show`: band 1:3 must have Sent > 0.
    for port in PORTS:
        for direction in ("dport", "sport"):
            parts.append("tc filter add dev %s protocol ip parent 1:0 prio 1 u32 "
                         "match ip protocol 17 0xff match ip %s %d 0xffff flowid 1:3" % (d, direction, port))
    # watchdog: clear unconditionally after `hold` seconds, so a shaping mistake costs a wait, not a reboot
    # The watchdog carries a marker so `clear` can kill it by a pattern that appears NOWHERE else on the
    # killing command line — the bracket trick only works when the plain text is absent from the rest of it,
    # which is why matching on 'tc qdisc del dev' failed twice: the clear command contains that string itself.
    parts.append("(setsid nohup sh -c 'sleep %d; tc qdisc del dev %s root # TESSERA_SHAPE_WD' >/dev/null 2>&1 &)"
                 % (hold, d))
    parts.append("tc qdisc show dev %s" % d)
    rc, out, err = mesh.ssh(n["ip"], " && ".join(parts), timeout=120)
    print("%s (%s): rc=%d  [shaped: udp/%s only; auto-clears in %ds]"
          % (region, d, rc, "/".join(str(p) for p in PORTS), hold))
    print(out.strip() or err.strip())


def clear(region):
    for r in (list(nodes().keys()) if region == "all" else [region]):
        n = nodes()[r]
        d = dev(n["ip"])
        # The bracket class is load-bearing, exactly as mesh.py's own pkill comment says: `pkill -f 'tc qdisc
        # del dev'` matches the ssh command line that CARRIES it, so the clear killed its own shell and left the
        # qdisc up while reporting success. Caught by checking `show` afterwards instead of trusting the exit.
        cmd = ("pkill -f 'TESSERA_SHAPE_W[D]' 2>/dev/null; tc qdisc del dev %s root 2>/dev/null; "
               "tc qdisc show dev %s" % (d, d))
        rc, out, err = mesh.ssh(n["ip"], cmd, timeout=120)
        print("%s: %s" % (r, out.strip().splitlines()[0] if out.strip() else err.strip()))


def show(region):
    for r in (list(nodes().keys()) if region == "all" else [region]):
        n = nodes()[r]
        d = dev(n["ip"])
        _, out, _ = mesh.ssh(n["ip"], "tc -s qdisc show dev %s" % d, timeout=120)
        print("--- %s" % r)
        print(out.strip())


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "show"
    arg = sys.argv[2] if len(sys.argv) > 2 else "all"

    def opt(k, d=None):
        return sys.argv[sys.argv.index("--" + k) + 1] if ("--" + k) in sys.argv else d

    if cmd == "apply":
        apply(arg, opt("loss"), opt("rate"), int(opt("limit", "64")), opt("delay", "0ms"), int(opt("for", "1800")))
    elif cmd == "clear":
        clear(arg)
    elif cmd == "show":
        show(arg)
    else:
        sys.exit(__doc__)
