package com.openggf.game.sonic2.credits;

import com.openggf.game.EndingProvider;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link Sonic2EndingProvider} state machine and phase mapping.
 * <p>
 * These tests exercise the provider's public API without requiring ROM,
 * OpenGL, or a running engine. They verify:
 * <ul>
 *   <li>Initial slide and logo-flash state before initialize()</li>
 *   <li>S2-specific demo defaults (all no-ops)</li>
 *   <li>GameModule integration</li>
 * </ul>
 */
public class TestSonic2EndingProvider {

    // ========================================================================
    // Initial state (before initialize)
    // ========================================================================

    @Test
    public void testInitialSlideIsZero() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertEquals(0, provider.getCurrentSlide(), "Initial slide should be 0");
    }

    // ========================================================================
    // S2 does not use demo playback defaults
    // ========================================================================

    @Test
    public void testNoDemoLoadRequest() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertFalse(provider.hasDemoLoadRequest(), "S2 ending should never have demo load requests");
    }

    @Test
    public void testNoTextReturnRequest() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertFalse(provider.hasTextReturnRequest(), "S2 ending should never have text return requests");
    }

    @Test
    public void testNoLamppostState() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertNull(provider.getDemoLamppostState(), "S2 ending should never return lamppost state");
    }

    @Test
    public void testScrollNotFrozen() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertFalse(provider.isScrollFrozen(), "S2 ending should not freeze scroll by default");
    }

    @Test
    public void testDemoInputMaskIsZero() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertEquals(0, provider.getDemoInputMask(), "S2 ending demo input mask should be 0");
    }

    @Test
    public void testDemoCoordinatesAreZero() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertEquals(0, provider.getDemoZone());
        assertEquals(0, provider.getDemoAct());
        assertEquals(0, provider.getDemoStartX());
        assertEquals(0, provider.getDemoStartY());
    }

    // ========================================================================
    // GameModule integration
    // ========================================================================

    @Test
    public void testSonic2GameModuleCreatesNewProviderPerCall() {
        Sonic2GameModule module = new Sonic2GameModule();
        EndingProvider first = module.getEndingProvider();
        EndingProvider second = module.getEndingProvider();
        assertNotSame(first, second, "Each getEndingProvider() call should create a new instance");
    }

    // ========================================================================
    // Logo flash manager accessor
    // ========================================================================

    @Test
    public void testLogoFlashManagerNullBeforePostCredits() {
        Sonic2EndingProvider provider = new Sonic2EndingProvider();
        assertNull(provider.getLogoFlashManager(), "Logo flash manager should be null before POST_CREDITS phase");
    }
}
