package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLiveRewindStepperAdmission {

    @Test
    void logicalInputPublicationFollowsSetupAdmission() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/openggf/game/rewind/LiveRewindStepper.java"));
        int admission = source.indexOf("LevelFrameStep.admit(");
        int setupReturn = source.indexOf(
                "if (admission.result() == LevelFrameResult.SETUP_ONLY)", admission);
        int publication = source.indexOf("liveInput.setLogicalOverride(", setupReturn);

        assertTrue(admission >= 0 && setupReturn > admission && publication > setupReturn,
                "rewind logical input must not be published until setup admission succeeds");
    }
}
