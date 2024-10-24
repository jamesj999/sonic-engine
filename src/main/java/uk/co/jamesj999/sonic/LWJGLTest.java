package uk.co.jamesj999.sonic;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;
import uk.co.jamesj999.sonic.graphics.ShaderProgram;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.ARBVertexArrayObject.*;
import static org.lwjgl.opengl.GL21.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class LWJGLTest {

    // Square vertices (two triangles)
    private static final float[] SQUARE_VERTICES = {
            -0.5f,  0.5f,  // Top-left
            0.5f,  0.5f,  // Top-right
            -0.5f, -0.5f,  // Bottom-left
            0.5f, -0.5f   // Bottom-right
    };

    private static int shaderProgram;
    private static int vao;
    private static int vbo;

    public static void main(String[] args) {
        // Initialize GLFW
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) {
            throw new IllegalStateException("Unable to initialize GLFW");
        }

        // Set OpenGL version to 2.1
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_ANY_PROFILE);

        // Create window
        long window = glfwCreateWindow(800, 600, "Square with OpenGL 2.1", NULL, NULL);
        if (window == NULL) {
            throw new RuntimeException("Failed to create the GLFW window");
        }

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);

        // Initialize the OpenGL bindings with LWJGL
        GL.createCapabilities();

        // Compile and link shaders
        shaderProgram = createShaderProgram("shaders/test-vertex-shader.glsl", "shaders/test-fragment-shader.glsl");

        // Set up the vertex array object (VAO) and vertex buffer object (VBO)
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        FloatBuffer verticesBuffer = MemoryUtil.memAllocFloat(SQUARE_VERTICES.length);
        verticesBuffer.put(SQUARE_VERTICES).flip();

        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, verticesBuffer, GL_STATIC_DRAW);

        // Set the vertex attributes
        int posAttrib = glGetAttribLocation(shaderProgram, "aPos");
        glVertexAttribPointer(posAttrib, 2, GL_FLOAT, false, 2 * Float.BYTES, 0);
        glEnableVertexAttribArray(posAttrib);

        MemoryUtil.memFree(verticesBuffer);

        // Main rendering loop
        while (!glfwWindowShouldClose(window)) {
            // Clear the screen
            glClear(GL_COLOR_BUFFER_BIT);

            // Use the shader program
            glUseProgram(shaderProgram);

            // Bind the VAO and draw the square (as two triangles)
            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

            // Swap buffers and poll events
            glfwSwapBuffers(window);
            glfwPollEvents();
        }

        // Clean up
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(shaderProgram);

        glfwDestroyWindow(window);
        glfwTerminate();
    }

    private static int createShaderProgram(String vertexShaderPath, String fragmentShaderPath) {
        // Load and compile vertex shader
        int vertexShader = glCreateShader(GL_VERTEX_SHADER);
        glShaderSource(vertexShader, readFile(vertexShaderPath));
        glCompileShader(vertexShader);
        checkCompileErrors(vertexShader, "VERTEX");

        // Load and compile fragment shader
        int fragmentShader = glCreateShader(GL_FRAGMENT_SHADER);
        glShaderSource(fragmentShader, readFile(fragmentShaderPath));
        glCompileShader(fragmentShader);
        checkCompileErrors(fragmentShader, "FRAGMENT");

        // Link shaders into a program
        int program = glCreateProgram();
        glAttachShader(program, vertexShader);
        glAttachShader(program, fragmentShader);
        glLinkProgram(program);
        checkLinkErrors(program);

        // Clean up shaders (they're no longer needed after linking)
        glDeleteShader(vertexShader);
        glDeleteShader(fragmentShader);

        return program;
    }

    private static void checkCompileErrors(int shader, String type) {
        int success = glGetShaderi(shader, GL_COMPILE_STATUS);
        if (success == GL_FALSE) {
            String infoLog = glGetShaderInfoLog(shader);
            System.err.println("ERROR::SHADER_COMPILATION_ERROR of type: " + type + "\n" + infoLog);
        }
    }

    private static void checkLinkErrors(int program) {
        int success = glGetProgrami(program, GL_LINK_STATUS);
        if (success == GL_FALSE) {
            String infoLog = glGetProgramInfoLog(program);
            System.err.println("ERROR::PROGRAM_LINKING_ERROR\n" + infoLog);
        }
    }

    public static String readFile(String filepath) {
        try {
            // Load the shader source code from the file
            String truePath = Objects.requireNonNull(LWJGLTest.class.getClassLoader().getResource(filepath)).getPath();
            if (truePath.contains(":")) {
                truePath = truePath.split("/", 2)[1];
            }
            String shaderSource = new String(Files.readAllBytes(Paths.get(truePath)));

            return shaderSource;
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }
}
