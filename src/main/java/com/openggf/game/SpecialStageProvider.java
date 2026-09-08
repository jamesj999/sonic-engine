package com.openggf.game;

import static org.lwjgl.opengl.GL11.glClearColor;

import com.openggf.audio.GameMusic;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.rewind.RewindSnapshottable;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Interface for special stage implementations that award Chaos Emeralds.
 * Extends {@link MiniGameProvider} with emerald-specific functionality.
 *
 * <p>
 * Access method varies by game:
 * <ul>
 * <li>Sonic 1: Giant rings (hidden in levels)</li>
 * <li>Sonic 2: Star posts with 50+ rings</li>
 * <li>Sonic 3&K: Giant rings (Blue Sphere stages)</li>
 * </ul>
 */
public interface SpecialStageProvider extends MiniGameProvider {
    String SPECIAL_STAGE_REWIND_KEY = "special-stage-runtime";

    /**
     * Returns the optional developer controls owned by this special stage.
     *
     * <p>Providers opt in to each control explicitly. The game loop consults
     * this profile before routing a debug key, so an omitted capability is
     * unavailable rather than a silent default-method call.</p>
     */
    default SpecialStageDebugCapabilities debugCapabilities() {
        return SpecialStageDebugCapabilities.NONE;
    }

    /**
     * Selects the special-stage index for a new entry and advances any
     * game-owned cursor state. The default matches the S1/S2 sequential
     * cursor; games with different ROM selection policy override here.
     */
    default int consumeStageIndexForEntry(GameStateManager gameState) {
        return gameState.consumeCurrentSpecialStageIndexAndAdvance();
    }

    /**
     * The PLC/V-int lifecycle phase the special stage's current represented
     * row belongs to.
     *
     * <p>A special stage is not one V-int handler for its whole duration: the
     * ROM's entry sequence runs other handlers before it installs its own. The
     * phase decides which owner services the row -- including whether the row
     * is a DMA-queue service boundary -- so a provider whose entry sequence
     * runs a different handler reports that handler's phase here. The default
     * is the stage's own handler.</p>
     */
    default PlcLifecyclePhase specialStagePlcLifecyclePhase() {
        return PlcLifecyclePhase.SPECIAL_STAGE;
    }

    /**
     * Gets the SFX ID to play when entering/exiting the special stage flow.
     *
     * @return game-specific SFX ID, or -1 to use the engine fallback
     */
    default int getTransitionSfxId() {
        return -1;
    }

    /**
     * Returns whether this game's special-stage entry queues a music fade
     * after its transition SFX. The default retains the shared entry behavior;
     * providers whose ROM uses a different entry command override this
     * semantic entry policy.
     */
    default boolean fadesMusicOnEntry() {
        return true;
    }

    /**
     * Gets the music ID to play while the special stage is active.
     *
     * @return game-specific music ID, or -1 to use the engine fallback
     */
    default int getStageMusicId() {
        return -1;
    }

    /**
     * Gets the shared music cue to play while the special stage is active.
     *
     * @return generic music cue, or null to use {@link #getStageMusicId()}
     */
    default GameMusic getStageMusic() {
        return null;
    }

    /**
     * Gets the music ID to play for special stage results.
     *
     * @return game-specific music ID, or -1 to use the engine fallback
     */
    default int getResultsMusicId() {
        return -1;
    }

    /**
     * Gets the shared music cue to play for special stage results.
     *
     * @return generic music cue, or null to use {@link #getResultsMusicId()}
     */
    default GameMusic getResultsMusic() {
        return null;
    }

    default boolean supportsRewind() {
        return false;
    }

    default Optional<RewindSnapshottable<?>> rewindAdapter() {
        return Optional.empty();
    }

    /**
     * Handles input plus the debug movement speed modifiers used by normal
     * gameplay. Providers without gameplay debug movement retain the ordinary
     * two-mask behavior.
     */
    default void handleInput(int heldButtons, int pressedButtons,
                             boolean debugSpeedUp, boolean debugSlowDown) {
        handleInput(heldButtons, pressedButtons);
    }

    /**
     * Checks if this game has special stages.
     *
     * @return true if special stages are available
     */
    boolean hasSpecialStages();

    /**
     * Gets the access method for special stages in this game.
     *
     * @return the access type (GIANT_RING or STARPOST)
     */
    SpecialStageAccessType getAccessType();

    /**
     * Initializes a specific special stage.
     *
     * @param stageIndex the stage index (0-6 for Sonic 2)
     * @throws IOException if initialization fails
     */
    void initializeStage(int stageIndex) throws IOException;

    /**
     * Initializes a stage with an explicit startup pacing policy. Providers
     * without observable startup phases use their normal initialization path.
     */
    default void initializeStage(int stageIndex, SpecialStageStartupPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        initializeStage(stageIndex);
    }

    /** Returns whether entry presentation may reveal the initialized stage. */
    default boolean isEntryPresentationReady() {
        return true;
    }

    /**
     * Returns whether the stage is still inside the ROM's entry fade-to-white.
     * {@code Pal_FadeToWhite} (docs/s2disasm/s2.asm:6547), {@code PaletteWhiteOut}
     * (docs/s1disasm/sonic.asm:3226) and S3K's {@code Pal_FadeToWhite}
     * (docs/skdisasm/sonic3k.asm:10591) are synchronous wait loops that run
     * before the stage is loaded, so the display still shows the level's last
     * frame while its palette steps to white. The engine keeps rendering the
     * frozen level under that fade for as long as this is true and only then
     * hands the frame to the stage's own draw.
     */
    default boolean isEntryFadeToWhiteActive() {
        return false;
    }

    /** Called at the native one-player special-stage results setup boundary. */
    default void onEnterResults() {
    }

    /** Resets the stage and publishes its game-owned results PLC producer. */
    default void resetForResults() {
        reset();
        onEnterResults();
    }

    /**
     * Gets the current stage index.
     *
     * @return the stage index (0-based)
     */
    int getCurrentStage();

    /**
     * Checks if an emerald was collected in the current stage.
     * Only valid after the stage is finished.
     *
     * @return true if the emerald was collected
     */
    boolean isEmeraldCollected();

    /**
     * Gets the index of the emerald collected (if any).
     * Same as getCurrentStage() in most implementations.
     *
     * @return the emerald index, or -1 if not collected
     */
    int getEmeraldIndex();

    /**
     * Gets the number of rings collected in the current stage.
     *
     * @return the ring count
     */
    int getRingsCollected();

    /**
     * Sets the emerald collected flag (for debug purposes).
     *
     * @param collected true to mark emerald as collected
     */
    void setEmeraldCollected(boolean collected);

    /**
     * Sets the OpenGL clear color to the special stage backdrop color.
     * Default clears to black; game-specific providers override with palette-derived color.
     */
    default void setClearColor() {
        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    }

    /**
     * Returns the ring count to simulate when debug-completing a specific stage.
     * S2 returns checkpoint-3 ring requirements; S1 returns a nominal value
     * since emerald collection is position-based, not ring-based.
     *
     * @param stageIndex the stage index (0-based)
     * @return simulated ring count for the debug results screen
     */
    default int getDebugCompletionRingCount(int stageIndex) {
        return 50;
    }

    default void handlePlayer2Input(int heldButtons, int logicalButtons) {
        // No-op by default.
    }

    /**
     * Binds physical controller input to the recurring ROM object pass the next
     * {@link #update()} executes, for a stage whose object pass is a separate
     * pacing unit from the V-blank observation (S2's {@code SS_MainLoop} /
     * {@code RunObjects} split, docs/s2disasm/s2.asm:6694-6721). Stages with no
     * such split ignore it.
     */
    default void bindPendingRecurringPassInput(
            int p1Held, int p1Pressed, int p2Held, int p2Logical) {
        // No-op by default.
    }

    // ==================== Debug Methods ====================

    /**
     * Debug: advance to the next stage within the current set.
     * Only meaningful for games with multi-stage special stage sets (e.g., S3K).
     */
    default void debugNextStage() {
        // No-op by default.
    }

    /**
     * Debug: toggle between layout sets (e.g., S3 vs SK in S3K).
     * Only meaningful for games with multiple layout sets.
     */
    default void debugToggleLayoutSet() {
        // No-op by default.
    }

    /**
     * Checks if gameplay debug movement mode is active.
     * When enabled, directional input moves the special-stage player directly.
     *
     * @return true if gameplay debug movement is enabled
     */
    default boolean isGameplayDebugMode() {
        return false;
    }

    /**
     * Toggles gameplay debug movement mode on/off.
     */
    default void toggleGameplayDebugMode() {
        // No-op by default.
    }

    /**
     * Checks if sprite debug mode is active.
     *
     * @return true if sprite debug viewer is enabled
     */
    boolean isSpriteDebugMode();

    /**
     * Toggles sprite debug mode on/off.
     */
    void toggleSpriteDebugMode();

    /**
     * Cycles through plane visibility debug modes (A/B/both/off).
     */
    void cyclePlaneDebugMode();

    /**
     * Gets the debug provider for sprite viewing.
     *
     * @return the debug provider, or null if not available
     */
    SpecialStageDebugProvider getDebugProvider();

    // ==================== Alignment Test Methods ====================

    /**
     * Checks if alignment test mode is active.
     *
     * @return true if alignment testing is enabled
     */
    boolean isAlignmentTestMode();

    /**
     * Toggles alignment test mode on/off.
     */
    void toggleAlignmentTestMode();

    /**
     * Adjusts the alignment offset for testing.
     *
     * @param delta amount to adjust the offset
     */
    void adjustAlignmentOffset(int delta);

    /**
     * Adjusts the alignment speed for testing.
     *
     * @param delta amount to adjust the speed
     */
    void adjustAlignmentSpeed(double delta);

    /**
     * Toggles alignment step mode for frame-by-frame testing.
     */
    void toggleAlignmentStepMode();

    /**
     * Renders the alignment test overlay.
     *
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    void renderAlignmentOverlay(int viewportWidth, int viewportHeight);

    // ==================== Lag Compensation Methods ====================

    /**
     * Renders the lag compensation debug overlay.
     *
     * @param viewportWidth  viewport width in pixels
     * @param viewportHeight viewport height in pixels
     */
    void renderLagCompensationOverlay(int viewportWidth, int viewportHeight);

    /**
     * Checks whether the lag compensation debug display is enabled.
     *
     * @return true if the read-only lag-model diagnostics are visible
     */
    default boolean isLagCompensationDisplayEnabled() {
        return false;
    }

    /**
     * Toggles the lag compensation debug display on/off.
     */
    default void toggleLagCompensationDisplay() {
        // No-op by default.
    }

    /**
     * External-pacing control for providers with an interactive lag or slowdown
     * simulation. Zero disables that local simulation so recorded lag outcomes
     * can be admitted only by the hardware-timing port; providers without a
     * local simulation may ignore the value.
     *
     * @param factor zero for external pacing; positive for normal live pacing
     */
    void setLagCompensation(double factor);

    // ==================== Results Screen ====================

    /**
     * Creates a results screen for this special stage type.
     *
     * @param ringsCollected    number of rings collected
     * @param gotEmerald        true if an emerald was collected
     * @param stageIndex        the stage index (0-based)
     * @param totalEmeraldCount total emeralds collected so far
     * @return a results screen instance
     */
    ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
            int stageIndex, int totalEmeraldCount);
}
