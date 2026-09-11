package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Spiny (0xA5) - Crawling caterpillar Badnik from CPZ.
 * Patrols back and forth and fires spike projectiles at the player.
 *
 * Behavior from s2.asm disassembly (ObjA5, s2.asm:75909-75976):
 * - Patrol: Moves at x_vel = -0x40, reverses every 0x80 frames (128)
 * - Detection: ROM Obj_GetOrientationToPlayer (s2.asm:72320-72346) picks the
 *   closest player by |x_pos delta| (MainCharacter vs Sidekick), then computes
 *   d2 = obj.x - player.x. The Spiny test (s2.asm:75939-75941) is:
 *       addi.w #$60, d2
 *       cmpi.w #$C0, d2
 *       blo.s loc_38B4E
 *   i.e. attack iff (d2 + 0x60) unsigned less than 0xC0, which is the range
 *   d2 in [-0x60, 0x5F] (signed). That is roughly +/-96px on the X axis only.
 *   There is NO y-axis check and NO facing-direction guard in the ROM.
 * - ROM execution order in loc_38B10 path:
 *     1. test/decrement lockout (objoff_2B), bra to movement if non-zero
 *     2. detection (loc_38B1E) -- if blo, jump to attack (skip movement)
 *     3. decrement direction timer (objoff_2A), possibly reverse x_vel
 *     4. ObjectMove (x_vel into x_pos:x_sub as 32-bit add)
 *   The detection in step 2 reads the PRE-movement obj.x. Movement is only
 *   applied if detection does not trigger an attack. We replicate that order
 *   exactly in updatePatrolling below.
 * - Attack: timer 0x28 (40 frames), fires at $14 remaining (20-frame mark);
 *   on exit (timer less than 0) routine returns to patrol with objoff_2B = $40
 *   (64-frame post-attack lockout). During the 64-frame lockout patrol moves
 *   but detection is skipped (loc_38B10 bra.w loc_38B2C). During the 40-frame
 *   attack window itself, no movement, no direction timer decrement.
 */
public class SpinyBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Movement constants
    private static final int X_VEL = 0x40;        // Movement speed (subpixels)
    // ROM reversal-timer quirk (s2.asm:76362, 76380, 76382):
    //   ObjA5_Init / reversal: `move.w #$80,objoff_2A(a0)` (a WORD write)
    //   loc_38B2C:             `subq.b #1,objoff_2A(a0)`   (a BYTE decrement)
    // objoff_2A is a single byte at $2A (s2.constants.asm:133). The word write
    // $0080 is big-endian, so byte[$2A] (the high byte, which the BYTE subq
    // decrements) is set to $00, NOT $80. Decrementing the high byte from $00
    // therefore wraps $00->$FF->...->$01->$00, taking 256 (0x100) decrements to
    // reach zero and reverse — NOT 128. The low byte $80 lands in objoff_2B
    // (the adjacent detect-lockout byte at $2B); see INITIAL_LOCKOUT below.
    private static final int MOVE_TIMER = 0x100;  // Move-frames before reversing (256, see above)

    // Attack constants
    private static final int ATTACK_TIMER = 0x28;   // Attack duration (40 frames)
    private static final int FIRE_FRAME = 0x14;     // Fire at this remaining (20 frames)
    private static final int ATTACK_LOCKOUT = 0x40; // Cooldown after attack (64 frames)
    // Side effect of the `move.w #$80,objoff_2A` quirk above: the WORD write's
    // low byte ($80) lands in objoff_2B (the detect-lockout byte at $2B). So at
    // spawn AND on every direction reversal, the spiny gets a 128-frame window
    // where loc_38B10 skips detection (objoff_2B != 0 -> bra loc_38B2C).
    private static final int INITIAL_LOCKOUT = 0x80; // Detect lockout from word write (128)

    // Detection range (ROM Obj_GetOrientationToPlayer + Spiny addi/cmpi/blo gate)
    // s2.asm:75939-75941: addi.w #$60, d2 ; cmpi.w #$C0, d2 ; blo
    private static final int DETECT_OFFSET = 0x60;
    private static final int DETECT_WIDTH = 0xC0;

    // Projectile constants
    private static final int SPIKE_X_VEL = 0x100;   // +/-0x100 toward MainCharacter
    private static final int SPIKE_Y_VEL = -0x300;  // Upward initial velocity

    // Animation constants
    private static final int CRAWL_ANIM_DELAY = 9;  // 9-frame delay between crawl frames

    private enum State {
        PATROLLING,
        ATTACKING
    }

    private State state;
    private int moveCounter;      // Frames until direction reversal
    private int attackTimer;      // Attack state timer
    private int attackLockout;    // Frames until can attack again
    private boolean movingLeft;   // Current movement direction
    private boolean hasFired;     // Whether spike has been fired this attack
    private int subPixelX;        // Subpixel accumulator for smooth movement

    public SpinyBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Spiny", Sonic2BadnikConfig.DESTRUCTION);
        this.state = State.PATROLLING;
        this.moveCounter = MOVE_TIMER;
        this.attackTimer = 0;
        // ROM ObjA5_Init `move.w #$80,objoff_2A` also seeds objoff_2B=$80, giving
        // a 128-frame initial detect lockout (s2.asm:76362; see INITIAL_LOCKOUT).
        this.attackLockout = INITIAL_LOCKOUT;
        this.movingLeft = true;      // Start moving left (like disassembly)
        this.hasFired = false;
        this.facingLeft = true;
        this.subPixelX = 0;          // Start with no subpixel offset
    }

    @Override
    public SpinyBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpinyBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case PATROLLING -> updatePatrolling(player);
            case ATTACKING -> updateAttacking(player);
        }
    }

    /**
     * Mirrors ROM loc_38B10 (s2.asm:75930-75953). The order is critical for
     * matching ROM timing of attack entry:
     *   1. If lockout non-zero, decrement and skip to step 4 (movement).
     *   2. Otherwise run detection against the closest player. If in range,
     *      transition to attack state and return WITHOUT moving this frame.
     *   3. Decrement direction timer; on hitting zero, reverse direction.
     *   4. Apply movement (add x_vel to x_pos:x_sub as 32-bit subpixel add).
     */
    private void updatePatrolling(AbstractPlayableSprite player) {
        // Step 1: post-attack lockout decrement, bypass detection while > 0.
        boolean skipDetection;
        if (attackLockout > 0) {
            attackLockout--;
            skipDetection = true;
        } else {
            skipDetection = false;
        }

        // Step 2: detection against closest player (Main vs Sidekick).
        if (!skipDetection) {
            AbstractPlayableSprite target = pickClosestPlayer(player);
            if (target != null && isPlayerInRange(target)) {
                // ROM jumps to loc_38B4E: addq.b #2, routine; objoff_2B = $28;
                // mapping_frame = 2. Subsequent ObjectMove is SKIPPED this frame.
                startAttack();
                return;
            }
        }

        // Step 3: decrement direction timer (objoff_2A), possibly reverse x_vel.
        // ROM loc_38B2C (s2.asm:76379-76383): subq.b #1,objoff_2A ; bne + ;
        // move.w #$80,objoff_2A ; neg.w x_vel. The reversal's `move.w #$80` is
        // the same word-write quirk as Init: it reseeds objoff_2A's reversal
        // period to 256 AND reseeds objoff_2B (attackLockout) to $80 (128),
        // re-imposing the detect lockout for 128 frames after every reversal.
        moveCounter--;
        if (moveCounter <= 0) {
            movingLeft = !movingLeft;
            moveCounter = MOVE_TIMER;
            attackLockout = INITIAL_LOCKOUT;
        }

        // Step 4: ObjectMove (x_vel into x_pos:x_sub).
        if (movingLeft) {
            subPixelX -= X_VEL;
        } else {
            subPixelX += X_VEL;
        }
        while (subPixelX >= 0x100) {
            currentX++;
            subPixelX -= 0x100;
        }
        while (subPixelX <= -0x100) {
            currentX--;
            subPixelX += 0x100;
        }
        facingLeft = movingLeft;
    }

    private void updateAttacking(AbstractPlayableSprite player) {
        attackTimer--;

        // Fire spike at the right moment (objoff_2B == 0x14 in ROM)
        if (!hasFired && attackTimer <= FIRE_FRAME) {
            fireSpike(player);
            hasFired = true;
        }

        // End attack when timer expires (bmi.s on subq result)
        if (attackTimer < 0) {
            state = State.PATROLLING;
            attackLockout = ATTACK_LOCKOUT;
            hasFired = false;
        }
    }

    /**
     * Picks the closest player (MainCharacter vs Sidekick) by absolute
     * horizontal distance, mirroring ROM Obj_GetOrientationToPlayer
     * (s2.asm:72320-72346). The ROM compares unsigned horizontal magnitudes
     * and prefers MainCharacter on ties (bls.s).
     */
    private AbstractPlayableSprite pickClosestPlayer(AbstractPlayableSprite mainPlayer) {
        if (mainPlayer == null) {
            return null;
        }
        ObjectPlayerQuery.NearestPlayerX nearest = playerQuery(mainPlayer)
                .nearestByRomX(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS,
                        currentX,
                        candidate -> candidate instanceof AbstractPlayableSprite);
        return nearest.player() instanceof AbstractPlayableSprite sprite ? sprite : mainPlayer;
    }

    private ObjectPlayerQuery playerQuery(AbstractPlayableSprite updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(
                () -> {
                    PlayableEntity main = query.mainPlayerOrNull();
                    return main instanceof AbstractPlayableSprite ? main : updatePlayer;
                },
                query::sidekicks);
    }

    /**
     * ROM-accurate horizontal detection gate (s2.asm:75939-75941):
     *   addi.w #$60, d2       (where d2 = obj.x - player.x, signed word)
     *   cmpi.w #$C0, d2
     *   blo.s  attack
     * Reproduces the unsigned 16-bit window without any Y or facing check.
     */
    private boolean isPlayerInRange(AbstractPlayableSprite player) {
        int d2 = (currentX - player.getCentreX() + DETECT_OFFSET) & 0xFFFF;
        return d2 < DETECT_WIDTH;
    }

    private void startAttack() {
        state = State.ATTACKING;
        attackTimer = ATTACK_TIMER;
        hasFired = false;
        // ROM does not update render_flags or facing on attack entry; the Spiny
        // continues to face whichever direction patrol was going.
    }

    private void fireSpike(AbstractPlayableSprite player) {
        // ROM loc_38C22 (s2.asm:76050-76070): spike spawned at the Spiny's
        // x_pos/y_pos, y_vel = -0x300, x_vel = +/-0x100 toward MainCharacter
        // (NOT the closest player). ROM cmp.w x_pos(a2),d0 ; blo.s + ; neg.w d1
        // where d0 = obj.x, a2 = MainCharacter. The engine passes MainCharacter
        // as the player parameter at the call site.
        AbstractPlayableSprite mainPlayer = player;
        int xVel;
        if (mainPlayer != null) {
            // If obj.x < player.x, do NOT neg (d1 stays +0x100, fires right).
            // If obj.x >= player.x, neg (d1 = -0x100, fires left).
            xVel = (currentX < mainPlayer.getCentreX()) ? SPIKE_X_VEL : -SPIKE_X_VEL;
        } else {
            xVel = facingLeft ? -SPIKE_X_VEL : SPIKE_X_VEL;
        }

        final int spawnX = currentX;
        final int spawnY = currentY; // ROM uses y_pos(a0) directly, no -8 offset
        final int fireXVel = xVel;
        // ROM loc_38C22 uses AllocateObjectAfterCurrent (s2.asm:76487), so the
        // Obj98 spike takes the slot right after the spiny and runs THIS frame's
        // exec pass — but its first pass is routine 0 (Obj98_Init -> LoadSubObject,
        // no movement, s2.asm:74583-74584); it only starts Obj98_SpinyShotFall
        // movement on the NEXT frame. Without deferring that init frame the engine
        // spike moves one frame early, arriving in the player/sidekick touchbox a
        // frame ahead of the ROM. Mirror CluckerBadnikInstance/NebulaBadnikInstance:
        // build with construction context, defer the init frame, add-after-current.
        spawnChild(() -> {
            // ROM Obj98_SpinyShotFall runs one routine-0 init frame (no move) then
            // moves from routine 2. The engine models that with
            // deferFirstMovementForLoadSubObjectInit(). However, the engine's
            // object-execution phase (objects move at end of frame; the CPU sidekick's
            // slot-1 TouchResponse reads them at frame start) places a freshly spawned
            // projectile one frame ahead of ROM's Obj98 at sidekick-touch time, hurting
            // the co-located CPU sidekick one frame early (CPZ1 f1157; ROM hurts f1158).
            // One extra stationary (initialDelay) frame realigns the spike's trajectory
            // with ROM frame-for-frame. See docs/status/trace-frontier-log.md (CPZ1 f1157).
            BadnikProjectileInstance spike = new BadnikProjectileInstance(
                    spawn,
                    BadnikProjectileInstance.ProjectileType.SPINY_SPIKE,
                    spawnX,
                    spawnY,
                    fireXVel,
                    SPIKE_Y_VEL,
                    true,  // Apply gravity (Obj98_SpinyShotFall)
                    false, // No initial flip
                    1      // Extra init-phase stationary frame (engine object-phase realignment)
            );
            spike.deferFirstMovementForLoadSubObjectInit();
            return spike;
        });
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (state == State.ATTACKING) {
            // Attack pose (frame 2)
            animFrame = 2;
        } else {
            // Crawling animation (frames 0-1, 9-frame delay)
            animFrame = ((vIntRunCount / CRAWL_ANIM_DELAY) & 1);
        }
    }

    @Override
    public String traceDebugDetails() {
        return String.format("st=%s mc=%02X at=%02X lk=%02X sub=%04X dir=%s",
                state,
                moveCounter & 0xFFFF,
                attackTimer & 0xFFFF,
                attackLockout & 0xFFFF,
                subPixelX & 0xFFFF,
                movingLeft ? "L" : "R");
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.SPINY);
        if (renderer == null) return;

        // Spiny is symmetrical, but we flip based on facing direction
        boolean hFlip = !facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
