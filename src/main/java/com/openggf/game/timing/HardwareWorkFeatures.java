package com.openggf.game.timing;

/** Deterministic compressed-stream metrics available before profile activation. */
public record HardwareWorkFeatures(
        int literalCommands,
        int shortCopyCommands,
        int longCopyCommands,
        int copiedOutputLength,
        int compressedLength,
        int decompressedLength,
        int moduleCount,
        int finalModuleSize,
        int coordinationCount) {

    public static final HardwareWorkFeatures EMPTY =
            new HardwareWorkFeatures(0, 0, 0, 0, 0, 0, 0, 0, 0);

    public HardwareWorkFeatures {
        if (literalCommands < 0 || shortCopyCommands < 0
                || longCopyCommands < 0 || copiedOutputLength < 0
                || compressedLength < 0 || decompressedLength < 0
                || moduleCount < 0 || finalModuleSize < 0
                || coordinationCount < 0) {
            throw new IllegalArgumentException("hardware work features must be non-negative");
        }
    }
}
