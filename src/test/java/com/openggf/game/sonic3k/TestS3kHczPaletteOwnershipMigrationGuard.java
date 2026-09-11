package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.fail;

class TestS3kHczPaletteOwnershipMigrationGuard {

    private static final String[] GUARDED_FILES = {
            "src/main/java/com/openggf/game/sonic3k/objects/bosses/HczEndBossInstance.java",
            "src/main/java/com/openggf/game/sonic3k/objects/HczMinibossInstance.java",
            "src/main/java/com/openggf/game/sonic3k/events/Sonic3kHCZEvents.java"
    };

    private static final String[] FORBIDDEN_SNIPPETS = {
            "services().updatePalette(",
            "palette.getColor(FLASH_INDICES[i]).fromSegaFormat(",
            "graphics.cachePaletteTexture(palette, 1);",
            "palette.setColor(PALETTE_COLOR_OFFSET + i, color);",
            "cachePaletteTextureIfReady(palette, PALETTE_LINE);",
            "bossPal.fromSegaFormat(line);",
            "graphics.cacheUnderwaterPaletteTexture(uwPalettes, level.getPalette(0));"
    };

    @Test
    void hczBossPaletteWriters_shouldUseSharedPaletteWriteSupport() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String file : GUARDED_FILES) {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }

            String content = Files.readString(path);
            for (String forbiddenSnippet : FORBIDDEN_SNIPPETS) {
                if (content.contains(forbiddenSnippet)) {
                    violations.add(file + " contains `" + forbiddenSnippet + "`");
                }
            }
            if (!content.contains("S3kPaletteWriteSupport.")) {
                violations.add(file + " does not use S3kPaletteWriteSupport");
            }
        }

        if (!violations.isEmpty()) {
            fail("HCZ boss palette writes should flow through shared palette ownership helpers:\n  "
                    + String.join("\n  ", new TreeSet<>(violations)));
        }
    }
}
