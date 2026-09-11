package com.openggf.util;

import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * Centralised helper for creating, destroying and managing OpenGL framebuffer
 * objects (FBOs).
 *
 * <p>Four renderer classes previously duplicated near-identical 15-20 line FBO
 * creation sequences. This utility captures the two variants used in the
 * codebase:
 * <ul>
 *   <li>{@link #createColorOnly} -- colour attachment only (e.g. tile priority)</li>
 *   <li>{@link #createWithDepth} -- colour + depth-renderbuffer (e.g. background renderers)</li>
 * </ul>
 *
 * <p>Viewport save/restore helpers are also provided to replace the recurring
 * {@code glGetIntegerv(GL_VIEWPORT, ...)} / {@code glViewport(...)} pattern.
 */
public final class FboHelper {

    private static final Logger LOG = Logger.getLogger(FboHelper.class.getName());

    private FboHelper() {}

    /**
     * Immutable handle to an FBO and its attachments.
     *
     * @param fboId     OpenGL framebuffer object name
     * @param textureId Colour-attachment texture name
     * @param depthId   Depth renderbuffer name, or {@code 0} if none
     */
    public record FboHandle(int fboId, int textureId, int depthId) {
        /** Returns {@code true} when this FBO was created with a depth buffer. */
        public boolean hasDepth() { return depthId != 0; }
    }

    /**
     * Create an FBO with a colour texture attachment only (no depth buffer).
     *
     * @param width    texture width in pixels
     * @param height   texture height in pixels
     * @param wrapMode GL wrap mode for S and T (e.g. {@code GL_CLAMP_TO_EDGE})
     * @return handle to the created FBO resources
     */
    public static FboHandle createColorOnly(int width, int height, int wrapMode) {
        int fboId = glGenFramebuffers();
        int textureId = createTexture(width, height, wrapMode);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, textureId, 0);
        if (!checkStatus("color-only")) {
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            destroy(new FboHandle(fboId, textureId, 0));
            return null;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new FboHandle(fboId, textureId, 0);
    }

    /**
     * Create an FBO with a colour texture attachment and a 16-bit depth
     * renderbuffer.
     *
     * @param width    texture / renderbuffer width in pixels
     * @param height   texture / renderbuffer height in pixels
     * @param wrapMode GL wrap mode for S and T (e.g. {@code GL_REPEAT})
     * @return handle to the created FBO resources
     */
    public static FboHandle createWithDepth(int width, int height, int wrapMode) {
        int fboId = glGenFramebuffers();
        int textureId = createTexture(width, height, wrapMode);

        int depthId = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, depthId);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT16, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);

        glBindFramebuffer(GL_FRAMEBUFFER, fboId);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D, textureId, 0);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
                GL_RENDERBUFFER, depthId);
        checkStatus("color+depth");
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new FboHandle(fboId, textureId, depthId);
    }

    /**
     * Delete all OpenGL resources associated with the given handle.
     *
     * @param handle the FBO handle to destroy (may be {@code null})
     */
    public static void destroy(FboHandle handle) {
        if (handle == null) return;
        if (handle.fboId() != 0) glDeleteFramebuffers(handle.fboId());
        if (handle.textureId() != 0) glDeleteTextures(handle.textureId());
        if (handle.depthId() != 0) glDeleteRenderbuffers(handle.depthId());
    }

    /**
     * Save the current GL viewport as a 4-element int array.
     *
     * @return {@code [x, y, width, height]}
     */
    public static int[] saveViewport() {
        int[] viewport = new int[4];
        glGetIntegerv(GL_VIEWPORT, viewport);
        return viewport;
    }

    /**
     * Restore a previously saved viewport.
     *
     * @param saved the array returned by {@link #saveViewport()}
     */
    public static void restoreViewport(int[] saved) {
        glViewport(saved[0], saved[1], saved[2], saved[3]);
    }

    // ---- internal helpers ----

    private static int createTexture(int width, int height, int wrapMode) {
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapMode);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapMode);
        glBindTexture(GL_TEXTURE_2D, 0);
        return textureId;
    }

    private static boolean checkStatus(String label) {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            LOG.severe("FBO creation failed (" + label + ") with status: " + status);
            return false;
        }
        return true;
    }
}
