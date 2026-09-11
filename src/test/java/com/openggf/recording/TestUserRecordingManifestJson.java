package com.openggf.game.recording;

import com.openggf.version.BuildIdentity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestUserRecordingManifestJson {
    @Test
    void manifestJsonRoundtripsMetadataAndToleratesReservedSidecarInterval() throws Exception {
        Instant createdAt = DEFAULT_CREATED_AT;
        UserRecordingManifest manifest = sampleManifest();

        String json = UserRecordingJson.writeManifest(manifest);
        UserRecordingManifest roundtripped = UserRecordingJson.readManifest(json);

        assertEquals("A", roundtripped.jumpActionButton());
        assertEquals(List.of("tails", "knuckles"), roundtripped.launchContext().sidekickCharacters());
        assertEquals(UserRecordingSidecarMetadata.CURRENT_DESYNC_LITE_SCHEMA_VERSION,
                roundtripped.sidecar().desyncLiteSchemaVersion());
        assertEquals("every-frame", roundtripped.sidecar().sampleMode());
        assertNull(roundtripped.sidecar().sampleInterval());
        assertEquals(createdAt, roundtripped.createdAt());

        String fixtureWithReservedInterval = json.replace(
                "\"sampleInterval\" : null",
                "\"sampleInterval\" : 30");
        UserRecordingManifest withReservedInterval = UserRecordingJson.readManifest(fixtureWithReservedInterval);

        assertEquals(30, withReservedInterval.sidecar().sampleInterval());
        assertEquals(createdAt, withReservedInterval.createdAt());
    }

    @Test
    void manifestJsonRoundtripsNullableDeterminismMetadata() throws Exception {
        UserRecordingManifest sample = sampleManifest();
        UserRecordingManifest manifest = new UserRecordingManifest(
                sample.schemaVersion(),
                sample.movieName(),
                sample.engineIdentity(),
                sample.launchContext(),
                sample.sidecar(),
                new RecordingDeterminismMetadata(null, null),
                sample.jumpActionButton(),
                sample.frameCount(),
                sample.stopReason(),
                sample.createdAt());

        String json = UserRecordingJson.writeManifest(manifest);
        UserRecordingManifest roundtripped = UserRecordingJson.readManifest(json);

        assertNull(roundtripped.determinism().initialLevelFrameCounter());
        assertNull(roundtripped.determinism().initialRngSeed());
    }

    @Test
    void launchContextDefensivelyCopiesSidekickCharacters() {
        List<String> sidekickCharacters = new ArrayList<>(List.of("tails"));

        RecordingLaunchContext launchContext = new RecordingLaunchContext(
                "s3k",
                0,
                1,
                "sonic",
                sidekickCharacters,
                true,
                "current-act-fresh-start");
        sidekickCharacters.add("knuckles");

        assertEquals(List.of("tails"), launchContext.sidekickCharacters());
        assertThrows(UnsupportedOperationException.class, () -> launchContext.sidekickCharacters().add("amy"));
    }

    @Test
    void launchContextTreatsNullSidekickCharactersAsEmpty() {
        RecordingLaunchContext launchContext = new RecordingLaunchContext(
                "s3k",
                0,
                1,
                "sonic",
                null,
                true,
                "current-act-fresh-start");

        assertEquals(List.of(), launchContext.sidekickCharacters());
    }

    @Test
    void readManifestReturnsUnmodifiableSidekickCharacters() throws Exception {
        UserRecordingManifest manifest = UserRecordingJson.readManifest(UserRecordingJson.writeManifest(sampleManifest()));

        assertThrows(UnsupportedOperationException.class,
                () -> manifest.launchContext().sidekickCharacters().add("amy"));
    }

    @Test
    void readManifestRejectsUnsupportedSchemaVersion() throws Exception {
        String json = UserRecordingJson.writeManifest(sampleManifest())
                .replace("\"schemaVersion\" : 1", "\"schemaVersion\" : 999");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingRequiredField() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "  \"movieName\" : \"s3k-aiz1-test\",");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingLaunchContextGameId() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"gameId\" : \"s3k\",");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingLaunchContextZone() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"zone\" : 0,");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingLaunchContextAct() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"act\" : 1,");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingLaunchContextDebugToolsEnabled() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"debugToolsEnabled\" : true,");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingEngineIdentityBaseVersion() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"baseVersion\" : \"0.6.prerelease\",");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingEngineIdentityDirty() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"dirty\" : false");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsNullEngineIdentityDirty() throws Exception {
        String json = UserRecordingJson.writeManifest(sampleManifest())
                .replace("\"dirty\" : false",
                        "\"dirty\" : null");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingSidecarSampleMode() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"sampleMode\" : \"every-frame\",");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsMissingSidecarDesyncLiteSchemaVersion() throws Exception {
        String json = removeJsonLine(
                UserRecordingJson.writeManifest(sampleManifest()),
                "    \"desyncLiteSchemaVersion\" : 1,");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsZeroSidecarDesyncLiteSchemaVersion() throws Exception {
        String json = UserRecordingJson.writeManifest(sampleManifest())
                .replace("\"desyncLiteSchemaVersion\" : 1",
                        "\"desyncLiteSchemaVersion\" : 0");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsUnsupportedJumpActionButton() throws Exception {
        String json = UserRecordingJson.writeManifest(sampleManifest())
                .replace("\"jumpActionButton\" : \"A\"",
                        "\"jumpActionButton\" : \"B\"");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestRejectsInvalidCreatedAt() throws Exception {
        String json = UserRecordingJson.writeManifest(sampleManifest())
                .replace("\"createdAt\" : \"2026-06-29T14:30:22Z\"",
                        "\"createdAt\" : \"not-a-timestamp\"");

        assertThrows(IOException.class, () -> UserRecordingJson.readManifest(json));
    }

    @Test
    void readManifestMapsFutureStopReasonToUnknown() throws Exception {
        String json = UserRecordingJson.writeManifest(sampleManifest())
                .replace("\"stopReason\" : \"USER_STOPPED\"",
                        "\"stopReason\" : \"SCHEDULED_AUTOSTOP\"");

        UserRecordingManifest manifest = UserRecordingJson.readManifest(json);

        assertEquals(UserRecordingStopReason.UNKNOWN, manifest.stopReason());
    }

    private static final Instant DEFAULT_CREATED_AT = Instant.parse("2026-06-29T14:30:22Z");

    private static UserRecordingManifest sampleManifest() {
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                "s3k-aiz1-test",
                new BuildIdentity("0.6.prerelease", "abcdef123", false),
                new RecordingLaunchContext(
                        "s3k",
                        0,
                        1,
                        "sonic",
                        List.of("tails", "knuckles"),
                        true,
                        "current-act-fresh-start"),
                UserRecordingSidecarMetadata.everyFrame(),
                new RecordingDeterminismMetadata(42, 123456789L),
                "A",
                600,
                UserRecordingStopReason.USER_STOPPED,
                DEFAULT_CREATED_AT);
    }

    private static String removeJsonLine(String json, String line) {
        return json.replace(line + System.lineSeparator(), "");
    }
}
