package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every old batch-7 S3K object that was previously dropped on a
 * held-rewind restore now exposes a generic recreate path: the Pachinko
 * bonus-stage energy trap and sloped flipper, the boss-defeat-to-signpost
 * orchestrator, the song-fade transition object, the AIZ/LRZ rock debris
 * fragment, and the egg-prison released animal.
 *
 * <p>Batch 7 has no accept-drop objects: each listed class is genuinely
 * recreated on restore (gameplay-critical traps/terrain/orchestrators, or
 * persistent multi-frame debris/animals whose captured siblings are restored
 * rather than dropped), so there are no documented transient-cosmetic drops to
 * exclude here.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS3KBatch7Codecs {

    private static boolean hasDynamicRecreatePath(String className) {
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void releaseSliceBatch7ObjectsHaveGenericRecreatePaths() {
        List<String> required = List.of(
                // Pachinko bonus-stage capture trap + sloped flipper.
                PachinkoEnergyTrapObjectInstance.class.getName(),
                PachinkoFlipperObjectInstance.class.getName(),
                // AIZ/LRZ breakable-rock gravity debris.
                RockDebrisChild.class.getName(),
                // Boss-defeat -> signpost -> results -> act-transition flow.
                S3kBossDefeatSignpostFlow.class.getName(),
                // Pending music-transition object.
                SongFadeTransitionInstance.class.getName(),
                // Egg-prison released animal (game-agnostic, spawned by S3K capsules).
                EggPrisonAnimalInstance.class.getName());

        for (String name : required) {
            assertTrue(hasDynamicRecreatePath(name),
                    "missing rewind recreate path for " + name);
        }
    }
}
