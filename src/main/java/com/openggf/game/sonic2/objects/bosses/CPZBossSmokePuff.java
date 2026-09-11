package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Boss Smoke Puff - Smoke effect during retreat.
 * ROM Reference: s2.asm Obj5D (ROUTINE_SMOKE_PUFF = 0x1A)
 * Animates through smoke frames following the boss.
 */
public class CPZBossSmokePuff extends AbstractObjectInstance implements RewindRecreatable {
    private final Sonic2CPZBossInstance mainBoss;

    private int x;
    private int y;
    private int mappingFrame;
    private int animFrameDuration;

    public CPZBossSmokePuff(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss) {
        super(spawn, "CPZ Boss Smoke");
        this.mainBoss = mainBoss;
        this.x = spawn.x();
        this.y = spawn.y();
        this.mappingFrame = 0;
        this.animFrameDuration = 5;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new CPZBossSmokePuff(ctx.spawn(), boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        animFrameDuration--;
        if (animFrameDuration < 0) {
            animFrameDuration = 5;
            mappingFrame++;
            if (mappingFrame == 4) {
                mappingFrame = 0;
                if (mainBoss == null || mainBoss.isDestroyed()) {
                    setDestroyed(true);
                    return;
                }
                x = mainBoss.getX() - 0x28;
                y = mainBoss.getY() + 4;
            }
        }

        if (mainBoss != null) {
            x = mainBoss.getX() - 0x28;
            y = mainBoss.getY() + 4;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_SMOKE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (mappingFrame < 0) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
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
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), 0, spawn.respawnTracked(), spawn.rawYWord());
    }
}
