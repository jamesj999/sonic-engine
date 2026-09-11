package com.openggf.game.sonic1.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every batch-inner1 S1 inner-class hazard/solid/cutscene child
 * that was previously dropped on a held-rewind restore now exposes a generic
 * recreate path.
 *
 * <p>These are static nested children keyed by their JVM binary name
 * ({@code Outer$Inner}). Parent-owned children either keep an explicit codec or
 * implement {@link RewindRecreatable} and use the generic recreate path; non-spawn
 * differentiator scalars are then reapplied by the generic field capturer:
 * <ul>
 *   <li>{@code Sonic1OrbinautBadnikInstance$OrbSpikeObjectInstance} — Orbinaut HURT
 *       satellite/projectile. Uses {@link RewindRecreatable} generic recreate to
 *       relink/adopt into the live Orbinaut parent; all live state is non-final
 *       and reapplied after recreate.</li>
 *   <li>{@code Sonic1FalseFloorInstance$FalseFloorBlock} — SBZ2 boss collapsing-floor
 *       tile. Uses {@link RewindRecreatable} generic recreate to re-register with the
 *       restored master; {@code currentX}/{@code currentY}/{@code blockIndex} are
 *       restored afterward.</li>
 * </ul>
 *
 * <p><b>Intentionally absent:</b> {@code ScrapEggmanButton} is construction-spawned
 * (the {@code Sonic1ScrapEggmanInstance} constructor directly calls
 * {@code spawnDynamicObject(button)}). Registering a codec would double it on
 * rewind restore (1 → 2) because boss reconstruction already re-adds it.
 * See {@code TestBossChildNoDoubleSpawnParity} and {@code docs/status/known-discrepancies.md}.
 *
 * <p>The SBZ rotating-junction display child now uses spawn-based generic recreate and
 * is asserted by {@code TestScalarOnlyCodecDeletion}.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip coverage is enforced by the
 * rewind coverage guard ({@code TestRewindCoverageGuard}).
 */
class TestRewindFixS1InnerBatch1Codecs {

    @Test
    void orbSpikeUsesRewindRecreatableInsteadOfExplicitCodec() throws Exception {
        String orbSpike = "com.openggf.game.sonic1.objects.badniks.Sonic1OrbinautBadnikInstance"
                + "$OrbSpikeObjectInstance";
        Class<?> orbSpikeClass = Class.forName(orbSpike);

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(orbSpike),
                "OrbSpikeObjectInstance should restore via RewindRecreatable genericRecreate, "
                        + "not a Sonic1ObjectRegistry explicit codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(orbSpikeClass),
                "OrbSpikeObjectInstance must opt into the generic RewindRecreatable path");
    }

    @Test
    void falseFloorBlockUsesRewindRecreatableInsteadOfExplicitCodec() {
        String falseFloorBlock =
                "com.openggf.game.sonic1.objects.bosses.Sonic1FalseFloorInstance"
                        + "$FalseFloorBlock";

        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(falseFloorBlock),
                "FalseFloorBlock should restore via RewindRecreatable genericRecreate, "
                        + "not a Sonic1ObjectRegistry explicit codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(
                        com.openggf.game.sonic1.objects.bosses.Sonic1FalseFloorInstance
                                .FalseFloorBlock.class),
                "FalseFloorBlock must opt into the generic RewindRecreatable path");
    }
}
