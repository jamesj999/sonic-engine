package com.openggf.level.resources;

/**
 * Exact identity of a level-resource operation whose bytes are already owned
 * by a production runtime job.
 */
public record DeferredLevelResourceDescriptor(
        Kind kind,
        int romSourceAddress,
        CompressionType compressionType,
        int destinationAddress) {

    public enum Kind {
        PATTERNS_8X8,
        CHUNKS_16X16,
        BLOCKS_128X128
    }

    public DeferredLevelResourceDescriptor {
        if (kind == null || compressionType == null) {
            throw new IllegalArgumentException(
                    "deferred resource kind and compression are required");
        }
        if (romSourceAddress < 0) {
            throw new IllegalArgumentException(
                    "deferred resource ROM source must be non-negative");
        }
    }
}
