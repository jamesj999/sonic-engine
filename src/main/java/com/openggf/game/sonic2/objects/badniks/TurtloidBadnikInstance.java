package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AnimalObjectInstance;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.level.objects.ExplosionObjectInstance;
import com.openggf.game.sonic2.objects.PointsObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Turtloid (0x9A) - Turtle badnik from Sky Chase Zone.
 * Flies left at constant speed, detects player below, pauses to let rider
 * fire a projectile, then resumes movement. Player can stand on it (platform).
 * Spawns a rider (Obj9B) and jet exhaust (Obj9C) as children.
 *
 * Based on disassembly Obj9A (s2.asm:74332-74418).
 */
public class TurtloidBadnikInstance extends AbstractBadnikInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Platform-only object - no special solid contact behavior needed
    }

    // Movement: move.w #-$80,x_vel(a0)
    private static final int X_VELOCITY = -0x80;

    // Platform collision: d1=$18, d2=$8, d3=$E (PlatformObject)
    private static final int PLATFORM_HALF_WIDTH = 0x18;   // 24 pixels
    private static final int PLATFORM_Y_RADIUS = 0x08;     // 8 pixels
    private static final int PLATFORM_Y_OFFSET = 0x0E;     // 14 pixels

    // Timers from disassembly
    private static final int PAUSE_TIMER = 4;   // objoff_2A = 4 (state 2 wait)
    private static final int SHOOT_TIMER = 8;   // objoff_2A = 8 (state 4 wait)

    // Horizontal detection threshold: cmpi.w #$80,d2
    private static final int DETECT_DISTANCE_X = 0x80;

    // Projectile spawn offsets from parent body
    // subi.w #$14,x_pos(a1) and addi.w #$A,y_pos(a1)
    private static final int SHOT_X_OFFSET = -0x14;
    private static final int SHOT_Y_OFFSET = 0x0A;
    // Projectile velocity: move.w #-$100,x_vel(a1)
    private static final int SHOT_X_VEL = -0x100;

    // Rider offset from parent: dc.w 4, dc.w -$18
    private static final int RIDER_X_OFFSET = 4;
    private static final int RIDER_Y_OFFSET = -0x18;

    private enum State {
        MOVING,         // routine_secondary 0: flying left, checking player
        PAUSE_BEFORE,   // routine_secondary 2: paused, timer counting down
        SHOOTING,       // routine_secondary 4: rider shooting, timer counting down
        DONE            // routine_secondary 6: do nothing (just platform/movement)
    }

    private State state;
    private int timer;
    private final SubpixelMotion.State motionState;

    // Child references
    private TurtloidRiderInstance rider;
    private TurtloidJetInstance jet;
    private boolean childrenSpawned;

    private final SolidObjectParams platformParams;

    public TurtloidBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Turtloid", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xVelocity = X_VELOCITY;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, X_VELOCITY, 0);
        this.state = State.MOVING;
        this.timer = 0;
        this.animFrame = 0;

        // PlatformObject: d1=$18 (half-width), d2=$8 (air half-height), d3=$E (ground half-height)
        this.platformParams = SolidObjectParams.of(
                PLATFORM_HALF_WIDTH, PLATFORM_Y_RADIUS, PLATFORM_Y_OFFSET);
    }

    @Override
    public TurtloidBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new TurtloidBadnikInstance(ctx.spawn());
    }

    private void ensureChildrenSpawned() {
        if (childrenSpawned || services().objectManager() == null) {
            return;
        }
        childrenSpawned = true;

        // Spawn rider (Obj9B) at offset (+4, -$18)
        int riderX = currentX + RIDER_X_OFFSET;
        int riderY = currentY + RIDER_Y_OFFSET;
        rider = spawnChild(() -> new TurtloidRiderInstance(
                new ObjectSpawn(riderX, riderY, 0x9B, 0x18, 0, false, 0),
                this));

        // Spawn jet exhaust (Obj9C) - follows parent and animates
        jet = spawnChild(() -> new TurtloidJetInstance(
                new ObjectSpawn(currentX, currentY, 0x9C, 0x1A, 0, false, 0),
                this));
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        ensureChildrenSpawned();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case MOVING -> updateMoving(player);
            case PAUSE_BEFORE -> updatePauseBefore();
            case SHOOTING -> updateShooting();
            case DONE -> {} // Do nothing, just drift
        }

        // ROM ObjectMove (s2.asm:29993-30008): 16.16 position += sign-extended x_vel << 8.
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = 0;
        SubpixelMotion.speedToPos(motionState);
        currentX = motionState.x;
        currentY = motionState.y;

        // ROM: loc_36776 - add Tornado_Velocity_X/Y to position each frame
        var parallax = services().parallaxManager();
        currentX += parallax.getTornadoVelocityX();
        currentY += parallax.getTornadoVelocityY();
    }

    /**
     * State 0: Moving left, checking for player ahead.
     * ROM: loc_379A0 - Obj_GetOrientationToPlayer, then:
     *   tst.w d0 / bmi.w return (d0 is 0 or 2, never negative - always falls through)
     *   cmpi.w #$80,d2 / bhs.w return (d2 = signed horizontal distance, unsigned compare)
     * Triggers when player is 0-127 pixels to the left (Turtloid is slightly ahead/right).
     */
    private void updateMoving(AbstractPlayableSprite player) {
        // Rider is the attack/shooting behavior. Once destroyed, keep base as
        // a moving platform and skip the attack state machine.
        if (rider == null) {
            return;
        }

        if (player == null) {
            return;
        }

        // d2 = object_x - player_x (signed horizontal distance)
        int dx = currentX - player.getCentreX();

        // cmpi.w #$80,d2 / bhs.w return (unsigned compare)
        // If dx < 0 (player to the right), the unsigned interpretation is large -> skip
        // If dx >= 0x80 (player too far left), skip
        // Trigger only when dx is 0..0x7F (player 0-127 pixels to the left)
        if (dx < 0 || dx >= DETECT_DISTANCE_X) {
            return;
        }

        state = State.PAUSE_BEFORE;
        xVelocity = 0; // Stop moving
        timer = PAUSE_TIMER;
        animFrame = 1; // Neck raised frame
    }

    /**
     * State 2: Waiting before shooting.
     * ROM: loc_379CA - countdown timer, then transition to shooting.
     */
    private void updatePauseBefore() {
        timer--;
        if (timer < 0) {
            state = State.SHOOTING;
            timer = SHOOT_TIMER;
            // Set rider to shooting frame
            if (rider != null) {
                rider.setMappingFrame(3);
            }
            // Fire projectile
            fireProjectile();
        }
    }

    /**
     * State 4: Shooting state, waiting for timer.
     * ROM: loc_379EA - countdown timer, then resume movement.
     */
    private void updateShooting() {
        timer--;
        if (timer < 0) {
            state = State.DONE;
            xVelocity = X_VELOCITY; // Resume moving left
            motionState.xSub = motionState.xSub == 0 ? 0x8000 : 0;
            animFrame = 0; // Back to normal frame
            // Rider frame restored by rider's own update (follows parent frame)
        }
    }

    /**
     * Fire a projectile from the parent body's position.
     * ROM: loc_37AF2 - allocates Obj98 projectile.
     */
    private void fireProjectile() {
        if (rider == null) {
            return;
        }

        int shotX = currentX + SHOT_X_OFFSET;
        // SCZ top-lane Turtloids live in the screen-top band where the level-select
        // trace shows the shot clearing Sonic on the Tornado; normal lanes use the
        // direct Obj9A parent y_pos + $A spawn from loc_37AF2.
        int shotY = currentY < 0x100
                ? rider.getY() + SHOT_Y_OFFSET
                : currentY + SHOT_Y_OFFSET;

        spawnFreeChild(() -> new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.TURTLOID_SHOT,
                shotX, shotY,
                SHOT_X_VEL, 0,
                false,  // No gravity
                false)); // No H-flip
    }

    void onRiderDestroyed(int riderX, int riderY, AbstractPlayableSprite player) {
        rider = null;

        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        spawnFreeChild(() -> new ExplosionObjectInstance(
                0x27, riderX, riderY, services().renderManager()));

        spawnFreeChild(() -> AnimalObjectInstance.deferredArtVariant(
                new ObjectSpawn(riderX, riderY, 0x28, 0, 0, false, 0), services(), null));

        int pointsValue = 100;
        if (player != null) {
            pointsValue = player.incrementBadnikChain();
            services().gameState().addScore(pointsValue);
        }

        int finalPointsValue = pointsValue;
        spawnFreeChild(() -> new PointsObjectInstance(
                new ObjectSpawn(riderX, riderY, 0x29, 0, 0, false, 0), services(), finalPointsValue));

        services().playSfx(Sonic2Sfx.EXPLOSION.id);
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Animation: Ani_obj9A = dc.b 1, 6, 7, $FF (jet exhaust frames)
        // The Turtloid body itself only uses frames 0 (normal) and 1 (neck raised),
        // which are set directly by the state machine above.
    }

    @Override
    protected int getCollisionSizeIndex() {
        // Obj9A_SubObjData collision = 0 (no touch response - it's a platform, not a hurtable badnik)
        // The rider (Obj9B) has the actual touch response ($1A)
        return 0;
    }

    @Override
    public int getCollisionFlags() {
        // No touch response for the Turtloid body itself (collision = 0 in subObjData)
        return 0;
    }

    // SolidObjectProvider - platform behavior
    @Override
    public SolidObjectParams getSolidParams() {
        return platformParams;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        return true;
    }

    @Override
    public boolean usesGroundHalfHeightForTopSolidContact() {
        return true;
    }

    @Override
    public boolean allowsZeroDistanceTopSolidLanding(PlayableEntity player) {
        return true;
    }

    @Override
    public boolean seedsNewRideCarryFromPreUpdateX() {
        // Obj9A passes pre-move x_pos in d4, but S2 PlatformObject consumes d4
        // only for already-standing players. New landings only set the ride bit,
        // so the next-frame engine baseline must start from current X.
        return false;
    }

    @Override
    public int getPriorityBucket() {
        // ROM: priority = 5 (Obj9A_SubObjData)
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.TURTLOID);
        if (renderer == null) return;

        // Turtloid body: frame 0 = flying, frame 1 = neck raised
        renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Platform bounds in green
        ctx.drawRect(currentX, currentY, PLATFORM_HALF_WIDTH, PLATFORM_Y_RADIUS, 0f, 1f, 0f);

        // State label
        String label = "Turtloid [" + state + "] t=" + timer;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.YELLOW);

        // Velocity arrow
        if (xVelocity != 0) {
            int endX = currentX + (xVelocity >> 5);
            ctx.drawArrow(currentX, currentY, endX, currentY, 0f, 1f, 1f);
        }
    }

    /** Used by rider and jet to track parent position. */
    public int getParentX() { return currentX; }
    public int getParentY() { return currentY; }
    public boolean isParentDestroyed() { return isDestroyed(); }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s t=%d xv=%04X xsub=%04X rider=%s",
                state,
                timer,
                xVelocity & 0xFFFF,
                motionState.xSub & 0xFFFF,
                rider == null ? "none" : "live");
    }
}
