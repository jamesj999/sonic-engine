package com.openggf.tests.trace;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestTraceV5PositiveInputGuard {
    private static final Path TEST_SOURCE_ROOT = Path.of("src/test/java");
    private static final String REJECTION_TEST =
            "com/openggf/trace/TestTraceV5LoadingContract.java";
    private static final String GUARD_TEST =
            "com/openggf/tests/trace/TestTraceV5PositiveInputGuard.java";
    private static final String FIXTURE_HELPER =
            "com/openggf/tests/trace/TraceV5TestFixture.java";
    private static final List<String> REMOVED_FIELDS = List.of(
            "lua_script_version", "csv_version", "ss_csv_version",
            "hardware_timing_schema", "run_schema");
    private static final Pattern TRACE_SCHEMA = Pattern.compile(
            "\\\"trace_schema\\\"\\s*:\\s*(\\d+)");
    private static final Pattern CSV_SEQUENCE = Pattern.compile(
            "(?<![0-9A-Za-z_,])([0-9A-Fa-f]{1,8}(?:,[0-9A-Fa-f]{1,8})+)(?![0-9A-Fa-f_,])");

    @Test
    void acceptsCanonicalTemporaryV5Input() {
        String source = """
                class Sample {
                    void writesCurrentTrace() {
                        var metadata = "\\\"trace_schema\\\": 5";
                        var row = TraceFrame.parseCsvRow(String.join(",",
                                java.util.Collections.nCopies(42, "0")));
                    }
                }
                """;

        assertEquals(List.of(), scanSource("sample/Sample.java", source));
    }

    @Test
    void rejectsLegacyPositiveInputSamples() {
        assertEquals(List.of("sample/Resource.java: legacy synthetic fixture dependency"),
                scanSource("sample/Resource.java", """
                        class Resource {
                            var path = Path.of("src/test/resources/traces/synthetic/basic_3frames");
                        }
                        """));
        assertEquals(List.of("sample/Metadata.java: removed trace metadata field csv_version"),
                scanSource("sample/Metadata.java", """
                        class Metadata {
                            var json = "{\\\"trace_schema\\\":5,\\\"csv_version\\\":7}";
                        }
                        """));
        assertEquals(List.of("sample/Schema.java: retired trace_schema 3"),
                scanSource("sample/Schema.java", """
                        class Schema {
                            var json = "{\\\"trace_schema\\\":3}";
                        }
                        """));
        assertEquals(List.of("sample/Row.java: retired 22-column level row"),
                scanSource("sample/Row.java", """
                        class Row {
                            var frame = TraceFrame.parseCsvRow(
                                    "0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0");
                        }
                        """));
    }

    @Test
    void repositoryPositiveTestsUseOnlyTemporaryV5Inputs() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var paths = Files.walk(TEST_SOURCE_ROOT)) {
            for (Path path : paths.filter(candidate -> candidate.toString().endsWith(".java"))
                    .sorted().toList()) {
                String relative = TEST_SOURCE_ROOT.relativize(path).toString();
                violations.addAll(scanSource(relative, Files.readString(path)));
            }
        }

        assertEquals(List.of(), violations,
                () -> "Positive trace tests retain legacy inputs:\n  "
                        + String.join("\n  ", violations));
    }

    private static List<String> scanSource(String relativePath, String source) {
        if (REJECTION_TEST.equals(relativePath) || GUARD_TEST.equals(relativePath)
                || FIXTURE_HELPER.equals(relativePath)) {
            return List.of();
        }

        String normalized = source.replace("\\\"", "\"");
        List<String> violations = new ArrayList<>();
        if (normalized.contains("traces/synthetic/")) {
            violations.add(relativePath + ": legacy synthetic fixture dependency");
        }
        for (String removedField : REMOVED_FIELDS) {
            if (normalized.contains("\"" + removedField + "\"")) {
                violations.add(relativePath + ": removed trace metadata field " + removedField);
            }
        }

        Matcher schema = TRACE_SCHEMA.matcher(normalized);
        while (schema.find()) {
            int version = Integer.parseInt(schema.group(1));
            if (version != 5) {
                violations.add(relativePath + ": retired trace_schema " + version);
            }
        }

        Matcher row = CSV_SEQUENCE.matcher(normalized);
        while (row.find()) {
            int width = row.group(1).split(",", -1).length;
            if (List.of(11, 18, 19, 20, 22, 37, 38).contains(width)) {
                violations.add(relativePath + ": retired " + width + "-column level row");
            }
        }
        return List.copyOf(violations);
    }
}
