package com.openggf.game.sonic1.objects.badniks;

import com.openggf.audio.AudioManager;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Batbrain / Basaran (0x55) - Bat Badnik from Marble Zone.
 * Hangs from the ceiling and drops when Sonic walks beneath it, flies
 * horizontally toward Sonic, then returns to the ceiling.
 * <p>
 * Based on docs/s1disasm/_incObj/55 Basaran.asm.
 * <p>
 * State machine (ob2ndRout / 2):
 * <ul>
 *   <li>0 (.dropcheck): Hanging on ceiling. Checks if Sonic is within $80 pixels
 *       horizontally AND below (within $80 pixels vertically). Drops on a
 *       randomized frame tick (v_vbla_byte + d7) & 7 == 0.</li>
 *   <li>1 (.dropfly): Falling with gravity ($18/frame). Tracks horizontal distance
 *       to Sonic. When within $10 pixels of Sonic's saved Y, transitions to
 *       horizontal flight with X velocity toward Sonic.</li>
 *   <li>2 (.flapsound): Horizontal flight. Plays flapping sound every 16 frames.
 *       Transitions to flyup when Sonic is >= $80 pixels away horizontally,
 *       gated by same randomized frame tick.</li>
 *   <li>3 (.flyup): Ascending with -$18 deceleration. When hitting ceiling
 *       (ObjHitCeiling returns d1 < 0), snaps to ceiling, clears velocity,
 *       resets to state 0.</li>
 * </ul>
 * <p>
 * Animations (Ani_Bas):
 * <ul>
 *   <li>0 (.still): frame 0 at speed $F - hanging from ceiling</li>
 *   <li>1 (.fall): frame 1 at speed $F - falling body</li>
 *   <li>2 (.fly): frames 1, 2, 3, 2 at speed 3 - wing flapping cycle</li>
 * </ul>
 */
public class Sonic1BatbrainBadnikInstance extends AbstractBadnikInstance implements SpawnRewindRecreatable {

    // From disassembly: obColType = $B (enemy category 0x00, size index $0B)
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // From disassembly: obHeight = $C (12 pixels)
    private static final int Y_RADIUS = 0x0C;

    // From disassembly: move.w #$80,d2 (proximity check distance)
    private static final int PROXIMITY_RANGE = 0x80;

    // From disassembly: cmpi.w #$80,d0 (vertical drop range)
    private static final int DROP_VERTICAL_RANGE = 0x80;

    // From disassembly: cmpi.w #$10,d0 (vertical distance to start horizontal flight)
    private static final int HORIZONTAL_FLIGHT_THRESHOLD = 0x10;

    // From disassembly: addi.w #$18,obVelY(a0) / subi.w #$18,obVelY(a0)
    private static final int GRAVITY = 0x18;

    // From disassembly .chkdistance: move.w #$100,d1 (horizontal flight speed)
    private static final int HORIZONTAL_VELOCITY = 0x100;

    // From disassembly: cmpi.w #$80,d0 (.flapsound horizontal distance for flyup transition)
    private static final int FLYUP_DISTANCE = 0x80;

    // State machine values (ob2ndRout / 2)
    private static final int STATE_DROP_CHECK = 0;
    private static final int STATE_DROP_FLY = 1;
    private static final int STATE_FLAP_SOUND = 2;
    private static final int STATE_FLY_UP = 3;

    // Animation IDs from Ani_Bas
    private static final int ANIM_STILL = 0;
    private static final int ANIM_FALL = 1;
    private static final int ANIM_FLY = 2;

    // Animation speeds from _anim/Basaran.asm: still=$F, fall=$F, fly=3
    private static final int ANIM_SPEED_STILL = 0x0F + 1;  // 16 ticks per frame
    private static final int ANIM_SPEED_FALL = 0x0F + 1;   // 16 ticks per frame
    private static final int ANIM_SPEED_FLY = 3 + 1;       // 4 ticks per frame

    // Fly animation frame sequence from Ani_Bas .fly: 1, 2, 3, 2
    private static final int[] FLY_FRAMES = {1, 2, 3, 2};

    // Randomization mask: (v_vbla_byte + d7) & 7
    private static final int RANDOM_MASK = 7;

    // Flap sound interval mask: v_vbla_byte & $F
    private static final int FLAP_SOUND_MASK = 0x0F;

    private int state;              // ob2ndRout / 2
    private int currentAnim;        // Current animation ID
    private int animTickCounter;    // Ticks within current animation
    private final SubpixelMotion.State motionState; // Subpixel position/velocity state
    private int targetY;            // objoff_36: Sonic's Y position when drop was initiated

    public Sonic1BatbrainBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Batbrain");
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.facingLeft = false;

        this.state = STATE_DROP_CHECK;
        this.currentAnim = ANIM_STILL;
        this.animTickCounter = 0;
        this.xVelocity = 0;
        this.yVelocity = 0;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.targetY = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case STATE_DROP_CHECK -> updateDropCheck(vIntRunCount, player);
            case STATE_DROP_FLY -> updateDropFly(vIntRunCount, player);
            case STATE_FLAP_SOUND -> updateFlapSound(vIntRunCount, player);
            case STATE_FLY_UP -> updateFlyUp(vIntRunCount);
        }
    }

    /**
     * State 0 (.dropcheck): Hanging from ceiling, waiting for Sonic to be in range.
     * <pre>
     * move.w  #$80,d2
     * bsr.w   .chkdistance        ; is Sonic < $80 pixels away horizontally?
     * bcc.s   .nodrop             ; if not, branch
     * move.w  (v_player+obY).w,d0
     * move.w  d0,objoff_36(a0)    ; save Sonic's Y
     * sub.w   obY(a0),d0
     * bcs.s   .nodrop             ; if Sonic is above basaran, branch
     * cmpi.w  #$80,d0             ; is Sonic < $80 pixels below?
     * bhs.s   .nodrop             ; if not, branch
     * tst.w   (v_debuguse).w      ; is debug mode on?
     * bne.s   .nodrop             ; if yes, branch
     * move.b  (v_vbla_byte).w,d0
     * add.b   d7,d0
     * andi.b  #7,d0
     * bne.s   .nodrop
     * move.b  #1,obAnim(a0)
     * addq.b  #2,ob2ndRout(a0)
     * </pre>
     */
    private void updateDropCheck(int vIntRunCount, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // .chkdistance always updates facing, even when out of range.
        // bset #0,obStatus -> then bclr if Sonic is left.
        int dx = player.getCentreX() - currentX;
        facingLeft = dx < 0;

        // Check horizontal distance (bcc.s .nodrop if absDx >= $80)
        int absDx = Math.abs(dx);
        if (absDx >= PROXIMITY_RANGE) {
            return;
        }

        // Save Sonic's Y and check vertical distance
        int sonicY = player.getCentreY();
        int dy = sonicY - currentY;
        // bcs.s .nodrop: if Sonic is above basaran (dy < 0)
        if (dy < 0) {
            return;
        }
        // cmpi.w #$80,d0 / bhs.s .nodrop: if Sonic >= $80 pixels below
        if (dy >= DROP_VERTICAL_RANGE) {
            return;
        }

        // tst.w (v_debuguse).w / bne.s .nodrop
        if (player.isDebugMode()) {
            return;
        }

        // Randomized frame gate: (v_vbla_byte + d7) & 7 == 0
        // d7 comes from ExecuteObjects' loop counter, NOT from the object's SST
        // address. ExecuteObjects counts d7 from 127 down to 0 across all 128
        // slots, so slot N sees d7 = 127 - N. Different slots fire their timing
        // gates at different points in the 8-frame cycle.
        int d7 = Math.max(0, 127 - getSlotIndex());
        if (((vIntRunCount + d7) & RANDOM_MASK) != 0) {
            return;
        }

        // Drop initiated: save target Y, transition to drop state
        // move.w d0,objoff_36(a0) - save Sonic's Y from earlier
        targetY = sonicY;
        // move.b #1,obAnim(a0) - fall animation
        setAnimation(ANIM_FALL);
        // addq.b #2,ob2ndRout(a0) - advance to dropfly state
        state = STATE_DROP_FLY;
    }

    /**
     * State 1 (.dropfly): Falling with gravity toward Sonic's saved Y.
     * <pre>
     * bsr.w   SpeedToPos
     * addi.w  #$18,obVelY(a0)     ; gravity
     * move.w  #$80,d2
     * bsr.w   .chkdistance        ; update facing / get horizontal speed
     * move.w  objoff_36(a0),d0
     * sub.w   obY(a0),d0
     * bcs.s   .chkdel             ; if below target, check delete
     * cmpi.w  #$10,d0             ; within $10 pixels of target?
     * bhs.s   .dropmore           ; if not, keep falling
     * move.w  d1,obVelX(a0)       ; set horizontal velocity toward Sonic
     * move.w  #0,obVelY(a0)       ; stop falling
     * move.b  #2,obAnim(a0)       ; fly animation
     * addq.b  #2,ob2ndRout(a0)    ; transition to flapsound state
     * </pre>
     */
    private void updateDropFly(int vIntRunCount, AbstractPlayableSprite player) {
        // SpeedToPos: apply velocity to position
        applyVelocity();

        // Apply gravity
        yVelocity += GRAVITY;

        // .chkdistance: update facing direction and compute horizontal speed
        int horizontalSpeed = checkDistance(player);

        // Check if basaran has reached or passed target Y
        int distToTarget = targetY - currentY;
        if (distToTarget < 0) {
            // Below target Y - check if off-screen for deletion
            checkOffScreenDelete();
            return;
        }

        // Within $10 pixels of target: transition to horizontal flight
        if (distToTarget < HORIZONTAL_FLIGHT_THRESHOLD) {
            xVelocity = horizontalSpeed;
            yVelocity = 0;
            setAnimation(ANIM_FLY);
            state = STATE_FLAP_SOUND;
        }
    }

    /**
     * State 2 (.flapsound): Horizontal flight, playing flapping sounds.
     * <pre>
     * move.b  (v_vbla_byte).w,d0
     * andi.b  #$F,d0
     * bne.s   .nosound
     * move.w  #sfx_Basaran,d0
     * jsr     (QueueSound2).l     ; play flapping sound every 16th frame
     * .nosound:
     * bsr.w   SpeedToPos
     * move.w  (v_player+obX).w,d0
     * sub.w   obX(a0),d0
     * bcc.s   .isright
     * neg.w   d0
     * .isright:
     * cmpi.w  #$80,d0             ; is Sonic within $80 pixels?
     * blo.s   .dontflyup          ; if yes, stay flying
     * move.b  (v_vbla_byte).w,d0
     * add.b   d7,d0
     * andi.b  #7,d0
     * bne.s   .dontflyup
     * addq.b  #2,ob2ndRout(a0)    ; transition to flyup
     * </pre>
     */
    private void updateFlapSound(int vIntRunCount, AbstractPlayableSprite player) {
        // Play flapping sound every 16 frames
        if ((vIntRunCount & FLAP_SOUND_MASK) == 0) {
            services().playSfx(Sonic1Sfx.BASARAN_FLAP.id);
        }

        // SpeedToPos: apply velocity
        applyVelocity();

        // Check horizontal distance to Sonic
        if (player != null) {
            int dx = player.getCentreX() - currentX;
            int absDx = Math.abs(dx);

            // If Sonic is >= $80 pixels away, consider flying up
            if (absDx >= FLYUP_DISTANCE) {
                // Randomized frame gate for transition
                // d7 comes from ExecuteObjects' loop counter (same as dropcheck):
                // slot N sees d7 = 127 - N.
                int d7 = Math.max(0, 127 - getSlotIndex());
                if (((vIntRunCount + d7) & RANDOM_MASK) == 0) {
                    state = STATE_FLY_UP;
                }
            }
        }
    }

    /**
     * State 3 (.flyup): Ascending back to ceiling.
     * <pre>
     * bsr.w   SpeedToPos
     * subi.w  #$18,obVelY(a0)     ; decelerate / accelerate upward
     * bsr.w   ObjHitCeiling       ; check ceiling collision
     * tst.w   d1                  ; has basaran hit the ceiling?
     * bpl.s   .noceiling          ; if not, branch
     * sub.w   d1,obY(a0)          ; snap to ceiling
     * andi.w  #$FFF8,obX(a0)      ; align X to 8-pixel boundary
     * clr.w   obVelX(a0)
     * clr.w   obVelY(a0)
     * clr.b   obAnim(a0)          ; still animation
     * clr.b   ob2ndRout(a0)       ; back to dropcheck state
     * </pre>
     */
    private void updateFlyUp(int vIntRunCount) {
        // SpeedToPos: apply velocity
        applyVelocity();

        // Decelerate vertically (accelerate upward)
        yVelocity -= GRAVITY;

        // ObjHitCeiling: check ceiling collision
        // ROM ObjHitCeiling probes at (x, y - obHeight) upward.
        // Returns d1 = distance to ceiling (negative = hit).
        // ObjHitCeiling enters FindFloor after EOR #$F on the probe Y. Use the
        // native upward path so its distance is not shifted by the legacy
        // surfaceY = tileTop + metric - 1 convention. That one-pixel shift
        // leaves a rehung Batbrain one pixel too high and can delay its next
        // fall-to-flight transition by a frame.
        TerrainCheckResult result = ObjectTerrainUtils.checkNativeUpwardCeilingDist(
                currentX, currentY, Y_RADIUS);
        if (result.foundSurface() && result.distance() < 0) {
            // Hit ceiling: snap position and reset
            // ROM: sub.w d1,obY(a0) -> obY -= d1 (d1 is negative, so pushes down)
            currentY -= result.distance();
            // andi.w #$FFF8,obX(a0) - align X to 8-pixel boundary
            currentX = currentX & 0xFFF8;
            xVelocity = 0;
            yVelocity = 0;
            setAnimation(ANIM_STILL);
            state = STATE_DROP_CHECK;
        }
    }

    /**
     * Mirrors the ROM .chkdistance subroutine.
     * Sets facing direction based on Sonic's position relative to basaran.
     * Returns horizontal speed ($100 toward Sonic, or $100 right if player is null).
     */
    private int checkDistance(AbstractPlayableSprite player) {
        int speed = HORIZONTAL_VELOCITY;
        facingLeft = false;
        if (player != null) {
            int dx = player.getCentreX() - currentX;
            if (dx < 0) {
                speed = -HORIZONTAL_VELOCITY;
                facingLeft = true;
            }
        }
        return speed;
    }

    /**
     * SpeedToPos: Apply X and Y velocity to position with subpixel precision.
     * ROM SpeedToPos sign-extends the 16-bit velocity, shifts it by 8, and
     * adds it to the object's 16.16 position longword.
     */
    private void applyVelocity() {
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        SubpixelMotion.speedToPos(motionState);
        currentX = motionState.x;
        currentY = motionState.y;
    }

    /**
     * Check if basaran has fallen off-screen. From .chkdel:
     * <pre>
     * tst.b   obRender(a0)
     * bpl.w   DeleteObject         ; if not on screen, delete
     * </pre>
     * The FixBugs variant avoids the null-pointer issue by using bmi.s + addq.l #4,sp.
     * In our engine, we simply mark as destroyed if off-screen.
     */
    private void checkOffScreenDelete() {
        if (!isOnScreenX(160)) {
            // ROM parity: .chkdel calls DeleteObject directly (clears the SST
            // entry) WITHOUT clearing the respawn table bit. Since ObjPosLoad
            // set bit 7 = 1 ("loaded"), this bit remains set, and ObjPosLoad
            // will skip this spawn on future passes. The object permanently
            // cannot respawn. This matches markRemembered() semantics: the
            // placement system treats the spawn as destroyed and never re-
            // creates it. Contrast with RememberState (isPersistent returning
            // false) which DOES clear bit 7, allowing respawn on camera re-entry.
            setDestroyed(true);
            ObjectServices svc = tryServices();
            var objectManager = svc != null ? svc.objectManager() : null;
            ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);
        }
    }

    private void setAnimation(int newAnim) {
        if (newAnim != currentAnim) {
            currentAnim = newAnim;
            animTickCounter = 0;
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animTickCounter++;
    }

    /**
     * Returns the mapping frame index based on current animation state.
     * From Ani_Bas:
     * <pre>
     * .still: dc.b $F, 0, afEnd                    ; frame 0
     * .fall:  dc.b $F, 1, afEnd                    ; frame 1
     * .fly:   dc.b 3, 1, 2, 3, 2, afEnd           ; frames 1, 2, 3, 2
     * </pre>
     */
    private int getMappingFrame() {
        return switch (currentAnim) {
            case ANIM_STILL -> 0;
            case ANIM_FALL -> 1;
            case ANIM_FLY -> {
                // 4-frame cycle at speed 3 (4 ticks each): 1, 2, 3, 2
                int step = (animTickCounter / ANIM_SPEED_FLY) % FLY_FRAMES.length;
                yield FLY_FRAMES[step];
            }
            default -> 0;
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
        if (isDestroyed()) return false;

        // ROM parity: in dropfly state below target Y, the ROM uses the
        // .chkdel → DeleteObject path which permanently prevents respawning.
        // The engine's syncActiveSpawns runs BEFORE object updates, so it
        // would remove the Batbrain via the normal isPersistent()==false path
        // (equivalent to RememberState, which allows respawning) before the
        // Batbrain's own update can call checkOffScreenDelete → markRemembered.
        //
        // By returning true here, we keep the Batbrain alive so its next
        // update can call checkOffScreenDelete, which calls markRemembered
        // to permanently prevent respawning — matching ROM's DeleteObject.
        if (state == STATE_DROP_FLY && (targetY - currentY) < 0) {
            return true;
        }

        // All other states: RememberState semantics — persists while on screen,
        // removed when off-screen (allowing respawn on camera re-entry).
        return isOnScreenX(160);
    }

    @Override
    public int getPriorityBucket() {
        // obPriority = 2
        return RenderPriority.clamp(2);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.BATBRAIN);
        if (renderer == null) return;

        int frame = getMappingFrame();
        // obRender bit 0 = X flip with obStatus bit 0 (facing direction)
        // facingLeft = true when obStatus bit 0 is clear (bclr #0)
        // When facing right (obStatus bit 0 set), art is H-flipped
        renderer.drawFrameIndex(frame, currentX, currentY, !facingLeft, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Yellow hitbox rectangle
        ctx.drawRect(currentX, currentY, 16, 16, 1f, 1f, 0f);

        // Cyan velocity arrow if moving
        if (xVelocity != 0 || yVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            int endY = currentY + (yVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, endY, 0f, 1f, 1f);
        }

        // State label
        String stateStr = switch (state) {
            case STATE_DROP_CHECK -> "HANG";
            case STATE_DROP_FLY -> "DROP";
            case STATE_FLAP_SOUND -> "FLY";
            case STATE_FLY_UP -> "UP";
            default -> "?";
        };
        String dir = facingLeft ? "L" : "R";
        String label = name + " " + stateStr + " f" + getMappingFrame() + " " + dir;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);

        // Green cross at target Y during drop/fly states
        if (state == STATE_DROP_FLY || state == STATE_FLAP_SOUND) {
            ctx.drawCross(currentX, targetY, 4, 0f, 1f, 0f);
        }
    }
}
