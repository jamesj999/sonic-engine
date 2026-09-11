package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * LBZ1 Robotnik approach / miniboss handoff entity.
 *
 * <p>ROM: {@code Obj_LBZ1Robotnik} at {@code sonic3k.asm:192147}. This covers
 * the visible Robotnik ship/head, the pre-collapse hover/rise sequence, the
 * {@code LBZ1_EventVScroll} ownership, and the handoff into {@code Obj_LBZMiniboss}.
 */
public final class Lbz1RobotnikEventController extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_APPROACH_HOVER = 0x02;
    private static final int ROUTINE_FIRST_RISE = 0x04;
    private static final int ROUTINE_WAIT_FOR_COLLAPSE_TRIGGER = 0x06;
    private static final int ROUTINE_WAIT_FOR_COLLAPSE_CLEAR = 0x08;
    private static final int ROUTINE_AFTER_COLLAPSE = 0x0A;
    private static final int ROUTINE_SECOND_RISE = 0x0C;
    private static final int ROUTINE_DIAGONAL_ESCAPE = 0x0E;
    private static final int ROUTINE_POST_HANDOFF = 0x10;
    private static final int APPROACH_CAMERA_MIN_X = 0x3820;
    private static final int APPROACH_CAMERA_MAX_X = 0x3AE8;
    private static final int TRIGGER_CAMERA_X = 0x3B40;
    private static final int TRIGGER_PLAYER_Y = 0x01C0;
    private static final int POST_COLLAPSE_CAMERA_MAX_X = 0x3EA0;
    private static final int POST_COLLAPSE_MIN_FOLLOW_LIMIT_X = 0x3E50;
    private static final int INC_LEVEL_END_X_STEP = 0x4000;
    private static final int APPROACH_NEAR_X = 0x70;
    private static final int APPROACH_NEAR_Y = 0x60;
    private static final int POST_COLLAPSE_NEAR_X = 0x60;
    private static final int FIRST_RISE_TELEPORT_Y = 0x0300;
    private static final int SECOND_RISE_STOP_Y = 0x012C;
    private static final int DIAGONAL_ESCAPE_HANDOFF_Y = 0x01B8;
    private static final int TELEPORT_X = 0x3EC0;
    private static final int TELEPORT_Y = 0x01A0;
    private static final int FIRST_RISE_Y_VEL = -0x0400;
    private static final int DIAGONAL_ESCAPE_X_VEL = 0x0200;
    private static final int DIAGONAL_ESCAPE_Y_VEL = 0x0200;
    private static final int SWING_INITIAL_VELOCITY = 0x00C0;
    private static final int SWING_ACCELERATION = 0x0010;
    private static final int COLLISION_FLAGS = 0x0F;
    private static final int COLLISION_PROPERTY_ALWAYS_BOUNCE = 0xFF;
    private static final int HIT_REACTION_FRAMES = 0x20;
    private static final int SHIP_FRAME = 0x0A;
    private static final int HEAD_FRAME_NORMAL_A = 0;
    private static final int HEAD_FRAME_NORMAL_B = 1;
    private static final int HEAD_FRAME_HURT = 2;
    private static final int FLAME_FRAME = 6;
    private static final int HEAD_Y_OFFSET = -0x1C;
    private static final int FLAME_X_OFFSET = 0x1E;
    private static final int FLAME_Y_OFFSET = 0;
    private static final int PALETTE_LINE = 0;
    private static final int BOX_PALETTE_LINE = 2;
    private static final int BOX_ANCHOR_Y_OFFSET = 0x34;
    private static final int DROPPED_BOX_WAIT = 0x1F;
    private static final int MINIBOSS_MUSIC_FADE_FRAMES = 90;
    /** ROM Sprite_CheckDeleteTouch2: coarse off-screen removal range. */
    private static final int OFFSCREEN_COARSE_RANGE = 0x280;

    private final SubpixelMotion.State motion;
    private int routine = ROUTINE_INIT;
    private int swingMaxVelocity;
    private int swingAcceleration;
    private boolean swingDirectionDown;
    private boolean facingLeft;
    private boolean flameVisible;
    private int headAnimationTimer;
    private int headFrame = HEAD_FRAME_NORMAL_A;
    private int postCollapseMaxXAccumulator;
    private boolean postCollapseMaxXActive;
    private boolean minibossSpawned;
    private boolean droppedBoxArmed;
    private int droppedBoxTimer;
    private int droppedBoxX;
    private int droppedBoxY;
    private LbzMinibossBoxRig boxRig;
    private int hitReactionTimer;
    private boolean shipGone;
    @RewindTransient(reason = "hardware handle is rebound from the captured KosM ordinal")
    private HardwareWorkHandle initialBoxArtHandle;
    private long initialBoxArtOrdinal = -1;
    @RewindTransient(reason = "hardware handle is rebound from the captured KosM ordinal")
    private HardwareWorkHandle collapseBoxArtHandle;
    private long collapseBoxArtOrdinal = -1;
    @RewindTransient(reason = "hardware handle is rebound from the captured KosM ordinal")
    private HardwareWorkHandle minibossArtHandle;
    private long minibossArtOrdinal = -1;
    private boolean minibossArtLoaded;

    public Lbz1RobotnikEventController(ObjectSpawn spawn) {
        super(spawn, "LBZ1Robotnik");
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getCollisionFlags() {
        return (hitReactionTimer > 0 || shipGone) ? 0 : COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return COLLISION_PROPERTY_ALWAYS_BOUNCE;
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (hitReactionTimer > 0 || shipGone || isDestroyed()) {
            return;
        }
        hitReactionTimer = HIT_REACTION_FRAMES;
        headFrame = HEAD_FRAME_HURT;
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        serviceMinibossBoxArtQueues();
        serviceMinibossArtQueue();
        if (isPlayerKnuckles()) {
            // ROM loc_8CB90: Knuckles never meets Obj_LBZ1Robotnik here.
            ObjectLifetimeOps.deleteNoRespawn(this);
            return;
        }
        flameVisible = false;
        animateHead();
        updateHitReaction();
        updateBoxRig();
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }

        switch (routine) {
            case ROUTINE_INIT -> initialize();
            case ROUTINE_APPROACH_HOVER -> updateApproachHover(player);
            case ROUTINE_FIRST_RISE -> updateFirstRise();
            case ROUTINE_WAIT_FOR_COLLAPSE_TRIGGER -> updateWaitForCollapseTrigger(player);
            case ROUTINE_WAIT_FOR_COLLAPSE_CLEAR -> {
                if (collapseEventFinished()) {
                    unlockPostCollapseCamera();
                    // CreateChild6_Simple allocates Obj_IncLevEndXGradual after
                    // Robotnik's slot, so Process_Sprites executes the new
                    // child once on this same frame. Seed its first $4000
                    // accumulator step here even though it has no integer
                    // carry yet.
                    updatePostCollapseCameraMax();
                    queueCollapseMinibossBoxArt();
                    routine = ROUTINE_AFTER_COLLAPSE;
                }
            }
            case ROUTINE_AFTER_COLLAPSE -> updateAfterCollapse(player);
            case ROUTINE_SECOND_RISE -> updateSecondRise();
            case ROUTINE_DIAGONAL_ESCAPE -> updateDiagonalEscape(vIntRunCount);
            case ROUTINE_POST_HANDOFF -> updatePostHandoff(vIntRunCount);
            default -> {
            }
        }
        updateDynamicSpawn(motion.x & 0xFFFF, motion.y & 0xFFFF);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        drawMinibossBox();
        if (shipGone) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(SHIP_FRAME, getX(), getY(), facingLeft, false, PALETTE_LINE);
        renderer.drawFrameIndex(headFrame, getX(), getY() + HEAD_Y_OFFSET, facingLeft, false, PALETTE_LINE);
        if (flameVisible) {
            int flameDx = facingLeft ? -FLAME_X_OFFSET : FLAME_X_OFFSET;
            renderer.drawFrameIndex(FLAME_FRAME, getX() + flameDx, getY() + FLAME_Y_OFFSET,
                    facingLeft, false, PALETTE_LINE);
        }
    }

    public int getRoutineForTest() {
        return routine;
    }

    public int getXVelocityForTest() {
        return motion.xVel;
    }

    public int getYVelocityForTest() {
        return motion.yVel;
    }

    public int getSwingMaxVelocityForTest() {
        return swingMaxVelocity;
    }

    public int getSwingAccelerationForTest() {
        return swingAcceleration;
    }

    public boolean isFacingLeftForTest() {
        return facingLeft;
    }

    public boolean isFlameVisibleForTest() {
        return flameVisible;
    }

    public int getHitReactionTimerForTest() {
        return hitReactionTimer;
    }

    public void forceRoutineForTest(int routine) {
        this.routine = routine;
    }

    public void forceInitializedForTest(int x, int y, int routine, int xVel, int yVel, boolean facingLeft) {
        this.motion.x = x;
        this.motion.y = y;
        this.motion.xSub = 0;
        this.motion.ySub = 0;
        this.motion.xVel = xVel;
        this.motion.yVel = yVel;
        this.routine = routine;
        this.facingLeft = facingLeft;
        this.flameVisible = xVel != 0;
        if (boxRig == null) {
            boxRig = new LbzMinibossBoxRig(x & 0xFFFF, (y + BOX_ANCHOR_Y_OFFSET) & 0xFFFF);
        }
        updateDynamicSpawn(x, y);
    }

    public void forceMinibossBoxReleaseForTest(int x, int y) {
        minibossSpawned = true;
        droppedBoxArmed = true;
        droppedBoxX = x & 0xFFFF;
        droppedBoxY = y & 0xFFFF;
        boxRig = new LbzMinibossBoxRig(droppedBoxX, droppedBoxY);
        boxRig.release();
    }

    private void initialize() {
        queueInitialMinibossBoxArt();
        ensureRobotnikArtLoaded();
        routine = ROUTINE_APPROACH_HOVER;
        swingSetup1();
        services().camera().setMinX((short) APPROACH_CAMERA_MIN_X);
        services().camera().setMaxX((short) APPROACH_CAMERA_MAX_X);
        // ROM loc_8CB9E: st (_unkFAAB).w — tells the standalone
        // Obj_LBZMinibossBox that the Robotnik intro owns this fight.
        LbzZoneRuntimeState lbz = resolveLbzRuntimeState();
        if (lbz != null) {
            lbz.setRobotnikIntroActive(true);
        }
        boxRig = new LbzMinibossBoxRig(getX(), getY() + BOX_ANCHOR_Y_OFFSET);
    }

    private void queueInitialMinibossBoxArt() {
        if (initialBoxArtOrdinal >= 0) {
            return;
        }
        try {
            S3kKosModuleQueue moduleQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            initialBoxArtHandle = moduleQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR,
                    Sonic3kConstants.ART_TILE_LBZ_MINIBOSS_BOX);
            initialBoxArtOrdinal = initialBoxArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to queue LBZ initial miniboss-box KosM art", e);
        }
    }

    private void queueCollapseMinibossBoxArt() {
        if (collapseBoxArtOrdinal >= 0) {
            return;
        }
        try {
            S3kKosModuleQueue moduleQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            collapseBoxArtHandle = moduleQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR,
                    Sonic3kConstants.ART_TILE_LBZ_MINIBOSS_BOX);
            collapseBoxArtOrdinal = collapseBoxArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to queue LBZ collapse miniboss-box KosM art", e);
        }
    }

    /** ROM loc_8CCF6: queue ArtKosM_LBZMiniboss at the second-rise handoff. */
    private void queueMinibossArt() {
        if (minibossArtLoaded || minibossArtOrdinal >= 0) {
            return;
        }
        try {
            S3kKosModuleQueue moduleQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            minibossArtHandle = moduleQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_ADDR,
                    Sonic3kConstants.ART_TILE_LBZ_MINIBOSS);
            minibossArtOrdinal = minibossArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException("Unable to queue LBZ miniboss KosM art", e);
        }
    }

    private void serviceMinibossBoxArtQueues() {
        serviceInitialMinibossBoxArt();
        serviceCollapseMinibossBoxArt();
    }

    private void serviceMinibossArtQueue() {
        if (minibossArtLoaded || minibossArtOrdinal < 0) {
            return;
        }
        S3kKosModuleQueue moduleQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
        if (minibossArtHandle == null) {
            minibossArtHandle = services().hardwareTiming().pendingHandle(
                            HardwareWorkKind.KOS_MODULE_QUEUE, minibossArtOrdinal)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing restored LBZ miniboss KosM job " + minibossArtOrdinal));
        }
        if (moduleQueue.isReady(minibossArtHandle)) {
            moduleQueue.claim(minibossArtHandle);
            minibossArtHandle = null;
            minibossArtOrdinal = -1;
            minibossArtLoaded = true;
        }
    }

    private void serviceInitialMinibossBoxArt() {
        if (initialBoxArtOrdinal < 0) {
            return;
        }
        S3kKosModuleQueue moduleQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
        if (initialBoxArtHandle == null) {
            initialBoxArtHandle = services().hardwareTiming().pendingHandle(
                            HardwareWorkKind.KOS_MODULE_QUEUE, initialBoxArtOrdinal)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing restored LBZ initial miniboss-box KosM job "
                                    + initialBoxArtOrdinal));
        }
        if (moduleQueue.isReady(initialBoxArtHandle)) {
            moduleQueue.claim(initialBoxArtHandle);
            initialBoxArtHandle = null;
            initialBoxArtOrdinal = -1;
        }
    }

    private void serviceCollapseMinibossBoxArt() {
        if (collapseBoxArtOrdinal < 0) {
            return;
        }
        S3kKosModuleQueue moduleQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
        if (collapseBoxArtHandle == null) {
            collapseBoxArtHandle = services().hardwareTiming().pendingHandle(
                            HardwareWorkKind.KOS_MODULE_QUEUE, collapseBoxArtOrdinal)
                    .orElseThrow(() -> new IllegalStateException(
                            "Missing restored LBZ collapse miniboss-box KosM job "
                                    + collapseBoxArtOrdinal));
        }
        if (moduleQueue.isReady(collapseBoxArtHandle)) {
            moduleQueue.claim(collapseBoxArtHandle);
            collapseBoxArtHandle = null;
            collapseBoxArtOrdinal = -1;
        }
    }

    private void updateApproachHover(AbstractPlayableSprite player) {
        // ROM loc_8CBF2: Find_OtherObject d1==0 (player at or above the ship)
        // triggers the rise immediately; otherwise the player must be within
        // $70/$60 and grounded.
        boolean playerAtOrAboveShip = signedDelta(getY(), player.getCentreY() & 0xFFFF) >= 0;
        if (playerAtOrAboveShip
                || (isPlayerNear(player, APPROACH_NEAR_X, APPROACH_NEAR_Y) && !player.getAir())) {
            routine = ROUTINE_FIRST_RISE;
            motion.yVel = FIRST_RISE_Y_VEL;
            return;
        }
        swingAndMove();
    }

    private void updateFirstRise() {
        if ((motion.y & 0xFFFF) < FIRST_RISE_TELEPORT_Y) {
            routine = ROUTINE_WAIT_FOR_COLLAPSE_TRIGGER;
            facingLeft = true;
            motion.x = TELEPORT_X;
            motion.y = TELEPORT_Y;
            // ROM loc_8CC3C writes the position words only; the low subpixel
            // words remain part of the MoveSprite2 state across this teleport.
            swingSetup1();
            return;
        }
        moveSprite2();
    }

    private void updateWaitForCollapseTrigger(AbstractPlayableSprite player) {
        if ((services().camera().getX() & 0xFFFF) < TRIGGER_CAMERA_X) {
            return;
        }
        if ((player.getCentreY() & 0xFFFF) < TRIGGER_PLAYER_Y) {
            return;
        }
        if (player.getAir()) {
            return;
        }
        if (services().levelEventProvider() instanceof Sonic3kLevelEventManager manager
                && manager.getLbzEvents() != null) {
            manager.getLbzEvents().startEndingCollapse();
            routine = ROUTINE_WAIT_FOR_COLLAPSE_CLEAR;
        }
    }

    private void updateAfterCollapse(AbstractPlayableSprite player) {
        updatePostCollapseCameraMin();
        updatePostCollapseCameraMax();
        // ROM loc_8CCB4 calls Find_SonicTails, which selects the nearer native
        // P1/P2 by horizontal distance before testing d2 against $60. The
        // update argument is only a fallback for isolated object fixtures that
        // do not expose the injected player roster.
        ObjectPlayerQuery.NearestPlayerX nearest = services().playerQuery().nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2, motion.x);
        boolean nativePlayerNear = nearest.player() != null
                ? nearest.distance() < POST_COLLAPSE_NEAR_X
                : isPlayerNear(player, POST_COLLAPSE_NEAR_X, Integer.MAX_VALUE);
        if (nativePlayerNear) {
            routine = ROUTINE_SECOND_RISE;
            motion.yVel = FIRST_RISE_Y_VEL;
            return;
        }
        swingAndMove();
    }

    private void updateSecondRise() {
        if ((motion.y & 0xFFFF) <= SECOND_RISE_STOP_Y) {
            routine = ROUTINE_DIAGONAL_ESCAPE;
            motion.y = SECOND_RISE_STOP_Y;
            motion.ySub = 0;
            motion.xVel = DIAGONAL_ESCAPE_X_VEL;
            motion.yVel = DIAGONAL_ESCAPE_Y_VEL;
            flameVisible = true;
            // ROM loc_8CCF6 queues ArtKosM_LBZMiniboss before the ship hands
            // the arena over to Obj_LBZMiniboss.
            queueMinibossArt();
            ensureRobotnikArtLoaded();
            armDroppedBoxHandoff();
            return;
        }
        moveSprite2();
    }

    private void updateDiagonalEscape(int vIntRunCount) {
        moveSprite2();
        flameVisible = (vIntRunCount & 1) == 0 && motion.xVel != 0;
        updateDroppedBoxHandoff();
        if ((motion.y & 0xFFFF) >= DIAGONAL_ESCAPE_HANDOFF_Y) {
            routine = ROUTINE_POST_HANDOFF;
            motion.yVel = 0;
        }
    }

    private void updatePostHandoff(int vIntRunCount) {
        // ROM loc_8CD4C: keep moving and delete once coarse-off-screen; the
        // floating box panels are independent and stay behind.
        moveSprite2();
        flameVisible = !shipGone && (vIntRunCount & 1) == 0 && motion.xVel != 0;
        updateDroppedBoxHandoff();
        if (!shipGone && isShipCoarseOffscreen()) {
            shipGone = true;
        }
        if (shipGone && (boxRig == null || !boxRig.hasVisiblePieces())) {
            ObjectLifetimeOps.deleteNoRespawn(this);
        }
    }

    private boolean isShipCoarseOffscreen() {
        int cameraX = services().camera().getX() & 0xFFFF;
        int coarseDistance = ((getX() & 0xFF80) - ((cameraX - 0x80) & 0xFF80)) & 0xFFFF;
        return coarseDistance > OFFSCREEN_COARSE_RANGE;
    }

    private boolean collapseEventFinished() {
        return services().levelEventProvider() instanceof Sonic3kLevelEventManager manager
                && manager.getLbzEvents() != null
                && manager.getLbzEvents().isEndingCollapseFinished();
    }

    private void unlockPostCollapseCamera() {
        postCollapseMaxXAccumulator = 0;
        postCollapseMaxXActive = true;
    }

    private void updatePostCollapseCameraMin() {
        int cameraX = services().camera().getX() & 0xFFFF;
        if (cameraX < POST_COLLAPSE_MIN_FOLLOW_LIMIT_X) {
            services().camera().setMinX((short) cameraX);
        }
    }

    private void updatePostCollapseCameraMax() {
        if (!postCollapseMaxXActive) {
            return;
        }
        int currentMax = services().camera().getMaxX() & 0xFFFF;
        if (currentMax >= POST_COLLAPSE_CAMERA_MAX_X) {
            services().camera().setMaxX((short) POST_COLLAPSE_CAMERA_MAX_X);
            postCollapseMaxXActive = false;
            return;
        }

        postCollapseMaxXAccumulator += INC_LEVEL_END_X_STEP;
        int delta = postCollapseMaxXAccumulator >>> 16;
        int nextMax = currentMax + delta;
        if (nextMax >= POST_COLLAPSE_CAMERA_MAX_X) {
            services().camera().setMaxX((short) POST_COLLAPSE_CAMERA_MAX_X);
            postCollapseMaxXActive = false;
        } else {
            services().camera().setMaxX((short) nextMax);
        }
    }

    private boolean isPlayerKnuckles() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration()) == PlayerCharacter.KNUCKLES;
    }

    private LbzZoneRuntimeState resolveLbzRuntimeState() {
        if (services().zoneRuntimeRegistry() == null) {
            return null;
        }
        return S3kRuntimeStates.currentLbz(services().zoneRuntimeRegistry()).orElse(null);
    }

    private void swingSetup1() {
        motion.yVel = SWING_INITIAL_VELOCITY;
        swingMaxVelocity = SWING_INITIAL_VELOCITY;
        swingAcceleration = SWING_ACCELERATION;
        swingDirectionDown = false;
    }

    private void swingAndMove() {
        SwingMotion.Result result = SwingMotion.update(
                swingAcceleration, motion.yVel, swingMaxVelocity, swingDirectionDown);
        motion.yVel = result.velocity();
        swingDirectionDown = result.directionDown();
        moveSprite2();
    }

    private void moveSprite2() {
        SubpixelMotion.moveSprite2(motion);
    }

    private int signedDelta(int a, int b) {
        return (short) ((a & 0xFFFF) - (b & 0xFFFF));
    }

    private boolean isPlayerNear(AbstractPlayableSprite player, int maxXDistance, int maxYDistance) {
        int dx = Math.abs(signedDelta(player.getCentreX() & 0xFFFF, motion.x & 0xFFFF));
        if (dx >= maxXDistance) {
            return false;
        }
        if (maxYDistance == Integer.MAX_VALUE) {
            return true;
        }
        int dy = Math.abs(signedDelta(player.getCentreY() & 0xFFFF, motion.y & 0xFFFF));
        return dy < maxYDistance;
    }

    private void animateHead() {
        if (hitReactionTimer > 0) {
            headFrame = HEAD_FRAME_HURT;
            return;
        }
        headAnimationTimer++;
        headFrame = ((headAnimationTimer / 6) & 1) == 0 ? HEAD_FRAME_NORMAL_A : HEAD_FRAME_NORMAL_B;
    }

    private void updateHitReaction() {
        if (hitReactionTimer > 0) {
            hitReactionTimer--;
        }
    }

    private void updateBoxRig() {
        if (boxRig == null) {
            return;
        }
        if (!boxRig.isReleased()) {
            int anchorX = droppedBoxArmed ? droppedBoxX : getX();
            int anchorY = droppedBoxArmed ? droppedBoxY : getY() + BOX_ANCHOR_Y_OFFSET;
            boxRig.follow(anchorX, anchorY);
        }
        var camera = services().camera();
        boxRig.update(camera != null ? camera.getX() & 0xFFFF : LbzMinibossBoxRig.NO_CAMERA);
    }

    private void drawMinibossBox() {
        if (boxRig == null || !boxRig.hasVisiblePieces()) {
            return;
        }
        PatternSpriteRenderer boxRenderer = getRenderer(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
        if (boxRenderer == null) {
            return;
        }
        boxRig.draw(boxRenderer, BOX_PALETTE_LINE);
    }

    private void ensureRobotnikArtLoaded() {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager != null && renderManager.getArtProvider() instanceof Sonic3kObjectArtProvider provider) {
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS_BOX);
            provider.ensureStandaloneArtLoaded(Sonic3kObjectArtKeys.LBZ_MINIBOSS);
        }
    }

    private void armDroppedBoxHandoff() {
        if (droppedBoxArmed || minibossSpawned) {
            return;
        }
        droppedBoxArmed = true;
        droppedBoxTimer = DROPPED_BOX_WAIT;
        droppedBoxX = motion.x & 0xFFFF;
        droppedBoxY = (motion.y + BOX_ANCHOR_Y_OFFSET) & 0xFFFF;
    }

    private void updateDroppedBoxHandoff() {
        if (!droppedBoxArmed || minibossSpawned) {
            return;
        }
        droppedBoxTimer--;
        if (droppedBoxTimer < 0) {
            spawnMiniboss(droppedBoxX, droppedBoxY);
        }
    }

    private void spawnMiniboss(int x, int y) {
        if (minibossSpawned) {
            return;
        }
        minibossSpawned = true;
        // ROM loc_8CD9C: bset #3,$38 releases the box pieces, plays
        // sfx_BossActivate, and creates Obj_LBZMiniboss.
        if (boxRig != null) {
            boxRig.release();
        }
        services().playSfx(Sonic3kSfx.BOSS_ACTIVATE.id);
        notifyBoxOpenedChunkSwap();
        spawnFreeChild(() -> new SongFadeTransitionInstance(
                MINIBOSS_MUSIC_FADE_FRAMES, Sonic3kMusic.MINIBOSS.id));
        spawnChild(() -> new LbzMinibossInstance(new ObjectSpawn(
                x & 0xFFFF, y & 0xFFFF, Sonic3kObjectIds.LBZ_MINIBOSS, 0, 0, false, 0)));
    }

    /**
     * ROM loc_8CE84: the first box piece sets Events_fg_4=$55, and
     * LBZ1_ScreenEvent swaps the boss-area layout chunk in response.
     */
    private void notifyBoxOpenedChunkSwap() {
        if (services().levelEventProvider() instanceof Sonic3kLevelEventManager manager
                && manager.getLbzEvents() != null) {
            manager.getLbzEvents().applyMinibossBoxOpenedChunkSwap(false);
        }
    }
}
