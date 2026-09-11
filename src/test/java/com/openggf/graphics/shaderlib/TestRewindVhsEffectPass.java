package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewindVhsEffectPass {

    @Test
    public void builtInPresetLoadsAndDeclaresRequiredUniforms() throws IOException {
        DisplayShaderPreset preset = RewindVhsEffectPass.builtInPreset();

        assertEquals(1, preset.passes().size(), "VHS effect is a single fragment-only pass");
        DisplayShaderPass pass = preset.passes().get(0);
        assertEquals(GlslShape.FRAGMENT_ONLY, pass.shape());
        assertEquals(ScaleType.VIEWPORT, pass.scaleTypeX(), "must not downsample the captured frame");

        String fragment = pass.fragmentSource();
        assertTrue(fragment.contains("uniform sampler2D Texture"), "missing Texture uniform");
        assertTrue(fragment.contains("uniform vec2 TextureSize"), "missing TextureSize uniform");
        assertTrue(fragment.contains("uniform vec2 OutputSize"), "missing OutputSize uniform");
        assertTrue(fragment.contains("uniform int FrameCount"), "missing FrameCount uniform");
        assertTrue(fragment.contains("uniform float RewindIntensity"), "missing RewindIntensity uniform");
        assertTrue(fragment.contains("uniform float RewindSpeed"), "missing RewindSpeed uniform");
        assertTrue(fragment.contains("uniform float RewindScroll"), "missing RewindScroll uniform");
        assertTrue(fragment.contains("uniform float RewindTearBands"), "missing RewindTearBands uniform");
    }

    @Test
    public void scrollPhaseAdvancesByRateTimesSpeedAndWraps() {
        assertEquals(0.006f, RewindVhsEffectPass.advanceScrollPhase(0.0f, 1.0f), 1e-6f);
        assertEquals(0.012f, RewindVhsEffectPass.advanceScrollPhase(0.0f, 2.0f), 1e-6f);
        assertEquals(0.003f, RewindVhsEffectPass.advanceScrollPhase(0.0f, 0.5f), 1e-6f);
        // wraps back into 0..1
        assertEquals(0.002f, RewindVhsEffectPass.advanceScrollPhase(0.996f, 1.0f), 1e-6f);
    }

    @Test
    public void scrollPhasePositionPersistsAcrossSpeedChanges() {
        // A speed change must alter the rate only — the phase continues from where
        // it was, never recomputed from absolute time.
        float phase = 0.5f;
        phase = RewindVhsEffectPass.advanceScrollPhase(phase, 1.0f);
        float afterNormal = phase;
        phase = RewindVhsEffectPass.advanceScrollPhase(phase, 0.5f);
        assertEquals(afterNormal + 0.003f, phase, 1e-6f);
    }

    @Test
    public void scrollPhaseRunsBackwardForAFastForwardTransport() {
        // Fast-forward scrolls the bands the other way at the same rate.
        assertEquals(0.244f, RewindVhsEffectPass.advanceScrollPhase(0.25f, -1.0f), 1e-6f);
        assertEquals(0.238f, RewindVhsEffectPass.advanceScrollPhase(0.25f, -2.0f), 1e-6f);
    }

    @Test
    public void scrollPhaseWrapsBackwardBelowZero() {
        assertEquals(0.998f, RewindVhsEffectPass.advanceScrollPhase(0.004f, -1.0f), 1e-6f);
    }
}
