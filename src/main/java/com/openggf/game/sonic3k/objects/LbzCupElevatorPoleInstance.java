package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * S3K S3KL object $19 - LBZ cup elevator pole.
 *
 * <p>ROM reference: {@code Obj_LBZCupElevatorPole} (sonic3k.asm:53187-53211).
 */
public final class LbzCupElevatorPoleInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {
    private static final int SHORT_HEIGHT = 0x30;
    private static final int LONG_HEIGHT = 0x60;

    private int mappingFrame;
    private int halfHeight;

    public LbzCupElevatorPoleInstance(ObjectSpawn spawn) {
        super(spawn, "LBZCupElevatorPole");
        boolean longPole = (spawn.subtype() & 0x3F) != 0;
        this.mappingFrame = longPole ? 4 : 3;
        this.halfHeight = longPole ? LONG_HEIGHT : SHORT_HEIGHT;
    }

    @Override
    public int getPriorityBucket() {
        return 3; // priority=$180
    }

    @Override
    public int getOnScreenHalfWidth() {
        return 8;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return halfHeight;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if ((spawn.subtype() & 0x40) != 0 && currentPlayerCharacter() == PlayerCharacter.KNUCKLES) {
            setDestroyed(true);
            return;
        }
        updateDynamicSpawn(getX(), getY());
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_CUP_ELEVATOR);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }

    private PlayerCharacter currentPlayerCharacter() {
        try {
            return S3kRuntimeStates.resolvePlayerCharacter(
                    services().zoneRuntimeRegistry(),
                    services().configuration());
        } catch (IllegalStateException ignored) {
            return PlayerCharacter.SONIC_AND_TAILS;
        }
    }
}
