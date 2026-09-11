package com.openggf.game;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic1.credits.Sonic1EndingProvider;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.credits.Sonic2EndingProvider;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Cross-game tests verifying the {@link EndingProvider} contract is
 * satisfied by all game modules that provide an ending implementation.
 * <p>
 * Tests here are ROM-free and verify:
 * <ul>
 *   <li>Each GameModule returns the correct provider type</li>
 *   <li>EndingPhase enum covers all expected values</li>
 *   <li>Pre-initialize safety (no exceptions from accessors)</li>
 * </ul>
 */
public class TestEndingProviderContract {

    // ========================================================================
    // Sonic 1 GameModule returns Sonic1EndingProvider
    // ========================================================================

    @Test
    public void testSonic1GameModuleHasEndingProvider() {
        Sonic1GameModule module = new Sonic1GameModule();
        EndingProvider provider = module.getEndingProvider();
        assertNotNull(provider, "Sonic1GameModule should provide an EndingProvider");
        assertTrue(provider instanceof Sonic1EndingProvider, "Sonic1GameModule EndingProvider should be Sonic1EndingProvider");
    }

    // ========================================================================
    // Sonic 2 GameModule returns Sonic2EndingProvider
    // ========================================================================

    @Test
    public void testSonic2GameModuleHasEndingProvider() {
        Sonic2GameModule module = new Sonic2GameModule();
        EndingProvider provider = module.getEndingProvider();
        assertNotNull(provider, "Sonic2GameModule should provide an EndingProvider");
        assertTrue(provider instanceof Sonic2EndingProvider, "Sonic2GameModule EndingProvider should be Sonic2EndingProvider");
    }

    // ========================================================================
    // GameModule default returns null (for games without endings)
    // ========================================================================

    @Test
    public void testDefaultEndingProviderIsNull() {
        // The default implementation in GameModule interface returns null.
        // Use Mockito.CALLS_REAL_METHODS to invoke the default method without
        // implementing every abstract method by hand.
        GameModule stubModule = mock(GameModule.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        EndingProvider defaultResult = stubModule.getEndingProvider();

        assertNull(defaultResult, "Default GameModule.getEndingProvider() should return null");
    }

    // ========================================================================
    // EndingPhase enum completeness
    // ========================================================================

    @Test
    public void testEndingPhaseHasFiveValues() {
        EndingPhase[] phases = EndingPhase.values();
        assertEquals(5, phases.length, "EndingPhase should have 5 values");
    }

    @Test
    public void testEndingPhaseValuesExist() {
        // Verify all expected enum constants exist (compilation would fail if not,
        // but this documents the contract explicitly)
        assertNotNull(EndingPhase.CUTSCENE);
        assertNotNull(EndingPhase.CREDITS_TEXT);
        assertNotNull(EndingPhase.CREDITS_DEMO);
        assertNotNull(EndingPhase.POST_CREDITS);
        assertNotNull(EndingPhase.FINISHED);
    }

    @Test
    public void testEndingPhaseOrdinalOrder() {
        // Verify phases are in the expected sequential order
        assertTrue(EndingPhase.CUTSCENE.ordinal() < EndingPhase.CREDITS_TEXT.ordinal(), "CUTSCENE should come before CREDITS_TEXT");
        assertTrue(EndingPhase.CREDITS_TEXT.ordinal() < EndingPhase.CREDITS_DEMO.ordinal(), "CREDITS_TEXT should come before CREDITS_DEMO");
        assertTrue(EndingPhase.CREDITS_DEMO.ordinal() < EndingPhase.POST_CREDITS.ordinal(), "CREDITS_DEMO should come before POST_CREDITS");
        assertTrue(EndingPhase.POST_CREDITS.ordinal() < EndingPhase.FINISHED.ordinal(), "POST_CREDITS should come before FINISHED");
    }

    // ========================================================================
    // Pre-initialize safety
    // ========================================================================

    @Test
    public void testSonic1ProviderSafeBeforeInitialize() {
        Sonic1EndingProvider provider = new Sonic1EndingProvider();
        // These should not throw before initialize() is called
        assertFalse(provider.isComplete());
        assertNotNull(provider.getCurrentPhase());
        assertEquals(EndingPhase.CREDITS_TEXT, provider.getCurrentPhase());
    }

    @Test
    public void testSonic2ProviderSafeBeforeInitialize() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        // These should not throw before initialize() is called
        assertFalse(provider.isComplete());
        assertNotNull(provider.getCurrentPhase());
        assertEquals(EndingPhase.CUTSCENE, provider.getCurrentPhase());
    }

    // ========================================================================
    // S1 initial phase differs from S2
    // ========================================================================

    @Test
    public void testSonic1InitialPhaseIsCreditsText() {
        // S1 has no cutscene, goes straight to credits text
        Sonic1EndingProvider provider = new Sonic1EndingProvider();
        assertEquals(EndingPhase.CREDITS_TEXT, provider.getCurrentPhase(), "S1 initial phase should be CREDITS_TEXT (no cutscene)");
    }

    @Test
    public void testSonic2InitialPhaseIsCutscene() {
        // S2 starts with a cutscene
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertEquals(EndingPhase.CUTSCENE, provider.getCurrentPhase(), "S2 initial phase should be CUTSCENE");
    }
}


