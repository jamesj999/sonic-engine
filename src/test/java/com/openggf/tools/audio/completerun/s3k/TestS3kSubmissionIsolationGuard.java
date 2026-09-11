package com.openggf.tools.audio.completerun.s3k;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestS3kSubmissionIsolationGuard {
    private static final String ADAPTER =
            "com.openggf.tools.audio.completerun.s3k."
                    + "S3kCompleteRunReferenceRawAdapter";
    private static final String PROJECTOR =
            "com.openggf.tools.audio.completerun.s3k."
                    + "S3kCompleteRunReferenceProjector";
    private static final String RAW_SUBMISSION = ADAPTER + "$RawSubmission";
    private static final String UNBOUND_AUTHORITY = "UNBOUND_TEST_ONLY";
    private static final String SUBMISSION_SCHEMA =
            "openggf.s3k-complete-run-audio-raw.v2";

    @TempDir
    Path temporaryDirectory;

    @Test
    void productionOmitsTheUnboundSubmissionExperiment() throws IOException {
        JavaClasses production = new ClassFileImporter()
                .importPath(Path.of("target/classes"));

        assertEquals(List.of(), experimentalBytecodeIdentifiers(production),
                "the deferred S3K raw-v2 Java experiment must stay out of production"
                        + " bytecode until authenticated capture and binding review");
        assertEquals(List.of(), experimentalSourceLiterals(
                        Path.of("src/main/java")),
                "the exact unbound authority/schema literals must stay out of production"
                        + " Java sources");
    }

    @Test
    void guardRejectsExactExperimentalIdentifiersUnderProductionClasses()
            throws IOException {
        Mutation mutation = compileProductionMutation();

        assertEquals(List.of(
                PROJECTOR + "#SUBMISSION_HOOKS",
                PROJECTOR + "#projectSubmissionV2PrefixForTesting(java.nio.file.Path,"
                        + "java.nio.file.Path)",
                ADAPTER + "#SUBMISSION_SCHEMA",
                ADAPTER + "#scanSubmissionV2PrefixForTesting(java.nio.file.Path,"
                        + ADAPTER + "$Sink)",
                RAW_SUBMISSION),
                experimentalBytecodeIdentifiers(mutation.classes()));
        assertEquals(List.of(
                mutation.adapterSource() + ":" + UNBOUND_AUTHORITY,
                mutation.adapterSource() + ":" + SUBMISSION_SCHEMA),
                experimentalSourceLiterals(mutation.sourceRoot()));
    }

    private static List<String> experimentalBytecodeIdentifiers(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        classes.stream().filter(type -> RAW_SUBMISSION.equals(type.getName()))
                .forEach(type -> violations.add(RAW_SUBMISSION));
        classes.stream().filter(type -> ADAPTER.equals(type.getName()))
                .findFirst().ifPresent(type -> inspectAdapter(type, violations));
        classes.stream().filter(type -> PROJECTOR.equals(type.getName()))
                .findFirst().ifPresent(type -> inspectProjector(type, violations));
        return violations.stream().sorted().toList();
    }

    private static void inspectAdapter(JavaClass type, List<String> violations) {
        type.getFields().stream()
                .filter(field -> "SUBMISSION_SCHEMA".equals(field.getName()))
                .forEach(field -> violations.add(ADAPTER + "#SUBMISSION_SCHEMA"));
        type.getMethods().stream()
                .filter(method -> "scanSubmissionV2PrefixForTesting".equals(
                        method.getName()))
                .filter(method -> rawParameterNames(method).equals(List.of(
                        Path.class.getName(), ADAPTER + "$Sink")))
                .forEach(method -> violations.add(ADAPTER
                        + "#scanSubmissionV2PrefixForTesting(java.nio.file.Path,"
                        + ADAPTER + "$Sink)"));
    }

    private static void inspectProjector(JavaClass type, List<String> violations) {
        type.getFields().stream()
                .filter(field -> "SUBMISSION_HOOKS".equals(field.getName()))
                .forEach(field -> violations.add(PROJECTOR + "#SUBMISSION_HOOKS"));
        type.getMethods().stream()
                .filter(method -> "projectSubmissionV2PrefixForTesting".equals(
                        method.getName()))
                .filter(method -> rawParameterNames(method).equals(List.of(
                        Path.class.getName(), Path.class.getName())))
                .forEach(method -> violations.add(PROJECTOR
                        + "#projectSubmissionV2PrefixForTesting(java.nio.file.Path,"
                        + "java.nio.file.Path)"));
    }

    private static List<String> rawParameterNames(
            com.tngtech.archunit.core.domain.JavaCodeUnit method) {
        return method.getRawParameterTypes().stream().map(JavaClass::getName).toList();
    }

    private static List<String> experimentalSourceLiterals(Path root) throws IOException {
        List<String> violations = new ArrayList<>();
        try (var sources = Files.walk(root)) {
            for (Path source : sources.filter(path -> path.toString().endsWith(".java"))
                    .sorted().toList()) {
                String text = Files.readString(source);
                if (text.contains(SUBMISSION_SCHEMA)) {
                    violations.add(source + ":" + SUBMISSION_SCHEMA);
                }
                if (text.contains(UNBOUND_AUTHORITY)) {
                    violations.add(source + ":" + UNBOUND_AUTHORITY);
                }
            }
        }
        return violations.stream().sorted().toList();
    }

    private Mutation compileProductionMutation() throws IOException {
        Path sourceRoot = temporaryDirectory.resolve("src/main/java");
        Path packageRoot = sourceRoot.resolve(
                "com/openggf/tools/audio/completerun/s3k");
        Path classes = temporaryDirectory.resolve("target/classes");
        Files.createDirectories(packageRoot);
        Files.createDirectories(classes);
        Path adapter = packageRoot.resolve("S3kCompleteRunReferenceRawAdapter.java");
        Path projector = packageRoot.resolve("S3kCompleteRunReferenceProjector.java");
        Files.writeString(adapter, """
                package com.openggf.tools.audio.completerun.s3k;

                import java.nio.file.Path;

                public final class S3kCompleteRunReferenceRawAdapter {
                    static final String SUBMISSION_SCHEMA =
                            "openggf.s3k-complete-run-audio-raw.v2";
                    static final String AUTHORITY = "UNBOUND_TEST_ONLY";
                    interface Sink { }
                    record RawSubmission(int request) { }
                    static void scanSubmissionV2PrefixForTesting(Path raw, Sink sink) { }
                }
                """);
        Files.writeString(projector, """
                package com.openggf.tools.audio.completerun.s3k;

                import java.nio.file.Path;

                public final class S3kCompleteRunReferenceProjector {
                    static final Object SUBMISSION_HOOKS = new Object();
                    Object projectSubmissionV2PrefixForTesting(Path raw, Path rom) {
                        return null;
                    }
                }
                """);
        int result = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                "--release", "21", "-d", classes.toString(),
                adapter.toString(), projector.toString());
        assertEquals(0, result, "could not compile production-shape v2 mutation");
        return new Mutation(sourceRoot, adapter,
                new ClassFileImporter().importPath(classes));
    }

    private record Mutation(Path sourceRoot, Path adapterSource,
            JavaClasses classes) { }
}
