package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.objects.HtzGroundFireObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreateObjectLinks;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HTZ Boss Lava Ball projectile (Obj52 subtype 6).
 * ROM Reference: s2.asm:63900-64006 (Obj52_LavaBall)
 *
 * Lava balls are spawned in pairs (left and right) when the boss descends.
 * They arc upward then fall, transforming into lava bubbles on ground contact.
 * They damage the player on contact.
 */
public class HTZBossLavaBall extends AbstractBossChild
        implements TouchResponseProvider, RewindRecreatable {

    // Physics constants (ROM: s2.asm:63987-64006)
    /** Left ball X velocity (ROM: move.w #$1C00,d0 / neg.w d0) */
    private static final int LEFT_X_VEL = -0x1C00;
    /** Right ball X velocity (ROM: move.w #$1C00,d0) */
    private static final int RIGHT_X_VEL = 0x1C00;
    /** Initial Y velocity from left side (ROM: move.w #-$5400,y_vel(a1)) */
    private static final int Y_VEL_LEFT_SIDE = -0x5400;
    /** Initial Y velocity from right side (ROM: move.w #-$6400,y_vel(a1)) */
    private static final int Y_VEL_RIGHT_SIDE = -0x6400;
    /** Gravity per frame (ROM: addi.w #$380,y_vel(a0)) */
    private static final int GRAVITY = 0x380;
    /**
     * Obj52 child objects use ArtTile_ArtNem_HTZBoss as their art base, while the
     * parent renderer sheet is aligned to ArtTile_ArtNem_Eggpod_2. The VRAM delta is
     * $60 tiles, so child tile indices must be shifted by +$60 on this combined sheet.
     */
    private static final int CHILD_TILE_BASE_OFFSET = 0x60;
    private static final int TILE_LARGE_FIRE_1 = CHILD_TILE_BASE_OFFSET + 0x63;
    private static final int TILE_LARGE_FIRE_2 = CHILD_TILE_BASE_OFFSET + 0x67;

    // Like HTZBossFlamethrower, this child live-tracks its own position
    // (updateDynamicSpawn() every frame it is initialized), so its captured spawn is
    // directly comparable to the boss's. It launches at up to Y_VEL_RIGHT_SIDE
    // (0x6400, i.e. ~6.25 speed-units/frame in the 16:16-with-<<4 scheme used here) and
    // decelerates under GRAVITY (0x380/frame) until it returns to launch height --
    // roughly 0x6400/0x380 =~ 28.6 frames to apex, ~57 frames total flight -- while
    // drifting horizontally at up to LEFT_X_VEL/RIGHT_X_VEL (0x1C00, ~1.75px/frame in
    // the same scheme), for a worst-case horizontal travel around 57*1.75 =~ 100px
    // before floor contact transforms it or the Y>0x700 safety catch destroys it.
    // Round up generously (0x200 = 512px) for margin.
    private static final int MAX_PARENT_RELINK_DISTANCE = 0x200;

    // Fixed-point position accumulators (ROM: objoff_2A for x, y_pos for y)
    private int xFixed;
    private int yFixed;
    private int xVel;
    private int yVel;

    // State
    private boolean initialized;
    private boolean leftBall;
    private boolean fromLeftSide;
    private int animFrame;
    private int animTimer;
    private static final int Y_RADIUS = 8;  // ROM: move.b #8,y_radius(a1)

    HTZBossLavaBall(ObjectSpawn spawn) {
        this(new Sonic2HTZBossInstance(spawn), spawn.x(), spawn.y(), false, false);
    }

    public static HTZBossLavaBall createInitialPairSpawner(Sonic2HTZBossInstance parent, int spawnX, int spawnY) {
        return new HTZBossLavaBall(parent, spawnX, spawnY, true, false, 0);
    }

    public HTZBossLavaBall(Sonic2HTZBossInstance parent, int spawnX, int spawnY,
                           boolean leftBall, boolean fromLeftSide) {
        this(parent, spawnX, spawnY, leftBall, true, 0);
    }

    private HTZBossLavaBall(Sonic2HTZBossInstance parent, int spawnX, int spawnY,
                            boolean leftBall, boolean initialized, int ignored) {
        super(parent, "HTZ Lava Ball", 3, Sonic2ObjectIds.HTZ_BOSS);

        // Initial position
        this.currentX = spawnX;
        this.currentY = spawnY;
        if (initialized) {
            initializeBall(leftBall);
        }
    }

    private void initializeBall(boolean leftBall) {
        this.initialized = true;
        this.leftBall = leftBall;
        this.fromLeftSide = currentX == 0x2F40;
        this.xFixed = currentX << 16;
        this.yFixed = currentY << 16;
        this.xVel = leftBall ? LEFT_X_VEL : RIGHT_X_VEL;
        this.yVel = fromLeftSide ? Y_VEL_LEFT_SIDE : Y_VEL_RIGHT_SIDE;
        this.animFrame = 0;
        this.animTimer = 3;
    }

    private void initializePairFromCurrentSlot() {
        // ROM loc_2FF78 initializes the current SST slot as ball 0, then
        // AllocateObject creates ball 1 with the same starting x_pos/y_pos.
        initializeBall(true);
        Sonic2HTZBossInstance htzParent = (Sonic2HTZBossInstance) parent;
        HTZBossLavaBall rightBall = spawnFreeChild(() -> new HTZBossLavaBall(
                htzParent,
                currentX,
                currentY,
                false,
                true,
                0));
        if (htzParent != null) {
            htzParent.getChildComponents().add(rightBall);
        }
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        // The lava ball is a child of the HTZ boss. If the boss was defeated/swept
        // before capture there is no parent to bind to, so drop the child rather than
        // throw. acceptDestroyed relinks to a restored-but-destroyed boss so the
        // child preserves its captured relationship. Bounded (see
        // MAX_PARENT_RELINK_DISTANCE) since this child's live-tracked captured position
        // is directly comparable to the boss's.
        return RewindRecreateObjectLinks.nearestObject(
                        ctx, Sonic2HTZBossInstance.class, true, MAX_PARENT_RELINK_DISTANCE)
                .<AbstractObjectInstance>map(boss ->
                        new HTZBossLavaBall(boss, ctx.spawn().x(), ctx.spawn().y(), false, false))
                .orElse(null);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!shouldUpdate(vIntRunCount)) {
            return;
        }
        if (!initialized) {
            initializePairFromCurrentSlot();
            updateDynamicSpawn();
            return;
        }

        // Apply physics (ROM: Obj52_LavaBall_Move)
        // ROM uses 16.16 fixed-point math
        // move.w x_vel(a0),d0 / ext.l d0 / asl.l #4,d0 / add.l d0,d2
        xFixed += (xVel << 4);
        yFixed += (yVel << 4);

        // Apply gravity
        // ROM: addi.w #$380,y_vel(a0)
        yVel += GRAVITY;

        // Update position from fixed-point
        currentX = xFixed >> 16;
        currentY = yFixed >> 16;

        // Check floor collision
        // ROM: jsrto JmpTo4_ObjCheckFloorDist
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        if (floor.hasCollision() && floor.distance() < 0) {
            // Hit the floor - transform to fire trail spawner (Obj20 routine $A)
            // ROM: s2.asm:64016-64060 - transforms to Obj20 with routine=$A,
            // objoff_32=9, objoff_36=3, spawning fire trail along ground
            services().playSfx(Sonic2Sfx.FIRE_BURN.id);

            int floorY = currentY + floor.distance();
            // Spread direction matches lava ball X velocity
            // ROM: tst.w x_vel(a1) / bpl.s / neg.w d0
            int spreadDir = (xVel >= 0) ? 1 : -1;

            // Spawn ground fire at impact point with 3-deep spread chain
            // ROM: objoff_36(a0) = 3
            spawnFreeChild(() -> new HtzGroundFireObjectInstance(
                    currentX, floorY, spreadDir, 3));

            setDestroyed(true);
            return;
        }

        // Update animation
        animTimer--;
        if (animTimer <= 0) {
            animTimer = 3;
            animFrame = (animFrame + 1) % 2;  // ROM: Animation 7 has 2 frames
        }

        // Check if off-screen (safety destroy)
        if (currentY > 0x700) {  // Well below lava level
            setDestroyed(true);
        }

        updateDynamicSpawn();
    }

    /**
     * Returns collision flags for touch response.
     * ROM: move.b #$8B,collision_flags(a1) - enemy projectile with collision size
     */
    public int getCollisionFlags() {
        if (!initialized || isDestroyed()) {
            return 0;
        }
        return 0x8B;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!initialized || isDestroyed()) {
            return;
        }
        ObjectRenderManager renderManager =
                services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer =
                renderManager.getRenderer(Sonic2ObjectArtKeys.HTZ_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Lava ball uses frames 14-15 (large lava ball frames)
        // ROM: Animation 7 - byte_302B7: dc.b 3, $E, $F, $FF → frames 14 ($E), 15 ($F)
        int baseTile = (animFrame == 0) ? TILE_LARGE_FIRE_1 : TILE_LARGE_FIRE_2;
        int drawX = currentX - 8;
        int drawY = currentY - 8;
        // Sprite mapping pieces use column-major tile order:
        // tileOffset = (tx * heightTiles) + ty (same as SpritePieceRenderer).
        for (int ty = 0; ty < 2; ty++) {
            for (int tx = 0; tx < 2; tx++) {
                int tile = baseTile + (tx * 2) + ty;
                renderer.drawPatternIndex(tile, drawX + (tx * 8), drawY + (ty * 8), 0);
            }
        }
    }
}
