package com.openggf.graphics;

import java.nio.ByteBuffer;

/** Test support for observing the headless underwater palette upload boundary. */
public final class RecordingUnderwaterPaletteUploadOps implements GraphicsManager.UnderwaterPaletteUploadOps {
    private int uploadCount;

    public static RecordingUnderwaterPaletteUploadOps install(GraphicsManager graphics) {
        RecordingUnderwaterPaletteUploadOps recorder = new RecordingUnderwaterPaletteUploadOps();
        graphics.setUnderwaterPaletteUploadOps(recorder);
        return recorder;
    }

    public int uploadCount() {
        return uploadCount;
    }

    @Override
    public int createTexture() {
        return 73;
    }

    @Override
    public void configureTexture(int textureId) {
    }

    @Override
    public void uploadTexture(int textureId, int totalLines, ByteBuffer rgbaBytes) {
        uploadCount++;
    }
}
