package com.openggf.game;

import com.openggf.TraceSessionLauncher;
import com.openggf.control.InputHandler;
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
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.logging.Logger;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;

/**
 * Master title screen shown on startup for game selection.
 * Runs before any ROM is loaded, using its own PNG-based rendering path.
 *
 * <p>Widescreen layout: the background and clouds fill the full projection width
 * ({@link #setViewportWidth(int)} driven by Engine's {@code realWidth}). All foreground
 * elements (emblem, title text, subtitle, game menu, nav hints, error overlay) are
 * centered on the viewport midpoint so they remain visually centered at any width.
 * At native width 320 every value collapses to the original literals — byte-identical.
 */
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

    // Short labels for the menu (fit within 320px when laid out horizontally)
    private static final String[] MENU_LABELS = { "Sonic 1", "Sonic 2", "Sonic 3K" };

    public enum GameEntry {
        SONIC_1("Sonic The Hedgehog", "s1", SonicConfiguration.SONIC_1_ROM,
                "s1.gen"),
        SONIC_2("Sonic The Hedgehog 2", "s2", SonicConfiguration.SONIC_2_ROM,
                "s2.gen"),
        SONIC_3K("Sonic 3 & Knuckles", "s3k", SonicConfiguration.SONIC_3K_ROM,
                "s3k.gen");

        public final String displayName;
        public final String gameId;
        public final SonicConfiguration romConfigKey;
        public final String expectedRomFilename;

        GameEntry(String displayName,
                  String gameId,
                  SonicConfiguration romConfigKey,
                  String expectedRomFilename) {
            this.displayName = displayName;
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

    public enum State {
        INACTIVE, FADE_IN, ACTIVE, ERROR_DISPLAY, CONFIRMING, EXITING
    }

    /**
     * Host-owned feedback events for the bootstrap menu. These deliberately
     * have no game-ROM sound id: the master title runs before a game profile
     * or ROM is selected.
     */
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
        AudioSink NO_OP = cue -> {
        };

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
    private static final int ERROR_DISPLAY_FRAMES = 180; // 3 seconds at 60fps

    private final boolean[] romAvailable = new boolean[GameEntry.values().length];

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
    private int bgTextureId;
    private int solidWhiteTextureId; // 1x1 white texture for solid color overlays
    private int titleTextId;
    private int titleTextWidth, titleTextHeight;
    private int cloudLargeTextureId;
    private int cloudLargeWidth, cloudLargeHeight;
    private int cloudSmallTextureId;
    private int cloudSmallWidth, cloudSmallHeight;
    private final int[] romPreviewTextureIds = new int[GameEntry.values().length];
    private final int[] romPreviewWidths = new int[GameEntry.values().length];
    private final int[] romPreviewHeights = new int[GameEntry.values().length];
    private final int[] romPreviewFrameTokens = new int[GameEntry.values().length];
    private final MasterTitleRomPreview.PreviewSequence[] romPreviewSequences =
            new MasterTitleRomPreview.PreviewSequence[GameEntry.values().length];
    private int previewAnimationFrame = 0;

    private final List<CloudSprite> clouds = new ArrayList<>();
    private final SonicConfigurationService configService;
    private final LaunchProfileStore launchProfileStore;
    private final AudioSink audioSink;
    private LaunchConfigPanel launchConfigPanel;
    private boolean programmaticSelection;

    /**
     * Projection width supplied by Engine each frame. Defaults to SCREEN_W (320)
     * so the screen behaves identically to native when no explicit width is set.
     * At widescreen (e.g. 400, 528) the background/clouds expand to fill the full
     * width while foreground elements stay centered.
     */
    private int viewportWidth = SCREEN_W;

    private boolean gameSelected = false;

    public MasterTitleScreen() {
        this(GameServices.configuration());
    }

    public MasterTitleScreen(SonicConfigurationService configService) {
        this(configService, new LaunchProfileStore(configService));
    }

    public MasterTitleScreen(SonicConfigurationService configService,
                             LaunchProfileStore launchProfileStore) {
        this(configService, launchProfileStore, AudioSink.NO_OP);
    }

    public MasterTitleScreen(SonicConfigurationService configService,
                             LaunchProfileStore launchProfileStore,
                             AudioSink audioSink) {
        this.configService = Objects.requireNonNull(configService, "configService");
        this.launchProfileStore = Objects.requireNonNull(launchProfileStore, "launchProfileStore");
        this.audioSink = Objects.requireNonNull(audioSink, "audioSink");
    }

    public void initialize() {
        // Check ROM availability
        for (int i = 0; i < GameEntry.values().length; i++) {
            GameEntry entry = GameEntry.values()[i];
            String romPath = configService.getString(entry.romConfigKey);
            romAvailable[i] = romPath != null && !romPath.isEmpty() && new File(romPath).exists();
        }

        try {
            // Initialize renderer and font
            renderer = new TexturedQuadRenderer();
            renderer.init();

            font = new PixelFont();
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
            if (errorFrameCounter >= ERROR_DISPLAY_FRAMES) {
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

        if (configService.getBoolean(SonicConfiguration.TEST_MODE_ENABLED)) {
            userRecordingMenu = null;
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
                        } else {
                            tracePicker.launchFailed();
                        }
                    }
                }
                case BACK -> {
                    // Disable test mode for this session so normal game-select runs
                    configService.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
                    tracePicker = null;
                }
                case NONE -> { }
            }
            return;
        }

        if (userRecordingMenu != null) {
            userRecordingMenu.update(inputHandler);
            if (userRecordingMenu.consumeCloseRequested()) {
                userRecordingMenu = null;
            }
            return;
        }

        if (launchConfigPanel != null) {
            launchConfigPanel.update(inputHandler);
            if (launchConfigPanel.consumeResult() == LaunchConfigPanel.Result.CLOSED) {
                GameEntry entry = GameEntry.values()[selectedIndex];
                launchProfileStore.save(entry, launchConfigPanel.currentProfile());
                launchConfigPanel = null;
            }
            return;
        }

        // Handle input
        int leftKey = configService.getInt(SonicConfiguration.LEFT);
        int rightKey = configService.getInt(SonicConfiguration.RIGHT);
        int jumpKey = configService.getInt(SonicConfiguration.JUMP);
        boolean leftPressed = inputHandler.isKeyPressed(leftKey) || inputHandler.logical().menuLeft();
        boolean rightPressed = inputHandler.isKeyPressed(rightKey) || inputHandler.logical().menuRight();
        boolean confirmPressed = inputHandler.isKeyPressed(jumpKey)
                || inputHandler.isKeyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER)
                || inputHandler.logical().menuAccept();

        int recordKey = configService.getInt(SonicConfiguration.RECORDING_RECORD_KEY);
        boolean recordingMenuRequested = inputHandler.isKeyPressed(recordKey) && inputHandler.isShiftDown();
        if (handleUserRecordingMenuRequest(recordingMenuRequested) || recordingMenuRequested) {
            return;
        }

        if ((inputHandler.isKeyPressed(GLFW_KEY_TAB) || inputHandler.isGamepadBackButtonPressed())
                && romAvailable[selectedIndex]) {
            GameEntry entry = GameEntry.values()[selectedIndex];
            launchConfigPanel = new LaunchConfigPanel(
                    entry,
                    launchProfileStore.load(entry),
                    launchProfileStore,
                    configService,
                    font,
                    renderer);
            return;
        }

        if (leftPressed) {
            if (setSelectedIndex(selectedIndex - 1)) {
                playNavigateSound();
            }
        }
        if (rightPressed) {
            if (setSelectedIndex(selectedIndex + 1)) {
                playNavigateSound();
            }
        }

        // Confirm with Jump or Enter
        if (confirmPressed) {
            if (!romAvailable[selectedIndex]) {
                state = State.ERROR_DISPLAY;
                errorFrameCounter = 0;
                playErrorSound();
            } else {
                state = State.CONFIRMING;
                playConfirmSound();
                programmaticSelection = false;
                gameSelected = true;
            }
        }
    }

    /**
     * Draws the title screen. Called once per frame from Engine.draw().
     */
    public void draw() {
        if (renderer == null) return;

        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        if (tracePicker != null) {
            // Paint solid black behind the picker so the regular master-title
            // artwork doesn't bleed through. Fill the full projection width.
            renderer.drawTexture(solidWhiteTextureId, 0, 0, viewportWidth, SCREEN_H,
                    0f, 0f, 0f, 1f);
            tracePicker.render();
            return;
        }

        if (userRecordingMenu != null) {
            renderer.drawTexture(solidWhiteTextureId, 0, 0, viewportWidth, SCREEN_H,
                    0f, 0f, 0f, 1f);
            userRecordingMenu.render();
            return;
        }

        // 1. Background — fills the full projection width so it expands at widescreen.
        //    At native 320 this is identical to the original drawTexture(bg, 0, 0, 320, 224).
        renderer.drawTexture(bgTextureId, 0, 0, viewportWidth, SCREEN_H);

        // 2. Clouds (behind emblem) — cloud x/y already tracks viewportWidth via update().
        for (CloudSprite cloud : clouds) {
            float glY = SCREEN_H - cloud.y - cloud.height;
            renderer.drawTexture(cloud.textureId, cloud.x, glY, cloud.width, cloud.height,
                1f, 1f, 1f, 0.85f);
        }

        // 3. Compute title text position.
        //    Foreground elements are centered on viewportWidth/2.
        //    At native 320: centerX(w, 320) == (320-w)/2 == existing literal. Byte-identical.
        int scaledTitleW = titleLogoScaledWidth(titleTextWidth);
        int scaledTitleH = titleLogoScaledHeight(titleTextHeight);
        float titleX = centerX(scaledTitleW, viewportWidth);
        float titleGlY = titleLogoY(scaledTitleH);

        // 4. ROM-derived title preview, shown only when the selected ROM exists.
        drawSelectedRomPreview();
        drawRomPreviewUiMattes();

        // 5. Title text "OpenGGF" (centered, top) - drawn after preview so it appears in front
        renderer.drawTexture(titleTextId, titleX, titleGlY, scaledTitleW, scaledTitleH);

        // All foreground text (subtitle + game menu + nav hints) shares the font atlas,
        // so mega-batch into a single GL draw call.
        font.beginMegaBatch();

        // 6. Subtitle text, right-aligned to title's right edge - drawn after title (no overlap).
        //    titleX is already centered on viewportWidth, so the right edge auto-follows.
        int titleRightEdge = (int)(titleX + scaledTitleW)-8;
        int subtitleY = (int)(SCREEN_H - titleGlY) - 6;
        int line1X = titleRightEdge - font.measureWidth("Open-Source");
        int line2X = titleRightEdge - font.measureWidth("Sonic Engine");
        font.drawText("Open-Source", line1X, subtitleY-8, 0.8f, 0.8f, 0.8f, 0.9f);
        font.drawText("Sonic Engine", line2X, subtitleY + 2, 0.8f, 0.8f, 0.8f, 0.9f);

        // 7. Missing-ROM prompt occupies the old emblem area when no ROM preview is available.
        drawSelectedMissingRomPrompt();
        drawLaunchHoverLine();

        // 7. Game selection menu at bottom — centered on viewportWidth.
        drawGameMenu();

        // 8. Navigation hints — centered on viewportWidth.
        font.drawTextCentered("< >  Select    Enter  Confirm", viewportWidth, 210,
            0.6f, 0.6f, 0.7f, 0.8f);

        font.endMegaBatch();

        if (launchConfigPanel != null) {
            renderer.drawTexture(solidWhiteTextureId, 0, 0, viewportWidth, SCREEN_H,
                    0f, 0f, 0f, LAUNCH_PANEL_OVERLAY_ALPHA);
            launchConfigPanel.render(viewportWidth);
            return;
        }

        // 9. Error message overlay — semi-transparent overlay fills full width;
        //    error text is centered on viewportWidth.
        if (state == State.ERROR_DISPLAY) {
            // Semi-transparent black overlay using solid white texture tinted black
            // (separate texture; not batched with font).
            renderer.drawTexture(solidWhiteTextureId, 0, 0, viewportWidth, SCREEN_H, 0f, 0f, 0f, 0.5f);

            // Error text - second batch (overlay texture sits between the two batches).
            font.beginMegaBatch();
            GameEntry entry = GameEntry.values()[selectedIndex];
            font.drawTextCentered("ROM NOT FOUND", viewportWidth, 90, 1f, 0.3f, 0.3f, 1f);
            font.drawTextCentered(entry.displayName, viewportWidth, 105, 0.8f, 0.8f, 0.8f, 1f);

            String romFile = configService.getString(entry.romConfigKey);
            if (romFile == null || romFile.isEmpty()) {
                romFile = "(not configured)";
            } else if (romFile.length() > 35) {
                romFile = "..." + romFile.substring(romFile.length() - 32);
            }
            font.drawTextCentered(romFile, viewportWidth, 125, 0.5f, 0.5f, 0.5f, 0.8f);
            font.endMegaBatch();
        }
    }

    private void drawGameMenu() {
        GameEntry[] entries = GameEntry.values();
        int totalWidth = 0;
        int[] widths = new int[entries.length];
        int spacing = 20;

        for (int i = 0; i < entries.length; i++) {
            widths[i] = font.measureWidth(MENU_LABELS[i]);
            totalWidth += widths[i];
        }
        totalWidth += spacing * (entries.length - 1);

        // Center the menu block on the viewport midpoint.
        // At native 320: (320 - totalWidth) / 2 == original literal.
        int startX = (viewportWidth - totalWidth) / 2;
        int menuY = 190;
        int cursorX = startX;

        for (int i = 0; i < entries.length; i++) {
            float[] color = menuTextColor(romAvailable[i], i == selectedIndex, frameCounter);

            font.drawText(MENU_LABELS[i], cursorX, menuY, color[0], color[1], color[2], color[3]);
            cursorX += widths[i] + spacing;
        }
    }

    private void loadRomPreviews() {
        GameEntry[] entries = GameEntry.values();
        for (int i = 0; i < entries.length; i++) {
            if (!romAvailable[i]) {
                continue;
            }
            GameEntry entry = entries[i];
            Path path = Path.of(configService.getString(entry.romConfigKey));
            MasterTitleRomPreview.loadSequenceFor(entry, path).ifPresent(sequence -> {
                int index = entry.ordinal();
                romPreviewSequences[index] = sequence;
                MasterTitleRomPreview.Image firstFrame = sequence.imageAt(0);
                romPreviewTextureIds[index] = MasterTitleRomPreview.uploadTexture(firstFrame);
                romPreviewWidths[index] = firstFrame.width();
                romPreviewHeights[index] = firstFrame.height();
            });
        }
    }

    private void drawSelectedRomPreview() {
        updateSelectedRomPreviewTexture();
        int textureId = romPreviewTextureIds[selectedIndex];
        if (textureId == 0) {
            return;
        }
        int previewW = romPreviewWidths[selectedIndex];
        int previewH = romPreviewHeights[selectedIndex];
        PreviewLayout layout = romPreviewLayout(previewW, previewH, viewportWidth);
        renderer.drawTexture(textureId, layout.x(), layout.y(), layout.width(), layout.height());
    }

    private void drawRomPreviewUiMattes() {
        if (romPreviewTextureIds[selectedIndex] == 0) {
            return;
        }
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
        MasterTitleRomPreview.PreviewSequence sequence = romPreviewSequences[selectedIndex];
        int textureId = romPreviewTextureIds[selectedIndex];
        if (sequence == null || textureId == 0) {
            return;
        }
        int token = sequence.frameTokenAt(previewAnimationFrame);
        if (romPreviewFrameTokens[selectedIndex] == token) {
            return;
        }
        MasterTitleRomPreview.Image frame = sequence.imageAt(previewAnimationFrame);
        MasterTitleRomPreview.updateTexture(textureId, frame);
        romPreviewFrameTokens[selectedIndex] = token;
    }

    private void drawSelectedMissingRomPrompt() {
        if (romAvailable[selectedIndex]) {
            return;
        }
        GameEntry entry = GameEntry.values()[selectedIndex];
        font.drawTextCentered(MISSING_ROM_PROMPT, viewportWidth, 82, 1f, 0.25f, 0.25f, 1f);

        String filename = missingRomFilenameLine(entry);
        float filenameScale = Math.min(0.72f, (viewportWidth - 36f) / Math.max(1, font.measureWidth(filename)));
        int filenameWidth = font.measureWidth(filename, filenameScale);
        float filenameX = centerX(filenameWidth, viewportWidth);
        font.drawText(filename, filenameX, 98, filenameScale, 1f, 0.55f, 0.55f, 1f);
    }

    private void playNavigateSound() {
        audioSink.play(AudioCue.NAVIGATE);
    }

    private void playConfirmSound() {
        audioSink.play(AudioCue.CONFIRM);
    }

    private void playErrorSound() {
        audioSink.play(AudioCue.ERROR);
    }

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
        setSelectedIndex(entry.ordinal());
        if (!romAvailable[selectedIndex]) {
            throw new IllegalStateException("Selected ROM is unavailable: " + entry.gameId);
        }
        this.state = State.CONFIRMING;
        playConfirmSound();
        this.programmaticSelection = true;
        this.gameSelected = true;
    }

    public void showRomLoadError(String gameId) {
        if (gameId != null) {
            for (GameEntry entry : GameEntry.values()) {
                if (entry.gameId.equalsIgnoreCase(gameId)) {
                    setSelectedIndex(entry.ordinal());
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

    boolean setSelectedIndex(int newIndex) {
        int clamped = Math.max(0, Math.min(GameEntry.values().length - 1, newIndex));
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
        return launchProfileStore.load(GameEntry.values()[selectedIndex]);
    }

    public void setRomAvailableForTest(GameEntry entry, boolean available) {
        romAvailable[entry.ordinal()] = available;
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

    void setTracePickerForTest(TestModeTracePicker tracePicker) {
        this.tracePicker = tracePicker;
    }

    private void resetRomPreviewTextureFrames() {
        java.util.Arrays.fill(romPreviewFrameTokens, Integer.MIN_VALUE);
    }

    public void setProjectionMatrix(float[] projectionMatrix) {
        if (renderer != null && projectionMatrix != null) {
            renderer.setProjectionMatrix(projectionMatrix);
        }
    }

    /**
     * Returns the game ID ("s1", "s2", "s3k") of the selected entry.
     */
    public String getSelectedGameId() {
        return GameEntry.values()[selectedIndex].gameId;
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
        GameEntry entry = GameEntry.values()[selectedIndex];
        try {
            userRecordingMenu = userRecordingMenuFactory.create(entry.gameId, font);
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

    private void drawLaunchHoverLine() {
        if (!romAvailable[selectedIndex]) {
            return;
        }
        GameEntry entry = GameEntry.values()[selectedIndex];
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
        if (pickerFont == null) {
            pickerFont = new PixelFont();
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
        for (int textureId : romPreviewTextureIds) {
            PngTextureLoader.deleteTexture(textureId);
        }
        if (renderer != null) renderer.cleanup();
        userRecordingMenu = null;
        state = State.INACTIVE;
        LOGGER.info("Master title screen cleaned up");
    }

    @FunctionalInterface
    public interface UserRecordingMenuFactory {
        UserRecordingMenu create(String gameId, PixelFont font) throws IOException;
    }
}
