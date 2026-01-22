# GPU Optimization Plan

Date: 2026-01-22
Owner: TBD
Status: Draft

## Goals
- Reduce CPU overhead from per-tile draw calls while preserving ROM-accurate visuals.
- Keep deterministic, pixel-perfect output (no filtering, no sub-pixel drift).
- Preserve existing render ordering (priority, sprites, rings, HUD, fade).
- Make changes incrementally to minimize regressions.

## Non-goals
- Rewriting physics or gameplay logic.
- Changing palette/VDP rules or sprite priority behavior.
- Introducing post-processing effects beyond what exists today.

## Constraints / Accuracy Requirements
- 8x8 pattern fidelity, nearest-neighbor sampling, no texture filtering.
- Palette index 0 must remain transparent.
- Correct priority layering (low/high, sprites, objects, rings, HUD).
- Preserve existing scanline H-scroll and water distortion behavior.

## Current Rendering Summary (Baseline)
- Each pattern is uploaded as its own GL texture (GL_RED 8x8).
- Fragment shader looks up palette using `Palette` + `PaletteLine`.
- Foreground/background tiles are drawn on CPU and issued as per-tile commands.
- GPU shaders already handle:
  - Parallax per-scanline scroll (background FBO + shader)
  - Water distortion
  - Fade overlays
  - Shadow/highlight mode

Key hotspots:
- `LevelManager.drawLayer()` -> per-chunk/per-pattern loops
- `GraphicsManager.renderPatternWithId()` -> per-pattern command
- `BatchedPatternRenderer` still issues `glDrawArrays` per tile

## Track A: Atlas + VBO/Instancing (Incremental, High Impact)

### Overview
Replace one-texture-per-pattern with a texture atlas (or texture array), batch all tiles
into a single VBO per layer, and supply per-tile palette line via vertex data. This keeps
existing CPU tilemap traversal but collapses draw calls and state changes.

### Phase A1: Texture Atlas (Patterns)
1. Create a pattern atlas texture:
   - Layout patterns in a grid (e.g., 256x256 or 512x512 pixels, 8x8 tiles).
   - Compute UVs per pattern.
   - Store all patterns in a single GL texture (GL_R8 or GL_RED).
2. Replace `patternTextureMap` with atlas metadata:
   - `patternId -> atlasUV` mapping (u0,v0,u1,v1) or `(atlasX, atlasY)`.
3. Keep palette texture as-is (combined 16x4 RGBA).

Affected files:
- `src/main/java/uk/co/jamesj999/sonic/graphics/GraphicsManager.java`
- new atlas helper (e.g. `graphics/PatternAtlas.java`)

### Phase A2: VBO-backed Batch Renderer
1. Replace per-tile `glDrawArrays` with VBOs:
   - Build a single interleaved buffer per layer with:
     - position (x,y)
     - atlas UV (u,v)
     - paletteLine (float or normalized byte in color/texcoord)
   - One `glDrawArrays` call per layer (or per priority pass).
2. Introduce a minimal vertex shader to pass paletteLine to fragment shader.
3. Update fragment shader to use interpolated paletteLine per-fragment.

Notes:
- Because all tiles use one atlas texture, there are no per-tile texture binds.
- `PaletteLine` uniform becomes a per-vertex attribute.

Affected files:
- `BatchedPatternRenderer` (replace with VBO batcher)
- `ShaderProgram` to support vertex+fragment shaders
- `shader_the_hedgehog.glsl` split into vertex+fragment or add a vertex shader

### Phase A3: Sprite/Ring/Object Instancing
1. Replace per-tile sprite drawing with instancing:
   - Each sprite piece becomes one instance with position, atlasUV, paletteLine, flip.
   - Draw all sprite pieces per priority bucket in one call.
2. Use a small per-instance buffer or `glVertexAttribDivisor` (if available in JOGL).

Affected files:
- `PatternSpriteRenderer`, `SpritePieceRenderer`, sprite render paths

### Validation
- Compare screenshot hashes before/after for:
  - Foreground/Background layers
  - Sprite-heavy scenes
  - Water zones
- Ensure palette index 0 remains transparent and shadow mode unchanged.

### Risks
- Atlas size limits on older GPUs (mitigate via multiple atlases or texture arrays).
- Off-by-one UV sampling; must sample texel centers (like special stage strips).

### Rollback Strategy
- Keep old render path behind feature flag (config toggle).

---

## Track B: Full GPU Tilemap Shader (Bigger Change, Largest CPU Savings)

### Overview
Move tilemap evaluation fully to GPU. Upload tile descriptors (pattern index, flip,
palette, priority) to a tilemap texture. Shader samples the descriptor per pixel, then
samples atlas + palette. CPU only uploads tilemap updates and H-scroll data.

### Phase B1: Tile Descriptor Texture
1. Define a compact tile descriptor:
   - 11-bit pattern index, 2-bit palette, H/V flip, priority bit.
2. Pack descriptor into RGBA8 (or RG16UI with integer sampling if supported):
   - Example RGBA8:
     - R: lower 8 bits of pattern index
     - G: upper bits + palette + flip + priority
3. Upload tilemap as a 2D texture (tile grid size: map width x map height).

Affected files:
- new `TilemapTexture` class
- `LevelManager` (upload on level load; update on animated patterns)

### Phase B2: Tilemap Fragment Shader
1. Shader computes tile coordinate from screen pixel + camera offset.
2. Sample descriptor, decode pattern index + flip + palette.
3. Compute atlas UV for the 8x8 tile.
4. Sample atlas (indexed texture), then sample palette.
5. Apply priority pass by discarding based on priority bit.

Two-pass approach:
- Pass 1: low priority only
- Pass 2: high priority only

### Phase B3: Integrate H-scroll + Water + Parallax
1. Replace CPU tile pass in `renderBackgroundShader()`:
   - Apply per-scanline H-scroll offsets directly in shader.
2. For water distortion, keep water shader as a post-pass or integrate into
   the tilemap shader for FG/background.

### Phase B4: Sprite/Objects
- Sprites can remain in Track A's instanced path.
- Tilemap shader only covers background/foreground layers.

### Validation
- Compare pixel output vs current pipeline at:
  - Scroll boundaries
  - Vertical wrap zones
  - H-scroll and water zones
- Ensure priority split matches original ordering.

### Risks
- Shader complexity and integer decoding precision.
- Shader derivatives on older GL2.1 hardware (stick to GLSL 110 rules).

### Rollback Strategy
- Keep CPU tile path selectable via config flag.

---

## Suggested Milestones
1. **Milestone 1 (Atlas + VBO)**
   - Pattern atlas + VBO batch for tilemap.
   - Feature flag + A/B comparison.

2. **Milestone 2 (Sprite Instancing)**
   - Replace sprite tile loops with instanced draw.

3. **Milestone 3 (Full Tilemap Shader)**
   - GPU tilemap for background (FG later).

   - NOTE: Keep `GPU_TILEMAP_ENABLED` as a fallback toggle during verification,
     then flip default to true and remove the flag after parity validation.

4. **Milestone 4 (Unification)**
   - Consolidate shaders, remove legacy paths if stable.

## Test/Validation Checklist
- Unit: render order recorder, collision overlays unaffected.
- Visual: snapshot comparison per zone (EHZ, CPZ, ARZ).
- Performance: measure frame time with profiler, look for CPU drops.

## Open Questions
- Max atlas size supported by target GPUs?
- JOGL support for texture arrays / integer samplers in current profile?
- Any constraints from special stage renderer path?

## Ownership & Next Steps
- Decide Track A vs B to prototype first.
- Add feature flags in `SonicConfiguration`.
- Build a minimal prototype and capture before/after screenshots.
