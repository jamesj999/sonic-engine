# CPZ Boss Implementation Plan

This document details the Chemical Plant Zone Boss (Object 0x5D) behavior from the Sonic 2 disassembly and outlines fixes needed for accurate implementation.

## Executive Summary

**Critical Bug Found**: The container never accelerates because `updateContainerMovement()` returns early when `xVel == CONTAINER_INIT_XVEL` (-0x10), preventing the attack cycle from completing. This causes the boss to hang indefinitely in the MAIN_FOLLOW_PLAYER state.

---

## ROM Behavior Analysis

### State Machine Overview

The CPZ Boss follows this attack cycle:

```
1. MAIN_DESCEND (0x00)
   └─ Descend at yVel=0x0100 until Y reaches 0x04C0
   └─ Transition to MAIN_MOVE_TOWARD_TARGET

2. MAIN_MOVE_TOWARD_TARGET (0x02)
   └─ Move toward target position (alternates left/right)
   └─ Left target: 0x2A50, Right target: 0x2B30
   └─ When within 3 pixels AND at target Y:
      └─ Toggle STATUS_SIDE (bit 3)
      └─ Set STATUS2_ACTION0 (bit 0)
      └─ Transition to MAIN_WAIT

3. MAIN_WAIT (0x04)
   └─ Wait for ACTION0 to be cleared by container extend
   └─ Transition to MAIN_FOLLOW_PLAYER

4. MAIN_FOLLOW_PLAYER (0x06)
   └─ Chase player at ±1 pixel/frame
   └─ Target = player.X + 76 pixels
   └─ Clamped to arena [0x2A28, 0x2B70]
   └─ Runs until gunk lands or falls off-screen

5. When gunk completes:
   └─ Set STATUS2_ACTION2, STATUS2_ACTION4
   └─ Set routineSecondary = MAIN_MOVE_TOWARD_TARGET
   └─ Container decelerates back to xVel = -0x10
   └─ When reset position reached, spawn new pipe
   └─ Cycle repeats from step 2
```

### Attack Subsystem Sequence

```
Timeline (frames approximate):

Frame 0:    Boss reaches target, sets ACTION0
Frame 1-12: Pipe extends (one segment per frame, 12 segments)
Frame 13:   Pipe becomes PIPE_PUMP, spawns dripper
Frame 13-32: Dripper cycles (19 frames per cycle)
Frame 32:   Dripper sets ACTION1 on mainBoss
Frame 33-44: Container extend animates (anim 0x0B → 0x17)
Frame 44:   Container extend clears ACTION0, sets ACTION2
Frame 44:   MAIN_WAIT transitions to MAIN_FOLLOW_PLAYER
Frame 45+:  Container accelerates (xVel: -0x10 → -0x58)
Frame ~117: Container reaches max speed (-0x58)
           Drop trigger checks player position
           When player in range: dump liquid
Frame X:   Gunk spawns, falls, lands/expires
           Boss returns to MAIN_MOVE_TOWARD_TARGET
```

---

## Identified Bugs

### Bug 1: Container Movement Early Return (CRITICAL)

**File**: `Sonic2CPZBossInstance.java`
**Method**: `updateContainerMovement()` (lines 1045-1067)

**Problem**: The method returns early when `xVel == CONTAINER_INIT_XVEL` even when not performing a reset.

**Current Code** (incorrect):
```java
private void updateContainerMovement() {
    int direction = (parent.status2 & STATUS2_ACTION4) != 0 ? 1 : -1;
    if (xVel == CONTAINER_INIT_XVEL) {
        if ((parent.status2 & STATUS2_ACTION4) != 0) {
            // Reset cycle
            parent.status2 &= ~STATUS2_ACTION4;
            parent.status2 &= ~STATUS2_ACTION2;
            routineSecondary = 0;
            spawnPipe();
        }
        return;  // BUG: Always returns when xVel == -0x10!
    }
    // Acceleration code never reached...
}
```

**ROM Code** (s2.asm lines 62133-62183):
```asm
loc_2E4CE:
    moveq   #1,d0
    btst    #4,Obj5D_status2(a1)    ; Check ACTION4
    bne.s   +
    moveq   #-1,d0
+
    cmpi.w  #-$10,Obj5D_x_vel(a0)
    bne.s   loc_2E552               ; If xVel != -$10, branch to acceleration
    bclr    #4,Obj5D_status2(a1)
    beq.s   loc_2E552               ; If ACTION4 wasn't set, ALSO branch to acceleration
    ; ... reset code only if xVel == -$10 AND ACTION4 was set ...
```

**Fix**:
```java
private void updateContainerMovement() {
    int direction = (parent.status2 & STATUS2_ACTION4) != 0 ? 1 : -1;

    // Only reset if xVel is at initial position AND ACTION4 is set
    if (xVel == CONTAINER_INIT_XVEL && (parent.status2 & STATUS2_ACTION4) != 0) {
        // Reset cycle
        parent.status2 &= ~STATUS2_ACTION4;
        parent.status2 &= ~STATUS2_ACTION2;
        routineSecondary = 0;
        spawnPipe();
        return;
    }

    // Acceleration code - always reached unless reset happened
    if (xVel >= -0x28) {
        anim = 6;
    } else if (xVel >= -0x40) {
        anim = 7;
    } else {
        anim = 8;
    }

    // Cap speed at -0x58
    if (xVel <= -0x58) {
        // Note: ROM flips sign based on direction, but we handle direction below
        return;  // Don't accelerate past cap
    }

    xVel += direction;
}
```

**Impact**: This bug prevents the entire attack cycle from functioning. Without acceleration, the container never moves fast enough to trigger the player-position-based drop, and the gunk never spawns.

---

### Bug 2: Speed Cap Logic

**File**: `Sonic2CPZBossInstance.java`
**Method**: `updateContainerMovement()` (line 1063-1065)

**Problem**: The speed cap logic tries to flip the sign, but this doesn't match ROM behavior.

**Current Code**:
```java
if (xVel <= -0x58) {
    xVel = (renderFlags & 1) == 0 ? -0x58 : 0x58;
}
xVel += direction;
```

**ROM Code** (s2.asm lines 62176-62183):
```asm
    cmpi.w  #-$58,d1
    blt.s   loc_2E57E       ; If xVel < -$58, branch (cap already exceeded)
    bgt.s   loc_2E578       ; If xVel > -$58, branch (accelerate)
    ; xVel == -$58 exactly
    btst    #4,Obj5D_status2(a1)
    beq.s   return_2E57C    ; If ACTION4 not set, don't change
loc_2E578:
    add.w   d0,Obj5D_x_vel(a0)  ; Accelerate
```

**Fix**: The speed cap should prevent further acceleration, not flip the sign:
```java
// Cap speed - don't accelerate past -0x58
if (xVel <= -0x58) {
    if ((parent.status2 & STATUS2_ACTION4) == 0) {
        return;  // At cap and not reversing, do nothing
    }
    // ACTION4 is set, decelerate (direction = 1)
}
xVel += direction;
```

---

### Bug 3: Drop Trigger Check Order

**File**: `Sonic2CPZBossInstance.java`
**Method**: `updateContainerDropTrigger()` (lines 1069-1106)

**Problem**: The current code structure doesn't exactly match ROM logic for the speed thresholds.

**ROM Logic** (s2.asm lines 62075-62107):
```
1. If ACTION3 or ACTION4 set, return (already triggered)
2. If xVel >= -$14 (very slow):
   - Only dump if STATUS_HIT is set (boss was just hit)
   - Clear STATUS_HIT, set STATUS_GUNK_READY
3. If xVel >= -$40 (medium speed):
   - Return, not fast enough for auto-dump
4. If xVel < -$40 (fast):
   - Check player position relative to container
   - Dump if player is within 0x18 pixels of impact zone
```

**Current Code Issues**:
- The threshold checks seem correct but verify the exact values
- The player position math may be inverted

**Fix**: Ensure the logic exactly matches ROM:
```java
private void updateContainerDropTrigger() {
    // Already triggered
    if ((parent.status2 & STATUS2_ACTION3) != 0 || (parent.status2 & STATUS2_ACTION4) != 0) {
        return;
    }

    // Very slow: only dump on hit
    if (xVel >= -0x14) {
        if ((parent.status & STATUS_HIT) == 0) {
            return;
        }
        parent.status &= ~STATUS_HIT;
        parent.status |= STATUS_GUNK_READY;
        startContainerDump();
        return;
    }

    // Medium speed: not fast enough
    if (xVel >= -0x40) {
        return;
    }

    // Fast: check player position
    AbstractPlayableSprite player = Camera.getInstance().getFocusedSprite();
    if (player == null) {
        return;
    }

    int playerX = player.getCentreX() - 8;

    if ((renderFlags & 1) != 0) {
        // Boss facing left
        int d1 = playerX + xVel - x;
        if (d1 > 0) {
            return;
        }
        if (d1 >= -0x18) {
            startContainerDump();
        }
    } else {
        // Boss facing right
        int d1 = playerX - xVel - x;
        if (d1 < 0) {
            return;
        }
        if (d1 <= 0x18) {
            startContainerDump();
        }
    }
}
```

---

### Bug 4: Gunk Floor Y Check

**File**: `Sonic2CPZBossInstance.java`
**Method**: `updateGunkMain()` (line 845)

**Potential Issue**: The floor Y constant may need verification.

**Current**: `GUNK_FLOOR_Y = 0x0518` (1304)

**Verification Needed**: Confirm this matches ROM's delete threshold for gunk that falls off-screen without hitting terrain.

---

## Movement Position Constants

The following constants from the disassembly appear correct in the Java implementation:

| Constant | Value | Purpose |
|----------|-------|---------|
| MAIN_START_X | 0x2B80 | Initial spawn X |
| MAIN_START_Y | 0x04B0 | Initial spawn Y (off-screen) |
| MAIN_TARGET_Y | 0x04C0 | Hover baseline Y |
| MAIN_TARGET_RIGHT | 0x2B30 | Right arena position |
| MAIN_TARGET_LEFT | 0x2A50 | Left arena position |
| MAIN_FOLLOW_MIN_X | 0x2A28 | Left arena boundary |
| MAIN_FOLLOW_MAX_X | 0x2B70 | Right arena boundary |
| MAIN_RETREAT_CAMERA_MAX_X | 0x2C30 | Camera limit during retreat |
| CONTAINER_OFFSET_Y | 0x38 | Container Y offset from boss |

---

## Pipe Positioning Analysis

The pipe positioning was analyzed and is **correct**:

1. Pipe control starts at `parent.y + 0x18` in `updatePipeWait`
2. But `updatePipeSegment` copies `pipeParent.y` each frame, overwriting the offset
3. In the ROM, this is the same behavior - the +0x18 from init is lost
4. All segments position at `mainBoss.y + yOffset` where yOffset = segment * 8
5. This is visually correct because sprite mappings account for the offset

---

## Implementation Checklist

### Critical Fixes
- [ ] Fix `updateContainerMovement()` early return bug
- [ ] Fix speed cap logic to match ROM
- [ ] Verify drop trigger thresholds and player position math

### Verification Steps
- [ ] Container accelerates from -0x10 toward -0x58 when ACTION2 is set
- [ ] Drop triggers when player is within 0x18 pixels at high speed
- [ ] Gunk spawns and falls correctly
- [ ] Gunk landing resets boss to MAIN_MOVE_TOWARD_TARGET
- [ ] Boss alternates between left (0x2A50) and right (0x2B30) targets
- [ ] Attack cycle repeats correctly

### Testing Scenarios
1. Let boss reach target, verify pipe extends
2. Wait for container to accelerate (visible animation change at speeds -0x28, -0x40)
3. Position player in dump zone, verify gunk spawns
4. Verify boss returns to alternating position after gunk lands
5. Hit boss during slow phase, verify immediate dump

---

## ROM Reference Addresses

Key disassembly locations for reference:

| Function | s2.asm Line |
|----------|-------------|
| Obj5D_Main | 61087 |
| Obj5D_Main_MoveTowardTarget | 61331 |
| Obj5D_Main_FollowPlayer | 61390 |
| Obj5D_Pipe_Wait | 61522 |
| Obj5D_Pipe_Extend | 61549 |
| Obj5D_Container_Main | 61950 |
| Container movement (loc_2E4CE) | 62133 |
| Container drop trigger (loc_2E3F2) | 62075 |
| Obj5D_PipeSegment | 61766 |

---

## Animation Notes

### Container Animation Frames
- Anim 6: Slow retraction (xVel >= -0x28)
- Anim 7: Medium retraction (xVel >= -0x40)
- Anim 8: Full retraction (xVel < -0x40)

### Container Extend Animation
- Starts at anim 0 (waiting)
- On ACTION1: anim = 0x0B
- Increments each frame
- At anim >= 0x17: clears ACTION0, sets ACTION2

---

## Summary

The primary issue is Bug #1 - the early return in `updateContainerMovement()`. This single bug breaks the entire attack cycle by preventing container acceleration. Once fixed, the boss should:

1. Reach target position
2. Extend pipe and pump liquid
3. Accelerate container
4. Dump liquid when player is in range
5. Return to opposite target
6. Repeat cycle

The fix is straightforward: change the early return condition to only trigger when actually performing a reset (xVel == -0x10 AND ACTION4 is set).
