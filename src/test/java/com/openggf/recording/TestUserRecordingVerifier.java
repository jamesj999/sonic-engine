package com.openggf.game.recording;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestUserRecordingVerifier {
    @Test
    void identicalFramesAreClean() {
        UserRecordingVerifier verifier = UserRecordingVerifier.forTesting(List.of(frame(0)), frame(0));

        verifier.observer().afterFrameAdvanced(movieFrame(0), false);

        UserRecordingVerificationResult result = verifier.result();
        assertEquals("clean", result.status());
        assertTrue(result.clean());
        assertEquals(1, result.comparedFrames());
        assertFalse(verifier.hasMismatch());
    }

    @Test
    void centerXMismatchReportsFieldAndFrame() {
        UserRecordingVerifier verifier = UserRecordingVerifier.forTesting(
                List.of(frame(4)),
                withCentreX(frame(4), 321));

        verifier.observer().afterFrameAdvanced(movieFrame(4), false);

        UserRecordingVerificationResult result = verifier.result();
        assertEquals("first-mismatch(frame=4, field=p1CentreX, expected=100, actual=321)",
                result.status());
        assertFalse(result.clean());
        assertEquals(1, result.comparedFrames());
        assertEquals(4, result.firstMismatchFrame());
        assertEquals("p1CentreX", result.firstMismatchField());
        assertEquals("100", result.expectedValue());
        assertEquals("321", result.actualValue());
        assertTrue(verifier.hasMismatch());
    }

    @Test
    void missingSparseFrameIsIgnored() {
        UserRecordingVerifier verifier = UserRecordingVerifier.forTesting(
                List.of(frame(2)),
                new UserRecordingSidecarMetadata(
                        UserRecordingSidecarMetadata.CURRENT_DESYNC_LITE_SCHEMA_VERSION,
                        "sparse",
                        30),
                frame(0));

        verifier.observer().afterFrameAdvanced(movieFrame(0), false);

        UserRecordingVerificationResult result = verifier.result();
        assertEquals("clean", result.status());
        assertTrue(result.clean());
        assertEquals(0, result.comparedFrames());
    }

    @Test
    void missingEveryFrameRowReportsTruncatedSidecar() {
        UserRecordingVerifier verifier = UserRecordingVerifier.forTesting(
                List.of(frame(2)),
                UserRecordingSidecarMetadata.everyFrame(),
                frame(0));

        verifier.observer().afterFrameAdvanced(movieFrame(0), false);

        UserRecordingVerificationResult result = verifier.result();
        assertEquals("truncated-sidecar", result.status());
        assertFalse(result.clean());
        assertEquals(0, result.comparedFrames());
    }

    @Test
    void missingSidecarIsVisibleNonBlockingStatus() {
        UserRecordingVerifier verifier = UserRecordingVerifier.missingSidecar();

        verifier.observer().afterFrameAdvanced(movieFrame(0), false);

        UserRecordingVerificationResult result = verifier.result();
        assertEquals("missing-sidecar", result.status());
        assertFalse(result.clean());
        assertEquals(0, result.comparedFrames());
        assertFalse(verifier.hasMismatch());
    }

    @Test
    void unsupportedSchemaIsVisibleNonBlockingStatus() {
        UserRecordingVerifier verifier = UserRecordingVerifier.unsupportedSchema(99);

        verifier.observer().afterFrameAdvanced(movieFrame(0), false);

        UserRecordingVerificationResult result = verifier.result();
        assertEquals("schema-unsupported", result.status());
        assertFalse(result.clean());
        assertEquals(0, result.comparedFrames());
        assertFalse(verifier.hasMismatch());
    }

    @Test
    void laterMismatchesDoNotOverwriteFirstMismatch() {
        UserRecordingVerifier verifier = UserRecordingVerifier.forTesting(
                List.of(frame(1), frame(2)),
                frame(1),
                withCameraX(frame(2), 777),
                withCentreX(frame(1), 999));

        verifier.observer().afterFrameAdvanced(movieFrame(1), false);
        verifier.observer().afterFrameAdvanced(movieFrame(2), false);
        verifier.observer().afterFrameAdvanced(movieFrame(1), false);

        UserRecordingVerificationResult result = verifier.result();
        assertFalse(result.clean());
        assertEquals(3, result.comparedFrames());
        assertEquals(2, result.firstMismatchFrame());
        assertEquals("cameraX", result.firstMismatchField());
        assertEquals("300", result.expectedValue());
        assertEquals("777", result.actualValue());
    }

    @Test
    void observerNeverSkipsGameplayTicks() {
        UserRecordingVerifier verifier = UserRecordingVerifier.forTesting(List.of(frame(0)), frame(0));

        assertFalse(verifier.observer().shouldSkipGameplayTick(movieFrame(0)));
    }

    @Test
    void mainPlayerResolverReportsConfiguredMissingSpriteCode() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        SpriteManager spriteManager = new SpriteManager(config);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> RecordingMainPlayerResolver.resolve(config, spriteManager));

        assertEquals("Main playable sprite not available for code: knuckles", error.getMessage());
    }

    private static Bk2FrameInput movieFrame(int frame) {
        return new Bk2FrameInput(frame, 0, 0, false, 0, 0, false, "");
    }

    private static DesyncLiteFrame frame(int frame) {
        return new DesyncLiteFrame(
                frame,
                100,
                200,
                10,
                20,
                30,
                0x02,
                5,
                300,
                400,
                12,
                34,
                1,
                7,
                5000);
    }

    private static DesyncLiteFrame withCentreX(DesyncLiteFrame source, int centreX) {
        return new DesyncLiteFrame(
                source.frame(),
                centreX,
                source.p1CentreY(),
                source.p1XSpeed(),
                source.p1YSpeed(),
                source.p1Inertia(),
                source.p1Status(),
                source.p1Animation(),
                source.cameraX(),
                source.cameraY(),
                source.timerFrames(),
                source.timerSeconds(),
                source.timerMinutes(),
                source.ringCount(),
                source.score());
    }

    private static DesyncLiteFrame withCameraX(DesyncLiteFrame source, int cameraX) {
        return new DesyncLiteFrame(
                source.frame(),
                source.p1CentreX(),
                source.p1CentreY(),
                source.p1XSpeed(),
                source.p1YSpeed(),
                source.p1Inertia(),
                source.p1Status(),
                source.p1Animation(),
                cameraX,
                source.cameraY(),
                source.timerFrames(),
                source.timerSeconds(),
                source.timerMinutes(),
                source.ringCount(),
                source.score());
    }
}
