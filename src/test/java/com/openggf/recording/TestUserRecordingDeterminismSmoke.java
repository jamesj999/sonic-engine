package com.openggf.game.recording;

import com.openggf.tests.FullReset;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@FullReset
@ExtendWith(SingletonResetExtension.class)
class TestUserRecordingDeterminismSmoke {

    @TempDir
    Path tempDir;

    @Test
    void freshStartRecordingReplaysCleanlyThroughSharedCoreDriver() throws Exception {
        UserRecordingSmokeHarness.SelectedRom selectedRom = UserRecordingSmokeHarness.selectAvailableRom();
        UserRecordingSmokeHarness.Result result = UserRecordingSmokeHarness.recordAndReplay(
                selectedRom,
                tempDir,
                120);

        assertEquals(120, result.recordedFrameCount());
        assertEquals(UserRecordingVerificationResult.clean(120), result.verification(),
                "fresh-start recording smoke gates core driver determinism only; "
                        + "GameLoop wrapper, runtime mode routing, UI, rendering suppression, "
                        + "and transitions remain covered by later tasks");
    }
}
