package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Boss Eyes - Animated decoration showing bulging eyes.
 * ROM Reference: s2.asm Obj89 (subtype 8)
 * Simple timed object that displays for ~40 frames then disappears.
 */
public class ARZBossEyes extends AbstractObjectInstance implements SpawnRewindRecreatable {

    private static final int EYES_DURATION = 0x28; // 40 frames
    private static final int EYES_MAPPING_FRAME = 2;

    private int x;
    private int y;
    private int renderFlags;
    private int eyesTimer;
    private int mappingFrame;

    public ARZBossEyes(ObjectSpawn spawn) {
        super(spawn, "ARZ Boss Eyes");
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.eyesTimer = EYES_DURATION;
        this.mappingFrame = EYES_MAPPING_FRAME;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (eyesTimer <= 0) {
            setDestroyed(true);
            return;
        }
        eyesTimer--;
        mappingFrame = EYES_MAPPING_FRAME;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.ARZ_BOSS_PARTS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public int getPriorityBucket() {
        return 2;
    }
}
