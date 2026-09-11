package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * S3K Obj $8C - Bloominator (AIZ).
 * Core routine mapping: Obj_Bloominator (sonic3k.asm loc_86D8A..loc_86E42).
 */
public final class BloominatorBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x23; // ObjDat_Bloominator flags $23
    private static final int PRIORITY_BUCKET = 4;         // ObjDat_Bloominator priority $200
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int RENDER_HALF_WIDTH = 0x0C;
    private static final int RENDER_HALF_HEIGHT = 0x18;

    private static final int INITIAL_WAIT_FRAMES = 0x1F;  // loc_86D8A
    private static final int REPEAT_WAIT_FRAMES = 2 * 60; // loc_86DFC

    private static final int PROJECTILE_FRAME = 4;        // ObjDat3_86E1E mapping frame
    private static final int PROJECTILE_COLLISION_SIZE = 0x18; // ObjDat3 collision flags $98
    private static final int PROJECTILE_X_VEL = 0x100;    // ChildObjDat_86E2A
    private static final int PROJECTILE_Y_VEL = -0x500;   // ChildObjDat_86E2A
    private static final int PROJECTILE_GRAVITY = 0x38;   // Child callback MoveSprite
    private static final int PROJECTILE_Y_OFFSET = -0x10; // ChildObjDat_86E2A
    private static final int PROJECTILE_PRIORITY = 5;     // ObjDat3_86E1E priority $280

    // byte_86E42 (frame, delay pairs). Spawn points at offsets 6 and $E => step 3 and 7.
    private static final int[] ATTACK_FRAMES = {0, 1, 2, 3, 0, 1, 2, 3, 0};
    private static final int[] ATTACK_DELAYS = {7, 9, 4, 4, 9, 9, 4, 4, 0};
    private static final int FIRST_FIRE_STEP = 3;
    private static final int SECOND_FIRE_STEP = 7;

    private enum State {
        IDLE_WAIT,
        ATTACK
    }

    private State state = State.IDLE_WAIT;
    private int stateTimer = INITIAL_WAIT_FRAMES;
    private int attackStep;
    private int attackStepTimer;
    private int shotToggleCounter;
    private boolean waitingForOnscreen = true;
    private boolean initialized;

    public BloominatorBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Bloominator",
                Sonic3kObjectArtKeys.BLOOMINATOR, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        // Obj_WaitOffscreen replaces the operation pointer until its temporary
        // $20-by-$20 placeholder has been rendered. Restoring the saved pointer
        // and running loc_86D8A each consume their own object dispatch.
        if (waitingForOnscreen) {
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                return;
            }
            waitingForOnscreen = false;
            return;
        }
        if (!initialized) {
            initialized = true;
            return;
        }

        switch (state) {
            case IDLE_WAIT -> updateIdleWait();
            case ATTACK -> updateAttack();
        }
    }

    private void updateIdleWait() {
        // loc_86DA2 consumes render_flags bit 7 from the preceding render pass.
        if (!isWithinSolidContactBounds()) {
            return;
        }
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = State.ATTACK;
        attackStep = 0;
        attackStepTimer = 0;
    }

    @Override
    public int getCollisionFlags() {
        return waitingForOnscreen || !initialized ? 0 : super.getCollisionFlags();
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    private void updateAttack() {
        if (attackStepTimer > 0) {
            attackStepTimer--;
            return;
        }

        attackStep++;
        if (attackStep >= ATTACK_FRAMES.length) {
            state = State.IDLE_WAIT;
            stateTimer = REPEAT_WAIT_FRAMES;
            mappingFrame = 0;
            return;
        }

        mappingFrame = ATTACK_FRAMES[attackStep];
        attackStepTimer = ATTACK_DELAYS[attackStep];
        // Animate_RawMultiDelay returns the newly loaded script offset in d0;
        // Obj_Bloominator fires when offsets 6/$E (steps 3/7) are loaded,
        // rather than after those frames' delays expire.
        if (attackStep == FIRST_FIRE_STEP || attackStep == SECOND_FIRE_STEP) {
            fireProjectile();
        }
    }

    private void fireProjectile() {
        services().playSfx(Sonic3kSfx.PROJECTILE.id);

        int xVel = PROJECTILE_X_VEL;
        shotToggleCounter++;
        if ((shotToggleCounter & 1) != 0) {
            xVel = -xVel;
        }

        spawnProjectile(new S3kBadnikProjectileInstance(
                spawn,
                Sonic3kObjectArtKeys.BLOOMINATOR,
                PROJECTILE_FRAME,
                currentX,
                currentY + PROJECTILE_Y_OFFSET,
                xVel,
                PROJECTILE_Y_VEL,
                PROJECTILE_GRAVITY,
                PROJECTILE_COLLISION_SIZE,
                RenderPriority.clamp(PROJECTILE_PRIORITY),
                xVel > 0));
    }
}
