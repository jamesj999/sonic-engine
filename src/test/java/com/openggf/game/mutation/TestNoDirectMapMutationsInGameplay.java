package com.openggf.game.mutation;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Migration guard: no gameplay-path code may directly call
 * {@code getMap().setValue(...)} or {@code map.setValue(...)} on a Map
 * obtained from a Level. All level mutations must route through
 * {@link com.openggf.game.mutation.ZoneLayoutMutationPipeline} so the
 * rewind framework's level-state copy-on-write can intercept them in a
 * single place.
 *
 * <p>Editor commands (PlaceBlockCommand, Derive*Command) are exempt
 * because they are not gameplay paths. Loaders and resource decoders that
 * populate level structures at initialization are also exempt (e.g.,
 * Sonic3kLevel.map.setValue for ROM layout decoding).
 */
class TestNoDirectMapMutationsInGameplay {

    private static final String RAW_MAP_MUTATORS =
            "(?:setValue|setChunkDesc|setPatternDesc|setSolidTileIndex|setSolidTileAltIndex)";
    private static final Pattern DIRECT_MAP_MUTATION = Pattern.compile(
            "(?:\\([^;\\r\\n]*\\bgetMap\\s*\\(\\s*\\)\\s*\\)|\\bgetMap\\s*\\(\\s*\\))"
                    + "\\s*\\.\\s*" + RAW_MAP_MUTATORS + "\\s*\\(");
    private static final Pattern LEVEL_MAP_ALIAS = Pattern.compile(
            "\\b([A-Za-z_$][\\w$]*)\\s*=\\s*[^;\\r\\n]*\\.\\s*getMap\\s*\\(\\s*\\)\\s*;");

    private static final String[] SCAN_ROOTS = {
            "src/main/java/com/openggf/game/sonic1",
            "src/main/java/com/openggf/game/sonic2",
            "src/main/java/com/openggf/game/sonic3k",
            "src/main/java/com/openggf/level/objects",
    };

    /**
     * File paths allowed to use direct getMap().setValue(...).
     * Each entry is relative to src/main/java/ and includes a justifying comment.
     */
    private static final Set<String> ALLOWED = Set.of(
            // Initial layout decoding from ROM (not a gameplay mutation)
            "com/openggf/game/sonic3k/Sonic3kLevel.java"
    );

    @Test
    void noDirectMapSetValueInGameplay() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String root : SCAN_ROOTS) {
            for (Path p : scannableJavaFiles(root)) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    // Strip line comments and block comments to avoid
                    // false positives on documentation that mentions
                    // the old pattern.
                    String stripped = stripCommentsAndStrings(content);
                    if (hasDirectMapMutation(stripped)) {
                        violations.add(p.toString());
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        if (!violations.isEmpty()) {
            fail("Direct getMap().setValue() calls found in gameplay paths. "
                    + "All level mutations must route through "
                    + "ZoneLayoutMutationPipeline.\n  "
                    + String.join("\n  ", violations));
        }
    }

    @Test
    void detectsDirectChainedMapMutation() {
        assertTrue(hasDirectMapMutation("""
                class GameplayMutation {
                    void apply(Level level) {
                        level.getMap().setValue(0, 0, 1);
                    }
                }
                """));
    }

    @Test
    void detectsParenthesizedChainedMapMutation() {
        assertTrue(hasDirectMapMutation("""
                class GameplayMutation {
                    void apply(Level level) {
                        (level.getMap()).setValue(0, 0, 1);
                    }
                }
                """));
    }

    @Test
    void detectsMapAliasMutationFromLevelMap() {
        assertTrue(hasDirectMapMutation("""
                class GameplayMutation {
                    void apply(Level level) {
                        Map map = level.getMap();
                        map.setValue(0, 0, 1);
                    }
                }
                """));
    }

    @Test
    void detectsAliasChunkAndTileMutatorsFromLevelMap() {
        assertTrue(hasDirectMapMutation("""
                class GameplayMutation {
                    void apply(Level level) {
                        var levelMap = level.getMap();
                        levelMap.setSolidTileAltIndex(3);
                    }
                }
                """));
    }

    @Test
    void scanRootsExistAndContainJavaFiles() throws IOException {
        for (String root : SCAN_ROOTS) {
            assertFalse(scannableJavaFiles(root).isEmpty(),
                    "scan root must exist and contain Java files: " + root);
        }
    }

    @Test
    void allowListEntriesStillExist() {
        for (String allowed : ALLOWED) {
            assertTrue(Files.isRegularFile(Paths.get("src/main/java").resolve(allowed)),
                    "allow-list entry must point at a current source file: " + allowed);
        }
    }

    @Test
    void ignoresMapMutationTextInCommentsAndStrings() {
        String stripped = stripCommentsAndStrings("""
                class Example {
                    // level.getMap().setValue(0, 0, 1);
                    String text = "Map map = level.getMap(); map.setValue(0, 0, 1);";
                }
                """);
        assertFalse(hasDirectMapMutation(stripped));
    }

    private static boolean hasDirectMapMutation(String strippedSource) {
        if (DIRECT_MAP_MUTATION.matcher(strippedSource).find()) {
            return true;
        }
        Matcher aliasMatcher = LEVEL_MAP_ALIAS.matcher(strippedSource);
        while (aliasMatcher.find()) {
            String alias = aliasMatcher.group(1);
            Pattern aliasMutation = Pattern.compile("\\b" + Pattern.quote(alias)
                    + "\\s*\\.\\s*" + RAW_MAP_MUTATORS + "\\s*\\(");
            if (aliasMutation.matcher(strippedSource).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Path> scannableJavaFiles(String root) throws IOException {
        Path rootPath = Paths.get(root);
        assertTrue(Files.isDirectory(rootPath), "scan root must exist: " + root);
        try (Stream<Path> walk = Files.walk(rootPath)) {
            List<Path> files = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !isAllowed(p))
                    .sorted()
                    .toList();
            assertTrue(!files.isEmpty(), "scan root must contain Java files: " + root);
            return files;
        }
    }

    /**
     * Check if a file path is in the allow-list.
     * The allow-list paths are relative to src/main/java/; we match against
     * the relative portion of the given path.
     */
    private static boolean isAllowed(Path filePath) {
        String pathStr = filePath.toString().replace('\\', '/');
        for (String allowed : ALLOWED) {
            if (pathStr.endsWith(allowed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Removes // line comments, /* ... *\/ block comments, and string literals
     * so that the regex doesn't match these tokens when they appear in
     * documentation, log messages, or javadoc examples.
     */
    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(source.charAt(i) == '*' && source.charAt(i + 1) == '/')) {
                    i++;
                }
                i = Math.min(n, i + 2);
            } else if (c == '"') {
                out.append('"');
                i++;
                while (i < n && source.charAt(i) != '"') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append('"');
                if (i < n) {
                    i++;
                }
            } else if (c == '\'') {
                out.append('\'');
                i++;
                while (i < n && source.charAt(i) != '\'') {
                    if (source.charAt(i) == '\\' && i + 1 < n) {
                        i += 2;
                    } else {
                        i++;
                    }
                }
                out.append('\'');
                if (i < n) {
                    i++;
                }
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
