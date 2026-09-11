package com.openggf.game.rewind;

import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic1.objects.Sonic1BigSpikedBallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1BridgeObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1BreakableWallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1BubblesObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ButtonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ChainedStomperObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1CollapsingLedgeObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ConveyorBeltObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1EdgeWallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ElectrocuterObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1ElevatorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1EndingSTHObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1FanObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1FlamethrowerObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1GargoyleObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1GiantRingObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1GirderBlockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1HarpoonObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1HiddenBonusObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LabyrinthBlockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LavaBallMakerObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LamppostObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LavaBallObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LavaTagObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1LZConveyorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1MonitorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1MovingBlockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1MzBrickObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1PlatformObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1PoleThatBreaksObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1PushBlockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SawObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SceneryObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SignpostObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SmashBlockObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SmallDoorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SpinConveyorObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SpikedPoleHelixObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SpikeObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SpinPlatformObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SpringObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1StaircaseObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1SwingingPlatformObjectInstance;
import com.openggf.game.sonic1.objects.Sonic1VanishingPlatformObjectInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS1SimpleObjectGenericRecreate {

    @ParameterizedTest(name = "{0} round-trips via generic recreate")
    @ValueSource(classes = {
            Sonic1BigSpikedBallObjectInstance.class,
            Sonic1BridgeObjectInstance.class,
            Sonic1BreakableWallObjectInstance.class,
            Sonic1BubblesObjectInstance.class,
            Sonic1ButtonObjectInstance.class,
            Sonic1ChainedStomperObjectInstance.class,
            Sonic1CollapsingLedgeObjectInstance.class,
            Sonic1ConveyorBeltObjectInstance.class,
            Sonic1EdgeWallObjectInstance.class,
            Sonic1ElectrocuterObjectInstance.class,
            Sonic1ElevatorObjectInstance.class,
            Sonic1EndingSTHObjectInstance.class,
            Sonic1FanObjectInstance.class,
            Sonic1FlamethrowerObjectInstance.class,
            Sonic1GargoyleObjectInstance.class,
            Sonic1GiantRingObjectInstance.class,
            Sonic1GirderBlockObjectInstance.class,
            Sonic1HarpoonObjectInstance.class,
            Sonic1HiddenBonusObjectInstance.class,
            Sonic1LabyrinthBlockObjectInstance.class,
            Sonic1LavaBallMakerObjectInstance.class,
            Sonic1LamppostObjectInstance.class,
            Sonic1LavaBallObjectInstance.class,
            Sonic1LavaTagObjectInstance.class,
            Sonic1LZConveyorObjectInstance.class,
            Sonic1MonitorObjectInstance.class,
            Sonic1MovingBlockObjectInstance.class,
            Sonic1MzBrickObjectInstance.class,
            Sonic1PlatformObjectInstance.class,
            Sonic1PoleThatBreaksObjectInstance.class,
            Sonic1PushBlockObjectInstance.class,
            Sonic1SawObjectInstance.class,
            Sonic1SceneryObjectInstance.class,
            Sonic1SignpostObjectInstance.class,
            Sonic1SmashBlockObjectInstance.class,
            Sonic1SmallDoorObjectInstance.class,
            Sonic1SpinConveyorObjectInstance.class,
            Sonic1SpikedPoleHelixObjectInstance.class,
            Sonic1SpikeObjectInstance.class,
            Sonic1SpinPlatformObjectInstance.class,
            Sonic1SpringObjectInstance.class,
            Sonic1StaircaseObjectInstance.class,
            Sonic1SwingingPlatformObjectInstance.class,
            Sonic1VanishingPlatformObjectInstance.class
    })
    void s1SimpleObjectsRoundTripThroughGenericRecreate(Class<?> objectClass) {
        RoundTripSweepResult result = RewindRoundTripHarness.probeClass(objectClass.getName());

        assertInstanceOf(RoundTripSweepResult.Passed.class, result,
                objectClass.getName() + " should round-trip via RewindRecreatable generic recreate");
    }

    @Test
    void spawnConstructibleS1ObjectsUseSharedRecreateMarker() {
        Class<?> marker = assertDoesNotThrow(
                () -> Class.forName("com.openggf.level.objects.SpawnRewindRecreatable"));
        Class<?>[] objectClasses = {
                Sonic1BigSpikedBallObjectInstance.class,
                Sonic1BridgeObjectInstance.class,
                Sonic1BreakableWallObjectInstance.class,
                Sonic1BubblesObjectInstance.class,
                Sonic1ButtonObjectInstance.class,
                Sonic1ChainedStomperObjectInstance.class,
                Sonic1CollapsingLedgeObjectInstance.class,
                Sonic1ConveyorBeltObjectInstance.class,
                Sonic1EdgeWallObjectInstance.class,
                Sonic1ElectrocuterObjectInstance.class,
                Sonic1ElevatorObjectInstance.class,
                Sonic1FanObjectInstance.class,
                Sonic1FlamethrowerObjectInstance.class,
                Sonic1GargoyleObjectInstance.class,
                Sonic1GiantRingObjectInstance.class,
                Sonic1GirderBlockObjectInstance.class,
                Sonic1HarpoonObjectInstance.class,
                Sonic1HiddenBonusObjectInstance.class,
                Sonic1LabyrinthBlockObjectInstance.class,
                Sonic1LavaBallMakerObjectInstance.class,
                Sonic1LamppostObjectInstance.class,
                Sonic1LavaBallObjectInstance.class,
                Sonic1LavaTagObjectInstance.class,
                Sonic1LZConveyorObjectInstance.class,
                Sonic1MonitorObjectInstance.class,
                Sonic1MovingBlockObjectInstance.class,
                Sonic1MzBrickObjectInstance.class,
                Sonic1PlatformObjectInstance.class,
                Sonic1PoleThatBreaksObjectInstance.class,
                Sonic1PushBlockObjectInstance.class,
                Sonic1SawObjectInstance.class,
                Sonic1SceneryObjectInstance.class,
                Sonic1SignpostObjectInstance.class,
                Sonic1SmashBlockObjectInstance.class,
                Sonic1SmallDoorObjectInstance.class,
                Sonic1SpinConveyorObjectInstance.class,
                Sonic1SpikedPoleHelixObjectInstance.class,
                Sonic1SpikeObjectInstance.class,
                Sonic1SpinPlatformObjectInstance.class,
                Sonic1SpringObjectInstance.class,
                Sonic1StaircaseObjectInstance.class,
                Sonic1SwingingPlatformObjectInstance.class,
                Sonic1VanishingPlatformObjectInstance.class
        };

        for (Class<?> objectClass : objectClasses) {
            assertTrue(marker.isAssignableFrom(objectClass),
                    objectClass.getName() + " should opt into shared spawn-based recreate");
            assertTrue(hasNoDeclaredRecreateForRewind(objectClass),
                    objectClass.getName() + " should inherit the shared recreate implementation");
        }
    }

    private static boolean hasNoDeclaredRecreateForRewind(Class<?> objectClass) {
        for (var method : objectClass.getDeclaredMethods()) {
            if (method.getName().equals("recreateForRewind")) {
                return false;
            }
        }
        return true;
    }
}
