package uk.co.jamesj999.sonic.level;

import uk.co.jamesj999.sonic.game.GameServices;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.Engine;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.AnimatedPaletteProvider;
import uk.co.jamesj999.sonic.data.AnimatedPatternProvider;
import uk.co.jamesj999.sonic.data.PlayerSpriteArtProvider;
import uk.co.jamesj999.sonic.data.SpindashDustArtProvider;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.GameModule;
import uk.co.jamesj999.sonic.game.GameModuleRegistry;
import uk.co.jamesj999.sonic.game.LevelEventProvider;
import uk.co.jamesj999.sonic.game.LevelState;
import uk.co.jamesj999.sonic.game.ObjectArtProvider;
import uk.co.jamesj999.sonic.game.RespawnState;
import uk.co.jamesj999.sonic.game.ZoneFeatureProvider;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArt;
import uk.co.jamesj999.sonic.debug.DebugObjectArtViewer;
import uk.co.jamesj999.sonic.debug.DebugOption;
import uk.co.jamesj999.sonic.debug.DebugOverlayManager;
import uk.co.jamesj999.sonic.debug.DebugOverlayPalette;
import uk.co.jamesj999.sonic.debug.DebugOverlayToggle;
import uk.co.jamesj999.sonic.level.objects.HudRenderManager;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.GLCommandGroup;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.graphics.GraphicsManager;
import uk.co.jamesj999.sonic.graphics.ShaderProgram;
import uk.co.jamesj999.sonic.graphics.WaterShaderProgram;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.graphics.PatternRenderCommand;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.level.render.BackgroundRenderer;
// import uk.co.jamesj999.sonic.level.ParallaxManager; -> Removed unused
import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;
import uk.co.jamesj999.sonic.level.rings.RingManager;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.animation.AnimatedPaletteManager;
import uk.co.jamesj999.sonic.level.animation.AnimatedPatternManager;
import uk.co.jamesj999.sonic.physics.CollisionSystem;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
import uk.co.jamesj999.sonic.sprites.art.SpriteArtSet;
import uk.co.jamesj999.sonic.sprites.managers.SpindashDustController;
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
    private static final float SWITCHER_DEBUG_ALPHA = 0.35f;
    private static final int OBJECT_PATTERN_BASE = 0x20000;
    private static LevelManager levelManager;
    private Level level;
    private Game game;
    private GameModule gameModule;

    public Game getGame() {
        return game;
    }

    public GameModule getGameModule() {
        return gameModule;
    }

    private final GraphicsManager graphicsManager = GraphicsManager.getInstance();
    private final SpriteManager spriteManager = SpriteManager.getInstance();
    private final SonicConfigurationService configService = SonicConfigurationService.getInstance();
    private final DebugOverlayManager overlayManager = GameServices.debugOverlay();
    private final List<List<LevelData>> levels = new ArrayList<>();
    private int currentAct = 0;
    private int currentZone = 0;
    private int frameCounter = 0;
    private ObjectManager objectManager;
    private RingManager ringManager;
    private ZoneFeatureProvider zoneFeatureProvider;
    private ObjectRenderManager objectRenderManager;
    private HudRenderManager hudRenderManager;
    private AnimatedPatternManager animatedPatternManager;
    private AnimatedPaletteManager animatedPaletteManager;
    private RespawnState checkpointState;
    private LevelState levelGamestate;

    private boolean specialStageRequestedFromCheckpoint;
    private boolean titleCardRequested;
    private int titleCardZone = -1;
    private int titleCardAct = -1;

    // Transition request flags (for fade-coordinated transitions)
    private boolean respawnRequested;
    private boolean nextActRequested;
    private boolean nextZoneRequested;

    // Background rendering support
    private final ParallaxManager parallaxManager = ParallaxManager.getInstance();
    private boolean useShaderBackground = true; // Feature flag for shader background

    // Pre-allocated lists for debug overlay rendering (avoids per-frame allocations)
    private final List<GLCommand> debugObjectCommands = new ArrayList<>(256);
    private final List<GLCommand> debugSwitcherLineCommands = new ArrayList<>(128);
    private final List<GLCommand> debugSwitcherAreaCommands = new ArrayList<>(128);
    private final List<GLCommand> debugRingCommands = new ArrayList<>(256);
    private final List<GLCommand> debugBoxCommands = new ArrayList<>(512);
    private final List<GLCommand> debugCenterCommands = new ArrayList<>(256);

    // Cached screen dimensions (avoids repeated config service lookups)
    private final int cachedScreenWidth = configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
    private final int cachedScreenHeight = configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

    // Camera reference for frustum culling
    private final Camera camera = Camera.getInstance();

    private enum TilePriorityPass {
        ALL,
        LOW_ONLY,
        HIGH_ONLY
    }

    /**
     * Checks if a point is within the visible camera frustum with optional padding.
     * Used to cull debug overlay commands for off-screen objects.
     *
     * @param x       world X coordinate
     * @param y       world Y coordinate
     * @param padding extra pixels around screen edges to include
     * @return true if the point is visible (or near-visible with padding)
     */
    private boolean isInCameraFrustum(int x, int y, int padding) {
        int camX = camera.getX();
        int camY = camera.getY();
        return x >= camX - padding && x <= camX + cachedScreenWidth + padding
                && y >= camY - padding && y <= camY + cachedScreenHeight + padding;
    }

    /**
     * Private constructor for Singleton pattern.
     * Zone list is lazily initialized from the current GameModule's ZoneRegistry.
     */
    protected LevelManager() {
        // Zones are loaded from ZoneRegistry in refreshZoneList()
    }

    /**
     * Refreshes the zone list from the current GameModule's ZoneRegistry.
     * Called during level loading to ensure zones match the current game.
     */
    private void refreshZoneList() {
        levels.clear();
        levels.addAll(gameModule.getZoneRegistry().getAllZones());
    }

    /**
     * Loads the specified level into memory.
     *
     * @param levelIndex the index of the level to load
     * @throws IOException if an I/O error occurs while loading the level
     */
    public void loadLevel(int levelIndex) throws IOException {
        try {
            Rom rom = GameServices.rom().getRom();
            parallaxManager.load(rom);
            gameModule = GameModuleRegistry.getCurrent();
            refreshZoneList();
            game = gameModule.createGame(rom);
            AudioManager audioManager = AudioManager.getInstance();
            audioManager.setAudioProfile(gameModule.getAudioProfile());
            audioManager.setRom(rom);
            audioManager.setSoundMap(game.getSoundMap());
            audioManager.resetRingSound();
            audioManager.playMusic(game.getMusicId(levelIndex));
            level = game.loadLevel(levelIndex);
            initAnimatedPatterns();
            initAnimatedPalettes();
            RomByteReader romReader = RomByteReader.fromRom(rom);
            TouchResponseTable touchResponseTable = gameModule.createTouchResponseTable(romReader);
            objectManager = new ObjectManager(level.getObjects(),
                    gameModule.createObjectRegistry(),
                    gameModule.getPlaneSwitcherObjectId(),
                    gameModule.getPlaneSwitcherConfig(),
                    touchResponseTable);
            // Wire up CollisionSystem with ObjectManager for unified collision pipeline
            CollisionSystem.getInstance().setObjectManager(objectManager);
            // Reset camera state from previous level (signpost may have locked it)
            Camera camera = Camera.getInstance();
            camera.setFrozen(false);
            camera.setMinX((short) 0);
            camera.setMaxX((short) (level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH));
            objectManager.reset(camera.getX());
            // Reset game-specific object state for new level
            gameModule.onLevelLoad();
            RingSpriteSheet ringSpriteSheet = level.getRingSpriteSheet();
            ringManager = new RingManager(level.getRings(), ringSpriteSheet, this, touchResponseTable);
            ringManager.reset(Camera.getInstance().getX());
            ringManager.ensurePatternsCached(graphicsManager, level.getPatternCount());
            // Initialize zone-specific features (CNZ bumpers, CPZ pylon, water surface, etc.)
            zoneFeatureProvider = gameModule.getZoneFeatureProvider();
            if (zoneFeatureProvider != null) {
                zoneFeatureProvider.initZoneFeatures(rom, level.getZoneIndex(), currentAct, camera.getX());
                // Cache zone feature patterns (water surface, etc.)
                int waterPatternBase = 0x30000; // High offset to avoid collision
                zoneFeatureProvider.ensurePatternsCached(graphicsManager, waterPatternBase);
            }
            initObjectArt();
            initPlayerSpriteArt();
            resetPlayerState();
            // Initialize checkpoint state for new level
            if (checkpointState == null) {
                checkpointState = gameModule.createRespawnState();
            }
            checkpointState.clear();
            levelGamestate = gameModule.createLevelState();

            // Initialize water system for this level
            // Use level.getZoneIndex() which returns the ROM zone ID (e.g., 0x0D for CPZ,
            // 0x0F for ARZ)
            WaterSystem waterSystem = WaterSystem.getInstance();
            waterSystem.loadForLevel(rom, level.getZoneIndex(), currentAct, level.getObjects());
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load level " + levelIndex, e);
            throw e;
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
            throw new IOException("Failed to load level due to unexpected error.", e);
        }
    }

    public void update() {
        // Update global oscillation values used by moving platforms, water surface,
        // etc.
        // Must be called centrally each frame to ensure continuous oscillation even
        // when
        // no platform objects are currently on-screen.
        uk.co.jamesj999.sonic.game.sonic2.OscillationManager.update(frameCounter);

        // Update dynamic water levels (for rising water in CPZ2, etc.)
        WaterSystem.getInstance().update();

        Sprite player = null;
        AbstractPlayableSprite playable = null;
        boolean needsPlayer = objectManager != null || ringManager != null;
        if (needsPlayer) {
            player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
        }
        if (objectManager != null) {
            objectManager.update(Camera.getInstance().getX(), playable, frameCounter + 1);
        }
        if (ringManager != null) {
            ringManager.update(Camera.getInstance().getX(), playable, frameCounter + 1);
            ringManager.updateLostRings(playable, frameCounter + 1);
        }
        // Update zone-specific features (CNZ bumpers, etc.)
        if (zoneFeatureProvider != null && level != null) {
            zoneFeatureProvider.update(playable, Camera.getInstance().getX(), level.getZoneIndex());
        }
        if (levelGamestate != null) {
            levelGamestate.update();
            if (levelGamestate.isTimeOver() && playable != null && !playable.getDead()) {
                playable.applyHurtOrDeath(0, AbstractPlayableSprite.DamageCause.TIME_OVER, false);
            }
        }

        // Update water state for player
        // Use level.getZoneIndex() which returns the ROM zone ID
        // Use getVisualWaterLevelY() so collision matches the oscillating water surface (CPZ2)
        WaterSystem waterSystem = WaterSystem.getInstance();
        if (level != null && playable != null && waterSystem.hasWater(level.getZoneIndex(), currentAct)) {
            int waterY = waterSystem.getVisualWaterLevelY(level.getZoneIndex(), currentAct);
            playable.updateWaterState(waterY);
        }
    }

    public void applyPlaneSwitchers(AbstractPlayableSprite player) {
        if (objectManager == null || player == null) {
            return;
        }
        objectManager.applyPlaneSwitchers(player);
    }

    public LevelState getLevelGamestate() {
        return levelGamestate;
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
            if (artSet == null || artSet.bankSize() <= 0 || artSet.mappingFrames().isEmpty()
                    || artSet.dplcFrames().isEmpty()) {
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

    private void resetPlayerState() {
        Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
        if (player instanceof AbstractPlayableSprite playable) {
            playable.resetState();
        }
    }

    private void initSpindashDust(AbstractPlayableSprite playable) {
        if (!(game instanceof SpindashDustArtProvider dustProvider)) {
            playable.setSpindashDustController(null);
            return;
        }
        try {
            SpriteArtSet dustArt = dustProvider.loadSpindashDustArt(playable.getCode());
            if (dustArt == null || dustArt.bankSize() <= 0 || dustArt.mappingFrames().isEmpty()
                    || dustArt.dplcFrames().isEmpty()) {
                playable.setSpindashDustController(null);
                return;
            }
            PlayerSpriteRenderer dustRenderer = new PlayerSpriteRenderer(dustArt);
            dustRenderer.ensureCached(graphicsManager);
            playable.setSpindashDustController(new SpindashDustController(playable, dustRenderer));
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load spindash dust art.", e);
            playable.setSpindashDustController(null);
        }
    }

    private void initObjectArt() {
        ObjectArtProvider provider = gameModule != null ? gameModule.getObjectArtProvider() : null;
        if (provider == null) {
            objectRenderManager = null;
            return;
        }

        try {
            int zoneIndex = level != null ? level.getZoneIndex() : -1;
            provider.loadArtForZone(zoneIndex);

            objectRenderManager = new ObjectRenderManager(provider);
            LOGGER.info("Initializing Object Art. Base Index: " + OBJECT_PATTERN_BASE);
            int hudBaseIndex = objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);

            hudRenderManager = new HudRenderManager(graphicsManager);
            // Wire up HUD to unified UI render pipeline
            if (graphicsManager.getUiRenderPipeline() != null) {
                graphicsManager.getUiRenderPipeline().setHudRenderManager(hudRenderManager);
            }

            Pattern[] hudDigits = provider.getHudDigitPatterns();
            if (hudDigits != null) {
                LOGGER.info("Cached " + hudDigits.length + " HUD Digit patterns at index " + hudBaseIndex);
                for (int i = 0; i < hudDigits.length; i++) {
                    graphicsManager.cachePatternTexture(hudDigits[i], hudBaseIndex + i);
                }
                hudRenderManager.setDigitPatternIndex(hudBaseIndex);

                int textBaseIndex = hudBaseIndex + hudDigits.length;
                Pattern[] hudText = provider.getHudTextPatterns();
                if (hudText != null) {
                    LOGGER.info("Cached " + hudText.length + " HUD Text patterns at index " + textBaseIndex);
                    for (int i = 0; i < hudText.length; i++) {
                        graphicsManager.cachePatternTexture(hudText[i], textBaseIndex + i);
                    }
                    hudRenderManager.setTextPatternIndex(textBaseIndex, hudText.length);

                    int livesBaseIndex = textBaseIndex + hudText.length;
                    Pattern[] hudLives = provider.getHudLivesPatterns();
                    if (hudLives != null) {
                        LOGGER.info("Cached " + hudLives.length + " HUD Lives patterns at index " + livesBaseIndex);
                        for (int i = 0; i < hudLives.length; i++) {
                            graphicsManager.cachePatternTexture(hudLives[i], livesBaseIndex + i);
                        }
                        hudRenderManager.setLivesPatternIndex(livesBaseIndex, hudLives.length);

                        int livesNumbersBaseIndex = livesBaseIndex + hudLives.length;
                        Pattern[] hudLivesNumbers = provider.getHudLivesNumbers();
                        if (hudLivesNumbers != null) {
                            LOGGER.info("Cached " + hudLivesNumbers.length + " HUD Lives Numbers patterns at index "
                                    + livesNumbersBaseIndex);
                            for (int i = 0; i < hudLivesNumbers.length; i++) {
                                graphicsManager.cachePatternTexture(hudLivesNumbers[i], livesNumbersBaseIndex + i);
                            }
                            hudRenderManager.setLivesNumbersPatternIndex(livesNumbersBaseIndex);
                        }
                    }
                }
            }

        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load object art.", e);
            objectRenderManager = null;
        }
    }

    private void initAnimatedPatterns() {
        animatedPatternManager = null;
        if (!(game instanceof AnimatedPatternProvider provider)) {
            return;
        }
        try {
            animatedPatternManager = provider.loadAnimatedPatternManager(level, level.getZoneIndex());
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load animated patterns.", e);
            animatedPatternManager = null;
        }
    }

    private void initAnimatedPalettes() {
        animatedPaletteManager = null;
        if (!(game instanceof AnimatedPaletteProvider provider)) {
            return;
        }
        try {
            animatedPaletteManager = provider.loadAnimatedPaletteManager(level, level.getZoneIndex());
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load animated palettes.", e);
            animatedPaletteManager = null;
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

        List<GLCommand> commands = new ArrayList<>(256);

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

        // Calculate drawing bounds
        int drawX = cameraX;
        int drawY = cameraY;
        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        int xLeftBound = Math.max(drawX, 0);
        int xRightBound = Math.min(cameraX + cameraWidth, levelWidth);
        int yTopBound = Math.max(drawY, 0);
        int yBottomBound = Math.min(cameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT, levelHeight);

        List<GLCommand> commands = new ArrayList<>(256);

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
        drawWithSpritePriority(null);
    }

    public void drawWithSpritePriority(SpriteManager spriteManager) {
        if (level == null) {
            LOGGER.warning("No level loaded to draw.");
            return;
        }

        frameCounter++;
        if (animatedPatternManager != null) {
            animatedPatternManager.update();
        }
        if (animatedPaletteManager != null && animatedPaletteManager != animatedPatternManager) {
            animatedPaletteManager.update();
        }
        Camera camera = Camera.getInstance();

        int bgScrollY = (int) (camera.getY() * 0.1f);
        if (game != null) {
            int levelIdx = levels.get(currentZone).get(currentAct).getLevelIndex();
            int[] scroll = game.getBackgroundScroll(levelIdx, camera.getX(), camera.getY());
            bgScrollY = scroll[1];
        }

        parallaxManager.update(currentZone, currentAct, camera, frameCounter, bgScrollY);
        List<GLCommand> collisionCommands = new ArrayList<>(256);

        // Update water shader state before rendering level
        updateWaterShaderState(camera);

        // Draw Background (Layer 1)
        if (useShaderBackground && graphicsManager.getBackgroundRenderer() != null) {
            renderBackgroundShader(collisionCommands, bgScrollY);
        }

        // Draw Foreground (Layer 0) low-priority pass - batched for performance
        graphicsManager.beginPatternBatch();
        drawLayer(collisionCommands, 0, camera, 1.0f, 1.0f, TilePriorityPass.LOW_ONLY, true, false);
        graphicsManager.flushPatternBatch();

        // Render collision debug overlay on top of foreground tiles
        if (!collisionCommands.isEmpty() && overlayManager.isEnabled(DebugOverlayToggle.COLLISION_VIEW)) {
            for (GLCommand cmd : collisionCommands) {
                graphicsManager.registerCommand(cmd);
            }
        }

        graphicsManager.beginPatternBatch();
        if (ringManager != null) {
            ringManager.draw(frameCounter);
            ringManager.drawLostRings(frameCounter);
        }

        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            if (spriteManager != null) {
                spriteManager.drawPriorityBucket(bucket, false);
            }
            if (objectManager != null) {
                objectManager.drawPriorityBucket(bucket, false);
            }
        }
        graphicsManager.flushPatternBatch();

        // Draw Foreground (Layer 0) high-priority pass - batched for performance
        // Note: drawCollision=false so commands list is not used
        graphicsManager.beginPatternBatch();
        drawLayer(null, 0, camera, 1.0f, 1.0f, TilePriorityPass.HIGH_ONLY, false, false);
        graphicsManager.flushPatternBatch();

        graphicsManager.beginPatternBatch();
        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            if (spriteManager != null) {
                spriteManager.drawPriorityBucket(bucket, true);
            }
            if (objectManager != null) {
                objectManager.drawPriorityBucket(bucket, true);
            }
        }
        graphicsManager.flushPatternBatch();

        // Draw water surface sprites at the water line (CPZ2, ARZ1, ARZ2)
        // Rendered last (after all sprites and tiles) so it appears in front of everything
        if (zoneFeatureProvider != null) {
            zoneFeatureProvider.render(camera, frameCounter);
        }

        DebugObjectArtViewer.getInstance().draw(objectRenderManager, camera);

        // Revert to default shader for HUD rendering to avoid distortion
        // IMPORTANT: Must be queued as a command so it executes AFTER pattern batches
        // Also reset PatternRenderCommand state so next pattern will reinitialize with
        // the default shader
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            graphicsManager.setUseWaterShader(false);
            PatternRenderCommand.resetFrameState();
        }));

        if (hudRenderManager != null) {
            AbstractPlayableSprite focusedPlayer = camera.getFocusedSprite();
            hudRenderManager.draw(levelGamestate, focusedPlayer);
        }

        boolean debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
        boolean overlayEnabled = debugViewEnabled && overlayManager.isEnabled(DebugOverlayToggle.OVERLAY);
        if (overlayEnabled) {
            graphicsManager.enqueueDebugLineState();
        }

        if (objectManager != null && overlayEnabled) {
            boolean showObjectPoints = overlayManager.isEnabled(DebugOverlayToggle.OBJECT_POINTS);
            boolean showPlaneSwitchers = overlayManager.isEnabled(DebugOverlayToggle.PLANE_SWITCHERS);
            debugObjectCommands.clear();
            debugSwitcherLineCommands.clear();
            debugSwitcherAreaCommands.clear();
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite
                    ? (AbstractPlayableSprite) player
                    : null;
            for (ObjectSpawn spawn : objectManager.getActiveSpawns()) {
                // Frustum cull: skip objects outside visible area (with 32px padding for large objects)
                if (!isInCameraFrustum(spawn.x(), spawn.y(), 32)) {
                    continue;
                }
                if (showObjectPoints) {
                    debugObjectCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                            -1,
                            GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                            1f, 0f, 1f,
                            spawn.x(), spawn.y(), 0, 0));
                }
                if (showPlaneSwitchers) {
                    appendPlaneSwitcherDebug(spawn, debugSwitcherLineCommands, debugSwitcherAreaCommands, playable);
                }
            }
            if (showPlaneSwitchers && !debugSwitcherAreaCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                for (GLCommand command : debugSwitcherAreaCommands) {
                    graphicsManager.registerCommand(command);
                }
            }
            if (showPlaneSwitchers && !debugSwitcherLineCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, debugSwitcherLineCommands));
            }
            if (showObjectPoints && !debugObjectCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, debugObjectCommands));
            }
        }

        if (ringManager != null && overlayEnabled
                && overlayManager.isEnabled(DebugOverlayToggle.RING_BOUNDS)) {
            Collection<RingSpawn> rings = ringManager.getActiveSpawns();
            if (!rings.isEmpty()) {
                if (!ringManager.hasRenderer()) {
                    debugRingCommands.clear();
                    for (RingSpawn ring : rings) {
                        if (!ringManager.isRenderable(ring, frameCounter)) {
                            continue;
                        }
                        // Frustum cull rings outside visible area
                        if (!isInCameraFrustum(ring.x(), ring.y(), 16)) {
                            continue;
                        }
                        debugRingCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                                -1,
                                GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                                1f, 0.85f, 0.1f,
                                ring.x(), ring.y(), 0, 0));
                    }
                    graphicsManager.enqueueDebugLineState();
                    graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, debugRingCommands));
                } else {
                    PatternSpriteRenderer.FrameBounds bounds = ringManager.getFrameBounds(frameCounter);
                    debugBoxCommands.clear();
                    debugCenterCommands.clear();
                    int crossHalf = 2;

                    for (RingSpawn ring : rings) {
                        if (!ringManager.isRenderable(ring, frameCounter)) {
                            continue;
                        }
                        // Frustum cull rings outside visible area
                        if (!isInCameraFrustum(ring.x(), ring.y(), 16)) {
                            continue;
                        }
                        int centerX = ring.x();
                        int centerY = ring.y();
                        int left = centerX + bounds.minX();
                        int right = centerX + bounds.maxX();
                        int top = centerY + bounds.minY();
                        int bottom = centerY + bounds.maxY();

                        // Bounding box (4 line segments)
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, top, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, top, 0, 0));

                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, top, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, bottom, 0, 0));

                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, right, bottom, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, bottom, 0, 0));

                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, bottom, 0, 0));
                        debugBoxCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                0.2f, 1f, 0.2f, left, top, 0, 0));

                        // Center cross
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX - crossHalf, centerY, 0, 0));
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX + crossHalf, centerY, 0, 0));
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX, centerY - crossHalf, 0, 0));
                        debugCenterCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                                1f, 0.85f, 0.1f, centerX, centerY + crossHalf, 0, 0));
                    }

                    if (!debugBoxCommands.isEmpty()) {
                        graphicsManager.enqueueDebugLineState();
                        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, debugBoxCommands));
                    }
                    if (!debugCenterCommands.isEmpty()) {
                        graphicsManager.enqueueDebugLineState();
                        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, debugCenterCommands));
                    }
                }
            }
        }

        if (overlayEnabled) {
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            if (player instanceof AbstractPlayableSprite playable) {
                if (overlayManager.isEnabled(DebugOverlayToggle.CAMERA_BOUNDS)) {
                    drawCameraBounds();
                }
                if (overlayManager.isEnabled(DebugOverlayToggle.PLAYER_BOUNDS)) {
                    drawPlayableSpriteBounds(playable);
                }
            }
        }
        graphicsManager.enqueueDefaultShaderState();
    }

    private void updateWaterShaderState(Camera camera) {
        WaterSystem waterSystem = WaterSystem.getInstance();
        int zoneId = level.getZoneIndex();

        if (waterSystem.hasWater(zoneId, currentAct)) {
            // Set uniforms via custom command - this also enables the water shader
            // Use visual water level (with oscillation) for rendering effects
            int waterLevel = waterSystem.getVisualWaterLevelY(zoneId, currentAct);
            float waterlineScreenY = (float) (waterLevel - camera.getY()); // Pixels from top

            graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
                // Enable water shader at execution time
                graphicsManager.setUseWaterShader(true);

                WaterShaderProgram shader = graphicsManager.getWaterShaderProgram();
                shader.use(gl);

                // Query actual window height from GL state to correct shader coordinates
                int[] viewport = new int[4];
                gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
                float windowHeight = (float) viewport[3];
                shader.setWindowHeight(gl, windowHeight);
                shader.setWaterlineScreenY(gl, waterlineScreenY);
                shader.setFrameCounter(gl, frameCounter);
                shader.setDistortionAmplitude(gl, 2.0f);
                shader.setScreenDimensions(gl, (float) configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
                        (float) configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));

                // Underwater Palette
                Palette[] underwater = waterSystem.getUnderwaterPalette(zoneId, currentAct);
                if (underwater != null) {
                    graphicsManager.cacheUnderwaterPaletteTexture(underwater);
                    Integer texId = graphicsManager.getUnderwaterPaletteTextureId();
                    int loc = shader.getUnderwaterPaletteLocation();

                    if (texId != null && loc != -1) {
                        // Bind to TU2
                        gl.glActiveTexture(GL2.GL_TEXTURE2);
                        gl.glBindTexture(GL2.GL_TEXTURE_2D, texId);
                        gl.glUniform1i(loc, 2);
                        gl.glActiveTexture(GL2.GL_TEXTURE0);
                    }
                }
            }));
        }
        // Note: We don't disable water shader here - that's done later before HUD
        // rendering
    }

    private void renderBackgroundShader(List<GLCommand> commands, int bgScrollY) {
        if (level == null || level.getMap() == null)
            return;

        BackgroundRenderer bgRenderer = graphicsManager.getBackgroundRenderer();
        if (bgRenderer == null)
            return;

        Camera camera = Camera.getInstance();

        // FBO is wider than screen to accommodate per-scanline scroll range
        // For EHZ, scroll difference can be up to cameraX pixels between sky and ground
        // Using 1024px width gives us 352px buffer on each side
        int fboWidth = 1024; // Wide enough for most scroll ranges
        int fboHeight = 256; // Full background map height for complete parallax

        // Extra buffer on each side
        int extraBuffer = (fboWidth - 320) / 2; // 352 pixels on each side

        // Get pattern renderer's screen height for correct Y coordinate handling
        int screenHeightPixels = cachedScreenHeight;

        // Use zone-specific vertical scroll from parallax manager
        // This ensures zones like MCZ use their act-dependent BG Y calculations
        int actualBgScrollY = parallaxManager.getVscrollFactorBG();

        // 1. Resize FBO
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            bgRenderer.resizeFBO(gl, fboWidth, fboHeight);
        }));

        // 2. Begin Tile Pass (Bind FBO)
        // Use water shader in screen-space mode for FBO, with adjusted waterline
        WaterSystem waterSystem = WaterSystem.getInstance();
        boolean hasWater = waterSystem.hasWater(level.getZoneIndex(), currentAct);
        // Use visual water level (with oscillation) for background rendering
        int waterLevelWorldY = hasWater ? waterSystem.getVisualWaterLevelY(level.getZoneIndex(), currentAct) : 9999;

        // Calculate waterline for FBO - use SCREEN-SPACE waterline PLUS parallax offset
        // The parallax shader shifts the FBO sampling by (actualBgScrollY - alignedBgY)
        // so we must shift the waterline by the same amount to keep it steady on screen

        int chunkHeight = LevelConstants.CHUNK_HEIGHT;
        int alignedBgY = (actualBgScrollY / chunkHeight) * chunkHeight;
        if (actualBgScrollY < 0 && actualBgScrollY % chunkHeight != 0) {
            alignedBgY -= chunkHeight; // Handle negative rounding
        }

        int vOffset = actualBgScrollY - alignedBgY;
        final float fboWaterlineY = (float) ((waterLevelWorldY - camera.getY()) + vOffset);

        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            bgRenderer.beginTilePass(gl, screenHeightPixels);

            if (hasWater) {
                // Use water shader in screen-space mode with FBO dimensions
                graphicsManager.setUseWaterShader(true);
                WaterShaderProgram waterShader = graphicsManager.getWaterShaderProgram();
                waterShader.use(gl);
                // Use screen-space mode (not world-space) with FBO-adjusted waterline
                waterShader.setWorldSpaceWater(gl, 0.0f, 0.0f, false);
                waterShader.setWindowHeight(gl, (float) fboHeight);
                waterShader.setScreenDimensions(gl, (float) fboWidth, (float) fboHeight);
                waterShader.setWaterlineScreenY(gl, fboWaterlineY);
            } else {
                graphicsManager.setUseWaterShader(false);
            }
            // Clear underwater palette for background flag usage - explicitly control it
            // Force underwater palette for background if we are fully submerged relative to
            // FBO
            // This ensures consistent behavior for deep water levels like ARZ
            boolean fullyUnderwater = hasWater && fboWaterlineY <= 0;
            graphicsManager.setUseUnderwaterPaletteForBackground(fullyUnderwater);
        }));

        // 3. Draw background tiles to wider FBO (uses water shader in world-space mode)
        graphicsManager.beginPatternBatch();
        drawBackgroundToFBOWide(commands, camera, actualBgScrollY, fboWidth, fboHeight, extraBuffer);
        graphicsManager.flushPatternBatch();

        // 4. End Tile Pass (Unbind FBO) and switch water shader back to screen-space
        // mode. Use visual water level (with oscillation) for foreground rendering.
        int waterLevel = hasWater ? waterSystem.getVisualWaterLevelY(level.getZoneIndex(), currentAct) : 0;
        float waterlineScreenY = (float) (waterLevel - camera.getY()); // Pixels from top

        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            bgRenderer.endTilePass(gl);

            // Always reset background palette flag to avoid affecting foreground
            graphicsManager.setUseUnderwaterPaletteForBackground(false);

            if (hasWater) {
                // Switch water shader back to screen-space mode for foreground rendering
                WaterShaderProgram waterShader = graphicsManager.getWaterShaderProgram();
                waterShader.use(gl);
                waterShader.setWorldSpaceWater(gl, 0.0f, 0.0f, false);

                // Restore screen-space uniforms
                int[] viewport = new int[4];
                gl.glGetIntegerv(GL2.GL_VIEWPORT, viewport, 0);
                float windowHeight = (float) viewport[3];
                waterShader.setWindowHeight(gl, windowHeight);
                waterShader.setWaterlineScreenY(gl, waterlineScreenY);
                waterShader.setScreenDimensions(gl,
                        (float) configService.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS),
                        (float) configService.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS));
            }
        }));

        // 5. Render the FBO with Parallax Shader
        Integer paletteId = graphicsManager.getCombinedPaletteTextureId();

        // Get the hScroll data and base scroll value (last line = furthest right in
        // level)
        int[] hScrollData = parallaxManager.getHScrollForShader();
        int baseScrollForShader = (hScrollData != null && hScrollData.length > 0)
                ? (short) (hScrollData[hScrollData.length - 1] & 0xFFFF)
                : 0; // Use last line (bottom) as base

        if (paletteId != null) {
            int pId = paletteId;
            int screenW = cachedScreenWidth;
            int screenH = screenHeightPixels;

            // Calculate vertical scroll offset (sub-chunk) for shader
            // The FBO is rendered aligned to 16-pixel chunk boundaries
            // The shader needs to shift the view by the remaining offset
            int shaderVOffset = actualBgScrollY % LevelConstants.CHUNK_HEIGHT;
            if (shaderVOffset < 0)
                shaderVOffset += LevelConstants.CHUNK_HEIGHT; // Handle negative modulo

            final int finalVOffset = shaderVOffset;

            graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx2, cy2, cw2, ch2) -> {
                bgRenderer.renderWithScrollWide(gl, hScrollData, baseScrollForShader, extraBuffer, finalVOffset, pId,
                        screenW,
                        screenH);
            }));
        }
    }

    /**
     * Draw background tiles to FBO for per-scanline scrolling.
     * Renders exactly one horizontal period of the background for seamless
     * wrapping.
     * Y coordinates are aligned to screen space (FBO Y=0 = screen Y=0).
     */
    private void drawBackgroundToFBOWide(List<GLCommand> commands, Camera camera, int bgScrollY,
            int fboWidth, int fboHeight, int extraBuffer) {
        int cameraX = camera.getX();
        int cameraY = camera.getY();

        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        // bgCameraY is the vertical scroll position for background
        int bgCameraY = bgScrollY;

        int xStart = 0;
        int xEnd = Math.min(fboWidth, levelWidth);

        // Render to FBO aligning Y to chunk boundaries
        // This ensures consistent tile placement regardless of sub-chunk scroll
        // The shader applies the sub-chunk offset
        int chunkHeight = LevelConstants.CHUNK_HEIGHT;
        // Align bgCameraY down to nearest chunk
        int alignedBgY = (bgCameraY / chunkHeight) * chunkHeight;
        if (bgCameraY < 0 && bgCameraY % chunkHeight != 0)
            alignedBgY -= chunkHeight; // Handle negative rounding

        // Render enough rows to fill the FBO height
        // alignedBgY corresponds to FBO Y=0
        int worldYStart = alignedBgY;
        int worldYEnd = alignedBgY + fboHeight;

        for (int worldY = worldYStart; worldY < worldYEnd; worldY += chunkHeight) {
            // Calculate FBO Y position for this tile
            // Since we start at alignedBgY, the first row is at fboY = 0
            int fboY = worldY - alignedBgY;

            // Skip tiles outside FBO range (safeguard)
            if (fboY < 0 || fboY >= fboHeight)
                continue;

            int wrappedY = ((worldY % levelHeight) + levelHeight) % levelHeight;

            for (int x = xStart; x < xEnd; x += LevelConstants.CHUNK_WIDTH) {
                int wrappedX = x % levelWidth;

                Block block = getBlockAtPosition((byte) 1, wrappedX, wrappedY);
                if (block != null) {
                    int xBlockBit = (wrappedX % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH;
                    int yBlockBit = (wrappedY % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT;
                    ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);

                    // Convert to pattern renderer coordinates
                    // We render relative to alignedBgY, so fboY 0 is alignedBgY
                    // renderY is passed to pattern renderer which eventually maps to FBO
                    // renderY = fboY + cameraY (but wait, we want fboY to be the OFFSET in the FBO)
                    // If we pass x, y+cameraY, drawing usually effectively subtracts cameraY.
                    // We want final coord in FBO to be fboY.
                    // So we pass renderY = fboY + cameraY.
                    // (Assuming drawPattern subtracts cameraY)
                    int renderX = x + cameraX;
                    int renderY = fboY + cameraY;

                    drawChunk(commands, chunkDesc, renderX, renderY, false, null, 0, 0, TilePriorityPass.ALL);
                }
            }
        }
    }

    private void drawLayer(List<GLCommand> commands,
            int layerIndex,
            Camera camera,
            float parallaxX,
            float parallaxY,
            TilePriorityPass priorityPass,
            boolean drawCollision,
            boolean renderToFBO) {
        int cameraX = camera.getX();
        int cameraY = camera.getY();
        int cameraWidth = camera.getWidth();
        int cameraHeight = camera.getHeight();

        int bgCameraX = (int) (cameraX * parallaxX);
        int bgCameraY = (int) (cameraY * parallaxY);

        // Disable CPU-side hScroll - Layer 0 (Foreground) doesn't use it here?
        // Actually Layer 0 might use hScroll from parallaxManager?
        // But getHScroll() was used for Layer 1. Layer 0 usually scroll constant?
        // ParallaxManager calculates FG scroll too.
        // But original code: (layerIndex == 1 && !renderToFBO)
        // So hScroll was NULL for Layer 0.
        int[] hScroll = null;

        int drawX, drawY, xStart, xEnd, yStart, yEnd;

        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;

        if (renderToFBO) {
            // Render entire map from (0,0)
            drawX = 0;
            drawY = 0;
            xStart = 0;
            xEnd = levelWidth; // Draw full width
            yStart = 0;
            yEnd = levelHeight; // Draw full height
            bgCameraX = 0; // No camera offset in FBO
            bgCameraY = 0;
            cameraX = 0; // Patterns drawn relative to 0
            cameraY = 0;
        } else {
            // Standard camera culling
            drawX = bgCameraX - (bgCameraX % LevelConstants.CHUNK_WIDTH);
            drawY = bgCameraY - (bgCameraY % LevelConstants.CHUNK_HEIGHT);

            xStart = drawX;
            xEnd = bgCameraX + cameraWidth;

            yStart = drawY;
            yEnd = bgCameraY + cameraHeight + LevelConstants.CHUNK_HEIGHT;
        }

        for (int y = yStart; y < yEnd; y += LevelConstants.CHUNK_HEIGHT) { // Changed <= to < to avoid OOB if exact
                                                                           // match? Check original logic. Original was
                                                                           // <=
            // Revert to original <= if needed, but watch out for duplicates?
            // Original: for (int y = yStart; y <= yEnd; y += ...
            // If yEnd is exactly on boundary, it draws one more row.
        }
        // Re-implementing the loop with corrected logic for FBO
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
                    if (line < 0)
                        line = 0;
                    if (line >= ParallaxManager.VISIBLE_LINES)
                        line = ParallaxManager.VISIBLE_LINES - 1;

                    short val = (short) (hScroll[line] & 0xFFFF);
                    if (val < localMin)
                        localMin = val;
                    if (val > localMax)
                        localMax = val;
                }

                rowXStart = -localMax;
                rowXEnd = cameraWidth - localMin;

                // Align to chunk boundary
                rowXStart -= (rowXStart % LevelConstants.CHUNK_WIDTH + LevelConstants.CHUNK_WIDTH)
                        % LevelConstants.CHUNK_WIDTH;

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
                    if (wrappedY < 0 || wrappedY >= levelHeight)
                        continue;
                }

                Block block = getBlockAtPosition((byte) layerIndex, wrappedX, wrappedY);
                if (block != null) {
                    int xBlockBit = (wrappedX % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH;
                    int yBlockBit = (wrappedY % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT;

                    ChunkDesc chunkDesc = block.getChunkDesc(xBlockBit, yBlockBit);

                    // Calculate screen coordinates
                    int screenX = x - bgCameraX;
                    int screenY = y - bgCameraY;

                    // Convert to absolute coordinates expected by renderPattern (which subtracts
                    // cameraX/Y)
                    int renderX = screenX + cameraX;
                    int renderY = screenY + cameraY;

                    // Draw collision only for foreground (Layer 0)
                    drawChunk(commands, chunkDesc, renderX, renderY, drawCollision, hScroll, screenY, bgCameraX,
                            priorityPass);
                }
            }
        }
    }

    /**
     * Draws a chunk of the level based on the provided chunk description.
     *
     * @param commands      the list of GLCommands to add to
     * @param chunkDesc     the description of the chunk to draw
     * @param x             the x-coordinate to draw the chunk at
     * @param y             the y-coordinate to draw the chunk at
     * @param drawCollision whether to draw collision debug info
     */
    private void drawChunk(List<GLCommand> commands, ChunkDesc chunkDesc, int x, int y, boolean drawCollision) {
        drawChunk(commands, chunkDesc, x, y, drawCollision, null, 0, 0, TilePriorityPass.ALL);
    }

    private void drawChunk(List<GLCommand> commands,
            ChunkDesc chunkDesc,
            int x,
            int y,
            boolean drawCollision,
            int[] hScroll,
            int screenY,
            int baseBgCameraX,
            TilePriorityPass priorityPass) {
        int chunkIndex = chunkDesc.getChunkIndex();
        if (chunkIndex == 0) {
            return; // Chunk 0 is always empty/transparent
        }
        if (chunkIndex >= level.getChunkCount()) {
            LOGGER.fine("Chunk index " + chunkIndex + " out of bounds; defaulting to 0.");
            chunkIndex = 0;
            return; // Since we default to 0, which is empty, we can just return
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
                    if (line < 0)
                        line = 0;
                    if (line >= ParallaxManager.VISIBLE_LINES)
                        line = ParallaxManager.VISIBLE_LINES - 1;

                    short scroll = (short) (hScroll[line] & 0xFFFF);
                    drawX = drawX + scroll + baseBgCameraX;
                }

                boolean isHighPriority = newPatternDesc.getPriority();
                if (priorityPass == TilePriorityPass.LOW_ONLY && isHighPriority) {
                    continue;
                }
                if (priorityPass == TilePriorityPass.HIGH_ONLY && !isHighPriority) {
                    continue;
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
            int y) {
        if (!overlayManager.isEnabled(DebugOverlayToggle.COLLISION_VIEW)) {
            return;
        }

        boolean hasSolidity = isPrimary
                ? chunkDesc.hasPrimarySolidity()
                : chunkDesc.hasSecondarySolidity();
        if (!hasSolidity) {
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
                // GLCommand constructor handles Y-flip (SCREEN_HEIGHT_PIXELS - y)
                // and execute() applies camera offset (y + cameraY)
                // We add 16 to align with the pattern renderer's coordinate system
                if (yFlip) {
                    // When yFlip is true, collision extends upward from bottom of chunk
                    drawStartY = y - LevelConstants.CHUNK_HEIGHT + 16;
                    drawEndY = drawStartY + height;
                } else {
                    // Normal rendering: collision extends downward from top of chunk
                    drawStartY = y + 16;
                    drawEndY = y - height + 16;
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
                        drawStartY));
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

        int collisionCenterX = sprite.getCentreX();
        int collisionCenterY = sprite.getCentreY();
        int renderCenterX = sprite.getRenderCentreX();
        int renderCenterY = sprite.getRenderCentreY();
        List<GLCommand> commands = new ArrayList<>(128);

        if (mappingBounds.width() > 0 && mappingBounds.height() > 0) {
            int mapLeft = renderCenterX + mappingBounds.minX();
            int mapRight = renderCenterX + mappingBounds.maxX();
            int mapTop = renderCenterY + mappingBounds.minY();
            int mapBottom = renderCenterY + mappingBounds.maxY();
            appendBox(commands, mapLeft, mapTop, mapRight, mapBottom, 0.1f, 0.85f, 1f);
        }

        int radiusLeft = collisionCenterX - sprite.getXRadius();
        int radiusRight = collisionCenterX + sprite.getXRadius();
        int radiusTop = collisionCenterY - sprite.getYRadius();
        int radiusBottom = collisionCenterY + sprite.getYRadius();
        appendBox(commands, radiusLeft, radiusTop, radiusRight, radiusBottom, 1f, 0.8f, 0.1f);

        appendCross(commands, collisionCenterX, collisionCenterY, 2, 1f, 0.8f, 0.1f);
        appendCross(commands, renderCenterX, renderCenterY, 2, 0.1f, 0.85f, 1f);

        Sensor[] sensors = sprite.getAllSensors();
        for (int i = 0; i < sensors.length; i++) {
            Sensor sensor = sensors[i];
            if (sensor == null) {
                continue;
            }
            short[] rotatedOffset = sensor.getRotatedOffset();
            int originX = collisionCenterX + rotatedOffset[0];
            int originY = collisionCenterY + rotatedOffset[1];

            float[] color = DebugOverlayPalette.sensorLineColor(i, sensor.isActive());
            appendCross(commands, originX, originY, 1, color[0], color[1], color[2]);

            if (!sensor.isActive()) {
                continue;
            }
            SensorResult result = sensor.getCurrentResult();
            if (result == null) {
                continue;
            }

            SensorConfiguration sensorConfiguration = SpriteManager
                    .getSensorConfigurationForGroundModeAndDirection(sprite.getGroundMode(), sensor.getDirection());
            Direction globalDirection = sensorConfiguration.direction();

            int dist = result.distance();
            int endX = originX;
            int endY = originY;
            switch (globalDirection) {
                case DOWN -> endY = originY + dist;
                case UP -> endY = originY - dist;
                case LEFT -> endX = originX - dist;
                case RIGHT -> endX = originX + dist;
            }

            appendLine(commands, originX, originY, endX, endY, color[0], color[1], color[2]);
        }

        if (!commands.isEmpty()) {
            graphicsManager.enqueueDebugLineState();
            graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, commands));
        }
    }

    private void drawCameraBounds() {
        Camera camera = Camera.getInstance();
        List<GLCommand> commands = new ArrayList<>(64);

        int camX = camera.getX();
        int camY = camera.getY();
        int camW = camera.getWidth();
        int camH = camera.getHeight();

        appendBox(commands, camX, camY, camX + camW, camY + camH, 0.85f, 0.9f, 1f);
        appendCross(commands, camX + (camW / 2), camY + (camH / 2), 4, 0.85f, 0.9f, 1f);

        int minX = camera.getMinX();
        int minY = camera.getMinY();
        int maxX = camera.getMaxX();
        int maxY = camera.getMaxY();
        if (maxX > minX || maxY > minY) {
            appendBox(commands, minX, minY, maxX + camW, maxY + camH, 0.2f, 0.9f, 0.9f);
        }

        if (!commands.isEmpty()) {
            graphicsManager.enqueueDebugLineState();
            graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, commands));
        }
    }

    private void appendLine(
            List<GLCommand> commands,
            int x1,
            int y1,
            int x2,
            int y2,
            float r,
            float g,
            float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    private void appendCross(
            List<GLCommand> commands,
            int centerX,
            int centerY,
            int halfSpan,
            float r,
            float g,
            float b) {
        appendLine(commands, centerX - halfSpan, centerY, centerX + halfSpan, centerY, r, g, b);
        appendLine(commands, centerX, centerY - halfSpan, centerX, centerY + halfSpan, r, g, b);
    }

    private void appendPlaneSwitcherDebug(ObjectSpawn spawn,
            List<GLCommand> lineCommands,
            List<GLCommand> areaCommands,
            AbstractPlayableSprite player) {
        if (gameModule == null || spawn.objectId() != gameModule.getPlaneSwitcherObjectId()) {
            return;
        }
        int subtype = spawn.subtype();
        int halfSpan = ObjectManager.decodePlaneSwitcherHalfSpan(subtype);
        boolean horizontal = ObjectManager.isPlaneSwitcherHorizontal(subtype);
        int x = spawn.x();
        int y = spawn.y();
        int sideState = objectManager != null ? objectManager.getPlaneSwitcherSideState(spawn) : -1;
        if (sideState < 0 && player != null) {
            sideState = horizontal
                    ? (player.getCentreY() >= y ? 1 : 0)
                    : (player.getCentreX() >= x ? 1 : 0);
        }
        if (sideState < 0) {
            sideState = 0;
        }

        int extent = halfSpan;
        if (horizontal) {
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x - halfSpan, y, 0, 0));
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x + halfSpan, y, 0, 0));

            int top = sideState == 0 ? y - extent : y;
            int bottom = sideState == 0 ? y : y + extent;
            areaCommands.add(new GLCommand(GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B, SWITCHER_DEBUG_ALPHA,
                    x - halfSpan, top,
                    x + halfSpan, bottom));
        } else {
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x, y - halfSpan, 0, 0));
            lineCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B,
                    x, y + halfSpan, 0, 0));

            int left = sideState == 0 ? x - extent : x;
            int right = sideState == 0 ? x : x + extent;
            areaCommands.add(new GLCommand(GLCommand.CommandType.RECTI, -1, GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                    SWITCHER_DEBUG_R, SWITCHER_DEBUG_G, SWITCHER_DEBUG_B, SWITCHER_DEBUG_ALPHA,
                    left, y - halfSpan,
                    right, y + halfSpan));
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
            float b) {
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
            if (wrappedY < 0 || wrappedY >= levelHeight)
                return null;
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
        Block block = getBlockAtPosition(layer, x, y);
        if (block == null) {
            return null;
        }

        int levelWidth = level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH;
        int wrappedX = ((x % levelWidth) + levelWidth) % levelWidth;
        int wrappedY = y;

        if (layer == 1) {
            int levelHeight = level.getMap().getHeight() * LevelConstants.BLOCK_HEIGHT;
            wrappedY = ((y % levelHeight) + levelHeight) % levelHeight;
        }

        ChunkDesc chunkDesc = block.getChunkDesc((wrappedX % LevelConstants.BLOCK_WIDTH) / LevelConstants.CHUNK_WIDTH,
                (wrappedY % LevelConstants.BLOCK_HEIGHT) / LevelConstants.CHUNK_HEIGHT);
        return chunkDesc;
    }

    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, int solidityBitIndex) {
        try {
            if (chunkDesc == null) {
                return null;
            }
            if (!chunkDesc.isSolidityBitSet(solidityBitIndex)) {
                return null;
            }

            Chunk chunk = level.getChunk(chunkDesc.getChunkIndex());
            if (chunk == null) {
                return null;
            }
            // Get collision index - ROM treats index 0 as "no collision"
            // (s2.asm FindFloor line 42963: beq.s loc_1E7E2)
            int collisionIndex = (solidityBitIndex < 0x0E)
                    ? chunk.getSolidTileIndex()
                    : chunk.getSolidTileAltIndex();
            if (collisionIndex == 0) {
                return null; // No collision shape assigned
            }
            return level.getSolidTile(collisionIndex);
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
    // But I can't leave this here without updating GroundSensor first or it won't
    // compile?
    // Wait, I can overload.
    public SolidTile getSolidTileForChunkDesc(ChunkDesc chunkDesc, byte layer) {
        int solidityBitIndex = (layer == 0) ? 0x0C : 0x0E;
        return getSolidTileForChunkDesc(chunkDesc, solidityBitIndex);
    }

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

    public int getCurrentZone() {
        return currentZone;
    }

    public int getCurrentAct() {
        return currentAct;
    }

    /**
     * Gets the music ID for the current level.
     * Returns -1 if no level is loaded or music ID cannot be determined.
     */
    public int getCurrentLevelMusicId() {
        if (game == null || levels == null || levels.isEmpty()) {
            return -1;
        }
        try {
            int levelIdx = levels.get(currentZone).get(currentAct).getLevelIndex();
            return game.getMusicId(levelIdx);
        } catch (Exception e) {
            LOGGER.warning("Failed to get music ID for current level: " + e.getMessage());
            return -1;
        }
    }

    public Collection<ObjectSpawn> getActiveObjectSpawns() {
        if (objectManager == null) {
            return List.of();
        }
        return objectManager.getActiveSpawns();
    }

    public ObjectRenderManager getObjectRenderManager() {
        return objectRenderManager;
    }

    public RingManager getRingManager() {
        return ringManager;
    }

    public boolean areAllRingsCollected() {
        return ringManager != null && ringManager.areAllCollected();
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    public void spawnLostRings(AbstractPlayableSprite player, int frameCounter) {
        if (ringManager == null || player == null) {
            return;
        }
        int count = player.getRingCount();
        if (count <= 0) {
            return;
        }
        ringManager.spawnLostRings(player, count, frameCounter);
    }

    /**
     * Loads the current level with title card.
     * Use this for fresh level starts (zone/act changes).
     */
    public void loadCurrentLevel() {
        loadCurrentLevel(true);
    }

    /**
     * Loads the current level for death respawn (no title card).
     */
    public void respawnPlayer() {
        loadCurrentLevel(false);
    }

    /**
     * Loads the current level with optional title card.
     *
     * @param showTitleCard true to show title card on fresh starts, false for death
     *                      respawns
     */
    private void loadCurrentLevel(boolean showTitleCard) {
        try {
            // Ensure zone list is populated before accessing it
            if (levels.isEmpty()) {
                gameModule = GameModuleRegistry.getCurrent();
                refreshZoneList();
            }
            LevelData levelData = levels.get(currentZone).get(currentAct);

            // Check if we have an active checkpoint BEFORE reloading
            // (loadLevel clears checkpointState, so we need to save the values first)
            boolean hasCheckpoint = checkpointState != null && checkpointState.isActive();
            int checkpointX = hasCheckpoint ? checkpointState.getSavedX() : 0;
            int checkpointY = hasCheckpoint ? checkpointState.getSavedY() : 0;
            int checkpointCameraX = hasCheckpoint ? checkpointState.getSavedCameraX() : 0;
            int checkpointCameraY = hasCheckpoint ? checkpointState.getSavedCameraY() : 0;
            int checkpointIndex = hasCheckpoint ? checkpointState.getLastCheckpointIndex() : -1;

            loadLevel(levelData.getLevelIndex());

            // Restore checkpoint state if we had an active checkpoint
            // (loadLevel clears it, but we need it for subsequent respawns)
            if (hasCheckpoint && checkpointState != null) {
                checkpointState.restoreFromSaved(checkpointX, checkpointY, checkpointCameraX, checkpointCameraY,
                        checkpointIndex);
            }

            frameCounter = 0;
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));

            // Use checkpoint position if available, otherwise level start
            // Note: Spawn Y coordinates from ROM data represent the sprite's CENTER
            // position,
            // but our setY() sets the top-left. We need to subtract yRadius to convert.
            int yRadius = 19; // Sonic's standing yRadius
            if (hasCheckpoint) {
                player.setX((short) checkpointX);
                player.setY((short) (checkpointY - yRadius));
                LOGGER.info("Set player position from checkpoint: X=" + checkpointX + ", Y=" + checkpointY +
                        " (adjusted by yRadius=" + yRadius + ")");
            } else {
                player.setX((short) levelData.getStartXPos());
                player.setY((short) (levelData.getStartYPos() - yRadius));
                LOGGER.info("Set player position from levelData: X=" + levelData.getStartXPos() +
                        ", Y=" + levelData.getStartYPos() + " (adjusted by yRadius=" + yRadius +
                        ", level: " + levelData.name() + ")");
            }

            if (player instanceof AbstractPlayableSprite) {
                AbstractPlayableSprite playable = (AbstractPlayableSprite) player;
                // Full state reset first
                playable.resetState();
                // Then set specific values
                playable.setXSpeed((short) 0);
                playable.setYSpeed((short) 0);
                playable.setGSpeed((short) 0);
                playable.setAir(false);
                LOGGER.info("Player state after loadCurrentLevel: air=" + playable.getAir() +
                        ", ySpeed=" + playable.getYSpeed() + ", layer=" + player.getLayer());
                playable.setRolling(false);
                playable.setDead(false);
                playable.setHurt(false);
                playable.setDeathCountdown(0);
                playable.setInvulnerableFrames(0);
                playable.setInvincibleFrames(0);
                playable.setDirection(uk.co.jamesj999.sonic.physics.Direction.RIGHT);
                playable.setAngle((byte) 0);
                player.setLayer((byte) 0);
                playable.setHighPriority(false);
                playable.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);

                // Clear rings on spawn (ROM behavior)
                playable.setRingCount(0);

                // Reset speed shoes effect and music tempo
                // Note: resetState is already called which clears speedShoes, but we also need
                // to reset audio
                uk.co.jamesj999.sonic.audio.AudioManager.getInstance().getBackend().setSpeedShoes(false);

                Camera camera = Camera.getInstance();
                camera.setFrozen(false); // Unlock camera after death
                camera.setFocusedSprite(playable);
                camera.updatePosition(true); // Force camera to player position

                Level currentLevel = getCurrentLevel();
                if (currentLevel != null) {
                    camera.setMinX((short) currentLevel.getMinX());
                    camera.setMaxX((short) currentLevel.getMaxX());
                    camera.setMinY((short) currentLevel.getMinY());
                    camera.setMaxY((short) currentLevel.getMaxY());
                }

                // Initialize level events for dynamic boundary updates (game-specific)
                LevelEventProvider levelEvents = GameModuleRegistry.getCurrent().getLevelEventProvider();
                if (levelEvents != null) {
                    levelEvents.initLevel(currentZone, currentAct);
                }
            }

            // Request title card for level starts and death respawns
            // Original Sonic 2 shows title card on all respawns (with or without
            // checkpoint)
            if (showTitleCard) {
                requestTitleCard(currentZone, currentAct);
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void nextAct() throws IOException {
        currentAct++;
        if (currentAct >= levels.get(currentZone).size()) {
            currentAct = 0;
        }
        // Clear checkpoint when manually changing level
        if (checkpointState != null) {
            checkpointState.clear();
        }
        loadCurrentLevel();
    }

    /**
     * Advance to the next level in progression order.
     * Unlike nextAct() which wraps, this advances to next zone when acts are
     * exhausted.
     * Called by results screen after tally completes.
     */
    public void advanceToNextLevel() throws IOException {
        currentAct++;
        if (currentAct >= levels.get(currentZone).size()) {
            // Move to next zone
            currentZone++;
            currentAct = 0;
            if (currentZone >= levels.size()) {
                LOGGER.info("All zones complete!");
                currentZone = 0; // Loop back for now - TODO: end game sequence
            }
        }
        // Clear checkpoint when advancing
        if (checkpointState != null) {
            checkpointState.clear();
        }
        loadCurrentLevel();
    }

    public void loadZoneAndAct(int zone, int act) throws IOException {
        currentAct = act;
        currentZone = zone;
        // Clear checkpoint when manually changing level
        if (checkpointState != null) {
            checkpointState.clear();
        }
        loadCurrentLevel();
    }

    public void nextZone() throws IOException {
        currentZone++;
        if (currentZone >= levels.size()) {
            currentZone = 0;
        }
        currentAct = 0;
        // Clear checkpoint when manually changing level
        if (checkpointState != null) {
            checkpointState.clear();
        }
        loadCurrentLevel();
    }

    public void loadZone(int zone) throws IOException {
        currentZone = zone;
        currentAct = 0;
        // Clear checkpoint when manually changing level
        if (checkpointState != null) {
            checkpointState.clear();
        }
        loadCurrentLevel();
    }

    public RespawnState getCheckpointState() {
        return checkpointState;
    }

    /**
     * Request entry to special stage from a checkpoint star.
     * Called by CheckpointStarInstance when the player touches a star.
     */
    public void requestSpecialStageFromCheckpoint() {
        this.specialStageRequestedFromCheckpoint = true;
    }

    /**
     * Consumes and clears the special stage request flag.
     * 
     * @return true if a special stage was requested since last check
     */
    public boolean consumeSpecialStageRequest() {
        boolean requested = specialStageRequestedFromCheckpoint;
        specialStageRequestedFromCheckpoint = false;
        return requested;
    }

    /**
     * Requests a title card to be shown for the current zone/act.
     * Called when a new level is loaded.
     *
     * @param zone Zone index (0-10)
     * @param act  Act index (0-2)
     */
    public void requestTitleCard(int zone, int act) {
        this.titleCardRequested = true;
        this.titleCardZone = zone;
        this.titleCardAct = act;
    }

    /**
     * Checks if a title card has been requested.
     *
     * @return true if a title card was requested since last check
     */
    public boolean isTitleCardRequested() {
        return titleCardRequested;
    }

    /**
     * Consumes and clears the title card request flag.
     *
     * @return true if a title card was requested since last check
     */
    public boolean consumeTitleCardRequest() {
        boolean requested = titleCardRequested;
        titleCardRequested = false;
        return requested;
    }

    /**
     * Gets the zone index for the requested title card.
     *
     * @return zone index, or -1 if none requested
     */
    public int getTitleCardZone() {
        return titleCardZone;
    }

    /**
     * Gets the act index for the requested title card.
     *
     * @return act index, or -1 if none requested
     */
    public int getTitleCardAct() {
        return titleCardAct;
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
        float r = 0.0f;
        float g = 0.0f;
        float b = 0.0f;
        boolean forceBlack = false;

        if (level instanceof uk.co.jamesj999.sonic.game.sonic2.Sonic2Level) {
            int zoneId = ((uk.co.jamesj999.sonic.game.sonic2.Sonic2Level) level).getZoneIndex();
            // Zone 11 (0xB) is MCZ
            if (zoneId == 11) {
                forceBlack = true;
            }
        }

        if (!forceBlack && level.getPaletteCount() > 1) {
            // In Sonic 2, Palette 1 is the level palette (Palette 0 is character).
            Palette.Color backgroundColor = level.getPalette(1).getColor(0);
            r = Byte.toUnsignedInt(backgroundColor.r) / 255f;
            g = Byte.toUnsignedInt(backgroundColor.g) / 255f;
            b = Byte.toUnsignedInt(backgroundColor.b) / 255f;
        }

        gl.glClearColor(r, g, b, 1.0f);
    }

    /**
     * Reloads the current level's palettes into the graphics manager.
     * Call this after returning from special stage to restore level colors.
     */
    public void reloadLevelPalettes() {
        if (level == null) {
            LOGGER.warning("Cannot reload palettes: no level loaded");
            return;
        }

        int paletteCount = level.getPaletteCount();
        for (int i = 0; i < paletteCount; i++) {
            Palette palette = level.getPalette(i);
            if (palette != null) {
                graphicsManager.cachePaletteTexture(palette, i);
            }
        }
        LOGGER.fine("Reloaded " + paletteCount + " level palettes");
    }

    // ==================== Transition Request Methods ====================
    // These allow GameLoop to coordinate fades with level transitions

    /**
     * Request a respawn (death). GameLoop will handle the fade transition.
     */
    public void requestRespawn() {
        this.respawnRequested = true;
    }

    /**
     * Check and consume respawn request.
     * 
     * @return true if respawn was requested
     */
    public boolean consumeRespawnRequest() {
        boolean requested = respawnRequested;
        respawnRequested = false;
        return requested;
    }

    /**
     * Request transition to next act. GameLoop will handle the fade transition.
     */
    public void requestNextAct() {
        this.nextActRequested = true;
    }

    /**
     * Check and consume next act request.
     * 
     * @return true if next act was requested
     */
    public boolean consumeNextActRequest() {
        boolean requested = nextActRequested;
        nextActRequested = false;
        return requested;
    }

    /**
     * Request transition to next zone. GameLoop will handle the fade transition.
     */
    public void requestNextZone() {
        this.nextZoneRequested = true;
    }

    /**
     * Check and consume next zone request.
     * 
     * @return true if next zone was requested
     */
    public boolean consumeNextZoneRequest() {
        boolean requested = nextZoneRequested;
        nextZoneRequested = false;
        return requested;
    }
}
