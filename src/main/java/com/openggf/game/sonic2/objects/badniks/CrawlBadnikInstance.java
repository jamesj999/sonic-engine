package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.AnimationTimer;

import java.util.List;

/**
 * Crawl (0xC8) - Bouncer Badnik from Casino Night Zone.
 * <p>
 * A unique "bouncer badnik" that acts as a mobile bumper. It walks back and forth,
 * and when the player rolls into its shield (on the SAME side as facing direction),
 * it bounces them away like a bumper. When hit from behind (the opposite direction),
 * it's vulnerable and can be destroyed.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 81770-81967
 * <ul>
 *   <li>ObjC8_Init (Routine 0): line 81804 - initialization</li>
 *   <li>ObjC8_Walking (Routine 2): line 81836 - walking state</li>
 *   <li>ObjC8_Pausing (Routine 4): line 81882 - pause state</li>
 *   <li>ObjC8_Attacking (Routine 6): line 81896 - bumper collision mode</li>
 * </ul>
 *
 * <h3>ROM Constants</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Value</th><th>ROM Reference</th></tr>
 *   <tr><td>Object ID</td><td>0xC8</td><td>ObjPtr_Crawl</td></tr>
 *   <tr><td>Walk Velocity</td><td>$20 (32)</td><td>line 81826</td></tr>
 *   <tr><td>Walk Duration</td><td>$200 (512 frames)</td><td>line 81828</td></tr>
 *   <tr><td>Pause Duration</td><td>$3B (59 frames)</td><td>line 81874</td></tr>
 *   <tr><td>Bounce Velocity</td><td>$700 (1792)</td><td>line 81944</td></tr>
 *   <tr><td>Collision Flags</td><td>$D7 (attack) / $17 (vulnerable)</td><td>lines 81820, 81960</td></tr>
 *   <tr><td>y_radius</td><td>$0F (15 px)</td><td>line 81812</td></tr>
 *   <tr><td>x_radius</td><td>$10 (16 px)</td><td>line 81814</td></tr>
 *   <tr><td>Sound</td><td>SndID_Bumper (0xB4)</td><td>line 81938</td></tr>
 * </table>
 *
 * <h3>Shield/Bumper Mechanics</h3>
 * Crawl has a shield on its FRONT. Collision behavior depends on attack direction:
 * <ul>
 *   <li>Player rolling into FRONT (shield): Bumper bounce, plays 0xB4</li>
 *   <li>Player rolling into BACK: Vulnerable, destroyed like normal badnik</li>
 *   <li>Player in air (rolling jump): Always bounces (shield active all directions)</li>
 *   <li>Player not rolling: Standard enemy collision (can hurt player)</li>
 * </ul>
 *
 * <h3>Animation Frames</h3>
 * <ul>
 *   <li>Frame 0: Walking pose 1</li>
 *   <li>Frame 1: Walking pose 2</li>
 *   <li>Frame 2: Impact (player on ground)</li>
 *   <li>Frame 3: Impact (player in air)</li>
 * </ul>
 */
public class CrawlBadnikInstance extends AbstractBadnikInstance implements TouchResponseListener, RewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /** Walk velocity = $20 (32 subpixels/frame) */
    private static final int WALK_VELOCITY = 0x20;

    /** Walk duration = $200 (512 frames, ~8.5 seconds) */
    private static final int WALK_DURATION = 0x200;

    /** Pause duration = $3B (59 frames, ~1 second) */
    private static final int PAUSE_DURATION = 0x3B;

    /** Bounce velocity magnitude = $700 (1792 in 8.8 fixed point) */
    private static final int BOUNCE_VELOCITY = 0x700;

    /** Collision size index 0x17 = collision_flags($D7) & $3F — maps to Touch_Sizes[23] = (8,8) */
    private static final int COLLISION_SIZE_INDEX = 0x17;

    /** Half-height from Touch_Sizes[0x17] — used for ROM-accurate Touch_Special Y threshold. */
    private static final int TOUCH_HEIGHT_RADIUS = 8;

    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.SONIC2_SPECIAL_PROPERTY,
            false,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            0,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    /** Animation delay for walking (frames per animation tick) */
    private static final int ANIM_DELAY = 0x13; // 19 frames

    // ========================================================================
    // Animation Frames
    // ========================================================================

    private static final int FRAME_WALK_1 = 0;
    private static final int FRAME_WALK_2 = 1;
    private static final int FRAME_IMPACT_GROUND = 2;
    private static final int FRAME_IMPACT_AIR = 3;

    // ========================================================================
    // States
    // ========================================================================

    private enum State {
        WALKING,    // Routine 2 - normal walking
        PAUSING,    // Routine 4 - stopped, waiting to reverse
        ATTACKING   // Routine 6 - bumper collision mode (when player approaches)
    }

    // ========================================================================
    // Instance State
    // ========================================================================

    private State state;
    private State previousState;     // Saved state for restoration (ROM: objoff_2C)
    private int walkTimer;           // Frames until pause
    private int pauseTimer;          // Frames until resume walking
    private int impactTimer;         // Frames showing impact animation
    private int xSubpixel;           // Subpixel accumulator for smooth movement
    private boolean vulnerable;      // True when hit from back (collision_flags = $17)
    private int attackingCollisionFlags; // ROM collision_flags while in ATTACKING
    private PlayableEntity pendingTouchPlayer; // Deferred touch from Touch_Special (ROM parity)
    private boolean touchAppliedThisFrame;     // Guard: step 2 applied bounce; skip step 3 direct check
    private final AnimationTimer walkAnim = new AnimationTimer(ANIM_DELAY, 2);

    public CrawlBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Crawl", Sonic2BadnikConfig.DESTRUCTION);
        this.state = State.WALKING;
        this.previousState = State.WALKING;
        this.walkTimer = WALK_DURATION;
        this.pauseTimer = 0;
        this.impactTimer = 0;
        this.xSubpixel = 0;
        this.vulnerable = false;
        this.attackingCollisionFlags = 0xD7;

        // Initial facing based on x_flip spawn flag
        // x_flip=1 (bit 0 set) means facing RIGHT
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;

        // Set initial velocity based on facing direction
        xVelocity = facingLeft ? -WALK_VELOCITY : WALK_VELOCITY;
    }

    @Override
    public CrawlBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CrawlBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;

        // ROM: each routine handles its own state transitions; proximity check
        // (loc_3D416) is called AFTER movement in the walking routine only.
        switch (state) {
            case WALKING -> updateWalking(player);
            case PAUSING -> updatePausing();
            case ATTACKING -> updateAttacking(player);
        }

        if (impactTimer > 0) {
            impactTimer--;
        }

        // Reset after step 3 so the guard is clear for the next frame's step 2.
        touchAppliedThisFrame = false;
    }

    /**
     * Walking state (Routine 2) — mirrors loc_3D27C (s2.asm:81826).
     * ROM: decrement timer → if zero transition to pause; else move + check proximity.
     * Proximity check (loc_3D416) runs AFTER movement, not before.
     */
    private void updateWalking(AbstractPlayableSprite player) {
        walkTimer--;
        if (walkTimer <= 0) {
            state = State.PAUSING;
            pauseTimer = PAUSE_DURATION;
            xVelocity = 0;
            return;
        }

        // ObjectMove (JmpTo26_ObjectMove, s2.asm:81853 -> ObjectMove): ROM adds the
        // SIGNED x_vel to the 16.16 (x_pos:x_sub) position. For leftward motion this
        // BORROWS from x_pos on the first sub-pixel underflow rather than carrying only
        // after an abs-accumulator crosses 0x100. The previous abs accumulator decremented
        // x_pos one pixel-step LATE on every leftward run, so the Crawl's frozen x_pos at
        // an ATTACKING-entry frame that was not an exact 8-frame boundary landed 1px off
        // ROM (e.g. CNZ2 s20 froze at 0x32F instead of ROM 0x32E). Use a signed 16:8 step
        // (equivalent to ObjectMove's 16.16 for |x_vel| < 0x100) so the per-frame x_pos
        // matches ROM at every frame, not only on carry boundaries.
        int xTotal = (xSubpixel & 0xFF) + (xVelocity & 0xFF);
        currentX += (xVelocity >> 8) + (xTotal >> 8);
        xSubpixel = xTotal & 0xFF;

        // loc_3D416 (s2.asm:82411): enter attack mode if the CLOSEST character is
        // within the orientation box. ROM calls Obj_GetOrientationToPlayer
        // (s2.asm:72755), which selects whichever of Main/Sidekick has the smaller
        // absolute horizontal distance, then tests d2/d3 (Crawl_pos - char_pos).
        // ROM box: addi.w #$40,d; cmpi.w #$80,d; bhs rts  -> keep when -$40 <= d < $40.
        // ROM does NOT check rolling here; the bounce check is in loc_3D2D4.
        AbstractPlayableSprite closest = closestCharacter(player);
        if (closest != null && withinOrientationBox(closest)) {
            previousState = state;
            state = State.ATTACKING;
            attackingCollisionFlags = 0xD7;
        }
    }

    /**
     * Mirrors Obj_GetOrientationToPlayer's character selection (s2.asm:72755-72781):
     * picks whichever of MainCharacter / Sidekick has the smaller absolute horizontal
     * distance to the Crawl, with the MainCharacter winning ties (bls.s branch).
     */
    private AbstractPlayableSprite closestCharacter(AbstractPlayableSprite main) {
        AbstractPlayableSprite closest = main;
        int bestDist = main != null ? Math.abs(main.getCentreX() - currentX) : Integer.MAX_VALUE;
        for (PlayableEntity entity :
                services().playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (entity == main || !(entity instanceof AbstractPlayableSprite sidekick)) {
                continue;
            }
            int dist = Math.abs(sidekick.getCentreX() - currentX);
            // ROM cmp.w d5,d4 / bls.s + : sidekick wins only when strictly closer.
            if (dist < bestDist) {
                bestDist = dist;
                closest = sidekick;
            }
        }
        return closest;
    }

    /**
     * ROM orientation box (loc_3D416 / loc_3D2D4, s2.asm:82306-82311):
     * d2 = x_pos(Crawl) - x_pos(char); addi.w #$40,d2; cmpi.w #$80,d2; bhs -> outside.
     * The original 64x64 half-extent box; the engine keeps the long-standing
     * {@code |delta| < 64} expression, which already matched ROM at every prior
     * Crawl/Sonic contact site. The only behaviour change in this fix is WHICH
     * character supplies the delta (closest of Main/Sidekick), not the box size.
     */
    private boolean withinOrientationBox(AbstractPlayableSprite character) {
        int dx = Math.abs(character.getCentreX() - currentX);
        int dy = Math.abs(character.getCentreY() - currentY);
        return dx < 64 && dy < 64;
    }

    /**
     * Pausing state (Routine 4) — mirrors loc_3D2A6 (s2.asm:81841).
     */
    private void updatePausing() {
        pauseTimer--;
        if (pauseTimer <= 0) {
            facingLeft = !facingLeft;
            xVelocity = facingLeft ? -WALK_VELOCITY : WALK_VELOCITY;
            walkTimer = WALK_DURATION;
            state = State.WALKING;
        }
    }

    /**
     * Attacking state (Routine 6) — mirrors loc_3D2D4 (s2.asm:81856).
     * ROM: sets collision_flags=$D7, then exits to previous routine if
     * |dx| >= 64 OR |dy| >= 64 (loc_3D39A).
     * Touch_Special defers contact to the next frame via collision_property.
     */
    private void updateAttacking(AbstractPlayableSprite player) {
        attackingCollisionFlags = 0xD7;

        // ROM: ObjC8_Attacking reads+clears collision_property, which Touch_Special set in step 2.
        // pendingTouchPlayer mirrors collision_property: set in onTouchResponse, consumed here.
        if (pendingTouchPlayer instanceof AbstractPlayableSprite pending) {
            pendingTouchPlayer = null;
            applyAttackingTouch(pending);
        }

        // ROM exit check (loc_3D2D4, s2.asm:82304-82311): Obj_GetOrientationToPlayer
        // selects the closest character; if it leaves the $40/$80 box, branch to
        // loc_3D39A and revert to the prior routine. Match the same closest-character
        // selection so the Crawl stays frozen in ATTACKING while the SIDEKICK is the
        // nearby character even when the MainCharacter is already out of range.
        AbstractPlayableSprite closest = closestCharacter(player);
        if (closest == null) {
            return;
        }
        if (!withinOrientationBox(closest)) {
            state = previousState;
            return;
        }

        int dx = Math.abs(closest.getCentreX() - currentX);
        int dy = Math.abs(closest.getCentreY() - currentY);

        // ROM: Touch_Special fires every frame the character geometrically overlaps Crawl.
        // Our SPECIAL is edge-triggered, so the callback is blocked when Crawl transitions
        // WALKING→ATTACKING while the character is already in the overlapping set (from the
        // prior ENEMY interaction). Synthesise the collision_property check directly using the
        // same combined extents as isOverlapping: x ≤ touch_x_radius+8 (playerX=centreX-8,
        // playerWidth=16), y ≤ touch_y_radius+baseYRadius (baseYRadius=max(1,yr-3)).
        // touchAppliedThisFrame prevents a double-apply when onTouchResponse already fired.
        if (!touchAppliedThisFrame) {
            int baseYRadius = Math.max(1, closest.getYRadius() - 3);
            if (dx <= TOUCH_HEIGHT_RADIUS + 8 && dy <= TOUCH_HEIGHT_RADIUS + baseYRadius) {
                applyAttackingTouch(closest);
            }
        }
    }

    /**
     * Direction-aware touch resolution for ATTACKING state, deferred one frame
     * to match Touch_Special behaviour (s2.asm:81896).
     */
    private void applyAttackingTouch(AbstractPlayableSprite player) {
        if (isDestroyed() || player == null) {
            return;
        }
        touchAppliedThisFrame = true;
        // ROM: cmpi.b #AniIDSonAni_Roll,anim(a1) — checks roll/spin ANIMATION (ID=2), set for
        // ground rolling, rolling jumps, AND normal jumps (Sonic always curls during any jump).
        // getRolling() = ground roll only; getRollingJump() = roll-jump; isJumping() = normal jump.
        boolean isRolling = player.getRolling() || player.getRollingJump() || player.isJumping();
        boolean inAir = player.getAir();

        if (!isRolling) {
            // ROM loc_3D36C: non-rolling contact turns the bouncer into a HURT touch object
            // for the next touch pass; invincibility downgrades it to normal ENEMY flags.
            attackingCollisionFlags = player.getInvincibleFrames() > 0 ? 0x17 : 0x97;
            animFrame = FRAME_WALK_2;
            return;
        }

        if (inAir) {
            applyBounce(player, FRAME_IMPACT_AIR);
            return;
        }

        boolean playerToLeft = player.getCentreX() < currentX;
        boolean hittingFromFront = facingLeft ? playerToLeft : !playerToLeft;

        if (hittingFromFront) {
            applyBounce(player, FRAME_IMPACT_GROUND);
        } else {
            // ROM: ObjC8_Attacking (s2.asm:81856) sets collision_flags=$17 (vulnerable) and
            // returns to the previous routine. Touch_KillEnemy fires on the NEXT frame (step 2)
            // via the ENEMY touch category → onPlayerAttack → destroyBadnik.
            // Don't destroy immediately; revert state so ENEMY touch handles it next frame.
            vulnerable = true;
            attackingCollisionFlags = 0x17;
            state = previousState;
        }
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed() || player == null) {
            return;
        }

        // ROM Reference: lines 81847-81876
        // Player must be in Roll animation for any special interaction
        boolean isRolling = player.getRolling() || player.getRollingJump();
        boolean inAir = player.getAir();

        if (!isRolling) {
            // Not rolling - standard enemy collision ($97)
            // Touch response system handles hurting the player
            return;
        }

        // ROM lines 81849-81850: If rolling AND in air, ALWAYS bounce (no direction check)
        // This includes jumping on top, jump attacks from any direction, etc.
        if (inAir) {
            applyBounce(player, FRAME_IMPACT_AIR);
            return;
        }

        // ROM lines 81851-81857: Rolling on ground - direction check determines outcome
        // Obj_GetOrientationToPlayer returns d0=0 if player LEFT, d0=2 if RIGHT
        // If x_flip set (facing RIGHT), subtract 2 from d0
        // If d0=0 after adjustment → BOUNCE (shield side)
        // If d0≠0 → VULNERABLE (back side, collision_flags = $17)
        boolean playerToLeft = player.getCentreX() < currentX;

        // Shield is on the SAME side as the facing direction:
        // - facingLeft=true (x_flip=0): shield is on LEFT, player to left = bounce
        // - facingLeft=false (x_flip=1): shield is on RIGHT, player to right = bounce
        boolean hittingFromFront;
        if (facingLeft) {
            hittingFromFront = playerToLeft;
        } else {
            hittingFromFront = !playerToLeft;
        }

        if (hittingFromFront) {
            // Shield side - bounce player away (radial bounce)
            applyBounce(player, FRAME_IMPACT_GROUND);
        } else {
            // Back side - vulnerable, destroy like normal badnik.
            // applyEnemyBounce in handleTouchResponse applies the Touch_KillEnemy y_speed change.
            vulnerable = true;
            destroyBadnik(player);
        }
    }

    /**
     * Apply radial bounce to player (mirrors loc_3D3A4, s2.asm:81932-81958).
     * ROM Reference: lines 81932-81958
     */
    private void applyBounce(AbstractPlayableSprite player, int impactFrame) {
        // ROM: d1=x_pos(Crawl)-x_pos(Player), d2=y_pos(Crawl)-y_pos(Player)
        int dx = currentX - player.getCentreX();
        int dy = currentY - player.getCentreY();

        // ROM: jsr (CalcAngle).l — integer arctangent lookup (returns 0x40 when dx=dy=0)
        int angle = TrigLookupTable.calcAngle((short) dx, (short) dy);

        // ROM: move.b (Level_frame_counter).w,d1; andi.w #3,d1
        // Level_frame_counter is a big-endian word; move.b reads the HIGH byte.
        // Use the level frame counter (not the global object frameCounter) to match ROM behaviour.
        // BumperObjectInstance uses the same pattern (s2.asm:44675-44677).
        int levelFrameCounter = services().levelManager() != null
                ? services().levelManager().getFrameCounter()
                : 0;
        angle = (angle + ((levelFrameCounter >> 8) & 3)) & 0xFF;

        // ROM: CalcSine → d1=cos(angle), d0=sin(angle)
        // x_vel = cos * -$700 >> 8; y_vel = sin * -$700 >> 8
        int cosVal = TrigLookupTable.cosHex(angle);
        int sinVal = TrigLookupTable.sinHex(angle);
        int xVel = cosVal * -BOUNCE_VELOCITY >> 8;
        int yVel = sinVal * -BOUNCE_VELOCITY >> 8;

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // ROM: bset #in_air; bclr #rolljumping; bclr #pushing; clr.b jumping
        player.setAir(true);
        player.setRollingJump(false);
        player.setJumping(false);
        player.setPushing(false);

        // Show impact animation
        animFrame = impactFrame;
        impactTimer = 16;

        // Play bumper sound
        services().playSfx(Sonic2Sfx.BUMPER.id);
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // If showing impact animation, keep that frame
        if (impactTimer > 0) {
            return; // animFrame already set by applyBounce
        }

        // Walking animation - alternate between frames 0 and 1
        walkAnim.tick();
        animFrame = walkAnim.getFrame(); // 0 = FRAME_WALK_1, 1 = FRAME_WALK_2
    }

    /**
     * In ATTACKING state the ROM uses collision_flags=$D7 (Touch_Special/SPECIAL routing)
     * rather than $17 (ENEMY). This defers bounce/destroy by one frame, matching the ROM.
     */
    @Override
    public int getCollisionFlags() {
        if (state == State.ATTACKING) {
            return attackingCollisionFlags;
        }
        return super.getCollisionFlags();
    }

    @Override
    public boolean usesSonic2TouchSpecialPropertyResponse() {
        return true;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    /**
     * Mirrors ROM Touch_Special setting collision_property bit 0 (s2.asm:81896).
     * ObjC8_Attacking reads+clears it in the same 68k frame (step 3, updateAttacking).
     */
    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (state != State.ATTACKING || isDestroyed()) {
            return;
        }
        pendingTouchPlayer = player;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
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

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CRAWL);
        if (renderer == null) return;

        // Art faces left by default; flip when facing right
        boolean hFlip = !facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
