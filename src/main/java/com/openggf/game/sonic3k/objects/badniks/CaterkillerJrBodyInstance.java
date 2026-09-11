package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CaterKiller Jr body segment (child of {@link CaterkillerJrHeadInstance}).
 * Body segments always hurt the player (collision type 0x97/0x98) and cannot
 * be destroyed by player attacks.
 * <p>
 * Based on loc_8778C (sonic3k.asm lines 183389-183515).
 *
 * <h3>Segment types by index:</h3>
 * <ul>
 *   <li>0-2: CaterKillerJr art, frame 1 (tall body), collision 0x97, can fire projectiles</li>
 *   <li>3: CaterKillerJr art, frame 2 (thin body), collision 0x97, no projectiles</li>
 *   <li>4-5: MonkeyDude art, frame 3 (coconut), collision 0x98, no projectiles</li>
 * </ul>
 */
public final class CaterkillerJrBodyInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {

    private static final int COLLISION_BODY = 0x97;
    private static final int COLLISION_TAIL = 0x98;
    private static final int PRIORITY_BUCKET = 5;
    private static final int INITIAL_X_VEL = -0x100;
    private static final int SLOW_MAX_VEL = 0x80;
    private static final int FAST_MAX_VEL = 0x100;
    private static final int SWING_ACCEL = 8;
    private static final int SLOW_PEAK_COUNT = 3;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            new com.openggf.game.profiles.touchresponse.TouchResponseProfile(
                    com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode.NORMAL,
                    false,
                    false,
                    false,
                    com.openggf.game.profiles.touchresponse.TouchShieldDeflectCapability.NONE,
                    0,
                    com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                    com.openggf.game.profiles.touchresponse.TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                    com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy
                            .STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS));

    // Projectile animation from byte_878A8: (delay, frame) pairs, $F4 loop
    private static final int[] PROJ_ANIM_DELAYS = {3, 3, 4, 5};
    private static final int[] PROJ_ANIM_FRAMES = {2, 2, 3, 4};
    private static final int PROJ_FIRE_COOLDOWN = 0x1A;

    // Non-final so the generic field capturer reapplies them after a rewind
    // recreate. segmentIndex (and the art/collision/fire flags it derives) is not
    // recoverable from the body's own spawn, which carries only position.
    private int segmentIndex;
    private String rendererKey;
    private int mappingFrame;
    private int collisionFlags;
    private boolean canFire;

    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private int xSubpixel;
    private int ySubpixel;
    private boolean facingLeft;
    private boolean destroyed;

    private enum State { WAITING, ACTIVE }
    private State state = State.WAITING;
    private int waitTimer;

    private enum Phase { SWING_COUNTED, SWING_FAST, SWING_FINISH }
    private Phase phase;
    private int peakCounter;
    private int swingMaxVel;
    private boolean swingDown;

    private int fireTimer;
    private int projAnimStep = -1;
    private int projAnimTimer;

    CaterkillerJrBodyInstance(ObjectSpawn headSpawn,
                              int segmentIndex, int waitDelay) {
        super(headSpawn, "CaterKillerJrBody");
        this.segmentIndex = segmentIndex;
        this.currentX = headSpawn.x();
        this.currentY = headSpawn.y();
        this.facingLeft = (headSpawn.renderFlags() & 0x01) == 0;

        // Select art and collision based on segment type (off_877C6 table)
        if (segmentIndex <= 2) {
            this.rendererKey = Sonic3kObjectArtKeys.CATERKILLER_JR;
            this.mappingFrame = 1;
            this.collisionFlags = COLLISION_BODY;
            this.canFire = true;
        } else if (segmentIndex == 3) {
            this.rendererKey = Sonic3kObjectArtKeys.CATERKILLER_JR;
            this.mappingFrame = 2;
            this.collisionFlags = COLLISION_BODY;
            this.canFire = false;
        } else {
            this.rendererKey = Sonic3kObjectArtKeys.MONKEY_DUDE;
            this.mappingFrame = 3;
            this.collisionFlags = COLLISION_TAIL;
            this.canFire = false;
        }

        // The constructor folds loc_877AC (routine 0) into object creation. A
        // child allocated after the head executes that setup later in the same
        // object pass, but does not enter loc_877D8's routine-2 decrement until
        // the next pass. Preserve that init-only dispatch with one extra tick.
        this.waitTimer = waitDelay + 1;
    }

    public CaterkillerJrBodyInstance(ObjectSpawn spawn) {
        this(spawn, 0, 0);
    }

    /**
     * Rewind recreate factory. The body holds no live parent reference, so a
     * structurally-valid instance positioned at the captured spawn is enough; the
     * non-final differentiator fields (segmentIndex, rendererKey, mappingFrame,
     * collisionFlags, canFire) and the in-flight motion/animation scalars are
     * reapplied by the generic field capturer immediately after recreate, so
     * placeholders are passed here. Exposed publicly so {@code Sonic3kObjectRegistry}
     * (in the parent package) can reach the package-private constructor.
     */
    public static CaterkillerJrBodyInstance forRewindRecreate(ObjectSpawn spawn) {
        return new CaterkillerJrBodyInstance(spawn, 0, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn capturedSpawn = ctx.spawn();
        if (capturedSpawn == null) {
            capturedSpawn = new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        }
        CaterkillerJrBodyInstance restored = forRewindRecreate(capturedSpawn);
        CaterkillerJrHeadInstance liveHead = CaterkillerJrHeadInstance.findLiveHeadForRewind(ctx);
        if (liveHead != null) {
            liveHead.attachBodySegmentForRewind(restored);
        }
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (destroyed) return;

        switch (state) {
            case WAITING -> updateWaiting();
            case ACTIVE -> updateActive();
        }

        if (projAnimStep >= 0) {
            updateProjectileAnimation();
        }
    }

    private void updateWaiting() {
        waitTimer--;
        if (waitTimer >= 0) return;

        state = State.ACTIVE;
        xVelocity = INITIAL_X_VEL;

        // Fire timer: $2E = $40 - (subtype+2)*4, where subtype = segmentIndex * 2
        if (canFire) {
            fireTimer = 0x40 - (segmentIndex + 1) * 8;
        }

        initSwingPhase1();
    }

    private void updateActive() {
        boolean shouldMove = switch (phase) {
            case SWING_COUNTED -> {
                if (applySwing()) {
                    peakCounter--;
                    if (peakCounter < 0) {
                        initSwingPhase2();
                        yield false;
                    }
                }
                yield true;
            }
            case SWING_FAST -> {
                if (applySwing()) {
                    phase = Phase.SWING_FINISH;
                    xVelocity = -xVelocity;
                    facingLeft = !facingLeft;
                }
                yield true;
            }
            case SWING_FINISH -> {
                if (applySwing()) {
                    initSwingPhase1();
                    yield false;
                }
                yield true;
            }
        };

        if (shouldMove) {
            moveWithVelocity();
        }
        checkFireProjectile();
    }

    private boolean applySwing() {
        SwingMotion.Result r = SwingMotion.update(SWING_ACCEL, yVelocity, swingMaxVel, swingDown);
        yVelocity = r.velocity();
        swingDown = r.directionDown();
        return r.directionChanged();
    }

    private void initSwingPhase1() {
        phase = Phase.SWING_COUNTED;
        peakCounter = SLOW_PEAK_COUNT;
        swingMaxVel = SLOW_MAX_VEL;
        yVelocity = SLOW_MAX_VEL;
        swingDown = false;
    }

    private void initSwingPhase2() {
        phase = Phase.SWING_FAST;
        swingMaxVel = FAST_MAX_VEL;
        yVelocity = FAST_MAX_VEL;
        swingDown = false;
    }

    private void moveWithVelocity() {
        int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
        int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
        xPos24 += xVelocity;
        yPos24 += yVelocity;
        currentX = xPos24 >> 8;
        currentY = yPos24 >> 8;
        xSubpixel = xPos24 & 0xFF;
        ySubpixel = yPos24 & 0xFF;
    }

    private void checkFireProjectile() {
        if (!canFire || state != State.ACTIVE) return;

        fireTimer--;
        if (fireTimer < 0) {
            fireTimer = PROJ_FIRE_COOLDOWN;
            projAnimStep = 0;
            projAnimTimer = PROJ_ANIM_DELAYS[0];
        }
    }

    private void updateProjectileAnimation() {
        projAnimTimer--;
        if (projAnimTimer > 0) return;

        projAnimStep++;
        if (projAnimStep >= PROJ_ANIM_FRAMES.length) {
            projAnimStep = 0;
        }
        projAnimTimer = PROJ_ANIM_DELAYS[projAnimStep];
    }

    void onHeadDestroyed() {
        destroyed = true;
        setDestroyed(true);
    }

    @Override
    public int getCollisionFlags() {
        if (destroyed) return 0;
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() { return currentX; }

    @Override
    public int getY() { return currentY; }

    @Override
    public int getPriorityBucket() { return PRIORITY_BUCKET; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(rendererKey);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, !facingLeft, false);
        }

        if (projAnimStep >= 0 && canFire) {
            PatternSpriteRenderer projRenderer = renderManager.getRenderer(
                    Sonic3kObjectArtKeys.CATERKILLER_JR);
            if (projRenderer != null && projRenderer.isReady()) {
                projRenderer.drawFrameIndex(PROJ_ANIM_FRAMES[projAnimStep],
                        currentX, currentY, !facingLeft, false);
            }
        }
    }
}
