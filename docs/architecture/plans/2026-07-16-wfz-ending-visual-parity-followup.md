# WFZ Ending Visual-Parity Follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the ROM's WFZ boss-wall flicker, keep the ending getaway-ship hull resident beside its thrusters, and reveal a black backdrop above the final horizon.

**Architecture:** Keep the three corrections at their smallest owners. The wall owns a rewind-captured display phase; `SwScrlWfz` exposes its existing live background X source to the tilemap cache; and the WFZ live runtime view publishes an escape-phase backdrop predicate consumed through the generic zone-runtime interface.

**Tech Stack:** Java 21, JUnit 5/Jupiter, Mockito where rendering calls require a test double, Maven, existing rewind schema/graph harness, trace-replay profile, and headless `TraceCaptureTool`/ffmpeg.

---

## File map

- `Sonic2WFZBossInstance.java`: owns ObjC5 laser-wall update/render behavior.
- `TestS2WfzBossLaserWall.java`: focused visibility/solidity unit coverage.
- `TestS2WfzBossGraphRewind.java`: proves the wall phase survives rewind recreation.
- `SwScrlWfz.java`: supplies both per-line scroll values and the Plane-B cache camera X.
- `TestSwScrlWfz.java`: verifies live runtime-state and fallback cache-camera values.
- `ZoneRuntimeState.java`: generic optional backdrop predicate.
- `WfzRuntimeStateView.java`: derives the predicate from the ROM event routine.
- `LevelManager.java`: combines runtime-state and existing provider backdrop requirements.
- `TestSonic2WfzRuntimeStateRegistration.java`: verifies routine-6 activation through the live view.
- `TestLevelRuntimeBackdrop.java`: verifies generic runtime-state precedence and legacy fallback.
- `CHANGELOG.md`: records the user-visible parity corrections.

---

### Task 1: ObjC5 laser-wall flicker remains solid

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/objects/bosses/Sonic2WFZBossInstance.java:948-1073`
- Create: `src/test/java/com/openggf/game/sonic2/objects/bosses/TestS2WfzBossLaserWall.java`
- Modify: `src/test/java/com/openggf/game/sonic2/objects/bosses/TestS2WfzBossGraphRewind.java`

- [ ] **Step 1: Write the failing active-cadence and solidity tests**

Create a same-package fixture with `ObjectSpawn(0x100, 0x400, WFZ_BOSS, 0, 0, false, 10)`, construct `Sonic2WFZBossInstance` and its `WFZLaserWall`, and inject `StubObjectServices` into both objects. Expose the render latch through the package-private `isVisibleThisFrameForTest()` accessor below. Assert the exact active sequence and unchanged solid parameters:

```java
@Test
void activeWallAlternatesVisibilityButRemainsSolid() {
    Sonic2WFZBossInstance.WFZLaserWall wall = newWall();

    wall.update(1, player);
    boolean first = wall.isVisibleThisFrameForTest();
    wall.update(2, player);
    boolean second = wall.isVisibleThisFrameForTest();
    wall.update(3, player);
    boolean third = wall.isVisibleThisFrameForTest();

    assertNotEquals(first, second);
    assertEquals(first, third);
    assertEquals(new SolidObjectParams(0x13, 0x40, 0x80), wall.getSolidParams());
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.objects.bosses.TestS2WfzBossLaserWall" test
```

Expected: compilation failure because `isVisibleThisFrameForTest()` does not exist, or an assertion failure because the wall is continuously visible.

- [ ] **Step 3: Implement the minimal wall-owned display latch**

Add and initialize a non-final scalar, toggle it only while active, and gate drawing without changing solidity:

```java
private boolean visibleThisFrame = true;

@Override
public void update(int frameCounter, PlayableEntity playerEntity) {
    if (!beginUpdate(frameCounter)) {
        return;
    }
    if (defeatSignaled) {
        updateDefeatDelete();
    } else {
        visibleThisFrame = !visibleThisFrame;
    }
    updateDynamicSpawn();
}

boolean isVisibleThisFrameForTest() {
    return visibleThisFrame;
}
```

In `appendRenderCommands`, return when the active latch is false. During defeat, set the latch from the ROM nested-counter display branch rather than layering the old signed-counter inference over the active cadence. Do not alter `getSolidParams()`.

- [ ] **Step 4: Prove rewind preserves the phase**

Extend `TestS2WfzBossGraphRewind`: advance the restored-side wall to one known visibility phase, capture, advance once, restore, and assert `isVisibleThisFrameForTest()` equals the captured phase before another update and flips exactly once afterward.

- [ ] **Step 5: Run focused wall and rewind tests and confirm GREEN**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.objects.bosses.TestS2WfzBossLaserWall,com.openggf.game.sonic2.objects.bosses.TestS2WfzBossGraphRewind" test
```

Expected: all tests pass.

- [ ] **Step 6: Commit the isolated wall correction**

Stage only the three Task 1 files plus `CHANGELOG.md` and commit with subject `fix(s2): flicker WFZ boss laser walls` and the required branch-policy trailers.

---

### Task 2: Plane-B cache follows the live WFZ background X

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/scroll/SwScrlWfz.java`
- Modify: `src/test/java/com/openggf/game/sonic2/scroll/TestSwScrlWfz.java`

- [ ] **Step 1: Write failing runtime and fallback cache-camera tests**

Add a shared resolver seam in `SwScrlWfz` and test that `getBgCameraX()` uses it:

```java
@Test
void bgCacheCameraUsesLiveWfzRuntimeX() throws IOException {
    BackgroundCamera fallback = new BackgroundCamera();
    fallback.setBgXPos(0x111);
    SwScrlWfz handler = new SwScrlWfz(new ParallaxTables(TestEnvironment.currentRom()), fallback);
    installRuntimeState(new TestWfzState(0x222, 0x3456));

    assertEquals(0x3456, handler.getBgCameraX());
}

@Test
void bgCacheCameraFallsBackOutsideGameplayRuntime() throws IOException {
    BackgroundCamera fallback = new BackgroundCamera();
    fallback.setBgXPos(0x456);
    SwScrlWfz handler = new SwScrlWfz(new ParallaxTables(TestEnvironment.currentRom()), fallback);

    assertEquals(0x456, handler.getBgCameraX());
}
```

Use the same `TestEnvironment`/session setup already used by WFZ runtime registration tests; the test state implements `WfzRuntimeState` and supplies `bgVscrollFactor()` plus `bgXPos()`.

- [ ] **Step 2: Run the focused test and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.scroll.TestSwScrlWfz" test
```

Expected: the runtime assertion fails because the inherited method returns the sentinel/fallback value.

- [ ] **Step 3: Implement one live-state resolver and override**

Add:

```java
private WfzRuntimeState currentRuntimeState() {
    return GameServices.hasRuntime()
            ? GameServices.zoneRuntimeRegistry().currentAs(WfzRuntimeState.class).orElse(null)
            : null;
}

private int currentBgXPos() {
    WfzRuntimeState state = currentRuntimeState();
    return state != null ? state.bgXPos() : bgCamera.getBgXPos();
}

@Override
public int getBgCameraX() {
    return currentBgXPos();
}
```

Change `update()` to use `currentRuntimeState()`/`currentBgXPos()` so H-scroll and tile residency cannot diverge. Preserve the existing Y fallback and all ROM table bytes.

- [ ] **Step 4: Run the scroll tests and confirm GREEN**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.scroll.TestSwScrlWfz,com.openggf.game.sonic2.TestSonic2WfzBgBridgeIntegration" test
```

Expected: all tests pass and the existing H/V bridge assertions remain unchanged.

- [ ] **Step 5: Commit the isolated tile-residency correction**

Stage the two Task 2 files plus `CHANGELOG.md` and commit with subject `fix(s2): track WFZ ending ship background window` and required trailers.

---

### Task 3: Escape phase forces a black transparent backdrop

**Files:**
- Modify: `src/main/java/com/openggf/game/zone/ZoneRuntimeState.java`
- Modify: `src/main/java/com/openggf/game/sonic2/runtime/WfzRuntimeStateView.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:3250-3263`
- Modify: `src/test/java/com/openggf/game/sonic2/TestSonic2WfzRuntimeStateRegistration.java`
- Create: `src/test/java/com/openggf/level/TestLevelRuntimeBackdrop.java`

- [ ] **Step 1: Write failing generic and WFZ activation tests**

Add a default-contract assertion, then drive the real WFZ event routine through its live view:

```java
@Test
void wfzViewForcesBlackBackdropOnlyInEscapeRoutine() {
    Sonic2WFZEvents events = new Sonic2WFZEvents();
    WfzRuntimeStateView state = new WfzRuntimeStateView(ZONE_WFZ, 0, events);

    events.setEventRoutine(4);
    assertFalse(state.forceBlackBackdrop());
    events.setEventRoutine(6);
    assertTrue(state.forceBlackBackdrop());
}
```

In `TestLevelRuntimeBackdrop`, install a dummy `ZoneRuntimeState` whose predicate returns true and assert `LevelManager.resolveLevelBackdropColor()` is black. Add a second assertion with no runtime predicate proving the existing `ZoneFeatureProvider#isForceBlackBackdrop()` path still forces MCZ black.

- [ ] **Step 2: Run the focused tests and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.TestSonic2WfzRuntimeStateRegistration,com.openggf.level.TestLevelRuntimeBackdrop" test
```

Expected: compilation failure because `forceBlackBackdrop()` is not defined.

- [ ] **Step 3: Add the generic predicate and live WFZ derivation**

In `ZoneRuntimeState`:

```java
default boolean forceBlackBackdrop() {
    return false;
}
```

In `WfzRuntimeStateView`:

```java
@Override
public boolean forceBlackBackdrop() {
    return events.getEventRoutine() >= 6;
}
```

This is derived live from the already-snapshotted ROM event routine; add no field or snapshot bytes.

- [ ] **Step 4: Make LevelManager combine runtime and provider requirements**

Replace the private check with:

```java
private boolean isForceBlackBackdrop() {
    if (GameServices.hasRuntime()
            && GameServices.zoneRuntimeRegistry().current().forceBlackBackdrop()) {
        return true;
    }
    ZoneFeatureProvider zfp = zoneFeatureProvider;
    return zfp != null && zfp.isForceBlackBackdrop();
}
```

`ZoneRuntimeRegistry#current()` always returns a state (`NoOpZoneRuntimeState` after clear), so no null or optional branch is required. Runtime true wins; runtime false preserves the provider fallback.

- [ ] **Step 5: Run focused and generic level tests and confirm GREEN**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.TestSonic2WfzRuntimeStateRegistration,com.openggf.level.TestLevelRuntimeBackdrop,com.openggf.tests.TestLevelManager" test
```

Expected: all tests pass.

- [ ] **Step 6: Commit the isolated backdrop correction**

Stage the five Task 3 files plus `CHANGELOG.md` and commit with subject `fix(s2): reveal black backdrop at WFZ escape` and required trailers.

---

### Task 4: Integrated verification and headless visual evidence

**Files:**
- Modify only if needed: `CHANGELOG.md`
- Do not modify trace resources.

- [ ] **Step 1: Run the focused regression suite**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.objects.bosses.TestS2WfzBossLaserWall,com.openggf.game.sonic2.objects.bosses.TestS2WfzBossGraphRewind,com.openggf.game.sonic2.scroll.TestSwScrlWfz,com.openggf.game.sonic2.TestSonic2WfzBgScroll,com.openggf.game.sonic2.TestSonic2WfzBgBridgeIntegration,com.openggf.game.sonic2.TestSonic2WfzRuntimeStateRegistration,com.openggf.level.TestLevelRuntimeBackdrop" test
```

Expected: all selected tests pass.

- [ ] **Step 2: Run both trace-replay release gates**

Discover the root-level Sonic 2 ROM and pass its actual path:

```powershell
$rom = Get-ChildItem -LiteralPath (git rev-parse --show-toplevel) -File -Filter *.gen |
    Where-Object { $_.Name -match 'sonic.?2|s2' } |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $rom) { throw 'No root-level Sonic 2 ROM was found' }
mvn -Ptrace-replay -o "-Dsonic2.rom.path=$rom" "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2DezEndingLevelSelectTraceReplay" test
```

Expected: both trace tests pass. Do not regenerate or modify the traces.

- [ ] **Step 3: Capture the ending entirely headlessly**

Use the repository's `trace-capture` skill and `TraceCaptureTool` against the existing WFZ trace. Capture lossless MKV with HUD/ghost settings matching the prior visual pass; do not use computer control.

- [ ] **Step 4: Extract acceptance frames with ffmpeg**

Extract consecutive boss frames proving draw/skip cadence, a frame in the first `$58`-thruster interval proving the adjacent small hull is resident, and a late frame proving black above the opaque blue/white horizon.

- [ ] **Step 5: Review the complete diff and commit any final documentation-only adjustment**

Confirm no ObjB2 spawn timing, foreground ship rendering, Sonic grab animation, ROM tables, or trace data changed. Commit only an outstanding changelog adjustment, with required trailers.

---

## Self-review

- Spec coverage: §7.1 → Task 1; §7.2 → Task 2; §7.3 → Task 3; §7.4 → Task 4.
- Type consistency: `forceBlackBackdrop()` is defined once on `ZoneRuntimeState`, overridden by `WfzRuntimeStateView`, and consumed by `LevelManager`; `getBgCameraX()` and `update()` share `currentBgXPos()`.
- Scope: foreground ship/Sonic grab, ObjB2 timing, ROM arrays, trace data, and broad renderer architecture are unchanged.
- Placeholder scan: no deferred implementation placeholders remain; ROM discovery is an executable PowerShell step.
