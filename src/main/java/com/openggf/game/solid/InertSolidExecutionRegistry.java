package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.List;

public enum InertSolidExecutionRegistry implements SolidExecutionRegistry {
    INSTANCE;

    @Override
    public void beginFrame(int frameCounter, List<? extends PlayableEntity> players) {
    }

    @Override
    public void beginObject(ObjectInstance object, ObjectSolidExecutionContext.Resolver resolver) {
    }

    @Override
    public ObjectSolidExecutionContext currentObject() {
        return ObjectSolidExecutionContext.inert();
    }

    @Override
    public PlayerStandingState previousStanding(ObjectInstance object, PlayableEntity player) {
        return PlayerStandingState.NONE;
    }

    @Override
    public void publishCheckpoint(SolidCheckpointBatch batch) {
    }

    @Override
    public void endObject(ObjectInstance object) {
    }

    @Override
    public void finishFrame() {
    }

    @Override
    public void clearTransientState() {
    }
}
