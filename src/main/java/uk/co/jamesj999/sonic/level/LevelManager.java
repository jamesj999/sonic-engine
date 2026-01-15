package uk.co.jamesj999.sonic.level;

import com.jogamp.opengl.GL2;
import uk.co.jamesj999.sonic.Engine;
import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.data.Game;
import uk.co.jamesj999.sonic.data.AnimatedPaletteProvider;
import uk.co.jamesj999.sonic.data.AnimatedPatternProvider;
import uk.co.jamesj999.sonic.data.ObjectArtProvider;
import uk.co.jamesj999.sonic.data.PlayerSpriteArtProvider;
import uk.co.jamesj999.sonic.data.SpindashDustArtProvider;
import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.data.RomByteReader;
import uk.co.jamesj999.sonic.data.RomManager;
import uk.co.jamesj999.sonic.game.GameModule;
import uk.co.jamesj999.sonic.game.GameModuleRegistry;
import uk.co.jamesj999.sonic.game.sonic2.CheckpointState;
import uk.co.jamesj999.sonic.game.sonic2.OscillationManager;
import uk.co.jamesj999.sonic.game.sonic2.LevelGamestate;
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
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.graphics.SpriteRenderManager;
import uk.co.jamesj999.sonic.level.render.SpritePieceRenderer;
import uk.co.jamesj999.sonic.level.render.BackgroundRenderer;
// import uk.co.jamesj999.sonic.level.ParallaxManager; -> Removed unused
import uk.co.jamesj999.sonic.level.objects.ObjectManager;
import uk.co.jamesj999.sonic.level.objects.ObjectPlacementManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.PlaneSwitcherManager;
import uk.co.jamesj999.sonic.level.objects.SolidObjectManager;
import uk.co.jamesj999.sonic.level.objects.TouchResponseManager;
import uk.co.jamesj999.sonic.level.objects.TouchResponseTable;
import uk.co.jamesj999.sonic.level.rings.RingManager;
import uk.co.jamesj999.sonic.level.rings.RingPlacementManager;
import uk.co.jamesj999.sonic.level.rings.RingRenderManager;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.level.rings.LostRingManager;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.level.animation.AnimatedPaletteManager;
import uk.co.jamesj999.sonic.level.animation.AnimatedPatternManager;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.sprites.Sprite;
import uk.co.jamesj999.sonic.sprites.SensorConfiguration;
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
    private static final float SWITCHER_DEBUG_ALPHA = 0.35f;
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
    private final DebugOverlayManager overlayManager = DebugOverlayManager.getInstance();
    private final List<List<LevelData>> levels = new ArrayList<>();
    private int currentAct = 0;
    private int currentZone = 0;
    private int frameCounter = 0;
    private ObjectPlacementManager objectPlacementManager;
    private PlaneSwitcherManager planeSwitcherManager;
    private ObjectManager objectManager;
    private SolidObjectManager solidObjectManager;
    private TouchResponseManager touchResponseManager;
    private RingPlacementManager ringPlacementManager;
    private RingRenderManager ringRenderManager;
    private RingManager ringManager;
    private LostRingManager lostRingManager;
    private ObjectRenderManager objectRenderManager;
    private HudRenderManager hudRenderManager;
    private AnimatedPatternManager animatedPatternManager;
    private AnimatedPaletteManager animatedPaletteManager;
    private CheckpointState checkpointState;
    private LevelGamestate levelGamestate;

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

    private enum TilePriorityPass {
        ALL,
        LOW_ONLY,
        HIGH_ONLY
    }

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
            Rom rom = RomManager.getInstance().getRom();
            parallaxManager.load(rom);
            gameModule = GameModuleRegistry.getCurrent();
            game = gameModule.createGame(rom);
            AudioManager audioManager = AudioManager.getInstance();
            audioManager.setAudioProfile(gameModule.getAudioProfile());
            audioManager.setRom(rom);
            audioManager.setSoundMap(game.getSoundMap());
            audioManager.resetRingSound();
            audioManager.playMusic(game.getMusicId(levelIndex));
            level = game.loadLevel(levelIndex);
            OscillationManager.reset();
            initAnimatedPatterns();
            initAnimatedPalettes();
            RomByteReader romReader = RomByteReader.fromRom(rom);
            objectPlacementManager = new ObjectPlacementManager(level.getObjects());
            planeSwitcherManager = new PlaneSwitcherManager(objectPlacementManager,
                    gameModule.getPlaneSwitcherObjectId(),
                    gameModule.getPlaneSwitcherConfig());
            objectManager = new ObjectManager(objectPlacementManager, gameModule.createObjectRegistry());
            // Reset camera state from previous level (signpost may have locked it)
            Camera camera = Camera.getInstance();
            camera.setFrozen(false);
            camera.setMinX((short) 0);
            camera.setMaxX((short) (level.getMap().getWidth() * LevelConstants.BLOCK_WIDTH));
            objectManager.reset(camera.getX(), level.getObjects());
            solidObjectManager = new SolidObjectManager(objectManager);
            solidObjectManager.reset();
            TouchResponseTable touchResponseTable = gameModule.createTouchResponseTable(romReader);
            touchResponseManager = new TouchResponseManager(objectManager, touchResponseTable);
            touchResponseManager.reset();
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
            lostRingManager = new LostRingManager(this, ringRenderManager, touchResponseTable);
            initObjectArt();
            initPlayerSpriteArt();
            resetPlayerState();
            // Initialize checkpoint state for new level
            if (checkpointState == null) {
                checkpointState = new CheckpointState();
            }
            checkpointState.clear();
            levelGamestate = new LevelGamestate();
        } catch (IOException e) {
            LOGGER.log(SEVERE, "Failed to load level " + levelIndex, e);
            throw e;
        } catch (Exception e) {
            LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
            throw new IOException("Failed to load level due to unexpected error.", e);
        }
    }

    public void update() {
        Sprite player = null;
        AbstractPlayableSprite playable = null;
        boolean needsPlayer = objectManager != null || ringManager != null;
        if (needsPlayer) {
            player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            playable = player instanceof AbstractPlayableSprite ? (AbstractPlayableSprite) player : null;
        }
        if (objectManager != null) {
            objectManager.update(Camera.getInstance().getX(), playable);
        } else if (objectPlacementManager != null) {
            objectPlacementManager.update(Camera.getInstance().getX());
        }
        if (solidObjectManager != null) {
            solidObjectManager.update(playable);
        }
        if (touchResponseManager != null) {
            // Pass frameCounter + 1 to match lostRingManager.update for consistent ring timing
            touchResponseManager.update(playable, frameCounter + 1);
        }
        if (ringManager != null) {
            ringManager.update(Camera.getInstance().getX(), playable, frameCounter + 1);
        }
        if (lostRingManager != null) {
            lostRingManager.update(playable, frameCounter + 1);
        }
        if (levelGamestate != null) {
            levelGamestate.update();
            if (levelGamestate.getTimer().isTimeOver() && playable != null && !playable.getDead()) {
                playable.applyHurtOrDeath(0, AbstractPlayableSprite.DamageCause.TIME_OVER, false);
            }
        }
    }

    public void applyPlaneSwitchers(AbstractPlayableSprite player) {
        if (planeSwitcherManager == null || player == null) {
            return;
        }
        planeSwitcherManager.update(player);
    }

    public LevelGamestate getLevelGamestate() {
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

    private void initObjectArt() {
        if (!(game instanceof ObjectArtProvider provider)) {
            objectRenderManager = null;
            return;
        }
        try {
            var artData = provider.loadObjectArt();
            if (artData == null) {
                objectRenderManager = null;
                return;
            }
            objectRenderManager = new ObjectRenderManager(artData);
            int baseIndex = level != null ? level.getPatternCount() : 0;
            RingSpriteSheet ringSpriteSheet = level != null ? level.getRingSpriteSheet() : null;
            if (ringSpriteSheet != null) {
                baseIndex += ringSpriteSheet.getPatterns().length;
            }
            LOGGER.info("Initializing Object Art. Base Index: " + baseIndex);
            int hudBaseIndex = objectRenderManager.ensurePatternsCached(graphicsManager, baseIndex);

            hudRenderManager = new HudRenderManager(graphicsManager);

            Pattern[] hudDigits = artData.getHudDigitPatterns();
            LOGGER.info("Cached " + hudDigits.length + " HUD Digit patterns at index " + hudBaseIndex);
            for (int i = 0; i < hudDigits.length; i++) {
                graphicsManager.cachePatternTexture(hudDigits[i], hudBaseIndex + i);
            }
            hudRenderManager.setDigitPatternIndex(hudBaseIndex);

            int textBaseIndex = hudBaseIndex + hudDigits.length;
            Pattern[] hudText = artData.getHudTextPatterns();
            LOGGER.info("Cached " + hudText.length + " HUD Text patterns at index " + textBaseIndex);
            for (int i = 0; i < hudText.length; i++) {
                graphicsManager.cachePatternTexture(hudText[i], textBaseIndex + i);
            }
            hudRenderManager.setTextPatternIndex(textBaseIndex, hudText.length);

            int livesBaseIndex = textBaseIndex + hudText.length;
            Pattern[] hudLives = artData.getHudLivesPatterns();
            LOGGER.info("Cached " + hudLives.length + " HUD Lives patterns at index " + livesBaseIndex);
            for (int i = 0; i < hudLives.length; i++) {
                graphicsManager.cachePatternTexture(hudLives[i], livesBaseIndex + i);
            }
            hudRenderManager.setLivesPatternIndex(livesBaseIndex, hudLives.length);

            int livesNumbersBaseIndex = livesBaseIndex + hudLives.length;
            Pattern[] hudLivesNumbers = artData.getHudLivesNumbers();
            LOGGER.info("Cached " + hudLivesNumbers.length + " HUD Lives Numbers patterns at index "
                    + livesNumbersBaseIndex);
            for (int i = 0; i < hudLivesNumbers.length; i++) {
                graphicsManager.cachePatternTexture(hudLivesNumbers[i], livesNumbersBaseIndex + i);
            }
            hudRenderManager.setLivesNumbersPatternIndex(livesNumbersBaseIndex);

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

    public void drawWithSpritePriority(SpriteRenderManager spriteRenderManager) {
        if (level == null) {
            LOGGER.warning("No level loaded to draw.");
            return;
        }

        frameCounter++;
        if (animatedPatternManager != null) {
            animatedPatternManager.update();
        }
        if (animatedPaletteManager != null) {
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
        List<GLCommand> commands = new ArrayList<>(256);

        // Draw Background (Layer 1)
        if (useShaderBackground && graphicsManager.getBackgroundRenderer() != null) {
            renderBackgroundShader(commands, bgScrollY);
        }

        // Draw Foreground (Layer 0) low-priority pass - batched for performance
        graphicsManager.beginPatternBatch();
        drawLayer(commands, 0, camera, 1.0f, 1.0f, TilePriorityPass.LOW_ONLY, true, false);
        graphicsManager.flushPatternBatch();

        if (!commands.isEmpty()) {
            graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, commands));
        }

        if (ringManager != null) {
            ringManager.draw(frameCounter);
        }
        if (lostRingManager != null) {
            lostRingManager.draw(frameCounter);
        }

        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            if (spriteRenderManager != null) {
                spriteRenderManager.drawPriorityBucket(bucket, false);
            }
            if (objectManager != null) {
                objectManager.drawPriorityBucket(bucket, false);
            }
        }

        // Draw Foreground (Layer 0) high-priority pass - batched for performance
        graphicsManager.beginPatternBatch();
        drawLayer(commands, 0, camera, 1.0f, 1.0f, TilePriorityPass.HIGH_ONLY, false, false);
        graphicsManager.flushPatternBatch();

        for (int bucket = RenderPriority.MAX; bucket >= RenderPriority.MIN; bucket--) {
            if (spriteRenderManager != null) {
                spriteRenderManager.drawPriorityBucket(bucket, true);
            }
            if (objectManager != null) {
                objectManager.drawPriorityBucket(bucket, true);
            }
        }

        DebugObjectArtViewer.getInstance().draw(objectRenderManager, camera);

        if (hudRenderManager != null) {
            hudRenderManager.draw(levelGamestate);
        }

        boolean debugViewEnabled = configService.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
        boolean overlayEnabled = debugViewEnabled && overlayManager.isEnabled(DebugOverlayToggle.OVERLAY);
        if (overlayEnabled) {
            graphicsManager.enqueueDebugLineState();
        }

        if (objectPlacementManager != null && overlayEnabled) {
            boolean showObjectPoints = overlayManager.isEnabled(DebugOverlayToggle.OBJECT_POINTS);
            boolean showPlaneSwitchers = overlayManager.isEnabled(DebugOverlayToggle.PLANE_SWITCHERS);
            List<GLCommand> objectCommands = new ArrayList<>();
            List<GLCommand> switcherLineCommands = new ArrayList<>();
            List<GLCommand> switcherAreaCommands = new ArrayList<>();
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
            AbstractPlayableSprite playable = player instanceof AbstractPlayableSprite
                    ? (AbstractPlayableSprite) player
                    : null;
            for (ObjectSpawn spawn : objectPlacementManager.getActiveSpawns()) {
                if (showObjectPoints) {
                    objectCommands.add(new GLCommand(GLCommand.CommandType.VERTEX2I,
                            -1,
                            GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
                            1f, 0f, 1f,
                            spawn.x(), spawn.y(), 0, 0));
                }
                if (showPlaneSwitchers) {
                    appendPlaneSwitcherDebug(spawn, switcherLineCommands, switcherAreaCommands, playable);
                }
            }
            if (showPlaneSwitchers && !switcherAreaCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                for (GLCommand command : switcherAreaCommands) {
                    graphicsManager.registerCommand(command);
                }
            }
            if (showPlaneSwitchers && !switcherLineCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, switcherLineCommands));
            }
            if (showObjectPoints && !objectCommands.isEmpty()) {
                graphicsManager.enqueueDebugLineState();
                graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_POINTS, objectCommands));
            }
        }

        if (ringManager != null && overlayEnabled
                && overlayManager.isEnabled(DebugOverlayToggle.RING_BOUNDS)) {
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
                    graphicsManager.enqueueDebugLineState();
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
                        graphicsManager.enqueueDebugLineState();
                        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, boxCommands));
                    }
                    if (!centerCommands.isEmpty()) {
                        graphicsManager.enqueueDebugLineState();
                        graphicsManager.registerCommand(new GLCommandGroup(GL2.GL_LINES, centerCommands));
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
        int fboHeight = 256; // Increased to 256 (power of 2) to allow vertical buffer for smooth scrolling

        // Extra buffer on each side
        int extraBuffer = (fboWidth - 320) / 2; // 352 pixels on each side

        // Get pattern renderer's screen height for correct Y coordinate handling
        int screenHeightPixels = SonicConfigurationService.getInstance()
                .getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS);

        // Use zone-specific vertical scroll from parallax manager
        // This ensures zones like MCZ use their act-dependent BG Y calculations
        int actualBgScrollY = parallaxManager.getVscrollFactorBG();

        // 1. Resize FBO
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            bgRenderer.resizeFBO(gl, fboWidth, fboHeight);
        }));

        // 2. Begin Tile Pass (Bind FBO)
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            bgRenderer.beginTilePass(gl, screenHeightPixels);
        }));

        // 3. Draw background tiles to wider FBO
        graphicsManager.beginPatternBatch();
        drawBackgroundToFBOWide(commands, camera, actualBgScrollY, fboWidth, fboHeight, extraBuffer);
        graphicsManager.flushPatternBatch();

        // 4. End Tile Pass (Unbind FBO)
        graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.CUSTOM, (gl, cx, cy, cw, ch) -> {
            bgRenderer.endTilePass(gl);
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
            int screenW = SonicConfigurationService.getInstance().getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS);
            int screenH = screenHeightPixels;

            // Calculate vertical scroll offset (sub-chunk) for shader
            // The FBO is rendered aligned to 16-pixel chunk boundaries
            // The shader needs to shift the view by the remaining offset
            int vOffset = actualBgScrollY % LevelConstants.CHUNK_HEIGHT;
            if (vOffset < 0)
                vOffset += LevelConstants.CHUNK_HEIGHT; // Handle negative modulo

            final int finalVOffset = vOffset;

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

        // Render enough rows to fill the 256px FBO height
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
        if (!configService.getBoolean(SonicConfiguration.DEBUG_COLLISION_VIEW_ENABLED)) {
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
        int halfSpan = PlaneSwitcherManager.decodeHalfSpan(subtype);
        boolean horizontal = PlaneSwitcherManager.isHorizontal(subtype);
        int x = spawn.x();
        int y = spawn.y();
        int sideState = planeSwitcherManager != null ? planeSwitcherManager.getSideState(spawn) : -1;
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
            if (solidityBitIndex < 0x0E) {
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
        if (objectPlacementManager == null) {
            return List.of();
        }
        return objectPlacementManager.getActiveSpawns();
    }

    public TouchResponseManager getTouchResponseManager() {
        return touchResponseManager;
    }

    public ObjectRenderManager getObjectRenderManager() {
        return objectRenderManager;
    }

    public RingRenderManager getRingRenderManager() {
        return ringRenderManager;
    }

    public boolean areAllRingsCollected() {
        return ringPlacementManager != null && ringPlacementManager.areAllCollected();
    }

    public ObjectManager getObjectManager() {
        return objectManager;
    }

    public ObjectPlacementManager getObjectPlacementManager() {
        return objectPlacementManager;
    }

    public void spawnLostRings(AbstractPlayableSprite player, int frameCounter) {
        if (lostRingManager == null || player == null) {
            return;
        }
        int count = player.getRingCount();
        if (count <= 0) {
            return;
        }
        lostRingManager.spawnLostRings(player, count, frameCounter);
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
     * @param showTitleCard true to show title card on fresh starts, false for death respawns
     */
    private void loadCurrentLevel(boolean showTitleCard) {
        try {
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
                checkpointState.restoreFromSaved(checkpointX, checkpointY, checkpointCameraX, checkpointCameraY, checkpointIndex);
            }

            frameCounter = 0;
            Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));

            // Use checkpoint position if available, otherwise level start
            // Note: Spawn Y coordinates from ROM data represent the sprite's CENTER position,
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
            }

            // Request title card for level starts and death respawns
            // Original Sonic 2 shows title card on all respawns (with or without checkpoint)
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

    public SolidObjectManager getSolidObjectManager() {
        return solidObjectManager;
    }

    public CheckpointState getCheckpointState() {
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
     * @return true if next zone was requested
     */
    public boolean consumeNextZoneRequest() {
        boolean requested = nextZoneRequested;
        nextZoneRequested = false;
        return requested;
    }
}
