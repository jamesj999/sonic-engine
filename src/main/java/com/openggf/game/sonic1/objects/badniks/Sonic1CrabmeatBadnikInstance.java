package com.openggf.game.sonic1.objects.badniks;
import com.openggf.game.PlayableEntity;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Crabmeat (0x1F) - Crab Badnik from GHZ and SYZ.
 * Walks along terrain, pauses to fire two projectiles in opposite directions,
 * then resumes walking. Follows slopes using ObjFloorDist.
 * <p>
 * Based on docs/s1disasm/_incObj/1F Crabmeat.asm.
 * <p>
 * State machine:
 * <ul>
 *   <li>ob2ndRout=0 (.waittofire): Idle/fire cycle. Timer expires → toggle fire mode.
 *       Fire mode: spawn 2 projectiles. Move mode: start walking.</li>
 *   <li>ob2ndRout=1 (.walkonfloor): Walk with terrain following. Timer expires or edge detected → return to idle.</li>
 * </ul>
 * <p>
 * Animation selection is based on terrain angle (flat/upslope/downslope) via Crab_SetAni.
 */
public class Sonic1CrabmeatBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType = 6 (enemy, collision size index 6)
    // Size 6: width=$14 (20px), height=$14 (20px)
    private static final int COLLISION_SIZE_INDEX = 0x06;

    // From disassembly: obYRad = $10
    private static final int Y_RADIUS = 0x10;

    // Crab_Main: obActWid = 42/2. BuildSprites uses the shared 32px
    // assumed-height band because sprite_customheight_bit is clear.
    private static final int DISPLAY_HALF_WIDTH = 42 / 2;
    private static final int ASSUMED_RENDER_HALF_HEIGHT = 32;

    // Walking velocity: move.w #$80,obVelX(a0)
    private static final int WALK_VELOCITY = 0x80;

    // Projectile X velocity: move.w #$100,obVelX(a1) / move.w #-$100,obVelX(a1)
    private static final int PROJECTILE_X_VEL = 0x100;

    // Projectile initial Y velocity: move.w #-$400,obVelY(a1)
    private static final int PROJECTILE_Y_VEL = -0x400;

    // Projectile spawn X offset: move.w obX(a0),obX(a1) / addi.w #$10,obX(a1)
    private static final int PROJECTILE_X_OFFSET = 0x10;

    // Timer values (in frames, 60fps)
    private static final int WALK_DURATION = 127;     // crab_timedelay for walking
    private static final int FIRE_DELAY = 59;         // crab_timedelay for firing pose
    private static final int IDLE_DELAY = 59;         // crab_timedelay after walking returns to idle

    // Terrain check offset for edge detection: addi.w #$10,d0 or subi.w #$20,d0
    private static final int TERRAIN_CHECK_FORWARD = 0x10;
    private static final int TERRAIN_CHECK_BACKWARD = 0x20;

    // Floor detection thresholds from disassembly .walkonfloor:
    // cmpi.w #-8,d1 / blt.s .retreat / cmpi.w #$C,d1 / bge.s .retreat
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // Slope angle thresholds for animation selection (Crab_SetAni):
    // cmpi.b #6,d3 (positive angle >= 6 = upslope)
    // cmpi.b #-6,d3 (negative angle <= -6 = downslope)
    private static final int SLOPE_THRESHOLD = 6;

    // Secondary routine states
    private static final int STATE_WAIT_FIRE = 0;
    private static final int STATE_WALK = 1;

    // crab_mode bit flags (objoff_32)
    private static final int MODE_BIT_DIRECTION = 0x01;
    private static final int MODE_BIT_FIRE = 0x02;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    private int secondaryState;
    private int timeDelay;         // crab_timedelay (objoff_30)
    private int crabMode;          // crab_mode (objoff_32)
    private byte terrainAngle;     // obAngle - surface angle for animation selection
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int fallVelocity;      // obVelY for ObjectFall during init
    private boolean initialized;
    // Crab_Main returns without DisplaySprite, so obRender bit 7 is guaranteed
    // clear when the first Crab_Action frame tests it.
    private boolean renderFlagClearFromInvisibleInit;

    // Animation state
    private int baseAnimIndex;     // Animation index from Crab_SetAni (0-2)
    private int renderedFrame;     // Actual mapping frame for rendering

    public Sonic1CrabmeatBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Crabmeat");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        // S1: obStatus bit 0 set = facing right (xFlip)
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;
        this.secondaryState = STATE_WAIT_FIRE;
        this.timeDelay = 0;
        this.crabMode = 0;
        this.terrainAngle = 0;
        this.fallVelocity = 0;
        this.initialized = false;
        this.renderFlagClearFromInvisibleInit = true;
        this.baseAnimIndex = 0;
        this.renderedFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            initialize();
            return;
        }

        switch (secondaryState) {
            case STATE_WAIT_FIRE -> updateWaitFire();
            case STATE_WALK -> updateWalk();
        }
        renderFlagClearFromInvisibleInit = false;
    }

    /**
     * Routine 0: Crab_Main - Runs every frame until floor is found.
     * ROM: ObjectFall applies current velocity to position FIRST, then adds gravity
     * to velocity for the next frame. ObjFloorDist checks floor.
     * Only advances to routine 2 when floor distance is negative (inside ground).
     * If floor not found or distance >= 0, keeps falling next frame.
     */
    private void initialize() {
        // ObjectFall: apply CURRENT velocity to position, THEN add gravity for next frame.
        // ROM order: move.w obVelY(a0),d0 / addi.w #$38,obVelY(a0) / ... / add.l d0,d3
        // The velocity applied to position is the value BEFORE gravity is added.
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = fallVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentY = motion.y;
        fallVelocity = motion.yVel;

        // ObjFloorDist: check floor from feet
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);

        // ROM: tst.w d1 / bpl.s .floornotfound
        // Only snap if distance < 0 (object's feet are inside the floor)
        if (floorResult.foundSurface() && floorResult.distance() < 0) {
            currentY += floorResult.distance();
            terrainAngle = floorResult.angle();
            fallVelocity = 0; // move.w #0,obVelY(a0)
            initialized = true; // addq.b #2,obRoutine(a0)
            updateAnimIndex();
        }
        // If floor not found: stay in routine 0, keep falling next frame
    }

    /**
     * ob2ndRout=0: Wait/Fire cycle.
     * Decrement timer. When expired:
     * - If off-screen (obRender bit 7 clear): go directly to movecrab (start walking)
     * - If on-screen: toggle fire mode bit. Fire → spawn projectiles, Move → start walking.
     */
    private void updateWaitFire() {
        timeDelay--;
        if (timeDelay >= 0) {
            return; // bpl.s .dontmove
        }

        // Timer expired
        // ROM: tst.b obRender(a0) / bpl.s .movecrab (off-screen → skip toggle, go to walk)
        if (renderFlagClearFromInvisibleInit
                || !isWithinRenderSpriteBounds(DISPLAY_HALF_WIDTH, ASSUMED_RENDER_HALF_HEIGHT)) {
            startWalking();
            return;
        }

        // On-screen: toggle fire mode bit.
        // ROM: bchg #1,crab_mode(a0) / bne.s .fire branches on the old bit value.
        boolean wasFireBitSet = (crabMode & MODE_BIT_FIRE) != 0;
        crabMode ^= MODE_BIT_FIRE;

        if (wasFireBitSet) {
            // Bit was set, now clear: fire
            fireProjectiles();
        } else {
            // Bit was clear, now set: start walking
            startWalking();
        }
    }

    /**
     * .fire: Spawn two projectiles in opposite directions.
     * Left projectile at X-$10, right projectile at X+$10, both at current Y.
     */
    private void fireProjectiles() {
        timeDelay = FIRE_DELAY;
        renderedFrame = 4; // Firing animation (mapping frame 4)

        // Left projectile
        spawnFreeChild(() -> new Sonic1CrabmeatProjectileInstance(
                currentX - PROJECTILE_X_OFFSET, currentY,
                -PROJECTILE_X_VEL, PROJECTILE_Y_VEL,
                this));

        // Right projectile
        spawnFreeChild(() -> new Sonic1CrabmeatProjectileInstance(
                currentX + PROJECTILE_X_OFFSET, currentY,
                PROJECTILE_X_VEL, PROJECTILE_Y_VEL,
                this));
    }

    /**
     * .movecrab: Start walking.
     * ROM: move.w #$80,obVelX / bchg #0,obStatus / bne.s .noflip / neg.w obVelX
     * Toggle facing direction (obStatus bit 0), set velocity accordingly.
     */
    private void startWalking() {
        secondaryState = STATE_WALK;
        timeDelay = WALK_DURATION;

        // ROM sets velocity to positive first, then toggles obStatus bit 0:
        // bchg #0,obStatus(a0) / bne.s .noflip / neg.w obVelX(a0)
        // bchg tests the OLD bit; bne branches if OLD bit was SET (Z=0).
        // OLD bit CLEAR → now SET → neg → walk LEFT  (facingLeft becomes false)
        // OLD bit SET   → now CLEAR → keep → walk RIGHT (facingLeft becomes true)
        facingLeft = !facingLeft;
        xVelocity = facingLeft ? WALK_VELOCITY : -WALK_VELOCITY;

        // Set walking animation (base + 3)
        updateAnimIndex();
    }

    /**
     * ob2ndRout=1: Walking on floor.
     * Decrement timer, apply velocity, check terrain ahead.
     * If floor disappears or timer expires, return to idle.
     */
    private void updateWalk() {
        timeDelay--;
        if (timeDelay < 0) {
            // Timer expired: return to idle
            returnToIdle();
            return;
        }

        // SpeedToPos: apply velocity with subpixel precision
        motion.x = currentX;
        motion.xVel = xVelocity;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;

        // Toggle terrain check each frame
        // ROM: bchg #0,crab_mode(a0) / bne.s loc_9654
        // bchg tests the OLD bit value: bne branches if bit WAS set (now clear)
        // Bit was SET → snap to floor (loc_9654)
        // Bit was CLEAR → edge check (fall through)
        boolean wasSet = (crabMode & MODE_BIT_DIRECTION) != 0;
        crabMode ^= MODE_BIT_DIRECTION;

        if (wasSet) {
            // Bit was set (bne taken): snap to floor at current position, update angle + anim
            // ROM: loc_9654: ObjFloorDist / add.w d1,obY / move.b d3,obAngle / Crab_SetAni
            TerrainCheckResult snapResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
            if (snapResult.foundSurface()) {
                currentY += snapResult.distance();
                terrainAngle = snapResult.angle();
            }
            updateAnimIndex();
            return;
        }

        // Bit was clear (fall through): edge detection check
        // ROM: addi.w #$10,d3 / btst #0,obStatus(a0) / beq.s (skip sub) / subi.w #$20,d3
        // obStatus bit 0 CLEAR (facingLeft=true): check at X + $10
        // obStatus bit 0 SET (facingLeft=false): check at X + $10 - $20 = X - $10
        int checkX;
        if (facingLeft) {
            checkX = currentX + TERRAIN_CHECK_FORWARD;
        } else {
            checkX = currentX + TERRAIN_CHECK_FORWARD - TERRAIN_CHECK_BACKWARD;
        }

        TerrainCheckResult aheadResult = ObjectTerrainUtils.checkFloorDist(checkX, currentY, Y_RADIUS);
        int floorDist = aheadResult.foundSurface() ? aheadResult.distance() : 100;

        // Edge detection: cmpi.w #-8,d1 / blt.s .retreat / cmpi.w #$C,d1 / bge.s .retreat
        if (floorDist < FLOOR_MIN_DIST || floorDist >= FLOOR_MAX_DIST) {
            returnToIdle();
        }
        // ROM: rts - edge check frame does NOT snap to floor (that's done on the alternate frame)
    }

    /**
     * Return to idle state: zero velocity, set idle timer, update animation.
     */
    private void returnToIdle() {
        secondaryState = STATE_WAIT_FIRE;
        timeDelay = IDLE_DELAY;
        xVelocity = 0;
        updateAnimIndex();
    }

    /**
     * Crab_SetAni: Select animation index based on terrain angle and facing direction.
     * <pre>
     * If angle >= 0 (flat or upslope):
     *   angle < 6  → anim 0 (flat)
     *   angle >= 6 → anim 1 or 2 depending on facing
     * If angle < 0 (downslope):
     *   angle > -6 → anim 0 (flat)
     *   angle <= -6 → anim 1 or 2 depending on facing
     * </pre>
     */
    private void updateAnimIndex() {
        int angle = terrainAngle; // signed byte

        if (angle >= 0) {
            if (angle < SLOPE_THRESHOLD) {
                baseAnimIndex = 0;
            } else {
                // ROM: btst #0,obStatus(a0) / bne.s .revslope1
                baseAnimIndex = facingLeft ? 2 : 1;
            }
        } else {
            if (angle > -SLOPE_THRESHOLD) {
                baseAnimIndex = 0;
            } else {
                baseAnimIndex = facingLeft ? 1 : 2;
            }
        }

        // Update rendered frame based on state
        if (secondaryState == STATE_WALK) {
            // Walking: use walking animations (base + 3)
            // Animation script alternates between two frames
            // Walk flat: frames 1, $21(=1 flipped), 0
            // Walk slope: frames $21(=1 flipped), 3, 2
            // For simplicity matching ROM animation: use mapping frames based on anim index
            updateWalkFrame();
        } else {
            // Standing: use standing animation
            updateStandFrame();
        }
    }

    /**
     * Updates the rendered frame for standing animations.
     * Stand flat (anim 0): mapping frame 0
     * Stand slope (anim 1): mapping frame 2
     * Stand slope reversed (anim 2): mapping frame 2 (x-flipped by renderer)
     */
    private void updateStandFrame() {
        renderedFrame = switch (baseAnimIndex) {
            case 1 -> 2; // slope1
            case 2 -> 2; // slope2 (flipped in renderer via x-flip toggle)
            default -> 0; // flat standing
        };
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation is driven by state in updateMovement, using ROM animation scripts.
        // Walking animations alternate between two frames at speed $0F (15 frames).
        if (secondaryState == STATE_WALK) {
            animTimer++;
            if (animTimer >= 0x10) { // speed $0F = show for 16 frames
                animTimer = 0;
            }
            updateWalkFrame();
        }
    }

    /**
     * Updates the rendered frame for walking animations based on the animation timer.
     * Walk flat (anim 3): frames 1, 0 (with $21 being frame 1 h-flipped)
     * Walk slope (anim 4): frames 3, 2 (with $21 being frame 1 h-flipped)
     * Walk slope reversed (anim 5): frames 1, 2 (with $23 being frame 3 h-flipped)
     */
    private void updateWalkFrame() {
        boolean secondHalf = (animTimer >= 0x08);
        renderedFrame = switch (baseAnimIndex) {
            case 1 -> secondHalf ? 2 : 3; // slope walk
            case 2 -> secondHalf ? 2 : 3; // slope walk reversed
            default -> secondHalf ? 0 : 1; // flat walk
        };
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    public boolean isPersistent() {
        // Crab_Action ends at RememberState, whose out_of_range macro deletes the
        // object once its chunk-aligned X leaves the [camera-128, camera-128+0x280]
        // window (docs/s1disasm/_incObj/1F Badnik - Crabmeat.asm:59 ->
        // _incObj/sub RememberState.asm:9 -> Macros.asm:278-295).
        return !isDestroyed() && isInRange();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3); // obPriority = 3
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.CRABMEAT);
        if (renderer == null) return;

        // Crabmeat art is symmetric (left/right pieces are h-flipped in mappings).
        // The sprite faces camera by default. H-flip based on facing direction.
        renderer.drawFrameIndex(renderedFrame, currentX, currentY, facingLeft, false);
    }
}
