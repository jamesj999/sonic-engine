package com.openggf.game.sonic3k.objects;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kPlcArtRegistry;
import com.openggf.game.sonic3k.S3kSpriteDataLoader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.data.RomByteReader;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Locked-on oracle for Obj_FBZElevator ($E2), $3CA1A-$3CB0C. */
class TestFbzElevator {
    private static final Set<Integer> SUBTYPES = Set.of(0x0F, 0x1E, 0x24, 0x25, 0x32, 0x37, 0x3B, 0x4B);

    @Test
    void controllerAllocatesAfterCurrentImmediatelyThenEvery96Frames() {
        ObjectManager manager = mock(ObjectManager.class);
        FbzElevatorObjectInstance elevator = elevator(0x24, 0, manager);
        elevator.update(0, null);
        assertEquals(0x5F, elevator.spawnTimer());

        ArgumentCaptor<ObjectInstance> cars = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(manager).addDynamicObjectAfterCurrent(cars.capture());
        FbzElevatorObjectInstance.Car car = assertInstanceOf(FbzElevatorObjectInstance.Car.class, cars.getValue());
        assertEquals(0x1000, car.getCentreX());
        assertEquals(0x700, car.getCentreY());
        assertEquals(0x24 * 8, car.travelTimer());
        assertEquals(-1, car.yVelocity());
        assertEquals(0, car.getSpawn().renderFlags(),
                "parent status bit 0 selects velocity but is not copied into child render_flags");
        assertTrue(car.requiresSameFrameUpdate(), "the higher slot executes later in the same ExecuteObjects pass");

        for (int i = 0; i < 95; i++) elevator.update(i + 1, null);
        verify(manager, times(1)).addDynamicObjectAfterCurrent(any());
        elevator.update(96, null);
        verify(manager, times(2)).addDynamicObjectAfterCurrent(any());
    }

    @Test
    void parentStatusBitZeroSelectsDownwardCarsAndAllPlacedSubtypesScaleDuration() {
        for (int subtype : SUBTYPES) {
            ObjectManager manager = mock(ObjectManager.class);
            FbzElevatorObjectInstance elevator = elevator(subtype, 1, manager);
            elevator.update(0, null);
            ArgumentCaptor<ObjectInstance> carCapture = ArgumentCaptor.forClass(ObjectInstance.class);
            verify(manager).addDynamicObjectAfterCurrent(carCapture.capture());
            FbzElevatorObjectInstance.Car car = assertInstanceOf(FbzElevatorObjectInstance.Car.class,
                    carCapture.getValue());
            assertEquals(subtype << 3, car.travelTimer(), "subtype $" + Integer.toHexString(subtype));
            assertEquals(1, car.yVelocity());
        }
    }

    @Test
    void carMovesOneIntegerPixelAfterPredecrementThenUses7f00ForPostRoutineCull() {
        FbzElevatorObjectInstance.Car car = new FbzElevatorObjectInstance.Car(0x1000, 0x700, 1, 1);
        car.update(0, null);
        assertEquals(0x701, car.getCentreY());
        assertEquals(0, car.travelTimer());
        car.update(1, null);
        assertEquals(0x7F00, car.getCentreX());
        assertEquals(0x702, car.getCentreY(), "ROM still performs the final y_pos add before Sprite_OnScreen_Test");
        assertFalse(car.isDestroyed(), "logical expiry is not unconditional deletion before Sprite_OnScreen_Test");
        assertTrue(car.checksOutOfRangeAfterRoutine());
        assertTrue(car.isCustomOutOfRange(0));
        assertFalse(car.isCustomOutOfRange(0x7F00), "Sprite_OnScreen_Test still draws if camera coarse-back contains $7F00");
    }

    @Test
    void carUsesExactSlopedSolidParametersAndTable() {
        FbzElevatorObjectInstance.Car car = new FbzElevatorObjectInstance.Car(0, 0, 8, -1);
        SlopedSolidProvider slope = car;
        assertEquals(0x3B, car.getSolidParams().halfWidth());
        assertEquals(0x10, car.getSolidParams().airHalfHeight());
        assertEquals(0x10, slope.getSlopeBaseline());
        assertTrue(slope.addsSlopeCatchRangeToVerticalOverlap(),
                "sub_1DD0E keeps caller d2=$10 in the new-contact vertical window");
        assertTrue(car.usesInclusiveRightEdge(),
                "loc_1DECE rejects only d0 > 2*d1");
        assertTrue(car.bypassesOffscreenSolidGate(),
                "sub_1DD24 reaches loc_1DECE without the render-flag gate");
        assertTrue(car.clearsStandingBitOnContinuedRideExit(null),
                "sub_1DD24 clears the participant standing bit on ride exit");
        assertEquals(60, slope.getSlopeData().length);
        assertEquals(0x10, slope.getSlopeData()[0]);
        assertEquals(0x21, slope.getSlopeData()[59]);
    }

    @Test
    @RequiresRom(SonicGame.SONIC_3K)
    void mappingAndLevelArtEntryAreExact() throws Exception {
        assertEquals(0x03CB0C, Sonic3kConstants.MAP_FBZ_ELEVATOR_ADDR);
        var entry = Sonic3kPlcArtRegistry.getPlan(4, 1).levelArt().stream()
                .filter(candidate -> Sonic3kObjectArtKeys.FBZ_ELEVATOR.equals(candidate.key()))
                .findFirst().orElseThrow();
        assertEquals(Sonic3kConstants.ARTTILE_FBZ_MISC2, entry.artTileBase());
        assertEquals(2, entry.palette());

        byte[] rom = Files.readAllBytes(com.openggf.tests.RomTestUtils.ensureSonic3kRomAvailable().toPath());
        var frames = S3kSpriteDataLoader.loadMappingFrames(new RomByteReader(rom),
                Sonic3kConstants.MAP_FBZ_ELEVATOR_ADDR);
        assertEquals(1, frames.size());
        assertEquals(7, frames.getFirst().pieces().size());
    }

    private static FbzElevatorObjectInstance elevator(int subtype, int renderFlags, ObjectManager manager) {
        FbzElevatorObjectInstance elevator = new FbzElevatorObjectInstance(new ObjectSpawn(
                0x1000, 0x700, Sonic3kObjectIds.FBZ_ELEVATOR, subtype, renderFlags, false, 0));
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(manager);
        elevator.setServices(services);
        return elevator;
    }
}
