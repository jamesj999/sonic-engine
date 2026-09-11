package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * "Get Blue Spheres" banner for the S3K Blue Ball special stage.
 * <p>
 * ROM: Obj_SStage_8E40 (sonic3k.asm:11310)
 * <p>
 * The banner starts DISPLAYED at the center of the screen. After 3 seconds
 * it slides OUT to the edges (left half goes left, right half goes right).
 * When fully exited, the player begins auto-advancing.
 * <p>
 * Later, when all converted rings are collected (rings_left == 0), the banner
 * re-enters from the edges and displays again.
 */
public class Sonic3kSpecialStageBanner {

    public enum Phase {
        /** Banner is displayed at center, timer counting down. */
        DISPLAYING,
        /** Banner is sliding out to the edges. */
        SLIDING_OUT,
        /** Banner has exited, normal gameplay. */
        IDLE,
        /** Banner is sliding back in (all rings collected). */
        SLIDING_IN
    }

    private Phase phase;
    /** Slide offset from center (0 = at center, BANNER_MAX_OFFSET = fully off-screen). */
    private int slideOffset;
    private int displayTimer;
    private boolean triggeredAdvance;
    /** True after re-entry has been triggered (show PERFECT instead of GET BLUE SPHERES). */
    private boolean showPerfect;

    public void initialize() {
        phase = Phase.DISPLAYING;
        slideOffset = 0;
        displayTimer = BANNER_DISPLAY_FRAMES;
        triggeredAdvance = false;
        showPerfect = false;
    }

    /**
     * Update the banner state machine.
     *
     * @return true if the banner just finished sliding out (trigger player advance)
     */
    public boolean update() {
        switch (phase) {
            case DISPLAYING:
                displayTimer--;
                if (displayTimer <= 0) {
                    phase = Phase.SLIDING_OUT;
                }
                break;

            case SLIDING_OUT:
                slideOffset += BANNER_SLIDE_SPEED;
                if (slideOffset >= BANNER_MAX_OFFSET) {
                    slideOffset = BANNER_MAX_OFFSET;
                    phase = Phase.IDLE;
                    if (!triggeredAdvance) {
                        triggeredAdvance = true;
                        return true; // Signal: trigger player auto-advance
                    }
                }
                break;

            case SLIDING_IN:
                slideOffset -= BANNER_SLIDE_SPEED;
                if (slideOffset <= 0) {
                    slideOffset = 0;
                    phase = Phase.DISPLAYING;
                    displayTimer = BANNER_DISPLAY_FRAMES;
                }
                break;

            case IDLE:
                break;
        }
        return false;
    }

    /**
     * Trigger the banner to re-enter (when all rings from conversion are collected).
     */
    public void triggerReEntry() {
        if (phase == Phase.IDLE) {
            phase = Phase.SLIDING_IN;
            slideOffset = BANNER_MAX_OFFSET;
            showPerfect = true;
        }
    }

    public Phase getPhase() { return phase; }
    public int getSlideOffset() { return slideOffset; }
    public boolean isVisible() { return phase != Phase.IDLE; }
    public boolean isShowPerfect() { return showPerfect; }

    Sonic3kSpecialStageSnapshot.BannerSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.BannerSnapshot(
                phase, slideOffset, displayTimer, triggeredAdvance, showPerfect);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.BannerSnapshot snapshot) {
        phase = snapshot.phase();
        slideOffset = snapshot.slideOffset();
        displayTimer = snapshot.displayTimer();
        triggeredAdvance = snapshot.triggeredAdvance();
        showPerfect = snapshot.showPerfect();
    }
}
