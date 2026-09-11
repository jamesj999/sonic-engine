package com.openggf.game.sonic1.objects.badniks;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractProjectileInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateDefaultArgsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Crabmeat Projectile (energy ball) - Fired by Crabmeat in opposite directions.
 * Uses shared object ID 0x1F with routine 6/8 in the disassembly.
 * <p>
 * Based on docs/s1disasm/_incObj/1F Crabmeat.asm (Crab_BallMain / Crab_BallMove).
 * <p>
 * Behavior:
 * <ul>
 *   <li>Launched with horizontal velocity (+/-$100) and upward velocity (-$400)</li>
 *   <li>Gravity applied each frame via ObjectFall ($38 subpixels/frame²)</li>
 *   <li>Alternates between ball animation frames 5 and 6 at speed 1</li>
 *   <li>Collision type $87: HURT category + size index 7</li>
 *   <li>Deleted when falling below level bottom boundary + $E0</li>
 * </ul>
 */
public class Sonic1CrabmeatProjectileInstance extends AbstractProjectileInstance
        implements SpawnCoordinateDefaultArgsRewindRecreatable {

    // Standard Mega Drive gravity: $38 subpixels/frame² (ObjectFall)
    private static final int GRAVITY = 0x38;

    // Collision: obColType = $87 -> category HURT ($80), size index 7
    private static final int COLLISION_SIZE_INDEX = 0x07;

    // Animation: speed 1 = each frame shows for 2 game frames
    private static final int ANIM_SPEED = 2;

    // Mapping frames for projectile: ball1=5, ball2=6
    private static final int BALL_FRAME_1 = 5;
    private static final int BALL_FRAME_2 = 6;

    // Level bottom margin for deletion: $E0 (224 pixels)
    private static final int BOTTOM_MARGIN = 0xE0;

    private int animTimer;
    private int renderedFrame;
    private int launchYVelocity;
    private boolean initialized;

    /**
     * Creates a Crabmeat projectile.
     *
     * @param x    Starting X position
     * @param y    Starting Y position
     * @param xVel X velocity in subpixels (+/-$100)
     * @param yVel Initial Y velocity in subpixels (-$400, upward)
     * @param parent Reference to parent Crabmeat (unused, kept for spawn-site compatibility)
     * @param levelManager Level manager reference (unused, kept for spawn-site compatibility)
     */
    public Sonic1CrabmeatProjectileInstance(int x, int y, int xVel, int yVel,
            Sonic1CrabmeatBadnikInstance parent) {
        super(new ObjectSpawn(x, y, 0x1F, 0, 0, false, 0), "CrabmeatBall",
                xVel, 0, GRAVITY, COLLISION_SIZE_INDEX, BOTTOM_MARGIN);
        this.launchYVelocity = yVel;
        this.animTimer = 0;
        this.renderedFrame = BALL_FRAME_1;
        this.touchCollisionActive = false;
        this.deferSameFrameUpdateAfterSpawn = true;
        this.initialized = false;
    }

    private Sonic1CrabmeatProjectileInstance(int x, int y, int xVel, int yVel) {
        this(x, y, xVel, yVel, null);
    }

    /**
     * S1 ObjectFall: move position with the OLD velocity (X first, no gravity;
     * then Y), and only AFTER the Y move add gravity to {@code obVelY} for the
     * next frame. This is move-with-old-velocity-then-gravity, NOT
     * gravity-then-move.
     *
     * <p>The previous implementation did {@code obVelY += gravity} BEFORE the
     * move (integrating with the already-incremented velocity), so each frame
     * the ball moved one $38 gravity-step less upward / more downward than the
     * ROM. Over the projectile's arc that compounds into ~2px of Y drift, which
     * pushed the engine ball into Sonic's ReactToItem reaction box and triggered
     * a spurious hurt the ROM never produces (GHZ1 trace f1390).
     *
     * <p>ROM ObjectFall (docs/s1disasm/_incObj/sub ObjectFall.asm:5-19):
     * <pre>
     *     move.w  obVelX(a0),d0           ; OLD x velocity
     *     ext.l d0 / asl.l #8,d0 / add.l d0,obX   ; X += old vel (16.16)
     *     move.w  obVelY(a0),d0           ; OLD y velocity
     *     addi.w  #$38,obVelY(a0)         ; gravity AFTER reading old velocity
     *     ext.l d0 / asl.l #8,d0 / add.l d0,obY   ; Y += old vel (16.16)
     * </pre>
     * {@link SubpixelMotion#objectFallXY} implements exactly this ordering.
     * Ball arc set up by Crab_BallMain/Crab_BallMove
     * (docs/s1disasm/_incObj/1F Crabmeat.asm:187-219).
     */
    @Override
    protected void updateMotion() {
        if (!initialized) {
            // Crab_BallMain sets obColType=$87 and obVelY=-$400 when routine 6
            // first executes; Crab_Move.fire only seeds obID/routine/x/y/x_vel
            // (docs/s1disasm/_incObj/1F Crabmeat.asm:80-100,187-201).
            motionState.yVel = launchYVelocity;
            initialized = true;
        } else {
            // ReactToItem gates on obRender bit 7, which only reflects a prior
            // DisplaySprite/BuildSprites pass, not the same Crab_BallMain tick
            // that first stores obColType=$87.
            touchCollisionActive = true;
        }
        SubpixelMotion.objectFallXY(motionState, gravity);
    }

    @Override
    protected void updateExtra(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Animate: alternate between ball frame 1 and 2 at speed 1
        animTimer++;
        if (animTimer >= ANIM_SPEED) {
            animTimer = 0;
            renderedFrame = (renderedFrame == BALL_FRAME_1) ? BALL_FRAME_2 : BALL_FRAME_1;
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3); // obPriority = 3
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.CRABMEAT);
        if (renderer == null) return;

        renderer.drawFrameIndex(renderedFrame, currentX, currentY, false, false);
    }
}
