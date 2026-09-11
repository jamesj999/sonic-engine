package com.openggf.tools.audio.timeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

@RequiresRom(SonicGame.SONIC_1)
class TestS1Ghz1OpenGgfAudioTimelineCapture {
    private static final String RUN_PROPERTY = "s1.audio.timeline.run.path";
    private static final String OUTPUT_PROPERTY = "s1.audio.timeline.output";

    @Test
    void captureRequestedOutput() throws Exception {
        // Break caught: the local producer accepts an incomplete request or needs a reference timeline.
        String run = System.getProperty(RUN_PROPERTY);
        String output = System.getProperty(OUTPUT_PROPERTY);
        Assumptions.assumeTrue(run != null || output != null,
                "no local OpenGGF timeline capture was requested");
        Path[] paths = requestedPaths(run, output);
        S1Ghz1OpenGgfAudioTimelineCapture.capture(paths[0], paths[1]);
        assertTrue(Files.isRegularFile(paths[1]));
        try (S1GameplayAudioTimelineJsonl.Reader reader = S1GameplayAudioTimelineJsonl.read(paths[1])) {
            assertEquals(S1GameplayAudioTimeline.OPENGGF_CAPTURE, reader.metadata().capture());
            int frames = 0;
            while (reader.hasNext()) {
                if (reader.next() instanceof S1GameplayAudioTimeline.Frame frame) {
                    frames++;
                    for (S1GameplayAudioTimeline.Admission admission : frame.admissions()) {
                        for (S1GameplayAudioTimeline.RoleArbitration decision : admission.arbitration()) {
                            if (decision.acquired()) {
                                assertNotEquals(decision.displacedOwner(), decision.finalOwner(),
                                        "acquired role self-displaced at BK2 frame " + frame.bk2Frame());
                            }
                        }
                    }
                }
            }
            assertEquals(4115, frames);
        }
    }

    @Test
    void rejectsPartialPropertyPair() {
        assertThrows(IllegalArgumentException.class, () -> requestedPaths("run", null));
        assertThrows(IllegalArgumentException.class, () -> requestedPaths(null, "output"));
    }

    @Test
    void v2AcceptsD0OnlyAsSpecialSfxOwnership() {
        // Break caught: S1's special $D0 is serialized as an ordinary SFX owner.
        var special = new S1GameplayAudioTimeline.OwnerRef(
                S1GameplayAudioTimeline.OwnerClass.SPECIAL_SFX, 0xD0, 3);
        var admission = new S1GameplayAudioTimeline.Admission(3,
                S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX, 0xD0,
                java.util.List.of(S1GameplayAudioTimeline.HardwareRole.FM3),
                java.util.List.of(new S1GameplayAudioTimeline.RoleArbitration(
                        S1GameplayAudioTimeline.HardwareRole.FM3, true,
                        new S1GameplayAudioTimeline.OwnerRef(
                                S1GameplayAudioTimeline.OwnerClass.MUSIC, 0x81, 0), special)));
        assertEquals(S1GameplayAudioTimeline.OwnerClass.SPECIAL_SFX,
                admission.arbitration().getFirst().finalOwner().ownerClass());
    }

    @Test
    void producerClassifiesD0AsSpecialBeforeConstructingAdmission() {
        assertEquals(S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX,
                S1Ghz1OpenGgfAudioTimelineCapture.CaptureState.soundClassForSfx(0xD0));
    }

    @Test
    void d0FallbackRoleLookupUsesTheLiveSfxSequencer() {
        // Break caught: SPECIAL_SFX semantic ownership was mistaken for a non-SFX sequencer.
        assertTrue(S1Ghz1OpenGgfAudioTimelineCapture.CaptureState.matchesLiveSequencerRole(
                S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX, true));
        assertFalse(S1Ghz1OpenGgfAudioTimelineCapture.CaptureState.matchesLiveSequencerRole(
                S1GameplayAudioTimeline.SoundClass.SPECIAL_SFX, false));
    }

    private static Path[] requestedPaths(String run, String output) {
        if (run == null || output == null) {
            throw new IllegalArgumentException(RUN_PROPERTY + " and " + OUTPUT_PROPERTY
                    + " must be provided together");
        }
        return new Path[] {Path.of(run), Path.of(output)};
    }
}
