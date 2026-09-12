package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.game.launch.LaunchProfile;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.testmode.TestModeTracePicker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_BACK;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

/**
 * Headless unit tests for MasterTitleScreen layout math.
 * No OpenGL, no ROM, no singletons — pure arithmetic.
 */
class TestMasterTitleScreenLayout {

    @TempDir
    Path tempDir;

    @Test
    void screenW_isNativeWidth() {
        assertEquals(320, MasterTitleScreen.SCREEN_W,
            "SCREEN_W must remain 320 to preserve native parity");
    }

    // -------------------------------------------------------------------------
    // setViewportWidth — clamped at SCREEN_W
    // -------------------------------------------------------------------------

    @Test
    void expectedRomFilename_usesProjectRootDefaultsForEachGame() {
        assertEquals("s1.gen",
                MasterTitleScreen.expectedRomFilename(MasterTitleScreen.GameEntry.SONIC_1));
        assertEquals("s2.gen",
                MasterTitleScreen.expectedRomFilename(MasterTitleScreen.GameEntry.SONIC_2));
        assertEquals("s3k.gen",
                MasterTitleScreen.expectedRomFilename(MasterTitleScreen.GameEntry.SONIC_3K));
    }

    @Test
    void gameEntryFromGameIdMatchesCaseInsensitivelyAndRejectsUnknownIds() {
        assertEquals(MasterTitleScreen.GameEntry.SONIC_1, MasterTitleScreen.GameEntry.fromGameId("S1"));
        assertEquals(MasterTitleScreen.GameEntry.SONIC_2, MasterTitleScreen.GameEntry.fromGameId("s2"));
        assertEquals(MasterTitleScreen.GameEntry.SONIC_3K, MasterTitleScreen.GameEntry.fromGameId("s3k"));
        assertThrows(IllegalArgumentException.class, () -> MasterTitleScreen.GameEntry.fromGameId("bad"));
    }

    @Test
    void titleLogoScaleReducesOpenGgfLogoToNineTenths() {
        assertEquals(63, MasterTitleScreen.titleLogoScaledWidth(200));
    }

    @Test
    void previewAnimationFrame_advancesUntilSelectionChanges() {
        MasterTitleScreen screen = new MasterTitleScreen(SonicConfigurationService.createStandalone());

        screen.advancePreviewAnimationFrame();
        screen.advancePreviewAnimationFrame();

        assertEquals(2, screen.previewAnimationFrameForTest());

        screen.setSelectedIndexForTest(MasterTitleScreen.GameEntry.SONIC_1.ordinal());

        assertEquals(0, screen.previewAnimationFrameForTest());
    }

    @Test
    void previewAnimationFrame_doesNotResetWhenSelectionStaysTheSame() {
        MasterTitleScreen screen = new MasterTitleScreen(SonicConfigurationService.createStandalone());

        screen.advancePreviewAnimationFrame();
        screen.setSelectedIndexForTest(MasterTitleScreen.GameEntry.SONIC_2.ordinal());

        assertEquals(1, screen.previewAnimationFrameForTest());
    }

    @Test
    void tabOpensLaunchPanelOnlyWhenSelectedRomIsAvailable() {
        MasterTitleScreen screen = activeScreen();
        InputHandler input = new InputHandler();

        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, false);
        pressFrame(screen, input, GLFW_KEY_TAB);
        assertFalse(screen.isLaunchConfigPanelOpenForTest());
        pressFrame(screen, input, GLFW_KEY_ESCAPE); // Acknowledge the unavailable-action page.

        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        pressFrame(screen, input, GLFW_KEY_TAB);
        assertTrue(screen.isLaunchConfigPanelOpenForTest());
    }

    @Test
    void gamepadBackButtonOpensLaunchPanelOnlyWhenSelectedRomIsAvailable() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        MasterTitleScreen screen = new MasterTitleScreen(config, new TrackingStore(config));
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), source);

        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, false);
        source.setDevices(GamepadStateSource.DeviceState.connected(0, "pad", buttons(GLFW_GAMEPAD_BUTTON_BACK), 0f, 0f));
        input.refreshLogicalSnapshot();
        screen.update(input);
        assertFalse(screen.isLaunchConfigPanelOpenForTest());

        source.setDevices(GamepadStateSource.DeviceState.connected(0, "pad", buttons(), 0f, 0f));
        input.refreshLogicalSnapshot();
        screen.update(input);
        pressFrame(screen, input, GLFW_KEY_ESCAPE); // Acknowledge the unavailable-action page.

        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        source.setDevices(GamepadStateSource.DeviceState.connected(0, "pad", buttons(GLFW_GAMEPAD_BUTTON_BACK), 0f, 0f));
        input.refreshLogicalSnapshot();
        screen.update(input);
        assertTrue(screen.isLaunchConfigPanelOpenForTest());
    }

    @Test
    void testModeTracePickerTakesPrecedenceOverLaunchPanelTab() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(com.openggf.configuration.SonicConfiguration.TEST_MODE_ENABLED, true);
        MasterTitleScreen screen = new MasterTitleScreen(config, new TrackingStore(config));
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        screen.setTracePickerForTest(new TestModeTracePicker(List.of(), null));

        pressFrame(screen, new InputHandler(), GLFW_KEY_TAB);

        assertFalse(screen.isLaunchConfigPanelOpenForTest());
    }

    @Test
    void openLaunchPanelDelegatesInputUntilClosed() {
        MasterTitleScreen screen = activeScreen();
        InputHandler input = new InputHandler();

        pressFrame(screen, input, GLFW_KEY_TAB);
        assertTrue(screen.isLaunchConfigPanelOpenForTest());

        pressFrame(screen, input, GLFW_KEY_RIGHT);
        assertEquals("s2", screen.getSelectedGameId(), "game-select right input should be ignored while panel is open");
        assertTrue(screen.currentLaunchProfileForTest().rewind(), "right input should cycle the panel row instead");

        pressFrame(screen, input, GLFW_KEY_ENTER);
        assertFalse(screen.isGameSelected(), "confirm input should be ignored while panel is open");
    }

    @Test
    void cancellingLaunchPanelDoesNotSaveAndReturnsToNormalMasterTitleInput() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        TrackingStore store = new TrackingStore(config);
        MasterTitleScreen screen = new MasterTitleScreen(config, store);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        InputHandler input = new InputHandler();

        pressFrame(screen, input, GLFW_KEY_TAB);
        pressFrame(screen, input, GLFW_KEY_ESCAPE);

        assertFalse(screen.isLaunchConfigPanelOpenForTest());
        assertEquals(0, store.saved.size());

        pressFrame(screen, input, GLFW_KEY_ENTER);
        assertFalse(screen.isGameSelected());
        pressFrame(screen, input, GLFW_KEY_ENTER);
        assertTrue(screen.isGameSelected());
        assertFalse(screen.isProgrammaticSelection());
    }

    @Test
    void logicalLeftRightNavigateActiveGameSelection() {
        MasterTitleScreen screen = activeScreen();
        InputHandler input = new InputHandler();

        input.setLogicalOverride(logicalPress(AbstractPlayableSprite.INPUT_RIGHT, 0, false));
        screen.update(input);
        assertEquals("s3k", screen.getSelectedGameId());

        input.setLogicalOverride(logicalPress(AbstractPlayableSprite.INPUT_LEFT, 0, false));
        screen.update(input);
        assertEquals("s2", screen.getSelectedGameId());
    }

    @Test
    void logicalBackOpensCancellableQuitBeforeAcceptCanEnterActions() {
        MasterTitleScreen screen = activeScreen();
        InputHandler input = new InputHandler();
        input.setLogicalOverride(logicalPress(0, InputActionMasks.ACTION_C, false));
        screen.update(input);
        assertFalse(screen.isGameSelected());
        input.setLogicalOverride(logicalPress(0, InputActionMasks.ACTION_A, false));
        screen.update(input);
        assertFalse(screen.isGameSelected());
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        screen.update(input);
        input.setLogicalOverride(logicalPress(0, InputActionMasks.ACTION_A, false));
        screen.update(input);
        assertFalse(screen.isGameSelected(), "accept enters actions after returning from Quit");
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        screen.update(input);
        input.setLogicalOverride(logicalPress(0, InputActionMasks.ACTION_A, false));
        screen.update(input);
        assertTrue(screen.isGameSelected());
        assertFalse(screen.isProgrammaticSelection());
    }

    @Test
    void selectEntryMarksProgrammaticSelection() {
        MasterTitleScreen screen = activeScreen();

        screen.selectEntry(MasterTitleScreen.GameEntry.SONIC_2);

        assertTrue(screen.isGameSelected());
        assertTrue(screen.isProgrammaticSelection());
    }

    private MasterTitleScreen activeScreen() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        MasterTitleScreen screen = new MasterTitleScreen(config, new TrackingStore(config));
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        return screen;
    }

    private static void pressFrame(MasterTitleScreen screen, InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
        screen.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
        screen.update(input);
    }

    private static boolean[] buttons(int... pressedButtons) {
        boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
        for (int button : pressedButtons) {
            buttons[button] = true;
        }
        return buttons;
    }

    private static final class FakeGamepadStateSource implements GamepadStateSource {
        private final List<DeviceState> devices = new ArrayList<>();

        void setDevices(DeviceState... devices) {
            this.devices.clear();
            this.devices.addAll(List.of(devices));
        }

        @Override
        public List<DeviceState> pollDevices() {
            return List.copyOf(devices);
        }
    }

    private static LogicalInputSnapshot logicalPress(int directionMask, int actionMask, boolean startPressed) {
        return LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(directionMask, directionMask, actionMask, actionMask, false, startPressed),
                PlayerInputState.neutral());
    }

    private static final class TrackingStore extends LaunchProfileStore {
        private final List<LaunchProfile> saved = new ArrayList<>();

        private TrackingStore(SonicConfigurationService configService) {
            super(configService);
        }

        @Override
        public void save(MasterTitleScreen.GameEntry entry, LaunchProfile profile) {
            saved.add(profile);
        }
    }
}
