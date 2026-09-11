package com.openggf.game.rewind.schema;

import java.util.Objects;

/**
 * Serializes a zone-event handler's mutable state via the rewind schema
 * system, replacing hand-counted byte layouts in
 * {@code Sonic3kLevelEventManager.captureExtra()}.
 *
 * <p>Handlers must annotate structural fields (services, managers, level
 * references, immutable tables) with {@code @RewindTransient}; every
 * remaining field must have a codec or the schema rejects the class at
 * first capture. Scalar-only by design: a handler whose schema produces
 * opaque values (e.g. String fields) needs explicit codec/policy work
 * before conversion.
 */
public final class ZoneEventSchemaSidecar {

    private ZoneEventSchemaSidecar() {
    }

    public static byte[] capture(Object handler) {
        Objects.requireNonNull(handler, "handler");
        RewindObjectStateBlob blob = CompactFieldCapturer.capture(handler);
        if (blob.opaqueValues().length != 0) {
            throw new IllegalStateException("Zone-event sidecar for "
                    + handler.getClass().getName()
                    + " produced opaque values; scalar-only fields required");
        }
        return blob.scalarData();
    }

    public static void restore(Object handler, byte[] bytes) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(bytes, "bytes");
        int expectedLength = capture(handler).length;
        if (bytes.length != expectedLength) {
            throw new IllegalArgumentException("Zone-event sidecar for "
                    + handler.getClass().getName()
                    + " expected " + expectedLength + " scalar bytes but got " + bytes.length);
        }
        restoreWithoutLengthPrecheck(handler, bytes);
    }

    /**
     * Restores a sidecar whose scalar byte width can legitimately vary with
     * captured state, such as handlers with non-final arrays. Callers must
     * roll back the target on failure if partial restore side effects matter.
     */
    public static void restoreVariableLength(Object handler, byte[] bytes) {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(bytes, "bytes");
        restoreWithoutLengthPrecheck(handler, bytes);
    }

    public static boolean hasValidVariableLengthPayload(Class<?> handlerType, byte[] bytes) {
        Objects.requireNonNull(handlerType, "handlerType");
        Objects.requireNonNull(bytes, "bytes");
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(handlerType);
        if (!schema.unsupportedFields().isEmpty()) {
            return false;
        }
        RewindStateBuffer.Reader reader =
                new RewindObjectStateBlob(schema.schemaId(), handlerType, bytes, new Object[0]).scalarReader();
        try {
            for (RewindFieldPlan plan : schema.capturedFields()) {
                skipScalarField(reader, plan.field());
            }
            return reader.remaining() == 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void restoreWithoutLengthPrecheck(Object handler, byte[] bytes) {
        RewindClassSchema schema = RewindSchemaRegistry.schemaFor(handler.getClass());
        CompactFieldCapturer.restore(handler,
                new RewindObjectStateBlob(schema.schemaId(), handler.getClass(), bytes, new Object[0]));
        validateNoNullEnumFields(handler, schema);
    }

    private static void skipScalarField(RewindStateBuffer.Reader reader, java.lang.reflect.Field field) {
        Class<?> type = field.getType();
        if (type.isPrimitive()) {
            skipScalar(reader, RewindScalarTag.forType(type));
            return;
        }
        if (type.isEnum()) {
            reader.readInt();
            return;
        }
        if (type.isArray()) {
            skipArray(reader, type.getComponentType());
            return;
        }
        RewindScalarTag scalarTag = RewindScalarTag.forType(type);
        if (scalarTag != null) {
            if (reader.readBoolean()) {
                skipScalar(reader, scalarTag);
            }
            return;
        }
        throw new IllegalArgumentException("Unsupported variable-length zone-event field type: " + field);
    }

    private static void skipArray(RewindStateBuffer.Reader reader, Class<?> componentType) {
        int length = reader.readInt();
        if (length < 0) {
            return;
        }
        for (int i = 0; i < length; i++) {
            if (!componentType.isPrimitive() && !reader.readBoolean()) {
                continue;
            }
            if (componentType.isEnum()) {
                reader.readInt();
            } else {
                skipScalar(reader, RewindScalarTag.forType(componentType));
            }
        }
    }

    private static void skipScalar(RewindStateBuffer.Reader reader, RewindScalarTag tag) {
        if (tag == null) {
            throw new IllegalArgumentException("Unsupported variable-length zone-event scalar type");
        }
        switch (tag) {
            case BOOLEAN, BYTE -> reader.readByte();
            case CHAR, SHORT -> reader.readShort();
            case INT, FLOAT -> reader.readInt();
            case LONG, DOUBLE -> reader.readLong();
        }
    }

    private static void validateNoNullEnumFields(Object handler, RewindClassSchema schema) {
        for (RewindFieldPlan plan : schema.capturedFields()) {
            if (!plan.field().getType().isEnum()) {
                continue;
            }
            try {
                if (plan.field().get(handler) == null) {
                    throw new IllegalArgumentException("Zone-event sidecar for "
                            + handler.getClass().getName()
                            + " restored null enum field " + plan.field().getName());
                }
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot inspect rewind enum field " + plan.field(), e);
            }
        }
    }
}
