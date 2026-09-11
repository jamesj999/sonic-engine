# Trace Replay Testing

Trace replay tests compare the engine against a frame-by-frame recording from the original ROM
running in BizHawk. They are the highest-signal tests in the repo for physics, object timing,
spawn timing, and collision parity work.

> **Workflow skill.** When investigating or fixing a `*TraceReplay` test failure, invoke the
> `trace-replay-bug-fixing` skill (mirrored at `.claude/skills/trace-replay-bug-fixing/skill.md`
> and `.agents/skills/trace-replay-bug-fixing/SKILL.md`). It codifies the comparison-only
> invariant, the four mission rules, the diagnose-fix-regen-loop workflow, recorder extension
> recipe, and pointers to disassembly and process skills.

## The Comparison-Only Invariant

The engine must be able to play back any BK2 movie and produce ROM-correct behaviour natively,
with no trace data on its inputs. Trace replay tests prove this — they are not state-syncing
harnesses.

- The **only** input the engine receives during a trace replay test is the BK2 controller stream
  (Player 1 buttons each frame). Everything else — sidekick AI, oscillator phase, object state,
  audio, RNG, sub-pixel position — must evolve natively from the same starting conditions ROM
  had at frame 0.
- `physics.csv` and `aux_state.jsonl` (or their `.gz` variants) are **read-only diagnostic data**.
  They feed the divergence comparator and the divergence report. They are not allowed to write
  back into engine state in committed test code.
- **Pre-trace bootstrap is fine.** Setting starting position, RNG seed, oscillation pre-advance,
  and frame counter once at frame 0 is "load a save state at the BK2 starting point". The
  prohibition is on per-frame write-back during the comparison loop.

If a trace replay test passes only because engine state is snapped back to ROM-correct values
each frame, the engine has not been verified — it has been masked. Honest tests force honest
engine fixes.

## Current Trace Coverage

| Game | Test class | Trace directory |
| --- | --- | --- |
| Sonic 1 | `TestS1Ghz1TraceReplay` | `src/test/resources/traces/s1/ghz1_fullrun/` |
| Sonic 1 | `TestS1Mz1TraceReplay` | `src/test/resources/traces/s1/mz1_fullrun/` |
| Sonic 1 | `TestS1Credits00..07*TraceReplay` (×8) | `src/test/resources/traces/s1/credits_*/` |
| Sonic 2 | `TestS2Ehz1TraceReplay` | `src/test/resources/traces/s2/ehz1_fullrun/` |
| Sonic 3&K | `TestS3kAizTraceReplay` | `src/test/resources/traces/s3k/aiz1_to_hcz_fullrun/` |
| Sonic 3&K | `TestS3kCnzTraceReplay` | `src/test/resources/traces/s3k/cnz/` |

The Sonic 1 credits demos use the ROM's built-in ending replays as the input source and do not
require BK2 movies (the ROM owns the input stream).

Each trace directory contains:

- `metadata.json` — trace metadata and start state
- `physics.csv` (or `physics.csv.gz`) — per-frame core state
- `aux_state.jsonl` (or `aux_state.jsonl.gz`) — richer diagnostic events
- a `.bk2` movie used to drive the engine replay (except credits-demo traces)

Larger traces are stored gzip-compressed (`.gz`). The Java parser and the test harness load
both forms transparently. See [Compressing trace payloads](#compressing-trace-payloads) below
for the workflow.

Replay phase is derived from recorded ROM counters:

- `gameplay_frame_counter` changes only when the level main loop completed
- `vblank_counter` changes on every VBlank
- `lag_counter` is diagnostic where the ROM exposes it

## When To Use It

Use trace replay when:

- a bug is about ROM parity rather than just local correctness
- a fix affects movement, terrain collision, object timing, or hurt/bounce flow
- you need to prove that a change fixed the first real divergence instead of only masking symptoms

Prefer ordinary headless tests for small isolated behaviours. Use trace replay when the interaction
between multiple systems is the thing under test.

## Running Existing Trace Tests

Trace replay tests require:

- the matching ROM for the game(s) you want to replay, in the repo root or via system property:
  - Sonic 1: `s1.gen`, World REV01, CRC32 `AFE05EEE` (or `-Dsonic1.rom.path=...`)
  - Sonic 2: `s2.gen`, World REV01, CRC32 `7B905383` (or `-Dsonic2.rom.path=...`)
  - Sonic 3 & Knuckles: `s3k.gen`, World lock-on combined ROM, CRC32 `63522553` (or `-Ds3k.rom.path=...`)
- the `.bk2` file to be present in the trace directory (except for ROM-driven credits demo
  traces)

Run all trace tests:

```bash
mvn test -Dtest='*TraceReplay'
```

Run cross-game baseline set (the canonical "did I regress anything?" check):

```bash
mvn test -Dtest='TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS2Ehz1TraceReplay,TestS3kAizTraceReplay,TestS3kCnzTraceReplay'
```

Run one trace:

```bash
mvn test -Dtest=TestS1Mz1TraceReplay
```

> **Note on `-Dtest=` filtering.** In some configurations the surefire `-Dtest=` filter does not
> restrict the run (the full test suite executes regardless). If you observe `passed=N` where
> `N > 100` for a focused selection, that's the signal. Currently a known followup; track via
> `target/surefire-reports/TEST-*.xml` for actual focused results.

Useful optional override while tuning S1 oscillation alignment:

```bash
mvn test -Dtest=TestS1Mz1TraceReplay -Dosc.override=0
```

Reports are written below `target/trace-reports` using an owner-aware layout:

- `<profile>/<logical-key>-<lane>-<owner-hash>.json`
- `<profile>/<logical-key>-<lane>-<owner-hash>_context.txt` when the run diverges
- a sibling `.json.owner.json` ownership sidecar

The JSON groups divergences by field and frame range. The context file is the fastest way to find
the first non-cascading failure — open it, scroll to the first error, look at the side-by-side
ROM-vs-engine columns.

> **Diagnosing tip — "phantom landing" patterns.** When a sidekick lands on a
> surface ROM keeps falling through (engine `tails_y_speed=0x0000` and
> `tails_g_speed=<prior_x_speed>` while ROM `tails_y_speed` keeps advancing
> by gravity), the fingerprint maps to one of two ROM paths: terrain
> `Sonic_HitFloor` or solid-object `SolidObjectTopSloped2` /
> `SolidObject_Landed`. Add a `setGSpeed` velocity-probe in
> `AbstractPlayableSprite` that prints the call site and check the stack;
> if it points into `ObjectManager.SolidContacts.resolveContactInternal`,
> the offender is a solid object whose state machine has drifted from ROM
> (e.g. AIZ F7127 was the AIZ collapsing platform stuck in
> `state==2`/solid-stay forever; ROM's `loc_205DE` at sonic3k.asm:44850-44854
> unconditionally promotes to the falling state when its post-fragment
> timer underflows, but the engine had been gating that promotion on a
> still-standing player). The correct fix touches the object's state
> machine, not the collision sensor.

## Recording Or Refreshing A Trace

The native BizHawk headless harness is the canonical recorder for all three
games and all production profiles. Every output uses one strict contract:
`trace_schema: 5`. Ordinary level rows have 42 columns, or 43 when the recording
carries the trailing `life_count` column; game/profile selects
the dedicated S1/S2/S3K special-stage width. A v5 timing file has one grammar,
and every run manifest includes `dynamic_art_gap_transitions`, even when empty.

Metadata uses `recorder` to identify the producer and `recorder_version` to
identify that implementation. They are opaque provenance and never select
parsing or replay. `lua_script_version`, `csv_version`, `ss_csv_version`,
`hardware_timing_schema`, and `run_schema` are removed rather than renamed.

See [Trace v5 capture and publication](trace-v5-publication.md) for the capture
matrix, validator, comparator, candidate-root replay, raw-host evidence, and
exact-byte approval workflow.

Prerequisites:

- BizHawk 2.11 installed at `tools/tracechaser/.dependencies/BizHawk-2.11-linux-x64`, or set `BIZHAWK_HOME`
- the matching game ROM for the recorder you are using
- a BK2 movie for the act you want to record

Examples:

```bash
tools/tracechaser/bizhawk-headless/run.sh --mode trace \
  --rom "$S3K_ROM_PATH" --movie /absolute/movie.bk2 \
  --output /absent/scratch/candidate --trace-profile aiz_end_to_end
```

Capture only to a new scratch root. Never copy candidate bytes into
`src/test/resources/traces` until the complete candidate is validated,
compared, frozen, and explicitly approved.

Sanity-check `metadata.json` before trusting the trace:

- `game` should be `s1`, `s2`, or `s3k` to match the recorder
- `zone` and `act` should match the intended recording
- `trace_frame_count` should be in the expected range for that route
- `start_x` and `start_y` should look plausible for the level start
- `trace_schema` must be integer `5`
- `recorder` must be `native-bizhawk-headless` for publication and
  `recorder_version` must be `3.0`; neither controls replay
- `bizhawk_version` should be `2.11`
- `genesis_core` should be `Genplus-gx`

Sonic 2 level-select traces use the `level_gated_reset_aware` profile. The profile discards the
shared setup section when debug mode exits EHZ back to the menu, then starts the persisted trace at
the selected gameplay route. For these traces, `zone_id` is the engine progression id used by the
Java replay/catalog code, `rom_zone_id` is the raw Sonic 2 ROM zone id, and `act` remains 1-based in
metadata. Frame `0` must include either `zone_act_state` or a `gameplay_start` checkpoint with
`game_mode: 12` so the fixture cannot accidentally begin in the menu. The generator script
normalizes the fixture input column from the BK2 log, then validates that metadata, BK2 input
alignment, frame-zero marker, and gzip-only payload storage before copying fixtures into
`src/test/resources/traces/s2/<route>/`.

The Sonic 2 DEZ ending movie is catalog/parser-only until replay support for the ending sequence is
proven; keep it out of replay test discovery unless that support is added deliberately.

Any absent or non-5 schema and every removed version key is rejected. There is
no legacy reader or compatibility warning path.

S3K traces additionally include `aux_schema_extras` listing opt-in per-frame event types — see
[Aux event types](#aux-event-types) below.

Interpret the execution counters the same way across games:

- `gameplay_frame_counter` advances only when the level main loop completed
- `vblank_counter` advances on every VBlank
- `lag_counter` is meaningful only for Sonic 3&K; Sonic 1 and Sonic 2 record `0`

## Aux event types

`aux_state.jsonl` contains a stream of one-JSON-object-per-line events. Standard events are
present in every game's trace:

- `zone_act_state` — emitted on zone/act/apparent-act/game-mode change
- `checkpoint` — semantic milestones (e.g. `gameplay_start`, `aiz2_reload_resume`)
- `state_snapshot` — periodic pose/state captures
- `mode_change` — Sonic/Tails routine transitions
- `slot_dump` — periodic OST dumps
- `object_appeared` / `object_near` / `object_removed` — object-window tracking

S3K traces opt in to additional per-frame diagnostic events declared in `aux_schema_extras`.
Current capabilities are semantic names advertised in `aux_schema_extras`:

| Aux key | Event type | Purpose |
| --- | --- | --- |
| `cpu_state_per_frame` | `cpu_state` | Sidekick CPU routine + flight timer + cached `Tails_CPU_interact` per frame |
| `oscillation_state_per_frame` | `oscillation_state` | ROM oscillator counters per frame |
| `object_state_per_frame` | `object_state` | Per-slot OST first 32 bytes for nearby objects |
| `interact_state_per_frame` | `interact_state` | Per-player `status` / `status_secondary` / `object_control` bytes |
| `cage_state_per_frame` | `cage_state` | CNZ wire-cage status + per-player phase/state bytes |
| `cage_execution_per_frame` | `cage_execution` | M68K execution-hook hits inside CNZ cage routines (BizHawk Lua `event.onmemoryexecute`) |
| `velocity_write_per_frame` | `velocity_write` | Per-frame writer-PC trace for Tails `x_vel` / `y_vel` (BizHawk Lua `event.onmemorywrite`, frame-window-gated) |
| `position_write_per_frame` | `position_write` | Per-frame writer-PC trace for Tails `x_pos` / `y_pos` with the captured `(a1)`/`(a0)` registers (v6.11-s3k recorder; BizHawk Lua `event.onmemorywrite`, frame-window-gated). Used to disambiguate Player_1 vs Player_2 targeting in routines like `SolidObjectFull2_1P` that loop both players |
| `solid_object_cont_entry_per_frame` | `solid_object_cont_entry` | Per-frame snapshot of `(a0)`/`(a1)`/d1/d2 plus `y_radius`/`default_y_radius` and pixel x/y on entry to `SolidObject_cont` (sonic3k.asm:41394 / ROM 0x1DF90; v6.11-s3k recorder; BizHawk Lua `event.onmemoryexecute`, frame-window-gated). Used to reconstruct the `loc_1DFD6+`/`loc_1E154` push-up branch d3/d4 conditional after the fact |

All event types are **diagnostic only** — they feed the divergence comparator and the
divergence report's context window. They are never written into engine state by the test
harness in committed code (see [Comparison-Only Invariant](#the-comparison-only-invariant)).

Adding a new event type:

1. Native recorder: emit a JSONL line with a new `event` field, add the semantic
   opt-in key to `aux_schema_extras`, and add behavioral/unit coverage. Lua may
   mirror it for scratch diagnostics but is not publication authority.
2. Java parser: add a new sealed-record subtype to
   [`TraceEvent.java`](../../../src/main/java/com/openggf/trace/TraceEvent.java); parse it
   in `parseJsonLine`; add `TraceMetadata.hasPerFrame<Feature>()` + a typed accessor on
   `TraceData`. Keep v5 strict: absent optional capability means absent events,
   not a legacy compatibility branch.
3. Wire the data into `DivergenceReport.getContextWindow` rendering or a probe class.
4. Regenerate the affected trace(s). Commit the regen as a separate logical change from the
   recorder schema bump.

## Compressing trace payloads

Larger `aux_state.jsonl` and `physics.csv` files are stored gzip-compressed in the repo to keep
under GitHub's 100 MB hard limit (50 MB recommended max). The Java parser
([`TraceData.load`](../../../src/main/java/com/openggf/trace/TraceData.java)) loads either form
transparently. Use the helper script after regenerating a large trace:

```powershell
powershell -File tools/tracechaser/traces/compress-traces.ps1 -Path tools/tracechaser/bizhawk/trace_output -Recurse
```

The script:

- Walks `aux_state*.jsonl` and `physics*.csv` files.
- Skips files below 1 MB (override with `-ThresholdBytes <bytes>`).
- Compresses to `<file>.gz`, verifies the round-trip via SHA-256, and removes the uncompressed
  source unless `-KeepOriginal` is set.

After regenerating a trace, copy the resulting `.gz` files (alongside `metadata.json`) into the
test resources tree. **Never commit the uncompressed `aux_state.jsonl` or `physics.csv` for
the larger zones** — GitHub will reject the push.

## Recording The S3K End-To-End Fixture

Use the `aiz_end_to_end` profile when refreshing the full AIZ intro through HCZ handoff trace.
That profile starts at BK2 frame `0` and augments `aux_state.jsonl` with:

- edge-triggered `zone_act_state` events whenever actual zone/act, apparent act, or game mode changes
- one-shot semantic `checkpoint` events for `intro_begin`, `gameplay_start`,
  `aiz1_fire_transition_begin`, `aiz2_reload_resume`, `aiz2_main_gameplay`,
  `hcz_handoff_begin`, and `hcz_handoff_complete`

Workflow:

1. Keep the locked-on ROM at the repo root.
2. Run:

```bat
tools\bizhawk\record_s3k_trace.bat ^
  "s3k.gen" ^
  "src\test\resources\traces\s3k\aiz1_to_hcz_fullrun\s3k-aiz1-aiz2-sonictails.bk2" ^
  aiz_end_to_end
```

3. Run the fixture sanity gate against the **uncompressed** `tools\bizhawk\trace_output\aux_state.jsonl` (do this BEFORE compressing):

```powershell
$events = Get-Content tools/tracechaser/bizhawk/trace_output/aux_state.jsonl | ForEach-Object { $_ | ConvertFrom-Json }
$required = 'intro_begin','gameplay_start','aiz1_fire_transition_begin','aiz2_reload_resume','aiz2_main_gameplay','hcz_handoff_begin','hcz_handoff_complete'
$frames = $events | Select-Object -ExpandProperty frame

if (($events | Where-Object event -eq 'checkpoint' | Where-Object name -eq 'intro_begin').Count -ne 1) { throw 'intro_begin count != 1' }
if ((($frames | Sort-Object) -join ',') -ne ($frames -join ',')) { throw 'frame order regressed' }
foreach ($name in $required) {
  if (($events | Where-Object event -eq 'checkpoint' | Where-Object name -eq $name).Count -ne 1) {
    throw "required checkpoint missing or duplicated: $name"
  }
}
for ($i = 1; $i -lt $events.Count; $i++) {
  if ($events[$i].frame -eq $events[$i-1].frame -and $events[$i].event -eq 'zone_act_state' -and $events[$i-1].event -eq 'checkpoint') {
    throw "zone_act_state emitted after checkpoint on frame $($events[$i].frame)"
  }
}
```

Expected result: no output and no exception.

4. Compress the large payloads:

```powershell
powershell -File tools\traces\compress-traces.ps1 -Path tools\bizhawk\trace_output -Recurse
```

5. Copy `tools\bizhawk\trace_output\metadata.json`, `physics.csv.gz`, and
   `aux_state.jsonl.gz` into `src\test\resources\traces\s3k\aiz1_to_hcz_fullrun\`.

## Recording Sonic 1 Credits Demo Traces

Use the dedicated credits recorder when you want the ROM's ending replays rather than a human-played
BK2 route. This path forces `GM_Credits` from the title screen, waits for each demo to become active,
and records each replay into its own trace directory under `tools/tracechaser/bizhawk/trace_output/credits_demos/`.

Command:

```bat
tools\bizhawk\record_s1_credits_traces.bat ^
  "s1.gen" ^
  all
```

Record one replay only:

```bat
tools\bizhawk\record_s1_credits_traces.bat ^
  "s1.gen" ^
  3
```

Supported replay indices:

- `0` = `ghz1_credits_demo_1`
- `1` = `mz2_credits_demo`
- `2` = `syz3_credits_demo`
- `3` = `lz3_credits_demo`
- `4` = `slz3_credits_demo`
- `5` = `sbz1_credits_demo`
- `6` = `sbz2_credits_demo`
- `7` = `ghz1_credits_demo_2`

Output layout:

- `tools/tracechaser/bizhawk/trace_output/credits_demos/manifest.json`
- `tools/tracechaser/bizhawk/trace_output/credits_demos/00_ghz1_credits_demo_1/`
- `tools/tracechaser/bizhawk/trace_output/credits_demos/01_mz2_credits_demo/`
- `...`

Each replay directory contains the same core files as a normal trace capture:

- `metadata.json`
- `physics.csv`
- `aux_state.jsonl`

Credits-demo metadata adds a few fields that are useful for future trace-framework work:

- `trace_type = "credits_demo"`
- `input_source = "rom_ending_demo"`
- `credits_demo_index`
- `credits_demo_slug`
- `emu_frame_start`

Notes:

- No `.bk2` is produced or required for this workflow.
- The script exits after the last gameplay replay finishes; it does not wait through the final
  text-only "PRESENTED BY SEGA" card or the `TRY AGAIN / END` screen.
- The capture format is intentionally aligned with the existing S1 `physics.csv` / `aux_state.jsonl`
  schema so the replay-side test harness can be extended later without inventing a parallel parser.

## Adding A New Trace Test

1. Create a new directory under `src/test/resources/traces/<game>/<name>/` (e.g.
   `src/test/resources/traces/s3k/mgz/`).
2. Record (see [Recording Or Refreshing A Trace](#recording-or-refreshing-a-trace)) and run
   `tools/tracechaser/traces/compress-traces.ps1` if your payload sizes warrant it. Copy
   `metadata.json`, `physics.csv` (or `.csv.gz`), `aux_state.jsonl` (or `.jsonl.gz`), and
   the `.bk2` movie into the target directory.
3. Add a test class under `src/test/java/com/openggf/tests/trace/<game>/` extending
   `AbstractTraceReplayTest`.
4. Return the correct engine zone index, act index, and trace directory path.
5. Run the new test once and inspect `target/trace-reports` before treating the baseline as
   valid.

Minimal example (S3K):

```java
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kMgzTraceReplay extends AbstractTraceReplayTest {
    @Override
    protected SonicGame game() { return SonicGame.SONIC_3K; }

    @Override
    protected int zone() { return 7; }   // engine zone index, not raw ROM v_zone

    @Override
    protected int act() { return 0; }

    @Override
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s3k/mgz");
    }
}
```

Important: the test's `zone()` is the engine's zone index, not necessarily the raw ROM `v_zone`
value. [`TestS1Mz1TraceReplay.java`](../../../src/test/java/com/openggf/tests/trace/s1/TestS1Mz1TraceReplay.java)
shows the Marble Zone mapping nuance.

## Reading Divergences

Focus on the first non-cascading error. Everything after that may be fallout.

The main sources are:

- `physics.csv` (or `.csv.gz`)
  Core compared fields: position (x/y), velocities (x_speed/y_speed/g_speed), angle, air/rolling
  flags, ground mode, status byte. For S2 / S3K, also Tails columns.
- `aux_state.jsonl` (or `.jsonl.gz`)
  Event stream — see [Aux event types](#aux-event-types) for the full taxonomy.
- `<manifest.trace_reports>/<game>_<zone>_context.txt`
  Side-by-side ROM and engine diagnostic context generated by `AbstractTraceReplayTest`.

The current recorder/test pipeline also carries diagnostic-only fields that do not fail the test by
themselves, but greatly narrow investigation:

- subpixel position
- routine (Sonic / Tails CPU)
- camera position
- ring count
- status byte (and per-game `status_secondary` / `object_control`)
- gameplay frame counter
- VBlank counter
- lag counter
- ridden object slot / latched solid object
- ObjPosLoad cursor state
- (S3K only) Tails CPU flight timer, oscillation phase, spring/cage execution path

Typical debugging pattern:

1. Open the first error in the context file.
2. Check whether the first mismatch is terrain, object contact, hurt/bounce, or spawn timing.
3. Cross-reference the same frame in the aux event stream (use `TraceData.eventsForFrame(K)` or
   open the JSONL/JSONL.gz directly with `gzip -d -c | grep '"frame":K'`).
4. Trace the divergence **backward** to the earliest divergent frame — that's the real bug, not
   necessarily the first-error frame.
5. If the divergence involves object control, participant selection, or object lifetime, map it
   to `ObjectControlState`, `ObjectPlayerQuery` / `ObjectPlayerParticipationPolicy`, or
   `ObjectLifetimeOps` before adding local trace-specific logic.
6. Only after the first error is understood should you trust later divergences.

The `trace-replay-bug-fixing` skill contains a full diagnose-fix-regen-loop workflow with
ROM-citation requirements and per-game parity rules.

## Common Pitfalls

- Missing `.bk2` file: the test will skip even if `metadata.json` and `physics.csv` exist.
- Wrong zone index in the test class: the metadata may be correct while the engine loads the
  wrong level.
- Regenerating only `physics.csv` but not `aux_state.jsonl` or `metadata.json`: diagnostics
  become misleading.
- Treating warning-only 1px drift as the root cause when a later object timing error is the
  real break.
- Updating the trace baseline before understanding whether a behaviour change is a real fix or
  a regression.
- **Committing uncompressed large traces.** GitHub will reject any push containing files
  >100 MB. Always run `tools/tracechaser/traces/compress-traces.ps1` after regenerating a large trace.
- **Adding per-frame trace-driven hydration to the test harness.** Setting engine state from
  recorded values mid-replay violates the comparison-only invariant and masks engine bugs. If
  you find yourself wanting to "preserve" or "set" engine fields each frame from the trace,
  the engine probably has a real bug — fix it instead.
- **Fixing object trace failures with one-off local control/lifetime logic.** Prefer
  `ObjectControlState` for native object-control bits, `ObjectPlayerQuery` plus
  `ObjectPlayerParticipationPolicy` for player/sidekick participation, and
  `ObjectLifetimeOps` for destruction, respawn-latch, offscreen-expiry, and slot-transfer
  semantics. If the current engine path still needs a `level.objects` compatibility wrapper,
  use that wrapper and keep the trace fix behavior-neutral outside the diagnosed divergence.
- **Growing guard baselines to land a trace fix.** Baselines are for known legacy violations
  and should ratchet downward as object/boss/badnik code migrates. A new trace fix should not
  add raw focused-player access, first-sidekick access, object-control setters, or direct
  lifecycle mutation unless the native-only reason is documented and covered by the guard.
- **Branching on game id in shared physics/AI code.** Per-game divergences must be gated via
  the narrowest typed `GameRules` rule or an existing provider/profile/registry/object owner,
  never `if (gameId == GameId.S3K)`. When ROM uses a different semantic on a value that
  exists across games (e.g. `cmp.w y_pos(a0),d0` in `Player_LevelBound` reads centre-Y while
  the engine's `getY()` returns top-left), prefer adding a focused rule with explicit
  cross-game values over flipping an unrelated global default in the same change. See
  `PlayerMovementRules.levelBoundaryUsesCentreY` for the canonical example. Another example
  is `ObjectInteractionRules.solidObjectTopBranchAlwaysLiftsOnUpwardVelocity`, which enables
  ROM `loc_1E154` (sonic3k.asm:41606-41632) writing the top-branch position lift before the
  `tst.w y_vel(a1) / bmi.s loc_1E198` test only on S3K; S1 `Solid_Landed`
  (s1disasm/_incObj/sub SolidObject.asm:278) and S2 `SolidObject_Landed` (s2.asm:35379-35380)
  bail on upward y_vel BEFORE any lift, so the rule stays false there.
- **Edit-tool BOM/CRLF silent failure.** Some files (e.g. `CnzCylinderInstance.java`,
  `AbstractTraceReplayTest.java`) have UTF-8 BOM + CRLF endings. The Claude Code Edit tool
  has been observed to silently fail on these — it returns "successfully" but doesn't write.
  Fall back to Python `open(path, 'rb').read().replace(...)`-style edits if you suspect this.
- **Misreading aux trace `cpu_state` event field semantics.** S3K's `cpu_state` event
  reports `cpu_routine` as the raw `Tails_CPU_routine` byte — `0x06` is the NORMAL
  ground-following AI (`loc_13D4A`, sonic3k.asm:26656), NOT a "FLY" routine. Similarly,
  `flight_timer` is `sub_13EFC`'s `Status_OnObj` watchdog used by NORMAL routine
  (sonic3k.asm:26816-26847), not an active-flight indicator; it ticks any time Tails is
  riding the same `Tails_CPU_interact` slot, regardless of whether Tails is flying. And
  `target_x` / `target_y` are leader-history positions (the CPU's follow target), not
  Tails's own position — for Tails's actual coordinates, use the
  `object_state` event for slot 1 in the same frame. Always cross-reference
  `Tails_CPU_Control_Index` (sonic3k.asm:26368-26386) when interpreting `cpu_routine`
  values, and do not infer "Tails is flying" from a non-zero `flight_timer` alone.

## Related Files

Test harness:

- [`AbstractTraceReplayTest.java`](../../../src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java)

Parser (production code, used by tests + diagnostics):

- [`TraceData.java`](../../../src/main/java/com/openggf/trace/TraceData.java)
- [`TraceMetadata.java`](../../../src/main/java/com/openggf/trace/TraceMetadata.java)
- [`TraceFrame.java`](../../../src/main/java/com/openggf/trace/TraceFrame.java)
- [`TraceEvent.java`](../../../src/main/java/com/openggf/trace/TraceEvent.java)
- [`TraceBinder.java`](../../../src/main/java/com/openggf/trace/TraceBinder.java)
- [`DivergenceReport.java`](../../../src/main/java/com/openggf/trace/DivergenceReport.java)

Recorders + launchers:

- [`s1_trace_recorder.lua`](../../../tools/tracechaser/bizhawk/s1_trace_recorder.lua)
- [`s2_trace_recorder.lua`](../../../tools/tracechaser/bizhawk/s2_trace_recorder.lua)
- [`s3k_trace_recorder.lua`](../../../tools/tracechaser/bizhawk/s3k_trace_recorder.lua)
- [`s1_credits_trace_recorder.py`](../../../tools/tracechaser/retro/s1_credits_trace_recorder.py) (stable-retro; the BizHawk Lua credits recorder was removed)
- [`record_trace.bat`](../../../tools/tracechaser/bizhawk/record_trace.bat)
- [`record_s2_trace.bat`](../../../tools/tracechaser/bizhawk/record_s2_trace.bat)
- [`record_s3k_trace.bat`](../../../tools/tracechaser/bizhawk/record_s3k_trace.bat)

Trace payload tooling:

- [`compress-traces.ps1`](../../../tools/tracechaser/traces/compress-traces.ps1)

Skills:

- [`trace-replay-bug-fixing`](../../../.claude/skills/trace-replay-bug-fixing/SKILL.md) — the
  canonical workflow for any `*TraceReplay` test failure.
- [`s1-trace-replay`](../../../.claude/skills/s1-trace-replay/) — Sonic 1 trace recording
  specifics.
- **`bizhawk-headless-trace`** — canonical native capture and publication,
  including S1 credits demos; the maintained protocol is
  [`tools/tracechaser/bizhawk-headless/README.md`](../../../tools/tracechaser/bizhawk-headless/README.md).

## Next Steps

- [Testing](testing.md)
- [Tutorial: Implement an Object](tutorial-implement-object.md)
- [Tooling](../cross-referencing/tooling.md)

## Diagnostic note: validating hypotheses against the recorded JSONL

When a trace replay test fails and the failure has been attributed to a
specific in-game object (e.g. "napalm projectile ride-bridge release"
in S3K AIZ F7552), always verify the hypothesis by inspecting the
recorded JSONL aux state directly before writing engine code. The
trace files live at
`src/test/resources/traces/<game>/<run>/aux_state.jsonl.gz`. Search
for the relevant per-frame events (`sidekick_interact_object`,
`object_state` with the suspect object code, `aiz_transition_floor_solid`,
etc.) across the suspect frame window and confirm the prerequisite
state matches the hypothesis.

For the AIZ F7552 case (round 2) this check disproved the prior-round
ride-bridge hypothesis: the suspected `interact` slot was
`destroyed=true` for ~50 frames before the divergent frame, ruling out
any `Solid_Object_Detach`-style release as the cause. Skipping this
check would have produced an engine fix targeted at the wrong root
cause — a hack by the repo policy in `CLAUDE.md`. Documented in
`docs/status/s3k-known-bugs.md` under the AIZ F7552 entry.

For the AIZ F8927 case (round 1, diagnosis-only) the same check
disproved a "swing/vine peak handling" guess that fell out of the
prior round's F7660 fix: at F8927 Sonic's `status=0x06` (Air|Roll)
with `obj_control=0x00` and `interact_slot=9` pointing to a static
sprite (`object_code=0x0002BF5A`) that has not moved since F7252, so
no swing or carry object is active. The actual signature in the
recorded data is a 5-frame `0/0x18/0x30/0x48/0x60/0` cycle on
`x_speed` with a sub-x snap pushback once per cycle — the canonical
ROM "rolling-air sliding into a flush right-side wall" pattern from
`SonicKnux_DoLevelCollision`'s `CheckRightWallDist` arm
(`sonic3k.asm:24061-24065`). This narrowed the next-round
investigation to the engine's airborne right-wall probe vs the
player path's fixed `addi.w #$A,d3` (sonic3k.asm:20195) before any
engine code was changed.
