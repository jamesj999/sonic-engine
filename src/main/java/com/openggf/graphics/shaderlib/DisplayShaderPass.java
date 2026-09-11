package com.openggf.graphics.shaderlib;

import java.util.Map;

public record DisplayShaderPass(
        String vertexSource,
        String fragmentSource,
        GlslShape shape,
        double scaleX,
        double scaleY,
        ScaleType scaleTypeX,
        ScaleType scaleTypeY,
        boolean filterLinear,
        WrapMode wrapMode,
        Map<String, Float> parameterValues) {

    public DisplayShaderPass {
        parameterValues = parameterValues == null || parameterValues.isEmpty()
                ? Map.of()
                : Map.copyOf(parameterValues);
    }

    public DisplayShaderPass(String vertexSource, String fragmentSource, GlslShape shape,
                             double scaleX, double scaleY, ScaleType scaleTypeX, ScaleType scaleTypeY,
                             boolean filterLinear, WrapMode wrapMode) {
        this(vertexSource, fragmentSource, shape, scaleX, scaleY, scaleTypeX, scaleTypeY,
                filterLinear, wrapMode, Map.of());
    }

    public DisplayShaderPass(String vertexSource, String fragmentSource, GlslShape shape,
                             double scale, ScaleType scaleType, boolean filterLinear, WrapMode wrapMode,
                             Map<String, Float> parameterValues) {
        this(vertexSource, fragmentSource, shape, scale, scale, scaleType, scaleType,
                filterLinear, wrapMode, parameterValues);
    }

    public DisplayShaderPass(String vertexSource, String fragmentSource, GlslShape shape,
                             double scale, ScaleType scaleType, boolean filterLinear, WrapMode wrapMode) {
        this(vertexSource, fragmentSource, shape, scale, scaleType, filterLinear, wrapMode, Map.of());
    }

    public double scale() {
        return scaleX;
    }

    public ScaleType scaleType() {
        return scaleTypeX;
    }
}
