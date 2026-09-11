# WFZ Ending Late Horizon and Thrusters Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep the WFZ ending sky blue until the ROM-authored black horizon arrives and retain both background-ship thrusters for the full hull pass.

**Architecture:** Size WFZ's cached Plane-B period from the live spread of its five scroll layers, using the established GHZ power-of-two/static-snapshot pattern and the 8192-pixel map extent. Remove the disproved runtime black-backdrop shortcut so opaque WFZ layout blocks own the transition, and make ObjBC persistent so its existing ROM `$380` event-offset condition exclusively owns deletion.

**Tech Stack:** Java 21, JUnit 5, Mockito, Maven, Sonic 2 ROM-backed scroll tests, headless `TraceCaptureTool`.

---

### Task 1: Preserve ObjBC through its ROM-owned lifetime

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic2/objects/TestWFZShipFireObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic2/objects/WFZShipFireObjectInstance.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write the failing persistence regression test**

Add a test that constructs ObjBC below the `$380` threshold and proves it declares itself persistent, alongside the existing explicit-threshold deletion test:

```java
@Test
void shipFireBypassesGenericCullingUntilItsRomDeleteThreshold() {
    Sonic2LevelEventManager events = wfzEventsWithBgXOffset(0x037F);
    WFZShipFireObjectInstance fire = shipFire(events, mock(ParallaxManager.class));

    assertTrue(fire.isPersistent(),
            "ObjBC has no MarkObjGone call and must survive generic off-screen culling");

    fire.update(0, player());
    fire.update(1, player());

    assertFalse(fire.isDestroyed(), "ObjBC remains alive below Camera_BG_X_offset $380");
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.objects.TestWFZShipFireObjectInstance" test
```

Expected: FAIL because `WFZShipFireObjectInstance.isPersistent()` inherits `false`.

- [ ] **Step 3: Implement the minimal persistence override**

Add to `WFZShipFireObjectInstance`:

```java
@Override
public boolean isPersistent() {
    // ObjBC never calls MarkObjGone; its Camera_BG_X_offset check owns deletion.
    return true;
}
```

Do not change subtype `$56`, subtype `$58`, renderer selection, flicker cadence, or the existing `$380` destruction condition.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: all `TestWFZShipFireObjectInstance` tests PASS.

- [ ] **Step 5: Document and commit the ObjBC correction**

Add a concise `CHANGELOG.md` fix entry explaining that WFZ background-ship flames now remain alive until their ROM event-offset deletion. Stage the two Java files and changelog, then commit with subject `fix(s2): retain WFZ background ship thrusters` and all required repository trailers.

### Task 2: Correct WFZ Plane-B coverage and remove the backdrop shortcut

**Files:**
- Modify: `src/test/java/com/openggf/game/sonic2/scroll/TestSwScrlWfz.java`
- Modify: `src/main/java/com/openggf/game/sonic2/scroll/SwScrlWfz.java`
- Modify: `src/test/java/com/openggf/game/sonic2/TestSonic2WfzRuntimeStateRegistration.java`
- Modify: `src/main/java/com/openggf/game/sonic2/runtime/WfzRuntimeStateView.java`
- Modify: `src/test/java/com/openggf/level/TestLevelRuntimeBackdrop.java`
- Modify: `src/main/java/com/openggf/game/zone/ZoneRuntimeState.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Write the failing WFZ period regression test**

Add to `TestSwScrlWfz`:

```java
@Test
void endingLayerSpreadUsesFullWfzBackgroundPeriod() throws IOException {
    BackgroundCamera fallback = new BackgroundCamera();
    SwScrlWfz handler = new SwScrlWfz(
            new ParallaxTables(TestEnvironment.currentRom()), fallback);
    GameServices.zoneRuntimeRegistry().install(new TestWfzState(0x58C, 0x2AE3));

    handler.update(new int[M68KMath.VISIBLE_LINES], 0x2C00, 0, 0x35FF, 0);

    assertEquals(8192, handler.getBgPeriodWidth(),
            "the ship/static and fastest-cloud layers must share one non-wrapping cache");
}
```

Frame counter `$35FF` produces the large-cloud layer at `$1B00`, so the live spread plus 320 pixels exceeds 4096 and rounds to the 8192-pixel map extent.

- [ ] **Step 2: Run the scroll test and verify RED**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.scroll.TestSwScrlWfz" test
```

Expected: FAIL with expected 8192 but actual default 512.

- [ ] **Step 3: Implement live WFZ period sizing**

Add `getBgPeriodWidth()` to `SwScrlWfz` after `getBgCameraX()`:

```java
@Override
public int getBgPeriodWidth() {
    int minLayerX = Integer.MAX_VALUE;
    int maxLayerX = Integer.MIN_VALUE;
    for (int scrollWord : layerScrollWord) {
        int layerX = (short) scrollWord;
        minLayerX = Math.min(minLayerX, layerX);
        maxLayerX = Math.max(maxLayerX, layerX);
    }

    int requiredWidth = maxLayerX - minLayerX + 320;
    int width = 512;
    while (width < requiredWidth && width < 8192) {
        width <<= 1;
    }
    return Math.min(width, 8192);
}
```

Keep the values derived from the same `layerScrollWord` array consumed by `update()` so cache residency and scanline scroll use one per-frame source.

- [ ] **Step 4: Run the scroll test and verify GREEN**

Run the command from Step 2.

Expected: all `TestSwScrlWfz` tests PASS.

- [ ] **Step 5: Remove the disproved runtime backdrop path**

Delete:

- `ZoneRuntimeState#forceBlackBackdrop()` and its Javadoc.
- `WfzRuntimeStateView#forceBlackBackdrop()`.
- `TestSonic2WfzRuntimeStateRegistration#wfzViewForcesBlackBackdropOnlyInEscapeRoutine()` and now-unused `assertFalse` import.
- The runtime registry branch from `LevelManager#isForceBlackBackdrop()`, leaving only:

```java
private boolean isForceBlackBackdrop() {
    ZoneFeatureProvider zfp = zoneFeatureProvider;
    return zfp != null && zfp.isForceBlackBackdrop();
}
```

- The default/runtime-state cases and helper runtime-state classes from `TestLevelRuntimeBackdrop`, retaining `legacyMczProviderStillForcesBlackBackdrop()` as coverage for the pre-existing provider contract.

- [ ] **Step 6: Run the combined focused regression set**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.scroll.TestSwScrlWfz,com.openggf.game.sonic2.TestSonic2WfzRuntimeStateRegistration,com.openggf.level.TestLevelRuntimeBackdrop" test
```

Expected: all selected tests PASS and production/test compilation contains no `forceBlackBackdrop()` reference on `ZoneRuntimeState`.

- [ ] **Step 7: Document and commit the Plane-B correction**

Amend the WFZ changelog entry to state that dynamic Plane-B coverage preserves the early blue sky and lets the opaque horizon tiles arrive naturally. Stage all Task 2 files and commit with subject `fix(s2): correct WFZ ending Plane-B horizon timing` and all required repository trailers.

### Task 3: Verify the ending and capture replacement evidence

**Files:**
- Verify only; do not modify trace data.
- Output: `target/trace-videos/wfz-followup-2/`

- [ ] **Step 1: Run all focused WFZ parity tests**

Run the existing scroll, event, runtime-state, ship-fire, boss-wall, rewind, and backdrop suites named by the two WFZ follow-up plans.

Expected: zero failures and zero errors.

- [ ] **Step 2: Run both ending trace replay gates**

Run the existing `Sonic2WfzEndingTraceReplay` and WFZ-to-DEZ transition replay tests using the discovered root-level Sonic 2 ROM path and the repository's comparison-only trace workflow.

Expected: both replay tests PASS; trace resources remain unmodified.

- [ ] **Step 3: Run the complete Maven suite**

Run:

```powershell
mvn test
```

Expected: zero failures and zero errors across the complete suite.

- [ ] **Step 4: Capture a replacement lossless headless video**

Use `TraceCaptureTool` with the same successful WFZ ending trace, headless renderer, FFV1 video, FLAC audio, and output directory `target/trace-videos/wfz-followup-2/`. Do not use desktop or computer control.

Expected: the capture command exits 0 and reports a playable 320x224 lossless Matroska file.

- [ ] **Step 5: Inspect objective visual checkpoints headlessly**

Extract frames during the small-hull pass and around the early/late horizon transition. Confirm:

- both placed ObjBC flames overlap the Plane-B hull and flicker on alternating frames;
- the prior premature-black interval remains blue sky;
- opaque black horizon content arrives later through Plane-B geometry;
- the foreground ship/grab animation remains unchanged.

- [ ] **Step 6: Run final diff and repository checks**

Run `git status --short`, review `git diff`/recent commits, verify no ROM, trace, video, or generated target artifacts are tracked, and request final spec-compliance plus code-quality review over both implementation commits.
