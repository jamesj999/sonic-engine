package com.openggf.tools.audio.parity.s2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI for the S2 driver oracle: compares the committed windowed reference
 * capture against a fresh engine capture and reports the first divergence.
 *
 * <pre>
 * java ... com.openggf.tools.audio.parity.s2.S2AudioOracleTool \
 *     --fixture src/test/resources/audio/parity/s2/s2-ehz-reload-w10150-10900.raw.jsonl.gz \
 *     --rom /abs/path/to/s2.gen [--report /abs/path/report.txt] [--ignore-digest]
 * </pre>
 *
 * Exit codes: 0 match, 2 usage, 3 divergence, 4 invalid input.
 */
public final class S2AudioOracleTool {

    private S2AudioOracleTool() {
    }

    public static void main(String[] arguments) throws IOException {
        Path fixture = null;
        Path rom = null;
        Path report = null;
        boolean verifyDigest = true;
        for (int index = 0; index < arguments.length; index++) {
            switch (arguments[index]) {
                case "--fixture" -> fixture = Path.of(arguments[++index]);
                case "--rom" -> rom = Path.of(arguments[++index]);
                case "--report" -> report = Path.of(arguments[++index]);
                case "--ignore-digest" -> verifyDigest = false;
                default -> {
                    System.err.println("unknown argument: " + arguments[index]);
                    System.exit(2);
                }
            }
        }
        if (fixture == null || rom == null) {
            System.err.println(
                    "usage: --fixture <raw.jsonl.gz> --rom <s2 REV01> [--report <file>] [--ignore-digest]");
            System.exit(2);
        }
        S2AudioOracleComparator.Report result =
                S2AudioOracleComparator.compare(fixture, rom, verifyDigest);
        String description = result.describe();
        System.out.println(description);
        if (report != null) {
            Files.writeString(report, description + System.lineSeparator(),
                    StandardCharsets.UTF_8);
        }
        System.exit(switch (result.kind()) {
            case MATCH -> 0;
            case DIVERGENCE -> 3;
            case INVALID -> 4;
        });
    }
}
