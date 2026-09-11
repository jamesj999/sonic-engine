package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestProductionAwtBlacklistGuard {

    private static final Path EXEMPT_FILE = Path.of("com/openggf/audio/debug/SoundTestApp.java");

    private static final Set<String> KNOWN_VIOLATIONS = Set.of();

    @Test
    public void productionCodeDoesNotIntroduceNewAwtOrSwingUsage() throws IOException {
        Path srcMain = findSourceRoot();
        assertTrue(srcMain != null, "Expected src/main/java to exist");

        Set<String> actualViolations = new TreeSet<>();
        try (var paths = Files.walk(srcMain)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> scanFile(srcMain, path, actualViolations));
        }

        assertEquals(new TreeSet<>(KNOWN_VIOLATIONS), actualViolations,
                "Production AWT/Swing usage must stay at the current shrink-to-zero baseline.");
    }

    private static void scanFile(Path srcMain, Path file, Set<String> violations) {
        Path relative = srcMain.relativize(file);
        if (relative.equals(EXEMPT_FILE)) {
            return;
        }

        try {
            String source = Files.readString(file);
            String stripped = TestProductionSingletonClosureGuard.stripComments(source);
            if (stripped.contains("java.awt.") || stripped.contains("javax.swing.")) {
                violations.add(relative.toString().replace('\\', '/'));
            }
        } catch (IOException ignored) {
        }
    }

    private static Path findSourceRoot() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path srcMain = cwd.resolve("src/main/java");
        if (Files.isDirectory(srcMain)) {
            return srcMain;
        }
        Path parent = cwd.getParent();
        if (parent == null) {
            return null;
        }
        srcMain = parent.resolve("src/main/java");
        return Files.isDirectory(srcMain) ? srcMain : null;
    }
}
