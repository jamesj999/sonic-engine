package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * HTZ Fire Shooter (Obj20, routines 0-6).
 * ROM Reference: s2.asm lines 48436-48535
 *
 * A stationary fire source that periodically shoots paired fireballs.
 * Subtype encoding:
 * - High nibble (bits 7-4): velocity = -((subtype << 3) & 0x780)
 * - Low nibble (bits 3-0): fire interval = (subtype & 0x0F) << 4 frames
 *
 * State machine:
 * ANIMATING (routine 2) -> FIRING (routine 4) -> COOLDOWN (routine 6) -> ANIMATING
 *
 * Animation script (Ani_obj20 anim 0): delay $B, frames 2, 3, then $FC (advance routine).
 * After firing, anim continues with frame 4, then changes to anim 1 (frame 5 looping).
 * On cooldown expiry, resets to anim 0.
 */
public class HtzFireShooterObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(HtzFireShooterObjectInstance.class.getName());

    private static final int PRIORITY = 3;

    // Animation constants from Ani_obj20
    private static final int ANIM_DELAY = 0x0B; // 11 ticks per frame in anim 0
    private static final int FIRE_TRIGGER_DURATION = 5; // ROM: cmpi.b #5,anim_frame_duration

    private enum State {
        ANIMATING,  // Routine 2: looping fire animation
        FIRING,     // Routine 4: spawn projectiles on trigger frame
        COOLDOWN    // Routine 6: timer countdown before restart
    }

    private int currentX;
    private int currentY;
    private int projectileVel;  // Velocity for spawned projectiles (8.8 format)
    private int reloadTime;     // Frames between fire cycles

    private State state;
    private int animFrame;
    private int animTimer;
    private int cooldownTimer;
    private boolean hasFired;

    // Animation sequence tracking
    // Anim 0: frames 2, 3, then advance state
    // Anim 0 continued in FIRING: frame 4, then anim 1 (frame 5 looping)
    private int animSequenceIndex;

    public HtzFireShooterObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Fire Shooter");
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // Decode subtype: ROM s2.asm:48464-48474
        int subtype = spawn.subtype() & 0xFF;
        int vel = (subtype << 3) & 0x780;
        this.projectileVel = -vel;
        this.reloadTime = (subtype & 0x0F) << 4;

        if (projectileVel == 0) {
            LOG.warning("HtzFireShooter at (" + currentX + "," + currentY + ") has zero projectile velocity (subtype=0x" + Integer.toHexString(subtype) + ")");
        }

        // Start in ANIMATING state (routine 2)
        this.state = State.ANIMATING;
        this.animFrame = 2;
        this.animTimer = ANIM_DELAY;
        this.cooldownTimer = reloadTime;
        this.hasFired = false;
        this.animSequenceIndex = 0;
    }

    @Override
    public HtzFireShooterObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HtzFireShooterObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case ANIMATING -> updateAnimating();
            case FIRING -> updateFiring();
            case COOLDOWN -> updateCooldown();
        }
    }

    /**
     * Routine 2: Play anim 0 (frames 2, 3) then advance to FIRING.
     */
    private void updateAnimating() {
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_DELAY;
            animSequenceIndex++;
            if (animSequenceIndex == 1) {
                animFrame = 3;
            } else {
                // $FC in anim script: advance routine to FIRING
                state = State.FIRING;
                animFrame = 4;
                animTimer = ANIM_DELAY;
                hasFired = false;
            }
        }
    }

    /**
     * Routine 4: Continue animation, fire projectiles when anim_frame_duration == 5.
     * ROM: s2.asm:48483-48503
     */
    private void updateFiring() {
        // Check trigger condition: ROM checks anim_frame_duration == 5
        if (!hasFired && animTimer == FIRE_TRIGGER_DURATION) {
            fireProjectiles();
            hasFired = true;
            // Advance to COOLDOWN (routine += 2)
            state = State.COOLDOWN;
            cooldownTimer = reloadTime;
            // Switch to anim 1: frame 5 looping with delay $7F
            animFrame = 5;
            animTimer = 0x7F;
            return;
        }

        // Continue animation (frame 4 display)
        animTimer--;
        if (animTimer < 0) {
            // Frame 4 done, switch to anim 1 (frame 5 looping)
            animFrame = 5;
            animTimer = 0x7F;
        }
    }

    /**
     * Routine 6: Countdown timer, then return to ANIMATING.
     * ROM: s2.asm:48525-48535
     */
    private void updateCooldown() {
        // Continue anim 1 (frame 5 looping)
        animTimer--;
        if (animTimer < 0) {
            animTimer = 0x7F;
            // Frame stays at 5 (anim 1 loops)
        }

        cooldownTimer--;
        if (cooldownTimer < 0) {
            // Return to routine 2 with anim 0
            // ROM: move.b #2,routine / move.w #(0<<8)|(1<<0),anim
            state = State.ANIMATING;
            animFrame = 2;
            animTimer = ANIM_DELAY;
            animSequenceIndex = 0;
        }
    }

    /**
     * Spawn two mirrored fire projectiles.
     * ROM: s2.asm:48483-48522
     */
    private void fireProjectiles() {
        // First projectile: inherits parent velocity and orientation
        HtzFireProjectileObjectInstance proj1 = new HtzFireProjectileObjectInstance(
                currentX, currentY, projectileVel, projectileVel, false);
        spawnDynamicObject(proj1);

        // Second projectile: negated X velocity, H-flipped
        // ROM: neg.w x_vel(a1) / bset #render_flags.x_flip,render_flags(a1)
        HtzFireProjectileObjectInstance proj2 = new HtzFireProjectileObjectInstance(
                currentX, currentY, -projectileVel, projectileVel, true);
        spawnDynamicObject(proj2);

        // ROM: move.w #SndID_ArrowFiring,d0 / jsr (PlaySound).l
        services().playSfx(Sonic2Sfx.ARROW_FIRING.id);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        // Fire source uses LAVA_BUBBLE sheet (ArtNem_HtzFireball2 + Obj20_MapUnc_23254)
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.LAVA_BUBBLE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
    }
}
