package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
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
 * CPZ Boss Pipe Segment - Individual segment of the extending pipe.
 * ROM Reference: s2.asm Obj5D (ROUTINE_PIPE_SEGMENT = 0x0E)
 * Follows parent pipe position with Y offset.
 */
public class CPZBossPipeSegment extends AbstractObjectInstance implements RewindRecreatable {
    private final Sonic2CPZBossInstance mainBoss;
    private final CPZBossPipe parentPipe;

    private int x;
    private int y;
    private int renderFlags;
    private int yOffset;
    private int anim;
    private int mappingFrame;
    private boolean retracting;

    private ObjectAnimationState animationState;

    public CPZBossPipeSegment(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss,
                               CPZBossPipe parentPipe, int yOffset) {
        super(spawn, "CPZ Boss Pipe Segment");
        this.mainBoss = mainBoss;
        this.parentPipe = parentPipe;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.yOffset = yOffset;
        this.anim = 1;
        this.mappingFrame = 0;
        this.retracting = false;
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
        animate();  // Initialize mappingFrame to correct first frame for this anim
    }

    private CPZBossPipeSegment(ObjectSpawn spawn) {
        this(spawn, null, null, 0);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        CPZBossPipe pipe = CpzBossRewindLinks.nearestPipe(ctx);
        if (pipe == null) {
            return null;
        }
        CPZBossPipeSegment segment = new CPZBossPipeSegment(ctx.spawn(), boss, pipe, 0);
        // The pipe's `segments` list is a final identity collection: structural rewind
        // state the owner must rebuild, not captured through the identity table. Re-register
        // each recreated segment so the restored pipe drives its one-at-a-time retract
        // sequence (updateRetract) off a rebuilt list instead of an empty one.
        pipe.reregisterRestoredSegment(segment);
        return segment;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (parentPipe == null || parentPipe.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        if (mainBoss != null && mainBoss.isBossDefeated()) {
            spawnFallingPart();
            setDestroyed(true);
            return;
        }

        if (retracting) {
            setDestroyed(true);
            return;
        }

        x = parentPipe.getPipeX();
        y = parentPipe.getPipeY() + yOffset;
        renderFlags = parentPipe.getSpawn().renderFlags();

        anim = 1;
        animate();
    }

    public void startRetract() {
        retracting = true;
    }

    public boolean isRetracting() {
        return retracting;
    }

    private void spawnFallingPart() {
        if (services().objectManager() == null) {
            return;
        }
        var motion = randomPipeMotion();
        ObjectSpawn partSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        spawnChild(() -> new CPZBossFallingPart(partSpawn, 1, motion.xVel(), motion.timer()));
    }

    private Sonic2Rng.PipeShardMotion randomPipeMotion() {
        return Sonic2Rng.nextPipeShardMotion(services().rng());
    }

    private void animate() {
        if (animationState == null) {
            return;
        }
        animationState.setAnimId(anim);
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
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
        return 5;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
