package com.openggf.tests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;

/** Test-only resolver for the exact optional TraceChaser gitlink. */
public final class TraceChaserTestSupport {
    private TraceChaserTestSupport() { }

    public static Path requirePinnedCheckout() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        String index = git(root, "ls-files", "-s", "--", "tools/tracechaser").strip();
        String[] fields = index.split("\\s+", 4);
        if (fields.length < 4 || !"160000".equals(fields[0])) {
            fail("tools/tracechaser is not a gitlink in the OpenGGF index");
        }
        String expected = fields[1];
        Path checkout = root.resolve("tools/tracechaser");
        if (Files.isSymbolicLink(checkout) || (Files.exists(checkout) && !Files.isDirectory(checkout))) {
            fail("tools/tracechaser is an unsafe path; expected " + expected);
        }
        Assumptions.assumeTrue(Files.exists(checkout.resolve(".git")),
                "TraceChaser is optional; initialize with: git submodule update --init --recursive tools/tracechaser");
        String actual = git(checkout, "rev-parse", "HEAD").strip();
        assertEquals(expected, actual,
                "wrong TraceChaser checkout; run: git submodule update --init --recursive tools/tracechaser");
        return checkout;
    }

    private static String git(Path cwd, String... arguments) {
        try {
            var command = new java.util.ArrayList<>(List.of("git", "-C", cwd.toString()));
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.waitFor() != 0) fail("git command failed: " + output);
            return output;
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
