package com.openggf.game.rewind.schema;

import java.util.Map;

/**
 * Precomputed scalar type tag so compact capture/restore can switch once
 * instead of re-walking {@code type ==} chains per field per frame. The
 * wire format is unchanged: each tag writes/reads exactly the bytes the
 * historical {@code writeScalar}/{@code readScalar} chains produced.
 *
 * <p>When adding a constant, update every tag switch:
 * {@code RewindFieldPlan.capturePrimitive}/{@code restorePrimitive},
 * {@code RewindCodecs.ScalarCodec.capturePrimitive}/{@code restorePrimitive},
 * {@code RewindCodecs.writeScalar(RewindScalarTag, ...)} /
 * {@code readScalar(RewindScalarTag, ...)}, and the {@code BY_TYPE} map
 * below. The statement switches guard with {@code default -> throw}; only
 * the {@code readScalar} expression switch is compiler-enforced.
 */
public enum RewindScalarTag {
    BOOLEAN,
    BYTE,
    CHAR,
    SHORT,
    INT,
    LONG,
    FLOAT,
    DOUBLE;

    private static final Map<Class<?>, RewindScalarTag> BY_TYPE = Map.ofEntries(
            Map.entry(boolean.class, BOOLEAN), Map.entry(Boolean.class, BOOLEAN),
            Map.entry(byte.class, BYTE), Map.entry(Byte.class, BYTE),
            Map.entry(char.class, CHAR), Map.entry(Character.class, CHAR),
            Map.entry(short.class, SHORT), Map.entry(Short.class, SHORT),
            Map.entry(int.class, INT), Map.entry(Integer.class, INT),
            Map.entry(long.class, LONG), Map.entry(Long.class, LONG),
            Map.entry(float.class, FLOAT), Map.entry(Float.class, FLOAT),
            Map.entry(double.class, DOUBLE), Map.entry(Double.class, DOUBLE));

    /** Tag for a primitive or wrapper scalar type, or {@code null} otherwise. */
    public static RewindScalarTag forType(Class<?> type) {
        return BY_TYPE.get(type);
    }
}
