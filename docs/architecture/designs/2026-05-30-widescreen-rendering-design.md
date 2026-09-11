# Design: Widescreen Rendering (Visual Layer)

## Problem

The Widescreen Foundation made the gameplay/config layer width-driven: at a widescreen `DISPLAY_ASPECT` the camera, spawn windows, boundaries, and object despawn/visibility all scale with the configured width, and the in-level **scene renders wide** (the shared ortho projection and viewport already key off `SCREEN_WIDTH_PIXELS`). But the **visual layer is incomplete**:

- UI surfaces authored in native `0..320` coordinates render **left-aligned**, not centered/pillarboxed.
- The background **parallax / per-16px vertical-scroll column buffers are fixed at 20** (H40), so the right portion of the wide scene gets no per-column vscroll.
- There is **no compositor** to render a centered-320 region (pillarbox) vs the full width.

This spec covers the visual layer that makes widescreen actually look right. `NATIVE_4_3` (the default) must stay byte-identical throughout.

## Established rendering facts (verified)

- The engine renders **everything** (scene, HUD, title screens, results) through **one shared ortho projection** `ortho2D(0, projectionWidth, 0, realHeight)` (`Engine.java`, three sites: reshape / display / prepareOverlayState). `projectionWidth == realWidth == SCREEN_WIDTH_PIXELS`.
- The **fade pass is projection-independent**: `QuadRenderer.draw(...)` ignores its args and emits a fullscreen triangle-strip from `gl_VertexID` in clip space (`shader_fullscreen.vert`). So fade already covers the full viewport — no change needed; it must simply **not** be wrapped in a narrowed projection.
- `UiRenderPipeline` (`com.openggf.graphics.pipeline`) already orders **Scene → HUD overlay → Fade**, and is the correct owner for projection scoping.
- The viewport is integer-scaled and centered with letterbox/pillarbox bars already (`Engine.reshape`); at widescreen the viewport aspect follows `realWidth/realHeight`.

## Architecture: the centered-320 compositor

Two projection matrices, selected per render pass:

| Projection | Ortho | Used by |
|------------|-------|---------|
| **Scene / Expand** | `ortho2D(0, width, 0, 224)` | in-level scene; EXPAND surfaces; fade (via gl_VertexID, unaffected) |
| **Safe-area (centered-320)** | `ortho2D(-pad, width - pad, 0, 224)`, `pad = (width - 320) / 2` | PILLARBOX + CENTER surfaces; native-320 UI |

The safe-area projection maps native content `x∈[0,320]` to screen `x∈[pad, pad+320]` — i.e. a 320-wide region centered in the wide viewport. **At `NATIVE_4_3`, `width = 320 → pad = 0`, so the safe-area projection equals the scene projection — byte-identical, no-op.**

`UiRenderPipeline` owns the swap: it uploads the scene projection for the scene/expand passes and the safe-area projection for pillarbox/center UI passes, then restores the scene projection before the fade pass. `GraphicsManager.getProjectionMatrixBuffer()` already supports a local override buffer that takes precedence over the engine's — the compositor sets/clears that override per pass (no change to the per-batch shader upload path).

**Why projection-swap, not scissor:** all geometry flows through the shared `ProjectionMatrix` uniform (`shader_basic.vert`); swapping the matrix re-centers every renderer (HUD, title, results) with zero per-renderer change. Scissor only clips — it can't re-center content authored at `x=0`.

> **⚠️ Learned in practice (2026-05-30):** the projection-swap compositor (R1.1–R1.3) was IMPLEMENTED and then REVERTED. Although the safe-area matrix is provably correct in isolation (headless-tested), in the live engine it (a) left several surfaces *left-aligned* (the pattern-batch projection upload is cached per-command via `stateInitialized`, so the override often isn't re-read) and (b) horizontally *distorted* others — symptoms that only appear at runtime and could not be root-caused headlessly. The primitives (`SafeAreaProjection`, `GraphicsManager.beginSafeAreaProjection`, `UiRenderPipeline.beginSafeArea`) are retained but **no longer called**.
>
> **Revised recommended approach: per-surface width-aware coordinates ("Approach B"), proven on the Master Title Screen** (`setViewportWidth(int)` + `centerX(elementWidth, viewportWidth)` for foreground; draw background at `0..viewportWidth`). It is more verbose (per surface) but reliable and visually verifiable surface-by-surface. The R1 phase below should be re-planned around Approach B, OR the projection-swap fixed with live GL-state diagnostics first. This work needs **live visual iteration** (run the app at a widescreen preset + observe); it is not headless-debuggable.

## Per-surface treatment (from the widescreen audit)

22 surfaces were classified. Mechanically there are three treatments: **PILLARBOX/CENTER** (render through the safe-area projection — identical mechanism, the only difference is whether a backdrop fills the side bars), and **EXPAND** (render through the scene projection and widen the content), and **MIXED** (background EXPAND pass + foreground CENTER pass).

| Treatment | Surfaces | Effort |
|-----------|----------|--------|
| **PILLARBOX** (safe-area projection; side bars = backdrop) | S2/S3K Level Select, S1/S2 Data-Select thumbnails, all 3 Special Stages (S1 maze, S2 track, S3K Blue Sphere) | low |
| **CENTER** (safe-area projection) | Legal Disclaimer, Results-screen base (`AbstractResultsScreen`), S2 Special-Stage Results, Trace HUD, shared Title-Card Element | low |
| **MIXED** (BG expand + FG center) | S1/S2/S3K **Title Screens**, **Master Title** (background expands; emblem/menu/cast center), S1/S2/S3K **Title Cards**, S3K **Data Select**, S2 **Ending/Credits** | medium–high |

Audit detail worth carrying into the plan:
- **S1/S2 title backgrounds already wrap horizontally** (Plane B loops ~42 tiles / past 320 with modulo nametable wrap), so EXPAND is mostly widening the tile-loop bound + the parallax buffer; the work is re-centering the fixed logo/Sonic/TM/menu cluster off literal `320`/`160` and widening the fade rects.
- **S2 title is the hardest**: the curved lower-logo occlusion and the ripple water rows use viewport-scaled `glScissor` math AND fixed `40`-col / `*0.5`-center assumptions that must be re-derived for the new width; static Plane B rows hard-cap at 320 (need fill/wrap to expand).
- **S3K title animation phases** are complete 40×28 nametables with no horizontal data — PILLARBOX (or accept letterbox) rather than EXPAND, since expanding needs art that doesn't exist.
- **Special stages** are confirmed PILLARBOX (per the foundation spec) — Blue Sphere / maze / track are pseudo-3D and not meaningfully wideable.

## In-level scene completion (parallax / GPU columns / FBO)

Independent of UI, the in-level scene needs the deferred Tier-1-rendering items so the *right portion* of the wide view is correct:
- **Per-16px vertical-scroll column buffers** scale to `ceil(width/16)` (20 at native): `ParallaxManager` (`BG/FG_VSCROLL_COLUMN_COUNT`), `TilemapGpuRenderer` (`new VScrollBuffer(20)`), `BackgroundRenderer` (`new VScrollBuffer(20)` + its local `SCREEN_WIDTH=320`).
- **Background FBO / draw extent** widened to the configured width (`BackgroundRenderer` FBO alloc + the visible-column loop).
- The per-scanline `hScroll[224]` array is height-indexed — unchanged.

## Phasing

Each phase is independently shippable; `NATIVE_4_3` unchanged throughout.

- **R1 — Compositor + easy surfaces.** Build the centered-320 safe-area projection in `UiRenderPipeline`/`GraphicsManager` (with the scene-projection restore before fade). Route all **PILLARBOX + CENTER** surfaces through it. Delivers correctly-centered menus/results/legal/special-stages/data-select. Low effort, high coverage. Unit-test the projection math; visually verify a few surfaces.
- **R2 — In-level scene.** Scale the parallax/GPU vscroll column buffers to `ceil(width/16)` and widen the `BackgroundRenderer` FBO/draw extent. After R2, in-level widescreen is visually complete (scene + parallax fill the wide view).
- **R3 — Expand surfaces (MIXED).** Title screens, master-title background, title cards, credits: widen backgrounds (tile-loop bounds, fade rects, ripple/occlusion scissor math) through the scene projection, and center the foreground clusters through the safe-area projection. Highest effort; S2 title is the hardest single item.
- **R4 — HUD widening (optional).** Spread the in-level HUD to the gameplay edges (or keep it in the centered safe-area, configurable). Cosmetic preference, lowest priority.

## Testing

- **Unit (headless):** the safe-area projection offset = `(width-320)/2` and is `0` at native; the vscroll column count = `ceil(width/16)` and is `20` at native; the BG draw extent = `ceil(width/8)` columns and `40` at native.
- **Native regression:** at `NATIVE_4_3`, `pad=0` and all column counts/extents equal today's — assert byte-identical, and the existing scene/UI tests stay green.
- **Visual verification (the primary check for GL work):** run the app at `WIDE_16_9` and `ULTRA_21_9`, screenshot each surface, and confirm the classified treatment (centered / pillarboxed / expanded). GL compositing is verified visually, not by unit tests.

## Non-Goals

- No new art (S3K title animation phases stay pillarboxed; special stages stay pillarboxed).
- No ROM-parity claim in widescreen (consistent with the foundation).
- No HUD redesign beyond optional R4 edge-spreading.
