# Recording the level-load span as its own run segment

## Status

Proposed — feasibility evaluation and design. No production code accompanies
this document. Two throwaway BizHawk probes were used to produce the
measurements below; both were deleted before commit.

Companion to
[2026-08-06-level-load-span-timing-port-scope.md](2026-08-06-level-load-span-timing-port-scope.md),
which ruled the v5 hardware-timing port out for this span. That verdict rested
decisively on its Fact 2: *"there is no representable `raw_frame` for a gap
row"*, because the rows are in no segment. **This document evaluates the change
that dissolves exactly that objection.**

---

## Verdict

**NO-GO as a standalone change. Conditional GO only as phase 1 of a bundled
programme that also lands an S1/S2 hardware-timing consumer, scheduled behind
the S3K vertical slice.**

The proposal is mechanically feasible — far more so than the timing-port route,
and much more cheaply on the recorder side than expected: the load span is
delimited by a single RAM bit the recorder already reads every frame, in all
three games, and the classifier that decides what a replayed row does already
handles rows of exactly this shape. That part is close to free.

It fails on three findings, each independently sufficient to decline it *as
proposed*:

1. **It does not remove the discrepancy.** Two independent reasons, §5.
2. **It does not derive the residual; it re-containers it** — from
   `Segment.bk2FrameOffset()` to `Segment.traceFrameCount()`. Same number,
   same origin, one field over. §6.
3. **Its immediate effect is red, not green, and by a large measured margin.**
   The residual is not a trailing idle that can be absorbed at the end of the
   span. It is *interleaved between the counted loops* in three separate
   blocks, and the engine's own counted work currently sits **11 rows early**
   for the whole 147-row title-card drain and **~38 rows early** for the
   post-drain palette-fade drain. Recording the span converts a silent
   misalignment into ~157 compared red rows per boundary × 21 S1 + 27 S2
   boundaries. §4, §6.3.

What it *does* buy is real and should be recorded honestly: it makes 195 of
each boundary's 228 rows compared instead of uncompared, it surfaces a genuine
engine/ROM PLC phase error that is invisible today, it makes the load-span rows
addressable by the existing v5 `raw_frame` grammar (unblocking the previously
**blocked** phase 4 of the rejected port path), and it would let the
`dynamic_art_gap_transitions` side-channel be retired. Those benefits are worth
having — after the release slice, and only bundled with the timing work that
makes them green.

---

## 1. Method

Everything numeric below is measured, not inferred. Two sources:

**(a) The committed fixtures.** `run_manifest.json` segment offsets and row
counts, and the `vblank_counter` / `gameplay_frame_counter` / `lag_counter`
columns of each segment's first and last recorded row, over
`s1/runs/s1-sonic-complete-withemeralds` (34 segments, 21 level→level gaps).

**(b) Two throwaway Lua probes** under the `probe_runtime.lua` declarative
contract, run against the committed BK2 and the REV01 ROM, both deleted
afterwards:

- probe 1 — `event.onmemorywrite` on system-bus `0xFFF600` (`v_gamemode`),
  logging `emu.framecount()`, `v_vblank_count` (`0xFFFE0C`), `v_framecount`,
  zone/act at every mode change;
- probe 2 — the same plus write hooks on `0xFFF680` (`v_plc_buffer` head) and
  `0xFFF6F8` (`v_plc_patternsleft`), gated to rows where `v_gamemode` bit 7 is
  set.

Stage gates were ROM-state predicates (`(v_gamemode & 0x7F) == 0x0C`) narrowed
by a frame window, per the probe contract. Read/log only.

---

## 2. Fact 1 — the recorder can delimit the span, and already samples the bit

### 2.1 The delimiter is `v_gamemode` bit 7, in all three games

`GM_Level` sets bit 7 of the game-mode byte on entry and clears it immediately
before the main loop:

| Game | set | clear |
|---|---|---|
| S1 | `bset #7,(v_gamemode).w` — `docs/s1disasm/sonic.asm:2703` | `bclr #7,(v_gamemode).w` — `sonic.asm:2991` (`Level_StartGame`, `Level_MainLoop` at `:2998`) |
| S2 | `bset #GameModeFlag_TitleCard,(Game_Mode).w` — `docs/s2disasm/s2.asm:4758` | `bclr` — `s2.asm:5082` |
| S3K | `bset #7,(Game_mode).w` — `docs/skdisasm/sonic3k.asm:7505` | `bclr #7,(Game_mode).w` — `sonic3k.asm:7882` |

The S1 restart path re-enters at the `GM_Level` label (`sonic.asm:3016-3018`,
`3041-3055`), so the bit is set again for every act advance and every death
restart. This is a plain RAM bit, not a PC hook, and it is game-uniform — no
zone, route, or game-name predicate is involved.

### 2.2 Measured: the bit-7 window is exactly coextensive with the recorded gap

Probe 1 output, `emu.framecount()` at each `v_gamemode` write, against the
manifest:

| boundary | bit 7 set | bit 7 cleared | span rows | manifest gap |
|---|---|---|---|---|
| `ghz2` → `ghz2_2` | 9,505 | 9,740 | 236 | 236 |
| `ghz3_2` → `mz1` | 27,239 | 27,466 | 228 | 228 |
| `mz1` → `mz1_2` (death restart) | 30,858 | 31,085 | 228 | 228 |

The destination's `bk2_frame_offset` is `bclr + 1` in every case (9,741;
27,467; 31,086) — the first `Level_MainLoop` `WaitForVBlank` return. The gap
is *entirely* pre-level rows: there is no leftover source-main-loop row at the
head and no locked main-loop row at the tail. The zone/act read at the *first*
row of the span is already the destination's (`zone=2 act=0` at 27,239 for
MZ1), so a load-span segment can carry and validate the destination identity
from its own row 0.

### 2.3 The recorders already read the bit; S2 already branches on it

`S1RunCaptureRunner` reads `v_gamemode` every frame
(`tools/bizhawk-headless/src/Recording/S1RunCaptureRunner.cs:223`) and already
normalises bit 7 in `MapExpectedMovieEndMode` (`:317-331`). The `$8C` rows
currently fall into the generic `gameMode != LevelGameMode` branch (`:291-300`)
and are discarded.

`S2RunCaptureRunner` is further along: it names the value
(`LevelTitleCardGameMode = 0x8C`, `:86`) and has a dedicated Block 1.5 that
fires exactly once per reload on the first `$8C` frame (`:289-294`), with the
comment *"`$8C` frames are manifest-only until the next `$0C` + `move_lock==0`
frame re-arms"*. Arming a load-span segment there is a small, well-sited change.

**S3K is the exception, and it matters.** `S3KCompleteRunSegmenter` arms
per *zone*, not per act (`:480-483`), and deliberately keeps trailing
`0x4C`/`0x8C` handoff frames *inside* the current segment (`:528-536`). Its
1,350-2,503-row gaps are not load spans: they contain bonus-stage results,
star-post re-entry presentation and the load. A load-span segment for S3K would
be a different and much larger design.

**Fact 1 verdict: yes for S1 and S2, cheaply. Not without redesign for S3K.**

---

## 3. Fact 2 — what a load-span segment would contain, measured row by row

Probe 2 localised every PLC write inside the span. The two MZ1 boundaries — one
an act advance (27,239), one a death restart (30,858) — produced **byte-identical
structure**, which is itself evidence that the span's shape is a property of
`GM_Level` and not of the route.

Decomposition of the 228-row span at 30,858-31,085 (row numbers relative to the
span, 1-based):

| rows | count | ROM | derivable? |
|---|---|---|---|
| 1 | 1 | `GM_Level` entry: `bset`, `ClearPLC`, `PaletteFadeOut` begins (`sonic.asm:2703-2712`) | boundary |
| 2-23 | 22 | `PaletteFadeOut` — `move.w #22-1,d4` (`_inc/Palette Fading.asm:137-143`) | **counted** |
| 24-29 | 6 | `disable_ints` / `NemDec (Nem_TitleCard)` / `enable_ints` (`sonic.asm:2719-2723`). **No V-int is taken on these rows.** | **un-timed** |
| 30-32 | 3 | level header + `AddPLC` ×2, `clearRAM` block, `disable_ints`/`ClearScreen`+VDP setup, music, title-card object (`sonic.asm:2725-2812`) | **un-timed** |
| 33-179 | 147 | `Level_TtlCardLoop` PLC drain (`sonic.asm:2814-2842`) | **counted** — engine derives 146 |
| 180-201 | 22 | `Hud_Base`, `PalLoad_Fade`, `LevelSizeLoad`, `DeformLayers`, `LevelDataLoad` (which `AddPLC`s the secondary art at row 198), `LoadTilesFromStart`, `ColIndexLoad`, `ObjPosLoad`, `ExecuteObjects`, `BuildSprites` (`sonic.asm:2856-2897`) | **un-timed** |
| 202-205 | 4 | `Level_Delay` (`sonic.asm:2957-2963`) | **counted** |
| 206-227 | 22 | `PalFadeIn_Alt` (`sonic.asm:2966`, `Palette Fading.asm:32-51`); its `RunPLC` calls drain the secondary PLC on rows 207-216 | **counted** |
| 228 | 1 | `Level_StartGame` `bclr` (`sonic.asm:2991`) | boundary |

Counted 195, un-timed 31, boundary 2 — the 34-row residual the discrepancy
entry documents, at the ±1 row convention it uses.

Two cross-checks confirm the un-timed classification:

- **The 6 no-V-int rows are zone-invariant.** Across all 21 S1 level→level gaps
  the fixtures give `Δvblank_counter = gap − 5` without exception (GHZ 236/235,
  MZ 228, SYZ 230, LZ 216/217, SLZ 219/220, SBZ 219/220), i.e. exactly six
  V-ints never fire. `VBlank_Exit` increments `v_vblank_count` on *every*
  interrupt including the lag path (`sonic.asm:685`), so a missing increment
  can only mean the interrupt did not fire. `Nem_TitleCard` is the same art in
  every zone, so a constant cost is exactly what the ROM predicts. Probe 2
  localises all six to before the `AddPLC` at row 30, i.e. to the `NemDec`
  window.
- **The 22+3 interrupt-enabled un-timed rows vary with payload** (34 rows for
  MZ, 36-37 LZ/SLZ, 38 SYZ, 39-40 SBZ, moving by a row between acts of one
  zone), which a counted loop cannot do.

---

## 4. Fact 3 — what is meaningful to compare when physics is frozen

The presentation-bridge precedent replays through
`TraceStructuralRowComparator`, which compares exactly three things
(`TraceStructuralRowComparator.java:269-300`, `:140-159`): physical BK2 input
alignment, `queue.*` load-queue state when the fixture advertises
`load_queue_state_per_frame`, and `dynamic_art.*` publication when it
advertises `dynamic_art_transfer_state_per_frame`. Applying that to a load-span
segment:

| field | status across a load span | verdict |
|---|---|---|
| `input` | The BK2 rows exist and are consumed 1:1, so alignment holds by construction. The ROM ignores input here (`Level_ChkWater` zeroes `v_jpadhold1/2`, `sonic.asm:2884-2885`). | **Trivially satisfied — not evidence.** Keep it: it is the alignment invariant every segment carries. |
| `queue.s1_nemesis_plc.*` | 147 drain rows with per-row head-pointer and patterns-left transitions, plus 10 secondary-PLC rows inside `PalFadeIn_Alt`. | **The prize.** 157 rows of exact ROM queue state that is compared nowhere today. |
| `dynamic_art.*` | The player DPLC load pair the run-gap ledger currently carries as `dynamic_art_gap_transitions`, at `segment_start − 26`. | **Meaningful, and a simplification** — see §7.4. |
| `vblank_counter` | Advances every row except the six `NemDec` rows. | **Meaningful but not comparable by the structural comparator**; it is consumed by `TraceReplayRowPolicy` as a *phase* (§4.1), which is the right place. |
| `gameplay_frame_counter` | Cleared to 0 at `sonic.asm:2917` and frozen for the whole span. | Noise. |
| `player_*`, `camera_*`, `rings`, `lag_counter` | Object RAM is cleared at row 30 and re-populated at rows 180-201; the columns hold stale, then zero, then start-position values at un-derivable rows. | **Noise, and actively harmful if compared** — they would encode the un-timed distribution as physics. A load-span segment must use the structural comparator, never the physics one. |

### 4.1 The row classifier already handles these rows

`TraceReplayRowPolicy.resolve` derives each row's `TraceExecutionPhase` from the
recorded counters, and already carries `observedVblankCounterAdvance`
(`TraceReplayRowPolicy.java:66-72`). A load-span row with the gameplay counter
frozen and the V-blank counter advancing classifies as `VBLANK_ONLY`; the six
`NemDec` rows, where neither advances, classify as `ADVANCE_ONLY`. Both phases
already exist, are already produced by the bridge, and already mean "advance
the movie, run a V-blank closure or nothing". **No new row semantics are
needed.**

This is worth naming precisely, because it is the strongest legitimacy argument
available to the proposal: recorded counters selecting a row's execution phase
is not a new authority. It is the same *contract 1, main-loop admission*
category as the `lag` column, which every trace already uses to tell the engine
that the main loop did not run on a frame the engine could not have derived.
Recording the load span extends that existing, sanctioned, cross-game mechanism
to rows that today have no representation at all.

---

## 5. Fact 4 — does it delete the admission-row condition? No, twice over

The condition is one conjunct in
`TraceRunPlaybackCoordinator.destinationReady` (`:396-399`):

```java
return rememberedLevelLoad != null
        && rememberedLevelLoad.identity().equals(observation.level())
        && observation.sharedBk2Cursor() >= destination.bk2FrameOffset()
        && (expected == null || observedBoundary != null);
```

### 5.1 S3K keeps unrecorded gaps, and the conjunct is shared

`destinationReady` is engine- and game-agnostic; the same line serves S1, S2 and
S3K. S3K's 14 large gaps in `s3k-knuckles-complete-superemeralds` (1,350-2,503
rows) are not load spans and would not be recorded by this proposal (§2.3), so
the floor is still load-bearing for them. Making the conjunct conditional on
"the previous segment is a recorded load span" is a legitimate *structural*
predicate rather than a fixture carve-out — but it retains the condition, and
retains the discrepancy entry, for every fixture that still has a bare gap.

### 5.2 Even where recorded, the same number just moves fields

With the span recorded, admission would follow the load-span segment's rows
being exhausted — `cursor >= trace.frameCount()`. That count *is* the gap
length. `Segment.bk2FrameOffset()` and `Segment.traceFrameCount()` are the same
recorded datum expressed twice; `interSegmentStepCap` already derives one from
the other (`TraceRunReplayWalker.java:863-871`). Nothing about "the recording
tells the engine how many rows the ROM's load took" changes.

What *does* change is the granularity: today one opaque 228-row floor, then
195 of those rows compared and 33 taken on trust. That is a real improvement in
verification, and it is the improvement the discrepancy's own removal condition
describes — *"so the counted and un-timed parts can be compared separately"*.
It is a **rewrite** of the entry from 228 rows to 33, not a deletion of it.

**Answer, plainly: the condition would not be removable. It would be narrowed
and, for S1/S2 fixtures, restated as a segment-exhaustion rule.**

---

## 6. Fact 5 — does it prove the un-timed residual? No, and the shape is worse than "relocated"

### 6.1 The engine cannot count the 33 rows

They are elapsed 68K execution: a Nemesis decode with interrupts off, a RAM
clear, a screen wipe with VDP register setup, a level-data decode, a full
foreground/background tile draw, and the object-placement pass. Deriving them
means a cycle model of those routines — partial 68K emulation, which is a
differently-shaped import of hardware timing rather than an escape from it.
This design reaches the same conclusion as the 2026-08-06 scope verdict and the
`72584655b` review, now with the per-block measurement behind it.

### 6.2 The residual is interleaved, not trailing

This is the finding that decides the question, and it is the reason "just
record it and let the engine idle" does not work.

The un-timed rows sit in **three separate positions**: 6 rows between
`PaletteFadeOut` and the drain, 3 more immediately before the drain, and 22
between the drain and `Level_Delay`. If the engine idles all 31 at the end of
the span — the only placement it can choose without external information — its
counted work lands at the wrong absolute rows and the recorded queue state
disagrees for the entire drain.

### 6.3 Measured: the engine is 11 rows early today, invisibly

The frontier log's 2026-08-06 entry records that after the three act-advance
defects were fixed, *"the restart occupies 168 rows (21 fade + 147 card)
against the recording's 228"*. So the engine's drain occupies span rows 22-168.
Probe 2 puts the ROM's drain at span rows 33-179.

- **147 drain rows misaligned by 11.**
- The engine appends the secondary PLC immediately after its drain
  (`Sonic1LevelInitProfile.completeInitialPresentationPlcs`, `:169-184`) and
  runs 22 `PALETTE_FADE` iterations there, so its ~10 active secondary rows land
  at span rows ~169-178 against the ROM's 207-216: **misaligned by ~38.**

Both misalignments are correct-in-substance and wrong-in-phase, and both are
invisible today because no gap row is compared. Recording the span makes each
of them ~157 red rows per boundary, at 21 S1 and 27 S2 boundaries.

There is no green path from recording alone. The only in-contract fix is a
readiness delay on the engine's own submitted PLC job — which is precisely what
hard rule 4 permits ("recorded hardware timing may drive a delay in the
art-loading pipelines of all three games — **S1 PLC**, S2 DPLC, and S3K
Kosinski queues") and precisely what the 2026-08-06 scope verdict costed at
11-18 days with phase 4 **blocked** for want of an addressable row.

**Recording the span is exactly the missing enabler for that blocked phase.**
That is the honest relationship between the two proposals: this is not an
alternative to the port, it is the port's prerequisite. Taken alone it produces
a stream nothing can consume *green*; taken together they are one programme
whose cost is the union, not the minimum, of the two.

---

## 7. Fact 6 — scope

### 7.1 Schema

`TraceRunManifest.SEGMENT_KINDS` is a closed set — `{"level", "special_stage",
"bonus_stage"}` (`TraceRunManifest.java:45`) — validated at parse with a hard
failure on an unknown kind (`:283-286`). A `"level_load"` kind is therefore a
**breaking manifest change**: manifests written with it are unparseable by any
build without the parser change, and manifests written without it are
structurally different from new ones.

Hard rule 4 states `trace_schema: 5` owns run manifests and is the sole live
contract. Two options, neither free:

- **In-place v5 extension.** Land the parser change and all seven regenerated
  run fixtures in one commit. Cheapest, but it means "v5" silently denotes two
  manifest shapes in git history.
- **`trace_schema: 6`.** Honest, and drags every `metadata.json` in the
  repository plus every version pin.

In-place is the right call, but it must be an atomic change and the schema
document must record the date the shape changed.

Per-segment `metadata.json` needs a new `trace_profile` token (e.g.
`s1_level_load`) and no schema change. Segment payloads are ~228 rows —
negligible bytes.

### 7.2 Fixtures needing re-record

All seven run fixtures, because the manifest shape changes even where no load
span exists:

| fixture | segments | level→level gaps |
|---|---|---|
| `s1/runs/s1-sonic-complete-withemeralds` | 34 | 21 |
| `s1/runs/s1-ghz-maze-roundtrip` | 3 | 0 |
| `s2/runs/s2-ehz-halfpipe-roundtrip` | 5 | 2 |
| `s2/runs/s2-sonic-tails-complete-emeralds` | 35 | 27 |
| `s3k/runs/s3k-multibonus` | 25 | 3 |
| `s3k/runs/s3-knux-multibonus-ss` | 25 | 3 |
| `s3k/runs/s3k-knuckles-complete-superemeralds` | 67 | 14 |

Capture time is not the constraint (~3.5 min each for the S1/S2 complete runs);
the publication contract is — frozen digests, a named cause for every byte-level
delta, explicit approval for the exact bytes, and full frontier re-measurement,
seven times. Standard single-act fixtures are unaffected: they never cross a
level-load boundary.

### 7.3 Blast radius

Recorder (C#): `S1RunCaptureRunner`, `S2RunCaptureRunner`, `RunManifestWriter`,
`S1/S2RunManifestWriter`, `StagedRunSegmentSink`, a new metadata writer, plus
`S1RunCaptureRunnerTests`, `S1RunCaptureRunnerStageFreeTests`,
`S2RunCaptureRunnerTests`, `S1/S2RunManifestWriterTests`, `TraceCliTests`.
`S3KCompleteRunSegmenter` needs at minimum a decision recorded, and its tests
pinned unchanged.

Replay (Java): `TraceRunManifest`, `TraceRunReplayWalker`
(`SegmentExecutionPolicy`, `segmentExecutionPolicy`, `pairBoundaries`,
`interSegmentStepCap`), `TraceRunPlaybackCoordinator`,
`DestinationAdmissionReceipt`, `TraceRunFrameDriver`, `RunSegmentAdvancer`,
`TraceSessionLauncher` (its whole transition-gap branch —
`suppressesRunNativeLevelBody`, `runGapRowContinuesSourceLevelMainLoop`,
`applyRunDestinationAdmission`), `TraceRunDynamicArtGapJournal`,
`TraceRunDynamicArtGapComparator`, `TraceCatalog`. Tests:
`TestTraceRunManifest`, `TestTraceRunPlaybackCoordinator`,
`TestTraceRunSegmentExecutionPolicyCatalog`, `TestTraceRunFrameDriver`,
`TestTraceRunReplayWalkerControlFlow`, `TestTraceSessionLauncherRunBranch`,
`TestTraceRunDynamicArtGapJournal`, `TestTraceRunDynamicArtGapComparator`,
`AbstractRunChainTest` and its four subclasses, `TraceV5RunFixture`,
`TestS2SyntheticRunFixture`.

### 7.4 One genuine simplification

`dynamic_art_gap_transitions` and its whole apparatus —
`DynamicArtSubmissionOrigin.RunGap`, `S1DynamicArtObserver.PublishGap`,
`DynamicArtLifecycleService.gapTransitions`, `TraceRunDynamicArtGapJournal`,
`TraceRunDynamicArtGapComparator`, the `movie_logical_frame` /
`gap_edge_index` field contracts and the `segment_start − 26` invariant —
exists solely because the load span is not recorded. Every edge it carries
would become an ordinary in-segment `dynamic_art` edge. That is a real
architectural payoff and should be counted on the credit side, though it is
also a migration in its own right and cannot be done in the same commit as the
recorder change.

---

## 8. Design, if it is built

Recorded here so the programme is costed against a concrete shape, not a
sketch.

### 8.1 The segment

A `"level_load"` manifest segment, emitted between a source segment and its
level destination:

- `bk2_frame_offset` = the first row on which the sampled game-mode byte has
  bit 7 set; `trace_frame_count` = rows through the last such row.
- `zone_id` / `act` = the destination's, read at the segment's own row 0 (§2.2
  measured that these are already correct there).
- `trace_profile` = `s1_level_load` / `s2_level_load`.
- No `special_stage_index`, no `bonus_stage_type`.
- Payload: the ordinary `physics.csv` shape (unchanged writer), plus
  `load_queue_state_per_frame` and `dynamic_art_transfer_state_per_frame`.
  Physics columns are recorded but are declared non-comparable by the
  segment's execution policy.

### 8.2 Recorder changes

**S1** — split the `gameMode != LevelGameMode` branch
(`S1RunCaptureRunner.cs:291-300`): on `(gameMode & 0x80) != 0` with a level
segment armed, finalize it and arm a load-span segment instead of going
unarmed; keep appending rows while the bit is set; finalize on the first row
where it is clear, which is the existing arm frame for the destination. The
existing arm gate is then reached with `Started` false, exactly as today, and
the destination's `bk2_frame_offset` is unchanged. The shared aux engine's
cross-segment carry-over (spec §8) must be preserved: load-span rows must not
advance level trackers.

**S2** — the same, sited in the existing Block 1.5
(`S2RunCaptureRunner.cs:289-294`), which already fires exactly once per reload
on the first `$8C` frame.

**S3K** — no change. Record the decision and its reason (§2.3) in
`tools/bizhawk-headless/docs/`, and pin `S3KCompleteRunSegmenterTests`
unchanged so the deferral is enforced rather than assumed.

### 8.3 Replay changes

- New `SegmentExecutionPolicy.LEVEL_LOAD_SPAN`, selected from the manifest kind
  (not from row shape — unlike the bridge, the kind is explicit).
- The policy routes the segment to `TraceStructuralRowComparator` and to the
  existing `TraceReplayRowPolicy` phase classification (§4.1). No new phases.
- `ownsCurrentSegment` for the new kind: destination level identity plus the
  cursor inside the segment's row range, mirroring the bridge branch
  (`TraceRunPlaybackCoordinator.java:417-426`).
- `destinationReady` for a `"level_load"` destination: the source's own closure
  plus the destination identity — no offset floor, because the source's
  exhaustion supplies it.
- `destinationReady` for a level destination whose *predecessor* is a recorded
  load span: drop the `>= bk2FrameOffset` conjunct; retain it otherwise. This
  is a structural predicate on the manifest's own topology, not a fixture
  identity.
- `TraceSessionLauncher`'s gap branch shrinks to the special-stage and
  bridge-split gaps (still 1 row) and the S3K gaps. `suppressesRunNativeLevelBody`
  must not be reused for load-span rows: those rows now have an owner.

### 8.4 The green-path dependency

Nothing above makes the segment pass. Green additionally requires an S1/S2
`hardware_timing.jsonl` stream and an S1/S2 production submitter, so the
engine's already-submitted PLC job can have its readiness deferred by the
measured 11 rows (and the secondary job by ~38). That is the rejected path's
phases 1-3 and 5-8, plus its §4.2 registry problem: adding a kind makes it
`RECORDED` for every fixture with a timing stream
(`HardwareTimingService.validateAdmissionPolicies`), dragging 25+ S3K timing
fixtures into a re-record. **This dependency is the reason for the NO-GO, and
it must not be discovered halfway through phase 3.**

---

## 9. Verification strategy

1. **Recorder** — C# unit tests for the bit-7 arm/finalize on synthetic hosts,
   including: a span with no PLC work, a span crossing a `$10` detour, and a
   run ending mid-span. Behavioural test asserting that the destination's
   `bk2_frame_offset` and `trace_frame_count` are byte-identical to the current
   fixture's, so the change is provably additive.
2. **Manifest-shape regression** — a fixture-driven test asserting that for
   every level destination, either a `"level_load"` predecessor exists whose
   rows exactly cover the former gap, or the offset floor still applies. Pins
   §2.2's coextensivity as data.
3. **Measured-decomposition test** — over the regenerated
   `s1-sonic-complete-withemeralds`, assert for every load-span segment that
   `traceFrameCount − derivedSpan(zone) ∈ [0, N]` and that exactly six rows
   classify `ADVANCE_ONLY`. Turns §3 into a unit failure instead of a whole-run
   failure. Run the same over S2.
4. **Structural-comparator confinement** — a guard asserting a `"level_load"`
   segment can never reach the physics comparator, and that its
   `RunPlaybackObservation` carries no gameplay owner.
5. **Expected-red baseline** — before the timing work lands, pin
   `TestS1CompleteEmeraldVisualRun` at the measured red (drain rows misaligned
   by 11) rather than letting it look like a regression. Record the exact first
   error row/field in `docs/status/trace-frontier-log.md`.
6. **Green gate** — after the timing work, the whole span green at every S1 and
   S2 boundary, `TestHardwareTimingAuthorityGuard`,
   `TestS1S2PlcComparisonOnlyGuard` and `TestCommittedHardwareTimingFixtures`
   green, and `mvn test -Dtest='*TraceReplay'` unchanged.
7. **Publication** — the full contract for each of the seven fixtures.

---

## 10. Phased estimate

| Phase | Work | Estimate |
|---|---|---|
| 1 | `"level_load"` manifest kind, parser, validation, `TraceRunManifest` tests | 0.5 day |
| 2 | S1 + S2 recorder arm/finalize, sink, metadata writer, C# tests, review | 2-3 days |
| 3 | `LEVEL_LOAD_SPAN` policy, coordinator/walker/driver/launcher changes, Java tests | 3-4 days |
| 4 | Re-record + publish 7 run fixtures through the publication contract | 2-3 days + 7 approval gates |
| 5 | Frontier re-measurement, expected-red pinning, discrepancy entry rewrite | 1 day |
| **5a** | **Interim state: 21 S1 + 27 S2 act boundaries red at ~157 rows each** | — |
| 6 | S1/S2 `hardware_timing.jsonl` recorder engine + run-sink wiring + tests | 2-4 days |
| 7 | S1/S2 production `HardwareTimingService` submission in the PLC pipeline | 2-3 days |
| 8 | Registry widening; re-record + re-publish 25+ S3K timing fixtures | 3-5 days + approval gate |
| 9 | Retire `dynamic_art_gap_transitions` and its journal/comparator | 1-2 days |
| | **Total to green** | **~17-25 days, 8 approval gates** |
| | **Total to the interim red** | **~9-12 days, 7 approval gates** |

For comparison, the 2026-08-06 scope verdict's recommended path — bound the
existing floor with a residual budget, guard its shape, pin the measured
residuals as data — is **~2.25 days with no re-record**, and delivers the one
safety property that actually matters today: a derivation regression inside the
195 counted rows fails loudly instead of being absorbed.

---

## 11. Recommendation

**NO-GO now.**

1. Take the 2026-08-06 recommended path (residual budget, shape guard,
   measured-residual regression test). It is 2.25 days, needs no fixture
   re-record, and closes the only way the current mechanism can hide an engine
   bug.
2. Fold the measurements in §2 and §3 into the *Whole-Run Level-Restart
   Admission Row* entry, replacing its prose list of un-timed steps with the
   measured three-block decomposition and the zone-invariant six-row
   `NemDec` figure. The entry's second removal condition stays as written; this
   document is the costing behind it.
3. Record in the same entry that the engine's counted work is **11 rows early**
   relative to the ROM for the whole title-card drain (§6.3). That is a known,
   quantified, currently-unobservable engine/ROM phase difference, and it should
   be on the books as a discrepancy in its own right rather than as an
   implication of another one.
4. Revisit this design if and when an S1/S2 hardware-timing consumer is built
   for any other reason. At that point recording the load span stops being a
   standalone cost and becomes phase 1 of a programme that already has to
   happen — and it is the step that unblocks the port path's blocked phase 4.

**What would change the verdict:**

- A second, independent consumer of S1/S2 recorded hardware timing appearing.
  Then phases 6-8 are already paid for and phases 1-5 become the cheap part.
- The S3K slice reaching release, freeing the trace lane for a multi-week
  fixture-shape migration.
- Evidence that the 11-row drain misalignment causes a *gameplay-visible*
  defect rather than an unobserved one. Today it is contained entirely inside a
  span in which nothing gameplay-visible happens; if a boundary defect were
  ever traced to it, the cost calculus inverts immediately.

**What would not change it:** the multiplicity argument. 21 boundaries in S1
and 27 in S2 all need the same self-referential fact, and one uniform rule
already supplies it to all of them. Multiplicity argues for a shared mechanism
over carve-outs; there are no carve-outs.
