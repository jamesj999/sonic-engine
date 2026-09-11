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
 * CPZ Boss Dripper - Dripping liquid effect from the pump.
 * ROM Reference: s2.asm Obj5D (ROUTINE_DRIPPER = 0x0A)
 * Shows liquid dripping animation as pump operates.
 */
public class CPZBossDripper extends AbstractObjectInstance implements RewindRecreatable {

    private static final int SUB_INIT = 0;
    private static final int SUB_MAIN = 2;
    private static final int SUB_END = 4;
    private final Sonic2CPZBossInstance mainBoss;
    private final CPZBossPipe parentPipe;

    private int x;
    private int y;
    private int renderFlags;
    private int routineSecondary;
    private int anim;
    private int mappingFrame;
    private int timer;
    private int timer4;

    private ObjectAnimationState animationState;

    public CPZBossDripper(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss,
                          CPZBossPipe parentPipe) {
        super(spawn, "CPZ Boss Dripper");
        this.mainBoss = mainBoss;
        this.parentPipe = parentPipe;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.routineSecondary = SUB_INIT;
        this.anim = 4;
        this.mappingFrame = -1;
        this.timer = 0x0F;
        this.timer4 = 0;
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
    }

    private CPZBossDripper(ObjectSpawn spawn) {
        this(spawn, null, null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        // The dripper outlives the pipe control (see updateMain); recreate from
        // the boss alone, keeping the pipe reference only when one still exists.
        CPZBossPipe pipe = CpzBossRewindLinks.nearestPipe(ctx);
        return boss == null ? null : new CPZBossDripper(ctx.spawn(), boss, pipe);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (mainBoss != null && mainBoss.isBossDefeated()) {
            setDestroyed(true);
            return;
        }

        switch (routineSecondary) {
            case SUB_INIT -> updateInit();
            case SUB_MAIN -> updateMain();
            case SUB_END -> updateEnd();
        }
    }

    private void updateInit() {
        routineSecondary = SUB_MAIN;
        anim = 4;
        timer = 0x0F;

        if (mainBoss != null) {
            x = mainBoss.getX();
            y = mainBoss.getY();
        }

        updateMain();
    }

    private void updateMain() {
        timer--;
        if (timer == 0) {
            anim = 5;
            timer = 4;
            routineSecondary = SUB_END;
            if (mainBoss != null) {
                x = mainBoss.getX() - 2;
                y = mainBoss.getY() - 0x24;
            }
            animate();  // Sync mappingFrame with new anim before returning
            return;
        }

        // ROM Obj5D_Dripper is INDEPENDENT of the pipe control: its
        // Obj5D_parent is the MAIN VEHICLE (Obj5D_Pipe_Pump_0 copies the pump
        // head's parent pointer, docs/s2disasm/s2.asm:62166-62168), it tracks
        // the vehicle's x/y every frame (Obj5D_Dripper_2/4 tails), and it only
        // deletes on the defeat status bit or after its own 12 cycles
        // (Obj5D_Dripper_4: addq #1,Obj5D_timer4 / cmpi #$C / bge DeleteObject).
        // The ROM pump head and pipe control are long gone (single pump pass,
        // s2.asm:62199-62218) while the dripper keeps pulsing status2 bit 1 to
        // drive the container fill, so its lifetime must not be tied to the
        // pipe's.
        if (mainBoss != null) {
            x = mainBoss.getX();
            y = mainBoss.getY();
            renderFlags = mainBoss.getRenderFlags();
        }
        animate();
    }

    private void updateEnd() {
        timer--;
        if (timer == 0) {
            routineSecondary = SUB_INIT;

            // Signal to boss that dripper cycle complete
            if (mainBoss != null) {
                mainBoss.onDripperCycleComplete();
            }

            timer4++;
            if (timer4 >= 0x0C) {
                setDestroyed(true);
            }
            return;
        }

        // See updateMain: the ROM dripper tracks the MAIN VEHICLE and outlives
        // the pipe control (Obj5D_Dripper_4 tail, docs/s2disasm/s2.asm:62252-62264).
        if (mainBoss != null) {
            x = mainBoss.getX() - 2;
            y = mainBoss.getY() - 0x24;
        }
        if ((renderFlags & 1) != 0) {
            x += 4;
        }
        animate();
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
        renderer.drawFrameIndex(mappingFrame, x, y, flipped, false, 3);
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
