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
 * ROM-gated, comparison-only oracle for the S1 gameplay driver reference
 * fixture ({@code s1-gameplay-ghz1-reference.v3.jsonl.gz}) -- the first S1
 * audio oracle sourced from a real playthrough (the pinned complete-run movie
 * {@code sonic1-complete-withemeralds.bk2}, power-on through early GHZ1 play)
 * rather than a scripted sound-test movie. See
 * {@code src/test/resources/audio/parity/s1/fixture-manifest.json} for the
 * fixture's capture provenance.
 *
 * <p>v3 supersedes v2, which superseded v1. v2 added a second dispatch hook
 * at {@code Sound_PlaySpecial} (docs/s1disasm/s1.sounddriver.asm:1117) so the
 * fixture records the GHZ waterfall special-SFX dispatch v1 could not see.
 * v3 replaces v2's arbitrary frame-3000 bound with the window's real
 * boundary: the capture now ends at the first post-epoch music request, which
 * in this movie is the GHZ1 invincibility monitor dispatching
 * {@code bgm_Invincible} ($87, docs/s1disasm/_Constants.asm:344) at frame
 * 3,219. That request is where the single-song oracle contract stops being
 * able to describe the ROM: {@code Sound_PlayBGM} reloads driver RAM with a
 * different song, and both the reference normalization and this test's engine
 * host are pinned to one song's asset range. v2 stopped short of it only
 * because the probe raised there and BizHawk's Lua host exits the client
 * before a probe error can surface. v1 and v2 bytes remain committed,
 * unmodified, for provenance; they are retired, not deleted.
 *
 * <p>This test never hydrates engine or gameplay state from the fixture; it
 * only replays the reference's own request/dispatch sequence through the real
 * {@link com.openggf.audio.driver.SmpsDriver} (via {@link
 * S1OpenGgfSfxAudioCapture}, the same host the committed SFX oracle uses) and
 * compares the resulting driver state. The gameplay oracle is not required to
 * be green: it is measurement, and {@link #oracleMatchesTheWholeReference()}
 * pins the current frontier so a regression there is visible without papering
 * over it. As of 2026-09-03 that frontier is the end of the capture: all 2,562
 * ticks match. See docs/status/audio-frontier-log.md for the dated
 * measurement this pins.
 */
class TestS1GameplayAudioDriverOracle {
    private static final Path REFERENCE = Path.of(
            "src/test/resources/audio/parity/s1/s1-gameplay-ghz1-reference.v3.jsonl.gz");

    @TempDir
    Path temp;

    @Test
    void oracleMatchesTheWholeReference() throws Exception {
        Path rom = requiredRom();
        Path reference = decompress(REFERENCE, temp.resolve("reference.jsonl"));
        Path openGgf = temp.resolve("openggf.jsonl");

        S1OpenGgfSfxAudioCapture.CaptureResult result =
                S1OpenGgfSfxAudioCapture.capture(reference, rom, openGgf);
        assertEquals(2562, result.recordCount());

        AudioParityReport report = AudioParityComparator.compare(reference, openGgf);

        // Frontier gate (see docs/status/audio-frontier-log.md, 2026-09-03):
        // the last divergence was tick 1759, where the shipped FixBugs = 0
        // SendVoiceTL reads v_special_voice_ptr for a normal SFX
        // (docs/s1disasm/s1.sounddriver.asm:2391-2398) after the special SFX
        // that installed it has finished. With that pointer modelled as the
        // ROM's persistent global the whole 2,562-tick reference matches. This
        // assertion is the frontier pin: it moved to MATCH, it was not removed.
        // If a change makes this red again, record the new first divergence in
        // the frontier log with the ROM routine that explains it.
        assertEquals(AudioParityReport.Kind.MATCH, report.kind(), report::toHumanText);
        assertEquals(2562, report.ticksCompared());
    }

    /**
     * Corrupts a byte in a copy of the committed reference and confirms the
     * comparator reports the corruption at the tick it lands on, proving the
     * comparison is live rather than vacuously green. See
     * docs/status/audio-frontier-log.md for this break-on-purpose evidence.
     */
    @Test
    void corruptingTheReferenceIsDetectedAtTheCorruptedTick() throws Exception {
        Path rom = requiredRom();
        Path reference = decompress(REFERENCE, temp.resolve("reference.jsonl"));
        Path openGgf = temp.resolve("openggf.jsonl");
        S1OpenGgfSfxAudioCapture.capture(reference, rom, openGgf);

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
