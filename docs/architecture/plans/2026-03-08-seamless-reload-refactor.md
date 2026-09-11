# Refactor SEAMLESS_RELOAD to ROM-Aligned Act Transition System — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the misaligned `SEAMLESS_RELOAD` profile path with a direct `executeActTransition()` that matches ROM behavior — act transitions are in-place data swaps, not level reloads.

**Architecture:** Create `LevelManager.executeActTransition()` that calls `loadLevelData()` directly (layout+collision only), bypassing the profile system. Remove `LevelLoadMode.SEAMLESS_RELOAD`, the `loadZoneAndActSeamless()` method, and all seamless gating from `Sonic3kLevelInitProfile`. Simplify `applySeamlessTransition()` to route RELOAD types through the new method.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Maven

---

### Task 1: Add `resetManagersForActTransition()` to LevelManager

This helper resets ObjectManager and RingManager state, matching ROM's clear of `Dynamic_object_RAM` and `Ring_status_table` during act transitions.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Test: `src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java` (no changes yet, just compile check)

**Step 1: Add the method to LevelManager**

Add this private method near the existing `applySeamlessOffsets()` method (around line 4044):

```java
/**
 * Resets object and ring managers for an in-place act transition.
 * Matches ROM behavior: clears Dynamic_object_RAM and Ring_status_table
 * without touching player/checkpoint state.
 */
private void resetManagersForActTransition() {
    ObjectManager om = getObjectManager();
    if (om != null) {
        om.reset(camera.getX());
    }
    RingManager rm = getRingManager();
    if (rm != null) {
        rm.reset(camera.getX());
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no errors)

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: add resetManagersForActTransition() to LevelManager"
```

---

### Task 2: Create `executeActTransition()` in LevelManager

The core new method that replaces `loadZoneAndActSeamless()`. It calls `loadLevelData()` directly instead of going through the profile system.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

**Step 1: Add the `executeActTransition()` method**

Add this public method near `loadZoneAndActSeamless()` (around line 4001), but DO NOT delete `loadZoneAndActSeamless()` yet:

```java
/**
 * Performs a ROM-aligned act transition: reloads layout + collision,
 * resets managers, applies offsets, and restores camera bounds.
 * <p>
 * This bypasses the profile system entirely because act transitions
 * are NOT level loads in the ROM — they are in-place data swaps
 * performed by level event background routines.
 * <p>
 * ROM reference: S3K zone BG event handlers (e.g. AIZ Act 2 transition
 * at sonic3k.asm). Pattern: set zone/act → Load_Level + LoadSolids →
 * Offset_ObjectsDuringTransition → clear managers → restore camera bounds.
 *
 * @param request the transition request with target zone/act, offsets, etc.
 * @throws IOException if level data loading fails
 */
public void executeActTransition(SeamlessLevelTransitionRequest request) throws IOException {
    if (request == null) {
        return;
    }

    // Suppress music reload if requested (ROM: music continues through transition)
    if (request.preserveMusic()) {
        setSuppressNextMusicChange(true);
    }

    // 1. Set zone/act (ROM: move.b d0, Current_zone_and_act)
    currentZone = request.targetZone();
    currentAct = request.targetAct();

    // 2. Reload layout + collision only (ROM: Load_Level + LoadSolids)
    if (levels.isEmpty()) {
        gameModule = GameModuleRegistry.getCurrent();
        refreshZoneList();
    }
    LevelData levelData = levels.get(currentZone).get(currentAct);
    loadLevelData(levelData.getLevelIndex());

    // 3. Apply art mutations if requested (ROM: zone-specific art swaps)
    if (request.mutationKey() != null && !request.mutationKey().isBlank()) {
        applySeamlessMutation(request.mutationKey());
    }

    // 4. Reset managers (ROM: clears Dynamic_object_RAM, Ring_status_table)
    resetManagersForActTransition();

    // 5. Apply coordinate offsets (ROM: Offset_ObjectsDuringTransition)
    applySeamlessOffsets(request);

    // 6. Restore camera bounds from new level data
    restoreCameraBoundsForCurrentLevel();
    camera.updatePosition(true);

    // 7. Reinitialize level events for new act
    initLevelEventsForCurrentZoneAct();

    // 8. Music override if specified
    if (request.musicOverrideId() >= 0) {
        AudioManager.getInstance().playMusic(request.musicOverrideId());
    }

    // 9. In-level title card if requested
    if (request.showInLevelTitleCard() && !graphicsManager.isHeadlessMode()) {
        requestInLevelTitleCard(currentZone, currentAct);
    }
}
```

**Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: add executeActTransition() — ROM-aligned act transition path"
```

---

### Task 3: Rewire `applySeamlessTransition()` to use `executeActTransition()`

Replace the `loadZoneAndActSeamless()` calls inside `applySeamlessTransition()` with calls to `executeActTransition()`. This makes `loadZoneAndActSeamless()` dead code (removed in Task 5).

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java:4453-4505`

**Step 1: Replace the method body**

Replace the entire `applySeamlessTransition()` method (lines 4453-4505) with:

```java
/**
 * Applies a seamless transition immediately.
 * <p>
 * Routes through {@link #executeActTransition} for RELOAD types,
 * which bypasses the profile system and matches ROM behavior.
 */
public void applySeamlessTransition(SeamlessLevelTransitionRequest request) {
    if (request == null) {
        return;
    }

    try {
        specialStageReturnLevelReloadRequested = false;
        switch (request.type()) {
            case MUTATE_ONLY -> applySeamlessMutation(request.mutationKey());
            case RELOAD_SAME_LEVEL -> {
                SeamlessLevelTransitionRequest adjusted = SeamlessLevelTransitionRequest
                        .builder(SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                        .targetZoneAct(currentZone, currentAct)
                        .deactivateLevelNow(request.deactivateLevelNow())
                        .preserveMusic(request.preserveMusic())
                        .showInLevelTitleCard(request.showInLevelTitleCard())
                        .playerOffset(request.playerOffsetX(), request.playerOffsetY())
                        .cameraOffset(request.cameraOffsetX(), request.cameraOffsetY())
                        .mutationKey(request.mutationKey())
                        .musicOverrideId(request.musicOverrideId())
                        .build();
                executeActTransition(adjusted);
            }
            case RELOAD_TARGET_LEVEL -> executeActTransition(request);
        }
    } catch (IOException e) {
        throw new RuntimeException("Failed to apply seamless transition", e);
    } finally {
        levelInactiveForTransition = false;
    }
}
```

Key differences from the old version:
- `RELOAD_SAME_LEVEL` and `RELOAD_TARGET_LEVEL` both call `executeActTransition()` (not `loadZoneAndActSeamless()`)
- Music override, offsets, camera bounds, level events, title card — all handled inside `executeActTransition()`, so no duplicate calls here
- Removed the post-switch `applySeamlessOffsets()`, `restoreCameraBoundsForCurrentLevel()`, `camera.updatePosition()`, music, and title card code — it's all in `executeActTransition()` now

**Step 2: Run all tests**

Run: `mvn test -q`
Expected: All tests pass. The only caller of this path is `GameLoop` → `consumeSeamlessTransitionRequest()` → `applySeamlessTransition()`, and the test suite exercises `SharedLevel.load()` + `HeadlessTestFixture`, not the seamless transition path. No test currently exercises the full act transition pipeline at runtime.

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: rewire applySeamlessTransition() to use executeActTransition()"
```

---

### Task 4: Remove seamless gating from `Sonic3kLevelInitProfile`

Now that `applySeamlessTransition()` no longer calls through the profile system, the `seamlessReload` gating in `Sonic3kLevelInitProfile` is dead code.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java:26-93`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java`

**Step 1: Remove seamless gating from `Sonic3kLevelInitProfile.levelLoadSteps()`**

In `Sonic3kLevelInitProfile.java`:

1. Remove the import of `LevelLoadMode` (line 6)
2. Remove the `boolean seamlessReload = ...` line (line 34)
3. Change `if (!seamlessReload) {` (line 69) to always include `InitPlayerAndCheckpoint` — remove the `if` wrapper
4. Change `if (ctx.isIncludePostLoadAssembly() && !seamlessReload) {` (line 83) to `if (ctx.isIncludePostLoadAssembly()) {`
5. Remove the Javadoc mention of seamless reload (line 26)

The result should look like (starting from line 29):

```java
public class Sonic3kLevelInitProfile extends AbstractLevelInitProfile {

    @Override
    public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
        LevelManager lm = LevelManager.getInstance();

        List<InitStep> steps = new ArrayList<>(20);
        steps.add(new InitStep("InitGameModule",
                "S3K Phase A-D (#1-20): cmd_FadeOut, Pal_FadeToBlack, Clear_Nem_Queue, clearRAM, create Game instance",
                () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
        steps.add(new InitStep("InitAudio",
                "S3K Phase F (#25): Play_Music from LevelMusic_Playlist - AIZ1 lamppost 3 music override",
                () -> { try { lm.initAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
        steps.add(new InitStep("LoadLevelData",
                "S3K Phase H-K (#30-38): Get_LevelSizeStart, DeformBgLayer, LoadLevelLoadBlock, LoadLevelLoadBlock2, j_LevelSetup, LoadSolids",
                () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }));
        steps.add(new InitStep("InitAnimatedContent",
                "S3K Phase J (#37): Animate_Init (zone-specific animation counter initialization)",
                lm::initAnimatedContent));
        steps.add(new InitStep("InitObjectManager",
                "S3K Phase O (#47-48): SpawnLevelMainSprites, Load_Sprites - create ObjectManager, wire CollisionSystem",
                () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
        steps.add(new InitStep("InitCameraBounds",
                "S3K Phase H (#32): Get_LevelSizeStart - reset camera bounds from level geometry",
                lm::initCameraBounds));
        steps.add(new InitStep("InitGameplayState",
                "S3K Phase N (#43-45): Clear game state, OscillateNumInit, Level_started_flag set before first object frame",
                lm::initGameplayState));
        steps.add(new InitStep("InitRings",
                "S3K Phase O (#49): Load_Rings - initial ring placement",
                lm::initRings));
        steps.add(new InitStep("InitZoneFeatures",
                "S3K Phase J (#36): j_LevelSetup -> LevelSetupArray per-zone dispatch, HCZ water surface, MHZ pollen",
                () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
        steps.add(new InitStep("InitArt",
                "S3K Phase C (#12-14): Load_PLC zone, character PLCs, standard PLCs",
                lm::initArt));
        steps.add(new InitStep("InitPlayerAndCheckpoint",
                "S3K Phase O (#47): SpawnLevelMainSprites - player spawn after game state init",
                lm::initPlayerAndCheckpoint));
        steps.add(new InitStep("InitWater",
                "S3K Phase E (#22): CheckLevelForWater, StartingWaterHeights",
                () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
        steps.add(new InitStep("InitBackgroundRenderer",
                "Engine-specific: Pre-allocate BG FBO for AIZ intro ocean-to-beach transition",
                lm::initBackgroundRenderer));

        // Post-load assembly: checkpoint restore, player spawn, camera, level events, sidekick, title card
        if (ctx.isIncludePostLoadAssembly()) {
            steps.add(restoreCheckpointStep(ctx));
            steps.add(spawnPlayerStep(ctx));
            steps.add(resetPlayerStateStep(ctx));
            steps.add(initCameraStep());
            steps.add(initLevelEventsStep());
            steps.add(spawnSidekickStep());
            steps.add(requestTitleCardStep(ctx));
        }

        return List.copyOf(steps);
    }
```

**Step 2: Update `TestSonic3kLevelInitProfile`**

1. Delete the `seamlessReloadSkipsPlayerAndSidekickSteps()` test method (lines 100-113)
2. Update `levelLoadStepsContains13WithoutPostLoad()` to expect **14** steps (now includes `InitPlayerAndCheckpoint` which was previously skipped in the default/non-postload path):

Wait — actually check: the test at line 62 uses `new LevelLoadContext()` (no `setLoadMode`), which defaults to `FULL`. With the old code, FULL mode without `includePostLoadAssembly` produces 13 steps (10 base + InitPlayerAndCheckpoint + InitWater + InitBackgroundRenderer = 13). With our change, it still produces 13 because `InitPlayerAndCheckpoint` was only skipped when `seamlessReload=true`. The default FULL mode already included it. So **no change needed** to the step count test.

Just delete the test and the `LevelLoadMode` import:

```java
// Delete these lines from TestSonic3kLevelInitProfile.java:
import com.openggf.game.LevelLoadMode;  // line 5

// Delete lines 100-113:
@Test
public void seamlessReloadSkipsPlayerAndSidekickSteps() { ... }
```

**Step 3: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java \
        src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java
git commit -m "refactor: remove SEAMLESS_RELOAD gating from Sonic3kLevelInitProfile"
```

---

### Task 5: Delete `loadZoneAndActSeamless()` and `SEAMLESS_RELOAD`

Now that nothing calls `loadZoneAndActSeamless()` or uses `SEAMLESS_RELOAD`, remove them.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java` — delete `loadZoneAndActSeamless()` (lines 3998-4030)
- Modify: `src/main/java/com/openggf/game/LevelLoadMode.java` — delete `SEAMLESS_RELOAD` value
- Modify: `src/main/java/com/openggf/game/LevelLoadContext.java` — keep `loadMode` field (still used for FULL), no change needed

**Step 1: Delete `loadZoneAndActSeamless()` from LevelManager**

Remove the entire method block from `LevelManager.java` (the block starting with `public void loadZoneAndActSeamless` through the closing brace). This is approximately lines 3998-4030.

**Step 2: Delete `SEAMLESS_RELOAD` from `LevelLoadMode`**

Replace the contents of `LevelLoadMode.java` with:

```java
package com.openggf.game;

/**
 * Mode for level-load profile execution.
 */
public enum LevelLoadMode {
    /**
     * Full level load path (default).
     */
    FULL
}
```

**Step 3: Verify no remaining references**

Run: `grep -rn "SEAMLESS_RELOAD\|loadZoneAndActSeamless" src/ --include="*.java"`
Expected: No output (zero references)

**Step 4: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java \
        src/main/java/com/openggf/game/LevelLoadMode.java
git commit -m "refactor: remove SEAMLESS_RELOAD and loadZoneAndActSeamless()"
```

---

### Task 6: Simplify `loadLevel()` overloads (optional cleanup)

With `SEAMLESS_RELOAD` gone, the `loadLevel(int, LevelLoadMode)` overload always receives `FULL`. Consider whether the `LevelLoadMode` parameter and `LevelLoadContext.loadMode` field still serve a purpose. If not, simplify.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java:588-650`
- Modify: `src/main/java/com/openggf/game/LevelLoadContext.java:22,55-58`

**Step 1: Evaluate**

Check if any code passes a non-FULL value or if any profile checks `getLoadMode()`:

Run: `grep -rn "LevelLoadMode\|getLoadMode\|setLoadMode" src/ --include="*.java"`

If all callers pass `LevelLoadMode.FULL` and no profile checks `getLoadMode()`, then:

1. Remove the `loadLevel(int, LevelLoadMode)` overload — have `loadLevel(int)` call `loadLevel(int, LevelLoadMode.FULL, ctx)` directly
2. Remove the `loadMode` field from `LevelLoadContext` and its getter/setter
3. Remove the `LevelLoadMode` enum entirely (or leave it with just `FULL` as a documented extension point)

**Decision:** Skip this cleanup if `LevelLoadMode` with just `FULL` is a reasonable extension point for future game support. The current state is clean enough. Only proceed if the team prefers maximum minimalism.

**Step 2: Run all tests**

Run: `mvn test -q`
Expected: All tests pass

**Step 3: Commit (if changes made)**

```bash
git add src/main/java/com/openggf/level/LevelManager.java \
        src/main/java/com/openggf/game/LevelLoadContext.java \
        src/main/java/com/openggf/game/LevelLoadMode.java
git commit -m "refactor: simplify loadLevel() overloads after SEAMLESS_RELOAD removal"
```

---

### Task 7: Full regression verification

**Step 1: Run all tests**

Run: `mvn test`
Expected: All 1522+ tests pass, BUILD SUCCESS

**Step 2: Verify key S3K tests specifically**

Run: `mvn test -Dtest="TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestSonic3kLevelInitProfile" -q`
Expected: All pass

**Step 3: Verify no dead code references remain**

Run: `grep -rn "SEAMLESS_RELOAD\|loadZoneAndActSeamless\|seamlessReload" src/ --include="*.java"`
Expected: No output (zero references)

**Step 4: Commit (no-op if clean)**

Already committed in previous tasks. This is verification only.

---

## Summary of Changes

| File | Change |
|------|--------|
| `LevelManager.java` | Add `resetManagersForActTransition()`, add `executeActTransition()`, rewrite `applySeamlessTransition()`, delete `loadZoneAndActSeamless()` |
| `LevelLoadMode.java` | Delete `SEAMLESS_RELOAD` value |
| `Sonic3kLevelInitProfile.java` | Remove `seamlessReload` gating, remove `LevelLoadMode` import |
| `TestSonic3kLevelInitProfile.java` | Delete `seamlessReloadSkipsPlayerAndSidekickSteps()` test, remove `LevelLoadMode` import |
| `SeamlessLevelTransitionRequest.java` | No change |
| `S3kSeamlessMutationExecutor.java` | No change |
| `Sonic3kAIZEvents.java` | No change |
| `GameLoop.java` | No change |
| `LevelLoadContext.java` | No change (loadMode field kept for extensibility) |
