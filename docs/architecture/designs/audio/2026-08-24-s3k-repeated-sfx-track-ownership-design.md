# S3K Repeated-SFX Service-Phase and Physical-Track Design

**Status:** amended after native first-divergence capture
**Date:** 2026-08-24
**Branch:** `bugfix/ai-s3k-sfx-overwrite-parity`
**Base:** `4618e882bdee5ca9c9f94f8344a60833f6e20ee1`

## Problem

Sonic 3 & Knuckles boss and miniboss death sequences request `sfx_Explode`
repeatedly. In OpenGGF, each new explosion appears to terminate the preceding
one too aggressively. The Collapse effect can also be complete in isolation
but is frequently only partially audible during gameplay.

The first captured divergence is now established at the S3K service/admission
boundary. OpenGGF applies a presentation request immediately, retiring the old
SFX before the next driver pass. The shipped driver first updates every current
SFX track, then consumes the queue and installs the replacement during the
music half of the same VInt. The correction must reproduce that ordering and
the shipped S3K sound driver's request,
fixed physical SFX-track RAM, override ownership, chip-write, and PCM sequence.
It must not add sound-ID, boss, zone, or route special cases.

## Goals

1. Establish a reproducible native oracle for a real repeated-Explosion boss
   sequence and for the AIZ Collapse route with normal music and subsequent
   effects present.
2. Locate the first divergence among request publication, driver admission,
   physical-track ownership, chip writes, and rendered PCM.
3. Correct the smallest shared S3K owner that diverges.
4. Preserve native channel contention: later requests replace only the fixed
   physical tracks that their SMPS definitions claim.
5. Preserve atomic admission, rewind, observer, and timed-write guarantees.
6. Add headless tests that fail if the premature truncation returns.

## Non-goals

- Independent polyphony for repeated copies of the same SFX.
- A Collapse-, Explosion-, boss-, miniboss-, act-, or zone-specific runtime
  exception.
- Inferring runtime behavior from a BK2 frame number or trace row.
- Broad S1/S2 audio architecture work. Every S3K discovery still receives a
  source-level S1/S2 audit and explicit control tests; production changes remain
  limited to games whose shipped driver shows the same semantic mismatch.
- Replacing the existing complete-run trace subsystem or widening its runtime
  authority.
- Perfect final PCM parity for all SMPS playback in this slice.

## Sources of truth

The shipped locked-on ROM is the runtime authority:

- SHA-1 `CFBF98C36C776677290A872547AC47C53D2761D6`.
- `docs/skdisasm/Sound/Z80 Sound Driver.asm`, especially `zPlaySound`, SFX
  track initialization, channel override marking, `zKeyOffIfActive`, track
  update, and `cfStopTrack` with `fix_sndbugs=0`.
- `docs/skdisasm/Sound/SFX/B4 - Explode.asm`.
- `docs/skdisasm/Sound/SFX/59 - Collapse.asm`.
- `docs/skdisasm/sonic3k.asm`, `S3kBossExplosionController` and the boss death
  request cadence.
- Pinned GPGX/libvgm chip behavior for diagnostic chip-write and PCM evidence.

The AIZ complete-run movie is comparison input only:

- `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/s3-aiz1-2-sonictails.bk2`
- SHA-256
  `6837de0f67db7eb68f20b6f6df6a2872713a613d8b4dbc804847209c16b56e97`

If that movie does not contain a clean, bounded miniboss death interval, the
implementation may add a deterministic BK2 derived from the user-described
AIZ1 miniboss route. The movie, its SHA-256, input provenance, and the exact
selection rule must then be tracked. It may not hydrate engine state.

## Native behavior

### Fixed track residences

The S3K Z80 driver owns one shared SFX-track residence per hardware role
(`zSFX_FM3` through `zSFX_FM6`, `zSFX_PSG1` through `zSFX_PSG3`). `zPlaySound`
loads an incoming SFX definition into the residences declared by that
definition. For each declared track it initializes that residence, key-offs or
silences the physical channel as the shipped path requires, and marks the
corresponding music track overridden.

There is no ordinary same-ID polyphony. A repeated normal SFX request reloads
the same residence. Conversely, a request must not erase a sibling residence
that it does not claim merely because the old logical SFX had several tracks.
Sound IDs at and above the continuous-SFX boundary retain their separately
specified continuous behavior; that policy is not generalized to ordinary
Explosion or Collapse requests.

### Explosion

`Sound_B4` contains one FM5 track: voice 0, modulation, note `nC0` for `$1A`,
then stop. The 68K boss explosion controller stores 2 in its object wait field,
so an ordinarily updated object publishes a request every third object update.
That is not a promise of exactly three Z80 sound-driver services: lag, request
queue phase, and service phase are separate observable clocks.
Native behavior therefore repeatedly reloads and restarts the single FM5 SFX
residence. Earlier explosions do not continue as independent voices. While
requests continue, each new request may authentically cut and restart the
previous FM5 envelope. After the final request, the final loaded residence must
be allowed to run through its complete note/stop lifecycle and restore music at
the native boundary.

The acceptance oracle must therefore check both facts: repeated restarts occur,
and no extra logical cleanup truncates the final restart.

### Captured first divergence

The retained 21,309-frame AIZ movie exposes three source-authenticated
three-frame Explosion runs (134 total physical FM5 reloads). Its first Collapse
residence occupies frames 1,558 through 1,678 inclusive: exactly 121 native
services, with later SFX traffic at frames 1,640, 1,641, 1,647, 1,653, 1,656,
1,657, 1,663, 1,669, 1,672, and 1,673. An injected native `B4` run confirms one
FM5 residence:
each request reloads it, the current residence receives its SFX-track update
before the later request is consumed, and only then does `zPlaySound` key off
and initialize the replacement. The final residence stops normally.

The equivalent engine RED shows immediate replacement before the current SFX
pass. Its per-request FM5 sequence is admission preparation followed by too
little current-residence service before the next replacement. This is earlier
than any ownership-cleanup or PCM mismatch. Physical-role handoff itself stays
required, but it is not the first defect.

### Collapse

`Sound_59` declares FM3, FM4, FM5, and PSG3/noise tracks. The FM tracks form the
short staggered onset. The PSG3/noise track supplies the long modulated,
attenuating tail. A later SFX may replace whichever of those physical roles it
claims. It must leave every uncontended Collapse residence alive. A later FM5
effect, for example, must not terminate Collapse's FM3, FM4, or PSG3 tracks.

The existing isolated test proves that the Collapse bytecode can run. It does
not prove that in-game logical replacement preserves uncontended residences,
or that those residences retain locks and reach the chip after another SFX is
admitted.

## Diagnostic architecture

### Comparison-only capture

Extend the existing BizHawk headless diagnostic path rather than Lua Z80 hooks.
All new trace data remains comparison-only and must never influence production
runtime state.

For each selected native interval, capture these bounded ordered facts:

1. 68K sound request publication: request ID and ordinal.
2. Z80 queue consumption and `zPlaySound` admission ordinal.
3. Each initialized SFX residence: hardware role, source pointer, track base,
   and request ordinal.
4. Residence termination or replacement: old request ordinal, new request
   ordinal, reason, and hardware role.
5. YM2612 and PSG writes with source cycles and physical channel.
6. Per-chip PCM and final mixed PCM in a fixed window beginning before the
   first relevant request and ending after the final native residence stops.
7. Terminal count/digest plus zero fault and zero overflow.

The native patch must be diagnostic-only, reproducible from pinned GPGX, and
covered by focused native self-tests. Capture A and B into fresh agent-scratch
directories; require byte-identical canonical output before publication.

### Engine observation

Use or narrowly extend the existing request, admission, contention, service,
chip-write, and presentation observers. The engine-side record uses semantic
request and physical-role ordinals; it does not use a native frame number as a
runtime key. PCM evidence is generated only after production playback executes.

### First-divergence rule

Compare the following boundaries in order:

1. **Request:** count, ID, and cadence.
2. **Admission:** which definition and track roles each request loads.
3. **Ownership:** old/new residence per physical role and music override state.
4. **Chip:** ordered register/value writes and PSG writes per residence.
5. **PCM:** per-chip and final-output onset, activity, and terminal window.

The implementation correction belongs at the first boundary that differs. A
later mismatch must not be patched while an earlier mismatch remains.

## Runtime service/admission contract

### S3K queue phase is authoritative

For the typed locked-on S3K policy, a presentation SFX request is staged as an
immutable pending admission. At the next driver boundary, `SmpsDriver` executes
one atomic `zUpdateEverything` analogue in this order:

1. service the SFX sequencers that were active at boundary entry;
2. apply the pending music/jingle queue decision;
3. commit surviving pending SFX admissions in source order, including priority,
   preparation writes, residence replacement, and override changes;
4. service music with the post-admission override state.

Newly admitted SFX tracks are not included in step 1 and first run at the next
driver boundary. A replaced residence therefore gets its final current-boundary
service before retirement, exactly as the ROM does. S1/S2 retain their existing
typed queue/service policies.

The pending queue models the shipped `zSFXNumber0`/`zSFXNumber1` input cells,
not an unbounded host command list. The 68K `Play_SFX` contract is exact:

- an ID equal to slot 0 is ignored;
- an empty slot 0 receives the request;
- otherwise slot 1 receives (and may overwrite) the request;
- at the next locked-on driver boundary, both cells are consumed in slot order
  after the current SFX pass and then cleared.

The music cell is logically before both SFX cells. An ordinary pending music
change is applied before SFX admission. During the active 1-up/fade-to-previous
branch, a queued 1-up or an ordinary music request clears both SFX cells exactly
as `zUpdateMusic.clr_sfx` does; no admission or chip callback is published for
those discarded entries. Stop/fade barriers retain their existing typed
discard rules. This is tested at the driver and presentation-command boundaries
rather than inferred from a particular jingle ID inside shared code.

Continuous-SFX extension and music commands retain their existing typed
contracts; this slice changes only ordinary SFX admission phase. A pending
request owns an immutable sequencer description, source metadata, and request
ordinal, but owns no physical role and emits no admission/chip callback before
consumption. Request-publication observation may occur when a cell changes;
admission/contention observation occurs only when the cell is consumed.

The pending queue is bounded to exactly two cells, snapshotted, restored, and
covered by the same outer driver-service transaction as the SFX pass and
admissions. A failure in
service, capacity preflight, admission validation, observer publication, or
source timing restores the pre-boundary active set, pending queue, locks,
claims, timelines, chip state, and ordinals. Reset/stop barriers discard pending
requests according to their existing generation contract.

The existing typed `SfxStartTiming.NEXT_DRIVER_UPDATE` is the policy owner for
this behavior. `SAME_DRIVER_UPDATE` keeps the current S1/S2 immediate admission
path. No game-name test is permitted in `SmpsDriver`.

This cross-game ruling is part of the acceptance contract for every later
audio discovery in this slice. A discovery must cite the corresponding S1 and
S2 driver boundary and add or retain a control before an S3K correction can be
accepted. “No S1/S2 production change” is valid only when those source paths
prove that their existing typed policy is already accurate.

### Ownership remains physical

`SmpsDriver` may keep logical sequencers as implementation objects, but hardware
ownership is defined per physical SFX track role. The identity map keyed by SFX
ID is lookup and lifecycle bookkeeping only; it is not independent hardware
authority. In retail S3K, one sound ID resolves to one fixed header, so a
repeated ordinary ID declares the same full role set on every admission. Tests
must not invent a mutable same-ID header or use a same-ID subset case to justify
a retail production correction.

Admission produces a bounded action for each incoming track:

- `CLAIM_EMPTY`: claim an unowned physical role.
- `REPLACE_RESIDENCE`: replace the current SFX track in that same physical role.
- `REJECT`: fail admission for a source-authenticated priority or malformed
  definition reason.

The action set is immutable and complete before any live state changes. No
whole-sequencer removal may implicitly release a role absent from this set.

### Replacement

For `REPLACE_RESIDENCE`, admission atomically:

1. records the outgoing residence and incoming track identity;
2. performs the shipped channel preparation/key-off/silence writes;
3. installs the incoming track as the role's sole owner;
4. retains the music override continuously, without an intermediate restore;
5. retires only the outgoing logical track;
6. retires the outgoing sequencer object only when it has no surviving tracks
   or other native lifecycle state.

Unreplaced sibling tracks of a different partially overlapping SFX remain
active, retain their locks, remain in snapshots, and continue producing
writes. Repeating the same ordinary ID reloads the same retail header and full
declared role set; it receives no special runtime branch.

### Completion and restoration

When a physical SFX residence stops naturally, it releases exactly that role.
Music restoration occurs only if no newer SFX residence owns the role. An old
logical sequencer reaching completion may not release a transferred/replaced
role. Whole-sequencer cleanup must be idempotent after per-track releases.

Already committed YM/PSG timeline entries keep the lifetime defined by the
existing service-transaction contract. Ordinary track replacement does not
retroactively erase a hardware write already issued by the emulated driver;
only an existing generation barrier may discard committed entries.

### Atomicity and rollback

The prepared action set, track state, role locks, music overrides, logical
identity maps, source-timed queues, PSG publication journal, observers, and
diagnostic ordinals participate in the existing admission transaction.

Any malformed definition, duplicate physical role, capacity failure, observer
poison, or source-timing failure aborts before publication and restores the
exact pre-admission snapshot. Observer callbacks are published once after
commit; chip callbacks remain drain-bound.

### Bounds

- At most the profile's existing maximum SFX tracks may be admitted.
- Each physical role appears at most once in one incoming definition.
- The action count equals the incoming track count.
- Existing YM/PSG queue and whole-service capacity limits remain hard.
- The two pending SFX cells are a source-derived hard cap. Slot-1 overwrite and
  slot-0 duplicate suppression are tested explicitly; a third logical queue is
  not introduced.
- All counters and ordinals use checked arithmetic; overflow fails closed.

No limit is increased without a source-derived maximum and N/N-1 tests.

## Required evidence and tests

### Native oracles

1. **AIZ1 miniboss Explosion sequence** (preferred) or AIZ2 boss if the AIZ1
   interval cannot be isolated:
   - exact 68K object-update/request cadence and, separately, exact Z80 queue
     consumption/admission cadence;
   - one FM5 residence per request;
   - exact replacement/key-off/voice/key-on ordering;
   - no music restore between repeated requests;
   - final request reaches native stop/restore;
   - per-chip and final PCM terminal windows.
2. **AIZ Collapse gameplay interval with music enabled and the real later
   effect(s):**
   - exact four initial residences;
   - exact later contention by physical role;
   - every uncontended residence remains owned and continues writes;
   - PSG3/noise remains active through its native terminal unless a captured
     later request authentically replaces PSG3;
   - per-chip and mixed PCM activity through the terminal window.

The oracle fixtures store provenance, input hashes, counts, ordered SHA-256s,
and compact bounded observations. Raw captures remain in agent scratch.

### Strict RED tests before production edits

1. A real different-ID partial-overlap test proving that only the captured
   physical-role intersection is replaced. A test-only definition may be used
   only for malformed/atomicity poison; it may not establish retail ownership
   semantics.
2. A repeated `EXPLODE` driver test proving current-residence service precedes
   each captured Z80 admission, then complete final lifecycle with active
   music and no intermediate restore.
3. A committed real-gameplay headless test that drives an actual AIZ1 miniboss
   or AIZ2 boss through its death sequence. It must retain active music and
   cover 68K object updates, request publication, queue consumption, repeated
   Z80 admission, physical ownership, chip writes, mixed PCM, and a terminal
   window after the final explosion. The test separately asserts object-update
   request cadence and captured request-to-admission service cadence.
4. A Collapse-plus-real-later-SFX test proving the native role intersection and
   the continued PSG/FM sibling writes and PCM.
5. Snapshot/restore after a partial replacement, followed by byte-identical
   ownership, writes, PCM, and completion.
6. Capacity N/N-1 and observer-poison retry proving no prefix publication,
   phantom callbacks, duplicate callbacks, or ordinal drift.
7. Unsupported/malformed definitions fail closed.

### Controls

- Existing Blue Sphere, Collapse/Dash, source-timed YM, rewind, observer, and
  presentation suites remain green.
- S1 and S2 contention/admission tests remain byte-identical or explicitly
  prove that the correction is gated by the typed S3K ownership profile.
- The existing isolated SoundTest path remains a diagnostic convenience, not
  an acceptance oracle.
- Full JDK 21 three-ROM results are compared by exact failing test identity and
  failure/error status against an updated integration baseline.

## Implementation boundary

The proven owner is `SmpsDriver`'s typed S3K service/admission phase. The
implementation may add a bounded pending-admission queue and its snapshot,
transaction, capacity, and observer state. Existing physical-role handoff and
per-track cleanup change only if later evidence finds a second divergence.
No implementation may branch on `EXPLODE`, `COLLAPSE`, a boss, a zone, or a
captured request ordinal.

## Acceptance

The change is accepted only when:

1. Both native A/B oracle captures are deterministic and source-authenticated.
2. The first divergence is documented with before/after evidence.
3. Every new focused RED is green for the source-owned reason.
4. Native/engine request, admission, ownership, chip, and bounded PCM assertions
   all pass for repeated Explosion and in-game Collapse.
5. Rewind, rollback, observer, capacity, and cross-game controls pass.
6. The full three-ROM suite introduces no new or worsened red identity.
7. A clean exact-HEAD JAR is built on JDK 21.
8. Human listening confirms the AIZ1 miniboss or AIZ2 boss explosion sequence,
   the user-described AIZ rock Collapse route, and nearby music/SFX controls.

Merge and push remain deferred until automated evidence is green and the user
confirms the listening result.
