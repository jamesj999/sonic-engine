package com.openggf.game.rewind.schema;

import com.openggf.game.rewind.FieldKey;

import java.lang.reflect.Field;
import java.util.Objects;

public record RewindFieldPlan(
        FieldKey key,
        Field field,
        RewindFieldPolicy policy,
        RewindCodec codec,
        RewindScalarTag primitiveScalarTag) {

    public RewindFieldPlan(FieldKey key, Field field, RewindFieldPolicy policy) {
        this(key, field, policy, null);
    }

    public RewindFieldPlan(FieldKey key, Field field, RewindFieldPolicy policy, RewindCodec codec) {
        this(key, field, policy, codec, null);
    }

    public RewindFieldPlan {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(policy, "policy");
        if (policy == RewindFieldPolicy.CAPTURED && codec == null) {
            throw new IllegalArgumentException("Captured rewind field requires a codec: " + key);
        }
        if (policy == RewindFieldPolicy.CAPTURED) {
            field.setAccessible(true);
        }
        // Always derived: the fast path is only byte-compatible with the plain
        // scalar codec's primitive layout (no null marker), so the tag is
        // normalized here rather than trusted from the caller.
        primitiveScalarTag = computePrimitiveScalarTag(field, policy, codec);
    }

    public boolean captured() {
        return policy == RewindFieldPolicy.CAPTURED;
    }

    /**
     * True when this field can capture/restore through the typed primitive
     * fast path, bypassing codec dispatch and boxed {@code Field.get}/
     * {@code Field.set}. Byte-identical to the scalar codec's primitive path.
     */
    public boolean hasPrimitiveFastPath() {
        return primitiveScalarTag != null;
    }

    public void capturePrimitive(Object target, RewindStateBuffer scalarData) {
        try {
            switch (primitiveScalarTag) {
                case BOOLEAN -> scalarData.writeBoolean(field.getBoolean(target));
                case BYTE -> scalarData.writeByte(field.getByte(target));
                case CHAR -> scalarData.writeShort(field.getChar(target));
                case SHORT -> scalarData.writeShort(field.getShort(target));
                case INT -> scalarData.writeInt(field.getInt(target));
                case LONG -> scalarData.writeLong(field.getLong(target));
                case FLOAT -> scalarData.writeFloat(field.getFloat(target));
                case DOUBLE -> scalarData.writeDouble(field.getDouble(target));
                // A silently unwritten tag would misalign the buffer.
                default -> throw new IllegalStateException(
                        "Unhandled scalar tag " + primitiveScalarTag + " for " + field);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot read rewind field " + field, e);
        }
    }

    public void restorePrimitive(Object target, RewindStateBuffer.Reader scalarData) {
        try {
            switch (primitiveScalarTag) {
                case BOOLEAN -> field.setBoolean(target, scalarData.readBoolean());
                case BYTE -> field.setByte(target, scalarData.readByte());
                case CHAR -> field.setChar(target, (char) (scalarData.readShort() & 0xFFFF));
                case SHORT -> field.setShort(target, scalarData.readShort());
                case INT -> field.setInt(target, scalarData.readInt());
                case LONG -> field.setLong(target, scalarData.readLong());
                case FLOAT -> field.setFloat(target, scalarData.readFloat());
                case DOUBLE -> field.setDouble(target, scalarData.readDouble());
                // A silently unread tag would misalign the buffer.
                default -> throw new IllegalStateException(
                        "Unhandled scalar tag " + primitiveScalarTag + " for " + field);
            }
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot write rewind field " + field, e);
        }
    }

    private static RewindScalarTag computePrimitiveScalarTag(
            Field field,
            RewindFieldPolicy policy,
            RewindCodec codec) {

        if (policy != RewindFieldPolicy.CAPTURED
                || !field.getType().isPrimitive()
                || !RewindCodecs.isPlainScalarCodec(codec)) {
            return null;
        }
        return RewindScalarTag.forType(field.getType());
    }
}
