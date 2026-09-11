# Widescreen Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the engine's gameplay/config layer fully width-driven so a new `DISPLAY_ASPECT` preset widens the play view, with `NATIVE_4_3` (320x224) byte-for-byte unchanged.

**Architecture:** The engine already drives `realWidth` / `projectionWidth` / the viewport / `Camera.width` from the `SCREEN_WIDTH_PIXELS` config value, and renders everything through one shared ortho projection. So widening the *scene* needs only config plumbing; this plan covers the config surface plus the gameplay-logic literals that do **not** auto-follow width (camera deadzone/snap, player level boundary + its MGZ duplicate, and the object/ring/bumper spawn windows). All new logic is pure/headless-testable. The rendering-layer literals (UI pillarbox projection swap, parallax/GPU column buffers, background FBO extent) are deferred to the follow-up "Widescreen Rendering" plan.

**Tech Stack:** Java 21, JUnit 5 (Jupiter only), Maven. No OpenGL in any test in this plan.

---

## Scope

**This plan (Foundation) delivers:**
- `DISPLAY_ASPECT`, `WIDESCREEN_DEADZONE_MODE`, `DISPLAY_WINDOW_AUTOSIZE` config keys.
- Preset → `SCREEN_WIDTH_PIXELS` resolution + autosize window sizing.
- Width-driven camera deadzone band, focus/respawn snap, and the two deadzone modes.
- Width-driven player right-boundary in `PlayableSpriteMovement` **and** its `Sonic3kMGZEvents` duplicate.
- Width-driven spawn `loadAhead` for all three `AbstractPlacementManager` streams (objects, rings, bumpers).
- A literal-`320` guard test (with documented allowlist for rendering literals the Rendering plan will remove).
- A native-regression test proving the parity path is unchanged.

**Deferred to "Widescreen Rendering" plan (do NOT attempt here):**
- UI safe-area pillarbox (centered-320 projection swap through `UiRenderPipeline` for HUD/title/results/data-select).
- Parallax + GPU vertical-scroll column buffers (`ParallaxManager` 20-col, `TilemapGpuRenderer`/`BackgroundRenderer` `VScrollBuffer(20)`).
- `BackgroundRenderer` FBO/draw-extent width.
- Fade full-viewport verification test (cheap; lives with the rendering pass).

**Deferred to later phases (separate specs/plans):** HUD widening, title-card widening, ultrawide tuning + per-mode pillarbox fallback, boss-arena widening. See `docs/architecture/designs/2026-05-30-widescreen-support-design.md`.

> **NOT user-facing-shippable on its own.** This plan is an internal foundation increment. After it lands, `NATIVE_4_3` (the default) is fully unaffected and all tests pass — but selecting *any* widescreen preset produces a **visibly broken frame**: the scene renders wide, yet UI/HUD/title/results stay left-aligned (no pillarbox projection swap) and the right-edge parallax/vscroll columns are unfilled. Therefore:
> - The **"Widescreen Rendering" plan is a hard prerequisite** for exposing the presets to users. It does not exist in `docs/architecture/plans/` yet and must be authored and executed before any release advertises widescreen. It covers: UI safe-area pillarbox (centered-320 projection swap via `UiRenderPipeline`), parallax + GPU vscroll column buffers, `BackgroundRenderer` FBO/draw extent, and the fade full-viewport verification.
> - Task 3 documents the config keys (defaulting to `NATIVE_4_3`, safe), but that documentation must mark the widescreen presets **experimental / incomplete until the Rendering plan ships** so users don't enable a broken frame.
> - The spec's "Phase 1" bundled these rendering items into one shippable unit; this plan deliberately splits Phase 1 along the GL boundary (per the writing-plans Scope Check) into Foundation (here) + Rendering (next), trading single-PR shippability for two accurately-specifiable, independently-testable plans.

---

## File Structure

| File | Responsibility | Action |
|------|----------------|--------|
| `src/main/java/com/openggf/configuration/WidescreenAspect.java` | Pure enum: preset → native pixel width; case-insensitive `parse()` | Create |
| `src/main/java/com/openggf/configuration/DeadzoneMode.java` | Pure enum: `CENTER_SCALED` / `PROPORTIONAL`; `parse()` | Create |
| `src/main/java/com/openggf/camera/DeadzoneGeometry.java` | Pure static math: band edges, band width, focus-snap offset from `(width, mode)` | Create |
| `src/main/java/com/openggf/configuration/DisplayWindowPolicy.java` | Pure static: resolve `(aspect, autosize, curW, curH)` → `(pixelWidth, windowWidth, windowHeight)` record | Create |
| `src/main/java/com/openggf/configuration/SonicConfiguration.java` | Add 3 enum constants | Modify |
| `src/main/java/com/openggf/configuration/SonicConfigurationService.java` | Defaults + `resolveDisplayAspect()` application | Modify |
| `src/main/java/com/openggf/camera/Camera.java` | Read deadzone mode; use `DeadzoneGeometry` in scroll + snap | Modify |
| `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java` | Right boundary uses `camera.getWidth()` | Modify |
| `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java` | Quake right-clamp uses `camera().getWidth()` | Modify |
| `src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java` | `loadAhead` derived from injected width supplier + `extraAhead` | Modify |
| `src/main/java/com/openggf/level/objects/ObjectManager.java` | Placement passes width supplier; teleport guard uses dynamic getters | Modify |
| `src/main/java/com/openggf/level/rings/RingManager.java` | RingPlacement passes width supplier | Modify |
| `src/main/java/com/openggf/game/sonic2/bumpers/CNZBumperManager.java` | Placement passes width supplier | Modify |
| `src/main/java/com/openggf/level/spawn/PlacementViewportWidth.java` | Null-safe configured-width reader (320 fallback) for placement suppliers | Create |
| `src/test/java/com/openggf/configuration/TestWidescreenAspect.java` | Unit tests | Create |
| `src/test/java/com/openggf/configuration/TestDisplayWindowPolicy.java` | Unit tests | Create |
| `src/test/java/com/openggf/configuration/TestDisplayAspectResolution.java` | Unit tests (preset resolution + persistence) | Create |
| `src/test/java/com/openggf/camera/TestDeadzoneGeometry.java` | Unit tests | Create |
| `src/test/java/com/openggf/sprites/managers/TestRightBoundary.java` | Unit tests | Create |
| `src/test/java/com/openggf/level/spawn/TestPlacementWindowWidth.java` | Unit tests | Create |
| `src/test/java/com/openggf/widescreen/TestNoScreenWidth320Literals.java` | Scanner guard test | Create |
| `src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java` | Aggregate native-parity test | Create |

---

## Task 1: WidescreenAspect enum (preset → width)

**Files:**
- Create: `src/main/java/com/openggf/configuration/WidescreenAspect.java`
- Test: `src/test/java/com/openggf/configuration/TestWidescreenAspect.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestWidescreenAspect {

    @Test
    void presetWidthsAreCorrectAndHeight224Fixed() {
        assertEquals(320, WidescreenAspect.NATIVE_4_3.pixelWidth());
        assertEquals(352, WidescreenAspect.WIDE_16_10.pixelWidth());
        assertEquals(400, WidescreenAspect.WIDE_16_9.pixelWidth());
        assertEquals(528, WidescreenAspect.ULTRA_21_9.pixelWidth());
        assertEquals(800, WidescreenAspect.SUPER_32_9.pixelWidth());
    }

    @Test
    void everyPresetWidthIsMultipleOf16() {
        for (WidescreenAspect a : WidescreenAspect.values()) {
            assertEquals(0, a.pixelWidth() % 16, a.name() + " width must be a multiple of 16");
        }
    }

    @Test
    void parseIsCaseInsensitiveAndFallsBackToNative() {
        assertEquals(WidescreenAspect.WIDE_16_9, WidescreenAspect.parse("wide_16_9"));
        assertEquals(WidescreenAspect.WIDE_16_9, WidescreenAspect.parse("  WIDE_16_9 "));
        assertEquals(WidescreenAspect.NATIVE_4_3, WidescreenAspect.parse(null));
        assertEquals(WidescreenAspect.NATIVE_4_3, WidescreenAspect.parse("garbage"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestWidescreenAspect" test`
Expected: FAIL — `WidescreenAspect` does not exist (compilation error).

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.configuration;

import java.util.Locale;
import java.util.logging.Logger;

/**
 * Display aspect presets. Height is always 224; only the native pixel width
 * varies. Every width is a multiple of 16 (per-16px vertical-scroll column
 * alignment). NATIVE_4_3 reproduces the original 320x224 behaviour.
 */
public enum WidescreenAspect {
    NATIVE_4_3(320),
    WIDE_16_10(352),
    WIDE_16_9(400),
    ULTRA_21_9(528),
    SUPER_32_9(800);

    private static final Logger LOGGER = Logger.getLogger(WidescreenAspect.class.getName());

    private final int pixelWidth;

    WidescreenAspect(int pixelWidth) {
        this.pixelWidth = pixelWidth;
    }

    public int pixelWidth() {
        return pixelWidth;
    }

    /** Parses a preset name (case-insensitive). Unknown/invalid values warn and fall back to NATIVE_4_3. */
    public static WidescreenAspect parse(String value) {
        if (value == null || value.isBlank()) {
            return NATIVE_4_3;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (WidescreenAspect aspect : values()) {
            if (aspect.name().equals(normalized)) {
                return aspect;
            }
        }
        LOGGER.warning("Unknown DISPLAY_ASPECT '" + value + "', falling back to NATIVE_4_3");
        return NATIVE_4_3;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestWidescreenAspect" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/WidescreenAspect.java src/test/java/com/openggf/configuration/TestWidescreenAspect.java
git commit -m "feat: add WidescreenAspect preset enum

Changelog: n/a: internal enum with no user-visible behaviour; user-facing entry lands with the DISPLAY_ASPECT config commit
Guide: n/a: no guide change
Known-Discrepancies: n/a: no divergence
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: config surface documented in the DISPLAY_ASPECT commit
Skills: n/a: no skill change"
```

> NOTE: All `feat:` commits in this plan touch `src/main/` and carry the trailer block shown in each commit step (the `prepare-commit-msg` hook auto-appends a blank one — fill it with the block shown, don't delete it). The repo rejects a bare `Changelog: n/a` on such commits, so intermediate commits use a justified `n/a: <reason>`, while the config commit (Task 3) and boundary commit (Task 6) use `updated` and stage their docs. Test-only commits (Tasks 9-10) touch only `src/test/` and are exempt from the changelog-justification rule. See "Documentation / Branch Policy" at the end for the mapping.

---

## Task 2: DisplayWindowPolicy (window sizing)

**Files:**
- Create: `src/main/java/com/openggf/configuration/DisplayWindowPolicy.java`
- Test: `src/test/java/com/openggf/configuration/TestDisplayWindowPolicy.java`

Window sizing must keep the existing 2x baseline (native default is 640x448 = 2x 320x224), so a widescreen preset never produces a smaller window than today. With autosize off, the caller's existing window dimensions are preserved verbatim.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestDisplayWindowPolicy {

    @Test
    void autosizeDerivesTwoTimesBaselineAndOverwritesLegacy() {
        // Legacy 640x448 present in config, autosize on -> derived from preset.
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.WIDE_16_9, true, 640, 448);
        assertEquals(400, r.pixelWidth());
        assertEquals(800, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }

    @Test
    void autosizeNativeIsUnchangedFromToday() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.NATIVE_4_3, true, 640, 448);
        assertEquals(320, r.pixelWidth());
        assertEquals(640, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }

    @Test
    void autosizeAtNativePreservesCustomWindow() {
        // A user with a custom 1280x768 window at NATIVE_4_3 must NOT be stomped.
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.NATIVE_4_3, true, 1280, 768);
        assertEquals(320, r.pixelWidth());
        assertEquals(1280, r.windowWidth());
        assertEquals(768, r.windowHeight());
    }

    @Test
    void autosizeOffPreservesProvidedWindowButStillSetsPixelWidth() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.WIDE_16_9, false, 1024, 768);
        assertEquals(400, r.pixelWidth());
        assertEquals(1024, r.windowWidth());
        assertEquals(768, r.windowHeight());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestDisplayWindowPolicy" test`
Expected: FAIL — `DisplayWindowPolicy` does not exist.

- [ ] **Step 3: Write minimal implementation**

```java
package com.openggf.configuration;

/**
 * Pure window-sizing policy for display aspect presets.
 * When autosize is on AND a widescreen preset is selected, the window is derived
 * at the 2x baseline (width = pixelWidth*2, height = 448). For NATIVE_4_3, or
 * when autosize is off, the caller's current window size is preserved so a
 * user's custom window is never stomped.
 */
public final class DisplayWindowPolicy {

    /** Existing default window scale relative to native pixels (640/320 = 448/224 = 2). */
    private static final int BASELINE_SCALE = 2;
    private static final int PIXEL_HEIGHT = 224;

    private DisplayWindowPolicy() {
    }

    public record Resolved(int pixelWidth, int windowWidth, int windowHeight) {
    }

    public static Resolved resolve(WidescreenAspect aspect, boolean autosize,
            int currentWindowWidth, int currentWindowHeight) {
        int pixelWidth = aspect.pixelWidth();
        // Derive the window only when opting into a WIDESCREEN preset with
        // autosize on. NATIVE_4_3 (and autosize-off) preserve the caller's
        // current window, so a user's custom window size is never stomped by
        // the default-true autosize flag.
        if (autosize && aspect != WidescreenAspect.NATIVE_4_3) {
            return new Resolved(pixelWidth, pixelWidth * BASELINE_SCALE, PIXEL_HEIGHT * BASELINE_SCALE);
        }
        return new Resolved(pixelWidth, currentWindowWidth, currentWindowHeight);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestDisplayWindowPolicy" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/DisplayWindowPolicy.java src/test/java/com/openggf/configuration/TestDisplayWindowPolicy.java
git commit -m "feat: add DisplayWindowPolicy window sizing

Changelog: n/a: internal mechanism; user-facing entry in the DISPLAY_ASPECT commit
Guide: n/a: no guide change
Known-Discrepancies: n/a: no divergence
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: config surface documented in the DISPLAY_ASPECT commit
Skills: n/a: no skill change"
```

---

## Task 3: Config keys + resolution wiring

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java` (add 3 enum constants near the other display keys)
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java` (defaults block ~lines 248-258; transient overlay in `getConfigValue`; `resolveDisplayAspect()` called at the **end of the constructor, after the save decision at lines 85-87**)
- Modify: `src/main/resources/config.json` (add the 3 keys)
- Modify: `CONFIGURATION.md` and `docs/guide/playing/configuration.md` (document the 3 keys — folded into this commit for the trailer policy)

`SonicConfiguration` is a plain constants enum (no fields). Values are stored as strings/numbers in the `config` map and read via `getString`/`getInt`/`getBoolean`.

**Persistence safety (critical):** the constructor calls `saveConfig()` when defaults are inserted (lines 83-87). If `resolveDisplayAspect()` wrote the derived `SCREEN_WIDTH_PIXELS` / `SCREEN_WIDTH` / `SCREEN_HEIGHT` into the persisted `config` map, those derived values would be written to the user's `config.json` (and again on any later save such as a color-profile toggle). To keep derived values **in-memory only** as the spec requires, they go into a separate `transientResolved` overlay map that `getConfigValue` reads first and `saveConfig` never writes. The three *user* keys (`DISPLAY_ASPECT`, `WIDESCREEN_DEADZONE_MODE`, `DISPLAY_WINDOW_AUTOSIZE`) remain normal persisted keys.

- [ ] **Step 1: Add the three enum constants**

In `SonicConfiguration.java`, add after `DISPLAY_COLOR_PROFILE_TOGGLE_KEY` (line ~131):

```java
	/**
	 * Display aspect preset (NATIVE_4_3, WIDE_16_10, WIDE_16_9, ULTRA_21_9,
	 * SUPER_32_9). Resolves to SCREEN_WIDTH_PIXELS; height stays 224.
	 */
	DISPLAY_ASPECT,

	/**
	 * Camera horizontal deadzone behaviour on wide screens
	 * (CENTER_SCALED keeps the native 16px band; PROPORTIONAL scales it).
	 */
	WIDESCREEN_DEADZONE_MODE,

	/**
	 * When true, the display window is derived from DISPLAY_ASPECT at the 2x
	 * baseline; when false, SCREEN_WIDTH/SCREEN_HEIGHT are used verbatim.
	 */
	DISPLAY_WINDOW_AUTOSIZE,
```

- [ ] **Step 2: Add defaults**

In `SonicConfigurationService.java`, after the `DISPLAY_COLOR_PROFILE` defaults (line ~258), add:

```java
		putDefault(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
		putDefault(SonicConfiguration.WIDESCREEN_DEADZONE_MODE, "PROPORTIONAL");
		putDefault(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE, true);
```

- [ ] **Step 3: Write the failing test**

`SonicConfigurationService`'s constructor is private; use the public `createStandalone()` factory and the existing `setConfigValue(...)` setter. A standalone service runs the full load/default path, so `DISPLAY_ASPECT` is already `NATIVE_4_3`; the tests set it explicitly and re-run `resolveDisplayAspect()` (which is idempotent).

```java
package com.openggf.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestDisplayAspectResolution {

    @Test
    void nativeDefaultLeavesScreenWidthPixels320() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "NATIVE_4_3");
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(224, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
        assertEquals(640, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void wide169WithAutosizeWidensPixelsAndWindow() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(800, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void autosizeOffPreservesWindow() {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone();
        cfg.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        cfg.setConfigValue(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE, false);
        cfg.setConfigValue(SonicConfiguration.SCREEN_WIDTH, 1024);
        cfg.setConfigValue(SonicConfiguration.SCREEN_HEIGHT, 768);
        cfg.resolveDisplayAspect();
        assertEquals(400, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(1024, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(768, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `mvn "-Dtest=TestDisplayAspectResolution" test`
Expected: FAIL — `resolveDisplayAspect` not defined.

- [ ] **Step 5: Add the transient overlay + `resolveDisplayAspect()`**

In `SonicConfigurationService.java`, add the overlay field near the other fields (e.g. after line 25):

```java
	// Derived (non-persisted) display values; read before `config`, never saved.
	private final java.util.Map<String, Object> transientResolved = new java.util.HashMap<>();
```

Make `getConfigValue` (lines 182-187) consult the overlay first:

```java
	public Object getConfigValue(SonicConfiguration sonicConfiguration) {
		Object overlay = transientResolved.get(sonicConfiguration.name());
		if (overlay != null) {
			return overlay;
		}
		if (config != null && config.containsKey(sonicConfiguration.name())) {
			return config.get(sonicConfiguration.name());
		}
		return null;
	}
```

Add the resolver (writes derived values to the overlay, never to `config`):

```java
	/**
	 * Resolves DISPLAY_ASPECT into SCREEN_WIDTH_PIXELS (and, when
	 * DISPLAY_WINDOW_AUTOSIZE is true with a widescreen preset,
	 * SCREEN_WIDTH/SCREEN_HEIGHT). Derived values are stored in an in-memory
	 * overlay only and are NEVER written to config.json (saveConfig persists
	 * `config`, not the overlay). SCREEN_WIDTH_PIXELS is therefore a *derived*
	 * value here, not a user setting — a manually-set SCREEN_WIDTH_PIXELS in
	 * config.json is superseded by the preset (this replaces the spec's
	 * "warn on disagreeing SCREEN_WIDTH_PIXELS" with a cleaner derived model).
	 * Idempotent; safe to call repeatedly. Height pixels stay 224.
	 */
	public void resolveDisplayAspect() {
		WidescreenAspect aspect = WidescreenAspect.parse(getString(SonicConfiguration.DISPLAY_ASPECT));
		boolean autosize = getBoolean(SonicConfiguration.DISPLAY_WINDOW_AUTOSIZE);
		int currentWindowW = getInt(SonicConfiguration.SCREEN_WIDTH);
		int currentWindowH = getInt(SonicConfiguration.SCREEN_HEIGHT);
		DisplayWindowPolicy.Resolved resolved = DisplayWindowPolicy.resolve(
				aspect, autosize, currentWindowW, currentWindowH);
		transientResolved.put(SonicConfiguration.SCREEN_WIDTH_PIXELS.name(), resolved.pixelWidth());
		transientResolved.put(SonicConfiguration.SCREEN_HEIGHT_PIXELS.name(), 224);
		transientResolved.put(SonicConfiguration.SCREEN_WIDTH.name(), resolved.windowWidth());
		transientResolved.put(SonicConfiguration.SCREEN_HEIGHT.name(), resolved.windowHeight());
		if (aspect != WidescreenAspect.NATIVE_4_3) {
			LOGGER.info("Display aspect " + aspect + " -> " + resolved.pixelWidth() + "x224 (in-memory only).");
			if (autosize && (resolved.windowWidth() != currentWindowW || resolved.windowHeight() != currentWindowH)) {
				LOGGER.info("DISPLAY_WINDOW_AUTOSIZE derived window " + resolved.windowWidth() + "x"
						+ resolved.windowHeight() + " (was " + currentWindowW + "x" + currentWindowH
						+ "); set DISPLAY_WINDOW_AUTOSIZE=false to keep a custom window.");
			}
		}
	}
```

(`LOGGER` already exists in `SonicConfigurationService`; reuse it.)

Call it as the **final statement of the constructor**, after the save decision (after line 87), so the derived values are computed once at startup but are never part of the constructor's `saveConfig()`:

```java
		resolveDisplayAspect();
```

> Persistence test guard (add to the test class): after `cfg.resolveDisplayAspect()` with `WIDE_16_9`, assert the persisted map still holds the user's `SCREEN_WIDTH_PIXELS` and that the derived value comes from the overlay — verify by checking `cfg.getDefaultValue(SonicConfiguration.SCREEN_WIDTH_PIXELS)` is unchanged (320) while `getInt(...)` returns 400.

- [ ] **Step 6: Run test to verify it passes**

Run: `mvn "-Dtest=TestDisplayAspectResolution" test`
Expected: PASS (3 tests).

- [ ] **Step 7: Add the keys to config.json**

In `src/main/resources/config.json`, add alongside the existing screen keys:

```json
  "DISPLAY_ASPECT": "NATIVE_4_3",
  "WIDESCREEN_DEADZONE_MODE": "PROPORTIONAL",
  "DISPLAY_WINDOW_AUTOSIZE": true,
```

- [ ] **Step 8: Document the new keys**

Add `DISPLAY_ASPECT`, `WIDESCREEN_DEADZONE_MODE`, `DISPLAY_WINDOW_AUTOSIZE` (values, defaults, in-memory window-derivation behaviour) to `CONFIGURATION.md` and `docs/guide/playing/configuration.md`, following the existing `DISPLAY_COLOR_PROFILE` entry format.

- [ ] **Step 9: Commit (this is the changelog-bearing commit for the config surface)**

Add a CHANGELOG entry for the new widescreen config surface. Stage docs with the code so the trailer attestation is valid.

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java src/main/java/com/openggf/configuration/SonicConfigurationService.java src/main/resources/config.json src/test/java/com/openggf/configuration/TestDisplayAspectResolution.java CHANGELOG.md CONFIGURATION.md docs/guide/playing/configuration.md
git commit -m "feat: resolve DISPLAY_ASPECT preset into screen config

Changelog: updated
Guide: updated
Known-Discrepancies: n/a: no behavioural divergence in this commit
S3K-Known-Discrepancies: n/a: no S3K divergence in this commit
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: updated
Skills: n/a: no skill change"
```

---

## Task 4: DeadzoneMode + DeadzoneGeometry (pure camera math)

**Files:**
- Create: `src/main/java/com/openggf/configuration/DeadzoneMode.java`
- Create: `src/main/java/com/openggf/camera/DeadzoneGeometry.java`
- Test: `src/test/java/com/openggf/camera/TestDeadzoneGeometry.java`

**Geometry derivation (reconciles the spec's "not a naive width/2 center" warning).** At native, screen-center is `width/2 = 160`, and the ROM band `[144, 160]` is **right-edge-anchored at screen-center** — i.e. the band is *not* centered on `width/2`; its right edge sits at `width/2` and it extends `bandWidth` to the left, so the sprite rests just left of center and the focus/respawn snap puts it exactly at center. The generalization preserves this exact anchoring:
- `rightEdge = width/2` (screen-center; the moving-right scroll trigger and the snap rest position both sit here — they are the same point, so there is a single function, not two).
- `bandWidth = 16` (CENTER_SCALED) or `round(16 * width / 320)` (PROPORTIONAL).
- `leftEdge = rightEdge - bandWidth`.

**Deliberate consequence (the spec wanted this decided, not assumed):** on wide screens the right trigger stays at screen-center while PROPORTIONAL widens the band *leftward only*. This is the faithful generalization of native's inherent leftward-only offset from center (native is already asymmetric: right edge at center, left edge 16px left). CENTER_SCALED keeps the native 16px band exactly; PROPORTIONAL scales it. If a future symmetric-feel variant is wanted, it becomes a third `DeadzoneMode`, not a change to these two.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.configuration.DeadzoneMode;
import org.junit.jupiter.api.Test;

class TestDeadzoneGeometry {

    @Test
    void nativeMatchesRomConstantsForBothModes() {
        for (DeadzoneMode mode : DeadzoneMode.values()) {
            // rightEdge is both the moving-right scroll trigger and the snap rest point.
            assertEquals(160, DeadzoneGeometry.rightEdge(320), mode.name());
            assertEquals(16, DeadzoneGeometry.bandWidth(320, mode), mode.name());
            assertEquals(144, DeadzoneGeometry.leftEdge(320, mode), mode.name());
        }
    }

    @Test
    void centerScaledKeeps16pxBandAtWidescreen() {
        assertEquals(200, DeadzoneGeometry.rightEdge(400));
        assertEquals(16, DeadzoneGeometry.bandWidth(400, DeadzoneMode.CENTER_SCALED));
        assertEquals(184, DeadzoneGeometry.leftEdge(400, DeadzoneMode.CENTER_SCALED));
    }

    @Test
    void proportionalScalesBandWithWidth() {
        // 16 * 400 / 320 = 20
        assertEquals(20, DeadzoneGeometry.bandWidth(400, DeadzoneMode.PROPORTIONAL));
        assertEquals(180, DeadzoneGeometry.leftEdge(400, DeadzoneMode.PROPORTIONAL));
        // 16 * 800 / 320 = 40
        assertEquals(40, DeadzoneGeometry.bandWidth(800, DeadzoneMode.PROPORTIONAL));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestDeadzoneGeometry" test`
Expected: FAIL — `DeadzoneMode` / `DeadzoneGeometry` do not exist.

- [ ] **Step 3: Write minimal implementations**

`DeadzoneMode.java`:

```java
package com.openggf.configuration;

import java.util.Locale;

/** Camera horizontal deadzone behaviour on wide screens. */
public enum DeadzoneMode {
    /** Native 16px band, re-centered. */
    CENTER_SCALED,
    /** Band width scales with width/320. Default. */
    PROPORTIONAL;

    public static DeadzoneMode parse(String value) {
        if (value == null) {
            return PROPORTIONAL;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (DeadzoneMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return PROPORTIONAL;
    }
}
```

`DeadzoneGeometry.java`:

```java
package com.openggf.camera;

import com.openggf.configuration.DeadzoneMode;

/**
 * Pure geometry for the camera horizontal scroll deadzone and focus/respawn
 * snap, generalized from the ROM native relationship (width 320: band [144,160],
 * snap at 160). At native every value reproduces the ROM constants exactly.
 */
public final class DeadzoneGeometry {

    private static final int NATIVE_WIDTH = 320;
    private static final int NATIVE_BAND_WIDTH = 16;

    private DeadzoneGeometry() {
    }

    /** Right edge of the deadzone = screen centre. */
    public static int rightEdge(int width) {
        return width / 2;
    }

    public static int bandWidth(int width, DeadzoneMode mode) {
        if (mode == DeadzoneMode.CENTER_SCALED) {
            return NATIVE_BAND_WIDTH;
        }
        return Math.round((float) NATIVE_BAND_WIDTH * width / NATIVE_WIDTH);
    }

    public static int leftEdge(int width, DeadzoneMode mode) {
        return rightEdge(width) - bandWidth(width, mode);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestDeadzoneGeometry" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/DeadzoneMode.java src/main/java/com/openggf/camera/DeadzoneGeometry.java src/test/java/com/openggf/camera/TestDeadzoneGeometry.java
git commit -m "feat: add width-driven deadzone geometry

Changelog: n/a: internal mechanism; user-facing entry in the DISPLAY_ASPECT commit
Guide: n/a: no guide change
Known-Discrepancies: n/a: no divergence
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: WIDESCREEN_DEADZONE_MODE documented in the DISPLAY_ASPECT commit
Skills: n/a: no skill change"
```

---

## Task 5: Wire Camera to use width-driven deadzone + snap

**Files:**
- Modify: `src/main/java/com/openggf/camera/Camera.java`

The deadzone math is verified by Task 4; this task swaps the literals so behaviour is width-driven, with native unchanged. Correctness at native is guaranteed by the existing camera behavioural tests staying green (run them in Step 4).

- [ ] **Step 1: Add a deadzone-mode field, read in the constructor**

Add a field near `width`/`height` (lines 68-69):

```java
	private com.openggf.configuration.DeadzoneMode deadzoneMode =
			com.openggf.configuration.DeadzoneMode.PROPORTIONAL;
```

In the `Camera(SonicConfigurationService configService)` constructor (lines 108-111), after the `height = ...` line, add:

```java
		deadzoneMode = com.openggf.configuration.DeadzoneMode.parse(
				configService.getString(com.openggf.configuration.SonicConfiguration.WIDESCREEN_DEADZONE_MODE));
```

- [ ] **Step 2: Replace the snap literal (line 127)**

Change:

```java
			x = (short) (focusedSprite.getCentreX() - 160);
```

to (the sprite snaps to the deadzone right edge = screen centre):

```java
			x = (short) (focusedSprite.getCentreX() - DeadzoneGeometry.rightEdge(width));
```

- [ ] **Step 3: Replace the scroll band literals (lines 334-348)**

Change the deadzone block in `computeNextHorizontalCameraX()`:

```java
		if (focusedSpriteRealX < 144) {
			short difference = (short) (focusedSpriteRealX - 144);
			if (difference < -cameraStepCap) {
				nextX -= cameraStepCap;
			} else {
				nextX += difference;
			}
		} else if (focusedSpriteRealX > 160) {
			short difference = (short) (focusedSpriteRealX - 160);
			if (difference > cameraStepCap) {
				nextX += cameraStepCap;
			} else {
				nextX += difference;
			}
		}
```

to:

```java
		int deadzoneLeft = DeadzoneGeometry.leftEdge(width, deadzoneMode);
		int deadzoneRight = DeadzoneGeometry.rightEdge(width);
		if (focusedSpriteRealX < deadzoneLeft) {
			short difference = (short) (focusedSpriteRealX - deadzoneLeft);
			if (difference < -cameraStepCap) {
				nextX -= cameraStepCap;
			} else {
				nextX += difference;
			}
		} else if (focusedSpriteRealX > deadzoneRight) {
			short difference = (short) (focusedSpriteRealX - deadzoneRight);
			if (difference > cameraStepCap) {
				nextX += cameraStepCap;
			} else {
				nextX += difference;
			}
		}
```

No import is needed for `DeadzoneGeometry`: `Camera` is already in `package com.openggf.camera`, the same package as `DeadzoneGeometry`. The `DeadzoneMode` references use the fully-qualified `com.openggf.configuration.DeadzoneMode` shown in Step 1, so no import is required there either.

- [ ] **Step 4: Run camera + native tests to verify no regression**

Run: `mvn "-Dtest=com.openggf.camera.*,TestDeadzoneGeometry" test`
Expected: PASS — all existing camera tests stay green (native width 320 reproduces 144/160/snap-160 exactly).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/camera/Camera.java
git commit -m "feat: drive camera deadzone and snap from configured width

Changelog: n/a: internal mechanism; user-facing entry in the DISPLAY_ASPECT commit
Guide: n/a: no guide change
Known-Discrepancies: n/a: native deadzone/snap unchanged (144/160)
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: no config-key change
Skills: n/a: no skill change"
```

---

## Task 6: Player right-boundary uses configured width

**Files:**
- Modify: `src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java:2226,2244`

`SCREEN_WIDTH - SONIC_WIDTH = 296 = 0x128` is the ROM-cited strict boundary; widening it to `camera.getWidth() - SONIC_WIDTH` is the deliberate divergence documented in the spec's "Parity Divergence: Right-Boundary Widening". At native (`getWidth()==320`) every path is byte-identical.

- [ ] **Step 1: Write the failing test (boundary helper extraction)**

To make this unit-testable without a full sprite/collision rig, extract the boundary math into a pure helper. Create test `src/test/java/com/openggf/sprites/managers/TestRightBoundary.java`:

```java
package com.openggf.sprites.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestRightBoundary {

    @Test
    void nativeStrictBoundaryIs0x128PastMaxX() {
        // strict (S3K / boss / end-of-level): maxX + width - 24, no +64
        assertEquals(1000 + 320 - 24, RightBoundary.compute(1000, 320, 24, 64, true));
    }

    @Test
    void nativeNormalBoundaryAddsRightExtra() {
        // normal: maxX + width - 24 + 64
        assertEquals(1000 + 320 - 24 + 64, RightBoundary.compute(1000, 320, 24, 64, false));
    }

    @Test
    void widescreenWidensBoundaryByWidthDelta() {
        assertEquals(1000 + 400 - 24, RightBoundary.compute(1000, 400, 24, 64, true));
        assertEquals(1000 + 400 - 24 + 64, RightBoundary.compute(1000, 400, 24, 64, false));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestRightBoundary" test`
Expected: FAIL — `RightBoundary` does not exist.

- [ ] **Step 3: Create the pure helper**

Create `src/main/java/com/openggf/sprites/managers/RightBoundary.java`:

```java
package com.openggf.sprites.managers;

/**
 * Pure computation of the player's right level-boundary clamp.
 * At native viewport width (320) this reproduces the ROM values exactly:
 * strict = maxX + 0x128, normal = maxX + 0x128 + 0x40. Widescreen widens the
 * boundary to track the configured viewport width (declared divergence).
 */
public final class RightBoundary {

    private RightBoundary() {
    }

    public static int compute(int maxX, int viewportWidth, int spriteWidth,
            int rightExtra, boolean strict) {
        int boundary = maxX + viewportWidth - spriteWidth;
        if (!strict) {
            boundary += rightExtra;
        }
        return boundary;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn "-Dtest=TestRightBoundary" test`
Expected: PASS (3 tests).

- [ ] **Step 5: Use the helper in `doLevelBoundary()`**

In `PlayableSpriteMovement.java`, change line 2226 to drop the local `SCREEN_WIDTH` and lines 2244-2252 to use the helper:

Replace:

```java
		final int SCREEN_WIDTH = 320, SONIC_WIDTH = 24, LEFT_OFFSET = 16, RIGHT_EXTRA = 64;
```

with:

```java
		final int SONIC_WIDTH = 24, LEFT_OFFSET = 16, RIGHT_EXTRA = 64;
```

Replace lines 2244-2252:

```java
		int rightBoundary = maxX + SCREEN_WIDTH - SONIC_WIDTH;
		PhysicsFeatureSet featureSet = sprite.getPhysicsFeatureSet();
		// S3K Player_Boundary_Sides/Tails_Check_Screen_Boundaries use
		// Camera_max_X_pos+$128 directly, with no normal-play +$40 extension
		// (sonic3k.asm:23183-23186, 28418-28421).
		boolean usesRomMaxPlus128 = featureSet != null && featureSet.levelBoundaryRightStrict();
		if (!usesRomMaxPlus128 && !gameState().isBossFightActive() && !gameState().isEndOfLevelActive()) {
			rightBoundary += RIGHT_EXTRA;
		}
```

with:

```java
		PhysicsFeatureSet featureSet = sprite.getPhysicsFeatureSet();
		// S3K Player_Boundary_Sides/Tails_Check_Screen_Boundaries use
		// Camera_max_X_pos+$128 directly, with no normal-play +$40 extension
		// (sonic3k.asm:23183-23186, 28418-28421). At native viewport width (320)
		// this reproduces +$128 / +$128+$40 exactly; widescreen widens the
		// boundary to the configured viewport width (declared divergence,
		// see KNOWN_DISCREPANCIES.md).
		boolean strict = (featureSet != null && featureSet.levelBoundaryRightStrict())
				|| gameState().isBossFightActive() || gameState().isEndOfLevelActive();
		int rightBoundary = RightBoundary.compute(maxX, camera.getWidth(), SONIC_WIDTH, RIGHT_EXTRA, strict);
```

- [ ] **Step 6: Run native regression for movement**

Run: `mvn "-Dtest=com.openggf.sprites.*,TestRightBoundary" test`
Expected: PASS — native movement/boundary tests unchanged.

- [ ] **Step 7: Document the divergence**

Add an entry to `docs/status/known-discrepancies.md` (and the S3K-specific notes it points to) describing the widescreen right-boundary widening: at native it reproduces the ROM `+$128` / `+$128+$40` exactly, but at widescreen the boundary widens to the configured viewport width, deliberately diverging from the ROM-cited `+$128` constant. Reference the spec section "Parity Divergence: Right-Boundary Widening".

- [ ] **Step 8: Commit (carries the divergence docs + trailers)**

```bash
git add src/main/java/com/openggf/sprites/managers/RightBoundary.java src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java src/test/java/com/openggf/sprites/managers/TestRightBoundary.java docs/status/known-discrepancies.md CHANGELOG.md
git commit -m "feat: drive player right-boundary from viewport width

Changelog: updated
Guide: n/a: no guide change
Known-Discrepancies: updated
S3K-Known-Discrepancies: updated
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: no config-key change
Skills: n/a: no skill change"
```

---

## Task 7: MGZ quake right-clamp uses configured width

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java:134,555`

This is the duplicate of the player right-boundary used during MGZ quake locks. It must follow the same width-driven rule.

- [ ] **Step 1: Replace the literal usage**

In `clampPlayerToCurrentViewportRightEdge()` (line 555), change:

```java
		int rightBoundary = (camera().getX() & 0xFFFF) + SCREEN_WIDTH - PLAYER_RIGHT_SCREEN_MARGIN;
```

to:

```java
		int rightBoundary = (camera().getX() & 0xFFFF) + camera().getWidth() - PLAYER_RIGHT_SCREEN_MARGIN;
```

- [ ] **Step 2: Remove the now-unused constant**

Delete line 134:

```java
	private static final int SCREEN_WIDTH = 320;
```

(Keep `PLAYER_RIGHT_SCREEN_MARGIN = 24`.)

- [ ] **Step 3: Run S3K MGZ tests**

Run: `mvn "-Dtest=TestSonic3kZoneFeatureProvider,com.openggf.game.sonic3k.*" test`
Expected: PASS — at native `getWidth()==320`, identical behaviour.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java
git commit -m "feat: drive MGZ quake right-clamp from viewport width

Changelog: n/a: same divergence already logged in the player right-boundary commit; no new user-facing behaviour
Guide: n/a: no guide change
Known-Discrepancies: n/a: covered by the right-boundary divergence entry
S3K-Known-Discrepancies: n/a: covered by the right-boundary divergence entry
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: no config-key change
Skills: n/a: no skill change"
```

---

## Task 8: Width-driven spawn loadAhead for all placement managers

**Files:**
- Modify: `src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java` — constant/ctor (3356-3360, 3458-3460), construction site (491), **and all six `LOAD_AHEAD` consumers** (3689, 3736, 3829, 4183, 4323, 4479)
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java:870-880`
- Modify: `src/main/java/com/openggf/game/sonic2/bumpers/CNZBumperManager.java:499-506`
- Test: `src/test/java/com/openggf/level/spawn/TestPlacementWindowWidth.java`

The legacy `loadAhead = 0x280` (640) decomposes as `extraAhead (320) + width (320)`. The base class will compute `loadAhead = viewportWidth() + extraAhead` at query time via an injected `IntSupplier`, so all streams follow the configured width. `unloadBehind` stays per-subclass (`0x80` / `0x300` / `768`).

> **CRITICAL:** `ObjectManager.Placement` references `LOAD_AHEAD` at **six** sites — the constructor (3459), the teleport guard (3736), and **five window-end computations** in counter-based reset (3689), refresh (3829), forward scan (4183), backward/OPL extension (4323), and post-camera extension (4479). Every one must move to the dynamic `getLoadAhead()`; if the constant is deleted while any reference remains, the file will not compile, and if any window-end keeps `LOAD_AHEAD` it stays fixed-width at widescreen. Enumerate and replace all six (Step 5).

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.level.spawn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestPlacementWindowWidth {

    /** Minimal SpawnPoint for testing window math. */
    private record Point(int x, int y) implements SpawnPoint {
    }

    /** Test subclass exposing the protected window math with a settable width. */
    private static final class TestPlacement extends AbstractPlacementManager<Point> {
        TestPlacement(int extraAhead, int unloadBehind, java.util.function.IntSupplier width) {
            super(List.of(new Point(0, 0)), extraAhead, unloadBehind, width);
        }
        int windowEnd(int cameraX) { return getWindowEnd(cameraX); }
        int loadAhead() { return getLoadAhead(); }
    }

    @Test
    void nativeWindowEqualsLegacy640() {
        // extraAhead 320 + width 320 = 640 = 0x280
        TestPlacement p = new TestPlacement(320, 0x80, () -> 320);
        assertEquals(640, p.loadAhead());
        // cameraX 0 -> chunkAligned 0 -> windowEnd 640
        assertEquals(640, p.windowEnd(0));
    }

    @Test
    void widescreenWindowScalesWithWidth() {
        TestPlacement p = new TestPlacement(320, 0x80, () -> 400);
        assertEquals(720, p.loadAhead()); // 400 + 320
        assertEquals(720, p.windowEnd(0));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn "-Dtest=TestPlacementWindowWidth" test`
Expected: FAIL — no 4-arg `AbstractPlacementManager` constructor; `getLoadAhead()` is not width-driven.

- [ ] **Step 3: Add the width-driven constructor + dynamic loadAhead to the base**

In `AbstractPlacementManager.java`, add an import and a `widthSupplier` field, a new constructor, and make `loadAhead` derivation dynamic. Replace the existing field block (lines 25-26) and constructor (lines 28-37):

```java
	private final int loadAhead;
	private final int unloadBehind;
```

becomes:

```java
	private final int extraAhead;
	private final int loadAheadFixed; // legacy fixed value when no width supplier is given
	private final java.util.function.IntSupplier widthSupplier; // null => use loadAheadFixed
	private final int unloadBehind;
```

Replace the constructor:

```java
	protected AbstractPlacementManager(List<T> spawns, int loadAhead, int unloadBehind) {
		ArrayList<T> sorted = new ArrayList<>(spawns);
		sorted.sort(Comparator.comparingInt(SpawnPoint::x));
		this.spawns = Collections.unmodifiableList(sorted);
		this.loadAhead = loadAhead;
		this.unloadBehind = unloadBehind;
		for (int i = 0; i < this.spawns.size(); i++) {
			spawnIndexMap.put(this.spawns.get(i), i);
		}
	}
```

with two constructors:

```java
	/** Legacy fixed-window constructor (used only by NATIVE-equivalent callers / tests). */
	protected AbstractPlacementManager(List<T> spawns, int loadAhead, int unloadBehind) {
		this(spawns, loadAhead, unloadBehind, null, 0);
	}

	/**
	 * Width-driven constructor. The load-ahead window is computed at query time
	 * as {@code widthSupplier.getAsInt() + extraAhead}, so the spawn window
	 * follows the configured viewport width. At native width (320) with the
	 * standard extraAhead (320) this equals the legacy 0x280 (640) window.
	 */
	protected AbstractPlacementManager(List<T> spawns, int extraAhead, int unloadBehind,
			java.util.function.IntSupplier widthSupplier) {
		this(spawns, 0, unloadBehind, widthSupplier, extraAhead);
	}

	private AbstractPlacementManager(List<T> spawns, int loadAheadFixed, int unloadBehind,
			java.util.function.IntSupplier widthSupplier, int extraAhead) {
		ArrayList<T> sorted = new ArrayList<>(spawns);
		sorted.sort(Comparator.comparingInt(SpawnPoint::x));
		this.spawns = Collections.unmodifiableList(sorted);
		this.loadAheadFixed = loadAheadFixed;
		this.extraAhead = extraAhead;
		this.widthSupplier = widthSupplier;
		this.unloadBehind = unloadBehind;
		for (int i = 0; i < this.spawns.size(); i++) {
			spawnIndexMap.put(this.spawns.get(i), i);
		}
	}
```

Replace `getLoadAhead()` (lines 89-91) and the `getWindowEnd` body (lines 116-119):

```java
	protected int getLoadAhead() {
		return loadAhead;
	}
```

becomes:

```java
	protected int getLoadAhead() {
		return widthSupplier != null ? widthSupplier.getAsInt() + extraAhead : loadAheadFixed;
	}
```

and:

```java
	protected int getWindowEnd(int cameraX) {
		int chunkAligned = cameraX & CHUNK_ALIGN_MASK;
		return chunkAligned + loadAhead;
	}
```

becomes:

```java
	protected int getWindowEnd(int cameraX) {
		int chunkAligned = cameraX & CHUNK_ALIGN_MASK;
		return chunkAligned + getLoadAhead();
	}
```

- [ ] **Step 4: Run the placement test to verify it passes**

Run: `mvn "-Dtest=TestPlacementWindowWidth" test`
Expected: PASS (2 tests).

- [ ] **Step 5: Switch the three subclasses to the width-driven constructor**

`ObjectManager` already holds the injected `Camera`, which is the authoritative width source (`Camera` reads `SCREEN_WIDTH_PIXELS` at construction). Use `camera::getWidth` for its Placement. `RingManager` and `CNZBumperManager` have no camera; they read the configured width through a **null-safe helper** rather than calling `GameServices.configuration()` directly in the lambda — that call resolves through `EngineServices.current()` (which self-bootstraps but, in headless tests run without `EngineServices.configure(...)`, yields a bootstrap config rather than the engine's resolved one, and could throw if bootstrap fails). The helper guards with a native-width fallback so the per-frame lambda never throws.

**5z. Create the null-safe width helper** — `src/main/java/com/openggf/level/spawn/PlacementViewportWidth.java`:

```java
package com.openggf.level.spawn;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.game.GameServices;

/**
 * Null-safe reader of the configured viewport pixel width for placement
 * windowing. Falls back to the native 320 if engine services / config are not
 * available (e.g. headless unit tests), so the per-frame width supplier never
 * throws.
 */
public final class PlacementViewportWidth {

    public static final int NATIVE = 320;

    private PlacementViewportWidth() {
    }

    public static int current() {
        try {
            return GameServices.configuration().getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        } catch (RuntimeException ex) {
            return NATIVE;
        }
    }
}
```

**5a. ObjectManager constant (lines 3356-3360):**

```java
        // ROM: ObjectsManager_GoingForward (s2.asm) uses addi.w #$280,d6 for forward load range.
        // Behind-window unload range is one chunk ($80) for forward movement.
        private static final int LOAD_AHEAD = 0x280;
        private static final int UNLOAD_BEHIND = 0x80;
```

becomes (drop `LOAD_AHEAD`; the ahead distance is now dynamic via `getLoadAhead()`):

```java
        // ROM: ObjectsManager_GoingForward (s2.asm) loads (viewport width + 0x140)
        // ahead; native (320) yields the original 0x280 (640) window.
        private static final int EXTRA_AHEAD = 0x140; // 320
        private static final int UNLOAD_BEHIND = 0x80;
```

**5b. ObjectManager Placement constructor (lines 3458-3460):** add an `IntSupplier` param so the width source is injectable (also makes the window testable):

```java
        Placement(List<ObjectSpawn> spawns) {
            super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
        }
```

becomes:

```java
        Placement(List<ObjectSpawn> spawns, java.util.function.IntSupplier widthSupplier) {
            super(spawns, EXTRA_AHEAD, UNLOAD_BEHIND, widthSupplier);
        }
```

**5c. ObjectManager construction site (line 491):** pass the camera width supplier:

```java
        this.placement = new Placement(spawns);
```

becomes:

```java
        this.placement = new Placement(spawns, camera::getWidth);
```

**5d. ObjectManager — replace ALL SIX `LOAD_AHEAD` references with `getLoadAhead()`:**

- Line 3689 (counter-based reset window):
  ```java
            int windowEnd = cameraChunk + LOAD_AHEAD;
  ```
  →
  ```java
            int windowEnd = cameraChunk + getLoadAhead();
  ```
- Line 3736 (teleport guard):
  ```java
                    && Math.abs((long) cameraX - lastCameraX) > (LOAD_AHEAD + UNLOAD_BEHIND)) {
  ```
  →
  ```java
                    && Math.abs((long) cameraX - lastCameraX) > (getLoadAhead() + getUnloadBehind())) {
  ```
- Line 3829 (refresh window):
  ```java
            int windowEnd = cameraChunk + LOAD_AHEAD;
  ```
  →
  ```java
            int windowEnd = cameraChunk + getLoadAhead();
  ```
- Line 4183 (forward scan):
  ```java
            int windowEnd = oplChunk + LOAD_AHEAD;
  ```
  →
  ```java
            int windowEnd = oplChunk + getLoadAhead();
  ```
- Line 4323 (OPL right-cursor extension):
  ```java
            int rightEdge = oplChunk + LOAD_AHEAD;
  ```
  →
  ```java
            int rightEdge = oplChunk + getLoadAhead();
  ```
- Line 4479 (post-camera extension):
  ```java
                int newWindowEnd = postChunk + LOAD_AHEAD;
  ```
  →
  ```java
                int newWindowEnd = postChunk + getLoadAhead();
  ```

Also update the comment at line 3688 (`// cameraChunk + LOAD_AHEAD, assigning counter values.`) and 4179/4322 references to read `getLoadAhead()`. After this step, grep `LOAD_AHEAD` in `ObjectManager.java` must return **zero** matches.

**5e. RingManager.java** RingPlacement (lines 870-880):

```java
        private static final int LOAD_AHEAD = 0x280;
        private static final int UNLOAD_BEHIND = 0x300;
```

becomes:

```java
        private static final int EXTRA_AHEAD = 0x140; // 320; native -> 0x280 window
        private static final int UNLOAD_BEHIND = 0x300;
```

and the super() call:

```java
            super(spawns, LOAD_AHEAD, UNLOAD_BEHIND);
```

becomes:

```java
            super(spawns, EXTRA_AHEAD, UNLOAD_BEHIND,
                    com.openggf.level.spawn.PlacementViewportWidth::current);
```

**5f. CNZBumperManager.java** Placement (lines 499-506):

```java
        private static final int LOAD_AHEAD = 640;
        private static final int UNLOAD_BEHIND = 768;
```

becomes:

```java
        private static final int EXTRA_AHEAD = 320; // native -> 640 window
        private static final int UNLOAD_BEHIND = 768;
```

and the super() call:

```java
            super(bumpers, LOAD_AHEAD, UNLOAD_BEHIND);
```

becomes:

```java
            super(bumpers, EXTRA_AHEAD, UNLOAD_BEHIND,
                    com.openggf.level.spawn.PlacementViewportWidth::current);
```

- [ ] **Step 6: Verify no `LOAD_AHEAD` remains, then run native regression**

First confirm the conversion is complete (must print nothing):

Run: `git grep -n "LOAD_AHEAD" -- src/main/java/com/openggf/level/objects/ObjectManager.java`
Expected: no output (zero matches). Any match means a counter/OPL window-end was missed and would stay fixed-width — fix it before proceeding.

Then run the placement + native suites:

Run: `mvn "-Dtest=TestPlacementWindowWidth,com.openggf.level.objects.*,com.openggf.level.rings.*" test`
Expected: PASS. The counter-based reset/refresh/forward/backward/post-camera paths now all route through the unit-tested `getLoadAhead()`, so they equal 640 at native (covered by the existing ObjectManager placement tests) and scale with width (covered by `TestPlacementWindowWidth`).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java src/main/java/com/openggf/level/spawn/PlacementViewportWidth.java src/main/java/com/openggf/level/objects/ObjectManager.java src/main/java/com/openggf/level/rings/RingManager.java src/main/java/com/openggf/game/sonic2/bumpers/CNZBumperManager.java src/test/java/com/openggf/level/spawn/TestPlacementWindowWidth.java
git commit -m "feat: drive spawn load window from configured viewport width

Changelog: n/a: internal spawn-window mechanism; user-facing entry in the DISPLAY_ASPECT commit
Guide: n/a: no guide change
Known-Discrepancies: n/a: no divergence (native window unchanged at 640)
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: no config-key change
Skills: n/a: no skill change"
```

---

## Task 9: Literal-320 guard test (focused, allowlist-free)

**Files:**
- Test: `src/test/java/com/openggf/widescreen/TestNoScreenWidth320Literals.java`

A tree-wide guard is **not** appropriate yet: a scan of `src/main/java/com/openggf` finds **31 screen-width-style `320` matches across 29 files** (title screens, level-select constants, special-stage and data-select renderers, results screens, title cards, fade, background renderer, legal disclaimer, trace HUD, etc.). All but two are rendering/UI literals owned by the **Widescreen Rendering** plan and later UI-widening phases; building a 27-file allowlist now would be brittle and would hide misclassifications.

Instead, this guard is **scoped to exactly the files this Foundation plan makes width-driven**, with no allowlist. It asserts those files contain no screen-width `320` literal — catching a regression if a later edit reintroduces one. The comprehensive tree-wide guard is deferred to the Rendering plan, where the UI/rendering literals are actually removed and can be enforced without a giant allowlist.

> The regex is a **heuristic tripwire**, not a proof: it matches the screen-width-shaped forms (`SCREEN_W… = 320`, `= 320 … 224`, `320, 224`) and will miss a bare/arithmetic/hex `320` (e.g. `x * 320`, `0x140`-style, lowercase). That is acceptable here because the six guarded files are small and were just hand-converted; the guard's job is to catch an accidental reintroduction, not to certify absence. Do not oversell it as comprehensive.

**Full current inventory (for the Rendering plan / later phases — do NOT fix in this slice unless flagged):**
`game/LegalDisclaimerScreen`, `game/MasterTitleScreen`, `game/sonic1/dataselect/S1DataSelectImageCacheManager`, `game/sonic1/levelselect/Sonic1LevelSelectConstants`, `game/sonic1/specialstage/Sonic1SpecialStageBackgroundRenderer`, `game/sonic1/titlecard/Sonic1TitleCardManager`, `game/sonic1/titlescreen/Sonic1TitleScreenManager`, `game/sonic2/credits/Sonic2EndingCutsceneManager`, `game/sonic2/dataselect/S2DataSelectImageCacheManager`, `game/sonic2/debug/Sonic2SpecialStageSpriteDebug`, `game/sonic2/levelselect/LevelSelectConstants`, `game/sonic2/objects/SpecialStageResultsScreenObjectInstance`, `game/sonic2/specialstage/SpecialStageBackgroundRenderer`, `game/sonic2/titlecard/TitleCardManager`, `game/sonic2/titlescreen/TitleScreenManager`, `game/sonic3k/dataselect/S3kDataSelectPresentation`, `game/sonic3k/dataselect/S3kDataSelectRenderer`, `game/sonic3k/levelselect/Sonic3kLevelSelectConstants`, `game/sonic3k/specialstage/Sonic3kSpecialStageRenderer`, `game/sonic3k/titlecard/Sonic3kTitleCardManager`, `game/sonic3k/titlescreen/Sonic3kTitleScreenManager`, `game/titlecard/TitleCardElement`, `graphics/FadeManager`, `level/objects/AbstractResultsScreen`, `level/render/BackgroundRenderer`, `testmode/TraceHudOverlay`.

> **One inventory entry needs assessment, not deferral:** `level/objects/AbstractObjectInstance` contains a `320` literal. If it gates object on-screen/despawn visibility, it is gameplay-relevant (objects would pop/despawn at the native edge under widescreen) and belongs in this Foundation slice, not the Rendering plan. **Before finishing this task, open `AbstractObjectInstance.java`, read the `320` usage, and either (a) bring it under `camera.getWidth()` here with a test, or (b) record an explicit justification that it is render-only and add it to the deferred inventory.** Do not silently defer it.

- [ ] **Step 1: Write the focused guard test**

```java
package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Focused guard: the gameplay/camera/placement files this widescreen Foundation
 * made width-driven must not reintroduce a hardcoded screen-width 320 literal.
 * The comprehensive tree-wide guard is deferred to the Widescreen Rendering plan
 * (29 files currently carry such literals; almost all are rendering/UI owned by
 * that plan). New code in these files must use camera.getWidth() /
 * SCREEN_WIDTH_PIXELS instead.
 */
class TestNoScreenWidth320Literals {

    // Matches a 320 literal that looks like a screen-width constant, e.g.
    // "SCREEN_WIDTH = 320", "= 320 ... 224", "320, 224".
    private static final Pattern SCREEN_320 =
            Pattern.compile("(SCREEN_W\\w*\\s*=\\s*320)|(=\\s*320\\b.*224)|(\\b320\\s*,\\s*224\\b)");

    // Exactly the files this Foundation plan made width-driven.
    private static final List<String> GUARDED_FILES = List.of(
            "src/main/java/com/openggf/camera/Camera.java",
            "src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java",
            "src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java",
            "src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java",
            "src/main/java/com/openggf/level/objects/ObjectManager.java",
            "src/main/java/com/openggf/level/rings/RingManager.java",
            "src/main/java/com/openggf/game/sonic2/bumpers/CNZBumperManager.java");

    @Test
    void guardedFilesHaveNoScreenWidth320Literal() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String file : GUARDED_FILES) {
            List<String> lines = Files.readAllLines(Path.of(file));
            for (int i = 0; i < lines.size(); i++) {
                if (SCREEN_320.matcher(lines.get(i)).find()) {
                    offenders.add(file + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Screen-width 320 literal reintroduced in a width-driven file "
                        + "(use camera.getWidth() / SCREEN_WIDTH_PIXELS):\n"
                        + String.join("\n", offenders));
    }
}
```

- [ ] **Step 2: Run the guard**

Run: `mvn "-Dtest=TestNoScreenWidth320Literals" test`
Expected: PASS — Tasks 5-8 already removed the `320` literals from these files. If it FAILS, the report names the file:line still carrying the literal; fix the code (do not weaken the guard).

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/openggf/widescreen/TestNoScreenWidth320Literals.java
git commit -m "test: guard width-driven files against screen-width 320 literals

Changelog: n/a: test-only, does not touch src/main
Guide: n/a: no guide change
Known-Discrepancies: n/a: no divergence
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: no config-key change
Skills: n/a: no skill change"
```

---

## Task 10: Native regression aggregate test

**Files:**
- Test: `src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java`

A single headless test pinning the parity invariant: at the default config, every width-derived value equals its historical native constant.

- [ ] **Step 1: Write the test**

```java
package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.camera.DeadzoneGeometry;
import com.openggf.configuration.DeadzoneMode;
import com.openggf.configuration.DisplayWindowPolicy;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.configuration.WidescreenAspect;
import com.openggf.sprites.managers.RightBoundary;
import org.junit.jupiter.api.Test;

/** Pins that the default (NATIVE_4_3) path reproduces every historical constant. */
class TestWidescreenNativeRegression {

    @Test
    void defaultConfigResolvesToNativeDimensions() {
        SonicConfigurationService cfg = new SonicConfigurationService();
        cfg.resolveDisplayAspect();
        assertEquals(320, cfg.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals(224, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
        assertEquals(640, cfg.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(448, cfg.getInt(SonicConfiguration.SCREEN_HEIGHT));
    }

    @Test
    void nativeCameraConstantsUnchanged() {
        for (DeadzoneMode mode : DeadzoneMode.values()) {
            assertEquals(144, DeadzoneGeometry.leftEdge(320, mode));
            // rightEdge(320) == 160 is both the scroll trigger and the focus-snap rest point.
            assertEquals(160, DeadzoneGeometry.rightEdge(320));
        }
    }

    @Test
    void nativeBoundaryConstantsUnchanged() {
        assertEquals(1000 + 0x128, RightBoundary.compute(1000, 320, 24, 64, true));
        assertEquals(1000 + 0x128 + 0x40, RightBoundary.compute(1000, 320, 24, 64, false));
    }

    @Test
    void nativeWindowPolicyUnchanged() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.NATIVE_4_3, true, 640, 448);
        assertEquals(320, r.pixelWidth());
        assertEquals(640, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }
}
```

- [ ] **Step 2: Run to verify it passes**

Run: `mvn "-Dtest=TestWidescreenNativeRegression" test`
Expected: PASS (4 tests).

- [ ] **Step 3: Full suite sanity + commit**

Run: `mvn test`
Expected: PASS — no regressions across the suite at native config.

```bash
git add src/test/java/com/openggf/widescreen/TestWidescreenNativeRegression.java
git commit -m "test: pin native-parity regression for widescreen foundation

Changelog: n/a: test-only, does not touch src/main
Guide: n/a: no guide change
Known-Discrepancies: n/a: no divergence
S3K-Known-Discrepancies: n/a: no S3K divergence
Agent-Docs: n/a: no agent-doc change
Configuration-Docs: n/a: no config-key change
Skills: n/a: no skill change"
```

---

## Documentation / Branch Policy

Per `CLAUDE.md` Branch Documentation Policy, **every** commit on this branch carries the 7-trailer block (the `prepare-commit-msg` hook auto-appends a blank one — fill it, never `--no-verify`). Docs are **folded into the commit that introduces the matching change**, not deferred, because the `commit-msg` hook + CI reject a bare `Changelog: n/a` on a `feat`/`fix`/`perf` commit touching `src/main/` and require staged↔trailer consistency:

- **Config surface** (`Changelog: updated`, `Configuration-Docs: updated`): folded into **Task 3** — stage `CHANGELOG.md`, `CONFIGURATION.md`, `docs/guide/playing/configuration.md` with the config code.
- **Right-boundary divergence** (`Known-Discrepancies: updated`, `S3K-Known-Discrepancies: updated`): folded into **Task 6** — stage `docs/status/known-discrepancies.md` with the boundary code.
- **Intermediate internal commits** (Tasks 1, 2, 4, 5, 8): `feat:` touching `src/main/` with no user-facing behaviour → use justified `Changelog: n/a: <reason>` (a bare `n/a` is rejected). Reason: "internal mechanism; user-facing entry in the DISPLAY_ASPECT commit".
- **Test-only commits** (Tasks 9, 10): touch only `src/test/` → exempt from the changelog-justification rule; plain `n/a` per trailer is fine.

No separate trailing documentation task — each commit above is self-attesting.

---

## Self-Review Notes

- **Spec coverage (Foundation slice):** config presets (Task 1-3), window sizing (Task 2-3), deadzone modes + snap (Task 4-5), player boundary + MGZ duplicate (Task 6-7), placement windows all-streams (Task 8), focused literal-320 guard (Task 9), native regression (Task 10). Rendering-layer items (UI pillarbox, parallax/GPU columns, BG FBO, fade verification) and the comprehensive tree-wide 320 guard are explicitly deferred to the Rendering plan per the Scope section.
- **Type consistency:** `WidescreenAspect.pixelWidth()`, `DeadzoneMode`, `DeadzoneGeometry.{rightEdge,leftEdge,bandWidth,snapOffset}`, `DisplayWindowPolicy.resolve(...)->Resolved`, `RightBoundary.compute(...)`, `AbstractPlacementManager(spawns, extraAhead, unloadBehind, IntSupplier)`, and the injectable `ObjectManager.Placement(spawns, IntSupplier)` are used consistently across tasks.
- **API correctness (verified against source):** uses `SonicConfigurationService.createStandalone()` + `setConfigValue(...)` (the public construction/setter path; the constructor is private), `getDefaultValue(...)` for the persistence assertion, and `camera.getWidth()` / `camera::getWidth` (verified accessor). The `getConfigValue` overlay edit matches the real method body.
- **Persistence safety:** derived display values go to a non-persisted `transientResolved` overlay read first by `getConfigValue` and never written by `saveConfig`, and `resolveDisplayAspect()` is called after the constructor's save decision — so widening never rewrites the user's `config.json` (Task 3).
- **No window stomp:** `DisplayWindowPolicy` derives the window only for a widescreen preset with autosize on; `NATIVE_4_3` and autosize-off preserve the caller's window, so a user's custom size survives the default-true flag. A window change is logged (Task 2/3).
- **Headless-safe suppliers:** Ring/CNZ width suppliers go through `PlacementViewportWidth.current()` (try/catch → 320 fallback) so the per-frame lambda never throws when `EngineServices` isn't configured; `ObjectManager` uses its injected `camera::getWidth` (Task 8).
- **Deadzone reconciled with spec:** geometry is right-edge-anchored at screen-center (not band-centered on `width/2`), faithfully generalizing native's asymmetry; `rightEdge` is the single function for both scroll-trigger and snap (no duplicate `snapOffset`); the leftward-only widening is documented as a deliberate decision (Task 4).
- **Spec'd warnings:** invalid `DISPLAY_ASPECT` logs a warning before fallback (`WidescreenAspect.parse`); the spec's "disagreeing SCREEN_WIDTH_PIXELS" warning is superseded by the derived-overlay model (documented in the `resolveDisplayAspect` javadoc).
- **ObjectManager completeness:** all six `LOAD_AHEAD` references (ctor + teleport guard + four counter/OPL window-ends) are enumerated and converted to `getLoadAhead()`, with a `git grep` zero-match gate (Task 8 Step 6) to prove the conversion compiles and is width-driven everywhere.
- **No placeholders:** every code step shows complete code. The remaining "open the file and decide" instruction (Task 9, `AbstractObjectInstance`) is a real classification action with a forced outcome (fix-with-test or justify-and-defer), not a TODO.
