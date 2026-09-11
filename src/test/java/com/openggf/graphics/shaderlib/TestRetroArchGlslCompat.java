package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRetroArchGlslCompat {

    @Test
    public void emits410CoreAndStageDefineBeforeSource() throws Exception {
        String staged = RetroArchGlslCompat.stageSource("#version 120\nvarying vec2 uv;", "FRAGMENT");

        assertTrue(staged.startsWith("#version 410 core\n#define FRAGMENT\n"));
        assertFalse(staged.contains("#version 120"));
    }

    @Test
    public void rejectsVersionsAboveEngineContext() {
        assertThrows(DisplayShaderLoadException.class,
                () -> RetroArchGlslCompat.stageSource("#version 450\nvoid main() {}", "FRAGMENT"));
    }

    @Test
    public void injectsLegacyFragmentPreludeWithoutUniformDeclarations() throws Exception {
        String source = """
                varying vec2 vTexCoord;
                uniform sampler2D Texture;
                void main() {
                    gl_FragColor = texture2D(Texture, vTexCoord);
                }
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertTrue(staged.contains("#define texture2D texture\n"));
        assertTrue(staged.contains("#define varying in\n"));
        assertTrue(staged.contains("out vec4 FragColor;\n"));
        assertTrue(staged.contains("#define gl_FragColor FragColor\n"));
        assertFalse(staged.contains("uniform vec"));
        assertFalse(staged.contains("OutputSize"));
        assertFalse(staged.contains("FrameCount"));
    }

    @Test
    public void ignoresVertexStageOutputsWhenCheckingCombinedFragmentOutput() throws Exception {
        String source = """
                #if defined(VERTEX)
                out vec2 vTexCoord;
                void main() {}
                #elif defined(FRAGMENT)
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                #endif
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertTrue(staged.contains("out vec4 FragColor;\n"));
        assertTrue(staged.contains("#define gl_FragColor FragColor\n"));
    }

    @Test
    public void emitsCompatStageAliasesBeforeCompatGuardedBlocks() throws Exception {
        String fragment = RetroArchGlslCompat.stageSource("""
                #ifdef COMPAT_FRAGMENT
                vec4 enabledFragmentBlock;
                #endif
                """, "FRAGMENT");
        String vertex = RetroArchGlslCompat.stageSource("""
                #ifdef COMPAT_VERTEX
                vec4 enabledVertexBlock;
                #endif
                """, "VERTEX");

        assertTrue(fragment.contains("#define COMPAT_FRAGMENT\n"));
        assertTrue(fragment.indexOf("#define COMPAT_FRAGMENT") < fragment.indexOf("#ifdef COMPAT_FRAGMENT"));
        assertTrue(vertex.contains("#define COMPAT_VERTEX\n"));
        assertTrue(vertex.indexOf("#define COMPAT_VERTEX") < vertex.indexOf("#ifdef COMPAT_VERTEX"));
    }

    @Test
    public void injectsFragmentOutputAfterLeadingExtensionDirectives() throws Exception {
        String source = """
                #version 120
                #extension GL_ARB_gpu_shader5 : enable
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertFalse(staged.contains("#version 120"));
        assertTrue(staged.indexOf("#extension GL_ARB_gpu_shader5 : enable") < staged.indexOf("out vec4 FragColor;"));
        assertTrue(staged.indexOf("out vec4 FragColor;") < staged.indexOf("void main()"));
    }

    @Test
    public void injectsFragmentOutputAfterLeadingCommentsAndExtensionDirectives() throws Exception {
        String source = """
                #version 120
                // required before extension
                /*
                 * keep before extension
                 */
                #extension GL_ARB_gpu_shader5 : enable
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertTrue(staged.indexOf("// required before extension")
                < staged.indexOf("#extension GL_ARB_gpu_shader5 : enable"));
        assertTrue(staged.indexOf("/*") < staged.indexOf("#extension GL_ARB_gpu_shader5 : enable"));
        assertTrue(staged.indexOf("#extension GL_ARB_gpu_shader5 : enable") < staged.indexOf("out vec4 FragColor;"));
        assertTrue(staged.indexOf("out vec4 FragColor;") < staged.indexOf("void main()"));
    }

    @Test
    public void injectsFragmentOutputAfterLeadingDefinesAndExtensionDirectives() throws Exception {
        String staged = RetroArchGlslCompat.stageSource("""
                #define FRAGMENT
                #extension GL_ARB_gpu_shader5 : enable
                void main() {
                    gl_FragColor = vec4(1.0);
                }
                """, "FRAGMENT");

        assertTrue(staged.indexOf("#define FRAGMENT") < staged.indexOf("#extension GL_ARB_gpu_shader5 : enable"));
        assertTrue(staged.indexOf("#extension GL_ARB_gpu_shader5 : enable") < staged.indexOf("out vec4 FragColor;"));
        assertTrue(staged.indexOf("out vec4 FragColor;") < staged.indexOf("void main()"));
    }

    @Test
    public void skipsStageAliasDefinesAlreadyPresentInBody() throws Exception {
        String withFragmentDefine = RetroArchGlslCompat.stageSource("""
                #define FRAGMENT
                void main() {}
                """, "FRAGMENT");
        String withCompatDefine = RetroArchGlslCompat.stageSource("""
                #define COMPAT_FRAGMENT
                void main() {}
                """, "FRAGMENT");

        assertEquals(1, countOccurrences(withFragmentDefine, "#define FRAGMENT"));
        assertEquals(1, countOccurrences(withFragmentDefine, "#define COMPAT_FRAGMENT"));
        assertEquals(1, countOccurrences(withCompatDefine, "#define FRAGMENT"));
        assertEquals(1, countOccurrences(withCompatDefine, "#define COMPAT_FRAGMENT"));
    }

    @Test
    public void detectsTexture2DWithWhitespaceBeforeCall() throws Exception {
        String staged = RetroArchGlslCompat.stageSource("""
                void main() {
                    gl_FragColor = texture2D (Texture, uv);
                }
                """, "FRAGMENT");

        assertTrue(staged.contains("#define texture2D texture\n"));
    }

    @Test
    public void injectsLegacyVertexPreludeStageAware() throws Exception {
        String source = """
                attribute vec4 VertexCoord;
                varying vec2 vTex;
                void main() {}
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "VERTEX");

        assertTrue(staged.contains("#define attribute in\n"));
        assertTrue(staged.contains("#define varying out\n"));
    }

    @Test
    public void existingFragmentOutputPreventsFragColorInjection() throws Exception {
        String source = """
                out vec4 SomeColor;
                void main() {
                    SomeColor = vec4(1.0);
                }
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertFalse(staged.contains("out vec4 FragColor;"));
        assertFalse(staged.contains("#define gl_FragColor FragColor"));
    }

    @Test
    public void existingFragmentOutputAliasesLegacyFragColorWrites() throws Exception {
        String source = """
                #if defined(FRAGMENT)
                #if __VERSION__ >= 130
                out vec4 FragColor;
                #else
                #define FragColor gl_FragColor
                #endif
                void main() {
                    gl_FragColor = vec4(0.0);
                    FragColor = vec4(1.0);
                }
                #endif
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertEquals(1, countOccurrences(staged, "out vec4 FragColor;"));
        assertTrue(staged.contains("#define gl_FragColor FragColor\n"));
    }

    @Test
    public void precisionQualifiedFragColorOutputPreventsDuplicateInjection() throws Exception {
        String source = """
                #if defined(VERTEX)
                void main() {}
                #elif defined(FRAGMENT)
                #if __VERSION__ >= 130
                #define COMPAT_PRECISION
                out COMPAT_PRECISION vec4 FragColor;
                #else
                #define FragColor gl_FragColor
                #endif
                void main() {
                    FragColor = vec4(1.0);
                }
                #endif
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");

        assertTrue(staged.contains("out COMPAT_PRECISION vec4 FragColor;"));
        assertFalse(staged.contains("out vec4 FragColor;"));
        assertEquals(1, countOccurrences(staged, "FragColor;"));
    }

    @Test
    public void canEnableRetroArchParameterUniformBlocks() throws Exception {
        String staged = RetroArchGlslCompat.stageSource("""
                #ifdef PARAMETER_UNIFORM
                uniform float HORIZONTAL_BLUR;
                #endif
                void main() {}
                """, "FRAGMENT", true);

        assertTrue(staged.contains("#define PARAMETER_UNIFORM\n"));
        assertTrue(staged.indexOf("#define PARAMETER_UNIFORM") < staged.indexOf("#ifdef PARAMETER_UNIFORM"));
    }

    @Test
    public void preservesRetroArchCompatMacrosInBody() throws Exception {
        String source = """
                #define COMPAT_TEXTURE texture
                #ifdef COMPAT_FRAGMENT
                COMPAT_TEXTURE(Texture, vTexCoord);
                #endif
                """;

        String staged = RetroArchGlslCompat.stageSource(source, "FRAGMENT");
        String body = staged.substring("#version 410 core\n#define FRAGMENT\n".length());

        assertTrue(staged.startsWith("#version 410 core\n#define FRAGMENT\n"));
        assertTrue(body.contains("#define COMPAT_TEXTURE texture"));
        assertTrue(body.contains("#ifdef COMPAT_FRAGMENT"));
        assertTrue(body.contains("COMPAT_TEXTURE(Texture, vTexCoord);"));
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
