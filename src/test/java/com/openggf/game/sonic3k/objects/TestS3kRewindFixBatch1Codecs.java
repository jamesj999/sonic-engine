package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.objects.badniks.S3kBadnikProjectileInstance;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every HCZ/MHZ/MGZ/ICZ release-slice object that was previously
 * dropped on a held-rewind restore still exposes the Phase-2
 * {@link RewindRecreatable} generic path.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. A full session round-trip is a separate concern
 * handled by the rewind campaign / coverage guard.
 */
class TestS3kRewindFixBatch1Codecs {

    private static boolean hasDynamicRecreatePath(String className) {
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void releaseSliceBatch1ObjectsHaveDynamicRecreatePaths() {
        List<String> required = List.of(
                HCZConveyorBeltObjectInstance.class.getName(),
                MhzPulleyLiftObjectInstance.class.getName(),
                MhzSwingVineObjectInstance.class.getName(),
                S3kBadnikProjectileInstance.class.getName(),
                MGZHeadTriggerProjectileInstance.class.getName(),
                IczBigSnowPileInstance.class.getName(),
                S3kSignpostInstance.class.getName(),
                S3kSignpostStubChild.class.getName(),
                S3kAirCountdownObjectInstance.class.getName(),
                Sonic3kStarPostStarChild.class.getName());

        for (String name : required) {
            assertTrue(hasDynamicRecreatePath(name),
                    "missing rewind recreate path for " + name);
        }
    }
}
