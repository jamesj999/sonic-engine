package com.openggf.game.sonic2.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Verifies that batch-inner1 S2 children now restore through generic recreate
 * and do not regain explicit dynamic codecs.
 *
 * <p>These are static nested children keyed by their JVM binary name
 * ({@code Outer$Inner}):
 * <ul>
 *   <li>{@code SmallMetalPformObjectInstance$SmallMetalPformChildInstance} — the WFZ
 *       ObjBD rideable metal platform child (top-solid {@code SolidObjectProvider}).
 *       Restores through generic recreate; {@code xFlipped = (renderFlags & 0x01) != 0}
 *       is fully spawn-derivable, so no parent relink is required.</li>
 *   <li>{@code Sonic2DEZEggmanInstance$BarrierWall} — the DEZ Eggman solid barrier wall
 *       ({@code SolidObjectProvider}). Restores through generic recreate; Eggman's
 *       structural {@code barrierWall} back-reference is relinked by the
 *       {@code RewindRecreatable} hook.</li>
 *   <li>{@code Sonic2MTZBossInstance$MTZBossLaser} — the MTZ boss fired laser
 *       ({@code TouchResponseProvider} HURT hazard). Now restored through generic
 *       recreate; {@code xVel} (firing direction, not spawn-derivable) is reapplied
 *       by the capturer after recreate.</li>
 * </ul>
 *
 * <p>Pure metadata test: it reads deleted-codec state without a ROM, OpenGL, or
 * an active gameplay session. Full session round-trip coverage is enforced by
 * the rewind coverage guard ({@code TestRewindCoverageGuard}).
 */
class TestRewindFixS2InnerBatch1Codecs {

    @Test
    void batchInner1S2ChildrenHaveExpectedCodecCoverage() {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        "com.openggf.game.sonic2.objects.SmallMetalPformObjectInstance"
                                + "$SmallMetalPformChildInstance"),
                "SmallMetalPform child must restore through RewindRecreatable generic recreate, "
                        + "not a batch-inner1 codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        "com.openggf.game.sonic2.objects.bosses.Sonic2DEZEggmanInstance$BarrierWall"),
                "DEZ barrier wall must restore through RewindRecreatable generic recreate, "
                        + "not a batch-inner1 codec");
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(
                        "com.openggf.game.sonic2.objects.bosses.Sonic2MTZBossInstance$MTZBossLaser"),
                "MTZ boss laser must restore through RewindRecreatable generic recreate, "
                        + "not a batch-inner1 codec");
    }
}
