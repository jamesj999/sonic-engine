# Player Sprite Progress (2025-12-22)

## Whats done
- Drafted the player sprite plan: `docs/sonic2_rev01_player_sprites_plan.md`.
- Identified Rev01 ROM offsets, mapping/DPLC formats, and base tile indices for Sonic/Tails.
- Defined the target architecture layers (ROM loader, dynamic pattern bank, renderer, animation bridge).

## Outstanding / Next options
1) Implement `Sonic2PlayerArt` (art + mapping + DPLC parsing) and add unit tests for frame counts and basic sanity checks.
2) Add `GraphicsManager.updatePatternTexture()` and `DynamicPatternBank` to support DPLC-style tile streaming.
3) Implement `PlayerSpriteRenderer` backed by `PatternSpriteRenderer`.
4) Wire Sonic/Tails sprites to the new renderer and add a minimal animation selector.
5) Add debug overlays or frame export to validate mappings vs. reference images.
