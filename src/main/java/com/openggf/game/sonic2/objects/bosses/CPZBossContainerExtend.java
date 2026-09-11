package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.objects.ObjectLifetimeOps;
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
 * CPZ Boss Container Extend - Extending liquid part of the container.
 * ROM Reference: s2.asm Obj5D (ROUTINE_CONTAINER routineSecondary 6)
 * Extends from container and eventually becomes gunk.
 */
public class CPZBossContainerExtend extends AbstractObjectInstance implements RewindRecreatable {
    private final Sonic2CPZBossInstance mainBoss;
    private final CPZBossContainer container;

    private int x;
    private int y;
    private int renderFlags;
    private int anim;
    private int mappingFrame;

    private ObjectAnimationState animationState;

    public CPZBossContainerExtend(ObjectSpawn spawn, Sonic2CPZBossInstance mainBoss,
                                   CPZBossContainer container) {
        super(spawn, "CPZ Boss Extend");
        this.mainBoss = mainBoss;
        this.container = container;
        this.x = spawn.x();
        this.y = spawn.y();
        this.renderFlags = spawn.renderFlags();
        this.anim = 0;
        this.mappingFrame = -1; // Don't render until animation starts
        this.animationState = new ObjectAnimationState(CPZBossAnimations.getDripperAnimations(), anim, mappingFrame);
    }

    private CPZBossContainerExtend(ObjectSpawn spawn) {
        this(spawn, null, null);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        Sonic2CPZBossInstance boss = CpzBossRewindLinks.nearestBoss(ctx);
        CPZBossContainer parentContainer = CpzBossRewindLinks.nearestContainer(ctx);
        return boss == null || parentContainer == null
                ? null
                : new CPZBossContainerExtend(ctx.spawn(), boss, parentContainer);
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

        if (container == null || container.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        // Check if should become gunk
        if (mainBoss != null && mainBoss.shouldSpawnGunk()) {
            mainBoss.clearSpawnGunkFlag();
            // ROM: the rewrite branches to Obj5D_Container_Floor_End, which still
            // copies the parent container's position, render flags and status into
            // this object and animates and displays it for THIS frame
            // (docs/s2disasm/s2.asm:62843-62848, :62881-62889). The gunk's own
            // routine is not reached on the rewriting frame, so this frame's
            // position copy is the position the gunk starts from.
            updatePosition();
            becomeGunk();
            return;
        }

        // ROM: bclr #1,Obj5D_status2(a1) / bne.s + / tst.b anim(a0) / bne.s Floor_End / rts
        // When ACTION1 is NOT set:
        //   - If anim == 0: return without rendering (wait state)
        //   - If anim != 0: render but DON'T increment
        // When ACTION1 IS set:
        //   - Clear ACTION1, set anim to 0x0B if it was 0, then increment
        if (!mainBoss.shouldAdvanceExtend()) {
            if (anim == 0) {
                // Wait state - don't render
                mappingFrame = -1;
                return;
            }
            // anim != 0 but ACTION1 not set - render but don't increment
            updatePosition();
            return;
        }

        // ACTION1 was set - clear it and advance animation
        mainBoss.clearAdvanceExtendFlag();
        if (anim == 0) {
            anim = 0x0B;
        }
        anim += 1;
        if (anim >= 0x17) {
            mainBoss.onExtendComplete();
        }
        updatePosition();
    }

    private void updatePosition() {
        if (container != null) {
            x = container.getContainerX();
            y = container.getContainerY();
            renderFlags = container.getSpawn().renderFlags();
        }
        animate();
    }

    /**
     * ROM: {@code Obj5D_Container_Extend} does not allocate a gunk. It rewrites
     * ITSELF -- {@code move.b #$C,routine(a0)},
     * {@code move.b #0,routine_secondary(a0)},
     * {@code move.b #$87,collision_flags(a0)} -- and then branches to
     * {@code Obj5D_Container_Floor_End} (docs/s2disasm/s2.asm:62843-62848).
     *
     * <p>Two consequences, and both are load-bearing. The gunk keeps this
     * object's SST slot rather than taking a fresh one. And because the object
     * pass has already run that slot, {@code Obj5D_Gunk_Init} -- which falls
     * through into {@code Obj5D_Gunk_Main} and its first
     * {@code ObjectMoveAndFall} -- is not reached until the NEXT frame.
     */
    private void becomeGunk() {
        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }
        int transferredSlot = ObjectLifetimeOps.detachSlotForTransfer(this);
        ObjectLifetimeOps.destroyLatched(this);
        ObjectLifetimeOps.removeSpawnFromActive(objectManager, spawn);
        ObjectSpawn gunkSpawn = new ObjectSpawn(x, y, Sonic2ObjectIds.CPZ_BOSS, 0, renderFlags, false, 0);
        ObjectLifetimeOps.addReplacementAtTransferredSlot(objectManager,
                new CPZBossGunk(gunkSpawn, mainBoss, false), transferredSlot);
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
        return 5;  // Render behind container body (bucket 4) so liquid appears inside
    }

    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(x, y, spawn.objectId(), spawn.subtype(), renderFlags, spawn.respawnTracked(), spawn.rawYWord());
    }
}
