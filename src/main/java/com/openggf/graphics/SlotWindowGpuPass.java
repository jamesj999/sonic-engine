package com.openggf.graphics;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;

/**
 * GPU pass for three scrolling slot windows. The supplied shader must use the
 * fullscreen gl_VertexID contract. Shader and texture ownership stays with the
 * caller; this pass owns its quad and cached bindings. Optional viewport-origin
 * uniforms preserve each fragment shader's existing coordinate convention.
 */
public final class SlotWindowGpuPass {
    private ShaderProgram shader;
    private final QuadRenderer quad = new QuadRenderer();
    private final int[] viewportScratch = new int[4];
    private int locSlotFaceTexture = -1;
    private int locPalette = -1;
    private int locSlotFace0 = -1;
    private int locSlotFace1 = -1;
    private int locSlotFace2 = -1;
    private int locSlotNextFace0 = -1;
    private int locSlotNextFace1 = -1;
    private int locSlotNextFace2 = -1;
    private int locSlotOffset0 = -1;
    private int locSlotOffset1 = -1;
    private int locSlotOffset2 = -1;
    private int locScreenX = -1;
    private int locScreenY = -1;
    private int locScreenWidth = -1;
    private int locScreenHeight = -1;
    private int locPaletteLine = -1;
    private int locTotalPaletteLines = -1;
    private int locViewportOffsetX = -1;
    private int locViewportOffsetY = -1;
    private int locViewportWidth = -1;
    private int locViewportHeight = -1;


    public void setShader(ShaderProgram shader) {
        this.shader = shader;
        resetUniformLocations();
    }

    public void init() {
        quad.init();
        cacheUniformLocations();
    }

    public void cleanup() {
        quad.cleanup();
        resetUniformLocations();
    }

    public void draw(int textureId, int paletteTextureId, int screenX, int screenY, float paletteLine,
                               int face0, int face1, int face2,
                               int nextFace0, int nextFace1, int nextFace2,
                               float offset0, float offset1, float offset2) {
        // Get viewport dimensions to handle scaling
        glGetIntegerv(GL_VIEWPORT, viewportScratch);
        int viewportWidth = viewportScratch[2];
        int viewportHeight = viewportScratch[3];

        // Save OpenGL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthWasEnabled = glIsEnabled(GL_DEPTH_TEST);

        // Set up for shader rendering
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glDisable(GL_DEPTH_TEST);

        // Use the slot shader
        shader.use();

        // Cache uniform locations if needed
        if (locSlotFaceTexture < 0) {
            cacheUniformLocations();
        }

        // Bind textures
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(locSlotFaceTexture, 0);

        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, paletteTextureId);
        glUniform1i(locPalette, 1);

        // Set slot state uniforms
        glUniform1i(locSlotFace0, face0);
        glUniform1i(locSlotFace1, face1);
        glUniform1i(locSlotFace2, face2);

        // Set next face uniforms (for scroll wrapping - faces are non-sequential in sequence)
        glUniform1i(locSlotNextFace0, nextFace0);
        glUniform1i(locSlotNextFace1, nextFace1);
        glUniform1i(locSlotNextFace2, nextFace2);

        glUniform1f(locSlotOffset0, offset0);
        glUniform1f(locSlotOffset1, offset1);
        glUniform1f(locSlotOffset2, offset2);

        // Set screen position uniforms
        glUniform1f(locScreenX, screenX);
        glUniform1f(locScreenY, screenY);
        glUniform1f(locScreenWidth, 320.0f);
        glUniform1f(locScreenHeight, 224.0f);
        glUniform1f(locPaletteLine, paletteLine);
        if (locTotalPaletteLines >= 0) {
            glUniform1f(locTotalPaletteLines, (float) RenderContext.getTotalPaletteLines());
        }

        // Fragment coordinates include the letterbox/pillarbox origin.
        glUniform1f(locViewportOffsetX, viewportScratch[0]);
        glUniform1f(locViewportOffsetY, viewportScratch[1]);

        // Pass actual viewport dimensions for coordinate conversion
        glUniform1f(locViewportWidth, viewportWidth);
        glUniform1f(locViewportHeight, viewportHeight);

        quad.draw(-1f, -1f, 1f, 1f);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        // Stop using shader
        shader.stop();

        // Reset active texture
        glActiveTexture(GL_TEXTURE0);

        // Restore OpenGL state
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (depthWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
    }

    private void cacheUniformLocations() {
        if (shader == null) {
            return;
        }

        int programId = shader.getProgramId();
        locSlotFaceTexture = glGetUniformLocation(programId, "SlotFaceTexture");
        locPalette = glGetUniformLocation(programId, "Palette");
        locSlotFace0 = glGetUniformLocation(programId, "SlotFace0");
        locSlotFace1 = glGetUniformLocation(programId, "SlotFace1");
        locSlotFace2 = glGetUniformLocation(programId, "SlotFace2");
        locSlotNextFace0 = glGetUniformLocation(programId, "SlotNextFace0");
        locSlotNextFace1 = glGetUniformLocation(programId, "SlotNextFace1");
        locSlotNextFace2 = glGetUniformLocation(programId, "SlotNextFace2");
        locSlotOffset0 = glGetUniformLocation(programId, "SlotOffset0");
        locSlotOffset1 = glGetUniformLocation(programId, "SlotOffset1");
        locSlotOffset2 = glGetUniformLocation(programId, "SlotOffset2");
        locScreenX = glGetUniformLocation(programId, "ScreenX");
        locScreenY = glGetUniformLocation(programId, "ScreenY");
        locScreenWidth = glGetUniformLocation(programId, "ScreenWidth");
        locScreenHeight = glGetUniformLocation(programId, "ScreenHeight");
        locPaletteLine = glGetUniformLocation(programId, "PaletteLine");
        locTotalPaletteLines = glGetUniformLocation(programId, "TotalPaletteLines");
        locViewportOffsetX = glGetUniformLocation(programId, "ViewportOffsetX");
        locViewportOffsetY = glGetUniformLocation(programId, "ViewportOffsetY");
        locViewportWidth = glGetUniformLocation(programId, "ViewportWidth");
        locViewportHeight = glGetUniformLocation(programId, "ViewportHeight");

    }

    /**
     * Reset cached uniform locations.
     */
    private void resetUniformLocations() {
        locSlotFaceTexture = -1;
        locPalette = -1;
        locSlotFace0 = -1;
        locSlotFace1 = -1;
        locSlotFace2 = -1;
        locSlotNextFace0 = -1;
        locSlotNextFace1 = -1;
        locSlotNextFace2 = -1;
        locSlotOffset0 = -1;
        locSlotOffset1 = -1;
        locSlotOffset2 = -1;
        locScreenX = -1;
        locScreenY = -1;
        locScreenWidth = -1;
        locScreenHeight = -1;
        locPaletteLine = -1;
        locTotalPaletteLines = -1;
        locViewportOffsetX = -1;
        locViewportOffsetY = -1;
        locViewportWidth = -1;
        locViewportHeight = -1;
    }

}
