# Sonic 2 Level-Select Traces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the recorder, generator, metadata, and test infrastructure needed to turn the S2 level-select BK2 movie set into reliable trace fixtures.

**Architecture:** Keep the normal trace replay path intact: BizHawk Lua records ROM-side diagnostics, Java parses trace metadata and payloads, and `AbstractTraceReplayTest` drives BK2 input without per-frame state hydration. New S2 route metadata distinguishes engine progression zone ids from raw S2 ROM zone ids so catalog/test-mode launch stays correct.

**Tech Stack:** Java 21, JUnit 5, PowerShell, Windows batch, BizHawk Lua, Maven Surefire.

---

## File Structure

- Modify `src/main/java/com/openggf/trace/TraceMetadata.java`
  - Add optional metadata fields used by generated S2 route fixtures.
- Modify `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`
  - Add coverage that new metadata fields parse while old traces remain valid.
- Create `src/test/java/com/openggf/tests/trace/s2/S2TraceRouteAssertions.java`
  - Shared metadata/checkpoint assertions for S2 route replay tests.
- Create `src/test/java/com/openggf/tests/trace/s2/TestS2TraceRouteAssertions.java`
  - Unit coverage for the shared assertion helper against synthetic temp traces.
- Modify `tools/bizhawk/record_s2_trace.bat`
  - Accept optional trace profile.
  - Export `OGGF_S2_TRACE_PROFILE`.
  - Export `OGGF_BK2_FRAME_COUNT` by counting `Input Log.txt` entries in the BK2.
  - Force compression with `-ThresholdBytes 0` for S2 recorder output.
- Modify `tools/bizhawk/s2_trace_recorder.lua`
  - Add profile plumbing.
  - Add reset-aware discard/re-arm for level-select movies.
  - Add route metadata and frame-zero `zone_act_state` / `gameplay_start` events.
  - Write engine progression `zone_id` plus raw `rom_zone_id`.
- Create `tools/bizhawk/record_s2_level_select_traces.ps1`
  - Batch-generate the level-select fixtures.
  - Validate metadata, frame-zero gameplay event, row count, payload compression, and BK2 input alignment.
- Modify `docs/guide/contributing/trace-replay.md`
  - Document the S2 level-select profile and batch workflow.
- Modify `tools/bizhawk/README.md`
  - Add the new S2 launcher/generator usage.

---

### Task 1: Metadata Parsing

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceMetadata.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`

- [ ] **Step 1: Add failing metadata parsing test**

Add this test to `TestTraceDataParsing`:

```java
@Test
void parsesExtendedS2LevelSelectMetadata() throws IOException {
    Path dir = Files.createTempDirectory("s2-level-select-meta");
    Files.writeString(dir.resolve("metadata.json"), """
        {
          "game": "s2",
          "zone": "cpz",
          "zone_id": 1,
          "rom_zone_id": 13,
          "act": 1,
          "bk2_frame_offset": 1234,
          "trace_frame_count": 1,
          "start_x": "0x0060",
          "start_y": "0x0290",
          "recording_date": "2026-05-14",
          "lua_script_version": "9.0-s2",
          "trace_schema": 8,
          "csv_version": 6,
          "trace_profile": "level_gated_reset_aware",
          "bizhawk_version": "2.11",
          "genesis_core": "Genplus-gx",
          "route": "cpz",
          "source_bk2": "s2-lvl-select-CPZ.bk2",
          "rom_checksum": "ABCDEF",
          "notes": "test",
          "characters": ["sonic", "tails"],
          "main_character": "sonic",
          "sidekicks": ["tails"]
        }
        """);
    Files.writeString(dir.resolve("physics.csv"), "0000,0000,0060,0290,0000,0000,0000,00,0,0,0\\n");

    TraceMetadata metadata = TraceData.load(dir).metadata();

    assertEquals("s2", metadata.game());
    assertEquals("cpz", metadata.zone());
    assertEquals(1, metadata.zoneId());
    assertEquals(13, metadata.romZoneId());
    assertEquals(6, metadata.csvVersion());
    assertEquals("level_gated_reset_aware", metadata.traceProfile());
    assertEquals("2.11", metadata.bizhawkVersion());
    assertEquals("Genplus-gx", metadata.genesisCore());
    assertEquals("cpz", metadata.route());
    assertEquals("s2-lvl-select-CPZ.bk2", metadata.sourceBk2());
}
```

- [ ] **Step 2: Run the focused test and confirm it fails**

Run:

```powershell
mvn test -Dtest=TestTraceDataParsing#parsesExtendedS2LevelSelectMetadata -DfailIfNoTests=false
```

Expected: compilation fails because the new `TraceMetadata` accessors do not exist.

- [ ] **Step 3: Add optional metadata fields**

Add these record components to `TraceMetadata`:

```java
@JsonProperty("csv_version") Integer csvVersion,
@JsonProperty("trace_profile") String traceProfile,
@JsonProperty("bizhawk_version") String bizhawkVersion,
@JsonProperty("genesis_core") String genesisCore,
@JsonProperty("rom_zone_id") Integer romZoneId,
@JsonProperty("route") String route,
@JsonProperty("source_bk2") String sourceBk2
```

- [ ] **Step 4: Run metadata tests**

Run:

```powershell
mvn test -Dtest=TestTraceDataParsing#parsesExtendedS2LevelSelectMetadata -DfailIfNoTests=false
```

Expected: pass.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/openggf/trace/TraceMetadata.java src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java
git commit -m "test: parse extended S2 trace metadata" -m "Changelog: n/a`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

### Task 2: S2 Route Metadata Assertions

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s2/S2TraceRouteAssertions.java`
- Create: `src/test/java/com/openggf/tests/trace/s2/TestS2TraceRouteAssertions.java`

- [ ] **Step 1: Write assertion helper**

Create `S2TraceRouteAssertions` with:

```java
package com.openggf.tests.trace.s2;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceMetadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class S2TraceRouteAssertions {
    private S2TraceRouteAssertions() {
    }

    static void assertRoute(TraceData trace, String zoneSlug, int engineZoneId,
                            int romZoneId, int metadataAct) {
        TraceMetadata metadata = trace.metadata();
        assertEquals("s2", metadata.game(), "trace game");
        assertEquals(zoneSlug, metadata.zone(), "trace zone slug");
        assertEquals(engineZoneId, metadata.zoneId(), "engine progression zone_id");
        assertEquals(romZoneId, metadata.romZoneId(), "raw S2 ROM zone id");
        assertEquals(metadataAct, metadata.act(), "1-based metadata act");
        assertTrue(hasFrameZeroGameplayMarker(trace),
                "frame 0 must include zone_act_state or gameplay_start checkpoint with game_mode=12");
    }

    private static boolean hasFrameZeroGameplayMarker(TraceData trace) {
        return trace.getEventsForFrame(0).stream().anyMatch(event -> {
            if (event instanceof TraceEvent.ZoneActState state) {
                return Integer.valueOf(12).equals(state.gameMode());
            }
            if (event instanceof TraceEvent.Checkpoint checkpoint) {
                return "gameplay_start".equals(checkpoint.name())
                        && Integer.valueOf(12).equals(checkpoint.gameMode());
            }
            return false;
        });
    }
}
```

- [ ] **Step 2: Add helper unit test**

Create `TestS2TraceRouteAssertions` with one passing synthetic trace and one failing synthetic trace.

- [ ] **Step 3: Run helper tests**

Run:

```powershell
mvn test -Dtest=TestS2TraceRouteAssertions -DfailIfNoTests=false
```

Expected: pass.

- [ ] **Step 4: Commit**

```powershell
git add src/test/java/com/openggf/tests/trace/s2/S2TraceRouteAssertions.java src/test/java/com/openggf/tests/trace/s2/TestS2TraceRouteAssertions.java
git commit -m "test: add S2 trace route metadata assertions" -m "Changelog: n/a`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

### Task 3: S2 Launcher Profile Support

**Files:**
- Modify: `tools/bizhawk/record_s2_trace.bat`

- [ ] **Step 1: Update usage and profile argument**

Make the launcher accept:

```bat
record_s2_trace.bat <rom_path> <bk2_path> [trace_profile]
```

Profile resolution order:

1. third argument,
2. `OGGF_S2_TRACE_PROFILE`,
3. `gameplay_unlock`.

- [ ] **Step 2: Add BK2 frame count export**

Copy the S3K launcher's `Input Log.txt` frame-count extraction pattern and export `OGGF_BK2_FRAME_COUNT`.

- [ ] **Step 3: Force output compression**

Call:

```bat
"%POWERSHELL_EXE%" -NoProfile -ExecutionPolicy Bypass -File "%COMPRESS_SCRIPT%" "%OUTPUT_DIR%" -ThresholdBytes 0
```

- [ ] **Step 4: Smoke-check usage text**

Run:

```powershell
tools\bizhawk\record_s2_trace.bat
```

Expected: usage text mentions optional `[trace_profile]`.

- [ ] **Step 5: Commit**

```powershell
git add tools/bizhawk/record_s2_trace.bat
git commit -m "tools: add S2 trace launcher profiles" -m "Changelog: n/a`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

### Task 4: S2 Recorder Reset-Aware Profile

**Files:**
- Modify: `tools/bizhawk/s2_trace_recorder.lua`

- [ ] **Step 1: Add profile constants and route mapping**

Add `TRACE_PROFILE`, `BK2_FRAME_COUNT`, engine-zone mapping, raw-zone mapping, and route slug helpers near the existing constants.

- [ ] **Step 2: Add `zone_act_state` and `checkpoint` emitters**

Implement S3K-style helpers:

```lua
local emitted_checkpoints = {}
local last_zone_act_state_key = nil

local function emit_zone_act_state(frame, raw_zone_id, engine_zone_id, actual_act, apparent_act, game_mode)
    -- writes JSON integer fields
end

local function emit_checkpoint_once(frame, name, raw_zone_id, engine_zone_id, actual_act, apparent_act, game_mode, notes)
    -- writes JSON integer fields and optional notes
end
```

- [ ] **Step 3: Add reset/discard state**

Implement `reset_recording_state()` that clears frame counters, buffered state, previous mode state, checkpoints, and open output files before re-arming.

- [ ] **Step 4: Add level-gated reset-aware logic**

For `TRACE_PROFILE == "level_gated_reset_aware"`:

- start only in final selected level gameplay,
- emit frame-0 gameplay marker,
- discard if gameplay returns to menu/title/options through debug exit before route completion,
- finalize on normal route end or BK2 configured end.

- [ ] **Step 5: Write extended metadata**

Metadata must write engine progression `zone_id`, raw `rom_zone_id`, `trace_profile`, `bizhawk_version`, `genesis_core`, `route`, `source_bk2` when available, and `aux_schema_extras`.

- [ ] **Step 6: Run Lua syntax smoke through BizHawk when available**

Run one short recording if the S2 ROM is present:

```powershell
tools\bizhawk\record_s2_trace.bat "Sonic The Hedgehog 2 (W) (REV01) [!].gen" "src\test\resources\traces\s2\ehz1_fullrun\s2-ehz1.bk2" gameplay_unlock
```

Expected: output is generated and compressed under `tools/bizhawk/trace_output`.

- [ ] **Step 7: Commit**

```powershell
git add tools/bizhawk/s2_trace_recorder.lua
git commit -m "tools: add reset-aware S2 trace recorder profile" -m "Changelog: n/a`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

### Task 5: S2 Level-Select Batch Generator

**Files:**
- Create: `tools/bizhawk/record_s2_level_select_traces.ps1`

- [ ] **Step 1: Create route table**

Include route rows with:

- route slug,
- BK2 filename,
- engine zone id,
- raw ROM zone id,
- starting metadata act,
- replay mode (`replay` or `parser-only`).

- [ ] **Step 2: Add validation functions**

Implement:

- metadata JSON validation,
- compressed payload check,
- `TraceData.load` row-count validation via Maven/Java helper or direct CSV row count,
- BK2 `Input Log.txt` input alignment preflight against `physics.csv(.gz)`,
- frame-zero gameplay marker validation from `aux_state.jsonl(.gz)`.

- [ ] **Step 3: Add generation loop**

For each selected route:

1. clear `tools/bizhawk/trace_output`,
2. call `record_s2_trace.bat` with `level_gated_reset_aware`,
3. run compression with `-ThresholdBytes 0`,
4. validate output,
5. copy metadata, payloads, and BK2 to `src/test/resources/traces/s2/<route>`.

- [ ] **Step 4: Run no-ROM dry validation**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bizhawk/record_s2_level_select_traces.ps1 -Help
```

Expected: help or usage displays without attempting BizHawk launch.

- [ ] **Step 5: Commit**

```powershell
git add tools/bizhawk/record_s2_level_select_traces.ps1
git commit -m "tools: add S2 level-select trace generator" -m "Changelog: n/a`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

### Task 6: Replay Tests and Docs

**Files:**
- Create route tests under `src/test/java/com/openggf/tests/trace/s2/`
- Modify `docs/guide/contributing/trace-replay.md`
- Modify `tools/bizhawk/README.md`

- [ ] **Step 1: Add route replay tests only after fixtures exist**

For replay-capable generated fixtures, create classes like:

```java
@RequiresRom(SonicGame.SONIC_2)
public class TestS2CpzTraceReplay extends AbstractTraceReplayTest {
    private static final Path TRACE_DIR = Path.of("src/test/resources/traces/s2/cpz");

    @Override
    protected SonicGame game() { return SonicGame.SONIC_2; }

    @Override
    protected int zone() { return Sonic2ZoneConstants.ZONE_CPZ; }

    @Override
    protected int act() { return 0; }

    @Override
    protected Path traceDirectory() { return TRACE_DIR; }

    @Test
    void routeMetadataMatchesFixture() throws Exception {
        S2TraceRouteAssertions.assertRoute(
                TraceData.load(TRACE_DIR),
                "cpz",
                Sonic2ZoneConstants.ZONE_CPZ,
                Sonic2ZoneConstants.ROM_ZONE_CPZ,
                1);
    }
}
```

- [ ] **Step 2: Keep DEZ ending parser-only initially**

Do not add `TestS2DezEndingTraceReplay` until standard level replay handles the ending path.

- [ ] **Step 3: Update docs**

Document:

- `record_s2_trace.bat <rom> <bk2> [trace_profile]`,
- `level_gated_reset_aware`,
- `record_s2_level_select_traces.ps1`,
- compressed-only route payload rule,
- engine `zone_id` vs raw `rom_zone_id`.

- [ ] **Step 4: Run docs/test smoke**

Run:

```powershell
mvn test -Dtest=TestTraceDataParsing,TestS2TraceRouteAssertions,TraceCatalogTest,TestS2Ehz1TraceReplay -DfailIfNoTests=false
```

Expected: pass, assuming S2 ROM is available for `TestS2Ehz1TraceReplay`; otherwise the ROM-gated test skips.

- [ ] **Step 5: Commit**

```powershell
git add src/test/java/com/openggf/tests/trace/s2 docs/guide/contributing/trace-replay.md tools/bizhawk/README.md
git commit -m "test: add S2 level-select trace workflow coverage" -m "Changelog: n/a`nGuide: updated`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

### Task 7: Fixture Generation

**Files:**
- Create route directories under `src/test/resources/traces/s2/`

- [ ] **Step 1: Generate one route first**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bizhawk/record_s2_level_select_traces.ps1 -RomPath "Sonic The Hedgehog 2 (W) (REV01) [!].gen" -Only cpz
```

Expected: `src/test/resources/traces/s2/cpz` contains `metadata.json`, the BK2, `physics.csv.gz`, and `aux_state.jsonl.gz`.

- [ ] **Step 2: Run the route test**

Run:

```powershell
mvn test -Dtest=TestS2CpzTraceReplay -DfailIfNoTests=false
```

Expected: pass or fail with a valid divergence report. Input mismatch is a generator bug.

- [ ] **Step 3: Generate remaining routes**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/bizhawk/record_s2_level_select_traces.ps1 -RomPath "Sonic The Hedgehog 2 (W) (REV01) [!].gen"
```

- [ ] **Step 4: Commit fixtures separately**

```powershell
git add src/test/resources/traces/s2
git commit -m "test: add S2 level-select trace fixtures" -m "Changelog: n/a`nGuide: n/a`nKnown-Discrepancies: n/a`nS3K-Known-Discrepancies: n/a`nAgent-Docs: n/a`nConfiguration-Docs: n/a`nSkills: n/a"
```

---

## Self-Review

- Spec coverage: recorder uplift, generation, compression, metadata, route tests, docs, and fixture generation each have tasks.
- Placeholder scan: no task depends on a hidden "figure it out later"; DEZ ending is explicitly parser-only until replay is proven.
- Type consistency: new metadata names are consistently `csvVersion`, `traceProfile`, `bizhawkVersion`, `genesisCore`, `romZoneId`, `route`, and `sourceBk2`.
- Risk note: Task 4 requires careful Lua implementation and should be reviewed before generating permanent fixtures.
