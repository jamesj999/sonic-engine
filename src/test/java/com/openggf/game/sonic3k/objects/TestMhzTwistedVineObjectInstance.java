package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.Direction;
import com.openggf.physics.TrigLookupTable;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMhzTwistedVineObjectInstance {
    private static final int MHZ_TWISTED_VINE = 0x03;

    @Test
    void registryRoutesSklSlot03ToMhzTwistedVineInsteadOfAizHollowTree() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));

        assertEquals("MHZTwistedVine", vine.getName(),
                "SKL slot $03 is Obj_MHZTwistedVine; MHZ must not use the S3KL AIZ hollow-tree object");
    }

    @Test
    void rightMovingPlayerEntersLowerVineFromLeftWithMinimumGroundSpeed() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = groundedPlayer(0x1FC8, 0x05D8, 0x0200);

        assertEquals("MHZTwistedVine", vine.getName(),
                "SKL slot $03 must construct the MHZ twisted vine before behavior can be validated");
        vine.update(0, player);

        assertTrue(player.isOnObject(), "sub_3DCD0 calls RideObject_SetRide when the entry window matches");
        assertEquals(MHZ_TWISTED_VINE, player.getLatchedSolidObjectId(),
                "RideObject_SetRide must leave a live object latch so shared collision does not treat Status_OnObj as stale");
        assertSame(vine, player.getLatchedSolidObjectInstance(),
                "The MHZ vine is a non-solid controller, so the latch must point back to this live object");
        assertEquals((short) 0x0600, player.getGSpeed(),
                "rightward entry clamps ground_vel up to +$600 when it was slower");
        assertEquals(20, player.getMoveLockTimer(),
                "rightward entry writes move_lock=20");
        assertEquals(Direction.RIGHT, player.getDirection(),
                "rightward entry clears Status_Facing");
        assertEquals(0x80, player.getFlipType(),
                "rightward lower-vine entry writes flip_type=$80");
    }

    @Test
    void lowerEntryUsesRomWordDistanceAcrossCoordinateWrap() {
        MhzTwistedVineObjectInstance vine = new MhzTwistedVineObjectInstance(new ObjectSpawn(
                0xFFE0, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = groundedPlayer(0xFFA8, 0x05D8, 0x0200);

        vine.update(0, player);

        assertTrue(player.isOnObject(),
                "sub_3DCD0 subtracts x_pos as a signed 16-bit word, so $FFA8-$FFE0 = -$38 enters");
        assertEquals((short) 0x0600, player.getGSpeed(),
                "wrapped lower-vine entry must still apply the +$600 minimum ground speed");
        assertEquals(20, player.getMoveLockTimer(),
                "wrapped lower-vine entry must preserve the ROM move_lock side effect");
    }

    @Test
    void nativeP2EntersLowerVineWhenP1UpdatesObject() {
        MhzTwistedVineObjectInstance vine = new MhzTwistedVineObjectInstance(new ObjectSpawn(
                0x2000, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));
        TestablePlayableSprite sonic = groundedPlayer(0x2100, 0x0600, 0x0200);
        TestablePlayableSprite tails = groundedPlayer(0x1FC8, 0x05D8, 0x0200);
        vine.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        vine.update(0, sonic);

        assertTrue(tails.isOnObject(),
                "loc_3DCBA runs sub_3DCD0 for Player_2 after Player_1 in the same object update");
        assertEquals((short) 0x0600, tails.getGSpeed(),
                "native P2 must receive the lower-vine minimum ground speed clamp");
        assertEquals(20, tails.getMoveLockTimer(),
                "native P2 must receive the same move_lock=20 entry side effect");
        assertFalse(sonic.isOnObject(),
                "P1 outside the twisted-vine entry window must remain unaffected while P2 enters");
    }

    @Test
    void leftMovingPlayerEntersLowerVineFromRightWithMirroredGroundSpeedAndFlipAngle() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = groundedPlayer(0x2038, 0x0618, -0x0200);

        assertEquals("MHZTwistedVine", vine.getName(),
                "SKL slot $03 must construct the MHZ twisted vine before behavior can be validated");
        vine.update(0, player);

        assertTrue(player.isOnObject(), "sub_3DCD0 mirrors the ride entry from the right side");
        assertEquals((short) -0x0600, player.getGSpeed(),
                "leftward entry clamps ground_vel down to -$600 when it was slower");
        assertEquals(20, player.getMoveLockTimer());
        assertEquals(Direction.LEFT, player.getDirection(),
                "leftward entry sets Status_Facing");
        assertEquals(0x80, player.getFlipAngle(),
                "leftward lower-vine entry seeds flip_angle=$80");
    }

    @Test
    void activeLowerVineUpdatesPlayerYAndFlipAngleFromRomSinePath() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = groundedPlayer(0x1FC8, 0x05D8, 0x0600);

        vine.update(0, player);
        player.setCentreXPreserveSubpixel((short) 0x1FE0);
        vine.update(1, player);

        assertTrue(player.isOnObject(),
                "loc_3DE36 keeps the player riding while x_pos stays inside object x_pos-$40..+$3F");
        assertEquals(MHZ_TWISTED_VINE, player.getLatchedSolidObjectId(),
                "loc_3DE36 must refresh the RideObject_SetRide latch while the vine owns y_pos");
        assertSame(vine, player.getLatchedSolidObjectInstance(),
                "Refreshing the latch keeps CollisionSystem.hasObjectSupport true between object updates");
        assertEquals(expectedLowerRideY(0x20, player.getYRadius()), player.getCentreY(),
                "loc_3DE36 writes y_pos from GetSineCosine(x_delta+$40+$80) and y_radius compensation");
        assertEquals(0x20, player.getFlipAngle(),
                "loc_3DE36 stores the x delta plus $40 in flip_angle while riding the lower vine");
    }

    @Test
    void activeLowerVineRightBoundaryExitsWithRomAngleAndVelocityMirrors() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance vine = registry.create(new ObjectSpawn(
                0x2000, 0x0600, MHZ_TWISTED_VINE, 0, 0, false, 0));
        TestablePlayableSprite player = groundedPlayer(0x1FC8, 0x05D8, 0x0600);
        player.setXSpeed((short) 0x0300);

        vine.update(0, player);
        player.setCentreXPreserveSubpixel((short) 0x2040);
        vine.update(1, player);

        assertFalse(player.isOnObject(),
                "loc_3DE36 boundary exit clears Status_OnObj once x_pos reaches object x_pos+$40");
        assertEquals(0, player.getLatchedSolidObjectId(),
                "boundary exit must clear the non-solid ride latch together with Status_OnObj");
        assertEquals(0x80, player.getAngle() & 0xFF,
                "lower-vine right boundary writes angle=$80");
        assertEquals(0, player.getFlipAngle());
        assertEquals(0, player.getFlipType(),
                "lower-vine right boundary clears flip_type before mirroring velocity");
        assertEquals((short) -0x0600, player.getGSpeed(),
                "lower-vine right boundary negates ground_vel");
        assertEquals((short) -0x0300, player.getXSpeed(),
                "lower-vine right boundary negates x_vel");
        assertEquals(Direction.LEFT, player.getDirection(),
                "lower-vine right boundary sets Status_Facing");
    }

    private static TestablePlayableSprite groundedPlayer(int x, int y, int xSpeed) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setAir(false);
        player.setXSpeed((short) xSpeed);
        player.setGSpeed((short) xSpeed);
        return player;
    }

    private static int expectedLowerRideY(int offsetWithinVine, int yRadius) {
        int cos = TrigLookupTable.cosHex((offsetWithinVine + 0x80) & 0xFF);
        return 0x0600 + (cos >> 4) + ((yRadius * cos) >> 8);
    }

    private static final class ZoneForTestRegistry extends Sonic3kObjectRegistry {
        private final int zoneId;

        private ZoneForTestRegistry(int zoneId) {
            this.zoneId = zoneId;
        }

        @Override
        protected int currentRomZoneId() {
            return zoneId;
        }
    }
}
