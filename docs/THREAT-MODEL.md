# Threat model

Phase 0 of `docs/AUDIT-PLAN.md`. One page, adversary first. Tessera is a single-implementation research
prototype, wire format v0, unaudited; nothing below has been independently reviewed. Claims about mechanisms
are made against the code in `core/src/main/kotlin/tessera/core/`, not the prose docs; where the two disagree,
the code is cited and the disagreement noted.

## 1. Assets protected

- **Payload confidentiality**, including against a recorder who stores traffic today and decrypts with a
  quantum computer later. The handshake secret is hybrid X25519 + ML-KEM-768; breaking confidentiality of a
  recorded session requires breaking both.
- **Payload and header integrity.** Every post-handshake packet is ChaCha20-Poly1305 AEAD with the header as
  AAD; an on-path modification fails authentication and the packet is dropped.
- **Connection identity.** Packets are bound to a connection by keys, not by address; only a holder of the
  connection's keys (or of the server's stateless-reset secret) can terminate or speak on a connection.
- **Bounded reflection.** A spoofed source cannot use the server as a >3x amplifier, and cannot (beyond fixed
  rate budgets) use it as a CPU oracle for ML-KEM decapsulations (`AddressValidation.kt`).

**Not protected:**

- **Traffic analysis.** Packet sizes, timing, rates, and direction are visible. No padding beyond the minimum
  needed for the header-protection sample; no cover traffic.
- **Connection linkability at the network layer.** The 4-byte short connection id rides in the clear on every
  short packet (deliberately, per `PacketCrypto.kt`: header protection masks only the packet number and two
  flag bits; the pathId bits and connId stay readable for load balancers). An observer can trivially track a
  connection across its lifetime, and across a client rebind if the connId is kept.
- **Peer anonymity.** IP addresses are not hidden; that is not this layer's job.
- **Full forward secrecy of the first flight.** The session key is DH(client-ephemeral, server-static) plus
  KEM against the server-static ML-KEM key (`Handshake.kt`). There is no server ephemeral in the exchange —
  the code is "Noise-IK-shaped", not full IK with an `ee` token as SPEC's shorthand might suggest — so an
  adversary who later obtains **both** server static private keys and recorded the traffic recovers the
  session key. Key rotation (`secret_{n+1} = HKDF(secret_n)`) chains forward from that same secret and does
  not repair this. Resumed sessions inherit the same bound via the ticket (below).

## 2. Adversaries

**Passive recorder (incl. harvest-now-decrypt-later).** Records everything, forever. Stopped by the hybrid
key exchange: recovering plaintext requires the X25519 shared secret *and* the ML-KEM-768 shared secret
(`Handshake.kt` concatenates both into HKDF). A future quantum attacker breaks X25519 but not ML-KEM; a
classical attacker with a lattice break gets the KEM but not X25519. Caveat: a recorder who later steals the
server's static keys wins (see forward-secrecy note above); a recorder who later steals a ticket key reads
that ticket's resumed 0-RTT flights.

**On-path active attacker.** Can drop, delay, reorder, modify, inject. Modification and injection fail the
AEAD (per-attempt forgery 2^-128 at tag 16, 2^-64 at tag 8). Flipping the key-phase bit to provoke a spurious
rotation is specifically handled: `KeyPhaseState.open` follows an update only after a packet authenticates
under the pre-derived next-generation keys. Dropping and delaying are availability attacks and are not
prevented — an on-path attacker can always kill a connection.

**Off-path spoofer.** Sends packets with forged sources. Cannot forge data packets (no keys), cannot forge a
stateless reset (the token is delivered inside the encrypted handshake reply and is an HMAC under a
server-held secret, `StatelessReset.kt`), cannot amplify (3x bound before path validation, `PathValidation`;
a Retry packet at 31 B against a >=1.2 KB initial is a deamplifier), and cannot burn KEM CPU past the fixed
per-source and global token buckets — under pressure an unvalidated source gets a Retry token it never
receives at a spoofed address (`AddressValidation.kt`). Known residue, documented in SPEC "Address
validation": the Retry itself is unauthenticated, so an off-path guesser can inject one; the client accepts
at most one per connect, from the addressed server only, and its retransmit train continues — cost is one
extra initial, not a failed connect.

**Malicious peer.** Holds valid keys; the crypto does not constrain it. It can lie in every frame: ACK
inflation, credit/grant manipulation, ECN lies, bogus PMTUD, junk frames. Defences are parsers hardened by
fuzzing (findings recorded in-code, e.g. the empty-packet check in `PacketCrypto.checkShort`), bounded
decoder state, and rate/credit sanity limits — but a peer-driven resource-exhaustion review is exactly what
an audit is for. Assume a malicious peer can waste this endpoint's memory and CPU up to the audited bounds
and no confidentiality beyond its own connection.

**Restarted / keyless server.** A server that crashed and lost all connection state can still emit a valid
stateless reset: the reset secret derives from the operator-provided ticket key, which survives restarts, and
the token is HMAC(secret, shortConnId) where the connId is readable on the client's retransmits
(`StatelessReset.kt`). Reset emission is itself bounded (min-size packet requirement plus a 2000/s global
bucket, SPEC "stateless reset") so the mechanism is not a reflector. The failure mode without it — client
retransmits into a black hole until the 10 s idle timeout — is availability, not secrecy.

## 3. Out of scope

- **Key distribution.** Noise IK premise: the client already holds the server's static X25519 and ML-KEM
  public keys, obtained out of band (pinning, TOFU, delegated credential). There is no PKI, no certificates,
  no in-band identity. A client that accepts the wrong static key has lost before the first packet.
- **DoS beyond the stated bounds.** The 3x amplification bound, the KEM admission budgets, and the reset/Retry
  rate caps are the whole DoS story. Volumetric flooding of the link, state exhaustion by a peer with valid
  keys past audited limits, and everything an on-path attacker can do by dropping are out of scope.
- **Side channels beyond constant-time primitives.** Tag and token comparisons are constant-time
  (`Arrays.constantTimeAreEqual`, the local `eq` in `AddressValidation.kt`); the crypto is BouncyCastle's.
  Cache-timing of the JVM, the Rust SIMD datapath, and ML-KEM implementation side channels are not analyzed.
- **Traffic analysis** of sizes, timing, and connection linkage (Section 1).

## 4. Known accepted weaknesses

- **`tagLen = 8` truncated-tag mode.** Negotiated in `ConnParams` (`Params.kt`): forgery ~2^-64 per attempt,
  documented in-code as acceptable for media/game state, **not for transactions**. Truncation happens on the
  wire only; the keystream and confidentiality argument are unchanged (SPEC v0.9). The truncated-tag open
  path recomputes the full Poly1305 tag and compares a prefix in constant time (`PacketCrypto.openTruncated`).
- **±10 s 0-RTT replay window with a seen-set.** `ZeroRtt.Server` and `Resumption.Server` reject timestamps
  outside ±10 s and any fingerprint already seen inside the window. The fingerprint is 8 bytes (first 8 of
  the random ePub; ticket-nonce xor client-nonce for resumption) — collision, not forgery, resistance; the
  seen-set is pruned only above 100k entries. Within-window replay from a clock-skewed pair, and the fact
  that 0-RTT data must be idempotent at the application layer regardless, are accepted and documented in the
  code. A resumed connection additionally binds ts+nonce into the session key so a replayed ticket under a
  fresh nonce yields a distinct key (`Resumption.sessionKey`).
- **Single implementation, no interop evidence.** The v0 wire format has never been parsed by an independent
  decoder (AUDIT-PLAN Phase 2 / TODO item 7). Every compatibility claim is self-referential.
- **No audit.** No external cryptographic or implementation review has occurred. The handshake deviates from
  proven Noise IK (hybrid KEM injection, no server ephemeral, 0-RTT layer) and those deltas are undocumented
  formally — that is Phase 1.

## 5. Mechanism inventory

| Mechanism | Where | Notes |
|---|---|---|
| Hybrid handshake: X25519 + ML-KEM-768 into HKDF-SHA256 | `core/Handshake.kt` | Client ephemeral only; server static keys pinned out of band |
| Packet AEAD (ChaCha20-Poly1305), header protection, truncated-tag open | `core/PacketCrypto.kt` (`PacketKeys`, `PacketProtection`) | RFC 9001 §5.4-shape HP; flags mask 0x63 leaves connId/pathId in clear by design |
| Key update via phase bit; one retained previous generation; follow only after next-key auth | `core/PacketCrypto.kt` (`KeyPhaseState`) | HP key fixed at generation 0 for the connection's lifetime |
| Automatic rotation policy: 2^20 packets or 1 GiB sealed per tx generation | SPEC v0.9; trigger in `transport` `transmit`, machinery `KeyPhaseState` | Policy, not a derived AEAD limit; counters freeze while an update is pending |
| 0-RTT replay defence: ±10 s window + seen-set | `core/ZeroRtt.kt`, `core/Resumption.kt` | App-layer idempotence still required |
| Stateless resumption tickets (server-encrypted, 7-day lifetime) | `core/Resumption.kt` | Ticket-key compromise reads resumed 0-RTT flights |
| Stateless reset tokens: HMAC(derived secret, connId), constant-time match | `core/StatelessReset.kt` | Token delivered inside encrypted handshake reply; survives restart via ticket key |
| Amplification bound (3x before path validation) + path challenge | `core/PathValidation.kt` | Bounds reflected bytes, not CPU |
| KEM admission control: per-source/global buckets, pressure-triggered Retry tokens | `core/AddressValidation.kt` | Bounds CPU; fixed 8192-slot keyed table, no growth |
