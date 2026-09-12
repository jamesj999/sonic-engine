package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.EngineSettingsDraft;
import com.openggf.configuration.ConfigCatalog;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.openggf.configuration.SonicConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestEngineSettingsScreen {
    @TempDir Path directory;

    @Test
    void backLeavesFieldsBeforeClosingTheScreen() {
        Fixture f = new Fixture();
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_ESCAPE);
        assertFalse(f.screen.consumeCloseRequested(), "Back from fields returns to the visible categories");
        f.press(GLFW_KEY_ESCAPE);
        assertTrue(f.screen.consumeCloseRequested());
        assertFalse(f.screen.consumeCloseRequested(), "Close is consumed once");
    }

    @Test
    void dirtyCancelDefaultsToKeepEditingAndExplicitDiscardDoesNotSave() {
        Fixture f = new Fixture();
        boolean original = f.config.getBoolean(AUDIO_ENABLED);
        f.press(GLFW_KEY_DOWN);
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_RIGHT);
        f.press(GLFW_KEY_ESCAPE);
        f.press(GLFW_KEY_ESCAPE);
        assertTrue(f.texts().stream().anyMatch(s -> s.contains("Discard unsaved")));
        f.press(GLFW_KEY_ENTER); // safe default: keep editing
        assertFalse(f.screen.consumeCloseRequested());
        f.press(GLFW_KEY_ESCAPE);
        f.press(GLFW_KEY_RIGHT);
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.screen.consumeCloseRequested());
        assertEquals(original, f.config.getBoolean(AUDIO_ENABLED));
        assertEquals(original, SonicConfigurationService.createStandalone(directory).getBoolean(AUDIO_ENABLED));
    }

    @Test
    void applyPersistsDraftAndSignalsOnceWithoutClosing() {
        Fixture f = new Fixture();
        boolean original = f.config.getBoolean(AUDIO_ENABLED);
        f.press(GLFW_KEY_DOWN); // Audio
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_RIGHT);
        assertEquals(original, f.config.getBoolean(AUDIO_ENABLED));
        f.press(GLFW_KEY_ESCAPE);
        f.press(GLFW_KEY_UP, 3); // Audio -> Display -> Cancel -> Apply
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.screen.consumeApplied());
        assertFalse(f.screen.consumeApplied());
        assertFalse(f.screen.consumeCloseRequested());
        assertEquals(!original, SonicConfigurationService.createStandalone(directory).getBoolean(AUDIO_ENABLED));
        assertTrue(f.texts().stream().anyMatch(s -> s.startsWith("Saved. Restart")));
    }

    @Test
    void physicalTextEditorCanReplaceAPathAndCancelAnotherEdit() {
        Fixture f = new Fixture();
        f.press(GLFW_KEY_DOWN, 4); // Files
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.texts().contains("Sonic 1 ROM file"));
        assertFalse(f.texts().contains("roms.sonic1"), "Human labels are primary");
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_HOME);
        f.press(GLFW_KEY_DELETE, f.config.getString(SONIC_1_ROM).length());
        f.type("A");
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.texts().contains("A *"));
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_BACKSPACE);
        f.press(GLFW_KEY_ESCAPE);
        assertTrue(f.texts().contains("A *"), "Cancel editing preserves the previous draft value");
        f.press(GLFW_KEY_ESCAPE);
        f.press(GLFW_KEY_DOWN, 4); // Apply
        f.press(GLFW_KEY_ENTER);
        assertEquals("A", SonicConfigurationService.createStandalone(directory).getString(SONIC_1_ROM));
    }

    @Test
    void choicesShowFriendlyValuesAndCommitOnlyToTheDraft() {
        Fixture f = new Fixture();
        f.press(GLFW_KEY_DOWN);
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.texts().contains("Music and sound effects"));
        assertTrue(f.texts().contains("On"));
        assertTrue(f.texts().contains("Off"));
        f.press(GLFW_KEY_UP);
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.texts().contains("Off *"));
        assertTrue(f.config.getBoolean(AUDIO_ENABLED));
    }

    @Test
    void controllerBCancelsBindingCaptureThenEditorWithoutChangingBinding() {
        Fixture f = new Fixture();
        String original = f.config.getString(PAUSE_KEY);
        f.press(GLFW_KEY_DOWN, 2); // Input
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_ENTER); // PAUSE binding menu; capture is selected
        f.press(GLFW_KEY_ENTER);
        assertTrue(f.texts().contains("Press a keyboard key or chord"));
        f.padPress(GLFW_GAMEPAD_BUTTON_B);
        assertFalse(f.texts().contains("Press a keyboard key or chord"));
        assertTrue(f.texts().contains("Capture keyboard binding"), "B returns to the editor");
        f.padPress(GLFW_GAMEPAD_BUTTON_B);
        assertFalse(f.texts().contains("Capture keyboard binding"), "B then returns to the fields");
        assertEquals(original, f.config.getString(PAUSE_KEY));
        assertFalse(f.screen.consumeApplied());
    }

    @Test
    void allCategoryPagesAndEditorsKeepTextInsideNativeAndWideViewport() {
        Fixture f = new Fixture();
        EngineSettingsDraft catalog = new EngineSettingsDraft(f.config);
        for (EngineSettingsDraft.Category category : EngineSettingsDraft.Category.values()) {
            f.press(GLFW_KEY_ENTER);
            for (int row = 0; row < catalog.keys(category).size(); row++) {
                f.assertTextBounds(320);
                f.assertTextBounds(426);
                f.press(GLFW_KEY_ENTER);
                f.assertTextBounds(320);
                f.assertTextBounds(426);
                f.press(GLFW_KEY_ESCAPE);
                f.press(GLFW_KEY_DOWN);
            }
            f.press(GLFW_KEY_ESCAPE);
            f.press(GLFW_KEY_DOWN);
        }
        f.assertTextBounds(320); // Apply action
        f.press(GLFW_KEY_DOWN);
        f.assertTextBounds(320); // Cancel action
    }

    @Test
    void categoriesFieldsChoicesAndControlsUseTheOriginalFullSizeFont() {
        Fixture f = new Fixture();
        f.texts();
        for (String label : List.of("Display", "Recording", "Advanced", "Screen shape")) {
            assertEquals(1f, f.font.lines.stream().filter(line -> line.text.equals(label)).findFirst().orElseThrow().scale,
                    "Primary label must remain full size: " + label);
        }
        assertEquals(1f, f.font.lines.stream().filter(line -> line.y == 212).findFirst().orElseThrow().scale);
        f.press(GLFW_KEY_DOWN);
        f.press(GLFW_KEY_ENTER);
        f.press(GLFW_KEY_ENTER);
        f.texts();
        for (String label : List.of("On", "Off", "Restore engine default")) {
            assertEquals(1f, f.font.lines.stream().filter(line -> line.text.equals(label)).findFirst().orElseThrow().scale);
        }
        assertTrue(f.font.lines.stream().anyMatch(line -> line.scale == MenuStyle.COMPACT),
                "Descriptions and status may use the secondary font");
    }

    @Test
    void shortDescriptionDisplaysBothLinesImmediatelyWithinTheAvailableBand() {
        Fixture f = new Fixture();
        f.press(GLFW_KEY_ENTER); // Display / screen shape
        for (int width : new int[] {320, 426}) {
            f.assertTextBounds(width);
            List<Line> help = f.font.lines.stream().filter(line -> line.y == 178 || line.y == 188).toList();
            if (width == 320) assertEquals(2, help.size(), "Both native-width help lines appear without waiting");
            else assertTrue(help.size() >= 1 && help.size() <= 2, "Wider screens may fit the whole description on one line");
            assertEquals(ConfigCatalog.meta(DISPLAY_ASPECT).description(),
                    String.join(" ", help.stream().map(Line::text).toList()));
            for (Line line : help) {
                assertEquals(MenuStyle.COMPACT, line.scale, "Only explanatory text uses the secondary font");
                assertTrue(line.y >= 178 && line.y + 8 <= 198, "Help stays clear of the rail and footer");
            }
        }
    }

    @Test
    void longDescriptionsAdvanceInReadablePairsWithoutDroppingText() {
        Fixture f = new Fixture();
        f.press(GLFW_KEY_DOWN, 5); // Recording
        f.press(GLFW_KEY_ENTER);
        int codecRow = new EngineSettingsDraft(f.config).keys(EngineSettingsDraft.Category.RECORDING).indexOf(CAPTURE_CODEC);
        assertTrue(codecRow >= 0);
        f.press(GLFW_KEY_DOWN, codecRow);
        List<String> first = f.descriptionLines();
        assertEquals(2, first.size());
        for (int i = 0; i < 240; i++) f.frame();
        assertEquals(first, f.descriptionLines(), "Reading the first pair no longer advances after only four seconds");
        for (int i = 0; i < 240; i++) f.frame();

        List<String> allLines = new ArrayList<>(first);
        boolean returnedToFirst = false;
        for (int page = 0; page < 10; page++) {
            List<String> current = f.descriptionLines();
            if (current.equals(first)) { returnedToFirst = true; break; }
            allLines.addAll(current);
            for (int i = 0; i < 480; i++) f.frame();
        }
        assertTrue(returnedToFirst, "The complete help repeats after its final page");
        assertEquals(ConfigCatalog.meta(CAPTURE_CODEC).description(), String.join(" ", allLines));
        f.press(GLFW_KEY_DOWN);
        f.press(GLFW_KEY_UP);
        assertEquals(first, f.descriptionLines(), "Selecting a field restarts at the first help pair");
    }

    private final class Fixture {
        final SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        final RecordingFont font = new RecordingFont();
        final EngineSettingsScreen screen = new EngineSettingsScreen(config, font);
        List<GamepadStateSource.DeviceState> pads = List.of();
        final InputHandler input = new InputHandler(InputBindingFactory.supplier(config), () -> pads);

        Fixture() {
            config.setConfigValue(CONTROLLER_ENABLED, true);
            config.setConfigValue(CONTROLLER_PLAYER1, "auto");
            config.setConfigValue(CONTROLLER_PLAYER2, "none");
            pad();
            frame();
        }
        void press(int key) { press(key, 1); }
        void press(int key, int times) {
            for (int i = 0; i < times; i++) {
                input.handleKeyEvent(key, GLFW_PRESS);
                frame();
                input.handleKeyEvent(key, GLFW_RELEASE);
                frame();
            }
        }
        void type(String text) { text.codePoints().forEach(c -> MenuInput.handleCharEvent(input, c)); frame(); }
        void padPress(int key) { pad(key); frame(); pad(); frame(); }
        void pad(int... buttons) {
            boolean[] state = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            for (int button : buttons) state[button] = true;
            pads = List.of(GamepadStateSource.DeviceState.connected(0, "Test pad", state, 0, 0));
        }
        void frame() { input.refreshLogicalSnapshot(); screen.update(input); input.update(); }
        List<String> descriptionLines() {
            texts();
            return font.lines.stream().filter(line -> line.y == 178 || line.y == 188).map(Line::text).toList();
        }
        List<String> texts() {
            font.lines.clear();
            screen.render(320);
            return font.lines.stream().map(Line::text).toList();
        }
        void assertTextBounds(int width) {
            font.lines.clear();
            screen.render(width);
            for (Line line : font.lines) {
                assertTrue(line.x >= 0 && line.y >= 0, line.text);
                assertTrue(line.x + font.measureWidth(line.text, line.scale) <= width,
                        () -> "Text overflows " + width + ": " + line);
                assertTrue(line.y + (line.scale < 1 ? 8 : 10) <= 224, line.text);
                assertTrue(line.scale == MenuStyle.COMPACT || line.scale == 1, "Only native font sizes: " + line);
            }
        }
    }

    private record Line(String text, int x, int y, float scale) { }
    private static final class RecordingFont extends PixelFont {
        final List<Line> lines = new ArrayList<>();
        @Override public void drawText(String text, int x, int y, float scale, float r, float g, float b, float a) {
            lines.add(new Line(text, x, y, scale));
        }
    }

}
