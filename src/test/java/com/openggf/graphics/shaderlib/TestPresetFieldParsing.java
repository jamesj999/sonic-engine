package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestPresetFieldParsing {

    @TempDir
    Path tempDir;

    @Test
    public void parsesCoreCgpFields() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("crt/pass0.glsl"), "void main() {}\n");
        write(root.resolve("crt/pass1.glsl"), "void main() {}\n");
        Path preset = root.resolve("crt/two-pass.cgp");
        write(preset, """
                shaders = 2
                shader0 = pass0.glsl
                scale0 = 2
                shader1 = pass1.glsl
                scale1 = 3
                scale_type1 = viewport
                filter_linear1 = true
                wrap_mode1 = clamp_to_border
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.CGP),
                ShaderPhase.PRESENTATION);

        assertEquals("crt/two-pass", loaded.label());
        assertEquals(ShaderPhase.PRESENTATION, loaded.phase());
        assertEquals(2, loaded.passes().size());
        assertEquals(2, loaded.passes().get(0).scale());
        assertEquals(ScaleType.SOURCE, loaded.passes().get(0).scaleType());
        assertFalse(loaded.passes().get(0).filterLinear());
        assertEquals(WrapMode.CLAMP_TO_EDGE, loaded.passes().get(0).wrapMode());
        assertEquals(3, loaded.passes().get(1).scale());
        assertEquals(ScaleType.VIEWPORT, loaded.passes().get(1).scaleType());
        assertTrue(loaded.passes().get(1).filterLinear());
        assertEquals(WrapMode.CLAMP_TO_EDGE, loaded.passes().get(1).wrapMode());
    }

    @Test
    public void missingScaleTypeDefaultsToSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("plain.glsl"), "void main() {}\n");
        Path preset = root.resolve("plain.glslp");
        write(preset, """
                shaders = 1
                shader0 = plain.glsl
                scale0 = 4
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.FINAL);

        assertEquals(ScaleType.SOURCE, loaded.passes().get(0).scaleType());
    }

    @Test
    public void finalPassWithoutScaleOptionsDefaultsToViewport() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("crt.glsl"), "void main() {}\n");
        Path preset = root.resolve("crt.glslp");
        write(preset, """
                shaders = 1
                shader0 = crt.glsl
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.FINAL);

        assertEquals(ScaleType.VIEWPORT, loaded.passes().get(0).scaleType());
        assertEquals(1, loaded.passes().get(0).scale());
    }

    @Test
    public void finalPassWithExplicitScaleTypeSourceStaysSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("crt.glsl"), "void main() {}\n");
        Path preset = root.resolve("crt.glslp");
        write(preset, """
                shaders = 1
                shader0 = crt.glsl
                scale_type0 = source
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.FINAL);

        assertEquals(ScaleType.SOURCE, loaded.passes().get(0).scaleType());
        assertEquals(1, loaded.passes().get(0).scale());
    }

    @Test
    public void intermediatePassWithoutScaleOptionsDefaultsToSource() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("pass0.glsl"), "void main() {}\n");
        write(root.resolve("pass1.glsl"), "void main() {}\n");
        Path preset = root.resolve("two-pass.glslp");
        write(preset, """
                shaders = 2
                shader0 = pass0.glsl
                shader1 = pass1.glsl
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.FINAL);

        assertEquals(ScaleType.SOURCE, loaded.passes().get(0).scaleType());
        assertEquals(ScaleType.VIEWPORT, loaded.passes().get(1).scaleType());
    }

    @Test
    public void parsesGlslpWithSameCoreFields() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("retro/shaders/pass0.glsl"), "void main() {}\n");
        write(root.resolve("retro/shaders/pass1.glsl"), "void main() {}\n");
        Path preset = root.resolve("retro/two-pass.glslp");
        write(preset, """
                shaders = 2
                shader0 = shaders/pass0.glsl
                scale0 = 2
                shader1 = shaders/pass1.glsl
                scale1 = 3
                scale_type1 = viewport
                filter_linear1 = true
                wrap_mode1 = repeat
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        assertEquals(2, loaded.passes().size());
        assertEquals(ScaleType.SOURCE, loaded.passes().get(0).scaleType());
        assertFalse(loaded.passes().get(0).filterLinear());
        assertEquals(ScaleType.VIEWPORT, loaded.passes().get(1).scaleType());
        assertTrue(loaded.passes().get(1).filterLinear());
        assertEquals(WrapMode.REPEAT, loaded.passes().get(1).wrapMode());
    }

    @Test
    public void parsesPerAxisScaleFields() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("retro/pass.glsl"), "void main() {}\n");
        Path preset = root.resolve("retro/per-axis.glslp");
        write(preset, """
                shaders = 1
                shader0 = pass.glsl
                scale_type_x0 = viewport
                scale_x0 = 1.0
                scale_type_y0 = source
                scale_y0 = 2.0
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        DisplayShaderPass pass = loaded.passes().get(0);
        assertEquals(ScaleType.VIEWPORT, pass.scaleTypeX());
        assertEquals(ScaleType.SOURCE, pass.scaleTypeY());
        assertEquals(1.0, pass.scaleX());
        assertEquals(2.0, pass.scaleY());
    }

    @Test
    public void parsesSupportedGlslpWrapModes() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("retro/pass0.glsl"), "void main() {}\n");
        write(root.resolve("retro/pass1.glsl"), "void main() {}\n");
        write(root.resolve("retro/pass2.glsl"), "void main() {}\n");
        write(root.resolve("retro/pass3.glsl"), "void main() {}\n");
        Path preset = root.resolve("retro/wraps.glslp");
        write(preset, """
                shaders = 4
                shader0 = pass0.glsl
                wrap_mode0 = clamp_to_border
                shader1 = pass1.glsl
                wrap_mode1 = clamp_to_edge
                shader2 = pass2.glsl
                wrap_mode2 = repeat
                shader3 = pass3.glsl
                wrap_mode3 = mirrored_repeat
                """);

        DisplayShaderPreset loaded = new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                ShaderPhase.PRESENTATION);

        assertEquals(WrapMode.CLAMP_TO_EDGE, loaded.passes().get(0).wrapMode());
        assertEquals(WrapMode.CLAMP_TO_EDGE, loaded.passes().get(1).wrapMode());
        assertEquals(WrapMode.REPEAT, loaded.passes().get(2).wrapMode());
        assertEquals(WrapMode.MIRRORED_REPEAT, loaded.passes().get(3).wrapMode());
    }

    @Test
    public void unknownWrapModeFailsLoad() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("retro/pass.glsl"), "void main() {}\n");
        Path preset = root.resolve("retro/wrap.glslp");
        write(preset, """
                shaders = 1
                shader0 = pass.glsl
                wrap_mode0 = mystery
                """);

        DisplayShaderLoadException error = assertThrows(DisplayShaderLoadException.class,
                () -> new DisplayShaderPresetLoader().load(ref(root, preset, DisplayShaderPresetRef.Kind.GLSLP),
                        ShaderPhase.PRESENTATION));

        assertTrue(error.getMessage().contains("wrap_mode"));
    }

    @Test
    public void displayShaderPresetDefensivelyCopiesPassList() {
        DisplayShaderPass pass = new DisplayShaderPass(null, "void main() {}\n", GlslShape.FRAGMENT_ONLY,
                1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE);
        List<DisplayShaderPass> passes = new ArrayList<>();
        passes.add(pass);

        DisplayShaderPreset preset = new DisplayShaderPreset("copy-test", ShaderPhase.FINAL, passes);
        passes.clear();

        assertEquals(1, preset.passes().size());
        assertThrows(UnsupportedOperationException.class, () -> preset.passes().add(pass));
    }

    private static DisplayShaderPresetRef ref(Path root, Path path, DisplayShaderPresetRef.Kind kind) {
        return new DisplayShaderPresetRef(kind, root.relativize(path).toString().replace('\\', '/'), path);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
