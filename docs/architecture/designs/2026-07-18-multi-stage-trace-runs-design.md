# Multi-Stage Trace Runs — Special/Bonus Stage Capture, Transitions, and Replay — Design

Date: 2026-07-18
Status: Revised after adversarial review; pending owner sign-off

## Goal

Extend trace support so that a single BizHawk bk2 recording in which the player
enters and leaves special/bonus stages is captured correctly and replayed
correctly, end to end:

1. Trace capture for S1, S2, and S3K special stages.
2. Trace capture for all three S3K bonus stages (gumball, glowing spheres /
   pachinko, slot machine).
3. Transitions into and out of special/bonus stages captured as part of the lua
   recording and handled correctly by the trace framework, both headless
   (JUnit replay) and visual (test-mode playback).

## Decisions locked with the owner (2026-07-18)

1. **Transition depth: chained segments + boundary assertions.** The recorder
   splits a run into typed segments linked by a manifest. Replay chains them,
   comparing each segment per-frame through its own stack. Transition
   presentation frames (fades, title cards, results tallies) are recorded but
   not per-frame compared. Boundary state IS asserted (return position,
   star-post restore, ring/emerald/reward carry-over). This matches the S2 SS
   precedent of recording-but-not-comparing the results tail.
2. **Comparator depth for new stage interiors: MVP, red-allowed.** Same
   definition of done as the shipped S2 SS pipeline
   (`docs/architecture/designs/2026-07-09-s2-special-stage-trace-design.md`):
   recorder captures generously, replay loads/steps/reports faithfully, core
   fields compared; tests may be red; divergence reports seed follow-up fix
   campaigns.
3. **Recordings: fix the S3K complete-run recorder gap + dedicated stage
   bk2s.** The complete-run recorder gains a mode guard so stage detours split
   segments instead of silently polluting them. New stage traces come from
   short dedicated bk2 recordings per stage (like the shipped
   `s2-lvl-select-special-stage.bk2`), not from re-recorded full playthroughs.
4. **Sequencing: S3K first** (bonus stages, then blue spheres, then slots
   depth), then S1 maze, then the S2 round-trip retrofit. To keep plans
   honestly sized, the S3K work decomposes into four plans, each engine-side
   addition living in the plan that consumes it (see the numbered additions
   list in Component 2):
   (a) manifest schema + recorder mode-transition state machine +
   complete-run mode-guard fix + parser/schema tests;
   (b) the gumball/pachinko per-segment headless slice + its bonus-aware
   bootstrap branch (addition #7);
   (c) the chained run driver + boundary assertions + the coordinator peeks
   (addition #1) — gumball/pachinko chain first, the cheapest stage settling
   the continuous-engine model;
   (d) visual run chaining + launch-config generalization (addition #5).
   S3K blue spheres (additions #3, #6), slots depth (addition #4), S1 maze
   (additions #2, #6), and the S2 retrofit follow as their own plans on the
   proven foundation.

## Verified ROM facts (disasm)

- **S1:** `id_Special = $10` (`GM_Special`), `sonic.asm:446`. Level = `$0C`.
- **S2:** `GameModeID_SpecialStage = $10` (already relied on by the shipped
  `s2_ss_trace_recorder.lua`).
- **S3K:** `GameModes` table (`sonic3k.asm:430-451`): Level entries at `$8`
  and `$C`, but `$8` is the **attract-mode demo** (`move.b #8,(Game_mode)`,
  `sonic3k.asm:5715`), which real player recordings never hit — the recorder
  guard `(Game_Mode & 0x0F) == 0x0C` (family mask with load-handoff bits 6/7)
  **deliberately excludes `$8`**; do not "fix" the mask to include it.
  `SpecialStage = $34`, `SpecialStage_Results = $48`, BlueSpheres standalone
  title/results = `$2C`/`$30` (out of scope).
- **S3K bonus stages are ROM levels**: they live under `Levels/Gumball`,
  `Levels/Pachinko`, `Levels/Slots` in skdisasm and run under the
  level-family game mode with zone ids `$13` (Gumball), `$14` (Pachinko),
  `$15` (Slots), readable from the `LevelMusic_Playlist` table
  (`sonic3k.asm:7496-7498`); the engine models them as zones
  `0x13`/`0x14`/`0x15`.
- **`Special_bonus_entry_flag`** (`sonic3k.constants.asm:831`): `1` entering a
  Special Stage, `2` entering a Bonus Stage — the ROM-side transition
  discriminator.
- **Return anchors:** player init skips re-saving `Saved_X_pos`/`Saved_Y_pos`/
  `Saved_art_tile`/`Saved_solid_bits` when `Special_bonus_entry_flag == 2` or
  `Last_star_post_hit != 0` (`sonic3k.asm:21917-21929`, Tails
  `:26118-26129`, Knuckles `:30364-30375`) — these RAM cells are the recorded
  ground truth for return-position boundary assertions.

## Current-state facts the design builds on (explored 2026-07-18)

- **Recorders** (`tools/bizhawk/`): standalone copy-derived scripts, no shared
  lua module. Level recorders (S1/S2/S3K) stop cleanly when `Game_Mode` leaves
  level; the S1 complete-run recorder treats a non-level mode as a segment
  boundary and skips stage frames. **The S3K complete-run recorder has no mode
  guard on its per-frame CSV write** — once armed it writes level-schema rows
  every frame regardless of mode, so a stage detour would silently pollute the
  active zone segment with garbage rows (latent today only because recorded
  routes avoid stages). Only `s2_ss_trace_recorder.lua` is stage-aware (arms
  on `$10`, real `emu.islagged()` lag column, RunObjects-pass PC hooks).
- **Replay**: two fully separate stacks sharing only `TraceMetadata`, the BK2
  reader (`Bk2Movie`, `RecordedInputSnapshots`), and `SpecialStageInputMapper`.
  Level: `TraceData`/`TraceFrame` (CSV v7, 42 cols) →
  `AbstractTraceReplayTest` → `LevelFrameStep`. S2 SS: `SpecialStageTraceData`
  / `SpecialStageTraceFrame` (48-col SS schema) →
  `S2SpecialStageReplayHarness` driving `Sonic2SpecialStageProvider` directly.
  Profile discriminator: `trace_profile` in `metadata.json`. **Nothing today
  handles a trace spanning a game-mode transition.**
- **Visual test mode**: `TraceCatalog` → `TestModeTracePicker` →
  `TraceSessionLauncher` with two parallel branches (level driver vs SS
  session). The SS branch owns a skip-gate in
  `GameLoop.updateSpecialStageMode()` (lag-row skips + trace-fed input) and
  its own fade-out end.
- **Engine transitions**: `LevelTransitionCoordinator` requests
  (`requestSpecialStageEntry`, `requestSpecialStageFromCheckpoint`,
  `requestBonusStageEntry`) consumed in the LEVEL tick →
  `GameLoop.enterSpecialStage()`/`enterBonusStage()`; return state in
  `BigRingReturnState` / `BonusStageState` (including
  `savedLastStarPostHit`); checkpoint/star-post restore on bonus exit.
- **Stage implementations**: all six exist and are gameplay-wired.
  S1 maze (`Sonic1SpecialStageManager`), S2 halfpipe, S3K blue spheres
  (`Sonic3kSpecialStageManager`) are provider-driven (own `update()`, bypass
  `LevelFrameStep`). Gumball and pachinko run on the normal LEVEL pipeline as
  objects in bonus zones. The slot machine also runs under the LEVEL pipeline
  but drives a dedicated `S3kSlotBonusStageRuntime` via coordinator hooks
  (`updateDuringLevelFrame`, `suppressesDefaultCameraStep`).

## Architecture — trace run = manifest + typed segments

A **trace run** is a directory containing `run_manifest.json` plus N ordered
**segment** trace directories, all referencing one shared `source_bk2`. Each
segment is a self-contained trace in an existing or new per-mode format,
discriminated by `trace_profile` exactly as today:

| Segment kind | trace_profile | Row schema | Replay stack |
|---|---|---|---|
| Level | existing level profiles | CSV v7, 42-col | `AbstractTraceReplayTest` / `LevelFrameStep` |
| S2 special stage | `s2_special_stage` (shipped) | 48-col SS schema | `S2SpecialStageReplayHarness` (shipped) |
| S1 special stage | `s1_special_stage` (new) | new maze schema | new harness driving `Sonic1SpecialStageProvider` |
| S3K special stage | `s3k_special_stage` (new) | new blue-spheres schema | new harness driving `Sonic3kSpecialStageProvider` |
| S3K bonus stage | `s3k_bonus_stage` (new) | **reuses CSV v7 level schema** | level replay stack + bonus-zone bootstrap |

Because S3K bonus stages are ROM levels, bonus capture reuses the existing
42-column level row writer verbatim (player at `$B000`, rings read
identically); only metadata differs (`bonus_stage_type`, no route). Gumball /
pachinko are therefore the cheapest full vertical slice and the first proof of
the chain. Slots rides the same pipeline through the coordinator, but its
engine runtime suppresses the default camera step
(`suppressesDefaultCameraStep`), so the RAM-map verification step (Component
1) explicitly covers the slot segment's camera columns before the schema is
frozen — camera-column equivalence is verified, not assumed.

### `run_manifest.json`

- `run_schema` version, `game`, `source_bk2`, `rom_checksum`,
  `lua_script_version`.
- Ordered `segments[]`: `{dir, kind (level|special_stage|bonus_stage),
  trace_profile, bk2_frame_offset, trace_frame_count, zone/act or
  stage_index/bonus_stage_type}`.
- `transitions[]`, one per boundary: `{from_segment, to_segment, entry_kind
  (giant_ring|starpost_special|starpost_bonus|stage_exit), boundary ROM state}`
  where boundary state records `Special_bonus_entry_flag`,
  `Saved_X_pos`/`Saved_Y_pos`, `Last_star_post_hit`, ring/emerald/reward
  counts sampled before entry and after return, and the bk2 frame indices of
  mode-change edges.

Standalone single-segment traces (everything that exists today) remain valid
without a manifest — no migration. Segments inside a run are also
independently replayable **by the per-segment tests**: each carries its own
`bk2_frame_offset` plus the boot parameters its stack needs in segment
metadata (`special_stage_index` resolved at entry time, `bonus_stage_type`,
team), so a stage-interior test does not depend on replaying the preceding
level segment. Per-segment tests stay green-able individually; transition and
carry-over validation is exclusively the chain test's job (it runs one
continuous engine — see *Chained run driver*). This bounds the
chained-divergence risk: if a level segment diverges before the giant ring,
only the chain test loses signal, not the stage-interior test.

The boundary-window tolerance used by the chained driver (how many frames of
slack the engine gets to raise a transition relative to the recorded
mode-change edge) is a **single global constant** in the driver — explicitly
not a per-transition, per-zone, or per-route tunable, per the no-carve-out
rule. It is not a manifest field.

## Component 1 — Capture (single-pass multi-mode recorders)

**Decision: single-pass.** One lua session observes all modes in one bk2
playthrough. The alternative (two passes over the same bk2 with different
recorders, stitched afterward) was rejected: it doubles capture runs, splits
the boundary observation across two scripts, and needs a cross-pass
consistency checker, for no data benefit given deterministic emulation.

Per game:

- **`s3k_trace_recorder.lua` + `s3k_complete_run_recorder.lua`** gain a
  mode-transition state machine:
  - A level-schema row is written only under level-family mode
    (`(Game_Mode & 0x0F) == 0x0C`) with a non-bonus zone. This alone fixes the
    complete-run pollution gap.
  - Leaving level mode finalizes the current level segment. Entering `$34`
    opens an `s3k_special_stage` segment with the new blue-spheres row writer.
    Entering a bonus zone (level mode, zone `0x13`–`0x15`) opens an
    `s3k_bonus_stage` segment reusing the existing level row writer.
    Returning to a normal level zone opens the next level segment.
  - Frames between segments (fade/title-card/results, `SpecialStage_Results
    $48`) are recorded only as manifest transition records, not CSV rows.
  - The recorder emits the manifest at finalize time.
- **`s1_complete_run_recorder.lua`** gains the same state machine with an
  embedded S1 maze row writer for mode `$10` (landed host, mirroring the S3K
  parity split above -- the run machinery lives in the complete-run
  recorders, not the interior-only `s1_trace_recorder.lua`).
- **`s2_trace_recorder.lua`** gains the state machine with the 48-col SS row
  writer copy-ported from `s2_ss_trace_recorder.lua` (which stays as-is for
  interior-only captures). The SS writer port includes the `emu.islagged()`
  lag column; whether the RunObjects PC hooks are ported too is decided by the
  first round-trip capture (see pacing note below).
- New stage RAM maps (S1 maze, S3K blue spheres) are derived from the disasm
  during implementation with a RAM-map verification step like the S2 SS work
  (address list validated against a live capture before the schema is frozen).
  The same pre-freeze verification covers the **bonus interiors'**
  player/ring/camera columns (all three bonus stages, symmetric with the slot
  camera-column check) — "bonus reuses the level schema" is verified against
  a live capture, not assumed. The `lag` column via `emu.islagged()` is
  captured for all stage segments (cheap, even where lag is rare).

Aux events use the S2 SS convention (`"type"` key). Per-segment
`metadata.json` carries the standard header plus `trace_profile`,
`run_id`/`segment_index` when part of a run, `special_stage_index` or
`bonus_stage_type`, and `bk2_frame_offset`.

The recording workflow scripts (`record_*_trace.bat` /
`record_s2_level_select_traces.ps1` conventions) gain run-profile validation
branches: manifest schema check, per-segment profile invariants,
frame-coverage check that tolerates uncompared transition gaps between
segments.

## Component 2 — Headless replay

### Per-segment harnesses (MVP comparators, red-allowed)

- **S3K SS / S1 SS:** new per-game loaders + replay harnesses *modeled on* the
  S2 pattern, not reusing its classes: `SpecialStageTraceData` is hard-gated
  to `trace_profile == "s2_special_stage"` and `SpecialStageTraceFrame` is a
  fixed 48-column schema, so S1/S3K SS each get their own
  `*TraceData`/`*TraceFrame` classes keyed to their profiles; the S2 classes
  stay S2-locked. Harness mechanics follow `S2SpecialStageReplayHarness`:
  logical-override input from BK2 rows via `RecordedInputSnapshots.fromBk2` +
  `SpecialStageInputMapper`, provider stepped directly, lag rows consume the
  BK2 row without stepping. MVP comparator fields:
  - S1 maze: player x/y (16.16), `ssAngle`/rotation state, rings collected,
    emerald/exit state, per-frame `lag` pacing.
  - S3K blue spheres: player grid x/y, `angle`/`turning`, `spheresLeft`,
    `ringsCollected`, `frameCounter`, clear-routine/timer state.
  - **These fields are not all currently readable** (S1 exposes none of
    them; S3K has partial getters) — exposing them is an explicit engine-side
    work item (see *Engine-side additions* below), not a pre-existing seam.
  - **Pacing:** start with VBlank pacing + lag-row skips. The S2 halfpipe's
    RunObjects-pass PC-hook pacing is NOT assumed; it is escalated to only if
    a stage's divergence report shows pass-bisection artifacts.
- **Bonus segments:** reuse `AbstractTraceReplayTest` and `TraceBinder`.
  Bootstrap differs by stage:
  - **Gumball / pachinko (MVP):** pure LEVEL-pipeline object zones — expected
    to boot via the existing fixture path (`withZoneAndAct(bonusZone, 0)`)
    plus a small bonus-aware bootstrap branch in
    `TraceReplaySessionBootstrap` (team/ring preconditions from metadata).
  - **Slots:** loading the zone is not enough — the slot runtime bootstraps
    only via `Sonic3kBonusStageCoordinator.onDeferredSetupComplete()`, which
    in live play fires from GameLoop's fade→TITLE_CARD→BONUS_STAGE sequence
    (`applyDeferredBonusStageSetup`) layered on top of the normal
    `loadZoneAndAct` load. The headless harness must invoke the coordinator's
    deferred-setup completion after fixture load (an explicit engine/test
    seam, see *Engine-side additions*). Slots is sequenced after
    gumball/pachinko for exactly this reason.
  - Slot-reel/reward internals are recorded as aux events (diagnostic-only at
    first); the level-schema player/camera/ring comparison is the MVP gate.

### Chained run driver (new) — one continuous engine

**Model commitment:** the chained driver runs **one continuous headless
Engine through GameLoop's real mode transitions** — the same continuous-engine
model the visual run branch (Component 3) uses. It does NOT hand segments to
the standalone per-segment harnesses: those harnesses construct providers
directly and bypass GameLoop entirely, so a driver built on them would never
populate `BigRingReturnState`/`BonusStageState` and would have no real engine
state for the boundary assertions to compare. In-chain interior comparison is
per segment kind: **bonus interiors** compare via the reused level
schema/comparator against the live engine (they run on the level pipeline —
this is what plan (c)'s gumball/pachinko chain uses); **special-stage
interiors** compare against the live provider via the new
`captureComparisonState()` accessors with input fed and lag rows skipped
through the existing GameLoop SS trace gate — a path that comes online with
the blue-spheres and S1-maze plans, after plan (c). Headless feasibility of in-engine
mode transitions is established precedent
(`TestGameLoopSpecialStageEntryPresentation`, `TestGameLoopSpecialStageSkipGate`,
`TestPachinkoTitleCardIntegration`), though no trace-replay test drives one
today — this driver is the first.

Division of labor, stated explicitly:

- **Standalone per-segment tests** (previous section) use the direct
  harnesses / `withZoneAndAct` fresh boots. They are independently runnable
  and green-able but **deliberately cannot validate boundary carry-over**.
- **The chain test** validates transitions and boundary carry-over on the
  continuous engine. The "independently replayable standalone" property of
  segments applies to the per-segment tests, not to the chain.

A headless `TraceRunReplay` walker reads the manifest and drives the
continuous engine:

1. Replays level segment N with normal per-frame comparison via the
   GameLoop-driven comparator path (the `TraceReplayDriver` /
   live-comparator mechanism the visual launcher already uses) — not the
   standalone `AbstractTraceReplayTest`/`LevelFrameStep` stack the
   Architecture table maps to per-segment tests.
2. At the recorded boundary, asserts the engine **organically** raised the
   matching transition from replayed inputs alone: giant ring touched / star
   post + ring threshold → the matching entry request fires within the global
   boundary-window tolerance of the recorded mode-change edge.
   `LevelTransitionCoordinator` currently exposes only *consuming* detectors
   for these requests (`consumeSpecialStageRequest()`,
   `consumeBonusStageRequest()`), which the engine's own LEVEL tick needs —
   a test cannot consume-to-observe without breaking the chain. **Adding
   non-consuming peeks (`isSpecialStageRequested()`,
   `peekBonusStageRequest()`), mirroring the existing
   `isRespawnRequested()`/`isTitleCardRequested()` pattern, is an explicit
   in-scope production change** (see *Engine-side additions*).
3. Lets GameLoop run its real transition (fade → title card / SS entry)
   without per-frame comparison (same spirit as the existing
   VBLANK_ONLY/ADVANCE_ONLY phases), then resumes per-frame comparison in the
   stage segment against the live provider.
4. On stage exit (again a real GameLoop transition), asserts boundary state
   against sources that actually exist, **split by entry kind**:
   - **Bonus return:** return position vs recorded
     `Saved_X_pos`/`Saved_Y_pos`, star-post index restore
     (`BonusStageState.savedLastStarPostHit`), ring carry-over
     (`BonusStageState.savedRingCount`).
   - **Special-stage return:** return position vs recorded saved position,
     ring carry-over (`BigRingReturnState.rings` — the SS return record has
     no star-post field), and emerald count read from
     `GameStateManager.getEmeraldCount()` (emeralds are global game state;
     neither return record holds them).
   - Extra-life/reward carry-over is **deferred**:
     `BonusStageState.savedExtraLifeFlags` is currently a hardcoded stub
     (`GameLoop.java` TODO), so asserting it would compare against a
     placeholder — wiring it is listed as a follow-up, not an MVP boundary
     assertion.

**Comparison-only invariant holds throughout** (per the
`trace-replay-bug-fixing` skill): no trace field is ever hydrated into engine
state; segments after a boundary compare against an engine that got there by
playing the recorded inputs. Boundary assertions are comparisons, not seeds.

Divergence reports keep the existing `target/trace-reports/` naming with
profile-derived prefixes (chain runs add a segment-index suffix), remain
`TraceTriageTool`-compatible, and enter `docs/status/trace-frontier-log.md`.

### Engine-side additions (src/main), enumerated

These are production changes this design *requires*; the review pass
confirmed none of them exist today. Each is comparison/observability-only —
no gameplay behavior change:

1. **`LevelTransitionCoordinator` non-consuming peeks** —
   `isSpecialStageRequested()` and `peekBonusStageRequest()` alongside the
   existing consuming detectors, mirroring the
   `isRespawnRequested()`/`consumeRespawnRequest()` pattern. Needed by the
   chained driver's boundary assertion (step 2 above).
2. **S1 SS comparison accessors** — `Sonic1SpecialStageManager` exposes no
   getters for `sonicPosX`/`sonicPosY`/`ssAngle`/exit state (all private),
   and `Sonic1SpecialStageProvider` has no `getManager()`. Add a read-only
   comparison snapshot (a `captureComparisonState()` analog of the S2
   manager's) plus the provider accessor. Note: the S1 provider has no
   `handlePlayer2Input` — the S1 maze is single-player; the harness drives P1
   only.
3. **S3K SS comparison snapshot** — `Sonic3kSpecialStageManager` has partial
   getters (`getSpheresLeft`, `getRingsCollected`, `getFrameCounter`,
   `getClearRoutine`, `getClearTimer`) but no aggregate
   `captureComparisonState()`, and player grid position/`angle`/`turning`
   live on the `Sonic3kSpecialStagePlayer` sub-object. Add a read-only
   aggregate snapshot covering both.
4. **Slots headless deferred-setup seam** — a way for the bonus replay
   harness to invoke `Sonic3kBonusStageCoordinator.onDeferredSetupComplete()`
   after fixture load (package-visible hook or a small coordinator method),
   since the live trigger is GameLoop's fade→title-card→bonus mode sequence
   which the headless fixture does not drive.
5. **Visual launch-config generalization** — per-game awareness in
   `TraceSessionLauncher.prepareSpecialStageConfiguration` (Component 3).
6. **S1/S3K SS headless standalone-loadability (verify, may be a no-op)** —
   the standalone-harness model assumes `Sonic1SpecialStageProvider` /
   `Sonic3kSpecialStageProvider` construct-and-step headlessly the way
   `Sonic2SpecialStageProvider` does. S3K in particular resolves
   `special_stage_index` at entry and loads ROM art/PLC data the S2 halfpipe
   doesn't. Planning includes an explicit check; any required headless-init
   hooks become additional engine-side items. Intentionally split into two
   per-provider sub-items: the S3K check lives in the blue-spheres plan, the
   S1 check in the S1-maze plan.
7. **Bonus-aware bootstrap branch in `TraceReplaySessionBootstrap`**
   (`src/main/java/com/openggf/trace/replay/`) — the gumball/pachinko
   headless slice's boot path (team/ring preconditions from metadata, bonus
   zone via `withZoneAndAct`). Disclosed in the per-segment harness section;
   enumerated here because this list is the authoritative inventory of
   required production changes.
8. **BONUS_STAGE playback bridge** (discovered in plan-(c) review,
   2026-07-19) — `PlaybackDebugManager` is hard-gated to `GameMode.LEVEL`:
   `isDriving` rejects `BONUS_STAGE`, and `GameLoop.updateBonusStageMode`
   never calls `onLevelFrameAdvanced()`/`shouldSkipCurrentGameplayTick()`,
   so during a bonus interior the BK2 forced-input feed stops and the
   playback cursor freezes — the chained driver would silently desync at
   the first bonus segment and every segment after it. Fix: widen
   `isDriving` to accept `BONUS_STAGE` and mirror `updateLevelMode`'s two
   playback calls in `updateBonusStageMode`. Replay/observability-only: with
   no active playback session both paths are no-ops in normal play. Lives in
   plan (c).

## Component 3 — Visual test mode

- `TraceCatalog` learns run manifests (a run renders as one entry) and the new
  stage profiles (profile-aware labels, as the S2 SS work already added).
- `TraceSessionLauncher` gains a run branch that chains its two existing
  launch paths: the level driver plays level segments; when the engine
  organically raises stage entry, the launcher switches the input feed to the
  stage segment; back to the level driver on return.
- The SS tick/input gate (`applySpecialStageTraceInputIfActive`,
  `shouldSkipCurrentSpecialStageTick`, both on `TraceSessionLauncher`) is
  **already provider-generic** — the gate methods contain no provider lookup
  at all, and the launch path resolves the provider generically via
  `GameServices.module().getSpecialStageProvider()` in
  `finishSpecialStageLaunch()`. The gate needs no refactor. What actually
  needs generalizing is the **launch / configuration path**:
  `prepareSpecialStageConfiguration` currently assumes SS traces are S2-only
  (it omits the level path's S3K fresh-load branch).
  That launch-config path gains per-game awareness so S1/S3K SS segments can
  launch; the gate itself is reused as-is. Bonus segments need no new gate —
  they already run under the level pipeline the existing level trace driver
  paces.

## Component 4 — Recordings

New short dedicated bk2s (recorded once the recorder work lands):

- S3K: level→gumball→level, level→pachinko→level, level→slots→level (star
  post with ring counts chosen to select each type via the
  `((rings-20)/15)%3` formula — ROM `loc_2D47E`, `sonic3k.asm:61886-61912`,
  already cited by `Sonic3kBonusStageCoordinator`),
  level→blue-spheres→level (giant ring).
- S1: level→maze→level (GHZ big ring).
- S2: level→halfpipe→level (star post) — the round-trip complement to the
  shipped interior-only trace.

The S3K complete-run movie is not re-recorded; its routes avoid stages.
Future complete-run movies may include detours once the mode guard lands.

## Testing the infrastructure

All new tests are JUnit 5 / Jupiter only (repo mandate).

- Parser unit tests per new CSV profile (round-trip hand-built rows).
- Manifest schema validation test + `TraceCatalog` scan test over a
  run-manifest directory.
- Recorder contract tests per the S2 SS precedent (artifact invariants
  validated before replay).
- Determinism test: two replays of the same run produce identical reports.
- Mode-guard regression coverage at two layers: the recorder workflow's
  validation scripts assert the lua guard's record-time behavior (a stage
  detour must produce split segments, never level rows); a JUnit test covers
  the Java layer — parser/manifest handling of a synthetic multi-segment
  artifact with a stage detour.
- Existing suites untouched: all new paths are gated by `trace_profile` /
  manifest presence; full trace sweep before merge.

## Out of scope (follow-ups)

- Green campaigns for the new stage interiors — each divergence report seeds
  its own follow-up plan, per the S2 SS precedent.
- Per-frame comparison of transition presentations (fades, title cards,
  results tallies) — recorded, not compared.
- Rewind integration for trace runs.
- S3K Blue Spheres standalone game modes (`$2C`/`$30`) and competition mode.
- Re-recording existing movies; S1/S2 complete-run stage detours.
- Wiring `BonusStageState.savedExtraLifeFlags` (currently a stubbed TODO) and
  the extra-life/reward carry-over boundary assertion that depends on it.
