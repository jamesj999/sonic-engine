package com.openggf.graphics;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class TestTexturedQuadRenderer {

    @Test
    void writeQuadVertices_writesExpectedTriangleDataWithoutIntermediateArrays() {
        FloatBuffer buffer = MemoryUtil.memAllocFloat(24);
        try {
            TexturedQuadRenderer.writeQuadVertices(buffer,
                    10f, 20f, 30f, 40f,
                    0.1f, 0.2f, 0.3f, 0.4f);

            float[] actual = new float[24];
            buffer.get(actual);

            assertArrayEquals(new float[] {
                    10f, 60f, 0.1f, 0.4f,
                    10f, 20f, 0.1f, 0.2f,
                    40f, 20f, 0.3f, 0.2f,
                    10f, 60f, 0.1f, 0.4f,
                    40f, 20f, 0.3f, 0.2f,
                    40f, 60f, 0.3f, 0.4f
            }, actual);
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    @Test
    void writeQuadVerticesAtOffset_appendsSequentialQuadsWithoutClearing() {
        float[] actual = new float[48];

        TexturedQuadRenderer.writeQuadVerticesAtOffset(actual, 0,
                10f, 20f, 30f, 40f,
                0.1f, 0.2f, 0.3f, 0.4f);
        TexturedQuadRenderer.writeQuadVerticesAtOffset(actual, 24,
                50f, 60f, 70f, 80f,
                0.5f, 0.6f, 0.7f, 0.8f);

        assertArrayEquals(new float[] {
                10f, 60f, 0.1f, 0.4f,
                10f, 20f, 0.1f, 0.2f,
                40f, 20f, 0.3f, 0.2f,
                10f, 60f, 0.1f, 0.4f,
                40f, 20f, 0.3f, 0.2f,
                40f, 60f, 0.3f, 0.4f,
                50f, 140f, 0.5f, 0.8f,
                50f, 60f, 0.5f, 0.6f,
                120f, 60f, 0.7f, 0.6f,
                50f, 140f, 0.5f, 0.8f,
                120f, 60f, 0.7f, 0.6f,
                120f, 140f, 0.7f, 0.8f
        }, actual);
    }
}
