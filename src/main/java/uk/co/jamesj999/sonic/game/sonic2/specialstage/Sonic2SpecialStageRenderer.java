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

    // Object rendering (Phase 4)
    private int ringPatternBase;
    private int bombPatternBase;
    private int starsPatternBase;      // For ring sparkle animation (uses separate art)
    private int explosionPatternBase;  // For bomb explosion animation (uses separate art)
    private int emeraldPatternBase;    // For chaos emerald
    private Sonic2SpecialStageObjectManager objectManager;
    private Sonic2PerspectiveData perspectiveData;

    // Checkpoint system
    private Sonic2SpecialStageCheckpoint checkpoint;

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

    public void setObjectPatternBases(int ringBase, int bombBase) {
        this.ringPatternBase = ringBase;
        this.bombPatternBase = bombBase;
    }

    public void setEffectPatternBases(int starsBase, int explosionBase) {
        this.starsPatternBase = starsBase;
        this.explosionPatternBase = explosionBase;
    }

    public void setEmeraldPatternBase(int emeraldBase) {
        this.emeraldPatternBase = emeraldBase;
    }

    public void setObjectManager(Sonic2SpecialStageObjectManager objectManager) {
        this.objectManager = objectManager;
    }

    public void setPerspectiveData(Sonic2PerspectiveData perspectiveData) {
        this.perspectiveData = perspectiveData;
    }

    public void setCheckpoint(Sonic2SpecialStageCheckpoint checkpoint) {
        this.checkpoint = checkpoint;
    }

    /**
     * Renders the background plane (Plane B).
     *
     * The background mapping buffer is 32 tiles wide × 32 tiles tall.
     * From disassembly SSPlaneB_Background (s2.asm line 9155):
     * - Rows 0-15: MapEng_SpecialBackBottom (top of screen)
     * - Rows 16-31: MapEng_SpecialBack (bottom of screen)
     *
     * The plane is 32 rows tall (256px). We render all rows and clip to the
     * 28-row visible window to preserve wrap behavior during vertical scroll.
     * H32 mode uses a 256-pixel wide viewport centered on the 320-pixel screen.
     * Tiles are clipped to prevent rendering outside the H32 viewport.
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

        // Screen parameters for H32 mode emulation
        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2; // Center 256px viewport on 320px screen

        graphicsManager.beginPatternBatch();

        int wordCount = mappings.length / 2;
        int mapWidth = H32_TILES_X; // 32 tiles wide
        int mapTotalHeight = 32; // Background is 32 tiles tall
        int mapHeight = Math.min(wordCount / mapWidth, mapTotalHeight);
        int planeHeightPx = mapTotalHeight * TILE_SIZE; // 256 pixels
        int normalizedScrollY = ((scrollY % planeHeightPx) + planeHeightPx) % planeHeightPx;

        // Normalize scroll to positive value within tile range
        // The scroll value wraps every 256 pixels (32 tiles * 8 pixels)
        int normalizedScrollX = ((scrollX % H32_WIDTH) + H32_WIDTH) % H32_WIDTH;

        for (int ty = 0; ty < mapHeight; ty++) {
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

                // Calculate position within H32 viewport (0-255 range)
                // The background is 32 tiles (256px) wide and wraps horizontally
                int tilePos = tx * TILE_SIZE - normalizedScrollX;
                // Wrap to 0-255 range (Java modulo can be negative, so normalize)
                int h32X = ((tilePos % H32_WIDTH) + H32_WIDTH) % H32_WIDTH;

                int tilePosY = ty * TILE_SIZE - normalizedScrollY;
                if (tilePosY < -TILE_SIZE) {
                    tilePosY += planeHeightPx;
                } else if (tilePosY >= H32_HEIGHT) {
                    tilePosY -= planeHeightPx;
                }
                if (tilePosY < -TILE_SIZE || tilePosY >= H32_HEIGHT) {
                    continue;
                }
                int screenY = tilePosY;
                int patternId = desc.getPatternIndex() + backgroundPatternBase;

                // Render tile within H32 viewport
                // Only render if tile starts within the viewport (h32X < 256)
                // Tiles are exactly aligned to 8-pixel boundaries, so with 32 tiles
                // covering 256 pixels exactly, there's no partial tile at edges
                int screenX = SCREEN_CENTER_OFFSET + h32X;
                graphicsManager.renderPatternWithId(patternId, desc, screenX, screenY);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders the background to an FBO for shader-based processing.
     *
     * Unlike renderBackground(), this renders tiles at their natural positions
     * without scroll wrapping or letterbox offset. The shader handles:
     * - Per-scanline horizontal scrolling
     * - H32 viewport clipping (256px centered on 320px)
     * - Vertical scrolling for parallax
     *
     * The FBO is 256x256 pixels (32x32 tiles), matching the background plane size.
     *
     * @param mappings Enigma-decoded mapping data (16-bit words)
     */
    public void renderBackgroundToFBO(byte[] mappings) {
        if (mappings == null || mappings.length < 2) {
            return;
        }

        graphicsManager.beginPatternBatch();

        int wordCount = mappings.length / 2;
        int mapWidth = H32_TILES_X; // 32 tiles wide
        int mapTotalHeight = 32;    // Background is 32 tiles tall
        int mapHeight = Math.min(wordCount / mapWidth, mapTotalHeight);

        for (int ty = 0; ty < mapHeight; ty++) {
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

                // Calculate position in FBO space (256x256)
                // No scroll offset - rendered at natural tile positions
                int screenX = tx * TILE_SIZE;
                // Y coordinate: ty=0 is top of background in Genesis coords
                // Pass Genesis Y directly - the pattern renderer handles Y-flip for OpenGL
                int screenY = ty * TILE_SIZE;

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
     * Renders a single player sprite using multi-piece mappings.
     *
     * Special stage sprites are NOT simple grids - they consist of multiple
     * sprite pieces at different positions with different sizes.
     * Data is from obj09.asm (Sonic) / obj0A.asm (Tails) mappings.
     *
     * @param player The player to render
     */
    private void renderPlayer(Sonic2SpecialStagePlayer player) {
        // Add H32 centering offset to match track rendering
        // The player's xPos is in H32 viewport coordinates (0-255), but we render on a 320px screen
        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;

        int screenX = SCREEN_CENTER_OFFSET + player.getXPos();
        // Y position is already in screen coordinates (Y=0 at top, increasing downward)
        // No need to invert since SSAnglePos already adds SS_Offset_Y
        int screenY = player.getYPos();

        int basePattern = playerPatternBase;
        if (player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.TAILS) {
            basePattern += TAILS_PATTERN_OFFSET;
        }

        // Get the mapping frame for current animation
        int mappingFrame = player.getMappingFrame();
        Sonic2SpecialStageSpriteMappings.SpriteFrame frame =
            Sonic2SpecialStageSpriteMappings.getSonicFrame(mappingFrame);

        boolean playerXFlip = player.isRenderXFlip();
        boolean playerYFlip = player.isRenderYFlip();

        int paletteIndex = player.getPlayerType() == Sonic2SpecialStagePlayer.PlayerType.SONIC ? 1 : 2;

        // Debug: log frame info once per animation change
        if (mappingFrame == 0 && !playerXFlip && !playerYFlip) {
            // Log nothing for now - can enable for debugging
        }

        // Render each sprite piece in the frame
        for (Sonic2SpecialStageSpriteMappings.SpritePiece piece : frame.pieces) {
            // Calculate piece position, applying player flip
            int pieceX = playerXFlip ? -piece.xOffset - (piece.widthTiles * TILE_SIZE) : piece.xOffset;
            int pieceY = playerYFlip ? -piece.yOffset - (piece.heightTiles * TILE_SIZE) : piece.yOffset;

            // Combine piece flip with player flip
            boolean finalHFlip = piece.hFlip ^ playerXFlip;
            boolean finalVFlip = piece.vFlip ^ playerYFlip;

            // Render all tiles in this piece
            // Mega Drive VDP sprites use COLUMN-MAJOR order: tiles go top-to-bottom, then left-to-right
            // When H-flipped, the VDP draws tiles from the LAST column first (tiles are rearranged)
            // When V-flipped, the VDP draws tiles from the BOTTOM row first (tiles are rearranged)
            // The flip flags ALSO flip each tile's pixels
            for (int tx = 0; tx < piece.widthTiles; tx++) {
                for (int ty = 0; ty < piece.heightTiles; ty++) {
                    // Determine which source tile to read based on flip state
                    int srcCol = finalHFlip ? (piece.widthTiles - 1 - tx) : tx;
                    int srcRow = finalVFlip ? (piece.heightTiles - 1 - ty) : ty;
                    // Column-major index: column * height + row
                    int tileIndexInPiece = srcCol * piece.heightTiles + srcRow;
                    int patternId = basePattern + piece.tileIndex + tileIndexInPiece;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(true);
                    desc.setPaletteIndex(paletteIndex);
                    desc.setHFlip(finalHFlip);
                    desc.setVFlip(finalVFlip);
                    desc.setPatternIndex(patternId & 0x7FF);

                    // Calculate tile position within the piece (always sequential on screen)
                    int tileOffsetX = tx * TILE_SIZE;
                    int tileOffsetY = ty * TILE_SIZE;

                    int tileScreenX = screenX + pieceX + tileOffsetX;
                    int tileScreenY = screenY + pieceY + tileOffsetY;

                    graphicsManager.renderPatternWithId(patternId, desc, tileScreenX, tileScreenY);
                }
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
            int screenY = player.getYPos();

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
     *
     * Charset mapping from s2.asm (lines 71524-71530) maps letters to frame numbers:
     * - G = frame 0, E = frame 1, T = frame 2, R = frame 3, I = frame 4, N = frame 5, S = frame 6
     *
     * Frame-to-tile mappings from obj5A.asm:
     * - Frame 0: tile offset 0x04 (G)
     * - Frame 1: tile offset 0x02 (E)
     * - Frame 2: tile offset 0x14 (T)
     * - Frame 3: tile offset 0x10 (R)
     * - Frame 4: tile offset 0x08 (I)
     * - Frame 5: tile offset 0x0C (N)
     * - Frame 6: tile offset 0x12 (S)
     * - Frame 7: tile offset 0x00 (unused here)
     */
    private static final int TILE_G = 0x04;   // Frame 0
    private static final int TILE_E = 0x02;   // Frame 1
    private static final int TILE_T = 0x14;   // Frame 2
    private static final int TILE_R = 0x10;   // Frame 3
    private static final int TILE_I = 0x08;   // Frame 4
    private static final int TILE_N = 0x0C;   // Frame 5
    private static final int TILE_S = 0x12;   // Frame 6 - S uses tile $12, not $00!
    private static final int TILE_O = 0x6A;   // Frame 8 - O uses tile $6A (overflows into HUD art)

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

        // Render "RINGS" at appropriate position
        // Note: Uses same TILE_G as in "GET" - 'G' is frame 0 in the charset
        // From disasm: x=$84 for 2-digit, $8C for 3-digit
        int ringsX = screenCenterOffset + (ringReq >= 100 ? 0x8C : 0x84);
        renderMessageLetter(ringsX, baseY, TILE_R, textPalette);
        renderMessageLetter(ringsX + 8, baseY, TILE_I, textPalette);
        renderMessageLetter(ringsX + 16, baseY, TILE_N, textPalette);
        renderMessageLetter(ringsX + 24, baseY, TILE_G, textPalette);
        renderMessageLetter(ringsX + 32, baseY, TILE_S, textPalette);
    }

    /**
     * Renders a single 1x2 tile letter from the SpecialMessages art.
     * Each letter is 8 pixels wide × 16 pixels tall (2 tiles stacked vertically).
     *
     * IMPORTANT: The original game's VRAM layout has SpecialMessages at $1A2 and
     * SpecialHUD at $1FA (88 tiles later). Some letters like 'O' use tile offset
     * $6A (106), which exceeds SpecialMessages' 88 tiles and overflows into
     * SpecialHUD territory. We handle this by checking if the offset is >= 88
     * and adjusting to use hudPatternBase instead.
     */
    private void renderMessageLetter(int x, int y, int tileOffset, int paletteIndex) {
        // SpecialMessages has exactly 88 tiles ($58). Offsets >= 88 overflow into SpecialHUD.
        final int MESSAGES_TILE_COUNT = 0x58;  // 88 tiles

        for (int ty = 0; ty < 2; ty++) {
            int patternId;
            if (tileOffset >= MESSAGES_TILE_COUNT) {
                // Overflow case: offset is in SpecialHUD territory
                // Calculate offset into HUD: (tileOffset - 88) + ty
                patternId = hudPatternBase + (tileOffset - MESSAGES_TILE_COUNT) + ty;
            } else {
                patternId = messagesPatternBase + tileOffset + ty;
            }

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
     * From obj5F_b.asm: digit 0 starts at tile $12, each digit is 2 tiles.
     * So digit N uses tiles at offset (0x12 + N*2) and (0x12 + N*2 + 1).
     */
    private void renderHudDigit(int x, int y, int digit, int paletteIndex) {
        // HUD digits start at tile offset 0x12, interleaved format
        int tileOffset = 0x12 + (digit * 2);
        int topPatternId = hudPatternBase + tileOffset;
        int bottomPatternId = hudPatternBase + tileOffset + 1;

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

    // ========== Ring Counter HUD (Phase 4.6) ==========

    /**
     * Renders the ring counter HUD during gameplay.
     * Shows current ring count in the top-left area.
     *
     * @param ringCount Current number of rings collected
     */
    public void renderRingCounter(int ringCount) {
        graphicsManager.beginPatternBatch();

        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;

        // Position: top-left corner with some padding
        // Original SS HUD is at approximately y=16 from top
        int baseX = SCREEN_CENTER_OFFSET + 16;
        int baseY = 16;

        // Render "RINGS" label using message letters (optional - can be just digits)
        // For now, just render the digit count

        // Calculate number of digits to display (always at least 1)
        int digitCount = 1;
        if (ringCount >= 100) {
            digitCount = 3;
        } else if (ringCount >= 10) {
            digitCount = 2;
        }

        // Position digits
        int digitX = baseX;
        int digitPalette = 2; // Use palette 2 for HUD digits

        // Hundreds digit
        if (ringCount >= 100) {
            int hundreds = (ringCount / 100) % 10;
            renderHudDigit(digitX, baseY, hundreds, digitPalette);
            digitX += 8;
        }

        // Tens digit (show as 0 if >= 10)
        if (ringCount >= 10) {
            int tens = (ringCount / 10) % 10;
            renderHudDigit(digitX, baseY, tens, digitPalette);
            digitX += 8;
        }

        // Ones digit
        int ones = ringCount % 10;
        renderHudDigit(digitX, baseY, ones, digitPalette);

        graphicsManager.flushPatternBatch();
    }

    // ========== Rings To Go HUD ==========

    /**
     * Renders the "X RINGS TO GO" counter during gameplay.
     * This is the persistent countdown display from Obj5A_RingsNeeded in the original game.
     *
     * The original game displays: [digits] RING[S] TO GO!
     * - Digits and "RING" are at fixed positions
     * - "TO GO!" letters animate from edges toward center
     * - "S" appears/hides based on count (pluralization)
     *
     * From s2.asm:
     * - y_pos = $38 (56 decimal)
     * - "RING" starts at x=$68, digits to the left starting at x=$5A
     * - "TO GO!" animates from Obj5A_ToGoOffsets positions
     *
     * @param ringsToGo Number of rings still needed (requirement - collected)
     * @param frameCounter Current frame for flash timing
     */
    public void renderRingsToGoHUD(int ringsToGo, int frameCounter) {
        // Don't show if we've already met the requirement
        if (ringsToGo <= 0) {
            return;
        }

        // Flash effect: hide every 8th frame (matching original's andi.b #7,d0; cmpi.b #6,d0)
        if ((frameCounter & 7) >= 6) {
            return;
        }

        graphicsManager.beginPatternBatch();

        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;

        // Position matches original: y=$38 (56)
        final int baseY = 0x38;
        final int textPalette = 2;
        final int digitPalette = 2;

        // Original layout (from disassembly):
        // - Digits start at x=$5A and decrease by 8 for each additional digit
        // - "RING" starts at x=$68
        // - "TO GO!" letters at various positions from Obj5A_ToGoOffsets
        // - "S" at position based on digit count

        // Calculate digit count
        int digitCount = 1;
        if (ringsToGo >= 100) {
            digitCount = 3;
        } else if (ringsToGo >= 10) {
            digitCount = 2;
        }

        // Simplified centered layout: "[digits] RINGS TO GO"
        // Total width: digits + space + RINGS(40) + space + TO(16) + space + GO(16) = varies
        final int RING_WIDTH = 32;  // "RING" = 4 letters * 8px
        final int TO_GO_WIDTH = 40; // "TO GO" = 5 letters * 8px (with spaces)

        int totalWidth = (digitCount * 8) + 8 + RING_WIDTH + 8 + TO_GO_WIDTH;
        int startX = SCREEN_CENTER_OFFSET + (H32_WIDTH - totalWidth) / 2;

        int drawX = startX;

        // Render digits
        if (ringsToGo >= 100) {
            int hundreds = (ringsToGo / 100) % 10;
            renderMessageDigit(drawX, baseY, hundreds, digitPalette);
            drawX += 8;
        }

        if (ringsToGo >= 10) {
            int tens = (ringsToGo / 10) % 10;
            renderMessageDigit(drawX, baseY, tens, digitPalette);
            drawX += 8;
        }

        int ones = ringsToGo % 10;
        renderMessageDigit(drawX, baseY, ones, digitPalette);
        drawX += 8;

        // Space
        drawX += 8;

        // Render "RING" (singular form in original)
        renderMessageLetter(drawX, baseY, TILE_R, textPalette);
        renderMessageLetter(drawX + 8, baseY, TILE_I, textPalette);
        renderMessageLetter(drawX + 16, baseY, TILE_N, textPalette);
        renderMessageLetter(drawX + 24, baseY, TILE_G, textPalette);
        drawX += 32;

        // Render "S" if plural (rings > 1)
        if (ringsToGo > 1) {
            renderMessageLetter(drawX, baseY, TILE_S, textPalette);
        }
        drawX += 8;

        // Space
        drawX += 8;

        // Render "TO GO" (simplified - original animates these)
        renderMessageLetter(drawX, baseY, TILE_T, textPalette);
        renderMessageLetter(drawX + 8, baseY, TILE_O, textPalette);
        drawX += 24;  // TO + space

        renderMessageLetter(drawX, baseY, TILE_G, textPalette);
        renderMessageLetter(drawX + 8, baseY, TILE_O, textPalette);

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a digit using the SpecialMessages art (0-9).
     * The digits in SpecialMessages art use different tile offsets than HUD art.
     *
     * From obj5A.asm: digits 0-9 are frames 12-16 (but frame ordering is different)
     * Actually looking at the charset, the digits use frames that map to specific tiles.
     *
     * For simplicity, we use the same HUD digit rendering which uses the Obj87 HUD art.
     */
    private void renderMessageDigit(int x, int y, int digit, int paletteIndex) {
        // Use messagesPatternBase for consistency
        // The messages art has digits at specific offsets
        // From the intro "GET XX RINGS", digits use hudPatternBase
        // So we'll continue using the HUD digit rendering
        renderHudDigit(x, y, digit, paletteIndex);
    }

    // ========== Object Rendering (Phase 4) ==========

    /**
     * Renders all special stage objects (rings and bombs).
     * Objects are sorted by depth (furthest first) for correct z-ordering.
     */
    public void renderObjects() {
        if (objectManager == null) {
            return;
        }

        List<Sonic2SpecialStageObject> objects = objectManager.getActiveObjects();
        if (objects.isEmpty()) {
            return;
        }

        // Sort by depth (higher depth = further away = draw first)
        List<Sonic2SpecialStageObject> sortedObjects = new ArrayList<>(objects);
        sortedObjects.sort((a, b) -> Integer.compare(b.getDepth(), a.getDepth()));

        graphicsManager.beginPatternBatch();

        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;

        for (Sonic2SpecialStageObject obj : sortedObjects) {
            if (!obj.isOnScreen()) {
                continue;
            }

            if (obj.isRing()) {
                renderRing((Sonic2SpecialStageRing) obj, SCREEN_CENTER_OFFSET);
            } else if (obj.isBomb()) {
                renderBomb((Sonic2SpecialStageBomb) obj, SCREEN_CENTER_OFFSET);
            } else if (obj.isEmerald()) {
                renderEmerald((Sonic2SpecialStageEmerald) obj, SCREEN_CENTER_OFFSET);
            }
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders a ring object using proper sprite mappings.
     * Ring sprites vary in size based on perspective distance.
     * When in sparkle state (collected), uses different art (SpecialStars).
     */
    private void renderRing(Sonic2SpecialStageRing ring, int screenCenterOffset) {
        int screenX = screenCenterOffset + ring.getScreenX();
        int screenY = ring.getScreenY();

        // Get the mapping frame for current animation state
        int mappingFrame = ring.getMappingFrame();
        Sonic2SpecialStageSpriteData.SpritePiece[] pieces =
            Sonic2SpecialStageSpriteData.getRingPieces(mappingFrame);

        // Determine which art base and palette to use
        // Sparkle uses SpecialStars art (palette 2), normal uses SpecialRings (palette 3)
        int patternBase;
        int paletteIndex;
        if (ring.isSparkle()) {
            patternBase = starsPatternBase;
            paletteIndex = 2;  // From make_art_tile(ArtTile_ArtNem_SpecialStars,2,0)
        } else {
            patternBase = ringPatternBase;
            paletteIndex = 3;  // From make_art_tile(ArtTile_ArtNem_SpecialRings,3,0)
        }

        // Render each sprite piece
        for (Sonic2SpecialStageSpriteData.SpritePiece piece : pieces) {
            int pieceX = screenX + piece.xOffset;
            int pieceY = screenY + piece.yOffset;

            // Render all tiles in this piece (column-major order)
            for (int tx = 0; tx < piece.widthTiles; tx++) {
                for (int ty = 0; ty < piece.heightTiles; ty++) {
                    // Column-major index
                    int tileIndex = tx * piece.heightTiles + ty;
                    int patternId = patternBase + piece.tileIndex + tileIndex;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(ring.isHighPriority());
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
     * Renders a bomb object using proper sprite mappings.
     * Bomb sprites vary in size based on perspective distance.
     * When in explosion state (hit), uses different art (SpecialExplosion).
     */
    private void renderBomb(Sonic2SpecialStageBomb bomb, int screenCenterOffset) {
        int screenX = screenCenterOffset + bomb.getScreenX();
        int screenY = bomb.getScreenY();

        // Get the mapping frame for current animation state
        int mappingFrame = bomb.getMappingFrame();
        Sonic2SpecialStageSpriteData.SpritePiece[] pieces =
            Sonic2SpecialStageSpriteData.getBombPieces(mappingFrame);

        // Determine which art base and palette to use
        // Explosion uses SpecialExplosion art (palette 2), normal uses SpecialBomb (palette 1)
        int patternBase;
        int paletteIndex;
        if (bomb.isExploding()) {
            patternBase = explosionPatternBase;
            paletteIndex = 2;  // From make_art_tile(ArtTile_ArtNem_SpecialExplosion,2,0)
        } else {
            patternBase = bombPatternBase;
            paletteIndex = 1;  // From make_art_tile(ArtTile_ArtNem_SpecialBomb,1,0)
        }

        // Render each sprite piece
        for (Sonic2SpecialStageSpriteData.SpritePiece piece : pieces) {
            int pieceX = screenX + piece.xOffset;
            int pieceY = screenY + piece.yOffset;

            // Render all tiles in this piece (column-major order)
            for (int tx = 0; tx < piece.widthTiles; tx++) {
                for (int ty = 0; ty < piece.heightTiles; ty++) {
                    // Column-major index
                    int tileIndex = tx * piece.heightTiles + ty;
                    int patternId = patternBase + piece.tileIndex + tileIndex;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(bomb.isHighPriority());
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
     * Renders an emerald object using proper sprite mappings.
     * Emerald sprites vary in size based on perspective distance (frames 0-9).
     * Uses palette line 3 (same as player).
     */
    private void renderEmerald(Sonic2SpecialStageEmerald emerald, int screenCenterOffset) {
        int screenX = screenCenterOffset + emerald.getScreenX();
        int screenY = emerald.getScreenY();

        // Apply bobbing offset when collected
        if (emerald.getPhase() == Sonic2SpecialStageEmerald.EmeraldPhase.COLLECTED) {
            screenY += emerald.getBobbingOffset();
        }

        // Get the mapping frame for current animation state
        int mappingFrame = emerald.getMappingFrame();
        Sonic2SpecialStageSpriteData.SpritePiece[] pieces =
            Sonic2SpecialStageSpriteData.getEmeraldPieces(mappingFrame);

        // Emerald uses palette 3 (from make_art_tile(ArtTile_ArtNem_SpecialEmerald,3,0))
        int patternBase = emeraldPatternBase;
        int paletteIndex = 3;

        // Render each sprite piece
        for (Sonic2SpecialStageSpriteData.SpritePiece piece : pieces) {
            int pieceX = screenX + piece.xOffset;
            int pieceY = screenY + piece.yOffset;

            // Render all tiles in this piece (column-major order)
            for (int tx = 0; tx < piece.widthTiles; tx++) {
                for (int ty = 0; ty < piece.heightTiles; ty++) {
                    // Column-major index
                    int tileIndex = tx * piece.heightTiles + ty;
                    int patternId = patternBase + piece.tileIndex + tileIndex;

                    PatternDesc desc = new PatternDesc();
                    desc.setPriority(true);  // Emerald always high priority
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

    // ========== Checkpoint Message Rendering ==========

    /**
     * Renders checkpoint UI elements (message text and hand).
     * Called when a checkpoint is active.
     */
    public void renderCheckpointUI() {
        if (checkpoint == null || !checkpoint.isActive()) {
            return;
        }

        graphicsManager.beginPatternBatch();

        final int H32_WIDTH = 256;
        final int SCREEN_CENTER_OFFSET = (320 - H32_WIDTH) / 2;

        // Render checkpoint message letters
        if (checkpoint.isMessageVisible()) {
            renderCheckpointMessage(SCREEN_CENTER_OFFSET);
        }

        // Render checkpoint hand (with wings)
        if (checkpoint.isHandVisible()) {
            renderCheckpointHand(SCREEN_CENTER_OFFSET);
        }

        graphicsManager.flushPatternBatch();
    }

    /**
     * Renders the checkpoint message text ("COOL!" or "NOT ENOUGH RINGS...").
     *
     * From s2.asm Init_Obj5A (line 71005) and Obj5A_CreateTextLetter (line 71509):
     * - Uses ArtTile_ArtNem_SpecialMessages ($1A2) with palette 2
     * - Same art as intro "GET XX RINGS" message
     *
     * Uses renderMessageLetter which handles the overflow case for letters like 'O'
     * that use tile offsets >= 88 (which overflow into SpecialHUD territory).
     */
    private void renderCheckpointMessage(int screenCenterOffset) {
        // Palette 2 for checkpoint message text (from make_art_tile(ArtTile_ArtNem_SpecialMessages,2,0))
        final int textPalette = 2;

        for (Sonic2SpecialStageCheckpoint.MessageLetter letter : checkpoint.getMessageLetters()) {
            if (!letter.visible) {
                continue;
            }

            int screenX = screenCenterOffset + letter.x;
            int screenY = letter.y;

            // Use renderMessageLetter which handles the overflow case for 'O' and other letters
            renderMessageLetter(screenX, screenY, letter.tileOffset, textPalette);
        }
    }

    /**
     * Renders the checkpoint hand sprite (with wings).
     *
     * From obj5A.asm mappings and s2.asm Obj5A_Handshake:
     * - Frame $14 (20): Checkpoint wings (Map_obj5A_00F4)
     * - Frame $15 (21): Checkpoint hand (Map_obj5A_0136)
     *
     * IMPORTANT: The hand uses ArtTile_ArtNem_SpecialMessages (messagesPatternBase),
     * NOT ArtTile_ArtNem_SpecialRings. See s2.asm line 71413.
     *
     * The hand shows thumbs up when passing, thumbs down (v-flipped) when failing.
     */
    private void renderCheckpointHand(int screenCenterOffset) {
        int handX = screenCenterOffset + checkpoint.getHandX();
        int handY = checkpoint.getHandY();
        boolean thumbsUp = checkpoint.isHandThumbsUp();

        // Palette 1 for hand/wings (from make_art_tile(ArtTile_ArtNem_SpecialMessages,1,0))
        final int handPalette = 1;

        // Render wings first (Frame $14 = Map_obj5A_00F4) - behind hand
        renderCheckpointWingsFrame20(handX, handY, handPalette);

        // Render hand (Frame $15 = Map_obj5A_0136)
        // vFlip = !thumbsUp (thumbs down when failing)
        renderCheckpointHandFrame21(handX, handY, handPalette, !thumbsUp);
    }

    /**
     * Renders the checkpoint wings sprite using Frame 20 mapping (Map_obj5A_00F4).
     *
     * From obj5A.asm - 8 sprite pieces forming symmetrical wings:
     * spritePiece -$30, -$1C, 1, 4, $1A, 0, 0, 0, 1   ; left edge
     * spritePiece -$28, -$14, 4, 4, $1E, 0, 0, 0, 1   ; left wing body
     * spritePiece -8, -$14, 1, 4, $2E, 0, 0, 0, 1     ; center left
     * spritePiece -$20, $C, 4, 2, $32, 0, 0, 0, 1     ; bottom left ribbon
     * spritePiece 0, -$14, 1, 4, $2E, 1, 0, 0, 1      ; center right (h-flipped)
     * spritePiece 0, $C, 4, 2, $32, 1, 0, 0, 1        ; bottom right ribbon (h-flipped)
     * spritePiece 8, -$14, 4, 4, $1E, 1, 0, 0, 1      ; right wing body (h-flipped)
     * spritePiece $28, -$1C, 1, 4, $1A, 1, 0, 0, 1    ; right edge (h-flipped)
     */
    private void renderCheckpointWingsFrame20(int centerX, int centerY, int paletteIndex) {
        // Left side pieces (not flipped)
        renderMessageSpritePiece(centerX - 0x30, centerY - 0x1C, 1, 4, 0x1A, paletteIndex, false, false);
        renderMessageSpritePiece(centerX - 0x28, centerY - 0x14, 4, 4, 0x1E, paletteIndex, false, false);
        renderMessageSpritePiece(centerX - 8, centerY - 0x14, 1, 4, 0x2E, paletteIndex, false, false);
        renderMessageSpritePiece(centerX - 0x20, centerY + 0x0C, 4, 2, 0x32, paletteIndex, false, false);

        // Right side pieces (h-flipped)
        renderMessageSpritePiece(centerX, centerY - 0x14, 1, 4, 0x2E, paletteIndex, true, false);
        renderMessageSpritePiece(centerX, centerY + 0x0C, 4, 2, 0x32, paletteIndex, true, false);
        renderMessageSpritePiece(centerX + 8, centerY - 0x14, 4, 4, 0x1E, paletteIndex, true, false);
        renderMessageSpritePiece(centerX + 0x28, centerY - 0x1C, 1, 4, 0x1A, paletteIndex, true, false);
    }

    /**
     * Renders the checkpoint hand sprite using Frame 21 mapping (Map_obj5A_0136).
     *
     * From obj5A.asm - 4 sprite pieces forming the hand:
     * spritePiece -$18, -$10, 3, 4, $3A, 0, 0, 0, 1  ; main hand body (palm/wrist)
     * spritePiece -$18, $10, 3, 1, $46, 0, 0, 0, 1   ; bottom (thumb area)
     * spritePiece 0, 0, 3, 3, $49, 0, 0, 0, 1        ; right side (fingers)
     * spritePiece 0, -$18, 2, 3, $52, 0, 0, 0, 1     ; top right (fingertips)
     *
     * @param centerX Center X position
     * @param centerY Center Y position
     * @param paletteIndex Palette to use
     * @param vFlip True if hand should be v-flipped (thumbs down for failure)
     */
    private void renderCheckpointHandFrame21(int centerX, int centerY, int paletteIndex, boolean vFlip) {
        // When v-flipped, we need to mirror Y positions around center
        // Original offsets: -$10, $10, 0, -$18
        // Flipped offsets: $10 - height, -$10 - height, 0 - height, $18 - height

        if (!vFlip) {
            // Normal orientation (thumbs up)
            renderMessageSpritePiece(centerX - 0x18, centerY - 0x10, 3, 4, 0x3A, paletteIndex, false, false);
            renderMessageSpritePiece(centerX - 0x18, centerY + 0x10, 3, 1, 0x46, paletteIndex, false, false);
            renderMessageSpritePiece(centerX, centerY, 3, 3, 0x49, paletteIndex, false, false);
            renderMessageSpritePiece(centerX, centerY - 0x18, 2, 3, 0x52, paletteIndex, false, false);
        } else {
            // Flipped orientation (thumbs down)
            // Each piece needs its Y position mirrored and individual tiles v-flipped
            // For a piece at offset Y with height H*8, flipped offset = -Y - H*8
            renderMessageSpritePiece(centerX - 0x18, centerY + 0x10 - 32, 3, 4, 0x3A, paletteIndex, false, true);
            renderMessageSpritePiece(centerX - 0x18, centerY - 0x10 - 8, 3, 1, 0x46, paletteIndex, false, true);
            renderMessageSpritePiece(centerX, centerY - 24, 3, 3, 0x49, paletteIndex, false, true);
            renderMessageSpritePiece(centerX, centerY + 0x18 - 24, 2, 3, 0x52, paletteIndex, false, true);
        }
    }

    /**
     * Renders a sprite piece from the SpecialMessages art.
     * Used for checkpoint hand and wings which use messagesPatternBase.
     *
     * @param x Top-left X position
     * @param y Top-left Y position
     * @param widthTiles Width in tiles
     * @param heightTiles Height in tiles
     * @param baseTile Base tile offset in SpecialMessages art
     * @param paletteIndex Palette to use
     * @param hFlip Horizontal flip
     * @param vFlip Vertical flip
     */
    private void renderMessageSpritePiece(int x, int y, int widthTiles, int heightTiles,
                                          int baseTile, int paletteIndex, boolean hFlip, boolean vFlip) {
        for (int tx = 0; tx < widthTiles; tx++) {
            for (int ty = 0; ty < heightTiles; ty++) {
                // Column-major order (VDP sprite format)
                int srcTx = hFlip ? (widthTiles - 1 - tx) : tx;
                int srcTy = vFlip ? (heightTiles - 1 - ty) : ty;
                int tileIndex = srcTx * heightTiles + srcTy;

                // Use messagesPatternBase because hand/wings use SpecialMessages art
                int patternId = messagesPatternBase + baseTile + tileIndex;

                PatternDesc desc = new PatternDesc();
                desc.setPriority(true);
                desc.setPaletteIndex(paletteIndex);
                desc.setHFlip(hFlip);
                desc.setVFlip(vFlip);
                desc.setPatternIndex(patternId & 0x7FF);

                int drawX = x + tx * TILE_SIZE;
                int drawY = y + ty * TILE_SIZE;

                graphicsManager.renderPatternWithId(patternId, desc, drawX, drawY);
            }
        }
    }
}
