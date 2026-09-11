package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.badniks.RexonHeadObjectInstance;
import com.openggf.game.sonic2.objects.bosses.CPZBossDripper;
import com.openggf.game.sonic2.objects.bosses.CPZBossPipeSegment;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that old batch-5 S2 objects stay on generic recreate coverage.
 *
 * <p>The original batch-5 codecs for CPZ-boss Dripper/PipeSegment, Rexon head,
 * Egg-prison button, destroyed Egg-prison body, ARZ leaf particle, and act
 * results have been deleted in favour of graph-tested or self-contained
 * {@code RewindRecreatable} generic recreate paths.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS2Batch5Codecs {

    @Test
    void batch5S2ObjectsKeepGenericRecreateCoverage() {
        List<Class<?>> deleted = List.of(
                CPZBossDripper.class,
                CPZBossPipeSegment.class,
                RexonHeadObjectInstance.class,
                EggPrisonButtonObjectInstance.class,
                LeafParticleObjectInstance.class,
                DestroyedEggPrisonObjectInstance.class,
                ResultsScreenObjectInstance.class);

        for (Class<?> type : deleted) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getName() + " must restore through RewindRecreatable generic recreate");
        }
    }
}
