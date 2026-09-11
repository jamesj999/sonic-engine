package com.openggf.game.rewind.identity;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

public final class RewindIdentityTable {
    private final IdentityHashMap<PlayableEntity, PlayerRefId> playerToId = new IdentityHashMap<>();
    private final Map<PlayerRefId, PlayableEntity> idToPlayer = new HashMap<>();
    private final IdentityHashMap<ObjectInstance, ObjectRefId> objectToId = new IdentityHashMap<>();
    private final Map<ObjectRefId, ObjectInstance> idToObject = new HashMap<>();
    private final IdentityHashMap<ObjectSpawn, SpawnRefId> spawnToId = new IdentityHashMap<>();
    private final Map<SpawnRefId, ObjectSpawn> idToSpawn = new HashMap<>();

    public void registerPlayer(PlayableEntity player, PlayerRefId id) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(id, "id");
        if (id.isNull()) {
            throw new IllegalArgumentException("Cannot register a live player with the null player id");
        }
        register(playerToId, idToPlayer, player, id, "player");
    }

    public PlayerRefId encodePlayer(PlayableEntity player) {
        if (player == null) {
            return PlayerRefId.nullRef();
        }
        return playerToId.get(player);
    }

    public PlayableEntity resolvePlayer(PlayerRefId id, boolean required) {
        if (id == null || id.isNull()) {
            return null;
        }
        PlayableEntity player = idToPlayer.get(id);
        if (player == null && required) {
            throw new IllegalStateException("Missing required player reference: " + id);
        }
        return player;
    }

    public void registerObject(ObjectInstance object, ObjectRefId id) {
        Objects.requireNonNull(object, "object");
        Objects.requireNonNull(id, "id");
        register(objectToId, idToObject, object, id, "object");
    }

    public ObjectRefId encodeObject(ObjectInstance object) {
        if (object == null) {
            return null;
        }
        return objectToId.get(object);
    }

    /**
     * Returns the registered {@link ObjectRefId} for the given object instance, or
     * {@code null} if the object has not been registered.
     *
     * <p>This is a convenience alias for {@link #encodeObject(ObjectInstance)} using the
     * name preferred by Task-2 callers and tests.
     */
    public ObjectRefId idFor(ObjectInstance object) {
        return encodeObject(object);
    }

    public ObjectInstance resolveObject(ObjectRefId id, boolean required) {
        if (id == null) {
            return null;
        }
        ObjectInstance object = idToObject.get(id);
        if (object == null && required) {
            throw new IllegalStateException("Missing required object reference: " + id);
        }
        return object;
    }

    /**
     * Resolves an {@link ObjectRefId} to the live {@link ObjectInstance} it was registered
     * for, or {@code null} if not found.
     *
     * <p>This is a non-throwing convenience alias for {@link #resolveObject(ObjectRefId, boolean)}
     * using the name preferred by Task-2 callers.
     */
    public ObjectInstance resolve(ObjectRefId id) {
        return resolveObject(id, false);
    }

    public void registerSpawn(ObjectSpawn spawn) {
        Objects.requireNonNull(spawn, "spawn");
        register(spawnToId, idToSpawn, spawn, SpawnRefId.fromSpawn(spawn), "spawn");
    }

    public SpawnRefId encodeSpawn(ObjectSpawn spawn) {
        if (spawn == null) {
            return null;
        }
        return spawnToId.get(spawn);
    }

    public ObjectSpawn resolveSpawn(SpawnRefId id, boolean required) {
        if (id == null) {
            return null;
        }
        ObjectSpawn spawn = idToSpawn.get(id);
        if (spawn == null && required) {
            throw new IllegalStateException("Missing required spawn reference: " + id);
        }
        return spawn;
    }

    private static <T, I> void register(
            IdentityHashMap<T, I> liveToId,
            Map<I, T> idToLive,
            T live,
            I id,
            String label) {

        I existingId = liveToId.get(live);
        if (existingId != null && !existingId.equals(id)) {
            throw new IllegalStateException("Cannot register same " + label + " with different ids: "
                    + existingId + " and " + id);
        }

        T existingLive = idToLive.get(id);
        if (existingLive != null && existingLive != live) {
            throw new IllegalStateException("Cannot register " + label + " id to different live instances: " + id);
        }

        liveToId.put(live, id);
        idToLive.put(id, live);
    }
}
