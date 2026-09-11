package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzSwingBarHorizontalObjectInstance {
    private static final int MHZ_SWING_BAR_HORIZONTAL = 0x0B;

    @Test
    void registryRoutesSklSlot0BToMhzHorizontalSwingBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));

        assertEquals("MHZSwingBarHorizontal", bar.getName(),
                "SKL slot $0B is Obj_MHZSwingBarHorizontal and must not remain a placeholder");
    }

    @Test
    void fallingPlayerInsideGrabWindowIsCapturedAtRomHangPoint() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) 0x0300);
        player.setRenderFlips(true, true);

        assertEquals("MHZSwingBarHorizontal", bar.getName(),
                "SKL slot $0B must construct the MHZ horizontal swing bar before capture can be validated");
        bar.update(0, player);

        assertTrue(player.isObjectControlled(),
                "Obj_MHZSwingBarHorizontal writes object_control=3 on grab");
        assertTrue(player.isObjectControlAllowsCpu(),
                "object_control=3 is a bits 0-6 control state, not the ROM bit-7 full-control state");
        assertTrue(player.isObjectControlSuppressesMovement(),
                "object_control=3 suppresses normal player movement while hanging");
        assertEquals(0x2200, player.getCentreX(),
                "Grab clears x_vel and leaves x_pos at the bar center when captured at dx=0");
        assertEquals(0x0714, player.getCentreY(),
                "Grab snaps y_pos to object y_pos+$14");
        assertEquals((short) 0, player.getXSpeed());
        assertEquals((short) 0, player.getYSpeed());
        assertEquals((short) 0, player.getGSpeed());
        assertFalse(player.getRenderHFlip(),
                "loc_3EF30 clears render_flags bit 0 before the player hangs from the horizontal bar");
        assertFalse(player.getRenderVFlip(),
                "loc_3EF30 clears render_flags bit 1 before the player hangs from the horizontal bar");
        assertTrue(player.isObjectMappingFrameControl(),
                "sub_3ED6E writes mapping_frame directly and calls Perform_Player_DPLC while the player hangs");
        assertEquals(0x94, player.getMappingFrame(),
                "Low incoming y_vel uses the hanging mapping frame $94");
    }

    @Test
    void hurtPlayerCannotGrabHorizontalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) 0x0300);
        player.setHurt(true);

        bar.update(0, player);

        assertFalse(player.isObjectControlled(),
                "ROM cmpi.b #4,routine(a1) / bhs.w locret_3EFB8 (sonic3k.asm:83439-83440) rejects the grab "
                        + "while the player's routine is hurt (>=4)");
    }

    @Test
    void deadPlayerCannotGrabHorizontalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) 0x0300);
        player.setDead(true);

        bar.update(0, player);

        assertFalse(player.isObjectControlled(),
                "ROM cmpi.b #4,routine(a1) / bhs.w locret_3EFB8 (sonic3k.asm:83439-83440) rejects the grab "
                        + "while the player's routine is dead (>=4)");
    }

    @Test
    void nativeP2InsideGrabWindowIsCapturedWhenP1UpdatesObject() {
        MhzSwingBarHorizontalObjectInstance bar = new MhzSwingBarHorizontalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite sonic = new TestablePlayableSprite("sonic", (short) 0x2100, (short) 0x0700);
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0x2200, (short) 0x0720);
        tails.setYSpeed((short) 0x0300);
        bar.setServices(new TestObjectServices().withSidekicks(List.of(tails)));

        bar.update(0, sonic);

        assertTrue(tails.isObjectControlled(),
                "loc_3ED46 runs sub_3ED6E for Player_2 after Player_1 in the same object update");
        assertEquals(0x0714, tails.getCentreY(),
                "native P2 must snap to the horizontal swing bar hang point, matching the Player_2 ROM path");
        assertEquals((short) 0, tails.getYSpeed());
        assertEquals(0x94, tails.getMappingFrame());
        assertFalse(sonic.isObjectControlled(),
                "P1 outside the grab window must remain unaffected while P2 is captured");
    }

    @Test
    void jumpWhileHangingReleasesPlayerUpwardAndRolls() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);

        assertEquals("MHZSwingBarHorizontal", bar.getName(),
                "SKL slot $0B must construct the MHZ horizontal swing bar before release can be validated");
        bar.update(0, player);
        player.setJumpInputPressed(true);
        bar.update(1, player);

        assertFalse(player.isObjectControlled(),
                "Jump release clears object_control after applying the launch");
        assertFalse(player.isObjectMappingFrameControl(),
                "loc_3EE2C returns normal player animation ownership after clearing the hang latch");
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
    void jumpReleaseCooldownPreventsImmediateRegrab() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);

        bar.update(0, player);
        player.setJumpInputPressed(true);
        bar.update(1, player);

        player.setJumpInputPressed(false);
        player.setCentreYPreserveSubpixel((short) 0x0720);
        bar.update(2, player);

        assertFalse(player.isObjectControlled(),
                "loc_3EE2C writes a 30-frame cooldown after jump release, so the bar cannot immediately regrab");
    }

    @Test
    void heldJumpWithoutLowBytePressDoesNotReleaseHorizontalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);

        player.setJumpInputPressed(true, false);
        bar.update(0, player);
        bar.update(1, player);

        assertTrue(player.isObjectControlled(),
                "loc_3EDE6 masks only the low Ctrl_logical A/B/C press bits; held-only jump stays attached");
        assertEquals(0x0714, player.getCentreY(),
                "Held jump without a fresh press continues through the hanging animation path");
    }

    @Test
    void fastDownwardHangAutoReleasesWithStoredYVelocity() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) 0x0400);

        bar.update(0, player);
        for (int frame = 1; frame <= 20; frame++) {
            bar.update(frame, player);
        }

        assertFalse(player.isObjectControlled(),
                "sub_3EFBA advances fast downward hangs until phase $05, where loc_3EEC2 auto-releases");
        assertTrue(player.getAir(),
                "Horizontal bar auto-release sets Status_InAir");
        assertFalse(player.isJumping(),
                "Horizontal bar auto-release clears the jumping byte instead of taking the jump-release path");
        assertFalse(player.getRollingJump(),
                "Horizontal bar auto-release clears Status_RollJump");
        assertEquals((short) 0x0400, player.getYSpeed(),
                "loc_3EEC2 restores the y_vel captured at grab time");
        assertEquals(0, player.getFlipAngle(),
                "Horizontal bar auto-release clears flip_angle");
    }

    @Test
    void fastUpwardHangAutoReleasesWithAmplifiedYVelocityAndSpringAnimation() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) -0x0400);

        bar.update(0, player);
        for (int frame = 1; frame <= 23; frame++) {
            bar.update(frame, player);
        }

        assertFalse(player.isObjectControlled(),
                "sub_3EFBA advances fast upward hangs until phase $28, where loc_3EE7A auto-releases");
        assertEquals((short) -0x0600, player.getYSpeed(),
                "loc_3EE7A writes y_vel = stored + stored/2");
        assertEquals(Sonic3kAnimationIds.SPRING.id(), player.getAnimationId(),
                "loc_3EE7A writes anim=$10 before the shared auto-release cleanup");
        assertFalse(player.isJumping(),
                "Horizontal bar auto-release clears the jumping byte");
    }

    @Test
    void heldLeftWhileHangingNudgesPlayerTowardRomClamp() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);

        bar.update(0, player);
        player.setDirectionalInputPressed(false, false, true, false);
        bar.update(1, player);

        assertTrue(player.isObjectControlled(),
                "Directional hanging adjustment must keep the player attached to the bar");
        assertEquals(0x21FF, player.getCentreX(),
                "loc_3ED8E subtracts one pixel while LEFT is held and x_pos is past object x_pos-$16");
    }

    @Test
    void heldLeftForEightFramesTogglesHorizontalBarFastFramePage() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);

        bar.update(0, player);
        player.setDirectionalInputPressed(false, false, true, false);
        for (int frame = 1; frame <= 8; frame++) {
            bar.update(frame, player);
        }

        assertEquals(0x95, player.getMappingFrame(),
                "loc_3EDA6 toggles byte 8(a2) every eight held LEFT frames, selecting RawAni_3F01A+$10");
    }

    @Test
    void grabPreservesPlayerYSubpixelOnHorizontalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) 0x0300);
        player.setSubpixelRaw(0, 0xA500);

        bar.update(0, player);

        assertEquals(0x0714, player.getCentreY(),
                "Grab still snaps y_pos to object y_pos+$14");
        assertEquals(0xA500, player.getYSubpixelRaw(),
                "ROM loc_3EEDA (sonic3k.asm:83448-83450) writes y_pos(a1) with move.w, which does not "
                        + "touch y_sub; the grab must preserve the player's subpixel fraction");
    }

    @Test
    void hangingFramePreservesPlayerYSubpixelOnHorizontalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);
        player.setYSpeed((short) 0x0300);

        bar.update(0, player);
        player.setSubpixelRaw(0, 0x4200);
        bar.update(1, player);

        assertTrue(player.isObjectControlled(),
                "The mid hang phase must not auto-release for a moderate incoming y_vel");
        assertEquals(0x4200, player.getYSubpixelRaw(),
                "ROM sub_3EFBA (sonic3k.asm:83533-83534) writes y_pos(a1) with move.w each hang frame, "
                        + "which does not touch y_sub");
    }

    @Test
    void horizontalInputPreservesPlayerXSubpixelOnHorizontalBar() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance bar = registry.create(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x2200, (short) 0x0720);

        bar.update(0, player);
        player.setSubpixelRaw(0x6300, 0);
        player.setDirectionalInputPressed(false, false, true, false);
        bar.update(1, player);

        assertEquals(0x21FF, player.getCentreX(),
                "loc_3ED8E still subtracts one pixel while LEFT is held");
        assertEquals(0x6300, player.getXSubpixelRaw(),
                "ROM loc_3ED8E (sonic3k.asm:83326) uses subq.w on x_pos(a1), which does not touch x_sub");
    }

    @Test
    void horizontalSwingBarRendersRomFrameAtAnchor() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_BAR_HORIZONTAL)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzSwingBarHorizontalObjectInstance bar = new MhzSwingBarHorizontalObjectInstance(new ObjectSpawn(
                0x2200, 0x0700, MHZ_SWING_BAR_HORIZONTAL, 0, 0, false, 0));
        bar.setServices(new TestObjectServices().withLevelManager(levelManager));

        bar.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_SWING_BAR_HORIZONTAL);
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
