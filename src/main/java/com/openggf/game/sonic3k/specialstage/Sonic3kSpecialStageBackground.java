package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Background scrolling for the S3K Blue Ball special stage.
 * <p>
 * The starfield background scrolls based on the player's angle and movement.
 * V-scroll tracks the player's movement along the forward axis.
 * H-scroll is derived directly from the player's angle.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm sub_9D5E (line 12694)
 */
public class Sonic3kSpecialStageBackground {

    /** Vertical scroll offset for background. */
    private int vScroll;
    /** Horizontal scroll offset for background. */
    private int hScroll;
    /** Previous X position for delta calculation. */
    private int prevXPos;
    /** Previous Y position for delta calculation. */
    private int prevYPos;

    /**
     * Initialize with the player's starting position.
     */
    public void initialize(int startX, int startY) {
        prevXPos = startX;
        prevYPos = startY;
        vScroll = 0;
        hScroll = 0;
    }

    /**
     * Update background scroll values based on player movement.
     * ROM: sub_9D5E (sonic3k.asm:12694)
     *
     * @param player the current player state
     */
    public void update(Sonic3kSpecialStagePlayer player) {
        int xPos = player.getXPos();
        int yPos = player.getYPos();
        int angle = player.getAngle();

        // Calculate position delta along the relevant axis
        int delta;
        if ((angle & ANGLE_AXIS_BIT) != 0) {
            // X-axis is forward
            delta = xPos - prevXPos;
        } else {
            // Y-axis is forward
            delta = yPos - prevYPos;
        }

        // Apply direction based on angle sign bit
        if ((angle & 0x80) == 0) {
            delta = -delta;
        }

        // Update V-scroll: delta >> 2
        vScroll += (delta >> 2);

        // Update H-scroll: directly from angle * 4
        hScroll = (angle & 0xFF) << 2;

        // Store current position for next frame's delta
        prevXPos = xPos;
        prevYPos = yPos;
    }

    public int getVScroll() {
        return vScroll;
    }

    public int getHScroll() {
        return hScroll;
    }

    Sonic3kSpecialStageSnapshot.BackgroundSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.BackgroundSnapshot(vScroll, hScroll, prevXPos, prevYPos);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.BackgroundSnapshot snapshot) {
        vScroll = snapshot.vScroll();
        hScroll = snapshot.hScroll();
        prevXPos = snapshot.prevXPos();
        prevYPos = snapshot.prevYPos();
    }

    public void reset() {
        vScroll = 0;
        hScroll = 0;
        prevXPos = 0;
        prevYPos = 0;
    }
}
