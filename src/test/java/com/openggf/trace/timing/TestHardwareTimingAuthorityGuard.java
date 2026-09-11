package com.openggf.trace.timing;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Source-level ratchet for the one-way hardware-timing replay input boundary. */
class TestHardwareTimingAuthorityGuard {
    private static final Path SRC_MAIN = Path.of("src", "main", "java");
    private static final Path SRC_TEST = Path.of("src", "test", "java", "com", "openggf", "trace", "timing");
    private static final List<Path> UNBOUND_REQUEST_REFERENCE_SOURCES = List.of(
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2RequestAwareOracleSchema.java"),
            Path.of("src/main/java/com/openggf/tools/audio/parity/s2/S2RequestAwareOracleRawStream.java"));
    private static final SourceCatalogueLoader SOURCE_CATALOGUES =
            new SourceCatalogueLoader(new FileSystemSourceTreeAccess());
    private static final String TIMING_PACKAGE_PREFIX = "com.openggf.trace.timing";
    private static final String HARDWARE_TIMING_SERVICE = "com.openggf.game.timing.HardwareTimingService";
    private static final String HARDWARE_TIMING_FILE = "hardware_timing.jsonl";
    private static final Pattern PACKAGE_DECLARATION = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");
    private static final Pattern HARDWARE_TIMING_SERVICE_ACCESS = Pattern.compile(
            "(?m)^import\\s+(?:static\\s+)?com\\.openggf\\.game\\.timing"
                    + "(?:\\.HardwareTimingService(?:\\.[\\w*]+)?|\\.\\*)\\s*;"
                    + "|\\bcom\\.openggf\\.game\\.timing\\.HardwareTimingService\\b");
    private static final Pattern TIMING_PARSER_ACCESS = Pattern.compile(
            "(?m)^import\\s+(?:static\\s+)?com\\.openggf\\.trace\\.timing(?:\\.[\\w*]+)*\\s*;"
                    + "|\\bcom\\.openggf\\.trace\\.timing\\.[A-Z][A-Za-z0-9_]*\\b");
    private static final Pattern REPLAY_PORT_REFERENCE = Pattern.compile(
            "\\bHardwareTimingReplayPort\\b");
    private static final Pattern REPLAY_PORT_APPLY = Pattern.compile(
            "(?:\\b[A-Za-z_$][A-Za-z0-9_$]*|\\))\\s*\\.\\s*"
                    + "(?:apply|applySuppressedRowCompletion)\\s*\\(");
    private static final Pattern SUPPRESSED_AUTHORITY_ADMISSION = Pattern.compile(
            "(?:\\b[A-Za-z_$][A-Za-z0-9_$]*|\\))\\s*\\.\\s*"
                    + "admitRecordedSuppressedRowCompletion\\s*\\(");
    private static final Pattern SUPPRESSED_OBSERVER_APPLY = Pattern.compile(
            "(?:\\b[A-Za-z_$][A-Za-z0-9_$]*|\\))\\s*\\.\\s*"
                    + "applySuppressedRowCompletion\\s*\\(");
    private static final Pattern RECORDED_EDGE_LOOKAHEAD = Pattern.compile(
            "hasPendingCompletionAtCurrentRawFrame\\s*\\(");
    /**
     * The replay-only held-tail marker may gate a local delay, but it must
     * never be handed to another production collaborator as an argument: that
     * lets recorded timing select what a production owner does, not merely
     * when engine-created work becomes ready.
     */
    private static final Pattern REPLAY_HELD_TAIL_SIGNAL_ARGUMENT = Pattern.compile(
            "(?<!\\w)(?!if|while|switch|return|assert)[A-Za-z_$][\\w$]*\\s*\\("
                    + "[^()]*\\.\\s*defersLoopTailPreparation\\s*\\(\\s*\\)");
    private static final Pattern IMPORT_DECLARATION = Pattern.compile(
            "(?m)^import\\s+(?:static\\s+)?([\\w.]+)(?:\\.\\*)?\\s*;");
    private static final Pattern HARDWARE_TIMING_FILE_LITERAL = Pattern.compile(
            "\\\"hardware_timing\\.jsonl\\\"");
    private static final Pattern HARDWARE_TIMING_FILE_CONCATENATION = Pattern.compile(
            "\\\"hardware_\\\"\\s*\\+\\s*\\\"timing\\.jsonl\\\"");
    private static final Pattern HARDWARE_TIMING_FILE_INDIRECTION = Pattern.compile(
            "\\b(?:HARDWARE_TIMING_FILE|HARDWARE_TIMING_FILENAME|TIMING_FILE_NAME)\\b");
    private static final Pattern AUXILIARY_PARSER_MARKER = Pattern.compile(
            "aux_state\\.jsonl|\\bloadAuxEvents\\s*\\(|\\bTraceData\\s*\\.\\s*loadAuxEvents\\s*\\(");
    private static final Pattern REFLECTIVE_GAMEPLAY_ACCESS = Pattern.compile(
            "\\b(?:java\\.lang\\.reflect|java\\.lang\\.invoke\\.MethodHandles|MethodHandles|"
                    + "getDeclaredMethod|getMethod|setAccessible|unreflect|findVirtual|findStatic|findSpecial|"
                    + "invokeExact|invokeWithArguments)\\b|\\.invoke\\s*\\(");
    private static final Pattern ROOT_GAMEPLAY_MUTATION = Pattern.compile(
            "\\bGameServices\\s*\\.|\\b(?:setGameMode|setGameplayMode|setLevel|setCentre[XY]|"
                    + "setRingCount|setForcedInputMask|startSession)\\s*\\(");
    private static final List<String> GAMEPLAY_OWNER_PACKAGE_PREFIXES = List.of(
            "com.openggf.game",
            "com.openggf.level",
            "com.openggf.sprites",
            "com.openggf.physics",
            "com.openggf.control",
            "com.openggf.camera");
    private static final List<String> AUXILIARY_PARSER_PATH_PREFIXES = List.of(
            "com/openggf/trace/",
            "com/openggf/game/sonic1/specialstage/",
            "com/openggf/game/sonic3k/specialstage/");
    private static final Set<String> ROOT_GAMEPLAY_OWNER_TYPES = Set.of(
            "com.openggf.Engine",
            "com.openggf.GameLoop",
            "com.openggf.LevelIterationAdmissionController");

    @Test
    void unboundRequestReferenceReaderCannotReachHardwareTimingAuthority() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : UNBOUND_REQUEST_REFERENCE_SOURCES) {
            assertTrue(Files.isRegularFile(source), "missing request reference source: " + source);
            String contents = Files.readString(source);
            if (HARDWARE_TIMING_SERVICE_ACCESS.matcher(contents).find()
                    || TIMING_PARSER_ACCESS.matcher(contents).find()
                    || REPLAY_PORT_REFERENCE.matcher(contents).find()
                    || HARDWARE_TIMING_FILE_LITERAL.matcher(contents).find()) {
                violations.add(source.toString());
            }
        }
        assertEquals(List.of(), violations,
                "unbound S2 request evidence must stay outside timing authority");
    }

    @Test
    void sourceCatalogueLoadsTheSameRootOnlyOnce() throws IOException {
        Path root = Path.of("production");
        Path first = root.resolve("sample/First.java");
        Path second = root.resolve("sample/Second.java");
        CountingSourceTreeAccess access = new CountingSourceTreeAccess(
                Map.of(root, List.of(first, second)),
                Map.of(
                        first, "package sample; class First {}",
                        second, "package sample; class Second {}"));
        SourceCatalogueLoader loader = new SourceCatalogueLoader(access);

        List<SourceFile> firstLoad = loader.load(root);
        List<SourceFile> secondLoad = loader.load(root);

        assertEquals(firstLoad, secondLoad);
        assertEquals(1, access.walkCount(root));
        assertEquals(2, access.readCount());
        assertThrows(UnsupportedOperationException.class,
                () -> firstLoad.add(new SourceFile("Other.java", "sample", "Other.java", "")));
    }

    @Test
    void measurementToolingCannotBecomeRuntimeOrReplayAuthority()
            throws IOException {
        try (Stream<Path> sources = Files.walk(SRC_MAIN)) {
            List<String> violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.startsWith(
                            SRC_MAIN.resolve("com/openggf/tools/timing")))
                    .filter(path -> !path.endsWith(
                            Path.of("HardwareTimingStreamLoader.java")))
                    .filter(path -> {
                        try {
                            String source = Files.readString(path);
                            return source.contains("com.openggf.tools.timing")
                                    || source.contains(
                                    "\"load_time_measurements.jsonl\"");
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .map(SRC_MAIN::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertEquals(List.of(), violations,
                    "measurement tooling leaked into runtime/replay authority");
        }
    }

    @Test
    void recordedEdgesCannotSelectProductionServiceBoundaries() throws IOException {
        try (Stream<Path> sources = Files.walk(SRC_MAIN)) {
            List<String> violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return RECORDED_EDGE_LOOKAHEAD.matcher(
                                    Files.readString(path)).find();
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .map(SRC_MAIN::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertEquals(List.of(), violations,
                    "recorded timing must not choose which production boundary runs");
        }
    }

    @Test
    void recordedHeldTailSignalCannotBePassedToProductionCollaborators()
            throws IOException {
        try (Stream<Path> sources = Files.walk(SRC_MAIN)) {
            List<String> violations = sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            return REPLAY_HELD_TAIL_SIGNAL_ARGUMENT.matcher(
                                    Files.readString(path)).find();
                        } catch (IOException exception) {
                            throw new java.io.UncheckedIOException(exception);
                        }
                    })
                    .map(SRC_MAIN::relativize)
                    .map(Path::toString)
                    .sorted()
                    .toList();
            assertEquals(List.of(), violations,
                    "recorded timing must not select what a production owner does");
        }
    }

    @Test
    void sourceCatalogueKeepsDifferentRootsIsolated() throws IOException {
        Path firstRoot = Path.of("first-production");
        Path secondRoot = Path.of("second-production");
        Path first = firstRoot.resolve("first/One.java");
        Path second = secondRoot.resolve("second/Two.java");
        CountingSourceTreeAccess access = new CountingSourceTreeAccess(
                Map.of(
                        firstRoot, List.of(first),
                        secondRoot, List.of(second)),
                Map.of(
                        first, "package first; class One {}",
                        second, "package second; class Two {}"));
        SourceCatalogueLoader loader = new SourceCatalogueLoader(access);

        List<SourceFile> firstSources = loader.load(firstRoot);
        List<SourceFile> secondSources = loader.load(secondRoot);

        assertEquals(List.of("first/One.java"),
                firstSources.stream().map(SourceFile::relativePath).toList());
        assertEquals(List.of("second/Two.java"),
                secondSources.stream().map(SourceFile::relativePath).toList());
        assertEquals(1, access.walkCount(firstRoot));
        assertEquals(1, access.walkCount(secondRoot));
    }

    @Test
    void sourceCatalogueKeepsCraftedViolationOrderAndTextExact() throws IOException {
        Path root = Path.of("production");
        Path first = root.resolve("first/First.java");
        Path second = root.resolve("second/Second.java");
        CountingSourceTreeAccess access = new CountingSourceTreeAccess(
                Map.of(root, List.of(second, first)),
                Map.of(
                        first, """
                                package sample.first;
                                class First { String file = "hardware_timing.jsonl"; }
                                """,
                        second, """
                                package sample.second;
                                class Second { String file = HARDWARE_TIMING_FILE; }
                                """));
        SourceCatalogueLoader loader = new SourceCatalogueLoader(access);

        List<String> violations = new ArrayList<>();
        for (SourceFile source : loader.load(root)) {
            violations.addAll(scanUnauthorizedTimingFilenameConstruction(
                    source.packageName(), source.fileName(), source.content()));
        }

        assertEquals(List.of(
                "sample.first.First.java constructs hardware_timing.jsonl outside "
                        + "com.openggf.trace.timing.HardwareTimingStreamLoader",
                "sample.second.Second.java constructs hardware_timing.jsonl outside "
                        + "com.openggf.trace.timing.HardwareTimingStreamLoader"), violations);
    }

    @Test
    void rejectsDirectWildcardStaticAndFullyQualifiedTimingServiceAccess() {
        assertDetected(scanForbiddenTimingServiceAccess("sample/TraceFrame.java", """
                import com.openggf.game.timing.HardwareTimingService;
                class TraceFrame {}
                """));
        assertDetected(scanForbiddenTimingServiceAccess("sample/TraceEvent.java", """
                import com.openggf.game.timing.*;
                class TraceEvent {}
                """));
        assertDetected(scanForbiddenTimingServiceAccess("sample/TraceBinder.java", """
                import static com.openggf.game.timing.HardwareTimingService.release;
                class TraceBinder {}
                """));
        assertDetected(scanForbiddenTimingServiceAccess("sample/TraceData.java", """
                class TraceData {
                    com.openggf.game.timing.HardwareTimingService timing;
                }
                """));
    }

    @Test
    void discoversAuxiliaryParsersFromOwnedPathsAndParserMarkers() {
        assertTrue(isComparisonOrAuxiliaryParser("com/openggf/trace/TraceData.java", """
                class TraceData { void parse() { loadAuxEvents(); } }
                """));
        assertTrue(isComparisonOrAuxiliaryParser(
                "com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceData.java", """
                class Sonic1SpecialStageTraceData { void parse() { TraceData.loadAuxEvents(path); } }
                """));
        assertFalse(isComparisonOrAuxiliaryParser("com/openggf/game/sonic1/objects/Badnik.java", """
                class Badnik { void update() { loadAuxEvents(); } }
                """));
    }

    @Test
    void rejectsAuxiliaryParserThatUsesOnlyTheAuxFilenameLiteralToReachTimingService() {
        String relative = "com/openggf/trace/NewAuxiliaryParser.java";
        String source = """
                package com.openggf.trace;
                import com.openggf.game.timing.HardwareTimingService;
                class NewAuxiliaryParser {
                    void load(java.nio.file.Path directory) {
                        directory.resolve("aux_state.jsonl");
                    }
                }
                """;

        assertTrue(isComparisonOrAuxiliaryParser(relative, source));
        assertDetected(scanForbiddenTimingServiceAccess(relative, source));
    }

    @Test
    void rejectsDirectWildcardStaticAndFullyQualifiedParserAccessFromGameplayOwners() {
        assertDetected(scanGameplayTimingParserAccess("com.openggf.physics", "Physics.java", """
                import com.openggf.trace.timing.HardwareTimingSchedule;
                class Physics {}
                """));
        assertDetected(scanGameplayTimingParserAccess("com.openggf.control", "InputController.java", """
                import com.openggf.trace.timing.*;
                class InputController {}
                """));
        assertDetected(scanGameplayTimingParserAccess("com.openggf.camera", "Camera.java", """
                import static com.openggf.trace.timing.HardwareTimingSchedule.empty;
                class Camera {}
                """));
        assertDetected(scanGameplayTimingParserAccess("com.openggf", "GameLoop.java", """
                class GameLoop {
                    com.openggf.trace.timing.HardwareTimingSchedule schedule;
                }
                """));
        assertDetected(scanGameplayTimingParserAccess("com.openggf", "GameplayMutationRoot.java", """
                import com.openggf.trace.timing.HardwareTimingSchedule;
                class GameplayMutationRoot {
                    void update() { setGameMode(null); }
                }
                """));
    }

    @Test
    void rejectsDirectConcatenatedAndIndirectTimingFilenameConstructionOutsideTheLoader() {
        assertDetected(scanUnauthorizedTimingFilenameConstruction("com.openggf.trace", "TraceData.java", """
                class TraceData { String file = "hardware_timing.jsonl"; }
                """));
        assertDetected(scanUnauthorizedTimingFilenameConstruction("com.openggf.trace", "TraceData.java", """
                class TraceData { String file = "hardware_" + "timing.jsonl"; }
                """));
        assertDetected(scanUnauthorizedTimingFilenameConstruction("com.openggf.trace", "TraceData.java", """
                class TraceData { String file = HARDWARE_TIMING_FILE; }
                """));
        assertTrue(scanUnauthorizedTimingFilenameConstruction(TIMING_PACKAGE_PREFIX,
                "HardwareTimingStreamLoader.java", """
                class HardwareTimingStreamLoader {
                    String file = "hardware_" + "timing.jsonl";
                }
                """).isEmpty());
    }

    @Test
    void rejectsReflectiveAndMethodHandleTimingFixtureMutationAcrossTestRoots() {
        assertDetected(scanReflectiveTimingFixtureAccess("sample/TestTimingFixture.java", """
                import com.openggf.game.timing.HardwareTimingService;
                import java.lang.reflect.Method;
                class TestTimingFixture { void test(Method method) throws Exception { method.invoke(null); } }
                """));
        assertDetected(scanReflectiveTimingFixtureAccess("sample/TestTimingFixture.java", """
                class TestTimingFixture {
                    void test() throws Exception {
                        java.lang.reflect.Method method = null;
                        method.invoke(null);
                        Class.forName("com.openggf.game.timing.HardwareTimingService");
                    }
                }
                """));
        assertDetected(scanReflectiveTimingFixtureAccess("sample/TestTimingFixture.java", """
                import com.openggf.game.timing.HardwareTimingService;
                import java.lang.invoke.MethodHandles;
                class TestTimingFixture {
                    void test(MethodHandles.Lookup lookup) throws Throwable {
                        lookup.findVirtual(HardwareTimingService.class, "release", null).invoke(null);
                    }
                }
                """));
    }

    @Test
    void rejectsReplayPortApplyOutsideTheStatelessBoundaryObserver() {
        assertDetected(scanReplayPortApply("com/openggf/trace/replay/Driver.java", """
                class Driver { void drive(HardwareTimingReplayPort replayPort) {
                    replayPort.apply(null);
                } }
                """));
        assertTrue(scanReplayPortApply(
                "com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java",
                "class TraceHardwareTimingBoundaryObserver { void x() { replayPort.apply(null); } }")
                .isEmpty());
        assertDetected(scanReplayPortApply("com/openggf/trace/replay/Alias.java", """
                class Alias { void drive(HardwareTimingReplayPort gate) {
                    gate.apply(null);
                } }
                """));
        assertDetected(scanReplayPortApply("com/openggf/trace/replay/Field.java", """
                class Field {
                    HardwareTimingReplayPort gate;
                    void drive() { this.gate.apply(null); }
                }
                """));
        assertDetected(scanReplayPortApply("com/openggf/trace/replay/Nested.java", """
                class Nested {
                    HardwareTimingReplayPort port() { return null; }
                    void drive() { port().apply(null); }
                }
                """));
        assertDetected(scanReplayPortApply("com/openggf/trace/replay/Driver.java", """
                class Driver { void drive(HardwareTimingReplayPort replayPort) {
                    replayPort.applySuppressedRowCompletion();
                } }
                """));
        assertTrue(scanReplayPortApply(
                "com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java",
                """
                class TraceHardwareTimingBoundaryObserver {
                    void x() { replayPort.applySuppressedRowCompletion(); }
                }
                """).isEmpty());
    }

    @Test
    void suppressedRowAuthorityIsConfinedToTheReplayPort() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            violations.addAll(scanSuppressedAuthorityAdmission(
                    source.relativePath(), source.content()));
        }
        assertNoViolations(
                "suppressed-row completion authority must remain inside the replay port",
                violations);

        assertDetected(scanSuppressedAuthorityAdmission(
                "com/openggf/trace/replay/Driver.java", """
                class Driver {
                    void drive(RecordedCompletionAuthority authority) {
                        authority.admitRecordedSuppressedRowCompletion(null, null, 0, null);
                    }
                }
                """));
        assertTrue(scanSuppressedAuthorityAdmission(
                "com/openggf/trace/timing/HardwareTimingReplayPort.java", """
                class HardwareTimingReplayPort {
                    void x() {
                        authority.admitRecordedSuppressedRowCompletion(null, null, 0, null);
                    }
                }
                """).isEmpty());
    }

    @Test
    void suppressedRowObserverEntryIsConfinedToTheClosure() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            violations.addAll(scanSuppressedObserverApply(
                    source.relativePath(), source.content()));
        }
        assertNoViolations(
                "suppressed-row observer entry must remain inside the row closure",
                violations);

        assertDetected(scanSuppressedObserverApply(
                "com/openggf/trace/replay/Driver.java", """
                class Driver {
                    void drive(TraceHardwareTimingBoundaryObserver observer) {
                        observer.applySuppressedRowCompletion();
                    }
                }
                """));
        assertTrue(scanSuppressedObserverApply(
                "com/openggf/trace/replay/TraceSuppressedRowClosure.java", """
                class TraceSuppressedRowClosure {
                    void x() { observer.applySuppressedRowCompletion(); }
                }
                """).isEmpty());
        assertTrue(scanSuppressedObserverApply(
                "com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java", """
                class TraceHardwareTimingBoundaryObserver {
                    void x() { replayPort.applySuppressedRowCompletion(); }
                }
                """).isEmpty());
    }

    @Test
    void rejectsGameplaySubsystemDependenciesFromReplayPort() {
        assertDetected(scanReplayPortDependencies("""
                import com.openggf.level.LevelManager;
                class HardwareTimingReplayPort {}
                """));
        assertDetected(scanReplayPortDependencies("""
                import com.openggf.game.TitleCardProvider;
                class HardwareTimingReplayPort {}
                """));
        assertDetected(scanReplayPortDependencies("""
                import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
                class HardwareTimingReplayPort {}
                """));
        assertDetected(scanReplayPortDependencies("""
                import com.openggf.game.sonic2.objects.TornadoObjectInstance;
                class HardwareTimingReplayPort {}
                """));
        assertDetected(scanReplayPortDependencies("""
                import com.openggf.level.objects.ObjectManager;
                class HardwareTimingReplayPort {}
                """));
        assertTrue(scanReplayPortDependencies("""
                import com.openggf.game.timing.RecordedCompletionAuthority;
                class HardwareTimingReplayPort {}
                """).isEmpty());
    }

    @Test
    void comparisonAndAuxiliaryParsersDoNotImportTheGameplayTimingService() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            if (!isComparisonOrAuxiliaryParser(source.relativePath(), source.content())) {
                continue;
            }
            violations.addAll(scanForbiddenTimingServiceAccess(source.relativePath(), source.content()));
        }

        assertNoViolations("comparison and aux parsers must not control gameplay timing", violations);
    }

    @Test
    void dynamicArtParsersAndComparatorsCannotBecomeHardwareAuthority()
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            boolean comparisonSource =
                    source.relativePath().startsWith("com/openggf/trace/")
                    || source.relativePath().equals(
                            "com/openggf/game/sonic1/specialstage/"
                                    + "Sonic1SpecialStageTraceData.java");
            if (!comparisonSource
                    || (!source.content().contains("DynamicArtDiagnosticsSnapshot")
                    && !source.content().contains(
                            "dynamic_art_transfer_state_per_frame"))) {
                continue;
            }
            violations.addAll(scanForbiddenTimingServiceAccess(
                    source.relativePath(), source.content()));
            violations.addAll(scanReplayPortApply(
                    source.relativePath(), source.content()));
            violations.addAll(scanUnauthorizedTimingFilenameConstruction(
                    source.packageName(), source.fileName(), source.content()));
        }
        assertNoViolations(
                "dynamic-art evidence must remain outside hardware timing authority",
                violations);
    }

    @Test
    void dynamicArtAuthorityGuardRejectsReplayPortAdmission() {
        assertDetected(scanReplayPortApply(
                "com/openggf/trace/DynamicArtComparator.java", """
                class DynamicArtComparator {
                    HardwareTimingReplayPort port;
                    void compare(Object edge) {
                        port.apply(edge);
                    }
                }
                """));
    }

    @Test
    void onlyTheDedicatedTimingPackageMayParseTheHardwareTimingFile() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            violations.addAll(scanUnauthorizedTimingFilenameConstruction(
                    source.packageName(), source.fileName(), source.content()));
        }

        assertNoViolations("only the timing parser package may parse the hardware timing stream", violations);
    }

    @Test
    void gameplayOwnersDoNotImportTimingParserTypes() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            violations.addAll(scanGameplayTimingParserAccess(
                    source.packageName(), source.fileName(), source.content()));
        }

        assertNoViolations("gameplay owners must receive timing readiness through the gameplay service", violations);
    }

    @Test
    void timingFixturesCannotInvokeGameplayMutationThroughReflection() throws IOException {
        if (!Files.isDirectory(SRC_TEST)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(SRC_TEST)) {
            for (Path file : stream.filter(path -> path.toString().endsWith(".java")).toList()) {
                violations.addAll(scanReflectiveTimingFixtureAccess(
                        SRC_TEST.relativize(file).toString().replace('\\', '/'), Files.readString(file)));
            }
        }

        assertNoViolations("timing fixtures must not mutate gameplay through reflection", violations);
    }

    @Test
    void onlyTheBoundaryObserverMayInvokeReplayPortApply() throws IOException {
        List<String> violations = new ArrayList<>();
        for (SourceFile source : productionSources()) {
            violations.addAll(scanReplayPortApply(source.relativePath(), source.content()));
        }
        assertNoViolations("only the stateless boundary observer may apply replay edges", violations);
    }

    @Test
    void replayPortDoesNotDependOnGameplaySubsystems() throws IOException {
        SourceFile port = productionSource(
                "com/openggf/trace/timing/HardwareTimingReplayPort.java");
        assertNoViolations("replay port must remain bounded to timing authority",
                scanReplayPortDependencies(port.content()));
    }

    private static List<SourceFile> productionSources() throws IOException {
        return SOURCE_CATALOGUES.load(SRC_MAIN);
    }

    private static SourceFile productionSource(String relativePath) throws IOException {
        return productionSources().stream()
                .filter(source -> source.relativePath().equals(relativePath))
                .findFirst()
                .orElseThrow(() -> new NoSuchFileException(SRC_MAIN.resolve(relativePath).toString()));
    }

    private static boolean hasPackagePrefix(String packageName, List<String> prefixes) {
        return prefixes.stream().anyMatch(prefix -> packageName.equals(prefix) || packageName.startsWith(prefix + "."));
    }

    private static boolean hasPathPrefix(String relativePath, List<String> prefixes) {
        return prefixes.stream().anyMatch(relativePath::startsWith);
    }

    private static boolean isComparisonOrAuxiliaryParser(String relative, String source) {
        return relative.equals("com/openggf/trace/TraceFrame.java")
                || relative.equals("com/openggf/trace/TraceEvent.java")
                || relative.equals("com/openggf/trace/TraceBinder.java")
                || (hasPathPrefix(relative, AUXILIARY_PARSER_PATH_PREFIXES)
                && AUXILIARY_PARSER_MARKER.matcher(stripCommentsPreservingStrings(source)).find());
    }

    private static List<String> scanForbiddenTimingServiceAccess(String relative, String source) {
        return scanAccess(relative, stripCommentsAndStrings(source), HARDWARE_TIMING_SERVICE_ACCESS,
                " accesses " + HARDWARE_TIMING_SERVICE);
    }

    private static List<String> scanGameplayTimingParserAccess(String packageName, String fileName, String source) {
        String typeName = packageName + "." + fileName.replaceFirst("\\.java$", "");
        if (typeName.equals("com.openggf.TraceSessionLauncher")) {
            return List.of();
        }
        if (!hasPackagePrefix(packageName, GAMEPLAY_OWNER_PACKAGE_PREFIXES)
                && !ROOT_GAMEPLAY_OWNER_TYPES.contains(typeName)
                && !(packageName.equals("com.openggf")
                && ROOT_GAMEPLAY_MUTATION.matcher(stripCommentsAndStrings(source)).find())) {
            return List.of();
        }
        return scanAccess(typeName.replace('.', '/') + ".java", stripCommentsAndStrings(source), TIMING_PARSER_ACCESS,
                " accesses a " + TIMING_PACKAGE_PREFIX + " parser type");
    }

    private static List<String> scanUnauthorizedTimingFilenameConstruction(
            String packageName, String fileName, String source) {
        if (packageName.equals(TIMING_PACKAGE_PREFIX) && fileName.equals("HardwareTimingStreamLoader.java")) {
            return List.of();
        }
        if (HARDWARE_TIMING_FILE_LITERAL.matcher(source).find()
                || HARDWARE_TIMING_FILE_CONCATENATION.matcher(source).find()
                || HARDWARE_TIMING_FILE_INDIRECTION.matcher(stripCommentsAndStrings(source)).find()) {
            return List.of(packageName + "." + fileName + " constructs " + HARDWARE_TIMING_FILE
                    + " outside " + TIMING_PACKAGE_PREFIX + ".HardwareTimingStreamLoader");
        }
        return List.of();
    }

    private static List<String> scanReflectiveTimingFixtureAccess(String relative, String source) {
        if (!source.contains("HardwareTiming") && !source.contains("hardware_timing")) {
            return List.of();
        }
        if (REFLECTIVE_GAMEPLAY_ACCESS.matcher(stripCommentsAndStrings(source)).find()) {
            return List.of(relative + " reflectively accesses a hardware-timing gameplay boundary");
        }
        return List.of();
    }

    private static List<String> scanReplayPortApply(String relative, String source) {
        if (relative.equals(
                "com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java")) {
            return List.of();
        }
        String stripped = stripCommentsAndStrings(source);
        if (!REPLAY_PORT_REFERENCE.matcher(stripped).find()) {
            return List.of();
        }
        return scanAccess(relative, stripped, REPLAY_PORT_APPLY,
                " invokes HardwareTimingReplayPort.apply outside the boundary observer");
    }

    private static List<String> scanReplayPortDependencies(String source) {
        var imports = IMPORT_DECLARATION.matcher(
                stripCommentsAndStrings(source));
        while (imports.find()) {
            String imported = imports.group(1);
            if (imported.startsWith("com.openggf.")
                    && !imported.startsWith("com.openggf.trace.timing.")
                    && !imported.startsWith("com.openggf.game.timing.")
                    && !imported.startsWith("com.openggf.game.rewind.")) {
                return List.of(
                        "HardwareTimingReplayPort imports gameplay dependency "
                                + imported);
            }
        }
        return List.of();
    }

    private static List<String> scanSuppressedAuthorityAdmission(
            String relative, String source) {
        if (relative.equals(
                "com/openggf/trace/timing/HardwareTimingReplayPort.java")) {
            return List.of();
        }
        return scanAccess(
                relative,
                stripCommentsAndStrings(source),
                SUPPRESSED_AUTHORITY_ADMISSION,
                " invokes suppressed-row completion authority outside the replay port");
    }

    private static List<String> scanSuppressedObserverApply(
            String relative, String source) {
        if (relative.equals(
                "com/openggf/trace/replay/TraceSuppressedRowClosure.java")
                || relative.equals(
                "com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java")) {
            return List.of();
        }
        return scanAccess(
                relative,
                stripCommentsAndStrings(source),
                SUPPRESSED_OBSERVER_APPLY,
                " invokes suppressed-row observer entry outside the row closure");
    }

    private static List<String> scanAccess(String relative, String source, Pattern access, String message) {
        return access.matcher(source).find() ? List.of(relative + message) : List.of();
    }

    private static void assertDetected(List<String> violations) {
        assertFalse(violations.isEmpty(), "source guard must reject this bypass form");
    }

    private static String stripCommentsAndStrings(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    stripped.append(current);
                } else {
                    stripped.append(' ');
                }
            } else if (inBlockComment) {
                if (current == '*' && next == '/') {
                    stripped.append("  ");
                    index++;
                    inBlockComment = false;
                } else {
                    stripped.append(current == '\n' || current == '\r' ? current : ' ');
                }
            } else if (inString || inChar) {
                char terminator = inString ? '"' : '\'';
                stripped.append(current == '\n' || current == '\r' || current == terminator ? current : ' ');
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == terminator) {
                    inString = false;
                    inChar = false;
                }
            } else if (current == '/' && next == '/') {
                stripped.append("  ");
                index++;
                inLineComment = true;
            } else if (current == '/' && next == '*') {
                stripped.append("  ");
                index++;
                inBlockComment = true;
            } else {
                if (current == '"') {
                    inString = true;
                } else if (current == '\'') {
                    inChar = true;
                }
                stripped.append(current);
            }
        }
        return stripped.toString();
    }

    private static String stripCommentsPreservingStrings(String source) {
        StringBuilder stripped = new StringBuilder(source.length());
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;

        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            if (inLineComment) {
                if (current == '\n' || current == '\r') {
                    inLineComment = false;
                    stripped.append(current);
                } else {
                    stripped.append(' ');
                }
                continue;
            }
            if (inBlockComment) {
                if (current == '*' && next == '/') {
                    stripped.append("  ");
                    index++;
                    inBlockComment = false;
                } else {
                    stripped.append(current == '\n' || current == '\r' ? current : ' ');
                }
                continue;
            }
            if (inString || inChar) {
                char terminator = inString ? '"' : '\'';
                stripped.append(current);
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == terminator) {
                    inString = false;
                    inChar = false;
                }
                continue;
            }
            if (current == '/' && next == '/') {
                stripped.append("  ");
                index++;
                inLineComment = true;
            } else if (current == '/' && next == '*') {
                stripped.append("  ");
                index++;
                inBlockComment = true;
            } else {
                if (current == '"') {
                    inString = true;
                } else if (current == '\'') {
                    inChar = true;
                }
                stripped.append(current);
            }
        }
        return stripped.toString();
    }

    private static void assertNoViolations(String message, List<String> violations) {
        if (!violations.isEmpty()) {
            fail(message + ":\n  " + String.join("\n  ", violations));
        }
    }

    private record SourceFile(String relativePath, String packageName, String fileName, String content) {
    }

    private interface SourceTreeAccess {
        List<Path> javaFiles(Path root) throws IOException;

        String readString(Path file) throws IOException;
    }

    private static final class SourceCatalogueLoader {
        private final SourceTreeAccess access;
        private final Map<Path, List<SourceFile>> sourcesByRoot = new java.util.HashMap<>();

        private SourceCatalogueLoader(SourceTreeAccess access) {
            this.access = access;
        }

        private List<SourceFile> load(Path root) throws IOException {
            Path rootKey = root.toAbsolutePath().normalize();
            List<SourceFile> cachedSources = sourcesByRoot.get(rootKey);
            if (cachedSources != null) {
                return cachedSources;
            }
            List<SourceFile> sources = new ArrayList<>();
            for (Path file : access.javaFiles(root).stream().sorted().toList()) {
                String content = access.readString(file);
                var matcher = PACKAGE_DECLARATION.matcher(content);
                String relative = root.relativize(file).toString().replace('\\', '/');
                if (!matcher.find()) {
                    throw new AssertionError("missing package declaration: " + relative);
                }
                sources.add(new SourceFile(
                        relative,
                        matcher.group(1),
                        file.getFileName().toString(),
                        content));
            }
            List<SourceFile> catalogue = List.copyOf(sources);
            sourcesByRoot.put(rootKey, catalogue);
            return catalogue;
        }
    }

    private static final class CountingSourceTreeAccess implements SourceTreeAccess {
        private final Map<Path, List<Path>> filesByRoot;
        private final Map<Path, String> contents;
        private final Map<Path, Integer> walkCounts = new java.util.HashMap<>();
        private int readCount;

        private CountingSourceTreeAccess(Map<Path, List<Path>> filesByRoot, Map<Path, String> contents) {
            this.filesByRoot = filesByRoot;
            this.contents = contents;
        }

        @Override
        public List<Path> javaFiles(Path root) {
            walkCounts.merge(root, 1, Integer::sum);
            return filesByRoot.getOrDefault(root, List.of());
        }

        @Override
        public String readString(Path file) {
            readCount++;
            return contents.get(file);
        }

        private int walkCount(Path root) {
            return walkCounts.getOrDefault(root, 0);
        }

        private int readCount() {
            return readCount;
        }
    }

    private static final class FileSystemSourceTreeAccess implements SourceTreeAccess {
        @Override
        public List<Path> javaFiles(Path root) throws IOException {
            try (Stream<Path> stream = Files.walk(root)) {
                return stream.filter(path -> path.toString().endsWith(".java")).toList();
            }
        }

        @Override
        public String readString(Path file) throws IOException {
            return Files.readString(file);
        }
    }
}
