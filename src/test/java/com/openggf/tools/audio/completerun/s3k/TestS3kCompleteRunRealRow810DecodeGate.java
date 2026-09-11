package com.openggf.tools.audio.completerun.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openggf.tests.TestTempFiles;
import com.openggf.tests.TraceChaserTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Assumptions;

/** Opt-in native proof that row 810 reaches the full S3K Java state pipeline. */
class TestS3kCompleteRunRealRow810DecodeGate {
    @Test
    @Tag("tracechaser-integration")
    void capturesAndDecodesTheExactRealRow810Boundary() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path traceChaser = TraceChaserTestSupport.requirePinnedCheckout();
        Assumptions.assumeTrue("1".equals(System.getenv("OPENGGF_S3K_COMPLETE_AUDIO_DECODE_GATE")),
                "set OPENGGF_S3K_COMPLETE_AUDIO_DECODE_GATE=1 to run the native capture");
        Path rom = requiredPath("S3K_ROM_PATH");
        Path movie = requiredPath("S3K_BK2_PATH");
        Path raw = TestTempFiles.createTempFile("openggf-s3k-row810-", ".jsonl");
        Files.delete(raw); // The C# publisher deliberately requires a create-new target.
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    traceChaser.resolve("bizhawk-headless/test.sh").toString(),
                    "--filter", "prove the real row 810 raw boundary");
            builder.directory(root.toFile());
            builder.inheritIO();
            builder.environment().put("OPENGGF_S3K_COMPLETE_AUDIO_REFERENCE", "1");
            builder.environment().put("OPENGGF_S3K_ROW810_RAW_OUTPUT", raw.toString());
            builder.environment().put("S3K_ROM_PATH", rom.toString());
            builder.environment().put("S3K_BK2_PATH", movie.toString());
            Process capture = builder.start();
            boolean finished = capture.waitFor(
                    Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) capture.destroyForcibly();
            assertTrue(finished, "native row-810 capture timed out");
            assertEquals(0, capture.exitValue(), "native row-810 capture failed");

            var catalog = S3kCompleteRunAssetCatalog.load(rom);
            var result = S3kCompleteRunReferencePreflight.preflightPrefixForTesting(raw, bytes -> {
                var snapshot = S3kCompleteRunStateDecoder.decode(bytes, catalog);
                return S3kCompleteRunStateNormalizer.normalizeReference(snapshot, catalog.assets());
            });
            assertEquals(810, result.boundaryCandidate().absoluteFrame());
            assertEquals(1, result.frameRows());
            assertEquals(34, result.rawEvents());
            assertEquals(0, result.cutoffActiveServices());
            assertEquals(0, result.cutoffPendingDescendants());
            assertEquals(false, result.baselineOwnershipCoherent(),
                    "row 810 has active tracks but no truthful prepublication owners yet");
        } finally {
            Files.deleteIfExists(raw);
        }
    }

    private static Path requiredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required by the opt-in row-810 gate");
        }
        Path path = Path.of(value).toAbsolutePath();
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException(name + " is not a file: " + path);
        }
        return path;
    }

}
