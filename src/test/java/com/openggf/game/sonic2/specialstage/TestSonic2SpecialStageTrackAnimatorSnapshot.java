package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2SpecialStageTrackAnimatorSnapshot {
    @Test
    void speedPromotionReloadsVintTimerWithoutAdvancingTrackDrawFrame() {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();

        animator.setSpeedFactor(12);

        animator.tickVintTimer();
        assertEquals(0, animator.getFrameDelayCounter());
        assertEquals(0, animator.getCurrentFrameInSegment());

        for (int update = 0; update < 4; update++) {
            animator.tickVintTimer();
        }
        assertEquals(4, animator.getFrameDelayCounter());
        assertEquals(0, animator.getCurrentFrameInSegment(),
                "VInt timer expiry does not stand in for SSTrack_Draw");

        assertFalse(animator.drawTrackFrame(4));
        assertEquals(0, animator.getCurrentFrameInSegment());
        assertTrue(animator.drawTrackFrame(0));
        assertEquals(1, animator.getCurrentFrameInSegment());
    }

    @Test
    void restoresTrackAnimatorStateAndClonesLayout() throws Exception {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        set(animator, "currentSegmentIndex", 4);
        set(animator, "currentFrameInSegment", 7);
        set(animator, "frameDelayCounter", 2);
        set(animator, "playerAnimFrameTimer", 4);
        set(animator, "currentSegmentType", 3);
        set(animator, "currentSegmentFlipped", true);
        set(animator, "speedFactor", 9);
        set(animator, "speedChangePending", true);
        set(animator, "stageComplete", true);
        set(animator, "orientationFlipped", true);
        set(animator, "lastOrientationFrame", 12);

        Sonic2SpecialStageSnapshot.TrackAnimatorSnapshot snapshot =
                animator.captureRewindSnapshot();

        set(animator, "currentSegmentIndex", 99);
        set(animator, "currentFrameInSegment", 99);
        set(animator, "frameDelayCounter", 99);
        set(animator, "playerAnimFrameTimer", 99);
        set(animator, "currentSegmentType", 99);
        set(animator, "currentSegmentFlipped", false);
        set(animator, "speedFactor", 1);
        set(animator, "speedChangePending", false);
        set(animator, "stageComplete", false);
        set(animator, "orientationFlipped", false);
        set(animator, "lastOrientationFrame", -1);

        animator.restoreRewindSnapshot(snapshot);

        assertEquals(4, get(animator, "currentSegmentIndex"));
        assertEquals(7, get(animator, "currentFrameInSegment"));
        assertEquals(2, get(animator, "frameDelayCounter"));
        assertEquals(4, get(animator, "playerAnimFrameTimer"));
        assertEquals(3, get(animator, "currentSegmentType"));
        assertEquals(true, get(animator, "currentSegmentFlipped"));
        assertEquals(9, get(animator, "speedFactor"));
        assertEquals(true, get(animator, "speedChangePending"));
        assertEquals(true, get(animator, "stageComplete"));
        assertEquals(true, get(animator, "orientationFlipped"));
        assertEquals(12, get(animator, "lastOrientationFrame"));

        byte[] liveLayout = (byte[]) get(animator, "stageLayout");
        assertArrayEquals(snapshot.stageLayout(), liveLayout);
        assertNotSame(snapshot.stageLayout(), liveLayout);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object get(Object target, String field) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        return f.get(target);
    }
}
