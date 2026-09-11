package com.openggf.game.sonic3k.resources;

/** Canonical ROM and destination span for one S3K Kosinski-module submission. */
public record S3kKosModuleDescriptor(
        int sourceAddress,
        int compressedLength,
        int destinationAddress,
        int destinationLength,
        int moduleCount) {
}
