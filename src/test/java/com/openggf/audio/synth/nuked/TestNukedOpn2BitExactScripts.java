package com.openggf.audio.synth.nuked;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@link NukedOpn2} port cycle-for-cycle against the pinned C build
 * ({@code tools/audio/nuked-opn2/PIN.md}) at the pin level.
 *
 * <p>Every script body under {@code src/test/resources/audio/nuked-opn2/port/}
 * is run under all four chip-type flag sets by {@link NukedOpn2ScriptRunner};
 * {@code expected.txt} holds, per body and chip type, the cycle count, the
 * FNV-1a checksum of every MOL/MOR pin value the C {@code ym3438.c} produced
 * on {@code OPN2_Clock}, and the count and checksum of its side log (status,
 * IRQ and state-dump lines). The synthetic bodies are the smoke patch and a
 * sweep over EG rates, SSG-EG modes, LFO settings, detune/multiple, channel 3
 * special mode and CSM, timers, DAC writes, the LSI test registers, bus edge
 * cases and a seeded fuzz; the {@code s1-}, {@code s2-} and {@code s3k-}
 * bodies are real SMPS write logs captured with
 * {@code com.openggf.tools.audio.FmSfxRenderTool} at the chip's internal
 * rate. The expectations were produced by
 * {@code tools/audio/nuked-opn2/harness/regenerate-bitexact-expectations.sh};
 * the validation record is
 * {@code docs/architecture/validation/2026-08-29-nuked-opn2-port-bit-exactness.md}.
 */
// Minutes-long oracle sweep: excluded from the -Psmoke fast lane, still run by
// the default suite on pull requests, the nightly schedule and release validation.
@Tag("slow-suite")
class TestNukedOpn2BitExactScripts {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int CHIP_TYPES = 4;

    @TestFactory
    Stream<DynamicTest> scriptsMatchTheCBuildCycleForCycle() throws IOException, URISyntaxException {
        Path directory = Path.of(TestNukedOpn2BitExactScripts.class
                .getResource("/audio/nuked-opn2/port/expected.txt").toURI()).getParent();
        List<String> expectations = Files.readAllLines(directory.resolve("expected.txt"));
        long bodies;
        try (Stream<Path> listing = Files.list(directory)) {
            bodies = listing.filter(p -> p.toString().endsWith(".txt.gz")).count();
        }
        assertEquals(bodies * CHIP_TYPES, expectations.size(),
                "expected.txt must list every script body under every chip type");
        return expectations.stream().map(line -> {
            String[] fields = line.trim().split("\\s+");
            return DynamicTest.dynamicTest(fields[0] + "-t" + fields[1], () -> runScript(directory, fields));
        });
    }

    private static void runScript(Path directory, String[] expectation) throws IOException {
        String name = expectation[0];
        int chipType = Integer.parseInt(expectation[1]);
        long expectedCycles = Long.parseLong(expectation[2]);
        long expectedChecksum = Long.parseUnsignedLong(expectation[3], 16);
        int expectedSideLines = Integer.parseInt(expectation[4]);
        long expectedSideChecksum = Long.parseUnsignedLong(expectation[5], 16);

        NukedOpn2ScriptRunner.Result result;
        try (BufferedReader reader = NukedOpn2ScriptRunner.open(directory.resolve(name + ".txt.gz"))) {
            result = NukedOpn2ScriptRunner.run(chipType, reader, null);
        }
        assertTrue(result.cycles() > 0, name + ": script clocked nothing");
        assertEquals(expectedCycles, result.cycles(), name + ": cycle count");
        assertEquals(expectedSideLines, result.side().size(), name + ": side log lines");
        assertEquals(Long.toHexString(expectedSideChecksum), Long.toHexString(sideChecksum(result.side())),
                name + ": side log (status / IRQ / dumps)");
        assertEquals(Long.toHexString(expectedChecksum), Long.toHexString(result.checksum()),
                name + ": per-cycle MOL/MOR stream");
    }

    /** FNV-1a over the US-ASCII bytes of every side line followed by a newline, as the C side log is stored. */
    private static long sideChecksum(List<String> side) {
        long checksum = FNV_OFFSET;
        for (String line : side) {
            for (byte b : line.getBytes(StandardCharsets.US_ASCII)) {
                checksum ^= b & 0xff;
                checksum *= FNV_PRIME;
            }
            checksum ^= '\n';
            checksum *= FNV_PRIME;
        }
        return checksum;
    }
}
