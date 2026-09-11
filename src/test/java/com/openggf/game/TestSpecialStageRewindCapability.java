package com.openggf.game;

import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.sonic2.Sonic2SpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSpecialStageRewindCapability {

    @Test
    void defaultAndNoOpProvidersDoNotSupportRewindOrProvideAdapters() {
        MinimalSpecialStageProvider defaultProvider = new MinimalSpecialStageProvider();
        assertFalse(defaultProvider.supportsRewind());
        assertTrue(defaultProvider.rewindAdapter().isEmpty());
        assertFalse(NoOpSpecialStageProvider.INSTANCE.supportsRewind());
        assertTrue(NoOpSpecialStageProvider.INSTANCE.rewindAdapter().isEmpty());
        assertEquals("special-stage-runtime", SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY);
    }

    @Test
    void sonic1Sonic2AndS3kProvidersSupportRewind() {
        Sonic1SpecialStageProvider sonic1 = new Sonic1SpecialStageProvider();
        Sonic2SpecialStageProvider sonic2 = new Sonic2SpecialStageProvider();
        Sonic3kSpecialStageProvider sonic3k = new Sonic3kSpecialStageProvider();

        assertTrue(sonic1.supportsRewind());
        assertTrue(sonic2.supportsRewind());
        assertTrue(sonic3k.supportsRewind());
        assertTrue(sonic1.rewindAdapter().isPresent());
        assertTrue(sonic2.rewindAdapter().isPresent());
        assertTrue(sonic3k.rewindAdapter().isPresent());
        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY,
                sonic1.rewindAdapter().orElseThrow().key());
        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY,
                sonic2.rewindAdapter().orElseThrow().key());
        assertEquals(SpecialStageProvider.SPECIAL_STAGE_REWIND_KEY,
                sonic3k.rewindAdapter().orElseThrow().key());
    }

    @Test
    void lagBypassIsWriteOnlyProviderConfiguration() {
        assertThrows(NoSuchMethodException.class,
                () -> SpecialStageProvider.class.getMethod("getLagCompensation"));
    }

    private static final class MinimalSpecialStageProvider implements SpecialStageProvider {
        @Override public void initialize() throws IOException { }
        @Override public void update() { }
        @Override public void draw() { }
        @Override public void handleInput(int heldButtons, int pressedButtons) { }
        @Override public boolean isFinished() { return false; }
        @Override public void reset() { }
        @Override public boolean isInitialized() { return true; }
        @Override public boolean hasSpecialStages() { return true; }
        @Override public SpecialStageAccessType getAccessType() { return SpecialStageAccessType.GIANT_RING; }
        @Override public void initializeStage(int stageIndex) throws IOException { }
        @Override public int getCurrentStage() { return 0; }
        @Override public boolean isEmeraldCollected() { return false; }
        @Override public int getEmeraldIndex() { return -1; }
        @Override public int getRingsCollected() { return 0; }
        @Override public void setEmeraldCollected(boolean collected) { }
        @Override public boolean isSpriteDebugMode() { return false; }
        @Override public void toggleSpriteDebugMode() { }
        @Override public void cyclePlaneDebugMode() { }
        @Override public SpecialStageDebugProvider getDebugProvider() { return null; }
        @Override public boolean isAlignmentTestMode() { return false; }
        @Override public void toggleAlignmentTestMode() { }
        @Override public void adjustAlignmentOffset(int delta) { }
        @Override public void adjustAlignmentSpeed(double delta) { }
        @Override public void toggleAlignmentStepMode() { }
        @Override public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) { }
        @Override public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) { }
        @Override public void setLagCompensation(double factor) { }
        @Override public ResultsScreen createResultsScreen(
                int ringsCollected, boolean gotEmerald, int stageIndex, int totalEmeraldCount) {
            return null;
        }
    }
}
