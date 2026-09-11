package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLbzSpinLauncherObjectInstance {
    private static final int LAUNCHER_X = 0x1800;
    private static final int LAUNCHER_Y = 0x0520;

    @Test
    void registryCreatesSpinLauncherOnlyForS3klLbz() {
        ObjectSpawn spawn = spawn(0);

        ObjectInstance lbzObject = registryForZone(Sonic3kZoneIds.ZONE_LBZ).create(spawn);
        ObjectInstance lrzObject = registryForZone(Sonic3kZoneIds.ZONE_LRZ).create(spawn);

        assertInstanceOf(LbzSpinLauncherObjectInstance.class, lbzObject);
        assertEquals("LBZSpinLauncher", lbzObject.getName());
        assertInstanceOf(PlaceholderObjectInstance.class, lrzObject);
        assertEquals("LRZDashElevator", lrzObject.getName());
    }

    @Test
    void exposesRomLevelArtAndSolidExtents() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));

        SolidObjectParams params = launcher.getSolidParams();

        assertEquals(Sonic3kObjectArtKeys.LBZ_SPIN_LAUNCHER, launcher.artKeyForTesting());
        assertEquals(0x20, launcher.getOnScreenHalfWidth());
        assertEquals(0x20, launcher.getOnScreenHalfHeight());
        assertEquals(0x2B, params.halfWidth());
        assertEquals(0x10, params.airHalfHeight());
        assertEquals(0x10, params.groundHalfHeight());
        assertEquals(1, launcher.getPriorityBucket());
    }

    @Test
    void exposesRomSlopeTableForSub1dd24ContactClassification() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, launcher,
                "Obj_LBZSpinLauncher passes byte_28FF4 to sub_1DD24 "
                        + "(sonic3k.asm:56476-56488,56639)");

        byte[] slopeData = sloped.getSlopeData();
        assertEquals(44, slopeData.length,
                "d1=$2B gives 44 table samples for the inclusive right edge");
        assertEquals(0x11, slopeData[0]);
        assertEquals(0x11, slopeData[6]);
        assertEquals(0x12, slopeData[7]);
        assertEquals(0x20, slopeData[21]);
        assertEquals(0x21, slopeData[22]);
        assertEquals(0x21, slopeData[43]);
        assertEquals(0x11, sloped.getSlopeBaseline(),
                "loc_1DECE subtracts byte_28FF4[0] before fresh contact classification");
        assertTrue(sloped.usesSlopeForNewLanding(),
                "sub_1DD24's no-standing-bit path samples byte_28FF4 before side/top classification");
        assertTrue(sloped.addsSlopeCatchRangeToVerticalOverlap(),
                "sub_1DD24 adds the launcher's d2=$10 catch range to y_radius before classification");
        assertFalse(sloped.isSlopeFlipped());
    }

    @Test
    void flippedLauncherMirrorsSlopeSamplesThroughStatusBitZero() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(1));

        SlopedSolidProvider sloped = assertInstanceOf(SlopedSolidProvider.class, launcher);

        assertTrue(sloped.isSlopeFlipped(),
                "sub_1DD24 mirrors the sample index when render_flags/status bit 0 is set");
    }

    @Test
    void leftFacingSideContactLaunchesPlayerUpAndLeft() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(1));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X - 0x08));

        launcher.onSolidContact(player, new SolidContact(false, true, false, false, false), 0);

        verify(player).setCentreXPreserveSubpixel((short) (LAUNCHER_X - 0x10));
        verify(player).setCentreYPreserveSubpixel((short) LAUNCHER_Y);
        verify(player).setXSpeed((short) 0);
        verify(player).setYSpeed((short) -0x0A00);
        verify(player).setGSpeed((short) 0x0800);
        verify(player).setAir(true);
        verify(player).setJumping(false);
        verify(player).setRolling(true);
        verify(player).setAnimationId(Sonic3kAnimationIds.ROLL);
        assertEquals(0x10, launcher.cooldownForTesting(true));
    }

    @Test
    void updateAlignmentWithoutSolidSideContactDoesNotLaunchPlayer() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(1));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X - 0x08));

        launcher.update(0, player);

        verify(player, never()).setYSpeed((short) -0x0A00);
        verify(player, never()).setCentreYPreserveSubpixel((short) LAUNCHER_Y);
        assertEquals(0, launcher.cooldownForTesting(true));
    }

    @Test
    void launchCooldownSkipsSolidRoutineThroughOneToZeroTick() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X + 0x08));

        launcher.onSolidContact(player, new SolidContact(false, false, true, false, false), 0);
        for (int frame = 1; frame <= 0x10; frame++) {
            launcher.update(frame, player);
            assertFalse(launcher.isSolidFor(player),
                    "loc_28DE4 skips sub_1DD24 for the entire nonzero cooldown tick");
        }

        launcher.update(0x11, player);
        assertTrue(launcher.isSolidFor(player));
    }

    @Test
    void updateDoesNotSelfDeleteWhenXIsInRomSpriteOnScreenRangeButYIsOutsideViewport() {
        AbstractObjectInstance.updateCameraBounds(LAUNCHER_X - 0x80, 0, LAUNCHER_X + 0x140, 0x00E0, 0);
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));

        launcher.update(0, mock(AbstractPlayableSprite.class));

        assertFalse(launcher.isDestroyed(),
                "Obj_LBZSpinLauncher ends in Sprite_OnScreen_Test, which is X-only in sonic3k.asm");
    }

    @Test
    void sideContactOutsideRomWindowDoesNotLaunch() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X - 0x19));

        launcher.onSolidContact(player, new SolidContact(false, true, false, false, false), 0);

        verify(player, never()).setYSpeed((short) -0x0A00);
        assertEquals(0, launcher.cooldownForTesting(true));
    }

    @Test
    void bottomContactAtOpeningLaunchesPlayerThroughRomTopBottomResult() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X + 0x08));

        launcher.onSolidContact(player, new SolidContact(false, false, true, false, false), 0);

        verify(player).setCentreXPreserveSubpixel((short) (LAUNCHER_X + 0x10));
        verify(player).setCentreYPreserveSubpixel((short) LAUNCHER_Y);
        verify(player).setXSpeed((short) 0);
        verify(player).setYSpeed((short) -0x0A00);
        verify(player).setGSpeed((short) 0x0800);
        verify(player).setAir(true);
        verify(player).setJumping(false);
        verify(player).setRolling(true);
        verify(player).setAnimationId(Sonic3kAnimationIds.ROLL);
        assertEquals(0x10, launcher.cooldownForTesting(true),
                "Obj_LBZSpinLauncher calls sub_28E76 when sub_1DD24 returns d4=-2 "
                        + "for top/bottom contacts (sonic3k.asm:56488-56492)");
    }

    @Test
    void standingContactReleasesRiderWithoutRolling() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X + 0x08));

        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        verify(player).setCentreXPreserveSubpixel((short) (LAUNCHER_X + 0x10));
        verify(player).setXSpeed((short) 0);
        verify(player).setYSpeed((short) 0);
        verify(player).setGSpeed((short) 0);
        verify(player).setAir(true);
        verify(player).setJumping(false);
        verify(player).setOnObject(false);
        verify(player).clearRollingFlagPreserveRadii();
        verify(player, never()).setRolling(false);
        verify(player, never()).setCentreYPreserveSubpixel((short) LAUNCHER_Y);
        verify(player).setAnimationId(Sonic3kAnimationIds.WALK);
        assertEquals(0x20, launcher.cooldownForTesting(true));
    }

    @Test
    void standingContactOutsideUnsignedRomWindowDoesNotReleaseRider() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X - 1));

        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        verify(player, never()).setCentreXPreserveSubpixel((short) (LAUNCHER_X + 0x10));
        verify(player, never()).setYSpeed((short) 0);
        assertEquals(0, launcher.cooldownForTesting(true),
                "sub_28F40 rejects negative (player_x-object_x) via unsigned cmpi.w #$20,bhs");
    }

    @Test
    void flippedStandingContactReleasesRiderToLeftWithoutWritingY() {
        LbzSpinLauncherObjectInstance launcher = new LbzSpinLauncherObjectInstance(spawn(1));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) (LAUNCHER_X - 0x08));

        launcher.onSolidContact(player, new SolidContact(true, false, false, true, false), 0);

        verify(player).setCentreXPreserveSubpixel((short) (LAUNCHER_X - 0x10));
        verify(player, never()).setCentreYPreserveSubpixel((short) LAUNCHER_Y);
        verify(player).setXSpeed((short) 0);
        verify(player).setYSpeed((short) 0);
        verify(player).setGSpeed((short) 0);
        verify(player).setAir(true);
        verify(player).setJumping(false);
        verify(player).setOnObject(false);
        verify(player).clearRollingFlagPreserveRadii();
        verify(player).setAnimationId(Sonic3kAnimationIds.WALK);
        assertEquals(0x20, launcher.cooldownForTesting(true));
    }

    @Test
    void profileMarksSpinLauncherImplementedForLbzOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        var lbz2 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_LAUNCH_BASE_2)
                .findFirst().orElseThrow();
        var mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst().orElseThrow();

        assertTrue(profile.getImplementedIds(lbz2).contains(Sonic3kObjectIds.LBZ_SPIN_LAUNCHER));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.LBZ_SPIN_LAUNCHER),
                "Object ID $1E belongs to LRZDashElevator in the SKL set and must stay out of SKL coverage");
        assertFalse(Sonic3kObjectProfile.SHARED_IMPLEMENTED_IDS.contains(Sonic3kObjectIds.LBZ_SPIN_LAUNCHER));
    }

    private static ObjectSpawn spawn(int renderFlags) {
        return new ObjectSpawn(LAUNCHER_X, LAUNCHER_Y, Sonic3kObjectIds.LBZ_SPIN_LAUNCHER,
                0, renderFlags, false, 0);
    }

    private static Sonic3kObjectRegistry registryForZone(int zoneId) {
        return new Sonic3kObjectRegistry() {
            @Override
            protected int currentRomZoneId() {
                return zoneId;
            }
        };
    }
}
