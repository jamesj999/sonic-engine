package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossArenaHelperInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossDefeatFragmentChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossEggCapsuleInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossHitProxyChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossPaletteFadeController;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikHeadChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossRobotnikShipFlameInstance;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossSpikeChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossVisualChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherMachineChild;
import com.openggf.game.sonic3k.objects.bosses.MhzEndBossWeatherVisualChild;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every batch-5 S3K object that was previously dropped on a
 * held-rewind restore still exposes the Phase-2 {@link RewindRecreatable}
 * generic path.
 *
 * <p>Batch 5 has no accept-drop objects: each listed class is genuinely
 * recreated on restore (gameplay-critical hazards/structures or persistent
 * cosmetic children that fly for many frames), so there are no documented
 * transient-cosmetic drops to exclude here.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS3KBatch5Codecs {

    private static boolean hasDynamicRecreatePath(String className) {
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void releaseSliceBatch5ObjectsHaveGenericRecreatePaths() {
        List<String> required = List.of(
                // CNZ traversal / launch objects.
                CnzBumperObjectInstance.class.getName(),
                CnzCannonInstance.class.getName(),
                CnzCylinderInstance.class.getName(),
                CnzLightsFlashChildInstance.class.getName(),
                // MHZ end-boss family (boss + self-contained objects).
                MhzEndBossInstance.class.getName(),
                MhzEndBossEggCapsuleInstance.class.getName(),
                MhzEndBossPaletteFadeController.class.getName(),
                MhzEndBossArenaHelperInstance.class.getName(),
                // MHZ end-boss parent-relinked children.
                MhzEndBossRobotnikHeadChild.class.getName(),
                MhzEndBossRobotnikShipFlameInstance.class.getName(),
                MhzEndBossSpikeChild.class.getName(),
                MhzEndBossVisualChild.class.getName(),
                MhzEndBossWeatherMachineChild.class.getName(),
                MhzEndBossWeatherVisualChild.class.getName(),
                MhzEndBossHitProxyChild.class.getName(),
                MhzEndBossDefeatFragmentChild.class.getName());

        for (String name : required) {
            assertTrue(hasDynamicRecreatePath(name),
                    "missing rewind recreate path for " + name);
        }
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossArenaHelperInstance.class.getName()),
                "MhzEndBossArenaHelperInstance must use the RewindRecreatable generic path, not an explicit codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossRobotnikHeadChild.class.getName()),
                "MhzEndBossRobotnikHeadChild must use the RewindRecreatable generic path, not an explicit codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossSpikeChild.class.getName()),
                "MhzEndBossSpikeChild must use the RewindRecreatable generic path, not an explicit codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossVisualChild.class.getName()),
                "MhzEndBossVisualChild must use the RewindRecreatable generic path, not an explicit codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossWeatherMachineChild.class.getName()),
                "MhzEndBossWeatherMachineChild must use the RewindRecreatable generic path, not an explicit codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossWeatherVisualChild.class.getName()),
                "MhzEndBossWeatherVisualChild must use the RewindRecreatable generic path, not an explicit codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(MhzEndBossHitProxyChild.class.getName()),
                "MhzEndBossHitProxyChild must use the RewindRecreatable generic path, not an explicit codec");
    }
}
