package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;

import java.util.List;

/**
 * CNZ miniboss coil child.
 *
 * <p>The ROM creates this alongside the top piece during Obj_CNZMiniboss init.
 * Collision and raw-animation behavior stay coordinated by the parent/top
 * objects while this child makes the production object graph explicit.
 */
public final class CnzMinibossCoilInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable, SpawnRewindRecreatable {
    private static final int CLOSED_COLLISION_FLAGS = 0x1A;
    private static final int CLOSED_COLLISION_PROPERTY = 0x70;
    private static final int OPEN_COLLISION_FLAGS = 0xA9;

    private CnzMinibossInstance boss;
    private int parentOffsetX;
    private int parentOffsetY;

    public CnzMinibossCoilInstance(ObjectSpawn spawn) {
        super(spawn, "CNZMinibossCoil");
    }

    public void attachBossForTest(CnzMinibossInstance boss) {
        this.boss = boss;
        if (boss != null) {
            parentOffsetX = getX() - boss.getCentreX();
            parentOffsetY = getY() - boss.getCentreY();
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (boss != null && boss.isDefeatedForChild()) {
            setDestroyed(true);
            return;
        }
        refreshChildPosition();
    }

    private void refreshChildPosition() {
        if (boss == null) {
            return;
        }
        updateDynamicSpawn(boss.getCentreX() + parentOffsetX, boss.getCentreY() + parentOffsetY);
    }

    @Override
    public int getCollisionFlags() {
        if (isDestroyed()) {
            return 0;
        }
        return boss != null && boss.isOpenForTopHit() ? OPEN_COLLISION_FLAGS : CLOSED_COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return boss != null && boss.isOpenForTopHit() ? 0 : CLOSED_COLLISION_PROPERTY;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Both coil routines refresh the child position before tail-calling
        // Add_SpriteToCollisionResponseList. The next player-slot Touch_Loop
        // dereferences that live SST position, not the snapshot from before the
        // preceding object pass (sonic3k.asm:145287-145340).
        return true;
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        if (boss == null || boss.isDefeatedForChild()) {
            return;
        }
        boss.onPlayerAttack(player, result);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Raw CNZ miniboss child rendering is handled in the animation slice.
    }
}
