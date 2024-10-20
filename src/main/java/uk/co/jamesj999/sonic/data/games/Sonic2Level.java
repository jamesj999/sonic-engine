package uk.co.jamesj999.sonic.data.games;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.level.*;
import uk.co.jamesj999.sonic.tools.KosinskiReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
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

    private int patternCount;
    private int chunkCount;
    private int blockCount;
    private int solidTileCount;

    private static final Logger LOG = Logger.getLogger(Sonic2Level.class.getName());

    public Sonic2Level(Rom rom,
                       int characterPaletteAddr,
                       int levelPalettesAddr,
                       int patternsAddr,
                       int chunksAddr,
                       int blocksAddr,
                       int mapAddr,
                       int collisionsAddr,
                       int altCollisionsAddr,
                       int solidTileHeightsAddr,
                       int solidTileWidthsAddr,
                       int solidTilesAngleAddr) throws IOException {
        loadPalettes(rom, characterPaletteAddr, levelPalettesAddr);
        loadPatterns(rom, patternsAddr);
        loadSolidTiles(rom, solidTileHeightsAddr, solidTileWidthsAddr, solidTilesAngleAddr);
        loadChunks(rom, chunksAddr, collisionsAddr, altCollisionsAddr);
        loadBlocks(rom, blocksAddr);
        loadMap(rom, mapAddr);

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

    private void loadPalettes(Rom rom, int characterPaletteAddr, int levelPalettesAddr) throws IOException {
        palettes = new Palette[PALETTE_COUNT];

        // Load character palette
        byte[] buffer = rom.readBytes(characterPaletteAddr, Palette.PALETTE_SIZE_IN_ROM);
        palettes[0] = new Palette();
        palettes[0].fromSegaFormat(buffer);

        // Load level palettes
        buffer = rom.readBytes(levelPalettesAddr, Palette.PALETTE_SIZE_IN_ROM * 3);
        for (int i = 0; i < 3; i++) {
            palettes[i + 1] = new Palette();
            // Use Arrays.copyOfRange to simulate pointer arithmetic and pass sub-arrays
            byte[] subArray = Arrays.copyOfRange(buffer, i * Palette.PALETTE_SIZE_IN_ROM, (i + 1) * Palette.PALETTE_SIZE_IN_ROM);
            palettes[i + 1].fromSegaFormat(subArray);
        }
    }

    private void loadPatterns(Rom rom, int patternsAddr) throws IOException {
        final int PATTERN_BUFFER_SIZE = 0xFFFF; // 64KB
        byte[] buffer = new byte[PATTERN_BUFFER_SIZE];
        FileChannel channel = rom.getFileChannel();
        channel.position(patternsAddr);

        KosinskiReader reader = new KosinskiReader();
        var result = reader.decompress(channel, buffer, PATTERN_BUFFER_SIZE);
        if (!result.success()) {
            throw new IOException("Pattern decompression failed");
        }

        patternCount = result.byteCount() / Pattern.PATTERN_SIZE_IN_ROM;
        if (result.byteCount() % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent pattern data");
        }

        patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(buffer, i * Pattern.PATTERN_SIZE_IN_ROM, (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);
        }

        LOG.info("Pattern count: " + patternCount + " (" + result.byteCount() + " bytes)");
    }

    //TODO both collision addresses
    private void loadChunks(Rom rom, int chunksAddr, int collisionAddr, int altCollisionAddr) throws IOException {
        final int CHUNK_BUFFER_SIZE = 0xFFFF; // 64KB
        final int SOLID_TILE_REF_BUFFER_LENGTH = 0x300;

        byte[] chunkBuffer = new byte[CHUNK_BUFFER_SIZE];
        byte[] solidTileRefBuffer = new byte[SOLID_TILE_REF_BUFFER_LENGTH];
        byte[] solidTileAltRefBuffer = new byte[SOLID_TILE_REF_BUFFER_LENGTH];

        FileChannel channel = rom.getFileChannel();
        channel.position(chunksAddr);

        KosinskiReader reader = new KosinskiReader();
        var result = reader.decompress(channel, chunkBuffer, CHUNK_BUFFER_SIZE);
        if (!result.success()) {
            throw new IOException("Chunk decompression error");
        }

        chunkCount = result.byteCount() / Chunk.CHUNK_SIZE_IN_ROM;
        if (result.byteCount() % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent chunk data");
        }

        channel.position(collisionAddr);

        result = reader.decompress(channel, solidTileRefBuffer, SOLID_TILE_REF_BUFFER_LENGTH);

        if (!result.success()) {
            throw new IOException("Collision decompression error");
        }

        channel.position(altCollisionAddr);

        result = reader.decompress(channel, solidTileAltRefBuffer, SOLID_TILE_REF_BUFFER_LENGTH);

        if (!result.success()) {
            throw new IOException("Alt Collision decompression error");
        }

        chunks = new Chunk[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = new Chunk();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(chunkBuffer, i * Chunk.CHUNK_SIZE_IN_ROM, (i + 1) * Chunk.CHUNK_SIZE_IN_ROM);
            int solidTileIndex = Byte.toUnsignedInt(solidTileRefBuffer[i]);
            int altSolidTileIndex = Byte.toUnsignedInt(solidTileAltRefBuffer[i]);
            chunks[i].fromSegaFormat(subArray, solidTileIndex, altSolidTileIndex);
        }

        LOG.info("Chunk count: " + chunkCount + " (" + result.byteCount() + " bytes)");
    }

    /**
     *
     * @param rom
     * @param tileHeightsAddr
     * @param anglesAddr
     * @throws IOException
     */
    private void loadSolidTiles(Rom rom, int tileHeightsAddr, int tileWidthsAddr, int anglesAddr) throws IOException {

        solidTileCount = (Sonic2.SOLID_TILE_MAP_SIZE+1) / SolidTile.TILE_SIZE_IN_ROM;
        LOG.info("how many solid tiles fit?:" + solidTileCount);

        byte[] solidTileHeightsBuffer = rom.readBytes(tileHeightsAddr, Sonic2.SOLID_TILE_MAP_SIZE);
        byte[] solidTileWidthsBuffer = rom.readBytes(tileWidthsAddr, Sonic2.SOLID_TILE_MAP_SIZE);

        if (solidTileHeightsBuffer.length % Sonic2.SOLID_TILE_MAP_SIZE != 0) {
            throw new IOException("Inconsistent SolidTile data");
        }

        solidTiles = new SolidTile[solidTileCount];
        for(int i = 0; i < solidTileCount; i++) {
            byte tileAngle = rom.readByte(anglesAddr+i);
            byte[] totallyLegitimateHeightArraySir = Arrays.copyOfRange(solidTileHeightsBuffer, i * SolidTile.TILE_SIZE_IN_ROM, (i+ 1) * SolidTile.TILE_SIZE_IN_ROM);
            byte[] totallyLegitimateWidthArraySir = Arrays.copyOfRange(solidTileWidthsBuffer, i * SolidTile.TILE_SIZE_IN_ROM, (i+ 1) * SolidTile.TILE_SIZE_IN_ROM);

            solidTiles[i] = new SolidTile(i, totallyLegitimateHeightArraySir, totallyLegitimateWidthArraySir, tileAngle);
        }

        LOG.info("SolidTiles loaded");

    }

    private void loadBlocks(Rom rom, int blocksAddr) throws IOException {
        final int BLOCK_BUFFER_SIZE = 0xFFFF; // 64KB
        
        byte[] blockBuffer = new byte[BLOCK_BUFFER_SIZE];



        FileChannel channel = rom.getFileChannel();
        KosinskiReader reader = new KosinskiReader();

        channel.position(blocksAddr);
        var result = reader.decompress(channel, blockBuffer, BLOCK_BUFFER_SIZE);

        if (!result.success()) {
            throw new IOException("Block decompression error");
        }

        blockCount = result.byteCount() / Block.BLOCK_SIZE_IN_ROM;
        if (result.byteCount() % Block.BLOCK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent block data");
        }

        blocks = new Block[blockCount];
        for (int i = 0; i < blockCount; i++) {
            blocks[i] = new Block();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(blockBuffer, i * Block.BLOCK_SIZE_IN_ROM, (i + 1) * Block.BLOCK_SIZE_IN_ROM);

            blocks[i].fromSegaFormat(subArray);

        }

        LOG.info("Block count: " + blockCount + " (" + result.byteCount() + " bytes)");

    }

    private void loadMap(Rom rom, int mapAddr) throws IOException {
        final int MAP_BUFFER_SIZE = 0xFFFF; // 64KB
        byte[] buffer = new byte[MAP_BUFFER_SIZE];
        FileChannel channel = rom.getFileChannel();
        channel.position(mapAddr);

        KosinskiReader reader = new KosinskiReader();
        var result = reader.decompress(channel, buffer, MAP_BUFFER_SIZE);
        if (!result.success()) {
            throw new IOException("Map decompression error");
        }

        if (result.byteCount() != MAP_LAYERS * MAP_HEIGHT * MAP_WIDTH) {
            throw new IOException("Inconsistent map data");
        }

        map = new Map(MAP_LAYERS, MAP_WIDTH, MAP_HEIGHT, buffer);

        System.out.println("Map loaded successfully. Byte count: " + result.success());
    }
}
