package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kBossDefeatSignpostFlow {

    @Test
    void displacedBossCollisionEntriesAdvanceEndSignControlWait() {
        S3kBossDefeatSignpostFlow flow = new S3kBossDefeatSignpostFlow(
                0x1180, 0,
                S3kBossDefeatSignpostFlow.CleanupAction.RESTORE_AIZ_FIRE_PALETTE,
                12, 0, 0, 0);

        assertEquals(0x77 - 12, flow.waitTimerAfterInitialization(),
                "the boss-owned collision bridge entries advance Obj_EndSignControl's "
                        + "native $77 wait instead of shortening Obj_EndSign's landed timer");
    }

    @Test
    void restorePlayerControlMatchesEndSignControlAwaitStartWrites() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setInteractSlotIndex(23);
        player.setControlLocked(true);
        player.setAir(true);
        player.setAnimationId(Sonic3kAnimationIds.VICTORY);
        player.getAnimationManager().publishPreviousAnimationId(Sonic3kAnimationIds.VICTORY.id());
        player.setAnimationFrameIndex(7);
        player.setAnimationTick(11);
        player.setXSpeed((short) -7);
        player.setYSpeed((short) 2);

        S3kBossDefeatSignpostFlow.restoreNativePlayerControl(player);

        assertFalse(player.isObjectControlled());
        assertEquals(0, player.getInteractSlotIndex());
        assertTrue(player.isControlLocked(),
                "Restore_PlayerControl must not clear the title-card controller lock");
        assertFalse(player.getAir(),
                "Restore_PlayerControl clears Status_InAir");
        assertEquals(Sonic3kAnimationIds.WAIT.id(), player.getAnimationId());
        assertEquals(Sonic3kAnimationIds.WAIT.id(),
                player.getAnimationManager().captureRewindState().lastAnimationId(),
                "Restore_PlayerControl writes anim and prev_anim together");
        assertEquals(0, player.getAnimationFrameIndex());
        assertEquals(0, player.getAnimationTick());
        assertEquals(-7, player.getXSpeed());
        assertEquals(2, player.getYSpeed());
    }
}
