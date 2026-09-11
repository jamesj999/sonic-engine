# S3K Sidekick Knuckles Fixes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix three bugs when Knuckles is the sidekick alongside Sonic in S3K: sprite corruption (VRAM collision), wrong palette (blue Knuckles), and broken glide-in drop (stuck in mid-air).

**Architecture:** Three independent fixes. (1) Always shift sidekick sprites to isolated `SIDEKICK_PATTERN_BASE` banks, removing the name-based `computeVramSlots` optimization. (2) Create per-sidekick `RenderContext` palette blocks for characters whose palette differs from the main character. (3) Make `KnucklesRespawnStrategy.requiresPhysics()` state-aware so the drop phase runs gravity.

**Tech Stack:** Java 21, JUnit 5, Maven

**Spec:** `docs/architecture/designs/2026-04-03-s3k-sidekick-knuckles-fixes-design.md`

---

### Task 1: Fix Knuckles Glide-In Drop (requiresPhysics)

This is the simplest and most self-contained fix. No other files depend on it.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/KnucklesRespawnStrategy.java`
- Create: `src/test/java/com/openggf/sprites/playable/TestKnucklesRespawnStrategy.java`

- [ ] **Step 1: Write failing test — requiresPhysics returns false before drop**

Create `src/test/java/com/openggf/sprites/playable/TestKnucklesRespawnStrategy.java`:

```java
package com.openggf.sprites.playable;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link KnucklesRespawnStrategy} state-aware physics gating.
 * Uses a minimal stub controller — no ROM, no OpenGL required.
 */
class TestKnucklesRespawnStrategy {

    @Test
    void requiresPhysics_falseBeforeDrop() {
        // Strategy starts in glide phase — physics should be skipped
        KnucklesRespawnStrategy strategy = new KnucklesRespawnStrategy(null);
        assertFalse(strategy.requiresPhysics(),
                "Glide phase should not require physics (manual positioning)");
    }
}
```

- [ ] **Step 2: Run test to verify it passes (baseline)**

Run: `mvn test -Dtest=TestKnucklesRespawnStrategy#requiresPhysics_falseBeforeDrop -pl . -q`

Expected: PASS — the default `requiresPhysics()` returns `false`, which matches our assertion. This establishes the baseline before we add the drop-phase test.

- [ ] **Step 3: Write failing test — requiresPhysics returns true during drop**

Add to `TestKnucklesRespawnStrategy.java`:

```java
    @Test
    void requiresPhysics_trueDuringDrop() {
        // After drop is triggered, physics must run so gravity applies
        KnucklesRespawnStrategy strategy = new KnucklesRespawnStrategy(null);
        // Trigger the drop by calling setDropping (or use reflection if private)
        strategy.triggerDrop();
        assertTrue(strategy.requiresPhysics(),
                "Drop phase must require physics for gravity to apply");
    }
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn test -Dtest=TestKnucklesRespawnStrategy#requiresPhysics_trueDuringDrop -pl . -q`

Expected: FAIL — `triggerDrop()` does not exist yet, compilation error.

- [ ] **Step 5: Implement requiresPhysics override and triggerDrop**

Edit `src/main/java/com/openggf/sprites/playable/KnucklesRespawnStrategy.java`.

Add the `requiresPhysics()` override and a package-private test helper:

```java
    @Override
    public boolean requiresPhysics() {
        return dropping;
    }

    /** Package-private: allows tests to trigger the drop phase directly. */
    void triggerDrop() {
        dropping = true;
    }
```

- [ ] **Step 6: Run both tests to verify they pass**

Run: `mvn test -Dtest=TestKnucklesRespawnStrategy -pl . -q`

Expected: PASS (both tests)

- [ ] **Step 7: Add glide animation — resolve GLIDE_DROP in constructor**

Edit `KnucklesRespawnStrategy.java`. Add a `glideAnimId` field resolved from `CanonicalAnimation.GLIDE_DROP`, and set forced animation during glide. The constructor needs to handle the case where `GameModuleRegistry.getCurrent()` returns null (unit tests) by falling back to -1.

Add import at top:
```java
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.GameModuleRegistry;
```

Add field:
```java
    private final int glideAnimId;
```

Replace constructor:
```java
    public KnucklesRespawnStrategy(SidekickCpuController controller) {
        var module = GameModuleRegistry.getCurrent();
        this.glideAnimId = (module != null)
                ? module.resolveAnimationId(CanonicalAnimation.GLIDE_DROP)
                : -1;
    }
```

In `beginApproach()`, add after the existing `sidekick.setGSpeed((short) 0);` line:
```java
        sidekick.setForcedAnimationId(glideAnimId);
```

In `updateApproaching()`, inside the `if (!dropping)` block, add after `sidekick.setObjectControlled(true);`:
```java
            sidekick.setForcedAnimationId(glideAnimId);
```

- [ ] **Step 8: Run all tests to verify nothing broke**

Run: `mvn test -pl . -q`

Expected: PASS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/KnucklesRespawnStrategy.java \
       src/test/java/com/openggf/sprites/playable/TestKnucklesRespawnStrategy.java
git commit -m "fix: Knuckles sidekick glide-in drop now applies gravity

Override requiresPhysics() to return true during the drop phase so
SpriteManager runs the physics pipeline. Add GLIDE_DROP forced
animation during the glide approach phase."
```

---

### Task 2: Always Shift Sidekick VRAM Banks

Remove `computeVramSlots()` and always give every sidekick an isolated pattern bank at `SIDEKICK_PATTERN_BASE + offset`.

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java:1198-1290`
- Replace: `src/test/java/com/openggf/game/TestSidekickArtBankAllocation.java` (rewrite tests)

- [ ] **Step 1: Delete the old test class and write new failing tests**

The old `TestSidekickArtBankAllocation` tests call `LevelManager.computeVramSlots()` which we are removing. Replace the entire file with tests that verify the new behavior: every sidekick is always shifted.

Since `computeVramSlots()` is being removed, we can't unit-test a static method any more. Instead, we'll test the invariant at a higher level: that the sidekick art init code always produces shifted bases. We'll do this by extracting the bank offset computation into a testable static utility.

Replace `src/test/java/com/openggf/game/TestSidekickArtBankAllocation.java` with:

```java
package com.openggf.game;

import com.openggf.level.LevelManager;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that every sidekick gets a unique shifted VRAM bank,
 * regardless of whether its character name matches the main character.
 */
class TestSidekickArtBankAllocation {

    /**
     * All sidekicks should get unique offsets within SIDEKICK_PATTERN_BASE,
     * including characters that differ from the main (previously got slot 0).
     */
    @Test
    void allSidekicksGetUniqueOffsets() {
        // Simulate: sonic main, knuckles sidekick (different char, previously slot 0)
        // Bank sizes: knuckles=48
        List<Integer> offsets = LevelManager.computeSidekickBankOffsets(List.of(48));
        assertEquals(1, offsets.size());
        assertEquals(0, offsets.get(0)); // first sidekick starts at offset 0
    }

    @Test
    void multipleSidekicksGetNonOverlappingOffsets() {
        // sonic main, tails(bankSize=32) + knuckles(bankSize=48) sidekicks
        List<Integer> offsets = LevelManager.computeSidekickBankOffsets(List.of(32, 48));
        assertEquals(2, offsets.size());
        assertEquals(0, offsets.get(0));   // tails at offset 0
        assertEquals(32, offsets.get(1));  // knuckles at offset 32 (after tails)
    }

    @Test
    void duplicateCharactersGetSeparateOffsets() {
        // sonic main, sonic(bankSize=64) + sonic(bankSize=64) sidekicks
        List<Integer> offsets = LevelManager.computeSidekickBankOffsets(List.of(64, 64));
        assertEquals(2, offsets.size());
        assertEquals(0, offsets.get(0));
        assertEquals(64, offsets.get(1));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=TestSidekickArtBankAllocation -pl . -q`

Expected: FAIL — `computeSidekickBankOffsets()` does not exist yet.

- [ ] **Step 3: Implement — remove computeVramSlots, add computeSidekickBankOffsets, simplify init loop**

Edit `src/main/java/com/openggf/level/LevelManager.java`.

**A. Remove `computeVramSlots()` method** (lines 1278-1290). Delete the entire method.

**B. Add replacement method** in the same location:

```java
    /**
     * Computes the running VRAM bank offset for each sidekick within SIDEKICK_PATTERN_BASE.
     * Every sidekick unconditionally gets its own isolated bank — no name-based slot
     * optimization (which missed ART_TILE collisions like Knuckles/Sonic sharing 0x0680).
     *
     * @param bankSizes the bank size of each sidekick's art set, in order
     * @return list of offsets (one per sidekick) within SIDEKICK_PATTERN_BASE
     */
    public static List<Integer> computeSidekickBankOffsets(List<Integer> bankSizes) {
        List<Integer> offsets = new java.util.ArrayList<>(bankSizes.size());
        int running = 0;
        for (int size : bankSizes) {
            offsets.add(running);
            running += size;
        }
        return offsets;
    }
```

**C. Simplify the sidekick art init loop** (lines 1198-1265). Replace the `computeVramSlots` call and the `if (slot > 0)` conditional so every sidekick is always shifted.

Remove these lines (around 1198):
```java
        java.util.Map<Integer, Integer> vramSlots = computeVramSlots(mainCharName, sidekickCharNames);
```

Remove the art cache approach that shares SpriteArtSet instances (since each sidekick needs its own shifted copy). Change the art cache to cache the *source* art only, and always create a shifted copy:

Replace the sidekick loop body (lines 1204-1244) — the section from getting the art through creating the shifted SpriteArtSet — with:

```java
        int sidekickBankOffset = 0;
        for (int i = 0; i < sidekicks.size(); i++) {
            AbstractPlayableSprite sidekick = sidekicks.get(i);
            String sidekickCharName = sidekickCharNames.get(i);
            try {
                SpriteArtSet sourceArt = artCache.computeIfAbsent(
                        sidekickCharName.toLowerCase(),
                        key -> {
                            try {
                                return artProvider.loadPlayerSpriteArt(key);
                            } catch (IOException e) {
                                LOGGER.log(SEVERE, "Failed to load art for sidekick character: " + key, e);
                                return null;
                            }
                        });
                if (sourceArt == null || sourceArt.bankSize() <= 0
                        || sourceArt.mappingFrames().isEmpty()
                        || sourceArt.dplcFrames().isEmpty()) {
                    LOGGER.warning("Skipping art init for sidekick " + i
                            + " (" + sidekickCharName + "): art unavailable or empty.");
                    continue;
                }
                // Every sidekick gets its own isolated bank in SIDEKICK_PATTERN_BASE range.
                // This avoids VRAM collisions even when characters share the same ART_TILE
                // base (e.g., Knuckles and Sonic both use 0x0680 in S3K).
                int shiftedBase = SIDEKICK_PATTERN_BASE + sidekickBankOffset;
                sidekickBankOffset += sourceArt.bankSize();
                SpriteArtSet sidekickArt = new SpriteArtSet(
                        sourceArt.artTiles(),
                        sourceArt.mappingFrames(),
                        sourceArt.dplcFrames(),
                        sourceArt.paletteIndex(),
                        shiftedBase,
                        sourceArt.frameDelay(),
                        sourceArt.bankSize(),
                        sourceArt.animationProfile(),
                        sourceArt.animationSet());
```

The rest of the loop body (creating renderer, setting it on sidekick, calling `initSpindashDust`, `initTailsTails`, `initSuperState`) stays unchanged. Make sure the variable name used downstream is `sidekickArt` (not `sourceArt`).

- [ ] **Step 4: Run new tests to verify they pass**

Run: `mvn test -Dtest=TestSidekickArtBankAllocation -pl . -q`

Expected: PASS

- [ ] **Step 5: Run full test suite to check for regressions**

Run: `mvn test -pl . -q`

Expected: PASS. No other tests should reference `computeVramSlots`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java \
       src/test/java/com/openggf/game/TestSidekickArtBankAllocation.java
git commit -m "fix: always isolate sidekick VRAM banks to prevent sprite corruption

Remove computeVramSlots() name-based optimization that missed VRAM
collisions when characters share the same ART_TILE base (Knuckles and
Sonic both use 0x0680 in S3K). Every sidekick now unconditionally gets
its own bank at SIDEKICK_PATTERN_BASE + running offset."
```

---

### Task 3: Sidekick Palette Isolation via RenderContext

Add `createSidekickContext()` to `RenderContext` and wire it into the sidekick art init loop so sidekick characters with different palettes render correctly.

**Files:**
- Modify: `src/main/java/com/openggf/graphics/RenderContext.java`
- Modify: `src/test/java/com/openggf/graphics/TestRenderContext.java`
- Modify: `src/main/java/com/openggf/data/PlayerSpriteArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3k.java`
- Modify: `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

#### Step Group A: RenderContext.createSidekickContext()

- [ ] **Step 1: Write failing test — createSidekickContext allocates fresh palette block**

Add to `src/test/java/com/openggf/graphics/TestRenderContext.java`:

```java
    @Test
    public void createSidekickContext_allocatesFreshBlock() {
        RenderContext sidekick = RenderContext.createSidekickContext(GameId.S3K);
        assertEquals(4, sidekick.getPaletteLineBase());
        assertEquals(8, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void createSidekickContext_doesNotCacheByGameId() {
        RenderContext first = RenderContext.createSidekickContext(GameId.S3K);
        RenderContext second = RenderContext.createSidekickContext(GameId.S3K);
        assertNotSame(first, second, "Each sidekick must get its own context");
        assertEquals(4, first.getPaletteLineBase());
        assertEquals(8, second.getPaletteLineBase());
        assertEquals(12, RenderContext.getTotalPaletteLines());
    }

    @Test
    public void createSidekickContext_coexistsWithDonorContexts() {
        RenderContext donor = RenderContext.getOrCreateDonor(GameId.S2);
        assertEquals(4, donor.getPaletteLineBase());

        RenderContext sidekick = RenderContext.createSidekickContext(GameId.S3K);
        assertEquals(8, sidekick.getPaletteLineBase());
        assertEquals(12, RenderContext.getTotalPaletteLines());
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=TestRenderContext#createSidekickContext_allocatesFreshBlock -pl . -q`

Expected: FAIL — method does not exist.

- [ ] **Step 3: Implement createSidekickContext**

Edit `src/main/java/com/openggf/graphics/RenderContext.java`. Add after the `getOrCreateDonor()` method (around line 41):

```java
    /**
     * Creates a fresh sidekick palette context, allocating the next available
     * palette line block. Unlike {@link #getOrCreateDonor(GameId)}, this does
     * NOT cache — each call returns a new context so multiple sidekicks can
     * each have their own isolated palette lines.
     *
     * @param gameId the game this sidekick belongs to (for diagnostics)
     * @return a new RenderContext with its own palette line block
     */
    public static RenderContext createSidekickContext(GameId gameId) {
        RenderContext ctx = new RenderContext(gameId, nextPaletteBase);
        nextPaletteBase += LINES_PER_CONTEXT;
        return ctx;
    }
```

Also update `uploadDonorPalettes` to also upload sidekick contexts. The simplest approach: track sidekick contexts in a list so they can be iterated during upload.

Add a field:
```java
    private static final java.util.List<RenderContext> sidekickContexts = new java.util.ArrayList<>();
```

In `createSidekickContext`, before `return ctx;`, add:
```java
        sidekickContexts.add(ctx);
```

Update `uploadDonorPalettes`:
```java
    public static void uploadDonorPalettes(GraphicsManager gm) {
        for (RenderContext ctx : donorContexts.values()) {
            for (int line = 0; line < LINES_PER_CONTEXT; line++) {
                Palette p = ctx.palettes[line];
                if (p != null) {
                    gm.cachePaletteTexture(p, ctx.paletteLineBase + line);
                }
            }
        }
        for (RenderContext ctx : sidekickContexts) {
            for (int line = 0; line < LINES_PER_CONTEXT; line++) {
                Palette p = ctx.palettes[line];
                if (p != null) {
                    gm.cachePaletteTexture(p, ctx.paletteLineBase + line);
                }
            }
        }
    }
```

Update `reset()`:
```java
    public static void reset() {
        donorContexts.clear();
        sidekickContexts.clear();
        nextPaletteBase = LINES_PER_CONTEXT;
    }
```

- [ ] **Step 4: Run RenderContext tests to verify they pass**

Run: `mvn test -Dtest=TestRenderContext -pl . -q`

Expected: PASS (all tests including new ones)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/RenderContext.java \
       src/test/java/com/openggf/graphics/TestRenderContext.java
git commit -m "feat: add RenderContext.createSidekickContext for palette isolation

Each sidekick can now get its own palette line block, independent of
donor contexts. Sidekick palettes are uploaded alongside donor palettes."
```

#### Step Group B: Add loadCharacterPalette to PlayerSpriteArtProvider

- [ ] **Step 6: Add default method to PlayerSpriteArtProvider interface**

Edit `src/main/java/com/openggf/data/PlayerSpriteArtProvider.java`:

```java
package com.openggf.data;

import com.openggf.level.Palette;
import com.openggf.sprites.art.SpriteArtSet;

import java.io.IOException;

/**
 * Optional interface for games that can provide player sprite art from ROM.
 */
public interface PlayerSpriteArtProvider {
    SpriteArtSet loadPlayerSpriteArt(String characterCode) throws IOException;

    /**
     * Loads the character palette for the given character code.
     * Returns null if the provider does not support per-character palettes.
     *
     * @param characterCode "sonic", "tails", or "knuckles"
     * @return the character's palette, or null
     */
    default Palette loadCharacterPalette(String characterCode) {
        return null;
    }
}
```

- [ ] **Step 7: Implement in Sonic3k**

Edit `src/main/java/com/openggf/game/sonic3k/Sonic3k.java`. Add after the `loadPlayerSpriteArt` method (around line 115):

```java
    @Override
    public Palette loadCharacterPalette(String characterCode) {
        if (characterCode == null) {
            return null;
        }
        int paletteAddr;
        int paletteSize = Palette.PALETTE_SIZE_IN_ROM;
        if ("knuckles".equalsIgnoreCase(characterCode)) {
            paletteAddr = Sonic3kConstants.KNUCKLES_PALETTE_ADDR;
            paletteSize = 32; // Pal_Knuckles: 1 palette line
        } else {
            paletteAddr = Sonic3kConstants.SONIC_PALETTE_ADDR;
        }
        byte[] data = rom.readBytes(paletteAddr, paletteSize);
        Palette palette = new Palette();
        palette.fromSegaFormat(data);
        return palette;
    }
```

Add import at top of `Sonic3k.java`:
```java
import com.openggf.level.Palette;
```

- [ ] **Step 8: Implement in CrossGameFeatureProvider**

`CrossGameFeatureProvider` already implements `PlayerSpriteArtProvider` and already has `loadCharacterPalette(String)`. Verify it exists — if it does, no change needed. If the method signature differs from the new interface default, make it an `@Override`.

Edit `src/main/java/com/openggf/game/CrossGameFeatureProvider.java`. Add `@Override` annotation to the existing `loadCharacterPalette(String characterCode)` method (around line 204):

```java
    @Override
    public Palette loadCharacterPalette(String characterCode) {
```

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/data/PlayerSpriteArtProvider.java \
       src/main/java/com/openggf/game/sonic3k/Sonic3k.java \
       src/main/java/com/openggf/game/CrossGameFeatureProvider.java
git commit -m "feat: add loadCharacterPalette to PlayerSpriteArtProvider

Sonic3k returns Pal_SonicTails or Pal_Knuckles based on character code.
CrossGameFeatureProvider already had this — now annotated @Override."
```

#### Step Group C: Wire sidekick palette isolation into LevelManager

- [ ] **Step 10: Add sidekick palette context creation to the sidekick art init loop**

Edit `src/main/java/com/openggf/level/LevelManager.java`. In the sidekick art init loop (inside `initPlayerSpriteArt()`), after creating the `PlayerSpriteRenderer` and before `sidekickRenderer.ensureCached(graphicsManager)`, add palette isolation logic.

Find this section (after the SpriteArtSet creation from Task 2):
```java
                PlayerSpriteRenderer sidekickRenderer = new PlayerSpriteRenderer(sidekickArt);
                if (CrossGameFeatureProvider.isActive()) {
                    sidekickRenderer.setRenderContext(
                            CrossGameFeatureProvider.getInstance().getDonorRenderContext());
                }
```

Replace with:
```java
                PlayerSpriteRenderer sidekickRenderer = new PlayerSpriteRenderer(sidekickArt);
                // Palette isolation: if sidekick uses a different palette than main,
                // create a dedicated RenderContext so it renders with correct colors.
                RenderContext sidekickPaletteCtx = createSidekickPaletteContext(
                        artProvider, sidekickCharName, mainCharName);
                if (sidekickPaletteCtx != null) {
                    sidekickRenderer.setRenderContext(sidekickPaletteCtx);
                } else if (CrossGameFeatureProvider.isActive()) {
                    sidekickRenderer.setRenderContext(
                            CrossGameFeatureProvider.getInstance().getDonorRenderContext());
                }
```

Then add this new private method to `LevelManager`:

```java
    /**
     * Creates a sidekick palette context if the sidekick character uses a different
     * palette than the main character. Returns null if palettes are the same
     * (no isolation needed) or if the art provider doesn't support character palettes.
     */
    private RenderContext createSidekickPaletteContext(
            PlayerSpriteArtProvider artProvider,
            String sidekickCharName, String mainCharName) {
        if (sidekickCharName.equalsIgnoreCase(mainCharName)) {
            return null; // Same character — palette line 0 is correct
        }
        Palette sidekickPalette = artProvider.loadCharacterPalette(sidekickCharName);
        if (sidekickPalette == null) {
            return null; // Provider doesn't support per-character palettes
        }
        GameId gameId = (GameModuleRegistry.getCurrent() != null)
                ? GameModuleRegistry.getCurrent().getGameId()
                : null;
        RenderContext ctx = RenderContext.createSidekickContext(gameId);
        ctx.setPalette(0, sidekickPalette);
        return ctx;
    }
```

Add the necessary import at the top of `LevelManager.java`:
```java
import com.openggf.graphics.RenderContext;
```

(Check if `GameModuleRegistry` is already imported — it likely is. Also check `Palette` — also likely imported.)

- [ ] **Step 11: Propagate sidekick palette context to spindash dust and Tails tail renderers**

In the sidekick art init loop, after `initSpindashDust(sidekick)` and `initTailsTails(sidekick, sidekickArt)`, add:

```java
                // Propagate sidekick palette context to sub-renderers (dust, tail appendage)
                if (sidekickPaletteCtx != null) {
                    propagateSidekickPaletteContext(sidekick, sidekickPaletteCtx);
                }
```

Add the helper method:

```java
    /**
     * Applies a sidekick's palette context to its sub-renderers (spindash dust,
     * Tails tail appendage) so they also render with the correct palette.
     */
    private void propagateSidekickPaletteContext(AbstractPlayableSprite sidekick, RenderContext ctx) {
        if (sidekick.getSpindashDustController() != null
                && sidekick.getSpindashDustController().getRenderer() != null) {
            sidekick.getSpindashDustController().getRenderer().setRenderContext(ctx);
        }
        if (sidekick.getTailsTailsController() != null
                && sidekick.getTailsTailsController().getRenderer() != null) {
            sidekick.getTailsTailsController().getRenderer().setRenderContext(ctx);
        }
    }
```

Check that `SpindashDustController.getRenderer()` and `TailsTailsController.getRenderer()` exist. If not, add package-private or public getters. Search for these classes:

Likely in `src/main/java/com/openggf/sprites/playable/SpindashDustController.java` and `src/main/java/com/openggf/sprites/playable/TailsTailsController.java`. If the renderer field is private with no getter, add:

```java
    public PlayerSpriteRenderer getRenderer() {
        return renderer;
    }
```

to each controller class.

- [ ] **Step 12: Upload sidekick palettes to GPU**

In the `initPlayerSpriteArt()` method, after the sidekick loop, update the palette upload section. Currently it only uploads when `CrossGameFeatureProvider.isActive()`:

```java
        // Upload donor palettes to GPU if cross-game features are active
        if (CrossGameFeatureProvider.isActive()) {
            RenderContext.uploadDonorPalettes(graphicsManager);
        }
```

Change to always upload (the method is now a no-op when no contexts exist):

```java
        // Upload donor and sidekick palettes to GPU
        RenderContext.uploadDonorPalettes(graphicsManager);
```

- [ ] **Step 13: Verify null-safe GameId handling**

The `RenderContext` constructor stores `GameId` directly — verify it handles null safely (it does, since it's only used for diagnostics). No code change needed, just confirm by reading the constructor.

- [ ] **Step 14: Run full test suite**

Run: `mvn test -pl . -q`

Expected: PASS

- [ ] **Step 15: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java \
       src/main/java/com/openggf/sprites/playable/SpindashDustController.java \
       src/main/java/com/openggf/sprites/playable/TailsTailsController.java
git commit -m "feat: sidekick palette isolation via per-sidekick RenderContext

When a sidekick character uses a different palette than the main (e.g.,
Knuckles sidekick with Sonic main), create a dedicated RenderContext
with the sidekick's palette loaded. Propagate to dust/tail sub-renderers.
Works for both native S3K and cross-game donation."
```

---

### Task 4: Manual Verification

- [ ] **Step 1: Test native S3K — Sonic main, Knuckles sidekick**

Launch the engine with S3K ROM, configured for Sonic main + Knuckles sidekick. Verify:
- Sonic's sprite renders correctly (no corruption)
- Knuckles renders with red/green palette (not blue)
- Knuckles glides in from screen edge when respawning
- Knuckles drops with gravity after glide and lands
- Knuckles follows Sonic normally after landing

- [ ] **Step 2: Test native S3K — Knuckles main, Sonic sidekick**

Verify Sonic sidekick renders correctly with blue palette (not Knuckles' red).

- [ ] **Step 3: Test native S3K — Sonic main, Tails sidekick (regression)**

Verify existing Tails sidekick behavior is unaffected:
- Tails renders correctly
- Tails fly-in respawn works
- No sprite corruption

- [ ] **Step 4: Test cross-game — Knuckles from S3K donated into S2**

If cross-game feature provider supports this configuration, verify:
- Knuckles renders with correct palette
- No sprite corruption on the main character

- [ ] **Step 5: Test duplicate character — Sonic main, Sonic sidekick**

Verify both Sonics render correctly with isolated VRAM banks.
