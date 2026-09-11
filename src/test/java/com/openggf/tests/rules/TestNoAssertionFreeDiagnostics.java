package com.openggf.tests.rules;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard against assertion-free "diagnostic" tests regrowing under
 * {@code com/openggf/tests/} (which includes {@code tests/trace/}).
 *
 * <p>A {@code @Test} (or {@code @ParameterizedTest}/{@code @RepeatedTest}/
 * {@code @TestFactory}) method that steps the engine and only prints to stdout
 * always passes and provides zero verification while masquerading as coverage.
 * This scanner fails the build when a test method body contains no oracle
 * (no {@code assert*}, {@code fail(}, Mockito {@code verify(}, {@code assertThrows},
 * and does not delegate to a helper whose name or body carries an oracle).
 *
 * <p>The check is intentionally permissive (it strips comments/strings so a
 * commented-out assertion does not count, but treats any helper call named
 * like an asserter as an oracle) so it cannot block the build on borderline
 * but legitimate helper-based tests. Disabled (skip-annotated) methods/classes
 * are skipped — a disabled test is not false coverage.
 *
 * <p>The {@link #ALLOWLIST} carries the handful of intentional diagnostic
 * probes (including in-flight work owned by other branches) that are knowingly
 * kept on the {@code @Test} surface; add to it only with a clear reason.
 */
class TestNoAssertionFreeDiagnostics {

    /** Scope: tests/ and its subtree (tests/trace/, tests/objects/, ...). */
    private static final Path SCAN_ROOT = Path.of("src", "test", "java", "com", "openggf", "tests");

    private static final String SELF = "TestNoAssertionFreeDiagnostics";

    // Assembled from parts so this scanner's own source does not contain the
    // literal skip-annotation token (mirrors TestJunit5MigrationGuard's split
    // literals); otherwise TestBuildToolingGuard would flag this file.
    private static final String DISABLED_ANNOTATION = "@" + "Disabled";

    /**
     * Classes (by simple name) or specific {@code Class#method} entries that are
     * allowed to remain assertion-free diagnostic probes. Keep this list short
     * and justified.
     */
    private static final Set<String> ALLOWLIST = Set.of(
            // In-flight diagnostic probes owned by other active branches; do not
            // disturb concurrent sessions. Remove once those branches land and
            // either assert or demote them.
            "DebugS1Mz2BatbrainProbe",
            "TestS3kMhz1GroundWallProbe"
    );

    private static final Pattern TEST_ANNOTATION = Pattern.compile(
            "@(Test|ParameterizedTest|RepeatedTest|TestFactory)\\b");

    /** Direct oracle tokens that prove a method verifies something. */
    private static final Pattern ORACLE_TOKEN = Pattern.compile(
            "\\bassert\\w*\\s*\\("        // assertEquals/True/False/NotNull/Throws/All/DoesNotThrow/...
                    + "|\\bfail\\s*\\("            // org.junit fail(...)
                    + "|\\bverify\\w*\\s*\\("      // Mockito verify(...)/verifyNoInteractions(...)
                    + "|\\bexpectThrows\\s*\\("
                    + "|\\bAssertions\\.");

    /** Helper-method call names that conventionally carry an oracle. */
    private static final Pattern ORACLE_HELPER_CALL = Pattern.compile(
            "\\b\\w*(?:assert|verify|expect)\\w*\\s*\\(", Pattern.CASE_INSENSITIVE);

    /** Keywords that look like {@code name(...) {} } but are not helper methods. */
    private static final Set<String> NON_METHOD_KEYWORDS = Set.of(
            "if", "for", "while", "switch", "catch", "synchronized", "do", "else",
            "return", "try", "finally", "new");

    @Test
    void noAssertionFreeTestMethodsUnderTestsTree() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SCAN_ROOT)) {
            files.filter(p -> p.toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> scanFile(p, violations));
        }
        assertTrue(violations.isEmpty(),
                "Assertion-free @Test methods found (add a real oracle, demote off @Test, "
                        + "or allowlist with a reason in " + SELF + "):"
                        + System.lineSeparator()
                        + String.join(System.lineSeparator(), violations));
    }

    private void scanFile(Path path, List<String> violations) {
        String fileName = path.getFileName().toString();
        String className = fileName.endsWith(".java")
                ? fileName.substring(0, fileName.length() - ".java".length())
                : fileName;
        if (SELF.equals(className) || ALLOWLIST.contains(className)) {
            return;
        }
        String raw;
        try {
            raw = Files.readString(path);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + path, e);
        }
        if (hasClassLevelDisabled(raw)) {
            return;
        }
        String stripped = stripCommentsAndStrings(raw);
        Set<String> oracleHelperNames = collectOracleHelperNames(stripped);

        Matcher m = TEST_ANNOTATION.matcher(stripped);
        while (m.find()) {
            Method method = parseMethod(stripped, m.end());
            if (method == null) {
                continue;
            }
            int blockStart = previousMemberBoundary(stripped, m.start());
            String header = stripped.substring(blockStart, method.bodyOpen);
            if (header.contains(DISABLED_ANNOTATION)) {
                continue;
            }
            String body = stripped.substring(method.bodyOpen + 1, method.bodyClose);
            if (hasOracle(body, oracleHelperNames)) {
                continue;
            }
            if (ALLOWLIST.contains(className + "#" + method.name)) {
                continue;
            }
            violations.add("  " + className + "#" + method.name
                    + " has no assertion/verify/fail oracle (" + path + ")");
        }
    }

    private boolean hasOracle(String body, Set<String> oracleHelperNames) {
        if (ORACLE_TOKEN.matcher(body).find()) {
            return true;
        }
        // Delegation to a parent test method (e.g. an abstract trace-replay base)
        // carries the oracle in the overridden super method.
        if (body.contains("super.")) {
            return true;
        }
        if (ORACLE_HELPER_CALL.matcher(body).find()) {
            return true;
        }
        for (String helper : oracleHelperNames) {
            if (Pattern.compile("\\b" + Pattern.quote(helper) + "\\s*\\(").matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    /** Names of methods in this file whose own body contains a direct oracle token. */
    private Set<String> collectOracleHelperNames(String stripped) {
        Set<String> names = new HashSet<>();
        Pattern decl = Pattern.compile("\\b(\\w+)\\s*\\([^;{}]*\\)\\s*(?:throws[\\w,.\\s]+)?\\{");
        Matcher m = decl.matcher(stripped);
        while (m.find()) {
            String name = m.group(1);
            if (NON_METHOD_KEYWORDS.contains(name)) {
                continue;
            }
            int open = m.end() - 1;
            int close = matchBrace(stripped, open);
            if (close < 0) {
                continue;
            }
            String body = stripped.substring(open + 1, close);
            if (ORACLE_TOKEN.matcher(body).find()) {
                names.add(name);
            }
        }
        return names;
    }

    private boolean hasClassLevelDisabled(String raw) {
        Matcher m = Pattern.compile(DISABLED_ANNOTATION + "\\b[^\\n]*\\n\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*"
                + "(?:public|final|abstract|\\s)*class\\b").matcher(raw);
        return m.find();
    }

    /**
     * From an index just after a test annotation, skip any stacked annotations
     * and modifiers/return type, then locate the method name, parameter list,
     * and body braces. Returns {@code null} for abstract test methods.
     */
    private Method parseMethod(String s, int from) {
        int n = s.length();
        int i = from;
        // The matched test annotation may carry its own (...) arguments, e.g.
        // @ParameterizedTest(name = "...") or @RepeatedTest(5) — consume them so
        // their array-initializer braces are not mistaken for the method body.
        while (i < n && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
        if (i < n && s.charAt(i) == '(') {
            i = skipBalancedParens(s, i);
            if (i < 0) {
                return null;
            }
        }
        while (i < n) {
            // skip whitespace
            while (i < n && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            if (i >= n) {
                return null;
            }
            // skip a stacked annotation, including its balanced (...) arguments
            if (s.charAt(i) == '@') {
                i++;
                while (i < n && (Character.isJavaIdentifierPart(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                while (i < n && Character.isWhitespace(s.charAt(i))) {
                    i++;
                }
                if (i < n && s.charAt(i) == '(') {
                    i = skipBalancedParens(s, i);
                }
                continue;
            }
            break;
        }
        // Now scan to the parameter-list '(' that follows the method name.
        // The identifier immediately before that '(' is the method name.
        int paren = -1;
        for (int j = i; j < n; j++) {
            char c = s.charAt(j);
            if (c == '(') {
                paren = j;
                break;
            }
            if (c == ';' || c == '{' || c == '}' || c == '=') {
                return null;
            }
        }
        if (paren < 0) {
            return null;
        }
        int nameEnd = paren;
        while (nameEnd > i && Character.isWhitespace(s.charAt(nameEnd - 1))) {
            nameEnd--;
        }
        int nameStart = nameEnd;
        while (nameStart > i && Character.isJavaIdentifierPart(s.charAt(nameStart - 1))) {
            nameStart--;
        }
        String name = nameStart < nameEnd ? s.substring(nameStart, nameEnd) : "<unknown>";

        int afterParams = skipBalancedParens(s, paren);
        if (afterParams < 0) {
            return null;
        }
        int k = afterParams;
        // skip whitespace + optional "throws A, B.C"
        while (k < n && s.charAt(k) != '{' && s.charAt(k) != ';') {
            k++;
        }
        if (k >= n || s.charAt(k) == ';') {
            return null; // abstract method, no body
        }
        int bodyOpen = k;
        int bodyClose = matchBrace(s, bodyOpen);
        if (bodyClose < 0) {
            return null;
        }
        return new Method(name, bodyOpen, bodyClose);
    }

    private int skipBalancedParens(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
    }

    private int matchBrace(String s, int open) {
        int depth = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private int previousMemberBoundary(String s, int from) {
        for (int i = from - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if (c == '}' || c == ';') {
                return i + 1;
            }
        }
        return 0;
    }

    /** Replace block/line comments and string/char literals with spaces (newline-preserving). */
    private String stripCommentsAndStrings(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (c == '/' && i + 1 < n && s.charAt(i + 1) == '/') {
                while (i < n && s.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(s.charAt(i) == '*' && s.charAt(i + 1) == '/')) {
                    out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                    i++;
                }
                i += 2;
            } else if (c == '"') {
                if (i + 2 < n && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"') {
                    i += 3;
                    while (i + 2 < n && !(s.charAt(i) == '"' && s.charAt(i + 1) == '"' && s.charAt(i + 2) == '"')) {
                        out.append(s.charAt(i) == '\n' ? '\n' : ' ');
                        i++;
                    }
                    i += 3;
                } else {
                    i++;
                    while (i < n && s.charAt(i) != '"') {
                        if (s.charAt(i) == '\\' && i + 1 < n) {
                            i++;
                        }
                        i++;
                    }
                    i++;
                }
            } else if (c == '\'') {
                i++;
                while (i < n && s.charAt(i) != '\'') {
                    if (s.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    i++;
                }
                i++;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private record Method(String name, int bodyOpen, int bodyClose) {
    }
}
