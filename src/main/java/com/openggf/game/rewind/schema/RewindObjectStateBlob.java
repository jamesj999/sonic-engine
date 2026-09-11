package com.openggf.game.rewind.schema;

import java.util.Arrays;
import java.util.Objects;

public final class RewindObjectStateBlob {
    private final int schemaId;
    private final Class<?> type;
    private final byte[] scalarData;
    private final Object[] opaqueValues;

    public RewindObjectStateBlob(int schemaId, Class<?> type, byte[] scalarData, Object[] opaqueValues) {
        this(schemaId, type,
                Arrays.copyOf(Objects.requireNonNull(scalarData, "scalarData"), scalarData.length),
                Arrays.copyOf(Objects.requireNonNull(opaqueValues, "opaqueValues"), opaqueValues.length),
                true);
    }

    private RewindObjectStateBlob(
            int schemaId, Class<?> type, byte[] scalarData, Object[] opaqueValues, boolean transferOwnership) {
        this.schemaId = schemaId;
        this.type = Objects.requireNonNull(type, "type");
        this.scalarData = scalarData;
        this.opaqueValues = opaqueValues;
    }

    /**
     * Wraps the given arrays as the blob's backing store with NO defensive
     * copy, mirroring {@code CompositeSnapshot.owned}. Ownership transfers to
     * the blob; the caller must not retain or mutate the arrays afterwards.
     * Package-private — only the capture hot path should call this.
     */
    static RewindObjectStateBlob owned(int schemaId, Class<?> type, byte[] scalarData, Object[] opaqueValues) {
        return new RewindObjectStateBlob(
                schemaId, type,
                Objects.requireNonNull(scalarData, "scalarData"),
                Objects.requireNonNull(opaqueValues, "opaqueValues"),
                true);
    }

    public int schemaId() {
        return schemaId;
    }

    public Class<?> type() {
        return type;
    }

    public byte[] scalarData() {
        return Arrays.copyOf(scalarData, scalarData.length);
    }

    public Object[] opaqueValues() {
        return Arrays.copyOf(opaqueValues, opaqueValues.length);
    }

    /**
     * Restore-path reader over the blob's own immutable bytes — no clone of
     * the scalar array. Package-private; callers only read through it.
     */
    RewindStateBuffer.Reader scalarReader() {
        return RewindStateBuffer.sharedReader(scalarData);
    }

    /**
     * Restore-path view of the stored opaque values without cloning the
     * array. Package-private; callers must treat it as read-only.
     */
    Object[] opaqueValuesShared() {
        return opaqueValues;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof RewindObjectStateBlob other)) return false;
        return schemaId == other.schemaId
                && type == other.type
                && Arrays.equals(scalarData, other.scalarData)
                && Arrays.equals(opaqueValues, other.opaqueValues);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schemaId, type, Arrays.hashCode(scalarData), Arrays.hashCode(opaqueValues));
    }
}
