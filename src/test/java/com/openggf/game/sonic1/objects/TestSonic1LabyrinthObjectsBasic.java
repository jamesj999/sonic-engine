package com.openggf.game.sonic1.objects;

import com.openggf.game.sonic1.objects.badniks.Sonic1BurrobotBadnikInstance;
import com.openggf.game.sonic1.objects.badniks.Sonic1OrbinautBadnikInstance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1LabyrinthObjectsBasic {

    @Test
    public void registryNamesIncludeNewObjects() {
        Sonic1ObjectRegistry registry = new Sonic1ObjectRegistry();
        assertEquals("FlappingDoor", registry.getPrimaryName(Sonic1ObjectIds.FLAPPING_DOOR));
        assertEquals("Burrobot", registry.getPrimaryName(Sonic1ObjectIds.BURROBOT));
        assertEquals("Orbinaut", registry.getPrimaryName(Sonic1ObjectIds.ORBINAUT));
        assertEquals("Waterfall", registry.getPrimaryName(Sonic1ObjectIds.WATERFALL));
    }

    @Test
    public void flappingDoorSolidityDependsOnPlayerSideWhenClosed() throws Exception {
        Sonic1FlappingDoorObjectInstance door = new Sonic1FlappingDoorObjectInstance(
                new ObjectSpawn(200, 128, Sonic1ObjectIds.FLAPPING_DOOR, 1, 0, false, 0));
        door.setServices(new TestObjectServices());
        TestPlayableSprite player = new TestPlayableSprite();

        setPrivateInt(door, "flapWait", 999);
        setPrivateInt(door, "mappingFrame", 0);

        player.setCentreX((short) 150);
        player.setCentreY((short) 128);
        door.update(1, player);
        assertTrue(door.isSolidFor(player));

        player.setCentreX((short) 220);
        door.update(2, player);
        assertFalse(door.isSolidFor(player));
    }

    @Test
    public void flappingDoorClosingAnimationHoldsFullyClosedFrame() throws Exception {
        Sonic1FlappingDoorObjectInstance door = new Sonic1FlappingDoorObjectInstance(
                new ObjectSpawn(200, 128, Sonic1ObjectIds.FLAPPING_DOOR, 1, 0, false, 0));
        door.setServices(new TestObjectServices());

        // Keep flap toggle inactive so we only validate animation script looping.
        setPrivateInt(door, "flapWait", 999);
        setPrivateInt(door, "animationId", 1); // Closing script: 2,1,0,afBack,1
        setPrivateInt(door, "animationFrameIndex", 0);
        setPrivateInt(door, "animationTimer", 0);

        // Run long enough to reach and hold fully-closed frame.
        for (int i = 0; i < 16; i++) {
            door.update(i + 1, null);
        }
        assertEquals(0, getPrivateInt(door, "mappingFrame"), "Closing script should hold on frame 0 after completion");

        // Continue running; frame should remain 0 (no 1<->0 flicker).
        for (int i = 0; i < 16; i++) {
            door.update(100 + i, null);
        }
        assertEquals(0, getPrivateInt(door, "mappingFrame"), "Closing script should continue holding frame 0");
    }

    @Test
    public void waterfallSubtypeNineUsesSplashPriorityAndSurvivesUpdates() {
        Sonic1WaterfallObjectInstance waterfall = new Sonic1WaterfallObjectInstance(
                new ObjectSpawn(100, 100, Sonic1ObjectIds.WATERFALL, 0x89, 0, false, 0));

        assertTrue(waterfall.isHighPriority());
        assertEquals(RenderPriority.MIN, waterfall.getPriorityBucket());

        waterfall.update(1, null);
        waterfall.update(2, null);
        assertFalse(waterfall.isDestroyed());
    }

    @Test
    public void burrobotAndOrbinautCanUpdateWithoutPlayerOrLevelManager() {
        Sonic1BurrobotBadnikInstance burrobot = new Sonic1BurrobotBadnikInstance(
                new ObjectSpawn(256, 256, Sonic1ObjectIds.BURROBOT, 0, 0, false, 0));
        burrobot.setServices(new TestObjectServices());
        Sonic1OrbinautBadnikInstance orbinaut = new Sonic1OrbinautBadnikInstance(
                new ObjectSpawn(512, 192, Sonic1ObjectIds.ORBINAUT, 0, 0, false, 0));
        orbinaut.setServices(new TestObjectServices());

        burrobot.update(1, null);
        burrobot.update(2, null);
        orbinaut.update(1, null);
        orbinaut.update(2, null);

        Assertions.assertEquals(0x05, burrobot.getCollisionFlags());
        Assertions.assertEquals(0x0B, orbinaut.getCollisionFlags());
    }

    @Test
    public void burrobotJumpsWhenSonicIsAboveWithinRange() throws Exception {
        Sonic1BurrobotBadnikInstance burrobot = new Sonic1BurrobotBadnikInstance(
                new ObjectSpawn(0x037F, 0x00AC, Sonic1ObjectIds.BURROBOT, 0, 0, true, 0));
        burrobot.setServices(new TestObjectServices());

        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) 0x0365);
        player.setCentreY((short) 0x0091);

        burrobot.update(1, player);

        assertEquals(2, getPrivateInt(burrobot, "state"),
                "Burro_ChkSonic should enter Burro_Jump when Sonic is within $60 X and -$80..-1 Y");
        assertEquals(-0x80, getPrivateInt(burrobot, "xVelocity"),
                "Burro_ChkSonic2 should face and jump toward Sonic");
        assertEquals(-0x400, getPrivateInt(burrobot, "yVelocity"),
                "Burro_ChkSonic stores -$400 in obVelY before the next Burro_Jump frame");

        burrobot.update(2, player);

        assertEquals(0x00A8, burrobot.getY(),
                "Burro_Jump should apply SpeedToPos before adding $18 gravity");
        assertEquals(-0x3E8, getPrivateInt(burrobot, "yVelocity"),
                "Burro_Jump should add $18 to obVelY after moving");
    }

    @Test
    public void burrobotMoveUsesBchgOldBitForFirstAheadFloorProbe() throws Exception {
        Sonic1BurrobotBadnikInstance burrobot = new Sonic1BurrobotBadnikInstance(
                new ObjectSpawn(0x0951, 0x04EC, Sonic1ObjectIds.BURROBOT, 0, 0, false, 0));
        burrobot.setServices(new TestObjectServices());

        setPrivateInt(burrobot, "state", 1);
        setPrivateInt(burrobot, "stateTimer", 255);
        setPrivateInt(burrobot, "xVelocity", 0x80);
        setPrivateBoolean(burrobot, "floorProbeToggle", false);

        burrobot.update(0xC9FB, null);

        assertEquals(2, getPrivateInt(burrobot, "state"),
                "Burro_Move bchg #0,objoff_32 branches on the old bit; old 0 should run ObjFloorDist2 immediately");
        assertEquals(-0x400, getPrivateInt(burrobot, "yVelocity"),
                "With v_vblank_byte bit 2 clear, Burro_Move should enter Burro_Jump");
    }

    @Test
    public void orbinautParentMovesOnFirstFrameAfterLastSatelliteLaunches() throws Exception {
        Sonic1OrbinautBadnikInstance orbinaut = new Sonic1OrbinautBadnikInstance(
                new ObjectSpawn(0x0C93, 0x05F8, Sonic1ObjectIds.ORBINAUT, 0, 0, false, 0));
        orbinaut.setServices(new TestObjectServices());

        setPrivateBoolean(orbinaut, "initialized", true);
        setPrivateInt(orbinaut, "routine", 2);
        setPrivateInt(orbinaut, "activeSpikes", 0);

        orbinaut.update(1, null);

        assertEquals(4, getPrivateInt(orbinaut, "routine"),
                "Obj60 child launch promotes the parent to Orb_Display before its next execution");
        assertEquals(0x0C92, orbinaut.getX(),
                "Orb_Display should run SpeedToPos on the first parent frame after the final satellite fires");
    }

    private static void setPrivateInt(Object target, String fieldName, int value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setPrivateBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static int getPrivateInt(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

}


