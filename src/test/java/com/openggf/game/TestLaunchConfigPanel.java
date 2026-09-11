package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.game.launch.LaunchProfile;
import com.openggf.game.launch.LaunchProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_3K;
import static com.openggf.game.launch.LaunchProfile.Row.DEBUG_TOOLS;
import static com.openggf.game.launch.LaunchProfile.Row.MAIN_CHARACTER;
import static com.openggf.game.launch.LaunchProfile.Row.REWIND;
import static com.openggf.game.launch.LaunchProfile.Row.SIDEKICK;
import static com.openggf.game.launch.LaunchProfile.Row.WIDESCREEN;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_BACK;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_UP;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_W;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestLaunchConfigPanel {

    @TempDir
    Path tempDir;

    @Test
    void configuredUpDownMoveSelectedRowAndWrap() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        InputHandler input = new InputHandler();

        pressFrame(panel, input, GLFW_KEY_W);
        assertEquals(SIDEKICK, panel.selectedRowForTest());

        pressFrame(panel, input, GLFW_KEY_S);
        assertEquals(REWIND, panel.selectedRowForTest());
    }

    @Test
    void configuredLeftRightCycleSelectedRowAndWrap() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        InputHandler input = new InputHandler();

        pressFrame(panel, input, GLFW_KEY_A);
        assertTrue(panel.currentProfileForTest().rewind());

        pressFrame(panel, input, GLFW_KEY_D);
        assertFalse(panel.currentProfileForTest().rewind());
    }

    @Test
    void backspaceResetsAllRowsToStock() {
        SonicConfigurationService config = configuredWasd();
        LaunchProfile changed = new LaunchProfile(true, "s1", true, "WIDE_16_9", "tails", "none");
        LaunchConfigPanel panel = panel(config, changed, new TrackingStore(config));
        InputHandler input = new InputHandler();

        pressFrame(panel, input, GLFW_KEY_BACKSPACE);

        assertEquals(LaunchProfile.stockFor(SONIC_3K), panel.currentProfileForTest());
    }

    @Test
    void constructorClampsCharacterRowsUnavailableFromCurrentDonor() {
        SonicConfigurationService config = configuredWasd();
        LaunchProfile invalid = new LaunchProfile(false, "s1", false, "global", "tails", "tails");

        LaunchConfigPanel panel = panel(config, invalid, new TrackingStore(config));

        assertEquals("sonic", panel.currentProfileForTest().mainCharacter());
        assertEquals("none", panel.currentProfileForTest().sidekick());
    }

    @Test
    void tabAndEscapeCloseWithClosedResult() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel tabPanel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        InputHandler input = new InputHandler();

        pressFrame(tabPanel, input, GLFW_KEY_TAB);
        assertEquals(LaunchConfigPanel.Result.CLOSED, tabPanel.consumeResult());
        assertEquals(LaunchConfigPanel.Result.NONE, tabPanel.consumeResult());

        LaunchConfigPanel escapePanel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        pressFrame(escapePanel, input, GLFW_KEY_ESCAPE);
        assertEquals(LaunchConfigPanel.Result.CLOSED, escapePanel.consumeResult());
    }

    @Test
    void gamepadDpadUpDownMoveSelectedRow() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), source);

        pressGamepadFrame(panel, input, source, GLFW_GAMEPAD_BUTTON_DPAD_UP);
        assertEquals(SIDEKICK, panel.selectedRowForTest());

        pressGamepadFrame(panel, input, source, GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        assertEquals(REWIND, panel.selectedRowForTest());
    }

    @Test
    void gamepadDpadLeftRightCycleSelectedValue() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), source);

        pressGamepadFrame(panel, input, source, GLFW_GAMEPAD_BUTTON_DPAD_LEFT);
        assertTrue(panel.currentProfileForTest().rewind());

        pressGamepadFrame(panel, input, source, GLFW_GAMEPAD_BUTTON_DPAD_RIGHT);
        assertFalse(panel.currentProfileForTest().rewind());
    }

    @Test
    void gamepadBackButtonClosesWithClosedResult() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), source);

        source.setDevices(GamepadStateSource.DeviceState.connected(0, "pad", buttons(GLFW_GAMEPAD_BUTTON_BACK), 0f, 0f));
        input.refreshLogicalSnapshot();
        panel.update(input);

        assertEquals(LaunchConfigPanel.Result.CLOSED, panel.consumeResult());
    }

    @Test
    void closingSavesExactlyOnceThroughInjectedStore() {
        SonicConfigurationService config = configuredWasd();
        TrackingStore store = new TrackingStore(config);
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), store);
        InputHandler input = new InputHandler();

        pressFrame(panel, input, GLFW_KEY_TAB);
        input.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
        input.update();
        pressFrame(panel, input, GLFW_KEY_ESCAPE);

        assertEquals(0, store.saved.size(), "panel update should not save; owner consumes CLOSED and saves");
    }

    @Test
    void rowViewsExposeGlobalAspectPinnedAspectAndStandardMarkers() {
        SonicConfigurationService config = configuredWasd();
        config.setConfigValue(SonicConfiguration.DISPLAY_ASPECT, "WIDE_16_9");
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", "tails", "none");
        LaunchConfigPanel panel = panel(config, profile, new TrackingStore(config));

        List<LaunchConfigPanel.RowView> rows = panel.rowViews();

        assertEquals("Global (16:9)", rows.get(WIDESCREEN.ordinal()).value());
        assertFalse(rows.get(WIDESCREEN.ordinal()).nonStandard());
        assertFalse(rows.get(MAIN_CHARACTER.ordinal()).stock());
        assertFalse(rows.get(MAIN_CHARACTER.ordinal()).nonStandard());
        assertFalse(rows.get(SIDEKICK.ordinal()).stock());
        assertFalse(rows.get(SIDEKICK.ordinal()).nonStandard());

        LaunchConfigPanel pinned = panel(config,
                new LaunchProfile(false, "off", true, "ULTRA_21_9", "tails", "knuckles"),
                new TrackingStore(config));
        List<LaunchConfigPanel.RowView> pinnedRows = pinned.rowViews();

        assertEquals("21:9", pinnedRows.get(WIDESCREEN.ordinal()).value());
        assertTrue(pinnedRows.get(WIDESCREEN.ordinal()).nonStandard());
        assertTrue(pinnedRows.get(DEBUG_TOOLS.ordinal()).nonStandard());
        assertTrue(pinnedRows.get(SIDEKICK.ordinal()).nonStandard());
    }

    @Test
    void s3kDonationHidesCharacterRowsAndNavigationSkipsThem() {
        SonicConfigurationService config = configuredWasd();
        LaunchProfile profile = new LaunchProfile(false, "s3k", false, "global", "knuckles", "tails");
        LaunchConfigPanel panel = panel(config, profile, new TrackingStore(config));
        InputHandler input = new InputHandler();

        assertIterableEquals(List.of(
                        "Rewind",
                        "Cross-game",
                        "Debug tools",
                        "Widescreen"),
                panel.rowViews().stream().map(LaunchConfigPanel.RowView::label).toList());

        pressFrame(panel, input, GLFW_KEY_W);

        assertEquals(WIDESCREEN, panel.selectedRowForTest());
    }

    @Test
    void textLineViewsCenterRowsAndFooterInViewport() {
        SonicConfigurationService config = configuredWasd();
        LaunchConfigPanel panel = panel(config, LaunchProfile.stockFor(SONIC_3K), new TrackingStore(config));

        for (LaunchConfigPanel.TextLineView line : panel.textLineViews(400)) {
            int expectedX = Math.round((400 - line.measuredWidth()) / 2f);
            assertEquals(expectedX, line.x(), "line should center on viewport: " + line.text());
        }
    }

    @Test
    void textLineViewsRenderNormalWideAsAmberNonStandard() {
        SonicConfigurationService config = configuredWasd();
        LaunchProfile profile = new LaunchProfile(false, "off", false, "WIDE_16_9", "sonic", "tails");
        LaunchConfigPanel panel = new LaunchConfigPanel(SONIC_3K, profile, new TrackingStore(config),
                config, new MeasuringFont(), null);

        LaunchConfigPanel.TextLineView widescreen = lineContaining(panel.textLineViews(400), "Widescreen:");

        assertTrue(widescreen.text().contains("*"), widescreen.text());
        assertFalse(widescreen.text().contains("!"), widescreen.text());
        assertEquals(1f, widescreen.r());
        assertEquals(0.72f, widescreen.g());
        assertEquals(0.25f, widescreen.b());
    }

    @Test
    void textLineViewsRenderUltrawideAsRedExperimental() {
        SonicConfigurationService config = configuredWasd();
        LaunchProfile profile = new LaunchProfile(false, "off", false, "ULTRA_21_9", "sonic", "tails");
        LaunchConfigPanel panel = new LaunchConfigPanel(SONIC_3K, profile, new TrackingStore(config),
                config, new MeasuringFont(), null);

        List<LaunchConfigPanel.TextLineView> lines = panel.textLineViews(400);
        LaunchConfigPanel.TextLineView widescreen = lineContaining(lines, "Widescreen:");

        assertTrue(widescreen.text().contains("!"), widescreen.text());
        assertFalse(widescreen.text().contains("*"), widescreen.text());
        assertEquals(1f, widescreen.r());
        assertEquals(0.25f, widescreen.g());
        assertEquals(0.25f, widescreen.b());
        assertTrue(lines.stream().anyMatch(line -> line.text().contains("! experimental")));
    }

    @Test
    void textLineViewsFitInsideNativeViewportWithRealGlyphMetrics() {
        SonicConfigurationService config = configuredWasd();
        LaunchProfile profile = new LaunchProfile(true, "s2", true, "SUPER_32_9", "knuckles", "tails");
        LaunchConfigPanel panel = new LaunchConfigPanel(SONIC_3K, profile, new TrackingStore(config),
                config, new MeasuringFont(), null);

        for (LaunchConfigPanel.TextLineView line : panel.textLineViews(320)) {
            assertTrue(line.x() >= 4, "line should not bleed left: " + line.text());
            assertTrue(line.x() + line.measuredWidth() <= 316, "line should not bleed right: " + line.text());
        }
    }

    private SonicConfigurationService configuredWasd() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.UP, GLFW_KEY_W);
        config.setConfigValue(SonicConfiguration.DOWN, GLFW_KEY_S);
        config.setConfigValue(SonicConfiguration.LEFT, GLFW_KEY_A);
        config.setConfigValue(SonicConfiguration.RIGHT, GLFW_KEY_D);
        return config;
    }

    private static LaunchConfigPanel.TextLineView lineContaining(
            List<LaunchConfigPanel.TextLineView> lines,
            String text) {
        return lines.stream()
                .filter(line -> line.text().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing line containing: " + text));
    }

    private static LaunchConfigPanel panel(SonicConfigurationService config,
                                           LaunchProfile profile,
                                           LaunchProfileStore store) {
        return new LaunchConfigPanel(SONIC_3K, profile, store, config, null, null);
    }

    private static void pressFrame(LaunchConfigPanel panel, InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
        panel.update(input);
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.update();
    }

    private static void pressGamepadFrame(
            LaunchConfigPanel panel, InputHandler input, FakeGamepadStateSource source, int gamepadButton) {
        source.setDevices(GamepadStateSource.DeviceState.connected(0, "pad", buttons(gamepadButton), 0f, 0f));
        input.refreshLogicalSnapshot();
        panel.update(input);
        source.setDevices(GamepadStateSource.DeviceState.connected(0, "pad", buttons(), 0f, 0f));
        input.refreshLogicalSnapshot();
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

    private static final class TrackingStore extends LaunchProfileStore {
        private final List<SavedProfile> saved = new ArrayList<>();

        private TrackingStore(SonicConfigurationService configService) {
            super(configService);
        }

        @Override
        public void save(MasterTitleScreen.GameEntry entry, LaunchProfile profile) {
            saved.add(new SavedProfile(entry, profile));
        }
    }

    private record SavedProfile(MasterTitleScreen.GameEntry entry, LaunchProfile profile) {
    }

    private static final class MeasuringFont extends com.openggf.graphics.PixelFont {
        @Override
        public int measureWidth(String text) {
            return measureWidth(text, 1.0f);
        }

        @Override
        public int measureWidth(String text, float scale) {
            return Math.round(text.length() * 9 * scale);
        }
    }
}
