package com.openggf.graphics;

import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Renders textured quads using the RGBA texture shader.
 * Used for PNG-based rendering (master title screen, sprite font).
 */
public class TexturedQuadRenderer {

    static final int QUAD_FLOATS = 6 * 4;
    /** Floats per quad when using per-vertex color (x, y, u, v, r, g, b, a). */
    static final int COLORED_QUAD_FLOATS = 6 * 8;

    private static final String VERT_PATH = "shaders/shader_rgba_texture.vert";
    private static final String FRAG_PATH = "shaders/shader_rgba_texture.frag";
    private static final String COLORED_VERT_PATH = "shaders/shader_rgba_texture_vcol.vert";
    private static final String COLORED_FRAG_PATH = "shaders/shader_rgba_texture_vcol.frag";

    private ShaderProgram shader;
    private int vao;
    private int vbo;
    private int projectionLocation;
    private int textureLocation;
    private int tintLocation;
    private FloatBuffer quadVertexBuffer;
    private int allocatedFloatCapacity;
    private float[] cachedProjectionMatrix;

    // Per-vertex color batch resources (lazy-initialized)
    private ShaderProgram coloredShader;
    private int coloredVao;
    private int coloredVbo;
    private int coloredProjectionLocation;
    private int coloredTextureLocation;
    private FloatBuffer coloredVertexBuffer;
    private int coloredAllocatedCapacity;

    public void init() throws IOException {
        shader = new ShaderProgram(VERT_PATH, FRAG_PATH);

        projectionLocation = glGetUniformLocation(shader.getProgramId(), "ProjectionMatrix");
        textureLocation = glGetUniformLocation(shader.getProgramId(), "Texture");
        tintLocation = glGetUniformLocation(shader.getProgramId(), "Tint");

        // Create VAO and VBO for a dynamic quad (2 triangles, 4 floats per vertex: x,y,u,v)
        vao = glGenVertexArrays();
        vbo = glGenBuffers();

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Allocate space for 6 vertices * 4 floats each
        glBufferData(GL_ARRAY_BUFFER, 6 * 4 * Float.BYTES, GL_DYNAMIC_DRAW);
        allocatedFloatCapacity = QUAD_FLOATS;

        // Position: location 0, 2 floats
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0);

        // UV: location 1, 2 floats
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);

        glBindVertexArray(0);
        glBindBuffer(GL_ARRAY_BUFFER, 0);

        quadVertexBuffer = MemoryUtil.memAllocFloat(QUAD_FLOATS);
    }

    /**
     * Sets the projection matrix for rendering.
     */
    public void setProjectionMatrix(float[] matrixBuffer) {
        cachedProjectionMatrix = matrixBuffer;
        shader.use();
        glUniformMatrix4fv(projectionLocation, false, matrixBuffer);
        shader.stop();
        if (coloredShader != null) {
            coloredShader.use();
            glUniformMatrix4fv(coloredProjectionLocation, false, matrixBuffer);
            coloredShader.stop();
        }
    }

    /**
     * Draws a textured quad with white tint (no color modification).
     */
    public void drawTexture(int textureId, float x, float y, float w, float h) {
        drawTextureRegion(textureId, x, y, w, h, 0f, 0f, 1f, 1f, 1f, 1f, 1f, 1f);
    }

    /**
     * Draws a textured quad with tint color.
     */
    public void drawTexture(int textureId, float x, float y, float w, float h,
                            float r, float g, float b, float a) {
        drawTextureRegion(textureId, x, y, w, h, 0f, 0f, 1f, 1f, r, g, b, a);
    }

    /**
     * Draws a sub-region of a texture with tint.
     * UV coordinates specify the region within the texture (0-1 range).
     */
    public void drawTextureRegion(int textureId, float x, float y, float w, float h,
                                  float u0, float v0, float u1, float v1,
                                  float r, float g, float b, float a) {
        shader.use();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(textureLocation, 0);
        glUniform4f(tintLocation, r, g, b, a);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        FloatBuffer vertexBuffer = quadVertexBuffer;
        if (vertexBuffer == null) {
            vertexBuffer = MemoryUtil.memAllocFloat(QUAD_FLOATS);
            quadVertexBuffer = vertexBuffer;
        }
        writeQuadVertices(vertexBuffer, x, y, w, h, u0, v0, u1, v1);
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexBuffer);

        glDrawArrays(GL_TRIANGLES, 0, 6);

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.stop();
    }

    /**
     * Draws multiple quads that share the same texture and tint in a single submission.
     * The provided array must contain {@code quadCount * 24} floats of packed vertex data.
     */
    public void drawTextureBatch(int textureId, float[] vertexData, int quadCount,
                                 float r, float g, float b, float a) {
        if (quadCount <= 0) {
            return;
        }

        int floatCount = quadCount * QUAD_FLOATS;
        ensureVertexCapacity(floatCount);

        shader.use();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(textureLocation, 0);
        glUniform4f(tintLocation, r, g, b, a);

        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);

        quadVertexBuffer.clear();
        quadVertexBuffer.put(vertexData, 0, floatCount);
        quadVertexBuffer.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, quadVertexBuffer);

        glDrawArrays(GL_TRIANGLES, 0, quadCount * 6);

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        shader.stop();
    }

    /**
     * Draws multiple quads with per-vertex color in a single submission.
     * Vertex format: x, y, u, v, r, g, b, a (8 floats per vertex, 48 per quad).
     * Uses a dedicated shader with vertex color attribute instead of a uniform tint.
     */
    public void drawColoredTextureBatch(int textureId, float[] vertexData, int quadCount) {
        if (quadCount <= 0) {
            return;
        }

        ensureColoredInit();

        int floatCount = quadCount * COLORED_QUAD_FLOATS;
        ensureColoredVertexCapacity(floatCount);

        coloredShader.use();

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, textureId);
        glUniform1i(coloredTextureLocation, 0);

        glBindVertexArray(coloredVao);
        glBindBuffer(GL_ARRAY_BUFFER, coloredVbo);

        coloredVertexBuffer.clear();
        coloredVertexBuffer.put(vertexData, 0, floatCount);
        coloredVertexBuffer.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, coloredVertexBuffer);

        glDrawArrays(GL_TRIANGLES, 0, quadCount * 6);

        glBindVertexArray(0);
        glBindTexture(GL_TEXTURE_2D, 0);
        coloredShader.stop();
    }

    public void cleanup() {
        if (shader != null) {
            shader.cleanup();
        }
        if (vao != 0) {
            glDeleteVertexArrays(vao);
        }
        if (vbo != 0) {
            glDeleteBuffers(vbo);
        }
        if (quadVertexBuffer != null) {
            MemoryUtil.memFree(quadVertexBuffer);
            quadVertexBuffer = null;
        }
        if (coloredShader != null) {
            coloredShader.cleanup();
        }
        if (coloredVao != 0) {
            glDeleteVertexArrays(coloredVao);
        }
        if (coloredVbo != 0) {
            glDeleteBuffers(coloredVbo);
        }
        if (coloredVertexBuffer != null) {
            MemoryUtil.memFree(coloredVertexBuffer);
            coloredVertexBuffer = null;
        }
    }

    static void writeQuadVertices(FloatBuffer buffer,
                                  float x, float y, float w, float h,
                                  float u0, float v0, float u1, float v1) {
        buffer.clear();
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;
        buffer.put(x0).put(y1).put(u0).put(v1);
        buffer.put(x0).put(y0).put(u0).put(v0);
        buffer.put(x1).put(y0).put(u1).put(v0);
        buffer.put(x0).put(y1).put(u0).put(v1);
        buffer.put(x1).put(y0).put(u1).put(v0);
        buffer.put(x1).put(y1).put(u1).put(v1);
        buffer.flip();
    }

    static void writeQuadVerticesAtOffset(float[] target, int floatOffset,
                                          float x, float y, float w, float h,
                                          float u0, float v0, float u1, float v1) {
        float x0 = x;
        float y0 = y;
        float x1 = x + w;
        float y1 = y + h;

        int i = floatOffset;
        target[i++] = x0; target[i++] = y1; target[i++] = u0; target[i++] = v1;
        target[i++] = x0; target[i++] = y0; target[i++] = u0; target[i++] = v0;
        target[i++] = x1; target[i++] = y0; target[i++] = u1; target[i++] = v0;
        target[i++] = x0; target[i++] = y1; target[i++] = u0; target[i++] = v1;
        target[i++] = x1; target[i++] = y0; target[i++] = u1; target[i++] = v0;
        target[i] = x1; target[i + 1] = y1; target[i + 2] = u1; target[i + 3] = v1;
    }

    static void writeColoredQuadVerticesAtOffset(float[] target, int floatOffset,
                                                    float x, float y, float w, float h,
                                                    float u0, float v0, float u1, float v1,
                                                    float r, float g, float b, float a) {
        float x1 = x + w;
        float y1 = y + h;

        int i = floatOffset;
        target[i]   = x;  target[i+1] = y1; target[i+2] = u0; target[i+3] = v1; target[i+4] = r; target[i+5] = g; target[i+6] = b; target[i+7] = a;
        i += 8;
        target[i]   = x;  target[i+1] = y;  target[i+2] = u0; target[i+3] = v0; target[i+4] = r; target[i+5] = g; target[i+6] = b; target[i+7] = a;
        i += 8;
        target[i]   = x1; target[i+1] = y;  target[i+2] = u1; target[i+3] = v0; target[i+4] = r; target[i+5] = g; target[i+6] = b; target[i+7] = a;
        i += 8;
        target[i]   = x;  target[i+1] = y1; target[i+2] = u0; target[i+3] = v1; target[i+4] = r; target[i+5] = g; target[i+6] = b; target[i+7] = a;
        i += 8;
        target[i]   = x1; target[i+1] = y;  target[i+2] = u1; target[i+3] = v0; target[i+4] = r; target[i+5] = g; target[i+6] = b; target[i+7] = a;
        i += 8;
        target[i]   = x1; target[i+1] = y1; target[i+2] = u1; target[i+3] = v1; target[i+4] = r; target[i+5] = g; target[i+6] = b; target[i+7] = a;
    }

    private void ensureColoredInit() {
        if (coloredShader != null) {
            return;
        }
        try {
            coloredShader = new ShaderProgram(COLORED_VERT_PATH, COLORED_FRAG_PATH);
            coloredProjectionLocation = glGetUniformLocation(coloredShader.getProgramId(), "ProjectionMatrix");
            coloredTextureLocation = glGetUniformLocation(coloredShader.getProgramId(), "Texture");

            coloredVao = glGenVertexArrays();
            coloredVbo = glGenBuffers();

            glBindVertexArray(coloredVao);
            glBindBuffer(GL_ARRAY_BUFFER, coloredVbo);
            glBufferData(GL_ARRAY_BUFFER, (long) COLORED_QUAD_FLOATS * Float.BYTES, GL_DYNAMIC_DRAW);
            coloredAllocatedCapacity = COLORED_QUAD_FLOATS;

            int stride = 8 * Float.BYTES;
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 2L * Float.BYTES);
            glEnableVertexAttribArray(2);
            glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 4L * Float.BYTES);

            glBindVertexArray(0);
            glBindBuffer(GL_ARRAY_BUFFER, 0);

            coloredVertexBuffer = MemoryUtil.memAllocFloat(COLORED_QUAD_FLOATS);

            if (cachedProjectionMatrix != null) {
                coloredShader.use();
                glUniformMatrix4fv(coloredProjectionLocation, false, cachedProjectionMatrix);
                coloredShader.stop();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize per-vertex color shader", e);
        }
    }

    private void ensureColoredVertexCapacity(int requiredFloats) {
        if (requiredFloats <= coloredAllocatedCapacity) {
            return;
        }
        MemoryUtil.memFree(coloredVertexBuffer);
        coloredVertexBuffer = MemoryUtil.memAllocFloat(requiredFloats);
        coloredAllocatedCapacity = requiredFloats;
        glBindBuffer(GL_ARRAY_BUFFER, coloredVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) requiredFloats * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }

    private void ensureVertexCapacity(int requiredFloats) {
        if (quadVertexBuffer == null) {
            quadVertexBuffer = MemoryUtil.memAllocFloat(requiredFloats);
            allocatedFloatCapacity = requiredFloats;
            glBindBuffer(GL_ARRAY_BUFFER, vbo);
            glBufferData(GL_ARRAY_BUFFER, (long) requiredFloats * Float.BYTES, GL_DYNAMIC_DRAW);
            glBindBuffer(GL_ARRAY_BUFFER, 0);
            return;
        }
        if (requiredFloats <= allocatedFloatCapacity) {
            return;
        }
        MemoryUtil.memFree(quadVertexBuffer);
        quadVertexBuffer = MemoryUtil.memAllocFloat(requiredFloats);
        allocatedFloatCapacity = requiredFloats;
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) requiredFloats * Float.BYTES, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
    }
}
