package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HTZ Ground Fire (Obj20, routine $A).
 * ROM Reference: s2.asm lines 48579-48616
 *
 * Fire that spreads along the ground after a fireball impacts.
 * Clones itself at ±14px intervals with floor snap, up to 3 clones in a chain.
 *
 * Animation: Anim 2 = frames 4,5,2,3,0,1,0,1,2,3,4,5 with delay 5, then $FC (delete).
 * Collision flags: 0x8B (inherited from projectile).
 */
public class HtzGroundFireObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    private static final int PRIORITY = 3;
    private static final int COLLISION_FLAGS = 0x8B;
    private static final int Y_RADIUS = 8;
    private static final int SPREAD_OFFSET = 14;       // ROM: move.w #$E,d0
    private static final int INITIAL_SPREAD_TIMER = 9;  // ROM: move.w #9,objoff_32
    private static final int SPREAD_TIMER_RELOAD = 0x7F; // ROM: move.w #$7F,objoff_32

    // Anim 2 frame sequence from ROM: dc.b 5, 4, 5, 2, 3, 0, 1, 0, 1, 2, 3, 4, 5, $FC
    private static final int ANIM_DELAY = 5;
    private static final int[] ANIM_SEQUENCE = {4, 5, 2, 3, 0, 1, 0, 1, 2, 3, 4, 5};

    private int currentX;
    private int currentY;
    private int spreadDirection;  // +1 (right) or -1 (left)
    private int spreadRemaining;        // Clones left to spawn
    private int spreadTimer;            // Countdown to next spread
    private int animFrame;
    private int animTimer;
    private int animIndex;              // Index into ANIM_SEQUENCE

    public HtzGroundFireObjectInstance(int x, int y, int spreadDirection, int spreadRemaining) {
        // Dynamic child - uses parent ID for spawn record (not placed from level layout)
        super(new ObjectSpawn(x, y, Sonic2ObjectIds.LAVA_BUBBLE, 0, 0, false, 0), "Ground Fire");
        this.currentX = x;
        this.currentY = y;
        this.spreadDirection = spreadDirection;
        this.spreadRemaining = spreadRemaining;
        this.spreadTimer = INITIAL_SPREAD_TIMER;
        this.animIndex = 0;
        this.animFrame = ANIM_SEQUENCE[0];
        this.animTimer = ANIM_DELAY;
    }

    HtzGroundFireObjectInstance(ObjectSpawn spawn) {
        this(spawn.x(), spawn.y(), 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Spread logic: ROM s2.asm:48579-48611
        spreadTimer--;
        if (spreadTimer < 0) {
            spreadTimer = SPREAD_TIMER_RELOAD;
            if (spreadRemaining > 0) {
                spreadRemaining--;
                spawnClone();
            }
        }

        // Animation: anim 2 plays through ANIM_SEQUENCE then deletes
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_DELAY;
            animIndex++;
            if (animIndex >= ANIM_SEQUENCE.length) {
                // $FC in anim script: advance routine to $C (delete)
                setDestroyed(true);
                return;
            }
            animFrame = ANIM_SEQUENCE[animIndex];
        }
    }

    /**
     * Spawn a clone offset by ±14px with floor snap.
     * ROM: s2.asm:48585-48611
     */
    private void spawnClone() {
        int cloneX = currentX + (spreadDirection * SPREAD_OFFSET);
        int cloneY = currentY;

        // Floor snap for the clone position
        // ROM: bsr.w FireCheckFloorDist / add.w d1,y_pos(a1)
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(cloneX, cloneY, Y_RADIUS);
        if (floor.foundSurface()) {
            cloneY += floor.distance();
        }

        HtzGroundFireObjectInstance clone = new HtzGroundFireObjectInstance(
                cloneX, cloneY, spreadDirection, spreadRemaining);
        spawnDynamicObject(clone);
    }

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
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
        if (isDestroyed()) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        // Ground fire uses GROUND_FIRE sheet (ArtNem_HtzFireball1 + Obj20_MapUnc_23294)
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.GROUND_FIRE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(animFrame, currentX, currentY, false, false);
    }
}
