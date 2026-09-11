# WFZ Ending Runtime Art and Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cache the runtime Tornado PLC safely so ObjB2 `$5C` renders its rocket pod/flame, then produce aligned headless evidence for the corrected WFZ ending.

**Architecture:** `LevelManager` remains the owner of the object virtual-pattern base and reserved atlas range. Object-art providers expose a side-effect-free regular-pattern count for capacity preflight; successful Sonic 2 runtime PLC intake calls a `LevelManager` refresh that caches only after validation. The S2 PLC rewind epoch is restored while immutable renderer allocations remain monotonic and idempotent. Final verification produces a canonical FFV1 capture and a separately labelled cadence-safe review copy.

**Tech Stack:** Java 21, Sonic 2 PLC parser/standalone art, PatternAtlas, JUnit 5, Maven, TraceCaptureTool, ffmpeg/ffprobe, stable-retro reference PNGs.

**Design:** `docs/architecture/designs/2026-07-16-wfz-ending-space-background-design.md` §§7.5-7.7.

---

### Task 1: Add side-effect-free object-pattern capacity preflight

**Files:**
- Modify: `src/main/java/com/openggf/game/ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectRenderManager.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/level/render/PatternSpriteRenderer.java`
- Test: `src/test/java/com/openggf/level/TestObjectArtPatternCapacity.java`
- Test: `src/test/java/com/openggf/graphics/TestPatternAtlasRangeRegistration.java`

- [ ] **Step 1: Write failing pattern-count, fixed-range, and preflight tests**

```java
@Test
void levelRegistersOneFullObjectGovernanceRange() {
    fixture.initializeObjectArt(providerWithPatternCount(12));
    assertEquals(List.of(new PatternRange(PatternAtlasRange.OBJECTS.base(),
                    PatternAtlasRange.OBJECTS.size(), "Objects")),
            fixture.atlas().registeredRangesForTesting());
}

@Test
void refreshRejectsOverflowBeforeAnyRendererCaches() {
    RecordingProvider provider = providerWithPatternCount(PatternAtlasRange.OBJECTS.size() + 1);
    assertThrows(IllegalStateException.class, fixture::refreshObjectArtPatterns);
    assertEquals(0, provider.ensureCalls());
}

@Test
void successfulRefreshEndMatchesPreflightEnd() {
    RecordingProvider provider = providerWithPatternCount(24);
    int end = fixture.refreshObjectArtPatterns();
    assertEquals(PatternAtlasRange.OBJECTS.base() + 24, end);
    assertEquals(1, provider.ensureCalls());
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.level.TestObjectArtPatternCapacity,com.openggf.graphics.TestPatternAtlasRangeRegistration" test
```

Expected: compilation failure for the count and refresh APIs.

- [ ] **Step 3: Add the count contract and implementations**

Add a compatibility-safe default to `ObjectArtProvider` so third-party/test providers still
compile but fail explicitly if used with the LevelManager cache preflight:

```java
/** Number of regular object patterns cached contiguously from the supplied object base. */
default int getRegularPatternCount() {
    throw new UnsupportedOperationException("Regular object pattern count is not exposed");
}
```

Implement it in all three providers with the same side-effect-free calculation:

```java
@Override
public int getRegularPatternCount() {
    return sheetOrder.stream().mapToInt(sheet -> sheet.getPatterns().length).sum();
}
```

Dedicated namespaces such as the S2 results renderer are excluded because
`ensurePatternsCached(...)` does not advance the regular returned end for them.

- [ ] **Step 4: Centralize fixed-range registration and preflight in LevelManager**

Add:

```java
public int refreshObjectArtPatterns() {
    if (objectRenderManager == null) {
        throw new IllegalStateException("Object render manager is not initialized");
    }
    int count = objectRenderManager.getRegularPatternCount();
    int prospectiveEnd = Math.addExact(OBJECT_PATTERN_BASE, count);
    if (prospectiveEnd > PatternAtlasRange.OBJECTS.endExclusive()) {
        throw new IllegalStateException("Object patterns exceed reserved atlas range: " + count);
    }
    int actualEnd = objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
    if (actualEnd != prospectiveEnd) {
        throw new IllegalStateException("Object pattern preflight/cache mismatch: "
                + prospectiveEnd + " != " + actualEnd);
    }
    return actualEnd;
}
```

During initial object-art setup call `patternAtlas.registerRange(PatternAtlasRange.OBJECTS)` once,
then call this method. Remove the current used-prefix registration. Expose a read-only registered-range
test seam if needed; do not weaken overlap validation. Add a read-only
`PatternSpriteRenderer#getPatternBase()` accessor so idempotence tests can assert stable allocation
without reflection.

- [ ] **Step 5: Run cross-game object-art and atlas tests**

```powershell
mvn -o "-Dtest=com.openggf.level.TestObjectArtPatternCapacity,com.openggf.graphics.TestPatternAtlasRangeRegistration,com.openggf.game.sonic1.TestSonic1ObjectArtProviderMZ,com.openggf.game.sonic3k.TestSonic3kObjectArtProvider,com.openggf.tests.TestSczObjectArtPalette" test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit Task 1**

```text
fix(render): preflight runtime object pattern capacity

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 2: Refresh successful Sonic 2 runtime PLC art and restore its epoch

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/events/Sonic2ZoneEvents.java`
- Modify: `src/test/java/com/openggf/game/sonic2/TestSonic2PlcArtRewindSnapshot.java`
- Create: `src/test/java/com/openggf/game/sonic2/TestSonic2RuntimePlcRendererRefresh.java`

- [ ] **Step 1: Write failing production-path and rewind tests**

```java
@Test
void successfulRuntimePlcMakesNewRendererReadyInSameFrame() {
    fixture.loadWfzInitialArt();
    assertNull(fixture.provider().getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER));

    fixture.requestThroughZoneEvents(Sonic2Constants.PLC_TORNADO);

    PatternSpriteRenderer renderer = fixture.provider()
            .getRenderer(Sonic2ObjectArtKeys.TORNADO_THRUSTER);
    assertNotNull(renderer);
    assertTrue(renderer.isReady());
    assertEquals(1, fixture.levelManager().refreshCallCount());
}

@Test
void rewindAndReplayRestoresEpochAndReusesRendererAllocation() {
    PlcProgressSnapshot before = fixture.provider().capture();
    fixture.requestThroughZoneEvents(Sonic2Constants.PLC_TORNADO);
    PatternSpriteRenderer first = fixture.thrusterRenderer();
    int firstBase = first.getPatternBase();

    fixture.provider().restore(before);
    assertEquals(before.loadEpoch(), fixture.provider().capture().loadEpoch());
    fixture.requestThroughZoneEvents(Sonic2Constants.PLC_TORNADO);

    assertSame(first, fixture.thrusterRenderer());
    assertEquals(firstBase, fixture.thrusterRenderer().getPatternBase());
}
```

- [ ] **Step 2: Run the focused tests and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.TestSonic2RuntimePlcRendererRefresh,com.openggf.game.sonic2.TestSonic2PlcArtRewindSnapshot" test
```

Expected: renderer remains unready and the existing `restoreIsNoOp` test conflicts with epoch restoration.

- [ ] **Step 3: Return whether a PLC added sheets**

Change `Sonic2ObjectArtProvider.requestPlc(int)` to return `boolean`. Record the sheet count before
and after `loadPlcEntries`; increment `loadEpoch` exactly once for every successful ROM PLC request,
and return true only when at least one sheet was newly registered:

```java
public boolean requestPlc(int plcId) throws IOException {
    ensureArtLoader();
    int before = sheets.size();
    loadPlcEntries(GameServices.rom().getRom(), plcId);
    loadEpoch++;
    return sheets.size() != before;
}
```

Repeated requests remain identity-preserving because `loadPlcEntries` skips existing keys.

- [ ] **Step 4: Refresh from the production runtime path and restore epoch**

In `Sonic2ZoneEvents.requestSonic2Plc(...)`, after the provider request returns true, call
`levelManager.refreshObjectArtPatterns()`. Keep absent runtime/level behavior as an immediate return
and keep the existing non-fatal exception logging; do not add a pending queue.

Replace the S2 restore no-op with:

```java
@Override
public void restore(PlcProgressSnapshot snap) {
    loadEpoch = snap.loadEpoch();
}
```

Immutable sheets/renderers remain loaded across rewind; the replayed PLC advances the restored epoch
but does not create a second renderer.

- [ ] **Step 5: Run runtime PLC, rewind, and other S2 PLC regressions**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.TestSonic2RuntimePlcRendererRefresh,com.openggf.game.sonic2.TestSonic2PlcArtRewindSnapshot,com.openggf.game.sonic2.TestSonic2PlcParser" test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit Task 2**

```text
fix(s2): cache runtime PLC object renderers

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 3: Verify the live ObjB2 `$5C` rocket composite

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic2/objects/TestTornadoObjectInstance.java`
- Create: `src/test/java/com/openggf/game/sonic2/objects/TestWfzTornadoThrusterRendering.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write a failing live-child render test**

```java
@Test
void runtimePlcRendersRocketBodyAndAlternatingFlameAtRomOffset() {
    fixture.requestTornadoPlc();
    TornadoObjectInstance parent = fixture.spawnWfzEndingTornado(0x3192, 0x0472);
    TornadoObjectInstance child = fixture.findSubtype(0x5C);
    child.setRoutineSecondaryForTest(2);

    fixture.stepObjectFrame();
    List<GLCommand> bodyAndLongFlame = fixture.render(child);
    fixture.stepObjectFrame();
    List<GLCommand> bodyAndShortFlame = fixture.render(child);

    assertEquals(0x3186, child.getX());
    assertEquals(0x049A, child.getY());
    assertMappingPieces(bodyAndLongFlame, 3);
    assertMappingPieces(bodyAndShortFlame, 3);
    assertNotEquals(commandBounds(bodyAndLongFlame), commandBounds(bodyAndShortFlame));
}
```

The fixture must use the real `Sonic2ObjectArtProvider`, production PLC request path, and
`ObjectRenderManager`; it may substitute a recording graphics manager to avoid an OpenGL context.

- [ ] **Step 2: Run the focused test and confirm RED before Task 2, GREEN after it**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.objects.TestWfzTornadoThrusterRendering,com.openggf.game.sonic2.objects.TestTornadoObjectInstance" test
```

Expected after Task 2: all selected tests pass, proving body and flame commands are emitted rather
than merely proving that subtype `$5C` exists.

- [ ] **Step 3: Run focused WFZ object and rewind suites**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.objects.TestWfzTornadoThrusterRendering,com.openggf.game.sonic2.objects.TestTornadoObjectInstance,com.openggf.game.rewind.TestS2TornadoGraphRewind,com.openggf.game.sonic2.TestSonic2RuntimePlcRendererRefresh" test
```

Expected: all selected tests pass.

- [ ] **Step 4: Commit Task 3**

If Task 3 requires only tests, use:

```text
test(s2): cover the WFZ Tornado rocket composite

Changelog: n/a: test-only rendered PLC regression
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 4: Full verification and headless evidence capture

**Files:**
- Modify only if verification moves documented behavior: `CHANGELOG.md`
- Output (ignored): `target/trace-videos/wfz-final/`
- Reference (existing ignored): `target/wfz-visual-analysis/retro-trace-*.png`

- [ ] **Step 1: Run all focused tests from both plans**

```powershell
mvn -o "-Dtest=com.openggf.level.TestIncrementalBgTilemapWindow,com.openggf.level.TestPersistentBgNametable,com.openggf.level.rewind.TestPersistentBgNametableRewind,com.openggf.game.sonic2.scroll.TestSwScrlWfz,com.openggf.game.sonic2.scroll.TestWfzPersistentPlaneBIntegration,com.openggf.level.TestObjectArtPatternCapacity,com.openggf.game.sonic2.TestSonic2RuntimePlcRendererRefresh,com.openggf.game.sonic2.objects.TestWfzTornadoThrusterRendering,com.openggf.game.sonic2.objects.TestWFZShipFireObjectInstance,com.openggf.game.sonic2.objects.bosses.TestS2WfzBossLaserWall,com.openggf.game.sonic2.objects.bosses.TestS2WfzBossGraphRewind" test
```

Expected: all selected tests pass.

- [ ] **Step 2: Run trace gates and the full test suite**

Discover the root-level Sonic 2 ROM and pass its actual path:

```powershell
$rom = (Get-ChildItem -Path . -File -Filter *.gen | Where-Object {
    $sha = (Get-FileHash -Algorithm SHA1 -LiteralPath $_.FullName).Hash
    $sha -eq '8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9'
}).FullName
mvn -Ptrace-replay "-Ds2.rom.path=$rom" "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2DezEndingLevelSelectTraceReplay" test
mvn "-Ds2.rom.path=$rom" test
```

Expected: both trace gates pass; full suite has zero failures/errors. Restore any test-generated
tracked reports without discarding unrelated user changes.

- [ ] **Step 3: Produce the canonical 60-fps lossless capture**

Use `TraceCaptureTool` with the checked-in WFZ trace, no ghosts, native 320x224 output, FFV1 video,
and the default headless 48-kHz FLAC audio path:

```powershell
$outDir = 'target/trace-videos/wfz-final'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
mvn exec:java "-Dexec.mainClass=com.openggf.tools.TraceCaptureTool" `
  "-Dexec.args=--trace src/test/resources/traces/s2/wfz --out-dir $outDir --scale 1 --fps 60 --codec ffv1 --no-ghosts"
$captured = Get-ChildItem -LiteralPath $outDir -Filter 'capture-wfz-*.mkv' |
  Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
Move-Item -LiteralPath $captured.FullName -Destination (Join-Path $outDir 'capture-wfz-final.mkv')
```

Save under:

```text
target/trace-videos/wfz-final/capture-wfz-final.mkv
```

Expected `ffprobe` properties: 320x224, 60/1 fps, 16,426 rendered frames, approximately 273.77 seconds.

- [ ] **Step 4: Extract deterministic acceptance frames**

```powershell
$video = 'target/trace-videos/wfz-final/capture-wfz-final.mkv'
foreach ($frame in 14760,14820,14920,15000,15840,15850,15860,15870) {
    ffmpeg -hide_banner -loglevel error -i $video -vf "select='eq(n,$frame)'" -frames:v 1 `
        "target/trace-videos/wfz-final/frame-$frame.png"
}
```

Compare against `target/wfz-visual-analysis/retro-trace-*.png` and assert:

- frame 14760 contains the gray Tornado rocket pod and left-facing orange/white flame;
- uncovered background is blue at 14820, 14920, 15000, and 15840;
- first horizon pixels enter around 15850-15860;
- black first appears around 15870;
- the small Plane-B ship overlaps both persistent ObjBC flames during its interval.

- [ ] **Step 5: Produce the cadence-safe review copy**

Create a labelled review-only 60-fps stream where each source group `(4k..4k+3)` becomes
`visible(4k), visible(4k), hidden(4k+3), hidden(4k+3)`, preserving audio and duration:

```powershell
$video = 'target/trace-videos/wfz-final/capture-wfz-final.mkv'
$review = 'target/trace-videos/wfz-final/capture-wfz-final-cadence-safe-review.mkv'
ffmpeg -hide_banner -y -i $video `
  -filter_complex "[0:v]select='eq(mod(n,4),0)+eq(mod(n,4),3)',setpts=N/(30*TB),fps=60[v]" `
  -map '[v]' -map '0:a?' -frames:v 16426 -c:v ffv1 -level 3 -g 12 -c:a copy $review
```

The selected 30-fps stream contains source parities visible/hidden and `fps=60` duplicates each
selected frame once, yielding the required VVHH pattern. The only intentional visual change is
cadence. Save under:

```text
target/trace-videos/wfz-final/capture-wfz-final-cadence-safe-review.mkv
```

Verify with `ffprobe` that it remains 60/1 fps, 16,426 frames, and approximately 273.77 seconds.
Extract the ObjBC interval and confirm both visible and hidden phases occur in every four-frame group.

- [ ] **Step 6: Run final repository checks**

```powershell
git diff --check
git status --short
```

Expected: no uncommitted source/doc changes and no tracked generated artifacts. Ignored videos and
screenshots may remain under `target/` for delivery.
