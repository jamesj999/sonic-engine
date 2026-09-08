package com.openggf;

import com.openggf.audio.StreamedMusicPort;
import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.mods.EffectiveModCatalog;
import com.openggf.mods.ModCatalog;
import com.openggf.mods.ModMusicResolver;
import com.openggf.mods.ModRuntimeFindingStore;
import com.openggf.mods.ModAudioPreparer;
import com.openggf.mods.ModState;
import com.openggf.mods.ModStateStore;
import com.openggf.mods.ModStateSaveResult;
import com.openggf.mods.ModTrackRegistry;
import com.openggf.mods.PendingModStateEditor;
import com.openggf.mods.DefaultModRepositoryScanner;
import com.openggf.mods.ModDescriptor;
import com.openggf.mods.code.GgfMod;
import com.openggf.mods.code.ModContext;
import com.openggf.mods.PreparedAudioSession;
import com.openggf.mods.PreparedModMusic;
import com.openggf.io.ModInputLimits;
import com.openggf.graphics.PatternAtlasRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestExternalContentPolicy {
    @TempDir Path temp;

    @Test
    void maintainedModApiDocumentationDescribesCandidateReleasePolicy() throws Exception {
        String compatibility = Files.readString(Path.of(
                "docs/architecture/mod-api-compatibility.md"));
        String contentMods = Files.readString(Path.of("docs/modding/content-mods.md"));
        String manifest = Files.readString(Path.of("docs/modding/formats/manifest.md"));
        String directive = "`mod-api-release-policy.properties` is the sole authority";

        assertTrue(compatibility.contains("unpublished, mutable compiled-mod candidate"));
        assertTrue(compatibility.contains("current descriptor has an empty published set"));
        assertTrue(compatibility.contains("mod-api-signatures-MAJOR.MINOR.PATCH.txt"));
        assertTrue(contentMods.replaceAll("\\s+", " ")
                .contains("no creator contract has been published yet"));
        assertTrue(manifest.contains("current unpublished candidate is Mod API `0.7.0`"));
        assertTrue(Files.readString(Path.of("AGENTS.md")).contains(directive));
        assertTrue(Files.readString(Path.of("CLAUDE.md")).contains(directive));
    }

    @Test
    void normalModeMayScanAtBootAndUseContentInSession() {
        ExternalContentPolicy policy = new ExternalContentPolicy(ExternalContentMode.NORMAL);

        assertTrue(policy.mayScanAtBoot());
        assertTrue(policy.mayUseInSession());
    }

    @Test
    void startupDeterministicModeNeitherScansNorUsesContent() {
        ExternalContentPolicy policy = new ExternalContentPolicy(
                ExternalContentMode.STARTUP_DETERMINISTIC);

        assertFalse(policy.mayScanAtBoot());
        assertFalse(policy.mayUseInSession());
    }

    @Test
    void laterDeterministicModeRetainsBootCatalogButDisablesSessionUse() {
        ExternalContentPolicy policy = new ExternalContentPolicy(
                ExternalContentMode.SESSION_DETERMINISTIC);

        assertTrue(policy.mayScanAtBoot());
        assertFalse(policy.mayUseInSession());
    }

    @Test
    void startupDeterministicInstallDoesNotInvokeBootLoader() {
        AtomicInteger calls = new AtomicInteger();

        ModSubsystem.installAtBoot(new ExternalContentPolicy(
                ExternalContentMode.STARTUP_DETERMINISTIC), () -> {
            calls.incrementAndGet();
            throw new AssertionError("boot loader must not run");
        });

        assertSame(SessionExternalContentView.EMPTY, ModSubsystem.current().sessionView());
        assertTrue(ModSubsystem.current().processCatalog().effective().orderedEnabled().isEmpty());
        assertTrue(calls.get() == 0);
        ModSubsystem.clearProcess();
    }

    @Test
    void startupDeterministicSkipsMalformedEnabledRepositoryBeforeScannerInvocation()
            throws Exception {
        Path root = temp.resolve("mods").toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.write(root.resolve("malformed.jar"), new byte[] {1, 2, 3});
        Files.writeString(root.resolve("modstate.json"),
                "{\"formatVersion\":1,\"entries\":[{\"id\":\"malformed\","
                        + "\"enabled\":true,\"order\":0}]}");
        AtomicInteger scannerCalls = new AtomicInteger();

        ModSubsystem.installAtBoot(new ExternalContentPolicy(
                ExternalContentMode.STARTUP_DETERMINISTIC), () -> {
            scannerCalls.incrementAndGet();
            return ModSubsystem.normalBootLoader(() -> root, ModInputLimits.production(),
                    (game, id) -> true, noOpBoundary()).get();
        });

        assertTrue(scannerCalls.get() == 0);
        assertSame(SessionExternalContentView.EMPTY, ModSubsystem.current().sessionView());
        ModSubsystem.clearProcess();
    }

    @Test
    void sessionDisableAtomicallyInstallsEmptyAndClosesPreparedPresentation() {
        AtomicBoolean closed = new AtomicBoolean();
        StreamedMusicPort port = closingPort(closed);
        ModCatalog catalog = new ModCatalog(List.of(), EffectiveModCatalog.EMPTY);
        ModSubsystem subsystem = new ModSubsystem(catalog, new ModRuntimeFindingStore(),
                (rate, game) -> new SessionExternalContentView(ModMusicResolver.EMPTY, port));
        ModSubsystem.installProcess(subsystem);
        subsystem.beginNormalSession(8_000, "s1");

        ModSubsystem.disableCurrentSessionForDeterminism();

        assertTrue(closed.get());
        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertSame(catalog, subsystem.processCatalog());
        assertSame(ExternalContentMode.SESSION_DETERMINISTIC, subsystem.policy().mode());
        ModSubsystem.clearProcess();
    }

    @Test
    void endingDeterministicSessionDoesNotHotRestoreAndNextNormalLaunchRebuilds() {
        AtomicInteger preparations = new AtomicInteger();
        ModSubsystem subsystem = new ModSubsystem(
                new ModCatalog(List.of(), EffectiveModCatalog.EMPTY),
                new ModRuntimeFindingStore(), (rate, game) -> {
            preparations.incrementAndGet();
            return new SessionExternalContentView(ModMusicResolver.EMPTY,
                    closingPort(new AtomicBoolean()));
        });
        subsystem.beginNormalSession(8_000, "s1");
        subsystem.disableForDeterministicSession();

        subsystem.returnToTitle();

        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertSame(ExternalContentMode.NORMAL, subsystem.policy().mode());
        assertTrue(preparations.get() == 1);
        subsystem.beginNormalSession(8_000, "s1");
        assertTrue(preparations.get() == 2);
        subsystem.close();
    }

    @Test
    void preparedViewOwnsAndReleasesPreparedMusicLeaseExactlyOnce() {
        PreparedAudioSession audio = new PreparedAudioSession(List.of(), List.of(), Set.of());
        PreparedModMusic music = PreparedModMusic.build(EffectiveModCatalog.EMPTY,
                ModTrackRegistry.EMPTY, audio, 8_000);
        SessionExternalContentView view = SessionExternalContentView.fromPreparedMusic(music, "s1");

        view.close();
        view.close();

        assertTrue(music.isClosed());
        assertTrue(audio.isClosed());
    }

    @Test
    void managerFactoryUsesFrozenCatalogEditorAndRuntimeStore() {
        ModCatalog catalog = new ModCatalog(List.of(), EffectiveModCatalog.EMPTY);
        PendingModStateEditor editor = new PendingModStateEditor(ModState.EMPTY,
                catalog.scanned(), new ModStateStore(temp.toAbsolutePath().normalize()));
        ModSubsystem subsystem = new ModSubsystem(catalog, editor,
                new ModRuntimeFindingStore(), (rate, game) -> SessionExternalContentView.EMPTY);

        ModManagerScreenHost host = subsystem.createManager(null);

        assertTrue(host != null);
        subsystem.close();
    }

    @Test
    void transferredPortIsReleasedOnlyByInstalledAudioBoundary() {
        AtomicInteger installs = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger portCloses = new AtomicInteger();
        ModCatalog catalog = new ModCatalog(List.of(), EffectiveModCatalog.EMPTY);
        PendingModStateEditor editor = new PendingModStateEditor(ModState.EMPTY,
                catalog.scanned(), new ModStateStore(temp.toAbsolutePath().normalize()));
        ModSubsystem.SessionAudioBoundary boundary = new ModSubsystem.SessionAudioBoundary() {
            private StreamedMusicPort installed = StreamedMusicPort.EMPTY;
            @Override public void install(StreamedMusicPort port) {
                installs.incrementAndGet();
                installed = port;
            }
            @Override public void clear() {
                clears.incrementAndGet();
                StreamedMusicPort previous = installed;
                installed = StreamedMusicPort.EMPTY;
                previous.close();
            }
        };
        ModSubsystem subsystem = new ModSubsystem(catalog, editor,
                new ModRuntimeFindingStore(), (rate, game) ->
                new SessionExternalContentView(ModMusicResolver.EMPTY,
                        countingPort(portCloses)), boundary);

        subsystem.beginNormalSession(8_000, "s1");
        subsystem.disableForDeterministicSession();
        subsystem.disableForDeterministicSession();

        assertTrue(installs.get() == 1);
        assertTrue(clears.get() == 1);
        assertTrue(portCloses.get() == 1);
        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
    }

    @Test
    void deterministicDisableWinsAgainstPreparationAlreadyInFlight() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch releasePreparation = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        ModSubsystem subsystem = new ModSubsystem(
                new ModCatalog(List.of(), EffectiveModCatalog.EMPTY),
                new ModRuntimeFindingStore(), (rate, game) -> {
            preparing.countDown();
            try {
                assertTrue(releasePreparation.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(error);
            }
            return new SessionExternalContentView(ModMusicResolver.EMPTY,
                    countingPort(closes));
        });
        Thread launch = new Thread(() -> subsystem.beginNormalSession(8_000, "s1"));

        launch.start();
        assertTrue(preparing.await(5, TimeUnit.SECONDS));
        subsystem.disableForDeterministicSession();
        releasePreparation.countDown();
        launch.join(5_000);

        assertFalse(launch.isAlive());
        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertSame(ExternalContentMode.SESSION_DETERMINISTIC, subsystem.policy().mode());
        assertTrue(closes.get() == 1);
    }

    @Test
    void acceptingInstallerThatThrowsIsClearedAndFailsClosed() {
        AtomicInteger clears = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        StreamedMusicPort port = countingPort(closes);
        ModSubsystem.SessionAudioBoundary boundary = new ModSubsystem.SessionAudioBoundary() {
            private StreamedMusicPort installed = StreamedMusicPort.EMPTY;
            @Override public void install(StreamedMusicPort accepted) {
                installed = accepted;
                throw new IllegalStateException("install failed after acceptance");
            }
            @Override public void clear() {
                clears.incrementAndGet();
                StreamedMusicPort previous = installed;
                installed = StreamedMusicPort.EMPTY;
                previous.close();
            }
        };
        ModSubsystem subsystem = new ModSubsystem(
                new ModCatalog(List.of(), EffectiveModCatalog.EMPTY),
                new ModRuntimeFindingStore(), (rate, game) ->
                new SessionExternalContentView(ModMusicResolver.EMPTY, port), boundary);

        ModSubsystem target = subsystem;
        assertThrows(IllegalStateException.class,
                () -> target.beginNormalSession(8_000, "s1"));
        assertSame(SessionExternalContentView.EMPTY, target.sessionView());
        assertTrue(clears.get() == 1);
        assertTrue(closes.get() == 1);
    }

    @Test
    void audioResetAndBackendReplacementInvalidateInstalledView() {
        AudioManager audio = AudioManager.getInstance();
        audio.setStreamedMusicSessionInvalidator(() -> { });
        TrackingBackend first = new TrackingBackend();
        audio.setBackend(first);
        AtomicInteger closes = new AtomicInteger();
        ModSubsystem.SessionAudioBoundary boundary =
                ModSubsystem.SessionAudioBoundary.audioManager(audio);
        ModSubsystem subsystem = new ModSubsystem(
                new ModCatalog(List.of(), EffectiveModCatalog.EMPTY),
                new ModRuntimeFindingStore(), (rate, game) ->
                new SessionExternalContentView(ModMusicResolver.EMPTY,
                        idempotentCountingPort(rate, closes)), boundary);
        ModSubsystem.installProcess(subsystem);
        int outputRate = audio.outputSampleRate();
        subsystem.beginNormalSession(outputRate, "s1");

        audio.resetState();

        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertTrue(closes.get() == 1);

        subsystem.beginNormalSession(outputRate, "s1");
        audio.setBackend(new TrackingBackend());

        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertTrue(closes.get() == 2);
        ModSubsystem.clearProcess();
        audio.setStreamedMusicSessionInvalidator(() -> { });
        audio.resetState();
    }

    @Test
    void backendLifecycleInvalidationPreservesDeterministicPolicyAndCannotReprepare() {
        AudioManager audio = AudioManager.getInstance();
        audio.setStreamedMusicSessionInvalidator(() -> { });
        audio.setBackend(new TrackingBackend());
        AtomicInteger preparations = new AtomicInteger();
        ModSubsystem.SessionAudioBoundary boundary =
                ModSubsystem.SessionAudioBoundary.audioManager(audio);
        ModSubsystem subsystem = new ModSubsystem(
                new ModCatalog(List.of(), EffectiveModCatalog.EMPTY),
                new ModRuntimeFindingStore(), (rate, game) -> {
            preparations.incrementAndGet();
            return new SessionExternalContentView(ModMusicResolver.EMPTY,
                    idempotentCountingPort(new AtomicInteger()));
        }, boundary);
        ModSubsystem.installProcess(subsystem);
        subsystem.disableForDeterministicSession();

        audio.setBackendForLaunch(new TrackingBackend());
        subsystem.beginNormalSession(8_000, "s1");

        assertSame(ExternalContentMode.SESSION_DETERMINISTIC, subsystem.policy().mode());
        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertTrue(preparations.get() == 0);
        ModSubsystem.clearProcess();
        audio.setStreamedMusicSessionInvalidator(() -> { });
        audio.resetState();
    }

    @Test
    void strictLaunchBackendFailurePropagatesAndInvalidatesPresentation() {
        AudioManager audio = AudioManager.getInstance();
        audio.setStreamedMusicSessionInvalidator(() -> { });
        audio.setBackend(new TrackingBackend());
        AtomicInteger closes = new AtomicInteger();
        ModSubsystem.SessionAudioBoundary boundary =
                ModSubsystem.SessionAudioBoundary.audioManager(audio);
        ModSubsystem subsystem = new ModSubsystem(
                new ModCatalog(List.of(), EffectiveModCatalog.EMPTY),
                new ModRuntimeFindingStore(), (rate, game) ->
                new SessionExternalContentView(ModMusicResolver.EMPTY,
                        countingPort(rate, closes)), boundary);
        ModSubsystem.installProcess(subsystem);
        subsystem.beginNormalSession(audio.outputSampleRate(), "s1");

        assertThrows(IllegalStateException.class,
                () -> audio.setBackendForLaunch(new FailingBackend()));

        assertSame(SessionExternalContentView.EMPTY, subsystem.sessionView());
        assertTrue(audio.getBackend() instanceof NullAudioBackend);
        assertTrue(closes.get() == 1);
        ModSubsystem.clearProcess();
        audio.setStreamedMusicSessionInvalidator(() -> { });
        audio.resetState();
    }

    @Test
    void normalBootLoaderResolvesRootInsideSupplierAndBuildsManagerState() {
        AtomicInteger rootCalls = new AtomicInteger();
        Path root = temp.resolve("mods").toAbsolutePath().normalize();

        ModSubsystem.installAtBoot(new ExternalContentPolicy(ExternalContentMode.NORMAL),
                ModSubsystem.normalBootLoader(() -> {
                    rootCalls.incrementAndGet();
                    return root;
                }, ModInputLimits.production(), (game, id) -> true,
                        new ModSubsystem.SessionAudioBoundary() {
                            @Override public void install(StreamedMusicPort port) { }
                            @Override public void clear() { }
                        }));

        assertTrue(rootCalls.get() == 1);
        assertTrue(ModSubsystem.current().createManager(null) != null);
        assertSame(ExternalContentMode.NORMAL, ModSubsystem.current().policy().mode());
        ModSubsystem.clearProcess();
    }

    @Test
    void normalBootFreezesMatchingTrustedOwnersAndPersistsHashMismatchRevocation()
            throws Exception {
        Path root = Files.createDirectories(temp.resolve("trust-mods")).toAbsolutePath().normalize();
        Path jar = root.resolve("code.jar");
        writeCodeModJar(jar, "payload-one");
        ModDescriptor scanned = (ModDescriptor) new DefaultModRepositoryScanner(
                ModInputLimits.production()).scan(root).getFirst();
        ModStateStore store = new ModStateStore(root);
        store.save(new ModState(1, List.of(new ModState.Entry(
                "boot-code", true, 0, true, scanned.sha256()))));

        ModSubsystem accepted = ModSubsystem.normalBootLoader(() -> root,
                ModInputLimits.production(), (game, id) -> true, noOpBoundary()).get();
        assertTrue(accepted.processCatalog().effective().orderedEnabled().stream()
                .anyMatch(descriptor -> descriptor.manifest().id().equals("boot-code")));
        assertTrue(accepted.trustedCodeOwners().contains("boot-code"));
        assertEquals(1, accepted.patternWindowStateForSession().totalWindows());
        assertEquals(PatternAtlasRange.CONTINUE_SCREEN.endExclusive(),
                accepted.patternWindowStateForSession().assignment("boot-code")
                        .orElseThrow().base());
        accepted.disableForDeterministicSession();
        assertEquals(0, accepted.patternWindowStateForSession().totalWindows());
        accepted.close();

        writeCodeModJar(jar, "payload-two");
        ModSubsystem revoked = ModSubsystem.normalBootLoader(() -> root,
                ModInputLimits.production(), (game, id) -> true, noOpBoundary()).get();
        assertTrue(revoked.processCatalog().effective().orderedEnabled().isEmpty());
        assertTrue(revoked.trustedCodeOwners().isEmpty());
        ModState.Entry persisted = store.load().state().entries().getFirst();
        assertFalse(persisted.trusted());
        assertTrue(persisted.trustedJarSha256() == null);
        revoked.close();
    }

    @Test
    void bootRevocationSaveFailureIsSurfacedWhileTrustRemainsFailClosed() throws Exception {
        Path root = Files.createDirectories(temp.resolve("failed-revocation"))
                .toAbsolutePath().normalize();
        writeCodeModJar(root.resolve("code.jar"), "changed");
        ModDescriptor descriptor = (ModDescriptor) new DefaultModRepositoryScanner(
                ModInputLimits.production()).scan(root).getFirst();
        ModState loaded = new ModState(1, List.of(new ModState.Entry(
                "boot-code", true, 0, true, "0".repeat(64))));

        ModSubsystem.BootTrustReconciliation result = ModSubsystem.reconcileBootTrust(
                loaded, List.of(descriptor), ignored ->
                        new ModStateSaveResult.Failed("injected disk failure"));

        assertFalse(result.state().entries().getFirst().trusted());
        assertTrue(result.trustedCodeOwners().isEmpty());
        assertTrue(result.findings().get("boot-code").stream()
                .anyMatch(finding -> finding.code().equals("TRUST_REVOCATION_SAVE_FAILED")
                        && finding.message().contains("injected disk failure")));
    }

    @Test
    void normalSessionWithNoValidatedTracksInstallsCanonicalEmptyView() {
        ModAudioPreparer preparer = new ModAudioPreparer(
                temp.toAbsolutePath().normalize(), ModInputLimits.production(),
                new ModRuntimeFindingStore(), owners -> new com.openggf.mods.ModStateSaveResult.Saved());
        ModSubsystem.SessionViewFactory factory = ModSubsystem.preparedAudioFactory(
                preparer, EffectiveModCatalog.EMPTY, ModTrackRegistry.EMPTY);

        SessionExternalContentView view = factory.prepare(8_000, "s1");

        assertSame(SessionExternalContentView.EMPTY, view);
    }

    private static StreamedMusicPort closingPort(AtomicBoolean closed) {
        return new StreamedMusicPort() {
            @Override public int outputRate() { return 8_000; }
            @Override public boolean hasStockOverride(int musicId) { return false; }
            @Override public boolean isCurrentStockOverride(int musicId) { return false; }
            @Override public void playStockOverride(int musicId) { }
            @Override public boolean hasSource() { return false; }
            @Override public int mixInto(short[] output, int frames) { return 0; }
            @Override public void pause(int reason) { }
            @Override public void resume(int reason) { }
            @Override public void fadeOut(int steps, int stepDelay) { }
            @Override public void fadeIn(int steps, int stepDelay) { }
            @Override public void advanceFade() { }
            @Override public boolean fadeActive() { return false; }
            @Override public boolean fadeAtFullGain() { return true; }
            @Override public void setSpeedMultiplier(int multiplier) { }
            @Override public void stop() { }
            @Override public void reset() { }
            @Override public java.util.Optional<State> captureState() { return java.util.Optional.empty(); }
            @Override public boolean restoreState(State state) { return false; }
            @Override public void close() { closed.set(true); }
        };
    }

    private static void writeCodeModJar(Path jar, String payload) throws Exception {
        String manifest = """
                formatVersion: 1
                id: boot-code
                name: Boot Code
                version: 1.0.0
                authors: [Test]
                description: Boot trust test.
                engineApiRange: "*"
                type: patch
                baseGame: s1
                entrypoint: com.openggf.TestExternalContentPolicy$BootCodeMod
                dependencies: []
                audioOverrides: {}
                artOverrides: {}
                """;
        String classEntry = BootCodeMod.class.getName().replace('.', '/') + ".class";
        byte[] classBytes;
        try (var input = BootCodeMod.class.getClassLoader().getResourceAsStream(classEntry)) {
            classBytes = java.util.Objects.requireNonNull(input).readAllBytes();
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : Map.of(
                    "META-INF/openggf-mod.yaml", manifest.getBytes(StandardCharsets.UTF_8),
                    classEntry, classBytes,
                    "assets/payload.txt", payload.getBytes(StandardCharsets.UTF_8)).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    public static final class BootCodeMod implements GgfMod {
        @Override public void register(ModContext context) { }
    }

    private static StreamedMusicPort countingPort(AtomicInteger closes) {
        return countingPort(8_000, closes);
    }

    private static StreamedMusicPort countingPort(int outputRate, AtomicInteger closes) {
        return new StreamedMusicPort() {
            @Override public int outputRate() { return outputRate; }
            @Override public boolean hasStockOverride(int musicId) { return false; }
            @Override public boolean isCurrentStockOverride(int musicId) { return false; }
            @Override public void playStockOverride(int musicId) { }
            @Override public boolean hasSource() { return false; }
            @Override public int mixInto(short[] output, int frames) { return 0; }
            @Override public void pause(int reason) { }
            @Override public void resume(int reason) { }
            @Override public void fadeOut(int steps, int stepDelay) { }
            @Override public void fadeIn(int steps, int stepDelay) { }
            @Override public void advanceFade() { }
            @Override public boolean fadeActive() { return false; }
            @Override public boolean fadeAtFullGain() { return true; }
            @Override public void setSpeedMultiplier(int multiplier) { }
            @Override public void stop() { }
            @Override public void reset() { }
            @Override public java.util.Optional<State> captureState() { return java.util.Optional.empty(); }
            @Override public boolean restoreState(State state) { return false; }
            @Override public void close() { closes.incrementAndGet(); }
        };
    }

    private static StreamedMusicPort idempotentCountingPort(AtomicInteger closes) {
        return idempotentCountingPort(8_000, closes);
    }

    private static StreamedMusicPort idempotentCountingPort(
            int outputRate, AtomicInteger closes) {
        AtomicBoolean closed = new AtomicBoolean();
        StreamedMusicPort delegate = countingPort(outputRate, closes);
        return new DelegatingPort(delegate) {
            @Override public void close() {
                if (closed.compareAndSet(false, true)) delegate.close();
            }
        };
    }

    private static ModSubsystem.SessionAudioBoundary noOpBoundary() {
        return new ModSubsystem.SessionAudioBoundary() {
            @Override public void install(StreamedMusicPort port) { }
            @Override public void clear() { }
        };
    }

    private static final class TrackingBackend extends NullAudioBackend {
        private StreamedMusicPort port = StreamedMusicPort.EMPTY;
        @Override public void installStreamedMusicPort(StreamedMusicPort replacement) {
            port = replacement;
        }
        @Override public void resetStreamedMusicPort() {
            StreamedMusicPort previous = port;
            port = StreamedMusicPort.EMPTY;
            previous.close();
        }
        @Override public void destroy() { resetStreamedMusicPort(); }
    }

    private static final class FailingBackend extends NullAudioBackend {
        @Override public void init() { throw new IllegalStateException("device unavailable"); }
    }

    private static class DelegatingPort implements StreamedMusicPort {
        private final StreamedMusicPort delegate;
        DelegatingPort(StreamedMusicPort delegate) { this.delegate = delegate; }
        @Override public int outputRate() { return delegate.outputRate(); }
        @Override public boolean hasStockOverride(int id) { return delegate.hasStockOverride(id); }
        @Override public boolean isCurrentStockOverride(int id) { return delegate.isCurrentStockOverride(id); }
        @Override public void playStockOverride(int id) { delegate.playStockOverride(id); }
        @Override public boolean hasSource() { return delegate.hasSource(); }
        @Override public int mixInto(short[] output, int frames) { return delegate.mixInto(output, frames); }
        @Override public void pause(int reason) { delegate.pause(reason); }
        @Override public void resume(int reason) { delegate.resume(reason); }
        @Override public void fadeOut(int steps, int delay) { delegate.fadeOut(steps, delay); }
        @Override public void fadeIn(int steps, int delay) { delegate.fadeIn(steps, delay); }
        @Override public void advanceFade() { delegate.advanceFade(); }
        @Override public boolean fadeActive() { return delegate.fadeActive(); }
        @Override public boolean fadeAtFullGain() { return delegate.fadeAtFullGain(); }
        @Override public void setSpeedMultiplier(int multiplier) { delegate.setSpeedMultiplier(multiplier); }
        @Override public void stop() { delegate.stop(); }
        @Override public void reset() { delegate.reset(); }
        @Override public java.util.Optional<State> captureState() { return delegate.captureState(); }
        @Override public boolean restoreState(State state) { return delegate.restoreState(state); }
        @Override public void close() { delegate.close(); }
    }
}
