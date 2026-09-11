package com.openggf.game.sonic1.specialstage;

import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.PatternDesc;
import com.openggf.level.render.SpriteMappingPiece;
import com.openggf.level.render.SpritePieceRenderer;
import com.openggf.physics.TrigLookupTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import static com.openggf.game.sonic1.constants.Sonic1Constants.*;

/**
 * Renders the Sonic 1 Special Stage rotating maze.
 *
 * Implements the SS_ShowLayout algorithm from sonic.asm (lines 6947-7069):
 * 1. Compute sin/cos from ssAngle
 * 2. Scale by block size (0x18 = 24px)
 * 3. For each 16x16 grid of visible blocks, compute rotated screen position
 * 4. Look up block render info and emit mapped sprite pieces
 *
 * Uses batched pattern rendering via GraphicsManager for performance.
 */
public class Sonic1SpecialStageRenderer {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SpecialStageRenderer.class.getName());

    // Special Stage viewport dimensions on the 320x224 output surface.
    public static final int H32_WIDTH = 320;
    public static final int H32_HEIGHT = 224;
    public static final int SCREEN_CENTER_OFFSET = 0;

    // Grid display size (16x16 blocks visible at once)
    private static final int GRID_SIZE = 16;
    private static final int SPRITE_CULL_MARGIN = 16;

    // VDP coordinate offsets from SS_ShowLayout
    private static final int VDP_OFFSET_X = 0x120; // 288
    private static final int VDP_OFFSET_Y = 0xF0;  // 240
    private static final int VDP_BASE = 128;        // VDP uses 128-based coordinates

    // Pre-allocated PatternDesc for rendering (avoid allocation per block)
    private final PatternDesc tempDesc = new PatternDesc();

    // Cached screen positions for the 16x16 grid (x,y pairs)
    private final int[] screenPositions = new int[GRID_SIZE * GRID_SIZE * 2];

    private final GraphicsManager graphicsManager;

    // Pattern base IDs in the atlas (set during art loading)
    private int wallPatternBase;
    private int bumperPatternBase;
    private int goalPatternBase;
    private int upDownPatternBase;
    private int rBlockPatternBase;
    private int oneUpPatternBase;
    private int emStarsPatternBase;
    private int redWhitePatternBase;
    private int ghostPatternBase;
    private int wBlockPatternBase;
    private int glassPatternBase;
    private int emeraldPatternBase;
    private int ringPatternBase;
    private final int[] zonePatternBases = new int[6];
    private int bgCloudPatternBase;
    private int bgFishPatternBase;

    // Fallback background color (palette-derived, updated each frame)
    private float bgR, bgG, bgB;
    // Wall palette animation table (from SS_WaRiVramSet, sonic.asm:7187-7194)
    // 4 groups x 16 entries. For frame f, positions 2-8 read entries at f+0 through f+6.
    private static final int[][] WALL_PALETTE_ANIM = {
            {0, 3, 0, 0, 0, 0, 0, 3, 0, 3, 0, 0, 0, 0, 0, 3},
            {1, 0, 1, 1, 1, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1, 0},
            {2, 1, 2, 2, 2, 2, 2, 1, 2, 1, 2, 2, 2, 2, 2, 1},
            {3, 2, 3, 3, 3, 3, 3, 2, 3, 2, 3, 3, 3, 3, 3, 2},
    };

    private static final int ZERO_RENDER_WARNING_THRESHOLD = 15;
    private int consecutiveZeroRenderFrames;
    private int lastRenderedBlocks;
    private int lastValidBlockCells;

    // Mapping data transcribed from docs/s1disasm/_maps/*.asm
    private static final List<List<SpriteMappingPiece>> MAP_SS_WALLS_FRAMES = createWallsFrames();
    private static final List<List<SpriteMappingPiece>> MAP_BUMPER_FRAMES = createBumperFrames();
    private static final List<List<SpriteMappingPiece>> MAP_SS_R_FRAMES = List.of(
            List.of(piece(-12, -12, 3, 3, 0, false, false)),
            List.of(piece(-12, -12, 3, 3, 9, false, false)),
            List.of() // Ghost switch frame (empty)
    );
    private static final List<List<SpriteMappingPiece>> MAP_SS_GLASS_FRAMES = List.of(
            List.of(piece(-12, -12, 3, 3, 0, false, false)),
            List.of(piece(-12, -12, 3, 3, 0, true, false)),
            List.of(piece(-12, -12, 3, 3, 0, true, true)),
            List.of(piece(-12, -12, 3, 3, 0, false, true))
    );
    private static final List<List<SpriteMappingPiece>> MAP_SS_UP_FRAMES = List.of(
            List.of(piece(-12, -12, 3, 3, 0, false, false)),
            List.of(piece(-12, -12, 3, 3, 0x12, false, false))
    );
    private static final List<List<SpriteMappingPiece>> MAP_SS_DOWN_FRAMES = List.of(
            List.of(piece(-12, -12, 3, 3, 9, false, false)),
            List.of(piece(-12, -12, 3, 3, 0x12, false, false))
    );
    private static final List<List<SpriteMappingPiece>> MAP_SS_CHAOS1_FRAMES = List.of(
            List.of(piece(-8, -8, 2, 2, 0, false, false)),
            List.of(piece(-8, -8, 2, 2, 0xC, false, false))
    );
    private static final List<List<SpriteMappingPiece>> MAP_SS_CHAOS2_FRAMES = List.of(
            List.of(piece(-8, -8, 2, 2, 4, false, false)),
            List.of(piece(-8, -8, 2, 2, 0xC, false, false))
    );
    private static final List<List<SpriteMappingPiece>> MAP_SS_CHAOS3_FRAMES = List.of(
            List.of(piece(-8, -8, 2, 2, 8, false, false)),
            List.of(piece(-8, -8, 2, 2, 0xC, false, false))
    );
    private static final List<List<SpriteMappingPiece>> MAP_RING_FRAMES = List.of(
            List.of(piece(-8, -8, 2, 2, 0, false, false)),
            List.of(piece(-8, -8, 2, 2, 4, false, false)),
            List.of(piece(-4, -8, 1, 2, 8, false, false)),
            List.of(piece(-8, -8, 2, 2, 4, true, false)),
            List.of(piece(-8, -8, 2, 2, 0xA, false, false)),
            List.of(piece(-8, -8, 2, 2, 0xA, true, false)),
            List.of(piece(-8, -8, 2, 2, 0xA, false, true)),
            List.of(piece(-8, -8, 2, 2, 0xA, true, true))
    );

    public Sonic1SpecialStageRenderer(GraphicsManager graphicsManager) {
        this.graphicsManager = graphicsManager;
    }

    public void setPatternBases(int wallBase, int bumperBase, int goalBase,
                                int upDownBase, int rBlockBase, int oneUpBase,
                                int emStarsBase, int redWhiteBase, int ghostBase,
                                int wBlockBase, int glassBase, int emeraldBase,
                                int ringBase,
                                int zone1Base, int zone2Base, int zone3Base,
                                int zone4Base, int zone5Base, int zone6Base,
                                int bgCloudBase, int bgFishBase) {
        this.wallPatternBase = wallBase;
        this.bumperPatternBase = bumperBase;
        this.goalPatternBase = goalBase;
        this.upDownPatternBase = upDownBase;
        this.rBlockPatternBase = rBlockBase;
        this.oneUpPatternBase = oneUpBase;
        this.emStarsPatternBase = emStarsBase;
        this.redWhitePatternBase = redWhiteBase;
        this.ghostPatternBase = ghostBase;
        this.wBlockPatternBase = wBlockBase;
        this.glassPatternBase = glassBase;
        this.emeraldPatternBase = emeraldBase;
        this.ringPatternBase = ringBase;
        this.zonePatternBases[0] = zone1Base;
        this.zonePatternBases[1] = zone2Base;
        this.zonePatternBases[2] = zone3Base;
        this.zonePatternBases[3] = zone4Base;
        this.zonePatternBases[4] = zone5Base;
        this.zonePatternBases[5] = zone6Base;
        this.bgCloudPatternBase = bgCloudBase;
        this.bgFishPatternBase = bgFishBase;
    }

    /**
     * Renders the complete special stage frame (background + maze).
     */
    public void render(byte[] layout, int ssAngle, int cameraX, int cameraY,
                       int sonicX, int sonicY, int wallRotFrame, int ringAnimFrame,
                       int wallVramAnimFrame, int ani2Frame, int ani3Frame,
                       boolean sonicFacingLeft) {
        // 1. Draw background (solid color fallback)
        renderBackground();

        // 2. Render maze (grid computation + block rendering)
        renderMaze(layout, ssAngle, cameraX, cameraY, wallRotFrame, ringAnimFrame, wallVramAnimFrame,
                ani2Frame, ani3Frame);
    }

    /**
     * Renders only the rotating maze blocks (no background).
     * Used when the background is rendered separately via the BG renderer.
     */
    public void renderMaze(byte[] layout, int ssAngle, int cameraX, int cameraY,
                           int wallRotFrame, int ringAnimFrame, int wallVramAnimFrame,
                           int ani2Frame, int ani3Frame) {
        computeGridPositions(ssAngle, cameraX, cameraY);

        graphicsManager.beginPatternBatch();
        renderBlocks(layout, cameraX, cameraY, wallRotFrame, ringAnimFrame, wallVramAnimFrame,
                ani2Frame, ani3Frame);
        graphicsManager.flushPatternBatch();
    }

    /**
     * Updates the fallback background color from the SS palette backdrop (CRAM[0]).
     */
    void setBackdropColor(float r, float g, float b) {
        this.bgR = r;
        this.bgG = g;
        this.bgB = b;
    }

    /**
     * Renders the solid-color background fallback.
     * Package-visible for use when the shader-based BG renderer is unavailable.
     */
    void renderBackground() {
        graphicsManager.registerCommand(new GLCommand(
            GLCommand.CommandType.RECTI,
            -1,
            GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
            bgR, bgG, bgB, 1.0f,
            0, 0,
            H32_WIDTH, H32_HEIGHT
        ));
    }

    /**
     * Computes the rotated screen positions for a 16x16 grid of blocks.
     * Translates SS_ShowLayout lines 6952-7001 from 68000 assembly.
     */
    private void computeGridPositions(int ssAngle, int cameraX, int cameraY) {
        // CalcSine with byte angle (top byte of 16-bit ssAngle, masked to 0xFC)
        int byteAngle = (ssAngle >> 8) & 0xFC;
        int sinVal = TrigLookupTable.sinHex(byteAngle);  // d0
        int cosVal = TrigLookupTable.cosHex(byteAngle);  // d1

        // Scale by block size
        int d4 = sinVal * SS_BLOCK_SIZE_PX; // sin * 0x18
        int d5 = cosVal * SS_BLOCK_SIZE_PX; // cos * 0x18

        // Camera sub-block offsets
        int offsetX = -(cameraX % SS_BLOCK_SIZE_PX) - 0xB4;
        int offsetY = -(cameraY % SS_BLOCK_SIZE_PX) - 0xB4;

        int posIdx = 0;
        int curOffsetY = offsetY;
        for (int row = 0; row < GRID_SIZE; row++) {
            // Compute first column's rotated position
            // d6 = (-sin * offsetX + cos * curOffsetY) -- actually ROM uses:
            // neg.w d0; muls d2,d1; muls d3,d0 -> d6 = d0+d1 = -sin*offsetX + cos*curOffsetY (note: d0/d1 swapped in CalcSine result)
            // Actually from the ROM code:
            // d0 = sin (from CalcSine), d1 = cos (from CalcSine)
            // loc_1B19E: push d0-d2, push d0-d1
            //   neg.w d0 -> -sin
            //   muls d2,d1 -> cos * offsetX
            //   muls d3,d0 -> -sin * curOffsetY
            //   d6 = d0 + d1 = cos*offsetX + (-sin)*curOffsetY
            // pop d0-d1
            //   muls d2,d0 -> sin * offsetX
            //   muls d3,d1 -> cos * curOffsetY
            //   d1 = d0 + d1 = sin*offsetX + cos*curOffsetY

            long rowBaseX = (long) cosVal * offsetX + (long) (-sinVal) * curOffsetY;
            long rowBaseY = (long) sinVal * offsetX + (long) cosVal * curOffsetY;

            for (int col = 0; col < GRID_SIZE; col++) {
                // Screen position: shift right 8, add VDP offset, convert from VDP coords
                int sx = (int) (rowBaseX >> 8) + VDP_OFFSET_X - VDP_BASE;
                int sy = (int) (rowBaseY >> 8) + VDP_OFFSET_Y - VDP_BASE;

                screenPositions[posIdx++] = sx;
                screenPositions[posIdx++] = sy;

                // Advance along row by one block (add scaled cos/sin step)
                rowBaseX += d5; // cos * blockSize
                rowBaseY += d4; // sin * blockSize
            }

            curOffsetY += SS_BLOCK_SIZE_PX;
        }
    }

    /**
     * Renders all visible blocks in the layout grid.
     */
    private void renderBlocks(byte[] layout, int cameraX, int cameraY,
                              int wallRotFrame, int ringAnimFrame, int wallVramAnimFrame,
                              int ani2Frame, int ani3Frame) {
        int layoutRows = layout.length / SS_LAYOUT_STRIDE;
        int layoutCols = SS_LAYOUT_STRIDE;
        if (layoutRows <= 0) {
            lastRenderedBlocks = 0;
            lastValidBlockCells = 0;
            return;
        }

        // Compute grid base position in layout buffer
        int baseRow = cameraY / SS_BLOCK_SIZE_PX;
        int baseCol = cameraX / SS_BLOCK_SIZE_PX;

        int posIdx = 0;
        int attemptedCells = 0;
        int inBoundsCells = 0;
        int validBlockCells = 0;
        int renderedBlocks = 0;
        for (int row = 0; row < GRID_SIZE; row++) {
            int layoutRow = baseRow + row;

            for (int col = 0; col < GRID_SIZE; col++) {
                int sx = screenPositions[posIdx++];
                int sy = screenPositions[posIdx++];

                // SS_ShowLayout culls at VDP x=$70..$1CF and y=$70..$16F.
                // Converted to screen space this is a 16px margin around 320x224.
                if (sx < -SPRITE_CULL_MARGIN || sx >= H32_WIDTH + SPRITE_CULL_MARGIN ||
                        sy < -SPRITE_CULL_MARGIN || sy >= H32_HEIGHT + SPRITE_CULL_MARGIN) {
                    continue;
                }

                attemptedCells++;
                int layoutCol = baseCol + col;
                if (layoutRow < 0 || layoutRow >= layoutRows || layoutCol < 0 || layoutCol >= layoutCols) {
                    continue;
                }
                inBoundsCells++;

                int layoutIndex = layoutRow * SS_LAYOUT_STRIDE + layoutCol;
                if (layoutIndex < 0 || layoutIndex >= layout.length) {
                    continue;
                }
                int blockId = layout[layoutIndex] & 0xFF;
                if (blockId == 0 || blockId > Sonic1SpecialStageBlockType.MAX_BLOCK_ID) {
                    continue;
                }
                validBlockCells++;

                renderBlock(blockId, sx + SCREEN_CENTER_OFFSET, sy, wallRotFrame, ringAnimFrame, wallVramAnimFrame,
                        ani2Frame, ani3Frame);
                renderedBlocks++;
            }
        }
        lastRenderedBlocks = renderedBlocks;
        lastValidBlockCells = validBlockCells;

        if (renderedBlocks == 0 && attemptedCells > 0) {
            consecutiveZeroRenderFrames++;
            if (consecutiveZeroRenderFrames == ZERO_RENDER_WARNING_THRESHOLD ||
                    consecutiveZeroRenderFrames % 120 == 0) {
                LOGGER.warning("S1 special stage rendered zero blocks for " + consecutiveZeroRenderFrames +
                        " frames (attempted=" + attemptedCells +
                        ", inBounds=" + inBoundsCells +
                        ", valid=" + validBlockCells +
                        ", camera=" + cameraX + "," + cameraY +
                        ", base=" + baseRow + "," + baseCol +
                        ", layoutRows=" + layoutRows +
                        ", layoutCols=" + layoutCols + ")");
            }
        } else {
            consecutiveZeroRenderFrames = 0;
        }
    }

    int getLastRenderedBlocks() {
        return lastRenderedBlocks;
    }

    int getLastValidBlockCells() {
        return lastValidBlockCells;
    }

    /**
     * Renders a single block at the given screen position.
     */
    private void renderBlock(int blockId, int screenX, int screenY,
                             int wallRotFrame, int ringAnimFrame, int wallVramAnimFrame,
                             int ani2Frame, int ani3Frame) {
        Sonic1SpecialStageBlockType.BlockRenderInfo info =
                Sonic1SpecialStageBlockType.getBlockInfo(blockId);
        if (info == null) {
            return;
        }

        int patternBase = getPatternBaseForArtTile(info.artTileBase());
        if (patternBase <= 0) {
            return;
        }

        List<SpriteMappingPiece> pieces = resolvePieces(info, blockId, wallRotFrame, ringAnimFrame,
                ani2Frame, ani3Frame);
        if (pieces.isEmpty()) {
            return;
        }

        // Wall palette animation override (SS_WaRiVramSet)
        int palette = info.paletteIndex();
        if (info.mappingType() == Sonic1SpecialStageBlockType.MappingType.WALLS) {
            int wallMetadata = Sonic1SpecialStageBlockType.getWallGroupAndPositionPacked(blockId);
            int wallPosition = Sonic1SpecialStageBlockType.wallPosition(wallMetadata);
            if (wallMetadata >= 0 && wallPosition >= 2) {
                int animPos = wallPosition - 2; // 0-6 (positions 2-8 map to anim entries 0-6)
                palette = WALL_PALETTE_ANIM[Sonic1SpecialStageBlockType.wallGroup(wallMetadata)]
                        [wallVramAnimFrame + animPos];
            }
        }

        renderMappedPieces(patternBase, pieces, palette, screenX, screenY);
    }

    private List<SpriteMappingPiece> resolvePieces(Sonic1SpecialStageBlockType.BlockRenderInfo info,
                                                   int blockId,
                                                   int wallRotFrame,
                                                   int ringAnimFrame,
                                                   int ani2Frame,
                                                   int ani3Frame) {
        return switch (info.mappingType()) {
            case WALLS -> frameOrEmpty(MAP_SS_WALLS_FRAMES, wallRotFrame);
            case BUMPER -> frameOrEmpty(MAP_BUMPER_FRAMES, info.animFrame());
            case BLOCK_3X3 -> {
                // GOAL (0x27) uses ani2 for blinking animation
                if (blockId == 0x27) {
                    yield frameOrEmpty(MAP_SS_R_FRAMES, ani2Frame);
                }
                yield frameOrEmpty(MAP_SS_R_FRAMES, info.animFrame());
            }
            case GLASS -> {
                // Red-White (0x2C) uses ani2 for blinking animation
                if (blockId == 0x2C) {
                    yield frameOrEmpty(MAP_SS_GLASS_FRAMES, ani2Frame);
                }
                // Glass blocks (0x2D-0x30, 0x4B-0x4E) use ani3 for rotation animation
                if ((blockId >= 0x2D && blockId <= 0x30) || (blockId >= 0x4B && blockId <= 0x4E)) {
                    yield frameOrEmpty(MAP_SS_GLASS_FRAMES, ani3Frame);
                }
                yield frameOrEmpty(MAP_SS_GLASS_FRAMES, info.animFrame());
            }
            case UP -> frameOrEmpty(MAP_SS_UP_FRAMES, ani2Frame);
            case DOWN -> frameOrEmpty(MAP_SS_DOWN_FRAMES, ani2Frame);
            case RING -> {
                int frame = info.animFrame() > 0 ? info.animFrame() : ringAnimFrame;
                yield frameOrEmpty(MAP_RING_FRAMES, frame);
            }
            case EMERALD_1 -> frameOrEmpty(MAP_SS_CHAOS1_FRAMES, ani2Frame);
            case EMERALD_2 -> frameOrEmpty(MAP_SS_CHAOS2_FRAMES, ani2Frame);
            case EMERALD_3 -> frameOrEmpty(MAP_SS_CHAOS3_FRAMES, ani2Frame);
        };
    }

    private void renderMappedPieces(int patternBase, List<SpriteMappingPiece> pieces,
                                    int palette, int screenX, int screenY) {
        SpritePieceRenderer.renderPieces(
                pieces,
                screenX,
                screenY,
                patternBase,
                palette,
                false,
                false,
                (patternId, pieceHFlip, pieceVFlip, paletteIndex, drawX, drawY) -> {
                    int descIndex = patternId & 0x7FF;
                    if (pieceHFlip) {
                        descIndex |= 0x800;
                    }
                    if (pieceVFlip) {
                        descIndex |= 0x1000;
                    }
                    descIndex |= (paletteIndex & 0x3) << 13;
                    tempDesc.set(descIndex);
                    graphicsManager.renderPatternWithId(patternId, tempDesc, drawX, drawY);
                }
        );
    }

    /**
     * Maps an ArtTile base constant to the pattern atlas base.
     */
    private int getPatternBaseForArtTile(int artTileBase) {
        if (artTileBase == ARTTILE_SS_WALL) return wallPatternBase;
        if (artTileBase == ARTTILE_SS_BUMPER) return bumperPatternBase;
        if (artTileBase == ARTTILE_SS_GOAL) return goalPatternBase;
        if (artTileBase == ARTTILE_SS_UP_DOWN) return upDownPatternBase;
        if (artTileBase == ARTTILE_SS_R_BLOCK) return rBlockPatternBase;
        if (artTileBase == ARTTILE_SS_EXTRA_LIFE) return oneUpPatternBase;
        if (artTileBase == ARTTILE_SS_EMERALD_SPARKLE) return emStarsPatternBase;
        if (artTileBase == ARTTILE_SS_RED_WHITE) return redWhitePatternBase;
        if (artTileBase == ARTTILE_SS_GHOST) return ghostPatternBase;
        if (artTileBase == ARTTILE_SS_W_BLOCK) return wBlockPatternBase;
        if (artTileBase == ARTTILE_SS_GLASS) return glassPatternBase;
        if (artTileBase == ARTTILE_SS_EMERALD) return emeraldPatternBase;
        if (artTileBase == ARTTILE_RING) return ringPatternBase;
        if (artTileBase == ARTTILE_SS_ZONE_1) return zonePatternBases[0];
        if (artTileBase == ARTTILE_SS_ZONE_2) return zonePatternBases[1];
        if (artTileBase == ARTTILE_SS_ZONE_3) return zonePatternBases[2];
        if (artTileBase == ARTTILE_SS_ZONE_4) return zonePatternBases[3];
        if (artTileBase == ARTTILE_SS_ZONE_5) return zonePatternBases[4];
        if (artTileBase == ARTTILE_SS_ZONE_6) return zonePatternBases[5];
        return wallPatternBase; // default fallback
    }

    private static List<SpriteMappingPiece> frameOrEmpty(List<List<SpriteMappingPiece>> frames, int frame) {
        if (frame < 0 || frame >= frames.size()) {
            return Collections.emptyList();
        }
        return frames.get(frame);
    }

    private static SpriteMappingPiece piece(int xOffset, int yOffset, int widthTiles, int heightTiles,
                                            int tileIndex, boolean hFlip, boolean vFlip) {
        return new SpriteMappingPiece(xOffset, yOffset, widthTiles, heightTiles,
                tileIndex, hFlip, vFlip, 0);
    }

    private static List<List<SpriteMappingPiece>> createWallsFrames() {
        List<List<SpriteMappingPiece>> frames = new ArrayList<>(16);
        frames.add(List.of(piece(-12, -12, 3, 3, 0, false, false)));
        for (int i = 1; i < 16; i++) {
            int tileBase = 9 + ((i - 1) * 16);
            frames.add(List.of(piece(-16, -16, 4, 4, tileBase, false, false)));
        }
        return List.copyOf(frames);
    }

    private static List<List<SpriteMappingPiece>> createBumperFrames() {
        List<List<SpriteMappingPiece>> frames = new ArrayList<>(3);
        frames.add(List.of(
                piece(-16, -16, 2, 4, 0, false, false),
                piece(0, -16, 2, 4, 0, true, false)
        ));
        frames.add(List.of(
                piece(-12, -12, 2, 3, 8, false, false),
                piece(4, -12, 1, 3, 8, true, false)
        ));
        frames.add(List.of(
                piece(-16, -16, 2, 4, 0xE, false, false),
                piece(0, -16, 2, 4, 0xE, true, false)
        ));
        return List.copyOf(frames);
    }
}
