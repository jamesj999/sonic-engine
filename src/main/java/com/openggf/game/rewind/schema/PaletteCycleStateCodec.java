package com.openggf.game.rewind.schema;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Reflection-based snapshot codec for opaque cycle state holders such as the
 * inner {@code PaletteCycle} subclasses owned by {@code Sonic2PaletteCycler}
 * and {@code Sonic3kPaletteCycler}.
 *
 * <p>Captures all non-static, non-final, primitive instance fields in
 * deterministic (name-sorted) order. This deliberately ignores object-reference
 * fields (palette data buffers, level references) which are either immutable
 * after construction or owned externally.
 *
 * <p>The format is self-describing in size only — the caller must restore into
 * an instance whose field layout matches what was captured. In practice this
 * holds because the cycles registered for a given zone/act are deterministic.
 */
public final class PaletteCycleStateCodec {

    private PaletteCycleStateCodec() {
    }

    /** Returns the captured bytes for the given target, or an empty array if null. */
    public static byte[] capture(Object target) {
        if (target == null) {
            return new byte[0];
        }
        List<Field> fields = mutablePrimitiveFields(target.getClass());
        int size = computeSize(fields);
        ByteBuffer buf = ByteBuffer.allocate(size);
        for (Field field : fields) {
            try {
                Class<?> type = field.getType();
                if (type == int.class) {
                    buf.putInt(field.getInt(target));
                } else if (type == long.class) {
                    buf.putLong(field.getLong(target));
                } else if (type == short.class) {
                    buf.putShort(field.getShort(target));
                } else if (type == byte.class) {
                    buf.put(field.getByte(target));
                } else if (type == boolean.class) {
                    buf.put((byte) (field.getBoolean(target) ? 1 : 0));
                } else if (type == float.class) {
                    buf.putFloat(field.getFloat(target));
                } else if (type == double.class) {
                    buf.putDouble(field.getDouble(target));
                } else if (type == char.class) {
                    buf.putChar(field.getChar(target));
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to capture field " + field, e);
            }
        }
        return buf.array();
    }

    /** Restores the captured bytes into the target. Tolerant of null/short inputs. */
    public static void restore(Object target, byte[] data) {
        if (target == null || data == null || data.length == 0) {
            return;
        }
        List<Field> fields = mutablePrimitiveFields(target.getClass());
        int expected = computeSize(fields);
        if (data.length < expected) {
            // Layout mismatch — refuse to restore partial state rather than corrupt the target.
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(data);
        for (Field field : fields) {
            try {
                Class<?> type = field.getType();
                if (type == int.class) {
                    field.setInt(target, buf.getInt());
                } else if (type == long.class) {
                    field.setLong(target, buf.getLong());
                } else if (type == short.class) {
                    field.setShort(target, buf.getShort());
                } else if (type == byte.class) {
                    field.setByte(target, buf.get());
                } else if (type == boolean.class) {
                    field.setBoolean(target, buf.get() != 0);
                } else if (type == float.class) {
                    field.setFloat(target, buf.getFloat());
                } else if (type == double.class) {
                    field.setDouble(target, buf.getDouble());
                } else if (type == char.class) {
                    field.setChar(target, buf.getChar());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Failed to restore field " + field, e);
            }
        }
    }

    /** Returns the byte width of {@link #capture(Object)} output for the target's class. */
    public static int sizeOf(Object target) {
        if (target == null) {
            return 0;
        }
        return computeSize(mutablePrimitiveFields(target.getClass()));
    }

    private static int computeSize(List<Field> fields) {
        int size = 0;
        for (Field field : fields) {
            Class<?> type = field.getType();
            if (type == int.class || type == float.class) {
                size += 4;
            } else if (type == long.class || type == double.class) {
                size += 8;
            } else if (type == short.class || type == char.class) {
                size += 2;
            } else if (type == byte.class || type == boolean.class) {
                size += 1;
            }
        }
        return size;
    }

    private static List<Field> mutablePrimitiveFields(Class<?> cls) {
        List<Field> result = new ArrayList<>();
        // Walk declared fields on this class and all superclasses up to Object.
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                int mods = field.getModifiers();
                if (Modifier.isStatic(mods) || Modifier.isFinal(mods)) {
                    continue;
                }
                if (!field.getType().isPrimitive()) {
                    continue;
                }
                field.setAccessible(true);
                result.add(field);
            }
        }
        // Sort by qualified name (declaring class + field name) so the order is stable
        // even when a subclass shadows a field name from its parent.
        result.sort(Comparator.<Field, String>comparing(f -> f.getDeclaringClass().getName())
                .thenComparing(Field::getName));
        return result;
    }
}
