package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class Sonic2SpecialStagePerPlayerRingsTest {
    @Test
    void tailsCollectionIncrementsOnlyTailsAndCombinedTotal() {
        Sonic2SpecialStagePlayer sonic = player(Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = player(Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objects = objectManager();

        objects.collectRing(tails);

        assertEquals(0, sonic.getRings());
        assertEquals(1, tails.getRings());
        assertCombinedEqualsPlayers(objects, sonic, tails);
    }

    @Test
    void sonicBombDebitUsesRomTenRingRuleAndFloorsAtZeroWithoutTouchingTails() {
        Sonic2SpecialStagePlayer sonic = player(Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = player(Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objects = objectManager();
        collect(objects, sonic, 12);
        collect(objects, tails, 4);

        assertEquals(10, objects.loseRingsFromBombHit(sonic));
        assertEquals(2, sonic.getRings());
        assertEquals(4, tails.getRings());
        assertCombinedEqualsPlayers(objects, sonic, tails);

        assertEquals(2, objects.loseRingsFromBombHit(sonic));
        assertEquals(0, sonic.getRings());
        assertEquals(4, tails.getRings());
        assertCombinedEqualsPlayers(objects, sonic, tails);
    }

    @Test
    void rewindSnapshotsRestoreBothPlayerCountsAndCombinedInvariant() {
        Sonic2SpecialStagePlayer sonic = player(Sonic2SpecialStagePlayer.PlayerType.SONIC, true);
        Sonic2SpecialStagePlayer tails = player(Sonic2SpecialStagePlayer.PlayerType.TAILS, false);
        Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objects = objectManager();
        collect(objects, sonic, 12);
        collect(objects, tails, 4);
        Sonic2SpecialStageSnapshot.PlayerSnapshot sonicSnapshot = sonic.captureRewindSnapshot();
        Sonic2SpecialStageSnapshot.PlayerSnapshot tailsSnapshot = tails.captureRewindSnapshot();
        Sonic2SpecialStageSnapshot.ObjectManagerSnapshot objectSnapshot = objects.captureRewindSnapshot();

        objects.collectRing(tails);
        objects.loseRingsFromBombHit(sonic);
        sonic.restoreRewindSnapshot(sonicSnapshot);
        tails.restoreRewindSnapshot(tailsSnapshot);
        objects.restoreRewindSnapshot(objectSnapshot, new Sonic2SpecialStageManager());

        assertEquals(12, sonic.getRings());
        assertEquals(4, tails.getRings());
        assertCombinedEqualsPlayers(objects, sonic, tails);
    }

    private static Sonic2SpecialStagePlayer player(Sonic2SpecialStagePlayer.PlayerType type,
                                                    boolean main) {
        return new Sonic2SpecialStagePlayer(type, main, new Sonic2SpecialStageManager());
    }

    private static Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objectManager() {
        return new Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager(null);
    }

    private static void collect(Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objects,
                                Sonic2SpecialStagePlayer player,
                                int count) {
        for (int i = 0; i < count; i++) {
            objects.collectRing(player);
        }
    }

    private static void assertCombinedEqualsPlayers(
            Sonic2SpecialStageManager.Sonic2SpecialStageObjectManager objects,
            Sonic2SpecialStagePlayer sonic,
            Sonic2SpecialStagePlayer tails) {
        assertEquals(sonic.getRings() + tails.getRings(), objects.getRingsCollected());
    }
}
