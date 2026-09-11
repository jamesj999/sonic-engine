package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class TestPachinkoMagnetOrbObjectInstance {

    @Test
    public void capturesMainPlayerAndSidekickIndependently() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x100, 0x100);
        AbstractPlayableSprite sidekick = mockPlayerAt(0x108, 0x108);

        orb.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));
        orb.update(0, main);

        // ROM sub_4A428 loc_4A5AA (sonic3k.asm:97086-97097) captures with
        // ground_vel/render_flags/anim writes plus `move.b #1,object_control(a1)`
        // and NO Ctrl_1_locked/Ctrl_2_locked write. Setting the engine control lock
        // here latched logicalInputState through Obj01_Control's Ctrl_1_locked
        // short-circuit (sonic3k.asm:21968-21971), which froze the Stat_table word
        // Sonic_RecordPos (:22132) records for the sidekick's delayed follow read.
        verify(main, never()).setControlLocked(anyBoolean());
        verify(sidekick, never()).setControlLocked(anyBoolean());
        verify(main).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
        verify(sidekick).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
        verify(main).setAnimationId(Sonic3kAnimationIds.ROLL);
        verify(sidekick).setAnimationId(Sonic3kAnimationIds.ROLL);
        verify(main, never()).setRolling(true);
        verify(sidekick, never()).setRolling(true);
    }

    @Test
    public void duplicateSidekickEntriesDoNotTickCapturedSidekickTwiceOnFirstCapture() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x100, 0x100);
        AbstractPlayableSprite sidekick = mockPlayerAt(0x108, 0x108);
        CountingObjectServices services = new CountingObjectServices();
        services.withSidekicks(List.of(sidekick, sidekick));
        orb.setServices(services);

        orb.update(0, main);

        verify(sidekick, times(1)).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
        assertEquals(0, services.sfxCount);
    }

    @Test
    public void capturesUpdatePlayerWhenPlayerQueryCannotResolveMain() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x100, 0x100);
        AbstractPlayableSprite sidekick = mockPlayerAt(0x108, 0x108);

        orb.setServices(new TestObjectServices().withSidekicks(List.of(sidekick)));
        orb.update(0, main);

        verify(main).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
        verify(sidekick).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
    }

    @Test
    public void launchReleaseAppliesRollState() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x100, 0x100);
        when(main.isJumpJustPressed()).thenReturn(true);

        orb.setServices(new TestObjectServices());
        orb.update(0, main);
        orb.update(1, main);

        // ROM loc_4A4F0/loc_4A4F6 (sonic3k.asm:97024-97042) clears object_control
        // bits 0-1 only; the release has no Ctrl_1_locked write either.
        verify(main, never()).setControlLocked(anyBoolean());
        verify(main).setRolling(true);
        // ROM loc_4A4F6 (sonic3k.asm:97029-97042) sets y_radius/x_radius and the
        // Status_Roll bit with NO y_pos write, so the release must preserve the player's
        // centre rather than apply Sonic_Roll's feet-planted getRollHeightAdjustment shift.
        // mockPlayerAt stubs getCentreY() as y+20 (0x100 + 20 = 0x114).
        verify(main).setCentreYPreserveSubpixel((short) 0x114);
        verify(main, never()).getRollHeightAdjustment();
        verify(main, atLeastOnce()).setAnimationId(Sonic3kAnimationIds.ROLL);
        verify(main, atLeastOnce()).setJumping(false);
        verify(main).setFlipAngle(0);
        verify(main).setDoubleJumpFlag(0);
    }

    @Test
    public void releaseKeepsNativeYWordAndFractionWhileEnteringRoll() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        Sonic player = new Sonic("sonic", (short) 0x100, (short) 0x100);
        player.setSubpixelRaw(0x2300, 0xA700);

        orb.setServices(new TestObjectServices());
        orb.update(0, player);
        short centreYBeforeRelease = player.getCentreY();

        orb.forceReleasePlayer(player, 1);

        assertEquals(centreYBeforeRelease, player.getCentreY());
        assertEquals(0xA700, player.getYSubpixelRaw());
    }

    @Test
    public void releaseStartsCooldownBeforeSameOrbCanRecapture() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x100, 0x100);
        when(main.isJumpJustPressed()).thenReturn(true, false);

        orb.setServices(new TestObjectServices());
        orb.update(0, main);
        orb.update(1, main);
        orb.update(2, main);

        verify(main, times(1)).applyObjectControlState(ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed());
    }

    @Test
    public void heldJumpDoesNotReleaseUntilRepressed() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x100, 0x100);
        when(main.isJumpPressed()).thenReturn(true, true, false, true);
        when(main.isJumpJustPressed()).thenReturn(false, false, true);

        orb.setServices(new TestObjectServices());
        orb.update(0, main);
        orb.update(1, main);
        orb.update(2, main);
        orb.update(3, main);

        verify(main, times(1)).releaseFromObjectControl(3);
    }

    /**
     * ROM {@code sub_4A428} (sonic3k.asm:96955-96967) has no on-screen or
     * camera-distance test in its captured branch, and Player 2 is driven through
     * the very same subroutine from {@code loc_4A408} (sonic3k.asm:96943-96949).
     * A CPU sidekick carried off-screen by the orbit therefore stays captured;
     * only Debug_placement_mode, {@code routine(a1) >= 4}, {@code object_control}
     * bit 7, or an A/B/C press in its own Ctrl_2_logical pressed byte release it.
     *
     * <p>This test previously asserted the opposite -- that an off-screen CPU
     * sidekick is released -- pinning engine-invented behaviour with no ROM
     * counterpart.
     */
    @Test
    public void offscreenCapturedSidekickStaysCaptured() {
        PachinkoMagnetOrbObjectInstance orb = new PachinkoMagnetOrbObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xEC, 0, 0, false, 0));
        AbstractPlayableSprite main = mockPlayerAt(0x400, 0x400);
        AbstractPlayableSprite sidekick = mockPlayerAt(0x100, 0x100);
        Camera camera = mock(Camera.class);
        when(sidekick.isCpuControlled()).thenReturn(true);
        when(camera.isOnScreen(sidekick)).thenReturn(false);

        CountingObjectServices services = new CountingObjectServices();
        services.withCamera(camera);
        services.withSidekicks(List.of(sidekick));
        orb.setServices(services);

        orb.update(0, main);
        services.resetSfxCount();
        orb.update(16, main);

        verify(sidekick, never()).releaseFromObjectControl(anyInt());
        // vIntRunCount 16 is a multiple of the ROM's `(Level_frame_counter+1) & $F`
        // hover-SFX period, and sub_4A428 reaches Play_SFX on that frame for a
        // still-captured player regardless of where the camera is.
        assertEquals(1, services.sfxCount);
    }

    private static AbstractPlayableSprite mockPlayerAt(int x, int y) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getX()).thenReturn((short) x);
        when(player.getY()).thenReturn((short) y);
        when(player.getCentreX()).thenReturn((short) (x + 10));
        when(player.getCentreY()).thenReturn((short) (y + 20));
        when(player.getWidth()).thenReturn(20);
        when(player.getHeight()).thenReturn(40);
        when(player.getRollHeightAdjustment()).thenReturn((short) 10);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getDead()).thenReturn(false);
        when(player.isHurt()).thenReturn(false);
        when(player.isObjectControlled()).thenReturn(false);
        when(player.isControlLocked()).thenReturn(false);
        when(player.isJumpPressed()).thenReturn(false);
        when(player.isJumpJustPressed()).thenReturn(false);
        when(player.isLeftPressed()).thenReturn(false);
        when(player.isRightPressed()).thenReturn(false);
        when(player.isCpuControlled()).thenReturn(false);
        return player;
    }

    private static final class CountingObjectServices extends TestObjectServices {
        private int sfxCount;

        @Override
        public void playSfx(int soundId) {
            sfxCount++;
        }

        private CountingObjectServices resetSfxCount() {
            sfxCount = 0;
            return this;
        }
    }
}

