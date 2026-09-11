# S3K Water System Design

## Goal

Implement full S3K water support: static water heights from ROM table, dynamic per-zone water level handlers, and underwater palette cycling. Refactor the existing game-specific water loading into a provider-based architecture.

## Current State

- `WaterSystem` has game-specific methods: `loadForLevel()` (S2), `loadForLevelS1()` (S1)
- `Sonic3kZoneFeatureProvider.hasWater()` returns `false` for all zones
- No S3K water constants, no height table loading, no dynamic handlers
- Drowning/physics already support S3K (bubble shield exception coded in `AbstractPlayableSprite`)

## ROM Reference

### Water Initialization (`CheckLevelForWater`)

Sets `Water_flag`, reads `StartingWaterHeights.bin` (32 big-endian words, one per zone — both acts share each entry), initializes `Water_level`, `Mean_water_level`, `Target_water_level`, `Water_speed=1`.

Index calculation: `(zone_and_act >> 7) & 0xFFFE` — effectively `zone * 2`.

### Zones With Water

| Zone | Acts | Starting Height | Notes |
|------|------|-----------------|-------|
| AIZ (0) | Both | 0x0504, 0x0528 | AIZ1 intro has special handling |
| HCZ (1) | Act 2 | 0x0700 | Act 1 water conditional on character |
| LBZ (6) | Both | Act 1: 0x0AD8, Act 2: 0x0A80/0x065E | Complex dynamic handlers |

Other zones: starting height 0x0600 (off-screen default), `Water_flag=0`.

### Dynamic Water Height Handlers (`DynamicWaterHeight_Index`)

Jump table indexed by zone. Called once per frame when water is active.

**AIZ1:** No-op (`rts`) — static water level.

**AIZ2:** Complex state machine:
- Camera X < 0x2440 and target == 0x0618: drop to 0x0528, speed=2
- Camera X >= 0x2850: set `Level_trigger_array=1`
- When triggered and camera X < 0x2900: screen shake, raise back to 0x0618
- Knuckles: different thresholds (0x3B60, 0x0820 Y cap)

**HCZ1:** Threshold table scan:
- Camera X <= 0x8500 → target 0x0900
- Camera X <= 0x8680 → target 0x2A00
- Camera X <= 0x86A0 → target 0x3500

**HCZ2:** Character-branched threshold tables:
- Sonic: X <= 0x0700 → 0x3E00; X <= 0x07E0 → end
- Knuckles: X <= 0x0700 → 0x4100; X <= 0x8360 → end

**LBZ1:** Threshold table: 0x0E00 → 0x1980 → 0x2340 → 0x2C00.

**LBZ2 Knuckles:** Event-flag branched — either threshold table or instant-set to 0x0660.

**Ending:** Single threshold: camera X >= 0x1DE0 → target 0x0510.

### Water Palette

`LoadWaterPalette` sets `Water_palette_data_addr` per zone/act:
- AIZ1: `WaterTransition_AIZ1`
- AIZ2: `WaterTransition_AIZ2`
- HCZ1/HCZ2/LBZ1: `WaterTransition_HCZLBZ1`
- LBZ2: `WaterTransition_LBZ2`
- CNZ2/ICZ2: `WaterTransition_CNZ2ICZ2`

Knuckles: additional palette patch (`Pal_WaterKnux`, 6 bytes at Water_palette+$04).

### Water Movement

Per frame: `Mean_water_level` moves toward `Target_water_level` by `Water_speed` pixels. `Water_level` copies from `Mean_water_level` (oscillation is visual-only for rendering).

### Bit 15 Convention

Target values with bit 15 set mean "set Mean directly" (instant teleport) vs "set Target to move toward gradually".

## Architecture

### New Interfaces

#### `WaterDataProvider` (in `com.openggf.game`)

```java
public interface WaterDataProvider {
    boolean hasWater(int zoneId, int actId, PlayerCharacter character);
    int getStartingWaterLevel(int zoneId, int actId);
    Palette[] getUnderwaterPalette(Rom rom, int zoneId, int actId, PlayerCharacter character);
    DynamicWaterHandler getDynamicHandler(int zoneId, int actId, PlayerCharacter character);
    default int getWaterSpeed(int zoneId, int actId) { return 1; }
}
```

Registered via `GameModule.getWaterDataProvider()`.

#### `DynamicWaterHandler` (in `com.openggf.game`)

```java
public interface DynamicWaterHandler {
    void update(DynamicWaterState state, int cameraX, int cameraY);
}
```

Called once per frame from `LevelManager` (or via `WaterSystem.update()`).

### `DynamicWaterState` Changes

Promote from `WaterSystem` private inner class to package-visible. Add fields:
- `speed` (int, default 1) — pixels per frame toward target
- `handler` (DynamicWaterHandler, nullable) — per-frame update logic

Update `update()` to move by `speed` pixels instead of hardcoded 1.

### `WaterSystem` Refactored

Replace game-specific `loadForLevel()` / `loadForLevelS1()` with single game-agnostic method:

```java
public void loadForLevel(WaterDataProvider provider, Rom rom, int zoneId, int actId, PlayerCharacter character) {
    if (!provider.hasWater(zoneId, actId, character)) {
        waterConfigs.put(key, new WaterConfig(false, 0, null));
        return;
    }
    int height = provider.getStartingWaterLevel(zoneId, actId);
    Palette[] palette = provider.getUnderwaterPalette(rom, zoneId, actId, character);
    DynamicWaterHandler handler = provider.getDynamicHandler(zoneId, actId, character);
    int speed = provider.getWaterSpeed(zoneId, actId);
    waterConfigs.put(key, new WaterConfig(true, height, palette));
    DynamicWaterState state = new DynamicWaterState(height);
    state.setSpeed(speed);
    state.setHandler(handler);
    dynamicWaterStates.put(key, state);
}
```

### S3K Dynamic Handlers

**`ThresholdTableWaterHandler`** — Shared implementation for table-scanned zones:

```java
record WaterThreshold(int cameraXThreshold, int targetWaterLevel) {}

class ThresholdTableWaterHandler implements DynamicWaterHandler {
    private final List<WaterThreshold> thresholds;
    public void update(DynamicWaterState state, int cameraX, int cameraY) {
        for (var t : thresholds) {
            if (cameraX <= t.cameraXThreshold()) {
                if (t.targetWaterLevel() < 0) {
                    // Bit 15 set: instant-set mean
                    state.setMeanDirect(t.targetWaterLevel() & 0x7FFF);
                } else {
                    state.setTarget(t.targetWaterLevel());
                }
                return;
            }
        }
    }
}
```

Used by: HCZ1, HCZ2 (Sonic), HCZ2 (Knuckles), LBZ1, LBZ2 (Knuckles).

**`Aiz2DynamicWaterHandler`** — Custom state machine for AIZ2 (camera triggers, screen shake, Level_trigger_array).

**`EndingWaterHandler`** — Single-threshold handler for ending sequence.

### Game-Specific Providers

**`Sonic3kWaterDataProvider`:**
- `hasWater()`: zone/act/character logic from `CheckLevelForWater`
- `getStartingWaterLevel()`: reads `StartingWaterHeights.bin` from ROM
- `getUnderwaterPalette()`: loads zone-specific `WaterTransition_*` palette tables, applies Knuckles patch
- `getDynamicHandler()`: returns appropriate handler per zone (threshold table, AIZ2 custom, null for static)

**`Sonic2WaterDataProvider`:**
- Migrated from existing `WaterSystem.loadForLevel()` + `extractWaterHeight()` logic
- `hasWater()`: ARZ, CPZ, HTZ
- Dynamic handler for CPZ2 rising Mega Mack

**`Sonic1WaterDataProvider`:**
- Migrated from existing `WaterSystem.loadForLevelS1()` + `getS1WaterHeight()` logic
- `hasWater()`: LZ acts 1-3, SBZ3

### `Sonic3kZoneFeatureProvider` Updates

- `hasWater()` → delegates to `WaterSystem.hasWater()`
- `getWaterLevel()` → delegates to `WaterSystem.getWaterLevelY()`
- `initZoneFeatures()` → calls `WaterSystem.loadForLevel(s3kProvider, ...)`

### `LevelManager` Changes

- `initWater()`: gets `WaterDataProvider` from `GameModule`, passes to `WaterSystem.loadForLevel()`
- Per-frame update: calls `WaterSystem.updateDynamic(zoneId, actId, cameraX, cameraY)` which invokes the stored `DynamicWaterHandler`

### Visual Water Oscillation

S3K water oscillation: `WaterSystem.getVisualWaterLevelY()` needs S3K zone cases. The ROM applies oscillation through `Handle_Onscreen_Water_Height` for H-interrupt timing. In the engine, this affects the visual water surface Y used for palette split rendering.

## Migration Path

1. Create `WaterDataProvider` + `DynamicWaterHandler` interfaces
2. Promote `DynamicWaterState` to package-visible, add `speed` + `handler` fields
3. Add game-agnostic `WaterSystem.loadForLevel(WaterDataProvider, ...)`, add `updateDynamic()`
4. Implement `Sonic3kWaterDataProvider` + S3K constants + threshold handlers
5. Migrate S2 water → `Sonic2WaterDataProvider`
6. Migrate S1 water → `Sonic1WaterDataProvider`
7. Remove old game-specific methods from `WaterSystem`
8. Wire `Sonic3kZoneFeatureProvider` + `LevelManager.initWater()`
9. Add S3K water palette loading
10. Tests: unit tests for each provider + handlers, regression tests for S1/S2

## Files to Create

| File | Purpose |
|------|---------|
| `game/WaterDataProvider.java` | Interface: water config per zone/act/character |
| `game/DynamicWaterHandler.java` | Interface: per-frame water level updates |
| `game/ThresholdTableWaterHandler.java` | Shared table-scan handler |
| `game/sonic3k/Sonic3kWaterDataProvider.java` | S3K water heights, palettes, handlers |
| `game/sonic3k/Aiz2DynamicWaterHandler.java` | AIZ2 state machine handler |
| `game/sonic2/Sonic2WaterDataProvider.java` | S2 water (migrated from WaterSystem) |
| `game/sonic1/Sonic1WaterDataProvider.java` | S1 water (migrated from WaterSystem) |

## Files to Modify

| File | Change |
|------|--------|
| `level/WaterSystem.java` | Promote DynamicWaterState, add game-agnostic loadForLevel, add updateDynamic, add speed field, remove S1/S2-specific methods |
| `game/GameModule.java` | Add `getWaterDataProvider()` |
| `game/sonic3k/Sonic3kGameModule.java` | Return `Sonic3kWaterDataProvider` |
| `game/sonic2/Sonic2GameModule.java` | Return `Sonic2WaterDataProvider` |
| `game/sonic1/Sonic1GameModule.java` | Return `Sonic1WaterDataProvider` |
| `game/sonic3k/Sonic3kZoneFeatureProvider.java` | Wire `hasWater()`/`getWaterLevel()` |
| `game/sonic3k/Sonic3kConstants.java` | Add water table + palette ROM addresses |
| `level/LevelManager.java` | Update `initWater()` to use provider, add dynamic update call |

## Testing

- `TestSonic3kWaterDataProvider`: hasWater per zone/act/character, starting heights match ROM
- `TestThresholdTableWaterHandler`: target setting at each camera X breakpoint
- `TestAiz2DynamicWaterHandler`: multi-trigger state machine transitions
- `TestSonic2WaterDataProvider`: regression — same hasWater/heights as before migration
- `TestSonic1WaterDataProvider`: regression — same hasWater/heights as before migration
- `TestDynamicWaterState`: speed field, movement toward target, instant-set via bit 15
- Headless integration: load S3K HCZ2, verify water level is set correctly
