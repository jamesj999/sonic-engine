package com.openggf.game;

import com.openggf.TraceSessionLauncher;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.launch.LaunchProfile;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.graphics.PngTextureLoader;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.TexturedQuadRenderer;
import com.openggf.game.recording.UserRecordingCatalog;
import com.openggf.game.recording.menu.UserRecordingMenu;
import com.openggf.game.recording.menu.UserRecordingMenuState;
import com.openggf.game.timeattack.GhostStore;
import com.openggf.game.timeattack.TimeAttackMenu;
import com.openggf.game.timeattack.TimeAttackMenuState;
import com.openggf.game.timeattack.TimeAttackLaunchRequest;
import com.openggf.game.timeattack.mp.MultiplayerRaceCoordinator;
import com.openggf.game.timeattack.mp.RaceLobbyScreen;
import com.openggf.game.timeattack.mp.ServerBrowserScreen;
import com.openggf.net.protocol.ControlMessage;
import com.openggf.testmode.TestModeTracePicker;
import com.openggf.trace.catalog.TraceCatalog;
import com.openggf.trace.catalog.TraceEntry;
import com.openggf.version.AppVersion;

import org.lwjgl.system.MemoryUtil;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.function.ToIntFunction;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;

/**
 * Master title screen shown on startup for game selection.
 * Runs before gameplay initialization, using host UI and ROM-backed preview textures.
 *
 * <p>The hub fits the native 320x224 viewport. The left pane preserves animated
 * ROM logos and their aspect ratio; the right pane exposes launch and engine actions.
 * Wider viewports expand the panes without changing the navigation model.
 */
@com.openggf.game.ModApi
public class MasterTitleScreen {

    private static final Logger LOGGER = Logger.getLogger(MasterTitleScreen.class.getName());
    static final int SCREEN_W = 320;
    private static final int SCREEN_H = 224;
    private static final String MISSING_ROM_PROMPT = "Requires the following ROM:";
    private static final int TITLE_LOGO_BASE_SCALE_NUMERATOR = 35;
    private static final int TITLE_LOGO_BASE_SCALE_DENOMINATOR = 100;
    private static final int TITLE_LOGO_SCALE_NUMERATOR = 9;
    private static final int TITLE_LOGO_SCALE_DENOMINATOR = 10;
    private static final int TOP_UI_MATTE_HEIGHT = 0;
    private static final int BOTTOM_UI_MATTE_HEIGHT = 56;
    private static final float BOTTOM_UI_MATTE_ALPHA = 0.68f;
    static final int LAUNCH_HOVER_Y = 178;
    static final float LAUNCH_HOVER_SCALE = 0.55f;
    static final float LAUNCH_PANEL_OVERLAY_ALPHA = 0.72f;
    private static final int FITTED_TEXT_SIDE_PADDING = 4;

    record PreviewLayout(int width, int height, float x, float y) {
    }

    record MenuItemLayout(int entryIndex, String text, int x, int width) {
    }

    @com.openggf.game.ModApi
    public enum GameEntry {
        SONIC_1("Sonic The Hedgehog", "Sonic 1", "s1", SonicConfiguration.SONIC_1_ROM,
                "s1.gen"),
        SONIC_2("Sonic The Hedgehog 2", "Sonic 2", "s2", SonicConfiguration.SONIC_2_ROM,
                "s2.gen"),
        SONIC_3K("Sonic 3 & Knuckles", "Sonic 3K", "s3k", SonicConfiguration.SONIC_3K_ROM,
                "s3k.gen");

        public final String displayName;
        public final String menuLabel;
        public final String gameId;
        public final SonicConfiguration romConfigKey;
        public final String expectedRomFilename;

        GameEntry(String displayName,
                  String menuLabel,
                  String gameId,
                  SonicConfiguration romConfigKey,
                  String expectedRomFilename) {
            this.displayName = displayName;
            this.menuLabel = menuLabel;
            this.gameId = gameId;
            this.romConfigKey = romConfigKey;
            this.expectedRomFilename = expectedRomFilename;
        }

        public static GameEntry fromGameId(String gameId) {
            for (GameEntry entry : values()) {
                if (entry.gameId.equalsIgnoreCase(gameId)) {
                    return entry;
                }
            }
            throw new IllegalArgumentException("Unknown game id: " + gameId);
        }
    }

    @com.openggf.game.ModApi
    public enum State {
        INACTIVE, FADE_IN, ACTIVE, ERROR_DISPLAY, CONFIRMING, EXITING
    }

    /** Host-owned feedback events emitted before any game ROM is selected. */
    public enum AudioCue {
        NAVIGATE("UI_NAVIGATE"),
        CONFIRM("UI_CONFIRM"),
        ERROR("UI_ERROR");

        private final String sfxName;

        AudioCue(String sfxName) {
            this.sfxName = sfxName;
        }

        public String sfxName() {
            return sfxName;
        }
    }

    @FunctionalInterface
    public interface AudioSink {
        AudioSink NO_OP = cue -> { };

        void play(AudioCue cue);
    }

    // Cloud sprite for parallax animation
    private static class CloudSprite {
        int textureId;
        float x;
        float y;
        float speed;
        int width;
        int height;

        CloudSprite(int textureId, float x, float y, float speed, int width, int height) {
            this.textureId = textureId;
            this.x = x;
            this.y = y;
            this.speed = speed;
            this.width = width;
            this.height = height;
        }

        void update(int vpWidth) {
            x += speed;
            // Wrap when fully off the right edge
            if (x > vpWidth) {
                x = -width;
            }
            // Wrap when fully off the left edge (negative speed)
            if (x + width < 0) {
                x = vpWidth;
            }
        }
    }

    private State state = State.INACTIVE;
    private int selectedIndex = 1; // Default to Sonic 2
    private int frameCounter = 0;
    private int errorFrameCounter = 0;
    private String launchErrorTitle;
    private String launchErrorDetail;
    private static final int ERROR_DISPLAY_FRAMES = 180; // 3 seconds at 60fps

    private final List<MasterTitleEntry> entries;
    private final Map<GameEntry, Boolean> stockAvailability = new EnumMap<>(GameEntry.class);

    // GL resources
    private TexturedQuadRenderer renderer;
    private PixelFont font;
    // Loaded lazily when TEST_MODE_ENABLED fires. Matches the rest
    // of the debug overlay (no drop shadow).
    private PixelFont pickerFont;
    private TestModeTracePicker tracePicker;
    private UserRecordingMenu userRecordingMenu;
    private UserRecordingMenuFactory userRecordingMenuFactory = this::createUserRecordingMenu;
    private UserRecordingMenu.PlaybackStarter userRecordingPlaybackStarter =
            (entry, options) -> LOGGER.info("User recording playback callback not configured.");
    private TimeAttackMenu timeAttackMenu;
    private TimeAttackMenuFactory timeAttackMenuFactory = this::createTimeAttackMenu;
    private TimeAttackMenu.LaunchStarter timeAttackLaunchStarter =
            request -> LOGGER.info("Time attack launch callback not configured.");
    private TimeAttackMenu.NetworkStarter timeAttackNetworkStarter = TimeAttackMenu.NetworkStarter.NONE;
    private RaceLobbyScreen raceLobbyScreen;
    private ServerBrowserScreen serverBrowserScreen;
    private int bgTextureId;
    private int solidWhiteTextureId; // 1x1 white texture for solid color overlays
    private int titleTextId;
    private int titleTextWidth, titleTextHeight;
    private int cloudLargeTextureId;
    private int cloudLargeWidth, cloudLargeHeight;
    private int cloudSmallTextureId;
    private int cloudSmallWidth, cloudSmallHeight;
    private final Map<GameEntry, RomPreviewState> romPreviews = new EnumMap<>(GameEntry.class);
    private int previewAnimationFrame = 0;

    private final List<CloudSprite> clouds = new ArrayList<>();
    private final SonicConfigurationService configService;
    private final LaunchProfileStore launchProfileStore;
    private final AudioSink audioSink;
    private LaunchConfigPanel launchConfigPanel;
    private boolean programmaticSelection;
    private final TitleHubNavigation navigation = new TitleHubNavigation();
    private InputHandler menuInput;
    private EngineSettingsScreen settingsScreen;
    private float[] currentProjection;
    private boolean toolsOpen;
    private int toolIndex;
    private boolean helpOpen;
    private boolean modsShortcutHeld;
    private boolean childInputPending;
    private Runnable modManagerOpenHandler =
            () -> LOGGER.info("Mod manager open callback not configured.");
    private ModManagerScreenFactory modManagerScreenFactory;
    private ModManagerView modManagerScreen;

    /**
     * Projection width supplied by Engine each frame. Defaults to SCREEN_W (320)
     * so the screen behaves identically to native when no explicit width is set.
     * At widescreen (e.g. 400, 528) the background/clouds expand to fill the full
     * width while foreground elements stay centered.
     */
    private int viewportWidth = SCREEN_W;

    private boolean gameSelected = false;
    private MasterTitleEntry.Launch selectedLaunch;
    private boolean standaloneActionOpen;
    private int standaloneActionIndex;

    private static final class RomPreviewState {
        int textureId;
        int width;
        int height;
        int frameToken = Integer.MIN_VALUE;
        MasterTitleRomPreview.PreviewSequence sequence;
    }

    public MasterTitleScreen() {
        this(GameServices.configuration());
    }

    public MasterTitleScreen(SonicConfigurationService configService) {
        this(configService, new LaunchProfileStore(configService));
    }

    public MasterTitleScreen(SonicConfigurationService configService,
                             LaunchProfileStore launchProfileStore) {
        this(configService, launchProfileStore, List.of(
                new MasterTitleEntry.Stock(GameEntry.SONIC_1),
                new MasterTitleEntry.Stock(GameEntry.SONIC_2),
                new MasterTitleEntry.Stock(GameEntry.SONIC_3K)), AudioSink.NO_OP);
    }

    public MasterTitleScreen(SonicConfigurationService configService,
                             LaunchProfileStore launchProfileStore,
                             AudioSink audioSink) {
        this(configService, launchProfileStore, List.of(
                new MasterTitleEntry.Stock(GameEntry.SONIC_1),
                new MasterTitleEntry.Stock(GameEntry.SONIC_2),
                new MasterTitleEntry.Stock(GameEntry.SONIC_3K)), audioSink);
    }

    public MasterTitleScreen(SonicConfigurationService configService,
                             LaunchProfileStore launchProfileStore,
                             List<MasterTitleEntry> entries) {
        this(configService, launchProfileStore, entries, AudioSink.NO_OP);
    }

    public MasterTitleScreen(SonicConfigurationService configService,
                             LaunchProfileStore launchProfileStore,
                             List<MasterTitleEntry> entries,
                             AudioSink audioSink) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.launchProfileStore = Objects.requireNonNull(launchProfileStore, "launchProfileStore");
        this.entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
        this.audioSink = Objects.requireNonNull(audioSink, "audioSink");
        if (this.entries.isEmpty()) throw new IllegalArgumentException("Master title requires entries");
        this.selectedIndex = defaultSelectionIndex(this.entries);
        for (GameEntry game : GameEntry.values()) stockAvailability.put(game, false);
    }

    public void initialize() {
        // Check ROM availability
        for (MasterTitleEntry entry : entries) {
            if (entry instanceof MasterTitleEntry.Stock stock) {
                String romPath = configService.getString(stock.game().romConfigKey);
                stockAvailability.put(stock.game(),
                        romPath != null && !romPath.isEmpty() && new File(romPath).exists());
            }
        }

        try {
            // Initialize renderer and font
            renderer = new TexturedQuadRenderer();
            renderer.init();

            font = new com.openggf.graphics.MenuPixelFont();
            font.init("pixel-font.png", renderer);

            // Load background
            bgTextureId = PngTextureLoader.loadTexture("titlescreen/bg.png");

            // Create 1x1 solid white texture for overlays
            solidWhiteTextureId = createSolidWhiteTexture();

            // Load title text
            titleTextId = PngTextureLoader.loadTexture("titlescreen/titletext.png");
            titleTextWidth = PngTextureLoader.getLastWidth();
            titleTextHeight = PngTextureLoader.getLastHeight();

            // Load cloud textures
            cloudLargeTextureId = PngTextureLoader.loadTexture("titlescreen/cloud-l.png");
            cloudLargeWidth = PngTextureLoader.getLastWidth();
            cloudLargeHeight = PngTextureLoader.getLastHeight();

            cloudSmallTextureId = PngTextureLoader.loadTexture("titlescreen/cloud-s.png");
            cloudSmallWidth = PngTextureLoader.getLastWidth();
            cloudSmallHeight = PngTextureLoader.getLastHeight();

            // Randomly generate clouds with varied positions and speeds
            Random rng = new Random();
            int cloudCount = 5 + rng.nextInt(3); // 5-7 clouds
            for (int i = 0; i < cloudCount; i++) {
                boolean large = rng.nextBoolean();
                int texId = large ? cloudLargeTextureId : cloudSmallTextureId;
                int cw = large ? cloudLargeWidth : cloudSmallWidth;
                int ch = large ? cloudLargeHeight : cloudSmallHeight;
                float x = rng.nextFloat() * (SCREEN_W + cw) - cw;
                float y = 55 + rng.nextFloat() * 100; // y range 55-155
                float speed = 0.08f + rng.nextFloat() * 0.4f; // speed 0.08-0.48
                clouds.add(new CloudSprite(texId, x, y, speed, cw, ch));
            }

            loadRomPreviews();
            resetRomPreviewTextureFrames();

            state = State.FADE_IN;
            LOGGER.info("Master title screen initialized");

        } catch (IOException e) {
            LOGGER.severe("Failed to initialize master title screen: " + e.getMessage());
            throw new RuntimeException("Failed to initialize master title screen", e);
        }
    }

    /**
     * Creates a 1x1 opaque white texture for use as a solid-color overlay base.
     */
    private static int createSolidWhiteTexture() {
        ByteBuffer pixel = MemoryUtil.memAlloc(4);
        pixel.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
        int texId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texId);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pixel);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glBindTexture(GL_TEXTURE_2D, 0);
        MemoryUtil.memFree(pixel);
        return texId;
    }

    /**
     * Updates the title screen state. Called once per frame from GameLoop.
     */
    public void update(InputHandler inputHandler) {
        menuInput = inputHandler;
        navigation.capture(inputHandler);
        frameCounter++;
        advancePreviewAnimationFrame();

        // Update cloud animation
        for (CloudSprite cloud : clouds) {
            cloud.update(viewportWidth);
        }

        if (state == State.FADE_IN) {
            // Transition to active after a brief delay
            if (frameCounter > 10) {
                state = State.ACTIVE;
            }
            return;
        }

        if (state == State.ERROR_DISPLAY) {
            errorFrameCounter++;
            if (errorFrameCounter >= ERROR_DISPLAY_FRAMES || navigation.back() || navigation.accept()) {
                state = State.ACTIVE;
                errorFrameCounter = 0;
            }
            return;
        }

        if (state == State.CONFIRMING || state == State.EXITING) {
            return; // Waiting for fade
        }

        if (state != State.ACTIVE) {
            return;
        }

        if (childInputPending) {
            if (MenuInput.accept(inputHandler) || MenuInput.back(inputHandler) || MenuInput.up(inputHandler)
                    || MenuInput.down(inputHandler) || MenuInput.left(inputHandler) || MenuInput.right(inputHandler)) return;
            childInputPending = false;
        }
        if (settingsScreen != null) {
            settingsScreen.update(inputHandler);
            if (settingsScreen.consumeApplied()) refreshRomPreviews();
            if (settingsScreen.consumeCloseRequested()) { settingsScreen = null; playErrorSound(); }
            return;
        }
        if (helpOpen) {
            if (navigation.back()) { helpOpen = false; playErrorSound(); }
            return;
        }
        if (toolsOpen) {
            updateTools();
            return;
        }
        if (modManagerScreen != null) {
            modManagerScreen.update(inputHandler);
            if (modManagerScreen.consumeCloseRequested()) {
                modManagerScreen = null;
                playErrorSound();
            }
            return;
        }

        if (tracePicker != null || configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)) {
            userRecordingMenu = null;
            timeAttackMenu = null;
            raceLobbyScreen = null;
            serverBrowserScreen = null;
            if (tracePicker == null) {
                Path root = Path.of(System.getProperty("user.dir"))
                        .resolve(configService.getString(SonicConfiguration.TRACE_CATALOG_DIR))
                        .normalize();
                tracePicker = new TestModeTracePicker(
                        TraceCatalog.scan(root), ensurePickerFont());
            }
            tracePicker.update(inputHandler);
            switch (tracePicker.consumeResult()) {
                case LAUNCH -> {
                    TraceEntry entry = tracePicker.selectedEntry();
                    if (entry != null) {
                        if (TraceSessionLauncher.launch(entry)) {
                            tracePicker = null;
                        } else if (tracePicker != null) {
                            // Keep the diagnostic, but release the loading latch so
                            // acknowledging it permits selection and another launch.
                            tracePicker.launchFailed();
                        }
                    }
                }
                case BACK -> {
                    // Disable test mode for this session so normal game-select runs
                    configService.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
                    tracePicker = null;
                    playErrorSound();
                }
                case NONE -> { }
            }
            return;
        }

        if (raceLobbyScreen != null) {
            raceLobbyScreen.update(inputHandler);
            return;
        }

        if (serverBrowserScreen != null) {
            serverBrowserScreen.update(inputHandler);
            return;
        }

        if (userRecordingMenu != null) {
            userRecordingMenu.update(inputHandler);
            if (userRecordingMenu.consumeCloseRequested()) {
                userRecordingMenu = null;
                playErrorSound();
            }
            return;
        }

        if (timeAttackMenu != null) {
            TimeAttackMenu menu = timeAttackMenu;
            menu.update(inputHandler);
            // The launch starter may synchronously tear this screen down
            // (Engine.launchTimeAttack -> cleanup()), nulling timeAttackMenu
            // mid-update; re-check the field before touching it again.
            if (timeAttackMenu != null && menu.consumeCloseRequested()) {
                timeAttackMenu = null;
                playErrorSound();
            }
            return;
        }

        if (launchConfigPanel != null) {
            launchConfigPanel.update(inputHandler);
            LaunchConfigPanel.Result result = launchConfigPanel.consumeResult();
            if (result == LaunchConfigPanel.Result.CANCELLED) {
                launchConfigPanel = null;
                playErrorSound();
            } else if (result == LaunchConfigPanel.Result.CLOSED) {
                MasterTitleEntry.Stock stock = (MasterTitleEntry.Stock) selectedEntry();
                try {
                    launchProfileStore.save(stock.game(), launchConfigPanel.currentProfile());
                    launchConfigPanel = null;
                    playConfirmSound();
                } catch (java.io.UncheckedIOException failure) {
                    LOGGER.warning("Could not save launch profile: " + failure.getMessage());
                    launchConfigPanel.saveFailed();
                    playErrorSound();
                }
            }
            return;
        }

        if (standaloneActionOpen) {
            updateStandaloneActionChooser(inputHandler);
            return;
        }

        // Shortcuts remain optional; every action is also in the visible menu.
        boolean modsShortcut = inputHandler.isKeyPressedWithoutModifiers(GLFW_KEY_M);
        boolean openMods = modsShortcut && !modsShortcutHeld;
        modsShortcutHeld = modsShortcut;
        if (openMods) { openModManager(); return; }
        int recordKey = configService.getInt(SonicConfiguration.RECORDING_RECORD_KEY);
        boolean recordingRequest = inputHandler.isKeyPressed(recordKey) && inputHandler.isShiftDown();
        if (handleUserRecordingMenuRequest(recordingRequest) || recordingRequest) return;
        boolean timeRequest = inputHandler.isKeyPressed(configService.getInt(SonicConfiguration.TIME_ATTACK_MENU_KEY));
        if (handleTimeAttackMenuRequest(timeRequest) || timeRequest) return;
        if (inputHandler.isKeyPressed(GLFW_KEY_TAB) || inputHandler.isGamepadBackButtonPressed()) {
            openLaunchOptions();
            return;
        }

        if (!navigation.actions()) {
            if (navigation.up() && setSelectedIndex(selectedIndex - 1)) playNavigateSound();
            if (navigation.down() && setSelectedIndex(selectedIndex + 1)) playNavigateSound();
            if (navigation.right() || navigation.accept()) {
                navigation.enter();
                playNavigateSound();
            }
            return;
        }
        if (navigation.back()) { navigation.leave(); playErrorSound(); return; }
        if (navigation.left()) { navigation.leave(); playNavigateSound(); return; }
        if (navigation.up() && navigation.move(-1)) { playNavigateSound(); return; }
        if (navigation.down() && navigation.move(1)) { playNavigateSound(); return; }
        if (!navigation.accept()) return;
        switch (navigation.action()) {
            case START -> startSelectedEntry();
            case LAUNCH -> { if (!openLaunchOptions()) showUnavailableAction("Launch options require a stock game ROM"); }
            case TIME_ATTACK -> { if (!tryOpenTimeAttackMenu()) showUnavailableAction("Time attack requires a stock game ROM"); }
            case RECORDINGS -> { if (!tryOpenUserRecordingMenuForSelectedGame()) showUnavailableAction("No recording menu for this selection"); }
            case MODS -> openModManager();
            case SETTINGS -> openSettings();
            case TOOLS -> { toolsOpen = true; toolIndex = 0; playConfirmSound(); }
        }
    }

    private void openSettings() {
        settingsScreen = new EngineSettingsScreen(configService, font, renderer, solidWhiteTextureId);
        playConfirmSound();
        childInputPending = true;
    }

    private void updateTools() {
        if (navigation.back()) { toolsOpen = false; playErrorSound(); return; }
        if (navigation.up()) toolIndex = Math.max(0, toolIndex - 1);
        if (navigation.down()) toolIndex = Math.min(2, toolIndex + 1);
        if (!navigation.accept()) return;
        toolsOpen = false;
        if (toolIndex == 0) {
            Path root = Path.of(System.getProperty("user.dir"))
                    .resolve(configService.getString(SonicConfiguration.TRACE_CATALOG_DIR)).normalize();
            tracePicker = new TestModeTracePicker(TraceCatalog.scan(root), ensurePickerFont());
            childInputPending = true;
            playConfirmSound();
        } else if (toolIndex == 1) openSettings();
        else { helpOpen = true; playConfirmSound(); }
    }

    private boolean openLaunchOptions() {
        if (!(selectedEntry() instanceof MasterTitleEntry.Stock stock) || !isEntryAvailable(stock)) return false;
        launchConfigPanel = new LaunchConfigPanel(stock.game(), launchProfileStore.load(stock.game()),
                launchProfileStore, configService, font, renderer);
        playConfirmSound();
        return true;
    }

    private void startSelectedEntry() {
        MasterTitleEntry entry = selectedEntry();
        if (!isEntryAvailable(entry)) {
            launchErrorTitle = null;
            state = State.ERROR_DISPLAY;
            errorFrameCounter = 0;
            playErrorSound();
        } else if (entry instanceof MasterTitleEntry.Standalone) {
            standaloneActionOpen = true;
            standaloneActionIndex = 0;
            playConfirmSound();
        } else confirmLaunch(new MasterTitleEntry.Launch(entry, MasterTitleEntry.Action.NEW_GAME), false);
    }

    private void showUnavailableAction(String detail) {
        launchErrorTitle = "ACTION UNAVAILABLE";
        launchErrorDetail = detail;
        state = State.ERROR_DISPLAY;
        errorFrameCounter = 0;
        playErrorSound();
    }

    private void refreshRomPreviews() {
        for (GameEntry game : GameEntry.values()) {
            String path = configService.getString(game.romConfigKey);
            stockAvailability.put(game, path != null && !path.isBlank() && new File(path).isFile());
        }
        if (renderer == null) return;
        romPreviews.values().forEach(preview -> PngTextureLoader.deleteTexture(preview.textureId));
        romPreviews.clear();
        loadRomPreviews();
        previewAnimationFrame = 0;
    }

    private void updateStandaloneActionChooser(InputHandler input) {
        List<MasterTitleEntry.Action> actions = standaloneActionsForTest();
        if (navigation.back()) { standaloneActionOpen = false; playErrorSound(); return; }
        if (navigation.up()) standaloneActionIndex = Math.max(0, standaloneActionIndex - 1);
        if (navigation.down()) standaloneActionIndex = Math.min(actions.size() - 1, standaloneActionIndex + 1);
        if (navigation.accept()) {
            confirmLaunch(new MasterTitleEntry.Launch(selectedEntry(), actions.get(standaloneActionIndex)), false);
            standaloneActionOpen = false;
        }
    }

    private void confirmLaunch(MasterTitleEntry.Launch launch, boolean programmatic) {
        selectedLaunch = launch;
        state = State.CONFIRMING;
        playConfirmSound();
        programmaticSelection = programmatic;
        gameSelected = true;
    }

    /**
     * Draws the title screen. Called once per frame from Engine.draw().
     */
    public void draw() {
        if (modManagerScreen != null) {
            drawNativePage(modManagerScreen::render);
            return;
        }
        if (renderer == null) return;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        drawContent();
    }

    /**
     * Runs the title draw body without touching OpenGL blend state.
     * Package-private for command-recording tests that use recording renderers
     * without a live context.
     */
    void drawForRecording() {
        if (renderer != null) {
            drawContent();
        }
    }

    private void drawNativePage(Runnable page) {
        if (renderer == null || font == null) { page.run(); return; }
        MenuStyle.fill(font, 0, 0, viewportWidth, SCREEN_H, .025f, .065f, .19f, 1);
        MenuStyle.checkerboard(font, viewportWidth);
        int margin = Math.max(0, (viewportWidth - SCREEN_W) / 2);
        if (margin == 0 || currentProjection == null) { page.run(); return; }
        float[] centered = new org.joml.Matrix4f()
                .ortho2D(-margin, viewportWidth - margin, 0, SCREEN_H).get(new float[16]);
        renderer.setProjectionMatrix(centered);
        try { page.run(); }
        finally { renderer.setProjectionMatrix(currentProjection); }
    }

    private void drawContent() {

        if (tracePicker != null) {
            drawNativePage(tracePicker::render);
            return;
        }

        if (raceLobbyScreen != null) {
            drawNativePage(raceLobbyScreen::render);
            return;
        }

        if (serverBrowserScreen != null) {
            drawNativePage(serverBrowserScreen::render);
            return;
        }

        if (userRecordingMenu != null) {
            drawNativePage(userRecordingMenu::render);
            return;
        }

        if (timeAttackMenu != null) {
            drawNativePage(timeAttackMenu::render);
            return;
        }

        if (settingsScreen != null) {
            settingsScreen.render(viewportWidth);
            return;
        }
        drawHub();

        if (launchConfigPanel != null) {
            renderer.drawTexture(solidWhiteTextureId, 0, 0, viewportWidth, SCREEN_H,
                    0.02f, 0.07f, 0.23f, 1f);
            launchConfigPanel.render(viewportWidth);
            return;
        }

        if (state == State.ERROR_DISPLAY) {
            font.beginMegaBatch();
            MasterTitleEntry entry = selectedEntry();
            MenuStyle.page(font, viewportWidth, "UNABLE TO START", entry.displayName());
            MenuStyle.panel(font, 9, 57, viewportWidth - 18, 132);
            MenuStyle.label(font, launchErrorTitle == null ? "ROM NOT FOUND" : launchErrorTitle,
                    17, 68, viewportWidth - 34, 1f, .35f, .35f);
            String detail = launchErrorDetail;
            if (detail == null && entry instanceof MasterTitleEntry.Stock stock)
                detail = configService.getString(stock.game().romConfigKey);
            if (detail == null || detail.isBlank()) detail = "No ROM path configured";
            int columns = Math.max(1, (viewportWidth - 34) / 6);
            for (int offset = 0, row = 0; offset < detail.length() && row < 5; offset += columns, row++)
                MenuStyle.text(font, detail.substring(offset, Math.min(detail.length(), offset + columns)),
                        17, 96 + row * 12, viewportWidth - 34, .85f, .9f, 1);
            MenuStyle.text(font, "Check Files in Engine Settings", 17, 172, viewportWidth - 34, 1, .78f, .3f);
            MenuStyle.footer(font, viewportWidth, "Your selection is preserved",
                    confirmHint() + "/" + backHint() + " Back");
            font.endMegaBatch();
        }
    }

    private String confirmHint() { return menuInput == null ? "Enter" : MenuInput.confirmLabel(menuInput); }
    private String backHint() { return menuInput == null ? "Esc" : MenuInput.backLabel(menuInput); }
    private String directionHint() { return menuInput == null ? "Arrows" : MenuInput.directionLabel(menuInput); }

    /** Native-pixel layout; preview art keeps its source aspect ratio at every width. */
    static PreviewLayout hubLogoLayout(int sourceWidth, int sourceHeight, int viewportWidth) {
        int paneWidth = Math.max(SCREEN_W, viewportWidth) / 2 - 18;
        double scale = Math.min((double) paneWidth / Math.max(1, sourceWidth), 112.0 / Math.max(1, sourceHeight));
        int width = Math.max(1, (int) Math.floor(sourceWidth * scale));
        int height = Math.max(1, (int) Math.floor(sourceHeight * scale));
        return new PreviewLayout(width, height, 9 + (paneWidth - width) / 2f,
                SCREEN_H - 43 - (112 - height) / 2f - height);
    }

    private void drawHub() {
        renderer.drawTexture(bgTextureId, 0, 0, viewportWidth, SCREEN_H);
        for (CloudSprite cloud : clouds) {
            renderer.drawTexture(cloud.textureId, cloud.x, SCREEN_H - cloud.y - cloud.height,
                    cloud.width, cloud.height, 1f, 1f, 1f, 0.38f);
        }
        box(0, 0, viewportWidth, SCREEN_H, 0.02f, 0.07f, 0.23f, 0.86f);
        MenuStyle.checkerboard(font, viewportWidth);
        box(0, 0, viewportWidth, 30, 0.04f, 0.14f, 0.36f, 1f);
        box(0, 29, viewportWidth, 1, 1f, 0.84f, 0.3f, 1f);
        box(0, 205, viewportWidth, 19, 0.01f, 0.03f, 0.12f, 1f);
        if (toolsOpen || helpOpen) {
            drawToolsAndHelp();
            return;
        }
        if (standaloneActionOpen) {
            font.beginMegaBatch();
            MenuStyle.page(font, viewportWidth, "START GAME", selectedEntry().menuLabel());
            drawStandaloneActions();
            textFitted(directionHint() + " Choose  " + confirmHint() + " Start  " + backHint() + " Back",
                    9, 211, viewportWidth - 18, 1f, 0.5f, 0.91f, 1f);
            font.endMegaBatch();
            return;
        }
        // Keep OpenGGF branding and ROM-decoded game logos, never mockup artwork.
        int logoW = Math.min(110, titleLogoScaledWidth(titleTextWidth));
        int logoH = titleTextWidth == 0 ? 1 : Math.max(1, titleTextHeight * logoW / titleTextWidth);
        renderer.drawTexture(titleTextId, 10, SCREEN_H - 6 - logoH, logoW, logoH);
        RomPreviewState preview = selectedPreview();
        if (preview != null) {
            // Keep the original ROM texture. Engine draws this quad into the final
            // window viewport: at 4x, source texels regain detail rather than enlarging
            // an image reduced to the 320x224 menu grid.
            updateSelectedRomPreviewTexture();
            PreviewLayout layout = hubLogoLayout(preview.width, preview.height, viewportWidth);
            renderer.drawTexture(preview.textureId, layout.x(), layout.y(), layout.width(), layout.height());
        }
        int leftWidth = viewportWidth / 2 - 18;
        int actionX = viewportWidth / 2;
        int actionWidth = viewportWidth - actionX - 9;
        for (int i = 0; i < TitleHubNavigation.Action.values().length; i++) {
            int y = 43 + i * 21;
            box(actionX, y, actionWidth, 18, 0.05f, 0.16f, 0.38f, 1f);
            if (navigation.actions() && navigation.selected() == i) focusBox(actionX, y, actionWidth, 18);
        }
        if (!navigation.actions()) focusBox(8, 158, leftWidth + 2, 19);
        font.beginMegaBatch();
        textFitted(navigation.actions() ? "ACTION MENU" : "SELECT GAME", viewportWidth - 111, 10, 103,
                1f, 0.5f, 0.91f, 1f);
        if (preview == null) {
            textFitted(selectedEntry() instanceof MasterTitleEntry.Stock ? "ROM NOT FOUND" : "STANDALONE GAME",
                    12, 75, leftWidth - 6, 0.8f, 0.9f, 0.9f, 1f);
            if (selectedEntry() instanceof MasterTitleEntry.Stock stock) {
                textFitted(expectedRomFilename(stock.game()), 12, 96, leftWidth - 6, 0.75f, 1f, 0.73f, 0.3f);
                textFitted("Configure in Settings", 12, 116, leftWidth - 6, 0.62f, 0.65f, 0.8f, 1f);
            }
        }
        textFitted(selectedEntry().menuLabel(), 12, 163, leftWidth - 6, 1f, 1f, 1f, 1f);
        textFitted(navigation.actions() ? "Left: Games" : "Right: Menu",
                12, 182, leftWidth - 6, 1f, 0.5f, 0.91f, 1f);
        for (TitleHubNavigation.Action action : TitleHubNavigation.Action.values()) {
            boolean available = action == TitleHubNavigation.Action.MODS || action == TitleHubNavigation.Action.SETTINGS
                    || action == TitleHubNavigation.Action.TOOLS || action == TitleHubNavigation.Action.START
                    && isEntryAvailable(selectedEntry()) || selectedEntry() instanceof MasterTitleEntry.Stock
                    && isEntryAvailable(selectedEntry());
            float brightness = available && navigation.actions() ? 1f : 0.68f;
            textFitted(action.label, actionX + 5, 47 + action.ordinal() * 21, actionWidth - 10,
                    1f, brightness, brightness, brightness);
        }
        if (selectedEntry() instanceof MasterTitleEntry.Stock stock) {
            LaunchProfile profile = launchProfileStore.load(stock.game());
            if (profile != null) {
                int count = profile.enabledCount(stock.game());
                boolean experimental = profile.isExperimental(LaunchProfile.Row.WIDESCREEN);
                textFitted(count == 0 ? "Stock profile" : count + " changes", 12, 195, leftWidth - 6,
                        1f, 1f, experimental ? 0.3f : count > 0 ? 0.72f : 1f,
                        experimental ? 0.3f : count > 0 ? 0.25f : 1f);
            }
        }
        String footer = navigation.actions()
                ? "Up/Down Menu  " + confirmHint() + " Open  Left Games"
                : "Up/Down Game  Right Menu";
        textFitted(footer, 9, 211, viewportWidth - 18, 1f, 0.5f, 0.91f, 1f);
        if (standaloneActionOpen) drawStandaloneActions();
        font.endMegaBatch();
    }

    private void drawToolsAndHelp() {
        if (toolsOpen) focusBox(10, 49 + toolIndex * 27, viewportWidth - 20, 22);
        font.beginMegaBatch();
        font.drawText(toolsOpen ? "ENGINE TOOLS" : "CONTROLS / HELP", 10, 10, 1f, 1f, 1f, 1f);
        String[] lines = toolsOpen ? new String[] { "Trace replays", "Engine settings", "Controls / help" }
                : new String[] { "Up/Down: game or action", "Left/Right: switch pane", confirmHint() + ": open action",
                    backHint() + ": return one screen", "Settings: draft, then Apply" };
        for (int i = 0; i < lines.length; i++) textFitted(lines[i], 17, 55 + i * 27,
                viewportWidth - 34, 1f, 1f, 1f, 1f);
        textFitted(toolsOpen ? directionHint() + " Menu  " + confirmHint() + " Open  " + backHint() + " Back"
                : backHint() + " Back", 9, 211, viewportWidth - 18, 1f, 0.5f, 0.91f, 1f);
        font.endMegaBatch();
    }

    private void textFitted(String text, int x, int y, int maxWidth, float preferredScale,
                            float r, float g, float b) {
        if (preferredScale >= 1f && text.length() * PixelFont.glyphWidth() <= maxWidth) {
            font.drawText(text, x, y, 1f, r, g, b, 1f);
        } else {
            MenuStyle.text(font, text, x, y, maxWidth, r, g, b);
        }
    }

    private void box(int x, int y, int width, int height, float r, float g, float b, float alpha) {
        renderer.drawTexture(solidWhiteTextureId, x, SCREEN_H - y - height, width, height, r, g, b, alpha);
    }

    private void focusBox(int x, int y, int width, int height) {
        box(x, y, width, 1, 0.5f, 0.91f, 1f, 1f);
        box(x, y + height - 1, width, 1, 0.5f, 0.91f, 1f, 1f);
        box(x, y, 1, height, 0.5f, 0.91f, 1f, 1f);
        box(x + width - 1, y, 1, height, 0.5f, 0.91f, 1f, 1f);
    }

    private void drawGameMenu() {
        int menuY = 190;
        List<String> labels = entries.stream().map(MasterTitleEntry::menuLabel).toList();
        for (MenuItemLayout item : menuItemLayouts(
                labels, selectedIndex, viewportWidth, font::measureWidth)) {
            MasterTitleEntry entry = entries.get(item.entryIndex());
            float[] color = menuTextColor(isEntryAvailable(entry),
                    item.entryIndex() == selectedIndex, frameCounter);
            font.drawText(item.text(), item.x(), menuY,
                    color[0], color[1], color[2], color[3]);
        }
    }


    private void loadRomPreviews() {
        for (MasterTitleEntry entry : entries) {
            if (!(entry instanceof MasterTitleEntry.Stock stock) || !isEntryAvailable(entry)) continue;
            GameEntry game = stock.game();
            Path path = Path.of(configService.getString(game.romConfigKey));
            MasterTitleRomPreview.loadSequenceFor(game, path).ifPresent(sequence -> {
                RomPreviewState preview = new RomPreviewState();
                preview.sequence = sequence;
                MasterTitleRomPreview.Image firstFrame = sequence.imageAt(0);
                preview.textureId = MasterTitleRomPreview.uploadTexture(firstFrame);
                preview.width = firstFrame.width();
                preview.height = firstFrame.height();
                romPreviews.put(game, preview);
            });
        }
    }

    private void drawSelectedRomPreview() {
        RomPreviewState preview = selectedPreview();
        if (preview == null) return;
        updateSelectedRomPreviewTexture();
        PreviewLayout layout = romPreviewLayout(preview.width, preview.height, viewportWidth);
        renderer.drawTexture(preview.textureId, layout.x(), layout.y(), layout.width(), layout.height());
    }

    private void drawRomPreviewUiMattes() {
        if (selectedPreview() == null) return;
        PreviewLayout bottom = bottomUiMatteLayout(viewportWidth);
        drawMatte(bottom, BOTTOM_UI_MATTE_ALPHA);
    }

    private void drawMatte(PreviewLayout layout, float alpha) {
        if (layout.height() <= 0 || layout.width() <= 0) {
            return;
        }
        renderer.drawTexture(solidWhiteTextureId, layout.x(), layout.y(), layout.width(), layout.height(),
                0f, 0f, 0f, alpha);
    }

    private void updateSelectedRomPreviewTexture() {
        RomPreviewState preview = selectedPreview();
        if (preview == null) return;
        int token = preview.sequence.frameTokenAt(previewAnimationFrame);
        if (preview.frameToken == token) return;
        MasterTitleRomPreview.Image frame = preview.sequence.imageAt(previewAnimationFrame);
        MasterTitleRomPreview.updateTexture(preview.textureId, frame);
        preview.frameToken = token;
    }

    private void drawSelectedMissingRomPrompt() {
        MasterTitleEntry selected = selectedEntry();
        if (selected instanceof MasterTitleEntry.Standalone standalone) {
            drawScaledCenteredText(standalone.displayName(), 98, 1f,
                    1f, 0.9f, 0.9f, 1f);
            if (standaloneActionOpen) drawStandaloneActions();
            return;
        }
        if (isEntryAvailable(selected)) {
            return;
        }
        GameEntry entry = ((MasterTitleEntry.Stock) selected).game();
        font.drawTextCentered(MISSING_ROM_PROMPT, viewportWidth, 82, 1f, 0.25f, 0.25f, 1f);

        String filename = missingRomFilenameLine(entry);
        float filenameScale = Math.min(0.72f, (viewportWidth - 36f) / Math.max(1, font.measureWidth(filename)));
        int filenameWidth = font.measureWidth(filename, filenameScale);
        float filenameX = centerX(filenameWidth, viewportWidth);
        font.drawText(filename, filenameX, 98, filenameScale, 1f, 0.55f, 0.55f, 1f);
    }

    private void drawStandaloneActions() {
        List<MasterTitleEntry.Action> actions = standaloneActionsForTest();
        for (int i = 0; i < actions.size(); i++) {
            String label = (i == standaloneActionIndex ? "> " : "  ")
                    + (actions.get(i) == MasterTitleEntry.Action.NEW_GAME ? "NEW GAME" : "CONTINUE");
            MenuStyle.panel(font, viewportWidth / 2 - 90, 76 + i * 28, 180, 23);
            if (i == standaloneActionIndex) MenuStyle.focus(font, viewportWidth / 2 - 90, 76 + i * 28, 180, 23);
            font.drawTextCentered(label, viewportWidth, 82 + i * 28, 1f, 1f, 1f, 1f);
        }
    }

    private void playNavigateSound() { audioSink.play(AudioCue.NAVIGATE); }
    private void playConfirmSound()  { audioSink.play(AudioCue.CONFIRM); }
    private void playErrorSound()    { audioSink.play(AudioCue.ERROR); }

    /**
     * Returns true when the user has selected a game and confirmed.
     */
    public boolean isGameSelected() {
        return gameSelected;
    }

    /**
     * Programmatic selection used by {@link com.openggf.TraceSessionLauncher}
     * to force a game without user input. Must be called while state is
     * {@code ACTIVE}. Seeds the internal "selected" state so
     * {@link #isGameSelected()} returns true on the next tick.
     */
    public void selectEntry(GameEntry entry) {
        Objects.requireNonNull(entry, "entry");
        int index = indexOfStock(entry);
        if (index < 0) throw new IllegalArgumentException("Stock entry is not present: " + entry.gameId);
        setSelectedIndex(index);
        if (!isEntryAvailable(selectedEntry())) {
            throw new IllegalStateException("Selected ROM is unavailable: " + entry.gameId);
        }
        confirmLaunch(new MasterTitleEntry.Launch(selectedEntry(), MasterTitleEntry.Action.NEW_GAME), true);
    }

    public void showRomLoadError(String gameId) {
        launchErrorTitle = null;
        launchErrorDetail = null;
        selectErrorGame(gameId);
    }

    public void showModLaunchError(String gameId) {
        launchErrorTitle = "MOD LAUNCH FAILED";
        launchErrorDetail = "DISABLED FOR NEXT LAUNCH";
        selectErrorGame(gameId);
    }

    private void selectErrorGame(String gameId) {
        if (gameId != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).gameId().equalsIgnoreCase(gameId)) {
                    setSelectedIndex(i);
                    break;
                }
            }
        }
        if (this.state != State.ERROR_DISPLAY) {
            playErrorSound();
        }
        this.state = State.ERROR_DISPLAY;
        this.errorFrameCounter = 0;
        this.gameSelected = false;
    }

    /**
     * Sets the projection (viewport) width for widescreen-aware layout.
     * Must be called each frame before {@link #draw()} when the engine is
     * running at a width other than native 320. At native width (320) this is
     * a no-op because the default already equals SCREEN_W.
     *
     * @param width projection width in pixels (e.g. 320, 400, 528)
     */
    public void setViewportWidth(int width) {
        this.viewportWidth = Math.max(SCREEN_W, width);
    }

    /**
     * Returns the horizontal center of an element of {@code elementWidth} pixels
     * within a viewport of {@code vpWidth} pixels.
     *
     * <p>At native width (vpWidth == 320) this collapses to the existing literals:
     * {@code (320 - w) / 2}.
     */
    static float centerX(int elementWidth, int vpWidth) {
        return (vpWidth - elementWidth) / 2f;
    }

    static PreviewLayout romPreviewLayout(int previewW, int previewH, int vpWidth) {
        int width = Math.max(1, previewW);
        int height = Math.max(1, previewH);
        return new PreviewLayout(width, height, centerX(width, vpWidth), 0);
    }

    static PreviewLayout topUiMatteLayout(int vpWidth) {
        int width = Math.max(SCREEN_W, vpWidth);
        return new PreviewLayout(width, TOP_UI_MATTE_HEIGHT, 0, SCREEN_H - TOP_UI_MATTE_HEIGHT);
    }

    static PreviewLayout bottomUiMatteLayout(int vpWidth) {
        int width = Math.max(SCREEN_W, vpWidth);
        return new PreviewLayout(width, BOTTOM_UI_MATTE_HEIGHT, 0, 0);
    }

    static float titleLogoY(int titleHeight) {
        return SCREEN_H - 2 - titleHeight;
    }

    static int titleLogoScaledWidth(int sourceWidth) {
        return scaledTitleLogoDimension(sourceWidth);
    }

    static int titleLogoScaledHeight(int sourceHeight) {
        return scaledTitleLogoDimension(sourceHeight);
    }

    private static int scaledTitleLogoDimension(int sourceDimension) {
        int scaled = sourceDimension
                * TITLE_LOGO_BASE_SCALE_NUMERATOR
                * TITLE_LOGO_SCALE_NUMERATOR
                / TITLE_LOGO_BASE_SCALE_DENOMINATOR
                / TITLE_LOGO_SCALE_DENOMINATOR;
        return Math.max(1, scaled);
    }

    static String expectedRomFilename(GameEntry entry) {
        return entry.expectedRomFilename;
    }

    static String missingRomPromptLine() {
        return MISSING_ROM_PROMPT;
    }

    static String missingRomFilenameLine(GameEntry entry) {
        return expectedRomFilename(entry);
    }

    static String launchHoverLine(int enabledCount) {
        if (enabledCount <= 0) {
            return "Stock launch - Tab to configure";
        }
        String noun = enabledCount == 1 ? " option enabled" : " options enabled";
        return enabledCount + noun + " - Tab to configure";
    }

    static int scaledCenteredTextX(String text, int screenWidth, float scale) {
        int textWidth = Math.round(text.length() * PixelFont.glyphWidth() * scale);
        return Math.round((screenWidth - textWidth) / 2f);
    }

    static List<MenuItemLayout> menuItemLayouts(
            List<String> labels, int selectedIndex, int viewportWidth) {
        return menuItemLayouts(labels, selectedIndex, viewportWidth,
                text -> text.length() * PixelFont.glyphWidth());
    }

    private static List<MenuItemLayout> menuItemLayouts(
            List<String> labels, int selectedIndex, int viewportWidth,
            ToIntFunction<String> measure) {
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(measure, "measure");
        if (labels.isEmpty()) return List.of();
        int selected = Math.max(0, Math.min(labels.size() - 1, selectedIndex));
        int width = Math.max(1, viewportWidth);
        int spacing = 20;
        int totalWidth = spacing * (labels.size() - 1);
        for (String label : labels) totalWidth += measure.applyAsInt(label);
        if (totalWidth <= width) {
            List<MenuItemLayout> result = new ArrayList<>(labels.size());
            int cursor = (width - totalWidth) / 2;
            for (int i = 0; i < labels.size(); i++) {
                String label = labels.get(i);
                int labelWidth = measure.applyAsInt(label);
                result.add(new MenuItemLayout(i, label, cursor, labelWidth));
                cursor += labelWidth + spacing;
            }
            return List.copyOf(result);
        }

        int available = Math.max(1, width - FITTED_TEXT_SIDE_PADDING * 2);
        String visible = elideToWidth(labels.get(selected), available, measure);
        int visibleWidth = Math.min(available, measure.applyAsInt(visible));
        return List.of(new MenuItemLayout(
                selected, visible, Math.max(0, (width - visibleWidth) / 2), visibleWidth));
    }

    private static String elideToWidth(String text, int maxWidth, ToIntFunction<String> measure) {
        if (measure.applyAsInt(text) <= maxWidth) return text;
        String suffix = "...";
        if (measure.applyAsInt(suffix) > maxWidth) return "";
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (measure.applyAsInt(text.substring(0, mid) + suffix) <= maxWidth) low = mid;
            else high = mid - 1;
        }
        return text.substring(0, low) + suffix;
    }

    static float[] menuTextColor(boolean available, boolean selected, int frameCounter) {
        if (!available) {
            if (selected) {
                return new float[] { 0.72f, 0.72f, 0.72f, 0.85f };
            }
            return new float[] { 0.4f, 0.4f, 0.4f, 0.7f };
        }
        if (selected) {
            float pulse = 0.5f + 0.5f * (float) Math.sin(frameCounter * 0.05);
            float brightness = 1.0f + 0.3f * pulse;
            return new float[] { brightness, brightness, brightness, 1.0f };
        }
        return new float[] { 0.8f, 0.8f, 0.8f, 1.0f };
    }

    static float[] secondaryActionTextColor(boolean focused, int frameCounter) {
        if (!focused) {
            return new float[] { 0.55f, 0.55f, 0.65f, 0.9f };
        }
        float pulse = 0.85f + 0.15f * (float) Math.sin(frameCounter * 0.08f);
        return new float[] { pulse, 0.75f * pulse, 0.25f * pulse, 1.0f };
    }

    private MasterTitleEntry selectedEntry() {
        return entries.get(selectedIndex);
    }

    private boolean isEntryAvailable(MasterTitleEntry entry) {
        return entry instanceof MasterTitleEntry.Standalone
                || entry instanceof MasterTitleEntry.Stock stock
                && stockAvailability.getOrDefault(stock.game(), false);
    }

    private RomPreviewState selectedPreview() {
        if (!(selectedEntry() instanceof MasterTitleEntry.Stock stock)) return null;
        RomPreviewState preview = romPreviews.get(stock.game());
        return preview == null || preview.textureId == 0 || preview.sequence == null ? null : preview;
    }

    private int indexOfStock(GameEntry game) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof MasterTitleEntry.Stock stock && stock.game() == game) return i;
        }
        return -1;
    }

    private static int defaultSelectionIndex(List<MasterTitleEntry> entries) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof MasterTitleEntry.Stock stock
                    && stock.game() == GameEntry.SONIC_2) return i;
        }
        return 0;
    }

    boolean setSelectedIndex(int newIndex) {
        int clamped = Math.max(0, Math.min(entries.size() - 1, newIndex));
        if (clamped == selectedIndex) {
            return false;
        }
        selectedIndex = clamped;
        previewAnimationFrame = 0;
        return true;
    }

    void advancePreviewAnimationFrame() {
        if (previewAnimationFrame < Integer.MAX_VALUE) {
            previewAnimationFrame++;
        }
    }

    int previewAnimationFrameForTest() {
        return previewAnimationFrame;
    }

    public void setSelectedIndexForTest(int newIndex) {
        setSelectedIndex(newIndex);
    }

    public void setStateForTest(State state) {
        this.state = state;
    }

    public boolean isLaunchConfigPanelOpenForTest() {
        return launchConfigPanel != null;
    }

    LaunchProfile currentLaunchProfileForTest() {
        if (launchConfigPanel != null) {
            return launchConfigPanel.currentProfile();
        }
        if (selectedEntry() instanceof MasterTitleEntry.Stock stock) {
            return launchProfileStore.load(stock.game());
        }
        return null;
    }

    public void setRomAvailableForTest(GameEntry entry, boolean available) {
        stockAvailability.put(entry, available);
    }

    List<MasterTitleEntry> entriesForTest() { return entries; }

    List<MasterTitleEntry.Action> standaloneActionsForTest() {
        if (!(selectedEntry() instanceof MasterTitleEntry.Standalone standalone)) return List.of();
        return standalone.continueAvailable()
                ? List.of(MasterTitleEntry.Action.NEW_GAME, MasterTitleEntry.Action.CONTINUE)
                : List.of(MasterTitleEntry.Action.NEW_GAME);
    }

    public MasterTitleEntry.Launch getSelectedLaunch() { return selectedLaunch; }

    public void setModManagerOpenHandler(Runnable handler) {
        modManagerOpenHandler = Objects.requireNonNull(handler, "handler");
    }

    public void setModManagerScreenFactory(ModManagerScreenFactory factory) {
        modManagerScreenFactory = Objects.requireNonNull(factory, "factory");
    }

    private void openModManager() {
        if (modManagerScreenFactory != null) {
            modManagerScreen = Objects.requireNonNull(
                    modManagerScreenFactory.create(font), "mod manager screen");
            modManagerScreen.suppressInputUntilNeutral();
        } else {
            modManagerOpenHandler.run();
        }
        playConfirmSound();
    }

    boolean isModsFocusedForTest() {
        return navigation.actions() && navigation.action() == TitleHubNavigation.Action.MODS;
    }

    boolean isModManagerOpenForTest() {
        return modManagerScreen != null;
    }

    public void setUserRecordingMenuFactoryForTest(UserRecordingMenuFactory userRecordingMenuFactory) {
        this.userRecordingMenuFactory = Objects.requireNonNull(userRecordingMenuFactory, "userRecordingMenuFactory");
    }

    public void setUserRecordingPlaybackStarter(UserRecordingMenu.PlaybackStarter playbackStarter) {
        this.userRecordingPlaybackStarter = Objects.requireNonNull(playbackStarter, "playbackStarter");
    }

    public boolean isUserRecordingMenuOpenForTest() {
        return userRecordingMenu != null;
    }

    public UserRecordingMenuState userRecordingMenuStateForTest() {
        return userRecordingMenu == null ? null : userRecordingMenu.state();
    }

    public void setTimeAttackMenuFactoryForTest(TimeAttackMenuFactory timeAttackMenuFactory) {
        this.timeAttackMenuFactory = Objects.requireNonNull(timeAttackMenuFactory, "timeAttackMenuFactory");
    }

    public void setTimeAttackLaunchStarter(TimeAttackMenu.LaunchStarter timeAttackLaunchStarter) {
        this.timeAttackLaunchStarter = Objects.requireNonNull(timeAttackLaunchStarter, "timeAttackLaunchStarter");
    }

    public void setTimeAttackNetworkStarter(TimeAttackMenu.NetworkStarter starter) {
        this.timeAttackNetworkStarter = Objects.requireNonNull(starter, "starter");
        if (timeAttackMenu != null) {
            timeAttackMenu.setNetworkStarter(starter);
        }
    }

    public void openRaceLobby(MultiplayerRaceCoordinator coordinator, boolean host,
                              ControlMessage.RoundConfig roundConfig, String character,
                              java.util.function.Consumer<TimeAttackLaunchRequest> roundLauncher,
                              Runnable leaveHandler) {
        timeAttackMenu = null;
        raceLobbyScreen = new RaceLobbyScreen(coordinator, font, host, roundConfig,
                character, roundLauncher, leaveHandler);
        serverBrowserScreen = null;
    }

    public void openServerBrowser(ServerBrowserScreen browser) {
        timeAttackMenu = null;
        raceLobbyScreen = null;
        serverBrowserScreen = Objects.requireNonNull(browser, "browser");
    }

    public void closeServerBrowser() {
        serverBrowserScreen = null;
    }

    public PixelFont pixelFont() {
        return font;
    }

    public boolean isRaceLobbyOpen() {
        return raceLobbyScreen != null;
    }

    public void closeRaceLobby() {
        raceLobbyScreen = null;
    }

    public boolean isTimeAttackMenuOpenForTest() {
        return timeAttackMenu != null;
    }

    public TimeAttackMenuState timeAttackMenuStateForTest() {
        return timeAttackMenu == null ? null : timeAttackMenu.state();
    }

    void setTracePickerForTest(TestModeTracePicker tracePicker) {
        this.tracePicker = tracePicker;
    }

    private void resetRomPreviewTextureFrames() {
        romPreviews.values().forEach(preview -> preview.frameToken = Integer.MIN_VALUE);
    }

    public void setProjectionMatrix(float[] projectionMatrix) {
        if (renderer != null && projectionMatrix != null) {
            currentProjection = projectionMatrix.clone();
            renderer.setProjectionMatrix(projectionMatrix);
        }
    }

    /**
     * Returns the game ID ("s1", "s2", "s3k") of the selected entry.
     */
    public String getSelectedGameId() {
        return selectedEntry().gameId();
    }

    public boolean isProgrammaticSelection() {
        return programmaticSelection;
    }

    public boolean tryOpenUserRecordingMenuForSelectedGame() {
        if (state != State.ACTIVE
                || configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)
                || launchConfigPanel != null) {
            return false;
        }
        if (!(selectedEntry() instanceof MasterTitleEntry.Stock stock)
                || !isEntryAvailable(stock)) return false;
        GameEntry entry = stock.game();
        try {
            userRecordingMenu = userRecordingMenuFactory.create(entry.gameId, font);
            childInputPending = true;
            playConfirmSound();
            return true;
        } catch (IOException ex) {
            LOGGER.warning("Failed to open recordings menu for " + entry.gameId + ": " + ex.getMessage());
            return false;
        }
    }

    public boolean handleUserRecordingMenuRequest(boolean recordingMenuRequested) {
        if (!recordingMenuRequested) {
            return false;
        }
        return tryOpenUserRecordingMenuForSelectedGame();
    }

    private UserRecordingMenu createUserRecordingMenu(String gameId, PixelFont font) throws IOException {
        Path root = Path.of(System.getProperty("user.dir", "."));
        return new UserRecordingMenu(
                gameId,
                UserRecordingCatalog.scan(root, gameId, AppVersion.identity()),
                font,
                userRecordingPlaybackStarter);
    }

    /**
     * Opens the Time Attack menu, seeded with the currently highlighted game
     * entry when its ROM is available (falls back to the first ROM-present
     * game otherwise). Returns false (no-op) when no game's ROM is available,
     * test mode is active, or the launch config panel is open.
     */
    public boolean tryOpenTimeAttackMenu() {
        if (state != State.ACTIVE
                || configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)
                || launchConfigPanel != null) {
            return false;
        }
        if (!(selectedEntry() instanceof MasterTitleEntry.Stock selectedStock)) return false;
        List<String> availableGameIds = entries.stream()
                .filter(MasterTitleEntry.Stock.class::isInstance)
                .map(MasterTitleEntry.Stock.class::cast)
                .filter(this::isEntryAvailable)
                .map(stock -> stock.game().gameId)
                .toList();
        if (availableGameIds.isEmpty()) {
            return false;
        }
        String initialGameId = selectedStock.game().gameId;
        try {
            timeAttackMenu = timeAttackMenuFactory.create(availableGameIds, initialGameId, font);
            childInputPending = true;
            playConfirmSound();
            return true;
        } catch (RuntimeException ex) {
            LOGGER.warning("Failed to open time attack menu: " + ex.getMessage());
            return false;
        }
    }

    public boolean handleTimeAttackMenuRequest(boolean timeAttackMenuRequested) {
        if (!timeAttackMenuRequested) {
            return false;
        }
        return tryOpenTimeAttackMenu();
    }

    private TimeAttackMenu createTimeAttackMenu(List<String> availableGameIds, String initialGameId, PixelFont font) {
        TimeAttackMenu menu = new TimeAttackMenu(
                availableGameIds,
                initialGameId,
                new GhostStore(Path.of("ghosts")),
                font,
                timeAttackLaunchStarter);
        menu.setNetworkStarter(timeAttackNetworkStarter);
        menu.setJoinAddress(configService.getString(SonicConfiguration.TIME_ATTACK_NET_LAST_JOIN_ADDRESS));
        return menu;
    }

    private void drawLaunchHoverLine() {
        if (!(selectedEntry() instanceof MasterTitleEntry.Stock stock)
                || !isEntryAvailable(stock)) return;
        GameEntry entry = stock.game();
        int enabledCount = launchProfileStore.load(entry).enabledCount(entry);
        if (enabledCount == 0) {
            drawScaledCenteredText(launchHoverLine(enabledCount), LAUNCH_HOVER_Y, LAUNCH_HOVER_SCALE,
                    0.55f, 0.55f, 0.55f, 0.88f);
        } else {
            drawScaledCenteredText(launchHoverLine(enabledCount), LAUNCH_HOVER_Y, LAUNCH_HOVER_SCALE,
                    1f, 0.82f, 0.28f, 1f);
        }
    }

    private void drawScaledCenteredText(String text, int y, float preferredScale,
                                        float r, float g, float b, float a) {
        float scale = fittedScale(text, preferredScale, viewportWidth);
        int width = font.measureWidth(text, scale);
        int x = Math.round((viewportWidth - width) / 2f);
        font.drawText(text, x, y, scale, r, g, b, a);
    }

    private static float fittedScale(String text, float preferredScale, int viewportWidth) {
        int preferredWidth = Math.round(text.length() * PixelFont.glyphWidth() * preferredScale);
        int maxWidth = Math.max(1, viewportWidth - FITTED_TEXT_SIDE_PADDING * 2);
        if (preferredWidth <= maxWidth) {
            return preferredScale;
        }
        return preferredScale * maxWidth / preferredWidth;
    }

    /**
     * Cleans up all GL resources.
     */
    private PixelFont ensurePickerFont() {
        if (renderer == null) return font;
        if (pickerFont == null) {
            pickerFont = new com.openggf.graphics.MenuPixelFont();
            try {
                pickerFont.init("pixel-font-ns.png", renderer);
            } catch (IOException e) {
                LOGGER.warning("Failed to load pixel-font-ns.png for trace picker, "
                        + "falling back to master-title font: " + e.getMessage());
                pickerFont = font;
            }
        }
        return pickerFont;
    }

    public void cleanup() {
        if (font != null) font.cleanup();
        // pickerFont is only non-null AND distinct from font when the
        // no-shadow atlas loaded successfully. Clean it up separately.
        if (pickerFont != null && pickerFont != font) {
            pickerFont.cleanup();
        }
        PngTextureLoader.deleteTexture(bgTextureId);
        PngTextureLoader.deleteTexture(solidWhiteTextureId);
        PngTextureLoader.deleteTexture(titleTextId);
        PngTextureLoader.deleteTexture(cloudLargeTextureId);
        PngTextureLoader.deleteTexture(cloudSmallTextureId);
        romPreviews.values().forEach(preview -> PngTextureLoader.deleteTexture(preview.textureId));
        if (renderer != null) renderer.cleanup();
        userRecordingMenu = null;
        timeAttackMenu = null;
        raceLobbyScreen = null;
        settingsScreen = null;
        toolsOpen = false;
        helpOpen = false;
        modManagerScreen = null;
        state = State.INACTIVE;
        LOGGER.info("Master title screen cleaned up");
    }

    @FunctionalInterface
    @com.openggf.game.ModApi
    public interface UserRecordingMenuFactory {
        UserRecordingMenu create(String gameId, PixelFont font) throws IOException;
    }

    @FunctionalInterface
    @com.openggf.game.ModApi
    public interface TimeAttackMenuFactory {
        TimeAttackMenu create(List<String> availableGameIds, String initialGameId, PixelFont font);
    }

    @FunctionalInterface
    @com.openggf.game.ModApi
    public interface ModManagerScreenFactory {
        ModManagerView create(PixelFont font);
    }

    /** Menu ownership is intentionally separate from the exported title-screen API. */
    boolean blocksGlobalShortcuts() {
        return state != State.ACTIVE || navigation.actions() || childInputPending
                || settingsScreen != null || launchConfigPanel != null || modManagerScreen != null
                || timeAttackMenu != null || userRecordingMenu != null || raceLobbyScreen != null
                || serverBrowserScreen != null || tracePicker != null || standaloneActionOpen
                || toolsOpen || helpOpen || configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED);
    }

    @com.openggf.game.ModApi
    public interface ModManagerView {
        void update(InputHandler input);
        void render();
        boolean consumeCloseRequested();
        void suppressInputUntilNeutral();
    }
}
