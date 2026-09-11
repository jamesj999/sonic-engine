# Sonic 2 Level-Select Trace Fixture Blueprint

## Requirements

### Goals

- Support the Sonic 2 level-select BK2 movie set in `docs/BizHawk-2.11-win-x64/Movies/s2-lvl-select-*.bk2`.
- Refresh `tools/bizhawk/s2_trace_recorder.lua` with the S3K recorder capabilities that matter for level-select movies:
  - profile selection,
  - reset-aware discard and re-arm,
  - reliable BK2 end handling,
  - `zone_act_state` and `checkpoint` aux events,
  - richer metadata,
  - compressed trace payload workflow.
- Generate Sonic 2 trace fixtures into the normal test resource tree under `src/test/resources/traces/s2/`.
- Add replay tests for the generated fixtures through the existing `AbstractTraceReplayTest` path.
- Make trace compression part of the generation workflow before files are staged for commit.
- Preserve the trace replay invariant: trace data is read-only comparison and diagnostic input, never per-frame engine hydration.

### Non-Goals

- Do not fix every engine divergence surfaced by the new traces in the fixture-generation change.
- Do not introduce a new trace parser or replay harness for Sonic 2.
- Do not rewrite the existing S1 or S3K recorder workflows.
- Do not make DEZ ending replay a required green test until the normal S2 level fixtures are validated.

### Constraints

- Use JUnit 5 only.
- Keep new trace replay tests extending `AbstractTraceReplayTest`.
- Keep generated fixtures compatible with `TraceData.load`, which already supports `physics.csv(.gz)` and `aux_state.jsonl(.gz)`.
- Avoid broad hook or CI churn unless generation-script compression enforcement is insufficient.
- Follow branch policy and commit trailer policy when implementation begins.

### Acceptance Criteria

- The S2 recorder can run a `level_gated_reset_aware` profile against each level-select BK2.
- The initial shared EHZ/options/debug-exit segment is discarded and is not present in the committed trace frame stream.
- Each committed fixture has valid `metadata.json`, a `.bk2`, `physics.csv.gz`, and `aux_state.jsonl.gz`.
- Metadata identifies game, engine progression zone id, raw S2 ROM zone id, 1-based act, trace profile, script version, BizHawk version, Genesis core, character set, start position, BK2 offset, source BK2, route name, and route notes.
- For S2 fixtures, `metadata.zone_id` is the engine progression zone id used by `Sonic2ZoneRegistry` and `TraceCatalog`; the raw byte read from S2 RAM is written separately as `rom_zone_id`.
- `metadata.act` remains 1-based, matching existing trace metadata and `TraceCatalog`'s `act - 1` conversion. Replay test classes still return 0-based engine acts.
- Each level-gated trace emits a frame-0 `zone_act_state` or `gameplay_start` checkpoint with integer `game_mode: 12`; if a future profile intentionally starts later, the profile must document its seed frame and BK2 offset math.
- `checkpoint` and `zone_act_state` JSON numeric fields are emitted as JSON integers or `null`, not hex strings.
- The generator validates BK2 input alignment before copying fixtures: the BK2 input stream at `bk2_frame_offset` must match the `physics.csv` input column for the recorded replay window, or fail before Java replay tests are run.
- `TraceData.load` and `TraceCatalog.scan` can load/discover the new fixtures.
- New replay tests run and either pass or fail with meaningful divergence reports in `target/trace-reports/`; input-offset failures are caught by generator preflight first, because `AbstractTraceReplayTest` fails immediately on input mismatch.
- Existing baseline trace tests, especially `TestS2Ehz1TraceReplay`, do not regress because of parser/bootstrap changes.

### Assumptions

- The BK2 files are valid BizHawk 2.11 movies for Sonic 2 REV01.
- The selected final level in each movie is the intended trace target after the shared setup segment.
- Route-level directories are preferable to per-act directories for the first pass, matching the existing S3K `cnz` style that can span multiple acts.
- `DEZ-Ending` should be captured as a fixture, but may initially be parser/catalog validated rather than strict replay validated if ending-mode support is incomplete.
- S2 `zone_id` semantics should be fixed in the new recorder/generator output rather than by making `TraceCatalog` guess whether an existing S2 value is raw or progression-ordered. Existing EHZ remains unaffected because raw and progression ids are both zero.

## Exploration Synthesis

Local inspection found:

- Existing S2 level-select BK2 movies:
  - `s2-lvl-select-ARZ.bk2`
  - `s2-lvl-select-CNZ.bk2`
  - `s2-lvl-select-CPZ.bk2`
  - `s2-lvl-select-DEZ-Ending.bk2`
  - `s2-lvl-select-HTZ.bk2`
  - `s2-lvl-select-MCZ.bk2`
  - `s2-lvl-select-OOZ.bk2`
  - `s2-lvl-select-SCZ.bk2`
  - `s2-lvl-select-WFZ.bk2`
- Existing S2 trace coverage is only `src/test/resources/traces/s2/ehz1_fullrun`.
- `tools/bizhawk/record_s2_trace.bat` already runs `tools/traces/compress-traces.ps1`, but only after a single recording and with the compression script's default threshold.
- S3K has the richer model in `tools/bizhawk/s3k_trace_recorder.lua`:
  - `OGGF_S3K_TRACE_PROFILE`,
  - `level_gated_reset_aware`,
  - reset-aware discard and re-arm,
  - BK2 frame count passed by `record_s3k_trace.bat`,
  - checkpoint and zone-act aux events,
  - profile and environment metadata.
- The Java parser already loads compressed trace payloads and already understands standard aux event types including `zone_act_state`, `checkpoint`, `cpu_state`, and pre-trace snapshots.
- The current trace replay docs live in `docs/guide/contributing/trace-replay.md` and should be updated with the S2 level-select workflow.

Recommendation: uplift S2 recorder behavior to match S3K where the concepts are shared, then generate fixtures through a repeatable script that enforces compression and metadata sanity before anything is copied into test resources.

## Architecture Decision

### Ownership

- `tools/bizhawk/s2_trace_recorder.lua` owns ROM-side S2 trace capture.
- `tools/bizhawk/record_s2_trace.bat` owns single-movie launcher behavior.
- A new script, probably `tools/bizhawk/record_s2_level_select_traces.ps1`, owns batch generation from the known level-select movie set.
- Java trace parser and replay harness remain shared infrastructure:
  - `src/main/java/com/openggf/trace/TraceData.java`
  - `src/main/java/com/openggf/trace/TraceMetadata.java`
  - `src/main/java/com/openggf/trace/TraceEvent.java`
  - `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- S2 fixture tests live under `src/test/java/com/openggf/tests/trace/s2/`.

### Data Flow

1. BizHawk runs a level-select BK2 with `s2_trace_recorder.lua`.
2. The recorder starts capturing when S2 enters the final selected gameplay segment.
3. If the movie enters a level and then returns to menu before the selected target, the recorder discards buffered output and re-arms.
4. The recorder writes `metadata.json`, `physics.csv`, and `aux_state.jsonl` to `tools/bizhawk/trace_output/`.
5. The launcher/generator compresses payloads, verifies round-trip, and copies the compressed output plus BK2 into `src/test/resources/traces/s2/<route>/`.
6. Replay tests drive the engine from BK2 input only and compare native engine state against the trace.

### Trace Directory Naming

Initial target directories:

- `src/test/resources/traces/s2/arz`
- `src/test/resources/traces/s2/cnz`
- `src/test/resources/traces/s2/cpz`
- `src/test/resources/traces/s2/htz`
- `src/test/resources/traces/s2/mcz`
- `src/test/resources/traces/s2/ooz`
- `src/test/resources/traces/s2/scz`
- `src/test/resources/traces/s2/wfz`
- `src/test/resources/traces/s2/dez_ending`

This keeps one fixture per BK2 route. If later diagnosis needs shorter act-local fixtures, those can be generated separately without changing this route-level layout.

### Compression Policy

The batch generation script should force compression for copied fixtures with `tools/traces/compress-traces.ps1 -ThresholdBytes 0`.

Before staging, generated trace directories should contain compressed payloads only:

- allowed: `physics.csv.gz`, `aux_state.jsonl.gz`
- disallowed in new generated route dirs: `physics.csv`, `aux_state.jsonl`

Existing legacy fixtures do not need churn unless touched.

### Rollback

Recorder and generated fixture changes should be separate logical commits where possible:

1. recorder and docs changes,
2. generated traces,
3. replay tests.

If fixture generation exposes bad metadata or broken input alignment, revert only the fixture/test commit while keeping recorder improvements for rerun.

## Feature Design

### S2 Recorder Profile Support

Add `TRACE_PROFILE = os.getenv("OGGF_S2_TRACE_PROFILE") or "gameplay_unlock"`.

Supported profiles:

- `gameplay_unlock`: current behavior for ordinary S2 BK2s such as `ehz1_fullrun`.
- `level_gated_reset_aware`: for `s2-lvl-select-*.bk2`; discard initial level attempts that return to menu and start final output at the selected target route.
- Optional later profile: `dez_ending`, only if ending capture needs different stop rules.

### Reset-Aware Capture

The reset-aware profile should:

- arm only when the ROM is in the level gameplay family,
- capture zone, act, apparent act if available, and game mode,
- emit `zone_act_state` when those values change,
- emit `checkpoint` events such as `gameplay_start`, `act_transition`, `route_end`, and for DEZ `ending_start` if detected,
- discard and reset buffered output when a started recording returns to options/title/menu through debug exit or reset before the target route is complete,
- finalize when the selected route leaves the target zone normally or the BK2 reaches its configured end.

Exact game mode constants and S2 RAM addresses should be verified against `docs/s2disasm/s2.asm` before coding.

### Zone and Act Semantics

Sonic 2 has two zone id domains that must not be mixed:

- raw ROM zone ids, read from S2 RAM and used by disassembly tables,
- engine progression zone ids, used by `Sonic2ZoneRegistry`, test classes, and `TraceCatalog`.

For new S2 level-select fixtures:

- `metadata.zone_id` is the engine progression zone id.
- `metadata.rom_zone_id` is the raw ROM zone byte.
- `metadata.zone` is the lowercase route slug such as `cpz`.
- aux events may keep `actual_zone_id` as the raw ROM value for ROM diagnostics, but metadata and catalog discovery use progression ids.
- replay tests should assert metadata `zone`, `zone_id`, `rom_zone_id`, and `act` before running route-specific checks.

This avoids `TraceCatalog.scan` launching non-EHZ traces into the wrong engine zone.

### Metadata

New S2 metadata should include:

- `game`
- `zone`
- `zone_id`
- `rom_zone_id`
- `act`
- `bk2_frame_offset`
- `trace_frame_count`
- `start_x`
- `start_y`
- `characters`
- `main_character`
- `sidekicks`
- `recording_date`
- `lua_script_version`
- `trace_schema`
- `csv_version`
- `aux_schema_extras`
- `trace_profile`
- `bizhawk_version`
- `genesis_core`
- `route`
- `source_bk2`
- `rom_checksum`
- `notes`

Parser compatibility should remain tolerant of old S2 metadata that lacks these newer fields.

Add optional `TraceMetadata` record components/accessors for fields that tests or catalog validation should assert:

- `csv_version`
- `trace_profile`
- `bizhawk_version`
- `genesis_core`
- `rom_zone_id`
- `route`
- `source_bk2`

If a field is intended only for generator-side raw JSON validation, document that explicitly in the generator instead of treating it as a Java acceptance condition.

### Batch Generation

`record_s2_level_select_traces.ps1` should take:

- `-RomPath`
- optional `-MoviesDir`, defaulting to `docs/BizHawk-2.11-win-x64/Movies`
- optional `-OutputRoot`, defaulting to `src/test/resources/traces/s2`
- optional `-Only` for one route reruns

It should fail fast if:

- the ROM is missing,
- a BK2 is missing,
- BizHawk exits non-zero,
- metadata game is not `s2`,
- metadata `zone_id` does not match the engine progression id for the route,
- metadata `rom_zone_id` does not match the raw S2 ROM zone id for the route,
- metadata `act` is not the expected 1-based starting act,
- frame 0 has no `zone_act_state` or `gameplay_start` event with integer `game_mode: 12`,
- BK2 input log values do not match the trace `input` column from `bk2_frame_offset`,
- `metadata.trace_frame_count` does not match the number of parsed trace rows after loading with `TraceData.load`,
- uncompressed payloads remain in the target route directory after copy/compression.

### Replay Tests

Add one class per generated route under `src/test/java/com/openggf/tests/trace/s2/`.

Candidate classes:

- `TestS2ArzTraceReplay`
- `TestS2CnzTraceReplay`
- `TestS2CpzTraceReplay`
- `TestS2HtzTraceReplay`
- `TestS2MczTraceReplay`
- `TestS2OozTraceReplay`
- `TestS2SczTraceReplay`
- `TestS2WfzTraceReplay`

Each class should:

- use `@RequiresRom(SonicGame.SONIC_2)`,
- extend `AbstractTraceReplayTest`,
- return the correct engine zone index and act 0 unless the fixture starts elsewhere,
- point at its fixture directory,
- assert route metadata consistency either through a shared S2 trace test helper or a route-specific test:
  - `game == "s2"`,
  - `zone` slug matches,
  - `zone_id` equals engine progression id,
  - `rom_zone_id` equals raw S2 ROM zone id,
  - `act` is expected 1-based metadata act,
  - a frame-0 gameplay checkpoint or zone-act event exists,
- avoid special tolerances unless a known non-primary mismatch creates unreadable noise.

For `DEZ-Ending`, do not include a `TestS2DezEndingTraceReplay` in the initial wildcard replay gate unless standard replay is proven. Prefer a parser/catalog validation test first, then add strict replay once ending-mode support is understood.

## Implementation Plan

This is a high-level implementation split, not the detailed step-by-step plan.

1. **Recorder uplift**
   - Modify `s2_trace_recorder.lua` to add profile plumbing, reset-aware state reset, zone/checkpoint events, and enriched metadata.
   - Modify `record_s2_trace.bat` to accept an optional profile and pass BK2 frame count like the S3K launcher.
   - Verification: run against the existing EHZ BK2 and confirm old behavior still works.

2. **Batch generator**
   - Add `record_s2_level_select_traces.ps1`.
   - Encode BK2 to route-directory mapping.
   - Enforce compression, metadata checks, TraceData row-count checks, and BK2 input preflight.
   - Verification: dry-run or one-route run for a short movie first.

3. **Fixture generation**
   - Generate each S2 route fixture from the movie set.
   - Copy BK2s into trace directories.
   - Commit compressed payloads only.
   - Verification: load every generated trace with `TraceData.load`.

4. **Replay test coverage**
   - Add S2 replay test classes.
   - Add a shared S2 metadata assertion helper or route-specific metadata tests.
   - Add parser/catalog tests for new metadata fields and compressed payload behavior.
   - Add a compression guard test for the new generated route directories if generation-script enforcement is not considered enough.
   - Verification: run the new S2 trace tests and `TestS2Ehz1TraceReplay`.

5. **Documentation**
   - Update `docs/guide/contributing/trace-replay.md`.
   - Update `tools/bizhawk/README.md`.
   - Document the level-select movie workflow, compression behavior, and expected failure mode.

6. **Failure triage**
   - Record which new tests pass, fail, or are temporarily parser-only.
   - For failures, keep first-error frame and report path in the final integration notes.
   - Do not fix broad engine parity failures in the same fixture-generation pass unless the root cause is a small recorder/test infrastructure mistake.

## Verification Commands

Focused infrastructure checks:

```powershell
mvn test -Dtest=TestTraceDataParsing,TraceCatalogTest,TestS2Ehz1TraceReplay -DfailIfNoTests=false
```

After new S2 tests exist:

```powershell
mvn test -Dtest='TestS2*TraceReplay' -DfailIfNoTests=false
```

Compression sanity for a generated route:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/traces/compress-traces.ps1 -Path src/test/resources/traces/s2/arz -ThresholdBytes 0
Get-ChildItem src/test/resources/traces/s2/arz -Include physics.csv,aux_state.jsonl -File
```

Expected result for the final `Get-ChildItem`: no files.

Cross-game smoke if shared parser/bootstrap code changes:

```powershell
mvn test -Dtest='TestS1Ghz1TraceReplay,TestS1Mz1TraceReplay,TestS2Ehz1TraceReplay,TestS3kAizTraceReplay,TestS3kCnzTraceReplay' -DfailIfNoTests=false
```

## Risks

- Incorrect S2 game mode constants could cause the recorder to keep the initial EHZ/debug-exit segment.
- If a level-select movie enters multiple acts or nonstandard transition paths, one test class per route may need route-specific expectations.
- Large traces can be expensive to store and run; route length may require selective test execution or parser-only staging.
- DEZ ending may require replay support outside the normal level-loop assumptions.
- Adding too many S3K-specific aux event ideas to S2 could create unnecessary parser work. Start with standard events and only add S2-specific diagnostics when a failing trace needs them.
- Current S2 engine parity may fail early in many zones. That is acceptable if the trace input alignment and reports are trustworthy.

## Review Questions

- Is `level_gated_reset_aware` the right default profile for all nine level-select movies?
- Is route-level fixture naming preferable to per-act naming for the first pass?
- Should compression be enforced only by the generation script, or also by a hook/test guard?
- Should `DEZ-Ending` receive a replay test immediately, or stay parser/catalog validated until ending support is checked?
- Are there any S2-specific aux diagnostics that should be included from day one, beyond standard `zone_act_state`, `checkpoint`, mode changes, pre-trace object snapshots, and Tails CPU snapshots?

## Self-Review

- Requirements are testable and traceable to the user request.
- Architecture reuses existing recorder, parser, compression, and replay harness paths.
- The comparison-only trace invariant is explicit.
- Compression is handled before commit through generation-script enforcement.
- DEZ ending uncertainty is isolated as a risk rather than blocking normal S2 fixture work.
- Detailed implementation steps still need a separate implementation plan before coding.
