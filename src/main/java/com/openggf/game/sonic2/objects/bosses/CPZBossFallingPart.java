package com.openggf.game.sonic2.objects.bosses;
import com.openggf.game.PlayableEntity;

import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.boss.BossExplosionObjectInstance;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CPZ Boss Falling Part - Debris during defeat sequence.
 * ROM Reference: s2.asm Obj5D (ROUTINE_FALLING_PARTS = 0x14)
 * Explodes after timer, then falls with gravity.
 */
public class CPZBossFallingPart extends AbstractObjectInstance
        implements SpawnTrailingZeroIntsRewindRecreatable {

    private static final int GRAVITY = 0x38;
    private static final int FLOOR_Y = 0x580;

    private int x;
    private int y;
    private int xVel;
    private int yVel;
    private int yFixed;
    private int renderFlags;
    private int mappingFrame;
    private int timer;
    private int timer2;
    private boolean exploded;

    public CPZBossFallingPart(ObjectSpawn spawn, int mappingFrame, int xVel) {
        this(spawn, mappingFrame, xVel, 0x78);
    }

    public CPZBossFallingPart(ObjectSpawn spawn, int mappingFrame, int xVel, int timer) {
        super(spawn, "CPZ Boss Part");
        this.x = spawn.x();
        this.y = spawn.y();
        this.xVel = xVel;
        this.yVel = -0x380;
        this.yFixed = y << 16;
        this.renderFlags = spawn.renderFlags();
        this.mappingFrame = mappingFrame;
        this.timer = timer;
        this.timer2 = 0;
        this.exploded = false;
    }

    private CPZBossFallingPart(ObjectSpawn spawn) {
        this(spawn, 0, 0, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (!exploded) {
            timer--;
            if (timer > 0) {
                return;
            }
            spawnExplosion();
            exploded = true;
            timer2 = 0x1E;
        }

        timer2--;
        if (timer2 >= 0) {
            return;
        }

        applyFallingMove();
        if (yFixed >= (FLOOR_Y << 16)) {
            setDestroyed(true);
        }
    }

    private void applyFallingMove() {
        x += xVel;
        yFixed += (yVel << 8);
        yVel += GRAVITY;
        y = yFixed >> 16;
    }

    private void spawnExplosion() {
        if (services().objectManager() == null) {
            return;
        }
        if (services().renderManager() == null) {
            return;
        }
        spawnFreeChild(() -> new BossExplosionObjectInstance(
                x, y, Sonic2ObjectIds.BOSS_EXPLOSION, Sonic2Sfx.BOSS_EXPLOSION.id));
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_PARTS);
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
        return 2;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
