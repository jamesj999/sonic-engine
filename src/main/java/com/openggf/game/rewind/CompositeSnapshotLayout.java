package com.openggf.game.rewind;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable ordered key layout shared by snapshots captured from one registry
 * configuration. Layout identity is the fast-path compatibility token; the
 * version is diagnostic only and is local to the owning registry.
 */
final class CompositeSnapshotLayout {
    private final long version;
    private final String[] keys;
    private final Map<String, Integer> indexByKey;

    private CompositeSnapshotLayout(long version, String[] keys) {
        this.version = version;
        this.keys = keys;
        this.indexByKey = new HashMap<>(hashCapacity(keys.length));
        for (int i = 0; i < keys.length; i++) {
            String key = keys[i];
            if (indexByKey.put(key, i) != null) {
                throw new IllegalArgumentException("Duplicate composite snapshot key: " + key);
            }
        }
    }

    static CompositeSnapshotLayout fromKeys(long version, List<String> keys) {
        Objects.requireNonNull(keys, "keys");
        return new CompositeSnapshotLayout(version, keys.toArray(String[]::new));
    }

    long version() {
        return version;
    }

    int size() {
        return keys.length;
    }

    String keyAt(int index) {
        return keys[index];
    }

    int indexOf(Object key) {
        Integer index = indexByKey.get(key);
        return index == null ? -1 : index;
    }

    private static int hashCapacity(int entryCount) {
        if (entryCount < 3) {
            return entryCount + 1;
        }
        return (int) Math.ceil(entryCount / 0.75d);
    }
}
