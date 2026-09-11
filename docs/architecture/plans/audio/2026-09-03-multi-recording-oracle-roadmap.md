# Multi-Recording Driver-Oracle Roadmap

## Outcome

Every supported game's SMPS driver should be measurable against real hardware-
behaviour captures over more than one recording and more than one bounded
window, so that a MATCH means the engine's driver is right rather than that one
window happens to agree. The acceptance surface is unchanged from the
[playback-authenticity roadmap](2026-08-21-smps-playback-authenticity-roadmap.md):
driver-RAM-shaped track state plus the ordered YM/PSG write stream of each
driver invocation, compared per tick with no realignment.

Today Sonic 2 has one published 750-row window on one movie, Sonic 1 has its two
inviolable oracle gates on a sound-test and a GHZ capture, and Sonic 3 & Knuckles
has no request authority at all. That is one recording per game at best. This
roadmap takes each game from one window on one movie to at least two recordings
with a widened window, and makes live recapture a routine, supervised step
rather than a one-off event.

## Scope boundary

Work is in scope when it does one of:

- publish or widen a driver-oracle reference capture for S1, S2, or S3K;
- add the request-observation authority a game still lacks;
- fix the first divergence a widened or new capture exposes, in the ROM-owned
  production source;
- make live capture reproducible from a supervised session.

Work is out of scope unless a named divergence proves it necessary:

- new comparison layers, schemas, or native observer ABI expansion;
- architecture migration whose only outcome is cleaner abstraction;
- whole-run recapture to restamp metadata;
- taking a `FixBugs = 0` branch the retail ROM did not take, in either
  direction.

Publication remains gated as Task 8A gates it: two independent captures with
identical digests, then explicit human approval. No fixture is hand-edited,
extended, or regenerated to make a comparison agree.

## Delivery items

### 1. Publish the Sonic 2 request-window fixture — this commit

The 750-row `[10150,10900)` request-aware raw-v2 capture becomes a committed
comparison reference at
`src/test/resources/audio/parity/s2/s2-request-window-w10150-10900.raw-v2.jsonl.gz`,
with a metadata sidecar recording both capture paths, the identical SHA-256, the
BK2 identity, the TraceChaser commit, the observer and extractor identities, and
the preserved `production_bound: false` state.
`TestS2RequestAwareOracleRawStream#realCandidateAndBk2DrivenDriverStateCompare`
defaults to the committed payload and keeps `-Ds2.request.candidate.path` as an
override. Publication installs a reference; it binds no producer, and request
equality stays a reference limitation.

### 2. S3K request authority, then the first divergence from service 128

- Implement the Task 8B S3K request observer at the `$1358`-`$1374` mailbox,
  approved 2026-09-02 and bounded by
  [the producer audit](../../audits/audio/2026-09-02-s3k-preconsumption-request-producer-audit.md).
  The observation is fixed at that mailbox; callers cannot select an address.
- Capture, duplicate-gate, and publish the Sonic/Tails `[0,5400)` diagnostic
  prefix. It is its own identity and never runs under the Knuckles complete-run
  profile.
- Then run the S3K first-divergence loop from service 128: one divergence, one
  ROM-owned fix, rerun, repeat.

### 3. Widen each game's window

- **S2** beyond row 10900, in bounded chunks rather than one jump to the end of
  the movie. Each chunk is its own capture, duplicate gate, and divergence loop.
- **S3K** beyond the AIZ1 intro, once item 2 has an authority to widen.
- **S1** onto the complete-run movie `sonic1-complete-withemeralds.bk2`, which
  is the first S1 evidence past the two existing bounded gates.

### 4. At least two recordings per game

A second recording is what distinguishes a correct driver from a fitted one.
The pinned movies that already exist are the cheapest second recordings:

- **S3K**: `s3k-full-chain-tails-all-emeralds.bk2` and
  `s3k-knuckles-complete-superemeralds.bk2`, which exercise different characters
  and different music routes.
- **S1**: `sonic1-complete-withemeralds.bk2` alongside the existing sound-test
  and GHZ gates.
- **S2**: a fresh route recorded specifically to leave the complete-emeralds
  movie's music sequence, so a S2 fix cannot be tuned to one song order.

An engine change that greens one recording and reds another is a fitted fix, and
the second recording is the thing that says so.

### 5. Live capture as a routine step

Live captures run from an X-capable session through the canonical TraceChaser
launcher, never from an ad-hoc BizHawk invocation. The first smoke of that path
is an S1 fixture recapture: the S1 fixtures are already published and pinned, so
a recapture that reproduces their digests proves the capture path before any new
evidence depends on it.

## Working rules

1. Fix only the first divergence. Never realign, skip ahead, or batch fixes
   across divergences.
2. Cite the driver routine that owns the behaviour, by file and line, next to
   every non-obvious change.
3. Model the `FixBugs = 0` path the retail ROMs shipped, and say so in a comment
   where the disassembly has the conditional.
4. Never edit a fixture and never weaken a comparator to reach agreement. A
   comparator that stops reporting a divergence has removed evidence, not
   produced a fix.
5. The S1 oracle gates are inviolable. Every tranche reruns them, and a red S1
   gate blocks the tranche regardless of what it greened elsewhere.
6. One review round per tranche. Land or reject; do not reopen a reviewed
   boundary to widen it.
7. No new architecture without a tick it moves. A layer, schema, or abstraction
   earns its place by naming the divergence it resolves.
8. Every native-boundary tranche ends with a disposable, non-authoritative live
   smoke. It proves the path still runs; it never becomes evidence and never
   moves the frontier.
9. Long runs write an explicit exit marker and watchers key on that marker.
   Process-name polling matches the watcher's own shell and is not a liveness
   signal.

## Current slice

Item 1 lands in this commit. Items 2 and 3-S1 are already running in parallel
lanes, so this document is their shared frontier record rather than a queue for
one worker. Items 3-S2, 4, and 5 are unstarted and unblocked by each other; item
4 for S3K depends on item 2 producing an authority first.
