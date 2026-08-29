#!/bin/bash
# Guard msquic's GSO feature behind MSQUIC_NO_GSO so netem sees wire-sized packets.
set -e
F=/opt/msquic/src/platform/datapath_linux.c
python3 - "$F" <<'PY'
import sys
p = sys.argv[1]
s = open(p).read()
old = "    Datapath->Features |= CXPLAT_DATAPATH_FEATURE_SEND_SEGMENTATION;"
new = "    if (getenv(\"MSQUIC_NO_GSO\") == NULL) Datapath->Features |= CXPLAT_DATAPATH_FEATURE_SEND_SEGMENTATION;"
assert s.count(old) == 1, "pattern count %d" % s.count(old)
open(p, "w").write(s.replace(old, new, 1))
print("patched")
PY
cd /opt/msquic/build && make -j1 secnetperf 2>&1 | tail -2
