package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guard: gameplay code must use {@link com.openggf.game.GameRng} (the deterministic
 * ROM-accurate RNG) instead of {@code java.util.Random},
 * {@code java.util.concurrent.ThreadLocalRandom}, or {@code Math.random()}.
 *
 * <p>Determinism is required for trace replay, the rewind framework, and
 * cross-platform parity. Any non-deterministic RNG in update/spawn paths breaks
 * all three.
 *
 * <p>The scanned packages cover all live gameplay code: {@code level}, {@code sprites},
 * {@code physics}, and the per-game roots ({@code game.sonic1}, {@code game.sonic2},
 * {@code game.sonic3k}). Test sources, audio drivers, graphics back-ends and
 * tooling are excluded — only runtime gameplay logic must be deterministic.
 *
 * <p>If a legitimate need for non-determinism exists (e.g. cosmetic-only
 * pre-game presentation), add the source path to {@link #PERMANENT_EXCEPTIONS}
 * with a justifying comment.
 */
class TestNoStrayRngInGameplay {

    private static final String[] SCANNED_PACKAGES = {
            "com/openggf/level",
            "com/openggf/sprites",
            "com/openggf/physics",
            "com/openggf/game/sonic1",
            "com/openggf/game/sonic2",
            "com/openggf/game/sonic3k",
    };

    /**
     * Source files allowed to use non-deterministic RNG. Each entry must include
     * a justifying comment. Cosmetic, pre-gameplay-only code is the only allowed
     * category — anything that affects gameplay state, spawns, physics, or
     * anything captured by trace replay must use {@link com.openggf.game.GameRng}.
     */
    private static final Set<String> PERMANENT_EXCEPTIONS = Set.of(
            // Cosmetic clouds on the master title screen, before any game module
            // boots. Uses nextFloat() for sub-pixel positioning; not part of
            // gameplay state and not exercised by trace replay or rewind.
            "com/openggf/game/MasterTitleScreen.java"
    );

    private static final Pattern FORBIDDEN_RNG = Pattern.compile(
            "\\b(?:java\\.util\\.Random\\b"
            + "|new\\s+Random\\s*\\("
            + "|java\\.util\\.concurrent\\.ThreadLocalRandom\\b"
            + "|ThreadLocalRandom\\s*\\.\\s*current\\s*\\("
            + "|Math\\s*\\.\\s*random\\s*\\()");

    @Test
    void gameplayCode_mustUseGameRng() throws IOException {
        Path srcMain = Path.of("src/main/java");
        if (!Files.isDirectory(srcMain)) {
            return;
        }

        List<String> violations = new ArrayList<>();

        for (String pkg : SCANNED_PACKAGES) {
            Path pkgDir = srcMain.resolve(pkg);
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .forEach(path -> {
                            String relative = srcMain.relativize(path).toString().replace('\\', '/');
                            if (PERMANENT_EXCEPTIONS.contains(relative)) {
                                return;
                            }
                            try {
                                String stripped = stripCommentsAndStrings(Files.readString(path));
                                Matcher matcher = FORBIDDEN_RNG.matcher(stripped);
                                if (matcher.find()) {
                                    violations.add(relative + " — " + matcher.group());
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        // Also scan the wider game package roots (excluding generic infrastructure
        // already covered above), so that any new gameplay file outside the
        // per-game packages still trips the guard.
        Path gameDir = srcMain.resolve("com/openggf/game");
        if (Files.isDirectory(gameDir)) {
            try (Stream<Path> files = Files.list(gameDir)) {
                files.filter(p -> p.toString().endsWith(".java"))
                        .forEach(path -> {
                            String relative = srcMain.relativize(path).toString().replace('\\', '/');
                            if (PERMANENT_EXCEPTIONS.contains(relative)) {
                                return;
                            }
                            try {
                                String stripped = stripCommentsAndStrings(Files.readString(path));
                                Matcher matcher = FORBIDDEN_RNG.matcher(stripped);
                                if (matcher.find()) {
                                    violations.add(relative + " — " + matcher.group());
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        if (!violations.isEmpty()) {
            fail("Gameplay code must use GameRng (services().rng() / GameServices.rng() / "
                    + "AbstractPlayableSprite.currentRng()) instead of stray RNG. "
                    + "Non-deterministic RNG breaks trace replay, rewind, and cross-platform parity.\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
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
