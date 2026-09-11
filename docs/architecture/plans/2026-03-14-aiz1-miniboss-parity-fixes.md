# AIZ1 Miniboss Parity Fixes Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three ROM-parity bugs in the AIZ1 fake-out miniboss encounter: missing explosion sequence, wrong explosion SFX, and fire curtain wave-gap / insufficient height.

**Architecture:** Create a S3K-specific boss explosion controller (plain Java object, NOT an ObjectInstance) that spawns timed child explosions at screen center every 2 frames (replacing the single S2 explosion). Fix the fire curtain renderer's wave-clip calculation and extend the fire tile zone with wrapping to prevent gaps and short curtain.

**Note on explosion art:** S3K's `Obj_BossExplosion2` uses `ArtTile_BossExplosion2` which is the same `ArtNem_BossExplosion` Nemesis-compressed data as S2. The existing `getBossExplosionRenderer()` returns the correct art for S3K explosions.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, no new dependencies.

**ROM References:**
- `Obj_BossExplosionSpecial` (sonic3k.asm:176812) — explosion controller
- `Obj_BossExpControl2` (sonic3k.asm:176792) — spawns children every 2 frames
- `Obj_BossExplosion2` (sonic3k.asm:176840) — individual explosion with `sfx_Explode` (0xB4)
- `CreateBossExp02` (sonic3k.asm:176703) — timer=$28, range=±$80, routine set $18
- `AIZTrans_WavyFlame` (Screen Events.asm:702-729) — VDP Vscroll-based fire wave
- `AIZ_FlameVScroll` (Screen Events.asm:740) — wave amplitude table (max -15px)

---

## Chunk 1: S3K Boss Explosion Controller

### Task 1: Create S3kBossExplosionController

The ROM's `Obj_BossExplosionSpecial` + `Obj_BossExpControl2` is a persistent object that:
1. Positions at screen center (Camera_X + 160, Camera_Y + 112)
2. Spawns one `Obj_BossExplosion2` child every 2 frames
3. Each child gets a random offset within ±range from the controller position
4. Controller self-destructs after timer expires (40 frames for subtype 2)
5. Each child plays `sfx_Explode` (0xB4) and animates through explosion frames

The controller is a plain Java object (not an ObjectInstance) owned by the cutscene miniboss. This avoids ObjectManager update-ordering issues and keeps the design simple — the controller just coordinates timing, the miniboss ticks it directly.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kBossExplosionController.java`
- Test: `src/test/java/com/openggf/game/sonic3k/objects/TestS3kBossExplosionController.java`

- [ ] **Step 1: Write failing test for controller lifecycle**

```java
package com.openggf.game.sonic3k.objects;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestS3kBossExplosionController {

    @Test
    public void controllerSpawnsExplosionsEveryTwoFrames() {
        // subtype 2: timer=0x28 (40), xRange=0x80, yRange=0x80
        var controller = new S3kBossExplosionController(160, 112, 2);

        int spawnCount = 0;
        for (int frame = 0; frame < 40; frame++) {
            controller.tick();
            spawnCount += controller.drainPendingExplosions().size();
        }

        // Timer=0x28 (40), spawn every 2 frames → 20 explosions
        assertEquals("Should spawn exactly 20 explosions", 20, spawnCount);
    }

    @Test
    public void controllerIsFinishedAfterTimerExpires() {
        var controller = new S3kBossExplosionController(160, 112, 2);
        for (int frame = 0; frame < 42; frame++) {
            controller.tick();
            controller.drainPendingExplosions();
        }
        assertTrue("Controller should be finished after timer", controller.isFinished());
    }

    @Test
    public void noExplosionsOnOddFrames() {
        var controller = new S3kBossExplosionController(160, 112, 2);

        // Frame 0 (even): should spawn
        controller.tick();
        assertEquals(1, controller.drainPendingExplosions().size());

        // Frame 1 (odd): should NOT spawn
        controller.tick();
        assertEquals(0, controller.drainPendingExplosions().size());

        // Frame 2 (even): should spawn
        controller.tick();
        assertEquals(1, controller.drainPendingExplosions().size());
    }

    @Test
    public void explosionOffsetsAreWithinRange() {
        var controller = new S3kBossExplosionController(160, 112, 2);
        controller.tick();

        for (var explosion : controller.drainPendingExplosions()) {
            int dx = Math.abs(explosion.x() - 160);
            int dy = Math.abs(explosion.y() - 112);
            assertTrue("X offset within ±0x80", dx <= 0x80);
            assertTrue("Y offset within ±0x80", dy <= 0x80);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestS3kBossExplosionController -pl . -q`
Expected: FAIL — class not found

- [ ] **Step 3: Implement S3kBossExplosionController**

ROM logic from `Obj_CreateBossExplosion` (sonic3k.asm:176654-176718) + `Obj_BossExpControl2` (176792-176809):

```java
package com.openggf.game.sonic3k.objects;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * S3K boss explosion controller (ROM: Obj_BossExplosionSpecial + Obj_BossExpControl2).
 *
 * Plain Java object (not an ObjectInstance) — ticked directly by the owning boss.
 * Spawns child explosions at random offsets from a fixed position every 2 frames
 * until the timer expires.
 *
 * ROM: CreateBossExp02 parameters: timer=$28 (40), xRange=$80, yRange=$80, routineSet=$18
 * Routine set $18 → Obj_Wait / Obj_BossExpControl2
 */
public class S3kBossExplosionController {
    // ROM: CreateBossExpParameterIndex entries — {timer, xRange, yRange}
    private static final int[][] SUBTYPE_PARAMS = {
            {0x20, 0x20, 0x20}, // subtype 0 (CreateBossExp00)
            {0x28, 0x80, 0x80}, // subtype 2 (CreateBossExp02) — used by AIZ cutscene
            {0x80, 0x20, 0x20}, // subtype 4 (CreateBossExp04)
            {0x04, 0x10, 0x10}, // subtype 6 (CreateBossExp06)
    };

    // ROM: move.w #2,$2E(a0) — Obj_BossExpControl2 spawns every 2 frames
    private static final int SPAWN_INTERVAL = 2;

    private final int centreX;
    private final int centreY;
    private final int xRange;
    private final int yRange;
    private int timer;
    private int frameCount;
    private final List<PendingExplosion> pendingExplosions = new ArrayList<>();

    public record PendingExplosion(int x, int y) {}

    public S3kBossExplosionController(int centreX, int centreY, int subtype) {
        this.centreX = centreX;
        this.centreY = centreY;

        int paramIndex = Math.min((subtype & 0xFF) >> 1, SUBTYPE_PARAMS.length - 1);
        int[] params = SUBTYPE_PARAMS[paramIndex];
        this.timer = params[0];
        this.xRange = params[1];
        this.yRange = params[2];
        this.frameCount = 0;
    }

    /** Advance one frame. Call from the owning boss's updateBossLogic(). */
    public void tick() {
        if (timer <= 0) {
            return;
        }
        timer--;
        // ROM: Obj_BossExpControl2 uses $2E as sub-frame counter, spawning every 2 frames
        if (frameCount % SPAWN_INTERVAL == 0) {
            spawnExplosionChild();
        }
        frameCount++;
    }

    public boolean isFinished() {
        return timer <= 0;
    }

    private void spawnExplosionChild() {
        // ROM: sub_83E84 random offset calculation (sonic3k.asm:176745-176768)
        // Uses bitmask: andi.w #(range*2-1),d0 then sub range
        int random = ThreadLocalRandom.current().nextInt(0x10000);
        int xMask = (xRange * 2) - 1;
        int yMask = (yRange * 2) - 1;
        int xOffset = (random & xMask) - xRange;
        int yOffset = ((random >> 8) & yMask) - yRange;

        pendingExplosions.add(new PendingExplosion(centreX + xOffset, centreY + yOffset));
    }

    /** Drain all pending explosions. Caller creates the actual object instances. */
    public List<PendingExplosion> drainPendingExplosions() {
        List<PendingExplosion> result = List.copyOf(pendingExplosions);
        pendingExplosions.clear();
        return result;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=TestS3kBossExplosionController -pl . -q`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kBossExplosionController.java \
       src/test/java/com/openggf/game/sonic3k/objects/TestS3kBossExplosionController.java
git commit -m "feat(s3k): add S3K boss explosion controller (ROM: Obj_BossExplosionSpecial)"
```

### Task 2: Create S3kBossExplosionChild

Individual explosion child that plays S3K SFX and animates. ROM: `Obj_BossExplosion2` (sonic3k.asm:176840).

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/S3kBossExplosionChild.java`

- [ ] **Step 1: Write the S3kBossExplosionChild class**

```java
package com.openggf.game.sonic3k.objects;

import com.openggf.audio.AudioManager;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K boss explosion child (ROM: Obj_BossExplosion2).
 * Plays sfx_Explode (0xB4) on init, animates through AniRaw_BossExplosion frames.
 *
 * ROM animation (sonic3k.asm:176871 AniRaw_BossExplosion):
 *   0, 0, 0, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 4, $F4
 * Uses ArtTile_BossExplosion2 (palette 0, priority 1).
 */
public class S3kBossExplosionChild extends AbstractObjectInstance {
    // ROM: AniRaw_BossExplosion — frame index per animation tick
    private static final int[] ANIM_SEQUENCE = {0, 0, 0, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 4};

    private int animTick;
    private boolean sfxPlayed;

    public S3kBossExplosionChild(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "S3kBossExplosion");
        this.animTick = 0;
        this.sfxPlayed = false;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (!sfxPlayed) {
            AudioManager.getInstance().playSfx(Sonic3kSfx.EXPLODE.id);
            sfxPlayed = true;
        }
        animTick++;
        if (animTick >= ANIM_SEQUENCE.length) {
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || animTick >= ANIM_SEQUENCE.length) {
            return;
        }
        ObjectRenderManager rm = LevelManager.getInstance().getObjectRenderManager();
        if (rm == null) {
            return;
        }
        PatternSpriteRenderer renderer = rm.getBossExplosionRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(ANIM_SEQUENCE[animTick], spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 1;
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/S3kBossExplosionChild.java
git commit -m "feat(s3k): add S3K boss explosion child with sfx_Explode (0xB4)"
```

### Task 3: Wire Explosion Controller into Cutscene Miniboss

Replace the single `spawnDefeatExplosion()` call with the S3K explosion controller. The controller is a plain object ticked directly by the miniboss — no ObjectManager registration needed.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java`

- [ ] **Step 1: Add explosion controller field and update method**

In `AizMinibossCutsceneInstance.java`:

1. Add field after line 67 (`private int waitTimer = -1;`):
```java
private S3kBossExplosionController explosionController;
```

2. Replace `onSwingComplete()` (line 184-187) — create the controller instead of one explosion:
```java
private void onSwingComplete() {
    setWait(PRE_EXIT_TIME, this::onPreExitComplete);
    Camera camera = Camera.getInstance();
    // ROM: Obj_BossExplosionSpecial positions at screen center
    explosionController = new S3kBossExplosionController(
            camera.getX() + 160, camera.getY() + 112, 2);
}
```

3. In `updateBossLogic()` (line 115-126), add explosion ticking AFTER the switch statement:
```java
@Override
protected void updateBossLogic(int frameCounter, AbstractPlayableSprite player) {
    switch (state.routine) {
        // ... existing cases unchanged ...
    }
    tickExplosionController();
}
```

4. Add the tick method:
```java
private void tickExplosionController() {
    if (explosionController == null || explosionController.isFinished()) {
        return;
    }
    explosionController.tick();
    var objectManager = levelManager.getObjectManager();
    if (objectManager == null) {
        return;
    }
    for (var pending : explosionController.drainPendingExplosions()) {
        objectManager.addDynamicObject(new S3kBossExplosionChild(pending.x(), pending.y()));
    }
}
```

- [ ] **Step 2: Verify the old `spawnDefeatExplosion()` call at line 186 is removed**

The inherited `spawnDefeatExplosion()` is no longer called. No import changes needed.

- [ ] **Step 3: Build and run existing tests**

Run: `mvn test -pl . -q`
Expected: All existing tests pass (the cutscene object doesn't have dedicated unit tests, but no regressions)

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java
git commit -m "fix(s3k): AIZ cutscene miniboss uses S3K explosion controller with correct SFX"
```

---

## Chunk 2: Fire Curtain Wave Gap and Height Extension

### Task 4: Fix Wave Clip Direction in Fire Curtain Renderer

The `clipTop` calculation in all plan-building methods pushes the clip DOWN when wave offset is negative, creating a gap at the top of the curtain. The ROM's VDP Vscroll fills the entire BG column with fire tiles, so no gap is possible.

**Fix:** When the wave offset shifts fire content UP (negative offset), the clip boundary must also extend UP (lower screen Y) to prevent gaps. Additionally, the fire tile row bounds check (`bgTileY < FIRE_TILE_START_BG_Y || bgTileY >= FIRE_TILE_END_BG_Y`) must be relaxed to allow wrapping/extension.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/features/AizFireCurtainRenderer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRenderer.java`

- [ ] **Step 1: Write failing test for wave-gap coverage**

Add to `TestAizFireCurtainRenderer.java`:

```java
@Test
public void negativeWaveOffsetDoesNotCreateGapAtCurtainTop() {
    AizFireCurtainRenderer renderer = rendererWithSampler();
    // Full-screen curtain with strong negative wave offsets
    int[] waveOffsets = new int[20];
    for (int i = 0; i < 20; i++) {
        waveOffsets[i] = -15; // Maximum wave amplitude from ROM
    }
    FireCurtainRenderState state = new FireCurtainRenderState(
            true,
            224, // full screen coverage
            0,
            8,
            0x1000,
            0x0180,
            waveOffsets,
            FireCurtainStage.AIZ1_RISING);

    AizFireCurtainRenderer.CurtainCompositionPlan plan =
            renderer.buildCompositionPlan(state, 320, 224);

    for (AizFireCurtainRenderer.ColumnRenderPlan column : plan.columns()) {
        // The column's topY must be at or above 0 (screen top)
        // when coverHeight == screenHeight, regardless of wave offset
        assertTrue("Column " + column.columnIndex()
                        + " has gap at top: topY=" + column.topY(),
                column.topY() <= 0);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestAizFireCurtainRenderer#negativeWaveOffsetDoesNotCreateGapAtCurtainTop -pl . -q`
Expected: FAIL — topY > 0 for columns with negative wave offsets

- [ ] **Step 3: Fix clipTop calculation in all plan-building methods**

In `AizFireCurtainRenderer.java`, the bug is on every line that computes `clipTop`:

**Current (buggy)** — appears at lines 120-121, 183-184, 288-289, 358-359:
```java
int clipWaveOffset = state.fullyOpaqueToGameplay() ? 0 : waveOffset;
int clipTop = clamp(baseTop - clipWaveOffset, 0, screenHeight);
```

When `waveOffset = -15`: `clipTop = baseTop - (-15) = baseTop + 15` → clip moves DOWN → gap.

**Fixed** — replace the two lines in ALL FOUR plan-building methods (`buildSampledPlan`, `buildBackgroundSampledPlan`, `buildCachedPlan`, `buildFireOverlayTilePlan`):
```java
int clipTop = clamp(baseTop + waveOffset, 0, screenHeight);
```

The wave offset shifts fire content vertically; the clip must follow in the same direction. Negative offset = content moves up = clip extends up. The `fullyOpaqueToGameplay()` branch is no longer needed — in post-RISING stages (where it returns true), `coverHeightPx == screenHeight` so `baseTop = 0`, and `clamp(0 + waveOffset, 0, screenHeight)` = 0 for any negative offset. The result is identical to the old `clipWaveOffset = 0` path, so removing the branch changes nothing for those stages while fixing RISING.

- [ ] **Step 4: Run tests to verify fix**

Run: `mvn test -Dtest=TestAizFireCurtainRenderer -pl . -q`
Expected: New test PASSES. Check that existing `waveOffsetsChangeColumnTopDeterministically` test still passes — if it asserts specific topY values that depend on the old (buggy) calculation, update those expected values to match the correct behavior.

**Note:** The existing test at line 101 asserts `assertEquals(160, wavyPlan.columns().get(4).topY())`. With the fix, column 4 has `waveOffset = -8`, so `clipTop = (224-72) + (-8) = 144`. Update expected value from `160` to `144`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/features/AizFireCurtainRenderer.java \
       src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRenderer.java
git commit -m "fix(s3k): fire curtain wave clip extends upward with negative offsets"
```

### Task 5: Extend Fire Tile Zone with Wrapping

The ROM's VDP wraps the BG plane, so fire tiles at Y 0x100–0x310 repeat infinitely. Our renderer clips at `FIRE_TILE_END_BG_Y` (0x310), causing the curtain to end abruptly. Extend the fire tile zone by wrapping fire row indices so tiles repeat beyond the original 0x100–0x310 range.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/features/AizFireCurtainRenderer.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRenderer.java`

- [ ] **Step 1: Write failing test for extended fire zone**

Add to `TestAizFireCurtainRenderer.java`:

```java
@Test
public void fireTilesWrapBeyondOriginalZoneBoundaries() {
    AizFireCurtainRenderer renderer = rendererWithSampler();
    // sourceWorldY well past the fire zone end (0x310) — should still produce tiles
    FireCurtainRenderState state = new FireCurtainRenderState(
            true,
            224,
            0,
            8,
            0x1000,
            0x0400, // BG Y = 0x400, well past FIRE_TILE_END_BG_Y (0x310)
            new int[20],
            FireCurtainStage.AIZ1_REFRESH,
            0x500,
            121);

    AizFireCurtainRenderer.CurtainCompositionPlan plan =
            renderer.buildCompositionPlan(state, 320, 224);

    assertFalse("Should still produce fire tiles via wrapping when bgY > 0x310",
            plan.columns().isEmpty());
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestAizFireCurtainRenderer#fireTilesWrapBeyondOriginalZoneBoundaries -pl . -q`
Expected: FAIL — empty columns because all bgTileY values fall outside 0x100–0x310

- [ ] **Step 3: Add fire zone wrapping constants and helper**

In `AizFireCurtainRenderer.java`, add a constant and helper method:

Add helper method (after `forceFirePalette`):
```java
/**
 * Wraps a BG tile Y coordinate into the fire zone [FIRE_TILE_START_BG_Y, FIRE_TILE_END_BG_Y).
 * Emulates VDP BG plane wrapping so fire tiles repeat beyond the original range.
 * Returns -1 if the input is below the fire zone start (pre-fire region).
 */
private static int wrapFireTileY(int bgTileY) {
    if (bgTileY < FIRE_TILE_START_BG_Y) {
        return -1;
    }
    int offset = bgTileY - FIRE_TILE_START_BG_Y;
    int fireHeight = FIRE_TILE_END_BG_Y - FIRE_TILE_START_BG_Y;
    int wrapped = ((offset % fireHeight) + fireHeight) % fireHeight;
    return FIRE_TILE_START_BG_Y + wrapped;
}
```

- [ ] **Step 4: Apply wrapping in buildCachedPlan**

In `buildCachedPlan()` (line 302-305), replace the hard boundary check:

**Current:**
```java
if (bgTileY < FIRE_TILE_START_BG_Y || bgTileY >= FIRE_TILE_END_BG_Y) {
    continue;
}
```

**Fixed:**
```java
int wrappedTileY = wrapFireTileY(bgTileY);
if (wrappedTileY < 0) {
    continue;
}
int fireRow = (wrappedTileY - FIRE_TILE_START_BG_Y) / TILE_SIZE;
```

And remove the separate `fireRow` calculation at line 311 (it's now computed above).

- [ ] **Step 5: Apply wrapping in buildBackgroundSampledPlan**

In `buildBackgroundSampledPlan()` (line 203-205), replace:

**Current:**
```java
if (bgTileY < FIRE_TILE_START_BG_Y || bgTileY >= FIRE_TILE_END_BG_Y) {
    continue;
}
```

**Fixed:**
```java
int wrappedTileY = wrapFireTileY(bgTileY);
if (wrappedTileY < 0) {
    continue;
}
```

Then update the `sampleBackgroundStripDescriptor` call to use `wrappedTileY` instead of `bgTileY`.

- [ ] **Step 6: Apply wrapping in buildFireOverlayTilePlan**

In `buildFireOverlayTilePlan()` (line 375-376), replace:

**Current:**
```java
if (bgTileY < FIRE_TILE_START_BG_Y || bgTileY >= FIRE_TILE_END_BG_Y) {
    continue;
}
```

**Fixed:**
```java
int wrappedTileY = wrapFireTileY(bgTileY);
if (wrappedTileY < 0) {
    continue;
}
```

Update the `bgRowIndex` used for tile selection to use `wrappedTileY / TILE_SIZE`.

- [ ] **Step 7: Run all fire curtain tests**

Run: `mvn test -Dtest=TestAizFireCurtainRenderer -pl . -q`
Expected: ALL tests PASS including the new wrapping test

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/features/AizFireCurtainRenderer.java \
       src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRenderer.java
git commit -m "fix(s3k): fire curtain wraps tile zone for VDP-accurate height"
```

### Task 6: Verify All Existing Tests Pass

- [ ] **Step 1: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass. Pay attention to:
- `TestAizFireCurtainRenderer` — all old + new tests
- `TestAizFireCurtainRendererRom` — ROM-based fire tests
- `TestS3kAiz1SkipHeadless` — AIZ1 skip-intro headless test
- `TestSonic3kLevelLoading` — S3K level loading

- [ ] **Step 2: Commit if any test adjustments were needed**

Only commit if test expected values needed updating for the corrected clip behavior.
