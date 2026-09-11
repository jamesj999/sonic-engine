package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests must clean up the temporary files they create.
 *
 * <p>They did not, for a long time: {@code Files.createTempDirectory("...")}
 * and {@code Files.createTempFile("...", "...")} put a directory or file in
 * {@code java.io.tmpdir} that nothing ever removed. Across a full run that left
 * roughly 1,800 directories behind, and on a machine where the system temp
 * directory is a RAM-backed tmpfs they accumulated across runs until the
 * filesystem was full. That then broke unrelated features that legitimately
 * need temporary space — live recording's ffmpeg process died with
 * {@code No space left on device} partway through a capture.
 *
 * <p>Surefire now points {@code java.io.tmpdir} at {@code target/test-tmp}, so
 * the blast radius is contained and {@code mvn clean} clears it. That is a
 * backstop, not a licence: this guard keeps the tests themselves tidy.
 *
 * <p>Use JUnit's {@code @TempDir} by preference — it is per-test and needs no
 * bookkeeping. Where the injection point does not exist (a static helper, or
 * several independent roots in one method), use {@link TestTempFiles}.
 *
 * <p>The overloads that take an existing directory
 * ({@code Files.createTempFile(dir, prefix, suffix)}) are not flagged: those
 * write where the caller already controls the lifetime.
 */
class TestNoLeakedTemporaryFiles {

    /** Matches only the {@code java.io.tmpdir}-rooted overloads. */
    private static final Pattern LEAKING_CALL = Pattern.compile(
            "\\bFiles\\.createTemp(?:Directory|File)\\(\\s*\"|\\bFile\\.createTempFile\\(");

    /**
     * Files that may still call through. Every entry is a leak that has not
     * been converted yet, not an approved pattern — shrink this list, do not
     * grow it. A new test belongs on {@code @TempDir} or {@link TestTempFiles}.
     */
    private static final Set<String> BASELINE = Set.of(
            // Writes a WAV beside a reference fixture for manual comparison on
            // failure; deleting it would defeat the diagnostic.
            "src/test/java/com/openggf/audio/AudioRegressionTest.java",
            // Same, for a rendered PNG. Excluded from surefire (needs a display).
            "src/test/java/com/openggf/graphics/VisualRegressionTest.java",
            // Documents the leak this guard exists to prevent, and asserts on
            // the directories the old code created.
            "src/test/java/com/openggf/TestInputBindingFactoryTempDirectory.java",
            // The helper itself.
            "src/test/java/com/openggf/tests/TestTempFiles.java",
            // This guard.
            "src/test/java/com/openggf/tests/TestNoLeakedTemporaryFiles.java");

    @Test
    void testsDoNotCreateTemporaryFilesTheyNeverRemove() throws IOException {
        Path testRoot = Paths.get(System.getProperty("project.basedir", "."))
                .resolve("src/test/java");
        assertTrue(Files.isDirectory(testRoot), "test sources not found at " + testRoot);

        List<String> offenders = new ArrayList<>();
        try (var sources = Files.walk(testRoot)) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = Paths.get(System.getProperty("project.basedir", "."))
                        .toAbsolutePath().relativize(source.toAbsolutePath()).toString();
                if (BASELINE.contains(relative.replace('\\', '/'))) {
                    continue;
                }
                Matcher matcher = LEAKING_CALL.matcher(Files.readString(source));
                if (matcher.find()) {
                    offenders.add(relative.replace('\\', '/'));
                }
            }
        }

        assertTrue(offenders.isEmpty(),
                "these tests create temporary files nothing removes:\n  "
                        + String.join("\n  ", offenders)
                        + "\n\nUse @TempDir, or com.openggf.tests.TestTempFiles where"
                        + " @TempDir cannot be injected. The overloads taking an"
                        + " existing directory are fine and are not flagged.");
    }
}
