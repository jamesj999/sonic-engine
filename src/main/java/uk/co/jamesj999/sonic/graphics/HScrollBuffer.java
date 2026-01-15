package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;

import java.nio.FloatBuffer;

/**
 * GPU-side horizontal scroll buffer for per-scanline parallax scrolling.
 * Emulates Mega Drive VDP HScroll RAM by storing per-line scroll values
 * in a 1D texture that the parallax shader samples.
 * 
 * The texture stores 224 entries (one per visible scanline), with each
 * entry containing the background X scroll offset normalized to -1..1.
 */
public class HScrollBuffer {

    public static final int VISIBLE_LINES = 224;

    private int textureId = -1;
    private final float[] scrollData = new float[VISIBLE_LINES];
    private boolean initialized = false;

    /**
     * Initialize the OpenGL texture for scroll data.
     * Must be called on the GL thread after context is created.
     */
    public void init(GL2 gl) {
        if (initialized) {
            return;
        }

        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        textureId = textures[0];

        gl.glBindTexture(GL2.GL_TEXTURE_1D, textureId);

        // Use nearest filtering - we want exact per-line values
        gl.glTexParameteri(GL2.GL_TEXTURE_1D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_1D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);

        // Clamp to edge - shouldn't sample outside valid range
        gl.glTexParameteri(GL2.GL_TEXTURE_1D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);

        // Allocate texture with R16F format for normalized float values
        gl.glTexImage1D(
                GL2.GL_TEXTURE_1D,
                0,
                GL2.GL_R16F,
                VISIBLE_LINES,
                0,
                GL2.GL_RED,
                GL2.GL_FLOAT,
                null);

        gl.glBindTexture(GL2.GL_TEXTURE_1D, 0);
        initialized = true;
    }

    /**
     * Upload new scroll data to the GPU texture.
     * 
     * @param hScroll Packed scroll array from ParallaxManager.
     *                Lower 16 bits contain BG scroll value.
     */
    public void upload(GL2 gl, int[] hScroll) {
        if (!initialized || hScroll == null) {
            return;
        }

        // Extract BG scroll values (lower 16 bits) and normalize to -1..1
        for (int i = 0; i < VISIBLE_LINES && i < hScroll.length; i++) {
            int raw = (short) (hScroll[i] & 0xFFFF);
            float normalized = raw / 32767.0f;
            if (normalized > 1.0f) {
                normalized = 1.0f;
            } else if (normalized < -1.0f) {
                normalized = -1.0f;
            }
            scrollData[i] = normalized;
        }

        FloatBuffer buffer = FloatBuffer.wrap(scrollData);

        gl.glBindTexture(GL2.GL_TEXTURE_1D, textureId);
        gl.glTexSubImage1D(
                GL2.GL_TEXTURE_1D,
                0,
                0,
                VISIBLE_LINES,
                GL2.GL_RED,
                GL2.GL_FLOAT,
                buffer);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, 0);
    }

    /**
     * Bind the scroll texture to a texture unit for shader sampling.
     * 
     * @param gl          OpenGL context
     * @param textureUnit Texture unit index (0-15)
     */
    public void bind(GL2 gl, int textureUnit) {
        if (!initialized) {
            return;
        }
        gl.glActiveTexture(GL2.GL_TEXTURE0 + textureUnit);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, textureId);
    }

    /**
     * Unbind the scroll texture.
     */
    public void unbind(GL2 gl, int textureUnit) {
        gl.glActiveTexture(GL2.GL_TEXTURE0 + textureUnit);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, 0);
    }

    /**
     * Get the OpenGL texture ID.
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * Check if the buffer has been initialized.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Clean up OpenGL resources.
     */
    public void cleanup(GL2 gl) {
        if (textureId > 0) {
            gl.glDeleteTextures(1, new int[] { textureId }, 0);
            textureId = -1;
        }
        initialized = false;
    }
}
