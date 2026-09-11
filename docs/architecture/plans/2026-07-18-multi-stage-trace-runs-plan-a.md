# Multi-Stage Trace Runs — Plan (a): Manifest Schema + Recorder State Machine + Mode-Guard Fix

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Land the trace-run foundation: the `run_manifest.json` schema + Java parser, the S3K complete-run recorder's mode-transition state machine (fixing its silent segment-pollution gap), and the parser/schema tests — per plan (a) of `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md`.

**Architecture:** A trace run = `run_manifest.json` + ordered typed segment dirs (spec §Architecture). Java side: a new Jackson record `TraceRunManifest` in `com.openggf.trace` plus three new optional `TraceMetadata` fields. Lua side: the S3K complete-run recorder gains a level-family mode guard on its per-frame CSV write, a stage-detour state machine (special-stage `$34` finalizes the segment; bonus zones `$13`–`$15` open bonus segments reusing the level row writer), transition records, and manifest emission.

**Tech Stack:** Java 21 + Jackson + JUnit 5 (Jupiter only); BizHawk 2.11 lua (Genplus-gx core).

## Global Constraints

- **Spec:** `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md`. Read it before starting any task.
- **Comparison-only invariant:** nothing added here may hydrate engine state from trace data.
- **No zone/route/frame carve-outs** in shared code; recorder gates key on ROM state (`Game_Mode`, zone id), which is the approved pattern.
- JUnit 5 / Jupiter only; no `org.junit.*` (JUnit 4) imports.
- **Commit policy:** every commit needs the trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills` — each `updated` or `n/a`). `feat`/`fix` commits touching `src/main/` MUST set `Changelog: updated` and stage `CHANGELOG.md` (CRLF file — verify `git diff CHANGELOG.md` shows only your lines, not a whole-file line-ending diff; if it does, restore and re-edit preserving CRLF).
- **Shared repo:** other agent sessions mutate this working tree. Stage files by exact path only; never `git add -A`.
- **Lua local-variable budget:** `s3k_complete_run_recorder.lua` is at BizHawk's 200-local limit (comment near line 280). New top-level recorder state MUST be declared as globals (no `local`), matching `current_segment_zone`/`segments_done`.
- Existing complete-run trace outputs (`physics.csv` + `aux_state.jsonl`) must remain byte-identical for stage-free movies (Task 8 proves it; `metadata.json` changes by design — version bump + segment_index).
- Maven: `mvn "-Dtest=..." test` — quote `-D` args in PowerShell; sandbox off for tests (lwjgl.dll).

---

### Task 1: `TraceRunManifest` record + loader + validation

**Files:**
- Create: `src/main/java/com/openggf/trace/TraceRunManifest.java`
- Test: `src/test/java/com/openggf/tests/trace/TestTraceRunManifest.java`

**Interfaces:**
- Consumes: nothing new (Jackson `ObjectMapper`, patterned on `TraceMetadata.java`).
- Produces: `TraceRunManifest.load(Path manifestPath)` (static, throws `IOException`), `manifest.validate(Path runDir)` (throws `IllegalStateException` with a message naming the first violation), nested records `TraceRunManifest.Segment` and `TraceRunManifest.Transition` with the exact fields below. Later plans (chained driver, catalog) consume these names verbatim.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.tests.trace;

import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceRunManifest {

    private static final String VALID_MANIFEST = """
        {
          "run_schema": 1,
          "game": "s3k",
          "run_id": "s3k-aiz-gumball-roundtrip",
          "source_bk2": "s3k-aiz-gumball.bk2",
          "rom_checksum": "C5B1C655C19F462ADE0AC4E17A844D10",
          "lua_script_version": "6.30-s3k-completerun",
          "segments": [
            {"dir": "seg00_aiz", "kind": "level", "trace_profile": "complete_run",
             "bk2_frame_offset": 500, "trace_frame_count": 1200, "zone_id": 0, "act": 1},
            {"dir": "seg01_gumball", "kind": "bonus_stage", "trace_profile": "s3k_bonus_stage",
             "bk2_frame_offset": 1900, "trace_frame_count": 800, "zone_id": 19,
             "bonus_stage_type": "gumball"},
            {"dir": "seg02_aiz", "kind": "level", "trace_profile": "complete_run",
             "bk2_frame_offset": 2900, "trace_frame_count": 600, "zone_id": 0, "act": 1}
          ],
          "transitions": [
            {"from_segment": 0, "to_segment": 1, "entry_kind": "starpost_bonus",
             "mode_change_bk2_frame": 1750, "special_bonus_entry_flag": 2,
             "saved_x_pos": 4660, "saved_y_pos": 1024, "last_star_post_hit": 1,
             "rings_before": 25, "emeralds_before": 0},
            {"from_segment": 1, "to_segment": 2, "entry_kind": "stage_exit",
             "mode_change_bk2_frame": 2800, "rings_after": 40, "emeralds_after": 0}
          ]
        }
        """;

    private Path writeRun(Path dir, String manifestJson, String... segmentDirs)
            throws IOException {
        for (String seg : segmentDirs) {
            Path segDir = dir.resolve(seg);
            Files.createDirectories(segDir);
            Files.writeString(segDir.resolve("metadata.json"), "{}");
        }
        Path manifest = dir.resolve("run_manifest.json");
        Files.writeString(manifest, manifestJson);
        return manifest;
    }

    @Test
    void loadsAndValidatesWellFormedManifest(@TempDir Path dir) throws IOException {
        Path manifest = writeRun(dir, VALID_MANIFEST, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        run.validate(dir);
        assertEquals(3, run.segments().size());
        assertEquals("bonus_stage", run.segments().get(1).kind());
        assertEquals("gumball", run.segments().get(1).bonusStageType());
        assertEquals(2, run.transitions().size());
        assertEquals("starpost_bonus", run.transitions().get(0).entryKind());
        assertEquals(2, run.transitions().get(0).specialBonusEntryFlag());
    }

    @Test
    void rejectsUnknownSegmentKind(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"kind\": \"bonus_stage\"", "\"kind\": \"casino\"");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("casino"), ex.getMessage());
    }

    @Test
    void rejectsNonMonotonicBk2Offsets(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"bk2_frame_offset\": 2900", "\"bk2_frame_offset\": 100");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("bk2_frame_offset"), ex.getMessage());
    }

    @Test
    void rejectsMissingSegmentDir(@TempDir Path dir) throws IOException {
        Path manifest = writeRun(dir, VALID_MANIFEST, "seg00_aiz", "seg02_aiz"); // seg01 missing
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("seg01_gumball"), ex.getMessage());
    }

    @Test
    void rejectsBonusSegmentWithoutType(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"bonus_stage_type\": \"gumball\"", "\"notes\": \"x\"");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("bonus_stage_type"), ex.getMessage());
    }

    @Test
    void rejectsTransitionWithBadIndices(@TempDir Path dir) throws IOException {
        String bad = VALID_MANIFEST.replace("\"from_segment\": 1, \"to_segment\": 2",
                                            "\"from_segment\": 1, \"to_segment\": 5");
        Path manifest = writeRun(dir, bad, "seg00_aiz", "seg01_gumball", "seg02_aiz");
        TraceRunManifest run = TraceRunManifest.load(manifest);
        IllegalStateException ex =
            assertThrows(IllegalStateException.class, () -> run.validate(dir));
        assertTrue(ex.getMessage().contains("to_segment"), ex.getMessage());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.tests.trace.TestTraceRunManifest" test`
Expected: COMPILE FAILURE — `TraceRunManifest` does not exist.

- [ ] **Step 3: Implement `TraceRunManifest`**

```java
package com.openggf.trace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Parsed {@code run_manifest.json} for a multi-segment trace run
 * (spec: docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md).
 * A run bundles ordered per-mode segment trace directories recorded from one
 * shared BK2 movie, plus the transition boundary records between them.
 * Comparison-only: this class is read by replay/validation code and never
 * feeds engine state.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceRunManifest(
    @JsonProperty("run_schema") int runSchema,
    @JsonProperty("game") String game,
    @JsonProperty("run_id") String runId,
    @JsonProperty("source_bk2") String sourceBk2,
    @JsonProperty("rom_checksum") String romChecksum,
    @JsonProperty("lua_script_version") String luaScriptVersion,
    @JsonProperty("segments") List<Segment> segments,
    @JsonProperty("transitions") List<Transition> transitions
) {

    public static final int SUPPORTED_RUN_SCHEMA = 1;
    public static final Set<String> SEGMENT_KINDS = Set.of("level", "special_stage", "bonus_stage");
    public static final Set<String> ENTRY_KINDS =
        Set.of("giant_ring", "starpost_special", "starpost_bonus", "stage_exit");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
        @JsonProperty("dir") String dir,
        @JsonProperty("kind") String kind,
        @JsonProperty("trace_profile") String traceProfile,
        @JsonProperty("bk2_frame_offset") int bk2FrameOffset,
        @JsonProperty("trace_frame_count") int traceFrameCount,
        @JsonProperty("zone_id") Integer zoneId,
        @JsonProperty("act") Integer act,
        @JsonProperty("special_stage_index") Integer specialStageIndex,
        @JsonProperty("bonus_stage_type") String bonusStageType
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Transition(
        @JsonProperty("from_segment") int fromSegment,
        @JsonProperty("to_segment") int toSegment,
        @JsonProperty("entry_kind") String entryKind,
        @JsonProperty("mode_change_bk2_frame") int modeChangeBk2Frame,
        @JsonProperty("special_bonus_entry_flag") Integer specialBonusEntryFlag,
        @JsonProperty("saved_x_pos") Integer savedXPos,
        @JsonProperty("saved_y_pos") Integer savedYPos,
        @JsonProperty("last_star_post_hit") Integer lastStarPostHit,
        @JsonProperty("rings_before") Integer ringsBefore,
        @JsonProperty("rings_after") Integer ringsAfter,
        @JsonProperty("emeralds_before") Integer emeraldsBefore,
        @JsonProperty("emeralds_after") Integer emeraldsAfter
    ) {}

    public static TraceRunManifest load(Path manifestPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(Files.readString(manifestPath), TraceRunManifest.class);
    }

    /**
     * Structural validation against the run directory. Throws
     * {@link IllegalStateException} naming the first violation.
     */
    public void validate(Path runDir) {
        if (runSchema != SUPPORTED_RUN_SCHEMA) {
            throw new IllegalStateException("Unsupported run_schema " + runSchema);
        }
        if (segments == null || segments.isEmpty()) {
            throw new IllegalStateException("Manifest has no segments");
        }
        int previousOffset = -1;
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (!SEGMENT_KINDS.contains(seg.kind())) {
                throw new IllegalStateException(
                    "Segment " + i + " has unknown kind '" + seg.kind() + "'");
            }
            if (seg.bk2FrameOffset() <= previousOffset) {
                throw new IllegalStateException(
                    "Segment " + i + " bk2_frame_offset " + seg.bk2FrameOffset()
                        + " is not strictly increasing");
            }
            previousOffset = seg.bk2FrameOffset();
            if ("bonus_stage".equals(seg.kind()) && seg.bonusStageType() == null) {
                throw new IllegalStateException(
                    "Segment " + i + " is bonus_stage but has no bonus_stage_type");
            }
            if ("special_stage".equals(seg.kind()) && seg.specialStageIndex() == null) {
                throw new IllegalStateException(
                    "Segment " + i + " is special_stage but has no special_stage_index");
            }
            Path segDir = runDir.resolve(seg.dir());
            if (!Files.isDirectory(segDir) || !Files.exists(segDir.resolve("metadata.json"))) {
                throw new IllegalStateException(
                    "Segment " + i + " directory missing or lacks metadata.json: " + seg.dir());
            }
        }
        if (transitions != null) {
            for (int i = 0; i < transitions.size(); i++) {
                Transition t = transitions.get(i);
                if (!ENTRY_KINDS.contains(t.entryKind())) {
                    throw new IllegalStateException(
                        "Transition " + i + " has unknown entry_kind '" + t.entryKind() + "'");
                }
                if (t.fromSegment() < 0 || t.fromSegment() >= segments.size()) {
                    throw new IllegalStateException(
                        "Transition " + i + " from_segment out of range: " + t.fromSegment());
                }
                if (t.toSegment() != t.fromSegment() + 1 || t.toSegment() >= segments.size()) {
                    throw new IllegalStateException(
                        "Transition " + i + " to_segment invalid: " + t.toSegment());
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.tests.trace.TestTraceRunManifest" test`
Expected: PASS (6 tests). Note: MSE prints a project-wide `total=`; check the class's own results.

- [ ] **Step 5: Update CHANGELOG.md and commit**

Add under the unreleased section (preserve CRLF; verify with `git diff CHANGELOG.md` that only your lines changed):
`- Trace framework: run_manifest.json schema + TraceRunManifest parser/validator for multi-segment trace runs (spec 2026-07-18).`

```bash
git add src/main/java/com/openggf/trace/TraceRunManifest.java src/test/java/com/openggf/tests/trace/TestTraceRunManifest.java CHANGELOG.md
git commit -m "feat(trace): add TraceRunManifest schema parser and validation" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 2: `TraceMetadata` run/segment fields

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceMetadata.java` (record components, end of component list)
- Test: `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java` (add one test)

**Interfaces:**
- Consumes: existing `TraceMetadata` record.
- Produces: three new nullable components on `TraceMetadata`: `@JsonProperty("run_id") String runId`, `@JsonProperty("segment_index") Integer segmentIndex`, `@JsonProperty("bonus_stage_type") String bonusStageType`. Existing callers are positional-record-constructor–free (Jackson only) except tests; check with `grep -rn "new TraceMetadata(" src/` and update any hits to pass `null, null, null` for the new trailing components.

- [ ] **Step 1: Write the failing test** — add to `TestTraceDataParsing.java`:

```java
@Test
void parsesRunSegmentMetadataFields() throws IOException {
    String json = """
        {"game": "s3k", "zone": "gumball", "act": 0, "bk2_frame_offset": 1900,
         "trace_frame_count": 800, "trace_profile": "s3k_bonus_stage",
         "run_id": "s3k-aiz-gumball-roundtrip", "segment_index": 1,
         "bonus_stage_type": "gumball"}
        """;
    TraceMetadata meta = new ObjectMapper().readValue(json, TraceMetadata.class);
    assertEquals("s3k-aiz-gumball-roundtrip", meta.runId());
    assertEquals(1, meta.segmentIndex());
    assertEquals("gumball", meta.bonusStageType());
}
```

(`TestTraceDataParsing` currently has NO `ObjectMapper` import — it uses the fully-qualified name elsewhere. Add `import com.fasterxml.jackson.databind.ObjectMapper;` for this snippet; `java.io.IOException` is already imported.)

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.tests.trace.TestTraceDataParsing" test`
Expected: COMPILE FAILURE — no `runId()` accessor.

- [ ] **Step 3: Add the three components** to the end of the `TraceMetadata` record parameter list (after `specialStageIndex`):

```java
    @JsonProperty("special_stage_index") Integer specialStageIndex,
    @JsonProperty("run_id") String runId,
    @JsonProperty("segment_index") Integer segmentIndex,
    @JsonProperty("bonus_stage_type") String bonusStageType
```

Then run `grep -rn "new TraceMetadata(" src/` and append `null, null, null` to every positional constructor call found (test helpers).

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn "-Dtest=com.openggf.tests.trace.TestTraceDataParsing" test`
Expected: PASS including the new test.

- [ ] **Step 5: Update CHANGELOG.md and commit**

CHANGELOG line: `- Trace framework: per-segment run_id/segment_index/bonus_stage_type metadata fields.`

```bash
git add src/main/java/com/openggf/trace/TraceMetadata.java src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java CHANGELOG.md
git commit -m "feat(trace): add run/segment fields to TraceMetadata" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 3: Synthetic stage-detour run fixture (JUnit layer of the mode-guard regression)

**Files:**
- Create: `src/test/resources/traces/synthetic/run_aiz_gumball_3seg/run_manifest.json`
- Create: `src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg00_aiz/{metadata.json,physics.csv}`
- Create: `src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg01_gumball/{metadata.json,physics.csv}`
- Create: `src/test/resources/traces/synthetic/run_aiz_gumball_3seg/seg02_aiz/{metadata.json,physics.csv}`
- Test: `src/test/java/com/openggf/tests/trace/TestTraceRunSyntheticFixture.java`

**Interfaces:**
- Consumes: `TraceRunManifest.load/validate` (Task 1), `TraceData` (existing) for level-schema segments.
- Produces: the committed synthetic run fixture later plans reuse; test name `TestTraceRunSyntheticFixture`.

- [ ] **Step 1: Build the fixture.** The existing synthetic S3K fixture is 22-col csv v4 — do NOT copy it (the v7 parser hard-throws on non-42-col rows, `TraceFrame.java:216-220`). Source real 42-column v7 rows instead: `gzip -dkc src/test/resources/traces/s3k/aiz_completerun/physics.csv.gz | head -3` (header + 2 data rows), copy into each segment's `physics.csv`, then edit values per segment. `seg00_aiz/metadata.json` (2-frame level segment):

```json
{"game": "s3k", "zone": "aiz", "zone_id": 0, "act": 1, "bk2_frame_offset": 500,
 "trace_frame_count": 2, "trace_schema": 6, "csv_version": 7,
 "trace_profile": "complete_run", "source_bk2": "synthetic.bk2",
 "run_id": "run_aiz_gumball_3seg", "segment_index": 0,
 "lua_script_version": "6.30-s3k-completerun", "start_x": "0500", "start_y": "0400"}
```

`seg01_gumball/metadata.json` differs: `"zone": "gumball", "zone_id": 19, "act": 0, "bk2_frame_offset": 1900, "trace_profile": "s3k_bonus_stage", "segment_index": 1, "bonus_stage_type": "gumball"`. `seg02_aiz`: `"bk2_frame_offset": 2900, "segment_index": 2`. `run_manifest.json`: the Task-1 `VALID_MANIFEST` shape with `"trace_frame_count": 2` per segment and the two transitions (`starpost_bonus` out at 1750, `stage_exit` back at 2800).

- [ ] **Step 2: Write the test**

```java
package com.openggf.tests.trace;

import com.openggf.trace.TraceData;
import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceRunSyntheticFixture {

    private static final Path RUN_DIR =
        Path.of("src", "test", "resources", "traces", "synthetic", "run_aiz_gumball_3seg");

    @Test
    void syntheticRunLoadsValidatesAndSegmentsParse() throws Exception {
        TraceRunManifest run = TraceRunManifest.load(RUN_DIR.resolve("run_manifest.json"));
        run.validate(RUN_DIR);
        assertEquals(3, run.segments().size());
        // The bonus segment parses through the EXISTING level trace loader —
        // this is the spec's "bonus reuses CSV v7 level schema" contract.
        for (TraceRunManifest.Segment seg : run.segments()) {
            TraceData data = TraceData.load(RUN_DIR.resolve(seg.dir()));
            assertEquals(seg.traceFrameCount(), data.frameCount(),
                "segment " + seg.dir());
        }
        // Gap between segments (transition frames) is represented ONLY in the
        // manifest, never as CSV rows: each segment's row span must end before
        // the next segment's offset.
        for (int i = 0; i < run.segments().size() - 1; i++) {
            TraceRunManifest.Segment a = run.segments().get(i);
            TraceRunManifest.Segment b = run.segments().get(i + 1);
            assertTrue(a.bk2FrameOffset() + a.traceFrameCount() <= b.bk2FrameOffset(),
                "segment " + i + " rows overlap next segment's offset");
        }
    }
}
```

(API verified: `TraceData.load(Path)` is static at `TraceData.java:57`; the row count accessor is `frameCount()` at `:77`.)

- [ ] **Step 3: Run test, verify it fails** (missing fixture files), then commit the fixture + test once green:

Run: `mvn "-Dtest=com.openggf.tests.trace.TestTraceRunSyntheticFixture" test`
Expected first: FAIL (missing files) → after fixture files land: PASS.

```bash
git add src/test/resources/traces/synthetic/run_aiz_gumball_3seg src/test/java/com/openggf/tests/trace/TestTraceRunSyntheticFixture.java
git commit -m "test(trace): synthetic 3-segment stage-detour run fixture" -m "Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 4: Recorder RAM addresses — derive and verify

**Files:**
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua` (constants block, after `ADDR_LEVEL_STARTED_FLAG` ~line 340)
- Create (scratch, not committed): a temporary derivation note

**Interfaces:**
- Produces: lua constants `ADDR_SPECIAL_BONUS_ENTRY_FLAG`, `ADDR_SAVED_X_POS`, `ADDR_SAVED_Y_POS`, `ADDR_LAST_STAR_POST_HIT`, `ADDR_EMERALD_COUNT`, `GAMEMODE_SPECIAL_STAGE = 0x34`, `BONUS_TOKENS = {[0x13]="gumball", [0x14]="pachinko", [0x15]="slots"}` consumed by Tasks 5–7, plus documentation-only constants `GAMEMODE_SS_RESULTS = 0x48`, `BONUS_ZONE_MIN = 0x13`, `BONUS_ZONE_MAX = 0x15` (referenced in comments, not code — the code uses `is_level_family_mode` and `BONUS_TOKENS` lookups). **Declare ALL of these as globals (no `local`)** — the file's main chunk is at Lua's 200-local limit (comments ~lines 279-282, 326-327); a `local` block here fails at load.

- [ ] **Step 1: Derive the five RAM addresses from skdisasm.** In `docs/skdisasm/sonic3k.constants.asm` the gameplay RAM block is `ds.b`-sequential from absolute anchors. For each of `Special_bonus_entry_flag` (line ~831), `Saved_X_pos`, `Saved_Y_pos`, `Last_star_post_hit`, `Emerald_count`: locate the symbol (`grep -n "<name>" docs/skdisasm/sonic3k.constants.asm`), find the nearest preceding absolute anchor (a `:=` assignment with a numeric address, e.g. the `Object_respawn_table`/RAM-block anchors), and sum the intervening `ds.b`/`ds.w`/`ds.l` sizes. Cross-check each result against `docs/skdisasm/s3.constants.asm` (same symbols; S3-side must agree) — two independent paths must give the same address or STOP and investigate.

- [ ] **Step 2: Live verification probe.** Add the constants plus a temporary `print` in `on_frame_end` (`if trace_frame % 600 == 0 then print(string.format("probe sp=%02X sx=%04X sy=%04X star=%02X em=%02X", mainmemory.read_u8(ADDR_SPECIAL_BONUS_ENTRY_FLAG), mainmemory.read_u16_be(ADDR_SAVED_X_POS), mainmemory.read_u16_be(ADDR_SAVED_Y_POS), mainmemory.read_u8(ADDR_LAST_STAR_POST_HIT), mainmemory.read_u8(ADDR_EMERALD_COUNT))) end`). Run the recorder against the committed movie for ~5k frames (see Task 8 for the exact invocation with `OGGF_TRACE_STOP_FRAME=5000`) and check: `saved_x/saved_y` match the AIZ start position after spawn, `last_star_post` flips 0→1 at the first star post (AIZ1 has one before frame ~5000 on the committed route; if not reached, extend the stop frame), `special_bonus_entry_flag` stays 0, `emerald_count` stays 0. Remove the probe print after verification.

- [ ] **Step 3: Commit** (constants only, probe removed):

```bash
git add tools/bizhawk/s3k_complete_run_recorder.lua
git commit -m "feat(trace): add S3K stage/bonus RAM constants to complete-run recorder" -m "Changelog: n/a: tooling-only lua change, no src/main
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 5: Physics-row mode guard (the pollution fix)

**Files:**
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua` — `on_frame_end`, immediately after the `if not started then ... return end` block (~line 4905, after the pre-first-segment early-return)

**Interfaces:**
- Consumes: `is_level_family_mode(game_mode)` (existing, line 917), Task-4 constants.
- Produces: guard behavior relied on by Task 6 (rows only under level-family mode).

- [ ] **Step 1: Add the guard.** After the `if not started then ... return end` block, insert:

```lua
    -- v6.30: HARD mode guard on the per-frame row write. Once armed, this
    -- recorder used to write a level-schema row EVERY frame regardless of
    -- Game_Mode; a special-stage ($34) or SS-results ($48) detour silently
    -- polluted the active zone segment with garbage rows (player slots hold
    -- SS object data in those modes). A level-schema row is only meaningful
    -- under the level family (raw $0C + $4C/$8C load-handoff). Detour
    -- entry/exit segmentation is handled BEFORE this guard (v6.30 state
    -- machine); this is the safety net that makes pollution structurally
    -- impossible. NOTE: $08 is the attract-mode demo, deliberately NOT
    -- level-family for recording purposes (spec 2026-07-18).
    do
        local guard_mode = mainmemory.read_u8(ADDR_GAME_MODE)
        if not is_level_family_mode(guard_mode) then
            return
        end
    end
```

Verified: `is_level_family_mode` (line 917) masks `(mode & GAMEMODE_MASK) == GAMEMODE_LEVEL` = `(mode & 0x0F) == 0x0C` (`GAMEMODE_MASK` at 460, `GAMEMODE_LEVEL` at 454), which already excludes `$08`/`$34`/`$48` — use it as-is.

- [ ] **Step 2: Regression check** — Task 8 (byte-identical AIZ regen) is the verification gate for this task; run its Step 1 now as a smoke check (stop frame 5000) and confirm rows still get written (non-empty physics.csv).

- [ ] **Step 3: Commit**

```bash
git add tools/bizhawk/s3k_complete_run_recorder.lua
git commit -m "fix(trace): mode-guard the complete-run per-frame row write" -m "Changelog: n/a: tooling-only lua change, no src/main
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 6: Stage-detour state machine + bonus segments

**Files:**
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua` — segmentation block in `on_frame_end` (~line 4838-4897), `start_new_segment` (~line 4762), `finalize_segment` (~line 4735), `write_metadata` (~line 1113), `zone_token_for` (~line 502), `precreate_segment_dirs` (~line 511)

**Interfaces:**
- Consumes: Task-4 constants, Task-5 guard.
- Produces: globals `transitions_done` (ordered boundary records, no indices), `segment_dir_counts` (repeat-count map), `pending_ss_transition`, `current_segment_dir_token`, `current_segment_is_bonus` — consumed by Task 7's manifest writer; bonus segments with `trace_profile = "s3k_bonus_stage"`, `bonus_stage_type`, `run_id`, `segment_index` in metadata.

- [ ] **Step 1: Segment dir naming for repeats.** The per-zone dir scheme collides when a zone recurs after a detour (aiz → gumball → aiz again). Add globals near `segments_done` (line ~720, NO `local` — 200-local budget):

```lua
-- v6.30 run/detour state (globals: local-budget). transitions_done holds
-- boundary records with EXPLICIT from_segment/to_segment captured at push
-- time: every push sits between finalize_segment() (which appended the
-- from-segment) and start_new_segment(), so from = #segments_done - 1 and
-- to = #segments_done are exact. Loop-position derivation would be WRONG:
-- plain level->level zone changes create segment boundaries with no
-- transition record, so record order does not map to boundary order in
-- multi-zone runs with detours.
transitions_done = {}
segment_dir_counts = {}
detour_active = nil               -- nil | "special_stage"
pending_ss_transition = nil       -- merged SS boundary record awaiting exit fields
current_segment_dir_token = nil   -- set by start_new_segment
current_segment_is_bonus = false  -- set by start_new_segment (before write_metadata)
run_id = os.getenv("OGGF_TRACE_RUN_ID") or nil
```

In `start_new_segment(zone_id)` replace the `OUTPUT_DIR` line with:

```lua
    local base_token = zone_token_for(zone_id)
    local n = (segment_dir_counts[base_token] or 0) + 1
    segment_dir_counts[base_token] = n
    local dir_token = (n == 1) and base_token or (base_token .. "_" .. n)
    OUTPUT_DIR = BASE_OUTPUT_DIR .. dir_token .. "/"
    current_segment_dir_token = dir_token   -- global
```

and record `dir = dir_token` in `finalize_segment`'s `segments_done` entry (add `dir = current_segment_dir_token,` and `kind`/`profile`/`bonus_stage_type` fields per Step 3). First occurrences keep today's exact dir names — existing stage-free runs stay byte-identical in layout.

- [ ] **Step 2: Special-stage detour handling.** In `on_frame_end`, right after `local game_mode = ...; local zone_id = ...` (before the arm-gate block), insert:

```lua
    -- ---- v6.30 stage-detour state machine ----
    -- SPECIAL STAGE ($34): finalize the current level segment at entry; no
    -- rows are written during the detour (row schema lands with the
    -- blue-spheres plan). SS_Results ($48) and the return load-handoff are
    -- transition frames, represented only in the manifest.
    if started and game_mode == GAMEMODE_SPECIAL_STAGE then
        -- ONE merged transition per SS detour: the SS produces NO segment in
        -- plan (a), so its entry and exit are a single boundary between two
        -- consecutive level segments. Entry fields are captured now; exit
        -- fields (rings_after/emeralds_after) are merged at the return re-arm.
        -- Pushing two records here would break the
        -- (#transitions == #segments - 1) invariant and produce out-of-range
        -- indices in TraceRunManifest.validate.
        pending_ss_transition = {
            entry_kind = "giant_ring",
            mode_change_bk2_frame = emu.framecount(),
            special_bonus_entry_flag = mainmemory.read_u8(ADDR_SPECIAL_BONUS_ENTRY_FLAG),
            saved_x_pos = mainmemory.read_u16_be(ADDR_SAVED_X_POS),
            saved_y_pos = mainmemory.read_u16_be(ADDR_SAVED_Y_POS),
            last_star_post_hit = mainmemory.read_u8(ADDR_LAST_STAR_POST_HIT),
            rings_before = mainmemory.read_u16_be(ADDR_RING_COUNT),
            emeralds_before = mainmemory.read_u8(ADDR_EMERALD_COUNT),
        }
        finalize_segment()
        detour_active = "special_stage"
        print(string.format("Special-stage detour at bk2 frame %d; segment finalized.",
            emu.framecount()))
        return
    end
    if detour_active == "special_stage" then
        if is_level_family_mode(game_mode) then
            -- Back in a level load: the arm gate below will open the next
            -- segment (current_segment_zone is nil after finalize). The
            -- pending SS transition completes and is pushed at that arm.
            detour_active = nil
        else
            return  -- $34/$48/fade frames: manifest-only, no rows
        end
    end
```

- [ ] **Step 3: Bonus segments.** Bonus zones arrive under level-family mode via the EXISTING arm gate (`game_mode == GAMEMODE_LEVEL and zone_id ~= current_segment_zone`). Extend `zone_token_for` to return `BONUS_TOKENS[zone_id]` for `0x13`–`0x15` (before its unknown-zone fallback). In `start_new_segment`, set a global `current_segment_is_bonus = (BONUS_TOKENS[zone_id] ~= nil)` — **before the existing `write_metadata()` call** (~line 4781), or the segment's initial metadata write emits the wrong profile. On the arm path (inside the existing `if started then finalize_segment() end` branch of the arm gate), when the NEW zone is a bonus zone, push the entry transition BEFORE `start_new_segment(zone_id)`:

```lua
            if BONUS_TOKENS[zone_id] ~= nil then
                transitions_done[#transitions_done + 1] = {
                    from_segment = #segments_done - 1,
                    to_segment = #segments_done,
                    entry_kind = "starpost_bonus",
                    mode_change_bk2_frame = emu.framecount(),
                    special_bonus_entry_flag = mainmemory.read_u8(ADDR_SPECIAL_BONUS_ENTRY_FLAG),
                    saved_x_pos = mainmemory.read_u16_be(ADDR_SAVED_X_POS),
                    saved_y_pos = mainmemory.read_u16_be(ADDR_SAVED_Y_POS),
                    last_star_post_hit = mainmemory.read_u8(ADDR_LAST_STAR_POST_HIT),
                    rings_before = mainmemory.read_u16_be(ADDR_RING_COUNT),
                    emeralds_before = mainmemory.read_u8(ADDR_EMERALD_COUNT),
                }
            end
```

**Placement is load-bearing:** all three push blocks (Case 1, Case 2, then the bonus-entry push) go **after the `if started then finalize_segment() end` line, still inside the `if ctrl_lock_timer == 0 and ctrl_locked == 0 then` block, NOT nested inside `if started`** — on an SS return, `started` is false at the arm (finalize ran at SS entry; `reset_recording_state` cleared it at line ~1055), so nesting them under `if started` would silently drop the SS boundary record. Order within the block: Case 1, Case 2, bonus-entry push, then `start_new_segment(zone_id)`.

```lua
            -- Case 1: returning from an SS detour — complete the merged
            -- pending record and push it (the ONLY record for that boundary).
            -- Indices are exact here: finalize appended the from-segment and
            -- start_new_segment has not run yet.
            if pending_ss_transition ~= nil then
                pending_ss_transition.from_segment = #segments_done - 1
                pending_ss_transition.to_segment = #segments_done
                pending_ss_transition.rings_after = mainmemory.read_u16_be(ADDR_RING_COUNT)
                pending_ss_transition.emeralds_after = mainmemory.read_u8(ADDR_EMERALD_COUNT)
                transitions_done[#transitions_done + 1] = pending_ss_transition
                pending_ss_transition = nil
            end
            -- Case 2: the just-finalized predecessor was a bonus segment —
            -- this arm is the bonus-exit boundary.
            if #segments_done > 0
                and segments_done[#segments_done].kind == "bonus_stage" then
                transitions_done[#transitions_done + 1] = {
                    from_segment = #segments_done - 1,
                    to_segment = #segments_done,
                    entry_kind = "stage_exit",
                    mode_change_bk2_frame = emu.framecount(),
                    rings_after = mainmemory.read_u16_be(ADDR_RING_COUNT),
                    emeralds_after = mainmemory.read_u8(ADDR_EMERALD_COUNT),
                }
            end
```

Explicit capture is required because plain level→level zone changes create segment boundaries with NO transition record; loop-position derivation in the writer would mis-map indices in any multi-zone run with a detour (e.g. aiz→hcz→gumball→hcz) while still passing the structural validator.

In `finalize_segment`, extend the `segments_done` entry:

```lua
    segments_done[#segments_done + 1] = {
        token = token,
        dir = current_segment_dir_token,
        kind = current_segment_is_bonus and "bonus_stage" or "level",
        profile = current_segment_is_bonus and "s3k_bonus_stage" or TRACE_PROFILE,
        bonus_stage_type = current_segment_is_bonus and BONUS_TOKENS[start_zone_id] or nil,
        zone_id = start_zone_id,
        act = start_act + 1,
        bk2_frame_offset = bk2_frame_offset,
        rows = rows,
    }
```

In `write_metadata`, when `current_segment_is_bonus`, write `"trace_profile": "s3k_bonus_stage"` instead of `TRACE_PROFILE`, plus `"bonus_stage_type": "<token>"`; always write `"run_id"` (when set) and `"segment_index": #segments_done` (the index this segment will get). Match `write_metadata`'s existing json-writing style (it mostly uses `..` string concatenation, not `string.format` — mirror the neighboring lines).

Also extend `precreate_segment_dirs` to pre-create `gumball/`, `pachinko/`, `slots/` (repeat-suffix dirs go through `ensure_segment_dir`'s fallback, which is fine).

**Bonus arm-gate caveat:** the existing gate requires `ctrl_lock_timer == 0 and ctrl_locked == 0`. Verify during Task 8's detour smoke run (when a bonus bk2 exists in plan (b)) that the bonus zone reaches control-unlocked; if the bonus intro holds a lock longer than expected the segment simply arms a few frames later — acceptable, the manifest's `mode_change_bk2_frame` still records the true entry edge.

- [ ] **Step 4: Syntax check.** BizHawk isn't needed to catch lua syntax errors: run `luacheck` if available, else `lua -e "loadfile('tools/bizhawk/s3k_complete_run_recorder.lua')" 2>&1 || true` — a bare `lua`/`lua5.x` binary parse (`loadfile` only parses, BizHawk APIs never execute). Expected: no parse errors. (If no lua interpreter is installed, Task 8's BizHawk run is the syntax gate.)

- [ ] **Step 5: Commit**

```bash
git add tools/bizhawk/s3k_complete_run_recorder.lua
git commit -m "feat(trace): stage-detour state machine + bonus segments in complete-run recorder" -m "Changelog: n/a: tooling-only lua change, no src/main
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 7: Manifest emission

**Files:**
- Modify: `tools/bizhawk/s3k_complete_run_recorder.lua` — add `write_run_manifest()` near `write_metadata` (~line 1113); call it at the main `while true` loop's finalization (~line 5268, after the last `finalize_segment()`, before `break`). There is NO `event.onexit` in this script.

**Interfaces:**
- Consumes: `segments_done`, `transitions_done`, `run_id`, `pending_ss_transition` (Task 6), the new `LUA_SCRIPT_VERSION` global (Step 2), `S3K_ROM_CHECKSUM` (existing global, line ~301). `source_bk2` is a **hardcoded string literal** in `write_metadata` (line ~1133, `"s3k-complete-sonic-tails.bk2"`) — hoist it to a global `SOURCE_BK2_NAME` and use it in both places.
- Produces: `BASE_OUTPUT_DIR .. "run_manifest.json"` in the Task-1 schema, `run_schema = 1`, with `from_segment`/`to_segment` derived from record order.

- [ ] **Step 1: Implement `write_run_manifest()`** (emit only when a detour occurred OR `OGGF_TRACE_RUN_ID` is set, so plain complete-run regenerations remain output-identical):

```lua
function write_run_manifest()
    if pending_ss_transition ~= nil then
        -- Movie ended mid-SS-detour: append the incomplete record so the
        -- Java validator rejects the manifest — the correct failure mode
        -- for a truncated recording.
        print("WARNING: movie ended mid special-stage detour; manifest incomplete.")
        pending_ss_transition.from_segment = #segments_done - 1
        pending_ss_transition.to_segment = #segments_done
        transitions_done[#transitions_done + 1] = pending_ss_transition
        pending_ss_transition = nil
    end
    if #transitions_done == 0 and run_id == nil then
        return  -- stage-free legacy run: no manifest, output layout unchanged
    end
    local f = io.open(BASE_OUTPUT_DIR .. "run_manifest.json", "w")
    if not f then
        print("WARNING: could not open run_manifest.json for writing")
        return
    end
    f:write('{\n')
    f:write('  "run_schema": 1,\n')
    f:write('  "game": "s3k",\n')  -- "s3k" is the canonical trace game id
                                   -- (TraceCatalog.VALID_GAME_IDS,
                                   -- TraceExecutionModel.forGame); NEVER
                                   -- "sonic3k", which forGame rejects.
    if run_id then f:write(string.format('  "run_id": %q,\n', run_id)) end
    f:write(string.format('  "source_bk2": %q,\n', SOURCE_BK2_NAME))
    f:write(string.format('  "rom_checksum": %q,\n', S3K_ROM_CHECKSUM))
    f:write(string.format('  "lua_script_version": %q,\n', LUA_SCRIPT_VERSION))
    f:write('  "segments": [\n')
    for i, s in ipairs(segments_done) do
        local extra = ""
        if s.kind == "bonus_stage" then
            extra = string.format(', "bonus_stage_type": %q', s.bonus_stage_type)
        end
        f:write(string.format(
            '    {"dir": %q, "kind": %q, "trace_profile": %q, "bk2_frame_offset": %d, "trace_frame_count": %d, "zone_id": %d, "act": %d%s}%s\n',
            s.dir, s.kind, s.profile, s.bk2_frame_offset, s.rows, s.zone_id, s.act,
            extra, (i < #segments_done) and "," or ""))
    end
    f:write('  ],\n')
    f:write('  "transitions": [\n')
    for i, t in ipairs(transitions_done) do
        -- Indices were captured at push time (between finalize and
        -- start_new_segment) — emit them verbatim. Never derive from loop
        -- position: level->level boundaries carry no record.
        local parts = {
            string.format('"from_segment": %d', t.from_segment),
            string.format('"to_segment": %d', t.to_segment),
            string.format('"entry_kind": %q', t.entry_kind),
            string.format('"mode_change_bk2_frame": %d', t.mode_change_bk2_frame),
        }
        -- Optional numeric fields, written explicitly; every field name must
        -- match TraceRunManifest.Transition.
        if t.special_bonus_entry_flag then parts[#parts+1] = string.format('"special_bonus_entry_flag": %d', t.special_bonus_entry_flag) end
        if t.saved_x_pos then parts[#parts+1] = string.format('"saved_x_pos": %d', t.saved_x_pos) end
        if t.saved_y_pos then parts[#parts+1] = string.format('"saved_y_pos": %d', t.saved_y_pos) end
        if t.last_star_post_hit then parts[#parts+1] = string.format('"last_star_post_hit": %d', t.last_star_post_hit) end
        if t.rings_before then parts[#parts+1] = string.format('"rings_before": %d', t.rings_before) end
        if t.rings_after then parts[#parts+1] = string.format('"rings_after": %d', t.rings_after) end
        if t.emeralds_before then parts[#parts+1] = string.format('"emeralds_before": %d', t.emeralds_before) end
        if t.emeralds_after then parts[#parts+1] = string.format('"emeralds_after": %d', t.emeralds_after) end
        f:write(string.format('    {%s}%s\n', table.concat(parts, ", "),
            (i < #transitions_done) and "," or ""))
    end
    f:write('  ]\n}\n')
    f:close()
    print(string.format("Wrote run_manifest.json (%d segments, %d transitions).",
        #segments_done, #transitions_done))
end
```

Invariant check before writing: transition counts are bounded by boundaries, not equal to them — plain level→level zone changes are boundaries with NO record. The checkable invariant is per-record: every record's `to_segment == from_segment + 1` and `to_segment <= #segments_done` (with the truncated-detour exception, where `to_segment == #segments_done` may reference a segment that never armed — that is deliberately left for the Java validator to reject). On violation print a WARNING naming the record and still write what was captured.

- [ ] **Step 2: Introduce `LUA_SCRIPT_VERSION` and `SOURCE_BK2_NAME` globals.** There is currently NO version constant — the version is a hardcoded literal inside `write_metadata` (line ~1143: `meta_file:write('  "lua_script_version": "6.29-s3k-completerun",\n')`) and `source_bk2` is likewise a literal (line ~1133). Add near the other config globals (~line 292):

```lua
LUA_SCRIPT_VERSION = "6.30-s3k-completerun"   -- no "v" prefix (existing convention)
SOURCE_BK2_NAME = "s3k-complete-sonic-tails.bk2"
```

and rewrite the two `write_metadata` lines to consume them:

```lua
    meta_file:write(string.format('  "source_bk2": %q,\n', SOURCE_BK2_NAME))
    meta_file:write(string.format('  "lua_script_version": %q,\n', LUA_SCRIPT_VERSION))
```

(`write_metadata` mostly uses `..` concatenation — match whichever style each neighboring line uses; the two lines above are complete replacements.)

- [ ] **Step 3: Syntax check** (same as Task 6 Step 4), then commit:

```bash
git add tools/bizhawk/s3k_complete_run_recorder.lua
git commit -m "feat(trace): emit run_manifest.json from complete-run recorder" -m "Changelog: n/a: tooling-only lua change, no src/main
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

### Task 8: No-regression proof — byte-identical AIZ segment regen

**Files:**
- No repo modifications (scratch output only).

**Interfaces:**
- Consumes: committed movie `src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2`, committed trace `src/test/resources/traces/s3k/aiz_completerun/`, modified recorder.

- [ ] **Step 1: Regenerate the AIZ segment with the modified recorder.** BizHawk lives at `docs/BizHawk-2.11-win-x64/`. Use the repo runner (`tools/bizhawk/run_bizhawk_lua.bat`) with the S3K ROM discovered at repo root (`*.gen`, CRC32 `63522553`) — inspect the .bat header for its exact argument order first:

```powershell
$env:OGGF_TRACE_OUTPUT_DIR = "$env:TEMP\oggf_regen_aiz\"
$env:OGGF_TRACE_STOP_FRAME = "40000"   # covers the full AIZ segment
tools\bizhawk\run_bizhawk_lua.bat <rom.gen> src\test\resources\traces\s3k\_movies\s3k-complete-sonic-tails.bk2 tools\bizhawk\s3k_complete_run_recorder.lua
```

- [ ] **Step 2: Diff against the committed segment** (gunzip first):

```powershell
# compare physics.csv
gzip -dkc src/test/resources/traces/s3k/aiz_completerun/physics.csv.gz > $env:TEMP\aiz_committed.csv
fc /b $env:TEMP\aiz_committed.csv $env:TEMP\oggf_regen_aiz\aiz\physics.csv
```

Expected: `FC: no differences encountered` (byte-identical; the committed movie's routes avoid stages, so the state machine must be a pure no-op). Also assert: NO `run_manifest.json` was written (`Test-Path $env:TEMP\oggf_regen_aiz\run_manifest.json` → False), aux_state.jsonl also byte-identical. **Diff only `physics.csv` + `aux_state.jsonl` — `metadata.json` differs BY DESIGN** (the `lua_script_version` bump to 6.30 plus the new always-written `segment_index` — and `run_id` when set); do not treat those metadata lines as a regression. Any other difference is a REGRESSION — stop and fix before proceeding.

- [ ] **Step 3: Record the result** in the final task's summary commit message (no repo change here).

---

### Task 9: Docs wrap-up

**Files:**
- Modify: `docs/status/trace-frontier-log.md` (note the recorder version bump + no-regression sweep result, per the repo's frontier-log obligation for trace-infrastructure changes)
- Modify: `tools/bizhawk/README.md` (short section: run manifests, bonus segments, `OGGF_TRACE_RUN_ID`, the mode guard)

- [ ] **Step 1: Write both doc updates.** Frontier log entry records: date, recorder `v6.30-s3k-completerun`, the Task-8 command, byte-identical result, and that no trace frontiers moved.

- [ ] **Step 2: Commit**

```bash
git add docs/status/trace-frontier-log.md tools/bizhawk/README.md
git commit -m "docs: record complete-run recorder v6.30 no-regression sweep" -m "Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

---

## Plan-level notes

- **Deliberate scope choice vs spec wording:** the spec's Component 1 names both `s3k_trace_recorder.lua` and `s3k_complete_run_recorder.lua` for the state machine. This plan implements it in the **complete-run recorder only**: it is the recorder with the pollution gap, it already owns segmentation, and it works on any bk2 (including the short dedicated stage movies plan (b) records). Duplicating the machine into the 222KB single-level recorder is deferred until a concrete use-case needs it (YAGNI); plan (b) revisits.
- **SS segments produce no CSV rows in plan (a)** — mode `$34` finalizes the level segment and records the (single, merged) transition; the blue-spheres row writer and its segment dirs land with the blue-spheres plan. Manifest `segments[]` therefore only ever lists dirs that exist (level + bonus), keeping `TraceRunManifest.validate` strict.
- **Giant-ring fade frames stay in the pre-detour segment:** the touch/fade-out before `Game_Mode` flips to `$34` runs under raw `$0C`, so those frames are recorded as normal rows of the outgoing level segment — consistent with how act-exit handoff tails are already recorded into the current segment. The manifest's `mode_change_bk2_frame` marks the true mode edge.
- **Full-suite gate before merge:** `mvn test` (sandbox off) — pre-existing failures per `docs/status/rewind-round-trip-gaps.md` context notwithstanding, no NEW failures.
- **Merge-time reminder:** merging the branch into `develop` requires a staged `README.md` update summarizing the branch change (repo merge policy) — not a task here, but budget for it at merge time.
