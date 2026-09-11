package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for SCZ spawn stability:
 * Sonic should settle onto ObjB2 (Tornado) instead of falling to death.
 *
 * Level data is loaded once via {@link SharedLevel#load} in {@code @BeforeAll};
 * sprite, camera, and game state are reset per test via {@link HeadlessTestFixture}.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestSczSpawnOnTornado {

    private static final int ZONE_SCZ = 8;
    private static final int ACT_1 = 0;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, ZONE_SCZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private LevelManager levelManager;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        levelManager = GameServices.level();
    }

    @Test
    public void sonicLandsOnTornadoAfterSkyChaseSpawn() {
        assertNotNull(levelManager.getObjectManager(), "ObjectManager should be initialized");
        boolean hasTornadoSpawn = levelManager.getActiveObjectSpawns().stream()
                .anyMatch(spawn -> spawn.objectId() == Sonic2ObjectIds.TORNADO);
        assertTrue(hasTornadoSpawn, "SCZ spawn window should contain ObjB2 Tornado");

        boolean rodeTornado = false;
        for (int i = 0; i < 180; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (levelManager.getObjectManager().isRidingObject(fixture.sprite())) {
                rodeTornado = true;
                break;
            }
            if (fixture.sprite().getDead()) {
                break;
            }
        }

        assertFalse(fixture.sprite().getDead(), "Sonic should not die before landing on Tornado");
        assertTrue(rodeTornado, "Sonic should establish riding contact with Tornado after SCZ spawn");
    }

    @Test
    public void sonicStaysOnTornadoWhenRunningRight() {
        boolean rodeTornado = false;
        for (int i = 0; i < 180; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (levelManager.getObjectManager().isRidingObject(fixture.sprite())) {
                rodeTornado = true;
                break;
            }
        }

        assertTrue(rodeTornado, "Precondition: Sonic should be riding Tornado before movement test");

        for (int i = 0; i < 120; i++) {
            fixture.stepFrame(false, false, false, true, false);
            assertFalse(fixture.sprite().getDead(), "Sonic should not die while running on Tornado");
        }

        assertTrue(levelManager.getObjectManager().isRidingObject(fixture.sprite()), "Sonic should still be riding Tornado after running right");
    }
}



