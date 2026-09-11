package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Aquis (0x50) - seahorse badnik from Oil Ocean Zone.
 * Waits until on-screen, chases the player with acceleration, fires downward
 * projectiles, and escapes after 3 shooting cycles. Has a wing child object
 * that follows the body.
 * Based on disassembly Obj50 (lines 60044-60310).
 */
public class AquisBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    private enum State {
        WAIT_FOR_SCREEN,  // routine_secondary 0: idle until on-screen
        CHASE,            // routine_secondary 2: follow player with accel
        SHOOTING,         // routine_secondary 4: wait then fire projectile
        ESCAPE            // routine_secondary 6: fly away
    }

    private static final int COLLISION_SIZE_INDEX = 0x0A; // collision_flags $0A
    private static final int WIDTH_PIXELS = 0x10;
    private static final int CHASE_ACCEL = 0x10;           // +-0x10 per frame
    private static final int MAX_CHASE_SPEED = 0x100;      // cap speed 0x100
    private static final int CHASE_TIMER = 0x80;           // 128 frames
    private static final int SHOOT_DELAY = 0x20;           // 32 frames
    private static final int INITIAL_SHOTS = 3;
    private static final int BULLET_X_VEL = 0x300;
    private static final int BULLET_Y_VEL = 0x200;
    private static final int BULLET_X_OFFSET = 0x10;
    private static final int BULLET_Y_OFFSET = 0x0A;
    private static final int ESCAPE_X_VEL = -0x200;
    private static final int POST_SHOT_Y_VEL = -0x100;
    private static final int WING_X_OFFSET = 0x0A;
    private static final int WING_Y_OFFSET = -6;

    private static final SpriteAnimationSet ANIMATIONS = createAnimations();
    private static final SpriteAnimationSet WING_ANIMATIONS = createWingAnimations();

    private State state;
    private int timer;
    private int shotsRemaining;
    private boolean shootingFlag; // prevents double-fire per shooting phase
    private final SubpixelMotion.State motionState;
    private final ObjectAnimationState animationState;

    @RewindTransient(reason = "ROM parent/wing pointer linkage is recreated through live object allocation")
    private AquisWingChild wingChild;
    private boolean wingSpawned;

    public AquisBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Aquis", Sonic2BadnikConfig.DESTRUCTION);
        this.state = State.WAIT_FOR_SCREEN;
        this.timer = 0;
        this.shotsRemaining = INITIAL_SHOTS;
        this.shootingFlag = false;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        // S2 NPC status/render x_flip means the Aquis faces right; clear means
        // left (docs/s2disasm/s2.constants.asm:224, s2.asm:60708-60718).
        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;
        this.animationState = new ObjectAnimationState(ANIMATIONS, 0, 0);
        this.wingChild = null;
        this.wingSpawned = false;
        // Bug 1 fix: ROM Obj50_Init writes move.w #-$100, x_vel(a0) immediately
        // after the standard init block (s2.asm:60100).
        this.xVelocity = -0x100;
    }

    @Override
    public AquisBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AquisBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        spawnWingChildOnce();
        AbstractPlayableSprite player = closestPlayer(playerEntity);
        switch (state) {
            case WAIT_FOR_SCREEN -> updateWaitForScreen();
            case CHASE -> updateChase(player);
            case SHOOTING -> updateShooting(player);
            case ESCAPE -> updateEscape();
        }

        syncWingChild();
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        animationState.update();
        animFrame = animationState.getMappingFrame();
    }

    private void updateWaitForScreen() {
        // ROM Obj50_CheckIfOnScreen (docs/s2disasm/s2.asm:60662-60671)
        // observes render_flags.on_screen from BuildSprites. Obj50_Init sets
        // width_pixels=$10 and does not set explicit_height (s2.asm:60567-60574),
        // so S2 BuildSprites takes its approximate-Y path: X uses width_pixels
        // and Y uses the assumed 32px band (s2.asm:30566-30611). The solid
        // contact gate uses the object's 16px half-height and keeps this Aquis
        // waiting 37 frames too long in the OOZ2 route.
        if (isWithinRenderSpriteBounds(WIDTH_PIXELS, 32)) {
            // ROM Obj50_CheckIfOnScreen (s2.asm:60607-60614) only advances
            // routine_secondary to Obj50_Chase; it does NOT initialise
            // Obj50_timer. The SST timer byte is therefore still 0 from the
            // cleared object slot, so the very first Obj50_FollowPlayer frame
            // does subq.b #1 -> 0xFF -> bmi -> Obj50_DoneFollowing immediately
            // (s2.asm:60670-60671). i.e. the Aquis fires one shot from its
            // spawn position before it ever moves. Leaving timer at 0 here (NOT
            // CHASE_TIMER) reproduces that initial stationary shooting phase;
            // setting it to 0x80 made the engine chase ~58 frames too early and
            // drift the Aquis ~41px left of the ROM by the kill frame.
            state = State.CHASE;
            timer = 0;
            animationState.setAnimId(1); // Flapping body animation
        }
    }

    private void updateChase(AbstractPlayableSprite player) {
        // ROM Obj50_FollowPlayer (s2.asm:60669-60697) decrements the timer and
        // bails to Obj50_DoneFollowing (-> Obj_MoveStop) BEFORE running
        // GetOrientationToPlayer / accel / CapSpeed / ObjectMove. Mirror that
        // ordering: on the expiry frame the object must NOT accelerate or move.
        // subq.b #1 / bmi fires when the byte underflows (0x00 -> 0xFF).
        timer = (byte) (timer - 1);
        if (timer < 0) {
            // Obj50_DoneFollowing (s2.asm:60693-60697): Obj_MoveStop clears
            // x_vel/y_vel, then routine -> Obj50_Shooting with timer = $20.
            xVelocity = 0;
            yVelocity = 0;
            state = State.SHOOTING;
            timer = SHOOT_DELAY;
            animationState.setAnimId(0); // Static body
            return;
        }

        if (player != null && !player.isDebugMode()) {
            // Obj_GetOrientationToPlayer (s2.asm:72755-72781) indexes
            // Obj50_Speeds {-$10, +$10} (s2.asm:60689-60691): the object
            // accelerates TOWARD the closest player on both axes, and faces
            // toward the player on X. d2 = obj.x - player.x; player to the left
            // (d2 >= 0) -> index 0 = -$10 (accel left); player to the right
            // (d2 < 0) -> index 2 = +$10 (accel right). Y is symmetric.
            if (player.getCentreX() < currentX) {
                xVelocity -= CHASE_ACCEL;
                facingLeft = true;
            } else {
                xVelocity += CHASE_ACCEL;
                facingLeft = false;
            }

            if (player.getCentreY() <= currentY) {
                yVelocity -= CHASE_ACCEL;
            } else {
                yVelocity += CHASE_ACCEL;
            }
        }

        // Obj_CapSpeed (s2.asm) clamps |x_vel|,|y_vel| to $100.
        xVelocity = clampSpeed(xVelocity, MAX_CHASE_SPEED);
        yVelocity = clampSpeed(yVelocity, MAX_CHASE_SPEED);

        applyMovement();
    }

    private void updateShooting(AbstractPlayableSprite player) {
        // ROM Obj50_Shooting calls Obj50_WaitForNextShot before Obj50_ChkIfShoot
        // (docs/s2disasm/s2.asm:60679-60681). On the expiry frame,
        // Obj50_WaitForNextShot clears Obj50_shooting_flag and returns to the
        // caller, which still falls through to Obj50_ChkIfShoot in that same
        // ExecuteObjects pass (s2.asm:60757-60769, 60685-60721).
        timer = (byte) (timer - 1);
        if (timer < 0) {
            shotsRemaining--;
            if (shotsRemaining >= 0) {
                // Return to chase
                state = State.CHASE;
                timer = CHASE_TIMER;
                yVelocity = POST_SHOT_Y_VEL; // Thrust upward after shot
                shootingFlag = false;
                animationState.setAnimId(1); // Flapping body
            } else {
                // All shots used, escape
                state = State.ESCAPE;
                xVelocity = ESCAPE_X_VEL;
                yVelocity = 0;
            }
        }

        // ROM Obj50_ChkIfShoot sets the one-shot flag before the vertical
        // eligibility check, so a too-high player still consumes this window.
        if (!shootingFlag && player != null && !player.isDebugMode()) {
            shootingFlag = true;
            if (player.getCentreY() > currentY) {
                fireProjectile();
            }
        }
    }

    private void updateEscape() {
        applyMovement();

        if (!isOnScreen(64)) {
            ObjectLifetimeOps.expireDynamic(this);
            expireWingChild();
        }
    }

    private void spawnWingChildOnce() {
        if (wingSpawned) {
            return;
        }
        wingSpawned = true;
        if (tryServices() == null || services().objectManager() == null) {
            return;
        }
        int wingX = currentX + WING_X_OFFSET;
        int wingY = currentY + WING_Y_OFFSET;
        // ROM Obj50 creates the wing with AllocateObject -- the LOWEST free SST slot
        // from Dynamic_Object_RAM upward, which may be below the parent's own slot
        // (docs/s2disasm/s2.asm:60606-60607 `jsrto JmpTo12_AllocateObject`; the
        // allocator itself scans upward from Dynamic_Object_RAM, s2.asm:33681-33694).
        // Obj50's bullet (s2.asm:60700) uses the same call and already uses
        // spawnFreeChild, so both children take FindFreeObj semantics.
        wingChild = spawnFreeChild(() -> new AquisWingChild(
                new ObjectSpawn(wingX, wingY, spawn.objectId(), 0, spawn.renderFlags(), false, spawn.rawYWord()),
                this));
        syncWingChild();
    }

    private void syncWingChild() {
        if (wingChild != null && !wingChild.isDestroyed()) {
            wingChild.syncToParent();
        }
    }

    private void expireWingChild() {
        if (wingChild != null && !wingChild.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(wingChild);
        }
    }

    private void attachWingForRewind(AquisWingChild wing) {
        wingChild = wing;
        wingSpawned = true;
    }

    private void fireProjectile() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }

        // Obj50_ChkIfShoot starts with d1=$10/d2=-$300, negates both only when
        // status.npc.x_flip is set, then subtracts d1 from x_pos and stores d2
        // to x_vel (docs/s2disasm/s2.asm:60708-60719). Engine facingLeft mirrors
        // Obj50's clear x-flip state, so the unflipped ROM shot travels left.
        final int bulletX = facingLeft ? currentX - BULLET_X_OFFSET : currentX + BULLET_X_OFFSET;
        // Obj50_ChkIfShoot subtracts the $0A Y offset before setting velocity.
        final int bulletY = currentY - BULLET_Y_OFFSET;
        final int bulletXVel = facingLeft ? -BULLET_X_VEL : BULLET_X_VEL;
        final boolean bulletHFlip = !facingLeft;

        spawnFreeChild(() -> new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.AQUIS_BULLET,
                bulletX,
                bulletY,
                bulletXVel,
                BULLET_Y_VEL,
                false,      // No gravity
                bulletHFlip));
    }

    /**
     * Bug 3 fix: ROM Obj_GetOrientationToPlayer (s2.asm:72320-72346) picks the
     * closer of MainCharacter and Sidekick by |x_pos - obX|. Engine previously
     * only used the main player. Walk the sidekick list and return the player
     * with minimum absolute X distance.
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
            for (PlayableEntity sk : svc.playerQuery().playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
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

    private void applyMovement() {
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        // ROM Obj50 uses ObjectMove/SpeedToPos (s2.asm:60736-60743),
        // which preserves the full 16-bit x_sub/y_sub words.
        SubpixelMotion.speedToPos(motionState);
        currentX = motionState.x;
        currentY = motionState.y;
    }

    private static int clampSpeed(int velocity, int max) {
        if (velocity > max) return max;
        if (velocity < -max) return -max;
        return velocity;
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

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.AQUIS);
        if (renderer == null) return;

        // Draw main body (priority 4)
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }

    @Override
    protected void destroyBadnik(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        expireWingChild();
        super.destroyBadnik(player);
    }

    @Override
    public void onUnload() {
        expireWingChild();
    }

    private static final class AquisWingChild extends AbstractObjectInstance implements RewindRecreatable {

        // The wing's own captured spawn is frozen where it was first spawned (Aquis's
        // WAIT_FOR_SCREEN position) and is never refreshed afterward (syncToParent()
        // updates the render-only wingX/wingY fields, not the captured spawn/
        // dynamicSpawn). The true parent Aquis then legitimately chases the player for
        // up to CHASE_TIMER (0x80 = 128) frames per cycle, across up to
        // INITIAL_SHOTS (3) chase/shoot cycles, at up to MAX_CHASE_SPEED (0x100 = 1px/
        // frame) on each axis independently -- worst case (naive, ignoring the gradual
        // CHASE_ACCEL ramp-up) 3 * 128 = 384px per axis, sqrt(384^2+384^2) =~ 543px
        // diagonal -- before ESCAPE (another ~a screen's width off-screen at
        // ESCAPE_X_VEL). Unlike a boss, multiple Aquis badniks CAN legitimately coexist
        // in the same act, so an unbounded search really can mis-relink to a different,
        // wrong Aquis; bound generously (0x400 = 1024px) to still reject anything
        // clearly unrelated while never rejecting the true, still-chasing parent.
        private static final int MAX_PARENT_RELINK_DISTANCE = 0x400;

        @RewindTransient(reason = "ROM Obj50 wing keeps a parent SST pointer; object graph recreates it live")
        private final AquisBadnikInstance parent;
        private final ObjectAnimationState animationState;
        private int wingX;
        private int wingY;
        private boolean wingFacingLeft;

        private AquisWingChild(ObjectSpawn spawn, AquisBadnikInstance parent) {
            super(spawn, "AquisWing");
            this.parent = parent;
            this.animationState = new ObjectAnimationState(WING_ANIMATIONS, 0, 1);
            this.wingX = spawn.x();
            this.wingY = spawn.y();
            this.wingFacingLeft = parent != null && parent.facingLeft;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            // Wing child of an Aquis badnik. If the Aquis was destroyed/swept before
            // capture there is no body to bind the wing to, so drop the wing (its live
            // update expires with a dead parent) rather than throw. acceptDestroyed
            // relinks to a restored-but-destroyed Aquis when that is the captured parent.
            // Bounded (see MAX_PARENT_RELINK_DISTANCE) since multiple Aquis badniks can
            // coexist in the same act -- an unbounded search could mis-relink to a
            // different, wrong Aquis.
            return RewindRecreateObjectLinks.nearestObject(
                            ctx, AquisBadnikInstance.class, true, MAX_PARENT_RELINK_DISTANCE)
                    .<AbstractObjectInstance>map(parent -> {
                        AquisWingChild wing = new AquisWingChild(ctx.spawn(), parent);
                        parent.attachWingForRewind(wing);
                        return wing;
                    })
                    .orElse(null);
        }

        /**
         * ROM {@code Obj50_Wing} (docs/s2disasm/s2.asm:60642-60653) ends with
         * {@code jmpto JmpTo32_DisplaySprite} -- it never calls
         * {@code MarkObjGone}. Unlike {@code Obj50_Main} (s2.asm:60632) and
         * {@code Obj50_Bullet} (s2.asm:60665), which both tail into
         * {@code JmpTo33_MarkObjGone}, the wing has no off-screen delete of its
         * own: its only deletion paths are the three parent checks at the top of
         * {@code Obj50_Wing} (empty parent slot, parent id no longer
         * {@code ObjID_Aquis}, or parent marked destroyed). The wing therefore
         * holds its SST slot for exactly as long as its parent does.
         *
         * <p>Without this, the engine's generic {@code MarkObjGone} window
         * deletes the wing on the very frame it is created whenever the parent
         * sits near the right edge of the load window: the wing spawns at
         * {@code parent.x + $A}, which can round into the next $80 chunk and so
         * exceed the $280 delete distance while the parent itself is still in
         * range. The freed slot is then handed to the next
         * {@code AllocateObject} caller, shifting every subsequent SST
         * allocation in the region down one slot.
         */
        @Override
        public boolean usesCustomOutOfRangeCheck() {
            return true;
        }

        @Override
        public boolean isCustomOutOfRange(int cameraX) {
            return false;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (parent == null || parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            animationState.update();
            syncToParent();
        }

        private void syncToParent() {
            if (parent == null) {
                return;
            }
            wingFacingLeft = parent.facingLeft;
            int offsetX = wingFacingLeft ? -WING_X_OFFSET : WING_X_OFFSET;
            wingX = parent.currentX + offsetX;
            wingY = parent.currentY + WING_Y_OFFSET;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(3);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.AQUIS);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(animationState.getMappingFrame(), wingX, wingY, !wingFacingLeft, false);
        }
    }

    /**
     * Animation scripts from Ani_obj50 (disassembly).
     */
    private static SpriteAnimationSet createAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Normal body (static) - dc.b $E, 0, $FF
        set.addScript(0, new SpriteAnimationScript(
                0x0E,
                List.of(0),
                SpriteAnimationEndAction.LOOP,
                0));

        // Anim 1: Body with flapping - dc.b 5, 3, 4, 3, 4, 3, 4, $FF
        set.addScript(1, new SpriteAnimationScript(
                5,
                List.of(3, 4, 3, 4, 3, 4),
                SpriteAnimationEndAction.LOOP,
                0));

        // Anim 2: Bullet spinning - dc.b 3, 5, 6, 7, 6, $FF
        set.addScript(2, new SpriteAnimationScript(
                3,
                List.of(5, 6, 7, 6),
                SpriteAnimationEndAction.LOOP,
                0));

        return set;
    }

    /**
     * Wing animation scripts.
     */
    private static SpriteAnimationSet createWingAnimations() {
        SpriteAnimationSet set = new SpriteAnimationSet();

        // Anim 0: Wing flapping - dc.b 3, 1, 2, $FF
        set.addScript(0, new SpriteAnimationScript(
                3,
                List.of(1, 2),
                SpriteAnimationEndAction.LOOP,
                0));

        return set;
    }
}
