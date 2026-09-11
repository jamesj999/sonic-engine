package com.openggf.game.sonic3k.resources;

import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.data.compression.DecoderSnapshot;
import com.openggf.data.compression.ResumableKosinskiDecoder;

import java.util.Objects;

/** Rewind state for one active standard-Kosinski direct stream. */
public record S3kKosDecompressionSnapshot(
        S3kKosDecompressionDescriptor descriptor,
        byte[] compressedBytes,
        DecoderSnapshot decoder) implements HardwareWorkPreparationSnapshot {
    public S3kKosDecompressionSnapshot {
        Objects.requireNonNull(descriptor, "descriptor");
        compressedBytes = Objects.requireNonNull(compressedBytes, "compressedBytes").clone();
        Objects.requireNonNull(decoder, "decoder");
    }

    @Override
    public byte[] compressedBytes() {
        return compressedBytes.clone();
    }

    @Override
    public HardwareWorkPreparation recreatePreparation() {
        return S3kKosDecompressionQueue.recreatePreparation(this);
    }

    ResumableKosinskiDecoder recreateDecoder() {
        return ResumableKosinskiDecoder.fromSnapshot(decoder);
    }
}
