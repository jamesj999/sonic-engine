package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// sub_3F7AE's wall sensors read terrain through GameServices.levelOrNull(), and
// the per-test reset keeps whatever level a previous class left in the session
// (a leaked HCZ1 pushes the parachute +$1E at the frame-1 wall check). These
// scenarios assume open air, so rebuild the session from scratch each test.
@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestMhzMushroomParachuteObjectInstance {
    private static final int MHZ_MUSHROOM_PARACHUTE = 0x12;

    @BeforeEach
    void keepMhzFixtureCameraOnParachute() {
        AbstractObjectInstance.updateCameraBounds(0x1400, 0x0480, 0x1600, 0x0680, 0);
    }

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void registryRoutesSklSlot12ToMhzMushroomParachute() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));

        assertFalse(parachute instanceof PlaceholderObjectInstance,
                "SKL slot $12 is Obj_MHZMushroomParachute and must not remain a placeholder");
    }

    @Test
    void fallingPlayerInGrabWindowIsCarriedAtRomOffsetAndParachuteStartsFalling() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        assertEquals(5, parachute.getPriorityBucket(),
                "Obj_MHZMushroomParachute initializes priority=$280");
        parachute.update(0, player);

        assertEquals(1, parachute.getPriorityBucket(),
                "sub_3F70C lowers the parachute to priority=$80 when it grabs a player");
        assertTrue(player.isObjectControlled(),
                "sub_3F5C2 writes object_control=3 after snapping the player to the parachute");
        assertTrue(player.isObjectControlAllowsCpu(),
                "object_control=3 is a bits 0-6 control state, so the ROM still lets Tails CPU logic run");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=3 suppresses normal movement while the parachute owns positioning");
        assertEquals(MHZ_MUSHROOM_PARACHUTE, player.getLatchedSolidObjectId(),
                "loc_3F70C writes the parachute object pointer to player interact");
        assertEquals(0x1500, player.getCentreX() & 0xFFFF);
        assertEquals(0x0525, player.getCentreY() & 0xFFFF,
                "Obj_MHZMushroomParachute carries the player at object y_pos+$25");
        assertEquals(0, player.getXSpeed());
        assertEquals(0, player.getYSpeed());
        assertEquals(0, player.getGSpeed());
        assertEquals(Sonic3kAnimationIds.WALK.id(), player.getAnimationId());

        parachute.update(1, player);

        assertEquals(0x1502, parachute.getX(),
                "sub_3F7E2 uses d1 from GetSineCosine, so angle $00 writes x_vel=$200 before MoveSprite2");
        assertEquals(0x0502, parachute.getY(),
                "loc_3F51C calls sub_3F7E2 first, reducing y_vel from $300 to $2E0 before MoveSprite2");
        assertEquals(0x1502, player.getCentreX() & 0xFFFF,
                "carried player follows the parachute x_pos after the ROM cosine-based horizontal move");
        assertEquals(0x0527, player.getCentreY() & 0xFFFF,
                "carried player follows the parachute at the same +$25 offset");
        assertEquals((short) 0x0200, player.getXSpeed(),
                "carried player mirrors the parachute x_vel stored in object RAM $34");
        assertEquals((short) 0x2E0, player.getYSpeed(),
                "carried player mirrors the parachute y_vel stored in object RAM $36");
    }

    @Test
    void carriedPlayerUsesRomParachuteMappingFrameAndFacingFromObjectAngle() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        player.setDirection(com.openggf.physics.Direction.LEFT);
        player.setRenderFlips(true, false);
        parachute.update(0, player);

        assertTrue(player.isObjectMappingFrameControl(),
                "loc_3F6A6 writes mapping_frame directly and calls Perform_Player_DPLC while carried");
        assertEquals(0xE4, player.getMappingFrame(),
                "RawAni_3F6EE[angle>>4] gives mapping_frame $E4 for the default angle $00");
        assertFalse(player.getRenderHFlip(),
                "angle+$40 is positive at angle $00, so loc_3F6A6 clears Status_Facing/render h-flip");
    }

    @Test
    void nativeP2AndP1CanBothGrabParachuteInSameRomPlayerLoop() {
        MhzMushroomParachuteObjectInstance parachute = new MhzMushroomParachuteObjectInstance(
                new ObjectSpawn(0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite sonic = playerAtGrabWindow(0x1500, 0x0525);
        TestablePlayableSprite tails = playerAtGrabWindow(0x1500, 0x0525);
        parachute.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        parachute.update(0, sonic);

        assertTrue(tails.isObjectControlled(),
                "sub_3F5AA runs the native P2 block first and should set the $31 latch");
        assertTrue(sonic.isObjectControlled(),
                "sub_3F5AA then runs the P1 block and should set the $30 latch");
        assertEquals(0x0525, tails.getCentreY() & 0xFFFF);
        assertEquals(0x0525, sonic.getCentreY() & 0xFFFF);

        parachute.update(1, sonic);

        assertEquals(0x0527, tails.getCentreY() & 0xFFFF,
                "falling parachute motion should continue to snap native P2 at y_pos+$25");
        assertEquals(0x0527, sonic.getCentreY() & 0xFFFF,
                "falling parachute motion should continue to snap P1 at y_pos+$25");
    }

    @Test
    void hurtPlayerInsideParachuteGrabWindowIsNotCaptured() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);
        player.setHurt(true);

        parachute.update(0, player);

        assertFalse(player.isObjectControlled(),
                "loc_3F70C rejects routine(a1) >= 4 before Obj_MHZMushroomParachute writes object_control=3");
        assertFalse(player.isObjectMappingFrameControl(),
                "a hurt player rejected by the ROM routine gate must not receive parachute-owned mapping frames");
        assertEquals((short) 0x0200, player.getYSpeed(),
                "rejected parachute grab must leave the player's falling y_vel untouched");
    }

    @Test
    void jumpReleasesCarriedPlayerWithRomVelocityAndDirectionalXSpeed() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        parachute.update(0, player);
        player.setDirectionalInputPressed(false, false, true, false);
        player.setJumpInputPressed(true, true);
        parachute.update(1, player);

        assertFalse(player.isObjectControlled(),
                "button A/B/C releases from sub_3F5C2 by clearing object_control");
        assertEquals(0, player.getLatchedSolidObjectId(),
                "button release clears interact before applying the roll launch");
        assertEquals((short) -0x200, player.getXSpeed(),
                "holding left during release writes x_vel=-$200");
        assertEquals((short) -0x380, player.getYSpeed(),
                "parachute release writes y_vel=-$380");
        assertTrue(player.getAir(), "release sets Status_InAir");
        assertTrue(player.isJumping(),
                "loc_3F634 writes jumping=1 when A/B/C releases from the parachute");
        assertTrue(player.getRolling(), "release sets Status_Roll");
        assertEquals(Sonic3kAnimationIds.ROLL.id(), player.getAnimationId());
    }

    @Test
    void heldJumpWithoutFreshPressDoesNotReleaseCarriedPlayer() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        parachute.update(0, player);
        player.setJumpInputPressed(true, false);
        parachute.update(1, player);

        assertTrue(player.isObjectControlled(),
                "sub_3F5C2 releases only when Ctrl_1_logical has a low-byte A/B/C press");
        assertEquals(0x0527, player.getCentreY() & 0xFFFF,
                "held jump without a fresh press should continue through loc_3F678 and stay snapped");
        assertEquals((short) 0x02E0, player.getYSpeed(),
                "carried player should keep mirroring the parachute y_vel instead of receiving -$380");
        assertFalse(player.getRolling(),
                "held jump without a fresh press must not enter the roll release branch");
    }

    @Test
    void velocityMismatchInvalidatesCarriedPlayerWithRomReleaseVelocity() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        parachute.update(0, player);
        parachute.update(1, player);
        player.setYSpeed((short) 0x0123);
        parachute.update(2, player);

        assertFalse(player.isObjectControlled(),
                "loc_3F660/loc_3F666 clear object_control when the carried player's velocity no longer matches $34/$36");
        assertEquals((short) -0x0100, player.getYSpeed(),
                "velocity mismatch release writes y_vel=-$100 instead of snapping the player back to the parachute");
        assertFalse(player.getRolling(),
                "the invalid release branch does not enter the button-release roll state");
        assertFalse(player.isJumping(),
                "the invalid release branch does not set jumping=1");
    }

    @Test
    void fallingParachuteDoesNotRegrabAfterP1LatchClears() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        parachute.update(0, player);
        parachute.update(1, player);
        player.setYSpeed((short) 0x0123);
        parachute.update(2, player);

        assertFalse(player.isObjectControlled(),
                "fixture should have cleared the P1 carry latch through loc_3F660/loc_3F666");

        for (int frame = 3; frame <= 0x42; frame++) {
            AbstractObjectInstance.updateCameraBounds(
                    parachute.getX() - 0x100,
                    parachute.getY() - 0x100,
                    parachute.getX() + 0x100,
                    parachute.getY() + 0x100,
                    0);
            player.setCentreX((short) parachute.getX());
            player.setCentreY((short) ((parachute.getY() + 0x25) & 0xFFFF));
            player.setYSpeed((short) 0x0200);
            player.setAir(true);
            parachute.update(frame, player);
        }

        assertFalse(player.isObjectControlled(),
                "After $30 clears, loc_3F51C switches to loc_3F572; that free-fall routine never calls sub_3F5AA to regrab");
    }

    @Test
    void offscreenCarriedPlayerUsesRomInvalidReleaseBranch() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        parachute.update(0, player);
        parachute.update(1, player);
        player.setRenderFlagOnScreen(false);
        parachute.update(2, player);

        assertFalse(player.isObjectControlled(),
                "sub_3F5C2 branches to loc_3F660 when the carried player's render_flags byte is not negative");
        assertEquals((short) -0x0100, player.getYSpeed(),
                "offscreen carried-player invalid release writes y_vel=-$100");
    }

    @Test
    void fallingParachuteMovesToRomSentinelXAndReleasesPlayerWhenOffscreen() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance parachute = registry.create(new ObjectSpawn(
                0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        TestablePlayableSprite player = playerAtGrabWindow(0x1500, 0x0525);

        parachute.update(0, player);
        assertTrue(player.isObjectControlled(),
                "fixture should start from the ROM carried-player state before the offscreen branch");

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        parachute.update(1, player);

        assertEquals(0x7F00, parachute.getX(),
                "loc_3F544/loc_3F572 write x_pos=$7F00 when the falling parachute is off-screen");
        assertFalse(player.isObjectControlled(),
                "offscreen parachute cleanup clears the carried player's object_control byte");
    }

    @Test
    void mushroomParachuteRendersRomLevelArtFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PARACHUTE)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzMushroomParachuteObjectInstance parachute = new MhzMushroomParachuteObjectInstance(
                new ObjectSpawn(0x1500, 0x0500, MHZ_MUSHROOM_PARACHUTE, 0, 0, false, 0));
        parachute.setServices(new TestObjectServices().withLevelManager(levelManager));
        parachute.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PARACHUTE);
        verify(renderer).drawFrameIndex(0, 0x1500, 0x0500, false, false);
    }

    private static TestablePlayableSprite playerAtGrabWindow(int x, int y) {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) x, (short) y);
        player.setYSpeed((short) 0x200);
        player.setXSpeed((short) 0x120);
        player.setGSpeed((short) 0x120);
        player.setAir(true);
        return player;
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
