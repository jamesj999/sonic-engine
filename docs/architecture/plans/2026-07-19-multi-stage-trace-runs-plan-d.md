# Multi-Stage Trace Runs — Plan (d): Visual Run Chaining + Launch-Config Generalization

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Surface trace runs in the test-mode picker and play them visually as one continuous session (level → bonus → level) with per-segment comparator/HUD swapping, plus the launch-config generalization (spec addition #5) — per plan (d) of `docs/architecture/designs/2026-07-18-multi-stage-trace-runs-design.md`.

**Architecture (verified 2026-07-19 exploration):** Plan (c) already landed the peeks, the BONUS_STAGE playback bridge, and the proven segment-handoff mechanics (comparator swap + cursor re-seek at `bk2_frame_offset`, transition frames uncompared because fade/TITLE_CARD freeze the cursor). The VISUAL run session asserts nothing — so it needs NO BoundaryProbe/peek/window machinery: segment handoffs are driven purely by GameLoop mode flips (leave LEVEL → detach comparator; settle into the next segment's mode → re-seek `playback.startSession(movie, offset)` + attach the next comparator). Single continuous playback with one comparator is NOT honest (a segment's comparator would run off its trace end during interiors) — per-segment swap is mandatory. The reusable walker core moves from src/test to src/main (`com.openggf.trace.replay.runs`), mirroring how `TraceReplayDriver`/`LiveTraceComparator` live in src/main precisely so tests and the visual launcher share them.

**Tech Stack:** Java 21 + JUnit 5 (Jupiter only). No lua changes.

## Global Constraints

- **Spec:** plan (d) scope = catalog run discovery + visual run branch + addition #5. Stage-interior schemas (blue spheres, S1, slots depth, S2 retrofit) are later plans.
- Comparison-only invariant; no zone/route/frame carve-outs (mode-flip-driven handoffs are engine-state-driven, not zone-keyed).
- JUnit 5 only; commit policy as plans (a)-(c) (trailer block; src/main feat → `Changelog: updated` + CHANGELOG.md staged CRLF-verified; stage exact paths; every commit ends with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` and `Claude-Session: https://claude.ai/code/session_01LPPPMPSUQBgYpxpA82bad5`).
- Recordings still absent: the visual run branch is validated by construction + unit/scan tests on the synthetic fixture; live visual validation activates when Component-4 recordings land.
- Guard awareness: moving walker classes into src/main trace code may trip `TestBuildToolingGuard`/`TestArchitecturalSourceGuard` — register per convention with justification if they fire; never weaken. The `traceReplayCodeDoesNotWriteRecordedStateBackIntoEngine` guard scans test trace dirs — the moved-code tests keep comparison-only shape.

---

### Task 1: Move the walker core to src/main

**Files:**
- Create: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java` (moved from `src/test/java/com/openggf/tests/trace/runs/TraceRunReplayWalker.java` — `git mv` semantics: delete old, add new, package rename only)
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestTraceRunReplayWalkerControlFlow.java` (imports)
- Modify: `src/test/java/com/openggf/tests/trace/runs/TestS3kBonusRoundTripChain.java` (imports)

**Interfaces:**
- Consumes: the existing walker source verbatim (SegmentPlan, plan, withinBoundaryWindow, BOUNDARY_WINDOW_FRAMES, EngineHooks, BoundaryProbe, awaitBoundary, BoundaryObservation).
- Produces: identical public surface under package `com.openggf.trace.replay.runs`. NO behavior change — this is a verbatim relocation with a package line + imports adjusted. The class javadoc gains one line noting it serves both the headless chain test and the visual run session.

- [ ] **Step 1:** Move the file (create new with the new package line, delete old), update the two tests' imports. Compile: `mvn "-Dtest=com.openggf.tests.trace.runs.TestTraceRunReplayWalkerControlFlow" test` — 6/6 green. Chain test still SKIPs.
- [ ] **Step 2:** Run the guard trio: `mvn "-Dtest=com.openggf.tests.TestBuildToolingGuard,com.openggf.tests.TestTraceReplayInvariantGuard,com.openggf.tests.TestArchitecturalSourceGuard" test`. If a guard fires on the moved file, register per its convention with a justification citing this plan; report what you registered.
- [ ] **Step 3:** CHANGELOG line `- Trace framework: run-replay walker promoted to src/main for visual run sessions.` + commit:

```bash
git add src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java src/test/java/com/openggf/tests/trace/runs/ CHANGELOG.md
git rm src/test/java/com/openggf/tests/trace/runs/TraceRunReplayWalker.java 2>/dev/null || true
git commit -m "feat(trace): promote run-replay walker to src/main" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

(If `git add` of the deleted path already stages the removal, skip the `git rm`. Verify `git status` shows exactly: new src/main file, deleted src/test file, 2 modified tests, CHANGELOG.)

---

### Task 2: Catalog run discovery + picker entry

**Files:**
- Modify: `src/main/java/com/openggf/trace/catalog/TraceCatalog.java`
- Modify: `src/main/java/com/openggf/trace/catalog/TraceEntry.java`
- Modify: `src/main/java/com/openggf/testmode/TestModeTracePicker.java` (only if rendering needs a change beyond displayLabel — read it first)
- Test: `src/test/java/com/openggf/trace/catalog/TestTraceCatalogRunDiscovery.java` (new)

**Interfaces:**
- Consumes: `TraceRunManifest.load/validate` (src/main since plan (a)); the existing `scan` walk (`Files.walk(root, 2)`, `TraceCatalog.java:53`), `tryLoad` validation (`:74-99`), `TraceEntry` record (`TraceEntry.java:12-22`), `displayLabel()` (`:32-39`). Callers of `scan`: `MasterTitleScreen.java:324`, `TraceCaptureTool.java:535,556`.
- Produces: `TraceEntry` gains two trailing nullable components: `Path runDir` and `TraceRunManifest runManifest` (null for ordinary traces); a static factory `TraceEntry.forRun(Path runDir, TraceRunManifest manifest, Path bk2Path)` populating gameId from the manifest, **zone/act converted from segment 0 exactly as `tryLoad` does (`romZoneToProgressionIndex(game, zoneId)` + `max(0, act-1)` — raw manifest values would boot the wrong zone/act via `driver.start`)**, frameCount = sum of segment `trace_frame_count`, bk2StartOffset = first segment's offset, **AND the fields existing consumers dereference (round-1 review — these NPE otherwise): `team` derived by reading segment 0's `metadata.json` (Jackson `TraceMetadata` load, the same loader `tryLoad` uses) and `metadata` set to that segment-0 metadata** (it is real data and makes `displayLabel`'s existing first-line deref safe); `boolean isRun()` accessor; `displayLabel()` handles the run case FIRST (before any metadata-profile deref): `"RUN " + runId + " (" + segments.size() + " segments)"`. **`TraceSessionLauncher.launch` gains its `if (entry.isRun())` dispatch BEFORE the `entry.metadata().traceProfile()` deref at `:182`.** Verify `TestModeTracePicker.formatTeam` renders the derived team without NPE (it reads `e.team().mainCharacter()` at `:214-215`). `TraceCatalog.scan` additionally globs `<root>/<game>/runs/*/run_manifest.json`, loads+validates each, resolves the shared bk2 via the same `_movies` convention (reuse/mirror the existing per-entry bk2 resolution — read how `tryLoad` resolves `source_bk2`), and appends run entries; a run that fails to load, validate, or resolve its bk2 is SKIPPED, never fatal — **catch `IOException` AND `IllegalStateException` (or `RuntimeException`) around the per-run load+validate**: `TraceRunManifest.validate` throws `IllegalStateException`, which `scan`'s existing `IOException`-only catch would let escape and crash the picker (the `invalidRunIsSkippedNotFatal` test exercises exactly this).

- [ ] **Step 1: Failing test** (synthetic fixture — note it lives under `synthetic/` which scan EXCLUDES, so the test uses a @TempDir catalog root):

```java
package com.openggf.trace.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceCatalogRunDiscovery {

    @Test
    void discoversRunManifestAsSingleEntry(@TempDir Path root) throws Exception {
        // Copy the committed synthetic run fixture into <root>/s3k/runs/run_aiz_gumball_3seg
        Path src = Path.of("src", "test", "resources", "traces", "synthetic", "run_aiz_gumball_3seg");
        Path runDir = root.resolve("s3k").resolve("runs").resolve("run_aiz_gumball_3seg");
        Files.createDirectories(runDir.getParent());
        copyRecursively(src, runDir);
        // The manifest's source_bk2 must resolve: place a dummy bk2 at <root>/s3k/_movies/synthetic.bk2
        Path movies = root.resolve("s3k").resolve("_movies");
        Files.createDirectories(movies);
        Files.write(movies.resolve("synthetic.bk2"), new byte[] {0});

        List<TraceEntry> entries = TraceCatalog.scan(root);
        List<TraceEntry> runs = entries.stream().filter(TraceEntry::isRun).toList();
        assertEquals(1, runs.size());
        TraceEntry run = runs.get(0);
        assertEquals("s3k", run.gameId());
        assertEquals(6, run.frameCount()); // 3 segments x 2 frames
        assertEquals(500, run.bk2StartOffset());
        assertTrue(run.displayLabel().contains("run_aiz_gumball_3seg"));
        assertNotNull(run.runManifest());
    }

    @Test
    void invalidRunIsSkippedNotFatal(@TempDir Path root) throws Exception {
        Path badRun = root.resolve("s3k").resolve("runs").resolve("broken");
        Files.createDirectories(badRun);
        Files.writeString(badRun.resolve("run_manifest.json"), "{\"run_schema\": 99}");
        List<TraceEntry> entries = TraceCatalog.scan(root);
        assertTrue(entries.stream().noneMatch(TraceEntry::isRun));
    }
    // copyRecursively: small Files.walk copy helper, implement inline.
}
```

(Adjust `TraceCatalog.scan`'s real signature — read it; if it takes a config/string, adapt the test to the real entry point. If `scan` is not static, construct the catalog the way `MasterTitleScreen` does.)

- [ ] **Step 2:** COMPILE/assert-fail, implement per Produces, tests green. Existing `TraceCatalog` scan tests must stay green; run whatever catalog tests exist (`grep -l TraceCatalog src/test/java -r`).
- [ ] **Step 3:** Check `TestModeTracePicker` renders the new entries acceptably (it keys off `displayLabel()`/`dir()` — with `dir()` = runDir for run entries this should Just Work; if `dir().getFileName()` collides or NPEs for runs, patch minimally and note it). Update the three positional `TraceEntry` constructor call sites if any exist outside the catalog (grep `new TraceEntry(`).
- [ ] **Step 4:** CHANGELOG line + commit (feat, src/main touched, Changelog updated; stage the exact files).

---

### Task 3: Visual run session branch in `TraceSessionLauncher`

**Files:**
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify: `src/main/java/com/openggf/GameLoop.java` (ONE new all-mode hook call — see below)
- Test: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java` (new; unit-level on the segment-advance state machine, no ROM)

**LOAD-BEARING (round-1 review):** the launcher's existing `tick()` fires ONLY from `updateLevelMode` (`GameLoop.java:1445`) — driven from there, the advancer goes blind the instant the mode leaves LEVEL and the chain never advances. Task 3 therefore adds ONE unconditional per-frame hook in GameLoop after the mode dispatch (~`:1016`, before `inputHandler.update()`): `TraceSessionLauncher`'s active session gets a `runAdvanceTickIfActive(currentGameMode, cursorFrame)` call — access the session the same way the existing SS hooks at `:1114-1128` reach the launcher (read them and mirror the pattern). The re-seek (`startSession`) + comparator swap execute inside that BETWEEN-FRAMES hook, never inside `afterFrameAdvanced` (mirrors why the headless chain re-seeks from its external loop). With no active run session the hook is a no-op — normal play unchanged.

**Interfaces:**
- Consumes: `TraceEntry.isRun()/runManifest()/runDir()` (Task 2), `TraceRunReplayWalker.plan(...)` (Task 1, src/main now), the existing level-branch machinery (`launchGameByEntry`, `finishLaunchAfterGameBootstrap` internals at `TraceSessionLauncher.java:286-330`: `LiveFixture`, `TraceReplayDriver`, comparator, `TraceCameraFocusController`, `TraceHudOverlay`, ghost hook), `PlaybackDebugManager.startSession(movie, offset)/setFrameObserver`, GameLoop's current-mode accessor, session end via `startFadeOut()` (`:559`).
- Produces:
  - A run dispatch in `launch(...)` (parallel to the SS dispatch at `:182`, and BEFORE the profile deref): `if (entry.isRun()) { launchRun(entry, loop, configSnapshot); return; }` — pass the same collaborators `launchSpecialStage` receives (config snapshot for the failure-path restore).
  - `launchRun` calls `TraceReplaySessionBootstrap.prepareConfiguration(seg0Trace, seg0Meta)` first (as the level branch does at `:191`), boots the game exactly like the level branch using `entry.zone()/entry.act()` (already engine-converted by `forRun`), then `finishRunLaunch` mirrors `finishLaunchAfterGameBootstrap` (minus held-rewind, per above) and additionally stores: the ordered `List<SegmentPlan>`, `currentSegmentIndex = 0`, and per-segment `TraceData` (already loaded by `plan(...)`).
  - `END_OF_RUN` derives from the advancer's own inputs: `cursorFrame >= lastSegment.bk2FrameOffset() + lastSegment.traceFrameCount()` while COMPARING the last segment — no extra completion signal is threaded in.
  - **A small package-private state machine `RunSegmentAdvancer`** (nested or sibling class — keep it independently unit-testable): inputs each frame are `(GameMode mode, int cursorFrame)`; states `COMPARING(segment k)` → mode leaves the segment's expected mode → `IN_TRANSITION(k→k+1)` (comparator detached) → mode settles into segment k+1's expected mode (`level`→LEVEL, `bonus_stage`→BONUS_STAGE, `special_stage`→SPECIAL_STAGE) → emits an `AdvanceAction(reseekOffset, nextSegmentIndex)` → `COMPARING(k+1)`; after the LAST segment's trace frames are exhausted (comparator complete) → emits `END_OF_RUN`. The launcher applies `AdvanceAction` by calling `playback.startSession(movie, reseekOffset)` and swapping the comparator/ghost to the next segment (build a fresh `LiveTraceComparator` per segment at initialCursor=0, same construction as the level branch uses); `END_OF_RUN` → `startFadeOut()` (session-owned end, mirroring the SS branch's cursor-driven fade at `:402-405`).
  - The new all-mode GameLoop hook (Files note above) drives the advancer for run sessions; the level branch's LEVEL-only `tick()` is NOT sufficient for advancing.
  - **`tick()` MUST be gated off for run sessions (round-2 blocker):** the completion-hold in `tick()` (`TraceSessionLauncher:410-425`, called at `GameLoop:1443-1445` whenever a comparator exists) arms a 60-frame fade countdown the moment ANY comparator completes — for a run that is the FIRST segment boundary, ending the whole run ~1s after segment 0. Add an early return in `tick()` when the session is a run session; the advancer alone owns run end via `END_OF_RUN`. (SS sessions escape only because their comparator is null — runs are the exception.)
  - **Held rewind: run sessions do NOT install it.** `finishRunLaunch` mirrors `finishLaunchAfterGameBootstrap` EXCEPT `installTraceRewindController`/`syncVisualRewindCursors` (`:564-589`) — the stepper/base-frames capture segment 0 and would silently rewind against the wrong segment after a re-seek. Leave `rewindController` null (then `GameLoop:834-835` engagement no-ops). Per-segment rewind for runs is a documented follow-up, not silently-wrong behavior.
  - **Segment advance rebinds EVERYTHING that captured the old comparator:** build the fresh `LiveTraceComparator` via the chain-test pattern (`new LiveTraceComparator(trace, ToleranceConfig.DEFAULT, 0, loop::getMainPlayableSprite)` — `TestS3kBonusRoundTripChain:192-193`; the level branch itself takes `driver.comparator()` and never constructs one directly), then: update the session's comparator field (covers ghost rendering, which reads the field), REBUILD `TraceHudOverlay` and `TraceCameraFocusController` against the new comparator and re-register them (`loop.setTraceCameraFocusController(...)`, overlay re-wire — mirror `finishLaunchAfterGameBootstrap:309-322`), and re-attach the playback frame observer to the new comparator. Stale overlay/camera bindings show the previous segment's counts — wrong output, silent.
  - Expected-mode mapping lives on the advancer as a pure function of `Segment.kind()` — data-driven, no zone checks.

- [ ] **Step 1: Failing unit test** for `RunSegmentAdvancer` alone (no engine): construct with the synthetic fixture's 3 segment plans (level/bonus_stage/level, offsets 500/1900/2900); feed mode sequences and assert: stays COMPARING in LEVEL; transitions on LEVEL→TITLE_CARD; emits AdvanceAction(1900, 1) when BONUS_STAGE reached; transitions again on BONUS_STAGE→TITLE_CARD; emits AdvanceAction(2900, 2) when LEVEL reached; emits END_OF_RUN when told segment 2's comparator completed. Also: a mode flicker DURING a transition (TITLE_CARD→TITLE_CARD) emits nothing; reaching the wrong mode (e.g. SPECIAL_STAGE when bonus expected) keeps waiting (no emit) — the advancer never throws.
- [ ] **Step 2:** COMPILE/assert-fail → implement advancer + launcher wiring → unit test green. Full-compile the tree.
- [ ] **Step 3:** CHANGELOG line + commit (feat, src/main).

---

### Task 4: Launch-config generalization (spec addition #5)

**Files:**
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java` (`prepareSpecialStageConfiguration`, `:245-253`)
- Test: extend an existing launcher/config test or add `src/test/java/com/openggf/TestTraceSessionLauncherSsConfig.java` (unit; no ROM)

**Interfaces:**
- Consumes: `TraceReplaySessionBootstrap.prepareConfiguration`'s S3K branch shape (`TraceReplaySessionBootstrap.java:134-137`: `requiresFreshLevelLoadForTraceReplay(trace) && "s3k".equals(meta.game())` → `S3K_SKIP_INTROS=false`).
- Produces (RESCOPED, rounds 1-2 — the SS launch path holds a `SpecialStageTraceData`, NOT a level `TraceData`, so `requiresFreshLevelLoadForTraceReplay` cannot be threaded honestly today): `prepareSpecialStageConfiguration` becomes per-game aware via a **unit-tested static helper** `applyPerGameSpecialStageConfig(config, TraceMetadata meta, boolean freshLoadSignal)` — meta is needed for the shared team settings; `freshLoadSignal` is the EXPLICIT seam for the S3K skip-intros branch (`"s3k".equals(meta.game()) && freshLoadSignal` → `S3K_SKIP_INTROS=false`). The live S2 SS call site passes `freshLoadSignal=false` (no honest signal exists today — the blue-spheres plan supplies the real one and owns wiring it; pointing comment). S1 documented no-flag.

- [ ] **Step 1:** Failing unit test, three cases against the static helper: (a) S2 metadata + `freshLoadSignal=false` → config identical to pre-change behavior; (b) S3K metadata + `freshLoadSignal=false` (today's live reality) → `S3K_SKIP_INTROS` NOT set; (c) S3K metadata + `freshLoadSignal=true` (the future seam) → `S3K_SKIP_INTROS=false` set. (Construct configs via the launcher's real config service seam — read how existing launcher tests do it; if none exist, test the static helper directly.)
- [ ] **Step 2:** Implement; green; existing SS launch tests (`TestGameLoopSpecialStageSkipGate` etc.) still green.
- [ ] **Step 3:** CHANGELOG line + commit.

---

### Task 5: Gate + docs

- [ ] **Step 1:** Full suite (detached + log monitor): no NEW failures vs baseline (29F/6E; expected skips unchanged at +4). Watch the guard trio re Task 1's move.
- [ ] **Step 2:** `docs/status/trace-frontier-log.md` entry: plan-(d) visual run chaining landed (catalog run entries, launcher run branch with mode-flip-driven segment advancer, walker promoted to src/main, SS launch-config per-game aware); live visual validation pending the same two recordings.
- [ ] **Step 3:** Commit docs. Merge-time README reminder stands.

## Plan-level notes

- The visual branch deliberately has NO probe/peek/window machinery — assertions are the headless chain's job; the visual session just plays honestly (per-segment comparator swap is the one non-negotiable, since a single comparator would compare garbage during interiors).
- SS-segment visual playback (blue spheres/S1) activates in later plans; the advancer's `special_stage` expected-mode mapping is included now (cheap, symmetric, data-driven).
- Deferred carry-forwards NOT in this plan: stage_exit await step cap + chain-test 3-segment hardcode (headless-chain items, revisit when recordings land).
