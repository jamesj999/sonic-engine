package com.openggf.game.sonic1.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.RewindRoundTripHarness;
import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.bosses.FZCylinder;
import com.openggf.game.sonic1.objects.bosses.FZPlasmaBall;
import com.openggf.game.sonic1.objects.bosses.FZPlasmaLauncher;
import com.openggf.game.sonic1.objects.bosses.Sonic1BossBlockInstance;
import com.openggf.level.objects.RewindRecreatable;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every old batch-3 S1 object that was previously dropped on a
 * held-rewind restore now exposes a generic recreate path.
 *
 * <p>Most assertions are metadata checks that read class opt-ins without a ROM
 * or active gameplay session. Codec deletions that now rely on generic recreate
 * also run the headless ObjectManager round-trip harness here.
 *
 * <p>{@code Sonic1SplashObjectInstance} is intentionally excluded: it is an
 * accept-drop transient cosmetic (LZ water splash re-emitted on water
 * entry/exit), documented in {@code docs/status/known-discrepancies.md}.
 */
class TestRewindFixS1Batch3Codecs {

    @Test
    void hasRecreatePathForBatch3S1Objects() {
        List<String> required = List.of(
                Sonic1BossBlockInstance.class.getName(),
                Sonic1CollapsingFloorObjectInstance.class.getName(),
                Sonic1ExplosionItemObjectInstance.class.getName(),
                Sonic1GrassFireObjectInstance.class.getName(),
                Sonic1LamppostTwirlInstance.class.getName(),
                Sonic1MonitorPowerUpObjectInstance.class.getName(),
                Sonic1RingInstance.class.getName(),
                Sonic1SeesawBallObjectInstance.class.getName(),
                Sonic1SpikedBallChainObjectInstance.class.getName());

        for (String name : required) {
            assertTrue(implementsRewindRecreatable(name),
                    "missing rewind recreate path for " + name);
        }
    }

    @Test
    void fzBossChildrenUseGenericRecreatablePathInsteadOfRegisteredCodecs() {
        for (Class<?> type : List.of(FZCylinder.class, FZPlasmaLauncher.class, FZPlasmaBall.class)) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(type.getName()),
                    type.getSimpleName() + " must restore through RewindRecreatable generic recreate, "
                            + "not an explicit S1 dynamic codec");
            assertTrue(RewindRecreatable.class.isAssignableFrom(type),
                    type.getSimpleName() + " must opt into the generic RewindRecreatable path");
        }
    }

    @Test
    void syzBossBlockUsesGenericRecreateInsteadOfRegisteredCodec() {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1BossBlockInstance.class.getName()),
                "Sonic1BossBlockInstance must restore through RewindRecreatable generic recreate, "
                        + "not an explicit S1 dynamic codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1BossBlockInstance.class),
                "Sonic1BossBlockInstance must opt into the generic RewindRecreatable path");
    }

    @Test
    void eggPrisonBodyUsesGenericRecreateInsteadOfRegisteredCodec() {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1EggPrisonObjectInstance.class.getName()),
                "Sonic1EggPrisonObjectInstance must restore through RewindRecreatable generic recreate, "
                        + "not an explicit S1 dynamic codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1EggPrisonObjectInstance.class),
                "Sonic1EggPrisonObjectInstance must opt into the generic RewindRecreatable path");

        RoundTripSweepResult result =
                RewindRoundTripHarness.probeClass(Sonic1EggPrisonObjectInstance.class.getName());
        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                "Sonic1EggPrisonObjectInstance must round-trip through the generic recreate path; got: "
                        + result);
    }

    @Test
    void ringFlashUsesGenericRecreateInsteadOfRegisteredCodec() {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(Sonic1RingFlashObjectInstance.class.getName()),
                "Sonic1RingFlashObjectInstance must restore through RewindRecreatable generic recreate, "
                        + "not an explicit S1 dynamic codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(Sonic1RingFlashObjectInstance.class),
                "Sonic1RingFlashObjectInstance must opt into the generic RewindRecreatable path");
    }

    private static boolean implementsRewindRecreatable(String className) {
        try {
            return RewindRecreatable.class.isAssignableFrom(Class.forName(className));
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
