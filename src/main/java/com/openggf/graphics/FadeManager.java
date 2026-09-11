package com.openggf.graphics;

import com.openggf.game.rewind.RewindSnapshottable;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL14.*;
import static org.lwjgl.opengl.GL20.*;

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
public class FadeManager implements RewindSnapshottable<FadeManagerSnapshot> {

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

    // Current fade state
    private FadeState state = FadeState.NONE;
    private int frameCount = 0;

    // Standard fade duration in color steps (7 per channel × 3 channels = 21 steps).
    // On Mega Drive, PaletteFadeOut/In uses dbf d4 with d4=$15 (22 VBla periods),
    // but only 21 perform color updates; the 22nd is a no-op. The engine's
    // completeFade() auto-hold adds 1 frame to match the full 22-frame ROM timing.
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
    private boolean holdRestoredFrameForNextUpdate;
    private int reversePresentationDepth;
    private boolean exactToFadeDuration;

    // Hold duration in frames (for optional pause at full white)
    private int holdDuration = 0;
    private int holdFrameCount = 0;

    // Per-fade effective parameters (computed in startFade methods).
    // Allows slow fades (e.g., ROM Level_FadeDemo uses 60-frame fade with
    // palette decrements every 3rd frame) by adjusting frames-per-channel
    // and increment values while keeping the same sequential-channel algorithm.
    private int effectiveFPC = FRAMES_PER_CHANNEL;
    private float effectiveIncrement = CHANNEL_INCREMENT;
    private int effectiveDuration = FADE_DURATION;

    // Shader program reference (set by GraphicsManager)
    private ShaderProgram fadeShader;
    private final QuadRenderer quadRenderer = new QuadRenderer();

    // Cached uniform location
    private int fadeColorLocation = -1;

    public FadeManager() {
    }

    /**
     * Resets mutable state without destroying the singleton instance.
     * Cached references held by other classes remain valid.
     * Preserves the shader and quad renderer (configuration), clears fade state.
     */
    public void resetState() {
        cancel();
        effectiveFPC = FRAMES_PER_CHANNEL;
        effectiveIncrement = CHANNEL_INCREMENT;
        effectiveDuration = FADE_DURATION;
        exactToFadeDuration = false;
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
        this.holdRestoredFrameForNextUpdate = false;
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
        this.holdRestoredFrameForNextUpdate = false;
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
     * Skips the next {@link #update()} step of the fade that was just started.
     *
     * <p>The ROM fade routines are synchronous wait loops whose first action is a
     * V-int wait, not a colour step: {@code Pal_FadeToWhite} does
     * {@code move.b #VintID_Fade,(Vint_routine).w / bsr.w WaitForVint /
     * bsr.s .UpdateAllColours} (docs/s2disasm/s2.asm:3571-3582; the S1 and S3K
     * equivalents have the same shape). The V-int on which the caller decided to
     * fade has therefore already been consumed by the loop iteration that made the
     * decision, so the first colour step belongs to the following V-int. Engine
     * callers that start a fade from inside a frame's logic — where
     * {@code FadeManager.update()} still runs later in that same frame — use this
     * to keep the fade window the same length as the ROM's.
     */
    public void deferFirstStepToNextVint() {
        this.holdRestoredFrameForNextUpdate = true;
    }

    /** Holds a fully opaque white overlay until another fade is started or cancelled. */
    public void holdWhite() {
        holdOpaque(FadeState.HOLD_WHITE, FadeType.WHITE);
    }

    /** Holds a fully opaque black overlay until another fade is started or cancelled. */
    public void holdBlack() {
        holdOpaque(FadeState.HOLD_BLACK, FadeType.BLACK);
    }

    private void holdOpaque(FadeState holdState, FadeType type) {
        holdRestoredFrameForNextUpdate = false;
        state = holdState;
        fadeType = type;
        frameCount = 0;
        fadeR = 1f;
        fadeG = 1f;
        fadeB = 1f;
        fadeAlpha = type == FadeType.BLACK ? 1f : 0f;
        onFadeComplete = null;
        holdDuration = Integer.MAX_VALUE;
        holdFrameCount = 0;
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
        startFadeToBlack(onComplete, holdFrames, 0);
    }

    /**
     * Start a fade-to-black transition with optional hold and custom duration.
     * <p>
     * The standard fade takes {@link #FADE_DURATION} frames (21 color steps matching
     * the Mega Drive's 7-level-per-channel palette). For slow fades (e.g., the demo
     * ending fadeout in credits), pass a larger totalDuration.
     * <p>
     * ROM reference: Level_FadeDemo (sonic.asm:3097) uses a 60-frame slow fade where
     * FadeOut_ToBlack is called every 3rd frame (v_palchgspeed pattern), giving 20
     * palette updates. The standard PaletteFadeOut uses 22 VBla periods (dbf d4 with
     * d4=$15 = 21 color steps + 1 no-op frame).
     *
     * @param onComplete     Callback to execute when fade completes (can be null)
     * @param holdFrames     Number of frames to hold at full black before completing
     * @param totalDuration  Total fade duration in frames (0 = use default FADE_DURATION).
     *                       Must be divisible by 3 for even channel distribution.
     */
    public void startFadeToBlack(Runnable onComplete, int holdFrames, int totalDuration) {
        this.holdRestoredFrameForNextUpdate = false;
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
        if (totalDuration > 0) {
            this.effectiveFPC = totalDuration / 3;
            this.effectiveIncrement = 1.0f / this.effectiveFPC;
            this.effectiveDuration = totalDuration;
            this.exactToFadeDuration = true;
        } else {
            this.effectiveFPC = FRAMES_PER_CHANNEL;
            this.effectiveIncrement = CHANNEL_INCREMENT;
            this.effectiveDuration = FADE_DURATION;
            this.exactToFadeDuration = false;
        }
    }

    /**
     * Start a fade-from-black transition (screen returns from black to normal).
     * Used after level loads, menu transitions, etc.
     *
     * @param onComplete Callback to execute when fade completes (can be null)
     */
    public void startFadeFromBlack(Runnable onComplete) {
        startFadeFromBlack(onComplete, 0);
    }

    /**
     * Starts a fade from black with optional fully revealed terminal VBlanks.
     * The S3K level reveal services 22 VBlanks: 21 change color and the last is
     * a no-op (Palette_fade_timer=$16 at sonic3k.asm:7875-7892).
     */
    public void startFadeFromBlack(Runnable onComplete, int terminalNoOpFrames) {
        if (terminalNoOpFrames < 0) {
            throw new IllegalArgumentException("terminalNoOpFrames must not be negative");
        }
        this.holdRestoredFrameForNextUpdate = false;
        this.state = FadeState.FADING_FROM_BLACK;
        this.fadeType = FadeType.BLACK;
        this.frameCount = 0;
        // Start at full darkness (all channels suppressed)
        this.fadeR = 1f;
        this.fadeG = 1f;
        this.fadeB = 1f;
        this.onFadeComplete = onComplete;
        this.holdDuration = terminalNoOpFrames;
        this.holdFrameCount = 0;
    }

    /**
     * Update the fade state. Call once per frame.
     */
    public void update() {
        if (reversePresentationDepth > 0) {
            return;
        }
        if (holdRestoredFrameForNextUpdate) {
            holdRestoredFrameForNextUpdate = false;
            return;
        }
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

    /**
     * Suppresses display-driven fade advancement while rewind restores historical
     * fade snapshots for rendering.
     */
    public void beginReversePresentation() {
        reversePresentationDepth++;
        holdRestoredFrameForNextUpdate = false;
    }

    public void endReversePresentation() {
        if (reversePresentationDepth > 0) {
            reversePresentationDepth--;
        }
        if (reversePresentationDepth == 0) {
            holdRestoredFrameForNextUpdate = false;
        }
    }

    public boolean isReversePresentationActive() {
        return reversePresentationDepth > 0;
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
        if (holdDuration == Integer.MAX_VALUE) {
            return;
        }
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

        // Black fade: RGB channels decrement sequentially (like the Mega Drive).
        // Standard: 7 frames per channel, 21 total. Slow (demo ending): 20 frames
        // per channel, 60 total. fadeR/G/B represent "darkness" (0 = full color,
        // 1 = no color).
        if (frameCount <= effectiveFPC) {
            // Increment red darkness
            fadeR = Math.min(1f, fadeR + effectiveIncrement);
        } else if (frameCount <= effectiveFPC * 2) {
            // Increment green darkness
            fadeG = Math.min(1f, fadeG + effectiveIncrement);
        } else if (frameCount <= effectiveDuration) {
            // Increment blue darkness
            fadeB = Math.min(1f, fadeB + effectiveIncrement);
        }

        // Check if fade is complete
        if (frameCount >= effectiveDuration) {
            // Ensure we're at full black
            fadeR = 1f;
            fadeG = 1f;
            fadeB = 1f;

            if (holdDuration > 0) {
                // Transition to hold state
                state = FadeState.HOLD_BLACK;
                holdFrameCount = 0;
            } else {
                if (exactToFadeDuration) {
                    state = FadeState.HOLD_BLACK;
                }
                completeFade();
            }
        }
    }

    private void updateHoldBlack() {
        if (holdDuration == Integer.MAX_VALUE) {
            return;
        }
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
            if (holdFrameCount < holdDuration) {
                holdFrameCount++;
                return;
            }
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

        // For "from" fades: clear overlay and transition to NONE
        if (previousState == FadeState.FADING_FROM_BLACK || previousState == FadeState.FADING_FROM_WHITE) {
            state = FadeState.NONE;
            fadeR = 0f;
            fadeG = 0f;
            fadeB = 0f;
            if (onFadeComplete != null) {
                Runnable callback = onFadeComplete;
                onFadeComplete = null;
                callback.run();
            }
            return;
        }

        // For HOLD states (from completed "to" fades):
        // Execute callback, then persist overlay if callback didn't start a new fade.
        // On original hardware the palette stays faded until explicitly unfaded.
        if (onFadeComplete != null) {
            Runnable callback = onFadeComplete;
            onFadeComplete = null;
            callback.run();

            // If callback started a new fade, state has already changed
            if (state != previousState) {
                return;
            }
            // Callback didn't start a new fade — persist overlay indefinitely
            // until the next startFade*() or cancel() clears it
            holdDuration = Integer.MAX_VALUE;
        } else {
            // No callback — nothing to keep the overlay for, transition to NONE
            state = FadeState.NONE;
        }
    }

    /**
     * Render the fade overlay. Call after all game rendering is complete.
     */
    public void render() {
        if (state == FadeState.NONE) {
            return;
        }
        quadRenderer.init();

        if (fadeType == FadeType.WHITE) {
            renderWhiteFade();
        } else {
            renderBlackFade();
        }
    }

    /**
     * Render white fade using additive blending.
     */
    private void renderWhiteFade() {
        // Skip if fade color is zero (nothing to render)
        if (fadeR == 0f && fadeG == 0f && fadeB == 0f) {
            return;
        }

        // Skip if no shader available
        if (fadeShader == null) {
            return;
        }

        // Save OpenGL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        int[] prevBlendSrc = new int[1];
        int[] prevBlendDst = new int[1];
        glGetIntegerv(GL_BLEND_SRC_ALPHA, prevBlendSrc);
        glGetIntegerv(GL_BLEND_DST_ALPHA, prevBlendDst);

        // Set up additive blending: result = src + dst
        glEnable(GL_BLEND);
        glBlendFunc(GL_ONE, GL_ONE);

        // Disable depth test for fullscreen overlay
        glDisable(GL_DEPTH_TEST);

        // Use fade shader
        fadeShader.use();

        // Set the fade color uniform
        if (fadeColorLocation < 0) {
            fadeColorLocation = glGetUniformLocation(fadeShader.getProgramId(), "FadeColor");
        }
        if (fadeColorLocation >= 0) {
            glUniform3f(fadeColorLocation, fadeR, fadeG, fadeB);
        }

        // Draw fullscreen quad (shader generates positions from gl_VertexID)
        quadRenderer.draw(0, 0, 320, 224);

        // Stop using shader
        fadeShader.stop();

        // Restore OpenGL state
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (depthTestWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        glBlendFunc(prevBlendSrc[0], prevBlendDst[0]);
    }

    /**
     * Render black fade using subtractive blending with shader.
     * Uses sequential per-channel darkening like Sonic 2:
     * - Each color's red channel decreases first, then green, then blue
     * - fadeR/G/B represent how much to subtract from each channel (0 to 1)
     */
    private void renderBlackFade() {
        // Skip if no darkness (nothing to render)
        if (fadeR == 0f && fadeG == 0f && fadeB == 0f) {
            return;
        }

        // Skip if no shader available
        if (fadeShader == null) {
            return;
        }

        // Save OpenGL state
        boolean blendWasEnabled = glIsEnabled(GL_BLEND);
        boolean depthTestWasEnabled = glIsEnabled(GL_DEPTH_TEST);
        int[] prevBlendSrc = new int[1];
        int[] prevBlendDst = new int[1];
        int[] prevBlendEquation = new int[1];
        glGetIntegerv(GL_BLEND_SRC_ALPHA, prevBlendSrc);
        glGetIntegerv(GL_BLEND_DST_ALPHA, prevBlendDst);
        glGetIntegerv(GL_BLEND_EQUATION_RGB, prevBlendEquation);

        // Set up subtractive blending: result = dst - src
        // This subtracts our fade color from the screen
        glEnable(GL_BLEND);
        glBlendEquation(GL_FUNC_REVERSE_SUBTRACT);
        glBlendFunc(GL_ONE, GL_ONE);

        // Disable depth test for fullscreen overlay
        glDisable(GL_DEPTH_TEST);

        // Use fade shader
        fadeShader.use();

        // Set the fade color uniform (amount to subtract per channel)
        if (fadeColorLocation < 0) {
            fadeColorLocation = glGetUniformLocation(fadeShader.getProgramId(), "FadeColor");
        }
        if (fadeColorLocation >= 0) {
            glUniform3f(fadeColorLocation, fadeR, fadeG, fadeB);
        }

        // Draw fullscreen quad (shader generates positions from gl_VertexID)
        quadRenderer.draw(0, 0, 320, 224);

        // Stop using shader
        fadeShader.stop();

        // Restore OpenGL state
        glBlendEquation(prevBlendEquation[0]);
        if (!blendWasEnabled) {
            glDisable(GL_BLEND);
        }
        if (depthTestWasEnabled) {
            glEnable(GL_DEPTH_TEST);
        }
        glBlendFunc(prevBlendSrc[0], prevBlendDst[0]);
    }

    /**
     * Check if a fade is currently active.
     */
    public boolean isActive() {
        return state != FadeState.NONE;
    }

    /**
     * True while a fade is in flight AND has a completion callback that has not
     * yet run. {@link #restore(FadeManagerSnapshot)} deliberately does not restore
     * {@link #onFadeComplete} (a transient callback closure, not restorable
     * state), so a rewind restore landing inside this window silently orphans
     * whatever the callback was going to do -- e.g. advancing zone/act counters,
     * requesting a special stage, or loading the next level -- with no other
     * flag observing it. Object/GameLoop code that starts a fade whose callback
     * performs a level/mode transition should be covered by
     * {@code GameLoop.isNonRewindableTransitionPending()}, which folds this in
     * game-agnostically rather than requiring every such call site to also set
     * one of the existing narrower transition-pending flags. See
     * ssentry-rewind-report.md.
     */
    public boolean hasPendingCompletion() {
        return state != FadeState.NONE && onFadeComplete != null;
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
        holdRestoredFrameForNextUpdate = false;
        reversePresentationDepth = 0;
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
        exactToFadeDuration = false;
    }

    /**
     * Clears any held or in-flight overlay immediately, modelling a ROM routine
     * that writes a whole new palette straight to the active palette instead of
     * fading back into it — {@code PalLoad_Now} in Sonic 2
     * (docs/s2disasm/s2.asm:3799) and {@code PalLoad} in Sonic 1
     * (docs/s1disasm/sonic.asm:3383). Unlike {@link #cancel()} this leaves the
     * reverse-presentation depth alone, so a rewind restore in flight keeps
     * owning fade advancement.
     */
    public void clearOverlayForImmediatePaletteLoad() {
        holdRestoredFrameForNextUpdate = false;
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
        exactToFadeDuration = false;
    }

    public void cleanup() {
        quadRenderer.cleanup();
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

    @Override
    public String key() {
        return "fademanager";
    }

    @Override
    public FadeManagerSnapshot capture() {
        return new FadeManagerSnapshot(
                state, frameCount, fadeR, fadeG, fadeB, fadeAlpha,
                fadeType, holdDuration, holdFrameCount,
                effectiveFPC, effectiveIncrement, effectiveDuration, exactToFadeDuration,
                onFadeComplete != null);
    }

    @Override
    public void restore(FadeManagerSnapshot snapshot) {
        this.state = snapshot.state();
        this.frameCount = snapshot.frameCount();
        this.fadeR = snapshot.fadeR();
        this.fadeG = snapshot.fadeG();
        this.fadeB = snapshot.fadeB();
        this.fadeAlpha = snapshot.fadeAlpha();
        this.fadeType = snapshot.fadeType();
        this.holdDuration = snapshot.holdDuration();
        this.holdFrameCount = snapshot.holdFrameCount();
        this.effectiveFPC = snapshot.effectiveFPC();
        this.effectiveIncrement = snapshot.effectiveIncrement();
        this.effectiveDuration = snapshot.effectiveDuration();
        this.exactToFadeDuration = snapshot.exactToFadeDuration();
        // Note: onFadeComplete callback is NOT restored (transient)
        this.onFadeComplete = null;
        this.holdRestoredFrameForNextUpdate = true;
    }
}
