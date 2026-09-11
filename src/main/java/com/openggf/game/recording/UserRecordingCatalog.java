package com.openggf.game.recording;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.Bk2MovieLoader;
import com.openggf.version.BuildIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class UserRecordingCatalog {
    private static final String MANIFEST_ENTRY = "OpenGGF/manifest.json";
    private static final String BK2_EXTENSION = ".bk2";
    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();

    private final Path root;
    private final Bk2MovieLoader movieLoader;

    public UserRecordingCatalog() {
        this(Path.of("."));
    }

    public UserRecordingCatalog(Path root) {
        this(root, new Bk2MovieLoader());
    }

    UserRecordingCatalog(Path root, Bk2MovieLoader movieLoader) {
        this.root = Objects.requireNonNull(root, "root");
        this.movieLoader = Objects.requireNonNull(movieLoader, "movieLoader");
    }

    public static List<UserRecordingEntry> scan(String gameId, BuildIdentity currentIdentity) throws IOException {
        return new UserRecordingCatalog().scanFromRoot(gameId, currentIdentity);
    }

    public static List<UserRecordingEntry> scan(Path root, String gameId, BuildIdentity currentIdentity)
            throws IOException {
        return new UserRecordingCatalog(root).scanFromRoot(gameId, currentIdentity);
    }

    private List<UserRecordingEntry> scanFromRoot(String gameId, BuildIdentity currentIdentity) throws IOException {
        Objects.requireNonNull(gameId, "gameId");
        Path gameRecordingDir = root.resolve("recordings").resolve(gameId);
        if (!Files.isDirectory(gameRecordingDir)) {
            return List.of();
        }

        List<UserRecordingEntry> entries = new ArrayList<>();
        try (var paths = Files.list(gameRecordingDir)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(UserRecordingCatalog::isBk2)
                    .toList()) {
                entries.add(readEntry(path, currentIdentity));
            }
        }
        entries.sort(Comparator.comparing(UserRecordingEntry::modifiedAt).reversed()
                .thenComparing(entry -> entry.path().getFileName().toString()));
        return List.copyOf(entries);
    }

    private UserRecordingEntry readEntry(Path path, BuildIdentity currentIdentity) {
        Instant modifiedAt = modifiedAt(path);
        UserRecordingManifest manifest = null;
        int frameCount = 0;
        String loadError = null;

        try {
            manifest = readManifest(path);
        } catch (IOException ex) {
            loadError = loadError(path, ex);
        }

        try {
            Bk2Movie movie = movieLoader.load(path);
            frameCount = movie.getFrameCount();
        } catch (IOException ex) {
            loadError = appendLoadError(loadError, loadError(path, ex));
        }

        return new UserRecordingEntry(
                path,
                displayName(path, manifest),
                manifest,
                frameCount,
                modifiedAt,
                classifyVersionWarning(manifest, currentIdentity),
                loadError);
    }

    private static UserRecordingManifest readManifest(Path path) throws IOException {
        try (ZipFile zip = new ZipFile(path.toFile())) {
            ZipEntry entry = findEntryIgnoreCase(zip, MANIFEST_ENTRY);
            if (entry == null) {
                throw new IOException("BK2 missing required entry: " + MANIFEST_ENTRY);
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return readCatalogManifest(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    private static UserRecordingManifest readCatalogManifest(String json) throws IOException {
        try {
            return UserRecordingJson.readManifest(json);
        } catch (IOException ex) {
            if (!isMissingEngineIdentityError(ex)) {
                throw ex;
            }
            return readManifestWithoutEngineIdentity(json, ex);
        }
    }

    private static boolean isMissingEngineIdentityError(IOException ex) {
        return "Missing required manifest field: engineIdentity".equals(ex.getMessage());
    }

    private static UserRecordingManifest readManifestWithoutEngineIdentity(String json, IOException original)
            throws IOException {
        JsonNode root = MANIFEST_MAPPER.readTree(json);
        if (!(root instanceof ObjectNode objectNode) || objectNode.hasNonNull("engineIdentity")) {
            throw original;
        }

        objectNode.set("engineIdentity", MANIFEST_MAPPER.createObjectNode()
                .put("baseVersion", "unknown")
                .put("commit", "")
                .put("dirty", false));
        UserRecordingManifest validated = UserRecordingJson.readManifest(MANIFEST_MAPPER.writeValueAsString(objectNode));
        return new UserRecordingManifest(
                validated.schemaVersion(),
                validated.movieName(),
                null,
                validated.launchContext(),
                validated.sidecar(),
                validated.determinism(),
                validated.jumpActionButton(),
                validated.frameCount(),
                validated.stopReason(),
                validated.createdAt());
    }

    private static ZipEntry findEntryIgnoreCase(ZipFile zip, String expectedName) {
        String expected = expectedName.toLowerCase(Locale.ROOT);
        return zip.stream()
                .filter(entry -> entry.getName().toLowerCase(Locale.ROOT).equals(expected))
                .findFirst()
                .orElse(null);
    }

    private static RecordingVersionWarning classifyVersionWarning(UserRecordingManifest manifest,
            BuildIdentity currentIdentity) {
        if (manifest == null || manifest.engineIdentity() == null || currentIdentity == null) {
            return RecordingVersionWarning.MISSING_METADATA;
        }

        BuildIdentity recordedIdentity = manifest.engineIdentity();
        if (recordedIdentity.dirty() || currentIdentity.dirty()) {
            return RecordingVersionWarning.DIRTY_BUILD;
        }

        boolean recordedPrerelease = recordedIdentity.isPrerelease();
        boolean currentPrerelease = currentIdentity.isPrerelease();
        if (!recordedPrerelease && !currentPrerelease
                && !normalized(recordedIdentity.baseVersion()).equals(normalized(currentIdentity.baseVersion()))) {
            return RecordingVersionWarning.OFFICIAL_VERSION_MISMATCH;
        }

        if ((recordedPrerelease || currentPrerelease) && !recordedIdentity.isCompatibleWith(currentIdentity)) {
            return RecordingVersionWarning.PRERELEASE_BUILD_MISMATCH;
        }

        return RecordingVersionWarning.NONE;
    }

    private static boolean isBk2(Path path) {
        return path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(BK2_EXTENSION);
    }

    private static Instant modifiedAt(Path path) {
        try {
            return Files.getLastModifiedTime(path).toInstant();
        } catch (IOException ex) {
            return Instant.EPOCH;
        }
    }

    private static String displayName(Path path, UserRecordingManifest manifest) {
        if (manifest != null && manifest.movieName() != null && !manifest.movieName().isBlank()) {
            return manifest.movieName();
        }
        String fileName = path.getFileName().toString();
        if (fileName.toLowerCase(Locale.ROOT).endsWith(BK2_EXTENSION)) {
            return fileName.substring(0, fileName.length() - BK2_EXTENSION.length());
        }
        return fileName;
    }

    private static String loadError(Path path, IOException ex) {
        return path.getFileName() + ": " + ex.getMessage();
    }

    private static String appendLoadError(String current, String next) {
        if (current == null || current.isBlank()) {
            return next;
        }
        return current + "; " + next;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
