package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;
import com.jogamp.opengl.util.GLBuffers;

import java.nio.FloatBuffer;

/**
 * VBO-backed quad renderer to avoid immediate mode draws.
 */
public class QuadRenderer {
    private static final int FLOATS_PER_QUAD = 8;

    private int vboId;
    private FloatBuffer vertexBuffer;

    public void init(GL2 gl) {
        if (gl == null || vboId != 0) {
            return;
        }
        int[] buffers = new int[1];
        gl.glGenBuffers(1, buffers, 0);
        vboId = buffers[0];
        if (vertexBuffer == null) {
            vertexBuffer = GLBuffers.newDirectFloatBuffer(FLOATS_PER_QUAD);
        }
    }

    public void draw(GL2 gl, float x0, float y0, float x1, float y1) {
        if (gl == null) {
            return;
        }
        if (vboId == 0) {
            init(gl);
        }
        if (vertexBuffer == null) {
            vertexBuffer = GLBuffers.newDirectFloatBuffer(FLOATS_PER_QUAD);
        }

        vertexBuffer.clear();
        vertexBuffer.put(x0).put(y0);
        vertexBuffer.put(x1).put(y0);
        vertexBuffer.put(x1).put(y1);
        vertexBuffer.put(x0).put(y1);
        vertexBuffer.flip();

        gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, vboId);
        gl.glBufferData(GL2.GL_ARRAY_BUFFER, (long) FLOATS_PER_QUAD * Float.BYTES, vertexBuffer,
                GL2.GL_STREAM_DRAW);

        gl.glEnableClientState(GL2.GL_VERTEX_ARRAY);
        gl.glVertexPointer(2, GL2.GL_FLOAT, 0, 0L);
        gl.glDrawArrays(GL2.GL_QUADS, 0, 4);
        gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);

        gl.glBindBuffer(GL2.GL_ARRAY_BUFFER, 0);
    }

    public void cleanup(GL2 gl) {
        if (gl != null && vboId != 0) {
            gl.glDeleteBuffers(1, new int[] { vboId }, 0);
        }
        vboId = 0;
    }
}
