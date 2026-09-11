package com.openggf.game.sonic1.objects;

import com.openggf.camera.Camera;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.Sonic1FloatingBlockState;
import com.openggf.game.sonic1.Sonic1ZoneFeatureProvider;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.ObjectSpawn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1FloatingBlockObjectInstance {

    private static final int ORIG_X = 0x600;
    private static final int ORIG_Y = 0x1A0;

    private ObjectServices testServices;
    private Sonic1SwitchManager switchManager;
    private Sonic1FloatingBlockState floatingBlockState;

    @BeforeEach
    public void resetSwitchState() {
        switchManager = new Sonic1SwitchManager();
        floatingBlockState = new Sonic1FloatingBlockState();
        switchManager.resetState();
        testServices = new StubObjectServices() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T gameService(Class<T> type) {
                if (type == Sonic1SwitchManager.class) return (T) switchManager;
                if (type == Sonic1FloatingBlockState.class) return (T) floatingBlockState;
                return null;
            }
        };
    }

    @Test
    public void syz3DestinationProxyDeletesUntilRealBlockCompletesTrip() {
        Sonic1FloatingBlockObjectInstance proxy = createSyz3TunnelBlock(0x1F38);

        proxy.update(1, null);

        assertTrue(proxy.isDestroyed(),
                "REV01 deletes the fake $1F38 block while f_obj56 is clear");
    }

    @Test
    public void syz3DestinationProxyRemainsStationaryAfterRealBlockCompletesTrip() {
        floatingBlockState.markTunnelBlockAtDestination();
        Sonic1FloatingBlockObjectInstance proxy = createSyz3TunnelBlock(0x1F38);

        proxy.update(1, null);

        assertFalse(proxy.isDestroyed());
        assertEquals(0x1F38, proxy.getX(),
                "REV01 clears the proxy subtype to stationary");
    }

    @Test
    public void syz3RealBlockSetsDestinationFlagAfterMovingThreeEightyPixels() {
        switchManager.setBit(0x0F, 0);
        Sonic1FloatingBlockObjectInstance real = createSyz3TunnelBlock(0x1BB8);

        for (int frame = 1; frame <= 0x380; frame++) {
            real.update(frame, null);
        }

        assertEquals(0x1F38, real.getX());
        assertTrue(floatingBlockState.isTunnelBlockAtDestination());
    }

    @Test
    public void verticalDoorDoesNotStartOpenJustBecauseItIsRespawnTracked() {
        Sonic1FloatingBlockObjectInstance door = createLzDoor(0xE0, true);

        door.update(1, null);

        // LZ vertical door: halfHeight=0x20, initial fb_height=0x40, y=origY+0x40 when closed.
        assertEquals(ORIG_Y + 0x40, door.getY());
    }

    @Test
    public void verticalDoorType05ChecksOnlySwitchBit0() {
        Sonic1FloatingBlockObjectInstance door = createLzDoor(0xE0, true);
        Sonic1SwitchManager switches = switchManager;

        // Set bit 7 only: ROM type 05 uses btst #0, so this must not open the door.
        switches.setBit(0, 7);
        door.update(1, null);
        assertEquals(ORIG_Y + 0x40, door.getY());

        // Set bit 0: door starts opening by 2 px this frame.
        switches.setBit(0, 0);
        door.update(2, null);
        assertEquals(ORIG_Y + 0x3E, door.getY());
    }

    @Test
    public void horizontalDoorType0CChecksOnlySwitchBit0() {
        Sonic1FloatingBlockObjectInstance door = createLzDoor(0xF0, true);
        Sonic1SwitchManager switches = switchManager;

        // LZ large horizontal door starts at x=origX+0x80 when closed.
        door.update(1, null);
        assertEquals(ORIG_X + 0x80, door.getX());

        // Bit 7 alone must not activate type 0C.
        switches.setBit(0, 7);
        door.update(2, null);
        assertEquals(ORIG_X + 0x80, door.getX());

        // Bit 0 activates type 0C; door moves left by 2.
        switches.setBit(0, 0);
        door.update(3, null);
        assertEquals(ORIG_X + 0x7E, door.getX());
        assertTrue(door.getX() < ORIG_X + 0x80);
    }

    @Test
    public void balanceUsesRomActiveWidthWithoutSolidObjectPadding() {
        ObjectSpawn spawn = new ObjectSpawn(
                ORIG_X,
                ORIG_Y,
                Sonic1ObjectIds.FLOATING_BLOCK,
                0x10,
                0,
                false,
                0
        );
        Sonic1FloatingBlockObjectInstance block = new Sonic1FloatingBlockObjectInstance(
                spawn, Sonic1Constants.ZONE_SYZ);

        assertEquals(0x20, block.getBalanceWidthPixels(),
                "Sonic_Move reads Obj56 obActWid from FBlock_Var");
        assertEquals(0x2B, block.getSolidParams().halfWidth(),
                "FBlock_Solid adds $B only to the SolidObject collision width");
        assertTrue(block.getSolidRoutineProfile().inclusiveRightEdge(),
                "FBlock_Solid retains SolidObject's inclusive right edge");
        assertTrue(block.usesInstanceSolidStateLatchKey(),
                "Obj56 status bits belong to its live SST while its dynamic spawn moves");
    }

    @Test
    public void lz1SwitchThreeDoorDisablesWindTunnelWhilePlayerIsLeftOfClosedDoor() throws java.io.IOException {
        // ROM: "56 SYZ, SLZ Floating Blocks and LZ Doors.asm" type05 (lines 219-243) -
        // for the LZ1 switch-3 door specifically, f_wtunnelallow is cleared every
        // frame, then re-disabled while the door hasn't been triggered and Sonic
        // is still to the left of it. Without this the LZ1 water current pushes
        // Sonic into the closed door and never lets go (softlock).
        Sonic1ZoneFeatureProvider zoneFeatures = new Sonic1ZoneFeatureProvider();
        zoneFeatures.initZoneFeatures(null, Sonic1Constants.ZONE_LZ, 0, 0);

        Camera camera = new Camera();
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) (ORIG_X - 0x40)); // left of the door
        player.setCentreY((short) ORIG_Y);
        camera.setFocusedSprite(player);

        Sonic1FloatingBlockObjectInstance door = createLz1SwitchThreeDoor(zoneFeatures, camera);

        door.update(1, player);

        assertTrue(zoneFeatures.isWindTunnelDisabled(),
                "Water current must stay disabled while the LZ1 door is closed and Sonic hasn't passed it");
    }

    @Test
    public void lz1SwitchThreeDoorReenablesWindTunnelOnceSwitchIsPressed() throws java.io.IOException {
        Sonic1ZoneFeatureProvider zoneFeatures = new Sonic1ZoneFeatureProvider();
        zoneFeatures.initZoneFeatures(null, Sonic1Constants.ZONE_LZ, 0, 0);

        Camera camera = new Camera();
        TestPlayableSprite player = new TestPlayableSprite();
        player.setCentreX((short) (ORIG_X - 0x40)); // still left of the door
        player.setCentreY((short) ORIG_Y);
        camera.setFocusedSprite(player);

        Sonic1FloatingBlockObjectInstance door = createLz1SwitchThreeDoor(zoneFeatures, camera);

        door.update(1, player);
        assertTrue(zoneFeatures.isWindTunnelDisabled());

        // Pressing switch 3 begins opening the door and, per ROM, unconditionally
        // re-enables the current the same frame (Step B overrides Step A).
        switchManager.setBit(3, 0);
        door.update(2, player);

        assertFalse(zoneFeatures.isWindTunnelDisabled(),
                "Once the door starts opening, the water current must be allowed again");
    }

    private Sonic1FloatingBlockObjectInstance createLz1SwitchThreeDoor(
            Sonic1ZoneFeatureProvider zoneFeatures, Camera camera) {
        // subtype 0xE3: bit7 set (switch-activated door), high nybble 0xE ->
        // varIndex 6 (LZ small vertical door, stays type05), low nybble 3 -> fb_type=3.
        ObjectSpawn spawn = new ObjectSpawn(
                ORIG_X,
                ORIG_Y,
                Sonic1ObjectIds.FLOATING_BLOCK,
                0xE3,
                0,
                false,
                0
        );
        Sonic1FloatingBlockObjectInstance door = new Sonic1FloatingBlockObjectInstance(spawn, Sonic1Constants.ZONE_LZ);
        ObjectServices services = new StubObjectServices() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T gameService(Class<T> type) {
                if (type == Sonic1SwitchManager.class) return (T) switchManager;
                return null;
            }

            @Override
            public ZoneFeatureProvider zoneFeatureProvider() {
                return zoneFeatures;
            }

            @Override
            public int currentAct() {
                return 0;
            }

            @Override
            public Camera camera() {
                return camera;
            }
        };
        door.setServices(services);
        return door;
    }

    private Sonic1FloatingBlockObjectInstance createLzDoor(int subtype, boolean respawnTracked) {
        ObjectSpawn spawn = new ObjectSpawn(
                ORIG_X,
                ORIG_Y,
                Sonic1ObjectIds.FLOATING_BLOCK,
                subtype,
                0,
                respawnTracked,
                0
        );
        Sonic1FloatingBlockObjectInstance door = new Sonic1FloatingBlockObjectInstance(spawn, Sonic1Constants.ZONE_LZ);
        door.setServices(testServices);
        return door;
    }

    private Sonic1FloatingBlockObjectInstance createSyz3TunnelBlock(int x) {
        ObjectSpawn spawn = new ObjectSpawn(
                x,
                0x549,
                Sonic1ObjectIds.FLOATING_BLOCK,
                0x37,
                0,
                false,
                0
        );
        Sonic1FloatingBlockObjectInstance block = new Sonic1FloatingBlockObjectInstance(
                spawn, Sonic1Constants.ZONE_SYZ);
        block.setServices(testServices);
        return block;
    }
}
