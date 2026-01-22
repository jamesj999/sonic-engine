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
- GPU tilemap rendering for BG/FG (`TilemapGpuRenderer`, `shader_tilemap.glsl`).
- Background FBO + per-scanline parallax (`BackgroundRenderer`).
- Water distortion shader (`WaterShaderProgram`).
- Shadow/highlight shader (`shader_shadow.glsl`).
- Fade shader (`FadeManager`).
- Shared fullscreen quad VBO (tilemap, parallax, fade, special stage).

## Current Hotspots
- Sprite/object/ring rendering still expands to per-tile submits on CPU.
- Batched renderer uses client-side arrays and per-batch snapshots.
- Immediate-mode fullscreen quads (tilemap, parallax, fade, special stage).

## Next Steps (Ordered)
1) **Sprite piece instancing (optional, larger)**  
   Move sprite pieces to GPU instancing with per-instance attributes:
   position, atlas UV or pattern index, palette line, flip bits.
   Requires a minimal vertex shader and `glVertexAttribDivisor`.

2) **Atlas/palette upload buffering**  
   Use a small direct-buffer pool or PBOs for animated pattern uploads to reduce
   driver stalls.

3) **Multi-atlas / texture array fallback**  
   If atlas capacity is exceeded, support multiple atlases or texture arrays.

## Validation
- Pixel diffs on representative scenes (EHZ/CPZ/ARZ, water zones, special stage).
- Render order checks via `RenderOrderRecorder`.
- Frame time + GC tracking before/after.

## Risks
- VBO pointer offsets on older GL2 drivers.
- Off-by-one sampling; must keep texel-center rules intact.
- Instancing path must preserve priority ordering and transparency rules.
