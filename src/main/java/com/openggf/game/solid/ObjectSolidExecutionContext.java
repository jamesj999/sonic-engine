package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.Map;

public final class ObjectSolidExecutionContext {

    @FunctionalInterface
    public interface Resolver {
        SolidCheckpointBatch resolveNow();
    }

    private static final SolidCheckpointBatch EMPTY_BATCH =
            new SolidCheckpointBatch(null, Map.of());
    private static final ObjectSolidExecutionContext INERT =
            new ObjectSolidExecutionContext(null, null, null);

    private final SolidExecutionRegistry owner;
    private final ObjectInstance object;
    private final Resolver resolver;
    private SolidCheckpointBatch lastCheckpoint;

    public ObjectSolidExecutionContext(
            SolidExecutionRegistry owner,
            ObjectInstance object,
            Resolver resolver) {
        this.owner = owner;
        this.object = object;
        this.resolver = resolver;
        this.lastCheckpoint = emptyBatchFor(object);
    }

    public static ObjectSolidExecutionContext inert() {
        return INERT;
    }

    public boolean isInert() {
        return object == null || resolver == null || owner == null;
    }

    public ObjectInstance object() {
        return object;
    }

    public SolidCheckpointBatch resolveSolidNowAll() {
        if (isInert()) {
            lastCheckpoint = emptyBatchFor(object);
            return lastCheckpoint;
        }
        lastCheckpoint = resolver.resolveNow();
        owner.publishCheckpoint(lastCheckpoint);
        return lastCheckpoint;
    }

    public PlayerSolidContactResult resolveSolidNow(PlayableEntity player) {
        PlayerStandingState previousStanding = isInert()
                ? PlayerStandingState.NONE
                : owner.previousStanding(object, player);
        return resolveSolidNowAll().perPlayer().getOrDefault(
                player,
                PlayerSolidContactResult.noContact(
                        previousStanding,
                        PreContactState.ZERO,
                        PostContactState.ZERO));
    }

    public SolidCheckpointBatch lastCheckpoint() {
        return lastCheckpoint;
    }

    private static SolidCheckpointBatch emptyBatchFor(ObjectInstance object) {
        return object == null ? EMPTY_BATCH : new SolidCheckpointBatch(object, Map.of());
    }
}
