# Audit plan

How Tessera gets from "unaudited, says so in the README" to reviewed — phased, because audit hours are the
most expensive hours this project will ever buy, and every unprepared hour is wasted. Phases 0-2 are free and
gate the paid ones.

## Phase 0 — threat model (days, free, first)

One page an adversary would read first. Assets: payload confidentiality and integrity, connection identity,
peer address privacy. Adversaries: passive recorder (including future-quantum — the reason for ML-KEM), on-path
active, off-path spoofer, malicious peer. Explicitly out of scope: key distribution (IK assumes the server's
static key is already held — there is no PKI story), DoS beyond the amplification bound, traffic analysis of
sizes and timing. Fragments of this exist across SPEC.md; the deliverable is one document. Writing it finds
bugs by itself.

## Phase 1 — formal handshake delta (weeks, nearly free)

The handshake is Noise IK, which already has machine-checked proofs (Noise Explorer, Kobeissi/Bhargavan). The
job is therefore NOT to prove the handshake — it is to document every deviation from the proven model and show
each one either maps onto it or stands as an open question:
- ML-KEM hybridization into the key schedule (FIPS 203 + X25519)
- 0-RTT resumption tickets and the replay window (core/Resumption.kt, core/ZeroRtt.kt)
- the key-rotation chain (secret_{n+1} = HKDF(secret_n); KeyPhaseState)
- stateless-reset tokens (keyed HMAC over connId)
Ambitious version: a ProVerif/Tamarin model of the resumption + 0-RTT layer specifically.

## Phase 2 — wire-format freeze (gates all paid work)

Auditing a v0 format that will change is burning money. The independent-decoder interop item (TODO 7) is the
forcing function: spec freeze and second implementation are the same milestone.

## Phase 3 — paid cryptographic design review

Design, not code: Trail of Bits / NCC Crypto Services / Cure53 / Kudelski class. Realistic scope after the prep
above: 1-2 weeks, ~$20-50k. They receive: threat model, frozen spec, Phase 1 delta document, the
known-weaknesses ledger (tagLen=8 truncation, the +-10 s replay window, no PKI — all already documented), NOTICE
provenance, the fuzz suites. The prep is why 1-2 weeks suffices.

## Phase 4 — implementation audit + funded fuzzing (only if heading to users)

Attack surface ranking: FrameCodec and packet parsers (fuzz findings already recorded in-code), handshake
decode, the native sockaddr path (fuzzed, 250k cases), replay windows under clock skew.

## Budget-realistic alternatives

- OSTIF funds OSS audits, but wants adoption evidence first.
- Academic collaboration: formalizing the resumption layer is a thesis-shaped problem.
- Publishing the spec with an explicit break-this invitation costs nothing.
- A bug bounty is premature: bounties reward finding bugs in things people use.
