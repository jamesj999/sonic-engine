package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestDisplayShaderPresetLoader {

    @TempDir
    Path tempDir;

    @Test
    public void offLoadsAsEmptyPassthroughPreset() throws Exception {
        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(DisplayShaderPresetRef.OFF,
                ShaderPhase.FINAL);

        assertEquals("Off", loaded.label());
        assertEquals(ShaderPhase.FINAL, loaded.phase());
        assertEquals(0, loaded.passes().size());
    }

    @Test
    public void loadsBizHawkCgpByMappingCgReferenceToGlslSibling() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("BizHawk/BizScanlines.cgp");
        write(preset, """
                shaders = 1
                shader0 = BizScanlines.cg
                """);
        write(root.resolve("BizHawk/BizScanlines.glsl"), "void main() {}\n");

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                ShaderPhase.PRESENTATION);

        assertEquals("BizHawk/BizScanlines", loaded.label());
        assertEquals(1, loaded.passes().size());
        assertTrue(loaded.passes().get(0).fragmentSource().contains("void main()"));
    }

    @Test
    public void cgpWithoutGlslSiblingFailsGracefully() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("BizHawk/BizScanlines.cgp");
        write(preset, """
                shaders = 1
                shader0 = BizScanlines.cg
                """);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("GLSL"));
    }

    @Test
    public void hq2xCgpDefaultsToScenePhase() throws Exception {
        assertScalerCgpDefaultsToScene("BizHawk/hq2x/hq2x.cgp", "hq2x.cg", "hq2x.glsl");
    }

    @Test
    public void hq4xCgpDefaultsToScenePhase() throws Exception {
        assertScalerCgpDefaultsToScene("BizHawk/hq4x/hq4x.cgp", "hq4x.cg", "hq4x.glsl");
    }

    @Test
    public void scalefxCgpDefaultsToScenePhase() throws Exception {
        assertScalerCgpDefaultsToScene("RetroArch/shaders_glsl/scalefx/scalefx.glslp", "scalefx.glsl", "scalefx.glsl");
    }

    @Test
    public void omniscaleCgpDefaultsToScenePhase() throws Exception {
        assertScalerCgpDefaultsToScene("RetroArch/shaders_glsl/omniscale/omniscale.glslp", "omniscale.glsl", "omniscale.glsl");
    }

    @Test
    public void xbrzCgpDefaultsToScenePhase() throws Exception {
        assertScalerCgpDefaultsToScene("RetroArch/shaders_glsl/xbrz/xbrz.glslp", "xbrz.glsl", "xbrz.glsl");
    }

    private void assertScalerCgpDefaultsToScene(String presetRelativePath, String shaderRef, String sourceFileName)
            throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve(presetRelativePath);
        write(preset, """
                shaders = 1
                shader0 = %s
                """.formatted(shaderRef));
        write(preset.getParent().resolve(sourceFileName), "void main() {}\n");
        DisplayShaderPresetRef.Kind kind = presetRelativePath.endsWith(".cgp")
                ? DisplayShaderPresetRef.Kind.CGP
                : DisplayShaderPresetRef.Kind.GLSLP;

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, kind),
                ShaderPhase.PRESENTATION);

        assertEquals(ShaderPhase.SCENE, loaded.phase());
    }

    @Test
    public void loadsGlslpRelativeShaderSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/scanlines/scanline.glslp");
        write(preset, """
                shaders = 1
                shader0 = shaders/scanline.glsl
                """);
        write(root.resolve("RetroArch/shaders_glsl/scanlines/shaders/scanline.glsl"), "void main() {}\n");

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.FINAL);

        assertEquals(ShaderPhase.FINAL, loaded.phase());
        assertEquals("void main() {}\n", loaded.passes().get(0).fragmentSource());
    }

    @Test
    public void glslpAcceptsDecimalFormattedIntegerScale() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/aa/scale.glslp");
        write(preset, """
                shaders = 1
                shader0 = pass.glsl
                scale_type0 = source
                scale0 = 2.0
                """);
        write(root.resolve("RetroArch/shaders_glsl/aa/pass.glsl"), "void main() {}\n");

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(
                ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        assertEquals(2, loaded.passes().get(0).scale());
    }

    @Test
    public void glslpAcceptsFractionalScale() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/fractional-scale.glslp");
        write(preset, """
                shaders = 1
                shader0 = pass.glsl
                scale_type0 = source
                scale0 = 0.25
                """);
        write(root.resolve("RetroArch/shaders_glsl/crt/pass.glsl"), "void main() {}\n");

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(
                ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        assertEquals(0.25, loaded.passes().get(0).scale());
    }

    @Test
    public void glslpAcceptsLegacySingleByteEncodedShaderSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/aa/legacy-encoding.glslp");
        write(preset, """
                shaders = 1
                shader0 = pass.glsl
                """);
        byte[] latin1Source = "/* Copyright \u00a9 */\nvoid main() {}\n".getBytes(StandardCharsets.ISO_8859_1);
        Files.createDirectories(preset.getParent());
        Files.write(root.resolve("RetroArch/shaders_glsl/aa/pass.glsl"), latin1Source);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(
                ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        assertTrue(loaded.passes().get(0).fragmentSource().contains("void main()"));
    }

    @Test
    public void glslpCarriesPragmaParameterDefaultsAndPresetOverrides() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/gizmo.glslp");
        write(preset, """
                shaders = 1
                shader0 = gizmo.glsl
                HORIZONTAL_BLUR = "1.0"
                """);
        write(root.resolve("RetroArch/shaders_glsl/crt/gizmo.glsl"), """
                #pragma parameter BRIGHTNESS "Scanline Intensity" 0.5 0.05 1.0 0.05
                #pragma parameter HORIZONTAL_BLUR "Horizontal Blur" 0.0 0.0 1.0 1.0
                #if defined(FRAGMENT)
                #ifdef PARAMETER_UNIFORM
                uniform float BRIGHTNESS;
                uniform float HORIZONTAL_BLUR;
                #endif
                void main() {}
                #endif
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(
                ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        assertEquals(0.5f, loaded.passes().get(0).parameterValues().get("BRIGHTNESS"));
        assertEquals(1.0f, loaded.passes().get(0).parameterValues().get("HORIZONTAL_BLUR"));
    }

    @Test
    public void glslpRejectsShaderPathEscapingLibraryRoot() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/escape.glslp");
        write(preset, """
                shaders = 1
                shader0 = ../../../../outside.glsl
                """);
        write(tempDir.resolve("outside.glsl"), "void main() {}\n");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("../../../../outside.glsl"));
    }

    @Test
    public void glslpRejectsRootedOrDriveQualifiedShaderRefs() throws Exception {
        assertShaderRefRejected("/evil.glsl");
        if (isWindows()) {
            assertShaderRefRejected("D:evil.glsl");
            assertShaderRefRejected("C:\\abs\\evil.glsl");
        }
    }

    @Test
    public void glslpRejectsSymlinkPassSourceEscapingLibraryRoot() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/symlink.glslp");
        Path link = root.resolve("RetroArch/shaders_glsl/crt/linked.glsl");
        write(preset, """
                shaders = 1
                shader0 = linked.glsl
                """);
        write(tempDir.resolve("outside.glsl"), "void main() {}\n");
        Files.createDirectories(link.getParent());
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(link, tempDir.resolve("outside.glsl"));
            symlinkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Symlink creation can require privileges on Windows or be disabled on some filesystems.
        }
        assumeTrue(symlinkCreated, "Symlink creation is unavailable in this environment");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("symbolic link"));
    }

    @Test
    public void glslpRejectsSymlinkDirectoryPassSourceEscapingLibraryRoot() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/symlink-dir.glslp");
        Path linkDir = root.resolve("RetroArch/shaders_glsl/crt/linked-dir");
        Path outsideDir = tempDir.resolve("outside-dir");
        write(preset, """
                shaders = 1
                shader0 = linked-dir/pass.glsl
                """);
        write(outsideDir.resolve("pass.glsl"), "void main() {}\n");
        Files.createDirectories(linkDir.getParent());
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(linkDir, outsideDir);
            symlinkCreated = true;
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            // Symlink creation can require privileges on Windows or be disabled on some filesystems.
        }
        assumeTrue(symlinkCreated, "Symlink creation is unavailable in this environment");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("escapes"));
    }

    @Test
    public void cgpRejectsCgFallbackPathEscapingLibraryRoot() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("BizHawk/nested/escape.cgp");
        write(preset, """
                shaders = 1
                shader0 = ../../../outside.cg
                """);
        write(tempDir.resolve("outside.glsl"), "void main() {}\n");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("../../../outside.cg"));
    }

    @Test
    public void manualPresetRefFallsBackToPresetDirectoryContainment() throws Exception {
        Path preset = tempDir.resolve("manual/manual.glslp");
        write(preset, """
                shaders = 1
                shader0 = ../outside.glsl
                """);
        write(tempDir.resolve("outside.glsl"), "void main() {}\n");
        DisplayShaderPresetRef manualRef = new DisplayShaderPresetRef(DisplayShaderPresetRef.Kind.GLSLP,
                "display/name.glslp", preset);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(manualRef, ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("../outside.glsl"));
    }

    @Test
    public void invalidShaderPathFailsAsLoadException() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/invalid.glslp");
        write(preset, """
                shaders = 1
                shader0 = bad\0path.glsl
                """);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("bad"));
    }

    private void assertShaderRefRejected(String shaderRef) throws Exception {
        Path root = tempDir.resolve("display-shaders-" + Integer.toUnsignedString(shaderRef.hashCode()));
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/rooted.glslp");
        write(preset, """
                shaders = 1
                shader0 = %s
                """.formatted(shaderRef));

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains(shaderRef));
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    public void glslpRejectsUnsupportedSlangReference() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_slang/crt/crt.glslp");
        write(preset, """
                shaders = 1
                shader0 = crt.slang
                """);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains(".glsl"));
    }

    @Test
    public void glslpRejectsReferenceDirectiveInheritance() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/child.glslp");
        write(preset, """
                #reference "../base.glslp"
                shaders = 1
                shader0 = pass.glsl
                """);
        write(root.resolve("RetroArch/shaders_glsl/crt/pass.glsl"), "void main() {}\n");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("inheritance"));
    }

    @Test
    public void cgpRejectsReferenceAssignmentInheritance() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("BizHawk/child.cgp");
        write(preset, """
                reference = base.cgp
                shaders = 1
                shader0 = child.cg
                """);
        write(root.resolve("BizHawk/child.glsl"), "void main() {}\n");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("unsupported"));
    }

    @Test
    public void cgpRejectsReferenceDirectiveInheritance() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("BizHawk/child.cgp");
        write(preset, """
                #reference "base.cgp"
                shaders = 1
                shader0 = child.cg
                """);
        write(root.resolve("BizHawk/child.glsl"), "void main() {}\n");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("inheritance"));
    }

    @Test
    public void cgpRejectsIncludeDirective() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("BizHawk/child.cgp");
        write(preset, """
                #include "base.cgp"
                shaders = 1
                shader0 = child.cg
                """);
        write(root.resolve("BizHawk/child.glsl"), "void main() {}\n");

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("includes"));
    }

    @Test
    public void glslpRejectsHistoryRuntimeInputInPassSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/history.glslp");
        write(preset, """
                shaders = 1
                shader0 = history.glsl
                """);
        write(root.resolve("RetroArch/shaders_glsl/crt/history.glsl"), """
                #pragma parameter glow "Glow" 0.5 0.0 1.0 0.1
                uniform sampler2D OriginalHistory0;
                void main() {}
                """);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("runtime input"));
    }

    @Test
    public void glslpRejectsLutSamplerInPassSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path preset = root.resolve("RetroArch/shaders_glsl/crt/lut.glslp");
        write(preset, """
                shaders = 1
                shader0 = lut.glsl
                """);
        write(root.resolve("RetroArch/shaders_glsl/crt/lut.glsl"), """
                uniform sampler2D LUT;
                void main() {}
                """);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("external texture"));
    }

    private static DisplayShaderPresetRef ref(Path root, Path path, DisplayShaderPresetRef.Kind kind) {
        return new DisplayShaderPresetRef(kind, root.relativize(path).toString().replace('\\', '/'), path);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
