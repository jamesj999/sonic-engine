package com.openggf.graphics.shaderlib;

import java.nio.file.Path;

public record DisplayShaderPresetRef(Kind kind, String relativePath, Path absolutePath) {
    public enum Kind {
        OFF,
        CGP,
        GLSLP,
        GLSL
    }

    public static final DisplayShaderPresetRef OFF = new DisplayShaderPresetRef(Kind.OFF, null, null);

    public String label() {
        if (kind == Kind.OFF) {
            return "Off";
        }
        String label = relativePath.replace('\\', '/');
        String lower = label.toLowerCase();
        if (lower.endsWith(".glslp")) {
            return label.substring(0, label.length() - ".glslp".length());
        }
        if (lower.endsWith(".glsl")) {
            return label.substring(0, label.length() - ".glsl".length());
        }
        if (lower.endsWith(".cgp")) {
            return label.substring(0, label.length() - ".cgp".length());
        }
        return label;
    }

    public String shortLabel() {
        if (kind == Kind.OFF) {
            return "Off";
        }
        String label = label();
        int slash = label.lastIndexOf('/');
        return slash < 0 ? label : label.substring(slash + 1);
    }
}
