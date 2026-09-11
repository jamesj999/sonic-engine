# Editor PixelFont And AWT Removal Design

## Goal

Remove `java.awt` and `javax.swing` from production code under `src/main/java`, starting with the editor text path, so native-image builds remain viable and the editor uses the engine's existing PNG-backed `PixelFont` instead of the AWT glyph pipeline. The single approved exception is `com.openggf.audio.debug.SoundTestApp`, which is a desktop-only debug tool and is not bundled into the native app.

## Requirements

1. Production code under `src/main/java` must not import or depend on AWT or Swing, except for `com.openggf.audio.debug.SoundTestApp`.
2. `EditorTextRenderer` must stop using `GlyphBatchRenderer` and AWT `Font`.
3. Editor overlay text should use the existing `PixelFont` asset path already proven by `MasterTitleScreen`.
4. The codebase must gain an automated guard so new AWT/Swing usage in production sources fails tests immediately.
5. Existing production usages of AWT/Swing should be removed incrementally as they are encountered, with the editor path addressed first.

## Non-Goals

1. Replacing all test-only `java.awt.image.BufferedImage` usage in this pass.
2. Introducing per-game ROM-derived editor fonts.
3. Redesigning editor layout, copy, or controls.
4. Rebuilding screenshot comparison tooling away from `BufferedImage` in the same task.

## Current State

### Editor text

`EditorTextRenderer` currently builds `TextCommand` records and renders them through `GlyphBatchRenderer`. That path initializes an AWT `Font`, depends on the debug glyph atlas, and silently disables itself if AWT-backed initialization fails.

### Existing non-editor production AWT usage

Known current `src/main/java` AWT/Swing usages include:

1. `com.openggf.debug.DebugRenderer`
2. `com.openggf.debug.GlyphBatchRenderer`
3. `com.openggf.debug.GlyphAtlas`
4. `com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager`
5. `com.openggf.control.InputHandler`
6. `com.openggf.Engine`
7. `com.openggf.graphics.ScreenshotCapture`
8. `com.openggf.debug.DebugArtViewer`
9. `com.openggf.audio.debug.SoundTestApp` (approved exemption)

Some of these are runtime paths, some are debug-only tools, and some are compatibility shims. All of them are defects while they remain under `src/main/java`, except for the explicitly approved `SoundTestApp` exemption.

### Existing non-AWT text path

`MasterTitleScreen` already uses `PixelFont` with `TexturedQuadRenderer`. That gives the project a production-safe text renderer based on PNG assets and OpenGL texture quads.

## Proposed Architecture

## 1. Shared PixelFont Text Path

Introduce a production-safe text rendering path for simple UI overlays based on `PixelFont`, not on the debug glyph system.

This path should:

1. Accept top-left UI coordinates, matching current editor overlay code.
2. Queue screen-space draw commands through `GraphicsManager`.
3. Support fixed line spacing and color tinting.
4. Support a cheap readability pass, implemented as one or more offset shadow draws rather than AWT outlined glyph generation.

This should live close to `PixelFont`, either as:

1. A small `PixelFontTextRenderer` in `graphics`, or
2. A refactoring of `EditorTextRenderer` that directly owns a lazily initialized `PixelFont`.

Recommendation: create a small reusable `graphics`-level helper rather than embedding `PixelFont` initialization inside editor-only code. The editor is the first consumer, but debug overlays and special-stage debug text will need the same path next.

## 2. EditorTextRenderer Rewrite

`EditorTextRenderer` should keep its current external shape:

1. `TextCommand` remains the intermediate model.
2. Layout methods such as `buildTextCommands(...)` remain intact.
3. Existing toolbar/command-strip/library renderers continue to build commands the same way.

Only the backend changes:

1. Remove `GlyphBatchRenderer` and AWT `Font` dependencies.
2. Replace `topLeftToGlyphY(...)` with top-left to screen-space conversion suitable for the pixel-font path.
3. Render each `TextCommand` through the shared pixel-font helper.
4. Preserve test-visible line-height behavior so existing editor layout assertions stay meaningful.

Because `PixelFont` has fixed glyph metrics, editor line height should become explicit and stable instead of implicitly tied to debug font sizing.

## 3. AWT/Swing Production Guard

Add a scanner test that fails if any file under `src/main/java` imports or references:

1. `java.awt`
2. `javax.swing`

The guard should be simple and explicit, similar in spirit to existing migration-guard tests in the codebase. It should report the violating files, not just fail generically. It should explicitly exclude `com/openggf/audio/debug/SoundTestApp.java`.

This guard becomes the enforcement mechanism for the blacklist. Once merged, new AWT regressions in production sources are blocked automatically.

## 4. Incremental Removal Strategy

After the editor is switched, remove remaining production AWT/Swing usage in dependency order.

### Phase A: unblock the editor and blacklist

1. Add the production guard test.
2. Replace `EditorTextRenderer` with `PixelFont`.
3. Remove the AWT prewarm block from `Engine`.

This is the minimum coherent set for the editor request.

### Phase B: remove runtime debug text AWT

Migrate:

1. `DebugRenderer`
2. `GlyphBatchRenderer`
3. `GlyphAtlas`
4. any helper classes that only exist to support AWT glyph rendering

The intent is not to preserve the exact old glyph implementation. The requirement is production safety, so debug text should move to the same pixel-font helper and the glyph-atlas stack should disappear from production code.

### Phase C: remove compatibility and debug stragglers

Migrate or remove:

1. `InputHandler` AWT keycode conversion helpers
2. `Sonic2SpecialStageManager` debug text font use
3. `DebugArtViewer`
4. `ScreenshotCapture`

Some of these may require behavior decisions:

1. `InputHandler` should become GLFW-only or use engine-owned key constants.
2. `ScreenshotCapture` needs a non-AWT image output path, likely based on STB or another byte-buffer-driven encoder.

These are implementation details for later tasks, but the design direction is fixed: no production AWT/Swing exemptions beyond `SoundTestApp`.

## PixelFont Rendering Details

The editor should use the existing `pixel-font.png` asset already loaded by `MasterTitleScreen`.

### Text appearance

To preserve readability against editor chrome:

1. Draw a black shadow first at `(x + 1, y + 1)`.
2. Draw the tinted foreground text at `(x, y)`.

This gives acceptable contrast without bringing back outlined AWT glyph rasterization.

### Coordinate model

The helper should take top-left UI coordinates and internally convert to the bottom-left OpenGL coordinate system, matching `PixelFont`'s current expectations. `EditorTextRenderer` should no longer contain glyph-system-specific Y conversions.

### Character set

The editor currently uses simple ASCII-like UI strings. `PixelFont` already covers the characters needed for toolbar labels, block/chunk labels, command hints, punctuation, and numbers. If a missing character appears during migration, the correct response is to extend `pixel-font.png` support or normalize the label text, not to fall back to AWT.

## Testing Strategy

### Required tests

1. Update `TestEditorRenderingSmoke` so it continues to validate command layout and no longer assumes the glyph-batch backend.
2. Add a production AWT blacklist scanner test covering `src/main/java`.
3. Add focused tests for the new pixel-font helper if it introduces measurable layout behavior such as width/line-height conversion.

### Verification commands

At minimum, the implementation should verify with:

1. `mvn "-Dtest=TestEditorRenderingSmoke,TestEditorToggleIntegration" -Dmse=off test`
2. The new AWT blacklist guard test
3. Any targeted tests added for the replacement text renderer

## Risks And Trade-Offs

### 1. PixelFont is less flexible than AWT glyph rendering

True, but acceptable. The editor needs deterministic, readable tool UI text, not arbitrary desktop typography.

### 2. Debug text migration may expand scope

Also true. That is why the work should be split into editor-first tasks with the blacklist test making remaining offenders explicit.

### 3. Some current production utilities may become awkward

That is preferable to keeping AWT hidden in production code. `SoundTestApp` is the one accepted desktop-only exception because it is not bundled into the native app. Any additional exception should require an explicit decision instead of being silently tolerated.

## Recommended Task Breakdown

1. Build a reusable non-AWT pixel-font text helper.
2. Switch `EditorTextRenderer` and its smoke tests to that helper.
3. Add the production AWT/Swing blacklist test.
4. Remove `Engine`'s AWT initialization path.
5. Tackle remaining production offenders one subsystem at a time until the blacklist test passes cleanly, with only `SoundTestApp` excluded.

## Acceptance Criteria

The design is satisfied when:

1. `EditorTextRenderer` no longer imports AWT or uses `GlyphBatchRenderer`.
2. Editor overlay text visibly renders through `PixelFont`.
3. `src/main/java` contains no `java.awt` or `javax.swing` usage other than `SoundTestApp`.
4. A guard test prevents reintroduction.
5. Native-image viability is no longer blocked by hidden AWT initialization in production runtime code.
