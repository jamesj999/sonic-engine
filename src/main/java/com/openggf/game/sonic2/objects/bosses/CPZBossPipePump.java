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
 * CPZ Boss Pipe Pump - Pump head at the bottom of the extending pipe.
 * ROM Reference: s2.asm Obj5D (ROUTINE_PIPE_PUMP = 0x06)
 * Animates pumping motion as liquid is sucked up.
 */
public class CPZBossPipePump extends AbstractObjectInstance implements RewindRecreatable {

    private static final int SUB_ANIMATE = 2;
    private static final int SUB_END = 4;
    private final Sonic2CPZBossInstance mainBoss;
    private final CPZBossPipe parentPipe;

    private int x;
    private int y;
    private int renderFlags;
    private int routineSecondary;
    private int anim;
    private int mappingFrame;
    private int yOffset;
    private int timer;
    private int timer3;

    private ObjectAnimationState animationState;

    public CPZBossPipePump(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss,
                           CPZBossPipe parentPipe) {
        super(spawn, "CPZ Boss Pipe Pump");
        this.mainBoss = mainBoss;
        this.parentPipe = parentPipe;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.routineSecondary = SUB_ANIMATE;
        this.anim = 2;
        this.mappingFrame = 0;
        this.yOffset = 0x58;
        this.timer = 0x12;
        this.timer3 = 2;
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
        animate();  // Initialize mappingFrame to correct first frame for this anim
    }

    private CPZBossPipePump(ObjectSpawn spawn) {
        this(spawn, null, null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        CPZBossPipe pipe = CpzBossRewindLinks.nearestPipe(ctx);
        return pipe == null ? null : new CPZBossPipePump(ctx.spawn(), boss, pipe);
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
            setDestroyed(true);
            return;
        }

        switch (routineSecondary) {
            case SUB_ANIMATE -> updatePumpAnimate();
            case SUB_END -> updatePumpEnd();
        }
    }

    private void updatePumpAnimate() {
        x = parentPipe.getPipeX();
        y = parentPipe.getPipeY();
        renderFlags = parentPipe.getSpawn().renderFlags();

        timer--;
        if (timer == 0) {
            timer = 0x12;
            yOffset -= 8;
            if (yOffset < 0) {
                timer = 6;
                routineSecondary = SUB_END;
                return;
            }
            if (yOffset == 0) {
                anim = 3;
                timer = 0x0C;
            }
        }
        y += yOffset;
        animate();
    }

    private void updatePumpEnd() {
        timer--;
        if (timer != 0) {
            return;
        }

        // ROM Obj5D_Pipe_Pump_4 (docs/s2disasm/s2.asm:62199-62218): after
        // `subq.b #1,Obj5D_timer3 / beq.s +`, the would-be repeat branch writes
        // anim/timer/routine_secondary/y_offset but has NO rts — it FALLS
        // THROUGH to `+`, which switches the control object to
        // Obj5D_Pipe_Retract and `jmpto DeleteObject`s the pump head
        // unconditionally. The two-pass pump that `timer3 = 2` implies never
        // happens on hardware: every pump cycle is a single pass, and the
        // repeat writes are dead stores into an object that deletes itself the
        // same frame. Honouring the repeat kept the pipe control alive through
        // the boss defeat, where its Obj5D_PipeSegment_End conversion drew a
        // RandomNumber the ROM never draws (CPZ2 capsule stream desync).
        timer3--;
        parentPipe.beginRetractFromPump();
        setDestroyed(true);
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
        return 4;
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
