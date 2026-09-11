package com.openggf.game.dataselect;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCrossGameDataSelectNativeImageMetadata {

    @Test
    void retainsS3kDonorBootstrapClassForReflectiveLoading() throws IOException {
        try (var input = getClass().getClassLoader().getResourceAsStream(
                "META-INF/native-image/com.openggf/OpenGGF/reflect-config.json")) {
            String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            assertTrue(metadata.contains("com.openggf.game.sonic3k.Sonic3kGameModule"),
                    "The native image must retain the class that registers the S3K data-select donor");
        }
    }
}
