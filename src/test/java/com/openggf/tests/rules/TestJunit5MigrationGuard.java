package com.openggf.tests.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestJunit5MigrationGuard {
    private static final Path TEST_ROOT = Path.of("src", "test", "java");
    private static final String SELF_PATH = "com/openggf/tests/rules/TestJunit5MigrationGuard.java";
    private static final String LEGACY_RULE_NAME = "RequiresRom" + "Rule";
    private static final String RULE_ANNOTATION = "@" + "Rule\\b";
    private static final String CLASS_RULE_ANNOTATION = "@Class" + "Rule\\b";
    private static final String RUN_WITH = "@" + "RunWith\\b";
    private static final String RUNNER_PACKAGE = "org\\.junit\\." + "runner";
    private static final String LEGACY_JUNIT_IMPORT =
            "import\\s+(?:static\\s+)?org\\.junit\\.(?!(?:jupiter|platform)\\b)[\\w.*]+;";
    private static final String FULLY_QUALIFIED_LEGACY_ANNOTATION =
            "@org\\.junit\\.(?:Test|Before|After|BeforeClass|AfterClass|Ignore|Rule|ClassRule)\\b";
    private static final Pattern RULE_USAGE =
            Pattern.compile(LEGACY_RULE_NAME + "|" + RULE_ANNOTATION + "|" + CLASS_RULE_ANNOTATION);
    private static final Pattern JUNIT4_IMPORTS = Pattern.compile(
            LEGACY_JUNIT_IMPORT
                    + "|" + FULLY_QUALIFIED_LEGACY_ANNOTATION
                    + "|" + RUN_WITH
                    + "|" + RUNNER_PACKAGE);

    @Test
    void testSourcesDoNotReintroduceRuleBasedOrJunit4Fixtures() throws IOException {
        List<String> violations = new ArrayList<>();

        try (Stream<Path> files = Files.walk(TEST_ROOT)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .sorted()
                    .forEach(path -> scan(path, violations));
        }

        assertTrue(violations.isEmpty(), String.join(System.lineSeparator(), violations));
    }

    @Test
    void junit4ImportPatternRejectsWildcardAndStaticLegacyImports() {
        assertTrue(JUNIT4_IMPORTS.matcher("import org.junit.*;").find());
        assertTrue(JUNIT4_IMPORTS.matcher("import static org.junit.Assert.assertEquals;").find());
        assertTrue(JUNIT4_IMPORTS.matcher("import org.junit.rules.TemporaryFolder;").find());
    }

    @Test
    void junit4ImportPatternRejectsFullyQualifiedLegacyAnnotations() {
        assertTrue(JUNIT4_IMPORTS.matcher("@org.junit.Test public void oldStyle() {}").find());
        assertTrue(JUNIT4_IMPORTS.matcher("@org.junit.Ignore(\"old\")").find());
    }

    private void scan(Path path, List<String> violations) {
        String normalized = TEST_ROOT.relativize(path).toString().replace('\\', '/');
        if (SELF_PATH.equals(normalized)) {
            return;
        }
        String source;
        try {
            source = Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }

        if (RULE_USAGE.matcher(source).find()) {
            violations.add("Legacy rule usage in " + normalized);
        }
        if (JUNIT4_IMPORTS.matcher(source).find()) {
            violations.add("JUnit 4 import or runner usage in " + normalized);
        }
    }
}


