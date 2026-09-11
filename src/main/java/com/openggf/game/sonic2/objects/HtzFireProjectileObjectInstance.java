package com.openggf.game.sonic2.objects;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * HTZ Fire Projectile (Obj20, routine 8).
 * ROM Reference: s2.asm lines 48538-48576
 *
 * Fireball launched by HtzFireShooterObjectInstance. Arcs with gravity,
 * flips vertically based on direction, and transforms into ground fire on floor contact.
 *
 * Physics: Standard ObjectMove (8.8 velocity, 16.16 position) + gravity 0x18/frame.
 * Collision flags: 0x8B (enemy projectile).
 */
public class HtzFireProjectileObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateZeroScalarArgsRewindRecreatable {

    private static final int PRIORITY = 3;
    private static final int GRAVITY = 0x18;           // ROM: addi.w #$18,y_vel(a0)
    private static final int Y_RADIUS = 8;             // ROM: move.b #8,y_radius(a1)
    private static final int COLLISION_FLAGS = 0x8B;   // ROM: move.b #$8B,collision_flags(a1)
    private static final int ANIM_DELAY = 7;           // ROM: move.b #7,anim_frame_duration

    // 16.16 fixed-point position
    private int xFixed;
    private int yFixed;
    private int currentX;
    private int currentY;

    // 8.8 fixed-point velocity
    private int xVel;
    private int yVel;

    private boolean hFlip;
    private boolean vFlip;
    private int animFrame;
    private int animTimer;

    private HtzFireProjectileObjectInstance() {
        this(0, 0, 0, 0, false);
    }

    public HtzFireProjectileObjectInstance(int x, int y, int xVel, int yVel, boolean hFlip) {
        // Dynamic child - uses parent ID for spawn record (not placed from level layout)
        super(new ObjectSpawn(x, y, Sonic2ObjectIds.LAVA_BUBBLE, 0, 0, false, 0), "Fire Projectile");
        this.currentX = x;
        this.currentY = y;
        this.xFixed = x << 16;
        this.yFixed = y << 16;
        this.xVel = xVel;
        this.yVel = yVel;
        this.hFlip = hFlip;
        this.vFlip = false;
        this.animFrame = 0;
        this.animTimer = ANIM_DELAY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Apply ObjectMove: position (16.16) += velocity (8.8) << 8
        // ROM: s2.asm ObjectMove
        xFixed += xVel << 8;
        yFixed += yVel << 8;

        // Apply gravity
        // ROM: addi.w #$18,y_vel(a0)
        yVel += GRAVITY;

        // Update integer position
        currentX = xFixed >> 16;
        currentY = yFixed >> 16;

        // Off-screen check: destroy if below camera + screen height
        // ROM: move.w (Camera_Max_Y_pos).w,d0 / addi.w #screen_height,d0
        if (!isOnScreen(128)) {
            setDestroyed(true);
            return;
        }

        // Y-flip based on velocity direction
        // ROM: bclr #render_flags.y_flip / tst.w y_vel / bmi.s BranchTo_JmpTo8_MarkObjGone
        //      bset #render_flags.y_flip / bsr.w ObjCheckFloorDist
        // Floor check ONLY runs when yVel >= 0 (falling). Rising projectiles skip it entirely.
        vFlip = yVel >= 0;

        if (yVel >= 0) {
            // Floor collision check (only when falling)
            // ROM: bsr.w ObjCheckFloorDist / tst.w d1 / bpl.s ...
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
            if (floor.hasCollision() && floor.distance() < 0) {
                // Hit the floor - snap and transform to ground fire
                // ROM: add.w d1,y_pos(a0)
                currentY += floor.distance();
                yFixed = currentY << 16;
                transformToGroundFire();
                return;
            }
        }

        // Update animation: toggle frames 0-1
        // ROM: move.b #7,anim_frame_duration / addq.b #1,mapping_frame / andi.b #1,mapping_frame
        animTimer--;
        if (animTimer < 0) {
            animTimer = ANIM_DELAY;
            animFrame = (animFrame + 1) & 1;
        }
    }

    /**
     * Transform into ground fire on floor contact.
     * ROM: s2.asm:48563-48573
     */
    private void transformToGroundFire() {
        // Determine spread direction from xVel sign
        int spreadDir = (xVel >= 0) ? 1 : -1;

        // Spawn ground fire with 3 spread clones allowed
        // ROM: move.b #3,objoff_36(a0) / move.w #9,objoff_32(a0)
        HtzGroundFireObjectInstance fire = new HtzGroundFireObjectInstance(
                currentX, currentY, spreadDir, 3);
        spawnDynamicObject(fire);

        setDestroyed(true);
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
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.standardEnemy();
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

        // Projectile uses LAVA_BUBBLE sheet (same as fire source: ArtNem_HtzFireball2)
        // ROM: shares mappings(a0) with parent
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.LAVA_BUBBLE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
