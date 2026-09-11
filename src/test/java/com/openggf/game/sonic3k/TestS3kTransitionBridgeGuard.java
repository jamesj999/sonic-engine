package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

class TestS3kTransitionBridgeGuard {

    @Test
    void hczBossObject_shouldUseSharedHczEventWriteSupport() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/objects/bosses/HczEndBossInstance.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (content.contains("Sonic3kLevelEventManager")) {
            violations.add(file + " still references Sonic3kLevelEventManager directly");
        }
        if (content.contains("getHczEvents(")) {
            violations.add(file + " still resolves HCZ events directly");
        }
        if (!content.contains("S3kHczEventWriteSupport.")) {
            violations.add(file + " does not use S3kHczEventWriteSupport");
        }

        if (!violations.isEmpty()) {
            fail("HCZ boss objects should route event mutations through shared support:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void s3kResultsScreen_shouldUseSharedTransitionWriteSupport() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (content.contains("Sonic3kLevelEventManager")) {
            violations.add(file + " still references Sonic3kLevelEventManager directly");
        }
        if (!content.contains("S3kTransitionWriteSupport.")) {
            violations.add(file + " does not use S3kTransitionWriteSupport");
        }

        if (!violations.isEmpty()) {
            fail("S3K results screen should route act-transition signaling through shared support:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void hczEvents_shouldUseSharedTransitionWriteSupportForPostTransitionCutscene() throws IOException {
        String file = "src/main/java/com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java";
        String content = Files.readString(Path.of(file));
        List<String> violations = new ArrayList<>();

        if (content.contains("levelEventManagerOrNull(")) {
            violations.add(file + " still resolves the level event manager directly");
        }
        if (content.contains("setHczPendingPostTransitionCutscene(")) {
            violations.add(file + " still calls setHczPendingPostTransitionCutscene(...) directly");
        }
        if (!content.contains("S3kTransitionWriteSupport.requestHczPostTransitionCutscene(")) {
            violations.add(file + " does not route the HCZ post-transition cutscene through S3kTransitionWriteSupport");
        }

        if (!violations.isEmpty()) {
            fail("HCZ transition events should route post-transition cutscene signaling through shared support:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }
}
