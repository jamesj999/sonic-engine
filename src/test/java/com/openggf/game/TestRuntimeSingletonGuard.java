package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Source-text guard for strict {@link GameServices} accessor null checks.
 * Direct runtime manager {@code getInstance()} calls are enforced by ArchUnit.
 */
public class TestRuntimeSingletonGuard {

    private static final Pattern STRICT_GAME_SERVICES_NULL_CHECK = Pattern.compile(
            "(?:com\\.openggf\\.game\\.)?GameServices\\.(camera|level|gameState|timers|rng|fade|sprites|collision|terrainCollision|parallax|water|bonusStage)\\(\\)\\s*(==|!=)\\s*null");

    private static final Pattern STRICT_GAME_SERVICES_LOCAL_NULL_CHECK = Pattern.compile(
            "(?:[\\w.<>?]+\\s+)?(\\w+)\\s*=\\s*(?:com\\.openggf\\.game\\.)?GameServices\\."
                    + "(camera|level|gameState|timers|rng|fade|sprites|collision|terrainCollision|parallax|water|bonusStage)\\(\\)\\s*;\\s*"
                    + "if\\s*\\(\\s*\\1\\s*(==|!=)\\s*null\\s*\\)");

    @Test
    public void productionCodeDoesNotNullCheckStrictGameServicesAccessors() throws IOException {
        Path srcMain = findSourceRoot();
        if (srcMain == null) {
            return;
        }

        List<String> violations = new ArrayList<>();

        Files.walkFileTree(srcMain, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!file.toString().endsWith(".java")) {
                    return FileVisitResult.CONTINUE;
                }

                SourceScanText source = sourceWithoutCommentOnlyLines(Files.readAllLines(file));
                Matcher matcher = STRICT_GAME_SERVICES_NULL_CHECK.matcher(source.text);
                while (matcher.find()) {
                    String relativePath = srcMain.relativize(file).toString();
                    violations.add(String.format(
                            "%s:%d - GameServices.%s() %s null",
                            relativePath, source.lineAt(matcher.start()), matcher.group(1), matcher.group(2)));
                }
                matcher = STRICT_GAME_SERVICES_LOCAL_NULL_CHECK.matcher(source.text);
                while (matcher.find()) {
                    String relativePath = srcMain.relativize(file).toString();
                    violations.add(String.format(
                            "%s:%d - %s = GameServices.%s(); if (%s %s null)",
                            relativePath, source.lineAt(matcher.start()), matcher.group(1), matcher.group(2),
                            matcher.group(1), matcher.group(3)));
                }

                return FileVisitResult.CONTINUE;
            }
        });

        if (!violations.isEmpty()) {
            fail("Found " + violations.size() + " null check(s) against strict GameServices accessors.\n"
                    + "Use GameServices.<name>OrNull() or remove the null check:\n  "
                    + violations.stream().collect(Collectors.joining("\n  ")));
        }
    }

    private Path findSourceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path srcMain = cwd.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            return srcMain;
        }
        srcMain = cwd.getParent().resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            return srcMain;
        }
        return null;
    }

    private static SourceScanText sourceWithoutCommentOnlyLines(List<String> lines) {
        StringBuilder source = new StringBuilder();
        List<Integer> lineByOffset = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            String scannedLine = (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*"))
                    ? ""
                    : line;
            source.append(scannedLine);
            for (int j = 0; j < scannedLine.length(); j++) {
                lineByOffset.add(i + 1);
            }
            source.append('\n');
            lineByOffset.add(i + 1);
        }
        return new SourceScanText(source.toString(), lineByOffset);
    }

    private static final class SourceScanText {
        private final String text;
        private final List<Integer> lineByOffset;

        private SourceScanText(String text, List<Integer> lineByOffset) {
            this.text = text;
            this.lineByOffset = lineByOffset;
        }

        private int lineAt(int offset) {
            if (lineByOffset.isEmpty()) {
                return 1;
            }
            return lineByOffset.get(Math.min(offset, lineByOffset.size() - 1));
        }
    }
}
