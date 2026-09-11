# S1 Donated Data Select Preview Fixes

## Goal

Fix three regressions in Sonic 1 donated Data Select previews:

1. Captured preview images currently include HUD/lives/UI text.
2. Captured preview framing uses spawn as screen center, which can expose out-of-bounds space at level start.
3. Donated S3K Data Select still shows native S3K selected-slot imagery for S1 saves instead of the generated S1 preview images.

## Recommended Approach

Implement a dedicated hidden S1 preview render path and route S1 host previews through it explicitly.

This keeps preview rendering as a first-class host-owned concern rather than layering more one-off conditions into the donated S3K frontend.

## Design

### 1. S1 Preview Framing

The S1 preview capture point semantics will be:

- `x`: left edge of the gameplay viewport
- `y`: vertical center of the gameplay viewport

Default S1 framing will be derived from the spawn point:

- `captureLeftX = spawnX`
- `captureCentreY = spawnY`

Camera placement for preview generation will become:

- `cameraX = captureLeftX`
- `cameraY = captureCentreY - 96`

Before rendering, the preview camera position must be clamped to the loaded level bounds so the captured image never includes out-of-bounds space solely because the spawn is near the start of the level.

The existing override map remains code-owned and empty for now. Future overrides will use the same semantics: left-edge `x`, center `y`.

### 2. Hidden Preview Render Mode

S1 preview generation will render through a dedicated hidden capture mode layered onto the normal level render pipeline.

The capture mode must:

- render terrain
- render objects
- suppress HUD
- suppress player sprite rendering
- suppress ring rendering
- suppress title-card and other screen-space overlays

The capture mode does not need to remove normal level objects. Objects may remain visible.

This should be implemented as an explicit render option/contract, not as post-processing of the captured PNG.

### 3. Donated S1 Host Preview Routing

S1 donated host previews must stop resolving as `TEXT_ONLY`.

`S1DataSelectProfile.resolveSlotPreview(...)` will instead resolve an image-backed preview for valid S1 payloads. The preview key should be based on the host zone so the donated S3K presentation can request the corresponding generated S1 PNG.

The donated S3K asset source will gain S1 host-preview support alongside the existing S2 host-preview path:

- when `hostGameCode == "s1"`, load generated PNG-backed preview assets from `saves/image-cache/s1/`
- expose those assets through the selected-slot preview interface used by the donated S3K save screen
- do not fall back to native S3K selected-slot imagery when a valid S1 preview image exists

If the S1 cache is missing or invalid, the existing non-image fallback may remain in place rather than breaking the screen.

## Error Handling

- If S1 preview generation fails, donated Data Select must still load and may fall back to non-image host preview behavior.
- If an expected S1 PNG is missing or undecodable, treat the S1 image preview as unavailable for that slot and fall back cleanly.
- Cache invalidation rules remain unchanged: engine version, generator format version, ROM SHA-256, and override flag all continue to force regeneration.

## Testing

Add or update tests for:

- S1 preview framing semantics: spawn maps to left-edge `x` and center `y`
- capture render path suppresses HUD/player/rings while still allowing objects
- S1 donated host preview resolution returns image-backed preview metadata rather than `TEXT_ONLY`
- donated S3K asset loading prefers S1 cached PNG-backed preview assets when host game is S1
- fallback behavior when S1 cached preview data is unavailable

## Scope Notes

This work is intentionally limited to donated S1 previews on the S3K Data Select frontend. It does not change native S1 level select behavior and does not change the broader S3K selected-slot rendering contract beyond adding the missing S1 host-owned image path.

## Implementation Outcome

The implemented branch keeps runtime-generated host previews on palette line `2` and relies on immediate per-layer flushes in `S3kDataSelectRenderer` so selected-preview palette uploads cannot leak forward and corrupt later emerald rendering.
