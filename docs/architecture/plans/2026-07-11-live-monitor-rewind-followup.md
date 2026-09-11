# Live Monitor Rewind Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reproduce the Sonic 2 monitor symptom through normal live rewind boundaries and fix only the first controller, live-manager, or presentation layer proven divergent.

**Architecture:** Use the already-green production object adapter as the state foundation. Test three ordered boundaries: replayable `RewindController` reconstruction, `LiveRewindManager` held-input selection through its owned gameplay context, then newly emitted render output from the restored graph. Stop at the first failure; if all pass, perform and report manual live acceptance without guessing a production change.

**Tech Stack:** Java 21, JUnit 5, Maven, `RewindController`, `LiveRewindManager`, `GameplayModeContext`, `ObjectManager`, Mockito/recording graphics fixtures.

---

### Task 1: Replayable RewindController monitor boundary

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java`
- Modify production only if RED proves it: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Modify if production changes: `CHANGELOG.md`

- [ ] **Step 1: Build a replayable monitor fixture**

Reuse the existing S2 monitor harness/player/touch/snapshot helpers. Define a local `ListInputSource implements InputSource` backed by an immutable `List<Bk2FrameInput>`; `frameCount()` returns list size and `read(frame)` returns `rows.get(frame)`. Rows are: index 0 neutral, index 1 with `ACTION_A` as the test-only replayable break action, and index 2 neutral. Create an `EngineStepper` that reads only its `Bk2FrameInput`; when `p1ActionMask() & InputActionMasks.ACTION_A` is nonzero it locates the live `MonitorObjectInstance` and invokes the real `onTouchResponse` with the rolling player and SPECIAL result, otherwise it advances the object manager one normal frame. Register `objectManager.rewindSnapshottable()` in the production `RewindRegistry` and construct:

```java
RewindController controller = new RewindController(
        registry, new InMemoryKeyframeStore(), inputs, stepper, 60);
```

The stepper must locate the monitor afresh after every restore; it must not retain the pre-restore object reference.

- [ ] **Step 2: Write the controller boundary assertions**

Assert initial `currentFrame()==0`; call `controller.step()` (which reads row `currentFrame + 1`) and assert frame 1 plus broken collision/state/contents; call `controller.step()` and assert frame 2. Call `controller.seekTo(1)` and assert frame 1 is reconstructed broken from keyframe 0 plus row 1. Then `controller.seekTo(0)` and assert intact collision/state, no break children, and object snapshot equivalence.

- [ ] **Step 3: Verify RED or negative-control GREEN**

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions#rewindControllerReplaysBrokenFrameThenRestoresIntactMonitor" test
```

If RED, save the exact first divergent `RewindSnapshotDiff`, implement the smallest universal `RewindController` fix, update `CHANGELOG.md`, and rerun GREEN. If GREEN, make no controller production change and proceed.

- [ ] **Step 4: Add >60-frame keyframe-crossing coverage**

Create rows 0..62: rows 1..60 neutral, row 61 contains `ACTION_A`, row 62 neutral. With keyframe interval 60, call `step()` through frame 62, `seekTo(61)` and assert broken, then call `stepBackward()` to frame 60 and again to frame 59, asserting both are intact. This explicitly crosses the retained frame-60 keyframe boundary.

- [ ] **Step 5: Commit the green controller slice**

Use `test(rewind): cover monitor controller rewind boundary` if test-only, or `fix(rewind): restore monitor across controller rewind` with changelog if production changed. Include all required trailers.

### Task 2: LiveRewindManager held-input boundary

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java` or create `src/test/java/com/openggf/game/rewind/TestLiveRewindMonitorState.java`
- Modify production only if RED proves it: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Modify if production changes: `CHANGELOG.md`

- [ ] **Step 1: Build the production gameplay-context fixture**

Create exact class `TestLiveRewindMonitorState`. Use `TestEnvironment.activeGameplayMode()` and its real `GameplayModeContext`, load S2 EHZ1 with valid `s2.gen`, and use the context's normal object adapter/rewind registry. Before installing live rewind, materialize the first monitor, position Sonic immediately above it, and configure every deterministic pre-break field (centre position, Roll animation, rolling status, downward velocity) so these values are present in the controller's initial snapshot. Enable `SonicConfiguration.LIVE_REWIND_ENABLED` and instantiate `LiveRewindManager`. With the rewind key up, call `handleRealtimeRewindInput(GameMode.LEVEL, false, input)` once; this public entry invokes `ensureInstalled()` without stepping backward. Assert it returns false and `context.getRewindController().currentFrame()==0`. Only after this frame-0 capture, execute the first intact neutral host gameplay frame through `HeadlessTestRunner`/`LevelFrameStep`, then call `recordExternalFrame(GameMode.LEVEL, false, input)` to append aligned row 1 and advance the cursor to frame 1. Record that cursor as `intactFrame`. Do not inject a prebuilt controller with reflection.

- [ ] **Step 2: Seed intact and broken live history through recorded input**

After installation, execute additional neutral host-step→record pairs while the monitor stays intact. For the break row, refresh/apply the real rolling collision input, run the canonical host frame so production touch response breaks the monitor, then call `recordExternalFrame`; record the controller cursor as `brokenFrame`. Add one later neutral host-step→record pair. For every row the order is host gameplay step then record call. Assert `brokenFrame > intactFrame`, and prove the live broken precondition before rewind. Because all Sonic setup existed before the initial controller snapshot, replay of the recorded rows starts from identical pre-break state. If EHZ1 cannot be loaded or the production collision cannot be made replayable in this fixture, STOP Task 2 with `BLOCKED` and the exact fixture gap; do not add an injection/factory seam or mutate the monitor out-of-band.

- [ ] **Step 3: Hold rewind and assert cursor/state before release**

Press the configured live rewind key on `InputHandler`. Assert each `handleRealtimeRewindInput(GameMode.LEVEL, false, input)` call returns `true`, `manager.effectIntensity() > 0`, and `context.getRewindController().currentFrame()` decreases until it crosses before the break. Assert the active monitor is intact with no break children before key release.

- [ ] **Step 4: Verify RED or negative-control GREEN**

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindMonitorState" test
```

If RED with green Task 1, fix only held-input orchestration/context cursor timing, update changelog, and rerun GREEN. If GREEN, make no manager production change and proceed.

- [ ] **Step 5: Commit the green live-manager slice**

Use `test(rewind): cover live monitor rewind state` if test-only, or a focused `fix(rewind): ...` subject with changelog. Include trailers.

### Task 3: Presentation render boundary

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/TestSonic2LiveObjectRewindRegressions.java` or create `src/test/java/com/openggf/game/rewind/TestLiveRewindMonitorPresentation.java`
- Modify production only if RED proves it: the first proven owner under `src/main/java/com/openggf/level/objects/` or `src/main/java/com/openggf/graphics/`
- Modify if production changes: `CHANGELOG.md`

- [ ] **Step 1: Add a recording monitor renderer**

Create exact class `TestLiveRewindMonitorPresentation` by extending the Task 2 production `GameplayModeContext` fixture. Before level/object materialization and before live rewind installation, use the context's supported test construction seam to provide an `ObjectRenderManager` whose monitor, monitor-content, and explosion renderer lookups return three separately mocked/recording `PatternSpriteRenderer` instances. The same context-owned `ObjectManager`, registry, and live controller from Task 2 must own and restore the graph; do not construct a standalone `StubObjectServices` graph or replace services after capture. Use a headless/recording `GraphicsManager`. If the production context exposes no supported render-manager construction seam, STOP Task 3 as `BLOCKED` and specify that fixture seam separately; do not use reflection or invent it inside this task.

- [ ] **Step 2: Prove broken and intact render oracles**

Before rewind, iterate every production object bucket from `RenderPriority.MIN` through `RenderPriority.MAX` and call `drawUnifiedBucketWithPriority` for each; verify the monitor renderer receives literal frame `0x0B` at monitor coordinates and that the separate content/explosion recorders receive calls. After the live-manager rewind from Task 2, clear recorders, render every bucket again, verify the monitor recorder receives a frame other than `0x0B` at restored coordinates, and verifyNoInteractions on content/explosion recorders. If Task 2 is BLOCKED, Task 3 is also BLOCKED rather than substituting direct adapter restore.

- [ ] **Step 3: Verify RED or negative-control GREEN**

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindMonitorPresentation" test
```

If RED with green state layers, minimally invalidate/rebuild only the proven bucket/cache/presentation owner after restore. If GREEN, make no production change.

- [ ] **Step 4: Commit the green presentation slice**

Use a test-only or fix subject matching whether production changed, with changelog/trailers as required.

### Task 4: Verification, manual acceptance, and final reviews

- [ ] **Step 1: Run focused monitor/live/controller suites**

```powershell
mvn "-Dtest=com.openggf.game.rewind.TestSonic2LiveObjectRewindRegressions,com.openggf.game.rewind.TestLiveRewindMonitorState,com.openggf.game.rewind.TestLiveRewindMonitorPresentation,com.openggf.game.rewind.TestLiveRewindManager*,com.openggf.game.sonic2.objects.TestMonitorObjectInstance,com.openggf.game.sonic2.objects.badniks.TestMasherBadnikInstance,com.openggf.tests.trace.s2.TestS2Ehz1BuzzerSpawnRegression,com.openggf.game.rewind.TestRewindController,com.openggf.tests.graphics.RenderOrderTest" test
```

- [ ] **Step 2: Run rewind guards and all rewind tests**

```powershell
mvn "-Dtest=com.openggf.game.rewind.coverage.TestRewindCoverageGuard,com.openggf.game.rewind.coverage.TestStaticStateRewindCoverageGuard,com.openggf.game.rewind.TestRewindArchitectureGuard" test
mvn "-Dtest=com.openggf.game.rewind.TestEveryObjectRewindRoundTrip,com.openggf.game.rewind.TestRewindInPlaceObjectRestore" test
mvn "-Dtest=*Rewind*" "-DfailIfNoTests=false" test
```

- [ ] **Step 3: Run best-effort trace sweep**

```powershell
mvn "-Dtest=*TraceReplay" "-DfailIfNoTests=false" test
```

Report existing S3K failures separately; never alter trace data.

- [ ] **Step 4: Perform manual S2 acceptance**

Run `mvn package`, then `java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar` with `config.yaml` live rewind enabled and valid `s2.gen`. Enter EHZ1, use the first standard-route monitor, accumulate 60 intact frames, break it, hold rewind before the break, and verify intact casing/no children while held and after release. Repeat a short rewind and a >60-frame crossing. If acceptance is performed, record centre coordinates from code/trace diagnostics (do not quote the debug HUD top-left `Pos:` values) and frame counters in `docs/rewind/live-monitor-acceptance.md`, then stage/commit that report with the appropriate documentation trailers. If automated boundaries are green but manual acceptance fails, capture the first divergent live state/presentation evidence before any further change.

- [ ] **Step 5: Final delegated reviews**

Dispatch fresh spec-compliance and code-quality reviewers over the follow-up commit range. Fix every Critical/Important finding and repeat until GREEN. Confirm worktree clean and `git diff --check`.

- [ ] **Step 6: Commit review fixes and re-verify**

Commit any review-driven changes with a focused subject and complete trailers. Rerun every focused command affected by those changes plus the guard command before claiming completion.
