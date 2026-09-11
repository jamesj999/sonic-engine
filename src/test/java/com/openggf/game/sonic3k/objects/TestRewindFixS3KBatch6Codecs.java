package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every batch-6 S3K object that was previously dropped on a
 * held-rewind restore still exposes the Phase-2 {@link RewindRecreatable}
 * generic path.
 *
 * <p>Batch 6 has no accept-drop objects: each listed class is genuinely recreated
 * on restore (gameplay-critical cutscene/terrain/boss objects, or a one-shot
 * cosmetic spark that lives many frames), so there are no documented
 * transient-cosmetic drops to exclude here.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS3KBatch6Codecs {

    private static boolean hasDynamicRecreatePath(String className) {
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void releaseSliceBatch6ObjectsHaveGenericRecreatePaths() {
        List<String> required = List.of(
                // LBZ1 Knuckles cutscene family.
                CutsceneKnucklesLbz1CollapseChild.class.getName(),
                CutsceneKnucklesLbz1RangeHelper.class.getName(),
                CutsceneKnucklesLbz1ThrownBomb.class.getName(),
                // AIZ1 intro Knuckles rock cutscene (parent + child).
                CutsceneKnucklesAiz1Instance.class.getName(),
                CutsceneKnucklesRockChild.class.getName(),
                // CNZ2 Knuckles cutscene blocking wall.
                CutsceneKnuxCnz2WallInstance.class.getName(),
                // S3K shields / spark.
                InstaShieldObjectInstance.class.getName(),
                LightningSparkObjectInstance.class.getName(),
                // MGZ end-of-act objects.
                Mgz2EndEggCapsuleInstance.class.getName(),
                Mgz2CapsuleAnimalInstance.class.getName(),
                S3kResultsScreenObjectInstance.class.getName(),
                Mgz2ResultsScreenObjectInstance.class.getName(),
                Mgz2LevelCollapseSolidInstance.class.getName(),
                MgzDrillingRobotnikInstance.class.getName(),
                MgzEndBossInstance.class.getName(),
                // MHZ ship-sequence controller.
                MhzShipSequenceControllerInstance.class.getName());

        for (String name : required) {
            assertTrue(hasDynamicRecreatePath(name),
                    "missing rewind recreate path for " + name);
        }

        assertGenericOnly(CutsceneKnucklesRockChild.class);
        assertGenericOnly(CutsceneKnuxCnz2WallInstance.class);
    }

    private static void assertGenericOnly(Class<?> type) {
        assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                type.getName() + " must use RewindRecreatable generic recreate");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                type.getName() + " must not keep an explicit dynamic rewind codec");
    }
}
