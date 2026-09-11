# Game-Specific Leak Fixes — Design Spec

**Date:** 2026-03-23
**Branch:** `feature/ai-common-utility-refactors`
**Status:** Approved

## Overview

Four targeted fixes to remove game-specific type references from shared infrastructure (`LevelManager` and `GraphicsManager`). Each follows the same pattern: move game-specific logic behind an existing provider/module interface. No new manager classes created. No null returns — use NoOp sentinels and empty-object patterns consistently.

## Non-Goals

- God class decomposition (deferred — fragmentation cost outweighs benefit)
- New singleton managers or service layers
- Changing any gameplay behavior

---

## Fix 1: `instanceof Sonic2Level` → `ZoneFeatureProvider.isForceBlackBackdrop()`

**Problem:** `LevelManager.isForceBlackBackdrop()` (line 3392) casts `level` to `Sonic2Level` to check if the zone is MCZ (zone 11). This is a Sonic 2-specific concern in shared code.

**Design:** Add a default method to `ZoneFeatureProvider`:

```java
default boolean isForceBlackBackdrop() {
    return false;
}
```

`Sonic2ZoneFeatureProvider` overrides it:

```java
@Override
public boolean isForceBlackBackdrop() {
    return currentZoneId == 0x0B; // MCZ
}
```

`LevelManager.isForceBlackBackdrop()` becomes:

```java
private boolean isForceBlackBackdrop() {
    ZoneFeatureProvider zfp = gameModule.getZoneFeatureProvider();
    return zfp.isForceBlackBackdrop();
}
```

**Imports removed from LevelManager:** `com.openggf.game.sonic2.Sonic2Level`

---

## Fix 2: `Sonic3kPlayerArt` instantiation → `GameModule.loadTailsTailArt()`

**Problem:** `LevelManager.initTailsTails()` (line 1185) directly instantiates `Sonic3kPlayerArt` to load Tails tail sprites. This is an S3K-specific class in shared code.

**Design:** Add an `EMPTY` sentinel and `isEmpty()` to `ObjectSpriteSheet`:

```java
// In ObjectSpriteSheet:
public static final ObjectSpriteSheet EMPTY = new ObjectSpriteSheet(new Pattern[0], List.of(), 0, 0);

public boolean isEmpty() {
    return patterns.length == 0;
}
```

Add a default method to `GameModule`:

```java
default ObjectSpriteSheet loadTailsTailArt() {
    return ObjectSpriteSheet.EMPTY;
}
```

`Sonic3kGameModule` overrides it:

```java
@Override
public ObjectSpriteSheet loadTailsTailArt() {
    Rom rom = GameServices.rom().getRom();
    Sonic3kPlayerArt s3kArt = new Sonic3kPlayerArt(RomByteReader.fromRom(rom));
    return s3kArt.loadTailsTail();
}
```

The existing code at line 1178-1187 has a branching structure:
- If `CrossGameFeatureProvider.isActive()` → use donor art
- Else if S3K → use `Sonic3kPlayerArt` directly

After the fix, the else branch calls `gameModule.loadTailsTailArt()` and checks `isEmpty()`:

```java
ObjectSpriteSheet tailsArt;
if (CrossGameFeatureProvider.getInstance().isActive()) {
    tailsArt = CrossGameFeatureProvider.getInstance().loadTailsTailArt();
} else {
    tailsArt = gameModule.loadTailsTailArt();
}
if (!tailsArt.isEmpty()) {
    // create tail controller
}
```

S1 and S2 modules inherit the default returning `EMPTY` — they don't have separate tail art (`hasSeparateTailsTailArt()` is already false for them).

**Imports removed from LevelManager:** `com.openggf.game.sonic3k.Sonic3kPlayerArt`

---

## Fix 3: `S3kSeamlessMutationExecutor` → `GameModule.applySeamlessMutation()`

**Problem:** `LevelManager.applySeamlessMutation()` (line 3492) directly calls `S3kSeamlessMutationExecutor.apply()`. This is an S3K-specific class in shared code.

**Design:** Add a default method to `GameModule`:

```java
default void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
    // No-op for games without seamless mutations
}
```

`Sonic3kGameModule` overrides it:

```java
@Override
public void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
    S3kSeamlessMutationExecutor.apply(levelManager, mutationKey);
}
```

`LevelManager.applySeamlessMutation()` becomes:

```java
private void applySeamlessMutation(String mutationKey) {
    gameModule.applySeamlessMutation(this, mutationKey);
}
```

**Imports removed from LevelManager:** `com.openggf.game.sonic3k.events.S3kSeamlessMutationExecutor`

---

## Fix 4: CNZ Slot Machine Renderer → `ZoneFeatureProvider.getFeatureRenderer()`

**Problem:** `GraphicsManager` owns `cnzSlotMachineRenderer` (line 88) and `cnzSlotsShaderProgram` (line 87) — Sonic 2 CNZ-specific rendering in shared graphics infrastructure.

**Design:** Define a minimal `ZoneFeatureRenderer` interface in the `game` package:

```java
public interface ZoneFeatureRenderer {
    void render(/* params TBD from current callers */);
    void cleanup();

    ZoneFeatureRenderer NONE = new ZoneFeatureRenderer() {
        @Override public void render(/* params */) {}
        @Override public void cleanup() {}
    };
}
```

Add a default method to `ZoneFeatureProvider`:

```java
default ZoneFeatureRenderer getFeatureRenderer() {
    return ZoneFeatureRenderer.NONE;
}
```

`Sonic2ZoneFeatureProvider` overrides with lazy init of the CNZ slot machine:

```java
private CNZSlotMachineRenderer cnzSlotMachineRenderer;
private ShaderProgram cnzSlotsShaderProgram;

@Override
public ZoneFeatureRenderer getFeatureRenderer() {
    if (cnzSlotMachineRenderer == null) {
        // Lazy init — move current code from GraphicsManager.getCnzSlotMachineRenderer()
        // Shader loading, renderer creation, setShader()
    }
    return cnzSlotMachineRenderer; // CNZSlotMachineRenderer implements ZoneFeatureRenderer
}
```

`CNZSlotMachineRenderer` implements `ZoneFeatureRenderer`. The lazy shader initialization (currently in `GraphicsManager.getCnzSlotMachineRenderer()` lines 1238-1248) moves into the provider.

`GraphicsManager` removes the 2 fields, the `getCnzSlotMachineRenderer()` method, the shader constant, and the init/cleanup code for these.

Callers (in S2 code) switch from `GraphicsManager.getInstance().getCnzSlotMachineRenderer()` to `zoneFeatureProvider.getFeatureRenderer()`.

**Fields removed from GraphicsManager:** `cnzSlotMachineRenderer`, `cnzSlotsShaderProgram`
**Methods removed from GraphicsManager:** `getCnzSlotMachineRenderer()`

---

## Summary

| Fix | Shared file | Import removed | Provider method added | No-null pattern |
|-----|-------------|----------------|----------------------|-----------------|
| 1 | LevelManager | `Sonic2Level` | `ZoneFeatureProvider.isForceBlackBackdrop()` | boolean default `false` |
| 2 | LevelManager | `Sonic3kPlayerArt` | `GameModule.loadTailsTailArt()` | `ObjectSpriteSheet.EMPTY` + `isEmpty()` |
| 3 | LevelManager | `S3kSeamlessMutationExecutor` | `GameModule.applySeamlessMutation()` | void default no-op |
| 4 | GraphicsManager | CNZ renderer + shader | `ZoneFeatureProvider.getFeatureRenderer()` | `ZoneFeatureRenderer.NONE` sentinel |

After all four fixes, LevelManager's game-specific imports drop from 4 to 1 (`Sonic2Constants` for `ART_TILE_TAILS_TAILS` — could be addressed separately via a constants provider). GraphicsManager loses its only game-specific rendering concern.
