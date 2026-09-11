package com.openggf.game.sonic3k.resources;

/** Canonical ROM and RAM span for one S3K {@code Queue_Kos} submission. */
public record S3kKosDecompressionDescriptor(
        int sourceAddress,
        int compressedLength,
        int destinationAddress,
        int destinationLength) {
}
