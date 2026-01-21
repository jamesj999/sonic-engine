package uk.co.jamesj999.sonic.game.sonic2;

import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.*;
import uk.co.jamesj999.sonic.level.resources.LevelResourcePlan;
import uk.co.jamesj999.sonic.level.resources.LoadOp;
import uk.co.jamesj999.sonic.level.resources.ResourceLoader;
import uk.co.jamesj999.sonic.tools.KosinskiReader;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class Sonic2Level implements Level {
    private static final int PALETTE_COUNT = 4;
    private static final int MAP_LAYERS = 2;
    private static final int MAP_HEIGHT = 16;
    private static final int MAP_WIDTH = 128;

    private Palette[] palettes;
    private Pattern[] patterns;
    private Chunk[] chunks;
    private Block[] blocks;
    private SolidTile[] solidTiles;
    private Map map;
    private List<ObjectSpawn> objects;
    private List<RingSpawn> rings;
    private RingSpriteSheet ringSpriteSheet;
    private final int zoneIndex;

    private int patternCount;
    private int chunkCount;
    private int blockCount;
    private int solidTileCount;
    private int minX;
    private int maxX;
    private int minY;
    private int maxY;
    private static final boolean KOS_DEBUG_LOG = false;

    private static final Logger LOG = Logger.getLogger(Sonic2Level.class.getName());

    public Sonic2Level(Rom rom,
            int zoneIndex,
            int characterPaletteAddr,
            int levelPalettesAddr,
            int levelPalettesSize,
            int patternsAddr,
            int chunksAddr,
            int blocksAddr,
            int mapAddr,
            int collisionsAddr,
            int altCollisionsAddr,
            int solidTileHeightsAddr,
            int solidTileWidthsAddr,
            int solidTilesAngleAddr,
            List<ObjectSpawn> objectSpawns,
            List<RingSpawn> ringSpawns,
            RingSpriteSheet ringSpriteSheet,
            int levelBoundariesAddr) throws IOException {
        this.zoneIndex = zoneIndex;
        loadPalettes(rom, characterPaletteAddr, levelPalettesAddr, levelPalettesSize);
        loadPatterns(rom, patternsAddr);
        loadSolidTiles(rom, solidTileHeightsAddr, solidTileWidthsAddr, solidTilesAngleAddr);
        loadChunks(rom, chunksAddr, collisionsAddr, altCollisionsAddr);
        loadBlocks(rom, blocksAddr);
        loadMap(rom, mapAddr);
        this.objects = List.copyOf(objectSpawns);
        this.rings = List.copyOf(ringSpawns);
        this.ringSpriteSheet = ringSpriteSheet;
        loadBoundaries(rom, levelBoundariesAddr);
    }

    /**
     * Creates a Sonic2Level using a LevelResourcePlan for overlay-based resource loading.
     *
     * <p>This constructor supports zones that compose resources from multiple sources,
     * such as Hill Top Zone which overlays HTZ-specific patterns and blocks on top
     * of shared EHZ/HTZ base data.
     *
     * @param rom                    The ROM to load from
     * @param zoneIndex              Zone index (ROM zone ID)
     * @param characterPaletteAddr   Address of character palette
     * @param levelPalettesAddr      Address of level palettes
     * @param levelPalettesSize      Size of level palette data
     * @param resourcePlan           Resource plan defining pattern/block/chunk/collision loading
     * @param mapAddr                Address of level layout
     * @param solidTileHeightsAddr   Address of solid tile heights
     * @param solidTileWidthsAddr    Address of solid tile widths
     * @param solidTilesAngleAddr    Address of solid tile angles
     * @param objectSpawns           Object spawn data
     * @param ringSpawns             Ring spawn data
     * @param ringSpriteSheet        Ring sprite sheet
     * @param levelBoundariesAddr    Address of level boundaries
     */
    public Sonic2Level(Rom rom,
            int zoneIndex,
            int characterPaletteAddr,
            int levelPalettesAddr,
            int levelPalettesSize,
            LevelResourcePlan resourcePlan,
            int mapAddr,
            int solidTileHeightsAddr,
            int solidTileWidthsAddr,
            int solidTilesAngleAddr,
            List<ObjectSpawn> objectSpawns,
            List<RingSpawn> ringSpawns,
            RingSpriteSheet ringSpriteSheet,
            int levelBoundariesAddr) throws IOException {
        this.zoneIndex = zoneIndex;
        loadPalettes(rom, characterPaletteAddr, levelPalettesAddr, levelPalettesSize);
        loadPatternsWithPlan(rom, resourcePlan);
        loadSolidTiles(rom, solidTileHeightsAddr, solidTileWidthsAddr, solidTilesAngleAddr);
        loadChunksWithPlan(rom, resourcePlan);
        loadBlocksWithPlan(rom, resourcePlan);
        loadMap(rom, mapAddr);
        this.objects = List.copyOf(objectSpawns);
        this.rings = List.copyOf(ringSpawns);
        this.ringSpriteSheet = ringSpriteSheet;
        loadBoundaries(rom, levelBoundariesAddr);
    }

    @Override
    public int getPaletteCount() {
        return PALETTE_COUNT;
    }

    @Override
    public Palette getPalette(int index) {
        if (index >= PALETTE_COUNT) {
            throw new IllegalArgumentException("Invalid palette index");
        }
        return palettes[index];
    }

    @Override
    public int getPatternCount() {
        return patternCount;
    }

    @Override
    public Pattern getPattern(int index) {
        if (index >= patternCount) {
            throw new IllegalArgumentException("Invalid pattern index");
        }
        return patterns[index];
    }

    @Override
    public void ensurePatternCapacity(int minCount) {
        if (minCount <= patternCount) {
            return;
        }
        int newCount = Math.max(minCount, patternCount);
        patterns = Arrays.copyOf(patterns, newCount);
        GraphicsManager graphicsMan = GraphicsManager.getInstance();
        for (int i = patternCount; i < newCount; i++) {
            patterns[i] = new Pattern();
            if (graphicsMan.getGraphics() != null) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }
        }
        patternCount = newCount;
    }

    @Override
    public int getChunkCount() {
        return chunkCount;
    }

    @Override
    public Chunk getChunk(int index) {
        if (index >= chunkCount) {
            throw new IllegalArgumentException("Invalid chunk index: " + index);
        }
        return chunks[index];
    }

    @Override
    public int getBlockCount() {
        return blockCount;
    }

    @Override
    public Block getBlock(int index) {
        if (index >= blockCount) {
            throw new IllegalArgumentException("Invalid block index: " + index);
        }
        return blocks[index];
    }

    @Override
    public SolidTile getSolidTile(int index) {
        if (index >= solidTileCount) {
            throw new IllegalArgumentException("Invalid block index");
        }
        return solidTiles[index];
    }

    @Override
    public Map getMap() {
        return map;
    }

    @Override
    public List<ObjectSpawn> getObjects() {
        return objects;
    }

    @Override
    public List<RingSpawn> getRings() {
        return rings;
    }

    @Override
    public RingSpriteSheet getRingSpriteSheet() {
        return ringSpriteSheet;
    }

    @Override
    public int getMinX() {
        return minX;
    }

    @Override
    public int getMaxX() {
        return maxX;
    }

    @Override
    public int getMinY() {
        return minY;
    }

    @Override
    public int getMaxY() {
        return maxY;
    }

    private void loadPalettes(Rom rom, int characterPaletteAddr, int levelPalettesAddr, int levelPalettesSize)
            throws IOException {
        palettes = new Palette[PALETTE_COUNT];
        GraphicsManager graphicsMan = GraphicsManager.getInstance();

        // Load character palette
        byte[] buffer = rom.readBytes(characterPaletteAddr, Palette.PALETTE_SIZE_IN_ROM);
        palettes[0] = new Palette();
        palettes[0].fromSegaFormat(buffer);

        // Load level palettes
        // levelPalettesSize is the total size of bytes to read from the ROM.
        // We will read all of them, then slice them into palette-sized chunks.
        buffer = rom.readBytes(levelPalettesAddr, levelPalettesSize);

        // Calculate how many full palettes we have available in the data
        int loadedPalettes = levelPalettesSize / Palette.PALETTE_SIZE_IN_ROM;

        // Mega Drive has 4 palettes total. Palette 0 is character palette (already
        // loaded).
        // Palettes 1, 2, 3 are level palettes.
        for (int i = 0; i < PALETTE_COUNT - 1; i++) {
            palettes[i + 1] = new Palette();
            if (i < loadedPalettes) {
                // Use Arrays.copyOfRange to simulate pointer arithmetic and pass sub-arrays
                int start = i * Palette.PALETTE_SIZE_IN_ROM;
                int end = (i + 1) * Palette.PALETTE_SIZE_IN_ROM;
                // Ensure we don't go out of bounds if size is weird
                if (end <= buffer.length) {
                    byte[] subArray = Arrays.copyOfRange(buffer, start, end);
                    palettes[i + 1].fromSegaFormat(subArray);
                }
            }
        }

        if (graphicsMan.getGraphics() != null) {
            for (int i = 0; i < palettes.length; i++) {
                graphicsMan.cachePaletteTexture(palettes[i], i);
            }
        }

    }

    private void loadPatterns(Rom rom, int patternsAddr) throws IOException {
        final int PATTERN_BUFFER_SIZE = 0xFFFF; // 64KB
        GraphicsManager graphicsMan = GraphicsManager.getInstance();
        FileChannel channel = rom.getFileChannel();
        channel.position(patternsAddr);

        var result = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

        patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent pattern data");
        }

        patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);

            if (graphicsMan.getGraphics() != null) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }

        }

        LOG.fine("Pattern count: " + patternCount + " (" + result.length + " bytes)");
    }

    // TODO both collision addresses
    private void loadChunks(Rom rom, int chunksAddr, int collisionAddr, int altCollisionAddr) throws IOException {
        final int CHUNK_BUFFER_SIZE = 0xFFFF; // 64KB
        final int SOLID_TILE_REF_BUFFER_LENGTH = 0x300;

        FileChannel channel = rom.getFileChannel();
        channel.position(chunksAddr);

        byte[] chunkBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);
        chunkBuffer = applyAnimatedPatternMappings(rom, chunkBuffer);

        chunkCount = chunkBuffer.length / Chunk.CHUNK_SIZE_IN_ROM;
        if (chunkBuffer.length % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent chunk data");
        }

        channel.position(collisionAddr);

        byte[] solidTileRefBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

        channel.position(altCollisionAddr);

        byte[] solidTileAltRefBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

        chunks = new Chunk[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = new Chunk();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(chunkBuffer, i * Chunk.CHUNK_SIZE_IN_ROM,
                    (i + 1) * Chunk.CHUNK_SIZE_IN_ROM);
            int solidTileIndex = 0;
            if (i < solidTileRefBuffer.length) {
                solidTileIndex = Byte.toUnsignedInt(solidTileRefBuffer[i]);
            }
            int altSolidTileIndex = 0;
            if (i < solidTileAltRefBuffer.length) {
                altSolidTileIndex = Byte.toUnsignedInt(solidTileAltRefBuffer[i]);
            }
            chunks[i].fromSegaFormat(subArray, solidTileIndex, altSolidTileIndex);
        }

        LOG.fine("Chunk count: " + chunkCount + " (" + chunkBuffer.length + " bytes)");
    }

    public int getZoneIndex() {
        return zoneIndex;
    }

    private byte[] applyAnimatedPatternMappings(Rom rom, byte[] chunkBuffer) throws IOException {
        if (chunkBuffer == null || chunkBuffer.length == 0) {
            return chunkBuffer;
        }
        if (zoneIndex < 0 || zoneIndex >= 0x11) {
            return chunkBuffer;
        }
        int tableAddr = Sonic2Constants.ANIM_PAT_MAPS_ADDR;
        int offset = rom.read16BitAddr(tableAddr + zoneIndex * 2);
        if (offset == 0) {
            return chunkBuffer;
        }
        int listAddr = tableAddr + offset;
        int destOffset = rom.read16BitAddr(listAddr);
        if (destOffset == 0) {
            return chunkBuffer;
        }
        int wordCount = rom.read16BitAddr(listAddr + 2);
        int wordsToCopy = wordCount + 1; // bytesToWcnt(n) = n/2 - 1
        int srcAddr = listAddr + 4;
        int maxBytes = wordsToCopy * 2;
        int requiredSize = destOffset + maxBytes;
        if (requiredSize > chunkBuffer.length) {
            chunkBuffer = Arrays.copyOf(chunkBuffer, requiredSize);
        }
        int available = Math.min(maxBytes, chunkBuffer.length - destOffset);
        if (available <= 0) {
            return chunkBuffer;
        }
        wordsToCopy = available / 2;
        for (int i = 0; i < wordsToCopy; i++) {
            int value = rom.read16BitAddr(srcAddr + i * 2L);
            int dest = destOffset + i * 2;
            chunkBuffer[dest] = (byte) ((value >> 8) & 0xFF);
            chunkBuffer[dest + 1] = (byte) (value & 0xFF);
        }
        return chunkBuffer;
    }

    /**
     * @param rom
     * @param tileHeightsAddr
     * @param anglesAddr
     * @throws IOException
     */
    private void loadSolidTiles(Rom rom, int tileHeightsAddr, int tileWidthsAddr, int anglesAddr) throws IOException {

        solidTileCount = (Sonic2Constants.SOLID_TILE_MAP_SIZE + 1) / SolidTile.TILE_SIZE_IN_ROM;
        LOG.fine("how many solid tiles fit?:" + solidTileCount);

        byte[] solidTileHeightsBuffer = rom.readBytes(tileHeightsAddr, Sonic2Constants.SOLID_TILE_MAP_SIZE);
        byte[] solidTileWidthsBuffer = rom.readBytes(tileWidthsAddr, Sonic2Constants.SOLID_TILE_MAP_SIZE);

        if (solidTileHeightsBuffer.length % Sonic2Constants.SOLID_TILE_MAP_SIZE != 0) {
            throw new IOException("Inconsistent SolidTile data");
        }

        solidTiles = new SolidTile[solidTileCount];
        for (int i = 0; i < solidTileCount; i++) {
            byte tileAngle = rom.readByte(anglesAddr + i);
            byte[] totallyLegitimateHeightArraySir = Arrays.copyOfRange(solidTileHeightsBuffer,
                    i * SolidTile.TILE_SIZE_IN_ROM, (i + 1) * SolidTile.TILE_SIZE_IN_ROM);
            byte[] totallyLegitimateWidthArraySir = Arrays.copyOfRange(solidTileWidthsBuffer,
                    i * SolidTile.TILE_SIZE_IN_ROM, (i + 1) * SolidTile.TILE_SIZE_IN_ROM);

            solidTiles[i] = new SolidTile(i, totallyLegitimateHeightArraySir, totallyLegitimateWidthArraySir,
                    tileAngle);
        }

        LOG.fine("SolidTiles loaded");

    }

    private void loadBlocks(Rom rom, int blocksAddr) throws IOException {
        final int BLOCK_BUFFER_SIZE = 0xFFFF; // 64KB

        FileChannel channel = rom.getFileChannel();
        KosinskiReader reader = new KosinskiReader();

        channel.position(blocksAddr);
        byte[] blockBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

        blockCount = blockBuffer.length / LevelConstants.BLOCK_SIZE_IN_ROM;
        if (blockBuffer.length % LevelConstants.BLOCK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent block data");
        }

        blocks = new Block[blockCount];
        for (int i = 0; i < blockCount; i++) {
            blocks[i] = new Block();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(blockBuffer, i * LevelConstants.BLOCK_SIZE_IN_ROM,
                    (i + 1) * LevelConstants.BLOCK_SIZE_IN_ROM);

            blocks[i].fromSegaFormat(subArray);

        }

        // Sanitize Block 0: In Sonic 2, Block 0 is universally defined as "Empty".
        // If the ROM data for Block 0 contains garbage (or valid but unwanted tiles),
        // it corrupts "empty" space in the level. Forcing it to a clean empty block
        // fixes this.
        if (blockCount > 0) {
            blocks[0] = new Block();
        }

        LOG.fine("Block count: " + blockCount + " (" + blockBuffer.length + " bytes)");

    }

    private void loadMap(Rom rom, int mapAddr) throws IOException {
        final int MAP_BUFFER_SIZE = 0xFFFF; // 64KB

        FileChannel channel = rom.getFileChannel();
        channel.position(mapAddr);

        byte[] buffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

        if (buffer.length != MAP_LAYERS * MAP_HEIGHT * MAP_WIDTH) {
            throw new IOException("Inconsistent map data");
        }

        map = new Map(MAP_LAYERS, MAP_WIDTH, MAP_HEIGHT, buffer);

        LOG.fine("Map loaded successfully. Byte count: " + buffer.length);
    }

    private void loadBoundaries(Rom rom, int levelBoundariesAddr) throws IOException {
        // Each entry is 8 bytes:
        // 0-1: minX (unsigned)
        // 2-3: maxX (unsigned)
        // 4-5: minY (signed)
        // 6-7: maxY (signed)

        this.minX = rom.read16BitAddr(levelBoundariesAddr);
        this.maxX = rom.read16BitAddr(levelBoundariesAddr + 2);
        this.minY = (short) rom.read16BitAddr(levelBoundariesAddr + 4);
        this.maxY = (short) rom.read16BitAddr(levelBoundariesAddr + 6);
    }

    // ===== Resource Plan-based loading methods =====

    /**
     * Loads patterns using a LevelResourcePlan, supporting overlay composition.
     *
     * <p>For zones like HTZ, this loads the base EHZ_HTZ patterns first, then
     * overlays HTZ-specific patterns at the specified offset (0x3F80 bytes).
     */
    private void loadPatternsWithPlan(Rom rom, LevelResourcePlan plan) throws IOException {
        GraphicsManager graphicsMan = GraphicsManager.getInstance();
        ResourceLoader loader = new ResourceLoader(rom);

        // Use a large initial buffer - will be trimmed to actual size
        byte[] result = loader.loadWithOverlays(plan.getPatternOps(), 0x10000);

        patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent pattern data after overlay composition");
        }

        patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);

            if (graphicsMan.getGraphics() != null) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }
        }

        if (plan.hasPatternOverlays()) {
            LOG.info("Pattern count: " + patternCount + " (" + result.length + " bytes) [with overlays]");
        } else {
            LOG.fine("Pattern count: " + patternCount + " (" + result.length + " bytes)");
        }
    }

    /**
     * Loads chunks (16x16 tile mappings) using a LevelResourcePlan, supporting overlay composition.
     *
     * <p>For zones like HTZ, this loads the base EHZ chunks first, then overlays
     * HTZ-specific chunks at the specified offset (0x0980 bytes). This also
     * loads the collision indices from the plan.
     */
    private void loadChunksWithPlan(Rom rom, LevelResourcePlan plan) throws IOException {
        ResourceLoader loader = new ResourceLoader(rom);

        // Load chunk data (usually single source)
        byte[] chunkBuffer = loader.loadWithOverlays(plan.getChunkOps(), 0x10000);
        chunkBuffer = applyAnimatedPatternMappings(rom, chunkBuffer);

        chunkCount = chunkBuffer.length / Chunk.CHUNK_SIZE_IN_ROM;
        if (chunkBuffer.length % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent chunk data");
        }

        // Load collision indices from the plan
        byte[] solidTileRefBuffer;
        byte[] solidTileAltRefBuffer;

        LoadOp primaryCollision = plan.getPrimaryCollision();
        LoadOp secondaryCollision = plan.getSecondaryCollision();

        if (primaryCollision != null) {
            solidTileRefBuffer = loader.loadSingle(primaryCollision);
        } else {
            solidTileRefBuffer = new byte[0];
        }

        if (secondaryCollision != null) {
            solidTileAltRefBuffer = loader.loadSingle(secondaryCollision);
        } else {
            solidTileAltRefBuffer = new byte[0];
        }

        chunks = new Chunk[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = new Chunk();
            byte[] subArray = Arrays.copyOfRange(chunkBuffer, i * Chunk.CHUNK_SIZE_IN_ROM,
                    (i + 1) * Chunk.CHUNK_SIZE_IN_ROM);
            int solidTileIndex = 0;
            if (i < solidTileRefBuffer.length) {
                solidTileIndex = Byte.toUnsignedInt(solidTileRefBuffer[i]);
            }
            int altSolidTileIndex = 0;
            if (i < solidTileAltRefBuffer.length) {
                altSolidTileIndex = Byte.toUnsignedInt(solidTileAltRefBuffer[i]);
            }
            chunks[i].fromSegaFormat(subArray, solidTileIndex, altSolidTileIndex);
        }

        LOG.fine("Chunk count: " + chunkCount + " (" + chunkBuffer.length + " bytes)");
    }

    /**
     * Loads blocks (128x128 tile mappings) using a LevelResourcePlan, supporting overlay composition.
     *
     * <p>Most zones use shared block data without overlays (e.g., HTZ uses shared EHZ_HTZ blocks).
     * This method supports overlays for future zones that may need them.
     */
    private void loadBlocksWithPlan(Rom rom, LevelResourcePlan plan) throws IOException {
        ResourceLoader loader = new ResourceLoader(rom);

        // Load and compose block data with overlays, aligned to block size
        byte[] blockBuffer = loader.loadWithOverlaysAligned(
                plan.getBlockOps(), 0x10000, LevelConstants.BLOCK_SIZE_IN_ROM);

        blockCount = blockBuffer.length / LevelConstants.BLOCK_SIZE_IN_ROM;
        // Alignment is guaranteed by loadWithOverlaysAligned

        blocks = new Block[blockCount];
        for (int i = 0; i < blockCount; i++) {
            blocks[i] = new Block();
            byte[] subArray = Arrays.copyOfRange(blockBuffer, i * LevelConstants.BLOCK_SIZE_IN_ROM,
                    (i + 1) * LevelConstants.BLOCK_SIZE_IN_ROM);
            blocks[i].fromSegaFormat(subArray);
        }

        // Sanitize Block 0: In Sonic 2, Block 0 is universally defined as "Empty".
        if (blockCount > 0) {
            blocks[0] = new Block();
        }

        if (plan.hasBlockOverlays()) {
            LOG.info("Block count: " + blockCount + " (" + blockBuffer.length + " bytes) [with overlays]");
        } else {
            LOG.fine("Block count: " + blockCount + " (" + blockBuffer.length + " bytes)");
        }
    }
}
