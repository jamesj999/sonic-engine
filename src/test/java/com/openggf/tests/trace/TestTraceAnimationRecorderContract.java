package com.openggf.tests.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.trace.TraceMetadata;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;

/** Consumer-owned checks over OpenGGF's canonical trace fixtures. */
class TestTraceAnimationRecorderContract {
    @Test
    void allCommittedGameplayFixturesCarryV5AnimationCsv() throws IOException {
        Map<String, Integer> expectedCounts = Map.of("s1", 21, "s2", 19, "s3k", 21);
        for (Map.Entry<String, Integer> entry : expectedCounts.entrySet()) {
            Path gameRoot = Path.of("src/test/resources/traces", entry.getKey());
            List<Path> fixtures;
            try (var paths = Files.walk(gameRoot, 2)) {
                fixtures = paths.filter(path -> path.getFileName().toString().equals("metadata.json"))
                        .map(Path::getParent)
                        .filter(path -> !path.getFileName().toString().startsWith("credits_"))
                        .filter(path -> !path.getFileName().toString().equals("special_stage"))
                        .sorted().toList();
            }
            assertEquals(entry.getValue(), fixtures.size(), entry.getKey());
            for (Path fixture : fixtures) {
                TraceMetadata metadata = TraceMetadata.load(fixture.resolve("metadata.json"));
                assertEquals(5, metadata.traceSchema(), fixture.toString());
                String header = readPhysicsHeader(fixture);
                int columns = header.split(",", -1).length;
                assertTrue(columns == 42 || columns == 43, fixture + " has " + columns + " columns");
                assertEquals(columns == 43, header.endsWith("life_count"));
                assertTrue(header.contains("player_animation_id"), fixture.toString());
                assertTrue(header.contains("sidekick_mapping_frame"), fixture.toString());
            }
        }
    }

    private static String readPhysicsHeader(Path fixture) throws IOException {
        Path plain = fixture.resolve("physics.csv");
        Path path = Files.exists(plain) ? plain : fixture.resolve("physics.csv.gz");
        InputStream raw = Files.newInputStream(path);
        try (InputStream input = path.toString().endsWith(".gz") ? new GZIPInputStream(raw) : raw;
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return reader.readLine();
        }
    }
}
