package com.openggf.game.sonic1;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.*;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.PlcParser.PlcEntry;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.data.compression.EnigmaReader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.data.compression.NemesisReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

/**
 * Level implementation for Sonic the Hedgehog 1.
 *
 * <p>Key differences from Sonic 2:
 * <ul>
 *   <li>Blocks are 256x256 pixels (16x16 grid of 16x16 chunks), not 128x128</li>
 *   <li>Patterns are Nemesis compressed (not Kosinski)</li>
 *   <li>16x16 chunk mappings are Enigma compressed</li>
 *   <li>256x256 block mappings are Kosinski compressed</li>
 *   <li>Level layouts are uncompressed with a 2-byte header (width, height)</li>
 *   <li>Collision indices are uncompressed binary (not Kosinski)</li>
 *   <li>Sonic 1 has no separate ring placement - rings are objects</li>
 *   <li>Block word format differs: 0SSY X0II IIII IIII</li>
 * </ul>
 */
public class Sonic1Level extends AbstractLevel {
    private static final int V_PALETTE_RAM_ADDR = 0xFB00;
    private static final int S1_CHUNKS_PER_BLOCK = 256; // 16x16 grid
    private static final int S1_BLOCK_SIZE_IN_ROM = S1_CHUNKS_PER_BLOCK * LevelConstants.BYTES_PER_CHUNK; // 512 bytes
    private static final int S1_GRID_SIDE = 16;
    private static final int COLLISION_INDEX_MAX_SIZE = 0x300;

    private static final Logger LOG = Logger.getLogger(Sonic1Level.class.getName());

    // Loop flag data: bit 7 of original FG layout bytes (used by Sonic_Loops)
    private boolean[] fgLoopFlags;
    private int fgMapWidth;
    private int fgMapHeight;

    /** Maps each ring ObjectSpawn to its expanded RingSpawn positions. Used by Sonic1ObjectRegistry. */
    private java.util.Map<ObjectSpawn, List<RingSpawn>> ringSpawnMapping = java.util.Map.of();

    public Sonic1Level(Rom rom,
                       int zoneIndex,
                       int sonicPaletteId,
                       int levelPaletteId,
                       List<PlcEntry> patternCues,
                       int chunksAddr,
                       int blocksAddr,
                       int fgLayoutAddr,
                       int bgLayoutAddr,
                       int collisionIndexAddr,
                       int solidTileHeightsAddr,
                       int solidTileWidthsAddr,
                       int solidTileAnglesAddr,
                       List<ObjectSpawn> objectSpawns,
                       List<RingSpawn> ringSpawns,
                       RingSpriteSheet ringSpriteSheet,
                       int[] boundaries) throws IOException {
        super(zoneIndex);
        this.objects = List.copyOf(objectSpawns);
        this.rings = List.copyOf(ringSpawns);
        this.ringSpriteSheet = ringSpriteSheet;

        loadPalettes(rom, sonicPaletteId, levelPaletteId);
        loadPatterns(rom, patternCues);
        loadSolidTiles(rom, solidTileHeightsAddr, solidTileWidthsAddr, solidTileAnglesAddr);
        loadChunks(rom, chunksAddr, collisionIndexAddr);
        loadBlocks(rom, blocksAddr);
        loadMap(rom, fgLayoutAddr, bgLayoutAddr);
        verifyChunkPatternReferences();

        this.minX = boundaries[0];
        this.maxX = boundaries[1];
        this.minY = boundaries[2];
        this.maxY = boundaries[3];
    }

    // ===== Level interface overrides =====

    @Override
    public int getBlockPixelSize() {
        return 256;
    }

    @Override
    public int getChunksPerBlockSide() {
        return 16;
    }

    // ===== Loop flag accessors =====

    /**
     * Returns whether the FG layout cell at (mapX, mapY) has the loop flag (bit 7) set.
     * Used by Sonic1LoopManager to detect loop tiles.
     */
    public boolean hasLoopFlag(int mapX, int mapY) {
        if (fgLoopFlags == null || mapX < 0 || mapX >= fgMapWidth || mapY < 0 || mapY >= fgMapHeight) {
            return false;
        }
        return fgLoopFlags[mapY * fgMapWidth + mapX];
    }

    /**
     * Returns the raw FG layout value (block ID with loop flag) for a given map cell.
     * Reconstructs the original byte from the stripped map value + stored loop flag.
     */
    public int getRawFgValue(int mapX, int mapY) {
        if (map == null || mapX < 0 || mapX >= fgMapWidth || mapY < 0 || mapY >= fgMapHeight) {
            return 0;
        }
        int stripped = map.getValue(0, mapX, mapY) & 0xFF;
        if (hasLoopFlag(mapX, mapY)) {
            stripped |= 0x80;
        }
        return stripped;
    }

    /**
     * Resolves the collision block index for Sonic 1 loop tiles.
     * When a block has the loop flag set, collision uses the next block (index + 1),
     * with a special case: block 0x29 remaps to 0x51 (FindNearestTile in original ROM).
     */
    @Override
    public int resolveCollisionBlockIndex(int blockIndex, int mapX, int mapY) {
        if (!hasLoopFlag(mapX, mapY)) {
            return blockIndex;
        }
        int resolved = (blockIndex & 0x7F) + 1;
        if (resolved == Sonic1Constants.LOOP_BLOCK_REMAP_FROM) {
            resolved = Sonic1Constants.LOOP_BLOCK_REMAP_TO;
        }
        return resolved;
    }

    /**
     * Sets the mapping from ring ObjectSpawn entries to their expanded RingSpawn positions.
     * Called from {@link Sonic1} after {@code Sonic1RingPlacement.extract()} runs.
     */
    public void setRingSpawnMapping(java.util.Map<ObjectSpawn, List<RingSpawn>> mapping) {
        this.ringSpawnMapping = mapping != null ? mapping : java.util.Map.of();
    }

    /**
     * Returns the ring spawn mapping, or an empty map if none was set.
     */
    public java.util.Map<ObjectSpawn, List<RingSpawn>> getRingSpawnMapping() {
        return ringSpawnMapping;
    }

    // ===== Loading methods =====

    /**
     * Loads palettes by applying PalPointers entries in ROM order.
     * ROM parity path:
     * 1) PalLoad_Fade(palid_Sonic)
     * 2) PalLoad_Fade(level palette id from LevelDataLoad)
     */
    private void loadPalettes(Rom rom, int sonicPaletteId, int levelPaletteId) throws IOException {
        palettes = new Palette[PALETTE_COUNT];
        GraphicsManager graphicsMan = GameServices.graphics();

        for (int i = 0; i < PALETTE_COUNT; i++) {
            palettes[i] = new Palette();
        }
        applyPaletteEntry(rom, sonicPaletteId);
        applyPaletteEntry(rom, levelPaletteId);

        if (graphicsMan.isGlInitialized()) {
            for (int i = 0; i < palettes.length; i++) {
                graphicsMan.cachePaletteTexture(palettes[i], i);
            }
        }

        // Log first few colors of each palette line for debugging
        for (int p = 0; p < PALETTE_COUNT; p++) {
            Palette pal = palettes[p];
            StringBuilder sb = new StringBuilder();
            sb.append("Palette ").append(p).append(": ");
            for (int c = 0; c < Math.min(4, pal.getColorCount()); c++) {
                Palette.Color col = pal.getColor(c);
                sb.append(String.format("(%d,%d,%d) ", col.r & 0xFF, col.g & 0xFF, col.b & 0xFF));
            }
            sb.append("...");
            LOG.info(sb.toString());
        }
    }

    private void applyPaletteEntry(Rom rom, int paletteId) throws IOException {
        int entryAddr = Sonic1Constants.PALETTE_TABLE_ADDR + paletteId * 8;
        int sourceAddr = rom.read32BitAddr(entryAddr);
        int destinationAddr = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
        int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
        int dataBytes = (countWord + 1) * 4;

        byte[] data = rom.readBytes(sourceAddr, dataBytes);
        int destinationOffset = destinationAddr - V_PALETTE_RAM_ADDR;

        for (int dataOffset = 0; dataOffset + 1 < data.length; dataOffset += Palette.BYTES_PER_COLOR) {
            int paletteByteOffset = destinationOffset + dataOffset;
            if (paletteByteOffset < 0) {
                continue;
            }
            int paletteIndex = paletteByteOffset / Palette.PALETTE_SIZE_IN_ROM;
            if (paletteIndex < 0 || paletteIndex >= PALETTE_COUNT) {
                continue;
            }
            int colorIndex = (paletteByteOffset % Palette.PALETTE_SIZE_IN_ROM) / Palette.BYTES_PER_COLOR;
            if (colorIndex < 0 || colorIndex >= Palette.PALETTE_SIZE) {
                continue;
            }
            palettes[paletteIndex].getColor(colorIndex).fromSegaFormat(data, dataOffset);
        }
    }

    /**
     * Loads patterns from multiple PLC (Pattern Load Cue) entries using Nemesis decompression.
     *
     * <p>Each PLC entry specifies a ROM address and a destination tile offset. All entries
     * are loaded at their specified offsets, allowing non-contiguous placement. This handles
     * zones like GHZ which require two files: Nem_GHZ_1st at tile 0 and Nem_GHZ_2nd at 0x1CD.
     *
     * <p>Any gaps between entries are filled with empty patterns.
     */
    private void loadPatterns(Rom rom, List<PlcEntry> cues) throws IOException {
        GraphicsManager graphicsMan = GameServices.graphics();

        // Sort cues by tile offset
        List<PlcEntry> sorted = new ArrayList<>(cues);
        sorted.sort(Comparator.comparingInt(PlcEntry::tileIndex));

        // Decompress all PLC entries and place at their specified tile offsets
        List<byte[]> decompressedData = new ArrayList<>();
        List<PlcEntry> usedCues = new ArrayList<>();
        int maxTileIndex = 0;

        for (PlcEntry cue : sorted) {
            byte[] data;
            synchronized (rom) {
                FileChannel channel = rom.getFileChannel();
                channel.position(cue.romAddr());
                data = NemesisReader.decompress(channel);
            }
            decompressedData.add(data);
            usedCues.add(cue);

            int tileCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;
            int endTile = cue.tileIndex() + tileCount;
            if (endTile > maxTileIndex) {
                maxTileIndex = endTile;
            }

            LOG.info("PLC entry: romAddr=0x" + Integer.toHexString(cue.romAddr()) +
                    " tileOffset=0x" + Integer.toHexString(cue.tileIndex()) +
                    " tiles=" + tileCount + " endTile=0x" + Integer.toHexString(endTile));
        }

        // Build pattern array covering all loaded entries
        patternCount = maxTileIndex;
        patterns = new Pattern[patternCount];

        for (int i = 0; i < usedCues.size(); i++) {
            PlcEntry cue = usedCues.get(i);
            byte[] data = decompressedData.get(i);
            int tileCount = data.length / Pattern.PATTERN_SIZE_IN_ROM;

            for (int t = 0; t < tileCount; t++) {
                int patIdx = cue.tileIndex() + t;
                if (patIdx >= patternCount) break;
                patterns[patIdx] = new Pattern();
                byte[] subArray = Arrays.copyOfRange(data, t * Pattern.PATTERN_SIZE_IN_ROM,
                        (t + 1) * Pattern.PATTERN_SIZE_IN_ROM);
                patterns[patIdx].fromSegaFormat(subArray);

                if (graphicsMan.isGlInitialized()) {
                    graphicsMan.cachePatternTexture(patterns[patIdx], patIdx);
                }
            }
        }

        // Fill any gaps with empty patterns
        for (int i = 0; i < patternCount; i++) {
            if (patterns[i] == null) {
                patterns[i] = new Pattern();
                if (graphicsMan.isGlInitialized()) {
                    graphicsMan.cachePatternTexture(patterns[i], i);
                }
            }
        }

        LOG.info("Total pattern count: 0x" + Integer.toHexString(patternCount) +
                " (" + patternCount + ") from " + usedCues.size() + " PLC entries [Nemesis]");
    }

    /**
     * Loads 16x16 chunk mappings using Enigma decompression and raw collision index.
     *
     * <p>Sonic 1 collision indices are uncompressed binary data (not Kosinski like Sonic 2).
     * Both primary and secondary collision paths use the same index in Sonic 1.
     */
    private void loadChunks(Rom rom, int chunksAddr, int collisionIndexAddr) throws IOException {
        byte[] chunkBuffer;
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(chunksAddr);
            chunkBuffer = EnigmaReader.decompress(channel, 0);
        }

        chunkCount = chunkBuffer.length / Chunk.CHUNK_SIZE_IN_ROM;
        if (chunkBuffer.length % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent chunk data");
        }

        // Collision index is raw binary in Sonic 1 (not Kosinski compressed)
        int collisionSize = Math.min(COLLISION_INDEX_MAX_SIZE, chunkCount);
        byte[] collisionIndex = rom.readBytes(collisionIndexAddr, collisionSize);

        chunks = new Chunk[chunkCount];
        for (int i = 0; i < chunkCount; i++) {
            chunks[i] = new Chunk();
            byte[] subArray = Arrays.copyOfRange(chunkBuffer, i * Chunk.CHUNK_SIZE_IN_ROM,
                    (i + 1) * Chunk.CHUNK_SIZE_IN_ROM);
            int solidTileIndex = 0;
            if (i < collisionIndex.length) {
                solidTileIndex = Byte.toUnsignedInt(collisionIndex[i]);
            }
            // Sonic 1 uses same collision for both layers
            chunks[i].fromSegaFormat(subArray, solidTileIndex, solidTileIndex);
        }

        LOG.fine("Chunk count: " + chunkCount + " (" + chunkBuffer.length + " bytes) [Enigma]");
    }

    /**
     * Loads 256x256 block mappings using Kosinski decompression.
     *
     * <p>Sonic 1 block word format: {@code 0SSY X0II IIII IIII}
     * <ul>
     *   <li>Bits 0-9: Chunk index (10 bits)</li>
     *   <li>Bit 11: X flip</li>
     *   <li>Bit 12: Y flip</li>
     *   <li>Bits 13-14: Solidity</li>
     * </ul>
     *
     * <p>This must be converted to Sonic 2 ChunkDesc format:
     * {@code SSTT YXII IIII IIII}
     */
    private void loadBlocks(Rom rom, int blocksAddr) throws IOException {
        byte[] blockBuffer;
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(blocksAddr);
            blockBuffer = KosinskiReader.decompress(channel, false);
        }

        int dataBlockCount = blockBuffer.length / S1_BLOCK_SIZE_IN_ROM;
        if (blockBuffer.length % S1_BLOCK_SIZE_IN_ROM != 0) {
            LOG.warning("Block data not aligned to block size: " + blockBuffer.length +
                    " bytes, expected multiple of " + S1_BLOCK_SIZE_IN_ROM);
        }

        // Sonic 1 block IDs are 1-based in the layout: ID 0 = empty, ID 1 = first
        // block in the Kosinski data. The disassembly's GetBlockData does subq.b #1
        // before computing the offset. So we allocate one extra slot and store data
        // blocks starting at index 1, keeping index 0 as the empty block.
        blockCount = dataBlockCount + 1;
        blocks = new Block[blockCount];
        blocks[0] = new Block(S1_GRID_SIDE); // Empty block for ID 0

        for (int i = 0; i < dataBlockCount; i++) {
            blocks[i + 1] = new Block(S1_GRID_SIDE);
            byte[] subArray = Arrays.copyOfRange(blockBuffer, i * S1_BLOCK_SIZE_IN_ROM,
                    (i + 1) * S1_BLOCK_SIZE_IN_ROM);
            byte[] converted = convertS1BlockData(subArray);
            blocks[i + 1].fromSegaFormat(converted, S1_CHUNKS_PER_BLOCK);
        }

        LOG.fine("Block count: " + blockCount + " (data=" + dataBlockCount +
                ", " + blockBuffer.length + " bytes) [Kosinski]");
    }

    /**
     * Converts Sonic 1 block word data to Sonic 2 ChunkDesc format.
     *
     * <p>S1 format: {@code 0SSY X0II IIII IIII}
     * <br>S2 format: {@code SSTT YXII IIII IIII}
     *
     * <p>Sonic 1 has a single solidity field; we duplicate it for both
     * primary and secondary collision.
     */
    private byte[] convertS1BlockData(byte[] s1Data) {
        byte[] result = new byte[s1Data.length];
        for (int i = 0; i < s1Data.length; i += 2) {
            int s1Word = ((s1Data[i] & 0xFF) << 8) | (s1Data[i + 1] & 0xFF);

            // S1: 0SSY X0II IIII IIII
            int chunkIndex = s1Word & 0x03FF;            // bits 0-9
            boolean xFlip = (s1Word & 0x0800) != 0;      // bit 11
            boolean yFlip = (s1Word & 0x1000) != 0;       // bit 12
            int solidity = (s1Word >> 13) & 0x3;           // bits 13-14

            // S2: SSTT YXII IIII IIII
            int s2Word = chunkIndex
                    | (xFlip ? 0x0400 : 0)       // bit 10
                    | (yFlip ? 0x0800 : 0)       // bit 11
                    | ((solidity & 0x3) << 12)   // bits 12-13 (primary)
                    | ((solidity & 0x3) << 14);  // bits 14-15 (secondary = same in S1)

            result[i] = (byte) ((s2Word >> 8) & 0xFF);
            result[i + 1] = (byte) (s2Word & 0xFF);
        }
        return result;
    }

    /**
     * Loads level layouts. Sonic 1 layouts are uncompressed with a 2-byte header:
     * byte 0 = (width - 1), byte 1 = (height - 1).
     */
    private void loadMap(Rom rom, int fgAddr, int bgAddr) throws IOException {
        // FG layout
        byte[] fgHeader = rom.readBytes(fgAddr, 2);
        int fgWidth = (fgHeader[0] & 0xFF) + 1;
        int fgHeight = (fgHeader[1] & 0xFF) + 1;
        byte[] fgData = rom.readBytes(fgAddr + 2, fgWidth * fgHeight);

        // BG layout
        byte[] bgHeader = rom.readBytes(bgAddr, 2);
        int bgWidth = (bgHeader[0] & 0xFF) + 1;
        int bgHeight = (bgHeader[1] & 0xFF) + 1;
        byte[] bgData = rom.readBytes(bgAddr + 2, bgWidth * bgHeight);

        // Preserve loop flags (bit 7) from FG layout before stripping
        this.fgMapWidth = fgWidth;
        this.fgMapHeight = fgHeight;
        this.fgLoopFlags = new boolean[fgWidth * fgHeight];
        for (int y = 0; y < fgHeight; y++) {
            for (int x = 0; x < fgWidth; x++) {
                fgLoopFlags[y * fgWidth + x] = (fgData[y * fgWidth + x] & 0x80) != 0;
            }
        }

        // Build Map with 2 layers
        int mapWidth = Math.max(fgWidth, bgWidth);
        int mapHeight = Math.max(fgHeight, bgHeight);
        byte[] mapBuffer = new byte[2 * mapWidth * mapHeight];

        // Copy FG (layer 0) - strip loop flag (bit 7)
        for (int y = 0; y < fgHeight; y++) {
            for (int x = 0; x < fgWidth; x++) {
                mapBuffer[y * mapWidth * 2 + x] = (byte) (fgData[y * fgWidth + x] & 0x7F);
            }
        }

        // Copy BG (layer 1) - strip loop flag (bit 7).
        // Tile BG data to fill the full unified map dimensions, since the engine's
        // Map wraps at mapWidth/mapHeight, not the BG's original dimensions.
        // GHZ BG is 32x1 but needs to tile across the full 48x5 FG area.
        for (int y = 0; y < mapHeight; y++) {
            int srcY = y % bgHeight;
            for (int x = 0; x < mapWidth; x++) {
                int srcX = x % bgWidth;
                mapBuffer[y * mapWidth * 2 + mapWidth + x] =
                        (byte) (bgData[srcY * bgWidth + srcX] & 0x7F);
            }
        }

        map = new Map(2, mapWidth, mapHeight, mapBuffer);

        LOG.fine("Map loaded: FG=" + fgWidth + "x" + fgHeight + ", BG=" + bgWidth + "x" + bgHeight);

        // Diagnostic: log BG layout block IDs for verification against disassembly
        if (LOG.isLoggable(java.util.logging.Level.INFO)) {
            StringBuilder sb = new StringBuilder("BG layout block IDs (first 32): ");
            for (int i = 0; i < Math.min(bgWidth, 32); i++) {
                if (i > 0) sb.append(", ");
                sb.append(String.format("0x%02X", bgData[i] & 0xFF));
            }
            LOG.info(sb.toString());
            // Log first two blocks (visible in 512px tilemap at bgscreenposx=0)
            LOG.info("BG tilemap shows blocks: " +
                    String.format("0x%02X", bgData[0] & 0x7F) + " (pos 0-255), " +
                    String.format("0x%02X", bgData[1] & 0x7F) + " (pos 256-511)");
        }
    }

    /**
     * Loads solid tile height/width arrays and angle map.
     * Same format as Sonic 2.
     */
    private void loadSolidTiles(Rom rom, int heightsAddr, int widthsAddr, int anglesAddr) throws IOException {
        solidTileCount = (Sonic1Constants.SOLID_TILE_MAP_SIZE + 1) / SolidTile.TILE_SIZE_IN_ROM;

        byte[] heights = rom.readBytes(heightsAddr, Sonic1Constants.SOLID_TILE_MAP_SIZE);
        byte[] widths = rom.readBytes(widthsAddr, Sonic1Constants.SOLID_TILE_MAP_SIZE);

        solidTiles = new SolidTile[solidTileCount];
        for (int i = 0; i < solidTileCount; i++) {
            byte angle = rom.readByte(anglesAddr + i);
            byte[] h = Arrays.copyOfRange(heights, i * SolidTile.TILE_SIZE_IN_ROM,
                    (i + 1) * SolidTile.TILE_SIZE_IN_ROM);
            byte[] w = Arrays.copyOfRange(widths, i * SolidTile.TILE_SIZE_IN_ROM,
                    (i + 1) * SolidTile.TILE_SIZE_IN_ROM);
            solidTiles[i] = new SolidTile(i, h, w, angle);
        }

        LOG.fine("SolidTiles loaded: " + solidTileCount);
    }

    /**
     * Verifies that all chunk pattern references are within the loaded pattern count.
     * Logs warnings for any out-of-range references that would appear as blank tiles.
     */
    private void verifyChunkPatternReferences() {
        int outOfRange = 0;
        int maxRefIndex = 0;
        for (int i = 0; i < chunkCount; i++) {
            Chunk chunk = chunks[i];
            for (int py = 0; py < 2; py++) {
                for (int px = 0; px < 2; px++) {
                    PatternDesc pd = chunk.getPatternDesc(px, py);
                    int pidx = pd.getPatternIndex();
                    if (pidx > maxRefIndex) {
                        maxRefIndex = pidx;
                    }
                    if (pidx >= patternCount && pidx != 0) {
                        outOfRange++;
                    }
                }
            }
        }
        LOG.info("Chunk pattern verification: maxRefIndex=0x" + Integer.toHexString(maxRefIndex) +
                " patternCount=0x" + Integer.toHexString(patternCount) +
                " outOfRange=" + outOfRange);
        if (outOfRange > 0) {
            LOG.warning(outOfRange + " chunk pattern references exceed loaded pattern count!");
        }
    }
}
