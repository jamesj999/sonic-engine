package uk.co.jamesj999.sonic.game;

import java.io.IOException;

/**
 * No-op implementation of {@link BonusStageProvider} for games without bonus stages.
 * Used as the default implementation to avoid null checks.
 */
public final class NoOpBonusStageProvider implements BonusStageProvider {
    public static final NoOpBonusStageProvider INSTANCE = new NoOpBonusStageProvider();

    private NoOpBonusStageProvider() {}

    @Override
    public boolean hasBonusStages() {
        return false;
    }

    @Override
    public BonusStageType selectBonusStage(int ringCount) {
        return BonusStageType.NONE;
    }

    @Override
    public void initializeBonusStage(BonusStageType type) throws IOException {
        // No-op
    }

    @Override
    public BonusStageRewards getRewards() {
        return BonusStageRewards.none();
    }

    @Override
    public void initialize() throws IOException {
        // No-op
    }

    @Override
    public void update() {
        // No-op
    }

    @Override
    public void draw() {
        // No-op
    }

    @Override
    public void handleInput(int heldButtons, int pressedButtons) {
        // No-op
    }

    @Override
    public boolean isFinished() {
        return true;
    }

    @Override
    public void reset() {
        // No-op
    }

    @Override
    public boolean isInitialized() {
        return false;
    }
}
