package com.openggf.graphics.shaderlib;

public class UnsupportedShaderException extends DisplayShaderLoadException {
    public UnsupportedShaderException(String message) {
        super(message);
    }

    public UnsupportedShaderException(String message, Throwable cause) {
        super(message, cause);
    }
}
