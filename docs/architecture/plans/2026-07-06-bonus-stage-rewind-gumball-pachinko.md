# Bonus Stage Rewind (Gumball + Pachinko) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enable held-key live rewind *within* the S3K Gumball and Pachinko (GLOWING_SPHERE) bonus stages, while leaving the Slot Machine bonus stage's current no-rewind behavior untouched.

**Architecture:** The live rewind stack already lives on the persistent `GameplayModeContext` and is exercised only in `GameMode.LEVEL`. Gumball/Pachinko run the same `LevelFrameStep.execute` pipeline that the rewind re-simulation stepper (`LiveRewindStepper`) replays, and their per-frame object/ring/level-event state is already captured because `LevelManager.loadZoneAndAct` re-registers all level adapters on the bonus-zone load. We therefore: (1) add a `BonusStageProvider.supportsRewind()` capability that is true only for Gumball/Pachinko; (2) widen the rewind mode gates from "LEVEL only" to "LEVEL or a rewind-supported BONUS_STAGE"; (3) drive the capture/engagement hooks from `updateBonusStageMode`; and (4) snapshot the one piece of per-frame state not already covered — the bonus coordinator's reward accumulators (`ringsCollected`/`livesAwarded`/`awardedShield`).

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Maven. Existing packages: `com.openggf.game`, `com.openggf.game.rewind`, `com.openggf.game.session`, `com.openggf.game.sonic3k`, plus `com.openggf` (`GameLoop`).

## Global Constraints

- Java 21; tests use JUnit 5 / Jupiter only — no `org.junit.*` (JUnit 4) imports, rules, or runners.
- Source files end with a trailing newline.
- Accuracy is paramount: every behavioral gate must model real ROM/engine state, never a zone/route/frame carve-out. `supportsRewind()` is a per-provider *capability* predicate (semantic state), which is the allowed pattern — not a `zone == X` branch in shared runtime code.
- Do NOT weaken the existing invariant enforced by `TestGameLoopSpecialStageRewindGate`: held rewind must cleanly disengage the moment `bonusStageTransitionPending` (or `specialStageTransitionPending`) becomes true. All new gates must still honor the `rewindBlocked` / transition-pending short-circuit.
- Level-mutation routing, singleton-reset, and `@RewindTransient` conventions from `CLAUDE.md` continue to apply.
- Commit-message trailers are required on every non-`master` non-merge commit (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`). Engine `feat`/`fix` commits touching `src/main/` must set `Changelog: updated` (stage `CHANGELOG.md`) or justify `n/a: <reason>`.
- Branch off `develop` (not `master`). Suggested branch: `feature/ai-bonus-stage-rewind`.

## Scope

**In scope (this plan):** Gumball and Pachinko (GLOWING_SPHERE) bonus stages — held-key rewind *within* the stage timeline only.

**Explicitly out of scope (separate follow-up plan):** The Slot Machine bonus stage. Its `S3kSlotBonusStageRuntime` holds live cross-references (a swapped-in player sprite + custom `slotPlayerRuntime`, `ObjectManager` reward objects tracked in parallel `List`s, mutable render buffers, and a ~35-field `S3kSlotStageState` with Deques) that require a dedicated snapshot/restore + reward-list reconciliation design. That is tracked as a follow-up (see "Slots Follow-Up" at the end) and requires an investigation spike before it can be planned to this plan's standard.

**Not attempted:** Cross-mode rewind (rewinding *out of* a bonus stage back into the parent level). The `LEVEL_LOAD` boundary fired by `loadZoneAndAct` on both bonus entry and exit already severs the timeline at the mode boundary; within-stage rewind is deliberately self-contained.

---

## Key Facts Verified During Planning (read before implementing)

- `LiveRewindManager` gates rewind on `mode != GameMode.LEVEL` in four places: `handleRealtimeRewindInput` (`LiveRewindManager.java:60`), `recordExternalFrame` (`:163`), `resetBufferAtCurrentFrame` (`:195`), `renderHud` (`:202`).
- `GameLoop.stepInternal()` engages realtime rewind only when `currentGameMode == GameMode.LEVEL` (`GameLoop.java:767`, `:774`), and calls `liveRewindManager.recordExternalFrame(...)` only inside the LEVEL branch (`GameLoop.java:1334`). `updateBonusStageMode` (`GameLoop.java:1394`) runs `LevelFrameStep.execute` but calls no rewind hook.
- The rewind re-simulation stepper `LiveRewindStepper` replays `LevelFrameStep.execute(LevelFrameContext.from(gameplayMode))`. `LevelFrameStep` invokes `bonusStageProvider.onFrameUpdate()` only when `updateDuringLevelFrame()` is true (`LevelFrameStep.java:249-251`). For Gumball/Pachinko the S3K coordinator's `updateDuringLevelFrame()` returns false and `onFrameUpdate()` is the no-op default, so `LevelFrameStep.execute` alone faithfully reproduces the frame — the existing stepper needs no changes.
- `LevelManager` marks `RewindBoundary.LEVEL_LOAD` on level load (`LevelManager.java:358`) and re-registers level/object/ring/level-event adapters (`registerLevelAdapters` at `GameplayModeContext.java:421`, called from `LevelManager.java:610`; `registerRingAdapter` from `:637`). Both fire on the bonus-zone load in `doEnterBonusStage` and again on the saved-zone reload in `doExitBonusStage`, so the bonus zone's objects are snapshot-covered and the parent-level timeline is cleared.
- The bonus reward accumulators live on `AbstractBonusStageCoordinator`: `ringsCollected`, `livesAwarded`, `awardedShield` (`AbstractBonusStageCoordinator.java:18-20`), mutated by `addRings`/`addLife`/`setAwardedShield` (`:83-99`) from item objects. Nothing captures them today.
- `GameLoop` holds `activeBonusStageProvider` (set in `doEnterBonusStage` at `GameLoop.java:2014`, cleared in `doExitBonusStage` at `:2187`), and `bonusStageTransitionPending`.

---

## File Structure

- **Modify** `src/main/java/com/openggf/game/BonusStageProvider.java` — add `supportsRewind()` default method.
- **Modify** `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java` — override `supportsRewind()`; add package-visible accumulator snapshot/restore accessors.
- **Create** `src/main/java/com/openggf/game/rewind/BonusStageCoordinatorRewindAdapter.java` — `RewindSnapshottable` wrapper over the coordinator accumulators.
- **Modify** `src/main/java/com/openggf/game/rewind/LiveRewindManager.java` — replace the four `mode != GameMode.LEVEL` checks with an `isRewindableMode(mode)` helper.
- **Modify** `src/main/java/com/openggf/game/session/GameplayModeContext.java` — add `registerBonusStageAdapter(BonusStageProvider)` / `deregisterBonusStageAdapter()`.
- **Modify** `src/main/java/com/openggf/GameLoop.java` — compute bonus-rewindable state, widen the two engagement guards, add the capture call in `updateBonusStageMode`, and register/deregister the coordinator adapter in `doEnterBonusStage`/`doExitBonusStage`.
- **Create tests:**
  - `src/test/java/com/openggf/game/TestBonusStageRewindCapability.java`
  - `src/test/java/com/openggf/game/rewind/TestBonusStageCoordinatorRewindAdapter.java`
  - `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerBonusStageMode.java`
- **Docs:** `CHANGELOG.md`, `docs/status/known-discrepancies.md`, `AGENTS_S3K.md` (S3K-Known-Discrepancies), plus commit trailers.

---

### Task 1: `BonusStageProvider.supportsRewind()` capability

**Files:**
- Modify: `src/main/java/com/openggf/game/BonusStageProvider.java`
- Modify: `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`
- Test: `src/test/java/com/openggf/game/TestBonusStageRewindCapability.java`

**Interfaces:**
- Produces: `boolean BonusStageProvider.supportsRewind()` — default `false`; `AbstractBonusStageCoordinator` returns `true` iff the active type is `GUMBALL` or `GLOWING_SPHERE`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/TestBonusStageRewindCapability.java`:

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bonus-stage rewind capability is a per-provider semantic predicate:
 * Gumball and Pachinko (GLOWING_SPHERE) run the plain LevelFrameStep pipeline
 * that the rewind re-simulation stepper can replay, so they are rewindable;
 * the Slot Machine (dedicated uncaptured runtime) and NONE are not.
 */
class TestBonusStageRewindCapability {

    private static final class FakeCoordinator extends AbstractBonusStageCoordinator {
        @Override public BonusStageType selectBonusStage(int ringCount) { return BonusStageType.NONE; }
        @Override public int getZoneId(BonusStageType type) { return 0; }
        @Override public int getMusicId(BonusStageType type) { return -1; }
    }

    @Test
    void noOpProviderDoesNotSupportRewind() {
        assertFalse(NoOpBonusStageProvider.INSTANCE.supportsRewind());
    }

    @Test
    void gumballAndPachinkoSupportRewindSlotsAndNoneDoNot() {
        FakeCoordinator coordinator = new FakeCoordinator();

        coordinator.onEnter(BonusStageType.GUMBALL, null);
        assertTrue(coordinator.supportsRewind(), "Gumball should be rewindable");

        coordinator.onEnter(BonusStageType.GLOWING_SPHERE, null);
        assertTrue(coordinator.supportsRewind(), "Pachinko (GLOWING_SPHERE) should be rewindable");

        coordinator.onEnter(BonusStageType.SLOT_MACHINE, null);
        assertFalse(coordinator.supportsRewind(), "Slots is out of scope until its runtime is snapshotted");

        coordinator.onExit();
        assertFalse(coordinator.supportsRewind(), "NONE (inactive) is not rewindable");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run (PowerShell): `mvn "-Dtest=com.openggf.game.TestBonusStageRewindCapability" test`
Expected: FAIL — compile error / `supportsRewind()` not defined.

- [ ] **Step 3: Add the default method to the interface**

In `src/main/java/com/openggf/game/BonusStageProvider.java`, add (place it near the other lifecycle default methods, e.g. just after `hasBonusStages()`):

```java
    /**
     * Whether held-key live rewind is supported while this bonus stage is
     * active. True only for stages whose per-frame simulation is fully
     * captured by the standard rewind adapters and faithfully reproduced by
     * the LevelFrameStep re-simulation stepper (Gumball / Pachinko). Stages
     * with a dedicated, not-yet-snapshotted runtime (Slot Machine) return
     * false so rewind stays disengaged for them.
     */
    default boolean supportsRewind() {
        return false;
    }
```

- [ ] **Step 4: Override in the coordinator**

In `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`, add (after `hasBonusStages()`):

```java
    @Override
    public boolean supportsRewind() {
        return activeType == BonusStageType.GUMBALL
                || activeType == BonusStageType.GLOWING_SPHERE;
    }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.TestBonusStageRewindCapability" test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/BonusStageProvider.java \
        src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java \
        src/test/java/com/openggf/game/TestBonusStageRewindCapability.java
git commit  # fill trailers: Changelog updated later; Skills n/a; etc.
```

---

### Task 2: Coordinator accumulator snapshot/restore accessors

**Files:**
- Modify: `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`
- Test: extend `src/test/java/com/openggf/game/rewind/TestBonusStageCoordinatorRewindAdapter.java` is created in Task 3; here we only add and unit-check the accessors via a small test in the same Task-1 test file is NOT appropriate — instead cover the accessors through Task 3's adapter round-trip. This task has no standalone test; its deliverable is verified by Task 3.

**Interfaces:**
- Produces on `AbstractBonusStageCoordinator`:
  - `public BonusStageAccumulatorSnapshot captureAccumulators()`
  - `public void restoreAccumulators(BonusStageAccumulatorSnapshot snapshot)`
  - Nested record `public record BonusStageAccumulatorSnapshot(int rings, int lives, ShieldType shield) {}`

- [ ] **Step 1: Add the snapshot record and accessors**

In `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`, add the record and methods (place after `setAwardedShield`):

```java
    /**
     * Immutable capture of the reward accumulators that objects mutate across
     * frames during a bonus stage. Held rewind restores these so a backward
     * seek that un-collects a gumball item rolls the pending reward totals back
     * in lockstep with the item objects' own restored state.
     */
    public record BonusStageAccumulatorSnapshot(int rings, int lives, ShieldType shield) {}

    /** Snapshots the live reward accumulators for rewind capture. */
    public BonusStageAccumulatorSnapshot captureAccumulators() {
        return new BonusStageAccumulatorSnapshot(ringsCollected, livesAwarded, awardedShield);
    }

    /** Restores reward accumulators from a rewind snapshot. */
    public void restoreAccumulators(BonusStageAccumulatorSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        this.ringsCollected = snapshot.rings();
        this.livesAwarded = snapshot.lives();
        this.awardedShield = snapshot.shield();
    }
```

- [ ] **Step 2: Compile check**

Run: `mvn "-Dtest=com.openggf.game.TestBonusStageRewindCapability" test`
Expected: PASS (still compiles; no behavior change yet).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java
git commit  # trailers filled
```

---

### Task 3: `BonusStageCoordinatorRewindAdapter`

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/BonusStageCoordinatorRewindAdapter.java`
- Test: `src/test/java/com/openggf/game/rewind/TestBonusStageCoordinatorRewindAdapter.java`

**Interfaces:**
- Consumes: `AbstractBonusStageCoordinator.captureAccumulators()` / `restoreAccumulators(...)` / `BonusStageAccumulatorSnapshot` (Task 2).
- Consumes: `RewindSnapshottable<T>` (existing interface at `com.openggf.game.rewind.RewindSnapshottable`, with `String key()`, `T snapshot()`, `void restore(T)`).
- Produces: `BonusStageCoordinatorRewindAdapter implements RewindSnapshottable<AbstractBonusStageCoordinator.BonusStageAccumulatorSnapshot>` with `key()` returning the constant `"bonus-stage-coordinator"`, exposed as `public static final String KEY`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/rewind/TestBonusStageCoordinatorRewindAdapter.java`:

```java
package com.openggf.game.rewind;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageType;
import com.openggf.game.ShieldType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestBonusStageCoordinatorRewindAdapter {

    private static final class FakeCoordinator extends AbstractBonusStageCoordinator {
        @Override public BonusStageType selectBonusStage(int ringCount) { return BonusStageType.NONE; }
        @Override public int getZoneId(BonusStageType type) { return 0; }
        @Override public int getMusicId(BonusStageType type) { return -1; }
    }

    @Test
    void keyIsStable() {
        FakeCoordinator coordinator = new FakeCoordinator();
        BonusStageCoordinatorRewindAdapter adapter = new BonusStageCoordinatorRewindAdapter(coordinator);
        assertEquals("bonus-stage-coordinator", adapter.key());
        assertEquals(BonusStageCoordinatorRewindAdapter.KEY, adapter.key());
    }

    @Test
    void roundTripsRewardAccumulators() {
        FakeCoordinator coordinator = new FakeCoordinator();
        coordinator.onEnter(BonusStageType.GUMBALL, null);
        BonusStageCoordinatorRewindAdapter adapter = new BonusStageCoordinatorRewindAdapter(coordinator);

        // Capture the empty starting state.
        var floor = adapter.snapshot();

        // Mutate as item objects would.
        coordinator.addRings(7);
        coordinator.addLife();
        coordinator.setAwardedShield(ShieldType.FIRE);
        assertEquals(7, coordinator.getRewards().rings());

        // Restore rolls the accumulators back to the captured floor.
        adapter.restore(floor);
        assertEquals(0, coordinator.getRewards().rings());
        assertEquals(0, coordinator.getRewards().lives());
        assertNull(coordinator.captureAccumulators().shield());
    }
}
```

Note: `BonusStageRewards` exposes `rings()`/`lives()` (see `AbstractBonusStageCoordinator.getRewards()`); shield state is verified via `captureAccumulators().shield()`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.rewind.TestBonusStageCoordinatorRewindAdapter" test`
Expected: FAIL — `BonusStageCoordinatorRewindAdapter` does not exist.

- [ ] **Step 3: Create the adapter**

Create `src/main/java/com/openggf/game/rewind/BonusStageCoordinatorRewindAdapter.java`:

```java
package com.openggf.game.rewind;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.AbstractBonusStageCoordinator.BonusStageAccumulatorSnapshot;

import java.util.Objects;

/**
 * Rewind adapter for a bonus stage coordinator's reward accumulators
 * (rings/lives/shield). These are mutated by item objects across frames but
 * are not part of any object's own captured state, so without this adapter a
 * backward seek would leave the pending reward totals stale relative to the
 * rolled-back item objects. Registered by {@code GameplayModeContext} only
 * while a rewind-supported bonus stage is active.
 */
public final class BonusStageCoordinatorRewindAdapter
        implements RewindSnapshottable<BonusStageAccumulatorSnapshot> {

    public static final String KEY = "bonus-stage-coordinator";

    private final AbstractBonusStageCoordinator coordinator;

    public BonusStageCoordinatorRewindAdapter(AbstractBonusStageCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public BonusStageAccumulatorSnapshot snapshot() {
        return coordinator.captureAccumulators();
    }

    @Override
    public void restore(BonusStageAccumulatorSnapshot state) {
        coordinator.restoreAccumulators(state);
    }
}
```

**Before implementing**, confirm the exact `RewindSnapshottable` method names/signatures by reading `src/main/java/com/openggf/game/rewind/RewindSnapshottable.java` (the plan assumes `String key()`, `T snapshot()`, `void restore(T)` per `RewindSnapshottable.java:15-28`). If the interface uses different names, match them.

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.rewind.TestBonusStageCoordinatorRewindAdapter" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/BonusStageCoordinatorRewindAdapter.java \
        src/test/java/com/openggf/game/rewind/TestBonusStageCoordinatorRewindAdapter.java
git commit  # trailers filled
```

---

### Task 4: Widen `LiveRewindManager` mode gates to rewindable modes

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Test: `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerBonusStageMode.java`

**Interfaces:**
- Produces: `private static boolean isRewindableMode(GameMode mode)` returning `mode == GameMode.LEVEL || mode == GameMode.BONUS_STAGE`. Used to replace the four `mode != GameMode.LEVEL` checks. Behavioral change: `recordExternalFrame` / `handleRealtimeRewindInput` no longer force-`clear()` when the mode is `BONUS_STAGE` (they proceed, still subject to the `nonRewindableTransitionPending` / `rewindBlocked` short-circuit that follows).

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/game/rewind/TestLiveRewindManagerBonusStageMode.java`. This verifies the mode gate treats `BONUS_STAGE` as rewindable (i.e. does not early-return purely on mode), while still rejecting a truly non-rewindable mode:

```java
package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewind mode gate must treat BONUS_STAGE as a rewindable mode (alongside
 * LEVEL) so Gumball/Pachinko can record and engage held rewind, while genuinely
 * non-rewindable modes (e.g. TITLE_SCREEN) stay excluded.
 */
class TestLiveRewindManagerBonusStageMode {

    private SonicConfigurationService config;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetConfigSingleton();
    }

    private static boolean isRewindableMode(GameMode mode) throws Exception {
        Method m = LiveRewindManager.class.getDeclaredMethod("isRewindableMode", GameMode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, mode);
    }

    @Test
    void levelAndBonusStageAreRewindableModes() throws Exception {
        assertTrue(isRewindableMode(GameMode.LEVEL));
        assertTrue(isRewindableMode(GameMode.BONUS_STAGE));
    }

    @Test
    void nonGameplayModesAreNotRewindable() throws Exception {
        assertFalse(isRewindableMode(GameMode.TITLE_SCREEN));
        assertFalse(isRewindableMode(GameMode.SPECIAL_STAGE));
        assertFalse(isRewindableMode(GameMode.SPECIAL_STAGE_RESULTS));
    }
}
```

If `TestEnvironment.resetConfigSingleton()` does not exist, drop the `@AfterEach` body (the test only reads the static helper and does not mutate config). Confirm against `src/test/java/com/openggf/tests/TestEnvironment.java`.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindManagerBonusStageMode" test`
Expected: FAIL — `isRewindableMode` method not found.

- [ ] **Step 3: Add the helper and replace the four gates**

In `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`:

Add the helper (place it near the other small private helpers, e.g. just above `enabled()`):

```java
    /**
     * Modes in which held live rewind may run. LEVEL is normal gameplay;
     * BONUS_STAGE covers the rewind-supported bonus stages (Gumball/Pachinko),
     * which run the same LevelFrameStep pipeline the re-simulation stepper
     * replays. The GameLoop only drives the record/engage hooks for a
     * BONUS_STAGE whose provider reports supportsRewind(), so this predicate can
     * accept BONUS_STAGE unconditionally without enabling rewind for the Slot
     * Machine (whose hooks are never called).
     */
    private static boolean isRewindableMode(GameMode mode) {
        return mode == GameMode.LEVEL || mode == GameMode.BONUS_STAGE;
    }
```

Then change each of these four conditions from `mode != GameMode.LEVEL` to `!isRewindableMode(mode)`:
- `handleRealtimeRewindInput` (line ~60): `if (!isRewindableMode(mode) || rewindBlocked || input == null || !enabled()) {`
- `recordExternalFrame` (line ~163): `if (!isRewindableMode(mode) || nonRewindableTransitionPending || input == null || !enabled()) {`
- `resetBufferAtCurrentFrame` (line ~195): `if (!isRewindableMode(mode)) {`
- `renderHud` (line ~202): `if (!isRewindableMode(mode) || text == null || !enabled()) {`

- [ ] **Step 4: Run the new test + the existing gate test to verify both pass**

Run: `mvn "-Dtest=com.openggf.game.rewind.TestLiveRewindManagerBonusStageMode,com.openggf.TestGameLoopSpecialStageRewindGate" test`
Expected: PASS for both. The gate test still passes because its disengagement is driven by `bonusStageTransitionPending` → `rewindBlocked`, not by the mode value (its fixture drives with `GameMode.LEVEL`).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/LiveRewindManager.java \
        src/test/java/com/openggf/game/rewind/TestLiveRewindManagerBonusStageMode.java
git commit  # trailers filled
```

---

### Task 5: `GameplayModeContext` register/deregister for the coordinator adapter

**Files:**
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Test: `src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java` (extend existing)

**Interfaces:**
- Consumes: `BonusStageCoordinatorRewindAdapter` (Task 3), `RewindRegistry.register(...)` / `deregister(String)` (existing, used throughout `registerLevelAdapters`).
- Produces:
  - `public void registerBonusStageAdapter(BonusStageProvider provider)` — registers a `BonusStageCoordinatorRewindAdapter` iff `rewindRegistry != null`, `provider instanceof AbstractBonusStageCoordinator`, and `provider.supportsRewind()`; deregisters any prior one first (idempotent).
  - `public void deregisterBonusStageAdapter()` — `rewindRegistry.deregister(BonusStageCoordinatorRewindAdapter.KEY)` when the registry exists.

- [ ] **Step 1: Write the failing test**

Add to (or create alongside) `src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java`. First read that file to match its existing fixture/setup style; then add:

```java
    @Test
    void registersBonusCoordinatorAdapterOnlyForRewindSupportedStage() {
        GameplayModeContext context = /* obtain the fixture's context — match existing tests */;

        var slots = new com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator();
        slots.onEnter(com.openggf.game.BonusStageType.SLOT_MACHINE, null);
        context.registerBonusStageAdapter(slots);
        assertFalse(context.getRewindRegistry().hasKey(
                com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter.KEY),
                "Slots is not rewind-supported; no adapter should register");

        var gumball = new com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator();
        gumball.onEnter(com.openggf.game.BonusStageType.GUMBALL, null);
        context.registerBonusStageAdapter(gumball);
        assertTrue(context.getRewindRegistry().hasKey(
                com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter.KEY),
                "Gumball is rewind-supported; adapter must register");

        context.deregisterBonusStageAdapter();
        assertFalse(context.getRewindRegistry().hasKey(
                com.openggf.game.rewind.BonusStageCoordinatorRewindAdapter.KEY));
    }
```

`RewindRegistry` may not expose `hasKey(...)` or `GameplayModeContext` may not expose `getRewindRegistry()`. Before writing the test, read `src/main/java/com/openggf/game/rewind/RewindRegistry.java` and `TestGameplayModeContextRewindRegistry.java` to find the existing way tests assert registration (e.g. a package-visible accessor, a `keys()`/`contains(...)` method, or capturing via `capture()` output). Use whatever the existing tests use; if none exists, add a minimal package-visible `boolean isRegistered(String key)` to `RewindRegistry` in this task and cover it.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=com.openggf.game.session.TestGameplayModeContextRewindRegistry" test`
Expected: FAIL — `registerBonusStageAdapter` not defined.

- [ ] **Step 3: Implement register/deregister**

In `src/main/java/com/openggf/game/session/GameplayModeContext.java`, add (near `registerRingAdapter`, ~line 480), importing `AbstractBonusStageCoordinator`, `BonusStageProvider`, and `BonusStageCoordinatorRewindAdapter`:

```java
    /**
     * Registers a rewind adapter capturing the active bonus-stage coordinator's
     * reward accumulators, but only for a rewind-supported stage
     * (Gumball/Pachinko). No-op for the Slot Machine or when rewind is
     * unavailable. Idempotent — deregisters any prior adapter first.
     */
    public void registerBonusStageAdapter(BonusStageProvider provider) {
        if (rewindRegistry == null) {
            return;
        }
        rewindRegistry.deregister(BonusStageCoordinatorRewindAdapter.KEY);
        if (provider instanceof AbstractBonusStageCoordinator coordinator
                && provider.supportsRewind()) {
            rewindRegistry.register(new BonusStageCoordinatorRewindAdapter(coordinator));
        }
    }

    /** Removes the bonus-stage coordinator rewind adapter on stage exit. */
    public void deregisterBonusStageAdapter() {
        if (rewindRegistry != null) {
            rewindRegistry.deregister(BonusStageCoordinatorRewindAdapter.KEY);
        }
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=com.openggf.game.session.TestGameplayModeContextRewindRegistry" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/session/GameplayModeContext.java \
        src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java
# plus RewindRegistry.java if an isRegistered(...) accessor was added
git commit  # trailers filled
```

---

### Task 6: Wire capture + engagement into `GameLoop` for rewind-supported bonus stages

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Test: covered by the existing `src/test/java/com/openggf/TestGameLoopSpecialStageRewindGate.java` (must stay green) plus manual/integration verification (Task 7).

**Interfaces:**
- Consumes: `activeBonusStageProvider` (GameLoop field), `BonusStageProvider.supportsRewind()` (Task 1), `liveRewindManager.recordExternalFrame(...)` / `handleRealtimeRewindInput(...)`, `GameplayModeContext.registerBonusStageAdapter(...)` / `deregisterBonusStageAdapter()` (Task 5).
- Produces: `private boolean isBonusStageRewindable()` returning `currentGameMode == GameMode.BONUS_STAGE && activeBonusStageProvider != null && activeBonusStageProvider.supportsRewind()`.

- [ ] **Step 1: Add the `isBonusStageRewindable()` helper**

In `src/main/java/com/openggf/GameLoop.java`, add near `isRewindBlocked()` (~line 745):

```java
    /**
     * True while the current bonus stage supports held rewind (Gumball /
     * Pachinko). The Slot Machine's provider reports supportsRewind()==false,
     * so its rewind hooks are never driven and it keeps its no-rewind behavior.
     */
    private boolean isBonusStageRewindable() {
        return currentGameMode == GameMode.BONUS_STAGE
                && activeBonusStageProvider != null
                && activeBonusStageProvider.supportsRewind();
    }
```

- [ ] **Step 2: Widen the realtime-rewind engagement guard**

In `stepInternal()` (~line 774), change the live-rewind engagement guard so it also fires for a rewind-supported bonus stage. Replace:

```java
        if (currentGameMode == GameMode.LEVEL
                && TraceSessionLauncher.active() == null
                && liveRewindManager.handleRealtimeRewindInput(
                        currentGameMode, rewindBlocked, inputHandler)) {
            inputHandler.update();
            return;
        }
```

with:

```java
        if ((currentGameMode == GameMode.LEVEL || isBonusStageRewindable())
                && TraceSessionLauncher.active() == null
                && liveRewindManager.handleRealtimeRewindInput(
                        currentGameMode, rewindBlocked, inputHandler)) {
            inputHandler.update();
            return;
        }
```

Leave the trace-session engagement branch above it (`~line 767`) LEVEL-only — trace replay does not drive bonus stages.

- [ ] **Step 3: Add the per-frame capture call in `updateBonusStageMode`**

In `updateBonusStageMode(...)` (~line 1394), inside the `if (!freezeForBonusExit) { ... }` block, add the capture call AFTER `activeBonusStageProvider.onFrameUpdate()` (~line 1427) and BEFORE the `isStageComplete()` completion check (~line 1430). Insert:

```java
            // Record a rewind keyframe/step for rewind-supported bonus stages
            // (Gumball/Pachinko). Placed before the completion check so the
            // exit frame — which sets bonusStageTransitionPending — is not
            // recorded. The LEVEL path is unchanged; the Slot Machine is
            // excluded via supportsRewind().
            if (isBonusStageRewindable() && TraceSessionLauncher.active() == null) {
                liveRewindManager.recordExternalFrame(
                        currentGameMode, bonusStageTransitionPending, inputHandler);
            }
```

- [ ] **Step 4: Register the coordinator adapter on entry**

In `doEnterBonusStage(...)` (~line 2014), right after the block that sets the provider on the gameplay mode (`gameplayMode.setActiveBonusStageProvider(provider);`) and after `provider.onEnter(type, savedState);`, add:

```java
        if (gameplayMode != null) {
            gameplayMode.registerBonusStageAdapter(provider);
        }
```

(The adapter is a no-op internally for non-rewind-supported stages, so this is safe to call unconditionally.)

- [ ] **Step 5: Deregister the coordinator adapter on exit**

In `doExitBonusStage(...)` (~line 2190), inside the block that clears the provider on the gameplay mode (`gameplayMode.setActiveBonusStageProvider(null);`), add alongside it:

```java
        if (gameplayMode != null) {
            gameplayMode.setActiveBonusStageProvider(null);
            gameplayMode.deregisterBonusStageAdapter();
        }
```

(Replace the existing `if (gameplayMode != null) { gameplayMode.setActiveBonusStageProvider(null); }` block; do not add a second null check.)

- [ ] **Step 6: Run the gate + capability + adapter tests**

Run: `mvn "-Dtest=com.openggf.TestGameLoopSpecialStageRewindGate,com.openggf.game.TestBonusStageRewindCapability,com.openggf.game.rewind.TestBonusStageCoordinatorRewindAdapter,com.openggf.game.rewind.TestLiveRewindManagerBonusStageMode,com.openggf.game.session.TestGameplayModeContextRewindRegistry" test`
Expected: PASS for all.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit  # trailers filled
```

---

### Task 7: Full-suite verification + documentation

**Files:**
- Modify: `CHANGELOG.md`, `docs/status/known-discrepancies.md`, `AGENTS_S3K.md`
- Verify: whole suite

- [ ] **Step 1: Run the S3K must-keep-green tests + rewind coverage guards**

Run:
```bash
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestS3kSlotBonusGraphRewind" test
```
Expected: PASS (no regressions; `TestS3kSlotBonusGraphRewind` still exercises slots object-graph recreation during LEVEL play, unaffected by this change).

- [ ] **Step 2: Run the full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS. If any pre-existing failures appear, confirm via `git stash` + `mvn test` on `develop` that they predate this branch; do not fix unrelated pre-existing failures here.

- [ ] **Step 3: Manual verification (documented, not automated)**

With a real `s3k.gen`, `debug.flags` shortcuts enabled, and `LIVE_REWIND_ENABLED=true`: trigger a Gumball or Pachinko bonus stage (star-post bonus or the debug bonus shortcut), collect a few rings/items, then hold the rewind key. Confirm: (a) the stage rewinds smoothly; (b) collected items reappear as you rewind past their collection; (c) on release and stage exit, the ring reward total matches what was actually collected at the release point (no over-count). Record the result in the commit / PR description. If a headless integration harness for bonus stages exists, prefer adding an automated check; otherwise this remains a manual gate (note the coverage limit explicitly — do not claim automated coverage).

- [ ] **Step 4: Update documentation**

- `CHANGELOG.md` (CRLF file — verify `git diff --stat` shows a small diff, not a full-file rewrite; if the Edit tool flipped line endings, re-normalize): add an entry under the current prerelease section, e.g. "Live rewind now works within the Gumball and Pachinko bonus stages (S3K); the Slot Machine bonus stage remains non-rewindable pending a dedicated runtime snapshot."
- `docs/status/known-discrepancies.md`: note the intentional scope — bonus-stage rewind is within-stage only (does not cross the mode boundary back into the level), and the minor cosmetic caveat that the player art-tile high-priority bit is not re-forced during backward re-simulation frames.
- `AGENTS_S3K.md` (S3K-Known-Discrepancies trailer): note that Slot Machine bonus-stage rewind is deferred and why (live cross-references in `S3kSlotBonusStageRuntime`).

- [ ] **Step 5: Commit docs**

```bash
git add CHANGELOG.md docs/status/known-discrepancies.md AGENTS_S3K.md
git commit  # Changelog: updated / Known-Discrepancies: updated / S3K-Known-Discrepancies: updated
```

---

## Self-Review Notes

- **Spec coverage:** Un-gating (Task 4), engagement (Task 6 Step 2), capture (Task 6 Step 3), accumulator correctness (Tasks 2/3/5/6), Slots-exclusion (Task 1 + `supportsRewind` gating throughout), boundary/timeline severing (relies on existing `LEVEL_LOAD` behavior — verified, no new code), gate-invariant preservation (Task 4 Step 4, Task 6 Step 6). Covered.
- **Type consistency:** `BonusStageAccumulatorSnapshot(int rings, int lives, ShieldType shield)` is defined in Task 2 and consumed unchanged in Task 3. `BonusStageCoordinatorRewindAdapter.KEY = "bonus-stage-coordinator"` defined in Task 3, consumed in Task 5/tests. `isRewindableMode` (Task 4) and `isBonusStageRewindable` (Task 6) are distinct and used consistently.
- **Assumptions flagged for the implementer to verify before coding:** the exact `RewindSnapshottable` method names (Task 3 Step 3), the existing `RewindRegistry`/`GameplayModeContext` registration-assertion mechanism (Task 5 Step 1), and `TestEnvironment` helper availability (Task 4 Step 1). Each task names the file to read.

---

## Slots Follow-Up (separate plan — paired with Sonic 1 Special Stage)

**Decision (2026-07-06):** Slot Machine rewind is deferred and will be tackled **together with Sonic 1's Special Stage** in a dedicated effort, because they share the same requirement shape — snapshotting a self-contained minigame runtime that holds its own mutable state and bypasses the standard object/level snapshot model (S1's special stage is a monolithic manager with a mutated layout array, custom player physics, camera, and animation/scroll accumulators; slots has its runtime + `S3kSlotStageState`). A shared "self-contained runtime snapshot" approach should be designed once and applied to both.

Slot Machine rewind requires its own plan after an investigation spike, because `S3kSlotBonusStageRuntime` holds state the standard adapters do not capture:
- `S3kSlotStageState` — ~35 scalar fields, six `int[3]` arrays, two `Deque<int[]>` reward queues.
- Runtime bookkeeping — `continueAwarded`, `exitFadeStarted`, `exitTriggered`, `lastFrameCounter`, and the coordinator's `slotFrameCounter`.
- A swapped-in player sprite (`slotPlayer`) with a custom `slotPlayerRuntime` (fixed-point `slotOriginX/Y`, exit-sequence phase) and suppressed CPU sidekicks.
- Parallel `List`s (`slotRingRewards`/`slotSpikeRewards`) of `ObjectManager` dynamic objects that the object-manager adapter recreates on restore — the runtime's lists must be reconciled to the recreated instances (extend the `S3kSlotRewindSupport` re-resolution pattern).
- Mutable render buffers (`S3kSlotRenderBuffers` animation state); `pointGrid`/`visibleCells` are derived and can be rebuilt.

Because `slots`' `updateDuringLevelFrame()` is true, the re-simulation stepper *does* drive `slotRuntime.update(...)`, so a faithful snapshot/restore of all of the above is mandatory for deterministic re-simulation — this is the moderate-tier work and should be scoped with its own spike + plan.
