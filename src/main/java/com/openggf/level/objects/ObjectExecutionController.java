package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.solid.ObjectSolidExecutionContext;
import com.openggf.game.solid.SolidExecutionRegistry;

import java.util.List;

/** Executes one SST routine inside the shared solid-checkpoint envelope. */
final class ObjectExecutionController {
    private final ObjectManager objects;

    ObjectExecutionController(ObjectManager objects) {
        this.objects = objects;
    }

    List<PlayableEntity> collectActivePlayers(
            PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            List<PlayableEntity> destination) {
        destination.clear();
        if (player != null) {
            destination.add(player);
        }
        for (PlayableEntity sidekick : sidekicks) {
            if (sidekick != null) {
                destination.add(sidekick);
            }
        }
        return destination;
    }

    void execute(
            ObjectInstance instance,
            PlayableEntity player,
            List<? extends PlayableEntity> sidekicks,
            boolean inlineSolidResolution,
            boolean solidPostMovement) {
        SolidExecutionRegistry registry = objects.solidExecutionRegistry();
        SolidExecutionMode mode = null;
        if (instance instanceof SolidObjectProvider provider) {
            SolidExecutionMode providerMode = provider.solidExecutionMode();
            if (inlineSolidResolution || providerMode == SolidExecutionMode.MANUAL_CHECKPOINT) {
                mode = providerMode;
            }
        }
        ObjectSolidExecutionContext.Resolver resolver =
                mode == SolidExecutionMode.MANUAL_CHECKPOINT
                        ? () -> objects.processManualSolidCheckpoint(
                                instance, player, sidekicks, solidPostMovement)
                        : null;
        registry.beginObject(instance, resolver);
        try {
            instance.update(objects.vblaCounter(), player);
            if (instance instanceof AbstractObjectInstance object) {
                object.clearAwaitingFirstTouchExecution();
            }
            objects.publishInitialCollisionResponse(instance);
            if (mode == SolidExecutionMode.AUTO_AFTER_UPDATE && !instance.isDestroyed()) {
                registry.publishCheckpoint(objects.processCompatibilitySolidCheckpoint(
                        instance, player, sidekicks, solidPostMovement));
            }
        } finally {
            registry.endObject(instance);
        }
    }
}
