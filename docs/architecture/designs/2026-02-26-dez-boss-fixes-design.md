# DEZ Boss Fixes Design

**Date:** 2026-02-26
**Scope:** 9 bugs across Silver Sonic (ObjAF), Robotnik transition (ObjC6), and Death Egg Robot (ObjC7)

## Group A: Silver Sonic (Mecha Sonic) Fixes

### Bug 1: Facing wrong way after direction flip

**Root cause:** `startDash()` sets `facingLeft = (xVel < 0)`, but the ROM (`loc_39D60`) never changes `render_flags.x_flip` at dash start. The ROM only toggles facing at the end of a dash via `bchg` at `loc_39A7C`. The Java double-flips: once at start (velocity-based) and once at end (toggle).

**Fix:** Remove `facingLeft = (xVel < 0)` from `startDash()`. Facing is managed exclusively by the existing toggles at each attack's end-of-dash point.

**File:** `Sonic2MechaSonicInstance.java` line 711

### Bug 2: Ball form shows sparks/booster

**Root cause:** `MechaSonicLEDWindow` renders overlay frames from the Silver Sonic art sheet at the parent's position during all states. Frames 0x09/0x0A appear as spark/thruster graphics overlaid on the ball.

**Fix:** In `MechaSonicLEDWindow.appendRenderCommands()`, skip rendering when parent is in ball form.

**File:** `Sonic2MechaSonicInstance.java` (MechaSonicLEDWindow inner class)

### Bug 3: Robotnik not visible in window / blinds don't close

**Analysis:** Animation logic looks structurally correct. Needs visual verification after other fixes. Potential issues: window position, `beginUpdate()` gating, or art loading.

**File:** `Sonic2MechaSonicInstance.java` (MechaSonicDEZWindow inner class)

## Group B: Robotnik Transition

### Bug 4: Robotnik doesn't spawn after Silver Sonic defeat

**Root cause:** No ObjC6 implementation exists. The ROM spawns Robotnik (ObjC6) after Silver Sonic's defeat to run from the window area to the Egg Robo cockpit.

**Fix:** Create `Sonic2DEZEggmanInstance` implementing the ROM's ObjC6 escape sequence:
1. Spawn at (0x3F8, 0x160) from Silver Sonic's defeat handler
2. Wait for player approach (angle check)
3. 0x18-frame pause, then run right at x_vel=0x200 with animation
4. At X >= 0x810, jump (y_vel=-0x200, x_vel=0x80) and despawn after 0x50 frames
5. Signal HeadChild that Eggman has boarded (replace timer-based fake boarding)

**Art:** Uses `ArtNem_RobotnikRunning` (tile 0x0518, shared WFZ art). Mappings from `objC6_a.asm`.

**Files:** New `Sonic2DEZEggmanInstance.java`, edits to `Sonic2MechaSonicInstance.java` defeat handler, `Sonic2DEZEvents.java`

## Group C: Death Egg Robot Fixes

### Bug 5: Head animation stuck in loop

**Root cause:** `HeadChild.stepGlow()` loops HEAD_GLOW_FRAMES endlessly. Boarding animation should play once and pause on last frame.

**Fix:** Add `playedOnce` flag. On first completion, hold on final frame (frame 2) instead of looping.

**File:** `Sonic2DeathEggRobotInstance.java` (HeadChild inner class)

### Bug 6: Arm/leg priorities need validation

**Current:** Front parts priority 4, back parts priority 5, body priority 5.

**Fix:** Cross-reference ROM's `ChildObjC7_*` spawn data (s2.asm:82053-82071) and correct any mismatches. Key constraint: back limbs behind body, front limbs in front, head in front of all.

**File:** `Sonic2DeathEggRobotInstance.java` (spawnChildren)

### Bug 7: Forearm arms go backwards when fired

**Root cause:** `facingLeft` starts as `false` (facing right) in `initializeBossState()`. ROM's Egg Robo faces left toward the player. Punch velocity uses `facingLeft` to determine direction, so punches go right (away from player).

**Fix:** Set correct initial facing (`facingLeft = true`). Verify punch direction matches ROM `loc_3D6AA`.

**File:** `Sonic2DeathEggRobotInstance.java`

### Bug 8: Sonic doesn't bounce off on hit

**Root cause:** `HeadChild.onPlayerAttack()` handles boss HP but never applies bounce physics to the player. ROM's `Touch_Enemy_Part2` reverses Sonic's Y velocity.

**Fix:** In `onPlayerAttack()`, after `parent.onHeadHit()`, apply `player.setYVelocity(-player.getYVelocity())` (standard ROM boss bounce). Without this, Sonic falls into body collision (0x16 = hurts player).

**File:** `Sonic2DeathEggRobotInstance.java` (HeadChild inner class)

### Bug 9: Defeat animation never plays

**Root cause:** Without bounce (bug 8), Sonic gets hurt by body after each head hit, making 12 hits nearly impossible. Also need to verify defeat fall/bounce loop executes correctly.

**Fix:** Fixing bug 8 should resolve this. Verify `triggerDefeatSequence()` → `updateDefeatFall()` → `updateDefeatExplode()` chain works. Add logging if defeat still doesn't trigger.

**File:** `Sonic2DeathEggRobotInstance.java`

## Implementation Order

1. Bug 1 (facing) — trivial, 1 line
2. Bug 2 (ball sparks) — small, render guard
3. Bug 7 (arm direction) — small, initial facing fix
4. Bug 8 (bounce) — small, add player bounce
5. Bug 5 (head loop) — small, play-once flag
6. Bug 6 (priorities) — ROM cross-reference needed
7. Bug 9 (defeat) — verify after 7+8
8. Bug 3 (window) — visual verification after other fixes
9. Bug 4 (Robotnik escape) — largest change, new class
