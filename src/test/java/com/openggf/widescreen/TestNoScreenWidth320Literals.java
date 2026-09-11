package com.openggf.widescreen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Focused guard: the gameplay/camera/placement files this widescreen Foundation
 * made width-driven must not reintroduce a hardcoded screen-width 320 literal.
 * The comprehensive tree-wide guard is deferred to the Widescreen Rendering plan
 * (many other files still carry such literals, almost all rendering/UI). New code
 * in these files must use camera.getWidth() / SCREEN_WIDTH_PIXELS instead.
 *
 * This is a heuristic tripwire (matches the screen-width-shaped forms), not a
 * proof of absence.
 */
class TestNoScreenWidth320Literals {

    private static final Pattern SCREEN_320 =
            Pattern.compile("(SCREEN_W\\w*\\s*=\\s*320)|(=\\s*320\\b.*224)|(\\b320\\s*,\\s*224\\b)");

    private static final List<String> GUARDED_FILES = List.of(
            "src/main/java/com/openggf/camera/Camera.java",
            "src/main/java/com/openggf/sprites/managers/PlayableSpriteMovement.java",
            "src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java",
            "src/main/java/com/openggf/level/spawn/AbstractPlacementManager.java",
            "src/main/java/com/openggf/level/objects/ObjectManager.java",
            "src/main/java/com/openggf/level/rings/RingManager.java",
            "src/main/java/com/openggf/game/sonic2/bumpers/CNZBumperManager.java");

    @Test
    void guardedFilesHaveNoScreenWidth320Literal() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String file : GUARDED_FILES) {
            List<String> lines = Files.readAllLines(Path.of(file));
            for (int i = 0; i < lines.size(); i++) {
                if (SCREEN_320.matcher(lines.get(i)).find()) {
                    offenders.add(file + ":" + (i + 1) + "  " + lines.get(i).trim());
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "Screen-width 320 literal reintroduced in a width-driven file "
                        + "(use camera.getWidth() / SCREEN_WIDTH_PIXELS):\n"
                        + String.join("\n", offenders));
    }
}
