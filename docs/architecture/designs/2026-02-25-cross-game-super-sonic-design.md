# Cross-Game Super Sonic Design

## Problem

1. **Sonic 1 + U key:** `Sonic1GameModule.createSuperStateController()` returns null (default). Pressing U does nothing even when `CROSS_GAME_FEATURES_ENABLED` is active.
2. **Sonic 2 + S3K donor:** `Sonic2GameModule` always returns `Sonic2SuperStateController`, which loads S2 art/palette data from the base ROM. Cross-game S3K donor art, palette cycling, and renderer are ignored.

## Solution: Donor SuperStateController Delegation

When `CrossGameFeatureProvider` is active, the donor game's own `SuperStateController` is instantiated and its ROM data is loaded from the donor ROM. No new controller classes are needed.

## Changes

### 1. `CrossGameFeatureProvider` — new method: `createSuperStateController(player)`

- If donor is S3K: returns `new Sonic3kSuperStateController(player)`, pre-loaded with donor ROM data
- If donor is S2: returns `new Sonic2SuperStateController(player)`, pre-loaded with donor ROM data
- Calls `controller.loadRomData(donorReader)` internally before returning

### 2. `Sonic1GameModule` — override `createSuperStateController()`

- When `CrossGameFeatureProvider.isActive()`: delegate to `CrossGameFeatureProvider.getInstance().createSuperStateController(player)`
- When inactive: return null (no Super Sonic in vanilla S1)

### 3. `Sonic2GameModule` — modify `createSuperStateController()`

- When `CrossGameFeatureProvider.isActive()`: delegate to `CrossGameFeatureProvider.getInstance().createSuperStateController(player)`
- When inactive: return `new Sonic2SuperStateController(player)` (unchanged)

### 4. `LevelManager.initSuperState()` — skip redundant ROM load

- When `CrossGameFeatureProvider.isActive()`: the controller is already pre-loaded with donor ROM data, so skip the base-ROM `loadRomData()` call
- When inactive: unchanged (load from base ROM as before)

## Key Design Decisions

- **Reuse existing controllers:** `Sonic2SuperStateController` and `Sonic3kSuperStateController` already handle palette cycling, art/renderer swapping, physics profiles, and ring drain for their respective games. No duplication needed.
- **Pre-load in provider:** `CrossGameFeatureProvider.createSuperStateController()` calls `loadRomData(donorReader)` before returning, so `LevelManager` doesn't need to know about donor ROMs.
- **Donor audio:** Super Sonic music (S2: `MUS_SUPER_SONIC`, S3K: invincibility) is already registered via `CrossGameFeatureProvider.initializeDonorAudio()`.
- **U key input:** `SpriteManager` already checks `superCtrl != null` — making the module return a non-null controller is all that's needed for U key activation.

## Files Modified

| File | Change |
|------|--------|
| `CrossGameFeatureProvider.java` | Add `createSuperStateController(player)` method |
| `Sonic1GameModule.java` | Override `createSuperStateController()` with cross-game delegation |
| `Sonic2GameModule.java` | Modify `createSuperStateController()` with cross-game delegation |
| `LevelManager.java` | Skip redundant `loadRomData()` when cross-game pre-loaded |
