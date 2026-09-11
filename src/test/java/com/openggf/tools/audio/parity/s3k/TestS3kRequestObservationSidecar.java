package com.openggf.tools.audio.parity.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.openggf.tests.RomTestUtils;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Strict-parsing contract for the source-observed S3K music-mailbox requests,
 * plus a MEASUREMENT_ONLY gate showing what supplying them does to the oracle.
 *
 * <p>The sidecar carries driver inputs, not compared values. Nothing here
 * hydrates engine state, publishes a fixture, or moves the committed
 * comparison: the default reader path stays byte-for-byte as it was, and the
 * gate only reports what a supplied input changes.</p>
 */
class TestS3kRequestObservationSidecar {
    private static final Path REFERENCE = Path.of(
            "src/test/resources/audio/parity/s3k/s3k-aiz1-intro-reference-v1.jsonl.gz");
    private static final String OBSERVATIONS_PROPERTY = "s3k.request.observations";

    @TempDir
    Path temporaryDirectory;

    private static final String VALID = """
            {
              "schema": "openggf.s3k-preconsumption-request-observations.v1",
              "production_bound": false,
              "rom_sha1": "cfbf98c36c776677290a872547ac47c53d2761d6",
              "bk2_basename": "s3k-complete-sonic-tails.bk2",
              "bk2_sha256": "82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf",
              "bk2_row_count": 466334,
              "service_manifest_sha256": "a2986032425af20fce19abd9e4bb0a1deabb142707510fe1d1830995adaaaf49",
              "first_row": 0,
              "exclusive_end": 5400,
              "capture_sha256": "aa",
              "duplicate_capture_sha256": "aa",
              "observations": [{"row": 242, "request": 254}]
            }
            """;

    @Test
    void readsTheObservedRequestAtItsMovieRow() throws Exception {
        S3kRequestObservationSidecar sidecar = write(VALID);
        assertEquals(1, sidecar.size());
        assertEquals(Optional.of(0xFE), sidecar.requestAt(242));
        assertEquals(Optional.empty(), sidecar.requestAt(243),
                "an unobserved row must stay unobserved rather than defaulting");
    }

    @Test
    void anAbsentSidecarObservesNothing() {
        assertEquals(0, S3kRequestObservationSidecar.absent().size());
        assertEquals(Optional.empty(), S3kRequestObservationSidecar.absent().requestAt(242));
    }

    @Test
    void rejectsAWrongSchema() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("request-observations.v1", "request-observations.v2")));
    }

    @Test
    void rejectsAProductionBoundClaim() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("\"production_bound\": false",
                        "\"production_bound\": true")));
    }

    @Test
    void rejectsDisagreeingDuplicateCaptures() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("\"duplicate_capture_sha256\": \"aa\"",
                        "\"duplicate_capture_sha256\": \"bb\"")));
    }

    @Test
    void rejectsAZeroRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("\"request\": 254", "\"request\": 0")));
    }

    @Test
    void rejectsAnObservationOutsideTheDeclaredWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("\"row\": 242", "\"row\": 5400")));
    }

    @Test
    void rejectsAnEmptyObservationList() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("[{\"row\": 242, \"request\": 254}]", "[]")));
    }

    @Test
    void rejectsUnorderedObservations() {
        assertThrows(IllegalArgumentException.class,
                () -> write(VALID.replace("[{\"row\": 242, \"request\": 254}]",
                        "[{\"row\": 242, \"request\": 254}, {\"row\": 13, \"request\": 225}]")));
    }

    /**
     * The committed comparison must not move because this class exists. Without
     * a sidecar the reader keeps declaring the same producer-input limitation.
     */
    @Test
    void theDefaultReaderPathStillReportsTheProducerInputLimitation() {
        assumeTrue(Files.isRegularFile(REFERENCE), "committed S3K oracle reference is required");
        List<S3kAudioTick> ticks = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(REFERENCE, ticks::add);
        List<Integer> unavailable = new ArrayList<>();
        for (int ordinal = 0; ordinal < ticks.size(); ordinal++) {
            if (ticks.get(ordinal).producerInputEvidence().unavailable()) {
                unavailable.add(ordinal);
            }
        }
        assertEquals(List.of(128), unavailable,
                "the default path must keep exactly its pre-existing limitation");
    }

    /**
     * MEASUREMENT_ONLY. Supplying the source-observed request removes the
     * producer-input limitation, so the comparison reaches real engine
     * behaviour instead of stopping at a blind input. This asserts the
     * limitation is gone, not that the comparison passes.
     */
    @Test
    void supplyingTheObservedRequestRemovesTheProducerInputLimitation() {
        assumeTrue(Files.isRegularFile(REFERENCE), "committed S3K oracle reference is required");
        String configured = System.getProperty(OBSERVATIONS_PROPERTY);
        assumeTrue(configured != null && !configured.isBlank(),
                "set -D" + OBSERVATIONS_PROPERTY + "=<observations.json> to run this measurement");
        Path path = Path.of(configured);
        assumeTrue(Files.isRegularFile(path), "observations sidecar must exist: " + path);
        File rom = RomTestUtils.ensureSonic3kRomAvailable();
        assumeTrue(rom != null && rom.isFile(), "the locked-on S3K ROM is required");

        S3kRequestObservationSidecar sidecar = S3kRequestObservationSidecar.read(path);
        assertTrue(sidecar.requestAt(242).isPresent(),
                "the in-frame request the pre-invocation sampling cannot see must be observed");

        List<S3kAudioTick> ticks = new ArrayList<>();
        S3kAudioReferenceReader.readDriverServices(REFERENCE, sidecar, ticks::add);
        for (S3kAudioTick tick : ticks) {
            assertTrue(!tick.producerInputEvidence().unavailable(),
                    "no producer input may remain unavailable once the request is supplied");
        }
    }

    private S3kRequestObservationSidecar write(String json) throws Exception {
        Path path = Files.createTempFile(temporaryDirectory, "observations", ".json");
        Files.writeString(path, json);
        return S3kRequestObservationSidecar.read(path);
    }
}
