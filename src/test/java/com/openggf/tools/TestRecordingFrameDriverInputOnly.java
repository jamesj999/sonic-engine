package com.openggf.tools;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestRecordingFrameDriverInputOnly {

    @Test
    void pausedAdmissionRetainsDeferredSeamlessBoundaryAuthority() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/openggf/tools/RecordingFrameDriver.java"));
        int paused = source.indexOf(
                "if (lastFrameResult == LevelFrameResult.PAUSED)");
        int boundary = source.indexOf(
                "if (pendingSeamlessBoundaryCompletion)", paused);

        assertTrue(paused >= 0 && boundary > paused,
                "pause must return before deferred seamless boundary authority is cleared");
    }

    @Test
    void setupOnlyDoesNotPublishTheRetriedRowsActionEdge() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        RecordingFrameDriver driver = new RecordingFrameDriver(fixture.sprite());
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-setup-retry.bk2"),
                "logkey",
                Map.of(),
                List.of(jumpRightFrame(0)),
                1);
        driver.setBk2Movie(movie, 0);

        driver.stepFrameFromRecording();

        assertEquals(com.openggf.LevelFrameResult.SETUP_ONLY,
                driver.getLastFrameResult());
        assertEquals(1, driver.getRecordingFramesRemaining());
        assertEquals(0, driver.getFrameCounter());
        assertFalse(fixture.sprite().isForcedJumpPress(),
                "unconsumed BK2 input must not be visible to initial Process_Sprites");

        driver.stepFrameFromRecording();

        assertEquals(com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                driver.getLastFrameResult());
        assertEquals(0, driver.getRecordingFramesRemaining());
        assertEquals(1, driver.getFrameCounter());
    }

    @Test
    void inputOnlyJumpEdgeLatchesForFollowingGameplayFrame() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(0, 0)
                    .build();
            RecordingFrameDriver driver = new RecordingFrameDriver(fixture.sprite());
            Bk2Movie movie = new Bk2Movie(
                    Path.of("synthetic-input-only.bk2"),
                    "logkey",
                    Map.of(),
                    List.of(
                            jumpRightFrame(0),
                            jumpRightFrame(1)),
                    1);
            driver.setBk2Movie(movie, 0);
            int expectedMask = AbstractPlayableSprite.INPUT_RIGHT
                    | AbstractPlayableSprite.INPUT_JUMP;

            assertEquals(expectedMask, driver.consumeRecordingFrameInputOnly(),
                    "the consumed row still supplies the trace validation mask");
            assertEquals(0, driver.getFrameCounter(),
                    "ADVANCE_ONLY must not tick gameplay");
            assertEquals(1, driver.getRecordingFramesRemaining(),
                    "the input-only row advances exactly one BK2 cursor entry");
            assertEquals(expectedMask, driver.peekRecordingInputAt(-1),
                    "the consumed controller state remains the prior latched BK2 row");
            assertTrue(fixture.sprite().isForcedJumpPress(),
                    "the consumed action edge must remain pending for the next gameplay dispatch");

            assertEquals(expectedMask, driver.stepFrameFromRecording(),
                    "the following held row keeps its own validation mask");
            assertEquals(0, driver.getFrameCounter(),
                    "the initial Process_Sprites pass must not tick gameplay");
            assertEquals(1, driver.getRecordingFramesRemaining(),
                    "SETUP_ONLY must retry the same BK2 row");

            assertEquals(expectedMask, driver.stepFrameFromRecording(),
                    "the retried row keeps the same validation mask");

            assertEquals(1, driver.getFrameCounter());
            assertEquals(0, driver.getRecordingFramesRemaining());
            assertTrue(fixture.sprite().getAir(),
                    "the pending action edge must make the following gameplay frame jump");
            assertTrue(fixture.sprite().getYSpeed() < 0,
                    "the following gameplay dispatch must consume the latched jump edge");
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkipIntros != null ? oldSkipIntros : false);
        }
    }

    private static Bk2FrameInput jumpRightFrame(int index) {
        return new Bk2FrameInput(
                index,
                AbstractPlayableSprite.INPUT_RIGHT,
                1,
                false,
                "");
    }
}
