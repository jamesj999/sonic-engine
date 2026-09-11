package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Build-time ratchet for tolerant rewind recreate links.
 *
 * <p>A {@code recreateForRewind} implementation must not {@code throw} when a
 * structural link target (parent / sibling / previous) is absent: during a rewind
 * restore the link target can legitimately have been destroyed by the player and
 * swept before capture, and every live consumer already tolerates a null link. The
 * canonical crash this guards against is
 * {@code IllegalStateException("Missing restored ... previous segment ...")} thrown
 * from a recreate helper that scanned for a <em>live</em> link and found none.
 *
 * <p>This scanner walks every object source file, extracts each
 * {@code recreateForRewind} method body plus the private helper methods it calls in
 * the same file, and counts {@code throw} statements in that scope. It fails when a
 * file's count differs from {@link #BASELINE_RESOURCE}. The baseline enumerates the
 * justified survivors:
 * <ul>
 *   <li><strong>Argument-validation</strong> throws in the shared recreate keystone
 *       interfaces — reflective "no matching constructor" failures signalling a
 *       framework/wiring mistake, not an absent game-state link.</li>
 * </ul>
 * A category-1 tolerance fix removes its baseline entry; a new throw beyond the
 * baseline fails the guard (fix coverage or, if genuinely category 2/3, add a
 * justified baseline entry).
 */
class TestRewindRecreateLinkToleranceGuard {

    private static final List<Path> OBJECT_SOURCE_ROOTS = List.of(
            Path.of("src/main/java/com/openggf/level/objects"),
            Path.of("src/main/java/com/openggf/level/rings"),
            Path.of("src/main/java/com/openggf/game/sonic1"),
            Path.of("src/main/java/com/openggf/game/sonic2"),
            Path.of("src/main/java/com/openggf/game/sonic3k")
    );

    private static final Path BASELINE_RESOURCE =
            Path.of("src/test/resources/rewind/recreate-throw-baseline.txt");

    /**
     * Files whose recreate throws are owned by a sibling effort and intentionally
     * excluded from this sweep. IczSegmentColumnObjectInstance's break-then-rewind
     * crash is fixed on bugfix/ai-icz-segment-column-rewind-break; excluding it here
     * keeps this guard valid whether or not that branch has merged.
     */
    private static final Set<String> EXCLUDED_FILES = Set.of(
            "src/main/java/com/openggf/game/sonic3k/objects/IczSegmentColumnObjectInstance.java"
    );

    private static final Pattern RECREATE_DECL = Pattern.compile(
            "\\brecreateForRewind\\s*\\(\\s*RewindRecreateContext\\b");
    private static final Pattern PRIVATE_METHOD_DECL = Pattern.compile(
            "\\bprivate\\s+(?:static\\s+)?[\\w.<>,\\[\\]\\s]+?\\b(\\w+)\\s*\\(");
    // Counts both a literal {@code throw} statement and the throwing-Optional idiom
    // {@code Optional.orElseThrow(...)} — an absent-link lookup expressed as a throw.
    private static final Pattern THROW = Pattern.compile("\\bthrow\\b|\\borElseThrow\\b");

    @Test
    void recreateForRewindLinkLookupsDoNotThrowBeyondBaseline() throws IOException {
        Map<String, Integer> actual = recreateThrowCounts();
        Map<String, Integer> baseline = loadBaseline();

        List<String> unexpected = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : actual.entrySet()) {
            Integer expected = baseline.get(entry.getKey());
            if (expected == null) {
                unexpected.add(entry.getKey() + " = " + entry.getValue()
                        + " throw(s) in recreate scope (no baseline entry)");
            } else if (!expected.equals(entry.getValue())) {
                unexpected.add(entry.getKey() + " = " + entry.getValue()
                        + " (baseline " + expected + ")");
            }
        }

        List<String> stale = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : baseline.entrySet()) {
            Integer observed = actual.get(entry.getKey());
            if (observed == null) {
                stale.add(entry.getKey() + " baseline " + entry.getValue()
                        + " is no longer present (remove the baseline line)");
            }
        }

        if (!unexpected.isEmpty() || !stale.isEmpty()) {
            List<String> sections = new ArrayList<>();
            if (!unexpected.isEmpty()) {
                sections.add("New/changed recreate throws (make the link lookup tolerant, "
                        + "or add a justified baseline line):\n" + String.join("\n", unexpected));
            }
            if (!stale.isEmpty()) {
                sections.add("Stale baseline lines:\n" + String.join("\n", stale));
            }
            fail("Rewind recreate link tolerance guard failed.\n" + String.join("\n\n", sections));
        }
    }

    private static Map<String, Integer> recreateThrowCounts() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        for (Path source : objectSources()) {
            String normalized = normalize(source);
            if (EXCLUDED_FILES.contains(normalized)) {
                continue;
            }
            String text = blankCommentsAndLiterals(Files.readString(source));
            if (!RECREATE_DECL.matcher(text).find()) {
                continue;
            }
            int throwCount = countThrowsInRecreateScope(text);
            if (throwCount > 0) {
                counts.put(normalized, throwCount);
            }
        }
        return counts;
    }

    /**
     * Counts {@code throw} statements inside every {@code recreateForRewind} body in
     * the file, plus every private helper method (declared in the same file) invoked
     * transitively from those bodies.
     */
    private static int countThrowsInRecreateScope(String text) {
        Map<String, int[]> privateMethods = indexPrivateMethods(text);

        Set<String> scopedHelperNames = new LinkedHashSet<>();
        List<String> scopeBodies = new ArrayList<>();

        Matcher decl = RECREATE_DECL.matcher(text);
        while (decl.find()) {
            String body = methodBodyFrom(text, decl.end());
            if (body != null) {
                scopeBodies.add(body);
                collectCalledPrivateMethods(body, privateMethods.keySet(), scopedHelperNames);
            }
        }

        // Transitively pull in helpers called by already-scoped helpers.
        boolean grew = true;
        while (grew) {
            grew = false;
            for (String name : new ArrayList<>(scopedHelperNames)) {
                int[] span = privateMethods.get(name);
                if (span == null) {
                    continue;
                }
                String body = text.substring(span[0], span[1]);
                int before = scopedHelperNames.size();
                collectCalledPrivateMethods(body, privateMethods.keySet(), scopedHelperNames);
                if (scopedHelperNames.size() != before) {
                    grew = true;
                }
            }
        }

        StringBuilder scope = new StringBuilder();
        for (String body : scopeBodies) {
            scope.append(body).append('\n');
        }
        for (String name : scopedHelperNames) {
            int[] span = privateMethods.get(name);
            if (span != null) {
                scope.append(text, span[0], span[1]).append('\n');
            }
        }
        return Math.toIntExact(THROW.matcher(scope.toString()).results().count());
    }

    private static void collectCalledPrivateMethods(
            String body, Set<String> methodNames, Set<String> out) {
        for (String name : methodNames) {
            if (Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\(").matcher(body).find()) {
                out.add(name);
            }
        }
    }

    /** Maps each private method name in the file to the [start,end) index of its body. */
    private static Map<String, int[]> indexPrivateMethods(String text) {
        Map<String, int[]> methods = new LinkedHashMap<>();
        Matcher m = PRIVATE_METHOD_DECL.matcher(text);
        while (m.find()) {
            int parenOpen = text.indexOf('(', m.start());
            int parenClose = matchDelimiter(text, parenOpen, '(', ')');
            if (parenClose < 0) {
                continue;
            }
            int braceOpen = text.indexOf('{', parenClose);
            int semicolon = text.indexOf(';', parenClose);
            // Abstract/interface method or field — no body.
            if (braceOpen < 0 || (semicolon >= 0 && semicolon < braceOpen)) {
                continue;
            }
            int braceClose = matchDelimiter(text, braceOpen, '{', '}');
            if (braceClose < 0) {
                continue;
            }
            methods.put(m.group(1), new int[]{braceOpen, braceClose + 1});
        }
        return methods;
    }

    /** Given the index just past a method's {@code (RewindRecreateContext...}, returns its body text. */
    private static String methodBodyFrom(String text, int fromIndex) {
        int parenOpen = text.lastIndexOf('(', fromIndex);
        int parenClose = matchDelimiter(text, parenOpen, '(', ')');
        if (parenClose < 0) {
            return null;
        }
        int braceOpen = text.indexOf('{', parenClose);
        if (braceOpen < 0) {
            return null;
        }
        int braceClose = matchDelimiter(text, braceOpen, '{', '}');
        if (braceClose < 0) {
            return null;
        }
        return text.substring(braceOpen, braceClose + 1);
    }

    private static int matchDelimiter(String text, int openIndex, char open, char close) {
        if (openIndex < 0 || openIndex >= text.length() || text.charAt(openIndex) != open) {
            return -1;
        }
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Returns the source with all comments and string/char literals replaced by
     * spaces (newlines preserved), so {@code throw} is only counted as a statement
     * and stray braces inside literals cannot corrupt delimiter matching.
     */
    private static String blankCommentsAndLiterals(String text) {
        char[] out = text.toCharArray();
        int n = out.length;
        int i = 0;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                out[i++] = ' ';
                out[i++] = ' ';
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\' && i + 1 < n) {
                        out[i++] = ' ';
                    }
                    if (i < n && out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    private static Map<String, Integer> loadBaseline() throws IOException {
        Map<String, Integer> baseline = new TreeMap<>();
        if (!Files.exists(BASELINE_RESOURCE)) {
            return baseline;
        }
        for (String raw : Files.readAllLines(BASELINE_RESOURCE)) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.lastIndexOf('=');
            if (eq < 0) {
                fail("Malformed baseline line (expected path=count): " + raw);
            }
            String path = line.substring(0, eq).trim();
            int count = Integer.parseInt(line.substring(eq + 1).trim());
            baseline.put(path, count);
        }
        return baseline;
    }

    private static List<Path> objectSources() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (Path root : OBJECT_SOURCE_ROOTS) {
            if (!Files.exists(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.walk(root)) {
                stream.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> normalize(path).contains("/objects/"))
                        .forEach(sources::add);
            }
        }
        sources.sort(Comparator.comparing(TestRewindRecreateLinkToleranceGuard::normalize));
        return sources;
    }

    private static String normalize(Path path) {
        return path.toString().replace('\\', '/');
    }
}
