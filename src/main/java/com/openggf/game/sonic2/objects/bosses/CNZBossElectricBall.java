package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Boss Electric Ball - Hazard projectile spawned by the CNZ boss.
 * ROM Reference: s2.asm Obj51 (subtype 4)
 *
 * States:
 * - ATTACH: Ball lowers from boss
 * - FALL: Ball drops toward floor
 * - SPLIT: Ball splits into two after hitting floor
 */
public class CNZBossElectricBall extends AbstractObjectInstance implements TouchResponseProvider, RewindRecreatable {

    // Routine states
    private static final int BALL_ATTACH = 0;
    private static final int BALL_FALL = 1;
    private static final int BALL_SPLIT = 2;

    // Physics constants
    private static final int GRAVITY = 0x38;
    private static final int Y_RADIUS = 8;
    private static final int DELETE_Y = 0x705;

    // Animation constants
    // Obj51 preserves frame 0 as a null frame, so these are the ROM mapping_frame values.
    // Frame $12 = Map_obj51_0144 = 3x3 spiked ball (24x24 pixels)
    // Frame $13 = Map_obj51_014E = 1x1 small orb 1 (8x8 pixels)
    // Frame $14 = Map_obj51_0158 = 1x1 small orb 2 (8x8 pixels)
    private static final int FRAME_SPIKED_BALL = 0x12;
    private static final int FRAME_ORB_1 = 0x13;
    private static final int FRAME_ORB_2 = 0x14;
    private Sonic2CNZBossInstance mainBoss;

    // Position
    private int x;
    private int y;
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;

    // State
    private int routineState;
    private int ballRiseOffset;
    private boolean exploding;
    private int renderFlags;
    private int lastVIntRunCount;
    private int positiveSplitPrePhysicsX;
    private int positiveSplitPrePhysicsY;
    private boolean positiveSplitPrePhysicsReady;

    /**
     * Create electric ball attached to boss.
     * ROM: loc_31F48 - Init sets position to parent.y + 0x30, then advances to attach.
     */
    public CNZBossElectricBall(ObjectSpawn spawn, Sonic2CNZBossInstance mainBoss) {
        super(spawn, "CNZ Boss Ball");
        this.mainBoss = mainBoss;

        // ROM: loc_31F48 - position = parent (x, y+0x30) during init.
        // The engine spawn records the parent SST x_pos visible when Obj51 is
        // allocated. Keep that published coordinate: Boss_MoveObject no longer
        // runs during loc_31BA8, so every later loc_31F96 parent read is equal.
        // Then immediately advances to attach routine where objoff_28 starts at 0
        // So ball position becomes parent.y + 0 on first frame of attach
        // This matches the ROM behavior where the ball "appears" at parent position
        this.x = spawn.x();
        this.y = mainBoss.getY();  // Will be adjusted by ballRiseOffset in attach
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.xVel = 0;
        this.yVel = 0;

        this.routineState = BALL_ATTACH;
        this.ballRiseOffset = 0;  // ROM: objoff_28 starts at 0
        this.exploding = false;
        this.renderFlags = 0;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CNZBossInstance parent = findClosestLiveParent(ctx);
        return parent == null ? null : new CNZBossElectricBall(ctx.spawn(), parent);
    }

    private static Sonic2CNZBossInstance findClosestLiveParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        Sonic2CNZBossInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        int childX = ctx.spawn().x();
        int childY = ctx.spawn().y();
        for (ObjectInstance inst : ctx.objectServices().objectManager().getActiveObjects()) {
            if (inst instanceof Sonic2CNZBossInstance boss && !boss.isDestroyed()) {
                long dx = (long) boss.getX() - childX;
                long dy = (long) boss.getY() - childY;
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = boss;
                }
            }
        }
        return best;
    }

    /**
     * Create split ball (called internally after floor hit).
     */
    private CNZBossElectricBall(int x, int y, int xVel, int yVel, Sonic2CNZBossInstance mainBoss) {
        super(new ObjectSpawn(x, y, Sonic2ObjectIds.CNZ_BOSS, 4, 0, false, 0), "CNZ Boss Ball");
        this.mainBoss = mainBoss;
        this.x = x;
        this.y = y;
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.xVel = xVel;
        this.yVel = yVel;
        this.routineState = BALL_SPLIT;
        this.exploding = true;
        this.renderFlags = 0;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        lastVIntRunCount = vIntRunCount;

        // Check if parent boss is defeated - delete ball
        if (mainBoss.isInDefeatSequence()) {
            setDestroyed(true);
            return;
        }

        switch (routineState) {
            case BALL_ATTACH -> updateBallAttach(vIntRunCount);
            case BALL_FALL -> updateBallFall();
            case BALL_SPLIT -> updateBallSplit();
        }
    }

    /**
     * ROM: loc_31F96 - Ball attached to boss, lowering.
     * objoff_28 starts at 0, increments by 1 per frame, caps at $2E.
     * Position = parent.y + objoff_28
     */
    private void updateBallAttach(int vIntRunCount) {
        // ROM loc_31F96 recopies the stationary parent x_pos. The equivalent
        // engine value was captured by the allocation spawn; re-reading the
        // live boss would expose a different fixed-accumulator phase.
        y = mainBoss.getY() + ballRiseOffset;

        // ROM: addi_.w #1,d0 / cmpi.w #$2E,d0 / blt.s + / move.w #$2E,d0
        ballRiseOffset++;
        if (ballRiseOffset >= 0x2E) {
            ballRiseOffset = 0x2E;
        }

        // Sync fixed-point position for when we transition to fall
        xFixed = x << 16;
        yFixed = y << 16;

        // ROM: tst.w (Boss_Countdown).w / bne.w DisplaySprite
        // Wait for main boss countdown to reach 0
        if (mainBoss.getBossCountdownVisibleToBall(vIntRunCount) == 0) {
            routineState = BALL_FALL;
            xVel = 0;
            yVel = 0;
        }
    }

    /**
     * ROM: loc_31FDC - Ball falling.
     * Uses ObjCheckFloorDist and splits only when d1 is negative.
     */
    private void updateBallFall() {
        applyBallPhysics();

        // ROM: jsr (ObjCheckFloorDist).l / tst.w d1 / bpl.w DisplaySprite.
        // bpl includes zero, so exact surface contact remains in BALL_FALL;
        // only a negative penetration reaches loc_32030 and splits the ball.
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(x, y, Y_RADIUS);
        if (floor.foundSurface() && floor.distance() < 0) {
            y += floor.distance();
            yFixed = y << 16;
            explodeAndSplit();
        }
    }

    /**
     * ROM: loc_32080 - Ball split, falling off.
     */
    private void updateBallSplit() {
        applyBallPhysics();

        // Delete when below floor
        if (y >= DELETE_Y) {
            setDestroyed(true);
        }
    }

    /**
     * Apply physics to ball (ROM: loc_31FF8).
     */
    private void applyBallPhysics() {
        if (routineState == BALL_SPLIT && xVel > 0) {
            // AllocateObjectAfterCurrent lets the copied positive half execute
            // after both player slots have already run. Retain the coordinate
            // from before that immediate loc_31FF8 step for the next ordinary
            // single-region touch scan.
            positiveSplitPrePhysicsX = x;
            positiveSplitPrePhysicsY = y;
            positiveSplitPrePhysicsReady = true;
        }
        xFixed = (x << 16) + (xVel << 8);
        yFixed = (y << 16) + (yVel << 8);
        yVel += GRAVITY;
        x = xFixed >> 16;
        y = yFixed >> 16;
    }

    /**
     * ROM: loc_32030 - Explode and split into two pieces.
     */
    private void explodeAndSplit() {
        services().playSfx(Sonic2Sfx.BOSS_EXPLOSION.id);
        exploding = true;
        yVel = -0x300;
        xVel = -0x100;
        routineState = BALL_SPLIT;

        // Spawn clone with opposite X velocity
        spawnBallClone();
    }

    private void spawnBallClone() {
        if (services().objectManager() == null) {
            return;
        }
        // ROM loc_32030 copies this object into an AllocateObjectAfterCurrent slot,
        // then negates the clone's x_vel. Preserve that after-current slot order.
        spawnChild(() -> new CNZBossElectricBall(x, y, 0x100, -0x300, mainBoss));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean flipped = (renderFlags & 1) != 0;
        int frame = getBallMappingFrame();
        if (frame >= 0) {
            renderer.drawFrameIndex(frame, x, y, flipped, false);
        }
    }

    /**
     * Get ball mapping frame based on animation state.
     * ROM animation 7 (byte_320DD): delay=3, frames cycle between orb 1 and orb 2.
     */
    private int getBallMappingFrame() {
        if (exploding) {
            // Cycle between FRAME_ORB_1 (18) and FRAME_ORB_2 (19) every 4 frames
            return FRAME_ORB_1 + ((lastVIntRunCount >> 2) & 1);
        }
        // During attach/fall phases, show the spiked ball
        return FRAME_SPIKED_BALL;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return 7;
    }

    @Override
    protected boolean isOnScreen() {
        Camera camera = services().camera();
        int screenX = x - camera.getX();
        int screenY = y - camera.getY();
        return screenX >= -64 && screenX <= camera.getWidth() + 64
                && screenY >= -64 && screenY <= camera.getHeight() + 64;
    }

    /**
     * Get collision flags for hazard detection.
     * ROM: collision_flags = $98 (harmful, category HAZARD, size 0x18)
     */
    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        // ROM: collision_flags = $98 (harmful)
        return 0x98;
    }

    @Override
    public TouchResponseProvider.TouchRegion[] getMultiTouchRegions() {
        if (isDestroyed()) {
            return null;
        }
        return null;
    }

    @Override
    public int getPreUpdateX() {
        if (routineState == BALL_SPLIT && xVel > 0 && positiveSplitPrePhysicsReady) {
            return positiveSplitPrePhysicsX;
        }
        return super.getPreUpdateX();
    }

    @Override
    public int getPreUpdateY() {
        if (routineState == BALL_SPLIT && xVel > 0 && positiveSplitPrePhysicsReady) {
            return positiveSplitPrePhysicsY;
        }
        return super.getPreUpdateY();
    }

    @Override
    public boolean requiresRenderFlagForTouch() {
        // S2 Touch_Boss scans Obj51 child collision_flags directly while the
        // boss is active; there is no render/on-screen touch gate in that path.
        return false;
    }

    /**
     * Get collision property (used for enemy bounce/hurt behavior).
     */
    @Override
    public int getCollisionProperty() {
        return 0;
    }
}
