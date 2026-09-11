package com.openggf.tools.audio.parity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

class TestS1AudioParityFixtureContract {
    private static final String EXPECTED_SHA256 =
            "622ff642d0b0835a4f77bee568f2413f288ead3306a8bc2a93e8d8f77f24ca9c";
    private static final Set<String> EXPECTED_ENTRIES = Set.of(
            "BizState 1.0", "BizVersion.txt", "Header.txt", "Comments.txt",
            "Subtitles.txt", "SyncSettings.json", "Input Log.txt");
    private static final Path FIXTURE = Path.of(
            "src/test/resources/audio/parity/s1/s1-soundtest-ghz.bk2");

    @Test
    void controllerOnlySoundTestMovieHasPinnedContract() throws Exception {
        assertTrue(Files.isRegularFile(FIXTURE), "tracked S1 parity BK2 is required");
        assertEquals(EXPECTED_SHA256, sha256(FIXTURE));

        try (ZipFile movie = new ZipFile(FIXTURE.toFile())) {
            Set<String> actualEntries = new HashSet<>();
            movie.stream().forEach(entry -> actualEntries.add(entry.getName()));
            assertEquals(EXPECTED_ENTRIES, actualEntries);

            for (ZipEntry entry : java.util.Collections.list(movie.entries())) {
                String name = entry.getName().toLowerCase(java.util.Locale.ROOT);
                assertFalse(name.contains("savestate"), "savestate payload is forbidden");
                assertFalse(name.endsWith(".gen") || name.endsWith(".bin") || name.endsWith(".rom"),
                        "ROM payload is forbidden");
                assertTrue(entry.getSize() >= 0 && entry.getSize() <= 1_000_000,
                        "unexpected large archive entry: " + entry.getName());
            }

            String header = read(movie, "Header.txt");
            assertEquals("Version 2.11\n", read(movie, "BizVersion.txt"));
            assertTrue(header.contains("MovieVersion BizHawk v2.0.0"));
            assertTrue(header.contains("emuVersion Version 2.11"));
            assertTrue(header.contains("Core Genplus-gx"));
            assertTrue(header.contains("GameName Sonic The Hedgehog (W) (REV01) [!]"));
            assertTrue(read(movie, "SyncSettings.json").contains("gpgx.GPGX"));
            String sha1 = header.lines().filter(line -> line.startsWith("SHA1 ")).findFirst().orElseThrow();
            assertTrue(sha1.substring(5).matches("[0-9A-Fa-f]{32}"), "SHA1 header is opaque metadata");

            String input = read(movie, "Input Log.txt");
            assertEquals(992, input.lines().count());
            assertEquals(1, input.lines().filter("[Input]"::equals).count());
            assertEquals(1, input.lines().filter(line -> line.startsWith("LogKey:")).count());
            assertEquals(1, input.lines().filter("[/Input]"::equals).count());
            assertEquals(989, input.lines().filter(line -> line.startsWith("|")).count());
        }
    }

    /**
     * Every committed movie and compressed reference capture is pinned by
     * {@code fixture-manifest.json}: identity hashes, sizes, capture kinds and
     * the recorder probe. A fixture that drifts, or a new capture dropped into
     * the directory without a manifest entry, fails here.
     */
    @Test
    void manifestPinsEveryCommittedAudioParityFixture() throws Exception {
        Path root = FIXTURE.getParent();
        Path manifestPath = root.resolve("fixture-manifest.json");
        assertTrue(Files.isRegularFile(manifestPath), "fixture-manifest.json is required");
        com.fasterxml.jackson.databind.JsonNode manifest =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(manifestPath.toFile());
        assertEquals("openggf.s1_audio_parity_fixture_manifest.v1", manifest.get("schema").asText());
        assertEquals(AudioParitySchema.S1_REV01_SHA1, manifest.get("rom").get("sha1").asText());
        assertEquals(AudioParitySchema.S1_REV01_CRC32, manifest.get("rom").get("crc32").asText());

        Set<String> pinnedMovies = new HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode movie : manifest.get("movies")) {
            Path file = root.resolve(movie.get("file").asText());
            assertTrue(Files.isRegularFile(file), "pinned movie missing: " + file);
            assertEquals(movie.get("sha256").asText(), sha256(file), file.toString());
            try (ZipFile archive = new ZipFile(file.toFile())) {
                String input = read(archive, "Input Log.txt");
                assertEquals(movie.get("input_rows").asLong(),
                        input.lines().filter(line -> line.startsWith("|")).count(), file.toString());
            }
            pinnedMovies.add(movie.get("file").asText());
        }

        Set<String> pinnedReferences = new HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode reference : manifest.get("references")) {
            Path file = root.resolve(reference.get("file").asText());
            assertTrue(Files.isRegularFile(file), "pinned reference missing: " + file);
            assertTrue(pinnedMovies.contains(reference.get("movie").asText()),
                    "reference must cite a pinned movie: " + file);
            Path probe = Path.of(reference.get("recorder_probe").asText());
            assertTrue(Files.isRegularFile(probe), "pinned recorder probe missing: " + probe);

            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            try (InputStream input = new java.util.zip.GZIPInputStream(Files.newInputStream(file))) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, count);
                    bytes += count;
                }
            }
            assertEquals(reference.get("uncompressed_sha256").asText(),
                    HexFormat.of().formatHex(digest.digest()), file.toString());
            assertEquals(reference.get("uncompressed_bytes").asLong(), bytes, file.toString());

            try (java.io.BufferedReader input = AudioParityJsonl.openReader(file)) {
                AudioParityMetadata metadata = AudioParityJsonl.parseMetadata(input.readLine());
                assertEquals(reference.get("capture").asText(), metadata.capture(), file.toString());
                assertEquals(reference.get("terminal_record_count").asInt(),
                        metadata.terminalRecordCount(), file.toString());
                assertEquals(reference.get("cycle_start").asInt(), metadata.cycleStart(),
                        file.toString());
                assertEquals(reference.get("period").asInt(), metadata.period(), file.toString());
            }
            pinnedReferences.add(reference.get("file").asText());
        }

        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().endsWith(".jsonl.gz"))
                    .forEach(path -> assertTrue(
                            pinnedReferences.contains(path.getFileName().toString()),
                            "committed reference capture is not pinned by the manifest: " + path));
        }
    }

    private static String read(ZipFile movie, String name) throws IOException {
        ZipEntry entry = movie.getEntry(name);
        assertNotNull(entry);
        try (InputStream stream = movie.getInputStream(entry)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
