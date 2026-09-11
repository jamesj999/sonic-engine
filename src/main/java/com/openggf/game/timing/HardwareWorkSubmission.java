package com.openggf.game.timing;

import java.util.Objects;

/** Canonical ROM-backed descriptor and runtime preparation for one hardware job. */
public record HardwareWorkSubmission(
        HardwareWorkKind kind,
        int romSourceAddress,
        int compressedLength,
        int destinationAddress,
        int destinationLength,
        String compressionVariant,
        int moduleCount,
        boolean exportableAcrossSegment,
        HardwareWorkFeatures features,
        HardwareWorkPreparation preparation) {

    public HardwareWorkSubmission(
            HardwareWorkKind kind,
            int romSourceAddress,
            int compressedLength,
            int destinationAddress,
            int destinationLength,
            String compressionVariant,
            int moduleCount,
            boolean exportableAcrossSegment,
            HardwareWorkPreparation preparation) {
        this(kind, romSourceAddress, compressedLength, destinationAddress,
                destinationLength, compressionVariant, moduleCount,
                exportableAcrossSegment, HardwareWorkFeatures.EMPTY, preparation);
    }

    public HardwareWorkSubmission {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(compressionVariant, "compressionVariant");
        Objects.requireNonNull(features, "features");
        Objects.requireNonNull(preparation, "preparation");
        if (compressedLength < 0) {
            throw new IllegalArgumentException("compressedLength must be non-negative");
        }
        if (destinationLength < 0) {
            throw new IllegalArgumentException("destinationLength must be non-negative");
        }
        if (moduleCount < 0) {
            throw new IllegalArgumentException("moduleCount must be non-negative");
        }
    }
}
