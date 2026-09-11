# S2 Special Stage Trace Capture & Replay — Design

Date: 2026-07-09
Status: Approved for planning

## Goal & Scope

Add a new trace type for Sonic 2 special stages: a BizHawk lua recorder that captures
per-frame ROM truth inside `GameMode_SpecialStage`, plus engine replay support
(headless JUnit + visual test mode) that steps the engine's S2 special stage
deterministically against the recorded trace.

Motivating bug areas (to be *exposed* by this work, *fixed* by a separate follow-up plan):

1. Control locked/unlocked at the wrong times.
2. Tails CPU sidekick behavior. Team support already exists in
   `Sonic2SpecialStageManager.setupPlayers()` — but it is *unreachable through the
   standard team config*: `setupPlayers()` switches on the literal main-character
   code `"sonic_and_tails"`, which nothing in the engine's normal team-selection
   flow (`MAIN_CHARACTER_CODE="sonic"` + `SIDEKICK_CHARACTER_CODE="tails"`,
   `ActiveGameplayTeamResolver`) ever produces — the likely root cause of
   "sidekick support isn't available". Additionally, the engine's `tailsCtrlRecordBuf`
   (`int[16]`) matches the ROM's `SS_Ctrl_Record_Buf` size (16 words,
   `s2.constants.asm:2039-2041`); the 180-frame (`$B4`) `Tails_control_counter`
   P2-override window is also already implemented
   (`Sonic2SpecialStageManager.java:1422-1429` vs `s2.asm:70437`). The remaining gap
   is *semantic* parity — buffer shift order / delayed-tap index
   (`s2.asm:70426-70436`) and the P2-vs-solo branch conditions
   (`s2.asm:70411-70417`) — which the trace's divergence report will adjudicate.
3. Playback speed approximated by a flat-0.35 lag compensator
   (`Sonic2SpecialStageManager.java:985-991`) instead of the ROM's real
   speed-factor/lag behavior (`SSRun_Animation_Timers`, `s2.asm:960-982`).
4. Timing of text/graphics (start banner, "GET n RINGS" flyout, rings-to-go HUD)
   and ring/bomb appearance.

**MVP scope:** special-stage interior only — trace starts at special-stage game-mode
entry (launched from level select via the existing
`docs/BizHawk-2.11-win-x64/Movies/s2-lvl-select-special-stage.bk2`) and ends at
results/exit. Sonic + Tails team.

**Definition of done:** pipeline proven end-to-end — recorder produces a valid trace;
the headless test loads it, steps the engine, and emits a faithful divergence report
(first-error frame/field); visual test mode can play it. The test may be red;
divergences become the follow-up fix plan's worklist and enter
`docs/status/trace-frontier-log.md`.

**Approach decision:** parallel SS trace profile reusing the proven S2 level-select
pipeline shape, discriminated by `trace_profile: "s2_special_stage"` end-to-end
(recorder → metadata → parser → fixture → catalog). No unification with level
`TraceFrame` (semantics differ) and no premature generic stage-mode framework —
the profile discriminator and the `TraceReplayFixture` interface are the seams a
future S1-special-stage trace can grow through.

## Frame Pacing Decision (load-bearing)

The S2 special stage lags heavily and irregularly on real hardware; BizHawk records
lag frames exactly. **Replay is trace-paced at logic-frame granularity:** the engine
steps one special-stage update per recorded non-lag frame; recorded lag frames consume
the corresponding BK2 input row without stepping (same precedent as
`skipFrameFromRecording()` for VBLANK-only frames in level traces). The flat lag
compensator is disabled during replay (`setLagCompensation(0)`). Comparison is
logic-frame to logic-frame, so it is exact.

**Press-edge rule across lag rows:** the ROM polls controllers every V-int,
*including lag frames* (`Vint_S2SS` → `ReadJoypads`, `s2.asm:837-840`), and
`Joypad_Read` computes press bits as `newHeld & ~prevHeld` between consecutive
polls (`s2.asm:1361-1387`). The fixture therefore computes press-edges against the
previous **physical** BK2 row (not the last *stepped* row) — matching the existing
`RecordingFrameDriver` behavior. A press that appears only on a lag row and is gone
by the next stepped row is legitimately invisible to game logic, in both ROM and
replay. With SS's high lag density this rule is load-bearing, so it is pinned here
rather than inherited by assumption.

Pacing and pad inputs are the only trace-derived data fed to the engine; the
comparison-only invariant (no state hydration from trace) holds unchanged.

Replacing the lag compensator for *normal* (non-replay) play is out of scope here;
the recorded lag schedule is the data source for a future state-derived lag model.

## Component 1 — Recorder: `tools/bizhawk/s2_ss_trace_recorder.lua`

Derived from `s2_trace_recorder.lua` (v9.10). Behavior:

- Idle until `Game_Mode` (`$FFFFF600`) `== 0x10` (`GameModeID_SpecialStage`); record
  every emulated frame (including lag frames, flagged) until `Game_Mode` changes.
  **Note:** the ROM runs the entire results/ring-bonus tally (Obj6F) while still
  under `Game_Mode == 0x10` — the mode only flips back to Level *after* the tally
  and PLC wait complete (`s2.asm:6794-6813`). The recorded trace therefore includes
  a results tail (~50-200+ frames) that the MVP fixture does **not** compare; it is
  retained as data for future results-screen parity work.
- Emit an aux `stage_finished` event at the frame the final checkpoint resolves
  (the ROM-side equivalent of the engine's `isFinished()`, before
  `Pal_FadeToWhite`/Obj6F). If the flag rise is observed on a lag row, key the
  event to the last non-lag logical observation and retain the raw row as
  `observed_frame`. This marks the end of the compared range. Record the later
  first Obj6F sighting separately as `results_started`; it must not redefine
  `stage_finished`.
- Assert SS mode is reached within a bounded frame count of movie start (guards
  against bk2 drift); record `bk2_frame_offset` at SS entry.

### Output trio (standard layout)

**`physics.csv`** — new csv profile, all values hex. Global columns:

| Column | Source |
|--------|--------|
| `frame`, `input`, `input_p2`, `lag` | movie inputs P1/P2, `emu.islagged()` |
| `speed_factor` | `SS_Cur_Speed_Factor` |
| `track_anim`, `track_anim_frame`, `track_drawing_index`, `track_orientation`, `track_duration_timer` | `SSTrack_*` block |
| `current_segment` | `SpecialStage_CurrentSegment` |
| `player_anim_frame_timer` | `SS_player_anim_frame_timer` |
| `rings_togo_bcd`, `check_rings_flag` | `SS_RingsToGoBCD`, `SS_Check_Rings_flag` |
| `tails_control_counter` | `Tails_control_counter` |
| `swap_positions_flag` | `SS_Swap_Positions_Flag` (see comparison caveats) |

Per character (`sonic_*` block then `tails_*` block), read from the standard object
slots (MainCharacter `$FFFFB000`, Sidekick `$FFFFB040`) using the SS field offsets
(`s2.constants.asm:138-153`):

`present, ss_x (+2A), ss_x_sub (+2C), ss_y (+2E), ss_y_sub (+30), ss_z (+34),
angle, routine, routine_secondary, status, anim, anim_frame,
rings_bcd (+3C..3E), hurt_timer (+36), slide_timer (+37), flip_timer (+33)`

**`aux_state.jsonl`** — per-frame events: checkpoint/emerald routine changes,
intro/message lifecycle transitions (start banner, "GET n RINGS" flyout via the
owning object slots plus `SS_NoRingsTogoLifetime` / `SS_TriggerRingsToGo` /
`SS_HideRingsToGo`), raw `off_00..off_3F` slot dumps for active rings/bombs near the
player plane (Java composes fields later, as level traces do), and a frame -1
`state_snapshot` (stage layout pointer `SS_CurrentLevelLayout`, ring requirement
`SS_Ring_Requirement`, initial speed factor).

**`metadata.json`** — existing fields plus `trace_profile: "s2_special_stage"`,
`special_stage_index`, `characters`, `bk2_frame_offset`, `source_bk2`.

Capture is deliberately generous (cheap at record time); the comparator binds fields
incrementally.

Recurring `run_objects_end` events identify both the current and previous
executed `Vint_S2SS` BK2 rows. Replay derives controller held/pressed state from
those rows through the normal BK2 input mapper; raw auxiliary held bytes are
diagnostics only. Recording workflow validation and the Java artifact contract
reject any relative/absolute identity or P1/P2 mask disagreement before replay.

## Component 2 — Recording workflow

Extend `tools/bizhawk/record_s2_level_select_traces.ps1`:

- Routes table gains an optional `Profile` column (default: level recorder lua;
  `s2_special_stage` dispatches the new lua).
- New route `special_stage` → `s2-lvl-select-special-stage.bk2`.
- Outputs land in `src/test/resources/traces/s2/special_stage/` (gzipped
  `physics.csv.gz` + `aux_state.jsonl.gz` + metadata + bk2 copy), same normalization.
  The script's validation helpers (`Assert-Metadata`, `Assert-ZoneActCoverage`)
  hard-require level/route concepts (`zone`, `zone_id`, `rom_zone_id`, `act`,
  `gameplay_segment`, per-act `zone_act_state` coverage) that have no SS analog —
  they gain an SS-profile validation branch (profile/stage-index/frame-count/
  bk2-offset invariants) instead of reusing the level checks verbatim. The
  frame-count invariant checks *total* recorded frames and must tolerate the
  uncompared results tail (compared range ends at the `stage_finished` aux event).
  The input-column normalizer learns the `input_p2` column (zero for this bk2;
  needed for future human-P2-override traces).

## Component 3 — Headless replay

New classes (test tree unless noted):

- `SpecialStageTraceFrame` / profile-aware parsing in the trace loader
  (`com.openggf.trace`) keyed off `trace_profile` — existing level parsing untouched.
- `SpecialStageTraceReplayFixture` — a new purpose-built replay driver *modeled on*
  (not delegating to) `SpecialStageStepper` (`game/rewind/SpecialStageStepper.java:26`).
  The stepper cannot be reused directly: it is package-private, purpose-built for
  held-rewind re-simulation, and deliberately suppresses the finish→results
  transition (`TestSpecialStageStepperReplay.finishingDuringReplayDoesNotDispatchResults`),
  while this fixture must drive the same
  `handleInput` → `handlePlayer2Input` → `update()` sequence *and* own the finish
  boundary: replay ends on the frame `SpecialStageProvider.isFinished()` fires,
  which corresponds to the trace's `stage_finished` aux event (the recorded results
  tail beyond it is not compared; the finish frame itself is, including that the
  finish fires on the *matching* frame).
- `AbstractS2SpecialStageTraceReplayTest` + concrete
  `TestS2SpecialStageTraceReplay` in `src/test/java/com/openggf/tests/trace/s2/`.

**Bootstrap contract (comparison-only invariant holds):** the engine boots the special
stage natively — team config from metadata via the same two-key pattern as
`TraceReplaySessionBootstrap.prepareConfiguration()` (relying on the
`setupPlayers()` alignment above), then
`Sonic2SpecialStageProvider.initializeStage(specialStageIndex)`; lag compensation set
to 0. No recorded player/track/object state is copied into the engine. Startup uses
the verified VBlank/non-lag pacing through the recorded `SpecialStage_Started`
transition. After that semantic boundary, each observation executes the zero, one,
or multiple ordered `run_objects_end` passes captured for it. Every pass names the
exact current and previous BK2 rows sampled by its preceding `ReadJoypads`; replay
derives held/pressed input from those movie identities, while the auxiliary held
bytes only validate the binding. The finish-causing pass is owned by its semantic
terminal lag observation. Player/object fields use the latest completed atomic pass
snapshot where required; raw VBlank fields retain observation semantics.

Frame 0 of the compared range is the first recorded frame with
`Game_Mode == 0x10`; the fixture anchors it immediately after
`initializeStage()` at the manager-owned `PRE_ROLL` phase, before the ROM player
object-creation tick. `routine` values are compared through an
explicit ROM↔engine mapping table (the ROM encodes hurt as a routine value; the
engine models it as `routineSecondary == 2`), defined in the fixture, not by raw
value equality.

**Comparator tiers:**

- Errors: per-player `present`, `ss_x`, `ss_y`, `ss_z`, `angle`, `routine`/hurt,
  decoded per-player rings, hurt/slide/flip timers; **combined** ring total;
  global `speed_factor`, `current_segment`, `track_anim_frame`,
  `player_anim_frame_timer`, refresh-gated decoded `rings_togo_bcd`,
  `swap_positions_flag`, `finished`, and the logical finish-transition boundary.
- Warnings: `tails_control_counter`, `track_drawing_index`,
  `track_duration_timer` (through the explicit countdown→elapsed mapping).
- Recorded but not currently compared: checkpoint/message diagnostics that do
  not yet have a ratcheted comparison.

**Per-player rings contract:** the ROM tracks each player's ring pickups separately
on their object slots (`ss_rings_*` at `objoff_3C..3E`; incremented per touching
object `s2.asm:70771-70789`, summed for the HUD `s2.asm:9938-9943`). The engine
stores that count on the touching `Sonic2SpecialStagePlayer`; ring collection and
bomb spill mutate that owner and the preserved combined total together. Rewind
captures each count, BCD fields are decoded to binary, and per-player mismatches
are errors.

**Further field-comparability contracts:**

- `swap_positions_flag`: ROM has one global cell, cleared by Obj09 initialization,
  toggled by whichever player jumps, and read by both players' depth routines
  (`s2.asm:69058`, `s2.asm:69247-69253`, `s2.asm:69505-69518`). The engine now
  owns the byte once on `Sonic2SpecialStageManager`; player reads and writes
  delegate to that required constructor owner, rewind captures it in the manager
  section, and the comparator treats mismatches as errors. There is no ownerless
  player construction path or silent-false getter fallback.
- Player hurt/slide/flip timers are errors.
  These object-owned bytes are compared only from atomic `run_objects_end` snapshots:
  raw VBlank rows can bisect the Obj09→Obj10 scan (the committed trace does so at
  f160 and f183), so raw rows are not a coherent two-player timer boundary.
- `player_anim_frame_timer`: `Sonic2TrackAnimator` stores the ROM RAM byte. A
  speed-factor change resets the track byte; expiry reloads the selected
  `SSAnim_Base_Duration` into both bytes and decrements the player byte once;
  non-expiry reads player+1 into `d1` without storing it (`s2.asm:960-982`). The
  value is rewind-snapshotted and compared as an error.
- `track_duration_timer`: `frameDelayCounter` counts *up* while the ROM counts
  down (`s2.asm:967`), so the warning comparison uses
  `SSAnim_Base_Duration - romTimer == engineCounter`, never raw equality.
- `rings_togo_bcd`: comparison decodes the packed BCD word and selects only the
  first completed `RunObjects` pass strictly after a recorded
  `SS_TriggerRingsToGo` clear, or an exact rising `SS_Check_Rings_flag`
  observation. It compares against `max(0, currentRingRequirement -
  combinedRings)`. It never persists across later ring collections because a
  later SST slot can change live rings after Obj5A already refreshed the cell
  (`s2.asm:71411-71582`). The final selected refresh belongs to terminal
  completed pass 2990 at raw finish observation f5181, after the ordinary loop's
  logical f5180 boundary; replay therefore emits that one final comparison from
  the post-pass engine capture with the raw observation as its semantic frame.
  The packed word must use only bits 0-11 and every nibble must be a decimal
  digit. Refresh discovery fails closed rather than silently omitting coverage:
  it accepts every complete `$FF->$00` trigger cycle (including later cycles
  after a `0->$FF` re-arm), requires a unique first completed pass after each
  clear, and rejects missing initial/clear samples, incomplete re-arms, duplicate
  or ambiguous samples/transitions, unmapped gates, and a selected observation
  containing multiple completed-pass identities (frame-only selection would be
  lossy there). The artifact must also
  contain exactly one rising `SS_Check_Rings_flag` observation and exactly one
  `stage_finished.observed_frame`, and those semantic frames must be identical.
- The `SpecialStage_Started` transition observation follows Obj5F's terminal
  pre-start pass. Replay completes only that already-pending native object pass,
  without a new VInt, before recorder pass sequence 0. This corrects the hidden
  `anim_frame_duration` phase and ratchets both player flip timers to errors.
  Obj5F's `$1E` routine-10 countdown overlaps its independently executing banner
  letter children; WAIT2 therefore begins when those children are created rather
  than after their visual flight completes. Obj5F_Init's fallthrough applies the
  first banner movement before fade; child creation stores `$1E` at elapsed zero,
  while the ascending live object scan gives every newly allocated later-slot
  banner child its first movement before display in that same allocation pass.
  Thirty following parent passes keep the counter active, and the 31st creates
  the initial GET-rings message and sets Started. Banner-child rendering remains active during
  the countdown while that initial message is hidden; checkpoint WAIT2 reuse is
  already Started and preserves immediate message visibility.
  The package-owned completion operation
  requires the exact native pending + terminal-WAIT2 + not-started state and
  throws before mutation on a wrong-phase or repeated call. Replay reaches it
  only through a test-source package bridge; there is no public provider mutator
  and no trace-event-derived gameplay flag.

Report JSON to
`target/trace-reports/s2_special_stage_<special_stage_index>_report.json` —
per-trace prefix derived from metadata, matching the existing dynamic-naming
convention (`AbstractTraceReplayTest.java:1249-1250`) so future multi-stage traces
don't overwrite each other. Existing format (first-divergence brief compatible
with `TraceTriageTool`).

**Engine-side additions (src/main), enumerated:**

- `Sonic2SpecialStageSnapshot` is package-private, so the fixture cannot read it
  from the test tree. Add a *public* read-only comparison accessor on
  `Sonic2SpecialStageManager` (a small comparison snapshot record or getters)
  exposing track animator state, checkpoint/intro phase, and per-player state —
  no behavior change, no new mutators.
- **Align `setupPlayers()` with the standard team model:** resolve the team via
  `ActiveGameplayTeamResolver` (`resolveMainCharacterCode` + sidekicks /
  `SelectedTeam`) instead of switching on the literal `"sonic_and_tails"`
  main-character code, so `MAIN_CHARACTER_CODE="sonic"` +
  `SIDEKICK_CHARACTER_CODE="tails"` spawns the team — the same two-key pattern
  `TraceReplaySessionBootstrap.prepareConfiguration()` already uses for level
  traces. This is a deliberate small behavior change (it makes the existing team
  code reachable in normal play) with its own focused test; without it, the
  fixture bootstrapping the standard config would silently get solo Sonic and
  every Tails comparison would diverge for a wiring reason, not a parity reason.

## Component 4 — Visual test mode

Sized honestly: this is a **parallel live SS trace driver** modeled on the level
`TraceSessionLauncher` stack, not a small in-place branch — the existing launcher
is level-shaped end-to-end (`TraceReplayDriver.start(zone, act)`, level camera
focus, `LevelFrameStep`-based stepping).

- `TraceCatalog` validation gains a profile switch so SS trace dirs validate
  against the SS artifact set. SS metadata carries no `zone_id`/`act` (they default
  to 0 in `TraceCatalog.tryLoad`); catalog labeling/sorting for SS entries derives
  from `trace_profile` + `special_stage_index` instead — a `TraceEntry` display
  change, called out so multiple SS traces don't all render as "zone 0 act 0".
- **`GameLoop.updateSpecialStageMode()` needs a new skip-gate**: unlike LEVEL mode
  (which has `shouldSkipCurrentGameplayTick()` at `GameLoop.java:1346` to suppress
  a gameplay tick on a lag row while advancing the BK2 cursor/VBlank counter),
  the SS path unconditionally calls `ssProvider.update()` every engine frame
  (`GameLoop.java:1049-1114`). A mirroring gate is a prerequisite for live
  trace-paced skips: on a skipped row the BK2 cursor and frame bookkeeping advance
  but `ssProvider.update()` does not run.
- The SS live driver drives special-stage entry and feeds BK2 inputs through
  `SpecialStageInputMapper` with the same trace-paced skips as headless replay.
  Note: `GameLoop.doEnterSpecialStage(...)` is `private` (`GameLoop.java:1995`; the
  one existing test caller uses reflection). Loosen it to package-private —
  `TraceSessionLauncher` lives in the same `com.openggf` package — rather than
  adding reflection in production code.
- Side benefit: paced skips reproduce the ROM's authentic perceived speed on screen,
  making side-by-side visual comparison with a BizHawk capture meaningful.

## Invariants & Error Handling

- Comparison-only invariant per the `trace-replay-bug-fixing` skill: committed code
  never hydrates engine state from trace fields.
- Startup validates BK2 input against each VBlank row; active replay validates
  every pass's exact current/previous `ReadJoypads` BK2 identities and masks.
- The trace's ERROR-severity frontier is release-gated by
  `assertNoReleaseBlockingDivergences()`; remaining WARNING fields and their first
  frontier stay explicit in `docs/status/trace-frontier-log.md`.
- Recorder hard-fails if SS mode never appears (bk2 drift guard).

## Testing the Infrastructure

- Parser unit test for the new CSV profile (round-trip a hand-built row).
- Fixture determinism test: two replays of the same trace produce identical reports.
- `TraceCatalog` scan test covering a profile-tagged trace dir.
- Existing level-trace suites untouched (new paths gated by the profile
  discriminator); full trace sweep run before merge to confirm.

## Out of Scope (follow-ups)

- Full round trip (level → SS → return transition validation).
- Results/ring-bonus tally replay (recorded in the trace tail, uncompared for MVP).
- All-7-stage complete-run traces; solo-Sonic / solo-Tails / human-P2 traces.
- S1 special stage generalization (seams intentionally left).
- All discrepancy fixes, including replacing the flat lag compensator for normal
  play with a state-derived lag model built from the recorded lag schedule —
  that is the follow-up plan, driven by this trace's divergence report.
