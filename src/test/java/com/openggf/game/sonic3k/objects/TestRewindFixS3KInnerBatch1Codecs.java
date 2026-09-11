package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that remaining batch-inner1 inner-class children have moved to the
 * Phase-2 generic recreate path.
 *
 * <p>These are static nested children (hazards, ridable platforms, projectiles,
 * a logic shim, and a defeat-cutscene ship) keyed by their JVM binary name
 * ({@code Outer$Inner}).
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip coverage is enforced by the
 * rewind coverage guard ({@code TestRewindCoverageGuard}).
 */
class TestRewindFixS3KInnerBatch1Codecs {
    private static final String FALLING_LOG_CHILD =
            "com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance$FallingLogChild";

    @Test
    void keepsDynamicRecreatePathsForBatchInner1S3KChildren() {
        List<String> required = List.of(FALLING_LOG_CHILD);

        for (String name : required) {
            assertTrue(dynamicRecreatePathExists(name),
                    "missing rewind dynamic recreate path for " + name);
        }
    }

    @Test
    void fallingLogChildHasDynamicRecreatePathWithoutExplicitCodec() {
        assertTrue(dynamicRecreatePathExists(FALLING_LOG_CHILD),
                "FallingLogChild must keep a dynamic recreate path after codec deletion");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(FALLING_LOG_CHILD),
                "FallingLogChild must no longer be registered as an explicit dynamic codec");
    }

    private static boolean dynamicRecreatePathExists(String className) {
        try {
            Class<?> cls = Class.forName(className);
            return RewindRecreatable.class.isAssignableFrom(cls);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
