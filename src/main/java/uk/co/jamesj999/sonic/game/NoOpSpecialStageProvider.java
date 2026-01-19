package uk.co.jamesj999.sonic.game;

import java.io.IOException;

/**
 * No-op implementation of {@link SpecialStageProvider} for games without special stages.
 * Used as the default implementation to avoid null checks.
 */
public final class NoOpSpecialStageProvider implements SpecialStageProvider {
    public static final NoOpSpecialStageProvider INSTANCE = new NoOpSpecialStageProvider();

    private NoOpSpecialStageProvider() {}

    @Override
    public boolean hasSpecialStages() {
        return false;
    }

    @Override
    public SpecialStageAccessType getAccessType() {
        return SpecialStageAccessType.GIANT_RING;
    }

    @Override
    public void initializeStage(int stageIndex) throws IOException {
        // No-op
    }

    @Override
    public int getCurrentStage() {
        return 0;
    }

    @Override
    public boolean isEmeraldCollected() {
        return false;
    }

    @Override
    public int getEmeraldIndex() {
        return -1;
    }

    @Override
    public int getRingsCollected() {
        return 0;
    }

    @Override
    public void setEmeraldCollected(boolean collected) {
        // No-op
    }

    @Override
    public boolean isSpriteDebugMode() {
        return false;
    }

    @Override
    public void toggleSpriteDebugMode() {
        // No-op
    }

    @Override
    public void cyclePlaneDebugMode() {
        // No-op
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
        // No-op
    }

    @Override
    public void adjustAlignmentOffset(int delta) {
        // No-op
    }

    @Override
    public void adjustAlignmentSpeed(double delta) {
        // No-op
    }

    @Override
    public void toggleAlignmentStepMode() {
        // No-op
    }

    @Override
    public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        // No-op
    }

    @Override
    public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        // No-op
    }

    @Override
    public double getLagCompensation() {
        return 0.0;
    }

    @Override
    public void setLagCompensation(double factor) {
        // No-op
    }

    @Override
    public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                                              int stageIndex, int totalEmeraldCount) {
        return NoOpResultsScreen.INSTANCE;
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
