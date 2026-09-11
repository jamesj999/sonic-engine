# Level-load span timing: scope verdict for the v5 hardware-timing port

## Status

Proposed. Design only — no production code accompanies this document.

## Question

Commit `2b046b8ec` closed the S1 emerald route's first plain act-to-act boundary by
adding one conjunct to `TraceRunPlaybackCoordinator.destinationReady`: a whole-run
destination level segment may not be admitted before the shared movie cursor has
reached that segment's own first recorded row. It is documented as the
*Whole-Run Level-Restart Admission Row* discrepancy, and reviewed in
`docs/status/trace-frontier-log.md` (commit `72584655b`) as ACCEPTABLE-WITH-CAVEAT.

That review declined to recommend the principled replacement — a level-load
readiness entry through the v5 hardware-timing port — partly because it assumed a
single consumer. That assumption is wrong: the S1 emerald route's manifest has 34
segments and 21 inter-segment level-load gaps, and the S2 and S3K whole-run
manifests have the same shape.

This document asks whether the multiplicity changes the verdict, and answers the
four questions posed with it: is a level-load span already in the port's scope,
what would S1 capture need, would the port actually remove the discrepancy, and
what would a re-record cost.

## Verdict

**Do not route the level-load span through the v5 hardware-timing port.** Not as a
contract amendment, not as a new `HardwareWorkKind`, not now and not when a second
consumer appears. The recommendation is not "later" — it is "the port is the wrong
mechanism for this class of cost", and the reasons are structural rather than
budgetary.

The multiplicity argument does not survive contact with the data, for a reason
worth stating up front: **each of the 21 boundaries needs the same self-referential
fact — "my own first recorded row" — and one uniform rule already supplies it to
all of them.** Multiplicity is an argument for a shared mechanism over 21
carve-outs. There are no carve-outs; there is one conjunct, in one method, applied
identically to every segment of every game. Moving that one conjunct into the port
would not consolidate 21 things into one; it would relocate one thing into a
mechanism that cannot express it.

What should happen instead is set out in [Recommendation](#recommendation): keep
the datum where it is, reclassify it correctly against the contract's own taxonomy,
bound it so it can never silently absorb a derivation regression, and guard it.
That is roughly two days against the port's eleven-to-eighteen, needs no fixture
re-record, and produces a *stronger* invariant than the port would.

---

## Fact 1 — Is a level-load span already in the port's scope?

No, and the contract rejects it twice: once on eligibility, once on inventory.

### 1.1 The contract's eligibility gate fails on two of five criteria

`2026-07-27-cross-game-hardware-timing-trace-contract.md` §"3. External work
completion" states the sole new authority applies when all five hold. Against the
S1 level load:

| # | Criterion | S1 `GM_Level` load |
|---|---|---|
| 1 | production code has submitted real ROM-backed work | **No** — nothing is submitted (see 1.4) |
| 2 | the ROM exposes a readiness value polled by ordinary main-loop code | **No** |
| 3 | the main loop can continue while that value remains pending | **No** |
| 4 | completion timing depends on hardware work not represented by `lag` or the execution phase | Arguably yes |
| 5 | readiness can affect a gameplay-visible lifecycle | Yes |

Criteria 2 and 3 fail on ROM evidence. Reading `docs/s1disasm/sonic.asm`:

- `Level_TtlCardLoop` (2814-2842) *does* poll a readiness gate —
  `tst.l (v_plc_buffer).w` at 2841. That is the one part of the span that matches
  the shape the port was built for, and it is also **the one part the engine
  already derives exactly**: 146 rows for MZ against `Sonic1PlcService`'s measured
  146, 150 against 150 for GHZ. It needs no recorded authority.
- Everything the residual is made of runs *outside* that loop and polls nothing.
  `NemDec` of `Nem_TitleCard` (2825-2830) runs between `disable_ints` and
  `enable_ints` — interrupts are off, so V-int does not even fire; the frames are
  simply missed. `Hud_Base` (2857), `Level_ClrRam`, `ClearScreen`, `LevelSizeLoad`,
  `LevelDataLoad`, `LoadTilesFromStart` and `ObjPosLoad` are straight-line calls
  with no `WaitForVBlank` between them. The next wait of any kind is `Level_Delay`
  (2957-2963, four rows) and `PalFadeIn_Alt` (2966, 22 rows), both already derived.
- REV01 has `FixBugs=0`, so the extra `WaitForVBlank` at 2946-2955 is not assembled.

So the residual is elapsed 68k execution between two `WaitForVBlank` calls, with no
pending job, no readiness word, and no polling consumer. That is the textbook
definition of the contract's **contract 1, main-loop admission**, whose text says
exactly this: *"The cause of the missed frame is deliberately absent.
Decompression, map construction, DMA setup, Z80 bus arbitration, or any other long
68K task all produce the same replay outcome when their only observable consequence
is a lag frame."*

### 1.2 The cross-game inventory already assigns this span to `Lag`

Two rows of the contract's inventory table cover it explicitly:

- *"Long synchronous decompression or level initialization | Physical VInts occur
  while the main loop is unavailable | ... | **Lag** | S1/S2 substantially covered"*
- *"Nemesis, Enigma, Saxman, raw map decompression | Normally synchronous from
  gameplay's point of view | ... | **Lag** | No codec-specific trace authority"*

And the S1/S2 audit section closes it: *"Any proposed S1/S2 authoritative
completion kind must then demonstrate a polled, gameplay-visible readiness gate
whose timing is not already reproduced by lag, execution phase, and deterministic
queue service."* The only polled gate in the span is the PLC drain, and it *is*
already reproduced by deterministic queue service.

### 1.3 Hard rule 4 names "S1 PLC", and S1 PLC is the part that already works

Hard rule 4 permits recorded timing to delay "the art-loading pipelines of all
three games — S1 PLC, S2 DPLC, and S3K Kosinski queues". It is tempting to read the
level load as art work, since it contains `NemDec`, `LevelDataLoad` and
`LoadTilesFromStart`. But the residual is *by construction the complement of the
PLC pipeline*: the counted span (fade-out 22 + PLC drain 146/150 + `Level_Delay` 4
+ `PalFadeIn_Alt` 22) is subtracted from the recorded gap, and what is left is
precisely the non-PLC work. Routing the residual through the "S1 PLC" permission is
a category error — the named pipeline is the one thing in the span that needs
nothing.

`TestS1S2PlcComparisonOnlyGuard` states the settled position in one line:
*"PLC readiness is native deterministic service, not timing-stream authority."*

### 1.4 No S1 or S2 production code submits hardware work at all

`HardwareTimingService.submit` has exactly two production callers, both S3K:
`S3kKosModuleQueue.java:105` and `S3kKosDecompressionQueue.java:110`.
`GameplayModeContext.java:193-196` constructs the service for every game and
`LevelFrameStep` traverses its boundaries every level frame, but for S1/S2 the job
list is permanently empty, `hasSubmitted` stays false, and any recorded edge on an
S1/S2 run would fail with `engine pending: <none>`.

For S1 to have level-load readiness, `LevelManager`'s S1 load path would have to
become a hardware-job submitter. That is a substantial intrusion into production
level loading for a behaviour it does not otherwise need.

**Fact 1 verdict: the span is out of scope, and it is out of scope on ROM grounds,
not on grounds a contract amendment could reasonably reverse.** An amendment that
admitted it would have to delete the contract's own distinction between contract 1
and contract 3 — which is the distinction the whole document exists to draw.

---

## Fact 2 — The port's grammar cannot address the rows in question

This is the decisive structural finding, and it is independent of the eligibility
argument above. Even granting every policy point, the mechanism does not fit.

### 2.1 The residual rows are outside every recorded segment

Measured from `src/test/resources/traces/s1/runs/s1-sonic-complete-withemeralds/run_manifest.json`
(34 segments; `gap` = next segment's `bk2_frame_offset` minus the previous
segment's last row):

| Destination | gap rows | Destination | gap rows |
|---|---|---|---|
| `ghz2_2` | 236 | `syz3_2` | 230 |
| `ghz3_2` | 235 | `lz1` / `lz1_2` / `lz3` | 216 |
| `mz1` / `mz1_2` | 228 | `lz2` / `lz4` | 217 |
| `mz2_2` / `mz2_3` | 228 | `slz2` / `slz3` / `sbz1` | 219 |
| `mz3_2` | 228 | `slz1` / `sbz2` / `sbz3` | 220 |
| `syz1` / `syz2_2` | 230 | (bridge splits) | 1 |

21 gaps of 216-236 rows, plus the run's initial load — the 22 load pairs the
`plc-system` skill's `segment_start - 26` invariant already counts. Cross-game:

| Run manifest | segments | distinct gaps |
|---|---|---|
| `s1/runs/s1-sonic-complete-withemeralds` | 34 | 1, 216, 217, 219, 220, 228, 230, 235, 236 |
| `s2/runs/s2-sonic-tails-complete-emeralds` | 35 | 1, 157-199 |
| `s3k/runs/s3k-knuckles-complete-superemeralds` | 67 | 1, 1350-2503 |

**No segment's `physics.csv` contains a single one of these rows.** The recorder
stops a segment at the end of gameplay and starts the next at its first main-loop
row; the load span between them is simply not recorded.

### 2.2 `raw_frame` is a row index *inside* a segment, hard-bounded by its row count

`HardwareTimingStreamLoader.loadVersion` rejects any edge with
`rawFrame < 0 || rawFrame >= traceFrameCount` (lines 88-91), where
`traceFrameCount` is that segment's own row count.
`TraceHardwareTimingScheduleCompiler.compileForInstall` then compiles edges by
looking each `raw_frame` up in a map built from `trace.getFrame(i).frame()`.
`HardwareTimingSchedule` indexes edges by `(rawFrame, boundary)`.

Empirically confirmed against a live fixture: `s3k/runs/s3k-multibonus/aiz` has
`trace_frame_count` 4654 and edge `raw_frame` values spanning 34-4648.

**There is no representable `raw_frame` for a gap row.** The rows do not exist in
any trace the loader can see.

### 2.3 No service boundary is traversed during the gap, so no edge could be admitted

`admitRecordedCompletion` requires `lastServicedBoundary == boundary`
(`HardwareTimingService.java:502-506`), and `lastServicedBoundary` is set in exactly
one place: `service(...)`, reached from `HardwareBoundaryDispatch.serviceBoundary`
via `LevelFrameStep`.

Transition-gap rows do not run the level body —
`TraceSessionLauncher.suppressesRunNativeLevelBody` (:2352-2356) skips it, and the
`plc-system` skill records the measured consequence: *"Transition-gap rows never
service a production V-blank."* So during the gap there is no `vint_service`, no
`post_objects` and no `pre_main_loop` traversal for an edge to prove itself
against. The port's entire identity-and-boundary safety apparatus has nothing to
match on.

### 2.4 The only edge placement that *is* representable is circular

Three placements are conceivable and all fail:

- **Destination segment, `raw_frame` 0.** Replay cannot reach destination row 0
  without first consuming the gap rows — which is the very decision the edge was
  meant to make. The edge cannot gate its own arrival.
- **Source segment, last row.** Representable, but an edge carries only a
  *position*, not a *duration*. An edge on the source's last row says "ready then",
  which is 34 rows too early and carries no information about the 34.
- **A new gap-relative anchor.** Not the existing port. It would require the edge
  to carry a row count — the first quantity, as opposed to position, ever placed in
  the stream — which the contract's Non-goals forbid outright: *"Recording
  compressed byte progress, DMA byte counts, VDP FIFO occupancy, or host execution
  duration."*

### 2.5 The contract already says gap-completed work completes live

Contract 4 anticipates exactly this situation and resolves it the other way:
*"Exported edges across structural segments may have ordinal gaps where native
phase work intentionally produced no completion edge; the production submissions
and claims must advance the ledger through that gap."*

This is visible in the fixtures: in `s3k-multibonus`, the `aiz` segment's first
`kos_decompression_queue` edge is ordinal 11 and `hcz`'s is ordinal 153, with the
ledger advanced through the intervening segments and their gaps by production
submissions that produced no edge. **The port's design already treats unrecorded
structural gaps as production-scheduler territory by construction.**

### 2.6 S3K is the proof by existence

S3K has the most mature timing port in the repository: 25+ populated
`hardware_timing.jsonl` streams, both kinds live-recorded, suppressed-row admission,
rewind coverage. Its whole-run manifests still have 1350-2503-row unrecorded gaps,
and the port does not address them. The same `TraceRunPlaybackCoordinator`
admission machinery serves S3K, S2 and S1 alike; `RunPlaybackObservation` even
carries `timingScheduleGeneration`, and `destinationReady` never reads it.

**In the game where the port is most complete, the port is not the mechanism for
this problem.** That is not an accident of investment; it is 2.1-2.4.

---

## Fact 3 — Would it remove the discrepancy? No.

The *Whole-Run Level-Restart Admission Row* entry names two removal conditions:

> Remove this boundary if the engine ever gains a derivation for a level load's
> elapsed hardware cost, or if the recorder begins to record the restart span so
> the counted and un-timed parts can be compared separately.

A port entry satisfies **neither**. It is not a derivation, and it does not record
the span as comparable rows. It substitutes one recorded-timing container for
another, and the second container is itself a documented discrepancy —
*Hardware-Timing Replay Input Exception*. Net discrepancy count: unchanged. Net
confinement: arguably worse, because the level-load consumption point would have to
sit either in the comparison coordinator (which would then query production timing
state it deliberately does not touch today) or inside `LevelManager`'s load path
(which would make S1/S2 level loading a hardware-job submitter for a behaviour it
otherwise does not need).

It is worth being blunt about the underlying arithmetic, because it bounds every
option. In a BK2-driven whole-run replay the engine must consume the same number of
movie rows the ROM did, or every subsequent input row lands on the wrong frame.
The ROM's load took 34 rows of 68k time; the engine's takes ~0. Those rows come
from exactly one of three places:

1. **derive them** — requires a cycle model of `NemDec`/`KosDec`/`ClearScreen`,
   i.e. partial 68k emulation, which is architecturally alien to a reimplementation
   and is itself just a differently-shaped import of hardware timing;
2. **take them from the recording** — in *some* container;
3. **diverge**.

All of (2)'s containers — manifest offset, timing edge, recorded lag rows — are
"the recording tells the engine how many rows the load took". The question is only
which container is most honest and best confined, and the answer is not the port.

### The `ProfiledLoadTimeManifest` path, and why it does not help either

The engine already has a measured-and-estimated elapsed-load-cost mechanism:
`ProfiledLoadTimeManifest` maps a `(kind, submissionFingerprint)` to
`serviceFrames`, with a deterministic estimator over `HardwareWorkFeatures` for
unmeasured S3K direct work. Its own publication gates accept a fingerprint median
error of ≤2 frames and p95 ≤5 frames.

That is a live-play convenience, not a replay derivation. A ±5-frame p95 cannot
satisfy a row-exact whole-run comparison where a single row's slip misaligns every
subsequent input. A `LEVEL_LOAD` profiled entry could give live play a plausible
loading pause; it could never remove the admission row.

---

## Fact 4 — What a re-record would cost, and whether one is needed

### 4.1 Recorder work is real but is not the binding constraint

`HardwareTimingEventEngine`
(`tools/bizhawk-headless/src/Recording/HardwareTimingEventEngine.cs`, 1434 lines)
is constructed only by `S3KCompleteRunCaptureRunner.cs:427-428` and
`S3KTraceCaptureRunner.cs:296-297`. `hardware_timing.jsonl` is registered only in
`CommandLineOptions.S3kTraceOutputFileNames` (`Program.cs:32-38`, four entries) and
`S3KStagedSegmentSink.cs:27-28`; the general `TraceOutputFileNames`
(`Program.cs:25-30`) has three entries and no timing file, and the S1/S2 run sink
`StagedRunSegmentSink.cs:47-49` is structurally three-file, with two streamed
handles and a two-member `RunSegmentStreams`. `TraceCliTests.cs:397,702,1286`
positively assert that S1/S2 metadata carries no timing declaration.

**Nothing in the S3K engine is reusable for S1.** It reads S3K RAM symbols
directly (`S3KRam.KosDecompQueueCount 0xFF0E`, `KosModuleQueue 0xFF64`,
`KosModulesLeft 0xFF60`, `NemDecompQueue 0xF680`), hooks a hardcoded S3K ROM PC
(`0x001B46`, the return from `Queue_Kos`), hardcodes the S3K title-card object
pointer (`0x0002D690`, SST slot 8, `+0x48`), and encodes both S3K Kosinski
bitstream shapes. There is no game abstraction and no profile — only the ROM byte
array is parameterised.

The closest S1 analogue, `LoadQueueStateProjector.CaptureS1`, is a per-row
*snapshot projector* over `v_plc_buffer 0xF680` / `v_plc_patternsleft 0xF6F8`: no
ordinals, no fingerprint, no pending-to-complete transition detection, no boundary
classification. A level-load detector would additionally need `v_vblank_routine`
(`0xF62A`) and `f_restart` (`0xFE02`), neither of which exists in `S1Ram` or is
read anywhere, plus title-card SST constants (`0xD080`-`0xD140`, `card_mainX` at
`+0x30`).

So the work is: a new S1 lifecycle engine, `StagedRunSegmentSink` +
`RunSegmentStreams` + both S1 and S2 runners' stream open/close, filename
registration and the `Program.cs:550` no-replace preflight, C# tests, and
independent review per the recorder-porting contract. 2-4 days, and it is
*additive* work with no existing scaffolding to lean on.

But this is only worth scoping if the resulting stream could be *consumed*, and §2
says it cannot: the recorder would have to stamp an edge at a row no segment
contains, which the loader rejects at parse time.

### 4.1a The recorder already stamps gap rows — and puts them in the manifest

`S1RunCaptureRunner` advances through every gap frame (`:213-215`); nothing is
skipped. But the only per-frame RAM it reads while unarmed is `v_gamemode`
(`:223`) and the player's `obCtrlLock` (`:282`). `S1AuxEventEngine.ProcessFrame`
and `LoadQueueStateProjector.CaptureS1` are called only from `AppendLevelRow`
(`:476-485`), so **the entire level-load window is currently unobserved** — it lies
before `obCtrlLock` reaches 0, which is exactly the arm predicate.

One component does run across the gap: `S1DynamicArtObserver`, constructed before
the loop (`:185-189`) with M68K callbacks registered for the whole capture
(`S1DynamicArtObserver.cs:63-79`). `RunState.PrepareDynamicArtCursor` (`:420-430`)
stamps unarmed frames with the raw BK2 row, callbacks raised outside a segment are
tagged `DynamicArtSubmissionOrigin.RunGap` (`:380-382`), and `PublishGap` (`:228-265`)
emits them.

They land in `run_manifest.json` under `dynamic_art_gap_transitions`
(`RunManifestWriter.cs:347-362`), keyed by `movie_logical_frame` and
`gap_edge_index` — **not** in `hardware_timing.jsonl`, and strictly
comparison-only.

This is the single most useful precedent in the codebase for the question at hand,
and it points the same way as everything else: gap-row stamping is mechanically
possible, and the last time the project needed gap-row data it put it in the run
manifest, because that is the only container whose coordinate system spans the gap.

### 4.1b The segment offset is itself a recorded ROM-state edge

Worth noting for the confinement argument. `S1RunCaptureRunner.ArmLevelSegment`
(`:276-289`, `:432-469`) sets `bk2FrameOffset = frameNow` only when
`v_gamemode == 0x0C` **and** the player's `obCtrlLock` is 0. The offset is
therefore the recorded observation of a ROM state predicate — the frame the ROM
first hands control to the player — not a chosen or fitted number. That does not
stop it being a frame index when consumed, but it does mean the admission floor
compares against a ROM-state edge the recorder observed, which is the category
hard rule 3 permits a provider to expose at an owning boundary.

### 4.2 The registry is all-or-nothing, so a new kind re-records S3K too

This is the practical blocker that makes the cost estimate misleading if taken as
"S1 recorder work only".

`HardwareTimingSchedule.recordedAdmissionPolicies()` (lines 101-110) enumerates the
two Kosinski kinds literally, and `HardwareTimingService.validateAdmissionPolicies`
(lines 565-585) **requires a policy for every `HardwareWorkKind` enum value**. The
v5 consolidation deliberately removed per-fixture registry selection: *"Presence of
`hardware_timing.jsonl` enables the one current registry"*, and *"No admission
policy is inferred from which event kinds happen to be present."*

So adding `LEVEL_LOAD` makes it `RECORDED` for **every fixture that has a timing
stream**. S3K loads levels too; any `LEVEL_LOAD` job it submits would then require
a matching edge, and its absence is a hard failure (*"a job remains pending past the
structural segment boundary"*). All 25+ committed S3K timing fixtures would need
re-recording and re-publication through the approval-gated publication contract, or
the kind would have to be submitted only by S1 — which is a game-name carve-out and
violates hard rule 2.

The alternative is reintroducing selective registries, which directly regresses the
2026-08-03 v5 consolidation.

### 4.3 Is a re-record needed for S1 lanes other than this run?

No. Standard S1 fixtures are single-act and never cross a level-load boundary. The
only S1 fixtures with inter-segment gaps are the two run manifests, and
`s1-ghz-maze-roundtrip` has only bridge splits (gap 1). So the affected surface is
exactly one fixture — `s1-sonic-complete-withemeralds`, ~214,000 movie rows,
roughly 3.5 minutes of native capture — and it needs no re-record for the
recommended path below.

### 4.4 Guard and test changes a port entry would need

For completeness, since the task asked which guards move:

- `TestS1S2PlcComparisonOnlyGuard.timingKindRegistryAdmitsOnlyKosinskiWork`
  (lines 79-90) pins the kind set to exactly `{KOS_MODULE_QUEUE,
  KOS_DECOMPRESSION_QUEUE}` and separately asserts no kind name contains `PLC`.
  Both assertions would have to be rewritten.
- `TestHardwareTimingStreamLoader:48` pins the accepted wire names.
- `TestCommittedHardwareTimingFixtures` (≈:310, :315, :384, :389) pins the fixture
  inventory's kind expectations.
- `TestHardwareTimingAuthorityGuard` — **no change required**. Contrary to the
  brief's expectation, it holds no kind literals at all; it is entirely
  package/path/regex shaped. Its exemption lists
  (`TraceHardwareTimingBoundaryObserver`, `HardwareTimingReplayPort`,
  `TraceSuppressedRowClosure`, `TraceSessionLauncher`) would only move if the
  consumption point moved.

**This is the widening to be honest about.** Today the guard says, in effect, "the
recorded-timing exception applies to exactly two S3K decompression queues". Replace
that with "…and to a composite `LEVEL_LOAD` kind covering `NemDec` + `clearRAM` +
`ClearScreen` + `Hud_Base` + `LevelDataLoad` + `LoadTilesFromStart` + `ObjPosLoad`",
and the exception's boundary stops being "a named pipeline the ROM polls" and
becomes "any long 68k routine we could not derive". Once one composite kind is
admitted, the argument for the next one is the previous one. That is precisely the
smuggling channel the pinned registry closes, and it is the strongest reason to
decline even if §1 and §2 could be answered.

---

## The correct classification of the existing mechanism

The admission row is not an unsanctioned cousin of the timing port. It is a
coarse-grained instance of the contract's **contract 1, main-loop admission** — the
same authority `physics.csv`'s `lag` column already exercises, for rows the
recorder chose not to emit.

This is worth stating plainly because it dissolves the review's discomfort. A lag
row *is* recorded hardware timing driving engine scheduling: it tells the engine
"the main loop did not run this frame", and the engine could not have derived that.
It lives entirely outside the guarded port, needs no fingerprint, no ordinal and no
kind, because the contract classifies it as main-loop admission rather than
external work completion. The admission row says the same thing about 34 rows at
once, in the only container the recorder produced for them.

What it lacks relative to a lag row is not legitimacy. It is **granularity and a
bound**: a lag row asserts one frame and cannot absorb an engine error, whereas an
unbounded `>=` floor can silently absorb an arbitrary derivation regression.

That is the real defect, and it is fixable cheaply.

---

## Recommendation

### R1. Decline the port entry, and record why in the contract

Amend `2026-07-27-cross-game-hardware-timing-trace-contract.md` with a short
subsection under the S1/S2 audit stating that the level-load span was evaluated
against the §3 eligibility gate and assigned to contract 1, with the ROM citations
from §1.1 above, and that the port's `raw_frame` grammar cannot address an
unrecorded structural gap (§2.2-2.4). This closes the question rather than leaving
it to be re-litigated at the next boundary, and it makes the "no composite kinds"
line explicit rather than implicit in a pinned test.

Add a `PLC_QUEUE`-style entry to the contract's non-authoritative candidate list:
`LEVEL_LOAD` / `LEVEL_INITIALIZATION`, rejected, with reason.

### R2. Restate the derived/residual division explicitly, and keep derivation the default

The division must be written down where the engine can be held to it:

- **Derived, and must stay derived** — `PaletteFadeOut` 22 rows
  (`Sonic1LevelInitProfile.preLevelFadeOutFrames`), the `Level_TtlCardLoop` PLC
  drain (146 MZ / 150 GHZ, measured by `Sonic1PlcService`), `Level_Delay` 4 +
  `PalFadeIn_Alt` 22 (`Sonic1LevelInitProfile.preLevelMainLoopDelayFrames`).
  Total 194 rows for the MZ boundary.
- **Recorded** — the residual only: 34 rows for MZ, 36-37 LZ/SLZ, 38 SYZ, 39-40 SBZ.

`LevelInitProfile` is the owner for any future derivation, and it is per-game
already, so new counted loops land there without a carve-out.

### R3. Bound the floor — the substantive improvement

Today the floor is `observation.sharedBk2Cursor() >= destination.bk2FrameOffset()`,
which will absorb *any* shortfall in the derived part. If a future change broke the
PLC drain derivation by 40 rows, the floor would silently correct it and the run
would stay green. That is the one way the current mechanism can hide an engine bug,
and it is not hypothetical — the derived part is 194 of the 228 rows.

Introduce an un-timed residual budget: the coordinator may defer admission past the
point at which every engine-derived condition is satisfied by at most `N` rows,
where `N` is a single documented constant covering the largest measured residual
across all published run fixtures plus headroom (measured maximum today is 40; 48 is
a reasonable constant). Exceeding it fails the run with a message naming the
observed deferral and the derived span, so a derivation regression surfaces as a
derivation regression.

`N` is one uniform number for all games and zones — a bound, not a table, and
therefore not a carve-out. This converts the mechanism from an unbounded silent
absorber into a bounded, budgeted, fail-loud one, which is a *stronger* invariant
than the port would give (the port's fail-closed behaviour is on identity, not on
magnitude).

### R4. Add the guard the review asked for

`TestTraceRunPlaybackCoordinator` pins the row semantics but nothing pins the
*shape*. Add a source guard asserting:

1. `TraceRunManifest.Segment.bk2FrameOffset()` is reachable only from
   `com.openggf.trace.replay.runs`, `com.openggf.trace.catalog`,
   `com.openggf.RunSegmentAdvancer` and `com.openggf.TraceSessionLauncher` — never
   from a gameplay-owner package (reuse `TestHardwareTimingAuthorityGuard`'s
   `GAMEPLAY_OWNER_PACKAGE_PREFIXES` list).
2. Every recorded-row comparison in `TraceRunPlaybackCoordinator.destinationReady`
   appears as a conjunct (`&&`) and never as a disjunct, so the defer-only property
   is structural rather than reviewed.

Both are cheap regex/AST-shaped tests in the style the repository already uses.

### R5. Keep the honest long-term removal on the books, unchanged

The discrepancy's second removal condition — *"the recorder begins to record the
restart span so the counted and un-timed parts can be compared separately"* — is
the right end state and is unaffected by this verdict. Recording the gap as ordinary
rows would turn the residual into row-granular comparison evidence and let the
derived 194 be checked directly instead of by subtraction.

It should not be done now, and the reason is worth recording: it would make every
act boundary of the S1 and S2 complete runs **red**, because the engine still could
not produce 34 rows of load. That is a more honest state and a worse release state.
It also requires re-recording and re-publishing both complete-run fixtures through
the approval-gated publication contract, plus full frontier re-measurement.

Schedule it behind the S3K vertical slice, not in front of it.

---

## Migration — does the admission-row condition get deleted?

No, under this recommendation. It is retained, reclassified (R1), bounded (R3) and
guarded (R4). The *Whole-Run Level-Restart Admission Row* discrepancy entry stays,
with its Contract section extended to name the residual budget and its Removal
Condition unchanged.

Had the port entry been recommended, deletion would have been the migration's final
step, and only after: S1 level-load submission landed in production, the recorder
emitted a consumable stream, all 25+ S3K timing fixtures were re-recorded for the
widened registry, and the S1 complete-emeralds run was green through all 21
boundaries on the port path. Deleting the conjunct earlier would strand every
boundary the port had not yet certified.

---

## Verification strategy

For the recommended path:

1. **Residual budget unit coverage** — extend `TestTraceRunPlaybackCoordinator`
   with: deferral of exactly `N` rows admitted; `N+1` rows fails with the derivation
   message; the existing row-129-rejected / row-130-admitted pins unchanged.
2. **Measured-residual regression test** — a fixture-driven test over
   `s1-sonic-complete-withemeralds`' manifest asserting that for every level-kind
   destination, `gap − derivedSpan(zone)` falls within `[0, N]`. This pins the
   34/36-37/38/39-40 measurements as data rather than prose, and turns a future
   derivation regression into a fast unit failure instead of a whole-run failure.
   The same test should run over the S2 complete-emeralds manifest.
3. **Shape guard** — R4's two assertions, plus negative self-tests in the style of
   `TestHardwareTimingAuthorityGuard`'s crafted-violation tests.
4. **Contract-document consistency** — `TestS1S2PlcComparisonOnlyGuard.timingKindRegistryAdmitsOnlyKosinskiWork`
   stays exactly as it is; its continued passing is the verification that R1 was
   honoured.
5. **Whole-run lane** — `TestS1CompleteEmeraldVisualRun` lane 2 must stay at its
   current frontier (crosses GHZ3 → MZ1, admits `mz1` at BK2 27,467) with no change
   in first-error frame or error count. Record in
   `docs/status/trace-frontier-log.md` per the frontier-log obligation.
6. **Cross-game sweep** — `mvn test -Dtest='*TraceReplay' -DfailIfNoTests=false`
   plus `TestHardwareTimingAuthorityGuard`, `TestS1S2PlcComparisonOnlyGuard`,
   `TestCommittedHardwareTimingFixtures`.

---

## Phased estimate

### Recommended path

| Phase | Work | Estimate |
|---|---|---|
| 1 | R1 — contract amendment + rejected-candidate entry; discrepancy entry extension | 0.5 day |
| 2 | R2 — write the derived/residual division into the discrepancy entry and `Sonic1LevelInitProfile` comments; no behaviour change | 0.25 day |
| 3 | R3 — residual budget in `TraceRunPlaybackCoordinator` + unit coverage | 0.5 day |
| 4 | Verification item 2 — measured-residual regression test over S1 and S2 manifests | 0.5 day |
| 5 | R4 — shape guard with negative self-tests | 0.5 day |
| | **Total** | **~2.25 days**, no fixture re-record, no recorder change |

### Rejected path, costed for comparison

| Phase | Work | Estimate |
|---|---|---|
| 1 | Contract amendment admitting a composite non-polled kind; review | 1 day |
| 2 | `LEVEL_LOAD` kind, composite fingerprint domain, registry/policy changes | 1-2 days |
| 3 | S1 level-load submission in production `LevelManager`/`LevelInitProfile` | 2-3 days |
| 4 | Gap-anchored edge grammar (new wire form; contract Non-goals conflict) | **blocked** |
| 5 | S1 recorder event engine + run sink + C# tests + review | 2-4 days |
| 6 | Re-record and re-publish 25+ S3K timing fixtures for the widened registry | 3-5 days + approval gate |
| 7 | Re-record and re-publish S1/S2 complete-run fixtures | 1-2 days + approval gate |
| 8 | Guard/test updates across four test classes | 0.5 day |
| | **Total** | **~11-18 days, with phase 4 unresolved** |

Phase 4 is not a scheduling problem. Without a representable anchor for a gap row,
phases 1-3 and 5-8 produce a stream nothing can consume.

---

## Summary of the answers requested

1. **Already in scope?** No. Fails contract §3 criteria 2 and 3 on ROM evidence;
   the inventory already assigns the span to `Lag`; hard rule 4's named S1 pipeline
   (PLC) is the one part already derived exactly. An amendment would erase the
   contract's own contract-1/contract-3 distinction.
2. **S1 capture needs?** An S1 event engine, run-sink wiring, filename registration,
   C# tests and review (2-4 days) — but the v5 schema **cannot** accommodate it,
   because `raw_frame` is bounded to a segment's own rows and the residual rows are
   in an unrecorded gap.
3. **Removes the discrepancy?** No. It satisfies neither stated removal condition
   and trades one documented discrepancy for another. Stated plainly, as asked:
   **the port cannot express "this level load is ready" for a row no segment
   records.**
4. **Re-record cost?** ~3.5 min of capture for the one affected S1 fixture, but the
   all-or-nothing v5 registry drags 25+ S3K fixtures with it. No other S1 lane needs
   one; standard S1 fixtures never cross a level-load boundary. The recommended path
   needs no re-record at all.
