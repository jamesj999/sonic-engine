package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroPairRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Bomb explosion fragment from the AIZ2 battleship bombing sequence.
 *
 * <p>ROM: Obj_AIZBombExplosion (sonic3k.asm:105466).
 * 8 fragments spawned per bomb impact, each with staggered delay, position offset,
 * and one of 2 animation variants. Collision flags 0x8B active during early frames.
 *
 * <p>Unlike the falling bomb, the explosion fragments are ordinary world-space
 * sprites after they spawn. ROM parity here is the per-wrap X correction via
 * {@code Level_repeat_offset}.
 */
public class AizBombExplosionInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnCoordinateZeroPairRewindRecreatable {

    private static final int COLLISION_FLAGS = 0x8B;
    private static final int[][][] ANIM_SCRIPTS = {
            {{1, 3}, {2, 4}, {3, 5}, {4, 5}, {5, 5}},
            {{6, 2}, {7, 3}, {8, 4}, {9, 5}, {10, 5}, {11, 5}},
    };

    /** World-space X position. */
    private int posX;
    private int posY;
    // animIndex/initialDelay are non-final so the rewind field capturer reapplies
    // them after spawn-coordinate recreate uses placeholders 0.
    private int animIndex;
    private int initialDelay;

    /** ROM {@code $2E(a0)} while the fragment is still Obj_AIZBombExplosion. */
    private int delayTimer;
    /** ROM {@code anim_frame}: index of the NEXT script entry to load. */
    private int animFrame;
    /** ROM {@code anim_frame_timer}. */
    private int frameTimer;
    /** ROM {@code mapping_frame}. */
    private int mappingFrame;
    private boolean active;

    /**
     * @param x          world X position
     * @param y          world Y position
     * @param animIndex  animation variant (0 or 1)
     * @param delay      frames to wait before becoming visible/active
     */
    public AizBombExplosionInstance(int x, int y, int animIndex, int delay) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "AIZBombExplosion");
        this.posX = x;
        this.posY = y;
        this.animIndex = Math.min(animIndex, ANIM_SCRIPTS.length - 1);
        this.initialDelay = delay;
        this.delayTimer = delay;
        this.animFrame = 0;
        this.frameTimer = 0;
        this.mappingFrame = ANIM_SCRIPTS[this.animIndex][0][0];
        this.active = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        if (!active) {
            // ROM Obj_AIZBombExplosion (sonic3k.asm:105471): subq.w #1,$2E(a0) /
            // bmi.s loc_505B4 / rts. The wait therefore lasts delay+1 frames --
            // it ends on the frame the counter goes negative, not the frame it
            // reaches zero. loc_505B4 then falls through via `bra.s loc_505E4`,
            // so the fragment animates on the very frame it becomes active.
            delayTimer--;
            if (delayTimer >= 0) {
                return;
            }
            active = true;
        }

        // ROM loc_505E4 -> Animate_SpriteIrregularDelay (sonic3k.asm:36238):
        // `subq.b #1,anim_frame_timer(a0) / bcc.s locret`. The branch is taken
        // while the subtraction did not borrow, so a script entry whose delay
        // byte is D is held for D+1 frames, and the timer of 0 that a freshly
        // transitioned fragment carries advances on its first call.
        frameTimer--;
        if (frameTimer < 0) {
            int[][] script = ANIM_SCRIPTS[animIndex];
            if (animFrame >= script.length) {
                // Script terminator $FC: Animate_SpriteIrregularDelay's loc_1AD0C
                // adds 2 to routine, and loc_505E4's `tst.b routine(a0) / bne`
                // jumps to Delete_Current_Sprite on that same frame -- so the
                // terminator frame is never drawn and never collidable.
                setDestroyed(true);
                return;
            }
            mappingFrame = script[animFrame][0];
            frameTimer = script[animFrame][1];
            animFrame++;
        }
    }

    @Override
    public int getCollisionFlags() {
        if (!active) {
            return 0;
        }
        // ROM loc_505FC: cmp.b mapping_frame,d0 / bls.s skip collision, so
        // equality with (4 + anim) is already non-collidable.
        return currentMappingFrame() < (4 + animIndex) ? COLLISION_FLAGS : 0;
    }

    @Override
    public int getCollisionProperty() { return 0; }

    @Override
    public int getX() { return posX; }

    @Override
    public int getY() { return posY; }

    @Override
    public String traceDebugDetails() {
        return String.format("anim=%d delay=%d frame=%d timer=%d active=%s map=%02X",
                animIndex,
                delayTimer,
                animFrame,
                frameTimer,
                active,
                currentMappingFrame());
    }

    /** ROM: subtract Level_repeat_offset on wrap frames. */
    public void applyWrapOffset(int offset) {
        posX -= offset;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || !active) return;

        ObjectRenderManager rm = services().renderManager();
        if (rm == null) return;

        PatternSpriteRenderer renderer = rm.getRenderer(Sonic3kObjectArtKeys.AIZ2_BOMB_EXPLODE);
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(currentMappingFrame(), getX(), posY, false, false);
    }

    @Override
    public boolean isHighPriority() { return false; }

    @Override
    public int getPriorityBucket() { return 1; }

    private int currentMappingFrame() {
        return mappingFrame;
    }
}
