package com.openggf.audio.presentation;

import com.openggf.audio.WavDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Session-scoped decoded sample cache. It is populated at command boundaries, never by rendering.
 */
public final class DecodedPcmCache {
    private final Map<String, DecodedPcm> decoded = new HashMap<>();

    public DecodedPcm getOrDecode(String assetId, Supplier<? extends InputStream> sourceSupplier) throws IOException {
        String key = requireAssetId(assetId);
        DecodedPcm cached = decoded.get(key);
        if (cached != null) {
            return cached;
        }
        try (InputStream source = Objects.requireNonNull(sourceSupplier, "sourceSupplier").get()) {
            if (source == null) {
                throw new IOException("WAV source supplier returned null for " + key);
            }
            DecodedPcm result = WavDecoder.decodePcm(key, source);
            decoded.put(key, result);
            return result;
        }
    }

    public DecodedPcm registerUnsigned8Mono(String assetId, byte[] source, int sourceRate) {
        String key = requireAssetId(assetId);
        if (sourceRate <= 0) {
            throw new IllegalArgumentException("sourceRate must be positive");
        }
        Objects.requireNonNull(source, "source");
        short[] signed = new short[source.length];
        for (int index = 0; index < source.length; index++) {
            signed[index] = (short) (((source[index] & 0xFF) - 128) << 8);
        }
        DecodedPcm requested = new DecodedPcm(key, 1, sourceRate, signed);
        DecodedPcm existing = decoded.get(key);
        if (existing == null) {
            decoded.put(key, requested);
            return requested;
        }
        if (existing.channels() != 1 || existing.sampleRate() != sourceRate
                || !hasSamples(existing, signed)) {
            throw new IllegalArgumentException("assetId already registered with different PCM: " + key);
        }
        return existing;
    }

    public DecodedPcm get(String assetId) {
        return decoded.get(requireAssetId(assetId));
    }

    public void clear() {
        decoded.clear();
    }

    private static String requireAssetId(String assetId) {
        String value = Objects.requireNonNull(assetId, "assetId");
        if (value.isBlank()) {
            throw new IllegalArgumentException("assetId must not be blank");
        }
        return value;
    }

    private static boolean hasSamples(DecodedPcm pcm, short[] expected) {
        if (pcm.sourceFrames() != expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if (pcm.sample(index, 0) != expected[index]) {
                return false;
            }
        }
        return true;
    }
}
