package uk.co.jamesj999.sonic.graphics;

import com.jogamp.opengl.GL2;

/**
 * Manages screen fade effects for transitions.
 *
 * Supports two types of fades:
 *
 * WHITE FADES (Special Stage):
 * Implements the original Sonic 2 fade-to-white algorithm where RGB channels
 * increment sequentially over 21 frames:
 * - Frames 0-6: Red channel increases
 * - Frames 7-13: Green channel increases
 * - Frames 14-20: Blue channel increases
 * Uses additive blending with a full-screen quad.
 *
 * BLACK FADES (Level transitions, menus, etc.):
 * Standard fade-to-black where all RGB channels decrement together.
 * Uses alpha blending with a black overlay quad.
 */
public class FadeManager {

    /**
     * Fade state enumeration.
     */
    public enum FadeState {
        /** No fade active */
        NONE,
        /** Fading screen to white (special stage) */
        FADING_TO_WHITE,
        /** Holding at full white (optional pause) */
        HOLD_WHITE,
        /** Fading from white back to normal */
        FADING_FROM_WHITE,
        /** Fading screen to black (level transitions) */
        FADING_TO_BLACK,
        /** Holding at full black (optional pause) */
        HOLD_BLACK,
        /** Fading from black back to normal */
        FADING_FROM_BLACK
    }

    /**
     * Type of fade effect (determines blending mode).
     */
    public enum FadeType {
        /** White fade using additive blending */
        WHITE,
        /** Black fade using alpha blending */
        BLACK
    }

    private static FadeManager instance;

    // Current fade state
    private FadeState state = FadeState.NONE;
    private int frameCount = 0;

    // Fade duration in frames (matches original Sonic 2: 21 frames)
    private static final int FADE_DURATION = 21;

    // Frames per RGB channel (7 levels per channel on Genesis: 0, 2, 4, 6, 8, A, C, E)
    private static final int FRAMES_PER_CHANNEL = 7;

    // Increment per frame for each channel (1.0 / 7 ≈ 0.143)
    private static final float CHANNEL_INCREMENT = 1.0f / FRAMES_PER_CHANNEL;

    // Current fade color values (0.0 to 1.0) - used for white fades
    private float fadeR = 0f;
    private float fadeG = 0f;
    private float fadeB = 0f;

    // Alpha value for black fades (0.0 to 1.0)
    private float fadeAlpha = 0f;

    // Current fade type
    private FadeType fadeType = FadeType.WHITE;

    // Callback to execute when fade completes
    private Runnable onFadeComplete;

    // Hold duration in frames (for optional pause at full white)
    private int holdDuration = 0;
    private int holdFrameCount = 0;

    // Shader program reference (set by GraphicsManager)
    private ShaderProgram fadeShader;

    // Cached uniform location
    private int fadeColorLocation = -1;

    private FadeManager() {
        // Private constructor for singleton
    }

    /**
     * Get the singleton instance.
     */
    public static synchronized FadeManager getInstance() {
        if (instance == null) {
            instance = new FadeManager();
        }
        return instance;
    }

    /**
     * Reset the singleton instance (for testing).
     */
    public static synchronized void resetInstance() {
        instance = null;
    }

    /**
     * Set the fade shader program. Called by GraphicsManager during initialization.
     */
    public void setFadeShader(ShaderProgram shader) {
        this.fadeShader = shader;
        this.fadeColorLocation = -1; // Reset cached location
    }

    /**
     * Start a fade-to-white transition.
     *
     * @param onComplete Callback to execute when fade completes (can be null)
     */
    public void startFadeToWhite(Runnable onComplete) {
        startFadeToWhite(onComplete, 0);
    }

    /**
     * Start a fade-to-white transition with optional hold at white.
     *
     * @param onComplete   Callback to execute when fade completes (can be null)
     * @param holdFrames   Number of frames to hold at full white before completing
     */
    public void startFadeToWhite(Runnable onComplete, int holdFrames) {
        this.state = FadeState.FADING_TO_WHITE;
        this.fadeType = FadeType.WHITE;
        this.frameCount = 0;
        this.fadeR = 0f;
        this.fadeG = 0f;
        this.fadeB = 0f;
        this.onFadeComplete = onComplete;
        this.holdDuration = holdFrames;
        this.holdFrameCount = 0;
    }

    /**
     * Start a fade-from-white transition (screen returns from white to normal).
     *
     * @param onComplete Callback to execute when fade completes (can be null)
     */
    public void startFadeFromWhite(Runnable onComplete) {
        this.state = FadeState.FADING_FROM_WHITE;
        this.fadeType = FadeType.WHITE;
        this.frameCount = 0;
        this.fadeR = 1f;
        this.fadeG = 1f;
        this.fadeB = 1f;
        this.onFadeComplete = onComplete;
        this.holdDuration = 0;
        this.holdFrameCount = 0;
    }

    /**
     * Start a fade-to-black transition.
     * Used for level transitions, menus, continue screen, etc.
     *
     * @param onComplete Callback to execute when fade completes (can be null)
     */
    public void startFadeToBlack(Runnable onComplete) {
        startFadeToBlack(onComplete, 0);
    }

    /**
     * Start a fade-to-black transition with optional hold at black.
     *
     * @param onComplete   Callback to execute when fade completes (can be null)
     * @param holdFrames   Number of frames to hold at full black before completing
     */
    public void startFadeToBlack(Runnable onComplete, int holdFrames) {
        this.state = FadeState.FADING_TO_BLACK;
        this.fadeType = FadeType.BLACK;
        this.frameCount = 0;
        // For black fade, fadeR/G/B represent "darkness" (0 = full color, 1 = no color)
        this.fadeR = 0f;
        this.fadeG = 0f;
        this.fadeB = 0f;
        this.onFadeComplete = onComplete;
        this.holdDuration = holdFrames;
        this.holdFrameCount = 0;
    }

    /**
     * Start a fade-from-black transition (screen returns from black to normal).
     * Used after level loads, menu transitions, etc.
     *
     * @param onComplete Callback to execute when fade completes (can be null)
     */
    public void startFadeFromBlack(Runnable onComplete) {
        this.state = FadeState.FADING_FROM_BLACK;
        this.fadeType = FadeType.BLACK;
        this.frameCount = 0;
        // Start at full darkness (all channels suppressed)
        this.fadeR = 1f;
        this.fadeG = 1f;
        this.fadeB = 1f;
        this.onFadeComplete = onComplete;
        this.holdDuration = 0;
        this.holdFrameCount = 0;
    }

    /**
     * Update the fade state. Call once per frame.
     */
    public void update() {
        switch (state) {
            case FADING_TO_WHITE:
                updateFadeToWhite();
                break;
            case HOLD_WHITE:
                updateHoldWhite();
                break;
            case FADING_FROM_WHITE:
                updateFadeFromWhite();
                break;
            case FADING_TO_BLACK:
                updateFadeToBlack();
                break;
            case HOLD_BLACK:
                updateHoldBlack();
                break;
            case FADING_FROM_BLACK:
                updateFadeFromBlack();
                break;
            case NONE:
            default:
                break;
        }
    }

    private void updateFadeToWhite() {
        frameCount++;

        // Determine which channel to increment based on frame count
        // Frames 1-7: Red, Frames 8-14: Green, Frames 15-21: Blue
        if (frameCount <= FRAMES_PER_CHANNEL) {
            // Increment red
            fadeR = Math.min(1f, fadeR + CHANNEL_INCREMENT);
        } else if (frameCount <= FRAMES_PER_CHANNEL * 2) {
            // Increment green
            fadeG = Math.min(1f, fadeG + CHANNEL_INCREMENT);
        } else if (frameCount <= FADE_DURATION) {
            // Increment blue
            fadeB = Math.min(1f, fadeB + CHANNEL_INCREMENT);
        }

        // Check if fade is complete
        if (frameCount >= FADE_DURATION) {
            // Ensure we're at full white
            fadeR = 1f;
            fadeG = 1f;
            fadeB = 1f;

            if (holdDuration > 0) {
                // Transition to hold state
                state = FadeState.HOLD_WHITE;
                holdFrameCount = 0;
            } else {
                // Fade complete
                completeFade();
            }
        }
    }

    private void updateHoldWhite() {
        holdFrameCount++;
        if (holdFrameCount >= holdDuration) {
            completeFade();
        }
    }

    private void updateFadeFromWhite() {
        frameCount++;

        // Reverse of fade-to-white: decrement blue, then green, then red
        // Frames 1-7: Blue decreases, Frames 8-14: Green decreases, Frames 15-21: Red decreases
        if (frameCount <= FRAMES_PER_CHANNEL) {
            // Decrement blue
            fadeB = Math.max(0f, fadeB - CHANNEL_INCREMENT);
        } else if (frameCount <= FRAMES_PER_CHANNEL * 2) {
            // Decrement green
            fadeG = Math.max(0f, fadeG - CHANNEL_INCREMENT);
        } else if (frameCount <= FADE_DURATION) {
            // Decrement red
            fadeR = Math.max(0f, fadeR - CHANNEL_INCREMENT);
        }

        // Check if fade is complete
        if (frameCount >= FADE_DURATION) {
            // Ensure we're at zero (no overlay)
            fadeR = 0f;
            fadeG = 0f;
            fadeB = 0f;
            completeFade();
        }
    }

    private void updateFadeToBlack() {
        frameCount++;

        // Black fade: RGB channels decrement sequentially (like Sonic 2)
        // Frames 1-7: Red decreases, Frames 8-14: Green decreases, Frames 15-21: Blue decreases
        // fadeR/G/B represent "darkness" (0 = full color, 1 = no color)
        if (frameCount <= FRAMES_PER_CHANNEL) {
            // Increment red darkness
            fadeR = Math.min(1f, fadeR + CHANNEL_INCREMENT);
        } else if (frameCount <= FRAMES_PER_CHANNEL * 2) {
            // Increment green darkness
            fadeG = Math.min(1f, fadeG + CHANNEL_INCREMENT);
        } else if (frameCount <= FADE_DURATION) {
            // Increment blue darkness
            fadeB = Math.min(1f, fadeB + CHANNEL_INCREMENT);
        }

        // Check if fade is complete
        if (frameCount >= FADE_DURATION) {
            // Ensure we're at full black
            fadeR = 1f;
            fadeG = 1f;
            fadeB = 1f;

            if (holdDuration > 0) {
                // Transition to hold state
                state = FadeState.HOLD_BLACK;
                holdFrameCount = 0;
            } else {
                completeFade();
            }
        }
    }

    private void updateHoldBlack() {
        holdFrameCount++;
        if (holdFrameCount >= holdDuration) {
            completeFade();
        }
    }

    private void updateFadeFromBlack() {
        frameCount++;

        // Reverse of fade-to-black: increment colors back (Blue first, then Green, then Red)
        // Frames 1-7: Blue increases, Frames 8-14: Green increases, Frames 15-21: Red increases
        if (frameCount <= FRAMES_PER_CHANNEL) {
            // Decrement blue darkness
            fadeB = Math.max(0f, fadeB - CHANNEL_INCREMENT);
        } else if (frameCount <= FRAMES_PER_CHANNEL * 2) {
            // Decrement green darkness
            fadeG = Math.max(0f, fadeG - CHANNEL_INCREMENT);
        } else if (frameCount <= FADE_DURATION) {
            // Decrement red darkness
            fadeR = Math.max(0f, fadeR - CHANNEL_INCREMENT);
        }

        // Check if fade is complete
        if (frameCount >= FADE_DURATION) {
            // Ensure we're at zero (no overlay)
            fadeR = 0f;
            fadeG = 0f;
            fadeB = 0f;
            completeFade();
        }
    }

    private void completeFade() {
        FadeState previousState = state;
        if ((previousState == FadeState.FADING_TO_BLACK || previousState == FadeState.FADING_TO_WHITE)
                && holdDuration == 0) {
            state = (previousState == FadeState.FADING_TO_BLACK)
                    ? FadeState.HOLD_BLACK
                    : FadeState.HOLD_WHITE;
            holdDuration = 1;
            holdFrameCount = 0;
            return;
        }
        state = FadeState.NONE;

        if (onFadeComplete != null) {
            Runnable callback = onFadeComplete;
            onFadeComplete = null;
            callback.run();
        }
    }

    /**
     * Render the fade overlay. Call after all game rendering is complete.
     *
     * @param gl The OpenGL context
     */
    public void render(GL2 gl) {
        if (state == FadeState.NONE) {
            return;
        }

        if (fadeType == FadeType.WHITE) {
            renderWhiteFade(gl);
        } else {
            renderBlackFade(gl);
        }
    }

    /**
     * Render white fade using additive blending.
     */
    private void renderWhiteFade(GL2 gl) {
        // Skip if fade color is zero (nothing to render)
        if (fadeR == 0f && fadeG == 0f && fadeB == 0f) {
            return;
        }

        // Skip if no shader available
        if (fadeShader == null) {
            return;
        }

        // Save OpenGL state
        gl.glPushAttrib(GL2.GL_ALL_ATTRIB_BITS);

        // Set up additive blending: result = src + dst
        gl.glEnable(GL2.GL_BLEND);
        gl.glBlendFunc(GL2.GL_ONE, GL2.GL_ONE);

        // Disable depth test and texturing
        gl.glDisable(GL2.GL_DEPTH_TEST);
        gl.glDisable(GL2.GL_TEXTURE_2D);

        // Use fade shader
        fadeShader.use(gl);

        // Set the fade color uniform
        if (fadeColorLocation < 0) {
            fadeColorLocation = gl.glGetUniformLocation(fadeShader.getProgramId(), "FadeColor");
        }
        if (fadeColorLocation >= 0) {
            gl.glUniform3f(fadeColorLocation, fadeR, fadeG, fadeB);
        }

        // Save and reset modelview matrix
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // Draw fullscreen quad (using screen coordinates 0-320 x 0-224)
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(0, 0);
        gl.glVertex2f(320, 0);
        gl.glVertex2f(320, 224);
        gl.glVertex2f(0, 224);
        gl.glEnd();

        // Restore modelview matrix
        gl.glPopMatrix();

        // Stop using shader
        fadeShader.stop(gl);

        // Restore OpenGL state
        gl.glPopAttrib();
    }

    /**
     * Render black fade using subtractive blending with shader.
     * Uses sequential per-channel darkening like Sonic 2:
     * - Each color's red channel decreases first, then green, then blue
     * - fadeR/G/B represent how much to subtract from each channel (0 to 1)
     */
    private void renderBlackFade(GL2 gl) {
        // Skip if no darkness (nothing to render)
        if (fadeR == 0f && fadeG == 0f && fadeB == 0f) {
            return;
        }

        // Skip if no shader available
        if (fadeShader == null) {
            return;
        }

        // Save OpenGL state
        gl.glPushAttrib(GL2.GL_ALL_ATTRIB_BITS);

        // Set up subtractive blending: result = dst - src
        // This subtracts our fade color from the screen
        gl.glEnable(GL2.GL_BLEND);
        gl.glBlendEquation(GL2.GL_FUNC_REVERSE_SUBTRACT);
        gl.glBlendFunc(GL2.GL_ONE, GL2.GL_ONE);

        // Disable depth test and texturing
        gl.glDisable(GL2.GL_DEPTH_TEST);
        gl.glDisable(GL2.GL_TEXTURE_2D);

        // Use fade shader
        fadeShader.use(gl);

        // Set the fade color uniform (amount to subtract per channel)
        if (fadeColorLocation < 0) {
            fadeColorLocation = gl.glGetUniformLocation(fadeShader.getProgramId(), "FadeColor");
        }
        if (fadeColorLocation >= 0) {
            gl.glUniform3f(fadeColorLocation, fadeR, fadeG, fadeB);
        }

        // Save and reset modelview matrix
        gl.glMatrixMode(GL2.GL_MODELVIEW);
        gl.glPushMatrix();
        gl.glLoadIdentity();

        // Draw fullscreen quad
        gl.glBegin(GL2.GL_QUADS);
        gl.glVertex2f(0, 0);
        gl.glVertex2f(320, 0);
        gl.glVertex2f(320, 224);
        gl.glVertex2f(0, 224);
        gl.glEnd();

        // Restore modelview matrix
        gl.glPopMatrix();

        // Stop using shader
        fadeShader.stop(gl);

        // Reset blend equation to default (additive)
        gl.glBlendEquation(GL2.GL_FUNC_ADD);

        // Restore OpenGL state
        gl.glPopAttrib();
    }

    /**
     * Check if a fade is currently active.
     */
    public boolean isActive() {
        return state != FadeState.NONE;
    }

    /**
     * Get the current fade state.
     */
    public FadeState getState() {
        return state;
    }

    /**
     * Get the current fade color values.
     *
     * @return Array of [r, g, b] values from 0.0 to 1.0
     */
    public float[] getFadeColor() {
        return new float[] { fadeR, fadeG, fadeB };
    }

    /**
     * Get the current frame count of the fade.
     */
    public int getFrameCount() {
        return frameCount;
    }

    /**
     * Cancel any active fade and reset to normal.
     */
    public void cancel() {
        state = FadeState.NONE;
        fadeType = FadeType.WHITE;
        frameCount = 0;
        fadeR = 0f;
        fadeG = 0f;
        fadeB = 0f;
        fadeAlpha = 0f;
        onFadeComplete = null;
        holdDuration = 0;
        holdFrameCount = 0;
    }

    /**
     * Get the current fade type.
     */
    public FadeType getFadeType() {
        return fadeType;
    }

    /**
     * Get the current fade alpha (for black fades).
     */
    public float getFadeAlpha() {
        return fadeAlpha;
    }
}
