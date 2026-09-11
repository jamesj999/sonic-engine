# Per-Character Respawn Strategies Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Knuckles glide-in and Sonic walk/spindash-in respawn strategies so each sidekick character re-enters the game with distinctive movement.

**Architecture:** Two new `SidekickRespawnStrategy` implementations wired by character name in the spawn loop. The interface gains a boolean return from `beginApproach` to support Sonic's terrain probe failure case. The controller's `enterApproachingState()` and `respawnToApproaching()` delegate to the strategy.

**Tech Stack:** Java 21, Maven, JUnit 5

**Spec:** `docs/architecture/designs/2026-03-19-per-character-respawn-strategies-design.md`

---

## File Map

### New Files

| File | Responsibility |
|------|---------------|
| `src/main/java/com/openggf/sprites/playable/KnucklesRespawnStrategy.java` | Glide-in from screen edge, drop when X-aligned or timeout |
| `src/main/java/com/openggf/sprites/playable/SonicRespawnStrategy.java` | Walk/spindash-in from screen edge floor, terrain probe |

### Modified Files

| File | Change |
|------|--------|
| `src/main/java/com/openggf/sprites/playable/SidekickRespawnStrategy.java` | `beginApproach` returns `boolean` |
| `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java` | Return `true` from `beginApproach` |
| `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` | Handle false from `beginApproach`, delegate `enterApproachingState()` to strategy |
| `src/main/java/com/openggf/Engine.java` | Strategy selection by character name in spawn loop |

---

## Task 1: Change `beginApproach` to return boolean

Update the interface, existing implementations, and controller to support strategy-driven approach failures.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickRespawnStrategy.java`
- Modify: `src/main/java/com/openggf/sprites/playable/TailsRespawnStrategy.java`
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`

- [ ] **Step 1: Change interface signature**

In `SidekickRespawnStrategy.java`, change `void beginApproach(...)` to `boolean beginApproach(...)`. Update the Javadoc:
```java
/**
 * Position and initialize the sidekick when transitioning from SPAWNING to APPROACHING.
 * @return true if approach started, false if conditions not met (stay in SPAWNING)
 */
boolean beginApproach(AbstractPlayableSprite sidekick, AbstractPlayableSprite leader);
```

- [ ] **Step 2: Update TailsRespawnStrategy**

Change `beginApproach` to return `true` (Tails can always fly in — no preconditions).

- [ ] **Step 3: Update respawnToApproaching() in controller**

In `SidekickCpuController.respawnToApproaching(target)` (~line 139), check the return value:
```java
private void respawnToApproaching(AbstractPlayableSprite target) {
    boolean started = respawnStrategy.beginApproach(sidekick, target);
    if (!started) {
        // Strategy can't start (e.g. no floor for Sonic) — stay in SPAWNING
        return;
    }
    state = State.APPROACHING;
    controlCounter = 0;
    despawnCounter = 0;
    normalFrameCount = 0;
    jumpingFlag = false;
}
```

- [ ] **Step 4: Update enterApproachingState() to delegate to strategy**

In `SidekickCpuController.enterApproachingState()` (~line 310), replace the hardcoded Tails fly-in with strategy delegation:
```java
private void enterApproachingState() {
    AbstractPlayableSprite target = getEffectiveLeader();
    if (target == null) {
        triggerDespawn();
        return;
    }
    sidekick.setSpindash(false);
    sidekick.setSpindashCounter((short) 0);
    boolean started = respawnStrategy.beginApproach(sidekick, target);
    if (!started) {
        // Can't approach (e.g. no floor) — go to SPAWNING to retry later
        triggerDespawn();
        return;
    }
    state = State.APPROACHING;
    despawnCounter = 0;
    controlCounter = 0;
    normalFrameCount = 0;
}
```

Remove the hardcoded `FLY_ANIM_ID`, `setControlLocked`, `setObjectControlled` lines — the strategy's `beginApproach` handles all of that.

- [ ] **Step 5: Build and run tests**

Run: `mvn test -q`
Expected: All tests pass — Tails strategy returns true, so behavior is unchanged.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "refactor: beginApproach returns boolean, enterApproachingState delegates to strategy"
```

---

## Task 2: Implement KnucklesRespawnStrategy

**Files:**
- Create: `src/main/java/com/openggf/sprites/playable/KnucklesRespawnStrategy.java`

- [ ] **Step 1: Create KnucklesRespawnStrategy**

Create `src/main/java/com/openggf/sprites/playable/KnucklesRespawnStrategy.java`:

The strategy needs:
- Constructor receives `SidekickCpuController` (for `getEffectiveLeader()`, `clampTargetYToWater()`)
- `beginApproach`: position at screen edge opposite to leader's movement direction, 192px above. Set air=true, direction toward leader. Always returns `true`.
- `updateApproaching`: move horizontally at 4px/frame toward leader, descend 1px/frame. Track an `approachFrameCount`. When within 16px of leader X OR `approachFrameCount >= 180` (3s timeout), stop horizontal movement and let gravity drop. Return `true` when `!sidekick.getAir()` (landed).

Key implementation details:
- Use `Camera.getInstance().getX()` for left screen edge, `camera.getX() + 320` for right edge
- Leader movement direction: `leader.getXSpeed() > 0` → right, `< 0` → left, `== 0` → default left edge
- During glide phase: directly set `sidekick.setX()` and `sidekick.setY()` each frame (like TailsRespawnStrategy does)
- During drop phase: stop setting X/Y, set `setControlLocked(false)` and `setObjectControlled(false)` so gravity works
- Keep `setControlLocked(true)` and `setObjectControlled(true)` during glide phase
- Use a `dropping` boolean flag to track phase transition (glide → drop)
- Reset `approachFrameCount` and `dropping` in `beginApproach`

Animation: use `Sonic2AnimationIds.WALK.id()` as placeholder (no Knuckles glide animation available). The spec notes this is out of scope.

- [ ] **Step 2: Build and run tests**

Run: `mvn test -q`
Expected: All tests pass (strategy isn't wired up yet).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add KnucklesRespawnStrategy (glide-in from screen edge)"
```

---

## Task 3: Implement SonicRespawnStrategy

**Files:**
- Create: `src/main/java/com/openggf/sprites/playable/SonicRespawnStrategy.java`

- [ ] **Step 1: Create SonicRespawnStrategy**

Create `src/main/java/com/openggf/sprites/playable/SonicRespawnStrategy.java`:

The strategy needs:
- Constructor receives `SidekickCpuController`
- `beginApproach`:
  1. Determine screen edge from leader speed (same logic as Knuckles)
  2. Terrain probe: call `ObjectTerrainUtils.checkFloorDist(edgeX, leaderCentreY)` in a loop, stepping down 16px per iteration, up to 8 iterations (128px). Use the first result where `result.foundSurface()` and `result.distance() >= 0`.
  3. If no floor found: return `false`
  4. Compute `floorY = probeY + result.distance()`
  5. Place sidekick at (edgeX, floorY), adjusting for sprite height (set centreY)
  6. Set `controlLocked = false`, `objectControlled = false`, `air = false`
  7. Set direction toward leader
  8. If `Math.abs(leader.getGSpeed()) > 0x600`: set `rolling = true`, `gSpeed` toward leader at 0x800
  9. Otherwise: set `gSpeed` toward leader at 0x200
  10. Return `true`
- `updateApproaching`:
  1. Set `inputLeft` or `inputRight` toward leader to maintain movement
  2. Return `true` when within 32px of leader's X

Key implementation details:
- Import `com.openggf.physics.ObjectTerrainUtils` and `com.openggf.physics.TerrainCheckResult`
- Import `com.openggf.camera.Camera`
- The terrain probe loop:
```java
int probeX = edgeX;
int probeY = leaderCentreY;
TerrainCheckResult floorResult = null;
for (int step = 0; step < 8; step++) {
    TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(probeX, probeY);
    if (result.foundSurface() && result.distance() >= 0) {
        floorResult = result;
        break;
    }
    probeY += 16;
}
if (floorResult == null) {
    return false; // No ground — stay in SPAWNING
}
int floorY = probeY + floorResult.distance();
```

- [ ] **Step 2: Build and run tests**

Run: `mvn test -q`
Expected: All tests pass (strategy isn't wired up yet).

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: add SonicRespawnStrategy (walk/spindash-in from screen edge floor)"
```

---

## Task 4: Wire strategy selection in spawn loop

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Add strategy selection after controller creation**

In `Engine.java` spawn loop (~line 389, after `sidekick.setCpuController(controller)`), add:

```java
// Select respawn strategy based on character type
if ("knuckles".equalsIgnoreCase(charName)) {
    controller.setRespawnStrategy(new KnucklesRespawnStrategy(controller));
} else if (!"tails".equalsIgnoreCase(charName)) {
    controller.setRespawnStrategy(new SonicRespawnStrategy(controller));
}
// Tails is the default — already set in constructor
```

Add imports for `KnucklesRespawnStrategy` and `SonicRespawnStrategy`.

- [ ] **Step 2: Build and run tests**

Run: `mvn test -q`
Expected: All tests pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat: wire per-character respawn strategy selection in spawn loop"
```

---

## Task 5: Integration tests

**Files:**
- Create: `src/test/java/com/openggf/sprites/playable/TestRespawnStrategies.java`

- [ ] **Step 1: Write unit tests for strategy selection and behavior**

Create `src/test/java/com/openggf/sprites/playable/TestRespawnStrategies.java`:

Tests (using TestableSprite stubs, no ROM):

1. `testTailsIsDefaultStrategy` — new controller has TailsRespawnStrategy
2. `testKnucklesStrategyAlwaysBegins` — KnucklesRespawnStrategy.beginApproach returns true
3. `testSonicStrategyReturnsFalseWithoutLevel` — SonicRespawnStrategy.beginApproach returns false when no LevelManager is initialized (no terrain to probe)
4. `testKnucklesDropsAfterTimeout` — after 180 frames of updateApproaching, Knuckles enters drop phase
5. `testSonicApproachCompletesNearLeader` — updateApproaching returns true when sidekick is within 32px of leader X

- [ ] **Step 2: Run tests**

Run: `mvn test -Dtest=TestRespawnStrategies -q`
Expected: All pass.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test: add per-character respawn strategy unit tests"
```
