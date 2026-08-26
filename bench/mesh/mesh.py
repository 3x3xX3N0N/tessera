#!/usr/bin/env python3
# E4 - global WAN mesh. Deploys one node per region, runs a full probe matrix (every node probes every other
# over Tessera AND raw UDP on the identical path, adjacent in time), collects results, destroys what it made.
#
# Why a full matrix rather than a star: a star measures one node's uplink N times. A mesh measures PATHS, and
# the interesting legs are the long-haul ones crossing several transit providers - that is where a backbone
# stops being clean and the transport has something to prove.
#
# Two deployment facts this encodes, learned the hard way (docs/LIVE-TEST.md):
#   * Vultr silently drops script_id at instance creation, so nothing is trusted to boot automation - every
#     node is set up over SSH where each step is verifiable.
#   * Vultr's Ubuntu 24.04 ships ufw ACTIVE default-deny, so test ports are opened explicitly. The tell for
#     that failure is a TIMEOUT rather than a connection refused.
#
# usage: python3 bench/mesh/mesh.py deploy|status|setup|run|destroy [--regions a,b,c] [--rate 50] [--count 300]
# State lives in bench/mesh/state.json so phases run separately and a crash cannot silently orphan a node;
# `destroy` reads that file and is safe to run at any time.
import json, os, subprocess, sys, time, base64, concurrent.futures as cf, urllib.request, urllib.error

# The Vultr key is IP-allowlisted to the home IPv4 connection. On a multi-homed host (wired + phone hotspot)
# an ordinary call can leave over IPv6 via the radio and come back 401 "Unauthorized IP address" - which reads
# like a bad key and is not. Force IPv4: the programmatic equivalent of the curl -4 rule in docs/LIVE-TEST.md.
import socket as _socket
_gai = _socket.getaddrinfo
_socket.getaddrinfo = lambda h, p, f=0, t=0, pr=0, fl=0: _gai(h, p, _socket.AF_INET, t, pr, fl)

HERE = os.path.dirname(os.path.abspath(__file__))
STATE = os.path.join(HERE, "state.json")
KEY = os.path.join(HERE, "meshkey")
API = "https://api.vultr.com/v2"
PLAN, OS_ID = "vc2-1c-1gb", 2284
TOOLS = "https://github.com/3x3xX3N0N/tessera/releases/download/v0.1.2-tools/tessera.zip"

def token():
    for line in open(os.path.expanduser("~/.tessera-cloud.env"), encoding="utf-8"):
        if line.startswith("VULTR_API_KEY="):
            return line.split("=", 1)[1].strip().strip('"').strip("'")
    sys.exit("no VULTR_API_KEY")

def api(method, path, body=None):
    req = urllib.request.Request(API + path, method=method,
                                 data=json.dumps(body).encode() if body else None,
                                 headers={"Authorization": "Bearer " + token(),
                                          "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            raw = r.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        sys.exit("API %s %s -> %d: %s" % (method, path, e.code, e.read().decode()[:200]))

def load():
    return json.load(open(STATE)) if os.path.exists(STATE) else {}

def save(s):
    json.dump(s, open(STATE, "w"), indent=1)

def ssh(ip, cmd, timeout=300):
    p = subprocess.run(["ssh", "-i", KEY, "-o", "StrictHostKeyChecking=no",
                        "-o", "UserKnownHostsFile=/dev/null", "-o", "BatchMode=yes",
                        "-o", "ConnectTimeout=12", "root@" + ip, cmd],
                       capture_output=True, text=True, timeout=timeout)
    return p.returncode, p.stdout, p.stderr

def deploy(regions):
    st = load()
    if st.get("nodes"):
        sys.exit("state.json already has nodes; run destroy first")
    if not os.path.exists(KEY):
        subprocess.run(["ssh-keygen", "-t", "ed25519", "-N", "", "-f", KEY, "-q", "-C", "tessera-mesh"], check=True)
    pub = open(KEY + ".pub").read().strip()
    kid = api("POST", "/ssh-keys", {"name": "tessera-mesh-%d" % int(time.time()), "ssh_key": pub})["ssh_key"]["id"]
    tok = base64.b64encode(os.urandom(15)).decode().replace("+", "").replace("/", "").replace("=", "")[:20]
    nodes = []
    for r in regions:
        inst = api("POST", "/instances", {"region": r, "plan": PLAN, "os_id": OS_ID,
                                          "label": "tessera-mesh-" + r, "sshkey_id": [kid],
                                          "backups": "disabled"})["instance"]
        nodes.append({"region": r, "id": inst["id"], "ip": "", "peer_key": ""})
        print("  %s: requested" % r)
    save({"nodes": nodes, "ssh_key_id": kid, "token": tok})
    print("deployed %d nodes" % len(nodes))

def status(wait=True):
    st = load()
    if not st.get("nodes"):
        sys.exit("no state")
    for _ in range(60):
        pending = []
        for n in st["nodes"]:
            d = api("GET", "/instances/" + n["id"])["instance"]
            n["ip"] = d["main_ip"]
            if not (d["status"] == "active" and d["server_status"] == "ok" and d["main_ip"] != "0.0.0.0"):
                pending.append(n["region"])
        save(st)
        print("all ready" if not pending else "waiting on: " + ",".join(pending))
        if not pending or not wait:
            break
        time.sleep(20)
    return st

def setup_one(n, tok):
    ip = n["ip"]
    for _ in range(20):
        if ssh(ip, "echo ok", timeout=30)[0] == 0:
            break
        time.sleep(10)
    else:
        return n["region"], "ssh never came up"
    script = (
        "set -e\n"
        "ufw allow 51820/udp >/dev/null 2>&1; ufw allow 51821/udp >/dev/null 2>&1\n"
        "export DEBIAN_FRONTEND=noninteractive\n"
        "apt-get update -qq >/dev/null 2>&1\n"
        "apt-get install -y -qq openjdk-21-jre-headless unzip >/dev/null 2>&1\n"
        "mkdir -p /opt && cd /opt\n"
        "curl -sL " + TOOLS + " -o t.zip && unzip -oq t.zip && (mv tessera-* tessera 2>/dev/null || true)\n"
        "cd /opt/tessera\n"
        "(nohup ./bin/tessera echo --token " + tok + " --port 51820 --also-udp > /var/log/echo.log 2>&1 &)\n"
        "sleep 25\n"
        "grep -oE 'peer-key [A-Za-z0-9+/=]+' /var/log/echo.log | head -1 | cut -d' ' -f2\n")
    rc, out, err = ssh(ip, script, timeout=900)
    pk = ""
    for l in out.splitlines():
        if len(l.strip()) > 200:
            pk = l.strip()
    return n["region"], pk if pk else "no peer key (rc=%d) %s" % (rc, err[-120:])

def setup():
    st = status(wait=True)
    tok = st["token"]
    with cf.ThreadPoolExecutor(max_workers=12) as ex:
        for region, res in ex.map(lambda n: setup_one(n, tok), st["nodes"]):
            node = [n for n in st["nodes"] if n["region"] == region][0]
            if len(res) > 200:
                node["peer_key"] = res
                print("  %s: echo up" % region)
            else:
                print("  %s: FAILED - %s" % (region, res))
    save(st)

def probe_pair(src, dst, tok, rate, count):
    # Tessera then raw UDP, adjacent in time on the identical path - the A/B rule this project measures by.
    out = {}
    base = "cd /opt/tessera && ./bin/tessera probe "
    cmds = {
        "tessera": base + "--connect %s:51820 --peer-key %s --token %s --rate %d --count %d --size 1200 2>/dev/null | grep -E '^tessera |^connect '" % (dst["ip"], dst["peer_key"], tok, rate, count),
        "udp": base + "--connect %s:51821 --transport udp --token x --rate %d --count %d --size 1200 2>/dev/null | grep -E '^udp '" % (dst["ip"], rate, count),
    }
    for mode in ("tessera", "udp"):
        rc, so, se = ssh(src["ip"], cmds[mode], timeout=900)
        out[mode] = so.strip()
    return src["region"], dst["region"], out

def run(rate, count, workers=1):
    # SERIAL BY DEFAULT, and that is a measurement decision rather than caution. These are 1-vCPU nodes; running
    # the matrix concurrently puts five inbound Tessera streams and five outbound on every node at once, and
    # AEAD + RLNC per packet then saturates the CPU while the raw-UDP echo it is compared against costs nothing.
    # Measured: fra->ewr read p50 506 ms concurrently and 81.3 ms alone, against UDP's 81.0 ms - the concurrent
    # matrix was measuring the harness, and would have reported a 6x transport regression that does not exist.
    st = load()
    tok = st["token"]
    live = [n for n in st["nodes"] if n.get("peer_key")]
    pairs = [(a, b) for a in live for b in live if a is not b]
    print("probing %d directed paths across %d nodes (%d/s x %d, workers=%d)" % (len(pairs), len(live), rate, count, workers))
    results = []
    with cf.ThreadPoolExecutor(max_workers=workers) as ex:
        futs = [ex.submit(probe_pair, a, b, tok, rate, count) for a, b in pairs]
        for f in cf.as_completed(futs):
            s, d, o = f.result()
            results.append({"src": s, "dst": d, "tessera": o["tessera"], "udp": o["udp"]})
            print("  %s -> %s done" % (s, d))
    json.dump(results, open(os.path.join(HERE, "results.json"), "w"), indent=1)
    print("wrote bench/mesh/results.json")

def destroy():
    st = load()
    for n in st.get("nodes", []):
        api("DELETE", "/instances/" + n["id"])
        print("  destroyed %s" % n["region"])
    if st.get("ssh_key_id"):
        api("DELETE", "/ssh-keys/" + st["ssh_key_id"])
    for f in (STATE, KEY, KEY + ".pub"):
        if os.path.exists(f):
            os.remove(f)
    print("all cloud resources removed")

if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else "status"
    def opt(k, d):
        return sys.argv[sys.argv.index("--" + k) + 1] if ("--" + k) in sys.argv else d
    if cmd == "deploy":
        deploy(opt("regions", "ewr,fra,nrt,syd,sao,jnb").split(","))
    elif cmd == "status":
        status()
    elif cmd == "setup":
        setup()
    elif cmd == "run":
        run(int(opt("rate", "50")), int(opt("count", "300")), int(opt("workers", "1")))
    elif cmd == "destroy":
        destroy()
    else:
        sys.exit("usage: mesh.py deploy|status|setup|run|destroy")
