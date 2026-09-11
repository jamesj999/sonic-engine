
> Historical note: this document may mention legacy JUnit 4 migration details. New and updated tests in this repository must use JUnit 5 / Jupiter only; do not create new JUnit 4 tests, rules, or runners.


> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a pipeline that replays BizHawk-captured BK2 inputs through the engine and compares per-frame physics state against trace data recorded from authentic Mega Drive emulation, for accuracy gap discovery.

**Architecture:** A BizHawk Lua script records frame-by-frame player state to CSV/JSONL files during BK2 playback. Java test infrastructure reads these trace files, replays the BK2 inputs through `HeadlessTestFixture`, compares engine state per frame via `TraceBinder`, and produces a `DivergenceReport` for diagnosis. An abstract JUnit base class makes adding new act tests trivial.

**Tech Stack:** BizHawk Lua API (memory reads, frame callbacks), Java 21 records/sealed interfaces, Jackson for JSON, existing `Bk2Movie`/`Bk2MovieLoader` for input parsing, existing `HeadlessTestFixture`/`HeadlessTestRunner` for headless execution.

**Spec:** `docs/architecture/designs/2026-03-26-bizhawk-trace-replay-testing-design.md`

---

## File Structure

### New Files (Java — test infrastructure)

| File | Responsibility |
|------|---------------|
| `src/test/java/com/openggf/tests/trace/TraceFrame.java` | Immutable record: one frame of primary trace data |
| `src/test/java/com/openggf/tests/trace/TraceMetadata.java` | Immutable record: trace directory metadata |
| `src/test/java/com/openggf/tests/trace/TraceEvent.java` | Sealed interface + subtypes for auxiliary events |
| `src/test/java/com/openggf/tests/trace/TraceData.java` | Reads/holds trace directory contents (CSV + JSONL + metadata) |
| `src/test/java/com/openggf/tests/trace/ToleranceConfig.java` | Per-field warn/error thresholds |
| `src/test/java/com/openggf/tests/trace/FieldComparison.java` | Single field comparison result (expected, actual, severity) |
| `src/test/java/com/openggf/tests/trace/FrameComparison.java` | Single frame comparison result (map of field comparisons) |
| `src/test/java/com/openggf/tests/trace/DivergenceGroup.java` | Run of consecutive divergent frames for one field |
| `src/test/java/com/openggf/tests/trace/DivergenceReport.java` | Full report: grouped divergences, context windows, JSON serialisation |
| `src/test/java/com/openggf/tests/trace/TraceBinder.java` | Per-frame comparison engine: sprite state vs trace state |
| `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java` | JUnit base class for trace replay tests |
| `src/test/java/com/openggf/tests/trace/s1/TestS1Ghz1TraceReplay.java` | Concrete GHZ1 trace replay test |

### New Files (Java — unit tests for trace infrastructure)

| File | Responsibility |
|------|---------------|
| `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java` | Tests for CSV/JSONL/metadata parsing |
| `src/test/java/com/openggf/tests/trace/TestTraceBinder.java` | Tests for comparison logic and tolerance |
| `src/test/java/com/openggf/tests/trace/TestDivergenceReport.java` | Tests for report grouping, context windows, JSON output |

### New Files (Test resources — synthetic trace fixtures)

| File | Responsibility |
|------|---------------|
| `src/test/resources/traces/synthetic/basic_3frames/metadata.json` | 3-frame synthetic trace metadata |
| `src/test/resources/traces/synthetic/basic_3frames/physics.csv` | 3-frame synthetic trace data |
| `src/test/resources/traces/synthetic/basic_3frames/aux_state.jsonl` | Synthetic aux events |

### New Files (Lua script)

| File | Responsibility |
|------|---------------|
| `tools/bizhawk/s1_trace_recorder.lua` | BizHawk Lua script for recording S1 REV01 traces |
| `tools/bizhawk/README.md` | Recording instructions |

### Modified Files

| File | Change |
|------|--------|
| `src/test/java/com/openggf/tests/HeadlessTestFixture.java` | Add `.withRecording(Path)` and `.withRecordingStartFrame(int)` to Builder |
| `src/test/java/com/openggf/tests/HeadlessTestRunner.java` | Add `stepFrameFromRecording()` method and BK2 movie state |
| `.gitignore` | Add `target/trace-reports/` |

---

## Task 1: TraceFrame and TraceMetadata Records

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/TraceFrame.java`
- Create: `src/test/java/com/openggf/tests/trace/TraceMetadata.java`

- [ ] **Step 1: Create TraceFrame record**

```java
package com.openggf.tests.trace;

/**
 * One frame of primary trace data from a BizHawk recording.
 * All values match the physics.csv format: positions and speeds are
 * 16-bit values as stored in 68K RAM.
 */
public record TraceFrame(
    int frame,
    int input,
    short x,
    short y,
    short xSpeed,
    short ySpeed,
    short gSpeed,
    byte angle,
    boolean air,
    boolean rolling,
    int groundMode
) {

    /**
     * Parse a single CSV row (all values in hex).
     * Expected format: frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode
     */
    public static TraceFrame parseCsvRow(String line) {
        String[] parts = line.split(",", -1);
        if (parts.length != 11) {
            throw new IllegalArgumentException(
                "Expected 11 CSV columns, got " + parts.length + ": " + line);
        }
        return new TraceFrame(
            Integer.parseInt(parts[0].trim(), 16),
            Integer.parseInt(parts[1].trim(), 16),
            (short) Integer.parseInt(parts[2].trim(), 16),
            (short) Integer.parseInt(parts[3].trim(), 16),
            parseSignedShortHex(parts[4].trim()),
            parseSignedShortHex(parts[5].trim()),
            parseSignedShortHex(parts[6].trim()),
            (byte) Integer.parseInt(parts[7].trim(), 16),
            !parts[8].trim().equals("0"),
            !parts[9].trim().equals("0"),
            Integer.parseInt(parts[10].trim())
        );
    }

    /**
     * Parse a hex string as a signed 16-bit value.
     * Handles both positive ("0380") and negative ("FC00" -> -1024) values.
     */
    private static short parseSignedShortHex(String hex) {
        int value = Integer.parseInt(hex, 16);
        if (value > 0x7FFF) {
            value -= 0x10000;
        }
        return (short) value;
    }
}
```

- [ ] **Step 2: Create TraceMetadata record**

```java
package com.openggf.tests.trace;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Metadata for a trace recording directory, parsed from metadata.json.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TraceMetadata(
    @JsonProperty("game") String game,
    @JsonProperty("zone") String zone,
    @JsonProperty("act") int act,
    @JsonProperty("bk2_frame_offset") int bk2FrameOffset,
    @JsonProperty("trace_frame_count") int traceFrameCount,
    @JsonProperty("start_x") String startXHex,
    @JsonProperty("start_y") String startYHex,
    @JsonProperty("recording_date") String recordingDate,
    @JsonProperty("lua_script_version") String luaScriptVersion,
    @JsonProperty("rom_checksum") String romChecksum,
    @JsonProperty("notes") String notes
) {

    /** Parse start_x hex string to short. */
    public short startX() {
        return (short) Integer.parseInt(startXHex.replace("0x", ""), 16);
    }

    /** Parse start_y hex string to short. */
    public short startY() {
        return (short) Integer.parseInt(startYHex.replace("0x", ""), 16);
    }

    /** Load metadata from a metadata.json file. */
    public static TraceMetadata load(Path metadataFile) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(metadataFile.toFile(), TraceMetadata.class);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/TraceFrame.java src/test/java/com/openggf/tests/trace/TraceMetadata.java
git commit -m "Add TraceFrame and TraceMetadata records for trace replay"
```

---

## Task 2: TraceEvent Sealed Interface

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/TraceEvent.java`

- [ ] **Step 1: Create TraceEvent sealed interface with subtypes**

```java
package com.openggf.tests.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An event from the auxiliary trace file (aux_state.jsonl).
 * Events are frame-keyed and only written for notable moments.
 */
public sealed interface TraceEvent {

    int frame();

    record ObjectAppeared(int frame, String objectType, short x, short y)
        implements TraceEvent {}

    record StateSnapshot(int frame, Map<String, Object> fields)
        implements TraceEvent {}

    record CollisionEvent(int frame, String type, String objectType, short x, short y)
        implements TraceEvent {}

    record ModeChange(int frame, String field, int from, int to)
        implements TraceEvent {}

    /**
     * Parse a single JSONL line into the appropriate TraceEvent subtype.
     * Unknown event types are returned as StateSnapshot with all fields preserved.
     */
    static TraceEvent parseJsonLine(String jsonLine, ObjectMapper mapper) {
        try {
            JsonNode node = mapper.readTree(jsonLine);
            int frame = node.get("frame").asInt();
            String event = node.has("event") ? node.get("event").asText() : "unknown";

            return switch (event) {
                case "object_appeared" -> new ObjectAppeared(
                    frame,
                    node.get("object_type").asText(),
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y")
                );
                case "collision_event" -> new CollisionEvent(
                    frame,
                    node.get("type").asText(),
                    node.has("object_type") ? node.get("object_type").asText() : "",
                    parseHexShort(node, "x"),
                    parseHexShort(node, "y")
                );
                case "mode_change" -> new ModeChange(
                    frame,
                    node.get("field").asText(),
                    node.get("from").asInt(),
                    node.get("to").asInt()
                );
                default -> {
                    // state_snapshot or unknown: preserve all fields as map
                    Map<String, Object> fields = new LinkedHashMap<>();
                    node.fields().forEachRemaining(
                        entry -> fields.put(entry.getKey(), nodeToValue(entry.getValue()))
                    );
                    yield new StateSnapshot(frame, fields);
                }
            };
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to parse JSONL line: " + jsonLine, e);
        }
    }

    private static short parseHexShort(JsonNode node, String field) {
        if (!node.has(field)) return 0;
        String hex = node.get(field).asText().replace("0x", "");
        return (short) Integer.parseInt(hex, 16);
    }

    private static Object nodeToValue(JsonNode node) {
        if (node.isInt()) return node.asInt();
        if (node.isLong()) return node.asLong();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isDouble()) return node.asDouble();
        return node.asText();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/TraceEvent.java
git commit -m "Add TraceEvent sealed interface for auxiliary trace events"
```

---

## Task 3: TraceData Reader with Unit Tests

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/TraceData.java`
- Create: `src/test/resources/traces/synthetic/basic_3frames/metadata.json`
- Create: `src/test/resources/traces/synthetic/basic_3frames/physics.csv`
- Create: `src/test/resources/traces/synthetic/basic_3frames/aux_state.jsonl`
- Create: `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`

- [ ] **Step 1: Create synthetic test fixtures**

`src/test/resources/traces/synthetic/basic_3frames/metadata.json`:
```json
{
  "game": "s1",
  "zone": "ghz",
  "act": 1,
  "bk2_frame_offset": 100,
  "trace_frame_count": 3,
  "start_x": "0x0050",
  "start_y": "0x03B0",
  "recording_date": "2026-03-26",
  "lua_script_version": "1.0",
  "rom_checksum": "test",
  "notes": "Synthetic 3-frame test fixture"
}
```

`src/test/resources/traces/synthetic/basic_3frames/physics.csv`:
```
frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode
0000,0000,0050,03B0,0000,0000,0000,00,0,0,0
0001,0008,0051,03B0,000C,0000,000C,00,0,0,0
0002,0008,0053,03B0,0030,0000,0030,10,0,0,0
```

`src/test/resources/traces/synthetic/basic_3frames/aux_state.jsonl`:
```
{"frame":0,"event":"state_snapshot","control_locked":false,"anim_id":0,"rings":0,"invuln_frames":0,"shield":false}
{"frame":2,"event":"mode_change","field":"angle","from":0,"to":16}
```

- [ ] **Step 2: Write the failing test for TraceData parsing**

```java
package com.openggf.tests.trace;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.*;

public class TestTraceDataParsing {

    private static final Path SYNTHETIC_3FRAMES =
        Path.of("src/test/resources/traces/synthetic/basic_3frames");

    @Test
    public void testMetadataLoading() throws IOException {
        TraceData data = TraceData.load(SYNTHETIC_3FRAMES);
        TraceMetadata meta = data.metadata();

        assertEquals("s1", meta.game());
        assertEquals("ghz", meta.zone());
        assertEquals(1, meta.act());
        assertEquals(100, meta.bk2FrameOffset());
        assertEquals(3, meta.traceFrameCount());
        assertEquals((short) 0x0050, meta.startX());
        assertEquals((short) 0x03B0, meta.startY());
    }

    @Test
    public void testFrameCount() throws IOException {
        TraceData data = TraceData.load(SYNTHETIC_3FRAMES);
        assertEquals(3, data.frameCount());
    }

    @Test
    public void testFrameParsing() throws IOException {
        TraceData data = TraceData.load(SYNTHETIC_3FRAMES);

        TraceFrame frame0 = data.getFrame(0);
        assertEquals(0, frame0.frame());
        assertEquals(0, frame0.input());
        assertEquals((short) 0x0050, frame0.x());
        assertEquals((short) 0x03B0, frame0.y());
        assertEquals((short) 0, frame0.xSpeed());
        assertFalse(frame0.air());
        assertFalse(frame0.rolling());
        assertEquals(0, frame0.groundMode());

        TraceFrame frame1 = data.getFrame(1);
        assertEquals(0x0008, frame1.input());
        assertEquals((short) 0x0051, frame1.x());
        assertEquals((short) 0x000C, frame1.xSpeed());
        assertEquals((short) 0x000C, frame1.gSpeed());

        TraceFrame frame2 = data.getFrame(2);
        assertEquals((byte) 0x10, frame2.angle());
    }

    @Test
    public void testAuxEventsParsing() throws IOException {
        TraceData data = TraceData.load(SYNTHETIC_3FRAMES);

        List<TraceEvent> frame0Events = data.getEventsForFrame(0);
        assertEquals(1, frame0Events.size());
        assertInstanceOf(TraceEvent.StateSnapshot.class, frame0Events.get(0));

        List<TraceEvent> frame2Events = data.getEventsForFrame(2);
        assertEquals(1, frame2Events.size());
        TraceEvent.ModeChange mc = (TraceEvent.ModeChange) frame2Events.get(0);
        assertEquals("angle", mc.field());
        assertEquals(0, mc.from());
        assertEquals(16, mc.to());
    }

    @Test
    public void testNoEventsForFrame() throws IOException {
        TraceData data = TraceData.load(SYNTHETIC_3FRAMES);
        List<TraceEvent> events = data.getEventsForFrame(1);
        assertTrue(events.isEmpty());
    }

    @Test
    public void testEventRange() throws IOException {
        TraceData data = TraceData.load(SYNTHETIC_3FRAMES);
        List<TraceEvent> events = data.getEventsInRange(0, 2);
        assertEquals(2, events.size());
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -Dtest=TestTraceDataParsing -pl . -q`
Expected: Compilation error — `TraceData` class does not exist yet.

- [ ] **Step 4: Implement TraceData**

```java
package com.openggf.tests.trace;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Reads and holds the contents of a trace directory:
 * metadata.json, physics.csv, and aux_state.jsonl.
 *
 * The primary CSV is loaded entirely into memory (small: ~100 bytes/frame).
 * Auxiliary events are lazy-loaded and indexed by frame number.
 */
public class TraceData {

    private final TraceMetadata metadata;
    private final List<TraceFrame> frames;
    private final Map<Integer, List<TraceEvent>> eventsByFrame;

    private TraceData(TraceMetadata metadata, List<TraceFrame> frames,
                      Map<Integer, List<TraceEvent>> eventsByFrame) {
        this.metadata = metadata;
        this.frames = frames;
        this.eventsByFrame = eventsByFrame;
    }

    /**
     * Load a trace from a directory containing metadata.json, physics.csv,
     * and optionally aux_state.jsonl.
     */
    public static TraceData load(Path traceDirectory) throws IOException {
        Path metadataPath = traceDirectory.resolve("metadata.json");
        Path physicsPath = traceDirectory.resolve("physics.csv");
        Path auxPath = traceDirectory.resolve("aux_state.jsonl");

        TraceMetadata metadata = TraceMetadata.load(metadataPath);
        List<TraceFrame> frames = loadPhysicsCsv(physicsPath);
        Map<Integer, List<TraceEvent>> events = Files.exists(auxPath)
            ? loadAuxEvents(auxPath)
            : Collections.emptyMap();

        return new TraceData(metadata, frames, events);
    }

    public TraceMetadata metadata() { return metadata; }

    public int frameCount() { return frames.size(); }

    public TraceFrame getFrame(int traceFrame) {
        if (traceFrame < 0 || traceFrame >= frames.size()) {
            throw new IndexOutOfBoundsException(
                "Frame " + traceFrame + " out of range [0, " + frames.size() + ")");
        }
        return frames.get(traceFrame);
    }

    /** Get auxiliary events for a specific frame. Returns empty list if none. */
    public List<TraceEvent> getEventsForFrame(int traceFrame) {
        return eventsByFrame.getOrDefault(traceFrame, Collections.emptyList());
    }

    /** Get all auxiliary events within a frame range (inclusive). */
    public List<TraceEvent> getEventsInRange(int startFrame, int endFrame) {
        List<TraceEvent> result = new ArrayList<>();
        for (int f = startFrame; f <= endFrame; f++) {
            result.addAll(getEventsForFrame(f));
        }
        return result;
    }

    private static List<TraceFrame> loadPhysicsCsv(Path csvPath) throws IOException {
        List<TraceFrame> frames = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            String line = reader.readLine(); // skip header
            if (line == null) return frames;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    frames.add(TraceFrame.parseCsvRow(trimmed));
                }
            }
        }
        return frames;
    }

    private static Map<Integer, List<TraceEvent>> loadAuxEvents(Path auxPath)
            throws IOException {
        Map<Integer, List<TraceEvent>> map = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();
        try (BufferedReader reader = Files.newBufferedReader(auxPath)) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    TraceEvent event = TraceEvent.parseJsonLine(trimmed, mapper);
                    map.computeIfAbsent(event.frame(), k -> new ArrayList<>()).add(event);
                }
            }
        }
        return map;
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TestTraceDataParsing -pl . -q`
Expected: All 5 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/TraceData.java src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java src/test/resources/traces/synthetic/basic_3frames/
git commit -m "Add TraceData reader with CSV/JSONL parsing and unit tests"
```

---

## Task 4: ToleranceConfig and FieldComparison

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/ToleranceConfig.java`
- Create: `src/test/java/com/openggf/tests/trace/FieldComparison.java`
- Create: `src/test/java/com/openggf/tests/trace/FrameComparison.java`

- [ ] **Step 1: Create Severity enum, FieldComparison, and FrameComparison**

```java
package com.openggf.tests.trace;

/** Severity of a single field divergence. */
public enum Severity {
    MATCH,   // No divergence
    WARNING, // Within tolerance but not exact
    ERROR    // Exceeds tolerance
}
```

Put this in its own file `Severity.java`.

```java
package com.openggf.tests.trace;

/**
 * Comparison result for a single field on a single frame.
 */
public record FieldComparison(
    String fieldName,
    String expected,
    String actual,
    Severity severity,
    int delta
) {
    public boolean isDivergent() {
        return severity != Severity.MATCH;
    }
}
```

```java
package com.openggf.tests.trace;

import java.util.List;
import java.util.Map;

/**
 * Comparison result for a single frame: all fields compared.
 */
public record FrameComparison(
    int frame,
    Map<String, FieldComparison> fields
) {
    /** True if any field has a non-MATCH severity. */
    public boolean hasDivergence() {
        return fields.values().stream().anyMatch(FieldComparison::isDivergent);
    }

    /** True if any field is ERROR severity. */
    public boolean hasError() {
        return fields.values().stream()
            .anyMatch(fc -> fc.severity() == Severity.ERROR);
    }

    /** Get all divergent fields. */
    public List<FieldComparison> divergentFields() {
        return fields.values().stream()
            .filter(FieldComparison::isDivergent)
            .toList();
    }
}
```

- [ ] **Step 2: Create ToleranceConfig**

```java
package com.openggf.tests.trace;

/**
 * Per-field tolerance thresholds for trace comparison.
 * Warn and error thresholds define the boundaries between MATCH, WARNING, and ERROR.
 * Boolean/enum fields (air, rolling, ground_mode) always ERROR on any mismatch.
 */
public record ToleranceConfig(
    int positionWarn,     // Absolute difference threshold for x, y
    int positionError,
    int speedWarn,        // Absolute difference threshold for speeds
    int speedError,
    boolean speedSignChangeIsError,  // Sign flip in speed = ERROR regardless of magnitude
    int angleWarn,
    int angleError
) {
    /**
     * Default tolerances:
     * - Position: warn at 1 subpixel, error at 256 (1 full pixel)
     * - Speed: warn at 1 subpixel, error at 128 (half pixel/frame), sign change = error
     * - Angle: warn at 1, error at 4
     * - Flags: any mismatch = error (hardcoded, not configurable)
     */
    public static final ToleranceConfig DEFAULT = new ToleranceConfig(
        1, 256,       // position
        1, 128, true, // speed
        1, 4          // angle
    );

    /** Classify a numeric difference against warn/error thresholds. */
    public Severity classify(int absDelta, int warn, int error) {
        if (absDelta == 0) return Severity.MATCH;
        if (absDelta >= error) return Severity.ERROR;
        if (absDelta >= warn) return Severity.WARNING;
        return Severity.MATCH;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/Severity.java src/test/java/com/openggf/tests/trace/FieldComparison.java src/test/java/com/openggf/tests/trace/FrameComparison.java src/test/java/com/openggf/tests/trace/ToleranceConfig.java
git commit -m "Add ToleranceConfig, Severity, FieldComparison, FrameComparison"
```

---

## Task 5: TraceBinder Comparison Engine with Unit Tests

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/TraceBinder.java`
- Create: `src/test/java/com/openggf/tests/trace/TestTraceBinder.java`

- [ ] **Step 1: Write failing tests for TraceBinder**

```java
package com.openggf.tests.trace;

import org.junit.Test;

import java.util.Map;

import static org.junit.Assert.*;

public class TestTraceBinder {

    @Test
    public void testExactMatchReturnsNoError() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        assertFalse(result.hasDivergence());
        assertFalse(result.hasError());
    }

    @Test
    public void testPositionDivergenceWarning() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        // 1 subpixel X difference: warning
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0051, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        assertTrue(result.hasDivergence());
        assertFalse(result.hasError());
        assertEquals(Severity.WARNING, result.fields().get("x").severity());
    }

    @Test
    public void testPositionDivergenceError() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        // Full pixel X difference: error
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0150, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("x").severity());
    }

    @Test
    public void testAirFlagMismatchIsError() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0000, (short) 0x0000, (short) 0x0000,
            (byte) 0x00, true, false, 0);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("air").severity());
    }

    @Test
    public void testSpeedSignChangeIsError() {
        TraceFrame frame = new TraceFrame(0, 0x0000,
            (short) 0x0050, (short) 0x03B0,
            (short) 0x0010, (short) 0x0000, (short) 0x0010,
            (byte) 0x00, false, false, 0);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        // Positive expected, negative actual = sign change
        FrameComparison result = binder.compareFrame(frame,
            (short) 0x0050, (short) 0x03B0,
            (short) -0x0010, (short) 0x0000, (short) -0x0010,
            (byte) 0x00, false, false, 0);

        assertTrue(result.hasError());
        assertEquals(Severity.ERROR, result.fields().get("x_speed").severity());
    }

    @Test
    public void testInputValidationMatch() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = new TraceFrame(0, 0x0008,
            (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (byte) 0, false, false, 0);
        assertTrue(binder.validateInput(frame, 0x0008));
    }

    @Test
    public void testInputValidationMismatch() {
        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        TraceFrame frame = new TraceFrame(0, 0x0008,
            (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
            (byte) 0, false, false, 0);
        assertFalse(binder.validateInput(frame, 0x0004));
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestTraceBinder -pl . -q`
Expected: Compilation error — `TraceBinder` class does not exist yet.

- [ ] **Step 3: Implement TraceBinder**

```java
package com.openggf.tests.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Comparison engine: compares engine sprite state against expected trace state
 * for a single frame, applying configurable tolerance thresholds.
 */
public class TraceBinder {

    private final ToleranceConfig tolerances;
    private final List<FrameComparison> allComparisons = new ArrayList<>();

    public TraceBinder(ToleranceConfig tolerances) {
        this.tolerances = tolerances;
    }

    /**
     * Compare a single frame's expected trace values against actual engine values.
     * Accepts raw values extracted from the sprite to keep this class decoupled
     * from AbstractPlayableSprite.
     */
    public FrameComparison compareFrame(
            TraceFrame expected,
            short actualX, short actualY,
            short actualXSpeed, short actualYSpeed, short actualGSpeed,
            byte actualAngle, boolean actualAir, boolean actualRolling,
            int actualGroundMode) {

        Map<String, FieldComparison> fields = new LinkedHashMap<>();

        // Position comparisons
        fields.put("x", compareNumeric("x", expected.x(), actualX,
            tolerances.positionWarn(), tolerances.positionError(), false));
        fields.put("y", compareNumeric("y", expected.y(), actualY,
            tolerances.positionWarn(), tolerances.positionError(), false));

        // Speed comparisons
        fields.put("x_speed", compareNumeric("x_speed", expected.xSpeed(), actualXSpeed,
            tolerances.speedWarn(), tolerances.speedError(), tolerances.speedSignChangeIsError()));
        fields.put("y_speed", compareNumeric("y_speed", expected.ySpeed(), actualYSpeed,
            tolerances.speedWarn(), tolerances.speedError(), tolerances.speedSignChangeIsError()));
        fields.put("g_speed", compareNumeric("g_speed", expected.gSpeed(), actualGSpeed,
            tolerances.speedWarn(), tolerances.speedError(), tolerances.speedSignChangeIsError()));

        // Angle comparison
        fields.put("angle", compareNumeric("angle",
            expected.angle() & 0xFF, actualAngle & 0xFF,
            tolerances.angleWarn(), tolerances.angleError(), false));

        // Boolean/enum flags: any mismatch is ERROR
        fields.put("air", compareFlag("air", expected.air(), actualAir));
        fields.put("rolling", compareFlag("rolling", expected.rolling(), actualRolling));
        fields.put("ground_mode", compareEnum("ground_mode",
            expected.groundMode(), actualGroundMode));

        FrameComparison result = new FrameComparison(expected.frame(), fields);
        allComparisons.add(result);
        return result;
    }

    /** Build the divergence report from all accumulated frame comparisons. */
    public DivergenceReport buildReport() {
        return new DivergenceReport(allComparisons);
    }

    /**
     * Validate that BK2-derived input matches trace-embedded input.
     * Returns true if they match, false on mismatch (alignment error).
     */
    public boolean validateInput(TraceFrame frame, int bk2Input) {
        return frame.input() == bk2Input;
    }

    private FieldComparison compareNumeric(String name, int expected, int actual,
            int warn, int error, boolean signChangeIsError) {
        int delta = Math.abs(expected - actual);

        // Check sign change (both nonzero, different signs)
        if (signChangeIsError && expected != 0 && actual != 0) {
            boolean expectedNeg = (expected < 0) || (expected > 0x7FFF);
            boolean actualNeg = (actual < 0) || (actual > 0x7FFF);
            if (expectedNeg != actualNeg) {
                return new FieldComparison(name,
                    formatHex(expected), formatHex(actual), Severity.ERROR, delta);
            }
        }

        Severity severity = tolerances.classify(delta, warn, error);
        return new FieldComparison(name,
            formatHex(expected), formatHex(actual), severity, delta);
    }

    private FieldComparison compareFlag(String name, boolean expected, boolean actual) {
        if (expected == actual) {
            return new FieldComparison(name,
                String.valueOf(expected ? 1 : 0), String.valueOf(actual ? 1 : 0),
                Severity.MATCH, 0);
        }
        return new FieldComparison(name,
            String.valueOf(expected ? 1 : 0), String.valueOf(actual ? 1 : 0),
            Severity.ERROR, 1);
    }

    private FieldComparison compareEnum(String name, int expected, int actual) {
        if (expected == actual) {
            return new FieldComparison(name,
                String.valueOf(expected), String.valueOf(actual), Severity.MATCH, 0);
        }
        return new FieldComparison(name,
            String.valueOf(expected), String.valueOf(actual),
            Severity.ERROR, Math.abs(expected - actual));
    }

    private static String formatHex(int value) {
        if (value < 0) {
            return String.format("-%04X", -value);
        }
        return String.format("0x%04X", value & 0xFFFF);
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=TestTraceBinder -pl . -q`
Expected: All 6 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/TraceBinder.java src/test/java/com/openggf/tests/trace/TestTraceBinder.java
git commit -m "Add TraceBinder comparison engine with tolerance tests"
```

---

## Task 6: DivergenceGroup and DivergenceReport with Unit Tests

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/DivergenceGroup.java`
- Create: `src/test/java/com/openggf/tests/trace/DivergenceReport.java`
- Create: `src/test/java/com/openggf/tests/trace/TestDivergenceReport.java`

- [ ] **Step 1: Create DivergenceGroup record**

```java
package com.openggf.tests.trace;

/**
 * A run of consecutive frames where the same field diverged at the same severity.
 */
public record DivergenceGroup(
    String field,
    Severity severity,
    int startFrame,
    int endFrame,
    String expectedAtStart,
    String actualAtStart,
    boolean cascading
) {
    public int frameSpan() {
        return endFrame - startFrame + 1;
    }
}
```

- [ ] **Step 2: Write failing tests for DivergenceReport**

```java
package com.openggf.tests.trace;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TestDivergenceReport {

    @Test
    public void testEmptyReportHasNoErrors() {
        DivergenceReport report = new DivergenceReport(List.of());
        assertFalse(report.hasErrors());
        assertFalse(report.hasWarnings());
        assertTrue(report.errors().isEmpty());
    }

    @Test
    public void testSingleErrorFrame() {
        FrameComparison frame = makeComparison(5, "air", Severity.ERROR, "0", "1");
        DivergenceReport report = new DivergenceReport(List.of(frame));

        assertTrue(report.hasErrors());
        assertEquals(1, report.errors().size());
        DivergenceGroup group = report.errors().get(0);
        assertEquals("air", group.field());
        assertEquals(5, group.startFrame());
        assertEquals(5, group.endFrame());
    }

    @Test
    public void testConsecutiveFramesGrouped() {
        FrameComparison f1 = makeComparison(10, "x", Severity.ERROR, "0x0050", "0x0150");
        FrameComparison f2 = makeComparison(11, "x", Severity.ERROR, "0x0052", "0x0152");
        FrameComparison f3 = makeComparison(12, "x", Severity.ERROR, "0x0054", "0x0154");

        DivergenceReport report = new DivergenceReport(List.of(f1, f2, f3));
        assertEquals(1, report.errors().size());
        DivergenceGroup group = report.errors().get(0);
        assertEquals(10, group.startFrame());
        assertEquals(12, group.endFrame());
        assertEquals(3, group.frameSpan());
    }

    @Test
    public void testWarningsAndErrorsSeparated() {
        FrameComparison f1 = makeComparison(0, "x", Severity.WARNING, "0x0050", "0x0051");
        FrameComparison f2 = makeComparison(1, "air", Severity.ERROR, "0", "1");

        DivergenceReport report = new DivergenceReport(List.of(f1, f2));
        assertTrue(report.hasErrors());
        assertTrue(report.hasWarnings());
        assertEquals(1, report.errors().size());
        assertEquals(1, report.warnings().size());
    }

    @Test
    public void testSummaryOutput() {
        FrameComparison f1 = makeComparison(5, "air", Severity.ERROR, "0", "1");
        DivergenceReport report = new DivergenceReport(List.of(f1));
        String summary = report.toSummary();

        assertTrue(summary.contains("1 error"));
        assertTrue(summary.contains("frame 5"));
    }

    @Test
    public void testContextWindow() {
        // Build a set of frame comparisons (frames 0-4, divergence at frame 2)
        FrameComparison f0 = makeMatchComparison(0, (short) 0x50, (short) 0x3B0);
        FrameComparison f1 = makeMatchComparison(1, (short) 0x51, (short) 0x3B0);
        FrameComparison f2 = makeComparison(2, "air", Severity.ERROR, "0", "1");
        FrameComparison f3 = makeComparison(3, "air", Severity.ERROR, "0", "1");
        FrameComparison f4 = makeMatchComparison(4, (short) 0x54, (short) 0x3B0);

        DivergenceReport report = new DivergenceReport(List.of(f0, f1, f2, f3, f4));
        String context = report.getContextWindow(2, 2);

        assertNotNull(context);
        assertTrue(context.contains("Frame"));
    }

    private FrameComparison makeComparison(int frame, String field,
            Severity severity, String expected, String actual) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        fields.put(field, new FieldComparison(field, expected, actual, severity,
            severity == Severity.MATCH ? 0 : 1));
        return new FrameComparison(frame, fields);
    }

    private FrameComparison makeMatchComparison(int frame, short x, short y) {
        Map<String, FieldComparison> fields = new LinkedHashMap<>();
        String xHex = String.format("0x%04X", x & 0xFFFF);
        String yHex = String.format("0x%04X", y & 0xFFFF);
        fields.put("x", new FieldComparison("x", xHex, xHex, Severity.MATCH, 0));
        fields.put("y", new FieldComparison("y", yHex, yHex, Severity.MATCH, 0));
        fields.put("air", new FieldComparison("air", "0", "0", Severity.MATCH, 0));
        return new FrameComparison(frame, fields);
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `mvn test -Dtest=TestDivergenceReport -pl . -q`
Expected: Compilation error — `DivergenceReport` class does not exist yet.

- [ ] **Step 4: Implement DivergenceReport**

```java
package com.openggf.tests.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.*;

/**
 * Structured divergence report: groups consecutive divergent frames by field
 * and severity, provides context windows and JSON serialisation for agent diagnosis.
 */
public class DivergenceReport {

    private final List<FrameComparison> allComparisons;
    private final List<DivergenceGroup> errors;
    private final List<DivergenceGroup> warnings;

    public DivergenceReport(List<FrameComparison> comparisons) {
        this.allComparisons = List.copyOf(comparisons);
        List<DivergenceGroup> allGroups = buildGroups(comparisons);
        this.errors = allGroups.stream()
            .filter(g -> g.severity() == Severity.ERROR)
            .toList();
        this.warnings = allGroups.stream()
            .filter(g -> g.severity() == Severity.WARNING)
            .toList();
    }

    public List<DivergenceGroup> errors() { return errors; }
    public List<DivergenceGroup> warnings() { return warnings; }
    public boolean hasErrors() { return !errors.isEmpty(); }
    public boolean hasWarnings() { return !warnings.isEmpty(); }

    /** Human-readable summary. */
    public String toSummary() {
        int errorCount = errors.size();
        int warningCount = warnings.size();

        if (errorCount == 0 && warningCount == 0) {
            return "All frames match trace. No divergences.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%d error%s, %d warning%s.",
            errorCount, errorCount == 1 ? "" : "s",
            warningCount, warningCount == 1 ? "" : "s"));

        if (errorCount > 0) {
            DivergenceGroup first = errors.get(0);
            sb.append(String.format(" First error: frame %d -- %s mismatch (expected=%s, actual=%s)",
                first.startFrame(), first.field(), first.expectedAtStart(), first.actualAtStart()));
        }
        return sb.toString();
    }

    /** JSON serialisation for agent consumption. */
    public String toJson() {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            ObjectNode root = mapper.createObjectNode();

            root.put("error_count", errors.size());
            root.put("warning_count", warnings.size());
            root.put("total_frames", allComparisons.size());
            root.put("summary", toSummary());

            ArrayNode errorsNode = root.putArray("errors");
            for (DivergenceGroup g : errors) {
                errorsNode.add(groupToJson(mapper, g));
            }

            ArrayNode warningsNode = root.putArray("warnings");
            for (DivergenceGroup g : warnings) {
                warningsNode.add(groupToJson(mapper, g));
            }

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\": \"Failed to serialise report: " + e.getMessage() + "\"}";
        }
    }

    /**
     * Context window showing expected vs actual state for frames around a divergence.
     * @param centreFrame the frame to centre on
     * @param radius number of frames before and after to include
     */
    public String getContextWindow(int centreFrame, int radius) {
        int start = Math.max(0, centreFrame - radius);
        int end = Math.min(allComparisons.size() - 1, centreFrame + radius);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-6s", "Frame"));

        // Collect all field names from comparisons in range
        Set<String> fieldNames = new LinkedHashSet<>();
        for (int i = start; i <= end; i++) {
            if (i < allComparisons.size()) {
                fieldNames.addAll(allComparisons.get(i).fields().keySet());
            }
        }

        for (String field : fieldNames) {
            sb.append(String.format(" | %-8s | %-8s", "Exp " + field, "Act " + field));
        }
        sb.append("\n");

        for (int i = start; i <= end; i++) {
            if (i >= allComparisons.size()) break;
            FrameComparison fc = allComparisons.get(i);
            sb.append(String.format("%-6d", fc.frame()));
            for (String field : fieldNames) {
                FieldComparison comp = fc.fields().get(field);
                if (comp != null) {
                    String marker = comp.severity() == Severity.ERROR ? "*" : " ";
                    sb.append(String.format(" | %-8s |%s%-7s",
                        comp.expected(), marker, comp.actual()));
                } else {
                    sb.append(String.format(" | %-8s | %-8s", "?", "?"));
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static List<DivergenceGroup> buildGroups(List<FrameComparison> comparisons) {
        List<DivergenceGroup> groups = new ArrayList<>();

        // Track open groups by field name
        Map<String, DivergenceGroupBuilder> openGroups = new LinkedHashMap<>();

        for (FrameComparison fc : comparisons) {
            Set<String> activeFields = new HashSet<>();

            for (Map.Entry<String, FieldComparison> entry : fc.fields().entrySet()) {
                String field = entry.getKey();
                FieldComparison comp = entry.getValue();

                if (comp.isDivergent()) {
                    activeFields.add(field);
                    DivergenceGroupBuilder builder = openGroups.get(field);
                    if (builder != null && builder.severity == comp.severity()
                            && builder.endFrame == fc.frame() - 1) {
                        // Extend existing group
                        builder.endFrame = fc.frame();
                    } else {
                        // Close previous group for this field if exists
                        if (builder != null) {
                            groups.add(builder.build());
                        }
                        // Start new group
                        openGroups.put(field, new DivergenceGroupBuilder(
                            field, comp.severity(), fc.frame(),
                            comp.expected(), comp.actual()));
                    }
                }
            }

            // Close groups for fields that are no longer divergent
            Iterator<Map.Entry<String, DivergenceGroupBuilder>> iter =
                openGroups.entrySet().iterator();
            while (iter.hasNext()) {
                Map.Entry<String, DivergenceGroupBuilder> entry = iter.next();
                if (!activeFields.contains(entry.getKey())) {
                    groups.add(entry.getValue().build());
                    iter.remove();
                }
            }
        }

        // Close any remaining open groups
        for (DivergenceGroupBuilder builder : openGroups.values()) {
            groups.add(builder.build());
        }

        // Sort by start frame
        groups.sort(Comparator.comparingInt(DivergenceGroup::startFrame));

        // Mark cascading: groups that start after an earlier error in a different field
        markCascading(groups);

        return groups;
    }

    private static void markCascading(List<DivergenceGroup> groups) {
        int earliestErrorFrame = Integer.MAX_VALUE;
        String earliestErrorField = null;
        for (DivergenceGroup g : groups) {
            if (g.severity() == Severity.ERROR && g.startFrame() < earliestErrorFrame) {
                earliestErrorFrame = g.startFrame();
                earliestErrorField = g.field();
            }
        }

        if (earliestErrorField == null) return;

        List<DivergenceGroup> updated = new ArrayList<>();
        for (DivergenceGroup g : groups) {
            boolean cascading = g.severity() == Severity.ERROR
                && g.startFrame() > earliestErrorFrame
                && !g.field().equals(earliestErrorField);
            if (cascading != g.cascading()) {
                updated.add(new DivergenceGroup(g.field(), g.severity(),
                    g.startFrame(), g.endFrame(),
                    g.expectedAtStart(), g.actualAtStart(), cascading));
            }
        }
        // Replace in-place (groups is mutable at this point in buildGroups)
        for (DivergenceGroup u : updated) {
            for (int i = 0; i < groups.size(); i++) {
                DivergenceGroup existing = groups.get(i);
                if (existing.field().equals(u.field())
                        && existing.startFrame() == u.startFrame()) {
                    groups.set(i, u);
                }
            }
        }
    }

    private ObjectNode groupToJson(ObjectMapper mapper, DivergenceGroup g) {
        ObjectNode node = mapper.createObjectNode();
        node.put("field", g.field());
        node.put("severity", g.severity().name());
        node.put("start_frame", g.startFrame());
        node.put("end_frame", g.endFrame());
        node.put("frame_span", g.frameSpan());
        node.put("expected_at_start", g.expectedAtStart());
        node.put("actual_at_start", g.actualAtStart());
        node.put("cascading", g.cascading());
        return node;
    }

    private static class DivergenceGroupBuilder {
        final String field;
        final Severity severity;
        final int startFrame;
        final String expectedAtStart;
        final String actualAtStart;
        int endFrame;

        DivergenceGroupBuilder(String field, Severity severity, int frame,
                String expected, String actual) {
            this.field = field;
            this.severity = severity;
            this.startFrame = frame;
            this.endFrame = frame;
            this.expectedAtStart = expected;
            this.actualAtStart = actual;
        }

        DivergenceGroup build() {
            return new DivergenceGroup(field, severity, startFrame, endFrame,
                expectedAtStart, actualAtStart, false);
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `mvn test -Dtest=TestDivergenceReport -pl . -q`
Expected: All 6 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/DivergenceGroup.java src/test/java/com/openggf/tests/trace/DivergenceReport.java src/test/java/com/openggf/tests/trace/TestDivergenceReport.java
git commit -m "Add DivergenceReport with grouping, context windows, JSON output"
```

---

## Task 7: Add BK2 Recording Support to HeadlessTestRunner and HeadlessTestFixture

**Files:**
- Modify: `src/test/java/com/openggf/tests/HeadlessTestRunner.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`

- [ ] **Step 1: Read current HeadlessTestRunner.java**

Read the file completely to identify exact insertion points. The runner needs:
- A `Bk2Movie` field and a `currentBk2Index` counter
- A `stepFrameFromRecording()` method that reads the current BK2 frame's input mask and delegates to `stepFrame()`
- A method to get the BK2 input mask for the current frame (for trace input validation)

- [ ] **Step 2: Add BK2 fields and stepFrameFromRecording to HeadlessTestRunner**

Add to the class:

```java
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2FrameInput;

// New fields
private Bk2Movie bk2Movie;
private int bk2StartIndex;     // Index into movie frames where trace begins
private int currentBk2Index;   // Current position within movie playback

// Setter called by HeadlessTestFixture.Builder
public void setBk2Movie(Bk2Movie movie, int bk2FrameOffset) {
    this.bk2Movie = movie;
    this.bk2StartIndex = movie.bk2FrameToIndex(bk2FrameOffset);
    this.currentBk2Index = bk2StartIndex;
}

/**
 * Step one frame using input from the BK2 movie recording.
 * Returns the raw input mask used (for trace input validation).
 */
public int stepFrameFromRecording() {
    if (bk2Movie == null) {
        throw new IllegalStateException("No BK2 movie loaded. Call setBk2Movie() first.");
    }
    if (currentBk2Index >= bk2Movie.getFrameCount()) {
        throw new IllegalStateException(
            "BK2 movie exhausted at index " + currentBk2Index
            + " (movie has " + bk2Movie.getFrameCount() + " frames)");
    }

    Bk2FrameInput frameInput = bk2Movie.getFrame(currentBk2Index);
    int mask = frameInput.p1InputMask();

    boolean up    = (mask & INPUT_UP) != 0;
    boolean down  = (mask & INPUT_DOWN) != 0;
    boolean left  = (mask & INPUT_LEFT) != 0;
    boolean right = (mask & INPUT_RIGHT) != 0;
    boolean jump  = (mask & INPUT_JUMP) != 0 || frameInput.p1ActionMask() != 0;

    stepFrame(up, down, left, right, jump);
    currentBk2Index++;

    return mask;
}

public int getRecordingFramesRemaining() {
    if (bk2Movie == null) return 0;
    return bk2Movie.getFrameCount() - currentBk2Index;
}
```

Note: `INPUT_UP`, `INPUT_DOWN`, `INPUT_LEFT`, `INPUT_RIGHT`, `INPUT_JUMP` are constants from `AbstractPlayableSprite` — import statically or reference via the class.

- [ ] **Step 3: Add withRecording to HeadlessTestFixture.Builder**

Add to the `Builder` inner class:

```java
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import java.io.IOException;
import java.nio.file.Path;

// New builder fields
private Bk2Movie bk2Movie;
private int bk2FrameOffset;

public Builder withRecording(Path bk2Path) throws IOException {
    this.bk2Movie = new Bk2MovieLoader().load(bk2Path);
    return this;
}

public Builder withRecordingStartFrame(int bk2FrameOffset) {
    this.bk2FrameOffset = bk2FrameOffset;
    return this;
}
```

In the `build()` method, after creating the `HeadlessTestRunner`, add:

```java
if (bk2Movie != null) {
    runner.setBk2Movie(bk2Movie, bk2FrameOffset);
}
```

Add a convenience delegate to `HeadlessTestFixture`:

```java
public int stepFrameFromRecording() {
    return runner.stepFrameFromRecording();
}
```

- [ ] **Step 4: Run existing tests to verify no regression**

Run: `mvn test -Dtest=TestS1Ghz1Headless -pl . -q`
Expected: All existing tests still PASS (the new fields default to null, existing tests don't use them).

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/openggf/tests/HeadlessTestRunner.java src/test/java/com/openggf/tests/HeadlessTestFixture.java
git commit -m "Add BK2 recording playback support to HeadlessTestRunner/Fixture"
```

---

## Task 8: AbstractTraceReplayTest Base Class

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`

- [ ] **Step 1: Read existing test patterns**

Re-read `TestS1Ghz1Headless.java` for the `@RequiresRom` + `RequiresRomRule` + `SharedLevel` pattern. The abstract base class will follow this pattern but automate the trace loading and comparison loop.

- [ ] **Step 2: Implement AbstractTraceReplayTest**

```java
package com.openggf.tests.trace;

import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.RequiresRomRule;
import com.openggf.tests.rules.SonicGame;
import org.junit.ClassRule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

/**
 * Abstract base class for trace replay tests. Subclasses provide game/zone/act/path;
 * this class handles level loading, BK2 playback, per-frame comparison, and report output.
 *
 * <p>The abstract game()/zone()/act() methods drive level loading.
 * On load, the base class validates these match metadata.json — a mismatch is a
 * test configuration error.</p>
 */
public abstract class AbstractTraceReplayTest {

    @ClassRule
    public static RequiresRomRule romRule = new RequiresRomRule();

    /** The game this test targets (used for @RequiresRom and level loading). */
    protected abstract SonicGame game();

    /** Zone index for level loading. */
    protected abstract int zone();

    /** Act index for level loading. */
    protected abstract int act();

    /** Path to the trace directory containing metadata.json, physics.csv, etc. */
    protected abstract Path traceDirectory();

    /** Override to customise tolerance thresholds. */
    protected ToleranceConfig tolerances() {
        return ToleranceConfig.DEFAULT;
    }

    /** Path to the report output directory. Created if absent. */
    protected Path reportOutputDir() {
        return Path.of("target/trace-reports");
    }

    @Test
    public void replayMatchesTrace() throws Exception {
        // 1. Load trace data
        TraceData trace = TraceData.load(traceDirectory());
        TraceMetadata meta = trace.metadata();

        // 2. Validate test configuration matches metadata
        validateMetadata(meta);

        // 3. Find BK2 file in trace directory
        Path bk2Path = findBk2File(traceDirectory());
        assertNotNull("No .bk2 file found in " + traceDirectory(), bk2Path);

        // 4. Load level and create fixture
        SharedLevel sharedLevel = SharedLevel.load(game(), zone(), act());
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .startPosition(meta.startX(), meta.startY())
                .withRecording(bk2Path)
                .withRecordingStartFrame(meta.bk2FrameOffset())
                .build();

            // 5. Run frame-by-frame comparison
            TraceBinder binder = new TraceBinder(tolerances());
            for (int i = 0; i < trace.frameCount(); i++) {
                TraceFrame expected = trace.getFrame(i);

                // Step engine and get the input mask used
                int bk2Input = fixture.stepFrameFromRecording();

                // Validate input alignment
                if (!binder.validateInput(expected, bk2Input)) {
                    fail(String.format(
                        "Input alignment error at trace frame %d: " +
                        "BK2 input=0x%04X, trace input=0x%04X. " +
                        "Check bk2_frame_offset in metadata.json.",
                        i, bk2Input, expected.input()));
                }

                // Compare physics state
                var sprite = fixture.sprite();
                binder.compareFrame(expected,
                    sprite.getCentreX(), sprite.getCentreY(),
                    sprite.getXSpeed(), sprite.getYSpeed(), sprite.getGSpeed(),
                    sprite.getAngle(),
                    sprite.getAir(), sprite.getRolling(),
                    sprite.getGroundMode().ordinal());
            }

            // 6. Build report
            DivergenceReport report = binder.buildReport();

            // 7. Write report if there are any divergences
            if (report.hasErrors() || report.hasWarnings()) {
                writeReport(report, meta);
            }

            // 8. Log summary
            System.out.println(report.toSummary());

            // 9. Assert no errors
            if (report.hasErrors()) {
                // Write context window for first error to console
                DivergenceGroup firstError = report.errors().get(0);
                System.err.println("\n=== Context window around first error ===");
                System.err.println(report.getContextWindow(firstError.startFrame(), 10));
                fail(report.toSummary());
            }
        } finally {
            sharedLevel.dispose();
        }
    }

    private void validateMetadata(TraceMetadata meta) {
        String expectedGameId = switch (game()) {
            case SONIC_1 -> "s1";
            case SONIC_2 -> "s2";
            case SONIC_3K -> "s3k";
        };
        assertEquals("Metadata game mismatch (test says " + game()
            + " but metadata says " + meta.game() + ")",
            expectedGameId, meta.game());
    }

    private Path findBk2File(Path dir) throws IOException {
        try (var files = Files.list(dir)) {
            return files
                .filter(p -> p.toString().endsWith(".bk2"))
                .findFirst()
                .orElse(null);
        }
    }

    private void writeReport(DivergenceReport report, TraceMetadata meta) {
        try {
            Path outDir = reportOutputDir();
            Files.createDirectories(outDir);

            String prefix = meta.game() + "_" + meta.zone() + meta.act();
            Path jsonPath = outDir.resolve(prefix + "_report.json");
            Files.writeString(jsonPath, report.toJson());

            // Write context for first error if present
            if (report.hasErrors()) {
                DivergenceGroup firstError = report.errors().get(0);
                Path contextPath = outDir.resolve(prefix + "_context.txt");
                Files.writeString(contextPath,
                    report.getContextWindow(firstError.startFrame(), 20));
            }
        } catch (IOException e) {
            System.err.println("Warning: failed to write report: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java src/test/java/com/openggf/tests/trace/TraceBinder.java
git commit -m "Add AbstractTraceReplayTest base class with full replay loop"
```

---

## Task 9: Concrete GHZ1 Test and Gitignore Update

**Files:**
- Create: `src/test/java/com/openggf/tests/trace/s1/TestS1Ghz1TraceReplay.java`
- Modify: `.gitignore`

- [ ] **Step 1: Create the concrete test class**

```java
package com.openggf.tests.trace.s1;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.tests.trace.AbstractTraceReplayTest;

import java.nio.file.Path;

/**
 * Trace replay test for Sonic 1 GHZ Act 1.
 * Requires a BizHawk trace recording in src/test/resources/traces/s1/ghz1_fullrun/.
 */
@RequiresRom(SonicGame.SONIC_1)
public class TestS1Ghz1TraceReplay extends AbstractTraceReplayTest {

    @Override
    protected SonicGame game() { return SonicGame.SONIC_1; }

    @Override
    protected int zone() { return 0; }

    @Override
    protected int act() { return 0; }

    @Override
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s1/ghz1_fullrun");
    }
}
```

- [ ] **Step 2: Add trace-reports to gitignore**

Add to `.gitignore`:

```
# Trace replay test reports
target/trace-reports/
```

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/trace/s1/TestS1Ghz1TraceReplay.java .gitignore
git commit -m "Add concrete GHZ1 trace replay test and gitignore reports dir"
```

---

## Task 10: BizHawk Lua Script

**Files:**
- Create: `tools/bizhawk/s1_trace_recorder.lua`
- Create: `tools/bizhawk/README.md`

- [ ] **Step 1: Create the Lua script**

```lua
------------------------------------------------------------------------------
-- s1_trace_recorder.lua
-- BizHawk Lua script for recording Sonic 1 REV01 frame-by-frame physics
-- state during BK2 movie playback.
--
-- Usage:
--   1. Open BizHawk with Sonic 1 REV01 ROM
--   2. Load a BK2 movie file
--   3. Tools > Lua Console > load this script
--   4. Play the movie -- recording starts automatically when gameplay begins
--   5. Stop the movie or close the script to finalise output files
------------------------------------------------------------------------------

-----------------
--- Constants ---
-----------------

-- Output directory (relative to BizHawk working dir)
local OUTPUT_DIR = "trace_output/"

-- S1 REV01 68K RAM addresses (mainmemory domain = $FF0000 base stripped)
local ADDR_GAME_MODE       = 0xF600
local ADDR_CTRL1_LOCKED    = 0xF604
local ADDR_CTRL1           = 0xF602

-- Player object base ($FFD000)
local PLAYER_BASE          = 0xD000
local OFF_X_POS            = 0x08   -- word: centre X
local OFF_X_SUB            = 0x0A   -- byte: X subpixel
local OFF_Y_POS            = 0x0C   -- word: centre Y
local OFF_Y_SUB            = 0x0E   -- byte: Y subpixel
local OFF_X_VEL            = 0x10   -- signed byte + subpixel byte
local OFF_Y_VEL            = 0x12   -- signed byte + subpixel byte
local OFF_INERTIA          = 0x14   -- signed byte + subpixel byte (ground speed)
local OFF_RADIUS_Y         = 0x16   -- signed byte
local OFF_RADIUS_X         = 0x17   -- signed byte
local OFF_ANIM_FRAME_DISP  = 0x1A   -- byte
local OFF_ANIM_FRAME       = 0x1B   -- byte
local OFF_ANIM_ID          = 0x1C   -- byte
local OFF_ANIM_TIMER       = 0x1E   -- byte
local OFF_STATUS           = 0x22   -- byte: status flags
local OFF_ANGLE            = 0x26   -- byte: terrain angle
local OFF_STICK_CONVEX     = 0x38   -- byte
local OFF_CTRL_LOCK        = 0x3E   -- word: control lock timer

-- Status flag bits
local STATUS_FACING_LEFT   = 0x01
local STATUS_IN_AIR        = 0x02
local STATUS_ROLLING       = 0x04
local STATUS_ON_OBJECT     = 0x08
local STATUS_ROLL_JUMP     = 0x10
local STATUS_PUSHING       = 0x20
local STATUS_UNDERWATER    = 0x40

-- Object table
local OBJ_TABLE_START      = 0xD000
local OBJ_TABLE_END        = 0xD000 + (64 * 0x40) -- 64 slots, 64 bytes each
local OBJ_SLOT_SIZE        = 0x40

-- Genesis joypad bitmask (matching engine convention)
-- Engine: UP=0x01, DOWN=0x02, LEFT=0x04, RIGHT=0x08, JUMP=0x10
local INPUT_UP    = 0x01
local INPUT_DOWN  = 0x02
local INPUT_LEFT  = 0x04
local INPUT_RIGHT = 0x08
local INPUT_JUMP  = 0x10

-- Game mode values
local GAMEMODE_LEVEL = 0x0C

-- Snapshot interval (frames between full state snapshots in aux file)
local SNAPSHOT_INTERVAL = 60

-----------------
--- State     ---
-----------------

local started = false
local trace_frame = 0
local bk2_frame_offset = 0
local start_x = 0
local start_y = 0

local prev_status = 0
local prev_ctrl_lock = 0

-- Object tracking: slot -> last known type ID
local known_objects = {}

-- File handles
local physics_file = nil
local aux_file = nil

-----------------
--- Helpers   ---
-----------------

-- Read a 16-bit signed value from two bytes (big-endian: high byte signed + low byte)
local function read_speed(base, offset)
    return mainmemory.read_s16_be(base + offset)
end

-- Convert joypad.get() table to engine input bitmask
local function joypad_to_mask(joy)
    local mask = 0
    if joy["Up"]    then mask = mask + INPUT_UP end
    if joy["Down"]  then mask = mask + INPUT_DOWN end
    if joy["Left"]  then mask = mask + INPUT_LEFT end
    if joy["Right"] then mask = mask + INPUT_RIGHT end
    if joy["A"] or joy["B"] or joy["C"] then mask = mask + INPUT_JUMP end
    return mask
end

-- Format a number as hex with specified width
local function hex(val, width)
    width = width or 4
    if val < 0 then
        -- Convert signed to unsigned 16-bit for display
        val = val + 0x10000
    end
    return string.format("%0" .. width .. "X", val)
end

-- Get ground mode from angle (derived from angle quadrant)
-- Floor=0, RightWall=1, Ceiling=2, LeftWall=3
local function angle_to_ground_mode(angle)
    if angle >= 0x00 and angle <= 0x3F then return 0 end     -- Floor (0-63)
    if angle >= 0x40 and angle <= 0x7F then return 1 end     -- Right wall (64-127)
    if angle >= 0x80 and angle <= 0xBF then return 2 end     -- Ceiling (128-191)
    return 3                                                    -- Left wall (192-255)
end

-- Write a JSONL line to aux file
local function write_aux(json_str)
    if aux_file then
        aux_file:write(json_str .. "\n")
        aux_file:flush()
    end
end

-----------------
--- Recording ---
-----------------

local function open_files()
    -- Ensure output directory exists
    os.execute("mkdir \"" .. OUTPUT_DIR .. "\" 2>NUL")

    physics_file = io.open(OUTPUT_DIR .. "physics.csv", "w")
    aux_file = io.open(OUTPUT_DIR .. "aux_state.jsonl", "w")

    -- Write CSV header
    physics_file:write("frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode\n")
    physics_file:flush()
end

local function write_metadata()
    local meta_file = io.open(OUTPUT_DIR .. "metadata.json", "w")
    meta_file:write("{\n")
    meta_file:write('  "game": "s1",\n')
    meta_file:write('  "zone": "ghz",\n')
    meta_file:write('  "act": 1,\n')
    meta_file:write('  "bk2_frame_offset": ' .. bk2_frame_offset .. ',\n')
    meta_file:write('  "trace_frame_count": ' .. trace_frame .. ',\n')
    meta_file:write('  "start_x": "0x' .. hex(start_x) .. '",\n')
    meta_file:write('  "start_y": "0x' .. hex(start_y) .. '",\n')
    meta_file:write('  "recording_date": "' .. os.date("%Y-%m-%d") .. '",\n')
    meta_file:write('  "lua_script_version": "1.0",\n')
    meta_file:write('  "rom_checksum": "",\n')
    meta_file:write('  "notes": ""\n')
    meta_file:write("}\n")
    meta_file:close()
    print("Metadata written. Trace frames: " .. trace_frame)
end

local function close_files()
    if physics_file then
        physics_file:close()
        physics_file = nil
    end
    if aux_file then
        aux_file:close()
        aux_file = nil
    end
end

local function scan_objects()
    for slot = 1, 63 do -- Skip slot 0 (player)
        local addr = OBJ_TABLE_START + (slot * OBJ_SLOT_SIZE)
        local obj_id = mainmemory.read_u8(addr)

        local prev_id = known_objects[slot] or 0
        if obj_id ~= 0 and obj_id ~= prev_id then
            -- New object appeared in this slot
            local obj_x = mainmemory.read_u16_be(addr + OFF_X_POS)
            local obj_y = mainmemory.read_u16_be(addr + OFF_Y_POS)
            write_aux(string.format(
                '{"frame":%d,"event":"object_appeared","object_type":"0x%02X","x":"0x%04X","y":"0x%04X"}',
                trace_frame, obj_id, obj_x, obj_y))
        end
        known_objects[slot] = obj_id
    end
end

local function write_state_snapshot()
    local ctrl_lock = mainmemory.read_u16_be(PLAYER_BASE + OFF_CTRL_LOCK)
    local anim_id = mainmemory.read_u8(PLAYER_BASE + OFF_ANIM_ID)
    local status = mainmemory.read_u8(PLAYER_BASE + OFF_STATUS)

    write_aux(string.format(
        '{"frame":%d,"event":"state_snapshot","control_locked":%s,"anim_id":%d,'
        .. '"status_byte":"0x%02X","on_object":%s,"pushing":%s,"underwater":%s,'
        .. '"roll_jumping":%s}',
        trace_frame,
        ctrl_lock > 0 and "true" or "false",
        anim_id,
        status,
        (bit.band(status, STATUS_ON_OBJECT) ~= 0) and "true" or "false",
        (bit.band(status, STATUS_PUSHING) ~= 0) and "true" or "false",
        (bit.band(status, STATUS_UNDERWATER) ~= 0) and "true" or "false",
        (bit.band(status, STATUS_ROLL_JUMP) ~= 0) and "true" or "false"
    ))
end

local function check_mode_changes(status)
    -- Air flag
    local was_air = bit.band(prev_status, STATUS_IN_AIR) ~= 0
    local is_air = bit.band(status, STATUS_IN_AIR) ~= 0
    if was_air ~= is_air then
        write_aux(string.format(
            '{"frame":%d,"event":"mode_change","field":"air","from":%d,"to":%d}',
            trace_frame, was_air and 1 or 0, is_air and 1 or 0))
        -- Also write a state snapshot on mode change
        write_state_snapshot()
    end

    -- Rolling flag
    local was_rolling = bit.band(prev_status, STATUS_ROLLING) ~= 0
    local is_rolling = bit.band(status, STATUS_ROLLING) ~= 0
    if was_rolling ~= is_rolling then
        write_aux(string.format(
            '{"frame":%d,"event":"mode_change","field":"rolling","from":%d,"to":%d}',
            trace_frame, was_rolling and 1 or 0, is_rolling and 1 or 0))
    end

    -- Control lock
    local ctrl_lock = mainmemory.read_u16_be(PLAYER_BASE + OFF_CTRL_LOCK)
    local was_locked = prev_ctrl_lock > 0
    local is_locked = ctrl_lock > 0
    if was_locked ~= is_locked then
        write_aux(string.format(
            '{"frame":%d,"event":"mode_change","field":"control_locked","from":%d,"to":%d}',
            trace_frame, was_locked and 1 or 0, is_locked and 1 or 0))
    end
    prev_ctrl_lock = ctrl_lock
end

-----------------
--- Main Loop ---
-----------------

local function on_frame_end()
    local game_mode = mainmemory.read_u8(ADDR_GAME_MODE)
    local ctrl_locked = mainmemory.read_u8(ADDR_CTRL1_LOCKED)

    if not started then
        -- Wait for level gameplay with controls unlocked
        if game_mode == GAMEMODE_LEVEL and ctrl_locked == 0 then
            started = true
            bk2_frame_offset = emu.framecount()
            start_x = mainmemory.read_u16_be(PLAYER_BASE + OFF_X_POS)
            start_y = mainmemory.read_u16_be(PLAYER_BASE + OFF_Y_POS)

            open_files()
            print(string.format("Trace recording started at BizHawk frame %d, pos (%04X, %04X)",
                bk2_frame_offset, start_x, start_y))
        end
        return
    end

    -- Stop recording if we leave level gameplay
    if game_mode ~= GAMEMODE_LEVEL then
        print("Left level gameplay at trace frame " .. trace_frame .. ". Finalising.")
        write_metadata()
        close_files()
        started = false
        return
    end

    -- Read player state
    local x = mainmemory.read_u16_be(PLAYER_BASE + OFF_X_POS)
    local y = mainmemory.read_u16_be(PLAYER_BASE + OFF_Y_POS)
    local x_speed = read_speed(PLAYER_BASE, OFF_X_VEL)
    local y_speed = read_speed(PLAYER_BASE, OFF_Y_VEL)
    local g_speed = read_speed(PLAYER_BASE, OFF_INERTIA)
    local angle = mainmemory.read_u8(PLAYER_BASE + OFF_ANGLE)
    local status = mainmemory.read_u8(PLAYER_BASE + OFF_STATUS)

    local air = bit.band(status, STATUS_IN_AIR) ~= 0
    local rolling = bit.band(status, STATUS_ROLLING) ~= 0
    local ground_mode = air and 0 or angle_to_ground_mode(angle)

    -- Read input
    local joy = joypad.get(1)
    local input_mask = joypad_to_mask(joy)

    -- Write CSV row
    physics_file:write(string.format("%04X,%04X,%04X,%04X,%04X,%04X,%04X,%02X,%d,%d,%d\n",
        trace_frame, input_mask, x, y,
        x_speed < 0 and (x_speed + 0x10000) or x_speed,
        y_speed < 0 and (y_speed + 0x10000) or y_speed,
        g_speed < 0 and (g_speed + 0x10000) or g_speed,
        angle,
        air and 1 or 0,
        rolling and 1 or 0,
        ground_mode))
    physics_file:flush()

    -- Check for mode changes (writes aux events)
    check_mode_changes(status)
    prev_status = status

    -- Periodic state snapshot
    if trace_frame % SNAPSHOT_INTERVAL == 0 then
        write_state_snapshot()
    end

    -- Scan for new objects
    scan_objects()

    trace_frame = trace_frame + 1
end

-- Register the callback
event.onframeend(on_frame_end, "S1TraceRecorder")
print("S1 Trace Recorder loaded. Waiting for level gameplay to begin...")

-- Cleanup on script exit
event.onexit(function()
    if started then
        write_metadata()
        close_files()
        print("Script exiting. Trace finalised at frame " .. trace_frame)
    end
end, "S1TraceRecorderExit")
```

- [ ] **Step 2: Create README**

```markdown
# BizHawk Trace Recording

## Requirements

- BizHawk 2.9+ (tested with 2.9.1)
- Genesis/Mega Drive core: "Genplus-gx" (default) or "PicoDrive"
- Sonic 1 REV01 ROM: `Sonic The Hedgehog (W) (REV01) [!].gen`

## Recording a Trace

1. Open BizHawk and load the Sonic 1 ROM
2. File > Movie > Play Movie... > select your .bk2 file
3. Tools > Lua Console
4. Script > Open Script... > select `s1_trace_recorder.lua`
5. Close the Lua Console dialog (the script keeps running)
6. The movie plays automatically. The console prints when recording starts
7. When the movie ends or you stop it, trace files are written

## Output

The script creates a `trace_output/` directory in BizHawk's working directory
containing:

- `metadata.json` - Recording metadata (game, zone, BK2 offset, etc.)
- `physics.csv` - Per-frame physics state (position, speed, angle, flags)
- `aux_state.jsonl` - Auxiliary events (object appearances, mode changes, snapshots)

## Using with OpenGGF

1. Copy the output files to `src/test/resources/traces/s1/<trace_name>/`
2. Copy the .bk2 file to the same directory
3. Update `metadata.json` zone/act fields if not GHZ Act 1
4. Create a test class extending `AbstractTraceReplayTest`
5. Run with `mvn test -Dtest=TestS1Ghz1TraceReplay`

## Notes

- Recording starts automatically when level gameplay begins (Game_Mode=0x0C, controls unlocked)
- Recording stops when leaving level gameplay (act clear, death, etc.)
- The `zone` and `act` fields in metadata.json default to "ghz"/1 -- update manually for other levels
- Ground mode is derived from the terrain angle quadrant when grounded
```

- [ ] **Step 3: Commit**

```bash
git add tools/bizhawk/s1_trace_recorder.lua tools/bizhawk/README.md
git commit -m "Add BizHawk Lua script for recording S1 REV01 traces"
```

---

## Task 11: Placeholder Trace for Compilation Verification

**Files:**
- Create: `src/test/resources/traces/s1/ghz1_fullrun/metadata.json`
- Create: `src/test/resources/traces/s1/ghz1_fullrun/physics.csv`
- Create: `src/test/resources/traces/s1/ghz1_fullrun/aux_state.jsonl`

This task creates a minimal placeholder trace so the concrete test class compiles and the
trace-loading code path exercises. The test will be skipped via `@RequiresRom` if no ROM
is present. The placeholder will be replaced with a real recording later.

- [ ] **Step 1: Create placeholder trace files**

`src/test/resources/traces/s1/ghz1_fullrun/metadata.json`:
```json
{
  "game": "s1",
  "zone": "ghz",
  "act": 1,
  "bk2_frame_offset": 0,
  "trace_frame_count": 1,
  "start_x": "0x0050",
  "start_y": "0x03B0",
  "recording_date": "2026-03-26",
  "lua_script_version": "1.0",
  "rom_checksum": "placeholder",
  "notes": "Placeholder - replace with real BizHawk recording"
}
```

`src/test/resources/traces/s1/ghz1_fullrun/physics.csv`:
```
frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode
0000,0000,0050,03B0,0000,0000,0000,00,0,0,0
```

`src/test/resources/traces/s1/ghz1_fullrun/aux_state.jsonl`:
```
{"frame":0,"event":"state_snapshot","control_locked":false,"anim_id":0}
```

- [ ] **Step 2: Commit**

```bash
git add src/test/resources/traces/s1/ghz1_fullrun/
git commit -m "Add placeholder GHZ1 trace for compilation verification"
```

---

## Task 12: Full Compilation and Test Verification

- [ ] **Step 1: Run all trace infrastructure unit tests**

Run: `mvn test -Dtest="TestTraceDataParsing,TestTraceBinder,TestDivergenceReport" -pl . -q`
Expected: All tests PASS. These tests do not require a ROM.

- [ ] **Step 2: Run full test suite to check for regressions**

Run: `mvn test -pl . -q`
Expected: No new failures. `TestS1Ghz1TraceReplay` should be skipped (no BK2 file in placeholder).

- [ ] **Step 3: Verify trace infrastructure classes compile cleanly**

Run: `mvn compile test-compile -pl . -q`
Expected: BUILD SUCCESS with no warnings in trace package.

- [ ] **Step 4: Final commit if any fixes were needed**

```bash
git add -A
git commit -m "Fix any compilation/test issues from trace replay integration"
```

---

## Summary

| Task | What it builds | Dependencies |
|------|---------------|-------------|
| 1 | `TraceFrame`, `TraceMetadata` records | None |
| 2 | `TraceEvent` sealed interface | None |
| 3 | `TraceData` reader + unit tests | Tasks 1-2 |
| 4 | `ToleranceConfig`, `Severity`, `FieldComparison`, `FrameComparison` | None |
| 5 | `TraceBinder` comparison engine + tests | Tasks 1, 4 |
| 6 | `DivergenceGroup`, `DivergenceReport` + tests | Tasks 4-5 |
| 7 | BK2 support in `HeadlessTestRunner`/`Fixture` | None (existing Bk2Movie) |
| 8 | `AbstractTraceReplayTest` base class | Tasks 3, 5, 6, 7 |
| 9 | `TestS1Ghz1TraceReplay` concrete test + gitignore | Task 8 |
| 10 | BizHawk Lua script + README | None (standalone) |
| 11 | Placeholder trace files | None |
| 12 | Full compilation/test verification | All above |

Tasks 1-2, 4, 7, 10, 11 are independent and can be parallelised. Tasks 3, 5, 6 form a chain. Task 8 depends on 3+5+6+7. Task 9 depends on 8. Task 12 is the final gate.
