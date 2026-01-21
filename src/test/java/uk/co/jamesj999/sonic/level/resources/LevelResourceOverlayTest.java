package uk.co.jamesj999.sonic.level.resources;

import org.junit.Before;
import org.junit.Test;
import org.junit.Assume;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2LevelResourcePlans;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Tests for the level resource overlay loading system.
 *
 * <p>These tests verify:
 * <ul>
 *   <li>LoadOp and LevelResourcePlan construction</li>
 *   <li>HTZ overlay composition produces different data than base-only loading</li>
 *   <li>Overlay offsets are applied correctly:
 *       <ul>
 *         <li>Patterns (8x8): overlay at offset 0x3F80</li>
 *         <li>Chunks (16x16): overlay at offset 0x0980</li>
 *         <li>Blocks (128x128): shared, no overlay</li>
 *       </ul>
 *   </li>
 *   <li>Cache safety: loading HTZ doesn't mutate shared/cached data</li>
 * </ul>
 *
 * <p><strong>Terminology Note:</strong> SonLVL uses inverted terminology from this engine:
 * <ul>
 *   <li>SonLVL "blocks" (16x16) = Engine "chunks"</li>
 *   <li>SonLVL "chunks" (128x128) = Engine "blocks"</li>
 * </ul>
 */
public class LevelResourceOverlayTest {

    private static final String ROM_FILENAME = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";

    private Rom rom;
    private boolean romAvailable;

    @Before
    public void setUp() {
        File romFile = new File(ROM_FILENAME);
        romAvailable = romFile.exists() && romFile.length() > 0;

        if (romAvailable) {
            try {
                rom = new Rom();
                rom.open(romFile.getAbsolutePath());
                // Verify the ROM opened successfully
                romAvailable = rom.isOpen();
            } catch (Exception e) {
                romAvailable = false;
            }
        }
    }

    @org.junit.After
    public void tearDown() {
        if (rom != null) {
            rom.close();
        }
    }

    // ===== Unit Tests (no ROM required) =====

    @Test
    public void testLoadOpCreation() {
        LoadOp base = LoadOp.kosinskiBase(0x12345);
        assertEquals(0x12345, base.romAddr());
        assertEquals(CompressionType.KOSINSKI, base.compressionType());
        assertEquals(0, base.destOffsetBytes());

        LoadOp overlay = LoadOp.kosinskiOverlay(0x67890, 0x3F80);
        assertEquals(0x67890, overlay.romAddr());
        assertEquals(CompressionType.KOSINSKI, overlay.compressionType());
        assertEquals(0x3F80, overlay.destOffsetBytes());
    }

    @Test
    public void testLevelResourcePlanBuilder() {
        LevelResourcePlan plan = LevelResourcePlan.builder()
                .addPatternOp(LoadOp.kosinskiBase(0x1000))
                .addPatternOp(LoadOp.kosinskiOverlay(0x2000, 0x3F80))
                .addBlockOp(LoadOp.kosinskiBase(0x3000))
                .addBlockOp(LoadOp.kosinskiOverlay(0x4000, 0x0980))
                .addChunkOp(LoadOp.kosinskiBase(0x5000))
                .setPrimaryCollision(LoadOp.kosinskiBase(0x6000))
                .setSecondaryCollision(LoadOp.kosinskiBase(0x7000))
                .build();

        assertEquals(2, plan.getPatternOps().size());
        assertEquals(2, plan.getBlockOps().size());
        assertEquals(1, plan.getChunkOps().size());
        assertTrue(plan.hasPatternOverlays());
        assertTrue(plan.hasBlockOverlays());
    }

    @Test
    public void testSimplePlanCreation() {
        LevelResourcePlan plan = LevelResourcePlan.simple(
                0x1000, 0x2000, 0x3000, 0x4000, 0x5000);

        assertEquals(1, plan.getPatternOps().size());
        assertEquals(1, plan.getBlockOps().size());
        assertEquals(1, plan.getChunkOps().size());
        assertFalse(plan.hasPatternOverlays());
        assertFalse(plan.hasBlockOverlays());
    }

    @Test(expected = IllegalStateException.class)
    public void testPlanBuilderRequiresPatternOp() {
        LevelResourcePlan.builder()
                .addBlockOp(LoadOp.kosinskiBase(0x1000))
                .addChunkOp(LoadOp.kosinskiBase(0x2000))
                .build();
    }

    @Test(expected = IllegalStateException.class)
    public void testPlanBuilderRequiresBlockOp() {
        LevelResourcePlan.builder()
                .addPatternOp(LoadOp.kosinskiBase(0x1000))
                .addChunkOp(LoadOp.kosinskiBase(0x2000))
                .build();
    }

    @Test(expected = IllegalStateException.class)
    public void testPlanBuilderRequiresChunkOp() {
        LevelResourcePlan.builder()
                .addPatternOp(LoadOp.kosinskiBase(0x1000))
                .addBlockOp(LoadOp.kosinskiBase(0x2000))
                .build();
    }

    @Test
    public void testHtzPlanConfiguration() {
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();

        // Verify pattern ops (8x8 tiles with overlay)
        assertEquals(2, htzPlan.getPatternOps().size());
        LoadOp patternBase = htzPlan.getPatternOps().get(0);
        LoadOp patternOverlay = htzPlan.getPatternOps().get(1);
        assertEquals(Sonic2Constants.HTZ_PATTERNS_BASE_ADDR, patternBase.romAddr());
        assertEquals(0, patternBase.destOffsetBytes());
        assertEquals(Sonic2Constants.HTZ_PATTERNS_OVERLAY_ADDR, patternOverlay.romAddr());
        assertEquals(Sonic2Constants.HTZ_PATTERNS_OVERLAY_OFFSET, patternOverlay.destOffsetBytes());

        // Verify chunk ops (16x16 mappings with overlay)
        assertEquals(2, htzPlan.getChunkOps().size());
        LoadOp chunkBase = htzPlan.getChunkOps().get(0);
        LoadOp chunkOverlay = htzPlan.getChunkOps().get(1);
        assertEquals(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR, chunkBase.romAddr());
        assertEquals(0, chunkBase.destOffsetBytes());
        assertEquals(Sonic2Constants.HTZ_CHUNKS_OVERLAY_ADDR, chunkOverlay.romAddr());
        assertEquals(Sonic2Constants.HTZ_CHUNKS_OVERLAY_OFFSET, chunkOverlay.destOffsetBytes());

        // Verify block op (128x128 mappings, no overlay - shared EHZ_HTZ)
        assertEquals(1, htzPlan.getBlockOps().size());
        assertEquals(Sonic2Constants.HTZ_BLOCKS_ADDR, htzPlan.getBlockOps().get(0).romAddr());

        // Verify collision ops
        assertEquals(Sonic2Constants.HTZ_COLLISION_PRIMARY_ADDR,
                htzPlan.getPrimaryCollision().romAddr());
        assertEquals(Sonic2Constants.HTZ_COLLISION_SECONDARY_ADDR,
                htzPlan.getSecondaryCollision().romAddr());

        assertTrue(htzPlan.hasPatternOverlays());
        assertTrue(htzPlan.hasChunkOverlays());
        assertFalse(htzPlan.hasBlockOverlays());
    }

    @Test
    public void testGetPlanForZone() {
        // HTZ should return a plan
        assertNotNull(Sonic2LevelResourcePlans.getPlanForZone(Sonic2Constants.ZONE_HTZ));

        // EHZ (zone 0) should return null (uses standard loading)
        assertNull(Sonic2LevelResourcePlans.getPlanForZone(0x00));

        // Other zones should return null
        assertNull(Sonic2LevelResourcePlans.getPlanForZone(0x0D)); // CPZ
    }

    // ===== Integration Tests (ROM required) =====

    @Test
    public void testChunkOverlayProducesDifferentData() throws IOException {
        Assume.assumeTrue("ROM not available, skipping ROM test", romAvailable);

        ResourceLoader loader = new ResourceLoader(rom);

        // Load base EHZ chunks (16x16) only
        byte[] ehzChunks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR));

        // Load HTZ overlay chunks
        byte[] htzOverlayChunks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_OVERLAY_ADDR));

        // Load composed HTZ chunks (base + overlay)
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();
        byte[] composedChunks = loader.loadWithOverlays(htzPlan.getChunkOps(), 0x10000);

        // The composed chunks should be different from base-only
        assertFalse("Composed HTZ chunks should differ from base EHZ chunks",
                Arrays.equals(ehzChunks, composedChunks));

        // The overlay should have been applied at offset 0x0980
        int overlayOffset = Sonic2Constants.HTZ_CHUNKS_OVERLAY_OFFSET;
        assertTrue("Composed chunks should be at least as large as overlay offset + overlay size",
                composedChunks.length >= overlayOffset + htzOverlayChunks.length);

        // Verify the overlay bytes match
        byte[] overlayRegion = Arrays.copyOfRange(composedChunks, overlayOffset,
                overlayOffset + htzOverlayChunks.length);
        assertArrayEquals("Overlay region should match HTZ chunk data",
                htzOverlayChunks, overlayRegion);
    }

    @Test
    public void testPatternOverlayProducesDifferentData() throws IOException {
        Assume.assumeTrue("ROM not available, skipping ROM test", romAvailable);

        ResourceLoader loader = new ResourceLoader(rom);

        // Load base EHZ_HTZ patterns only
        byte[] basePatterns = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_PATTERNS_BASE_ADDR));

        // Load HTZ supplement patterns
        byte[] htzSuppPatterns = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_PATTERNS_OVERLAY_ADDR));

        // Load composed HTZ patterns (base + overlay)
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();
        byte[] composedPatterns = loader.loadWithOverlays(htzPlan.getPatternOps(), 0x10000);

        // The composed patterns should be different from base-only
        String baseHash = computeHash(basePatterns);
        String composedHash = computeHash(composedPatterns);
        assertNotEquals("Composed HTZ patterns should differ from base patterns",
                baseHash, composedHash);

        // The overlay should have been applied at offset 0x3F80
        int overlayOffset = Sonic2Constants.HTZ_PATTERNS_OVERLAY_OFFSET;
        assertTrue("Composed patterns should be at least as large as overlay offset + overlay size",
                composedPatterns.length >= overlayOffset + htzSuppPatterns.length);

        // Verify the overlay bytes match
        byte[] overlayRegion = Arrays.copyOfRange(composedPatterns, overlayOffset,
                overlayOffset + htzSuppPatterns.length);
        assertArrayEquals("Overlay region should match HTZ supplement pattern data",
                htzSuppPatterns, overlayRegion);
    }

    @Test
    public void testCacheSafety_OverlayDoesNotMutateOriginal() throws IOException {
        Assume.assumeTrue("ROM not available, skipping ROM test", romAvailable);

        ResourceLoader loader = new ResourceLoader(rom);

        // Load base EHZ chunks (16x16), save a copy
        byte[] ehzChunksOriginal = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR));
        byte[] ehzChunksCopy = Arrays.copyOf(ehzChunksOriginal, ehzChunksOriginal.length);

        // Now load HTZ composed chunks
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();
        byte[] composedChunks = loader.loadWithOverlays(htzPlan.getChunkOps(), 0x10000);

        // Load EHZ chunks again
        byte[] ehzChunksAfter = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR));

        // The EHZ chunks should still match the original (no mutation)
        assertArrayEquals("EHZ chunk data should not be mutated by HTZ loading",
                ehzChunksCopy, ehzChunksAfter);
    }

    @Test
    public void testOverlayOffsetCalculation() throws IOException {
        Assume.assumeTrue("ROM not available, skipping ROM test", romAvailable);

        // Pattern overlay offset 0x3F80 bytes corresponds to tile index 0x01FC
        // (0x3F80 / 32 bytes per tile = 0x1FC)
        int patternOffset = Sonic2Constants.HTZ_PATTERNS_OVERLAY_OFFSET;
        int tileIndex = patternOffset / 32;  // 32 bytes per 8x8 tile (4bpp)
        assertEquals("Pattern overlay should start at tile index 0x01FC",
                0x01FC, tileIndex);

        // Chunk overlay offset 0x0980 bytes corresponds to chunk index 0x0130
        // (0x0980 / 8 bytes per chunk = 0x130)
        // Note: SonLVL calls these "blocks" (16x16), but engine calls them "chunks"
        int chunkOffset = Sonic2Constants.HTZ_CHUNKS_OVERLAY_OFFSET;
        int chunkIndex = chunkOffset / 8;  // 8 bytes per chunk (based on SonLVL comment)
        assertEquals("Chunk overlay should start at chunk index 0x0130",
                0x0130, chunkIndex);
    }

    @Test
    public void testBlockDataSharedBetweenEhzAndHtz() throws IOException {
        Assume.assumeTrue("ROM not available, skipping ROM test", romAvailable);

        ResourceLoader loader = new ResourceLoader(rom);

        // The block data (128x128) should be the same for EHZ and HTZ (no overlay)
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();
        byte[] htzBlocks = loader.loadWithOverlays(htzPlan.getBlockOps(), 0x10000);

        // Load directly from the shared address
        byte[] sharedBlocks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_BLOCKS_ADDR));

        // They should be identical (no overlay applied for blocks)
        assertArrayEquals("HTZ and shared EHZ_HTZ blocks (128x128) should be identical",
                sharedBlocks, htzBlocks);
    }

    private String computeHash(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
