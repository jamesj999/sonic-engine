package com.openggf.game.sonic1.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.PlayableEntity;

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
 * Newtron (0x42) - Chameleon-like Badnik from Green Hill Zone.
 * Has two distinct subtypes:
 * <ul>
 *   <li>Type 0 (subtype 0x00): Appears, falls to ground, then walks horizontally</li>
 *   <li>Type 1 (subtype != 0): Appears using palette line 1, fires a missile, then disappears</li>
 * </ul>
 * <p>
 * Based on docs/s1disasm/_incObj/42 Newtron.asm.
 * <p>
 * State machine (ob2ndRout values):
 * <ul>
 *   <li>0 (.chkdistance): Activation detection - waits until player within $80 pixels</li>
 *   <li>2 (.type00): Type 0 appearing animation, then falling with gravity</li>
 *   <li>4 (.matchfloor): Floor attachment - terrain-following horizontal movement</li>
 *   <li>6 (.speed): Constant-speed horizontal movement (no terrain following)</li>
 *   <li>8 (.type01): Type 1 firing sequence - plays firing animation, spawns missile</li>
 * </ul>
 * <p>
 * Animation scripts (Ani_Newt):
 * <ul>
 *   <li>0 (A_Newt_Blank): frame $A at speed $F - invisible/idle</li>
 *   <li>1 (A_Newt_Drop): frames 0,1,3,4,5 at speed $13 with afBack 1 - appearing/drop</li>
 *   <li>2 (A_Newt_Fly1): frames 6,7 at speed 2 - flying/walking variant 1</li>
 *   <li>3 (A_Newt_Fly2): frames 8,9 at speed 2 - flying/walking variant 2</li>
 *   <li>4 (A_Newt_Fires): frames 0,1,1,2,1,1,0 at speed $13 with afRoutine - firing</li>
 * </ul>
 */
public class Sonic1NewtronBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType values used during different phases
    private static final int COLLISION_SIZE_FALLING = 0x0C;
    private static final int COLLISION_SIZE_MOVING = 0x0D;

    // From disassembly: obHeight = $10, obWidth = 8
    private static final int Y_RADIUS = 0x10;

    // From disassembly: cmpi.w #$80,d0 - activation distance
    private static final int ACTIVATION_RANGE = 0x80;

    // From disassembly: move.w #$200,obVelX(a0) - horizontal velocity
    private static final int MOVE_VELOCITY = 0x200;

    // ObjectFall gravity: addi.w #$38,obVelY(a0)
    private static final int GRAVITY = 0x38;

    // Floor detection thresholds from .matchfloor:
    // cmpi.w #-8,d1 / blt / cmpi.w #$C,d1 / bge
    private static final int FLOOR_MIN_DIST = -8;
    private static final int FLOOR_MAX_DIST = 0x0C;

    // Missile spawn offsets from disassembly:
    // subq.w #8,obY(a1) - missile Y offset
    // move.w #$14,d0 - missile X offset
    private static final int MISSILE_Y_OFFSET = -8;
    private static final int MISSILE_X_OFFSET = 0x14;
    // move.w #$200,obVelX(a1) - missile velocity
    private static final int MISSILE_VELOCITY = 0x200;

    // Animation timing: speed byte + 1 = ticks per frame step
    // A_Newt_Drop: speed=$13 -> 20 ticks per step
    private static final int ANIM_SPEED_DROP = 0x13 + 1;
    // A_Newt_Fly: speed=2 -> 3 ticks per step
    private static final int ANIM_SPEED_FLY = 2 + 1;
    // A_Newt_Blank: speed=$F -> 16 ticks per step
    private static final int ANIM_SPEED_BLANK = 0x0F + 1;
    // A_Newt_Fires: speed=$13 -> 20 ticks per step
    private static final int ANIM_SPEED_FIRES = 0x13 + 1;

    // Animation frame sequences (mapping frame indices)
    // A_Newt_Drop: 0, 1, 3, 4, 5, [afBack 1 -> loops back to frame 5]
    private static final int[] DROP_FRAMES = {0, 1, 3, 4, 5};
    // A_Newt_Fires: 0, 1, 1, 2, 1, 1, 0, [afRoutine]
    private static final int[] FIRES_FRAMES = {0, 1, 1, 2, 1, 1, 0};

    // Sub-state machine values (ob2ndRout / 2)
    private static final int STATE_CHECK_DISTANCE = 0;
    private static final int STATE_TYPE00 = 1;
    private static final int STATE_MATCH_FLOOR = 2;
    private static final int STATE_SPEED = 3;
    private static final int STATE_TYPE01 = 4;

    private boolean isType1;               // true if subtype != 0 (missile-firing variant)
    private int secondaryState;
    private int fallVelocity;              // obVelY during falling phase
    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int collisionSizeIndex;        // Current collision type (changes between phases)
    private boolean collisionEnabled;      // obColType set after appearing

    // Animation state
    private int currentAnim;               // Current animation script index (0-4)
    private int animStepIndex;             // Index within the current animation sequence
    private int animTickCounter;           // Ticks within current animation step
    private int mappingFrame;              // Current mapping frame index for rendering

    // Type 1 specific
    private boolean missileFired;          // objoff_32: prevents firing more than once

    public Sonic1NewtronBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Newtron");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.facingLeft = false;

        this.isType1 = spawn.subtype() != 0;
        this.secondaryState = STATE_CHECK_DISTANCE;
        this.fallVelocity = 0;
        this.collisionSizeIndex = COLLISION_SIZE_FALLING;
        this.collisionEnabled = false;

        // Start with blank animation (anim 0: frame $A at speed $F)
        this.currentAnim = 0;
        this.animStepIndex = 0;
        this.animTickCounter = 0;
        this.mappingFrame = 10; // M_Newt_Blank

        this.missileFired = false;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (secondaryState) {
            case STATE_CHECK_DISTANCE -> updateCheckDistance(player);
            case STATE_TYPE00 -> updateType00(player);
            case STATE_MATCH_FLOOR -> updateMatchFloor();
            case STATE_SPEED -> updateSpeed();
            case STATE_TYPE01 -> updateType01();
        }
    }

    /**
     * Sub-state 0 (.chkdistance): Check if player is within activation range.
     * <pre>
     * bset    #0,obStatus(a0)              ; assume Sonic is to the right
     * move.w  (v_player+obX).w,d0
     * sub.w   obX(a0),d0
     * bcc.s   .sonicisright                ; if positive, Sonic IS right
     * neg.w   d0
     * bclr    #0,obStatus(a0)              ; Sonic is to the left
     * .sonicisright:
     * cmpi.w  #$80,d0                      ; within 128px?
     * bhs.s   .outofrange
     * </pre>
     */
    private void updateCheckDistance(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Determine facing direction based on player position
        int dx = player.getCentreX() - currentX;
        facingLeft = dx < 0;

        // Check activation range
        int absDx = Math.abs(dx);
        if (absDx >= ACTIVATION_RANGE) {
            return; // Out of range, stay idle
        }

        // Activated - choose behavior based on subtype
        if (!isType1) {
            // Type 0: appear and drop
            secondaryState = STATE_TYPE00;
            setAnimation(1); // A_Newt_Drop
        } else {
            // Type 1: fire missile
            // move.w #make_art_tile(ArtTile_Newtron,1,0),obGfx(a0) - palette line 1
            secondaryState = STATE_TYPE01;
            setAnimation(4); // A_Newt_Fires
        }
    }

    /**
     * Sub-state 2 (.type00): Appearing animation, then falling.
     * <pre>
     * cmpi.b  #4,obFrame(a0)   ; has appearing animation reached frame 4 (M_Newt_Drop2)?
     * bhs.s   .fall
     * ; During animation: keep updating facing direction
     * .fall:
     * cmpi.b  #1,obFrame(a0)   ; (dead code for type 0: obFrame is always >= 4 here)
     * bne.s   .loc_DE42
     * move.b  #$C,obColType(a0)
     * .loc_DE42:
     * bsr.w   ObjectFall       ; apply gravity
     * bsr.w   ObjFloorDist     ; check floor
     * tst.w   d1               ; hit floor?
     * bpl.s   .keepfalling
     * ; Land: snap to floor, set walking state
     * </pre>
     */
    private void updateType00(AbstractPlayableSprite player) {
        // Phase 1: Appearing animation - ROM checks cmpi.b #4,obFrame(a0)
        // Wait until mapping frame reaches 4 (M_Newt_Drop2) before falling
        if (mappingFrame < 4) {
            // During appearing animation, keep tracking player direction
            if (player != null) {
                facingLeft = player.getCentreX() < currentX;
            }
            return;
        }

        // Phase 2: Falling
        // Note: ROM's cmpi.b #1,obFrame check inside .fall is dead code for type 0
        // because obFrame is always >= 4 when .fall is reached. No collision is enabled
        // during the falling phase; collision type $D is only set upon landing.

        // ObjectFall: apply velocity to position, then add gravity
        motion.x = currentX;
        motion.y = currentY;
        motion.xVel = 0;
        motion.yVel = fallVelocity;
        SubpixelMotion.moveSprite(motion, GRAVITY);
        currentY = motion.y;
        fallVelocity = motion.yVel;

        // ObjFloorDist: check floor from feet
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        int floorDist = floorResult.foundSurface() ? floorResult.distance() : 100;

        if (floorDist >= 0) {
            return; // Still falling
        }

        // Landed: snap to floor
        currentY += floorDist;
        fallVelocity = 0;

        // Transition to floor-following state
        secondaryState = STATE_MATCH_FLOOR;

        // Choose fly animation based on obGfx bit 5 (palette line indicator)
        // btst #5,obGfx(a0) / beq.s .notgreen / addq.b #1,obAnim(a0)
        // Type 0 with palette 0 -> anim 2 (Fly1); if palette bit set -> anim 3 (Fly2)
        setAnimation(2); // A_Newt_Fly1 (default for Type 0)

        // Enable stronger collision for horizontal movement
        collisionSizeIndex = COLLISION_SIZE_MOVING;
        collisionEnabled = true;

        // Set horizontal velocity based on facing direction
        xVelocity = facingLeft ? -MOVE_VELOCITY : MOVE_VELOCITY;
    }

    /**
     * Sub-state 4 (.matchfloor): Terrain-following horizontal movement.
     * <pre>
     * bsr.w   SpeedToPos
     * bsr.w   ObjFloorDist
     * cmpi.w  #-8,d1 / blt.s .nextroutine
     * cmpi.w  #$C,d1 / bge.s .nextroutine
     * add.w   d1,obY(a0)
     * </pre>
     */
    private void updateMatchFloor() {
        // SpeedToPos: apply velocity with subpixel precision
        motion.x = currentX;
        motion.xVel = xVelocity;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;

        // ObjFloorDist
        TerrainCheckResult floorResult = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        int floorDist = floorResult.foundSurface() ? floorResult.distance() : 100;

        // Check if floor distance is within acceptable range
        if (floorDist < FLOOR_MIN_DIST || floorDist >= FLOOR_MAX_DIST) {
            // Out of range: transition to constant-speed (no terrain following)
            secondaryState = STATE_SPEED;
            return;
        }

        // Snap to floor slope
        currentY += floorDist;
    }

    /**
     * Sub-state 6 (.speed): Constant horizontal movement, no terrain following.
     * <pre>
     * bsr.w   SpeedToPos
     * rts
     * </pre>
     */
    private void updateSpeed() {
        // SpeedToPos: apply velocity with subpixel precision
        motion.x = currentX;
        motion.xVel = xVelocity;
        SubpixelMotion.moveX(motion);
        currentX = motion.x;
    }

    /**
     * Sub-state 8 (.type01): Type 1 firing sequence.
     * <pre>
     * cmpi.b  #1,obFrame(a0) / bne.s .firemissile
     * move.b  #$C,obColType(a0)           ; enable collision at frame 1
     * .firemissile:
     * cmpi.b  #2,obFrame(a0) / bne.s .fail
     * tst.b   objoff_32(a0) / bne.s .fail ; only fire once
     * move.b  #1,objoff_32(a0)
     * ; spawn missile...
     * </pre>
     */
    private void updateType01() {
        // Enable collision when mapping frame reaches 1 (Normal standing frame)
        if (!collisionEnabled && mappingFrame >= 1) {
            collisionEnabled = true;
            collisionSizeIndex = COLLISION_SIZE_FALLING;
        }

        // Fire missile when mapping frame is 2 (Fires frame) and hasn't fired yet
        if (mappingFrame == 2 && !missileFired) {
            missileFired = true;
            fireMissile();
        }
    }

    /**
     * Spawns a missile at the Newtron's position with appropriate offsets.
     * <pre>
     * move.w  obX(a0),obX(a1)
     * move.w  obY(a0),obY(a1)
     * subq.w  #8,obY(a1)                  ; offset Y up by 8
     * move.w  #$200,obVelX(a1)
     * move.w  #$14,d0
     * btst    #0,obStatus(a0) / bne.s .noflip
     * neg.w   d0 / neg.w obVelX(a1)
     * .noflip:
     * add.w   d0,obX(a1)
     * </pre>
     */
    private void fireMissile() {
        int missileXVel = MISSILE_VELOCITY;
        int xOffset = MISSILE_X_OFFSET;

        if (facingLeft) {
            missileXVel = -missileXVel;
            xOffset = -xOffset;
        }

        int missileX = currentX + xOffset;
        int missileY = currentY + MISSILE_Y_OFFSET;

        final int fMissileX = missileX;
        final int fMissileY = missileY;
        final int fMissileXVel = missileXVel;
        spawnFreeChild(() -> new Sonic1NewtronMissileInstance(
                fMissileX, fMissileY, fMissileXVel, facingLeft));
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animTickCounter++;

        int speed = getAnimSpeed();
        if (animTickCounter < speed) {
            return;
        }
        animTickCounter = 0;

        switch (currentAnim) {
            case 0 -> {
                // A_Newt_Blank: frame $A (10), loops via afEnd
                mappingFrame = 10;
            }
            case 1 -> {
                // A_Newt_Drop: 0, 1, 3, 4, 5, afBack 1 (loops frame 5)
                if (animStepIndex < DROP_FRAMES.length - 1) {
                    animStepIndex++;
                }
                // afBack 1: once past the end, keep looping the last frame (5)
                mappingFrame = DROP_FRAMES[animStepIndex];
            }
            case 2 -> {
                // A_Newt_Fly1: frames 6, 7, afEnd (loops)
                animStepIndex = (animStepIndex + 1) % 2;
                mappingFrame = 6 + animStepIndex;
            }
            case 3 -> {
                // A_Newt_Fly2: frames 8, 9, afEnd (loops)
                animStepIndex = (animStepIndex + 1) % 2;
                mappingFrame = 8 + animStepIndex;
            }
            case 4 -> {
                // A_Newt_Fires: 0, 1, 1, 2, 1, 1, 0, afRoutine
                if (animStepIndex < FIRES_FRAMES.length - 1) {
                    animStepIndex++;
                    mappingFrame = FIRES_FRAMES[animStepIndex];
                }
                // afRoutine: after finishing, stay on last frame
                // (ROM would increment ob2ndRout, but firing is already handled)
            }
        }
    }

    private int getAnimSpeed() {
        return switch (currentAnim) {
            case 0 -> ANIM_SPEED_BLANK;
            case 1 -> ANIM_SPEED_DROP;
            case 2, 3 -> ANIM_SPEED_FLY;
            case 4 -> ANIM_SPEED_FIRES;
            default -> ANIM_SPEED_BLANK;
        };
    }

    private void setAnimation(int animIndex) {
        if (currentAnim == animIndex) {
            return;
        }
        currentAnim = animIndex;
        animStepIndex = 0;
        animTickCounter = 0;

        // Set initial mapping frame for each animation
        mappingFrame = switch (animIndex) {
            case 0 -> 10;                    // M_Newt_Blank
            case 1 -> DROP_FRAMES[0];        // First frame of drop sequence (0)
            case 2 -> 6;                     // M_Newt_Fly1a
            case 3 -> 8;                     // M_Newt_Fly2a
            case 4 -> FIRES_FRAMES[0];       // First frame of fires sequence (0)
            default -> 10;
        };
    }

    @Override
    protected int getCollisionSizeIndex() {
        return collisionSizeIndex;
    }

    @Override
    public int getCollisionFlags() {
        if (!collisionEnabled) {
            return 0;
        }
        return super.getCollisionFlags();
    }

    @Override
    protected DestructionConfig getDestructionConfig() {
        return Sonic1DestructionConfig.S1_DESTRUCTION_CONFIG;
    }

    @Override
    public boolean isPersistent() {
        // Newt_Action ends at RememberState, whose out_of_range macro deletes the
        // object once its chunk-aligned X leaves the [camera-128, camera-128+0x280]
        // window (docs/s1disasm/_incObj/42 Badnik - Newtron.asm:38 ->
        // _incObj/sub RememberState.asm:9 -> Macros.asm:278-295).
        return !isDestroyed() && isInRange();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // obPriority = 4
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.NEWTRON);
        if (renderer == null) return;

        // S1 convention: default sprite art faces left, hFlip = true when facing right
        // Type 1 uses palette line 1 (green): make_art_tile(ArtTile_Newtron,1,0)
        if (isType1) {
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, !facingLeft, false, 1);
        } else {
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, !facingLeft, false);
        }
    }
}
