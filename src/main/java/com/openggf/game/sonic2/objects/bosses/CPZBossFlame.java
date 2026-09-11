package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Boss Flame - Jet flame decoration under the Eggpod.
 * ROM Reference: s2.asm Obj5D (ROUTINE_FLAME = 0x18)
 * Animates through flame frames while attached to the boss.
 */
public class CPZBossFlame extends AbstractObjectInstance implements RewindRecreatable {

    private static final int[] FLAME_FRAMES = {0, -1, 1};
    private final Sonic2CPZBossInstance mainBoss;

    private int x;
    private int y;
    private int renderFlags;
    private int mappingFrame;
    private int animFrameDuration;
    private int frameIndex;

    public CPZBossFlame(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss) {
        super(spawn, "CPZ Boss Flame");
        this.mainBoss = mainBoss;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.mappingFrame = 0;
        this.animFrameDuration = 1;
        this.frameIndex = 0;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new CPZBossFlame(ctx.spawn(), boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (mainBoss == null || mainBoss.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        // Always sync position with parent boss (even during defeat/retreat)
        x = mainBoss.getX();
        y = mainBoss.getY();
        renderFlags = mainBoss.getRenderFlags();

        if (mappingFrame < 0) {
            mappingFrame = 0;
        }

        // Skip animation updates during defeat sequence
        if (mainBoss.isBossDefeated()) {
            return;
        }

        // Normal animation loop (3 frames)
        animFrameDuration--;
        if (animFrameDuration < 0) {
            animFrameDuration = 1;
            frameIndex++;
            if (frameIndex > 2) {
                frameIndex = 0;
            }
            mappingFrame = FLAME_FRAMES[frameIndex];
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_JETS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (mappingFrame < 0) {
            return;
        }

        boolean flipped = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, flipped, false);
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
        return 3;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
