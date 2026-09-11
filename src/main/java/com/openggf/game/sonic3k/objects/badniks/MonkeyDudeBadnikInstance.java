package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $8E - Monkey Dude (AIZ).
 * Core routine mapping: Obj_MonkeyDude (sonic3k.asm loc_87172..loc_876BD).
 *
 * This implementation preserves the key gameplay behavior: alternating wait/active
 * cycles, subtype-driven vertical stepping, player-facing checks, and coconut throws.
 */
public final class MonkeyDudeBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B; // ObjDat_MonkeyDude flags $0B
    private static final int PRIORITY_BUCKET = 5;         // ObjDat_MonkeyDude priority $280

    private static final int WAIT_FRAMES = 60 - 1;        // loc_87172, loc_87218
    private static final int STEP_PIXELS = 8;             // loc_87218 branches
    private static final int PLAYER_Y_RANGE = 0x80;       // sub_87524
    private static final int PLAYER_X_RANGE = 0xC0;       // gameplay approximation

    private static final int PROJECTILE_FRAME = 6;        // ObjDat3_87674 mapping frame
    private static final int PROJECTILE_COLLISION_SIZE = 0x18; // ObjDat3 flags $98
    private static final int PROJECTILE_X_VEL = 0x200;    // ChildObjDat_8769C
    private static final int PROJECTILE_Y_VEL = -0x400;   // ChildObjDat_8769C
    private static final int PROJECTILE_GRAVITY = 0x20;   // MoveSprite_LightGravity
    private static final int PROJECTILE_PRIORITY = 5;     // ObjDat3_87674 priority $280
    private static final int THROW_COOLDOWN_FRAMES = 0x78;
    private static final int ARM_ROOT_X_OFFSET = 0x0E;    // child side offset in loc_87280
    private static final int FLIPPED_RENDER_X_OFFSET = 32; // 4 patterns

    // byte_876B4: (7) 0,1 loop
    private static final int[] WAIT_FRAMES_SEQ = {0, 1};
    private static final int[] WAIT_DELAYS = {7, 7};
    // byte_876B8: 0,7 then 2,7 loop
    private static final int[] ACTIVE_FRAMES_SEQ = {0, 2};
    private static final int[] ACTIVE_DELAYS = {7, 7};

    private enum State {
        WAIT,
        ACTIVE
    }

    private enum ArmRootPhase {
        INITIAL_SWING,
        RANDOM_SWING,
        ATTACK_WINDUP,
        ATTACK_SWING,
        ATTACK_RETURN,
        POST_ATTACK_SWING
    }

    private static final int ARM_FRAME_BASE = 3;
    private static final int ARM_FRAME_THROW = 4;
    private static final int COCONUT_FRAME = 6;
    private static final int ARM_SEGMENT_COUNT = 4;
    private static final int ARM_FOLLOWER_COUNT = ARM_SEGMENT_COUNT - 1;
    private static final int ARM_SEGMENT_RADIUS = 8;      // MoveSprite_CircularSimple with d2=5
    private static final int[] ARM_DELAY_BASE = {2, 4, 6}; // subtype*2 follower cadence
    private static final int ARM_RIGHT_MIN_ANGLE = 0x40;
    private static final int ARM_RIGHT_MAX_ANGLE = 0x80;
    private static final int ARM_LEFT_MIN_ANGLE = 0x80;
    private static final int ARM_LEFT_MAX_ANGLE = 0xC0;
    private static final int ARM_ANGLE_STEP = 2;
    private static final int HELD_COCONUT_Y_OFFSET = -8;

    private int activeStepCount;
    private int firstStepCount;
    private int treeAnchorX;
    private boolean initialFacingLeft;
    private final int[] armSegmentX = new int[ARM_SEGMENT_COUNT];
    private final int[] armSegmentY = new int[ARM_SEGMENT_COUNT];
    private final int[] followerAngle = new int[ARM_FOLLOWER_COUNT];
    private final int[] followerDelayTimer = new int[ARM_FOLLOWER_COUNT];
    private int armRootAngle;
    private int armAngleStep = ARM_ANGLE_STEP;
    private ArmRootPhase armRootPhase = ArmRootPhase.INITIAL_SWING;
    private int romArmRootAngle;
    private int romArmAngleStep = 1;
    private int armRandomWaitTimer;
    private int armSetupDelay = 2;
    private boolean armRootActivated;
    /**
     * routine 0. Obj_WaitOffscreen's release pass (loc_85B02) returns without
     * dispatching and leaves routine at 0, so the first dispatch that reaches the
     * routine table runs MonkeyDude_Init, not MonkeyDude_Wait.
     */
    private boolean initPending = true;
    private boolean lastFacingLeft;

    private State state = State.WAIT;
    private int stateTimer = WAIT_FRAMES;
    private int remainingVerticalSteps;
    private boolean movingUp;
    private boolean threwProjectileThisActive;
    private int throwCooldown;

    private int animIndex;
    private int animTimer;
    private int[] frames = WAIT_FRAMES_SEQ;
    private int[] delays = WAIT_DELAYS;

    public MonkeyDudeBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "MonkeyDude",
                Sonic3kObjectArtKeys.MONKEY_DUDE, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);

        // ROM init:
        // d0 = subtype >> 2; subtype = d0; $39 = d0 >> 1
        int subtypeShift2 = Math.max(1, (spawn.subtype() & 0xFF) >> 2);
        this.activeStepCount = subtypeShift2;
        this.firstStepCount = Math.max(1, subtypeShift2 >> 1);
        this.treeAnchorX = spawn.x();
        this.initialFacingLeft = facingLeft;
        this.remainingVerticalSteps = firstStepCount;
        this.mappingFrame = WAIT_FRAMES_SEQ[0];
        this.animTimer = WAIT_DELAYS[0];
        this.lastFacingLeft = facingLeft;
        resetArmChainForFacing();
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM entry point begins with Obj_WaitOffscreen, so the state machine,
        // arm animation, and throw cadence must not advance until visible.
        if (isDestroyed()) {
            return;
        }

        boolean withinWaitOffscreenBounds = isOnScreen(0x20);
        if (!armRootActivated) {
            if (!withinWaitOffscreenBounds) {
                return;
            }
            armRootActivated = true;
            // Obj_WaitOffscreen's release pass is loc_85B02: it restores the
            // saved operation into (a0) and `rts` back to Process_Sprites
            // (sonic3k.asm:180299-180301). It does NOT re-enter Obj_MonkeyDude,
            // so the release frame runs no routine at all -- routine 0 is
            // dispatched on the FOLLOWING frame. Returning here keeps that
            // dispatch separate from the Init dispatch below; both frames exist
            // in the ROM and both must be consumed.
            return;
        }

        if (initPending) {
            // MonkeyDude_Init (sonic3k.asm:182710-182728) calls
            // SetUp_ObjAttributes (tail `addq.b #2,routine(a0)` / `rts`,
            // sonic3k.asm:176901-176919), rewrites subtype = subtype >> 2 and
            // $39 = subtype >> 3, points $30 at AniRaw_MonkeyDudeWait, sets
            // $2E = 60-1 and $34 = MonkeyDude_StartActive, and ends by jumping to
            // CreateChild4_LinkListRepeated for the arm chain. Every path RETURNS
            // rather than falling through to MonkeyDude_Wait, so this dispatch
            // runs no animation, no facing check and no throw cadence; routine 2
            // starts on the next dispatch. The constructor already applies those
            // field writes and builds the arm chain.
            initPending = false;
            return;
        }

        // Obj_WaitOffscreen replaces the operation only until the placeholder
        // first becomes visible. Once restored, the separately allocated root
        // child keeps running even after it leaves those bounds.
        if (!withinWaitOffscreenBounds) {
            updateNativeArmRoot(player);
            return;
        }

        if (throwCooldown > 0) {
            throwCooldown--;
        }

        updateFacingAndOffset(player);
        updateNativeArmRoot(player);
        updateArmChainAnimation();

        switch (state) {
            case WAIT -> updateWait();
            case ACTIVE -> updateActive(player);
        }
    }

    @Override
    public int getCollisionFlags() {
        // collision_flags is written by SetUp_ObjAttributes inside
        // MonkeyDude_Init (sonic3k.asm:176910), so the SST slot still reads zero
        // for the whole Init dispatch: the frame's touch scan runs at the player
        // slot before this object's routine.
        return initPending ? 0 : super.getCollisionFlags();
    }

    private void updateWait() {
        tickAnimation();

        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = State.ACTIVE;
        threwProjectileThisActive = false;
        setAnimation(ACTIVE_FRAMES_SEQ, ACTIVE_DELAYS);
    }

    private void updateActive(AbstractPlayableSprite player) {
        boolean frameAdvanced = tickAnimation();

        if (frameAdvanced) {
            applyVerticalStepOnAnimationEdge();
        }

        if (!threwProjectileThisActive && throwCooldown <= 0 && canThrowAtPlayer(player) && mappingFrame == 2) {
            throwCoconut();
            threwProjectileThisActive = true;
            throwCooldown = THROW_COOLDOWN_FRAMES;
        }
    }

    private boolean tickAnimation() {
        if (animTimer > 0) {
            animTimer--;
            return false;
        }
        animIndex = (animIndex + 1) % frames.length;
        mappingFrame = frames[animIndex];
        animTimer = delays[animIndex];
        return true;
    }

    private void setAnimation(int[] frames, int[] delays) {
        this.frames = frames;
        this.delays = delays;
        this.animIndex = 0;
        this.mappingFrame = frames[0];
        this.animTimer = delays[0];
    }

    private void applyVerticalStepOnAnimationEdge() {
        if (!movingUp) {
            if (mappingFrame != 0) {
                return;
            }
            currentY += STEP_PIXELS;
            remainingVerticalSteps--;
            if (remainingVerticalSteps <= 1) {
                resetToWaitAndToggleDirection();
            }
            return;
        }

        if (mappingFrame != 2) {
            return;
        }
        remainingVerticalSteps--;
        if (remainingVerticalSteps <= 0) {
            resetToWaitAndToggleDirection();
            return;
        }
        currentY -= STEP_PIXELS;
    }

    private void resetToWaitAndToggleDirection() {
        state = State.WAIT;
        stateTimer = WAIT_FRAMES;
        movingUp = !movingUp;
        remainingVerticalSteps = activeStepCount;
        setAnimation(WAIT_FRAMES_SEQ, WAIT_DELAYS);
    }

    private boolean canThrowAtPlayer(AbstractPlayableSprite player) {
        if (player == null) {
            return false;
        }
        int dx = player.getCentreX() - getBodyAnchorX();
        int dy = Math.abs(player.getCentreY() - currentY);
        if (Math.abs(dx) > PLAYER_X_RANGE || dy >= PLAYER_Y_RANGE) {
            return false;
        }
        return facingLeft ? dx < 0 : dx > 0;
    }

    private void updateFacingAndOffset(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        boolean nextFacingLeft = player.getCentreX() < treeAnchorX;
        facingLeft = nextFacingLeft;
        if (facingLeft != lastFacingLeft) {
            resetArmChainForFacing();
            lastFacingLeft = facingLeft;
        }
    }

    private void throwCoconut() {
        services().playSfx(Sonic3kSfx.MISSILE_THROW.id);

        int xVel = facingLeft ? -PROJECTILE_X_VEL : PROJECTILE_X_VEL;
        int tipX = armSegmentX[ARM_SEGMENT_COUNT - 1];
        int tipY = armSegmentY[ARM_SEGMENT_COUNT - 1];
        spawnProjectile(new S3kBadnikProjectileInstance(
                spawn,
                Sonic3kObjectArtKeys.MONKEY_DUDE,
                PROJECTILE_FRAME,
                tipX,
                tipY + HELD_COCONUT_Y_OFFSET,
                xVel,
                PROJECTILE_Y_VEL,
                PROJECTILE_GRAVITY,
                PROJECTILE_COLLISION_SIZE,
                RenderPriority.clamp(PROJECTILE_PRIORITY),
                xVel > 0));
    }

    private void resetArmChainForFacing() {
        armRootAngle = facingLeft ? 0xA0 : 0x60;
        armAngleStep = ARM_ANGLE_STEP;
        for (int i = 0; i < ARM_FOLLOWER_COUNT; i++) {
            followerAngle[i] = armRootAngle;
            followerDelayTimer[i] = ARM_DELAY_BASE[i];
        }
    }

    private void updateNativeArmRoot(PlayableEntity player) {
        if (armSetupDelay > 0) {
            armSetupDelay--;
            return;
        }
        updateArmRootRoutine(closestNativePlayerByHorizontalDistance(player));
    }

    private void updateArmRootRoutine(PlayableEntity targetEntity) {
        boolean mirrored = !initialFacingLeft;
        switch (armRootPhase) {
            case INITIAL_SWING -> {
                romArmRootAngle = addAngle(romArmRootAngle, mirrored ? 4 : -4);
                if ((mirrored && romArmRootAngle >= 0x80)
                        || (!mirrored && romArmRootAngle <= 0x80)) {
                    armRootPhase = ArmRootPhase.RANDOM_SWING;
                    resetRandomArmWait();
                }
            }
            case RANDOM_SWING -> {
                if (armTargetIsOnNativeSide(targetEntity, mirrored)) {
                    armRootPhase = ArmRootPhase.ATTACK_WINDUP;
                } else {
                    updateRandomArmSwing(mirrored);
                }
            }
            case ATTACK_WINDUP -> {
                romArmRootAngle = addAngle(romArmRootAngle, mirrored ? 4 : -4);
                if ((mirrored && romArmRootAngle >= 0xC0)
                        || (!mirrored && romArmRootAngle <= 0x40)) {
                    armRootPhase = ArmRootPhase.ATTACK_SWING;
                }
            }
            case ATTACK_SWING -> {
                romArmRootAngle = addAngle(romArmRootAngle, mirrored ? -8 : 8);
                if ((mirrored && romArmRootAngle <= 0x60)
                        || (!mirrored && romArmRootAngle >= 0xA0)) {
                    armRootPhase = ArmRootPhase.ATTACK_RETURN;
                }
            }
            case ATTACK_RETURN -> {
                romArmRootAngle = addAngle(romArmRootAngle, mirrored ? 2 : -2);
                if ((mirrored && romArmRootAngle >= 0x80)
                        || (!mirrored && romArmRootAngle <= 0x80)) {
                    armRootPhase = ArmRootPhase.POST_ATTACK_SWING;
                    resetRandomArmWait();
                }
            }
            case POST_ATTACK_SWING -> updateRandomArmSwing(mirrored);
        }
    }

    private boolean armTargetIsOnNativeSide(PlayableEntity targetEntity, boolean mirrored) {
        if (!(targetEntity instanceof AbstractPlayableSprite target)
                || Math.abs(target.getCentreY() - currentY) >= PLAYER_Y_RANGE) {
            return false;
        }
        int dx = target.getCentreX() - treeAnchorX;
        return mirrored ? dx >= 0 : dx < 0;
    }

    private void updateRandomArmSwing(boolean mirrored) {
        romArmRootAngle = addAngle(romArmRootAngle, romArmAngleStep);
        int rangeRelative = addAngle(romArmRootAngle, mirrored ? -0x20 : -0x80);
        if (rangeRelative >= 0x60) {
            romArmAngleStep = -romArmAngleStep;
            romArmRootAngle = addAngle(romArmRootAngle, romArmAngleStep);
        }
        armRandomWaitTimer--;
        if (armRandomWaitTimer < 0) {
            resetRandomArmWait();
        }
    }

    private void resetRandomArmWait() {
        services().rng().nextRaw();
        // 68k move.w (RNG_seed).w reads the first (high) word of the
        // big-endian longword stored by Random_Number.
        int seedWord = (int) (services().rng().getSeed() >>> 16) & 0xFFFF;
        romArmAngleStep = (seedWord & 1) == 0 ? 1 : -1;
        armRandomWaitTimer = seedWord & 0x3C;
    }

    private static int addAngle(int angle, int delta) {
        return (angle + delta) & 0xFF;
    }

    private void updateArmChainAnimation() {
        int anchorX = getRenderAnchorX();
        // Root chain anchor from loc_87280 / sub_87500.
        armSegmentX[0] = anchorX + (facingLeft ? -ARM_ROOT_X_OFFSET : ARM_ROOT_X_OFFSET);
        int rootY = currentY - 2;
        if (mappingFrame != 0) {
            rootY -= 2;
        }
        armSegmentY[0] = rootY;

        // The consolidated renderer retains its established arm-chain pose;
        // the ROM root-child state above owns timing/RNG independently.
        armRootAngle = (armRootAngle + armAngleStep) & 0xFF;
        int minAngle = facingLeft ? ARM_LEFT_MIN_ANGLE : ARM_RIGHT_MIN_ANGLE;
        int maxAngle = facingLeft ? ARM_LEFT_MAX_ANGLE : ARM_RIGHT_MAX_ANGLE;
        if (armRootAngle <= minAngle || armRootAngle >= maxAngle) {
            armRootAngle = Math.max(minAngle, Math.min(maxAngle, armRootAngle));
            armAngleStep = -armAngleStep;
        }

        int parentAngle = armRootAngle;
        for (int i = 0; i < ARM_FOLLOWER_COUNT; i++) {
            followerDelayTimer[i]--;
            if (followerDelayTimer[i] <= 0) {
                followerDelayTimer[i] = ARM_DELAY_BASE[i];
                followerAngle[i] = parentAngle;
            }
            int angle = followerAngle[i] & 0xFF;
            int dx = (TrigLookupTable.sinHex(angle) * ARM_SEGMENT_RADIUS) >> 8;
            int dy = (TrigLookupTable.cosHex(angle) * ARM_SEGMENT_RADIUS) >> 8;
            armSegmentX[i + 1] = armSegmentX[i] + dx;
            armSegmentY[i + 1] = armSegmentY[i] + dy;
            parentAngle = angle;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.MONKEY_DUDE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        int anchorX = getRenderAnchorX();
        boolean hFlip = !facingLeft;
        renderer.drawFrameIndex(mappingFrame, anchorX, currentY, hFlip, false);

        int tipFrame = (state == State.ACTIVE && mappingFrame == 2) ? ARM_FRAME_THROW : ARM_FRAME_BASE;
        for (int i = 0; i < ARM_SEGMENT_COUNT; i++) {
            int frame = (i == ARM_SEGMENT_COUNT - 1) ? tipFrame : ARM_FRAME_BASE;
            renderer.drawFrameIndex(frame, armSegmentX[i], armSegmentY[i], hFlip, false);
        }

        if (!threwProjectileThisActive) {
            int tipX = armSegmentX[ARM_SEGMENT_COUNT - 1];
            int tipY = armSegmentY[ARM_SEGMENT_COUNT - 1];
            renderer.drawFrameIndex(COCONUT_FRAME, tipX, tipY + HELD_COCONUT_Y_OFFSET, hFlip, false);
        }
    }

    /**
     * The live collision/debug anchor follows the same facing-dependent origin as
     * the body sprite. Keep the inherited frame-start anchor: S3K's player touch
     * pass reads the object's frame-start {@code x_pos}, while the ROM's render
     * flip does not move that field (sonic3k.asm:20655-20681).
     */
    @Override
    public int getCollisionX() {
        return facingAdjustedX(facingLeft);
    }

    @Override
    protected int getRenderAnchorX() {
        return facingAdjustedX(facingLeft);
    }

    private int facingAdjustedX(boolean facingLeft) {
        if (facingLeft == initialFacingLeft) {
            return currentX;
        }
        return currentX + (initialFacingLeft ? FLIPPED_RENDER_X_OFFSET : -FLIPPED_RENDER_X_OFFSET);
    }
}
