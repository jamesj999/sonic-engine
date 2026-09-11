package com.openggf.graphics.shaderlib;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestDisplayShaderPresetRef {

    @Test
    public void offEntryHasStableLabel() {
        assertEquals("Off", DisplayShaderPresetRef.OFF.label());
    }

    @Test
    public void shaderLabelStripsExtension() {
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.CGP,
                "BizHawk/BizScanlines.cgp",
                Path.of("shaders", "BizHawk", "BizScanlines.cgp"));

        assertEquals("BizHawk/BizScanlines", ref.label());
    }

    @Test
    public void glslpEntryLabelStripsExtension() {
        DisplayShaderPresetRef ref = new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSLP,
                "RetroArch/shaders_glsl/scanlines/scanline.glslp",
                Path.of("shaders", "RetroArch", "shaders_glsl", "scanlines", "scanline.glslp"));

        assertEquals("RetroArch/shaders_glsl/scanlines/scanline", ref.label());
    }
}
