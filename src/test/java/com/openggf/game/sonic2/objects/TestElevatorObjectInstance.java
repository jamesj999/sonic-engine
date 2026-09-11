package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestElevatorObjectInstance {

    @Test
    void cnzElevatorUsesRomPlatformD3AsRideSurfaceOffset() {
        ElevatorObjectInstance elevator = new ElevatorObjectInstance(
                new ObjectSpawn(0x0D30, 0x0548, Sonic2ObjectIds.CNZ_ELEVATOR, 0, 0, false, 0),
                "Elevator");

        SolidObjectParams params = elevator.getSolidParams();

        assertEquals(0x10, params.halfWidth());
        assertEquals(9, params.airHalfHeight());
        assertEquals(9, params.groundHalfHeight());
    }

    @Test
    void cnzElevatorSuppressesThreeInitialRidingHorizontalInputFrames() {
        ElevatorObjectInstance elevator = new ElevatorObjectInstance(
                new ObjectSpawn(0x1530, 0x0188, Sonic2ObjectIds.CNZ_ELEVATOR, 0, 0, false, 0),
                "Elevator");

        assertEquals(3, elevator.staleHorizontalLogicalInputFramesWhileRiding(null, 1));
    }

    @Test
    void cnzElevatorKeepsStaleInputEdgeAcrossMidRideInertiaReset() {
        ElevatorObjectInstance elevator = new ElevatorObjectInstance(
                new ObjectSpawn(0x20B0, 0x0398, Sonic2ObjectIds.CNZ_ELEVATOR, 0, 0, false, 0),
                "Elevator");

        assertTrue(elevator.preservesStaleHorizontalInputEdgeWhileMoving(null));
    }

    @Test
    void cnzElevatorSkipsNewContactWhenPlayerAlreadyRidesAnotherObject() {
        ElevatorObjectInstance elevator = new ElevatorObjectInstance(
                new ObjectSpawn(0x20B0, 0x0398, Sonic2ObjectIds.CNZ_ELEVATOR, 0, 0, false, 0),
                "Elevator");

        assertTrue(elevator.skipsNewContactWhilePlayerAlreadyOnObject(null));
    }

}
