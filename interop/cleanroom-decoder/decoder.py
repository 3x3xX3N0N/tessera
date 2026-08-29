#!/usr/bin/env python3
"""
Tessera v0 clean-room decoder (round 2 -- full recovery).

Built from spec.md + captured wire data ONLY (no reference to any existing
implementation). See report.md for the compliance statement and gap list.

Pipeline:
  1. Parse every datagram header (long-header handshake vs short-header data).
  2. Skip F_INITIAL (top flag bit) datagrams as parse-only.
  3. Derive per-direction packet keys from sessionKey per the amended spec's
     "Packet protection (v0)" section, remove header protection, open the
     ChaCha20-Poly1305 AEAD.
  4. Parse frames; reassemble Msg fragments into application messages.
  5. Print a census and compare against ground-truth.json (PASS/FAIL + verdict).
"""
import json, os

from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms
from cryptography.hazmat.primitives.kdf.hkdf import HKDFExpand
from cryptography.hazmat.primitives.hashes import SHA256
from cryptography.hazmat.primitives.hmac import HMAC

HERE = os.path.dirname(os.path.abspath(__file__))

F_INITIAL = 0x80
PN_OFFSET = 5                 # flags(1) + shortConnId(4)
SAMPLE_OFF = PN_OFFSET + 4    # sample assumes max pnLen 4
HP_FLAG_MASK = 0x63           # pnLen bits (5-6) + key-phase bits (0-1)

FRAME_NAMES = {
    0x02: "Ack", 0x03: "Grant", 0x04: "Repair", 0x05: "PathChallenge",
    0x06: "Ping", 0x07: "PathResponse", 0x08: "Close", 0x09: "MaxData",
    0x0A: "AckFrequency", 0x80: "Msg", 0x81: "Padding",
}

# --------------------------------------------------------------------------
# Key schedule (amended spec, "Packet protection (v0)")
# HKDF = extract-then-expand, absent (zero) salt, ASCII label as expand info.
# --------------------------------------------------------------------------
def hkdf(key, label, length):
    prk = HMAC(b"\x00" * 32, SHA256())
    prk.update(key)
    return HKDFExpand(SHA256(), length, label.encode()).derive(prk.finalize())

def direction_keys(secret):
    return {
        "key": hkdf(secret, "tessera pkt key", 32),
        "iv":  hkdf(secret, "tessera pkt iv", 12),
        "hp":  hkdf(secret, "tessera hp", 32),
    }

def hp_mask(hp_key, sample):
    enc = Cipher(algorithms.ChaCha20(hp_key, sample[:16]), mode=None).encryptor()
    return enc.update(b"\x00" * 5)

# --------------------------------------------------------------------------
# QUIC-style truncated packet-number reconstruction (spec: "closest to
# largestSeen+1 among candidates congruent to the truncation").
# --------------------------------------------------------------------------
def reconstruct_pn(truncated, pnlen, largest_seen):
    if largest_seen < 0:
        return truncated
    win = 1 << (8 * pnlen)
    base = (largest_seen + 1) & ~(win - 1)
    cand = base | truncated
    best = cand
    for c in (cand - win, cand, cand + win):
        if c >= 0 and abs(c - (largest_seen + 1)) < abs(best - (largest_seen + 1)):
            best = c
    return best

def open_packet(raw, keys, largest):
    """Return (plaintext, pn, flags, pnlen) or raise on AEAD failure."""
    b = bytearray(raw)
    mask = hp_mask(keys["hp"], bytes(b[SAMPLE_OFF:SAMPLE_OFF + 16]))
    flags = b[0] ^ (mask[0] & HP_FLAG_MASK)
    pnlen = ((flags >> 5) & 0x03) + 1
    pn_bytes = bytearray(b[PN_OFFSET:PN_OFFSET + pnlen])
    for i in range(pnlen):
        pn_bytes[i] ^= mask[1 + i]
    pn = reconstruct_pn(int.from_bytes(pn_bytes, "big"), pnlen, largest)

    nonce = bytearray(keys["iv"])          # nonce = iv, low 8 bytes XOR pn (BE)
    pnb = pn.to_bytes(8, "big")
    for i in range(8):
        nonce[4 + i] ^= pnb[i]
    aad = bytes([flags]) + bytes(b[1:PN_OFFSET]) + bytes(pn_bytes)  # pre-HP header
    ct = bytes(b[PN_OFFSET + pnlen:])
    pt = ChaCha20Poly1305(keys["key"]).decrypt(bytes(nonce), ct, aad)
    return pt, pn, flags, pnlen

# --------------------------------------------------------------------------
# Frame / Msg decoding
# --------------------------------------------------------------------------
def read_varint(buf, i):
    prefix = buf[i] >> 6
    ln = 1 << prefix
    val = buf[i] & 0x3f
    for k in range(1, ln):
        val = (val << 8) | buf[i + k]
    return val, i + ln

def parse_msg_frame(pt):
    """
    Empirically decoded Msg frame (spec's compact Msg; the byte layout is not
    in the spec -- see gap R2-1):
        0x80 0x02 fragSeq(2, BE) flags(1) msgId(1) [offset(varint) if flags&0x04]
        data...(implied length = to end of packet; Msg is the sole/first frame)
      flags bits: 0x10 base, 0x02 = FIN, 0x04 = OFFSET present.
    """
    flags = pt[4]
    msg_id = pt[5]
    i = 6
    offset = None
    if flags & 0x04:
        offset, i = read_varint(pt, i)
    fin = bool(flags & 0x02)
    return msg_id, flags, offset, fin, pt[i:]

# --------------------------------------------------------------------------
def main():
    ds = [json.loads(l) for l in open(os.path.join(HERE, "datagrams.jsonl"))]
    meta = json.load(open(os.path.join(HERE, "meta.json")))
    gt = json.load(open(os.path.join(HERE, "ground-truth.json")))
    session_key = bytes.fromhex(meta["sessionKeyHex"])

    keys = {
        "c2s": direction_keys(hkdf(session_key, "tessera-v0.3 c2s", 32)),
        "s2c": direction_keys(hkdf(session_key, "tessera-v0.3 s2c", 32)),
    }

    census = {"c2s": 0, "s2c": 0}
    handshake = decrypted = failed = 0
    frame_counts = {}
    largest = {"c2s": -1, "s2c": -1}
    reasm = {"c2s": {}, "s2c": {}}

    for d in ds:
        census[d["dir"]] += 1
        raw = bytes.fromhex(d["hex"])
        if raw[0] & F_INITIAL:
            handshake += 1
            continue
        try:
            pt, pn, flags, pnlen = open_packet(raw, keys[d["dir"]], largest[d["dir"]])
        except Exception:
            failed += 1
            continue
        decrypted += 1
        if pn > largest[d["dir"]]:
            largest[d["dir"]] = pn

        # A data packet begins with a Msg frame (0x80 0x02); other packets carry
        # Ack/Grant/Repair/etc. We count the leading frame type for the census
        # and only reassemble from true Msg frames (never from Repair copies).
        ftype = pt[0] if pt else None
        if ftype == 0x80 and len(pt) >= 2 and pt[1] == 0x02:
            name = "Msg"
        else:
            name = FRAME_NAMES.get(ftype, f"0x{ftype:02x}" if ftype is not None else "empty")
        frame_counts[name] = frame_counts.get(name, 0) + 1

        if name == "Msg":
            mid, mflags, off, fin, data = parse_msg_frame(pt)
            R = reasm[d["dir"]].setdefault(mid, {"buf": bytearray(), "fin": False})
            o = off if off is not None else len(R["buf"])
            if o + len(data) > len(R["buf"]):
                R["buf"].extend(b"\x00" * (o + len(data) - len(R["buf"])))
            R["buf"][o:o + len(data)] = data
            if fin:
                R["fin"] = True

    # Direction -> ground-truth index. Client msgId 0 was the 0-RTT payload
    # (carried in the parse-only initial), so post-handshake c2s msgIds are
    # 1-based; the server sent no 0-RTT, so s2c msgIds are 0-based.
    base = {"c2s": 1, "s2c": 0}

    def recovered(dirk, count):
        out = []
        for idx in range(count):
            R = reasm[dirk].get(idx + base[dirk])
            out.append(bytes(R["buf"]).hex() if R else None)
        return out

    rec = {"c2s": recovered("c2s", len(gt["clientToServer"])),
           "s2c": recovered("s2c", len(gt["serverToClient"]))}

    # ---- census ----
    print("=" * 60)
    print("TESSERA CLEAN-ROOM DECODER -- CENSUS")
    print("=" * 60)
    print(f"datagrams: total={len(ds)}  c2s={census['c2s']}  s2c={census['s2c']}")
    print(f"handshake (F_INITIAL, parse-only): {handshake}")
    print(f"packets decrypted: {decrypted}   failed: {failed}")
    print("leading-frame counts by type:")
    for k, v in sorted(frame_counts.items()):
        print(f"    {k}: {v}")
    print("\nRecovered application messages (in order, hex):")
    for dirk in ("c2s", "s2c"):
        print(f"  {dirk}: {sum(x is not None for x in rec[dirk])} message(s)")
        for h in rec[dirk]:
            shown = h if h and len(h) <= 64 else (h[:64] + "..." if h else "(missing)")
            print("    " + shown)

    # ---- verdict ----
    print("\n" + "=" * 60)
    print("VERDICT vs ground-truth.json")
    print("=" * 60)
    total = passed = 0
    for dirk, truth in (("c2s", gt["clientToServer"]), ("s2c", gt["serverToClient"])):
        for idx, exp in enumerate(truth):
            total += 1
            got = rec[dirk][idx]
            ok = (got == exp)
            passed += ok
            if not ok:
                print(f"  {dirk}[{idx:02d}] FAIL "
                      f"(expected {len(exp)//2}B, got "
                      f"{'-' if got is None else str(len(got)//2)+'B'})")
    print(f"\n  {passed}/{total} messages byte-exact")
    print(f"  OVERALL: {'PASS' if passed == total else 'FAIL'}")


if __name__ == "__main__":
    main()
