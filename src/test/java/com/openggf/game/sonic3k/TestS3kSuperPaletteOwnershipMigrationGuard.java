package com.openggf.game.sonic3k;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.fail;

class TestS3kSuperPaletteOwnershipMigrationGuard {
    @Test
    void superStatePaletteWriterShouldUseSharedPaletteWriteSupport() throws IOException {
        Path path = Path.of("src/main/java/com/openggf/game/sonic3k/Sonic3kSuperStateController.java");
        String content = Files.readString(path);

        List<String> violations = new ArrayList<>();
        if (content.contains(".fromSegaFormat(paletteData,")) {
            violations.add("direct paletteData fromSegaFormat write");
        }
        if (content.contains("gfx.cachePaletteTexture(palette, target.gpuLine())")) {
            violations.add("direct Super palette texture upload");
        }
        if (!content.contains("S3kPaletteWriteSupport.")) {
            violations.add("missing S3kPaletteWriteSupport usage");
        }

        if (!violations.isEmpty()) {
            fail("S3K Super palette writes should flow through shared palette ownership helpers: "
                    + String.join(", ", violations));
        }
    }
}
