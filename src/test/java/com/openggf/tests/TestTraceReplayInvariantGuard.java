package com.openggf.tests;

import com.openggf.game.rules.GameRules;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.Severity;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceBinder;
import com.openggf.trace.TraceCharacterState;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class TestTraceReplayInvariantGuard {
    private static final Path MAIN_ROOT = Path.of("src/main/java");
    private static final Path TEST_ROOT = Path.of("src/test/java");
    private static final String SELF_PATH =
            "src/test/java/com/openggf/tests/TestTraceReplayInvariantGuard.java";

    private static final Set<String> TRACE_REPLAY_BASE_ALLOWLIST = Set.of(
            "src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java",
            "src/test/java/com/openggf/tests/trace/AbstractCreditsDemoTraceReplayTest.java"
    );

    private static final Set<String> FORBIDDEN_PARSER_DEPENDENCIES = Set.of(
            "com.openggf.game.GameServices",
            "com.openggf.level.objects.ObjectManager",
            "com.openggf.level.objects.AbstractObjectInstance",
            "com.openggf.level.objects.ObjectServices",
            "com.openggf.sprites.",
            "com.openggf.level.MutableLevel",
            "com.openggf.game.mutation.",
            "com.openggf.level.mutation."
    );

    private static final Set<String> PARSER_DEPENDENCY_BASELINE = Set.of(
            "src/main/java/com/openggf/trace/TraceCharacterState.java depends on com.openggf.game.GameServices",
            "src/main/java/com/openggf/trace/TraceCharacterState.java depends on com.openggf.level.objects.AbstractObjectInstance",
            "src/main/java/com/openggf/trace/TraceCharacterState.java depends on com.openggf.level.objects.ObjectManager",
            "src/main/java/com/openggf/trace/TraceCharacterState.java depends on com.openggf.sprites.",
            "src/main/java/com/openggf/trace/TraceEvent.java depends on com.openggf.sprites."
    );

    @Test
    void traceReplayCodeDoesNotWriteRecordedStateBackIntoEngine()
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : replaySources()) {
            List<String> lines = Files.readAllLines(source);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (isForbiddenTraceHydration(source, line)) {
                    violations.add(source + ":" + (i + 1) + " - " + line.trim());
                }
            }
        }

        if (!violations.isEmpty()) {
            fail("Trace replay must compare trace rows, not write them "
                    + "back into engine state:\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void defaultTraceReplayToleranceIsStrict() {
        ToleranceConfig defaults = ToleranceConfig.DEFAULT;

        assertEquals(1, defaults.positionError(), "position delta of one must be an error");
        assertEquals(1, defaults.speedError(), "speed delta of one must be an error");
        assertEquals(1, defaults.angleError(), "angle delta of one must be an error");
        assertEquals(1, defaults.cameraError(), "camera delta of one must be an error");
        assertEquals(ToleranceConfig.RingCountMode.FORCE_ERROR, defaults.ringCountMode(),
                "ring count mismatch must default to error; opt into WARN_ONLY explicitly");
    }

    @Test
    void s2ControlLockLogicalLatchIsEnabledWithForcedInputBypass() {
        assertTrue(GameRules.SONIC_2.playerMovement().controlLockLatchesLogicalInput(),
                "S2 Obj01_Control latches Ctrl_1_Logical while Control_Locked; "
                        + "forced-input writers bypass the latch for signpost/auto-walk scripts.");
        assertTrue(GameRules.SONIC_3K.playerMovement().controlLockLatchesLogicalInput(),
                "S3K keeps the ROM Ctrl_1_locked logical-input latch enabled.");
    }

    @Test
    void traceReplayBootstrapDoesNotEnableRecordedFrameZeroStateSeeding() {
        assertFalse(TraceReplayBootstrap.shouldUseTraceStartBootstrapForTraceReplay(null),
                "Trace-start state bootstrap must stay disabled by default.");
        assertFalse(TraceReplayBootstrap.shouldSeedFrameZeroForTraceReplay(null),
                "Frame-zero trace rows are comparison data, not engine seed data.");
        assertFalse(TraceReplayBootstrap.shouldSeedReplayStartStateForTraceReplay(null, 0),
                "Replay-start trace rows are comparison data, not engine seed data.");
    }

    @Test
    void recordedSidekickFieldsAreStrictParityErrors() {
        TraceCharacterState expectedTails = traceCharacter(
                (short) 0x0050, (short) 0x0288, (short) 0x0010);
        TraceCharacterState actualTails = traceCharacter(
                (short) 0x0051, (short) 0x0288, (short) 0x0010);
        TraceFrame frame = new TraceFrame(0, 0,
                (short) 0x0060, (short) 0x0290,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                0, 0, -1, -1, -1, 0, 0,
                0, 0, 0, 0, expectedTails);

        TraceBinder binder = new TraceBinder(ToleranceConfig.DEFAULT);
        FrameComparison comparison = binder.compareFrame(frame,
                (short) 0x0060, (short) 0x0290,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                null, null, "tails", actualTails);

        assertEquals(Severity.ERROR, comparison.fields().get("tails_x").severity(),
                "Recorded sidekick position deltas must be release-blocking errors.");
    }

    @Test
    void traceReplayParityTestsDoNotDowngradeRingMismatchesToWarnings() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(Path.of("src/test/java/com/openggf/tests/trace"))) {
            String normalized = normalize(source);
            if (normalized.equals("src/test/java/com/openggf/tests/trace/TestTraceBinder.java")) {
                continue;
            }
            String text = Files.readString(source);
            if (text.contains("RingCountMode.WARN_ONLY")) {
                violations.add(normalized);
            }
        }

        if (!violations.isEmpty()) {
            fail("Release trace replay parity must treat ring-count mismatches as errors; "
                    + "WARN_ONLY is reserved for TraceBinder unit coverage:\n"
                    + String.join("\n", violations));
        }
    }

    @Test
    void s3kTraceReplayValidatesBk2InputAlignment() throws IOException {
        String text = Files.readString(Path.of(
                "src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java"));
        String method = methodBody(text, "private boolean replayS3kTrace(");

        assertTrue(method.contains("binder.validateInput(driveFrame, bk2Input)"),
                "S3K trace replay must validate BK2 input against each trace row "
                        + "before comparing engine state.");
    }

    @Test
    void s3kTraceReplayKeepsRingDiagnosticsRowStrictBeforeComparison() throws IOException {
        String text = Files.readString(Path.of(
                "src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java"));
        String method = methodBody(text, "private boolean replayS3kTrace(");

        assertTrue(method.contains("s3kFrameForGameplayComparison("),
                "S3K trace replay must normalize split-row camera sampling explicitly.");
        assertFalse(method.contains("s3kFrameForRingDiagnosticComparison("),
                "S3K trace replay must not substitute next-row ring counts.");
        assertFalse(Files.readString(Path.of("src/main/java/com/openggf/trace/TraceFrame.java"))
                        .contains("withRingDiagnosticsFrom"),
                "TraceFrame must not expose a helper that rewrites only the expected ring count.");
    }

    @Test
    void traceParserDataAndCatalogStayIndependentOfEngineRuntime()
            throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : traceParserDataAndCatalogSources()) {
            String text = Files.readString(source);
            for (String forbidden : FORBIDDEN_PARSER_DEPENDENCIES) {
                if (text.contains(forbidden)) {
                    violations.add(normalize(source) + " depends on " + forbidden);
                }
            }
        }

        List<String> unexpected = new ArrayList<>(violations);
        unexpected.removeAll(PARSER_DEPENDENCY_BASELINE);
        List<String> stale = new ArrayList<>(PARSER_DEPENDENCY_BASELINE);
        stale.removeAll(violations);
        if (!unexpected.isEmpty() || !stale.isEmpty()) {
            List<String> sections = new ArrayList<>();
            if (!unexpected.isEmpty()) {
                sections.add("New parser/data/catalog runtime dependencies:\n"
                        + String.join("\n", unexpected));
            }
            if (!stale.isEmpty()) {
                sections.add("Baseline dependencies no longer present:\n"
                        + String.join("\n", stale));
            }
            fail("Trace parser/data/catalog classes should stay read-only and engine-independent.\n"
                    + String.join("\n\n", sections));
        }
    }

    @Test
    void concreteTraceReplayTestsUseSharedReplayBaseClass() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaSources(Path.of("src/test/java/com/openggf/tests/trace"))) {
            String normalized = normalize(source);
            if (TRACE_REPLAY_BASE_ALLOWLIST.contains(normalized)) {
                continue;
            }
            String fileName = source.getFileName().toString();
            if (!fileName.startsWith("Test") || !fileName.endsWith("TraceReplay.java")) {
                continue;
            }
            String text = Files.readString(source);
            if (!text.contains("package com.openggf.tests.trace")) {
                continue;
            }
            if (!text.contains("extends AbstractTraceReplayTest")
                    && !text.contains("extends AbstractCreditsDemoTraceReplayTest")
                    && !text.contains("extends AbstractS2LevelSelectTraceReplayTest")
                    && !text.contains("extends AbstractS2SpecialStageTraceReplayTest")
                    && !text.contains("extends AbstractS3kBonusStageTraceReplayTest")
                    // AbstractS3kSpecialStageTraceReplayTest is the S3K analogue of
                    // AbstractS2SpecialStageTraceReplayTest above: the SS provider is a
                    // standalone MiniGameProvider (no level pipeline), so it intentionally
                    // does not extend AbstractTraceReplayTest.
                    && !text.contains("extends AbstractS3kSpecialStageTraceReplayTest")
                    // AbstractS1SpecialStageTraceReplayTest is the S1 maze analogue of the
                    // two SS bases above (s1_special_stage profile, provider stepped
                    // directly, no level pipeline) — same intentional non-extension.
                    && !text.contains("extends AbstractS1SpecialStageTraceReplayTest")) {
                violations.add(normalized);
            }
        }

        if (!violations.isEmpty()) {
            fail("*TraceReplay tests under com.openggf.tests.trace.. must use the shared replay base:\n"
                    + String.join("\n", violations));
        }
    }

    private static List<Path> replaySources() throws IOException {
        Set<Path> sources = new HashSet<>();
        sources.addAll(javaSources(Path.of("src/main/java/com/openggf/trace")));
        sources.addAll(javaSources(Path.of("src/test/java/com/openggf/tests/trace")));
        sources.add(Path.of("src/main/java/com/openggf/TraceSessionLauncher.java"));
        sources.addAll(traceConsumersOutsideCurrentRoots());
        sources.add(Path.of("src/main/java/com/openggf/sprites/playable/SidekickCpuController.java"));
        sources.remove(Path.of(SELF_PATH));
        return sources.stream()
                .filter(Files::exists)
                .filter(path -> !isAllowedTraceSupportSource(path))
                .sorted(Comparator.comparing(TestTraceReplayInvariantGuard::normalize))
                .toList();
    }

    private static TraceCharacterState traceCharacter(short x, short y, short xSpeed) {
        return new TraceCharacterState(true, x, y, xSpeed,
                (short) 0, (short) 0, (byte) 0,
                false, false, 0, 0, 0, 2, 0, 0);
    }

    private static boolean isAllowedTraceSupportSource(Path source) {
        String normalized = normalize(source);
        return normalized.endsWith("src/main/java/com/openggf/sprites/ghost/GhostTraceRenderer.java")
                || normalized.endsWith("src/test/java/com/openggf/tests/HeadlessTestFixture.java")
                // Unit test that builds SYNTHETIC leader position history for
                // respawn fixtures; it only imports com.openggf.trace for
                // TraceCharacterState.statusByteFromSprite and never hydrates
                // engine state from recorded trace rows.
                || normalized.endsWith("src/test/java/com/openggf/sprites/playable/TestRespawnStrategies.java");
    }

    /**
     * Catches setter calls whose argument expression reads from a trace
     * snapshot/frame field. The pattern below matches a leading dot then
     * a setter, then anywhere inside the parenthesised argument list one of
     * the well-known trace-side identifiers ({@code state}, {@code frame},
     * {@code snapshot}, {@code sn}, {@code fields.get}). This is a conservative
     * heuristic but it would have caught
     * {@code applyFrameZeroPlayerSnapshot}'s
     * {@code player.setControlLocked(controlLocked)} chain because each value
     * is parsed via {@code parseBoolean(fields.get(...))} earlier in the same
     * method, and the fields-derived locals are detected via the dedicated
     * {@code fields.get(} check below.
     */
    private static final Pattern SETTER_FROM_TRACE_FIELD = Pattern.compile(
            "\\.set[A-Z]\\w*\\([^)]*\\b(state|frame|snapshot|sn)\\.\\w+");

    private static boolean isForbiddenTraceHydration(Path source, String line) {
        if (isVisualTraceGhostHydration(source)) {
            return false;
        }
        String trimmed = line.trim();
        if (isAllowedTraceBootstrapLine(trimmed)) {
            return false;
        }
        if (isTraceCaptureToolRecordingAdapter(source, trimmed)) {
            return false;
        }
        return trimmed.contains("applyRecordedFirstSidekickState(")
                || line.contains("applyRecordedFrameState(")
                || line.contains("applySeededFirstSidekickState(")
                || line.contains("applySidekickFollowDelayOverride(")
                || line.contains("applyFrameZeroPlayerSnapshot(")
                || line.contains("applyCustomRadii(")
                || line.contains("hydrateFromRomCpuStatePerFrame(")
                || line.contains("hydrateRecordedHistory(")
                || line.contains("sidekickFollowDelayOverrideForTraceReplay(")
                || line.contains("setTraceReplayFollowDelayFrames(")
                || line.contains("traceReplayFollowDelayFrames")
                || line.contains("S3kElasticWindowController")
                || line.contains(".advanceRecordingCursor(")
                || line.contains(".isStrictComparisonEnabled()")
                || line.contains(".strictTraceIndex()")
                || line.contains(".driveTraceIndex()")
                || line.contains(".hydrateFromRomSnapshot(")
                || line.contains("hydrateFromRomSnapshot(")
                || setterHydratesPrimaryPlayerState(line)
                || setterHydratesCameraState(line)
                || setterHydratesRingState(line)
                || line.contains("setCentreX(state.")
                || line.contains("setCentreY(state.")
                || line.contains("setXSpeed(state.")
                || line.contains("setYSpeed(state.")
                || line.contains("setGSpeed(state.")
                || line.contains("setCentreX(frame.")
                || line.contains("setCentreY(frame.")
                || line.contains("setXSpeed(frame.")
                || line.contains("setYSpeed(frame.")
                || line.contains("setGSpeed(frame.")
                || line.contains("setCentreX((short) snapshot.xPos())")
                || line.contains("setCentreY((short) snapshot.yPos())")
                || line.contains("setXSpeed((short) snapshot.xVel())")
                || line.contains("setYSpeed((short) snapshot.yVel())")
                || line.contains("fields.get(\"")
                || line.contains("frameZero != null && frameZero.")
                || line.contains("recordedRings = frameZero")
                || line.contains("recordedCamera")
                || SETTER_FROM_TRACE_FIELD.matcher(line).find();
    }

    private static boolean isVisualTraceGhostHydration(Path source) {
        return normalize(source).equals("src/main/java/com/openggf/sprites/ghost/GhostTraceRenderer.java");
    }

    /**
     * The headless CLI tools' {@code TraceReplayFixture} view forwards the
     * recording-cursor call straight to the driver. It advances a cursor over
     * recorded <em>input</em>, which is what a replay is supposed to consume; it
     * writes no recorded engine state anywhere.
     */
    private static boolean isTraceCaptureToolRecordingAdapter(Path source, String trimmed) {
        String normalized = normalize(source);
        return (normalized.equals("src/main/java/com/openggf/tools/TraceReplayDrive.java")
                || normalized.equals(
                        "src/test/java/com/openggf/tests/HeadlessTestRunner.java"))
                && trimmed.equals("driver.advanceRecordingCursor(frameCount);");
    }

    private static boolean isAllowedTraceBootstrapLine(String trimmed) {
        return trimmed.startsWith("//")
                || trimmed.startsWith("*")
                || trimmed.contains("public void advanceRecordingCursor(")
                || trimmed.contains("TraceReplayBootstrap.initialVblankCounterForTraceReplay(trace)")
                || trimmed.contains("TraceReplayBootstrap.alignFrameCountersForReplayStart(")
                || trimmed.contains("GameServices.spritesOrNull().setFrameCounter(")
                || trimmed.contains("GameServices.levelOrNull().setFrameCounter(")
                || trimmed.contains("sprite.setCentreX(meta.startX())")
                || trimmed.contains("sprite.setCentreY(meta.startY())");
    }

    private static boolean setterHydratesPrimaryPlayerState(String line) {
        return traceSetter(line, "setCentreX")
                || traceSetter(line, "setCentreY")
                || traceSetter(line, "setXSpeed")
                || traceSetter(line, "setYSpeed")
                || traceSetter(line, "setGSpeed")
                || traceSetter(line, "setXSubpixelRaw")
                || traceSetter(line, "setYSubpixelRaw")
                || traceSetter(line, "setAir")
                || traceSetter(line, "setRolling")
                || traceSetter(line, "setGroundMode")
                || traceSetter(line, "setAngle");
    }

    private static boolean setterHydratesCameraState(String line) {
        return traceSetter(line, "setX")
                || traceSetter(line, "setY")
                || traceSetter(line, "setPosition")
                || traceSetter(line, "setCameraX")
                || traceSetter(line, "setCameraY");
    }

    private static boolean setterHydratesRingState(String line) {
        return traceSetter(line, "setRingCount")
                || traceSetter(line, "setRings")
                || traceSetter(line, "setCollectedRing")
                || traceSetter(line, "setRing");
    }

    private static boolean traceSetter(String line, String method) {
        int call = line.indexOf("." + method + "(");
        if (call < 0) {
            return false;
        }
        int start = line.indexOf('(', call);
        int end = line.indexOf(')', start + 1);
        String args = end > start ? line.substring(start + 1, end) : line.substring(start + 1);
        return readsTraceState(args);
    }

    private static boolean readsTraceState(String expression) {
        return expression.contains("state.")
                || expression.contains("frame.")
                || expression.contains("frameZero.")
                || expression.contains("recordedSidekick.")
                || expression.contains("snapshot.")
                || expression.contains("aux.")
                || expression.contains("fields.get(")
                || expression.contains("TraceFrame")
                || expression.contains("TraceEvent");
    }

    private static List<Path> traceConsumersOutsideCurrentRoots() throws IOException {
        List<Path> consumers = new ArrayList<>();
        for (Path root : List.of(MAIN_ROOT, TEST_ROOT)) {
            for (Path source : javaSources(root)) {
                String normalized = normalize(source);
                if (normalized.startsWith("src/main/java/com/openggf/trace/")
                        || normalized.startsWith("src/test/java/com/openggf/tests/trace/")
                        || SELF_PATH.equals(normalized)) {
                    continue;
                }
                String text = Files.readString(source);
                if (importsTraceReplayData(text)) {
                    consumers.add(source);
                }
            }
        }
        return consumers;
    }

    private static boolean importsTraceReplayData(String text) {
        return text.contains("import com.openggf.trace.*;")
                || text.contains("import com.openggf.trace.")
                || text.contains("com.openggf.trace.")
                || text.contains("import com.openggf.trace.TraceData;")
                || text.contains("import com.openggf.trace.TraceFrame;")
                || text.contains("import com.openggf.trace.TraceEvent;")
                || text.contains("import com.openggf.trace.TraceReplayBootstrap;");
    }

    private static List<Path> traceParserDataAndCatalogSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path source : javaSources(Path.of("src/main/java/com/openggf/trace"))) {
            String normalized = normalize(source);
            if (normalized.startsWith("src/main/java/com/openggf/trace/live/")
                    || normalized.startsWith("src/main/java/com/openggf/trace/replay/")
                    || normalized.equals("src/main/java/com/openggf/trace/TraceReplayBootstrap.java")) {
                continue;
            }
            sources.add(source);
        }
        return sources;
    }

    private static String methodBody(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start >= 0, "Missing method signature: " + signature);
        int brace = text.indexOf('{', start);
        assertTrue(brace >= 0, "Missing method body: " + signature);
        int depth = 0;
        for (int i = brace; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    return text.substring(brace + 1, i);
                }
            }
        }
        fail("Unclosed method body: " + signature);
        return "";
    }

    private static List<Path> javaSources(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(path -> path.toString().endsWith(".java"))
                    .sorted(Comparator.comparing(TestTraceReplayInvariantGuard::normalize))
                    .toList();
        }
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
