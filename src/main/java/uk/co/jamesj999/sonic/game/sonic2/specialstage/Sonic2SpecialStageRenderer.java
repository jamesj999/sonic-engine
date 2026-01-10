package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.level.PatternDesc;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

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
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageRenderer.class.getName());

    public static final int H32_TILES_X = 32;
    public static final int H32_TILES_Y = 28;
    public static final int TILE_SIZE = 8;
    public static final int H32_HEIGHT = H32_TILES_Y * TILE_SIZE;

    private final GraphicsManager graphicsManager;

    private int backgroundPatternBase;
    private int trackPatternBase;
    private int playerPatternBase;
    private int scrollX = 0;
    private int scrollY = 0;

    private List<Sonic2SpecialStagePlayer> players = new ArrayList<>();

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

    /**
     * Renders the background plane (Plane B).
     *
     * @param mappings Enigma-decoded mapping data (16-bit words)
     * @param scrollX Horizontal scroll offset
     * @param scrollY Vertical scroll offset
     */
    public void renderBackground(byte[] mappings, int scrollX, int scrollY) {
        if (mappings == null || mappings.length < 2) {
            renderPlaceholderBackground();
            return;
        }

        graphicsManager.beginPatternBatch();

        int wordCount = mappings.length / 2;
        int mapWidth = H32_TILES_X;
        int mapHeight = Math.min(wordCount / mapWidth, H32_TILES_Y);

        for (int ty = 0; ty < mapHeight; ty++) {
            for (int tx = 0; tx < mapWidth; tx++) {
                int wordIndex = ty * mapWidth + tx;
                if (wordIndex * 2 + 1 >= mappings.length) continue;

                int word = ((mappings[wordIndex * 2] & 0xFF) << 8) |
                           (mappings[wordIndex * 2 + 1] & 0xFF);

                PatternDesc desc = new PatternDesc(word);

                int screenX = (tx * TILE_SIZE - scrollX) & 0xFF;
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

    /**
     * Renders the track plane (Plane A).
     *
     * @param trackFrameIndex Current track frame index (0-55)
     * @param frameTiles Decoded tile data from track frame (or null for placeholder)
     */
    public void renderTrack(int trackFrameIndex, int[] frameTiles) {
        if (frameTiles == null || frameTiles.length == 0) {
            renderPlaceholderTrack(trackFrameIndex);
            return;
        }

        graphicsManager.beginPatternBatch();

        int tileIndex = 0;
        for (int ty = 0; ty < H32_TILES_Y && tileIndex < frameTiles.length; ty++) {
            for (int tx = 0; tx < H32_TILES_X && tileIndex < frameTiles.length; tx++) {
                int word = frameTiles[tileIndex++];

                if (word == 0) continue;

                PatternDesc desc = new PatternDesc(word);
                int patternId = desc.getPatternIndex() + trackPatternBase;

                int screenX = tx * TILE_SIZE;
                int screenY = (H32_TILES_Y - 1 - ty) * TILE_SIZE;

                graphicsManager.renderPatternWithId(patternId, desc, screenX, screenY);
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
     * @param scrollTable The skydome scroll delta table
     * @param frameCounter Current frame number for animation
     */
    public void applySkydomeScroll(byte[] scrollTable, int frameCounter) {
    }

    public void setScrollX(int scrollX) {
        this.scrollX = scrollX;
    }

    public void setScrollY(int scrollY) {
        this.scrollY = scrollY;
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
            basePattern += 0x60;
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
}


