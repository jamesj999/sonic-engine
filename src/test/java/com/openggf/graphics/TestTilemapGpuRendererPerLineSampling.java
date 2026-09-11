package com.openggf.graphics;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class TestTilemapGpuRendererPerLineSampling {

    @Test
    void perLineTilePassRowsMapBackToVisibleScanlinesAfterVOffset() throws Exception {
        assertEquals(0.0f, invokeResolvePerLineScrollSampleRow(0.0f, 5.0f, 224.0f));
        assertEquals(0.0f, invokeResolvePerLineScrollSampleRow(5.0f, 5.0f, 224.0f));
        assertEquals(1.0f, invokeResolvePerLineScrollSampleRow(6.0f, 5.0f, 224.0f));
        assertEquals(209.0f, invokeResolvePerLineScrollSampleRow(224.0f, 15.0f, 224.0f));
        assertEquals(223.0f, invokeResolvePerLineScrollSampleRow(238.0f, 15.0f, 224.0f));
    }

    @Test
    void scalarTilemapRingBaseCompatibilityPinsYToZero() throws Exception {
        TilemapShaderProgram shader = mock(TilemapShaderProgram.class, CALLS_REAL_METHODS);
        float[] captured = new float[2];
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            captured[1] = invocation.getArgument(1);
            return null;
        }).when(shader).applyTilemapRingBaseUniforms(anyFloat(), anyFloat());

        shader.setTilemapRingBase(7.0f);

        assertEquals(7.0f, captured[0]);
        assertEquals(0.0f, captured[1]);
        assertTrue(TilemapShaderProgram.class
                .getMethod("setTilemapRingBase", float.class)
                .isAnnotationPresent(Deprecated.class));
    }

    private static float invokeResolvePerLineScrollSampleRow(float pixelYFromTop,
                                                             float sampleYOffsetPx,
                                                             float screenHeight) throws Exception {
        Method method = TilemapGpuRenderer.class.getDeclaredMethod(
                "resolvePerLineScrollSampleRow",
                float.class,
                float.class,
                float.class);
        method.setAccessible(true);
        return (float) method.invoke(null, pixelYFromTop, sampleYOffsetPx, screenHeight);
    }
}
