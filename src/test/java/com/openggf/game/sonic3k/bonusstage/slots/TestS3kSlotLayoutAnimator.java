package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestS3kSlotLayoutAnimator {

    @Test
    void ringSparkleAnimatesThroughFramesThenClearsCell() {
        byte[] layout = new byte[32 * 32];
        layout[100] = 8; // ring tile
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        animator.queueRingSparkle(layout, 100);
        // First frame applied immediately
        assertEquals(0x10, layout[100]);

        // Tick through all 4 sparkle frames (5-frame delay each)
        // Frame 0 (0x10) applied on queue, frame 1 at tick 5, frame 2 at 10, frame 3 at 15
        // End marker (0) at tick 20 → restore to 0
        for (int i = 0; i < 25; i++) {
            animator.tick(layout);
        }
        assertEquals(0, layout[100]); // ring consumed (restoreTile = 0)
        assertEquals(0, animator.activeCount());
    }

    @Test
    void bumperBounceAnimatesAndRestoresTile5() {
        byte[] layout = new byte[32 * 32];
        layout[50] = 5; // bumper tile
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        animator.queueBumperBounce(layout, 50);
        assertEquals(0x0A, layout[50]); // first frame

        // 2 frames at 1-tick delay, then end → restore to 5
        for (int i = 0; i < 5; i++) {
            animator.tick(layout);
        }
        assertEquals(5, layout[50]); // bumper restored
        assertEquals(0, animator.activeCount());
    }

    @Test
    void spikeAnimationRestoresTile6() {
        byte[] layout = new byte[32 * 32];
        layout[75] = 6; // spike tile
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        animator.queueSpikeAnimation(layout, 75);
        assertEquals(0x0C, layout[75]); // first frame

        // 3 frames at 7-tick delay, then end → restore to 6
        for (int i = 0; i < 30; i++) {
            animator.tick(layout);
        }
        assertEquals(6, layout[75]); // spike restored
    }

    @Test
    void multipleAnimationsRunConcurrently() {
        byte[] layout = new byte[32 * 32];
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        animator.queueRingSparkle(layout, 10);
        animator.queueBumperBounce(layout, 20);

        assertEquals(2, animator.activeCount());

        // Bumper finishes faster (1-tick delay, 2 frames)
        for (int i = 0; i < 3; i++) animator.tick(layout);
        assertEquals(1, animator.activeCount()); // only ring sparkle left
    }

    @Test
    void queueReturnsFalseWhenAllSlotsFull() {
        byte[] layout = new byte[32 * 32];
        S3kSlotLayoutAnimator animator = new S3kSlotLayoutAnimator();

        for (int i = 0; i < 32; i++) {
            assertTrue(animator.queueRingSparkle(layout, i));
        }
        // 33rd should fail
        assertFalse(animator.queueRingSparkle(layout, 100));
    }
}


