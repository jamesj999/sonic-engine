package uk.co.jamesj999.sonic.graphics;

import org.lwjgl.opengl.GL20;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class ShaderProgram {

    private int programId;

    /**
     * Initializes a shader program with both vertex and fragment shaders.
     */
    public ShaderProgram(Shader... shaders) throws IOException {

        programId = GL20.glCreateProgram();

        for (Shader shader : shaders) {
            int shaderType = shader.getType().gl20;
            int shaderId = loadShader(shader.getPath(), shaderType);
            GL20.glAttachShader(programId, shaderId);
        }

        GL20.glLinkProgram(programId);

        if (GL20.glGetProgrami(programId, GL20.GL_LINK_STATUS) == 0) {
            throw new IllegalStateException("Shader program linking failed: " + GL20.glGetProgramInfoLog(programId));
        }
    }

    /**
     * Activates the shader program stored by its key.
     */
    public void use() {
        GL20.glUseProgram(programId);
    }

    /**
     * Stops the current shader program.
     */
    public void stop() {
        GL20.glUseProgram(0);
    }

    /**
     * Cleans up all shader programs.
     */
    public void cleanup() {
        GL20.glDeleteProgram(programId);
        programId = 0;
    }

    public int getProgramId() {
        return programId;
    }

    public static int loadShader(String filePath, int shaderType) throws IOException {
        // Load the shader source code from the file
        String truePath = Objects.requireNonNull(ShaderProgram.class.getClassLoader().getResource(filePath)).getPath();
        if (truePath.contains(":")) {
            truePath = truePath.split("/", 2)[1];
        }
        String shaderSource = new String(Files.readAllBytes(Paths.get(truePath)));

        // Create a new shader object
        int shaderId = GL20.glCreateShader(shaderType);

        // Pass the shader source to OpenGL
        GL20.glShaderSource(shaderId, shaderSource);

        // Compile the shader
        GL20.glCompileShader(shaderId);

        // Check for compile errors
        int compiled = GL20.glGetShaderi(shaderId, GL20.GL_COMPILE_STATUS);
        if (compiled == 0) {
            // Compilation failed, retrieve and print the log
            String log = GL20.glGetShaderInfoLog(shaderId);
            System.err.println("Shader compilation failed:\n" + log);
        }

        return shaderId;
    }


}
