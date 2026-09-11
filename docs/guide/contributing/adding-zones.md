# Adding Zones

Bringing up a new zone involves more than implementing individual objects. This page covers
the full set of systems that need attention.

## Overview

A fully supported zone needs:

1. **Level data** -- Layouts, chunks, blocks, patterns verified and loading correctly.
2. **Collision** -- Height arrays and collision indices loaded and working.
3. **Scroll handler** -- Parallax background rendering.
4. **Palette** -- Zone palette and palette cycling (animated colors).
5. **Objects** -- Zone-specific objects registered and implemented.
6. **Level events** -- Dynamic camera boundaries, triggers, cutscenes.
7. **Water** -- If the zone has water: heights, palettes, dynamic handlers.
8. **Boss** -- If the zone has a boss encounter.

Not all of these are needed for initial bring-up. A zone can be "loadable" with just
level data and collision, and incrementally improved from there.

## Current S3K Priority

For Sonic 3 & Knuckles, prefer playable vertical slices over checklist-only zone work.
AIZ -> HCZ remains the primary release slice, but CNZ, MGZ, ICZ, MHZ, and LBZ now
have enough coverage that new S3K work should be selected from route blockers,
complete-run trace frontiers, and release-readiness gaps rather than first-pass zone
bring-up. A slice should be judged by whether a player can traverse it with coherent
events, camera bounds, scroll/parallax, animated tiles, palette/PLC state, required
objects, bosses or transitions, and known trace or visual parity blockers addressed
or documented.

Use the runtime-owned framework stack when implementing the slice: typed zone state in
`ZoneRuntimeRegistry`, palette writes through `PaletteOwnershipRegistry`, art uploads
through `AnimatedTileChannelGraph`, layout edits through `ZoneLayoutMutationPipeline`,
scroll composition through `ScrollEffectComposer`, and extra render state through
`SpecialRenderEffectRegistry` / `AdvancedRenderModeController`.

## Step 1: Verify Level Data

Before writing any code, confirm that the zone's data loads correctly from the ROM.

### Use RomOffsetFinder

Find the level data addresses:

```bash
# Search for the zone's resources
mvn exec:java -Dexec.mainClass="com.openggf.tools.disasm.RomOffsetFinder" \
    -Dexec.args="search OOZ" -q
```

This will show art, mapping, palette, and layout resources for the zone.

### Check the Constants File

Verify that the game's constants file has correct addresses for the zone's data. For S2,
check that the `LEVEL_DATA_DIR` entry for the zone points to valid data. For S3K, check
the `LevelLoadBlock` entry.

### Load and Inspect

Run the engine and navigate to the zone. Look for:
- **Garbled tiles:** Wrong pattern data address or wrong decompression type.
- **Missing background:** Background layout not loading, or loading wrong data.
- **Collision mismatch:** Player falls through floors or gets stuck in walls -- wrong
  collision array address or wrong collision index assignment.

## Step 2: Scroll Handler

Most zones need a custom scroll handler for parallax background scrolling. Without one,
the background will scroll 1:1 with the camera (no parallax effect).

### The Interface

Implement `ScrollHandler` and register it through the game's `ScrollHandlerProvider`:

The scroll handler is called each frame with the current camera position. It returns
scroll offsets for each scanline of the background plane.

### Reference

Look at existing scroll handlers for the same game. For S2:
- `src/main/java/com/openggf/game/sonic2/scroll/` -- one handler per zone
- EHZ is a good starting point: simple two-layer parallax with different scroll rates.

The disassembly equivalent is the `Deform_` or `SwScrl_` routines. Search the disassembly
for the zone's scroll routine to find the exact scroll rates and line splits.

### Verification

Compare the parallax rates against the disassembly values. Common format: each background
row scrolls at a fraction of the camera movement (e.g., 1/2, 1/4, 1/8 speed). The
disassembly typically expresses these as shift amounts or fractional multipliers.

## Step 3: Zone Palette

### Loading

The zone's palette is loaded from a ROM address specified in the game's level data
directory or palette table. The engine loads it during level initialization (one of the
`LevelInitProfile` steps).

For most zones, the palette loads automatically if the level data addresses are correct.
Check the rendered colors against the original game.

### Palette Cycling

Many zones have animated palette effects: waterfalls, lava, flashing lights, conveyor
belts. These are driven by palette cycle managers that modify specific palette entries
each frame.

In the disassembly: `Pal_Cycle` or `PalCycle_` routines.

In the engine: each game has a palette cycle manager (e.g., `Sonic2PaletteCycleManager`)
with per-zone cycle definitions. Adding a new zone's palette cycles means:
1. Reading the cycle routine in the disassembly.
2. Identifying which palette indices are cycled and at what rate.
3. Adding the cycle definition to the palette cycle manager.

## Step 4: Objects

### Which Objects Does the Zone Need?

Check the object checklists:
- [Sonic 2 Object Checklist](../../../OBJECT_CHECKLIST.md) -- shows which objects appear
  in each zone and their implementation status.

You can also load the zone and look for "Object registry missing id" log messages, which
tell you exactly which unimplemented objects the zone's placement data references.

### Implementation Priority

Not all objects are equally important. Prioritise:
1. **Route blockers** (doors, launchers, forced movement, water/chase pieces, boss gates) --
   these decide whether the slice can be played end to end.
2. **Platforms and terrain modifiers** (collapsing floors, moving platforms, bridges) --
   these affect whether the zone is traversable.
3. **Hazards** (spikes, lava, crushers) -- these affect gameplay.
4. **Boss/miniboss support objects and event helpers** -- these often gate act completion.
5. **High-usage badniks** -- enemies make the zone feel alive and exercise touch/collision parity.
6. **Scenery and decoration** (waterfalls, background animations) -- visual polish.

See [Tutorial: Implement an Object](tutorial-implement-object.md) for the implementation
pattern.

## Step 5: Level Events

Level events handle dynamic behavior that is tied to the player's position in the level:
- Camera boundary changes (opening up new areas as the player progresses).
- Boss encounter triggers.
- Cutscene triggers.
- Zone-specific mechanics (HTZ earthquake, LZ water level changes).

### The Interface

Implement a zone event class and register it through the game's `LevelEventProvider`.
The event's `update()` method is called each frame with the current camera position and
player state.

### Reference

In the disassembly, search for `LevEvents_` labels (S2) or the zone's event routine.
These are typically structured as a series of position checks:

```asm
; If camera X >= threshold, change right boundary to new value
cmpi.w  #$2A00,(Camera_X_pos).w
blt.s   +
move.w  #$2F60,(Camera_Max_X_pos).w
```

In the engine:

```java
if (camera.getX() >= 0x2A00) {
    camera.setRightBound(0x2F60);
}
```

## Step 6: Water (If Applicable)

Water zones need:

1. **Water heights** -- The initial water level for each act. Defined in the game's
   `WaterDataProvider`.
2. **Dynamic water handler** -- If the water level changes during gameplay (e.g., rising
   water in LZ). Implement `DynamicWaterHandler`.
3. **Underwater palette** -- The palette used below the water line. Typically stored in
   the ROM as a separate palette.
4. **Drowning mechanics** -- These are shared across all water zones and should work
   automatically once the water system is configured.

## Step 7: Boss (If Applicable)

See [Adding Bosses](adding-bosses.md) for the full boss implementation pattern. Key
integration points with the zone:
- The boss is spawned by the level event when the player reaches the trigger position.
- The level event manages camera lock/unlock around the boss arena.
- The boss's defeat triggers end-of-act flow (signpost or egg prison).

## Incremental Bring-Up

You do not need to complete everything at once. A reasonable progression:

1. **First pass:** Level data loads, basic collision works, player can walk through the
   zone. No scroll handler, no objects, no events.
2. **Second pass:** Scroll handler added, palette loads correctly, zone looks right.
3. **Third pass:** Core objects implemented (platforms, hazards), zone is playable.
4. **Fourth pass:** Badniks, level events, palette cycling, polish.
5. **Fifth pass:** Boss, full act completion flow, edge cases.

## Next Steps

- [Tutorial: Implement an Object](tutorial-implement-object.md) -- Object implementation pattern
- [Adding Bosses](adding-bosses.md) -- Boss-specific patterns
- [Testing](testing.md) -- Writing tests for zone behavior
