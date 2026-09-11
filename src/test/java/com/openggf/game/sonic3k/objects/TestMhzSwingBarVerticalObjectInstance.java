package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzSwingBarVerticalObjectInstance {
    private static final int MHZ_SWING_BAR_VERTICAL = 0x0C;

    @Test
    void registryRoutesSklSlot0CToMhzVerticalSwingBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));

        assertEquals("MHZSwingBarVertical", bar.getName(),
                "SKL slot $0C is Obj_MHZSwingBarVertical and must not remain a placeholder");
    }

    @Test
    void groundedPlayerRunningIntoRightSideIsCapturedOnVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        assertEquals("MHZSwingBarVertical", bar.getName(),
                "SKL slot $0C must construct the MHZ vertical swing bar before capture can be validated");
        bar.update(0, player);

        assertTrue(player.isObjectControlled(),
                "Obj_MHZSwingBarVertical writes object_control=3 on grab");
        assertTrue(player.isObjectControlAllowsCpu(),
                "object_control=3 is a bits 0-6 control state, not the ROM bit-7 full-control state");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=3 suppresses normal player movement while hanging");
        assertTrue(player.isObjectMappingFrameControl(),
                "sub_3F2BE writes mapping_frame directly and loc_3F346 calls Perform_Player_DPLC while hanging");
        assertEquals(0x2212, player.getCentreX(),
                "Right-side grab snaps x_pos to object x_pos+$12");
        assertEquals(0x0700, player.getCentreY(),
                "Vertical bar grab leaves y_pos unchanged");
        assertEquals((short) 0, player.getXSpeed());
        assertEquals((short) 0, player.getYSpeed());
        assertEquals((short) 0, player.getGSpeed());
        assertEquals(0x62, player.getMappingFrame(),
                "Grab starts from mapping frame $62 before the climb animation advances");
    }

    @Test
    void hurtPlayerCannotGrabVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);
        player.setHurt(true);

        bar.update(0, player);

        assertFalse(player.isObjectControlled(),
                "ROM cmpi.b #4,routine(a1) / bhs.w locret_3F35E (sonic3k.asm:83737-83738) rejects the grab "
                        + "while the player's routine is hurt (>=4)");
    }

    @Test
    void deadPlayerCannotGrabVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);
        player.setDead(true);

        bar.update(0, player);

        assertFalse(player.isObjectControlled(),
                "ROM cmpi.b #4,routine(a1) / bhs.w locret_3F35E (sonic3k.asm:83737-83738) rejects the grab "
                        + "while the player's routine is dead (>=4)");
    }

    @Test
    void grabbingVerticalBarPlaysGrabSfx() {
        int[] lastSfx = {-1};
        MhzSwingBarVerticalObjectInstance bar = new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        bar.setServices(new TestObjectServices() {
            @Override
            public void playSfx(int soundId) {
                lastSfx[0] = soundId;
            }
        });
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        bar.update(0, player);

        assertEquals(Sonic3kSfx.GRAB.id, lastSfx[0],
                "sub_3F0D8 plays sfx_Grab (sonic3k.asm:83775-83776) once the vertical-bar grab completes");
    }

    @Test
    void nativeP2RunningIntoRightSideIsCapturedWhenP1UpdatesObject() {
        MhzSwingBarVerticalObjectInstance bar = new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x2100, (short) 0x0700);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x2214, (short) 0x0700);
        tails.setXSpeed((short) 0x0400);
        tails.setGSpeed((short) 0x0400);
        tails.setAir(false);
        bar.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        bar.update(0, sonic);

        assertTrue(tails.isObjectControlled(),
                "loc_3F08C runs sub_3F0D8 for Player_2 after Player_1 in the same object update");
        assertEquals(0x2212, tails.getCentreX(),
                "native P2 must snap to object x_pos+$12 on the right-side grab path");
        assertEquals((short) 0, tails.getXSpeed());
        assertEquals((short) 0, tails.getGSpeed());
        assertEquals(0x62, tails.getMappingFrame());
        assertFalse(sonic.isObjectControlled(),
                "P1 outside the vertical-bar grab window must remain unaffected while P2 is captured");
    }

    @Test
    void completingVerticalBarAnimationLaunchesPlayerHorizontally() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        assertEquals("MHZSwingBarVertical", bar.getName(),
                "SKL slot $0C must construct the MHZ vertical swing bar before release can be validated");
        bar.update(0, player);
        for (int frame = 1; frame <= 31; frame++) {
            bar.update(frame, player);
        }

        assertFalse(player.isObjectControlled(),
                "The ROM auto-release path clears object_control after the climb phase reaches -8");
        assertFalse(player.isObjectMappingFrameControl(),
                "loc_3F1C8 returns normal player animation ownership after clearing the hang latch");
        assertEquals(0x2200, player.getCentreX(),
                "Auto-release centers x_pos on the bar before applying x_vel");
        assertEquals((short) 0x1000, player.getXSpeed(),
                "Facing-right auto-release writes x_vel=$1000");
        assertEquals((short) 0x1000, player.getGSpeed(),
                "Auto-release mirrors x_vel into ground_vel");
        assertEquals(15, player.getMoveLockTimer(),
                "Auto-release writes move_lock=15");
        assertFalse(player.isJumping(),
                "Auto-release clears the jumping byte");
        assertFalse(player.getRollingJump(),
                "Auto-release clears Status_RollJump");
        assertEquals(0, player.getFlipAngle(),
                "Auto-release clears flip_angle");
        assertEquals(0x24, player.getMappingFrame(),
                "Sonic auto-release uses mapping frame $24");
    }

    @Test
    void rightSideGrabClearsStaleFacingLeftBeforeAutoRelease() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);
        player.setRenderFlips(true, false);

        bar.update(0, player);
        assertFalse(player.getRenderHFlip(),
                "Obj_MHZSwingBarVertical clears render_flags bits 0-1 before right-side grab facing is applied");
        for (int frame = 1; frame <= 31; frame++) {
            bar.update(frame, player);
        }

        assertEquals((short) 0x1000, player.getXSpeed(),
                "Right-side auto-release must launch right even if the player entered with stale left-facing render_flags");
    }

    @Test
    void rollingPlayerGrabRestoresStandingRadiiAndAppliesRomYCorrection() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);
        player.setRolling(true);
        int expectedCentreY = player.getCentreY() + player.getYRadius() - player.getStandYRadius();

        bar.update(0, player);

        assertFalse(player.getRolling(),
                "loc_3F2BE clears Status_Roll when the vertical bar captures a rolling player");
        assertEquals(player.getStandXRadius(), player.getXRadius(),
                "loc_3F2BE restores x_radius from default_x_radius on grab");
        assertEquals(player.getStandYRadius(), player.getYRadius(),
                "loc_3F2BE restores y_radius from default_y_radius on grab");
        assertEquals(expectedCentreY, player.getCentreY(),
                "when Status_Roll was set, loc_3F2BE adds old y_radius-default_y_radius to y_pos");
    }

    @Test
    void tailsAutoReleaseUsesTailsMappingFrame() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("tails", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        bar.update(0, player);
        for (int frame = 1; frame <= 31; frame++) {
            bar.update(frame, player);
        }

        assertEquals(0x0E, player.getMappingFrame(),
                "ROM character_id=1 (Tails) replaces the vertical-bar auto-release mapping frame with $0E");
    }

    @Test
    void jumpWhileHangingReleasesVerticalBarPlayerUpwardAndRolls() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        assertEquals("MHZSwingBarVertical", bar.getName(),
                "SKL slot $0C must construct the MHZ vertical swing bar before jump release can be validated");
        bar.update(0, player);
        player.setJumpInputPressed(true);
        bar.update(1, player);

        assertFalse(player.isObjectControlled(),
                "Jump release clears object_control after applying the launch");
        assertFalse(player.isObjectMappingFrameControl(),
                "loc_3F17A returns normal player animation ownership after clearing the hang latch");
        assertTrue(player.getAir(), "Jump release sets Status_InAir");
        assertTrue(player.isJumping(), "Jump release sets the jumping byte");
        assertTrue(player.getRolling(), "Jump release sets Status_Roll");
        assertFalse(player.getRollingJump(), "Jump release clears Status_RollJump");
        assertEquals((short) -0x0500, player.getYSpeed(),
                "Normal-air jump release writes y_vel=-$500");
        assertEquals(0, player.getFlipAngle(),
                "Jump release clears flip_angle");
        assertEquals(7, player.getXRadius());
        assertEquals(14, player.getYRadius());
    }

    @Test
    void heldJumpWithoutLowBytePressDoesNotReleaseVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        player.setJumpInputPressed(true, false);
        bar.update(0, player);
        bar.update(1, player);

        assertTrue(player.isObjectControlled(),
                "loc_3F0F0 masks only the low Ctrl_logical A/B/C press bits; held-only jump stays attached");
        assertEquals(0x2212, player.getCentreX(),
                "Held jump without a fresh press continues through the vertical-bar hanging path");
    }

    @Test
    void hangingPrimaryPlayerForcesCameraScrollToBarXAndPlayerY() {
        Camera camera = mock(Camera.class);
        MhzSwingBarVerticalObjectInstance bar = new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        bar.setServices(new TestObjectServices().withCamera(camera));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        bar.update(0, player); // grab -> loc_3F0CC forces scroll to bar X + Player_1 Y

        // Forced X is the bar's x_pos; forced Y is Player_1's y_pos (unchanged by the grab), NOT the bar's.
        verify(camera).requestForcedScroll(0x2200, 0x0700);
    }

    @Test
    void releasedVerticalBarStopsForcingCameraScroll() {
        Camera camera = mock(Camera.class);
        MhzSwingBarVerticalObjectInstance bar = new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        bar.setServices(new TestObjectServices().withCamera(camera));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        bar.update(0, player); // grab
        player.setJumpInputPressed(true);
        bar.update(1, player); // jump release clears the hang
        clearInvocations(camera);

        bar.update(2, player);
        bar.update(3, player);

        verify(camera, never()).requestForcedScroll(anyInt(), anyInt());
    }

    @Test
    void grabPreservesPlayerXSubpixelOnVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);
        player.setSubpixelRaw(0x9A00, 0);

        bar.update(0, player);

        assertEquals(0x2212, player.getCentreX(),
                "Right-side grab still snaps x_pos to object x_pos+$12");
        assertEquals(0x9A00, player.getXSubpixelRaw(),
                "ROM loc_3F316 (sonic3k.asm:83757-83759) writes x_pos(a1) with move.w, which does not "
                        + "touch x_sub; the grab must preserve the player's subpixel fraction");
    }

    @Test
    void climbFramePreservesPlayerXSubpixelOnVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        bar.update(0, player);
        player.setSubpixelRaw(0x3300, 0);
        bar.update(1, player);

        assertTrue(player.isObjectControlled(),
                "The mid climb phase must not auto-release this early");
        assertEquals(0x3300, player.getXSubpixelRaw(),
                "ROM sub_3F11C (sonic3k.asm:83625-83626) writes x_pos(a1) with move.w each climb frame, "
                        + "which does not touch x_sub");
    }

    @Test
    void autoReleasePreservesPlayerXSubpixelOnVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);

        bar.update(0, player);
        player.setSubpixelRaw(0x1100, 0);
        for (int frame = 1; frame <= 31; frame++) {
            bar.update(frame, player);
        }

        assertFalse(player.isObjectControlled(),
                "This is still the auto-release frame reached by the existing release test");
        assertEquals(0x2200, player.getCentreX());
        assertEquals(0x1100, player.getXSubpixelRaw(),
                "ROM loc_3F1C8 (sonic3k.asm:83669) writes x_pos(a1) with move.w on auto-release, which "
                        + "does not touch x_sub");
    }

    @Test
    void rollingGrabPreservesPlayerYSubpixelOnVerticalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2214, (short) 0x0700);
        player.setXSpeed((short) 0x0400);
        player.setGSpeed((short) 0x0400);
        player.setAir(false);
        player.setRolling(true);
        player.setSubpixelRaw(0, 0x7700);
        int expectedCentreY = player.getCentreY() + player.getYRadius() - player.getStandYRadius();

        bar.update(0, player);

        assertEquals(expectedCentreY, player.getCentreY());
        assertEquals(0x7700, player.getYSubpixelRaw(),
                "ROM loc_3F2BE (sonic3k.asm:83752-83754) applies add.w to y_pos(a1), which does not "
                        + "touch y_sub; the roll-radius correction must preserve the subpixel fraction");
    }

    @Test
    void verticalSwingBarRendersRomFrameAtAnchor() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_BAR_VERTICAL)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzSwingBarVerticalObjectInstance bar = new MhzSwingBarVerticalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_VERTICAL, 0, 0, false, 0));
        bar.setServices(new TestObjectServices().withLevelManager(levelManager));

        bar.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_BAR_VERTICAL);
        verify(renderer).drawFrameIndex(0, 0x2200, 0x0700, false, false);
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
