package com.openggf.game.sonic1;

import com.openggf.data.Rom;
import com.openggf.game.rewind.snapshot.NemesisPlcQueueSnapshot;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@RequiresRom(SonicGame.SONIC_1)
class TestSonic1SkippedPresentationPlcLifecycle {
    private Rom rom;

    @BeforeEach
    void setUp() {
        rom = TestEnvironment.currentRom();
    }

    @Test
    void finalZoneSkipReachesTheRomPostFadeQueueBoundary() throws Exception {
        Sonic1PlcService service = new Sonic1PlcService(rom);
        service.transact(
                Sonic1PlcService.clear(),
                Sonic1PlcService.appendOperation(14),
                Sonic1PlcService.appendOperation(1));

        Sonic1LevelInitProfile.completeInitialPresentationPlcs(rom, service, 5);

        NemesisPlcQueueSnapshot snapshot = service.capture();
        NemesisPlcQueueSnapshot.Entry active = snapshot.activeEntry();
        assertNotNull(active);
        assertEquals(0x0351F6, active.sourceAddress());
        assertEquals(0x0492, active.destinationTile());
        assertEquals(49, active.totalPatterns());
        assertEquals(22, active.remainingPatterns(),
                "22 palette iterations prepare once, then service 21 VBlanks with descriptor bubbles");

        assertEquals(List.of(
                entry(0x0353D4, 0x03F9, 4),
                entry(0x034EC6, 0x04DF, 48),
                entry(0x0344C4, 0x050F, 12),
                entry(0x02FCFE, 0x051B, 8),
                entry(0x03A80A, 0x0523, 16),
                entry(0x03A90C, 0x0533, 14)),
                snapshot.queuedEntries());
    }

    private static NemesisPlcQueueSnapshot.Entry entry(
            int sourceAddress, int destinationTile, int patterns) {
        return new NemesisPlcQueueSnapshot.Entry(
                sourceAddress, destinationTile, patterns, patterns);
    }
}
