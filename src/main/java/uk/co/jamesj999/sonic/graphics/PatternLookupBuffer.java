package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import java.nio.ByteBuffer;

/**
 * 1D texture that maps pattern index to atlas tile coordinates.
 * Stores RGBA8 where R=tileX, G=tileY.
 */
public class PatternLookupBuffer {
    private int textureId = 0;
    private int size = 0;

    public void init(GL2 gl, int size) {
        if (gl == null || size <= 0) {
            return;
        }
        if (textureId == 0) {
            int[] textures = new int[1];
            gl.glGenTextures(1, textures, 0);
            textureId = textures[0];
        }
        this.size = size;

        gl.glBindTexture(GL2.GL_TEXTURE_1D, textureId);
        gl.glTexImage1D(GL2.GL_TEXTURE_1D, 0, GL2.GL_RGBA8, size, 0, GL2.GL_RGBA,
                GL2.GL_UNSIGNED_BYTE, null);
        gl.glTexParameteri(GL2.GL_TEXTURE_1D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL2.GL_TEXTURE_1D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_1D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, 0);
    }

    public void upload(GL2 gl, byte[] data, int size) {
        if (gl == null || data == null || size <= 0) {
            return;
        }
        if (textureId == 0 || this.size != size) {
            init(gl, size);
        }
        ByteBuffer buffer = GLBuffers.newDirectByteBuffer(data.length);
        buffer.put(data);
        buffer.flip();
        gl.glBindTexture(GL2.GL_TEXTURE_1D, textureId);
        gl.glTexSubImage1D(GL2.GL_TEXTURE_1D, 0, 0, size, GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, buffer);
        gl.glBindTexture(GL2.GL_TEXTURE_1D, 0);
    }

    public int getTextureId() {
        return textureId;
    }

    public int getSize() {
        return size;
    }

    public void cleanup(GL2 gl) {
        if (gl != null && textureId != 0) {
            gl.glDeleteTextures(1, new int[] { textureId }, 0);
        }
        textureId = 0;
        size = 0;
    }
}
