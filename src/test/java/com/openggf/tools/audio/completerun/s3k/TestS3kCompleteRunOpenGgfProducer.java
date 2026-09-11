package com.openggf.tools.audio.completerun.s3k;

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

class TestS3kCompleteRunOpenGgfProducer {
    @TempDir Path temporaryDirectory;

    @Test
    void unavailableBindingPreventsRunnerStartupAndLeavesOutputUntouched()
            throws Exception {
        Path output = temporaryDirectory.resolve("existing-output");
        Files.writeString(output, "keep");
        var request = new CompleteRunAudioProducer.Request(ProducerKind.OPENGGF,
                S3kCompleteRunAudioProfile.ID, poison("rom.gen"), poison("movie.bk2"),
                poison("manifest.json"), poison("reference-home"), output);

        Exception failure = assertThrows(Exception.class,
                () -> new S3kCompleteRunOpenGgfProducer().capture(request));

        assertEquals("OpenGGF producer artifact attestation trust root is not installed",
                failure.getMessage());
        assertEquals("keep", Files.readString(output));
    }

    @Test
    void fixedProfileTypesOpenGgfAsFrameChipEvidenceOnly() {
        var inventory = S3kCompleteRunAudioProfile.profile()
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
