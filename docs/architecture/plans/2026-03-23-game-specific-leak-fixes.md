# Game-Specific Leak Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove 4 game-specific type references from shared infrastructure (LevelManager and GraphicsManager) by routing through provider/module interfaces.

**Architecture:** Four independent commits, each moving one game-specific concern behind an existing interface. No new manager classes. No null returns — use NoOp sentinels and empty-object patterns.

**Tech Stack:** Java 21, Maven

**Spec:** `docs/architecture/designs/2026-03-23-game-specific-leak-fixes-design.md`

---

### Task 1: Move isForceBlackBackdrop to ZoneFeatureProvider

**Files:**
- Modify: `src/main/java/com/openggf/game/ZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:3391-3398`

- [ ] **Step 1: Add default method to ZoneFeatureProvider**

At the end of `ZoneFeatureProvider.java` (after line ~200, before closing brace), add:

```java
/**
 * Whether the current zone requires a black backdrop instead of the
 * normal background clear color. Default: false.
 */
default boolean isForceBlackBackdrop() {
    return false;
}
```

- [ ] **Step 2: Override in Sonic2ZoneFeatureProvider**

Add override. The class has a `currentZone` int field (line 54):

```java
@Override
public boolean isForceBlackBackdrop() {
    return currentZone == 0x0B; // MCZ
}
```

- [ ] **Step 3: Replace LevelManager.isForceBlackBackdrop()**

Replace lines 3391-3398 with:

```java
private boolean isForceBlackBackdrop() {
    ZoneFeatureProvider zfp = gameModule.getZoneFeatureProvider();
    return zfp.isForceBlackBackdrop();
}
```

Remove the `import com.openggf.game.sonic2.Sonic2Level;` (line 5).

- [ ] **Step 4: Verify**

Run: `mvn compile -q && mvn test -q 2>&1 | tail -5`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/ZoneFeatureProvider.java src/main/java/com/openggf/game/sonic2/Sonic2ZoneFeatureProvider.java src/main/java/com/openggf/level/LevelManager.java
git commit -m "$(cat <<'EOF'
refactor: move isForceBlackBackdrop to ZoneFeatureProvider

LevelManager was casting to Sonic2Level to check for MCZ zone ID.
Now routed through ZoneFeatureProvider.isForceBlackBackdrop() with
a default of false. S2 overrides for MCZ. Removes Sonic2Level import
from LevelManager.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Move Tails tail art loading to GameModule

**Files:**
- Modify: `src/main/java/com/openggf/sprites/SpriteArtSet.java` (add EMPTY sentinel)
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:1168-1199`

- [ ] **Step 1: Find SpriteArtSet and add EMPTY sentinel**

Read `src/main/java/com/openggf/sprites/SpriteArtSet.java` (or wherever the class lives — grep for `class SpriteArtSet`). Add:

```java
public static final SpriteArtSet EMPTY = new SpriteArtSet(new Pattern[0], List.of(), List.of(), 0, 0);

public boolean isEmpty() {
    return artTiles() == null || artTiles().length == 0;
}
```

Adjust the EMPTY constructor args to match the actual constructor signature. If SpriteArtSet is a record, the field access pattern will differ — read the file first.

- [ ] **Step 2: Add default method to GameModule**

Near `hasSeparateTailsTailArt()` (line ~292), add:

```java
/**
 * Loads the separate Tails tail sprite art set (Obj05).
 * Returns {@link SpriteArtSet#EMPTY} if this game doesn't use separate tail art.
 */
default SpriteArtSet loadTailsTailArt() {
    return SpriteArtSet.EMPTY;
}
```

- [ ] **Step 3: Override in Sonic3kGameModule**

Near `hasSeparateTailsTailArt()` (line ~180), add:

```java
@Override
public SpriteArtSet loadTailsTailArt() {
    try {
        Rom rom = GameServices.rom().getRom();
        Sonic3kPlayerArt s3kArt = new Sonic3kPlayerArt(RomByteReader.fromRom(rom));
        return s3kArt.loadTailsTail();
    } catch (Exception e) {
        LOGGER.log(Level.SEVERE, "Failed to load S3K tails tail art", e);
        return SpriteArtSet.EMPTY;
    }
}
```

Add necessary imports (`GameServices`, `Sonic3kPlayerArt`, `RomByteReader`, `SpriteArtSet`, `Level`, `Logger`).

- [ ] **Step 4: Simplify LevelManager.initTailsTails()**

Replace the S3K branch (lines ~1182-1187) that directly instantiates `Sonic3kPlayerArt`:

```java
if (CrossGameFeatureProvider.isActive()) {
    tailsArt = CrossGameFeatureProvider.getInstance().loadTailsTailArt();
} else {
    tailsArt = gameModule.loadTailsTailArt();
}
```

The `try/catch` around this block can be simplified since Sonic3kGameModule now handles the exception internally and returns EMPTY.

After the art loading, check `isEmpty()`:

```java
if (tailsArt.isEmpty()) {
    playable.setTailsTailsController(null);
    return;
}
```

Remove `import com.openggf.game.sonic3k.Sonic3kPlayerArt;` (line 54).

- [ ] **Step 5: Verify**

Run: `mvn compile -q && mvn test -q 2>&1 | tail -5`

- [ ] **Step 6: Commit**

```bash
git add -A src/main/java/
git commit -m "$(cat <<'EOF'
refactor: move Tails tail art loading to GameModule

LevelManager was directly instantiating Sonic3kPlayerArt for tail
sprites. Now routed through GameModule.loadTailsTailArt() with a
default returning SpriteArtSet.EMPTY. Added isEmpty() sentinel
pattern to SpriteArtSet. Removes Sonic3kPlayerArt import from
LevelManager.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Move seamless mutation to GameModule

**Files:**
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:3491-3493`

- [ ] **Step 1: Add default method to GameModule**

Add near the end of the interface:

```java
/**
 * Applies a seamless art mutation during act transitions.
 * No-op for games without seamless mutations.
 */
default void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
    // No-op
}
```

Add import for `LevelManager` if not present.

- [ ] **Step 2: Override in Sonic3kGameModule**

```java
@Override
public void applySeamlessMutation(LevelManager levelManager, String mutationKey) {
    S3kSeamlessMutationExecutor.apply(levelManager, mutationKey);
}
```

Verify `S3kSeamlessMutationExecutor` is already imported in Sonic3kGameModule (it's in the same `game.sonic3k` package tree).

- [ ] **Step 3: Replace LevelManager.applySeamlessMutation()**

Replace lines 3491-3493 with:

```java
private void applySeamlessMutation(String mutationKey) {
    gameModule.applySeamlessMutation(this, mutationKey);
}
```

Remove `import com.openggf.game.sonic3k.events.S3kSeamlessMutationExecutor;` (line 6).

- [ ] **Step 4: Verify**

Run: `mvn compile -q && mvn test -q 2>&1 | tail -5`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/GameModule.java src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java src/main/java/com/openggf/level/LevelManager.java
git commit -m "$(cat <<'EOF'
refactor: move seamless mutation to GameModule

LevelManager was directly calling S3kSeamlessMutationExecutor.apply().
Now routed through GameModule.applySeamlessMutation() with a default
no-op. S3K overrides to delegate. Removes S3kSeamlessMutationExecutor
import from LevelManager.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Move CNZ Slot Machine Renderer to ZoneFeatureProvider

This is the most involved fix. The renderer has GL state (textures, VAO/VBO, shader) and lifecycle concerns.

**Files:**
- Create: `src/main/java/com/openggf/game/ZoneFeatureRenderer.java` (interface)
- Modify: `src/main/java/com/openggf/game/ZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/slotmachine/CNZSlotMachineRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java:87-88,180-181,988-993,1238-1248`

- [ ] **Step 1: Create ZoneFeatureRenderer interface**

Create `src/main/java/com/openggf/game/ZoneFeatureRenderer.java`:

```java
package com.openggf.game;

/**
 * Optional zone-specific renderer for custom visual effects (e.g., CNZ slot
 * machine overlay, bonus stage wheels). Returned by
 * {@link ZoneFeatureProvider#getFeatureRenderer()}.
 */
public interface ZoneFeatureRenderer {

    /** Release any GL resources held by this renderer. */
    void cleanup();

    /** No-op sentinel for zones without a feature renderer. */
    ZoneFeatureRenderer NONE = new ZoneFeatureRenderer() {
        @Override
        public void cleanup() {}
    };
}
```

- [ ] **Step 2: Add default method to ZoneFeatureProvider**

```java
/**
 * Returns a zone-specific feature renderer, or {@link ZoneFeatureRenderer#NONE}
 * if the current zone has no custom rendering.
 */
default ZoneFeatureRenderer getFeatureRenderer() {
    return ZoneFeatureRenderer.NONE;
}
```

- [ ] **Step 3: Make CNZSlotMachineRenderer implement ZoneFeatureRenderer**

In `CNZSlotMachineRenderer.java` (line ~34), change the class declaration:

```java
public class CNZSlotMachineRenderer implements ZoneFeatureRenderer {
```

The class already has a `cleanup()` method (lines 548-555) that matches the interface.

- [ ] **Step 4: Move ownership to Sonic2ZoneFeatureProvider**

Read `Sonic2ZoneFeatureProvider.java`. It already has a reference at line 120. Move the lazy shader init from GraphicsManager into the provider.

Add fields:
```java
private CNZSlotMachineRenderer cnzSlotMachineRenderer;
private ShaderProgram cnzSlotsShaderProgram;
```

Override `getFeatureRenderer()`:
```java
@Override
public ZoneFeatureRenderer getFeatureRenderer() {
    if (cnzSlotMachineRenderer == null) {
        cnzSlotMachineRenderer = new CNZSlotMachineRenderer();
    }
    if (cnzSlotsShaderProgram == null && GraphicsManager.getInstance().isGlInitialized()) {
        try {
            cnzSlotsShaderProgram = new ShaderProgram(
                    ShaderProgram.FULLSCREEN_VERTEX_SHADER,
                    "/shaders/cnz_slots.frag"); // Use the actual path from GraphicsManager.CNZ_SLOTS_SHADER_PATH
            cnzSlotMachineRenderer.setShader(cnzSlotsShaderProgram);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load CNZ slot shader", e);
        }
    }
    return cnzSlotMachineRenderer;
}
```

Check GraphicsManager for the actual `CNZ_SLOTS_SHADER_PATH` constant value and use it.

- [ ] **Step 5: Update the caller in Sonic2ZoneFeatureProvider**

The existing line 120 (`cnzSlotMachineRenderer = graphicsManager.getCnzSlotMachineRenderer()`) should be replaced to use the local field via `getFeatureRenderer()` or direct field access.

- [ ] **Step 6: Remove CNZ code from GraphicsManager**

Remove from `GraphicsManager.java`:
- Fields at lines 87-88 (`cnzSlotsShaderProgram`, `cnzSlotMachineRenderer`)
- Init at line 180-181 (`this.cnzSlotMachineRenderer = new CNZSlotMachineRenderer()`)
- Cleanup at lines 988-993 (the two if-blocks for CNZ)
- The entire `getCnzSlotMachineRenderer()` method at lines 1238-1248
- The `CNZ_SLOTS_SHADER_PATH` constant (if no other code uses it)
- Any CNZ-related imports that become unused

- [ ] **Step 7: Verify**

Run: `mvn compile -q && mvn test -q 2>&1 | tail -5`

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/
git commit -m "$(cat <<'EOF'
refactor: move CNZ slot machine renderer to ZoneFeatureProvider

GraphicsManager owned Sonic 2 CNZ-specific rendering (shader + renderer).
Now owned by Sonic2ZoneFeatureProvider via ZoneFeatureRenderer interface.
ZoneFeatureRenderer.NONE sentinel used for zones/games without custom
renderers. Removes CNZ fields, init, cleanup, and accessor from
GraphicsManager.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```
