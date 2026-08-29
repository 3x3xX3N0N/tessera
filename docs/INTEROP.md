# Interop plan

The claim under test is not "Tessera works" — 279 tests say that. It is: **the protocol exists independently of
this codebase.** Every Tessera packet ever sent was parsed by Tessera; an encoder and decoder wrong in the same
way are invisible, and a spec that diverges from the code fails nothing. Interop is the only test that catches
that class, and *failure at any rung is a finding, not a setback* — "the spec cannot be implemented from the
document" is exactly the kind of result this project records.

## The contamination rule, first

A clean-room implementer's only inputs are `docs/SPEC.md` and the published vectors. Anyone who has read the
implementation is contaminated — that includes every contributor to date and the agent that wrote this plan.
Three ways to get an uncontaminated implementer, in increasing strength:

1. **A context-isolated agent** given only the spec text and vectors in its prompt, instructed to read nothing
   else — and *audited afterwards from its transcript*, which records every file it opened. Weak wall,
   verifiable audit. Cheap enough to run this week.
2. **A person who has never seen the repo**, given the same two inputs.
3. Eventually: a genuinely independent third-party implementation, which is what TLS/QUIC have.

Spec gaps found by the implementer are fixed **in SPEC.md, never by hinting** — a hint contaminates the run and
the fix would rot. Each gap gets a ledger entry here.

## The ladder

### L0 — Publish the vectors (hours; NOT clean-room — done by this codebase, for the other side)

`WireVectorsTest` already pins golden bytes for every frame, header and varint. Export them to
`interop/vectors/` as JSON, plus the one thing unit vectors cannot give: **a full captured session** — every
datagram of a loopback connect-send-close run with fixed inputs, alongside the negotiated secrets (handshake
keys, per-generation packet secrets), so a passive decoder can decrypt without re-deriving the key exchange.
Deliverable: `vectors/frames.json`, `vectors/session-1/{datagrams.bin,secrets.json,ground-truth.json}` where
ground-truth lists what the decoder should recover (messages, fec seqs, frame counts).

### L1 — Passive decoder, clean-room (days)

The implementer writes a decoder in a language of their choice, from SPEC.md + L0 vectors only:
varints -> headers -> header-protection removal -> AEAD open (standard ChaCha20-Poly1305 libraries) -> frame
parse -> message reassembly. **Success:** byte-exact recovery of every application message in the captured
session and a frame census matching ground-truth. **What it proves:** the wire format and crypto layout
sections of SPEC are complete and correct. This is the highest information-per-effort rung.

### L2 — Active responder, clean-room (about a week)

A minimal second implementation that a real `TesseraClient` can connect to: Noise IK responder + ML-KEM
(standard libraries; FIPS 203 finalised), params TLV, and enough of the data plane to receive one message and
echo it. No RLNC encoder needed — a responder may send repairs never and sources verbatim; SPEC must say
whether that is conformant (if it does not, that is finding #1). **Success:** cross-implementation handshake
and one echoed message. **What it proves:** the handshake and negotiation sections — the security-critical
prose — are implementable from the document.

### L3 — Sustained interop (later)

The L2 responder behind `NetemSim`/tc profiles: loss recovery against a peer that ARQs but never RLNCs,
close/linger semantics, key update follow. This is where "acked is not delivered" class bugs surface
cross-implementation. Not scheduled until L2 exists.

## Governance

- Vectors are frozen per wire version; a vector change is a wire change and follows the WireVectorsTest rule
  (deliberate, SPEC-noted, never "fixed to green").
- The implementer's questions are answered ONLY by editing SPEC.md and re-issuing it. The diff of SPEC over
  the course of L1+L2 *is* the measurement of how incomplete the document was.
- Contamination audit: for an agent implementer, the transcript is reviewed for any repo read; for a human,
  it is an honour declaration. Either is recorded with the result.

## What settles TODO item 7's interop half

L1 passing settles "the wire format is real beyond this repo". L2 passing settles "the protocol is
implementable from the document". The audit and formal-analysis halves of item 7 are unaffected — interop is
evidence of implementability, not of security.

## Ledger

- **2026-08-29, L1 round 1: 0/40 — blocked, correctly.** The spec lacked the entire packet-protection key
  schedule, and its Packet section described only the long header. Fixed in SPEC ("Packet protection (v0)" is
  new; Packet corrected). Report: `interop/reports/L1-round1-report.md`.
- **2026-08-29, L1 round 2: 40/40 byte-exact — L1 PASSED.** 212/212 non-handshake packets decrypted, zero
  failures, on the amended spec's first try. New gaps found on the way out: the wire message encoding (FEC seq
  frame + compact message frame) was absent from the frame catalog, which listed only the canonical `0x01`
  form the transport never sends; msgId origin per direction was undocumented. Fixed in SPEC. Report:
  `interop/reports/L1-round2-report.md`; the passing decoder: `interop/cleanroom-decoder/decoder.py`.
- **Clean-room grade:** rung-1 (context-isolated agent; wall is instruction plus attested compliance, both
  rounds attested clean). A hard-sandboxed or human run remains the stronger form.
- **Remaining before L2:** control-frame byte layouts (Ack/Grant/Repair/...) are still unspecified (round-2
  gap R2-5) — not needed for passive decoding, required for an active responder.
