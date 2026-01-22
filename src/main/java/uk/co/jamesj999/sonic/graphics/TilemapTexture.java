package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import java.nio.ByteBuffer;

/**
 * 2D texture storing tile descriptors for GPU tilemap rendering.
 */
public class TilemapTexture {
    private int textureId = 0;
    private int widthTiles = 0;
    private int heightTiles = 0;

    public void init(GL2 gl, int widthTiles, int heightTiles) {
        if (gl == null || widthTiles <= 0 || heightTiles <= 0) {
            return;
        }
        if (textureId == 0) {
            int[] textures = new int[1];
            gl.glGenTextures(1, textures, 0);
            textureId = textures[0];
        }
        this.widthTiles = widthTiles;
        this.heightTiles = heightTiles;

        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_RGBA8, widthTiles, heightTiles, 0,
                GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, null);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_REPEAT);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_REPEAT);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
    }

    public void upload(GL2 gl, byte[] data, int widthTiles, int heightTiles) {
        if (gl == null || data == null || widthTiles <= 0 || heightTiles <= 0) {
            return;
        }
        if (textureId == 0 || this.widthTiles != widthTiles || this.heightTiles != heightTiles) {
            init(gl, widthTiles, heightTiles);
        }
        ByteBuffer buffer = GLBuffers.newDirectByteBuffer(data.length);
        buffer.put(data);
        buffer.flip();
        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);
        gl.glTexSubImage2D(GL2.GL_TEXTURE_2D, 0, 0, 0, widthTiles, heightTiles,
                GL2.GL_RGBA, GL2.GL_UNSIGNED_BYTE, buffer);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, 0);
    }

    public int getTextureId() {
        return textureId;
    }

    public int getWidthTiles() {
        return widthTiles;
    }

    public int getHeightTiles() {
        return heightTiles;
    }

    public void cleanup(GL2 gl) {
        if (gl != null && textureId != 0) {
            gl.glDeleteTextures(1, new int[] { textureId }, 0);
        }
        textureId = 0;
        widthTiles = 0;
        heightTiles = 0;
    }
}
