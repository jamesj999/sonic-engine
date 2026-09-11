package com.openggf.graphics;

import java.io.IOException;

import static org.lwjgl.opengl.GL20.*;

/**
 * Shader program for sprite-to-tile priority compositing.
 *
 * This shader extends the standard hedgehog pattern shader to support
 * ROM-accurate sprite-to-tile layering. Low-priority sprites (isHighPriority=false)
 * are hidden behind high-priority foreground tiles, while high-priority sprites
 * always render on top of all tiles.
 *
 * This allows sprite-to-sprite ordering (via buckets 0-7) to work independently
 * of sprite-to-tile layering (via isHighPriority flag).
 */
public class SpritePriorityShaderProgram extends ShaderProgram {

    // Uniform locations for priority compositing
    private int tilePriorityTextureLocation = -1;
    private int tileOcclusionPaletteMaskLocation = -1;
    private int screenSizeLocation = -1;
    private int viewportOffsetLocation = -1;
    // Uniform locations for underwater palette support
    private int underwaterPaletteLocation = -1;
    private int waterlineScreenYLocation = -1;
    private int windowHeightLocation = -1;
    private int screenHeightLocation = -1;
    private int waterEnabledLocation = -1;

    private static final String BASIC_VERTEX_SHADER = "shaders/shader_basic.vert";

    public SpritePriorityShaderProgram(String fragmentShaderPath) throws IOException {
        super(BASIC_VERTEX_SHADER, fragmentShaderPath);
    }

    @Override
    public void cacheUniformLocations() {
        // Cache base uniforms (Palette, IndexedColorTexture, PaletteLine)
        super.cacheUniformLocations();

        int programId = getProgramId();

        // Cache priority-specific uniforms
        tilePriorityTextureLocation = glGetUniformLocation(programId, "TilePriorityTexture");
        tileOcclusionPaletteMaskLocation = glGetUniformLocation(programId, "TileOcclusionPaletteMask");
        screenSizeLocation = glGetUniformLocation(programId, "ScreenSize");
        viewportOffsetLocation = glGetUniformLocation(programId, "ViewportOffset");
        // Cache underwater palette uniforms
        underwaterPaletteLocation = glGetUniformLocation(programId, "UnderwaterPalette");
        waterlineScreenYLocation = glGetUniformLocation(programId, "WaterlineScreenY");
        windowHeightLocation = glGetUniformLocation(programId, "WindowHeight");
        screenHeightLocation = glGetUniformLocation(programId, "ScreenHeight");
        waterEnabledLocation = glGetUniformLocation(programId, "WaterEnabled");
    }

    /**
     * Get the uniform location for the tile priority texture sampler.
     */
    public int getTilePriorityTextureLocation() {
        return tilePriorityTextureLocation;
    }

    /**
     * Set the tile priority texture sampler unit.
     */
    public void setTilePriorityTexture(int textureUnit) {
        if (tilePriorityTextureLocation != -1) {
            glUniform1i(tilePriorityTextureLocation, textureUnit);
        }
    }

    /**
     * Set whether the current sprite has high priority (appears above all tiles).
     *
     * @param highPriority true if sprite should appear above all tiles, false if it
     *                     should appear behind high-priority tiles
     */
    public void setSpriteHighPriority(boolean highPriority) {
        setTileOcclusionPaletteMask(highPriority ? 0 : 0xF);
    }

    public void setTileOcclusionPaletteMask(int mask) {
        if (tileOcclusionPaletteMaskLocation != -1) {
            glUniform1i(tileOcclusionPaletteMaskLocation, mask & 0xF);
        }
    }

    /**
     * Set the screen dimensions for coordinate lookup in the priority texture.
     */
    public void setScreenSize(float width, float height) {
        if (screenSizeLocation != -1) {
            glUniform2f(screenSizeLocation, width, height);
        }
    }

    /**
     * Set the viewport offset in window coordinates.
     * This is needed because gl_FragCoord is in window coordinates, not viewport-local.
     * When the viewport is letterboxed/centered, the offset is non-zero.
     */
    public void setViewportOffset(float x, float y) {
        if (viewportOffsetLocation != -1) {
            glUniform2f(viewportOffsetLocation, x, y);
        }
    }

    /**
     * Get the uniform location for the underwater palette texture sampler.
     */
    public int getUnderwaterPaletteLocation() {
        return underwaterPaletteLocation;
    }

    /**
     * Set the waterline screen Y position (pixels from top of screen).
     * Set to a negative value to disable underwater palette switching.
     */
    public void setWaterlineScreenY(float y) {
        if (waterlineScreenYLocation != -1) {
            glUniform1f(waterlineScreenYLocation, y);
        }
    }

    /**
     * Set the physical window height in pixels.
     */
    public void setWindowHeight(float height) {
        if (windowHeightLocation != -1) {
            glUniform1f(windowHeightLocation, height);
        }
    }

    /**
     * Set the logical screen height (e.g., 224 for Genesis).
     */
    public void setScreenHeight(float height) {
        if (screenHeightLocation != -1) {
            glUniform1f(screenHeightLocation, height);
        }
    }

    /**
     * Set whether water is enabled in the current zone.
     */
    public void setWaterEnabled(boolean enabled) {
        if (waterEnabledLocation != -1) {
            glUniform1i(waterEnabledLocation, enabled ? 1 : 0);
        }
    }
}
