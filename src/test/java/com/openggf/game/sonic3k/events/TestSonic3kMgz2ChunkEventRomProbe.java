package com.openggf.game.sonic3k.events;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.tests.SharedLevel;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kMgz2ChunkEventRomProbe {

    private static SharedLevel sharedLevel;
    private static Object oldSkipIntros;

    @BeforeAll
    static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_MGZ, 1);
    }

    @AfterAll
    static void cleanup() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @Test
    void firstChunkTriggerArea_usesRomTargetBlockIdsInVisibleForeground() {
        Level level = sharedLevel.level();
        Map map = level.getMap();
        boolean foundMutatedBlock = false;
        StringBuilder nearby = new StringBuilder();
        for (int y = 7; y <= 12; y++) {
            nearby.append("y=").append(y).append(':');
            for (int x = 27; x <= 34; x++) {
                int blockIndex = map.getValue(0, x, y) & 0xFF;
                nearby.append(' ').append(String.format("%02X", blockIndex));
                if (blockIndex == 0xB1 || blockIndex == 0xEA) {
                    foundMutatedBlock = true;
                }
            }
            nearby.append('\n');
        }
        List<String> occurrences = new ArrayList<>();
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                int blockIndex = map.getValue(0, x, y) & 0xFF;
                if (blockIndex == 0xB1 || blockIndex == 0xEA) {
                    occurrences.add(String.format("(%d,%d)=%02X", x, y, blockIndex));
                }
            }
        }
        assertTrue(foundMutatedBlock,
                "Expected visible MGZ2 foreground near the first chunk trigger to reference block $B1 or $EA."
                        + "\nNearby block ids:\n" + nearby
                        + "Occurrences: " + occurrences);
    }
}
