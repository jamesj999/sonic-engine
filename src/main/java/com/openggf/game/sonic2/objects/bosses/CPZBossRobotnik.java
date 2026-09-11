package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
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
 * CPZ Boss Robotnik face - Animated Eggman sprite.
 * ROM Reference: s2.asm Obj5D (ROUTINE_ROBOTNIK = 0x16)
 * Follows parent position and shows expressions based on hit state.
 */
public class CPZBossRobotnik extends AbstractObjectInstance implements RewindRecreatable {
    private final Sonic2CPZBossInstance mainBoss;

    private int x;
    private int y;
    private int renderFlags;
    private int anim;
    private int mappingFrame;

    private ObjectAnimationState animationState;

    public CPZBossRobotnik(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss) {
        super(spawn, "CPZ Boss Robotnik");
        this.mainBoss = mainBoss;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.anim = 1;
        this.mappingFrame = 0;
        this.animationState = new ObjectAnimationState(
                CPZBossAnimations.getEggpodAnimations(), anim, mappingFrame);
        animate();  // Initialize mappingFrame to correct first frame for this anim
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        return boss == null ? null : new CPZBossRobotnik(ctx.spawn(), boss);
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

        x = mainBoss.getX();
        y = mainBoss.getY();
        renderFlags = mainBoss.getRenderFlags();

        // Check if boss just got hit
        if (mainBoss.isInvulnerable() && mainBoss.getInvulnerabilityTimer() == mainBoss.getInvulnerabilityDuration() - 1) {
            anim = 2; // Hurt face
        }

        // Check if player is hurt (laugh)
        if (player != null && player.isHurt()) {
            anim = 3;
        }

        animate();
    }

    private void animate() {
        animationState.setAnimId(anim);
        animationState.update();
        mappingFrame = animationState.getMappingFrame();
    }

    public void setAnim(int anim) {
        this.anim = anim;
        if (animationState != null) {
            animationState.setAnimId(anim);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CPZ_BOSS_EGGPOD);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (mappingFrame < 0) {
            return;
        }

        boolean flipped = (renderFlags & 1) != 0;
        renderer.drawFrameIndex(mappingFrame, x, y, flipped, false, 0);
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
