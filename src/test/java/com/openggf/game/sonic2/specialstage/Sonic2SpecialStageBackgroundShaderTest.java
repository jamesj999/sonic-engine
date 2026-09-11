package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class Sonic2SpecialStageBackgroundShaderTest {

    private static final Path SHADER_PATH = Path.of("src/main/resources/shaders/shader_ss_background.glsl");

    private static final int SCREEN_GAME_WIDTH = SpecialStageBackgroundRenderer.SCREEN_WIDTH;

    /**
     * The S2 special stage runs in H32 (256px) mode centered in the 320px game viewport,
     * so the 32px side borders must stay black and the visible region must sample the
     * background from active-display-local X coordinates.
     *
     * <p>A fully behavioral pixel assertion would require executing the GLSL shader under an
     * OpenGL context, which is infeasible headlessly. Instead this asserts the renderer's
     * computed active-display edge geometry (the values the shader is fed and the math it
     * applies), then reproduces the shader's edge formula to confirm side-border masking and
     * local-X sampling at representative pixel positions. A single minimal source guard is
     * retained to confirm the shader still consumes {@code ActiveDisplayWidth} for masking.
     */
    @Test
    public void testShaderKeepsSonic2H32SideBordersBlack() throws Exception {
        // Renderer's configured active-display geometry (H32 width centered in 320px).
        float activeDisplayWidth = SpecialStageBackgroundRenderer.H32_WIDTH;
        assertEquals(256.0f, activeDisplayWidth,
                "S2 special stage active display must be H32 (256px) wide");

        // These mirror the shader's edge math: leftEdge = (320 - width) * 0.5.
        float activeDisplayLeftEdge = (SCREEN_GAME_WIDTH - activeDisplayWidth) * 0.5f;
        float activeDisplayRightEdge = activeDisplayLeftEdge + activeDisplayWidth;
        assertEquals(32.0f, activeDisplayLeftEdge,
                "H32 left edge must be 32px (the renderer's H32_OFFSET)");
        assertEquals((float) SpecialStageBackgroundRenderer.H32_OFFSET, activeDisplayLeftEdge,
                "Computed left edge must match the renderer's H32_OFFSET constant");
        assertEquals(288.0f, activeDisplayRightEdge,
                "H32 right edge must be 288px (32 + 256)");

        // Pixels outside the active region must be masked to black; inside maps to local X.
        assertTrue(isMaskedBlack(0, activeDisplayLeftEdge, activeDisplayRightEdge),
                "Left border pixel (x=0) must be black");
        assertTrue(isMaskedBlack(31, activeDisplayLeftEdge, activeDisplayRightEdge),
                "Last left-border pixel (x=31) must be black");
        assertTrue(isMaskedBlack(288, activeDisplayLeftEdge, activeDisplayRightEdge),
                "First right-border pixel (x=288) must be black");
        assertTrue(isMaskedBlack(319, activeDisplayLeftEdge, activeDisplayRightEdge),
                "Right border pixel (x=319) must be black");

        // Inside the active region, sampling uses active-display-local X (gameX - leftEdge).
        assertTrue(!isMaskedBlack(32, activeDisplayLeftEdge, activeDisplayRightEdge),
                "First visible pixel (x=32) must not be masked");
        assertEquals(0.0f, localX(32, activeDisplayLeftEdge),
                "First visible pixel must sample localX=0");
        assertEquals(255.0f, localX(287, activeDisplayLeftEdge),
                "Last visible pixel (x=287) must sample localX=255");

        // Minimal retained source guard: confirm the shader still consumes ActiveDisplayWidth
        // to drive the side-border masking (the behavioral assertions above reproduce its math,
        // but cannot prove the shader file itself is still wired this way).
        String shader = Files.readString(SHADER_PATH);
        assertTrue(shader.contains("uniform float ActiveDisplayWidth;")
                        && shader.contains("gameX < activeDisplayLeftEdge || gameX >= activeDisplayRightEdge"),
                "Shader must still consume ActiveDisplayWidth and emit black outside the active viewport");
    }

    /** Reproduces the shader's masking decision for a given game-X pixel. */
    private static boolean isMaskedBlack(float gameX, float leftEdge, float rightEdge) {
        return gameX < leftEdge || gameX >= rightEdge;
    }

    /** Reproduces the shader's active-display-local X mapping. */
    private static float localX(float gameX, float leftEdge) {
        return gameX - leftEdge;
    }
}
