package com.openggf.game.sonic1.objects.badniks;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractProjectileInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Newtron Missile - Projectile fired by Type 1 (green) Newtron.
 * <p>
 * Reuses the Buzz Bomber missile object (id_Missile = 0x23) with obSubtype=1,
 * which routes to Msl_FromNewt (routine 8) in the disassembly.
 * <p>
 * Based on docs/s1disasm/_incObj/23 Buzz Bomber Missile.asm, Msl_FromNewt routine.
 * <p>
 * Unlike Buzz Bomber missiles, Newtron missiles:
 * <ul>
 *   <li>Skip the flare countdown/animation phase entirely</li>
 *   <li>Enable collision (obColType=$87) immediately</li>
 *   <li>Use animation 1 (active ball) from the start</li>
 *   <li>Delete based on render bit (off-screen check) rather than collision flag</li>
 *   <li>Have no Y velocity (fly horizontally only)</li>
 * </ul>
 */
public class Sonic1NewtronMissileInstance extends AbstractProjectileInstance
        implements SpawnCoordinateZeroScalarArgsRewindRecreatable {

    // Collision: obColType = $87 -> category HURT ($80), size index 7
    private static final int COLLISION_SIZE_INDEX = 0x07;

    // Active missile animation: speed=1 means each frame shows for 2 game frames
    // From Ani_Missile animation 1: dc.b 1, 2, 3, afEnd
    private static final int ACTIVE_ANIM_SPEED = 2;

    private boolean facingLeft;
    private int animTimer;
    private int animFrame;

    /**
     * Creates a missile spawned by a Type 1 Newtron.
     *
     * @param x         Starting X position (Newtron X + offset)
     * @param y         Starting Y position (Newtron Y - 8)
     * @param xVel      X velocity in subpixels ($200 or -$200)
     * @param facingLeft Direction the parent Newtron was facing
     */
    public Sonic1NewtronMissileInstance(int x, int y, int xVel, boolean facingLeft) {
        super(new ObjectSpawn(x, y, 0x23, 1, 0, false, 0), "NewtronMissile",
                xVel, 0, 0, COLLISION_SIZE_INDEX, 32);
        this.facingLeft = facingLeft;
        this.animTimer = 0;
        this.animFrame = 0;
    }

    /**
     * Newtron missiles move horizontally only (no Y movement).
     */
    @Override
    protected void updateMotion() {
        SubpixelMotion.moveX(motionState);
    }

    @Override
    protected void updateExtra(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Animate: alternate frames 2-3 (Ball1/Ball2) at speed 1
        animTimer++;
        if (animTimer >= ACTIVE_ANIM_SPEED) {
            animTimer = 0;
            animFrame = (animFrame == 0) ? 1 : 0;
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3); // obPriority = 3
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        // Newtron missile reuses Buzz Bomber missile art with animation 1 (active ball)
        PatternSpriteRenderer renderer = renderManager.getRenderer(ObjectArtKeys.BUZZ_BOMBER_MISSILE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Active missile frames: 2 + animFrame (Ball1=2, Ball2=3 in missile sprite sheet)
        int renderedFrame = 2 + animFrame;
        renderer.drawFrameIndex(renderedFrame, currentX, currentY, !facingLeft, false);
    }
}
