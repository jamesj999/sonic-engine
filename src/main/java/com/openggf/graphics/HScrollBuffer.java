package com.openggf.graphics;

import com.openggf.util.IntIndexedView;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_R32F;

/**
 * GPU-side horizontal scroll buffer for per-scanline parallax scrolling.
 * Emulates Mega Drive VDP HScroll RAM by storing per-line scroll values
 * in a 1D texture that the parallax shader samples.
 *
 * The texture stores 224 entries (one per visible scanline), with each
 * entry containing the background X scroll offset normalized to -1..1.
 *
 * IMPORTANT: Uses R32F format (32-bit float) instead of R16F because:
 * - 16-bit half-float only has 11 significant bits of mantissa
 * - At high scroll values (e.g., cameraX=25000), precision loss causes
 * visible "jitter" as fractional positions are rounded
 * - 32-bit float provides 23 bits of mantissa, sufficient for sub-pixel
 * precision across the entire level range
 */
public class HScrollBuffer {

    public static final int VISIBLE_LINES = 224;

    private int textureId = -1;
    private final float[] scrollData = new float[VISIBLE_LINES];
    private final boolean foregroundWord;
    private boolean initialized = false;
    // Persistent native staging buffer, reused across frames (BG HScroll uploads
    // every frame). Lazily allocated on first upload (after GL init, so headless
    // code paths never touch native memory); freed in cleanup().
    private FloatBuffer uploadBuffer;

    public HScrollBuffer() {
        this(false);
    }

    public HScrollBuffer(boolean foregroundWord) {
        this.foregroundWord = foregroundWord;
    }

    /**
     * Initialize the OpenGL texture for scroll data.
     * Must be called on the GL thread after context is created.
     */
    public void init() {
        if (initialized) {
            return;
        }

        textureId = glGenTextures();

        glBindTexture(GL_TEXTURE_1D, textureId);

        // Use nearest filtering - we want exact per-line values
        glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        // Clamp to edge - shouldn't sample outside valid range
        glTexParameteri(GL_TEXTURE_1D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);

        // Allocate texture with R32F format for full precision
        // R16F (half-float) only has 11 significant bits, causing jitter at high X
        glTexImage1D(
                GL_TEXTURE_1D,
                0,
                GL_R32F,
                VISIBLE_LINES,
                0,
                GL_RED,
                GL_FLOAT,
                (FloatBuffer) null);

        glBindTexture(GL_TEXTURE_1D, 0);
        initialized = true;
    }

    /**
     * Upload new scroll data to the GPU texture.
     *
     * @param hScroll Packed scroll array from ParallaxManager.
     *                Lower 16 bits contain BG scroll value.
     */
    public void upload(int[] hScroll) {
        if (!initialized || hScroll == null) {
            return;
        }

        stageForUpload(hScroll);

        if (uploadBuffer == null) {
            uploadBuffer = MemoryUtil.memAllocFloat(VISIBLE_LINES);
        }
        uploadBuffer.clear();
        uploadBuffer.put(scrollData);
        uploadBuffer.flip();

        glBindTexture(GL_TEXTURE_1D, textureId);
        glTexSubImage1D(
                GL_TEXTURE_1D,
                0,
                0,
                VISIBLE_LINES,
                GL_RED,
                GL_FLOAT,
                uploadBuffer);
        glBindTexture(GL_TEXTURE_1D, 0);
    }

    void stageForUpload(int[] hScroll) {
        for (int i = 0; i < VISIBLE_LINES; i++) {
            scrollData[i] = normalize(i < hScroll.length ? hScroll[i] : 0);
        }
    }

    float stagedScrollAt(int line) {
        return scrollData[line];
    }

    private float normalize(int packed) {
        int raw = foregroundWord
                ? (short) ((packed >>> 16) & 0xFFFF)
                : (short) (packed & 0xFFFF);
        return Math.max(-1.0f, Math.min(1.0f, raw / 32767.0f));
    }

    /** Uploads a logical-size read-only view, zero-filling missing scanlines. */
    public void upload(IntIndexedView hScroll) {
        if (!initialized || hScroll == null) {
            return;
        }
        for (int i = 0; i < VISIBLE_LINES; i++) {
            int packed = i < hScroll.size() ? hScroll.get(i) : 0;
            scrollData[i] = normalize(packed);
        }
        if (uploadBuffer == null) {
            uploadBuffer = MemoryUtil.memAllocFloat(VISIBLE_LINES);
        }
        uploadBuffer.clear();
        uploadBuffer.put(scrollData);
        uploadBuffer.flip();
        glBindTexture(GL_TEXTURE_1D, textureId);
        glTexSubImage1D(GL_TEXTURE_1D, 0, 0, VISIBLE_LINES, GL_RED, GL_FLOAT, uploadBuffer);
        glBindTexture(GL_TEXTURE_1D, 0);
    }

    /**
     * Bind the scroll texture to a texture unit for shader sampling.
     *
     * @param textureUnit Texture unit index (0-15)
     */
    public void bind(int textureUnit) {
        if (!initialized) {
            return;
        }
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_1D, textureId);
    }

    /**
     * Unbind the scroll texture.
     */
    public void unbind(int textureUnit) {
        glActiveTexture(GL_TEXTURE0 + textureUnit);
        glBindTexture(GL_TEXTURE_1D, 0);
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
    public void cleanup() {
        if (textureId > 0) {
            glDeleteTextures(textureId);
            textureId = -1;
        }
        initialized = false;
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
        }
    }
}
