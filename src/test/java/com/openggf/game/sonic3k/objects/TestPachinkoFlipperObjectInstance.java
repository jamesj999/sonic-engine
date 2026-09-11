package com.openggf.game.sonic3k.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class TestPachinkoFlipperObjectInstance {

    /**
     * Held (not just-pressed) jump must not launch. ROM sub_49CFE's already-locked
     * branch only reaches the launch trigger (loc_49D68 -> sub_49D72) when an A/B/C
     * button was JUST pressed; otherwise it runs loc_49D54/loc_49DE4, which
     * accelerate/project/move the locked player without ever setting Status_InAir
     * (sonic3k.asm:96437-96534). The flipper therefore does drive x_vel/y_vel every
     * locked frame now, so absence-of-launch is asserted via Status_InAir, not via
     * "no velocity write".
     */
    @Test
    public void heldJumpDoesNotLaunchImmediatelyOnContact() {
        PachinkoFlipperObjectInstance flipper = new PachinkoFlipperObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xE7, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getRolling()).thenReturn(false);
        when(player.getY()).thenReturn((short) 0x100);
        when(player.getX()).thenReturn((short) 0x100);
        when(player.getRollHeightAdjustment()).thenReturn((short) 10);
        when(player.isJumpPressed()).thenReturn(true, true, false);
        when(player.isJumpJustPressed()).thenReturn(false, false, false);

        flipper.setServices(new TestObjectServices());
        SolidContact standing = new SolidContact(true, false, false, false, false, 0, false);

        flipper.onSolidContact(player, standing, 0);
        flipper.update(0, player);
        flipper.onSolidContact(player, standing, 1);
        flipper.update(1, player);
        flipper.onSolidContact(player, standing, 2);

        // No launch: sub_49D72 is the only flipper path that sets Status_InAir,
        // and it is never reached without a just-pressed jump.
        verify(player, never()).setAir(true);
    }

    @Test
    public void launchDistanceUsesPlayerCentreX() {
        PachinkoFlipperObjectInstance flipper = new PachinkoFlipperObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xE7, 0, 0, false, 0));
        flipper.setServices(new TestObjectServices());

        LaunchVelocity narrowBounds = launchWithPlayerPosition(flipper, 0x100, 0x110);

        flipper = new PachinkoFlipperObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xE7, 0, 0, false, 0));
        flipper.setServices(new TestObjectServices());
        LaunchVelocity wideBounds = launchWithPlayerPosition(flipper, 0x108, 0x110);

        assertEquals(narrowBounds.xSpeed(), wideBounds.xSpeed(),
                "Changing top-left X without changing ROM x_pos must not change launch X velocity");
        assertEquals(narrowBounds.ySpeed(), wideBounds.ySpeed(),
                "Changing top-left X without changing ROM x_pos must not change launch Y velocity");
    }

    private static LaunchVelocity launchWithPlayerPosition(PachinkoFlipperObjectInstance flipper,
            int topLeftX, int centreX) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getRolling()).thenReturn(true);
        when(player.getAir()).thenReturn(false);
        when(player.getX()).thenReturn((short) topLeftX);
        when(player.getCentreX()).thenReturn((short) centreX);
        when(player.isJumpJustPressed()).thenReturn(true);

        SolidContact standing = new SolidContact(true, false, false, false, false, 0, false);
        flipper.onSolidContact(player, standing, 0);
        flipper.onSolidContact(player, standing, 1);

        // ROM loc_49D68's fall-through to loc_49DE4 (sonic3k.asm:96460-96462,
        // 96532-96541) projects ground_vel onto angle into x_vel/y_vel and moves
        // the player BEFORE sub_49D72 (sonic3k.asm:96469-96504) overwrites
        // x_vel/y_vel with the distance-based launch velocity, so setXSpeed/
        // setYSpeed are each invoked twice on a launch frame. ArgumentCaptor.
        // getValue() returns the LAST captured value, i.e. the launch overwrite.
        ArgumentCaptor<Short> xSpeed = ArgumentCaptor.forClass(Short.class);
        ArgumentCaptor<Short> ySpeed = ArgumentCaptor.forClass(Short.class);
        verify(player, times(2)).setXSpeed(xSpeed.capture());
        verify(player, times(2)).setYSpeed(ySpeed.capture());
        return new LaunchVelocity(xSpeed.getValue(), ySpeed.getValue());
    }

    private record LaunchVelocity(short xSpeed, short ySpeed) {
    }

    /**
     * ROM sub_49CFE's already-locked branch (loc_49D3C -> loc_49D54 ->
     * loc_49DE4, sonic3k.asm:96437-96521) is only reachable while the player's
     * own object_control(a1) bit 0 stays set, which makes the player's control
     * routine skip Sonic_Modes (RollRepel/RollSpeed/etc) entirely for that
     * frame (sonic3k.asm:21973-21976). The engine keeps running its normal
     * per-frame roll update instead, so pinballSpeedLock must be asserted for
     * the whole locked ride to suppress RollSpeed's friction/deceleration/
     * stop-rolling block -- without it, ground_vel gets corrupted by the
     * roll-stop pinball-mode +-0x400 snap on the very next frame.
     */
    @Test
    public void lockingAndReleasingTogglesPinballSpeedLock() {
        PachinkoFlipperObjectInstance flipper = new PachinkoFlipperObjectInstance(
                new ObjectSpawn(0x100, 0x100, 0xE7, 0, 0, false, 0));
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getRolling()).thenReturn(false, true);
        when(player.getY()).thenReturn((short) 0x100);
        when(player.getX()).thenReturn((short) 0x100);
        when(player.getRollHeightAdjustment()).thenReturn((short) 10);
        when(player.isJumpJustPressed()).thenReturn(false);
        when(player.getAir()).thenReturn(false);

        flipper.setServices(new TestObjectServices());
        SolidContact standing = new SolidContact(true, false, false, false, false, 0, false);

        // Lock frame: control/pinball flags asserted, no acceleration yet.
        flipper.onSolidContact(player, standing, 0);
        verify(player).setPinballSpeedLock(true);
        verify(player, never()).setGSpeed(anyShort());
        flipper.update(0, player);

        // Already-locked frame: still asserted, acceleration applied with no
        // direction write (ROM loc_49D54 never touches status(a1)).
        flipper.onSolidContact(player, standing, 1);
        verify(player, times(2)).setPinballSpeedLock(true);
        verify(player, atLeastOnce()).setGSpeed(anyShort());
        verify(player, never()).setDirection(any());
        flipper.update(1, player);

        // Player leaves contact (no onSolidContact call this frame): lock
        // must be released on the following update().
        flipper.update(2, player);
        verify(player).setPinballSpeedLock(false);
        verify(player).setControlLocked(false);
    }
}
