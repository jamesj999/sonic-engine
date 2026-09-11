package com.openggf.game.recording;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openggf.version.BuildIdentity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public final class UserRecordingJson {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private UserRecordingJson() {
    }

    public static String writeManifest(UserRecordingManifest manifest) throws JsonProcessingException {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(ManifestDto.from(manifest));
    }

    public static void writeManifest(Path path, UserRecordingManifest manifest) throws IOException {
        Files.writeString(path, writeManifest(manifest), StandardCharsets.UTF_8);
    }

    public static UserRecordingManifest readManifest(String json) throws IOException {
        return MAPPER.readValue(json, ManifestDto.class).toManifest();
    }

    public static UserRecordingManifest readManifest(Path path) throws IOException {
        return readManifest(Files.readString(path, StandardCharsets.UTF_8));
    }

    private record ManifestDto(
            Integer schemaVersion,
            String movieName,
            EngineIdentityDto engineIdentity,
            LaunchContextDto launchContext,
            UserRecordingSidecarMetadata sidecar,
            RecordingDeterminismMetadata determinism,
            String jumpActionButton,
            Integer frameCount,
            String stopReason,
            String createdAt
    ) {
        static ManifestDto from(UserRecordingManifest manifest) {
            return new ManifestDto(
                    manifest.schemaVersion(),
                    manifest.movieName(),
                    EngineIdentityDto.from(manifest.engineIdentity()),
                    LaunchContextDto.from(manifest.launchContext()),
                    manifest.sidecar(),
                    manifest.determinism(),
                    manifest.jumpActionButton(),
                    manifest.frameCount(),
                    manifest.stopReason() == null ? null : manifest.stopReason().name(),
                    manifest.createdAt() == null ? null : manifest.createdAt().toString());
        }

        UserRecordingManifest toManifest() throws IOException {
            validate();
            return new UserRecordingManifest(
                    schemaVersion,
                    movieName,
                    engineIdentity.toBuildIdentity(),
                    launchContext.toLaunchContext(),
                    sidecar,
                    determinism,
                    jumpActionButton,
                    frameCount,
                    parseStopReason(stopReason),
                    parseCreatedAt(createdAt));
        }

        private void validate() throws IOException {
            requirePresent(schemaVersion, "schemaVersion");
            if (schemaVersion != UserRecordingManifest.CURRENT_SCHEMA_VERSION) {
                throw new IOException("Unsupported manifest schema version: " + schemaVersion);
            }
            requirePresent(movieName, "movieName");
            requirePresent(engineIdentity, "engineIdentity");
            requirePresent(launchContext, "launchContext");
            requirePresent(sidecar, "sidecar");
            requirePresent(determinism, "determinism");
            requirePresent(jumpActionButton, "jumpActionButton");
            requirePresent(frameCount, "frameCount");
            requirePresent(stopReason, "stopReason");
            requirePresent(createdAt, "createdAt");
            validateEngineIdentity(engineIdentity);
            validateLaunchContext(launchContext);
            validateSidecar(sidecar);
            validateJumpActionButton(jumpActionButton);
        }

        private static void requirePresent(Object value, String fieldName) throws IOException {
            if (value == null) {
                throw new IOException("Missing required manifest field: " + fieldName);
            }
        }

        private static void requireNonBlank(String value, String fieldName) throws IOException {
            requirePresent(value, fieldName);
            if (value.trim().isEmpty()) {
                throw new IOException("Missing required manifest field: " + fieldName);
            }
        }

        private static void validateEngineIdentity(EngineIdentityDto engineIdentity) throws IOException {
            requireNonBlank(engineIdentity.baseVersion(), "engineIdentity.baseVersion");
            requirePresent(engineIdentity.dirty(), "engineIdentity.dirty");
        }

        private static void validateLaunchContext(LaunchContextDto launchContext) throws IOException {
            requireNonBlank(launchContext.gameId(), "launchContext.gameId");
            requirePresent(launchContext.zone(), "launchContext.zone");
            requirePresent(launchContext.act(), "launchContext.act");
            requireNonBlank(launchContext.mainCharacter(), "launchContext.mainCharacter");
            requirePresent(launchContext.debugToolsEnabled(), "launchContext.debugToolsEnabled");
            requireNonBlank(launchContext.launchRoute(), "launchContext.launchRoute");
        }

        private static void validateSidecar(UserRecordingSidecarMetadata sidecar) throws IOException {
            if (sidecar.desyncLiteSchemaVersion() <= 0) {
                throw new IOException("Missing required manifest field: sidecar.desyncLiteSchemaVersion");
            }
            requireNonBlank(sidecar.sampleMode(), "sidecar.sampleMode");
        }

        private static void validateJumpActionButton(String jumpActionButton) throws IOException {
            if (!"A".equals(jumpActionButton)) {
                throw new IOException("Unsupported manifest jumpActionButton: " + jumpActionButton);
            }
        }

        private static UserRecordingStopReason parseStopReason(String value) {
            try {
                return UserRecordingStopReason.valueOf(value);
            } catch (IllegalArgumentException ex) {
                return UserRecordingStopReason.UNKNOWN;
            }
        }

        private static Instant parseCreatedAt(String value) throws IOException {
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException ex) {
                throw new IOException("Invalid manifest createdAt timestamp: " + value, ex);
            }
        }
    }

    private record EngineIdentityDto(
            String baseVersion,
            String commit,
            Boolean dirty
    ) {
        static EngineIdentityDto from(BuildIdentity identity) {
            return new EngineIdentityDto(identity.baseVersion(), identity.commit(), identity.dirty());
        }

        BuildIdentity toBuildIdentity() {
            return new BuildIdentity(baseVersion, commit, dirty);
        }
    }

    private record LaunchContextDto(
            String gameId,
            Integer zone,
            Integer act,
            String mainCharacter,
            java.util.List<String> sidekickCharacters,
            Boolean debugToolsEnabled,
            String launchRoute
    ) {
        static LaunchContextDto from(RecordingLaunchContext launchContext) {
            return new LaunchContextDto(
                    launchContext.gameId(),
                    launchContext.zone(),
                    launchContext.act(),
                    launchContext.mainCharacter(),
                    launchContext.sidekickCharacters(),
                    launchContext.debugToolsEnabled(),
                    launchContext.launchRoute());
        }

        RecordingLaunchContext toLaunchContext() {
            return new RecordingLaunchContext(
                    gameId,
                    zone,
                    act,
                    mainCharacter,
                    sidekickCharacters,
                    debugToolsEnabled,
                    launchRoute);
        }
    }
}
