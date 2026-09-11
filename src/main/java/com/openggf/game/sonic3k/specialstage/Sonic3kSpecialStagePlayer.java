package com.openggf.game.sonic3k.specialstage;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;
import com.openggf.game.GameServices;

/**
 * Player state and physics for the S3K Blue Ball special stage.
 * <p>
 * Implements ROM-accurate movement on the 32x32 toroidal grid including
 * forward/backward motion, 90-degree turning at cell boundaries, jumping,
 * spring bouncing, and bumper collision response.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm sub_9580 (line 11914), Obj_SStage_8FAA (line 11413)
 */
public class Sonic3kSpecialStagePlayer {

    // ==================== Position State ====================

    /** X position in subpixels (256 = 1 grid cell). */
    private int xPos;
    /** Y position in subpixels (256 = 1 grid cell). */
    private int yPos;
    /** Grid rotation angle (0-255). 0x00=N, 0x40=W, 0x80=S, 0xC0=E. */
    private int angle;
    /** Forward/backward velocity (signed). */
    private int velocity;
    /** Maximum speed (increases over time). */
    private int rate;
    /** Frames until next speed increase. */
    private int rateTimer;

    // ==================== Movement Flags ====================

    /** Turn direction: +4=left, -4=right, 0=none. */
    private int turning;
    /** If true, player cannot turn (mid-turn lock). */
    private boolean turnLock;
    /** If true, player is auto-advancing (up is virtually held). */
    private boolean advancing;
    /** Set when player first begins moving. */
    private boolean started;
    /** If true, player cannot advance (bumper lock). */
    private boolean bumperLock;
    /**
     * Whether the last {@link #update} call's ROM {@code sub_9580} reached the
     * cell-check tail at {@code bsr.s sub_972E} (sonic3k.asm:12078), rather
     * than leaving the routine through one of its earlier {@code rts} /
     * {@code bra locret_972C} exits. Those exits skip the cell check for that
     * frame -- see {@link #reachedCellCheck()}.
     */
    private boolean reachedCellCheck = true;
    /** Grid index of the bumper that locked the player. */
    private int bumperInteractIndex;

    // ==================== Jump State ====================

    /** Jump state: 0=ground, 1=request, 0x80=normal jump, 0x81=spring jump. */
    private int jumping;
    /** Jump height (subpixel offset, long-format for precision). */
    private long jumpHeight;
    /** Jump velocity (long-format). */
    private long jumpVelocity;

    // ==================== Animation State ====================

    /** Current animation frame timer (wraps at 12). */
    private int animFrameTimer;
    /** Current mapping frame index. */
    private int mappingFrame;
    /** Previous mapping frame (for DPLC change detection). */
    private int prevMappingFrame = 0xFF;

    // ==================== Stage Completion ====================

    /** If true, player has hit a red sphere (stage failed). */
    private boolean failed;
    /** If set, jumping is disabled (during clear sequence). */
    private boolean clearRoutineActive;
    /** Fade timer for exit sequence. */
    private int fadeTimer;

    /** Whether this is using Blue Spheres standalone mode timing. */
    private boolean blueSphereMode;

    public Sonic3kSpecialStagePlayer() {}

    /**
     * Initialize player state from layout parameters.
     *
     * @param startAngle initial angle (from layout trailer word 0)
     * @param startX initial X position (from layout trailer word 1)
     * @param startY initial Y position (from layout trailer word 2)
     * @param blueSpheres true for Blue Spheres standalone mode
     */
    public void initialize(int startAngle, int startX, int startY, boolean blueSpheres) {
        // ROM stores angle as a word but reads with move.b (high byte in big-endian)
        this.angle = (startAngle >> 8) & 0xFF;
        this.xPos = startX & 0xFFFF;
        this.yPos = startY & 0xFFFF;
        this.velocity = 0;
        this.rate = INITIAL_RATE;
        this.rateTimer = blueSpheres ? RATE_TIMER_BLUE_SPHERES : RATE_TIMER_NORMAL;
        this.blueSphereMode = blueSpheres;

        this.turning = 0;
        this.turnLock = false;
        this.advancing = false;
        this.started = false;
        this.bumperLock = false;
        this.bumperInteractIndex = -1;
        this.jumping = 0;
        this.jumpHeight = 0;
        this.jumpVelocity = 0;
        this.animFrameTimer = 0;
        this.mappingFrame = 0;
        this.prevMappingFrame = 0xFF;
        this.failed = false;
        this.clearRoutineActive = false;
        this.fadeTimer = 0;
    }

    /**
     * Process one frame of player movement.
     * ROM: sub_9580 (sonic3k.asm:11914)
     *
     * @param heldButtons currently held button bitmask
     * @param pressedButtons buttons pressed this frame
     */
    public void update(int heldButtons, int pressedButtons) {
        // Speed increase timer
        updateRateTimer();

        // Main movement logic
        updateMovement(heldButtons, pressedButtons);

        // Update animation
        updateAnimation();
    }

    /**
     * Speed increase timer: every 30s (or 45s in Blue Spheres mode),
     * rate increases by 0x400, up to max 0x2000.
     * ROM: loc_903E (sonic3k.asm:11445)
     */
    /** Whether the rate just increased (for the manager to trigger tempo change). */
    private boolean rateJustIncreased;

    private void updateRateTimer() {
        rateJustIncreased = false;
        if (rateTimer <= 0) {
            return;
        }
        rateTimer--;
        if (rateTimer > 0) {
            return;
        }
        // Reset timer
        rateTimer = blueSphereMode ? RATE_TIMER_BLUE_SPHERES : RATE_TIMER_NORMAL;
        // Increase rate
        if (rate < MAX_RATE) {
            rate += RATE_INCREMENT;
            rateJustIncreased = true;
        }
    }

    public boolean didRateJustIncrease() {
        return rateJustIncreased;
    }

    /**
     * Calculate the music tempo for the current rate.
     * ROM: move.b (rate).w,d0 / subi.b #$20,d0 / neg.b d0 / add.b d0,d0 / addq.b #8,d0
     * Lower return value = faster music.
     */
    public int calculateMusicTempo() {
        int rateHighByte = (rate >> 8) & 0xFF;
        int d0 = (0x20 - rateHighByte) & 0xFF;
        return ((d0 * 2) + 8) & 0xFF;
    }

    /**
     * Core movement logic from sub_9580.
     *
     * @param heldButtons held button bitmask
     * @param pressedButtons pressed button bitmask
     */
    private void updateMovement(int heldButtons, int pressedButtons) {
        // sub_9580 reaches its cell-check tail unless one of the early exits
        // below fires. See reachedCellCheck().
        reachedCellCheck = true;
        // Handle fade exit
        if (fadeTimer > 0) {
            if (fadeTimer < 0x61) {
                angle = (angle + 8) & 0xFF;
                fadeTimer++;
                // ROM: `addq.b #1,(Special_stage_fade_timer).w / rts`
                // (sonic3k.asm:11921-11922) leaves sub_9580 before the cell
                // check at sonic3k.asm:12078.
                reachedCellCheck = false;
            } else {
                // Wait for position alignment before completing fade
                int pos = xPos | yPos;
                if ((pos & CELL_ALIGN_MASK) == 0) {
                    fadeTimer = 0;
                }
            }
            return;
        }

        // Determine relevant axis position for turn/alignment checks
        int axisPos;
        if ((angle & ANGLE_AXIS_BIT) != 0) {
            axisPos = xPos;
        } else {
            axisPos = yPos;
        }

        // Process turning
        // ROM: sonic3k.asm:11940-11954. After rotating the angle, the ROM only
        // returns early (bne.w locret_972C, line 11949) while the turn is NOT
        // yet complete (angle not aligned to a cardinal direction). Once the
        // turn completes on this same frame, it falls straight through into
        // loc_95F4 (line 11955, no intervening rts) and continues processing
        // movement/position update in that same frame -- it does not wait for
        // the next frame.
        if (turning != 0 && (axisPos & CELL_ALIGN_MASK) == 0) {
            if ((jumping & 0x80) != 0) {
                // Can't turn while jumping; skip to position update (loc_9600 falls through)
            } else {
                angle = (angle + turning) & 0xFF;
                if ((angle & ANGLE_ALIGN_MASK) != 0) {
                    // Turn not yet complete this frame - only rotate, return early.
                    // ROM: `bne.w locret_972C` (sonic3k.asm:11949) leaves
                    // sub_9580 before the cell check at sonic3k.asm:12078.
                    reachedCellCheck = false;
                    return;
                }
                // Turn complete - aligned to cardinal direction; fall through
                // to loc_95F4 and process movement/position update this frame.
                turning = 0;
                if (velocity != 0) {
                    turnLock = true;
                }
            }
        }

        // Unlock turn when leaving cell boundary
        if ((axisPos & CELL_ALIGN_MASK) != 0) {
            turnLock = false;
        }

        // Process input
        int vel = velocity;
        if (!clearRoutineActive) {
            if (!bumperLock) {
                // Up button: start advancing
                if ((heldButtons & 0x01) != 0) { // UP
                    advancing = true;
                    started = true;
                }

                // ROM loc_9628 (sonic3k.asm:11972-11985) dispatches on three
                // tests, not two. `advancing` goes straight to loc_964A; if it
                // is clear, an unstarted stage skips the whole block; and a
                // started stage then runs `tst.w d2 / bpl.s loc_964A`, so a
                // NON-NEGATIVE velocity also lands on the forward
                // acceleration. Only a negative velocity reaches the backward
                // deceleration below. Coasting therefore keeps clamping the
                // velocity up to the current rate rather than freezing it,
                // which is what lets the player track a mid-stage
                // Special_stage_rate step without touching the D-pad.
                if (advancing || (started && vel >= 0)) {
                    // ROM loc_964A (sonic3k.asm:11988-11993).
                    vel += ACCELERATION;
                    if (vel >= rate) {
                        vel = rate;
                    }
                } else if (started) {
                    // ROM sonic3k.asm:11979-11984, reached only when d2 is
                    // negative.
                    vel -= ACCELERATION;
                    if (vel <= -rate) {
                        vel = -rate;
                    }
                }
            }

            // Turn input (only when not locked)
            if (!turnLock) {
                if ((heldButtons & 0x04) != 0) { // LEFT
                    turning = TURN_LEFT;
                }
                if ((heldButtons & 0x08) != 0) { // RIGHT
                    turning = TURN_RIGHT;
                }
            }
        }

        velocity = vel;

        // Handle bumper lock: modifies velocity for position update
        // ROM: loc_9676 (sonic3k.asm:12007)
        Integer velForPosition = velocity;
        if (bumperLock) {
            velForPosition = handleBumperLock(velForPosition);
        }

        // Apply velocity to position. ROM: the different-cell unlock branch
        // (loc_96CE, sonic3k.asm:12037-12039) ends in `rts` -- it does NOT
        // fall through to the shared position-update code at loc_96FA the
        // way the same-cell branches (loc_96F2/loc_96F8) do. That frame
        // writes the new ±rate velocity to RAM but leaves X/Y untouched
        // until the *next* frame's update() call. handleBumperLock signals
        // that early return with a null velForPosition.
        if (velForPosition != null) {
            applyVelocityWithValue(velForPosition);
        }
    }

    /**
     * Handle bumper lock state. Returns the velocity to use for position update,
     * or {@code null} if the ROM took the ``different cell'' unlock's early
     * {@code rts} (sonic3k.asm:12039) and this frame must skip the position
     * update entirely.
     * <p>
     * ROM logic (loc_9676, sonic3k.asm:12007):
     * - If at cell boundary AND on a different cell than the bumper: unlock,
     *   set velocity to ±rate (reverse from current direction), and RETURN
     *   without updating position this frame (loc_96CE -&gt; rts, sonic3k.asm:12039).
     * - If at cell boundary AND on the same cell: if velocity=0, unlock and
     *   set advancing + reverse velocity, falling through to the position
     *   update (loc_96F2 -&gt; bra loc_96FA, sonic3k.asm:12052-12054). If
     *   velocity!=0, negate it for position update (loc_96F8, falls through
     *   to loc_96FA too).
     * - If NOT at cell boundary: same-cell logic (loc_96D4) applies directly.
     *
     * @param vel the current velocity
     * @return velocity to use for the position update (may be negated), or
     *         {@code null} to skip the position update this frame
     */
    private Integer handleBumperLock(int vel) {
        // Check relevant axis position for cell alignment
        int relevantPos;
        if ((angle & ANGLE_AXIS_BIT) != 0) {
            relevantPos = xPos;
        } else {
            relevantPos = yPos;
        }

        if ((relevantPos & CELL_ALIGN_MASK) != 0) {
            // Not at cell boundary — go to "same cell" logic (loc_96D4)
            return handleBumperSameCell(vel);
        }

        // At cell boundary — check if we've moved to a different cell
        int currentIndex = Sonic3kSpecialStageGrid.positionToIndex(xPos, yPos);
        if (currentIndex != bumperInteractIndex) {
            // Different cell: unlock and set velocity to ±rate (reverse
            // direction), then return WITHOUT applying it to position this
            // frame (ROM: loc_96CE's rts, sonic3k.asm:12039 -- there is no
            // bra to loc_96FA here, unlike the same-cell branches below).
            bumperLock = false;
            int newVel = rate;
            if (velocity >= 0) {
                newVel = -newVel;
            }
            velocity = newVel;
            // ROM: `move.w d2,(Special_stage_velocity).w / rts` (loc_96CE,
            // sonic3k.asm:12038-12039). Unlike the same-cell branches this
            // is an `rts`, not a `bra loc_96FA`, so it leaves sub_9580
            // before BOTH the position update and the cell check at
            // `bsr.s sub_972E` (sonic3k.asm:12078). The frame therefore
            // neither moves nor interacts with the cell it is standing on --
            // which is what lets the ROM re-arm on the same bumper one frame
            // later, from the far side of the sphere.
            reachedCellCheck = false;
            return null;
        }

        // Same cell at boundary
        return handleBumperSameCell(vel);
    }

    /**
     * Bumper lock: on the same cell (or not at boundary).
     * ROM: loc_96D4 (sonic3k.asm:12042)
     */
    private int handleBumperSameCell(int vel) {
        if (vel == 0) {
            // Velocity reached zero: unlock, start advancing, reverse.
            // ROM: loc_96F2 falls through (bra.s loc_96FA, sonic3k.asm:12054)
            // to the position update, unlike the different-cell branch above.
            bumperLock = false;
            advancing = true;
            int newVel = rate;
            if (velocity >= 0) {
                newVel = -newVel;
            }
            velocity = newVel;
            return newVel;
        }
        // Velocity non-zero: negate for position update (bounce backward)
        // ROM: loc_96F8: neg.w d2, falls through to loc_96FA (position update)
        // Note: velocity in RAM stays unchanged; only the position update uses -vel
        return -vel;
    }

    /**
     * Apply velocity to position using sine/cosine of the current angle.
     * Uses the stored velocity field (normal path).
     */
    private void applyVelocity() {
        applyVelocityWithValue(velocity);
    }

    /**
     * Apply a specific velocity value to position.
     * ROM: loc_96FA (sonic3k.asm:12060)
     * <p>
     * ROM GetSineCosine returns 8.8 fixed-point sine/cosine.
     * Position update: X -= (sin * vel) >> 16, Y -= (cos * vel) >> 16
     *
     * @param vel velocity to apply (may differ from stored velocity during bumper lock)
     */
    private void applyVelocityWithValue(int vel) {
        // Spring jump doubles movement speed
        if (jumping == JUMP_SPRING) {
            vel *= 2;
        }

        // Get 8.8 fixed-point sine and cosine for current angle
        int sin = getSine(angle);
        int cos = getCosine(angle);

        // ROM: muls.w d2,d0 / swap d0 / sub.w d0,(X_pos).w
        int dx = (sin * vel) >> 16;
        int dy = (cos * vel) >> 16;
        xPos = (xPos - dx) & 0xFFFF;
        yPos = (yPos - dy) & 0xFFFF;
    }

    /**
     * ROM-accurate sine lookup (8.8 fixed point, range ~-256 to +256).
     * The ROM's sine table contains 256 word entries.
     * We compute this with standard math scaled to match.
     *
     * @param byteAngle 0-255 angle (256 = full circle)
     * @return sine value in 8.8 fixed point
     */
    static int getSine(int byteAngle) {
        double radians = (byteAngle & 0xFF) * 2.0 * Math.PI / 256.0;
        return (int) Math.round(Math.sin(radians) * 256.0);
    }

    /**
     * ROM-accurate cosine lookup (8.8 fixed point).
     *
     * @param byteAngle 0-255 angle
     * @return cosine value in 8.8 fixed point
     */
    static int getCosine(int byteAngle) {
        double radians = (byteAngle & 0xFF) * 2.0 * Math.PI / 256.0;
        return (int) Math.round(Math.cos(radians) * 256.0);
    }

    /**
     * Process jump input and physics.
     * Called from the main player object update, not from sub_9580.
     * ROM: Obj_SStage_8FAA loc_90EE (sonic3k.asm:11507)
     *
     * @param pressedButtons buttons pressed this frame
     */
    public void updateJump(int pressedButtons) {
        // Request jump on A/B/C press
        if (!clearRoutineActive) {
            if ((pressedButtons & 0x70) != 0) { // A, B, or C
                if (jumping <= 0) {
                    jumping = JUMP_REQUEST;
                }
            }
        }

        // Execute jump at aligned angle
        if ((angle & ANGLE_ALIGN_MASK) == 0) {
            if (jumping == JUMP_REQUEST) {
                jumpVelocity = JUMP_VELOCITY;
                jumping = JUMP_NORMAL;
                turning = 0;
                GameServices.audio().playSfx(
                        com.openggf.game.sonic3k.audio.Sonic3kSfx.JUMP.id);
            }
        }

        // Jump physics — ROM uses tst.b which treats 0x80 as negative
        if ((jumping & 0x80) != 0) { // 0x80 (normal) or 0x81 (spring)
            jumpHeight += jumpVelocity;
            if (jumpHeight >= 0) {
                // Landed
                jumpHeight = 0;
                jumpVelocity = 0;
                jumping = 0;
            } else {
                // Apply gravity: rate * 16
                jumpVelocity += (long) rate << 4;
            }
        }
    }

    /**
     * Trigger a spring jump.
     * ROM: loc_97EE (sonic3k.asm:12158)
     */
    public void springJump() {
        if ((jumping & 0x80) != 0 || clearRoutineActive) {
            return;
        }
        if ((angle & ANGLE_ALIGN_MASK) != 0) {
            return;
        }
        jumpVelocity = SPRING_JUMP_VELOCITY;
        jumping = JUMP_SPRING;
        // sfx_Spring plays here
    }

    /**
     * Activate bumper lock.
     * ROM: loc_97C8 (sonic3k.asm:12146)
     *
     * @param gridIndex grid index of the bumper cell
     */
    public void activateBumperLock(int gridIndex) {
        if (bumperLock) {
            return;
        }
        bumperInteractIndex = gridIndex;
        bumperLock = true;
        advancing = false;
        // sfx_Bumper plays here
    }

    /**
     * Update animation frame based on velocity.
     * ROM: Obj_SStage_8FAA loc_907E (sonic3k.asm:11466)
     * <p>
     * ROM logic:
     * - anim_frame_timer is a WORD (16-bit), velocity>>5 is added each frame
     * - move.b reads the HIGH BYTE of the word (68000 big-endian) as the frame index
     * - If the byte is negative (signed), add 12
     * - If the byte >= 12, subtract 12
     * - This gives a value 0-11 indexing into the animation table
     * - If velocity is 0, frame index 12 (idle) is used
     * - move.b d0,anim_frame_timer(a0) writes back to the HIGH BYTE
     */
    private void updateAnimation() {
        int frameIdx;
        if (velocity == 0) {
            frameIdx = 12; // Idle frame
        } else {
            // ROM: asr.w #5,d1 / add.w d1,anim_frame_timer(a0)
            animFrameTimer = (animFrameTimer + (velocity >> 5)) & 0xFFFF;

            // ROM: move.b anim_frame_timer(a0),d0 — reads HIGH BYTE (big-endian!)
            frameIdx = (animFrameTimer >> 8) & 0xFF;

            // ROM: bpl.s (if positive as signed byte, check >= 12)
            if ((frameIdx & 0x80) != 0) {
                // Signed negative: addi.b #$C,d0
                frameIdx = (frameIdx + 12) & 0xFF;
            }
            // ROM: cmpi.b #$C,d0 / blo.s (if < 12, use as-is)
            if (frameIdx >= 12) {
                // subi.b #$C,d0
                frameIdx = (frameIdx - 12) & 0xFF;
            }

            // ROM: move.b d0,anim_frame_timer(a0) — writes back to HIGH BYTE
            animFrameTimer = (frameIdx << 8) | (animFrameTimer & 0xFF);
        }

        // Select animation table based on jump state
        int[] animTable;
        if ((jumping & 0x80) != 0) {
            animTable = ANIM_JUMP_P1;
            if (velocity == 0) {
                // ROM: idle during jump uses Level_frame_counter & 3
                frameIdx = animFrameTimer & 3;
            }
        } else {
            animTable = ANIM_WALKING;
        }

        // Clamp to table bounds
        if (frameIdx < 0) frameIdx = 0;
        if (frameIdx >= animTable.length) frameIdx = animTable.length - 1;

        prevMappingFrame = mappingFrame;
        mappingFrame = animTable[frameIdx];
    }

    // ==================== Getters ====================

    public int getXPos() { return xPos; }
    public int getYPos() { return yPos; }
    public int getAngle() { return angle; }
    public int getVelocity() { return velocity; }
    public int getRate() { return rate; }
    public int getTurning() { return turning; }
    public boolean isAdvancing() { return advancing; }
    public boolean isStarted() { return started; }
    public boolean isBumperLocked() { return bumperLock; }

    /**
     * Whether this frame's {@code sub_9580} fell through to its cell-check
     * tail (`bsr.s sub_972E`, sonic3k.asm:12078) instead of leaving through one
     * of the routine's earlier exits. The cell check is the LAST thing
     * sub_9580 does, so every early exit suppresses it for that frame.
     *
     * @return true when the cell check should run for this frame
     */
    public boolean reachedCellCheck() { return reachedCellCheck; }
    public int getJumping() { return jumping; }
    public long getJumpHeight() { return jumpHeight; }
    public int getMappingFrame() { return mappingFrame; }
    public int getPrevMappingFrame() { return prevMappingFrame; }
    public boolean isFailed() { return failed; }
    public int getFadeTimer() { return fadeTimer; }

    Sonic3kSpecialStageSnapshot.PlayerSnapshot captureRewindSnapshot() {
        return new Sonic3kSpecialStageSnapshot.PlayerSnapshot(
                xPos, yPos, angle, velocity, rate, rateTimer,
                turning, turnLock, advancing, started, bumperLock, bumperInteractIndex,
                jumping, jumpHeight, jumpVelocity,
                animFrameTimer, mappingFrame, prevMappingFrame,
                failed, clearRoutineActive, fadeTimer, blueSphereMode, rateJustIncreased);
    }

    void restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.PlayerSnapshot snapshot) {
        xPos = snapshot.xPos();
        yPos = snapshot.yPos();
        angle = snapshot.angle();
        velocity = snapshot.velocity();
        rate = snapshot.rate();
        rateTimer = snapshot.rateTimer();
        turning = snapshot.turning();
        turnLock = snapshot.turnLock();
        advancing = snapshot.advancing();
        started = snapshot.started();
        bumperLock = snapshot.bumperLock();
        bumperInteractIndex = snapshot.bumperInteractIndex();
        jumping = snapshot.jumping();
        jumpHeight = snapshot.jumpHeight();
        jumpVelocity = snapshot.jumpVelocity();
        animFrameTimer = snapshot.animFrameTimer();
        mappingFrame = snapshot.mappingFrame();
        prevMappingFrame = snapshot.prevMappingFrame();
        failed = snapshot.failed();
        clearRoutineActive = snapshot.clearRoutineActive();
        fadeTimer = snapshot.fadeTimer();
        blueSphereMode = snapshot.blueSphereMode();
        rateJustIncreased = snapshot.rateJustIncreased();
    }

    // ==================== Setters ====================

    public void setAdvancing(boolean advancing) { this.advancing = advancing; }
    public void setStarted(boolean started) { this.started = started; }
    public void setFailed(boolean failed) { this.failed = failed; }
    public void setClearRoutineActive(boolean active) { this.clearRoutineActive = active; }
    public void setFadeTimer(int timer) { this.fadeTimer = timer; }
    public void setVelocity(int velocity) { this.velocity = velocity; }

    /**
     * Get the Y offset for rendering based on jump height.
     * Maps the jump height (long) to a screen pixel offset.
     *
     * @return Y offset in pixels (negative = up)
     */
    public int getJumpYOffset() {
        return (int) (jumpHeight >> 16) - 0x800;
    }
}
