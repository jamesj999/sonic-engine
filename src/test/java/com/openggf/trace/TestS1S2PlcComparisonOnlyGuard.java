package com.openggf.trace;

import com.openggf.game.resources.DynamicArtDiagnosticsProvider;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.timing.HardwareWorkKind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps S1/S2 PLC readiness native: trace records may compare it, but never schedule it.
 */
class TestS1S2PlcComparisonOnlyGuard {
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path TRACE_REPLAY_TESTS = Path.of("src", "test", "java", "com", "openggf", "tests", "trace");
    private static final List<String> PLC_SERVICES = List.of(
            "com.openggf.game.sonic1.resources.Sonic1PlcService",
            "com.openggf.game.sonic2.resources.Sonic2PlcService");
    private static final Pattern TRACE_IMPORT = Pattern.compile(
            "(?m)^import\\s+(?:static\\s+)?com\\.openggf\\.trace(?:\\.[\\w*]+)*\\s*;");
    private static final Pattern PLC_IMPORT = Pattern.compile(
            "(?m)^import\\s+(?:static\\s+)?com\\.openggf\\.game\\.sonic[12]\\.resources\\.Sonic[12]PlcService(?:\\.[\\w*]+)?\\s*;");
    private static final Pattern PLC_FQ_REFERENCE = Pattern.compile(
            "com\\.openggf\\.game\\.sonic[12]\\.resources\\.Sonic[12]PlcService");
    private static final Pattern DYNAMIC_ART_MUTATOR_REFERENCE = Pattern.compile(
            "(?:import\\s+com\\.openggf\\.game\\.resources\\."
                    + "DynamicArtLifecycleService|"
                    + "com\\.openggf\\.game\\.resources\\."
                    + "DynamicArtLifecycleService)");
    private static final Pattern DYNAMIC_ART_MUTATION_CALL = Pattern.compile(
            "\\.\\s*(?:seedDynamicArtFromTrace|submitDynamicArtFromTrace|"
                    + "submitRecordedTransfer|"
                    + "beginRun|finishRun|openComparisonSegment|"
                    + "closeComparisonSegment|serviceProductionVBlank|"
                    + "finishProductionIteration|publishRow|publishTerminal|"
                    + "observe(?:Player|Rom|Ram)?Dplc|"
                    + "complete(?:PlayerDplc|Applied)|restore|"
                    + "resetForMissingSnapshot)\\s*\\(");

    @Test
    void nativePlcServicesDoNotDependOnTracePackages() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String service : PLC_SERVICES) {
            Path source = MAIN.resolve(service.replace('.', '/') + ".java");
            if (TRACE_IMPORT.matcher(Files.readString(source)).find()) {
                violations.add(service + " imports com.openggf.trace");
            }
        }
        assertNoViolations("native PLC services must not depend on trace data", violations);
    }

    @Test
    void traceProductionSourcesDoNotDependOnNativePlcServices() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path source : javaFiles(MAIN.resolve("com/openggf/trace"))) {
            String text = Files.readString(source);
            String relative = MAIN.relativize(source).toString().replace('\\', '/');
            if (PLC_IMPORT.matcher(text).find() || PLC_FQ_REFERENCE.matcher(text).find()) {
                violations.add(relative + " depends on an S1/S2 PLC service");
            }
        }
        assertNoViolations("trace production sources must not control native PLC readiness", violations);
    }

    /**
     * The registry is closed, but no longer to S3K alone. The hardware-timing
     * exception is cross-game: recorded timing may drive a delay in the art
     * pipelines of all three games -- S1 PLC, S2 DPLC and S3K Kosinski queues
     * (CLAUDE.md hard rule 4, and
     * docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md).
     * What stays forbidden is a kind that carries PLC <em>payload</em>: S1's
     * entry releases only the {@code RunPLC} arming edge, and every pattern it
     * later decompresses is still produced natively, which is what the
     * structural assertions in this class check.
     */
    @Test
    void timingKindRegistryIsClosedToUndeclaredWork() {
        var admittedKinds = Arrays.stream(HardwareWorkKind.values())
                .map(Enum::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (!admittedKinds.equals(Set.of(
                "KOS_MODULE_QUEUE", "KOS_DECOMPRESSION_QUEUE", "NEMESIS_PLC_QUEUE"))) {
            fail("hardware timing may admit only declared cross-game art work, but was "
                    + admittedKinds);
        }
    }

    @Test
    void replayAndBootstrapSourcesDoNotReferenceNativePlcServices() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> sources = new ArrayList<>();
        sources.addAll(javaFiles(MAIN.resolve("com/openggf/trace/replay")));
        sources.addAll(javaFiles(TRACE_REPLAY_TESTS));
        for (Path source : sources) {
            String text = Files.readString(source);
            if (PLC_IMPORT.matcher(text).find() || PLC_FQ_REFERENCE.matcher(text).find()) {
                violations.add(source.toString().replace('\\', '/')
                        + " references a native S1/S2 PLC service from replay/bootstrap code");
            }
        }
        assertNoViolations("trace replay/bootstrap must not reference native S1/S2 PLC services", violations);
    }

    @Test
    void traceAndGhostSourcesCannotReachDynamicArtMutation() throws IOException {
        List<String> violations = new ArrayList<>();
        List<Path> roots = List.of(
                MAIN.resolve("com/openggf/trace"),
                MAIN.resolve("com/openggf/sprites/ghost"));
        for (Path root : roots) {
            for (Path source : javaFiles(root)) {
                String text = Files.readString(source);
                if (!isDynamicArtMutationFree(text)) {
                    violations.add(MAIN.relativize(source).toString()
                            .replace('\\', '/'));
                }
            }
        }
        assertNoViolations(
                "trace and ghost production sources must use only read-only "
                        + "dynamic-art diagnostics", violations);
    }

    @Test
    void dynamicArtMutationGuardRejectsImportQualifiedAndCallBypasses() {
        assertFalse(isDynamicArtMutationFree("""
                import com.openggf.game.resources.DynamicArtLifecycleService;
                class Comparator {}
                """));
        assertFalse(isDynamicArtMutationFree("""
                class Comparator {
                    com.openggf.game.resources.DynamicArtLifecycleService owner;
                }
                """));
        assertFalse(isDynamicArtMutationFree("""
                class Comparator {
                    void compare(Object owner) {
                        owner.openComparisonSegment();
                    }
                }
                """));
        assertFalse(isDynamicArtMutationFree("""
                class Ghost {
                    void draw(Object owner) {
                        owner.completeApplied(null);
                    }
                }
                """));
        assertFalse(isDynamicArtMutationFree("""
                class Comparator {
                    void compare(Object owner) {
                        owner.seedDynamicArtFromTrace(null);
                    }
                }
                """));
        assertFalse(isDynamicArtMutationFree("""
                class Comparator {
                    void compare(Object owner) {
                        owner.submitRecordedTransfer(null);
                    }
                }
                """));
    }

    @Test
    void diagnosticsSurfaceHasNoCapableParametersOrTraceReturns() {
        for (var method : DynamicArtDiagnosticsProvider.class
                .getDeclaredMethods()) {
            assertEquals(0, method.getParameterCount(),
                    "diagnostics provider must not retain callbacks, expected "
                            + "trace data, or production lifecycle owners");
            assertFalse(method.getReturnType().getName()
                    .startsWith("com.openggf.trace"),
                    "diagnostics provider must not return trace authority");
        }
        for (var field : DynamicArtLifecycleService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName()
                            .startsWith("java.util.function"),
                    "lifecycle service must not retain a capable callback: "
                            + field.getName());
            assertFalse(field.getType().getName()
                            .startsWith("com.openggf.trace"),
                    "lifecycle service must not retain trace authority: "
                            + field.getName());
        }
    }

    private static boolean isDynamicArtMutationFree(String source) {
        return !DYNAMIC_ART_MUTATOR_REFERENCE.matcher(source).find()
                && !DYNAMIC_ART_MUTATION_CALL.matcher(source).find();
    }

    private static List<Path> javaFiles(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static void assertNoViolations(String rule, List<String> violations) {
        if (!violations.isEmpty()) {
            fail(rule + ":\n  " + String.join("\n  ", violations));
        }
    }
}
