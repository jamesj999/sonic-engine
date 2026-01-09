package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import java.io.IOException;

public class ShaderProgram {
    private int programId;

    // Cached uniform locations for pattern rendering
    private int paletteLocation = -1;
    private int indexedColorTextureLocation = -1;
    private int paletteLineLocation = -1;
    private boolean uniformsCached = false;

    public int getProgramId() {
        return programId;
    }

    public void setProgramId(int programId) {
        this.programId = programId;
        // Invalidate cached locations when program changes
        uniformsCached = false;
    }

    /**
     * Cache uniform locations for efficient repeated access.
     * Call this once after shader is linked and before rendering.
     */
    public void cacheUniformLocations(GL2 gl) {
        if (uniformsCached) {
            return;
        }
        paletteLocation = gl.glGetUniformLocation(programId, "Palette");
        indexedColorTextureLocation = gl.glGetUniformLocation(programId, "IndexedColorTexture");
        paletteLineLocation = gl.glGetUniformLocation(programId, "PaletteLine");
        uniformsCached = true;
    }

    public int getPaletteLocation() {
        return paletteLocation;
    }

    public int getIndexedColorTextureLocation() {
        return indexedColorTextureLocation;
    }

    public int getPaletteLineLocation() {
        return paletteLineLocation;
    }

    /**
     * Set the palette line uniform (fast path using cached location).
     */
    public void setPaletteLine(GL2 gl, float line) {
        if (paletteLineLocation >= 0) {
            gl.glUniform1f(paletteLineLocation, line);
        }
    }

    /**
     * Initializes a shader program with only a fragment shader.
     *
     * @param gl                 the OpenGL context
     * @param fragmentShaderPath the path to the fragment shader file
     * @throws IOException if the shader file cannot be loaded
     */
    public ShaderProgram(GL2 gl, String fragmentShaderPath) throws IOException {
        // Load and compile the fragment shader
        int fragmentShaderId = ShaderLoader.loadShader(gl, fragmentShaderPath, GL2.GL_FRAGMENT_SHADER);

        // Create a new shader program
        programId = gl.glCreateProgram();

        // Attach the fragment shader to the program
        gl.glAttachShader(programId, fragmentShaderId);

        // Link the program
        gl.glLinkProgram(programId);

        // Check for linking errors
        int[] linked = new int[1];
        gl.glGetProgramiv(programId, GL2.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            // Linking failed, retrieve and print the log
            int[] logLength = new int[1];
            gl.glGetProgramiv(programId, GL2.GL_INFO_LOG_LENGTH, logLength, 0);
            byte[] log = new byte[logLength[0]];
            gl.glGetProgramInfoLog(programId, log.length, null, 0, log, 0);
            System.err.println("Shader linking failed:\n" + new String(log));
        }
    }

    /**
     * Binds the shader program for use.
     *
     * @param gl the OpenGL context
     */
    public void use(GL2 gl) {
        gl.glUseProgram(programId);
    }

    /**
     * Unbinds the shader program.
     *
     * @param gl the OpenGL context
     */
    public void stop(GL2 gl) {
        gl.glUseProgram(0);
    }

    /**
     * Cleans up and deletes the shader program.
     *
     * @param gl the OpenGL context
     */
    public void cleanup(GL2 gl) {
        if (programId != 0) {
            gl.glDeleteProgram(programId);
            programId = 0;
        }
    }
}
