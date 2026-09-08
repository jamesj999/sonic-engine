package com.openggf.game.sonic3k.specialstage;

import com.openggf.audio.GameMusic;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.EmeraldRewardKind;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugCapabilities;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageViewport;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;

import java.io.IOException;
import java.util.Optional;

/**
 * Sonic 3&K special stage provider implementation.
 * Wraps {@link Sonic3kSpecialStageManager} with the {@link SpecialStageProvider}
 * interface, following the same pattern as {@code Sonic2SpecialStageProvider}.
 * <p>
 * S3K special stages are accessed via giant rings hidden in levels.
 * Each stage awards one of seven Chaos Emeralds upon successful completion.
 */
public class Sonic3kSpecialStageProvider implements SpecialStageProvider {
    private final Sonic3kSpecialStageManager manager;
    private SpecialStageViewport viewport = SpecialStageViewport.nativeViewport();

    public Sonic3kSpecialStageProvider() {
        this(new Sonic3kSpecialStageManager());
    }

    public Sonic3kSpecialStageProvider(Sonic3kSpecialStageManager manager) {
        this.manager = manager;
        this.manager.setSpecialStageViewport(viewport);
    }

    @Override
    public void setSpecialStageViewport(SpecialStageViewport viewport) {
        this.viewport = java.util.Objects.requireNonNull(viewport, "viewport");
        manager.setSpecialStageViewport(viewport);
    }

    @Override
    public SpecialStageViewport getSpecialStageViewport() {
        return viewport;
    }

    @Override
    public SpecialStageDebugCapabilities debugCapabilities() {
        // X/Z stage and layout navigation are live manager operations. The
        // manager's sprite flag has no viewer and the remaining diagnostics
        // are not implemented, so those keys are intentionally unavailable.
        return new SpecialStageDebugCapabilities(false, true, true, false, false, false, false);
    }

    @Override
    public int consumeStageIndexForEntry(GameStateManager gameState) {
        return gameState.consumeCurrentSpecialStageIndexAndAdvanceSkippingCollected(false);
    }

    @Override
    public int getTransitionSfxId() {
        // Obj_SSEntryFlash plays sfx_EnterSS at SSEntryFlash_GoSS for both
        // ordinary and sanctuary routes. Returning it here would double-play it.
        return -1;
    }

    @Override
    public GameMusic getStageMusic() {
        return GameMusic.SPECIAL_STAGE;
    }

    @Override
    public int getResultsMusicId() {
        // Return -1: S3K results screen plays music internally at the ROM-accurate
        // frame (71 frames into the 360-frame pre-tally wait, when countdown == 289).
        // Returning a music ID here would cause GameLoop to double-play it immediately.
        return -1;
    }

    @Override
    public boolean hasSpecialStages() {
        return true;
    }

    @Override
    public boolean supportsRewind() {
        return true;
    }

    @Override
    public Optional<RewindSnapshottable<?>> rewindAdapter() {
        return Optional.of(new Sonic3kSpecialStageRewindAdapter(manager));
    }

    @Override
    public SpecialStageAccessType getAccessType() {
        return SpecialStageAccessType.GIANT_RING;
    }

    @Override
    public void initializeStage(int stageIndex) throws IOException {
        initializeStage(stageIndex, SpecialStageStartupPolicy.FAST);
    }

    /**
     * The ROM opens {@code SpecialStage} with a blocking 22-frame
     * {@code Pal_FadeToWhite} (sonic3k.asm:10591, routine at 5232-5242) before
     * any special-stage state exists. That fade is visible (it runs over the
     * level's last frame), so both policies leave the hold armed and step it
     * frame by frame; the presentation controller reveals the stage once
     * {@link #isEntryPresentationReady()} reports the fade has elapsed. The
     * masked-interrupt load that follows has no live approximation yet.
     */
    @Override
    public void initializeStage(int stageIndex, SpecialStageStartupPolicy policy)
            throws IOException {
        java.util.Objects.requireNonNull(policy, "policy");
        manager.reset();
        manager.initialize(stageIndex);
    }

    @Override
    public boolean isEntryPresentationReady() {
        return manager.isEntryPresentationReady();
    }

    @Override
    public boolean isEntryFadeToWhiteActive() {
        return manager.isEntryFadeToWhiteActive();
    }

    @Override
    public void initializeStage(int stageIndex,
                                com.openggf.game.SpecialStageStartupPolicy policy,
                                EmeraldRewardKind rewardKind) throws IOException {
        java.util.Objects.requireNonNull(policy, "policy");
        java.util.Objects.requireNonNull(rewardKind, "rewardKind");
        manager.reset();
        manager.initialize(stageIndex, rewardKind);
    }

    @Override
    public boolean ownsEmeraldReward() {
        return true;
    }

    @Override
    public int getCurrentStage() {
        return manager.getCurrentStage();
    }

    @Override
    public boolean isEmeraldCollected() {
        return manager.hasEmeraldCollected();
    }

    @Override
    public int getEmeraldIndex() {
        return isEmeraldCollected() ? getCurrentStage() : -1;
    }

    @Override
    public int getRingsCollected() {
        return manager.getRingsCollected();
    }

    @Override
    public void setEmeraldCollected(boolean collected) {
        manager.setEmeraldCollected(collected);
    }

    @Override
    public int getDebugCompletionRingCount(int stageIndex) {
        // S3K special stages don't have checkpoint ring requirements
        // like S2. Return a nominal value for debug purposes.
        return 50;
    }

    // ==================== Debug Methods ====================

    @Override
    public boolean isSpriteDebugMode() {
        return manager.isSpriteDebugMode();
    }

    @Override
    public void toggleSpriteDebugMode() {
        manager.toggleSpriteDebugMode();
    }

    @Override
    public void cyclePlaneDebugMode() {
        manager.cyclePlaneDebugMode();
    }

    @Override
    public SpecialStageDebugProvider getDebugProvider() {
        return manager.getDebugProvider();
    }

    // ==================== Alignment Test Methods ====================

    @Override
    public boolean isAlignmentTestMode() {
        return manager.isAlignmentTestMode();
    }

    @Override
    public void toggleAlignmentTestMode() {
        manager.toggleAlignmentTestMode();
    }

    @Override
    public void adjustAlignmentOffset(int delta) {
        manager.adjustAlignmentOffset(delta);
    }

    @Override
    public void adjustAlignmentSpeed(double delta) {
        manager.adjustAlignmentSpeed(delta);
    }

    @Override
    public void toggleAlignmentStepMode() {
        manager.toggleAlignmentStepMode();
    }

    @Override
    public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        manager.renderAlignmentOverlay(viewportWidth, viewportHeight);
    }

    // ==================== Lag Compensation Methods ====================

    @Override
    public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        manager.renderLagCompensationOverlay(viewportWidth, viewportHeight);
    }

    @Override
    public void setLagCompensation(double factor) {
        manager.setLagCompensation(factor);
    }

    // ==================== Results Screen ====================

    @Override
    public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                                             int stageIndex, int totalEmeraldCount) {
        // ROM SpecialStage_Results copies Current_zone_and_act into
        // Special_stage_zone_and_act, so the reveal branch at loc_2E540 tests the zone the
        // Big Ring was collected in — still the loaded level at this point.
        boolean skSideOrigin = GameServices.hasRuntime()
                && Sonic3kZoneIds.isSkSideZone(GameServices.level().getCurrentZone());
        return new S3kSpecialStageResultsScreen(
                ringsCollected, gotEmerald, stageIndex, totalEmeraldCount,
                manager.getPlayerCharacter(), manager.isSuperEmeraldMode(), skSideOrigin);
    }

    // ==================== MiniGameProvider Methods ====================

    @Override
    public void initialize() throws IOException {
        // No-op: Use initializeStage(int) instead
    }

    @Override
    public void update() {
        manager.update();
    }

    @Override
    public void draw() {
        manager.draw();
    }

    @Override
    public void handleInput(int heldButtons, int pressedButtons) {
        manager.handleInput(heldButtons, pressedButtons);
    }

    @Override
    public void handlePlayer2Input(int heldButtons, int logicalButtons) {
        manager.handlePlayer2Input(heldButtons, logicalButtons);
    }

    @Override
    public boolean isFinished() {
        return manager.isFinished();
    }

    @Override
    public void reset() {
        manager.reset();
    }

    @Override
    public boolean isInitialized() {
        return manager.isInitialized();
    }

    /**
     * Gets the underlying manager for advanced functionality.
     *
     * @return the Sonic3kSpecialStageManager instance
     */
    public Sonic3kSpecialStageManager getManager() {
        return manager;
    }

    @Override
    public void debugNextStage() {
        manager.debugNextStage();
    }

    @Override
    public void debugToggleLayoutSet() {
        manager.debugToggleLayoutSet();
    }
}
