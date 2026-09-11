package com.openggf.tools.audio.completerun.s2;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Fail-closed ratchet around the output-only S2 production request projection. */
class TestS2ProductionRequestAuthorityGuard {
    private static final Path SERVICE = Path.of(
            "src/main/java/com/openggf/game/sonic2/audio/Sonic2SoundRequestService.java");
    private static final Path PROJECTOR = Path.of(
            "src/main/java/com/openggf/tools/audio/completerun/s2/S2ProductionRequestProjector.java");
    private static final Path MANAGER = Path.of(
            "src/main/java/com/openggf/audio/AudioManager.java");
    private static final Pattern SERVICE_FORBIDDEN = Pattern.compile(
            "com\\.openggf\\.(?:tools|trace)|\\b(?:DriverRequest|HardwareTiming[A-Za-z0-9_]*|"
                    + "SmpsDriver(?:Session)?|AudioManager|logicalDriverForTesting|addSequencer|getInstance)\\b");
    private static final Pattern PROJECTOR_FORBIDDEN = Pattern.compile(
            "\\b(?:S2RequestAwareOracle(?:RawStream|Schema)|S2OracleEngineCapture|DriverRequest|"
                    + "S2CompleteRunReference[A-Za-z0-9_]*|HardwareTiming[A-Za-z0-9_]*|"
                    + "TraceSession|TraceReplay|TraceBootstrap|Physics|Auxiliary)\\b");
    private static final Pattern GAME_CARVEOUT = Pattern.compile(
            "instanceof\\s+Sonic2|[\"'](?:s2|sonic2)[\"']",
            Pattern.CASE_INSENSITIVE);

    @Test
    void productionServiceAndProjectorCannotReachReferenceOrPlaybackAuthority() {
        assertEquals(false, SERVICE_FORBIDDEN.matcher(read(SERVICE)).find());
        assertEquals(false, PROJECTOR_FORBIDDEN.matcher(read(PROJECTOR)).find());
        assertEquals(false, GAME_CARVEOUT.matcher(read(MANAGER)).find());
    }

    @Test
    void requestComparisonAndBothProducerBindingsRemainUnavailable() {
        var profile = S2CompleteRunAudioProfile.profile();
        assertEquals(CompleteRunAudioTrace.ComparisonLayerStatus.UNAVAILABLE,
                profile.comparisonLayerInventory()
                        .claim(CompleteRunAudioTrace.ComparisonLayer.REQUESTS).status());
        assertEquals(CompleteRunAudioTrace.ComparisonLayerStatus.UNAVAILABLE,
                profile.comparisonLayerInventory()
                        .claim(CompleteRunAudioTrace.ComparisonLayer.DECISIONS).status());
        assertEquals(CompleteRunAudioTrace.ObservationStatus.UNOBSERVED,
                profile.producerObservationInventories()
                        .get(CompleteRunAudioTrace.ProducerKind.OPENGGF)
                        .claim(CompleteRunAudioTrace.ComparisonLayer.REQUESTS).status());
        for (CompleteRunAudioTrace.ProducerKind kind
                : List.of(CompleteRunAudioTrace.ProducerKind.values())) {
            assertInstanceOf(CompleteRunAudioTrace.UnavailableProducerBinding.class,
                    profile.producerBindings().get(kind));
        }
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
