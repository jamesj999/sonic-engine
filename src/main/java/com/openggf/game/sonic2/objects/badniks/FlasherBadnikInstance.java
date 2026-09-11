package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Flasher (0xA3) - firefly/glowbug badnik from MCZ.
 *
 * ROM reference: ObjA3 in s2.asm.
 */
public class FlasherBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x06; // subObjData ... ,6

    // Routine timers/velocities from ObjA3.
    private static final int WAIT_TIMER_INIT = 0x40;
    private static final int FLIGHT_TIMER_INIT = 0x80;
    private static final int ELECTRIFIED_TIMER_INIT = 0x80;
    private static final int INITIAL_X_VELOCITY = -0x100;
    private static final int INITIAL_Y_VELOCITY = 0x40;
    private static final int INITIAL_X_ACCELERATION = 0x0002;

    // ObjA3 flight phase table (word_38810 + byte_38820 pairs used after each threshold).
    private static final int[] FLIGHT_PHASE_THRESHOLDS = {
            0x100, 0x1A0, 0x208, 0x285, 0x300, 0x340, 0x390, 0x440
    };
    private static final boolean[] TOGGLE_X_ACCEL = {
            true, false, true, false, false, true, false, false
    };
    private static final boolean[] TOGGLE_Y_VELOCITY = {
            true, true, true, true, true, false, true, true
    };

    // Animation scripts from Ani_objA3_a/b/c (byte[0] is delay, rest are frame indices).
    private static final int[] ANIM_CHARGE = { // Ani_objA3_a: delay=0, frames only
            0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1,
            0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 2, 3, 4
    };
    private static final int[] ANIM_ELECTRIFIED_LOOP = {2, 0, 3, 0, 4, 0, 3, 0}; // Ani_objA3_b: delay=0
    private static final int[] ANIM_RECOVER = {4, 3, 2, 1, 0};
    private static final int RECOVER_FRAME_DELAY = 3; // Ani_objA3_c delay byte
    private static final int FRAME_ELECTRIFIED_TRANSITION = 3; // loc_3884A

    private enum State {
        INIT,               // routine 0 (loc_3875A): one-frame setup, no countdown
        WAITING,            // routine 2
        FLYING,             // routine 4
        CHARGING,           // routine 6
        ELECTRIFIED_HOLD,   // routine 8
        RECOVERING,         // routine A
        RESETTING           // routine C
    }

    private State state;
    private int stateTimer;

    private int xPosFixed;
    private int yPosFixed;
    private int xAcceleration;
    private int flightCounter;
    private int flightPhaseIndex;
    private boolean electrified;

    private int[] animationScript;
    private boolean animationLoops;
    private int animationDelay;
    private int animationDelayCounter;
    private int animationIndex;

    public FlasherBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Flasher", Sonic2BadnikConfig.DESTRUCTION);
        // ObjA3 routine 0 (loc_3875A, s2.asm:76027-76030) runs as a dedicated
        // init frame: LoadSubObject, set objoff_2A=$40, then rts WITHOUT running
        // the countdown that lives in routine 2 (loc_38766). Modeling WAITING as
        // the start state would decrement objoff_2A on the spawn frame, starting
        // the firefly's flight one frame early; the trajectory then leads ROM by
        // one frame (MCZ2 trace f3729: engine hits the Flasher one frame before
        // ROM, applying the React_Enemy +$100 y_vel bounce a frame early). The
        // INIT state consumes the routine-0 frame and only sets up the countdown.
        this.state = State.INIT;
        this.stateTimer = WAIT_TIMER_INIT;
        this.xPosFixed = currentX << 8;
        this.yPosFixed = currentY << 8;
        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;
        this.xAcceleration = 0;
        this.flightCounter = 0;
        this.flightPhaseIndex = 0;
        this.electrified = false;
        this.animFrame = 0;
    }

    @Override
    public FlasherBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new FlasherBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case INIT -> updateInit();
            case WAITING -> updateWaiting();
            case FLYING -> updateFlying();
            case CHARGING -> updateCharging();
            case ELECTRIFIED_HOLD -> updateElectrifiedHold();
            case RECOVERING -> updateRecovering();
            case RESETTING -> updateResetting();
        }

        currentX = xPosFixed >> 8;
        currentY = yPosFixed >> 8;
    }

    private void updateInit() {
        // ObjA3 loc_3875A (s2.asm:76027-76030): routine 0 only sets the wait
        // timer (objoff_2A=$40) and returns; the routine advances to 2 so the
        // countdown begins on the NEXT frame. No position/timer change here.
        stateTimer = WAIT_TIMER_INIT;
        state = State.WAITING;
    }

    private void updateWaiting() {
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = State.FLYING;
        xVelocity = INITIAL_X_VELOCITY;
        yVelocity = INITIAL_Y_VELOCITY;
        xAcceleration = INITIAL_X_ACCELERATION;
        flightCounter = 0;
        flightPhaseIndex = 0;
        stateTimer = FLIGHT_TIMER_INIT;
        electrified = false;
        clearAnimationState();
        animFrame = 0;
    }

    private void updateFlying() {
        stateTimer--;
        if (stateTimer < 0) {
            state = State.CHARGING;
            electrified = true; // ObjA3: ori.b #$80,collision_flags(a0)
            clearAnimationState();
            startAnimation(ANIM_CHARGE, 0, false);
            return;
        }

        if (flightCounter < 0) {
            setDestroyed(true);
            setDestroyed(true); // ObjA3: delete if objoff_2A wrapped negative
            return;
        }

        // ObjA3 clears x_flip then sets it when x_vel >= 0.
        facingLeft = xVelocity < 0;

        flightCounter++;
        applyFlightPhaseTransitions();

        xVelocity += xAcceleration;
        xPosFixed += xVelocity;
        yPosFixed += yVelocity;
    }

    private void applyFlightPhaseTransitions() {
        while (flightPhaseIndex < FLIGHT_PHASE_THRESHOLDS.length
                && flightCounter >= FLIGHT_PHASE_THRESHOLDS[flightPhaseIndex]) {
            if (TOGGLE_X_ACCEL[flightPhaseIndex]) {
                xAcceleration = -xAcceleration;
            }
            if (TOGGLE_Y_VELOCITY[flightPhaseIndex]) {
                yVelocity = -yVelocity;
            }
            flightPhaseIndex++;
        }
    }

    private void updateCharging() {
        if (advanceAnimation()) {
            // ObjA3 loc_3884A: clear anim state and set mapping_frame to 3 for one frame.
            clearAnimationState();
            animFrame = FRAME_ELECTRIFIED_TRANSITION;
            state = State.ELECTRIFIED_HOLD;
            stateTimer = ELECTRIFIED_TIMER_INIT;
        }
    }

    private void updateElectrifiedHold() {
        stateTimer--;
        if (stateTimer < 0) {
            // ObjA3 loc_38870: transition to routine A and clear animation fields.
            state = State.RECOVERING;
            clearAnimationState();
            return;
        }

        if (animationScript == null) {
            startAnimation(ANIM_ELECTRIFIED_LOOP, 0, true);
            return;
        }
        advanceAnimation();
    }

    private void updateRecovering() {
        if (animationScript == null) {
            startAnimation(ANIM_RECOVER, RECOVER_FRAME_DELAY, false);
            return;
        }

        if (advanceAnimation()) {
            state = State.RESETTING;
        }
    }

    private void updateResetting() {
        // ObjA3 loc_3888E: routine=4, timer=0x80, clear electrified bit + anim state.
        state = State.FLYING;
        stateTimer = FLIGHT_TIMER_INIT;
        electrified = false;
        clearAnimationState();
        animFrame = 0;
    }

    private void startAnimation(int[] script, int delay, boolean loop) {
        animationScript = script;
        animationDelay = delay;
        animationLoops = loop;
        animationIndex = 0;
        animationDelayCounter = delay;
        animFrame = script[0];
    }

    private boolean advanceAnimation() {
        if (animationScript == null || animationScript.length == 0) {
            return false;
        }

        if (animationDelayCounter > 0) {
            animationDelayCounter--;
            return false;
        }

        animationDelayCounter = animationDelay;
        animationIndex++;
        if (animationIndex >= animationScript.length) {
            if (animationLoops) {
                animationIndex = 0;
            } else {
                animationIndex = animationScript.length - 1;
                return true;
            }
        }
        animFrame = animationScript[animationIndex];
        return false;
    }

    private void clearAnimationState() {
        animationScript = null;
        animationLoops = false;
        animationDelay = 0;
        animationDelayCounter = 0;
        animationIndex = 0;
    }

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        int category = electrified ? 0x80 : 0x00;
        return category | (getCollisionSizeIndex() & 0x3F);
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // S2 Touch_Loop scans collision_flags directly with no render-flag
        // gate (s2.asm:85048-85054). ObjA3 starts with collision_flags=$06
        // from subObjData and sets bit 7 while electrified (s2.asm:76227-76228,
        // 76144-76148), so off-screen CPU Tails can still be hurt.
        return false;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.FLASHER);
        if (renderer == null) return;

        // ObjA3 uses x_flip = 0 while moving left, x_flip = 1 while moving right.
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }
}
