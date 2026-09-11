package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.physics.Direction;
import com.openggf.game.PlayableEntity;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.List;

/**
 * Water splash effect object (Object $08, routine 2).
 * Created when Sonic enters or exits water.
 * Uses splash frames from the shared dust/splash art data.
 * 
 * In the original game, the splash object is spawned at the water surface
 * at the player's X position. It plays through its animation frames and
 * then destroys itself.
 * 
 * Splash animation frames (from Sonic 2 disassembly obj08.asm):
 * - Frames 0-9 are splash frames (Ani_obj08: byte_12CA6)
 * - Frame duration is 3 ticks per frame
 */
public class SplashObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    // Splash animation frames from obj08.asm Ani_obj08 (splash water)
    // byte_12CA6: dc.b 2, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, $FC
    // Frame duration is 2 (meaning 3 game ticks per frame)
    private static final int[] SPLASH_FRAMES = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
    private static final int FRAME_DELAY = 2; // 3 game ticks per frame (original uses delay value of 2)

    private final PlayerSpriteRenderer renderer;
    private int animTimer;
    private int frameIndex;
    private boolean facingLeft;

    /**
     * Creates a splash object at the specified position.
     * 
     * @param x          World X coordinate (player's X position)
     * @param y          World Y coordinate (water surface level)
     * @param renderer   The dust/splash art renderer
     * @param facingLeft Whether the player was facing left when entering water
     */
    public SplashObjectInstance(int x, int y, PlayerSpriteRenderer renderer, boolean facingLeft) {
        this(new ObjectSpawn(x, y, 0x08, 0, facingLeft ? 1 : 0, false, 0), renderer);
    }

    public SplashObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    public SplashObjectInstance(ObjectSpawn spawn, PlayerSpriteRenderer renderer) {
        super(spawn, "Splash");
        this.renderer = renderer;
        this.animTimer = FRAME_DELAY;
        this.frameIndex = 0;
        this.facingLeft = (spawn.renderFlags() & 1) != 0;
    }

    @Override
    public SplashObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new SplashObjectInstance(ctx.spawn(), currentSplashRenderer(ctx));
    }

    private static PlayerSpriteRenderer currentSplashRenderer(RewindRecreateContext ctx) {
        if (ctx.objectServices() == null || ctx.objectServices().camera() == null) {
            return null;
        }
        if (!(ctx.objectServices().camera().getFocusedSprite() instanceof AbstractPlayableSprite sprite)) {
            return null;
        }
        var dust = sprite.getSpindashDustController();
        return dust == null ? null : dust.getRenderer();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // Decrement animation timer
        animTimer--;
        if (animTimer < 0) {
            animTimer = FRAME_DELAY;
            frameIndex++;

            // Check if animation is complete
            if (frameIndex >= SPLASH_FRAMES.length) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || renderer == null) {
            return;
        }

        if (frameIndex >= 0 && frameIndex < SPLASH_FRAMES.length) {
            int mappingFrame = SPLASH_FRAMES[frameIndex];
            renderer.drawFrame(mappingFrame, spawn.x(), spawn.y(), facingLeft, false);
        }
    }

    /**
     * Creates a splash object for when the player enters or exits water.
     * The splash is spawned at the water surface level at the player's X position.
     * 
     * @param player The player sprite
     * @param waterY The water surface Y coordinate
     * @return A new splash object, or null if renderer is not available
     */
    public static SplashObjectInstance create(PlayableEntity player, int waterY) {
        var levelManager = staticLevelManager();
        if (levelManager == null) {
            return null;
        }

        // Get the dust/splash renderer from the player's dust manager
        // Escape hatch: getSpindashDustController() returns sprites.managers type,
        // not exposed on PlayableEntity to avoid game -> sprites.managers dependency.
        if (!(player instanceof AbstractPlayableSprite aps)) {
            return null;
        }
        var dustManager = aps.getSpindashDustController();
        if (dustManager == null) {
            return null;
        }

        // The dust manager shares the same art as splash
        // We need to access its renderer - for now we'll create one from the player
        PlayerSpriteRenderer renderer = dustManager.getRenderer();
        if (renderer == null) {
            return null;
        }

        // Splash is centered at player's X, at water surface Y
        int splashX = player.getCentreX();
        int splashY = waterY;
        boolean facingLeft = player.getDirection() == Direction.LEFT;

        return new SplashObjectInstance(splashX, splashY, renderer, facingLeft);
    }
}
