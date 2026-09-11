package com.openggf.tools.audio.parity.s2;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Ratchet for the unbound request-reader's one-way comparison boundary. */
class TestS2RequestReferenceAuthorityGuard {
    private static final List<Path> REFERENCE_SOURCES = List.of(
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2RequestAwareOracleSchema.java"),
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2RequestAwareOracleRawStream.java"));
    private static final List<Path> EXISTING_AUTHORITY_OWNERS = List.of(
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2OracleRawStream.java"),
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2AudioOracleComparator.java"),
            Path.of("src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunAudioProfile.java"),
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2OracleEngineCapture.java"));
    private static final List<Path> MEASUREMENT_SOURCES = List.of(
            Path.of("src/test/java/com/openggf/tools/audio/parity/s2/S2Bk2DriverOracleComparator.java"),
            Path.of("src/test/java/com/openggf/tests/trace/runs/S2RequestProjectionBk2Capture.java"),
            Path.of("src/test/java/com/openggf/tests/trace/runs/S2RequestProjectionBk2TestBridge.java"));
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\b(?:DriverRequest|ResolvedSmpsSfxSource|Sonic2SoundRequestPipeline|SmpsDriver(?:Session)?|"
                    + "AudioManager|GameServices|HardwareTiming[A-Za-z0-9_]*|AudioPresentationCommand|"
                    + "admitSfx|S2Oracle(?:RawStream|Schema)|S2AudioOracleComparator|"
                    + "S2CompleteRunAudioProfile|S2OracleEngineCapture|"
                    + "com\\.openggf\\.(?:game|audio\\.presentation|trace\\.timing))\\b");

    @Test
    void requestReferenceSourcesCannotReachPlaybackGameplayOrTimingOwners() throws IOException {
        assertEquals(List.of(), REFERENCE_SOURCES.stream()
                .filter(Files::isRegularFile)
                .filter(path -> FORBIDDEN.matcher(read(path)).find())
                .map(Path::toString)
                .toList());
    }

    @Test
    void candidateReaderHasNoPublicPathOpeningOrProductionBindingEntry() throws Exception {
        assertFalse(java.lang.reflect.Modifier.isPublic(S2RequestAwareOracleRawStream.class
                .getDeclaredMethod("scanCandidateForTesting", Path.class).getModifiers()),
                "the only input-opening seam must stay package-private");
        assertFalse(java.lang.reflect.Modifier.isPublic(S2RequestAwareOracleRawStream.class
                .getDeclaredMethod("scanWindowSourceCandidateForTesting", Path.class)
                .getModifiers()),
                "the measurement-only window entry must stay package-private");
        assertEquals(List.of(), java.util.Arrays.stream(
                        S2RequestAwareOracleRawStream.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers()))
                .map(java.lang.reflect.Method::getName).toList(),
                "the unbound reader must expose no public operation");
        assertEquals(List.of(), java.util.Arrays.stream(
                        S2RequestAwareOracleRawStream.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .filter(name -> name.toLowerCase().contains("production")
                        || name.toLowerCase().contains("bind"))
                .toList());
    }

    @Test
    void existingComparisonAndProductionAuthoritiesStayRequestReaderFree() throws IOException {
        assertEquals(List.of(), EXISTING_AUTHORITY_OWNERS.stream()
                .filter(Files::isRegularFile)
                .filter(path -> read(path).contains("S2RequestAwareOracle"))
                .map(Path::toString)
                .toList(), "Tranche A cannot add a profile, comparator, capture, or v1 route");
    }

    @Test
    void measurementSeamCannotTurnCandidateTransfersIntoDriverInputs() {
        Pattern forbiddenInput = Pattern.compile(
                "\\b(?:DriverRequest|requestTransfers|RequestTransfer)\\b");
        assertEquals(List.of(), MEASUREMENT_SOURCES.stream()
                .filter(Files::isRegularFile)
                .filter(path -> forbiddenInput.matcher(read(path)).find())
                .map(Path::toString)
                .toList());
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }
}
