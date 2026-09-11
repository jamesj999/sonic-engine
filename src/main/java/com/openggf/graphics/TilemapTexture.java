package com.openggf.graphics;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;

/**
 * 2D texture storing tile descriptors for GPU tilemap rendering.
 */
public class TilemapTexture {
    private int textureId = 0;
    private int widthTiles = 0;
    private int heightTiles = 0;
    private ByteBuffer uploadBuffer;
    private int uploadBufferCapacity;

    public void init(int widthTiles, int heightTiles) {
        if (widthTiles <= 0 || heightTiles <= 0) {
            return;
        }
        if (textureId == 0) {
            textureId = glGenTextures();
        }
        this.widthTiles = widthTiles;
        this.heightTiles = heightTiles;

        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, widthTiles, heightTiles, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public void upload(byte[] data, int widthTiles, int heightTiles) {
        int requiredBytes = checkedTilemapByteCount(widthTiles, heightTiles);
        if (data == null || widthTiles <= 0 || heightTiles <= 0
                || requiredBytes < 0 || requiredBytes > data.length) {
            return;
        }
        if (textureId == 0 || this.widthTiles != widthTiles || this.heightTiles != heightTiles) {
            init(widthTiles, heightTiles);
        }
        ensureUploadCapacity(requiredBytes);
        uploadBuffer.clear();
        uploadBuffer.put(data, 0, requiredBytes);
        uploadBuffer.flip();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, widthTiles, heightTiles,
                GL_RGBA, GL_UNSIGNED_BYTE, uploadBuffer);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /**
     * Uploads a contiguous run of logical source columns into a physical texture
     * column. Rows are packed into caller-independent staging because the source
     * array retains the full tilemap row stride.
     */
    public boolean uploadColumns(byte[] data, int sourceWidthTiles, int heightTiles,
            int sourceColumn, int destinationColumn, int columnCount) {
        int requiredSourceBytes = checkedTilemapByteCount(sourceWidthTiles, heightTiles);
        int uploadBytes = checkedTilemapByteCount(columnCount, heightTiles);
        if (data == null || sourceWidthTiles <= 0 || heightTiles <= 0 || columnCount <= 0
                || sourceColumn < 0 || columnCount > sourceWidthTiles
                || sourceColumn > sourceWidthTiles - columnCount
                || destinationColumn < 0 || destinationColumn > sourceWidthTiles - columnCount
                || requiredSourceBytes < 0 || requiredSourceBytes > data.length
                || uploadBytes < 0
                || !hasStorage(sourceWidthTiles, heightTiles)) {
            return false;
        }
        ensureUploadCapacity(uploadBytes);
        uploadBuffer.clear();
        int sourceRowBytes = sourceWidthTiles * 4;
        int copiedRowBytes = columnCount * 4;
        int sourceOffset = sourceColumn * 4;
        for (int row = 0; row < heightTiles; row++) {
            uploadBuffer.put(data, row * sourceRowBytes + sourceOffset, copiedRowBytes);
        }
        uploadBuffer.flip();
        uploadSubImage(destinationColumn, columnCount, heightTiles, uploadBuffer);
        return true;
    }

    protected void uploadSubImage(int destinationColumn, int columnCount,
            int heightTiles, ByteBuffer packedRows) {
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, destinationColumn, 0, columnCount, heightTiles,
                GL_RGBA, GL_UNSIGNED_BYTE, packedRows);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    /** Uploads contiguous full-width logical rows into physical texture rows. */
    public boolean uploadRows(byte[] data, int widthTiles, int sourceHeightTiles,
            int sourceRow, int destinationRow, int rowCount) {
        int requiredSourceBytes = checkedTilemapByteCount(widthTiles, sourceHeightTiles);
        int uploadBytes = checkedTilemapByteCount(widthTiles, rowCount);
        if (data == null || widthTiles <= 0 || sourceHeightTiles <= 0 || rowCount <= 0
                || sourceRow < 0 || rowCount > sourceHeightTiles
                || sourceRow > sourceHeightTiles - rowCount
                || destinationRow < 0 || destinationRow > sourceHeightTiles - rowCount
                || requiredSourceBytes < 0 || requiredSourceBytes > data.length
                || uploadBytes < 0
                || !hasStorage(widthTiles, sourceHeightTiles)) {
            return false;
        }
        int sourceOffset = sourceRow * widthTiles * 4;
        ensureUploadCapacity(uploadBytes);
        uploadBuffer.clear();
        uploadBuffer.put(data, sourceOffset, uploadBytes);
        uploadBuffer.flip();
        uploadRowsSubImage(destinationRow, widthTiles, rowCount, uploadBuffer);
        return true;
    }

    protected void uploadRowsSubImage(int destinationRow, int widthTiles,
            int rowCount, ByteBuffer contiguousRows) {
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, destinationRow, widthTiles, rowCount,
                GL_RGBA, GL_UNSIGNED_BYTE, contiguousRows);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public boolean hasStorage(int widthTiles, int heightTiles) {
        return textureId != 0 && this.widthTiles == widthTiles && this.heightTiles == heightTiles;
    }

    private static int checkedTilemapByteCount(int widthTiles, int heightTiles) {
        if (widthTiles <= 0 || heightTiles <= 0) {
            return -1;
        }
        try {
            return Math.multiplyExact(Math.multiplyExact(widthTiles, heightTiles), 4);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private void ensureUploadCapacity(int capacity) {
        if (uploadBuffer != null && uploadBufferCapacity >= capacity) {
            return;
        }
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
        }
        uploadBuffer = MemoryUtil.memAlloc(capacity);
        uploadBufferCapacity = capacity;
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

    public void cleanup() {
        if (textureId != 0) {
            glDeleteTextures(textureId);
        }
        textureId = 0;
        widthTiles = 0;
        heightTiles = 0;
        if (uploadBuffer != null) {
            MemoryUtil.memFree(uploadBuffer);
            uploadBuffer = null;
            uploadBufferCapacity = 0;
        }
    }
}
