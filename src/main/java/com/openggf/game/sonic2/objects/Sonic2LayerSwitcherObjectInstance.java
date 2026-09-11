package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;

import java.util.List;

/**
 * Invisible SST occupant for Sonic 2 Obj03.
 * <p>
 * The layer/priority behavior is owned by ObjectManager's plane-switcher path,
 * but the ROM still allocates Obj03 into an object slot before its MarkObjGone3
 * unload check (docs/s2disasm/s2.asm:45550-45789). Keeping an instance here
 * preserves native slot layout for Tails' interact slot and other SST-indexed
 * state without rendering or duplicating the behavior manager.
 */
public class Sonic2LayerSwitcherObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    public Sonic2LayerSwitcherObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public Sonic2LayerSwitcherObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new Sonic2LayerSwitcherObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        for (PlayableEntity participant : services().playerQuery().playersFor(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2)) {
            services().objectManager().applyPlaneSwitcher(getSpawn(), participant);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Obj03 does not render.
    }
}
