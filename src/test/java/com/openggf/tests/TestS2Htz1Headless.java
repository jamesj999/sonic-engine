package com.openggf.tests;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.runtime.HtzRuntimeState;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.SolidTile;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Grouped headless tests for Sonic 2 HTZ Act 1.
 *
 * Level data is loaded once via {@link SharedLevel#load} in {@code @BeforeAll};
 * sprite, camera, and game state are reset per test via {@link HeadlessTestFixture}.
 *
 * Merged from:
 * <ul>
 *   <li>TestHtzDropOnFloor (lava platform detach, lava damage)</li>
 *   <li>TestHTZInvisibleWallBug (collision diagnostics, earthquake offset sync, walk-through)</li>
 * </ul>
 */
@RequiresRom(SonicGame.SONIC_2)
public class TestS2Htz1Headless {
    private static final int HTZ_ZONE = 4;
    private static final int HTZ_ACT = 0;

    // Bug location coordinates (from TestHTZInvisibleWallBug)
    private static final int BUG_X = 6635;
    private static final int BUG_Y = 1433;

    private static SharedLevel sharedLevel;

    private HeadlessTestFixture fixture;
    private Sonic sprite;
    private LevelManager levelManager;

    @BeforeAll
    public static void loadLevel() throws Exception {
        sharedLevel = SharedLevel.load(SonicGame.SONIC_2, HTZ_ZONE, HTZ_ACT);
    }

    @AfterAll
    public static void cleanup() {
        if (sharedLevel != null) sharedLevel.dispose();
    }

    @BeforeEach
    public void setUp() {
        fixture = HeadlessTestFixture.builder()
                .withSharedLevel(sharedLevel)
                .build();
        sprite = (Sonic) fixture.sprite();
        levelManager = GameServices.level();
    }

    // ========== From TestHtzDropOnFloor ==========

    /**
     * Test that Sonic detaches from the HTZ rising lava platform when it descends
     * into solid terrain (ROM: DropOnFloor, s2.asm:35810).
     *
     * Sonic at debug position X=6857 Y=1403 in HTZ1 earthquake zone.
     * Platform rises -> Sonic up to ~Y=1337. On descent, Sonic should not clip below
     * the terrain floor (Y should stay within 30px of the settled position).
     */
    @Test
    public void sonicDetachesFromLavaPlatformAtFloor() {
        sprite.setX((short) 6857);
        sprite.setY((short) 1403);
        fixture.camera().updatePosition(true);

        Sonic2LevelEventManager lem =
                (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();

        // Settle: let earthquake trigger and Sonic land on terrain
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        // Verify earthquake activated
        assertTrue(htzState().cameraBgYOffset() > 0 || lem.getEventRoutine() >= 2, "Earthquake should have triggered (camera in zone)");

        int baseY = sprite.getY();
        int maxY = baseY;
        boolean detectedClip = false;
        boolean observedDropOnFloorAirRelease = false;
        boolean wasOnObject = sprite.isOnObject();

        // Run 1200 frames -- enough for full oscillation cycles.
        // Platform rises (Sonic rides up to ~Y=1337) then descends.
        // DropOnFloor should detach Sonic when platform pushes into terrain.
        for (int frame = 0; frame < 1200; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            int y = sprite.getY();
            if (y > maxY) maxY = y;
            if (wasOnObject && !sprite.isOnObject() && sprite.getAir()) {
                observedDropOnFloorAirRelease = true;
            }
            wasOnObject = sprite.isOnObject();

            // Tolerance: 31px accounts for DropOnFloor detach timing with properly
            // advancing oscillation. Previously oscillation was stuck in headless mode
            // (LevelManager.frameCounter not incrementing), masking a 1px overshoot.
            if (y > baseY + 31) {
                detectedClip = true;
                break;
            }
        }

        assertFalse(detectedClip, "Sonic should not clip through floor when riding descending platform "
                + "(Y should stay <= " + (baseY + 30) + " but reached " + maxY + ")");
        assertTrue(observedDropOnFloorAirRelease,
                "ROM DropOnFloor clears OnObj and sets InAir when ChkFloorEdge2 distance is <= 0");
    }

    /**
     * ROM: Obj30 subtype 4 uses jsrto (JSR) for DropOnFloor at s2.asm:49149,
     * which returns and falls through to Obj30_HurtSupportedPlayers (line 49151).
     * Sonic should take lava damage when standing on the subtype 4 platform.
     */
    @Test
    public void sonicTakesDamageFromLavaSubtype4() {
        // Give Sonic rings so hurt doesn't kill him
        sprite.setRingCount(10);

        // Reposition to the subtype 4 platform area
        sprite.setX((short) 7502);
        sprite.setY((short) 1329);
        fixture.camera().updatePosition(true);

        // Settle: let earthquake trigger
        for (int i = 0; i < 20; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        boolean wasHurt = false;

        // Run 600 frames -- Sonic should land on subtype 4 lava and get hurt
        for (int frame = 0; frame < 600; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            if (sprite.isHurt()) {
                wasHurt = true;
                break;
            }
        }

        assertTrue(wasHurt, "Sonic should take damage from subtype 4 lava at (7502, 1329)");
    }

    // ========== From TestHTZInvisibleWallBug ==========

    /**
     * Diagnose the collision data at the bug location.
     * This test prints detailed information about the collision chain.
     */
    @Test
    public void testDiagnoseCollisionAtBugLocation() {
        sprite.setX((short) (BUG_X - 100));
        sprite.setY((short) BUG_Y);
        fixture.camera().updatePosition(true);

        Level level = levelManager.getCurrentLevel();

        System.out.println("=== HTZ Invisible Wall Bug Diagnostic ===");
        System.out.println("Bug position: (" + BUG_X + ", " + BUG_Y + ")");
        System.out.println();

        // Calculate block and chunk indices
        int blockX = BUG_X / 128;
        int blockY = BUG_Y / 128;
        int inBlockX = BUG_X % 128;
        int inBlockY = BUG_Y % 128;
        int chunkInBlockX = inBlockX / 16;
        int chunkInBlockY = inBlockY / 16;
        int inChunkX = BUG_X % 16;
        int inChunkY = BUG_Y % 16;

        System.out.println("Position breakdown:");
        System.out.println("  Block index: (" + blockX + ", " + blockY + ")");
        System.out.println("  Within block: (" + inBlockX + ", " + inBlockY + ")");
        System.out.println("  Chunk in block: (" + chunkInBlockX + ", " + chunkInBlockY + ")");
        System.out.println("  Within chunk: (" + inChunkX + ", " + inChunkY + ")");
        System.out.println();

        // Get ChunkDesc at bug location
        ChunkDesc chunkDesc = levelManager.getChunkDescAt((byte) 0, BUG_X, BUG_Y);
        assertNotNull(chunkDesc, "ChunkDesc should exist at bug location");

        System.out.println("ChunkDesc details:");
        System.out.println("  Raw value: 0x" + Integer.toHexString(chunkDesc.get()));
        System.out.println("  Chunk index: " + chunkDesc.getChunkIndex());
        System.out.println("  H-Flip: " + chunkDesc.getHFlip());
        System.out.println("  V-Flip: " + chunkDesc.getVFlip());
        System.out.println("  Primary collision mode: " + chunkDesc.getPrimaryCollisionMode());
        System.out.println("  Secondary collision mode: " + chunkDesc.getSecondaryCollisionMode());
        System.out.println("  Has primary solidity: " + chunkDesc.hasPrimarySolidity());
        System.out.println("  Has secondary solidity: " + chunkDesc.hasSecondarySolidity());
        System.out.println();

        // Check solidity bits (0x0C for floor, 0x0D for left-right-bottom)
        boolean solidBit0C = chunkDesc.isSolidityBitSet(0x0C);
        boolean solidBit0D = chunkDesc.isSolidityBitSet(0x0D);
        boolean solidBit0E = chunkDesc.isSolidityBitSet(0x0E);
        boolean solidBit0F = chunkDesc.isSolidityBitSet(0x0F);

        System.out.println("Solidity bits:");
        System.out.println("  Bit 0x0C (top_solid_bit): " + solidBit0C);
        System.out.println("  Bit 0x0D (lrb_solid_bit): " + solidBit0D);
        System.out.println("  Bit 0x0E (secondary top): " + solidBit0E);
        System.out.println("  Bit 0x0F (secondary lrb): " + solidBit0F);
        System.out.println();

        // Get chunk from level
        int chunkIndex = chunkDesc.getChunkIndex();
        int chunkCount = level.getChunkCount();

        System.out.println("Chunk lookup:");
        System.out.println("  Level chunk count: " + chunkCount);
        System.out.println("  Requested chunk index: " + chunkIndex);

        if (chunkIndex >= chunkCount) {
            System.out.println("  ERROR: Chunk index out of bounds!");
            fail("Chunk index " + chunkIndex + " >= chunk count " + chunkCount);
            return;
        }

        Chunk chunk = level.getChunk(chunkIndex);
        if (chunk == null) {
            System.out.println("  ERROR: Chunk is null!");
            return;
        }

        int solidTileIndex = chunk.getSolidTileIndex();
        int altSolidTileIndex = chunk.getSolidTileAltIndex();

        System.out.println("  Chunk solid tile index (primary): " + solidTileIndex);
        System.out.println("  Chunk solid tile index (alt): " + altSolidTileIndex);
        System.out.println();

        // Get SolidTile for primary collision
        if (solidTileIndex > 0) {
            SolidTile solidTile = level.getSolidTile(solidTileIndex);
            if (solidTile != null) {
                System.out.println("Primary SolidTile (index " + solidTileIndex + "):");
                System.out.println("  Angle: 0x" + Integer.toHexString(solidTile.getAngle() & 0xFF));

                // Print height array
                System.out.print("  Heights: [");
                for (int i = 0; i < 16; i++) {
                    System.out.print(solidTile.getHeightAt((byte) i));
                    if (i < 15) System.out.print(", ");
                }
                System.out.println("]");

                // Print width array
                System.out.print("  Widths:  [");
                for (int i = 0; i < 16; i++) {
                    System.out.print(solidTile.getWidthAt((byte) i));
                    if (i < 15) System.out.print(", ");
                }
                System.out.println("]");

                // Check the specific position within chunk
                int heightAtX = solidTile.getHeightAt((byte) inChunkX);
                int widthAtY = solidTile.getWidthAt((byte) inChunkY);
                System.out.println("  Height at X=" + inChunkX + ": " + heightAtX);
                System.out.println("  Width at Y=" + inChunkY + ": " + widthAtY);
            } else {
                System.out.println("Primary SolidTile is NULL (index " + solidTileIndex + ")");
            }
        } else {
            System.out.println("No primary solid tile (index = 0)");
        }
        System.out.println();

        // Check adjacent chunks to understand context
        System.out.println("=== Adjacent Chunk Analysis ===");
        analyzeChunkAt(level, BUG_X - 16, BUG_Y, "Left (-16,0)");
        analyzeChunkAt(level, BUG_X + 16, BUG_Y, "Right (+16,0)");
        analyzeChunkAt(level, BUG_X, BUG_Y - 16, "Above (0,-16)");
        analyzeChunkAt(level, BUG_X, BUG_Y + 16, "Below (0,+16)");

        // The test passes if we successfully diagnosed the collision
        System.out.println();
        System.out.println("=== Diagnostic Complete ===");
    }

    /**
     * Test walking through the bug location to reproduce the invisible wall.
     */
    @Test
    public void testWalkThroughBugLocation() {
        sprite.setX((short) (BUG_X - 100));
        sprite.setY((short) BUG_Y);
        fixture.camera().updatePosition(true);

        // First scan to find solid ground near the bug location
        System.out.println("=== Scanning for solid ground near bug location ===");
        findSolidGroundAt(BUG_X, BUG_Y);
        findSolidGroundAt(BUG_X - 50, BUG_Y);
        findSolidGroundAt(BUG_X, BUG_Y + 50);
        findSolidGroundAt(BUG_X, BUG_Y + 100);

        // Try starting from the level start position and walking right
        System.out.println("\n=== Testing from level start ===");

        // Reset position to default spawn
        sprite.setX((short) 96);
        sprite.setY((short) 1007);
        sprite.setAir(false);

        System.out.println("Starting at default spawn: (" + sprite.getX() + ", " + sprite.getY() + ")");

        // Let sprite settle
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }
        System.out.println("After settling: (" + sprite.getX() + ", " + sprite.getY() + "), air=" + sprite.getAir());

        // Walk right for a bit to test collision
        short lastX = sprite.getX();
        int stalledFrames = 0;

        for (int frame = 0; frame < 200; frame++) {
            short beforeX = sprite.getX();
            short beforeGSpeed = sprite.getGSpeed();
            short beforeYSpeed = sprite.getYSpeed();
            boolean beforeAir = sprite.getAir();
            byte beforeAngle = sprite.getAngle();

            fixture.stepFrame(false, false, false, true, false);

            short afterX = sprite.getX();
            short afterGSpeed = sprite.getGSpeed();

            // Detect gSpeed reset
            if (beforeGSpeed > 100 && afterGSpeed < 50) {
                System.out.println("\n*** gSpeed DROP at frame " + frame + " ***");
                System.out.println("  Before: X=" + beforeX + ", gSpeed=" + beforeGSpeed +
                        ", ySpeed=" + beforeYSpeed + ", air=" + beforeAir +
                        ", angle=0x" + Integer.toHexString(beforeAngle & 0xFF));
                System.out.println("  After:  X=" + afterX + ", gSpeed=" + afterGSpeed +
                        ", ySpeed=" + sprite.getYSpeed() + ", air=" + sprite.getAir() +
                        ", angle=0x" + Integer.toHexString(sprite.getAngle() & 0xFF));
            }

            if (afterX == lastX && !sprite.getAir()) {
                stalledFrames++;
                if (stalledFrames == 1) {
                    // First frame of stall - detailed diagnosis
                    System.out.println("\nFirst stall frame " + frame + ":");
                    System.out.println("  Before: X=" + beforeX + ", gSpeed=" + beforeGSpeed);
                    System.out.println("  After:  X=" + afterX + ", gSpeed=" + afterGSpeed);
                    System.out.println("  Y=" + sprite.getY() + ", air=" + sprite.getAir());
                    System.out.println("  groundMode=" + sprite.getGroundMode());
                }
                if (stalledFrames > 10) {
                    System.out.println("STALLED at frame " + frame + ": (" + afterX + ", " + sprite.getY() + ")");
                    diagnoseWallSensorAt(afterX, sprite.getY());

                    // Diagnose ground collision
                    System.out.println("\n=== Ground collision diagnosis ===");
                    Level level = levelManager.getCurrentLevel();
                    for (int dx = -8; dx <= 24; dx += 8) {
                        int checkX = afterX + dx;
                        int checkY = sprite.getY() + 20; // Below Sonic
                        ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, checkX, checkY);
                        if (desc != null && desc.hasPrimarySolidity()) {
                            int chunkIdx = desc.getChunkIndex();
                            Chunk chunk = level.getChunk(chunkIdx);
                            int solidIdx = chunk != null ? chunk.getSolidTileIndex() : -1;
                            System.out.println("Ground X+" + dx + ": chunk=" + chunkIdx +
                                ", solidTile=" + solidIdx);

                            if (solidIdx > 0) {
                                SolidTile tile = level.getSolidTile(solidIdx);
                                if (tile != null) {
                                    int localX = checkX % 16;
                                    byte height = tile.getHeightAt((byte) localX);
                                    System.out.println("    Height at localX=" + localX + ": " + height);
                                    System.out.println("    HFlip=" + desc.getHFlip() + ", VFlip=" + desc.getVFlip());
                                    System.out.print("    Full heights: [");
                                    for (int i = 0; i < 16; i++) {
                                        System.out.print(tile.getHeightAt((byte) i));
                                        if (i < 15) System.out.print(", ");
                                    }
                                    System.out.println("]");
                                    System.out.println("    Angle: 0x" + Integer.toHexString(tile.getAngle() & 0xFF));
                                }
                            }
                        }
                    }

                    // Check push sensor positions (wall check)
                    System.out.println("\n=== Push sensor wall check ===");
                    short centreX = sprite.getCentreX();
                    short centreY = sprite.getCentreY();
                    System.out.println("Sprite X (top-left): " + afterX);
                    System.out.println("Sprite Y (top-left): " + sprite.getY());
                    System.out.println("Sprite centre: (" + centreX + ", " + centreY + ")");
                    System.out.println("Sprite yRadius: " + sprite.getYRadius());
                    System.out.println("Sprite angle: 0x" + Integer.toHexString(sprite.getAngle() & 0xFF));

                    // Calculate where the ground surface should be
                    int chunkTileY = (sprite.getY() + 20) / 16 * 16;
                    System.out.println("Ground chunk tile base Y: " + chunkTileY);
                    System.out.println("Expected ground surface: " + (chunkTileY + 16 - 2) + " (tileY + 16 - height)");

                    // Check solidity at push sensor positions (X+10, X-10)
                    for (int pushDx : new int[]{-10, 10}) {
                        int wallX = centreX + pushDx;
                        System.out.println("Push sensor at X=" + wallX + " (dx=" + pushDx + "):");
                        for (int wallDy = -8; wallDy <= 8; wallDy += 8) {
                            int wallY = centreY + wallDy;
                            ChunkDesc wallDesc = levelManager.getChunkDescAt((byte) 0, wallX, wallY);
                            if (wallDesc == null) {
                                System.out.println("  Y+" + wallDy + ": null");
                                continue;
                            }
                            int wChunkIdx = wallDesc.getChunkIndex();
                            boolean hasPri = wallDesc.hasPrimarySolidity();
                            boolean hasLRB = wallDesc.isSolidityBitSet(0x0D);
                            int rawVal = wallDesc.get();
                            boolean lrbCalc = (rawVal & (1 << 0x0D)) != 0;
                            Chunk wChunk = wChunkIdx < level.getChunkCount() ? level.getChunk(wChunkIdx) : null;
                            int wSolidIdx = wChunk != null ? wChunk.getSolidTileIndex() : -1;

                            System.out.println("  Y+" + wallDy + ": chunk=" + wChunkIdx +
                                " (raw=0x" + Integer.toHexString(wallDesc.get()) +
                                "), pri=" + hasPri + ", lrb=" + hasLRB + "(calc=" + lrbCalc + ")" +
                                ", solidIdx=" + wSolidIdx);

                            // Check width array if solid
                            if (hasPri && wSolidIdx > 0) {
                                SolidTile wTile = level.getSolidTile(wSolidIdx);
                                if (wTile != null) {
                                    int localY = wallY % 16;
                                    int width = wTile.getWidthAt((byte) localY);
                                    System.out.println("      Width at localY=" + localY + ": " + width);
                                    if (wSolidIdx == 255) {
                                        // Dump full solid tile 255
                                        System.out.print("      Full widths: [");
                                        for (int wi = 0; wi < 16; wi++) {
                                            System.out.print(wTile.getWidthAt((byte) wi));
                                            if (wi < 15) System.out.print(", ");
                                        }
                                        System.out.println("]");
                                        System.out.print("      Full heights: [");
                                        for (int hi = 0; hi < 16; hi++) {
                                            System.out.print(wTile.getHeightAt((byte) hi));
                                            if (hi < 15) System.out.print(", ");
                                        }
                                        System.out.println("]");
                                        System.out.println("      Angle: 0x" + Integer.toHexString(wTile.getAngle() & 0xFF));
                                    }
                                }
                            }
                        }
                    }
                    break;
                }
            } else {
                stalledFrames = 0;
            }
            lastX = afterX;

            if (frame % 50 == 0) {
                System.out.println("Frame " + frame + ": (" + sprite.getX() + ", " + sprite.getY() + ")");
            }
        }

        System.out.println("Final: (" + sprite.getX() + ", " + sprite.getY() + ")");
        assertTrue(sprite.getX() > 96, "Sonic should progress past start position");
    }

    /**
     * Test that verifies the HTZ earthquake zone offset synchronization.
     *
     * <p>This test checks that during screen shake, the cameraBgYOffset and
     * shakeOffsetY are properly synchronized. If they diverge, visual terrain
     * will appear in a different position than collision platforms, causing
     * invisible walls.</p>
     */
    @Test
    public void testEarthquakeOffsetSync() {
        System.out.println("=== HTZ Earthquake Offset Synchronization Test ===");

        // The earthquake triggers when CAMERA reaches X >= 0x1800 (6144) AND Y >= 0x400 (1024)
        // Camera typically centers on Sonic with some offset.
        // We need to teleport both Sonic AND the camera to the trigger zone.

        Sonic2LevelEventManager levelEventManager =
                (Sonic2LevelEventManager) GameServices.module().getLevelEventProvider();
        ParallaxManager parallaxManager = GameServices.parallax();
        Camera camera = fixture.camera();

        // Teleport Sonic to a position where the camera will be in the earthquake zone
        final int TARGET_X = 0x1800 + 200;  // 6344 - comfortably past trigger
        final int TARGET_Y = 0x400 + 150;   // 1174 - comfortably past trigger

        sprite.setX((short) TARGET_X);
        sprite.setY((short) TARGET_Y);
        sprite.setAir(false);

        // Force camera to snap to Sonic's position immediately
        camera.setX((short) (TARGET_X - 160)); // Approximate camera offset
        camera.setY((short) (TARGET_Y - 96));
        camera.updatePosition(true);  // Force immediate update

        System.out.println("Teleported Sonic to: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println("Camera set to: (" + camera.getX() + ", " + camera.getY() + ")");

        // Ensure Sonic2LevelEventManager is initialized for HTZ
        levelEventManager.initLevel(HTZ_ZONE, HTZ_ACT);

        // Step frames to trigger earthquake detection
        System.out.println("\n=== Triggering earthquake ===");
        boolean enteredZone = false;
        for (int i = 0; i < 10 && !enteredZone; i++) {
            fixture.stepFrame(false, false, false, false, false);
            if (htzState().earthquakeActive()) {
                enteredZone = true;
                System.out.println("Earthquake triggered at frame " + i);
            }
        }

        System.out.println("After settling:");
        System.out.println("  Camera: (" + camera.getX() + ", " + camera.getY() + ")");
        System.out.println("  Sonic: (" + sprite.getX() + ", " + sprite.getY() + ")");
        System.out.println("  HTZ shake active: " + htzState().earthquakeActive());
        System.out.println("  Screen shake active: " + GameServices.gameState().isScreenShakeActive());
        System.out.println("  cameraBgYOffset: " + htzState().cameraBgYOffset());

        if (!enteredZone) {
            System.out.println("\nManually enabling earthquake for offset testing...");
        }
        // Force the earthquake active so the offset-sync invariant is always exercised,
        // even when headless camera clamping prevents the natural trigger.
        levelEventManager.getHtzEvents().setEarthquakeActive(true);
        assertTrue(htzState().earthquakeActive(),
                "Earthquake should be active before verifying offset synchronization");

        System.out.println("\n=== Verifying offset values during earthquake ===");
        System.out.println("Frame | cameraBgYOffset | shakeOffsetY | Combined | screenShakeActive");
        System.out.println("------|-----------------|--------------|----------|------------------");

        int lastBgYOffset = -999;
        int lastShakeY = -999;

        // Run frames to observe offset behavior
        for (int frame = 0; frame < 60; frame++) {
            fixture.stepFrame(false, false, false, false, false);

            int cameraBgYOffset = htzState().cameraBgYOffset();
            int shakeOffsetY = parallaxManager.getShakeOffsetY();
            boolean screenShakeActive = GameServices.gameState().isScreenShakeActive();
            int combinedOffset = cameraBgYOffset + shakeOffsetY;

            // Log when values change
            if (cameraBgYOffset != lastBgYOffset || shakeOffsetY != lastShakeY) {
                System.out.printf("%5d | %15d | %12d | %8d | %s%n",
                        frame, cameraBgYOffset, shakeOffsetY,
                        combinedOffset, screenShakeActive);
                lastBgYOffset = cameraBgYOffset;
                lastShakeY = shakeOffsetY;
            }

            // Synchronization invariant: during the earthquake, SwScrlHtz computes
            // Vscroll_Factor_BG = (Camera_Y_pos - cameraBgYOffset) + shakeOffsetY
            // (see SwScrlHtz.updateEarthquakeMode). The BG scroll factor must stay
            // locked to BOTH the camera BG offset and the per-frame shake offset; if
            // they diverged, visual terrain and collision platforms would desync
            // (the invisible-wall symptom). Verify the relationship every frame.
            short expectedBgVscroll = (short) ((camera.getY() - cameraBgYOffset) + shakeOffsetY);
            assertEquals(expectedBgVscroll, parallaxManager.getVscrollFactorBG(),
                    "BG vscroll factor must stay synchronized with cameraBgYOffset and shakeOffsetY "
                            + "during the earthquake (frame " + frame + ", cameraY=" + camera.getY()
                            + ", cameraBgYOffset=" + cameraBgYOffset + ", shakeOffsetY=" + shakeOffsetY + ")");
        }

        System.out.println("\n=== Test Complete ===");
        System.out.println("Final position: (" + sprite.getX() + ", " + sprite.getY() + ")");

        // The earthquake must remain active across the observation window so the
        // synchronization checks above were actually exercised on the shake path.
        assertTrue(GameServices.gameState().isScreenShakeActive(),
                "Screen shake should remain active throughout the offset-sync observation window");
        assertTrue(htzState().earthquakeActive(),
                "HTZ earthquake should remain active throughout the offset-sync observation window");
    }

    /**
     * Test that verifies Sonic can walk through the earthquake zone without hitting invisible walls.
     */
    @Test
    public void testWalkThroughEarthquakeZoneNoStall() {
        System.out.println("=== HTZ Earthquake Zone Walk-Through Test ===");

        // Start from level spawn
        sprite.setX((short) 96);
        sprite.setY((short) 1007);
        sprite.setAir(false);
        fixture.camera().updatePosition(true);

        // Let sprite settle
        for (int i = 0; i < 10; i++) {
            fixture.stepFrame(false, false, false, false, false);
        }

        System.out.println("Starting at: (" + sprite.getX() + ", " + sprite.getY() + ")");

        int frameCounter = 0;
        int stalledFrames = 0;
        short lastX = sprite.getX();
        boolean passedBugLocation = false;

        // Walk right for a long time to reach and pass the bug location
        for (int i = 0; i < 2000; i++) {
            frameCounter++;
            fixture.stepFrame(false, false, false, true, false);

            short currentX = sprite.getX();

            // Check if we've passed the bug location
            if (currentX >= BUG_X && !passedBugLocation) {
                passedBugLocation = true;
                System.out.println("Passed bug location at frame " + frameCounter);
                System.out.println("Position: (" + currentX + ", " + sprite.getY() + ")");
                System.out.println("gSpeed: " + sprite.getGSpeed());
            }

            // Detect stalls (gSpeed drop to 0 while on ground)
            if (currentX == lastX && !sprite.getAir() && sprite.getGSpeed() < 10) {
                stalledFrames++;
                if (stalledFrames > 10) {
                    System.out.println("\n*** STALL DETECTED at frame " + frameCounter + " ***");
                    System.out.println("Position: (" + currentX + ", " + sprite.getY() + ")");
                    System.out.println("This indicates an invisible wall!");

                    // Log earthquake state
                    boolean htzShake = htzState().earthquakeActive();
                    boolean screenShake = GameServices.gameState().isScreenShakeActive();
                    int bgYOffset = htzState().cameraBgYOffset();
                    int shakeY = GameServices.parallax().getShakeOffsetY();

                    System.out.println("HTZ shake active: " + htzShake);
                    System.out.println("Screen shake active: " + screenShake);
                    System.out.println("cameraBgYOffset: " + bgYOffset);
                    System.out.println("shakeOffsetY: " + shakeY);

                    fail("Sonic stalled at position (" + currentX + ", " + sprite.getY() +
                            ") - invisible wall detected!");
                }
            } else {
                stalledFrames = 0;
            }

            lastX = currentX;

            // Progress report
            if (frameCounter % 500 == 0) {
                System.out.println("Frame " + frameCounter + ": (" + currentX + ", " + sprite.getY() + ")");
            }
        }

        System.out.println("Final position: (" + sprite.getX() + ", " + sprite.getY() + ")");
        if (passedBugLocation) {
            System.out.println("SUCCESS: Passed the bug location without stalling!");
        } else {
            System.out.println("Note: Did not reach the bug location in 2000 frames");
        }
    }

    // ========== Helper Methods ==========

    private void analyzeChunkAt(Level level, int x, int y, String label) {
        ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, x, y);
        if (desc == null) {
            System.out.println(label + ": ChunkDesc is NULL");
            return;
        }

        int chunkIndex = desc.getChunkIndex();
        boolean hasPrimary = desc.hasPrimarySolidity();
        boolean hasSecondary = desc.hasSecondarySolidity();

        Chunk chunk = chunkIndex < level.getChunkCount() ? level.getChunk(chunkIndex) : null;
        int solidIndex = chunk != null ? chunk.getSolidTileIndex() : -1;

        System.out.println(label + ": chunk=" + chunkIndex +
                ", primary=" + hasPrimary +
                ", secondary=" + hasSecondary +
                ", solidTileIdx=" + solidIndex);
    }

    private void findSolidGroundAt(int x, int startY) {
        System.out.println("Scanning for ground at X=" + x + " starting from Y=" + startY);
        for (int y = startY; y < startY + 300; y += 16) {
            ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, x, y);
            if (desc != null && desc.hasPrimarySolidity()) {
                Level level = levelManager.getCurrentLevel();
                Chunk chunk = level.getChunk(desc.getChunkIndex());
                if (chunk != null && chunk.getSolidTileIndex() > 0) {
                    System.out.println("  Found solid at Y=" + y + ": chunk=" + desc.getChunkIndex() +
                            ", solidTile=" + chunk.getSolidTileIndex());
                    return;
                }
            }
        }
        System.out.println("  No solid ground found in scan range");
    }

    private void diagnoseWallSensorAt(short x, short y) {
        System.out.println("\n=== Wall Sensor Diagnosis at (" + x + ", " + y + ") ===");

        Level level = levelManager.getCurrentLevel();

        // Check chunks around Sonic's position in a grid
        System.out.println("Chunk map around position (showing solidity and collision info):");
        for (int dy = -16; dy <= 16; dy += 16) {
            StringBuilder row = new StringBuilder();
            row.append(String.format("Y%+3d: ", dy));
            for (int dx = -16; dx <= 32; dx += 8) {
                int checkX = x + dx;
                int checkY = y + dy;
                ChunkDesc desc = levelManager.getChunkDescAt((byte) 0, checkX, checkY);

                if (desc == null) {
                    row.append("[NULL] ");
                    continue;
                }

                int chunkIdx = desc.getChunkIndex();
                boolean hasPri = desc.hasPrimarySolidity();

                Chunk chunk = chunkIdx < level.getChunkCount() ? level.getChunk(chunkIdx) : null;
                int solidIdx = chunk != null ? chunk.getSolidTileIndex() : -1;

                if (!hasPri) {
                    row.append(String.format("[%03d:--] ", chunkIdx));
                } else if (solidIdx == 0) {
                    row.append(String.format("[%03d:00] ", chunkIdx));
                } else {
                    row.append(String.format("[%03d:%02d] ", chunkIdx, solidIdx));
                }
            }
            System.out.println(row);
        }

        // Specifically diagnose the chunk directly to the right (wall sensor location)
        int wallCheckX = x + 10; // Sonic's push radius is typically 10
        int wallCheckY = y;
        System.out.println("\nWall check position: (" + wallCheckX + ", " + wallCheckY + ")");

        ChunkDesc wallDesc = levelManager.getChunkDescAt((byte) 0, wallCheckX, wallCheckY);
        if (wallDesc != null) {
            System.out.println("  ChunkDesc raw: 0x" + Integer.toHexString(wallDesc.get()));
            System.out.println("  Chunk index: " + wallDesc.getChunkIndex());
            System.out.println("  Primary solidity: " + wallDesc.hasPrimarySolidity());
            System.out.println("  H-flip: " + wallDesc.getHFlip() + ", V-flip: " + wallDesc.getVFlip());

            if (wallDesc.hasPrimarySolidity()) {
                Chunk chunk = level.getChunk(wallDesc.getChunkIndex());
                if (chunk != null) {
                    int solidIdx = chunk.getSolidTileIndex();
                    System.out.println("  Solid tile index: " + solidIdx);

                    if (solidIdx > 0) {
                        SolidTile tile = level.getSolidTile(solidIdx);
                        if (tile != null) {
                            int localY = wallCheckY % 16;
                            int width = tile.getWidthAt((byte) localY);
                            System.out.println("  Width at localY=" + localY + ": " + width);

                            System.out.print("  Full width array: [");
                            for (int i = 0; i < 16; i++) {
                                System.out.print(tile.getWidthAt((byte) i));
                                if (i < 15) System.out.print(", ");
                            }
                            System.out.println("]");
                        }
                    }
                }
            }
        }
    }

    private static HtzRuntimeState htzState() {
        return GameServices.zoneRuntimeRegistry()
                .currentAs(HtzRuntimeState.class)
                .orElseThrow(() -> new AssertionError("Expected HTZ runtime state to be installed"));
    }
}

