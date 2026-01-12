package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.TAILS_PATTERN_OFFSET;

/**
 * Handles rendering for Sonic 2 Special Stage.
 *
 * Responsible for:
 * - Background (Plane B) rendering with skydome scroll effect
 * - Track (Plane A) rendering using decoded mapping frames
 *
 * H32 mode: 256 pixels wide × 224 pixels tall (32×28 tiles)
 */
public class Sonic2SpecialStageRenderer {
    public static final int H32_TILES_X = 32;
    public static final int H32_TILES_Y = 28;
    public static final int TILE_SIZE = 8;
    public static final int H32_HEIGHT = H32_TILES_Y * TILE_SIZE;

    private final GraphicsManager graphicsManager;

    private int backgroundPatternBase;
    private int trackPatternBase;
    private int playerPatternBase;
    private int hudPatternBase;
    private int startPatternBase;
    private int messagesPatternBase;

    private List<Sonic2SpecialStagePlayer> players = new ArrayList<>();
    private Sonic2SpecialStageIntro intro;

    /**
     * START banner sprite-piece definition (Obj5F frame0 from obj5F_a.asm).
     * spritePiece format: xOffset, yOffset, widthTiles, heightTiles, tileIndexOffset, hFlip, vFlip
     */
    private static final class SpritePiece {
        final int xOffset;
        final int yOffset;
        final int widthTiles;
        final int heightTiles;
        final int tileIndexOffset;
        final boolean hFlip;
        final boolean vFlip;

        SpritePiece(int xOffset, int yOffset, int widthTiles, int heightTiles,
                    int tileIndexOffset, boolean hFlip, boolean vFlip) {
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.widthTiles = widthTiles;
            this.heightTiles = heightTiles;
            this.tileIndexOffset = tileIndexOffset;
            this.hFlip = hFlip;
            this.vFlip = vFlip;
        }
    }

    /**
     * Obj5F frame0 mapping (START banner fully visible).
     * From obj5F_a.asm:
     * spritePiece -$48, 0, 4, 4, 0,   0, 0, 1, 1   ; left checkered
     * spritePiece -$28, 0, 2, 4, $10, 0, 0, 1, 1   ; S
     * spritePiece -$18, 0, 2, 4, $18, 0, 0, 1, 1   ; T
     * spritePiece -8,   0, 2, 4, $20, 0, 0, 1, 1   ; A
     * spritePiece 8,    0, 2, 4, $28, 0, 0, 1, 1   ; R
     * spritePiece $18,  0, 2, 4, $18, 0, 0, 1, 1   ; T (reused)
     * spritePiece $28,  0, 4, 4, 0,   1, 0, 1, 1   ; right checkered (h-flipped)
     */
    private static final SpritePiece[] START_BANNER_FRAME0 = {
        new SpritePiece(-0x48, 0, 4, 4, 0x00, false, false), // left checkered
        new SpritePiece(-0x28, 0, 2, 4, 0x10, false, false), // S
        new SpritePiece(-0x18, 0, 2, 4, 0x18, false, false), // T
        new SpritePiece(-0x08, 0, 2, 4, 0x20, false, false), // A
        new SpritePiece( 0x08, 0, 2, 4, 0x28, false, false), // R
        new SpritePiece( 0x18, 0, 2, 4, 0x18, false, false), // T (reused)
        new SpritePiece( 0x28, 0, 4, 4, 0x00, true,  false), // right checkered, h-flipped
    };

    public Sonic2SpecialStageRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = graphicsManager;
    }

    public void setPatternBases(int backgroundBase, int trackBase) {
        this.backgroundPatternBase = backgroundBase;
        this.trackPatternBase = trackBase;
        this.playerPatternBase = trackBase + 256;
    }

    public void setPlayerPatternBase(int playerBase) {
        this.playerPatternBase = playerBase;
    }

    public void setPlayers(List<Sonic2SpecialStagePlayer> players) {
        this.players = players != null ? players : new ArrayList<>();
    }

    public void setIntro(Sonic2SpecialStageIntro intro) {
        this.intro = intro;
    }

    public void setIntroPatternBases(int hudBase, int startBase, int messagesBase) {
        this.hudPatternBase = hudBase;
        this.startPatternBase = startBase;
        this.messagesPatternBase = messagesBase;
    }

    /**
     * Renders the background plane (Plane B).
     *
     * The background mapping buffer is 32 tiles wide × 32 tiles tall.
     * - Rows 0-15: Lower/upper sky portion (from MapEng_SpecialBackBottom)
     * - Rows 16-31: Main background portion (from MapEng_SpecialBack)
     *
     * Only rows that fit on screen (28 rows in H32 mode) are rendered.
     *
     * @param mappings Enigma-decoded mapping data (16-bit words)
     * @param scrollX  Horizontal scroll offset
     * @param scrollY  Vertical scroll offset
     */
    public void renderBackground(byte[] mappings, int scrollX, int scrollY) {
        if (mappings == null || mappings.length < 2) {
            renderPlaceholderBackground();
            return;
        }

        graphicsManager.beginPatternBatch();

        // Screen parameters for H32 mode emulation
        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2; // Center 256px image on 320px screen

        int wordCount = mappings.length / 2;
        int mapWidth = H32_TILES_X; // 32 tiles wide
        int mapTotalHeight = 32; // Background is 32 tiles tall
        int mapHeight = Math.min(wordCount / mapWidth, mapTotalHeight);

        // Only render the visible portion (28 rows fit on screen)
        int visibleRows = Math.min(mapHeight, H32_TILES_Y);

        for (int ty = 0; ty < visibleRows; ty++) {
            for (int tx = 0; tx < mapWidth; tx++) {
                int wordIndex = ty * mapWidth + tx;
                if (wordIndex * 2 + 1 >= mappings.length)
                    continue;

                int word = ((mappings[wordIndex * 2] & 0xFF) << 8) |
                        (mappings[wordIndex * 2 + 1] & 0xFF);

                // Skip empty tiles
                if (word == 0)
                    continue;

                PatternDesc desc = new PatternDesc(word);

                // Calculate screen position with H32 offset centered
                // Background wraps horizontally every 256 pixels
                int screenX = SCREEN_CENTER_OFFSET + ((tx * TILE_SIZE - scrollX) & 0xFF);
                int screenY = ((H32_TILES_Y - 1 - ty) * TILE_SIZE + scrollY);

                int patternId = desc.getPatternIndex() + backgroundPatternBase;
                graphicsManager.renderPatternWithId(patternId, desc, screenX, screenY);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a placeholder background for testing.
     */
    public void renderPlaceholderBackground() {
        graphicsManager.beginPatternBatch();

        for (int ty = 0; ty < H32_TILES_Y; ty++) {
            for (int tx = 0; tx < H32_TILES_X; tx++) {
                int patternId = backgroundPatternBase + ((tx + ty * 4) % 64);

                PatternDesc desc = new PatternDesc();
                desc.setPriority(false);
                desc.setPaletteIndex(0);
                desc.setHFlip(false);
                desc.setVFlip(false);
                desc.setPatternIndex(patternId & 0x7FF);

                int screenX = tx * TILE_SIZE;
                int screenY = (H32_TILES_Y - 1 - ty) * TILE_SIZE;

                graphicsManager.renderPatternWithId(patternId, desc, screenX, screenY);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    // Set to true to debug with full tiles instead of strip rendering
    private static final boolean DEBUG_FULL_TILE_MODE = false;

    // Which strip's tiles to render in debug mode (0-3, or -1 for all)
    // 0 = columns 0-31, 1 = columns 96-127, 2 = columns 64-95, 3 = columns 32-63
    private static final int DEBUG_STRIP_TO_RENDER = -1;

    // Set to true to force all strips to use strip index 0 (tests if texture coord issue)
    private static final boolean DEBUG_FORCE_STRIP_ZERO = false;

    // Set to true to render strips as full 8-pixel height instead of 2-pixel
    // This tests if the issue is with the strip height vs the data selection
    private static final boolean DEBUG_STRIP_AS_FULL_HEIGHT = false;

    // X offset (in pixels) applied to each strip to compensate for built-in offsets in track data
    // The Genesis H-scroll cancels these offsets; we simulate by shifting X position
    // Positive = shift right, Negative = shift left
    // Strip 1 appears ~4-8 tiles left, so try compensating with increasing right shifts
    private static final int[] DEBUG_STRIP_X_OFFSETS = {0, 0, 0, 0};

    // Debug: force all strips to use data from a specific strip's columns
    // -1 = normal (each strip uses its own columns), 0-3 = use that strip's columns for all
    private static final int DEBUG_FORCE_DATA_FROM_STRIP = -1; // Normal mode - each strip uses its own data

    /**
     * Renders the track plane (Plane A) using 2-scanline strip rendering.
     *
     * The Sonic 2 special stage uses per-scanline horizontal scroll to create
     * a pseudo-3D halfpipe effect:
     * - Each 8-scanline tile row is divided into 4 strips of 2 scanlines each
     * - Each strip shows a different 32-column band of the 128-wide VDP plane
     * - This creates the "folded" perspective where the track converges to a point
     *
     * The VDP plane is 128 cells wide. The Genesis uses H-scroll to show different
     * 32-column slices at different scanlines within each tile row:
     * - Scanlines 0-1: VDP columns 0-31 (strip 0)
     * - Scanlines 2-3: VDP columns 32-63 (strip 1)
     * - Scanlines 4-5: VDP columns 64-95 (strip 2)
     * - Scanlines 6-7: VDP columns 96-127 (strip 3)
     *
     * @param trackFrameIndex Current track frame index (0-55)
     * @param frameTiles      Decoded tile data from track frame (or null for
     *                        placeholder)
     */
    public void renderTrack(int trackFrameIndex, int[] frameTiles) {
        if (frameTiles == null || frameTiles.length == 0) {
            renderPlaceholderTrack(trackFrameIndex);
            return;
        }

        if (DEBUG_FULL_TILE_MODE) {
            renderTrackDebugFullTiles(frameTiles);
            return;
        }

        graphicsManager.beginPatternBatch();

        // Screen parameters for H32 mode emulation
        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2; // Center 256px image on 320px screen

        // Track Data Structure:
        // Decoded frameTiles represents the VRAM Plane A (28 rows * 128 cells).
        // Each VRAM row contains 4 strips of 32 cells (Strip 0, 1, 2, 3).
        // The game displays these using H-Scroll interleaving where each pair of
        // scanlines shows a different 32-column slice of the 128-wide plane.

        final int NUM_ROWS = 28;
        final int CELLS_PER_ROW = 128; // The full plane width
        final int CELLS_PER_STRIP = 32;

        for (int row = 0; row < NUM_ROWS; row++) {
            int baseY = row * 8; // Screen Y for this tile row

            // Render each of the 4 strips (2 scanlines each)
            for (int stripNum = 0; stripNum < 4; stripNum++) {
                // Debug: optionally render only one strip
                if (DEBUG_STRIP_TO_RENDER >= 0 && stripNum != DEBUG_STRIP_TO_RENDER) {
                    continue;
                }

                // Which 2-scanline strip within this tile row (0-3)
                int screenY = baseY + stripNum * 2;

                // Which 32-column slice of the 128-wide plane to show
                // Strip 0: columns 0-31, Strip 1: columns 32-63, etc.
                int rowDataStart = row * CELLS_PER_ROW;
                int dataStripNum = (DEBUG_FORCE_DATA_FROM_STRIP >= 0) ? DEBUG_FORCE_DATA_FROM_STRIP : stripNum;
                int stripDataStart = rowDataStart + dataStripNum * CELLS_PER_STRIP;

                for (int col = 0; col < CELLS_PER_STRIP; col++) {
                    int tileIndex = stripDataStart + col;

                    if (tileIndex >= frameTiles.length)
                        break;

                    int word = frameTiles[tileIndex];

                    // Skip empty tiles (check pattern index)
                    if ((word & 0x7FF) == 0) {
                        continue;
                    }

                    PatternDesc desc = new PatternDesc(word);
                    int patternId = desc.getPatternIndex() + trackPatternBase;

                    // Calculate X position within the 256-pixel H32 viewport, with wrapping
                    // The offset simulates H-scroll which wraps within the viewport
                    int viewportX = col * TILE_SIZE + DEBUG_STRIP_X_OFFSETS[stripNum];
                    // Wrap within the 256-pixel viewport (H32 width)
                    viewportX = ((viewportX % H32_WIDTH) + H32_WIDTH) % H32_WIDTH;
                    // Convert to screen position (add letterbox offset)
                    int drawX = SCREEN_CENTER_OFFSET + viewportX;

                    if (DEBUG_STRIP_AS_FULL_HEIGHT) {
                        // Render as full 8x8 tile at the strip's Y position
                        // This tests if the issue is with strip height or data selection
                        graphicsManager.renderPatternWithId(patternId, desc, drawX, screenY);
                    } else {
                        // Use strip rendering - only show 2 scanlines of the 8x8 tile
                        // stripNum tells us which 2-scanline portion of the source tile to show
                        // Since track tiles are 1-line-per-tile (all rows identical), the strip index
                        // shouldn't matter visually - but we use it for correct texture sampling
                        int actualStripIndex = DEBUG_FORCE_STRIP_ZERO ? 0 : stripNum;
                        graphicsManager.renderStripPatternWithId(patternId, desc, drawX, screenY, actualStripIndex);
                    }
                }
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Debug mode: Renders track using full 8x8 tiles instead of strips.
     * Mode 1: Only strip 0's tiles (32 columns)
     * Mode 2: All strips' tiles stacked (shows full 128 columns as 4 vertical sections)
     */
    private void renderTrackDebugFullTiles(int[] frameTiles) {
        graphicsManager.beginPatternBatch();

        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;
        final int NUM_ROWS = 28;
        final int CELLS_PER_ROW = 128;
        final int CELLS_PER_STRIP = 32;

        // Render all 4 strips worth of tiles, stacked vertically
        // Each strip becomes a separate 8-pixel-tall band
        // This shows the full decoded data in a blocky but visible way
        for (int row = 0; row < NUM_ROWS; row++) {
            int rowDataStart = row * CELLS_PER_ROW;

            for (int stripNum = 0; stripNum < 4; stripNum++) {
                // Each strip rendered as full tiles, stacked at different Y positions
                // Strip 0 at Y offset 0, Strip 1 at Y offset 2, etc (within each row's 8px)
                int screenY = row * 8 + stripNum * 2;
                int stripDataStart = rowDataStart + stripNum * CELLS_PER_STRIP;

                for (int col = 0; col < CELLS_PER_STRIP; col++) {
                    int tileIndex = stripDataStart + col;
                    if (tileIndex >= frameTiles.length) break;

                    int word = frameTiles[tileIndex];
                    if ((word & 0x7FF) == 0) continue;

                    PatternDesc desc = new PatternDesc(word);
                    int patternId = desc.getPatternIndex() + trackPatternBase;
                    int drawX = SCREEN_CENTER_OFFSET + col * TILE_SIZE;

                    // Use full tile rendering (8x8) - this will overlap/stack
                    graphicsManager.renderPatternWithId(patternId, desc, drawX, screenY);
                }
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a placeholder track for testing.
     */
    public void renderPlaceholderTrack(int trackFrameIndex) {
        graphicsManager.beginPatternBatch();

        int trackStartX = 4;
        int trackEndX = 28;
        int trackStartY = 4;
        int trackEndY = 24;

        for (int ty = trackStartY; ty < trackEndY; ty++) {
            for (int tx = trackStartX; tx < trackEndX; tx++) {
                int patternId = trackPatternBase + ((tx + ty * 3 + trackFrameIndex) % 64);

                PatternDesc desc = new PatternDesc();
                desc.setPriority(true);
                desc.setPaletteIndex(1);
                desc.setHFlip(false);
                desc.setVFlip(false);
                desc.setPatternIndex(patternId & 0x7FF);

                int screenX = tx * TILE_SIZE;
                int screenY = (H32_TILES_Y - 1 - ty) * TILE_SIZE;

                graphicsManager.renderPatternWithId(patternId, desc, screenX, screenY);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Applies per-scanline horizontal scroll for skydome effect.
     * For Phase 1, this is a placeholder. Full implementation requires
     * custom shader support or scanline-based rendering.
     *
     * @param scrollTable  The skydome scroll delta table
     * @param frameCounter Current frame number for animation
     */
    public void applySkydomeScroll(byte[] scrollTable, int frameCounter) {
    }

    /**
     * Renders all player sprites.
     * Players are sorted by priority (higher priority = drawn later = on top).
     */
    public void renderPlayers() {
        if (players.isEmpty()) {
            return;
        }

        List<Sonic2SpecialStagePlayer> sortedPlayers = new ArrayList<>(players);
        sortedPlayers.sort(Comparator.comparingInt(Sonic2SpecialStagePlayer::getPriority));

        graphicsManager.beginPatternBatch();

        for (Sonic2SpecialStagePlayer player : sortedPlayers) {
            if (player.isInvulnerable() && (System.currentTimeMillis() & 0x80) != 0) {
                continue;
            }

            renderPlayer(player);
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a single player sprite.
     *
     * @param player The player to render
     */
    private void renderPlayer(Sonic2SpecialStagePlayer player) {
        int screenX = player.getXPos();
        int screenY = H32_HEIGHT - player.getYPos();

        int basePattern = playerPatternBase;
        if (player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.TAILS) {
            basePattern += TAILS_PATTERN_OFFSET;
        }

        int spriteWidth = 3;
        int spriteHeight = 4;

        int animOffset = player.getAnim() * 16;

        boolean xFlip = player.isRenderXFlip();
        boolean yFlip = player.isRenderYFlip();

        int centerOffsetX = (spriteWidth * TILE_SIZE) / 2;
        int centerOffsetY = (spriteHeight * TILE_SIZE) / 2;

        for (int ty = 0; ty < spriteHeight; ty++) {
            for (int tx = 0; tx < spriteWidth; tx++) {
                int tileIndex = ty * spriteWidth + tx + animOffset;

                int drawTx = xFlip ? (spriteWidth - 1 - tx) : tx;
                int drawTy = yFlip ? (spriteHeight - 1 - ty) : ty;

                int patternId = basePattern + tileIndex;

                PatternDesc desc = new PatternDesc();
                desc.setPriority(true);
                desc.setPaletteIndex(player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.SONIC ? 1 : 2);
                desc.setHFlip(xFlip);
                desc.setVFlip(yFlip);
                desc.setPatternIndex(patternId & 0x7FF);

                int tileScreenX = screenX - centerOffsetX + drawTx * TILE_SIZE;
                int tileScreenY = screenY - centerOffsetY + drawTy * TILE_SIZE;

                graphicsManager.renderPatternWithId(patternId, desc, tileScreenX, tileScreenY);
            }
        }
    }

    /**
     * Renders placeholder player sprites using patterns.
     * Uses distinctive colored patterns to represent each player.
     */
    public void renderPlaceholderPlayers() {
        if (players.isEmpty()) {
            return;
        }

        List<Sonic2SpecialStagePlayer> sortedPlayers = new ArrayList<>(players);
        sortedPlayers.sort(Comparator.comparingInt(Sonic2SpecialStagePlayer::getPriority));

        graphicsManager.beginPatternBatch();

        for (Sonic2SpecialStagePlayer player : sortedPlayers) {
            if (player.isInvulnerable() && (System.currentTimeMillis() & 0x80) != 0) {
                continue;
            }

            int screenX = player.getXPos();
            int screenY = H32_HEIGHT - player.getYPos();

            int tilesWide = player.isJumping() ? 2 : 2;
            int tilesHigh = player.isJumping() ? 2 : 3;

            int paletteIndex = player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.SONIC ? 1 : 2;
            if (player.isHurt()) {
                paletteIndex = 3;
            }

            int centerOffsetX = (tilesWide * TILE_SIZE) / 2;
            int centerOffsetY = (tilesHigh * TILE_SIZE) / 2;

            int basePatternOffset = player.getAnim() * 8;

            for (int ty = 0; ty < tilesHigh; ty++) {
                for (int tx = 0; tx < tilesWide; tx++) {
                    int patternId = playerPatternBase + basePatternOffset + ty * tilesWide + tx;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(true);
                    desc.setPaletteIndex(paletteIndex);
                    desc.setHFlip(player.isRenderXFlip());
                    desc.setVFlip(player.isRenderYFlip());
                    desc.setPatternIndex(patternId & 0x7FF);

                    int drawTx = player.isRenderXFlip() ? (tilesWide - 1 - tx) : tx;
                    int drawTy = player.isRenderYFlip() ? (tilesHigh - 1 - ty) : ty;

                    int tileScreenX = screenX - centerOffsetX + drawTx * TILE_SIZE;
                    int tileScreenY = screenY - centerOffsetY + drawTy * TILE_SIZE;

                    graphicsManager.renderPatternWithId(patternId, desc, tileScreenX, tileScreenY);
                }
            }
        }

        graphicsManager.flushPatternBatch();
    }

    // ========== Intro UI Rendering ==========

    /**
     * Renders the intro sequence UI elements (START banner and ring requirement message).
     */
    public void renderIntroUI() {
        if (intro == null) {
            return;
        }

        graphicsManager.beginPatternBatch();

        // Screen parameters for H32 mode
        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;

        // Render START banner if visible
        if (intro.isBannerVisible()) {
            renderStartBanner(SCREEN_CENTER_OFFSET);
        }

        // Render ring requirement message if visible
        if (intro.isMessageVisible()) {
            renderRingMessage(SCREEN_CENTER_OFFSET);
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders the START banner sprite using the real Obj5F frame0 sprite pieces.
     *
     * Mega Drive coordinate system: Y=0 is at the TOP of the screen.
     * intro.getBannerX()/getBannerY() are in that same space.
     */
    private void renderStartBanner(int screenCenterOffset) {
        int bannerX = intro.getBannerX();
        int bannerY = intro.getBannerY();

        // Convert from H32 coordinates to screen coordinates:
        // H32: X=0 is left, Y=0 is top. Our renderer also uses Y=0 at top.
        int baseX = screenCenterOffset + bannerX;
        int baseY = bannerY;

        // All pieces use palette 1 and priority 1 in the original mappings.
        final int paletteIndex = 1;

        for (SpritePiece piece : START_BANNER_FRAME0) {
            int pieceX = baseX + piece.xOffset;
            int pieceY = baseY + piece.yOffset;

            // Each spritePiece is a widthTiles x heightTiles block starting at tileIndexOffset.
            // VDP uses COLUMN-MAJOR ordering: tiles are stored column by column.
            // For a WxH piece, tile at (tx, ty) is at offset: tx * heightTiles + ty
            for (int ty = 0; ty < piece.heightTiles; ty++) {
                for (int tx = 0; tx < piece.widthTiles; tx++) {
                    // Calculate tile offset within piece using column-major order
                    int localTx = piece.hFlip ? (piece.widthTiles - 1 - tx) : tx;
                    int localTy = piece.vFlip ? (piece.heightTiles - 1 - ty) : ty;
                    int patternOffset = piece.tileIndexOffset + localTx * piece.heightTiles + localTy;
                    int patternId = startPatternBase + patternOffset;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(true);
                    desc.setPaletteIndex(paletteIndex);
                    desc.setHFlip(piece.hFlip);
                    desc.setVFlip(piece.vFlip);
                    desc.setPatternIndex(patternId & 0x7FF);

                    int tileScreenX = pieceX + tx * TILE_SIZE;
                    int tileScreenY = pieceY + ty * TILE_SIZE;

                    graphicsManager.renderPatternWithId(patternId, desc, tileScreenX, tileScreenY);
                }
            }
        }
    }

    /**
     * Tile offsets in SpecialMessages art for letters.
     * From obj5A.asm Map_obj5A mappings - each is a 1x2 tile piece (8x16 pixels).
     * These are stored interleaved: top tile then bottom tile.
     */
    private static final int TILE_G = 0x04;
    private static final int TILE_E = 0x02;
    private static final int TILE_T = 0x14;
    private static final int TILE_R = 0x10;  // Actually maps to 'N' in obj5A, R needs verification
    private static final int TILE_I = 0x08;
    private static final int TILE_N = 0x10;
    private static final int TILE_S = 0x0C;

    /**
     * Renders the "GET XX RINGS" message.
     * Uses HUD patterns for digits (interleaved format like HudRenderManager).
     * Uses SpecialMessages art for text letters.
     *
     * Mega Drive coordinate system: Y=0 is at the TOP of the screen.
     * Original position from disasm: x=$54 for GET, digits centered, x=$84 for RINGS
     */
    private void renderRingMessage(int screenCenterOffset) {
        int ringReq = intro.getRingRequirement();

        // Original positions from Obj5A_CreateRingReqMessage:
        // GET at x=$54, y=$6C; digits at x=$80 (centered); RINGS! at x=$84, y=$6C
        final int baseY = 0x6C;
        final int textPalette = 1;   // Palette 1 for message text
        final int digitPalette = 2;  // Palette 2 for HUD digits (matching Obj87)

        // Render "GET" using Messages art at x=$54
        int getX = screenCenterOffset + 0x54;
        renderMessageLetter(getX, baseY, TILE_G, textPalette);
        renderMessageLetter(getX + 8, baseY, TILE_E, textPalette);
        renderMessageLetter(getX + 16, baseY, TILE_T, textPalette);

        // Render digits using HUD art (interleaved format like HudRenderManager)
        // Position depends on digit count
        int digitX;
        if (ringReq >= 100) {
            digitX = screenCenterOffset + 0x80 - 12;
        } else if (ringReq >= 10) {
            digitX = screenCenterOffset + 0x80 - 8;
        } else {
            digitX = screenCenterOffset + 0x80 - 4;
        }

        // Hundreds digit
        if (ringReq >= 100) {
            int hundreds = (ringReq / 100) % 10;
            renderHudDigit(digitX, baseY, hundreds, digitPalette);
            digitX += 8;
        }

        // Tens digit
        if (ringReq >= 10) {
            int tens = (ringReq / 10) % 10;
            renderHudDigit(digitX, baseY, tens, digitPalette);
            digitX += 8;
        }

        // Ones digit
        int ones = ringReq % 10;
        renderHudDigit(digitX, baseY, ones, digitPalette);

        // Render "RINGS!" at appropriate position
        int ringsX = screenCenterOffset + (ringReq >= 100 ? 0x90 : 0x88);
        renderMessageLetter(ringsX, baseY, TILE_R, textPalette);
        renderMessageLetter(ringsX + 8, baseY, TILE_I, textPalette);
        renderMessageLetter(ringsX + 16, baseY, TILE_N, textPalette);
        renderMessageLetter(ringsX + 24, baseY, TILE_G, textPalette);
        renderMessageLetter(ringsX + 32, baseY, TILE_S, textPalette);
    }

    /**
     * Renders a single 1x2 tile letter from the SpecialMessages art.
     * Each letter is 8 pixels wide × 16 pixels tall (2 tiles stacked vertically).
     */
    private void renderMessageLetter(int x, int y, int tileOffset, int paletteIndex) {
        for (int ty = 0; ty < 2; ty++) {
            int patternId = messagesPatternBase + tileOffset + ty;

            PatternDesc desc = new PatternDesc();
            desc.setPriority(true);
            desc.setPaletteIndex(paletteIndex);
            desc.setHFlip(false);
            desc.setVFlip(false);
            desc.setPatternIndex(patternId & 0x7FF);

            // Letters are centered: offset by half the height (-8 pixels)
            int drawY = y - 8 + ty * TILE_SIZE;

            graphicsManager.renderPatternWithId(patternId, desc, x, drawY);
        }
    }

    /**
     * Renders a digit using HUD art patterns in interleaved format.
     * Matches HudRenderManager's approach: digit N uses patterns (N*2) and (N*2+1).
     */
    private void renderHudDigit(int x, int y, int digit, int paletteIndex) {
        // Interleaved layout: top tile = digit*2, bottom tile = digit*2+1
        int topPatternId = hudPatternBase + (digit * 2);
        int bottomPatternId = hudPatternBase + (digit * 2) + 1;

        PatternDesc desc = new PatternDesc();
        desc.setPriority(true);
        desc.setPaletteIndex(paletteIndex);
        desc.setHFlip(false);
        desc.setVFlip(false);

        // Top tile
        desc.setPatternIndex(topPatternId & 0x7FF);
        graphicsManager.renderPatternWithId(topPatternId, desc, x, y - 8);

        // Bottom tile
        desc.setPatternIndex(bottomPatternId & 0x7FF);
        graphicsManager.renderPatternWithId(bottomPatternId, desc, x, y);
    }
}
