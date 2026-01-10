package uk.co.jamesj999.sonic.game.sonic2.specialstage;

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

    private static final int SS_OFFSET_X = 0x80;
    private static final int SS_OFFSET_Y = 0x50;

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
        ssFlipTimer = 0;
        ssLastAngleIndex = 0;

        anim = 0;
        prevAnim = 0;
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
        ssPlayerCollision();
    }

    private void updateJumping(int heldButtons) {
        ssPlayerChgJumpDir(heldButtons);
        ssObjectMoveAndFall();
        ssPlayerJumpAngle();
        ssPlayerDoLevelCollision();
        ssPlayerSwapPositions();
        ssAnglePos();
    }

    private void updateAirborne(int heldButtons) {
        ssPlayerChgJumpDir(heldButtons);
        ssObjectMoveAndFall();
        ssPlayerJumpAngle();
        ssPlayerDoLevelCollision();
        ssPlayerSwapPositions();
        ssAnglePos();
        ssPlayerSetAnimation();
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

        int sine = calcSine(angle);

        int traction = (sine * TRACTION_FACTOR) >> 8;
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
        int d2 = inertia;
        if (d2 < 0) {
            d2 = -d2;
            int angleChange = d2 >> 8;
            angle = (angle - angleChange) & 0xFF;
        } else {
            int angleChange = d2 >> 8;
            angle = (angle + angleChange) & 0xFF;
        }

        int sine = calcSine(angle);
        int cosine = calcCosine(angle);

        ssXPos = (sine * ssZPos) >> 8;
        ssYPos = (cosine * ssZPos) >> 8;
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

        swapPositionsFlag = !swapPositionsFlag;

        LOGGER.fine("Player jumped at angle " + angle + ", vel=(" + xVel + "," + yVel + ")");
    }

    private void ssObjectMoveAndFall() {
        long d2 = ((long) ssXPos << 16) | (ssXSub & 0xFFFF);
        long d3 = ((long) ssYPos << 16) | (ssYSub & 0xFFFF);

        int xVelExt = xVel;
        d2 += ((long) xVelExt) << 8;

        yVel += GRAVITY;

        int yVelExt = yVel;
        d3 += ((long) yVelExt) << 8;

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

        int d0 = ((angle - 0x10) & 0xFF) >> 5;
        ssLastAngleIndex = d0;

        int[][] animTable = {
            {0, 1, 1},
            {0, 0, 0},
            {0, 0, 0},
            {2, 0, 0},
            {0, 0, 1},
            {0, 0, 1},
            {0, 1, 1},
            {2, 1, 0}
        };

        if (d0 < animTable.length) {
            anim = animTable[d0][0];
            statusXFlip = animTable[d0][1] != 0;
            statusYFlip = animTable[d0][2] != 0;
        }

        if (d0 == 1 || d0 == 5) {
            ssInitFlipTimer = 0x400;
        }
    }

    private void ssPlayerCollision() {
        if (collisionProperty == 0) {
            return;
        }

        collisionProperty = 0;

        if (ssDplcTimer != 0) {
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
            ssDplcTimer = 0x1E;
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
    public boolean isRenderXFlip() { return renderXFlip || statusXFlip; }
    public boolean isRenderYFlip() { return renderYFlip || statusYFlip; }
    public boolean isJumping() { return statusJumping; }
    public boolean isHurt() { return routineSecondary == 2; }
    public boolean isInvulnerable() { return ssDplcTimer > 0; }
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
