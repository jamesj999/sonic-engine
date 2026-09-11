# Cross-game hardware-timing trace contract

## Status

Approved after independent review. The symptom-first direction, the narrow
authoritative hardware-completion exception, and the schema-2 S3K direct
Kosinski extension are user-approved. This document inventories
timing-sensitive Mega Drive activities used by Sonic 1, Sonic 2, and Sonic 3
& Knuckles and defines the minimum authority a dedicated hardware-timing
input stream may have over them. The direct-queue implementation details are
owned by
[`2026-07-28-s3k-kos-decompression-queue.md`](2026-07-28-s3k-kos-decompression-queue.md);
this document remains the cross-game authority boundary.

### 2026-08-03 v5 grammar supersession

The trace-v5 consolidation supersedes the schema-selection mechanics below,
without changing this document's authority boundary. Current metadata declares
only `trace_schema: 5`; `hardware_timing_schema` is removed. Presence of
`hardware_timing.jsonl` enables the one current registry. Every event still
admits only matching, prepared, production-submitted ROM work after kind,
ordinal, stable submission fingerprint, and service-boundary checks succeed.

The registry covers three kinds, and is closed to any kind not listed here:

| Wire name | `HardwareWorkKind` | Owning pipeline |
|---|---|---|
| `kos_module_queue` | `KOS_MODULE_QUEUE` | S3K resumable Kosinski/KosinskiM module queue |
| `kos_decompression_queue` | `KOS_DECOMPRESSION_QUEUE` | S3K direct Kosinski queue |
| `nemesis_plc_queue` | `NEMESIS_PLC_QUEUE` | Sonic 1 `RunPLC` arming edge (`docs/s1disasm/sonic.asm:1379`) |

`nemesis_plc_queue` records the moment the ROM accepts the Nemesis PLC FIFO
head for decompression -- the *arming* edge, not the delivery of any decoded
art. Every pattern the entry later decompresses is still produced natively by
the production PLC pipeline, so this kind stays inside the authority boundary
this document already defines: it moves only *when* an engine-submitted arm
becomes visible, never *what* the arm loads. `S1ConditionalTraceOutputFileNames`
(`tools/bizhawk-headless/src/Program.cs`) publishes the S1 stream only when the
capture actually observed an edge, so an S1 fixture that records none keeps its
historical three-file inventory rather than gaining an empty stream.

`HardwareWorkKind` is the normative list;
`TestS1S2PlcComparisonOnlyGuard.timingKindRegistryIsClosedToUndeclaredWork`
pins the enum to exactly these three, so adding a fourth is a deliberate,
reviewed contract change rather than an implementation detail.

Historical schema-1/schema-2 names and recorder stamps later in this document
describe the evidence and decisions that led to v5. They are not live parser
choices or compatibility obligations. `recorder` and `recorder_version` are
opaque provenance; `lua_script_version` was removed rather than renamed.

The governing principle is:

> Record the smallest scheduling outcome observable to the game, not the
> hardware cause that produced it.

Most expensive hardware work therefore does not need its own trace event. If
its only gameplay-visible consequence is that the 68K main loop missed a
frame, the existing lag-row contract is sufficient. A new completion event is
reserved for work that remains pending while the main loop continues and the
ROM explicitly polls a hardware-owned readiness gate.

### 2026-08-20 coverage status: contract, implementation, fixtures

Three questions have three different answers, and conflating them has already
mis-briefed at least one round. Keep them apart.

**Contract scope -- cross-game.** Unchanged since this document was approved.
Recorded timing *may* delay readiness in the S1 Nemesis PLC, S2 DPLC, and S3K
Kosinski pipelines, under the single authority boundary defined here.

**Implementation -- S1 and S3K.** Both ends are live for two of the three:

- S3K: `S3kKosModuleQueue` and `S3kKosDecompressionQueue` submit
  `KOS_MODULE_QUEUE` / `KOS_DECOMPRESSION_QUEUE`; recorded by
  `S3KTraceCaptureRunner` and `S3KCompleteRunCaptureRunner`.
- S1: `Sonic1PlcArmTiming` submits `NEMESIS_PLC_QUEUE` and
  `Sonic1PlcService.ownsTimedLoopTailArm()` gates the loop-tail arm on
  `isRecordedAuthority()`; recorded by `S1PlcHardwareTimingObserver`, wired
  into both `S1TraceCaptureRunner` and `S1RunCaptureRunner`.
- S2: **not implemented.** No source under `game/sonic2/` references
  `HardwareWorkKind` at all, and no S2 recorder constructs
  `HardwareTimingEventEngine`. The S2 DPLC pipeline is inside the contract's
  permitted scope and outside its built scope. Do not describe it as available.

**Fixture coverage -- S3K only.** Every committed `hardware_timing.jsonl` in
`src/test/resources/traces` is under `s3k/` (272 of them, plus 2
`hardware_timing_interstitial.jsonl`). No committed S1 or S2 fixture carries
one.

The consequence for S1 is specific, and is not the same thing as the mechanism
being absent. Recorded admission is installed only by
`GameplayModeContext.activateRecordedHardwareAdmission()`; with no stream, every
kind stays at `HardwareReadinessAdmissionPolicy.LIVE`,
`Sonic1PlcArmTiming.isRecordedAuthority()` returns false, and the arm is
released by the same boundary that prepared it -- exactly the behaviour that
predates the timing port. So the S1 path today is **implemented but dormant**:
it is exercised by unit and guard tests, and by no committed trace fixture.
A round debugging an S1 PLC divergence should reason about the native service
model, not about a recorded edge, until an S1 fixture carrying a stream lands.

Recorded timing is not the first resort for S1 in any case: a divergence that
looks like elapsed hardware cost is usually a counted ROM wait loop in the
wrong place (see the `plc-system` skill's S1 `segment_start - 26` load-pair
invariant).

### 2026-08-21 unrepresented spans: readiness falls back to the native budget

The readiness shape has a boundary the original text did not state, and both S1
and S3K deadlocked on it before it was written down.

`HardwareTimingReplayPort.enterUnrepresentedGap` contracts that production
hardware work may continue while row authority is deactivated, but that no
recorded completion edge may be applied until the next `beginRawFrame`. The
recorder discards anything observed outside a segment's rows, so **no completion
edge can ever exist for work submitted in such a span**. A recorded-admission
kind that waits for one there waits forever. Measured twice, in two games:

- S1: 214 consecutive blocks deadlocked the title card, which loops until the
  PLC buffer empties (`docs/s1disasm/sonic.asm:2840-2841`). Fixed at the
  consumer gate by `51ef66b30`.
- S3K: 38400 consecutive blocks deadlocked the AIZ special-stage-return title
  card, waiting on `KOS_MODULE_QUEUE#11` with `recordedAuthorityRepresentsRow()`
  false on every one of them. The ROM's own art wait drains through
  `Process_Kos_Module_Queue`, one module per call
  (`docs/skdisasm/sonic3k.asm:2726-2790`).

Two games reaching the same wall through two different kinds made this a
property of the port rather than of either consumer, so the rule now lives in
`HardwareTimingService` and applies to **every** recorded kind:

1. **Membership is fixed at submission.** The service records which handles were
   submitted while row authority was deactivated. It is not a property of the
   moment of release, so work submitted inside coverage still blocks on its
   recorded edge even if the run later leaves coverage, and leaving coverage can
   never release work the recorder did count.
2. **Release still costs ROM service frames.** Unrepresented work is serviced on
   the native work budget -- the load-time profile is activated and advanced and
   the job is released in FIFO order, exactly as a live run would do it. It is
   *not* admitted instantly. This is load-bearing rather than tidy: an
   implementation that admitted readiness directly at the consumer's gate raised
   `IllegalStateException: hardware work is not prepared: KOS_MODULE_QUEUE#11`,
   because a KosM parent is only prepared once its modules have decompressed
   across frames. An edge still cannot force preparation or decoder progress.
3. **Inside coverage nothing changes.** An unmatched job still blocks and still
   raises, so a genuine kind/ordinal/fingerprint/boundary mismatch remains a
   hard failure.

This stays inside the readiness-release shape hard rule 4 permits: it changes
only *when* real, engine-created work becomes ready. It creates no work, carries
no value, calls no gameplay owner, and keys on no frame index, zone, route or
game name. `TestHardwareTimingAuthorityGuard` covers it.

#### Open gap: identity return is single-ordinal, and the S3K case is a batch

An unrepresented submission still allocates an ordinal, and ordinals are the only
counter the engine and the recording share. `releaseUnrepresentedIdentity` returns
that borrowed ordinal after production claims the result, and
`Sonic1PlcArmTiming.releaseArm` calls it whenever its job was submitted
unrepresented -- whichever path admitted readiness.

**It does not generalise to a batch.** The method accepts only the most recently
allocated ordinal of its kind, and only when no other job of that kind is pending;
both guards exist so that no handle is left numbered on a stale axis. The S3K
title card submits four KosM modules and claims them **11, 12, 13, 14 ascending**,
so none of them can use it as written. Those four therefore keep the ordinals they
borrowed.

Nothing is being papered over on the fixture that surfaced this: after the release,
`s3k-tails-full-chain-all-emeralds` shows no ordinal or fingerprint mismatch and no
pending submissions at end of run. But that is evidence from one recording, and the
bar is any BK2. A batch-shaped identity return -- returning a contiguous run of
ordinals in reverse once all of them are claimed -- is an open design question, not
a settled one, and it is the first thing to examine if a later fixture shows an
S3K KosM ordinal one ahead of the recording.

#### 2026-08-21 measured: the predicted tell fired, and it is a different defect

`s3k-tails-full-chain-all-emeralds` now fails at the first special-stage return
with

```
IllegalStateException: recorded ordinal span does not begin at the production
cursor for KOS_DECOMPRESSION_QUEUE: production next=20, recorded span=16..31
```

This is **not** the batch identity-return gap above. It was established by
fingerprint, not by ordinal arithmetic, and the two hypotheses give opposite
answers.

The engine's four unrepresented KosM submissions across this interstitial are
`KOS_MODULE_QUEUE#11..14` with `KOS_DECOMPRESSION_QUEUE#16..19` as their module
children -- the shape the open gap predicts. But their submission fingerprints
are `fbfc78d4 / 513a9a90 / d24be135 / 5dc423fa`, and the recording carries that
exact batch twice: at `bk2_frame` 630-637 as the run's opening level load
(interstitial ordinals `0..3`), and again at `bk2_frame` 6093-6100 as
`kos_decompression_queue 21..24` / `kos_module_queue 15..18`. So the recorder
*did* count this work. Nothing was borrowed and left unreturned, and returning
these ordinals would move the cursor away from the numbers the recording gives
them, not toward them.

What the engine never submits is the batch the recording places *first* in the
same interstitial, at `bk2_frame` 5562-5573: `kos_decompression_queue 16..20`
(`589a478d / 41b5f251 / 7e6020e6 / dc855aca / f88214ef`) with
`kos_module_queue 11..14`. Those nine fingerprints occur fifteen times each in
`hardware_timing_interstitial.jsonl` -- once per special stage -- and zero times
in any engine submission. They are the special-stage exit/results art load, which
the engine does not model; segment `ss` ends at the special stage's own rows and
segment `aiz_2` opens at `bk2_frame` 6221, so both batches fall in the gap
between them.

The defect is therefore structural to the interstitial index rather than to
identity return. `HardwareTimingInterstitialSpans` states its premise in its own
class comment -- *"Production replaying the run does not reproduce those
submissions"* -- and a single `RecordedOrdinalSpan(first, last)` per boundary per
kind can only express *skip this contiguous run*. At the pre-run interstitial the
premise holds exactly and the mechanism works: production submits nothing there,
and its ledger opens at `kos_decompression_queue#11` / `kos_module_queue#6`
where the recording resumes. At a special-stage return the premise fails:
production submits the level reload *inside* the recorded span, interleaved
between recorded work it does not submit (`16..20` skipped, `21..24` submitted,
`25..31` skipped). The assertion is correct and is reporting this honestly.

Two ways out, and they are not equivalent:

1. **Implement the missing loads.** If the engine submitted the special-stage
   exit art, its ledger would reach `21..24` on its own and no contract change
   would be needed for that block. This is ordinary engine work under rule 1,
   with no new authority. It does not by itself prove the boundary is then
   clean -- the trailing `25..31` block would still have to be either submitted
   or expressible as a span -- but it removes the interleaving that makes the
   current shape inexpressible.
2. **Generalise the span to a set.** Permitting production submissions inside a
   recorded interstitial requires deciding *which* recorded ordinals production's
   own submissions correspond to, and the only available discriminator is the
   recorded submission fingerprint. That is recorded data selecting production
   numbering, which is a widening of what this contract permits, not a change of
   how it is implemented. **It is a design decision for the user and is not taken
   here.**

The open gap in the preceding section remains open and unobserved: no fixture has
yet shown an S3K KosM ordinal one ahead of the recording for the reason that
section describes.

Two further facts, measured by bypassing the span check locally so the run could
continue past the handoff (diagnostic only, never committed; the physics
divergence such a run reports is an artefact of the bypass and is not a frontier):

- **Both S3K Kosinski kinds are skewed, not just the reported one.** With the
  check relaxed, the handoff reports `KOS_MODULE_QUEUE cursor=15 span=11..20` as
  well as `KOS_DECOMPRESSION_QUEUE cursor=20 span=16..31` -- 4 short and 5 short
  respectively, the exit-art batch in each kind. `KOS_DECOMPRESSION_QUEUE` is
  merely the kind the `values()` iteration reaches first.
- **Implementing the exit art alone would not close the boundary.** Past the
  handoff the engine's next submissions are `kos_decompression_queue 32,33,34`
  (`c3e8ddd3 / 2bed3f7b / 055a7ca7`) and `kos_module_queue 21,22,23`
  (`65c8c371 / 5c387ee7 / 4728f00c`) -- the same fingerprints it submits at
  `decomp#11..13` / `module#6..8` at the start of the run, i.e. the segment's own
  in-segment art. The engine therefore never submits the recording's trailing
  interstitial block, `kos_decompression_queue 25..31` / `kos_module_queue 19..20`
  (`149e63bc / 912aa214 / ae73908a / 77708e82 / 78b85320 / 4c509876 / 7b1b550d`
  and `925beedb / d713465c`). That block is the same one the recording places at
  `decomp 4..10` / `module 4..5` in the pre-run interstitial, which the engine
  also does not submit -- there it is absorbed by the initial ordinal base
  instead. So the interstitial contains *two* unmodelled blocks with the engine's
  level reload between them, and the skip/submit/skip shape survives implementing
  only the first. Option 1 closes this boundary only if both blocks are modelled.

Two tests in `TestHardwareTimingService` are red on `189acc824` independently of
this: `anIdentityIsNeverReturnedWhileRowAuthorityRepresentsARow` and
`recordedAdmissionStartsOnlyBeforeFirstSubmissionAndEndsOnlyWhenEmpty`. Both sit
on `releaseUnrepresentedIdentity`, so they are worth clearing before anyone
builds on that method.

### Historical pre-v5 wire format (not live)

The schema-1/schema-2 grammar, selectors, and recorder stamps described later
in this document are retained as migration evidence only. They are not parser
fallbacks or compatibility obligations. The sole live contract is v5: an
absent timing file means no recorded timing port; a present file, including an
explicitly empty file, uses the complete module-and-direct registry. No
admission policy is inferred from which event kinds happen to be present.

### 2026-08-02 suppressed-row boundary clarification

Schema-2 capture can observe a loop-tail completion on a physical row whose
`Level_frame_counter` remains held. In that case the CPU has already traversed
`Process_Kos_Queue` and reached `Wait_VSync`, but the stored row owns only the
resulting VBlank closure and no gameplay dispatch. A `pre_main_loop` edge
explicitly recorded on that row is structural evidence for the completion's
deferred visibility. Replay may expose that exact edge to the timing observer
after the row's VInt closure, without executing another production service,
main loop, object scan, or producer.

When that admission succeeds, the suppressed-row closure completes only the
production coordinator's post-service half for `pre_main_loop`. This is the
ordinary queue-owned observation of newly ready work: for S3K it retires the
ready direct FIFO head so the KosM parent can claim it at its next
`post_objects` state step. Replay does not repeat the coordinator pre-step or
`HardwareTimingService.service`, and therefore cannot create work, advance
preparation, or invent a consumer. The timing observer returns only whether it
consumed an exact edge; it never receives or calls the coordinator itself.

The ordinary admission operation proves its boundary from the production
service's `lastServicedBoundary`. That proof is intentionally unavailable after
the suppressed row has serviced VInt. The recorded-completion authority may
therefore expose one distinct suppressed-row admission operation. It accepts
only `pre_main_loop`, and only the replay port may invoke it after proving that
the next unconsumed edge belongs to the latched current raw row. It bypasses
only the stale `lastServicedBoundary` equality; it reuses every pending-head,
kind, ordinal, fingerprint, preparation, release, and deduplication check.
Source guards confine the operation to the replay port and confine the port's
suppressed-row entry to the stateless timing observer.

This is not elapsed-row reconciliation: advancing to the next raw row never
authorizes a stale edge. An ordinary lag row without a current-row
`pre_main_loop` edge remains VInt-only. The exception still releases only an
already-submitted, already-prepared FIFO head after kind, ordinal, fingerprint,
and boundary all match; missing, unprepared, reordered, mismatched, or
gap-crossing work fails closed.

This clarification does not authorize a module-parent completion merely because
the recorder first observes its RAM retirement on a held-counter row. The
schema-2 native recorder currently classifies every newly observed module-head
retirement from a duplicate `Level_frame_counter` sample as `vint_service`.
That classifier and the published `6.40-s3k-completerun` timing streams predate
the production loop-tail phase correction in `ddaf8e152`. If such an edge names
a parent that production has not prepared, replay must fail closed. The next
owner is an audited native-recorder observation-row/service-row attribution
review. If it finds the capture attribution stale, correction requires a
separately approved fixture publication; if it validates the current stamp, a
broader partial-CPU-prefix replay contract requires its own design and review.
Neither outcome authorizes timing authority to run `Process_Kos_Module_Queue`,
backdate an edge ad hoc, or prepare the parent.

## Goals

- Reproduce hardware-dependent scheduling without copying gameplay state from
  the trace.
- Reuse the established lag-frame model wherever it completely describes the
  observable result.
- Keep replay independent of host decompression, rendering, I/O, and CPU
  speed.
- Preserve strict comparison of gameplay state, including ring count, after
  the relevant scheduling outcome is reproduced.
- Fail loudly when the engine and trace disagree about which hardware work
  exists.

## Non-goals

- Cycle-accurate 68K, Z80, VDP, or DMA emulation.
- Recording compressed byte progress, DMA byte counts, VDP FIFO occupancy, or
  host execution duration. A stable submission fingerprint is comparison
  evidence for independently submitted work; it is not a trace-supplied work
  descriptor.
- Allowing a trace to set rings, positions, routines, object slots, event
  flags, or any other gameplay state.
- Adding zone-, route-, trace-, or frame-specific scheduling branches.
- Making visual-only timing release-blocking in physics traces.

## The five replay contracts

Every timing-sensitive activity must reduce to one of these contracts.

### 1. Main-loop admission

The existing trace `lag` outcome says whether the gameplay main loop ran.
When it did not run, replay services the ROM-equivalent interrupt work,
retains/re-samples controls according to the game's lag policy, and does not
run gameplay.

The cause of the missed frame is deliberately absent. Decompression, map
construction, DMA setup, Z80 bus arbitration, or any other long 68K task all
produce the same replay outcome when their only observable consequence is a
lag frame.

### 2. Execution phase

Some physical frames use a non-level VInt/main-loop regime: fade, lag,
special-stage, controller/DMA, title or another structural phase. Replay
models the phase's observable scheduling semantics, not the low-level work
that selected it.

Phase evidence must be structural. It may come from mode and lifecycle state
already recorded for comparison, but must not be inferred from a fixture
name, route, position, animation, or a convenient row shape.

### 3. External work completion

This is the sole new authority proposed by this design. It applies when:

1. production code has submitted real ROM-backed work;
2. the ROM exposes a readiness value polled by ordinary main-loop code;
3. the main loop can continue while that value remains pending;
4. completion timing depends on hardware work not represented by `lag` or the
   execution phase; and
5. readiness can affect a gameplay-visible lifecycle.

The dedicated timing stream may release a matching pending job. Physics CSV
and auxiliary events remain comparison-only and have no access to this port.
The timing stream may not perform the consumer's response.

### 4. Hardware-relative initial base

Persistent values such as `V_int_run_count` may depend on power-on history
that a segment trace does not replay. The trace may seed such a value once at
a structural segment boundary. The engine then advances it natively.

The same rule applies to hardware-work identity when a standalone segment
begins after earlier work in its captured structural run. Before the first
production submission of a kind, the timing schedule may establish that
kind's first recorded ordinal as the production ledger's initial ordinal.
This base is restored by rewind. It is not reapplied at a run-chain handoff,
cannot renumber an existing submission, and does not change preparation,
readiness, payload, or gameplay state. Edges for one kind must be contiguous
within one timing stream. Exported edges across structural segments may have
ordinal gaps where native phase work intentionally produced no completion
edge; the production submissions and claims must advance the ledger through
that gap. Handoff never seeds it. If those native submissions are absent, the
later edge fails its ordinary engine-identity admission check.
An empty initial run schedule does not infer a base from a later segment:
when ordinal continuity depends on earlier production submissions, those
submissions must occur. A reviewed nonzero initial base must be established
explicitly at initial run installation, before production submits that kind.

This is initial-state reconstruction, not recurring synchronization.

### 5. Diagnostic-only presentation timing

Raster effects, palette publication, sprite dropout, audio presentation, and
other visual/audio-only results remain diagnostic unless the ROM exposes a
RAM readiness gate that blocks or branches gameplay. A physics trace does not
gain authority merely because the presentation differs.

## Cross-game inventory

The table records the symptom that matters to replay. “Lag” means no
cause-specific event should be added. “Phase” means existing or strengthened
structural scheduling. “Completion candidate” means the mechanism must pass
the eligibility gate above before receiving trace authority.

| Activity | Sonic 1 | Sonic 2 | Sonic 3 & Knuckles | Replay contract | Current disposition |
|---|---|---|---|---|---|
| Long synchronous decompression or level initialization | Physical VInts occur while the main loop is unavailable | Explicitly visible in special-stage and level-start lag rows | Present during black-screen level loads and other initialization | Lag | S1/S2 substantially covered; audit S3K lag capture parity |
| Normal PLC processing | `RunPLC` services a persistent queue while ordinary loops and some objects poll it | Normal/fade/special handlers service a persistent queue; ordinary gameplay can poll it | PLC/AniPLC coexist with later Kos queues | Native deterministic service queue; the S1 *arming* edge is an approved external completion | S1: `NEMESIS_PLC_QUEUE` records only the FIFO-head arming edge -- still do not record individual PLC entries or decoded art. S2: unbuilt; audit service cadence and polled gates |
| Direct Kosinski decompression queue | Not used as the S3K-style owner | Not used as the S3K-style owner | `Kos_decomp_queue_count` independently gates AIZ intro and ICZ act-transition progression | External completion in S3K schema 2 | `KOS_DECOMPRESSION_QUEUE` is authoritative only under the reviewed schema-2 registry |
| Kosinski/KosinskiM module queue | Not used as the S3K-style owner | Not used as the S3K-style owner | Resumable module queue remains pending while results/title/event code continues polling | External completion | `KOS_MODULE_QUEUE` is the first approved authoritative kind |
| Nemesis, Enigma, Saxman, raw map decompression | Normally synchronous from gameplay's point of view | Normally synchronous; special-stage work produces lag rows | Normally synchronous unless wrapped in an explicit deferred queue | Lag | No codec-specific trace authority |
| VDP DMA transfer and FIFO pressure | Can consume VInt budget or contribute to lag | Can consume VInt budget; controller/DMA VInt is structurally distinct | Queued art, palette, tile and plane transfers may have completion flags | Lag, phase, or completion candidate | Record a completion only when the ROM polls a gameplay-visible fence |
| Foreground/background plane drawing | Usually initialization/presentation | Special-stage name-table and draw pipeline has structural waits | Some background-event routines wait for a draw/refresh result | Phase or completion candidate | Inventory each polled RAM fence; presentation alone is diagnostic |
| Animated tile/DPLC uploads | VInt/frame-counter driven | VInt/frame-counter driven | AniPLC plus custom DMA updaters | Native deterministic counter; diagnostic presentation | No completion authority unless gameplay polls readiness |
| Palette fades | Fixed VInt loops | Fixed VInt loops with handler-specific PLC service | Fixed VInt loops around transitions | Phase | Model the loop; do not synchronize the final palette value |
| Palette cycling | Counter/table driven | Counter/table driven | Counter/table/event-flag driven | Native deterministic counter | Visual comparison only unless a gameplay routine reads the same flag |
| Controller sampling | Physical sample and logical word publication depend on VInt/main-loop admission | Lag and special-stage paths distinguish sampled and consumed controls | Same class of physical/logical publication | Lag and phase | BK2 supplies buttons; scheduling supplies when they become logical input |
| Persistent VInt counters and parity bytes | Object, animation, sound-gate and demo cadence | Object, animation and special-stage cadence | Object cadence, bonus-stage entropy and ongoing Slots reads | Initial base plus native advancement | Existing metadata/base work is the precedent |
| Software RNG seeded from hardware-relative time | Seed/history may depend on VInt history | Seed/history may depend on prior session work | Gumball and Slots explicitly consume VInt-derived state | Initial base plus native advancement | Never synchronize individual RNG calls |
| Ordinary object scheduling | Deterministic SST scan | Deterministic SST scan | Deterministic SST scan | Native gameplay | Hardware timing has no authority once main-loop admission is known |
| Special-stage draw/update pipelines | Rotation/object work follows special-stage VInts | Drawing index, duration wait, controller/DMA wait and fades are explicitly phased | Special-stage/bonus-stage modes have their own VInt-derived counters | Lag and phase | S2 is the strongest existing model; audit S1/S3K against it |
| H-Interrupt/raster effects | Water/scroll presentation | Water splits and per-line effects | Water splits, window-plane and per-line deformation | Diagnostic presentation | No physics synchronization unless a RAM gate changes gameplay progression |
| Sprite table upload and hardware sprite limits | Presentation/dropout | Presentation/dropout | Presentation/dropout | Diagnostic presentation | Object existence must not be inferred from rendered sprite presence |
| Region/refresh rate | PAL/NTSC changes physical frame cadence | PAL/NTSC changes physical frame cadence | PAL/NTSC changes physical frame cadence | Session configuration | Deterministic from ROM/region; no recurring trace event |
| Z80/SMPS driver ticks | Audio continues independently across some 68K work | Audio plus bus-request/driver-load work | Audio continues independently across some 68K work | Separate audio clock; lag if 68K is stalled | No physics-state authority from audio completion |
| Z80 bus requests or driver loading | May consume 68K time | Runtime driver/data handling can consume 68K time | May consume 68K time | Lag | Record the missed main-loop frame, not the bus transaction |
| VDP status/busy polling | May extend a synchronous operation | May extend a synchronous operation | May extend a synchronous operation or back a polled fence | Lag or completion candidate | Completion requires an explicit ROM-visible readiness owner |
| SRAM/save/peripheral waits | Outside active trace gameplay | Outside active trace gameplay | Outside active trace gameplay | Excluded unless evidence appears | Explicitly ruled out for current trace scope |

## S1/S2 lag-frame coverage audit

S1 and S2 should not gain new completion events merely because their ROMs use
decompression or DMA. Lag is sufficient only when all of the following hold:

- raw capture includes every physical emulator frame;
- the lag flag distinguishes a serviced interrupt from an executed gameplay
  loop;
- replay advances the game's required VInt-owned counters and queues on that
  row;
- input sampling/reuse follows the game's lag path; and
- no ordinary main-loop routine polls a still-pending hardware readiness value
  across multiple non-lag rows.

The S2 special-stage initialization timeline is the reference example for
synchronous initialization:
decompression and PLC work span physical VInts, but the trace needs only lag
rows plus structural fade, special-stage, and controller/DMA phases. It does
not need one event per decompressor or DMA operation.

Normal S1/S2 PLC queues are an explicit exception to the assumption that all
loading collapses into lag: ordinary loops can continue while a PLC remains
pending. They are initially classified as native deterministic service queues,
not as automatically authoritative trace inputs. Their audit must enumerate
which VInt handlers service them, which gameplay routines poll them, and
whether existing replay advances that service on lag and non-lag rows.

Any proposed S1/S2 authoritative completion kind must then demonstrate a
polled, gameplay-visible readiness gate whose timing is not already reproduced
by lag, execution phase, and deterministic queue service.

## Completion event schema

Authoritative edges live in a dedicated hardware-timing stream, not
`physics.csv` or `aux_state.jsonl`. The representation is intentionally small:

```json
{
  "event": "hardware_work_completed",
  "raw_frame": 10429,
  "boundary": "post_objects",
  "kind": "kos_module_queue",
  "ordinal": 3,
  "submission_fingerprint": "sha256:..."
}
```

- `kind` names a hardware-service class, not a zone, archive, object, or trace.
- `ordinal` is monotonic within that kind and structural replay session.
- `submission_fingerprint` is generated independently by the recorder and
  engine from a canonical tuple of kind, ROM source span, destination span,
  compression variant, and module count. The trace cannot use it to construct
  or modify a job.
- `raw_frame` is the physical capture row.
- `boundary` is one of `vint_service`, `pre_main_loop`, or `post_objects`.
  It identifies the service boundary at which the ROM first exposes the
  completion.
- There is no payload containing gameplay state or work progress.

The container contract below is **pre-v5 and not live** -- its schema
selector and `trace_schema` value are both superseded by the v5 grammar
section above, which is the authority: metadata declares only
`trace_schema: 5`, `hardware_timing_schema` is removed, and presence of the
file alone enables the single registry. The list is retained unedited as
migration evidence.

- filename: `hardware_timing.jsonl`;
- metadata discovery key: `"hardware_timing_schema": 1` or
  `"hardware_timing_schema": 2`;
- fixture trace schema: `trace_schema: 7`;
- current native S3K standard recorder version for schema 2: `6.41-s3k`;
- native S3K complete-run recorder version for schema 2:
  `6.42-s3k-completerun` (schema 2 was introduced in both native recorder
  families at version `6.38`); and
- the frozen Lua recorders remain at `6.37-s3k` and
  `6.37-s3k-completerun`, emitting schema 1 only.

For legacy `trace_schema <= 6` fixtures, absence of both the metadata key and
file means no authoritative timing input; replay uses only the production
scheduler and existing lag/phase contracts. For `trace_schema: 7`, a file
without the metadata key, the key without the file, a value other than integer
`1` or `2`, or an unknown future version is a hard fixture-load failure. An
empty stream is valid in either hardware-timing schema.

Events use UTF-8, one compact JSON object per LF-terminated line, and canonical
ordering by `raw_frame`, then ROM loop-tail boundary order `vint_service`,
`post_objects`, `pre_main_loop`, then `kind`, then `ordinal`. Duplicate event
identities and out-of-order lines are rejected rather than normalized.

The kind registry is selected by the metadata version:

```text
schema 1: KOS_MODULE_QUEUE
schema 2: KOS_MODULE_QUEUE, KOS_DECOMPRESSION_QUEUE
```

Under schema 1, `KOS_MODULE_QUEUE` uses recorded final readiness while
`KOS_DECOMPRESSION_QUEUE` remains a live production queue. Under schema 2,
both kinds use recorded final readiness. No timing stream may configure both
kinds as live, and an event kind not admitted by the selected registry fails
loading or admission. `PLC_QUEUE`, VDP transfer fences, and plane-draw fences
remain non-authoritative inventory candidates; each requires separate ROM
evidence and design review.

Committed schema-1 fixtures remain loadable. A fixture that crosses the AIZ
intro or ICZ act-transition direct-count consumer cannot certify that
boundary, because its direct work is live rather than edge-authorized. The
checked compatibility inventory lives in
`TestCommittedHardwareTimingFixtures`; replacement with schema-2 native
output is a separate publication action requiring explicit approval.

### Boundary application

The fixture loader compiles each raw edge into the existing replay execution
timeline; runtime code never infers a boundary from row contents.

- `vint_service`: apply inside the row's selected VInt service, before any
  post-VInt main-loop consumer.
- `post_objects`: apply after the row's object scan. A consumer in that scan
  cannot observe it until its next admitted dispatch.
- `pre_main_loop`: normally apply after `post_objects` as the current frame's
  final loop-tail boundary, ahead of `Wait_VSync` and the next admitted
  iteration. A held-counter row may instead expose a `pre_main_loop` completion
  edge explicitly compiled for that same raw row after its VInt closure. This
  represents deferred observation of the prior loop tail; it does not admit a
  main loop or traverse production queue service. On exact admission, replay
  runs only the production coordinator's `pre_main_loop` post-service hook so
  its normal queue metadata observes readiness. Its dedicated admission
  bypasses only the ordinary service's now-`vint_service` last-boundary check
  and remains confined to the compiled current row.
- Lag rows execute eligible VInt service but no main-loop or object consumer.
  They expose no other boundary unless the compiled current row contains the
  held-counter `pre_main_loop` completion described above.
- Setup-only and advance-only rows execute only the service boundaries named
  by their production lifecycle; they never gain an implied gameplay pass.
- The first raw row has no synthetic predecessor. An edge on it must match a
  job submitted by the represented structural prefix or fails.
- At segment end, any unconsumed edge or pending non-exportable job fails.
- Rewind restores the compiled-edge cursor, so the same edge is consumed once
  again on replaying the restored boundary.

## Engine model

### Submission

Production code submits a typed hardware job with ROM-backed input. Submission
allocates the next ordinal for that kind and computes the stable fingerprint
from the job it actually submitted. Host-side data preparation may finish
immediately, but observable readiness remains owned by the timing service.

### Ordinary play

The same production queue/decoder state machines serve both ordinary play and
replay. They retain the ROM descriptor, bookmark, source/destination, module,
FIFO, and service-point state required by each physical queue. S3K standard
Kosinski work uses one shared four-entry direct FIFO; KosM parents enqueue
their child streams into that FIFO and advance only through their coordinator.
Ordinary play releases work from ROM-derived work units at the
disassembly-defined service points, never from host wall-clock duration.

The recorded scheduler may replace only the final readiness admission. It does
not replace submission, preparation, queue order, decoder progress, service
calls, or the consumer. Live AIZ/HCZ acceptance tests must demonstrate that the
production scheduler remains within the recorded ROM completion boundaries;
trace green alone is insufficient evidence of live accuracy.

### Trace replay

For a kind configured as recorded by the selected schema, the dedicated edge
is authoritative over final readiness:

- an engine job whose data is ready early remains observably pending;
- the matching kind, ordinal, fingerprint, and boundary are released at the
  recorded boundary;
- the job must already be prepared; an edge cannot force preparation or
  decoder progress;
- release makes the engine readiness owner change naturally;
- the ROM-modeled consumer performs every downstream mutation.

One exception, and only one: a job **submitted while row authority was
deactivated** has no edge to wait for, because the recorder discards anything
observed outside a segment's rows. Such a job is serviced on the native work
budget instead -- profile activated and advanced, released in FIFO order, still
requiring preparation. Membership is fixed at submission, so this never releases
work the recording does describe. See the 2026-08-21 status entry above for the
two deadlocks that established it and for the open ordinal-return gap.

The timing adapter is a dedicated input port. It cannot read physics or aux
comparison data and never calls a title-card, results, level-load, ring,
object, or event mutation API.

### Rewind

Rewind captures the complete ordered FIFO and replay ledger:

- next ordinal per kind;
- every queued job's kind, ordinal, fingerprint, canonical submission fields,
  prepared/released state, and output ownership;
- active descriptor/bookmark/source/destination/module/FIFO progress;
- deterministic-scheduler work-unit progress;
- compiled completion edges and the consumption cursor; and
- the set of already-consumed edge identities.

A restored replay must reproduce output side effects and accept the same future
completion edge exactly once. Tests cover snapshots immediately before, on,
and after completion in ordinary and recorded modes.

## Failure semantics

Synchronization must expose structural disagreement rather than conceal it.

Replay fails when:

- a completion edge has no matching pending kind and ordinal;
- the independently computed submission fingerprint differs;
- the service boundary differs;
- the matching job is not prepared;
- the engine submits an unexpected additional job;
- the expected job was already released;
- a job remains pending past the structural segment boundary;
- two events release the same job; or
- the completion would be consumed at an ambiguous replay phase.

Reports show both sides:

```text
expected completion: KOS_MODULE_QUEUE#3
engine pending:       KOS_MODULE_QUEUE#2
```

An engine that submitted the wrong work must not be made green by releasing
whatever happens to be pending. Moving an otherwise valid edge changes
gameplay timing and should produce ordinary strict comparison failures; an edge
is called structurally invalid only when identity, preparation, ordering, or
boundary disagrees.

## Comparison policy

Gameplay comparison remains strict. In particular, this design does not
declare ring reset timing non-release-blocking.

The completion edge reproduces the missing external timing input. The
results/title routine must then clear rings through its ordinary ROM-modeled
branch. A ring mismatch after the edge remains a real divergence.

During development, a report may label fields inside a proven pending interval
as timing-correlated diagnostics. Such labeling must not suppress committed
release assertions and must disappear once the completion contract is active.

## Recorder policy

Recorders should poll stable RAM symptoms at the normal capture point. They
should not install broad execute/write hooks merely to infer hardware causes.

For the S3K Kos queues:

- observe the ROM queue/busy owners already read by consumer routines;
- emit an edge only on the eligible pending-to-complete transition;
- assign independent per-kind ordinals from observed FIFO lifecycles;
- retain direct slot identity across bit-15 busy progress and reconcile
  retirement plus append without requiring a sampled zero;
- emit module retirement at `post_objects` before any same-frame direct
  retirement at the later loop-tail `pre_main_loop` boundary;
- keep stage gating ahead of any optional diagnostic hook;
- retain invisible, sound-disabled, maximum-speed operation; and
- terminate within a bounded window.

For the S1 Nemesis PLC queue, `S1PlcHardwareTimingObserver` follows the same
rules against the ROM's own `v_plc_buffer` FIFO. Two of its properties are
load-bearing for the engine side and must not drift: it observes the head
before the shift that destroys head identity, and it discards anything seen
before a segment's first recorded row. The latter is why
`Sonic1PlcArmTiming.releaseArm()` falls back to native readiness in an
unrepresented span -- a level load's own `RunPLC` arming reaches no trace file,
so no edge for it can ever exist, and holding it against recorded readiness
would deadlock the ROM title-card wait.

The native harness is the fixture-publication authority. Before publication, its
implementation must be established against the audited ROM/disassembly
semantics, behavioral and unit tests, cross-implementation vectors where
available, and independent code review. An existing candidate is valid when it
was produced by the unchanged implementation that receives that review.
Lua/native byte equivalence is optional corroboration, not a publication
prerequisite. Version-1 differential coverage includes `6.37-s3k` standard and
`6.37-s3k-completerun` captures and byte-exact empty streams for routes with no
eligible completion. The maintained native schema-2 recorders are
`6.41-s3k` and `6.42-s3k-completerun`; they emit both module and direct
retirements. Recorder 6.37 treats an unchanged
`Level_frame_counter` as `vint_service` unless the ROM is inside its
held-counter title-card load loop. That loop is armed only by the fixed
`Obj_TitleCard` parent in physical SST slot 8 with `objoff_48` set, and its
post-object admission remains active for the iteration selected by the ROM's
raw `objoff_48` or `Nem_decomp_queue` exit predicates. An advancing counter
also admits `post_objects`. A Nemesis job alone cannot arm the exception, so
ordinary lag rows remain VInt-only. The stream is not declared through
`aux_schema_extras`.

Native 6.41/6.42 no longer uses the generic row heuristic for a module parent
whose prior modules-left byte is exactly `0x81`: a canonical one-head FIFO
removal/shift, including exact active identity, trailing entries, cardinality,
and reset/mode fencing, proves the ROM POST owner even when the observation row
holds `Level_frame_counter`. Duplicate/no-retirement rows remain VInt-only;
stale, malformed, multi-head, append-during-shift, and reset-crossing shapes
fail closed. Frozen Lua 6.37 behavior is unchanged and may therefore differ at
this one native state-proven attribution.

After capture, publication records and pins the native candidate's digests,
lengths, event counts, ordering, ranges, and semantic inventory as immutable
evidence, then copies that output exactly after explicit user approval.
Committed tests use those frozen literal expectations; they must not calculate
expected values by invoking the native recorder that produced the candidate.

## Acceptance criteria

The first implementation is accepted only when:

1. S1 and S2 PLC audits prove their per-handler service and polling behavior;
   existing replay results remain unchanged unless a separately reviewed
   timing kind is justified.
2. AIZ, HCZ, and ICZ submit matching generic Kos queue work without zone or
   trace predicates; module children contend in the shared direct FIFO.
3. Recorded Kos completion edges release only matching prepared kind,
   ordinal, fingerprint, and boundary.
4. AIZ intro and ICZ act-transition progression emerge from the production
   direct-count predicate; AIZ and HCZ ring-reset timing still emerges from
   the ordinary results/title routines.
5. Missing, duplicate, reordered, mismatched, wrong-boundary, and unprepared
   edges fail structurally; a shifted valid edge causes strict downstream
   comparison failure.
   Suppressed-row coverage additionally proves that an exact prepared
   current-row `pre_main_loop` edge succeeds without production service or
   gameplay, while absent, unprepared, wrong-kind, wrong-ordinal,
   wrong-fingerprint, wrong-boundary, stale, and unrepresented-gap cases fail
   closed. Rewind consumes the edge exactly once again after restore.
6. Every gameplay row remains compared.
7. Live play uses a deterministic non-wall-clock scheduler.
8. Rewind across pending and completed work is deterministic.
9. No trace payload writes gameplay state.
10. All previously-green non-LBZ S3K replays and the repository's required
    S3K bootstrap/loading guards remain green. LBZ is not used as a trace-work
    validation target.
11. Schema-1 consumer-crossing fixtures remain loadable but are not described
    as direct-boundary certification; schema-2 replacement remains behind the
    fixture-publication approval gate.

## Follow-up audit outputs

Before implementation, produce:

1. an S1 timing inventory separating synchronous lag work from PLC queues that
   ordinary loops poll;
2. the equivalent S2 inventory, using the special-stage timeline as the
   reference;
3. an S3K inventory of every main-loop-polled hardware readiness value,
   separating the direct Kos queue, Kos module queue, VDP/plane fences, and
   presentation-only flags; and
4. a trace-schema audit that pins the raw-frame boundary at which completion
   becomes visible to replay.

These audits should remove candidates when lag or phase already covers them.
The goal is the smallest completion-kind registry that explains every
remaining hardware-timing-sensitive gameplay boundary.
