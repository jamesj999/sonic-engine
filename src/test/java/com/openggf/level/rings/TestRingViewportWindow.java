package com.openggf.level.rings;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.SessionManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestRingViewportWindow {
    @ParameterizedTest
    @EnumSource(value = SonicGame.class, names = {"SONIC_1", "SONIC_2", "SONIC_3K"})
    void unsortedPlacementsDoNotHideNearerRings(SonicGame game) {
        try {
            TestEnvironment.configureGameModuleFixture(game);
            RingSpawn farther = new RingSpawn(3528, 900);
            RingSpawn nearer = new RingSpawn(3464, 948);
            RingManager manager = new RingManager(List.of(farther, nearer), null, null, null, null);
            manager.reset(3400);
            assertEquals(List.of(nearer, farther), List.copyOf(manager.getActiveSpawns()));
            manager.resyncSpawnList(List.of(farther, nearer));
            manager.update(3400, null, 0);
            assertEquals(List.of(nearer, farther), List.copyOf(manager.getActiveSpawns()),
                    "Editor resync must preserve the ordering required by ring window searches");
        } finally {
            SessionManager.clear();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "SONIC_2, 320", "SONIC_2, 352", "SONIC_2, 400", "SONIC_2, 528", "SONIC_2, 800",
            "SONIC_3K, 320", "SONIC_3K, 352", "SONIC_3K, 400", "SONIC_3K, 528", "SONIC_3K, 800"
    })
    void ringsCoverViewportWithExclusiveRightMargin(SonicGame game, int width) {
        SonicConfigurationService configuration = SonicConfigurationService.getInstance();
        int previousWidth = configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
        try {
            TestEnvironment.configureGameModuleFixture(game);
            configuration.setSessionOverride(SonicConfiguration.SCREEN_WIDTH_PIXELS, width);
            int cameraX = 0x101; // Deliberately not chunk-aligned.
            RingSpawn behind = new RingSpawn(cameraX - 9, 100);
            RingSpawn left = new RingSpawn(cameraX - 8, 100);
            RingSpawn visibleRight = new RingSpawn(cameraX + width - 1, 100);
            RingSpawn margin = new RingSpawn(cameraX + width + 7, 100);
            RingSpawn endpoint = new RingSpawn(cameraX + width + 8, 100);
            RingManager manager = new RingManager(
                    List.of(behind, left, visibleRight, margin, endpoint), null, null, null, null);

            manager.reset(cameraX);
            assertEquals(List.of(left, visibleRight, margin), List.copyOf(manager.getActiveSpawns()),
                    "Draw/collection window must include the wider screen and retain ROM margins");

            manager.update(cameraX + 1, null, 1);
            assertEquals(List.of(visibleRight, margin, endpoint), List.copyOf(manager.getActiveSpawns()),
                    "Forward scrolling admits the endpoint and retires rings behind the left margin");

            manager.update(cameraX, null, 2);
            assertEquals(List.of(left, visibleRight, margin), List.copyOf(manager.getActiveSpawns()),
                    "Backward scrolling must rebuild the same viewport-sized window");
        } finally {
            configuration.setSessionOverride(SonicConfiguration.SCREEN_WIDTH_PIXELS, previousWidth);
            SessionManager.clear();
        }
    }
}
