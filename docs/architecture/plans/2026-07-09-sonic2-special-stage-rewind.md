# Sonic 2 Special Stage Rewind Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add deterministic held-key live rewind support to Sonic 2 special stages by capturing and restoring the complete Sonic 2 special-stage runtime graph.

**Architecture:** Reuse the shared special-stage rewind foundation already used by Sonic 1: provider capability gate, provider-owned adapter under `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`, and `SpecialStageStepper` replay. Keep all Sonic 2 state capture package-local under `com.openggf.game.sonic2.specialstage`; do not add shared GameLoop or rewind-controller behavior.

**Tech Stack:** Java 17, JUnit 5, Maven, existing `RewindSnapshottable`, existing Sonic 2 special-stage manager/player/object classes.

---

## Source Spec

Implement from:

- `docs/architecture/designs/2026-07-09-sonic2-special-stage-rewind-design.md`

Do not implement S3K special-stage rewind in this plan. S3K should stay non-rewindable except for tests that assert it remains disabled.

## File Structure

Create:

- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
  - Package-private immutable snapshot container and clone helpers.
  - Holds manager, track animator, player topology/player state, intro, objects, checkpoint, alignment checkpoint, and mutable palette state.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRewindAdapter.java`
  - Public adapter implementing `RewindSnapshottable<Sonic2SpecialStageSnapshot>` so `Sonic2SpecialStageProvider` in the sibling `com.openggf.game.sonic2` package can instantiate it.
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindAdapter.java`
  - Direct adapter key/default-missing-snapshot/capture-restore shell tests. Sonic 2 provider capability stays disabled until Task 6.
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStagePlayerSnapshot.java`
  - Player state, topology, and mandatory player-owned invulnerability countdown tests.
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageObjectSnapshot.java`
  - Active object snapshot/reconstruction tests.
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageCheckpointSnapshot.java`
  - Checkpoint message/rainbow/callback preservation tests.
- `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java`
  - Manager-level snapshot integration tests.

Modify:

- `src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java`
  - In Task 6 only, after full snapshot tests pass, return `supportsRewind() == true`.
  - In Task 6 only, return `Optional.of(new Sonic2SpecialStageRewindAdapter(manager))`.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
  - Add package-local `captureRewindSnapshot()` and `restoreRewindSnapshot(...)`.
  - Add package-local test hooks only where needed to build initialized headless state without ROM/GL.
  - Add object-manager capture/restore.
  - Restore mutable palettes and recache palette textures only when graphics is available.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java`
  - Add package-local capture/restore.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
  - Replace `TimerManager`/`SSInvulnerabilityTimer` ownership with a player-owned countdown.
  - Add package-local capture/restore and topology inspection helpers.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageIntro.java`
  - Add package-local capture/restore.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageObject.java`
  - Add package-local base capture/restore helpers.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRing.java`
  - Add package-local capture/restore for `spinFrame`.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageBomb.java`
  - Add package-local capture/restore using base state.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageEmerald.java`
  - Add package-local capture/restore and manager reattachment.
- `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageCheckpoint.java`
  - Add package-local capture/restore preserving callbacks.
- `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`
  - Update capability expectation to include Sonic 2, keep S3K disabled.
- `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`
  - Add Sonic 2 registration coverage under the generic special-stage key.

Do not modify:

- `src/main/java/com/openggf/GameLoop.java`
- `src/main/java/com/openggf/game/SpecialStageStepper.java`
- `src/main/java/com/openggf/game/sonic3k/specialstage/*`
- Level rewind, trace replay, bonus-stage rewind.

## Task 1: Snapshot Container And Adapter Shell

**Files:**

- Create: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Create: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRewindAdapter.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindAdapter.java`

- [ ] **Step 1: Write the failing direct adapter shell test**

Create `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindAdapter.java`.

Use direct adapter tests here. Do not change `Sonic2SpecialStageProvider.supportsRewind()` yet; the provider must stay non-rewindable until the full runtime graph is snapshotted in Task 6.

```java
package com.openggf.game.sonic2.specialstage;

import com.openggf.game.SpecialStageProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSonic2SpecialStageRewindAdapter {
    @Test
    void adapterUsesGenericSpecialStageKeyAndKeepsMissingSnapshotDefault() {
        Sonic2SpecialStageRewindAdapter adapter =
                new Sonic2SpecialStageRewindAdapter(new Sonic2SpecialStageManager());

        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, adapter.key());
        assertThrows(IllegalStateException.class, adapter::resetForMissingSnapshot);
    }

    @Test
    void adapterDelegatesCaptureAndRestoreToManager() {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        manager.markCompleted(true);
        Sonic2SpecialStageRewindAdapter adapter = new Sonic2SpecialStageRewindAdapter(manager);

        Sonic2SpecialStageSnapshot snapshot = adapter.capture();
        manager.markFailed();

        adapter.restore(snapshot);

        assertEquals(Sonic2SpecialStageManager.ResultState.COMPLETED, manager.getResultState());
    }
}
```

- [ ] **Step 2: Run the failing direct adapter test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindAdapter" test
```

Expected: compile failure because the Sonic 2 snapshot and adapter do not exist.

- [ ] **Step 3: Add `Sonic2SpecialStageSnapshot` shell**

Create `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java` with this initial content. Later tasks add the full field list.

```java
package com.openggf.game.sonic2.specialstage;

import com.openggf.level.Palette;

import java.util.List;

final class Sonic2SpecialStageSnapshot {
    final boolean initialized;
    final int currentStage;
    final Sonic2SpecialStageManager.ResultState resultState;
    final boolean emeraldCollected;

    Sonic2SpecialStageSnapshot(
            boolean initialized,
            int currentStage,
            Sonic2SpecialStageManager.ResultState resultState,
            boolean emeraldCollected) {
        this.initialized = initialized;
        this.currentStage = currentStage;
        this.resultState = resultState;
        this.emeraldCollected = emeraldCollected;
    }

    static byte[] cloneByteArray(byte[] source) {
        return source != null ? source.clone() : null;
    }

    static int[] cloneIntArray(int[] source) {
        return source != null ? source.clone() : null;
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

    static <T> List<T> copyList(List<T> source) {
        return source != null ? List.copyOf(source) : List.of();
    }
}
```

- [ ] **Step 4: Add `Sonic2SpecialStageRewindAdapter`**

Create `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRewindAdapter.java`:

```java
package com.openggf.game.sonic2.specialstage;

import com.openggf.game.SpecialStageProvider;
import com.openggf.game.rewind.RewindSnapshottable;

import java.util.Objects;

public final class Sonic2SpecialStageRewindAdapter
        implements RewindSnapshottable<Sonic2SpecialStageSnapshot> {
    private final Sonic2SpecialStageManager manager;

    public Sonic2SpecialStageRewindAdapter(Sonic2SpecialStageManager manager) {
        this.manager = Objects.requireNonNull(manager, "manager");
    }

    @Override
    public String key() {
        return SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY;
    }

    @Override
    public Sonic2SpecialStageSnapshot capture() {
        return manager.captureRewindSnapshot();
    }

    @Override
    public void restore(Sonic2SpecialStageSnapshot snapshot) {
        manager.restoreRewindSnapshot(snapshot);
    }
}
```

- [ ] **Step 5: Add minimal manager capture/restore hooks**

Edit `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`.

Add near the existing public getters:

```java
Sonic2SpecialStageSnapshot captureRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot(
            initialized,
            currentStage,
            resultState,
            emeraldCollected);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot snapshot) {
    initialized = snapshot.initialized;
    currentStage = snapshot.currentStage;
    resultState = snapshot.resultState;
    emeraldCollected = snapshot.emeraldCollected;
}
```

- [ ] **Step 6: Run the direct adapter test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindAdapter" test
```

Expected: pass.

- [ ] **Step 7: Commit Task 1**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRewindAdapter.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindAdapter.java `
        CHANGELOG.md
git commit -m "feat: add Sonic 2 special stage rewind adapter shell" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Also stage `CHANGELOG.md` with a short entry under Unreleased before committing because this task touches `src/main`.

## Task 2: Track Animator Snapshot

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageTrackAnimatorSnapshot.java`

- [ ] **Step 1: Write failing track animator snapshot tests**

Create `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageTrackAnimatorSnapshot.java`:

```java
package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class TestSonic2SpecialStageTrackAnimatorSnapshot {
    @Test
    void restoresTrackAnimatorStateAndClonesLayout() throws Exception {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        set(animator, "currentSegmentIndex", 4);
        set(animator, "currentFrameInSegment", 7);
        set(animator, "frameDelayCounter", 2);
        set(animator, "currentSegmentType", 3);
        set(animator, "currentSegmentFlipped", true);
        set(animator, "speedFactor", 9);
        set(animator, "stageComplete", true);
        set(animator, "orientationFlipped", true);
        set(animator, "lastOrientationFrame", 12);

        Sonic2SpecialStageSnapshot.TrackAnimatorSnapshot snapshot =
                animator.captureRewindSnapshot();

        set(animator, "currentSegmentIndex", 99);
        set(animator, "currentFrameInSegment", 99);
        set(animator, "frameDelayCounter", 99);
        set(animator, "currentSegmentType", 99);
        set(animator, "currentSegmentFlipped", false);
        set(animator, "speedFactor", 1);
        set(animator, "stageComplete", false);
        set(animator, "orientationFlipped", false);
        set(animator, "lastOrientationFrame", -1);

        animator.restoreRewindSnapshot(snapshot);

        assertEquals(4, get(animator, "currentSegmentIndex"));
        assertEquals(7, get(animator, "currentFrameInSegment"));
        assertEquals(2, get(animator, "frameDelayCounter"));
        assertEquals(3, get(animator, "currentSegmentType"));
        assertEquals(true, get(animator, "currentSegmentFlipped"));
        assertEquals(9, get(animator, "speedFactor"));
        assertEquals(true, get(animator, "stageComplete"));
        assertEquals(true, get(animator, "orientationFlipped"));
        assertEquals(12, get(animator, "lastOrientationFrame"));

        byte[] liveLayout = (byte[]) get(animator, "stageLayout");
        assertArrayEquals(snapshot.stageLayout(), liveLayout);
        assertNotSame(snapshot.stageLayout(), liveLayout);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
```

- [ ] **Step 2: Run the failing track animator test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageTrackAnimatorSnapshot" test
```

Expected: compile failure because `TrackAnimatorSnapshot`, `captureRewindSnapshot()`, and `restoreRewindSnapshot(...)` do not exist.

- [ ] **Step 3: Add `TrackAnimatorSnapshot`**

Edit `Sonic2SpecialStageSnapshot.java` and add:

```java
record TrackAnimatorSnapshot(
        byte[] stageLayout,
        int layoutLength,
        int currentSegmentIndex,
        int currentFrameInSegment,
        int frameDelayCounter,
        int currentSegmentType,
        boolean currentSegmentFlipped,
        int speedFactor,
        boolean stageComplete,
        boolean orientationFlipped,
        int lastOrientationFrame) {
    TrackAnimatorSnapshot {
        stageLayout = Sonic2SpecialStageSnapshot.cloneByteArray(stageLayout);
    }
}
```

- [ ] **Step 4: Implement track animator capture/restore**

Edit `Sonic2TrackAnimator.java` and add package-local methods near the getters:

```java
Sonic2SpecialStageSnapshot.TrackAnimatorSnapshot captureRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot.TrackAnimatorSnapshot(
            stageLayout,
            layoutLength,
            currentSegmentIndex,
            currentFrameInSegment,
            frameDelayCounter,
            currentSegmentType,
            currentSegmentFlipped,
            speedFactor,
            stageComplete,
            orientationFlipped,
            lastOrientationFrame);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.TrackAnimatorSnapshot snapshot) {
    stageLayout = Sonic2SpecialStageSnapshot.cloneByteArray(snapshot.stageLayout());
    layoutLength = snapshot.layoutLength();
    currentSegmentIndex = snapshot.currentSegmentIndex();
    currentFrameInSegment = snapshot.currentFrameInSegment();
    frameDelayCounter = snapshot.frameDelayCounter();
    currentSegmentType = snapshot.currentSegmentType();
    currentSegmentFlipped = snapshot.currentSegmentFlipped();
    speedFactor = snapshot.speedFactor();
    stageComplete = snapshot.stageComplete();
    orientationFlipped = snapshot.orientationFlipped();
    lastOrientationFrame = snapshot.lastOrientationFrame();
}
```

- [ ] **Step 5: Run the track animator test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageTrackAnimatorSnapshot" test
```

Expected: pass.

- [ ] **Step 6: Commit Task 2**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackAnimator.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageTrackAnimatorSnapshot.java `
        CHANGELOG.md
git commit -m "feat: snapshot Sonic 2 special stage track animator" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use `Changelog: updated` because `src/main` changed.

## Task 3: Player Countdown, Snapshot, And Topology

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStagePlayerSnapshot.java`

- [ ] **Step 1: Write failing player snapshot and countdown tests**

Create `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStagePlayerSnapshot.java`:

```java
package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2SpecialStagePlayerSnapshot {
    @Test
    void playerSnapshotRestoresDeterministicFieldsAndClonesControlBuffer() throws Exception {
        Sonic2SpecialStagePlayer player = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        seedPlayer(player);

        Sonic2SpecialStageSnapshot.PlayerSnapshot snapshot = player.captureRewindSnapshot();
        set(player, "ssXPos", 999);
        set(player, "ssXSub", 999);
        set(player, "ssYPos", 999);
        set(player, "ssYSub", 999);
        set(player, "ssZPos", 999);
        set(player, "anim", 99);
        set(player, "prevAnim", 99);
        set(player, "animFrame", 99);
        set(player, "animFrameDuration", 99);
        set(player, "ssInitFlipTimer", 99);
        set(player, "ssFlipTimer", 99);
        set(player, "ssLastAngleIndex", 99);
        set(player, "invulnerabilityCountdown", 0);

        player.restoreRewindSnapshot(snapshot);

        assertEquals(0x1234, get(player, "ssXPos"));
        assertEquals(0x56, get(player, "ssXSub"));
        assertEquals(0x2345, get(player, "ssYPos"));
        assertEquals(0x67, get(player, "ssYSub"));
        assertEquals(0x78, get(player, "ssZPos"));
        assertEquals(2, get(player, "anim"));
        assertEquals(1, get(player, "prevAnim"));
        assertEquals(3, get(player, "animFrame"));
        assertEquals(4, get(player, "animFrameDuration"));
        assertEquals(0x400, get(player, "ssInitFlipTimer"));
        assertEquals(5, get(player, "ssFlipTimer"));
        assertEquals(6, get(player, "ssLastAngleIndex"));
        assertEquals(30, get(player, "invulnerabilityCountdown"));
        assertEquals(0xAAAA, player.getControlRecordEntry(0));
        assertNotSame(snapshot.ctrlRecordBuf(), get(player, "ctrlRecordBuf"));
    }

    @Test
    void playerOwnedInvulnerabilityCountdownTicksInSpecialStageUpdatePath() throws Exception {
        Sonic2SpecialStagePlayer player = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        set(player, "invulnerabilityCountdown", 2);

        assertTrue(player.isInvulnerable());
        player.update(0, 0);
        assertEquals(1, player.getInvulnerabilityTicks());
        player.update(0, 0);
        assertEquals(0, player.getInvulnerabilityTicks());
    }

    @Test
    void topologySnapshotPreservesSoloAndTeamPlayerRoles() {
        Sonic2SpecialStagePlayer sonic = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        sonic.setOtherPlayer(tails);
        tails.setOtherPlayer(sonic);

        Sonic2SpecialStageSnapshot.PlayerTopologySnapshot topology =
                Sonic2SpecialStageSnapshot.PlayerTopologySnapshot.capture(
                        java.util.List.of(sonic, tails), sonic, tails);

        assertEquals(2, topology.slots().size());
        assertEquals(Sonic2SpecialStagePlayer.PlayerType.SONIC, topology.slots().get(0).type());
        assertTrue(topology.slots().get(0).mainCharacter());
        assertEquals(Sonic2SpecialStagePlayer.PlayerType.TAILS, topology.slots().get(1).type());
        assertEquals(0, topology.sonicSlotIndex());
        assertEquals(1, topology.tailsSlotIndex());
        assertTrue(topology.playersLinked());
    }

    @Test
    void restoreTopologyCoversSonicSoloTailsSoloAndTeamRelinking() throws Exception {
        assertSoloTopology(
                new Sonic2SpecialStagePlayer(Sonic2SpecialStagePlayer.PlayerType.SONIC, true),
                "sonicPlayer",
                "tailsPlayer");
        assertSoloTopology(
                new Sonic2SpecialStagePlayer(Sonic2SpecialStagePlayer.PlayerType.TAILS, true),
                "tailsPlayer",
                "sonicPlayer");

        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        java.util.ArrayList<Sonic2SpecialStagePlayer> players = new java.util.ArrayList<>();
        Sonic2SpecialStagePlayer sonic = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        sonic.setOtherPlayer(tails);
        tails.setOtherPlayer(sonic);
        players.add(sonic);
        players.add(tails);
        set(manager, "players", players);
        set(manager, "sonicPlayer", sonic);
        set(manager, "tailsPlayer", tails);
        set(manager, "renderer", renderer);
        renderer.setPlayers(new java.util.ArrayList<>());

        Sonic2SpecialStageSnapshot.PlayerTopologySnapshot topology =
                Sonic2SpecialStageSnapshot.PlayerTopologySnapshot.capture(players, sonic, tails);
        java.util.List<Sonic2SpecialStageSnapshot.PlayerSnapshot> playerSnapshots =
                java.util.List.of(sonic.captureRewindSnapshot(), tails.captureRewindSnapshot());
        sonic.setOtherPlayer(null);
        tails.setOtherPlayer(null);

        manager.restorePlayerTopologyForRewind(topology, playerSnapshots);

        assertSame(sonic, get(manager, "sonicPlayer"));
        assertSame(tails, get(manager, "tailsPlayer"));
        assertSame(tails, sonic.getOtherPlayerForRewind());
        assertSame(sonic, tails.getOtherPlayerForRewind());
        assertSame(players, get(renderer, "players"));
    }

    private static void assertSoloTopology(Sonic2SpecialStagePlayer player,
                                           String presentField,
                                           String absentField) throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        java.util.ArrayList<Sonic2SpecialStagePlayer> players = new java.util.ArrayList<>();
        players.add(player);
        set(manager, "players", players);
        set(manager, presentField, player);
        set(manager, absentField, null);
        set(manager, "renderer", renderer);

        Sonic2SpecialStageSnapshot.PlayerTopologySnapshot topology =
                Sonic2SpecialStageSnapshot.PlayerTopologySnapshot.capture(
                        players,
                        "sonicPlayer".equals(presentField) ? player : null,
                        "tailsPlayer".equals(presentField) ? player : null);

        manager.restorePlayerTopologyForRewind(
                topology,
                java.util.List.of(player.captureRewindSnapshot()));

        assertSame(player, get(manager, presentField));
        assertNull(get(manager, absentField));
        assertNull(player.getOtherPlayerForRewind());
        assertSame(players, get(renderer, "players"));
    }

    private static void seedPlayer(Sonic2SpecialStagePlayer player) throws Exception {
        set(player, "ssXPos", 0x1234);
        set(player, "ssXSub", 0x56);
        set(player, "ssYPos", 0x2345);
        set(player, "ssYSub", 0x67);
        set(player, "ssZPos", 0x78);
        set(player, "xPos", 10);
        set(player, "yPos", 20);
        set(player, "xVel", 30);
        set(player, "yVel", 40);
        set(player, "inertia", 50);
        set(player, "angle", 60);
        set(player, "ssSlideTimer", 7);
        set(player, "ssHurtTimer", 8);
        set(player, "ssDplcTimer", 9);
        set(player, "ssInitFlipTimer", 0x400);
        set(player, "ssFlipTimer", 5);
        set(player, "ssLastAngleIndex", 6);
        set(player, "anim", 2);
        set(player, "prevAnim", 1);
        set(player, "animFrame", 3);
        set(player, "animFrameDuration", 4);
        set(player, "mappingFrame", 11);
        set(player, "globalAnimFrameTimer", 12);
        set(player, "collisionProperty", 13);
        set(player, "invulnerabilityCountdown", 30);
        int[] ctrl = (int[]) get(player, "ctrlRecordBuf");
        ctrl[0] = 0xAAAA;
        set(player, "ctrlRecordIndex", 3);
        set(player, "swapPositionsFlag", true);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
```

- [ ] **Step 2: Run the failing player tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStagePlayerSnapshot" test
```

Expected: compile failure because player snapshot methods, topology snapshot, and `invulnerabilityCountdown` do not exist.

- [ ] **Step 3: Remove TimerManager ownership from Sonic 2 player invulnerability**

Edit `Sonic2SpecialStagePlayer.java`.

Remove imports:

```java
import com.openggf.timer.Timer;
import com.openggf.timer.timers.SSInvulnerabilityTimer;
```

Keep `import com.openggf.game.GameServices;` because the class still uses `GameServices.audio().playSfx(...)` in the jump path.

Add field near `swapPositionsFlag`:

```java
private int invulnerabilityCountdown;
```

In `reset()`, set:

```java
invulnerabilityCountdown = 0;
```

At the top of `update(int heldButtons, int pressedButtons)`, after input record handling and before routine logic, add:

```java
if (invulnerabilityCountdown > 0) {
    invulnerabilityCountdown--;
}
```

In `ssHurtAnimation()`, replace TimerManager registration with:

```java
if (ssHurtTimer == 0) {
    routineSecondary = 0;
    invulnerabilityCountdown = 0x1E;
}
```

Replace invulnerability methods with:

```java
public boolean isInvulnerable() {
    return invulnerabilityCountdown > 0;
}

public int getInvulnerabilityTicks() {
    return invulnerabilityCountdown;
}

public void clearInvulnerability() {
    invulnerabilityCountdown = 0;
    LOGGER.fine("Invulnerability ended for " + playerType.name());
}
```

Keep `clearInvulnerability()` for compatibility with existing `SSInvulnerabilityTimer` type, but no S2 special-stage path should register that timer.

- [ ] **Step 4: Add player snapshot records**

Edit `Sonic2SpecialStageSnapshot.java`.

Add:

```java
record PlayerSlotSnapshot(
        Sonic2SpecialStagePlayer.PlayerType type,
        boolean mainCharacter) {
}

record PlayerTopologySnapshot(
        List<PlayerSlotSnapshot> slots,
        int sonicSlotIndex,
        int tailsSlotIndex,
        boolean playersLinked) {
    PlayerTopologySnapshot {
        slots = List.copyOf(slots);
    }

    static PlayerTopologySnapshot capture(
            List<Sonic2SpecialStagePlayer> players,
            Sonic2SpecialStagePlayer sonicPlayer,
            Sonic2SpecialStagePlayer tailsPlayer) {
        java.util.ArrayList<PlayerSlotSnapshot> slots = new java.util.ArrayList<>();
        int sonicIndex = -1;
        int tailsIndex = -1;
        for (int i = 0; i < players.size(); i++) {
            Sonic2SpecialStagePlayer player = players.get(i);
            slots.add(new PlayerSlotSnapshot(player.getPlayerType(), player.isMainCharacter()));
            if (player == sonicPlayer) {
                sonicIndex = i;
            }
            if (player == tailsPlayer) {
                tailsIndex = i;
            }
        }
        boolean linked = sonicPlayer != null && tailsPlayer != null
                && sonicPlayer.getOtherPlayerForRewind() == tailsPlayer
                && tailsPlayer.getOtherPlayerForRewind() == sonicPlayer;
        return new PlayerTopologySnapshot(slots, sonicIndex, tailsIndex, linked);
    }
}

record PlayerSnapshot(
        Sonic2SpecialStagePlayer.PlayerType playerType,
        boolean mainCharacter,
        Sonic2SpecialStagePlayer.RoutineState routine,
        int routineSecondary,
        int ssXPos,
        int ssXSub,
        int ssYPos,
        int ssYSub,
        int ssZPos,
        int xPos,
        int yPos,
        int xVel,
        int yVel,
        int inertia,
        int angle,
        int ssSlideTimer,
        int ssHurtTimer,
        int ssDplcTimer,
        int ssInitFlipTimer,
        int ssFlipTimer,
        int ssLastAngleIndex,
        int anim,
        int prevAnim,
        int animFrame,
        int animFrameDuration,
        int mappingFrame,
        int yRadius,
        int xRadius,
        int priority,
        boolean statusXFlip,
        boolean statusYFlip,
        boolean statusJumping,
        boolean statusSlowing,
        boolean renderXFlip,
        boolean renderYFlip,
        int collisionProperty,
        int globalAnimFrameTimer,
        int[] ctrlRecordBuf,
        int ctrlRecordIndex,
        boolean swapPositionsFlag,
        int invulnerabilityCountdown) {
    PlayerSnapshot {
        ctrlRecordBuf = Sonic2SpecialStageSnapshot.cloneIntArray(ctrlRecordBuf);
    }
}
```

- [ ] **Step 5: Add player helpers and capture/restore**

Edit `Sonic2SpecialStagePlayer.java`.

Add package-local helpers:

```java
boolean isMainCharacter() {
    return isMainCharacter;
}

Sonic2SpecialStagePlayer getOtherPlayerForRewind() {
    return otherPlayer;
}
```

Add capture/restore:

```java
Sonic2SpecialStageSnapshot.PlayerSnapshot captureRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot.PlayerSnapshot(
            playerType,
            isMainCharacter,
            routine,
            routineSecondary,
            ssXPos,
            ssXSub,
            ssYPos,
            ssYSub,
            ssZPos,
            xPos,
            yPos,
            xVel,
            yVel,
            inertia,
            angle,
            ssSlideTimer,
            ssHurtTimer,
            ssDplcTimer,
            ssInitFlipTimer,
            ssFlipTimer,
            ssLastAngleIndex,
            anim,
            prevAnim,
            animFrame,
            animFrameDuration,
            mappingFrame,
            yRadius,
            xRadius,
            priority,
            statusXFlip,
            statusYFlip,
            statusJumping,
            statusSlowing,
            renderXFlip,
            renderYFlip,
            collisionProperty,
            globalAnimFrameTimer,
            ctrlRecordBuf,
            ctrlRecordIndex,
            swapPositionsFlag,
            invulnerabilityCountdown);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.PlayerSnapshot snapshot) {
    if (playerType != snapshot.playerType() || isMainCharacter != snapshot.mainCharacter()) {
        throw new IllegalStateException("Sonic 2 special-stage player topology changed during rewind restore");
    }
    routine = snapshot.routine();
    routineSecondary = snapshot.routineSecondary();
    ssXPos = snapshot.ssXPos();
    ssXSub = snapshot.ssXSub();
    ssYPos = snapshot.ssYPos();
    ssYSub = snapshot.ssYSub();
    ssZPos = snapshot.ssZPos();
    xPos = snapshot.xPos();
    yPos = snapshot.yPos();
    xVel = snapshot.xVel();
    yVel = snapshot.yVel();
    inertia = snapshot.inertia();
    angle = snapshot.angle();
    ssSlideTimer = snapshot.ssSlideTimer();
    ssHurtTimer = snapshot.ssHurtTimer();
    ssDplcTimer = snapshot.ssDplcTimer();
    ssInitFlipTimer = snapshot.ssInitFlipTimer();
    ssFlipTimer = snapshot.ssFlipTimer();
    ssLastAngleIndex = snapshot.ssLastAngleIndex();
    anim = snapshot.anim();
    prevAnim = snapshot.prevAnim();
    animFrame = snapshot.animFrame();
    animFrameDuration = snapshot.animFrameDuration();
    mappingFrame = snapshot.mappingFrame();
    yRadius = snapshot.yRadius();
    xRadius = snapshot.xRadius();
    priority = snapshot.priority();
    statusXFlip = snapshot.statusXFlip();
    statusYFlip = snapshot.statusYFlip();
    statusJumping = snapshot.statusJumping();
    statusSlowing = snapshot.statusSlowing();
    renderXFlip = snapshot.renderXFlip();
    renderYFlip = snapshot.renderYFlip();
    collisionProperty = snapshot.collisionProperty();
    globalAnimFrameTimer = snapshot.globalAnimFrameTimer();
    ctrlRecordBuf = Sonic2SpecialStageSnapshot.cloneIntArray(snapshot.ctrlRecordBuf());
    ctrlRecordIndex = snapshot.ctrlRecordIndex();
    swapPositionsFlag = snapshot.swapPositionsFlag();
    invulnerabilityCountdown = snapshot.invulnerabilityCountdown();
}
```

- [ ] **Step 6: Add manager topology restore helper**

Edit `Sonic2SpecialStageManager.java`.

Add package-local helper:

```java
void restorePlayerTopologyForRewind(
        Sonic2SpecialStageSnapshot.PlayerTopologySnapshot topology,
        java.util.List<Sonic2SpecialStageSnapshot.PlayerSnapshot> playerSnapshots) {
    if (topology.slots().size() != players.size() || topology.slots().size() != playerSnapshots.size()) {
        throw new IllegalStateException("Sonic 2 special-stage player count changed during rewind restore");
    }
    for (int i = 0; i < players.size(); i++) {
        Sonic2SpecialStagePlayer player = players.get(i);
        Sonic2SpecialStageSnapshot.PlayerSlotSnapshot slot = topology.slots().get(i);
        if (player.getPlayerType() != slot.type() || player.isMainCharacter() != slot.mainCharacter()) {
            throw new IllegalStateException("Sonic 2 special-stage player topology changed during rewind restore");
        }
        player.restoreRewindSnapshot(playerSnapshots.get(i));
    }
    sonicPlayer = topology.sonicSlotIndex() >= 0 ? players.get(topology.sonicSlotIndex()) : null;
    tailsPlayer = topology.tailsSlotIndex() >= 0 ? players.get(topology.tailsSlotIndex()) : null;
    if (topology.playersLinked()) {
        sonicPlayer.setOtherPlayer(tailsPlayer);
        tailsPlayer.setOtherPlayer(sonicPlayer);
    } else {
        if (sonicPlayer != null) sonicPlayer.setOtherPlayer(null);
        if (tailsPlayer != null) tailsPlayer.setOtherPlayer(null);
    }
    if (renderer != null) {
        renderer.setPlayers(players);
    }
}
```

- [ ] **Step 7: Run player tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStagePlayerSnapshot" test
```

Expected: pass.

- [ ] **Step 8: Commit Task 3**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStagePlayer.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStagePlayerSnapshot.java `
        CHANGELOG.md
git commit -m "feat: snapshot Sonic 2 special stage players" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use `Changelog: updated`.

## Task 4: Intro And Checkpoint Snapshots

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageIntro.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageCheckpoint.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageCheckpointSnapshot.java`

- [ ] **Step 1: Write failing intro/checkpoint snapshot tests**

Create `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageCheckpointSnapshot.java`:

```java
package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestSonic2SpecialStageCheckpointSnapshot {
    @Test
    void checkpointSnapshotRestoresMessageRainbowAndPreservesCallbacks() throws Exception {
        Sonic2SpecialStageCheckpoint checkpoint = new Sonic2SpecialStageCheckpoint();
        AtomicInteger resolved = new AtomicInteger();
        Runnable musicCallback = resolved::incrementAndGet;
        Sonic2SpecialStageCheckpoint.CheckpointResolvedCallback checkpointCallback =
                (result, checkpointNumber, ringRequirement, ringsCollected, finalCheckpoint) ->
                        resolved.addAndGet(checkpointNumber);
        checkpoint.setOnMusicFadeRequested(musicCallback);
        checkpoint.setOnCheckpointResolved(checkpointCallback);
        checkpoint.beginCheckpoint(2, 80, 64, false);
        checkpoint.update(true);
        set(checkpoint, "phaseTimer", 33);
        set(checkpoint, "handY", 99);

        Sonic2SpecialStageSnapshot.CheckpointSnapshot snapshot = checkpoint.captureRewindSnapshot();
        checkpoint.reset();
        checkpoint.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic2SpecialStageCheckpoint.MessagePhase.RAINBOW_RINGS, checkpoint.getPhase());
        assertEquals(33, get(checkpoint, "phaseTimer"));
        assertEquals(99, checkpoint.getHandY());
        assertSame(musicCallback, get(checkpoint, "onMusicFadeRequested"));
        assertSame(checkpointCallback, get(checkpoint, "onCheckpointResolved"));
    }

    @Test
    void introSnapshotRestoresLettersAndBannerState() throws Exception {
        Sonic2SpecialStageIntro intro = new Sonic2SpecialStageIntro();
        intro.initialize(0, 50);
        set(intro, "currentPhase", Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT);
        set(intro, "phaseTimer", 12);
        set(intro, "frameCounter", 34);
        set(intro, "bannerX", 56);
        set(intro, "bannerY", 78);
        set(intro, "messageVisible", true);

        Sonic2SpecialStageSnapshot.IntroSnapshot snapshot = intro.captureRewindSnapshot();
        intro.initialize(1, 90);
        intro.restoreRewindSnapshot(snapshot);

        assertEquals(Sonic2SpecialStageIntro.Phase.MESSAGE_FLYOUT, intro.getCurrentPhase());
        assertEquals(34, intro.getFrameCounter());
        assertEquals(56, intro.getBannerX());
        assertEquals(78, intro.getBannerY());
        assertEquals(50, intro.getRingRequirement());
        assertEquals(snapshot.messageLetters().size(), intro.getMessageLetters().size());
        assertEquals(snapshot.bannerLetters().size(), intro.getBannerLetters().size());
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
```

- [ ] **Step 2: Run failing intro/checkpoint test**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageCheckpointSnapshot" test
```

Expected: compile failure because snapshot records and methods do not exist.

- [ ] **Step 3: Add intro/checkpoint snapshot records**

Edit `Sonic2SpecialStageSnapshot.java`.

Add records:

```java
record IntroMessageLetterSnapshot(int x, int y, int tileOffset, double flyoutAngle,
                                  int flyoutSpeed, boolean visible) {
}

record IntroBannerLetterSnapshot(int x, int y, int frame, double flyoutAngle,
                                 int flyoutSpeed, boolean visible) {
}

record IntroSnapshot(
        Sonic2SpecialStageIntro.Phase currentPhase,
        int phaseTimer,
        int frameCounter,
        int bannerX,
        int bannerY,
        boolean bannerVisible,
        int messageX,
        int messageY,
        boolean messageVisible,
        int ringRequirement,
        boolean lettersFlying,
        int letterFlyoutProgress,
        boolean messageFlyoutInitialized,
        boolean bannerFlyoutInitialized,
        List<IntroMessageLetterSnapshot> messageLetters,
        List<IntroBannerLetterSnapshot> bannerLetters) {
    IntroSnapshot {
        messageLetters = List.copyOf(messageLetters);
        bannerLetters = List.copyOf(bannerLetters);
    }
}

record CheckpointMessageLetterSnapshot(int x, int y, int tileOffset, int flyoutAngle,
                                       int flyoutSpeed, boolean visible) {
}

record CheckpointRainbowRingSnapshot(int baseIndex, int frameIndex, int positionOffset,
                                     int mappingFrame, int x, int y, boolean active) {
}

record CheckpointSnapshot(
        Sonic2SpecialStageCheckpoint.MessagePhase phase,
        int phaseTimer,
        Sonic2SpecialStageCheckpoint.Result lastResult,
        int currentCheckpoint,
        int ringRequirement,
        int ringsCollected,
        List<CheckpointMessageLetterSnapshot> messageLetters,
        boolean showCheckpointHand,
        int handX,
        int handY,
        int handTargetY,
        boolean handThumbsUp,
        boolean handMovingDown,
        List<CheckpointRainbowRingSnapshot> rainbowRings,
        int pendingRingRequirement,
        int pendingRingsCollected,
        boolean pendingFinalCheckpoint,
        boolean rainbowOnly) {
    CheckpointSnapshot {
        messageLetters = List.copyOf(messageLetters);
        rainbowRings = List.copyOf(rainbowRings);
    }
}
```

- [ ] **Step 4: Implement intro capture/restore**

Edit `Sonic2SpecialStageIntro.java`.

Add package-local methods that copy list entries by value:

```java
Sonic2SpecialStageSnapshot.IntroSnapshot captureRewindSnapshot() {
    java.util.ArrayList<Sonic2SpecialStageSnapshot.IntroMessageLetterSnapshot> message = new java.util.ArrayList<>();
    for (MessageLetter letter : messageLetters) {
        message.add(new Sonic2SpecialStageSnapshot.IntroMessageLetterSnapshot(
                letter.x, letter.y, letter.tileOffset, letter.flyoutAngle, letter.flyoutSpeed, letter.visible));
    }
    java.util.ArrayList<Sonic2SpecialStageSnapshot.IntroBannerLetterSnapshot> banner = new java.util.ArrayList<>();
    for (BannerLetter letter : bannerLetters) {
        banner.add(new Sonic2SpecialStageSnapshot.IntroBannerLetterSnapshot(
                letter.x, letter.y, letter.frame, letter.flyoutAngle, letter.flyoutSpeed, letter.visible));
    }
    return new Sonic2SpecialStageSnapshot.IntroSnapshot(
            currentPhase, phaseTimer, frameCounter, bannerX, bannerY, bannerVisible,
            messageX, messageY, messageVisible, ringRequirement, lettersFlying,
            letterFlyoutProgress, messageFlyoutInitialized, bannerFlyoutInitialized,
            message, banner);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.IntroSnapshot snapshot) {
    currentPhase = snapshot.currentPhase();
    phaseTimer = snapshot.phaseTimer();
    frameCounter = snapshot.frameCounter();
    bannerX = snapshot.bannerX();
    bannerY = snapshot.bannerY();
    bannerVisible = snapshot.bannerVisible();
    messageX = snapshot.messageX();
    messageY = snapshot.messageY();
    messageVisible = snapshot.messageVisible();
    ringRequirement = snapshot.ringRequirement();
    lettersFlying = snapshot.lettersFlying();
    letterFlyoutProgress = snapshot.letterFlyoutProgress();
    messageFlyoutInitialized = snapshot.messageFlyoutInitialized();
    bannerFlyoutInitialized = snapshot.bannerFlyoutInitialized();
    messageLetters.clear();
    for (Sonic2SpecialStageSnapshot.IntroMessageLetterSnapshot letter : snapshot.messageLetters()) {
        MessageLetter restored = new MessageLetter(letter.x(), letter.y(), letter.tileOffset());
        restored.flyoutAngle = letter.flyoutAngle();
        restored.flyoutSpeed = letter.flyoutSpeed();
        restored.visible = letter.visible();
        messageLetters.add(restored);
    }
    bannerLetters.clear();
    for (Sonic2SpecialStageSnapshot.IntroBannerLetterSnapshot letter : snapshot.bannerLetters()) {
        BannerLetter restored = new BannerLetter(letter.x(), letter.y(), letter.frame());
        restored.flyoutAngle = letter.flyoutAngle();
        restored.flyoutSpeed = letter.flyoutSpeed();
        restored.visible = letter.visible();
        bannerLetters.add(restored);
    }
}
```

- [ ] **Step 5: Implement checkpoint capture/restore**

Edit `Sonic2SpecialStageCheckpoint.java`.

Add package-local methods that preserve `onCheckpointResolved` and `onMusicFadeRequested` by not assigning them in restore:

```java
Sonic2SpecialStageSnapshot.CheckpointSnapshot captureRewindSnapshot() {
    java.util.ArrayList<Sonic2SpecialStageSnapshot.CheckpointMessageLetterSnapshot> letters =
            new java.util.ArrayList<>();
    for (MessageLetter letter : messageLetters) {
        letters.add(new Sonic2SpecialStageSnapshot.CheckpointMessageLetterSnapshot(
                letter.x, letter.y, letter.tileOffset, letter.flyoutAngle,
                letter.flyoutSpeed, letter.visible));
    }
    java.util.ArrayList<Sonic2SpecialStageSnapshot.CheckpointRainbowRingSnapshot> rings =
            new java.util.ArrayList<>();
    for (RainbowRing ring : rainbowRings) {
        rings.add(new Sonic2SpecialStageSnapshot.CheckpointRainbowRingSnapshot(
                ring.baseIndex, ring.frameIndex, ring.positionOffset,
                ring.mappingFrame, ring.x, ring.y, ring.active));
    }
    return new Sonic2SpecialStageSnapshot.CheckpointSnapshot(
            phase, phaseTimer, lastResult, currentCheckpoint, ringRequirement,
            ringsCollected, letters, showCheckpointHand, handX, handY, handTargetY,
            handThumbsUp, handMovingDown, rings, pendingRingRequirement,
            pendingRingsCollected, pendingFinalCheckpoint, rainbowOnly);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.CheckpointSnapshot snapshot) {
    phase = snapshot.phase();
    phaseTimer = snapshot.phaseTimer();
    lastResult = snapshot.lastResult();
    currentCheckpoint = snapshot.currentCheckpoint();
    ringRequirement = snapshot.ringRequirement();
    ringsCollected = snapshot.ringsCollected();
    showCheckpointHand = snapshot.showCheckpointHand();
    handX = snapshot.handX();
    handY = snapshot.handY();
    handTargetY = snapshot.handTargetY();
    handThumbsUp = snapshot.handThumbsUp();
    handMovingDown = snapshot.handMovingDown();
    pendingRingRequirement = snapshot.pendingRingRequirement();
    pendingRingsCollected = snapshot.pendingRingsCollected();
    pendingFinalCheckpoint = snapshot.pendingFinalCheckpoint();
    rainbowOnly = snapshot.rainbowOnly();
    messageLetters.clear();
    for (Sonic2SpecialStageSnapshot.CheckpointMessageLetterSnapshot letter : snapshot.messageLetters()) {
        MessageLetter restored = new MessageLetter(letter.x(), letter.y(), letter.tileOffset());
        restored.flyoutAngle = letter.flyoutAngle();
        restored.flyoutSpeed = letter.flyoutSpeed();
        restored.visible = letter.visible();
        messageLetters.add(restored);
    }
    rainbowRings.clear();
    for (Sonic2SpecialStageSnapshot.CheckpointRainbowRingSnapshot ring : snapshot.rainbowRings()) {
        RainbowRing restored = new RainbowRing(ring.baseIndex());
        restored.frameIndex = ring.frameIndex();
        restored.positionOffset = ring.positionOffset();
        restored.mappingFrame = ring.mappingFrame();
        restored.x = ring.x();
        restored.y = ring.y();
        restored.active = ring.active();
        rainbowRings.add(restored);
    }
}
```

Because these methods live inside `Sonic2SpecialStageCheckpoint`, they can access `RainbowRing` private fields and constructor directly. Do not expose public setters for test-only access.

- [ ] **Step 6: Run checkpoint tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageCheckpointSnapshot" test
```

Expected: pass.

- [ ] **Step 7: Commit Task 4**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageIntro.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageCheckpoint.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageCheckpointSnapshot.java `
        CHANGELOG.md
git commit -m "feat: snapshot Sonic 2 special stage messages" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use `Changelog: updated`.

## Task 5: Object Snapshot And Reconstruction

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageObject.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRing.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageBomb.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageEmerald.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageObjectSnapshot.java`

- [ ] **Step 1: Write failing object snapshot tests**

Create `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageObjectSnapshot.java`:

```java
package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestSonic2SpecialStageObjectSnapshot {
    @Test
    void objectManagerRestoresOrderedConcreteObjectsAndCounters() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);

        Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
        ring.initialize(32, 0x40);
        ring.collect();

        Sonic2SpecialStageBomb bomb = new Sonic2SpecialStageBomb();
        bomb.initialize(40, 0x50);
        bomb.explode();

        Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
        emerald.initialize(54, 0x40);
        emerald.setRingRequirement(120);
        emerald.setManager(manager);

        objectManager.getActiveObjects().add(ring);
        objectManager.getActiveObjects().add(bomb);
        objectManager.getActiveObjects().add(emerald);
        set(objectManager, "ringsCollected", 44);
        set(objectManager, "perfectRingsTotal", 55);
        set(objectManager, "currentSpecialAct", 3);
        set(objectManager, "ringsToGoEnabled", true);
        set(objectManager, "emeraldSpawned", true);

        Sonic2SpecialStageSnapshot.ObjectManagerSnapshot snapshot =
                objectManager.captureRewindSnapshot();

        objectManager.reset();
        objectManager.restoreRewindSnapshot(snapshot, manager);

        assertEquals(44, objectManager.getRingsCollected());
        assertEquals(55, objectManager.getPerfectRingsTotal());
        assertEquals(3, objectManager.getCurrentSpecialAct());
        assertEquals(true, objectManager.isRingsToGoEnabled());
        assertEquals(true, objectManager.isEmeraldSpawned());

        List<Sonic2SpecialStageObject> restored = objectManager.getActiveObjects();
        assertInstanceOf(Sonic2SpecialStageRing.class, restored.get(0));
        assertInstanceOf(Sonic2SpecialStageBomb.class, restored.get(1));
        Sonic2SpecialStageEmerald restoredEmerald =
                assertInstanceOf(Sonic2SpecialStageEmerald.class, restored.get(2));
        assertSame(manager, get(restoredEmerald, "manager"));
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
```

- [ ] **Step 2: Run failing object tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageObjectSnapshot" test
```

Expected: compile failure because object snapshot APIs do not exist.

- [ ] **Step 3: Add object snapshot records**

Edit `Sonic2SpecialStageSnapshot.java`.

Add:

```java
enum SpecialStageObjectType {
    RING,
    BOMB,
    EMERALD
}

record BaseObjectSnapshot(
        Sonic2SpecialStageObject.State state,
        int angle,
        long depthFixed,
        int screenX,
        int screenY,
        int trackFloorY,
        int animIndex,
        int animFrame,
        int animTimer,
        boolean onScreen,
        boolean highPriority) {
}

record ObjectSnapshot(
        SpecialStageObjectType type,
        BaseObjectSnapshot base,
        Integer ringSpinFrame,
        Sonic2SpecialStageEmerald.EmeraldPhase emeraldPhase,
        int emeraldPhaseTimer,
        int emeraldBobbingOffset,
        int emeraldBobbingCounter,
        int emeraldRingRequirement,
        boolean emeraldMusicFaded,
        boolean emeraldAwarded) {
}

record ObjectManagerSnapshot(
        byte[] objectLocationData,
        int[] stageOffsets,
        int currentPosition,
        int currentStage,
        int lastProcessedSegment,
        int ringsCollected,
        int perfectRingsTotal,
        int currentSpecialAct,
        boolean noCheckpointFlag,
        boolean noCheckpointMsgFlag,
        boolean ringsToGoEnabled,
        boolean emeraldSpawned,
        List<ObjectSnapshot> activeObjects) {
    ObjectManagerSnapshot {
        objectLocationData = Sonic2SpecialStageSnapshot.cloneByteArray(objectLocationData);
        stageOffsets = Sonic2SpecialStageSnapshot.cloneIntArray(stageOffsets);
        activeObjects = List.copyOf(activeObjects);
    }
}
```

- [ ] **Step 4: Implement object base capture/restore**

Edit `Sonic2SpecialStageObject.java`.

Add:

```java
Sonic2SpecialStageSnapshot.BaseObjectSnapshot captureBaseRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot.BaseObjectSnapshot(
            state, angle, depthFixed, screenX, screenY, trackFloorY,
            animIndex, animFrame, animTimer, onScreen, highPriority);
}

void restoreBaseRewindSnapshot(Sonic2SpecialStageSnapshot.BaseObjectSnapshot snapshot) {
    state = snapshot.state();
    angle = snapshot.angle();
    depthFixed = snapshot.depthFixed();
    screenX = snapshot.screenX();
    screenY = snapshot.screenY();
    trackFloorY = snapshot.trackFloorY();
    animIndex = snapshot.animIndex();
    animFrame = snapshot.animFrame();
    animTimer = snapshot.animTimer();
    onScreen = snapshot.onScreen();
    highPriority = snapshot.highPriority();
}

abstract Sonic2SpecialStageSnapshot.ObjectSnapshot captureRewindSnapshot();
```

- [ ] **Step 5: Implement concrete object capture/restore**

In `Sonic2SpecialStageRing.java`, add:

```java
@Override
Sonic2SpecialStageSnapshot.ObjectSnapshot captureRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot.ObjectSnapshot(
            Sonic2SpecialStageSnapshot.SpecialStageObjectType.RING,
            captureBaseRewindSnapshot(),
            spinFrame,
            null,
            0,
            0,
            0,
            0,
            false,
            false);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.ObjectSnapshot snapshot) {
    restoreBaseRewindSnapshot(snapshot.base());
    spinFrame = snapshot.ringSpinFrame() != null ? snapshot.ringSpinFrame() : 0;
}
```

In `Sonic2SpecialStageBomb.java`, add:

```java
@Override
Sonic2SpecialStageSnapshot.ObjectSnapshot captureRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot.ObjectSnapshot(
            Sonic2SpecialStageSnapshot.SpecialStageObjectType.BOMB,
            captureBaseRewindSnapshot(),
            null,
            null,
            0,
            0,
            0,
            0,
            false,
            false);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.ObjectSnapshot snapshot) {
    restoreBaseRewindSnapshot(snapshot.base());
}
```

In `Sonic2SpecialStageEmerald.java`, add:

```java
@Override
Sonic2SpecialStageSnapshot.ObjectSnapshot captureRewindSnapshot() {
    return new Sonic2SpecialStageSnapshot.ObjectSnapshot(
            Sonic2SpecialStageSnapshot.SpecialStageObjectType.EMERALD,
            captureBaseRewindSnapshot(),
            null,
            phase,
            phaseTimer,
            bobbingOffset,
            bobbingCounter,
            ringRequirement,
            musicFaded,
            emeraldAwarded);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.ObjectSnapshot snapshot,
                           Sonic2SpecialStageManager manager) {
    restoreBaseRewindSnapshot(snapshot.base());
    phase = snapshot.emeraldPhase();
    phaseTimer = snapshot.emeraldPhaseTimer();
    bobbingOffset = snapshot.emeraldBobbingOffset();
    bobbingCounter = snapshot.emeraldBobbingCounter();
    ringRequirement = snapshot.emeraldRingRequirement();
    musicFaded = snapshot.emeraldMusicFaded();
    emeraldAwarded = snapshot.emeraldAwarded();
    this.manager = manager;
}
```

- [ ] **Step 6: Implement object manager capture/restore**

Edit nested `Sonic2SpecialStageObjectManager` in `Sonic2SpecialStageManager.java`.

Add:

```java
Sonic2SpecialStageSnapshot.ObjectManagerSnapshot captureRewindSnapshot() {
    java.util.ArrayList<Sonic2SpecialStageSnapshot.ObjectSnapshot> objects =
            new java.util.ArrayList<>();
    for (Sonic2SpecialStageObject object : activeObjects) {
        objects.add(object.captureRewindSnapshot());
    }
    return new Sonic2SpecialStageSnapshot.ObjectManagerSnapshot(
            objectLocationData,
            stageOffsets,
            currentPosition,
            currentStage,
            lastProcessedSegment,
            ringsCollected,
            perfectRingsTotal,
            currentSpecialAct,
            noCheckpointFlag,
            noCheckpointMsgFlag,
            ringsToGoEnabled,
            emeraldSpawned,
            objects);
}

void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.ObjectManagerSnapshot snapshot,
                           Sonic2SpecialStageManager owner) {
    objectLocationData = Sonic2SpecialStageSnapshot.cloneByteArray(snapshot.objectLocationData());
    stageOffsets = Sonic2SpecialStageSnapshot.cloneIntArray(snapshot.stageOffsets());
    currentPosition = snapshot.currentPosition();
    currentStage = snapshot.currentStage();
    lastProcessedSegment = snapshot.lastProcessedSegment();
    ringsCollected = snapshot.ringsCollected();
    perfectRingsTotal = snapshot.perfectRingsTotal();
    currentSpecialAct = snapshot.currentSpecialAct();
    noCheckpointFlag = snapshot.noCheckpointFlag();
    noCheckpointMsgFlag = snapshot.noCheckpointMsgFlag();
    ringsToGoEnabled = snapshot.ringsToGoEnabled();
    emeraldSpawned = snapshot.emeraldSpawned();
    activeObjects.clear();
    for (Sonic2SpecialStageSnapshot.ObjectSnapshot objectSnapshot : snapshot.activeObjects()) {
        Sonic2SpecialStageObject object = switch (objectSnapshot.type()) {
            case RING -> {
                Sonic2SpecialStageRing ring = new Sonic2SpecialStageRing();
                ring.restoreRewindSnapshot(objectSnapshot);
                yield ring;
            }
            case BOMB -> {
                Sonic2SpecialStageBomb bomb = new Sonic2SpecialStageBomb();
                bomb.restoreRewindSnapshot(objectSnapshot);
                yield bomb;
            }
            case EMERALD -> {
                Sonic2SpecialStageEmerald emerald = new Sonic2SpecialStageEmerald();
                emerald.restoreRewindSnapshot(objectSnapshot, owner);
                yield emerald;
            }
        };
        activeObjects.add(object);
    }
}
```

- [ ] **Step 7: Run object tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageObjectSnapshot" test
```

Expected: pass.

- [ ] **Step 8: Commit Task 5**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageObject.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRing.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageBomb.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageEmerald.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageObjectSnapshot.java `
        CHANGELOG.md
git commit -m "feat: snapshot Sonic 2 special stage objects" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use `Changelog: updated`.

## Task 6: Manager Snapshot, Palette Restore, And Integration Tests

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java`
- Modify: `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`
- Modify: `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`
- Create: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java`

- [ ] **Step 1: Write failing manager snapshot tests**

Create `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java`:

```java
package com.openggf.game.sonic2.specialstage;

import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2SpecialStageRewindSnapshot {
    @Test
    void managerSnapshotRestoresScalarsNestedStateAndPalettes() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        manager.reset();
        seedMinimalInitializedGraph(manager);
        set(manager, "initialized", true);
        set(manager, "currentStage", 2);
        set(manager, "resultState", Sonic2SpecialStageManager.ResultState.RUNNING);
        set(manager, "emeraldCollected", false);
        set(manager, "frameCounter", 123);
        set(manager, "heldButtons", 0x11);
        set(manager, "pressedButtons", 0x22);
        set(manager, "p2HeldButtons", 0x33);
        set(manager, "p2LogicalButtons", 0x44);
        set(manager, "lagCompensation", 0.25);
        set(manager, "lagAccumulator", 0.75);
        set(manager, "decodedTrackFrame", new int[] { 1, 2, 3 });
        Palette[] palettes = createPalettes(10);
        set(manager, "palettes", palettes);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        palettes[3].setColor(11, new Palette.Color((byte) 99, (byte) 99, (byte) 99));
        set(manager, "frameCounter", 999);
        set(manager, "heldButtons", 999);
        set(manager, "lagCompensation", 0.5);
        set(manager, "decodedTrackFrame", new int[] { 9, 9, 9 });

        manager.restoreRewindSnapshot(snapshot);

        assertEquals(123, get(manager, "frameCounter"));
        assertEquals(0x11, get(manager, "heldButtons"));
        assertEquals(0.25, (double) get(manager, "lagCompensation"), 0.0001);
        int[] restoredFrame = (int[]) get(manager, "decodedTrackFrame");
        assertEquals(1, restoredFrame[0]);
        Palette[] restoredPalettes = (Palette[]) get(manager, "palettes");
        assertEquals(10, restoredPalettes[3].getColor(11).r);
        assertNotSame(snapshot.palettes, restoredPalettes);
    }

    @Test
    void managerSnapshotRestoresEmeraldAndCheckpointPalettePhases() throws Exception {
        assertPalettePhaseRoundTrips(false, 0, new int[] { 0x0EE, 0x044, 0x000 },
                "emerald baseline before checkpoint rainbow");
        assertPalettePhaseRoundTrips(false, 0, new int[] { 0x0EE, 0x088, 0x044 },
                "checkpoint rainbow cleared");
        assertPalettePhaseRoundTrips(true, 0, new int[] { 0x0EE, 0x0CC, 0x088 },
                "checkpoint rainbow enabled");
        assertPalettePhaseRoundTrips(true, 2, new int[] { 0x0EE, 0x044, 0x088 },
                "checkpoint rainbow cycle phase");
    }

    private static void assertPalettePhaseRoundTrips(boolean rainbowActive,
                                                     int rainbowCycleIndex,
                                                     int[] genesisColors,
                                                     String phaseName) throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Palette[] palettes = createPalettes(0);
        for (int i = 0; i < genesisColors.length; i++) {
            palettes[3].setColor(11 + i,
                    Sonic2SpecialStagePalette.genesisColorToPaletteColor(genesisColors[i]));
        }
        set(manager, "palettes", palettes);
        set(manager, "checkpointRainbowPaletteActive", rainbowActive);
        set(manager, "rainbowPaletteCycleIndex", rainbowCycleIndex);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        palettes[3].setColor(11, new Palette.Color((byte) 1, (byte) 2, (byte) 3));
        palettes[3].setColor(12, new Palette.Color((byte) 4, (byte) 5, (byte) 6));
        palettes[3].setColor(13, new Palette.Color((byte) 7, (byte) 8, (byte) 9));
        set(manager, "checkpointRainbowPaletteActive", !rainbowActive);
        set(manager, "rainbowPaletteCycleIndex", 99);

        manager.restoreRewindSnapshot(snapshot);

        Palette[] restored = (Palette[]) get(manager, "palettes");
        for (int i = 0; i < genesisColors.length; i++) {
            Palette.Color expected = Sonic2SpecialStagePalette.genesisColorToPaletteColor(genesisColors[i]);
            Palette.Color actual = restored[3].getColor(11 + i);
            assertEquals(expected.r, actual.r, phaseName + " red " + i);
            assertEquals(expected.g, actual.g, phaseName + " green " + i);
            assertEquals(expected.b, actual.b, phaseName + " blue " + i);
        }
        assertEquals(rainbowActive, get(manager, "checkpointRainbowPaletteActive"));
        assertEquals(rainbowCycleIndex, get(manager, "rainbowPaletteCycleIndex"));
    }

    @Test
    void restoreFailsFastWhenPlayerTopologyChanges() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        ((java.util.List<?>) get(manager, "players")).clear();

        assertThrows(IllegalStateException.class, () -> manager.restoreRewindSnapshot(snapshot));
    }

    @Test
    void restoreBindsRendererToAlignmentCheckpointWhenAlignmentModeIsActive() throws Exception {
        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();
        seedMinimalInitializedGraph(manager);
        Sonic2SpecialStageRenderer renderer = new Sonic2SpecialStageRenderer(null);
        Sonic2SpecialStageCheckpoint normalCheckpoint = new Sonic2SpecialStageCheckpoint();
        Sonic2SpecialStageCheckpoint alignmentCheckpoint = new Sonic2SpecialStageCheckpoint();
        set(manager, "renderer", renderer);
        set(manager, "checkpoint", normalCheckpoint);
        set(manager, "alignmentCheckpoint", alignmentCheckpoint);
        set(manager, "alignmentTestMode", true);
        renderer.setCheckpoint(normalCheckpoint);

        Sonic2SpecialStageSnapshot snapshot = manager.captureRewindSnapshot();
        renderer.setCheckpoint(normalCheckpoint);

        manager.restoreRewindSnapshot(snapshot);

        assertSame(alignmentCheckpoint, get(renderer, "checkpoint"));
    }

    private static void seedMinimalInitializedGraph(Sonic2SpecialStageManager manager) throws Exception {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        set(manager, "trackAnimator", animator);

        Sonic2SpecialStagePlayer sonic = new Sonic2SpecialStagePlayer(
                Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        ArrayList<Sonic2SpecialStagePlayer> players = new ArrayList<>();
        players.add(sonic);
        set(manager, "players", players);
        set(manager, "sonicPlayer", sonic);
        set(manager, "tailsPlayer", null);

        Sonic2SpecialStageIntro intro = new Sonic2SpecialStageIntro();
        intro.initialize(0, 50);
        set(manager, "intro", intro);

        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager =
                new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
        set(manager, "objectManager", objectManager);

        Sonic2SpecialStageCheckpoint checkpoint = new Sonic2SpecialStageCheckpoint();
        set(manager, "checkpoint", checkpoint);
        set(manager, "alignmentCheckpoint", null);
    }

    private static Palette[] createPalettes(int seed) {
        Palette[] palettes = new Palette[4];
        for (int line = 0; line < palettes.length; line++) {
            palettes[line] = new Palette();
            for (int color = 0; color < Palette.PALETTE_SIZE; color++) {
                palettes[line].setColor(color, new Palette.Color(
                        (byte) (seed + line + color),
                        (byte) (seed + line + color + 1),
                        (byte) (seed + line + color + 2)));
            }
        }
        return palettes;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
```

- [ ] **Step 2: Run failing manager snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindSnapshot" test
```

Expected: fail or compile fail because the manager snapshot does not yet include full fields and nested state.

- [ ] **Step 3: Expand `Sonic2SpecialStageSnapshot` constructor and fields**

Edit `Sonic2SpecialStageSnapshot.java`.

Replace the minimal fields/constructor from Task 1 with the full manager surface:

```java
final boolean initialized;
final int currentStage;
final Sonic2SpecialStageManager.ResultState resultState;
final boolean emeraldCollected;
final int frameCounter;
final int heldButtons;
final int pressedButtons;
final int p2HeldButtons;
final int p2LogicalButtons;
final int tailsControlCounter;
final int[] tailsCtrlRecordBuf;
final int lastDrawingIndex;
final boolean checkpointRainbowPaletteActive;
final int rainbowPaletteCycleIndex;
final boolean pendingCheckpoint;
final int pendingCheckpointNumber;
final int pendingRingRequirement;
final int pendingRingsCollected;
final boolean pendingFinalCheckpoint;
final int currentRingRequirement;
final boolean spriteDebugMode;
final Object planeDebugMode;
final boolean alignmentTestMode;
final boolean alignmentTestSavedRainbowPalette;
final boolean alignmentPendingCheckpoint;
final int alignmentFrameIndex;
final int alignmentFrameTimer;
final int alignmentTrackFrameIndex;
final int alignmentLastDecodedFrameIndex;
final int[] alignmentDecodedTrackFrame;
final int alignmentDrawingIndex;
final int alignmentTriggerOffsetFrames;
final double alignmentRainbowSpeedScale;
final double alignmentRainbowSpeedAccumulator;
final boolean alignmentStepByTrackFrame;
final double lagCompensation;
final double lagAccumulator;
final boolean lagCompensationDisplayEnabled;
final long diagnosticWallStartTime;
final int diagnosticUpdateCount;
final int diagnosticTrackAdvances;
final long lastFrameTime;
final int frameSampleCount;
final long frameSampleSum;
final int skydomeScrollX;
final boolean alternateScrollBuffer;
final boolean lastAlternateScrollBuffer;
final int drawingIndex;
final int lastAnimFrame;
final int vScrollBG;
final int hScrollDebugTotal;
final int hScrollDebugFrames;
final int lastDebugSegmentIndex;
final int[] decodedTrackFrame;
final int lastDecodedFrameIndex;
final boolean lastDecodedFlipped;
final Palette[] palettes;
final TrackAnimatorSnapshot trackAnimator;
final PlayerTopologySnapshot playerTopology;
final List<PlayerSnapshot> players;
final IntroSnapshot intro;
final ObjectManagerSnapshot objectManager;
final CheckpointSnapshot checkpoint;
final CheckpointSnapshot alignmentCheckpoint;
```

Use a constructor that assigns each scalar and clones arrays/palettes/lists:

```java
Sonic2SpecialStageSnapshot(
        boolean initialized,
        int currentStage,
        Sonic2SpecialStageManager.ResultState resultState,
        boolean emeraldCollected,
        int frameCounter,
        int heldButtons,
        int pressedButtons,
        int p2HeldButtons,
        int p2LogicalButtons,
        int tailsControlCounter,
        int[] tailsCtrlRecordBuf,
        int lastDrawingIndex,
        boolean checkpointRainbowPaletteActive,
        int rainbowPaletteCycleIndex,
        boolean pendingCheckpoint,
        int pendingCheckpointNumber,
        int pendingRingRequirement,
        int pendingRingsCollected,
        boolean pendingFinalCheckpoint,
        int currentRingRequirement,
        boolean spriteDebugMode,
        Object planeDebugMode,
        boolean alignmentTestMode,
        boolean alignmentTestSavedRainbowPalette,
        boolean alignmentPendingCheckpoint,
        int alignmentFrameIndex,
        int alignmentFrameTimer,
        int alignmentTrackFrameIndex,
        int alignmentLastDecodedFrameIndex,
        int[] alignmentDecodedTrackFrame,
        int alignmentDrawingIndex,
        int alignmentTriggerOffsetFrames,
        double alignmentRainbowSpeedScale,
        double alignmentRainbowSpeedAccumulator,
        boolean alignmentStepByTrackFrame,
        double lagCompensation,
        double lagAccumulator,
        boolean lagCompensationDisplayEnabled,
        long diagnosticWallStartTime,
        int diagnosticUpdateCount,
        int diagnosticTrackAdvances,
        long lastFrameTime,
        int frameSampleCount,
        long frameSampleSum,
        int skydomeScrollX,
        boolean alternateScrollBuffer,
        boolean lastAlternateScrollBuffer,
        int drawingIndex,
        int lastAnimFrame,
        int vScrollBG,
        int hScrollDebugTotal,
        int hScrollDebugFrames,
        int lastDebugSegmentIndex,
        int[] decodedTrackFrame,
        int lastDecodedFrameIndex,
        boolean lastDecodedFlipped,
        Palette[] palettes,
        TrackAnimatorSnapshot trackAnimator,
        PlayerTopologySnapshot playerTopology,
        List<PlayerSnapshot> players,
        IntroSnapshot intro,
        ObjectManagerSnapshot objectManager,
        CheckpointSnapshot checkpoint,
        CheckpointSnapshot alignmentCheckpoint) {
    this.initialized = initialized;
    this.currentStage = currentStage;
    this.resultState = resultState;
    this.emeraldCollected = emeraldCollected;
    this.frameCounter = frameCounter;
    this.heldButtons = heldButtons;
    this.pressedButtons = pressedButtons;
    this.p2HeldButtons = p2HeldButtons;
    this.p2LogicalButtons = p2LogicalButtons;
    this.tailsControlCounter = tailsControlCounter;
    this.tailsCtrlRecordBuf = cloneIntArray(tailsCtrlRecordBuf);
    this.lastDrawingIndex = lastDrawingIndex;
    this.checkpointRainbowPaletteActive = checkpointRainbowPaletteActive;
    this.rainbowPaletteCycleIndex = rainbowPaletteCycleIndex;
    this.pendingCheckpoint = pendingCheckpoint;
    this.pendingCheckpointNumber = pendingCheckpointNumber;
    this.pendingRingRequirement = pendingRingRequirement;
    this.pendingRingsCollected = pendingRingsCollected;
    this.pendingFinalCheckpoint = pendingFinalCheckpoint;
    this.currentRingRequirement = currentRingRequirement;
    this.spriteDebugMode = spriteDebugMode;
    this.planeDebugMode = planeDebugMode;
    this.alignmentTestMode = alignmentTestMode;
    this.alignmentTestSavedRainbowPalette = alignmentTestSavedRainbowPalette;
    this.alignmentPendingCheckpoint = alignmentPendingCheckpoint;
    this.alignmentFrameIndex = alignmentFrameIndex;
    this.alignmentFrameTimer = alignmentFrameTimer;
    this.alignmentTrackFrameIndex = alignmentTrackFrameIndex;
    this.alignmentLastDecodedFrameIndex = alignmentLastDecodedFrameIndex;
    this.alignmentDecodedTrackFrame = cloneIntArray(alignmentDecodedTrackFrame);
    this.alignmentDrawingIndex = alignmentDrawingIndex;
    this.alignmentTriggerOffsetFrames = alignmentTriggerOffsetFrames;
    this.alignmentRainbowSpeedScale = alignmentRainbowSpeedScale;
    this.alignmentRainbowSpeedAccumulator = alignmentRainbowSpeedAccumulator;
    this.alignmentStepByTrackFrame = alignmentStepByTrackFrame;
    this.lagCompensation = lagCompensation;
    this.lagAccumulator = lagAccumulator;
    this.lagCompensationDisplayEnabled = lagCompensationDisplayEnabled;
    this.diagnosticWallStartTime = diagnosticWallStartTime;
    this.diagnosticUpdateCount = diagnosticUpdateCount;
    this.diagnosticTrackAdvances = diagnosticTrackAdvances;
    this.lastFrameTime = lastFrameTime;
    this.frameSampleCount = frameSampleCount;
    this.frameSampleSum = frameSampleSum;
    this.skydomeScrollX = skydomeScrollX;
    this.alternateScrollBuffer = alternateScrollBuffer;
    this.lastAlternateScrollBuffer = lastAlternateScrollBuffer;
    this.drawingIndex = drawingIndex;
    this.lastAnimFrame = lastAnimFrame;
    this.vScrollBG = vScrollBG;
    this.hScrollDebugTotal = hScrollDebugTotal;
    this.hScrollDebugFrames = hScrollDebugFrames;
    this.lastDebugSegmentIndex = lastDebugSegmentIndex;
    this.decodedTrackFrame = cloneIntArray(decodedTrackFrame);
    this.lastDecodedFrameIndex = lastDecodedFrameIndex;
    this.lastDecodedFlipped = lastDecodedFlipped;
    this.palettes = clonePalettes(palettes);
    this.trackAnimator = trackAnimator;
    this.playerTopology = playerTopology;
    this.players = List.copyOf(players);
    this.intro = intro;
    this.objectManager = objectManager;
    this.checkpoint = checkpoint;
    this.alignmentCheckpoint = alignmentCheckpoint;
}
```

The constructor above must be kept in the same order as the `new Sonic2SpecialStageSnapshot(...)` call in `Sonic2SpecialStageManager.captureRewindSnapshot()`. These clone assignments are load-bearing:

```java
this.tailsCtrlRecordBuf = cloneIntArray(tailsCtrlRecordBuf);
this.alignmentDecodedTrackFrame = cloneIntArray(alignmentDecodedTrackFrame);
this.decodedTrackFrame = cloneIntArray(decodedTrackFrame);
this.palettes = clonePalettes(palettes);
this.players = List.copyOf(players);
```

Keep `planeDebugMode` as `Object` because `PlaneDebugMode` is currently private inside the manager. The implementation plan intentionally avoids making that enum public.

- [ ] **Step 4: Implement full manager capture**

Edit `Sonic2SpecialStageManager.java`.

Replace the minimal `captureRewindSnapshot()` with a full constructor call:

```java
Sonic2SpecialStageSnapshot captureRewindSnapshot() {
    java.util.ArrayList<Sonic2SpecialStageSnapshot.PlayerSnapshot> playerSnapshots =
            new java.util.ArrayList<>();
    for (Sonic2SpecialStagePlayer player : players) {
        playerSnapshots.add(player.captureRewindSnapshot());
    }
    return new Sonic2SpecialStageSnapshot(
            initialized,
            currentStage,
            resultState,
            emeraldCollected,
            frameCounter,
            heldButtons,
            pressedButtons,
            p2HeldButtons,
            p2LogicalButtons,
            tailsControlCounter,
            tailsCtrlRecordBuf,
            lastDrawingIndex,
            checkpointRainbowPaletteActive,
            rainbowPaletteCycleIndex,
            pendingCheckpoint,
            pendingCheckpointNumber,
            pendingRingRequirement,
            pendingRingsCollected,
            pendingFinalCheckpoint,
            currentRingRequirement,
            spriteDebugMode,
            planeDebugMode,
            alignmentTestMode,
            alignmentTestSavedRainbowPalette,
            alignmentPendingCheckpoint,
            alignmentFrameIndex,
            alignmentFrameTimer,
            alignmentTrackFrameIndex,
            alignmentLastDecodedFrameIndex,
            alignmentDecodedTrackFrame,
            alignmentDrawingIndex,
            alignmentTriggerOffsetFrames,
            alignmentRainbowSpeedScale,
            alignmentRainbowSpeedAccumulator,
            alignmentStepByTrackFrame,
            lagCompensation,
            lagAccumulator,
            lagCompensationDisplayEnabled,
            diagnosticWallStartTime,
            diagnosticUpdateCount,
            diagnosticTrackAdvances,
            lastFrameTime,
            frameSampleCount,
            frameSampleSum,
            skydomeScrollX,
            alternateScrollBuffer,
            lastAlternateScrollBuffer,
            drawingIndex,
            lastAnimFrame,
            vScrollBG,
            hScrollDebugTotal,
            hScrollDebugFrames,
            lastDebugSegmentIndex,
            decodedTrackFrame,
            lastDecodedFrameIndex,
            lastDecodedFlipped,
            palettes,
            trackAnimator != null ? trackAnimator.captureRewindSnapshot() : null,
            Sonic2SpecialStageSnapshot.PlayerTopologySnapshot.capture(players, sonicPlayer, tailsPlayer),
            playerSnapshots,
            intro != null ? intro.captureRewindSnapshot() : null,
            objectManager != null ? objectManager.captureRewindSnapshot() : null,
            checkpoint != null ? checkpoint.captureRewindSnapshot() : null,
            alignmentCheckpoint != null ? alignmentCheckpoint.captureRewindSnapshot() : null);
}
```

- [ ] **Step 5: Implement full manager restore and palette recache**

Edit `Sonic2SpecialStageManager.java`.

Replace `restoreRewindSnapshot(...)` with assignments for every field. The restore must call nested restores when live components exist:

```java
void restoreRewindSnapshot(Sonic2SpecialStageSnapshot snapshot) {
    initialized = snapshot.initialized;
    currentStage = snapshot.currentStage;
    resultState = snapshot.resultState;
    emeraldCollected = snapshot.emeraldCollected;
    frameCounter = snapshot.frameCounter;
    heldButtons = snapshot.heldButtons;
    pressedButtons = snapshot.pressedButtons;
    p2HeldButtons = snapshot.p2HeldButtons;
    p2LogicalButtons = snapshot.p2LogicalButtons;
    tailsControlCounter = snapshot.tailsControlCounter;
    System.arraycopy(snapshot.tailsCtrlRecordBuf, 0, tailsCtrlRecordBuf, 0,
            Math.min(tailsCtrlRecordBuf.length, snapshot.tailsCtrlRecordBuf.length));
    lastDrawingIndex = snapshot.lastDrawingIndex;
    checkpointRainbowPaletteActive = snapshot.checkpointRainbowPaletteActive;
    rainbowPaletteCycleIndex = snapshot.rainbowPaletteCycleIndex;
    pendingCheckpoint = snapshot.pendingCheckpoint;
    pendingCheckpointNumber = snapshot.pendingCheckpointNumber;
    pendingRingRequirement = snapshot.pendingRingRequirement;
    pendingRingsCollected = snapshot.pendingRingsCollected;
    pendingFinalCheckpoint = snapshot.pendingFinalCheckpoint;
    currentRingRequirement = snapshot.currentRingRequirement;
    spriteDebugMode = snapshot.spriteDebugMode;
    planeDebugMode = (PlaneDebugMode) snapshot.planeDebugMode;
    alignmentTestMode = snapshot.alignmentTestMode;
    alignmentTestSavedRainbowPalette = snapshot.alignmentTestSavedRainbowPalette;
    alignmentPendingCheckpoint = snapshot.alignmentPendingCheckpoint;
    alignmentFrameIndex = snapshot.alignmentFrameIndex;
    alignmentFrameTimer = snapshot.alignmentFrameTimer;
    alignmentTrackFrameIndex = snapshot.alignmentTrackFrameIndex;
    alignmentLastDecodedFrameIndex = snapshot.alignmentLastDecodedFrameIndex;
    alignmentDecodedTrackFrame = Sonic2SpecialStageSnapshot.cloneIntArray(snapshot.alignmentDecodedTrackFrame);
    alignmentDrawingIndex = snapshot.alignmentDrawingIndex;
    alignmentTriggerOffsetFrames = snapshot.alignmentTriggerOffsetFrames;
    alignmentRainbowSpeedScale = snapshot.alignmentRainbowSpeedScale;
    alignmentRainbowSpeedAccumulator = snapshot.alignmentRainbowSpeedAccumulator;
    alignmentStepByTrackFrame = snapshot.alignmentStepByTrackFrame;
    lagCompensation = snapshot.lagCompensation;
    lagAccumulator = snapshot.lagAccumulator;
    lagCompensationDisplayEnabled = snapshot.lagCompensationDisplayEnabled;
    diagnosticWallStartTime = snapshot.diagnosticWallStartTime;
    diagnosticUpdateCount = snapshot.diagnosticUpdateCount;
    diagnosticTrackAdvances = snapshot.diagnosticTrackAdvances;
    lastFrameTime = snapshot.lastFrameTime;
    frameSampleCount = snapshot.frameSampleCount;
    frameSampleSum = snapshot.frameSampleSum;
    skydomeScrollX = snapshot.skydomeScrollX;
    alternateScrollBuffer = snapshot.alternateScrollBuffer;
    lastAlternateScrollBuffer = snapshot.lastAlternateScrollBuffer;
    drawingIndex = snapshot.drawingIndex;
    lastAnimFrame = snapshot.lastAnimFrame;
    vScrollBG = snapshot.vScrollBG;
    hScrollDebugTotal = snapshot.hScrollDebugTotal;
    hScrollDebugFrames = snapshot.hScrollDebugFrames;
    lastDebugSegmentIndex = snapshot.lastDebugSegmentIndex;
    decodedTrackFrame = Sonic2SpecialStageSnapshot.cloneIntArray(snapshot.decodedTrackFrame);
    lastDecodedFrameIndex = snapshot.lastDecodedFrameIndex;
    lastDecodedFlipped = snapshot.lastDecodedFlipped;
    palettes = Sonic2SpecialStageSnapshot.clonePalettes(snapshot.palettes);
    recacheRestoredPalettes();
    if (trackAnimator != null && snapshot.trackAnimator != null) {
        trackAnimator.restoreRewindSnapshot(snapshot.trackAnimator);
    }
    restorePlayerTopologyForRewind(snapshot.playerTopology, snapshot.players);
    if (intro != null && snapshot.intro != null) {
        intro.restoreRewindSnapshot(snapshot.intro);
    }
    if (objectManager != null && snapshot.objectManager != null) {
        objectManager.restoreRewindSnapshot(snapshot.objectManager, this);
    }
    if (checkpoint != null && snapshot.checkpoint != null) {
        checkpoint.restoreRewindSnapshot(snapshot.checkpoint);
    }
    if (snapshot.alignmentCheckpoint != null) {
        if (alignmentCheckpoint == null) {
            alignmentCheckpoint = new Sonic2SpecialStageCheckpoint();
        }
        alignmentCheckpoint.restoreRewindSnapshot(snapshot.alignmentCheckpoint);
    } else {
        alignmentCheckpoint = null;
    }
    if (renderer != null) {
        renderer.setPlayers(players);
        renderer.setIntro(intro);
        renderer.setCheckpoint(alignmentTestMode && alignmentCheckpoint != null
                ? alignmentCheckpoint
                : checkpoint);
    }
}
```

Add:

```java
private void recacheRestoredPalettes() {
    GraphicsManager graphics = graphicsManagerOrNull();
    if (graphics == null || palettes == null) {
        return;
    }
    for (int i = 0; i < palettes.length; i++) {
        if (palettes[i] != null) {
            graphics.cachePaletteTexture(palettes[i], i);
        }
    }
}
```

- [ ] **Step 6: Run manager snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindSnapshot" test
```

Expected: pass.

- [ ] **Step 7: Run all Sonic 2 special-stage snapshot tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.TestSonic2SpecialStage*Snapshot,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageTrackAnimatorSnapshot" test
```

Expected: pass.

- [ ] **Step 8: Write the failing final provider capability test**

Edit `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`.

Replace the Sonic 1-only rollout test with:

```java
@Test
void sonic1AndSonic2ProvidersSupportRewindButS3kDoesNotYet() {
    assertTrue(new Sonic1SpecialStageProvider().supportsRewind());
    assertTrue(new Sonic2SpecialStageProvider().supportsRewind());
    assertTrue(new Sonic1SpecialStageProvider().rewindAdapter().isPresent());
    assertTrue(new Sonic2SpecialStageProvider().rewindAdapter().isPresent());
    assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY,
            new Sonic2SpecialStageProvider().rewindAdapter().orElseThrow().key());
    assertFalse(new Sonic3kSpecialStageProvider().supportsRewind());
    assertTrue(new Sonic3kSpecialStageProvider().rewindAdapter().isEmpty());
}
```

- [ ] **Step 9: Write the failing final GameplayModeContext Sonic 2 registration test**

Edit `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`.

Add import:

```java
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
```

Add this test:

```java
@Test
void registersSonic2ProviderOwnedSpecialStageRuntimeUnderGenericKey() {
    GameplayModeContext context = buildAttachedContext();
    Sonic2SpecialStageProvider provider = new Sonic2SpecialStageProvider();

    context.registerSpecialStageAdapter(provider);

    CompositeSnapshot snapshot = context.getRewindRegistry().capture();
    assertTrue(snapshot.containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY),
            "Sonic 2 should register its provider-owned special-stage rewind runtime");

    context.deregisterSpecialStageAdapter();
    assertFalse(context.getRewindRegistry().capture()
            .containsKey(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY));
}
```

Add this assertion to `sonic1AdapterUsesGenericKeyAndKeepsThrowingMissingSnapshotDefault()`:

```java
RewindSnapshottable<?> sonic2Adapter = new Sonic2SpecialStageProvider()
        .rewindAdapter()
        .orElseThrow();
assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY, sonic2Adapter.key());
assertThrows(IllegalStateException.class, sonic2Adapter::resetForMissingSnapshot);
```

- [ ] **Step 10: Run the failing final provider tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
```

Expected: fail because Sonic 2 still reports `supportsRewind() == false`.

- [ ] **Step 11: Enable Sonic 2 provider rewind**

Edit `src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java`.

Add imports:

```java
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageRewindAdapter;
import java.util.Optional;
```

Add methods after `hasSpecialStages()`:

```java
@Override
public boolean supportsRewind() {
    return true;
}

@Override
public Optional<RewindSnapshottable<?>> rewindAdapter() {
    return Optional.of(new Sonic2SpecialStageRewindAdapter(manager));
}
```

This provider flip happens only after the full snapshot graph and focused snapshot tests pass.

- [ ] **Step 12: Run final provider tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter" test
```

Expected: pass.

- [ ] **Step 13: Commit Task 6**

Run:

```powershell
git add src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java `
        src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java `
        src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java `
        CHANGELOG.md
git commit -m "feat: snapshot Sonic 2 special stage manager" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use `Changelog: updated`.

If you split the provider enablement into a separate commit, use:

```powershell
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java `
        src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java `
        src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java `
        CHANGELOG.md
git commit -m "feat: snapshot Sonic 2 special stage manager" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Then commit provider/test enablement with:

```powershell
git add src/main/java/com/openggf/game/sonic2/Sonic2SpecialStageProvider.java `
        src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java `
        src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java `
        CHANGELOG.md
git commit -m "feat: enable Sonic 2 special stage rewind" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Task 7: Regression Verification And Guardrails

**Files:**

- Modify only if tests expose a real bug:
  - `src/main/java/com/openggf/game/sonic2/specialstage/*`
  - `src/test/java/com/openggf/game/sonic2/specialstage/*`
  - `src/test/java/com/openggf/game/TestSpecialStageRewindCapability.java`
  - `src/test/java/com/openggf/game/session/TestGameplayModeContextSpecialStageRewindAdapter.java`

- [ ] **Step 1: Run focused special-stage rewind tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter,com.openggf.game.sonic1.specialstage.TestSonic1SpecialStageRewindSnapshot,TestGameLoopSpecialStageRewindBoundary,TestGameLoopSpecialStageRewindDebugBoundary,TestLiveRewindManagerSpecialStageMode" test
```

Expected: pass. If a test fails because Sonic 2 adapter capture now requires initialized nested state during registration, fix `Sonic2SpecialStageManager.captureRewindSnapshot()` to tolerate uninitialized state by storing null nested snapshots when components are null. Do not special-case `GameplayModeContext`.

- [ ] **Step 2: Run focused Sonic 2 special-stage tests**

Run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.*" test
```

Expected: pass. If shell wildcard resolution is not accepted by Maven on this machine, run:

```powershell
mvn "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageRendererDeterminismTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageDataLoaderTest,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageBackgroundShaderTest,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStagePlayerSnapshot,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageObjectSnapshot,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageCheckpointSnapshot,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageRewindSnapshot,com.openggf.game.sonic2.specialstage.TestSonic2SpecialStageTrackAnimatorSnapshot" test
```

- [ ] **Step 3: Run architectural guards likely to catch direct singleton or palette issues**

Run:

```powershell
mvn "-Dtest=com.openggf.tests.TestArchitecturalSourceGuard,com.openggf.tests.TestArchUnitRules" test
```

Expected: pass. If direct `cachePaletteTexture` calls in `Sonic2SpecialStageManager` are already allowed in this file, keep the restore call localized in `recacheRestoredPalettes()`. If a guard blocks it, route through the existing same-file palette cache pattern instead of broadening the guard.

- [ ] **Step 4: Run package-level compile/test smoke**

Run:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter,com.openggf.game.sonic2.specialstage.*" test
```

Expected: pass.

- [ ] **Step 5: Commit verification-only fixes if needed**

If any verification step required code or test fixes, commit:

```powershell
git add src/main/java/com/openggf/game/sonic2 src/test/java/com/openggf CHANGELOG.md
git commit -m "fix: stabilize Sonic 2 special stage rewind tests" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

Use `Changelog: updated` if `src/main` changed. Skip this commit if no files changed.

## Task 8: Manual Acceptance Gate

**Files:**

- No code files expected.
- Update docs only if manual testing finds a known discrepancy that should be documented.

- [ ] **Step 1: Confirm `s2.gen` exists**

Run:

```powershell
Test-Path s2.gen
```

Expected: `True`. If false, skip manual ROM acceptance and document that it was not run.

- [ ] **Step 2: Build runnable jar**

Run:

```powershell
mvn package
```

Expected: build succeeds.

- [ ] **Step 3: Run the engine**

Run:

```powershell
java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar
```

Expected: engine boots. Enter Sonic 2 and reach a special stage.

- [ ] **Step 4: Manual rewind checks**

In a Sonic 2 special stage:

- Hold live rewind during the intro.
- Hold live rewind during normal running.
- Collect rings, then rewind across the collection.
- Hit a bomb, then rewind across hurt and post-hit invulnerability.
- Cross a checkpoint, then rewind across message/rainbow palette state.
- Reach emerald approach, then rewind across emerald palette/object state.
- Use F1 and alignment/debug controls; confirm each severs the current rewind session and starts a clean one on the next frame. F6/F7 do not mutate the deterministic lag model.
- Leave the stage to results; confirm special-stage rewind disables.

Expected: positions, track frame, objects, rings, checkpoint UI, palette colors, invulnerability flash, and emerald state restore without doubled SFX or music replay.

- [ ] **Step 5: Record acceptance result**

If manual acceptance passes and no files changed, do not commit.

If only `CHANGELOG.md` changes, commit:

```powershell
git add CHANGELOG.md
git commit -m "docs: record Sonic 2 special stage rewind acceptance" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

If `docs/status/known-discrepancies.md` also changes, commit:

```powershell
git add CHANGELOG.md docs/status/known-discrepancies.md
git commit -m "docs: record Sonic 2 special stage rewind acceptance" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: updated
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

If `docs/S3K_KNOWN_DISCREPANCIES.md` also changes, commit:

```powershell
git add CHANGELOG.md docs/S3K_KNOWN_DISCREPANCIES.md
git commit -m "docs: record Sonic 2 special stage rewind acceptance" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: updated
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

If both discrepancy docs change, commit:

```powershell
git add CHANGELOG.md docs/status/known-discrepancies.md docs/S3K_KNOWN_DISCREPANCIES.md
git commit -m "docs: record Sonic 2 special stage rewind acceptance" -m "Changelog: updated
Guide: n/a
Known-Discrepancies: updated
S3K-Known-Discrepancies: updated
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a"
```

## Final Verification

Run before handing off as complete:

```powershell
mvn "-Dtest=com.openggf.game.TestSpecialStageRewindCapability,com.openggf.game.session.TestGameplayModeContextSpecialStageRewindAdapter,com.openggf.game.sonic1.specialstage.TestSonic1SpecialStageRewindSnapshot,TestGameLoopSpecialStageRewindBoundary,TestGameLoopSpecialStageRewindDebugBoundary,TestLiveRewindManagerSpecialStageMode,com.openggf.game.sonic2.specialstage.*" test
```

If time allows, run:

```powershell
mvn test
```

Expected: all selected tests pass. If `mvn test` is not run, state that explicitly in the final handoff.

## Completion Criteria

- `Sonic2SpecialStageProvider.supportsRewind()` returns true.
- Sonic 2 provider returns a `RewindSnapshottable` under `SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY`.
- S3K provider still reports non-rewindable.
- Sonic 2 manager, track animator, player topology/player state, intro, object manager/objects, checkpoint, alignment checkpoint, palette state, lag state, and decoded track caches round-trip through snapshot restore.
- S2 invulnerability is owned by the player countdown and no longer depends on `TimerManager` during special-stage gameplay.
- Focused tests pass.
- Manual ROM acceptance is either passed or explicitly not run because `s2.gen` is unavailable.
