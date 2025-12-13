package uk.co.jamesj999.sonic.graphics;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.IOException;
import java.util.Objects;

import com.jogamp.opengl.GL2;

public class ShaderLoader {
    public static int loadShader(GL2 gl, String filePath, int shaderType) throws IOException {
        // Load the shader source code from the file

        String truePath = Objects.requireNonNull(ShaderLoader.class.getClassLoader().getResource(filePath).getPath());
        if (truePath.contains(":")) {
            truePath = truePath.split("/",2)[1];
        }
        String shaderSource = new String(Files.readAllBytes(Paths.get(truePath)));

        // Create a new shader object
        int shaderId = gl.glCreateShader(shaderType);

        // Pass the shader source to OpenGL
        gl.glShaderSource(shaderId, 1, new String[] { shaderSource }, null);

        // Compile the shader
        gl.glCompileShader(shaderId);

        // Check for compile errors
        int[] compiled = new int[1];
        gl.glGetShaderiv(shaderId, GL2.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            // Compilation failed, retrieve and print the log
            int[] logLength = new int[1];
            gl.glGetShaderiv(shaderId, GL2.GL_INFO_LOG_LENGTH, logLength, 0);
            byte[] log = new byte[logLength[0]];
            gl.glGetShaderInfoLog(shaderId, log.length, null, 0, log, 0);
            System.err.println("Shader compilation failed:\n" + new String(log));
        }

        return shaderId;
    }
}