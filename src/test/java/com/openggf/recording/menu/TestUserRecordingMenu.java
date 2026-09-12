package com.openggf.game.recording.menu;

import com.openggf.GameLoop;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.control.InputActionMasks;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.GameMode;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.recording.DesyncLiteFrame;
import com.openggf.game.recording.RecordedFrameInput;
import com.openggf.game.recording.RecordingDeterminismMetadata;
import com.openggf.game.recording.RecordingLaunchContext;
import com.openggf.game.recording.RecordingVersionWarning;
import com.openggf.game.recording.UserRecordingEntry;
import com.openggf.game.recording.UserRecordingManifest;
import com.openggf.game.recording.UserRecordingPlaybackOptions;
import com.openggf.game.recording.UserRecordingSidecarMetadata;
import com.openggf.game.recording.UserRecordingStopReason;
import com.openggf.game.recording.UserRecordingWriter;
import com.openggf.tests.TestEnvironment;
import com.openggf.version.BuildIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SUPER;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

@Isolated
class TestUserRecordingMenu {
    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
    }

    @AfterEach
    void tearDown() {
        TestEnvironment.resetAll();
    }

    @Test
    void masterTitleOpensRecordingsMenuForSelectedGameWhenTestModeIsDisabled() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        List<String> openedGameIds = new ArrayList<>();
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setSelectedIndexForTest(MasterTitleScreen.GameEntry.SONIC_3K.ordinal());
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_3K, true);
        screen.setUserRecordingMenuFactoryForTest((gameId, font) -> {
            openedGameIds.add(gameId);
            return new UserRecordingMenu(gameId, List.of(entry("s3k", 90)), null, (recording, options) -> { });
        });

        assertTrue(screen.tryOpenUserRecordingMenuForSelectedGame());

        assertEquals(List.of("s3k"), openedGameIds);
        assertTrue(screen.isUserRecordingMenuOpenForTest());
        assertEquals("s3k", screen.userRecordingMenuStateForTest().gameId());
    }

    @Test
    void masterTitleDoesNotOpenRecordingsMenuWhenTestModeIsEnabled() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        List<String> openedGameIds = new ArrayList<>();
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setUserRecordingMenuFactoryForTest((gameId, font) -> {
            openedGameIds.add(gameId);
            return new UserRecordingMenu(gameId, List.of(), null, (recording, options) -> { });
        });

        assertFalse(screen.tryOpenUserRecordingMenuForSelectedGame());

        assertTrue(openedGameIds.isEmpty());
        assertFalse(screen.isUserRecordingMenuOpenForTest());
    }

    @Test
    void masterTitleRecordingMenuRequestOpensSelectedGameInNormalMode() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        List<String> openedGameIds = new ArrayList<>();
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setSelectedIndexForTest(MasterTitleScreen.GameEntry.SONIC_1.ordinal());
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_1, true);
        screen.setUserRecordingMenuFactoryForTest((gameId, font) -> {
            openedGameIds.add(gameId);
            return new UserRecordingMenu(gameId, List.of(entry("s1", 90)), null, (recording, options) -> { });
        });

        assertTrue(screen.handleUserRecordingMenuRequest(true));

        assertEquals(List.of("s1"), openedGameIds);
        assertTrue(screen.isUserRecordingMenuOpenForTest());
    }

    @Test
    void masterTitleRecordingMenuRequestDoesNotOpenInTestMode() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        List<String> openedGameIds = new ArrayList<>();
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setUserRecordingMenuFactoryForTest((gameId, font) -> {
            openedGameIds.add(gameId);
            return new UserRecordingMenu(gameId, List.of(entry("s2", 90)), null, (recording, options) -> { });
        });

        assertFalse(screen.handleUserRecordingMenuRequest(true));

        assertTrue(openedGameIds.isEmpty());
        assertFalse(screen.isUserRecordingMenuOpenForTest());
    }

    @Test
    void shiftRecordUpdatePathRequestsRecordingMenuInNormalMode() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        List<String> openedGameIds = new ArrayList<>();
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setSelectedIndexForTest(MasterTitleScreen.GameEntry.SONIC_3K.ordinal());
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_3K, true);
        screen.setUserRecordingMenuFactoryForTest((gameId, font) -> {
            openedGameIds.add(gameId);
            return new UserRecordingMenu(gameId, List.of(entry("s3k", 90)), null, (recording, options) -> { });
        });
        InputHandler input = new InputHandler();

        pressShiftRecord(screen, input, config);

        assertEquals(List.of("s3k"), openedGameIds);
        assertTrue(screen.isUserRecordingMenuOpenForTest());
    }

    @Test
    void gameLoopInstalledPlaybackStarterRunsWhenMasterTitleMenuStartsRecording() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        String oldUserDir = System.getProperty("user.dir");
        Path bk2 = writeRecording("s3k", 4);
        AtomicReference<UserRecordingEntry> startedEntry = new AtomicReference<>();
        AtomicReference<UserRecordingPlaybackOptions> startedOptions = new AtomicReference<>();

        try {
            System.setProperty("user.dir", tempDir.toString());
            SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
            MasterTitleScreen screen = new MasterTitleScreen(config);
            screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
            screen.setSelectedIndexForTest(MasterTitleScreen.GameEntry.SONIC_3K.ordinal());
            screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_3K, true);
            InputHandler input = new InputHandler();
            GameLoop loop = new GameLoop(input);
            loop.setUserRecordingPlaybackStarter((entry, options) -> {
                startedEntry.set(entry);
                startedOptions.set(options);
            });
            loop.setMasterTitleScreenSupplier(() -> screen);
            loop.setGameMode(GameMode.MASTER_TITLE_SCREEN);

            pressShiftRecord(loop, input, config);
            // Catalog I/O is asynchronous; wait for readiness, then release the opener.
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (!screen.isUserRecordingMenuOpenForTest() && System.nanoTime() < deadline) {
                loop.step();
                Thread.yield();
            }
            assertTrue(screen.isUserRecordingMenuOpenForTest(), "catalog must complete");
            loop.step();
            pressLoopKey(loop, input, GLFW_KEY_ENTER);
            pressLoopKey(loop, input, GLFW_KEY_ENTER);
            pressLoopKey(loop, input, GLFW_KEY_ENTER);
        } finally {
            if (oldUserDir == null) {
                System.clearProperty("user.dir");
            } else {
                System.setProperty("user.dir", oldUserDir);
            }
        }

        assertNotNull(startedEntry.get());
        assertEquals(bk2, startedEntry.get().path());
        assertNotNull(startedOptions.get());
        assertEquals(3, startedOptions.get().targetFrame());
    }

    @Test
    void targetPromptAcceptsDigitsAndClampsToMovieLength() {
        UserRecordingMenuState state = new UserRecordingMenuState("s2", List.of(entry("s2", 125)));

        state.pressEnter();
        state.typeDigit('9');
        state.typeDigit('9');
        state.typeDigit('9');
        state.pressEnter();

        assertFalse(state.isPromptingForTargetFrame());
        assertEquals(124, state.options().targetFrame());
    }

    @Test
    void togglesAndLeftRightUpdatePlaybackOptions() {
        UserRecordingMenuState state = new UserRecordingMenuState("s1", List.of(entry("s1", 180)));

        assertFalse(state.options().pauseOnDesync());
        assertFalse(state.options().fastForward());
        assertEquals(179, state.options().targetFrame());

        state.pressP();
        state.pressF();
        state.pressLeft();
        state.pressLeft();
        state.pressRight();

        UserRecordingPlaybackOptions options = state.options();
        assertTrue(options.pauseOnDesync());
        assertTrue(options.fastForward());
        assertEquals(119, options.targetFrame());
    }

    @Test
    void selectedInfoFieldsIncludeFileCreatedEngineAndLaunchContext() {
        UserRecordingMenuState state = new UserRecordingMenuState("s3k", List.of(entry("s3k", 180)));

        List<String> lines = state.selectedInfoLines();

        assertTrue(lines.stream().anyMatch(line -> line.contains("s3k-180.bk2")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("2026-06-29T14:30:22Z")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("0.6.prerelease-abcdef123")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("s3k  Zone:02  Act:1")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("sonic+tails")));
    }

    @Test
    void escapeClosesMenuAndPromptEscapeReturnsToList() {
        UserRecordingMenuState state = new UserRecordingMenuState("s1", List.of(entry("s1", 60)));

        state.pressEnter();
        assertTrue(state.isPromptingForTargetFrame());
        state.pressEscape();
        assertFalse(state.isPromptingForTargetFrame());
        assertFalse(state.consumeCloseRequested());

        state.pressEscape();
        assertTrue(state.consumeCloseRequested());
    }

    @Test
    void amberWarningAppearsForMismatchButStillAllowsPlayback() {
        UserRecordingEntry warned = entry("s3k", 240, RecordingVersionWarning.PRERELEASE_BUILD_MISMATCH);
        UserRecordingMenuState state = new UserRecordingMenuState("s3k", List.of(warned));

        assertTrue(state.hasAmberWarning());
        assertNotNull(state.warningText());

        state.pressPlay();

        UserRecordingMenuState.PlaybackRequest request = state.consumePlaybackRequest();
        assertNotNull(request);
        assertEquals(warned, request.entry());
        assertEquals(239, request.options().targetFrame());
        assertNull(state.consumePlaybackRequest());
    }

    /**
     * The twelve menu keys are read through isKeyPressedWithoutModifiers, so a
     * modifier that latched because its release went to another window disables
     * the whole menu until the focus-loss clear drops it.
     */
    @Test
    void aLatchedSuperKeyStopsTheMenuUntilFocusLossClearsIt() {
        UserRecordingMenuState state =
                new UserRecordingMenuState("s2", List.of(entry("s2", 60), entry("s2", 120)));
        InputHandler input = new InputHandler();
        input.handleKeyEvent(GLFW_KEY_LEFT_SUPER, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);

        state.update(input);
        assertEquals(0, state.cursor(), "held Super suppresses the unmodified menu key");

        input.clearKeyState();
        input.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        state.update(input);

        assertEquals(1, state.cursor(), "the menu responds again after focus loss");
    }

    @Test
    void controllerCanChooseRecordingEditOptionsAndBackWithoutPlayback() {
        UserRecordingMenuState state = new UserRecordingMenuState("s2", List.of(entry("s2", 60), entry("s2", 120)));
        InputHandler input = new InputHandler();
        controllerInput(input, AbstractPlayableSprite.INPUT_DOWN, 0);
        state.update(input);
        assertEquals(1, state.cursor());
        controllerInput(input, 0, InputActionMasks.ACTION_B);
        state.update(input);
        assertTrue(state.isPromptingForTargetFrame());
        controllerInput(input, AbstractPlayableSprite.INPUT_LEFT, 0);
        state.update(input);
        assertEquals("118", state.promptBuffer());
        controllerInput(input, AbstractPlayableSprite.INPUT_UP, 0);
        state.update(input);
        assertTrue(state.options().pauseOnDesync());
        controllerInput(input, AbstractPlayableSprite.INPUT_DOWN, 0);
        state.update(input);
        assertTrue(state.options().fastForward());
        controllerInput(input, 0, InputActionMasks.ACTION_C);
        state.update(input);
        assertFalse(state.isPromptingForTargetFrame());
        assertNull(state.consumePlaybackRequest());
        state.update(input);
        assertTrue(state.consumeCloseRequested());
        assertNull(state.consumePlaybackRequest());
    }

    private static void controllerInput(InputHandler input, int direction, int action) {
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(direction, direction, action, action, false, false),
                PlayerInputState.neutral()));
    }

    @Test
    void visibleOptionsRequireExplicitPlayAndTargetEditorCanUseKeyboard() {
        AtomicReference<UserRecordingPlaybackOptions> played = new AtomicReference<>();
        UserRecordingMenu menu = new UserRecordingMenu("s2", List.of(entry("s2", 120)), null,
                (recording, options) -> played.set(options));
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        assertNull(played.get());
        for (int i = 0; i < 3; i++) menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_UP);
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        for (int i = 0; i < 3; i++) menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE);
        InputHandler typing = new InputHandler();
        com.openggf.control.MenuInput.handleCharEvent(typing, '4');
        com.openggf.control.MenuInput.handleCharEvent(typing, '2');
        menu.update(typing);
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        for (int i = 0; i < 3; i++) menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN);
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        assertNotNull(played.get());
        assertEquals(42, played.get().targetFrame());
    }

    @Test
    void recordingsLibraryOptionsAndDetailsStayWithinNativeBounds() {
        com.openggf.graphics.PixelFont font = new com.openggf.graphics.PixelFont() {
            @Override public void beginMegaBatch() { }
            @Override public void endMegaBatch() { }
            @Override public void drawText(String value, int x, int y, float scale,
                    float r, float g, float b, float a) {
                int advance = scale < 1 ? 6 : 9;
                assertTrue(x >= 0 && x + value.length() * advance <= 320, value);
                assertTrue(y >= 0 && y + (scale < 1 ? 8 : 10) <= 224, value);
                assertTrue(scale == 1f || scale == com.openggf.game.MenuStyle.COMPACT, value);
            }
        };
        UserRecordingMenu menu = new UserRecordingMenu("s2", List.of(entry("s2", 120)), font, (e, o) -> { });
        menu.render();
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        menu.render();
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN);
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        menu.render();
        menuKey(menu, org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE);
        assertFalse(menu.consumeCloseRequested());
    }

    private static void menuKey(UserRecordingMenu menu, int key) {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        menu.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        menu.update(input);
    }

    private static UserRecordingEntry entry(String gameId, int frameCount) {
        return entry(gameId, frameCount, RecordingVersionWarning.NONE);
    }

    private static UserRecordingEntry entry(String gameId, int frameCount, RecordingVersionWarning warning) {
        return new UserRecordingEntry(
                Path.of(gameId + "-" + frameCount + ".bk2"),
                gameId + " movie " + frameCount,
                manifest(gameId, frameCount),
                frameCount,
                Instant.parse("2026-06-29T14:30:22Z"),
                warning,
                null);
    }

    private static UserRecordingManifest manifest(String gameId, int frameCount) {
        return new UserRecordingManifest(
                UserRecordingManifest.CURRENT_SCHEMA_VERSION,
                gameId + " movie",
                new BuildIdentity("0.6.prerelease", "abcdef123", false),
                new RecordingLaunchContext(
                        gameId,
                        2,
                        1,
                        "sonic",
                        List.of("tails"),
                        false,
                        "current-act-fresh-start"),
                UserRecordingSidecarMetadata.everyFrame(),
                new RecordingDeterminismMetadata(0, null),
                "A",
                frameCount,
                UserRecordingStopReason.USER_STOPPED,
                Instant.parse("2026-06-29T14:30:22Z"));
    }

    private Path writeRecording(String gameId, int frameCount) throws Exception {
        Path bk2 = tempDir.resolve("recordings").resolve(gameId).resolve(gameId + "-" + frameCount + ".bk2");
        Files.createDirectories(bk2.getParent());
        UserRecordingWriter.write(bk2, manifest(gameId, frameCount), inputs(frameCount), sidecarFrames(frameCount));
        return bk2;
    }

    private static List<RecordedFrameInput> inputs(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(frame -> new RecordedFrameInput(frame, 0, 0, false, 0, 0, false))
                .toList();
    }

    private static List<DesyncLiteFrame> sidecarFrames(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(frame -> new DesyncLiteFrame(frame, 0, 0, 0, 0, 0, 0, 0,
                        0, 0, 0, 0, 0, 0, 0))
                .toList();
    }

    private static void pressShiftRecord(MasterTitleScreen screen, InputHandler input, SonicConfigurationService config) {
        int recordKey = config.getInt(SonicConfiguration.RECORDING_RECORD_KEY);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(recordKey, GLFW_PRESS);
        screen.update(input);
        input.handleKeyEvent(recordKey, GLFW_RELEASE);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        input.update();
    }

    private static void pressShiftRecord(GameLoop loop, InputHandler input, SonicConfigurationService config) {
        int recordKey = config.getInt(SonicConfiguration.RECORDING_RECORD_KEY);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(recordKey, GLFW_PRESS);
        loop.step();
        input.handleKeyEvent(recordKey, GLFW_RELEASE);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        input.update();
    }

    private static void pressLoopKey(GameLoop loop, InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
        loop.step();
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
    }
}
