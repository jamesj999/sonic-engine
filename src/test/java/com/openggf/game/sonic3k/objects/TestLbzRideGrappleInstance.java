package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.physics.Direction;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLbzRideGrappleInstance {

    @Test
    void registryRoutesS3klSlot17ToLbzRideGrapple() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);

        ObjectInstance grapple = registry.create(new ObjectSpawn(
                0x1800, 0x0600, Sonic3kObjectIds.LBZ_RIDE_GRAPPLE, 0, 0, false, 0));

        assertFalse(grapple instanceof PlaceholderObjectInstance,
                "S3KL slot $17 is Obj_LBZRideGrapple and must not remain a placeholder");
        assertEquals("LBZRideGrapple", grapple.getName());
        assertEquals(1, grapple.getReservedChildSlotCount(),
                "Obj_LBZRideGrapple owns one loc_2668E multisprite SST child");
    }

    @Test
    void playerInsideGrabWindowIsHeldAtRomOffset() {
        ObjectInstance grapple = grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);

        grapple.update(0, player);

        assertTrue(player.isObjectControlled(),
                "loc_267B2 writes object_control=3 when the player is inside the grapple window");
        assertTrue(player.isObjectControlAllowsCpu(),
                "object_control=3 is a bits 0-6 control state, not ROM bit-7 full-control");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=3 suppresses normal movement while the grapple owns positioning");
        assertEquals(0x1800, player.getCentreX() & 0xFFFF);
        assertEquals(0x0624, player.getCentreY() & 0xFFFF,
                "loc_267B2 snaps the capture frame to the parent y_pos+$24");
        assertEquals(Sonic3kAnimationIds.HANG2.id(), player.getAnimationId(),
                "loc_267F8 writes anim=$14 on capture");
        assertEquals(0x91, player.getMappingFrame(),
                "move.b angle(a0),d0 reads the high byte of the word accumulator, so the first held frame is $91");
        assertTrue(player.isObjectMappingFrameControl(),
                "Perform_Player_DPLC consumes the grapple-owned mapping frame while held");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
    }

    @Test
    void captureFrameUsesParentPositionBeforeFollowingHandle() throws ReflectiveOperationException {
        LbzRideGrappleInstance grapple = (LbzRideGrappleInstance) grapple(0x1800, 0x0600, 0x80);
        writeInt(grapple, "chainExtension", 0x28);
        writeInt(grapple, "angle", 0x1000);
        invokeUpdateChainCoordinates(grapple);
        assertTrue(readInt(grapple, "handleX") != 0x1800,
                "test setup must place the swaying handle away from the parent");
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);

        grapple.update(0, player);

        assertEquals(0x1800, player.getCentreX() & 0xFFFF,
                "loc_267B2 writes parent x_pos on the capture dispatch");
        assertEquals(0x0624, player.getCentreY() & 0xFFFF,
                "loc_267B2 writes parent y_pos+$24 before loc_2673E follows the handle next frame");
    }

    @Test
    void emptyZeroLengthChainClearsIdleSwayEveryDispatch() throws ReflectiveOperationException {
        LbzRideGrappleInstance grapple = (LbzRideGrappleInstance) grapple(0x1800, 0x0600, 0);
        writeInt(grapple, "angle", 0x1000);
        writeInt(grapple, "swayVelocity", 0x0200);
        ((SubpixelMotion.State) readField(grapple, "motion")).xVel = 0x0100;

        grapple.update(0, null);

        assertEquals(0x40, readInt(grapple, "angle"),
                "loc_2684C clears stale angle before the current dispatch applies its first sway step");
        assertEquals(0x40, readInt(grapple, "swayVelocity"));
        assertEquals(0, ((SubpixelMotion.State) readField(grapple, "motion")).xVel);
    }

    @Test
    void heldPlayerUsesHighByteAngleForStableHandleMotion() {
        ObjectInstance grapple = grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);

        grapple.update(0, player);
        for (int frame = 1; frame < 8; frame++) {
            grapple.update(frame, player);

            int dx = Math.abs((player.getCentreX() & 0xFFFF) - 0x1800);
            int dy = Math.abs((player.getCentreY() & 0xFFFF) - 0x0624);
            assertTrue(dx <= 1,
                    "early idle sway should only move Sonic by a pixel-scale amount, not jerk him sideways");
            assertTrue(dy <= 8,
                    "early chain extension should move Sonic by a small bounded amount, not jerk him vertically");
            assertEquals(0x91, player.getMappingFrame(),
                    "byte_26794 should also use the high angle byte, not the rapidly changing low byte");
        }
    }

    @Test
    void chainCoordinatesIncludeParentSubpixelCarry() throws ReflectiveOperationException {
        LbzRideGrappleInstance grapple = (LbzRideGrappleInstance) grapple(0x1800, 0x0600, 0);
        writeInt(grapple, "chainExtension", 0x28);
        writeInt(grapple, "angle", 0x1000);
        invokeUpdateChainCoordinates(grapple);
        int wholePixelHandleX = readInt(grapple, "handleX");

        SubpixelMotion.State motion = (SubpixelMotion.State) readField(grapple, "motion");
        motion.xSub = 0xFF;
        invokeUpdateChainCoordinates(grapple);

        assertEquals(wholePixelHandleX + 1, readInt(grapple, "handleX"),
                "sub_2682E adds the circular deltas to the complete 16.16 parent x_pos");
    }

    @Test
    void movingGrappleDeletesOnlyAfterCurrentAndOriginalPositionsLeaveRange()
            throws ReflectiveOperationException {
        LbzRideGrappleInstance grapple = (LbzRideGrappleInstance) grapple(0x0E68, 0x0600, 9);
        ((SubpixelMotion.State) readField(grapple, "motion")).x = 0x1398;

        assertFalse(grapple.isCustomOutOfRange(0x0D00),
                "loc_265A2 keeps the grapple alive while its saved original x_pos remains in range");
        assertTrue(grapple.isCustomOutOfRange(0x1700),
                "loc_265BC deletes only after both the moving and saved x_pos are out of range");
    }

    @Test
    void nativeP2IsProcessedBeforePlayerOne() {
        AbstractObjectInstance grapple = (AbstractObjectInstance) grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite sidekick = playerAt(0x1800, 0x0620);
        grapple.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));
        TestablePlayableSprite sonic = playerAt(0x1900, 0x0620);

        grapple.update(0, sonic);

        assertFalse(sonic.isObjectControlled());
        assertTrue(sidekick.isObjectControlled(),
                "sub_26694 checks Player_2 before Player_1, so native P2 can grab independently");
        assertEquals(0x1800, sidekick.getCentreX() & 0xFFFF);
        assertEquals(0x0624, sidekick.getCentreY() & 0xFFFF);
    }

    @Test
    void heldDirectionalInputUpdatesFacingAndHeldFrame() {
        ObjectInstance grapple = grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);
        player.setDirection(Direction.RIGHT);

        grapple.update(0, player);

        player.setDirectionalInputPressed(false, false, true, false);
        grapple.update(1, player);

        assertEquals(Direction.LEFT, player.getDirection(),
                "loc_26726 sets Status_Facing when holding left");
        assertTrue(player.getRenderHFlip(),
                "loc_2673E mirrors Status_Facing into render_flags bit 0");

        player.setDirectionalInputPressed(false, false, false, true);
        grapple.update(2, player);

        assertEquals(Direction.RIGHT, player.getDirection(),
                "loc_26732 clears Status_Facing when holding right");
        assertFalse(player.getRenderHFlip(),
                "loc_2673E clears render_flags bit 0 when Status_Facing is clear");
    }

    @Test
    void jumpReleasesHeldPlayerWithRomRollLaunchState() {
        ObjectInstance grapple = grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);

        grapple.update(0, player);
        player.setJumpInputPressed(true, true);
        grapple.update(1, player);

        assertFalse(player.isObjectControlled(),
                "A/B/C press in loc_266B0 clears the per-player grab byte and object_control");
        assertFalse(player.isObjectMappingFrameControl(),
                "release returns mapping-frame ownership to the player animation system");
        assertEquals((short) 0, player.getXSpeed(),
                "release copies the current grapple x_vel to the player");
        assertEquals((short) -0x380, player.getYSpeed(),
                "loc_266E6 writes y_vel=-$380");
        assertTrue(player.getAir(), "release sets Status_InAir");
        assertTrue(player.isJumping(), "release writes jumping=1");
        assertTrue(player.getRolling(), "release sets Status_Roll");
        assertEquals(7, player.getXRadius());
        assertEquals(0x0E, player.getYRadius());
        assertEquals(Sonic3kAnimationIds.ROLL.id(), player.getAnimationId());
    }

    @Test
    void jumpReleaseSuppressesStaleJumpPressForShieldAbilities() {
        ObjectInstance grapple = grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);

        grapple.update(0, player);
        player.setJumpInputPressed(true, true);
        grapple.update(1, player);

        assertTrue(player.consumeSuppressNextJumpPress(),
                "object-controlled release must consume the held release press before airborne shield ability checks");
    }

    @Test
    void heldOffscreenPlayerIsReleasedBeforeAnotherHandleSnap() {
        LbzRideGrappleInstance grapple =
                (LbzRideGrappleInstance) grapple(0x1800, 0x0600, 0);
        TestablePlayableSprite player = playerAt(0x1800, 0x0620);

        grapple.update(0, player);
        int capturedX = player.getCentreX() & 0xFFFF;
        int capturedY = player.getCentreY() & 0xFFFF;
        player.setRenderFlagOnScreen(false);
        grapple.update(1, player);

        assertFalse(player.isObjectControlled(),
                "loc_26718 clears object_control when render_flags bit 7 is clear");
        assertFalse(grapple.isNativeGrabbedForTest(0));
        assertEquals(0x3C, grapple.getReleaseCooldownForTest(0));
        assertEquals(capturedX, player.getCentreX() & 0xFFFF);
        assertEquals(capturedY, player.getCentreY() & 0xFFFF,
                "the offscreen release returns before loc_2673E follows the handle");
    }

    private static ObjectInstance grapple(int x, int y, int subtype) {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_LBZ);
        return registry.create(new ObjectSpawn(
                x, y, Sonic3kObjectIds.LBZ_RIDE_GRAPPLE, subtype, 0, false, 0));
    }

    private static TestablePlayableSprite playerAt(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setYSpeed((short) 0x0200);
        player.setGSpeed((short) 0x0100);
        return player;
    }

    private static void invokeUpdateChainCoordinates(LbzRideGrappleInstance grapple)
            throws ReflectiveOperationException {
        Method method = LbzRideGrappleInstance.class.getDeclaredMethod("updateChainCoordinates");
        method.setAccessible(true);
        method.invoke(grapple);
    }

    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int readInt(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void writeInt(Object target, String name, int value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
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
