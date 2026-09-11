# S3K Sidekick Knuckles Fixes

Three bugs when Knuckles is the sidekick alongside Sonic (main character) in S3K, affecting both native S3K and cross-game modes.

## Bug 1: Sonic Sprite Corruption (VRAM Collision)

### Root Cause

`ART_TILE_KNUCKLES = ART_TILE_SONIC = 0x0680` in `Sonic3kConstants`. On original hardware only one character occupies this VRAM slot at a time, but the engine supports arbitrary sidekick combinations.

`computeVramSlots()` in `LevelManager` keys on character *name* to detect conflicts. "sonic" != "knuckles" yields slot 0 (no isolation), so both characters' DPLC updates write to the same `DynamicPatternBank` region — each frame's tile load clobbers the other character's tiles.

### Fix: Always Shift Sidekicks to SIDEKICK_PATTERN_BASE

Remove the `computeVramSlots()` optimization entirely. Every sidekick unconditionally gets its own bank at `SIDEKICK_PATTERN_BASE (0x38000) + running offset`.

**Files:**
- `LevelManager.java` — Remove `computeVramSlots()` method. Remove the `if (slot > 0)` conditional in the sidekick art init loop. Every sidekick always gets `shiftedBase = SIDEKICK_PATTERN_BASE + sidekickBankOffset`.
- The art cache (keyed by character name) remains for avoiding redundant ROM reads, but each sidekick instance gets its own shifted `SpriteArtSet` created fresh — the cache provides source data only.

**Why this is safe:** The virtual pattern ID space (0x38000+) is vast, and `renderPatternWithId()` handles the full 32-bit range via `PatternAtlas` sparse lookup. There is no real cost to always shifting — the "natural slot" optimization only saved virtual ID space we have plenty of.

## Bug 2: Blue Knuckles (Palette Isolation)

### Root Cause

All characters hardcode `paletteIndex = 0` in their `SpriteArtSet` (set in `Sonic3kPlayerArt.loadSonic()`, `loadKnuckles()`, `loadTails()`). Palette line 0 is loaded with whichever character is *main* — when Sonic is main, palette 0 has Sonic's blue colors. Knuckles sidekick's `PlayerSpriteRenderer` reads palette 0 via `artSet.paletteIndex()` and renders blue.

No sidekick palette isolation exists in native S3K mode. The cross-game `RenderContext` system provides palette isolation for donor characters, but only when `CrossGameFeatureProvider` is active.

### Fix: Per-Sidekick RenderContext for Palette Isolation

Extend the existing `RenderContext` system to support sidekick palette isolation within the same game.

**Files:**

- **`RenderContext.java`** — Add `createSidekickContext(GameId gameId)` static factory method. Allocates a palette line block from the same `nextPaletteBase` counter as donor contexts. Unlike `getOrCreateDonor()`, this does NOT cache by `GameId` — each call returns a fresh `RenderContext` so multiple sidekicks can each have their own palette block. The existing private constructor and `getEffectivePaletteLine()` remapping work unchanged. The `GameId` parameter is passed to the constructor for diagnostic/logging purposes only.

- **`LevelManager.java` (sidekick art init loop)** — After creating the sidekick's `PlayerSpriteRenderer`:
  1. Determine whether palette isolation is needed (sidekick character palette differs from main character palette).
  2. Load the sidekick's character palette from ROM.
  3. Create a sidekick `RenderContext` via `createSidekickContext()`.
  4. Set the character palette into line 0 of that context.
  5. Set the context on the sidekick's `PlayerSpriteRenderer` (overriding any donor context).
  6. Propagate to spindash dust and Tails tail renderers for that sidekick.
  7. Upload the sidekick palette to GPU alongside donor palettes.

- **Palette source resolution** — The game module needs to provide character-specific palette addresses:
  - Native S3K: `Sonic3kConstants.SONIC_PALETTE_ADDR` vs `KNUCKLES_PALETTE_ADDR`, resolved by character code.
  - Cross-game: `CrossGameFeatureProvider.loadCharacterPalette(charCode)` already handles this.
  - Add a `loadCharacterPalette(String charCode)` method to the player art provider interface or game module, abstracting the palette address lookup.

- **Priority layering** — When `CrossGameFeatureProvider` is active:
  - Main character gets the donor `RenderContext` (existing behavior).
  - Sidekick that needs different colors gets a sidekick `RenderContext` with its own palette loaded. This takes precedence over the donor context for the sidekick's renderer.

## Bug 3: Knuckles Stuck in Mid-Air (Physics Skipped During Drop)

### Root Cause

`KnucklesRespawnStrategy` has two phases:
1. **Glide phase** (`dropping = false`): Manually positions the sprite each frame — horizontal movement toward leader + shallow descent.
2. **Drop phase** (`dropping = true`): Stops manual positioning, expects the physics engine to apply gravity until landing.

`SpriteManager` checks `isApproaching() && !requiresPhysics()` to decide whether to skip physics for the sidekick. `SidekickRespawnStrategy.requiresPhysics()` defaults to `false`. So during the drop phase, the sprite is still in `APPROACHING` state, `requiresPhysics()` is still `false`, and physics are skipped entirely — gravity never applies, Knuckles hangs in mid-air.

### Fix: State-Aware requiresPhysics()

Override `requiresPhysics()` in `KnucklesRespawnStrategy` to return the `dropping` field.

**Files:**

- **`KnucklesRespawnStrategy.java`**:
  1. Override `requiresPhysics()` → `return dropping;`
  2. Resolve `GLIDE_DROP` animation ID in the constructor via `GameModuleRegistry.getCurrent().resolveAnimationId(CanonicalAnimation.GLIDE_DROP)`.
  3. Set forced animation to `GLIDE_DROP` during `beginApproach()` and maintain it during the glide loop (matching `TailsRespawnStrategy`'s pattern with `flyAnimId`).
  4. When drop triggers, clear forced animation (`setForcedAnimationId(-1)`) — already present in current code.

- **`SpriteManager.java`** — No changes needed. The existing check handles both phases correctly:
  - Glide: `isApproaching() && !requiresPhysics()` → `true && !false` → skip physics (manual positioning)
  - Drop: `isApproaching() && !requiresPhysics()` → `true && !true` → run physics (gravity applies)

## Testing

- Verify Sonic sprite renders correctly when Knuckles is sidekick in native S3K.
- Verify Knuckles renders with red/green palette (not blue) in both native S3K and cross-game.
- Verify Knuckles glide-in respawn completes: glides from screen edge, drops with gravity, lands, transitions to NORMAL follow behavior.
- Verify existing Tails sidekick behavior is unaffected by the always-shift VRAM change.
- Verify Sonic-as-sidekick (duplicate character) still works with isolated VRAM bank.
- Verify cross-game Knuckles (S3K Knuckles donated into S2) palette isolation still works.
