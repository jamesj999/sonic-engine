# HUD Mapping-Driven Refactor Design

## Goal

Replace the current procedural HUD static-piece rendering with a shared mapping-driven path that matches the ROM architecture across Sonic 1, Sonic 2, and Sonic 3&K:

- static HUD layout is drawn from sprite mappings
- flashing chooses among mapping frames
- numeric fields are updated separately from cached/dirty digit art

The immediate bug driver is the Sonic 3&K lives HUD, which currently mutates palette lines during draw and causes avoidable per-frame CPU and GPU churn.

## Problem Statement

The current engine HUD path in [HudRenderManager.java](../../../src/main/java/com/openggf/level/objects/HudRenderManager.java) mixes two concerns:

- layout and palette selection for static HUD pieces
- digit and timer rendering for dynamic values

This creates a non-ROM model for the lives HUD:

- static lives icon and name are assembled manually every frame
- palette selection is forced through `PatternDesc` choices
- Sonic 3&K donor/native mixed-palette lives art is compensated for by temporarily overriding shared palette lines during draw

That differs from the ROM behavior:

- S1 draws HUD mappings from `Map_HUD` and updates digit tiles only when flagged
- S2 draws HUD mappings from `hud_a.asm`/related HUD maps and updates digit tiles only when flagged
- S3K draws HUD mappings from `Map_HUD` and updates digit tiles only when flagged

In S2 and S3K, mixed palette use inside the HUD is encoded in the mapping pieces themselves, especially for the lives icon/name split. The engine currently flattens that distinction, then recreates it with runtime palette mutation.

## ROM Findings

### Shared Model

All three games follow the same broad pattern:

1. Static HUD sprites are rendered from mappings.
2. Flashing is expressed by switching mapping frames.
3. Digits are written into HUD VRAM only when dirty flags request it.

### Sonic 1

- `HUD_Update` updates score/rings/time/lives digit art only when flags are set.
- `HUD` object chooses mapping frames for flashing and draws `Map_HUD`.
- Native S1 keeps the lives HUD on one palette contract, so it has no native icon/name palette split problem.

### Sonic 2

- `HudUpdate` updates score/rings/time/lives digit art only when flags are set.
- `BuildHUD` renders mappings from `HUD_MapUnc_40A9A` and related tables.
- The lives icon and lives name use different palette indices in the mappings themselves.

### Sonic 3&K

- `UpdateHUD` updates score/rings/time/lives digit art only when flags are set.
- `Render_HUD` renders `Map_HUD`.
- `HUD_Lives` only writes the numeric lives digits.
- `Map_HUD` also encodes the icon/name palette split directly in the mapping words.

## Root Cause

The regression is a symptom of the wrong abstraction.

The engine currently treats the HUD as direct tile draws with game-selected palette descriptors. That model is already weaker than the ROM for S2/S3K because HUD palette composition is partly per-piece, not per-draw-call.

The recent Sonic 3&K lives HUD fixes then stacked a runtime palette override onto that model:

- provider recomputes a lives override palette on demand
- `HudRenderManager.drawLives()` applies the override during draw
- the HUD flushes and restores the original palette after the draw

This adds per-frame work because the engine is solving a layout/palette encoding problem through shared palette mutation.

## Design

### 1. Shared Static HUD Mapping Path

Introduce shared mapping-driven rendering for static HUD pieces:

- score/time/rings labels
- lives icon/name/X marker
- flash-state variants

`HudRenderManager` should consume HUD mapping frames rather than manually placing each tile for these pieces.

The mapping path must preserve per-piece palette bits exactly as stored in the mapping data.

### 2. Keep Dynamic Digit Updates Separate

Preserve the existing split between static HUD layout and dynamic numeric values:

- score digits
- rings digits
- time digits
- lives digits
- debug hex digits

These should continue to use cached numeric conversion and direct pattern rendering, with later cleanup available for timer-string allocation if desired.

### 3. Provider-Supplied HUD Mapping Data

Extend `ObjectArtProvider` to expose HUD mapping data needed by the shared HUD renderer.

At minimum, providers should expose:

- normal HUD mapping frames
- flash-state mapping frames or enough structured variants to select them
- lives/static frames needed by the runtime HUD

The provider remains responsible for game-specific data extraction from ROM or asm includes.

### 4. Preserve Piece Palette Semantics

The shared HUD renderer must use rendering primitives that keep each mapping piece's palette index intact.

This is already supported by:

- `SpriteMappingFrame`
- `SpriteMappingPiece`
- `PatternSpriteRenderer` / `SpritePieceRenderer`

The new HUD path should reuse that behavior instead of inventing another HUD-only palette interpretation.

### 5. Remove Runtime Lives Palette Override from the Main Path

Once static HUD lives rendering is mapping-driven, remove the current shared runtime palette override mechanism from the main gameplay HUD path:

- `usesIconPaletteForLivesName()`
- `getHudLivesPaletteOverride()`
- `HudRenderManager` palette override supplier logic

Those methods exist to compensate for the current procedural HUD model and should not survive as the primary solution.

If any cross-game donation edge case still truly requires preprocessing, the correct place is provider-side art normalization or mapping normalization during load, not palette swapping during frame draw.

### 6. Keep Shared Direction Across S1/S2/S3K

Apply the mapping-driven static HUD design as a shared direction:

- S3K gets the regression fix and correct ROM-style palette handling
- S2 gains better parity because its mixed icon/name palette split is also mapping-based in ROM
- S1 fits the same architecture cleanly even though its native lives HUD is simpler

This avoids a special-case S3K HUD stack.

The target should still be native per-game behavior wherever the source data supports it. If the shared HUD path deliberately normalizes some host or donor combinations around an S3K-style lives mapping contract, that choice should be treated as an explicit architectural discrepancy against the affected ROMs rather than an assumed equivalence.

## Implementation Outline

1. Extend `ObjectArtProvider` with shared HUD mapping accessors.
2. Load or construct HUD mapping frames for S1, S2, and S3K providers from disassembly-backed data.
3. Refactor `HudRenderManager` so static HUD pieces render from mapping frames.
4. Keep digit rendering and debug hex rendering as the dynamic sub-path.
5. Remove the per-frame lives palette override path once mapping-driven lives rendering is in place.
6. Add/update tests for:
   - S3K mixed icon/name palettes without palette mutation
   - S2 mixed icon/name palettes preserved through mappings
   - S1 flashing frame selection still correct
   - no palette uploads during steady-state HUD draw for native S3K lives HUD
7. Update [KNOWN_DISCREPANCIES.md](../../../docs/status/known-discrepancies.md) only for intentional end-state ROM deviations. If the final shared HUD path deliberately standardizes on an S3K-style lives mapping/palette contract for any S1 or S2 cases, document those resulting behavior differences against the native ROMs. Do not use that document to record the removed palette-override bug.

## Documentation Plan

Use `docs/status/known-discrepancies.md` only for the final architectural result, not for transient bugs or superseded implementation details.

For this refactor, that means:

- If the shared HUD path remains natively accurate for each game, no HUD entry belongs in `KNOWN_DISCREPANCIES.md`.
- If the final design intentionally aligns some S1 or S2 HUD behavior with an S3K-style shared contract, add an entry describing the exact end-user-visible differences from the affected ROMs and why the shared architecture chose that trade-off.
- The current per-frame palette-override issue should not be documented there because it is a defect being removed, not an intentional architectural deviation.

## Testing Strategy

- Unit tests for provider-loaded HUD mapping palette semantics.
- `HudRenderManager` tests verifying steady-state draws no longer upload/restore palettes for native S3K lives.
- Regression tests for flash-frame selection and lives rendering in S1/S2/S3K.
- Existing debug hex-digit tests remain valid and should continue to pass.

## Risks

- S1, S2, and S3K use different source formats for HUD mappings; the provider API must stay small enough to avoid exploding into per-game special cases.
- Cross-game donated life-icon/name art may still need load-time normalization if its mapping contract differs from the native host game.
- The HUD renderer should not become a second generic sprite renderer; keep the scope limited to HUD-specific static mapping selection plus existing digit draws.

## Recommendation

Proceed with a shared mapping-driven static HUD refactor, starting from the lives/icon path that currently causes the regression, then fold score/time/rings label rendering into the same structure.

This is the cleanest path because it removes the palette churn by adopting the ROM's representation instead of optimizing around a mismatched one.
