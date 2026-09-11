package com.openggf.trace.replay.runs;

import com.openggf.game.profiles.trace.TracePlaybackProfile;
import com.openggf.trace.TraceRunManifest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunVblankClock {

    @Test
    void levelDestinationsUseTheSameManifestMovieBudgetAsHeadlessChains() {
        TraceRunManifest.Segment ghz1 = level("ghz1", 788, 5_598, 1);
        TraceRunManifest.Segment ghz2 = level("ghz2", 6_622, 4_028, 2);
        TraceRunManifest.Segment ghz3 = level("ghz3", 10_885, 9_678, 3);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.SONIC_1);

        clock.captureLevelSourceTail(0, ghz1, 6_386, 0x17B7);
        assertEquals(0x17B7 + 230,
                clock.levelDestinationTarget(0, ghz1, ghz2, 0).orElseThrow());

        clock.captureLevelSourceTail(1, ghz2, 10_650, 0x2850);
        assertEquals(0x2850 + 229,
                clock.levelDestinationTarget(1, ghz2, ghz3, 0).orElseThrow());
    }

    @Test
    void disabledProfilesNeverRewriteTheProductionClock() {
        TraceRunManifest.Segment act1 = level("act1", 10, 20, 1);
        TraceRunManifest.Segment act2 = level("act2", 40, 20, 2);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.DISABLED);

        clock.captureLevelSourceTail(0, act1, 30, 1234);

        assertTrue(clock.levelDestinationTarget(0, act1, act2, 0).isEmpty());
    }

    @Test
    void specialStageReturnUsesThePreservedSourceLevelAnchor() {
        TraceRunManifest.Segment source = level("ghz1", 100, 10, 1);
        TraceRunManifest.Segment returned = level("ghz2", 140, 10, 2);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.SONIC_1);

        clock.captureLevelSourceTail(0, source, 110, 0x500);

        assertEquals(0x500 + 31,
                clock.uncomparedInteriorReturnTarget(
                        0, source, returned, 1).orElseThrow());
    }

    @Test
    void specialStageReturnTargetFollowsTheConsumedDestinationRowCount() {
        TraceRunManifest.Segment source = level("ghz1", 100, 10, 1);
        TraceRunManifest.Segment returned = level("ghz2", 140, 10, 2);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.SONIC_1);

        clock.captureLevelSourceTail(0, source, 110, 0x500);

        // One consumed row is the shape every committed uncompared return has;
        // a second would carry its own tick rather than leaving the anchor to
        // sit on a row the engine has already run past.
        assertEquals(0x500 + 31,
                clock.uncomparedInteriorReturnTarget(
                        0, source, returned, 1).orElseThrow());
        assertEquals(0x500 + 32,
                clock.uncomparedInteriorReturnTarget(
                        0, source, returned, 2).orElseThrow());
    }

    /**
     * The committed emerald route's first results-screen bridge, at its real
     * manifest rows: ghz1 ends at BK2 4,974, the bridge starts at 8,705 and
     * runs 800 rows, and ghz2's gameplay resumes at 9,741. The recorded ROM
     * clock is 0x1234 entering BK2 4,975, 0x20C7 on the bridge's first row and
     * 0x24C6 on ghz2's -- so the bridge is entered on 0x20C6, leaves on 0x23DF,
     * and hands ghz2 0x24C5 to tick into 0x24C6.
     */
    @Test
    void aPresentationBridgeCarriesTheClockItsOwnRowsCouldNotTick() {
        TraceRunManifest.Segment ghz1 = level("ghz1", 860, 4_115, 1);
        TraceRunManifest.Segment bridge = level("ghz2", 8_705, 800, 2);
        TraceRunManifest.Segment ghz2 = level("ghz2_2", 9_741, 3_606, 2);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.SONIC_1);

        clock.captureLevelSourceTail(0, ghz1, 4_975, 0x1234);
        assertEquals(0x20C6,
                clock.presentationBridgeEntryTarget(0, ghz1, 2, bridge)
                        .orElseThrow());

        // The bridge's own production counter never ticks, so its observed
        // tail is the value it was seeded with -- the derived span must not
        // consult it.
        clock.captureLevelSourceTail(2, bridge, 9_504, 0x20C6);
        assertEquals(0x24C5,
                clock.levelDestinationTarget(2, bridge, ghz2, 0).orElseThrow());
    }

    @Test
    void disabledProfilesNeverSeedAPresentationBridge() {
        TraceRunManifest.Segment source = level("act1", 10, 20, 1);
        TraceRunManifest.Segment bridge = level("bridge", 60, 20, 2);
        TraceRunVblankClock clock = new TraceRunVblankClock(
                TracePlaybackProfile.DISABLED);

        clock.captureLevelSourceTail(0, source, 30, 1234);

        assertTrue(clock.presentationBridgeEntryTarget(0, source, 1, bridge)
                .isEmpty());
    }

    private static TraceRunManifest.Segment level(
            String dir, int offset, int frames, int act) {
        return new TraceRunManifest.Segment(
                dir, "level", "complete_run", offset, frames,
                0, act, null, null);
    }
}
