package com.openggf.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.FileLockInterruptionException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

public final class RetroArchGlslShaderPackDownloader {
    public static final URI DEFAULT_ARCHIVE_URI = URI.create(
            "https://github.com/libretro/glsl-shaders/archive/refs/heads/master.zip");
    public static final String INSTALL_DIR_NAME = "libretro-glsl";
    public static final String METADATA_FILE_NAME = ".openggf-libretro-glsl.properties";

    private static final int BUFFER_SIZE = 64 * 1024;
    private static final String LOCK_FILE_NAME = ".openggf-libretro-glsl.lock";
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final HttpTransport transport;

    public RetroArchGlslShaderPackDownloader() {
        this(new JavaHttpTransport());
    }

    public RetroArchGlslShaderPackDownloader(HttpTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public InstallResult download(Path shaderRoot, ProgressListener progress) throws ShaderPackDownloadException {
        ProgressListener listener = progressOrNone(progress);
        Path root = normalizeRoot(shaderRoot);
        return withInstallLock(root, () -> downloadLocked(root, listener));
    }

    public InstallResult downloadIfNewer(Path shaderRoot, ProgressListener progress) throws ShaderPackDownloadException {
        ProgressListener listener = progressOrNone(progress);
        Path root = normalizeRoot(shaderRoot);
        return withInstallLock(root, () -> {
            UpdateStatus status = checkForUpdateLocked(root, listener);
            if (status.state() == UpdateState.UP_TO_DATE) {
                return new InstallResult(root.resolve(INSTALL_DIR_NAME), false, status.etag(), status.lastModified());
            }
            return downloadLocked(root, listener);
        });
    }

    public UpdateStatus checkForUpdate(Path shaderRoot, ProgressListener progress) throws ShaderPackDownloadException {
        ProgressListener listener = progressOrNone(progress);
        Path root = normalizeRoot(shaderRoot);
        if (!Files.exists(root)) {
            return new UpdateStatus(UpdateState.NOT_INSTALLED, null, null);
        }
        return withInstallLock(root, () -> checkForUpdateLocked(root, listener));
    }

    private InstallResult downloadLocked(Path root, ProgressListener listener) throws ShaderPackDownloadException {
        Path installRoot = root.resolve(INSTALL_DIR_NAME);
        Path stagingRoot = root.resolve(".openggf-libretro-glsl-staging-" + UUID.randomUUID());
        Path archivePart = stagingRoot.resolve("archive.zip.part");
        Path extractedInstallRoot = stagingRoot.resolve(INSTALL_DIR_NAME);
        Path backupRoot = stagingRoot.resolve("previous-" + INSTALL_DIR_NAME);
        boolean movedInstallToBackup = false;
        boolean installed = false;

        try {
            createDirectory(root);
            createDirectory(stagingRoot);
            DownloadIdentity identity = downloadArchive(archivePart, listener);
            extractArchive(archivePart, extractedInstallRoot, listener);
            writeMetadata(extractedInstallRoot, identity);

            if (Files.exists(installRoot)) {
                move(installRoot, backupRoot);
                movedInstallToBackup = true;
            }
            move(extractedInstallRoot, installRoot);
            installed = true;
            safeDeleteRecursive(root, backupRoot);

            Properties metadata = loadMetadata(installRoot.resolve(METADATA_FILE_NAME));
            listener.onProgress(Stage.COMPLETE, 1, 1, installRoot.toString());
            return new InstallResult(installRoot, true, emptyToNull(metadata.getProperty("etag")),
                    emptyToNull(metadata.getProperty("lastModified")));
        } catch (ShaderPackDownloadException ex) {
            if (movedInstallToBackup && !installed) {
                restoreBackup(root, backupRoot, installRoot);
            }
            throw ex;
        } finally {
            if (!movedInstallToBackup || installed || !Files.exists(backupRoot)) {
                bestEffortSafeDeleteRecursive(root, stagingRoot);
            }
        }
    }

    private UpdateStatus checkForUpdateLocked(Path root, ProgressListener listener) throws ShaderPackDownloadException {
        Path installRoot = root.resolve(INSTALL_DIR_NAME);
        Path metadataPath = installRoot.resolve(METADATA_FILE_NAME);
        if (!Files.isDirectory(installRoot) || !Files.exists(metadataPath)) {
            return new UpdateStatus(UpdateState.NOT_INSTALLED, null, null);
        }

        Properties metadata;
        try {
            metadata = loadMetadata(metadataPath);
        } catch (ShaderPackDownloadException ex) {
            listener.onProgress(Stage.COMPLETE, 1, 1, "metadata unreadable; update available");
            return new UpdateStatus(UpdateState.UPDATE_AVAILABLE, null, null);
        }
        String storedEtag = emptyToNull(metadata.getProperty("etag"));
        String storedLastModified = emptyToNull(metadata.getProperty("lastModified"));
        Map<String, String> headers = new LinkedHashMap<>();
        if (storedEtag != null) {
            headers.put("If-None-Match", storedEtag);
        }
        if (storedLastModified != null) {
            headers.put("If-Modified-Since", storedLastModified);
        }

        listener.onProgress(Stage.CHECKING, 0, -1, DEFAULT_ARCHIVE_URI.toString());
        HttpResponse response = send(new HttpRequest("HEAD", DEFAULT_ARCHIVE_URI, headers));
        try (response) {
            if (response.statusCode() == 304) {
                listener.onProgress(Stage.COMPLETE, 1, 1, "up to date");
                return new UpdateStatus(UpdateState.UP_TO_DATE, storedEtag, storedLastModified);
            }
            if (response.statusCode() != 200) {
                throw failure(FailureReason.HTTP_STATUS,
                        "HTTP " + response.statusCode() + " while checking " + DEFAULT_ARCHIVE_URI);
            }

            String remoteEtag = firstHeader(response.headers(), "ETag").orElse(null);
            String remoteLastModified = firstHeader(response.headers(), "Last-Modified").orElse(null);
            UpdateState state = identityMatches(storedEtag, storedLastModified, remoteEtag, remoteLastModified)
                    ? UpdateState.UP_TO_DATE
                    : UpdateState.UPDATE_AVAILABLE;
            listener.onProgress(Stage.COMPLETE, 1, 1, state.name());
            return new UpdateStatus(state, remoteEtag, remoteLastModified);
        } catch (IOException ex) {
            throw failure(FailureReason.NETWORK, "Failed to close update-check response", ex);
        }
    }

    private <T> T withInstallLock(Path root, LockOperation<T> operation) throws ShaderPackDownloadException {
        createDirectory(root);
        Path lockPath = root.resolve(LOCK_FILE_NAME);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(root, ignored -> new ReentrantLock());
        boolean jvmLockHeld = false;
        try {
            jvmLock.lockInterruptibly();
            jvmLockHeld = true;
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } catch (InterruptedException | FileLockInterruptionException ex) {
            Thread.currentThread().interrupt();
            throw failure(FailureReason.INTERRUPTED, "Interrupted while waiting for shader pack install lock", ex);
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to lock shader pack install root " + root, ex);
        } finally {
            if (jvmLockHeld) {
                jvmLock.unlock();
            }
        }
    }

    private DownloadIdentity downloadArchive(Path archivePart, ProgressListener progress) throws ShaderPackDownloadException {
        HttpResponse response = send(new HttpRequest("GET", DEFAULT_ARCHIVE_URI, Map.of()));
        try (response) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure(FailureReason.HTTP_STATUS,
                        "HTTP " + response.statusCode() + " while downloading " + DEFAULT_ARCHIVE_URI);
            }
            long progressTotal = firstHeader(response.headers(), "Content-Length")
                    .flatMap(RetroArchGlslShaderPackDownloader::parseLong)
                    .orElse(-1L);
            long partialExpected = progressTotal >= 0 ? progressTotal : response.contentLength();
            progress.onProgress(Stage.DOWNLOADING, 0, progressTotal, DEFAULT_ARCHIVE_URI.toString());

            long downloaded = copyResponseBody(response.body(), archivePart, progress, progressTotal);
            if (partialExpected >= 0 && downloaded != partialExpected) {
                deleteIfExists(archivePart);
                throw failure(FailureReason.PARTIAL_DOWNLOAD,
                        "Expected " + partialExpected + " bytes but received " + downloaded);
            }
            return new DownloadIdentity(firstHeader(response.headers(), "ETag").orElse(null),
                    firstHeader(response.headers(), "Last-Modified").orElse(null));
        } catch (IOException ex) {
            deleteIfExists(archivePart);
            throw failure(FailureReason.NETWORK, "Failed to close download response", ex);
        }
    }

    private long copyResponseBody(InputStream body,
                                  Path archivePart,
                                  ProgressListener progress,
                                  long progressTotal) throws ShaderPackDownloadException {
        long downloaded = 0;
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = body; OutputStream out = Files.newOutputStream(archivePart)) {
            while (true) {
                int read;
                try {
                    read = in.read(buffer);
                } catch (IOException ex) {
                    throw failure(FailureReason.NETWORK, "Failed while reading shader archive", ex);
                }
                if (read < 0) {
                    break;
                }
                try {
                    out.write(buffer, 0, read);
                } catch (IOException ex) {
                    throw failure(FailureReason.DISK, "Failed while writing shader archive", ex);
                }
                downloaded += read;
                progress.onProgress(Stage.DOWNLOADING, downloaded, progressTotal, DEFAULT_ARCHIVE_URI.toString());
            }
        } catch (ShaderPackDownloadException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to create shader archive temp file", ex);
        }
        return downloaded;
    }

    private void extractArchive(Path archivePart, Path extractedInstallRoot, ProgressListener progress)
            throws ShaderPackDownloadException {
        createDirectory(extractedInstallRoot);
        int extractedEntries = 0;
        try (InputStream fileIn = Files.newInputStream(archivePart);
             ZipInputStream zip = new ZipInputStream(fileIn)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String strippedName = stripFirstPathSegment(entry.getName());
                if (strippedName.isBlank()) {
                    continue;
                }
                Path target = extractedInstallRoot.resolve(strippedName).normalize();
                if (!target.startsWith(extractedInstallRoot)) {
                    throw failure(FailureReason.INVALID_ARCHIVE, "Archive entry escapes install root: " + entry.getName());
                }

                if (entry.isDirectory()) {
                    createDirectory(target);
                } else {
                    createDirectory(target.getParent());
                    try (OutputStream out = Files.newOutputStream(target)) {
                        zip.transferTo(out);
                    }
                    extractedEntries++;
                    progress.onProgress(Stage.EXTRACTING, extractedEntries, -1, strippedName);
                }
                zip.closeEntry();
            }
        } catch (ZipException ex) {
            throw failure(FailureReason.INVALID_ARCHIVE, "Invalid shader archive", ex);
        } catch (ShaderPackDownloadException ex) {
            throw ex;
        } catch (IOException ex) {
            throw failure(FailureReason.EXTRACTION, "Failed to extract shader archive", ex);
        }

        if (extractedEntries == 0) {
            throw failure(FailureReason.INVALID_ARCHIVE, "Shader archive did not contain extractable files");
        }
    }

    private void writeMetadata(Path installRoot, DownloadIdentity identity) throws ShaderPackDownloadException {
        Properties metadata = new Properties();
        metadata.setProperty("archiveUrl", DEFAULT_ARCHIVE_URI.toString());
        metadata.setProperty("etag", nullToEmpty(identity.etag()));
        metadata.setProperty("lastModified", nullToEmpty(identity.lastModified()));
        metadata.setProperty("installedAt", Instant.now().toString());
        try (OutputStream out = Files.newOutputStream(installRoot.resolve(METADATA_FILE_NAME))) {
            metadata.store(out, "OpenGGF libretro GLSL shader pack metadata");
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to write shader pack metadata", ex);
        }
    }

    private static boolean identityMatches(String storedEtag,
                                           String storedLastModified,
                                           String remoteEtag,
                                           String remoteLastModified) {
        if (storedEtag != null && remoteEtag != null) {
            return storedEtag.equals(remoteEtag);
        }
        if (storedLastModified != null && remoteLastModified != null) {
            return storedLastModified.equals(remoteLastModified);
        }
        return false;
    }

    private HttpResponse send(HttpRequest request) throws ShaderPackDownloadException {
        try {
            return transport.send(request);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw failure(FailureReason.INTERRUPTED, "Interrupted while contacting " + request.uri(), ex);
        } catch (IOException ex) {
            throw failure(FailureReason.NETWORK, "Failed to contact " + request.uri(), ex);
        }
    }

    private static Path normalizeRoot(Path shaderRoot) {
        return Objects.requireNonNull(shaderRoot, "shaderRoot").toAbsolutePath().normalize();
    }

    private static void createDirectory(Path path) throws ShaderPackDownloadException {
        try {
            Files.createDirectories(path);
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to create directory " + path, ex);
        }
    }

    private static void move(Path source, Path target) throws ShaderPackDownloadException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to move " + source + " to " + target, ex);
        }
    }

    private static Properties loadMetadata(Path path) throws ShaderPackDownloadException {
        Properties metadata = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            metadata.load(in);
            return metadata;
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to read shader pack metadata " + path, ex);
        }
    }

    private static String stripFirstPathSegment(String entryName) throws ShaderPackDownloadException {
        String normalizedName = entryName.replace('\\', '/');
        if (normalizedName.startsWith("/") || normalizedName.matches("^[A-Za-z]:.*")) {
            throw failure(FailureReason.INVALID_ARCHIVE, "Archive entry uses an absolute path: " + entryName);
        }
        int slash = normalizedName.indexOf('/');
        if (slash < 0 || slash == normalizedName.length() - 1) {
            return "";
        }
        return normalizedName.substring(slash + 1);
    }

    private static Optional<String> firstHeader(Map<String, List<String>> headers, String name) {
        String lowerName = name.toLowerCase(Locale.ROOT);
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(lowerName))
                .flatMap(entry -> entry.getValue().stream())
                .filter(value -> value != null && !value.isBlank())
                .findFirst();
    }

    private static Optional<Long> parseLong(String value) {
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static ProgressListener progressOrNone(ProgressListener progress) {
        return progress == null ? ProgressListener.NONE : progress;
    }

    static HttpClient newDefaultHttpClient() {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static ShaderPackDownloadException failure(FailureReason reason, String message) {
        return new ShaderPackDownloadException(reason, message);
    }

    private static ShaderPackDownloadException failure(FailureReason reason, String message, Throwable cause) {
        return new ShaderPackDownloadException(reason, message, cause);
    }

    private static void deleteIfExists(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best-effort cleanup; the original failure reason is more useful to callers.
        }
    }

    private static void restoreBackup(Path root, Path backupRoot, Path installRoot) {
        try {
            if (Files.exists(backupRoot) && backupRoot.toAbsolutePath().normalize().startsWith(root)) {
                safeDeleteRecursive(root, installRoot);
                Files.move(backupRoot, installRoot, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | ShaderPackDownloadException ignored) {
            // Preserve the original install failure.
        }
    }

    private static void safeDeleteRecursive(Path root, Path path) throws ShaderPackDownloadException {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root) || normalized.equals(root)) {
            throw failure(FailureReason.DISK, "Refusing to delete outside shader root: " + path);
        }
        if (!Files.exists(normalized)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(normalized)) {
            paths.sorted(Comparator.reverseOrder()).forEach(candidate -> {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException ex) {
                    throw new CleanupException(ex);
                }
            });
        } catch (CleanupException ex) {
            throw failure(FailureReason.DISK, "Failed to clean up " + path, ex.getCause());
        } catch (IOException ex) {
            throw failure(FailureReason.DISK, "Failed to clean up " + path, ex);
        }
    }

    private static void bestEffortSafeDeleteRecursive(Path root, Path path) {
        try {
            safeDeleteRecursive(root, path);
        } catch (ShaderPackDownloadException ignored) {
            // Keep the original operation result visible to the caller.
        }
    }

    public enum Stage { CHECKING, DOWNLOADING, EXTRACTING, COMPLETE }

    public enum UpdateState { NOT_INSTALLED, UP_TO_DATE, UPDATE_AVAILABLE }

    public enum FailureReason { NETWORK, HTTP_STATUS, INTERRUPTED, PARTIAL_DOWNLOAD, INVALID_ARCHIVE, EXTRACTION, DISK }

    @FunctionalInterface
    public interface ProgressListener {
        ProgressListener NONE = (stage, completed, total, detail) -> {
        };

        void onProgress(Stage stage, long completed, long total, String detail);
    }

    public record InstallResult(Path installRoot, boolean downloaded, String etag, String lastModified) {
    }

    public record UpdateStatus(UpdateState state, String etag, String lastModified) {
    }

    private record DownloadIdentity(String etag, String lastModified) {
    }

    @FunctionalInterface
    private interface LockOperation<T> {
        T run() throws ShaderPackDownloadException;
    }

    public record HttpRequest(String method, URI uri, Map<String, String> headers) {
        public HttpRequest {
            method = Objects.requireNonNull(method, "method");
            uri = Objects.requireNonNull(uri, "uri");
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
        }
    }

    public record HttpResponse(int statusCode,
                               Map<String, List<String>> headers,
                               InputStream body,
                               long contentLength) implements AutoCloseable {
        public HttpResponse {
            headers = Map.copyOf(Objects.requireNonNull(headers, "headers"));
            body = Objects.requireNonNull(body, "body");
        }

        @Override
        public void close() throws IOException {
            body.close();
        }
    }

    @FunctionalInterface
    public interface HttpTransport {
        HttpResponse send(HttpRequest request) throws IOException, InterruptedException;
    }

    public static final class ShaderPackDownloadException extends Exception {
        private final FailureReason reason;

        public ShaderPackDownloadException(FailureReason reason, String message) {
            super(message);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public ShaderPackDownloadException(FailureReason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        public FailureReason reason() {
            return reason;
        }
    }

    private static final class JavaHttpTransport implements HttpTransport {
        private final HttpClient client = newDefaultHttpClient();

        @Override
        public HttpResponse send(HttpRequest request) throws IOException, InterruptedException {
            java.net.http.HttpRequest.Builder builder = java.net.http.HttpRequest.newBuilder(request.uri());
            request.headers().forEach(builder::header);
            java.net.http.HttpRequest.BodyPublisher bodyPublisher = java.net.http.HttpRequest.BodyPublishers.noBody();
            java.net.http.HttpRequest javaRequest = builder.method(request.method(), bodyPublisher).build();
            java.net.http.HttpResponse<InputStream> response = client.send(javaRequest,
                    java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            return new HttpResponse(response.statusCode(), response.headers().map(), response.body(),
                    response.headers().firstValueAsLong("Content-Length").orElse(-1));
        }
    }

    private static final class CleanupException extends RuntimeException {
        CleanupException(IOException cause) {
            super(cause);
        }

        @Override
        public IOException getCause() {
            return (IOException) super.getCause();
        }
    }
}
