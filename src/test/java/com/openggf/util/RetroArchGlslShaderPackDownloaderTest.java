package com.openggf.util;

import com.openggf.util.RetroArchGlslShaderPackDownloader.FailureReason;
import com.openggf.util.RetroArchGlslShaderPackDownloader.HttpRequest;
import com.openggf.util.RetroArchGlslShaderPackDownloader.HttpResponse;
import com.openggf.util.RetroArchGlslShaderPackDownloader.InstallResult;
import com.openggf.util.RetroArchGlslShaderPackDownloader.ProgressListener;
import com.openggf.util.RetroArchGlslShaderPackDownloader.ShaderPackDownloadException;
import com.openggf.util.RetroArchGlslShaderPackDownloader.Stage;
import com.openggf.util.RetroArchGlslShaderPackDownloader.UpdateState;
import com.openggf.util.RetroArchGlslShaderPackDownloader.UpdateStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.function.Predicate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RetroArchGlslShaderPackDownloaderTest {

    @TempDir
    Path tempDir;

    @Test
    public void downloadExtractsArchiveIntoLibretroFolderAndWritesMetadata() throws Exception {
        byte[] archive = zip(Map.of(
                "glsl-shaders-master/crt/guest/foo.glslp", "shader0 = foo.glsl\n",
                "glsl-shaders-master/crt/guest/foo.glsl", "void main() {}\n",
                "glsl-shaders-master/README.md", "libretro glsl\n"));
        FakeTransport transport = new FakeTransport().when("GET", 200, headers("ETag", "\"a1\"", "Last-Modified",
                "Mon, 15 Jun 2026 12:00:00 GMT", "Content-Length", Integer.toString(archive.length)), archive);
        List<ProgressEvent> events = new ArrayList<>();

        InstallResult result = new RetroArchGlslShaderPackDownloader(transport).download(tempDir,
                (stage, completed, total, detail) -> events.add(new ProgressEvent(stage, completed, total, detail)));

        Path installRoot = tempDir.resolve(RetroArchGlslShaderPackDownloader.INSTALL_DIR_NAME);
        assertEquals(installRoot, result.installRoot());
        assertTrue(result.downloaded());
        assertEquals("\"a1\"", result.etag());
        assertEquals("Mon, 15 Jun 2026 12:00:00 GMT", result.lastModified());
        assertEquals("shader0 = foo.glsl\n", Files.readString(installRoot.resolve("crt/guest/foo.glslp")));
        assertEquals("void main() {}\n", Files.readString(installRoot.resolve("crt/guest/foo.glsl")));
        assertFalse(Files.exists(installRoot.resolve("glsl-shaders-master")));

        Properties metadata = loadMetadata(installRoot);
        assertEquals(RetroArchGlslShaderPackDownloader.DEFAULT_ARCHIVE_URI.toString(), metadata.getProperty("archiveUrl"));
        assertEquals("\"a1\"", metadata.getProperty("etag"));
        assertEquals("Mon, 15 Jun 2026 12:00:00 GMT", metadata.getProperty("lastModified"));
        assertNotNull(metadata.getProperty("installedAt"));
        assertTrue(events.stream().anyMatch(e -> e.stage() == Stage.DOWNLOADING && e.total() == archive.length));
        assertTrue(events.stream().anyMatch(e -> e.stage() == Stage.EXTRACTING));
        assertEquals(Stage.COMPLETE, events.get(events.size() - 1).stage());
    }

    @Test
    public void downloadIfNewerSkipsWhenStoredEtagStillMatches() throws Exception {
        seedInstall("\"same\"", "Mon, 15 Jun 2026 12:00:00 GMT");
        FakeTransport transport = new FakeTransport().when("HEAD", 304, headers("ETag", "\"same\""), new byte[0]);

        InstallResult result = new RetroArchGlslShaderPackDownloader(transport).downloadIfNewer(tempDir,
                ProgressListener.NONE);

        assertFalse(result.downloaded());
        assertEquals("\"same\"", result.etag());
        assertEquals(1, transport.requests().size());
        HttpRequest request = transport.requests().get(0);
        assertEquals("HEAD", request.method());
        assertEquals("\"same\"", request.headers().get("If-None-Match"));
        assertEquals("Mon, 15 Jun 2026 12:00:00 GMT", request.headers().get("If-Modified-Since"));
        assertEquals("keep\n", Files.readString(tempDir.resolve("libretro-glsl/existing.txt")));
    }

    @Test
    public void downloadIfNewerDownloadsWhenRemoteEtagChanges() throws Exception {
        seedInstall("\"old\"", "Mon, 15 Jun 2026 12:00:00 GMT");
        byte[] archive = zip(Map.of("glsl-shaders-master/new.glslp", "shader0 = new.glsl\n"));
        FakeTransport transport = new FakeTransport()
                .when("HEAD", 200, headers("ETag", "\"new\"", "Last-Modified", "Tue, 16 Jun 2026 12:00:00 GMT"),
                        new byte[0])
                .when("GET", 200, headers("ETag", "\"new\"", "Last-Modified", "Tue, 16 Jun 2026 12:00:00 GMT"),
                        archive);

        InstallResult result = new RetroArchGlslShaderPackDownloader(transport).downloadIfNewer(tempDir,
                ProgressListener.NONE);

        assertTrue(result.downloaded());
        assertEquals("\"new\"", result.etag());
        assertFalse(Files.exists(tempDir.resolve("libretro-glsl/existing.txt")));
        assertEquals("shader0 = new.glsl\n", Files.readString(tempDir.resolve("libretro-glsl/new.glslp")));
        assertEquals(List.of("HEAD", "GET"), transport.requests().stream().map(HttpRequest::method).toList());
    }

    @Test
    public void rejectsZipSlipArchiveWithoutCreatingInstallRoot() throws Exception {
        byte[] archive = zip(Map.of("glsl-shaders-master/../../escape.txt", "bad\n"));
        FakeTransport transport = new FakeTransport().when("GET", 200, headers(), archive);

        ShaderPackDownloadException failure = assertThrows(ShaderPackDownloadException.class,
                () -> new RetroArchGlslShaderPackDownloader(transport).download(tempDir, ProgressListener.NONE));

        assertEquals(FailureReason.INVALID_ARCHIVE, failure.reason());
        assertFalse(Files.exists(tempDir.resolve("libretro-glsl")));
        assertFalse(Files.exists(tempDir.resolve("escape.txt")));
    }

    @Test
    public void checkForUpdateReportsNotInstalledAndUpdateAvailability() throws Exception {
        UpdateStatus notInstalled = new RetroArchGlslShaderPackDownloader(new FakeTransport()).checkForUpdate(tempDir,
                ProgressListener.NONE);
        assertEquals(UpdateState.NOT_INSTALLED, notInstalled.state());

        seedInstall("\"old\"", null);
        FakeTransport transport = new FakeTransport().when("HEAD", 200, headers("ETag", "\"remote\""), new byte[0]);

        UpdateStatus update = new RetroArchGlslShaderPackDownloader(transport).checkForUpdate(tempDir,
                ProgressListener.NONE);

        assertEquals(UpdateState.UPDATE_AVAILABLE, update.state());
        assertEquals("\"remote\"", update.etag());
    }

    @Test
    public void unreadableMetadataIsTreatedAsUpdateable() throws Exception {
        Path installRoot = tempDir.resolve(RetroArchGlslShaderPackDownloader.INSTALL_DIR_NAME);
        Files.createDirectories(installRoot.resolve(RetroArchGlslShaderPackDownloader.METADATA_FILE_NAME));

        UpdateStatus update = new RetroArchGlslShaderPackDownloader(new FakeTransport()).checkForUpdate(tempDir,
                ProgressListener.NONE);

        assertEquals(UpdateState.UPDATE_AVAILABLE, update.state());
    }

    @Test
    public void defaultTransportFollowsNormalRedirects() {
        assertEquals(HttpClient.Redirect.NORMAL,
                RetroArchGlslShaderPackDownloader.newDefaultHttpClient().followRedirects());
    }

    @Test
    public void concurrentDownloadsForSameRootSerializeBeforeTransportAndReplace() throws Exception {
        byte[] firstArchive = zip(Map.of("glsl-shaders-master/first.glslp", "shader0 = first.glsl\n"));
        byte[] secondArchive = zip(Map.of("glsl-shaders-master/second.glslp", "shader0 = second.glsl\n"));
        BlockingTransport transport = new BlockingTransport(firstArchive, secondArchive);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<InstallResult> first = executor.submit(() -> new RetroArchGlslShaderPackDownloader(transport)
                    .download(tempDir, ProgressListener.NONE));
            // Deterministic gate: the first worker has reached the transport and
            // is now parked on releaseFirst while holding the install lock.
            assertTrue(transport.awaitFirstEntered(), "first download should reach transport");

            // The second worker signals that it is actively running (and thus
            // contending for the install lock) before we assert. This replaces a
            // fixed Thread.sleep with a real happens-before signal, so the test
            // is not racy.
            CountDownLatch secondStarted = new CountDownLatch(1);
            Future<InstallResult> second = executor.submit(() -> {
                secondStarted.countDown();
                return new RetroArchGlslShaderPackDownloader(transport)
                        .download(tempDir, ProgressListener.NONE);
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS),
                    "second download worker should start running");
            // Give the second worker a bounded window to (incorrectly) reach the
            // transport. While the first holds the install lock the count must
            // stay at 1; awaitSecondEntered must time out rather than fire.
            assertFalse(transport.awaitSecondEntered(),
                    "second download must not reach transport while first holds the install lock");
            assertEquals(1, transport.startedRequests(),
                    "second download should wait on the install lock before contacting transport");

            transport.releaseFirst();
            assertTrue(first.get(5, TimeUnit.SECONDS).downloaded());
            assertTrue(second.get(5, TimeUnit.SECONDS).downloaded());
            assertEquals(2, transport.startedRequests());
            assertTrue(Files.exists(tempDir.resolve("libretro-glsl/second.glslp")));
        } finally {
            transport.releaseFirst();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void reportsHttpNetworkInterruptedPartialInvalidArchiveAndDiskFailuresWithReasons() throws Exception {
        assertFailure(FailureReason.HTTP_STATUS,
                new FakeTransport().when("GET", 500, headers(), "server error".getBytes(StandardCharsets.UTF_8)),
                tempDir.resolve("http"));
        assertFailure(FailureReason.NETWORK, request -> {
            throw new IOException("offline");
        }, tempDir.resolve("network"));
        assertFailure(FailureReason.INTERRUPTED, request -> {
            throw new InterruptedException("stop");
        }, tempDir.resolve("interrupted"));
        assertTrue(Thread.interrupted(), "download should restore interrupt status");

        byte[] partialBody = "abc".getBytes(StandardCharsets.UTF_8);
        assertFailure(FailureReason.PARTIAL_DOWNLOAD,
                new FakeTransport().when("GET", 200, headers("Content-Length", "8"), partialBody), tempDir.resolve("partial"));
        assertFailure(FailureReason.INVALID_ARCHIVE,
                new FakeTransport().when("GET", 200, headers(), "not a zip".getBytes(StandardCharsets.UTF_8)),
                tempDir.resolve("invalid"));

        Path diskRoot = tempDir.resolve("disk");
        Files.writeString(diskRoot, "not a directory");
        assertFailure(FailureReason.DISK, new FakeTransport(), diskRoot);
    }

    private void assertFailure(FailureReason expected,
                               RetroArchGlslShaderPackDownloader.HttpTransport transport,
                               Path shaderRoot) {
        ShaderPackDownloadException failure = assertThrows(ShaderPackDownloadException.class,
                () -> new RetroArchGlslShaderPackDownloader(transport).download(shaderRoot, ProgressListener.NONE));

        assertEquals(expected, failure.reason());
        assertFalse(Files.exists(shaderRoot.resolve("download.part")));
    }

    private void seedInstall(String etag, String lastModified) throws IOException {
        Path installRoot = tempDir.resolve(RetroArchGlslShaderPackDownloader.INSTALL_DIR_NAME);
        Files.createDirectories(installRoot);
        Files.writeString(installRoot.resolve("existing.txt"), "keep\n");
        Properties metadata = new Properties();
        metadata.setProperty("archiveUrl", RetroArchGlslShaderPackDownloader.DEFAULT_ARCHIVE_URI.toString());
        metadata.setProperty("etag", etag);
        if (lastModified != null) {
            metadata.setProperty("lastModified", lastModified);
        }
        metadata.setProperty("installedAt", "2026-06-15T12:00:00Z");
        try (var out = Files.newOutputStream(installRoot.resolve(
                RetroArchGlslShaderPackDownloader.METADATA_FILE_NAME))) {
            metadata.store(out, null);
        }
    }

    private static Properties loadMetadata(Path installRoot) throws IOException {
        Properties metadata = new Properties();
        try (var in = Files.newInputStream(installRoot.resolve(
                RetroArchGlslShaderPackDownloader.METADATA_FILE_NAME))) {
            metadata.load(in);
        }
        return metadata;
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static Map<String, List<String>> headers(String... namesAndValues) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (int i = 0; i < namesAndValues.length; i += 2) {
            headers.put(namesAndValues[i], List.of(namesAndValues[i + 1]));
        }
        return headers;
    }

    private record ProgressEvent(Stage stage, long completed, long total, String detail) {
    }

    private static final class FakeTransport implements RetroArchGlslShaderPackDownloader.HttpTransport {
        private final List<ResponseRule> rules = new ArrayList<>();
        private final List<HttpRequest> requests = new ArrayList<>();

        FakeTransport when(String method, int statusCode, Map<String, List<String>> headers, byte[] body) {
            rules.add(new ResponseRule(request -> request.method().equals(method), statusCode, headers, body));
            return this;
        }

        List<HttpRequest> requests() {
            return requests;
        }

        @Override
        public HttpResponse send(HttpRequest request) throws IOException {
            requests.add(request);
            ResponseRule rule = rules.stream()
                    .filter(candidate -> candidate.matches().test(request))
                    .findFirst()
                    .orElseThrow(() -> new IOException("unexpected request " + request.method() + " " + request.uri()));
            Map<String, List<String>> responseHeaders = new HashMap<>(rule.headers());
            long contentLength = header(responseHeaders, "Content-Length")
                    .map(Long::parseLong)
                    .orElse((long) rule.body().length);
            return new HttpResponse(rule.statusCode(), responseHeaders, new ByteArrayInputStream(rule.body()),
                    contentLength);
        }

        private static java.util.Optional<String> header(Map<String, List<String>> headers, String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT)))
                    .flatMap(entry -> entry.getValue().stream())
                    .findFirst();
        }
    }

    private record ResponseRule(Predicate<HttpRequest> matches, int statusCode, Map<String, List<String>> headers,
                                byte[] body) {
    }

    private static final class BlockingTransport implements RetroArchGlslShaderPackDownloader.HttpTransport {
        private final List<byte[]> bodies;
        private final CountDownLatch firstEntered = new CountDownLatch(1);
        private final CountDownLatch secondEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirst = new CountDownLatch(1);
        private final AtomicInteger startedRequests = new AtomicInteger();

        BlockingTransport(byte[] firstBody, byte[] secondBody) {
            this.bodies = Collections.synchronizedList(new ArrayList<>(List.of(firstBody, secondBody)));
        }

        boolean awaitFirstEntered() throws InterruptedException {
            return firstEntered.await(5, TimeUnit.SECONDS);
        }

        /**
         * Bounded wait for a SECOND request to reach the transport. Expected to
         * time out (return false) while the first request holds the install
         * lock — a true return signals the serialization contract was broken.
         */
        boolean awaitSecondEntered() throws InterruptedException {
            return secondEntered.await(500, TimeUnit.MILLISECONDS);
        }

        void releaseFirst() {
            releaseFirst.countDown();
        }

        int startedRequests() {
            return startedRequests.get();
        }

        @Override
        public HttpResponse send(HttpRequest request) throws IOException, InterruptedException {
            int requestNumber = startedRequests.incrementAndGet();
            if (requestNumber == 1) {
                firstEntered.countDown();
                releaseFirst.await();
            } else {
                secondEntered.countDown();
            }
            if (bodies.isEmpty()) {
                throw new IOException("unexpected extra request");
            }
            byte[] body = bodies.remove(0);
            return new HttpResponse(200, headers("ETag", "\"etag-" + requestNumber + "\""),
                    new ByteArrayInputStream(body), body.length);
        }
    }
}
