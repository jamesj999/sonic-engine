package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotRingRewardObjectInstance {

    /**
     * ROM Obj_SlotRing routine 0 -&gt; 1 (sonic3k.asm:35862-35887): the cage's active-reward
     * count ($30(a0), reached through this object's $2E(a0) pointer) is decremented with
     * {@code subq.w #1,(a1)} at the exact grant instant -- immediately before {@code GiveRing}
     * and the {@code addi.b #2,routine(a0)} bump into the cosmetic sparkle handler
     * (loc_1AA56) -- not when the sparkle visual later finishes. Decrementing only on full
     * object destruction (after the sparkle plays out) held the cage's active count high for
     * the extra sparkle-duration frames, which is exactly the S3K slots frame-332 regression
     * this test guards (S3kSlotBonusCageObjectInstance's release-angle-alignment wait missed
     * its window and delayed cage ejection by a full 16-frame rotation).
     */
    @Test
    void grantingRingDecrementsActiveCountImmediatelyNotAtSparkleEnd() {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0x00, 0x00, 0x00, false, 0);
        S3kSlotStageController controller = new S3kSlotStageController();
        controller.bootstrap();
        controller.onRewardSpawned();
        assertEquals(1, controller.activeRewardObjects());

        S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(spawn, controller);
        reward.setServices(new TestObjectServices());
        reward.activate(0x460, 0x430, 0x460, 0x430);

        Sonic player = new Sonic("sonic", (short) 0x460, (short) 0x430);

        // EXPIRY_FRAMES = 0x1A: tick until (and including) the grant frame.
        for (int i = 0; i < 0x1A; i++) {
            reward.tickSlotRuntime(i, player);
        }

        // The grant must have already fired and reported the cage's active-count
        // decrement, even though the object is still alive playing its sparkle.
        assertEquals(0, controller.activeRewardObjects());
        assertTrue(reward.isInSparkle());
        assertFalse(reward.isDestroyed());

        // Ticking through the remaining sparkle frames must NOT decrement again.
        for (int i = 0; i < 16; i++) {
            reward.tickSlotRuntime(0x1A + i, player);
        }
        assertEquals(0, controller.activeRewardObjects());
        assertTrue(reward.isDestroyed());
    }
}
