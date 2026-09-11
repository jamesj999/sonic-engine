package com.openggf.game.solid;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectInstance;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

public record SolidCheckpointBatch(
        ObjectInstance object,
        Map<PlayableEntity, PlayerSolidContactResult> perPlayer) {

    public SolidCheckpointBatch {
        perPlayer = defensiveCopy(perPlayer);
    }

    /**
     * Identity-keyed defensive copy that does not allocate per entry.
     *
     * <p>A batch is built for every solid object every frame, so this sits in
     * the steady-state allocation path. The obvious spelling —
     * {@code new IdentityHashMap<>(source)} — routes through {@code putAll},
     * whose iterator allocates a fresh {@code Map.Entry} for <em>every</em>
     * element: {@code IdentityHashMap}'s entry iterator hands back a new object
     * per {@code next()} rather than reusing a cursor. {@code forEach} walks the
     * table directly, so a copy costs one lambda instead of one object per
     * player, plus the iterator.
     *
     * <p>An empty map short-circuits to {@link Map#of()} — the common case, for
     * every object that resolves no contact this frame — which avoids both the
     * map and the unmodifiable wrapper. Identity-versus-equality semantics are
     * immaterial for a map with no entries.
     *
     * <p>The copy itself is kept: callers hand over a map they built and the
     * batch outlives the call, so trusting the caller not to mutate it would
     * trade a real correctness guarantee for two allocations.
     */
    private static Map<PlayableEntity, PlayerSolidContactResult> defensiveCopy(
            Map<PlayableEntity, PlayerSolidContactResult> source) {
        if (source.isEmpty()) {
            return Map.of();
        }
        IdentityHashMap<PlayableEntity, PlayerSolidContactResult> copy =
                new IdentityHashMap<>(source.size());
        source.forEach(copy::put);
        return Collections.unmodifiableMap(copy);
    }
}
