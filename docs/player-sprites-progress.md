# Player Sprite Progress (2025-12-22)

## Whats done
- Drafted the player sprite plan: `docs/sonic2_rev01_player_sprites_plan.md`.
- Identified Rev01 ROM offsets, mapping/DPLC formats, and base tile indices for Sonic/Tails.
- Implemented core sprite-art abstractions: mapping frames/pieces, DPLC frames, tile load requests, and `SpriteArtSet`.
- Added `DynamicPatternBank` and GPU-side pattern updates (`GraphicsManager.updatePatternTexture`).
- Implemented `Sonic2PlayerArt` loader and `PlayerSpriteArtProvider` integration in `Sonic2`.
- Added `PlayerSpriteRenderer` and wired it into `AbstractPlayableSprite` + `LevelManager`.
- Added `PlayableSpriteAnimationManager` with scripted animation playback and a scripted velocity selector.
- Added Sonic animation script parsing from ROM (SonicAniData at 0x01B618, 34 scripts).
- Added ROM-dependent tests for Sonic/Tails mapping/DPLC frame counts.
- Added optional `Tails` playable sprite and engine selection by main character code.
- Extended shader + palette handling to support per-sprite palette lines.

## Outstanding / Next options
1) Add debug overlays or frame export to validate mappings vs. reference images.
2) Replace placeholder animation profile values with accurate Sonic 2 frame indices.
3) Extend palette shader to support optional tint/flash uniforms (invincibility, underwater).
