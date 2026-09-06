package com.openggf.game.sonic3k.resources;

import com.openggf.data.Rom;
import com.openggf.data.compression.KosinskiInspectionCache;

import java.io.IOException;

/** Validates a direct/direct/module transition batch before its first FIFO mutation. */
public final class S3kKosTransitionPreflight {

    private S3kKosTransitionPreflight() {
    }

    public static void validate(
            Rom rom,
            S3kKosDecompressionQueue directQueue,
            S3kKosModuleQueue moduleQueue,
            int chunkSource,
            int blockSource,
            int artSource) throws IOException {
        if (directQueue.availableCapacity() < 2 || !moduleQueue.hasCapacity()) {
            throw new IllegalStateException(
                    "S3K transition Kos batch requires two direct slots and one module slot");
        }
        inspectStandard(rom, chunkSource);
        inspectStandard(rom, blockSource);
        inspectModuled(rom, artSource);
    }

    private static void inspectStandard(Rom rom, int source) throws IOException {
        KosinskiInspectionCache.inspectStandard(rom, source);
    }

    private static void inspectModuled(Rom rom, int source) throws IOException {
        KosinskiInspectionCache.inspectModuled(rom, source);
    }
}
