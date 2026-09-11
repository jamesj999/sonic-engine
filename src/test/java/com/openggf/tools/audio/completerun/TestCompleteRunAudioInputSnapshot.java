package com.openggf.tools.audio.completerun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.CompleteRunFixture;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ManagedObserverAdapter;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ManifestSegment;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerKind;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.ProducerRuntimeIdentity;
import com.openggf.tools.audio.completerun.CompleteRunAudioTrace.RuntimeArtifact;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class TestCompleteRunAudioInputSnapshot {
    @TempDir Path temporary;

    @Test
    void bindsOnlyCompletedPrivateCopiesAndRemovesThemOnClose() throws Exception {
        Fixture source = fixture();
        Path output = temporary.resolve("capture").toAbsolutePath();
        Path privateRoot;

        try (var snapshot = CompleteRunAudioInputSnapshot.bind(source.request(output), source.profile)) {
            CompleteRunAudioProducer.Request bound = snapshot.request();
            privateRoot = snapshot.root();
            assertNotEquals(source.rom, bound.rom());
            assertNotEquals(source.bk2, bound.bk2());
            assertNotEquals(source.manifest, bound.runManifest());
            assertNotEquals(source.referenceHome, bound.referenceHome());
            assertNotEquals(output, bound.output());
            assertFalse(bound.output().startsWith(privateRoot),
                    "the transactional output staging path must remain outside the input snapshot");
            assertEquals(PosixFilePermissions.fromString("rwx------"),
                    Files.getPosixFilePermissions(privateRoot));

            Files.writeString(source.rom, "mutated-rom");
            Files.writeString(source.bk2, "mutated-movie");
            Files.writeString(source.manifest, "mutated-manifest");
            Files.writeString(source.launcher, "mutated-launcher");

            assertEquals("rom", Files.readString(bound.rom()));
            assertEquals("movie", Files.readString(bound.bk2()));
            assertEquals("manifest", Files.readString(bound.runManifest()));
            assertEquals("launcher", Files.readString(bound.referenceHome().resolve(
                    "bizhawk-headless/run-complete-audio.sh")));
            assertEquals(source.referenceTreeSha256,
                    CompleteRunAudioTool.installationTreeDigest(bound.referenceHome()));
        }

        assertFalse(Files.exists(privateRoot));
        assertFalse(Files.exists(output));
    }

    @Test
    void rejectsAnUnpinnedReferenceHomeBeforeExposingABoundRequest() throws Exception {
        Fixture source = fixture();
        Files.writeString(source.referenceHome.resolve("unreviewed-extra"), "poison");
        AtomicReference<CompleteRunAudioProducer.Request> exposed = new AtomicReference<>();

        assertThrows(IllegalArgumentException.class, () -> {
            try (var snapshot = CompleteRunAudioInputSnapshot.bind(
                    source.request(temporary.resolve("rejected").toAbsolutePath()), source.profile)) {
                exposed.set(snapshot.request());
            }
        });

        assertEquals(null, exposed.get());
        assertFalse(hasPrivateSnapshot());
    }

    @Test
    void cleansPrivateInputsWhenTheConsumerOrCleanupFails() throws Exception {
        Fixture source = fixture();
        Path output = temporary.resolve("failed").toAbsolutePath();

        Exception failure = assertThrows(Exception.class, () ->
                CompleteRunAudioInputSnapshot.withBoundRequest(source.request(output), source.profile,
                        request -> {
                            createCanonicalStore(request.output(), "unpublished");
                            throw new Exception("synthetic producer/projector failure");
                        }));

        assertEquals("synthetic producer/projector failure", failure.getMessage());
        assertFalse(hasPrivateSnapshot());
        assertFalse(Files.exists(output));
        assertFalse(hasPublicationStaging());
    }

    @ParameterizedTest
    @EnumSource(TraceChaserAudioProcess.Game.class)
    void s2AndS3kProcessesConsumeOnlySnapshotPaths(TraceChaserAudioProcess.Game game) throws Exception {
        Fixture source = fixture();
        Path output = temporary.resolve("capture-" + game.name()).toAbsolutePath();

        CompleteRunAudioInputSnapshot.withBoundRequest(source.request(output), source.profile, bound -> {
            Files.writeString(source.rom, "mutated-rom");
            Files.writeString(source.bk2, "mutated-movie");
            Files.writeString(source.launcher, "mutated-launcher");
            var process = new TraceChaserAudioProcess(invocation -> {
                List<String> arguments = invocation.arguments();
                Path privateRom = Path.of(arguments.get(arguments.indexOf("--rom") + 1));
                Path privateMovie = Path.of(arguments.get(arguments.indexOf("--movie") + 1));
                Path privateLauncher = Path.of(arguments.get(0));
                assertEquals("rom", Files.readString(privateRom));
                assertEquals("movie", Files.readString(privateMovie));
                assertEquals("launcher", Files.readString(privateLauncher));
                assertTrue(privateRom.startsWith(bound.rom().getParent()));
                assertTrue(privateMovie.startsWith(bound.bk2().getParent()));
                assertTrue(privateLauncher.startsWith(bound.referenceHome()));
                assertEquals(game == TraceChaserAudioProcess.Game.S2,
                        arguments.contains("--capability"));
                Path raw = Path.of(arguments.get(arguments.indexOf("--output") + 1));
                Files.writeString(raw, "raw\n");
                return new CompletedProcess();
            });
            try (var raw = process.capture(bound, game)) {
                assertEquals("raw\n", Files.readString(raw.raw()));
            }
            new CompleteRunAudioCaptureStore().writeNew(bound.output(),
                    TestCompleteRunAudioCaptureStore.metadata(1),
                    TestCompleteRunAudioCaptureStore.records(1).iterator());
        });

        assertTrue(Files.isSymbolicLink(output));
        assertTrue(Files.isRegularFile(output.resolve("manifest.json")));
        assertFalse(hasPrivateSnapshot());
        assertFalse(hasPublicationStaging());
    }

    @Test
    void rejectsLinkedInputsWithoutPublishingOrLeavingPrivateState() throws Exception {
        Fixture source = fixture();
        Path linked = temporary.resolve("linked.bk2").toAbsolutePath();
        Files.createSymbolicLink(linked, source.bk2);
        var request = new CompleteRunAudioProducer.Request(ProducerKind.REFERENCE, "profile",
                source.rom, linked, source.manifest, source.referenceHome,
                temporary.resolve("linked-output").toAbsolutePath());

        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioInputSnapshot.bind(request, source.profile));
        assertFalse(hasPrivateSnapshot());
    }

    @Test
    void rejectsLinkedAncestorsHardlinksAndReferenceOutputOverlap() throws Exception {
        Fixture source = fixture();
        Path linkedDirectory = temporary.resolve("linked-inputs");
        Path realDirectory = Files.createDirectory(temporary.resolve("real-inputs"));
        Path nestedMovie = Files.writeString(realDirectory.resolve("movie.bk2"), "movie");
        Files.createSymbolicLink(linkedDirectory, realDirectory);
        var linkedAncestor = new CompleteRunAudioProducer.Request(ProducerKind.REFERENCE, "profile",
                source.rom, linkedDirectory.resolve("movie.bk2"), source.manifest,
                source.referenceHome, temporary.resolve("linked-ancestor-output").toAbsolutePath());
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioInputSnapshot.bind(linkedAncestor, source.profile));

        Path hardlink = temporary.resolve("hardlinked.bk2");
        Files.createLink(hardlink, nestedMovie);
        var hardlinked = new CompleteRunAudioProducer.Request(ProducerKind.REFERENCE, "profile",
                source.rom, hardlink, source.manifest, source.referenceHome,
                temporary.resolve("hardlink-output").toAbsolutePath());
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioInputSnapshot.bind(hardlinked, source.profile));

        var overlapping = source.request(source.referenceHome.resolve("capture"));
        assertThrows(IllegalArgumentException.class,
                () -> CompleteRunAudioInputSnapshot.bind(overlapping, source.profile));
        assertFalse(hasPrivateSnapshot());
    }

    @Test
    void cleanupFailureCannotPublishTheCompletedPrivateStore() throws Exception {
        Fixture source = fixture();
        Path output = temporary.resolve("cleanup-failed").toAbsolutePath();
        AtomicReference<Path> privateRoot = new AtomicReference<>();

        Exception failure = assertThrows(Exception.class, () -> CompleteRunAudioInputSnapshot.withBoundRequest(
                source.request(output), source.profile, bound -> {
                    privateRoot.set(bound.rom().getParent());
                    new CompleteRunAudioCaptureStore().writeNew(bound.output(),
                            TestCompleteRunAudioCaptureStore.metadata(1),
                            TestCompleteRunAudioCaptureStore.records(1).iterator());
                    Files.setPosixFilePermissions(bound.referenceHome(), Set.of());
                }));

        assertFalse(Files.exists(output), "cleanup failure must precede durable publication");
        assertFalse(hasPublicationStaging(), () -> "publication staging survived: " + failure
                + " suppressed=" + java.util.Arrays.toString(failure.getSuppressed()));
        Path root = privateRoot.get();
        Path privateHome = root.resolve("reference-home");
        Files.setPosixFilePermissions(privateHome, PosixFilePermissions.fromString("rwx------"));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    @Test
    void competingFinalTargetIsNeverReplacedAndPrivateBackingIsRemoved() throws Exception {
        Fixture source = fixture();
        Path output = temporary.resolve("competing-output").toAbsolutePath();

        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> CompleteRunAudioInputSnapshot.withBoundRequest(
                        source.request(output), source.profile, bound -> {
                            new CompleteRunAudioCaptureStore().writeNew(bound.output(),
                                    TestCompleteRunAudioCaptureStore.metadata(1),
                                    TestCompleteRunAudioCaptureStore.records(1).iterator());
                            Files.createDirectory(output);
                            Files.writeString(output.resolve("sentinel"), "keep");
                        }));

        assertEquals("keep", Files.readString(output.resolve("sentinel")));
        assertFalse(hasPrivateSnapshot());
        assertFalse(hasPublicationStaging());
        assertFalse(hasPublishedBacking());
    }

    @Test
    void replacedInputRootIsNeverRecursivelyDeleted() throws Exception {
        Fixture source = fixture();
        var snapshot = CompleteRunAudioInputSnapshot.bind(
                source.request(temporary.resolve("root-replaced").toAbsolutePath()), source.profile);
        Path owned = snapshot.root();
        Path moved = owned.resolveSibling("moved-owned-inputs");
        Files.move(owned, moved);
        Files.createDirectory(owned);
        Files.writeString(owned.resolve("foreign-sentinel"), "keep");

        assertThrows(java.io.IOException.class, snapshot::close);
        assertEquals("keep", Files.readString(owned.resolve("foreign-sentinel")));

        try (var paths = Files.walk(owned)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
        try (var paths = Files.walk(moved)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private static void createCanonicalStore(Path output, String content) throws Exception {
        Path backing = Files.createTempDirectory(output.getParent(), ".audio-published-");
        Files.writeString(backing.resolve("manifest.json"), content);
        Files.createSymbolicLink(output, backing.getFileName());
    }

    private Fixture fixture() throws Exception {
        Path rom = Files.writeString(temporary.resolve("game.gen"), "rom").toAbsolutePath();
        Path bk2 = Files.writeString(temporary.resolve("movie.bk2"), "movie").toAbsolutePath();
        Path manifest = Files.writeString(temporary.resolve("run.json"), "manifest").toAbsolutePath();
        Path referenceHome = Files.createDirectory(temporary.resolve("reference-home")).toAbsolutePath();
        Path headless = Files.createDirectory(referenceHome.resolve("bizhawk-headless"));
        Path fixtures = Files.createDirectory(headless.resolve("fixtures"));
        Path launcher = Files.writeString(headless.resolve("run-complete-audio.sh"), "launcher");
        Files.writeString(fixtures.resolve("gpgx-audio-service-manifests-v1.json"), "service");
        Files.writeString(fixtures.resolve("gpgx-audio-capability-v1.json"), "capability");
        launcher.toFile().setExecutable(true, true);
        Path dependency = Files.writeString(referenceHome.resolve("dependency"), "dependency");
        Files.setPosixFilePermissions(referenceHome, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(headless, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(fixtures, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.setPosixFilePermissions(dependency, PosixFilePermissions.fromString("rw-r--r--"));
        String tree = CompleteRunAudioTool.installationTreeDigest(referenceHome);
        CompleteRunFixture fixture = new CompleteRunFixture(digest("SHA-1", rom), "00000000",
                digest("SHA-256", bk2), 1, digest("SHA-256", manifest),
                List.of(new ManifestSegment("all", 0, 1)), 0, 1);
        ProducerRuntimeIdentity identity = new ProducerRuntimeIdentity("reference", "1", "emulator", "1",
                "core", "1", ManagedObserverAdapter.CALLBACK_ONLY,
                Map.of(RuntimeArtifact.REFERENCE_INSTALLATION_TREE, tree));
        CompleteRunAudioProfile profile = mock(CompleteRunAudioProfile.class);
        when(profile.fixture()).thenReturn(fixture);
        when(profile.producerRuntimeIdentities()).thenReturn(Map.of(ProducerKind.REFERENCE, identity));
        return new Fixture(rom, bk2, manifest, referenceHome, launcher, tree, profile);
    }

    private boolean hasPrivateSnapshot() throws Exception {
        try (var entries = Files.list(temporary)) {
            return entries.anyMatch(path -> path.getFileName().toString().startsWith(".audio-inputs-"));
        }
    }

    private boolean hasPublicationStaging() throws Exception {
        try (var entries = Files.list(temporary)) {
            return entries.anyMatch(path -> path.getFileName().toString().contains(".audio-publication-"));
        }
    }

    private boolean hasPublishedBacking() throws Exception {
        try (var entries = Files.list(temporary)) {
            return entries.anyMatch(path -> path.getFileName().toString().startsWith(".audio-published-"));
        }
    }

    private static String digest(String algorithm, Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(Files.readAllBytes(path)));
    }

    private record Fixture(Path rom, Path bk2, Path manifest, Path referenceHome, Path launcher,
            String referenceTreeSha256, CompleteRunAudioProfile profile) {
        CompleteRunAudioProducer.Request request(Path output) {
            return new CompleteRunAudioProducer.Request(ProducerKind.REFERENCE, "profile",
                    rom, bk2, manifest, referenceHome, output);
        }
    }

    private static final class CompletedProcess extends Process {
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return 0; }
        @Override public int exitValue() { return 0; }
        @Override public void destroy() { }
    }
}
