package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.badniks.GrounderBadnikInstance;
import com.openggf.game.sonic2.objects.badniks.ShellcrackerClawInstance;
import com.openggf.game.sonic2.objects.badniks.SlicerPincerInstance;
import com.openggf.game.sonic2.objects.badniks.SolFireballObjectInstance;
import com.openggf.game.sonic2.objects.badniks.SpikerDrillObjectInstance;
import com.openggf.game.sonic2.objects.badniks.TurtloidJetInstance;
import com.openggf.game.sonic2.objects.badniks.TurtloidRiderInstance;
import com.openggf.game.sonic2.objects.bosses.CNZBossElectricBall;
import com.openggf.game.sonic2.objects.bosses.HTZBossFlamethrower;
import com.openggf.game.sonic2.objects.bosses.HTZBossLavaBall;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every old batch-3 S2 object that was previously dropped on a
 * held-rewind restore now exposes a generic recreate path.
 *
 * <p>Pure metadata test: it checks {@link RewindRecreatable} opt-ins without a
 * ROM, OpenGL, or an active gameplay session. Full session round-trip is
 * handled by the rewind coverage guard.
 */
class TestRewindFixS2Batch3Codecs {

    // EHZ boss construction-spawned children and HTZ boss hazards no longer need
    // explicit dynamic codecs; graph/generic recreate coverage owns them.
    private static final List<Class<?>> GENERIC_RECREATE_CLASSES = List.of(
            GrounderBadnikInstance.class,
            ShellcrackerClawInstance.class,
            SlicerPincerInstance.class,
            TurtloidJetInstance.class,
            TurtloidRiderInstance.class,
            SolFireballObjectInstance.class,
            SpikerDrillObjectInstance.class,
            WallTurretShotInstance.class,
            VerticalLaserObjectInstance.class,
            SpikyBlockSpikeInstance.class,
            BombPrizeObjectInstance.class,
            CNZBossElectricBall.class,
            HTZBossFlamethrower.class,
            HTZBossLavaBall.class);

    @Test
    void registersRestorePathsForBatch3S2Objects() {
        for (Class<?> type : GENERIC_RECREATE_CLASSES) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    "missing RewindRecreatable generic recreate path for " + type.getName());
        }
    }
}
