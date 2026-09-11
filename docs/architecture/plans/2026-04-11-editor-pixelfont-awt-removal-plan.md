# Editor PixelFont And AWT Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove AWT/Swing from production code under `src/main/java` except `SoundTestApp`, switch the editor text path to `PixelFont`, and leave a scanner guard that prevents regressions.

**Architecture:** Introduce one reusable `PixelFont`-backed overlay text helper in `com.openggf.graphics`, then migrate editor, debug, and special-stage overlay text onto that path. Use a temporary scanner test with an explicit `KNOWN_VIOLATIONS` set during the cleanup phase so new production AWT/Swing usage fails immediately while remaining offenders are removed one subsystem at a time; finish by driving that set to empty with only `SoundTestApp` excluded.

**Tech Stack:** Java 21, Maven, JUnit 5, LWJGL/OpenGL, existing `PixelFont` and `TexturedQuadRenderer`, STB image load/write.

---

## File Structure

| File | Responsibility |
|------|----------------|
| `src/main/java/com/openggf/graphics/PixelFont.java` | Expose stable glyph metrics needed by overlay callers |
| `src/main/java/com/openggf/graphics/PixelFontTextRenderer.java` | Shared non-AWT overlay text renderer with shadow pass and deterministic line height |
| `src/main/java/com/openggf/editor/render/EditorTextRenderer.java` | Keep `TextCommand` layout, swap backend from glyph batch to `PixelFontTextRenderer` |
| `src/main/java/com/openggf/Engine.java` | Remove AWT prewarm and outdated glyph-atlas comments |
| `src/main/java/com/openggf/debug/DebugRenderer.java` | Replace glyph-batch text drawing with `PixelFontTextRenderer` |
| `src/main/java/com/openggf/debug/PerformancePanelRenderer.java` | Replace glyph-batch panel text with `PixelFontTextRenderer` while keeping GL chart rendering |
| `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java` | Replace alignment/lag overlays with `PixelFontTextRenderer` |
| `src/main/java/com/openggf/control/InputHandler.java` | Remove `java.awt.event.KeyEvent` conversion helpers entirely |
| `src/main/java/com/openggf/configuration/ConfigMigrationService.java` | Migrate legacy AWT keycode configs without depending on `InputHandler` or AWT |
| `src/main/java/com/openggf/configuration/LegacyAwtKeyCodeMapper.java` | Numeric legacy-key migration table with no `java.awt` imports |
| `src/main/java/com/openggf/graphics/RgbaImage.java` | Small production-safe image container replacing `BufferedImage` in production utilities |
| `src/main/java/com/openggf/graphics/ScreenshotCapture.java` | STB-backed capture/load/save/diff logic using `RgbaImage` |
| `src/main/java/com/openggf/debug/DebugArtViewer.java` | PNG dump utility rewritten to use `RgbaImage` + `ScreenshotCapture.savePNG(...)` |
| `src/main/java/com/openggf/debug/GlyphBatchRenderer.java` | Delete after all production callers are migrated |
| `src/main/java/com/openggf/debug/GlyphAtlas.java` | Delete after all production callers are migrated |
| `src/test/java/com/openggf/graphics/TestPixelFontTextRenderer.java` | Helper-level layout and shadow-pass tests |
| `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java` | Editor text layout tests updated for the new backend |
| `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java` | Production AWT/Swing guard with `KNOWN_VIOLATIONS` shrink-to-zero enforcement |
| `src/test/java/com/openggf/configuration/TestConfigMigrationService.java` | Legacy keycode migration tests after `InputHandler` cleanup |
| `src/test/java/com/openggf/graphics/TestRgbaImageIO.java` | `RgbaImage` load/save/diff tests |
| `src/test/java/com/openggf/graphics/VisualRegressionTest.java` | Consume the `RgbaImage` production API for screenshot comparisons |
| `src/test/java/com/openggf/graphics/VisualReferenceGenerator.java` | Consume the `RgbaImage` production API for reference capture output |

## Current Audit

Current production AWT/Swing offenders confirmed under `src/main/java`:

- `com/openggf/editor/render/EditorTextRenderer.java`
- `com/openggf/Engine.java`
- `com/openggf/debug/DebugRenderer.java`
- `com/openggf/debug/GlyphBatchRenderer.java`
- `com/openggf/debug/GlyphAtlas.java`
- `com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- `com/openggf/control/InputHandler.java`
- `com/openggf/graphics/ScreenshotCapture.java`
- `com/openggf/debug/DebugArtViewer.java`
- `com/openggf/audio/debug/SoundTestApp.java` (permitted permanent exemption)

Do not touch unrelated dirty files already present in the worktree while executing this plan.

## Task 1: Introduce the Shared PixelFont Overlay Text Helper

**Files:**
- Create: `src/main/java/com/openggf/graphics/PixelFontTextRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/PixelFont.java`
- Create: `src/test/java/com/openggf/graphics/TestPixelFontTextRenderer.java`

- [ ] **Step 1: Write the failing helper tests**

```java
package com.openggf.graphics;

import com.openggf.debug.DebugColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPixelFontTextRenderer {

    @Test
    void lineHeight_matchesPixelFontMetricsPlusShadowPadding() {
        PixelFontTextRenderer renderer = new PixelFontTextRenderer();

        assertEquals(PixelFont.glyphHeight() + 2, renderer.lineHeight());
    }

    @Test
    void drawShadowedText_emitsShadowThenForeground() {
        RecordingPixelFontTextRenderer renderer = new RecordingPixelFontTextRenderer();

        renderer.drawShadowedText("EDIT", 8, 12, DebugColor.YELLOW);

        assertEquals(List.of(
                "EDIT@9,13#0,0,0,255",
                "EDIT@8,12#255,255,0,255"
        ), renderer.calls);
    }

    private static final class RecordingPixelFontTextRenderer extends PixelFontTextRenderer {
        private final List<String> calls = new ArrayList<>();

        @Override
        protected void drawRawText(String text, int x, int y, DebugColor color) {
            calls.add(text + "@" + x + "," + y + "#"
                    + color.red() + "," + color.green() + "," + color.blue() + "," + color.alpha());
        }
    }
}
```

- [ ] **Step 2: Run the helper tests to verify RED**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.graphics.TestPixelFontTextRenderer" test
```

Expected: FAIL because `PixelFontTextRenderer`, `PixelFont.glyphHeight()`, and the override hook do not exist yet.

- [ ] **Step 3: Implement the reusable non-AWT helper**

```java
package com.openggf.graphics;

import com.openggf.debug.DebugColor;

import java.io.IOException;

public class PixelFontTextRenderer {
    private static final int SHADOW_OFFSET = 1;

    private final PixelFont font = new PixelFont();
    private TexturedQuadRenderer quadRenderer;
    private boolean initialized;

    public int lineHeight() {
        return PixelFont.glyphHeight() + 2;
    }

    public int measureWidth(String text) {
        ensureInitialized();
        return font.measureWidth(text);
    }

    public void drawShadowedText(String text, int x, int y, DebugColor color) {
        drawRawText(text, x + SHADOW_OFFSET, y + SHADOW_OFFSET, DebugColor.BLACK);
        drawRawText(text, x, y, color);
    }

    protected void drawRawText(String text, int x, int y, DebugColor color) {
        ensureInitialized();
        font.drawText(text, x, y,
                color.red() / 255f,
                color.green() / 255f,
                color.blue() / 255f,
                color.alpha() / 255f);
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        try {
            quadRenderer = new TexturedQuadRenderer();
            quadRenderer.init();
            font.init("pixel-font.png", quadRenderer);
            initialized = true;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize PixelFontTextRenderer", e);
        }
    }
}
```

```java
public class PixelFont {
    public static int glyphWidth() {
        return GLYPH_W;
    }

    public static int glyphHeight() {
        return GLYPH_H;
    }
}
```

- [ ] **Step 4: Run the helper tests to verify GREEN**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.graphics.TestPixelFontTextRenderer" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/PixelFont.java src/main/java/com/openggf/graphics/PixelFontTextRenderer.java src/test/java/com/openggf/graphics/TestPixelFontTextRenderer.java
git commit -m "feat: add PixelFont overlay text renderer"
```

## Task 2: Switch EditorTextRenderer to PixelFont

**Files:**
- Modify: `src/main/java/com/openggf/editor/render/EditorTextRenderer.java`
- Modify: `src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java`

- [ ] **Step 1: Update the editor smoke tests to describe the new backend behavior**

Replace the glyph-conversion assertion with a top-left line-height assertion and keep the queueing assertion:

```java
@Test
void textRenderer_usesStableTopLeftLineSpacing() {
    InspectableTextRenderer renderer = new InspectableTextRenderer();

    List<EditorTextRenderer.TextCommand> commands = renderer.buildCommands(List.of("One", "Two"), 8, 12);

    assertEquals(12, commands.get(0).y());
    assertEquals(22, commands.get(1).y());
    assertEquals(10, commands.get(0).lineHeight());
}
```

Delete the `convertTopLeftY(...)` probe from `InspectableTextRenderer`; the test should no longer know about glyph-batch Y transforms.

- [ ] **Step 2: Run the editor smoke test to verify RED**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorRenderingSmoke" test
```

Expected: FAIL until `EditorTextRenderer` stops exposing the glyph-specific helper and renders through the new text path.

- [ ] **Step 3: Rewrite `EditorTextRenderer` to use the shared helper**

```java
package com.openggf.editor.render;

import com.openggf.debug.DebugColor;
import com.openggf.debug.FontSize;
import com.openggf.game.GameServices;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PixelFontTextRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class EditorTextRenderer {
    public record TextCommand(String text, int x, int y, int lineHeight, DebugColor color, FontSize fontSize) {}

    private static final int DEFAULT_LINE_HEIGHT = 10;
    private static final DebugColor DEFAULT_COLOR = DebugColor.WHITE;
    private static final FontSize DEFAULT_FONT_SIZE = FontSize.SMALL;

    private final GraphicsManager graphicsManager;
    private final PixelFontTextRenderer textRenderer;

    public EditorTextRenderer() {
        this(GameServices.graphics(), new PixelFontTextRenderer());
    }

    public EditorTextRenderer(GraphicsManager graphicsManager) {
        this(graphicsManager, new PixelFontTextRenderer());
    }

    public EditorTextRenderer(GraphicsManager graphicsManager, PixelFontTextRenderer textRenderer) {
        this.graphicsManager = Objects.requireNonNull(graphicsManager, "graphicsManager");
        this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
    }

    protected GLCommandable buildTextBatchCommand(List<TextCommand> commands) {
        return (cameraX, cameraY, cameraWidth, cameraHeight) -> {
            for (TextCommand command : commands) {
                textRenderer.drawShadowedText(command.text(), command.x(), command.y(), command.color());
            }
        };
    }
}
```

Do not carry over `GlyphBatchRenderer`, `Font`, `initializationAttempted`, or `topLeftToGlyphY(...)`.

- [ ] **Step 4: Run the editor smoke suite to verify GREEN**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.editor.TestEditorRenderingSmoke,com.openggf.graphics.TestPixelFontTextRenderer" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/editor/render/EditorTextRenderer.java src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java
git commit -m "refactor: move editor text to PixelFont"
```

## Task 3: Add the Production AWT Guard and Remove Engine Prewarm

**Files:**
- Create: `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java`
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Add the temporary shrink-to-zero scanner guard**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class TestProductionAwtBlacklistGuard {
    private static final Set<String> EXEMPT_FILES = Set.of(
            "com/openggf/audio/debug/SoundTestApp.java"
    );

    private static final Set<String> KNOWN_VIOLATIONS = Set.of(
            "com/openggf/control/InputHandler.java",
            "com/openggf/debug/DebugArtViewer.java",
            "com/openggf/debug/DebugRenderer.java",
            "com/openggf/debug/GlyphAtlas.java",
            "com/openggf/debug/GlyphBatchRenderer.java",
            "com/openggf/graphics/ScreenshotCapture.java",
            "com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java"
    );

    @Test
    void productionSources_doNotRegressOnAwtOrSwingUsage() throws IOException {
        Path srcMain = Path.of("src/main/java");
        TreeSet<String> actualViolations = new TreeSet<>();

        try (Stream<Path> files = Files.walk(srcMain)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> {
                        try {
                            String rel = srcMain.relativize(path).toString().replace('\\', '/');
                            if (EXEMPT_FILES.contains(rel)) {
                                return;
                            }
                            String content = Files.readString(path);
                            if (content.contains("java.awt") || content.contains("javax.swing")) {
                                actualViolations.add(rel);
                            }
                        } catch (IOException ignored) {
                        }
                    });
        }

        TreeSet<String> regressions = new TreeSet<>(actualViolations);
        regressions.removeAll(KNOWN_VIOLATIONS);

        TreeSet<String> migrated = new TreeSet<>(KNOWN_VIOLATIONS);
        migrated.removeAll(actualViolations);

        if (!regressions.isEmpty() || !migrated.isEmpty()) {
            fail("AWT/Swing guard mismatch\n"
                    + "Regressions: " + regressions + "\n"
                    + "Migrated: " + migrated + "\n"
                    + "Actual: " + actualViolations);
        }
    }
}
```

This goes in as a passable guard immediately: new offenders fail, and cleaned files must be removed from `KNOWN_VIOLATIONS`.

- [ ] **Step 2: Run the new guard and confirm the current list is accurate**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionAwtBlacklistGuard" test
```

Expected: PASS with the current seven non-exempt violators after `EditorTextRenderer` is already cleaned up.

- [ ] **Step 3: Remove the AWT prewarm block from `Engine`**

Delete the eager AWT initialization in `init()` and collapse the stale comments:

```java
// Debug overlay uses production-safe overlay renderers; native-image no longer needs an AWT prewarm path.
if (isNativeImage()) {
    debugViewEnabled = false;
}
```

Delete these statements entirely:

```java
java.awt.Toolkit.getDefaultToolkit();
var img = new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_BYTE_GRAY);
```

- [ ] **Step 4: Re-run the guard and the editor smoke suite**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionAwtBlacklistGuard,com.openggf.editor.TestEditorRenderingSmoke" test
```

Expected: PASS; `Engine.java` should not appear in the guard.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/Engine.java src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java
git commit -m "test: add production AWT blacklist guard"
```

## Task 4: Remove the Glyph Stack From Debug Rendering

**Files:**
- Modify: `src/main/java/com/openggf/debug/DebugRenderer.java`
- Modify: `src/main/java/com/openggf/debug/PerformancePanelRenderer.java`
- Delete: `src/main/java/com/openggf/debug/GlyphBatchRenderer.java`
- Delete: `src/main/java/com/openggf/debug/GlyphAtlas.java`
- Modify: `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java`

- [ ] **Step 1: Swap debug text callers to `PixelFontTextRenderer`**

Use one shared helper per renderer instead of glyph-batch state:

```java
private final PixelFontTextRenderer textRenderer = new PixelFontTextRenderer();

private static final int SENSOR_LINE_HEIGHT = PixelFont.glyphHeight() + 2;

textRenderer.drawShadowedText(label, screenX + offsetX, screenY + offsetY, sensorColor);
textRenderer.drawShadowedText(line, startX, y, DebugColor.WHITE);
```

For `PerformancePanelRenderer`, keep the VAO/VBO chart rendering and replace only the text drawing:

```java
private final PixelFontTextRenderer textRenderer;

public PerformancePanelRenderer(int baseWidth, int baseHeight,
                                PixelFontTextRenderer textRenderer,
                                GraphicsManager graphicsManager,
                                PerformanceProfiler profiler) {
    this.textRenderer = Objects.requireNonNull(textRenderer, "textRenderer");
}
```

- [ ] **Step 2: Delete the AWT glyph implementation files**

Delete:

```text
src/main/java/com/openggf/debug/GlyphBatchRenderer.java
src/main/java/com/openggf/debug/GlyphAtlas.java
```

No production file should import either class after this step.

- [ ] **Step 3: Shrink the guard allowlist and verify the debug surface is clean**

Remove these entries from `KNOWN_VIOLATIONS`:

```java
"com/openggf/debug/DebugRenderer.java",
"com/openggf/debug/GlyphAtlas.java",
"com/openggf/debug/GlyphBatchRenderer.java",
```

Run:

```powershell
rg -n "GlyphBatchRenderer|GlyphAtlas|java\\.awt|javax\\.swing" src/main/java/com/openggf/debug
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionAwtBlacklistGuard" test
```

Expected:

- `rg` only reports `SoundTestApp` outside the debug package
- the guard passes with the debug entries removed from `KNOWN_VIOLATIONS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/debug/DebugRenderer.java src/main/java/com/openggf/debug/PerformancePanelRenderer.java src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java
git rm src/main/java/com/openggf/debug/GlyphBatchRenderer.java src/main/java/com/openggf/debug/GlyphAtlas.java
git commit -m "refactor: remove AWT glyph debug renderer"
```

## Task 5: Migrate Sonic 2 Special Stage Overlay Text

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java`
- Modify: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java`

- [ ] **Step 1: Add a render smoke test for the overlay methods**

```java
@Test
public void testOverlayRenderMethodsDoNotRequireAwt() {
    Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager();

    assertDoesNotThrow(() -> manager.renderLagCompensationOverlay(256, 224));
}
```

If needed, add a package-visible helper to enable `alignmentTestMode` before calling `renderAlignmentOverlay(...)`; keep the test focused on “does not throw” rather than visual assertions.

- [ ] **Step 2: Run the special-stage test to verify RED or current baseline**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest" test
```

Expected: existing baseline passes or a targeted failure appears once the new smoke case is added; either way, keep the test in place before the renderer swap.

- [ ] **Step 3: Replace both overlay renderers with `PixelFontTextRenderer`**

```java
private PixelFontTextRenderer alignmentTextRenderer;
private PixelFontTextRenderer lagCompensationTextRenderer;

if (alignmentTextRenderer == null) {
    alignmentTextRenderer = new PixelFontTextRenderer();
}

alignmentTextRenderer.drawShadowedText("SS ALIGNMENT TEST (F4 to exit)", 8, 8, DebugColor.WHITE);
alignmentTextRenderer.drawShadowedText(
        String.format("Lag: %.0f%% (~%.0f upd/s)  F6/F7", lagCompensation * 100, effectiveUpdates),
        8, 210, DebugColor.YELLOW);
```

Use top-left game-space Y positions instead of glyph-batch viewport coordinates.

- [ ] **Step 4: Shrink the guard allowlist and rerun tests**

Remove this entry from `KNOWN_VIOLATIONS`:

```java
"com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java",
```

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.game.TestProductionAwtBlacklistGuard,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest,com.openggf.game.sonic2.TestSonic2SpecialStageModuleGraph" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java
git commit -m "refactor: move special stage overlays to PixelFont"
```

## Task 6: Remove AWT Key Conversion From Input Handling

**Files:**
- Create: `src/main/java/com/openggf/configuration/LegacyAwtKeyCodeMapper.java`
- Modify: `src/main/java/com/openggf/configuration/ConfigMigrationService.java`
- Modify: `src/main/java/com/openggf/control/InputHandler.java`
- Create: `src/test/java/com/openggf/configuration/TestConfigMigrationService.java`
- Modify: `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java`

- [ ] **Step 1: Add a focused config migration test**

```java
package com.openggf.configuration;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestConfigMigrationService {

    @Test
    void migrateConfig_convertsLegacyAwtArrowAndActionKeys() {
        Map<String, Object> config = new HashMap<>();
        config.put(SonicConfiguration.UP.name(), 38);
        config.put(SonicConfiguration.DOWN.name(), 40);
        config.put(SonicConfiguration.LEFT.name(), 37);
        config.put(SonicConfiguration.RIGHT.name(), 39);
        config.put(SonicConfiguration.JUMP.name(), 32);

        ConfigMigrationService service = new ConfigMigrationService();

        assertTrue(service.detectAwtKeyCodes(config));
        service.migrateConfig(config);

        assertEquals(265, config.get(SonicConfiguration.UP.name()));
        assertEquals(264, config.get(SonicConfiguration.DOWN.name()));
        assertEquals(263, config.get(SonicConfiguration.LEFT.name()));
        assertEquals(262, config.get(SonicConfiguration.RIGHT.name()));
        assertEquals(32, config.get(SonicConfiguration.JUMP.name()));
    }
}
```

- [ ] **Step 2: Run the config migration test to verify RED**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.configuration.TestConfigMigrationService" test
```

Expected: FAIL because `ConfigMigrationService` still routes through `InputHandler.awtToGlfw(...)` and no standalone mapper exists.

- [ ] **Step 3: Move the legacy mapping table out of `InputHandler`**

```java
package com.openggf.configuration;

import static org.lwjgl.glfw.GLFW.*;

final class LegacyAwtKeyCodeMapper {
    private LegacyAwtKeyCodeMapper() {
    }

    static int toGlfw(int legacyCode) {
        return switch (legacyCode) {
            case 37 -> GLFW_KEY_LEFT;
            case 38 -> GLFW_KEY_UP;
            case 39 -> GLFW_KEY_RIGHT;
            case 40 -> GLFW_KEY_DOWN;
            case 32 -> GLFW_KEY_SPACE;
            case 10 -> GLFW_KEY_ENTER;
            default -> legacyCode;
        };
    }
}
```

```java
public void migrateConfig(Map<String, Object> config) {
    for (SonicConfiguration keyConfig : KEY_CONFIGS) {
        Integer legacyCode = getIntValue(config, keyConfig.name());
        if (legacyCode != null) {
            int glfwCode = LegacyAwtKeyCodeMapper.toGlfw(legacyCode);
            if (glfwCode != legacyCode) {
                config.put(keyConfig.name(), glfwCode);
            }
        }
    }
}
```

Delete both `glfwToAwt(...)` and `awtToGlfw(...)` from `InputHandler`.

- [ ] **Step 4: Shrink the guard allowlist and rerun tests**

Remove this entry from `KNOWN_VIOLATIONS`:

```java
"com/openggf/control/InputHandler.java",
```

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.configuration.TestConfigMigrationService,com.openggf.game.TestProductionAwtBlacklistGuard" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/configuration/LegacyAwtKeyCodeMapper.java src/main/java/com/openggf/configuration/ConfigMigrationService.java src/main/java/com/openggf/control/InputHandler.java src/test/java/com/openggf/configuration/TestConfigMigrationService.java src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java
git commit -m "refactor: remove AWT key conversion helpers"
```

## Task 7: Replace Production BufferedImage Utilities

**Files:**
- Create: `src/main/java/com/openggf/graphics/RgbaImage.java`
- Modify: `src/main/java/com/openggf/graphics/ScreenshotCapture.java`
- Modify: `src/main/java/com/openggf/debug/DebugArtViewer.java`
- Create: `src/test/java/com/openggf/graphics/TestRgbaImageIO.java`
- Modify: `src/test/java/com/openggf/graphics/VisualRegressionTest.java`
- Modify: `src/test/java/com/openggf/graphics/VisualReferenceGenerator.java`
- Modify: `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java`

- [ ] **Step 1: Add a narrow `RgbaImage` I/O test**

```java
package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRgbaImageIO {

    @Test
    void saveAndLoadPng_roundTripsPixels() throws Exception {
        RgbaImage image = new RgbaImage(2, 1, new int[] {
                0xFFFF0000,
                0xFF00FF00
        });
        Path png = Files.createTempFile("rgba-image", ".png");

        ScreenshotCapture.savePNG(image, png);
        RgbaImage loaded = ScreenshotCapture.loadPNG(png);

        assertEquals(2, loaded.width());
        assertEquals(1, loaded.height());
        assertEquals(0xFFFF0000, loaded.argb(0, 0));
        assertEquals(0xFF00FF00, loaded.argb(1, 0));
        assertTrue(ScreenshotCapture.imagesMatch(image, loaded, 0).matched());
    }
}
```

- [ ] **Step 2: Run the image test to verify RED**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.graphics.TestRgbaImageIO" test
```

Expected: FAIL because `RgbaImage` and the non-AWT `ScreenshotCapture` API do not exist yet.

- [ ] **Step 3: Replace `BufferedImage` with a production-safe image type**

```java
package com.openggf.graphics;

public record RgbaImage(int width, int height, int[] pixels) {
    public int argb(int x, int y) {
        return pixels[y * width + x];
    }

    public void setArgb(int x, int y, int argb) {
        pixels[y * width + x] = argb;
    }
}
```

```java
public static RgbaImage captureFramebuffer(int width, int height) { ... }
public static ComparisonResult imagesMatch(RgbaImage reference, RgbaImage current, int tolerance) { ... }
public static void savePNG(RgbaImage image, Path path) throws IOException { ... }
public static RgbaImage loadPNG(Path path) throws IOException { ... }
public static RgbaImage createDiffImage(RgbaImage reference, RgbaImage current, int tolerance) { ... }
```

```java
public static void dumpPatterns(Pattern[] patterns, String filename) {
    RgbaImage image = new RgbaImage(tilesPerRow * 8, rows * 8, new int[tilesPerRow * 8 * rows * 8]);
    image.setArgb(tileX + x, tileY + y, rgb);
    ScreenshotCapture.savePNG(image, Path.of(filename));
}
```

Update the test-side visual utilities to consume `RgbaImage` directly instead of `BufferedImage`.

- [ ] **Step 4: Shrink the guard allowlist and rerun tests**

Remove these entries from `KNOWN_VIOLATIONS`:

```java
"com/openggf/graphics/ScreenshotCapture.java",
"com/openggf/debug/DebugArtViewer.java",
```

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.graphics.TestRgbaImageIO,com.openggf.game.TestProductionAwtBlacklistGuard" test
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/RgbaImage.java src/main/java/com/openggf/graphics/ScreenshotCapture.java src/main/java/com/openggf/debug/DebugArtViewer.java src/test/java/com/openggf/graphics/TestRgbaImageIO.java src/test/java/com/openggf/graphics/VisualRegressionTest.java src/test/java/com/openggf/graphics/VisualReferenceGenerator.java src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java
git commit -m "refactor: remove BufferedImage from production screenshot tools"
```

## Task 8: Drive the Guard to Final State and Verify the Full Cleanup

**Files:**
- Modify: `src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java`

- [ ] **Step 1: Empty the temporary known-violations set**

After Tasks 4 through 7, the file should end in this state:

```java
private static final Set<String> EXEMPT_FILES = Set.of(
        "com/openggf/audio/debug/SoundTestApp.java"
);

private static final Set<String> KNOWN_VIOLATIONS = Set.of();
```

Do not add any second exemption list.

- [ ] **Step 2: Run the focused cleanup verification bundle**

Run:

```powershell
mvn -q -Dmse=off "-Dtest=com.openggf.graphics.TestPixelFontTextRenderer,com.openggf.editor.TestEditorRenderingSmoke,com.openggf.configuration.TestConfigMigrationService,com.openggf.graphics.TestRgbaImageIO,com.openggf.game.TestProductionAwtBlacklistGuard,com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManagerTest,com.openggf.game.sonic2.TestSonic2SpecialStageModuleGraph" test
```

Expected: PASS.

- [ ] **Step 3: Run source scans for the final acceptance criteria**

Run:

```powershell
rg -n "java\\.awt|javax\\.swing" src/main/java
rg -n "GlyphBatchRenderer|GlyphAtlas" src/main/java
git diff --check
git status --short
```

Expected:

- first scan reports only `src/main/java/com/openggf/audio/debug/SoundTestApp.java`
- second scan reports no output
- `git diff --check` reports no whitespace issues
- `git status --short` shows only the intended files for this cleanup

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/openggf/game/TestProductionAwtBlacklistGuard.java
git commit -m "test: finalize production AWT blacklist"
```
