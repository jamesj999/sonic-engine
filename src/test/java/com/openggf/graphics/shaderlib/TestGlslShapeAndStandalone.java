package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

public class TestGlslShapeAndStandalone {

    @TempDir
    Path tempDir;

    @Test
    public void detectsBizHawkCombinedShape() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path shader = root.resolve("BizHawk/combined.glsl");
        write(shader, """
                #ifdef VERTEX
                void main() {}
                #endif
                #ifdef FRAGMENT
                void main() {}
                #endif
                """);

        DisplayShaderPass pass = loadStandalone(root, shader).passes().get(0);

        assertEquals(GlslShape.COMBINED, pass.shape());
        assertEquals(pass.fragmentSource(), pass.vertexSource());
    }

    @Test
    public void detectsRetroArchCombinedShape() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path shader = root.resolve("RetroArch/combined.glsl");
        write(shader, """
                #if defined(VERTEX)
                void main() {}
                #elif defined(FRAGMENT)
                void main() {}
                #endif
                """);

        DisplayShaderPass pass = loadStandalone(root, shader).passes().get(0);

        assertEquals(GlslShape.COMBINED, pass.shape());
        assertEquals(pass.fragmentSource(), pass.vertexSource());
    }

    @Test
    public void detectsUnderscoreVertexFragmentCombinedShape() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path shader = root.resolve("RetroArch/underscore-combined.glsl");
        write(shader, """
                #ifdef __vertex__
                void main() {}
                #endif
                #ifdef __fragment__
                void main() {}
                #endif
                """);

        DisplayShaderPass pass = loadStandalone(root, shader).passes().get(0);

        assertEquals(GlslShape.COMBINED, pass.shape());
        assertEquals(pass.fragmentSource(), pass.vertexSource());
    }

    @Test
    public void detectsCompatVertexFragmentCombinedShape() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path shader = root.resolve("RetroArch/compat-combined.glsl");
        write(shader, """
                #if defined(COMPAT_VERTEX)
                void main() {}
                #elif defined(COMPAT_FRAGMENT)
                void main() {}
                #endif
                """);

        DisplayShaderPass pass = loadStandalone(root, shader).passes().get(0);

        assertEquals(GlslShape.COMBINED, pass.shape());
        assertEquals(pass.fragmentSource(), pass.vertexSource());
    }

    @Test
    public void fragmentOnlyWhenNoVertexSection() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path shader = root.resolve("fragment.glsl");
        write(shader, "void main() {}\n");

        DisplayShaderPass pass = loadStandalone(root, shader).passes().get(0);

        assertEquals(GlslShape.FRAGMENT_ONLY, pass.shape());
        assertNull(pass.vertexSource());
    }

    @Test
    public void standaloneGlslLoadsSinglePassWithDefaultPhase() throws Exception {
        Path root = tempDir.resolve("display-shaders");
        Path shader = root.resolve("Custom/warm.glsl");
        write(shader, "void main() {}\n");

        DisplayShaderPreset loaded = loadStandalone(root, shader);
        DisplayShaderPass pass = loaded.passes().get(0);

        assertEquals("Custom/warm", loaded.label());
        assertEquals(ShaderPhase.FINAL, loaded.phase());
        assertEquals(1, loaded.passes().size());
        assertEquals(1, pass.scale());
        assertEquals(ScaleType.SOURCE, pass.scaleType());
        assertFalse(pass.filterLinear());
        assertEquals(WrapMode.CLAMP_TO_EDGE, pass.wrapMode());
        assertEquals("void main() {}\n", pass.fragmentSource());
    }

    private static DisplayShaderPreset loadStandalone(Path root, Path shader) throws Exception {
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(DisplayShaderPresetRef.Kind.GLSL,
                root.relativize(shader).toString().replace('\\', '/'), shader);
        return new DisplayShaderPresetLoader().load(ref, ShaderPhase.FINAL);
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
