package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class TestShaderLoader {

    @Test
    public void loadShaderSourceFindsBasicVertexShader() throws IOException {
        String source = ShaderLoader.loadShaderSource("shaders/shader_basic.vert");

        assertFalse(source.isBlank());
        assertTrue(source.contains("gl_Position"));
    }

    @Test
    public void instancedPriorityShaderSupportsGhostEffect() throws IOException {
        String source = ShaderLoader.loadShaderSource("shaders/shader_instanced_priority.glsl");

        assertTrue(source.contains("uniform int GhostMode;"));
        assertTrue(source.contains("uniform float GhostAlpha;"));
        assertTrue(source.contains("clamp(GhostAlpha"));
    }

    @Test
    public void priorityShadersMeasureWaterlineFromViewportTop() throws IOException {
        for (String shader : new String[]{
                "shaders/shader_sprite_priority.glsl",
                "shaders/shader_instanced_priority.glsl"
        }) {
            String source = ShaderLoader.loadShaderSource(shader);

            assertTrue(source.contains("gl_FragCoord.y - ViewportOffset.y"),
                    shader + " must remove letterbox offset before resolving the waterline");
        }
    }

    @Test
    public void shaderLoaderLoadsFromClasspathNotRepoFilesystem() throws IOException {
        // 1. A known classpath shader must load and return exactly the bytes of the
        //    packaged classpath resource (not some repo-filesystem variant).
        String classpathResource = "shaders/shader_basic.vert";
        String loaded = ShaderLoader.loadShaderSource(classpathResource);
        assertFalse(loaded.isBlank(), "classpath shader should load non-empty source");

        String expected;
        try (InputStream is = ShaderLoader.class.getClassLoader().getResourceAsStream(classpathResource)) {
            assertNotNull(is, "expected " + classpathResource + " to be a classpath resource");
            expected = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(expected, loaded,
                "loadShaderSource must return the classpath resource content verbatim");

        // 2. A path that exists on the repo filesystem but is NOT a classpath resource
        //    must fail to load. If ShaderLoader ever fell back to reading the working-dir
        //    filesystem, this would succeed and silently leak source files as "shaders".
        String repoOnlyPath = "src/main/java/com/openggf/graphics/ShaderLoader.java";
        assumeTrue(Files.exists(Path.of(repoOnlyPath)),
                "guard relies on the ShaderLoader source file existing on disk");
        assertFalse(ShaderLoader.class.getClassLoader().getResource(repoOnlyPath) != null,
                "the repo-only path must not be a classpath resource for this guard to be meaningful");
        assertThrows(IOException.class, () -> ShaderLoader.loadShaderSource(repoOnlyPath),
                "ShaderLoader must not fall back to reading repo filesystem paths");
    }
}

