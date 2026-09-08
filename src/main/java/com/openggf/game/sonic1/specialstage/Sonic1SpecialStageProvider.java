package com.openggf.game.sonic1.specialstage;

import com.openggf.audio.GameMusic;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugCapabilities;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.SpecialStageViewport;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.resources.Sonic1PlcService;

import com.openggf.level.Palette;

import static org.lwjgl.opengl.GL11.glClearColor;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/**
 * Sonic 1 special stage provider.
 *
 * <p>Implements the shared special-stage contract so Sonic 1 uses the same
 * game-mode pipeline as Sonic 2. The underlying stage gameplay is scaffolded
 * and expanded in follow-up parity passes.
 */
public final class Sonic1SpecialStageProvider implements SpecialStageProvider {
    private final Sonic1SpecialStageManager manager = new Sonic1SpecialStageManager();
    private SpecialStageViewport viewport = SpecialStageViewport.nativeViewport();
    private boolean resultsPlcSubmitted;

    public Sonic1SpecialStageProvider() {
        manager.setSpecialStageViewport(viewport);
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

    @Override
    public SpecialStageDebugCapabilities debugCapabilities() {
        // S1's scaffold has a real direct-movement debug mode. Its sprite,
        // plane, alignment, and lag tools remain unavailable until their
        // game-owned implementations exist.
        return new SpecialStageDebugCapabilities(true, false, false, false, false, false, false);
    }

    @Override
    public int getTransitionSfxId() {
        return Sonic1Sfx.ENTER_SS.id;
    }

    @Override
    public boolean fadesMusicOnEntry() {
        // GM_Special queues sfx_EnterSS and enters PaletteWhiteOut directly;
        // unlike S2 SpecialStage, it issues no fade command in between
        // (sonic.asm:3224-3227). S1 FadeOutMusic would stop $CA itself.
        return false;
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
        return Optional.of(new Sonic1SpecialStageRewindAdapter(manager,
                () -> resultsPlcSubmitted, submitted -> resultsPlcSubmitted = submitted));
    }

    @Override
    public SpecialStageAccessType getAccessType() {
        return SpecialStageAccessType.GIANT_RING;
    }

    @Override
    public void setClearColor() {
        Palette.Color backdrop = manager.getBackdropColor();
        if (backdrop != null) {
            glClearColor(backdrop.rFloat(), backdrop.gFloat(), backdrop.bFloat(), 1.0f);
        } else {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        }
    }

    @Override
    public void initializeStage(int stageIndex) throws IOException {
        initializeStage(stageIndex, SpecialStageStartupPolicy.FAST);
    }

    /**
     * Both policies leave GM_Special's observable hold armed so each frame of
     * {@code PaletteWhiteOut}/instant-setup/{@code PaletteWhiteIn} is stepped
     * through {@link #update()}: the white-out is the visible fade over the
     * level's last frame and the white-in is the stage reveal, so neither is
     * hidden startup that FAST could retire. S1 loads the stage inside the
     * instant setup block without waiting for a V-int, so there is no load
     * span for FAST to approximate either (contrast
     * {@code Sonic2SpecialStageProvider.initializeStage(int, SpecialStageStartupPolicy)}).
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
            Sonic1PlcService plcService = GameServices.module().getGameService(Sonic1PlcService.class);
            if (plcService != null) {
                plcService.transact(Sonic1PlcService.replace(0), Sonic1PlcService.appendOperation(27));
            }
            resultsPlcSubmitted = true;
        } catch (Exception ignored) {
            // Results rendering also has standalone construction paths.
        }
    }

    @Override
    public void resetForResults() {
        reset();
        resultsPlcSubmitted = false;
        onEnterResults();
    }

    /**
     * The ROM reveal cannot begin until GM_Special's startup hold has reached
     * its presentation boundary. FAST initialization consumes that hold before
     * returning; TRACE_ACCURATE leaves it observable to the GameLoop so visual
     * complete-run playback keeps the fade and recorded lag rows aligned.
     */
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
        return manager.isEmeraldCollected();
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
        if (collected) {
            manager.markFinished();
        }
    }

    @Override
    public boolean isGameplayDebugMode() {
        return manager.isDebugMode();
    }

    @Override
    public void toggleGameplayDebugMode() {
        manager.toggleDebugMode();
    }

    @Override
    public boolean isSpriteDebugMode() {
        return false;
    }

    @Override
    public void toggleSpriteDebugMode() {
        // No-op in scaffold.
    }

    @Override
    public void cyclePlaneDebugMode() {
        // No-op in scaffold.
    }

    @Override
    public SpecialStageDebugProvider getDebugProvider() {
        return null;
    }

    @Override
    public boolean isAlignmentTestMode() {
        return false;
    }

    @Override
    public void toggleAlignmentTestMode() {
        // No-op in scaffold.
    }

    @Override
    public void adjustAlignmentOffset(int delta) {
        // No-op in scaffold.
    }

    @Override
    public void adjustAlignmentSpeed(double delta) {
        // No-op in scaffold.
    }

    @Override
    public void toggleAlignmentStepMode() {
        // No-op in scaffold.
    }

    @Override
    public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        // No-op in scaffold.
    }

    @Override
    public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        // No-op in scaffold.
    }

    @Override
    public void setLagCompensation(double factor) {
        // No-op in scaffold.
    }

    @Override
    public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
            int stageIndex, int totalEmeraldCount) {
        return ResultsScreen.withBeforeUpdate(new Sonic1SpecialStageResultsScreen(
                ringsCollected, gotEmerald, stageIndex, totalEmeraldCount), this::onEnterResults);
    }

    @Override
    public void initialize() throws IOException {
        // Use initializeStage(int).
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
    public void handleInput(int heldButtons, int pressedButtons,
                            boolean debugSpeedUp, boolean debugSlowDown) {
        manager.handleInput(heldButtons, pressedButtons, debugSpeedUp, debugSlowDown);
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

    /** The backing manager, for trace-replay comparison snapshots. */
    public Sonic1SpecialStageManager getManager() {
        return manager;
    }
}
