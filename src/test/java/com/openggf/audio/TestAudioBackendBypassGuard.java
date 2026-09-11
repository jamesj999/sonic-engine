package com.openggf.audio;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAudioBackendBypassGuard {
    @Test
    void gameplayCodeDoesNotBypassAudioManagerForBackendCommands() throws IOException {
        Path root = Path.of("src/main/java/com/openggf");
        List<String> violations = new ArrayList<>();

        try (var paths = Files.walk(root)) {
            for (Path path : paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".java"))
                    .toList()) {
                String normalized = path.toString().replace('\\', '/');
                if (normalized.contains("/audio/AudioManager.java")
                        || normalized.contains("/audio/debug/")
                        || normalized.contains("/audio/LWJGLAudioBackend.java")
                        || normalized.contains("/audio/NullAudioBackend.java")
                        || normalized.contains("/audio/AudioBackend.java")) {
                    continue;
                }

                String text = Files.readString(path);
                if (text.contains("GameServices.audio().getBackend()")
                        || text.contains("services().audioManager().getBackend()")
                        || text.contains("audioManager.getBackend()")) {
                    violations.add(normalized);
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Gameplay audio commands must route through AudioManager so rewind suppression can observe them: "
                        + violations);
    }
}
