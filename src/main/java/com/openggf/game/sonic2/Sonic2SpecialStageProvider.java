package com.openggf.game.sonic2;


import com.openggf.audio.GameMusic;
import com.openggf.game.session.EngineServices;
import com.openggf.game.GameServices;
import com.openggf.game.ResultsScreen;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugCapabilities;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageViewport;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.resources.Sonic2RuntimePlcPublisher;
import com.openggf.game.sonic2.objects.SpecialStageResultsScreenObjectInstance;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageIntro;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageManager;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStagePlayer;
import com.openggf.game.sonic2.specialstage.Sonic2SpecialStageRewindAdapter;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.DefaultObjectServices;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Sonic 2 special stage provider implementation.
 * Wraps the existing Sonic2SpecialStageManager with the SpecialStageProvider
 * interface.
 *
 * <p>
 * Sonic 2's special stages are accessed via star posts (checkpoints)
 * when the player has 50 or more rings. Each stage awards one of seven
 * Chaos Emeralds upon successful completion.
 */
public class Sonic2SpecialStageProvider implements SpecialStageProvider {
    private final Sonic2SpecialStageManager manager;
    private SpecialStageViewport viewport = SpecialStageViewport.nativeViewport();
    private boolean resultsPlcSubmitted;
    /**
     * The two per-player ring totals latched at the ROM's own copy point, the
     * {@code move.w (Ring_count).w,(Bonus_Countdown_1).w} /
     * {@code move.w (Ring_count_2P).w,(Bonus_Countdown_2).w} pair that runs
     * immediately before {@code Obj6F} is created (docs/s2disasm/s2.asm:6784-6785,
     * :6797). They must be read before {@link #resetForResults()} clears the
     * stage's players.
     */
    private int bonusCountdown1;
    private int bonusCountdown2;
    private boolean ringsLatched;

    @Override
    public SpecialStageDebugCapabilities debugCapabilities() {
        return new SpecialStageDebugCapabilities(false, false, false, true, true, true, false);
    }

    public Sonic2SpecialStageProvider() {
        this(new Sonic2SpecialStageManager());
    }

    public Sonic2SpecialStageProvider(Sonic2SpecialStageManager manager) {
        this.manager = manager;
        this.manager.setSpecialStageViewport(viewport);
    }

    @Override
    public void setSpecialStageViewport(SpecialStageViewport viewport) {
        this.viewport = Objects.requireNonNull(viewport, "viewport");
        manager.setSpecialStageViewport(viewport);
    }

    @Override
    public SpecialStageViewport getSpecialStageViewport() {
        return viewport;
    }

    /**
     * Sonic 2's special-stage entry opens with {@code Pal_FadeToWhite}, which
     * sets {@code VintID_Fade} and waits for a V-int on each of its 22 {@code dbf}
     * iterations (docs/s2disasm/s2.asm:3571-3581, called from {@code SpecialStage}
     * at s2.asm:6547). Those rows run {@code Vint_Fade}
     * (docs/s2disasm/s2.asm:1068-1070 -- {@code Do_ControllerPal}, the H-int
     * counter reload and {@code ProcessDPLC}), which never reaches
     * {@code ProcessDMAQueue}; the stage's own {@code VintID_S2SS} is not
     * installed until after the entry load, at s2.asm:6642. So the fade rows are
     * palette-fade rows, not special-stage rows, and a transfer still queued on
     * entry survives them.
     *
     * <p>{@code Pal_FadeFromWhite} (s2.asm:3477, called at s2.asm:6672) uses the
     * same handler, but the engine already routes that window through the shared
     * fade lifecycle, which claims the phase itself.</p>
     */
    @Override
    public PlcLifecyclePhase specialStagePlcLifecyclePhase() {
        Sonic2SpecialStageIntro intro = manager != null ? manager.getIntro() : null;
        return intro != null
                && intro.getCurrentPhase() == Sonic2SpecialStageIntro.Phase.PRE_ROLL
                ? PlcLifecyclePhase.PALETTE_FADE
                : PlcLifecyclePhase.SPECIAL_STAGE;
    }

    @Override
    public int getTransitionSfxId() {
        return Sonic2Sfx.SPECIAL_STAGE_ENTRY.id;
    }

    @Override
    public GameMusic getStageMusic() {
        return GameMusic.SPECIAL_STAGE;
    }

    @Override
    public GameMusic getResultsMusic() {
        return GameMusic.ACT_CLEAR;
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
        return Optional.of(new Sonic2SpecialStageRewindAdapter(manager,
                () -> resultsPlcSubmitted, submitted -> resultsPlcSubmitted = submitted));
    }

    @Override
    public SpecialStageAccessType getAccessType() {
        return SpecialStageAccessType.STARPOST;
    }

    @Override
    public void initializeStage(int stageIndex) throws IOException {
        initializeStage(stageIndex, SpecialStageStartupPolicy.FAST);
    }

    /**
     * Sonic 2's entry ({@code SpecialStage}, docs/s2disasm/s2.asm:6538-6672) is
     * a fixed sequence of blocking waits: 22 {@code Pal_FadeToWhite} V-ints,
     * the masked-interrupt load, the two startup loops and one
     * {@code VintID_CtrlDMA} wait before {@code MusID_SpecStage} and
     * {@code Pal_FadeFromWhite}. The manager steps every V-int wait through
     * its ordinary update path under both policies. The load itself (104
     * lag rows in every recorded stage, s2.asm:6557-6645) is only reproduced
     * under TRACE_ACCURATE, where the timing port admits the recorded rows;
     * FAST skips it, so normal play goes straight from the fade to startup.
     */
    @Override
    public void initializeStage(int stageIndex, SpecialStageStartupPolicy policy) throws IOException {
        Objects.requireNonNull(policy, "policy");
        manager.reset();
        manager.initialize(stageIndex);
    }

    @Override
    public boolean isEntryFadeToWhiteActive() {
        return manager.isEntryFadeToWhiteActive();
    }

    @Override
    public void onEnterResults() {
        if (resultsPlcSubmitted) return;
        try {
            Sonic2PlcService plcService = GameServices.module().getGameService(Sonic2PlcService.class);
            if (plcService != null) {
                if (GameServices.module().getObjectArtProvider() instanceof Sonic2ObjectArtProvider artProvider
                        && GameServices.levelOrNull() != null) {
                    Sonic2RuntimePlcPublisher.transact(artProvider, plcService,
                            GameServices.levelOrNull()::refreshObjectArtPatterns,
                            Sonic2PlcService.replaceOperation(0));
                } else {
                    plcService.transact(Sonic2PlcService.replaceOperation(0));
                }
            }
            resultsPlcSubmitted = true;
        } catch (Exception ignored) {
            // Results rendering also has standalone construction paths.
        }
    }

    @Override
    public void resetForResults() {
        bonusCountdown1 = manager.getRingsCollected(
                Sonic2SpecialStagePlayer.PlayerType.SONIC);
        bonusCountdown2 = manager.getRingsCollected(
                Sonic2SpecialStagePlayer.PlayerType.TAILS);
        ringsLatched = true;
        reset();
        resultsPlcSubmitted = false;
        onEnterResults();
    }

    @Override
    public boolean isEntryPresentationReady() {
        return manager.isEntryPresentationReady();
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
        // Ring requirements at final checkpoint (checkpoint 3) for each stage
        // From s2.asm Ring_Requirement_Table (solo mode)
        int[][] requirements = {
                { 30, 60, 90, 120 },   // Stage 1
                { 40, 80, 120, 160 },   // Stage 2
                { 50, 100, 140, 180 },  // Stage 3
                { 50, 100, 140, 180 },  // Stage 4
                { 60, 110, 160, 200 },  // Stage 5
                { 70, 120, 180, 220 },  // Stage 6
                { 80, 140, 200, 240 }   // Stage 7
        };
        if (stageIndex >= 0 && stageIndex < requirements.length) {
            return requirements[stageIndex][3];
        }
        return 100;
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
        // Compatibility no-op: S2 exposes no live pacing diagnostic.
    }

    @Override
    public boolean isLagCompensationDisplayEnabled() {
        return false;
    }

    @Override
    public void toggleLagCompensationDisplay() {
        // Compatibility no-op: the capability is not advertised.
    }

    @Override
    public void setLagCompensation(double factor) {
        manager.setLagCompensation(factor);
    }

    // ==================== Results Screen ====================

    @Override
    public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                                             int stageIndex, int totalEmeraldCount) {
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode == null) {
            throw new IllegalStateException("Special-stage results screen requires an active GameplayModeContext");
        }
        DefaultObjectServices services = new DefaultObjectServices(
                gameplayMode, EngineServices.current());
        // The ROM's two bonus countdowns drain in parallel, one ring each per
        // frame (Obj6F_TallyScore, docs/s2disasm/s2.asm:28376-28396), so the
        // tally lasts as long as the LONGER of them -- never their sum. Use the
        // split latched at the ROM's copy point; a caller that never ran
        // resetForResults() has no split to offer, which is the ROM's
        // single-player shape (one countdown loaded, the other left at zero,
        // s2.asm:6773-6785).
        int countdown1 = ringsLatched ? bonusCountdown1 : ringsCollected;
        int countdown2 = ringsLatched ? bonusCountdown2 : 0;
        return ResultsScreen.withBeforeUpdate(ObjectConstructionContext.construct(services,
                () -> new SpecialStageResultsScreenObjectInstance(
                        countdown1, countdown2, gotEmerald, stageIndex, totalEmeraldCount, services)),
                this::onEnterResults);
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

    /** Binds physical input to the recurring pass that the next update executes. */
    @Override
    public void bindPendingRecurringPassInput(
            int p1Held, int p1Pressed, int p2Held, int p2Logical) {
        manager.bindPendingRecurringPassInput(p1Held, p1Pressed, p2Held, p2Logical);
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
     * Used by Engine/GameLoop for debug overlays and other features.
     *
     * @return the Sonic2SpecialStageManager instance
     */
    public Sonic2SpecialStageManager getManager() {
        return manager;
    }
}
