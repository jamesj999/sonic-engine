package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.SpecialStageAccessType;
import uk.co.jamesj999.sonic.game.SpecialStageProvider;
import uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageManager;

import java.io.IOException;

/**
 * Sonic 2 special stage provider implementation.
 * Wraps the existing Sonic2SpecialStageManager with the SpecialStageProvider interface.
 *
 * <p>Sonic 2's special stages are accessed via star posts (checkpoints)
 * when the player has 50 or more rings. Each stage awards one of seven
 * Chaos Emeralds upon successful completion.
 */
public class Sonic2SpecialStageProvider implements SpecialStageProvider {
    private final Sonic2SpecialStageManager manager;

    public Sonic2SpecialStageProvider() {
        this.manager = Sonic2SpecialStageManager.getInstance();
    }

    @Override
    public boolean hasSpecialStages() {
        return true;
    }

    @Override
    public SpecialStageAccessType getAccessType() {
        return SpecialStageAccessType.STARPOST;
    }

    @Override
    public void initializeStage(int stageIndex) throws IOException {
        manager.reset();
        manager.initialize(stageIndex);
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

    // MiniGameProvider interface methods

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
