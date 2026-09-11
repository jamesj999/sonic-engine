package com.openggf.game.sonic2;

import com.openggf.game.sonic2.constants.Sonic2Constants;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.*;
import com.openggf.level.resources.LevelResourcePlan;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.data.compression.KosinskiReader;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;

import com.openggf.data.RomManager;
import com.openggf.data.compression.NemesisReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

public class Sonic2Level extends AbstractLevel {
    private static final int MAP_LAYERS = 2;
    private static final int MAP_HEIGHT = 16;
    private static final int MAP_WIDTH = 128;

    private static final boolean KOS_DEBUG_LOG = false;

    private static final Logger LOG = Logger.getLogger(Sonic2Level.class.getName());

    /**
     * Decompressed size in bytes of the zone's 8x8 tile art -- LoadZoneTiles' {@code d3}
     * after {@code bsr.w KosDec / move.w a1,d3} (docs/s2disasm/s2.asm:6482-6483), which is
     * the Chunk_Table write pointer the Kosinski decode leaves behind, i.e. the composed
     * decompressed byte count. See {@link #getZoneTileArtByteSize()}.
     */
    private int zoneTileArtByteSize;

    /**
     * LoadZoneTiles' decompressed 8x8 art size in bytes (docs/s2disasm/s2.asm:6482).
     *
     * <p>LoadZoneTiles uploads this art in {@code $1000}-byte DMA chunks and spends one
     * {@code bsr.w WaitForVint} per chunk (:6519, inside the {@code dbf d7,-} loop spanning
     * :6506-6523). The chunk count is extracted as {@code rol.w #4,d7 / andi.w #$F,d7}
     * (:6485-6486) -- bits 15-12 of the byte size, i.e. {@code size / $1000} -- and
     * {@code dbf} runs the body {@code d7 + 1} times.
     *
     * <p>HTZ and WFZ decode a supplement over the base tileset and take {@code d3} from that
     * second decode, as the constants {@code ArtTile_ArtKos_NumTiles_HTZ / _WFZ} =
     * {@code Main + Sup - 1} record (:6488, :6494). This field follows the same rule -- it is
     * the last decode's end offset, not the composed buffer's extent -- which matters for
     * HTZ, whose supplement finishes inside the larger EHZ base it overlays. The remaining
     * one-tile difference against the ROM constants cannot move the {@code size / $1000}
     * quotient for either zone.
     */
    public int getZoneTileArtByteSize() {
        return zoneTileArtByteSize;
    }

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
        super(zoneIndex);
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
        super(zoneIndex);
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

    private void loadPalettes(Rom rom, int characterPaletteAddr, int levelPalettesAddr, int levelPalettesSize)
            throws IOException {
        palettes = new Palette[PALETTE_COUNT];
        GraphicsManager graphicsMan = GameServices.graphics();

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

        // "Knuckles in Sonic 2" lock-on: replace palette line 0 with the
        // S2-compatible Knuckles palette from the S3K ROM (0x060BEA).
        // Only indices 2-5 differ (Knuckles' reds vs Sonic's blues);
        // indices 0-1 and 6-15 are identical to S2's Pal_SonicTails.
        if (com.openggf.game.CrossGameFeatureProvider.isActive()) {
            String mainChar = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
            Palette hostPal = GameServices.crossGameFeatures()
                    .loadHostCompatiblePalette(mainChar);
            if (hostPal != null) {
                palettes[0] = hostPal;
            }
        }

        if (graphicsMan.isGlInitialized()) {
            for (int i = 0; i < palettes.length; i++) {
                graphicsMan.cachePaletteTexture(palettes[i], i);
            }
        }

    }

    private void loadPatterns(Rom rom, int patternsAddr) throws IOException {
        final int PATTERN_BUFFER_SIZE = 0xFFFF; // 64KB
        GraphicsManager graphicsMan = GameServices.graphics();
        byte[] result;
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(patternsAddr);
            result = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);
        }

        patternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent pattern data");
        }
        // LoadZoneTiles' move.w a1,d3 (docs/s2disasm/s2.asm:6483).
        zoneTileArtByteSize = result.length;

        patterns = new Pattern[patternCount];
        for (int i = 0; i < patternCount; i++) {
            patterns[i] = new Pattern();
            // Pass a sub-array (slice) using Arrays.copyOfRange
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);

            if (graphicsMan.isGlInitialized()) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }

        }

        LOG.fine("Pattern count: " + patternCount + " (" + result.length + " bytes)");
    }

    // Loads chunks with both primary and secondary collision indices.
    private void loadChunks(Rom rom, int chunksAddr, int collisionAddr, int altCollisionAddr) throws IOException {
        final int CHUNK_BUFFER_SIZE = 0xFFFF; // 64KB
        final int SOLID_TILE_REF_BUFFER_LENGTH = 0x300;

        byte[] chunkBuffer;
        byte[] solidTileRefBuffer;
        byte[] solidTileAltRefBuffer;
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(chunksAddr);
            chunkBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

            channel.position(collisionAddr);
            solidTileRefBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);

            channel.position(altCollisionAddr);
            solidTileAltRefBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);
        }
        chunkBuffer = applyAnimatedPatternMappings(rom, chunkBuffer);

        chunkCount = chunkBuffer.length / Chunk.CHUNK_SIZE_IN_ROM;
        if (chunkBuffer.length % Chunk.CHUNK_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent chunk data");
        }

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

        byte[] blockBuffer;
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(blocksAddr);
            blockBuffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);
        }

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

        byte[] buffer;
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(mapAddr);
            buffer = KosinskiReader.decompress(channel, KOS_DEBUG_LOG);
        }

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
     *
     * <p>HTZ also requires extended pattern space for dynamic art (mountains/clouds)
     * which are normally loaded by Dynamic_HTZ at runtime. We pre-fill these with
     * sky blue placeholder patterns to avoid garbled rendering.
     */
    private void loadPatternsWithPlan(Rom rom, LevelResourcePlan plan) throws IOException {
        GraphicsManager graphicsMan = GameServices.graphics();
        ResourceLoader loader = new ResourceLoader(rom);

        // Use a large initial buffer - will be trimmed to actual size
        byte[] result = loader.loadWithOverlays(plan.getPatternOps(), 0x10000);

        int loadedPatternCount = result.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (result.length % Pattern.PATTERN_SIZE_IN_ROM != 0) {
            throw new IOException("Inconsistent pattern data after overlay composition");
        }
        // LoadZoneTiles' d3. For an overlay zone the ROM does not keep the base decode's
        // a1: it runs a second KosDec for the supplement and takes the size from there
        // (docs/s2disasm/s2.asm:6485-6494 for HTZ, :6491-6497 for WFZ), so the size is the
        // last decode's end, not the composed buffer's extent -- HTZ's supplement finishes
        // inside the EHZ base it overlays, and the ROM discards the base tail. Recorded
        // before the HTZ dynamic-art padding below, which LoadZoneTiles never uploads
        // (PatchHTZTiles owns those tiles).
        zoneTileArtByteSize = loader.lastOpEndBytes();

        // For HTZ, extend pattern array to include dynamic art tile indices
        // The background map references tiles at $0500-$0520 (1280-1312) for mountains/clouds
        int requiredPatterns = loadedPatternCount;
        if (zoneIndex == Sonic2Constants.ZONE_HTZ) {
            requiredPatterns = Math.max(requiredPatterns, Sonic2Constants.HTZ_DYNAMIC_TILES_END);
        }

        patternCount = requiredPatterns;
        patterns = new Pattern[patternCount];

        // Load patterns from ROM data
        for (int i = 0; i < loadedPatternCount; i++) {
            patterns[i] = new Pattern();
            byte[] subArray = Arrays.copyOfRange(result, i * Pattern.PATTERN_SIZE_IN_ROM,
                    (i + 1) * Pattern.PATTERN_SIZE_IN_ROM);
            patterns[i].fromSegaFormat(subArray);

            if (graphicsMan.isGlInitialized()) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }
        }

        // Fill any extended patterns (for HTZ dynamic art region) with sky blue
        if (patternCount > loadedPatternCount) {
            fillHtzDynamicArtPatterns(graphicsMan, loadedPatternCount);
        }

        if (plan.hasPatternOverlays()) {
            LOG.info("Pattern count: " + patternCount + " (" + result.length + " bytes) [with overlays]");
        } else {
            LOG.fine("Pattern count: " + patternCount + " (" + result.length + " bytes)");
        }
    }

    /**
     * PatchHTZTiles equivalent: fills HTZ dynamic art pattern slots with actual
     * decompressed cliff art and uncompressed cloud art from the ROM.
     *
     * <p>Reference: s2.asm PatchHTZTiles (line 86777). The ROM decompresses
     * ArtNem_HTZCliffs and scatters 128-byte chunks to VRAM tile slots. The
     * first 24 tiles (6 strips × 4 tiles) go to $0500-$0517 (mountains).
     * Cloud art (ArtUnc_HTZClouds, 1024 bytes = 32 tiles) fills $0518-$051F
     * with the initial 8 tiles.
     *
     * <p>Dynamic_HTZ then streams position-specific mountain/cloud art every
     * frame at runtime, overwriting these initial values.
     */
    private void fillHtzDynamicArtPatterns(GraphicsManager graphicsMan, int startIndex) {
        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                fillEmptyPatterns(graphicsMan, startIndex);
                return;
            }

            // 1. Decompress cliff art (ArtNem_HTZCliffs → ~6KB)
            byte[] cliffArt;
            synchronized (rom) {
                FileChannel ch = rom.getFileChannel();
                ch.position(Sonic2Constants.ART_NEM_HTZ_CLIFFS_ADDR);
                cliffArt = NemesisReader.decompress(ch);
            }

            // 2. Fill mountain tile slots ($0500-$0517) with the first 24 tiles
            //    from decompressed cliff art (matching PatchHTZTiles' initial frame).
            int destTile = Sonic2Constants.HTZ_MOUNTAINS_TILE_INDEX;
            int srcOff = 0;
            int mountainsFilled = 0;
            for (int i = 0; i < Sonic2Constants.HTZ_MOUNTAINS_TILE_COUNT && destTile < patternCount; i++, destTile++) {
                patterns[destTile] = new Pattern();
                if (srcOff + Pattern.PATTERN_SIZE_IN_ROM <= cliffArt.length) {
                    byte[] tile = new byte[Pattern.PATTERN_SIZE_IN_ROM];
                    System.arraycopy(cliffArt, srcOff, tile, 0, Pattern.PATTERN_SIZE_IN_ROM);
                    patterns[destTile].fromSegaFormat(tile);
                    srcOff += Pattern.PATTERN_SIZE_IN_ROM;
                    mountainsFilled++;
                } else {
                    patterns[destTile].fromSegaFormat(new byte[Pattern.PATTERN_SIZE_IN_ROM]);
                }
                if (graphicsMan.isGlInitialized()) {
                    graphicsMan.cachePatternTexture(patterns[destTile], destTile);
                }
            }

            // 3. Load uncompressed cloud art and fill cloud tile slots ($0518-$051F)
            byte[] cloudArt = new byte[Sonic2Constants.ART_UNC_HTZ_CLOUDS_SIZE];
            synchronized (rom) {
                FileChannel ch = rom.getFileChannel();
                ch.position(Sonic2Constants.ART_UNC_HTZ_CLOUDS_ADDR);
                ByteBuffer buf = ByteBuffer.wrap(cloudArt);
                while (buf.hasRemaining()) {
                    if (ch.read(buf) < 0) break;
                }
            }
            destTile = Sonic2Constants.HTZ_CLOUDS_TILE_INDEX;
            srcOff = 0;
            int cloudsFilled = 0;
            for (int i = 0; i < Sonic2Constants.HTZ_CLOUDS_TILE_COUNT && destTile < patternCount; i++, destTile++) {
                patterns[destTile] = new Pattern();
                if (srcOff + Pattern.PATTERN_SIZE_IN_ROM <= cloudArt.length) {
                    byte[] tile = new byte[Pattern.PATTERN_SIZE_IN_ROM];
                    System.arraycopy(cloudArt, srcOff, tile, 0, Pattern.PATTERN_SIZE_IN_ROM);
                    patterns[destTile].fromSegaFormat(tile);
                    srcOff += Pattern.PATTERN_SIZE_IN_ROM;
                    cloudsFilled++;
                } else {
                    patterns[destTile].fromSegaFormat(new byte[Pattern.PATTERN_SIZE_IN_ROM]);
                }
                if (graphicsMan.isGlInitialized()) {
                    graphicsMan.cachePatternTexture(patterns[destTile], destTile);
                }
            }

            // 4. Fill any remaining slots between cloud end and patternCount with empty
            for (int i = Math.max(startIndex, Sonic2Constants.HTZ_DYNAMIC_TILES_END); i < patternCount; i++) {
                if (patterns[i] == null) {
                    patterns[i] = new Pattern();
                    patterns[i].fromSegaFormat(new byte[Pattern.PATTERN_SIZE_IN_ROM]);
                    if (graphicsMan.isGlInitialized()) {
                        graphicsMan.cachePatternTexture(patterns[i], i);
                    }
                }
            }
            // Also fill gaps between startIndex and mountain range
            for (int i = startIndex; i < Sonic2Constants.HTZ_MOUNTAINS_TILE_INDEX && i < patternCount; i++) {
                if (patterns[i] == null) {
                    patterns[i] = new Pattern();
                    patterns[i].fromSegaFormat(new byte[Pattern.PATTERN_SIZE_IN_ROM]);
                    if (graphicsMan.isGlInitialized()) {
                        graphicsMan.cachePatternTexture(patterns[i], i);
                    }
                }
            }

            LOG.info("HTZ PatchHTZTiles: filled " + mountainsFilled + " mountain tiles from cliff art ("
                    + cliffArt.length + " bytes), " + cloudsFilled + " cloud tiles from cloud art");

        } catch (IOException e) {
            LOG.warning("Failed to load HTZ dynamic art, using empty placeholders: " + e.getMessage());
            fillEmptyPatterns(graphicsMan, startIndex);
        }
    }

    private void fillEmptyPatterns(GraphicsManager graphicsMan, int startIndex) {
        byte[] empty = new byte[Pattern.PATTERN_SIZE_IN_ROM];
        for (int i = startIndex; i < patternCount; i++) {
            patterns[i] = new Pattern();
            patterns[i].fromSegaFormat(empty);
            if (graphicsMan.isGlInitialized()) {
                graphicsMan.cachePatternTexture(patterns[i], i);
            }
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
