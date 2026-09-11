package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.level.render.PatternSpriteRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestMhzMushroomPlatformObjectInstance {
    private static final int MHZ_MUSHROOM_PLATFORM = 0x11;

    @BeforeEach
    void keepMhzFixtureCameraOnPlatform() {
        AbstractObjectInstance.updateCameraBounds(0x1300, 0x0500, 0x1500, 0x0700, 0);
    }

    @AfterEach
    void resetCameraBounds() {
        AbstractObjectInstance.resetCameraBoundsForTests();
    }

    @Test
    void registryRoutesSklSlot11ToMhzMushroomPlatform() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);

        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 0, 0, false, 0));

        assertFalse(platform instanceof PlaceholderObjectInstance,
                "SKL slot $11 is Obj_MHZMushroomPlatform and must not fall back to placeholder rendering");
        assertInstanceOf(SlopedSolidProvider.class, platform,
                "Obj_MHZMushroomPlatform calls SolidObjectTopSloped2 with byte_3F42A");
        assertEquals(5, platform.getPriorityBucket(),
                "Obj_MHZMushroomPlatform initializes priority=$280");
    }

    @Test
    void mushroomPlatformExposesRomSlopeTableAndTopSolidProfile() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 0, 0, false, 0));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, platform);

        assertArrayEquals(new byte[]{
                0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13,
                0x13, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14,
                0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x14, 0x13,
                0x13, 0x12, 0x11, 0x10, 0x0F, 0x0E, 0x0D, 0x0C
        }, sloped.getSlopeData(), "byte_3F42A must be used verbatim for sloped top collision");
        assertEquals(0x20, sloped.getSolidParams().halfWidth(),
                "Obj_MHZMushroomPlatform passes width_pixels ($20) into SolidObjectTopSloped2");
        assertEquals(0, sloped.getSlopeBaseline(),
                "SolidObjectTopSloped2 uses absolute height samples for this platform");
    }

    @Test
    void mushroomPlatformHonorsPlacementHFlipForSlopeAndRendering() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PLATFORM)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzMushroomPlatformObjectInstance platform = new MhzMushroomPlatformObjectInstance(
                new ObjectSpawn(0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 0, 1, false, 0));
        platform.setServices(new TestObjectServices().withLevelManager(levelManager));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, platform);
        platform.appendRenderCommands(new ArrayList<>());

        assertEquals(true, sloped.isSlopeFlipped(),
                "SolidObjectTopSloped2 sees the object's preserved H-flip bit from render_flags");
        verify(renderer).drawFrameIndex(0, 0x1400, 0x0600, true, false);
    }

    @Test
    void fallingSubtypeWaitsSixteenFramesAfterStandingThenAcceleratesDownward() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 1, 0, false, 0));
        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, platform);

        listener.onSolidContact(null, new SolidContact(true, false, false, true, false), 0);
        platform.update(0, null);

        for (int frame = 1; frame <= 16; frame++) {
            platform.update(frame, null);
            assertEquals(0x0600, platform.getY(),
                    "ROM $2F countdown holds the mushroom platform at its spawn y_pos before MoveSprite2 runs");
        }

        platform.update(17, null);
        assertEquals(0x0600, platform.getY(),
                "First falling frame applies MoveSprite2 with old y_vel=0, then adds gravity");
        platform.update(18, null);
        assertEquals(0x0600, platform.getY(),
                "Second falling frame has only accumulated $28 subpixels, so integer y_pos is still unchanged");
        platform.update(19, null);
        assertEquals(0x0600, platform.getY(),
                "Third falling frame has accumulated $78 subpixels, so integer y_pos is still unchanged");
        platform.update(20, null);
        assertEquals(0x0600, platform.getY(),
                "Fourth falling frame has accumulated $F0 subpixels, still just below the next integer pixel");
        platform.update(21, null);
        assertEquals(0x0601, platform.getY(),
                "By the fifth falling frame, accumulated $F0 subpixels plus $A0 velocity crosses one pixel");
    }

    @Test
    void fallingSubtypeMovesToRomSentinelXWhenItFallsOffscreen() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 1, 0, false, 0));
        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, platform);

        listener.onSolidContact(null, new SolidContact(true, false, false, true, false), 0);
        platform.update(0, null);
        for (int frame = 1; frame <= 16; frame++) {
            platform.update(frame, null);
        }

        AbstractObjectInstance.updateCameraBounds(0, 0, 320, 224, 0);
        platform.update(17, null);

        assertEquals(0x7F00, platform.getX(),
                "loc_3F3AE writes x_pos=$7F00 when a falling mushroom platform is off-screen");
    }

    @Test
    void fallingSubtypeReleasesStaleRiderAfterPlatformDropsAway() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        ObjectInstance platform = registry.create(new ObjectSpawn(
                0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 1, 0, false, 0));
        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, platform);
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0x1400, (short) 0x05E0);
        player.setOnObject(true);
        player.setAir(false);

        listener.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);
        platform.update(0, player);
        for (int frame = 1; frame <= 16; frame++) {
            listener.onSolidContact(player, new SolidContact(true, false, false, true, false), frame);
            platform.update(frame, player);
        }

        platform.update(17, player);
        assertEquals(0x0600, platform.getY(),
                "First falling frame has not moved the platform down by a full pixel yet");
        assertFalse(player.getAir(),
                "CheckPlayerReleaseFromObj keeps the rider while SonicOnObjHitFloor still finds support");

        for (int frame = 18; frame <= 21; frame++) {
            platform.update(frame, player);
        }

        assertEquals(0x0601, platform.getY(),
                "The regression reaches the first frame where the falling platform has dropped below the rider");
        assertEquals(false, player.isOnObject(),
                "loc_3F3AE calls CheckPlayerReleaseFromObj, clearing Status_OnObj once support is lost");
        assertEquals(true, player.getAir(),
                "CheckPlayerReleaseFromObj sets Status_InAir when the falling platform drops away");
    }

    @Test
    void standingSwitchesToRomPressedAnimationBeforeReturningIdle() {
        Sonic3kObjectRegistry registry = new ZoneForTestRegistry(Sonic3kZoneIds.ZONE_MHZ);
        MhzMushroomPlatformObjectInstance platform = assertInstanceOf(MhzMushroomPlatformObjectInstance.class,
                registry.create(new ObjectSpawn(0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 0, 0, false, 0)));
        SolidObjectListener listener = assertInstanceOf(SolidObjectListener.class, platform);

        platform.update(0, null);
        assertEquals(1, platform.getMappingFrame(),
                "Ani_MHZMushroomPlatform anim 0 starts on mapping frame 1");

        listener.onSolidContact(null, new SolidContact(true, false, false, true, false), 1);
        platform.update(1, null);
        assertEquals(2, platform.getMappingFrame(),
                "loc_3F3CC sets anim(a0)=1 while a player is standing, so Animate_Sprite selects frame 2");

        for (int frame = 2; frame <= 4; frame++) {
            platform.update(frame, null);
            assertEquals(2, platform.getMappingFrame(),
                    "Ani_MHZMushroomPlatform anim 1 holds frame 2 for its $03 delay before $FD,0");
        }

        platform.update(5, null);
        assertEquals(2, platform.getMappingFrame(),
                "Animate_Sprite's Code FD handler (sonic3k.asm loc_1AC5C) writes anim(a0)=0 and rts's "
                        + "immediately without touching mapping_frame, so the pressed frame is still "
                        + "showing on the same call that processes $FD,0");

        platform.update(6, null);
        assertEquals(1, platform.getMappingFrame(),
                "The next Animate_Sprite call sees anim(a0) changed to idle animation 0 and loads its "
                        + "Seq0 script fresh (anim_frame reset to 0), emitting mapping frame 1");
    }

    @Test
    void mushroomPlatformRendersCurrentRomLevelArtFrame() {
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(renderer.isReady()).thenReturn(true);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        when(renderManager.getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PLATFORM)).thenReturn(renderer);
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);

        MhzMushroomPlatformObjectInstance platform = new MhzMushroomPlatformObjectInstance(
                new ObjectSpawn(0x1400, 0x0600, MHZ_MUSHROOM_PLATFORM, 0, 0, false, 0));
        platform.setServices(new TestObjectServices().withLevelManager(levelManager));
        platform.appendRenderCommands(new ArrayList<>());

        verify(renderManager).getRenderer(Sonic3kObjectArtKeys.MHZ_MUSHROOM_PLATFORM);
        verify(renderer).drawFrameIndex(0, 0x1400, 0x0600, false, false);
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
