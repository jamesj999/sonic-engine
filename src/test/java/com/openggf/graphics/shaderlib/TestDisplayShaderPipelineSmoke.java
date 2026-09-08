package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class TestDisplayShaderPipelineSmoke {

    @Test
    void frameStateRetainsViewportAndReusesTwoBackingsAcross600Frames() {
        DisplayShaderPipeline.FrameStatePool pool = new DisplayShaderPipeline.FrameStatePool();
        DisplayShaderPipeline.FrameState frameN = pool.obtainForTesting(1, 2, 320, 224);
        DisplayShaderPipeline.FrameState frameNPlusOne = pool.obtainForTesting(3, 4, 640, 448);

        assertEquals(1, frameN.viewportAt(0));
        assertEquals(2, frameN.viewportAt(1));
        assertEquals(320, frameN.viewportAt(2));
        assertEquals(224, frameN.viewportAt(3));
        assertEquals(3, frameNPlusOne.viewportAt(0));
        assertEquals(448, frameNPlusOne.viewportAt(3));

        Set<Object> states = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> viewports = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            DisplayShaderPipeline.FrameState state = pool.obtainForTesting(frame, 0, 320, 224);
            states.add(state);
            viewports.add(state.viewportBackingIdentity());
        }
        assertEquals(2, states.size());
        assertEquals(2, viewports.size());
    }

    @Test
    public void brokenShaderActivationReturnsFalseAndInactive() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);

            DisplayShaderPreset preset = new DisplayShaderPreset("broken", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            uniform sampler2D Texture;
                            void main() {
                                this is not valid glsl
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertFalse(pipeline.activate(preset));
            assertFalse(pipeline.isActive());
            assertTrue(pipeline.lastActivationFailure().contains("Display shader compile failed"));
            pipeline.dispose();
        }
    }

    @Test
    public void failedActivationKeepsPreviousShaderActive() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);
            assertTrue(pipeline.activate(passthroughPreset()));

            DisplayShaderPreset broken = new DisplayShaderPreset("broken", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            uniform sampler2D Texture;
                            void main() {
                                this is not valid glsl
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertFalse(pipeline.activate(broken));
            assertTrue(pipeline.isActive());

            pipeline.dispose();
        }
    }

    @Test
    public void combinedRetroArchStyleSourceCompilesAndActivates() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);

            String source = """
                    #if defined(VERTEX)
                    attribute vec4 VertexCoord;
                    attribute vec4 TexCoord;
                    attribute vec4 COLOR;
                    varying vec4 vTexCoord;
                    varying vec4 vColor;
                    uniform mat4 MVPMatrix;
                    void main() {
                        gl_Position = MVPMatrix * VertexCoord;
                        vTexCoord = TexCoord;
                        vColor = COLOR;
                    }
                    #elif defined(FRAGMENT)
                    varying vec4 vTexCoord;
                    varying vec4 vColor;
                    uniform sampler2D Texture;
                    uniform vec2 InputSize;
                    uniform vec2 TextureSize;
                    uniform vec2 OutputSize;
                    uniform int FrameCount;
                    void main() {
                        vec2 sizeMix = (InputSize + TextureSize + OutputSize) * 0.0;
                        gl_FragColor = texture2D(Texture, vTexCoord.xy + sizeMix) * vColor + vec4(float(FrameCount) * 0.0);
                    }
                    #endif
                    """;

            DisplayShaderPreset preset = new DisplayShaderPreset("combined", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(source, source, GlslShape.COMBINED, 1, ScaleType.SOURCE,
                            false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            assertTrue(pipeline.isActive());
            pipeline.dispose();
        }
    }

    @Test
    public void existingFragmentOutputWithLegacyFragColorWriteLinks() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);

            String source = """
                    #if defined(VERTEX)
                    attribute vec4 VertexCoord;
                    attribute vec2 TexCoord;
                    varying vec2 TEX0;
                    uniform mat4 MVPMatrix;
                    void main() {
                        gl_Position = MVPMatrix * VertexCoord;
                        TEX0 = TexCoord;
                    }
                    #elif defined(FRAGMENT)
                    #if __VERSION__ >= 130
                    out vec4 FragColor;
                    #else
                    #define FragColor gl_FragColor
                    #endif
                    varying vec2 TEX0;
                    void main() {
                        if (TEX0.x < 0.0) {
                            gl_FragColor = vec4(0.0);
                            return;
                        }
                        FragColor = vec4(1.0);
                    }
                    #endif
                    """;

            DisplayShaderPreset preset = new DisplayShaderPreset("existing-output-legacy-write", ShaderPhase.FINAL,
                    List.of(new DisplayShaderPass(source, source, GlslShape.COMBINED, 1, ScaleType.SOURCE,
                            false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset), pipeline.lastActivationFailure());
            assertTrue(pipeline.isActive());
            pipeline.dispose();
        }
    }

    @Test
    public void combinedTextureCoordinatesPreserveXAxisAndYAxis() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 16, 32, 16);

            String source = """
                    #if defined(VERTEX)
                    attribute vec4 VertexCoord;
                    attribute vec2 TexCoord;
                    varying vec2 TEX0;
                    uniform mat4 MVPMatrix;
                    void main() {
                        gl_Position = MVPMatrix * VertexCoord;
                        TEX0 = TexCoord;
                    }
                    #elif defined(FRAGMENT)
                    varying vec2 TEX0;
                    void main() {
                        gl_FragColor = vec4(TEX0.x, TEX0.y, 0.0, 1.0);
                    }
                    #endif
                    """;

            DisplayShaderPreset preset = new DisplayShaderPreset("axis-check", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(source, source, GlslShape.COMBINED, 1, ScaleType.SOURCE,
                            false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 32, 16);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 32, 16, 1);

            ByteBuffer rightMiddle = ByteBuffer.allocateDirect(4);
            glReadPixels(28, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, rightMiddle);
            int rightRed = Byte.toUnsignedInt(rightMiddle.get(0));
            int rightGreen = Byte.toUnsignedInt(rightMiddle.get(1));
            assertTrue(rightRed > rightGreen, "expected x axis to dominate at right edge, red="
                    + rightRed + " green=" + rightGreen);

            ByteBuffer topMiddle = ByteBuffer.allocateDirect(4);
            glReadPixels(16, 14, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, topMiddle);
            int topRed = Byte.toUnsignedInt(topMiddle.get(0));
            int topGreen = Byte.toUnsignedInt(topMiddle.get(1));
            assertTrue(topGreen > topRed, "expected y axis to dominate at top edge, red="
                    + topRed + " green=" + topGreen);
            pipeline.dispose();
        }
    }

    @Test
    public void fragmentOnlyPassthroughActivatesAndApplies() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);

            DisplayShaderPreset preset = new DisplayShaderPreset("passthrough", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            uniform sampler2D Texture;
                            uniform vec2 InputSize;
                            uniform vec2 TextureSize;
                            uniform vec2 OutputSize;
                            void main() {
                                vec2 uv = gl_FragCoord.xy / OutputSize;
                                gl_FragColor = texture2D(Texture, uv);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.MIRRORED_REPEAT)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 32, 32);
            glClearColor(0.25f, 0.5f, 0.75f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);

            pipeline.apply(0, 0, 32, 32, 7);

            assertTrue(pipeline.isActive());
            pipeline.dispose();
        }
    }

    @Test
    public void retroArchParameterUniformSourceActivatesAndBindsPresetValue() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(16, 16, 16, 16);

            DisplayShaderPreset preset = new DisplayShaderPreset("parameter-uniform", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            #ifdef PARAMETER_UNIFORM
                            uniform float HORIZONTAL_BLUR;
                            #endif
                            void main() {
                                gl_FragColor = HORIZONTAL_BLUR == 1.0
                                    ? vec4(0.0, 1.0, 0.0, 1.0)
                                    : vec4(1.0, 0.0, 0.0, 1.0);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false,
                            WrapMode.CLAMP_TO_EDGE, Map.of("HORIZONTAL_BLUR", 1.0f))));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 16, 16);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 16, 16, 1);

            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            assertTrue(green > 200, "expected bound parameter to render green, red=" + red + " green=" + green);
            assertTrue(red < 50, "expected low red success pixel, red=" + red + " green=" + green);
            pipeline.dispose();
        }
    }

    @Test
    public void dynamicUniformOverloadBindsPerFrameValues() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(16, 16, 16, 16);

            DisplayShaderPreset preset = new DisplayShaderPreset("dynamic-uniform", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            uniform float RewindIntensity;
                            void main() {
                                gl_FragColor = RewindIntensity == 0.75
                                    ? vec4(0.0, 1.0, 0.0, 1.0)
                                    : vec4(1.0, 0.0, 0.0, 1.0);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 16, 16);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 16, 16, 1, Map.of("RewindIntensity", 0.75f));

            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            assertTrue(green > 200, "expected dynamic uniform to render green, red=" + red + " green=" + green);
            assertTrue(red < 50, "expected low red success pixel, red=" + red + " green=" + green);
            pipeline.dispose();
        }
    }

    @Test
    public void passPrevTextureSamplerBindsEarlierPassOutput() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(16, 16, 16, 16);

            DisplayShaderPreset preset = new DisplayShaderPreset("pass-prev", ShaderPhase.FINAL, List.of(
                    solidColorPass(1.0f, 0.0f, 0.0f),
                    solidColorPass(0.0f, 1.0f, 0.0f),
                    solidColorPass(0.0f, 0.0f, 1.0f),
                    solidColorPass(0.0f, 0.0f, 1.0f),
                    solidColorPass(0.0f, 0.0f, 1.0f),
                    new DisplayShaderPass(null, """
                            uniform sampler2D Texture;
                            uniform sampler2D PassPrev4Texture;
                            uniform vec2 OutputSize;
                            void main() {
                                vec2 uv = gl_FragCoord.xy / OutputSize;
                                gl_FragColor = texture2D(PassPrev4Texture, uv);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 16, 16);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 16, 16, 1);

            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            glReadPixels(8, 8, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            int blue = Byte.toUnsignedInt(pixel.get(2));
            assertTrue(green > 200, "expected PassPrev4Texture to sample green pass, red="
                    + red + " green=" + green + " blue=" + blue);
            assertTrue(red < 50, "expected low red, red=" + red + " green=" + green + " blue=" + blue);
            assertTrue(blue < 50, "expected low blue, red=" + red + " green=" + green + " blue=" + blue);
            pipeline.dispose();
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    public void applyRestoresCallerGlStateAfterSuccess(int textureUnit) {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);
            assertTrue(pipeline.activate(passthroughPreset()));

            int sentinelFbo = glGenFramebuffers();
            int sentinelTexture = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, sentinelTexture);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 4, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            glBindFramebuffer(GL_FRAMEBUFFER, sentinelFbo);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, sentinelTexture, 0);
            assertEquals(GL_FRAMEBUFFER_COMPLETE, glCheckFramebufferStatus(GL_FRAMEBUFFER));

            int sentinelProgram = createSentinelProgram();
            int sentinelVao = glGenVertexArrays();
            int boundTexture = glGenTextures();
            glActiveTexture(GL_TEXTURE0 + textureUnit);
            glBindTexture(GL_TEXTURE_2D, boundTexture);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, sentinelFbo);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, sentinelFbo);
            glUseProgram(sentinelProgram);
            glBindVertexArray(sentinelVao);
            glViewport(3, 5, 17, 19);
            glEnable(GL_BLEND);
            glEnable(GL_DEPTH_TEST);

            pipeline.apply(0, 0, 32, 32, 3);

            int[] viewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, viewport);
            assertEquals(3, viewport[0]);
            assertEquals(5, viewport[1]);
            assertEquals(17, viewport[2]);
            assertEquals(19, viewport[3]);
            assertEquals(sentinelFbo, glGetInteger(GL_READ_FRAMEBUFFER_BINDING));
            assertEquals(sentinelFbo, glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING));
            assertEquals(sentinelProgram, glGetInteger(GL_CURRENT_PROGRAM));
            assertEquals(sentinelVao, glGetInteger(GL_VERTEX_ARRAY_BINDING));
            assertEquals(GL_TEXTURE0 + textureUnit, glGetInteger(GL_ACTIVE_TEXTURE));
            assertEquals(boundTexture, glGetInteger(GL_TEXTURE_BINDING_2D));
            glActiveTexture(GL_TEXTURE0);
            assertEquals(textureUnit == 0 ? boundTexture : sentinelTexture,
                    glGetInteger(GL_TEXTURE_BINDING_2D));
            glActiveTexture(GL_TEXTURE0 + textureUnit);
            assertTrue(glIsEnabled(GL_BLEND));
            assertTrue(glIsEnabled(GL_DEPTH_TEST));

            pipeline.dispose();
            glDeleteVertexArrays(sentinelVao);
            glDeleteProgram(sentinelProgram);
            glDeleteTextures(boundTexture);
            glDeleteFramebuffers(sentinelFbo);
            glDeleteTextures(sentinelTexture);
        }
    }

    @Test
    public void firstPassUsesLogicalSourceSizeUniformsWhenViewportDiffers() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(16, 8, 32, 32);

            DisplayShaderPreset preset = new DisplayShaderPreset("uniform-check", ShaderPhase.FINAL, List.of(
                    new DisplayShaderPass(null, """
                            uniform sampler2D Texture;
                            uniform vec2 InputSize;
                            uniform vec2 TextureSize;
                            uniform vec2 OutputSize;
                            uniform int FrameCount;
                            uniform int FrameDirection;
                            uniform mat4 MVPMatrix;
                            struct InputBlock {
                                vec2 video_size;
                                vec2 texture_size;
                                vec2 output_size;
                            };
                            uniform InputBlock IN;
                            void main() {
                                bool ok = InputSize == vec2(16.0, 8.0)
                                    && TextureSize == vec2(16.0, 8.0)
                                    && OutputSize == vec2(32.0, 16.0)
                                    && IN.video_size == vec2(16.0, 8.0)
                                    && IN.texture_size == vec2(16.0, 8.0)
                                    && IN.output_size == vec2(32.0, 16.0)
                                    && FrameCount == 11
                                    && FrameDirection == 1
                                    && MVPMatrix[0][0] == 1.0;
                                gl_FragColor = ok ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 2, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 32, 32);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 32, 32, 11);

            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            assertTrue(green > 200, "expected green success pixel, red=" + red + " green=" + green);
            assertTrue(red < 50, "expected low red success pixel, red=" + red + " green=" + green);
            pipeline.dispose();
        }
    }

    @Test
    public void sourceScaleTargetsCascadeFromPreviousPassOutput() {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(8, 8, 32, 32);

            DisplayShaderPreset preset = new DisplayShaderPreset("source-cascade", ShaderPhase.FINAL, List.of(
                    solidColorPass(0.0f, 0.0f, 1.0f, 2, ScaleType.SOURCE),
                    new DisplayShaderPass(null, """
                            uniform vec2 InputSize;
                            uniform vec2 TextureSize;
                            uniform vec2 OutputSize;
                            void main() {
                                bool ok = InputSize == vec2(16.0, 16.0)
                                    && TextureSize == vec2(16.0, 16.0)
                                    && OutputSize == vec2(32.0, 32.0);
                                gl_FragColor = ok ? vec4(0.0, 1.0, 0.0, 1.0) : vec4(1.0, 0.0, 0.0, 1.0);
                            }
                            """, GlslShape.FRAGMENT_ONLY, 2, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));

            assertTrue(pipeline.activate(preset));
            glViewport(0, 0, 32, 32);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 32, 32, 1);

            ByteBuffer pixel = ByteBuffer.allocateDirect(4);
            glReadPixels(16, 16, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
            int red = Byte.toUnsignedInt(pixel.get(0));
            int green = Byte.toUnsignedInt(pixel.get(1));
            assertTrue(green > 200, "expected cascaded source scale uniforms, red=" + red + " green=" + green);
            assertTrue(red < 50, "expected low red success pixel, red=" + red + " green=" + green);
            pipeline.dispose();
        }
    }

    @Test
    public void vhsRewindBuiltInPresetActivatesAndApplies() throws Exception {
        try (GlContext ignored = GlContext.open()) {
            DisplayShaderPipeline pipeline = new DisplayShaderPipeline();
            pipeline.resize(32, 32, 32, 32);

            assertTrue(pipeline.activate(RewindVhsEffectPass.builtInPreset()),
                    pipeline.lastActivationFailure());

            glViewport(0, 0, 32, 32);
            glClearColor(0.2f, 0.4f, 0.6f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            pipeline.apply(0, 0, 32, 32, 5, Map.of("RewindIntensity", 1.0f, "RewindSpeed", 1.0f));

            assertTrue(pipeline.isActive(), "VHS preset apply must not disable the pipeline");
            pipeline.dispose();
        }
    }

    @Test
    public void rewindVhsEffectPassPrewarmsAppliesAndStaysHealthy() {
        try (GlContext ignored = GlContext.open()) {
            RewindVhsEffectPass pass = new RewindVhsEffectPass();

            // apply before prewarm is a safe no-op
            pass.apply(1.0f, 1.0f, RewindVhsEffectPass.REWIND_SCROLL_DIRECTION, true, 32, 32, 0, 0, 32, 32);
            assertFalse(pass.isFailed());

            pass.prewarm(32, 32, 32, 32);
            assertFalse(pass.isFailed(), "built-in preset must activate cleanly");

            glViewport(0, 0, 32, 32);
            glClearColor(0.2f, 0.4f, 0.6f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);

            pass.apply(1.0f, 1.0f, RewindVhsEffectPass.REWIND_SCROLL_DIRECTION, true, 32, 32, 0, 0, 32, 32);
            assertFalse(pass.isFailed(), "healthy apply must not latch failure");

            // zero intensity is a no-op and must not affect health
            pass.apply(0.0f, 1.0f, RewindVhsEffectPass.REWIND_SCROLL_DIRECTION, true, 32, 32, 0, 0, 32, 32);
            assertFalse(pass.isFailed());

            pass.dispose();
        }
    }

    private static DisplayShaderPreset passthroughPreset() {
        return new DisplayShaderPreset("passthrough", ShaderPhase.FINAL, List.of(
                new DisplayShaderPass(null, """
                        uniform sampler2D Texture;
                        uniform vec2 OutputSize;
                        void main() {
                            gl_FragColor = texture2D(Texture, gl_FragCoord.xy / OutputSize);
                        }
                        """, GlslShape.FRAGMENT_ONLY, 1, ScaleType.SOURCE, false, WrapMode.CLAMP_TO_EDGE)));
    }

    private static DisplayShaderPass solidColorPass(float red, float green, float blue) {
        return solidColorPass(red, green, blue, 1, ScaleType.SOURCE);
    }

    private static DisplayShaderPass solidColorPass(float red, float green, float blue,
                                                    double scale, ScaleType scaleType) {
        return new DisplayShaderPass(null, """
                void main() {
                    gl_FragColor = vec4(%sf, %sf, %sf, 1.0);
                }
                """.formatted(red, green, blue), GlslShape.FRAGMENT_ONLY, scale, scaleType,
                false, WrapMode.CLAMP_TO_EDGE);
    }

    private static int createSentinelProgram() {
        int vertex = compileSentinelShader(GL_VERTEX_SHADER, """
                #version 410 core
                void main() {
                    gl_Position = vec4(0.0);
                }
                """);
        int fragment = compileSentinelShader(GL_FRAGMENT_SHADER, """
                #version 410 core
                out vec4 FragColor;
                void main() {
                    FragColor = vec4(1.0);
                }
                """);
        int program = glCreateProgram();
        glAttachShader(program, vertex);
        glAttachShader(program, fragment);
        glLinkProgram(program);
        assertEquals(GL_TRUE, glGetProgrami(program, GL_LINK_STATUS), glGetProgramInfoLog(program));
        glDetachShader(program, vertex);
        glDetachShader(program, fragment);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        return program;
    }

    private static int compileSentinelShader(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        assertEquals(GL_TRUE, glGetShaderi(shader, GL_COMPILE_STATUS), glGetShaderInfoLog(shader));
        return shader;
    }

    private static final class GlContext implements AutoCloseable {
        private final long window;

        private GlContext(long window) {
            this.window = window;
        }

        static GlContext open() {
            boolean initialized = glfwInit();
            Assumptions.assumeTrue(initialized, "GLFW init failed; skipping GL smoke test");

            long window = 0L;
            try {
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
                glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
                glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
                glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);

                window = glfwCreateWindow(64, 64, "DisplayShaderPipelineSmoke", 0L, 0L);
                Assumptions.assumeTrue(window != 0L, "OpenGL 4.1 core context unavailable; skipping GL smoke test");
                glfwMakeContextCurrent(window);
                GL.createCapabilities();
                return new GlContext(window);
            } catch (Throwable t) {
                if (window != 0L) {
                    glfwDestroyWindow(window);
                }
                glfwTerminate();
                Assumptions.assumeTrue(false, "OpenGL context unavailable; skipping GL smoke test: " + t);
                return null;
            }
        }

        @Override
        public void close() {
            glfwMakeContextCurrent(0L);
            if (window != 0L) {
                glfwDestroyWindow(window);
            }
            glfwTerminate();
        }
    }
}
