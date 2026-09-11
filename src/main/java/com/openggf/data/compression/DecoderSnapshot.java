package com.openggf.data.compression;

import java.util.Objects;

/** Complete immutable state for a resumable standard-Kosinski module decoder. */
public record DecoderSnapshot(
        byte[] input,
        int moduleStart,
        int readPosition,
        int descriptor,
        int descriptorBitsRemaining,
        byte[] output,
        boolean complete) {

    public DecoderSnapshot {
        input = Objects.requireNonNull(input, "input").clone();
        output = Objects.requireNonNull(output, "output").clone();
    }

    @Override
    public byte[] input() {
        return input.clone();
    }

    @Override
    public byte[] output() {
        return output.clone();
    }
}
