# DEZ Parallax Background During S2 Ending Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Render the DEZ star field background with per-row parallax scrolling during the S2 ending cutscene's sky phases (CAMERA_SCROLL and MAIN_ENDING).

**Architecture:** DEZ level data (patterns, tilemap, BackgroundRenderer, ParallaxManager with SwScrlDez) survives the ending transition. We add two default methods to EndingProvider so the engine can query whether to render the level background, then plumb a new `renderEndingBackground()` path through LevelManager that calls SwScrlDez with the ending's BG Y scroll value and renders via the existing shader pipeline.

**Tech Stack:** Java 21, OpenGL (via LWJGL), existing ParallaxManager/SwScrlDez/BackgroundRenderer/TilemapGpuRenderer pipeline.

**Design doc:** `docs/architecture/designs/2026-02-27-ending-parallax-background-design.md`

---

### Task 1: Add `needsLevelBackground()` and `getBackgroundVscroll()` to EndingProvider

**Files:**
- Modify: `src/main/java/com/openggf/game/EndingProvider.java:166` (after `onReturnToText()`)

**Step 1: Add the two default methods to EndingProvider**

After the existing `onReturnToText()` method (line 185), add:

```java
/**
 * Returns whether the ending currently needs the DEZ level background
 * rendered behind the cutscene sprites. When true, the engine calls
 * {@link com.openggf.level.LevelManager#renderEndingBackground(int)}
 * before {@link #draw()}.
 *
 * @return true if level background should be rendered this frame
 */
default boolean needsLevelBackground() {
    return false;
}

/**
 * Returns the current background vertical scroll value.
 * Maps to ROM's Vscroll_Factor_BG during the ending sequence.
 * Only meaningful when {@link #needsLevelBackground()} returns true.
 *
 * @return BG vertical scroll in pixels
 */
default int getBackgroundVscroll() {
    return 0;
}
```

**Step 2: Verify build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (no consumers yet, just default methods)

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/EndingProvider.java
git commit -m "feat: add needsLevelBackground/getBackgroundVscroll to EndingProvider"
```

---

### Task 2: Add BG Y position tracking and background state to Sonic2EndingCutsceneManager

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`

**Step 1: Add bgYPos field and constants**

Near the existing `CAMERA_SCROLL state` fields (around line 173-174), add:

```java
// Background vertical scroll tracking (ROM: Camera_BG_Y_pos)
// Starts at $C8 (200) during CHARACTER_APPEAR, increments during CAMERA_SCROLL
private int bgYPos;
```

Near the existing timing constants, add:

```java
/** ROM: Camera_BG_Y_pos initial value ($C8 = 200). */
private static final int INITIAL_BG_Y_POS = 0xC8;
```

**Step 2: Initialize bgYPos in the `enterCharacterAppear()` method**

In the `enterCharacterAppear()` method, after the existing state setup, add:

```java
// ROM: EndingSequence sets Camera_BG_Y_pos = $C8
bgYPos = INITIAL_BG_Y_POS;
```

**Step 3: Increment bgYPos during CAMERA_SCROLL**

In `updateCameraScroll()`, after incrementing `stateFrameCounter`, add:

```java
// ROM: SetHorizVertiScrollFlagsBG adds Camera_Y_pos_diff << 8 to Camera_BG_Y_pos
// With Camera_Y_pos_diff=$100, this increments ~1px/frame (256 subpixels in 8.8 fixed-point)
bgYPos++;
```

**Step 4: Add public accessors for Engine integration**

After the existing `isSkyPhase()` method (around line 444), add:

```java
/**
 * Returns whether the level background (DEZ star field) should be rendered
 * behind the cutscene sprites. Active during CAMERA_SCROLL and MAIN_ENDING.
 */
public boolean needsLevelBackground() {
    return state == CutsceneState.CAMERA_SCROLL
            || state == CutsceneState.MAIN_ENDING;
}

/**
 * Returns the current background vertical scroll value (ROM: Vscroll_Factor_BG).
 * Starts at $C8 during CHARACTER_APPEAR, increments during CAMERA_SCROLL.
 */
public int getBackgroundVscroll() {
    return bgYPos;
}
```

**Step 5: Verify build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java
git commit -m "feat: add BG Y position tracking for ending parallax"
```

---

### Task 3: Delegate background methods from Sonic2EndingProvider

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingProvider.java`

**Step 1: Add needsLevelBackground() override**

After the existing `setClearColor()` method (line 216), add:

```java
@Override
public boolean needsLevelBackground() {
    if (cutsceneManager != null && (state == InternalState.CUTSCENE || state == InternalState.CUTSCENE_FADE_OUT)) {
        return cutsceneManager.needsLevelBackground();
    }
    return false;
}

@Override
public int getBackgroundVscroll() {
    if (cutsceneManager != null) {
        return cutsceneManager.getBackgroundVscroll();
    }
    return 0;
}
```

**Step 2: Verify build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingProvider.java
git commit -m "feat: delegate needsLevelBackground from Sonic2EndingProvider"
```

---

### Task 4: Add `renderEndingBackground()` to LevelManager

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

This is the core rendering method. It reuses the existing `renderBackgroundShader()` pipeline but with ending-specific parameters: camera at (0,0), BG vscroll from the ending provider, and DEZ-specific parallax update.

**Step 1: Add the renderEndingBackground method**

After the existing `renderBackgroundShader()` method (which ends around line 1613+), add:

```java
/**
 * Renders the DEZ background during the ending cutscene.
 * <p>
 * Reuses the existing shader background pipeline with ending-specific parameters:
 * camera at (0,0), BG vertical scroll from the ending provider, and DEZ
 * parallax update via SwScrlDez TempArray accumulation.
 * <p>
 * ROM reference: During the ending, SwScrl_DEZ runs every frame with
 * Camera_X_pos=0, Camera_BG_Y_pos starting at $C8 and incrementing during
 * CAMERA_SCROLL. Stars animate via TempArray addq accumulation independent
 * of camera movement.
 *
 * @param bgVscroll the current background vertical scroll value (ROM: Vscroll_Factor_BG)
 */
public void renderEndingBackground(int bgVscroll) {
    if (level == null || level.getMap() == null) {
        return;
    }
    if (!useShaderBackground || graphicsManager.getBackgroundRenderer() == null) {
        return;
    }

    // Update parallax with camera=(0,0) and the ending's BG vscroll
    // This drives SwScrlDez TempArray accumulation for star parallax
    frameCounter++;
    parallaxManager.updateForEnding(currentZone, currentAct, frameCounter, bgVscroll);

    // Render using the existing shader pipeline
    renderBackgroundShader(collisionCommands, bgVscroll);
}
```

**Step 2: Verify build (will fail — `updateForEnding` not yet added)**

Run: `mvn compile -q`
Expected: FAIL with "cannot find symbol: method updateForEnding"

This is expected — we'll add it in Task 5.

---

### Task 5: Add `updateForEnding()` to ParallaxManager

**Files:**
- Modify: `src/main/java/com/openggf/level/ParallaxManager.java`

The existing `update()` method uses BackgroundCamera to compute vscrollFactorBG from camera position. During the ending, we need to bypass that and pass the ending's BG Y position directly. We also need to make sure the SwScrlDez handler receives the correct vscrollFactorBG.

**Step 1: Add the updateForEnding method**

After the existing `update(int, int, Camera, int, int, Level)` overload (line 527), add:

```java
/**
 * Update parallax for the ending cutscene.
 * <p>
 * Uses camera (0,0) and a fixed BG vscroll value from the ending provider.
 * Only drives the DEZ scroll handler (SwScrlDez) since DEZ is always the
 * zone during the ending.
 *
 * @param zoneId     zone ID (should be ZONE_DEZ=10)
 * @param actId      act ID
 * @param frameCounter current frame counter
 * @param bgVscroll  ending BG vertical scroll (ROM: Camera_BG_Y_pos)
 */
public void updateForEnding(int zoneId, int actId, int frameCounter, int bgVscroll) {
    java.util.Arrays.fill(hScroll, 0);
    minScroll = Integer.MAX_VALUE;
    maxScroll = Integer.MIN_VALUE;

    // Camera is (0,0) during ending
    int cameraX = 0;
    vscrollFactorFG = 0;
    vscrollFactorBG = (short) bgVscroll;

    // Initialize zone if needed (ensures dezHandler is created)
    initZone(zoneId, actId, cameraX, 0);

    if (dezHandler != null) {
        dezHandler.setVscrollFactorBG(vscrollFactorBG);
        dezHandler.update(hScroll, cameraX, 0, frameCounter, actId);
        minScroll = dezHandler.getMinScrollOffset();
        maxScroll = dezHandler.getMaxScrollOffset();
        vscrollFactorBG = dezHandler.getVscrollFactorBG();
    }
}
```

**Step 2: Verify build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (LevelManager.renderEndingBackground can now resolve updateForEnding)

**Step 3: Run tests**

Run: `mvn test -q`
Expected: All 1392 tests pass. No existing behavior changed.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/ParallaxManager.java src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: add ending-specific parallax update and BG render path"
```

---

### Task 6: Wire Engine.display() to render ending background

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java:762-770`

**Step 1: Update the ENDING_CUTSCENE rendering block**

Replace the existing ENDING_CUTSCENE block at lines 762-770:

```java
} else if (getCurrentGameMode() == GameMode.CREDITS_TEXT
        || getCurrentGameMode() == GameMode.ENDING_CUTSCENE) {
    // Credits text / ending cutscene: screen-space rendering
    camera.setX((short) 0);
    camera.setY((short) 0);
    EndingProvider provider = gameLoop.getEndingProvider();
    if (provider != null) {
        provider.draw();
    }
```

With:

```java
} else if (getCurrentGameMode() == GameMode.ENDING_CUTSCENE) {
    // Ending cutscene: render DEZ background during sky phases, then cutscene sprites
    camera.setX((short) 0);
    camera.setY((short) 0);
    EndingProvider provider = gameLoop.getEndingProvider();
    if (provider != null) {
        if (provider.needsLevelBackground()) {
            levelManager.renderEndingBackground(provider.getBackgroundVscroll());
        }
        provider.draw();
    }
} else if (getCurrentGameMode() == GameMode.CREDITS_TEXT) {
    // Credits text: screen-space rendering (no background)
    camera.setX((short) 0);
    camera.setY((short) 0);
    EndingProvider provider = gameLoop.getEndingProvider();
    if (provider != null) {
        provider.draw();
    }
```

Note: We split the combined `CREDITS_TEXT || ENDING_CUTSCENE` condition because only ENDING_CUTSCENE needs the level background check. CREDITS_TEXT remains unchanged.

**Step 2: Verify build**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

**Step 3: Run tests**

Run: `mvn test -q`
Expected: All 1392 tests pass.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/Engine.java
git commit -m "feat: render DEZ parallax background during ending sky phases"
```

---

### Task 7: Visual verification and final commit

**Step 1: Run the game and reach the ending**

Run: `java -jar target/sonic-engine-0.4.prerelease-jar-with-dependencies.jar`

Navigate to DEZ and defeat the boss (or use debug mode to jump to the ending).

**Step 2: Verify visual behavior**

Check each phase:
- [ ] During photos (INIT → PHOTO_LOAD): solid clear color, no background visible
- [ ] During CHARACTER_APPEAR: no background (palette still fading BG colors)
- [ ] During CAMERA_SCROLL: DEZ star field appears with per-row parallax
- [ ] Stars drift at different speeds per row (matching ROM behavior)
- [ ] Background scrolls vertically as scene progresses
- [ ] During MAIN_ENDING: stars continue parallax animation behind tornado/clouds/birds
- [ ] No visual glitches at phase transitions

**Step 3: If any issues, debug and fix**

Common issues to check:
- If background doesn't appear: verify `needsLevelBackground()` returns true during correct states
- If background is offset: verify bgVscroll value ($C8 = 200 initially)
- If stars don't animate: verify SwScrlDez.updateTempArray() is being called (frameCounter incrementing)
- If wrong zone background: verify currentZone is ZONE_DEZ (10) in LevelManager

**Step 4: Final commit if fixes were needed**

```bash
git add -A
git commit -m "fix: ending parallax background visual adjustments"
```
