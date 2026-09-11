package com.openggf.trace;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Immutable, insertion-ordered {@code Map<String, Object>} backed by two
 * parallel arrays, for generic per-frame trace events ({@link
 * TraceEvent.StateSnapshot}). Complete-run aux files carry hundreds of
 * thousands of such events with identical key sets, so the key array is
 * deduplicated through a small shared pool and each map instance retains only
 * one values array plus one shared keys reference — roughly an order of
 * magnitude less heap than a {@code LinkedHashMap} per event.
 *
 * <p>Lookups scan linearly; events carry at most a few dozen fields, so this
 * is cheaper in practice than hashing.
 */
final class CompactFieldMap extends AbstractMap<String, Object> {

    /** Key sets recur per event shape; pool them so each shape is stored once. */
    private static final Map<List<String>, String[]> KEY_POOL = new ConcurrentHashMap<>();

    private final String[] keys;
    private final Object[] values;

    private CompactFieldMap(String[] keys, Object[] values) {
        this.keys = keys;
        this.values = values;
    }

    static CompactFieldMap of(List<String> keys, List<Object> values) {
        if (keys.size() != values.size()) {
            throw new IllegalArgumentException("keys/values size mismatch");
        }
        String[] pooledKeys = KEY_POOL.computeIfAbsent(
                List.copyOf(keys), k -> k.toArray(new String[0]));
        return new CompactFieldMap(pooledKeys, values.toArray());
    }

    @Override
    public Object get(Object key) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equals(key)) {
                return values[i];
            }
        }
        return null;
    }

    @Override
    public boolean containsKey(Object key) {
        for (String candidate : keys) {
            if (candidate.equals(key)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int size() {
        return keys.length;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return new AbstractSet<>() {
            @Override
            public int size() {
                return keys.length;
            }

            @Override
            public Iterator<Entry<String, Object>> iterator() {
                return new Iterator<>() {
                    private int index;

                    @Override
                    public boolean hasNext() {
                        return index < keys.length;
                    }

                    @Override
                    public Entry<String, Object> next() {
                        if (index >= keys.length) {
                            throw new NoSuchElementException();
                        }
                        int i = index++;
                        return new SimpleImmutableEntry<>(keys[i], values[i]);
                    }
                };
            }
        };
    }
}
