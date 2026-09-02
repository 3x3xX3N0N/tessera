"""The two-node bulk campaign, driven from the desk: stall confirmation, the growth-rule pair A/B, and the
TLS comparison, on the scl->syd pair, with every rep's full output kept.

Everything the week taught is baked in rather than remembered: the sink node's TCP port is opened for the TLS
arm; segmentation offload is turned off on the shaped node (a TSO superpacket is one unit to netem); every arm
is order-alternated (ABBA); a guard kill leaves a MISS line, never silence; the sink's stats are dumped on every
failure; and the sink is restarted per rep for the pair A/B so a config never leaks across arms.

    python bench/mesh/bulk_campaign.py [--reps 6] [--out bench/mesh/bulk-campaign.out] [--only stall|pair|tls] [--guard 240]

Node scripts are rendered from state.json into the scratch dir and scp'd; the sink runs on `syd`, the pusher and
the shaping on `scl` (the data direction).
"""
import json, os, subprocess, sys, time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import mesh  # noqa: E402

ST = json.load(open(os.path.join(HERE, "state.json")))
NODE = {n["region"]: n for n in ST["nodes"]}
SINK, PUSH = NODE["syd"], NODE["scl"]
TOK = "bk3"
T = "/opt/tessera/bin/tessera"
GEN = os.path.join(os.environ.get("TEMP", "/tmp"), "tessera-bulk-gen")
os.makedirs(GEN, exist_ok=True)


def opt(k, d):
    return sys.argv[sys.argv.index("--" + k) + 1] if ("--" + k) in sys.argv else d


REPS = int(opt("reps", "6"))
OUT = opt("out", os.path.join(HERE, "bulk-campaign.out"))
ONLY = opt("only", "")
GUARD = int(opt("guard", "240"))   # per-transfer guard for the tessera arms; must exceed bulkpush's own receipt wait
log = open(OUT, "a", encoding="utf-8")


def say(s):
    print(s, flush=True); log.write(s + "\n"); log.flush()


def scp(local, ip, remote):
    subprocess.run(["scp", "-i", mesh.KEY, "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null",
                    "-o", "BatchMode=yes", local, "root@%s:%s" % (ip, remote)], capture_output=True, timeout=300)


def ssh(node, cmd, timeout=300):
    rc, out, err = mesh.ssh(node["ip"], cmd, timeout=timeout)
    return out


def render(name, body):
    p = os.path.join(GEN, name)
    open(p, "w", encoding="utf-8", newline="\n").write(body)
    return p


# ---------------------------------------------------------------- node scripts

SINK_RESTART = """#!/bin/bash
# sink-restart.sh <growthCap> <delayGateUs>: fresh bulksink under the given credit knobs; prints its peer key
pkill -f 'tessera[.]tools' 2>/dev/null; sleep 1
(setsid nohup %(T)s bulksink --token %(TOK)s --port 51820 --growthCap $1 --delayGateUs $2 > /root/sink.log 2>&1 &)
sleep 6
grep -a -oE 'peer-key [A-Za-z0-9+/=]+' /root/sink.log | head -1 | cut -d' ' -f2
""" % dict(T=T, TOK=TOK)

SHAPE = """#!/bin/bash
# shape.sh recipe|lte|clear  - on the pusher's egress, data direction only (dports 51820/51821, udp+tcp)
tc qdisc del dev enp1s0 root 2>/dev/null
pkill -f 'TESSERA_SHAPE_W[D]' 2>/dev/null
if [ "$1" = clear ]; then ethtool -K enp1s0 tso on gso on 2>/dev/null; echo cleared; exit 0; fi
ethtool -K enp1s0 tso off gso off tx-udp-segmentation off 2>/dev/null
tc qdisc add dev enp1s0 root handle 1: prio bands 3
if [ "$1" = recipe ]; then
  # the deep-outstanding stall recipe: a shallow 20 mbit bottleneck, no injected loss
  tc qdisc add dev enp1s0 parent 1:3 handle 31: tbf rate 20mbit burst 32kb limit 96000
else
  # lte-shaped: bursty GE loss + a 30 mbit cap, the modelled loss process
  tc qdisc add dev enp1s0 parent 1:3 handle 30: netem loss gemodel 1% 20% limit 1000
  tc qdisc add dev enp1s0 parent 30: handle 31: tbf rate 30mbit burst 64kb limit 1500000
fi
for port in 51820 51821; do
  tc filter add dev enp1s0 protocol ip parent 1:0 prio 1 u32 match ip protocol 17 0xff match ip dport $port 0xffff flowid 1:3
  tc filter add dev enp1s0 protocol ip parent 1:0 prio 1 u32 match ip protocol 6 0xff match ip dport $port 0xffff flowid 1:3
done
(setsid nohup sh -c 'sleep 3600; tc qdisc del dev enp1s0 root # TESSERA_SHAPE_WD' >/dev/null 2>&1 &)
echo shaped-$1
"""

PUSH_ONE = """#!/bin/bash
# push-one.sh <label> <arm> <port> <mb> <guard> <pace> <peerkey>: one transfer, full output kept, MISS on guard
L=$1; ARM=$2; PORT=$3; MB=$4; G=$5; PACE=$6; K=$7
timeout $G %(T)s bulkpush --connect %(SINKIP)s:$PORT --peer-key "$K" --token %(TOK)s --arm $ARM --mb $MB --pace $PACE > /root/rep-$L.log 2>&1
RC=$?
LINE=$(grep -a bulkpush /root/rep-$L.log | head -1)
if [ -n "$LINE" ]; then echo "$L $LINE"; else echo "$L MISS(guard ${G}s rc=$RC) $(grep -a -oE 'PUSH-FAIL.*' /root/rep-$L.log | head -1 | cut -c1-200)"; fi
""" % dict(T=T, TOK=TOK, SINKIP=SINK["ip"])


def install():
    for name, body, node in (("sink-restart.sh", SINK_RESTART, SINK), ("shape.sh", SHAPE, PUSH), ("push-one.sh", PUSH_ONE, PUSH)):
        scp(render(name, body), node["ip"], "/opt/" + name)
    ssh(SINK, "chmod +x /opt/sink-restart.sh"); ssh(PUSH, "chmod +x /opt/shape.sh /opt/push-one.sh")
    say("node scripts installed (sink %s, pusher %s)" % (SINK["ip"], PUSH["ip"]))


def sink(cap, gate):
    key = ssh(SINK, "bash /opt/sink-restart.sh %d %d" % (cap, gate), timeout=180).strip().splitlines()
    key = key[-1] if key else ""
    assert len(key) > 200, "sink did not come up: %r" % key[:80]
    return key


def push(label, arm, mb, guard, pace, key):
    port = 51820 if arm == "tessera" else 51821
    line = ssh(PUSH, "bash /opt/push-one.sh %s %s %d %d %d %d '%s'" % (label, arm, port, mb, guard, pace, key), timeout=guard + 60).strip()
    say("  " + line)
    if "MISS" in line:
        say("    sink: " + ssh(SINK, "tail -3 /root/sink.log | cut -c1-300", timeout=60).strip().replace("\n", "\n          "))
    return line


def phase_stall():
    say("\n=== PHASE 1: stall confirmation - the recipe, %d reps, shipped sink config ===" % REPS)
    key = sink(4, 0)
    say(ssh(PUSH, "bash /opt/shape.sh recipe").strip())
    miss = 0
    for r in range(1, REPS + 1):
        if "MISS" in push("stall-rep%d" % r, "tessera", 5, GUARD, 0, key): miss += 1
    say("stall confirmation: %d/%d stalled (expected 0/%d)" % (miss, REPS, REPS))


def phase_pair():
    say("\n=== PHASE 2: growth-rule pair A/B on the recipe, %d pairs, ABBA ===" % REPS)
    arms = {"shipped": (4, 0, 0), "pair": (8, 2000, 8)}
    say(ssh(PUSH, "bash /opt/shape.sh recipe").strip())
    for r in range(1, REPS + 1):
        order = ("shipped", "pair") if r % 2 else ("pair", "shipped")
        for arm in order:
            cap, gate, pace = arms[arm]
            key = sink(cap, gate)
            push("pair-rep%d-%s" % (r, arm), "tessera", 5, GUARD, pace, key)


def phase_tls():
    say("\n=== PHASE 3: tessera vs TLS, lte-shaped, TSO off, %d pairs, ABBA, 20 MB ===" % max(4, REPS // 2 * 2))
    key = sink(4, 0)
    say(ssh(PUSH, "bash /opt/shape.sh lte").strip())
    for r in range(1, max(4, REPS // 2 * 2) + 1):
        order = ("tessera", "tls") if r % 2 else ("tls", "tessera")
        for arm in order:
            push("tls-rep%d-%s" % (r, arm), arm, 20, 480 if arm == "tessera" else 900, 0, key)


if __name__ == "__main__":
    say("\n##### bulk campaign %s  sink=syd %s  pusher=scl %s" % (time.strftime("%Y-%m-%d %H:%M"), SINK["ip"], PUSH["ip"]))
    install()
    try:
        if ONLY in ("", "stall"): phase_stall()
        if ONLY in ("", "pair"): phase_pair()
        if ONLY in ("", "tls"): phase_tls()
    finally:
        say(ssh(PUSH, "bash /opt/shape.sh clear").strip())
        say("##### done %s" % time.strftime("%H:%M"))
