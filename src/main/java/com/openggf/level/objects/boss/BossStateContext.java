package com.openggf.level.objects.boss;

/**
 * State holder for boss objects.
 * Supports fixed-point math (16.16 format for sub-pixel precision).
 * Matches ROM format: position is longword, velocity is word in "pixels * 256" format.
 */
public class BossStateContext {
    // Position (pixels)
    public int x;
    public int y;

    // Fixed-point position (16.16 format: upper 16 bits = integer, lower 16 bits = fraction)
    public int xFixed;
    public int yFixed;

    // Velocity (in ROM format: value * 256 = pixels per frame, e.g., 0x200 = 2.0 pixels)
    public int xVel;
    public int yVel;

    // State tracking
    public int routine;
    public int routineSecondary;
    public int routineTertiary;
    public int hitCount;
    public boolean defeated;
    public boolean invulnerable;
    public int invulnerabilityTimer;

    // Render flags
    public int renderFlags;
    public int lastUpdatedVIntRunCount = -1;

    // Hover/sine wave state
    public int sineCounter;

    public BossStateContext(int initialX, int initialY, int initialHitCount) {
        this.x = initialX;
        this.y = initialY;
        this.xFixed = initialX << 16;
        this.yFixed = initialY << 16;
        this.xVel = 0;
        this.yVel = 0;
        this.routine = 0;
        this.routineSecondary = 0;
        this.routineTertiary = 0;
        this.hitCount = initialHitCount;
        this.defeated = false;
        this.invulnerable = false;
        this.invulnerabilityTimer = 0;
        this.renderFlags = 0;
        this.sineCounter = 0;
    }

    /**
     * Update position from fixed-point values.
     * ROM: integer position = upper 16 bits of longword.
     */
    public void updatePositionFromFixed() {
        this.x = this.xFixed >> 16;
        this.y = this.yFixed >> 16;
    }

    /**
     * Apply velocity using fixed-point math.
     */
    public void applyVelocity() {
        this.xFixed += (this.xVel << 8);
        this.yFixed += (this.yVel << 8);
        updatePositionFromFixed();
    }
}
