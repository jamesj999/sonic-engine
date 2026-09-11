package com.openggf.game.sonic3k.features;

import com.openggf.game.sonic3k.objects.HCZBreakableBarStaticAdapter;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance.HCZBreakableBarState;
import com.openggf.game.sonic3k.objects.HCZWaterRushObjectInstance.HCZWaterRushPaletteCycleGate;
import com.openggf.game.sonic3k.objects.HCZWaterRushPaletteCycleStaticAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies rewind coverage for the four HCZ global static managers found by
 * {@code TestStaticStateRewindCoverageGuard} to share the MGZ dash-trigger
 * bug family: mutable cross-frame state consumed by gameplay objects but
 * never registered with the rewind registry.
 */
class TestHczWaterRewindStaticAdapters {

    @BeforeEach
    void setUp() {
        HCZWaterSkimHandler.reset();
        HCZWaterTunnelHandler.reset();
        HCZBreakableBarState.reset();
        HCZWaterRushPaletteCycleGate.reset();
    }

    @Test
    void waterSkimAdapterRoundTripsPerPlayerState() {
        var adapter = new HCZWaterSkimStaticAdapter();
        assertEquals("hcz-water-skim", adapter.key());

        var clean = adapter.capture();

        // Simulate a mid-skim frame having advanced splash animation state.
        HCZWaterSkimHandler.restore(new HCZWaterSkimHandler.Snapshot(
                true, false, 3, 0, 1, 0, 42));
        assertTrue(HCZWaterSkimHandler.isSkimActiveP1());

        adapter.restore(clean);
        assertFalse(HCZWaterSkimHandler.isSkimActiveP1(),
                "Restoring a pre-skim snapshot must clear the skim-active flag");
    }

    @Test
    void waterTunnelAdapterRoundTripsPerPlayerState() {
        var adapter = new HCZWaterTunnelStaticAdapter();
        assertEquals("hcz-water-tunnel", adapter.key());

        var clean = adapter.capture();

        HCZWaterTunnelHandler.restore(new HCZWaterTunnelHandler.Snapshot(
                true, false, 0x100, 0, 0, 0));
        assertTrue(HCZWaterTunnelHandler.isPlayerInTunnel(0));

        adapter.restore(clean);
        assertFalse(HCZWaterTunnelHandler.isPlayerInTunnel(0),
                "Restoring a pre-tunnel snapshot must clear the wind-tunnel flag");
    }

    @Test
    void breakableBarAdapterRoundTripsLatch() {
        var adapter = new HCZBreakableBarStaticAdapter();
        assertEquals("hcz-breakable-bar-state", adapter.key());

        var clean = adapter.capture();

        HCZBreakableBarState.setBit(0);
        assertTrue(HCZBreakableBarState.testBit(0));

        adapter.restore(clean);
        assertFalse(HCZBreakableBarState.testBit(0),
                "Restoring a pre-latch snapshot must clear the player bit");
    }

    @Test
    void paletteCycleGateAdapterRoundTripsFlag() {
        var adapter = new HCZWaterRushPaletteCycleStaticAdapter();
        assertEquals("hcz-water-rush-palette-cycle-gate", adapter.key());

        var clean = adapter.capture();

        HCZWaterRushPaletteCycleGate.setActive(true);
        assertTrue(HCZWaterRushPaletteCycleGate.isActive());

        adapter.restore(clean);
        assertFalse(HCZWaterRushPaletteCycleGate.isActive(),
                "Restoring a pre-activation snapshot must clear the gate");
    }
}
