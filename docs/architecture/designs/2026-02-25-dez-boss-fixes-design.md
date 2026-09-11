# DEZ Boss Fixes Design

Date: 2026-02-25

## Problem

Death Egg Zone has three runtime failures:
1. Camera locks don't work (Silver Sonic arena, Death Egg Robot arena)
2. Silver Sonic doesn't spawn
3. Death Egg Robot is missing body parts and flies off screen

## Root Causes

### Zone ID Mismatch
`Sonic2ZoneRegistry` uses indices 0-10 (game progression). `Sonic2LevelEventManager` uses ROM-style constants with gaps: `ZONE_UNUSED_1=1`, `ZONE_UNUSED_2=8`, making `ZONE_SCZ=9`, `ZONE_WFZ=10`, `ZONE_DEZ=11`. When DEZ loads at zone index 10, the event dispatch hits `case ZONE_WFZ` instead of `case ZONE_DEZ`. DEZ events never fire.

Also broken: SCZ (reg=8, dispatches to UNUSED_2=null) and WFZ (reg=9, dispatches to SCZ handler).

### Death Egg Robot Children Not Rendered
10 body part children are added to `childComponents` for update() but never registered with `ObjectManager.addDynamicObject()`. They update but don't render.

### State Machine Runs Without Arena
Without level events, the Death Egg Robot starts immediately. Head auto-boards Eggman after 30 frames, countdown 60 frames, then RISE at yVel=-0x100 for 121 frames — straight off the top of the screen.

## Fix Plan

### Fix 1: Zone ID Constants
Change `Sonic2LevelEventManager` to match zone registry ordering:
- Remove ZONE_UNUSED_1 and ZONE_UNUSED_2
- ZONE_SCZ = 8, ZONE_WFZ = 9, ZONE_DEZ = 10
- ZONE_CPZ stays at 1

### Fix 2: Register Children
In `Sonic2DeathEggRobotInstance.spawnChildren()`, add `addDynamicObject()` calls for all 10 children, matching Silver Sonic's pattern.

### Fix 3: Verify State Machine
With events firing, confirm the boss sequence works: camera lock at X=0x680 → robot rise → fight. No code changes expected if events fire correctly.

## Files Changed
- `Sonic2LevelEventManager.java` — zone constants
- `Sonic2DeathEggRobotInstance.java` — child registration
