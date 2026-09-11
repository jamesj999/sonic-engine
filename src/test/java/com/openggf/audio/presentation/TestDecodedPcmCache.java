package com.openggf.audio.presentation;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestDecodedPcmCache {

    @Test
    void decodesOnlyOnceForAStableAssetIdentity() throws Exception {
        DecodedPcmCache cache = new DecodedPcmCache();
        AtomicInteger supplied = new AtomicInteger();

        DecodedPcm first = cache.getOrDecode("wav", () -> {
            supplied.incrementAndGet();
            return new ByteArrayInputStream(wav8Mono((byte) 128));
        });
        DecodedPcm second = cache.getOrDecode("wav", () -> {
            throw new AssertionError("cache miss");
        });

        assertSame(first, second);
        assertEquals(1, supplied.get());
    }

    @Test
    void rawRegistrationRejectsConflictingMetadataOrContent() {
        DecodedPcmCache cache = new DecodedPcmCache();
        cache.registerUnsigned8Mono("raw", new byte[] {1}, 8_000);

        assertThrows(IllegalArgumentException.class,
                () -> cache.registerUnsigned8Mono("raw", new byte[] {1}, 16_000));
        assertThrows(IllegalArgumentException.class,
                () -> cache.registerUnsigned8Mono("raw", new byte[] {2}, 8_000));
    }

    @Test
    void clearRemovesSessionOwnedAssets() {
        DecodedPcmCache cache = new DecodedPcmCache();
        cache.registerUnsigned8Mono("raw", new byte[] {1}, 8_000);

        cache.clear();

        assertEquals(null, cache.get("raw"));
    }

    private static byte[] wav8Mono(byte sample) {
        return new byte[] {
                'R', 'I', 'F', 'F', 38, 0, 0, 0, 'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ', 16, 0, 0, 0, 1, 0, 1, 0,
                64, 31, 0, 0, 64, 31, 0, 0, 1, 0, 8, 0,
                'd', 'a', 't', 'a', 1, 0, 0, 0, sample, 0
        };
    }
}
