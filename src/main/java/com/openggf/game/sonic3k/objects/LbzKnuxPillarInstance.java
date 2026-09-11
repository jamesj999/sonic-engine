package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.runtime.LbzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

public final class LbzKnuxPillarInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private int x;
    private int y;
    @RewindTransient(reason = "Constructor-derived from spawn render flags.")
    private final boolean artPriority;
    @RewindTransient(reason = "Constructor-derived from spawn render flags.")
    private final int priority;

    public LbzKnuxPillarInstance(ObjectSpawn spawn) {
        super(spawn, "LBZKnuxPillar");
        this.x = spawn.x();
        this.y = spawn.y();
        this.artPriority = (spawn.renderFlags() & 0x02) != 0;
        this.priority = (spawn.renderFlags() & 0x01) != 0 ? 0 : 0x280;
        updateDynamicSpawn(x, y);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        y = (y + launchYDelta()) & 0xFFFF;
        updateDynamicSpawn(x, y);
        if (!isInRangeAt(x)) {
            setDestroyedByOffscreen();
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_KNUX_PILLAR);
        if (renderer != null) {
            renderer.drawFrameIndex(1, x, y, false, false);
        }
    }

    public int getCentreX() {
        return x;
    }

    public int getCentreY() {
        return y;
    }

    public boolean isArtPriorityForTest() {
        return artPriority;
    }

    @Override
    public boolean isHighPriority() {
        return artPriority;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(priority / 0x80);
    }

    public int getPriorityForTest() {
        return priority;
    }

    private int launchYDelta() {
        return services().zoneRuntimeRegistry()
                .currentAs(LbzZoneRuntimeState.class)
                .map(LbzZoneRuntimeState::getLaunchYDelta)
                .orElse(0);
    }
}
