package com.openggf.game.sonic2.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

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
public class DestroyedEggPrisonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SpawnRewindRecreatable {

    private static final int FRAME_BODY_OPEN_3 = 3; // Fully open capsule frame

    // SolidObject parameters for body (same as EggPrisonObjectInstance)
    private static final int BODY_HALF_WIDTH = 0x2B;  // 43 pixels
    private static final int BODY_HALF_HEIGHT = 0x18; // 24 pixels

    private int positionX;
    private int positionY;

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

    DestroyedEggPrisonObjectInstance(ObjectSpawn spawn) {
        this(spawn, spawn.x(), spawn.y());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No updates - static visual only
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            // Fallback rendering not needed - just don't render if art isn't loaded
            return;
        }

        // Render frame 3 (fully open capsule body)
        // Note: Button is NOT rendered here - EggPrisonButtonObjectInstance persists
        // during results screen to maintain its own render priority (5) and collision
        renderer.drawFrameIndex(FRAME_BODY_OPEN_3, positionX, positionY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4); // Same as capsule body
    }

    @Override
    public int getX() {
        return positionX;
    }

    @Override
    public int getY() {
        return positionY;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // ROM Obj3E allocates each capsule piece (body, button, lock, broken
        // half) into its own SST slot via AllocateObject, and copies only the
        // per-piece load data into it (docs/s2disasm/s2.asm:84832-84865). Each
        // piece therefore owns a separate status(a0) byte, and
        // SolidObject_TestClearPush releases the player's push bit only when
        // the CALLING object's own pushing bit is set -- otherwise it branches
        // straight to SolidObject_NoCollision without touching status(a1)
        // (docs/s2disasm/s2.asm:35462-35466,35483-35490). Keying the engine's
        // push/standing latch on the shared ObjectSpawn instead lets a sibling
        // piece's no-contact pass clear the body's push mark inside the same
        // object pass, so Sonic's next Sonic_Animate never sees Status_Push and
        // publishes a walk mapping frame where ROM publishes SonAni_Push.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Same collision as the original capsule body
        return SolidObjectParams.of(
            BODY_HALF_WIDTH,    // 0x2B = 43 pixels
            BODY_HALF_HEIGHT,   // 0x18 = 24 pixels (air)
            BODY_HALF_HEIGHT    // 0x18 = 24 pixels (ground)
        );
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return true; // Always solid
    }

    @Override
    public String toString() {
        return String.format("DestroyedEggPrison[x=%d, y=%d]", positionX, positionY);
    }
}
