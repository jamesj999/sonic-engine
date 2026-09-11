# GPU Optimization Plan (Updated)

Date: 2026-01-22
Owner: TBD
Status: Active

## Goals
- Reduce CPU overhead from per-tile/per-sprite draw submission.
- Preserve pixel-perfect, ROM-accurate output.
- Keep render ordering and priority rules identical.

## Implemented (Already in Code)
- Pattern atlas for indexed 8x8 patterns (`PatternAtlas`).
- Batched pattern rendering path (`BatchedPatternRenderer`), plus strip patterns.
- VBO-backed batch uploads + command pooling (no client-side arrays per batch).
- Sprite piece instancing with per-instance attributes (`InstancedPatternRenderer`).
- GPU tilemap rendering for BG/FG (`TilemapGpuRenderer`, `shader_tilemap.glsl`).
- Background FBO + per-scanline parallax (`BackgroundRenderer`).
- Water distortion shader (`WaterShaderProgram`).
- Shadow/highlight shader (`shader_shadow.glsl`).
- Fade shader (`FadeManager`).
- Shared fullscreen quad VBO (tilemap, parallax, fade, special stage).
- Atlas/palette upload buffer reuse (no per-update allocations).
- Multi-atlas fallback (up to 2 atlases) for pattern caching.

## Current Hotspots
- None identified in the original GPU plan scope.

## Next Steps (Ordered)
- None in the original GPU plan scope.

## Validation
- Pixel diffs on representative scenes (EHZ/CPZ/ARZ, water zones, special stage).
- Render order checks via `RenderOrderRecorder`.
- Frame time + GC tracking before/after.

## Risks
- VBO pointer offsets on older GL2 drivers.
- Off-by-one sampling; must keep texel-center rules intact.
- Instancing path must preserve priority ordering and transparency rules.
