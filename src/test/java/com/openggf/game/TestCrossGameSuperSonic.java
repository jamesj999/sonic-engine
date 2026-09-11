package com.openggf.game;

import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.sprites.playable.SuperStateController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies cross-game Super Sonic delegation behavior.
 */
public class TestCrossGameSuperSonic {

    @AfterEach
    public void tearDown() {
        CrossGameFeatureProvider.getInstance().resetState();
    }

    @Test
    public void vanillaS1HasNoSuperSonic() {
        // When cross-game is NOT active, Sonic1GameModule should return null
        Sonic1GameModule s1Module = new Sonic1GameModule();
        assertNull(s1Module.createSuperStateController(null), "Vanilla S1 should not support Super Sonic");
    }

    @Test
    public void inactiveProviderReturnsNull() {
        // Provider not initialized â€” should return null
        CrossGameFeatureProvider provider = CrossGameFeatureProvider.getInstance();
        SuperStateController ctrl = provider.createSuperStateController(null);
        assertNull(ctrl, "Inactive provider should return null");
    }

    @Test
    public void crossGameInactiveCheckReturnsNullForS1() {
        // CrossGameFeatureProvider.isActive() is false => S1 module returns null
        assertFalse(CrossGameFeatureProvider.isActive(), "Provider should not be active without initialization");
        Sonic1GameModule s1Module = new Sonic1GameModule();
        assertNull(s1Module.createSuperStateController(null));
    }
}


