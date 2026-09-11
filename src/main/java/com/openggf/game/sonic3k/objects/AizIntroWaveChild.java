package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Wave splash sprite for AIZ1 intro.
 * ROM: loc_678A0 (sonic3k.asm)
 *
 * Uses Map_AIZIntroWaves / ArtTile_AIZIntroSprites.
 * Spawned every 5 frames during monitoring routines (0x06-0x0B) of the
 * intro orchestrator. Init sets position, priority 0x100, width 0x10.
 * Each frame scrolls left by the parent's scroll speed and animates.
 * Self-deletes when X < 0x60.
 */
public class AizIntroWaveChild extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizIntroWaveChild.class.getName());

    /** X threshold below which the wave self-deletes. */
    static final int DELETE_X = 0x60;

    /**
     * ROM animation data from byte_67A9B (sonic3k.asm:136013).
     * Pairs of (mapping_frame, delay). Delay value N means N+1 frames.
     * First pair (0,1) is skipped on first call (anim_frame starts at 0, incremented to 2).
     * Sentinel -1 means delete sprite.
     */
    private static final int[] ANIM_DATA = {
        0, 1,   // offset 0: skipped
        0, 0,   // offset 2: frame 0, 1 tick
        1, 1,   // offset 4: frame 1, 2 ticks
        2, 2,   // offset 6: frame 2, 3 ticks
        3, 2,   // offset 8: frame 3, 3 ticks
        4, 1,   // offset 10: frame 4, 2 ticks
        5, 1,   // offset 12: frame 5, 2 ticks
        -1       // sentinel: delete
    };
    private final AizPlaneIntroInstance parent;
    private int currentX;
    private int currentY;
    /** Index into ANIM_DATA (advances by 2 per animation step). */
    private int animIndex;
    private int animTimer;
    /** Current mapping frame to render. */
    private int mappingFrame;
    /** ROM callback has replaced the routine with Go_Delete_Sprite. */
    private boolean deleteRoutinePending;

    /**
     * @param spawn  spawn position for the wave
     * @param parent parent intro object (provides live scroll speed via $40(a0))
     */
    public AizIntroWaveChild(ObjectSpawn spawn, AizPlaneIntroInstance parent) {
        super(spawn, "AIZIntroWave");
        this.parent = parent;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizPlaneIntroInstance liveParent = AizIntroRewindLinks.liveIntroParent(ctx);
        return liveParent == null ? null : new AizIntroWaveChild(ctx.spawn(), liveParent);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: loc_678A0 writes priority(a0) = $100.
        return 2;
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
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        if (deleteRoutinePending) {
            setDestroyed(true);
            return;
        }

        // ROM order (loc_678DA): check delete threshold, subtract scroll, animate, draw.
        // Check x < DELETE_X before scroll subtraction to match ROM
        if (currentX < DELETE_X) {
            setDestroyed(true);
            return;
        }

        // ROM: subtract parent.$40 each frame (speed changes from 8 -> 12 -> 16).
        currentX -= parent.getScrollSpeed();

        // Animate_RawMultiDelay: decrement timer, advance on expiry
        if (--animTimer < 0) {
            // Advance to next animation entry
            animIndex += 2;
            if (animIndex >= ANIM_DATA.length || ANIM_DATA[animIndex] == -1) {
                // $F4 invokes the $34 callback, which writes Go_Delete_Sprite
                // into the object routine. The slot remains occupied by that
                // routine until its next object dispatch.
                deleteRoutinePending = true;
                return;
            }
            mappingFrame = ANIM_DATA[animIndex];
            animTimer = ANIM_DATA[animIndex + 1];
        }
    }

    public int getAnimFrame() {
        return mappingFrame;
    }

    boolean isDeleteRoutinePending() {
        return deleteRoutinePending;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = AizIntroArtLoader.getIntroSpritesRenderer(services());
        if (renderer == null || !renderer.isReady()) return;
        // Screen-space coordinates use the ROM +128 sprite-table bias.
        int renderX = currentX;
        int renderY = currentY;
        try {
            Camera camera = services().camera();
            renderX += camera.getX() - 128;
            renderY += camera.getY() - 128;
        } catch (Exception e) {
            LOG.fine(() -> "AizIntroWaveChild.appendRenderCommands: " + e.getMessage());
        }
        renderer.drawFrameIndex(mappingFrame, renderX, renderY, true, false);
    }
}
