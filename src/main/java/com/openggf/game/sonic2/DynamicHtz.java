package com.openggf.game.sonic2;

import com.openggf.data.Rom;
import com.openggf.game.GameServices;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.Level;
import com.openggf.level.Pattern;
import com.openggf.game.sonic2.scroll.SwScrlHtz;
import com.openggf.data.compression.NemesisReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.logging.Logger;

/**
 * Implements Dynamic_HTZ from Sonic 2 disassembly.
 * Reference: s2.asm Dynamic_HTZ at ROM $3FD22 (lines 85503-85632)
 *
 * This handles:
 * 1. Mountain art streaming based on camera X position
 * 2. Cloud art streaming based on TempArray_LayerDef values from SwScrl_HTZ
 *
 * The cliff art is Nemesis compressed and decompresses to approximately 6KB.
 * The cloud art is uncompressed (1024 bytes = 32 tiles).
 *
 * Mountain tiles go to VRAM index $0500 (24 tiles: 6 strips of 4 tiles each).
 * Cloud tiles go to VRAM index $0518 (8 tiles).
 */
public class DynamicHtz {
    private static final Logger LOG = Logger.getLogger(DynamicHtz.class.getName());

    // Art data loaded from files
    private byte[] cliffArtData;    // Decompressed cliff art
    private byte[] cloudArtData;    // Uncompressed cloud art (1024 bytes)

    // RAM buffer for cloud composition (8 tiles = 256 bytes)
    // Organized as 4 "super-rows" of 64 bytes each
    private final byte[] cloudBuffer = new byte[256];

    // Last mountain frame index to detect changes (Anim_Counters+1 equivalent)
    private int lastMountainFrame = -1;

    // Offset table for mountain art (from disassembly lines 85554-85570)
    // Each row of 6 offsets defines which art strips to load (6 strips of 4 tiles = 24 tiles)
    // 16 rows total, each row duplicated giving 96 entries
    // ROM: word_3FD9C / .offsets
    private static final int[] MOUNTAIN_OFFSETS = {
        0x0080, 0x0180, 0x0280, 0x0580, 0x0600, 0x0700,  // Row 0
        0x0080, 0x0180, 0x0280, 0x0580, 0x0600, 0x0700,  // Row 1 (dup)
        0x0980, 0x0A80, 0x0B80, 0x0C80, 0x0D00, 0x0D80,  // Row 2
        0x0980, 0x0A80, 0x0B80, 0x0C80, 0x0D00, 0x0D80,  // Row 3 (dup)
        0x0E80, 0x1180, 0x1200, 0x1280, 0x1300, 0x1380,  // Row 4
        0x0E80, 0x1180, 0x1200, 0x1280, 0x1300, 0x1380,  // Row 5 (dup)
        0x1400, 0x1480, 0x1500, 0x1580, 0x1600, 0x1900,  // Row 6
        0x1400, 0x1480, 0x1500, 0x1580, 0x1600, 0x1900,  // Row 7 (dup)
        0x1D00, 0x1D80, 0x1E00, 0x1F80, 0x2400, 0x2580,  // Row 8
        0x1D00, 0x1D80, 0x1E00, 0x1F80, 0x2400, 0x2580,  // Row 9 (dup)
        0x2600, 0x2680, 0x2780, 0x2B00, 0x2F00, 0x3280,  // Row 10
        0x2600, 0x2680, 0x2780, 0x2B00, 0x2F00, 0x3280,  // Row 11 (dup)
        0x3600, 0x3680, 0x3780, 0x3C80, 0x3D00, 0x3F00,  // Row 12
        0x3600, 0x3680, 0x3780, 0x3C80, 0x3D00, 0x3F00,  // Row 13 (dup)
        0x3F80, 0x4080, 0x4480, 0x4580, 0x4880, 0x4900,  // Row 14
        0x3F80, 0x4080, 0x4480, 0x4580, 0x4880, 0x4900   // Row 15 (dup)
    };

    // Mapping from offset table RAM addresses to source byte offsets in decompressed art.
    // Built by simulating PatchHTZTiles: it reads 128-byte chunks sequentially from
    // decompressed data and scatters them to the RAM addresses in the offset table.
    // Uses only unique rows (0,2,4,6,8,10,12,14) - 8 rows × 6 columns = 48 patches × 128 bytes = 6144 bytes.
    private static final java.util.Map<Integer, Integer> OFFSET_TO_SOURCE = new java.util.HashMap<>();
    static {
        // PatchHTZTiles processes 8 unique rows, 6 offsets each
        // Unique rows are at table indices 0, 12, 24, 36, 48, 60, 72, 84 (every other row of 12)
        int sourceOffset = 0;
        for (int row = 0; row < 8; row++) {
            int tableBase = row * 12; // Each unique row is followed by its duplicate
            for (int col = 0; col < 6; col++) {
                int ramAddress = MOUNTAIN_OFFSETS[tableBase + col];
                OFFSET_TO_SOURCE.put(ramAddress, sourceOffset);
                sourceOffset += 128; // 4 tiles = 128 bytes per patch
            }
        }
    }

    private boolean initialized = false;

    public DynamicHtz() {
    }

    /**
     * Initialize by loading art data from the ROM.
     * Cliff art is Nemesis compressed at 0x49A14.
     * Cloud art is uncompressed at 0x4A33E (1024 bytes).
     */
    public void init() {
        if (initialized) {
            return;
        }

        try {
            Rom rom = GameServices.rom().getRom();
            if (rom == null) {
                LOG.warning("Cannot initialize DynamicHtz: ROM not loaded");
                return;
            }

            loadCloudArt(rom);
            loadCliffArt(rom);
            initialized = true;
            LOG.info("DynamicHtz initialized - cloud art: " +
                     (cloudArtData != null ? cloudArtData.length : 0) + " bytes, cliff art: " +
                     (cliffArtData != null ? cliffArtData.length : 0) + " bytes");
        } catch (IOException e) {
            LOG.log(java.util.logging.Level.WARNING, "Failed to load HTZ dynamic art from ROM: " + e.getMessage(), e);
        }
    }

    private void loadCloudArt(Rom rom) throws IOException {
        cloudArtData = new byte[Sonic2Constants.ART_UNC_HTZ_CLOUDS_SIZE];
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(Sonic2Constants.ART_UNC_HTZ_CLOUDS_ADDR);
            ByteBuffer buffer = ByteBuffer.wrap(cloudArtData);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    throw new IOException("Unexpected EOF reading HTZ cloud art");
                }
            }
        }
        LOG.fine("Loaded HTZ cloud art from ROM: " + cloudArtData.length + " bytes");
    }

    private void loadCliffArt(Rom rom) throws IOException {
        synchronized (rom) {
            FileChannel channel = rom.getFileChannel();
            channel.position(Sonic2Constants.ART_NEM_HTZ_CLIFFS_ADDR);
            cliffArtData = NemesisReader.decompress(channel);
        }
        LOG.fine("Loaded and decompressed HTZ cliff art from ROM: " + cliffArtData.length + " bytes");
    }

    /**
     * Update the dynamic HTZ art based on camera position and scroll values.
     * Should be called every frame.
     *
     * @param level       The level object containing pattern data
     * @param cameraX     Current camera X position
     * @param htzHandler  The HTZ scroll handler with TempArray_LayerDef values
     */
    public void update(Level level, int cameraX, SwScrlHtz htzHandler) {
        if (!initialized || level == null) {
            return;
        }

        updateMountainArt(level, cameraX);
        updateCloudArt(level, cameraX, htzHandler);
    }

    /**
     * Update mountain art tiles based on camera X position.
     * Reference: s2.asm Dynamic_HTZ lines 85508-85549
     *
     * Algorithm:
     * d1 = -cameraX >> 3
     * d0 = cameraX >> 4
     * d0 = d1 + d0 - $10
     * d0 = d0 / $30 (divu.w, then swap to get remainder in low word)
     * frame = remainder (used for table indexing)
     * Check if frame changed from last time
     * Calculate table offset: (frame & 7) * 12 + ((frame & $38) >> 2)
     * Load 6 strips of 4 tiles each
     */
    private void updateMountainArt(Level level, int cameraX) {
        if (cliffArtData == null || cliffArtData.length == 0) {
            return;
        }

        // Calculate mountain frame index matching disassembly exactly
        // d1 = neg.w cameraX / asr.w #3
        int d1 = (short)((-cameraX) >> 3);
        // d0 = cameraX / lsr.w #4
        int d0 = (cameraX >> 4) & 0xFFFF;
        // d0 = d1 + d0 - $10
        d0 = ((d1 + d0) - 0x10) & 0xFFFF;

        // divu.w #$30,d0 - unsigned division
        // After divu: d0.low = quotient, d0.high = remainder
        // After swap: d0.low = remainder, d0.high = quotient
        // The code uses the REMAINDER (d0.low after swap) for the frame index
        int remainder = (d0 & 0xFFFF) % 0x30;
        int mountainFrame = remainder & 0xFF;

        // Check if we need to update (cmp.b 1(a3),d0 / beq.s .skipMountainArt)
        if (mountainFrame == lastMountainFrame) {
            return;
        }
        lastMountainFrame = mountainFrame;

        // Calculate offset into MOUNTAIN_OFFSETS table
        // Disassembly calculates BYTE offset, but our array is WORD indexed
        // Byte offset = (remainder & 7) * 24 + ((remainder & 0x38) >> 2)
        // Word offset = byte_offset / 2 = (remainder & 7) * 12 + ((remainder & 0x38) >> 3)
        int tableBase = (mountainFrame & 7) * 12;  // Row of 12 words (6 pairs)
        int tableOffset = ((mountainFrame & 0x38) >> 3);  // Additional word offset (>> 3 not >> 2)
        int offsetIdx = tableBase + tableOffset;

        // Clamp to valid range
        if (offsetIdx >= MOUNTAIN_OFFSETS.length - 5) {
            offsetIdx = MOUNTAIN_OFFSETS.length - 6;
        }

        GraphicsManager graphicsMan = GameServices.graphics();

        // Load 6 mountain strips (4 tiles each = 24 tiles total)
        // The MOUNTAIN_OFFSETS are RAM addresses. Use the OFFSET_TO_SOURCE mapping
        // to find the actual byte offset in the decompressed cliff art data.
        int artLen = cliffArtData.length;

        for (int strip = 0; strip < 6; strip++) {
            int ramAddress = MOUNTAIN_OFFSETS[offsetIdx + strip];

            // Look up the source offset for this RAM address
            Integer srcOffsetObj = OFFSET_TO_SOURCE.get(ramAddress);
            if (srcOffsetObj == null) {
                // Offset not in mapping - shouldn't happen with correct table
                continue;
            }
            int srcOffset = srcOffsetObj;

            // Sanity check bounds
            if (srcOffset < 0 || srcOffset >= artLen) {
                continue;
            }

            int destTileIndex = Sonic2Constants.HTZ_MOUNTAINS_TILE_INDEX + strip * 4;

            // Copy 4 tiles (128 bytes) from cliff art to level patterns
            for (int tile = 0; tile < 4; tile++) {
                int srcTileOffset = srcOffset + tile * 32;
                int destIndex = destTileIndex + tile;

                if (srcTileOffset + 32 > artLen) {
                    // Source data doesn't have enough bytes
                    continue;
                }

                if (destIndex < level.getPatternCount()) {
                    Pattern pattern = level.getPattern(destIndex);
                    byte[] tileData = new byte[32];
                    System.arraycopy(cliffArtData, srcTileOffset, tileData, 0, 32);
                    pattern.fromSegaFormat(tileData);

                    if (graphicsMan.isGlInitialized()) {
                        graphicsMan.updatePatternTexture(pattern, destIndex);
                    }
                }
            }
        }
    }

    /**
     * Update cloud art tiles based on TempArray_LayerDef values.
     * Reference: s2.asm Dynamic_HTZ.doCloudArt lines 85573-85632
     *
     * Algorithm:
     * d2 = -cameraX >> 3 (base scroll)
     * For each of 16 TempArray entries:
     *   d0 = -entry + d2
     *   d0 &= $1F (mask to 0-31)
     *   d0 >>= 1, if carry (odd), add $200
     *   Read from cloudArt+d0, write to buffer at interleaved positions
     *
     * The output buffer is organized as 4 "super-rows" of 64 bytes each.
     * Each super-row contains data for 2 tile rows (at positions +0, +$40, +$80, +$C0).
     */
    private void updateCloudArt(Level level, int cameraX, SwScrlHtz htzHandler) {
        if (cloudArtData == null || cloudArtData.length == 0 || htzHandler == null) {
            return;
        }

        short[] tempArray = htzHandler.getTempArrayLayerDef();
        if (tempArray == null || tempArray.length < 16) {
            return;
        }

        // d2 = -cameraX >> 3 (base scroll value)
        int d2 = (short)((-cameraX) >> 3);

        // Clear cloud buffer
        java.util.Arrays.fill(cloudBuffer, (byte) 0);

        // a0 = ArtUnc_HTZClouds base (advances by $20 each iteration)
        // a2 = output buffer base (advances by 4 each iteration after writeback)
        int cloudSourceBase = 0;
        int bufferWritePos = 0;

        // Process 16 TempArray entries
        for (int i = 0; i < 16; i++) {
            int d0 = tempArray[i] & 0xFFFF;

            // neg.w d0; add.w d2,d0
            d0 = ((-d0) + d2) & 0xFFFF;

            // andi.w #$1F,d0
            d0 = d0 & 0x1F;

            // lsr.w #1,d0; bcc.s + / addi.w #$200,d0
            boolean wasOdd = (d0 & 1) != 0;
            d0 = d0 >> 1;
            if (wasOdd) {
                d0 += 0x200;
            }

            // Calculate source address: cloudArt + cloudSourceBase + d0
            int srcAddr = cloudSourceBase + d0;

            // lsr.w #1,d0 again to determine even/odd copy mode
            boolean useByteMode = (d0 & 1) != 0;

            // Copy 4 bytes to each of 4 positions spaced $40 apart
            // Positions: bufferWritePos, bufferWritePos+64, bufferWritePos+128, bufferWritePos+192
            for (int row = 0; row < 4; row++) {
                int destPos = bufferWritePos + row * 64;
                if (destPos + 4 <= cloudBuffer.length) {
                    for (int b = 0; b < 4; b++) {
                        int srcPos = srcAddr + row * 4 + b;
                        if (srcPos >= 0 && srcPos < cloudArtData.length) {
                            cloudBuffer[destPos + b] = cloudArtData[srcPos];
                        }
                    }
                }
            }

            // Advance source by one tile ($20 = 32 bytes)
            cloudSourceBase += 32;
            if (cloudSourceBase >= cloudArtData.length) {
                cloudSourceBase = 0;
            }

            // Advance write position by 4 bytes
            bufferWritePos += 4;
        }

        // Now copy the composed cloud data to level patterns at tile $0518
        // The buffer is already in VDP linear tile format after the interleaved writes:
        // - Tile 0 = buffer[0:32]
        // - Tile 1 = buffer[32:64]
        // - Tile 2 = buffer[64:96]
        // - etc.
        GraphicsManager graphicsMan = GameServices.graphics();

        for (int tile = 0; tile < 8; tile++) {
            int destIndex = Sonic2Constants.HTZ_CLOUDS_TILE_INDEX + tile;

            if (destIndex < level.getPatternCount()) {
                Pattern pattern = level.getPattern(destIndex);
                byte[] tileData = new byte[32];

                // Each tile is 32 consecutive bytes in the buffer
                int srcOffset = tile * 32;
                System.arraycopy(cloudBuffer, srcOffset, tileData, 0, 32);

                pattern.fromSegaFormat(tileData);

                if (graphicsMan.isGlInitialized()) {
                    graphicsMan.updatePatternTexture(pattern, destIndex);
                }
            }
        }
    }

    /**
     * Reset state when leaving HTZ.
     */
    public void reset() {
        lastMountainFrame = -1;
    }

    public boolean isInitialized() {
        return initialized;
    }
}
