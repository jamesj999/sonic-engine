package com.openggf.tests;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard: the class-level engine teardown stays wired into every test run.
 * <p>
 * Order-dependent suite failures were traced to test classes that build a
 * {@link HeadlessTestFixture} / {@link SharedLevel}, open a gameplay session,
 * or write configuration and never tear it down, so the next class in a
 * reused fork inherits a loaded level. {@link HeadlessStateTeardownExtension}
 * fixes that systemically, but only while three pieces of wiring hold: the
 * service registration, the platform property that makes JUnit honour it, and
 * the {@code afterAll} body that actually resets. Each is a one-line file a
 * tidy-up could drop without any test failing deterministically, so this
 * guard pins all three from source, in a fresh JVM under {@code -Pguards},
 * while {@link TestHeadlessStateTeardownExtension} proves the runtime effect
 * in the ordinary suite.
 */
class TestHeadlessStateTeardownGuard {

    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final Path TEST_RESOURCES = PROJECT_ROOT.resolve("src/test/resources");
    private static final Path TEST_SOURCES = PROJECT_ROOT.resolve("src/test/java");
    private static final String EXTENSION_CLASS =
            "com.openggf.tests.HeadlessStateTeardownExtension";
    private static final String AUTODETECTION_KEY =
            "junit.jupiter.extensions.autodetection.enabled";

    private static Path resolveProjectRoot() {
        String basedir = System.getProperty("project.basedir");
        if (basedir != null && !basedir.isEmpty()) {
            return Paths.get(basedir);
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }

    @Test
    void platformPropertiesEnableExtensionAutodetection() throws IOException {
        Path properties = TEST_RESOURCES.resolve("junit-platform.properties");
        assertTrue(Files.isRegularFile(properties),
                "missing " + properties + "; JUnit only honours the service-registered "
                        + "teardown extension when autodetection is enabled there");
        Properties parsed = new Properties();
        try (InputStream in = Files.newInputStream(properties)) {
            parsed.load(in);
        }
        assertEquals("true", parsed.getProperty(AUTODETECTION_KEY),
                properties + " must set " + AUTODETECTION_KEY + "=true");
    }

    @Test
    void serviceRegistrationNamesTheTeardownExtension() throws IOException {
        Path service = TEST_RESOURCES.resolve(
                "META-INF/services/org.junit.jupiter.api.extension.Extension");
        assertTrue(Files.isRegularFile(service), "missing " + service);
        List<String> providers = Files.readAllLines(service, StandardCharsets.UTF_8).stream()
                .map(line -> line.replaceFirst("#.*$", "").strip())
                .filter(line -> !line.isEmpty())
                .toList();
        assertTrue(providers.contains(EXTENSION_CLASS),
                service + " must list " + EXTENSION_CLASS + "; found " + providers);
    }

    @Test
    void extensionResetsTheBaselineAfterEachTopLevelClass() throws IOException {
        Path source = TEST_SOURCES.resolve(
                EXTENSION_CLASS.replace('.', '/') + ".java");
        assertTrue(Files.isRegularFile(source), "missing " + source);
        String text = Files.readString(source, StandardCharsets.UTF_8);
        assertTrue(text.contains("implements BeforeAllCallback, AfterAllCallback"),
                EXTENSION_CLASS + " must implement AfterAllCallback");
        String afterAll = bodyOf(text, "public void afterAll(");
        assertTrue(afterAll.contains("TestEnvironment.resetAll()"),
                EXTENSION_CLASS + ".afterAll must call TestEnvironment.resetAll()");
        assertTrue(afterAll.contains("getEnclosingClass() != null"),
                EXTENSION_CLASS + ".afterAll must skip @Nested classes so an outer "
                        + "class keeps its @BeforeAll fixture");
    }

    @Test
    void buildDoesNotDisableAutodetection() throws IOException {
        Pattern disabled = Pattern.compile(
                Pattern.quote(AUTODETECTION_KEY) + "\\s*[=>]\\s*false");
        Path pom = PROJECT_ROOT.resolve("pom.xml");
        assertFalse(disabled.matcher(Files.readString(pom, StandardCharsets.UTF_8)).find(),
                "pom.xml disables " + AUTODETECTION_KEY);
        try (var walk = Files.walk(TEST_RESOURCES.resolve("."))) {
            for (Path p : walk.filter(p -> p.getFileName().toString()
                    .equals("junit-platform.properties")).toList()) {
                assertFalse(disabled.matcher(Files.readString(p, StandardCharsets.UTF_8)).find(),
                        p + " disables " + AUTODETECTION_KEY);
            }
        }
    }

    @Test
    void runtimeProofDeclaresNoExtensionOfItsOwn() throws IOException {
        Path proof = TEST_SOURCES.resolve(
                "com/openggf/tests/TestHeadlessStateTeardownExtension.java");
        assertTrue(Files.isRegularFile(proof), "missing " + proof);
        String text = Files.readString(proof, StandardCharsets.UTF_8);
        assertFalse(text.contains("@ExtendWith("),
                proof + " must not declare @ExtendWith; its beforeAll observation "
                        + "is only evidence of the global registration if nothing "
                        + "registers the extension declaratively");
        assertTrue(text.contains("HeadlessStateTeardownExtension.observed(getClass())"),
                proof + " must assert HeadlessStateTeardownExtension.observed(getClass())");
    }

    /** Returns the brace-balanced body following the first occurrence of {@code signature}. */
    private static String bodyOf(String text, String signature) {
        int start = text.indexOf(signature);
        assertTrue(start >= 0, "no " + signature + " in extension source");
        int open = text.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return text.substring(open, i + 1);
            }
        }
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
