package com.openggf.game;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.launch.LaunchProfileStore;
import com.openggf.graphics.PixelFont;
import com.openggf.graphics.TexturedQuadRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.openggf.game.TestMasterTitleHub.press;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.mockito.Mockito.mock;

class TestMasterTitleHubMessages {
    @TempDir Path directory;
    private final RecordingFont font = new RecordingFont();

    @Test void unavailableStandaloneActionCannotLeakIntoTheNextMissingRomError() throws Exception {
        var screen = screen(true);
        screen.setSelectedIndexForTest(3);
        press(screen, GLFW_KEY_DOWN);
        for (int i = 0; i < 3; i++) press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        for (int i = 0; i < 600; i++) screen.update(new InputHandler());
        draw(screen);
        assertTrue(font.has("ACTION UNAVAILABLE"), "error stays open until acknowledged");
        assertTrue(font.allText().contains("Recordings is not available for standalone games"));
        assertFalse(font.has("Check Files in Engine Settings"));

        press(screen, GLFW_KEY_ESCAPE);
        press(screen, GLFW_KEY_LEFT);
        for (int i = 0; i < 3; i++) press(screen, GLFW_KEY_UP);
        press(screen, GLFW_KEY_ENTER);
        draw(screen);
        assertTrue(font.has("ROM NOT FOUND"));
        assertTrue(font.has("missing-s3k.gen"));
        assertTrue(font.has("Check Files in Engine Settings"));
        assertFalse(font.allText().contains("Recordings is not available for standalone games"));
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3})
    void missingRomActionsExplainTheMissingFile(int action) throws Exception {
        var screen = screen(false);
        press(screen, GLFW_KEY_DOWN);
        for (int i = 0; i < action; i++) press(screen, GLFW_KEY_DOWN);
        press(screen, GLFW_KEY_ENTER);
        draw(screen);
        assertTrue(font.has("ROM NOT FOUND"));
        assertTrue(font.has("missing-s2.gen"));
        assertFalse(font.has("ACTION UNAVAILABLE"));
    }

    @Test void carouselShowsSelectionNeighborsAndMissingRomStatus() throws Exception {
        var screen = screen(false);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        for (int width : new int[] {320, 400, 528}) {
            screen.setViewportWidth(width);
            draw(screen);
            assertTrue(font.has("Sonic 1"));
            assertTrue(font.has("Sonic 2"));
            assertTrue(font.has("Sonic 3K"));
            assertTrue(font.red("Sonic 1") < font.red("Sonic 2"));
            assertTrue(font.red("Sonic 3K") < font.red("Sonic 2"));
            assertTrue(font.has("ADVANCED"));
            assertTrue(font.has("QUIT"));
            assertTrue(font.has("2 / 3"));
            assertTrue(font.texts.stream().filter(t -> t.y == 181)
                    .allMatch(t -> t.x >= 8 && t.x + t.text.length() * 6 <= width / 2));
        }
    }

    @Test void profileStatusOnlyAppearsForAnAvailableGame() throws Exception {
        var screen = screen(false);
        draw(screen);
        assertTrue(font.has("ROM NOT FOUND"));
        assertFalse(font.has("Stock profile"));
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        draw(screen);
        assertTrue(font.has("Stock profile"));
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, false);
        draw(screen);
        assertFalse(font.has("Stock profile"));
    }

    private MasterTitleScreen screen(boolean standalone) throws Exception {
        var config = SonicConfigurationService.createStandalone(directory);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, false);
        config.setConfigValue(SonicConfiguration.SONIC_2_ROM, "missing-s2.gen");
        config.setConfigValue(SonicConfiguration.SONIC_3K_ROM, "missing-s3k.gen");
        var entries = new ArrayList<MasterTitleEntry>();
        for (var game : MasterTitleScreen.GameEntry.values()) entries.add(new MasterTitleEntry.Stock(game));
        if (standalone) entries.add(new MasterTitleEntry.Standalone("sample", "Sample game", false));
        var screen = new MasterTitleScreen(config, new LaunchProfileStore(config), entries, cue -> {});
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        for (var game : MasterTitleScreen.GameEntry.values()) screen.setRomAvailableForTest(game, false);
        var rendererField = MasterTitleScreen.class.getDeclaredField("renderer");
        rendererField.setAccessible(true);
        rendererField.set(screen, mock(TexturedQuadRenderer.class));
        var fontField = MasterTitleScreen.class.getDeclaredField("font");
        fontField.setAccessible(true);
        fontField.set(screen, font);
        return screen;
    }

    private void draw(MasterTitleScreen screen) {
        font.texts.clear();
        screen.drawForRecording();
    }

    private record Text(String text, float x, float y, float red) { }
    private static final class RecordingFont extends PixelFont {
        final List<Text> texts = new ArrayList<>();
        @Override public void beginMegaBatch() { }
        @Override public void endMegaBatch() { }
        @Override public void drawText(String text, float x, float y, float scale,
                float r, float g, float b, float a) { texts.add(new Text(text, x, y, r)); }
        boolean has(String text) { return texts.stream().anyMatch(t -> t.text.equals(text)); }
        String allText() { return texts.stream().map(Text::text).collect(java.util.stream.Collectors.joining()); }
        float red(String text) { return texts.stream().filter(t -> t.text.equals(text)).findFirst().orElseThrow().red; }
    }
}
