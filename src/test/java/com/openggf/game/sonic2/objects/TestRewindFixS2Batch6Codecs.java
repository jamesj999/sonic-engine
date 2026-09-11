package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.bosses.CPZBossContainerExtend;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SplashObjectInstance;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every batch-6 S2 object that was previously dropped on a
 * held-rewind restore keeps a generic recreate path.
 *
 * <p>Batch-6 adds parent/sibling relink for the HTZ seesaw ball
 * (relinked to its placed parent seesaw) and the CPZ boss container extend
 * (relinked to its live boss + container parents). The CNZ slot-machine ring
 * prize, MTZ steam puff, and shared water splash now restore through generic
 * recreate.
 *
 * <p>The remaining cosmetic transient child evaluated in this batch,
 * {@code SuperSonicStarsObjectInstance}, is still intentionally accept-drop and
 * documented in {@code docs/status/known-discrepancies.md}.
 */
class TestRewindFixS2Batch6Codecs {

    @Test
    void batch6S2ObjectsKeepGenericRecreateCoverage() {
        List<Class<?>> covered = List.of(
                CPZBossContainerExtend.class,
                SeesawBallObjectInstance.class,
                SteamPuffObjectInstance.class,
                RingPrizeObjectInstance.class,
                SplashObjectInstance.class);

        for (Class<?> type : covered) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getName() + " must restore through RewindRecreatable generic recreate");
        }
    }
}
