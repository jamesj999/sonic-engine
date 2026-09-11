package com.openggf.level.resources;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.openggf.data.Rom;
import com.openggf.game.sonic2.Sonic2LevelResourcePlans;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

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
@RequiresRom(SonicGame.SONIC_2)
public class LevelResourceOverlayTest {
    private Rom rom;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
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

        LoadOp appended = LoadOp.kosinskiAppend(0x24680);
        assertEquals(0x24680, appended.romAddr());
        assertEquals(CompressionType.KOSINSKI, appended.compressionType());
        assertEquals(LoadOp.APPEND_TO_PREVIOUS, appended.destOffsetBytes());
        assertTrue(appended.appendsToPrevious());
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

    @Test
    public void testPlanBuilderRequiresPatternOp() {
        assertThrows(IllegalStateException.class, () -> LevelResourcePlan.builder()
                .addBlockOp(LoadOp.kosinskiBase(0x1000))
                .addChunkOp(LoadOp.kosinskiBase(0x2000))
                .build());
    }

    @Test
    public void testPlanBuilderRequiresBlockOp() {
        assertThrows(IllegalStateException.class, () -> LevelResourcePlan.builder()
                .addPatternOp(LoadOp.kosinskiBase(0x1000))
                .addChunkOp(LoadOp.kosinskiBase(0x2000))
                .build());
    }

    @Test
    public void testPlanBuilderRequiresChunkOp() {
        assertThrows(IllegalStateException.class, () -> LevelResourcePlan.builder()
                .addPatternOp(LoadOp.kosinskiBase(0x1000))
                .addBlockOp(LoadOp.kosinskiBase(0x2000))
                .build());
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

        ResourceLoader loader = new ResourceLoader(rom);

        // Load base EHZ chunks (16x16) only
        byte[] ehzChunks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR));

        // Load HTZ overlay chunks
        byte[] htzOverlayChunks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_OVERLAY_ADDR));

        // Load composed HTZ chunks (base + overlay)
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();
        byte[] composedChunks = loader.loadWithOverlays(htzPlan.getChunkOps(), 0x10000);

        // The composed chunks should be different from base-only
        assertFalse(Arrays.equals(ehzChunks, composedChunks), "Composed HTZ chunks should differ from base EHZ chunks");

        // The overlay should have been applied at offset 0x0980
        int overlayOffset = Sonic2Constants.HTZ_CHUNKS_OVERLAY_OFFSET;
        assertTrue(composedChunks.length >= overlayOffset + htzOverlayChunks.length, "Composed chunks should be at least as large as overlay offset + overlay size");

        // Verify the overlay bytes match
        byte[] overlayRegion = Arrays.copyOfRange(composedChunks, overlayOffset,
                overlayOffset + htzOverlayChunks.length);
        assertArrayEquals(htzOverlayChunks, overlayRegion, "Overlay region should match HTZ chunk data");
    }

    @Test
    public void testAppendOverlayMatchesExplicitOffsetForChunks() throws IOException {
        ResourceLoader loader = new ResourceLoader(rom);

        byte[] ehzChunks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR));

        byte[] explicit = loader.loadWithOverlays(Arrays.asList(
                LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR),
                LoadOp.kosinskiOverlay(Sonic2Constants.HTZ_CHUNKS_OVERLAY_ADDR, ehzChunks.length)), 0x10000);

        byte[] appended = loader.loadWithOverlays(Arrays.asList(
                LoadOp.kosinskiBase(Sonic2Constants.HTZ_CHUNKS_BASE_ADDR),
                LoadOp.kosinskiAppend(Sonic2Constants.HTZ_CHUNKS_OVERLAY_ADDR)), 0x10000);

        assertArrayEquals(explicit, appended, "Append-mode overlays should match explicit-offset composition");
    }

    @Test
    public void testPatternOverlayProducesDifferentData() throws IOException {

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
        assertNotEquals(baseHash, composedHash, "Composed HTZ patterns should differ from base patterns");

        // The overlay should have been applied at offset 0x3F80
        int overlayOffset = Sonic2Constants.HTZ_PATTERNS_OVERLAY_OFFSET;
        assertTrue(composedPatterns.length >= overlayOffset + htzSuppPatterns.length, "Composed patterns should be at least as large as overlay offset + overlay size");

        // Verify the overlay bytes match
        byte[] overlayRegion = Arrays.copyOfRange(composedPatterns, overlayOffset,
                overlayOffset + htzSuppPatterns.length);
        assertArrayEquals(htzSuppPatterns, overlayRegion, "Overlay region should match HTZ supplement pattern data");
    }

    @Test
    public void testCacheSafety_OverlayDoesNotMutateOriginal() throws IOException {

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
        assertArrayEquals(ehzChunksCopy, ehzChunksAfter, "EHZ chunk data should not be mutated by HTZ loading");
    }

    @Test
    public void testOverlayOffsetCalculation() throws IOException {

        // Pattern overlay offset 0x3F80 bytes corresponds to tile index 0x01FC
        // (0x3F80 / 32 bytes per tile = 0x1FC)
        int patternOffset = Sonic2Constants.HTZ_PATTERNS_OVERLAY_OFFSET;
        int tileIndex = patternOffset / 32;  // 32 bytes per 8x8 tile (4bpp)
        assertEquals(0x01FC, tileIndex, "Pattern overlay should start at tile index 0x01FC");

        // Chunk overlay offset 0x0980 bytes corresponds to chunk index 0x0130
        // (0x0980 / 8 bytes per chunk = 0x130)
        // Note: SonLVL calls these "blocks" (16x16), but engine calls them "chunks"
        int chunkOffset = Sonic2Constants.HTZ_CHUNKS_OVERLAY_OFFSET;
        int chunkIndex = chunkOffset / 8;  // 8 bytes per chunk (based on SonLVL comment)
        assertEquals(0x0130, chunkIndex, "Chunk overlay should start at chunk index 0x0130");
    }

    @Test
    public void testBlockDataSharedBetweenEhzAndHtz() throws IOException {

        ResourceLoader loader = new ResourceLoader(rom);

        // The block data (128x128) should be the same for EHZ and HTZ (no overlay)
        LevelResourcePlan htzPlan = Sonic2LevelResourcePlans.createHtzPlan();
        byte[] htzBlocks = loader.loadWithOverlays(htzPlan.getBlockOps(), 0x10000);

        // Load directly from the shared address
        byte[] sharedBlocks = loader.loadSingle(LoadOp.kosinskiBase(Sonic2Constants.HTZ_BLOCKS_ADDR));

        // They should be identical (no overlay applied for blocks)
        assertArrayEquals(sharedBlocks, htzBlocks, "HTZ and shared EHZ_HTZ blocks (128x128) should be identical");
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


