package com.openggf.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** An explicit reference lane must never appear successful because prerequisites skipped. */
@Tag("audio-reference")
class TestAudioReferencePrerequisites {
    @Test
    void externalBundleAndEveryRequiredRomArePresentAndVerified() throws Exception {
        JsonNode manifest;
        try (var input = getClass().getResourceAsStream("/audio/contracts/external-fixtures-v1.json")) {
            assertNotNull(input, "Public external-fixture digest contract is required");
            manifest = new ObjectMapper().readTree(input);
        }
        assertEquals("openggf.external-audio-fixtures.v1", manifest.path("schema").asText());
        assertTrue(manifest.path("files").isArray());
        var paths = new HashSet<String>();
        for (JsonNode file : manifest.path("files")) {
            String name = file.path("path").asText();
            assertTrue(paths.add(name), "duplicate fixture manifest member: " + name);
            Path path = AudioReferenceFixtures.require(name);
            assertEquals(file.path("bytes").asLong(-1), Files.size(path), name);
            assertEquals(file.path("sha256").asText(), digest(path, "SHA-256"), name);
        }
        assertEquals(98, paths.size(), "external bundle must retain 95 payload/metadata files, FM expectations and two manifest-referenced movies");
        for (var rom : Map.of(
                "sonic1.rom.path", "69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b",
                "sonic2.rom.path", "8bca5dcef1af3e00098666fd892dc1c2a76333f9",
                "s3k.rom.path", "cfbf98c36c776677290a872547ac47c53d2761d6").entrySet()) {
            String configured = System.getProperty(rom.getKey());
            assertNotNull(configured, "Audio reference coverage requires -D" + rom.getKey() + "=<ROM>");
            Path path = Path.of(configured);
            assertTrue(Files.isRegularFile(path), "Required ROM is absent: " + path);
            assertEquals(rom.getValue(), digest(path, "SHA-1"), "Wrong ROM identity: " + path);
        }
    }

    private static String digest(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (var stream = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = stream.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
