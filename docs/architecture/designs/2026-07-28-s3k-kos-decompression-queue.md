# S3K direct Kosinski decompression queue

Date: 2026-07-28

## Status

Proposed for implementation. This design promotes the S3K direct Kosinski
decompression queue from the hardware-timing inventory's pending-review state
to a separately versioned authoritative readiness owner.

## Requirements

### Goals

- Model the ROM's shared four-entry `Queue_Kos` FIFO as runtime-owned,
  rewind-safe production state.
- Make direct completion visible at the ROM's pre-main-loop boundary, before
  the same frame's object and screen-event scans.
- Route every KosM module through that shared direct FIFO so ordinary Kosinski
  work and KosM work contend exactly as they do in the ROM.
- Replace AIZ intro and ICZ1-to-ICZ2 synthetic readiness with the ROM-owned
  `Kos_decomp_queue_count == 0` predicate.
- Record and replay direct completion without allowing trace data to create
  work, provide payload bytes, or call gameplay consumers.
- Preserve schema-1 parsing and module-only authority while introducing a
  schema that can authorize both queue kinds. Schema-1 AIZ/ICZ fixtures that
  cross a direct-count consumer must be regenerated before remaining replay
  gates.

### Non-goals

- Cycle-accurate 68000 execution or VBlank interruption.
- Recording decoder progress or copying decompressed bytes from a trace.
- Modeling synchronous Kosinski calls that do not enter `Queue_Kos`.
- Porting every unimplemented S3K act transition as part of this change.
- Using a fixed frame delay, zone exception, route identity, or trace frame as
  production behavior.

### Constraints

- Runtime bytes come from the user-supplied ROM.
- The direct queue is a single session-owned physical FIFO. Facades must not
  create independent queues.
- A module-created direct stream is a real direct submission and receives a
  direct ordinal and fingerprint in schema 2. Suppressing these children would
  omit the stream whose completion can make the queue-empty predicate true.
- Existing module archive ordinals and final `POST_OBJECTS` readiness remain a
  separate lifecycle from child direct-stream completion.
- Rewind captures both FIFOs, active decoder state, parent-child associations,
  ordinals, prepared payloads, readiness, and recorded-edge cursors.

## Exploration synthesis

### ROM evidence

`Queue_Kos` appends an eight-byte source/destination entry and increments
`Kos_decomp_queue_count`. `Process_Kos_Queue` owns the FIFO head, sets bit 15
while decoding, resumes through the VInt bookmark, clears the busy bit, retires
one stream, and shifts remaining entries
(`docs/skdisasm/sonic3k.asm:2803-2967`).

`Process_Kos_Module_Queue` does not have a private decoder. It appends the
current module to the same direct FIFO, sets the module busy bit, waits for the
entire direct FIFO to become empty, enqueues the module DMA, then advances the
module/archive state (`docs/skdisasm/sonic3k.asm:2668-2791`).

The ordinary level loop services the direct queue before `Wait_VSync`, runs
objects and screen events, then services the module queue
(`docs/skdisasm/sonic3k.asm:7884-7922`). Therefore:

- direct completion is observable at `PRE_MAIN_LOOP`;
- AIZ and ICZ screen events can consume queue-empty readiness in that same
  admitted scan; and
- final module retirement remains `POST_OBJECTS`.

The two gameplay consumers of the direct count are AIZ intro progression
(`docs/skdisasm/sonic3k.asm:104575-104590`) and ICZ act-transition progression
(`docs/skdisasm/sonic3k.asm:110259-110287`). Numerous act transitions enqueue
ordinary chunks/blocks beside KosM art, so the shared FIFO also affects module
completion indirectly.

### Current engine and recorder

`S3kKosModuleQueue` currently embeds a `ResumableKosinskiDecoder` and explicitly
does not submit child direct work. `HardwareTimingService` services the first
globally unprepared job and admits prepared jobs in global submission order.
Adding child jobs without changing that rule can deadlock a module parent
behind its own child or impose ordering between unrelated physical queues.

The native recorder's `HardwareTimingEventEngine` and the Lua corroboration
module currently mirror only the module FIFO. Both already have the ROM
scanner and stable fingerprint machinery needed for a second ledger, but the
direct ledger must use the low 15 count bits, retain FIFO identity across the
busy bit, and detect head shifts without requiring a sampled zero.

### Considered approaches

1. **Recommended: compositional direct child jobs with schema-2 authority.**
   The direct FIFO owns standard Kosinski decoding; module parents submit
   children to it and advance only after the shared FIFO empties. Both child
   and parent completions are recorded at their real boundaries. This matches
   ROM ownership and preserves strict identity.
2. **Record only ordinary direct jobs.** This reduces fixture events, but a
   module child may be the last physical stream whose completion releases the
   AIZ/ICZ direct-count poll. Omitting it makes queue-empty timing host-derived
   and is rejected.
3. **Keep independent module/direct decoders.** This is additive but cannot
   reproduce FIFO capacity, contention, or the ROM's queue-wide wait. It is
   rejected.

## Architecture decision

### Queue ownership

Add a session-owned `S3kKosDecompressionQueue` accessed through the S3K object
art/resource owner. It represents the one physical direct FIFO and exposes:

- `queueStandardKos(...)` for ordinary ROM `Queue_Kos` submissions;
- an internal module-child submission API carrying the parent archive handle
  and module index;
- `decompressionsPending()` matching the low-15-bit ROM predicate;
- handle-specific readiness and claim operations; and
- complete rewind capture/restore.

An `S3kKosModuleQueue` facade retains archive-level submission and readiness,
but its preparation becomes a coordinator-owned, boundary-driven aggregate
job. A module parent performs no decoder work through the generic scheduler:

1. initialize the active archive/module;
2. when the module is not busy and direct capacity permits, submit its exact
   standard-Kosinski child to the shared direct FIFO;
3. at `PRE_MAIN_LOOP`, the direct owner advances only the FIFO head;
4. at `POST_OBJECTS`, if the active child is ready and the entire direct FIFO
   is empty, claim its bytes, append them to the archive output, perform the
   ROM-equivalent DMA/publication state change, and advance;
5. after the final child, prepare the module parent payload and retire the
   module archive through its existing readiness owner.

Each `POST_OBJECTS` invocation performs at most one ROM-equivalent module
transition. Queueing a child returns immediately. Retiring child N also
returns immediately, so child N+1 cannot be submitted until the following
`POST_OBJECTS`.

The direct owner distinguishes physical FIFO occupancy from payload ownership.
A `PRE_MAIN_LOOP` retirement removes the job from physical occupancy and makes
its payload ready and claimable. A ready-but-unclaimed child neither keeps
`decompressionsPending()` true nor consumes one of the four slots. A full FIFO
causes module submission to defer and retry on a later module-service call,
matching the ROM; it is not a fatal fifth-child submission.

### Timing scheduler

Hardware preparation and live admission become per-kind rather than globally
head-blocking. FIFO order is preserved within each `HardwareWorkKind`.
Dependencies between kinds are expressed only by the S3K queue coordinator;
the generic timing ledger cannot advance module-parent preparation.

The service continues to expose only typed submission, preparation, and final
readiness. Recorded authority cannot submit a child or cause module
advancement.

### Authority schemas

- No timing stream configures both kinds as `LIVE`.
- Hardware timing schema 1 configures `KOS_MODULE_QUEUE` as `RECORDED` and
  `KOS_DECOMPRESSION_QUEUE` as `LIVE`. Direct jobs run through the production
  scheduler and do not require recorded edges.
- Hardware timing schema 2 authorizes `KOS_MODULE_QUEUE` and
  `KOS_DECOMPRESSION_QUEUE` as `RECORDED`. A schema-2 direct job prepared early
  remains pending until its matching edge.
- The metadata value selects the allowed kind registry. Unknown kinds or kinds
  not authorized by that schema fail fixture loading.
- Trace schema remains 7 because the container and event shape do not change;
  recorder versions are bumped to identify the expanded semantics.

This is a compatibility migration, not a permissive fallback. A schema-2
fixture missing a production direct edge fails at the normal segment/run
completion checks. Schema 1 remains loadable, but it cannot be claimed as a
sync gate for AIZ intro or ICZ act-transition gameplay until that fixture is
regenerated under schema 2. The committed fixture inventory must identify
every schema-1 route that reaches either consumer.

`HardwareTimingService` snapshots the per-kind admission map. Its recorded
capability rejects an edge for any kind that is not `RECORDED` under the
installed schema.

### Direct identity

Each physical `Queue_Kos` submission receives a per-kind monotonic ordinal.
The fingerprint uses the existing canonical tuple:

- kind `KOS_DECOMPRESSION_QUEUE`;
- canonical ROM source address;
- scanned compressed length;
- canonical destination address;
- decoded destination length;
- variant `kosinski`;
- module count `1`.

For module children, the canonical source is the exact aligned child stream
address derived independently by the engine and recorder. Canonical
destination is always the exact 32-bit longword stored by the ROM's
`Queue_Kos`. Word-addressed RAM destinations are therefore sign-extended
(`0xFFFFxxxx`), not masked to `0x00FFxxxx`; this includes ordinary
`RAM_start`/`Block_table` destinations and the shared `Kos_decomp_buffer`.
Those exact bits are serialized big-endian on both sides; Java uses the
corresponding signed `int` bits and C# uses `unchecked((int)u32)`. Host array
offsets, 24-bit bus-normalized addresses, and publication targets are separate
metadata and never enter this field. Parent ordinal and module index are
production dependency metadata but are not trace-supplied and do not replace
the canonical fingerprint fields.

Golden engine-Java/native-C# vectors cover one AIZ ordinary destination, one
ICZ ordinary destination, and one module-buffer destination.

The standard-Kosinski scanner starts at the stream descriptor: there is no
KosM total-size header and no inter-module alignment. Compressed length ends
after the zero terminator. Decoded length counts literal and match output,
ignores the one-byte no-output marker, and validates descriptor refills,
backreferences, terminators, and ROM bounds.

### Recorder lifecycle

The native recorder adds a run-wide direct FIFO mirror at `$FF40-$FF5F` and
reads `Kos_decomp_queue_count` at `$FF0E`.

- Bit 15 is busy state; bits 0-14 are the physical count.
- The mirror retains the sampled busy state as lifecycle evidence; it is not
  discarded after extracting the physical count. While the prior head remains
  busy, slot zero must retain its canonical identity and cannot be inferred as
  retired. A busy-to-not-busy transition proves retirement of that canonical
  head, including the otherwise byte-ambiguous case where an identical job is
  appended into slot zero before the next sample.
- Newly observed slots allocate ordinals in FIFO order, including identical
  adjacent submissions.
- A retirement is detected from the prior mirrored head plus count/shift
  reconciliation. A sampled zero is not required.
- Retirement and append reconciliation supports every observable count
  transition permitted between PRE samples, including `1 -> 1`, `1 -> 2`,
  `2 -> 3`, and unchanged-count transitions from longer queues. It first
  derives the longest valid suffix/prefix overlap between the prior canonical
  ledger and the sampled physical slots, retires only the proven leading
  entries, then allocates every proven appended slot in FIFO order.
- Canonical identity is captured when a slot first appears and retained in the
  mirrored ledger. Interrupted progress lives in saved registers. A final
  retirement writes advanced pointers into slot zero, so stale zero-count RAM
  is never used to invent or recanonicalize a job.
- Every retirement emits a `pre_main_loop` direct event in schema 2.
- The module observer continues to emit final archive retirement separately.
- When direct and module jobs retire in one raw frame, canonical output order
  is the direct `pre_main_loop` edge followed by the module `post_objects`
  edge.
- Within an armed observable interval, count is always
  `Kos_decomp_queue_count & 0x7FFF`; zero-sentinel scanning is forbidden.
  Every occupied physical slot, including slot zero, is identity-checked
  against the canonical ledger. Interrupted decoder progress is represented
  only by `$FF10-$FF37`, so a changed slot zero while the prior head remains
  busy is fatal. After a busy-state transition proves or excludes head
  retirement, a shifted head must match a later prior ledger entry and
  appended slots begin only after that proven overlap. Identical descriptors
  use the busy transition plus count and maximum-overlap constraints so an
  admitted identical replacement receives a new ordinal while a stable
  identical snapshot emits nothing.
- A completion requires a previously mirrored submission. Phase-local work
  that appears and retires wholly inside a declared unrepresented gap emits no
  fabricated edge; the next represented interval reboots from observable
  pending slots. Unexplained loss or mutation inside an armed interval fails.
- Unrepresented gaps, special-stage detours, and segment handoffs preserve
  both ledgers. An accepted standard-recorder discard/reset atomically
  discards both ledgers and streams and initializes both ordinal bases to
  zero.

STANDARD differential validation accepts production `6.38-s3k`,
trace-schema 7, hardware-schema 2 metadata and validates both direct and
module ledgers. Its compatibility path continues to load committed
`6.37-s3k`, trace-schema 7, hardware-schema 1 fixtures, but does not grant
those fixtures direct-count boundary authority.

The deferred-resource load overload must not worsen the existing
`LevelManager` size ratchet. Move its self-contained pattern-location search
algorithm to a package-local level utility and retain the public
`LevelManager.findPatternOffset(...)` API as a thin delegate. This is an
ownership-preserving extraction only: map selection, coordinate conversion,
search order, return shape, and callers remain unchanged.

Tests that exercise level-iteration timing admission must prepare Kos work
through the runtime-owned direct/module coordinator rather than fabricating a
raw `KOS_MODULE_QUEUE` submission at the timing service. Rewind-registry
coverage must include the new seamless-transition handoff adapter as a tenth
gameplay-mode key. These are production-contract updates to stale harness
expectations, not authority bypasses or guard baselines.

AIZ event tests that drain the fire-transition Kos work must execute the same
production callback order as gameplay: timing service, direct FIFO retirement,
then KosM parent coordination. That drain helper is scoped to transition tests
which explicitly submitted the queue work; unrelated intro and save-event
harnesses must not gain implicit queue advancement.

### Provider boundary and production ownership

Shared session, frame, service, and object layers must depend only on a
game-neutral runtime-art coordinator contract. `GameModule` creates that
contract from the session-owned `HardwareTimingService`; S3K's implementation
privately owns the concrete direct and module queues and supplies its
rewind/reset adapters. `GameplayModeContext`, `LevelFrameContext`,
`LevelFrameStep`, `GameServices`, and `ObjectServices` store or expose only the
neutral contract. S3K code resolves its concrete facade through an S3K-local
adapter; zone event subclasses use protected accessors on
`Sonic3kZoneEvents`, not direct `GameServices` calls. No shared API returns an
S3K queue type.

The base `com.openggf.data.Game` abstraction remains independent of level
runtime resource trackers. Optional deferred loading lives behind a
level-layer `DeferredLevelResourceLoader` provider implemented by `Sonic3k`;
`LevelManager` selects that provider when an explicit tracker is present and
otherwise uses the ordinary `Game.loadLevel(int)` path.

The physical module FIFO counts only parents that have not completed
preparation. Ready-but-unclaimed timing payloads remain claimable by their
consumer but no longer occupy one of the ROM's four module slots. Production,
headless, title-card, and provider-driven loops all invoke the neutral
coordinator at the same PRE/direct and POST/module boundaries. Repeated
producer calls must retain their handle instead of resubmitting while pending.

Object and results rewind tests construct session-backed `ObjectServices`
which expose the neutral coordinator; tests must not fabricate fallback S3K
queues. Architecture guards must pass with no new shared-to-S3K,
data-to-runtime, or zone-event direct-service violations.

Fresh level assembly arms the existing setup-only initial `Process_Sprites`
lifecycle before post-load assembly, and gameplay reset discards any
unconsumed token. Runtime-art queue work must not cause title release to
consume an ordinary-frame pass or leak the one-shot setup lifecycle across
teardown. Results-object rewind harnesses follow the production multi-dispatch
creation gate before asserting queued-art ownership.

The ROM can preserve bit 15 and resume an interrupted active decompressor
through its Kos bookmark registers; recorded authority remains the source of
truth for that observed completion timing. The engine's non-cycle-accurate
live fallback cannot infer a VInt interruption budget, so one admitted PRE
executes the complete codec call for the active direct child instead of
yielding after one descriptor command. Thus the live fallback completes one
direct FIFO head per PRE. The decoder remains snapshot-capable for
prepared/recorded authority and rewind restoration, while live execution does
not invent a hundreds-of-frames command cadence. Real AIZ title archives must
retire quickly enough that later plane/object-art producers observe physical
module capacity in the ROM order.

The native headless recorder is the sole maintained fixture authority. The
frozen Lua recorder is not extended merely for parity; independent Java/C#
golden vectors and ROM/disassembly lifecycle tests provide cross-implementation
corroboration. A throwaway Lua diagnostic may be used if a disputed capture
needs it, but it is not a deliverable.

### AIZ consumer

At the ROM trigger, queue the main-level 16x16 standard-Kosinski stream and
the main-level KosM archive through the shared owner. Split the current
combined publication:

- the direct 16x16 block overlay is published when
  `decompressionsPending() == false`, and `Events_fg_5`/deformation/draw
  progression advances in that same screen-event scan;
- the KosM 8x8 pattern payload is claimed and published only after module
  retirement at `POST_OBJECTS`, so it becomes consumer-visible no earlier
  than the following scan.

Predecoded bytes may be retained only as a payload optimization; neither
publication fence is bypassed.

The immutable S3K level-resource profile for AIZ intro composes two
ROM-derived LevelLoadBlock identities: entry 0 remains the immediate initial
resource plan, while entry 26 contributes only the exact main-level 16x16
block and KosM pattern descriptors as deferred declarations. Entry 0's
secondary intro resources are not omitted. Every `Sonic3k.loadLevel` call
creates a fresh local
exact-consumption tracker from that profile while constructing the ordinary
initial `LevelResourcePlan`, omits each descriptor exactly once, and verifies
the profile was fully consumed. Selection is owned by the
ROM-derived S3K LevelLoadBlock/profile identity; shared loading code contains
no AIZ/zone-name branch. The non-intro AIZ1 profile and later ordinary loads
do not inherit the deferral. Re-loading the intro profile creates a new local
tracker and reproduces the same deferral; no consumption state is retained in
the immutable profile.

This makes the initial target buffers genuinely pre-publication data. AIZ
tests compare the affected block and pattern bytes before the trigger, after
direct PRE publication, and after module POST publication; phase flags or
handle claims alone are insufficient evidence. Profile tests cover repeated
intro loads, prove entry 0 intro assets remain visible while entry 26
main-level bytes remain absent, and reject missing, duplicate, and
non-matching deferred descriptors.

### ICZ consumer

At camera X `$6900`, queue the ICZ2 secondary chunk and block standard-Kosinski
streams plus the secondary KosM archive. Remove the fixed 41-frame timer.
Request the act transition on the first screen-event scan observing the shared
direct queue empty, preserving the existing seamless-transition mutation and
save behavior.

The secondary module archive is an intentional seamless-transition handoff.
Its parent and ready child payload remain session-owned across
`executeActTransition`; the timing ledger is not reset. ICZ2 receives the
exact transferred handles/facade, the same frame's later `POST_OBJECTS` may
retire the parent, and ICZ2 publishes the payload through its level-resource
owner. The parent is exportable across this structural handoff only with that
exact production owner and matching future edge.

The source event registers an immutable, transition-scoped
`SeamlessTransitionResourceHandoff` in a session-owned,
rewind-snapshottable `SeamlessTransitionHandoffRegistry`. The request carries
only its opaque handoff id. The registered payload contains:

- a generic `LevelResourceLoadPolicy` whose entries are exact resource
  descriptors, not zone/game names; and
- the production-owned queue handles/facades that the handoff transfers to the
  newly initialized target event provider.

Before any level/game-state mutation, `LevelActTransitionExecutor` atomically
claims and removes the id from the registry. Claim returns the immutable
payload and creates a per-execution consumption tracker from its policy. A
failed transition does not put the payload back, so retrying or applying the
same request twice fails before resource loading. Rewind before the claim
restores the registered payload; rewind after the claim preserves its consumed
state. The executor passes the tracker to
`LevelManager.loadLevelData(levelIndex, policy)`, which selects the optional
level-layer `DeferredLevelResourceLoader` implemented by `Sonic3k`; ordinary
games continue through `Game.loadLevel(int)` and reject a non-empty policy.
The S3K loader consumes matching descriptors while building its
`LevelResourcePlan`. The S3K resource owner matches the exact
already-submitted ICZ2 secondary chunk, block, and KosM descriptors and omits
those load operations. Shared loading code carries and verifies descriptor
identity only; it contains no ICZ or zone-name branch.
A missing, duplicate, or non-matching descriptor fails the transition. The
tracker verifies that every requested descriptor was consumed exactly once
before transition execution continues.

After `initLevelEventsForCurrentZoneAct` constructs the target provider,
`LevelActTransitionExecutor` invokes the claimed handoff to transfer the
exact handles/facades atomically. Immediate execution and queued
`requestSeamlessTransition` execution therefore use one path. When
`RELOAD_SAME_LEVEL` is rebuilt as a target request, the handoff is copied
unchanged. A handoff is single-use for one transition execution and cannot
affect a later ordinary load.

Consequently, target buffers remain at their pre-publication state across the
in-frame reload. The transferred direct chunk/block payloads and the KosM
parent payload are published through the mutation/resource owner only after
their respective production readiness fences. Tests compare target buffer
bytes before and after publication; handle readiness alone is insufficient
evidence.

### Failure handling

- Queue overflow, unexplained recorder FIFO mutation/loss, malformed Kosinski
  input, parent-child mismatch, wrong kind/ordinal/fingerprint/boundary, an
  unprepared recorded completion, or leftover non-exportable work is fatal.
- A module child cannot be claimed while another physical direct entry remains pending,
  matching the ROM's queue-wide wait.
- Rewind restore rebinding must locate exact handles by kind and ordinal;
  missing or mismatched handles fail rather than resubmit work.

## Feature design and acceptance tests

### Queue behavior

- Four direct entries are accepted; a fifth fails.
- Standard Kosinski bytes decode from the ROM and are returned only after the
  direct handle becomes ready.
- Only `PRE_MAIN_LOOP` advances direct decoding or admits direct readiness.
- Busy/interrupted snapshots restore byte-identical decoder progress.
- Head shift, adjacent identical jobs, and retire-plus-append preserve ordinal
  identity.
- Final retirement leaves a ready/claimable payload while physical occupancy
  is zero and slot-zero RAM contains stale advanced pointers.

### Module composition

- A KosM archive submits exactly one direct child per module.
- Ordinary direct work ahead of or behind a child participates in the same
  capacity and queue-empty predicate.
- A child direct completion is observable at `PRE_MAIN_LOOP`; the corresponding
  module advancement/final retirement occurs at `POST_OBJECTS`.
- Recorded authority cannot release the module parent before its child payload
  is prepared and claimed.
- Frame-by-frame multi-module service is pinned as POST queue child N, next PRE
  complete child N, next POST retire child N, following POST queue child N+1.
- A child behind ordinary work and ordinary work appended behind a child both
  delay module POST advancement until the entire direct FIFO empties.
- A full direct FIFO defers a module child without failing.
- A final child direct edge at PRE and parent module edge at POST may share a
  raw frame and retain canonical boundary ordering.

### Replay compatibility

- Schema 1 accepts only module events and services direct jobs live; its module
  parent remains recorded while its direct children can become live-ready.
- Schema 2 accepts both kinds and holds early direct completion for the exact
  recorded edge.
- Schema 1 rejects direct events; schema 2 rejects missing/extra/mismatched
  direct events.
- Ordinal bases, handoffs, rewind cursors, and canonical ordering operate
  independently per kind.
- The hardware-timing authority guard continues to forbid physics, auxiliary,
  reflection, and gameplay mutation paths.

### Gameplay consumers

- AIZ does not publish the main-level terrain while any direct stream remains
  pending. Direct block/event progression occurs on frame N's scan, while a
  final module parent retiring at frame N's POST publishes KosM art no earlier
  than frame N+1.
- ICZ no longer contains or depends on a 41-frame synthetic drain and starts
  its act transition from the direct queue-empty predicate.
- Rewind before, during, and after both transitions reproduces queue state and
  the same future completion once.
- ICZ preserves parent/child ownership across PRE completion, in-frame act
  reload, POST module retirement, ICZ2 publication, and rewind on both sides.
- Rewind after direct retirement but before the module POST claim restores a
  zero-occupancy, ready-unclaimed child exactly.

### Recorder

- Native and engine scanners produce matching fingerprints and boundaries for
  ordinary and module-child direct streams through one checked-in,
  language-neutral golden-vector source consumed independently by Java and
  native C# tests.
- Tests cover busy heads, every retire-plus-append count transition (including
  `1 -> 1`, `1 -> 2`, and `2 -> 3`), slot-zero mutation, shifts without zero,
  unchanged-count retire-and-append, busy-to-not-busy identical replacement,
  stable identical jobs, busy-head mutation, mixed ordinary/module
  submissions, segment handoff, bootstrap, and reset.
- Scanner goldens cover descriptor refill, extended matches, no-output
  commands, invalid backreferences, ROM bounds, and decoded length.
- Phase-loop/gap tests prove that only previously mirrored submissions emit
  completions and resets discard both ledgers atomically.
- Newly regenerated schema-2 fixtures contain canonical event ordering and
  pass committed-fixture validation.

## Post-publication blocker amendment (2026-07-29)

Installing the approved schema-2 evidence exposed a production identity error
that schema 1 could not observe. Both AIZ fixtures require direct completion
ordinal 8 with fingerprint
`sha256:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b`.
The native tuple is:

- kind `KOS_DECOMPRESSION_QUEUE`;
- ROM source `0x382626`;
- compressed length `1894`;
- destination `0xFFFFD000`;
- decoded length `4096`;
- variant `kosinski`;
- module count `1`.

The engine submits the same tuple with destination `0xFFFFD400`, producing
`sha256:953838e00d2ae0f967fd6ef24be750cc56825ac2827b3c1a53421bc60f8a608f`.
The wrong field comes from
`S3kKosRamDestinations.KOS_DECOMP_BUFFER`; every module child receives that
constant through `S3kKosModuleQueue`.

The ROM owner is unambiguous. `Process_Kos_Module_Queue` passes
`Kos_decomp_buffer` to `Queue_Kos` (`sonic3k.asm:2736-2740`), the native
four-entry FIFO contains the sign-extended longword `0xFFFFD000`, and the RAM
layout defines the `$1000`-byte buffer immediately before `H_scroll_buffer`
(`sonic3k.constants.asm:328`). `0xFFFFD400` is not the module decompression
buffer. Production must correct the shared constant to `0xFFFFD000`; recorder
or fixture normalization is forbidden.

Acceptance adds a cross-language regression for the exact AIZ module child.
The Java ROM-backed queue test must freeze all seven canonical fields and the
`c381...` fingerprint through the real module-child path. The native C#
identity test independently freezes that literal tuple/fingerprint, while the
ROM-backed AIZ differential gate proves the scanner observes it in production.
Both AIZ schema-2 replays must then pass the first direct edge. The fix is one
shared destination correction; no zone-specific branch, trace exception, or
fixture edit is permitted.

The approved ICZ fixture exposes a separate standalone-segment bootstrap gap,
not a second destination error. Its first exported edge is matching module
ordinal 158 (`9d76...`) at raw frame 35, but the engine parent still has an
unreleased direct child at ordinal 161. In the native complete run that child
was submitted and retired before the ICZ segment began, so its direct edge is
not exported into the ICZ-local stream; a fresh standalone level boot
resubmits it after segment start. Schema 2 correctly refuses to invent the
missing pre-segment completion. This amendment does not hydrate or release
that job from trace data. Standalone ICZ remains an explicitly measured
bootstrap blocker pending a separately reviewed structural-run/prefix design.

## Recorder observability amendment (2026-07-29)

Moving both approved AIZ replays beyond direct ordinal 8 exposed a native
recorder sampling gap, not another production queue identity error. The engine
correctly prepares the second AIZ intro-plane child as:

- direct ordinal `9`;
- source `0x382D96` (the first archive source plus the aligned first-module
  span);
- destination `0xFFFFD000`;
- compressed length `45`;
- fingerprint
  `sha256:4767ec1feea97155923dab11e07b8485826833eec442da46480bffb3d8a98d38`.

The approved native streams contain no matching direct edge before module
ordinal 2 (`4423...`) completes. Both native capture runners currently inspect
the FIFO only after `GpgxHost.Advance` returns from a whole emulated frame.
That sole frame-end sample can therefore miss a short-lived child:
`Process_Kos_Module_Queue` enqueues it after the prior PRE observation at a
VINT or post-objects service, and the next PRE service retires it before the
next frame-end observation. The module parent remains visible and is emitted,
leaving an authority stream that cannot prepare the engine's real child.

The pinned S&K listing provides a narrower observation boundary than a generic
RAM-write callback. `Process_Kos_Module_Queue` begins at ROM PC `0x001B28`;
its child path calls `Queue_Kos` at `0x001B42`, and execution resumes at
`0x001B46` after `Queue_Kos` has written source and destination into the
four-entry direct FIFO and incremented `Kos_decomp_queue_count`. The pinned
BizHawk 2.11 GPGX core exposes synchronous execute callbacks through
`IDebuggable.MemoryCallbacks`, scope `M68K BUS`. The native host registers one
address-filtered execute callback at `0x001B46` for the duration of each S3K
capture.

The recorder therefore separates its logical authority ledger from its latest
physical FIFO snapshot. The callback performs observation and staging only:

- it reads the count-derived occupied direct entries after the enqueue;
- if the logical direct ledger is empty (including after an earlier empty
  frame-end sample during unexported boot), it mirrors every currently
  occupied slot in physical FIFO order. The exact callback proves the final
  entry is the just-enqueued module child; earlier entries are already-pending
  direct work submitted since the empty sample. This bootstrap emits/stages no
  retirement and establishes the physical snapshot for later reconciliation;
- it accepts exactly two count shapes implied by one proven enqueue: no
  retirement (`current count = prior count + 1`) or one intervening PRE head
  retirement (`current count = prior count`);
- for no retirement, every prior physical entry must match the current prefix
  and the child is the one new tail;
- for one retirement, the prior physical suffix after the old head must match
  the current prefix and the child is the one new tail. Thus `[A,B] -> [B,C]`
  is valid. It retains `A` in the logical ledger, stages that previously
  mirrored ordinal for later PRE retirement, and mirrors `C` with a fresh
  ordinal. `[A] -> [A]` is an identical replacement, not an unchanged sample,
  because this exact callback proves one enqueue occurred;
- it rejects loss of more than one prior physical head, loss/mutation of a
  retained tail, a count outside capacity, or any shape not explained by one
  PRE retirement followed by this one exact enqueue;
- it never removes or completes a logical entry, changes prior busy-state
  evidence, writes an event, or assigns a completion boundary.

Ordinary frame-end reconciliation remains the sole direct-completion owner.
At an admitted PRE boundary it first emits and removes staged retirements in
canonical ordinal order, then reconciles the current physical snapshot. Each
completion may name only a job already present in the logical mirror,
including a child observed by the execute callback. The staged head cannot be
retired again by the subsequent physical reconciliation. An unmirrored
disappearance, double retirement, or unexplained multi-head loss remains
fatal; neither the callback nor a later module edge may fabricate a direct
completion. The exact function-local hook also excludes unrelated callers of
`Queue_Kos`, avoiding partial-state sampling at the FIFO-count RAM write.

The host callback registration is an explicit capture dependency, is disposed
when the capture ends, and remains installed across represented segment gaps.
A standard-recorder discard/reset clears the engine's direct/module ledgers
and ordinal bases but does not require re-registering the host callback.
Submission observations during unexported gaps update the run-wide ledger
without producing output. Segment writers and authority arming continue to
govern only later completion export.

Native tests must prove both VINT/post-objects short-lived-child sequences,
zero output from submission observation itself, first-callback multi-entry
bootstrap during an unexported gap, `[A] -> [C]` and
`[A,B] -> [B,C]` staging, byte-identical `[A] -> [A]` replacement with
distinct ordinals, fail-closed multi-head loss, exactly one subsequent
canonical PRE event per staged/mirrored retirement, no double retirement,
direct-PRE-before-module-POST canonical order, no event for wholly
unrepresented work, preserved ordinals across gaps/handoffs, and atomic reset
of callback-observed physical, logical, staged, and module ledgers. A GPGX
integration test must prove the
address-filtered `M68K BUS` callback fires at `0x001B46` with the incremented
FIFO visible.

This callback is hardware-timing submission observability, not a diagnostic
hook or sync/completion authority. The scoped native-harness `AGENTS.md` and
`CLAUDE.md` policy must be amended together to permit only this exact
address-filtered lifecycle callback while continuing to prohibit the Lua
diagnostic-hook families, hook-derived trace sync, and event emission from a
callback.

This correction changes recorder output, so the installed approved fixtures
are not edited in place. Fresh AIZ standard, AIZ complete-run, and any other
candidate whose timing stream changes are captured into new isolated scratch
directories. Their four-file bytes, hashes, event inventories, and mechanical
deltas receive independent review. Replacement of committed fixture bytes and
publication literals requires renewed explicit user approval. The standalone
ICZ structural-prefix blocker remains separate and is not normalized by this
recorder fix.

## Corrected-candidate replay amendment (2026-07-29)

The first candidate replay attempt incorrectly left the Surefire fork rooted
at the worktree and therefore measured the installed, superseded streams.
Candidate replay must set the fork's `user.dir` to an isolated fixture overlay.
With that correction, the newly observed AIZ intro children and module parent
are internally consistent. Immediately before module ordinal 2 is admitted at
raw frame 361, the engine parent has completed one of two modules, direct
ordinal 10 (`4767...`) is active, prepared, and ready, and the physical direct
FIFO is empty. The POST sequence runs generic timing, the runtime-art
coordinator claims the child and prepares/captures the parent, then the
recorded observer admits the module edge. No same-POST loop or boundary-order
change is required.

The corrected streams instead expose production-owner gaps:

- STANDARD AIZ stops at direct ordinal 25 (`669610...`), raw frame 3879.
  It is the sole child of StarPost Stars3 parent module ordinal 12
  (`28a69...`): source `0x187C50`, compressed length 91, destination
  `0xFFFFD000`, decoded length 96. ROM `sub_2D3C8` queues the parent at
  `0x187C4E` to tile `0x5EC`. The engine already has the correct
  `Sonic3kStarPostObjectInstance -> queueStarPostBonusArt` owner, but the
  replay route never reaches its activation. This remains an upstream
  object/gameplay divergence; timing code must not synthesize the job.
- COMPLETE AIZ stops at direct ordinal 26 (`1cea...`), raw frame 6257,
  because `AIZ1BGE_FireTransition` queues three ordinary `Queue_Kos` jobs
  before its two `Queue_Kos_Module` parents, while
  `Sonic3kAIZEvents.queueAct2KosArt()` currently queues only the two parents.
  The engine therefore submits the primary KosM child (`086520...`) at
  ordinal 26 three positions too early.
- COMPLETE HCZ stops at repeated Blastoid child ordinal 80 (`82c973...`),
  raw frame 1335, source `0x36A7C8`, compressed length 411, destination
  `0xFFFFD000`, decoded length 640, followed by parent ordinal 48
  (`f7d726...`). The initial HCZ enemy-art group already replays; the later
  repetition is a level/reload gameplay-owner gap, not a segment bootstrap or
  queue-coordinator defect.
- COMPLETE MGZ and CNZ stop at their first direct children, ordinals 131
  (`e045a5...`) at raw frame 35 and 197 (`c2b0be...`) at raw frame 36.
  `Sonic3kObjectArtProvider.scheduleEnemyKosArt()` is the production
  `Obj_TitleCardWait2 -> LoadEnemyArt` owner and already arms only from
  `onTitleCardArtRetired()`, but its zone profile switch omits MGZ and CNZ.
  These are ordinary missing production profiles, not trace-created bootstrap
  work.
- COMPLETE ICZ successfully admits initial Snowdust/StarPointer children
  234/235 and parents 158/159. It later stops at direct ordinal 236
  (`107442...`), raw frame 1629, paired with StarPost bonus-art parent ordinal
  160 (`0fbb...`). This is the same upstream StarPost gameplay divergence,
  not the earlier initial-segment bootstrap hypothesis.

### AIZ fire-transition ownership

`queueAct2KosArt()` remains the owning event boundary. It reads the AIZ2 level
load block at index 1 and must submit these five jobs in ROM order:

1. standard Kosinski source at entry `+16` to
   `S3kKosRamDestinations.RAM_START`;
2. standard Kosinski source at entry `+8` to
   `S3kKosRamDestinations.BLOCK_TABLE`;
3. standard Kosinski source at entry `+12` to
   `S3kKosRamDestinations.blockTableOffset(0xAB8)`;
4. KosM source at entry `+0` to tile `0x000`; and
5. KosM source at entry `+4` to tile `0x1FC`.

The three direct jobs must produce the frozen native fingerprints
`1cea...`, `6ab93...`, and `3b4e...` before the first KosM child
`086520...`. The event owner retains a direct queue facade, three transient
handles, and three scalar ordinals alongside the existing module state.
Rewind rebinds each direct handle by kind/ordinal, derived-facade discard
clears only handles/facades, and initialization/reset returns every ordinal to
`-1`. When both module parents are ready, native FIFO ordering already implies
that the three earlier direct jobs are ready; the event owner claims the
direct payloads before claiming the module payloads and requesting the
transition. The current ROM-backed mutation pipeline remains the terrain-data
consumer, so the timing payloads are lifecycle evidence rather than an
alternate layout mutation path.

### MGZ/CNZ LoadEnemyArt ownership

Extend the existing provider profile, preserving ROM list order:

- MGZ1: Spiker `0x36E0C4`/tile `0x530`, MGZMiniboss
  `0x36B02C`/tile `0x54F`, MGZEndBossDebris
  `0x36D572`/tile `0x570`;
- MGZ2: Spiker `0x36E0C4`/tile `0x530`, Mantis
  `0x36E2D6`/tile `0x54F`;
- CNZ: Sparkle `0x3700CA`/tile `0x524`, Batbot
  `0x3703EC`/tile `0x552`, ClamerShot
  `0x370058`/tile `0x570`, CNZBalloon
  `0x37060E`/tile `0x574`.

The offsets are S&K-half addresses from `sonic3k.lst`; the destinations and
ordering are `PLCKosM_MGZ1`, `PLCKosM_MGZ2`, and `PLCKosM_CNZ`. Add named
constants rather than embedding literals in the provider. Existing provider
rewind state already captures pending entries, submitted handle ordinals, and
the title-retirement arm. Do not add a trace bootstrap, zone exception in
shared timing code, early arming, or trace-derived submission.

Candidate-overlay replay is the acceptance fence. AIZ complete must advance
through direct ordinals 26-28 and first primary module children. MGZ and CNZ
must admit their first direct/module groups from the ordinary title-retired
provider path. STANDARD AIZ StarPost, later HCZ reload, and later ICZ
StarPost frontiers are recorded but explicitly deferred to their gameplay
owners. These are engine-only changes and do not authorize another native
recapture or fixture replacement.

## Migration and rollback

Implementation first lands schema-2 parsing and per-kind authority while
schema 1 remains loadable and module-only. Production direct ownership and
consumers then move onto the shared queue. Recorder schema 2 and selected
fixture regeneration follow. Every committed schema-1 fixture reaching AIZ
intro or the ICZ transition is removed from replay-gate status or regenerated
before delivery; loader compatibility alone is not sync compatibility. A
rollback can stop producing schema 2 and retain schema-1 module-only fixtures,
but must not restore fixed gameplay delays once the shared production queue
owns AIZ/ICZ readiness.

## Risks

- Parent/child deadlock if generic service remains globally ordered.
- One-frame-late gameplay if direct readiness is applied at `POST_OBJECTS`.
- Double modeling if module preparations retain their embedded decoder.
- Fixture breakage if schema 1 is silently reinterpreted.
- Recorder/engine fingerprint drift for aligned module child sources or the
  shared buffer destination.
- Rewind gaps in transient facades or parent-child handles.
- Large fixture regeneration scope; selected AIZ/ICZ traces must be proven
  first before fleet-wide publication.
