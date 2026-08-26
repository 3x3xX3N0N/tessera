# Running a live test across two machines

Everything in [`BENCH-netem.md`](BENCH-netem.md) was measured on a single machine — loopback, with `tc netem`
imitating a link. No packet has ever crossed a real network. This document is how you change that.

You need two machines and about twenty minutes. One **listens** (it must be reachable), the other **probes**
(it does the measuring). Nothing is installed system-wide and nothing is left running afterwards.

---

## 0. Decide which machine listens

The listener needs to accept inbound UDP. In order of preference:

| | How | When |
|---|---|---|
| **IPv6 direct** | Both machines have a global IPv6 address (check `test-ipv6.com`). No NAT, nothing to forward — just allow the port in the local firewall. | Best. Most residential ISPs now provide it. |
| **IPv4 port forward** | Forward one UDP port to the listener in the router, probe connects to the public IPv4. | When one side has no IPv6. Also worth doing deliberately: it exercises the NAT path that IPv6 hides. |
| **Same LAN** | Private addresses, no router config. | A smoke test to prove the setup works before involving the internet. |

If neither side can accept inbound (both behind CGNAT, e.g. 5G home internet), you need either a rendezvous
host or a simultaneous-open hole punch — out of scope here.

## 1. Both machines: build

Requires **JDK 21**. A Rust toolchain is optional — without it the JVM datapath and scalar FEC are used.

```bash
git clone https://github.com/3x3xX3N0N/tessera
cd tessera
./gradlew :tools:installDist
```

That produces `tools/build/install/tessera/` — a self-contained folder you can also just copy to the other
machine instead of building there twice.

## 2. Listener

Pick any shared secret for `--token`; both sides must use the same one.

```bash
tools/build/install/tessera/bin/tessera echo --token <shared-secret> --port 51820 --also-udp
```

It prints a **`--peer-key <base64>`** line. Copy it — that is the pinned public key the prober needs, and it is
regenerated every time you restart the echo, so copy it fresh each run.

Allow the ports inbound. Windows, in an elevated prompt:

```
netsh advfirewall firewall add rule name="tessera" dir=in action=allow protocol=UDP localport=51820-51821
```

Linux with ufw: `sudo ufw allow 51820:51821/udp`. Remove the rule when you are finished.

Find the address to hand to the prober: `ipconfig` on Windows or `ip -6 addr` on Linux — use the **global,
non-temporary** IPv6 address, or your public IPv4 if you are port-forwarding.

## 3. Prober

```bash
T=tools/build/install/tessera/bin/tessera

# Tessera, over the real path
$T probe --connect '[<listener-address>]:51820' --peer-key <paste-from-step-2> --token <shared-secret> \
   --rate 50 --size 1200 --count 2000 --connect-warmup 2 --out tessera.csv

# Plain UDP over the identical path, immediately after — this is the floor to compare against
$T probe --connect '[<listener-address>]:51821' --transport udp --token x \
   --rate 50 --size 1200 --count 2000 --out udp.csv
```

IPv4 targets need no brackets: `--connect 203.0.113.7:51820`.

Run the two back to back and repeat the pair a few times. A home path drifts minute to minute, so a Tessera run
compared against a UDP run from ten minutes earlier tells you very little.

## 4. What you get

```
connect  fresh-PQ   0-RTT payload echoed in   8.4 ms
connect  resumed    0-RTT payload echoed in   5.7 ms  (68% of fresh)
tessera  delivered=2000/2000 (0.00% lost)  rtt p50=... p90=... p99=... p999=... min=...
udp      delivered=1904/2000 (4.80% lost)  rtt p50=... p90=... p99=... p999=... min=...
```

The interesting comparison is **not** p50 — Tessera will always be a little slower there, since it does crypto
and coding that raw UDP does not. It is the **delivery ratio and the p99/p999**: whether the messages UDP loses
arrive anyway, and what they cost when they do.

The CSVs are `seq,rtt_us` with blanks for anything that never arrived.

---

## Reading the numbers honestly

- **These are round trips.** The probe times against its own clock, so no clock synchronisation is needed — but
  do not compare them with the one-way figures in `BENCH-netem.md`.
- **The echo doubles the path.** A loss on either leg shows up as one lost message; you cannot attribute it to a
  direction.
- **Use `--connect-warmup 2`.** The first connect in a fresh JVM pays class loading and the first ML-KEM
  operation — about 100 ms of pure CPU on loopback, which would otherwise swamp the network measurement.
- **Watch the data volume.** 2000 × 1200 B is ~2.4 MB each way. At `--rate 2000` a 60-second run is ~144 MB,
  which matters on a metered connection. Say so before pointing that at someone else's machine.
- **Record the conditions.** Time of day, access type on each end, and a `ping`/`traceroute` between them.
  Evening congestion is real and it will move your numbers.

## When it does not work

| Symptom | Cause |
|---|---|
The client binds the dual-stack `::` wildcard by default, so one socket reaches both IPv6 and IPv4 peers; pass`--bind` only to pin a specific interface. A family mismatch fails immediately with a message naming both ends,rather than timing out.
| `no echo of the 0-RTT payload` | Token mismatch between the two sides, or something on the path is dropping UDP. |
| First connect takes ~100 ms more than expected | Cold JVM. Use `--connect-warmup`. |
| Works on the LAN, not over the internet | Some networks throttle or block UDP on unusual ports. Try `--port 443`; the difference is itself a finding worth writing down. |

## Safety

The listener requires the shared token in the 0-RTT payload and **drops any connection without it, with no
reply** — so it cannot be used as a reflector and does not answer scanners. Echo replies are the same size as
requests, so there is no amplification. Static keys are generated at startup and never written to disk.

It is still unaudited research code opening a UDP port. Time-box the session, do not leave it running
unattended, and if you are asking someone else to run it, tell them exactly that.

## Vultr deployment gotchas (learned the hard way, 2026-08-26)

Four boxes were deployed before one worked, and neither cause was the script:

1. **`script_id` is silently dropped at instance creation.** The POST succeeds, the instance comes up, and
   `GET /v2/instances/{id}` returns `script_id: None` — the startup script never runs and nothing says so.
   `user_data` (cloud-init, base64) attaches reliably; verify either way with
   `GET /v2/instances/{id}/user-data` before waiting on a boot.
2. **Vultr's Ubuntu 24.04 image ships `ufw` ACTIVE with default-deny incoming.** Only port 22 is open, so the
   echo and any log server are silently filtered — the box pings, SSH works, and every test port times out.
   The tell is *timeout* rather than *connection refused*: refused means nothing is listening, timeout means
   packets are being dropped. `ufw allow 51820/udp && ufw allow 51821/udp` (plus 8081/tcp for log publishing).

The reliable recipe is therefore: create with an **SSH key** (`sshkey_id`), then run setup over SSH where every
step is verifiable, rather than trusting boot automation you cannot observe. Take a **wired control run** from
the same box before switching to the radio, so the radio numbers have a same-session baseline.

**The API key is IP-allowlisted to the home connection.** Deploy and destroy while wired; from a hotspot every
call returns 401. On a multi-homed host use `curl -4` so requests leave via the wired NIC.
