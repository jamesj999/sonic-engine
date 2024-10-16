package uk.co.jamesj999.sonic;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import javax.swing.JFrame;

import com.jogamp.opengl.*;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.util.Animator;

public class RenderTest implements GLEventListener {

    private int shaderProgram;
    private int vboId;
    private int textureId;

    private int positionLoc;
    private int patternLoc;
    private int paletteLoc;

    private final int patternWidth = 8;
    private final int patternHeight = 8;

    private final byte[] patternData = new byte[patternWidth * patternHeight];
    private final float[] paletteData = new float[16 * 3]; // 16 colors, each with RGB

    public static void main(String[] args) {
        JFrame frame = new JFrame("Pattern Renderer");
        GLProfile profile = GLProfile.getDefault();
        GLCapabilities capabilities = new GLCapabilities(profile);
        GLCanvas canvas = new GLCanvas(capabilities);

        RenderTest renderer = new RenderTest();
        canvas.addGLEventListener(renderer);

        frame.getContentPane().add(canvas);
        frame.setSize(400, 400);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setVisible(true);

        Animator animator = new Animator(canvas);
        animator.start();
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        // Initialize GL
        GL2 gl = drawable.getGL().getGL2();

        // Initialize shaders
        initShaders(gl);

        // Initialize data
        initData(gl);
    }

    private void initShaders(GL2 gl) {
        String vertexShaderSource = "#version 120\n" +
                "attribute vec2 position;\n" +
                "varying vec2 texCoord;\n" +
                "void main() {\n" +
                "    texCoord = (position + 1.0) / 2.0;\n" +
                "    gl_Position = vec4(position, 0.0, 1.0);\n" +
                "}";

        String fragmentShaderSource = "#version 120\n" +
                "uniform sampler2D pattern;\n" +
                "uniform vec3 palette[16];\n" +
                "varying vec2 texCoord;\n" +
                "void main() {\n" +
                "    float index = texture2D(pattern, texCoord).r * 15.0;\n" +
                "    int idx = int(index + 0.5);\n" +
                "    gl_FragColor = vec4(palette[idx], 1.0);\n" +
                "}";

        // Compile vertex shader
        int vertexShader = gl.glCreateShader(GL2.GL_VERTEX_SHADER);
        gl.glShaderSource(vertexShader, 1, new String[]{vertexShaderSource}, null, 0);
        gl.glCompileShader(vertexShader);
        checkShaderCompileStatus(gl, vertexShader, "vertex");

        // Compile fragment shader
        int fragmentShader = gl.glCreateShader(GL2.GL_FRAGMENT_SHADER);
        gl.glShaderSource(fragmentShader, 1, new String[]{fragmentShaderSource}, null, 0);
        gl.glCompileShader(fragmentShader);
        checkShaderCompileStatus(gl, fragmentShader, "fragment");

        // Create shader program
        shaderProgram = gl.glCreateProgram();
        gl.glAttachShader(shaderProgram, vertexShader);
        gl.glAttachShader(shaderProgram, fragmentShader);

        // Bind attribute location
        gl.glBindAttribLocation(shaderProgram, 0, "position");

        // Link program
        gl.glLinkProgram(shaderProgram);
        checkProgramLinkStatus(gl, shaderProgram);

        // Get uniform locations
        positionLoc = gl.glGetAttribLocation(shaderProgram, "position");
        patternLoc = gl.glGetUniformLocation(shaderProgram, "pattern");
        paletteLoc = gl.glGetUniformLocation(shaderProgram, "palette");

        // Clean up shaders (they are linked into our program and no longer necessary)
        gl.glDeleteShader(vertexShader);
        gl.glDeleteShader(fragmentShader);
    }

    private void initData(GL2 gl) {
        // Create vertex data for a full-screen quad
        float[] vertices = {
                -1.0f, -1.0f, // Bottom-left
                1.0f, -1.0f,  // Bottom-right
                -1.0f, 1.0f,  // Top-left
                1.0f, 1.0f    // Top-right
        };

        // Generate VBO
        int[] buffers = new int[1];
        gl.glGenBuffers(1, buffers, 0);
        vboId = buffers[0];

        gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, vboId);
        FloatBuffer vertexData = FloatBuffer.wrap(vertices);
        gl.glBufferData(GL2.GL_ARRAY_BUFFER, vertexData.limit() * Float.BYTES, vertexData, GL2.GL_STATIC_DRAW);

        // Generate texture for the pattern data
        int[] textures = new int[1];
        gl.glGenTextures(1, textures, 0);
        textureId = textures[0];

        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);

        // Create an example pattern (checkerboard)
        for (int y = 0; y < patternHeight; y++) {
            for (int x = 0; x < patternWidth; x++) {
                int index = y * patternWidth + x;
                if ((x + y) % 2 == 0) {
                    patternData[index] = 0; // Palette index 0
                } else {
                    patternData[index] = 15; // Palette index 15
                }
            }
        }

        ByteBuffer patternBuffer = ByteBuffer.wrap(patternData);

        gl.glTexImage2D(GL2.GL_TEXTURE_2D, 0, GL2.GL_LUMINANCE, patternWidth, patternHeight, 0,
                GL2.GL_LUMINANCE, GL2.GL_UNSIGNED_BYTE, patternBuffer);

        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MIN_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_MAG_FILTER, GL2.GL_NEAREST);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_S, GL2.GL_CLAMP_TO_EDGE);
        gl.glTexParameteri(GL2.GL_TEXTURE_2D, GL2.GL_TEXTURE_WRAP_T, GL2.GL_CLAMP_TO_EDGE);

        // Create an example palette (random colors)
        for (int i = 0; i < 16 * 3; i++) {
            paletteData[i] = (float) Math.random();
        }
    }

    @Override
    public void display(GLAutoDrawable drawable) {
        // Render
        GL2 gl = drawable.getGL().getGL2();

        gl.glClear(GL2.GL_COLOR_BUFFER_BIT);

        // Use shader program
        gl.glUseProgram(shaderProgram);

        // Set active texture unit and bind texture
        gl.glActiveTexture(GL2.GL_TEXTURE0);
        gl.glBindTexture(GL2.GL_TEXTURE_2D, textureId);

        // Set 'pattern' sampler uniform to texture unit 0
        gl.glUniform1i(patternLoc, 0);

        // Set 'palette' uniform array
        gl.glUniform3fv(paletteLoc, 16, paletteData, 0);

        // Enable vertex attribute array and set data
        gl.glEnableVertexAttribArray(positionLoc);
        gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, vboId);
        gl.glVertexAttribPointer(positionLoc, 2, GL2.GL_FLOAT, false, 0, 0);

        // Draw quad
        gl.glDrawArrays(GL2.GL_TRIANGLE_STRIP, 0, 4);

        gl.glDisableVertexAttribArray(positionLoc);
        gl.glUseProgram(0);
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        // Cleanup
        GL2 gl = drawable.getGL().getGL2();

        if (shaderProgram != 0) {
            gl.glDeleteProgram(shaderProgram);
            shaderProgram = 0;
        }

        if (vboId != 0) {
            gl.glDeleteBuffers(1, new int[]{vboId}, 0);
            vboId = 0;
        }

        if (textureId != 0) {
            gl.glDeleteTextures(1, new int[]{textureId}, 0);
            textureId = 0;
        }
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        // Handle window resizing if necessary
        GL2 gl = drawable.getGL().getGL2();
        gl.glViewport(0, 0, width, height);
    }

    private void checkShaderCompileStatus(GL2 gl, int shader, String shaderType) {
        int[] compiled = new int[1];
        gl.glGetShaderiv(shader, GL2.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            System.err.println("Error compiling " + shaderType + " shader.");
            printShaderLog(gl, shader);
            System.exit(1);
        }
    }

    private void checkProgramLinkStatus(GL2 gl, int program) {
        int[] linked = new int[1];
        gl.glGetProgramiv(program, GL2.GL_LINK_STATUS, linked, 0);
        if (linked[0] == 0) {
            System.err.println("Error linking program.");
            printProgramLog(gl, program);
            System.exit(1);
        }
    }

    private void printShaderLog(GL2 gl, int shader) {
        int[] infoLogLength = new int[1];
        gl.glGetShaderiv(shader, GL2.GL_INFO_LOG_LENGTH, infoLogLength, 0);
        if (infoLogLength[0] > 1) {
            byte[] infoLog = new byte[infoLogLength[0]];
            gl.glGetShaderInfoLog(shader, infoLogLength[0], null, 0, infoLog, 0);
            System.err.println("Shader InfoLog:\n" + new String(infoLog));
        }
    }

    private void printProgramLog(GL2 gl, int program) {
        int[] infoLogLength = new int[1];
        gl.glGetProgramiv(program, GL2.GL_INFO_LOG_LENGTH, infoLogLength, 0);
        if (infoLogLength[0] > 1) {
            byte[] infoLog = new byte[infoLogLength[0]];
            gl.glGetProgramInfoLog(program, infoLogLength[0], null, 0, infoLog, 0);
            System.err.println("Program InfoLog:\n" + new String(infoLog));
        }
    }
}
