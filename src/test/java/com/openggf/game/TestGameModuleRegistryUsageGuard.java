package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class TestGameModuleRegistryUsageGuard {

    @Test
    void gameplayCodeShouldNotReadGameModuleRegistryDirectly() throws IOException {
        Path srcMain = Path.of("src/main/java");
        List<String> allowed = List.of(
                "src/main/java/com/openggf/game/GameModuleRegistry.java"
        );
        List<String> violations = new ArrayList<>();

        Files.walk(srcMain)
                .filter(path -> path.toString().endsWith(".java"))
                .forEach(path -> {
                    try {
                        String normalized = path.toString().replace('\\', '/');
                        if (allowed.contains(normalized)) {
                            return;
                        }
                        String content = Files.readString(path);
                        if (content.contains("GameModuleRegistry.getCurrent(")) {
                            violations.add(normalized);
                        }
                    } catch (IOException ignored) {
                    }
                });

        if (!violations.isEmpty()) {
            fail("Direct gameplay GameModuleRegistry usage remains:\n  " + String.join("\n  ", violations));
        }
    }
}


