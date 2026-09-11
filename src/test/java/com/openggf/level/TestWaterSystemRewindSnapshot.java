package com.openggf.level;

import com.openggf.data.Rom;
import com.openggf.game.DynamicWaterHandler;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.rewind.snapshot.WaterSystemSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestWaterSystemRewindSnapshot {

    @Test
    void roundTripPreservesWaterEnteredCounter() {
        WaterSystem ws = new WaterSystem();
        ws.incrementWaterEnteredCounter();
        ws.incrementWaterEnteredCounter();
        WaterSystemSnapshot snap = ws.capture();
        ws.reset();
        ws.restore(snap);
        assertEquals(2, ws.getWaterEnteredCounter());
    }

    @Test
    void keyIsWater() {
        assertEquals("water", new WaterSystem().key());
    }

    @Test
    void captureWithNoDynamicStateIsEmpty() {
        WaterSystem ws = new WaterSystem();
        WaterSystemSnapshot snap = ws.capture();
        assertEquals(0, snap.waterEnteredCounter());
        assertTrue(snap.dynamicStates().isEmpty());
    }

    @Test
    void restoreWithNoMatchingEntryIsNoOp() {
        WaterSystem ws = new WaterSystem();
        // Restore a snapshot with an entry that doesn't exist in the current system
        Map<String, WaterSystemSnapshot.DynamicWaterEntry> entries = Map.of(
                "99_0", new WaterSystemSnapshot.DynamicWaterEntry(100, 200, 150, true, 1, false, false, 0)
        );
        WaterSystemSnapshot snap = new WaterSystemSnapshot(5, entries);
        assertDoesNotThrow(() -> ws.restore(snap));
        assertEquals(5, ws.getWaterEnteredCounter());
    }

    @Test
    void roundTripPreservesRuntimeWaterEnableFlag() {
        WaterSystem ws = new WaterSystem();
        ws.loadForLevelFromProvider(new TestWaterProvider(), null,
                99, 0, PlayerCharacter.SONIC_ALONE);
        assertTrue(ws.hasWater(99, 0));

        ws.setWaterEnabled(99, 0, false);
        WaterSystemSnapshot disabled = ws.capture();
        assertFalse(ws.hasWater(99, 0));

        ws.setWaterEnabled(99, 0, true);
        ws.restore(disabled);
        assertFalse(ws.hasWater(99, 0));
        assertEquals(0x618, ws.getWaterLevelY(99, 0),
                "disabling Water_flag must retain the loaded water registers");
    }

    @Test
    void liveWaterFlagRequiresLoadedDynamicStateAndTracksItsEnabledBit() {
        WaterSystem ws = new WaterSystem();
        assertFalse(ws.isLiveWaterFlagSet(99, 0),
                "an absent dynamic state is not a live Water_flag");

        ws.loadForLevelFromProvider(new TestWaterProvider(), null,
                99, 0, PlayerCharacter.SONIC_ALONE);
        assertTrue(ws.isLiveWaterFlagSet(99, 0));

        ws.setWaterEnabled(99, 0, false);
        assertFalse(ws.isLiveWaterFlagSet(99, 0));
    }

    private static final class TestWaterProvider implements WaterDataProvider {
        @Override public boolean hasWater(int zoneId, int actId, PlayerCharacter character) { return true; }
        @Override public int getStartingWaterLevel(int zoneId, int actId) { return 0x618; }
        @Override public Palette[] getUnderwaterPalette(
                Rom rom, int zoneId, int actId, PlayerCharacter character) { return null; }
        @Override public DynamicWaterHandler getDynamicHandler(
                int zoneId, int actId, PlayerCharacter character) { return null; }
    }
}
