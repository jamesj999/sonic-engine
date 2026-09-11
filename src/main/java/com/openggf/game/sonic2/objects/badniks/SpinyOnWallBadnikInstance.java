package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * SpinyOnWall (0xA6) - Wall-climbing variant of Spiny Badnik from CPZ.
 * Patrols vertically on walls and fires spike projectiles horizontally.
 *
 * Behavior from s2.asm disassembly (ObjA6_WallType):
 * - Patrol: Moves at y_vel = -0x40, reverses every 0x80 frames (128)
 * - Detection: Checks angle to player, attacks if within 0x60-0xC0 range
 * - Attack: Timer 0x28 (40 frames), fires at 0x14 (20 remaining), lockout 0x40 (64)
 * - Spike fire: Horizontal (x_vel = 0x300 or -0x300) based on facing direction
 *
 * Uses the same art as Spiny (ArtNem_Spiny) but different animation frames:
 * - Frames 3-4: Wall climbing (patrol)
 * - Frame 5: Attack pose
 * - Frames 6-7: Spike projectile
 */
public class SpinyOnWallBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Movement constants
    private static final int Y_VEL = 0x40;        // Movement speed (subpixels)
    // ROM reversal-timer quirk (s2.asm:76434, 76452, 76454), identical to the
    // floor Spiny (ObjA5) modelled in SpinyBadnikInstance:
    //   ObjA6_Init:  `move.w #$80,objoff_2A(a0)` (a WORD write)
    //   loc_38BC8:   `subq.b #1,objoff_2A(a0)`   (a BYTE decrement)
    // objoff_2A is a single byte at $2A. The big-endian word write $0080 sets
    // byte[$2A] (the high byte the BYTE subq decrements) to $00 and byte[$2B]
    // (the adjacent detect-lockout byte) to $80. Decrementing the high byte from
    // $00 wraps $00->$FF->...->$01->$00, taking 256 (0x100) decrements to reach
    // zero and reverse y_vel — NOT 128. The low byte $80 lands in objoff_2B,
    // imposing a 128-frame detect lockout at spawn and after every reversal.
    private static final int MOVE_TIMER = 0x100;  // Move-frames before reversing (256, see above)

    // Attack constants
    private static final int ATTACK_TIMER = 0x28;   // Attack duration (40 frames)
    private static final int FIRE_FRAME = 0x14;     // Fire at this remaining (20 frames)
    private static final int ATTACK_LOCKOUT = 0x40; // Cooldown after attack (64 frames)
    // Side effect of the `move.w #$80,objoff_2A` quirk above: the word write's
    // low byte ($80) lands in objoff_2B (the detect-lockout byte at $2B). So at
    // spawn AND on every direction reversal, the spiny gets a 128-frame window
    // where loc_38BAC skips detection (objoff_2B != 0 -> decrement, bra loc_38BC8).
    private static final int INITIAL_LOCKOUT = 0x80; // Detect lockout from word write (128)

    // Detection range. ROM loc_38BBA (s2.asm:76445-76449) calls
    // Obj_GetOrientationToPlayer, then: addi.w #$60,d2 / cmpi.w #$C0,d2 / blo.
    // d2 is the *signed* horizontal distance (spiny.x - closestPlayer.x) to the
    // CLOSER of MainCharacter/Sidekick. The unsigned (d2 + $60) < $C0 test
    // therefore attacks whenever the player is within [-$60, $60) horizontally.
    // There is NO vertical gate and NO facing gate in ObjA6's detection — the
    // earlier 0x80 box + dy + facing check fired the spike at the wrong frames,
    // and in CPZ1 the resulting falling spike hit CPU Tails (a hurt ROM never
    // produces). Firing direction is the spiny's fixed x_flip (loc_38C6E), not
    // the player's side.
    private static final int DETECT_X_OFFSET = 0x60; // addi.w #$60,d2
    private static final int DETECT_X_RANGE = 0xC0;  // cmpi.w #$C0,d2 / blo

    // Projectile constants - horizontal only for wall variant
    private static final int SPIKE_X_VEL = 0x300;   // Horizontal spike velocity
    private static final int SPIKE_Y_VEL = 0;       // No vertical component

    // Animation constants
    private static final int CLIMB_ANIM_DELAY = 9;  // 9-frame delay between climbing frames

    private enum State {
        PATROLLING,
        ATTACKING
    }

    private State state;
    private int moveCounter;      // Frames until direction reversal
    private int attackTimer;      // Attack state timer
    private int attackLockout;    // Frames until can attack again
    private boolean movingUp;     // Current movement direction (vertical)
    private boolean hasFired;     // Whether spike has been fired this attack
    private int ySubpixel;        // Subpixel accumulator for smooth movement

    public SpinyOnWallBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "SpinyOnWall", Sonic2BadnikConfig.DESTRUCTION);
        this.state = State.PATROLLING;
        this.moveCounter = MOVE_TIMER;
        this.attackTimer = 0;
        // ROM ObjA6_Init `move.w #$80,objoff_2A` also seeds objoff_2B=$80, giving
        // a 128-frame initial detect lockout (s2.asm:76434; see INITIAL_LOCKOUT).
        this.attackLockout = INITIAL_LOCKOUT;
        this.movingUp = true;      // Start moving up (negative Y, y_vel = -$40)
        this.hasFired = false;
        this.ySubpixel = 0;
        // Preserve spawn's render_flags for initial facing direction (x_flip bit)
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public SpinyOnWallBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SpinyOnWallBadnikInstance(ctx.spawn());
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
     * Mirrors ROM loc_38BAC -> loc_38BC8 (s2.asm:76438-76460). The order is
     * critical for matching ROM badnik Y position over the patrol window:
     *   1. loc_38BAC: if objoff_2B != 0, decrement it and skip detection
     *      (bra loc_38BC8). Otherwise run detection; if in range, jump to
     *      attack (loc_38BEA) WITHOUT running loc_38BC8 this frame.
     *   2. loc_38BC8: subq.b #1,objoff_2A; on hitting zero reseed objoff_2A to
     *      $80 (word write -> 256-frame reversal period + 128-frame lockout in
     *      objoff_2B) and neg.w y_vel; then ObjectMove applies y_vel.
     * Unlike the floor Spiny, movement (loc_38BC8) runs every patrol frame,
     * including during the detect lockout.
     */
    private void updatePatrolling(AbstractPlayableSprite player) {
        // Step 1: detect lockout (objoff_2B). While non-zero, decrement and skip
        // detection (ROM bra loc_38BC8). loc_38BC8 still runs below.
        boolean skipDetection;
        if (attackLockout > 0) {
            attackLockout--;
            skipDetection = true;
        } else {
            skipDetection = false;
        }

        // Step 2: detection against closest player. If in range, ROM jumps to
        // loc_38BEA (attack) and does NOT run loc_38BC8 (no move) this frame.
        if (!skipDetection && player != null && isPlayerInRange(player)) {
            startAttack();
            return;
        }

        // Step 3: loc_38BC8 reversal timer (objoff_2A). subq.b #1; on zero reseed
        // to $80 (256-frame period via word-write quirk, + 128-frame detect
        // lockout in objoff_2B) and neg.w y_vel.
        moveCounter--;
        if (moveCounter <= 0) {
            movingUp = !movingUp;
            moveCounter = MOVE_TIMER;
            attackLockout = INITIAL_LOCKOUT;
        }

        // Step 4: ObjectMove. Y_VEL = 0x40 = 64 subpixels/frame = 0.25 px/frame.
        ySubpixel += Y_VEL;
        while (ySubpixel >= 256) {
            ySubpixel -= 256;
            if (movingUp) {
                currentY--;
            } else {
                currentY++;
            }
        }
    }

    private void updateAttacking(AbstractPlayableSprite player) {
        attackTimer--;

        // Fire spike at the right moment
        if (!hasFired && attackTimer <= FIRE_FRAME) {
            fireSpike();
            hasFired = true;
        }

        // End attack when timer expires
        if (attackTimer <= 0) {
            state = State.PATROLLING;
            attackLockout = ATTACK_LOCKOUT;
            hasFired = false;
        }
    }

    /**
     * Checks if a player is within attack range, matching ROM ObjA6 loc_38BBA
     * (s2.asm:76445-76449):
     * <pre>
     *   bsr.w  Obj_GetOrientationToPlayer  ; d2 = spiny.x - closestPlayer.x (signed)
     *   addi.w #$60,d2
     *   cmpi.w #$C0,d2
     *   blo.s  loc_38BEA                   ; attack
     * </pre>
     * The signed horizontal distance is taken to the CLOSER of MainCharacter and
     * Sidekick (Obj_GetOrientationToPlayer, s2.asm:72755-72781). The detection
     * uses ONLY this horizontal band; there is no vertical gate and no facing
     * gate in the ROM.
     */
    private boolean isPlayerInRange(AbstractPlayableSprite player) {
        AbstractPlayableSprite target = closestPlayer(player);
        if (target == null) {
            return false;
        }
        // d2 = spiny.x - player.x (signed), then unsigned (d2 + $60) < $C0.
        int adjustedDx = (currentX - target.getCentreX()) + DETECT_X_OFFSET;
        return adjustedDx >= 0 && adjustedDx < DETECT_X_RANGE;
    }

    /**
     * Mirrors Obj_GetOrientationToPlayer's character selection (s2.asm:72755-72781):
     * picks the closer of MainCharacter / Sidekick by absolute horizontal distance.
     */
    private AbstractPlayableSprite closestPlayer(PlayableEntity mainPlayer) {
        AbstractPlayableSprite best = null;
        int bestDist = Integer.MAX_VALUE;
        if (mainPlayer instanceof AbstractPlayableSprite mainSprite) {
            best = mainSprite;
            bestDist = Math.abs(mainSprite.getCentreX() - currentX);
        }
        ObjectServices svc = tryServices();
        if (svc != null) {
            for (PlayableEntity sk : svc.playerQuery()
                    .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (sk == mainPlayer) {
                    continue;
                }
                if (sk instanceof AbstractPlayableSprite skSprite) {
                    int dist = Math.abs(skSprite.getCentreX() - currentX);
                    if (dist < bestDist) {
                        best = skSprite;
                        bestDist = dist;
                    }
                }
            }
        }
        return best;
    }

    private void startAttack() {
        state = State.ATTACKING;
        attackTimer = ATTACK_TIMER;
        hasFired = false;
        // Unlike regular Spiny, SpinyOnWall does NOT turn to face player
        // It fires in its current facing direction
    }

    private void fireSpike() {
        // ROM loc_38C6E (s2.asm:76509-76526): the spike spawns at the spiny's
        // exact x_pos/y_pos and moves at x_vel = $300, negated when the spiny's
        // render_flags.x_flip bit is set (facingLeft). y_vel starts at 0 and
        // Obj98_SpinyShotFall applies +$20 gravity (s2.asm:74628-74632). The
        // previous +/-8 muzzle offset is not in the ROM and shifted the spike's
        // landing point, contributing to phantom CPU-Tails hits in CPZ1.
        int xVel = facingLeft ? -SPIKE_X_VEL : SPIKE_X_VEL;

        services().objectManager().createDynamicObject(() -> new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.SPINY_SPIKE,
                currentX,
                currentY,
                xVel,
                SPIKE_Y_VEL,
                true,   // Apply gravity after firing
                false   // No initial flip
        ));
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (state == State.ATTACKING) {
            // Attack pose (frame 5) for wall variant
            animFrame = 5;
        } else {
            // Wall-climbing animation (frames 3-4, 9-frame delay)
            animFrame = 3 + ((vIntRunCount / CLIMB_ANIM_DELAY) & 1);
        }
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

        // Flip sprite based on facing direction
        // Default sprite has body on left, legs on right (for LEFT wall, facing RIGHT)
        // When facingLeft=true (on RIGHT wall, facing LEFT), we need to H-flip
        boolean hFlip = facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
