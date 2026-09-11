package com.openggf.game.sonic3k.specialstage;

import com.openggf.game.PlayerCharacter;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;

import java.util.Arrays;
import java.util.logging.Logger;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;

/**
 * Renderer for the S3K Blue Ball special stage.
 * <p>
 * Implements Draw_SSSprites (sonic3k.asm:12266) — the pseudo-3D perspective
 * projection that renders spheres, rings, bumpers, and springs from the 32x32
 * grid onto the screen using pre-computed perspective maps.
 * <p>
 * Reference: docs/skdisasm/sonic3k.asm Draw_SSSprites (line 12266)
 */
public class Sonic3kSpecialStageRenderer {
    private static final Logger LOGGER = Logger.getLogger(Sonic3kSpecialStageRenderer.class.getName());

    public static final int SCREEN_WIDTH = 320;
    public static final int SCREEN_HEIGHT = 224;
    public static final int TILE_SIZE = 8;

    private final GraphicsManager graphicsManager;
    private final PatternDesc reusableDesc = new PatternDesc();

    // Pattern base indices for each art type in the atlas
    private int floorPatternBase;
    private int spherePatternBase;
    private int ringPatternBase;
    private int bgPatternBase;
    private int shadowPatternBase;
    private int getBlueSpherePatternBase;
    private int gbsArrowPatternBase;
    private int digitsPatternBase;
    private int iconsPatternBase;
    private int playerPatternBase;
    private int emeraldPatternBase;
    private int tailsPatternBase;
    private int tailsTailsPatternBase;

    /** Raw mapping data for Tails' SS sprite. */
    private byte[] tailsMappingData;
    private int tailsMappingFrameCount = 12;
    /** Raw mapping data for Tails' tails sprite. */
    private byte[] tailsTailsMappingData;
    private int tailsTailsMappingFrameCount = 15;

    private boolean artLoaded;

    /** Decompressed perspective map data (loaded from SStageKos_PerspectiveMaps). */
    private byte[] perspectiveMaps;


    /** Decompressed Enigma floor map (9 frames of 40x28 tiles). */
    private byte[] floorMapData;

    /** Decompressed Enigma BG map (64x32 tiles, starfield). */
    private byte[] bgMapData;

    private int[] cachedStarfieldGeometry;
    private int[][] cachedFloorGeometry;
    private byte[] cachedStarfieldContent;
    private boolean cachedStarfieldContentInitialized;
    private final byte[][] cachedFloorContent = new byte[9][];
    private final boolean[] cachedFloorContentInitialized = new boolean[9];
    private boolean staticGeometryDirty = true;
    private long staticGeometryStageGeneration;
    private long cachedStaticGeometryStageGeneration = -1;
    private Object renderContextGeneration;
    private Object cachedRenderContextGeneration;
    private int staticGeometryBuildCount;
    private boolean staticGeometryRebuiltThisRender;
    private int backgroundContentValidationCount;
    private int floorContentValidationCount;
    // Logical source bytes covered by bulk validation calls, not scalar comparison work.
    private int logicalContentValidationBytes;

    /** Raw mapping data for Sonic's SS sprite. */
    private byte[] sonicMappingData;
    /** Raw DPLC data for Sonic's SS sprite. */
    private byte[] sonicDplcData;
    /** Number of mapping frames in the header. */
    private int sonicMappingFrameCount;

    /** MapUnc_SSNum data (120 bytes: 3 rows × 10 digits × 4 bytes). */
    private byte[] hudNumberMap;
    /** MapUnc_SSNum000 template (48 bytes: 8 tiles × 3 rows × 2 bytes). */
    private byte[] hudTemplate;

    /**
     * Direction table for perspective grid traversal.
     * 4 quadrants, 6 fields each: col_start, row_start, col_step, col_mask, row_step, row_mask.
     * From word_98B0 (sonic3k.asm:12229).
     */
    private static final int[][] DIR_TABLE = {
        { 0x18, 6,  1, 0x1F, -1, 0x1F },  // North (0x00-0x3F)
        { 8,    6, -1, 0x1F, -1, 0x1F },  // West  (0x40-0x7F)
        { 8,  0x1A,-1, 0x1F,  1, 0x1F },  // South (0x80-0xBF)
        { 0x18,0x1A, 1, 0x1F,  1, 0x1F }, // East  (0xC0-0xFF)
    };

    private static final int[] HUD_TEMPLATE_BORDER_COLUMNS = {0, 7};
    /** Flat triples of tile width, tile height, tile offset by size index. */
    private static final int[] EMERALD_SIZE_MAP = {
            4, 4, 0x00, 4, 4, 0x10,
            3, 3, 0x20, 3, 3, 0x29, 3, 3, 0x32, 3, 3, 0x3B,
            2, 2, 0x44, 2, 2, 0x48, 2, 2, 0x4C, 2, 2, 0x50,
            1, 1, 0x54, 1, 1, 0x55, 1, 1, 0x56, 1, 1, 0x57
    };
    private static final int[] RING_FRONT_SIZE_MAP = {
            3, 2, 0x00, 3, 2, 0x0C, 3, 3, 0x18, 3, 2, 0x27,
            2, 2, 0x33, 2, 2, 0x39, 2, 2, 0x3F, 2, 1, 0x45, 1, 1, 0x48
    };
    private static final int[] RING_SIDE_SIZE_MAP = {
            2, 3, 0x06, 2, 3, 0x12, 2, 3, 0x21, 2, 3, 0x2D,
            1, 2, 0x37, 1, 2, 0x3D, 1, 2, 0x43, 1, 1, 0x47, 1, 1, 0x49
    };

    private int[] spriteCellTypes = new int[16];
    private int[] spriteScreenXs = new int[16];
    private int[] spriteScreenYs = new int[16];
    private int[] spriteSizeIndices = new int[16];
    private int spriteCount;

    public Sonic3kSpecialStageRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = graphicsManager;
    }

    public void loadArt(Sonic3kSpecialStageDataLoader dataLoader) {
        artLoaded = false;
    }

    public void setPerspectiveMaps(byte[] maps) {
        this.perspectiveMaps = maps;
    }

    public void setFloorMapData(byte[] data) {
        this.floorMapData = data;
    }

    public void setBgMapData(byte[] data) {
        this.bgMapData = data;
    }

    void onRenderContextGenerationChanged(Object generation) {
        if (renderContextGeneration != generation) {
            renderContextGeneration = generation;
            invalidateStaticGeometry();
        }
    }

    void resetStageGeometryCache() {
        staticGeometryStageGeneration++;
        invalidateStaticGeometry();
    }

    private void invalidateStaticGeometry() {
        staticGeometryDirty = true;
    }

    int staticGeometryBuildCountForTesting() {
        return staticGeometryBuildCount;
    }

    void resetContentValidationCountersForTesting() {
        backgroundContentValidationCount = 0;
        floorContentValidationCount = 0;
        logicalContentValidationBytes = 0;
    }

    int backgroundContentValidationCountForTesting() {
        return backgroundContentValidationCount;
    }

    int floorContentValidationCountForTesting() {
        return floorContentValidationCount;
    }

    int logicalContentValidationBytesForTesting() {
        return logicalContentValidationBytes;
    }

    public void setHudNumberMap(byte[] data) {
        this.hudNumberMap = data;
    }

    public void setHudTemplate(byte[] data) {
        this.hudTemplate = data;
    }

    public void setSonicMappingData(byte[] mappingData, byte[] dplcData) {
        this.sonicMappingData = mappingData;
        this.sonicDplcData = dplcData;
        // Count mapping frames from header (12 frames for Sonic SS)
        this.sonicMappingFrameCount = 12;
    }

    // ==================== Main Render ====================

    /** BG plane dimensions (VDP plane size 64x32). */
    private static final int BG_PLANE_W = 64;
    private static final int BG_PLANE_H = 32;
    /** Screen viewport in tiles. */
    private static final int SCREEN_TILES_X = 40;
    private static final int SCREEN_TILES_Y = 28;

    public void render(Sonic3kSpecialStageManager manager) {
        onRenderContextGenerationChanged(graphicsManager.getPatternAtlas());
        staticGeometryRebuiltThisRender = false;
        Sonic3kSpecialStagePlayer player = manager.getPlayer();
        Sonic3kSpecialStageGrid grid = manager.getGrid();

        // Render starfield background (Plane B) with scroll
        renderBackground(manager, player);

        // Render checkerboard floor (Plane A)
        renderFloor(manager, player);

        // Render grid sprites with perspective projection (VDP sprites)
        renderGridSprites3D(manager, grid, player);

        // Render HUD (sphere/ring counts and icons)
        renderHud(manager);

        // Render "Get Blue Spheres" banner
        renderBanner(manager);
    }

    /** Raw mapping data for the "Get Blue Spheres" banner sprite. */
    private byte[] bannerMappingData;

    public void setBannerMappingData(byte[] data) {
        this.bannerMappingData = data;
    }

    /**
     * Render HUD showing sphere count and ring count.
     * <p>
     * ROM layout:
     * - Sphere icon at screen (72, 8) — 3×3 tile sprite, tiles 0-8 of icons art
     * - Ring icon at screen (224, 8) — 3×3 tile sprite, tiles 9-17
     * - Sphere count 3 digits at screen (24, 8)
     * - Ring count 3 digits at screen (248, 8)
     */
    /**
     * Render the "Get Blue Spheres" banner.
     * <p>
     * Two halves slide in from the screen edges, display for 3 seconds, then slide out.
     * ROM: Obj_SStage_8E40 (sonic3k.asm:11310). Object center at VDP (0x120, 0xE8)
     * = screen (160, 104). Left half slides from -0xC0 to 0, right from +0xC0 to 0.
     * <p>
     * Map_GetBlueSpheres has 4 frames: 0=left part1, 1=right part1, 2=left part2, 3=right part2.
     */
    private void renderBanner(Sonic3kSpecialStageManager manager) {
        if (!artLoaded || getBlueSpherePatternBase <= 0 || bannerMappingData == null) return;

        Sonic3kSpecialStageBanner banner = manager.getBanner();
        if (banner == null || !banner.isVisible()) return;

        int slideOffset = banner.getSlideOffset();
        // Banner center Y: VDP 0xE8 = screen 0xE8-128 = 104
        int centerY = 104;

        graphicsManager.beginPatternBatch();

        // Left half: slides from center to left (center - slideOffset)
        int leftX = 160 - slideOffset;
        // Right half: slides from center to right (center + slideOffset)
        int rightX = 160 + slideOffset;

        // Frames 0+1 = "GET BLUE SPHERES", Frames 2+3 = "PERFECT"
        if (banner.isShowPerfect()) {
            renderBannerHalf(leftX, centerY, 2);
            renderBannerHalf(rightX, centerY, 3);
        } else {
            renderBannerHalf(leftX, centerY, 0);
            renderBannerHalf(rightX, centerY, 1);
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Render one mapping frame of the banner at the given center position.
     */
    private void renderBannerHalf(int centerX, int centerY, int frame) {
        if (bannerMappingData == null) return;
        if (frame * 2 + 1 >= bannerMappingData.length) return;

        int frameOffset = readWord(bannerMappingData, frame * 2);
        if (frameOffset + 1 >= bannerMappingData.length) return;

        int pieceCount = readWord(bannerMappingData, frameOffset);
        if (pieceCount <= 0 || pieceCount > 10) return;

        for (int p = 0; p < pieceCount; p++) {
            int po = frameOffset + 2 + p * 6;
            if (po + 5 >= bannerMappingData.length) break;

            int yOff = (byte) bannerMappingData[po];
            int sizeByte = bannerMappingData[po + 1] & 0xFF;
            int patternWord = readWord(bannerMappingData, po + 2);
            int xOff = readSignedWord(bannerMappingData, po + 4);

            int tilesW = ((sizeByte >> 2) & 3) + 1;
            int tilesH = (sizeByte & 3) + 1;
            int tileIdx = patternWord & 0x07FF;
            boolean hFlip = (patternWord & 0x0800) != 0;

            // Tile index 0x199 is from the GBS Arrow art; others from Get Blue Spheres art
            // Both share the same VRAM base (ART_TILE_GET_BLUE_SPHERES = 0x055F)
            int patternId = getBlueSpherePatternBase + tileIdx;

            for (int col = 0; col < tilesW; col++) {
                for (int row = 0; row < tilesH; row++) {
                    int tileOff;
                    if (hFlip) {
                        tileOff = (tilesW - 1 - col) * tilesH + row;
                    } else {
                        tileOff = col * tilesH + row;
                    }

                    int drawX = centerX + xOff + col * TILE_SIZE;
                    int drawY = centerY + yOff + row * TILE_SIZE;

                    reusableDesc.set(0);
                    reusableDesc.setPriority(true);
                    reusableDesc.setPaletteIndex(1); // Banner uses palette 1
                    reusableDesc.setHFlip(hFlip);
                    graphicsManager.renderPatternWithId(patternId + tileOff,
                            reusableDesc, drawX, drawY);
                }
            }
        }
    }

    private void renderHud(Sonic3kSpecialStageManager manager) {
        if (!artLoaded) return;

        graphicsManager.beginPatternBatch();

        // Render number boxes first (Plane A — behind sprites)
        if (digitsPatternBase > 0) {
            renderThreeDigitNumber(manager.getSpheresLeft(), 16, 8);
            renderThreeDigitNumber(manager.getRingsCollected(), 240, 8);
        }

        // Render icons on top (VDP sprites — in front of plane tiles)
        if (iconsPatternBase > 0) {
            renderHudIcon(72, 8, 0);   // Sphere icon
            renderHudIcon(224, 8, 9);  // Ring icon
        }

        graphicsManager.flushPatternBatch();
    }

    /** Render a 3×3 tile HUD icon. */
    private void renderHudIcon(int x, int y, int tileOff) {
        for (int col = 0; col < 3; col++) {
            for (int row = 0; row < 3; row++) {
                int tileIdx = col * 3 + row;
                reusableDesc.set(0);
                reusableDesc.setPriority(true);
                reusableDesc.setPaletteIndex(2);
                graphicsManager.renderPatternWithId(iconsPatternBase + tileOff + tileIdx,
                        reusableDesc, x + col * TILE_SIZE, y + row * TILE_SIZE);
            }
        }
    }

    /**
     * Render a 3-digit number with surrounding box template.
     * The template (MapUnc_SSNum000) is 8×3 tiles with end bars at columns 0 and 7.
     * The 3 digits fill columns 1-6 (2 tiles per digit).
     */
    private void renderThreeDigitNumber(int value, int x, int y) {
        value = Math.max(0, Math.min(value, 999));

        // Render only the end bar columns (0 and 7) from the template.
        // Columns 1-6 contain "000" which would show through under the actual digits.
        if (hudTemplate != null) {
            for (int row = 0; row < 3; row++) {
                for (int col : HUD_TEMPLATE_BORDER_COLUMNS) {
                    int off = (row * 8 + col) * 2;
                    if (off + 1 >= hudTemplate.length) continue;
                    int word = ((hudTemplate[off] & 0xFF) << 8) | (hudTemplate[off + 1] & 0xFF);
                    int tileIdx = word & 0x7FF;
                    boolean hFlip = (word & 0x0800) != 0;
                    boolean vFlip = (word & 0x1000) != 0;
                    int palette = (word >> 13) & 3;
                    int patternId = digitsPatternBase + (tileIdx - Sonic3kSpecialStageConstants.ART_TILE_DIGITS);
                    reusableDesc.set(0);
                    reusableDesc.setPriority(true);
                    reusableDesc.setPaletteIndex(palette);
                    reusableDesc.setHFlip(hFlip);
                    reusableDesc.setVFlip(vFlip);
                    graphicsManager.renderPatternWithId(patternId, reusableDesc,
                            x + col * TILE_SIZE, y + row * TILE_SIZE);
                }
            }
        }

        // Render digits over the template (columns 1-2, 3-4, 5-6)
        renderDigit(value / 100, x + 1 * TILE_SIZE, y);
        renderDigit((value / 10) % 10, x + 3 * TILE_SIZE, y);
        renderDigit(value % 10, x + 5 * TILE_SIZE, y);
    }

    /**
     * Render a single digit (0-9) as 2×3 tiles using MapUnc_SSNum.
     * <p>
     * The map is organized as 3 row blocks of 40 bytes each (10 digits × 4 bytes).
     * Row 0 at offset 0x00, row 1 at 0x28, row 2 at 0x50.
     * Each digit entry is 4 bytes = 2 VDP tile words (left + right tile).
     * Each word: PCCV_HTTT_TTTT_TTTT (priority, palette, vflip, hflip, tile index).
     */
    private void renderDigit(int digit, int x, int y) {
        if (hudNumberMap == null || digit < 0 || digit > 9) return;

        for (int row = 0; row < 3; row++) {
            int rowOffset = row * 0x28; // 40 bytes per row block
            int off = rowOffset + digit * 4;
            if (off + 3 >= hudNumberMap.length) break;

            for (int col = 0; col < 2; col++) {
                int word = ((hudNumberMap[off + col * 2] & 0xFF) << 8)
                         | (hudNumberMap[off + col * 2 + 1] & 0xFF);

                int tileIdx = word & 0x7FF;
                boolean hFlip = (word & 0x0800) != 0;
                boolean vFlip = (word & 0x1000) != 0;
                int palette = (word >> 13) & 3;

                // Convert from VDP tile index to our pattern atlas index
                int patternId = digitsPatternBase + (tileIdx - Sonic3kSpecialStageConstants.ART_TILE_DIGITS);

                reusableDesc.set(0);
                reusableDesc.setPriority(true);
                reusableDesc.setPaletteIndex(palette);
                reusableDesc.setHFlip(hFlip);
                reusableDesc.setVFlip(vFlip);
                graphicsManager.renderPatternWithId(patternId, reusableDesc,
                        x + col * TILE_SIZE, y + row * TILE_SIZE);
            }
        }
    }

    /**
     * Render the starfield background (Plane B).
     * The BG is a 64x32 tile plane with H-scroll and V-scroll.
     * ROM: sub_9D5E computes V_scroll_value_BG and H_scroll_buffer.
     * H-scroll = angle * 4, V-scroll = accumulated position delta / 4.
     */
    private void renderBackground(Sonic3kSpecialStageManager manager,
                                  Sonic3kSpecialStagePlayer player) {
        if (!artLoaded || bgMapData == null || bgPatternBase <= 0) return;
        ensureGlobalStaticGeometry();
        if (!staticGeometryRebuiltThisRender && !starfieldContentMatches()) {
            cachedStarfieldGeometry = buildStarfieldGeometry();
            cachedStarfieldContent = retainCopy(bgMapData, cachedStarfieldContent);
            cachedStarfieldContentInitialized = true;
            staticGeometryBuildCount++;
        }

        // Get scroll values from background handler
        Sonic3kSpecialStageBackground bg = manager.getBackground();
        int hScroll = bg != null ? bg.getHScroll() : 0;
        int vScroll = bg != null ? bg.getVScroll() : 0;

        // Convert scroll to tile offsets (pixels / 8) and pixel sub-offsets
        // Use Math.floorMod/floorDiv to handle negative scroll values correctly
        // VDP H-scroll moves the plane left (positive = content shifts right on screen).
        // Negate so increasing angle moves the background in the correct direction.
        int hScrollNeg = -hScroll;
        int hScrollTile = Math.floorMod(Math.floorDiv(hScrollNeg, TILE_SIZE), BG_PLANE_W);
        int hScrollPx = Math.floorMod(hScrollNeg, TILE_SIZE);
        int vScrollTile = Math.floorMod(Math.floorDiv(vScroll, TILE_SIZE), BG_PLANE_H);
        int vScrollPx = Math.floorMod(vScroll, TILE_SIZE);

        graphicsManager.beginPatternBatch();

        // Render visible viewport (40x28 tiles + 1 extra for sub-tile scroll)
        for (int sy = 0; sy <= SCREEN_TILES_Y; sy++) {
            for (int sx = 0; sx <= SCREEN_TILES_X; sx++) {
                // Map screen tile to plane tile with scroll wrapping
                int planeX = ((sx + hScrollTile) % BG_PLANE_W + BG_PLANE_W) % BG_PLANE_W;
                int planeY = ((sy + vScrollTile) % BG_PLANE_H + BG_PLANE_H) % BG_PLANE_H;

                int geometryIndex = planeY * BG_PLANE_W + planeX;
                if (cachedStarfieldGeometry == null || geometryIndex >= cachedStarfieldGeometry.length) continue;
                int geometry = cachedStarfieldGeometry[geometryIndex];
                int word = unpackDescriptorWord(geometry);

                // The Enigma map was decompressed with startTile including the art base,
                // so patternIdx already includes ArtTile_SStage_BG offset.
                // We need to subtract the base and add our bgPatternBase.
                int patternId = bgPatternBase + unpackPatternIndex(geometry);

                reusableDesc.set(word);
                reusableDesc.setPriority(false); // BG behind everything
                graphicsManager.renderPatternWithId(patternId, reusableDesc,
                        sx * TILE_SIZE - hScrollPx, sy * TILE_SIZE - vScrollPx);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    private void ensureGlobalStaticGeometry() {
        boolean rebuildAll = staticGeometryDirty
                || cachedStaticGeometryStageGeneration != staticGeometryStageGeneration
                || cachedRenderContextGeneration != renderContextGeneration;
        if (!rebuildAll) {
            return;
        }

        cachedStarfieldGeometry = buildStarfieldGeometry();
        cachedStarfieldContent = retainCopy(bgMapData, cachedStarfieldContent);
        cachedStarfieldContentInitialized = true;
        staticGeometryBuildCount++;

        cachedFloorGeometry = new int[9][];
        for (int frame = 0; frame < cachedFloorGeometry.length; frame++) {
            cachedFloorGeometry[frame] = buildFloorGeometry(frame);
            cachedFloorContent[frame] = retainFloorFrameCopy(frame, cachedFloorContent[frame]);
            cachedFloorContentInitialized[frame] = true;
            staticGeometryBuildCount++;
        }
        cachedStaticGeometryStageGeneration = staticGeometryStageGeneration;
        cachedRenderContextGeneration = renderContextGeneration;
        staticGeometryDirty = false;
        staticGeometryRebuiltThisRender = true;
    }

    private boolean starfieldContentMatches() {
        backgroundContentValidationCount++;
        if (!cachedStarfieldContentInitialized) {
            return false;
        }
        return byteArraysEqualOverLogicalSpan(cachedStarfieldContent, bgMapData);
    }

    private boolean floorFrameContentMatches(int frame) {
        floorContentValidationCount++;
        if (!cachedFloorContentInitialized[frame]) {
            return false;
        }
        int frameSize = 40 * 28 * 2;
        int frameOffset = frame * frameSize;
        boolean frameAvailable = floorMapData != null && frameOffset + frameSize <= floorMapData.length;
        byte[] retained = cachedFloorContent[frame];
        if (!frameAvailable) {
            return retained == null;
        }
        if (retained == null || retained.length != frameSize) {
            return false;
        }
        logicalContentValidationBytes += frameSize;
        return Arrays.equals(floorMapData, frameOffset, frameOffset + frameSize,
                retained, 0, frameSize);
    }

    private boolean byteArraysEqualOverLogicalSpan(byte[] expected, byte[] actual) {
        if (expected == null || actual == null) {
            return expected == actual;
        }
        if (expected.length != actual.length) {
            return false;
        }
        logicalContentValidationBytes += expected.length;
        return Arrays.equals(expected, actual);
    }

    private static byte[] retainCopy(byte[] source, byte[] retained) {
        if (source == null) {
            return null;
        }
        if (retained == null || retained.length != source.length) {
            retained = new byte[source.length];
        }
        System.arraycopy(source, 0, retained, 0, source.length);
        return retained;
    }

    private byte[] retainFloorFrameCopy(int frame, byte[] retained) {
        int frameSize = 40 * 28 * 2;
        int frameOffset = frame * frameSize;
        if (floorMapData == null || frameOffset + frameSize > floorMapData.length) {
            return null;
        }
        if (retained == null || retained.length != frameSize) {
            retained = new byte[frameSize];
        }
        System.arraycopy(floorMapData, frameOffset, retained, 0, frameSize);
        return retained;
    }

    private int[] buildStarfieldGeometry() {
        int tileCount = Math.min(BG_PLANE_W * BG_PLANE_H, bgMapData != null ? bgMapData.length / 2 : 0);
        int[] geometry = new int[tileCount];
        for (int i = 0; i < tileCount; i++) {
            int word = readWord(bgMapData, i * 2);
            int tileId = (word & 0x7FF) - Sonic3kSpecialStageConstants.ART_TILE_BG;
            // Preserve the exact descriptor that the old per-frame builder emitted:
            // palette/flips only, forced behind all other layers, and no baked tile id.
            geometry[i] = packGeometry(Math.max(0, tileId), word & 0x7800);
        }
        return geometry;
    }

    private int[] buildFloorGeometry(int frame) {
        int tileCount = 40 * 28;
        int frameOffset = frame * tileCount * 2;
        if (floorMapData == null || frameOffset + tileCount * 2 > floorMapData.length) {
            return new int[0];
        }
        int[] geometry = new int[tileCount];
        for (int i = 0; i < tileCount; i++) {
            int word = readWord(floorMapData, frameOffset + i * 2);
            // Pattern lookup remains explicit; the descriptor contains only the
            // priority/palette/flip metadata used by the live palette renderer.
            geometry[i] = packGeometry(word & 0x7FF, word & 0xF800);
        }
        return geometry;
    }

    private static int packGeometry(int patternIndex, int descriptorWord) {
        return (descriptorWord << 16) | (patternIndex & 0xFFFF);
    }

    private static int unpackPatternIndex(int geometry) {
        return geometry & 0xFFFF;
    }

    private static int unpackDescriptorWord(int geometry) {
        return geometry >>> 16;
    }

    /**
     * Floor frame lookup matching SS_Pal_Map_Ptrs (sonic3k.asm:11107).
     * <p>
     * The Enigma map data contains 9 frames at offsets in RAM:
     * <pre>
     *   Frame 0: RAM+$5500 (offset 0)
     *   Frame 1: RAM+$5DC0 (offset $8C0 = 2240)
     *   Frame 2: RAM+$6680 (offset $1180)
     *   ...
     *   Frame 8: RAM+$9B00 (offset $4600)
     * </pre>
     * Moving anim frames 0-15 alternate between frames 0 and 1.
     * Turning anim frames 16-22 use frames 8 down to 2 (reverse order).
     */
    private static final int[] FLOOR_FRAME_MAP = {
        0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, // anim 0-15: alternate 0/1
        8, 7, 6, 5, 4, 3, 2                                 // anim 16-22: turning
    };

    /**
     * Render the checkerboard floor using the Enigma-mapped layout tiles.
     */
    private void renderFloor(Sonic3kSpecialStageManager manager, Sonic3kSpecialStagePlayer player) {
        if (!artLoaded || floorMapData == null || floorPatternBase <= 0) return;

        // Compute anim frame (same as sprite renderer)
        int angle = player.getAngle();
        boolean axisBit6 = (angle & 0x40) != 0;
        int d0;
        if (axisBit6) {
            d0 = (player.getXPos() + 0x100 + (player.getYPos() & 0x100)) & 0xFFFF;
        } else {
            d0 = (player.getYPos() + (player.getXPos() & 0x100)) & 0xFFFF;
        }
        if ((angle & 0x80) == 0) d0 = (-d0 + 0x1F) & 0xFFFF;
        int animFrame = (d0 & 0x1E0) >> 5;
        int turnBits = angle & 0x38;
        if (turnBits != 0) animFrame = 0x0F + (turnBits >> 3);

        // Floor frame selection.
        // The ROM uses BOTH palette rotation (Rotate_SSPal) AND map frame swapping
        // together: palette rotation shifts colors on even anim frames, and the map
        // swap between frames 0/1 shifts the tile pattern on odd anim frames. Combined,
        // this gives 16 visual updates per cell crossing instead of 8.
        // Turning uses dedicated pre-rendered perspective frames 2-8.
        int floorFrame;
        if (animFrame >= 16 && animFrame < FLOOR_FRAME_MAP.length) {
            floorFrame = FLOOR_FRAME_MAP[animFrame]; // Turning frames
        } else {
            floorFrame = FLOOR_FRAME_MAP[animFrame & 0xF]; // 0 or 1 alternating
        }
        int maxFrames = floorMapData.length / (40 * 28 * 2);
        if (maxFrames <= 0) return;
        if (floorFrame >= maxFrames) floorFrame = 0;

        ensureGlobalStaticGeometry();
        if (!staticGeometryRebuiltThisRender && !floorFrameContentMatches(floorFrame)) {
            cachedFloorGeometry[floorFrame] = buildFloorGeometry(floorFrame);
            cachedFloorContent[floorFrame] = retainFloorFrameCopy(floorFrame, cachedFloorContent[floorFrame]);
            cachedFloorContentInitialized[floorFrame] = true;
            staticGeometryBuildCount++;
        }

        int[] floorGeometry = cachedFloorGeometry[floorFrame];
        if (floorGeometry.length != 40 * 28) return;

        graphicsManager.beginPatternBatch();

        for (int ty = 0; ty < 28; ty++) {
            for (int tx = 0; tx < 40; tx++) {
                int geometry = floorGeometry[ty * 40 + tx];
                int patternId = floorPatternBase + unpackPatternIndex(geometry);

                reusableDesc.set(unpackDescriptorWord(geometry));
                graphicsManager.renderPatternWithId(patternId, reusableDesc,
                        tx * TILE_SIZE, ty * TILE_SIZE);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    // ==================== 3D Perspective Rendering ====================

    /**
     * Render grid sprites using ROM-accurate pseudo-3D perspective.
     * <p>
     * Implements Draw_SSSprites (sonic3k.asm:12266):
     * <ol>
     *   <li>Select direction table based on angle quadrant</li>
     *   <li>Compute animation frame from position/angle</li>
     *   <li>Look up perspective map pointer for this frame</li>
     *   <li>Traverse 16 rows x 15 columns of the grid</li>
     *   <li>For each non-empty cell, read perspective entry (screen pos + size)</li>
     *   <li>Render the sprite at the projected position with scaled size</li>
     * </ol>
     */
    private void renderGridSprites3D(Sonic3kSpecialStageManager manager,
                                     Sonic3kSpecialStageGrid grid,
                                     Sonic3kSpecialStagePlayer player) {
        if (!artLoaded) return;

        int angle = player.getAngle();
        int xPos = player.getXPos();
        int yPos = player.getYPos();
        boolean axisBit6 = (angle & 0x40) != 0;

        // Select direction table
        int quadrant = (angle & 0xC0) >> 6;
        int[] dir = DIR_TABLE[quadrant];

        // Compute d1 and animation frame exactly as the ROM does.
        // ROM lines 12271-12306:
        //   move.b (X_pos).w,d1 → reads HIGH byte of word (grid cell X, 0-31)
        //   move.w (X_pos).w,d0 → reads full word
        //   d0 = X_pos + $100 + (Y_pos & $100)
        //   if bit6=0: d1 = Y_pos high byte (grid cell Y), d0 = Y_pos + (X_pos & $100)
        //   if angle positive: d0 = -d0 + $1F, if (d0 & $E0)!=0 then d1++
        //
        // IMPORTANT: 68000 move.b at a word address reads the HIGH byte (big-endian)
        int d1 = (xPos >> 8) & 0xFF;  // Grid cell X
        int d0 = (xPos + 0x100 + (yPos & 0x100)) & 0xFFFF;

        if (!axisBit6) {
            d1 = (yPos >> 8) & 0xFF;  // Grid cell Y
            d0 = (yPos + (xPos & 0x100)) & 0xFFFF;
        }

        if ((angle & 0x80) == 0) {
            d0 = (-d0 + 0x1F) & 0xFFFF;
            if ((d0 & 0xE0) != 0) {
                d1 = (d1 + 1) & 0xFF;
            }
        }

        int animFrame = (d0 & 0x1E0) >> 5;

        // Turning frames override
        int turnBits = angle & 0x38;
        if (turnBits != 0) {
            animFrame = 0x0F + (turnBits >> 3);
        }

        // Get perspective map data for this animation frame
        int perspFrameOffset = getPerspectiveFrameOffset(animFrame);

        // d5 = row variable: dir[1] (row_start) + d1, masked by dir[5]
        int d5 = (dir[1] + d1) & dir[5];

        // Collect all visible sprites with their screen positions, then sort
        // by screen Y ascending so that far sprites (top of screen) draw first
        // and close sprites (bottom of screen) draw last (on top).
        // This matches VDP behaviour where lower sprite table entries (drawn first
        // by Draw_SSSprites for close rows) appear in front of later entries.
        spriteCount = 0;
        int ringAnim = manager.getRingAnimFrame();

        int perspIdx = 0;

        for (int rowIdx = 0; rowIdx < 16; rowIdx++) {
            int d4;
            if (axisBit6) {
                d4 = (dir[0] + ((yPos >> 8) & 0xFF)) & dir[3];
            } else {
                d4 = (dir[0] + ((xPos >> 8) & 0xFF)) & dir[3];
            }

            for (int colIdx = 0; colIdx < 15; colIdx++) {
                int gridIndex;
                if (axisBit6) {
                    gridIndex = ((d4 & 0x1F) << 5) | (d5 & 0x1F);
                } else {
                    gridIndex = ((d5 & 0x1F) << 5) | (d4 & 0x1F);
                }

                int cellType = grid.getCellByIndex(gridIndex & 0x3FF);

                if (cellType != CELL_EMPTY && perspFrameOffset >= 0) {
                    int entryOff = perspFrameOffset + perspIdx * 6;
                    if (entryOff + 5 < perspectiveMaps.length) {
                        int perspWord = readWord(perspectiveMaps, entryOff);
                        int scrX = readSignedWord(perspectiveMaps, entryOff + 2) - 128;
                        int scrY = readSignedWord(perspectiveMaps, entryOff + 4) - 128;

                        int sizeField = (perspWord & 0x7C) >> 2;
                        int sizeIndex = sizeField - 6;

                        if (sizeIndex >= 0 && sizeIndex < 16) {
                            appendSprite(cellType, scrX, scrY, sizeIndex);
                        }
                    }
                }

                perspIdx++;
                d4 = (d4 + dir[2]) & dir[3];
            }

            d5 = (d5 + dir[4]) & dir[5];
        }

        // Sort by screen Y ascending: far/top sprites first, close/bottom sprites last (on top)
        sortSpritesByScreenYStable();

        // Apply fly-away effect during clear sequence
        // ROM: Draw_SSSprite_FlyAway modifies sprite positions:
        //   Y -= clearTimer (sprites fly upward)
        //   X = ((X - 160) * (clearTimer + 256)) / 256 + 160 (spread outward)
        int clearTimer = manager.getClearTimer();

        graphicsManager.beginPatternBatch();
        for (int i = 0; i < spriteCount; i++) {
            int sx = spriteScreenXs[i];
            int sy = spriteScreenYs[i];
            if (clearTimer > 0) {
                sy -= clearTimer;                              // Fly upward
                sx = (((sx - 160) * (clearTimer + 256)) >> 8) + 160; // Spread outward
            }
            renderPerspectiveSprite(spriteCellTypes[i], sx, sy, spriteSizeIndices[i], ringAnim);
        }

        // Render player with jump height offset.
        // Full 3D projection trace with actual scalar values gives:
        //   Ground ($36=-0x800): screenY=162, Peak ($36=-0x858): screenY=130
        //   Delta = 32 pixels for jumpHeight peak of -0x580000.
        // Scale factor: 32 / 0x580000 ≈ 1 / 0x2C000. Approximated as >> 17 * 3/4.
        // Simplified: (jumpHeight >> 16) * 32 / 0x58 = (jumpHeight >> 16) / 2.75
        long swapped = player.getJumpHeight() >> 16;
        int jumpYOffset = (int)(swapped * 32 / 0x58);

        // Render main player character.
        // ROM: Obj_SStage_8FAA uses palette 1 when Player_mode == 2 (Tails alone).
        PlayerCharacter playerCharacter = manager.getPlayerCharacter();
        renderPlayerSprite(player, playerCharacter, PLAYER_SCREEN_CENTER_X, 160 + jumpYOffset);

        // Render tails appendage on main player when Tails is the main character
        if (playerCharacter == PlayerCharacter.TAILS_ALONE
                && tailsTailsPatternBase > 0 && tailsTailsMappingData != null) {
            int ttFrame = manager.getTailsTailsMappingFrame();
            if (ttFrame >= 0 && ttFrame < tailsTailsMappingFrameCount
                    && ttFrame * 2 + 1 < tailsTailsMappingData.length) {
                renderCharacterSprite(tailsTailsMappingData, tailsTailsMappingFrameCount,
                        tailsTailsPatternBase, 1, ttFrame,
                        PLAYER_SCREEN_CENTER_X, 160 + jumpYOffset);
            }
        }

        // Render Tails sidekick on top (closer to camera, lower on screen)
        // ROM: Tails at $38=-0x20 (closer to camera in Z), rendered lower on screen
        if (manager.isTailsEnabled() && tailsPatternBase > 0 && tailsMappingData != null) {
            renderTailsSprite(manager, PLAYER_SCREEN_CENTER_X, 172);
        }

        graphicsManager.flushPatternBatch();
    }

    private void appendSprite(int cellType, int screenX, int screenY, int sizeIndex) {
        ensureSpriteCapacity(spriteCount + 1);
        spriteCellTypes[spriteCount] = cellType;
        spriteScreenXs[spriteCount] = screenX;
        spriteScreenYs[spriteCount] = screenY;
        spriteSizeIndices[spriteCount] = sizeIndex;
        spriteCount++;
    }

    private void ensureSpriteCapacity(int requiredCapacity) {
        if (requiredCapacity <= spriteCellTypes.length) {
            return;
        }
        int newCapacity = Math.max(requiredCapacity, spriteCellTypes.length << 1);
        spriteCellTypes = Arrays.copyOf(spriteCellTypes, newCapacity);
        spriteScreenXs = Arrays.copyOf(spriteScreenXs, newCapacity);
        spriteScreenYs = Arrays.copyOf(spriteScreenYs, newCapacity);
        spriteSizeIndices = Arrays.copyOf(spriteSizeIndices, newCapacity);
    }

    /** Stable insertion sort: equal Y keys never move ahead of their traversal predecessor. */
    private void sortSpritesByScreenYStable() {
        for (int i = 1; i < spriteCount; i++) {
            int cellType = spriteCellTypes[i];
            int screenX = spriteScreenXs[i];
            int screenY = spriteScreenYs[i];
            int sizeIndex = spriteSizeIndices[i];
            int insertAt = i;
            while (insertAt > 0 && spriteScreenYs[insertAt - 1] > screenY) {
                spriteCellTypes[insertAt] = spriteCellTypes[insertAt - 1];
                spriteScreenXs[insertAt] = spriteScreenXs[insertAt - 1];
                spriteScreenYs[insertAt] = spriteScreenYs[insertAt - 1];
                spriteSizeIndices[insertAt] = spriteSizeIndices[insertAt - 1];
                insertAt--;
            }
            spriteCellTypes[insertAt] = cellType;
            spriteScreenXs[insertAt] = screenX;
            spriteScreenYs[insertAt] = screenY;
            spriteSizeIndices[insertAt] = sizeIndex;
        }
    }

    /**
     * RAM_start address on Mega Drive. Perspective map pointers are absolute
     * RAM addresses; subtracting this gives the offset within our buffer.
     */
    private static final int RAM_START = 0x00FF0000;

    /**
     * Get the byte offset into perspectiveMaps for a given animation frame.
     * <p>
     * The first 96 bytes (24 longwords) are a pointer table. Each entry is
     * an absolute Mega Drive RAM address (e.g. 0x00FF005C). The data was
     * decompressed to RAM_start (0x00FF0000), so the buffer-relative offset
     * is {@code pointer - RAM_START}.
     * <p>
     * Each frame is 1440 bytes (16 rows x 15 columns x 6 bytes per entry).
     */
    private int getPerspectiveFrameOffset(int animFrame) {
        if (perspectiveMaps == null || animFrame < 0 || animFrame >= 24) {
            return -1;
        }
        int tableOffset = animFrame * 4;
        if (tableOffset + 3 >= perspectiveMaps.length) {
            return -1;
        }
        int ptr = readLong(perspectiveMaps, tableOffset);
        int offset = ptr - RAM_START;
        if (offset < 0 || offset >= perspectiveMaps.length) {
            return -1;
        }
        return offset;
    }

    // ==================== Sprite Rendering ====================

    /**
     * Render a sprite at a perspective-projected position with size scaling.
     * Maps sizeIndex to the correct sphere/ring mapping frame.
     */
    private void renderPerspectiveSprite(int cellType, int screenX, int screenY, int sizeIndex,
                                         int ringAnimPhase) {
        int patternBase;
        int paletteIndex;

        switch (cellType) {
            case CELL_BLUE:  patternBase = spherePatternBase; paletteIndex = 2; break;
            case CELL_RED:
            case CELL_TOUCHED:  // Touched spheres display as red
                             patternBase = spherePatternBase; paletteIndex = 0; break;
            case CELL_BUMPER: patternBase = spherePatternBase; paletteIndex = 1; break;
            case CELL_RING:
                patternBase = ringPatternBase; paletteIndex = 2; break;
            case CELL_RING_ANIM_1: case CELL_RING_ANIM_2:
            case CELL_RING_ANIM_3: case CELL_RING_ANIM_4:
                patternBase = ringPatternBase; paletteIndex = 2; break;
            case CELL_SPRING: patternBase = spherePatternBase; paletteIndex = 3; break;
            case CELL_CHAOS_EMERALD: case CELL_SUPER_EMERALD:
                patternBase = emeraldPatternBase > 0 ? emeraldPatternBase : ringPatternBase;
                paletteIndex = 3; break;
            default: return;
        }

        // Size index → sprite dimensions and tile offset in sphere art
        int tilesW, tilesH, tileOffset;
        if (sizeIndex < 4) {
            tilesW = 4; tilesH = 4;
            tileOffset = sizeIndex * 0x10;
        } else if (sizeIndex < 8) {
            tilesW = 3; tilesH = 3;
            tileOffset = 0x40 + (sizeIndex - 4) * 9;
        } else if (sizeIndex < 12) {
            tilesW = 2; tilesH = 2;
            tileOffset = 0x64 + (sizeIndex - 8) * 4;
        } else {
            tilesW = 1; tilesH = 1;
            tileOffset = 0x74 + (sizeIndex - 12);
        }

        // Emerald art (Map_SStageChaosEmerald) has different frame layout from spheres.
        // Frames 0-1: 4×4 (0x00, 0x10), Frames 2-5: 3×3 (0x20, 0x29, 0x32, 0x3B),
        // Frames 6-9: 2×2 (0x44, 0x48, 0x4C, 0x50), Frames 10-13: 1×1 (0x54-0x57)
        boolean isEmerald = (cellType == CELL_CHAOS_EMERALD || cellType == CELL_SUPER_EMERALD);
        if (isEmerald) {
            int offset = Math.min(sizeIndex, EMERALD_SIZE_MAP.length / 3 - 1) * 3;
            tilesW = EMERALD_SIZE_MAP[offset];
            tilesH = EMERALD_SIZE_MAP[offset + 1];
            tileOffset = EMERALD_SIZE_MAP[offset + 2];
        }

        // Ring art has 3 rotation phases (from Map_SStageRing).
        // Phase 0: front view (3xN wide), Phase 1: side view (2xN narrow),
        // Phase 2: same tiles as phase 1 but with H-flip.
        boolean isRing = (cellType == CELL_RING || (cellType >= CELL_RING_ANIM_1 && cellType <= CELL_RING_ANIM_4));
        boolean ringHFlip = false;
        boolean sparkleVFlip = false;
        if (isRing) {
            int idx = Math.min(sizeIndex, 8);
            if (ringAnimPhase == 0) {
                // Phase 0: front-facing (wider)
                int offset = idx * 3;
                tilesW = RING_FRONT_SIZE_MAP[offset];
                tilesH = RING_FRONT_SIZE_MAP[offset + 1];
                tileOffset = RING_FRONT_SIZE_MAP[offset + 2];
            } else {
                // Phase 1 & 2: side view (narrower), phase 2 adds H-flip
                int offset = idx * 3;
                tilesW = RING_SIDE_SIZE_MAP[offset];
                tilesH = RING_SIDE_SIZE_MAP[offset + 1];
                tileOffset = RING_SIDE_SIZE_MAP[offset + 2];
                ringHFlip = (ringAnimPhase == 2);
            }
        }

        // Ring collection sparkle animation (cell types 6-9).
        // ROM: these use fixed 3×3 tiles at offset 0x50 with flip variations,
        // regardless of distance. Frames 50-53 in Map_SStageRing.
        boolean isSparkle = (cellType >= CELL_RING_ANIM_1 && cellType <= CELL_RING_ANIM_4);
        if (isSparkle) {
            tilesW = 3; tilesH = 3; tileOffset = 0x50;
            ringHFlip = false; sparkleVFlip = false;
            switch (cellType) {
                case CELL_RING_ANIM_1: break;                                      // No flip
                case CELL_RING_ANIM_2: ringHFlip = true; sparkleVFlip = true; break; // H+V flip
                case CELL_RING_ANIM_3: ringHFlip = true; break;                     // H flip
                case CELL_RING_ANIM_4: sparkleVFlip = true; break;                  // V flip
            }
        }

        // Apply the mapping piece offsets to center the sprite on the grid position.
        // From Map_SStageSphere: each size has specific y/x offsets:
        //   4x4 (32px): y=-16 (0xF0), x=-16 (0xFFF0)
        //   3x3 (24px): y=-12 (0xF4), x=-12 (0xFFF4)
        //   2x2 (16px): y=-8  (0xF8), x=-8  (0xFFF8)
        //   1x1 (8px):  y=-4  (0xFC), x=-4  (0xFFFC)
        int centerOffX = -(tilesW * TILE_SIZE) / 2;
        int centerOffY = -(tilesH * TILE_SIZE) / 2;

        // Render using column-major VDP ordering
        for (int col = 0; col < tilesW; col++) {
            for (int row = 0; row < tilesH; row++) {
                int tileIdx;
                int drawCol = col;
                // VDP column-major: tileIdx = col * tilesH + row
                // H-flip reverses which column's tiles are used at each screen column
                // V-flip reverses which row's tile is used at each screen row
                int srcCol = ringHFlip ? (tilesW - 1 - col) : col;
                int srcRow = sparkleVFlip ? (tilesH - 1 - row) : row;
                tileIdx = srcCol * tilesH + srcRow;
                int patternId = patternBase + tileOffset + tileIdx;
                reusableDesc.set(0);
                reusableDesc.setPriority(true);
                reusableDesc.setPaletteIndex(paletteIndex);
                reusableDesc.setHFlip(ringHFlip);
                reusableDesc.setVFlip(sparkleVFlip);
                graphicsManager.renderPatternWithId(patternId, reusableDesc,
                        screenX + centerOffX + drawCol * TILE_SIZE,
                        screenY + centerOffY + row * TILE_SIZE);
            }
        }
    }

    /**
     * Render the player character sprite using ROM mapping + DPLC data.
     * <p>
     * The DPLC (Dynamic Pattern Load Cue) defines which art tiles to load
     * for each mapping frame. Each DPLC entry is a word: high nibble = count-1,
     * low 12 bits = source tile index in the uncompressed art.
     * <p>
     * The mapping references VRAM tile indices 0, 0x10, 0x13, etc. which
     * correspond to the DPLC-loaded tiles. We resolve the actual art tile
     * by walking the DPLC entries to build a VRAM→art tile index map.
     */
    /**
     * Render Tails and Tails' tails sprites.
     * ROM: Obj_SStage_9212 (Tails body) + Obj_SStage_9444 (tails' tails).
     * Tails uses palette 1 and has an independent jump height.
     */
    private void renderTailsSprite(Sonic3kSpecialStageManager manager, int centerX, int centerY) {
        // Apply Tails' own jump height offset
        long swapped = manager.getTailsJumpHeight() >> 16;
        int tailsJumpY = (int)(swapped * 32 / 0x58);
        int tailsY = centerY + tailsJumpY;

        // Render Tails body first (behind tails' tails)
        int frame = manager.getTailsMappingFrame();
        renderCharacterSprite(tailsMappingData, tailsMappingFrameCount,
                tailsPatternBase, 1, frame, centerX, tailsY);

        // Render Tails' tails on top of body
        if (tailsTailsPatternBase > 0 && tailsTailsMappingData != null) {
            int ttFrame = manager.getTailsTailsMappingFrame();
            if (ttFrame >= 0 && ttFrame < tailsTailsMappingFrameCount
                    && ttFrame * 2 + 1 < tailsTailsMappingData.length) {
                renderCharacterSprite(tailsTailsMappingData, tailsTailsMappingFrameCount,
                        tailsTailsPatternBase, 1, ttFrame, centerX, tailsY);
            }
        }
    }

    private void renderPlayerSprite(Sonic3kSpecialStagePlayer player,
                                    PlayerCharacter playerCharacter,
                                    int centerX, int centerY) {
        if (!artLoaded || playerPatternBase <= 0 || sonicMappingData == null) return;
        int paletteIndex = playerCharacter == PlayerCharacter.TAILS_ALONE ? 1 : 0;
        renderCharacterSprite(sonicMappingData, sonicMappingFrameCount,
                playerPatternBase, paletteIndex, player.getMappingFrame(), centerX, centerY);
    }

    /**
     * Render a character sprite using ROM mapping + DPLC data.
     * Shared between Sonic and Tails rendering.
     */
    private void renderCharacterSprite(byte[] mappingData, int frameCount,
                                       int patternBase, int paletteIndex,
                                       int frame, int centerX, int centerY) {
        if (mappingData == null || patternBase <= 0) return;
        if (frame >= frameCount) return;

        // --- Step 1: Parse DPLC to build tile remapping table ---
        int dplcHeaderOff = frameCount * 2;
        if (dplcHeaderOff + frame * 2 + 1 >= mappingData.length) return;

        int dplcFrameOff = dplcHeaderOff + readWord(mappingData, dplcHeaderOff + frame * 2);
        if (dplcFrameOff + 1 >= mappingData.length) return;
        int dplcCount = readWord(mappingData, dplcFrameOff);
        if (dplcCount <= 0 || dplcCount > 20) return;

        int[] vramToArt = new int[64];
        int vramSlot = 0;
        for (int d = 0; d < dplcCount; d++) {
            int dplcWord = readWord(mappingData, dplcFrameOff + 2 + d * 2);
            int tileCount = ((dplcWord >> 12) & 0xF) + 1;
            int artTileIdx = dplcWord & 0xFFF;
            for (int t = 0; t < tileCount && vramSlot < 64; t++) {
                vramToArt[vramSlot++] = artTileIdx + t;
            }
        }

        // --- Step 2: Parse mapping and render pieces ---
        if (frame * 2 + 1 >= mappingData.length) return;
        int mapFrameOff = readWord(mappingData, frame * 2);
        if (mapFrameOff + 1 >= mappingData.length) return;

        int pieceCount = readWord(mappingData, mapFrameOff);
        if (pieceCount <= 0 || pieceCount > 10) return;

        for (int p = 0; p < pieceCount; p++) {
            int po = mapFrameOff + 2 + p * 6;
            if (po + 5 >= mappingData.length) break;

            int yOff = (byte) mappingData[po];
            int sizeByte = mappingData[po + 1] & 0xFF;
            int patternWord = readWord(mappingData, po + 2);
            int xOff = readSignedWord(mappingData, po + 4);

            int tilesW = ((sizeByte >> 2) & 3) + 1;
            int tilesH = (sizeByte & 3) + 1;
            int vramTileBase = patternWord & 0x07FF;
            boolean hFlip = (patternWord & 0x0800) != 0;

            // Render each tile of this piece using column-major ordering
            for (int col = 0; col < tilesW; col++) {
                for (int row = 0; row < tilesH; row++) {
                    int tileOff = col * tilesH + row;
                    int vramTile = vramTileBase + tileOff;

                    // Resolve VRAM tile to actual art tile via DPLC
                    int artTile = (vramTile < vramSlot) ? vramToArt[vramTile] : vramTile;
                    int patternId = patternBase + artTile;

                    int drawX = centerX + xOff + col * TILE_SIZE;
                    int drawY = centerY + yOff + row * TILE_SIZE;

                    // H-flip reverses column order
                    if (hFlip) {
                        drawX = centerX + xOff + (tilesW - 1 - col) * TILE_SIZE;
                    }

                    reusableDesc.set(0);
                    reusableDesc.setPriority(true);
                    reusableDesc.setPaletteIndex(paletteIndex);
                    reusableDesc.setHFlip(hFlip);
                    graphicsManager.renderPatternWithId(patternId, reusableDesc, drawX, drawY);
                }
            }
        }
    }

    // ==================== Byte Reading Helpers ====================

    private static int readWord(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    private static int readSignedWord(byte[] data, int offset) {
        return (short)(((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF));
    }

    private static int readLong(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
             | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    // ==================== Palette Index Mapping ====================

    private static int cellTypeToPaletteIndex(int cellType) {
        switch (cellType) {
            case CELL_EMPTY: return -1;
            case CELL_BLUE: return 2;
            case CELL_RED: return 0;
            case CELL_BUMPER: return 1;
            case CELL_RING: return 2;
            case CELL_SPRING: return 1;
            case CELL_CHAOS_EMERALD: case CELL_SUPER_EMERALD: return 2;
            default:
                if (cellType >= CELL_RING_ANIM_1 && cellType <= CELL_RING_ANIM_4) return 2;
                return -1;
        }
    }

    // ==================== Setters ====================

    public boolean isArtLoaded() { return artLoaded; }
    public void setArtLoaded(boolean loaded) {
        if (this.artLoaded != loaded) {
            invalidateStaticGeometry();
        }
        this.artLoaded = loaded;
    }
    public void setFloorPatternBase(int base) { this.floorPatternBase = base; }
    public void setSpherePatternBase(int base) { this.spherePatternBase = base; }
    public void setRingPatternBase(int base) { this.ringPatternBase = base; }
    public void setBgPatternBase(int base) { this.bgPatternBase = base; }
    public void setShadowPatternBase(int base) { this.shadowPatternBase = base; }
    public void setGetBlueSpherePatternBase(int base) { this.getBlueSpherePatternBase = base; }
    public void setGbsArrowPatternBase(int base) { this.gbsArrowPatternBase = base; }
    public void setDigitsPatternBase(int base) { this.digitsPatternBase = base; }
    public void setIconsPatternBase(int base) { this.iconsPatternBase = base; }
    public void setPlayerPatternBase(int base) { this.playerPatternBase = base; }
    public void setEmeraldPatternBase(int base) { this.emeraldPatternBase = base; }
    public void setTailsPatternBase(int base) { this.tailsPatternBase = base; }
    public void setTailsMappingData(byte[] mappingData, byte[] dplcData) {
        this.tailsMappingData = mappingData;
        this.tailsMappingFrameCount = 12;
    }
    public void setTailsTailsPatternBase(int base) { this.tailsTailsPatternBase = base; }
    public void setTailsTailsMappingData(byte[] data) {
        this.tailsTailsMappingData = data;
    }
}
