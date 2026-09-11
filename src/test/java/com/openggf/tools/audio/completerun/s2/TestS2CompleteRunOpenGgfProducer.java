package com.openggf.tools.audio.completerun.s2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.openggf.tools.audio.completerun.CompleteRunAudioProducer;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ComparisonLayer;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestS2CompleteRunOpenGgfProducer {
    @TempDir Path temporaryDirectory;

    @Test
    void unavailableBindingPreventsRunnerStartupAndLeavesOutputUntouched()
            throws Exception {
        Path output = temporaryDirectory.resolve("existing-output");
        Files.writeString(output, "keep");
        var request = new CompleteRunAudioProducer.Request(ProducerKind.OPENGGF,
                S2CompleteRunAudioProfile.ID, poison("rom.gen"), poison("movie.bk2"),
                poison("manifest.json"), poison("reference-home"), output);

        Exception failure = assertThrows(Exception.class,
                () -> new S2CompleteRunOpenGgfProducer().capture(request));

        assertEquals("OpenGGF producer artifact attestation trust root is not installed",
                failure.getMessage());
        assertEquals("keep", Files.readString(output));
    }

    @Test
    void fixedProfileTypesOpenGgfAsFrameChipEvidenceOnly() {
        var inventory = S2CompleteRunAudioProfile.profile()
                .producerObservationInventories().get(ProducerKind.OPENGGF);
        for (ComparisonLayer layer : ComparisonLayer.values()) {
            assertEquals(layer == ComparisonLayer.FRAME_CHIP_EVENTS,
                    inventory.isObserved(layer), layer.name());
        }
    }

    private Path poison(String name) {
        return temporaryDirectory.resolve("missing").resolve(name);
    }
}
