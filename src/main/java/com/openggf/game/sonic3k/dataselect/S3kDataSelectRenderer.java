package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.GameServices;
import com.openggf.game.dataselect.HostSlotPreview;
import com.openggf.game.sonic3k.S3kFrontendPaletteUploader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.List;
import java.util.Arrays;

import static org.lwjgl.opengl.GL11.glClearColor;

/**
 * ROM-backed asset contract for the donated S3K Data Select renderer.
 *
 * <p>Palette accessors are renderer-facing contracts, not arbitrary raw palette dumps.
 * In particular, {@link #getEmeraldPaletteBytes()} must provide bytes already shaped for
 * the save-card overlay path: the emerald bytes are applied onto palette line 2 starting
 * at color 1, while color 0 stays owned by the underlying character palette.</p>
 */
interface S3kDataSelectAssetSource {
    void loadData() throws java.io.IOException;
    boolean isLoaded();
    int getMusicId();
    int[] getLayoutWords();
    default int[] getPlaneALayoutWords() {
        return getLayoutWords();
    }
    int[] getNewLayoutWords();
    int[][] getStaticLayouts();
    int[] getMenuBackgroundLayoutWords();
    Pattern[] getMenuBackgroundPatterns();
    Pattern[] getMiscPatterns();
    Pattern[] getExtraPatterns();
    default Pattern[] getTextPatterns() {
        return new Pattern[0];
    }
    default Pattern[] getSlotIconPatterns(int iconIndex) {
        return new Pattern[0];
    }
    default Palette getSelectedSlotIconPalette(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
        return null;
    }
    default SpriteMappingFrame getSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
        return null;
    }
    default boolean useScaledSelectedSlotIconFrame(S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
        return false;
    }
    Pattern[] getSkZonePatterns();
    Pattern[] getPortraitPatterns();
    Pattern[] getS3ZonePatterns();
    byte[] getMenuBackgroundPaletteBytes();
    byte[] getCharacterPaletteBytes();
    /**
     * Returns emerald palette bytes for the save-card overlay path.
     *
     * <p>This is not a free-form host palette line. The renderer overlays these bytes onto
     * palette line 2 starting at color 1 so the native S3K save-card emerald mappings can
     * keep working. Donated hosts may therefore adapt their colors into this format instead
     * of returning raw source palette slots unchanged.</p>
     */
    byte[] getEmeraldPaletteBytes();
    byte[][] getFinishCardPalettes();
    byte[][] getZoneCardPalettes();
    byte[] getS3ZoneCard8PaletteBytes();
    List<SpriteMappingFrame> getSaveScreenMappings();
    S3kSaveScreenLayoutObjects getSaveScreenLayoutObjects();
    default HostEmeraldLayoutProfile getHostEmeraldLayoutProfile() {
        return HostEmeraldLayoutProfile.defaultSeven();
    }
    default byte[] getCustomEmeraldPaletteBytes() {
        return new byte[0];
    }
}

public class S3kDataSelectRenderer {
    private static final int SCREEN_WIDTH = 320;
    private static final int SCREEN_HEIGHT = 224;
    private static final int SCREEN_TILE_WIDTH = 40;
    private static final int SCREEN_TILE_HEIGHT = 28;

    // Widescreen support — default 320 (native); set by setViewportWidth() before each draw.
    private int viewportWidth = SCREEN_WIDTH;
    private static final int PLANE_WIDTH_TILES = 128;
    private static final int PLANE_HEIGHT_TILES = 32;
    private static final int CARD_TILE_WIDTH = 10;
    private static final int CARD_TILE_HEIGHT = 7;
    private static final int SCREEN_SPACE_WORLD_ORIGIN = 128;
    private static final int SAVE_SLOT_PLANE_OFFSET = 0x021A;
    private static final int SAVE_SLOT_PLANE_STEP = 0x001A;
    private static final int SAVE_SLOT_LABEL_OFFSET = 0x0A20;
    private static final int SAVE_SLOT_LIVES_OFFSET = 0x1220;
    private static final int SAVE_SLOT_LABEL_PREFIX_OFFSET = 0x0A1E;
    private static final int NO_SAVE_TEXT_OFFSET = 0x0C06;
    private static final int SAVE_TEXT_OFFSET = 0x0C0C;
    private static final int DELETE_TEXT_OFFSET = 0x0CEC;
    private static final int LAUNCH_ERROR_TEXT_Y = 208;
    private static final int LAUNCH_ERROR_HORIZONTAL_MARGIN_TILES = 2;
    private static final int SELECTED_ICON_DMA_TILE_COUNT = (0x460 * 2) / Pattern.PATTERN_SIZE_IN_ROM;
    private static final int SELECTED_ICON_TARGET_X = -40;
    private static final int SELECTED_ICON_TARGET_Y = -120;
    private static final float SELECTED_ICON_TARGET_WIDTH = 80f;
    private static final float SELECTED_ICON_TARGET_HEIGHT = 56f;

    private static final int DATA_SELECT_PATTERN_BASE = PatternAtlasRange.MENU_AND_DATA_SELECT.base();
    private static final int TILE_WORD_FLAGS = 0xA000;
    private static final int SAVE_TEXT_WORD_BASE = Sonic3kConstants.ARTTILE_SAVE_TEXT - 0x10 + TILE_WORD_FLAGS;
    private static final int SAVE_SCREEN_OBJECT_BASE_DESC = 0x8000;

    private final PatternDesc reusableDesc = new PatternDesc();

    private boolean cached;

    // Cached static text arrays — avoid per-frame allocations
    private int[] cachedBlankLabelWords;
    private int[] cachedClearLabelWords;
    private int[] cachedBlankStatWords;
    private int[] cachedNoTextWords;
    private int[] cachedSaveTextWords;
    private int[] cachedDeleteTextWords;
    private String cachedLaunchErrorText;
    private int[] cachedLaunchErrorWords;
    private final int[][] cachedHeaderWords = new int[4][];
    private final java.util.Map<Integer, int[]> cachedZoneLabelWords = new java.util.HashMap<>();
    private final java.util.Map<Integer, int[]> cachedDigitWords = new java.util.HashMap<>();

    public void draw(S3kDataSelectAssetSource assets,
                     S3kSaveScreenObjectState objectState) {
        draw(GameServices.graphics(), assets, objectState);
    }

    void draw(GraphicsManager graphics,
              S3kDataSelectAssetSource assets,
              S3kSaveScreenObjectState objectState) {
        if (assets == null || objectState == null || !assets.isLoaded()) {
            return;
        }

        if (graphics == null || graphics.isHeadlessMode()) {
            return;
        }

        ensureCached(graphics, assets);
        // Widescreen: shift all content right by xOffset() by subtracting xOffset() from cameraX
        // (since screen-space X = worldX - effectiveCameraX). The menu background is expanded
        // by tiling instead. xOffset() == 0 at native 320 — byte-identical.
        int cameraX = objectState.selectorState().cameraX() - xOffset();
        boolean prevBatchingEnabled = graphics.isBatchingEnabled();
        boolean prevInstancedBatchingEnabled = graphics.isInstancedBatchingEnabled();
        graphics.setBatchingEnabled(true);
        graphics.setInstancedBatchingEnabled(true);
        try {
            graphics.beginPatternBatch();
            renderScreenTilemapTiled(graphics, assets.getMenuBackgroundLayoutWords(),
                    SCREEN_TILE_WIDTH, SCREEN_TILE_HEIGHT, viewportWidth);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderPlaneABase(graphics, assets.getPlaneALayoutWords(), cameraX, false);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderCardsPlaneLayer(graphics, assets, objectState, cameraX, false);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderStaticPlaneTextOverlays(graphics, cameraX, false);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderTitle(graphics, assets, objectState, cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderCardsSpriteBaseLayer(graphics, assets, objectState, cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderPlaneABase(graphics, assets.getPlaneALayoutWords(), cameraX, true);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderCardsPlaneLayer(graphics, assets, objectState, cameraX, true);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderStaticPlaneTextOverlays(graphics, cameraX, true);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderCardsSpriteEmeraldLayer(graphics, assets, objectState, cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            cacheSelectedSlotIcon(graphics, assets, objectState.selectedSlotIcon());
            renderSelectedSlotIcon(graphics, assets, objectState.selectedSlotIcon(), cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderCardsSpriteOverlayLayer(graphics, assets, objectState, cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderDelete(graphics, assets, objectState, cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderSelector(graphics, assets, objectState, cameraX);
            flushLayer(graphics);

            graphics.beginPatternBatch();
            renderLaunchErrorMessage(graphics, objectState.launchErrorMessage());
            flushLayer(graphics);
        } finally {
            graphics.setBatchingEnabled(prevBatchingEnabled);
            graphics.setInstancedBatchingEnabled(prevInstancedBatchingEnabled);
        }
    }

    public void setClearColor(S3kDataSelectAssetSource assets) {
        Palette backdrop = paletteFromBytes(assets != null ? assets.getMenuBackgroundPaletteBytes() : null, 0);
        if (backdrop == null) {
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            return;
        }
        Palette.Color color = backdrop.getColor(0);
        glClearColor(color.rFloat(), color.gFloat(), color.bFloat(), 1.0f);
    }

    /**
     * Sets the projection-space viewport width for widescreen support.
     *
     * <p>The data-select menu background tiles to fill the full viewport width.
     * All other content (save cards, title, selector) is centred by shifting
     * their effective camera origin by {@code (viewportWidth - 320) / 2}.
     * At native width 320 the offset is 0 — byte-identical output.
     */
    public void setViewportWidth(int width) {
        this.viewportWidth = Math.max(SCREEN_WIDTH, width);
    }

    /** Horizontal pixel offset for centering native-320 content. Returns 0 at native 320. */
    private int xOffset() {
        return (viewportWidth - SCREEN_WIDTH) / 2;
    }

    public void reset() {
        cached = false;
        characterPalettesBuilt = false;
        cachedCharLine1 = null;
        cachedCharLine2 = null;
        lastCachedIconIndex = -1;
        lastCachedIconPaletteIndex = -1;
        cachedIconPalette = null;
        cachedIconPaletteLine = -1;
        cachedBlankLabelWords = null;
        cachedClearLabelWords = null;
        cachedBlankStatWords = null;
        cachedNoTextWords = null;
        cachedSaveTextWords = null;
        cachedDeleteTextWords = null;
        cachedLaunchErrorText = null;
        cachedLaunchErrorWords = null;
        Arrays.fill(cachedHeaderWords, null);
        cachedZoneLabelWords.clear();
        cachedDigitWords.clear();
    }

    private void ensureCached(GraphicsManager graphics, S3kDataSelectAssetSource assets) {
        if (cached) {
            return;
        }

        graphics.cachePatternTexture(new Pattern(), DATA_SELECT_PATTERN_BASE);
        cachePatterns(graphics, assets.getMenuBackgroundPatterns(),
                DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_S3_MENU_BG);
        cachePatterns(graphics, assets.getMiscPatterns(),
                DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC);
        cachePatterns(graphics, assets.getExtraPatterns(),
                DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_EXTRA);
        cachePatterns(graphics, assets.getTextPatterns(),
                DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_TEXT);

        cachePalette(graphics, assets.getMenuBackgroundPaletteBytes(), 0);
        cacheCharacterAndEmeraldPalettes(graphics,
                assets.getCharacterPaletteBytes(),
                assets.getEmeraldPaletteBytes());

        byte[][] finishPalettes = assets.getFinishCardPalettes();
        if (finishPalettes.length > 0) {
            cachePalette(graphics, finishPalettes[0], 3);
        }

        cached = true;
    }

    private void flushLayer(GraphicsManager graphics) {
        graphics.flushPatternBatch();
        graphics.flush();
    }

    private int lastCachedIconIndex = -1;
    private int lastCachedIconPaletteIndex = -1;
    private boolean lastCachedIconFinishCard;
    private Palette cachedIconPalette;
    private int cachedIconPaletteLine = -1;

    private void cacheSelectedSlotIcon(GraphicsManager graphics,
                                       S3kDataSelectAssetSource assets,
                                       S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
        if (selectedSlotIcon == null) {
            lastCachedIconIndex = -1;
            lastCachedIconPaletteIndex = -1;
            cachedIconPalette = null;
            return;
        }

        boolean iconChanged = selectedSlotIcon.iconIndex() != lastCachedIconIndex
                || selectedSlotIcon.finishCard() != lastCachedIconFinishCard;
        boolean paletteChanged = iconChanged
                || selectedSlotIcon.paletteIndex() != lastCachedIconPaletteIndex;

        // Re-upload patterns only when the icon changes (expensive GPU upload)
        if (iconChanged) {
            lastCachedIconIndex = selectedSlotIcon.iconIndex();
            lastCachedIconFinishCard = selectedSlotIcon.finishCard();
            Pattern[] patterns = assets.getSlotIconPatterns(selectedSlotIcon.iconIndex());
            if (patterns.length == 0) {
                return;
            }
            int patternBase = DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC
                    + (selectedSlotIcon.finishCard() ? 0x27D : 0x31B);
            cachePatterns(graphics,
                    java.util.Arrays.copyOf(patterns, Math.min(patterns.length, SELECTED_ICON_DMA_TILE_COUNT)),
                    patternBase);
        }

        // Rebuild palette object only when palette source changes; re-upload to
        // GPU every frame (cheap glTexSubImage2D) because the emerald layer
        // overwrites this palette line each frame.
        cachedIconPaletteLine = selectedSlotIconPaletteLine(assets, selectedSlotIcon);
        if (paletteChanged) {
            lastCachedIconPaletteIndex = selectedSlotIcon.paletteIndex();
            Palette selectedPalette = assets.getSelectedSlotIconPalette(selectedSlotIcon);
            if (selectedPalette != null) {
                cachedIconPalette = selectedPalette.deepCopy();
            } else {
                byte[] paletteBytes = selectedSlotIcon.finishCard()
                        ? paletteAt(assets.getFinishCardPalettes(), selectedSlotIcon.paletteIndex())
                        : selectedIconZonePaletteBytes(assets, selectedSlotIcon.paletteIndex());
                cachedIconPalette = paletteFromBytes(paletteBytes, 0);
            }
        }
        if (cachedIconPalette != null) {
            S3kFrontendPaletteUploader.cacheLine(graphics, cachedIconPalette, cachedIconPaletteLine);
        }
    }

    private int selectedSlotIconPaletteLine(S3kDataSelectAssetSource assets,
                                            S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon) {
        SpriteMappingFrame customFrame = assets.getSelectedSlotIconFrame(selectedSlotIcon);
        if (customFrame != null && !customFrame.pieces().isEmpty()) {
            return customFrame.pieces().getFirst().paletteIndex();
        }
        return 3;
    }

    private byte[] selectedIconZonePaletteBytes(S3kDataSelectAssetSource assets, int paletteIndex) {
        if (paletteIndex == 7) {
            byte[] special = assets.getS3ZoneCard8PaletteBytes();
            if (special != null && special.length > 0) {
                return special;
            }
        }
        return paletteAt(assets.getZoneCardPalettes(), paletteIndex);
    }

    private void renderCardsPlaneLayer(GraphicsManager graphics,
                                       S3kDataSelectAssetSource assets,
                                       S3kSaveScreenObjectState objectState,
                                       int cameraX,
                                       boolean highPriority) {
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        S3kSaveScreenObjectState.VisualState visualState = objectState.visualState();
        List<S3kSaveScreenObjectState.SlotVisualState> slotStates = visualState.slotStates();
        for (int slotIndex = 0; slotIndex < slotStates.size(); slotIndex++) {
            S3kSaveScreenObjectState.SlotVisualState slotState = slotStates.get(slotIndex);
            int[] words = selectSlotLayout(assets, visualState, slotState);
            renderPlaneOverlayTilemap(graphics, words, CARD_TILE_WIDTH, CARD_TILE_HEIGHT,
                    SAVE_SLOT_PLANE_OFFSET + (slotIndex * SAVE_SLOT_PLANE_STEP), cameraX, highPriority);
            renderSlotMetadata(graphics, slotState, slotIndex, cameraX, highPriority);
        }
    }

    private void renderCardsSpriteBaseLayer(GraphicsManager graphics,
                                            S3kDataSelectAssetSource assets,
                                            S3kSaveScreenObjectState objectState,
                                            int cameraX) {
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        S3kSaveScreenObjectState.VisualState visualState = objectState.visualState();
        renderNoSaveBase(graphics, assets, layoutObjects, visualState, cameraX);

        List<S3kSaveScreenObjectState.SlotVisualState> slotStates = visualState.slotStates();
        for (int slotIndex = 0; slotIndex < slotStates.size(); slotIndex++) {
            S3kSaveScreenObjectState.SlotVisualState slotState = slotStates.get(slotIndex);
            S3kSaveScreenLayoutObjects.SaveSlotObject slotObject = layoutObjects.slots().get(slotIndex);
            renderSlotBase(graphics, assets, slotObject, slotState, cameraX);
        }
    }

    private void renderCardsSpriteOverlayLayer(GraphicsManager graphics,
                                               S3kDataSelectAssetSource assets,
                                               S3kSaveScreenObjectState objectState,
                                               int cameraX) {
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        S3kSaveScreenObjectState.VisualState visualState = objectState.visualState();
        renderNoSaveOverlay(graphics, assets, layoutObjects, visualState, cameraX);

        List<S3kSaveScreenObjectState.SlotVisualState> slotStates = visualState.slotStates();
        for (int slotIndex = 0; slotIndex < slotStates.size(); slotIndex++) {
            S3kSaveScreenObjectState.SlotVisualState slotState = slotStates.get(slotIndex);
            S3kSaveScreenLayoutObjects.SaveSlotObject slotObject = layoutObjects.slots().get(slotIndex);
            renderSlotOverlay(graphics, assets, slotObject, slotState, cameraX);
        }
    }

    private void renderCardsSpriteEmeraldLayer(GraphicsManager graphics,
                                               S3kDataSelectAssetSource assets,
                                               S3kSaveScreenObjectState objectState,
                                               int cameraX) {
        cacheCharacterAndEmeraldPalettes(graphics,
                assets.getCharacterPaletteBytes(),
                assets.getEmeraldPaletteBytes());
        cachePalette(graphics, assets.getCustomEmeraldPaletteBytes(), 3, 1);
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        HostEmeraldLayoutProfile layout = assets.getHostEmeraldLayoutProfile();
        List<S3kSaveScreenObjectState.SlotVisualState> slotStates = objectState.visualState().slotStates();
        for (int slotIndex = 0; slotIndex < slotStates.size(); slotIndex++) {
            S3kSaveScreenObjectState.SlotVisualState slotState = slotStates.get(slotIndex);
            S3kSaveScreenLayoutObjects.SaveSlotObject slotObject = layoutObjects.slots().get(slotIndex);
            int slotWorldX = slotObject.worldX() - cameraX;
            int slotWorldY = slotObject.worldY();
            int emeraldCount = Math.min(slotState.emeraldMappingFrames().size(),
                    Math.min(layout.activeEmeraldCount(), layout.positions().size()));
            for (int emeraldIndex = 0; emeraldIndex < emeraldCount; emeraldIndex++) {
                HostEmeraldLayoutProfile.Point offset = layout.positions().get(emeraldIndex);
                int emeraldFrame = slotState.emeraldMappingFrames().get(emeraldIndex);
                renderObjectFrame(graphics, assets, emeraldFrame,
                        slotWorldX + offset.x(), slotWorldY + offset.y(),
                        SAVE_SCREEN_OBJECT_BASE_DESC);
            }
        }
    }

    private void renderTitle(GraphicsManager graphics,
                             S3kDataSelectAssetSource assets,
                             S3kSaveScreenObjectState objectState,
                             int cameraX) {
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        renderObjectFrame(graphics, assets, layoutObjects.titleText().mappingFrame(),
                layoutObjects.titleText().worldX(), layoutObjects.titleText().worldY(),
                SAVE_SCREEN_OBJECT_BASE_DESC);
    }

    private void renderSelector(GraphicsManager graphics,
                                S3kDataSelectAssetSource assets,
                                S3kSaveScreenObjectState objectState,
                                int cameraX) {
        if (!objectState.selectorState().visible()) {
            return;
        }
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        int frameIndex = objectState.selectorState().mappingFrame();
        List<SpriteMappingFrame> mappings = assets.getSaveScreenMappings();
        if (frameIndex < 0 || frameIndex >= mappings.size()) {
            return;
        }
        int selectorWorldY = layoutObjects.selector().worldY();
        renderMappingFrame(graphics,
                mappings.get(frameIndex),
                objectState.selectorState().selectorBiasedX() - 128,
                selectorWorldY - 128,
                DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC,
                SAVE_SCREEN_OBJECT_BASE_DESC);
    }

    private void renderSelectedSlotIcon(GraphicsManager graphics,
                                        S3kDataSelectAssetSource assets,
                                        S3kSaveScreenObjectState.SelectedSlotIcon selectedSlotIcon,
                                        int cameraX) {
        if (selectedSlotIcon == null) {
            return;
        }
        SpriteMappingFrame customFrame = assets.getSelectedSlotIconFrame(selectedSlotIcon);
        if (customFrame != null) {
            if (assets.useScaledSelectedSlotIconFrame(selectedSlotIcon)) {
                renderScaledMappingFrame(graphics, customFrame,
                        selectedSlotIcon.worldX() - cameraX - 128,
                        selectedSlotIcon.worldY() - 128,
                        DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC);
                return;
            }
            renderMappingFrame(graphics, customFrame,
                    selectedSlotIcon.worldX() - cameraX - 128,
                    selectedSlotIcon.worldY() - 128,
                    DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC,
                    SAVE_SCREEN_OBJECT_BASE_DESC);
            return;
        }
        renderObjectFrame(graphics, assets, selectedSlotIcon.mappingFrame(),
                selectedSlotIcon.worldX() - cameraX, selectedSlotIcon.worldY(),
                SAVE_SCREEN_OBJECT_BASE_DESC);
    }

    private void renderNoSaveBase(GraphicsManager graphics,
                                  S3kDataSelectAssetSource assets,
                                  S3kSaveScreenLayoutObjects layoutObjects,
                                  S3kSaveScreenObjectState.VisualState visualState,
                                  int cameraX) {
        if (visualState.noSaveCustomFrame() != null) {
            renderMappingFrame(graphics, visualState.noSaveCustomFrame(),
                    layoutObjects.noSave().worldX() - cameraX - 128,
                    layoutObjects.noSave().worldY() - 128,
                    DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC,
                    SAVE_SCREEN_OBJECT_BASE_DESC);
        } else {
            renderObjectFrame(graphics, assets, visualState.noSaveMappingFrame(),
                    layoutObjects.noSave().worldX() - cameraX, layoutObjects.noSave().worldY(),
                    SAVE_SCREEN_OBJECT_BASE_DESC);
        }
    }

    private void renderScaledMappingFrame(GraphicsManager graphics,
                                          SpriteMappingFrame frame,
                                          int worldX,
                                          int worldY,
                                          int patternBase) {
        if (frame == null || frame.pieces().isEmpty()) {
            return;
        }
        float[] bounds = mappingFrameBounds(frame);
        float sourceX = bounds[0];
        float sourceY = bounds[1];
        float sourceWidth = bounds[2];
        float sourceHeight = bounds[3];
        if (sourceWidth <= 0f || sourceHeight <= 0f) {
            return;
        }
        List<SpriteMappingPiece> pieces = frame.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            renderScaledMappingPiece(graphics, pieces.get(i), worldX, worldY, patternBase,
                    sourceX, sourceY, sourceWidth, sourceHeight);
        }
    }

    private void renderScaledMappingPiece(GraphicsManager graphics,
                                          SpriteMappingPiece piece,
                                          int worldX,
                                          int worldY,
                                          int patternBase,
                                          float sourceX,
                                          float sourceY,
                                          float sourceWidth,
                                          float sourceHeight) {
        float tileWidth = SELECTED_ICON_TARGET_WIDTH / sourceWidth * 8f;
        float tileHeight = SELECTED_ICON_TARGET_HEIGHT / sourceHeight * 8f;
        for (int tx = 0; tx < piece.widthTiles(); tx++) {
            for (int ty = 0; ty < piece.heightTiles(); ty++) {
                int tileOffset = tx * piece.heightTiles() + ty;
                int patternId = patternBase + piece.tileIndex() + tileOffset;
                float sourceTileX = piece.xOffset() + (tx * 8f);
                float sourceTileY = piece.yOffset() + (ty * 8f);
                float targetTileX = worldX + SELECTED_ICON_TARGET_X
                        + ((sourceTileX - sourceX) / sourceWidth) * SELECTED_ICON_TARGET_WIDTH;
                float targetTileY = worldY + SELECTED_ICON_TARGET_Y
                        + ((sourceTileY - sourceY) / sourceHeight) * SELECTED_ICON_TARGET_HEIGHT;
                reusableDesc.set(patternId & 0x7FF);
                if (piece.hFlip()) {
                    reusableDesc.set(reusableDesc.get() | 0x0800);
                }
                if (piece.vFlip()) {
                    reusableDesc.set(reusableDesc.get() | 0x1000);
                }
                reusableDesc.set(reusableDesc.get() | ((piece.paletteIndex() & 0x3) << 13));
                if (piece.priority()) {
                    reusableDesc.set(reusableDesc.get() | 0x8000);
                }
                graphics.renderPatternWithIdScaled(patternId, reusableDesc,
                        targetTileX, targetTileY, tileWidth, tileHeight);
            }
        }
    }

    private float[] mappingFrameBounds(SpriteMappingFrame frame) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        for (SpriteMappingPiece piece : frame.pieces()) {
            minX = Math.min(minX, piece.xOffset());
            minY = Math.min(minY, piece.yOffset());
            maxX = Math.max(maxX, piece.xOffset() + piece.widthTiles() * 8f);
            maxY = Math.max(maxY, piece.yOffset() + piece.heightTiles() * 8f);
        }
        return new float[]{minX, minY, maxX - minX, maxY - minY};
    }

    private void renderNoSaveOverlay(GraphicsManager graphics,
                                     S3kDataSelectAssetSource assets,
                                     S3kSaveScreenLayoutObjects layoutObjects,
                                     S3kSaveScreenObjectState.VisualState visualState,
                                     int cameraX) {
        renderObjectFrame(graphics, assets, visualState.noSaveChildMappingFrame(),
                layoutObjects.noSave().worldX() - cameraX, layoutObjects.noSave().worldY(),
                SAVE_SCREEN_OBJECT_BASE_DESC);
    }

    private void renderDelete(GraphicsManager graphics,
                              S3kDataSelectAssetSource assets,
                              S3kSaveScreenObjectState objectState,
                              int cameraX) {
        S3kSaveScreenLayoutObjects layoutObjects = objectState.layoutObjects();
        S3kSaveScreenObjectState.VisualState visualState = objectState.visualState();
        renderObjectFrame(graphics, assets, visualState.deleteMappingFrame(),
                objectState.deleteWorldX() - cameraX, layoutObjects.deleteIcon().worldY(),
                SAVE_SCREEN_OBJECT_BASE_DESC);
        renderObjectFrame(graphics, assets, visualState.deleteChildMappingFrame(),
                objectState.deleteWorldX() - cameraX, layoutObjects.deleteIcon().worldY(),
                SAVE_SCREEN_OBJECT_BASE_DESC);
    }

    private void renderSlotBase(GraphicsManager graphics,
                                S3kDataSelectAssetSource assets,
                                S3kSaveScreenLayoutObjects.SaveSlotObject slotObject,
                                S3kSaveScreenObjectState.SlotVisualState slotState,
                                int cameraX) {
        int slotWorldX = slotObject.worldX() - cameraX;
        int slotWorldY = slotObject.worldY();
        if (slotState.customObjectFrame() != null) {
            renderMappingFrame(graphics, slotState.customObjectFrame(), slotWorldX - 128, slotWorldY - 128,
                    DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC,
                    SAVE_SCREEN_OBJECT_BASE_DESC);
        } else {
            renderObjectFrame(graphics, assets, slotState.objectMappingFrame(), slotWorldX, slotWorldY,
                    SAVE_SCREEN_OBJECT_BASE_DESC);
        }
    }

    private void renderSlotOverlay(GraphicsManager graphics,
                                   S3kDataSelectAssetSource assets,
                                   S3kSaveScreenLayoutObjects.SaveSlotObject slotObject,
                                   S3kSaveScreenObjectState.SlotVisualState slotState,
                                   int cameraX) {
        if (slotState.sub2MappingFrame() < 0) {
            return;
        }
        int slotWorldX = slotObject.worldX() - cameraX;
        int slotWorldY = slotObject.worldY();
        int sub2WorldY = slotState.sub2MappingFrame() == 0x1A ? slotWorldY - 8 : slotWorldY;
        renderObjectFrame(graphics, assets, slotState.sub2MappingFrame(), slotWorldX, sub2WorldY,
                SAVE_SCREEN_OBJECT_BASE_DESC);
    }

    private void renderSlotMetadata(GraphicsManager graphics,
                                    S3kSaveScreenObjectState.SlotVisualState slotState,
                                    int slotIndex,
                                    int cameraX,
                                    boolean highPriority) {
        int labelPrefixOffset = SAVE_SLOT_LABEL_PREFIX_OFFSET + (slotIndex * SAVE_SLOT_PLANE_STEP);
        int labelOffset = SAVE_SLOT_LABEL_OFFSET + (slotIndex * SAVE_SLOT_PLANE_STEP);
        switch (slotState.labelKind()) {
            case BLANK -> {
                renderPlaneOverlayTilemap(graphics, saveMiscLabelPrefixWord(), 1, 1,
                        labelPrefixOffset, cameraX, highPriority);
                renderPlaneOverlayTilemap(graphics, blankLabelWords(), 5, 1, labelOffset, cameraX, highPriority);
            }
            case CLEAR -> {
                renderPlaneOverlayTilemap(graphics, saveMiscLabelPrefixWord(), 1, 1,
                        labelPrefixOffset, cameraX, highPriority);
                renderPlaneOverlayTilemap(graphics, clearLabelWords(), 5, 1, labelOffset, cameraX, highPriority);
            }
            case ZONE -> {
                if (slotState.hostPreview() != null
                        && slotState.hostPreview().type() == HostSlotPreview.HostSlotPreviewType.NUMBERED_ZONE
                        && slotState.hostPreview().zoneDisplayNumber() != null) {
                    renderPlaneOverlayTilemap(graphics,
                            zoneLabelWords(slotState.hostPreview().zoneDisplayNumber()),
                            6, 1, labelOffset - 2, cameraX, highPriority);
                } else if (slotState.hostPreview() != null
                        && slotState.hostPreview().zoneLabelText() != null) {
                    renderPlaneOverlayTilemap(graphics,
                            hostZoneLabelWords(slotState.hostPreview().zoneLabelText()),
                            6, 1, labelOffset - 2, cameraX, highPriority);
                } else {
                    renderPlaneOverlayTilemap(graphics, zoneLabelWords(slotState.zoneDisplayNumber()),
                            6, 1, labelOffset - 2, cameraX, highPriority);
                }
            }
        }

        int livesOffset = SAVE_SLOT_LIVES_OFFSET + (slotIndex * SAVE_SLOT_PLANE_STEP);
        renderPlaneOverlayTilemap(graphics, livesContinueHeaderWords(slotState.headerStyleIndex()),
                3, 5, livesOffset, cameraX, highPriority);
        if (slotState.headerStyleIndex() == 0) {
            renderPlaneOverlayTilemap(graphics, blankStatWords(), 2, 5, livesOffset + 6, cameraX, highPriority);
        } else {
            renderPlaneOverlayTilemap(graphics, lifeContinueDigitsWords(slotState.lives()), 2, 2, livesOffset + 6,
                    cameraX, highPriority);
            renderPlaneOverlayTilemap(graphics, lifeContinueDigitsWords(slotState.continuesCount()), 2, 2,
                    livesOffset + 0x306, cameraX, highPriority);
        }
    }

    private void renderObjectFrame(GraphicsManager graphics,
                                   S3kDataSelectAssetSource assets,
                                   int frameIndex,
                                   int worldX,
                                   int worldY,
                                   int baseDescBits) {
        List<SpriteMappingFrame> mappings = assets.getSaveScreenMappings();
        if (frameIndex < 0 || frameIndex >= mappings.size()) {
            return;
        }
        renderMappingFrame(graphics, mappings.get(frameIndex), worldX - 128, worldY - 128,
                DATA_SELECT_PATTERN_BASE + Sonic3kConstants.ARTTILE_SAVE_MISC,
                baseDescBits);
    }

    private int[] selectSlotLayout(S3kDataSelectAssetSource assets,
                                   S3kSaveScreenObjectState.VisualState visualState,
                                   S3kSaveScreenObjectState.SlotVisualState slotState) {
        if (slotState == null || slotState.kind() == S3kSaveScreenObjectState.SlotVisualKind.EMPTY) {
            return assets.getNewLayoutWords();
        }
        int[][] staticLayouts = assets.getStaticLayouts();
        if (staticLayouts.length == 0) {
            return assets.getNewLayoutWords();
        }
        int frame = Math.max(0, Math.min(staticLayouts.length - 1, visualState.activeHeaderAnimationFrame()));
        return staticLayouts[frame];
    }

    private void renderScreenTilemap(GraphicsManager graphics, int[] words, int width, int height) {
        renderTilemap(graphics, words, width, height,
                SCREEN_SPACE_WORLD_ORIGIN, SCREEN_SPACE_WORLD_ORIGIN);
    }

    /**
     * Renders the menu-background tilemap tiled horizontally to fill {@code viewportWidth}.
     *
     * <p>At native width 320 (= {@code width} × 8) this draws exactly {@code width} columns
     * starting at X=0, identical to {@link #renderScreenTilemap} — byte-identical output.
     * At widescreen widths additional tile columns are appended by wrapping the source column
     * index modulo {@code width}, matching the MenuBackgroundRenderer.renderTiled pattern.
     */
    private void renderScreenTilemapTiled(GraphicsManager graphics, int[] words,
                                          int width, int height, int vpWidth) {
        if (words == null || words.length == 0) {
            return;
        }
        int originY = SCREEN_SPACE_WORLD_ORIGIN - 128;  // = 0
        int tileColumns = (vpWidth + 7) / 8;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < tileColumns; col++) {
                int srcCol = col % width;
                int index = row * width + srcCol;
                if (index >= words.length) {
                    continue;
                }
                int word = words[index];
                if (word == 0) {
                    continue;
                }
                reusableDesc.set(word);
                if (reusableDesc.getPatternIndex() == 0) {
                    continue;
                }
                graphics.renderPatternWithId(DATA_SELECT_PATTERN_BASE + reusableDesc.getPatternIndex(),
                        reusableDesc, col * 8, originY + row * 8);
            }
        }
    }

    private void renderPlaneOverlayTilemap(GraphicsManager graphics, int[] words, int width, int height,
                                           int planeByteOffset,
                                           int cameraX,
                                           boolean highPriority) {
        int tileIndex = planeByteOffset / 2;
        int tileX = tileIndex % PLANE_WIDTH_TILES;
        int tileY = tileIndex / PLANE_WIDTH_TILES;
        renderTilemap(graphics, words, width, height,
                SCREEN_SPACE_WORLD_ORIGIN - cameraX + (tileX * 8),
                SCREEN_SPACE_WORLD_ORIGIN + (tileY * 8),
                highPriority ? PriorityFilter.HIGH : PriorityFilter.LOW);
    }

    private void renderStaticPlaneTextOverlays(GraphicsManager graphics, int cameraX, boolean highPriority) {
        if (cachedNoTextWords == null) cachedNoTextWords = textWords("NO");
        if (cachedSaveTextWords == null) cachedSaveTextWords = textWords("SAVE");
        if (cachedDeleteTextWords == null) cachedDeleteTextWords = textWords("DELETE");
        renderPlaneOverlayTilemap(graphics, cachedNoTextWords, 2, 1, NO_SAVE_TEXT_OFFSET, cameraX, highPriority);
        renderPlaneOverlayTilemap(graphics, cachedSaveTextWords, 4, 1, SAVE_TEXT_OFFSET, cameraX, highPriority);
        renderPlaneOverlayTilemap(graphics, cachedDeleteTextWords, 6, 1, DELETE_TEXT_OFFSET, cameraX, highPriority);
    }

    private void renderLaunchErrorMessage(GraphicsManager graphics, String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        int[] words = launchErrorWords(message);
        int x = Math.max(0, (viewportWidth - words.length * Pattern.PATTERN_WIDTH) / 2);
        renderTilemap(graphics, words, words.length, 1,
                SCREEN_SPACE_WORLD_ORIGIN + x,
                SCREEN_SPACE_WORLD_ORIGIN + LAUNCH_ERROR_TEXT_Y);
    }

    private int[] launchErrorWords(String message) {
        String text = fitLaunchErrorText(message);
        if (!text.equals(cachedLaunchErrorText)) {
            cachedLaunchErrorText = text;
            cachedLaunchErrorWords = safeTextWords(text);
        }
        return cachedLaunchErrorWords;
    }

    private String fitLaunchErrorText(String message) {
        String text = message.trim();
        int maxTiles = Math.max(1, (viewportWidth / Pattern.PATTERN_WIDTH)
                - (LAUNCH_ERROR_HORIZONTAL_MARGIN_TILES * 2));
        return text.length() <= maxTiles ? text : text.substring(0, maxTiles);
    }

    private void renderPlaneABase(GraphicsManager graphics, int[] words, int cameraX, boolean highPriority) {
        renderTilemap(graphics, words, PLANE_WIDTH_TILES, PLANE_HEIGHT_TILES,
                SCREEN_SPACE_WORLD_ORIGIN - cameraX, SCREEN_SPACE_WORLD_ORIGIN,
                highPriority ? PriorityFilter.HIGH : PriorityFilter.LOW);
    }

    private int[] blankLabelWords() {
        if (cachedBlankLabelWords == null) {
            cachedBlankLabelWords = new int[]{blankPriorityWord(), blankPriorityWord(), blankPriorityWord(),
                    blankPriorityWord(), blankPriorityWord()};
        }
        return cachedBlankLabelWords;
    }

    private int[] clearLabelWords() {
        if (cachedClearLabelWords == null) {
            cachedClearLabelWords = textWords("CLEAR");
        }
        return cachedClearLabelWords;
    }

    private int[] zoneLabelWords(int zoneDisplayNumber) {
        return cachedZoneLabelWords.computeIfAbsent(zoneDisplayNumber, num -> {
            int tens = Math.max(0, Math.min(99, num)) / 10;
            int ones = Math.max(0, Math.min(99, num)) % 10;
            return new int[]{
                    saveTextWord('Z'),
                    saveTextWord('O'),
                    saveTextWord('N'),
                    saveTextWord('E'),
                    tens == 0 ? highPriorityWord() : saveTextDigitWord(tens),
                    saveTextDigitWord(ones)
            };
        });
    }

    /**
     * Renders a host zone label (e.g. "GHZ", "EHZ") left-justified in the
     * 6-tile label area, padded with blank high-priority tiles.
     */
    private int[] hostZoneLabelWords(String label) {
        int[] words = new int[6];
        int len = Math.min(label.length(), 6);
        for (int i = 0; i < len; i++) {
            words[i] = saveTextWord(label.charAt(i));
        }
        for (int i = len; i < 6; i++) {
            words[i] = blankPriorityWord();
        }
        return words;
    }

    private int[] livesContinueHeaderWords(int headerStyleIndex) {
        int idx = Math.max(0, Math.min(3, headerStyleIndex));
        if (cachedHeaderWords[idx] == null) {
            cachedHeaderWords[idx] = buildHeaderWords(headerStyleIndex);
        }
        return cachedHeaderWords[idx];
    }

    private int[] buildHeaderWords(int headerStyleIndex) {
        return switch (headerStyleIndex) {
            case 1 -> new int[]{
                    saveExtraWord(0x6E), saveExtraWord(0x70), saveExtraWord(0x5A),
                    saveExtraWord(0x6F), saveExtraWord(0x71), saveExtraWord(0x5B),
                    saveExtraWord(0x5C), saveExtraWord(0x5F), blankPriorityWord(),
                    saveExtraWord(0x5D), saveExtraWord(0x60), saveExtraWord(0x5A),
                    saveExtraWord(0x5E), saveExtraWord(0x61), saveExtraWord(0x5B)
            };
            case 2 -> new int[]{
                    saveExtraWord(0x72), saveExtraWord(0x74), saveExtraWord(0x5A),
                    saveExtraWord(0x73), saveExtraWord(0x75), saveExtraWord(0x5B),
                    saveExtraWord(0x62), saveExtraWord(0x65), blankPriorityWord(),
                    saveExtraWord(0x63), saveExtraWord(0x66), saveExtraWord(0x5A),
                    saveExtraWord(0x64), saveExtraWord(0x67), saveExtraWord(0x5B)
            };
            case 3 -> new int[]{
                    saveExtraWord(0x76), saveExtraWord(0x78), saveExtraWord(0x5A),
                    saveExtraWord(0x77), saveExtraWord(0x79), saveExtraWord(0x5B),
                    saveExtraWord(0x68), saveExtraWord(0x6B), blankPriorityWord(),
                    saveExtraWord(0x69), saveExtraWord(0x6C), saveExtraWord(0x5A),
                    saveExtraWord(0x6A), saveExtraWord(0x6D), saveExtraWord(0x5B)
            };
            default -> new int[15];
        };
    }

    private int[] blankStatWords() {
        if (cachedBlankStatWords == null) {
            cachedBlankStatWords = new int[]{
                    blankPriorityWord(), blankPriorityWord(),
                    blankPriorityWord(), blankPriorityWord(),
                    blankPriorityWord(), blankPriorityWord(),
                    blankPriorityWord(), blankPriorityWord(),
                    blankPriorityWord(), blankPriorityWord()
            };
        }
        return cachedBlankStatWords;
    }

    private int[] lifeContinueDigitsWords(int value) {
        return cachedDigitWords.computeIfAbsent(value, v -> {
            int clamped = Math.max(0, Math.min(99, v));
            int tens = clamped / 10;
            int ones = clamped % 10;
            int tensLeft = tens == 0 ? blankPriorityWord() : saveExtraWord(0x46 + (tens * 2));
            int tensRight = tens == 0 ? blankPriorityWord() : saveExtraWord(0x47 + (tens * 2));
            return new int[]{
                    tensLeft,
                    saveExtraWord(0x46 + (ones * 2)),
                    tensRight,
                    saveExtraWord(0x47 + (ones * 2))
            };
        });
    }

    private int saveTextWord(char c) {
        int encoded = S3kSaveTextCodec.encode(c);
        return encoded == 0 ? blankPriorityWord() : SAVE_TEXT_WORD_BASE + encoded;
    }

    private int[] textWords(String text) {
        int[] words = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            words[i] = saveTextWord(text.charAt(i));
        }
        return words;
    }

    private int[] safeTextWords(String text) {
        int[] words = new int[text.length()];
        for (int i = 0; i < text.length(); i++) {
            try {
                words[i] = saveTextWord(text.charAt(i));
            } catch (IllegalArgumentException ignored) {
                words[i] = blankPriorityWord();
            }
        }
        return words;
    }

    private int saveTextDigitWord(int digit) {
        return Sonic3kConstants.ARTTILE_SAVE_TEXT + digit + TILE_WORD_FLAGS;
    }

    private int saveExtraWord(int tileOffset) {
        return Sonic3kConstants.ARTTILE_SAVE_EXTRA + tileOffset + TILE_WORD_FLAGS;
    }

    private int blankPriorityWord() {
        return 0x8000;
    }

    private int highPriorityWord() {
        return 0x8000;
    }

    private int[] saveMiscLabelPrefixWord() {
        return new int[]{saveMiscWord(0x12)};
    }

    private int saveMiscWord(int tileOffset) {
        return Sonic3kConstants.ARTTILE_SAVE_MISC + tileOffset + highPriorityWord();
    }

    private void renderTilemap(GraphicsManager graphics, int[] words, int width, int height, int worldX, int worldY) {
        renderTilemap(graphics, words, width, height, worldX, worldY, PriorityFilter.ALL);
    }

    private void renderTilemap(GraphicsManager graphics, int[] words, int width, int height, int worldX, int worldY,
                               PriorityFilter priorityFilter) {
        if (words == null || words.length == 0) {
            return;
        }

        int originX = worldX - 128;
        int originY = worldY - 128;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int index = row * width + col;
                if (index >= words.length) {
                    return;
                }
                int word = words[index];
                if (word == 0) {
                    continue;
                }
                if (!priorityFilter.matches(word)) {
                    continue;
                }
                reusableDesc.set(word);
                if (reusableDesc.getPatternIndex() == 0) {
                    continue;
                }
                graphics.renderPatternWithId(DATA_SELECT_PATTERN_BASE + reusableDesc.getPatternIndex(),
                        reusableDesc, originX + col * 8, originY + row * 8);
            }
        }
    }

    private void renderMappingFrame(GraphicsManager graphics, SpriteMappingFrame frame, int worldX, int worldY,
                                    int patternBase,
                                    int baseDescBits) {
        if (frame == null || frame.pieces().isEmpty()) {
            return;
        }
        List<SpriteMappingPiece> pieces = frame.pieces();
        for (int i = pieces.size() - 1; i >= 0; i--) {
            renderMappingPiece(graphics, pieces.get(i), worldX, worldY, patternBase, baseDescBits);
        }
    }

    private void renderMappingPiece(GraphicsManager graphics, SpriteMappingPiece piece, int worldX, int worldY,
                                    int patternBase,
                                    int baseDescBits) {
        for (int tx = 0; tx < piece.widthTiles(); tx++) {
            for (int ty = 0; ty < piece.heightTiles(); ty++) {
                int tileOffset = tx * piece.heightTiles() + ty;
                int patternId = patternBase + piece.tileIndex() + tileOffset;
                int tileX = worldX + piece.xOffset() + (piece.hFlip() ? ((piece.widthTiles() - 1 - tx) * 8) : (tx * 8));
                int tileY = worldY + piece.yOffset() + (piece.vFlip() ? ((piece.heightTiles() - 1 - ty) * 8) : (ty * 8));
                int descBits = patternId & 0x7FF;
                if (piece.hFlip()) {
                    descBits |= 0x800;
                }
                if (piece.vFlip()) {
                    descBits |= 0x1000;
                }
                descBits |= (piece.paletteIndex() & 0x3) << 13;
                if (piece.priority()) {
                    descBits |= 0x8000;
                }
                descBits |= baseDescBits;
                graphics.renderPatternWithId(patternId, new PatternDesc(descBits), tileX, tileY);
            }
        }
    }

    private void cachePatterns(GraphicsManager graphics, Pattern[] patterns, int basePatternId) {
        for (int i = 0; i < patterns.length; i++) {
            graphics.cachePatternTexture(patterns[i], basePatternId + i);
        }
    }

    private void cachePalette(GraphicsManager graphics, byte[] segaBytes, int paletteLine) {
        cachePalette(graphics, segaBytes, paletteLine, 0);
    }

    private void cachePalette(GraphicsManager graphics, byte[] segaBytes, int paletteLine, int startColorIndex) {
        Palette palette = paletteFromBytes(segaBytes, startColorIndex);
        if (palette != null) {
            S3kFrontendPaletteUploader.cacheLine(graphics, palette, paletteLine);
        }
    }

    /**
     * Builds the save-card character palette stack.
     *
     * <p>Line 1 comes directly from the character palette. Line 2 starts from the
     * character palette's third line and then overlays emerald colors beginning at
     * color 1. Color 0 is intentionally preserved so donated emerald colors cannot
     * clobber the character backdrop/shadow slot that the S3K save-card art relies on.</p>
     */
    private Palette cachedCharLine1;
    private Palette cachedCharLine2;
    private boolean characterPalettesBuilt;

    private void cacheCharacterAndEmeraldPalettes(GraphicsManager graphics,
                                                  byte[] characterPaletteBytes,
                                                  byte[] emeraldPaletteBytes) {
        // Build palette objects once, but re-upload to GPU each frame since other
        // layers (selected slot icon) may overwrite these palette lines.
        if (!characterPalettesBuilt) {
            characterPalettesBuilt = true;
            if (characterPaletteBytes == null || characterPaletteBytes.length == 0) {
                cachedCharLine1 = null;
                cachedCharLine2 = paletteFromBytes(emeraldPaletteBytes, 1);
            } else {
                cachedCharLine1 = paletteFromBytes(slicePaletteLine(characterPaletteBytes, 0), 0);
                cachedCharLine2 = paletteFromBytes(
                        slicePaletteLine(characterPaletteBytes, Palette.PALETTE_SIZE * 2), 0);
                if (cachedCharLine2 == null) {
                    cachedCharLine2 = new Palette();
                }
                overlayPaletteBytes(cachedCharLine2, emeraldPaletteBytes, 1);
            }
        }
        // Re-upload cached palettes (cheap GPU call, no object allocation)
        if (cachedCharLine1 != null) {
            S3kFrontendPaletteUploader.cacheLine(graphics, cachedCharLine1, 1);
        }
        if (cachedCharLine2 != null) {
            S3kFrontendPaletteUploader.cacheLine(graphics, cachedCharLine2, 2);
        }
    }

    private byte[] slicePaletteLine(byte[] segaBytes, int startByte) {
        if (segaBytes == null || startByte >= segaBytes.length) {
            return new byte[0];
        }
        int end = Math.min(segaBytes.length, startByte + (Palette.PALETTE_SIZE * 2));
        return Arrays.copyOfRange(segaBytes, startByte, end);
    }

    /**
     * Overlays a Sega-format palette fragment into an existing palette.
     *
     * <p>The caller chooses the destination start color. For donated save-card emeralds
     * this is always color 1, which is why host emerald colors must be pre-adapted to the
     * S3K save-card slot ordering before they reach this method.</p>
     */
    private void overlayPaletteBytes(Palette palette, byte[] segaBytes, int startColorIndex) {
        if (palette == null || segaBytes == null || segaBytes.length == 0) {
            return;
        }
        for (int i = 0; i + 1 < segaBytes.length && (startColorIndex + (i / 2)) < Palette.PALETTE_SIZE; i += 2) {
            palette.getColor(startColorIndex + (i / 2)).fromSegaFormat(segaBytes, i);
        }
    }

    private Palette paletteFromBytes(byte[] segaBytes, int startColorIndex) {
        if (segaBytes == null || segaBytes.length == 0) {
            return null;
        }
        Palette palette = new Palette();
        if (startColorIndex <= 0) {
            palette.fromSegaFormat(segaBytes);
            return palette;
        }
        for (int i = 0; i + 1 < segaBytes.length && (startColorIndex + (i / 2)) < Palette.PALETTE_SIZE; i += 2) {
            palette.getColor(startColorIndex + (i / 2)).fromSegaFormat(segaBytes, i);
        }
        return palette;
    }

    private byte[] paletteAt(byte[][] palettes, int index) {
        if (palettes == null || palettes.length == 0) {
            return new byte[0];
        }
        int clamped = Math.max(0, Math.min(palettes.length - 1, index));
        return palettes[clamped];
    }

    private enum PriorityFilter {
        ALL,
        LOW,
        HIGH;

        private boolean matches(int word) {
            boolean highPriority = (word & 0x8000) != 0;
            return switch (this) {
                case ALL -> true;
                case LOW -> !highPriority;
                case HIGH -> highPriority;
            };
        }
    }
}
