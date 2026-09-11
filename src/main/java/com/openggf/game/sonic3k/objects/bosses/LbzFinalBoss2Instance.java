package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

import java.util.List;

/**
 * Thin handoff placeholder for S3KL object $CC, Obj_LBZFinalBoss2.
 *
 * <p>The Big Arm fight is intentionally out of scope here. This object only
 * gives {@link LbzFinalBoss1Instance}'s Knuckles branch and the LBZ registry a
 * concrete handoff target without loading Big Arm art/palette data or modeling
 * the fight init routine.
 */
public final class LbzFinalBoss2Instance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    public LbzFinalBoss2Instance(ObjectSpawn spawn) {
        super(spawn, "LBZFinalBoss2");
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        updateDynamicSpawn(getX(), getY());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Big Arm rendering is not implemented in this scope.
    }
}
