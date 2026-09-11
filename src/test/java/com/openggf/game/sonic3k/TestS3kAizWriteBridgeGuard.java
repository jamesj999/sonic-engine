package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

class TestS3kAizWriteBridgeGuard {

    private static final String[] GUARDED_FILES = {
            "src/main/java/com/openggf/game/sonic3k/objects/AizBattleshipInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/AizBossSmallInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/AizEndBossInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/AizMinibossInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/AizShipBombInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/S3kBossDefeatSignpostFlow.java"
    };

    @Test
    void aizWriteSideObjects_shouldUseSharedAizEventWriteSupport() throws IOException {
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
            if (!content.contains("S3kAizEventWriteSupport.")) {
                violations.add(file + " does not use S3kAizEventWriteSupport");
            }
        }

        if (!violations.isEmpty()) {
            fail("AIZ write-side object code should route event mutations through shared support:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }

    @Test
    void aizBossChildrenShouldUseConstructionContextSpawning() throws IOException {
        List<String> violations = new ArrayList<>();
        assertNoRawChildSpawns(violations,
                "src/main/java/com/openggf/game/sonic3k/objects/AizEndBossInstance.java",
                "objectManager.addDynamicObject(shipChild)",
                "objectManager.addDynamicObject(leftArm)",
                "objectManager.addDynamicObject(rightArm)",
                "objectManager.addDynamicObject(new AizEndBossFlameColumnChild",
                "objectManager.addDynamicObject(explosion)");
        assertNoRawChildSpawns(violations,
                "src/main/java/com/openggf/game/sonic3k/objects/AizMinibossCutsceneInstance.java",
                "objectManager.addDynamicObject(child)",
                "objectManager.addDynamicObject(new S3kBossExplosionChild",
                "objectManager.addDynamicObject(new AizMinibossDebrisChild");

        if (!violations.isEmpty()) {
            fail("AIZ boss child objects should be created through spawnChild suppliers so constructor services are set:\n  "
                    + String.join("\n  ", violations));
        }
    }

    private static void assertNoRawChildSpawns(List<String> violations,
                                               String file,
                                               String... forbiddenSnippets) throws IOException {
        String content = Files.readString(Path.of(file));
        for (String forbidden : forbiddenSnippets) {
            if (content.contains(forbidden)) {
                violations.add(file + " contains " + forbidden);
            }
        }
    }
}
