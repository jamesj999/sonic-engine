package com.openggf.graphics;

import com.openggf.util.FboHelper;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;

class TestFboReleaseFailure {

    @Test
    void createColorOnlyReturnsNullAndDeletesResourcesWhenFramebufferIsIncomplete() {
        try (MockedStatic<GL11> gl11 = mockStatic(GL11.class);
             MockedStatic<GL30> gl30 = mockStatic(GL30.class)) {
            gl30.when(GL30::glGenFramebuffers).thenReturn(101);
            gl11.when(GL11::glGenTextures).thenReturn(202);
            gl30.when(() -> GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER))
                    .thenReturn(GL30.GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT);

            FboHelper.FboHandle handle = FboHelper.createColorOnly(320, 224, GL12.GL_CLAMP_TO_EDGE);

            assertNull(handle, "Incomplete framebuffer creation must fail closed");
            gl30.verify(() -> GL30.glDeleteFramebuffers(101));
            gl11.verify(() -> GL11.glDeleteTextures(202));
            gl30.verify(() -> GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0));
        }
    }

    @Test
    void tilePriorityFboRemainsUninitializedWhenColorFboCreationFails() {
        try (MockedStatic<FboHelper> fboHelper = mockStatic(FboHelper.class)) {
            fboHelper.when(() -> FboHelper.createColorOnly(320, 224, GL12.GL_CLAMP_TO_EDGE))
                    .thenReturn(null);

            TilePriorityFBO priorityFbo = new TilePriorityFBO();

            assertDoesNotThrow(() -> priorityFbo.init(320, 224));
            assertFalse(priorityFbo.isInitialized());
            assertEquals(-1, priorityFbo.getTextureId());
            assertEquals(320, priorityFbo.getWidth());
            assertEquals(224, priorityFbo.getHeight());
        }
    }
}
