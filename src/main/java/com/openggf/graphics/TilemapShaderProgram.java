package com.openggf.graphics;

import java.io.IOException;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL20.*;

/**
 * Shader program for GPU tilemap rendering.
 */
public class TilemapShaderProgram extends ShaderProgram {
    private static final Logger LOGGER = Logger.getLogger(TilemapShaderProgram.class.getName());
    private boolean loggedVdpWrapHeight = false;
    private int tilemapTextureLocation = -1;
    private int patternLookupLocation = -1;
    private int atlasTextureLocation = -1;
    private int paletteLocation = -1;
    private int underwaterPaletteLocation = -1;
    private int tilemapWidthLocation = -1;
    private int tilemapHeightLocation = -1;
    private int tilemapRingBaseXLocation = -1;
    private int tilemapRingBaseYLocation = -1;
    private int atlasWidthLocation = -1;
    private int atlasHeightLocation = -1;
    private int lookupSizeLocation = -1;
    private int windowWidthLocation = -1;
    private int windowHeightLocation = -1;
    private int viewportWidthLocation = -1;
    private int viewportHeightLocation = -1;
    private int viewportOffsetXLocation = -1;
    private int viewportOffsetYLocation = -1;
    private int worldOffsetXLocation = -1;
    private int worldOffsetYLocation = -1;
    private int wrapYLocation = -1;
    private int priorityPassLocation = -1;
    private int maskOutputLocation = -1;
    private int useUnderwaterPaletteLocation = -1;
    private int waterlineScreenYLocation = -1;
    private int hScrollTextureLocation = -1;
    private int perLineScrollLocation = -1;
    private int vScrollColumnTextureLocation = -1;
    private int perColumnVScrollLocation = -1;
    private int vScrollColumnCountLocation = -1;
    private int screenHeightLocation = -1;
    private int perLineScrollSampleYOffsetPxLocation = -1;
    private int vdpWrapWidthLocation = -1;
    private int vdpWrapHeightLocation = -1;
    private int nametableBaseLocation = -1;
    private int upperBandWrapHeightPxLocation = -1;
    private int upperBandWrapWidthTilesLocation = -1;
    private int frameCounterLocation = -1;
    private int shimmerStyleLocation = -1;

    public TilemapShaderProgram(String fragmentShaderPath) throws IOException {
        super(FULLSCREEN_VERTEX_SHADER, fragmentShaderPath);
    }

    @Override
    public void cacheUniformLocations() {
        super.cacheUniformLocations();
        int programId = getProgramId();
        tilemapTextureLocation = glGetUniformLocation(programId, "TilemapTexture");
        patternLookupLocation = glGetUniformLocation(programId, "PatternLookup");
        atlasTextureLocation = glGetUniformLocation(programId, "AtlasTexture");
        paletteLocation = glGetUniformLocation(programId, "Palette");
        underwaterPaletteLocation = glGetUniformLocation(programId, "UnderwaterPalette");
        tilemapWidthLocation = glGetUniformLocation(programId, "TilemapWidth");
        tilemapHeightLocation = glGetUniformLocation(programId, "TilemapHeight");
        tilemapRingBaseXLocation = glGetUniformLocation(programId, "TilemapRingBaseX");
        tilemapRingBaseYLocation = glGetUniformLocation(programId, "TilemapRingBaseY");
        atlasWidthLocation = glGetUniformLocation(programId, "AtlasWidth");
        atlasHeightLocation = glGetUniformLocation(programId, "AtlasHeight");
        lookupSizeLocation = glGetUniformLocation(programId, "LookupSize");
        windowWidthLocation = glGetUniformLocation(programId, "WindowWidth");
        windowHeightLocation = glGetUniformLocation(programId, "WindowHeight");
        viewportWidthLocation = glGetUniformLocation(programId, "ViewportWidth");
        viewportHeightLocation = glGetUniformLocation(programId, "ViewportHeight");
        viewportOffsetXLocation = glGetUniformLocation(programId, "ViewportOffsetX");
        viewportOffsetYLocation = glGetUniformLocation(programId, "ViewportOffsetY");
        worldOffsetXLocation = glGetUniformLocation(programId, "WorldOffsetX");
        worldOffsetYLocation = glGetUniformLocation(programId, "WorldOffsetY");
        wrapYLocation = glGetUniformLocation(programId, "WrapY");
        priorityPassLocation = glGetUniformLocation(programId, "PriorityPass");
        maskOutputLocation = glGetUniformLocation(programId, "MaskOutput");
        useUnderwaterPaletteLocation = glGetUniformLocation(programId, "UseUnderwaterPalette");
        waterlineScreenYLocation = glGetUniformLocation(programId, "WaterlineScreenY");
        hScrollTextureLocation = glGetUniformLocation(programId, "HScrollTexture");
        perLineScrollLocation = glGetUniformLocation(programId, "PerLineScroll");
        vScrollColumnTextureLocation = glGetUniformLocation(programId, "VScrollColumnTexture");
        perColumnVScrollLocation = glGetUniformLocation(programId, "PerColumnVScroll");
        vScrollColumnCountLocation = glGetUniformLocation(programId, "VScrollColumnCount");
        screenHeightLocation = glGetUniformLocation(programId, "ScreenHeight");
        perLineScrollSampleYOffsetPxLocation = glGetUniformLocation(programId, "PerLineScrollSampleYOffsetPx");
        vdpWrapWidthLocation = glGetUniformLocation(programId, "VDPWrapWidth");
        vdpWrapHeightLocation = glGetUniformLocation(programId, "VDPWrapHeight");
        nametableBaseLocation = glGetUniformLocation(programId, "NametableBase");
        upperBandWrapHeightPxLocation = glGetUniformLocation(programId, "UpperBandWrapHeightPx");
        upperBandWrapWidthTilesLocation = glGetUniformLocation(programId, "UpperBandWrapWidthTiles");
        frameCounterLocation = glGetUniformLocation(programId, "FrameCounter");
        shimmerStyleLocation = glGetUniformLocation(programId, "ShimmerStyle");
    }

    public void setTextureUnits(int tilemapUnit, int lookupUnit, int atlasUnit, int paletteUnit,
            int underwaterPaletteUnit) {
        if (tilemapTextureLocation >= 0) {
            glUniform1i(tilemapTextureLocation, tilemapUnit);
        }
        if (patternLookupLocation >= 0) {
            glUniform1i(patternLookupLocation, lookupUnit);
        }
        if (atlasTextureLocation >= 0) {
            glUniform1i(atlasTextureLocation, atlasUnit);
        }
        if (paletteLocation >= 0) {
            glUniform1i(paletteLocation, paletteUnit);
        }
        if (underwaterPaletteLocation >= 0) {
            glUniform1i(underwaterPaletteLocation, underwaterPaletteUnit);
        }
    }

    public void setTilemapDimensions(float widthTiles, float heightTiles) {
        if (tilemapWidthLocation >= 0) {
            glUniform1f(tilemapWidthLocation, widthTiles);
        }
        if (tilemapHeightLocation >= 0) {
            glUniform1f(tilemapHeightLocation, heightTiles);
        }
    }

    /** @deprecated use {@link #setTilemapRingBase(float, float)}. */
    @Deprecated
    public void setTilemapRingBase(float baseXTiles) {
        setTilemapRingBase(baseXTiles, 0.0f);
    }

    public void setTilemapRingBase(float baseXTiles, float baseYTiles) {
        applyTilemapRingBaseUniforms(baseXTiles, baseYTiles);
    }

    protected void applyTilemapRingBaseUniforms(float baseXTiles, float baseYTiles) {
        if (tilemapRingBaseXLocation >= 0) {
            glUniform1f(tilemapRingBaseXLocation, baseXTiles);
        }
        if (tilemapRingBaseYLocation >= 0) {
            glUniform1f(tilemapRingBaseYLocation, baseYTiles);
        }
    }

    public void setAtlasDimensions(float width, float height) {
        if (atlasWidthLocation >= 0) {
            glUniform1f(atlasWidthLocation, width);
        }
        if (atlasHeightLocation >= 0) {
            glUniform1f(atlasHeightLocation, height);
        }
    }

    public void setLookupSize(float size) {
        if (lookupSizeLocation >= 0) {
            glUniform1f(lookupSizeLocation, size);
        }
    }

    public void setWindowDimensions(float width, float height) {
        if (windowWidthLocation >= 0) {
            glUniform1f(windowWidthLocation, width);
        }
        if (windowHeightLocation >= 0) {
            glUniform1f(windowHeightLocation, height);
        }
    }

    public void setViewport(float offsetX, float offsetY, float width, float height) {
        if (viewportOffsetXLocation >= 0) {
            glUniform1f(viewportOffsetXLocation, offsetX);
        }
        if (viewportOffsetYLocation >= 0) {
            glUniform1f(viewportOffsetYLocation, offsetY);
        }
        if (viewportWidthLocation >= 0) {
            glUniform1f(viewportWidthLocation, width);
        }
        if (viewportHeightLocation >= 0) {
            glUniform1f(viewportHeightLocation, height);
        }
    }

    public void setWorldOffset(float x, float y) {
        if (worldOffsetXLocation >= 0) {
            glUniform1f(worldOffsetXLocation, x);
        }
        if (worldOffsetYLocation >= 0) {
            glUniform1f(worldOffsetYLocation, y);
        }
    }

    public void setWrapY(boolean wrap) {
        if (wrapYLocation >= 0) {
            glUniform1i(wrapYLocation, wrap ? 1 : 0);
        }
    }

    public void setPriorityPass(int pass) {
        if (priorityPassLocation >= 0) {
            glUniform1i(priorityPassLocation, pass);
        }
    }

    public void setMaskOutput(boolean maskOutput) {
        if (maskOutputLocation >= 0) {
            glUniform1i(maskOutputLocation, maskOutput ? 1 : 0);
        }
    }

    public void setHScrollTexture(int textureUnit) {
        if (hScrollTextureLocation >= 0) {
            glUniform1i(hScrollTextureLocation, textureUnit);
        }
    }

    public void setPerLineScroll(boolean enabled) {
        if (perLineScrollLocation >= 0) {
            glUniform1i(perLineScrollLocation, enabled ? 1 : 0);
        }
    }

    public void setVScrollColumnTexture(int textureUnit) {
        if (vScrollColumnTextureLocation >= 0) {
            glUniform1i(vScrollColumnTextureLocation, textureUnit);
        }
    }

    public void setPerColumnVScroll(boolean enabled) {
        if (perColumnVScrollLocation >= 0) {
            glUniform1i(perColumnVScrollLocation, enabled ? 1 : 0);
        }
    }

    public void setVScrollColumnCount(float count) {
        if (vScrollColumnCountLocation >= 0) {
            glUniform1f(vScrollColumnCountLocation, count);
        }
    }

    public void setScreenHeight(float height) {
        if (screenHeightLocation >= 0) {
            glUniform1f(screenHeightLocation, height);
        }
    }

    public void setPerLineScrollSampleYOffsetPx(float offsetPx) {
        if (perLineScrollSampleYOffsetPxLocation >= 0) {
            glUniform1f(perLineScrollSampleYOffsetPxLocation, offsetPx);
        }
    }

    public void setVdpWrapWidth(float width) {
        if (vdpWrapWidthLocation >= 0) {
            glUniform1f(vdpWrapWidthLocation, width);
        }
    }

    public void setVdpWrapHeight(float height) {
        if (!loggedVdpWrapHeight && height > 0) {
            LOGGER.info("VDPWrapHeight uniform location=" + vdpWrapHeightLocation + " value=" + height);
            loggedVdpWrapHeight = true;
        }
        if (vdpWrapHeightLocation >= 0) {
            glUniform1f(vdpWrapHeightLocation, height);
        }
    }

    public void setNametableBase(float base) {
        if (nametableBaseLocation >= 0) {
            glUniform1f(nametableBaseLocation, base);
        }
    }

    public void setUpperBandWrap(float heightPx, float widthTiles) {
        if (upperBandWrapHeightPxLocation >= 0) {
            glUniform1f(upperBandWrapHeightPxLocation, heightPx);
        }
        if (upperBandWrapWidthTilesLocation >= 0) {
            glUniform1f(upperBandWrapWidthTilesLocation, widthTiles);
        }
    }

    public void setShimmerParams(int frameCounter, int shimmerStyle) {
        if (frameCounterLocation >= 0) {
            glUniform1i(frameCounterLocation, frameCounter);
        }
        if (shimmerStyleLocation >= 0) {
            glUniform1i(shimmerStyleLocation, shimmerStyle);
        }
    }

    public void setWaterSplit(boolean useUnderwaterPalette, float waterlineScreenY) {
        if (useUnderwaterPaletteLocation >= 0) {
            glUniform1i(useUnderwaterPaletteLocation, useUnderwaterPalette ? 1 : 0);
        }
        if (waterlineScreenYLocation >= 0) {
            glUniform1f(waterlineScreenYLocation, waterlineScreenY);
        }
    }
}
