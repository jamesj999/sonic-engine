package com.openggf.graphics.shaderlib;

import java.util.List;

public record DisplayShaderPreset(String label, ShaderPhase phase, List<DisplayShaderPass> passes) {
    public DisplayShaderPreset {
        passes = List.copyOf(passes);
    }
}
