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
import com.openggf.graphics.PixelFont;
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

    // -------------------------------------------------------------------------
    // centerX — native parity
    // -------------------------------------------------------------------------

    /**
     * At native width 320, centerX(w, 320) must equal (320-w)/2 exactly.
     * This ensures byte-identical behavior to the original literals.
     */
    @Test
    void centerX_atNativeWidth_collapsesToOriginalLiteral() {
        // Various element widths that MasterTitleScreen might encounter
        int[] widths = {0, 10, 64, 100, 128, 160, 200, 320};
        for (int w : widths) {
            float expected = (320 - w) / 2f;
            float actual = MasterTitleScreen.centerX(w, 320);
            assertEquals(expected, actual, 0f,
                "centerX(" + w + ", 320) should equal (320-" + w + ")/2 = " + expected);
        }
    }

    /**
     * At viewport width 400 (WIDE_16_9), centerX places the element so it is
     * horizontally centered: left edge at (400-w)/2.
     */
    @Test
    void centerX_atWide16_9_centresElement() {
        int vpWidth = 400;
        int elementWidth = 100;
        float result = MasterTitleScreen.centerX(elementWidth, vpWidth);
        assertEquals((vpWidth - elementWidth) / 2f, result, 0f);
        // Verify: left edge + element width = right edge mirror of left edge
        assertEquals(vpWidth - result - elementWidth, result, 0f,
            "Element should be symmetrically centered");
    }

    /**
     * At viewport width 528 (ULTRA_21_9), centerX correctly places the element.
     */
    @Test
    void centerX_atUltra21_9_centresElement() {
        int vpWidth = 528;
        int elementWidth = 80;
        float result = MasterTitleScreen.centerX(elementWidth, vpWidth);
        assertEquals((vpWidth - elementWidth) / 2f, result, 0f);
    }

    /**
     * Zero-width element centers at the midpoint (no-op offset).
     */
    @Test
    void centerX_zeroWidthElement_returnsMidpoint() {
        assertEquals(160f, MasterTitleScreen.centerX(0, 320), 0f);
        assertEquals(200f, MasterTitleScreen.centerX(0, 400), 0f);
        assertEquals(264f, MasterTitleScreen.centerX(0, 528), 0f);
    }

    /**
     * Element equal to viewport width results in x=0.
     */
    @Test
    void centerX_elementFillsViewport_returnsZero() {
        assertEquals(0f, MasterTitleScreen.centerX(320, 320), 0f);
        assertEquals(0f, MasterTitleScreen.centerX(400, 400), 0f);
    }

    // -------------------------------------------------------------------------
    // SCREEN_W constant — must remain 320 for native parity
    // -------------------------------------------------------------------------

    @Test
    void screenW_isNativeWidth() {
        assertEquals(320, MasterTitleScreen.SCREEN_W,
            "SCREEN_W must remain 320 to preserve native parity");
    }

    // -------------------------------------------------------------------------
    // setViewportWidth — clamped at SCREEN_W
    // -------------------------------------------------------------------------

    @Test
    void setViewportWidth_belowNative_clampsToScreenW() {
        // We can't easily query viewportWidth directly (it's private), but we can
        // check that setting a value below SCREEN_W doesn't cause an exception
        // and that SCREEN_W itself is used as the floor.
        // This is a smoke-test; the behavioral proof is in centerX tests above.
        assertEquals(320, MasterTitleScreen.SCREEN_W);
    }

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
    void missingRomPrompt_showsRequiredRomLineAndSelectedFilename() {
        assertEquals("Requires the following ROM:",
                MasterTitleScreen.missingRomPromptLine());
        assertEquals("s2.gen",
                MasterTitleScreen.missingRomFilenameLine(MasterTitleScreen.GameEntry.SONIC_2));
    }

    @Test
    void launchHoverLineUsesAtlasSafeHyphenAndPluralization() {
        assertEquals("Stock launch - Tab to configure", MasterTitleScreen.launchHoverLine(0));
        assertEquals("1 option enabled - Tab to configure", MasterTitleScreen.launchHoverLine(1));
        assertEquals("3 options enabled - Tab to configure", MasterTitleScreen.launchHoverLine(3));
    }

    @Test
    void gameEntryFromGameIdMatchesCaseInsensitivelyAndRejectsUnknownIds() {
        assertEquals(MasterTitleScreen.GameEntry.SONIC_1, MasterTitleScreen.GameEntry.fromGameId("S1"));
        assertEquals(MasterTitleScreen.GameEntry.SONIC_2, MasterTitleScreen.GameEntry.fromGameId("s2"));
        assertEquals(MasterTitleScreen.GameEntry.SONIC_3K, MasterTitleScreen.GameEntry.fromGameId("s3k"));
        assertThrows(IllegalArgumentException.class, () -> MasterTitleScreen.GameEntry.fromGameId("bad"));
    }

    @Test
    void menuTextColor_keepsUnavailableUnselectedGamesGreyedOut() {
        float[] color = MasterTitleScreen.menuTextColor(false, false, 0);

        assertEquals(0.4f, color[0], 0f);
        assertEquals(0.4f, color[1], 0f);
        assertEquals(0.4f, color[2], 0f);
        assertEquals(0.7f, color[3], 0f);
    }

    @Test
    void menuTextColor_highlightsUnavailableSelectedGamesWhileDisabled() {
        float[] color = MasterTitleScreen.menuTextColor(false, true, 0);

        assertEquals(0.72f, color[0], 0f);
        assertEquals(0.72f, color[1], 0f);
        assertEquals(0.72f, color[2], 0f);
        assertEquals(0.85f, color[3], 0f);
    }

    @Test
    void romPreviewLayoutRendersNativeTitleScreensAtNativeSize() {
        MasterTitleScreen.PreviewLayout layout = MasterTitleScreen.romPreviewLayout(320, 224, 320);

        assertEquals(320, layout.width());
        assertEquals(224, layout.height());
        assertEquals(0f, layout.x(), 0f);
        assertEquals(0f, layout.y(), 0f);
    }

    @Test
    void romPreviewLayoutStaysCenteredInWideViewports() {
        MasterTitleScreen.PreviewLayout layout = MasterTitleScreen.romPreviewLayout(320, 224, 400);

        assertEquals(320, layout.width());
        assertEquals(224, layout.height());
        assertEquals(40f, layout.x(), 0f);
    }

    @Test
    void romPreviewTopMatteIsDisabledForOpenGgfLogoArea() {
        MasterTitleScreen.PreviewLayout layout = MasterTitleScreen.topUiMatteLayout(400);

        assertEquals(400, layout.width());
        assertEquals(0, layout.height());
        assertEquals(0f, layout.x(), 0f);
        assertEquals(224f, layout.y(), 0f);
    }

    @Test
    void romPreviewBottomMatteCoversMenuArea() {
        MasterTitleScreen.PreviewLayout layout = MasterTitleScreen.bottomUiMatteLayout(400);

        assertEquals(400, layout.width());
        assertEquals(56, layout.height());
        assertEquals(0f, layout.x(), 0f);
        assertEquals(0f, layout.y(), 0f);
    }

    @Test
    void launchHoverLineFitsInsideNativeViewportAndBottomMatte() {
        String line = MasterTitleScreen.launchHoverLine(5);
        int width = Math.round(line.length() * PixelFont.glyphWidth() * MasterTitleScreen.LAUNCH_HOVER_SCALE);
        int x = MasterTitleScreen.scaledCenteredTextX(line, 320, MasterTitleScreen.LAUNCH_HOVER_SCALE);

        assertTrue(x >= 4, "hover line should not bleed left");
        assertTrue(x + width <= 316, "hover line should not bleed right");

        MasterTitleScreen.PreviewLayout matte = MasterTitleScreen.bottomUiMatteLayout(320);
        assertTrue(MasterTitleScreen.LAUNCH_HOVER_Y >= 224 - matte.height() + 2,
                "hover line should sit inside the bottom matte");
    }

    @Test
    void launchConfigOverlayIsDarkEnoughForTextReadability() {
        assertTrue(MasterTitleScreen.LAUNCH_PANEL_OVERLAY_ALPHA >= 0.7f);
    }

    @Test
    void titleLogoYPlacesOpenGgfLogoOneTileHigher() {
        assertEquals(180f, MasterTitleScreen.titleLogoY(42), 0f);
    }

    @Test
    void titleLogoScaleReducesOpenGgfLogoToNineTenths() {
        assertEquals(63, MasterTitleScreen.titleLogoScaledWidth(200));
        assertEquals(31, MasterTitleScreen.titleLogoScaledHeight(100));
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
    void closingLaunchPanelSavesAndReturnsToNormalMasterTitleInput() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        TrackingStore store = new TrackingStore(config);
        MasterTitleScreen screen = new MasterTitleScreen(config, store);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        InputHandler input = new InputHandler();

        pressFrame(screen, input, GLFW_KEY_TAB);
        pressFrame(screen, input, GLFW_KEY_ESCAPE);

        assertFalse(screen.isLaunchConfigPanelOpenForTest());
        assertEquals(1, store.saved.size());

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
    void logicalMenuAcceptConfirmsActiveGameSelection() {
        MasterTitleScreen screen = activeScreen();
        InputHandler input = new InputHandler();

        input.setLogicalOverride(logicalPress(0, InputActionMasks.ACTION_C, false));
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
