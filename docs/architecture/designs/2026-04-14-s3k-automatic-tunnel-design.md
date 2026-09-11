# S3K AutomaticTunnel Object Implementation

**Date:** 2026-04-14
**Scope:** Implement Object 0x24 (AutomaticTunnel) for S3K — path-guided tube traversal used in AIZ and LBZ.

## Problem

AIZ tubes are non-functional. The player enters rolling state (via AutoSpin 0x26 triggers at tube entries) but is not guided through the tube path. They can stop mid-tube and momentum is wrong because AutomaticTunnel (0x24) — which captures the player and moves them along a predefined waypoint path — has no factory implementation. It is only named in `Sonic3kObjectRegistry.getPrimaryName()`.

## Design

### New class: `AutomaticTunnelObjectInstance`

**File:** `src/main/java/com/openggf/game/sonic3k/objects/AutomaticTunnelObjectInstance.java`

**Extends:** `AbstractObjectInstance`

**Disassembly reference:** `Obj_AutomaticTunnel` (sonic3k.asm lines 57180-57457), path data at lines 202488-203387.

### State machine (per-character, P1 + sidekick independently)

Three states matching ROM's `AutoTunnel_Index` jump table:

| State | ROM label | Behavior |
|-------|-----------|----------|
| 0 (INIT) | `Obj_AutoTunnelInit` | Detect player in capture zone, lock and launch into path |
| 2 (RUN) | `Obj_AutoTunnelRun` | Follow waypoints with calculated velocity |
| 4 (LAST_MOVE) | `Obj_AutoTunnelLastMove` | 2-frame gravity exit, then release |

#### State 0 — INIT (capture detection)

Trigger zone: player within ±0x10 X and ±0x18 Y of object position (ROM lines 57222-57231: `addi.w #$10,d0; cmpi.w #$20,d0` for X, `addi.w #$18,d1; cmpi.w #$28,d1` for Y — unsigned range checks centered on object).

Skip if `object_control != 0` (player already controlled by another object).

On capture:
- `object_control = 0x81` → `setObjectControlled(true)` + `setControlLocked(true)` (full lockdown, no input)
- `anim = 2` → force rolling animation
- `jumping = 0` → clear jumping flag
- `ground_vel = 0x800`, `x_vel = 0`, `y_vel = 0`
- Clear pushing, set InAir
- Snap player position to object position
- Resolve path via `AutoTunnel_GetPath`, calculate initial velocity
- Play rolling SFX
- If subtype bit 5 AND act 2: strip fire and lightning shields (LBZ2 water tunnels)

#### State 2 — RUN (path following)

Each frame:
1. Decrement duration timer
2. If timer > 0: move player by velocity (16.16 fixed-point: `x_pos += x_vel << 8`, `y_pos += y_vel << 8`)
3. If timer reaches 0:
   - Snap player to current waypoint position
   - If path reversed (subtype bit 7): step pointer backwards
   - Decrement path remaining by 4
   - If path exhausted → transition to LAST_MOVE:
     - If subtype bit 6 NOT set: zero x_vel and y_vel (otherwise maintain velocity)
     - Mask y_pos with 0xFFF
     - Play tube launcher SFX (`sfx_TubeLauncher`)
     - If subtype bit 5: spawn exhaust object (stubbed initially — LBZ2 cosmetic)
   - Otherwise: calculate velocity to next waypoint

#### State 4 — LAST_MOVE (gravity exit)

2-frame phase: each frame adds 0x38 to y_vel (gravity), then moves player. After 2 frames: clear `object_control`, reset state to 0.

### Velocity calculation

Identical to MTZ spin tube (`AutoTunnel_CalcSpeed`, ROM lines 57390-57457):
- Speed constant: 0x1000 on the dominant axis
- Compare abs(dx) vs abs(dy) to determine dominant axis
- Dominant axis velocity = ±0x1000 (signed by direction)
- Cross-axis velocity = `(cross_distance << 16) / duration`
- Duration = `abs((dominant_distance << 16) / 0x1000)`
- Store duration high byte as frame countdown timer

### Path data

26 paths hardcoded as `int[][]` from disassembly (`AutoTunnel_00` through `AutoTunnel_19`). Each path is a flat array of X, Y coordinate pairs. Paths 1 and 2 share the same data.

### Subtype encoding

| Bits | Mask | Meaning |
|------|------|---------|
| 0-4 | 0x1F | Path ID (0-25) |
| 5 | 0x20 | LBZ2 mode: strip fire/lightning shields on entry, spawn exhaust on exit |
| 6 | 0x40 | Maintain velocity on exit (skip zeroing x/y vel) |
| 7 | 0x80 | Reverse path traversal direction |

### Special case: Tails path redirect

ROM line 57366: When `Player_mode == TAILS_ALONE` and path ID is 0x10 (16), redirect to path 0. This handles a Tails-specific alternate route.

### Per-character state

Each character (P1 and sidekick) has independent state matching ROM's 10-byte blocks at `objoff_30` and `objoff_3A`:
- `state` (byte): current phase (0/2/4)
- `subState` (byte): unused padding in ROM
- `duration` (word): frame countdown timer
- `pathRemaining` (word): bytes of path data remaining
- `pathDataPointer` (long): current index into path array

### Persistence

Object stays alive while either character is in state != 0 (actively controlled). When both are idle, normal off-screen deletion applies.

### Registration changes

1. Add `AUTOMATIC_TUNNEL = 0x24` to `Sonic3kObjectIds`
2. Register factory in `Sonic3kObjectRegistry.registerDefaultFactories()`

### Delayed variant

`Obj_AutomaticTunnelDelayed` (ROM line 57171) is a timer-gated wrapper used when the object is spawned dynamically during cutscenes. The normal factory-spawned object uses the standard init path. The delayed variant is not needed for level-placed objects.

## Verification

- Play AIZ Act 1 and verify tubes guide Sonic through the correct path
- Verify player cannot stop mid-tube or provide input during traversal
- Verify exit momentum matches ROM (zeroed or maintained based on subtype)
- Verify tube launcher SFX plays on exit
- Verify rolling SFX plays on entry
- Run existing S3K tests to ensure no regressions:
  - `TestS3kAiz1SkipHeadless`
  - `TestSonic3kLevelLoading`
  - `TestSonic3kBootstrapResolver`
  - `TestSonic3kDecodingUtils`
  - `TestS3kAiz1LoopRegression`
  - `TestS3kAiz1SpindashLoopTraversal`
