package com.openggf.game.sonic1.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.sonic1.objects.badniks.Sonic1BombFuseInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1CaterkillerBodyInstance;
import com.openggf.game.sonic1.objects.bosses.GHZBossWreckingBall;
import com.openggf.game.sonic1.objects.bosses.Sonic1SLZBossSpikeball;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the old batch-2 S1 objects stay on their current generic
 * recreate paths and do not regain explicit dynamic codecs.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip is handled by the rewind
 * coverage guard.
 *
 * <p><b>Intentionally absent:</b> {@code SYZBossSpike} is construction-spawned
 * (inside {@code Sonic1SYZBossInstance.initializeBossState()} → {@code spawnSpikeChild()}).
 * Registering a codec would double it on rewind restore (1 → 2). Re-established by
 * boss reconstruction. See {@code TestBossChildNoDoubleSpawnParity}.
 *
 * <p>{@code Sonic1MotobugSmokeInstance} is intentionally excluded: it is an
 * accept-drop transient cosmetic (re-emitted in-frame by its parent), documented
 * in {@code docs/status/known-discrepancies.md}.
 */
class TestRewindFixS1Batch2Codecs {

    @Test
    void batch2S1ObjectsUseGenericRecreateWithoutExplicitCodecs() {
        // SYZBossSpike intentionally absent: construction-spawned child.
        // Adding a codec would double it on restore. See TestBossChildNoDoubleSpawnParity.

        List<String> genericRecreate = List.of(
                "com.openggf.game.sonic1.objects.badniks.Sonic1BombShrapnelInstance",
                "com.openggf.game.sonic1.objects.badniks.Sonic1CannonballInstance",
                "com.openggf.game.sonic1.objects.badniks.Sonic1NewtronMissileInstance",
                "com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileInstance",
                "com.openggf.game.sonic1.objects.badniks.Sonic1BuzzBomberMissileDissolveInstance",
                "com.openggf.game.sonic1.objects.badniks.Sonic1CrabmeatProjectileInstance",
                Sonic1SLZBossSpikeball.class.getName());
        for (String name : genericRecreate) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(name),
                    name + " must restore through RewindRecreatable generic recreate, not a batch-2 codec");
        }

        List<Class<?>> graphRecreate = List.of(
                GHZBossWreckingBall.class,
                Sonic1BombFuseInstance.class,
                Sonic1CaterkillerBodyInstance.class,
                Sonic1SLZBossSpikeball.class);
        for (Class<?> type : graphRecreate) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getName() + " must restore through graph-tested RewindRecreatable, "
                            + "not a batch-2 codec");
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getName() + " must opt into RewindRecreatable graph restore");
        }
    }
}
