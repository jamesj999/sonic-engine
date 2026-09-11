# S3K Cutscene Architecture Design

## Context

Sonic 3 & Knuckles has scripted sequences ("cutscenes") throughout gameplay: zone intros (AIZ plane arrival, ICZ snowboard), Knuckles encounters (13 across the game), act-to-act transitions (AIZ fire, LBZ Death Egg launch), boss arena lockdowns, and attract-mode demo playback. These are deeply integrated into the level event system rather than being a separate game mode. This document maps the ROM's cutscene architecture onto the engine's existing patterns, identifying what can be reused and what needs to be built.

## Key Insight: Cutscenes Are Level Events

In the original ROM, cutscenes happen **within the normal game loop**. The level continues rendering, objects keep updating, and physics keeps running. The ROM achieves scripted sequences through three control mechanisms applied simultaneously:

| Mechanism | ROM Variable | Engine Equivalent | Status |
|-----------|-------------|-------------------|--------|
| Input lock | `Ctrl_1_locked` | `AbstractPlayableSprite.controlLocked` | **Already exists** |
| Camera lock | `Scroll_lock` | `Camera.frozen` | **Already exists** |
| Scripted input | Demo data buffer / object code | `TailsCpuController` pattern | **Pattern exists** |
| Event dispatch | `LevelEventArray` table | `LevelEventProvider.update()` | **Already exists** |
| Routine state machine | `Events_routine_fg/bg` | `LevelEventManager.eventRoutine` | **Already exists** |

The existing engine has the primitives. What's needed is an S3K-specific `LevelEventProvider` and cutscene object implementations.

## ROM Architecture (Reference)

### Event Dispatch

The ROM's `ScreenEvents()` function (skdisasm line 102228) is called once per frame and dispatches to zone-specific handlers via two tables:

```
LevelSetupArray  - init handlers (called once on level load)
  Per zone/act: [ScreenInit, BackgroundInit]

LevelEventArray  - per-frame handlers
  Per zone/act: [ScreenEvent, BackgroundEvent]
```

Each zone has two event handlers per act:
- **ScreenEvent** (Plane A) - Foreground tile changes, camera boundary triggers, object spawning
- **BackgroundEvent** (Plane B) - Parallax, deformation, BG scrolling

### Cutscene Trigger Flow

```
ScreenEvent called each frame
  |
  +-- Check camera X/Y against hardcoded thresholds
  |     e.g. AIZ1: Camera_X >= 0x2D30 triggers tree chunk change
  |     e.g. MHZ1: Camera_X >= 0x4298 AND Camera_Y >= 0x710 triggers miniboss
  |
  +-- When trigger met:
        1. Set Ctrl_1_locked = 1  (suppress player input)
        2. Set Scroll_lock = 1    (stop camera following player)
        3. Modify Camera_min/max_X/Y_pos (lock camera boundaries)
        4. Spawn cutscene object (AllocateObject -> Obj_CutsceneKnuckles, etc.)
        5. Advance Events_routine_fg += 4 (move to next state)
        6. Play music/SFX as needed
```

### Cutscene Object Pattern

Cutscene objects (e.g., `Obj_CutsceneKnuckles`, `Obj_AIZPlaneIntro`) use the standard object routine pattern:

```assembly
Obj_CutsceneKnuckles:
    moveq  #0,d0
    move.b routine(a0),d0
    move.w CutsceneKnuckles_Index(pc,d0.w),d1
    jsr    CutsceneKnuckles_Index(pc,d1.w)
```

- Routine index table with 7-24 routines per object
- Each routine handles one phase (init, wait for trigger, animate, move, cleanup)
- Routines advance via `addq.b #2,routine(a0)`
- Frame counters at `$2E(a0)` with jump-to addresses at `$34(a0)` for timed transitions
- Standard physics calls (`MoveSprite2`, `Animate_Raw`)

### Knuckles Cutscene Subtypes

`Obj_CutsceneKnuckles` has 13 subtypes selected by the object's subtype byte:

| Subtype | Zone | Description |
|---------|------|-------------|
| 0x00 | AIZ1 | Punch intro - Knuckles punches Sonic, scatters emeralds |
| 0x04 | AIZ2 | Idle/watching |
| 0x08 | HCZ2 | Gray button puzzle |
| 0x0C | CNZ2a | Idle |
| 0x10 | CNZ2b | Idle |
| 0x14 | LBZ1 | Pillar trap |
| 0x18 | LBZ2 | Death Egg girder |
| 0x1C | MHZ1 | Idle/watching |
| 0x20 | MHZ2 | Press button |
| 0x24 | LRZ2 | On platform |
| 0x28 | SSZ1 | Tired (S&K only) |
| ... | ... | ... |

### Demo Playback System

For attract mode, the ROM uses pre-recorded input buffers:

```
DemoPtrs table -> [DemoDat_AIZ, DemoDat_HCZ, DemoDat_MGZ, ...]

Format (non-special-stage): One byte per frame = button state
Format (special stage): Alternating [duration_byte][input_byte] pairs

Demo_PlayRecord() each frame:
  1. Read next byte from demo data
  2. Write to Ctrl_1 (replaces real input)
  3. Check if player pressed START to exit
```

## Engine Mapping

### What Already Works

1. **`LevelEventProvider` interface** (`game/LevelEventProvider.java`)
   - `initLevel(zone, act)` - perfect for per-zone/act setup
   - `update()` - called every frame, dispatches to zone handlers

2. **`LevelEventManager` pattern** (`game/sonic2/LevelEventManager.java`)
   - `eventRoutine` counter incremented by 2 per state = ROM's `Events_routine_fg`
   - `frameCounter` for timing = ROM's various timing counters
   - Switch-based zone dispatch = ROM's `LevelEventArray` table
   - Camera boundary manipulation via `camera.setMinX/MaxX/MinY/MaxY()`
   - Camera target boundaries with easing (`setMinXTarget()`, etc.)
   - Object spawning via `LevelManager.getObjectManager().addDynamicObject()`
   - Audio triggers via `AudioManager`

3. **Input locking** (`sprites/playable/AbstractPlayableSprite.java:283`)
   - `controlLocked` field = ROM's `Ctrl_1_locked`
   - `setControlLocked(true)` suppresses all directional + jump input
   - Already integrated into `SpriteManager.update()` input processing

4. **Camera freeze** (`camera/Camera.java:43`)
   - `frozen` flag = ROM's `Scroll_lock`
   - `setFrozen(true)` stops camera from following player
   - Manual position control still works via `setX()`/`setY()`

5. **CPU-controlled sprites** (`sprites/playable/AbstractPlayableSprite.java:98-101`)
   - `cpuControlled` flag + `TailsCpuController` interface
   - Used for Tails AI following - same pattern needed for cutscene input injection
   - `SpriteManager` already checks `cpuControlled` and reads AI input

6. **Object routine pattern** - All existing objects use `AbstractObjectInstance` with update methods

### What Needs to Be Built

#### 1. `Sonic3kLevelEventManager` (implements `LevelEventProvider`)

The S3K equivalent of `LevelEventManager`. Key differences from S2:

- **Dual event counters**: S3K uses separate `Events_routine_fg` and `Events_routine_bg` (S2 uses one `eventRoutine`). Need two routine counters.
- **Per-event data storage**: S3K has `Events_fg_0` through `Events_fg_5` and `Events_bg_0` through `Events_bg_16+` - per-zone temporary variables. Could use a `short[]` or named fields.
- **Special events routine**: Global `Special_events_routine` counter for cross-system coordination (e.g., boss active flag).
- **More zones**: S3K has ~13 zones vs S2's ~11.

```
Sonic3kLevelEventManager
  - eventRoutineFg: int          (Events_routine_fg)
  - eventRoutineBg: int          (Events_routine_bg)
  - specialEventsRoutine: int    (Special_events_routine)
  - eventDataFg: short[6]       (Events_fg_0..5)
  - eventDataBg: short[24]      (Events_bg_0..23)
  - scrollLocked: boolean        (Scroll_lock - could also use Camera.frozen)
  - bossFlag: boolean            (Boss_flag)

  + initLevel(zone, act)
  + update()
  - updateAiz1(), updateAiz2(), ... (per-zone handlers)
```

#### 2. Cutscene Object Instances

Standard `AbstractObjectInstance` subclasses. Each cutscene object is just a game object with a routine-based state machine:

```
CutsceneKnucklesObjectInstance extends AbstractObjectInstance
  - routineIndex: int
  - frameTimer: int
  - Knuckles sprite/animation data

  + update() -> dispatch to current routine
  - routineInit(), routineWaitTrigger(), routineJump(), routineLand(), ...
```

These need:
- S3K-specific sprite art (Knuckles mappings, cutscene-specific tiles)
- Animation data from ROM
- The `Sonic3kObjectRegistry` to register them

#### 3. `DemoPlaybackController` (for attract mode)

Implements the `TailsCpuController` interface to feed recorded input:

```
DemoPlaybackController implements TailsCpuController
  - demoData: byte[]         (loaded from ROM)
  - position: int            (current read position)
  - holdCounter: int         (frames remaining for current input)
  - currentButtons: int      (current button state)

  + update(frameCounter)     (advance demo data, update button state)
  + getInputUp/Down/Left/Right/Jump()  (read from currentButtons)
```

Two formats to support:
- **Standard**: 1 byte per frame (non-special-stage zones)
- **Special stage**: alternating `[duration][buttons]` pairs

#### 4. Zone Intro Objects

Per-zone intro sequences are separate objects spawned by `SpawnLevelMainSprites()`:

| Object | Zone | Description |
|--------|------|-------------|
| `AizPlaneIntroObject` | AIZ1 | Sonic arrives on Tornado, jumps off |
| `IczSnowboardIntroObject` | ICZ1 | Sonic snowboards down mountain |
| `LevelIntroPlayerFallObject` | Various | Generic "player falls from above" |
| `LevelIntroPlayerRunObject` | Various | Generic "player runs in from left" |

These are standard objects with 10-24 routine states each.

### Architecture Diagram

```
GameLoop.step()
  |
  +-- spriteManager.update(inputHandler)
  |     |
  |     +-- For each PlayableSprite:
  |           if cpuControlled -> read TailsCpuController/DemoPlaybackController
  |           if controlLocked -> suppress input
  |           else -> read keyboard input
  |
  +-- levelEvents.update()          [Sonic3kLevelEventManager]
  |     |
  |     +-- Check camera triggers
  |     +-- Advance event routines
  |     +-- Lock/unlock input (sprite.setControlLocked())
  |     +-- Lock/unlock camera (camera.setFrozen())
  |     +-- Spawn cutscene objects (objectManager.addDynamicObject())
  |     +-- Manipulate camera boundaries
  |     +-- Trigger audio
  |
  +-- camera.updateBoundaryEasing()
  +-- camera.updatePosition()       [no-op if frozen]
  +-- levelManager.update()
        |
        +-- objectManager.updateAll()
              |
              +-- CutsceneKnucklesObject.update()  [routine state machine]
              +-- AizPlaneIntroObject.update()      [routine state machine]
              +-- ... (normal objects still updating too)
```

### Integration Points in Existing Code

| Component | Change Needed | File |
|-----------|--------------|------|
| `Sonic3kGameModule.getLevelEventProvider()` | Return `Sonic3kLevelEventManager` instead of null | `game/sonic3k/Sonic3kGameModule.java` |
| `Sonic3kGameModule.createObjectRegistry()` | Return registry with cutscene objects | `game/sonic3k/Sonic3kGameModule.java` |
| `GameLoop.step()` | No changes - already calls `levelEvents.update()` | `GameLoop.java` |
| `SpriteManager.update()` | No changes - already handles `controlLocked` and `cpuControlled` | `sprites/managers/SpriteManager.java` |
| `Camera` | No changes - `frozen` and boundary setters already exist | `camera/Camera.java` |

### No New GameMode Needed

S3K cutscenes do **not** need a `CUTSCENE` entry in the `GameMode` enum. They run within `GameMode.LEVEL` using the existing update pipeline. The level event system handles all cutscene orchestration.

### Character-Specific Branching

S3K cutscenes differ by character (Sonic/Tails vs Knuckles). The level event manager needs to know the current player character:

```java
// In Sonic3kLevelEventManager
private void updateAiz1() {
    if (playerMode == PlayerMode.KNUCKLES) {
        updateAiz1Knuckles();
    } else {
        updateAiz1SonicTails();
    }
}
```

ROM uses `Player_mode` (0=Sonic+Tails, 1=Sonic alone, 2=Tails alone, 3=Knuckles).

## File Structure (When Implemented)

```
game/sonic3k/
  events/
    Sonic3kLevelEventManager.java      - Main event dispatcher
    Sonic3kLevelEventData.java         - Per-zone event data storage
    zones/
      AizEventHandler.java            - AIZ1/2 events (intro, fire, Knuckles)
      HczEventHandler.java            - HCZ1/2 events
      MhzEventHandler.java            - MHZ1/2 events (miniboss)
      LbzEventHandler.java            - LBZ1/2 events (Death Egg launch)
      ... (one per zone)
  objects/
    CutsceneKnucklesObjectInstance.java  - All 13 Knuckles encounters
    AizPlaneIntroObjectInstance.java     - AIZ intro plane sequence
    IczSnowboardIntroObjectInstance.java - ICZ snowboard intro
    LevelIntroObjectInstances.java       - Generic intro types (fall, run)
  demo/
    DemoPlaybackController.java        - Attract mode input replay
    DemoDataLoader.java                - Loads demo data from ROM
```

## Prerequisites (Before Cutscenes Can Be Implemented)

1. **S3K level loading** - Chunks, blocks, patterns, collision from S3K ROM
2. **S3K object system** - `Sonic3kObjectRegistry` with factories
3. **S3K sprite art** - Knuckles sprite sheets, cutscene-specific tiles
4. **S3K zone registry** - Zone metadata (IDs, names, act counts)
5. **S3K player character selection** - Sonic/Tails/Knuckles mode

## Verification Plan

When eventually implemented, cutscenes can be verified by:
1. **HeadlessTestRunner** - Step frames, assert camera position, player position, and input lock state at known frame counts
2. **Visual comparison** - Side-by-side with emulator at key cutscene moments
3. **State assertions** - After N frames of cutscene, verify `controlLocked`, `camera.isFrozen()`, spawned object count
