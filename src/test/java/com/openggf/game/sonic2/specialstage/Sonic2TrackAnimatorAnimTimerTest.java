package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** ROM cadence tests for {@code SSRun_Animation_Timers} (s2.asm:960-982). */
class Sonic2TrackAnimatorAnimTimerTest {
    @Test
    void speedTwelveReloadsBothRomTimersAndKeepsPlayerTimerUntilNextExpiry() {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();

        assertEquals(0, animator.getPlayerAnimFrameTimer(),
                "SS_player_anim_frame_timer is cleared before the first VInt");

        animator.setSpeedFactor(12); // SSAnim_Base_Duration[6] == 5
        animator.tickVintTimer();

        assertEquals(0, animator.getFrameDelayCounter(),
                "speed change resets and immediately reloads SSTrack_duration_timer");
        assertEquals(4, animator.getPlayerAnimFrameTimer(),
                "reload writes duration to both bytes, then decrements the player byte once");

        for (int elapsed = 1; elapsed <= 4; elapsed++) {
            animator.tickVintTimer();
            assertEquals(elapsed, animator.getFrameDelayCounter());
            assertEquals(4, animator.getPlayerAnimFrameTimer(),
                    "non-expiry path reads player timer + 1 into d1 without storing it");
        }

        animator.tickVintTimer();
        assertEquals(0, animator.getFrameDelayCounter());
        assertEquals(4, animator.getPlayerAnimFrameTimer());
    }

    @Test
    void speedChangeResetsTrackPhaseAndReloadsPlayerTimerForNewDuration() {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();
        animator.setSpeedFactor(12);
        animator.tickVintTimer();
        animator.tickVintTimer();
        animator.tickVintTimer();
        assertEquals(2, animator.getFrameDelayCounter());

        animator.setSpeedFactor(10); // SSAnim_Base_Duration[5] == 6
        animator.tickVintTimer();

        assertEquals(0, animator.getFrameDelayCounter());
        assertEquals(5, animator.getPlayerAnimFrameTimer());
    }

    @Test
    void zeroBaseDurationReloadUnderflowsPlayerByteLikeTheRom() {
        Sonic2TrackAnimator animator = new Sonic2TrackAnimator(null);
        animator.initializeWithMockLayout();

        animator.setSpeedFactor(14); // SSAnim_Base_Duration[7] == 0
        animator.tickVintTimer();

        assertEquals(0, animator.getFrameDelayCounter());
        assertEquals(0xFF, animator.getPlayerAnimFrameTimer(),
                "reload 0 followed by subq.b #1 wraps the RAM byte to $FF");
    }
}
