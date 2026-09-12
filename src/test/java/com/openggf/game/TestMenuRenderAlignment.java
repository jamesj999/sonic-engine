package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.timeattack.GhostStore;
import com.openggf.game.timeattack.TimeAttackMenu;
import com.openggf.graphics.MenuPixelFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.mockito.Mockito.mock;

/** Records the real render calls, including native font rectangles, without a GL context. */
class TestMenuRenderAlignment {
    @TempDir Path directory;

    @Test
    void settingsRailAndDoubleLineFieldsCenterTheirActualGlyphCells() {
        for (int width : new int[] {320, 426}) {
            Capture capture = new Capture();
            SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
            EngineSettingsScreen screen = new EngineSettingsScreen(config, capture.font);
            InputHandler input = new InputHandler(InputBindingFactory.supplier(config), List::of);
            screen.render(width);
            Rect rail = capture.focus.getFirst();
            assertCentered(rail, capture.text("Display").y, 10);
            assertEquals(13, rail.height);

            press(input, screen::update, GLFW_KEY_ENTER);
            capture.clear();
            screen.render(width);
            Rect field = capture.focus.getFirst();
            List<Line> contents = capture.lines.stream().filter(line -> line.x == 111 && line.scale == 1
                    && line.y >= field.y && line.y + 10 <= field.y + field.height).toList();
            assertEquals(2, contents.size());
            assertCentered(field, contents.getFirst().y, contents.getLast().y + 10 - contents.getFirst().y);
            assertEquals(width - 7, field.x + field.width);
        }
    }

    @Test
    void timeAttackFieldsAndFinalActionUseTheSameCenteredPrimaryRows() throws ReflectiveOperationException {
        Capture capture = new Capture();
        SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), List::of);
        TimeAttackMenu menu = new TimeAttackMenu(List.of("s2"), "s2", new GhostStore(directory),
                capture.font, request -> fail("Rendering must not launch"));
        var visibleRows = menu.state().getClass().getDeclaredMethod("visibleRows");
        visibleRows.setAccessible(true);
        int count = ((List<?>) visibleRows.invoke(menu.state())).size();
        for (int row = 0; row <= count; row++) {
            capture.clear();
            menu.render();
            Rect focus = capture.focus.getFirst();
            Line label = capture.lines.stream().filter(line -> line.x == 14 && line.scale == 1
                    && line.y >= focus.y && line.y + 10 <= focus.y + focus.height).findFirst().orElseThrow();
            assertCentered(focus, label.y, 10);
            assertEquals(16, focus.height);
            assertTrue(focus.y + focus.height <= 198);
            if (row < count) press(input, menu::update, GLFW_KEY_DOWN);
        }
    }

    private static void press(InputHandler input, Consumer<InputHandler> update, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
        input.refreshLogicalSnapshot();
        update.accept(input);
        input.update();
        input.handleKeyEvent(key, GLFW_RELEASE);
        input.refreshLogicalSnapshot();
        update.accept(input);
        input.update();
    }

    private static void assertCentered(Rect rect, int textY, int textHeight) {
        int above = textY - rect.y;
        int below = rect.y + rect.height - textY - textHeight;
        assertTrue(above >= 1 && below >= 1, "Glyph cells must clear both focus borders");
        assertTrue(Math.abs(above - below) <= 1, "Native cell padding must be balanced");
    }

    private record Line(String text, int x, int y, float scale) { }
    private record Rect(int x, int y, int width, int height) { }

    private static final class Capture {
        final List<Line> lines = new ArrayList<>();
        final List<Rect> focus = new ArrayList<>();
        final MenuPixelFont font = mock(MenuPixelFont.class, invocation -> {
            Object[] args = invocation.getArguments();
            if (invocation.getMethod().getName().equals("drawText") && args.length == 8) {
                lines.add(new Line((String) args[0], ((Number) args[1]).intValue(),
                        ((Number) args[2]).intValue(), ((Number) args[3]).floatValue()));
            } else if (invocation.getMethod().getName().equals("fillRect")
                    && (float) args[4] == .08f && (float) args[5] == .23f) {
                focus.add(new Rect((int) args[0], (int) args[1], (int) args[2], (int) args[3]));
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        Line text(String text) { return lines.stream().filter(line -> line.text.equals(text)).findFirst().orElseThrow(); }
        void clear() { lines.clear(); focus.clear(); }
    }
}
