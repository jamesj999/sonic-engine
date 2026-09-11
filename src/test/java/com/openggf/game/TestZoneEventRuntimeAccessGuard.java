package com.openggf.game;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.fail;

class TestZoneEventRuntimeAccessGuard {

    private static final String[] EVENT_PACKAGES = {
            "src/main/java/com/openggf/game/sonic1/events",
            "src/main/java/com/openggf/game/sonic2/events",
            "src/main/java/com/openggf/game/sonic3k/events"
    };

    private static final Set<String> ALLOWED_FILES = Set.of(
            "Sonic1ZoneEvents.java",
            "Sonic1LZWaterEvents.java",
            "Sonic2ZoneEvents.java",
            "Sonic3kZoneEvents.java",
            "S3kSeamlessMutationExecutor.java",
            // FIXME: Sonic3kMGZEvents still reaches GameServices directly for the
            // level-event provider and the zone-layout mutation pipeline. Landed
            // on develop ahead of this branch; track routing it through the shared
            // Sonic3k event helpers as a follow-up so this entry can be removed.
            "Sonic3kMGZEvents.java"
    );

    @Test
    void zoneEventImplementations_shouldNotReferenceGameServicesDirectly() throws IOException {
        List<String> violations = new ArrayList<>();

        for (String root : EVENT_PACKAGES) {
            Path pkgDir = Path.of(root);
            if (!Files.isDirectory(pkgDir)) {
                continue;
            }

            try (Stream<Path> files = Files.walk(pkgDir)) {
                files.filter(path -> path.toString().endsWith(".java"))
                        .filter(path -> !ALLOWED_FILES.contains(path.getFileName().toString()))
                        .forEach(path -> {
                            try {
                                String content = Files.readString(path);
                                if (content.contains("GameServices.")) {
                                    violations.add(path.toString().replace('\\', '/'));
                                }
                            } catch (IOException ignored) {
                            }
                        });
            }
        }

        if (!violations.isEmpty()) {
            fail("Zone event implementations should route runtime access through shared event helpers, not GameServices directly:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }
}


