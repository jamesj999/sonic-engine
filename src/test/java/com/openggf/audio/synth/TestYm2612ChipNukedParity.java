package com.openggf.audio.synth;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link Ym2612Chip} against the pinned Nuked-OPN2 C build.
 *
 * <p>Each script under {@code src/test/resources/audio/nuked-opn2/adapter/}
 * is a register stream ({@code type}, {@code write port reg val},
 * {@code render n}, {@code status}); {@code expected.txt} holds, per script,
 * the frame count, the FNV-1a checksum of every native frame, the last four
 * frames and the status bytes that
 * {@code tools/audio/nuked-opn2/harness/adapter_parity_harness.c} printed
 * when it drove the C {@code ym3438.c} with the facade's own bus pacing. The
 * facade runs the same script at its internal rate (no resampling) and must
 * reproduce the stream sample-exactly. Scripts cover the S1 bomb voice on all
 * eight algorithms with and without feedback, channel 3 special mode, partial
 * key-on, LFO with AMS/PMS, every SSG-EG mode, timers and CSM with status
 * reads, {@code silenceAll}, a DAC ramp and a seeded register fuzz, in both
 * output stages.
 */
class TestYm2612ChipNukedParity {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int TAIL_FRAMES = 4;

    @TestFactory
    Stream<DynamicTest> scriptsMatchTheCBuild() throws IOException, URISyntaxException {
        Path directory = Path.of(TestYm2612ChipNukedParity.class
                .getResource("/audio/nuked-opn2/adapter/expected.txt").toURI()).getParent();
        List<String> expectations = Files.readAllLines(directory.resolve("expected.txt"));
        assertTrue(expectations.size() >= 60, "expected.txt must list every generated script");
        return expectations.stream().map(line -> {
            String[] fields = line.trim().split("\\s+");
            return DynamicTest.dynamicTest(fields[0], () -> runScript(directory, fields));
        });
    }

    private static void runScript(Path directory, String[] expectation) throws IOException {
        String name = expectation[0];
        int expectedFrames = Integer.parseInt(expectation[1]);
        long expectedChecksum = Long.parseUnsignedLong(expectation[2], 16);
        int[] expectedTail = parsePairs(expectation[3]);
        List<Integer> expectedStatuses = new ArrayList<>();
        if (!"-".equals(expectation[4])) {
            for (String status : expectation[4].split(",")) {
                if (!status.isEmpty()) {
                    expectedStatuses.add(Integer.parseInt(status));
                }
            }
        }

        Ym2612Chip chip = new Ym2612Chip();
        chip.setOutputSampleRate(Ym2612Chip.getInternalRate());
        int[] left = new int[expectedFrames];
        int[] right = new int[expectedFrames];
        int rendered = 0;
        List<Integer> statuses = new ArrayList<>();
        for (String line : Files.readAllLines(directory.resolve(name + ".txt"))) {
            String[] fields = line.trim().split("\\s+");
            switch (fields[0]) {
                case "type" -> chip.setChipType(Integer.parseInt(fields[1]) == 3 ? 0 : 1);
                case "write" -> chip.write(Integer.parseInt(fields[1]),
                        Integer.parseInt(fields[2]), Integer.parseInt(fields[3]));
                case "render" -> {
                    int frames = Integer.parseInt(fields[1]);
                    assertTrue(rendered + frames <= expectedFrames, name + " renders more than the C build");
                    int[] l = new int[frames];
                    int[] r = new int[frames];
                    chip.renderStereo(l, r, frames);
                    System.arraycopy(l, 0, left, rendered, frames);
                    System.arraycopy(r, 0, right, rendered, frames);
                    rendered += frames;
                }
                case "status" -> statuses.add(chip.readStatus());
                default -> throw new IllegalStateException(name + ": unknown script line " + line);
            }
        }
        assertEquals(expectedFrames, rendered, name + ": frame count");
        assertEquals(expectedStatuses, statuses, name + ": status bytes");

        long checksum = FNV_OFFSET;
        for (int i = 0; i < rendered; i++) {
            checksum ^= left[i] & 0xffffffffL;
            checksum *= FNV_PRIME;
            checksum ^= right[i] & 0xffffffffL;
            checksum *= FNV_PRIME;
        }
        int[] tail = new int[TAIL_FRAMES * 2];
        for (int i = 0; i < TAIL_FRAMES; i++) {
            int frame = rendered - TAIL_FRAMES + i;
            tail[i * 2] = left[frame];
            tail[i * 2 + 1] = right[frame];
        }
        assertArrayEquals(expectedTail, tail, name + ": last frames");
        assertEquals(Long.toHexString(expectedChecksum), Long.toHexString(checksum), name + ": stream checksum");
    }

    private static int[] parsePairs(String field) {
        String[] pairs = field.split(";");
        int[] values = new int[pairs.length * 2];
        for (int i = 0; i < pairs.length; i++) {
            String[] pair = pairs[i].split(",");
            values[i * 2] = Integer.parseInt(pair[0]);
            values[i * 2 + 1] = Integer.parseInt(pair[1]);
        }
        return values;
    }
}
