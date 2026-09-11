package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PlaceholderObjectInstance;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tools.Sonic3kObjectProfile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLbzLoweringGrappleObjectInstance {
    private static final int GRAPPLE_X = 0x0100;
    private static final int GRAPPLE_Y = 0x0050;

    @Test
    void registryRoutesS3klSlot1fToLoweringGrappleOnlyForLbz() {
        ObjectSpawn spawn = spawn(0x1A);

        ObjectInstance lbzObject = registryForZone(Sonic3kZoneIds.ZONE_LBZ).create(spawn);
        ObjectInstance aizObject = registryForZone(Sonic3kZoneIds.ZONE_AIZ).create(spawn);
        ObjectInstance lrzObject = registryForZone(Sonic3kZoneIds.ZONE_LRZ).create(spawn);

        assertInstanceOf(LbzLoweringGrappleObjectInstance.class, lbzObject);
        assertEquals("LBZLoweringGrapple", lbzObject.getName());
        assertInstanceOf(PlaceholderObjectInstance.class, aizObject);
        assertEquals("LBZLoweringGrapple", aizObject.getName());
        assertInstanceOf(PlaceholderObjectInstance.class, lrzObject);
        assertEquals("LRZLavaFall", lrzObject.getName());
    }

    @Test
    void profileMarksLoweringGrappleImplementedForLbzOnly() {
        Sonic3kObjectProfile profile = new Sonic3kObjectProfile();
        var lbz2 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_LAUNCH_BASE_2)
                .findFirst().orElseThrow();
        var aiz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_ANGEL_ISLAND_1)
                .findFirst().orElseThrow();
        var mhz1 = profile.getLevels().stream()
                .filter(level -> level.levelData() == com.openggf.level.LevelData.S3K_MUSHROOM_HILL_1)
                .findFirst().orElseThrow();

        assertTrue(profile.getImplementedIds(lbz2).contains(Sonic3kObjectIds.LBZ_LOWERING_GRAPPLE));
        assertFalse(profile.getImplementedIds(aiz1).contains(Sonic3kObjectIds.LBZ_LOWERING_GRAPPLE));
        assertFalse(profile.getImplementedIds(mhz1).contains(Sonic3kObjectIds.LBZ_LOWERING_GRAPPLE),
                "SKL slot $1F is Obj_LRZLavaFall, not the LBZ lowering grapple");
        assertFalse(Sonic3kObjectProfile.SHARED_IMPLEMENTED_IDS.contains(Sonic3kObjectIds.LBZ_LOWERING_GRAPPLE));
    }

    @Test
    void subtypeLowSevenBitsSetExtensionDistanceAndInitialFrame() {
        LbzLoweringGrappleObjectInstance retracted = new LbzLoweringGrappleObjectInstance(spawn(0x1A));
        LbzLoweringGrappleObjectInstance lowered = new LbzLoweringGrappleObjectInstance(spawn(0x9A));

        assertEquals(0xD0, retracted.targetExtensionForTesting());
        assertEquals(0, retracted.currentExtensionForTesting());
        assertEquals(GRAPPLE_Y, retracted.getY());
        assertEquals(0, retracted.mappingFrameForTesting());

        assertEquals(0xD0, lowered.targetExtensionForTesting());
        assertEquals(0xD0, lowered.currentExtensionForTesting());
        assertEquals(GRAPPLE_Y + 0xD0, lowered.getY());
        assertEquals(14, lowered.mappingFrameForTesting());
    }

    @Test
    void updateDoesNotSelfDeleteWhenXIsInRomSpriteOnScreenRangeButYIsOutsideViewport() {
        AbstractObjectInstance.updateCameraBounds(GRAPPLE_X - 0x80, 0x0200, GRAPPLE_X + 0x140, 0x02E0, 0);
        AbstractPlayableSprite player = playerAt(GRAPPLE_X + 0x100, GRAPPLE_Y + 0x200);
        LbzLoweringGrappleObjectInstance grapple = grapple(services(player, null), 0x1A);

        grapple.update(0, player);

        assertFalse(grapple.isDestroyed(),
                "Obj_LBZLoweringGrapple ends in Sprite_OnScreen_Test, which is X-only in sonic3k.asm");
    }

    @Test
    void playerCaptureLocksMovementAtHandleAndPlaysSwitch() {
        AbstractPlayableSprite player = playerAt(GRAPPLE_X, GRAPPLE_Y + 0x90);
        RecordingServices services = services(player, null);
        LbzLoweringGrappleObjectInstance grapple = grapple(services, 0x1A);

        grapple.update(0, player);

        assertTrue(grapple.grabbedForTesting(0));
        assertEquals(List.of(Sonic3kSfx.SWITCH.id), services.playedSfx);
        verify(player).setXSpeed((short) 0);
        verify(player).setYSpeed((short) 0);
        verify(player).setGSpeed((short) 0);
        verify(player).setCentreXPreserveSubpixel((short) GRAPPLE_X);
        verify(player).setCentreYPreserveSubpixel((short) (GRAPPLE_Y + 0x94));
        verify(player).setAnimationId(Sonic3kAnimationIds.HANG2.id());
        verify(player).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
    }

    @Test
    void grabbedMainPlayerLowersByTwoPixelsAndHeldYFollowsObject() {
        AbstractPlayableSprite player = playerAt(GRAPPLE_X, GRAPPLE_Y + 0x90);
        RecordingServices services = services(player, null);
        LbzLoweringGrappleObjectInstance grapple = grapple(services, 0x1A);
        grapple.update(0, player);
        clearInvocations(player);

        grapple.update(1, player);

        assertEquals(2, grapple.currentExtensionForTesting());
        assertEquals(GRAPPLE_Y + 2, grapple.getY());
        assertEquals(1, grapple.mappingFrameForTesting());
        verify(player).setCentreYPreserveSubpixel((short) (GRAPPLE_Y + 2 + 0x94));
        verify(player, never()).setCentreXPreserveSubpixel((short) GRAPPLE_X);
    }

    @Test
    void jumpReleaseClearsControlSetsCooldownAndAppliesDirectionalLaunch() {
        AbstractPlayableSprite player = playerAt(GRAPPLE_X, GRAPPLE_Y + 0x90);
        RecordingServices services = services(player, null);
        LbzLoweringGrappleObjectInstance grapple = grapple(services, 0x1A);
        grapple.update(0, player);
        clearInvocations(player);
        when(player.getLogicalInputState()).thenReturn(
                AbstractPlayableSprite.INPUT_JUMP | AbstractPlayableSprite.INPUT_LEFT);
        when(player.isLogicalJumpPressActive()).thenReturn(true);

        grapple.update(1, player);

        assertFalse(grapple.grabbedForTesting(0));
        assertEquals(0x3C, grapple.cooldownForTesting(0));
        verify(player).releaseFromObjectControl(1);
        verify(player).setXSpeed((short) -0x0200);
        verify(player).setYSpeed((short) -0x0380);
        verify(player).setAir(true);
        verify(player).setJumping(true);
        verify(player).setRolling(true);
        verify(player).setRollingJump(false);
        verify(player).setFlipAngle(0);
        verify(player).setAnimationId(Sonic3kAnimationIds.ROLL.id());
        verify(player).suppressNextJumpPress();
    }

    @Test
    void heldJumpWithoutFreshLogicalPressDoesNotReleaseGrabbedPlayer() {
        AbstractPlayableSprite player = playerAt(GRAPPLE_X, GRAPPLE_Y + 0x90);
        RecordingServices services = services(player, null);
        LbzLoweringGrappleObjectInstance grapple = grapple(services, 0x1A);
        grapple.update(0, player);
        clearInvocations(player);
        when(player.getLogicalInputState()).thenReturn(AbstractPlayableSprite.INPUT_JUMP);
        when(player.isLogicalJumpPressActive()).thenReturn(false);

        grapple.update(1, player);

        assertTrue(grapple.grabbedForTesting(0));
        assertEquals(0, grapple.cooldownForTesting(0));
        verify(player).setCentreYPreserveSubpixel((short) (GRAPPLE_Y + 2 + 0x94));
        verify(player, never()).releaseFromObjectControl(1);
        verify(player, never()).setYSpeed((short) -0x0380);
    }

    @Test
    void perPlayerCooldownDoesNotBlockNativeP2Capture() {
        AbstractPlayableSprite main = playerAt(GRAPPLE_X, GRAPPLE_Y + 0x90);
        AbstractPlayableSprite sidekick = playerAt(GRAPPLE_X, GRAPPLE_Y + 0x200);
        when(sidekick.isCpuControlled()).thenReturn(true);
        RecordingServices services = services(main, sidekick);
        LbzLoweringGrappleObjectInstance grapple = grapple(services, 0x1A);
        grapple.update(0, main);
        when(main.getLogicalInputState()).thenReturn(AbstractPlayableSprite.INPUT_JUMP);
        when(main.isLogicalJumpPressActive()).thenReturn(true);
        when(sidekick.getCentreY()).thenReturn((short) (GRAPPLE_Y + 0x90));

        grapple.update(1, main);

        assertFalse(grapple.grabbedForTesting(0));
        assertEquals(0x12, grapple.cooldownForTesting(0));
        assertTrue(grapple.grabbedForTesting(1),
                "P2 uses $31/$33 and can grab while P1's $32 release cooldown is active");
        assertEquals(0, grapple.cooldownForTesting(1));
    }

    private static LbzLoweringGrappleObjectInstance grapple(RecordingServices services, int subtype) {
        LbzLoweringGrappleObjectInstance grapple = new LbzLoweringGrappleObjectInstance(spawn(subtype));
        grapple.setServices(services);
        return grapple;
    }

    private static ObjectSpawn spawn(int subtype) {
        return new ObjectSpawn(GRAPPLE_X, GRAPPLE_Y, Sonic3kObjectIds.LBZ_LOWERING_GRAPPLE,
                subtype, 0, false, 0);
    }

    private static AbstractPlayableSprite playerAt(int x, int y) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) x);
        when(player.getCentreY()).thenReturn((short) y);
        when(player.getDead()).thenReturn(false);
        when(player.isDebugMode()).thenReturn(false);
        when(player.isHurt()).thenReturn(false);
        when(player.isObjectControlled()).thenReturn(false);
        when(player.getLogicalInputState()).thenReturn(0);
        return player;
    }

    private static RecordingServices services(AbstractPlayableSprite main, AbstractPlayableSprite sidekick) {
        return new RecordingServices(main, sidekick);
    }

    private static Sonic3kObjectRegistry registryForZone(int zoneId) {
        return new Sonic3kObjectRegistry() {
            @Override
            protected int currentRomZoneId() {
                return zoneId;
            }
        };
    }

    private static final class RecordingServices extends StubObjectServices {
        private final List<Integer> playedSfx = new java.util.ArrayList<>();

        private RecordingServices(AbstractPlayableSprite main, AbstractPlayableSprite sidekick) {
            withPlayerQuery(new ObjectPlayerQuery(
                    () -> main,
                    () -> sidekick == null ? List.of() : List.of(sidekick)));
        }

        @Override
        public void playSfx(int soundId) {
            playedSfx.add(soundId);
        }
    }
}
