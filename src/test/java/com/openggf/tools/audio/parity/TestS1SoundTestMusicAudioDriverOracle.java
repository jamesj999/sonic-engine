package com.openggf.tools.audio.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * ROM-gated, comparison-only oracle for the S1 sound-test GHZ music reference
 * ({@code s1-soundtest-ghz-reference.v1.jsonl.gz}, captured from
 * {@code s1-soundtest-ghz.bk2}; see
 * {@code src/test/resources/audio/parity/s1/fixture-manifest.json} for its
 * capture provenance). Until this test existed, the reference's own
 * {@code terminal_record_count} of 14,690 ticks was checked only through the
 * external {@code tools/audio/run_s1_audio_parity.sh} BizHawk wrapper
 * (docs/status/audio-frontier-log.md, 2026-09-03/04 "S1 GHZ music oracle"
 * regression gates), never by a committed JUnit test.
 *
 * <p>This test never hydrates engine or gameplay state from the fixture; it
 * replays the reference's own request/dispatch sequence through the real
 * {@link com.openggf.audio.driver.SmpsDriver} (via
 * {@link S1OpenGgfAudioCapture}, the same host {@code S1AudioParityTool
 * capture --capture music} and the shell wrapper use) and compares the
 * resulting driver state with {@link AudioParityComparator}.
 */
class TestS1SoundTestMusicAudioDriverOracle {
    private static final Path REFERENCE = Path.of(
            "src/test/resources/audio/parity/s1/s1-soundtest-ghz-reference.v1.jsonl.gz");

    @TempDir
    Path temp;

    @Test
    void wholeWindowMatches() throws Exception {
        Path rom = requiredRom();
        Path reference = decompress(REFERENCE, temp.resolve("reference.jsonl"));
        Path openGgf = temp.resolve("openggf.jsonl");

        S1OpenGgfAudioCapture.CaptureResult result =
                S1OpenGgfAudioCapture.capture(reference, rom, openGgf);
        assertEquals(14690, result.recordCount());

        AudioParityReport report = AudioParityComparator.compare(reference, openGgf);

        // Regression gate (docs/status/audio-frontier-log.md, S1 GHZ music
        // oracle): this whole-window comparison has stood at MATCH for the
        // fixture's full 14,690 ticks since it was first driven through the
        // shell wrapper; this assertion is the frontier pin now that a
        // committed JUnit test drives it directly.
        assertEquals(AudioParityReport.Kind.MATCH, report.kind(), report::toHumanText);
        assertEquals(14690, report.ticksCompared());
    }

    /**
     * Corrupts a byte in a copy of the committed reference and confirms the
     * comparator reports the corruption at the tick it lands on, proving the
     * comparison is live rather than vacuously green.
     */
    @Test
    void corruptingTheReferenceIsDetectedAtTheCorruptedTick() throws Exception {
        Path rom = requiredRom();
        Path reference = decompress(REFERENCE, temp.resolve("reference.jsonl"));
        Path openGgf = temp.resolve("openggf.jsonl");
        S1OpenGgfAudioCapture.capture(reference, rom, openGgf);

        List<String> lines = Files.readAllLines(reference);
        // Tick 0's line is index 1 (index 0 is the metadata line): flip the
        // first recorded YM2612 write's value so tick 0's events no longer
        // match (its first event is register 40 (0x28) key-on/off, value 2).
        String corruptedFirstTick = lines.get(1).replaceFirst(
                "\"register\":40,\"value\":2\\}", "\"register\":40,\"value\":3}");
        assertNotEquals(lines.get(1), corruptedFirstTick, "fixture no longer contains the byte this test flips");
        lines.set(1, corruptedFirstTick);
        Path corrupted = temp.resolve("reference-corrupted.jsonl");
        Files.write(corrupted, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

        AudioParityReport report = AudioParityComparator.compare(corrupted, openGgf);
        assertNotEquals(AudioParityReport.Kind.MATCH, report.kind());
        assertEquals(0, report.tickOrdinal(), "corruption was introduced at tick 0");
    }

    private static Path decompress(Path gz, Path destination) throws Exception {
        try (var input = new java.util.zip.GZIPInputStream(Files.newInputStream(gz))) {
            Files.copy(input, destination, StandardCopyOption.REPLACE_EXISTING);
        }
        return destination;
    }

    private static Path requiredRom() {
        String configured = System.getProperty("sonic1.rom.path");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(),
                "-Dsonic1.rom.path is required");
        return Path.of(configured);
    }
}
