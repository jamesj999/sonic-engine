package com.openggf.level.objects.boss;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Boss Explosion (Obj58).
 * Uses ArtNem_FieryExplosion with mappings from Obj58_MapUnc_2D50A.
 */
public class BossExplosionObjectInstance extends AbstractObjectInstance
        implements SpawnCoordinateZeroScalarArgsRewindRecreatable {
    private static final int FRAME_DELAY = 7;
    private static final int LAST_FRAME = 6;

    private int sfxId;
    private int mappingFrame;
    private int frameTimer;
    private boolean initialized;

    public BossExplosionObjectInstance(int x, int y, int sfxId) {
        this(x, y, 0, sfxId);
    }

    public BossExplosionObjectInstance(int x, int y, int objectId, int sfxId) {
        super(new ObjectSpawn(x, y, objectId, 0, 0, false, 0), "Boss Explosion");
        this.sfxId = sfxId;
        this.mappingFrame = 0;
        this.frameTimer = FRAME_DELAY;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!initialized) {
            services().playSfx(sfxId);
            initialized = true;
            return;
        }
        frameTimer--;
        if (frameTimer >= 0) {
            return;
        }
        frameTimer = FRAME_DELAY;
        mappingFrame++;
        if (mappingFrame > LAST_FRAME) {
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        var renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getBossExplosionRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), false, false);
    }
}
