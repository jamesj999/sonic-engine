package com.openggf.game.timing;

import com.openggf.data.compression.KosinskiReader;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHardwareSubmissionFingerprint {

    private static final String GOLDEN_FINGERPRINT =
            "sha256:11609213811e60294ea19488a1e3c6e87cd91f0af35480541091f5f7f478863b";

    @Test
    void fingerprintUsesCanonicalBigEndianTuple() {
        HardwareWorkSubmission submission = new HardwareWorkSubmission(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0x12345678,
                0x01020304,
                0x0000ABCD,
                0x11223344,
                "KosM",
                7,
                false,
                preparedWith(new byte[] {1, 2, 3}));

        String fingerprint = HardwareSubmissionFingerprint.compute(submission);

        assertEquals(GOLDEN_FINGERPRINT, fingerprint);
        assertTrue(fingerprint.matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void everyCanonicalRomWorkFieldChangesFingerprint() {
        HardwareWorkSubmission baseline = baseline();

        assertFingerprintChanges(baseline, submission -> copy(
                submission, submission.romSourceAddress() + 1,
                submission.compressedLength(), submission.destinationAddress(),
                submission.destinationLength(), submission.compressionVariant(),
                submission.moduleCount(), submission.exportableAcrossSegment(),
                submission.preparation()));
        assertFingerprintChanges(baseline, submission -> copy(
                submission, submission.romSourceAddress(),
                submission.compressedLength() + 1, submission.destinationAddress(),
                submission.destinationLength(), submission.compressionVariant(),
                submission.moduleCount(), submission.exportableAcrossSegment(),
                submission.preparation()));
        assertFingerprintChanges(baseline, submission -> copy(
                submission, submission.romSourceAddress(),
                submission.compressedLength(), submission.destinationAddress() + 1,
                submission.destinationLength(), submission.compressionVariant(),
                submission.moduleCount(), submission.exportableAcrossSegment(),
                submission.preparation()));
        assertFingerprintChanges(baseline, submission -> copy(
                submission, submission.romSourceAddress(),
                submission.compressedLength(), submission.destinationAddress(),
                submission.destinationLength() + 1, submission.compressionVariant(),
                submission.moduleCount(), submission.exportableAcrossSegment(),
                submission.preparation()));
        assertFingerprintChanges(baseline, submission -> copy(
                submission, submission.romSourceAddress(),
                submission.compressedLength(), submission.destinationAddress(),
                submission.destinationLength(), "KosinskiM",
                submission.moduleCount(), submission.exportableAcrossSegment(),
                submission.preparation()));
        assertFingerprintChanges(baseline, submission -> copy(
                submission, submission.romSourceAddress(),
                submission.compressedLength(), submission.destinationAddress(),
                submission.destinationLength(), submission.compressionVariant(),
                submission.moduleCount() + 1, submission.exportableAcrossSegment(),
                submission.preparation()));

        String kindFingerprint = HardwareSubmissionFingerprint.compute(baseline);
        assertNotEquals(kindFingerprint,
                HardwareSubmissionFingerprint.computeCanonical(
                        "FUTURE_HARDWARE_KIND",
                        baseline.romSourceAddress(),
                        baseline.compressedLength(),
                        baseline.destinationAddress(),
                        baseline.destinationLength(),
                        baseline.compressionVariant(),
                        baseline.moduleCount()));
    }

    @Test
    void schedulingPolicyAndPayloadAreExcludedFromCanonicalIdentity() {
        HardwareWorkSubmission baseline = baseline();
        HardwareWorkSubmission exportable = copy(
                baseline, baseline.romSourceAddress(), baseline.compressedLength(),
                baseline.destinationAddress(), baseline.destinationLength(),
                baseline.compressionVariant(), baseline.moduleCount(), true,
                baseline.preparation());
        HardwareWorkSubmission differentPayload = copy(
                baseline, baseline.romSourceAddress(), baseline.compressedLength(),
                baseline.destinationAddress(), baseline.destinationLength(),
                baseline.compressionVariant(), baseline.moduleCount(), false,
                preparedWith(new byte[] {99, 98, 97}));

        assertEquals(HardwareSubmissionFingerprint.compute(baseline),
                HardwareSubmissionFingerprint.compute(exportable));
        assertEquals(HardwareSubmissionFingerprint.compute(baseline),
                HardwareSubmissionFingerprint.compute(differentPayload));
    }

    @Test
    void standardKosScannerAndFingerprintsMatchLanguageNeutralVectors()
            throws Exception {
        Set<String> coveredFeatures = new HashSet<>();
        for (ScannerVector vector : loadScannerVectors()) {
            coveredFeatures.addAll(Arrays.asList(vector.features().split(",")));
            if (!vector.outcome().equals("ok")) {
                IOException error = assertThrows(IOException.class,
                        () -> KosinskiReader.inspectStandard(vector.bytes(), 0),
                        vector.name());
                String expectedMessage = vector.outcome().equals(
                        "invalid_backreference")
                        ? "backreference precedes output"
                        : "Unexpected end";
                assertTrue(error.getMessage().contains(expectedMessage),
                        vector.name() + ": " + error.getMessage());
                continue;
            }

            KosinskiReader.StandardArchiveInfo info =
                    KosinskiReader.inspectStandard(vector.bytes(), 0);
            assertEquals(vector.compressedLength(), info.compressedLength(),
                    vector.name());
            assertEquals(vector.decodedLength(), info.decompressedLength(),
                    vector.name());
            assertEquals(vector.fingerprint(),
                    HardwareSubmissionFingerprint.computeCanonical(
                            HardwareWorkKind.KOS_DECOMPRESSION_QUEUE.name(),
                            vector.sourceAddress(),
                            vector.compressedLength(),
                            vector.destinationAddress(),
                            vector.decodedLength(),
                            "kosinski",
                            1),
                    vector.name());
        }

        assertEquals(Set.of(
                        "descriptor_refill",
                        "literal",
                        "short_match",
                        "long_match",
                        "extended_match",
                        "no_output",
                        "invalid_backreference",
                        "terminator",
                        "rom_bound"),
                coveredFeatures);
    }

    private static List<ScannerVector> loadScannerVectors()
            throws IOException {
        Path path = Path.of(
                "src",
                "test",
                "resources",
                "kosinski",
                "standard-scanner-vectors.tsv");
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(TestHardwareSubmissionFingerprint::parseScannerVector)
                .toList();
    }

    private static ScannerVector parseScannerVector(String line) {
        String[] fields = line.split("\\t", -1);
        if (fields.length != 9) {
            throw new IllegalArgumentException(
                    "standard Kosinski vector must have nine fields: " + line);
        }
        boolean success = fields[1].equals("ok");
        return new ScannerVector(
                fields[0],
                fields[1],
                fields[2],
                Integer.parseUnsignedInt(fields[3], 16),
                Integer.parseUnsignedInt(fields[4], 16),
                HexFormat.of().parseHex(fields[5]),
                success ? Integer.parseInt(fields[6]) : -1,
                success ? Integer.parseInt(fields[7]) : -1,
                fields[8]);
    }

    private record ScannerVector(
            String name,
            String outcome,
            String features,
            int sourceAddress,
            int destinationAddress,
            byte[] bytes,
            int compressedLength,
            int decodedLength,
            String fingerprint) {
        private ScannerVector {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    private static void assertFingerprintChanges(
            HardwareWorkSubmission baseline,
            UnaryOperator<HardwareWorkSubmission> mutation) {
        assertNotEquals(HardwareSubmissionFingerprint.compute(baseline),
                HardwareSubmissionFingerprint.compute(mutation.apply(baseline)));
    }

    private static HardwareWorkSubmission baseline() {
        return new HardwareWorkSubmission(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0x1000,
                0x200,
                0x4000,
                0x800,
                "KosM",
                2,
                false,
                preparedWith(new byte[] {1, 2, 3}));
    }

    private static HardwareWorkSubmission copy(
            HardwareWorkSubmission submission,
            int sourceAddress,
            int compressedLength,
            int destinationAddress,
            int destinationLength,
            String compressionVariant,
            int moduleCount,
            boolean exportable,
            HardwareWorkPreparation preparation) {
        return new HardwareWorkSubmission(
                submission.kind(),
                sourceAddress,
                compressedLength,
                destinationAddress,
                destinationLength,
                compressionVariant,
                moduleCount,
                exportable,
                preparation);
    }

    private static HardwareWorkPreparation preparedWith(byte[] payload) {
        return new TestPreparation(0, payload);
    }

    private record PreparationSnapshot(int remainingUnits, byte[] payload)
            implements HardwareWorkPreparationSnapshot {
        private PreparationSnapshot {
            payload = payload.clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new TestPreparation(remainingUnits, payload);
        }
    }

    private static final class TestPreparation implements HardwareWorkPreparation {
        private int remainingUnits;
        private final byte[] payload;

        private TestPreparation(int remainingUnits, byte[] payload) {
            this.remainingUnits = remainingUnits;
            this.payload = payload.clone();
        }

        @Override
        public boolean stepOneWorkUnit() {
            if (remainingUnits == 0) {
                return false;
            }
            remainingUnits--;
            return true;
        }

        @Override
        public boolean isPrepared() {
            return remainingUnits == 0;
        }

        @Override
        public byte[] preparedPayload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new PreparationSnapshot(remainingUnits, payload);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            remainingUnits = ((PreparationSnapshot) snapshot).remainingUnits();
        }
    }
}
