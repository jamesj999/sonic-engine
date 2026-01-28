package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Static destroyed EggPrison visual - mimics ROM orphaned child object behavior.
 *
 * In the ROM (s2.asm), when the parent capsule is deleted via Load_EndOfAct (loc_3F406),
 * the child objects (body visual, button, lock, broken piece) are NOT automatically deleted.
 * The body visual child (routine 2) continues rendering the open capsule animation,
 * which is why the open capsule stays visible during the results screen.
 *
 * This class mimics that behavior: when the main EggPrisonObjectInstance is destroyed,
 * it spawns this static visual object to maintain the open capsule appearance.
 *
 * Lifecycle:
 * - Spawned: When all animals are collected and results screen triggers
 * - Lifetime: Persists until level transition
 * - No updates: Just renders, no logic
 */
public class DestroyedEggPrisonObjectInstance extends AbstractObjectInstance {

    private static final int FRAME_BODY_OPEN_3 = 3; // Fully open capsule frame

    private final int positionX;
    private final int positionY;

    /**
     * Create a static destroyed capsule visual at the given position.
     *
     * @param spawn Original spawn data (used for object ID only)
     * @param x X position (from parent capsule)
     * @param y Y position (from parent capsule)
     */
    public DestroyedEggPrisonObjectInstance(ObjectSpawn spawn, int x, int y) {
        super(spawn, "Destroyed EggPrison");
        this.positionX = x;
        this.positionY = y;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // No updates - static visual only
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            // Fallback rendering not needed - just don't render if art isn't loaded
            return;
        }

        // Render frame 3 (fully open capsule)
        renderer.drawFrameIndex(FRAME_BODY_OPEN_3, positionX, positionY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // Same as capsule body
    }

    @Override
    public String toString() {
        return String.format("DestroyedEggPrison[x=%d, y=%d]", positionX, positionY);
    }
}
