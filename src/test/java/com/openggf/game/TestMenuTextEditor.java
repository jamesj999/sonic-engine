package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.control.MenuInput;
import com.openggf.graphics.PixelFont;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.openggf.configuration.SonicConfiguration.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestMenuTextEditor {
    @TempDir Path directory;

    @Test
    void physicalTypingAndCaretEditingIgnoreGameplayLetterBindings() {
        Fixture f = new Fixture("ab", 64);
        f.config.setConfigValue(P1_A, "X");
        f.config.setConfigValue(P1_C, "Z");
        f.press(GLFW_KEY_LEFT);
        f.type(GLFW_KEY_X, "X");
        assertEquals("aXb", f.editor.value());
        assertEquals(MenuTextEditor.Result.NONE, f.editor.consumeResult());
        f.type(GLFW_KEY_Z, "Z");
        assertEquals("aXZb", f.editor.value());
        assertEquals(MenuTextEditor.Result.NONE, f.editor.consumeResult());
        f.press(GLFW_KEY_BACKSPACE);
        f.press(GLFW_KEY_DELETE);
        assertEquals("aX", f.editor.value());
        f.press(GLFW_KEY_HOME);
        f.type(GLFW_KEY_Q, "q");
        f.press(GLFW_KEY_END);
        f.type(GLFW_KEY_W, "w");
        assertEquals("qaXw", f.editor.value());
        f.press(GLFW_KEY_ENTER);
        assertEquals(MenuTextEditor.Result.ACCEPTED, f.editor.consumeResult());
        assertEquals(MenuTextEditor.Result.NONE, f.editor.consumeResult());
    }

    @Test
    void controllerCanChooseLettersAndAcceptWithoutAnyKeyboard() {
        Fixture f = new Fixture("", 64);
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 6); // field -> 0 -> 50 switch page
        f.padPress(GLFW_GAMEPAD_BUTTON_A); // uppercase page
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_UP, 3); // 50 -> 20 = A
        f.padPress(GLFW_GAMEPAD_BUTTON_A);
        assertEquals("A", f.editor.value());
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 3);
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, 9);
        f.padPress(GLFW_GAMEPAD_BUTTON_A);
        assertEquals(MenuTextEditor.Result.ACCEPTED, f.editor.consumeResult());
    }

    @Test
    void controllerCaretCommandsInsertInTheMiddleOfExistingText() {
        Fixture f = new Fixture("ab", 64);
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 6);
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT, 5); // caret left button
        f.padPress(GLFW_GAMEPAD_BUTTON_A);
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_UP, 3); // 55 -> 25 = h
        f.padPress(GLFW_GAMEPAD_BUTTON_A);
        assertEquals("ahb", f.editor.value());
        f.padPress(GLFW_GAMEPAD_BUTTON_B);
        assertEquals(MenuTextEditor.Result.CANCELLED, f.editor.consumeResult());
    }

    @Test
    void rejectedValueStaysEditableAndLimitsDoNotSplitUnicodeCharacters() {
        Fixture f = new Fixture("", 3);
        f.type(GLFW_KEY_A, "a\uD83D\uDE00bc");
        assertEquals("a\uD83D\uDE00b", f.editor.value());
        f.press(GLFW_KEY_BACKSPACE);
        f.press(GLFW_KEY_BACKSPACE);
        assertEquals("a", f.editor.value());
        f.press(GLFW_KEY_ENTER);
        assertEquals(MenuTextEditor.Result.ACCEPTED, f.editor.consumeResult());
        f.editor.reject("Use a longer name");
        f.type(GLFW_KEY_B, "b");
        f.press(GLFW_KEY_ENTER);
        assertEquals("ab", f.editor.value());
        assertEquals(MenuTextEditor.Result.ACCEPTED, f.editor.consumeResult());
    }

    @Test
    void allPrintableAsciiCharactersAreReachableAndAllPagesFitAtNativeResolution() {
        Fixture f = new Fixture("a very long filename that should scroll when its caret reaches the edge.gen", 4096);
        RecordingFont font = new RecordingFont();
        Set<Character> available = new HashSet<>();
        available.add(' '); // The grid labels space explicitly as SPC.
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_DOWN, 6); // page button
        for (int page = 0; page < 3; page++) {
            for (int width : new int[] {320, 426}) {
                font.lines.clear();
                f.editor.render(font, width);
                for (Line line : font.lines) {
                    if (line.text.length() == 1) available.add(line.text.charAt(0));
                    assertTrue(line.x >= 0 && line.y >= 0, line.text);
                    assertTrue(line.x + font.measureWidth(line.text, line.scale) <= width, line.toString());
                    assertTrue(line.y + (line.scale < 1 ? 8 : 10) <= 224, line.toString());
                    assertTrue(line.scale == MenuStyle.COMPACT || line.scale == 1, line.toString());
                    if (line.y == 45 || line.y >= 91 && line.y <= 166 || line.y == 212) {
                        assertEquals(1f, line.scale, "Typed value, grid and controls use full-size glyphs: " + line);
                    }
                }
            }
            f.padPress(GLFW_GAMEPAD_BUTTON_A);
        }
        for (char c = 32; c <= 126; c++) assertTrue(available.contains(c), "Missing key: " + c);
    }

    @Test
    void openingAnOverlongExistingValueDoesNotTruncateIt() {
        Fixture f = new Fixture("preserve this", 4);
        assertEquals("preserve this", f.editor.value());
        f.press(GLFW_KEY_ENTER);
        assertEquals(MenuTextEditor.Result.NONE, f.editor.consumeResult());
        f.press(GLFW_KEY_ESCAPE);
        assertEquals(MenuTextEditor.Result.CANCELLED, f.editor.consumeResult());
        assertEquals("preserve this", f.editor.value());
    }

    @Test
    void keyboardCanEnterKeypadChooseAKeyAndReturnToText() {
        Fixture f = new Fixture("ab", 64);
        f.press(GLFW_KEY_DOWN); // field -> first keypad row
        f.press(GLFW_KEY_RIGHT);
        f.press(GLFW_KEY_ENTER);
        assertEquals("ab2", f.editor.value());
        assertEquals(MenuTextEditor.Result.NONE, f.editor.consumeResult());
        f.press(GLFW_KEY_UP); // first row -> field
        f.press(GLFW_KEY_LEFT);
        f.type(GLFW_KEY_X, "x");
        assertEquals("abx2", f.editor.value());
        f.press(GLFW_KEY_ENTER);
        assertEquals(MenuTextEditor.Result.ACCEPTED, f.editor.consumeResult());
    }

    @Test
    void keypadFocusSurvivesDeviceSwitchesAndTypingReturnsToField() {
        Fixture f = new Fixture("", 64);
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        f.press(GLFW_KEY_RIGHT);
        f.padPress(GLFW_GAMEPAD_BUTTON_A);
        assertEquals("2", f.editor.value());
        f.press(GLFW_KEY_LEFT);
        f.press(GLFW_KEY_ENTER);
        assertEquals("21", f.editor.value());
        f.type(GLFW_KEY_Z, "z");
        f.press(GLFW_KEY_ENTER);
        assertEquals("21z", f.editor.value());
        assertEquals(MenuTextEditor.Result.ACCEPTED, f.editor.consumeResult());
    }

    @Test
    void focusTransitionsHaveFeedbackAndHeldDownDoesNotSkipTheFirstRow() {
        Fixture f = new Fixture("", 64);
        List<MenuFeedback.Cue> cues = new ArrayList<>();
        MenuFeedback.withSink(cues::add, () -> {
            f.press(GLFW_KEY_UP); // already at the field: no action
            f.input.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
            f.frame(); f.frame(); f.frame();
            f.input.handleKeyEvent(GLFW_KEY_DOWN, GLFW_RELEASE); f.frame();
            f.press(GLFW_KEY_ENTER);
            f.press(GLFW_KEY_UP);
        });
        assertEquals("1", f.editor.value());
        assertEquals(List.of(MenuFeedback.Cue.NAVIGATE, MenuFeedback.Cue.NAVIGATE,
                MenuFeedback.Cue.NAVIGATE), cues);
        assertEquals(MenuTextEditor.Result.NONE, f.editor.consumeResult());
    }

    @Test
    void keyboardKeypadFocusIsVisibleAndDeviceChangesDoNotHideIt() {
        Fixture f = new Fixture("value", 64);
        List<Integer> focusRows = new ArrayList<>();
        var font = org.mockito.Mockito.mock(com.openggf.graphics.MenuPixelFont.class, invocation -> {
            Object[] args = invocation.getArguments();
            if (invocation.getMethod().getName().equals("fillRect")
                    && (float) args[4] == .08f && (float) args[5] == .23f) {
                focusRows.add((int) args[1]);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });
        f.editor.render(font, 320);
        assertEquals(List.of(37), focusRows, "The text field starts visibly focused");
        focusRows.clear();
        f.press(GLFW_KEY_DOWN);
        f.editor.render(font, 320);
        assertEquals(1, focusRows.size());
        assertTrue(focusRows.getFirst() > 80, "Keyboard navigation must visibly focus a keypad key");
        int keypadRow = focusRows.getFirst();
        focusRows.clear();
        f.padPress(GLFW_GAMEPAD_BUTTON_DPAD_RIGHT);
        f.editor.render(font, 320);
        assertEquals(List.of(keypadRow), focusRows, "Changing device must preserve keypad focus");
    }

    private final class Fixture {
        final SonicConfigurationService config = SonicConfigurationService.createStandalone(directory);
        final MenuTextEditor editor;
        List<GamepadStateSource.DeviceState> pads = List.of();
        final InputHandler input = new InputHandler(InputBindingFactory.supplier(config), () -> pads);
        Fixture(String initial, int limit) {
            config.setConfigValue(CONTROLLER_ENABLED, true);
            config.setConfigValue(CONTROLLER_PLAYER1, "auto");
            config.setConfigValue(CONTROLLER_PLAYER2, "none");
            editor = new MenuTextEditor("Edit text", initial, limit);
            pad();
            frame();
        }
        void press(int key) {
            input.handleKeyEvent(key, GLFW_PRESS); frame();
            input.handleKeyEvent(key, GLFW_RELEASE); frame();
        }
        void type(int physicalKey, String text) {
            input.handleKeyEvent(physicalKey, GLFW_PRESS);
            text.codePoints().forEach(c -> MenuInput.handleCharEvent(input, c));
            frame();
            input.handleKeyEvent(physicalKey, GLFW_RELEASE); frame();
        }
        void padPress(int key) { padPress(key, 1); }
        void padPress(int key, int count) {
            for (int i = 0; i < count; i++) { pad(key); frame(); pad(); frame(); }
        }
        void pad(int... buttons) {
            boolean[] state = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            for (int button : buttons) state[button] = true;
            pads = List.of(GamepadStateSource.DeviceState.connected(0, "Test pad", state, 0, 0));
        }
        void frame() { input.refreshLogicalSnapshot(); editor.update(input); input.update(); }
    }
    private record Line(String text, int x, int y, float scale) { }
    private static final class RecordingFont extends PixelFont {
        final List<Line> lines = new ArrayList<>();
        @Override public void drawText(String text, int x, int y, float scale, float r, float g, float b, float a) {
            lines.add(new Line(text, x, y, scale));
        }
    }
}
