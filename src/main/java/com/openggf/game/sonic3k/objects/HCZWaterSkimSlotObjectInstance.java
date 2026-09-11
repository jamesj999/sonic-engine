package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;

import java.util.List;

/**
 * Persistent owner for HCZ's interactive water-skim SST record.
 *
 * <p>The ROM installs {@code Obj_HCZWaterSplash} subtype 1 at
 * {@code Dynamic_object_RAM+2}, absolute SST slot 5, during HCZ level init
 * (sonic3k.asm:7807-7809). Its player-facing state is coordinated by
 * {@link com.openggf.game.sonic3k.features.HCZWaterSkimHandler}; this object
 * keeps the native slot occupied so later {@code AllocateObject} calls scan
 * the same SST window as the ROM.
 */
public final class HCZWaterSkimSlotObjectInstance extends AbstractObjectInstance
        implements RewindRecreatable {

    private static final ObjectSpawn SLOT_SPAWN =
            new ObjectSpawn(0, 0, 0, 0, 0, false, 0);

    public HCZWaterSkimSlotObjectInstance() {
        this(SLOT_SPAWN);
    }

    public HCZWaterSkimSlotObjectInstance(ObjectSpawn spawn) {
        super(spawn, "HCZWaterSkim");
    }

    @Override
    public HCZWaterSkimSlotObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new HCZWaterSkimSlotObjectInstance(ctx.spawn());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        // HCZWaterSkimHandler owns the player-participant pass and its timing.
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // The handler renders the player-following splash at the native routine's
        // post-player position; the slot owner itself has no independent sprite.
    }

    @Override
    public boolean isPersistent() {
        return true;
    }
}
