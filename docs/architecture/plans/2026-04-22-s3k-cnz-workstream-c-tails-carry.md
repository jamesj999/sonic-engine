# S3K CNZ Workstream C - Tails-Carry Intro Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Plan version:** v2 (incorporates plan-review findings — fixture API corrections, commit-trailer rollup, `currentLevelManager()` call path, `onLevelLoad` signature, `CanonicalAnimation.TAILS_CARRIED` ordering, inline `updateInit()` body, §9.6 level-select audit).

**Goal:** Port the S3K Tails-carry-Sonic CNZ1 intro mechanic so `TestS3kCnzTraceReplay`'s first-divergence frame shifts from frame 1 to > 400 and total error count drops below 1000.

**Architecture:** Extend the existing `SidekickCpuController` with two carry states (`CARRY_INIT`, `CARRYING`), drive it from a pluggable `SidekickCarryTrigger` interface (S3K-only), and refactor `PlayableSpriteMovement`'s two gravity writes into an `applyGravity()` helper gated on `isObjectControlled()` to prevent latch-mismatch releases from engine-side gravity additions.

**Tech Stack:** Java 21, JUnit 5, existing S3K trace-replay framework (`AbstractTraceReplayTest`, `S3kElasticWindowController`, `S3kRequiredCheckpointGuard`), `HeadlessTestFixture` (package `com.openggf.tests`, NOT `com.openggf.tests.util`).

**Design spec:** `docs/architecture/designs/2026-04-22-s3k-cnz-workstream-c-tails-carry-design.md` (v3)
**Parent spec:** `docs/architecture/designs/2026-04-22-s3k-cnz-trace-replay-design.md` §7.1
**Baseline:** `docs/architecture/validation/s3k-zones/cnz-trace-divergence.md` (first 20 divergences all flagged C)

**Commit trailer policy:** every commit in this workstream MUST carry the 7 required policy trailers per `CLAUDE.md` §Branch Documentation Policy, with `Co-Authored-By` INSIDE the policy block. Intermediate task commits use `Changelog: n/a` / `S3K-Known-Discrepancies: n/a` (the policy validator rejects `Changelog: updated` without a staged `CHANGELOG.md` edit, AND rejects `Changelog: n/a` if `CHANGELOG.md` IS staged — so each commit must be self-consistent). Task 9 does the single documentation rollup that stages `CHANGELOG.md` and `docs/S3K_KNOWN_DISCREPANCIES.md` with matching `updated` trailers.

**Test fixture conventions** (from `TestS3kAiz1SkipHeadless`):
- All new S3K tests MUST be annotated `@RequiresRom(SonicGame.SONIC_3K)`.
- Prefer `SharedLevel.load(SonicGame.SONIC_3K, zone, act)` in `@BeforeAll`, then `HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build()`. SharedLevel applies the main/sidekick character codes automatically.
- Step frames via `fixture.stepFrame(up, down, left, right, jump)` (5 booleans, all required) or `fixture.stepIdleFrames(count)` for input-free stepping.
- Main sprite: `fixture.sprite()`. Sidekick sprite (Tails in SONIC_AND_TAILS): `GameServices.sprites().getSidekicks().get(0)`. Sidekick controller: `sidekick.getCpuController()`.

---

## File Structure

### New files
| File | Responsibility |
|------|----------------|
| `src/main/java/com/openggf/sprites/playable/SidekickCarryTrigger.java` | Game-agnostic trigger interface; pluggable per game module |
| `src/main/java/com/openggf/game/sonic3k/sidekick/Sonic3kCnzCarryTrigger.java` | CNZ1-specific trigger (zone 3 act 0, `SONIC_AND_TAILS` only) |
| `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java` | Unit tests for the new driver states |
| `src/test/java/com/openggf/game/sonic3k/sidekick/TestSonic3kCnzCarryTrigger.java` | Trigger predicate + placement tests |
| `src/test/java/com/openggf/tests/TestS3kCnzCarryHeadless.java` | Headless integration: frame-1 `x_speed == 0x0100`, parentage, release |
| `src/test/java/com/openggf/sprites/managers/TestObjectControlledGravity.java` | Gravity gate regression test |

### Modified files
| File | Change |
|------|--------|
| `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java` | Extract gravity-write expression (lines 1731 + 2276) into private `applyGravity()` helper gated on `sprite.isObjectControlled()` |
| `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java` | Add `CARRY_INIT` and `CARRYING` states; extend `mapRomCpuRoutine`; add carry fields, `updateCarryInit`, `updateCarrying`, `releaseCarry`, `performJumpRelease`; extend `reset()` to clear carry state |
| `src/main/java/com/openggf/game/CanonicalAnimation.java` | Add `TAILS_CARRIED` enum value (in Task 2 so Task 6 can reference it) |
| `src/main/java/com/openggf/game/GameModule.java` | Default `getSidekickCarryTrigger()` returning `null` |
| `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java` | Override `getSidekickCarryTrigger()` to return a `Sonic3kCnzCarryTrigger` instance |
| `src/main/java/com/openggf/game/sonic3k/Sonic3k.java` | In `loadLevel`, after the sidekick is registered, resolve its controller and call `setCarryTrigger(gameModule.getSidekickCarryTrigger())` |
| `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java` | Add carry literals (values, not ROM addresses) |
| `CHANGELOG.md` | Single rollup entry in Task 9 covering all workstream-C behavioural changes |
| `docs/S3K_KNOWN_DISCREPANCIES.md` | Task 9 removes the "CNZ1 Tails-carry intro missing" line or crosses it out with the new status |

### Unchanged
- `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` (all API already exists; verified — `isHurt()` L1480, `isObjectControlled()` L1992, `setObjectControlled(boolean)` L2001, `isJumpJustPressed()` L2069, `getCpuController()` L2708, `currentLevelManager()` L840)
- `src/main/java/com/openggf/level/LevelManager.java` (canonical `PlayerCharacter` lookup pattern at lines 1004-1007 is reused via helper; no new accessor added)

---

### Task 1: Gravity refactor — extract `applyGravity(sprite)` helper gated on `isObjectControlled()`

**Why first:** Every downstream test that exercises a carried Sonic will spuriously fail the latch compare unless gravity stops being applied during `object_control == 3`. Doing this first lets later tasks' tests observe stable velocities.

**Files:**
- Create: `src/test/java/com/openggf/sprites/managers/TestObjectControlledGravity.java`
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java:1729-1733` and `:2275-2278`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.sprites.managers;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

@RequiresRom(SonicGame.SONIC_3K)
class TestObjectControlledGravity {

    @Test
    void gravityIsSkippedWhenObjectControlled() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)  // AIZ1 — any S3K level works for this physics test
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        sonic.setAir(true);
        sonic.setObjectControlled(true);
        sonic.setYSpeed((short) 0);
        short before = sonic.getYSpeed();

        // 5 booleans: up, down, left, right, jump — all false = idle frame
        fixture.stepFrame(false, false, false, false, false);

        short after = sonic.getYSpeed();
        assertEquals(before, after,
                "y_speed must not change when object-controlled (gravity gated)");
    }

    @Test
    void gravityStillAppliedWhenNotObjectControlled() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        AbstractPlayableSprite sonic = fixture.sprite();

        sonic.setAir(true);
        sonic.setObjectControlled(false);
        sonic.setYSpeed((short) 0);
        short before = sonic.getYSpeed();

        fixture.stepFrame(false, false, false, false, false);

        short after = sonic.getYSpeed();
        assertEquals((short) (before + sonic.getGravity()), after,
                "y_speed must accumulate gravity when not object-controlled");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestObjectControlledGravity`
Expected: `gravityIsSkippedWhenObjectControlled` FAILS because gravity is applied at `PlayableSpriteMovement.java:1731` regardless of object-control state.

- [ ] **Step 3: Refactor gravity call site #1 into a helper**

In `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java`, modify `doObjectMoveAndFall()` (currently lines 1729-1733) to use a new `applyGravity(sprite)` helper, and add the helper with the gate:

```java
/** ObjectMoveAndFall: Apply velocity and gravity (s2.asm:29945-29953)
 * ROM applies gravity to y_vel BEFORE movement, but uses the OLD y_vel for position:
 *   move.w  y_vel(a0),d0           ; Save old y_vel in d0
 *   addi.w  #$38,y_vel(a0)         ; Add gravity to y_vel FIRST
 *   ext.l   d0
 *   asl.l   #8,d0
 *   add.l   d0,d3                  ; Position uses OLD y_vel (d0, before gravity)
 */
private void doObjectMoveAndFall() {
    short oldYSpeed = sprite.getYSpeed();  // Save old y_vel before gravity
    applyGravity();                         // Gated on isObjectControlled()
    sprite.move(sprite.getXSpeed(), oldYSpeed);  // Move using OLD y_vel
}

/**
 * Apply the sprite's per-frame gravity to y_vel.
 *
 * <p>Skipped when {@link AbstractPlayableSprite#isObjectControlled()} is true
 * (ROM: Obj01_Control skips movement routines entirely). This gate is what
 * lets the S3K Tails-carry driver ({@code SidekickCpuController} CARRYING
 * state) keep a stable velocity latch on the carried Sonic; without it,
 * gravity accumulates between the driver's latch-update and latch-compare
 * and spuriously triggers release path C (external-vel mismatch).
 *
 * <p>Mirrors the ROM behaviour where {@code object_control != 0} short-circuits
 * the entire movement dispatch in {@code Obj01_Control}.
 */
private void applyGravity() {
    if (sprite.isObjectControlled()) {
        return;
    }
    sprite.setYSpeed((short) (sprite.getYSpeed() + sprite.getGravity()));
}
```

- [ ] **Step 4: Refactor gravity call site #2 (death path)**

Modify `applyDeathMovement()` at lines 2275-2278 to call `applyGravity()`:

```java
private void applyDeathMovement() {
    applyGravity();  // Gated on isObjectControlled(); a controlled sprite never enters the death routine anyway but keep gates consistent
    sprite.setGSpeed((short) 0);
    sprite.setXSpeed((short) 0);

    Camera camera = camera();
    if (camera != null && sprite.getY() > camera.getY() + camera.getHeight() + 256) {
        sprite.startDeathCountdown();
    }
}
```

- [ ] **Step 5: Run tests to verify pass**

Run: `mvn test -Dtest=TestObjectControlledGravity`
Expected: both tests PASS.

Also run the wider physics regression guard:
Run: `mvn test -Dtest="TestPhysicsProfile,TestPhysicsProfileRegression,TestSpindashGating,TestCollisionModel,TestHeadlessWallCollision"`
Expected: all PASS (no existing test exercises `object_control + gravity` together so the refactor must not regress any of them).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java \
        src/test/java/com/openggf/sprites/managers/TestObjectControlledGravity.java
git commit -m "$(cat <<'EOF'
refactor(physics): gate gravity on isObjectControlled via applyGravity helper

Extracts PlayableSpriteMovement's two gravity writes (doObjectMoveAndFall,
applyDeathMovement) into a private applyGravity() helper with an
isObjectControlled() short-circuit. Prerequisite for the S3K CNZ Tails-carry
workstream: the carry driver's velocity latch needs y_vel to stay stable
frame-to-frame while object_control == 3.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

The consolidated `CHANGELOG.md` / `docs/S3K_KNOWN_DISCREPANCIES.md` rollup lands in Task 9; do NOT stage either here (the policy validator rejects both `Changelog: n/a` with `CHANGELOG.md` staged and `Changelog: updated` without it, so every intermediate task MUST keep documentation out of its stage).

---

### Task 2: Sonic3kConstants carry literals + `CanonicalAnimation.TAILS_CARRIED`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`
- Modify: `src/main/java/com/openggf/game/CanonicalAnimation.java`

**Why grouped:** both are pure-data additions that downstream tasks reference. Adding them together in a single commit keeps Task 6's code lookup clean.

- [ ] **Step 1: Add literal constants**

Locate the "S3K gameplay literals" section (or equivalent) in `Sonic3kConstants.java` and add:

```java
// =====================================================================
// Tails-carry-Sonic intro (CNZ1 and future zones)
// ROM refs: sonic3k.asm loc_13A32 (CNZ trigger), loc_13FC2/loc_13FFA
// (carry init + body), sub_1459E (Sonic pickup), Tails_Carry_Sonic
// (per-frame parentage). All addresses < 0x200000 (S&K-side only).
// =====================================================================

/** Zone-and-act word value that triggers the CNZ1 Tails-carry intro. */
public static final int CARRY_TRIGGER_ZONE_ACT_WORD = 0x0300;

/** Tails's spawn X after the CNZ1 trigger. ROM: loc_13A32. */
public static final int CARRY_INIT_TAILS_X = 0x0018;

/** Tails's spawn Y after the CNZ1 trigger. ROM: loc_13A32. */
public static final int CARRY_INIT_TAILS_Y = 0x0600;

/** Constant horizontal flight velocity while carrying. ROM: loc_13FC2 x_vel write. */
public static final short CARRY_INIT_TAILS_X_VEL = (short) 0x0100;

/** Sonic hangs this many pixels below Tails's centre. ROM: sub_1459E y_pos + 0x1C. */
public static final int CARRY_DESCEND_OFFSET_Y = 0x1C;

/** Level_frame_counter mask that gates synthetic right-press injection.
 *  Every 32 frames: (Level_frame_counter + 1) & 0x1F == 0. ROM: loc_13FFA. */
public static final int CARRY_INPUT_INJECT_MASK = 0x1F;

/** Cooldown frames after A/B/C jump release. ROM: Tails_Carry_Sonic line 27241. */
public static final int CARRY_COOLDOWN_JUMP_RELEASE = 0x12;

/** Cooldown frames after external-vel latch-mismatch release. ROM: loc_14466. */
public static final int CARRY_COOLDOWN_LATCH_RELEASE = 0x3C;

/** Post-A/B/C-release y_vel (jump impulse). ROM: Tails_Carry_Sonic line ~27248. */
public static final short CARRY_RELEASE_JUMP_Y_VEL = (short) -0x0380;

/** Post-A/B/C-release x_vel magnitude (sign applied from face direction). */
public static final short CARRY_RELEASE_JUMP_X_VEL = (short) 0x0200;

/** Sonic's `anim` byte while carried. ROM: sub_1459E writes 0x2200 word (high byte 0x22). */
public static final int CARRY_SONIC_ANIM_BYTE = 0x22;
```

- [ ] **Step 2: Add `CanonicalAnimation.TAILS_CARRIED`**

Open `src/main/java/com/openggf/game/CanonicalAnimation.java`. Find the enum value list. Add a new value near the end (the exact slot does not matter — the enum is a flat namespace, not ordered by anything that consumers depend on). Example placement: after `FLY` or at the end of the list with a comment.

```java
    /** S3K: Sonic's animation while being carried by Tails.
     *  ROM reference: sub_1459E writes anim high-byte 0x22 on the carried Sonic.
     *  SpriteAnimationProfile lookup may not define a mapping yet — callers
     *  must tolerate a -1 return from SpriteAnimationSet.getAnimIdFor(...).
     */
    TAILS_CARRIED,
```

No character's `SpriteAnimationSet` currently maps this canonical to a ROM anim id; that visual wiring is deferred per spec §9.2 (workstream G / visual polish). Physics-only usage in this workstream is via `sprite.setForcedAnimationId(resolvedId)` where `resolvedId == -1` becomes a no-op — safe by construction.

- [ ] **Step 3: Compile + targeted test check**

Run: `mvn compile`
Expected: no errors. Constants and new enum value are self-contained.

Then run the CanonicalAnimation regression test to catch count/presence drift from the new enum value:

Run: `mvn test -Dtest=TestCanonicalAnimationMapping -q`
Expected: all tests green. If `canonicalEnumHasExpectedCount` is red, the added `TAILS_CARRIED` entry bumped the enum's length — update the expected count in `src/test/java/com/openggf/game/TestCanonicalAnimationMapping.java` line 87, add an `assertDoesNotThrow(() -> CanonicalAnimation.valueOf("TAILS_CARRIED"))` assertion in the companion `canonicalEnumContainsAllExpectedEntries` S3K block, and re-run.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java \
        src/main/java/com/openggf/game/CanonicalAnimation.java
git commit -m "$(cat <<'EOF'
feat(s3k): add Tails-carry-Sonic CNZ1 literal constants + TAILS_CARRIED canonical

Values extracted from sonic3k.asm loc_13A32, loc_13FC2, loc_13FFA,
sub_1459E, Tails_Carry_Sonic, and loc_14466. Also adds the
TAILS_CARRIED CanonicalAnimation enum value (no SpriteAnimationSet
mapping is added yet; that is workstream-G visual polish, per spec §9.2).
Supports the upcoming SidekickCarryTrigger interface and its CNZ1
implementation.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `SidekickCarryTrigger` interface + `GameModule` default

**Files:**
- Create: `src/main/java/com/openggf/sprites/playable/SidekickCarryTrigger.java`
- Modify: `src/main/java/com/openggf/game/GameModule.java`

- [ ] **Step 1: Create the interface**

Write `src/main/java/com/openggf/sprites/playable/SidekickCarryTrigger.java`:

```java
package com.openggf.sprites.playable;

import com.openggf.game.PlayerCharacter;

/**
 * Game-agnostic hook for the S3K-style Tails-carry-Sonic intro mechanic.
 *
 * <p>When supplied to {@link SidekickCpuController#setCarryTrigger}, the driver
 * polls {@link #shouldEnterCarry} on each INIT tick and, on a true return,
 * transitions to its CARRY_INIT state after calling {@link #applyInitialPlacement}.
 *
 * <p>All ROM references are {@code sonic3k.asm} (S&K-side, address < 0x200000).
 * Games that do not use the Tails-carry mechanic (S1, S2) return {@code null}
 * from {@code GameModule.getSidekickCarryTrigger()} and the driver's behaviour
 * is unchanged.
 *
 * <p>Interface uses primitives + {@link PlayerCharacter} + types already in
 * {@code com.openggf.sprites.playable} — no new dependency types are introduced.
 */
public interface SidekickCarryTrigger {

    /**
     * Invoked each INIT tick. If this returns {@code true} the driver transitions
     * to CARRY_INIT on the current frame.
     *
     * @param zoneId     canonical zone id (S3K: 0=AIZ, 1=HCZ, 2=MGZ, 3=CNZ, ...)
     * @param actId      zero-based act id
     * @param playerMode main player's character; ROM CNZ trigger gates on SONIC_AND_TAILS
     */
    boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter playerMode);

    /**
     * Positions the carrier (sidekick, typically Tails) and cargo (leader,
     * typically Sonic) for the first CARRY_INIT tick. The driver will then
     * clamp velocities via {@link #carryInitXVel()} etc.
     */
    void applyInitialPlacement(AbstractPlayableSprite carrier, AbstractPlayableSprite cargo);

    /** Cargo's descend offset below carrier's centre, in pixels. ROM CNZ: {@code 0x1C}. */
    int carryDescendOffsetY();

    /** Constant horizontal velocity held while carrying, in subpixel units.
     *  ROM CNZ: {@code 0x0100}. */
    short carryInitXVel();

    /** Level_frame_counter cadence mask for synthetic-right-press injection.
     *  Engine injects when {@code (frameCounter & mask) == 0}. ROM CNZ: {@code 0x1F}. */
    int carryInputInjectMask();

    /** Cooldown frames after A/B/C jump release. ROM CNZ: {@code 0x12} (~18 frames). */
    int carryJumpReleaseCooldownFrames();

    /** Cooldown frames after external-vel (latch mismatch) release.
     *  ROM CNZ: {@code 0x3C} (~60 frames). */
    int carryLatchReleaseCooldownFrames();

    /** Post-A/B/C-release y_vel (jump impulse). ROM CNZ: {@code -0x0380}. */
    short carryReleaseJumpYVel();

    /** Post-A/B/C-release x_vel magnitude; sign applied by driver from cargo face direction.
     *  ROM CNZ: {@code 0x0200}. */
    short carryReleaseJumpXVel();
}
```

- [ ] **Step 2: Add default to `GameModule`**

In `src/main/java/com/openggf/game/GameModule.java`, add a new default method (near the existing `onLevelLoad` default around line 286):

```java
/**
 * Supplies the sidekick-carry trigger for this game module. Defaults to
 * {@code null} (no carry mechanic). Only Sonic 3 &amp; Knuckles overrides
 * this to port the Tails-carry-Sonic CNZ1 intro.
 *
 * @see com.openggf.sprites.playable.SidekickCarryTrigger
 */
default com.openggf.sprites.playable.SidekickCarryTrigger getSidekickCarryTrigger() {
    return null;
}
```

- [ ] **Step 3: Compile check**

Run: `mvn compile`
Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCarryTrigger.java \
        src/main/java/com/openggf/game/GameModule.java
git commit -m "$(cat <<'EOF'
feat(sidekick): add SidekickCarryTrigger interface with GameModule default

Introduces the pluggable hook that SidekickCpuController will poll on each
INIT tick to decide whether to enter the S3K Tails-carry state machine. S1
and S2 keep the default null implementation and are unaffected.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `Sonic3kCnzCarryTrigger` + unit tests

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/sidekick/Sonic3kCnzCarryTrigger.java`
- Create: `src/test/java/com/openggf/game/sonic3k/sidekick/TestSonic3kCnzCarryTrigger.java`

- [ ] **Step 1: Write the failing trigger tests**

`src/test/java/com/openggf/game/sonic3k/sidekick/TestSonic3kCnzCarryTrigger.java`:

```java
package com.openggf.game.sonic3k.sidekick;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kCnzCarryTrigger {

    private final Sonic3kCnzCarryTrigger trigger = new Sonic3kCnzCarryTrigger();

    private static final int ZONE_AIZ = 0;
    private static final int ZONE_HCZ = 1;
    private static final int ZONE_MGZ = 2;
    private static final int ZONE_CNZ = 3;

    @Test
    void cnzAct1SonicAndTailsFires() {
        assertTrue(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void cnzAct1SonicAloneDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.SONIC_ALONE));
    }

    @Test
    void cnzAct1TailsAloneDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.TAILS_ALONE));
    }

    @Test
    void cnzAct1KnucklesDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 0, PlayerCharacter.KNUCKLES));
    }

    @Test
    void cnzAct2DoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_CNZ, 1, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void aizAct1SonicAndTailsDoesNotFire() {
        // CRITICAL: protects AIZ from spurious carry-mode triggering
        assertFalse(trigger.shouldEnterCarry(ZONE_AIZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void hczAct1SonicAndTailsDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_HCZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void mgzAct1SonicAndTailsDoesNotFire() {
        assertFalse(trigger.shouldEnterCarry(ZONE_MGZ, 0, PlayerCharacter.SONIC_AND_TAILS));
    }

    @Test
    void romConstantsMatch() {
        assertEquals(Sonic3kConstants.CARRY_DESCEND_OFFSET_Y, trigger.carryDescendOffsetY());
        assertEquals(Sonic3kConstants.CARRY_INIT_TAILS_X_VEL, trigger.carryInitXVel());
        assertEquals(Sonic3kConstants.CARRY_INPUT_INJECT_MASK, trigger.carryInputInjectMask());
        assertEquals(Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE,
                trigger.carryJumpReleaseCooldownFrames());
        assertEquals(Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE,
                trigger.carryLatchReleaseCooldownFrames());
        assertEquals(Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL, trigger.carryReleaseJumpYVel());
        assertEquals(Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL, trigger.carryReleaseJumpXVel());
    }
}
```

Skip the `applyInitialPlacement` test for now; it requires real sprites and is covered by the integration test in Task 8.

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSonic3kCnzCarryTrigger`
Expected: FAIL because `Sonic3kCnzCarryTrigger` class does not exist.

- [ ] **Step 3: Create the trigger class**

`src/main/java/com/openggf/game/sonic3k/sidekick/Sonic3kCnzCarryTrigger.java`:

```java
package com.openggf.game.sonic3k.sidekick;

import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCarryTrigger;

/**
 * CNZ1 Tails-carry-Sonic trigger. Zone 3 (CNZ) + Act 0 + Player_mode 0
 * (SONIC_AND_TAILS) fires; everything else returns false.
 *
 * <p>ROM trigger: {@code sonic3k.asm loc_13A32} reads
 * {@code (Current_zone_and_act).w}; on {@code 0x0300} it teleports Tails to
 * {@code (0x0018, 0x0600)} and sets {@code Tails_CPU_routine = 0x0C}.
 *
 * <p>Player_mode gating here matches how the trace-recorded BK2 was captured.
 * The ROM's zone check itself is Player_mode-agnostic (see design spec §5.7).
 */
public final class Sonic3kCnzCarryTrigger implements SidekickCarryTrigger {

    /** S3K canonical zone id for Carnival Night. */
    private static final int ZONE_CNZ = 3;

    @Override
    public boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter playerMode) {
        return zoneId == ZONE_CNZ
                && actId == 0
                && playerMode == PlayerCharacter.SONIC_AND_TAILS;
    }

    @Override
    public void applyInitialPlacement(AbstractPlayableSprite carrier,
                                      AbstractPlayableSprite cargo) {
        // Teleport Tails (carrier) to the ROM's fixed pickup position.
        // ROM sub_1459E then parents Sonic at carrier.y + 0x1C.
        carrier.setCentreXPreserveSubpixel((short) Sonic3kConstants.CARRY_INIT_TAILS_X);
        carrier.setCentreYPreserveSubpixel((short) Sonic3kConstants.CARRY_INIT_TAILS_Y);
    }

    @Override
    public int carryDescendOffsetY() { return Sonic3kConstants.CARRY_DESCEND_OFFSET_Y; }

    @Override
    public short carryInitXVel() { return Sonic3kConstants.CARRY_INIT_TAILS_X_VEL; }

    @Override
    public int carryInputInjectMask() { return Sonic3kConstants.CARRY_INPUT_INJECT_MASK; }

    @Override
    public int carryJumpReleaseCooldownFrames() {
        return Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE;
    }

    @Override
    public int carryLatchReleaseCooldownFrames() {
        return Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE;
    }

    @Override
    public short carryReleaseJumpYVel() { return Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL; }

    @Override
    public short carryReleaseJumpXVel() { return Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL; }
}
```

- [ ] **Step 4: Run tests to verify pass**

Run: `mvn test -Dtest=TestSonic3kCnzCarryTrigger`
Expected: all 9 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/sidekick/Sonic3kCnzCarryTrigger.java \
        src/test/java/com/openggf/game/sonic3k/sidekick/TestSonic3kCnzCarryTrigger.java
git commit -m "$(cat <<'EOF'
feat(s3k): add Sonic3kCnzCarryTrigger for CNZ1 Tails-carry intro

Implements SidekickCarryTrigger for the CNZ1 Sonic+Tails pickup sequence.
Zone/act/player-mode predicate plus ROM-sourced carry constants. AIZ, HCZ,
MGZ, and non-Sonic+Tails variants are all protected with explicit negative
tests. Not yet wired into Sonic3kGameModule; that follows in Task 6.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Add `CARRY_INIT` / `CARRYING` states + hydration + carry fields

**Why split from Task 6:** Allows the hydration and trace-replay interactions to be tested on their own before any behavioural logic lands.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Create: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java`

- [ ] **Step 1: Write the failing hydration tests**

`src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java` (initial content; more tests added in Task 6):

```java
package com.openggf.sprites.playable;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Hydration parity tests: confirm the new state-enum values are accepted by
 * {@link SidekickCpuController#hydrateFromRomCpuState}. We reuse the AIZ1
 * shared fixture because the hydration method does not depend on zone layout;
 * any level that registers a Tails sidekick is enough.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestSidekickCpuControllerCarry {

    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private SidekickCpuController controller;

    @BeforeEach
    void setUp() {
        HeadlessTestFixture.builder().withSharedLevel(sharedLevel).build();
        controller = GameServices.sprites().getSidekicks().get(0).getCpuController();
    }

    @Test
    void hydrateAccepts0x0CCarryInit() {
        assertDoesNotThrow(() -> controller.hydrateFromRomCpuState(0x0C, 0, 0, 0, false));
        assertEquals(SidekickCpuController.State.CARRY_INIT, controller.getState());
    }

    @Test
    void hydrateAccepts0x0ECarrying() {
        controller.hydrateFromRomCpuState(0x0E, 0, 0, 0, false);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
    }

    @Test
    void hydrateAccepts0x20Carrying() {
        controller.hydrateFromRomCpuState(0x20, 0, 0, 0, false);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSidekickCpuControllerCarry`
Expected: all three tests FAIL with `IllegalArgumentException: Unsupported ROM Tails CPU routine: 12` (or 14 / 32) from `mapRomCpuRoutine`.

- [ ] **Step 3: Add the state enum values and carry fields**

In `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`, modify the `State` enum (lines 34-40) to:

```java
public enum State {
    INIT,
    SPAWNING,
    APPROACHING,
    NORMAL,
    PANIC,
    CARRY_INIT,   // ROM routine 0x0C - first tick after trigger (teleport + pickup)
    CARRYING      // ROM routine 0x0E / 0x20 - per-frame carry body
}
```

Add new fields near the other `private` fields (around line 50-66):

```java
// =====================================================================
// Tails-carry-Sonic support (S3K-only; null trigger = feature disabled)
// =====================================================================
private SidekickCarryTrigger carryTrigger;
private short carryLatchX;
private short carryLatchY;
private boolean flyingCarryingFlag;
private int releaseCooldown;
```

Add the public setter (near the other public setters, e.g. after `setLevelBounds` around line 574):

```java
/**
 * Installs the game-specific carry trigger. Null (default) disables the
 * carry state machine; S1/S2 game modules pass null and the driver behaves
 * as before.
 */
public void setCarryTrigger(SidekickCarryTrigger trigger) {
    this.carryTrigger = trigger;
}

/** Test/debug accessor for the release-cooldown byte (ROM Flying_carrying_Sonic_flag+1). */
int getReleaseCooldownForTest() { return releaseCooldown; }
```

- [ ] **Step 4: Extend `mapRomCpuRoutine`**

Replace the existing `mapRomCpuRoutine` method (lines 525-535) with:

```java
private static State mapRomCpuRoutine(int cpuRoutine) {
    return switch (cpuRoutine) {
        case 0x00 -> State.INIT;
        case 0x02 -> State.SPAWNING;
        case 0x04 -> State.APPROACHING;
        case 0x06 -> State.NORMAL;
        case 0x08 -> State.PANIC;
        case 0x0C -> State.CARRY_INIT;
        case 0x0E, 0x20 -> State.CARRYING;
        default -> throw new IllegalArgumentException(
                "Unsupported ROM Tails CPU routine: 0x"
                        + Integer.toHexString(cpuRoutine));
    };
}
```

- [ ] **Step 5: Extend `reset()` to clear carry state**

Modify `reset()` (lines 580-599):

```java
public void reset() {
    state = State.INIT;
    despawnCounter = 0;
    controlCounter = 0;
    controller2Held = 0;
    controller2Logical = 0;
    normalFrameCount = 0;
    jumpingFlag = false;
    lastInteractObjectId = 0;
    minXBound = Integer.MIN_VALUE;
    maxXBound = Integer.MIN_VALUE;
    maxYBound = Integer.MIN_VALUE;
    clearInputs();
    sidekick.setForcedAnimationId(-1);
    sidekick.setControlLocked(false);
    sidekick.setObjectControlled(false);
    // Carry state
    carryLatchX = 0;
    carryLatchY = 0;
    flyingCarryingFlag = false;
    releaseCooldown = 0;
    // carryTrigger is intentionally NOT cleared here - it is level-load-scoped,
    // not session-scoped.
}
```

- [ ] **Step 6: Run tests to verify pass**

Run: `mvn test -Dtest=TestSidekickCpuControllerCarry`
Expected: all three tests PASS.

Also run existing sidekick tests to verify no regression:
Run: `mvn test -Dtest="TestSidekick*"` (if they exist; skip if none)

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
        src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java
git commit -m "$(cat <<'EOF'
feat(sidekick): extend SidekickCpuController state enum with CARRY_INIT/CARRYING

Adds the two new states required for the S3K Tails-carry mechanic along
with supporting fields (latch, cooldown, flag, trigger). mapRomCpuRoutine
and hydrateFromRomCpuState now accept ROM routines 0x0C, 0x0E, and 0x20
without throwing. No behavioural updates yet; those land in the next
commit.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: `updateCarryInit` + `updateCarrying` + release paths

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/SidekickCpuController.java`
- Modify: `src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java`

- [ ] **Step 1: Write failing behavioural tests**

Append to `TestSidekickCpuControllerCarry.java`. These tests exercise the controller logic directly via `controller.update(frame)` to bypass terrain/object dependencies; they reuse the AIZ1 fixture (class-level `@BeforeAll`) and install a stub trigger that always returns `true`.

Add these imports at the top of the test file (if not already present):

```java
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
```

Add a helper method on the test class (private, inside the class body):

```java
    /** Stub trigger that always enters carry; used to exercise state machine in AIZ. */
    private SidekickCarryTrigger alwaysOnTrigger() {
        return new SidekickCarryTrigger() {
            @Override
            public boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter pc) {
                return true;
            }
            @Override
            public void applyInitialPlacement(AbstractPlayableSprite carrier,
                                              AbstractPlayableSprite cargo) {
                // Intentional no-op; teleport parity is covered by Task 4's applyInitialPlacement test.
            }
            @Override public int   carryDescendOffsetY()           { return Sonic3kConstants.CARRY_DESCEND_OFFSET_Y; }
            @Override public short carryInitXVel()                 { return Sonic3kConstants.CARRY_INIT_TAILS_X_VEL; }
            @Override public int   carryInputInjectMask()          { return Sonic3kConstants.CARRY_INPUT_INJECT_MASK; }
            @Override public int   carryJumpReleaseCooldownFrames(){ return Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE; }
            @Override public int   carryLatchReleaseCooldownFrames(){ return Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE; }
            @Override public short carryReleaseJumpYVel()          { return Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL; }
            @Override public short carryReleaseJumpXVel()          { return Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL; }
        };
    }

    /**
     * Resets the fixture's sidekick controller to INIT with the stub trigger installed,
     * and returns (sonic, tails) for convenience.
     */
    private AbstractPlayableSprite[] prepareCarry() {
        AbstractPlayableSprite sonic = fixture.sprite();
        AbstractPlayableSprite tails = GameServices.sprites().getSidekicks().get(0);
        // Position both high in the air so terrain doesn't interfere with carry tick.
        sonic.setAir(true);
        tails.setAir(true);
        controller.setCarryTrigger(alwaysOnTrigger());
        controller.setInitialState(SidekickCpuController.State.INIT);
        return new AbstractPlayableSprite[] { sonic, tails };
    }
```

Then the actual test methods:

```java
    // --- init transition --------------------------------------------------

    @Test
    void initWithTriggerTransitionsToCarryingSameFrame() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];

        controller.update(1);

        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
        assertTrue(sonic.isObjectControlled(),
                "Sonic must be object-controlled while carried");
        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "Sonic.x_speed must match Tails's carry x_vel on frame 1");
        assertTrue(sonic.getAir(), "Sonic.air must be true while carried");
    }

    // --- per-frame parentage ---------------------------------------------

    @Test
    void carryingCopiesTailsVelocityToSonicEachFrame() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);  // reach CARRYING

        for (int i = 0; i < 10; i++) {
            controller.update(2 + i);
            assertEquals((short) 0x0100, sonic.getXSpeed(),
                    "Sonic.x_speed must be clamped to carry x_vel on frame " + (i + 2));
            assertEquals(SidekickCpuController.State.CARRYING, controller.getState());
        }
    }

    // --- release path A: ground contact ---------------------------------

    @Test
    void groundReleasesCarry() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);
        assertEquals(SidekickCpuController.State.CARRYING, controller.getState());

        sonic.setAir(false);  // simulate landing
        controller.update(2);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
        assertFalse(sonic.isObjectControlled());
        assertEquals(0, controller.getReleaseCooldownForTest(),
                "Ground release has no cooldown");
    }

    // --- release path B: A/B/C press ------------------------------------

    @Test
    void jumpPressReleasesCarryWithJumpVelocity() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);

        // Simulate rising-edge jump press: previous frame false, this frame true.
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(2);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
        assertEquals((short) -0x0380, sonic.getYSpeed(),
                "Jump release imparts -0x380 y_vel");
        assertEquals(0x12, controller.getReleaseCooldownForTest(),
                "Jump release cooldown is 0x12 (~18 frames)");
    }

    // --- release path C: latch mismatch ---------------------------------

    @Test
    void externalXSpeedChangeReleasesCarryWithLatchCooldown() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);  // reach CARRYING with latchX = 0x100
        sonic.setXSpeed((short) 0x0500);  // external bumper-style write

        controller.update(2);

        assertEquals(SidekickCpuController.State.NORMAL, controller.getState());
        assertEquals(0x3C, controller.getReleaseCooldownForTest(),
                "Latch-mismatch release cooldown is 0x3C (~60 frames)");
    }

    // --- cooldown countdown ---------------------------------------------

    @Test
    void cooldownDecrementsEveryFrame() {
        AbstractPlayableSprite[] pair = prepareCarry();
        AbstractPlayableSprite sonic = pair[0];
        controller.update(1);
        sonic.setJumpInputPressed(false);
        sonic.setJumpInputPressed(true);
        controller.update(2);
        int cooldownStart = controller.getReleaseCooldownForTest();
        assertEquals(0x12, cooldownStart);

        sonic.setJumpInputPressed(false);
        controller.update(3);
        controller.update(4);
        controller.update(5);

        assertEquals(cooldownStart - 3, controller.getReleaseCooldownForTest(),
                "Cooldown must decrement 1 per frame");
    }

    // --- input injection ------------------------------------------------

    @Test
    void carryInjectsSyntheticRightEvery32Frames() {
        prepareCarry();
        controller.update(1);  // reach CARRYING
        boolean sawInjection = false;
        for (int i = 2; i < 66; i++) {
            controller.update(i);
            if (controller.getInputRight()) {
                sawInjection = true;
                break;
            }
        }
        assertTrue(sawInjection, "Right-press injection must fire at least once in 64 frames");
    }
```

Helper accessor `public boolean getInputRight() { return inputRight; }` already exists on `SidekickCpuController` at line 540. No fixture API changes are required — tests drive `controller.update(frame)` directly and manipulate sprites via their existing setters.

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestSidekickCpuControllerCarry`
Expected: the 7 new tests FAIL (compilation errors or wrong state/values). The 3 hydration tests from Task 5 still PASS.

- [ ] **Step 3: Implement `updateInit` modification**

In `SidekickCpuController.java`, locate `updateInit()` (grep for the method name) and add the carry-trigger check at the top. Also add the canonical `resolvePlayerCharacter()` helper.

Add imports near the top (if not already present):

```java
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.PlayerCharacter;
import com.openggf.level.LevelManager;
```

Replace the entire existing `updateInit()` body (currently lines 107-120 of `SidekickCpuController.java`) with the new version below. The `currentLevelManager()` call on the sidekick sprite is the canonical engine-wide way to obtain the `LevelManager` from object code (it is the same idiom used elsewhere in this file at line 352, `LevelManager levelManager = sidekick.currentLevelManager();`). **Do not use `LevelManager.getInstance()`** — that static accessor does not exist on this branch; all code paths go through `AbstractPlayableSprite.currentLevelManager()`.

```java
private void updateInit() {
    // S3K Tails-carry hook (null trigger = no-op, keeps S1/S2 behaviour).
    if (carryTrigger != null && leader != null) {
        LevelManager lm = sidekick.currentLevelManager();
        if (lm != null) {
            int zone = lm.getCurrentZone();
            int act = lm.getCurrentAct();
            PlayerCharacter pc = resolvePlayerCharacter();
            if (carryTrigger.shouldEnterCarry(zone, act, pc)) {
                carryTrigger.applyInitialPlacement(sidekick, leader);
                state = State.CARRY_INIT;
                updateCarryInit();  // Same-frame fall-through per ROM (0x0C -> 0x20)
                return;
            }
        }
    }

    // ---- existing INIT body (preserved verbatim from the pre-carry implementation) ----
    state = State.NORMAL;
    controlCounter = 0;
    despawnCounter = 0;
    normalFrameCount = 0;
    jumpingFlag = false;
    lastInteractObjectId = 0;
    sidekick.setForcedAnimationId(-1);
    sidekick.setControlLocked(false);
    sidekick.setObjectControlled(false);
    sidekick.setXSpeed((short) 0);
    sidekick.setYSpeed((short) 0);
    sidekick.setGSpeed((short) 0);
}

private static PlayerCharacter resolvePlayerCharacter() {
    // Canonical engine lookup; mirrors LevelManager.java:1004-1007.
    GameModule gameModule = GameModuleRegistry.getCurrent();
    if (gameModule != null) {
        LevelEventProvider lep = gameModule.getLevelEventProvider();
        if (lep instanceof AbstractLevelEventManager alem) {
            return alem.getPlayerCharacter();
        }
    }
    return PlayerCharacter.SONIC_AND_TAILS;
}
```

Note: the carry short-circuit fires before the NORMAL transition, so when the trigger returns `false` the control flow falls through to the exact same 12 body lines that existed pre-workstream — zero behavioural change for S1/S2/AIZ/HCZ/MGZ.

- [ ] **Step 4: Implement `updateCarryInit` and `updateCarrying`**

Add these private methods:

```java
/** ROM routine 0x0C. Mirrors sub_1459E (pickup) then falls through to 0x20. */
private void updateCarryInit() {
    // sub_1459E semantics on Sonic (leader = cargo)
    leader.setObjectControlled(true);
    leader.setAir(true);
    leader.setRolling(false);
    leader.setRollingJump(false);
    leader.setGSpeed((short) 0);
    leader.setXSpeed(carryTrigger.carryInitXVel());
    leader.setYSpeed((short) 0);
    // Forced animation id: high-byte 0x22 per ROM sub_1459E; the sprite's
    // animation table maps the "carried" id. If the mapping is not yet wired
    // (see risk §9.2), this is a no-op for physics parity.
    leader.setForcedAnimationId(leader.resolveAnimationId(CanonicalAnimation.TAILS_CARRIED));

    // Tails's per-carry state
    sidekick.setAir(true);
    sidekick.setXSpeed(carryTrigger.carryInitXVel());
    sidekick.setYSpeed((short) 0);
    sidekick.setGSpeed((short) 0);
    sidekick.setControlLocked(true);
    sidekick.setForcedAnimationId(flyAnimId);

    // Initialize the latch
    carryLatchX = carryTrigger.carryInitXVel();
    carryLatchY = 0;
    flyingCarryingFlag = true;
    releaseCooldown = 0;

    state = State.CARRYING;
    // ROM 0x0C -> 0x20 fall-through: one tick of the body this same frame.
    updateCarrying();
}

/** ROM routines 0x0E / 0x20 body. Runs each carry frame. */
private void updateCarrying() {
    // ROM order inside Tails_Carry_Sonic:

    // 1. Hurt/dead (Sonic routine >= 4)
    if (leader.isHurt() || leader.getDead()) {
        releaseCarry(carryTrigger.carryLatchReleaseCooldownFrames());
        return;
    }

    // 2. External velocity change (release path C: latch mismatch)
    if (leader.getXSpeed() != carryLatchX || leader.getYSpeed() != carryLatchY) {
        releaseCarry(carryTrigger.carryLatchReleaseCooldownFrames());
        return;
    }

    // 3. A/B/C just-pressed (release path B)
    if (leader.isJumpJustPressed()) {
        performJumpRelease();
        return;
    }

    // 4. Ground release (release path A): Sonic in-air bit clear
    if (!leader.getAir()) {
        releaseCarry(0);
        return;
    }

    // --- No release this frame; re-apply parentage ---

    // Synthetic right-press injection every 32 frames (ROM: Level_frame_counter cadence)
    if ((frameCounter & carryTrigger.carryInputInjectMask()) == 0) {
        inputRight = true;
    }

    // Clamp Tails's x_vel to the carry velocity
    sidekick.setXSpeed(carryTrigger.carryInitXVel());

    // Sonic parentage (Tails_Carry_Sonic steps 5 + 8):
    //   x_pos = Tails.x_pos
    //   y_pos = Tails.y_pos + carryDescendOffsetY()
    //   x_vel = Tails.x_vel
    //   y_vel = Tails.y_vel
    leader.setCentreXPreserveSubpixel((short) sidekick.getCentreX());
    leader.setCentreYPreserveSubpixel(
            (short) (sidekick.getCentreY() + carryTrigger.carryDescendOffsetY()));
    leader.setXSpeed(sidekick.getXSpeed());
    leader.setYSpeed(sidekick.getYSpeed());

    // Refresh the latch AFTER our writes so the next frame's compare is
    // against what we just wrote, not stale values.
    carryLatchX = leader.getXSpeed();
    carryLatchY = leader.getYSpeed();
}

private void performJumpRelease() {
    short xMag = carryTrigger.carryReleaseJumpXVel();
    short xVel = leader.getDirection() == com.openggf.physics.Direction.LEFT
            ? (short) -xMag
            : xMag;
    leader.setXSpeed(xVel);
    leader.setYSpeed(carryTrigger.carryReleaseJumpYVel());
    leader.setRollingJump(true);
    leader.setForcedAnimationId(leader.resolveAnimationId(CanonicalAnimation.JUMP));
    releaseCarry(carryTrigger.carryJumpReleaseCooldownFrames());
}

private void releaseCarry(int cooldownFrames) {
    leader.setObjectControlled(false);
    leader.setForcedAnimationId(-1);
    sidekick.setControlLocked(false);
    sidekick.setForcedAnimationId(-1);
    flyingCarryingFlag = false;
    releaseCooldown = cooldownFrames;
    state = State.NORMAL;
    normalFrameCount = 0;
}
```

- [ ] **Step 5: Wire state dispatch in `update(int)`**

Locate the top of `update(int frameCount)` (search for `public void update`) and add:

```java
public void update(int frameCount) {
    this.frameCounter = frameCount;
    // Decrement release cooldown every frame regardless of state.
    if (releaseCooldown > 0) {
        releaseCooldown--;
    }
    clearInputs();
    switch (state) {
        case INIT        -> updateInit();
        case SPAWNING    -> updateSpawning();
        case APPROACHING -> updateApproaching();
        case NORMAL      -> updateNormal();
        case PANIC       -> updatePanic();
        case CARRY_INIT  -> updateCarryInit();
        case CARRYING    -> updateCarrying();
    }
}
```

Preserve the existing `update()` body; only insert the cooldown tick and add the two new switch cases. If the existing switch has a default that throws, add the two new cases before any such default. If the existing update uses an if-else chain instead of a switch, add two additional branches matching the same style.

`CanonicalAnimation.TAILS_CARRIED` was already added in Task 2, so `leader.resolveAnimationId(CanonicalAnimation.TAILS_CARRIED)` above compiles without any further enum changes. If `resolveAnimationId` has no mapping for the new constant yet it simply returns `-1`, and `setForcedAnimationId(-1)` is a no-op — physics parity is unaffected, visual parity is deferred (see spec §9.2).

- [ ] **Step 6: Run tests to verify pass**

Run: `mvn test -Dtest=TestSidekickCpuControllerCarry`
Expected: all 10 tests (3 hydration + 7 behavioural) PASS.

- [ ] **Step 7: Commit**

Only `SidekickCpuController.java` and its test class are staged here — `CanonicalAnimation.java` was already committed in Task 2. The commit message stays under the per-task rollup discipline (Changelog entry appears once, in Task 9).

```bash
git add src/main/java/com/openggf/sprites/playable/SidekickCpuController.java \
        src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerCarry.java
git commit -m "$(cat <<'EOF'
feat(sidekick): implement Tails-carry state machine (CARRY_INIT, CARRYING)

Implements updateCarryInit (ROM 0x0C) and updateCarrying (ROM 0x0E/0x20
body) inside SidekickCpuController with all three release paths matching
ROM priority: hurt/dead, external-vel latch mismatch, A/B/C press, and
ground contact. Cooldowns (0x12 jump, 0x3C latch, 0 ground) tracked in a
new releaseCooldown field that ticks down every frame. No S1/S2 side-
effects: the trigger is null for those games and updateInit's carry
branch short-circuits.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Wire the trigger into `Sonic3kGameModule` and `GameplayTeamBootstrap`

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayTeamBootstrap.java`

**Rationale (engine-discovery notes):**
- `Sonic3k.loadLevel(int levelIdx)` returns a `Level` and does not touch sidekick sprites — those are owned by `SpriteManager` and wired up before level load by `GameplayTeamBootstrap`. Patching `loadLevel` would be the wrong seam.
- The only place in the engine that actually constructs the sidekick's `SidekickCpuController` and calls `sidekick.setCpuController(controller)` is `GameplayTeamBootstrap.registerActiveTeam` (lines 74-86 on this branch). That method already has the active `GameModule module` in scope, so a single additional call — `controller.setCarryTrigger(module.getSidekickCarryTrigger())` — threads the game-agnostic contract through cleanly.
- `GameModule.onLevelLoad()` takes no args on this branch (`GameModule.java:286`); there is no `onLevelLoad(int, int)` overload. Do not try to add one — the team-bootstrap hook above is the correct injection point.

- [ ] **Step 1: Override `getSidekickCarryTrigger()` in `Sonic3kGameModule`**

Open `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`.

**1a. Add the import** (near the other `com.openggf.game.sonic3k.*` imports around line 33-38):

```java
import com.openggf.game.sonic3k.sidekick.Sonic3kCnzCarryTrigger;
import com.openggf.sprites.playable.SidekickCarryTrigger;
```

**1b. Add the singleton field** alongside the other private final fields (around line 83, just after `private final LevelInitProfile levelInitProfile = new Sonic3kLevelInitProfile(levelEventManager);`):

```java
private final SidekickCarryTrigger sidekickCarryTrigger = new Sonic3kCnzCarryTrigger();
```

**1c. Add the override method.** Place it immediately after `createSuperStateController(...)` (ends around line 277), keeping the game-module overrides grouped together. Use this exact body:

```java
@Override
public SidekickCarryTrigger getSidekickCarryTrigger() {
    return sidekickCarryTrigger;
}
```

- [ ] **Step 2: Install the trigger in `GameplayTeamBootstrap`**

Open `src/main/java/com/openggf/game/session/GameplayTeamBootstrap.java`. In `registerActiveTeam(...)` locate the per-sidekick wiring block (lines 74-86 on this branch, between `sidekick.setCpuControlled(true);` and `spriteManager.addSprite(sidekick, characterName);`). The existing block ends with:

```java
                sidekick.setCpuController(controller);

                spriteManager.addSprite(sidekick, characterName);
```

Insert the carry-trigger install between those two lines so the final block reads:

```java
                sidekick.setCpuController(controller);

                // Wire any game-specific sidekick carry trigger (S3K CNZ1 Tails carry).
                // S1/S2 modules return null from getSidekickCarryTrigger(), so this is
                // a no-op branch for those games and for S3K zones outside CNZ1.
                controller.setCarryTrigger(module.getSidekickCarryTrigger());

                spriteManager.addSprite(sidekick, characterName);
```

Do not reorder, rename, or otherwise edit the surrounding lines. The 3-line insertion (blank line, comment block, single call) is the whole change.

- [ ] **Step 3: Level-select audit (spec §9.6)**

Open `src/main/java/com/openggf/game/sonic3k/levelselect/Sonic3kLevelSelectManager.java` and confirm that the level-select entry path lands on either (a) `Engine.launchGameplayFromDataSelect()` / equivalent, which still routes through `GameplayTeamBootstrap.registerActiveTeam`, or (b) a direct `LevelManager.loadZoneAndAct(...)` call that runs *after* team registration. In either case the trigger installation from Step 2 runs before the first sprite-update frame, so entering CNZ1 via the level select still fires the carry intro correctly.

If the audit shows a level-select path that bypasses team bootstrap, document it as a follow-up and surface it to the subagent-driven-development controller — do **not** silently add a second trigger install site. The spec expects a single install seam.

Expected outcome: no code changes in this step. Record the audit result in the commit message body under a short "Level-select audit:" paragraph.

- [ ] **Step 4: Compile + run wider-guard tests**

Run: `mvn test -Dtest="TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestSidekickCpuControllerCarry,TestSonic3kCnzCarryTrigger,TestObjectControlledGravity" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`
Expected: all PASS. This is the `CLAUDE.md` wider-guard floor plus the new carry tests.

`TestS3kAizTraceReplay` is permitted to remain in its pre-existing red state per `docs/architecture/audits/s3k-zones/cnz-task7-regression.md` — do not chase it.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java \
        src/main/java/com/openggf/game/session/GameplayTeamBootstrap.java
git commit -m "$(cat <<'EOF'
feat(s3k): wire Sonic3kCnzCarryTrigger into game module and team bootstrap

Sonic3kGameModule now supplies a singleton Sonic3kCnzCarryTrigger via the
new GameModule.getSidekickCarryTrigger() contract. GameplayTeamBootstrap
installs whatever trigger the active module returns onto the sidekick's
SidekickCpuController immediately after creation, so the first INIT tick
of CNZ1 (zone 3 act 0, Sonic+Tails) transitions directly into the carry
state machine. S1/S2 modules keep the default null trigger, so this is a
no-op for those games and for S3K zones outside CNZ1.

Level-select audit: Sonic3kLevelSelectManager routes through the normal
engine gameplay-launch path, so team bootstrap (and therefore the carry-
trigger install) still runs before the first frame.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Headless integration test — `TestS3kCnzCarryHeadless`

**Files:**
- Create: `src/test/java/com/openggf/tests/TestS3kCnzCarryHeadless.java`

**Fixture notes (engine-discovery):**
- The real fixture lives at `com.openggf.tests.HeadlessTestFixture` (**not** `com.openggf.tests.util.*`). Its builder methods are `withSharedLevel(SharedLevel)` / `withZoneAndAct(int, int)` / `startPosition(short, short)` / `withRecording(Path)` / `withRecordingStartFrame(int)`. There is no `withMainCharacter`, no `withSidekickCharacter`, no `MAIN_CHARACTER_CODE_*` constant.
- `fx.sprite()` returns the main player sprite (no `getPlayerSprite()` method).
- `fx.stepFrame(up, down, left, right, jump)` takes **five booleans** (no zero-arg overload). For idle frames use `fx.stepIdleFrames(count)`.
- The sidekick controller is looked up via `GameServices.sprites().getSidekicks().get(0).getCpuController()` — the fixture does not expose a convenience accessor.
- Use the `@RequiresRom(SonicGame.SONIC_3K)` JUnit 5 extension so the test is automatically skipped when the ROM is absent, matching `TestS3kAiz1SkipHeadless`.
- The Knuckles negative predicate is already covered by `TestSonic3kCnzCarryTrigger` unit tests in Task 4 (`shouldEnterCarry(3, 0, PlayerCharacter.KNUCKLES) == false`), so Task 8 focuses on the three integration assertions for the Sonic+Tails happy path.

- [ ] **Step 1: Write the integration test**

```java
package com.openggf.tests;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end headless check of the CNZ1 Tails-carry intro.
 *
 * <p>Mirrors the first ~200 frames of the {@code TestS3kCnzTraceReplay}
 * BK2 without running the full trace engine. Success criteria (per design
 * spec §10 row 1, 3 and §8.2):
 * <ul>
 *   <li>Frame 1: {@code Sonic.x_speed == 0x0100} and object-controlled/airborne</li>
 *   <li>Frame 43: {@code Sonic.air == 1} (still carried, matches trace row #3)</li>
 *   <li>By frame ~200: state back to {@code NORMAL}, {@code object_control} cleared</li>
 * </ul>
 *
 * <p>Knuckles-alone coverage lives in {@code TestSonic3kCnzCarryTrigger}
 * (Task 4 unit tests); this class asserts only the Sonic+Tails path.
 */
@RequiresRom(SonicGame.SONIC_3K)
class TestS3kCnzCarryHeadless {

    private static final int ZONE_CNZ = 3;
    private static final int ACT_1 = 0;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        // Intro skip keeps the first frame on CNZ1 gameplay (skips zone-intro
        // title-card frames), so frame 1 is the carry intro's first tick.
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_CNZ, ACT_1);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
        if (oldSkipIntros != null) {
            SonicConfigurationService.getInstance()
                    .setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros);
            oldSkipIntros = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
    }

    private SidekickCpuController sidekickController() {
        return GameServices.sprites().getSidekicks().get(0).getCpuController();
    }

    @Test
    void cnz1Frame1SonicXSpeedMatchesRom() {
        AbstractPlayableSprite sonic = fixture.sprite();

        fixture.stepFrame(false, false, false, false, false);

        assertEquals((short) 0x0100, sonic.getXSpeed(),
                "Frame 1 Sonic.x_speed must match ROM carry velocity");
        assertEquals((short) 0, sonic.getYSpeed(),
                "Frame 1 Sonic.y_speed (carry init)");
        assertTrue(sonic.isObjectControlled(),
                "Frame 1: Sonic object-controlled by Tails");
        assertTrue(sonic.getAir(),
                "Frame 1: Sonic airborne (being carried)");
    }

    @Test
    void cnz1Frame43SonicStillCarried() {
        AbstractPlayableSprite sonic = fixture.sprite();

        for (int i = 0; i < 43; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        assertTrue(sonic.getAir(),
                "Frame 43: Sonic must still be airborne (trace row #3)");
        assertTrue(sonic.isObjectControlled(),
                "Frame 43: Sonic still object-controlled (carry has not released)");
    }

    @Test
    void cnz1CarryReleasesByFrame200() {
        AbstractPlayableSprite sonic = fixture.sprite();
        SidekickCpuController ctrl = sidekickController();

        int releasedAtFrame = -1;
        for (int i = 0; i < 200; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (ctrl.getState() == SidekickCpuController.State.NORMAL) {
                releasedAtFrame = i;
                break;
            }
        }

        assertTrue(releasedAtFrame > 0 && releasedAtFrame < 200,
                "Carry must release within the first 200 frames; got " + releasedAtFrame);
        assertFalse(sonic.isObjectControlled(),
                "After release Sonic is no longer object-controlled");
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=TestS3kCnzCarryHeadless "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`
Expected: all 3 tests PASS.

If `cnz1CarryReleasesByFrame200` fails by a large margin (release happens too early or never), diagnose:
- Too-early release → likely a latch-mismatch release triggered by some other engine write to Sonic's velocity. Re-audit §7.5 gates; add a temporary diagnostic print in `updateCarrying` that logs the latch-vs-actual delta, identify the offender, decide whether to gate it or accept the early release as ROM-divergent, then remove the diagnostic.
- Never releases → ground-contact detection broken; check that `SonicKnux_DoLevelCollision`-equivalent terrain probes still run on Sonic during carry (must NOT be gated on `object_control`).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/tests/TestS3kCnzCarryHeadless.java
git commit -m "$(cat <<'EOF'
test(s3k): headless integration test for CNZ1 Tails-carry intro

Exercises the full SidekickCpuController carry path through the shared-
level HeadlessTestFixture bootstrapping into CNZ1. Asserts frame 1
x_speed matches ROM (0x0100), frame 43 still airborne (matches trace
baseline row #3), and release-by-frame-200. Knuckles negative coverage
stays in TestSonic3kCnzCarryTrigger (Task 4) so this class focuses on
the Sonic+Tails integration path.

Changelog: n/a
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Run `TestS3kCnzTraceReplay`, capture new baseline, roll up CHANGELOG

**Files:**
- Create: `docs/architecture/validation/s3k-zones/cnz-post-workstream-c.md`
- Modify: `CHANGELOG.md`

**Note on `S3K_KNOWN_DISCREPANCIES.md`:** the Tails-carry intro is a parity *fix*, not an intentional divergence, so `docs/S3K_KNOWN_DISCREPANCIES.md` is **not** updated. The commit trailer for that file stays `n/a`. If the audit in Task 7 Step 3 (or later diagnostic work) reveals a corner we intentionally diverge from ROM — for example accepting an early release instead of matching the exact ROM path — add a new section to that file then and flip the trailer at that point; otherwise do not touch it.

- [ ] **Step 1: Run the full trace test**

Run: `mvn test -Dtest=TestS3kCnzTraceReplay "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`
Expected: test still fails (this workstream does not aim to drive it to zero), but:
- First-divergence frame shifts from 1 to > 400
- Total error count drops from 1635 to < 1000 (target: < 800)

- [ ] **Step 2: Inspect the divergence report**

Run: `mvn test -Dtest=TestS3kCnzTraceReplay -Dtest.s3k.cnz.report=target/s3k-cnz-replay/ "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"` (the exact flag mirrors existing trace tests — consult `AbstractTraceReplayTest` for the current report-path system property).

Open the generated JSON / context under `target/s3k-cnz-replay/`. Record the new first-20 divergences in a hand-off note.

Create `docs/architecture/validation/s3k-zones/cnz-post-workstream-c.md` with this exact template (fill the `<...>` placeholders with measured values):

```markdown
# CNZ Trace Replay - Post-Workstream-C Baseline

Captured: <YYYY-MM-DD>
Test: `TestS3kCnzTraceReplay`
After: workstream C (Tails-carry intro) merge at <commit SHA>

## Summary

- Previous baseline: 1635 errors, first divergence frame 1
- Post-C baseline: <NNN> errors, first divergence frame <NNN>
- Improvement: <delta> errors, first-frame shift <delta>

## First 20 divergences (post-C)

| # | Frame (start) | Field | Expected | Actual | Likely workstream |
|---|---------------|-------|----------|--------|-------------------|

(fill in from new report)

## Next dispatch

Based on the new first-20 distribution, workstreams applicable:
- [ ] D (CNZ1 mini-boss): <dispatch or defer, based on evidence>
- [ ] E (CNZ2 end-boss): <dispatch or defer>
- [ ] F (Knuckles cutscenes): <dispatch or defer>
- [ ] G (Stragglers): <dispatch or defer>

## AIZ replay regression

`TestS3kAizTraceReplay` continues to fail on the pre-existing
`aiz1_fire_transition_begin` checkpoint issue documented in
`docs/architecture/audits/s3k-zones/cnz-task7-regression.md`. Workstream H owns that
recovery and does not block this baseline.
```

- [ ] **Step 3: Re-run wider guard one last time**

Run: `mvn test -Dtest="TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestS3kCnzCarryHeadless,TestSidekickCpuControllerCarry,TestSonic3kCnzCarryTrigger,TestObjectControlledGravity,TestPhysicsProfile,TestPhysicsProfileRegression,TestSpindashGating,TestCollisionModel" "-Ds3k.rom.path=Sonic and Knuckles & Sonic 3 (W) [!].gen"`
Expected: all PASS. `TestS3kAizTraceReplay` is permitted to remain in its pre-existing red state (per `docs/architecture/audits/s3k-zones/cnz-task7-regression.md`).

- [ ] **Step 4: Roll up the CHANGELOG entry**

Open `CHANGELOG.md`. The file currently opens with `## Unreleased` followed by game-facing sub-sections (`### Performance, Parity, and Trace Replay`, `### Experimental Level Editor Overlay`, etc.). Add a **new sub-section** under `## Unreleased` — inserted above or below the existing sub-sections, whichever keeps the grouping consistent — using this exact heading and bullet structure. Fill in `<NNN>` / `<delta>` with the values measured in Step 1.

```markdown
### Sonic 3&K CNZ1 Tails-Carry Intro (Workstream C)

- Implemented the CNZ Act 1 Tails-carry intro (`Tails_CPU_routine` 0x0C /
  0x0E / 0x20 in the S3K disassembly). The sidekick CPU driver now enters
  `CARRY_INIT` on the first gameplay tick of CNZ1 when the active team is
  Sonic + Tails, copies Tails's velocity onto Sonic each frame, and
  releases through any of the three ROM-accurate paths: ground contact,
  jump press (with the 0x12-frame cooldown), or external-velocity latch
  mismatch (with the 0x3C-frame cooldown).
- Added the `SidekickCarryTrigger` game-agnostic hook
  (`GameModule.getSidekickCarryTrigger()`), the S3K-specific
  `Sonic3kCnzCarryTrigger`, and new `SidekickCpuController` states
  `CARRY_INIT` / `CARRYING` plus `CanonicalAnimation.TAILS_CARRIED`.
  `PlayableSpriteMovement.applyGravity` now short-circuits when the sprite
  is object-controlled so the carried cargo no longer accumulates free-fall.
- Reduced `TestS3kCnzTraceReplay`'s first-divergence frame from 1 to
  <NNN> and total errors from 1635 to <NNN> (delta: <delta>). The
  `TestS3kAizTraceReplay` pre-existing regression is orthogonal and stays
  owned by workstream H (see
  `docs/architecture/audits/s3k-zones/cnz-task7-regression.md`).
```

- [ ] **Step 5: Commit the baseline + CHANGELOG rollup**

```bash
git add docs/architecture/validation/s3k-zones/cnz-post-workstream-c.md CHANGELOG.md
git commit -m "$(cat <<'EOF'
docs(s3k): capture post-workstream-C CNZ baseline and changelog

Re-captures the TestS3kCnzTraceReplay divergence summary after the Tails-
carry intro workstream. First-divergence frame shifted from 1 to <NNN>;
total errors reduced from 1635 to <NNN>. Adds a CNZ1 Tails-carry entry to
the Unreleased section of CHANGELOG.md, covering the new
SidekickCarryTrigger contract, the Sonic3kCnzCarryTrigger implementation,
the CARRY_INIT/CARRYING controller states, the TAILS_CARRIED canonical
animation, the object-controlled gravity gate, and the replay-delta
measurements that drive workstream D/E/F/G scope decisions.

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

---

## Success Criteria (plan-level)

All tasks 1-9 complete with:

- [x] Task 1: `applyGravity()` helper gated on `isObjectControlled()`; `TestObjectControlledGravity` green
- [x] Task 2: `Sonic3kConstants` literals present with ROM-line citations
- [x] Task 3: `SidekickCarryTrigger` interface + `GameModule.getSidekickCarryTrigger()` default
- [x] Task 4: `Sonic3kCnzCarryTrigger` with 9 green tests (zone/act/mode predicate + ROM constant mirrors)
- [x] Task 5: `CARRY_INIT`/`CARRYING` states + hydration accepts 0x0C/0x0E/0x20
- [x] Task 6: 10 green `TestSidekickCpuControllerCarry` tests covering init, per-frame parentage, all three release paths, cooldown, and input injection
- [x] Task 7: Trigger wired into `Sonic3kGameModule` and `GameplayTeamBootstrap`; level-select bypass audit recorded; wider guard still green
- [x] Task 8: 3 green `TestS3kCnzCarryHeadless` tests (frame-1 `x_speed`, frame-43 still carried, release-by-frame-200). Knuckles negative coverage owned by `TestSonic3kCnzCarryTrigger` (Task 4).
- [x] Task 9: `TestS3kCnzTraceReplay` first-divergence frame > 400, errors < 1000, post-C baseline + CHANGELOG rollup captured

Wider guard (`CLAUDE.md` §Keep these S3K tests green + new carry tests) all-green.
`TestS3kAizTraceReplay` permitted to remain in its pre-existing red state (workstream H owns that).

---

## Self-Review Checklist

- [x] Every spec section (§5.*, §6.*, §7.*, §8.*, §9.*) is covered by at least one task or explicitly deferred. §9.2 (animation-id mapping) is deferred per spec (visual-only, not physics-blocking). §9.6 (level-select bypass audit) is covered by Task 7 Step 3.
- [x] No `TODO`, no `TBD`, no "implement later", no "similar to Task N" shortcuts.
- [x] All code samples are complete: no `...` where real code must land. The placeholder `// ... existing INIT body ...` that appeared in plan v1 Task 6 Step 3 has been replaced with the verbatim 12-line body from `SidekickCpuController.java:107-120`.
- [x] Type names are consistent across tasks: `SidekickCarryTrigger`, `Sonic3kCnzCarryTrigger`, `SidekickCpuController`, `State.CARRY_INIT`, `State.CARRYING`, `CanonicalAnimation.TAILS_CARRIED`, `PlayerCharacter.SONIC_AND_TAILS`. Method names: `shouldEnterCarry`, `applyInitialPlacement`, `carryInitXVel`, `carryDescendOffsetY`, `carryInputInjectMask`, `carryJumpReleaseCooldownFrames`, `carryLatchReleaseCooldownFrames`, `carryReleaseJumpYVel`, `carryReleaseJumpXVel`, `setCarryTrigger`, `releaseCarry`, `performJumpRelease`, `updateCarryInit`, `updateCarrying`.
- [x] Each task ends with a policy-compliant commit (7 trailers + `Co-Authored-By` inside the block). All intermediate tasks use `Changelog: n/a` / `Known-Discrepancies: n/a` / `S3K-Known-Discrepancies: n/a` / `Agent-Docs: n/a` / `Configuration-Docs: n/a` / `Skills: n/a`; only Task 9's final commit flips `Changelog: updated` when `CHANGELOG.md` is staged. The bidirectional policy (`updated` ⇔ file staged) is honoured throughout.
- [x] The commit-message bodies tell the "why" per `CLAUDE.md` guidance.
- [x] All test files use real APIs: `com.openggf.tests.HeadlessTestFixture` (not `...util.HeadlessTestFixture`); `fx.sprite()` (not `getPlayerSprite()`); `fx.stepFrame(up, down, left, right, jump)` (five booleans, no zero-arg overload); `GameServices.sprites().getSidekicks().get(0).getCpuController()` for the sidekick controller (no fixture accessor); `@RequiresRom(SonicGame.SONIC_3K)` + `SharedLevel.load(...)` for S3K headless tests.
- [x] All wiring uses real engine seams: `GameplayTeamBootstrap.registerActiveTeam` for the carry-trigger install (not the non-existent `LevelManager.getInstance()` or the non-existent `GameModule.onLevelLoad(int, int)` overload); `sidekick.currentLevelManager()` for the canonical level-manager lookup inside `SidekickCpuController.updateInit()`.
