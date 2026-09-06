package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.*;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.LevelResourcePlan;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

/**
 * Level implementation for Sonic 3 &amp; Knuckles.
 *
 * <p>Key differences from Sonic 2:
 * <ul>
 *   <li>Patterns use Kosinski Moduled (KosM) compression</li>
 *   <li>Layout is uncompressed: 0x1000 bytes with 8-byte header</li>
 *   <li>Collision indices are noninterleaved (primary 0x600 + secondary 0x600)</li>
 *   <li>LevelLoadBlock entries are 24 bytes with embedded PLC and palette indices</li>
 * </ul>
 */
public class Sonic3kLevel extends AbstractLevel {
    private final boolean[] backgroundCollisionRows = new boolean[Sonic3kConstants.MAP_HEIGHT];
    private static final Logger LOG = Logger.getLogger(Sonic3kLevel.class.getName());

    private static final int BLOCK_GRID_SIDE = 8;

    private byte[] primaryCollisionIndexTable = new byte[0];
    private byte[] secondaryCollisionIndexTable = new byte[0];
    private final Integer minXOverride;
    /**
     * False for a level built off the frame thread ahead of its install: the
     * constructor then skips palette/pattern publication and the installer
     * calls {@link #publishGraphics} on the frame thread instead.
     */
    private final boolean publishGraphicsOnLoad;
    /** Level-art sheets built alongside a prepared load, consumed once by the art provider. */
    private Sonic3kObjectArtProvider.PreparedLevelArt preparedLevelArt;
    private int fgLayoutWidthBlocks = Sonic3kConstants.MAP_WIDTH;
    private int fgLayoutHeightBlocks = Sonic3kConstants.MAP_HEIGHT;
    private int bgLayoutWidthBlocks = Sonic3kConstants.MAP_WIDTH;
    private int bgLayoutHeightBlocks = Sonic3kConstants.MAP_HEIGHT;

    /**
     * Creates an S3K level using a LevelResourcePlan for resource loading.
     *
     * @param rom                    The ROM to load from
     * @param zoneIndex              ROM zone ID (0=AIZ, 1=HCZ, etc.)
     * @param resourcePlan           Resource plan for patterns/blocks/chunks
     * @param primaryCollisionAddr   ROM address of primary collision index data
     * @param secondaryCollisionAddr ROM address of secondary collision index data
     * @param interleavedCollision   true if collision data is interleaved (SK zones),
     *                               false if non-interleaved (S3K zones: primary 0x600 + secondary 0x600)
     * @param layoutAddr             ROM address of uncompressed layout data (0x1000 bytes)
     * @param levelBoundariesAddr    ROM address of level boundaries (8 bytes)
     * @param characterPaletteAddr   ROM address of character palette
     * @param levelPaletteAddr       ROM address of level palette data
     * @param minXOverride           Optional override for loaded minX boundary
     * @param objects                Object spawn list for this zone/act
     * @param rings                  Ring spawn list for this zone/act
     * @param ringSpriteSheet        Ring art and frame mappings
     */
    public Sonic3kLevel(Rom rom,
                        int zoneIndex,
                        LevelResourcePlan resourcePlan,
                        int primaryCollisionAddr,
                        int secondaryCollisionAddr,
                        boolean interleavedCollision,
                        int layoutAddr,
                        int levelBoundariesAddr,
                        int characterPaletteAddr,
                        int levelPaletteAddr,
                        Integer minXOverride,
                        List<ObjectSpawn> objects,
                        List<RingSpawn> rings,
                        RingSpriteSheet ringSpriteSheet) throws IOException {
        this(rom, zoneIndex, resourcePlan, primaryCollisionAddr, secondaryCollisionAddr,
                interleavedCollision, layoutAddr, levelBoundariesAddr,
                characterPaletteAddr, levelPaletteAddr, minXOverride,
                objects, rings, ringSpriteSheet, true);
    }

    /**
     * As the main constructor, with {@code publishGraphicsOnLoad} false when the
     * level is being built off the frame thread (see
     * {@link com.openggf.level.resources.PreparableLevelLoader}); the installer
     * then publishes palettes and patterns through {@link #publishGraphics}.
     */
    public Sonic3kLevel(Rom rom,
                        int zoneIndex,
                        LevelResourcePlan resourcePlan,
                        int primaryCollisionAddr,
                        int secondaryCollisionAddr,
                        boolean interleavedCollision,
                        int layoutAddr,
                        int levelBoundariesAddr,
                        int characterPaletteAddr,
                        int levelPaletteAddr,
                        Integer minXOverride,
                        List<ObjectSpawn> objects,
                        List<RingSpawn> rings,
                        RingSpriteSheet ringSpriteSheet,
                        boolean publishGraphicsOnLoad) throws IOException {
        super(zoneIndex);
        this.publishGraphicsOnLoad = publishGraphicsOnLoad;
        this.objects = objects != null ? objects : Collections.emptyList();
        this.rings = rings != null ? rings : Collections.emptyList();
        this.ringSpriteSheet = ringSpriteSheet;
        this.minXOverride = minXOverride;

        loadPalettes(rom, characterPaletteAddr, levelPaletteAddr);
        loadPatternsWithPlan(rom, resourcePlan);
        loadSolidTiles(rom);
        loadChunksWithCollision(rom, resourcePlan, primaryCollisionAddr, secondaryCollisionAddr,
                interleavedCollision);
        loadBlocksWithPlan(rom, resourcePlan);
        loadMap(rom, layoutAddr);
        loadBoundaries(rom, levelBoundariesAddr);
        validateResourceReferences();
    }

    /**
     * Publishes this level's palettes and patterns to the graphics manager,
     * exactly as the constructor does when {@code publishGraphicsOnLoad} is
     * true, but with the pattern uploads batched into one atlas upload.
     * No-op without an initialised GL context, matching the constructor.
     */
    public synchronized void publishGraphics(GraphicsManager graphics) {
        if (graphics == null || !graphics.isGlInitialized()) {
            return;
        }
        for (int i = 0; i < palettes.length; i++) {
            graphics.cachePaletteTexture(palettes[i], i);
        }
        graphics.beginPatternAtlasBatch();
        try {
            for (int i = 0; i < patternCount; i++) {
                if (patterns[i] != null) {
                    graphics.cachePatternTexture(patterns[i], i);
                }
            }
        } finally {
            graphics.endPatternAtlasBatch();
        }
    }

    /** Attaches level-art sheets built alongside a prepared load. */
    public void attachPreparedLevelArt(Sonic3kObjectArtProvider.PreparedLevelArt art) {
        this.preparedLevelArt = art;
    }

    /** Removes and returns prepared level-art sheets, or {@code null} when none were attached. */
    public Sonic3kObjectArtProvider.PreparedLevelArt takePreparedLevelArt() {
        Sonic3kObjectArtProvider.PreparedLevelArt art = preparedLevelArt;
        preparedLevelArt = null;
        return art;
    }

    // ===== Level interface overrides =====

    @Override
    public synchronized void ensurePatternCapacity(int minCount) {
        super.ensurePatternCapacity(minCount);
    }

    @Override
    public int getLayerWidthBlocks(int layer) {
        return layer == 1 ? bgLayoutWidthBlocks : fgLayoutWidthBlocks;
    }

    @Override
    public int getLayerHeightBlocks(int layer) {
        return layer == 1 ? bgLayoutHeightBlocks : fgLayoutHeightBlocks;
    }

    @Override
    public boolean hasBackgroundCollisionRowAt(int y) {
        // Find_Tile_BG selects a row with (y >> 5) & $7C, i.e. a 32-row
        // 128-pixel block grid. Rows whose interleaved BG pointer word is zero
        // have no layout source and must not wrap through the declared visual
        // BG height.
        int row = (y & 0x0FFF) >>> 7;
        return backgroundCollisionRows[row];
    }

    // ===== Loading methods =====

    private void loadPalettes(Rom rom, int characterPaletteAddr, int levelPaletteAddr) throws IOException {
        palettes = new Palette[PALETTE_COUNT];
        GraphicsManager graphicsMan = GameServices.graphics();

        // Palette 0: character palette (Sonic)
        palettes[0] = new Palette();
        if (characterPaletteAddr > 0) {
            byte[] charPalData = rom.readBytes(characterPaletteAddr, Palette.PALETTE_SIZE_IN_ROM);
            palettes[0].fromSegaFormat(charPalData);
        }

        // Palettes 1-3: level palettes
        // S3K level palettes are 3 palette lines (48 bytes = 3 * 16 colors * 2 bytes)
        if (levelPaletteAddr > 0) {
            int palSize = 3 * Palette.PALETTE_SIZE_IN_ROM;
            byte[] levelPalData = rom.readBytes(levelPaletteAddr, palSize);
            for (int i = 0; i < 3; i++) {
                palettes[i + 1] = new Palette();
                int start = i * Palette.PALETTE_SIZE_IN_ROM;
                int end = start + Palette.PALETTE_SIZE_IN_ROM;
                if (end <= levelPalData.length) {
                    palettes[i + 1].fromSegaFormat(Arrays.copyOfRange(levelPalData, start, end));
                }
            }
        } else {
            for (int i = 1; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }
        }

        if (publishGraphicsOnLoad && graphicsMan.isGlInitialized()) {
            for (int i = 0; i < palettes.length; i++) {
                graphicsMan.cachePaletteTexture(palettes[i], i);
            }
        }
    }

    private void loadPatternsWithPlan(Rom rom, LevelResourcePlan plan) throws IOException {
        GraphicsManager graphicsMan = GameServices.graphics();
        ResourceLoader loader = new ResourceLoader(rom);

        byte[] result = loader.loadWithOverlays(plan.getPatternOps(), 0x10000);

        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            LOG.warning(String.format("S3K pattern data not 32-byte aligned: %d bytes (remainder %d). Truncating.",
                    result.length, result.length % Pattern.PATTERN_SIZE_IN_ROM));
            int alignedLength = (result.length / Pattern.PATTERN_SIZE_IN_ROM) * Pattern.PATTERN_SIZE_IN_ROM;
            result = Arrays.copyOf(result, alignedLength);
        }
        patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;

        patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
            if (publishGraphicsOnLoad && graphicsMan.isGlInitialized()) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }
        }

        LOG.info("S3K pattern count: " + patternCount + " (" + result.length + " bytes)");
    }

    private void loadSolidTiles(Rom rom) throws IOException {
        int heightsAddr = Sonic3kConstants.SOLID_TILE_VERTICAL_MAP_ADDR;
        int widthsAddr = Sonic3kConstants.SOLID_TILE_HORIZONTAL_MAP_ADDR;
        int anglesAddr = Sonic3kConstants.SOLID_TILE_ANGLE_ADDR;

        if (heightsAddr == 0 || widthsAddr == 0 || anglesAddr == 0) {
            LOG.warning("S3K collision addresses not set - collision will not work");
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            return;
        }

        solidTileCount = (Sonic3kConstants.SOLID_TILE_MAP_SIZE + 1) / SolidTile.TILE_SIZE_IN_ROM;
        byte[] heightsBuffer = rom.readBytes(heightsAddr, Sonic3kConstants.SOLID_TILE_MAP_SIZE);
        byte[] widthsBuffer = rom.readBytes(widthsAddr, Sonic3kConstants.SOLID_TILE_MAP_SIZE);

        solidTiles = new SolidTile[solidTileCount];
        for (int i = 0; i < solidTileCount; i++) {
            byte angle = rom.readByte(anglesAddr + i);
            byte[] heights = Arrays.copyOfRange(heightsBuffer,
                    i * SolidTile.TILE_SIZE_IN_ROM, (i + 1) * SolidTile.TILE_SIZE_IN_ROM);
            byte[] widths = Arrays.copyOfRange(widthsBuffer,
                    i * SolidTile.TILE_SIZE_IN_ROM, (i + 1) * SolidTile.TILE_SIZE_IN_ROM);
            solidTiles[i] = new SolidTile(i, heights, widths, angle);
        }

        LOG.fine("S3K SolidTiles loaded: " + solidTileCount);
    }

    /**
     * Loads chunks (16x16 tiles) from the resource plan and collision indices from ROM.
     *
     * <p>S3K collision data is uncompressed. Two formats exist:
     * <ul>
     *   <li>Non-interleaved (S3K zones): primary 0x600 bytes at primaryAddr,
     *       secondary 0x600 bytes at secondaryAddr</li>
     *   <li>Interleaved (SK zones): primary and secondary bytes alternate,
     *       primaryAddr points to even bytes, secondaryAddr = primaryAddr + 1</li>
     * </ul>
     */
    private void loadChunksWithCollision(Rom rom, LevelResourcePlan plan,
                                         int primaryCollisionAddr,
                                         int secondaryCollisionAddr,
                                         boolean interleaved) throws IOException {
        ResourceLoader loader = new ResourceLoader(rom);

        byte[] chunkBuffer = loader.loadWithOverlays(plan.getChunkOps(), 0x10000);

        chunkCount = chunkBuffer.length / Chunk.CHUNK_SIZE_IN_ROM;
        if (chunkBuffer.length % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent S3K chunk data");
        }

        // S3K collision indices are read via chunkIndex*2 in the original code.
        // Keep raw tables and apply stride during lookup.
        int tableSize = interleaved
                ? Sonic3kConstants.COLLISION_INDEX_SIZE * 2
                : Sonic3kConstants.COLLISION_INDEX_SIZE;
        byte[] primaryCollision = primaryCollisionAddr > 0
                ? rom.readBytes(primaryCollisionAddr, tableSize)
                : new byte[0];
        byte[] secondaryCollision = secondaryCollisionAddr > 0
                ? rom.readBytes(secondaryCollisionAddr, tableSize)
                : new byte[0];
        primaryCollisionIndexTable = Arrays.copyOf(primaryCollision, primaryCollision.length);
        secondaryCollisionIndexTable = Arrays.copyOf(secondaryCollision, secondaryCollision.length);

        chunks = new Chunk[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = new Chunk();
            byte[] subArray = Arrays.copyOfRange(chunkBuffer, i * Chunk.CHUNK_SIZE_IN_ROM,
                    (i + 1) * Chunk.CHUNK_SIZE_IN_ROM);
            int solidIndex = readCollisionIndex(primaryCollision, i);
            int altSolidIndex = readCollisionIndex(secondaryCollision, i);
            chunks[i].fromSegaFormat(subArray, solidIndex, altSolidIndex);
        }

        LOG.info("S3K chunk count: " + chunkCount + " (" + chunkBuffer.length + " bytes)");
    }

    /**
     * Applies an 8x8 pattern overlay at runtime.
     * Used by AIZ intro to swap secondary terrain tiles to the "main level" set.
     */
    public synchronized void applyPatternOverlay(byte[] overlayData, int destOffsetBytes) {
        applyPatternOverlay(overlayData, destOffsetBytes, true);
    }

    /**
     * Applies an 8x8 pattern overlay at runtime.
     *
     * @param overlayData      decompressed tile data
     * @param destOffsetBytes  destination byte offset in pattern memory
     * @param clearTrailing    true to clear tiles above the written range; false to preserve them
     */
    public synchronized void applyPatternOverlay(byte[] overlayData,
                                                 int destOffsetBytes,
                                                 boolean clearTrailing) {
        if (overlayData == null || overlayData.length == 0 || destOffsetBytes < 0) {
            return;
        }

        if (destOffsetBytes % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            LOG.warning("Pattern overlay offset is not 32-byte aligned: 0x"
                    + Integer.toHexString(destOffsetBytes));
            return;
        }

        int usableLength = (overlayData.length / Pattern.PATTERN_SIZE_IN_ROM) * Pattern.PATTERN_SIZE_IN_ROM;
        int startPatternIndex = destOffsetBytes / Pattern.PATTERN_SIZE_IN_ROM;
        int overlayPatternCount = usableLength / Pattern.PATTERN_SIZE_IN_ROM;
        int requiredPatternCount = startPatternIndex + overlayPatternCount;
        ensurePatternCapacity(requiredPatternCount);

        GraphicsManager graphics = GameServices.graphics();
        graphics.beginPatternAtlasBatch();
        try {
            byte[] tileBytes = new byte[Pattern.PATTERN_SIZE_IN_ROM];
            for (int i = 0; i < overlayPatternCount; i++) {
                int src = i * Pattern.PATTERN_SIZE_IN_ROM;
                System.arraycopy(overlayData, src, tileBytes, 0, Pattern.PATTERN_SIZE_IN_ROM);

                int patternIndex = startPatternIndex + i;
                Pattern pattern = patterns[patternIndex];
                if (pattern == null) {
                    pattern = new Pattern();
                    patterns[patternIndex] = pattern;
                }
                pattern.fromSegaFormat(tileBytes);

                if (graphics.isGlInitialized()) {
                    graphics.cachePatternTexture(pattern, patternIndex);
                }
            }

            // Some event paths intentionally patch only a subset of the pattern table.
            // Preserve trailing data by default for compatibility, with optional clear
            // mode retained for intro swap parity behavior.
            if (clearTrailing && requiredPatternCount < patternCount) {
                for (int i = requiredPatternCount; i < patternCount; i++) {
                    if (patterns[i] != null) {
                        patterns[i].clear();
                        if (graphics.isGlInitialized()) {
                            graphics.cachePatternTexture(patterns[i], i);
                        }
                    }
                }
            }
        } finally {
            graphics.endPatternAtlasBatch();
        }
    }

    /**
     * Applies a 16x16 block-map overlay at runtime.
     * In engine terminology this updates {@link Chunk} data.
     */
    public synchronized void applyChunkOverlay(byte[] overlayData, int destOffsetBytes) {
        applyChunkOverlay(overlayData, destOffsetBytes, true);
    }

    /**
     * Applies a 16x16 block-map overlay at runtime.
     *
     * @param overlayData      decompressed chunk bytes
     * @param destOffsetBytes  destination byte offset in chunk memory
     * @param clearTrailing    true to clear chunks above the written range; false to preserve them
     */
    public synchronized void applyChunkOverlay(byte[] overlayData,
                                               int destOffsetBytes,
                                               boolean clearTrailing) {
        if (overlayData == null || overlayData.length == 0 || destOffsetBytes < 0) {
            return;
        }
        if (destOffsetBytes % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            LOG.warning("Chunk overlay offset is not 8-byte aligned: 0x"
                    + Integer.toHexString(destOffsetBytes));
            return;
        }

        int usableLength = (overlayData.length / Chunk.CHUNK_SIZE_IN_ROM) * Chunk.CHUNK_SIZE_IN_ROM;
        int startChunkIndex = destOffsetBytes / Chunk.CHUNK_SIZE_IN_ROM;
        int overlayChunkCount = usableLength / Chunk.CHUNK_SIZE_IN_ROM;
        int requiredChunkCount = startChunkIndex + overlayChunkCount;
        ensureChunkCapacity(requiredChunkCount);

        // Rewind keyframes share the live chunks array by reference
        // (LevelSnapshot contract): write into a cloned array with freshly
        // constructed Chunk instances so previously captured keyframes keep
        // the pre-overlay terrain and collision.
        Chunk[] newChunks = chunks.clone();
        byte[] chunkBytes = new byte[Chunk.CHUNK_SIZE_IN_ROM];
        for (int i = 0; i < overlayChunkCount; i++) {
            int src = i * Chunk.CHUNK_SIZE_IN_ROM;
            System.arraycopy(overlayData, src, chunkBytes, 0, Chunk.CHUNK_SIZE_IN_ROM);

            int chunkIndex = startChunkIndex + i;
            int solidIndex = readCollisionIndex(primaryCollisionIndexTable, chunkIndex);
            int altSolidIndex = readCollisionIndex(secondaryCollisionIndexTable, chunkIndex);
            Chunk chunk = new Chunk();
            chunk.fromSegaFormat(chunkBytes, solidIndex, altSolidIndex);
            newChunks[chunkIndex] = chunk;
        }

        // Some event paths intentionally patch only a subset of the chunk table.
        // Preserve trailing entries by default for compatibility, with optional
        // clear mode retained for intro swap parity behavior.
        if (clearTrailing && requiredChunkCount < chunkCount) {
            byte[] emptyChunkBytes = new byte[Chunk.CHUNK_SIZE_IN_ROM];
            for (int i = requiredChunkCount; i < chunkCount; i++) {
                if (newChunks[i] != null) {
                    Chunk cleared = new Chunk();
                    cleared.fromSegaFormat(emptyChunkBytes, 0, 0);
                    newChunks[i] = cleared;
                }
            }
        }
        chunks = newChunks;
    }

    /**
     * Rotates a 128x128 block definition's 16x16 descriptors right by the
     * given number of grid entries (row-major), preserving rewind snapshots
     * via copy-on-write.
     *
     * <p>ROM: {@code LBZ1_RotateChunks} rotates chunk {@code $DB}'s 64-word
     * chunk-table definition by 24 words during {@code Adjust_LBZ2Layout},
     * shifting the chunk graphics down three 16x16 rows.
     */
    public synchronized void rotateBlockChunkDescs(int blockIndex, int rotateBy) {
        Block block = getBlock(blockIndex);
        if (block == null) {
            return;
        }
        block.cowEnsureWritable(currentEpoch());
        int side = block.getGridSide();
        int total = side * side;
        ChunkDesc[] original = new ChunkDesc[total];
        for (int i = 0; i < total; i++) {
            original[i] = block.getChunkDesc(i % side, i / side);
        }
        for (int i = 0; i < total; i++) {
            ChunkDesc source = original[((i - rotateBy) % total + total) % total];
            block.setChunkDesc(i % side, i / side, source);
        }
    }

    /**
     * Saves the state of all chunks as a 2D int array.
     * Used for snapshot/restore during pre-computation of transition tilemaps.
     */
    public int[][] snapshotChunks() {
        int[][] snapshot = new int[chunkCount][];
        for (int i = 0; i < chunkCount; i++) {
            snapshot[i] = chunks[i].saveState();
        }
        return snapshot;
    }

    /**
     * Restores all chunks from a previously saved snapshot.
     * Installs fresh Chunk instances into a cloned array so rewind keyframes
     * sharing the previous array (LevelSnapshot contract) are not mutated.
     */
    public void restoreChunks(int[][] snapshot) {
        Chunk[] newChunks = chunks.clone();
        for (int i = 0; i < snapshot.length && i < chunkCount; i++) {
            Chunk chunk = new Chunk();
            chunk.restoreState(snapshot[i]);
            newChunks[i] = chunk;
        }
        chunks = newChunks;
    }

    /**
     * Applies a 128x128 block-map overlay at runtime.
     * In engine terminology this updates {@link Block} data.
     */
    public synchronized void applyBlockOverlay(byte[] overlayData, int destOffsetBytes) {
        applyBlockOverlay(overlayData, destOffsetBytes, true);
    }

    /**
     * Applies a 128x128 block-map overlay at runtime.
     *
     * @param overlayData      decompressed block bytes
     * @param destOffsetBytes  destination byte offset in block memory
     * @param clearTrailing    true to clear blocks above the written range; false to preserve them
     */
    public synchronized void applyBlockOverlay(byte[] overlayData,
                                               int destOffsetBytes,
                                               boolean clearTrailing) {
        if (overlayData == null || overlayData.length == 0 || destOffsetBytes < 0) {
            return;
        }
        if (destOffsetBytes % LevelConstants.BLOCK_SIZE_IN_ROM != 0) {
            LOG.warning("Block overlay offset is not 128-byte aligned: 0x"
                    + Integer.toHexString(destOffsetBytes));
            return;
        }

        int usableLength = (overlayData.length / LevelConstants.BLOCK_SIZE_IN_ROM)
                * LevelConstants.BLOCK_SIZE_IN_ROM;
        int startBlockIndex = destOffsetBytes / LevelConstants.BLOCK_SIZE_IN_ROM;
        int overlayBlockCount = usableLength / LevelConstants.BLOCK_SIZE_IN_ROM;
        int requiredBlockCount = startBlockIndex + overlayBlockCount;
        ensureBlockCapacity(requiredBlockCount);

        // Same rewind-keyframe isolation contract as applyChunkOverlay: clone
        // the array and install fresh Block instances instead of mutating
        // entries shared with captured LevelSnapshots.
        Block[] newBlocks = blocks.clone();
        byte[] blockBytes = new byte[LevelConstants.BLOCK_SIZE_IN_ROM];
        for (int i = 0; i < overlayBlockCount; i++) {
            int src = i * LevelConstants.BLOCK_SIZE_IN_ROM;
            System.arraycopy(overlayData, src, blockBytes, 0, LevelConstants.BLOCK_SIZE_IN_ROM);

            int blockIndex = startBlockIndex + i;
            Block prior = newBlocks[blockIndex];
            Block block = prior != null ? new Block(prior.getGridSide()) : new Block();
            block.fromSegaFormat(blockBytes);
            newBlocks[blockIndex] = block;
        }

        if (clearTrailing && requiredBlockCount < blockCount) {
            byte[] emptyBlockBytes = new byte[LevelConstants.BLOCK_SIZE_IN_ROM];
            for (int i = requiredBlockCount; i < blockCount; i++) {
                if (newBlocks[i] != null) {
                    Block cleared = new Block(newBlocks[i].getGridSide());
                    cleared.fromSegaFormat(emptyBlockBytes);
                    newBlocks[i] = cleared;
                }
            }
        }
        blocks = newBlocks;
    }

    private void ensureChunkCapacity(int minCount) {
        if (minCount <= chunkCount) {
            return;
        }
        int oldCount = chunkCount;
        chunks = Arrays.copyOf(chunks, minCount);
        for (int i = oldCount; i < minCount; i++) {
            chunks[i] = new Chunk();
        }
        chunkCount = minCount;
    }

    private void ensureBlockCapacity(int minCount) {
        if (minCount <= blockCount) {
            return;
        }
        int oldCount = blockCount;
        blocks = Arrays.copyOf(blocks, minCount);
        for (int i = oldCount; i < minCount; i++) {
            blocks[i] = new Block();
        }
        blockCount = minCount;
    }

    private void loadBlocksWithPlan(Rom rom, LevelResourcePlan plan) throws IOException {
        ResourceLoader loader = new ResourceLoader(rom);

        byte[] blockBuffer = loader.loadWithOverlaysAligned(
                plan.getBlockOps(), 0x10000, LevelConstants.BLOCK_SIZE_IN_ROM);

        blockCount = blockBuffer.length / LevelConstants.BLOCK_SIZE_IN_ROM;

        blocks = new Block[blockCount];
        for (int i = 0; i < blockCount; i++) {
            blocks[i] = new Block();
            byte[] subArray = Arrays.copyOfRange(blockBuffer, i * LevelConstants.BLOCK_SIZE_IN_ROM,
                    (i + 1) * LevelConstants.BLOCK_SIZE_IN_ROM);
            blocks[i].fromSegaFormat(subArray);
        }

        LOG.info("S3K block count: " + blockCount + " (" + blockBuffer.length + " bytes)");
    }

    /**
     * Loads the S3K level layout.
     *
     * <p>S3K layout format (0x1000 bytes):
     * <ul>
     *   <li>Header (8 bytes): FG cols/row (word), BG cols/row (word), FG rows (word), BG rows (word)</li>
     *   <li>Data (0xFF8 bytes): row pointer offsets + chunk index bytes</li>
     * </ul>
     *
     * <p>The layout is loaded into a 2-layer Map matching S2's format (128 wide, 16 tall)
     * for compatibility with the engine's rendering and collision systems.
     */
    private void loadMap(Rom rom, int layoutAddr) throws IOException {
        if (layoutAddr == 0) {
            LOG.warning("S3K layout address is 0 - using empty map");
            map = new Map(Sonic3kConstants.MAP_LAYERS, Sonic3kConstants.MAP_WIDTH, Sonic3kConstants.MAP_HEIGHT);
            return;
        }

        byte[] layoutData = rom.readBytes(layoutAddr, Sonic3kConstants.LEVEL_LAYOUT_TOTAL_SIZE);
        for (int row = 0; row < backgroundCollisionRows.length; row++) {
            int ptrPos = Sonic3kConstants.LEVEL_LAYOUT_HEADER_SIZE + 2 + row * 4;
            int rowPointerWord = ((layoutData[ptrPos] & 0xFF) << 8) | (layoutData[ptrPos + 1] & 0xFF);
            backgroundCollisionRows[row] = decodeLayoutRowOffset(rowPointerWord) >= 0;
        }

        // Parse header
        int fgColsPerRow = ((layoutData[0] & 0xFF) << 8) | (layoutData[1] & 0xFF);
        int bgColsPerRow = ((layoutData[2] & 0xFF) << 8) | (layoutData[3] & 0xFF);
        int fgRows = ((layoutData[4] & 0xFF) << 8) | (layoutData[5] & 0xFF);
        int bgRows = ((layoutData[6] & 0xFF) << 8) | (layoutData[7] & 0xFF);

        fgLayoutWidthBlocks = clampLayoutDimension(fgColsPerRow, Sonic3kConstants.MAP_WIDTH);
        fgLayoutHeightBlocks = clampLayoutDimension(fgRows, Sonic3kConstants.MAP_HEIGHT);
        bgLayoutWidthBlocks = clampLayoutDimension(bgColsPerRow, Sonic3kConstants.MAP_WIDTH);
        bgLayoutHeightBlocks = clampLayoutDimension(bgRows, Sonic3kConstants.MAP_HEIGHT);

        LOG.info(String.format("S3K layout header: FG %dx%d, BG %dx%d",
                fgColsPerRow, fgRows, bgColsPerRow, bgRows));

        // Derive map dimensions from the actual layout data (like S1),
        // capped at the safety maximums in Sonic3kConstants.
        int mapWidth = Math.max(fgLayoutWidthBlocks, bgLayoutWidthBlocks);
        int mapHeight = Math.max(fgLayoutHeightBlocks, bgLayoutHeightBlocks);
        map = new Map(Sonic3kConstants.MAP_LAYERS, mapWidth, mapHeight);

        // Parse FG/BG layout pointers.
        // S3K stores row pointers as interleaved word pairs:
        //   row0: FG_ptr, BG_ptr
        //   row1: FG_ptr, BG_ptr
        //   ...
        // (each row-pair = 4 bytes).
        parseLayoutLayer(layoutData, Sonic3kConstants.LEVEL_LAYOUT_HEADER_SIZE,
                4, fgColsPerRow, fgRows, map, 0, mapWidth, mapHeight);
        parseLayoutLayer(layoutData, Sonic3kConstants.LEVEL_LAYOUT_HEADER_SIZE + 2,
                4, bgColsPerRow, bgRows, map, 1, mapWidth, mapHeight);
        LOG.info("S3K map loaded successfully");
    }

    private static int clampLayoutDimension(int value, int fallback) {
        if (value <= 0) {
            return fallback;
        }
        return Math.min(value, fallback);
    }

    /**
     * Parses a single layout layer (FG or BG) from S3K layout data.
     *
     * <p>Each layer has a table of word-sized row pointers (relative to layout start + 8),
     * and each row pointer leads to chunk index bytes for that row.
     */
    private void parseLayoutLayer(byte[] layoutData, int rowPtrOffset, int rowPtrStride,
                                  int colsPerRow, int rows,
                                  Map map, int layer,
                                  int mapWidth, int mapHeight) {
        for (int row = 0; row < Math.min(rows, mapHeight); row++) {
            // Read word-sized row pointer
            int ptrPos = rowPtrOffset + row * rowPtrStride;
            if (ptrPos + 1 >= layoutData.length) break;

            int rowPointerWord = ((layoutData[ptrPos] & 0xFF) << 8) | (layoutData[ptrPos + 1] & 0xFF);
            int rowDataAddr = decodeLayoutRowOffset(rowPointerWord);
            if (rowDataAddr < 0 || rowDataAddr >= layoutData.length) {
                continue;
            }

            for (int col = 0; col < Math.min(colsPerRow, mapWidth); col++) {
                int srcIdx = rowDataAddr + col;
                if (srcIdx >= layoutData.length) break;
                map.setValue(layer, col, row, layoutData[srcIdx]);
            }
        }
    }

    static int decodeLayoutRowOffset(int rowPointerWord) {
        int pointer = rowPointerWord & Sonic3kConstants.LEVEL_LAYOUT_ROW_POINTER_MASK;
        if (pointer == 0) {
            return -1;
        }
        if (pointer >= Sonic3kConstants.LEVEL_LAYOUT_RAM_BASE) {
            return pointer - Sonic3kConstants.LEVEL_LAYOUT_RAM_BASE;
        }
        // Fallback for unexpected builds: treat as direct offset.
        return pointer;
    }

    static int readCollisionIndex(byte[] collisionTable, int chunkIndex) {
        if (collisionTable == null || chunkIndex < 0) {
            return 0;
        }
        int offset = chunkIndex * Sonic3kConstants.COLLISION_INDEX_STRIDE_BYTES;
        if (offset < 0 || offset >= collisionTable.length) {
            LOG.warning(String.format(
                    "S3K collision index out of bounds: chunkIndex=%d, offset=0x%X, tableLength=0x%X",
                    chunkIndex, offset, collisionTable.length));
            return 0;
        }
        return Byte.toUnsignedInt(collisionTable[offset]);
    }

    private void validateResourceReferences() {
        if (map == null || blockCount == 0 || chunkCount == 0 || patternCount == 0) {
            LOG.warning("S3K resource validation skipped due to incomplete level data.");
            return;
        }

        int maxMapBlockIndex = 0;
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                int fg = Byte.toUnsignedInt(map.getValue(0, x, y));
                int bg = Byte.toUnsignedInt(map.getValue(1, x, y));
                if (fg > maxMapBlockIndex) maxMapBlockIndex = fg;
                if (bg > maxMapBlockIndex) maxMapBlockIndex = bg;
            }
        }

        int maxBlockChunkIndex = 0;
        for (int blockIdx = 0; blockIdx < blockCount; blockIdx++) {
            Block block = blocks[blockIdx];
            for (int cy = 0; cy < BLOCK_GRID_SIDE; cy++) {
                for (int cx = 0; cx < BLOCK_GRID_SIDE; cx++) {
                    int chunkIndex = block.getChunkDesc(cx, cy).getChunkIndex();
                    if (chunkIndex > maxBlockChunkIndex) {
                        maxBlockChunkIndex = chunkIndex;
                    }
                }
            }
        }

        int maxChunkPatternIndex = 0;
        for (int chunkIdx = 0; chunkIdx < chunkCount; chunkIdx++) {
            Chunk chunk = chunks[chunkIdx];
            for (int py = 0; py < 2; py++) {
                for (int px = 0; px < 2; px++) {
                    int patternIndex = chunk.getPatternDesc(px, py).getPatternIndex();
                    if (patternIndex > maxChunkPatternIndex) {
                        maxChunkPatternIndex = patternIndex;
                    }
                }
            }
        }

        if (maxMapBlockIndex >= blockCount) {
            LOG.warning("S3K map references block " + maxMapBlockIndex +
                    " but blockCount is " + blockCount);
        }
        // ROM parity: on the Mega Drive the chunk table is a fixed-size RAM buffer.
        // Block ChunkDescs may reference indices beyond the decompressed data;
        // those slots are implicitly zero (transparent).  Extend the array so
        // that every block reference resolves to a valid (empty) Chunk.
        if (maxBlockChunkIndex >= chunkCount) {
            LOG.info("S3K extending chunk array from " + chunkCount +
                    " to " + (maxBlockChunkIndex + 1) + " to cover all block references");
            ensureChunkCapacity(maxBlockChunkIndex + 1);
        }
        if (maxChunkPatternIndex >= patternCount) {
            LOG.warning("S3K chunks reference pattern " + maxChunkPatternIndex +
                    " but patternCount is " + patternCount);
        }
    }

    private void loadBoundaries(Rom rom, int levelBoundariesAddr) throws IOException {
        if (levelBoundariesAddr == 0) {
            minX = 0;
            maxX = 0x6000;
            minY = 0;
            maxY = 0x0800;
        } else {
            this.minX = rom.read16BitAddr(levelBoundariesAddr);
            this.maxX = rom.read16BitAddr(levelBoundariesAddr + 2);
            // Y boundaries use (short) cast for sign extension: S3K zones can have
            // negative Y bounds (e.g., AIZ intro minY), unlike X which is always >= 0.
            this.minY = (short) rom.read16BitAddr(levelBoundariesAddr + 4);
            this.maxY = (short) rom.read16BitAddr(levelBoundariesAddr + 6);
        }
        if (minXOverride != null) {
            this.minX = minXOverride;
        }
    }
}
