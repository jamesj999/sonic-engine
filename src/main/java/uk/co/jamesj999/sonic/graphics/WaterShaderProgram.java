package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import java.io.IOException;

/**
 * Shader program for underwater distortion effects.
 * Extends ShaderProgram to be compatible with PatternRenderCommand.
 */
public class WaterShaderProgram extends ShaderProgram {

    // Uniform locations for water effect
    private int underwaterPaletteLocation = -1;
    private int waterlineScreenYLocation = -1;
    private int frameCounterLocation = -1;
    private int distortionAmplitudeLocation = -1;
    private int screenHeightLocation = -1;
    private int screenWidthLocation = -1;
    private int indexedTextureWidthLocation = -1;
    private int windowHeightLocation = -1;

    // World-space water level for FBO rendering
    private int waterLevelWorldYLocation = -1;
    private int renderWorldYOffsetLocation = -1;
    private int useWorldSpaceWaterLocation = -1;

    public WaterShaderProgram(GL2 gl, String fragmentShaderPath) throws IOException {
        super(gl, fragmentShaderPath);
    }

    public WaterShaderProgram(GL2 gl, String vertexShaderPath, String fragmentShaderPath) throws IOException {
        super(gl, vertexShaderPath, fragmentShaderPath);
    }

    @Override
    public void cacheUniformLocations(GL2 gl) {
        // Cache base uniforms (Palette, IndexedColorTexture, PaletteLine)
        super.cacheUniformLocations(gl);

        int programId = getProgramId();

        // Cache water-specific uniforms
        underwaterPaletteLocation = gl.glGetUniformLocation(programId, "UnderwaterPalette");
        waterlineScreenYLocation = gl.glGetUniformLocation(programId, "WaterlineScreenY");
        frameCounterLocation = gl.glGetUniformLocation(programId, "FrameCounter");
        distortionAmplitudeLocation = gl.glGetUniformLocation(programId, "DistortionAmplitude");
        screenHeightLocation = gl.glGetUniformLocation(programId, "ScreenHeight");
        screenWidthLocation = gl.glGetUniformLocation(programId, "ScreenWidth");
        indexedTextureWidthLocation = gl.glGetUniformLocation(programId, "IndexedTextureWidth");
        windowHeightLocation = gl.glGetUniformLocation(programId, "WindowHeight");

        // World-space uniforms for FBO rendering
        waterLevelWorldYLocation = gl.glGetUniformLocation(programId, "WaterLevelWorldY");
        renderWorldYOffsetLocation = gl.glGetUniformLocation(programId, "RenderWorldYOffset");
        useWorldSpaceWaterLocation = gl.glGetUniformLocation(programId, "UseWorldSpaceWater");
    }

    public int getUnderwaterPaletteLocation() {
        return underwaterPaletteLocation;
    }

    public void setWaterlineScreenY(GL2 gl, float y) {
        if (waterlineScreenYLocation != -1) {
            gl.glUniform1f(waterlineScreenYLocation, y);
        }
    }

    public void setFrameCounter(GL2 gl, int frame) {
        if (frameCounterLocation != -1) {
            gl.glUniform1i(frameCounterLocation, frame);
        }
    }

    public void setDistortionAmplitude(GL2 gl, float amp) {
        if (distortionAmplitudeLocation != -1) {
            gl.glUniform1f(distortionAmplitudeLocation, amp);
        }
    }

    public void setScreenDimensions(GL2 gl, float width, float height) {
        if (screenWidthLocation != -1) {
            gl.glUniform1f(screenWidthLocation, width);
        }
        if (screenHeightLocation != -1) {
            gl.glUniform1f(screenHeightLocation, height);
        }
    }

    public void setIndexedTextureWidth(GL2 gl, float width) {
        if (indexedTextureWidthLocation != -1) {
            gl.glUniform1f(indexedTextureWidthLocation, width);
        }
    }

    public void setWindowHeight(GL2 gl, float height) {
        if (windowHeightLocation != -1) {
            gl.glUniform1f(windowHeightLocation, height);
        }
    }

    /**
     * Set world-space water parameters for FBO rendering.
     * 
     * @param waterLevelWorldY   The water level in world Y coordinates
     * @param renderWorldYOffset The world Y offset for the current render
     *                           (typically camera Y + FBO offset)
     * @param useWorldSpace      If true, use world-space calculation instead of
     *                           screen-space
     */
    public void setWorldSpaceWater(GL2 gl, float waterLevelWorldY, float renderWorldYOffset, boolean useWorldSpace) {
        if (waterLevelWorldYLocation != -1) {
            gl.glUniform1f(waterLevelWorldYLocation, waterLevelWorldY);
        }
        if (renderWorldYOffsetLocation != -1) {
            gl.glUniform1f(renderWorldYOffsetLocation, renderWorldYOffset);
        }
        if (useWorldSpaceWaterLocation != -1) {
            gl.glUniform1i(useWorldSpaceWaterLocation, useWorldSpace ? 1 : 0);
        }
    }
}
