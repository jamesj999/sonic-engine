package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

class TestSingletonLifecycleGuard {
    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final Set<String> AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE = baseline("""
            src/test/java/com/openggf/TestGameLoop.java#setUp
            src/test/java/com/openggf/TestTraceSessionLauncherRewindPresentation.java#setUp
            src/test/java/com/openggf/audio/AudioRegressionTest.java#setUpClass
            src/test/java/com/openggf/editor/TestEditorRenderingSmoke.java#setUp
            src/test/java/com/openggf/editor/TestEditorToggleIntegration.java#setUp
            src/test/java/com/openggf/editor/TestLevelEditorController.java#setUp
            src/test/java/com/openggf/game/TestCrossGameFeatureProviderRefactor.java#setUp
            src/test/java/com/openggf/game/TestGameStateManager.java#setUp
            src/test/java/com/openggf/game/TestGameModuleProviderLifetimes.java#configureEngineServices
            src/test/java/com/openggf/game/TestGameStateManager.java#configureEngineServices
            src/test/java/com/openggf/game/TestInstaShieldVisual.java#setUpTest
            src/test/java/com/openggf/game/TestInstaShieldVisual.java#setUpClass
            src/test/java/com/openggf/game/TestLegalDisclaimerHandoff.java#setUp
            src/test/java/com/openggf/game/TestPostLoadAssemblyBehavior.java#resetCamera
            src/test/java/com/openggf/game/TestS3kCharacterSpeeds.java#setUp
            src/test/java/com/openggf/game/TestSpindashGating.java#setUp
            src/test/java/com/openggf/game/TestZoneLayoutMutationPipeline.java#setUp
            src/test/java/com/openggf/game/rewind/TestLiveRewindManagerAudioCleanup.java#setUp
            src/test/java/com/openggf/game/rewind/schema/TestRewindPlayerReferenceCodecs.java#setUpRuntime
            src/test/java/com/openggf/game/session/TestGameplayModeContextPlaybackController.java#configureServices
            src/test/java/com/openggf/game/session/TestGameplayModeContextRewindRegistry.java#configureServices
            src/test/java/com/openggf/game/session/TestSessionManager.java#configureServices
            src/test/java/com/openggf/game/sonic1/TestSonic1LivesHudDonation.java#setUp
            src/test/java/com/openggf/game/sonic1/TestSonic1PatternAnimatorRewindSnapshot.java#setUp
            src/test/java/com/openggf/game/sonic1/dataselect/TestS1DataSelectProfile.java#configureEngineServices
            src/test/java/com/openggf/game/sonic1/events/TestSonic1SBZEvents.java#setUp
            src/test/java/com/openggf/game/sonic1/objects/TestSonic1GargoyleObjectInstanceRender.java#setUp
            src/test/java/com/openggf/game/sonic1/objects/TestSonic1LavaGeyserOutOfRange.java#setUp
            src/test/java/com/openggf/game/sonic1/objects/TestSonic1MovingBlockObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic1/objects/badniks/TestBuzzBomberLifecycle.java#setUp
            src/test/java/com/openggf/game/sonic1/objects/badniks/TestSonic1CaterkillerBodyChaining.java#setUp
            src/test/java/com/openggf/game/sonic1/objects/bosses/TestSonic1FzBossEscapeHitCue.java#setUp
            src/test/java/com/openggf/game/sonic2/TestSonic2CnzMutationPipeline.java#setUp
            src/test/java/com/openggf/game/sonic2/TestSonic2LevelEventRewindSnapshot.java#setUp
            src/test/java/com/openggf/game/sonic2/TestSonic2PlcArtRewindSnapshot.java#setUp
            src/test/java/com/openggf/game/sonic2/TestTodo10_MTZEventSpecs.java#setUp
            src/test/java/com/openggf/game/sonic2/TestTodo11_SCZEventSpecs.java#setUp
            src/test/java/com/openggf/game/sonic2/TestTodo12_WFZEventSpecs.java#setUp
            src/test/java/com/openggf/game/sonic2/TestTodo9_DEZEventSpecs.java#setUp
            src/test/java/com/openggf/game/sonic2/dataselect/TestS2DataSelectProfile.java#configureEngineServices
            src/test/java/com/openggf/game/sonic2/objects/TestMonitorObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic2/objects/TestSpiralObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic2/objects/TestSpringObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic2/objects/TestTodo4_MCZBossCollision.java#setUp
            src/test/java/com/openggf/game/sonic2/objects/TestTornadoObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic3k/TestS3kCnzVisualCapture.java#setUpClass
            src/test/java/com/openggf/game/sonic3k/TestSonic3kBootstrapResolver.java#setUp
            src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelEventRewindSnapshot.java#setUp
            src/test/java/com/openggf/game/sonic3k/TestSonic3kPatternAnimatorRewindSnapshot.java#setUp
            src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRewindSnapshot.java#setUp
            src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectManager.java#setUp
            src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectPresentation.java#configureEngineServices
            src/test/java/com/openggf/game/sonic3k/dataselect/TestS3kDataSelectProfile.java#configureEngineServices
            src/test/java/com/openggf/game/sonic3k/events/TestSonic3kMgz2BgRiseEvents.java#setUp
            src/test/java/com/openggf/game/sonic3k/events/TestSonic3kMgz2ChunkEvents.java#setUp
            src/test/java/com/openggf/game/sonic3k/events/TestSonic3kMgz2CollapseEvents.java#setUp
            src/test/java/com/openggf/game/sonic3k/events/TestSonic3kMgz2EndBossEvents.java#setUp
            src/test/java/com/openggf/game/sonic3k/events/TestSonic3kMgz2QuakeEvents.java#setUp
            src/test/java/com/openggf/game/sonic3k/features/TestFireCurtainBoundaryDiag.java#setUp
            src/test/java/com/openggf/game/sonic3k/objects/TestAizVineHandleLogic.java#setUp
            src/test/java/com/openggf/game/sonic3k/objects/TestAiz2BossEndSequenceObjects.java#setUp
            src/test/java/com/openggf/game/sonic3k/objects/TestMGZSwingingPlatformObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kMonitorObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kSpringObjectInstance.java#setUp
            src/test/java/com/openggf/game/sonic3k/objects/badniks/TestMegaChopperBadnikInstance.java#setUp
            src/test/java/com/openggf/game/sonic3k/scroll/SwScrlMgzTest.java#setUpRuntime
            src/test/java/com/openggf/game/sonic3k/specialstage/TestS3kSpecialStageResultsVisual.java#setUpClass
            src/test/java/com/openggf/game/sonic3k/titlescreen/TestSonic3kTitleScreenBootstrap.java#setUp
            src/test/java/com/openggf/graphics/TestFadeManager.java#setUp
            src/test/java/com/openggf/graphics/TestGraphicsManagerFadeRebinding.java#setUp
            src/test/java/com/openggf/graphics/TestGraphicsManagerSpriteSatReplay.java#setUp
            src/test/java/com/openggf/graphics/TestSpriteManagerRender.java#setUp
            src/test/java/com/openggf/level/TestLevelManagerSlotBackgroundCopy.java#setUp
            src/test/java/com/openggf/level/objects/TestObjectManagerCounterBasedDynamicUnload.java#setUp
            src/test/java/com/openggf/level/objects/TestObjectManagerRewindDynamicClassification.java#setUp
            src/test/java/com/openggf/level/objects/TestObjectManagerRewindSnapshot.java#setUp
            src/test/java/com/openggf/level/objects/TestPlaneSwitcherStateIsolation.java#setUp
            src/test/java/com/openggf/level/rings/TestRingManagerRewindSnapshot.java#setUp
            src/test/java/com/openggf/level/scroll/SwScrlArzTest.java#setUp
            src/test/java/com/openggf/level/scroll/SwScrlMczTest.java#setUp
            src/test/java/com/openggf/physics/TestGroundSensor.java#setUp
            src/test/java/com/openggf/physics/TestTerrainCollisionManager.java#setUp
            src/test/java/com/openggf/physics/TestTerrainCollisionManagerReset.java#setUp
            src/test/java/com/openggf/sprites/playable/TestAbstractPlayableSpriteRewindCapture.java#setUp
            src/test/java/com/openggf/sprites/playable/TestLogicalInputControlLockLatch.java#setUp
            src/test/java/com/openggf/sprites/managers/TestSpriteManagerUpdateOrder.java#configureRuntime
            src/test/java/com/openggf/sprites/playable/TestOnObjectAtFrameStartSnapshot.java#configureRuntime
            src/test/java/com/openggf/sprites/playable/TestRespawnStrategies.java#setUp
            src/test/java/com/openggf/sprites/playable/TestSidekickCpuControllerRewindCapture.java#setUp
            src/test/java/com/openggf/tests/TestHTZRisingLavaDisassemblyParity.java#setUp
            src/test/java/com/openggf/tests/TestMonitorIconTiming.java#setUp
            src/test/java/com/openggf/tests/TestOilSurfaceManager.java#setUp
            src/test/java/com/openggf/tests/TestRingManager.java#setUp
            src/test/java/com/openggf/tests/TestSonic1MonitorObjectInstance.java#setUp
            src/test/java/com/openggf/tests/TestSonic3kLightningShieldObjectInstance.java#setUp
            src/test/java/com/openggf/tests/TestSonic3kMonitorObjectInstance.java#setUp
            src/test/java/com/openggf/tests/TestSonic3kZoneFeatureProvider.java#setUp
            src/test/java/com/openggf/tests/TestS3kIczFreezerObject.java#setUp
            src/test/java/com/openggf/tests/TestS3kMgzPulleyAndMantis.java#setUp
            src/test/java/com/openggf/tests/TestS3kMgzTwistingLoopObject.java#setUp
            src/test/java/com/openggf/tests/TestTodo30_TimerErrorReporting.java#setUp
            src/test/java/com/openggf/trace/replay/TraceReplaySessionBootstrapConfigTest.java#setUp
            """);
    private static final Set<String> SCANNER_UTILITY_FILES = Set.of(
            "src/test/java/com/openggf/tests/TestEnvironment.java",
            "src/test/java/com/openggf/tests/HeadlessTestFixture.java",
            "src/test/java/com/openggf/tests/SharedLevel.java",
            "src/test/java/com/openggf/game/rewind/encounter/RewindEncounterValidator.java",
            "src/test/java/com/openggf/tests/TestSingletonLifecycleGuard.java"
    );
    private static final Pattern METHOD_NAME = Pattern.compile("\\b(?:void|[A-Za-z0-9_<>]+)\\s+(\\w+)\\s*\\(");
    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bclass\\s+\\w+\\b");
    private static final Pattern SINGLETON_RESET_EXTENSION_ANNOTATION = Pattern.compile(
            "@ExtendWith\\s*\\(\\s*(?:\\{\\s*)?SingletonResetExtension\\.class");

    @Test
    void ambientGameplayModeSetupsDoNotGrowWithoutLifecycleTriage() throws IOException {
        Set<String> actual = Set.copyOf(ambientGameplayModeSetups());
        List<String> unexpected = actual.stream()
                .filter(entry -> !AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE.contains(entry))
                .sorted()
                .toList();
        List<String> stale = AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE.stream()
                .filter(entry -> !actual.contains(entry))
                .sorted()
                .toList();

        if (!unexpected.isEmpty() || !stale.isEmpty()) {
            List<String> sections = new ArrayList<>();
            if (!unexpected.isEmpty()) {
                sections.add("New ambient gameplay setup methods:\n" + String.join("\n", unexpected));
            }
            if (!stale.isEmpty()) {
                sections.add("Baseline entries no longer present:\n" + String.join("\n", stale));
            }
            fail("Test setup methods that open active gameplay mode should use TestEnvironment.resetAll(), "
                    + "configureGameModuleFixture(...), or configureRomFixture(...), unless explicitly triaged.\n"
                    + String.join("\n\n", sections));
        }
    }

    @Test
    void sampleScannerFlagsAmbientGameplayModeSetup() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestLeaky.java", """
                class TestLeaky {
                    @BeforeEach
                    void setUp() {
                        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
                        TestEnvironment.activeGameplayMode();
                    }
                }
                """);

        assertEquals(List.of("sample/TestLeaky.java#setUp"), violations);
    }

    @Test
    void sampleScannerAllowsCentralResetFixture() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestSafe.java", """
                class TestSafe {
                    @BeforeEach
                    void setUp() {
                        TestEnvironment.resetAll();
                    }
                }
                """);

        assertEquals(List.of(), violations);
    }

    @Test
    void sampleScannerAllowsNamedGameModuleFixture() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestSafe.java", """
                class TestSafe {
                    @BeforeEach
                    void setUp() {
                        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
                    }
                }
                """);

        assertEquals(List.of(), violations);
    }

    @Test
    void sampleScannerAllowsClassLevelSingletonResetExtension() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestSafe.java", """
                @ExtendWith(SingletonResetExtension.class)
                class TestSafe {
                    @BeforeEach
                    void setUp() {
                        TestEnvironment.activeGameplayMode();
                    }
                }
                """);

        assertEquals(List.of(), violations);
    }

    @Test
    void sampleScannerDoesNotLetCommentedExtensionSuppressLeakySetup() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestLeaky.java", """
                @ExtendWith(OtherExtension.class)
                class TestLeaky {
                    // SingletonResetExtension.class belongs in this file eventually.
                    @BeforeEach
                    void setUp() {
                        TestEnvironment.activeGameplayMode();
                    }
                }
                """);

        assertEquals(List.of("sample/TestLeaky.java#setUp"), violations);
    }

    @Test
    void sampleScannerScopesSingletonResetExtensionToAnnotatedClass() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestMixed.java", """
                @ExtendWith(SingletonResetExtension.class)
                class TestSafe {
                    @BeforeEach
                    void setUp() {
                        TestEnvironment.activeGameplayMode();
                    }
                }

                class TestLeaky {
                    @BeforeEach
                    void setUp() {
                        TestEnvironment.activeGameplayMode();
                    }
                }
                """);

        assertEquals(List.of("sample/TestMixed.java#setUp"), violations);
    }

    @Test
    void sampleScannerFlagsBootstrapSingletonSetupWithoutActiveGameplayCall() {
        List<String> violations = scanAmbientGameplayModeSetups("sample/TestBootstrapLeaky.java", """
                class TestBootstrapLeaky {
                    @BeforeEach
                    void setUp() {
                        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
                    }
                }
                """);

        assertEquals(List.of("sample/TestBootstrapLeaky.java#setUp"), violations);
    }

    private static List<String> ambientGameplayModeSetups() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(TEST_ROOT)) {
            String normalized = normalize(source);
            if (SCANNER_UTILITY_FILES.contains(normalized)) {
                continue;
            }
            violations.addAll(scanAmbientGameplayModeSetups(normalized, Files.readString(source)));
        }
        return violations.stream().sorted().toList();
    }

    private static List<String> scanAmbientGameplayModeSetups(String relative, String source) {
        List<String> violations = new ArrayList<>();
        for (SetupMethod method : setupMethods(source)) {
            String body = method.body();
            if (!opensAmbientGameplayMode(body)) {
                continue;
            }
            if (method.resetExtensionScoped()) {
                continue;
            }
            if (usesApprovedLifecycleFixture(body)) {
                continue;
            }
            violations.add(relative + "#" + method.name());
        }
        return violations;
    }

    private static boolean opensAmbientGameplayMode(String body) {
        return body.contains("TestEnvironment.activeGameplayMode()")
                || body.contains("EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap())");
    }

    private static boolean usesApprovedLifecycleFixture(String body) {
        return body.contains("TestEnvironment.resetAll(")
                || body.contains("TestEnvironment.configureGameModuleFixture(")
                || body.contains("TestEnvironment.configureRomFixture(")
                || body.contains("TestEnvironment.resetPerTest(");
    }

    private static List<SetupMethod> setupMethods(String source) {
        List<String> lines = source.lines().toList();
        List<SetupMethod> methods = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("@BeforeEach") && !trimmed.startsWith("@BeforeAll")) {
                continue;
            }
            int signatureLine = findMethodSignature(lines, i + 1);
            if (signatureLine < 0) {
                continue;
            }
            Matcher name = METHOD_NAME.matcher(lines.get(signatureLine));
            if (!name.find()) {
                continue;
            }
            int endLine = findMethodEnd(lines, signatureLine);
            if (endLine < signatureLine) {
                continue;
            }
            methods.add(new SetupMethod(
                    name.group(1),
                    String.join("\n", lines.subList(signatureLine, endLine + 1)),
                    isSingletonResetExtensionScoped(lines, i, signatureLine)));
            i = endLine;
        }
        return methods;
    }

    private static boolean isSingletonResetExtensionScoped(List<String> lines, int beforeAnnotationLine, int signatureLine) {
        return hasSingletonResetExtensionInAnnotationBlock(lines, beforeAnnotationLine, signatureLine)
                || hasSingletonResetExtensionOnEnclosingClass(lines, beforeAnnotationLine);
    }

    private static boolean hasSingletonResetExtensionOnEnclosingClass(List<String> lines, int beforeMethodLine) {
        for (int i = beforeMethodLine; i >= 0; i--) {
            if (CLASS_DECLARATION.matcher(lines.get(i)).find()) {
                return hasSingletonResetExtensionInAnnotationBlock(lines, i, i);
            }
        }
        return false;
    }

    private static boolean hasSingletonResetExtensionInAnnotationBlock(List<String> lines, int declarationLine, int endLine) {
        StringBuilder block = new StringBuilder();
        int start = declarationLine;
        for (int i = declarationLine - 1; i >= 0; i--) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("@")) {
                start = i;
                continue;
            }
            break;
        }
        for (int i = start; i <= endLine && i < lines.size(); i++) {
            block.append(stripStringsAndLineComment(lines.get(i))).append('\n');
        }
        return SINGLETON_RESET_EXTENSION_ANNOTATION.matcher(block).find();
    }

    private static int findMethodSignature(List<String> lines, int start) {
        for (int i = start; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.startsWith("@")) {
                continue;
            }
            if (trimmed.contains("(") && trimmed.contains(")")) {
                return i;
            }
        }
        return -1;
    }

    private static int findMethodEnd(List<String> lines, int signatureLine) {
        int depth = 0;
        boolean started = false;
        for (int i = signatureLine; i < lines.size(); i++) {
            String line = stripStringsAndLineComment(lines.get(i));
            for (int j = 0; j < line.length(); j++) {
                char c = line.charAt(j);
                if (c == '{') {
                    depth++;
                    started = true;
                } else if (c == '}') {
                    depth--;
                    if (started && depth <= 0) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private static String stripStringsAndLineComment(String line) {
        StringBuilder stripped = new StringBuilder(line.length());
        boolean inString = false;
        boolean inChar = false;
        boolean escaping = false;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
            if (!inString && !inChar && current == '/' && next == '/') {
                break;
            }
            if (inString) {
                stripped.append(' ');
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '"') {
                    inString = false;
                }
                continue;
            }
            if (inChar) {
                stripped.append(' ');
                if (escaping) {
                    escaping = false;
                } else if (current == '\\') {
                    escaping = true;
                } else if (current == '\'') {
                    inChar = false;
                }
                continue;
            }
            if (current == '"') {
                inString = true;
                stripped.append(' ');
            } else if (current == '\'') {
                inChar = true;
                stripped.append(' ');
            } else {
                stripped.append(current);
            }
        }
        return stripped.toString();
    }

    private static List<Path> javaSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(TestSingletonLifecycleGuard::normalize))
                    .toList();
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static Set<String> baseline(String entries) {
        return entries.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private record SetupMethod(String name, String body, boolean resetExtensionScoped) {
    }
}
