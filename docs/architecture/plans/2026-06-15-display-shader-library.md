# Runtime Display Shader Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a user-facing display shader library rooted at `shaders/`, discovered at runtime, selectable through quick cycling plus a searchable/category-aware picker, optionally populated by downloading the upstream libretro GLSL pack, and applied as a display-only post-process pass that never affects physics/traces.

**Architecture:** Pure-logic layers first (config, discovery, `.cgp`/`.glslp` parsing, RetroArch GLSL normalization, downloader, picker model, controller) so the bulk is unit-testable headlessly via JUnit 5. A thin GL layer (`DisplayShaderPipeline`) sits behind a `Predicate<DisplayShaderPresetRef>` activation callback so the controller stays GL-free and testable. `GraphicsManager` owns the pipeline; `Engine.display()` drives capture/composite at a configurable render phase, mirroring the existing post-fade diagnostic guard. The controller mirrors the existing `DisplayColorProfileController` notification pattern.

**Tech Stack:** Java 21, LWJGL/OpenGL, JUnit 5 (Jupiter only), existing `FboHelper`, `ConfigCatalog`/`SonicConfiguration`, `InputHandler`.

**Source of truth:** `docs/architecture/designs/2026-06-15-display-shader-library-design.md`.

**New package:** `com.openggf.graphics.shaderlib` (all new classes + tests under `src/test/java/com/openggf/graphics/shaderlib`).

**Commit / trailer note:** This repo's `prepare-commit-msg` hook auto-appends a trailer block (`Changelog`, `Guide`, `Known-Discrepancies`, `S3K-Known-Discrepancies`, `Agent-Docs`, `Configuration-Docs`, `Skills`). Fill it in per commit — do not delete it, do not use `--no-verify`. For `feat`/`fix` commits touching `src/main/`, set `Changelog: updated` and stage `CHANGELOG.md`, or justify `Changelog: n/a: <reason>`. Config-key commits that touch `CONFIGURATION.md` must set `Configuration-Docs: updated`. Run `mvn` once before the first commit so the hook is installed (`core.hooksPath -> .githooks`), or run `git config core.hooksPath .githooks` manually.

**Build commands (PowerShell):**
- Single test class: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderLibrary" test`
- Full suite: `mvn test`

---

## File Structure

**Create (`src/main/java/com/openggf/graphics/shaderlib/`):**
- `ShaderPhase.java` — enum `{ SCENE, PRESENTATION, FINAL }`
- `ScaleType.java` — enum `{ SOURCE, VIEWPORT }`
- `WrapMode.java` — enum for supported preset texture wrap hints
- `GlslShape.java` — enum `{ COMBINED, FRAGMENT_ONLY }`
- `DisplayShaderPresetRef.java` — record describing one library entry (incl. `OFF`)
- `DisplayShaderLibrary.java` — recursive scan + sorted entry list
- `UnsupportedShaderException.java` — thrown when a preset has no GLSL source / uses unsupported features
- `DisplayShaderPass.java` — record: resolved GLSL + scale metadata for one pass
- `DisplayShaderPreset.java` — record: label + phase + passes
- `DisplayShaderPresetLoader.java` — parse `.cgp` / `.glslp`, resolve GLSL pass sources, load standalone `.glsl`
- `RetroArchGlslCompat.java` — pure string normalizer for OpenGL 4.1 core-compatible generated stage sources
- `DisplayShaderSelectionModel.java` — GL-free searchable/category-aware selection model for large libraries
- `DisplayShaderController.java` — cycling, persistence, notification state (GL-free)
- `DisplayShaderPipeline.java` — GL: compile, ping-pong FBOs, uniform binding, composite (Phase E)

**Create (`src/main/java/com/openggf/util/`):**
- `RetroArchGlslShaderPackDownloader.java` — self-contained libretro GLSL zip download, update check, progress, staged extraction

**Modify:**
- `src/main/java/com/openggf/configuration/SonicConfiguration.java` — 6 new enum constants
- `src/main/java/com/openggf/configuration/ConfigCatalog.java` — register the 6 keys in `display`
- `src/main/java/com/openggf/configuration/SonicConfigurationService.java` — defaults + unbind playback keys
- `src/main/resources/config.yaml` — add `display.shader*`, unbind `debug.playback.*Key`
- `CONFIGURATION.md` — document new keys, unbound playback defaults, contract, limitations
- `src/main/java/com/openggf/graphics/GraphicsManager.java` — own + init the pipeline
- `src/main/java/com/openggf/Engine.java` — controller wiring, phase capture/composite, stacked notification, screenshot note

**Test (`src/test/java/com/openggf/graphics/shaderlib/`):** one test class per logic component, plus `src/test/java/com/openggf/configuration/TestPlaybackKeyDefaultsUnbound.java`.

**Test (`src/test/java/com/openggf/util/`):**
- `RetroArchGlslShaderPackDownloaderTest.java` — no live network; uses injectable HTTP transport and in-memory zip fixtures.

---

# Phase A — Configuration foundation (no GL)

### Task 1: Add the six display-shader config enum constants

**Files:**
- Modify: `src/main/java/com/openggf/configuration/SonicConfiguration.java` (insert after `DISPLAY_WINDOW_AUTOSIZE`, line 142)

- [ ] **Step 1: Add the enum constants**

In `SonicConfiguration.java`, immediately after the `DISPLAY_WINDOW_AUTOSIZE,` constant (line 142) and before the blank line preceding `REGION`, insert:

```java
	/** Root directory scanned for user display shaders (relative to working dir). */
	DISPLAY_SHADER_LIBRARY_ROOT,
	/** Last selected display shader: "OFF" or a root-relative forward-slash path. */
	DISPLAY_SHADER_SELECTION,
	/** Runtime key to advance to the next display shader. */
	DISPLAY_SHADER_NEXT_KEY,
	/** Runtime key to move to the previous display shader. */
	DISPLAY_SHADER_PREVIOUS_KEY,
	/** Runtime key to open the searchable display shader picker. */
	DISPLAY_SHADER_PICKER_KEY,
	/** Fallback render phase for standalone display shaders (SCENE/PRESENTATION/FINAL). */
	DISPLAY_SHADER_DEFAULT_PHASE,
```

- [ ] **Step 2: Compile to verify the enum is valid**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS (constants referenced by ConfigCatalog in Task 2 are not yet added, so this only proves the enum compiles).

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/openggf/configuration/SonicConfiguration.java
git commit -m "feat(config): add display shader library config keys"
```
Trailer: `Changelog: n/a: config keys wired in follow-up task` / `Configuration-Docs: n/a: documented in later task`.

---

### Task 2: Register catalog metadata, defaults, and unbind playback keys

**Files:**
- Modify: `src/main/java/com/openggf/configuration/ConfigCatalog.java` (after line 62, the `FPS` entry in the `display` section)
- Modify: `src/main/java/com/openggf/configuration/SonicConfigurationService.java` (defaults block ~line 563; playback defaults lines 572-580)

- [ ] **Step 1: Register catalog metadata in the `display` section**

In `ConfigCatalog.java`, immediately after the `FPS` registration (line 62), before the `// input` comment (line 64), insert. (These keys live in the normal `display` section, which precedes the fenced `debug.*` block, satisfying `TestConfigCatalog.debugSectionsAreContiguousAndLast`.)

```java
        put(DISPLAY_SHADER_LIBRARY_ROOT, of("display", "shaderLibraryRoot", STRING,
                "Root directory scanned for user display shaders"));
        put(DISPLAY_SHADER_SELECTION, of("display", "shaderSelection", STRING,
                "Last selected display shader: OFF or a root-relative path"));
        put(DISPLAY_SHADER_NEXT_KEY, of("display", "shaderNextKey", KEY,
                "Runtime key to advance to the next display shader"));
        put(DISPLAY_SHADER_PREVIOUS_KEY, of("display", "shaderPreviousKey", KEY,
                "Runtime key to move to the previous display shader"));
        put(DISPLAY_SHADER_PICKER_KEY, of("display", "shaderPickerKey", KEY,
                "Runtime key to open the searchable display shader picker"));
        put(DISPLAY_SHADER_DEFAULT_PHASE, ofEnum("display", "shaderDefaultPhase",
                "Fallback render phase for standalone display shaders",
                Set.of("SCENE", "PRESENTATION", "FINAL")));
```

- [ ] **Step 2: Add defaults and unbind playback keys in the service**

In `SonicConfigurationService.java`, in the defaults block, add after the `FPS` default (line 563):

```java
		putDefault(SonicConfiguration.DISPLAY_SHADER_LIBRARY_ROOT, "shaders");
		putDefault(SonicConfiguration.DISPLAY_SHADER_SELECTION, "OFF");
		putDefaultKey(SonicConfiguration.DISPLAY_SHADER_NEXT_KEY, GLFW_KEY_RIGHT_BRACKET);
		putDefaultKey(SonicConfiguration.DISPLAY_SHADER_PREVIOUS_KEY, GLFW_KEY_LEFT_BRACKET);
		putDefaultKey(SonicConfiguration.DISPLAY_SHADER_PICKER_KEY, GLFW_KEY_BACKSLASH);
		putDefault(SonicConfiguration.DISPLAY_SHADER_DEFAULT_PHASE, "PRESENTATION");
```

Then replace the nine playback key defaults (lines 572-580) so every playback-only key defaults to unbound. `GLFW_KEY_UNKNOWN` is `-1`, the resolver's unbound sentinel:

```java
		putDefault(SonicConfiguration.PLAYBACK_TOGGLE_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_LOAD_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_PLAY_PAUSE_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_STEP_BACK_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_STEP_FORWARD_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_JUMP_BACK_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_JUMP_FORWARD_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_FAST_RATE_KEY, "");
		putDefault(SonicConfiguration.PLAYBACK_RESET_TO_START_KEY, "");
```

(Note: an empty-string key value resolves to `-1` via `GlfwKeyNameResolver.resolve("")` returning empty → unbound. This keeps the YAML readable as `toggleKey: ""`.)

- [ ] **Step 3: Run the catalog test to verify ordering + enum allowed-values pass**

Run: `mvn "-Dtest=com.openggf.configuration.TestConfigCatalog" test`
Expected: PASS (every constant has meta; `display.*` precedes `debug.*`; the ENUM key has allowed values).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/configuration/ConfigCatalog.java src/main/java/com/openggf/configuration/SonicConfigurationService.java
git commit -m "feat(config): catalog display shader keys, unbind playback defaults"
```
Trailer: `Changelog: updated` (stage `CHANGELOG.md` with a one-line entry) / `Configuration-Docs: n/a: doc sync in Task 3`.

---

### Task 3: Sync checked-in config artifacts + add a regression test

**Files:**
- Modify: `src/main/resources/config.yaml` (display section ~line 4-11; `debug.playback` ~line 142-153)
- Modify: `CONFIGURATION.md` (embedded sample config `debug.playback` ~line 504-512; key tables)
- Test: `src/test/java/com/openggf/configuration/TestPlaybackKeyDefaultsUnbound.java`

- [ ] **Step 1: Write the failing regression test**

Create `src/test/java/com/openggf/configuration/TestPlaybackKeyDefaultsUnbound.java`. Use `SonicConfigurationService.createStandalone(tempDir)` for isolation — this is the production-supported path for an independent config service rooted at a directory, and (unlike a process-wide `user.dir` override) it is safe under `forkCount=4` parallel execution:

```java
package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPlaybackKeyDefaultsUnbound {

    @Test
    void playbackOnlyKeyDefaultsAreUnbound(@TempDir Path tempDir) {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        SonicConfiguration[] playbackKeys = {
                SonicConfiguration.PLAYBACK_TOGGLE_KEY,
                SonicConfiguration.PLAYBACK_LOAD_KEY,
                SonicConfiguration.PLAYBACK_PLAY_PAUSE_KEY,
                SonicConfiguration.PLAYBACK_STEP_BACK_KEY,
                SonicConfiguration.PLAYBACK_STEP_FORWARD_KEY,
                SonicConfiguration.PLAYBACK_JUMP_BACK_KEY,
                SonicConfiguration.PLAYBACK_JUMP_FORWARD_KEY,
                SonicConfiguration.PLAYBACK_FAST_RATE_KEY,
                SonicConfiguration.PLAYBACK_RESET_TO_START_KEY,
        };
        for (SonicConfiguration key : playbackKeys) {
            assertEquals(-1, cfg.getInt(key), key + " must default to unbound (-1)");
        }
    }

    @Test
    void shaderCycleAndPickerKeysDefaultToBracketCluster(@TempDir Path tempDir) {
        SonicConfigurationService cfg = SonicConfigurationService.createStandalone(tempDir);
        assertEquals(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET,
                cfg.getInt(SonicConfiguration.DISPLAY_SHADER_NEXT_KEY));
        assertEquals(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET,
                cfg.getInt(SonicConfiguration.DISPLAY_SHADER_PREVIOUS_KEY));
        assertEquals(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH,
                cfg.getInt(SonicConfiguration.DISPLAY_SHADER_PICKER_KEY));
    }
}
```

- [ ] **Step 2: Run it to verify it passes (Task 2 already changed the defaults)**

Run: `mvn "-Dtest=com.openggf.configuration.TestPlaybackKeyDefaultsUnbound" test`
Expected: PASS. If a playback assertion FAILS, the code default in Task 2 was missed — fix `SonicConfigurationService` rather than the test.

- [ ] **Step 3: Update the checked-in `config.yaml`**

In `src/main/resources/config.yaml`, under `display:` (after `fps:`), add:

```yaml
  shaderLibraryRoot: shaders
  shaderSelection: OFF
  shaderNextKey: RIGHT_BRACKET
  shaderPreviousKey: LEFT_BRACKET
  shaderPickerKey: BACKSLASH
  shaderDefaultPhase: PRESENTATION
```

Under `debug:` → `playback:`, set every key entry to unbound (keep the comments):

```yaml
    toggleKey: ""   # Toggle playback mode (unbound by default; rebind to use playback)
    loadKey: ""   # Load/reload the BK2 movie
    playPauseKey: ""   # Toggle playback play/pause
    stepBackKey: ""   # Step the cursor back one frame
    stepForwardKey: ""   # Step the cursor forward one frame
    jumpBackKey: ""   # Jump the cursor back by a larger interval
    jumpForwardKey: ""   # Jump the cursor forward by a larger interval
    fastRateKey: ""   # Cycle playback rate (1x/2x/4x/8x)
    resetToStartKey: ""   # Reset the cursor to the first frame
```

- [ ] **Step 4: Update `CONFIGURATION.md`**

In the embedded sample `config.yaml` (the `debug.playback` block ~line 504-512), apply the same unbound values as Step 3. In the `display` part of the sample, add the six `shader*` lines from Step 3. In the prose/key tables add rows for `display.shaderLibraryRoot`, `display.shaderSelection`, `display.shaderNextKey`, `display.shaderPreviousKey`, `display.shaderPickerKey`, `display.shaderDefaultPhase`, and add one sentence near the `debug.playback` description: "Playback controls are unbound by default; rebind them in config to use BK2 playback." (Full GLSL-contract / limitation docs land in Task 14.)

- [ ] **Step 5: Run the config suite to confirm no drift regressions**

Run: `mvn "-Dtest=com.openggf.configuration.*" test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/config.yaml CONFIGURATION.md src/test/java/com/openggf/configuration/TestPlaybackKeyDefaultsUnbound.java
git commit -m "docs(config): sync seed config + document unbound playback defaults"
```
Trailer: `Configuration-Docs: updated` / `Changelog: n/a: doc/seed sync only`.

---

# Phase B — Discovery (no GL)

### Task 4: Phase/scale/wrap/shape enums + `DisplayShaderPresetRef`

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/ShaderPhase.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/ScaleType.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/WrapMode.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/GlslShape.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetRef.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPresetRef.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestDisplayShaderPresetRef {

    @Test
    void offEntryHasOffLabelAndNoPath() {
        DisplayShaderPresetRef off = DisplayShaderPresetRef.OFF;
        assertEquals(DisplayShaderPresetRef.Kind.OFF, off.kind());
        assertEquals("Off", off.label());
        assertNull(off.relativePath());
    }

    @Test
    void cgpEntryLabelStripsExtension() {
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.CGP, "BizHawk/BizScanlines.cgp",
                Path.of("shaders/BizHawk/BizScanlines.cgp"));
        assertEquals("BizHawk/BizScanlines.cgp", ref.relativePath());
        assertEquals("BizHawk/BizScanlines", ref.label());
    }

    @Test
    void glslpEntryLabelStripsExtension() {
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSLP, "RetroArch/shaders_glsl/scanlines/scanline.glslp",
                Path.of("shaders/RetroArch/shaders_glsl/scanlines/scanline.glslp"));
        assertEquals("RetroArch/shaders_glsl/scanlines/scanline", ref.label());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPresetRef" test`
Expected: FAIL (classes do not exist).

- [ ] **Step 3: Implement the enums and record**

`ShaderPhase.java`:
```java
package com.openggf.graphics.shaderlib;

/** Insertion point of a display shader pass within {@code Engine.display()}. */
public enum ShaderPhase { SCENE, PRESENTATION, FINAL }
```

`ScaleType.java`:
```java
package com.openggf.graphics.shaderlib;

/** Supported {@code scale_typeN} modes. {@code absolute} is intentionally unsupported. */
public enum ScaleType { SOURCE, VIEWPORT }
```

`WrapMode.java`:
```java
package com.openggf.graphics.shaderlib;

/** Supported RetroArch preset texture wrap hints. */
public enum WrapMode {
    CLAMP_TO_EDGE,
    CLAMP_TO_BORDER,
    REPEAT
}
```

`GlslShape.java`:
```java
package com.openggf.graphics.shaderlib;

/** Whether a GLSL source carries its own vertex stage or only a fragment stage. */
public enum GlslShape { COMBINED, FRAGMENT_ONLY }
```

`DisplayShaderPresetRef.java`:
```java
package com.openggf.graphics.shaderlib;

import java.nio.file.Path;

/**
 * One library entry. {@link Kind#OFF} is the always-present disable entry and has
 * no path. {@code relativePath} is root-relative with forward slashes.
 */
public record DisplayShaderPresetRef(Kind kind, String relativePath, Path absolutePath) {

    public enum Kind { OFF, CGP, GLSLP, GLSL }

    public static final DisplayShaderPresetRef OFF =
            new DisplayShaderPresetRef(Kind.OFF, null, null);

    /** Human label: "Off", or the relative path with its extension stripped. */
    public String label() {
        if (kind == Kind.OFF) {
            return "Off";
        }
        int dot = relativePath.lastIndexOf('.');
        return dot > 0 ? relativePath.substring(0, dot) : relativePath;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPresetRef" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/ShaderPhase.java src/main/java/com/openggf/graphics/shaderlib/ScaleType.java src/main/java/com/openggf/graphics/shaderlib/WrapMode.java src/main/java/com/openggf/graphics/shaderlib/GlslShape.java src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetRef.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPresetRef.java
git commit -m "feat(shaderlib): add display shader value types"
```
Trailer: `Changelog: n/a: internal types, no behavior yet`.

---

### Task 5: `DisplayShaderLibrary` recursive scan

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderLibrary.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderLibrary.java`

Discovery rules (from spec): `Off` always index 0; selectable = `.cgp` presets, `.glslp` presets, and standalone `.glsl` files that are not referenced by a preset and are not implementation pass files under a root-relative `shaders/` or `resources/` segment. Ignore `.hlsl`, `.cg`, `.slangp`, `.slang`, `.txt`, hidden directories, and anything outside the root; sort stable, case-insensitive, root-relative. Scan produces metadata only: it may cheaply text-parse preset `shaderN` references to build the standalone `.glsl` exclusion set, but it must not compile shaders or initialize GL.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestDisplayShaderLibrary {

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    @Test
    void missingRootYieldsOffOnly(@TempDir Path tempDir) {
        DisplayShaderLibrary lib = DisplayShaderLibrary.scan(tempDir.resolve("does-not-exist"));
        assertEquals(List.of("Off"), labels(lib));
    }

    @Test
    void scanSortsPresetsAndUnreferencedGlslAfterOff(@TempDir Path root) throws IOException {
        write(root.resolve("BizHawk/BizScanlines.cgp"), "shaders = 1\nshader0 = BizScanlines.cg\nscale0 = 2\n");
        write(root.resolve("BizHawk/BizScanlines.glsl"), "// paired by cgp, must be hidden");
        write(root.resolve("BizHawk/hq2x.cgp"), "shaders = 1\nshader0 = hq2x.cg\n");
        write(root.resolve("Custom/warm-crt.glsl"), "// standalone");
        write(root.resolve("RetroArch/shaders_glsl/scanlines/scanline.glslp"),
                "shaders = 1\nshader0 = shaders/scanline.glsl\n");
        write(root.resolve("RetroArch/shaders_glsl/scanlines/shaders/scanline.glsl"),
                "// referenced implementation pass, must be hidden");
        write(root.resolve("Custom/notes.txt"), "ignored");
        write(root.resolve("Custom/raw.hlsl"), "ignored");

        DisplayShaderLibrary lib = DisplayShaderLibrary.scan(root);
        assertEquals(List.of(
                "Off",
                "BizHawk/BizScanlines",
                "BizHawk/hq2x",
                "Custom/warm-crt",
                "RetroArch/shaders_glsl/scanlines/scanline"), labels(lib));
    }

    @Test
    void rootNamedShadersDoesNotFilterEverything(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("shaders");
        write(root.resolve("visible.glsl"), "// standalone");
        DisplayShaderLibrary lib = DisplayShaderLibrary.scan(root);
        assertEquals(List.of("Off", "visible"), labels(lib));
    }

    @Test
    void rootRelativeImplementationSegmentsHideStandaloneGlsl(@TempDir Path root) throws IOException {
        write(root.resolve("resources/helper.glsl"), "// implementation file");
        write(root.resolve("effects/real.glsl"), "// user-facing standalone");
        DisplayShaderLibrary lib = DisplayShaderLibrary.scan(root);
        assertEquals(List.of("Off", "effects/real"), labels(lib));
    }

    @Test
    void hiddenDirectoriesAndSlangAreIgnored(@TempDir Path root) throws IOException {
        write(root.resolve(".cache/secret.cgp"), "shaders = 1\n");
        write(root.resolve("ok.glsl"), "// ok");
        write(root.resolve("modern.slangp"), "shaders = 1\n");
        write(root.resolve("modern.slang"), "#version 450\n");
        DisplayShaderLibrary lib = DisplayShaderLibrary.scan(root);
        assertEquals(List.of("Off", "ok"), labels(lib));
    }

    private static List<String> labels(DisplayShaderLibrary lib) {
        return lib.entries().stream().map(DisplayShaderPresetRef::label).toList();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderLibrary" test`
Expected: FAIL (class does not exist).

- [ ] **Step 3: Implement `DisplayShaderLibrary`**

```java
package com.openggf.graphics.shaderlib;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Recursively scans a root directory for selectable display shader presets.
 * Always exposes {@link DisplayShaderPresetRef#OFF} at index 0. Produces metadata
 * only -- it may text-parse preset shaderN references, but never compiles shaders.
 */
public final class DisplayShaderLibrary {

    private static final Logger LOG = Logger.getLogger(DisplayShaderLibrary.class.getName());
    private static final Pattern SHADER_REF =
            Pattern.compile("^\\s*shader(\\d+)\\s*=\\s*(.+?)\\s*$", Pattern.CASE_INSENSITIVE);

    private final List<DisplayShaderPresetRef> entries;

    private DisplayShaderLibrary(List<DisplayShaderPresetRef> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<DisplayShaderPresetRef> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public DisplayShaderPresetRef at(int index) {
        return entries.get(Math.floorMod(index, entries.size()));
    }

    /** Index of the entry whose relativePath equals the given path, or 0 (OFF) if absent. */
    public int indexOfRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank() || "OFF".equalsIgnoreCase(relativePath)) {
            return 0;
        }
        String normalized = relativePath.replace('\\', '/');
        for (int i = 0; i < entries.size(); i++) {
            if (normalized.equals(entries.get(i).relativePath())) {
                return i;
            }
        }
        return 0;
    }

    public static DisplayShaderLibrary scan(Path root) {
        List<DisplayShaderPresetRef> out = new ArrayList<>();
        out.add(DisplayShaderPresetRef.OFF);
        if (root == null || !Files.isDirectory(root)) {
            return new DisplayShaderLibrary(out);
        }
        Path normalizedRoot = root.toAbsolutePath().normalize();

        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(normalizedRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> !hasHiddenSegment(normalizedRoot, p))
                .filter(p -> p.normalize().startsWith(normalizedRoot))
                .forEach(files::add);
        } catch (IOException e) {
            LOG.warning("Display shader scan failed for " + normalizedRoot + ": " + e.getMessage());
            return new DisplayShaderLibrary(out);
        }

        Set<String> referencedGlsl = collectReferencedGlsl(normalizedRoot, files);

        List<DisplayShaderPresetRef> discovered = new ArrayList<>();
        for (Path p : files) {
            String ext = extension(p);
            String rel = relative(normalizedRoot, p);
            if (ext.equals("cgp")) {
                discovered.add(new DisplayShaderPresetRef(DisplayShaderPresetRef.Kind.CGP, rel, p));
            } else if (ext.equals("glslp")) {
                discovered.add(new DisplayShaderPresetRef(DisplayShaderPresetRef.Kind.GLSLP, rel, p));
            } else if (ext.equals("glsl")
                    && !referencedGlsl.contains(rel.toLowerCase(Locale.ROOT))
                    && !underImplementationSegment(normalizedRoot, p)) {
                discovered.add(new DisplayShaderPresetRef(DisplayShaderPresetRef.Kind.GLSL, rel, p));
            }
            // .hlsl/.cg/.slangp/.slang/.txt and referenced implementation .glsl files are ignored.
        }

        discovered.sort(Comparator.comparing(DisplayShaderPresetRef::relativePath,
                String.CASE_INSENSITIVE_ORDER));
        out.addAll(discovered);
        return new DisplayShaderLibrary(out);
    }

    private static Set<String> collectReferencedGlsl(Path root, List<Path> files) {
        Set<String> out = new HashSet<>();
        for (Path preset : files) {
            String ext = extension(preset);
            if (!ext.equals("cgp") && !ext.equals("glslp")) {
                continue;
            }
            try {
                for (String line : Files.readAllLines(preset)) {
                    Matcher m = SHADER_REF.matcher(line);
                    if (m.matches()) {
                        addResolvedGlslReference(root, preset, m.group(2).trim(), ext, out);
                    }
                }
            } catch (IOException e) {
                LOG.fine("Skipping preset reference scan for " + preset + ": " + e.getMessage());
            }
        }
        return out;
    }

    private static void addResolvedGlslReference(Path root, Path preset, String rawRef,
                                                 String presetExt, Set<String> out) {
        String cleaned = stripQuotes(rawRef).replace('\\', '/');
        Path dir = preset.getParent();
        if (cleaned.toLowerCase(Locale.ROOT).endsWith(".glsl")) {
            addIfUnderRoot(root, dir.resolve(cleaned), out);
            return;
        }
        if (presetExt.equals("cgp")) {
            int dot = cleaned.lastIndexOf('.');
            String base = dot >= 0 ? cleaned.substring(0, dot) : cleaned;
            addIfUnderRoot(root, dir.resolve(base + ".glsl"), out);
        }
    }

    private static void addIfUnderRoot(Path root, Path candidate, Set<String> out) {
        Path normalized = candidate.toAbsolutePath().normalize();
        if (normalized.startsWith(root)) {
            out.add(relative(root, normalized).toLowerCase(Locale.ROOT));
        }
    }

    private static boolean hasHiddenSegment(Path root, Path file) {
        Path rel = root.relativize(file);
        for (Path seg : rel) {
            String s = seg.toString();
            if (s.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static String relative(Path root, Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private static boolean underImplementationSegment(Path root, Path file) {
        Path rel = root.relativize(file);
        int count = rel.getNameCount();
        for (int i = 0; i < count - 1; i++) { // exclude filename; root name is not in rel
            String segment = rel.getName(i).toString().toLowerCase(Locale.ROOT);
            if (segment.equals("shaders") || segment.equals("resources")) {
                return true;
            }
        }
        return false;
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : "";
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\""))
                || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderLibrary" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderLibrary.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderLibrary.java
git commit -m "feat(shaderlib): recursive display shader discovery"
```
Trailer: `Changelog: n/a: discovery wired into runtime in later task`.

---

# Phase C — Preset parsing & loading (no GL)

### Task 6: Pass/preset records + `.cgp` / `.glslp` field parsing

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPass.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPreset.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/UnsupportedShaderException.java`
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetLoader.java` (parsing only this task)
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestPresetFieldParsing.java`

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPresetFieldParsing {

    @Test
    void parsesPassCountSourceScaleAndDefaults() {
        String cgp = "shaders = 2\n"
                + "shader0 = BizScanlines.cg\n"
                + "scale0 = 2\n"
                + "shader1 = second.glsl\n"
                + "scale_type1 = viewport\n"
                + "filter_linear1 = true\n"
                + "wrap_mode1 = repeat\n";
        DisplayShaderPresetLoader.CgpFields f = DisplayShaderPresetLoader.parseCgp(cgp);

        assertEquals(2, f.passCount());
        assertEquals("BizScanlines.cg", f.source(0));
        assertEquals(2, f.scale(0));
        assertEquals(ScaleType.SOURCE, f.scaleType(0)); // missing -> source
        assertEquals(ScaleType.VIEWPORT, f.scaleType(1));
        assertEquals(false, f.filterLinear(0));
        assertEquals(true, f.filterLinear(1));
        assertEquals(WrapMode.CLAMP_TO_EDGE, f.wrapMode(0));
        assertEquals(WrapMode.REPEAT, f.wrapMode(1));
    }

    @Test
    void missingScaleDefaultsToOne() {
        DisplayShaderPresetLoader.CgpFields f =
                DisplayShaderPresetLoader.parseCgp("shaders = 1\nshader0 = hq2x.cg\n");
        assertEquals(1, f.scale(0));
        assertEquals(ScaleType.SOURCE, f.scaleType(0));
    }

    @Test
    void parsesGlslpWithSameCoreFields() throws UnsupportedShaderException {
        DisplayShaderPresetLoader.CgpFields f = DisplayShaderPresetLoader.parseGlslp(
                "shaders = 1\nshader0 = shaders/scanline.glsl\nscale_type0 = viewport\n");
        assertEquals(1, f.passCount());
        assertEquals("shaders/scanline.glsl", f.source(0));
        assertEquals(ScaleType.VIEWPORT, f.scaleType(0));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestPresetFieldParsing" test`
Expected: FAIL (classes do not exist).

- [ ] **Step 3: Implement records, exception, and parser**

`UnsupportedShaderException.java`:
```java
package com.openggf.graphics.shaderlib;

/** Thrown when a preset cannot be loaded as GLSL (no GLSL source, unsupported scale, etc.). */
public class UnsupportedShaderException extends Exception {
    public UnsupportedShaderException(String message) {
        super(message);
    }
}
```

`DisplayShaderPass.java`:
```java
package com.openggf.graphics.shaderlib;

/**
 * One resolved post-process pass. {@code vertexSource} is null when the pass is
 * fragment-only and should use the engine fullscreen quad vertex shader.
 */
public record DisplayShaderPass(
        String vertexSource,
        String fragmentSource,
        GlslShape shape,
        int scale,
        ScaleType scaleType,
        boolean filterLinear,
        WrapMode wrapMode) {
}
```

`DisplayShaderPreset.java`:
```java
package com.openggf.graphics.shaderlib;

import java.util.List;

/** A fully-resolved, ready-to-compile preset. */
public record DisplayShaderPreset(String label, ShaderPhase phase, List<DisplayShaderPass> passes) {
    public DisplayShaderPreset {
        passes = List.copyOf(passes);
    }
}
```

`DisplayShaderPresetLoader.java` (parser only for now; loading added in Tasks 7-8):
```java
package com.openggf.graphics.shaderlib;

import java.util.HashMap;
import java.util.Map;

/** Parses the shared focused subset of Cg/RetroArch GLSL preset formats. */
public final class DisplayShaderPresetLoader {

    /** Parsed raw fields of a {@code .cgp}, addressed by pass index. */
    public static final class CgpFields {
        private final int passCount;
        private final Map<String, String> kv;

        CgpFields(int passCount, Map<String, String> kv) {
            this.passCount = passCount;
            this.kv = kv;
        }

        public int passCount() {
            return passCount;
        }

        public String source(int pass) {
            return kv.get("shader" + pass);
        }

        public int scale(int pass) {
            String v = kv.get("scale" + pass);
            return v == null ? 1 : Integer.parseInt(v.trim());
        }

        public ScaleType scaleType(int pass) throws UnsupportedShaderException {
            String v = kv.get("scale_type" + pass);
            if (v == null) {
                return ScaleType.SOURCE; // RetroArch default; matches BizScanlines/hq2x
            }
            return switch (v.trim().toLowerCase()) {
                case "source" -> ScaleType.SOURCE;
                case "viewport" -> ScaleType.VIEWPORT;
                default -> throw new UnsupportedShaderException(
                        "Unsupported scale_type" + pass + " = " + v.trim());
            };
        }

        public boolean filterLinear(int pass) {
            return Boolean.parseBoolean(String.valueOf(kv.get("filter_linear" + pass)).trim());
        }

        public WrapMode wrapMode(int pass) throws UnsupportedShaderException {
            String v = kv.get("wrap_mode" + pass);
            if (v == null || v.isBlank()) {
                return WrapMode.CLAMP_TO_EDGE;
            }
            return switch (v.trim().toLowerCase()) {
                case "clamp_to_edge", "clamp-to-edge" -> WrapMode.CLAMP_TO_EDGE;
                case "clamp_to_border", "clamp-to-border" -> WrapMode.CLAMP_TO_BORDER;
                case "repeat" -> WrapMode.REPEAT;
                default -> throw new UnsupportedShaderException(
                        "Unsupported wrap_mode" + pass + " = " + v.trim());
            };
        }
    }

    public static CgpFields parseCgp(String text) {
        return parsePresetFields(text);
    }

    public static CgpFields parseGlslp(String text) {
        return parsePresetFields(text);
    }

    private static CgpFields parsePresetFields(String text) {
        Map<String, String> kv = new HashMap<>();
        int passCount = 0;
        for (String rawLine : text.split("\\R")) {
            String line = stripComment(rawLine).trim();
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).trim().toLowerCase();
            String value = unquote(line.substring(eq + 1).trim());
            if (key.equals("shaders")) {
                passCount = Integer.parseInt(value);
            } else {
                kv.put(key, value);
            }
        }
        return new CgpFields(passCount, kv);
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        int slashes = line.indexOf("//");
        int cut = -1;
        if (hash >= 0) {
            cut = hash;
        }
        if (slashes >= 0 && (cut < 0 || slashes < cut)) {
            cut = slashes;
        }
        return cut >= 0 ? line.substring(0, cut) : line;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private DisplayShaderPresetLoader() {
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestPresetFieldParsing" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPass.java src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPreset.java src/main/java/com/openggf/graphics/shaderlib/UnsupportedShaderException.java src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetLoader.java src/test/java/com/openggf/graphics/shaderlib/TestPresetFieldParsing.java
git commit -m "feat(shaderlib): parse display shader preset fields"
```
Trailer: `Changelog: n/a: parser wired into runtime in later task`.

---

### Task 7: `.cgp` / `.glslp` GLSL source resolution + full preset load

**Files:**
- Modify: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetLoader.java` (add `load`, `.cgp`/`.glslp` source resolution, unsupported-feature checks)
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestCgpSourceResolution.java`

Resolution order (spec): `.cgp` uses (1) referenced path if it ends `.glsl`; (2) same basename `.glsl` beside the `.cgp`; (3) `UnsupportedShaderException` (HLSL/Cg not compiled). `.glslp` resolves `shaderN` exactly relative to the preset directory and rejects non-GLSL pass sources, missing files, includes, textures/LUTs, history/feedback, and Slang references.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCgpSourceResolution {

    private static void write(Path p, String body) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, body);
    }

    @Test
    void mapsCgReferenceToSiblingGlsl(@TempDir Path dir) throws Exception {
        write(dir.resolve("BizScanlines.cgp"), "shaders = 1\nshader0 = BizScanlines.cg\nscale0 = 2\n");
        write(dir.resolve("BizScanlines.glsl"), "// fragment\nvec4 frag() { return vec4(1.0); }");

        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.CGP, "BizScanlines.cgp", dir.resolve("BizScanlines.cgp"));
        DisplayShaderPreset preset = new DisplayShaderPresetLoader().load(ref, ShaderPhase.PRESENTATION);

        assertEquals(1, preset.passes().size());
        assertEquals(2, preset.passes().get(0).scale());
        assertTrue(preset.passes().get(0).fragmentSource().contains("frag"));
    }

    @Test
    void cgpWithoutGlslSourceIsUnsupported(@TempDir Path dir) throws Exception {
        write(dir.resolve("bicubic-fast.cgp"), "shaders = 1\nshader0 = bicubic-fast.cg\n");
        write(dir.resolve("bicubic-fast.hlsl"), "// hlsl only");

        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.CGP, "bicubic-fast.cgp", dir.resolve("bicubic-fast.cgp"));
        DisplayShaderPresetLoader loader = new DisplayShaderPresetLoader();
        assertThrows(UnsupportedShaderException.class,
                () -> loader.load(ref, ShaderPhase.PRESENTATION));
    }

    @Test
    void scalerPresetIsForcedToScenePhase(@TempDir Path dir) throws Exception {
        write(dir.resolve("hq2x.cgp"), "shaders = 1\nshader0 = hq2x.cg\nscale0 = 2\n");
        write(dir.resolve("hq2x.glsl"), "uniform sampler2D s_p; void main(){}");

        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.CGP, "BizHawk/hq2x.cgp", dir.resolve("hq2x.cgp"));
        DisplayShaderPreset preset = new DisplayShaderPresetLoader().load(ref, ShaderPhase.PRESENTATION);

        assertEquals(ShaderPhase.SCENE, preset.phase()); // forced regardless of default
    }

    @Test
    void loadsGlslpRelativeShaderSource(@TempDir Path dir) throws Exception {
        write(dir.resolve("scanlines/scanline.glslp"),
                "shaders = 1\nshader0 = shaders/scanline.glsl\nscale_type0 = viewport\n");
        write(dir.resolve("scanlines/shaders/scanline.glsl"),
                "#if defined(VERTEX)\nvoid main(){}\n#elif defined(FRAGMENT)\nuniform sampler2D Texture; void main(){}\n#endif\n");

        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSLP,
                "RetroArch/shaders_glsl/scanlines/scanline.glslp",
                dir.resolve("scanlines/scanline.glslp"));
        DisplayShaderPreset preset = new DisplayShaderPresetLoader().load(ref, ShaderPhase.PRESENTATION);

        assertEquals(1, preset.passes().size());
        assertEquals(ScaleType.VIEWPORT, preset.passes().get(0).scaleType());
    }

    @Test
    void glslpRejectsUnsupportedSlangReference(@TempDir Path dir) throws Exception {
        write(dir.resolve("bad.glslp"), "shaders = 1\nshader0 = shaders/bad.slang\n");
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSLP, "bad.glslp", dir.resolve("bad.glslp"));

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedShaderException.class,
                () -> new DisplayShaderPresetLoader().load(ref, ShaderPhase.PRESENTATION));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestCgpSourceResolution" test`
Expected: FAIL (`load` not defined).

- [ ] **Step 3: Implement `load` + `resolveGlslSource`**

Make the parser methods non-static-friendly by adding an instance entrypoint. Change the `private DisplayShaderPresetLoader()` constructor to `public DisplayShaderPresetLoader()` and add these methods to the class (keep `parseCgp` static):

```java
    /** Load a preset from a discovered ref. Standalone .glsl handling lands in Task 8. */
    public DisplayShaderPreset load(DisplayShaderPresetRef ref, ShaderPhase defaultPhase)
            throws UnsupportedShaderException, java.io.IOException {
        if (ref.kind() == DisplayShaderPresetRef.Kind.CGP) {
            return loadCgp(ref, defaultPhase);
        }
        if (ref.kind() == DisplayShaderPresetRef.Kind.GLSLP) {
            return loadGlslp(ref, defaultPhase);
        }
        throw new UnsupportedShaderException("Standalone GLSL load not yet implemented");
    }

    private DisplayShaderPreset loadCgp(DisplayShaderPresetRef ref, ShaderPhase defaultPhase)
            throws UnsupportedShaderException, java.io.IOException {
        java.nio.file.Path cgpPath = ref.absolutePath();
        CgpFields fields = parseCgp(java.nio.file.Files.readString(cgpPath));
        if (fields.passCount() < 1) {
            throw new UnsupportedShaderException("cgp declares no passes: " + ref.relativePath());
        }
        java.util.List<DisplayShaderPass> passes = new java.util.ArrayList<>();
        for (int i = 0; i < fields.passCount(); i++) {
            String referenced = fields.source(i);
            if (referenced == null) {
                throw new UnsupportedShaderException("cgp missing shader" + i + ": " + ref.relativePath());
            }
            String glsl = resolveGlslSource(cgpPath, referenced);
            // GLSL shape detection is added in Task 8; default fragment-only here.
            passes.add(new DisplayShaderPass(null, glsl, GlslShape.FRAGMENT_ONLY,
                    fields.scale(i), fields.scaleType(i), fields.filterLinear(i), fields.wrapMode(i)));
        }
        return new DisplayShaderPreset(ref.label(), phaseForPreset(ref, defaultPhase), passes);
    }

    private DisplayShaderPreset loadGlslp(DisplayShaderPresetRef ref, ShaderPhase defaultPhase)
            throws UnsupportedShaderException, java.io.IOException {
        java.nio.file.Path glslpPath = ref.absolutePath();
        CgpFields fields = parseGlslp(java.nio.file.Files.readString(glslpPath));
        if (fields.passCount() < 1) {
            throw new UnsupportedShaderException("glslp declares no passes: " + ref.relativePath());
        }
        java.util.List<DisplayShaderPass> passes = new java.util.ArrayList<>();
        for (int i = 0; i < fields.passCount(); i++) {
            String referenced = fields.source(i);
            if (referenced == null) {
                throw new UnsupportedShaderException("glslp missing shader" + i + ": " + ref.relativePath());
            }
            String glsl = resolveGlslpSource(glslpPath, referenced);
            rejectUnsupportedRetroArchFeatures(glsl, referenced);
            passes.add(new DisplayShaderPass(null, glsl, GlslShape.FRAGMENT_ONLY,
                    fields.scale(i), fields.scaleType(i), fields.filterLinear(i), fields.wrapMode(i)));
        }
        return new DisplayShaderPreset(ref.label(), phaseForPreset(ref, defaultPhase), passes);
    }

    // Hardcoded compatibility table: pixel scalers run at SCENE. Current Engine SCENE
    // insertion is after the normal scene flush, so HUD pixels are included for now.
    private static final java.util.Set<String> SCENE_SCALER_BASENAMES = java.util.Set.of("hq2x");
    private static final java.util.Set<String> SCENE_SCALER_CATEGORIES =
            java.util.Set.of("scalenx", "scalehq", "xbr", "xbrz", "xsal", "xsoft");

    private static ShaderPhase phaseForPreset(DisplayShaderPresetRef ref, ShaderPhase defaultPhase) {
        for (String segment : ref.relativePath().toLowerCase().split("/")) {
            if (SCENE_SCALER_CATEGORIES.contains(segment)) {
                return ShaderPhase.SCENE;
            }
        }
        String base = ref.relativePath().toLowerCase();
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return SCENE_SCALER_BASENAMES.contains(base) ? ShaderPhase.SCENE : defaultPhase;
    }

    /** Resolve a cgp-referenced source to GLSL text, or throw if only HLSL/Cg exists. */
    String resolveGlslSource(java.nio.file.Path cgpPath, String referenced)
            throws UnsupportedShaderException, java.io.IOException {
        java.nio.file.Path dir = cgpPath.getParent();
        // 1. Exact reference if it is already .glsl.
        if (referenced.toLowerCase().endsWith(".glsl")) {
            java.nio.file.Path exact = dir.resolve(referenced);
            if (java.nio.file.Files.isRegularFile(exact)) {
                return java.nio.file.Files.readString(exact);
            }
        }
        // 2. Same basename with .glsl beside the cgp.
        String base = referenced;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        java.nio.file.Path sibling = dir.resolve(base + ".glsl");
        if (java.nio.file.Files.isRegularFile(sibling)) {
            return java.nio.file.Files.readString(sibling);
        }
        // 3. Unsupported.
        throw new UnsupportedShaderException(
                "No GLSL source for " + referenced + " (HLSL/Cg is not compiled)");
    }

    String resolveGlslpSource(java.nio.file.Path glslpPath, String referenced)
            throws UnsupportedShaderException, java.io.IOException {
        String lower = referenced.toLowerCase();
        if (lower.endsWith(".slang") || lower.endsWith(".cg") || lower.endsWith(".hlsl")) {
            throw new UnsupportedShaderException("Unsupported RetroArch shader source: " + referenced);
        }
        if (!lower.endsWith(".glsl")) {
            throw new UnsupportedShaderException("RetroArch GLSL preset pass is not .glsl: " + referenced);
        }
        java.nio.file.Path resolved = glslpPath.getParent().resolve(referenced).normalize();
        if (!java.nio.file.Files.isRegularFile(resolved)) {
            throw new UnsupportedShaderException("Missing RetroArch GLSL source: " + referenced);
        }
        return java.nio.file.Files.readString(resolved);
    }

    private static void rejectUnsupportedRetroArchFeatures(String glsl, String referenced)
            throws UnsupportedShaderException {
        if (glsl.contains("#include")) {
            throw new UnsupportedShaderException("RetroArch #include is not supported: " + referenced);
        }
        if (glsl.contains("OriginalHistory") || glsl.contains("Prev")
                || glsl.contains("Feedback") || glsl.contains("PassFeedback")) {
            throw new UnsupportedShaderException("RetroArch history/feedback is not supported: " + referenced);
        }
    }
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestCgpSourceResolution" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetLoader.java src/test/java/com/openggf/graphics/shaderlib/TestCgpSourceResolution.java
git commit -m "feat(shaderlib): resolve cgp glsl sources, fail hlsl-only gracefully"
```
Trailer: `Changelog: n/a: loader wired into runtime in later task`.

---

### Task 8: GLSL shape detection + standalone `.glsl` load

**Files:**
- Modify: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetLoader.java` (add `detectShape`, finish standalone branch, set shape on cgp passes)
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestGlslShapeAndStandalone.java`

Shape rule (spec): combined files contain BizHawk `#ifdef VERTEX`/`#ifdef FRAGMENT` sections or RetroArch `#if defined(VERTEX)`/`#elif defined(FRAGMENT)` sections; fragment-only files sample `s_p`, `SceneTexture`, or `Texture` and use the engine fullscreen quad vertex shader.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestGlslShapeAndStandalone {

    @Test
    void detectsCombinedShape() {
        String combined = "#ifdef VERTEX\nvoid main(){}\n#endif\n#ifdef FRAGMENT\nvoid main(){}\n#endif\n";
        assertEquals(GlslShape.COMBINED, DisplayShaderPresetLoader.detectShape(combined));
    }

    @Test
    void detectsRetroArchCombinedShape() {
        String combined = "#if defined(VERTEX)\nvoid main(){}\n#elif defined(FRAGMENT)\nvoid main(){}\n#endif\n";
        assertEquals(GlslShape.COMBINED, DisplayShaderPresetLoader.detectShape(combined));
    }

    @Test
    void detectsFragmentOnlyShape() {
        String frag = "uniform sampler2D s_p;\nvoid main(){ gl_FragColor = texture(s_p, vec2(0.0)); }\n";
        assertEquals(GlslShape.FRAGMENT_ONLY, DisplayShaderPresetLoader.detectShape(frag));
    }

    @Test
    void standaloneGlslBecomesSinglePassWithDefaultPhase(@TempDir Path dir) throws IOException, UnsupportedShaderException {
        Path glsl = dir.resolve("warm-crt.glsl");
        Files.writeString(glsl, "uniform sampler2D s_p;\nvoid main(){}\n");
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSL, "warm-crt.glsl", glsl);

        DisplayShaderPreset preset = new DisplayShaderPresetLoader().load(ref, ShaderPhase.PRESENTATION);

        assertEquals(ShaderPhase.PRESENTATION, preset.phase());
        assertEquals(1, preset.passes().size());
        DisplayShaderPass pass = preset.passes().get(0);
        assertEquals(GlslShape.FRAGMENT_ONLY, pass.shape());
        assertNull(pass.vertexSource());
        assertEquals(1, pass.scale());
        assertEquals(ScaleType.SOURCE, pass.scaleType());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestGlslShapeAndStandalone" test`
Expected: FAIL (`detectShape` missing, standalone branch throws).

- [ ] **Step 3: Implement shape detection + standalone branch**

Add to `DisplayShaderPresetLoader`:

```java
    /** Combined if it carries its own vertex stage; otherwise fragment-only. */
    public static GlslShape detectShape(String source) {
        boolean hasVertex = source.contains("#ifdef VERTEX")
                || source.contains("#ifdef VERTEX_SHADER")
                || source.contains("#if defined(VERTEX)")
                || source.contains("#if defined(VERTEX_SHADER)");
        boolean hasFragment = source.contains("#ifdef FRAGMENT")
                || source.contains("#ifdef FRAGMENT_SHADER")
                || source.contains("#elif defined(FRAGMENT)")
                || source.contains("#if defined(FRAGMENT)")
                || source.contains("#elif defined(FRAGMENT_SHADER)")
                || source.contains("#if defined(FRAGMENT_SHADER)");
        return (hasVertex && hasFragment) ? GlslShape.COMBINED : GlslShape.FRAGMENT_ONLY;
    }
```

Replace the standalone branch in `load(...)` (the line that throws "not yet implemented"):

```java
        if (ref.kind() == DisplayShaderPresetRef.Kind.GLSL) {
            String glsl = java.nio.file.Files.readString(ref.absolutePath());
            GlslShape shape = detectShape(glsl);
            String vertexSource = shape == GlslShape.COMBINED ? glsl : null;
            DisplayShaderPass pass = new DisplayShaderPass(
                    vertexSource, glsl, shape, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE);
            return new DisplayShaderPreset(ref.label(), defaultPhase, java.util.List.of(pass));
        }
```

In `loadCgp`, replace the fragment-only hardcode so cgp passes also detect shape:

```java
            String glsl = resolveGlslSource(cgpPath, referenced);
            GlslShape shape = detectShape(glsl);
            String vertexSource = shape == GlslShape.COMBINED ? glsl : null;
            passes.add(new DisplayShaderPass(vertexSource, glsl, shape,
                    fields.scale(i), fields.scaleType(i), fields.filterLinear(i), fields.wrapMode(i)));
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestGlslShapeAndStandalone" test`
Expected: PASS.

- [ ] **Step 5: Run the whole shaderlib package + cgp tests together**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.*" test`
Expected: PASS (Tasks 4-8 all green).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPresetLoader.java src/test/java/com/openggf/graphics/shaderlib/TestGlslShapeAndStandalone.java
git commit -m "feat(shaderlib): detect glsl shape, load standalone glsl presets"
```
Trailer: `Changelog: n/a: loader wired into runtime in later task`.

---

# Phase D — Download + selection UX foundation (no GL)

### Task 8A: `RetroArchGlslShaderPackDownloader` libretro zip install/update utility

**Files:**
- Create: `src/main/java/com/openggf/util/RetroArchGlslShaderPackDownloader.java`
- Test: `src/test/java/com/openggf/util/RetroArchGlslShaderPackDownloaderTest.java`

The downloader is a self-contained Java utility, not UI code. It accepts the configured shader root (`shaders` by default), owns only the `libretro-glsl` child directory, downloads GitHub's zip archive, strips the archive top-level folder during extraction, reports progress, and writes update metadata to `libretro-glsl/.openggf-libretro-glsl.properties`. Tests must use an injectable transport and in-memory zip fixtures; no test should call GitHub.

- [ ] **Step 1: Write the failing tests**

Create tests covering these concrete behaviors:

```java
@Test
void downloadExtractsArchiveIntoLibretroFolderAndWritesMetadata()

@Test
void downloadIfNewerSkipsWhenStoredEtagStillMatches()

@Test
void downloadIfNewerDownloadsWhenRemoteEtagChanges()

@Test
void rejectsZipSlipArchiveWithoutCreatingInstallRoot()

@Test
void reportsHttpNetworkInterruptedPartialInvalidArchiveAndDiskFailuresWithReasons()
```

The desired public API:

```java
public final class RetroArchGlslShaderPackDownloader {
    public static final URI DEFAULT_ARCHIVE_URI =
            URI.create("https://github.com/libretro/glsl-shaders/archive/refs/heads/master.zip");
    public static final String INSTALL_DIR_NAME = "libretro-glsl";
    public static final String METADATA_FILE_NAME = ".openggf-libretro-glsl.properties";

    public RetroArchGlslShaderPackDownloader();
    public RetroArchGlslShaderPackDownloader(HttpTransport transport);

    public InstallResult download(Path shaderRoot, ProgressListener progress)
            throws ShaderPackDownloadException;

    public InstallResult downloadIfNewer(Path shaderRoot, ProgressListener progress)
            throws ShaderPackDownloadException;

    public UpdateStatus checkForUpdate(Path shaderRoot, ProgressListener progress)
            throws ShaderPackDownloadException;

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (stage, completed, total, detail) -> {};
        void onProgress(Stage stage, long completed, long total, String detail);
    }

    public enum Stage { CHECKING, DOWNLOADING, EXTRACTING, COMPLETE }
    public enum UpdateState { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE }
    public enum FailureReason {
        NETWORK, HTTP_STATUS, INTERRUPTED, PARTIAL_DOWNLOAD,
        INVALID_ARCHIVE, EXTRACTION, DISK
    }

    public record InstallResult(Path installRoot, boolean downloaded, String etag,
                                String lastModified) {}
    public record UpdateStatus(UpdateState state, String etag, String lastModified) {}
    public record HttpRequest(String method, URI uri, Map<String, String> headers) {}
    public record HttpResponse(int statusCode, Map<String, List<String>> headers,
                               InputStream body, long contentLength)
            implements AutoCloseable {
        @Override public void close() throws IOException { body.close(); }
    }
    @FunctionalInterface
    public interface HttpTransport {
        HttpResponse send(HttpRequest request) throws IOException, InterruptedException;
    }
    public static final class ShaderPackDownloadException extends Exception {
        public FailureReason reason() { return reason; }
    }
}
```

- [ ] **Step 2: Run to verify the tests fail**

Run: `mvn "-Dtest=com.openggf.util.RetroArchGlslShaderPackDownloaderTest" test`
Expected: FAIL because `RetroArchGlslShaderPackDownloader` does not exist.

- [ ] **Step 3: Implement the downloader**

Implementation requirements:

- Default transport uses Java 21 `HttpClient`; no `git` process, no external dependency.
- `download(shaderRoot, progress)` creates `<shaderRoot>/libretro-glsl` via a staging directory and replaces the active install only after download + extraction succeeds.
- Download to a `.part` temp file under the shader root/staging area and delete it on failure.
- Use `Content-Length` for download progress when present; otherwise pass `-1` as total.
- Extract with zip-slip protection by normalizing every stripped output path and checking it remains below the staging directory.
- Strip exactly the first zip path segment so `glsl-shaders-master/crt/foo.glslp` becomes `libretro-glsl/crt/foo.glslp`.
- Write `.openggf-libretro-glsl.properties` containing at least `archiveUrl`, `etag`, `lastModified`, and `installedAt`.
- `checkForUpdate(...)` reads the metadata file and sends `HEAD` to the archive URL with `If-None-Match` / `If-Modified-Since` when available. `304` means `UP_TO_DATE`; `200` with changed identity means `UPDATE_AVAILABLE`; missing install/metadata means `NOT_INSTALLED`.
- Convert failures into `ShaderPackDownloadException` with the `FailureReason` enum; interrupted operations must restore interrupt status.
- A failed download/extraction must leave an existing `libretro-glsl` install intact.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.util.RetroArchGlslShaderPackDownloaderTest" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/util/RetroArchGlslShaderPackDownloader.java src/test/java/com/openggf/util/RetroArchGlslShaderPackDownloaderTest.java
git commit -m "feat(shaderlib): add libretro glsl shader pack downloader"
```
Trailer: `Changelog: updated` / `Configuration-Docs: n/a: documented in final shader docs task`.

---

### Task 8B: `DisplayShaderSelectionModel` searchable/category-aware picker model

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderSelectionModel.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderSelectionModel.java`

This task prevents the full libretro pack from becoming unusable through linear cycling alone. It does not build the final overlay renderer; it provides the GL-free model that Engine/UI code can drive from `DISPLAY_SHADER_PICKER_KEY`.

- [ ] **Step 1: Write the failing tests**

Create tests with a synthetic library containing `Off`, `libretro-glsl/crt/crt-easymode.glslp`, `libretro-glsl/scanlines/scanline.glslp`, `libretro-glsl/xbr/xbr-lv2.glslp`, and `BizHawk/BizScanlines.cgp`:

```java
@Test
void emptyQueryReturnsOffThenSortedEntries()

@Test
void textQueryMatchesRootRelativePathCaseInsensitively()

@Test
void categoryQueryMatchesPathSegment()

@Test
void categoryListExcludesImplementationSegmentsShadersAndResources()

@Test
void selectByVisibleIndexReturnsTheUnderlyingPresetRef()
```

Desired API:

```java
public final class DisplayShaderSelectionModel {
    public DisplayShaderSelectionModel(DisplayShaderLibrary library);
    public List<SelectionItem> filter(String query);
    public List<String> categories();
    public DisplayShaderPresetRef select(List<SelectionItem> visibleItems, int visibleIndex);

    public record SelectionItem(DisplayShaderPresetRef ref, String category, String displayPath) {}
}
```

- [ ] **Step 2: Run to verify the tests fail**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderSelectionModel" test`
Expected: FAIL because `DisplayShaderSelectionModel` does not exist.

- [ ] **Step 3: Implement the model**

Implementation rules:

- `filter("")` returns all library entries in existing library order.
- Matching is case-insensitive against `SelectionItem.displayPath()` and category segment.
- Category is the first meaningful root-relative path segment after known install roots such as `libretro-glsl` and `RetroArch/shaders_glsl`; for `libretro-glsl/crt/crt-easymode.glslp`, category is `crt`.
- Categories exclude implementation-only segments `shaders` and `resources`.
- `select(...)` bounds-checks the visible index and returns `Off` for index `0` when that is the selected visible item.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderSelectionModel" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderSelectionModel.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderSelectionModel.java
git commit -m "feat(shaderlib): add searchable shader selection model"
```
Trailer: `Changelog: n/a: picker wiring lands in Engine integration task`.

---

# Phase E — Controller (no GL)

### Task 9: `DisplayShaderController` cycling, persistence, notification

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderController.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderController.java`

The controller is GL-free: activation is a `Predicate<DisplayShaderPresetRef>` returning `true` on success. On success it persists by relative path and shows `Shader: <label>`; on failure it marks the entry failed-this-process, skips it on later cycles, and shows `Shader failed: <label>`. Notification uses the same countdown as `DisplayColorProfileController`.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestDisplayShaderController {

    private static DisplayShaderLibrary twoEntryLibrary(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.glsl"), "uniform sampler2D s_p;");
        Files.writeString(root.resolve("b.glsl"), "uniform sampler2D s_p;");
        return DisplayShaderLibrary.scan(root); // [Off, a, b]
    }

    @Test
    void nextAdvancesPersistsAndNotifies(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root);
        AtomicReference<String> persisted = new AtomicReference<>();
        DisplayShaderController c = new DisplayShaderController(
                lib, "OFF", GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_LEFT_BRACKET,
                persisted::set, ref -> true);

        InputHandler in = new InputHandler();
        in.handleKeyEvent(GLFW_KEY_RIGHT_BRACKET, GLFW_PRESS);
        c.update(in);

        assertEquals("a", c.currentRef().label());
        assertEquals("a.glsl", persisted.get());
        assertEquals("Shader: a", c.notificationText());
    }

    @Test
    void previousFromOffWrapsToLastEntry(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root);
        DisplayShaderController c = new DisplayShaderController(
                lib, "OFF", GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_LEFT_BRACKET,
                s -> {}, ref -> true);

        InputHandler in = new InputHandler();
        in.handleKeyEvent(GLFW_KEY_LEFT_BRACKET, GLFW_PRESS);
        c.update(in);

        assertEquals("b", c.currentRef().label());
    }

    @Test
    void failedShaderIsSkippedOnNextCycleAndReportsFailure(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root); // [Off, a, b]
        Predicate<DisplayShaderPresetRef> activate =
                ref -> !"a".equals(ref.label()); // 'a' always fails
        DisplayShaderController c = new DisplayShaderController(
                lib, "OFF", GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_LEFT_BRACKET, s -> {}, activate);

        InputHandler in = new InputHandler();
        in.handleKeyEvent(GLFW_KEY_RIGHT_BRACKET, GLFW_PRESS); // Off -> a (fails)
        c.update(in);
        assertEquals("Shader failed: a", c.notificationText());

        in.handleKeyEvent(GLFW_KEY_RIGHT_BRACKET, GLFW_PRESS); // a -> skip a -> b
        c.update(in);
        assertEquals("b", c.currentRef().label());
        assertEquals("Shader: b", c.notificationText());
    }

    @Test
    void unboundKeysAreIgnored(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root);
        DisplayShaderController c = new DisplayShaderController(
                lib, "OFF", -1, -1, s -> {}, ref -> true);
        InputHandler in = new InputHandler();
        in.handleKeyEvent(GLFW_KEY_RIGHT_BRACKET, GLFW_PRESS);
        c.update(in);
        assertEquals("Off", c.currentRef().label());
        assertNull(c.notificationText());
    }

    @Test
    void savedSelectionResolvesToEntry(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root);
        DisplayShaderController c = new DisplayShaderController(
                lib, "b.glsl", GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_LEFT_BRACKET, s -> {}, ref -> true);
        assertEquals("b", c.currentRef().label());
    }

    @Test
    void silentBootActivatesWithoutPersistingOrNotifying(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root);
        AtomicReference<String> persisted = new AtomicReference<>();
        DisplayShaderController c = new DisplayShaderController(
                lib, "b.glsl", GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_LEFT_BRACKET, persisted::set, ref -> true);

        c.applySavedSelectionSilently();

        assertEquals("b", c.currentRef().label());
        assertNull(persisted.get());          // boot must not persist
        assertNull(c.notificationText());      // boot must not toast
    }

    @Test
    void silentBootFallsBackToOffWhenSavedShaderFails(@TempDir Path root) throws IOException {
        DisplayShaderLibrary lib = twoEntryLibrary(root);
        DisplayShaderController c = new DisplayShaderController(
                lib, "b.glsl", GLFW_KEY_RIGHT_BRACKET, GLFW_KEY_LEFT_BRACKET, s -> {},
                ref -> ref.kind() == DisplayShaderPresetRef.Kind.OFF); // every real shader fails

        c.applySavedSelectionSilently();

        assertEquals("Off", c.currentRef().label());
        assertNull(c.notificationText());
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderController" test`
Expected: FAIL (class does not exist).

- [ ] **Step 3: Implement `DisplayShaderController`**

```java
package com.openggf.graphics.shaderlib;

import com.openggf.control.InputHandler;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * GL-free controller for cycling the display shader library with two keys.
 * Activation is delegated to a predicate so production wiring can compile shaders
 * while tests stay headless. Notification mirrors {@code DisplayColorProfileController}.
 */
public final class DisplayShaderController {

    public static final int NOTIFICATION_FRAMES = 120;

    private final DisplayShaderLibrary library;
    private final int nextKey;
    private final int previousKey;
    private final Consumer<String> persistSelection;
    private final Predicate<DisplayShaderPresetRef> activate;
    private final Set<String> failedThisProcess = new HashSet<>();

    private int index;
    private String notificationText;
    private int notificationFramesRemaining;

    public DisplayShaderController(DisplayShaderLibrary library,
                                  String savedSelection,
                                  int nextKey,
                                  int previousKey,
                                  Consumer<String> persistSelection,
                                  Predicate<DisplayShaderPresetRef> activate) {
        this.library = Objects.requireNonNull(library, "library");
        this.nextKey = nextKey;
        this.previousKey = previousKey;
        this.persistSelection = Objects.requireNonNull(persistSelection, "persistSelection");
        this.activate = Objects.requireNonNull(activate, "activate");
        this.index = library.indexOfRelativePath(savedSelection);
    }

    public void update(InputHandler input) {
        if (input != null && nextKey >= 0 && input.isKeyPressed(nextKey)) {
            cycle(+1);
            return;
        }
        if (input != null && previousKey >= 0 && input.isKeyPressed(previousKey)) {
            cycle(-1);
            return;
        }
        if (notificationFramesRemaining > 0 && --notificationFramesRemaining == 0) {
            notificationText = null;
        }
    }

    private void cycle(int direction) {
        int size = library.size();
        for (int step = 1; step <= size; step++) {
            int candidate = Math.floorMod(index + direction * step, size);
            DisplayShaderPresetRef ref = library.at(candidate);
            if (ref.kind() != DisplayShaderPresetRef.Kind.OFF
                    && failedThisProcess.contains(ref.relativePath())) {
                continue; // skip entries that already failed this process
            }
            select(candidate, ref);
            return;
        }
    }

    private void select(int candidate, DisplayShaderPresetRef ref) {
        boolean ok = activate.test(ref);
        if (ok) {
            index = candidate;
            String selection = ref.kind() == DisplayShaderPresetRef.Kind.OFF ? "OFF" : ref.relativePath();
            persistSelection.accept(selection);
            notify("Shader: " + ref.label());
        } else {
            failedThisProcess.add(ref.relativePath());
            notify("Shader failed: " + ref.label());
        }
    }

    private void notify(String text) {
        notificationText = text;
        notificationFramesRemaining = NOTIFICATION_FRAMES;
    }

    /**
     * Compile/apply the saved selection at boot WITHOUT persisting or showing a
     * toast (boot is not a user action). On activation failure, record the entry
     * as failed and fall back to OFF silently, per the spec recovery rule.
     */
    public void applySavedSelectionSilently() {
        DisplayShaderPresetRef ref = library.at(index);
        if (ref.kind() == DisplayShaderPresetRef.Kind.OFF) {
            activate.test(ref);
            return;
        }
        if (!activate.test(ref)) {
            failedThisProcess.add(ref.relativePath());
            index = 0;
            activate.test(library.at(0)); // OFF
        }
    }

    public DisplayShaderPresetRef currentRef() {
        return library.at(index);
    }

    public String notificationText() {
        return notificationFramesRemaining > 0 ? notificationText : null;
    }
}
```

The `select(...)` path (used only by `cycle`) persists and toasts because cycling is a user action; `applySavedSelectionSilently()` deliberately bypasses both.

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderController" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderController.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderController.java
git commit -m "feat(shaderlib): display shader cycling controller"
```
Trailer: `Changelog: n/a: controller wired into Engine in Phase E`.

---

# Phase F — GL pipeline & Engine integration

> GL tasks cannot use `HeadlessTestRunner`. Their automated coverage is best-effort GL smoke tests that create a hidden GLFW context and **skip** (via `Assumptions.assumeTrue`) when no context is available, plus render-order assertions that need no GL. Treat the manual run (Step "verify in-app") as the real acceptance check.

### Task 10: `RetroArchGlslCompat` source normalization (no GL)

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/RetroArchGlslCompat.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestRetroArchGlslCompat.java`

This pure string normalizer is required because `Engine` runs OpenGL 4.1 core profile with forward compatibility enabled. The normalizer emits `#version 410 core`, inserts the stage define immediately after the version line, preserves RetroArch `COMPAT_*` headers, and injects only bounded legacy support when needed. It must never inject uniform declarations; the pipeline populates user-declared uniforms by location.

- [ ] **Step 1: Write the failing test**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRetroArchGlslCompat {

    @Test
    void emits410CoreAndStageDefineBeforeSource() throws Exception {
        String out = RetroArchGlslCompat.stageSource("#version 120\nvarying vec2 uv;\n", "FRAGMENT");
        assertTrue(out.startsWith("#version 410 core\n#define FRAGMENT\n"));
        assertFalse(out.contains("#version 120"));
    }

    @Test
    void rejectsVersionsAboveEngineContext() {
        assertThrows(UnsupportedShaderException.class,
                () -> RetroArchGlslCompat.stageSource("#version 450\nvoid main(){}\n", "VERTEX"));
    }

    @Test
    void injectsLegacyFragmentPreludeWithoutUniformDeclarations() throws Exception {
        String out = RetroArchGlslCompat.stageSource(
                "varying vec2 vTex;\nuniform sampler2D Texture;\nvoid main(){ gl_FragColor = texture2D(Texture, vTex); }\n",
                "FRAGMENT");
        assertTrue(out.contains("#define texture2D texture"));
        assertTrue(out.contains("out vec4 FragColor;"));
        assertTrue(out.contains("#define gl_FragColor FragColor"));
        assertFalse(out.contains("uniform vec2 OutputSize;"));
        assertFalse(out.contains("uniform int FrameCount;"));
    }

    @Test
    void injectsLegacyVertexPreludeStageAware() throws Exception {
        String out = RetroArchGlslCompat.stageSource(
                "attribute vec4 VertexCoord;\nvarying vec2 vTex;\nvoid main(){ gl_Position = VertexCoord; }\n",
                "VERTEX");
        assertTrue(out.contains("#define attribute in"));
        assertTrue(out.contains("#define varying out"));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestRetroArchGlslCompat" test`
Expected: FAIL (`RetroArchGlslCompat` does not exist).

- [ ] **Step 3: Implement the normalizer**

```java
package com.openggf.graphics.shaderlib;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds stage-specific GLSL source compatible with the engine's OpenGL 4.1 core profile. */
public final class RetroArchGlslCompat {
    private static final Pattern VERSION =
            Pattern.compile("(?m)^\\s*#version\\s+(\\d+)(?:\\s+\\w+)?\\s*$");

    private RetroArchGlslCompat() {
    }

    public static String stageSource(String source, String stage) throws UnsupportedShaderException {
        Matcher m = VERSION.matcher(source);
        int bodyStart = 0;
        if (m.find()) {
            int version = Integer.parseInt(m.group(1));
            if (version > 410) {
                throw new UnsupportedShaderException("GLSL version " + version
                        + " is newer than the engine's 4.1 core context");
            }
            bodyStart = m.end();
        }
        String body = source.substring(bodyStart).stripLeading();
        StringBuilder out = new StringBuilder();
        out.append("#version 410 core\n");
        out.append("#define ").append(stage).append('\n');
        appendLegacyPrelude(out, body, stage);
        out.append(body);
        return out.toString();
    }

    private static void appendLegacyPrelude(StringBuilder out, String body, String stage) {
        if (body.contains("texture2D(")) {
            out.append("#define texture2D texture\n");
        }
        if (stage.equals("VERTEX")) {
            if (containsToken(body, "attribute")) {
                out.append("#define attribute in\n");
            }
            if (containsToken(body, "varying")) {
                out.append("#define varying out\n");
            }
        } else if (stage.equals("FRAGMENT")) {
            if (containsToken(body, "varying")) {
                out.append("#define varying in\n");
            }
            if (body.contains("gl_FragColor") && !declaresFragmentOutput(body)) {
                out.append("out vec4 FragColor;\n");
                out.append("#define gl_FragColor FragColor\n");
            }
        }
    }

    private static boolean containsToken(String body, String token) {
        return Pattern.compile("\\b" + Pattern.quote(token) + "\\b").matcher(body).find();
    }

    private static boolean declaresFragmentOutput(String body) {
        return Pattern.compile("(?m)^\\s*(layout\\s*\\([^)]*\\)\\s*)?out\\s+\\w+\\s+\\w+\\s*;")
                .matcher(body).find();
    }
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestRetroArchGlslCompat" test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/RetroArchGlslCompat.java src/test/java/com/openggf/graphics/shaderlib/TestRetroArchGlslCompat.java
git commit -m "feat(shaderlib): normalize retroarch glsl for core profile"
```
Trailer: `Changelog: n/a: normalizer wired into GL pipeline next`.

---

### Task 11: `DisplayShaderPipeline` (compile, ping-pong FBO, composite)

**Files:**
- Create: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPipeline.java`
- Test: `src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java`

Design: the pipeline holds the active compiled program(s) and ping-pong FBOs (`viewport render -> capture FBO -> pass FBOs -> default framebuffer`). It reuses `FboHelper` for FBO lifecycle and viewport save/restore. Display shaders are external user content with the BizHawk + RetroArch GLSL uniform contract, so the pipeline compiles GLSL from source strings directly (the existing `ShaderProgram` only compiles classpath files). Two vertex paths are built here: fragment-only passes use an empty VAO + `gl_VertexID` fullscreen quad; combined BizHawk/RetroArch passes use a real interleaved VBO exposing `VertexCoord` (vec4-compatible, location 0), `TexCoord` (vec4-compatible, location 1), and `COLOR` (constant white, location 2), with those names bound to fixed locations via `glBindAttribLocation` before link so a single quad VAO serves every combined program. `apply(vpX, vpY, vpW, vpH, frameCount)` takes the engine's aspect-correct game-viewport rectangle so letterbox/pillarbox bars are never captured or overwritten, and receives the display-only frame counter used for RetroArch `FrameCount`.

- [ ] **Step 1: Write the failing smoke tests**

```java
package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.glfw.GLFW.*;

class TestDisplayShaderPipelineSmoke {
    private long initHiddenContext() {
        if (!glfwInit()) {
            return 0L;
        }
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        long w = glfwCreateWindow(64, 64, "smoke", 0L, 0L);
        if (w != 0L) {
            glfwMakeContextCurrent(w);
            org.lwjgl.opengl.GL.createCapabilities();
        }
        return w;
    }

    @Test
    void badShaderSelectionFailsWithoutThrowing() {
        long window = 0L;
        try {
            window = initHiddenContext();
            assumeTrue(window != 0L, "No GL context available; skipping GL smoke test");

            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(256, 224, 256, 224);
            DisplayShaderPass broken = new DisplayShaderPass(
                    null, "this is not valid glsl", GlslShape.FRAGMENT_ONLY,
                    1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE);
            boolean ok = pipeline.activate(new DisplayShaderPreset("bad", ShaderPhase.PRESENTATION,
                    java.util.List.of(broken)));
            assertFalse(ok, "Broken shader must fail selection, not crash");
            assertFalse(pipeline.isActive());
            pipeline.dispose();
        } finally {
            if (window != 0L) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
        }
    }

    @Test
    void retroArchCombinedShaderCompilesInCoreForwardCompatibleContext() {
        long window = 0L;
        try {
            window = initHiddenContext();
            assumeTrue(window != 0L, "No GL context available; skipping GL smoke test");

            String source = """
                    #if defined(VERTEX)
                    in vec4 VertexCoord;
                    in vec4 TexCoord;
                    in vec4 COLOR;
                    out vec2 vTex;
                    void main() {
                        vTex = TexCoord.xy;
                        gl_Position = VertexCoord;
                    }
                    #elif defined(FRAGMENT)
                    uniform sampler2D Texture;
                    uniform vec2 InputSize;
                    uniform vec2 TextureSize;
                    uniform vec2 OutputSize;
                    uniform int FrameCount;
                    in vec2 vTex;
                    out vec4 FragColor;
                    void main() {
                        FragColor = texture(Texture, vTex) + vec4(float(FrameCount) * 0.0);
                    }
                    #endif
                    """;
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(256, 224, 256, 224);
            DisplayShaderPass pass = new DisplayShaderPass(
                    source, source, GlslShape.COMBINED,
                    1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE);
            assertTrue(pipeline.activate(new DisplayShaderPreset("ra", ShaderPhase.PRESENTATION,
                    java.util.List.of(pass))));
            pipeline.dispose();
        } finally {
            if (window != 0L) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
        }
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPipelineSmoke" test`
Expected: FAIL (class does not exist) — or SKIP if no GL; if skipped, proceed and rely on the manual check. `RetroArchGlslCompat` string-normalization tests already ran in Task 10 and are not duplicated here.

- [ ] **Step 3: Implement `DisplayShaderPipeline`**

```java
package com.openggf.graphics.shaderlib;

import com.openggf.util.FboHelper;
import com.openggf.util.FboHelper.FboHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * GL post-process chain for display shaders. Captures the rendered game-viewport
 * region into an FBO, runs each compiled pass through ping-pong FBOs, then
 * composites the final texture back to the same default-framebuffer rectangle.
 * All failures degrade to "inactive" (scene renders unmodified) rather than abort.
 *
 * <p>Two vertex paths: fragment-only passes use an empty VAO + {@code gl_VertexID}
 * fullscreen quad; combined BizHawk passes use a real VBO exposing
 * {@code VertexCoord} (location 0, vec4) and {@code TexCoord} (location 1, vec2),
 * bound before link so a single VAO serves every combined program.
 */
public final class DisplayShaderPipeline {

    private static final Logger LOG = Logger.getLogger(DisplayShaderPipeline.class.getName());

    // Engine fullscreen quad for fragment-only passes: triangle strip from gl_VertexID.
    private static final String FULLSCREEN_VERT =
            "#version 330 core\n"
            + "out vec2 v_uv;\n"
            + "void main(){\n"
            + "  vec2 p[4]=vec2[](vec2(-1.,-1.),vec2(1.,-1.),vec2(-1.,1.),vec2(1.,1.));\n"
            + "  v_uv=p[gl_VertexID]*0.5+0.5;\n"
            + "  gl_Position=vec4(p[gl_VertexID],0.,1.);\n"
            + "}\n";

    // Interleaved quad: VertexCoord(vec4) + TexCoord(vec4) + COLOR(vec4), triangle-strip BL,BR,TL,TR.
    private static final float[] QUAD = {
            -1f, -1f, 0f, 1f, 0f, 0f, 0f, 1f, 1f, 1f, 1f, 1f,
             1f, -1f, 0f, 1f, 1f, 0f, 0f, 1f, 1f, 1f, 1f, 1f,
            -1f,  1f, 0f, 1f, 0f, 1f, 0f, 1f, 1f, 1f, 1f, 1f,
             1f,  1f, 0f, 1f, 1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f,
    };
    private static final int FLOATS_PER_VERTEX = 12;
    private static final int STRIDE_BYTES = FLOATS_PER_VERTEX * Float.BYTES;

    private record PassProgram(int program, GlslShape shape) {
    }

    private int sourceWidth = 1;
    private int sourceHeight = 1;
    private int viewportWidth = 1;
    private int viewportHeight = 1;

    private boolean active;
    private DisplayShaderPreset preset;
    private final List<PassProgram> programs = new ArrayList<>();
    private FboHandle captureFbo;
    private final List<FboHandle> passFbos = new ArrayList<>();
    private int emptyVao;
    private int quadVao;
    private int quadVbo;

    public boolean isActive() {
        return active;
    }

    public ShaderPhase phase() {
        return preset != null ? preset.phase() : ShaderPhase.PRESENTATION;
    }

    /**
     * @param sourceW logical game width (SCREEN_WIDTH_PIXELS)
     * @param sourceH logical game height (SCREEN_HEIGHT_PIXELS)
     * @param viewportW on-screen game viewport width in pixels
     * @param viewportH on-screen game viewport height in pixels
     */
    public void resize(int sourceW, int sourceH, int viewportW, int viewportH) {
        boolean changed = sourceW != sourceWidth || sourceH != sourceHeight
                || viewportW != viewportWidth || viewportH != viewportHeight;
        this.sourceWidth = Math.max(1, sourceW);
        this.sourceHeight = Math.max(1, sourceH);
        this.viewportWidth = Math.max(1, viewportW);
        this.viewportHeight = Math.max(1, viewportH);
        if (active && changed) {
            rebuildFbos();
        }
    }

    /** Compile + select a preset. Returns false (and stays inactive) on any failure. */
    public boolean activate(DisplayShaderPreset newPreset) {
        disposePrograms();
        if (newPreset == null) { // OFF
            active = false;
            preset = null;
            return true;
        }
        try {
            ensureQuadResources();
            for (DisplayShaderPass pass : newPreset.passes()) {
                boolean combined = pass.shape() == GlslShape.COMBINED;
                String vert = pass.vertexSource() != null
                        ? RetroArchGlslCompat.stageSource(pass.vertexSource(), "VERTEX")
                        : FULLSCREEN_VERT;
                String frag = RetroArchGlslCompat.stageSource(pass.fragmentSource(), "FRAGMENT");
                programs.add(new PassProgram(linkProgram(vert, frag, combined), pass.shape()));
            }
            this.preset = newPreset;
            this.active = true;
            rebuildFbos();
            return true;
        } catch (RuntimeException e) {
            LOG.warning("Display shader activation failed: " + e.getMessage());
            disposePrograms();
            active = false;
            preset = null;
            return false;
        }
    }

    /**
     * Capture the game viewport region from the default framebuffer, run passes,
     * composite back to the SAME rectangle. No-op when inactive. The viewport
     * rectangle must be the engine's aspect-correct game region (origin included)
     * so letterbox/pillarbox bars are not captured or overwritten.
     */
    public void apply(int vpX, int vpY, int vpW, int vpH, int frameCount) {
        if (!active || captureFbo == null || passFbos.isEmpty()) {
            return;
        }
        int[] savedViewport = FboHelper.saveViewport();
        try {
            // Copy the game-viewport region of the default framebuffer into the capture texture.
            glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, captureFbo.fboId());
            glBlitFramebuffer(vpX, vpY, vpX + vpW, vpY + vpH,
                    0, 0, sourceWidth, sourceHeight, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);

            int inputTex = captureFbo.textureId();
            int inputW = sourceWidth;
            int inputH = sourceHeight;

            // Render EVERY pass (including the last) into its own scaleN-sized FBO, so
            // scale0/scale_type0 are honored and IN.output_size / gl_FragCoord match the
            // preset's intended pass resolution. The final texture is then presented to
            // the viewport rectangle by a blit below.
            for (int i = 0; i < programs.size(); i++) {
                FboHandle target = passFbos.get(i);
                int outW = fboWidth(i);
                int outH = fboHeight(i);

                glBindFramebuffer(GL_FRAMEBUFFER, target.fboId());
                glViewport(0, 0, outW, outH);

                PassProgram pp = programs.get(i);
                glUseProgram(pp.program());
                bindUniforms(pp.program(), inputW, inputH, outW, outH, frameCount);
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, inputTex);
                applySamplerState(passFilterLinear(i), passWrapMode(i));

                glBindVertexArray(pp.shape() == GlslShape.COMBINED ? quadVao : emptyVao);
                glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

                inputTex = target.textureId();
                inputW = outW;
                inputH = outH;
            }
            glBindVertexArray(0);
            glUseProgram(0);

            // Present the final pass texture into the game viewport rectangle.
            FboHandle finalFbo = passFbos.get(passFbos.size() - 1);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, finalFbo.fboId());
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glBlitFramebuffer(0, 0, inputW, inputH,
                    vpX, vpY, vpX + vpW, vpY + vpH, GL_COLOR_BUFFER_BIT, GL_LINEAR);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        } catch (RuntimeException e) {
            LOG.warning("Display shader render failed; disabling pipeline: " + e.getMessage());
            active = false;
        } finally {
            FboHelper.restoreViewport(savedViewport);
        }
    }

    private void bindUniforms(int program, int texW, int texH, int outW, int outH, int frameCount) {
        bindSampler(program, "s_p", 0);
        bindSampler(program, "SceneTexture", 0);
        bindSampler(program, "Texture", 0);
        setVec2(program, "IN.video_size", sourceWidth, sourceHeight);
        setVec2(program, "IN.texture_size", texW, texH);
        setVec2(program, "IN.output_size", outW, outH);
        setVec2(program, "InputSize", texW, texH);
        setVec2(program, "TextureSize", texW, texH);
        setVec2(program, "OutputSize", outW, outH);
        setInt(program, "FrameCount", frameCount);
        setInt(program, "FrameDirection", 1);
        int mvp = glGetUniformLocation(program, "MVPMatrix");
        if (mvp >= 0) {
            glUniformMatrix4fv(mvp, false, new float[]{
                    1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1});
        }
    }

    private void bindSampler(int program, String name, int unit) {
        int loc = glGetUniformLocation(program, name);
        if (loc >= 0) {
            glUniform1i(loc, unit);
        }
    }

    private void setVec2(int program, String name, float x, float y) {
        int loc = glGetUniformLocation(program, name);
        if (loc >= 0) {
            glUniform2f(loc, x, y);
        }
    }

    private void setInt(int program, String name, int value) {
        int loc = glGetUniformLocation(program, name);
        if (loc >= 0) {
            glUniform1i(loc, value);
        }
    }

    private void applySamplerState(boolean linear, WrapMode wrapMode) {
        int mode = linear ? GL_LINEAR : GL_NEAREST;
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, mode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, mode);
        int wrap = switch (wrapMode) {
            case CLAMP_TO_BORDER -> GL_CLAMP_TO_BORDER;
            case REPEAT -> GL_REPEAT;
            case CLAMP_TO_EDGE -> GL_CLAMP_TO_EDGE;
        };
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
    }

    private boolean passFilterLinear(int i) {
        return preset != null && i < preset.passes().size() && preset.passes().get(i).filterLinear();
    }

    private WrapMode passWrapMode(int i) {
        return preset != null && i < preset.passes().size()
                ? preset.passes().get(i).wrapMode()
                : WrapMode.CLAMP_TO_EDGE;
    }

    private int fboWidth(int passIndex) {
        DisplayShaderPass p = preset.passes().get(passIndex);
        return Math.max(1, (p.scaleType() == ScaleType.VIEWPORT ? viewportWidth : sourceWidth) * p.scale());
    }

    private int fboHeight(int passIndex) {
        DisplayShaderPass p = preset.passes().get(passIndex);
        return Math.max(1, (p.scaleType() == ScaleType.VIEWPORT ? viewportHeight : sourceHeight) * p.scale());
    }

    private void rebuildFbos() {
        disposeFbos();
        captureFbo = FboHelper.createColorOnly(sourceWidth, sourceHeight, GL_CLAMP_TO_EDGE);
        // One FBO per pass (including the last). The final pass texture is presented to
        // the viewport by a blit in apply(), so scaleN is honored for single-pass presets too.
        for (int i = 0; i < programs.size(); i++) {
            passFbos.add(FboHelper.createColorOnly(fboWidth(i), fboHeight(i), GL_CLAMP_TO_EDGE));
        }
    }

    private void ensureQuadResources() {
        if (emptyVao == 0) {
            emptyVao = glGenVertexArrays();
        }
        if (quadVao == 0) {
            quadVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
            glBufferData(GL_ARRAY_BUFFER, QUAD, GL_STATIC_DRAW);
            quadVao = glGenVertexArrays();
            glBindVertexArray(quadVao);
            // location 0: VertexCoord (vec4) at offset 0
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 4, GL_FLOAT, false, STRIDE_BYTES, 0L);
            // location 1: TexCoord (vec4-compatible) at offset 4 floats
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, STRIDE_BYTES, 4L * Float.BYTES);
            // location 2: COLOR (vec4 constant white from the quad VBO) at offset 8 floats
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, STRIDE_BYTES, 8L * Float.BYTES);
            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    private int linkProgram(String vertSrc, String fragSrc, boolean bindQuadAttribs) {
        int vert = compile(GL_VERTEX_SHADER, vertSrc);
        int frag = compile(GL_FRAGMENT_SHADER, fragSrc);
        int program = glCreateProgram();
        glAttachShader(program, vert);
        glAttachShader(program, frag);
        if (bindQuadAttribs) {
            // Force BizHawk/RetroArch attribute names to the quad VAO's fixed locations.
            glBindAttribLocation(program, 0, "VertexCoord");
            glBindAttribLocation(program, 1, "TexCoord");
            glBindAttribLocation(program, 2, "COLOR");
        }
        glLinkProgram(program);
        glDeleteShader(vert);
        glDeleteShader(frag);
        if (glGetProgrami(program, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(program);
            glDeleteProgram(program);
            throw new RuntimeException("link failed: " + log);
        }
        return program;
    }

    private int compile(int type, String src) {
        int shader = glCreateShader(type);
        glShaderSource(shader, src);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new RuntimeException("compile failed: " + log);
        }
        return shader;
    }

    private void disposePrograms() {
        for (PassProgram p : programs) {
            glDeleteProgram(p.program());
        }
        programs.clear();
    }

    private void disposeFbos() {
        if (captureFbo != null) {
            FboHelper.destroy(captureFbo);
            captureFbo = null;
        }
        for (FboHandle h : passFbos) {
            FboHelper.destroy(h);
        }
        passFbos.clear();
    }

    public void dispose() {
        disposePrograms();
        disposeFbos();
        if (emptyVao != 0) {
            glDeleteVertexArrays(emptyVao);
            emptyVao = 0;
        }
        if (quadVao != 0) {
            glDeleteVertexArrays(quadVao);
            quadVao = 0;
        }
        if (quadVbo != 0) {
            glDeleteBuffers(quadVbo);
            quadVbo = 0;
        }
        active = false;
        preset = null;
    }
}
```

- [ ] **Step 4: Run to verify it passes (or skips cleanly)**

Run: `mvn "-Dtest=com.openggf.graphics.shaderlib.TestDisplayShaderPipelineSmoke" test`
Expected: PASS, or SKIPPED with "No GL context available" (acceptable on headless CI).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPipeline.java src/test/java/com/openggf/graphics/shaderlib/TestDisplayShaderPipelineSmoke.java
git commit -m "feat(shaderlib): GL display shader pipeline with ping-pong FBOs"
```
Trailer: `Changelog: updated` (stage `CHANGELOG.md`) — this adds rendering behavior.

---

### Task 12: Own the pipeline in `GraphicsManager`; wire controller, picker key, and phases in `Engine`

**Files:**
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java` (init/own pipeline ~line 296; add accessor near line 1877)
- Modify: `src/main/java/com/openggf/Engine.java` (field ~line 113; construct controller ~line 384; update ~line 1360; composite at phase in `display()`; resize)

This task wires the fast path (`[`/`]`), the picker-open key (`BACKSLASH` by default), and the picker-owned libretro install/update action (`F5`). The first implementation may render a compact pixel-font overlay backed by `DisplayShaderSelectionModel`; it should be enough to filter by root-relative text/category, select a visible entry without cycling through the whole libretro pack, and trigger the `RetroArchGlslShaderPackDownloader` from inside the application. If the overlay is split into its own renderer/controller file during implementation, keep it under `com.openggf.graphics.shaderlib` and add focused non-GL tests for its state machine.

- [ ] **Step 1: Add pipeline ownership to `GraphicsManager`**

After `this.uiRenderPipeline = new UiRenderPipeline(this);` (line 296) add:

```java
		this.displayShaderPipeline = new com.openggf.graphics.shaderlib.DisplayShaderPipeline();
```

Add the field near line 116 (next to `uiRenderPipeline`):

```java
	private com.openggf.graphics.shaderlib.DisplayShaderPipeline displayShaderPipeline;
```

Add an accessor near `getUiRenderPipeline()` (line 1877):

```java
	public com.openggf.graphics.shaderlib.DisplayShaderPipeline getDisplayShaderPipeline() {
		return displayShaderPipeline;
	}
```

- [ ] **Step 2: Construct the controller in `Engine`**

Add a field near line 113 (next to `displayColorProfileController`):

```java
	private com.openggf.graphics.shaderlib.DisplayShaderController displayShaderController;
	private com.openggf.graphics.shaderlib.DisplayShaderSelectionModel displayShaderSelectionModel;
	private int displayShaderFrameCounter;
```

After the `displayColorProfileController = DisplayColorProfileController.fromConfig(...)` block (line 384), build the library + controller. The `activate` predicate loads the preset and hands it to the pipeline:

```java
		java.nio.file.Path shaderRoot = java.nio.file.Path.of(
				configuration.getString(SonicConfiguration.DISPLAY_SHADER_LIBRARY_ROOT));
		com.openggf.graphics.shaderlib.DisplayShaderLibrary shaderLibrary =
				com.openggf.graphics.shaderlib.DisplayShaderLibrary.scan(shaderRoot);
		displayShaderSelectionModel =
				new com.openggf.graphics.shaderlib.DisplayShaderSelectionModel(shaderLibrary);
		com.openggf.graphics.shaderlib.ShaderPhase defaultPhase =
				com.openggf.graphics.shaderlib.ShaderPhase.valueOf(
						configuration.getString(SonicConfiguration.DISPLAY_SHADER_DEFAULT_PHASE));
		com.openggf.graphics.shaderlib.DisplayShaderPresetLoader shaderLoader =
				new com.openggf.graphics.shaderlib.DisplayShaderPresetLoader();
		displayShaderController = new com.openggf.graphics.shaderlib.DisplayShaderController(
				shaderLibrary,
				configuration.getString(SonicConfiguration.DISPLAY_SHADER_SELECTION),
				configuration.getInt(SonicConfiguration.DISPLAY_SHADER_NEXT_KEY),
				configuration.getInt(SonicConfiguration.DISPLAY_SHADER_PREVIOUS_KEY),
				selection -> {
					configuration.setConfigValue(SonicConfiguration.DISPLAY_SHADER_SELECTION, selection);
					configuration.saveConfig();
				},
				ref -> {
					com.openggf.graphics.shaderlib.DisplayShaderPipeline pipeline =
							graphicsManager.getDisplayShaderPipeline();
					if (ref.kind() == com.openggf.graphics.shaderlib.DisplayShaderPresetRef.Kind.OFF) {
						return pipeline.activate(null);
					}
					try {
						return pipeline.activate(shaderLoader.load(ref, defaultPhase));
					} catch (Exception e) {
						return false;
					}
				});
		// Compile/apply the saved selection once at startup, silently (no toast, no
		// re-persist). Falls back to OFF if the saved shader fails to compile.
		displayShaderController.applySavedSelectionSilently();
```

(`applySavedSelectionSilently()` was added to `DisplayShaderController` in Task 9 — it deliberately bypasses the persist + notification path that `cycle()` uses for user-driven changes.)

- [ ] **Step 3: Update the controller each frame**

After the `displayColorProfileController.update(inputHandler)` block (line 1360-1361) add controller update plus picker-key handling:

```java
		if (displayShaderController != null) {
			displayShaderController.update(inputHandler);
		}
		if (displayShaderSelectionModel != null
				&& inputHandler.isKeyPressed(configuration.getInt(SonicConfiguration.DISPLAY_SHADER_PICKER_KEY))) {
			openDisplayShaderPicker();
		}
```

Add the picker helper and fields in `Engine` in the same step. If implementation chooses to extract the overlay state into `DisplayShaderPickerController`, keep this helper as a thin toggle/delegate:

```java
	private boolean displayShaderPickerOpen;
	private String displayShaderPickerQuery = "";
	private java.util.List<com.openggf.graphics.shaderlib.DisplayShaderSelectionModel.SelectionItem>
			displayShaderPickerItems = java.util.List.of();

	private void openDisplayShaderPicker() {
		displayShaderPickerOpen = !displayShaderPickerOpen;
		if (displayShaderPickerOpen) {
			displayShaderPickerQuery = "";
			displayShaderPickerItems = displayShaderSelectionModel.filter(displayShaderPickerQuery);
		}
	}
```

The overlay input/render details can be implemented either inline in `Engine` or as a small extracted controller/renderer. Acceptance requirement: pressing `BACKSLASH` opens a visible pixel-font picker, typing/filtering narrows by root-relative path/category, arrow keys move the visible selection, `ENTER` activates the selected entry through the same activation path as cycling, `F5` installs/updates the libretro GLSL pack with progress feedback and rescans on completion, and `ESCAPE` closes without changing selection.

- [ ] **Step 4: Composite at the configured phase**

The pipeline runs at exactly one of the three phases. Add a private helper to `Engine` that applies the pipeline only when active and at the requested phase, always passing the engine's aspect-correct game-viewport rectangle (`viewportX, viewportY, viewportWidth, viewportHeight`) — never the whole window — so letterbox/pillarbox bars are untouched. Add near `renderDisplayColorProfileNotification()`:

```java
	private void applyDisplayShaderPhase(com.openggf.graphics.shaderlib.ShaderPhase phase) {
		com.openggf.graphics.shaderlib.DisplayShaderPipeline shaderPipeline =
				graphicsManager.getDisplayShaderPipeline();
		if (shaderPipeline == null || !shaderPipeline.isActive() || shaderPipeline.phase() != phase) {
			return;
		}
		shaderPipeline.resize(
				configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
				configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS),
				viewportWidth, viewportHeight);
		shaderPipeline.apply(viewportX, viewportY, viewportWidth, viewportHeight,
				displayShaderFrameCounter++);
	}
```

Then insert the three phase calls in `display()`:

- `SCENE` — immediately after `graphicsManager.flush()` (line 1368):
```java
			applyDisplayShaderPhase(com.openggf.graphics.shaderlib.ShaderPhase.SCENE);
```
- `PRESENTATION` — immediately after the `uiPipeline.renderFadePass()` block (line 1374), before the post-fade diagnostics so diagnostics stay readable:
```java
			applyDisplayShaderPhase(com.openggf.graphics.shaderlib.ShaderPhase.PRESENTATION);
```
- `FINAL` — after the debug-overlay block, just before the F12 screenshot check (~line 1462):
```java
			applyDisplayShaderPhase(com.openggf.graphics.shaderlib.ShaderPhase.FINAL);
```

(`viewportX`/`viewportY`/`viewportWidth`/`viewportHeight` are the same fields `display()` already uses at `Engine.java:1336` to position the game viewport.)

- [ ] **Step 5: Build and run the full suite**

Run: `mvn test`
Expected: PASS (no regressions; GL smoke test passes or skips).

- [ ] **Step 6: Verify in-app (manual acceptance)**

1. Create `shaders/Custom/` at the repo root and drop a simple fragment-only `warm-crt.glsl` (e.g. multiply scene by `vec3(1.1, 1.0, 0.9)`).
2. Run: `java -jar target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar` (with a ROM present).
3. Press `]` — confirm the on-screen `Shader: Custom/warm-crt` text appears and the image warms.
4. Press `[` back to `Off` — confirm the image returns to normal and `Shader: Off` shows.
5. Quit and re-launch — confirm the selection persisted (the warm shader reapplies if it was selected).
6. Copy `BizScanlines.cgp` + `BizScanlines.glsl` into `shaders/BizHawk/`, cycle to it, and confirm scanline density looks correct at multiple window sizes (the `scale0 = 2` source-scaled pass should keep scanline spacing tied to game pixels, not window pixels). Resize the window and confirm the density does not change — this validates the `scaleN` fix.
7. With a synthetic `shaders/libretro-glsl/crt/` and `shaders/libretro-glsl/scanlines/` fixture present, press `BACKSLASH`, type/filter `crt`, confirm only matching entries remain, select one with arrow keys + `ENTER`, and confirm it activates without stepping through unrelated entries.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/graphics/GraphicsManager.java src/main/java/com/openggf/Engine.java src/main/java/com/openggf/graphics/shaderlib/DisplayShaderController.java CHANGELOG.md
git commit -m "feat: integrate display shader pipeline into engine render loop"
```
Trailer: `Changelog: updated` / `Guide: updated` if you touch a guide, else `n/a`.

---

### Task 13: Stacked on-screen notification

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java` (new `renderDisplayShaderNotification()` near line 1581; call near line 1384)

The color-profile notification draws at `y = 224 - lineHeight - 4`. The shader notification stacks one line above so both can be visible.

- [ ] **Step 1: Add the render method**

After `renderDisplayColorProfileNotification()` (ends line 1581) add:

```java
	private void renderDisplayShaderNotification() {
		if (displayShaderController == null) {
			return;
		}
		String text = displayShaderController.notificationText();
		if (text == null) {
			return;
		}
		float scale = 1.0f;
		int lineHeight = traceHudTextRenderer.lineHeight(scale);
		int y = 224 - (lineHeight * 2) - 6; // one line above the color-profile slot
		traceHudTextRenderer.setProjectionMatrix(getProjectionMatrixBuffer());
		traceHudTextRenderer.drawShadowedText(text, 4, y, DebugColor.YELLOW, scale);
	}
```

- [ ] **Step 2: Call it next to the color-profile notification**

After `renderDisplayColorProfileNotification();` (line 1384) add a recorder tag + the call:

```java
		if (postFadeRecorder != null) {
			postFadeRecorder.recordPostFadeDiagnostic("DisplayShaderNotification");
		}
		renderDisplayShaderNotification();
```

- [ ] **Step 3: Build**

Run: `mvn -q -DskipTests compile`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Verify in-app**

Re-run the app, toggle color profile (`V`) and shader (`]`) within ~2s of each other; confirm both notifications are visible stacked (shader above color).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/Engine.java
git commit -m "feat: stack display shader notification above color-profile toast"
```
Trailer: `Changelog: updated`.

---

# Phase G — Documentation & finish

### Task 14: Documentation + screenshot/trace parity note + branch finish

**Files:**
- Modify: `CONFIGURATION.md` (contract, limitations, layout, recovery)
- Modify: `.gitignore` (add `/shaders/`)

- [ ] **Step 1: Add `/shaders/` to `.gitignore`**

Append to `.gitignore`:

```text
# User-supplied display shader library (root-level; engine shaders live in src/main/resources/shaders)
/shaders/
```

- [ ] **Step 2: Expand `CONFIGURATION.md`**

Add a "Display shader library" subsection covering: the default `shaders/` layout (the spec's User Workflow tree), the `shaders/libretro-glsl` install/update flow (picker `F5`, GitHub zip archive, top-level folder stripped, `.openggf-libretro-glsl.properties` metadata, rescan on completion), the searchable/category picker for large libraries (`BACKSLASH` default) alongside `[`/`]` quick cycling, the GLSL compatibility contract (the `s_p`/`SceneTexture`/`IN.*`/`MVPMatrix` uniforms and `VertexCoord`/`TexCoord` attributes for combined shaders), the HLSL/Cg limitation (discoverable, fail-on-select), the recovery behavior (broken shader → `Off`, rendering continues), and the note that `src/main/resources/shaders` are engine shaders while root `shaders/` is the user library and is gitignored unless sample licensing is handled.

- [ ] **Step 3: Confirm the screenshot/trace parity behavior holds (no code change expected)**

Verify by inspection: F12 `ScreenshotCapture` reads `GL_BACK` after the composite, so screenshots capture the shader output (intended). `TraceCaptureSession.renderFrame()` has its own render path that stops after `graphicsManager.flush()` and never calls `displayShaderPipeline.apply()`, so trace/video artifacts stay shader-free. If `renderFrame()` is ever changed to share `Engine.display()`, gate the `apply()` calls on "not in trace capture". Document this one-liner in the `CONFIGURATION.md` recovery/limitations note.

- [ ] **Step 4: Full verification sweep**

Run: `mvn test`
Expected: PASS. Then re-run the in-app manual check from Task 12 Step 6 once more to confirm nothing regressed.

- [ ] **Step 5: Commit**

```bash
git add CONFIGURATION.md .gitignore
git commit -m "docs: document display shader library, contract, and limitations"
```
Trailer: `Configuration-Docs: updated` / `Changelog: n/a: docs only`.

- [ ] **Step 6: Finish the branch**

Use the `superpowers:finishing-a-development-branch` skill to choose merge/PR/cleanup. Per repo policy, merging into `develop` requires a `README.md` release-log update in the merge.

---

## Self-Review

**Spec coverage:**
- Goals (runtime scan, Off entry, `[`/`]` cycling plus picker, persist-by-path, compile-only-selected, BizHawk GLSL + standalone GLSL, libretro download, display-only, on-screen confirmation) → Tasks 5, 4, 9/12, 9, 10/11, 7-8, 8A, 12 (apply()), 13. ✓
- Configuration (6 keys, YAML under `display`, ConfigCatalog enum/ordering, playback unbinding) → Tasks 1-3. ✓
- Discovery rules (cgp + unpaired glsl, ignore hlsl/cg/txt/hidden/outside, sort, Off=0, metadata-only, hlsl-only stays selectable) → Tasks 5, 7. ✓
- Preset loading (cgp subset fields, source resolution order, scale_type default source, standalone glsl, GLSL shapes) → Tasks 6, 7, 8. ✓
- Libretro GLSL download/install/update (zip archive, `libretro-glsl`, metadata, progress, categorized errors, staged extraction) → Task 8A. ✓
- Large-library picker/search/category model and picker key wiring → Tasks 8B and 12. ✓
- Render phases (SCENE/PRESENTATION/FINAL) → Task 12 Step 4. ✓
- Rendering architecture (shaderlib classes, downloader, picker model, ping-pong FBOs, FboHelper reuse, resize, GL_NEAREST/filter_linear) → Tasks 4-12. ✓
- Shader compatibility contract (uniform mapping, `RetroArchGlslCompat` stage normalization, combined vertex format) → Task 10 `RetroArchGlslCompat`, Task 11 `bindUniforms`, and the quad VBO with `glBindAttribLocation` for `VertexCoord`/`TexCoord`/`COLOR`. ✓
- Error handling (missing root, missing selection, unsupported source, compile/link failure, FBO failure, skip-failed-this-process, downloader network/archive/disk failures) → Tasks 5, 8A, 9, 11. ✓
- Notifications (DisplayColorProfileController pattern, stacking) → Tasks 9, 13. ✓
- Screenshots/capture parity → Task 14 Step 3. ✓
- Testing list → Tasks 2-12 (each logic bullet has a test; GL smoke per Task 11). ✓
- Documentation → Task 14. ✓

**Combined-shader support (now in scope):** Task 11 builds the textured-quad VBO with `VertexCoord`/`TexCoord` bound to fixed locations before link, so combined BizHawk shaders like `hq2x.glsl` receive valid vertex inputs. Residual risk: only the fragment-only and combined `#ifdef VERTEX/FRAGMENT` shapes are exercised; exotic BizHawk constructs (extra named samplers, `#pragma` parameters, prev-frame history) are still out of scope and will fail-on-select gracefully rather than render wrong. This matches the spec Non-Goal "no promise every pack works unchanged." `hq2x` should be smoke-tested manually in Task 12 Step 6. It is forced to `SCENE` phase by `phaseForCgp` (Task 7), so it scales game pixels before HUD/fade rather than scaling the composited UI.

**Placeholder scan:** No TBD/TODO; every code step has complete code. ✓

**Type consistency:** `DisplayShaderPresetRef.Kind`, `ScaleType`, `WrapMode`, `GlslShape`, `ShaderPhase`, `DisplayShaderPass(vertexSource, fragmentSource, shape, scale, scaleType, filterLinear, wrapMode)`, `DisplayShaderPreset(label, phase, passes)`, `DisplayShaderPresetLoader.load(ref, defaultPhase)` / `parseCgp` / `parseGlslp` / `detectShape` / `resolveGlslSource`, `RetroArchGlslCompat.stageSource(source, stage)`, `RetroArchGlslShaderPackDownloader.download/downloadIfNewer/checkForUpdate`, `DisplayShaderSelectionModel.filter/categories/select`, `DisplayShaderController(library, savedSelection, nextKey, previousKey, persistSelection, activate)` + `update`/`currentRef`/`notificationText`/`applySavedSelectionSilently`, `DisplayShaderPipeline.activate/apply/resize/isActive/phase/dispose` — names are consistent across all tasks. ✓
