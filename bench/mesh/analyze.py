#!/usr/bin/env python3
# Turns campaign.json (one record per flow) into the compact dataset the visual embeds.
# A "flow" is one probe run: a fresh source port, hence a fresh ECMP route draw. Flows are the unit of
# comparison here, not messages - which is why the campaign runs many short flows per path rather than one
# long one (docs/BENCH-netem.md, the ECMP finding).
import json, re, statistics as st, sys, os

HERE = os.path.dirname(os.path.abspath(__file__))
PAT = re.compile(r"delivered=(\d+)/(\d+) \(([\d.]+)% lost\)\s+rtt p50=([\d.]+)ms p90=([\d.]+)ms p99=([\d.]+)ms p999=([\d.]+)ms min=([\d.]+)ms")

def parse(block, prefix):
    for line in block.splitlines():
        if line.startswith(prefix):
            m = PAT.search(line)
            if m:
                return dict(got=int(m.group(1)), tot=int(m.group(2)), loss=float(m.group(3)),
                            p50=float(m.group(4)), p90=float(m.group(5)), p99=float(m.group(6)),
                            p999=float(m.group(7)), mn=float(m.group(8)))
    return None

recs = json.load(open(os.path.join(HERE, "campaign.json")))
flows = []
for r in recs:
    t, u = parse(r["tessera"], "tessera"), parse(r["udp"], "udp")
    if t and u:
        flows.append(dict(src=r["src"], dst=r["dst"], rep=r["rep"], t=t, u=u, png=r.get("ping")))

regions = sorted({f["src"] for f in flows} | {f["dst"] for f in flows})
paths = sorted({(f["src"], f["dst"]) for f in flows})
t_msgs = sum(f["t"]["tot"] for f in flows)
u_msgs = sum(f["u"]["tot"] for f in flows)
t_lost = sum(f["t"]["tot"] - f["t"]["got"] for f in flows)
u_lost = sum(f["u"]["tot"] - f["u"]["got"] for f in flows)

# Per-path aggregate: median across the repetitions, so one unlucky ECMP draw cannot define a path.
per_path = []
for (s, d) in paths:
    fs = [f for f in flows if f["src"] == s and f["dst"] == d]
    per_path.append(dict(
        src=s, dst=d, n=len(fs),
        t_p50=st.median(f["t"]["p50"] for f in fs), u_p50=st.median(f["u"]["p50"] for f in fs),
        t_p99=st.median(f["t"]["p99"] for f in fs), u_p99=st.median(f["u"]["p99"] for f in fs),
        t_loss=100.0 * sum(f["t"]["tot"] - f["t"]["got"] for f in fs) / sum(f["t"]["tot"] for f in fs),
        u_loss=100.0 * sum(f["u"]["tot"] - f["u"]["got"] for f in fs) / sum(f["u"]["tot"] for f in fs),
        rtt=st.median(f["u"]["mn"] for f in fs),
        # ICMP floor, matched to the data arms in size and rate. A reference line, not a competitor: ICMP is
        # routinely rate-limited or deprioritised, so it says what the path costs, not what a transport costs.
        ping_p50=st.median([f["png"]["p50"] for f in fs if f.get("png") and f["png"]["n"]] or [0]),
        ping_p99=st.median([f["png"]["p99"] for f in fs if f.get("png") and f["png"]["n"]] or [0]),
        ping_loss=st.median([f["png"]["loss"] for f in fs if f.get("png")] or [0])))

deltas = sorted(p["t_p50"] - p["u_p50"] for p in per_path)
d99 = sorted(p["t_p99"] - p["u_p99"] for p in per_path)
lossy = sorted([p for p in per_path if p["u_loss"] > 0 or p["t_loss"] > 0], key=lambda p: -p["u_loss"])

have_ping = [p for p in per_path if p["ping_p50"] > 0]
over_ping = sorted(p["t_p50"] - p["ping_p50"] for p in have_ping) if have_ping else [0]
udp_over_ping = sorted(p["u_p50"] - p["ping_p50"] for p in have_ping) if have_ping else [0]

out = dict(
    n_ping_paths=len(have_ping),
    t_over_ping_median=st.median(over_ping), u_over_ping_median=st.median(udp_over_ping),
    ping_loss_mean=(sum(p["ping_loss"] for p in have_ping) / len(have_ping)) if have_ping else 0.0,
    regions=regions, n_regions=len(regions), n_paths=len(paths), n_flows=len(flows),
    t_msgs=t_msgs, u_msgs=u_msgs, t_lost=t_lost, u_lost=u_lost,
    t_loss_pct=100.0 * t_lost / max(t_msgs, 1), u_loss_pct=100.0 * u_lost / max(u_msgs, 1),
    d50_median=st.median(deltas), d50_p10=deltas[len(deltas) // 10], d50_p90=deltas[9 * len(deltas) // 10],
    d99_median=st.median(d99),
    better99=sum(1 for p in per_path if p["t_p99"] < p["u_p99"]),
    lossy_paths=lossy[:22], per_path=per_path, deltas=deltas)
json.dump(out, open(os.path.join(HERE, "dataset.json"), "w"), indent=1)

print("flows %d | paths %d | regions %d" % (len(flows), len(paths), len(regions)))
print("messages: tessera %d (lost %d = %.4f%%) | udp %d (lost %d = %.4f%%)" % (t_msgs, t_lost, out["t_loss_pct"], u_msgs, u_lost, out["u_loss_pct"]))
print("p50 delta (T-U) median %+.2f ms  p10 %+.2f  p90 %+.2f" % (out["d50_median"], out["d50_p10"], out["d50_p90"]))
if have_ping:
    print("vs ICMP floor: tessera +%.2f ms median, udp +%.2f ms median, ping loss %.3f%%"
          % (out["t_over_ping_median"], out["u_over_ping_median"], out["ping_loss_mean"]))
print("paths where udp lost: %d | where tessera lost: %d" % (sum(1 for p in per_path if p["u_loss"] > 0), sum(1 for p in per_path if p["t_loss"] > 0)))
