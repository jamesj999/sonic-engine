package com.openggf.game.sonic3k.objects;

import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that every batch-inner2 nested-class child that was previously
 * dropped on a held-rewind restore now exposes the generic
 * {@code RewindRecreatable} path.
 *
 * <p>These are static/non-static nested children (MGZ miniboss spire/arm, gumball
 * exit trigger, MGZ stone chip, MHZ1/MHZ2 cutscene helpers, HCZ miniboss rocket
 * touch hitbox, ICZ ice-spike hurt child) keyed by their JVM binary name
 * ({@code Outer$Inner}). Each recreate hook either relinks the live parent
 * recreated earlier in the restore or re-runs the child's constructor from the
 * captured spawn; non-spawn differentiator scalars were un-finaled so the
 * generic field capturer reapplies them after recreate.
 *
 * <p>Pure metadata test: it reads class opt-ins without a ROM, OpenGL, or an
 * active gameplay session. Full session round-trip coverage is enforced by the
 * rewind coverage guard ({@code TestRewindCoverageGuard}).
 */
class TestRewindFixS3KInnerBatch2Codecs {

    @Test
    void batchInner2S3KChildrenHaveGenericRecreatePaths() {
        List<String> required = List.of();

        for (String name : required) {
            assertTrue(com.openggf.level.objects.RewindRecreatable.class.isAssignableFrom(loadClass(name)),
                    "missing rewind recreate codec for " + name);
        }
    }

    @Test
    void migratedInner2ChildrenUseGenericRecreateInsteadOfExplicitCodecs() throws Exception {
        List<String> genericRecreateClasses = List.of(
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$CeilingSpireChild",
                "com.openggf.game.sonic3k.objects.GumballMachineObjectInstance$ExitTriggerChild",
                "com.openggf.game.sonic3k.objects.MGZHeadTriggerObjectInstance$HeadTriggerStoneChipChild",
                "com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance$Mhz1CutscenePlayerTwoStopper",
                "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesRouteSwitchChild",
                "com.openggf.game.sonic3k.objects.HczMinibossInstance$RocketTouchChild",
                "com.openggf.game.sonic3k.objects.MgzMinibossInstance$DrillArmChild",
                "com.openggf.game.sonic3k.objects.IczIceSpikesObjectInstance$SpikeHurtChild");

        for (String className : genericRecreateClasses) {
            assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(className),
                    className + " must restore through RewindRecreatable generic recreate");
            assertTrue(com.openggf.level.objects.RewindRecreatable.class.isAssignableFrom(loadClass(className)),
                    className + " must opt into generic recreate");
        }
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError(e);
        }
    }
}
