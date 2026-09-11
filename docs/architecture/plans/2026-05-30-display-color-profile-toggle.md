# Display Color Profile Toggle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add runtime-switchable display color profiles with persisted config and a short bottom-left confirmation message.

**Architecture:** Keep ROM palette data unchanged and apply display conversion only while writing palette colors to GPU buffers and palette-derived clear colors. Add a small runtime controller for key handling, config persistence, palette refresh, and notification state. Render the notification through the existing pixel-font screen-space text path after the fade pass.

**Tech Stack:** Java, LWJGL/OpenGL, Jackson-backed `SonicConfigurationService`, JUnit 5.

---

### Task 1: Config Keys and Key Alias

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java`
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java`
- Modify: `src/main/java/com/openggf/configuration/GlfwKeyNameResolver.java`
- Test: `src/test/java/com/openggf/configuration/TestGlfwKeyNameResolver.java`
- Test: `src/test/java/com/openggf/configuration/TestConfigKeyNameResolution.java`

- [ ] **Step 1: Write failing tests**

Add tests that `GlfwKeyNameResolver.resolve("#")` returns `GLFW_KEY_WORLD_1`, and that new defaults are `DISPLAY_COLOR_PROFILE = "RAW_RGB"` and `DISPLAY_COLOR_PROFILE_TOGGLE_KEY = "WORLD_1"`.

- [ ] **Step 2: Run focused config tests**

Run: `mvn "-Dtest=com.openggf.configuration.TestGlfwKeyNameResolver,com.openggf.configuration.TestConfigKeyNameResolution" test`

Expected: fails because the new enum values/defaults and `"#"` alias do not exist.

- [ ] **Step 3: Implement config additions**

Add `DISPLAY_COLOR_PROFILE` and `DISPLAY_COLOR_PROFILE_TOGGLE_KEY` to `SonicConfiguration`, add defaults in `SonicConfigurationService.applyDefaults()`, and add explicit alias support in `GlfwKeyNameResolver` so `"#"` maps to `GLFW_KEY_WORLD_1`.

- [ ] **Step 4: Re-run focused config tests**

Run: `mvn "-Dtest=com.openggf.configuration.TestGlfwKeyNameResolver,com.openggf.configuration.TestConfigKeyNameResolution" test`

Expected: passes.

### Task 2: Display Color Profiles

**Files:**
- Create: `src/main/java/com/openggf/graphics/color/DisplayColorProfile.java`
- Create: `src/main/java/com/openggf/graphics/color/DisplayColorConverter.java`
- Test: `src/test/java/com/openggf/graphics/color/TestDisplayColorConverter.java`

- [ ] **Step 1: Write failing conversion tests**

Cover `RAW_RGB` identity behavior and deterministic darker/desaturated output for `MD_ANALOG` and `NTSC_SOFT` using representative colors: black, white, mid gray, red, green, blue.

- [ ] **Step 2: Run focused conversion tests**

Run: `mvn "-Dtest=com.openggf.graphics.color.TestDisplayColorConverter" test`

Expected: fails because the new classes do not exist.

- [ ] **Step 3: Implement profile enum and converter**

Implement profile parsing with fallback to `RAW_RGB`, human labels, cycling order, and conversion methods that transform existing `Palette.Color` byte values into upload RGB bytes without mutating the source color.

- [ ] **Step 4: Re-run focused conversion tests**

Run: `mvn "-Dtest=com.openggf.graphics.color.TestDisplayColorConverter" test`

Expected: passes.

### Task 3: Palette Upload Refresh

**Files:**
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Test: `src/test/java/com/openggf/graphics/TestGraphicsManagerHeadless.java`

- [ ] **Step 1: Write failing graphics-manager tests**

In headless mode, verify `cachePaletteTexture(...)` retains source palettes by line, `refreshAllPaletteTextures()` preserves cached-line records, and the selected profile affects generated upload bytes through a package-visible/test-visible helper.

- [ ] **Step 2: Run focused graphics tests**

Run: `mvn "-Dtest=com.openggf.graphics.TestGraphicsManagerHeadless" test`

Expected: fails because refresh/source retention and conversion helper do not exist.

- [ ] **Step 3: Implement retained palette sources and shared byte writer**

Store cached `Palette` references by palette line, store last underwater palette inputs, replace the duplicate normal/underwater RGB byte loops with one helper that applies `DisplayColorConverter`, and expose `refreshAllPaletteTextures()`.

- [ ] **Step 4: Re-run focused graphics tests**

Run: `mvn "-Dtest=com.openggf.graphics.TestGraphicsManagerHeadless" test`

Expected: passes.

### Task 4: Runtime Controller and Notification

**Files:**
- Create: `src/main/java/com/openggf/graphics/color/DisplayColorProfileController.java`
- Modify: `src/main/java/com/openggf/Engine.java`
- Test: `src/test/java/com/openggf/graphics/color/TestDisplayColorProfileController.java`

- [ ] **Step 1: Write failing controller tests**

Test that a key press cycles the profile, writes the new profile name to config, requests palette refresh, exposes `Color: <label>` for 120 frames, and then hides the message.

- [ ] **Step 2: Run focused controller tests**

Run: `mvn "-Dtest=com.openggf.graphics.color.TestDisplayColorProfileController" test`

Expected: fails because the controller does not exist.

- [ ] **Step 3: Implement controller**

Implement controller construction from `SonicConfigurationService`, `InputHandler` edge handling, `saveConfig()`, `GraphicsManager.refreshAllPaletteTextures()`, and notification countdown.

- [ ] **Step 4: Wire into Engine**

Instantiate the controller during engine init after config and graphics manager are available. Call controller update before drawing HUD overlays, and render its notification after the fade pass through `traceHudTextRenderer.drawShadowedText(...)` at bottom-left coordinates.

- [ ] **Step 5: Re-run focused controller tests**

Run: `mvn "-Dtest=com.openggf.graphics.color.TestDisplayColorProfileController" test`

Expected: passes.

### Task 5: Documentation and Verification

**Files:**
- Modify: `docs/guide/playing/configuration.md`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Document config keys**

Add the display profile values, default toggle key, `#` alias caveat, and bottom-left confirmation message.

- [ ] **Step 2: Run focused test set**

Run: `mvn "-Dtest=com.openggf.configuration.TestGlfwKeyNameResolver,com.openggf.configuration.TestConfigKeyNameResolution,com.openggf.graphics.color.TestDisplayColorConverter,com.openggf.graphics.color.TestDisplayColorProfileController,com.openggf.graphics.TestGraphicsManagerHeadless" test`

Expected: passes.

- [ ] **Step 3: Run broader verification**

Run: `mvn test`

Expected: passes, or report any unrelated pre-existing failures with exact output.
