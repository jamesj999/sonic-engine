package com.openggf.game.sonic3k;

import com.openggf.game.GameServices;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.AizIntroArtLoader;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlas;
import com.openggf.level.LevelManager;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.physics.GroundSensor;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Diagnostic test for the AIZ intro Tornado plane sprite art.
 * Investigates two corrupt Tails' face patterns (tiles 26-41, piece 4 of frame 0).
 *
 * Checks:
 * 1. KosinskiM decompression correctness (size, non-zero tiles)
 * 2. Mapping frame 0 piece layout and tile index bounds
 * 3. Pattern atlas collision between level patterns and intro patterns
 * 4. Duplicate/zero patterns in the face area
 */
@RequiresRom(SonicGame.SONIC_3K)
public class TestAizPlaneArtDiag {
    @BeforeEach
    public void setUp() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, false);
        GraphicsManager.getInstance().initHeadless();
    }

    /**
     * Verify KosinskiM decompression produces exactly 136 tiles (4352 bytes).
     */
    @Test
    public void planeArtDecompressedSizeIs136Tiles() throws Exception {
        byte[] data = decompressPlaneArt();
        System.out.println("Plane art decompressed size: " + data.length + " bytes ("
                + (data.length / 32) + " tiles)");
        assertEquals(4352, data.length, "Plane art should be 136 tiles (4352 bytes)");
    }

    /**
     * Verify tiles 26-41 (Tails' face area in piece 4) are not all zeros.
     */
    @Test
    public void tailsFaceTilesAreNotEmpty() throws Exception {
        byte[] data = decompressPlaneArt();
        int emptyCount = 0;
        for (int t = 26; t <= 41; t++) {
            boolean allZero = true;
            int offset = t * 32;
            for (int b = 0; b < 32; b++) {
                if (data[offset + b] != 0) {
                    allZero = false;
                    break;
                }
            }
            if (allZero) {
                System.out.println("WARNING: tile " + t + " is all zeros (empty)");
                emptyCount++;
            }
        }
        assertEquals(0, emptyCount, "No face tiles should be empty");
    }

    /**
     * Hex dump all 16 face tiles and look for suspicious patterns:
     * - tiles that are identical to each other (unexpected duplication)
     * - tiles that match tiles outside the face area (data mix-up)
     */
    @Test
    public void dumpFaceTilesAndCheckForDuplication() throws Exception {
        byte[] data = decompressPlaneArt();
        int totalTiles = data.length / 32;

        System.out.println("=== TAILS FACE TILES (26-41) ===");
        for (int t = 26; t <= 41; t++) {
            int offset = t * 32;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Tile %3d: ", t));
            for (int b = 0; b < 32; b++) {
                sb.append(String.format("%02X", data[offset + b] & 0xFF));
                if (b % 4 == 3) sb.append(' ');
            }
            // Count non-zero pixels (each byte = 2 pixels in 4bpp)
            int nonZero = 0;
            for (int b = 0; b < 32; b++) {
                int hi = (data[offset + b] >> 4) & 0xF;
                int lo = data[offset + b] & 0xF;
                if (hi != 0) nonZero++;
                if (lo != 0) nonZero++;
            }
            sb.append(" (nonzero pixels: ").append(nonZero).append("/64)");
            System.out.println(sb);
        }

        // Check for duplicate tiles within the face area
        System.out.println("\n=== DUPLICATE CHECK (face tiles) ===");
        int dupeCount = 0;
        for (int i = 26; i <= 41; i++) {
            for (int j = i + 1; j <= 41; j++) {
                if (tilesEqual(data, i, j)) {
                    System.out.println("DUPLICATE: tile " + i + " == tile " + j);
                    dupeCount++;
                }
            }
        }
        System.out.println("Found " + dupeCount + " duplicate pairs in face area");

        // Check if any face tiles match tiles outside the face area
        System.out.println("\n=== CROSS-MATCH CHECK (face tiles vs rest) ===");
        int crossMatches = 0;
        for (int face = 26; face <= 41; face++) {
            for (int other = 0; other < totalTiles; other++) {
                if (other >= 26 && other <= 41) continue; // skip face range
                if (tilesEqual(data, face, other)) {
                    System.out.println("CROSS-MATCH: face tile " + face + " == tile " + other);
                    crossMatches++;
                }
            }
        }
        System.out.println("Found " + crossMatches + " cross-matches with non-face tiles");
    }

    /**
     * Parse mapping frame 0 and verify all piece tile indices are in bounds.
     * Also dump the complete piece layout.
     */
    @Test
    public void mappingFrame0TileIndicesInBounds() throws Exception {
        byte[] artData = decompressPlaneArt();
        int totalPatterns = artData.length / 32;

        RomByteReader reader = RomByteReader.fromRom(GameServices.rom().getRom());
        List<SpriteMappingFrame> frames = AizIntroArtLoader.loadS3kMappingFrames(
                reader, Sonic3kConstants.MAP_AIZ_INTRO_PLANE_ADDR);

        assertFalse(frames.isEmpty(), "Should have at least 1 mapping frame");
        SpriteMappingFrame frame0 = frames.get(0);

        System.out.println("=== PLANE FRAME 0: " + frame0.pieces().size() + " pieces ===");
        int maxTileUsed = 0;
        boolean anyOob = false;

        for (int i = 0; i < frame0.pieces().size(); i++) {
            SpriteMappingPiece piece = frame0.pieces().get(i);
            int tileCount = piece.widthTiles() * piece.heightTiles();
            int lastTile = piece.tileIndex() + tileCount - 1;
            maxTileUsed = Math.max(maxTileUsed, lastTile);
            boolean oob = lastTile >= totalPatterns;
            if (oob) anyOob = true;

            System.out.println(String.format(
                    "  Piece %d: pos=(%d,%d) size=%dx%d tiles=%d-%d pal=%d hflip=%b vflip=%b pri=%b%s",
                    i, piece.xOffset(), piece.yOffset(),
                    piece.widthTiles(), piece.heightTiles(),
                    piece.tileIndex(), lastTile,
                    piece.paletteIndex(), piece.hFlip(), piece.vFlip(), piece.priority(),
                    oob ? " *** OUT OF BOUNDS ***" : ""));
        }
        System.out.println("Max tile index used: " + maxTileUsed + " (total patterns: " + totalPatterns + ")");
        assertFalse(anyOob, "No tile indices should exceed pattern count");
    }

    /**
     * Load a full AIZ1 level with intro, then load intro art, and check
     * for pattern ID collisions in the atlas.
     */
    @Test
    public void noPatternIdCollisionBetweenLevelAndIntroArt() throws Exception {
        // Load the level first (this caches level patterns in the atlas)
        Sonic sonic = new Sonic("sonic", (short) 0, (short) 0);
        GameServices.sprites().addSprite(sonic);
        Camera camera = GameServices.camera();
        camera.setFocusedSprite(sonic);
        camera.setFrozen(false);

        LevelManager levelManager = GameServices.level();
        levelManager.loadZoneAndAct(0, 0);
        GroundSensor.setLevelManager(levelManager);
        camera.updatePosition(true);

        // Now load intro art (this caches intro patterns in the atlas)
        AizIntroArtLoader.reset();
        AizIntroArtLoader.loadAllIntroArt();

        // Get the atlas via reflection to check entries
        GraphicsManager gm = GraphicsManager.getInstance();
        PatternAtlas atlas = getPatternAtlas(gm);
        assertNotNull(atlas, "Pattern atlas should exist");

        // Check that intro pattern IDs (0x40000+) don't collide with level IDs
        Pattern[] planePatterns = AizIntroArtLoader.getPlanePatterns();
        int introBase = 0x40000;
        int collisions = 0;

        for (int i = 0; i < planePatterns.length; i++) {
            int introId = introBase + i;
            PatternAtlas.Entry entry = atlas.getEntry(introId);
            // Entry should either not exist yet (not cached in headless) or be unique
            if (entry != null) {
                // Check if a level pattern with the same ID exists
                // Level patterns use IDs 0-N, intro uses 0x40000+, so no collision expected
                PatternAtlas.Entry levelEntry = atlas.getEntry(i);
                if (levelEntry != null && levelEntry.slot() == entry.slot()) {
                    System.out.println("COLLISION: intro pattern " + introId + " shares atlas slot with level pattern " + i);
                    collisions++;
                }
            }
        }

        System.out.println("Checked " + planePatterns.length + " intro patterns for collisions: " + collisions + " found");
        assertEquals(0, collisions, "No atlas slot collisions between level and intro patterns");
    }

    /**
     * Verify raw KosinskiM module boundary by checking tiles near index 128
     * (module boundary = 4096 bytes = 128 tiles of 32 bytes).
     */
    @Test
    public void moduleBoundaryTilesAreValid() throws Exception {
        byte[] data = decompressPlaneArt();
        // Tiles 126-135 span the module boundary (module 1 ends at tile 127, module 2 starts at 128)
        System.out.println("=== MODULE BOUNDARY TILES (126-135) ===");
        int nonZeroTileCount = 0;
        int totalBoundaryTiles = 0;
        for (int t = 126; t < Math.min(136, data.length / 32); t++) {
            int offset = t * 32;
            int nonZero = 0;
            for (int b = 0; b < 32; b++) {
                if (data[offset + b] != 0) nonZero++;
            }
            if (nonZero > 0) nonZeroTileCount++;
            totalBoundaryTiles++;
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("Tile %3d: first8=[", t));
            for (int b = 0; b < 8; b++) {
                sb.append(String.format("%02X", data[offset + b] & 0xFF));
            }
            sb.append("] nonzero=").append(nonZero).append("/32");
            System.out.println(sb);
        }
        assertTrue(nonZeroTileCount > 0, "At least some module boundary tiles should contain non-zero data");
        assertTrue(totalBoundaryTiles > 0, "Should have checked boundary tiles");
    }

    /**
     * Compare decompressed plane art against a manual second decompression
     * to rule out non-determinism.
     */
    @Test
    public void decompressionIsDeterministic() throws Exception {
        byte[] first = decompressPlaneArt();
        byte[] second = decompressPlaneArt();
        assertArrayEquals(first, second, "Two decompressions should produce identical results");
    }

    /**
     * Check if specific face tile pixel data looks like it belongs to a different
     * sprite/object. Dump the 4bpp pixel grid for visual inspection.
     */
    @Test
    public void dumpFaceTilePixelGrids() throws Exception {
        byte[] data = decompressPlaneArt();
        System.out.println("=== FACE TILE PIXEL GRIDS (4bpp, 8x8) ===");
        System.out.println("(Each digit = one pixel color index, 0=transparent)");
        int tilesWithPixelData = 0;
        for (int t = 26; t <= 41; t++) {
            int offset = t * 32;
            System.out.println("--- Tile " + t + " (col " + ((t - 26) / 4) + " row " + ((t - 26) % 4) + ") ---");
            boolean hasNonZero = false;
            for (int row = 0; row < 8; row++) {
                StringBuilder sb = new StringBuilder("  ");
                for (int col = 0; col < 4; col++) {
                    int b = data[offset + row * 4 + col] & 0xFF;
                    int hi = (b >> 4) & 0xF;
                    int lo = b & 0xF;
                    sb.append(Integer.toHexString(hi)).append(Integer.toHexString(lo));
                    if (hi != 0 || lo != 0) hasNonZero = true;
                }
                System.out.println(sb);
            }
            if (hasNonZero) tilesWithPixelData++;
        }
        assertTrue(tilesWithPixelData > 0, "At least some face tiles should contain non-zero pixel data");
    }

    /**
     * Full end-to-end test: cache intro patterns in headless atlas,
     * then verify each face tile's atlas entry exists and has unique slot.
     */
    @Test
    public void faceTileAtlasEntriesAreUnique() throws Exception {
        GraphicsManager gm = GraphicsManager.getInstance();

        AizIntroArtLoader.reset();
        AizIntroArtLoader.loadAllIntroArt();

        // Simulate pattern caching in headless mode
        Pattern[] planePatterns = AizIntroArtLoader.getPlanePatterns();
        int introBase = 0x40000;
        for (int i = 0; i < planePatterns.length; i++) {
            gm.cachePatternTexture(planePatterns[i], introBase + i);
        }

        PatternAtlas atlas = getPatternAtlas(gm);
        assertNotNull(atlas, "Atlas should exist");

        // Check that face tiles 26-41 each have unique atlas slots
        Set<Integer> slots = new HashSet<>();
        System.out.println("=== FACE TILE ATLAS ENTRIES ===");
        for (int t = 26; t <= 41; t++) {
            int patternId = introBase + t;
            PatternAtlas.Entry entry = atlas.getEntry(patternId);
            assertNotNull(entry, "Face tile " + t + " should have atlas entry");
            boolean unique = slots.add(entry.slot());
            System.out.println(String.format("  Tile %d: patternId=0x%05X slot=%d tileXY=(%d,%d)%s",
                    t, patternId, entry.slot(), entry.tileX(), entry.tileY(),
                    unique ? "" : " *** DUPLICATE SLOT ***"));
            assertTrue(unique, "Each face tile should have a unique atlas slot");
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private byte[] decompressPlaneArt() throws Exception {
        Rom rom = GameServices.rom().getRom();
        int romAddr = Sonic3kConstants.ART_KOSM_AIZ_INTRO_PLANE_ADDR;
        int inputSize = Math.min(0x10000, (int) (rom.getSize() - romAddr));
        byte[] romData = rom.readBytes(romAddr, inputSize);
        return KosinskiReader.decompressModuled(romData, 0);
    }

    private boolean tilesEqual(byte[] data, int tile1, int tile2) {
        int off1 = tile1 * 32;
        int off2 = tile2 * 32;
        if (off1 + 32 > data.length || off2 + 32 > data.length) return false;
        for (int i = 0; i < 32; i++) {
            if (data[off1 + i] != data[off2 + i]) return false;
        }
        return true;
    }

    private PatternAtlas getPatternAtlas(GraphicsManager gm) {
        try {
            Field f = GraphicsManager.class.getDeclaredField("patternAtlas");
            f.setAccessible(true);
            return (PatternAtlas) f.get(gm);
        } catch (Exception e) {
            fail("Could not access patternAtlas field: " + e.getMessage());
            return null;
        }
    }
}


