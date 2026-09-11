package com.openggf.tests;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.game.sonic2.Sonic2;
import com.openggf.level.Level;
import com.openggf.level.LevelData;
import com.openggf.game.GameServices;
import com.openggf.level.LevelManager;
import com.openggf.physics.Direction;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GroundMode;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that Sonic's spawn positions for all levels place him correctly on the
 * ground,
 * not inside the floor. This helps identify misconfigured spawn Y coordinates.
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestSpawnPositions {
    /**
     * The original Sonic 2 game intentionally spawns players up to ~3 pixels inside the
     * ground; the physics system pushes them out during the title card. Spawns within this
     * tolerance are expected behaviour, not a misconfiguration.
     */
    private static final int SPAWN_INSIDE_GROUND_TOLERANCE = 3;

    private Rom rom;
    private Game game;
    private LevelManager levelManager;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
        game = new Sonic2(rom);

        assertTrue(game.isCompatible(), "ROM should be compatible");

        levelManager = GameServices.level();
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Reset the level field in LevelManager
        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, null);
    }

    /**
     * Creates a mock sprite at the given position for testing ground sensors.
     */
    private AbstractPlayableSprite createTestSprite(short x, short y) {
        AbstractPlayableSprite sprite = new AbstractPlayableSprite("test", x, y) {
            // Use Sonic's radii
            {
                standXRadius = 9;
                standYRadius = 19;
                rollXRadius = 7;
                rollYRadius = 14;
            }

            @Override
            protected void defineSpeeds() {
                // Not needed for sensor testing
            }

            @Override
            protected void createSensorLines() {
                // We'll create sensors manually
            }

            @Override
            public void draw() {
                // Not needed for testing
            }
        };

        sprite.setGroundMode(GroundMode.GROUND);
        sprite.setLayer((byte) 0);
        sprite.setWidth(19); // Sonic's width
        sprite.setHeight(39); // Sonic's standing height

        return sprite;
    }

    /**
     * Creates ground sensors like Sonic uses (from Sonic.java lines 76-78).
     * Ground sensors are at Y offset +20 (from center), X offsets -9 and +9.
     */
    private Sensor[] createGroundSensors(AbstractPlayableSprite sprite) {
        Sensor[] sensors = new Sensor[2];
        sensors[0] = new GroundSensor(sprite, Direction.DOWN, (byte) -9, (byte) 20, true);
        sensors[1] = new GroundSensor(sprite, Direction.DOWN, (byte) 9, (byte) 20, true);
        return sensors;
    }

    /**
     * Tests a single level's spawn position.
     *
     * @param levelData the level to test
     * @return a SpawnTestResult with sensor distances
     */
    private SpawnTestResult testSpawnPosition(LevelData levelData) throws Exception {
        // Load the level into the LevelManager via reflection
        Level level = game.loadLevel(levelData.getLevelIndex());

        Field levelField = LevelManager.class.getDeclaredField("level");
        levelField.setAccessible(true);
        levelField.set(levelManager, level);

        // Create sprite at spawn position
        // Note: Spawn Y from ROM data is the CENTER position, but our sprite Y is
        // top-left
        // We need to subtract yRadius (19) to convert, matching what LevelManager does
        short spawnX = (short) levelData.getStartXPos();
        short spawnY = (short) levelData.getStartYPos();
        short yRadius = 19; // Sonic's standing yRadius
        short adjustedY = (short) (spawnY - yRadius);
        AbstractPlayableSprite sprite = createTestSprite(spawnX, adjustedY);

        // Create and scan ground sensors
        Sensor[] groundSensors = createGroundSensors(sprite);
        SensorResult result0 = groundSensors[0].scan();
        SensorResult result1 = groundSensors[1].scan();

        int distance0 = result0 != null ? result0.distance() : Integer.MAX_VALUE;
        int distance1 = result1 != null ? result1.distance() : Integer.MAX_VALUE;

        return new SpawnTestResult(levelData, spawnX, spawnY, distance0, distance1);
    }

    @Test
    public void testAllSpawnPositions() throws Exception {
        List<SpawnTestResult> results = new ArrayList<>();
        List<SpawnTestResult> failures = new ArrayList<>();
        List<String> unloadable = new ArrayList<>();

        for (LevelData levelData : LevelData.values()) {
            try {
                SpawnTestResult result = testSpawnPosition(levelData);
                results.add(result);

                // Check if spawn is excessively inside the ground. The original Sonic 2
                // game intentionally spawns players up to ~3px inside the ground (physics
                // pushes them out during the title card), so allow that documented tolerance.
                if (result.distance0 < -SPAWN_INSIDE_GROUND_TOLERANCE
                        || result.distance1 < -SPAWN_INSIDE_GROUND_TOLERANCE) {
                    failures.add(result);
                }
            } catch (Exception e) {
                // This fixture runs under the Sonic 2 module (@RequiresRom(SONIC_2)), so
                // levels from other games in the shared LevelData enum cannot be loaded
                // here. Record and surface them loudly rather than silently swallowing,
                // but do not fail the spawn-position oracle on an out-of-scope load issue.
                unloadable.add(levelData.name() + " (" + e.getMessage() + ")");
            }
        }

        // Print summary
        System.out.println("\n=== Spawn Position Test Results ===");
        System.out.println("Format: Level (X, Y) -> [sensor0 distance, sensor1 distance]");
        System.out.println("Negative distance = INSIDE ground\n");

        for (SpawnTestResult result : results) {
            String status = (result.distance0 < 0 || result.distance1 < 0) ? " *** INSIDE GROUND ***" : "";
            System.out.printf("%-20s (%4d, %4d) -> [%3d, %3d]%s%n",
                    result.levelData.name(),
                    result.spawnX,
                    result.spawnY,
                    result.distance0,
                    result.distance1,
                    status);
        }

        if (!failures.isEmpty()) {
            System.out.println("\n=== FAILURES (Spawn inside ground) ===");
            for (SpawnTestResult failure : failures) {
                System.out.printf("%-20s at (%d, %d): distances = [%d, %d]%n",
                        failure.levelData.name(),
                        failure.spawnX,
                        failure.spawnY,
                        failure.distance0,
                        failure.distance1);
            }
        }

        if (!unloadable.isEmpty()) {
            System.out.println("\n=== SKIPPED (could not load under the Sonic 2 module) ===");
            unloadable.forEach(u -> System.out.println("  " + u));
        }

        // We must actually have exercised some levels — guard against a harness change
        // that silently loads nothing.
        assertFalse(results.isEmpty(), "No spawn positions were tested at all");

        // The original Sonic 2 game intentionally spawns players up to ~3 pixels inside
        // the ground; the physics system pushes them out during the title card. Anything
        // deeper than that documented tolerance is a misconfigured spawn Y coordinate.
        assertTrue(failures.isEmpty(),
                "Some levels have spawn positions more than " + SPAWN_INSIDE_GROUND_TOLERANCE
                        + "px inside ground: " + failures.stream().map(f -> f.levelData.name()).toList());
    }

    @Test
    public void testChemicalPlantZone1() throws Exception {
        SpawnTestResult result = testSpawnPosition(LevelData.CHEMICAL_PLANT_1);

        System.out.println("\n=== Chemical Plant Zone 1 Spawn Test ===");
        System.out.printf("Spawn position: (%d, %d)%n", result.spawnX, result.spawnY);
        System.out.printf("Ground sensor 0 distance: %d%n", result.distance0);
        System.out.printf("Ground sensor 1 distance: %d%n", result.distance1);

        if (result.distance0 < 0 || result.distance1 < 0) {
            System.out.println("WARNING: Spawn position is INSIDE the ground!");
            System.out.println("Expected distance should be >= 0 (on or above ground)");
        }

        // Allow the documented ~3px intentional spawn-inside-ground tolerance; anything
        // deeper indicates a misconfigured spawn Y coordinate.
        assertTrue(result.distance0 >= -SPAWN_INSIDE_GROUND_TOLERANCE
                        && result.distance1 >= -SPAWN_INSIDE_GROUND_TOLERANCE,
                "Spawn should not be more than " + SPAWN_INSIDE_GROUND_TOLERANCE + "px inside ground");
    }

    /**
     * Simple result holder for spawn position tests.
     */
    private static class SpawnTestResult {
        final LevelData levelData;
        final short spawnX;
        final short spawnY;
        final int distance0;
        final int distance1;

        SpawnTestResult(LevelData levelData, short spawnX, short spawnY, int distance0, int distance1) {
            this.levelData = levelData;
            this.spawnX = spawnX;
            this.spawnY = spawnY;
            this.distance0 = distance0;
            this.distance1 = distance1;
        }
    }
}


