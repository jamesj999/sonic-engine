package com.openggf.tests;

import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance;
import com.openggf.level.BigRingReturnState;
import com.openggf.level.ChunkDesc;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test: reproduce the "in the floor" bug when returning from a
 * special stage to AIZ1 via the first big ring.
 *
 * Simulates the exact flow:
 * 1. Load AIZ1 normally, walk Sonic past X=$1400 (terrain swap point)
 * 2. Record position (simulating big ring save)
 * 3. Reload level via loadCurrentLevel (simulating special stage return)
 * 4. Restore position
 * 5. Step frames â€” Sonic should NOT die or fall through the floor
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kAiz1SpecialStageReturn {
    private static final int ZONE_AIZ = 0;
    private static final int ACT_1 = 0;

    private static Object oldSkipIntros;
    private static SharedLevel sharedLevel;

    @BeforeAll
    public static void loadLevel() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        sharedLevel = SharedLevel.load(SonicGame.SONIC_3K, ZONE_AIZ, ACT_1);
    }

    @AfterAll
    public static void cleanup() {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS,
                oldSkipIntros != null ? oldSkipIntros : false);
        if (sharedLevel != null) sharedLevel.dispose();
    }

    private HeadlessTestFixture fixture;
    private Sonic sprite;

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
    }

    /**
     * Simulate the special stage return flow at a position past the terrain
     * swap point (X=$1400). Teleport directly to avoid walk-time issues.
     *
     * Tests with skip_intros=true config (terrain swap already applied during
     * initial load).
     */
    @Test
    public void specialStageReturn_skipIntrosConfig_sonicDoesNotDie() {
        specialStageReturnFlowTest("skipIntrosConfig", 0x1600, 0x0300);
    }

    /**
     * The REAL scenario â€” config has skip_intros=false (intro was enabled),
     * then special stage return causes a reload where our fix must override
     * to SKIP_INTRO. This matches the user's actual game flow.
     */
    @Test
    public void specialStageReturn_introEnabledConfig_sonicDoesNotDie() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        try {
            specialStageReturnFlowTest("introEnabledConfig", 0x1600, 0x0300);
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        }
    }

    /**
     * Diagnostic: scan terrain at and around the user-reported big ring
     * position to understand what collision data exists there.
     */
    @Test
    public void diagnostic_terrainAtBigRingPosition() {
        int bigRingX = 7107;  // 0x1BC3
        int bigRingY = 1194;  // 0x4AA

        System.out.println("=== TERRAIN SCAN at big ring position ===");
        System.out.println("Big ring: X=0x" + Integer.toHexString(bigRingX)
                + " Y=0x" + Integer.toHexString(bigRingY));

        // Scan a column at X=bigRingX, from Y=0x300 to Y=0x600
        for (int y = 0x300; y <= 0x600; y += 16) {
            ChunkDesc desc = GameServices.level().getChunkDescAt((byte) 0, bigRingX, y);
            String info = "  Y=0x" + Integer.toHexString(y) + ": ";
            if (desc == null) {
                info += "null";
            } else {
                boolean primarySolid = desc.hasPrimarySolidity();
                int chunkIdx = desc.getChunkIndex();
                info += "chunk=0x" + Integer.toHexString(chunkIdx)
                        + " primarySolid=" + primarySolid;
                if (primarySolid && chunkIdx >= 0
                        && chunkIdx < GameServices.level().getCurrentLevel().getChunkCount()) {
                    var chunk = GameServices.level().getCurrentLevel().getChunk(chunkIdx);
                    info += " solidTileIdx=" + chunk.getSolidTileIndex();
                }
            }
            System.out.println(info);
        }

        // Also scan nearby X positions
        System.out.println("\n=== HORIZONTAL SCAN at Y=0x4AA ===");
        for (int x = bigRingX - 128; x <= bigRingX + 128; x += 32) {
            ChunkDesc desc = GameServices.level().getChunkDescAt((byte) 0, x, bigRingY);
            String info = "  X=0x" + Integer.toHexString(x) + ": ";
            if (desc == null) {
                info += "null";
            } else {
                info += "chunk=0x" + Integer.toHexString(desc.getChunkIndex())
                        + " primarySolid=" + desc.hasPrimarySolidity();
            }
            System.out.println(info);
        }

        // Check secondary (path B) collision too
        System.out.println("\n=== SECONDARY PATH SCAN at X=0x1BC3 ===");
        int secondaryTilesInspected = 0;
        int secondarySolidBelowRing = 0;
        for (int y = 0x400; y <= 0x520; y += 16) {
            ChunkDesc desc = GameServices.level().getChunkDescAt((byte) 1, bigRingX, y);
            String info = "  Y=0x" + Integer.toHexString(y) + ": ";
            if (desc == null) {
                info += "null";
            } else {
                secondaryTilesInspected++;
                boolean secondarySolid = desc.hasSecondarySolidity();
                if (secondarySolid && y >= bigRingY) {
                    secondarySolidBelowRing++;
                }
                info += "chunk=0x" + Integer.toHexString(desc.getChunkIndex())
                        + " secondarySolid=" + secondarySolid;
            }
            System.out.println(info);
        }

        // Honest oracle: this is a diagnostic scan, not a behavioral test (the sibling
        // specialStageReturn_* tests cover the real return/landing flow). Assert the
        // scan actually resolved the level and inspected terrain at the big-ring
        // secondary-path column, so the diagnostic can't silently no-op.
        assertNotNull(GameServices.level().getCurrentLevel(),
                "Level must be loaded for the big-ring terrain scan");
        assertTrue(secondaryTilesInspected > 0,
                "Secondary-path scan must inspect at least one chunk at the big-ring column");
        // characterization: current terrain state at big-ring column. The secondary
        // (path B) plane currently has NO solid terrain below the big ring; pin this
        // observed value so a change in collision data at this column is surfaced.
        assertEquals(0, secondarySolidBelowRing,
                "Secondary-path solid-below-ring count changed from the observed value (0)");
    }

    /**
     * User-reported position of the first big ring in AIZ1: X~7107 Y~1194.
     * This is at X=0x1BC3, Y=0x4AA â€” well past the terrain swap point.
     */
    /**
     * The exact user-reported scenario: big ring at (7107, 1194) on the
     * secondary collision path. Tests ONLY the reload flow â€” the initial
     * SharedLevel won't have objects spawned at this position since the
     * camera was elsewhere, but loadCurrentLevel() respawns objects near
     * the big ring return position.
     */
    @Test
    public void specialStageReturn_atActualBigRingPosition_sonicDoesNotDie() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        try {
            // Save big ring position directly (simulating Sonic touching the ring)
            LevelManager lm = GameServices.level();
            int bigRingX = 7107, bigRingY = 1194;
            // Camera would be roughly centred on player
            int camX = bigRingX - 160, camY = bigRingY - 96;
            // Secondary path: player was past plane switchers
            byte topBit = 0x0E, lrbBit = 0x0F;
            lm.saveBigRingReturn(new BigRingReturnState(bigRingX, bigRingY, camX, camY, 10,
                    topBit, lrbBit, 1194 + 200, 0));

            System.out.println("=== bigRingPos: saved position X=0x"
                    + Integer.toHexString(bigRingX) + " Y=0x"
                    + Integer.toHexString(bigRingY)
                    + " solidBits=0x0E/0x0F ===");

            // Reload level (simulating special stage return)
            lm.loadCurrentLevel();
            GroundSensor.setLevelManager(lm);

            System.out.println("BigRingReturn active: " + lm.hasBigRingReturn());
            System.out.println("Sonic after reload: centreX=0x"
                    + Integer.toHexString(sprite.getCentreX())
                    + " centreY=0x" + Integer.toHexString(sprite.getCentreY())
                    + " topSolidBit=0x" + Integer.toHexString(sprite.getTopSolidBit())
                    + " lrbSolidBit=0x" + Integer.toHexString(sprite.getLrbSolidBit()));

            assertEquals(bigRingY - 0x60, fixture.camera().getY(),
                    "Return load should apply Saved2_camera_max_Y_pos before its first camera snap");

            // Simulate enterTitleCardFromResults: restore position + solid bits
            assertTrue(lm.hasBigRingReturn(), "BigRingReturn should be active after reload");
            lm.getBigRingReturn().restoreToPlayer(sprite, fixture.camera(), lm.getLevelGamestate());
            lm.clearBigRingReturn();

            sprite.setXSpeed((short) 0);
            sprite.setYSpeed((short) 0);
            sprite.setGSpeed((short) 0);
            sprite.setAir(false);
            sprite.setDead(false);
            sprite.setHurt(false);

            System.out.println("After restore: centreX=0x"
                    + Integer.toHexString(sprite.getCentreX())
                    + " centreY=0x" + Integer.toHexString(sprite.getCentreY())
                    + " topSolidBit=0x" + Integer.toHexString(sprite.getTopSolidBit())
                    + " lrbSolidBit=0x" + Integer.toHexString(sprite.getLrbSolidBit()));

            assertEquals(bigRingY - 0x60, fixture.camera().getY(),
                    "Camera should use the ROM's player-centred return position after applying saved max Y");

            // Ground snap with correct solid bits
            GameServices.collision().resolveGroundAttachment(sprite, 14, () -> false);
            System.out.println("After snap: centreY=0x"
                    + Integer.toHexString(sprite.getCentreY())
                    + " air=" + sprite.getAir());

            Camera cam = fixture.camera();
            System.out.println("Camera bounds: minX=" + cam.getMinX()
                    + " maxX=" + cam.getMaxX()
                    + " minY=" + cam.getMinY()
                    + " maxY=" + cam.getMaxY());
            System.out.println("Camera pos: X=" + cam.getX() + " Y=" + cam.getY());
            System.out.println("Level maxY=" + lm.getCurrentLevel().getMaxY());

            // Step 60 frames
            for (int frame = 0; frame < 60; frame++) {
                fixture.stepFrame(false, false, false, false, false);
                if (sprite.getDead()) {
                    fail("bigRingPos: Sonic DIED on frame " + frame
                            + " centreX=0x" + Integer.toHexString(sprite.getCentreX())
                            + " centreY=0x" + Integer.toHexString(sprite.getCentreY())
                            + " air=" + sprite.getAir()
                            + " topSolidBit=0x" + Integer.toHexString(sprite.getTopSolidBit()));
                }
            }
            System.out.println("=== bigRingPos ALIVE after 60 frames ===");
            assertFalse(sprite.getDead(), "bigRingPos: Sonic should not be dead");
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        }
    }

    @Test
    public void specialStageReturn_preservesBrokenMonitorRespawnState() throws Exception {
        LevelManager lm = GameServices.level();
        ObjectManager objectManager = lm.getObjectManager();
        ObjectSpawn monitor = lm.getCurrentLevel().getObjects().stream()
                .filter(spawn -> (spawn.objectId() & 0xFF) == 0x01)
                .findFirst()
                .orElseThrow(() -> new AssertionError("AIZ1 should contain a monitor spawn"));

        fixture.camera().setX((short) (monitor.x() - 0xA0));
        fixture.camera().setY((short) (monitor.y() - 0x60));
        objectManager.reset(fixture.camera().getX());
        assertTrue(objectManager.getActiveSpawns().contains(monitor),
                "The selected monitor should be in the active placement window");

        objectManager.markRemembered(monitor);
        assertTrue(objectManager.isRemembered(monitor),
                "The setup monitor should be marked as broken before entering the special stage");

        lm.saveBigRingReturn(new BigRingReturnState(
                monitor.x(), monitor.y(),
                monitor.x() - 0xA0, monitor.y() - 0x60, sprite.getRingCount(),
                sprite.getTopSolidBit(), sprite.getLrbSolidBit(), fixture.camera().getMaxY(), 0));
        lm.loadCurrentLevel();

        ObjectSpawn reloadedMonitor = lm.getCurrentLevel().getObjects().stream()
                .filter(spawn -> (spawn.objectId() & 0xFF) == 0x01)
                .filter(spawn -> spawn.x() == monitor.x() && spawn.y() == monitor.y())
                .findFirst()
                .orElseThrow(() -> new AssertionError("The saved monitor should exist after reload"));
        ObjectManager reloadedObjectManager = lm.getObjectManager();
        assertTrue(reloadedObjectManager.isRemembered(reloadedMonitor),
                "A monitor broken before the special stage must remain broken on return");
        reloadedObjectManager.preloadInitialSpawnsForHydration();
        ObjectInstance reloadedInstance = reloadedObjectManager.getActiveObjects().stream()
                .filter(instance -> reloadedMonitor.equals(instance.getSpawn()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The remembered monitor should remain materialized"));
        Sonic3kMonitorObjectInstance reloadedMonitorInstance =
                assertInstanceOf(Sonic3kMonitorObjectInstance.class, reloadedInstance);
        reloadedMonitorInstance.update(0, sprite);
        assertEquals(0, reloadedMonitorInstance.getCollisionFlags(),
                "A remembered monitor should materialize as a broken shell, not an intact monitor");
    }

    private void specialStageReturnFlowTest(String label, int teleportX, int teleportY) {
        // Place Sonic at the specified position and snap to ground.
        // This simulates the player standing near a big ring.
        sprite.setCentreX((short) teleportX);
        sprite.setCentreY((short) teleportY);
        sprite.setAir(false);
        fixture.camera().updatePosition(true);
        GameServices.collision().resolveGroundAttachment(sprite, 14, () -> false);

        System.out.println("=== " + label + " INITIAL PLACEMENT ===");
        System.out.println("Requested: X=0x" + Integer.toHexString(teleportX)
                + " Y=0x" + Integer.toHexString(teleportY));
        System.out.println("After snap: centreX=0x" + Integer.toHexString(sprite.getCentreX())
                + " centreY=0x" + Integer.toHexString(sprite.getCentreY())
                + " air=" + sprite.getAir());

        // Step a few frames to confirm stable footing
        for (int frame = 0; frame < 10; frame++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        assertFalse(sprite.getDead(), label + ": Sonic should not die at initial position"
                        + " centreX=0x" + Integer.toHexString(sprite.getCentreX())
                        + " centreY=0x" + Integer.toHexString(sprite.getCentreY())
                        + " air=" + sprite.getAir());

        // Record grounded position (may have shifted slightly from snap)
        int savedCentreX = sprite.getCentreX();
        int savedCentreY = sprite.getCentreY();
        int savedCameraX = fixture.camera().getX();
        int savedCameraY = fixture.camera().getY();
        int savedRings = sprite.getRingCount();

        System.out.println("=== " + label + " SAVED POSITION ===");
        System.out.println("Centre: X=0x" + Integer.toHexString(savedCentreX)
                + " Y=0x" + Integer.toHexString(savedCentreY));
        System.out.println("Camera: X=0x" + Integer.toHexString(savedCameraX)
                + " Y=0x" + Integer.toHexString(savedCameraY));
        System.out.println("Air: " + sprite.getAir()
                + " Dead: " + sprite.getDead());

        // Save big ring return position
        LevelManager lm = GameServices.level();
        lm.saveBigRingReturn(new BigRingReturnState(savedCentreX, savedCentreY,
                savedCameraX, savedCameraY, savedRings,
                sprite.getTopSolidBit(), sprite.getLrbSolidBit(),
                fixture.camera().getMaxY(), 0));

        // ---- Simulate doExitResultsScreen flow ----
        lm.loadCurrentLevel();
        GroundSensor.setLevelManager(lm);

        System.out.println("=== " + label + " POST-RELOAD ===");
        System.out.println("BigRingReturn active: " + lm.hasBigRingReturn());
        System.out.println("Sonic centre: X=0x" + Integer.toHexString(sprite.getCentreX())
                + " Y=0x" + Integer.toHexString(sprite.getCentreY()));

        // Simulate enterTitleCardFromResults
        if (lm.hasBigRingReturn()) {
            lm.getBigRingReturn().restoreToPlayer(sprite, fixture.camera(), lm.getLevelGamestate());
            lm.clearBigRingReturn();
        } else {
            System.out.println("WARNING: BigRingReturn was NOT active after reload!");
        }
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
        sprite.setAir(false);
        sprite.setDead(false);
        sprite.setHurt(false);

        boolean hasCollision = hasCollisionBelow(savedCentreX, savedCentreY);
        System.out.println("Has collision below restored pos: " + hasCollision);

        // Ground snap
        GameServices.collision().resolveGroundAttachment(sprite, 14, () -> false);
        System.out.println("After ground snap: centreY=0x"
                + Integer.toHexString(sprite.getCentreY())
                + " air=" + sprite.getAir());

        // Step 60 frames â€” check for death
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, false, false);
            if (sprite.getDead()) {
                fail(label + ": Sonic DIED on frame " + frame
                        + " after special stage return!"
                        + " centreX=0x" + Integer.toHexString(sprite.getCentreX())
                        + " centreY=0x" + Integer.toHexString(sprite.getCentreY())
                        + " air=" + sprite.getAir()
                        + " Y(top-left)=" + sprite.getY());
            }
        }
        System.out.println("=== " + label + " ALIVE after 60 frames ===");
        assertFalse(sprite.getDead(), label + ": Sonic should not be dead");
    }

    private boolean hasCollisionBelow(int worldX, int worldY) {
        LevelManager lm = GameServices.level();
        if (lm.getCurrentLevel() == null) return false;
        int endY = worldY + 256;
        for (int y = worldY; y <= endY; y += 16) {
            ChunkDesc desc = lm.getChunkDescAt((byte) 0, worldX, y);
            if (desc != null && desc.hasPrimarySolidity()) {
                int chunkIdx = desc.getChunkIndex();
                if (chunkIdx >= 0 && chunkIdx < lm.getCurrentLevel().getChunkCount()) {
                    var chunk = lm.getCurrentLevel().getChunk(chunkIdx);
                    if (chunk.getSolidTileIndex() != 0) return true;
                }
            }
        }
        return false;
    }
}
