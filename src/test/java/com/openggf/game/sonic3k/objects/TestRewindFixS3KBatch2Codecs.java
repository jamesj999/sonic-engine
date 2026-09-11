package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.objects.badniks.BuggernautBabyInstance;
import com.openggf.game.sonic3k.objects.badniks.CaterkillerJrBodyInstance;
import com.openggf.game.sonic3k.objects.bosses.IczEndBossEggCapsuleInstance;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every old batch-2 S3K object that was previously dropped on a
 * held-rewind restore now exposes a generic recreate path.
 *
 * <p>The accept-drop {@code MgzEndBossDefeatDebrisChild} is intentionally NOT
 * listed: it stays uncovered (transient cosmetic, re-emitted in-frame) and is
 * documented in {@code docs/S3K_KNOWN_DISCREPANCIES.md}.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS3KBatch2Codecs {

    private static boolean hasDynamicRecreatePath(String className) {
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void releaseSliceBatch2ObjectsHaveGenericRecreatePaths() {
        List<String> required = List.of(
                AizRockFragmentChild.class.getName(),
                CnzMinibossDebrisChild.class.getName(),
                S3kBossExplosionChild.class.getName(),
                S3kSignpostSparkleChild.class.getName(),
                MhzPollenParticleInstance.class.getName(),
                IczEndBossEggCapsuleInstance.class.getName(),
                CaterkillerJrBodyInstance.class.getName(),
                MhzMinibossEscapeShardInstance.class.getName(),
                Sonic3kStarPostBonusStarChild.class.getName(),
                Sonic3kSSEntryFlashObjectInstance.class.getName(),
                BuggernautBabyInstance.class.getName());

        for (String name : required) {
            assertTrue(hasDynamicRecreatePath(name),
                    "missing rewind recreate path for " + name);
        }
    }
}
