# Threat model

Phase 0 of `docs/AUDIT-PLAN.md`. One page, adversary first. Tessera is a single-implementation research
prototype, wire format v0, unaudited; nothing below has been independently reviewed. Claims about mechanisms
are made against the code in `core/src/main/kotlin/tessera/core/`, not the prose docs; where the two disagree,
the code is cited and the disagreement noted. One deliberate deviation from AUDIT-PLAN itself: Phase 0's asset
list names "peer address privacy", which this document reclassifies as explicitly *not* protected (the connId
is cleartext by design, per `PacketCrypto.kt`) — this document supersedes AUDIT-PLAN's list on that point.

## 1. Assets protected

- **Payload confidentiality**, including against a recorder who stores traffic today and decrypts with a
  quantum computer later. The handshake secret is hybrid X25519 + ML-KEM-768; breaking confidentiality of a
  recorded session requires breaking both. This bound holds for *fresh* connects only — the resumed path is
  strictly weaker (see the ticket key below and Section 4).
- **Payload and header integrity.** Every post-handshake packet is ChaCha20-Poly1305 AEAD with the header as
  AAD; an on-path modification fails authentication and the packet is dropped.
- **Connection identity — with a caveat.** Packets are bound to a connection by keys, not by address; nobody
  without the connection's keys can speak on it. Termination is weaker than that: a stateless-reset token is
  a deterministic function of the reusable 4-byte connId under a long-lived secret, not of the connection
  (see the reset residue in Section 4).
- **Bounded reflection.** A spoofed source cannot use the server as a >3x amplifier, and cannot (beyond fixed
  rate budgets) use it as a CPU oracle for ML-KEM decapsulations (`AddressValidation.kt`). The KEM CPU bound
  is carried by the **global** bucket (`globalKemPerSec = 200.0`); the per-source bucket is best-effort only
  against spoofed sources, because `AddressValidator.takeSource` resets a slot to full burst whenever its
  fingerprint changes (`fp[i] != h`, `AddressValidation.kt` line 243) — an attacker rotating spoofed
  addresses both evades per-source limiting and continually resets the budget of any honest source sharing
  the slot. Per-source limiting constrains only a non-spoofing attacker; this is by design, per the in-code
  comment. **The KEM buckets bound KEM cost only.** Resumed (`F_RESUME`) initials do no KEM and take a
  different admission path entirely: `TesseraServer.onInitial` routes them through
  `validator.onCheapInitial` (`Endpoints.kt` line ~298), which applies **only the per-source, unvalidated
  bucket — the global bucket, the pressure regime, and Retry never apply to this path**. Since the per-source
  bucket is evadable by rotating spoofed sources (above), a spoofed-source F_RESUME flood buys an effectively
  unbounded rate of ticket AEAD opens (`Resumption.Server.accept` parsing plus one ChaCha20-Poly1305 open per
  packet, plus pressure-counter inflation on the failure path). The per-packet cost is small and symmetric —
  no asymmetric crypto — but no stated global bound exists for it; this is an accepted gap, recorded in
  Section 4.

**The ticket key is a concentrated-risk asset.** The single operator-provided ticket key is the root secret
for three separate mechanisms (`Endpoints.kt` lines 243, 248, 255): ticket encryption
(`Resumption.Server`), the retry-token secret (`RetryToken.deriveSecret`), and the stateless-reset secret
(`StatelessReset.deriveSecret`). Compromise of this one key yields, simultaneously: (1) **full session keys
of every recorded resumed session within the ticket lifetime** — the ticket is enc(resumptionSecret) under
the ticket key, the resumed session key is `HKDF(resumptionSecret, ts‖nonce)` (`Resumption.sessionKey`),
and ts and nonce ride in cleartext in the initial's prefix, with no fresh DH or KEM on resumption — both
directions, all data, plus impersonation of either endpoint of any resumed connection; (2) **forgeable
stateless-reset tokens for every connId** — the connId is cleartext, so this is a one-packet off-path kill
of any current or future connection; (3) **address-validation bypass**, defeating the KEM CPU admission
control. Rotation is all-or-nothing: rotating the key invalidates outstanding tickets, reset tokens, and
retry tokens at once. Storage and rotation of this key are the operator's problem and are not further
specified for v0 — an auditor should start here.

**Not protected:**

- **Initiator identity — the initiator is cryptographically anonymous.** `Handshake.initiate` sends only a
  fresh ephemeral X25519 key and a KEM ciphertext; there is no initiator static key anywhere in the exchange,
  so the server authenticates nobody. *Anyone holding the server's published static keys can connect.* The
  only client-side gates are address validation (proof of address, not identity) and the optional `--token`
  shared secret, which rides *inside* the 0-RTT AEAD (`AddressValidation.kt` doc comment) — a bearer secret,
  not a cryptographic identity. Applications that need to know who connected must authenticate above this
  layer. This is a deliberate non-goal for v0, recorded here so an auditor does not have to ask.
- **Traffic analysis.** Packet sizes, timing, rates, and direction are visible. No padding beyond the minimum
  needed for the header-protection sample; no cover traffic.
- **Connection linkability at the network layer.** The 4-byte short connection id rides in the clear on every
  short packet (deliberately, per `PacketCrypto.kt`: header protection masks only the packet number and four
  flag bits — `SHORT_FLAGS_MASK = 0x63`, the pnLen bits 5-6 and key-phase bits 0-1; the pathId bits, form
  bit, and connId stay readable for load balancers). An observer can trivially track a connection across its
  lifetime, and across a client rebind if the connId is kept. (`StatelessReset.kt` carries two stale
  comments an auditor should not trust: line 23 says header protection masks "two flag bits" — the code's
  0x63 mask is authoritative — and line 15 says the idle timeout is "10 s", against a code default of 60 s,
  see Section 2. Both comments should be fixed.)
- **Peer anonymity.** IP addresses are not hidden; that is not this layer's job. (This supersedes
  AUDIT-PLAN Phase 0's "peer address privacy" asset, per the preamble.)
- **Full forward secrecy of the first flight.** The session key is DH(client-ephemeral, server-static) plus
  KEM against the server-static ML-KEM key (`Handshake.kt`). The exchange has *neither* an initiator static
  *nor* a server ephemeral — the code is "Noise-IK-shaped" in its one-flight, pre-shared-responder-key
  premise, but on the initiator side it is NK-shaped, a larger delta from the proven IK model than SPEC's
  shorthand suggests, and one that Phase 1's "IK proofs mostly carry over" premise must account for. An
  adversary who later obtains **both** server static private keys and recorded the traffic recovers the
  session key. Key rotation (`secret_{n+1} = HKDF(secret_n)`) chains forward from that same secret and does
  not repair this. Worse than the passive risk: static-key compromise also enables *active* impersonation
  indefinitely — see Section 4.
- **Forward secrecy of resumed sessions — none at all.** Strictly worse than the fresh-connect bound above:
  recovering a recorded *resumed* session requires only the ticket key, not both server static private keys
  (see the ticket-key asset above and Section 4).

## 2. Adversaries

**Passive recorder (incl. harvest-now-decrypt-later).** Records everything, forever. Stopped by the hybrid
key exchange: recovering plaintext requires the X25519 shared secret *and* the ML-KEM-768 shared secret
(`Handshake.kt` concatenates both into HKDF). A future quantum attacker breaks X25519 but not ML-KEM; a
classical attacker with a lattice break gets the KEM but not X25519. Caveats: a recorder who later steals the
server's static keys wins on fresh connects (forward-secrecy note above, and active impersonation in
Section 4); a recorder who later steals the **ticket key** recovers the **complete session key of every
recorded resumed session** within the ticket lifetime — `Resumption.sessionKey = HKDF(resumptionSecret,
ts‖nonce)` with the resumptionSecret decryptable from the ticket and ts/nonce cleartext in the prefix, no
fresh DH or KEM anywhere on the resumed path. That is both directions, all data, and impersonation of either
endpoint of resumed connections — not merely the 0-RTT first flight.

**On-path active attacker.** Can drop, delay, reorder, modify, inject. Modification and injection fail the
AEAD. Per-attempt forgery at the full 16-byte tag is bounded by Poly1305's polynomial term, roughly
8·⌈L/16⌉/2^106 — about 2^-97 for a ~1.3 KB packet (⌈1300/16⌉ = 82; 8·82/2^106 ≈ 2^-96.6; RFC 8439 /
Bernstein's bound), not the ideal-MAC 2^-128. At `tagLen = 8` the binding bound is tag-guessing, ~2^-64 per
attempt (the polynomial term is unchanged; the truncation dominates). Flipping the key-phase bit to provoke a
spurious rotation is specifically handled: `KeyPhaseState.open` follows an update only after a packet
authenticates under the pre-derived next-generation keys. Dropping and delaying are availability attacks and
are not prevented — an on-path attacker can always kill a connection.

**Off-path spoofer.** Sends packets with forged sources. Cannot forge data packets (no keys), cannot forge a
stateless reset against a live connection whose token it never saw (the token is delivered inside the
encrypted handshake reply and is an HMAC under a server-held secret, `StatelessReset.kt` — but see the
connId-reuse residue in Section 4), cannot amplify (3x bound before path validation, `PathValidation`; a
Retry packet at 31 B against a >=1.2 KB initial is a deamplifier), and cannot burn KEM CPU past the
**global** 200/s bucket (`AddressValidation.kt`) — the per-source bucket does not hold against spoofed
sources, per Section 1, so the global bucket alone carries this claim; under pressure an unvalidated source
gets a Retry token it never receives at a spoofed address. It *can* drive unbounded symmetric-crypto work
through spoofed F_RESUME initials (one ticket AEAD open per packet, Section 1) — the accepted gap in
Section 4. Known residue, documented in SPEC "Address validation": the Retry itself is unauthenticated, so
an off-path guesser can inject one; the client accepts at most one per connect, from the addressed server
only, and its retransmit train continues — cost is one extra initial, not a failed connect.

**Malicious peer.** Holds valid keys — which, per Section 1, means *anyone* who holds the server's public
keys, since the initiator is unauthenticated; the crypto does not constrain it. It can lie in every frame:
ACK inflation, credit/grant manipulation, ECN lies, bogus PMTUD, junk frames. Defences are parsers hardened
by fuzzing (findings recorded in-code, e.g. the empty-packet check in `PacketCrypto.checkShort`), bounded
decoder state, and rate/credit sanity limits — but a peer-driven resource-exhaustion review is exactly what
an audit is for. Assume a malicious peer can waste this endpoint's memory and CPU up to the audited bounds
and no confidentiality beyond its own connection. One further residue: the server migrates a connection's
path on any authenticated non-probing packet from a new source address (`Connection.migrate` →
`PathValidation.onMigration`), so a key-holding peer can spoof its own source to a victim's address and
redirect the server's traffic for that connection at the victim — bounded to 3x the peer's own sent bytes by
the post-migration reset (`validated = false`, fresh budget, challenge issued), but refillable by continuing
to send. This is the standard RFC 9000 migration-reflection residue and is accepted for v0.

**Restarted / keyless server.** A server that crashed and lost all connection state can still emit a valid
stateless reset: the reset secret derives from the operator-provided ticket key, which survives restarts, and
the token is HMAC(secret, shortConnId) where the connId is readable on the client's retransmits
(`StatelessReset.kt`). Reset emission is itself bounded (min-size packet requirement plus a 2000/s global
bucket, SPEC "stateless reset") so the mechanism is not a reflector. The failure mode without it — client
retransmits into a black hole until the idle timeout, **default 60 s** (`ConnConfig.idleTimeoutMs = 60_000`,
`transport/Connection.kt`; the 3 s ping / 10 s timeout pairing is only the documented wired-peer alternative,
and `StatelessReset.kt` line 15's "10 s" comment is stale against the code, per Section 1) — is
availability, not secrecy. **A restart also re-opens the 0-RTT replay window**: the seen-sets are per-process
`HashMap`s (`ZeroRtt.Server.seen`, `Resumption.Server.seen`) and are emptied by a restart — and never shared
across multiple server instances — so an attacker who recorded a 0-RTT or resumed initial can replay it
against the restarted (or a sibling) process for as long as its timestamp stays inside the ±10 s window.
App-layer idempotence is the only defence across a restart or between instances (Section 4).

## 3. Attack surface and out-of-scope

**The Rust native datapath is in scope for implementation audit.** The `native` module ingests
attacker-controlled input before any authentication — batched UDP receive and sockaddr parsing in
`native/rust/src/udp/mod.rs`, bound through Panama FFM — and it is the one component where memory-safety
bugs would live. AUDIT-PLAN Phase 4 ranks the native sockaddr path as attack surface, with a 250k-case fuzz
corpus already run against it and the receive loop noted in-code as "the largest remaining hole" named by the
project's own fuzzing work. Memory safety of that ingest path belongs to Phase 4; this document does not
analyze it beyond recording that it exists and is not covered by any Kotlin-side argument above.

**Client-side rx path.** Most of this document is written from the server's seat; the client also parses
under-authenticated input: unmatched short packets (stateless-reset recognition, `Endpoints.kt`
`onUnmatchedShort`), handshake replies, and — after a NAT rebind — packets arriving on a fresh socket. Per
the code, an off-path attacker spraying reset-shaped packets fails the token match and the packets drop —
but the mechanism is a **linear scan, not a single-token check**: any unmatched short packet of at least
16 B (`StatelessReset.TOKEN_LEN`; the client applies no larger minimum, unlike the server's
`RESET_PACKET_LEN` gate) has its 16-byte trailer compared, constant-time per comparison
(`StatelessReset.matches`), against *every* live connection's `peerResetToken` on that socket
(`TesseraClient.onUnmatchedShort`, `Endpoints.kt` lines ~436-444). Two consequences an auditor should note:
per-packet check cost scales with the number of live connections, and a token learned for any one connection
kills that connection with a single spoofed packet — no knowledge of its connId required, since the demux
miss is what routes the packet here. Spoofed handshake replies fail the AEAD under the session key and drop.
The Retry-injection residue (Section 2) is the one client-side pre-auth acceptance. All of these client
parsers are in scope for the Phase 4 parser audit.

Out of scope:

- **Key distribution.** Noise premise: the client already holds the server's static X25519 and ML-KEM
  public keys, obtained out of band (pinning, TOFU, delegated credential). There is no PKI, no certificates,
  no in-band identity — in either direction (the server has no identity story beyond its pinned statics, and
  the client has none at all, Section 1). A client that accepts the wrong static key has lost before the
  first packet. How a *compromised* static key is retired is likewise out of band — and there is no
  mechanism for it at all; see Section 4.
- **DoS beyond the stated bounds.** The 3x amplification bound, the KEM admission budgets, and the reset/Retry
  rate caps are the DoS bounds this document claims — and they are **not** the whole story: the resumed-initial
  path has no global bound (Sections 1 and 4). Volumetric flooding of the link, state exhaustion by a peer
  with valid keys past audited limits, and everything an on-path attacker can do by dropping are out of scope.
- **Side channels beyond constant-time primitives.** Tag and token comparisons are constant-time
  (`Arrays.constantTimeAreEqual`, the local `eq` in `AddressValidation.kt`); the crypto is BouncyCastle's.
  Cache-timing of the JVM, the Rust SIMD datapath, and ML-KEM implementation side channels are not analyzed.
- **Traffic analysis** of sizes, timing, and connection linkage (Section 1).

## 4. Known accepted weaknesses

- **`tagLen = 8` truncated-tag mode.** Negotiated in `ConnParams` (`Params.kt`): forgery ~2^-64 per attempt,
  documented in-code as acceptable for media/game state, **not for transactions**. Truncation happens on the
  wire only; the keystream and confidentiality argument are unchanged (SPEC v0.9). The truncated-tag open
  path recomputes the full Poly1305 tag and compares a prefix in constant time (`PacketCrypto.openTruncated`).
  The negotiation itself is not downgradable by an attacker without keys: the client's `ConnParams` offer
  travels inside the AEAD-protected 0-RTT body (`Endpoints.kt`, server accept path reads the offer from the
  decrypted body) and the server's confirmation inside the encrypted handshake reply
  (`Connection.buildHandshakeReply`), so every parameter flight is authenticated end to end.
- **Static-key compromise has no recovery story.** Theft of the server's static X25519 + ML-KEM private keys
  is not only the passive-decryption risk of Section 1: it enables **full active impersonation of the server
  — a MITM of every future fresh connect — indefinitely**. There is no server ephemeral, no certificate, no
  revocation, and no in-band rotation mechanism for the static keys in v0 (`Handshake.kt`); the only recovery
  is out-of-band redistribution of new pinned keys to every client. Accepted as a consequence of the
  pinned-static Noise premise, but an auditor should treat static-key custody as first-order alongside the
  ticket key.
- **The resumed-initial admission path has no global bound.** Restating Section 1 so it appears in this
  ledger: F_RESUME initials skip the global KEM bucket, the pressure regime, and Retry entirely
  (`onCheapInitial`, `Endpoints.kt` line ~298), and the per-source bucket that does apply is evadable by
  rotating spoofed sources. Cost to the server is one ticket AEAD open plus `Resumption.accept` parsing per
  packet — symmetric crypto only, no KEM — at whatever packet rate the attacker's link sustains, plus
  inflation of the pressure counter via `onFailure`. Accepted for v0 on the argument that the per-packet
  cost is comparable to ordinary AEAD-verify work on any flood; unbounded and unmeasured, and a natural
  audit target.
- **No forward secrecy on the resumed path; ticket-key compromise is total for resumed sessions.** There is
  no fresh DH or KEM on resumption: the resumed session key is `HKDF(resumptionSecret, ts‖nonce)`
  (`Resumption.sessionKey`) with ts and nonce cleartext in the initial's prefix and the resumptionSecret
  encrypted in the ticket under the ticket key. A recorder who later obtains the ticket key recovers every
  recorded resumed session in full and can impersonate either endpoint of resumed connections — a strictly
  worse bound than fresh connects, which require both server static private keys. Accepted for v0 as the
  TLS-1.3-PSK-shaped trade-off, but the blast radius is the whole session, not the first flight.
- **Tickets are unbound multi-use bearer credentials.** `Resumption.Server.accept` dedupes on
  `fp = ticketNonce xor clientNonce`, so one ticket resumes arbitrarily many times under fresh client
  nonces. A ticket stolen from a client's storage is therefore a 7-day impersonation credential: no client
  binding, no revocation mechanism, valid until lifetime expiry.
- **Session-key compromise chains forward through resumption.** `resumptionSecret =
  HKDF(sessionKey, "tessera-resume")` (`Resumption.kt` line 23): compromising one live session's key
  transitively yields the resumption secret of the ticket issued on it, and hence every future session
  resumed from that ticket. A session-key compromise is not contained to that session.
- **±10 s 0-RTT replay window with a seen-set — per-process only.** `ZeroRtt.Server` and `Resumption.Server`
  reject timestamps outside ±10 s and any fingerprint already seen inside the window. The fingerprint is
  8 bytes (first 8 of the random ePub; ticket-nonce xor client-nonce for resumption) — collision, not
  forgery, resistance; the seen-set is pruned only above 100k entries. The seen-set is an in-process
  `HashMap`: **replay defence does not survive a server restart within the window and does not extend across
  multiple server instances** — a recorded initial replays cleanly against a restarted or sibling process
  until its timestamp ages out. Within-window replay from a clock-skewed pair, the restart/multi-instance
  hole, and the fact that 0-RTT data must be idempotent at the application layer regardless, are accepted;
  app-layer idempotence is the only defence in the restart and multi-instance cases. A resumed connection
  additionally binds ts+nonce into the session key so a replayed ticket under a fresh nonce yields a distinct
  key (`Resumption.sessionKey`).
- **Stateless-reset tokens are per-connId, not per-connection.** The token is HMAC(secret, shortConnId)
  under a deliberately restart-stable secret (derived from the ticket key), and the 4-byte connId comes from
  a sequential counter seeded from a random start that re-randomizes on restart (`Endpoints.kt`,
  `newShortId`); uniqueness is enforced only against currently-live ids in one process. So a former
  legitimate client — or anyone who ever learned a token — holds a credential valid for *any future
  connection* that is assigned the same shortConnId after a restart, and can kill it with one packet. Impact
  is availability only (a reset carries no keys and reads nothing), the id space is 2^32, and rotating the
  ticket key rotates the secret; accepted for v0, but the Section 1 "connection identity" asset is
  correspondingly weaker than "only a key holder can terminate".
- **One root key for three mechanisms.** Restating the Section 1 ticket-key asset as an accepted concentration
  of risk: ticket encryption, retry tokens, and stateless-reset tokens all derive from the single
  operator-held ticket key, so its compromise simultaneously decrypts recorded resumed sessions, enables
  one-packet off-path kills of any connection, and bypasses KEM admission control — and rotation invalidates
  all three credential families at once.
- **Migration-based traffic redirection.** A key-holding peer can point up to 3x of its own send rate of
  server traffic at a spoofed victim address via path migration (Section 2); accepted as the RFC 9000
  residue, bounded by `PathValidation`'s post-migration budget reset.
- **Unauthenticated initiator.** Restating Section 1 as an accepted weakness so it appears in this ledger:
  the server cannot distinguish clients cryptographically; admission control and the optional in-AEAD
  `--token` bearer secret are the only gates.
- **Single implementation, no interop evidence.** The v0 wire format has never been parsed by an independent
  decoder (AUDIT-PLAN Phase 2 / TODO item 7). Every compatibility claim is self-referential.
- **No audit.** No external cryptographic or implementation review has occurred. The handshake deviates from
  proven Noise IK (no initiator static — NK-shaped on that side, Section 1 — plus hybrid KEM injection, no
  server ephemeral, and the 0-RTT layer) and those deltas are undocumented formally — that is Phase 1, and
  the missing initiator static in particular means the IK proofs do not carry over unexamined.

## 5. Mechanism inventory

| Mechanism | Where | Notes |
|---|---|---|
| Hybrid handshake: X25519 + ML-KEM-768 into HKDF-SHA256 | `core/Handshake.kt` | Client ephemeral only — no initiator static, no server ephemeral; server static keys pinned out of band, no revocation or in-band rotation (Section 4) |
| Packet AEAD (ChaCha20-Poly1305), header protection, truncated-tag open | `core/PacketCrypto.kt` (`PacketKeys`, `PacketProtection`) | RFC 9001 §5.4-shape HP; flags mask 0x63 (pnLen + key-phase bits) leaves connId/pathId in clear by design |
| Key update via phase bit; one retained previous generation; follow only after next-key auth | `core/PacketCrypto.kt` (`KeyPhaseState`) | HP key fixed at generation 0 for the connection's lifetime |
| Automatic rotation policy: 2^20 packets or 1 GiB sealed per tx generation | SPEC v0.9; trigger in `transport` `transmit`, machinery `KeyPhaseState` | Policy, not a derived AEAD limit; counters freeze while an update is pending |
| 0-RTT replay defence: ±10 s window + seen-set | `core/ZeroRtt.kt`, `core/Resumption.kt` | Per-process memory; empty after restart, not shared across instances; app-layer idempotence still required |
| Stateless resumption tickets (server-encrypted, 7-day lifetime) | `core/Resumption.kt` | No fresh DH/KEM on resume: ticket-key compromise yields the full session key of every recorded resumed session (ts/nonce cleartext, `Resumption.sessionKey`); ticket is a multi-use bearer credential, no client binding or revocation |
| Stateless reset tokens: HMAC(derived secret, connId), constant-time match | `core/StatelessReset.kt` | Token delivered inside encrypted handshake reply; survives restart via ticket key; per-connId, so connId reuse across restarts revalidates old tokens (availability only); secret derives from the ticket key; client-side match is a linear scan over live connections' tokens, min 16 B (Section 3) |
| Amplification bound (3x before path validation) + path challenge | `core/PathValidation.kt` | Bounds reflected bytes, not CPU; also the only bound on peer-driven migration redirection (Section 4) |
| KEM admission control: per-source/global buckets, pressure-triggered Retry tokens | `core/AddressValidation.kt` | CPU bound carried by the global 200/s bucket; per-source bucket is best-effort against spoofed sources (slot reuse resets budgets by design); **resumed initials bypass the global bucket and Retry entirely** (`onCheapInitial` — one ticket AEAD open per packet, gated only per-source, Sections 1 and 4); retry-token secret derives from the ticket key; optional `--token` bearer secret inside the AEAD is admission, not identity |
| Native UDP ingest (batched receive, sockaddr parsing) | `native/rust/src/udp/mod.rs` via Panama FFM | Pre-auth attacker input; 250k-case sockaddr fuzz run; memory-safety audit is AUDIT-PLAN Phase 4 |