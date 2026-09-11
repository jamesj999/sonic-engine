package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameStateManager;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.List;

/**
 * MGZ2 post-boss waiter.
 *
 * <p>ROM: after {@code loc_694AA} creates the floating egg prison,
 * {@code Obj_MGZEndBoss} stays alive at {@code loc_6C2EE} and waits for the
 * results object to finish. When results set {@code End_of_level_flag}, it
 * creates {@code loc_6D104}, the MGZ-to-CNZ palette fade controller.
 */
public class Mgz2PostBossSequenceController extends AbstractObjectInstance implements RewindRecreatable {

    public Mgz2PostBossSequenceController() {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "MGZ2PostBossSequence");
    }

    @Override
    public Mgz2PostBossSequenceController recreateForRewind(RewindRecreateContext ctx) {
        return new Mgz2PostBossSequenceController();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        GameStateManager gameState = services().gameState();
        if (gameState == null || !gameState.isEndOfLevelFlag()) {
            return;
        }

        spawnFreeChild(Mgz2PostBossPaletteFadeController::new);
        setDestroyed(true);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
    }

    @Override
    public boolean isPersistent() {
        return true;
    }
}
