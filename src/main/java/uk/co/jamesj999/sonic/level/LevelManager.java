package uk.co.jamesj999.sonic.level;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.Engine;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.games.Sonic2;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * Manages the loading and rendering of game levels.
 */
public class LevelManager {
    private static final Logger LOGGER = Logger.getLogger(LevelManager.class.getName());
    private static LevelManager levelManager;
    private Level level;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final SpriteManager spriteManager = SpriteManager.getInstance();


    /**
     * Private constructor for Singleton pattern.
     */
    private LevelManager() {
        // No-op
    }

    /**
     * Loads the specified level into memory.
     *
     * @param levelIndex the index of the level to load
     * @throws IOException if an I/O error occurs while loading the level
     */
    public void loadLevel(int levelIndex) throws IOException {
        try {
            Rom rom = new Rom();
            rom.open(SonicConfigurationService.getInstance().getString(SonicConfiguration.ROM_FILENAME));
            Game game = new Sonic2(rom);
            level = game.loadLevel(levelIndex);
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load level " + levelIndex, e);
            throw e;
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
            throw new IOException("Failed to load level due to unexpected error.", e);
        }
    }

    /**
     * Debug Functionality to print each pattern to the screen.
     */
    public void drawAllPatterns() {
        if (level == null) {
            LOGGER.warning("No level loaded to draw.");
            return;
        }

        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        // Calculate drawing bounds, adjusted to include partially visible tiles
        int drawX = cameraX;
        int drawY = cameraY;
        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        int xLeftBound = Math.max(drawX, 0);
        int xRightBound = Math.min(cameraX + cameraWidth, levelWidth);
        int yTopBound = Math.max(drawY, 0);
        int yBottomBound = Math.min(cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT, levelHeight);

        List<GLCommand> commands = new ArrayList<>();

        // Iterate over the visible area of the level
        int count = 0;
        int maxCount = level.getPatternCount();

        if (Engine.debugOption.ordinal() > LevelConstants.MAX_PALETTES) {
            Engine.debugOption = DebugOption.A;
        }

        for (int y = yTopBound; y <= yBottomBound; y += Pattern.PATTERN_HEIGHT) {
            for (int x = xLeftBound; x <= xRightBound; x += Pattern.PATTERN_WIDTH) {
                if (count < maxCount) {
                    PatternDesc pDesc = new PatternDesc();
                    pDesc.setPaletteIndex(Engine.debugOption.ordinal());
                    pDesc.setPatternIndex(count);
                    graphicsManager.renderPattern(pDesc, x, y);
                    count++;
                }
            }
        }

        // Register all collected drawing commands with the graphics manager
        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, commands));

    }

    /**
     * Debug Functionality to print each ChunkDesc to the screen.
     */
    public void drawAllChunks() {
        if (level == null) {
            LOGGER.warning("No level loaded to draw.");
            return;
        }

        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        // Calculate drawing bounds, adjusted to include partially visible tiles
        int drawX = cameraX;
        int drawY = cameraY + Pattern.PATTERN_HEIGHT;
        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        int xLeftBound = Math.max(drawX, 0);
        int xRightBound = Math.min(cameraX + cameraWidth, levelWidth);
        int yTopBound = Math.max(drawY, 0);
        int yBottomBound = Math.min(cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT, levelHeight);

        List<GLCommand> commands = new ArrayList<>();

        // Iterate over the visible area of the level
        int count = 0;
        int maxCount = level.getChunkCount();

        for (int y = yTopBound; y <= yBottomBound; y += Chunk.CHUNK_HEIGHT) {
            for (int x = xLeftBound; x <= xRightBound; x += Chunk.CHUNK_WIDTH) {
                if (count < maxCount) {
                    ChunkDesc chunkDesc = new ChunkDesc();
                    chunkDesc.setChunkIndex(count);
                    drawChunk(commands, chunkDesc, x, y);
                    count++;
                }
            }
        }

        // Register all collected drawing commands with the graphics manager
        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, commands));

    }

    /**
     * Renders the current level by processing and displaying collision data.
     * This is currently for debugging purposes to visualize collision areas.
     */
    public void draw() {
        if (level == null) {
            LOGGER.warning("No level loaded to draw.");
            return;
        }

        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        Sprite sprite = spriteManager.getSprite(
                SonicConfigurationService.getInstance().getString(SonicConfiguration.MAIN_CHARACTER_CODE)
        );
        if (sprite == null) {
            LOGGER.warning("Main character sprite not found.");
            return;
        }
        byte currentLayer = sprite.getLayer();

        // Calculate drawing bounds, adjusted to include partially visible tiles
        int drawX = cameraX - (cameraX % LevelConstants.CHUNK_WIDTH);
        int drawY = cameraY - (cameraY % LevelConstants.CHUNK_HEIGHT);
        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        int xLeftBound = Math.max(drawX, 0);
        int xRightBound = Math.min(cameraX + cameraWidth, levelWidth);
        int yTopBound = Math.max(drawY, 0);
        int yBottomBound = Math.min(cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT, levelHeight);

        List<GLCommand> commands = new ArrayList<>();

        // Iterate over the visible area of the level
        for (int y = yTopBound; y <= yBottomBound; y += LevelConstants.CHUNK_HEIGHT) {
            for (int x = xLeftBound; x <= xRightBound; x += LevelConstants.CHUNK_WIDTH) {
                Block block = getBlockAtPosition(currentLayer, x, y);
                if (block != null) {
                    int xBlockBit = (x % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH;
                    int yBlockBit = (y % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT;

                    ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);
                    drawChunk(commands, chunkDesc, x, y);
                }
            }
        }

        // Register all collected drawing commands with the graphics manager
        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, commands));
    }

    /**
     * Draws a chunk of the level based on the provided chunk description.
     *
     * @param commands  the list of GLCommands to add to
     * @param chunkDesc the description of the chunk to draw
     * @param x         the x-coordinate to draw the chunk at
     * @param y         the y-coordinate to draw the chunk at
     */
    private void drawChunk(List<GLCommand> commands, ChunkDesc chunkDesc, int x, int y) {
        int chunkIndex = chunkDesc.getChunkIndex();
        if (chunkIndex >= level.getChunkCount()) {
            LOGGER.warning("Chunk index " + chunkIndex + " out of bounds; defaulting to 0.");
            chunkIndex = 0;
        }

        Chunk chunk = level.getChunk(chunkIndex);
        if (chunk == null) {
            LOGGER.warning("Chunk at index " + chunkIndex + " is null.");
            return;
        }

        for (int cY = 0; cY < 2; cY++) {
            for (int cX = 0; cX < 2; cX++) {
                PatternDesc patternDesc = chunk.getPatternDesc(cX, cY);
                graphicsManager.renderPattern(patternDesc, x + (cX * Pattern.PATTERN_WIDTH), y + (cY * Pattern.PATTERN_HEIGHT));
            }
        }

        // Handle primary and secondary collisions
        processCollisionMode(commands, chunkDesc, chunk, true, x, y);
        processCollisionMode(commands, chunkDesc, chunk, false, x, y);
    }

    /**
     * Processes and renders collision modes for a chunk.
     *
     * @param commands  the list of GLCommands to add to
     * @param chunkDesc the description of the chunk
     * @param chunk     the chunk data
     * @param isPrimary whether to process the primary collision mode
     * @param x         the x-coordinate
     * @param y         the y-coordinate
     */
    private void processCollisionMode(
            List<GLCommand> commands,
            ChunkDesc chunkDesc,
            Chunk chunk,
            boolean isPrimary,
            int x,
            int y
    ) {
        CollisionMode collisionMode = isPrimary
                ? chunkDesc.getPrimaryCollisionMode()
                : chunkDesc.getSecondaryCollisionMode();
        if (collisionMode == CollisionMode.NO_COLLISION) {
            return;
        }

        int solidTileIndex = isPrimary
                ? chunk.getSolidTileIndex()
                : chunk.getSolidTileAltIndex();
        SolidTile solidTile = level.getSolidTile(solidTileIndex);
        if (solidTile == null) {
            LOGGER.warning("SolidTile at index " + solidTileIndex + " is null.");
            return;
        }

        // Determine color based on collision mode
        float r, g, b;
        if (isPrimary) {
            r = 1.0f; // White color for primary collision
            g = 1.0f;
            b = 1.0f;
        } else {
            r = 0.5f; // Gray color for secondary collision
            g = 0.5f;
            b = 0.5f;
        }

        boolean hFlip = chunkDesc.getHFlip();
        boolean yFlip = chunkDesc.getVFlip(); // Using VFlip as per your current code

        // Iterate over each pixel column in the tile
        for (int i = 0; i < LevelConstants.CHUNK_WIDTH; i++) {
            int tileIndex = hFlip ? (LevelConstants.CHUNK_HEIGHT - 1 - i) : i;
            int height = solidTile.getHeightAt((byte) tileIndex);

            if (height > 0) {
                int drawStartX = x + i;
                int drawEndX = drawStartX + 1;

                int drawStartY;
                int drawEndY;

                // Adjust drawing coordinates based on vertical flip
                if (yFlip) {
                    // When yFlip is true, y coordinates increase downwards in the rendering context
                    drawStartY = y - LevelConstants.CHUNK_HEIGHT;
                    drawEndY = drawStartY + height;
                } else {
                    // Normal rendering, y decreases upwards
                    drawStartY = y;
                    drawEndY = y - height;
                }

                /*commands.add(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        GL2.GL_2D,
                        r,
                        g,
                        b,
                        drawStartX,
                        drawEndY,
                        drawEndX,
                        drawStartY
                ));*/
            }
        }
    }

    /**
     * Retrieves the block at a given position.
     *
     * @param layer the layer to retrieve the block from
     * @param x     the x-coordinate in pixels
     * @param y     the y-coordinate in pixels
     * @return the Block at the specified position, or null if not found
     */
    private Block getBlockAtPosition(byte layer, int x, int y) {
        if (level == null || level.getMap() == null) {
            LOGGER.warning("Level or Map is not initialized.");
            return null;
        }

        Map map = level.getMap();
        int mapX = x / LevelConstants.BLOCK_WIDTH;
        int mapY = y / LevelConstants.BLOCK_HEIGHT;

        byte value = map.getValue(layer, mapX, mapY);
        if (value < 0) {
            return null;
        }

        // Mask the value to treat the byte as unsigned
        int blockIndex = value & 0xFF;
        Block block = level.getBlock(blockIndex);
        if (block == null) {
            LOGGER.warning("Block at index " + blockIndex + " is null.");
        }

        return block;
    }

    public ChunkDesc getChunkDescAt(byte layer, int x, int y) {
        Block block = getBlockAtPosition(layer, x ,y);
        if(block == null) {
            return null;
        }
        ChunkDesc chunkDesc = block.getChunkDesc((x % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH,(y % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT);
        return chunkDesc;
    }

    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc) {
        try {
            if (chunkDesc == null) {
                return null;
            }
            Chunk chunk = level.getChunk(chunkDesc.getChunkIndex());
            if (chunk == null) {
                return null;
            }
            return level.getSolidTile(chunk.getSolidTileIndex());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Returns the current level.
     *
     * @return the current Level object
     */
    public Level getCurrentLevel() {
        return level;
    }

    /**
     * Returns the singleton instance of LevelManager.
     *
     * @return the singleton LevelManager instance
     */
    public static synchronized LevelManager getInstance() {
        if (levelManager == null) {
            levelManager = new LevelManager();
        }
        return levelManager;
    }
}
