# Design: message expiry and latest-wins (TODO §2e) — design only, nothing here is built

Fast-changing state — positions, ticks, sensor readings, presence — wants old updates **dropped**, not
reliably delivered late. Tessera reliably delivers everything, which for stale data is effort spent on the
wrong thing: the measured low-rate amplification (BENCH, `bench amp`) is largely insurance on messages whose
value may already be zero. This is the gap the external review's MoQ comparison points at, and the one feature
that makes "small data that changes a lot" a true workload claim rather than an aspirational one.

The prize, concretely: at high update rates on a lossy link, the latency that matters is **staleness of the
latest value**, not delivery latency of every value. A transport that may abandon superseded updates spends
its repair budget only on messages that still matter.

## The constraint that shapes everything: emitted equations are immutable

A repair symbol is a linear combination over the encoder window. Once emitted, the sources it mixes cannot be
unmixed: if the receiver holds `R = a·S1 + b·S2` and needs `S2`, it needs `S1` — expired or not — to solve.
So **expiry cannot mean "forget the symbol"** on either side while any in-flight equation references it.
Every design that starts "just delete the expired message from the window" resurrects the vsbulk lesson: the
repair machinery will faithfully re-deliver what the application abandoned, or the decoder will wedge on
equations it can no longer reduce.

What expiry CAN mean, without touching emitted equations:

1. **Stop spending on it going forward.** No new repair windows include it, no verbatim re-sends
   (`resendCancelled` already exists as machinery and a counter), no tail repair for it, no linger obligation
   at close (`peerLowestUndelivered` accounting must treat it as settled).
2. **Tell the receiver to stop waiting for it.** The receiver marks the seq delivered-without-delivery — the
   `skipDelivered` / `markDelivered` path exists — so FEC feedback stops reporting it as a hole, the horizon
   advances past it, and `lowestUndelivered` moves. Its symbol, IF held, stays usable for decoding; if never
   received, equations referencing it are reduced normally once it is either recovered incidentally or every
   live unknown in them is solved elsewhere.

## Wire: one new frame

`Expire` (next free type): `seqLo(varint) seqCount(varint)` — the sender declares fec seqs
`[seqLo, seqLo+seqCount)` abandoned. Properties it must have:

- **Idempotent and unreliable**, like MaxData and AckFrequency: never retransmitted as itself, but re-sendable,
  and piggybacked on the same cadence as FEC feedback re-runs. A lost Expire costs only continued (wasted)
  repair traffic until the next one — degradation, not incorrectness.
- **Monotone-safe**: expiring an already-delivered seq is a no-op; delivering an already-expired seq is
  `skipDelivered`. Race-free by construction because both sides converge on "settled".
- Ranges, because supersession expires runs of updates at once at high rates.

## API: two layers

- **TTL**: `send(bytes, ttlMs)` — the sender expires the message itself when the deadline passes un-acked.
  The transport-visible form; no key concept.
- **Supersede-by-key**: `send(bytes, key)` — sending under a key expires every earlier un-acked message with
  the same key. Pure sender-side policy over the TTL machinery (expire-now on the predecessor), so it costs no
  additional wire surface. This is the latest-wins primitive applications actually want.

Both are per-message opt-in; the default stays reliable-everything. A message class, not a connection mode —
mixing telemetry (expiring) and control (reliable) on one connection is the point.

## What it deliberately does NOT do

- No receiver-driven expiry (the receiver cannot know value staleness; only the sender can).
- No reordering-window semantics or per-key ordering guarantees — latest-wins is "the newest that ARRIVED",
  and applications needing sequencing already stamp their own.
- No interaction with credit/flow accounting: expired bytes were sent and charged; the books stay closed.
- No expiry of 0-RTT payloads (they are the handshake; their retransmission is the connect).

## What would settle it (the tests are the spec)

1. **Expired messages consume no further repair budget**: at a fixed loss rate, a stream where every message
   is superseded before its repair fires shows `repairsGated`/`resendCancelled` absorbing what the baseline
   spends, and `bench amp`'s pkt/src drops toward 1 at low rates.
2. **An expired hole blocks nothing**: close linger completes with expired seqs outstanding
   (the `lingerNeeded` clause honours settlement); FEC feedback stops re-sending for them; the reliability
   horizon advances past them without `HZN-ASSUMED` firing.
3. **The decoder never wedges on expiry**: a soak where random subsets expire mid-window, asserting every
   *live* message still delivers — the immutable-equations constraint, exercised.
4. **The latency claim**: on a lossy profile at high update rate, staleness-of-latest for keyed updates vs the
   reliable-everything baseline. If latest-wins does not beat reliable delivery on staleness under loss, the
   feature has no reason to exist and this document should record that instead.

## Cost estimate and order

Frame + sender bookkeeping + receiver settle path is days; the soak and the staleness bench are the real work.
Do it after the growth-rule redesign — expiry changes what the repair machinery spends, and measuring it on top
of a congestion story in flux would confound both.
