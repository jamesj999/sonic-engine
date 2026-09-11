package com.openggf.game.timing;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestProfiledLoadTimeManifest {

    @Test
    void returnsMeasuredDecisionForExactKindFingerprintAndModel() throws Exception {
        List<String> warnings = new ArrayList<>();
        ProfiledLoadTimeManifest manifest = load("""
                {
                  "formatVersion": 1,
                  "profile": "s3k",
                  "serviceModel": "s3k-kos-v1",
                  "fixtures": ["aiz/run-1"],
                  "entries": [{
                    "kind": "kos_decompression_queue",
                    "submissionFingerprint": "sha256:abc",
                    "serviceFrames": 7,
                    "eligibleBoundaries": ["pre_main_loop"],
                    "sampleCount": 2,
                    "minFrames": 6,
                    "maxFrames": 7,
                    "fixtureIndexes": [0]
                  }]
                }
                """, warnings);

        LoadTimeDecision decision = manifest.assign(
                null,
                new HardwareWorkHandle(
                        HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        4,
                        "sha256:abc"));

        assertEquals(7, decision.serviceFrames());
        assertEquals(LoadTimeDecisionSource.MEASURED, decision.source());
        assertEquals("s3k-kos-v1", decision.serviceModel());
        assertEquals(
                java.util.Set.of(HardwareServiceBoundary.PRE_MAIN_LOOP),
                decision.eligibleBoundaries());
        assertEquals(List.of(), warnings);
    }

    @Test
    void missingFingerprintWarnsOnceAndFallsBackToImmediate() throws Exception {
        List<String> warnings = new ArrayList<>();
        ProfiledLoadTimeManifest manifest = load("""
                {
                  "formatVersion": 1,
                  "profile": "s3k",
                  "serviceModel": "s3k-kos-v1",
                  "fixtures": [],
                  "entries": []
                }
                """, warnings);
        HardwareWorkHandle handle = new HardwareWorkHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, 0, "sha256:missing");

        assertEquals(LoadTimeDecisionSource.IMMEDIATE,
                manifest.assign(null, handle).source());
        assertEquals(LoadTimeDecisionSource.IMMEDIATE,
                manifest.assign(null, handle).source());
        assertEquals(1, warnings.size());
    }

    @Test
    void missingFingerprintUsesPublishedDeterministicEstimator() throws Exception {
        List<String> warnings = new ArrayList<>();
        ProfiledLoadTimeManifest manifest = load("""
                {
                  "formatVersion": 1,
                  "profile": "s3k",
                  "serviceModel": "s3k-kos-v1",
                  "fixtures": [],
                  "estimator": {
                    "kind": "kos_decompression_queue",
                    "serviceModel": "s3k-kos-v1",
                    "feature": "shortCopyCommands",
                    "intercept": 1,
                    "divisor": 4,
                    "validation": {
                      "accepted": true,
                      "sampleCount": 20,
                      "fingerprintCount": 20,
                      "familyCount": 3,
                      "fingerprintMedianError": 2,
                      "fingerprintP95Error": 5,
                      "familyMedianError": 2,
                      "familyP95Error": 5
                    }
                  },
                  "entries": []
                }
                """, warnings);
        HardwareWorkSubmission submission = new HardwareWorkSubmission(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                1, 8, 2, 3, "kosinski", 1, false,
                new HardwareWorkFeatures(3, 9, 0, 0, 8, 3, 1, 3, 0),
                new Prepared());
        HardwareWorkHandle handle = new HardwareWorkHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                0, "sha256:missing");

        LoadTimeDecision decision = manifest.assign(submission, handle);

        assertEquals(4, decision.serviceFrames());
        assertEquals(LoadTimeDecisionSource.ESTIMATED, decision.source());
        assertEquals(1, warnings.size());
        assertEquals(true, warnings.getFirst().contains("deterministic estimate"));
    }

    @Test
    void rejectsDuplicateKeysAndInvalidStatistics() {
        String duplicate = """
                {
                  "formatVersion": 1,
                  "profile": "s3k",
                  "serviceModel": "s3k-kos-v1",
                  "fixtures": [],
                  "entries": [
                    {"kind":"kos_module_queue","submissionFingerprint":"same",
                     "serviceFrames":0,"eligibleBoundaries":[],
                     "sampleCount":1,"minFrames":0,"maxFrames":0,"fixtureIndexes":[]},
                    {"kind":"kos_module_queue","submissionFingerprint":"same",
                     "serviceFrames":0,"eligibleBoundaries":[],
                     "sampleCount":1,"minFrames":0,"maxFrames":0,"fixtureIndexes":[]}
                  ]
                }
                """;

        assertThrows(IllegalArgumentException.class,
                () -> load(duplicate, new ArrayList<>()));
    }

    @Test
    void rejectsEstimatorWithoutPassingPublicationEvidence() {
        String unvalidated = """
                {
                  "formatVersion": 1,
                  "profile": "s3k",
                  "serviceModel": "s3k-kos-v1",
                  "fixtures": [],
                  "estimator": {
                    "kind": "kos_decompression_queue",
                    "serviceModel": "s3k-kos-v1",
                    "feature": "shortCopyCommands",
                    "intercept": 0,
                    "divisor": 133,
                    "validation": {
                      "accepted": true,
                      "sampleCount": 19,
                      "fingerprintCount": 19,
                      "familyCount": 2,
                      "fingerprintMedianError": 3,
                      "fingerprintP95Error": 6,
                      "familyMedianError": 3,
                      "familyP95Error": 6
                    }
                  },
                  "entries": []
                }
                """;

        assertThrows(IllegalArgumentException.class,
                () -> load(unvalidated, new ArrayList<>()));
    }

    private static ProfiledLoadTimeManifest load(
            String json, List<String> warnings) throws Exception {
        return ProfiledLoadTimeManifest.load(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)),
                warnings::add);
    }

    private static final class Prepared implements HardwareWorkPreparation {
        @Override
        public boolean stepOneWorkUnit() {
            return false;
        }

        @Override
        public boolean isPrepared() {
            return true;
        }

        @Override
        public byte[] preparedPayload() {
            return new byte[0];
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return () -> new Prepared();
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
        }
    }
}
