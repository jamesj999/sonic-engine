package com.openggf.audio;

import com.openggf.audio.presentation.AudioPresentationParityProbe;
import com.openggf.audio.presentation.AudioPresentationMixer;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.presentation.AudioPresentationSessionCommandApplier;
import com.openggf.audio.presentation.AudioPresentationSnapshot;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.PcmPresentationVoice;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.presentation.SampleBackedVoice;
import com.openggf.audio.rewind.AudioKeyframeStore;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.runtime.AudioFrameClock;
import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.session.SmpsPhysicalDevice;
import com.openggf.audio.session.SmpsPhysicalPort;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.smps.SmpsSequencerHost;
import com.openggf.audio.synth.Synthesizer;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TestAudioPresentationArchitectureGuard {
    private JavaClasses productionClasses;

    private JavaClasses productionClasses() {
        // The bytecode is fixed for this test class. Keep fixture imports separate
        // so negative examples cannot contaminate the production graph.
        if (productionClasses == null) {
            productionClasses = new ClassFileImporter()
                    .withImportOption(new ImportOption.DoNotIncludeTests())
                    .importPackages("com.openggf");
        }
        return productionClasses;
    }

    private static final Path AUDIO_ROOT =
            Path.of("src/main/java/com/openggf/audio");
    private static final Path PRODUCTION_ROOT =
            Path.of("src/main/java/com/openggf");
    private static final Set<String> BACKEND_COMMANDS = Set.of(
            "playMusic", "playSfx", "playSmps", "playSfxSmps",
            "toggleMute", "toggleSolo", "isMuted", "isSoloed");
    /**
     * The v2 timeline schema has no production-audio data dependency. Future
     * entries need a plan-mandated immutable/read-only interface and an
     * explicit review here; mutating owners are never allow-listed.
     */
    private static final Set<String> TIMELINE_READ_ONLY_AUDIO_DEPENDENCIES = Set.of();

    /**
     * Superseded split-runtime / recording-lease-switch identifiers. None may
     * survive anywhere in production once the unified presentation producer is
     * the sole owner of cadence, final PCM, history and capture leases.
     */
    private static final List<String> FORBIDDEN_RUNTIME_TOKENS = List.of(
            "DeterministicAudioRuntime",
            "setDeterministicAudioRuntime",
            "applyDeterministicAudioRuntime",
            "supportsDeterministicRuntimePresentation",
            "supportsLiveCapturePresentation",
            "presentationHandoff",
            "handoffMusicData",
            "handoffSfxData",
            "runtimeProvidesPresentationPcm",
            "deferredLiveCaptureRuntime",
            "preCaptureRuntime",
            "captureRuntime");

    /**
     * Files allowed to construct an {@link
     * com.openggf.audio.runtime.AudioFrameClock}. The producer owns the
     * presentation clock and its capture-handle internals; the clocked-silence
     * degradation handle continues a detached capture lease at the producer's
     * phase; the parity probe is a read-only presentation-package diagnostic
     * that mirrors — and never drives — that cadence.
     *
     * <p>Matched on the repo-relative path, not the bare file name, so a new
     * production file that merely happens to share one of these names does not
     * inherit the exemption.
     */
    private static final Set<String> AUDIO_FRAME_CLOCK_OWNERS = Set.of(
            "audio/presentation/AudioPresentationProducer.java",
            "audio/ClockedSilenceAudioHandle.java",
            "audio/presentation/AudioPresentationParityProbe.java");

    private static final Set<String> PCM_HISTORY_OWNERS = Set.of(
            "audio/presentation/AudioPresentationProducer.java");

    private static final Set<String> PCM_HISTORY_ITSELF = Set.of(
            "audio/runtime/PcmHistoryRing.java");

    private static final Set<String> FINAL_PCM_SINK = Set.of(
            "audio/output/OpenAlPcmSink.java");

    /** The one production site allowed to publish a presented packet. */
    private static final Set<String> PRESENTATION_FAN_OUT_OWNER = Set.of(
            "audio/presentation/AudioPresentationProducer.java");

    /** The listener contract itself, which only declares the callback. */
    private static final Set<String> PRESENTATION_LISTENER_CONTRACT = Set.of(
            "audio/presentation/AudioPresentationListener.java");

    /**
     * The manager-only reverse-release failure injection. It is consumed on
     * every reverse release and reset by {@code resetState()}, but a second
     * production reference could arm it and abort a real held-rewind release,
     * so exactly one production file may name it.
     */
    private static final Set<String> REVERSE_RELEASE_INJECTION_OWNER = Set.of(
            "audio/AudioManager.java");

    private static final List<String> OPENAL_TOKENS = List.of(
            "import org.lwjgl.openal",
            "alGenSources",
            "alSourcePlay",
            "alSourceQueueBuffers",
            "alSourceUnqueueBuffers",
            "alBufferData",
            "alSourcei(",
            "AL_LOOPING",
            "AL_PITCH");

    @Test
    void smpsDriverRemainsTheLogicalSynthesizerWithoutPhysicalInheritance() {
        assertTrue(Synthesizer.class.isAssignableFrom(SmpsDriver.class));
        assertFalse(VirtualSynthesizer.class.isAssignableFrom(SmpsDriver.class));
    }

    @Test
    void smpsSequencerUsesItsLogicalHostWithoutDependingOnTheDriver() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(SmpsSequencer.class);

        assertTrue(SmpsSequencerHost.class.isInterface());
        assertFalse(classes.get(SmpsSequencer.class)
                        .getDirectDependenciesFromSelf().stream()
                        .anyMatch(dependency -> dependency.getTargetClass()
                                .isEquivalentTo(SmpsDriver.class)),
                "sequencers must reach driver callbacks through SmpsSequencerHost");
    }

    @Test
    void sessionBackedPresentationHasNoAuthoritativeCarrierDependency()
            throws IOException {
        Set<String> compatibilityTypes = Set.of(
                "com.openggf.audio.presentation.SmpsCompositeVoice");
        JavaClasses authoritative = new ClassFileImporter().importClasses(
                AudioPresentationProducer.class,
                AudioPresentationMixer.class,
                AudioPresentationSnapshot.class,
                AudioPresentationSessionCommandApplier.class,
                PcmPresentationVoice.class);
        List<String> dependencies = authoritative.stream()
                .flatMap(owner -> owner.getDirectDependenciesFromSelf()
                        .stream())
                .filter(dependency -> compatibilityTypes.contains(
                        dependency.getTargetClass().getFullName()))
                .map(Dependency::getDescription)
                .sorted()
                .toList();

        assertEquals(List.of(), dependencies,
                "authoritative producer/mixer/snapshot paths must not read "
                        + "standalone SMPS compatibility carriers");
        assertTrue(PcmPresentationVoice.class
                .isAssignableFrom(SampleBackedVoice.class));
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.openggf.audio.presentation.SmpsCompositeVoice"));

        String producer = Files.readString(PRODUCTION_ROOT.resolve(
                "audio/presentation/AudioPresentationProducer.java"));
        String sessionMix = compactMethod(producer,
                "private short[] mixSessionForward(");
        assertTrue(sessionMix.contains("smpsSession.renderFrames("));
        assertTrue(sessionMix.contains("mixer.mixPcmVoices("));
        assertFalse(sessionMix.contains("mixer.mix("));

        String factory = Files.readString(PRODUCTION_ROOT.resolve(
                "audio/presentation/AudioPresentationSourceFactory.java"));
        String sessionMusic = compactMethod(factory,
                "MusicVoiceEntry musicSmpsFromRegistered(");
        assertTrue(sessionMusic.contains("if (smpsSession == null)"));
        assertTrue(sessionMusic.contains("prepareMusicActivation(source)"));
        assertFalse(sessionMusic.contains("SmpsCompositeVoice"));

        String registry = Files.readString(PRODUCTION_ROOT.resolve(
                "audio/presentation/AudioVoiceRegistry.java"));
        String apply = compactMethod(registry,
                "public void apply(AudioPresentationCommand command)");
        assertTrue(apply.indexOf("hasUnownedSmps(command)")
                        < apply.indexOf("applySessionMetadata(command)"),
                "session commands must reject compatibility carriers before "
                        + "the authoritative metadata path");
        String restore = compactMethod(registry,
                "public PreparedSnapshotRestore prepareSnapshotRestore(");
        assertTrue(restore.indexOf("validateAuthoritativeSnapshot(snapshot)")
                        < restore.indexOf("resolver.beginDiagnosticTransaction()"),
                "carrier snapshots must fail before dependency recreation");
        assertFalse(registry.contains("standaloneSmps"));
    }

    @Test
    void sessionCompositionStoresNoPortAndOwnsTheOnlyComposedDriverFactory() {
        Class<?> access = Arrays.stream(
                        SmpsDriverSession.class.getDeclaredClasses())
                .filter(type -> type.getSimpleName().equals(
                        "SmpsSessionSynthesizerAccess"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of(), Arrays.stream(access.getDeclaredFields())
                .filter(field -> field.getGenericType().getTypeName()
                        .contains(SmpsPhysicalPort.class.getName()))
                .map(field -> field.getName() + ":"
                        + field.getGenericType().getTypeName())
                .toList(),
                "session logical access must resolve each ephemeral port "
                        + "from the current scoped epoch");

        JavaClasses production = productionClasses();
        List<String> foreignFactories = production.stream()
                .flatMap(owner -> owner.getMethodCallsFromSelf().stream())
                .filter(call -> call.getTargetOwner()
                        .isEquivalentTo(SmpsDriver.class))
                .filter(call -> call.getName().equals(
                        "createSessionDriver"))
                .filter(call -> !call.getOriginOwner()
                        .isEquivalentTo(SmpsDriverSession.class))
                .map(JavaMethodCall::getDescription)
                .sorted()
                .toList();
        assertEquals(List.of(), foreignFactories,
                "only the session composition root may create a driver "
                        + "without a private chip pair");
    }

    @Test
    void logicalSnapshotHasNoPhysicalStateField() {
        assertEquals(List.of(), Arrays.stream(
                        SmpsDriverSnapshot.class.getRecordComponents())
                .filter(component -> component.getType()
                                == SmpsPhysicalDevice.Snapshot.class
                        || component.getType().getName().startsWith(
                                "com.openggf.audio.synth.VirtualSynthesizer"))
                .map(component -> component.getName() + ":"
                        + component.getType().getName())
                .toList());
    }

    @Test
    void presentationCannotConstructPrivateChipPairsOrStorePhysicalPorts()
            throws IOException {
        String presentation = Files.walk(PRODUCTION_ROOT.resolve(
                        "audio/presentation"))
                .filter(path -> path.toString().endsWith(".java"))
                .sorted()
                .map(path -> {
                    try {
                        return Files.readString(path);
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    }
                })
                .reduce("", String::concat);
        assertFalse(presentation.contains("new VirtualSynthesizer"));
        assertFalse(presentation.matches(
                "(?s).*new\\s+SmpsDriver\\s*\\(.*"));
        assertEquals(List.of(), Arrays.stream(SmpsDriver.class
                        .getDeclaredFields())
                .filter(field -> field.getType()
                        == SmpsPhysicalPort.class)
                .map(field -> field.getName() + ":"
                        + field.getType().getName())
                .toList());
    }

    @Test
    void logicalSnapshotsAndVoicesCannotRenderOrOwnPhysicalState() {
        assertEquals(List.of(), Arrays.stream(
                        SmpsDriverSnapshot.class.getRecordComponents())
                .filter(component -> component.getType()
                        == VirtualSynthesizer.Snapshot.class)
                .map(component -> component.getName() + ":"
                        + component.getType().getName())
                .toList());
        assertThrows(ClassNotFoundException.class, () -> Class.forName(
                "com.openggf.audio.presentation.SmpsCompositeVoice"));
    }

    private static String compactMethod(String source, String marker) {
        String body = methodBody(sanitizedSource(source), marker);
        if (body == null) {
            throw new AssertionError("missing guarded method " + marker);
        }
        return body.replaceAll("\\s+", " ");
    }

    @Test
    void smpsOwnershipDetectorRejectsRepresentativeHotPathRegressions() {
        Map<String, String> sources = representativeSafeSmpsSources();
        sources.put("audio/smps/DacData.java",
                "public byte[] rawSample() { return bytes; }");
        sources.put("audio/smps/SmpsProgramView.java",
                "public int[] rawPointers();");
        sources.put("audio/presentation/AudioPresentationSourceFactory.java",
                "void freshSource() {} void copyDac() {} "
                        + "void newSequencer() { SmpsSourceDescriptor.from(data); }");
        sources.put("audio/presentation/AudioPresentationCommandResolver.java",
                "private Object resolveSmpsSfxCommand() { loadNow(); "
                        + "factory.findRegisteredSmpsSfxAsset(key, generation); } "
                        + "private Object loadNow() { return loader.load(); }");
        sources.put("audio/presentation/AudioVoiceRegistry.java",
                "private void addSmpsSfxToOwner() { "
                        + "captureLiveCommandMutation(); }");
        sources.put("audio/driver/SmpsDriver.java",
                "public void commitSfxAdmission() { "
                        + "hasChipWriteObserver(); "
                        + "captureLiveCommandMutation(); }");
        sources.put("audio/AudioManager.java",
                safeAudioManagerClassificationMethods()
                        + " private boolean ensureRegisteredSmpsSfx() { "
                        + "loadAsset(); catalog.findRegisteredSmpsSfxAsset(); "
                        + "return true; } "
                        + "private Object loadAsset() { "
                        + "return source.loader().loadSfx(1); }");

        assertEquals(List.of(
                "public raw DAC array @ audio/smps/DacData.java",
                "public raw SMPS array @ audio/smps/SmpsProgramView.java",
                "freshSource/copyDac @ audio/presentation/AudioPresentationSourceFactory.java",
                "load before lookup @ audio/presentation/AudioPresentationCommandResolver.java",
                "load before lookup @ audio/AudioManager.java",
                "retired registry SMPS owner method @ audio/presentation/AudioVoiceRegistry.java",
                "warmed descriptor materialization @ audio/presentation/AudioPresentationSourceFactory.java",
                "unguarded driver snapshot @ audio/driver/SmpsDriver.java"),
                smpsOwnershipViolations(sources));
    }

    @Test
    void productionSmpsOwnershipKeepsAssetWorkOutOfTheWarmedPath()
            throws IOException {
        Map<String, String> sources = new LinkedHashMap<>();
        for (String relative : List.of(
                "audio/smps/DacData.java",
                "audio/smps/SmpsProgramView.java",
                "audio/AudioManager.java",
                "audio/presentation/AudioPresentationSourceFactory.java",
                "audio/presentation/AudioPresentationCommandResolver.java",
                "audio/presentation/AudioVoiceRegistry.java",
                "audio/driver/SmpsDriver.java")) {
            sources.put(relative,
                    Files.readString(PRODUCTION_ROOT.resolve(relative)));
        }
        assertEquals(List.of(), smpsOwnershipViolations(sources));
    }

    @Test
    void smpsOwnershipDetectorFailsClosedOnRenamedMethods() {
        Map<String, String> sources = representativeSafeSmpsSources();
        sources.put("audio/AudioManager.java",
                safeAudioManagerClassificationMethods().replace(
                        "ensureRegisteredSmpsSfx(", "renamedOwner(")
                        + " private boolean renamedOwner() { return true; }");
        sources.put("audio/driver/SmpsDriver.java",
                "public void renamedAdmission() {}");

        List<String> violations = smpsOwnershipViolations(sources);

        assertTrue(violations.contains(
                "missing method ensureRegisteredSmpsSfx( @ audio/AudioManager.java"));
        assertTrue(violations.contains(
                "missing method commitSfxAdmission( @ audio/driver/SmpsDriver.java"));
    }

    @Test
    void smpsOwnershipDetectorIgnoresCommentsAndRequiresObserverControlFlow() {
        Map<String, String> sources = representativeSafeSmpsSources();
        sources.put("audio/driver/SmpsDriver.java",
                "public void commitSfxAdmission() { "
                        + "String lie = \"hasChipWriteObserver() ? \"; "
                        + "// captureLiveCommandMutation()\n"
                        + "captureLiveCommandMutation(); }");

        assertTrue(smpsOwnershipViolations(sources).contains(
                "unguarded driver snapshot @ audio/driver/SmpsDriver.java"));
    }

    @Test
    void smpsOwnershipDetectorRejectsPublicAndExtractedLoadBeforeLookup() {
        for (String mutation : List.of(
                "source.loader().loadSfx(sfxName); "
                        + "ensureRegisteredSmpsSfx();",
                "loadBeforeOwner(); ensureRegisteredSmpsSfx(); "
                        + "} private final void loadBeforeOwner() { "
                        + "source.loader().loadSfx(7);",
                "this.source.loader().loadSfx(7); "
                        + "ensureRegisteredSmpsSfx();")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/AudioManager.java",
                    safeAudioManagerClassificationMethods().replace(
                            "ensureRegisteredSmpsSfx(); } "
                                    + "public void playSfx(GameSound",
                            mutation + " } public void playSfx(GameSound")
                            + " private synchronized boolean "
                            + "ensureRegisteredSmpsSfx() { "
                            + "catalog.findRegisteredSmpsSfxAsset(); "
                            + "return source.loader().loadSfx(1) != null; }");

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "load before lookup @ audio/AudioManager.java"),
                    mutation);
        }
    }

    @Test
    void observerGuardRejectsEveryUnconditionalOrExtractedCapture() {
        for (String driver : List.of(
                "public void commitSfxAdmission() { Object state = "
                        + "hasChipWriteObserver() ? "
                        + "captureLiveCommandMutation() : null; "
                        + "captureLiveCommandMutation(); }",
                "public synchronized void commitSfxAdmission() { "
                        + "captureFallback(); } private final void "
                        + "captureFallback() { captureLiveCommandMutation(); }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/driver/SmpsDriver.java", driver);

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "unguarded driver snapshot @ audio/driver/SmpsDriver.java"),
                    driver);
        }
    }

    @Test
    void lookupGuardTraversesUnsafeLaterOverloadsAndThrowsHelpers() {
        for (String helperDeclarations : List.of(
                "private void classify(int id) { "
                        + "catalog.findRegisteredSmpsSfxAsset(); "
                        + "source.loader().loadSfx(id); } "
                        + "private void classify(String name) { "
                        + "source.loader().loadSfx(name); }",
                "private final synchronized void classify(int id) "
                        + "throws IOException { "
                        + "source.loader().loadSfx(id); }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/AudioManager.java",
                    safeAudioManagerClassificationMethods().replace(
                            "ensureRegisteredSmpsSfx(); } "
                                    + "public void playSfx(GameSound",
                            "classify(7); ensureRegisteredSmpsSfx(); } "
                                    + "public void playSfx(GameSound")
                            + helperDeclarations
                            + " private boolean ensureRegisteredSmpsSfx() { "
                            + "catalog.findRegisteredSmpsSfxAsset(); "
                            + "return source.loader().loadSfx(1) != null; }");

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "load before lookup @ audio/AudioManager.java"),
                    helperDeclarations);
        }
    }

    @Test
    void observerGuardRejectsNonPositiveConditionsAndRegistryHelpers() {
        for (String driver : List.of(
                "public void commitSfxAdmission() { if "
                        + "(!hasChipWriteObserver()) { "
                        + "captureLiveCommandMutation(); } }",
                "public void commitSfxAdmission() { if "
                        + "(hasChipWriteObserver() || true) { "
                        + "captureLiveCommandMutation(); } }",
                "public void commitSfxAdmission() { Object state = "
                        + "!hasChipWriteObserver() ? "
                        + "captureLiveCommandMutation() : null; }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/driver/SmpsDriver.java", driver);
            assertTrue(smpsOwnershipViolations(sources).contains(
                    "unguarded driver snapshot @ audio/driver/SmpsDriver.java"),
                    driver);
        }

        Map<String, String> registryMutation = representativeSafeSmpsSources();
        registryMutation.put("audio/presentation/AudioVoiceRegistry.java",
                "private void addSmpsSfxToOwner() { captureHelper(); } "
                        + "private final void captureHelper() throws IOException { "
                        + "captureLiveCommandMutation(); }");
        assertTrue(smpsOwnershipViolations(registryMutation).contains(
                "retired registry SMPS owner method @ "
                        + "audio/presentation/AudioVoiceRegistry.java"));
    }

    @Test
    void observerGuardTraversesAllDriverHelperOverloads() {
        Map<String, String> sources = representativeSafeSmpsSources();
        sources.put("audio/driver/SmpsDriver.java",
                "public void commitSfxAdmission() { captureHelper(1); } "
                        + "private void captureHelper(int id) { } "
                        + "private void captureHelper(String id) "
                        + "throws IOException { captureLiveCommandMutation(); }");

        assertTrue(smpsOwnershipViolations(sources).contains(
                "unguarded driver snapshot @ audio/driver/SmpsDriver.java"));
    }

    @Test
    void lookupGuardTraversesAnnotatedHelpersIncludingArgumentsAndStacks() {
        for (String declaration : List.of(
                "@Deprecated private void annotatedLoad() { "
                        + "source.loader().loadSfx(7); }",
                "@SuppressWarnings(value = \"unchecked\") @Deprecated "
                        + "private final synchronized void annotatedLoad() "
                        + "throws IOException { source.loader().loadSfx(7); }",
                "@Outer(value = @Inner(factory(\"unsafe\"))) @Deprecated "
                        + "private void annotatedLoad() { "
                        + "source.loader().loadSfx(7); }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/AudioManager.java",
                    safeAudioManagerClassificationMethods().replace(
                            "ensureRegisteredSmpsSfx(); } "
                                    + "public void playSfx(GameSound",
                            "annotatedLoad(); ensureRegisteredSmpsSfx(); } "
                                    + "public void playSfx(GameSound")
                            + declaration
                            + " private boolean ensureRegisteredSmpsSfx() { "
                            + "catalog.findRegisteredSmpsSfxAsset(); "
                            + "return source.loader().loadSfx(1) != null; }");

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "load before lookup @ audio/AudioManager.java"),
                    declaration);
        }
    }

    @Test
    void observerGuardTraversesUnsafeAnnotatedDriverHelpers() {
        for (String declaration : List.of(
                "@Deprecated private void annotatedCapture() { "
                        + "captureLiveCommandMutation(); }",
                "@SuppressWarnings(value = \"unchecked\") @Deprecated "
                        + "private final void annotatedCapture() "
                        + "throws IOException { "
                        + "captureLiveCommandMutation(); }",
                "@Outer(value = @Inner(factory(\"unsafe\"))) @Deprecated "
                        + "private void annotatedCapture() { "
                        + "captureLiveCommandMutation(); }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/driver/SmpsDriver.java",
                    "public void commitSfxAdmission() { "
                            + "annotatedCapture(); } " + declaration);

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "unguarded driver snapshot @ audio/driver/SmpsDriver.java"),
                    declaration);
        }
    }

    @Test
    void registryGuardRejectsEveryCaptureEvenUnderPositiveObserverGate() {
        for (String registry : List.of(
                "private void addSmpsSfxToOwner() { "
                        + "captureLiveCommandMutation(); }",
                "private void addSmpsSfxToOwner() { "
                        + "if (hasChipWriteObserver()) { "
                        + "captureLiveCommandMutation(); } }",
                "private void addSmpsSfxToOwner() { "
                        + "if (this.hasChipWriteObserver()) { "
                        + "annotatedCapture(); } } "
                        + "@SuppressWarnings(value = nested(\"unchecked\")) "
                        + "@Deprecated private final void annotatedCapture() "
                        + "throws IOException { "
                        + "captureLiveCommandMutation(); }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/presentation/AudioVoiceRegistry.java",
                    registry);

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "retired registry SMPS owner method @ "
                            + "audio/presentation/AudioVoiceRegistry.java"),
                    registry);
        }
    }

    @Test
    void driverGuardAllowsOnlyItsExactPositiveObserverBranches() {
        for (String driver : List.of(
                "public void commitSfxAdmission() { "
                        + "if (hasChipWriteObserver()) { "
                        + "captureLiveCommandMutation(); } }",
                "public void commitSfxAdmission() { Object state = "
                        + "this.hasChipWriteObserver() "
                        + "? captureLiveCommandMutation() : null; }")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put("audio/driver/SmpsDriver.java", driver);

            assertFalse(smpsOwnershipViolations(sources).contains(
                    "unguarded driver snapshot @ audio/driver/SmpsDriver.java"),
                    driver);
        }
    }

    @Test
    void warmedMaterializationGuardTraversesAnnotatedHelpers() {
        for (String forbidden : List.of(
                "SmpsSourceDescriptor.from(data);",
                "data.getData();",
                "describeSfx(data);")) {
            Map<String, String> sources = representativeSafeSmpsSources();
            sources.put(
                    "audio/presentation/AudioPresentationSourceFactory.java",
                    "private void newSequencer() { materialize(); } "
                            + "@SuppressWarnings(value = nested(\"unsafe\")) "
                            + "@Deprecated private final void materialize() { "
                            + forbidden + " }");

            assertTrue(smpsOwnershipViolations(sources).contains(
                    "warmed descriptor materialization @ "
                            + "audio/presentation/AudioPresentationSourceFactory.java"),
                    forbidden);
        }
    }

    @Test
    void lookupGuardRejectsMutuallyExclusiveLookupAndLoadBranches() {
        Map<String, String> resolverMutation = representativeSafeSmpsSources();
        resolverMutation.put(
                "audio/presentation/AudioPresentationCommandResolver.java",
                "private Object resolveSmpsSfxCommand() { "
                        + "if (catalogReady) { "
                        + "factory.findRegisteredSmpsSfxAsset(); "
                        + "} else { return loader.load(); } return null; }");
        assertTrue(smpsOwnershipViolations(resolverMutation).contains(
                "load before lookup @ "
                        + "audio/presentation/AudioPresentationCommandResolver.java"));

        Map<String, String> managerMutation = representativeSafeSmpsSources();
        managerMutation.put("audio/AudioManager.java",
                safeAudioManagerClassificationMethods()
                        + " private boolean ensureRegisteredSmpsSfx() { "
                        + "if (catalogReady) { "
                        + "catalog.findRegisteredSmpsSfxAsset(); "
                        + "} else { return source.loader().loadSfx(1) != null; } "
                        + "return true; }");
        assertTrue(smpsOwnershipViolations(managerMutation).contains(
                "load before lookup @ audio/AudioManager.java"));
    }

    @Test
    void lookupGuardRejectsMutuallyExclusiveAnnotatedHelperBranches() {
        Map<String, String> sources = representativeSafeSmpsSources();
        sources.put(
                "audio/presentation/AudioPresentationCommandResolver.java",
                "private Object resolveSmpsSfxCommand() { "
                        + "if (catalogReady) { lookupHelper(); } "
                        + "else { return loadHelper(); } return null; } "
                        + "@Deprecated private void lookupHelper() { "
                        + "factory.findRegisteredSmpsSfxAsset(); } "
                        + "@SuppressWarnings(value = nested(\"unsafe\")) "
                        + "private Object loadHelper() { return loader.load(); }");

        assertTrue(smpsOwnershipViolations(sources).contains(
                "load before lookup @ "
                        + "audio/presentation/AudioPresentationCommandResolver.java"));
    }

    @Test
    void frozenRomDependencyShapeFieldHasNoRuntimeAccesses() {
        JavaClasses classes = new ClassFileImporter()
                .importClasses(AudioManager.class);
        List<String> runtimeAccesses = classes.get(AudioManager.class)
                .getField("rom")
                .getAccessesToSelf().stream()
                .filter(access -> !access.getOrigin().isConstructor())
                .map(access -> access.getOrigin().getFullName())
                .sorted()
                .toList();

        assertEquals(List.of(), runtimeAccesses,
                "the frozen AudioManager -> Rom field is architecture-only; "
                        + "runtime ROM state must come from BaseAudioSource");
    }

    private static Map<String, String> representativeSafeSmpsSources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("audio/smps/DacData.java", "final class DacData {}");
        sources.put("audio/smps/SmpsProgramView.java",
                "interface SmpsProgramView {}");
        sources.put("audio/AudioManager.java",
                safeAudioManagerClassificationMethods()
                        + " private boolean ensureRegisteredSmpsSfx() { "
                        + "catalog.findRegisteredSmpsSfxAsset(); "
                        + "return source.loader().loadSfx(1) != null; }");
        sources.put("audio/presentation/AudioPresentationSourceFactory.java",
                "private void newSequencer() {}");
        sources.put("audio/presentation/AudioPresentationCommandResolver.java",
                "private Object resolveSmpsSfxCommand() { "
                        + "factory.findRegisteredSmpsSfxAsset(); "
                        + "return loader.load(); }");
        sources.put("audio/presentation/AudioVoiceRegistry.java", "");
        sources.put("audio/driver/SmpsDriver.java",
                "public void commitSfxAdmission() { Object state = "
                        + "hasChipWriteObserver() "
                        + "? captureLiveCommandMutation() : null; }");
        return sources;
    }

    private static String safeAudioManagerClassificationMethods() {
        return "public void playSfx(String sfxName, float pitch) { "
                + "ensureRegisteredSmpsSfx(); } "
                + "public void playSfx(GameSound sound, float pitch) { "
                + "playBaseSfx(); ensureRegisteredSmpsSfx(); } "
                + "public boolean playSfx(int sfxId, float pitch) { "
                + "return playBaseSfx(); } "
                + "public void playDonorSfx(String donorGameId, int sfxId) { "
                + "ensureRegisteredSmpsSfx(); }";
    }

    private static List<String> smpsOwnershipViolations(
            Map<String, String> sources) {
        List<String> violations = new ArrayList<>();
        String dac = sanitizedSource(
                source(sources, "audio/smps/DacData.java"));
        if (Pattern.compile(
                "\\bpublic\\s+(?:final\\s+)?(?:byte|short|int|long)\\[\\]"
                        + "\\s+\\w+")
                .matcher(dac).find()
                || Pattern.compile(
                "\\bpublic\\s+(?:final\\s+)?Map<[^>]*\\[\\][^>]*>")
                .matcher(dac).find()) {
            violations.add("public raw DAC array @ audio/smps/DacData.java");
        }

        String programView = sanitizedSource(source(
                sources, "audio/smps/SmpsProgramView.java"));
        if (Pattern.compile(
                "\\b(?:byte|short|int|long)\\[\\]\\s+\\w+\\s*\\(")
                .matcher(programView).find()) {
            violations.add(
                    "public raw SMPS array @ audio/smps/SmpsProgramView.java");
        }

        String factory = sanitizedSource(source(sources,
                "audio/presentation/AudioPresentationSourceFactory.java"));
        if (factory.contains("freshSource") || factory.contains("copyDac")) {
            violations.add("freshSource/copyDac @ "
                    + "audio/presentation/AudioPresentationSourceFactory.java");
        }

        String resolverSource = sanitizedSource(source(sources,
                "audio/presentation/AudioPresentationCommandResolver.java"));
        String resolver = requiredMethodBody(resolverSource,
                "resolveSmpsSfxCommand(",
                "audio/presentation/AudioPresentationCommandResolver.java",
                violations);
        if (resolver != null && !lookupPrecedesEveryLoad(
                resolverSource, "resolveSmpsSfxCommand")) {
            violations.add("load before lookup @ "
                    + "audio/presentation/AudioPresentationCommandResolver.java");
        }

        String managerSource = sanitizedSource(
                source(sources, "audio/AudioManager.java"));
        requireClassificationMethod(managerSource,
                "public void playSfx(String sfxName, float pitch)",
                "ensureRegisteredSmpsSfx(", violations);
        requireClassificationMethod(managerSource,
                "public void playSfx(GameSound sound, float pitch)",
                "ensureRegisteredSmpsSfx(", violations);
        requireClassificationMethod(managerSource,
                "public boolean playSfx(int sfxId, float pitch)",
                "playBaseSfx(", violations);
        requireClassificationMethod(managerSource,
                "public void playDonorSfx(String donorGameId, int sfxId)",
                "ensureRegisteredSmpsSfx(", violations);
        String managerOwner = requiredMethodBody(managerSource,
                "ensureRegisteredSmpsSfx(", "audio/AudioManager.java",
                violations);
        if (managerOwner != null && !lookupPrecedesEveryLoad(
                managerSource, "ensureRegisteredSmpsSfx")) {
            violations.add("load before lookup @ audio/AudioManager.java");
        }
        Map<MethodSignature, String> managerMethods = methodBodies(managerSource);
        Set<String> managerRelevantMethods =
                lookupRelevantMethods(managerMethods);
        for (String marker : List.of(
                "public void playSfx(String sfxName, float pitch)",
                "public void playSfx(GameSound sound, float pitch)",
                "public boolean playSfx(int sfxId, float pitch)",
                "public void playDonorSfx(String donorGameId, int sfxId)")) {
            String route = methodBody(managerSource, marker);
            if (route != null && !inspectLookupOrder(
                    route, managerMethods, managerRelevantMethods,
                    false, new HashSet<>()).valid()
                    && !violations.contains(
                    "load before lookup @ audio/AudioManager.java")) {
                violations.add("load before lookup @ audio/AudioManager.java");
            }
        }

        String registrySource = sanitizedSource(source(sources,
                "audio/presentation/AudioVoiceRegistry.java"));
        if (methodBody(registrySource, "addSmpsSfxToOwner(") != null) {
            violations.add("retired registry SMPS owner method @ "
                    + "audio/presentation/AudioVoiceRegistry.java");
        }

        String instantiation = requiredMethodBody(factory, "newSequencer(",
                "audio/presentation/AudioPresentationSourceFactory.java",
                violations);
        if (instantiation != null
                && reachableContainsAny(factory, "newSequencer", List.of(
                "SmpsSourceDescriptor.from(", "getData()",
                "describeSfx("))) {
            violations.add("warmed descriptor materialization @ "
                    + "audio/presentation/AudioPresentationSourceFactory.java");
        }

        String driverSource = sanitizedSource(source(sources,
                "audio/driver/SmpsDriver.java"));
        String driver = requiredMethodBody(driverSource,
                "commitSfxAdmission(", "audio/driver/SmpsDriver.java",
                violations);
        if (driver != null && !observerSafeReachable(
                driverSource, "commitSfxAdmission", true)) {
            violations.add("unguarded driver snapshot @ "
                    + "audio/driver/SmpsDriver.java");
        }
        return List.copyOf(violations);
    }

    private static String source(
            Map<String, String> sources, String path) {
        String source = sources.get(path);
        if (source == null) {
            throw new AssertionError("missing guard source " + path);
        }
        return source;
    }

    private static void requireClassificationMethod(
            String source, String marker, String ownerCall,
            List<String> violations) {
        String path = "audio/AudioManager.java";
        String body = requiredMethodBody(source, marker, path, violations);
        boolean delegatedGameSoundClassification = marker.contains(
                "playSfx(GameSound sound, float pitch)")
                && body != null
                && classifiesThroughDelegate(source, body,
                        "playGameSfxResolved(");
        if (body != null
                && !classifiesThroughDelegate(source, body, ownerCall)
                && !delegatedGameSoundClassification) {
            violations.add("classification bypass " + marker + " @ " + path);
        }
    }

    /**
     * True when the entry point performs the classification call itself or
     * forwards to a private overload that does.
     *
     * <p>A public entry point is allowed to share its body with a sibling
     * request shape by delegating to one private overload -- the S2 secondary
     * request route does exactly that. What the guard protects is that the
     * classification owner is still called before the request leaves the
     * manager, so the search follows local calls rather than reading only the
     * entry point's own text.
     */
    private static boolean classifiesThroughDelegate(
            String source, String body, String ownerCall) {
        Map<MethodSignature, String> methods = methodBodies(source);
        Set<String> visited = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.push(body);
        int inspected = 0;
        while (!pending.isEmpty() && inspected++ < CLASSIFICATION_HOP_LIMIT) {
            String current = pending.pop();
            if (current.contains(ownerCall)) {
                return true;
            }
            for (String method : methodNames(methods)) {
                if (!visited.add(method)) {
                    continue;
                }
                if (localCallPattern(method).matcher(current).find()) {
                    for (Map.Entry<MethodSignature, String> target
                            : methodsNamed(methods, method)) {
                        pending.push(target.getValue());
                    }
                }
            }
        }
        return false;
    }

    /** Bounds the delegation walk so the guard cannot run away on a cycle. */
    private static final int CLASSIFICATION_HOP_LIMIT = 64;

    private static String requiredMethodBody(
            String source, String marker, String path,
            List<String> violations) {
        String body = methodBody(source, marker);
        if (body == null) {
            violations.add("missing method " + marker + " @ " + path);
        }
        return body;
    }

    private static String methodBody(String source, String marker) {
        int markerIndex = source.indexOf(marker);
        if (markerIndex < 0) {
            return null;
        }
        int open = source.indexOf('{', markerIndex + marker.length());
        if (open < 0) {
            return null;
        }
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        return null;
    }

    private static boolean lookupPrecedesEveryLoad(
            String source, String rootMethod) {
        Map<MethodSignature, String> methods = methodBodies(source);
        Set<String> relevantMethods = lookupRelevantMethods(methods);
        List<Map.Entry<MethodSignature, String>> roots =
                methodsNamed(methods, rootMethod);
        return !roots.isEmpty() && roots.stream().allMatch(root ->
                inspectLookupOrder(root.getValue(), methods,
                        relevantMethods, false,
                        new HashSet<>(Set.of(root.getKey()))).valid());
    }

    /**
     * Memo for {@link #inspectLookupOrder}. The walk expands every local call
     * at every call site, so the same (body, seen, cycle-cut) state is
     * re-derived thousands of times over the manager's call graph; the result
     * is a pure function of that state, so caching it is behaviour-preserving.
     */
    private record LookupState(String body, boolean lookupSeen,
            Set<MethodSignature> activeMethods) {
    }

    private static final Map<LookupState, LookupInspection> LOOKUP_MEMO =
            new HashMap<>();

    private static LookupInspection inspectLookupOrder(
            String body,
            Map<MethodSignature, String> methods,
            Set<String> relevantMethods,
            boolean lookupSeen,
            Set<MethodSignature> activeMethods) {
        LookupState state = new LookupState(body, lookupSeen,
                Set.copyOf(activeMethods));
        LookupInspection memoised = LOOKUP_MEMO.get(state);
        if (memoised != null) {
            return memoised;
        }
        LookupInspection computed = computeLookupOrder(
                body, methods, relevantMethods, lookupSeen, activeMethods);
        LOOKUP_MEMO.put(state, computed);
        return computed;
    }

    private static LookupInspection computeLookupOrder(
            String body,
            Map<MethodSignature, String> methods,
            Set<String> relevantMethods,
            boolean lookupSeen,
            Set<MethodSignature> activeMethods) {
        ConditionalBranch branch = firstRelevantConditional(
                body, relevantMethods);
        if (branch == null) {
            return inspectLinearLookupOrder(
                    body, methods, relevantMethods,
                    lookupSeen, activeMethods);
        }
        String prefix = body.substring(0, branch.start())
                + branch.condition() + ";";
        LookupInspection beforeBranch = inspectLinearLookupOrder(
                prefix, methods, relevantMethods, lookupSeen,
                new HashSet<>(activeMethods));
        if (!beforeBranch.valid()) {
            return beforeBranch;
        }
        String whenTrue = branch.compatibilityCondition()
                ? maskCompatibilityLoads(branch.whenTrue())
                : branch.whenTrue();
        LookupInspection trueBranch = inspectLookupOrder(
                whenTrue, methods, relevantMethods,
                beforeBranch.lookupSeen(),
                new HashSet<>(activeMethods));
        if (!trueBranch.valid()) {
            return trueBranch;
        }
        LookupInspection falseBranch = inspectLookupOrder(
                branch.whenFalse(), methods, relevantMethods,
                beforeBranch.lookupSeen(),
                new HashSet<>(activeMethods));
        if (!falseBranch.valid()) {
            return falseBranch;
        }
        boolean everyBranchSeesLookup = trueBranch.lookupSeen()
                && falseBranch.lookupSeen();
        return inspectLookupOrder(
                body.substring(branch.end()), methods, relevantMethods,
                everyBranchSeesLookup, activeMethods);
    }

    private static LookupInspection inspectLinearLookupOrder(
            String body,
            Map<MethodSignature, String> methods,
            Set<String> relevantMethods,
            boolean lookupSeen,
            Set<MethodSignature> activeMethods) {
        List<GuardEvent> events = new ArrayList<>();
        addEvents(events, body, "findRegisteredSmpsSfxAsset(", "lookup");
        addEvents(events, body, "loader.load(", "load");
        addEvents(events, body, ".loadSfx(", "load");
        for (String method : methodNames(methods)) {
            addLocalCallEvents(events, body, method);
        }
        events.sort(Comparator.comparingInt(GuardEvent::offset));
        boolean seen = lookupSeen;
        for (GuardEvent event : events) {
            if (event.kind().equals("lookup")) {
                seen = true;
            } else if (event.kind().equals("load")) {
                if (!seen && !isExplicitCompatibilityLoad(
                        body, event.offset())) {
                    return new LookupInspection(false, seen);
                }
            } else {
                String called = event.kind().substring("call:".length());
                boolean everyTargetSeesLookup = true;
                for (Map.Entry<MethodSignature, String> target
                        : methodsNamed(methods, called)) {
                    if (activeMethods.add(target.getKey())) {
                        LookupInspection nested = inspectLookupOrder(
                                target.getValue(), methods,
                                relevantMethods, seen,
                                activeMethods);
                        activeMethods.remove(target.getKey());
                        if (!nested.valid()) {
                            return nested;
                        }
                        everyTargetSeesLookup &= nested.lookupSeen();
                    }
                }
                seen |= everyTargetSeesLookup;
            }
        }
        return new LookupInspection(true, seen);
    }

    private static ConditionalBranch firstRelevantConditional(
            String body, Set<String> relevantMethods) {
        Matcher matcher = Pattern.compile("(?<![A-Za-z0-9_$])if\\s*\\(")
                .matcher(body);
        while (matcher.find()) {
            int conditionOpen = body.indexOf('(', matcher.start());
            int conditionClose = matching(body, conditionOpen, '(', ')');
            if (conditionClose < 0) {
                return null;
            }
            int thenOpen = skipWhitespace(body, conditionClose + 1);
            if (thenOpen >= body.length() || body.charAt(thenOpen) != '{') {
                continue;
            }
            int thenClose = matching(body, thenOpen, '{', '}');
            if (thenClose < 0) {
                return null;
            }
            int end = thenClose + 1;
            String whenFalse = "";
            int elseStart = skipWhitespace(body, end);
            if (body.startsWith("else", elseStart)
                    && identifierEndsAt(body, elseStart + 4)) {
                int elseOpen = skipWhitespace(body, elseStart + 4);
                if (elseOpen < body.length()
                        && body.charAt(elseOpen) == '{') {
                    int elseClose = matching(body, elseOpen, '{', '}');
                    if (elseClose < 0) {
                        return null;
                    }
                    whenFalse = body.substring(elseOpen + 1, elseClose);
                    end = elseClose + 1;
                }
            }
            String whenTrue = body.substring(thenOpen + 1, thenClose);
            if (containsLookupEvent(whenTrue, relevantMethods)
                    || containsLookupEvent(whenFalse, relevantMethods)) {
                String condition = body.substring(
                        conditionOpen + 1, conditionClose);
                return new ConditionalBranch(
                        matcher.start(), end, condition,
                        whenTrue, whenFalse,
                        isExplicitCompatibilityCondition(condition));
            }
        }
        return null;
    }

    private static boolean containsLookupEvent(
            String body, Set<String> relevantMethods) {
        if (body.contains("findRegisteredSmpsSfxAsset(")
                || body.contains("loader.load(")
                || body.contains(".loadSfx(")) {
            return true;
        }
        return relevantMethods.stream().anyMatch(method ->
                localCallPattern(method).matcher(body).find());
    }

    private static Set<String> lookupRelevantMethods(
            Map<MethodSignature, String> methods) {
        Set<String> relevant = new HashSet<>();
        methods.forEach((signature, body) -> {
            if (containsLookupEvent(body, Set.of())) {
                relevant.add(signature.name());
            }
        });
        boolean changed;
        do {
            changed = false;
            for (Map.Entry<MethodSignature, String> method
                    : methods.entrySet()) {
                if (!relevant.contains(method.getKey().name())
                        && relevant.stream().anyMatch(name ->
                        localCallPattern(name).matcher(
                                method.getValue()).find())) {
                    changed |= relevant.add(method.getKey().name());
                }
            }
        } while (changed);
        return Set.copyOf(relevant);
    }

    private static int skipWhitespace(String source, int offset) {
        int cursor = offset;
        while (cursor < source.length()
                && Character.isWhitespace(source.charAt(cursor))) {
            cursor++;
        }
        return cursor;
    }

    private static boolean identifierEndsAt(String source, int offset) {
        return offset >= source.length()
                || !Character.isJavaIdentifierPart(source.charAt(offset));
    }

    private static boolean isExplicitCompatibilityCondition(
            String condition) {
        return condition.contains("< 0")
                || condition.contains(".isBlank(")
                || condition.contains("!hasCatalogDependencies(");
    }

    private static String maskCompatibilityLoads(String body) {
        return body.replace("loader.load(", "loader.compatibilityLoad(")
                .replace(".loadSfx(", ".compatibilityLoadSfx(");
    }

    private static boolean reachableContainsAny(
            String source, String rootMethod, List<String> tokens) {
        Map<MethodSignature, String> methods = methodBodies(source);
        return methodsNamed(methods, rootMethod).stream().anyMatch(root ->
                reachableContainsAny(root.getValue(), methods, tokens,
                        new HashSet<>(Set.of(root.getKey()))));
    }

    private static boolean reachableContainsAny(
            String body,
            Map<MethodSignature, String> methods,
            List<String> tokens,
            Set<MethodSignature> activeMethods) {
        if (tokens.stream().anyMatch(body::contains)) {
            return true;
        }
        for (Map.Entry<MethodSignature, String> method : methods.entrySet()) {
            if (!localCallPattern(method.getKey().name())
                    .matcher(body).find()) {
                continue;
            }
            if (activeMethods.add(method.getKey())) {
                boolean forbidden = reachableContainsAny(
                        method.getValue(), methods, tokens, activeMethods);
                activeMethods.remove(method.getKey());
                if (forbidden) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addEvents(
            List<GuardEvent> events, String body,
            String token, String kind) {
        int offset = body.indexOf(token);
        while (offset >= 0) {
            events.add(new GuardEvent(offset, kind));
            offset = body.indexOf(token, offset + token.length());
        }
    }

    private static void addLocalCallEvents(
            List<GuardEvent> events, String body, String method) {
        Matcher calls = localCallPattern(method).matcher(body);
        while (calls.find()) {
            events.add(new GuardEvent(calls.start(), "call:" + method));
        }
    }

    /**
     * Cache of the per-method call patterns. The lookup-order walk re-scans the
     * same method names across thousands of body fragments, and recompiling
     * these patterns each time dominated the guard's runtime.
     */
    private static final Map<String, Pattern> LOCAL_CALL_PATTERNS =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static Pattern localCallPattern(String method) {
        return LOCAL_CALL_PATTERNS.computeIfAbsent(method, name ->
                Pattern.compile("(?<![A-Za-z0-9_.$])(?:this\\s*\\.\\s*)?"
                        + Pattern.quote(name) + "\\s*\\("));
    }

    private static Map<MethodSignature, String> methodBodies(String source) {
        String declarationsSource = annotationsElided(source);
        Pattern declarations = Pattern.compile(
                "(?:^|[;}])\\s*"
                        + "(?:(?:public|protected|private|static|final|synchronized|"
                        + "native|abstract|strictfp)\\s+)*"
                        + "(?:[\\w<>?.,\\[\\]]+\\s+)+"
                        + "([A-Za-z][A-Za-z0-9_]*)\\s*"
                        + "\\(([^;{}]*)\\)\\s*"
                        + "(?:throws\\s+[\\w.,\\s]+)?\\{");
        Matcher matcher = declarations.matcher(declarationsSource);
        Map<MethodSignature, String> methods = new LinkedHashMap<>();
        while (matcher.find()) {
            String name = matcher.group(1);
            if (Set.of("if", "for", "while", "switch", "catch", "try",
                    "else", "do", "synchronized").contains(name)) {
                continue;
            }
            String body = bracedBody(
                    declarationsSource, matcher.end() - 1);
            MethodSignature signature = new MethodSignature(
                    name, matcher.group(2).replaceAll("\\s+", " ").trim());
            methods.put(signature, body);
        }
        return methods;
    }

    private static String annotationsElided(String source) {
        char[] elided = source.toCharArray();
        for (int index = 0; index < source.length(); index++) {
            if (source.charAt(index) != '@'
                    || source.startsWith("@interface", index)) {
                continue;
            }
            int cursor = index + 1;
            if (cursor >= source.length()
                    || !Character.isJavaIdentifierStart(
                    source.charAt(cursor))) {
                continue;
            }
            cursor++;
            while (cursor < source.length()) {
                char current = source.charAt(cursor);
                if (Character.isJavaIdentifierPart(current)
                        || current == '.') {
                    cursor++;
                } else {
                    break;
                }
            }
            while (cursor < source.length()
                    && Character.isWhitespace(source.charAt(cursor))) {
                cursor++;
            }
            if (cursor < source.length() && source.charAt(cursor) == '(') {
                int close = matching(source, cursor, '(', ')');
                if (close < 0) {
                    continue;
                }
                cursor = close + 1;
            }
            for (int blank = index; blank < cursor; blank++) {
                if (elided[blank] != '\n' && elided[blank] != '\r') {
                    elided[blank] = ' ';
                }
            }
            index = cursor - 1;
        }
        return new String(elided);
    }

    /**
     * Per-method-map caches. {@code methodNames} and {@code methodsNamed} are
     * called once per inspected body fragment over an unchanging map, so the
     * repeated stream scans were pure overhead.
     */
    private static final Map<Map<MethodSignature, String>, Set<String>>
            METHOD_NAME_CACHE = new java.util.IdentityHashMap<>();
    private static final Map<Map<MethodSignature, String>,
            Map<String, List<Map.Entry<MethodSignature, String>>>>
            METHODS_NAMED_CACHE = new java.util.IdentityHashMap<>();

    private static Set<String> methodNames(
            Map<MethodSignature, String> methods) {
        return METHOD_NAME_CACHE.computeIfAbsent(methods, source -> {
            Set<String> names = new HashSet<>();
            source.keySet().forEach(method -> names.add(method.name()));
            return names;
        });
    }

    private static List<Map.Entry<MethodSignature, String>> methodsNamed(
            Map<MethodSignature, String> methods, String name) {
        return METHODS_NAMED_CACHE
                .computeIfAbsent(methods, source -> new HashMap<>())
                .computeIfAbsent(name, wanted -> methods.entrySet().stream()
                        .filter(entry -> entry.getKey().name().equals(wanted))
                        .toList());
    }

    private static String bracedBody(String source, int open) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            char character = source.charAt(index);
            if (character == '{') {
                depth++;
            } else if (character == '}' && --depth == 0) {
                return source.substring(open + 1, index);
            }
        }
        return "";
    }

    private static boolean isExplicitCompatibilityLoad(
            String body, int loadOffset) {
        int search = 0;
        while ((search = body.indexOf("if", search)) >= 0) {
            int conditionOpen = body.indexOf('(', search + 2);
            if (conditionOpen < 0) {
                return false;
            }
            int conditionClose = matching(body, conditionOpen, '(', ')');
            int blockOpen = conditionClose < 0 ? -1
                    : body.indexOf('{', conditionClose);
            int blockClose = blockOpen < 0 ? -1
                    : matching(body, blockOpen, '{', '}');
            if (blockOpen >= 0 && loadOffset > blockOpen
                    && loadOffset < blockClose) {
                String condition = body.substring(
                        conditionOpen + 1, conditionClose);
                if (condition.contains("< 0")
                        || condition.contains(".isBlank(")
                        || condition.contains("!hasCatalogDependencies(")) {
                    return true;
                }
            }
            search += 2;
        }
        return false;
    }

    private static boolean observerSafeReachable(
            String source, String rootMethod,
            boolean allowDriverObserverGate) {
        Map<MethodSignature, String> methods = methodBodies(source);
        List<Map.Entry<MethodSignature, String>> roots =
                methodsNamed(methods, rootMethod);
        return !roots.isEmpty() && roots.stream().allMatch(root ->
                observerSafe(root.getValue(), methods,
                        allowDriverObserverGate, false,
                        new HashSet<>(Set.of(root.getKey()))));
    }

    private static boolean observerSafe(
            String body,
            Map<MethodSignature, String> methods,
            boolean allowDriverObserverGate,
            boolean inheritedObserverDominance,
            Set<MethodSignature> activeMethods) {
        int capture = body.indexOf("captureLiveCommandMutation(");
        while (capture >= 0) {
            if (!allowDriverObserverGate
                    || (!inheritedObserverDominance
                    && !observerDominates(body, capture))) {
                return false;
            }
            capture = body.indexOf(
                    "captureLiveCommandMutation(", capture + 1);
        }
        for (Map.Entry<MethodSignature, String> method : methods.entrySet()) {
            String called = method.getKey().name();
            if (called.equals("captureLiveCommandMutation")
                    || called.equals("hasChipWriteObserver")) {
                continue;
            }
            Matcher calls = localCallPattern(called).matcher(body);
            while (calls.find()) {
                int call = calls.start();
                if (activeMethods.add(method.getKey())) {
                    boolean dominated = allowDriverObserverGate
                            && (inheritedObserverDominance
                            || observerDominates(body, call));
                    boolean safe = observerSafe(method.getValue(), methods,
                            allowDriverObserverGate, dominated, activeMethods);
                    activeMethods.remove(method.getKey());
                    if (!safe) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean observerDominates(String body, int offset) {
        Matcher ternary = Pattern.compile(
                "(?:^|[=,(])\\s*(?:this\\.)?hasChipWriteObserver"
                        + "\\s*\\(\\s*\\)\\s*\\?")
                .matcher(body);
        while (ternary.find() && ternary.start() < offset) {
            int colon = body.indexOf(':', ternary.end());
            if (ternary.end() <= offset && colon > offset) {
                return true;
            }
        }
        int search = 0;
        while ((search = body.indexOf("if", search)) >= 0) {
            int conditionOpen = body.indexOf('(', search + 2);
            int conditionClose = conditionOpen < 0 ? -1
                    : matching(body, conditionOpen, '(', ')');
            int blockOpen = conditionClose < 0 ? -1
                    : body.indexOf('{', conditionClose);
            int blockClose = blockOpen < 0 ? -1
                    : matching(body, blockOpen, '{', '}');
            if (blockOpen >= 0 && offset > blockOpen && offset < blockClose
                    && isPositiveObserverCondition(body.substring(
                    conditionOpen + 1, conditionClose))) {
                return true;
            }
            search += 2;
        }
        return false;
    }

    private static boolean isPositiveObserverCondition(String condition) {
        String compact = condition.replaceAll("\\s+", "");
        return compact.equals("hasChipWriteObserver()")
                || compact.equals("this.hasChipWriteObserver()")
                || compact.equals("hasPotentiallyThrowingAdmissionObserver()")
                || compact.equals(
                        "this.hasPotentiallyThrowingAdmissionObserver()")
                || compact.contains(
                        "&&hasPotentiallyThrowingAdmissionObserver()");
    }

    private static int matching(
            String source, int open, char opening, char closing) {
        int depth = 0;
        for (int index = open; index < source.length(); index++) {
            if (source.charAt(index) == opening) {
                depth++;
            } else if (source.charAt(index) == closing && --depth == 0) {
                return index;
            }
        }
        return -1;
    }

    private static String sanitizedSource(String source) {
        StringBuilder clean = new StringBuilder(source.length());
        int state = 0;
        for (int index = 0; index < source.length(); index++) {
            char value = source.charAt(index);
            char next = index + 1 < source.length()
                    ? source.charAt(index + 1) : '\0';
            if (state == 0 && value == '/' && next == '/') {
                clean.append("  ");
                index++;
                state = 1;
            } else if (state == 0 && value == '/' && next == '*') {
                clean.append("  ");
                index++;
                state = 2;
            } else if (state == 0 && (value == '"' || value == '\'')) {
                clean.append(' ');
                state = value == '"' ? 3 : 4;
            } else if (state == 1 && (value == '\n' || value == '\r')) {
                clean.append(value);
                state = 0;
            } else if (state == 2 && value == '*' && next == '/') {
                clean.append("  ");
                index++;
                state = 0;
            } else if ((state == 3 || state == 4) && value == '\\') {
                clean.append(' ');
                if (++index < source.length()) {
                    clean.append(' ');
                }
            } else if ((state == 3 && value == '"')
                    || (state == 4 && value == '\'')) {
                clean.append(' ');
                state = 0;
            } else {
                clean.append(state == 0 ? value : ' ');
            }
        }
        return clean.toString();
    }

    private record GuardEvent(int offset, String kind) {
    }

    private record MethodSignature(String name, String parameters) {
    }

    private record LookupInspection(boolean valid, boolean lookupSeen) {
    }

    private record ConditionalBranch(
            int start,
            int end,
            String condition,
            String whenTrue,
            String whenFalse,
            boolean compatibilityCondition) {
    }

    @Test
    void lwjglBackendDoesNotOwnIndependentMusicOrSfxSources()
            throws IOException {
        String source = Files.readString(
                AUDIO_ROOT.resolve("LWJGLAudioBackend.java"));
        for (String forbidden : List.of(
                "sfxSources", "musicSource", "playWav(",
                "AL_LOOPING", "AL_PITCH", "alSourcei(source, AL_BUFFER")) {
            assertFalse(source.contains(forbidden), forbidden);
        }
    }

    @Test
    void openAlImportsExistOnlyInTheFinalPcmSink() throws IOException {
        try (var files = Files.walk(AUDIO_ROOT)) {
            List<Path> offenders = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return Files.readString(path)
                                    .contains("import org.lwjgl.openal");
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .filter(path -> !path.endsWith("OpenAlPcmSink.java"))
                    .toList();
            assertTrue(offenders.isEmpty(), offenders::toString);
        }
    }

    @Test
    void finalPcmSinkOwnsOneStereoPresentationSource()
            throws IOException {
        String source = Files.readString(
                AUDIO_ROOT.resolve("output/OpenAlPcmSink.java"));
        assertEquals(1, Pattern.compile(
                        "\\bint\\s+presentationSource\\b")
                .matcher(source).results().count());
        assertTrue(source.contains(
                "import com.openggf.audio.presentation.AudioPresentationFrameView;"));
        assertTrue(source.contains(
                "void accept(AudioPresentationFrameView frame)"));
        assertTrue(source.contains("AL_FORMAT_STEREO16"));
        assertFalse(source.contains("AL_FORMAT_MONO"));
        assertFalse(source.contains("AL_LOOPING"));
        assertFalse(source.contains("AL_PITCH"));
    }

    @Test
    void productionPumpsTheSpeakerExactlyOnceAtTheOuterFrameBoundary()
            throws IOException {
        Path productionRoot = Path.of("src/main/java/com/openggf");
        try (var files = Files.walk(productionRoot)) {
            List<String> pumps = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(path -> {
                        try {
                            return Files.readAllLines(path).stream()
                                    .filter(line -> line.contains(
                                            "GameServices.audio().update()"))
                                    .map(line -> path + ": " + line.trim());
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .toList();
            assertEquals(List.of(
                    Path.of("src/main/java/com/openggf/Engine.java")
                            + ": GameServices.audio().update();"), pumps);
        }
    }

    @Test
    void backendHasNoSecondCoordFlagOwnerOrCompatibilityFactory()
            throws IOException {
        String source = Files.readString(
                AUDIO_ROOT.resolve("AbstractSmpsAudioBackend.java"));
        assertFalse(source.contains("legacyCoordFlagHandlers"));
        assertFalse(source.contains("inactiveCoordFlagHandlers"));
        assertFalse(source.contains("createLegacySourceFactory"));
    }

    @Test
    void onlyProducerOwnsAudioFrameClockAndPcmHistory() throws IOException {
        assertEquals(List.of(),
                productionOffenders("new AudioFrameClock(",
                        AUDIO_FRAME_CLOCK_OWNERS, Set.of()),
                "AudioFrameClock construction outside the producer, its "
                        + "capture handles, and the clocked-silence handle");
        assertEquals(List.of(),
                productionOffenders("new PcmHistoryRing(",
                        PCM_HISTORY_OWNERS, PCM_HISTORY_ITSELF),
                "PcmHistoryRing construction outside the producer");
        // No trailing space: a reintroduced second cursor is at least as likely
        // to appear as `new PcmHistoryRing.ReverseCursor(...)`, a generic type
        // argument or a cast as it is as a bare field declaration.
        assertEquals(List.of(),
                productionOffenders("PcmHistoryRing.ReverseCursor",
                        PCM_HISTORY_OWNERS, PCM_HISTORY_ITSELF),
                "PcmHistoryRing reverse cursor ownership outside the producer");

        // The parity probe is exempted from the clock allow-list only because
        // it mirrors cadence; bound that exemption by proving its clock never
        // escapes to drive anything.
        for (var method : AudioPresentationParityProbe.class.getMethods()) {
            assertNotEquals(AudioFrameClock.class, method.getReturnType(),
                    "the parity probe must not hand out its mirror clock: "
                            + method.getName());
        }
    }

    @Test
    void noRuntimeInstallationOrCaptureLeaseSwitchRemains() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String token : FORBIDDEN_RUNTIME_TOKENS) {
            offenders.addAll(
                    productionOffenders(token, Set.of(), Set.of()));
        }
        assertEquals(List.of(), offenders.stream().sorted().distinct().toList(),
                "superseded runtime installation / capture-lease switch tokens");

        assertEquals(List.of(),
                productionOffenders("failNextReverseRelease",
                        REVERSE_RELEASE_INJECTION_OWNER, Set.of()),
                "the reverse-release failure injection is manager-private");
    }

    @Test
    void backendHasNoPresentationHandoffOrReverseCursor() throws IOException {
        for (String backendFile : List.of(
                "AudioBackend.java",
                "AbstractSmpsAudioBackend.java",
                "LWJGLAudioBackend.java",
                "HeadlessSmpsAudioBackend.java",
                "NullAudioBackend.java")) {
            String source = Files.readString(AUDIO_ROOT.resolve(backendFile));
            for (String forbidden : List.of(
                    "presentationHandoff",
                    "handoffMusicData",
                    "handoffSfxData",
                    "pcmHistory",
                    "PcmHistoryRing",
                    "reverseCursor",
                    "ReverseCursor",
                    "AudioBackendLogicalSnapshot",
                    "DeterministicAudioRuntime",
                    "beginReversePresentation",
                    "endReversePresentation",
                    "setReversePlaybackRate",
                    "setForwardPlaybackRate",
                    "setRewindHistoryArmed")) {
                assertFalse(source.contains(forbidden),
                        backendFile + " still owns " + forbidden);
            }
        }
    }

    /**
     * The speaker and every capture lease must be fed by the one producer
     * fan-out, from the one packet that presentation just selected. A second
     * production site that handed a frame view to a sink or a listener could
     * give the recorder a different packet from the speaker's — exactly the
     * split the ROM/integration parity tests assert cannot happen — so the fan
     * out is pinned to the producer here rather than only observed in tests.
     */
    @Test
    void onlyTheProducerFansOnePacketOutToSpeakerAndCaptureConsumers()
            throws IOException {
        assertEquals(List.of(),
                productionOffenders("sink.accept(",
                        PRESENTATION_FAN_OUT_OWNER, Set.of()),
                "speaker packets are handed out only by the producer");
        assertEquals(List.of(),
                productionOffenders("onPresentationFrame(",
                        PRESENTATION_FAN_OUT_OWNER,
                        PRESENTATION_LISTENER_CONTRACT),
                "capture packets are handed out only by the producer");
    }

    @Test
    void noVoiceWritesDirectlyToOpenAl() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String token : OPENAL_TOKENS) {
            offenders.addAll(productionOffenders(
                    token, FINAL_PCM_SINK, Set.of()));
        }
        assertEquals(List.of(), offenders.stream().sorted().distinct().toList(),
                "OpenAL is written to only by the single final-PCM sink");
    }

    @Test
    void gameplayAudioTimelineIsToolingOnlyAndCannotDriveAudioOrTraceAuthority()
            throws IOException {
        JavaClasses production = productionClasses();
        assertEquals(List.of(), timelineDependenciesOutsideTimeline(production),
                "production runtime must not depend on gameplay-audio timeline tooling");

        JavaClasses timeline = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("com.openggf.tools.audio.timeline");
        assertEquals(List.of(), timelineAuthorityCalls(timeline),
                "timeline tooling must remain a read-only schema boundary");
        assertEquals(List.of(), timelineAudioOwnerDependencies(timeline),
                "timeline tooling may not depend on mutation-capable audio owners");

        JavaClasses fixture = new ClassFileImporter()
                .importClasses(RepresentativeTimelineAuthorityBypass.class);
        assertEquals(1, timelineDependenciesOutsideTimeline(fixture).size(),
                "fully-qualified timeline references must be visible as class dependencies");
        assertEquals(13, timelineAuthorityCalls(fixture).size(),
                "representative direct audio mutation/advance calls must be visible");

        JavaClasses ownerFixture = new ClassFileImporter()
                .importClasses(RepresentativeTimelineAudioOwnerBypass.class);
        assertEquals(7, timelineAudioOwnerDependencies(ownerFixture).size(),
                "fully-qualified mutation-capable audio owners must be denied by ownership");
    }

    /**
     * @param allowedPaths repo-relative path suffixes (e.g.
     *                     {@code "audio/presentation/X.java"}) permitted to
     *                     contain {@code token}; matched on the path so a
     *                     same-named file elsewhere is not exempted
     */
    private static List<String> productionOffenders(
            String token, Set<String> allowedPaths,
            Set<String> ignoredPaths) throws IOException {
        try (var files = Files.walk(PRODUCTION_ROOT)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !matchesAny(path, allowedPaths))
                    .filter(path -> !matchesAny(path, ignoredPaths))
                    .filter(path -> {
                        try {
                            return Files.readString(path).contains(token);
                        } catch (IOException failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .map(path -> token + " @ " + path)
                    .sorted()
                    .toList();
        }
    }

    private static boolean matchesAny(Path path, Set<String> pathSuffixes) {
        String normalized = path.toString().replace('\\', '/');
        return pathSuffixes.stream()
                .anyMatch(suffix -> normalized.endsWith("/" + suffix));
    }

    private static List<String> timelineDependenciesOutsideTimeline(JavaClasses classes) {
        return classes.stream()
                .filter(origin -> !origin.getPackageName().startsWith("com.openggf.tools.audio.timeline"))
                .flatMap(origin -> origin.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getTargetClass().getPackageName()
                        .startsWith("com.openggf.tools.audio.timeline"))
                .map(Dependency::getDescription)
                .sorted()
                .toList();
    }

    private static List<String> timelineAuthorityCalls(JavaClasses classes) {
        return classes.stream()
                .flatMap(origin -> origin.getMethodCallsFromSelf().stream())
                .filter(call -> isTimelineAuthorityCall(call, call.getTargetOwner()))
                .map(JavaMethodCall::getDescription)
                .sorted()
                .toList();
    }

    private static List<String> timelineAudioOwnerDependencies(JavaClasses classes) {
        return classes.stream()
                .flatMap(origin -> origin.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> dependency.getTargetClass().getPackageName()
                        .startsWith("com.openggf.audio"))
                .filter(dependency -> !TIMELINE_READ_ONLY_AUDIO_DEPENDENCIES
                        .contains(dependency.getTargetClass().getFullName()))
                .map(Dependency::getDescription)
                .sorted()
                .toList();
    }

    private static boolean isTimelineAuthorityCall(JavaMethodCall call, JavaClass targetOwner) {
        if (targetOwner.isEquivalentTo(AudioManager.class)) {
            return Set.of("playMusic", "playSfx", "replayTimelineCommand",
                    "replayTimelineCommandLogically", "restoreLogicalSnapshot", "presentFrame", "update")
                    .contains(call.getName());
        }
        if (targetOwner.isEquivalentTo(AudioPresentationProducer.class)) {
            return call.getName().equals("present");
        }
        if (targetOwner.isEquivalentTo(AudioKeyframeStore.class)) {
            return call.getName().equals("replayTo") || call.getName().equals("replayToLogicalState");
        }
        if (targetOwner.isAssignableTo(AudioBackend.class)) {
            return Set.of("playMusic", "playSfx", "update").contains(call.getName());
        }
        return targetOwner.getPackageName().startsWith("com.openggf.trace.timing");
    }

    @Test
    void productionDoesNotBypassManagerOwnedAudioCommands() {
        JavaClasses production = productionClasses();

        assertEquals(List.of(), directBackendCommandBypasses(production));
    }

    @Test
    void directBackendGuardRejectsRepresentativeBypass() {
        JavaClasses fixture = new ClassFileImporter()
                .importClasses(RepresentativeBackendBypass.class);

        List<String> bypasses = directBackendCommandBypasses(fixture);

        assertEquals(2, bypasses.size(), bypasses::toString);
        assertTrue(bypasses.stream().anyMatch(
                call -> call.contains(".playMusic(")));
        assertTrue(bypasses.stream().anyMatch(
                call -> call.contains(".toggleMute(")));
    }

    private static List<String> directBackendCommandBypasses(
            JavaClasses classes) {
        return classes.stream()
                .flatMap(owner -> owner.getMethodCallsFromSelf().stream())
                .filter(TestAudioPresentationArchitectureGuard
                        ::targetsBackendCommand)
                .filter(call -> !isApprovedBackendCommandOwner(
                        call.getOriginOwner()))
                .map(JavaMethodCall::getDescription)
                .sorted()
                .toList();
    }

    private static boolean targetsBackendCommand(JavaMethodCall call) {
        return BACKEND_COMMANDS.contains(call.getName())
                && call.getTargetOwner().isAssignableTo(AudioBackend.class);
    }

    private static boolean isApprovedBackendCommandOwner(
            com.tngtech.archunit.core.domain.JavaClass owner) {
        return owner.isEquivalentTo(AudioManager.class)
                || owner.isEquivalentTo(AudioPresentationSourceFactory.class)
                || owner.isAssignableTo(AudioBackend.class);
    }

    private static final class RepresentativeBackendBypass {
        private final AudioBackend backend = new NullAudioBackend();

        private void bypass() {
            backend.playMusic(1);
            backend.toggleMute(ChannelType.FM, 0);
        }
    }

    private static final class RepresentativeTimelineAuthorityBypass {
        @SuppressWarnings("unused")
        private final com.openggf.tools.audio.timeline.S1GameplayAudioTimeline.Metadata metadata = null;

        private void bypass(com.openggf.audio.AudioManager audio,
                com.openggf.audio.presentation.AudioPresentationProducer producer,
                com.openggf.audio.rewind.AudioKeyframeStore keyframes,
                com.openggf.audio.AudioBackend backend) {
            audio.playMusic(0x81);
            audio.playSfx("ring");
            audio.replayTimelineCommand(null);
            audio.replayTimelineCommandLogically(null);
            audio.restoreLogicalSnapshot(null);
            audio.presentFrame(null);
            audio.update();
            producer.present(0, null);
            keyframes.replayTo(null, 0, null);
            keyframes.replayToLogicalState(null, 0);
            backend.playMusic(0x81);
            backend.playSfx("ring");
            backend.update();
        }
    }

    private static final class RepresentativeTimelineAudioOwnerBypass {
        private void bypass(com.openggf.audio.AudioManager audio,
                com.openggf.audio.debug.StandaloneAudioPresentationHost host) {
            audio.playStandaloneMusic(null, null);
            audio.playStandaloneSfx(null, null, 1.0f);
            host.playMusic(null, null);
            host.playSfx(null, null, 1.0f);
            host.presentFrame();
        }
    }
}
