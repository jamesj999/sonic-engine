package com.openggf.graphics;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL20.*;

public class ShaderLoader {
    private static final Logger LOGGER = Logger.getLogger(ShaderLoader.class.getName());

    public static int loadShader(String filePath, int shaderType) throws IOException {
        // Load the shader source code from the classpath
        String shaderSource = loadShaderSource(filePath);

        // Create a new shader object
        int shaderId = glCreateShader(shaderType);

        // Pass the shader source to OpenGL
        glShaderSource(shaderId, shaderSource);

        // Compile the shader
        glCompileShader(shaderId);

        // Check for compile errors
        int compiled = glGetShaderi(shaderId, GL_COMPILE_STATUS);
        if (compiled == 0) {
            // Compilation failed, retrieve log, clean up the shader object, then throw.
            String log = glGetShaderInfoLog(shaderId);
            glDeleteShader(shaderId);
            LOGGER.severe("Shader compilation failed:\n" + log);
            throw new RuntimeException("Shader compilation failed: " + log);
        }

        return shaderId;
    }

    static String loadShaderSource(String filePath) throws IOException {
        try (InputStream is = ShaderLoader.class.getClassLoader().getResourceAsStream(filePath)) {
            if (is == null) {
                throw new IOException("Shader file not found: " + filePath);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
