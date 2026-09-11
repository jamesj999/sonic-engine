package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.objects.bosses.CPZBossFallingPart;
import com.openggf.game.sonic2.objects.bosses.CPZBossSmokePuff;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that old batch-4 S2 objects stay on generic recreate coverage.
 *
 * <p>The CPZ boss-component chain, OOZ burner flame, falling part, ARZ rising
 * bubble, smoke puff, and scalar-only lava/MCZ falling hazards now use graph/generic
 * recreate.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 */
class TestRewindFixS2Batch4Codecs {

    @Test
    void batch4S2ObjectsKeepGenericRecreateCoverage() {
        List<Class<?>> deleted = List.of(
                OOZBurnerFlameObjectInstance.class,
                BubbleObjectInstance.class,
                CPZBossFallingPart.class,
                CPZBossSmokePuff.class);

        for (Class<?> type : deleted) {
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getName() + " must restore through RewindRecreatable generic recreate");
        }
    }
}
