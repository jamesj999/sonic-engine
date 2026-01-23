package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.render.PlayerSpriteRenderer;

import java.util.List;

/**
 * Skid dust effect object (Object $08, routine 6 / animation 3).
 * Created when Sonic starts skidding (braking at speed >= 0x400).
 * Uses skid frames from the shared dust/splash art data.
 *
 * In the original game, the skid dust object is spawned at the player's feet
 * when skidding begins. It plays through its animation frames and then
 * destroys itself. Unlike spindash dust, it does NOT follow the player.
 *
 * Skid animation frames (from Sonic 2 disassembly obj08.asm):
 * Obj08Ani_Skid: dc.b 3,$11,$12,$13,$14,$FC
 * - Frame delay: 3 (4 game ticks per frame)
 * - Frames: 0x11, 0x12, 0x13, 0x14
 * - End action: $FC (routine increment - animation ends)
 */
public class SkidDustObjectInstance extends AbstractObjectInstance {
    // Skid animation frames from obj08.asm Obj08Ani_Skid
    private static final int[] SKID_FRAMES = { 0x11, 0x12, 0x13, 0x14 };
    private static final int FRAME_DELAY = 3; // 4 game ticks per frame

    // Frame 0x15 (21) has the DPLC that loads skid dust tiles.
    // Frames 0x11-0x14 have empty DPLCs and reuse tiles from frame 0x15.
    private static final int PRELOAD_DPLC_FRAME = 0x15;

    private final PlayerSpriteRenderer renderer;
    private int animTimer;
    private int frameIndex;
    private final boolean facingLeft;
    private boolean dplcPreloaded = false;

    /**
     * Creates a skid dust object at the specified position.
     *
     * @param x          World X coordinate (player's X position)
     * @param y          World Y coordinate (player's feet Y position)
     * @param renderer   The dust/splash art renderer
     * @param facingLeft Whether the player was facing left when skidding started
     */
    public SkidDustObjectInstance(int x, int y, PlayerSpriteRenderer renderer, boolean facingLeft) {
        super(new ObjectSpawn(x, y, 0x08, 0, 0, false, 0), "SkidDust");
        this.renderer = renderer;
        this.animTimer = FRAME_DELAY;
        this.frameIndex = 0;
        this.facingLeft = facingLeft;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Decrement animation timer
        animTimer--;
        if (animTimer < 0) {
            animTimer = FRAME_DELAY;
            frameIndex++;

            // Check if animation is complete
            if (frameIndex >= SKID_FRAMES.length) {
                setDestroyed(true);
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || renderer == null) {
            return;
        }

        // ROM: Frames 0x11-0x14 have empty DPLCs and reuse tiles loaded by frame 0x15.
        // We must "draw" frame 0x15 first to trigger its DPLC load (it has no mapping
        // pieces, so nothing visible is drawn), then the animation frames will work.
        if (!dplcPreloaded) {
            renderer.drawFrame(PRELOAD_DPLC_FRAME, spawn.x(), spawn.y(), facingLeft, false);
            dplcPreloaded = true;
        }

        if (frameIndex >= 0 && frameIndex < SKID_FRAMES.length) {
            int mappingFrame = SKID_FRAMES[frameIndex];
            renderer.drawFrame(mappingFrame, spawn.x(), spawn.y(), facingLeft, false);
        }
    }

    /**
     * Creates a skid dust object for when the player starts skidding.
     * The dust is spawned at the player's feet position.
     *
     * @param player The player sprite
     * @return A new skid dust object, or null if renderer is not available
     */
    public static SkidDustObjectInstance create(AbstractPlayableSprite player) {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return null;
        }

        // Get the dust/splash renderer from the player's dust manager
        var dustManager = player.getSpindashDustController();
        if (dustManager == null) {
            return null;
        }

        PlayerSpriteRenderer renderer = dustManager.getRenderer();
        if (renderer == null) {
            return null;
        }

        // ROM: Skid dust position is player center + 0x10 to Y
        // See s2.asm Obj08_SkidDust: move.w y_pos(a2),y_pos(a1) / addi.w #$10,y_pos(a1)
        int dustX = player.getCentreX();
        int dustY = player.getCentreY() + 16;
        boolean facingLeft = player.getDirection() == uk.co.jamesj999.sonic.physics.Direction.LEFT;

        return new SkidDustObjectInstance(dustX, dustY, renderer, facingLeft);
    }

    /**
     * Spawns a skid dust object at the player's position.
     * Convenience method that creates and adds the dust to the object manager.
     *
     * @param player The player sprite that started skidding
     */
    public static void spawn(AbstractPlayableSprite player) {
        SkidDustObjectInstance dust = create(player);
        if (dust != null) {
            LevelManager.getInstance().getObjectManager().addDynamicObject(dust);
        }
    }
}
