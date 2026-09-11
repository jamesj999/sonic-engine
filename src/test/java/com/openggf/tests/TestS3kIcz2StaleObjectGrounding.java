package com.openggf.tests;

import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kIcz2StaleObjectGrounding {
    private static final int SCREENSHOT_TOP_LEFT_X = 2137;
    private static final int SCREENSHOT_TOP_LEFT_Y = 797;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private AbstractPlayableSprite player;

    @BeforeAll
    static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, Sonic3kZoneIds.ZONE_ICZ, 1);
    }

    @AfterAll
    static void cleanup() {
        if (sharedLevel != null) {
            sharedLevel.dispose();
            sharedLevel = null;
        }
    }

    @BeforeEach
    void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        player = fixture.sprite();
    }

    @Test
    void staleObjectStatusDoesNotKeepSonicGroundedInIcz2EmptyAir() {
        player.setCentreX((short) (SCREENSHOT_TOP_LEFT_X + player.getXRadius()));
        player.setCentreY((short) (SCREENSHOT_TOP_LEFT_Y + player.getYRadius()));
        player.setAir(false);
        player.setOnObject(true);
        player.setPushing(true);
        player.setGSpeed((short) 0);
        player.setYSpeed((short) 0);

        fixture.stepFrame(false, false, false, false, false);

        assertTrue(player.getAir(),
                "A stale Status_OnObj bit must not keep Sonic grounded over empty ICZ2 terrain");
        assertFalse(player.isOnObject(),
                "The stale object latch should be cleared when no live support remains");
        assertFalse(player.getPushing(), "Terrain walk-off should clear pushing");
    }
}
