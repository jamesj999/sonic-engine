package com.openggf.tools.audio.parity;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROM-gated, comparison-only oracle over the committed per-song run windows.
 *
 * <p>Each fixture is one window of a pinned complete run: it opens at a
 * {@code Sound_PlayBGM} dispatch and closes at the next one, which is the ROM's
 * own boundary because {@code Sound_PlayBGM} reloads the driver's music track
 * RAM through {@code InitMusicPlayback} (s1.sounddriver.asm:1498-1502). A
 * window is therefore single-song, so the reference normalizes sequence
 * positions against one song's ROM asset range and
 * {@link S1OpenGgfSfxAudioCapture} drives one music sequencer loaded from the
 * window's own epoch song.
 *
 * <p>Every window carries a pinned expected outcome rather than a bare
 * assertion that it matches. A window that is green must stay green, and a
 * window whose frontier moves in either direction has to be recorded in
 * docs/status/audio-frontier-log.md rather than absorbed. The committed set
 * covers every distinct song each movie plays, so a song that agrees on one
 * recording and not the other is visible as such: cross-recording agreement is
 * the point, since the bar is any BK2 rather than one BK2.
 *
 * <p>This test never hydrates engine or gameplay state from a fixture. It
 * replays the reference's own recorded dispatch sequence through the real
 * {@link com.openggf.audio.driver.SmpsDriver} and compares driver state.
 */
class TestS1RunWindowAudioDriverOracle {
    private static final Path ROOT =
            Path.of("src/test/resources/audio/parity/s1/runs");

    /**
     * Pinned first divergence per window, or {@code MATCH}. Keyed by the
     * fixture's path relative to {@link #ROOT}. Sourced from the dated
     * measurement in docs/status/audio-frontier-log.md; update both together.
     */
    private static final Map<String, String> PINNED = S1RunWindowPins.PINNED;

    @TempDir
    Path temp;

    @TestFactory
    Stream<DynamicTest> everyCommittedWindowHoldsItsPinnedOutcome() throws IOException {
        Path rom = requiredRom();
        List<Path> fixtures = committedFixtures();
        assertTrue(fixtures.size() >= PINNED.size(),
                "a pinned window has no committed fixture: " + fixtures.size()
                        + " fixtures against " + PINNED.size() + " pins");
        List<DynamicTest> tests = new ArrayList<>();
        for (Path fixture : fixtures) {
            String key = ROOT.relativize(fixture).toString();
            tests.add(DynamicTest.dynamicTest(key, () -> {
                String expected = PINNED.get(key);
                assertTrue(expected != null,
                        "committed window has no pinned outcome, add one to S1RunWindowPins: " + key);
                AudioParityReport report = measure(fixture, rom);
                String actual = summarize(report);
                System.out.println("MEASUREMENT_ONLY s1-run-window " + key + ": " + actual);
                assertEquals(expected, actual, () -> "window " + key
                        + " moved; record it in docs/status/audio-frontier-log.md"
                        + " and update S1RunWindowPins\n" + report.toHumanText());
            }));
        }
        return tests.stream();
    }

    /**
     * The title-screen song, asserted by name and length rather than only
     * through the pins table, because it is the first whole song outside GHZ to
     * agree end to end and a regression in it should name itself.
     */
    @org.junit.jupiter.api.Test
    void titleScreenSongMatchesOverAllSeventyTwoTicks() throws Exception {
        Path rom = requiredRom();
        Path fixture = ROOT.resolve("s1-complete-run/w000-id8A.jsonl.gz");
        assertTrue(Files.isRegularFile(fixture), "committed 0x8A window is required");
        Path reference = decompress(fixture, temp.resolve("reference.jsonl"));
        Path openGgf = temp.resolve("openggf.jsonl");

        S1OpenGgfSfxAudioCapture.CaptureResult result =
                S1OpenGgfSfxAudioCapture.capture(reference, rom, openGgf);
        assertEquals(72, result.recordCount());

        AudioParityReport report = AudioParityComparator.compare(reference, openGgf);
        assertEquals(AudioParityReport.Kind.MATCH, report.kind(), report::toHumanText);
        assertEquals(72, report.ticksCompared());
    }

    /**
     * Corrupts a byte in a copy of one committed reference and confirms the
     * comparator reports it at the corrupted tick, proving these comparisons
     * are live rather than vacuously reporting a stale pin.
     */
    @TestFactory
    Stream<DynamicTest> corruptingAReferenceIsDetectedAtTheCorruptedTick() throws IOException {
        Path rom = requiredRom();
        List<Path> fixtures = committedFixtures();
        Assumptions.assumeFalse(fixtures.isEmpty(), "no committed run windows yet");
        Path fixture = fixtures.get(0);
        return Stream.of(DynamicTest.dynamicTest(ROOT.relativize(fixture).toString(), () -> {
            Path reference = decompress(fixture, temp.resolve("reference.jsonl"));
            Path openGgf = temp.resolve("openggf.jsonl");
            S1OpenGgfSfxAudioCapture.capture(reference, rom, openGgf);

            List<String> lines = Files.readAllLines(reference);
            String corrupted = lines.get(1).replaceFirst(
                    "\"register\":40,\"value\":2\\}", "\"register\":40,\"value\":3}");
            assertNotEquals(lines.get(1), corrupted,
                    "fixture no longer contains the byte this test flips");
            lines.set(1, corrupted);
            Path path = temp.resolve("reference-corrupted.jsonl");
            Files.write(path, lines, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            AudioParityReport report = AudioParityComparator.compare(path, openGgf);
            assertNotEquals(AudioParityReport.Kind.MATCH, report.kind());
            assertEquals(0, report.tickOrdinal().intValue(), "corruption was introduced at tick 0");
        }));
    }

    private AudioParityReport measure(Path fixture, Path rom) throws Exception {
        String stem = fixture.getFileName().toString().replace(".jsonl.gz", "");
        Path reference = decompress(fixture, temp.resolve(stem + "-reference.jsonl"));
        Path openGgf = temp.resolve(stem + "-openggf.jsonl");
        S1OpenGgfSfxAudioCapture.capture(reference, rom, openGgf);
        return AudioParityComparator.compare(reference, openGgf);
    }

    /** The one-line form the pins are written in and the log records. */
    private static String summarize(AudioParityReport report) {
        if (report.kind() == AudioParityReport.Kind.MATCH) {
            return "MATCH";
        }
        StringBuilder text = new StringBuilder(report.kind().name());
        if (report.tickOrdinal() != null) {
            text.append(" tick ").append(report.tickOrdinal());
        }
        if (report.role() != null) {
            text.append(" role ").append(report.role());
        }
        if (report.field() != null) {
            text.append(" field ").append(report.field());
        }
        if (report.referenceValue() != null || report.openGgfValue() != null) {
            text.append(" reference ").append(report.referenceValue())
                    .append(" against engine ").append(report.openGgfValue());
        }
        return text.toString();
    }

    private static List<Path> committedFixtures() throws IOException {
        if (!Files.isDirectory(ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(ROOT)) {
            return walk.filter(path -> path.getFileName().toString().endsWith(".jsonl.gz"))
                    .sorted()
                    .toList();
        }
    }

    private static Path decompress(Path gz, Path destination) throws IOException {
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
