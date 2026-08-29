# Tessera v0 clean-room decoder — report

## (a) Compliance statement

Files read (all inside the cleanroom directory, plus files I created there):
- cleanroom/spec.md
- cleanroom/meta.json
- cleanroom/datagrams.jsonl
- cleanroom/ground-truth.json
- my own scratch scripts (brute*.py) and decoder.py in cleanroom/

I did NOT read any file outside the cleanroom directory, nothing under any
"tessera" path, and did NOT use WebSearch/WebFetch or look at any existing
Tessera implementation. I complied with the clean-room rule. The one installed
primitive (`cryptography`, in cleanroom/venv) is a standard AEAD/HKDF library,
not the protocol — permitted by the task.

## (b) What works, and the census/verdict

Verified against the capture:
- Census / direction split: 214 datagrams (109 c2s, 105 s2c).
- Header parsing. Short header confirmed empirically as
  flags(1) | connId(4) | truncatedPN(1-4). The 4-byte connId is constant per
  direction (c2s=cdfc84c9, s2c=cdfc84c8); the bytes after it vary uniformly,
  consistent with an encrypted (header-protected) packet number. The two
  handshake datagrams are long headers
  flags(1) | connId(8=390dfc034b8f4337) | pathId(1=00) | pn(4=00000000).
- Handshake skip: both F_INITIAL datagrams (0x80 client initial, 0xc0 server
  reply — reply has F_INITIAL set) classified parse-only and skipped.
- AEAD + header protection implemented per RFC 9001 §5.4 shape (ChaCha20 HP,
  16-byte sample at pnOffset+4, nonce=iv XOR padded pn, AAD=unprotected header).
  A self-constructed seal/open round-trip passes, proving the open code correct.
- Frame parser + Msg reassembler implemented for the spec's frame catalog.

Census / verdict from `python decoder.py`:
    datagrams: total=214  c2s=109  s2c=105
    handshake (F_INITIAL, parse-only): 2
    packets decrypted: 0   failed: 212
    frame counts: (none)
    VERDICT: 0/40 messages byte-exact — OVERALL FAIL

Outcome: 0 of 40 messages recovered — blocked by an underspecified key schedule
(gap 1). Every stage except the actual key derivation is exercised and correct.

### Why the blocker is real, not a coding error
The AEAD/HP harness is proven correct by self-test. Against the capture I ran an
exhaustive search that, per candidate, tries pn-lengths 1-4, all plausible HP
flag masks, both header offsets, and checks the Poly1305 tag over EVERY short
packet. None opened a single packet. Space searched:
- PRK: session key direct; HKDF-Extract (zero/connId/self salt).
- Shape: one-level; two-level (per-dir secret -> key/iv/hp); 152-byte block
  split across directions (several orders).
- HKDF info: RFC 8446 Expand-Label (empty/connId/direction context) and raw
  ASCII info.
- Label prefixes: "tls13 ", "tessera ", "quic ", "noise ", "".
- Direction tokens: client/server, c2s/s2c, client in/server in, c/s, 0/1, tx,
  write server, to client, ... with separator variants.
- key/iv/hp suffixes: quic key|iv|hp, key|iv|hp, tessera key|iv|hp, key|nonce|hp,
  key|iv|pn, plus SHA-256-based derivations.
- Noise split() transport keys (both halves), as direct ChaCha key (counter
  nonce LE/BE) and as a secret feeding key/iv/hp.
- Nonce: iv XOR pn, counter-only BE, counter-only LE, pn-in-last-4.
- Header protection on/off; pn offsets 5 and 6.
Thousands of combinations; the exact scheme is not among reasonable guesses and
the spec does not narrow it. That is the finding.

## (c) THE SPEC GAP LIST

1. Packet-protection key schedule completely unspecified (THE BLOCKER).
   spec.md says "derive packet-protection keys from the session key (HKDF
   labels, per-direction derivation, header-protection key)" and cites
   "RFC 9001 §5.4" + "HKDF-SHA256", but never states the labels, the
   per-direction derivation, or the HP construction. The only concrete HKDF
   labels anywhere are "tessera stateless-reset" and the key-update chain —
   neither is the packet key. And since the project forbids QUIC lineage, one
   cannot assume QUIC's "tls13 "+"quic key/iv/hp" labels (empirically they do
   not work). Fix — one sentence giving the two-level structure and the six
   literal label strings + prefix + client/server direction labels.

2. Header-protection sample offset and masked bits not given. Assumed RFC 9001
   exactly (offset pnOffset+4, 16-byte sample, low-5 flag bits masked). Fix:
   "HP follows RFC 9001 §5.4.4 verbatim."

3. AEAD nonce construction not stated. ChaCha20-Poly1305 named; "iv xor pn"
   appears once but padding/endianness never pinned. Assumed RFC 9001 rule.
   Fix: "nonce = 12-byte iv XOR pn (big-endian, right-aligned, left-padded)."

4. AAD extent not stated. Assumed the unprotected header (RFC rule). A subtle
   trap: feeding the still-HP-protected pn bytes as AAD silently fails the tag.

5. Short-header layout must be reconstructed from three scattered notes, and
   whether a pathId byte is present is genuinely ambiguous ("pathId in clear"
   in the crypto section vs the v0.2 short-header note). The capture shows NO
   pathId in the short header. Fix: "short header = flags(1)|connId(4)|pn(2-4),
   no pathId; path 0 implied."

6. Packet-number truncation/reconstruction window not specified for an observer
   (not reached here, but needed for a full decode).

7. tagLen source: negotiated ConnParam; here it comes from meta.json (16), not
   from anything a passive observer can parse on the wire.

## (d) Capture vs spec contradictions

- No pathId in the short header (gap 5): the top-level "Packet" layout and the
  "pathId in clear" crypto note imply a pathId byte, but the captured
  short-header packets place the protected packet number immediately after the
  4-byte connId. This matches the v0.2 short-header note and contradicts the
  general Packet layout. No other contradiction was reachable, since decryption
  is blocked.
