package com.openggf.game.sonic3k.features;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.events.FireCurtainRenderState;
import com.openggf.game.sonic3k.events.FireCurtainStage;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.LevelManager;

import java.util.ArrayList;
import java.util.List;
import com.openggf.game.GameServices;

final class AizFireCurtainRenderer {
    private static final int COLUMN_COUNT = 20;
    private static final int TILE_SIZE = 8;
    private static final int EMPTY_DESCRIPTOR = 0;
    private static final int PALETTE_MASK = 0x3 << 13;
    private static final int FIRE_PALETTE_INDEX = 3;
    /** BG Y coordinate where fire tiles begin in the AIZ BG layout. */
    private static final int FIRE_TILE_START_BG_Y = 0x100;
    /** BG Y coordinate where fire tiles end (exclusive). */
    private static final int FIRE_TILE_END_BG_Y = 0x310;
    /** Number of BG tile rows in the fire zone. */
    private static final int FIRE_ZONE_ROWS = (FIRE_TILE_END_BG_Y - FIRE_TILE_START_BG_Y) / TILE_SIZE;
    /** AIZ's ROM fire source occupies one 64-tile (512px) VDP background ring. */
    private static final int FIRE_SOURCE_TILE_COLS = 64;
    /** Start of the dense (loopable) fire body, well past the top flame-tip fringe. */
    private static final int FIRE_DENSE_START_BG_Y = 0x150;
    /** End of the dense fire body, well before the bottom transition fringe.
     *  Height = 0x250 - 0x150 = 0x100 = 256px, matching the VDP nametable period. */
    private static final int FIRE_DENSE_END_BG_Y = 0x250;
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(AizFireCurtainRenderer.class.getName());

    private final PatternDesc reusableDesc = new PatternDesc();
    private final TileDescriptorSampler sampler;

    /**
     * Cached fire tile descriptors sampled from the BG layout during RISING
     * (before mutation overwrites the BG data).  Indexed as
     * {@code [fireZoneRow][screenTileCol]} where fireZoneRow =
     * (bgTileY - FIRE_TILE_START_BG_Y) / TILE_SIZE.
     */
    private int[][] cachedFireDescriptors;
    private boolean fireDescriptorsCached;
    AizFireCurtainRenderer() {
        this(null);
    }

    AizFireCurtainRenderer(TileDescriptorSampler sampler) {
        this.sampler = sampler;
    }

    void reset() {
        fireDescriptorsCached = false;
        cachedFireDescriptors = null;
    }

    void render(Camera camera, FireCurtainRenderState state, int screenWidth, int screenHeight) {
        if (camera == null || state == null || !state.active() || state.coverHeightPx() <= 0) {
            return;
        }

        CurtainCompositionPlan plan = buildCompositionPlan(state, screenWidth, screenHeight);
        if (plan.columns().isEmpty()) {
            return;
        }

        GraphicsManager graphicsManager = GameServices.graphics();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        for (ColumnRenderPlan column : plan.columns()) {
            for (TileDraw draw : column.draws()) {
                reusableDesc.set(draw.descriptor());
                graphicsManager.renderPatternWithId(draw.renderPatternId(),
                        reusableDesc,
                        cameraX + draw.screenX(),
                        cameraY + draw.screenY());
            }
        }
    }

    CurtainCompositionPlan buildCompositionPlan(FireCurtainRenderState state, int screenWidth, int screenHeight) {
        if (state == null || !state.active() || state.coverHeightPx() <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            return new CurtainCompositionPlan(screenWidth, screenHeight, List.of());
        }
        // During RISING, sample from the BG level layout (fire tiles are in-place)
        // and cache the descriptors for use after mutation.
        if (state.stage() == FireCurtainStage.AIZ1_RISING) {
            if (sampler != null) {
                return buildSampledPlan(state, screenWidth, screenHeight);
            }
            CurtainCompositionPlan bgPlan = buildBackgroundSampledPlan(state, screenWidth, screenHeight);
            return bgPlan;
        }
        // Post-mutation: use cached BG descriptors if available (preserves real
        // fire tile layout across the mutation). If no cache exists, fail closed
        // rather than synthesizing descriptors that were not sourced from the ROM
        // layout.
        if (fireDescriptorsCached) {
            return buildCachedPlan(state, screenWidth, screenHeight);
        }
        if (sampler != null) {
            return buildSampledPlan(state, screenWidth, screenHeight);
        }
        return buildBackgroundSampledPlan(state, screenWidth, screenHeight);
    }

    private CurtainCompositionPlan buildSampledPlan(FireCurtainRenderState state, int screenWidth, int screenHeight) {
        int[] columnWaveOffsets = state.columnWaveOffsetsPx();
        List<ColumnRenderPlan> columns = new ArrayList<>(COLUMN_COUNT);
        int baseTop = clamp(screenHeight - state.coverHeightPx(), 0, screenHeight);
        int bgY = state.sourceWorldY();

        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            int columnLeft = (columnIndex * screenWidth) / COLUMN_COUNT;
            int columnRight = ((columnIndex + 1) * screenWidth) / COLUMN_COUNT;
            int columnWidth = Math.max(1, columnRight - columnLeft);
            int waveOffset = columnIndex < columnWaveOffsets.length ? columnWaveOffsets[columnIndex] : 0;
            int clipTop = clamp(baseTop + waveOffset, 0, screenHeight);
            if (clipTop >= screenHeight) {
                continue;
            }

            int columnVScroll = bgY + waveOffset;
            int bgAtBottom = columnVScroll + screenHeight;
            int bgAtClipTop = columnVScroll + clipTop;
            int bgRowBottom = Math.floorDiv(bgAtBottom - 1, TILE_SIZE);
            int bgRowTop = Math.floorDiv(bgAtClipTop - TILE_SIZE, TILE_SIZE);

            List<TileDraw> draws = new ArrayList<>();
            int subColumns = Math.max(1, (columnWidth + TILE_SIZE - 1) / TILE_SIZE);
            for (int bgRow = bgRowBottom; bgRow >= bgRowTop; bgRow--) {
                int bgTileY = bgRow * TILE_SIZE;
                int drawY = bgTileY - columnVScroll;
                if (drawY >= screenHeight || drawY + TILE_SIZE <= clipTop) {
                    continue;
                }
                for (int subColumn = 0; subColumn < subColumns; subColumn++) {
                    int drawX = columnLeft + subColumn * TILE_SIZE;
                    if (drawX >= columnRight) {
                        continue;
                    }
                    int sourceX = state.sourceWorldX() + drawX;
                    int descriptor = sampler.sample(sourceX, bgTileY);
                    if ((descriptor & 0x7FF) == EMPTY_DESCRIPTOR) {
                        continue;
                    }
                    int patternIndex = descriptor & 0x7FF;
                    draws.add(new TileDraw(forceFirePalette(descriptor), patternIndex, drawX, drawY));
                }
            }
            if (!draws.isEmpty()) {
                columns.add(new ColumnRenderPlan(columnIndex, columnLeft, columnWidth, clipTop, screenHeight, draws));
            }
        }
        return new CurtainCompositionPlan(screenWidth, screenHeight, columns);
    }

    /**
     * Samples the BG level layout to build the fire curtain plan.
     * Also populates the fire descriptor cache for use after mutation.
     */
    private CurtainCompositionPlan buildBackgroundSampledPlan(FireCurtainRenderState state, int screenWidth, int screenHeight) {
        int[] columnWaveOffsets = state.columnWaveOffsetsPx();
        List<ColumnRenderPlan> columns = new ArrayList<>(COLUMN_COUNT);
        int baseTop = clamp(screenHeight - state.coverHeightPx(), 0, screenHeight);
        int bgY = state.sourceWorldY();
        int tileBase = state.fireOverlayTileBase();
        int tileCount = state.fireOverlayTileCount();

        // Populate the cache with valid fire overlay tiles. If the viewport
        // grows while the rising phase is still active, capture the wider
        // strip before the BG mutation makes those descriptors unavailable.
        int requiredTileColumns = requiredTileColumns(screenWidth);
        if ((!fireDescriptorsCached || cachedTileColumns() < requiredTileColumns) && tileCount > 0) {
            populateFireDescriptorCache(state.sourceWorldX(), tileBase, tileCount, screenWidth);
        }

        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            int columnLeft = (columnIndex * screenWidth) / COLUMN_COUNT;
            int columnRight = ((columnIndex + 1) * screenWidth) / COLUMN_COUNT;
            int columnWidth = Math.max(1, columnRight - columnLeft);
            int waveOffset = columnIndex < columnWaveOffsets.length ? columnWaveOffsets[columnIndex] : 0;
            int clipTop = clamp(baseTop + waveOffset, 0, screenHeight);
            if (clipTop >= screenHeight) {
                continue;
            }

            int columnVScroll = bgY + waveOffset;
            int bgAtBottom = columnVScroll + screenHeight;
            int bgAtClipTop = columnVScroll + clipTop;
            int bgRowBottom = Math.floorDiv(bgAtBottom - 1, TILE_SIZE);
            int bgRowTop = Math.floorDiv(bgAtClipTop - TILE_SIZE, TILE_SIZE);

            List<TileDraw> draws = new ArrayList<>();
            int subColumns = Math.max(1, (columnWidth + TILE_SIZE - 1) / TILE_SIZE);
            for (int bgRow = bgRowBottom; bgRow >= bgRowTop; bgRow--) {
                int bgTileY = bgRow * TILE_SIZE;
                int drawY = bgTileY - columnVScroll;
                if (drawY >= screenHeight || drawY + TILE_SIZE <= clipTop) {
                    continue;
                }
                int wrappedBgTileY = wrapFireTileY(bgTileY, state.wrapFireTiles());
                if (wrappedBgTileY < 0) {
                    continue;
                }
                for (int subColumn = 0; subColumn < subColumns; subColumn++) {
                    int drawX = columnLeft + subColumn * TILE_SIZE;
                    if (drawX >= columnRight) {
                        continue;
                    }
                    int sourceX = state.sourceWorldX() + drawX;
                    int descriptor = sampleBackgroundStripDescriptor(sourceX, wrappedBgTileY);
                    int patternIndex = descriptor & 0x7FF;

                    // Skip empty tiles — fire zone boundary rows contain
                    // transparent buffer tiles (pattern=0) that the ROM
                    // renders as invisible via VDP priority.  Drawing a
                    // synthetic fire tile here creates a garbage row.
                    if (patternIndex == EMPTY_DESCRIPTOR) {
                        continue;
                    }
                    if (patternIndex >= tileBase && patternIndex < tileBase + tileCount) {
                        draws.add(new TileDraw(forceFirePalette(descriptor), patternIndex, drawX, drawY));
                    }
                }
            }
            if (!draws.isEmpty()) {
                columns.add(new ColumnRenderPlan(columnIndex, columnLeft, columnWidth, clipTop, screenHeight, draws));
            }
        }

        return new CurtainCompositionPlan(screenWidth, screenHeight, columns);
    }

    /**
     * Populates the fire descriptor cache by sampling the entire fire zone
     * from the BG layout at a fixed X offset. Called during RISING, before the
     * mutation overwrites the BG data.
     */
    private void populateFireDescriptorCache(int sourceWorldX, int tileBase, int tileCount, int screenWidth) {
        int screenTileColumns = requiredTileColumns(screenWidth);
        cachedFireDescriptors = new int[FIRE_ZONE_ROWS][screenTileColumns];
        for (int row = 0; row < FIRE_ZONE_ROWS; row++) {
            int bgTileY = FIRE_TILE_START_BG_Y + row * TILE_SIZE;
            for (int col = 0; col < screenTileColumns; col++) {
                int sourceCol = Math.floorMod(col, FIRE_SOURCE_TILE_COLS);
                int worldX = sourceWorldX + sourceCol * TILE_SIZE;
                int descriptor = sampleBackgroundStripDescriptor(worldX, bgTileY);
                int patternIndex = descriptor & 0x7FF;
                // Leave empty tiles as 0 in the cache — they are transparent
                // buffer rows that must not become synthetic fire tiles.
                if (patternIndex == EMPTY_DESCRIPTOR) {
                    continue;
                }
                if (patternIndex >= tileBase && patternIndex < tileBase + tileCount) {
                    cachedFireDescriptors[row][col] = forceFirePalette(descriptor);
                }
            }
        }
        fireDescriptorsCached = true;
        LOG.info("Cached fire tile descriptors: " + FIRE_ZONE_ROWS + " rows × " + screenTileColumns + " cols");
    }

    /**
     * Builds a fire curtain plan from cached BG descriptors.
     * Used after mutation when the BG has been overwritten with AIZ2 data
     * but the VRAM fire tiles (0x500+) still exist.
     */
    private CurtainCompositionPlan buildCachedPlan(FireCurtainRenderState state,
                                                    int screenWidth, int screenHeight) {
        int[] columnWaveOffsets = state.columnWaveOffsetsPx();
        int bgY = state.sourceWorldY();
        List<ColumnRenderPlan> columns = new ArrayList<>(COLUMN_COUNT);
        int baseTop = clamp(screenHeight - state.coverHeightPx(), 0, screenHeight);
        int cachedTileColumns = cachedTileColumns();

        for (int columnIndex = 0; columnIndex < COLUMN_COUNT; columnIndex++) {
            int columnLeft = (columnIndex * screenWidth) / COLUMN_COUNT;
            int columnRight = ((columnIndex + 1) * screenWidth) / COLUMN_COUNT;
            int columnWidth = Math.max(1, columnRight - columnLeft);
            int waveOffset = columnIndex < columnWaveOffsets.length ? columnWaveOffsets[columnIndex] : 0;
            int clipTop = clamp(baseTop + waveOffset, 0, screenHeight);
            if (clipTop >= screenHeight) {
                continue;
            }

            int columnVScroll = bgY + waveOffset;
            int bgAtBottom = columnVScroll + screenHeight;
            int bgAtClipTop = columnVScroll + clipTop;
            int bgRowBottom = Math.floorDiv(bgAtBottom - 1, TILE_SIZE);
            int bgRowTop = Math.floorDiv(bgAtClipTop - TILE_SIZE, TILE_SIZE);

            List<TileDraw> draws = new ArrayList<>();
            int subColumns = Math.max(1, (columnWidth + TILE_SIZE - 1) / TILE_SIZE);
            for (int bgRow = bgRowBottom; bgRow >= bgRowTop; bgRow--) {
                int bgTileY = bgRow * TILE_SIZE;
                // Wrap tile Y into the fire zone to emulate VDP BG plane
                // wrapping — fire tiles loop as the BG scrolls past the
                // fire zone end, matching the ROM's continuous fire effect.
                int wrappedTileY = wrapFireTileY(bgTileY, state.wrapFireTiles());
                if (wrappedTileY < 0) {
                    continue;
                }
                int fireRow = (wrappedTileY - FIRE_TILE_START_BG_Y) / TILE_SIZE;
                int drawY = bgTileY - columnVScroll;
                if (drawY >= screenHeight || drawY + TILE_SIZE <= clipTop) {
                    continue;
                }
                for (int subColumn = 0; subColumn < subColumns; subColumn++) {
                    int drawX = columnLeft + subColumn * TILE_SIZE;
                    if (drawX >= columnRight) {
                        continue;
                    }
                    int cacheCol = drawX / TILE_SIZE;
                    if (fireRow < 0 || fireRow >= FIRE_ZONE_ROWS
                            || cacheCol < 0 || cacheCol >= cachedTileColumns) {
                        continue;
                    }
                    int descriptor = cachedFireDescriptors[fireRow][cacheCol];
                    if ((descriptor & 0x7FF) == EMPTY_DESCRIPTOR) {
                        continue;
                    }
                    int patternIndex = descriptor & 0x7FF;
                    draws.add(new TileDraw(descriptor, patternIndex, drawX, drawY));
                }
            }
            if (!draws.isEmpty()) {
                columns.add(new ColumnRenderPlan(columnIndex, columnLeft, columnWidth, clipTop, screenHeight, draws));
            }
        }
        return new CurtainCompositionPlan(screenWidth, screenHeight, columns);
    }

    private static int requiredTileColumns(int screenWidth) {
        return (screenWidth + TILE_SIZE - 1) / TILE_SIZE;
    }

    private int cachedTileColumns() {
        return cachedFireDescriptors == null || cachedFireDescriptors.length == 0
                ? 0
                : cachedFireDescriptors[0].length;
    }

    private static int sampleBackgroundStripDescriptor(int sourceX, int sourceY) {
        LevelManager levelManager = GameServices.levelOrNull();
        if (levelManager == null || levelManager.getCurrentLevel() == null) {
            return 0;
        }
        return levelManager.getBackgroundTileDescriptorAtWorld(sourceX, sourceY);
    }

    /**
     * Wraps a BG tile Y coordinate for the fire curtain.
     * <ul>
     *   <li>Below fire zone (&lt; 0x100): returns -1 (skip)</li>
     *   <li>Within fire zone [0x100, 0x310): returns as-is (includes flame
     *       tips at top and transition tiles at bottom)</li>
     *   <li>Past fire zone (&ge; 0x310): wraps into the <em>dense middle</em>
     *       [0x118, 0x2B8) so the fire body loops without repeating the
     *       flame-tip or transition fringe rows</li>
     * </ul>
     */
    private static int wrapFireTileY(int bgTileY, boolean wrapEnabled) {
        if (bgTileY < FIRE_TILE_START_BG_Y) {
            return -1;
        }
        if (bgTileY < FIRE_DENSE_END_BG_Y) {
            return bgTileY;
        }
        if (!wrapEnabled) {
            // Scroll-off: show tiles through the full fire zone [0x100, 0x310),
            // clip only past the actual fire zone end.
            return bgTileY < FIRE_TILE_END_BG_Y ? bgTileY : -1;
        }
        // Linger: wrap into the dense middle body so fire loops.
        // Dense height is 256 (power of 2) so use bitmask instead of modulo.
        int offset = bgTileY - FIRE_DENSE_START_BG_Y;
        return FIRE_DENSE_START_BG_Y + (offset & 0xFF);
    }

    private static int forceFirePalette(int descriptor) {
        return (descriptor & ~PALETTE_MASK) | (FIRE_PALETTE_INDEX << 13);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @FunctionalInterface
    interface TileDescriptorSampler {
        int sample(int worldX, int worldY);
    }

    record TileDraw(int descriptor, int renderPatternId, int screenX, int screenY) {
    }

    record ColumnRenderPlan(int columnIndex, int screenX, int widthPx, int topY, int bottomY, List<TileDraw> draws) {
    }

    record CurtainCompositionPlan(int screenWidth,
                                  int screenHeight,
                                  List<ColumnRenderPlan> columns) {
    }
}
