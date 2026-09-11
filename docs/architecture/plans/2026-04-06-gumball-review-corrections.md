# Gumball Bonus Stage Review Corrections — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix all critical, major, and high-impact simplification issues identified by the three-agent code review of the `feature/ai-s3k-bonus-stage-framework` branch.

**Architecture:** Corrections are grouped into 6 tasks by file/concern. Each task is independently testable. Tasks 1-3 fix correctness/ROM-parity issues. Tasks 4-5 fix architectural/design issues. Task 6 removes dead code and stale comments. A final validation gate runs the full test suite.

**Tech Stack:** Java 21, Maven, JUnit 5

**Validation strategy:** After each task, run `mvn test -pl . -Dtest="TestBonusStageLifecycle,TestGumballMachineDrift" -q` for fast feedback, plus `mvn test -q` at each gate checkpoint.

---

## Task 1: Fix missing fade-guard in `enterBonusStage` (Critical)

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java:975-1021`
- Modify: `src/main/java/com/openggf/GameLoop.java:1040-1042` (NONE guard)

**Context:** Every other transition entry point (`enterSpecialStage` at line 838, `enterResultsScreenWithDebugRings` at line 792) checks `fadeManager.isActive()` before starting a new fade. `enterBonusStage` does not, so triggering it during an active fade corrupts the state machine. Additionally, `BonusStageType.NONE` can reach `doEnterBonusStage` and decode to zone=0xFF.

- [ ] **Step 1: Add fade-guard and NONE-type guard to `enterBonusStage`**

In `GameLoop.java`, find the `enterBonusStage` method. After the existing `hasBonusStages()` guard (around line 970), add two guards before any state is captured:

```java
// After: if (!provider.hasBonusStages()) { return; }

if (type == null || type == BonusStageType.NONE) {
    LOGGER.fine("Bonus stage entry ignored: NONE type");
    return;
}

if (fadeManager.isActive()) {
    LOGGER.fine("Bonus stage entry ignored: fade already in progress");
    return;
}
```

- [ ] **Step 2: Run tests to verify no regression**

Run: `mvn test -Dtest="TestBonusStageLifecycle" -q`
Expected: PASS (existing lifecycle tests still green)

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit -m "fix: add fade-guard and NONE-type guard to enterBonusStage"
```

---

## Task 2: Fix ROM parity bug in `onCollectPush` (Critical)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java:339-353`

**Context:** The ROM clears three distinct status bits: `Status_RollJump` (`bclr`), `Status_Push` (`bclr`), and `jumping` (`clr.b`). The engine maps the first two to `setJumping(false)` (duplicate). The correct mapping is: `setRollingJump(false)` for `Status_RollJump`, `setPushing(false)` for `Status_Push`, and `setJumping(false)` for `jumping`. Both `setRollingJump` and `setJumping` exist on `AbstractPlayableSprite` (the `sprite` variable's type at this point). `setPushing` exists on `PlayableEntity`.

- [ ] **Step 1: Fix the three status-bit clears in `onCollectPush`**

In `GumballItemObjectInstance.java`, replace the try/catch block in `onCollectPush` (lines 339-353):

```java
        try {
            sprite.setXSpeed((short) xVel);
            sprite.setYSpeed((short) yVel);

            // ROM: bset #Status_InAir,status(a1)
            sprite.setAir(true);

            // ROM: bclr #Status_RollJump,status(a1)
            sprite.setRollingJump(false);

            // ROM: bclr #Status_Push,status(a1)
            sprite.setPushing(false);

            // ROM: clr.b jumping(a1)
            sprite.setJumping(false);
        } catch (Exception e) {
            // safe fallback
        }
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest="TestBonusStageLifecycle,TestGumballMachineDrift" -q`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java
git commit -m "fix: ROM-accurate Status_RollJump/Push/jumping clears in GumballItem push"
```

---

## Task 3: Fix unsafe cast in `GumballTriangleBumperObjectInstance` (Critical)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballTriangleBumperObjectInstance.java:90-94`

**Context:** Direct cast `(AbstractPlayableSprite) playerEntity` will throw `ClassCastException` if the entity is a non-matching type. Other gumball objects correctly use `instanceof` pattern matching. The null-check after the cast is dead code (NPE would fire first).

- [ ] **Step 1: Replace direct cast with `instanceof` pattern matching**

In `GumballTriangleBumperObjectInstance.java`, replace lines 91-94:

Old:
```java
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }
```

New:
```java
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest="TestBonusStageLifecycle,TestGumballMachineDrift" -q`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballTriangleBumperObjectInstance.java
git commit -m "fix: use instanceof pattern matching in GumballTriangleBumper"
```

---

## Task 4: Promote `addRings`/`setAwardedShield` to `BonusStageProvider` interface (Major)

**Files:**
- Modify: `src/main/java/com/openggf/game/BonusStageProvider.java:10-32`
- Modify: `src/main/java/com/openggf/game/NoOpBonusStageProvider.java:6-21`
- Modify: `src/main/java/com/openggf/level/objects/DefaultObjectServices.java:305-327`

**Context:** `DefaultObjectServices.addBonusStageRings()` and `setBonusStageShield()` use `instanceof AbstractBonusStageCoordinator` downcasts. This silently no-ops for any non-subclass provider. The methods should be on the interface itself with default no-op implementations, and `AbstractBonusStageCoordinator` already has the real implementations.

- [ ] **Step 1: Add default methods to `BonusStageProvider` interface**

In `BonusStageProvider.java`, add three default methods after line 21 (before the `BonusStageRewards` record):

```java
    /** Accumulate rings. ROM equivalent: add.w d0,(Saved_ring_count).w */
    default void addRings(int count) {}

    /** Accumulate lives. ROM equivalent: addq.b #1,(Life_count).w */
    default void addLife() {}

    /** Record shield awarded during bonus stage. */
    default void setAwardedShield(com.openggf.game.ShieldType type) {}
```

`NoOpBonusStageProvider` inherits the no-op defaults — no changes needed there.

`AbstractBonusStageCoordinator` already has `addRings(int)`, `addLife()`, and `setAwardedShield(ShieldType)` as public methods. Add `@Override` to each:

```java
    @Override
    public void addRings(int count) { ringsCollected += count; }

    @Override
    public void addLife() { livesAwarded++; }

    @Override
    public void setAwardedShield(ShieldType type) {
        this.awardedShield = type;
    }
```

- [ ] **Step 2: Remove `instanceof` downcasts in `DefaultObjectServices`**

In `DefaultObjectServices.java`, replace `addBonusStageRings` (lines 306-315):

```java
    @Override
    public void addBonusStageRings(int count) {
        try {
            com.openggf.game.GameServices.bonusStage().addRings(count);
        } catch (Exception e) {
            LOG.warning("addBonusStageRings failed: " + e.getMessage());
        }
    }
```

Replace `setBonusStageShield` (lines 317-327):

```java
    @Override
    public void setBonusStageShield(com.openggf.game.ShieldType type) {
        try {
            com.openggf.game.GameServices.bonusStage().setAwardedShield(type);
        } catch (Exception e) {
            LOG.warning("setBonusStageShield failed: " + e.getMessage());
        }
    }
```

- [ ] **Step 3: Run tests**

Run: `mvn test -Dtest="TestBonusStageLifecycle,TestGumballMachineDrift,TestObjectServices" -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/BonusStageProvider.java \
       src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java \
       src/main/java/com/openggf/level/objects/DefaultObjectServices.java
git commit -m "refactor: promote addRings/setAwardedShield to BonusStageProvider interface"
```

---

## Task 5: Add `exitFired` guard to `ExitTriggerChild` (Major)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java:860-879`

**Context:** `ExitTriggerChild.update()` calls `services().requestBonusStageExit()` every frame the player is in the exit zone. The coordinator's `requestExit()` is idempotent, but it logs LOGGER.info every frame and could cause issues if side effects are added later.

- [ ] **Step 1: Add `exitFired` guard field and check**

In `GumballMachineObjectInstance.java`, inside the `ExitTriggerChild` inner class:

Add a field after the constants (around line 849):
```java
        private boolean exitFired;
```

Then modify `update()` — wrap the exit request in a guard:

```java
        @Override
        public void update(int frameCounter, PlayableEntity playerEntity) {
            if (playerEntity == null || exitFired) {
                return;
            }

            int playerX = playerEntity.getCentreX();
            int playerY = playerEntity.getCentreY();
            int dx = playerX - spawn.x();
            int dy = playerY - spawn.y();

            if (dx >= EXIT_X_MIN && dx <= EXIT_X_MAX
                    && dy >= EXIT_Y_MIN && dy <= EXIT_Y_MAX) {
                exitFired = true;
                LOGGER.info("GumballExitTrigger: player in exit range, requesting bonus stage exit");
                try {
                    services().requestBonusStageExit();
                } catch (Exception e) {
                    LOGGER.warning("GumballExitTrigger: failed to request exit: " + e.getMessage());
                }
            }
        }
```

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest="TestBonusStageLifecycle,TestGumballMachineDrift" -q`
Expected: PASS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java
git commit -m "fix: ExitTriggerChild fires requestBonusStageExit only once"
```

---

## Task 6: Dead code removal and stale comment cleanup (Simplification)

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java:101-103,285`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java:82-85,99-100,137-144,470-473`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java:327`
- Modify: `src/main/java/com/openggf/game/sonic3k/scroll/SwScrlGumball.java:71-72`
- Modify: `src/main/java/com/openggf/GameLoop.java:1023-1024,1097-1102,1122-1123,1143-1144`
- Modify: `src/main/java/com/openggf/game/GameRuntime.java:43`

- [ ] **Step 1: Remove dead constants from `GumballMachineObjectInstance`**

Delete lines 101-103 (the unused `DISPENSER_OFFSET_X/Y` constants and their comment):
```java
    // Kept for legacy reference but no longer used for dispenser positioning.
    private static final int DISPENSER_OFFSET_X = 0;
    private static final int DISPENSER_OFFSET_Y = 0;
```

Delete the redundant `currentInstance = this` at line 285 (already set in constructor line 203):
```java
            currentInstance = this;
```

- [ ] **Step 2: Remove dead field and collapse branch in `GumballItemObjectInstance`**

Remove the `useGumballMappings` field declaration at line 99-100:
```java
    /** Whether this item uses GumballBonus mappings (true) or PachinkoFItem (false). */
    private final boolean useGumballMappings;
```

Collapse the constructor branch (lines 138-144) from:
```java
        if (subtype == 0) {
            this.useGumballMappings = true;
            this.mappingFrame = 8;  // byte_61466: $7F, 8, 8, $FC
        } else {
            this.useGumballMappings = true;
            this.mappingFrame = subtype + 8;
        }
```
To:
```java
        // ROM animation scripts (byte_61466+) use subtype + 8 as the static frame.
        this.mappingFrame = subtype + 8;
```

Replace the ternary in `appendRenderCommands` (lines 471-473) from:
```java
        String artKey = useGumballMappings
                ? Sonic3kObjectArtKeys.GUMBALL_BONUS
                : Sonic3kObjectArtKeys.GUMBALL_BONUS; // TODO: add PACHINKO_F_ITEM art key when pachinko is implemented
```
To:
```java
        String artKey = Sonic3kObjectArtKeys.GUMBALL_BONUS;
```

Remove the unused `RING_REWARD_TABLE` constant (lines 81-85):
```java
    // ROM: byte_1E44C4 — ring reward table indexed by (y_pos & 0xF)
    private static final int[] RING_REWARD_TABLE = {
            0x50, 0x32, 0x28, 0x23, 0x23, 0x1E, 0x1E, 0x14,
            0x14, 0x0A, 0x0A, 0x0A, 0x0A, 0x05, 0x05, 0x05
    };
```

- [ ] **Step 3: Remove unreachable `case 0x13` from `Sonic3kPatternAnimator`**

In `Sonic3kPatternAnimator.java`, the `update()` method has an early return at line 304-307 for `zoneIndex == 0x13`, making the `case 0x13 -> updateGumball()` at line 327 unreachable. Delete line 327:
```java
            case 0x13 -> updateGumball();
```

- [ ] **Step 4: Remove stale tuning comment from `SwScrlGumball`**

In `SwScrlGumball.java`, delete the stale "3 columns" comment at lines 71-72:
```java
    // Tuned from chunk boundaries [0x80, 0x180) by narrowing 3 columns (48px)
    // on each side to match the visible machine body tile area.
```
Keep only the "4 columns" comment (lines 73-74) which matches the actual values.

- [ ] **Step 5: Fix stale Javadoc in `GameLoop`**

Delete the first (stale) Javadoc block before `forcePlayerHighPriorityInBonusStage` (lines 1097-1102):
```java
    /**
     * Forces the player sprite to VDP high priority during the bonus stage.
     * ROM bset #7,(Player_1+art_tile).w is a one-time set at init, but
     * various engine paths (hurt landing at AbstractPlayableSprite:963,
     * plane switching) can clear it. Re-assert every frame.
     */
```

Fix three Javadoc comments that say "white" when the code uses `startFadeToBlack`:
- Line 1024: change `"fade-to-white"` → `"fade-to-black"`
- Line 1123: change `"Fades to white"` → `"Fades to black"`
- Line 1144: change `"fade-to-white"` → `"fade-to-black"`

- [ ] **Step 6: Remove unnecessary `volatile` from `GameRuntime`**

In `GameRuntime.java` line 43, change:
```java
    private volatile BonusStageProvider activeBonusStageProvider = NoOpBonusStageProvider.INSTANCE;
```
To:
```java
    private BonusStageProvider activeBonusStageProvider = NoOpBonusStageProvider.INSTANCE;
```
The game loop is single-threaded; no cross-thread access exists for this field.

- [ ] **Step 7: Run full test suite**

Run: `mvn test -q`
Expected: ALL PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java \
       src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java \
       src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java \
       src/main/java/com/openggf/game/sonic3k/scroll/SwScrlGumball.java \
       src/main/java/com/openggf/GameLoop.java \
       src/main/java/com/openggf/game/GameRuntime.java
git commit -m "cleanup: remove dead code, stale comments, and fix Javadoc in gumball objects"
```

---

## Gate: Full validation

After all 6 tasks are complete:

- [ ] **Run full test suite**

```bash
mvn test -q
```
Expected: ALL PASS — no regressions.

- [ ] **Spawn a code-review agent** against the new commits to verify all issues are resolved

The reviewer should confirm:
1. Fade-guard present in `enterBonusStage`
2. `BonusStageType.NONE` rejected at entry
3. Three distinct ROM status bits cleared in `onCollectPush`
4. `instanceof` pattern matching in triangle bumper
5. No `instanceof AbstractBonusStageCoordinator` downcasts remain
6. `ExitTriggerChild` fires exit request only once
7. All dead code/stale comments removed
8. No new issues introduced

---

## Issues deferred (not in this plan)

These were flagged by the review but are lower priority or require broader design discussion:

- **Static `currentInstance` singleton** in `GumballMachineObjectInstance` — needs architectural decision on whether to route through `ObjectServices` or `GameRuntime`. Deferring to avoid scope creep.
- **Excessive try/catch blocks** in `GumballItemObjectInstance` — 9 broad `catch(Exception)` blocks. Requires `StubObjectServices` to provide adequate stubs for all service methods first.
- **Duplicated render-Y calculation** in `PlatformChild`/`BodyOverlayChild` — minor DRY improvement, low impact.
- **Duplicated spring-crumble signaling** — minor DRY, low risk of the current pattern.
- **Duplicated mainCode lookup** in `GameLoop` — minor DRY across 4 call sites.
- **Duplicated `activeBonusStageProvider` state** between `GameLoop` and `GameRuntime` — architectural cleanup that touches state management patterns.
- **Gumball animated tiles frame counter vs ROM formula** — should document in `KNOWN_DISCREPANCIES.md`.
- **Missing test coverage** for item subtypes, bumper bounce, spring crumble chain, VSCROLL, shield rewards.
