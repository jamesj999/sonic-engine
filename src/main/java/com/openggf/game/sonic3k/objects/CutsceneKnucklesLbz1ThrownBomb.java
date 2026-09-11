package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Thrown bomb child for the LBZ1 rival Knuckles sequence.
 *
 * <p>ROM reference: {@code loc_6282A}; starts at x_vel=-$200,
 * y_vel=-$400, then uses {@code MoveSprite_LightGravity}.
 */
public final class CutsceneKnucklesLbz1ThrownBomb extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int LIGHT_GRAVITY = 0x20;
    private final SubpixelMotion.State motion;
    private boolean initialized;

    CutsceneKnucklesLbz1ThrownBomb(int x, int y) {
        super(new ObjectSpawn(x, y, 0, 0, 0, false, 0), "CutsceneKnuxLBZ1ThrownBomb");
        this.motion = new SubpixelMotion.State(x, y, 0, 0, -0x0200, -0x0400);
    }

    CutsceneKnucklesLbz1ThrownBomb(ObjectSpawn spawn) {
        this(spawn != null ? spawn.x() : 0, spawn != null ? spawn.y() : 0);
    }

    @Override
    public int getX() {
        return motion.x;
    }

    @Override
    public int getY() {
        return motion.y;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (!initialized) {
            initialized = true;
            services().playSfx(Sonic3kSfx.MISSILE_THROW.id);
        }
        SubpixelMotion.objectFallXY(motion, LIGHT_GRAVITY);
        updateDynamicSpawn(motion.x, motion.y);
        if (!isOnScreen(64)) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ1_CUTSCENE_KNUCKLES_BOMB);
        if (renderer != null && renderer.isReady()) {
            renderer.drawFrameIndexForcedPriority(0, motion.x, motion.y, false, false, -1, true);
        }
    }
}
