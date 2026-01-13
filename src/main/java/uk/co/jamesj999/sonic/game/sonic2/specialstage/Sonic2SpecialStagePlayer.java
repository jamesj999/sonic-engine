package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.timer.Timer;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.timer.timers.SSInvulnerabilityTimer;

import java.util.logging.Logger;

/**
 * Represents a player (Sonic or Tails) in Sonic 2 Special Stage.
 *
 * Special Stage uses a different coordinate system than normal levels:
 * - ss_x_pos/ss_y_pos: Track-space position (relative to track center)
 * - ss_z_pos: Depth position (affects sprite size and priority)
 * - angle: Current position around the half-pipe (0-255, where 0x40 is center)
 * - inertia: Left/right speed on the track surface
 *
 * The player moves around the half-pipe track, and their screen position
 * is calculated by projecting track-space coordinates using SSAnglePos.
 *
 * Based on Obj09 (Sonic) and Obj0A (Tails) from s2disasm.
 */
public class Sonic2SpecialStagePlayer {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStagePlayer.class.getName());

    public enum PlayerType {
        SONIC,
        TAILS
    }

    public enum RoutineState {
        INIT(0),
        NORMAL(2),
        JUMPING(4),
        AIRBORNE(8);

        private final int value;
        RoutineState(int value) { this.value = value; }
        public int getValue() { return value; }
    }

    private final PlayerType playerType;
    private final boolean isMainCharacter;

    private RoutineState routine = RoutineState.INIT;
    private int routineSecondary = 0;

    private int ssXPos;
    private int ssXSub;
    private int ssYPos;
    private int ssYSub;
    private int ssZPos;

    private int xPos;
    private int yPos;

    private int xVel;
    private int yVel;
    private int inertia;

    private int angle;
    private int ssSlideTimer;
    private int ssHurtTimer;
    private int ssDplcTimer;

    // Flip timer for creating 8-frame running animation from 4 art frames.
    // ss_init_flip_timer is a word (0x400), but when read as byte it gives high byte (0x04).
    // After every 4 animation frame advances, the flip toggles.
    private int ssInitFlipTimer;
    private int ssFlipTimer;
    private int ssLastAngleIndex;

    private int anim;
    private int prevAnim;
    private int animFrame;
    private int animFrameDuration;
    private int mappingFrame;

    private int yRadius = 0x0E;
    private int xRadius = 0x07;
    private int priority = 3;

    private boolean statusXFlip;
    private boolean statusYFlip;
    private boolean statusJumping;
    private boolean statusSlowing;

    private boolean renderXFlip;
    private boolean renderYFlip;

    private int collisionProperty;

    private static final int SS_OFFSET_X = 0x80;  // From s2disasm line 6631
    private static final int SS_OFFSET_Y = 0x36;  // From s2disasm line 6632 (was incorrectly 0x50)

    private static final int INITIAL_Z_POS_MAIN = 0x6E;
    private static final int INITIAL_Z_POS_SIDEKICK = 0x80;
    private static final int Z_POS_MIN = 0x6E;
    private static final int Z_POS_MAX = 0x80;
    private static final int Z_POS_PRIORITY_THRESHOLD = 0x77;

    private static final int INITIAL_Y_POS = 0x80;
    private static final int INITIAL_ANGLE = 0x40;

    private static final int MOVE_ACCEL = 0x60;
    private static final int MAX_INERTIA = 0x600;
    private static final int JUMP_VELOCITY = 0x780;
    private static final int GRAVITY = 0xA8;
    private static final int AIR_CONTROL = 0x40;
    private static final int TRACTION_FACTOR = 0x50;
    private static final int FRICTION_SHIFT = 3;
    private static final int SLIDE_TIMER_INIT = 0x1E;

    private static final int SCREEN_SCALE_FACTOR = 0xCC;

    // Animation scripts from s2disasm off_341E4 (byte_341EE through byte_34208)
    // Format: {duration, frame0, frame1, ..., -1 to mark end/loop}
    private static final int[][] ANIM_SCRIPTS = {
        // Anim 0: Upright running - frames 0,1,2,3 looping
        { 3, 0, 1, 2, 3, -1 },
        // Anim 1: Diagonal - frames 4,5,6,7,8,9,10,11 looping
        { 3, 4, 5, 6, 7, 8, 9, 10, 11, -1 },
        // Anim 2: Horizontal - frames 12,13,14,15 looping
        { 3, 12, 13, 14, 15, -1 },
        // Anim 3: Ball/Jump - frames 16,17 looping
        { 1, 16, 17, -1 },
        // Anim 4: Hurt rotation - frames 0,4,12,4,0,4,12,4 looping
        { 3, 0, 4, 12, 4, 0, 4, 12, 4, -1 }
    };

    // Global animation frame timer from special stage (SS_player_anim_frame_timer)
    // This is set by the manager each frame based on the track animator's speed factor.
    // Animation timing based on SSAnim_Base_Duration table from s2.asm (lines 986-989)
    // The player animation uses this value divided by 2 (lsr.b #1)
    private int globalAnimFrameTimer = 30;

    private int[] ctrlRecordBuf;
    private int ctrlRecordIndex;
    private static final int CTRL_RECORD_SIZE = 16;

    private boolean swapPositionsFlag;
    private Sonic2SpecialStagePlayer otherPlayer;

    private static final int[] SINE_TABLE = new int[256];
    private static final int[] COSINE_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            double rad = (i / 256.0) * 2.0 * Math.PI;
            SINE_TABLE[i] = (int) (Math.sin(rad) * 256);
            COSINE_TABLE[i] = (int) (Math.cos(rad) * 256);
        }
    }

    public Sonic2SpecialStagePlayer(PlayerType type, boolean isMain) {
        this.playerType = type;
        this.isMainCharacter = isMain;
        this.ctrlRecordBuf = new int[CTRL_RECORD_SIZE];
        reset();
    }

    public void reset() {
        routine = RoutineState.INIT;
        routineSecondary = 0;

        ssXPos = 0;
        ssXSub = 0;
        ssYPos = INITIAL_Y_POS;
        ssYSub = 0;
        ssZPos = isMainCharacter ? INITIAL_Z_POS_MAIN : INITIAL_Z_POS_SIDEKICK;

        xPos = SS_OFFSET_X;
        yPos = SS_OFFSET_Y + INITIAL_Y_POS;

        xVel = 0;
        yVel = 0;
        inertia = 0;

        angle = INITIAL_ANGLE;
        ssSlideTimer = 0;
        ssHurtTimer = 0;
        ssDplcTimer = 0;

        ssInitFlipTimer = 0x400;
        // Original 68000: ss_init_flip_timer is at offset $32, ss_flip_timer at offset $33
        // When move.w #$400 writes to offset $32, it puts $04 at $32 and $00 at $33
        // So ss_flip_timer (offset $33) starts at 0, triggering flip on first frame
        ssFlipTimer = ssInitFlipTimer & 0xFF;  // = 0 (low byte)
        ssLastAngleIndex = 0;

        anim = 0;
        prevAnim = -1;  // Force animation update on first frame
        animFrame = 0;
        animFrameDuration = 0;
        mappingFrame = 0;

        priority = 3;

        statusXFlip = false;
        statusYFlip = false;
        statusJumping = false;
        statusSlowing = false;

        renderXFlip = false;
        renderYFlip = false;

        collisionProperty = 0;
        swapPositionsFlag = false;

        for (int i = 0; i < CTRL_RECORD_SIZE; i++) {
            ctrlRecordBuf[i] = 0;
        }
        ctrlRecordIndex = 0;

        routine = RoutineState.NORMAL;
    }

    /**
     * Sets the global animation frame timer from the track animator.
     * This should be called by the manager each frame before update().
     * @param timer The SS_player_anim_frame_timer value
     */
    public void setGlobalAnimFrameTimer(int timer) {
        this.globalAnimFrameTimer = timer;
    }

    public void update(int heldButtons, int pressedButtons) {
        updateControlRecord(heldButtons);

        switch (routine) {
            case NORMAL:
                updateNormal(heldButtons, pressedButtons);
                break;
            case JUMPING:
                updateJumping(heldButtons);
                break;
            case AIRBORNE:
                updateAirborne(heldButtons);
                break;
            default:
                break;
        }

        // Invulnerability timer is now managed by TimerManager (SSInvulnerabilityTimer)
    }

    private void updateControlRecord(int buttons) {
        for (int i = CTRL_RECORD_SIZE - 1; i > 0; i--) {
            ctrlRecordBuf[i] = ctrlRecordBuf[i - 1];
        }
        ctrlRecordBuf[0] = buttons;
    }

    private void updateNormal(int heldButtons, int pressedButtons) {
        if (routineSecondary == 2) {
            updateHurt();
            return;
        }

        ssPlayerMove(heldButtons);
        ssPlayerTraction();
        ssPlayerSwapPositions();
        ssObjectMove();
        ssAnglePos();
        ssPlayerJump(pressedButtons);
        ssPlayerSetAnimation();
        ssPlayerAnimate();
        ssPlayerCollision();
    }

    private void updateJumping(int heldButtons) {
        // Original Obj09_MdJump does NOT call SSPlayer_SetAnimation
        // Animation is set to 3 (ball) when entering jump state
        ssPlayerChgJumpDir(heldButtons);
        ssObjectMoveAndFall();
        ssPlayerJumpAngle();
        ssPlayerDoLevelCollision();
        ssPlayerSwapPositions();
        ssAnglePos();
        // Note: No ssPlayerSetAnimation() here - matches original
        ssPlayerAnimate();
    }

    private void updateAirborne(int heldButtons) {
        ssPlayerChgJumpDir(heldButtons);
        ssObjectMoveAndFall();
        ssPlayerJumpAngle();
        ssPlayerDoLevelCollision();
        ssPlayerSwapPositions();
        ssAnglePos();
        ssPlayerSetAnimation();
        ssPlayerAnimate();
    }

    private void updateHurt() {
        ssHurtAnimation();
        ssPlayerSwapPositions();
        ssObjectMove();
        ssAnglePos();
    }

    private void ssPlayerMove(int heldButtons) {
        int d2 = inertia;
        boolean left = (heldButtons & 0x04) != 0;
        boolean right = (heldButtons & 0x08) != 0;

        if (left) {
            d2 += MOVE_ACCEL;
            if (d2 > MAX_INERTIA) {
                d2 = MAX_INERTIA;
            }
            inertia = d2;
            statusSlowing = false;
            ssSlideTimer = 0;
        } else if (right) {
            d2 -= MOVE_ACCEL;
            if (d2 < -MAX_INERTIA) {
                d2 = -MAX_INERTIA;
            }
            inertia = d2;
            statusSlowing = false;
            ssSlideTimer = 0;
        } else {
            if (!statusSlowing) {
                statusSlowing = true;
                ssSlideTimer = SLIDE_TIMER_INIT;
            }

            int friction = d2 >> FRICTION_SHIFT;
            d2 -= friction;

            inertia = d2;

            if (ssSlideTimer > 0) {
                ssSlideTimer--;
            }
        }
    }

    private void ssPlayerTraction() {
        if (ssSlideTimer != 0) {
            return;
        }

        // Original uses d1 (cosine) from CalcSine, not d0 (sine)
        // This creates a restoring force: cos(0x40) = 0 at center,
        // positive on one side, negative on other
        int cosine = calcCosine(angle);

        int traction = (cosine * TRACTION_FACTOR) >> 8;
        inertia += traction;

        int a = angle & 0xFF;
        if (a >= 0x80) {
            int d0 = (a + 4) & 0xFF;
            if (d0 >= 0x88) {
                int absInertia = Math.abs(inertia);
                if (absInertia < 0x100) {
                    routine = RoutineState.AIRBORNE;
                }
            }
        }
    }

    private void ssObjectMove() {
        // Add inertia to angle: angle += inertia >> 8
        // Inertia is signed, so positive = left (increasing angle), negative = right
        int angleChange = inertia >> 8;
        angle = (angle + angleChange) & 0xFF;

        // Calculate track-space position from angle and depth
        // ss_x_pos = cos(angle) * ss_z_pos >> 8
        // ss_y_pos = sin(angle) * ss_z_pos >> 8
        int sine = calcSine(angle);
        int cosine = calcCosine(angle);

        ssXPos = (cosine * ssZPos) >> 8;
        ssYPos = (sine * ssZPos) >> 8;
    }

    private void ssAnglePos() {
        int d0 = ssXPos;
        d0 = (d0 * SCREEN_SCALE_FACTOR) >> 8;
        xPos = d0 + SS_OFFSET_X;

        yPos = ssYPos + SS_OFFSET_Y;
    }

    private void ssPlayerJump(int pressedButtons) {
        boolean jumpPressed = (pressedButtons & 0x70) != 0;
        if (!jumpPressed) {
            return;
        }

        int jumpAngle = (angle + 0x80) & 0xFF;
        int sine = calcSine(jumpAngle);
        int cosine = calcCosine(jumpAngle);

        xVel += (sine * JUMP_VELOCITY) >> 8;
        yVel += (cosine * JUMP_VELOCITY) >> 7;

        statusJumping = true;
        routine = RoutineState.JUMPING;
        anim = 3;
        animFrame = 0;
        animFrameDuration = 0;
        collisionProperty = 0;

        // Original: tst.b (SS_2p_Flag).w / bne.s loc_33B9E / tst.w (Player_mode).w / bne.s loc_33BA2
        // Only toggle swap flag in team mode (when otherPlayer exists)
        if (otherPlayer != null) {
            swapPositionsFlag = !swapPositionsFlag;
        }

        LOGGER.fine("Player jumped at angle " + angle + ", vel=(" + xVel + "," + yVel + ")");
    }

    private void ssObjectMoveAndFall() {
        // Original 68000 assembly loads y_vel BEFORE adding gravity,
        // then adds gravity to memory AFTER. This means gravity affects
        // the NEXT frame's position, not the current frame.
        long d2 = ((long) ssXPos << 16) | (ssXSub & 0xFFFF);
        long d3 = ((long) ssYPos << 16) | (ssYSub & 0xFFFF);

        int xVelExt = xVel;
        d2 += ((long) xVelExt) << 8;

        // Load yVel BEFORE applying gravity (matches original behavior)
        int yVelExt = yVel;
        d3 += ((long) yVelExt) << 8;

        // Apply gravity AFTER using yVel for position update
        yVel += GRAVITY;

        ssXPos = (int) (d2 >> 16);
        ssXSub = (int) (d2 & 0xFFFF);
        ssYPos = (int) (d3 >> 16);
        ssYSub = (int) (d3 & 0xFFFF);
    }

    private void ssPlayerChgJumpDir(int heldButtons) {
        boolean left = (heldButtons & 0x04) != 0;
        boolean right = (heldButtons & 0x08) != 0;

        if (left) {
            xVel -= AIR_CONTROL;
        } else if (right) {
            xVel += AIR_CONTROL;
        }
    }

    private void ssPlayerJumpAngle() {
        int d2 = ssYPos;
        int d3 = ssXPos;

        if (d2 < 0) {
            d2 = -d2;
            if (d3 < 0) {
                d3 = -d3;
                if (d3 >= d2) {
                    angle = 0x80 + ((d2 << 5) / d3);
                } else {
                    angle = 0xC0 - ((d3 << 5) / d2);
                }
            } else {
                if (d3 >= d2) {
                    angle = 0x100 - ((d2 << 5) / d3);
                } else {
                    angle = 0xC0 + ((d3 << 5) / d2);
                }
            }
        } else {
            if (d2 == 0 && d3 == 0) {
                angle = 0x40;
                return;
            }

            if (d3 < 0) {
                d3 = -d3;
                if (d3 >= d2) {
                    angle = (d2 << 5) / d3;
                } else {
                    angle = 0x40 - ((d3 << 5) / d2);
                }
            } else {
                if (d3 >= d2) {
                    angle = ((d2 << 5) / d3);
                } else {
                    angle = 0x40 - ((d3 << 5) / d2);
                }
            }
        }
        angle &= 0xFF;
    }

    private void ssPlayerDoLevelCollision() {
        if (ssYPos <= 0) {
            return;
        }

        int d0 = ssYPos * ssYPos;
        int d1 = ssXPos * ssXPos;
        int distSquared = d0 + d1;

        int radiusSquared = ssZPos * ssZPos;

        if (distSquared >= radiusSquared) {
            routine = RoutineState.NORMAL;
            statusJumping = false;
            xVel = 0;
            yVel = 0;
            inertia = 0;
            ssSlideTimer = 0;
            statusSlowing = true;
            ssObjectMove();
            ssAnglePos();
        }
    }

    private void ssPlayerSwapPositions() {
        // Original: tst.w (Player_mode).w / bne.s return_33E8E
        // Only swap positions in team mode (Sonic & Tails together)
        // In solo mode, otherPlayer is null, so skip this logic
        if (otherPlayer == null) {
            return;
        }

        int d0 = ssZPos;

        boolean shouldMoveCloser;
        if (isMainCharacter) {
            shouldMoveCloser = !swapPositionsFlag;
        } else {
            shouldMoveCloser = swapPositionsFlag;
        }

        if (shouldMoveCloser) {
            if (d0 > Z_POS_MIN) {
                d0--;
            }
        } else {
            if (d0 < Z_POS_MAX) {
                d0++;
            }
        }

        ssZPos = d0;

        if (d0 < Z_POS_PRIORITY_THRESHOLD) {
            priority = 3;
        } else {
            priority = 2;
        }
    }

    private void ssPlayerSetAnimation() {
        if (statusJumping) {
            anim = 3;
            statusXFlip = false;
            statusYFlip = false;
            return;
        }

        // Convert angle to table index: (angle - 0x10) >> 5 gives 0-7
        int d0 = ((angle - 0x10) & 0xFF) >> 5;

        // Animation table from byte_33E90 in s2disasm (lines 69031-69039)
        // Format: {anim, xFlip, yFlip}
        // The table maps 8 angle ranges (each 32 units) to animations:
        //   Index 0 (angle 0x10-0x2F): anim 1 (diagonal), xFlip
        //   Index 1 (angle 0x30-0x4F): anim 0 (upright), no flip - CENTER BOTTOM
        //   Index 2 (angle 0x50-0x6F): anim 1 (diagonal), no flip
        //   Index 3 (angle 0x70-0x8F): anim 2 (horizontal), no flip
        //   Index 4 (angle 0x90-0xAF): anim 1 (diagonal), yFlip
        //   Index 5 (angle 0xB0-0xCF): anim 0 (upright), yFlip
        //   Index 6 (angle 0xD0-0xEF): anim 1 (diagonal), xFlip+yFlip
        //   Index 7 (angle 0xF0-0x0F): anim 2 (horizontal), xFlip
        int[][] animTable = {
            {1, 1, 0},  // Index 0: diagonal, xFlip
            {0, 0, 0},  // Index 1: upright, no flip (center bottom)
            {1, 0, 0},  // Index 2: diagonal, no flip
            {2, 0, 0},  // Index 3: horizontal, no flip
            {1, 0, 1},  // Index 4: diagonal, yFlip
            {0, 0, 1},  // Index 5: upright, yFlip
            {1, 1, 1},  // Index 6: diagonal, xFlip+yFlip
            {2, 1, 0}   // Index 7: horizontal, xFlip
        };

        if (d0 >= animTable.length) {
            return;
        }

        int newAnim = animTable[d0][0];

        // Original assembly (lines 69057-69060):
        // cmp.b anim(a0),d2 / bne.s + / cmp.b ss_last_angle_index(a0),d1 / beq.s return
        // Only update status flip flags if animation OR angle index changed.
        // This preserves the flip timer's toggled state when staying in the same position.
        if (newAnim == anim && d0 == ssLastAngleIndex) {
            return;
        }

        ssLastAngleIndex = d0;
        anim = newAnim;
        statusXFlip = animTable[d0][1] != 0;
        statusYFlip = animTable[d0][2] != 0;

        // Reset flip timer at specific angle indices (center positions)
        // In the original, writing word 0x400 to ss_init_flip_timer (offset $32)
        // also writes to ss_flip_timer (offset $33) since it's a 16-bit write.
        // High byte ($32) gets 0x04, low byte ($33) gets 0x00.
        if (d0 == 1 || d0 == 5) {
            ssInitFlipTimer = 0x400;
            ssFlipTimer = ssInitFlipTimer & 0xFF;  // = 0 (triggers flip on next anim advance)
        }
    }

    /**
     * Animates the player by stepping through the animation script.
     * Based on SSPlayer_Animate from s2disasm (lines 69079-69120).
     *
     * The animation script format is: duration, frame0, frame1, ..., -1 (loop marker)
     * This method decrements the frame duration timer, and when it reaches 0,
     * advances to the next frame in the script and sets mappingFrame.
     */
    private void ssPlayerAnimate() {
        int currentAnim = anim;

        // Bounds check
        if (currentAnim < 0 || currentAnim >= ANIM_SCRIPTS.length) {
            currentAnim = 0;
        }

        // Check if animation changed
        if (currentAnim != prevAnim) {
            animFrame = 0;
            prevAnim = currentAnim;
            animFrameDuration = 0;
        }

        int[] script = ANIM_SCRIPTS[currentAnim];

        // Decrement frame duration timer
        animFrameDuration--;
        if (animFrameDuration >= 0) {
            // Still waiting, no update needed
            return;
        }

        // Reset frame duration using the global animation timer divided by 2.
        // Original: move.b (SS_player_anim_frame_timer).w,d0 / lsr.b #1,d0
        animFrameDuration = (globalAnimFrameTimer >> 1) & 0xFF;

        // Handle flip timer for anim 0 (upright running with periodic flip)
        // Original: subi_.b #1,ss_flip_timer(a0) / bgt.s + / bchg ...
        // The flip timer counts down, and when it reaches 0, toggles the flip
        // and resets to the byte at ss_init_flip_timer offset ($32), which is 0x04
        if (currentAnim == 0) {
            ssFlipTimer--;
            if (ssFlipTimer <= 0) {
                // Toggle x flip status (render flags are copied from status at end)
                statusXFlip = !statusXFlip;
                // Reset timer: read byte at ss_init_flip_timer offset ($32)
                // When word 0x0400 is written at $32, byte at $32 is 0x04 (high byte)
                ssFlipTimer = (ssInitFlipTimer >> 8) & 0xFF;
            }
        }

        // Get current frame from script (frame data starts at index 1)
        int frameDataIndex = animFrame + 1;
        if (frameDataIndex >= script.length || script[frameDataIndex] == -1) {
            // Loop back to start
            animFrame = 0;
            frameDataIndex = 1;
        }

        int frame = script[frameDataIndex];
        if (frame == -1) {
            // Safety: loop marker, restart
            animFrame = 0;
            frame = script[1];
        }

        // Set the mapping frame (strip sign bit like original: andi.b #$7F,d0)
        mappingFrame = frame & 0x7F;

        // Apply status flip flags to render flags
        renderXFlip = statusXFlip;
        renderYFlip = statusYFlip;

        // Advance to next frame
        animFrame++;
    }

    private void ssPlayerCollision() {
        if (collisionProperty == 0) {
            return;
        }

        collisionProperty = 0;

        // Check invulnerability using Timer framework
        if (isInvulnerable()) {
            return;
        }

        inertia &= 0xFF;

        if (isMainCharacter) {
            swapPositionsFlag = true;
        } else {
            swapPositionsFlag = false;
        }

        routineSecondary = 2;
        ssHurtTimer = 0;
    }

    private void ssHurtAnimation() {
        ssHurtTimer = (ssHurtTimer + 8) & 0xFF;
        if (ssHurtTimer == 0) {
            routineSecondary = 0;
            // Register invulnerability timer (30 frames = 0x1E)
            // Timer will call clearInvulnerability() when complete
            String timerCode = getInvulnerabilityTimerCode();
            TimerManager.getInstance().registerTimer(
                new SSInvulnerabilityTimer(timerCode, 0x1E, this));
        }

        int displayAngle = (ssHurtTimer + angle - 0x10) & 0xFF;
        int frameIndex = displayAngle >> 5;

        int[] hurtFrames = { 4, 0, 4, 12, 4, 0, 4, 12 };
        boolean[] hurtXFlips = { true, false, false, false, false, false, true, true };
        boolean[] hurtYFlips = { false, false, false, false, true, true, true, false };

        if (frameIndex < 8) {
            mappingFrame = hurtFrames[frameIndex];
            renderXFlip = hurtXFlips[frameIndex];
            renderYFlip = hurtYFlips[frameIndex];
        }
    }

    public void triggerHit() {
        collisionProperty = 1;
    }

    private int calcSine(int angle) {
        return SINE_TABLE[angle & 0xFF];
    }

    private int calcCosine(int angle) {
        return COSINE_TABLE[angle & 0xFF];
    }

    public int getXPos() { return xPos; }
    public int getYPos() { return yPos; }
    public int getSSXPos() { return ssXPos; }
    public int getSSYPos() { return ssYPos; }
    public int getSSZPos() { return ssZPos; }
    public int getAngle() { return angle; }
    public int getInertia() { return inertia; }
    public int getPriority() { return priority; }
    public int getMappingFrame() { return mappingFrame; }
    public int getAnim() { return anim; }
    public int getAnimFrame() { return animFrame; }
    public boolean isRenderXFlip() { return renderXFlip; }
    public boolean isRenderYFlip() { return renderYFlip; }
    public boolean isJumping() { return statusJumping; }
    public boolean isHurt() { return routineSecondary == 2; }

    /**
     * Checks if player is invulnerable (post-hurt invulnerability period).
     * Uses the Timer framework - invulnerability is active while the timer exists.
     */
    public boolean isInvulnerable() {
        return TimerManager.getInstance().getTimerForCode(getInvulnerabilityTimerCode()) != null;
    }

    /**
     * Gets the remaining invulnerability ticks (for flashing effect).
     * Returns 0 if not invulnerable.
     */
    public int getInvulnerabilityTicks() {
        Timer timer = TimerManager.getInstance().getTimerForCode(getInvulnerabilityTimerCode());
        return timer != null ? timer.getTicks() : 0;
    }

    /**
     * Gets the unique timer code for this player's invulnerability timer.
     */
    private String getInvulnerabilityTimerCode() {
        return "SSInvulnerable-" + playerType.name();
    }

    /**
     * Clears the invulnerability state. Called by SSInvulnerabilityTimer when complete.
     */
    public void clearInvulnerability() {
        // Timer is automatically removed by TimerManager when perform() returns true
        // This method exists for any additional cleanup if needed
        LOGGER.fine("Invulnerability ended for " + playerType.name());
    }

    public PlayerType getPlayerType() { return playerType; }
    public RoutineState getRoutine() { return routine; }

    public void setOtherPlayer(Sonic2SpecialStagePlayer other) {
        this.otherPlayer = other;
    }

    public void setSwapPositionsFlag(boolean flag) {
        this.swapPositionsFlag = flag;
    }

    public boolean getSwapPositionsFlag() {
        return swapPositionsFlag;
    }

    public int getControlRecordEntry(int framesAgo) {
        if (framesAgo < 0 || framesAgo >= CTRL_RECORD_SIZE) {
            return 0;
        }
        return ctrlRecordBuf[framesAgo];
    }
}
