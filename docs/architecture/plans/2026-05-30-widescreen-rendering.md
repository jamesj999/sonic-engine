# Widescreen Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax. GL compositing is verified **visually** (run the app at a widescreen preset and screenshot) in addition to the headless unit tests; use the `run` skill for the visual steps.

**Goal:** Make widescreen *look* right — a centered-320 safe-area compositor for UI, full-width in-level parallax, and (later) expanded title/menu backgrounds — with `NATIVE_4_3` byte-identical.

**Architecture:** One shared ortho projection drives all rendering. The compositor adds a second **safe-area projection** `ortho2D(-(width-320)/2, width-(width-320)/2, 0, 224)` that centers native-320 UI; `UiRenderPipeline` swaps to it for pillarbox/center surfaces and restores the scene projection before the (projection-independent) fade. The in-level scene parallax/vscroll column buffers scale to `ceil(width/16)`. See `docs/architecture/designs/2026-05-30-widescreen-rendering-design.md`.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), LWJGL/OpenGL, Maven. Branch off `develop` (or continue on `feature/ai-widescreen-foundation`). Commits carry the 7-trailer block.

---

## Scope

**This plan specifies in executable detail:**
- **R1 — Compositor + easy surfaces:** the centered-320 safe-area projection, owned by `UiRenderPipeline`/`GraphicsManager`, routed to all PILLARBOX + CENTER surfaces (special stages, level selects, data-select thumbnails, legal, results, trace-HUD, title-card element).
- **R2 — In-level scene:** scale parallax/GPU vscroll column buffers to `ceil(width/16)` and widen the `BackgroundRenderer` FBO/draw extent.

**Outlined (catalogue-driven, investigated at execution time):**
- **R3 — Expand surfaces (MIXED):** title screens, master-title background, title cards, credits.
- **R4 — HUD widening (optional).**

**Prerequisite:** the Widescreen Foundation branch (config presets, width-driven camera/spawn/object layer) must be merged or present. `NATIVE_4_3` stays byte-identical in every task (`pad=0`, columns=20).

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `src/main/java/com/openggf/graphics/pipeline/SafeAreaProjection.java` | Pure: compute centered-320 ortho params (left/right) from width; `pad(width)` | Create |
| `src/main/java/com/openggf/graphics/pipeline/UiRenderPipeline.java` | Own the projection swap: `beginSafeArea()` / `endSafeArea()` around pillarbox/center UI; restore before fade | Modify |
| `src/main/java/com/openggf/graphics/GraphicsManager.java` | Provide a settable safe-area projection override buffer (reuse the existing local-override path) | Modify |
| `src/main/java/com/openggf/Engine.java` | Route non-gameplay UI modes (master title, results, data select, special stage, legal) through the safe-area scope; scene + fade stay full-width | Modify |
| `src/main/java/com/openggf/level/ParallaxManager.java` | `BG/FG_VSCROLL_COLUMN_COUNT` → `ceil(width/16)` | Modify |
| `src/main/java/com/openggf/graphics/TilemapGpuRenderer.java` | `new VScrollBuffer(ceil(width/16))` | Modify |
| `src/main/java/com/openggf/level/render/BackgroundRenderer.java` | `VScrollBuffer` size + local `SCREEN_WIDTH` + FBO/draw extent → width-driven | Modify |
| `src/test/java/com/openggf/graphics/pipeline/TestSafeAreaProjection.java` | Unit tests | Create |
| `src/test/java/com/openggf/graphics/TestVScrollColumnCount.java` | Unit tests | Create |
| `src/test/java/com/openggf/widescreen/TestWidescreenRenderingNativeRegression.java` | Native-parity pins | Create |

---

## Phase R1 — Safe-area compositor + easy surfaces

### Task R1.1: SafeAreaProjection (pure math)

**Files:** Create `SafeAreaProjection.java`; Test `TestSafeAreaProjection.java`.

- [ ] **Step 1: failing test**

```java
package com.openggf.graphics.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestSafeAreaProjection {

    @Test
    void padIsZeroAtNative() {
        assertEquals(0, SafeAreaProjection.pad(320));
    }

    @Test
    void padCentersNative320InWiderViewport() {
        assertEquals(40, SafeAreaProjection.pad(400));   // (400-320)/2
        assertEquals(104, SafeAreaProjection.pad(528));  // (528-320)/2
        assertEquals(240, SafeAreaProjection.pad(800));  // (800-320)/2
    }

    @Test
    void orthoLeftRightFrameTheCenteredRegion() {
        // native content [0,320] maps to screen [pad, pad+320]; ortho spans [-pad, width-pad]
        assertEquals(-40f, SafeAreaProjection.orthoLeft(400), 0.001f);
        assertEquals(360f, SafeAreaProjection.orthoRight(400), 0.001f); // 400 - 40
        // native: ortho == [0, 320] (no-op)
        assertEquals(0f, SafeAreaProjection.orthoLeft(320), 0.001f);
        assertEquals(320f, SafeAreaProjection.orthoRight(320), 0.001f);
    }
}
```

- [ ] **Step 2: run → FAIL** (`mvn "-Dtest=TestSafeAreaProjection" test`).

- [ ] **Step 3: implement**

```java
package com.openggf.graphics.pipeline;

/**
 * Pure parameters for the centered-320 "safe-area" projection used to pillarbox /
 * center native-320 UI inside a wider viewport. At native width (320) pad=0, so
 * the safe-area ortho equals the scene ortho [0,320] — a no-op.
 */
public final class SafeAreaProjection {

    public static final int NATIVE_WIDTH = 320;

    private SafeAreaProjection() {
    }

    /** Horizontal padding (px) on each side: (width - 320) / 2, clamped at 0. */
    public static int pad(int width) {
        return Math.max(0, (width - NATIVE_WIDTH) / 2);
    }

    /** ortho2D left bound so native x=0 maps to screen x=pad. */
    public static float orthoLeft(int width) {
        return -pad(width);
    }

    /** ortho2D right bound so native x=320 maps to screen x=pad+320. */
    public static float orthoRight(int width) {
        return width - pad(width);
    }
}
```

- [ ] **Step 4: run → PASS** (3 tests).

- [ ] **Step 5: commit** (`feat: add SafeAreaProjection centered-320 math` + 7-trailer block; `Changelog: n/a: internal rendering helper`, others `n/a`).

### Task R1.2: GraphicsManager safe-area override + UiRenderPipeline scope

**Files:** Modify `GraphicsManager.java`, `UiRenderPipeline.java`.

- [ ] **Step 1: GraphicsManager — add a safe-area projection override.** `GraphicsManager.getProjectionMatrixBuffer()` already prefers a local `projectionMatrixBuffer` over the engine's. Add methods to set/clear it from a JOML ortho built from `SafeAreaProjection`:

```java
	private final org.joml.Matrix4f safeAreaMatrix = new org.joml.Matrix4f();
	private final float[] safeAreaBuffer = new float[16];

	/** Push a centered-320 safe-area projection for the configured viewport width. No-op at native (pad 0). */
	public void beginSafeAreaProjection(int viewportWidth, int viewportHeightPixels) {
		safeAreaMatrix.identity().ortho2D(
				com.openggf.graphics.pipeline.SafeAreaProjection.orthoLeft(viewportWidth),
				com.openggf.graphics.pipeline.SafeAreaProjection.orthoRight(viewportWidth),
				0f, viewportHeightPixels);
		safeAreaMatrix.get(safeAreaBuffer);
		setProjectionMatrixBuffer(safeAreaBuffer); // existing local-override setter
	}

	/** Restore the engine's scene projection (clears the local override). */
	public void endSafeAreaProjection() {
		setProjectionMatrixBuffer(null);
	}
```

(Confirm the existing local-override setter name — the getter is `getProjectionMatrixBuffer()` with a `projectionMatrixBuffer` field that takes precedence; add/clear via that field. If there is no public setter, add `public void setProjectionMatrixBuffer(float[] buf)`.)

- [ ] **Step 2: UiRenderPipeline — expose the scope.** Add `beginSafeArea()` / `endSafeArea()` that delegate to the GraphicsManager using the configured width/height, and ensure `renderFadePass()` runs **after** `endSafeArea()` (fade must be full-viewport). The pipeline already orders Scene → Overlay → Fade; wrap only the centered-UI/overlay draws:

```java
	public void beginSafeArea(int viewportWidth, int viewportHeightPixels) {
		graphicsManager.beginSafeAreaProjection(viewportWidth, viewportHeightPixels);
	}

	public void endSafeArea() {
		graphicsManager.endSafeAreaProjection();
	}
```

Document that callers MUST `endSafeArea()` before `renderFadePass()`.

- [ ] **Step 3: native no-op test (headless).** Add to `TestSafeAreaProjection` (or a pipeline test) an assertion that at width 320, `orthoLeft==0 && orthoRight==320` so the override equals the scene projection. (Full GL behaviour is verified visually in R1.4.)

- [ ] **Step 4: commit** (`feat: add safe-area projection scope to UiRenderPipeline`).

### Task R1.3: Route PILLARBOX + CENTER surfaces through the safe-area scope

**Files:** Modify `Engine.java` (the per-mode UI render dispatch).

Default principle: **non-gameplay UI modes render through the safe-area scope**; the in-level scene and the fade pass do not. At native this is a no-op.

- [ ] **Step 1:** Identify in `Engine.display()` (and the mode dispatch) where each of these is drawn, and wrap the draw in `uiRenderPipeline.beginSafeArea(width,224)` / `endSafeArea()`:
  - Legal disclaimer screen, master-title menu/emblem foreground, results screen (`AbstractResultsScreen`), data-select screens, special-stage UI/results, trace HUD overlay, title-card element.
  - **Do NOT** wrap: the in-level scene render, background/parallax pass, or `renderFadePass()`.
  - For MASTER TITLE and TITLE SCREEN backgrounds (EXPAND, R3) leave on the scene projection — only their centered foregrounds move to safe-area in R3. In R1, routing the whole master-title/title through safe-area is acceptable as an interim (centered, pillarboxed) until R3 expands the backgrounds.

- [ ] **Step 2: visual verification (run skill).** Run the app at `WIDE_16_9` and `ULTRA_21_9` (set `DISPLAY_ASPECT` in config.json; remember `TEST_MODE_ENABLED` forces native, so leave it false). Screenshot: legal disclaimer, a level-select, a results screen, a special stage, master title. Confirm each native-320 surface is **centered** with side bars, not left-aligned. Confirm `NATIVE_4_3` is unchanged.

- [ ] **Step 3: commit** (`feat: render pillarbox/center UI surfaces through the safe-area projection`).

### Task R1.4: Native regression pin

**Files:** Create `TestWidescreenRenderingNativeRegression.java`.

- [ ] Assert `SafeAreaProjection.pad(320)==0`, `orthoLeft(320)==0`, `orthoRight(320)==320` (safe-area == scene at native), plus the column-count native value from R2. Commit (test-only).

---

## Phase R2 — In-level scene (parallax / vscroll columns / FBO)

### Task R2.1: VScroll column count → ceil(width/16)

**Files:** Create `TestVScrollColumnCount.java`; Modify `ParallaxManager.java`, `TilemapGpuRenderer.java`, `BackgroundRenderer.java`.

- [ ] **Step 1: failing test** for a pure helper `column count = ceil(width/16)`:

```java
package com.openggf.graphics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class TestVScrollColumnCount {
    private static int columns(int width) { return (width + 15) / 16; }

    @Test void nativeIs20() { assertEquals(20, columns(320)); }
    @Test void widescreen() {
        assertEquals(25, columns(400));
        assertEquals(33, columns(528));
        assertEquals(50, columns(800));
    }
}
```

- [ ] **Step 2:** Introduce the `ceil(width/16)` count where the buffers are sized. Read the configured viewport width via the existing config/camera path (the foundation's `PlacementViewportWidth.current()` or `GameServices.configuration().getInt(SCREEN_WIDTH_PIXELS)`); compute `columns = (width + 15) / 16`. Apply to:
  - `ParallaxManager`: `BG_VSCROLL_COLUMN_COUNT` / `FG_VSCROLL_COLUMN_COUNT` and their `short[]` arrays (size at construction from `columns`).
  - `TilemapGpuRenderer`: `new VScrollBuffer(columns)` (was 20).
  - `BackgroundRenderer`: `new VScrollBuffer(columns)` and its local `SCREEN_WIDTH=320` → width-driven.
  Keep the per-scanline `hScroll[224]` unchanged. At native, `columns==20` — byte-identical.

- [ ] **Step 3:** Run `mvn "-Dtest=TestVScrollColumnCount,com.openggf.level.*,com.openggf.graphics.*" test` — native green (no new failures vs the known pre-existing set).

- [ ] **Step 4: visual verification.** Run a level at `WIDE_16_9`; confirm per-column vertical-scroll parallax fills the full width (no flat/unscrolled right band).

- [ ] **Step 5: commit** (`feat: scale vscroll column buffers to viewport width`).

### Task R2.2: BackgroundRenderer FBO / draw extent

**Files:** Modify `BackgroundRenderer.java` (and the tilemap GPU draw-extent loop if it caps at 40 cells).

- [ ] Widen the FBO allocation and the visible-column draw loop to the configured width (`ceil(width/8)` cells). Read width from config/camera. Native (`40` cells / 320px FBO) unchanged.
- [ ] Visual: run a level at `ULTRA_21_9`; confirm the background plane fills the full width with no repeated/cut edge.
- [ ] Commit (`feat: widen background FBO and draw extent to viewport width`).

> After R2, in-level widescreen is **visually complete** (scene + parallax + background fill the wide view; objects already correct from the Foundation). Menus/results/special-stages are centered from R1. Only the title/master-title/credits "expand" polish (R3) remains.

---

## Phase R3 — Expand surfaces (MIXED) — outline

These are investigated per-surface at execution time using the audit catalogue (each renderer has fixed `320`/`160`/`*0.5`-center and tile-loop-count assumptions). One task per surface/cluster; each: (a) widen the background through the scene projection (tile-loop bound, fade rects, and for S2 the ripple/occlusion scissor math), (b) center the foreground cluster (logo/emblem/sprites/menu/intro-text) through the safe-area scope, (c) visually verify at `WIDE_16_9` + `ULTRA_21_9`, (d) confirm native unchanged.

Ordered by effort (do low-risk first):
1. **Master Title background** (the user-flagged one) — medium.
2. **S1 Title Screen** — background already wraps; medium.
3. **Title Cards** (S1/S2/S3K), **S2 Ending/Credits** — medium.
4. **S2 Title Screen** — high (curved occlusion + ripple scissor + 40-col/*0.5 center; static Plane B rows hard-cap at 320 → need fill/wrap).
5. **S3K Title Screen** — animation phases are full 40×28 pictures with no horizontal data → PILLARBOX (keep centered) rather than expand; only the interactive-phase background can widen if data allows.
6. **S3K Data Select** — high.

## Phase R4 — HUD widening (optional) — outline

Spread the in-level HUD to the gameplay edges, or keep it in the centered safe-area (configurable). Lowest priority; cosmetic. `HudRenderManager` left/bottom anchors already valid; add width-relative anchoring.

---

## Self-Review Notes

- **Spec coverage:** R1 (compositor + easy surfaces) and R2 (parallax columns + FBO) are specified in executable detail; R3 (expand) and R4 (HUD) are catalogue-driven outlines because each GL surface needs execution-time investigation (per the writing-plans scope guidance — don't over-specify uncertain GL work).
- **Native parity:** every task's native path is `pad=0` / `columns=20` / `40`-cell extent — byte-identical, pinned by the regression test.
- **GL verification:** compositing is verified visually (run + screenshot) in addition to headless math unit tests — GL render output is not meaningfully unit-testable.
- **Dependency:** assumes the Widescreen Foundation (width-driven gameplay/config + object-despawn sweep) is present; the scene already renders wide.
