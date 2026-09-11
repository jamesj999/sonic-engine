package com.openggf.game;

import com.openggf.game.save.SaveReason;
import com.openggf.game.resources.PlcLifecyclePhase;

import java.util.Optional;

/**
 * Game-agnostic interface for ending/credits sequences.
 * <p>
 * Each game module provides its own implementation that manages the full
 * ending flow: cutscene, credits text, demo playback, and post-credits.
 * The {@link EndingPhase} returned by {@link #getCurrentPhase()} drives
 * GameLoop mode routing so the engine knows which subsystems to update.
 * <p>
 * Default methods support S1-style demo playback during credits, where the
 * credits text screen requests zone loads and feeds recorded input. Games
 * that do not interleave demos (e.g., S2) can ignore these defaults.
 */
public interface EndingProvider {
    default Optional<PlcLifecyclePhase> plcLifecyclePhaseOverride() {
        return Optional.empty();
    }
    /**
     * Returns a save request to issue when the engine starts the ending
     * transition. Most games do not save here; S2 persists progression before
     * entering credits.
     */
    default Optional<SaveReason> saveReasonOnEndingStart() {
        return Optional.empty();
    }


    /**
     * Called once when the ending sequence is triggered.
     * Implementations should set up initial state, load art/palettes,
     * and begin the first phase.
     */
    void initialize();

    /**
     * Per-frame update. Called every frame while the ending is active.
     * Advances internal timers, animation, and phase transitions.
     */
    void update();

    /**
     * Render the current ending state.
     * Called each frame after {@link #update()}.
     */
    void draw();

    /**
     * Returns the current phase of the ending sequence.
     * The game loop uses this to decide which subsystems are active
     * (e.g., level rendering during CREDITS_DEMO, text rendering
     * during CREDITS_TEXT).
     *
     * @return the current ending phase
     */
    EndingPhase getCurrentPhase();

    /**
     * Returns whether the entire ending sequence is complete.
     * When true, the engine should transition back to the title screen.
     *
     * @return true if all ending phases have finished
     */
    boolean isComplete();

    /**
     * Sets the OpenGL clear color for the current ending state.
     * Default is black; games can override to use VDP background color
     * (e.g., palette 2 color 0 for sky blue during cutscene sky phases).
     */
    default void setClearColor() {
        org.lwjgl.opengl.GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    // --- Demo playback support (S1-style interleaved credits/demos) ---

    /**
     * Returns whether the credits text phase is requesting a demo zone load.
     * The game loop checks this to know when to load a level for demo playback.
     *
     * @return true if a demo zone load is pending
     */
    default boolean hasDemoLoadRequest() {
        return false;
    }

    /**
     * Acknowledges and clears the demo load request.
     * Called by the game loop after initiating the zone load.
     */
    default void consumeDemoLoadRequest() {
        // no-op
    }

    /**
     * Returns the zone index for the pending demo load.
     *
     * @return zone index (0-based)
     */
    default int getDemoZone() {
        return 0;
    }

    /**
     * Returns the act index for the pending demo load.
     *
     * @return act index (0-based)
     */
    default int getDemoAct() {
        return 0;
    }

    /**
     * Returns the starting X position for the demo player.
     *
     * @return X coordinate in level pixels
     */
    default int getDemoStartX() {
        return 0;
    }

    /**
     * Returns the starting Y position for the demo player.
     *
     * @return Y coordinate in level pixels
     */
    default int getDemoStartY() {
        return 0;
    }

    /**
     * Called after the demo zone has been fully loaded.
     * Implementations can use this to finalize demo setup
     * (e.g., position camera, start playback timer).
     */
    default void onDemoZoneLoaded() {
        // no-op
    }

    /**
     * Returns the current frame's input mask for demo playback.
     * Bits follow the standard Mega Drive joypad layout.
     *
     * @return input bitmask for the current demo frame
     */
    default int getDemoInputMask() {
        return 0;
    }

    /**
     * Returns whether demo gameplay should run this frame.
     * <p>
     * Some endings have non-interactive demo sub-phases (for example, Sonic 1's
     * hidden load delay and fade-in before {@code MoveSonicInDemo} starts).
     * During those phases the engine should render the demo scene but skip
     * physics/input playback.
     *
     * @return true if demo physics and input playback should run this frame
     */
    default boolean shouldRunDemoGameplay() {
        return true;
    }

    /**
     * Returns whether demo sprites/objects should render over the global fade.
     * Used by Sonic 1 credits demo fade-in, where sprites are already visible
     * while the level tiles are still being revealed.
     *
     * @return true if the engine should draw the sprite/object pass after fade
     */
    default boolean shouldRenderDemoSpritesOverFade() {
        return false;
    }

    /**
     * Returns whether the frozen credits-demo scene should still advance its
     * non-player preroll state this frame.
     * <p>
     * Sonic 1 can override this when it truly needs hidden preroll work.
     * The default behavior is to keep frozen demo scenes static.
     *
     * @return true if frozen demo scene prep should advance this frame
     */
    default boolean shouldAdvanceFrozenDemoScene() {
        return false;
    }

    /**
     * Returns whether camera scrolling should be frozen.
     * Used during credits text phases where the background is static.
     *
     * @return true if scrolling should be suppressed
     */
    default boolean isScrollFrozen() {
        return false;
    }

    /**
     * Returns whether the demo phase is requesting a return to credits text.
     * The game loop checks this to know when to transition back from
     * demo playback to the text overlay.
     *
     * @return true if a text return is pending
     */
    default boolean hasTextReturnRequest() {
        return false;
    }

    /**
     * Acknowledges and clears the text return request.
     * Called by the game loop after transitioning back to credits text.
     */
    default void consumeTextReturnRequest() {
        // no-op
    }

    /**
     * Returns lamppost state for the current credits demo, or {@code null}
     * if the demo starts from the zone's normal start position.
     * <p>
     * When non-null, the game loop restores player position, camera, and
     * water state from the returned snapshot instead of using the zone's
     * start coordinates.
     *
     * @return lamppost state snapshot, or null for normal start position
     */
    default DemoLamppostState getDemoLamppostState() {
        return null;
    }

    /**
     * Called when transitioning back from demo playback to credits text.
     * Implementations can use this to reset demo state and prepare
     * the next credit text display.
     */
    default void onReturnToText() {
        // no-op
    }

    /**
     * Returns whether the ending currently needs the DEZ level background
     * rendered behind the cutscene sprites. When true, the engine calls
     * {@link com.openggf.level.LevelManager#renderEndingBackground(int)}
     * before {@link #draw()}.
     *
     * @return true if level background should be rendered this frame
     */
    default boolean needsLevelBackground() {
        return false;
    }

    /**
     * Returns the current background vertical scroll value.
     * Maps to ROM's Vscroll_Factor_BG during the ending sequence.
     * Only meaningful when {@link #needsLevelBackground()} returns true.
     *
     * @return BG vertical scroll in pixels
     */
    default int getBackgroundVscroll() {
        return 0;
    }

    /**
     * Returns the backdrop color override for the level background during the
     * ending cutscene. The BG shader normally uses the level's stored palette
     * line 2 color 0 as backdrop, but during the ending the cutscene fades
     * display palettes from white to target — the backdrop must match.
     * <p>
     * Returns a 3-element float array {r, g, b} in [0..1] range, or null
     * if no override is needed (use level default).
     *
     * @return backdrop color override as {r, g, b}, or null
     */
    default float[] getBackdropColorOverride() {
        return null;
    }

    // --- Post-credits input handling ---

    /**
     * Updates the post-credits phase with input access.
     * <p>
     * Post-credits screens (S1 TRY AGAIN/END, S2 logo flash) need input
     * for user-initiated skip/exit. The default delegates to {@link #update()}.
     *
     * @param inputHandler the input handler for detecting key presses
     */
    default void updatePostCredits(com.openggf.control.InputHandler inputHandler) {
        update();
    }

    /**
     * Checks and consumes a pending exit request from the post-credits phase.
     * Returns true if the engine should transition back to the title screen.
     * <p>
     * Default returns false (rely on {@link #isComplete()} instead).
     *
     * @return true if an exit was requested and consumed
     */
    default boolean consumePostCreditsExitRequest() {
        return false;
    }
}
