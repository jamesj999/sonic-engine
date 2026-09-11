package com.openggf.graphics.shaderlib;

import com.openggf.util.FboHelper;
import com.openggf.util.FboHelper.FboHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL14.GL_MIRRORED_REPEAT;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

public class DisplayShaderPipeline {
    private static final Logger LOG = Logger.getLogger(DisplayShaderPipeline.class.getName());
    private static final float[] IDENTITY_MATRIX = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
    };
    private static final String FULLSCREEN_VERTEX_SOURCE = """
            #version 410 core
            out vec2 vTexCoord;
            void main() {
                vec2 positions[4] = vec2[](
                    vec2(-1.0, -1.0),
                    vec2( 1.0, -1.0),
                    vec2(-1.0,  1.0),
                    vec2( 1.0,  1.0)
                );
                vec2 texCoords[4] = vec2[](
                    vec2(0.0, 0.0),
                    vec2(1.0, 0.0),
                    vec2(0.0, 1.0),
                    vec2(1.0, 1.0)
                );
                gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
                vTexCoord = texCoords[gl_VertexID];
            }
            """;

    private final List<CompiledPass> passes = new ArrayList<>();
    private final List<PassTarget> passTargets = new ArrayList<>();
    private FboHandle captureFbo;
    private int fragmentOnlyVao;
    private int combinedVao;
    private int combinedVbo;
    // Context capability, captured with the resources owned by that context.
    private int maxTextureUnits;
    private int sourceWidth = 1;
    private int sourceHeight = 1;
    private int viewportWidth = 1;
    private int viewportHeight = 1;
    private ShaderPhase phase = ShaderPhase.FINAL;
    private boolean active;
    private String lastActivationFailure = "";
    private final FrameStatePool frameStatePool = new FrameStatePool();

    public boolean activate(DisplayShaderPreset preset) {
        ShaderPhase requestedPhase = preset == null || preset.phase() == null ? ShaderPhase.FINAL : preset.phase();
        if (preset == null || preset.passes().isEmpty()) {
            dispose();
            phase = requestedPhase;
            lastActivationFailure = "";
            return true;
        }

        List<CompiledPass> compiled = new ArrayList<>(preset.passes().size());
        String presetLabel = preset.label() == null || preset.label().isBlank() ? "<unnamed>" : preset.label();
        FboSet fbos = null;
        int newFragmentOnlyVao = 0;
        int newCombinedVao = 0;
        int newCombinedVbo = 0;
        try {
            for (DisplayShaderPass pass : preset.passes()) {
                compiled.add(compilePass(pass));
            }
            newFragmentOnlyVao = glGenVertexArrays();
            CombinedQuadResources quad = createCombinedQuadResources();
            newCombinedVao = quad.vaoId();
            newCombinedVbo = quad.vboId();
            fbos = createFbos(compiled);
            if (fbos == null) {
                throw new DisplayShaderLoadException("Display shader FBO allocation failed");
            }

            dispose();
            passes.addAll(compiled);
            captureFbo = fbos.captureFbo();
            passTargets.addAll(fbos.targets());
            fragmentOnlyVao = newFragmentOnlyVao;
            combinedVao = newCombinedVao;
            combinedVbo = newCombinedVbo;
            maxTextureUnits = glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
            phase = requestedPhase;
            active = true;
            lastActivationFailure = "";
            compiled = null;
            fbos = null;
            newFragmentOnlyVao = 0;
            newCombinedVao = 0;
            newCombinedVbo = 0;
            return true;
        } catch (Exception e) {
            lastActivationFailure = e.getMessage() == null ? e.toString() : e.getMessage();
            LOG.log(Level.WARNING, "Display shader activation failed: " + presetLabel, e);
            if (compiled != null) {
                deletePrograms(compiled);
            }
            destroyFbos(fbos);
            deleteVertexResources(newFragmentOnlyVao, newCombinedVao, newCombinedVbo);
            return false;
        }
    }

    public String lastActivationFailure() {
        return lastActivationFailure;
    }

    public boolean isActive() {
        return active;
    }

    public ShaderPhase phase() {
        return phase;
    }

    public void resize(int sourceW, int sourceH, int viewportW, int viewportH) {
        int nextSourceW = Math.max(1, sourceW);
        int nextSourceH = Math.max(1, sourceH);
        int nextViewportW = Math.max(1, viewportW);
        int nextViewportH = Math.max(1, viewportH);
        boolean changed = nextSourceW != sourceWidth
                || nextSourceH != sourceHeight
                || nextViewportW != viewportWidth
                || nextViewportH != viewportHeight;

        sourceWidth = nextSourceW;
        sourceHeight = nextSourceH;
        viewportWidth = nextViewportW;
        viewportHeight = nextViewportH;

        if (active && changed && !rebuildFbos()) {
            LOG.severe("Display shader FBO rebuild failed after resize; disabling pipeline");
            dispose();
        }
    }

    public void apply(int vpX, int vpY, int vpW, int vpH, int frameCount) {
        apply(vpX, vpY, vpW, vpH, frameCount, Map.of());
    }

    /**
     * Apply the pass chain with additional per-frame float uniforms, set after
     * the preset's static parameter values (so dynamic values win on collision).
     */
    public void apply(int vpX, int vpY, int vpW, int vpH, int frameCount, Map<String, Float> dynamicUniforms) {
        if (!active || vpW <= 0 || vpH <= 0) {
            return;
        }

        FrameState state = frameStatePool.obtain();
        state.capture();
        boolean disableAfterRestore = false;
        try {
            if (vpW != viewportWidth || vpH != viewportHeight) {
                resize(sourceWidth, sourceHeight, vpW, vpH);
                if (!active) {
                    return;
                }
            }

            clearGlErrors();
            captureViewport(vpX, vpY, vpW, vpH);

            int inputTexture = captureFbo.textureId();
            int inputWidth = sourceWidth;
            int inputHeight = sourceHeight;
            int lastFilter = GL_NEAREST;
            for (int i = 0; i < passes.size(); i++) {
                CompiledPass pass = passes.get(i);
                PassTarget target = passTargets.get(i);
                renderPass(i, pass, target, inputTexture, inputWidth, inputHeight, frameCount, dynamicUniforms);
                inputTexture = target.fbo().textureId();
                inputWidth = target.width();
                inputHeight = target.height();
                lastFilter = pass.filterLinear() ? GL_LINEAR : GL_NEAREST;
            }

            blitToDefaultViewport(passTargets.get(passTargets.size() - 1), vpX, vpY, vpW, vpH, lastFilter);
            checkGlError("display shader apply");
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Display shader apply failed; disabling pipeline", e);
            disableAfterRestore = true;
        } finally {
            state.restore();
            if (disableAfterRestore) {
                dispose();
            }
        }
    }

    public void dispose() {
        active = false;
        deletePrograms(passes);
        passes.clear();
        destroyCurrentFbos();
        deleteVertexResources(fragmentOnlyVao, combinedVao, combinedVbo);
        fragmentOnlyVao = 0;
        combinedVao = 0;
        combinedVbo = 0;
    }

    private boolean rebuildFbos() {
        FboSet rebuilt = createFbos(passes);
        if (rebuilt == null) {
            return false;
        }
        destroyCurrentFbos();
        captureFbo = rebuilt.captureFbo();
        passTargets.addAll(rebuilt.targets());
        return true;
    }

    private void captureViewport(int vpX, int vpY, int vpW, int vpH) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, captureFbo.fboId());
        glBlitFramebuffer(vpX, vpY, vpX + vpW, vpY + vpH,
                0, 0, viewportWidth, viewportHeight,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
    }

    private void renderPass(int passIndex, CompiledPass pass, PassTarget target, int inputTexture,
                            int inputWidth, int inputHeight, int frameCount, Map<String, Float> dynamicUniforms) {
        glBindFramebuffer(GL_FRAMEBUFFER, target.fbo().fboId());
        glViewport(0, 0, target.width(), target.height());
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_BLEND);
        glUseProgram(pass.programId());

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, inputTexture);
        configureSampler(pass);
        setUniforms(pass.programId(), sourceWidth, sourceHeight,
                inputWidth, inputHeight, target.width(), target.height(), frameCount,
                pass.parameterValues(), dynamicUniforms);
        bindPassHistoryTextures(pass.programId(), passIndex, pass);

        if (pass.shape() == GlslShape.FRAGMENT_ONLY) {
            glBindVertexArray(fragmentOnlyVao);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        } else {
            glBindVertexArray(combinedVao);
            glDrawArrays(GL_TRIANGLES, 0, 6);
        }
    }

    private void bindPassHistoryTextures(int programId, int passIndex, CompiledPass pass) {
        int maxHistory = Math.min(passIndex, Math.max(0, maxTextureUnits - 1));
        for (int historyIndex = 1; historyIndex <= maxHistory; historyIndex++) {
            PassTarget previous = passTargets.get(passIndex - historyIndex);
            glActiveTexture(GL_TEXTURE0 + historyIndex);
            glBindTexture(GL_TEXTURE_2D, previous.fbo().textureId());
            configureSampler(pass);
            setSampler(programId, "PassPrev" + historyIndex + "Texture", historyIndex);
            setVec2(programId, "PassPrev" + historyIndex + "InputSize", previous.width(), previous.height());
            setVec2(programId, "PassPrev" + historyIndex + "TextureSize", previous.width(), previous.height());
            setVec2(programId, "PassPrev" + historyIndex + "OutputSize", previous.width(), previous.height());
        }

        if (passIndex > 0) {
            setSampler(programId, "PassPrevTexture", 1);
            PassTarget previous = passTargets.get(passIndex - 1);
            setVec2(programId, "PassPrevInputSize", previous.width(), previous.height());
            setVec2(programId, "PassPrevTextureSize", previous.width(), previous.height());
            setVec2(programId, "PassPrevOutputSize", previous.width(), previous.height());
        }
        glActiveTexture(GL_TEXTURE0);
    }

    private void blitToDefaultViewport(PassTarget target, int vpX, int vpY, int vpW, int vpH, int filter) {
        glBindFramebuffer(GL_READ_FRAMEBUFFER, target.fbo().fboId());
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, target.width(), target.height(),
                vpX, vpY, vpX + vpW, vpY + vpH,
                GL_COLOR_BUFFER_BIT, filter);
    }

    private void setUniforms(int programId, int videoWidth, int videoHeight, int inputWidth, int inputHeight,
                             int outputWidth, int outputHeight, int frameCount,
                             Map<String, Float> parameterValues, Map<String, Float> dynamicUniforms) {
        setSampler(programId, "s_p");
        setSampler(programId, "SceneTexture");
        setSampler(programId, "Texture");

        setVec2(programId, "IN.video_size", videoWidth, videoHeight);
        setVec2(programId, "IN.texture_size", inputWidth, inputHeight);
        setVec2(programId, "IN.output_size", outputWidth, outputHeight);
        setVec2(programId, "InputSize", inputWidth, inputHeight);
        setVec2(programId, "TextureSize", inputWidth, inputHeight);
        setVec2(programId, "OutputSize", outputWidth, outputHeight);

        setInt(programId, "FrameCount", frameCount);
        setInt(programId, "FrameDirection", 1);
        if (parameterValues != null) {
            for (Map.Entry<String, Float> entry : parameterValues.entrySet()) {
                setFloat(programId, entry.getKey(), entry.getValue());
            }
        }
        if (dynamicUniforms != null) {
            for (Map.Entry<String, Float> entry : dynamicUniforms.entrySet()) {
                setFloat(programId, entry.getKey(), entry.getValue());
            }
        }
        int mvpLocation = glGetUniformLocation(programId, "MVPMatrix");
        if (mvpLocation >= 0) {
            glUniformMatrix4fv(mvpLocation, false, IDENTITY_MATRIX);
        }
    }

    private void setSampler(int programId, String name) {
        setSampler(programId, name, 0);
    }

    private void setSampler(int programId, String name, int textureUnit) {
        int location = glGetUniformLocation(programId, name);
        if (location >= 0) {
            glUniform1i(location, textureUnit);
        }
    }

    private void setVec2(int programId, String name, int x, int y) {
        int location = glGetUniformLocation(programId, name);
        if (location >= 0) {
            glUniform2f(location, x, y);
        }
    }

    private void setInt(int programId, String name, int value) {
        int location = glGetUniformLocation(programId, name);
        if (location >= 0) {
            glUniform1i(location, value);
        }
    }

    private void setFloat(int programId, String name, float value) {
        int location = glGetUniformLocation(programId, name);
        if (location >= 0) {
            glUniform1f(location, value);
        }
    }

    private void configureSampler(CompiledPass pass) {
        int filter = pass.filterLinear() ? GL_LINEAR : GL_NEAREST;
        int wrap = toGlWrapMode(pass.wrapMode());
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrap);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrap);
    }

    private FboSet createFbos(List<CompiledPass> compiledPasses) {
        FboHandle capture = null;
        List<PassTarget> targets = new ArrayList<>(compiledPasses.size());
        try {
            capture = FboHelper.createColorOnly(viewportWidth, viewportHeight, GL_CLAMP_TO_EDGE);
            if (capture == null) {
                return null;
            }

            for (CompiledPass pass : compiledPasses) {
                int previousWidth = targets.isEmpty() ? sourceWidth : targets.get(targets.size() - 1).width();
                int previousHeight = targets.isEmpty() ? sourceHeight : targets.get(targets.size() - 1).height();
                int baseWidth = pass.scaleTypeX() == ScaleType.VIEWPORT ? viewportWidth : previousWidth;
                int baseHeight = pass.scaleTypeY() == ScaleType.VIEWPORT ? viewportHeight : previousHeight;
                int width = scaledDimension(baseWidth, pass.scaleX());
                int height = scaledDimension(baseHeight, pass.scaleY());
                FboHandle fbo = FboHelper.createColorOnly(width, height, toGlWrapMode(pass.wrapMode()));
                if (fbo == null) {
                    return null;
                }
                targets.add(new PassTarget(fbo, width, height));
            }

            return new FboSet(capture, List.copyOf(targets));
        } finally {
            if (targets.size() != compiledPasses.size()) {
                FboHelper.destroy(capture);
                for (PassTarget target : targets) {
                    FboHelper.destroy(target.fbo());
                }
            }
        }
    }

    private CompiledPass compilePass(DisplayShaderPass pass) throws DisplayShaderLoadException {
        if (pass == null || pass.fragmentSource() == null || pass.fragmentSource().isBlank()) {
            throw new DisplayShaderLoadException("Display shader pass requires fragment source");
        }

        GlslShape shape = pass.shape() == null ? GlslShape.FRAGMENT_ONLY : pass.shape();
        String rawVertexSource = shape == GlslShape.FRAGMENT_ONLY
                ? FULLSCREEN_VERTEX_SOURCE
                : firstPresent(pass.vertexSource(), pass.fragmentSource());
        boolean enableParameterUniforms = !pass.parameterValues().isEmpty();
        String vertexSource = shape == GlslShape.FRAGMENT_ONLY
                ? rawVertexSource
                : RetroArchGlslCompat.stageSource(rawVertexSource, "VERTEX", enableParameterUniforms);
        String fragmentSource = RetroArchGlslCompat.stageSource(pass.fragmentSource(), "FRAGMENT",
                enableParameterUniforms);
        int programId = compileProgram(vertexSource, fragmentSource, shape);
        return new CompiledPass(programId, shape, sanitizeScale(pass.scaleX()), sanitizeScale(pass.scaleY()),
                pass.scaleTypeX() == null ? ScaleType.SOURCE : pass.scaleTypeX(),
                pass.scaleTypeY() == null ? ScaleType.SOURCE : pass.scaleTypeY(),
                pass.filterLinear(),
                pass.wrapMode() == null ? WrapMode.CLAMP_TO_EDGE : pass.wrapMode(),
                pass.parameterValues());
    }

    private static double sanitizeScale(double scale) {
        return Double.isFinite(scale) && scale > 0.0 ? scale : 1.0;
    }

    private static int scaledDimension(int baseDimension, double scale) {
        return Math.max(1, (int) Math.round(baseDimension * sanitizeScale(scale)));
    }

    private int compileProgram(String vertexSource, String fragmentSource, GlslShape shape)
            throws DisplayShaderLoadException {
        int vertexShader = 0;
        int fragmentShader = 0;
        int programId = 0;
        try {
            vertexShader = compileShader(GL_VERTEX_SHADER, vertexSource);
            fragmentShader = compileShader(GL_FRAGMENT_SHADER, fragmentSource);
            programId = glCreateProgram();
            glAttachShader(programId, vertexShader);
            glAttachShader(programId, fragmentShader);
            if (shape == GlslShape.COMBINED) {
                glBindAttribLocation(programId, 0, "VertexCoord");
                glBindAttribLocation(programId, 1, "TexCoord");
                glBindAttribLocation(programId, 2, "COLOR");
            }
            glLinkProgram(programId);
            if (glGetProgrami(programId, GL_LINK_STATUS) == 0) {
                throw new DisplayShaderLoadException("Display shader link failed: " + glGetProgramInfoLog(programId));
            }
            return programId;
        } catch (DisplayShaderLoadException e) {
            if (programId != 0) {
                glDeleteProgram(programId);
                programId = 0;
            }
            throw e;
        } finally {
            if (programId != 0 && vertexShader != 0) {
                glDetachShader(programId, vertexShader);
            }
            if (programId != 0 && fragmentShader != 0) {
                glDetachShader(programId, fragmentShader);
            }
            if (vertexShader != 0) {
                glDeleteShader(vertexShader);
            }
            if (fragmentShader != 0) {
                glDeleteShader(fragmentShader);
            }
        }
    }

    private int compileShader(int shaderType, String source) throws DisplayShaderLoadException {
        int shaderId = glCreateShader(shaderType);
        try {
            glShaderSource(shaderId, source);
            glCompileShader(shaderId);
            if (glGetShaderi(shaderId, GL_COMPILE_STATUS) == 0) {
                throw new DisplayShaderLoadException("Display shader compile failed: " + glGetShaderInfoLog(shaderId));
            }
            return shaderId;
        } catch (DisplayShaderLoadException e) {
            glDeleteShader(shaderId);
            throw e;
        }
    }

    private CombinedQuadResources createCombinedQuadResources() {
        int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = glGetInteger(GL_ARRAY_BUFFER_BINDING);
        int vaoId = 0;
        int vboId = 0;
        try {
            vaoId = glGenVertexArrays();
            vboId = glGenBuffers();
            glBindVertexArray(vaoId);
            glBindBuffer(GL_ARRAY_BUFFER, vboId);
            glBufferData(GL_ARRAY_BUFFER, combinedQuadVertices(), GL_STATIC_DRAW);

            int stride = 12 * Float.BYTES;
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 4, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 8L * Float.BYTES);

            return new CombinedQuadResources(vaoId, vboId);
        } catch (RuntimeException | Error e) {
            if (vaoId != 0) {
                glDeleteVertexArrays(vaoId);
            }
            if (vboId != 0) {
                glDeleteBuffers(vboId);
            }
            throw e;
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, previousArrayBuffer);
            glBindVertexArray(previousVao);
        }
    }

    private static float[] combinedQuadVertices() {
        return new float[] {
                -1.0f, -1.0f, 0.0f, 1.0f,   0.0f, 0.0f, 0.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f,
                 1.0f, -1.0f, 0.0f, 1.0f,   1.0f, 0.0f, 0.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,   0.0f, 1.0f, 0.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f,
                -1.0f,  1.0f, 0.0f, 1.0f,   0.0f, 1.0f, 0.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f,
                 1.0f, -1.0f, 0.0f, 1.0f,   1.0f, 0.0f, 0.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f,
                 1.0f,  1.0f, 0.0f, 1.0f,   1.0f, 1.0f, 0.0f, 1.0f,   1.0f, 1.0f, 1.0f, 1.0f
        };
    }

    private String firstPresent(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private int toGlWrapMode(WrapMode wrapMode) {
        return switch (wrapMode == null ? WrapMode.CLAMP_TO_EDGE : wrapMode) {
            case CLAMP_TO_EDGE -> GL_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER -> GL_CLAMP_TO_BORDER;
            case REPEAT -> GL_REPEAT;
            case MIRRORED_REPEAT -> GL_MIRRORED_REPEAT;
        };
    }

    private void destroyCurrentFbos() {
        FboHelper.destroy(captureFbo);
        captureFbo = null;
        for (PassTarget target : passTargets) {
            FboHelper.destroy(target.fbo());
        }
        passTargets.clear();
    }

    private void destroyFbos(FboSet fboSet) {
        if (fboSet == null) {
            return;
        }
        FboHelper.destroy(fboSet.captureFbo());
        for (PassTarget target : fboSet.targets()) {
            FboHelper.destroy(target.fbo());
        }
    }

    private void deletePrograms(List<CompiledPass> compiledPasses) {
        for (CompiledPass pass : compiledPasses) {
            if (pass.programId() != 0) {
                glDeleteProgram(pass.programId());
            }
        }
    }

    private void deleteVertexResources(int fragmentVao, int quadVao, int quadVbo) {
        if (fragmentVao != 0) {
            glDeleteVertexArrays(fragmentVao);
        }
        if (quadVao != 0) {
            glDeleteVertexArrays(quadVao);
        }
        if (quadVbo != 0) {
            glDeleteBuffers(quadVbo);
        }
    }

    private void clearGlErrors() {
        while (glGetError() != GL_NO_ERROR) {
            // Drain stale errors so apply failure reflects this pipeline run.
        }
    }

    private void checkGlError(String label) {
        int error = glGetError();
        if (error != GL_NO_ERROR) {
            throw new IllegalStateException(label + " GL error: 0x" + Integer.toHexString(error));
        }
    }

    private record CompiledPass(int programId, GlslShape shape, double scaleX, double scaleY,
                                ScaleType scaleTypeX, ScaleType scaleTypeY,
                                boolean filterLinear, WrapMode wrapMode, Map<String, Float> parameterValues) {
    }

    private record PassTarget(FboHandle fbo, int width, int height) {
    }

    private record FboSet(FboHandle captureFbo, List<PassTarget> targets) {
    }

    private record CombinedQuadResources(int vaoId, int vboId) {
    }

    static final class FrameStatePool {
        private final FrameState[] states = {new FrameState(), new FrameState()};
        private int next;

        FrameState obtain() {
            FrameState state = states[next];
            next ^= 1;
            return state;
        }

        FrameState obtainForTesting(int x, int y, int width, int height) {
            FrameState state = obtain();
            state.setViewport(x, y, width, height);
            return state;
        }
    }

    static final class FrameState {
        private final int[] viewport = new int[4];
        private int readFramebuffer;
        private int drawFramebuffer;
        private int program;
        private int vertexArray;
        private int activeTexture;
        private int activeTextureBinding;
        private int texture0Binding;
        private boolean blendEnabled;
        private boolean depthEnabled;

        private void capture() {
            int activeTexture = glGetInteger(GL_ACTIVE_TEXTURE);
            int activeTextureBinding = glGetInteger(GL_TEXTURE_BINDING_2D);
            int texture0Binding = activeTextureBinding;
            if (activeTexture != GL_TEXTURE0) {
                glActiveTexture(GL_TEXTURE0);
                texture0Binding = glGetInteger(GL_TEXTURE_BINDING_2D);
                glActiveTexture(activeTexture);
            }

            glGetIntegerv(GL_VIEWPORT, viewport);
            readFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            drawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            program = glGetInteger(GL_CURRENT_PROGRAM);
            vertexArray = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            this.activeTexture = activeTexture;
            this.activeTextureBinding = activeTextureBinding;
            this.texture0Binding = texture0Binding;
            blendEnabled = glIsEnabled(GL_BLEND);
            depthEnabled = glIsEnabled(GL_DEPTH_TEST);
        }

        private void restore() {
            FboHelper.restoreViewport(viewport);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, readFramebuffer);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, drawFramebuffer);
            glUseProgram(program);
            glBindVertexArray(vertexArray);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, texture0Binding);
            if (activeTexture != GL_TEXTURE0) {
                glActiveTexture(activeTexture);
                glBindTexture(GL_TEXTURE_2D, activeTextureBinding);
            }

            setCapability(GL_BLEND, blendEnabled);
            setCapability(GL_DEPTH_TEST, depthEnabled);
        }

        private static void setCapability(int capability, boolean enabled) {
            if (enabled) {
                glEnable(capability);
            } else {
                glDisable(capability);
            }
        }

        private void setViewport(int x, int y, int width, int height) {
            viewport[0] = x;
            viewport[1] = y;
            viewport[2] = width;
            viewport[3] = height;
        }

        int viewportAt(int index) {
            return viewport[index];
        }

        Object viewportBackingIdentity() {
            return viewport;
        }
    }
}
