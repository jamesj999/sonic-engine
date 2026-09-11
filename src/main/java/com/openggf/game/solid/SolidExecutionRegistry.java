package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.List;

public interface SolidExecutionRegistry {
    /**
     * Begins a new solid-execution frame.
     *
     * @param players the active players for this frame. This list is transient
     *     caller-owned scratch (reused across frames); implementations must not
     *     retain the reference past this call — copy the contents if they are
     *     needed later.
     */
    void beginFrame(int frameCounter, List<? extends PlayableEntity> players);

    void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver);

    ObjectSolidExecutionContext currentObject();

    PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player);

    void publishCheckpoint(SolidCheckpointBatch batch);

    void endObject(ObjectInstance object);

    void finishFrame();

    void clearTransientState();

    static SolidExecutionRegistry inert() {
        return InertSolidExecutionRegistry.INSTANCE;
    }
}
