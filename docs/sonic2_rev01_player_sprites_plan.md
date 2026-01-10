# Sonic 2 (Genesis) Rev01 player sprites (Sonic/Tails) plan

This document is meant to be handed to an AI coding agent (Codex, etc.) so it can add accurate Sonic/Tails sprite loading and rendering to the engine using the Rev01 ROM. The immediate goal is to render player sprites from ROM art + mappings + DPLCs while keeping the architecture clean and reusable.

## Scope and success criteria

Milestone 1: The engine can decode Sonic and Tails art, mappings, and DPLCs from a Rev01 ROM and render a single, fixed frame in-game using the correct palette and tile data.

Milestone 2: The engine updates DPLC-driven tiles when the mapping frame changes, producing correct-looking animated sprites.

Milestone 3: The engine integrates a minimal animation selector (idle/run/jump) for Sonic and Tails based on the playable sprite state.

Milestone 4: Rendering is within a few pixels of the original game and can be validated against reference screenshots.

## Primary reference sources (use these as ground truth)

The offsets and formats below are based on community references and the Sonic 2 split disassembly. Always validate offsets against the Rev01 disassembly build (gameRevision = 1).

Suggested references:
- Sonic Retro (Sonic 2 disassembly and asset notes)
- Clownacy's Corner (Sonic 2 mapping format details)
- s2disasm (Rev01 offsets and constants)

## ROM asset locations (Rev01, no header)

| Asset | Purpose | ROM offset (hex) | Size (hex) | Notes |
| --- | --- | --- | --- | --- |
| ArtUnc_Sonic | Sonic raw tile art (uncompressed) | 0x50000 | 0x14320 | 0x14320 / 0x20 = 2585 tiles |
| ArtUnc_Tails | Tails raw tile art (uncompressed) | 0x64320 | 0x0B8C0 | 0x0B8C0 / 0x20 = 1478 tiles |
| MapUnc_Sonic | Sonic sprite mappings | 0x6FBE0 | ends at 0x714E0 | frame composition data |
| MapRUnc_Sonic | Sonic DPLCs | 0x714E0 | ends at 0x71D8E | per-frame tile streaming |
| MapUnc_Tails | Tails sprite mappings | 0x739E2 | ends at 0x7446C | frame composition data |
| MapRUnc_Tails | Tails DPLCs | 0x7446C | continues | next known offset 0x74CF6 |

Sanity checks:
- First word of MapUnc_Sonic is 0x01AC -> 0x01AC / 2 = 214 frames.
- First word of MapUnc_Tails implies 139 frames (same offset-table layout).

## Data formats to implement

### Tile art (ArtUnc_*)

- 8x8 tiles, 4bpp, 32 bytes per tile.
- Pixel values are palette indices (0-15), index 0 is transparent for sprites.
- Use existing Pattern.fromSegaFormat to decode into Pattern[].

### Sprite mappings (MapUnc_*)

Sonic 2 uses a mapping format with padding for 2-player mode.

Per frame:
- Offset table first (word offsets relative to base).
- Frame block: word piece_count, then piece_count pieces.

Each piece is 8 bytes:
1) y (signed byte)
2) size (byte, VDP size encoding)
3) tile_word_1P (word)
4) tile_word_2P (word) (ignored for 1P)
5) x (signed word)

Tile word bits:
- bits 0-10: tile index
- bit 11: hFlip
- bit 12: vFlip
- bits 13-14: palette line
- bit 15: priority (often handled elsewhere)

Size encoding:
- widthTiles = ((size >> 2) & 0x3) + 1
- heightTiles = (size & 0x3) + 1

### Facing + slope flips (Sonic_Animate parity)

- `status.player.x_flip` drives base facing (left/right) and should be set by the movement logic (Sonic_MoveLeft/Right), not by raw x-velocity.
- For walk/run on slopes, the original `Sonic_Animate` computes an angle-based flip: when the "upside-down" bit is set, both X and Y render flips are enabled, and the X flip is effectively inverted relative to facing.
- Mirror this by letting the animation manager adjust render flips from `angle` + `status.player.x_flip`, instead of re-deriving facing from screen-space movement.

### DPLCs (MapRUnc_*)

Per character:
- Offset table first (word offsets relative to base).
- Frame block: word request_count, then request_count entries.
- Each entry packs tile count and source tile offset (nibble count minus 1 + 12-bit offset).

Recommended decode:
- count = ((entry >> 12) & 0xF) + 1
- srcTile = entry & 0x0FFF

## Rendering approach (recommended)

Use a small VRAM-like tile bank for each character (Sonic/Tails). This is equivalent to the original DPLC flow but implemented with engine-friendly abstractions.

Approach B (recommended):
1) Maintain a DynamicPatternBank (array of Pattern slots, mapped to fixed pattern IDs).
2) When mapping_frame changes, apply DPLC requests and overwrite the bank slots from ArtUnc_*.
3) Render the mapping frame by indexing into the bank.

Approach A (fallback for early validation):
- Build a per-frame "virtual VRAM list" by concatenating requested tile runs from ArtUnc_* and render directly from that list.

## GPU palette management (shader extension)

The existing shader (`src/main/resources/shaders/shader_the_hedgehog.glsl`) already performs indexed-color lookup into a palette texture. We can extend this to support per-sprite palette lines and optional effects without changing pattern data.

Recommended changes:
1) Store all 4 Mega Drive palette lines in a single 16x4 RGBA texture (instead of 1x16).
2) Add a `PaletteLine` uniform (0-3) to the fragment shader and sample:
   - `paletteX = (index + 0.5) / 16.0`
   - `paletteY = (PaletteLine + 0.5) / 4.0`
3) Keep flips/priority on CPU, but leave palette lookup on GPU.
4) Optional: add uniforms for tint/flash to implement invincibility or water effects.

Implementation hooks:
- `GraphicsManager.cachePaletteTexture(...)` should upload a 16x4 palette texture.
- `PatternRenderCommand` (or equivalent) should pass palette line as a uniform.
- This integrates cleanly with DPLC streaming since texture IDs stay fixed and only tile contents change.

## Architecture changes

### Data layer

Add a loader similar to Sonic2RingArt:
- Sonic2PlayerArt (new)
  - loadSonic()
  - loadTails()
  - returns PlayerArtSet with art tiles, mapping frames, dplc frames

Suggested new types:
- PlayerArtSet
- PlayerMappingFrame / PlayerMappingPiece (implement SpriteFramePiece)
- PlayerDplcFrame / PlayerDplcRequest

### Render layer

Add new render helpers that reuse PatternSpriteRenderer:
- DynamicPatternBank: owns a contiguous range of pattern IDs and updates their textures.
- PlayerSpriteRenderer: draws a mapping frame at (x,y) using PatternSpriteRenderer.

GraphicsManager change:
- Add updatePatternTexture(patternId, Pattern) using glTexSubImage2D.

### Sprite layer

Playable sprites should own:
- mappingFrame
- PlayerSpriteRenderer
- optional AnimationManager that computes mappingFrame based on state

Sonic and Tails draw() should call renderer.drawFrame(mappingFrame, xPixel, yPixel).

## Implementation steps

1) ROM parsing
   - Implement Sonic2PlayerArt loader with offsets above.
   - Parse mapping frames and DPLC frames into strongly typed objects.

2) Dynamic bank + renderer
   - Add DynamicPatternBank + GraphicsManager.updatePatternTexture().
   - Implement PlayerSpriteRenderer (DPLC apply on frame change).

3) Sprite integration
   - Add renderer to Sonic and (new) Tails sprite.
   - Map base pattern indices (Sonic 0x0780, Tails 0x07A0) to bank slots.

4) Animation bridge
   - Add minimal PlayableSpriteAnimationManager that maps state -> mappingFrame.
   - Start with a small subset (idle/run/jump) and expand later.

5) Validation
   - Visual debug overlay to draw bounding boxes for mapping pieces.
   - Optional export to PNG for a few known frames.

## Verification plan

Recommended tests (ROM-dependent, skip if ROM missing):
- Assert first word of MapUnc_Sonic / MapUnc_Tails yields expected frame counts.
- Assert DPLC frame counts match mapping frame counts.
- Assert a handful of frames contain the expected number of pieces.
- Smoke test: render a fixed frame at a known coordinate and compare output visually.

## Notes on palette and base tile indices

Base tile indices from disassembly:
- Sonic: 0x0780
- Tails: 0x07A0

These map to VRAM addresses (tile_index * 32). In the engine, map these to a contiguous pattern ID range used by DynamicPatternBank.

## Next steps after initial sprite rendering

- Full animation state machine parity with the original (all actions and transitions).
- Per-piece priority handling and render ordering.
- 2-player mappings if ever needed (use tile_word_2P).
- Dynamic palette swaps for invincibility and underwater effects.
