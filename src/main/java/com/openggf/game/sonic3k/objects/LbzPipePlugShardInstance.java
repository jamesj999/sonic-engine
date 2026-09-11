package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnDefaultArgsRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Falling visual shard spawned by {@link LbzPipePlugObjectInstance}.
 *
 * <p>ROM reference: {@code loc_275B2} and the falling children allocated by
 * {@code PipePlugSmashObject} ({@code sonic3k.asm:53684-53779}).
 */
final class LbzPipePlugShardInstance extends AbstractObjectInstance
        implements SpawnDefaultArgsRewindRecreatable {
    private static final int FALL_GRAVITY = 0x18;
    private static final int PRIORITY_BUCKET = 4;
    private static final int ON_SCREEN_HALF_SIZE = 0x20;

    private final SubpixelMotion.State motion;
    private boolean animatedSmallShard;
    private int mappingFrame;
    private int framePieceIndex;
    private int animFrame;

    LbzPipePlugShardInstance(ObjectSpawn spawn, int mappingFrame, int framePieceIndex,
            int xVel, int yVel, boolean animatedSmallShard) {
        super(spawn, "LBZPipePlugShard");
        this.mappingFrame = mappingFrame;
        this.framePieceIndex = framePieceIndex;
        this.animFrame = mappingFrame;
        this.animatedSmallShard = animatedSmallShard;
        this.motion = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, xVel, yVel);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (animatedSmallShard) {
            mappingFrame = animFrame;
            animFrame++;
            if (animFrame >= 6) {
                animFrame = 0;
            }
        }
        SubpixelMotion.moveSprite2(motion);
        motion.yVel += FALL_GRAVITY;
        updateDynamicSpawn(motion.x, motion.y);
        if (!isOnScreen(0x80)) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return ON_SCREEN_HALF_SIZE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_PIPE_PLUG);
        if (renderer != null) {
            if (framePieceIndex >= 0) {
                renderer.drawFramePieceByIndex(mappingFrame, framePieceIndex, getX(), getY(), false, false);
            } else {
                renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false, 2);
            }
        }
    }
}
