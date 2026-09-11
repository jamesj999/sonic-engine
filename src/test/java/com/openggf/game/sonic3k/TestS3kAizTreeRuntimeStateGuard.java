package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

class TestS3kAizTreeRuntimeStateGuard {

    private static final String[] GUARDED_FILES = {
            "src/main/java/com/openggf/game/sonic3k/objects/AizBgTreeInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/AizBgTreeSpawnerInstance.java"
    };

    @Test
    void aizTreeObjects_shouldReadBattleshipStateFromRuntimeRegistry() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String file : GUARDED_FILES) {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            String content = Files.readString(path);
            if (content.contains("Sonic3kLevelEventManager")) {
                violations.add(file + " still references Sonic3kLevelEventManager directly");
            }
            if (content.contains("getAizEvents(")) {
                violations.add(file + " still resolves AIZ events directly");
            }
            if (!content.contains("S3kRuntimeStates.currentAiz(")) {
                violations.add(file + " does not read AIZ state through S3kRuntimeStates.currentAiz(...)");
            }
        }

        if (!violations.isEmpty()) {
            fail("AIZ background tree objects should read shared runtime state instead of the event manager:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }
}
