package uk.co.jamesj999.sonic.level;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.Engine;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.PlayerSpriteArtProvider;
import uk.co.jamesj999.sonic.data.SpindashDustArtProvider;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.games.Sonic2;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.ShaderProgram;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.level.ParallaxManager;
import uk.co.jamesj999.sonic.level.objects.ObjectPlacementManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.PlaneSwitcherManager;
import uk.co.jamesj999.sonic.level.rings.RingManager;
import uk.co.jamesj999.sonic.level.rings.RingPlacementManager;
import uk.co.jamesj999.sonic.level.rings.RingRenderManager;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;
import uk.co.jamesj999.sonic.sprites.managers.SpindashDustManager;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.render.PlayerSpriteRenderer;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * Manages the loading and rendering of game levels.
 */
public class LevelManager {
    private static final Logger LOGGER = Logger.getLogger(LevelManager.class.getName());
    private static final float SWITCHER_DEBUG_R = 1.0f;
    private static final float SWITCHER_DEBUG_G = 0.55f;
    private static final float SWITCHER_DEBUG_B = 0.1f;
    private static final float SWITCHER_DEBUG_ALPHA = 0.25f;
    private static final int SWITCHER_DEBUG_HALF_THICKNESS = 1;
    private static LevelManager levelManager;
    private Level level;
    private Game game;
    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final SpriteManager spriteManager = SpriteManager.getInstance();
    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final List<List<LevelData>> levels = new ArrayList<>();
    private int currentAct = 0;
    private int currentZone = 0;
    private int frameCounter = 0;
    private ObjectPlacementManager objectPlacementManager;
    private PlaneSwitcherManager planeSwitcherManager;
    private RingPlacementManager ringPlacementManager;
    private RingRenderManager ringRenderManager;
    private RingManager ringManager;

    private final ParallaxManager parallaxManager = ParallaxManager.getInstance();

    /**
     * Private constructor for Singleton pattern.
     */
    protected LevelManager() {
        levels.add(List.of(LevelData.EMERALD_HILL_1, LevelData.EMERALD_HILL_2));
        levels.add(List.of(LevelData.CHEMICAL_PLANT_1, LevelData.CHEMICAL_PLANT_2));
        levels.add(List.of(LevelData.AQUATIC_RUIN_1, LevelData.AQUATIC_RUIN_2));
        levels.add(List.of(LevelData.CASINO_NIGHT_1, LevelData.CASINO_NIGHT_2));
        levels.add(List.of(LevelData.HILL_TOP_1, LevelData.HILL_TOP_2));
        levels.add(List.of(LevelData.MYSTIC_CAVE_1, LevelData.MYSTIC_CAVE_2));
        levels.add(List.of(LevelData.OIL_OCEAN_1, LevelData.OIL_OCEAN_2));
        levels.add(List.of(LevelData.METROPOLIS_1, LevelData.METROPOLIS_2, LevelData.METROPOLIS_3));
        levels.add(List.of(LevelData.SKY_CHASE));
        levels.add(List.of(LevelData.WING_FORTRESS));
        levels.add(List.of(LevelData.DEATH_EGG));
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
            parallaxManager.load(rom);
            game = new Sonic2(rom);
            AudioManager.getInstance().setRom(rom);
            AudioManager.getInstance().setSoundMap(game.getSoundMap());
            AudioManager.getInstance().resetRingSound();
            AudioManager.getInstance().playMusic(game.getMusicId(levelIndex));
            level = game.loadLevel(levelIndex);
            objectPlacementManager = new ObjectPlacementManager(level.getObjects());
            objectPlacementManager.reset(Camera.getInstance().getX());
            planeSwitcherManager = new PlaneSwitcherManager(objectPlacementManager);
            ringPlacementManager = new RingPlacementManager(level.getRings());
            ringPlacementManager.reset(Camera.getInstance().getX());
            RingSpriteSheet ringSpriteSheet = level.getRingSpriteSheet();
            if (ringSpriteSheet != null && ringSpriteSheet.getFrameCount() > 0) {
                ringRenderManager = new RingRenderManager(ringSpriteSheet);
                ringRenderManager.ensurePatternsCached(graphicsManager, level.getPatternCount());
            } else {
                ringRenderManager = null;
            }
            ringManager = new RingManager(ringPlacementManager, ringRenderManager);
            initPlayerSpriteArt();
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load level " + levelIndex, e);
            throw e;
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
            throw new IOException("Failed to load level due to unexpected error.", e);
        }
    }

    public void update() {
        if (objectPlacementManager != null) {
            objectPlacementManager.update(Camera.getInstance().getX());
        }
        if (ringManager != null) {
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
            ringManager.update(Camera.getInstance().getX(), playable, frameCounter + 1);
        }
    }

    public void applyPlaneSwitchers(AbstractPlayableSprite player) {
        if (planeSwitcherManager == null || player == null) {
            return;
        }
        planeSwitcherManager.update(player);
    }

    private void initPlayerSpriteArt() {
        if (!(game instanceof PlayerSpriteArtProvider provider)) {
            return;
        }
        Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        if (!(player instanceof AbstractPlayableSprite playable)) {
            return;
        }
        try {
            SpriteArtSet artSet = provider.loadPlayerSpriteArt(playable.getCode());
            if (artSet == null || artSet.bankSize() <= 0 || artSet.mappingFrames().isEmpty() || artSet.dplcFrames().isEmpty()) {
                playable.setSpriteRenderer(null);
                return;
            }
            PlayerSpriteRenderer renderer = new PlayerSpriteRenderer(artSet);
            renderer.ensureCached(graphicsManager);
            playable.setSpriteRenderer(renderer);
            playable.setMappingFrame(0);
            playable.setAnimationFrameCount(artSet.mappingFrames().size());
            playable.setAnimationProfile(artSet.animationProfile());
            playable.setAnimationSet(artSet.animationSet());
            playable.setAnimationId(0);
            playable.setAnimationFrameIndex(0);
            playable.setAnimationTick(0);
            initSpindashDust(playable);
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load player sprite art.", e);
        }
    }

    private void initSpindashDust(AbstractPlayableSprite playable) {
        if (!(game instanceof SpindashDustArtProvider dustProvider)) {
            playable.setSpindashDustManager(null);
            return;
        }
        try {
            SpriteArtSet dustArt = dustProvider.loadSpindashDustArt(playable.getCode());
            if (dustArt == null || dustArt.bankSize() <= 0 || dustArt.mappingFrames().isEmpty()
                    || dustArt.dplcFrames().isEmpty()) {
                playable.setSpindashDustManager(null);
                return;
            }
            PlayerSpriteRenderer dustRenderer = new PlayerSpriteRenderer(dustArt);
            dustRenderer.ensureCached(graphicsManager);
            playable.setSpindashDustManager(new SpindashDustManager(playable, dustRenderer));
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load spindash dust art.", e);
            playable.setSpindashDustManager(null);
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
                    drawChunk(commands, chunkDesc, x, y, true);
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

        frameCounter++;
        Camera camera = Camera.getInstance();

        int bgScrollY = (int) (camera.getY() * 0.1f);
        if (game != null) {
            int levelIdx = levels.get(currentZone).get(currentAct).getLevelIndex();
            int[] scroll = game.getBackgroundScroll(levelIdx, camera.getX(), camera.getY());
            bgScrollY = scroll[1];
        }

        parallaxManager.update(currentZone, currentAct, camera, frameCounter, bgScrollY);
        List<GLCommand> commands = new ArrayList<>();

        // Draw Background (Layer 1)
        drawLayer(commands, 1, camera, 0.5f, 0.1f);

        // Draw Foreground (Layer 0)
        drawLayer(commands, 0, camera, 1.0f, 1.0f);

        // Register all collected drawing commands with the graphics manager
        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, commands));

        if (ringManager != null) {
            ringManager.draw(frameCounter);
        }

        if (objectPlacementManager != null && configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            List<GLCommand> objectCommands = new ArrayList<>();
            List<GLCommand> switcherLineCommands = new ArrayList<>();
            List<GLCommand> switcherAreaCommands = new ArrayList<>();
            for (ObjectSpawn spawn : objectPlacementManager.getActiveSpawns()) {
                objectCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                        -1,
                        GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                        1f, 0f, 1f,
                        spawn.x(), spawn.y(), 0, 0));
                appendPlaneSwitcherDebug(spawn, switcherLineCommands, switcherAreaCommands);
            }
            if (!switcherAreaCommands.isEmpty()) {
                for (GLCommand command : switcherAreaCommands) {
                    graphicsManager.registerCommand(command);
                }
            }
            if (!switcherLineCommands.isEmpty()) {
                graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, switcherLineCommands));
            }
            if (!objectCommands.isEmpty()) {
                graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, objectCommands));
            }
        }

        if (ringManager != null && configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            Collection<RingSpawn> rings = ringManager.getActiveSpawns();
            if (!rings.isEmpty()) {
                if (ringRenderManager == null) {
                    List<GLCommand> ringCommands = new ArrayList<>();
                    for (RingSpawn ring : rings) {
                        if (!ringManager.isRenderable(ring, frameCounter)) {
                            continue;
                        }
                        ringCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                                -1,
                                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                                1f, 0.85f, 0.1f,
                                ring.x(), ring.y(), 0, 0));
                    }
                    graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, ringCommands));
                } else {
                    PatternSpriteRenderer.FrameBounds bounds = ringRenderManager.getFrameBounds(frameCounter);
                    List<GLCommand> boxCommands = new ArrayList<>();
                    List<GLCommand> centerCommands = new ArrayList<>();
                    int crossHalf = 2;

                    for (RingSpawn ring : rings) {
                        if (!ringManager.isRenderable(ring, frameCounter)) {
                            continue;
                        }
                        int centerX = ring.x();
                        int centerY = ring.y();
                        int left = centerX + bounds.minX();
                        int right = centerX + bounds.maxX();
                        int top = centerY + bounds.minY();
                        int bottom = centerY + bounds.maxY();

                        // Bounding box (4 line segments)
                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, top, 0, 0));
                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, top, 0, 0));

                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, top, 0, 0));
                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, bottom, 0, 0));

                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, bottom, 0, 0));
                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, bottom, 0, 0));

                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, bottom, 0, 0));
                        boxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, top, 0, 0));

                        // Center cross
                        centerCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX - crossHalf, centerY, 0, 0));
                        centerCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX + crossHalf, centerY, 0, 0));
                        centerCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX, centerY - crossHalf, 0, 0));
                        centerCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX, centerY + crossHalf, 0, 0));
                    }

                    if (!boxCommands.isEmpty()) {
                        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, boxCommands));
                    }
                    if (!centerCommands.isEmpty()) {
                        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, centerCommands));
                    }
                }
            }
        }

        if (configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            if (player instanceof AbstractPlayableSprite playable) {
                drawPlayableSpriteBounds(playable);
            }
        }
    }

    private void drawLayer(List<GLCommand> commands, int layerIndex, Camera camera, float parallaxX, float parallaxY) {
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        int bgCameraX = (int) (cameraX * parallaxX);
        int bgCameraY = (int) (cameraY * parallaxY);

        if (layerIndex == 1 && game != null) {
            int levelIdx = levels.get(currentZone).get(currentAct).getLevelIndex();
            int[] scroll = game.getBackgroundScroll(levelIdx, cameraX, cameraY);
            bgCameraY = scroll[1];
        }

        int[] hScroll = (layerIndex == 1) ? parallaxManager.getHScroll() : null;

        int drawX = bgCameraX - (bgCameraX % LevelConstants.CHUNK_WIDTH);
        int drawY = bgCameraY - (bgCameraY % LevelConstants.CHUNK_HEIGHT);

        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        int xStart = drawX;
        int xEnd = bgCameraX + cameraWidth;

        int yStart = drawY;
        int yEnd = bgCameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT;

        for (int y = yStart; y <= yEnd; y += LevelConstants.CHUNK_HEIGHT) {
            int rowXStart = xStart;
            int rowXEnd = xEnd;

            if (hScroll != null) {
                int screenY = y - bgCameraY;
                int localMin = Integer.MAX_VALUE;
                int localMax = Integer.MIN_VALUE;

                // Check scroll values for the scanlines covered by this chunk row
                for (int i = 0; i < LevelConstants.CHUNK_HEIGHT; i++) {
                    int line = screenY + i;
                    if (line < 0) line = 0;
                    if (line >= ParallaxManager.VISIBLE_LINES) line = ParallaxManager.VISIBLE_LINES - 1;

                    short val = (short)(hScroll[line] & 0xFFFF);
                    if (val < localMin) localMin = val;
                    if (val > localMax) localMax = val;
                }

                rowXStart = -localMax;
                rowXEnd = cameraWidth - localMin;

                // Align to chunk boundary
                rowXStart -= (rowXStart % LevelConstants.CHUNK_WIDTH + LevelConstants.CHUNK_WIDTH) % LevelConstants.CHUNK_WIDTH;

                // Add buffer
                rowXStart -= LevelConstants.CHUNK_WIDTH;
                rowXEnd += LevelConstants.CHUNK_WIDTH;
            }

            for (int x = rowXStart; x <= rowXEnd; x += LevelConstants.CHUNK_WIDTH) {
                // Handle wrapping for X
                int wrappedX = x;
                wrappedX = ((wrappedX % levelWidth) + levelWidth) % levelWidth;

                // Handle wrapping for Y
                int wrappedY = y;
                if (layerIndex == 1) {
                    // Background loops vertically
                    wrappedY = ((wrappedY % levelHeight) + levelHeight) % levelHeight;
                } else {
                    // Foreground Clamps
                    if (wrappedY < 0 || wrappedY >= levelHeight) continue;
                }

                Block block = getBlockAtPosition((byte) layerIndex, wrappedX, wrappedY);
                if (block != null) {
                    int xBlockBit = (wrappedX % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH;
                    int yBlockBit = (wrappedY % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT;

                    ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);

                    // Calculate screen coordinates
                    int screenX = x - bgCameraX;
                    int screenY = y - bgCameraY;

                    // Convert to absolute coordinates expected by renderPattern (which subtracts cameraX/Y)
                    int renderX = screenX + cameraX;
                    int renderY = screenY + cameraY;

                    // Draw collision only for foreground (Layer 0)
                    drawChunk(commands, chunkDesc, renderX, renderY, layerIndex == 0, hScroll, screenY, bgCameraX);
                }
            }
        }
    }

    /**
     * Draws a chunk of the level based on the provided chunk description.
     *
     * @param commands  the list of GLCommands to add to
     * @param chunkDesc the description of the chunk to draw
     * @param x         the x-coordinate to draw the chunk at
     * @param y         the y-coordinate to draw the chunk at
     * @param drawCollision whether to draw collision debug info
     */
    private void drawChunk(List<GLCommand> commands, ChunkDesc chunkDesc, int x, int y, boolean drawCollision) {
        drawChunk(commands, chunkDesc, x, y, drawCollision, null, 0, 0);
    }

    private void drawChunk(List<GLCommand> commands, ChunkDesc chunkDesc, int x, int y, boolean drawCollision, int[] hScroll, int screenY, int baseBgCameraX) {
        int chunkIndex = chunkDesc.getChunkIndex();
        if (chunkIndex >= level.getChunkCount()) {
            LOGGER.fine("Chunk index " + chunkIndex + " out of bounds; defaulting to 0.");
            chunkIndex = 0;
        }

        Chunk chunk = level.getChunk(chunkIndex);
        if (chunk == null) {
            LOGGER.warning("Chunk at index " + chunkIndex + " is null.");
            return;
        }

        boolean chunkHFlip = chunkDesc.getHFlip();
        boolean chunkVFlip = chunkDesc.getVFlip();

        for (int cY = 0; cY < 2; cY++) {
            for (int cX = 0; cX < 2; cX++) {
                int logicalX = chunkHFlip ? 1 - cX : cX;
                int logicalY = chunkVFlip ? 1 - cY : cY;

                PatternDesc patternDesc = chunk.getPatternDesc(logicalX, logicalY);

                int newIndex = patternDesc.get();
                if (chunkHFlip) {
                    newIndex ^= 0x800;
                }
                if (chunkVFlip) {
                    newIndex ^= 0x1000;
                }
                PatternDesc newPatternDesc = new PatternDesc(newIndex);

                int drawX = x + (cX * Pattern.PATTERN_WIDTH);
                int drawY = y + (cY * Pattern.PATTERN_HEIGHT);

                if (hScroll != null) {
                    int line = screenY + (cY * Pattern.PATTERN_HEIGHT);
                    if (line < 0) line = 0;
                    if (line >= ParallaxManager.VISIBLE_LINES) line = ParallaxManager.VISIBLE_LINES - 1;

                    short scroll = (short) (hScroll[line] & 0xFFFF);
                    drawX = drawX + scroll + baseBgCameraX;
                }

                graphicsManager.renderPattern(newPatternDesc, drawX, drawY);
            }
        }

        // Handle primary and secondary collisions
        if (drawCollision) {
            processCollisionMode(commands, chunkDesc, chunk, true, x, y);
            processCollisionMode(commands, chunkDesc, chunk, false, x, y);
        }
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
        if (!configService.getBoolean(SonicConfiguration.DEBUG_COLLISION_VIEW_ENABLED)) {
            return;
        }

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

        // Disable texturing and shaders for drawing solid colors
        ShaderProgram shaderProgram = graphicsManager.getShaderProgram();
        int shaderProgramId = 0;
        if (shaderProgram != null) {
            shaderProgramId = shaderProgram.getProgramId();
        }
        commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, 0));
        commands.add(new GLCommand(GLCommand.CommandType.DISABLE, GL2.GL_TEXTURE_2D));

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

                commands.add(new GLCommand(
                        GLCommand.CommandType.RECTI,
                        GL2.GL_2D,
                        r,
                        g,
                        b,
                        drawStartX,
                        drawEndY,
                        drawEndX,
                        drawStartY
                ));
            }
        }
        // Re-enable texturing and shader for subsequent rendering
        commands.add(new GLCommand(GLCommand.CommandType.ENABLE, GL2.GL_TEXTURE_2D));
        if (shaderProgramId != 0) {
            commands.add(new GLCommand(GLCommand.CommandType.USE_PROGRAM, shaderProgramId));
        }
    }

    private void drawPlayableSpriteBounds(AbstractPlayableSprite sprite) {
        PlayerSpriteRenderer renderer = sprite.getSpriteRenderer();
        if (renderer == null) {
            return;
        }

        boolean hFlip = Direction.LEFT.equals(sprite.getDirection());
        SpritePieceRenderer.FrameBounds mappingBounds = renderer.getFrameBounds(sprite.getMappingFrame(), hFlip, false);

        int centerX = sprite.getCentreX();
        int centerY = sprite.getCentreY();
        List<GLCommand> commands = new ArrayList<>();

        if (mappingBounds.width() > 0 && mappingBounds.height() > 0) {
            int mapLeft = centerX + mappingBounds.minX();
            int mapRight = centerX + mappingBounds.maxX();
            int mapTop = centerY + mappingBounds.minY();
            int mapBottom = centerY + mappingBounds.maxY();
            appendBox(commands, mapLeft, mapTop, mapRight, mapBottom, 0.1f, 0.85f, 1f);
        }

        int hitLeft = centerX - (sprite.getWidth() / 2);
        int hitRight = centerX + (sprite.getWidth() / 2);
        int hitTop = centerY - (sprite.getHeight() / 2);
        int hitBottom = centerY + (sprite.getHeight() / 2);
        appendBox(commands, hitLeft, hitTop, hitRight, hitBottom, 1f, 0.8f, 0.1f);

        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, commands));
        }
    }

    private void appendPlaneSwitcherDebug(ObjectSpawn spawn,
                                          List<GLCommand> lineCommands,
                                          List<GLCommand> areaCommands) {
        if (spawn.objectId() != PlaneSwitcherManager.OBJECT_ID) {
            return;
        }
        int subtype = spawn.subtype();
        int halfSpan = PlaneSwitcherManager.decodeHalfSpan(subtype);
        boolean horizontal = PlaneSwitcherManager.isHorizontal(subtype);
        int x = spawn.x();
        int y = spawn.y();

        if (horizontal) {
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x - halfSpan, y, 0, 0));
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x + halfSpan, y, 0, 0));

            areaCommands.add(new GLCommand(GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B, SWITCHER_DEBUG_ALPHA,
                    x - halfSpan, y - SWITCHER_DEBUG_HALF_THICKNESS,
                    x + halfSpan, y + SWITCHER_DEBUG_HALF_THICKNESS));
        } else {
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x, y - halfSpan, 0, 0));
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x, y + halfSpan, 0, 0));

            areaCommands.add(new GLCommand(GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B, SWITCHER_DEBUG_ALPHA,
                    x - SWITCHER_DEBUG_HALF_THICKNESS, y - halfSpan,
                    x + SWITCHER_DEBUG_HALF_THICKNESS, y + halfSpan));
        }
    }

    private void appendBox(
            List<GLCommand> commands,
            int left,
            int top,
            int right,
            int bottom,
            float r,
            float g,
            float b
    ) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, top, 0, 0));

        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, top, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, bottom, 0, 0));

        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, right, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, bottom, 0, 0));

        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, bottom, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, left, top, 0, 0));
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

		int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
		int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

		// Handle wrapping for X
		int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;

		// Handle wrapping for Y
		int wrappedY = y;
		if (layer == 1) {
			// Background loops vertically
			wrappedY = ((wrappedY % levelHeight) + levelHeight) % levelHeight;
		} else {
			// Foreground Clamps
			if (wrappedY < 0 || wrappedY >= levelHeight) return null;
		}

        Map map = level.getMap();
		int mapX = wrappedX / LevelConstants.BLOCK_WIDTH;
		int mapY = wrappedY / LevelConstants.BLOCK_HEIGHT;

        byte value = map.getValue(layer, mapX, mapY);

        // Mask the value to treat the byte as unsigned
        int blockIndex = value & 0xFF;

        if (blockIndex >= level.getBlockCount()) {
            return null;
        }

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

		int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
		int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;
		int wrappedY = y;

		if (layer == 1) {
			int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;
			wrappedY = ((y % levelHeight) + levelHeight) % levelHeight;
		}

        ChunkDesc chunkDesc = block.getChunkDesc((wrappedX % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH,(wrappedY % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT);
        return chunkDesc;
    }

    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, byte layer) {
        try {
            if (chunkDesc == null) {
                return null;
            }
            CollisionMode collisionMode;
            if (layer == 0) {
                collisionMode = chunkDesc.getPrimaryCollisionMode();
            } else {
                collisionMode = chunkDesc.getSecondaryCollisionMode();
            }

            if (CollisionMode.NO_COLLISION.equals(collisionMode)) {
                return null;
            }

            Chunk chunk = level.getChunk(chunkDesc.getChunkIndex());
            if (chunk == null) {
                return null;
            }
            if (layer == 0) {
                return level.getSolidTile(chunk.getSolidTileIndex());
            } else {
                return level.getSolidTile(chunk.getSolidTileAltIndex());
            }
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // Deprecated or convenience method for backward compatibility if needed,
    // but better to remove or update callers.
    // For now, let's overload it to default to Layer 0 (Primary) if not specified,
    // or we can force update. GroundSensor is the main one.
    // I'll leave a deprecated one just in case, or remove it.
    // GroundSensor calls it. I should update GroundSensor.
    // But I can't leave this here without updating GroundSensor first or it won't compile?
    // Wait, I can overload.
    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc) {
        return getSolidTileForChunkDesc(chunkDesc, (byte) 0);
    }

    /**
     * Returns the current level.
     *
     * @return the current Level object
     */
    public Level getCurrentLevel() {
        return level;
    }

    public Collection<ObjectSpawn> getActiveObjectSpawns() {
        if (objectPlacementManager == null) {
            return List.of();
        }
        return objectPlacementManager.getActiveSpawns();
    }

    public void loadCurrentLevel() {
        try {
            LevelData levelData = levels.get(currentZone).get(currentAct);
            loadLevel(levelData.getLevelIndex());
            frameCounter = 0;
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            player.setX((short) levelData.getStartXPos());
            player.setY((short) levelData.getStartYPos());
            if (player instanceof AbstractPlayableSprite) {
                ((AbstractPlayableSprite) player).setXSpeed((short) 0);
                ((AbstractPlayableSprite) player).setYSpeed((short) 0);
                ((AbstractPlayableSprite) player).setGSpeed((short) 0);
                ((AbstractPlayableSprite) player).setAir(false);
                ((AbstractPlayableSprite) player).setRolling(false);
                player.setLayer((byte) 0);
                ((AbstractPlayableSprite) player).setHighPriority(false);
                Camera camera = Camera.getInstance();
                camera.setFocusedSprite((AbstractPlayableSprite) player);
                Level currentLevel = getCurrentLevel();
                if (currentLevel != null) {
                    camera.setMinX((short) currentLevel.getMinX());
                    camera.setMaxX((short) currentLevel.getMaxX());
                    camera.setMinY((short) currentLevel.getMinY());
                    camera.setMaxY((short) currentLevel.getMaxY());
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void nextAct() throws IOException  {
        currentAct++;
        if (currentAct >= levels.get(currentZone).size()) {
            currentAct = 0;
        }
        loadCurrentLevel();
    }

    public void loadZoneAndAct(int zone, int act) throws IOException {
        currentAct = act;
        currentZone = zone;
        loadCurrentLevel();
    }

    public void nextZone() throws IOException  {
        currentZone++;
        if (currentZone >= levels.size()) {
            currentZone = 0;
        }
        currentAct = 0;
        loadCurrentLevel();
    }

    public void loadZone(int zone) throws IOException {
        currentZone = zone;
        currentAct = 0;
        loadCurrentLevel();
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

    public void setClearColor(GL2 gl) {
        // In Sonic 2, Palette 1 is the level palette (Palette 0 is character).
        Palette.Color backgroundColor = level.getPalette(1).getColor(0);
        gl.glClearColor(
                Byte.toUnsignedInt(backgroundColor.r) / 255f,
                Byte.toUnsignedInt(backgroundColor.g) / 255f,
                Byte.toUnsignedInt(backgroundColor.b) / 255f,
                1.0f);
    }
}
