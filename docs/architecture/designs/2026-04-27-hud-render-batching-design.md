# HUD Render Batching Design

## Context

RenderDoc captures show many small `glDrawArrays` calls in HUD-related rendering. The normal gameplay HUD (`SCORE`, `TIME`, `RINGS`, lives, and debug coordinates) is rendered by `HudRenderManager` as individual 8x8 pattern submissions. Those submissions only use the existing instanced pattern shader when a `GraphicsManager` pattern batch is active.

Debug and trace overlays are separate from the normal gameplay HUD. They mostly use `PixelFontTextRenderer` and `PixelFont`, which already support mega-batching multiple text draws into one textured-quad draw call when callers open a batch scope.

## Goals

- Reduce gameplay HUD draw calls by using the existing pattern batching/instanced shader path.
- Reduce debug and trace overlay draw calls by adding top-level pixel-font batch scopes where they are missing.
- Keep palette behavior, flashing HUD labels, lives palette overrides, fade ordering, and debug overlay ordering unchanged.
- Avoid introducing a dedicated HUD shader until RenderDoc shows the existing shared shader paths are insufficient.

## Design

### Action A: Gameplay HUD Pattern Batch

`HudRenderManager.draw(...)` will open a pattern batch around each normal and bonus-stage HUD render. Every existing `renderSafe(...)` call then feeds the same instanced pattern path used by sprites and level patterns.

The lives palette override is the only mid-HUD state change that can affect palette texture contents. If a lives palette override upload is needed, the renderer will flush commands before the upload, apply the palette texture update, draw the lives HUD, and flush again before returning. This preserves current visual behavior while still batching the separate groups.

### Action B: Debug and Trace Overlay Text Batches

Top-level overlay call sites will use `PixelFontTextRenderer.beginBatch()` / `endBatch()` when rendering multiple text lines. Existing nested batching is avoided by placing scopes at outer render boundaries only.

The initial target is the trace test HUD drawn after the fade pass, because it is rendered directly from `Engine.display()` and currently passes a shared `PixelFontTextRenderer` into `TraceSessionLauncher.render(...)`. Debug overlay paths that already use `GlyphBatchRenderer` or `PerformancePanelRenderer` batching will be left alone unless inspection shows a missing outer batch.

## Testing

- Run focused tests for UI render order and HUD-adjacent behavior where available.
- Run `mvn -DskipTests package` to catch compile errors.
- Use RenderDoc manually through `run_renderdoc.cmd` to confirm fewer HUD/overlay draw calls in a live capture.
