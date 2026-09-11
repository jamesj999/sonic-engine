package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.Sonic1PointsObjectInstance;
import com.openggf.game.sonic2.objects.PointsObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kPointsObjectInstance;
import com.openggf.graphics.GraphicsManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class TestRewindRoundTripHarnessConstruction {

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @ParameterizedTest
    @ValueSource(classes = {
            Sonic1PointsObjectInstance.class,
            PointsObjectInstance.class,
            Sonic3kPointsObjectInstance.class
    })
    void pointsObjectsWithServicesAndScoreConstructorRoundTrip(Class<?> objectClass) {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(objectClass.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                objectClass.getName() + " must be probeable before its points codec can be deleted");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.openggf.game.sonic2.objects.bosses.HTZBossFlamethrower",
            "com.openggf.game.sonic2.objects.bosses.HTZBossLavaBall",
            "com.openggf.game.sonic3k.objects.AizFallingLogObjectInstance$SplashChild",
            "com.openggf.game.sonic3k.objects.badniks.DragonflyBadnikInstance$LinkedBodyChild",
            "com.openggf.game.sonic3k.objects.badniks.SpikerBadnikInstance$SpikerTopSpikeChild",
            "com.openggf.game.sonic3k.objects.badniks.TurboSpikerBadnikInstance$TurboSpikerShellChild",
            "com.openggf.game.sonic3k.objects.CutsceneKnucklesMhz2Instance$Mhz2KnucklesRouteSwitchChild",
            "com.openggf.game.sonic3k.objects.Mhz1CutsceneDoorInstance",
            "com.openggf.game.sonic3k.objects.Mhz1CutsceneKnucklesInstance$Mhz1CutscenePlayerTwoStopper",
            "com.openggf.game.sonic3k.objects.MhzMinibossFlameInstance",
            "com.openggf.game.sonic3k.objects.Sonic3kSSEntryFlashObjectInstance"
    })
    void parentLinkedOtherFailureTailIsSeededByHarness(String className) {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(className);

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                className + " should round-trip when the harness seeds its required parent graph: "
                        + result);
    }
}
