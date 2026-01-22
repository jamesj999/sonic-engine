package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * GPU renderer that draws a tilemap texture into the current framebuffer.
 */
public class TilemapGpuRenderer {
    private static final Logger LOGGER = Logger.getLogger(TilemapGpuRenderer.class.getName());

    private TilemapShaderProgram shader;
    private final TilemapTexture tilemapTexture = new TilemapTexture();
    private final PatternLookupBuffer patternLookup = new PatternLookupBuffer();

    private byte[] tilemapData;
    private int tilemapWidthTiles;
    private int tilemapHeightTiles;
    private boolean tilemapDirty = false;

    private byte[] lookupData;
    private int lookupSize;
    private boolean lookupDirty = false;

    public void init(GL2 gl, String shaderPath) throws IOException {
        if (gl == null) {
            return;
        }
        if (shader == null) {
            shader = new TilemapShaderProgram(gl, shaderPath);
            shader.cacheUniformLocations(gl);
            LOGGER.info("Tilemap GPU renderer initialized.");
        }
    }

    public void setTilemapData(byte[] data, int widthTiles, int heightTiles) {
        this.tilemapData = data;
        this.tilemapWidthTiles = widthTiles;
        this.tilemapHeightTiles = heightTiles;
        this.tilemapDirty = true;
    }

    public void setPatternLookupData(byte[] data, int size) {
        this.lookupData = data;
        this.lookupSize = size;
        this.lookupDirty = true;
    }

    public void render(GL2 gl,
            int windowWidth,
            int windowHeight,
            float worldOffsetX,
            float worldOffsetY,
            int atlasWidth,
            int atlasHeight,
            int atlasTextureId,
            int paletteTextureId,
            int priorityPass,
            boolean wrapY) {
        if (gl == null || shader == null || tilemapData == null || lookupData == null) {
            return;
        }

        if (tilemapDirty) {
            tilemapTexture.upload(gl, tilemapData, tilemapWidthTiles, tilemapHeightTiles);
            tilemapDirty = false;
        }
        if (lookupDirty) {
            patternLookup.upload(gl, lookupData, lookupSize);
            lookupDirty = false;
        }

        shader.use(gl);
        shader.cacheUniformLocations(gl);

        shader.setTextureUnits(gl, 0, 1, 2, 3);
        shader.setTilemapDimensions(gl, tilemapWidthTiles, tilemapHeightTiles);
        shader.setAtlasDimensions(gl, atlasWidth, atlasHeight);
        shader.setLookupSize(gl, lookupSize);
        shader.setWindowDimensions(gl, windowWidth, windowHeight);
        shader.setWorldOffset(gl, worldOffsetX, worldOffsetY);
        shader.setWrapY(gl, wrapY);
        shader.setPriorityPass(gl, priorityPass);

        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, tilemapTexture.getTextureId());

        gl.glActiveTexture(GL2.GL_TEXTURE1);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, patternLookup.getTextureId());

        gl.glActiveTexture(GL2.GL_TEXTURE2);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, atlasTextureId);

        gl.glActiveTexture(GL2.GL_TEXTURE3);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, paletteTextureId);

        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(0, 0);
        gl.glVertex2f(windowWidth, 0);
        gl.glVertex2f(windowWidth, windowHeight);
        gl.glVertex2f(0, windowHeight);
        gl.glEnd();

        gl.glActiveTexture(GL2.GL_TEXTURE3);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl.glActiveTexture(GL2.GL_TEXTURE2);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
        gl.glActiveTexture(GL2.GL_TEXTURE1);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, 0);
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);

        shader.stop(gl);
    }

    public void cleanup(GL2 gl) {
        if (shader != null) {
            shader.cleanup(gl);
            shader = null;
        }
        tilemapTexture.cleanup(gl);
        patternLookup.cleanup(gl);
    }
}
