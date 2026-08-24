#!/bin/bash
# Tessera live-test endpoint. Boots a listener that echoes messages back, plus a plain-UDP echo
# on port+1 as the comparison floor. Generates its own keypair and publishes only the PUBLIC key
# over HTTP on :8080 so the probe can be pointed at it without any SSH access.
set -x
exec > /var/log/tessera-boot.log 2>&1

TOKEN="__TOKEN__"
PORT=51820
REL="https://github.com/3x3xX3N0N/tessera/releases/download/v0.1.0-tools/tessera.zip"

export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y openjdk-21-jre-headless unzip curl

mkdir -p /opt && cd /opt
curl -fsSL -o tessera.zip "$REL"
unzip -q -o tessera.zip
T=/opt/tessera/bin/tessera
export JAVA_OPTS="-Dtessera.native=off"      # the bundled native lib is Windows-only

# Keys are generated here and never leave the box; only the public half is published.
$T keygen --out /opt/server.key > /opt/keygen.txt
mkdir -p /opt/pub
tail -n 1 /opt/keygen.txt > /opt/pub/peer-key.txt

# Open the ports (Vultr images ship with an empty ruleset, but be explicit if ufw is active).
if command -v ufw >/dev/null; then ufw allow $PORT/udp; ufw allow $((PORT+1))/udp; ufw allow 8080/tcp; fi

# Publish the public key so the probe can fetch it: public data, served read-only from its own dir.
nohup python3 -m http.server 8080 --directory /opt/pub >/var/log/tessera-pub.log 2>&1 &

nohup $T echo --token "$TOKEN" --port $PORT --key-in /opt/server.key --also-udp \
  >/var/log/tessera-echo.log 2>&1 &

echo "tessera endpoint up on udp/$PORT (tessera) and udp/$((PORT+1)) (plain udp)"
