package com.openggf.level.objects;

import com.openggf.graphics.GLCommand;
import com.openggf.physics.Direction;
import com.openggf.game.PlayableEntity;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Tails;
import com.openggf.sprites.render.PlayerSpriteRenderer;

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
public class SkidDustObjectInstance extends AbstractObjectInstance implements SpawnServicesRewindRecreatable {
    // Skid animation frames from obj08.asm Obj08Ani_Skid
    private static final int[] SKID_FRAMES = { 0x11, 0x12, 0x13, 0x14 };
    private static final int FRAME_DELAY = 3; // 4 game ticks per frame
    private static final int DELETE_ROUTINE_DELAY = 1;

    // Frame 0x15 (21) has the DPLC that loads skid dust tiles.
    // Frames 0x11-0x14 have empty DPLCs and reuse tiles from frame 0x15.
    private static final int PRELOAD_DPLC_FRAME = 0x15;

    private PlayerSpriteRenderer renderer;
    private int animTimer;
    private int frameIndex;
    private int deleteRoutineDelay = -1;
    private boolean facingLeft;
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
        super(new ObjectSpawn(x, y, 0x08, 0, facingLeft ? 1 : 0, false, 0), "SkidDust");
        this.renderer = renderer;
        this.animTimer = FRAME_DELAY;
        this.frameIndex = 0;
        this.facingLeft = facingLeft;
    }

    public SkidDustObjectInstance(ObjectSpawn spawn, ObjectServices services) {
        this(spawn.x(), spawn.y(), null, (spawn.renderFlags() & 1) != 0);
    }

    private static PlayerSpriteRenderer findRewindRenderer(ObjectServices services) {
        PlayerSpriteRenderer renderer = null;
        if (services != null && services.spriteManager() != null) {
            for (Sprite sprite : services.spriteManager().getAllSprites()) {
                if (sprite instanceof AbstractPlayableSprite playable
                        && playable.getSpindashDustController() != null) {
                    renderer = playable.getSpindashDustController().getRenderer();
                    if (renderer != null && !playable.isCpuControlled()) {
                        break;
                    }
                }
            }
        }
        return renderer;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (deleteRoutineDelay >= 0) {
            if (deleteRoutineDelay-- == 0) {
                ObjectLifetimeOps.expireDynamic(this);
            }
            return;
        }

        // Decrement animation timer
        animTimer--;
        if (animTimer < 0) {
            animTimer = FRAME_DELAY;
            frameIndex++;

            // ROM animation command $FC increments Obj08 to routine 4; the
            // object remains allocated until that delete routine is reached on
            // a later RunObjects pass (docs/s2disasm/s2.asm:42660-42664,
            // 42846-42847).
            if (frameIndex >= SKID_FRAMES.length) {
                deleteRoutineDelay = DELETE_ROUTINE_DELAY;
            }
        }
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        // ROM Obj08_SkidDust tails through loc_1DEE0 ->
        // Obj08_LoadDustOrSplashArt -> rts, and the allocated puff itself runs
        // Obj08_Main -> Obj08_Display -> DisplaySprite
        // (docs/s2disasm/s2.asm:42796-42800, 42821-42851). Neither path calls
        // MarkObjGone, so the puff's lifetime is owned entirely by its
        // animation counter ($FC -> routine 4 -> DeleteObject); it must survive
        // the camera scrolling away from where it was dropped. The S3K dash
        // dust draws the same way (docs/skdisasm/sonic3k.asm:34023, 34068).
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    @Override
    public PerObjectRewindSnapshot captureRewindState() {
        return super.captureRewindState().withObjectSubclassExtra(
                new SkidDustRewindExtra(animTimer, frameIndex, deleteRoutineDelay, dplcPreloaded));
    }

    @Override
    public void restoreRewindState(PerObjectRewindSnapshot snapshot) {
        super.restoreRewindState(snapshot);
        if (snapshot.objectSubclassExtra() instanceof SkidDustRewindExtra extra) {
            animTimer = extra.animTimer();
            frameIndex = extra.frameIndex();
            deleteRoutineDelay = extra.deleteRoutineDelay();
            dplcPreloaded = extra.dplcPreloaded();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PlayerSpriteRenderer activeRenderer = resolveRenderer();
        if (isDestroyed() || activeRenderer == null) {
            return;
        }

        // ROM: Frames 0x11-0x14 have empty DPLCs and reuse tiles loaded by frame 0x15.
        // We must "draw" frame 0x15 first to trigger its DPLC load (it has no mapping
        // pieces, so nothing visible is drawn), then the animation frames will work.
        if (!dplcPreloaded) {
            activeRenderer.drawFrame(PRELOAD_DPLC_FRAME, spawn.x(), spawn.y(), facingLeft, false);
            dplcPreloaded = true;
        }

        if (frameIndex >= 0 && frameIndex < SKID_FRAMES.length) {
            int mappingFrame = SKID_FRAMES[frameIndex];
            activeRenderer.drawFrame(mappingFrame, spawn.x(), spawn.y(), facingLeft, false);
        }
    }

    private PlayerSpriteRenderer resolveRenderer() {
        if (renderer == null) {
            renderer = findRewindRenderer(tryServices());
        }
        return renderer;
    }

    /**
     * Creates a skid dust object for when the player starts skidding.
     * The dust is spawned at the player's feet position.
     *
     * @param player The player sprite
     * @return A new skid dust object, or null if renderer is not available
     */
    public static SkidDustObjectInstance create(PlayableEntity player) {
        var objectManager = staticObjectManager();
        var levelManager = staticLevelManager();
        if (levelManager == null || objectManager == null) {
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

        PlayerSpriteRenderer renderer = dustManager.getRenderer();
        if (renderer == null) {
            return null;
        }

        // ROM: Skid dust position is player center + 0x10 to Y
        // See s2.asm Obj08_SkidDust: move.w y_pos(a2),y_pos(a1) / addi.w #$10,y_pos(a1).
        // Tails then subtracts 4 because his sprite is shorter.
        int dustX = player.getCentreX();
        int dustY = player.getCentreY() + 16;
        if (player instanceof Tails) {
            dustY -= 4;
        }
        boolean facingLeft = player.getDirection() == Direction.LEFT;

        return new SkidDustObjectInstance(dustX, dustY, renderer, facingLeft);
    }

    /**
     * Spawns a skid dust object at the player's position.
     * Convenience method that creates and adds the dust to the object manager.
     *
     * @param player The player sprite that started skidding
     */
    public static void spawn(PlayableEntity player) {
        SkidDustObjectInstance dust = create(player);
        if (dust != null) {
            ObjectManager objectManager = staticObjectManager();
            if (objectManager != null) {
                objectManager.addDynamicObject(dust);
            }
        }
    }

    private record SkidDustRewindExtra(
            int animTimer,
            int frameIndex,
            int deleteRoutineDelay,
            boolean dplcPreloaded
    ) implements PerObjectRewindSnapshot.ObjectSubclassRewindExtra {
    }
}
