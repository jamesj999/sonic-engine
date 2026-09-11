package com.openggf.game.rewind.snapshot;

import com.openggf.game.rewind.FieldKey;
import com.openggf.game.rewind.GenericFieldCapturer;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record GenericObjectSnapshot(Class<?> type, List<FieldKey> keys, Object[] values) {
    public GenericObjectSnapshot {
        Objects.requireNonNull(type, "type");
        keys = List.copyOf(Objects.requireNonNull(keys, "keys"));
        values = deepCopyValues(Objects.requireNonNull(values, "values"));
        if (keys.size() != values.length) {
            throw new IllegalArgumentException("keys/values length mismatch");
        }
    }

    @Override
    public Object[] values() {
        return deepCopyValues(values);
    }

    public Object value(FieldKey key) {
        int idx = keys.indexOf(key);
        return idx < 0 ? null : GenericFieldCapturer.deepCopySnapshotValue(values[idx]);
    }

    private static Object[] deepCopyValues(Object[] source) {
        Object[] copy = source.clone();
        for (int i = 0; i < copy.length; i++) {
            copy[i] = GenericFieldCapturer.deepCopySnapshotValue(copy[i]);
        }
        return copy;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof GenericObjectSnapshot other
                && type.equals(other.type)
                && keys.equals(other.keys)
                && Arrays.deepEquals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hash(type, keys) + Arrays.deepHashCode(values);
    }
}
