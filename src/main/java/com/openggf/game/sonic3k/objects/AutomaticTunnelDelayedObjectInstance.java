package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;

import java.util.List;

/**
 * Delayed entry point for {@link AutomaticTunnelObjectInstance}.
 *
 * <p>ROM reference: {@code Obj_AutomaticTunnelDelayed}
 * ({@code sonic3k.asm:57171-57179}). The delay byte is stored in
 * {@code anim_frame_timer}; after it underflows the object becomes a normal
 * {@code Obj_AutomaticTunnel}.
 */
final class AutomaticTunnelDelayedObjectInstance extends AbstractObjectInstance
        implements SpawnTrailingZeroIntsRewindRecreatable {
    private int subtype;
    private int delayTimer;
    private boolean delayElapsed;

    AutomaticTunnelDelayedObjectInstance(ObjectSpawn spawn, int subtype) {
        super(spawn, "AutomaticTunnelDelayed");
        this.subtype = subtype;
        this.delayTimer = 7;
    }

    @Override
    public String getName() {
        return "AutomaticTunnelDelayed";
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!delayElapsed) {
            delayTimer--;
            if (delayTimer >= 0) {
                return;
            }
            delayTimer = 0;
            delayElapsed = true;
        }
        spawnChild(() -> new AutomaticTunnelObjectInstance(new ObjectSpawn(
                getX(), getY(), Sonic3kObjectIds.AUTOMATIC_TUNNEL, subtype,
                spawn.renderFlags(), spawn.respawnTracked(), spawn.rawYWord(), spawn.layoutIndex())));
        setDestroyed(true);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Delayed wrapper is not drawn by the ROM; it becomes Obj_AutomaticTunnel after the timer underflows.
    }

    int delayTimerForTesting() {
        return delayTimer;
    }
}
