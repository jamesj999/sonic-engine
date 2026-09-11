# Trace Test Mode — Visual Trace Replay Inside The Live Engine

**Date:** 2026-04-23
**Status:** Design approved, pending implementation plan
**Owner:** Farrell

## 1. Motivation

Trace replay tests under `src/test/java/com/openggf/tests/trace/**` already drive
the engine frame-by-frame from BizHawk `.bk2` recordings and compare against
recorded ROM physics in `physics.csv`. They run **headless** — no window, no
rendering, no audio — so a failure report reads as numeric divergences without
any way to actually *watch* where and how Sonic goes off course.

This design adds a **Trace Test Mode** to the live engine: a config-gated screen
that sits on top of the cross-game master title, lists every trace in
`src/test/resources/traces/**`, and plays a selected trace back inside the
full graphical engine while overlaying live error/warning/lag counters and a
rolling mismatch log.

Goal: flip a config flag, launch the jar from the repo root, pick a trace,
watch it run. Close the engine when done. No extra CLI flags, no ad-hoc test
harness, no code changes needed per trace.

Non-goal: shipping this in a distributed jar. This is a development-time tool
running from the project working directory.

## 2. User-facing flow

1. User sets `TEST_MODE_ENABLED=true` in `config.json` (or the user-local
   `config.local.json`).
2. User runs the engine as normal (`java -jar ...`).
3. The master title screen starts in **Trace Test Mode** instead of its normal
   game-selection flow. Pressing `Esc` from the picker returns to the normal
   master-title game selection within the same session.
4. The picker lists every trace directory under
   `src/test/resources/traces/`, grouped by game (S1 → S2 → S3K), then sorted
   by zone/act, then by directory name. Up/Down navigates; PgUp/PgDn jumps
   between game groups; Enter launches the highlighted trace.
5. A context-info panel at the bottom of the picker shows the currently
   highlighted trace's metadata: game, zone, act, frame count, BK2 offset,
   pre-trace oscillation frames, team composition, start position.
6. On Enter, the engine switches to the trace's game module, loads the level,
   applies the same mid-level state bootstrap used by
   `AbstractTraceReplayTest` (pre-trace oscillation, pre-trace object SST
   snapshots, start position), and starts playback driven by the BK2.
7. During playback, the screen shows normal gameplay **plus** a fixed overlay
   in the bottom-right showing:
   - red **ERRORS** counter (hard divergences)
   - orange **WARN** counter (within-tolerance but notable divergences)
   - grey **LAG** counter (ROM lag frames that were skipped from the trace
     replay — i.e. frames classified as `VBLANK_ONLY` by `TraceReplayBootstrap`)
   - an input visualiser showing the BK2 frame's inputs as
     `A B C U D L R S` with a `.` wherever a button is released
   - the last 5 distinct mismatch entries
8. Session end (two paths, both ending in a fade-to-black → picker):
   - **Natural completion.** On BK2 exhaustion the overlay shows
     `TRACE COMPLETE`, holds for ~1 second (parameterised via a code
     constant `TraceSessionLauncher.COMPLETION_HOLD_SECONDS`, default
     `1.0`), then starts a `FadeManager.startFadeToBlack(...)` using
     the default fade duration. When the fade completes, the launcher
     tears the session down (`TestEnvironment.resetAll()`) and returns
     to the picker.
   - **Manual `Esc`.** Skips the hold and begins the same fade-to-black
     immediately. A second `Esc` during the fade has no additional
     effect — once a fade is scheduled, the teardown runs on its
     completion callback either way.
9. Standard `PlaybackDebugManager` hotkeys (play/pause, step forward/back,
   jump, rate) continue to work during the session — those already exist
   and don't need changes.

## 3. Architecture

```
config.TEST_MODE_ENABLED=true
          │
          ▼
   MasterTitleScreen
   (enters trace-picker branch when flag on)
          │
          ▼
   TestModeTracePicker ──►  TraceSessionLauncher
        (list + info)             │
                                  ▼
                      (module swap + level load
                       + TraceReplayBootstrap
                       + PlaybackDebugManager.start)
                                  │
                                  ▼
                      LiveTraceComparator (per-frame, per-field)
                                  │
                                  ▼
                      TraceHudOverlay (counters + input + log)
                                  │
                     Esc returns to picker
                     (TestEnvironment.resetAll)
```

Five new runtime pieces:

- **`TraceCatalog`** — scans the trace resource root, returns an immutable
  `List<TraceEntry>`. Called once when entering Trace Test Mode, and on
  explicit refresh.
- **`TestModeTracePicker`** — new screen owning navigation state, input
  handling, and its own bitmap-text render call. Lives under
  `com.openggf.testmode`.
- **`TraceSessionLauncher`** — orchestrates the actual launch: module
  detection, level load, `TraceReplayBootstrap` application, BK2 wire-up to
  `PlaybackDebugManager`.
- **`LiveTraceComparator`** — engine-side per-frame comparator that mirrors
  the test-side `TraceBinder`. Emits divergences into an observable buffer.
- **`TraceHudOverlay`** — renders counters, input visualiser, and mismatch
  log. Uses the existing `DebugRenderer` bitmap font path.

## 4. Trace catalog

### 4.1 Resource layout

The catalog reads directly from `src/test/resources/traces/` relative to the
JVM working directory. The existing directory shape is already uniform and
requires no changes:

```
src/test/resources/traces/
  s1/
    ghz1_fullrun/
      metadata.json
      physics.csv
      aux_state.jsonl       (optional)
      <name>.bk2
    mz1_fullrun/...
    credits_00_ghz1/...
    ...
  s2/
    ehz1_fullrun/...
  s3k/
    aiz1_to_hcz_fullrun/
      metadata.json
      physics.csv
      aux_state.jsonl
      s3-aiz1-2-sonictails.bk2
    cnz/...
  synthetic/              ← fixture-only; filtered out
```

### 4.2 Validity rule

A directory is a valid `TraceEntry` iff **all** of the following hold:

- contains `metadata.json` and `physics.csv`
- contains **exactly one** `*.bk2` file
- `metadata.json` parses as a `TraceMetadata` with a non-null `game` field in
  `{"s1", "s2", "s3k"}`

The `synthetic/` subtree is filtered out because those traces are only used
for unit tests over the binder infrastructure and are not intended to be
played back visually. Any other directory missing the above requirements is
silently skipped and logged at `FINE`.

### 4.3 Entry shape

```java
public record TraceEntry(
    Path dir,
    String gameId,              // "s1" | "s2" | "s3k" (from metadata)
    int zone, int act,
    int frameCount,             // physics.csv row count
    int bk2StartOffset,         // metadata.bk2FrameOffset()
    int preTraceOscFrames,      // metadata.preTraceOscillationFrames()
    SelectedTeam team,          // from metadata.main + recordedSidekicks
    Path bk2Path,
    TraceMetadata metadata
) {}
```

`SelectedTeam` already exists
(`com.openggf.game.save.SelectedTeam(mainCharacter, sidekicks)`). The catalog
builds it from `metadata.recordedMainCharacter()` +
`metadata.recordedSidekicks()`. **Important:** use the null-safe
`recordedMainCharacter()` accessor, not the raw `mainCharacter()` JSON
field — the newer `characters[]` metadata format leaves the raw field
null, and `SelectedTeam` rejects null at construction. When
`recordedMainCharacter()` itself returns null (legacy trace with no
recorded team), default to `"sonic"` for display.

### 4.4 Sorting

Entries are sorted by: `(gameOrder, zone, act, dirName)` where `gameOrder` is
`s1 < s2 < s3k`. Grouping in the picker uses the `gameId` prefix, not the
filesystem path.

## 5. Test Mode master title integration

### 5.1 Config

New enum value in `SonicConfiguration`:

```
TEST_MODE_ENABLED        // boolean, default false
TRACE_CATALOG_DIR        // string, default "src/test/resources/traces"
```

Both added to `config.json` with the defaults above. `TRACE_CATALOG_DIR` is
resolved against `user.dir` the same way `PLAYBACK_MOVIE_PATH` already is
(`PlaybackDebugManager.resolveAgainstWorkingDir`).

### 5.2 MasterTitleScreen branch

`MasterTitleScreen.update(InputHandler)` checks
`configService.getBoolean(TEST_MODE_ENABLED)` on entry. When set, it hands
off to a `TestModeTracePicker` instance instead of running its normal
game-selection state machine.

**State handling.** The full `MasterTitleScreen.State` enum is
`{INACTIVE, FADE_IN, ACTIVE, ERROR_DISPLAY, CONFIRMING, EXITING}`. The
picker is only reachable from `ACTIVE` — the other states are either
transitional (`FADE_IN`, `EXITING`) or dialog-ish (`ERROR_DISPLAY`,
`CONFIRMING`). In test mode:

- `FADE_IN` proceeds as usual (fade-in animation plays).
- On entering `ACTIVE` for the first time, if `TEST_MODE_ENABLED` is true
  the picker takes over the `ACTIVE` render/update path; otherwise the
  normal game-select layout runs.
- `ERROR_DISPLAY` and `CONFIRMING` retain their existing behaviour. The
  picker is suspended while those are up and resumes when the state
  returns to `ACTIVE`.
- `EXITING` proceeds as usual once a trace is chosen and the launcher
  hands off to `GameLoop.launchGameByEntry`.

When `Esc` is pressed inside the picker, the picker returns a
`BACK_TO_MASTER` result and the master title falls through to its normal
game-select layout in `ACTIVE`. This avoids needing a separate `GameMode`
entry: the picker is a sibling render path inside `MASTER_TITLE_SCREEN`.

`GameMode` itself is **not** changed. A dedicated mode isn't needed because
the picker only runs before any ROM is loaded, and the playback session
itself uses the normal `GameMode.LEVEL` once launched.

### 5.3 Picker layout

Rendered with `DebugRenderer`'s bitmap text at `320×224` virtual resolution,
scaled by the existing upscaler:

```
┌────────────────────────────── TRACE TEST MODE ──────────────────────────────┐
│                                                                              │
│   SONIC 1                                                                    │
│      ghz1 fullrun                  [6521 frames, bk2 offset 1148]            │
│    ► mz1 fullrun                   [9204 frames, bk2 offset 982 ]            │
│      credits 00 ghz1               [ 900 frames, bk2 offset  74 ]            │
│      ...                                                                     │
│                                                                              │
│   SONIC 2                                                                    │
│      ehz1 fullrun                                                            │
│                                                                              │
│   SONIC 3&K                                                                  │
│      aiz1 to hcz fullrun                                                     │
│      cnz                                                                     │
│                                                                              │
├──── SELECTED: s1/mz1_fullrun ───────────────────────────────────────────────┤
│  Game: Sonic 1   Zone: MZ  Act: 1   Frames: 9204   BK2 offset: 982           │
│  Team: Sonic (solo)         Pre-trace osc: 12      Start: (0x0050, 0x03B0)   │
│  BK2: s1-mz1.bk2                                                             │
│                                                                              │
│  ↑/↓ navigate     PgUp/PgDn jump group     ENTER play     ESC back           │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 5.4 Controls

| Key | Action |
|-----|--------|
| `Up`/`Down` | Move cursor one entry |
| `PgUp`/`PgDn` | Move cursor to first entry of prev/next game group |
| `Home`/`End` | First / last entry |
| `Enter` | Launch highlighted entry |
| `Esc` | Return to normal master title |

All key bindings are hard-coded in the picker — they are dev-tool defaults
and don't need to go through `SonicConfiguration`.

## 6. Session launch

`TraceSessionLauncher.launch(TraceEntry)` performs, in order:

1. Resolve the entry's `gameId` to a `MasterTitleScreen.GameEntry`
   (`SONIC_1` / `SONIC_2` / `SONIC_3K`).
2. Reuse the master-title game-bootstrap pathway. Two small access-level
   changes are required in `GameLoop`:
   - Promote `exitMasterTitleScreen(MasterTitleScreen)` from `private` to
     package-private, **or** introduce a package-private wrapper
     `launchGameByEntry(GameEntry)` that internally synthesises a forced
     selection and calls `exitMasterTitleScreen`. The spec picks the
     wrapper approach to keep the existing private routine untouched.
   - `MasterTitleScreen` exposes a package-private `selectEntry(GameEntry)`
     that mirrors its Enter-confirmation behaviour (seeds
     `isGameSelected()` to true for the chosen entry).
   - `TraceSessionLauncher` lives in the same package as `GameLoop` (i.e.
     `com.openggf`) so it can invoke `launchGameByEntry`.

   The result: the launcher picks the `GameEntry`, the existing
   master-title post-selection code runs as if the user had pressed Enter,
   and the right ROM and module load with zero duplicated logic.
3. Reuse `AbstractTraceReplayTest`'s bootstrap flow — extracted into a new
   `TraceReplaySessionBootstrap` helper so it can be called from both the
   test and the engine:
   - Apply recorded team config (`MAIN_CHARACTER_CODE`,
     `SIDEKICK_CHARACTER_CODE`) from metadata.
   - For S3K, clear `S3K_SKIP_INTROS` if
     `TraceReplayBootstrap.requiresFreshLevelLoadForTraceReplay(trace)`.
   - Load the level (the engine's normal level-load path).
   - Initialise vblank counter via `ObjectManager.initVblaCounter`.
   - Pre-advance `OscillationManager.update(-n)` for
     `metadata.preTraceOscillationFrames()`.
   - Apply pre-trace object snapshots via
     `TraceReplayBootstrap.applyPreTraceState(trace, fixture)`, where
     "fixture" now is a thin interface extracted from `HeadlessTestFixture`
     (`sprite()`, `runtime()`) so `GameRuntime` can back it at runtime.
   - Apply replay start state via
     `TraceReplayBootstrap.applyReplayStartStateForTraceReplay(trace, fixture)`.
4. Start playback programmatically via a new public
   `PlaybackDebugManager.startSession(Bk2Movie movie, int startOffsetIndex)`
   (see 6.2 below). This replaces the hotkey-only `loadFromConfig()` path.
5. Activate `LiveTraceComparator` with the loaded `TraceData` and the
   appropriate `ToleranceConfig` (default — same as most replay tests).
   The comparator owns the trace context used for lag-phase classification
   (see 6.3) and is registered with `PlaybackDebugManager` before playback
   starts so phase gating is live from frame zero.
6. Register `TraceHudOverlay` with the debug-overlay pipeline.

The launcher also installs a shutdown callback. Both paths out of a
playback session (natural completion or `Esc`) go through the same
fade-to-black → `TestEnvironment.resetAll()` → picker sequence (see
9.2). `Esc` simply skips the completion hold; the fade duration itself
is always `FadeManager`'s default.

### 6.1 Extracted bootstrap helper

Currently, the bootstrap logic above is partly in `AbstractTraceReplayTest`
(lines ~96–170) and partly in `HeadlessTestFixture`. To avoid duplicating it
in the launcher, extract a new pure class:

```java
// src/main/java/com/openggf/trace/replay/TraceReplaySessionBootstrap.java
public final class TraceReplaySessionBootstrap {
    public static void prepareLevel(TraceData trace, TraceMetadata meta);
    public static void applyBootstrap(TraceData trace, TraceReplayFixture fixture);
}
```

`TraceReplayFixture` is a new narrow interface that both
`HeadlessTestFixture` and the live launcher implement:

```java
public interface TraceReplayFixture {
    AbstractPlayableSprite sprite();
    GameRuntime runtime();
    int stepFrameFromRecording();     // runs one tick using BK2 input, returns the mask
    int skipFrameFromRecording();     // advances BK2, does NOT run gameplay, returns mask
    void advanceRecordingCursor(int frameCount);
}
```

The three playback-driving methods are required — `TraceReplayBootstrap`'s
existing call sites (`applyReplayStartStateForTraceReplay`,
`replayLegacyFramesToSeedIndex`, etc.) already invoke all of them on its
fixture arg. The test class keeps orchestrating JUnit/assertions; the
bootstrap helper owns the ordering that is identical in both paths.

**Live-launcher implementation.** The launcher's `TraceReplayFixture` impl
delegates the three playback methods to `PlaybackDebugManager`:
- `stepFrameFromRecording()` reads the current `Bk2FrameInput`, feeds it
  into the input bridge, advances the cursor, returns the mask.
- `skipFrameFromRecording()` advances the cursor without binding input or
  running a gameplay tick (the live engine's lag-gate from 6.3 makes this
  a no-op-on-gameplay path).
- `advanceRecordingCursor(int)` pumps the cursor by N frames without
  stepping gameplay.

These behaviours already exist on `HeadlessTestRunner`; factoring them
behind the interface is the move that makes the live launcher possible.

### 6.2 Programmatic playback entrypoint

`PlaybackDebugManager` currently loads a movie only from
`SonicConfiguration.PLAYBACK_MOVIE_PATH` via a private `loadFromConfig()`
called from a hotkey. Two new public methods are added:

```java
public synchronized void startSession(Bk2Movie movie, int startOffsetIndex);
public synchronized void endSession();
```

`startSession` installs the movie, creates the
`PlaybackTimelineController`, resets the cursor to `startOffsetIndex`, sets
`enabled=true` and `playing=true`. `endSession` tears the above down and
clears state, mirroring what pressing the toggle key twice already does.
Neither conflicts with the existing hotkey path — if a user manually
toggles playback mid-session, `endSession` is idempotent.

### 6.3 Lag-frame physics gating

The replay tests keep engine and CSV aligned by calling
`fixture.skipFrameFromRecording()` (advances BK2, does *not* step gameplay)
whenever `TraceReplayBootstrap.phaseForReplay(...) == VBLANK_ONLY`. The
live engine's `GameLoop` has no equivalent — it runs gameplay every frame.
Without a gate the engine over-advances relative to the CSV and every
frame past the first lag frame registers as a divergence.

Gate design:

- `PlaybackDebugManager` gains an optional observer interface:
  ```java
  public interface PlaybackFrameObserver {
      /** Called before the engine's gameplay tick. Return true to skip
       *  that tick entirely (BK2 cursor still advances). */
      boolean shouldSkipGameplayTick(Bk2FrameInput frame);
      /** Called after a gameplay tick (or skip) so observers can record
       *  what happened and update their own counters. */
      void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped);
  }
  public synchronized void setFrameObserver(PlaybackFrameObserver observer);
  ```
- `LiveTraceComparator` implements this interface. Its
  `shouldSkipGameplayTick` returns true when
  `phaseForReplay(trace, prevTraceFrame, currentTraceFrame) == VBLANK_ONLY`
  for the current BK2 index.
- `PlaybackDebugManager` exposes a new query
  `shouldSkipCurrentGameplayTick()` that reads the current `Bk2FrameInput`
  and, if an observer is set, delegates to
  `observer.shouldSkipGameplayTick(frame)`. With no observer it returns
  false. `GameLoop.update()` consults this before running the gameplay
  update; when true, the frame is a no-op for gameplay (rendering and
  input handling still happen so the screen remains responsive) and the
  BK2 cursor still advances via `onLevelFrameAdvanced()`.
- `afterFrameAdvanced` is where `LiveTraceComparator` increments counters
  (`laggedFrames` on skip, physics comparison on non-skip).

No observer → no behaviour change, so the existing non-trace playback flow
(BK2 hotkey playback against the live title screen) is unaffected.

## 7. Code reorganization — moving trace-infra from test to main

`LiveTraceComparator` and `TraceReplaySessionBootstrap` need access to
`TraceData`, `TraceBinder`, `TraceFrame`, `ToleranceConfig`,
`DivergenceGroup`, `Severity`, etc., which today live in
`src/test/java/com/openggf/tests/trace/`. A surgical move is part of this
work:

**Moves to `src/main/java/com/openggf/trace/`:**

- `TraceData`, `TraceFrame`, `TraceCharacterState`, `TraceMetadata`,
  `TraceEvent`, `TraceEventFormatter`, `TraceExecutionPhase`,
  `TraceExecutionModel`, `TraceHistoryHydration`
- `TraceBinder`, `ToleranceConfig`
- `FieldComparison`, `FrameComparison`, `DivergenceGroup`, `DivergenceReport`,
  `Severity`
- `TraceObjectSnapshotBinder`, `TraceReplayBootstrap`
- `EngineDiagnostics`, `EngineNearbyObject`, `EngineNearbyObjectFormatter`,
  `TouchResponseDebugHitFormatter`

**Stays in `src/test/`:**

- `AbstractTraceReplayTest`, `AbstractCreditsDemoTraceReplayTest`
- All `TestS{1,2,3k}*TraceReplay.java` and regression classes
- `Test*` classes for the moved infrastructure (they keep their tests in
  `src/test/` but import from the new main-side package)
- `Debug*` probes

This move is mechanical: change package statements from
`com.openggf.tests.trace` to `com.openggf.trace`, update imports across
`src/test/`. No behavioural change. Tests must stay green after the move.

(The only runtime cost is a few hundred K of classes added to the production
jar. Acceptable.)

## 8. Live comparator

`LiveTraceComparator` is an engine-side wrapper that consumes engine state
each frame and feeds a `TraceBinder`. It has no JUnit dependency.

```java
public final class LiveTraceComparator implements PlaybackDebugManager.PlaybackFrameObserver {
    LiveTraceComparator(TraceData trace, ToleranceConfig tolerances);

    // PlaybackFrameObserver:
    @Override boolean shouldSkipGameplayTick(Bk2FrameInput frame);
    @Override void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped);

    int errorCount();     // Severity.ERROR divergences
    int warningCount();   // Severity.WARNING divergences
    int laggedFrames();   // phase == VBLANK_ONLY frames skipped this session
    boolean isComplete(); // ran off end of trace
    List<MismatchEntry> recentMismatches(int max);
    int recentInputMask();
    boolean recentStartPressed();
}

public record MismatchEntry(int frame, String field, String romValue,
                            String engValue, String delta, Severity severity,
                            int repeatCount) {}
```

### 8.1 Frame-drive semantics

The comparator is driven through the `PlaybackFrameObserver` interface
introduced in 6.3:

- `shouldSkipGameplayTick(Bk2FrameInput frame)` — classifies the current
  BK2 index via
  `TraceReplayBootstrap.phaseForReplay(trace, previousTraceFrame, currentTraceFrame)`.
  Returns true (skip gameplay tick) when phase is `VBLANK_ONLY`, false
  (run gameplay tick) when phase is `GAMEPLAY`.
- `afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped)` — after
  the engine has either run or skipped the gameplay tick:
  - `wasSkipped == true` → increment `laggedFrames`.
  - `wasSkipped == false` → sample the active primary player sprite held
    by `GameRuntime` (the same one `HeadlessTestFixture` exposes via
    `sprite()`) and hand its state plus the expected `TraceFrame` to
    `TraceBinder`. Any `FieldComparison` whose status is `ERROR` or
    `WARNING` is pushed into the mismatch ring buffer and increments the
    respective counter.
- The observer also tracks the current BK2 cursor so
  `recentInputMask()` / `recentStartPressed()` return what the HUD needs.

### 8.2 Mismatch ring buffer

- Capacity: 5.
- Eviction: newest-wins FIFO.
- Deduplication: if the new mismatch has the same `(field, romValue,
  engValue)` as the head of the buffer, increment its `repeatCount` instead
  of pushing a new entry. Any other field combination flushes the repeat
  counter. Keeps a single spammy field (e.g. a stuck subpixel divergence)
  from evicting every other error from the log.

### 8.3 S3K elastic window

S3K replays use `S3kElasticWindowController` to align engine frames to trace
frames across checkpoints. Full elastic-window re-alignment is out of scope
for v1.

However, a naive frame-N-to-`startIndex + N` comparison produces a flood of
false positives during pre-gameplay frames (intro cutscene, title card,
etc.) where the ROM and engine are intentionally out of step. The existing
`AbstractTraceReplayTest.replayS3kTrace` gates all binder calls on
`S3kElasticWindowController.isStrictComparisonEnabled()` — comparison is
suppressed until the first checkpoint aligns.

V1 picks the minimum viable subset of that gating:

- The comparator consumes `TraceEvent.Checkpoint` events via
  `S3kReplayCheckpointDetector` (the existing probe-driven detector).
- Per-frame comparison is **suppressed** for S3K traces until the first
  `gameplay_start` checkpoint is detected on the engine side.
- Once past the first gameplay checkpoint, comparison resumes against
  `startIndex + N` without elastic re-alignment. Downstream checkpoint
  drift is reflected as real divergences — which is exactly the signal a
  visual tool wants to surface.
- The grey LAG counter and error/warning counters remain live throughout
  (including the suppressed window), so the user can see the engine is
  running even while the comparator is intentionally quiet.

The hook point is a new `boolean comparisonSuppressed()` method on the
comparator. When true, `afterFrameAdvanced` only increments the lag
counter (if `wasSkipped`) and does not call into `TraceBinder`.

## 9. HUD overlay

`TraceHudOverlay` is registered through the existing `DebugOverlayManager`
so the normal debug-render pass picks it up.

### 9.1 Layout (bottom-right, ~120×60 px at 0.5× font scale)

```
 ERRORS   12     (red,   00FF0000)
 WARN      3     (orange,00FFA500)
 LAG      47     (grey,  00808080)

 ABC..L.S         (green letters where pressed, dot where released)

 Last mismatches:
  f 04A1  xSpeed rom=+0180 eng=+0178   (Δ8)      ×3
  f 04A0  rolling rom=1 eng=0
  f 049E  y      rom=03E4 eng=03E5    (Δ1)
  f 0488  angle  rom=E0 eng=DF
  f 0470  gSpeed rom=+0100 eng=+00F8
```

- Counter colours are fixed RGB values tuned to remain readable on common
  level backgrounds.
- Input row is a single packed 8-character string — each position
  holds the corresponding button's letter when pressed or `.` when
  released. No static header row and no inter-letter spacing, per
  the dev-tool "compact" brief.
- `A`/`B`/`C` come from `Bk2FrameInput.p1ActionMask()` (bits `0x01`/`0x02`/
  `0x04`). `U`/`D`/`L`/`R` come from `p1InputMask()`. `S` comes from
  `p1StartPressed()`.
- The mismatch log fades older entries slightly so the newest is visually
  loudest.
- Whole HUD is drawn at scale `0.5` against the engine's virtual
  `320×224` coordinate space so it fits into the bottom-right
  without overlapping gameplay.

### 9.2 Completion state & auto-return

When `LiveTraceComparator.isComplete()` first goes true:

1. The overlay appends a `TRACE COMPLETE` line above the mismatch log.
2. `TraceSessionLauncher` starts a hold timer for
   `COMPLETION_HOLD_SECONDS` (a private `static final double` in
   `TraceSessionLauncher`, default `1.0`). During the hold the HUD stays
   fully drawn so the user can read the final counters.
3. When the hold elapses, the launcher calls
   `GameServices.fade().startFadeToBlack(onComplete)` — using
   `FadeManager`'s default fade duration (unparameterised). The HUD
   counters, input visualiser, and `TRACE COMPLETE` line stay drawn on
   top of the fade at full brightness so the final counts remain
   readable until the screen is fully black.
4. The fade's `onComplete` callback runs the same teardown path as
   manual `Esc` (`TestEnvironment.resetAll()` + return to picker).
5. `Esc` pressed during the hold skips the hold and starts the fade
   immediately — teardown still runs on the fade's completion callback,
   not instantly. `Esc` pressed during the fade has no additional
   effect; the fade runs to completion and teardown follows. This keeps
   both end paths visually identical.

`FadeManager` composites correctly with the normal level render pipeline
and works across S1/S2/S3K without per-game code.

## 10. Controls during playback

All existing `PlaybackDebugManager` hotkeys keep working
(play/pause/step/jump/rate), courtesy of the manager already being wired
into `GameLoop`. The only new binding is:

| Key | Action |
|-----|--------|
| `Esc` | End the trace session: skip the completion hold, fade to black (default `FadeManager` duration), reset runtime, return to picker |

## 11. Configuration keys

```json
{
  "TEST_MODE_ENABLED": false,
  "TRACE_CATALOG_DIR": "src/test/resources/traces"
}
```

Both default to the values above. `TEST_MODE_ENABLED=true` is the only
required flip.

No per-trace settings — tolerances come from the trace's own metadata or
the binder defaults, exactly like the tests.

## 12. Testing plan

- **Unit tests for `TraceCatalog`** (JUnit 5): scan a synthetic tmp
  directory with valid and invalid trace shapes; verify filter rules and
  sort order.
- **Unit tests for `LiveTraceComparator`**: feed a hand-crafted
  `TraceData` + mock sprite states; verify error/warning/lag counters and
  mismatch-buffer deduplication.
- **Unit tests for the ring buffer** covering FIFO eviction, `repeatCount`
  aggregation, and flush-on-different-field.
- **Move-migration tests**: after the package move in section 7, the
  existing trace-replay tests must still pass with only package/import
  changes (no logic edits).
- **Smoke test**: run engine from IDE with `TEST_MODE_ENABLED=true`,
  verify the picker lists every trace dir, launch `s1/ghz1_fullrun` and
  watch it run to completion with counters updating. Manual; no automation.

No new integration tests are added for the visual screen itself — it's a
dev tool and its correctness is observed directly.

## 13. Out of scope

- Shipping Trace Test Mode in distributed jars (no resource-bundling plan).
- Editor/designer features (spawn-state editing, ROM breakpoint overlays).
- Sidekick / secondary-character per-field divergence display (counters
  still include sidekick errors/warnings; log-display prioritises primary
  sprite).
- Per-trace tolerance overrides in the catalog UI.
- Recording new traces from inside Trace Test Mode.
- S3K elastic checkpoint re-alignment inside the live comparator (noted in
  8.3). Re-visit if v1 is too noisy to be useful for S3K traces.
- Replacing the existing `PlaybackDebugManager` hotkey set (all kept).

## 14. Open questions

None blocking. Implementation can proceed on the design above.
