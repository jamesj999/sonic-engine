# BizHawk Trace Replay Testing

## Purpose

Use BizHawk's Lua scripting to record frame-by-frame physics state from authentic Mega Drive emulation during BK2 movie playback, then replay the same inputs through the OpenGGF engine and compare state per frame. Primary goal is accuracy gap discovery; traces graduate into regression tests once accuracy is achieved.

## Architecture Overview

Two independent halves connected by a file format contract:

**BizHawk side:** A Lua script runs alongside BK2 movie playback, monitors RAM for gameplay start, and writes per-frame state to trace files.

**Engine side:** Java test infrastructure reads trace files, replays BK2 inputs through `HeadlessTestFixture`, compares engine state against trace state per frame, and produces a structured divergence report for diagnosis.

```
BizHawk + BK2 --> Lua script --> trace files (CSV + JSONL + metadata.json)
                                       |
                                       v
Engine tests <-- BK2 (inputs) ---------+
     |                                 |
     v                                 v
HeadlessTestFixture --> TraceBinder --> DivergenceReport
     |                                 |
     v                                 v
  frame-by-frame                  JSON file (for agent
  engine execution                diagnosis when test fails)
```

BizHawk and the engine never communicate directly. Trace files are the contract. Traces can be re-recorded independently of engine changes, and engine comparisons run without BizHawk.

## Trace File Format

A trace is a directory containing three or four files. The directory name is a human label (e.g. `ghz1_fullrun`, `ghz1_no_deaths_march_2026`) — not parsed. All game/zone/act information comes from `metadata.json`.

### `metadata.json`

```json
{
  "game": "s1",
  "zone": "ghz",
  "act": 1,
  "bk2_frame_offset": 847,
  "trace_frame_count": 5230,
  "start_x": "0x0050",
  "start_y": "0x03B0",
  "recording_date": "2026-03-26",
  "lua_script_version": "1.0",
  "rom_checksum": "...",
  "notes": "Full act playthrough, no deaths"
}
```

- `bk2_frame_offset` — BizHawk global frame number where the trace begins. The engine skips this many BK2 frames before starting comparison.
- `start_x`, `start_y` — player centre position on trace frame 0. Used to initialise the engine sprite and as a sanity check.

### `physics.csv` (primary trace)

Core physics state every frame. All numeric values in hex for consistency with disassembly conventions.

```
frame,input,x,y,x_speed,y_speed,g_speed,angle,air,rolling,ground_mode
0000,0000,0050,03B0,0000,0000,0000,00,0,0,0
0001,0008,0050,03B0,000C,0000,000C,00,0,0,0
0002,0008,0050,03B0,0030,0000,0030,00,0,0,0
```

| Column | Type | Description |
|--------|------|-------------|
| `frame` | int | Trace frame number (0-indexed) |
| `input` | 16-bit | Joypad bitmask (Genesis convention) |
| `x` | 16-bit | Player centre X position |
| `y` | 16-bit | Player centre Y position |
| `x_speed` | signed 16-bit | X velocity |
| `y_speed` | signed 16-bit | Y velocity |
| `g_speed` | signed 16-bit | Ground speed |
| `angle` | byte | Terrain angle (0x00-0xFF) |
| `air` | 0/1 | Airborne flag |
| `rolling` | 0/1 | Rolling flag |
| `ground_mode` | 0-3 | 0=floor, 1=right wall, 2=ceiling, 3=left wall |

The `input` column embeds the joypad state read by the Lua script. This serves as an alignment sanity check — if the BK2-derived input and the trace-embedded input disagree, the BK2 frame offset is wrong.

### `aux_state.jsonl` (auxiliary detail, opened on demand)

One JSON object per line. Only written for frames where something notable happens, plus periodic snapshots.

```json
{"frame":1247,"event":"object_appeared","object_type":"0x2F","x":"0x1A40","y":"0x0280"}
{"frame":1803,"event":"state_snapshot","control_locked":true,"anim_id":3,"rings":12,"invuln_frames":0,"shield":false}
{"frame":2100,"event":"collision_event","type":"hit_enemy","object_type":"0x11","x":"0x0E00","y":"0x0300"}
{"frame":2500,"event":"mode_change","field":"air","from":0,"to":1}
```

Event types:

| Event | Written when |
|-------|-------------|
| `object_appeared` | New object type appears in an active SST slot |
| `state_snapshot` | Every ~60 frames, plus on any mode_change or collision_event frame |
| `collision_event` | Player hits enemy, lands on platform, takes damage |
| `mode_change` | air/ground transition, rolling start/stop, control lock toggle |

JSONL is inherently extensible — new event types or fields on existing events are ignored by older code.

### BK2 Movie File

The BK2 file used to generate the trace lives alongside it in the same directory. The engine already supports BK2 playback. The BK2 is the authoritative input source; `physics.csv` embeds inputs only as a cross-check.

## BizHawk Lua Script

### Scope

A single Lua script targeting Sonic 1 REV01: `s1_trace_recorder.lua`. No multi-game abstraction — when S2/S3K traces are needed, clone and adapt (or refactor if patterns emerge).

### S1 REV01 RAM Addresses

Verified against SPG Sonic 1 REV01 overlay script (`docs/SPGSonic1Rev01Overlay.lua`). All addresses are 68K RAM (`$FFxxxx`); BizHawk `mainmemory` strips the `$FF` prefix.

**System state:**

| Address | Size | Field |
|---------|------|-------|
| `$F600` | byte | `Game_Mode` (0x0C = level gameplay) |
| `$F604` | byte | `Ctrl_1_Locked` (0 = control granted) |
| `$F602` | word | `Ctrl_1` (current input state) |

**Player object (base `$D000`, 64-byte SST slot):**

| Offset | Size | Field | Notes |
|--------|------|-------|-------|
| `+$08` | word | X position (whole) | Centre X |
| `+$0A` | byte | X subpixel | |
| `+$0C` | word | Y position (whole) | Centre Y |
| `+$0E` | byte | Y subpixel | |
| `+$10` | byte+byte | X velocity | `+$10` = whole (signed), `+$11` = subpixel |
| `+$12` | byte+byte | Y velocity | `+$12` = whole (signed), `+$13` = subpixel |
| `+$14` | byte+byte | Ground speed (inertia) | `+$14` = whole (signed), `+$15` = subpixel |
| `+$16` | sbyte | Radius Y | Signed |
| `+$17` | sbyte | Radius X | Signed |
| `+$1A` | byte | Animation frame (display) | |
| `+$1B` | byte | Animation frame (internal) | |
| `+$1C` | byte | Animation ID | |
| `+$1E` | byte | Animation frame timer | |
| `+$22` | byte | Status flags | See below |
| `+$26` | byte | Angle | 0x00-0xFF |
| `+$38` | byte | Stick-to-convex flag | |
| `+$3E` | word | Control lock timer | |

**Status flag bits (`+$22`):**

| Bit | Field |
|-----|-------|
| 0 | Facing left |
| 1 | In air |
| 2 | Spinning/rolling |
| 3 | On object |
| 4 | Roll-jumping |
| 5 | Pushing |
| 6 | Underwater |

**Object table:** `$D000`-`$DFFF`, 64-byte (`$40`) entries. Object ID at offset `+$00` of each slot.

### BizHawk API Usage

- **Memory reads:** `mainmemory.read_u16_be(addr)` / `mainmemory.read_s16_be(addr)` for 16-bit values, `mainmemory.read_u8(addr)` / `mainmemory.read_s8(addr)` for bytes. Genesis 68000 is big-endian. The `mainmemory` domain maps to 68K RAM starting at `$FF0000`, so RAM address `$F600` is read as `mainmemory.read_u8(0xF600)`.
- **Frame callback:** `event.onframeend(callback)` — runs after emulation completes each frame, so all RAM state is settled.
- **Frame count:** `emu.framecount()` — global frame counter for BK2 offset recording.
- **Input:** `joypad.get(1)` — returns a table of button states for controller 1 (e.g. `{Up=true, B=true}`).
- **File I/O:** Standard Lua `io.open()` / `file:write()` / `file:close()`.

### Script Flow

1. Open output files (physics.csv, aux_state.jsonl) via `io.open()`
2. Write CSV header
3. Register `event.onframeend()` callback:
   a. **Pre-start:** Check `Game_Mode == 0x0C` AND `Ctrl_1_Locked == 0`. When both true:
      - Record `bk2_frame_offset = emu.framecount()`
      - Record `start_x`, `start_y`
      - Mark trace as started
   b. **Recording:** Each frame after start:
      - Read all RAM fields via `mainmemory.read_*_be()` functions
      - Convert `joypad.get(1)` button table to bitmask
      - Write CSV row to `physics.csv`
      - Compare status flags against previous frame; write `mode_change` events to aux on change
      - Every 60 frames: write `state_snapshot` to aux with extended state
      - Scan object table for new types in active slots; write `object_appeared` events
      - Increment trace frame counter
4. On script exit / emulator stop:
   - Write `metadata.json` with collected metadata
   - Close files

### Object Tracking

Lightweight: each frame, read the type byte at offset 0 of each 64-byte SST slot. Maintain a set of seen (slot, type) pairs. When a slot holds a new type, log `object_appeared`. No per-frame position tracking of objects.

## Java Engine-Side Components

All classes in `com.openggf.tests.trace`.

### `TraceData`

Reads and holds the contents of a trace directory.

```java
public class TraceData {
    static TraceData load(Path traceDirectory);

    TraceMetadata metadata();
    int frameCount();
    TraceFrame getFrame(int traceFrame);

    // Auxiliary (lazy-loaded, indexed by frame)
    List<TraceEvent> getEventsForFrame(int traceFrame);
    List<TraceEvent> getEventsInRange(int startFrame, int endFrame);
}
```

The entire primary CSV is loaded into memory upfront. At ~100 bytes per frame, even a 73,306-frame full-game trace is ~7MB.

### `TraceFrame` (record)

```java
public record TraceFrame(
    int frame, int input,
    short x, short y,
    short xSpeed, short ySpeed, short gSpeed,
    byte angle, boolean air, boolean rolling, int groundMode
) {}
```

### `TraceMetadata` (record)

```java
public record TraceMetadata(
    String game, String zone, int act,
    int bk2FrameOffset, int traceFrameCount,
    short startX, short startY,
    String recordingDate, String luaScriptVersion,
    String romChecksum, String notes
) {}
```

### `TraceEvent` (sealed interface)

```java
public sealed interface TraceEvent {
    int frame();

    record ObjectAppeared(int frame, String objectType, short x, short y) implements TraceEvent {}
    record StateSnapshot(int frame, Map<String, Object> fields) implements TraceEvent {}
    record CollisionEvent(int frame, String type, String objectType, short x, short y) implements TraceEvent {}
    record ModeChange(int frame, String field, int from, int to) implements TraceEvent {}
}
```

### `TraceBinder`

Comparison engine. Called after each frame by the fixture.

```java
public class TraceBinder {
    TraceBinder(TraceData trace, ToleranceConfig tolerances);

    // Per-frame comparison
    FrameComparison compareFrame(int traceFrame, AbstractPlayableSprite sprite);

    // Input cross-check (BK2 input vs trace-embedded input)
    boolean validateInput(int traceFrame, int bk2Input);

    // After run completes
    DivergenceReport buildReport();
}
```

Input validation is a hard gate: if the BK2 input and trace input disagree for a frame, the test fails immediately with an alignment error.

### `ToleranceConfig`

Configurable thresholds per field. Defaults:

| Field | Warn threshold | Error threshold |
|-------|---------------|-----------------|
| x, y | >= 1 subpixel | >= 256 (1 full pixel) |
| x_speed, y_speed, g_speed | >= 1 subpixel | >= 128 (half pixel/frame) or sign change |
| angle | >= 1 | >= 4 |
| air, rolling, ground_mode | n/a | any mismatch |

Starting points to be tuned as real divergence patterns emerge.

### `DivergenceReport`

Groups consecutive divergent frames by field and severity.

```java
public class DivergenceReport {
    List<DivergenceGroup> errors();
    List<DivergenceGroup> warnings();

    boolean hasErrors();
    boolean hasWarnings();

    // Serialisation for agent consumption
    String toJson();
    String toSummary();

    // Context window: expected vs actual state, N frames either side of a divergence
    String getContextWindow(int centreFrame, int radius);
}
```

`DivergenceGroup`: `(String field, Severity severity, int startFrame, int endFrame, String expectedAtStart, String actualAtStart, boolean cascading)`. The `cascading` flag marks groups likely downstream of an earlier root divergence.

### `AbstractTraceReplayTest`

JUnit base class. Subclasses provide game/zone/act/path; the base class handles level loading, fixture wiring, replay, and assertion. The abstract `game()/zone()/act()` methods drive `@RequiresRom` annotation and level loading setup. On load, the base class validates these match `metadata.json` — a mismatch is a test configuration error.

```java
@RequiresRom(SonicGame.SONIC_1)
public abstract class AbstractTraceReplayTest {
    protected abstract SonicGame game();
    protected abstract int zone();
    protected abstract int act();
    protected abstract Path traceDirectory();

    protected ToleranceConfig tolerances() { return ToleranceConfig.DEFAULT; }

    @Test
    public void replayMatchesTrace() {
        TraceData trace = TraceData.load(traceDirectory());
        // 1. Load level using game/zone/act from metadata
        // 2. Create HeadlessTestFixture with:
        //    .withRecording(traceDir/bk2File) at bk2FrameOffset
        //    .withTrace(traceDir)
        //    .startPosition(trace.metadata().startX(), startY())
        // 3. Step through all frames, TraceBinder compares each
        // 4. Build DivergenceReport
        // 5. Assert: !report.hasErrors()
        // 6. On failure: write report JSON to target/trace-reports/
    }
}
```

Concrete test for GHZ1:

```java
public class TestS1Ghz1TraceReplay extends AbstractTraceReplayTest {
    protected SonicGame game() { return SonicGame.SONIC_1; }
    protected int zone() { return 0; }
    protected int act() { return 0; }
    protected Path traceDirectory() {
        return Path.of("src/test/resources/traces/s1/ghz1_fullrun");
    }
}
```

## Divergence Diagnosis Workflow

When a trace replay test fails:

### 1. Read the Summary

Console output: `"3 errors, 12 warnings. First error: frame 847 -- air flag mismatch (expected=0, actual=1)"`

### 2. Read the Report

`target/trace-reports/s1_ghz1_report.json` contains divergence groups sorted by frame. Focus on the first non-cascading error — this is most likely the root cause.

### 3. Pull Context Window

The report provides expected vs actual state side by side for frames surrounding a divergence:

```
Frame  | Exp X  | Act X  | Exp Y  | Act Y  | Exp Air | Act Air | Exp GSpd | Act GSpd
  837  | 0x0A40 | 0x0A40 | 0x0180 | 0x0180 |       0 |       0 |   0x0380 |   0x0380
  ...
  846  | 0x0B10 | 0x0B10 | 0x0164 | 0x0164 |       0 |       0 |   0x0400 |   0x0400
  847  | 0x0B30 | 0x0B30 | 0x0160 | 0x0162 |       0 |       1 |   0x0400 |   0x0000
  848  | 0x0B50 | 0x0B52 | 0x0160 | 0x0168 |       0 |       1 |   0x0400 |   0x0000
```

### 4. Cross-Reference Auxiliary Data

Check `aux_state.jsonl` for events near the divergent frame — mode changes, object appearances, collision events. Narrows down whether the issue is terrain collision, solid object contact, or physics calculation.

### 5. Investigate Engine Code

With the specific frame, position, and symptom identified:
- Examine terrain data at that level position
- Check collision angle/height at those coordinates
- Trace sensor logic for the exact scenario
- Compare against s1disasm routines

## File & Directory Layout

### Trace Resources (committed to repo)

```
src/test/resources/traces/
  s1/
    ghz1_fullrun/
      metadata.json
      physics.csv
      aux_state.jsonl
      ghz1_playthrough.bk2
```

### Lua Scripts (committed to repo)

```
tools/bizhawk/
  s1_trace_recorder.lua
  README.md
```

The README documents: BizHawk version requirements, Genesis core selection, movie recording setup, how to verify a trace recording.

### Java Source

```
src/test/java/com/openggf/tests/trace/
  TraceData.java
  TraceFrame.java
  TraceMetadata.java
  TraceEvent.java
  TraceBinder.java
  ToleranceConfig.java
  FieldComparison.java
  FrameComparison.java
  DivergenceGroup.java
  DivergenceReport.java
  AbstractTraceReplayTest.java

src/test/java/com/openggf/tests/trace/s1/
  TestS1Ghz1TraceReplay.java
```

### Build Output (gitignored)

```
target/trace-reports/
  s1_ghz1_report.json
  s1_ghz1_context.txt
```

## Initial Scope

- One game: Sonic 1
- One act: GHZ Act 1
- One Lua script: `s1_trace_recorder.lua`
- One concrete test: `TestS1Ghz1TraceReplay`
- Full pipeline end-to-end: record in BizHawk, replay in engine, diagnose divergences

Expansion to more acts, more games follows the same pattern: new trace directory, new one-class test extending `AbstractTraceReplayTest`, new Lua script per game.

## Memory Budget

At ~100 bytes per `TraceFrame`, a 73,306-frame full-game S3K trace is ~7MB — well within JUnit default heap. Aux JSONL is event-driven and negligible. No streaming or pagination needed.
