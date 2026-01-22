package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;

import java.io.IOException;

/**
 * Shader program for GPU tilemap rendering.
 */
public class TilemapShaderProgram extends ShaderProgram {
    private int tilemapTextureLocation = -1;
    private int patternLookupLocation = -1;
    private int atlasTextureLocation = -1;
    private int paletteLocation = -1;
    private int underwaterPaletteLocation = -1;
    private int tilemapWidthLocation = -1;
    private int tilemapHeightLocation = -1;
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
    private int useUnderwaterPaletteLocation = -1;
    private int waterlineScreenYLocation = -1;

    public TilemapShaderProgram(GL2 gl, String fragmentShaderPath) throws IOException {
        super(gl, fragmentShaderPath);
    }

    @Override
    public void cacheUniformLocations(GL2 gl) {
        super.cacheUniformLocations(gl);
        int programId = getProgramId();
        tilemapTextureLocation = gl.glGetUniformLocation(programId, "TilemapTexture");
        patternLookupLocation = gl.glGetUniformLocation(programId, "PatternLookup");
        atlasTextureLocation = gl.glGetUniformLocation(programId, "AtlasTexture");
        paletteLocation = gl.glGetUniformLocation(programId, "Palette");
        underwaterPaletteLocation = gl.glGetUniformLocation(programId, "UnderwaterPalette");
        tilemapWidthLocation = gl.glGetUniformLocation(programId, "TilemapWidth");
        tilemapHeightLocation = gl.glGetUniformLocation(programId, "TilemapHeight");
        atlasWidthLocation = gl.glGetUniformLocation(programId, "AtlasWidth");
        atlasHeightLocation = gl.glGetUniformLocation(programId, "AtlasHeight");
        lookupSizeLocation = gl.glGetUniformLocation(programId, "LookupSize");
        windowWidthLocation = gl.glGetUniformLocation(programId, "WindowWidth");
        windowHeightLocation = gl.glGetUniformLocation(programId, "WindowHeight");
        viewportWidthLocation = gl.glGetUniformLocation(programId, "ViewportWidth");
        viewportHeightLocation = gl.glGetUniformLocation(programId, "ViewportHeight");
        viewportOffsetXLocation = gl.glGetUniformLocation(programId, "ViewportOffsetX");
        viewportOffsetYLocation = gl.glGetUniformLocation(programId, "ViewportOffsetY");
        worldOffsetXLocation = gl.glGetUniformLocation(programId, "WorldOffsetX");
        worldOffsetYLocation = gl.glGetUniformLocation(programId, "WorldOffsetY");
        wrapYLocation = gl.glGetUniformLocation(programId, "WrapY");
        priorityPassLocation = gl.glGetUniformLocation(programId, "PriorityPass");
        useUnderwaterPaletteLocation = gl.glGetUniformLocation(programId, "UseUnderwaterPalette");
        waterlineScreenYLocation = gl.glGetUniformLocation(programId, "WaterlineScreenY");
    }

    public void setTextureUnits(GL2 gl, int tilemapUnit, int lookupUnit, int atlasUnit, int paletteUnit,
            int underwaterPaletteUnit) {
        if (tilemapTextureLocation >= 0) {
            gl.glUniform1i(tilemapTextureLocation, tilemapUnit);
        }
        if (patternLookupLocation >= 0) {
            gl.glUniform1i(patternLookupLocation, lookupUnit);
        }
        if (atlasTextureLocation >= 0) {
            gl.glUniform1i(atlasTextureLocation, atlasUnit);
        }
        if (paletteLocation >= 0) {
            gl.glUniform1i(paletteLocation, paletteUnit);
        }
        if (underwaterPaletteLocation >= 0) {
            gl.glUniform1i(underwaterPaletteLocation, underwaterPaletteUnit);
        }
    }

    public void setTilemapDimensions(GL2 gl, float widthTiles, float heightTiles) {
        if (tilemapWidthLocation >= 0) {
            gl.glUniform1f(tilemapWidthLocation, widthTiles);
        }
        if (tilemapHeightLocation >= 0) {
            gl.glUniform1f(tilemapHeightLocation, heightTiles);
        }
    }

    public void setAtlasDimensions(GL2 gl, float width, float height) {
        if (atlasWidthLocation >= 0) {
            gl.glUniform1f(atlasWidthLocation, width);
        }
        if (atlasHeightLocation >= 0) {
            gl.glUniform1f(atlasHeightLocation, height);
        }
    }

    public void setLookupSize(GL2 gl, float size) {
        if (lookupSizeLocation >= 0) {
            gl.glUniform1f(lookupSizeLocation, size);
        }
    }

    public void setWindowDimensions(GL2 gl, float width, float height) {
        if (windowWidthLocation >= 0) {
            gl.glUniform1f(windowWidthLocation, width);
        }
        if (windowHeightLocation >= 0) {
            gl.glUniform1f(windowHeightLocation, height);
        }
    }

    public void setViewport(GL2 gl, float offsetX, float offsetY, float width, float height) {
        if (viewportOffsetXLocation >= 0) {
            gl.glUniform1f(viewportOffsetXLocation, offsetX);
        }
        if (viewportOffsetYLocation >= 0) {
            gl.glUniform1f(viewportOffsetYLocation, offsetY);
        }
        if (viewportWidthLocation >= 0) {
            gl.glUniform1f(viewportWidthLocation, width);
        }
        if (viewportHeightLocation >= 0) {
            gl.glUniform1f(viewportHeightLocation, height);
        }
    }

    public void setWorldOffset(GL2 gl, float x, float y) {
        if (worldOffsetXLocation >= 0) {
            gl.glUniform1f(worldOffsetXLocation, x);
        }
        if (worldOffsetYLocation >= 0) {
            gl.glUniform1f(worldOffsetYLocation, y);
        }
    }

    public void setWrapY(GL2 gl, boolean wrap) {
        if (wrapYLocation >= 0) {
            gl.glUniform1i(wrapYLocation, wrap ? 1 : 0);
        }
    }

    public void setPriorityPass(GL2 gl, int pass) {
        if (priorityPassLocation >= 0) {
            gl.glUniform1i(priorityPassLocation, pass);
        }
    }

    public void setWaterSplit(GL2 gl, boolean useUnderwaterPalette, float waterlineScreenY) {
        if (useUnderwaterPaletteLocation >= 0) {
            gl.glUniform1i(useUnderwaterPaletteLocation, useUnderwaterPalette ? 1 : 0);
        }
        if (waterlineScreenYLocation >= 0) {
            gl.glUniform1f(waterlineScreenYLocation, waterlineScreenY);
        }
    }
}
