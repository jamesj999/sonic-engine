# Sonic 3&K Special Stage Rewind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic held-key live rewind support to the Sonic 3&K Blue Spheres special stage.

**Architecture:** Reuse the shared special-stage rewind registry path: `Sonic3kSpecialStageProvider` exposes a provider-owned `RewindSnapshottable` under `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`, and `GameplayModeContext` registers it with `RewindRegistry`. Keep all S3K state capture package-local under `com.openggf.game.sonic3k.specialstage`; do not add GameLoop, SpecialStageStepper, or shared rewind-controller special cases.

**Tech Stack:** Java 17, JUnit 5, Maven, existing `RewindSnapshottable`, existing `SpecialStageProvider`, existing S3K Blue Spheres manager/subsystem classes.

---

## Source Spec

Implement from:

- `docs/architecture/designs/2026-07-09-sonic3k-special-stage-rewind-design.md`

Do not implement S3K results-screen rewind in this plan. Results screen remains a mode boundary.

## File Structure

Create:

- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java`
  - Package-private immutable snapshot container with nested records and array/palette clone helpers.
  - Holds manager fields, gameplay subsystem snapshots, visual/UI subsystem snapshots, and optional `GameStateSnapshot`.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRewindAdapter.java`
  - Public adapter implementing `RewindSnapshottable<Sonic3kSpecialStageSnapshot>`.
- `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageRewindAdapter.java`
  - Adapter key and uninitialized capture/restore behavior.
- `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageGameplaySnapshot.java`
  - Grid, player, Tails AI, and collision queue snapshot tests.
- `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageVisualSnapshot.java`
  - Perspective, background, HUD, banner, palette, and ring-converter snapshot tests.
- `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageManagerSnapshot.java`
  - Manager-level capture/restore tests for representative cross-subsystem state.

Modify:

- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageProvider.java`
  - Return rewind support and adapter only after manager snapshot is complete.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
  - Add package-local `captureRewindSnapshot()` and `restoreRewindSnapshot(...)`.
  - Restore game-state snapshot, audio tempo, palette cache, and final subsystem state without replaying SFX.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageGrid.java`
  - Add package-local full-buffer capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePlayer.java`
  - Add package-local full player capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageTailsAI.java`
  - Add package-local delay-buffer capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageCollisionQueue.java`
  - Add package-local queue array capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRingConverter.java`
  - Add package-local capture/restore for `seedBlueConverted`.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePerspective.java`
  - Add package-local frame capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBackground.java`
  - Add package-local scroll capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePalette.java`
  - Add package-local palette capture/restore with deep palette copies.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageHud.java`
  - Add package-local HUD capture/restore.
- `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBanner.java`
  - Add package-local banner capture/restore.
- `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`
  - Update S3K expectation from unsupported to supported.
- `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`
  - Add S3K adapter key/registration coverage that does not call capture on an uninitialized manager.
- `CHANGELOG.md`
  - Add one Unreleased entry in Task 1 for the complete S3K Blue Spheres rewind feature. Later commits use explicit `Changelog: n/a` trailer reasons that point back to this entry.

Do not modify:

- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/game/rewind/SpecialStageStepper.java`
- `src/main/java/com/openggf/game/SpecialStageProvider.java`
- Sonic 1 or Sonic 2 special-stage runtime code
- S3K results-screen runtime code

## Task 1: Snapshot Container And Adapter Shell

**Files:**

- Create: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java`
- Create: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRewindAdapter.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageRewindAdapter.java`

- [ ] **Step 1: Write the failing adapter shell test**

Create `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageRewindAdapter.java`:

```java
package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSonic3kSpecialStageRewindAdapter {
    @Test
    void adapterUsesGenericSpecialStageKeyAndKeepsMissingSnapshotDefault() {
        Sonic3kSpecialStageRewindAdapter adapter =
                new Sonic3kSpecialStageRewindAdapter(new Sonic3kSpecialStageManager());

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
    }

    @Test
    void captureAndRestoreFailBeforeManagerIsInitialized() {
        Sonic3kSpecialStageRewindAdapter adapter =
                new Sonic3kSpecialStageRewindAdapter(new Sonic3kSpecialStageManager());
        Sonic3kSpecialStageSnapshot emptySnapshot = Sonic3kSpecialStageSnapshot.uninitializedForTest();

        assertThrows(IllegalStateException.class, adapter::capture);
        assertThrows(IllegalStateException.class, () -> adapter.restore(emptySnapshot));
    }
}
```

- [ ] **Step 2: Run the failing adapter shell test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageRewindAdapter" test
```

Expected: compile failure because `Sonic3kSpecialStageSnapshot` and `Sonic3kSpecialStageRewindAdapter` do not exist.

- [ ] **Step 3: Add `Sonic3kSpecialStageSnapshot` shell**

Create `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java`:

```java
package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.snapshot.GameStateSnapshot;
import com.openggf.level.Palette;

import java.util.Arrays;

record Sonic3kSpecialStageSnapshot(
        int currentStage,
        boolean initialized,
        boolean finished,
        boolean emeraldCollected,
        boolean superEmeraldMode,
        int ringsCollected,
        int spheresLeft,
        int ringsLeft,
        int frameCounter,
        int heldButtons,
        int pressedButtons,
        int p2HeldButtons,
        int clearRoutine,
        int clearTimer,
        int emeraldTimer,
        int emeraldInteractIndex,
        boolean exitSpinStarted,
        int palFadeDelay,
        boolean musicSpedUp,
        int ringAnimTimer,
        int ringAnimFrame,
        int bannerPhase,
        int bannerTimer,
        int bannerOffset,
        int tailsAnimTimer,
        int tailsMappingFrame,
        int tailsTailsAnimTimer,
        int tailsTailsMappingFrame,
        int tailsJumping,
        long tailsJumpHeight,
        long tailsJumpVelocity,
        boolean tailsEnabled,
        PlayerCharacter playerCharacter,
        boolean spriteDebugMode,
        boolean useSkLayouts,
        GameStateSnapshot gameState,
        GridSnapshot grid,
        PlayerSnapshot player,
        TailsAiSnapshot tailsAi,
        CollisionQueueSnapshot collisionQueue,
        RingConverterSnapshot ringConverter,
        PerspectiveSnapshot perspective,
        BackgroundSnapshot background,
        HudSnapshot hud,
        BannerSnapshot banner,
        PaletteSnapshot palette) {

    static Sonic3kSpecialStageSnapshot uninitializedForTest() {
        return new Sonic3kSpecialStageSnapshot(
                0, false, false, false, false,
                0, 0, 0, 0,
                0, 0, 0,
                0, 0, 0, 0, false, 0, false,
                0, 0, 0, 0, 0,
                0, 0, 0, 1, 0, 0L, 0L,
                true,
                PlayerCharacter.SONIC_AND_TAILS,
                false, false, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    static int[] cloneIntArray(int[] source) {
        return source != null ? source.clone() : null;
    }

    static void copyInto(int[] source, int[] target) {
        Arrays.fill(target, 0);
        if (source != null) {
            System.arraycopy(source, 0, target, 0, Math.min(source.length, target.length));
        }
    }

    static Palette[] clonePalettes(Palette[] source) {
        if (source == null) {
            return null;
        }
        Palette[] copy = new Palette[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i] != null ? source[i].deepCopy() : null;
        }
        return copy;
    }

    static void copyPalettesInto(Palette[] source, Palette[] target) {
        if (source == null || target == null) {
            return;
        }
        for (int i = 0; i < target.length && i < source.length; i++) {
            target[i] = source[i] != null ? source[i].deepCopy() : null;
        }
    }

    record GridSnapshot(int[] buffer) {
        GridSnapshot {
            buffer = Sonic3kSpecialStageSnapshot.cloneIntArray(buffer);
        }
    }

    record PlayerSnapshot() { }
    record TailsAiSnapshot() { }
    record CollisionQueueSnapshot() { }
    record RingConverterSnapshot(int seedBlueConverted) { }
    record PerspectiveSnapshot(int animFrame, int paletteFrame) { }
    record BackgroundSnapshot(int vScroll, int hScroll, int prevXPos, int prevYPos) { }
    record HudSnapshot(boolean sphereHudDirty, boolean ringHudDirty,
                       int displayedSphereCount, int displayedRingCount) { }
    record BannerSnapshot(Sonic3kSpecialStageBanner.Phase phase, int slideOffset,
                          int displayTimer, boolean triggeredAdvance, boolean showPerfect) { }
    record PaletteSnapshot(Palette[] palettes, byte[] stagePaletteData, boolean fadeActive) {
        PaletteSnapshot {
            palettes = Sonic3kSpecialStageSnapshot.clonePalettes(palettes);
            stagePaletteData = stagePaletteData != null ? stagePaletteData.clone() : null;
        }
    }
}
```

- [ ] **Step 4: Add `Sonic3kSpecialStageRewindAdapter`**

Create `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRewindAdapter.java`:

```java
package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Objects;

public final class Sonic3kSpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic3kSpecialStageSnapshot> {
    private final Sonic3kSpecialStageManager manager;

    public Sonic3kSpecialStageRewindAdapter(Sonic3kSpecialStageManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public String key() {
        return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
    }

    @Override
    public Sonic3kSpecialStageSnapshot capture() {
        return manager.captureRewindSnapshot();
    }

    @Override
    public void restore(Sonic3kSpecialStageSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot);
    }
}
```

- [ ] **Step 5: Add temporary manager guard methods**

Edit `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java` and add these package-local methods near the lifecycle/getter block:

```java
Sonic3kSpecialStageSnapshot captureRewindSnapshot() {
    if (!initialized) {
        throw new IllegalStateException("Cannot capture S3K special-stage rewind state before initialization");
    }
    return Sonic3kSpecialStageSnapshot.uninitializedForTest();
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot snapshot) {
    if (!initialized || snapshot == null || !snapshot.initialized()) {
        throw new IllegalStateException("Cannot restore S3K special-stage rewind state before initialization");
    }
}
```

Task 4 replaces the temporary return value with the full manager snapshot.

- [ ] **Step 6: Run the adapter shell test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageRewindAdapter" test
```

Expected: pass.

- [ ] **Step 7: Commit Task 1**

Add a concise `CHANGELOG.md` entry under Unreleased for S3K Blue Spheres rewind support, then run:

```powershell
git add src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRewindAdapter.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java `
        src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageRewindAdapter.java `
        CHANGELOG.md
git commit -m "feat: add S3K special stage rewind adapter shell" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Task 2: Gameplay Runtime Snapshots

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageGrid.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageTailsAI.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageCollisionQueue.java`
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageGameplaySnapshot.java`

- [ ] **Step 1: Write failing gameplay snapshot tests**

Create `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageGameplaySnapshot.java`:

```java
package com.openggf.game.sonic3k.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestSonic3kSpecialStageGameplaySnapshot {
    @Test
    void gridSnapshotClonesAndRestoresBuffer() {
        Sonic3kSpecialStageGrid grid = new Sonic3kSpecialStageGrid();
        grid.setCellByIndex(0x20, Sonic3kSpecialStageConstants.CELL_BLUE);
        grid.setCellByIndex(0x21, Sonic3kSpecialStageConstants.CELL_RING);

        Sonic3kSpecialStageSnapshot.GridSnapshot snapshot = grid.captureRewindSnapshot();
        grid.setCellByIndex(0x20, Sonic3kSpecialStageConstants.CELL_RED);
        grid.setCellByIndex(0x21, Sonic3kSpecialStageConstants.CELL_EMPTY);

        grid.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic3kSpecialStageConstants.CELL_BLUE, grid.getCellByIndex(0x20));
        assertEquals(Sonic3kSpecialStageConstants.CELL_RING, grid.getCellByIndex(0x21));
        assertNotSame(snapshot.buffer(), liveIntArray(grid, "buffer"));
    }

    @Test
    void playerSnapshotRestoresMovementJumpAnimationAndLatchFields() throws Exception {
        Sonic3kSpecialStagePlayer player = new Sonic3kSpecialStagePlayer();
        player.initialize(0x4000, 0x1234, 0x2345, false);
        set(player, "velocity", 0x1800);
        set(player, "rate", 0x1C00);
        set(player, "rateTimer", 11);
        set(player, "turning", Sonic3kSpecialStageConstants.TURN_LEFT);
        set(player, "turnLock", true);
        set(player, "advancing", true);
        set(player, "started", true);
        set(player, "bumperLock", true);
        set(player, "bumperInteractIndex", 0x155);
        set(player, "jumping", Sonic3kSpecialStageConstants.JUMP_SPRING);
        set(player, "jumpHeight", -0x120000L);
        set(player, "jumpVelocity", -0x20000L);
        set(player, "animFrameTimer", 0x4500);
        set(player, "mappingFrame", 7);
        set(player, "prevMappingFrame", 6);
        set(player, "failed", true);
        set(player, "clearRoutineActive", true);
        set(player, "fadeTimer", 33);
        set(player, "blueSphereMode", true);
        set(player, "rateJustIncreased", true);

        Sonic3kSpecialStageSnapshot.PlayerSnapshot snapshot = player.captureRewindSnapshot();

        player.initialize(0, 0, 0, false);
        player.restoreRewindSnapshot(snapshot);

        assertEquals(0x1234, player.getXPos());
        assertEquals(0x2345, player.getYPos());
        assertEquals(0x40, player.getAngle());
        assertEquals(0x1800, player.getVelocity());
        assertEquals(Sonic3kSpecialStageConstants.JUMP_SPRING, player.getJumping());
        assertEquals(-0x120000L, player.getJumpHeight());
        assertEquals(7, player.getMappingFrame());
        assertEquals(6, player.getPrevMappingFrame());
        assertEquals(true, get(player, "rateJustIncreased"));
    }

    @Test
    void tailsAiSnapshotRestoresDelayBuffersAndIdleState() throws Exception {
        Sonic3kSpecialStageTailsAI ai = new Sonic3kSpecialStageTailsAI();
        ai.initialize();
        ai.update(0x11, 0x80, 0);
        ai.update(0x22, 0, 0x10);
        set(ai, "cpuIdleTimer", 77);
        set(ai, "lastP2Input", 0x10);

        Sonic3kSpecialStageSnapshot.TailsAiSnapshot snapshot = ai.captureRewindSnapshot();

        ai.initialize();
        ai.restoreRewindSnapshot(snapshot);

        assertEquals(2, get(ai, "posTableIndex"));
        assertEquals(77, get(ai, "cpuIdleTimer"));
        assertEquals(0x10, get(ai, "lastP2Input"));
        assertArrayEquals(snapshot.posTableInput(), liveIntArray(ai, "posTableInput"));
        assertArrayEquals(snapshot.posTableJump(), liveIntArray(ai, "posTableJump"));
        assertNotSame(snapshot.posTableInput(), liveIntArray(ai, "posTableInput"));
    }

    @Test
    void collisionQueueSnapshotRestoresAllQueueArrays() throws Exception {
        Sonic3kSpecialStageCollisionQueue queue = new Sonic3kSpecialStageCollisionQueue();
        queue.addRing(0x22);
        queue.addBlueSphere(0x44);

        Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot snapshot = queue.captureRewindSnapshot();
        queue.clear();
        queue.restoreRewindSnapshot(snapshot);

        assertArrayEquals(snapshot.types(), liveIntArray(queue, "types"));
        assertArrayEquals(snapshot.timers(), liveIntArray(queue, "timers"));
        assertArrayEquals(snapshot.frames(), liveIntArray(queue, "frames"));
        assertArrayEquals(snapshot.gridIndices(), liveIntArray(queue, "gridIndices"));
    }

    private static int[] liveIntArray(Object target, String field) {
        return (int[]) get(target, field);
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
```

- [ ] **Step 2: Run the failing gameplay snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageGameplaySnapshot" test
```

Expected: compile failure because gameplay capture/restore methods and full nested snapshot records do not exist.

- [ ] **Step 3: Expand gameplay snapshot records**

Edit `Sonic3kSpecialStageSnapshot.java` and replace the empty gameplay nested records with:

```java
record PlayerSnapshot(
        int xPos,
        int yPos,
        int angle,
        int velocity,
        int rate,
        int rateTimer,
        int turning,
        boolean turnLock,
        boolean advancing,
        boolean started,
        boolean bumperLock,
        int bumperInteractIndex,
        int jumping,
        long jumpHeight,
        long jumpVelocity,
        int animFrameTimer,
        int mappingFrame,
        int prevMappingFrame,
        boolean failed,
        boolean clearRoutineActive,
        int fadeTimer,
        boolean blueSphereMode,
        boolean rateJustIncreased) { }

record TailsAiSnapshot(int[] posTableInput, int[] posTableJump,
                       int posTableIndex, int cpuIdleTimer, int lastP2Input) {
    TailsAiSnapshot {
        posTableInput = Sonic3kSpecialStageSnapshot.cloneIntArray(posTableInput);
        posTableJump = Sonic3kSpecialStageSnapshot.cloneIntArray(posTableJump);
    }
}

record CollisionQueueSnapshot(int[] types, int[] timers, int[] frames, int[] gridIndices) {
    CollisionQueueSnapshot {
        types = Sonic3kSpecialStageSnapshot.cloneIntArray(types);
        timers = Sonic3kSpecialStageSnapshot.cloneIntArray(timers);
        frames = Sonic3kSpecialStageSnapshot.cloneIntArray(frames);
        gridIndices = Sonic3kSpecialStageSnapshot.cloneIntArray(gridIndices);
    }
}
```

- [ ] **Step 4: Implement grid capture/restore**

Edit `Sonic3kSpecialStageGrid.java`:

```java
Sonic3kSpecialStageSnapshot.GridSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.GridSnapshot(buffer);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.GridSnapshot snapshot) {
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.buffer(), buffer);
}
```

- [ ] **Step 5: Implement player capture/restore**

Edit `Sonic3kSpecialStagePlayer.java`:

```java
Sonic3kSpecialStageSnapshot.PlayerSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.PlayerSnapshot(
            xPos, yPos, angle, velocity, rate, rateTimer,
            turning, turnLock, advancing, started, bumperLock, bumperInteractIndex,
            jumping, jumpHeight, jumpVelocity,
            animFrameTimer, mappingFrame, prevMappingFrame,
            failed, clearRoutineActive, fadeTimer, blueSphereMode, rateJustIncreased);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.PlayerSnapshot snapshot) {
    xPos = snapshot.xPos();
    yPos = snapshot.yPos();
    angle = snapshot.angle();
    velocity = snapshot.velocity();
    rate = snapshot.rate();
    rateTimer = snapshot.rateTimer();
    turning = snapshot.turning();
    turnLock = snapshot.turnLock();
    advancing = snapshot.advancing();
    started = snapshot.started();
    bumperLock = snapshot.bumperLock();
    bumperInteractIndex = snapshot.bumperInteractIndex();
    jumping = snapshot.jumping();
    jumpHeight = snapshot.jumpHeight();
    jumpVelocity = snapshot.jumpVelocity();
    animFrameTimer = snapshot.animFrameTimer();
    mappingFrame = snapshot.mappingFrame();
    prevMappingFrame = snapshot.prevMappingFrame();
    failed = snapshot.failed();
    clearRoutineActive = snapshot.clearRoutineActive();
    fadeTimer = snapshot.fadeTimer();
    blueSphereMode = snapshot.blueSphereMode();
    rateJustIncreased = snapshot.rateJustIncreased();
}
```

- [ ] **Step 6: Implement Tails AI capture/restore**

Edit `Sonic3kSpecialStageTailsAI.java`:

```java
Sonic3kSpecialStageSnapshot.TailsAiSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.TailsAiSnapshot(
            posTableInput, posTableJump, posTableIndex, cpuIdleTimer, lastP2Input);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.TailsAiSnapshot snapshot) {
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.posTableInput(), posTableInput);
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.posTableJump(), posTableJump);
    posTableIndex = snapshot.posTableIndex();
    cpuIdleTimer = snapshot.cpuIdleTimer();
    lastP2Input = snapshot.lastP2Input();
}
```

- [ ] **Step 7: Implement collision queue capture/restore**

Edit `Sonic3kSpecialStageCollisionQueue.java`:

```java
Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot(
            types, timers, frames, gridIndices);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.CollisionQueueSnapshot snapshot) {
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.types(), types);
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.timers(), timers);
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.frames(), frames);
    Sonic3kSpecialStageSnapshot.copyInto(snapshot.gridIndices(), gridIndices);
}
```

- [ ] **Step 8: Run gameplay snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageGameplaySnapshot" test
```

Expected: pass.

- [ ] **Step 9: Commit Task 2**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageGrid.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePlayer.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageTailsAI.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageCollisionQueue.java `
        src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageGameplaySnapshot.java
git commit -m "feat: snapshot S3K special stage gameplay state" -m "Changelog: n/a: covered by Task 1 S3K special-stage rewind changelog entry
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Task 3: Visual, UI, Palette, And Converter Snapshots

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRingConverter.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePerspective.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBackground.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePalette.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageHud.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBanner.java`
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageVisualSnapshot.java`

- [ ] **Step 1: Write failing visual snapshot tests**

Create `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageVisualSnapshot.java`:

```java
package com.openggf.game.sonic3k.specialstage;

import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestSonic3kSpecialStageVisualSnapshot {
    @Test
    void perspectiveBackgroundHudAndBannerRestoreState() {
        Sonic3kSpecialStagePerspective perspective = new Sonic3kSpecialStagePerspective();
        set(perspective, "animFrame", 12);
        set(perspective, "paletteFrame", 8);
        Sonic3kSpecialStageSnapshot.PerspectiveSnapshot perspectiveSnapshot =
                perspective.captureRewindSnapshot();
        set(perspective, "animFrame", 1);
        set(perspective, "paletteFrame", 2);
        perspective.restoreRewindSnapshot(perspectiveSnapshot);
        assertEquals(12, perspective.getAnimFrame());
        assertEquals(8, perspective.getPaletteFrame());

        Sonic3kSpecialStageBackground background = new Sonic3kSpecialStageBackground();
        set(background, "vScroll", 40);
        set(background, "hScroll", 80);
        set(background, "prevXPos", 0x1111);
        set(background, "prevYPos", 0x2222);
        Sonic3kSpecialStageSnapshot.BackgroundSnapshot backgroundSnapshot =
                background.captureRewindSnapshot();
        background.reset();
        background.restoreRewindSnapshot(backgroundSnapshot);
        assertEquals(40, background.getVScroll());
        assertEquals(80, background.getHScroll());
        assertEquals(0x1111, get(background, "prevXPos"));
        assertEquals(0x2222, get(background, "prevYPos"));

        Sonic3kSpecialStageHud hud = new Sonic3kSpecialStageHud();
        hud.initialize();
        hud.update(17, 42);
        hud.clearSphereDirty();
        Sonic3kSpecialStageSnapshot.HudSnapshot hudSnapshot = hud.captureRewindSnapshot();
        hud.update(1, 2);
        hud.restoreRewindSnapshot(hudSnapshot);
        assertEquals(17, hud.getDisplayedSphereCount());
        assertEquals(42, hud.getDisplayedRingCount());
        assertEquals(false, hud.isSphereDirty());
        assertEquals(true, hud.isRingDirty());

        Sonic3kSpecialStageBanner banner = new Sonic3kSpecialStageBanner();
        banner.initialize();
        set(banner, "phase", Sonic3kSpecialStageBanner.Phase.SLIDING_IN);
        set(banner, "slideOffset", 33);
        set(banner, "displayTimer", 44);
        set(banner, "triggeredAdvance", true);
        set(banner, "showPerfect", true);
        Sonic3kSpecialStageSnapshot.BannerSnapshot bannerSnapshot = banner.captureRewindSnapshot();
        banner.initialize();
        banner.restoreRewindSnapshot(bannerSnapshot);
        assertEquals(Sonic3kSpecialStageBanner.Phase.SLIDING_IN, banner.getPhase());
        assertEquals(33, banner.getSlideOffset());
        assertEquals(true, banner.isShowPerfect());
    }

    @Test
    void paletteSnapshotDeepCopiesPalettesAndStageData() {
        Sonic3kSpecialStagePalette palette = new Sonic3kSpecialStagePalette();
        Palette[] livePalettes = (Palette[]) get(palette, "palettes");
        for (int i = 0; i < livePalettes.length; i++) {
            livePalettes[i] = new Palette();
        }
        byte[] stageData = new byte[]{1, 2, 3, 4};
        set(palette, "stagePaletteData", stageData);
        set(palette, "fadeActive", true);

        Sonic3kSpecialStageSnapshot.PaletteSnapshot snapshot = palette.captureRewindSnapshot();
        stageData[0] = 99;
        set(palette, "fadeActive", false);
        palette.restoreRewindSnapshot(snapshot);

        assertEquals(true, get(palette, "fadeActive"));
        assertArrayEquals(new byte[]{1, 2, 3, 4}, (byte[]) get(palette, "stagePaletteData"));
        assertNotSame(snapshot.palettes(), palette.getPalettes());
        assertNotSame(snapshot.palettes()[0], palette.getPalette(0));
    }

    @Test
    void ringConverterSnapshotRestoresSeedField() {
        Sonic3kSpecialStageRingConverter converter = new Sonic3kSpecialStageRingConverter();
        set(converter, "seedBlueConverted", 5);
        Sonic3kSpecialStageSnapshot.RingConverterSnapshot snapshot = converter.captureRewindSnapshot();
        set(converter, "seedBlueConverted", 0);
        converter.restoreRewindSnapshot(snapshot);
        assertEquals(5, get(converter, "seedBlueConverted"));
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
```

- [ ] **Step 2: Run the failing visual snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageVisualSnapshot" test
```

Expected: compile failure because visual capture/restore methods do not exist.

- [ ] **Step 3: Implement perspective, background, HUD, and banner capture/restore**

Add package-local capture/restore methods to these classes:

`Sonic3kSpecialStagePerspective.java`:

```java
Sonic3kSpecialStageSnapshot.PerspectiveSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.PerspectiveSnapshot(animFrame, paletteFrame);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.PerspectiveSnapshot snapshot) {
    animFrame = snapshot.animFrame();
    paletteFrame = snapshot.paletteFrame();
}
```

`Sonic3kSpecialStageBackground.java`:

```java
Sonic3kSpecialStageSnapshot.BackgroundSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.BackgroundSnapshot(vScroll, hScroll, prevXPos, prevYPos);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.BackgroundSnapshot snapshot) {
    vScroll = snapshot.vScroll();
    hScroll = snapshot.hScroll();
    prevXPos = snapshot.prevXPos();
    prevYPos = snapshot.prevYPos();
}
```

`Sonic3kSpecialStageHud.java`:

```java
Sonic3kSpecialStageSnapshot.HudSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.HudSnapshot(
            sphereHudDirty, ringHudDirty, displayedSphereCount, displayedRingCount);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.HudSnapshot snapshot) {
    sphereHudDirty = snapshot.sphereHudDirty();
    ringHudDirty = snapshot.ringHudDirty();
    displayedSphereCount = snapshot.displayedSphereCount();
    displayedRingCount = snapshot.displayedRingCount();
}
```

`Sonic3kSpecialStageBanner.java`:

```java
Sonic3kSpecialStageSnapshot.BannerSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.BannerSnapshot(
            phase, slideOffset, displayTimer, triggeredAdvance, showPerfect);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.BannerSnapshot snapshot) {
    phase = snapshot.phase();
    slideOffset = snapshot.slideOffset();
    displayTimer = snapshot.displayTimer();
    triggeredAdvance = snapshot.triggeredAdvance();
    showPerfect = snapshot.showPerfect();
}
```

- [ ] **Step 4: Implement palette capture/restore**

Edit `Sonic3kSpecialStagePalette.java`:

```java
Sonic3kSpecialStageSnapshot.PaletteSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.PaletteSnapshot(
            palettes,
            stagePaletteData != null ? stagePaletteData.clone() : null,
            fadeActive);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.PaletteSnapshot snapshot) {
    Sonic3kSpecialStageSnapshot.copyPalettesInto(snapshot.palettes(), palettes);
    stagePaletteData = snapshot.stagePaletteData() != null ? snapshot.stagePaletteData().clone() : null;
    fadeActive = snapshot.fadeActive();
}
```

- [ ] **Step 5: Implement ring converter capture/restore**

Edit `Sonic3kSpecialStageRingConverter.java` and add package-local capture/restore methods near the end of the class:

```java
Sonic3kSpecialStageSnapshot.RingConverterSnapshot captureRewindSnapshot() {
    return new Sonic3kSpecialStageSnapshot.RingConverterSnapshot(seedBlueConverted);
}

void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.RingConverterSnapshot snapshot) {
    seedBlueConverted = snapshot.seedBlueConverted();
}
```

- [ ] **Step 6: Run visual snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageVisualSnapshot" test
```

Expected: pass.

- [ ] **Step 7: Commit Task 3**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRingConverter.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePerspective.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBackground.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStagePalette.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageHud.java `
        src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageBanner.java `
        src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageVisualSnapshot.java
git commit -m "feat: snapshot S3K special stage visual state" -m "Changelog: n/a: covered by Task 1 S3K special-stage rewind changelog entry
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Task 4: Manager Snapshot, Game State, Audio, And Palette Restore

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java`
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageManagerSnapshot.java`

- [ ] **Step 1: Write failing manager snapshot tests**

Create `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageManagerSnapshot.java`:

```java
package com.openggf.game.sonic3k.specialstage;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestSonic3kSpecialStageManagerSnapshot {
    private AudioTestFixtures.RecordingAudioBackend audioBackend;
    private GameStateManager gameState;

    @BeforeEach
    void configureServices() {
        SessionManager.clear();
        AudioManager.getInstance().resetState();
        audioBackend = new AudioTestFixtures.RecordingAudioBackend();
        AudioManager.getInstance().setBackend(audioBackend);
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void clearServices() {
        SessionManager.clear();
        AudioManager.getInstance().resetState();
    }

    @Test
    void managerSnapshotRestoresScalarsAndSubsystemsWithoutInitializationReload() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);
        manager.getGrid().setCellByIndex(0x44, Sonic3kSpecialStageConstants.CELL_RING);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);
        set(manager, "ringsCollected", 17);
        set(manager, "spheresLeft", 88);
        set(manager, "ringsLeft", 5);
        set(manager, "frameCounter", 1234);
        set(manager, "heldButtons", 0x0F);
        set(manager, "pressedButtons", 0x70);
        set(manager, "p2HeldButtons", 0x10);
        set(manager, "clearRoutine", 3);
        set(manager, "clearTimer", 9);
        set(manager, "emeraldTimer", 7);
        set(manager, "emeraldInteractIndex", 0x155);
        set(manager, "exitSpinStarted", true);
        set(manager, "palFadeDelay", 1);
        set(manager, "musicSpedUp", true);
        set(manager, "ringAnimTimer", 2);
        set(manager, "ringAnimFrame", 1);
        set(manager, "tailsAnimTimer", 0x1200);
        set(manager, "tailsMappingFrame", 4);
        set(manager, "tailsTailsAnimTimer", 0x3400);
        set(manager, "tailsTailsMappingFrame", 6);
        set(manager, "tailsJumping", 0x80);
        set(manager, "tailsJumpHeight", -0x10000L);
        set(manager, "tailsJumpVelocity", -0x2000L);
        set(manager, "spriteDebugMode", true);
        set(manager, "useSkLayouts", true);

        Sonic3kSpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        set(manager, "ringsCollected", 0);
        set(manager, "spheresLeft", 0);
        set(manager, "frameCounter", 0);
        manager.getGrid().setCellByIndex(0x44, Sonic3kSpecialStageConstants.CELL_RED);

        manager.restoreRewindSnapshot(snapshot);

        assertEquals(17, manager.getRingsCollected());
        assertEquals(88, manager.getSpheresLeft());
        assertEquals(5, manager.getRingsLeft());
        assertEquals(1234, manager.getFrameCounter());
        assertEquals(3, manager.getClearRoutine());
        assertEquals(9, manager.getClearTimer());
        assertEquals(Sonic3kSpecialStageConstants.CELL_RING, manager.getGrid().getCellByIndex(0x44));
        assertEquals(0x2222, manager.getPlayer().getXPos());
        assertEquals(true, get(manager, "spriteDebugMode"));
        assertEquals(true, get(manager, "useSkLayouts"));
    }

    @Test
    void managerRestoreRejectsUninitializedLiveOrSnapshotState() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class, manager::captureRewindSnapshot);
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> manager.restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.uninitializedForTest()));
    }

    @Test
    void managerSnapshotRestoresDurableEmeraldGameState() {
        attachRuntime();
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);

        gameState.markEmeraldCollected(0);
        gameState.markSuperEmeraldCollected(1);
        Sonic3kSpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        gameState.markEmeraldCollected(2);
        gameState.markSuperEmeraldCollected(3);
        manager.restoreRewindSnapshot(snapshot);

        assertTrue(gameState.hasEmerald(0));
        assertFalse(gameState.hasEmerald(2));
        assertTrue(gameState.hasSuperEmerald(1));
        assertFalse(gameState.hasSuperEmerald(3));
        assertEquals(1, gameState.getEmeraldCount());
    }

    @Test
    void managerRestoreSetsAudioSpeedFromMusicSpeedFlag() {
        attachRuntime();
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);

        set(manager, "musicSpedUp", false);
        Sonic3kSpecialStageSnapshot normalSpeed = manager.captureRewindSnapshot();
        manager.restoreRewindSnapshot(normalSpeed);
        assertTrue(audioBackend.calls.contains("setSpeedMultiplier:1"));

        audioBackend.clear();
        set(manager.getPlayer(), "rate", 0x1800);
        set(manager, "musicSpedUp", true);
        Sonic3kSpecialStageSnapshot spedUp = manager.captureRewindSnapshot();
        set(manager.getPlayer(), "rate", 0x1000);
        set(manager, "musicSpedUp", false);
        manager.restoreRewindSnapshot(spedUp);

        assertTrue(audioBackend.calls.contains("setSpeedMultiplier:24"));
    }

    private void attachRuntime() {
        GameplayModeContext context = SessionManager.openGameplaySession(new Sonic3kGameModule());
        gameState = new GameStateManager();
        context.attachGameplayManagers(
                new Camera(),
                new TimerManager(),
                gameState,
                new FadeManager(),
                new GameRng(GameRng.Flavour.S3K),
                new DefaultSolidExecutionRegistry());
        context.attachSharedRegistries(
                new ZoneRuntimeRegistry(),
                new PaletteOwnershipRegistry(),
                new AnimatedTileChannelGraph(),
                new SpecialRenderEffectRegistry(),
                new AdvancedRenderModeController(),
                new ZoneLayoutMutationPipeline());
        WaterSystem water = new WaterSystem();
        ParallaxManager parallax = new ParallaxManager();
        TerrainCollisionManager terrain = new TerrainCollisionManager();
        CollisionSystem collision = new CollisionSystem(terrain);
        SpriteManager sprites = new SpriteManager();
        LevelManager level = mock(LevelManager.class);
        context.attachLevelManagers(water, parallax, terrain, collision, sprites, level);
    }

    private static void seedInitializedManager(Sonic3kSpecialStageManager manager) {
        set(manager, "initialized", true);
        set(manager, "finished", false);
        set(manager, "currentStage", 2);
        set(manager, "emeraldCollected", false);
        set(manager, "superEmeraldMode", false);
        set(manager, "tailsEnabled", true);
        set(manager, "playerCharacter", com.openggf.game.PlayerCharacter.SONIC_AND_TAILS);
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
```

- [ ] **Step 2: Run the failing manager snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageManagerSnapshot" test
```

Expected: failure because the manager still returns the temporary snapshot and does not restore fields.

- [ ] **Step 3: Replace manager capture implementation**

Edit `Sonic3kSpecialStageManager.captureRewindSnapshot()`:

```java
Sonic3kSpecialStageSnapshot captureRewindSnapshot() {
    if (!initialized) {
        throw new IllegalStateException("Cannot capture S3K special-stage rewind state before initialization");
    }
    GameStateManager gameState = GameServices.gameStateOrNull();
    return new Sonic3kSpecialStageSnapshot(
            currentStage,
            initialized,
            finished,
            emeraldCollected,
            superEmeraldMode,
            ringsCollected,
            spheresLeft,
            ringsLeft,
            frameCounter,
            heldButtons,
            pressedButtons,
            p2HeldButtons,
            clearRoutine,
            clearTimer,
            emeraldTimer,
            emeraldInteractIndex,
            exitSpinStarted,
            palFadeDelay,
            musicSpedUp,
            ringAnimTimer,
            ringAnimFrame,
            bannerPhase,
            bannerTimer,
            bannerOffset,
            tailsAnimTimer,
            tailsMappingFrame,
            tailsTailsAnimTimer,
            tailsTailsMappingFrame,
            tailsJumping,
            tailsJumpHeight,
            tailsJumpVelocity,
            tailsEnabled,
            playerCharacter,
            spriteDebugMode,
            useSkLayouts,
            gameState != null ? gameState.capture() : null,
            grid.captureRewindSnapshot(),
            player.captureRewindSnapshot(),
            tailsAI.captureRewindSnapshot(),
            collisionQueue.captureRewindSnapshot(),
            ringConverter.captureRewindSnapshot(),
            perspective.captureRewindSnapshot(),
            background.captureRewindSnapshot(),
            hud.captureRewindSnapshot(),
            banner.captureRewindSnapshot(),
            palette != null ? palette.captureRewindSnapshot() : null);
}
```

- [ ] **Step 4: Replace manager restore implementation**

Edit `Sonic3kSpecialStageManager.restoreRewindSnapshot(...)`:

```java
void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot snapshot) {
    if (!initialized || snapshot == null || !snapshot.initialized()) {
        throw new IllegalStateException("Cannot restore S3K special-stage rewind state before initialization");
    }
    currentStage = snapshot.currentStage();
    initialized = snapshot.initialized();
    finished = snapshot.finished();
    emeraldCollected = snapshot.emeraldCollected();
    superEmeraldMode = snapshot.superEmeraldMode();
    ringsCollected = snapshot.ringsCollected();
    spheresLeft = snapshot.spheresLeft();
    ringsLeft = snapshot.ringsLeft();
    frameCounter = snapshot.frameCounter();
    heldButtons = snapshot.heldButtons();
    pressedButtons = snapshot.pressedButtons();
    p2HeldButtons = snapshot.p2HeldButtons();
    clearRoutine = snapshot.clearRoutine();
    clearTimer = snapshot.clearTimer();
    emeraldTimer = snapshot.emeraldTimer();
    emeraldInteractIndex = snapshot.emeraldInteractIndex();
    exitSpinStarted = snapshot.exitSpinStarted();
    palFadeDelay = snapshot.palFadeDelay();
    musicSpedUp = snapshot.musicSpedUp();
    ringAnimTimer = snapshot.ringAnimTimer();
    ringAnimFrame = snapshot.ringAnimFrame();
    bannerPhase = snapshot.bannerPhase();
    bannerTimer = snapshot.bannerTimer();
    bannerOffset = snapshot.bannerOffset();
    tailsAnimTimer = snapshot.tailsAnimTimer();
    tailsMappingFrame = snapshot.tailsMappingFrame();
    tailsTailsAnimTimer = snapshot.tailsTailsAnimTimer();
    tailsTailsMappingFrame = snapshot.tailsTailsMappingFrame();
    tailsJumping = snapshot.tailsJumping();
    tailsJumpHeight = snapshot.tailsJumpHeight();
    tailsJumpVelocity = snapshot.tailsJumpVelocity();
    tailsEnabled = snapshot.tailsEnabled();
    playerCharacter = snapshot.playerCharacter();
    spriteDebugMode = snapshot.spriteDebugMode();
    useSkLayouts = snapshot.useSkLayouts();

    GameStateManager gameState = GameServices.gameStateOrNull();
    if (gameState != null && snapshot.gameState() != null) {
        gameState.restore(snapshot.gameState());
    }
    grid.restoreRewindSnapshot(snapshot.grid());
    player.restoreRewindSnapshot(snapshot.player());
    tailsAI.restoreRewindSnapshot(snapshot.tailsAi());
    collisionQueue.restoreRewindSnapshot(snapshot.collisionQueue());
    ringConverter.restoreRewindSnapshot(snapshot.ringConverter());
    perspective.restoreRewindSnapshot(snapshot.perspective());
    background.restoreRewindSnapshot(snapshot.background());
    hud.restoreRewindSnapshot(snapshot.hud());
    banner.restoreRewindSnapshot(snapshot.banner());
    if (palette != null && snapshot.palette() != null) {
        palette.restoreRewindSnapshot(snapshot.palette());
        recacheRestoredPaletteForRewind();
    }
    restoreAudioSpeedForRewind();
}
```

- [ ] **Step 5: Add restore side-effect helpers**

Add these private methods to `Sonic3kSpecialStageManager`:

```java
private void recacheRestoredPaletteForRewind() {
    if (palette == null) {
        return;
    }
    try {
        Sonic3kSpecialStagePaletteUploader.cacheAll(GameServices.graphics(), palette.getPalettes());
    } catch (IllegalStateException ignored) {
        // Headless unit tests can exercise snapshot restore without graphics services.
    }
}

private void restoreAudioSpeedForRewind() {
    try {
        GameServices.audio().setSpeedMultiplier(musicSpedUp ? player.calculateMusicTempo() : 1);
    } catch (IllegalStateException ignored) {
        // Headless unit tests can exercise snapshot restore without audio services.
    }
}
```

Do not call SFX methods from restore.

- [ ] **Step 6: Run manager snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageManagerSnapshot" test
```

Expected: pass.

- [ ] **Step 7: Commit Task 4**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageManager.java `
        src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageManagerSnapshot.java
git commit -m "feat: snapshot S3K special stage manager" -m "Changelog: n/a: covered by Task 1 S3K special-stage rewind changelog entry
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Task 5: Provider Enablement And Integration Coverage

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageProvider.java`
- Modify: `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`
- Modify: `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`
- Test: all S3K special-stage rewind tests

- [ ] **Step 1: Write failing provider and registration expectations**

Edit `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java` and replace the S3K-disabled assertions in `sonic1AndSonic2ProvidersSupportRewindButS3kDoesNotYet()` with:

```java
        assertTrue(new Sonic3kSpecialStageProvider().supportsRewind());
        assertTrue(new Sonic3kSpecialStageProvider().rewindAdapter().isPresent());
        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY,
                new Sonic3kSpecialStageProvider().rewindAdapter().orElseThrow().key());
```

Rename the test method to:

```java
void sonic1Sonic2AndS3kProvidersSupportRewind()
```

Edit `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java` and add this test:

```java
@Test
void sonic3kAdapterUsesGenericKeyWithoutCapturingUninitializedManager() {
    RewindSnapshottable<?> adapter = new com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider()
            .rewindAdapter()
            .orElseThrow();

    assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
    assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
}
```

This test deliberately avoids `context.getRewindRegistry().capture()` because an uninitialized S3K manager must reject capture.

- [ ] **Step 2: Run failing provider tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
```

Expected: failure because `Sonic3kSpecialStageProvider` still reports unsupported and returns an empty adapter.

- [ ] **Step 3: Enable provider rewind support**

Edit `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageProvider.java`:

```java
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Optional;
```

Add or replace these methods:

```java
@Override
public boolean supportsRewind() {
    return true;
}

@Override
public Optional<RewindSnapshottable<?>> rewindAdapter() {
    return Optional.of(new Sonic3kSpecialStageRewindAdapter(manager));
}
```

- [ ] **Step 4: Run focused S3K rewind tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageRewindAdapter,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageGameplaySnapshot,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageVisualSnapshot,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageManagerSnapshot,com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
```

Expected: pass.

- [ ] **Step 5: Run existing S3K special-stage tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageGrid,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStagePlayer,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageRingConverter,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsArt,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsTally,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsVisual" test
```

Expected: pass.

- [ ] **Step 6: Run package build verification**

Run:

```powershell
mvn "-DskipTests" package
```

Expected: pass and produce `target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar`.

Then run:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.*Sonic3kSpecialStage*,com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
```

Expected: pass. If Surefire wildcard matching does not select the intended classes, run the explicit focused test command from Step 4 and the existing S3K test command from Step 5 instead.

- [ ] **Step 7: Commit Task 5**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageProvider.java `
        src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java `
        src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java
git commit -m "feat: enable S3K special stage rewind" -m "Changelog: n/a: covered by Task 1 S3K special-stage rewind changelog entry
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Manual Acceptance

After Task 5, run the jar with a valid `s3k.gen` and verify held rewind in S3K Blue Spheres across:

- banner slide-out into auto-advance;
- blue-sphere collection and delayed red-sphere conversion;
- ring collection while ring animation queue is active;
- sphere-to-ring conversion and PERFECT banner re-entry;
- bumper and spring interactions;
- red-sphere failure exit spin and white fade;
- all-spheres clear sequence, emerald placement, emerald collection, and transition to results;
- Tails AI jump follow and Player 2 takeover;
- debug next-stage/layout-set boundaries, confirming the timeline is severed after the shortcut.

## Final Verification

Before declaring implementation complete:

```powershell
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageRewindAdapter,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageGameplaySnapshot,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageVisualSnapshot,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageManagerSnapshot,com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
mvn "-Dtest=com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageGrid,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStagePlayer,com.openggf.game.sonic3k.specialstage.TestSonic3kSpecialStageRingConverter,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsArt,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsTally,com.openggf.game.sonic3k.specialstage.TestS3kSpecialStageResultsVisual" test
mvn "-DskipTests" package
```

If full `mvn test` is run and fails in unrelated existing suites, record the failing classes and still report the focused S3K rewind verification separately.
