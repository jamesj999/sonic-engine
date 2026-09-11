package com.openggf.graphics.shaderlib;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_1;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_MINUS;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_PERIOD;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_R;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SLASH;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_UP;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestDisplayShaderPickerController {
    private static final int PICKER_KEY = GLFW_KEY_BACKSLASH;

    @Test
    void openingShowsOffAndAllEntriesWithCurrentSelectionHighlighted() {
        DisplayShaderPresetRef current = preset("crt/crt-easymode.glslp");
        DisplayShaderPickerController picker = pickerWith(current, preset("scanlines/zebra.glslp"));

        press(picker, PICKER_KEY, current);

        assertTrue(picker.isOpen());
        assertEquals("", picker.query());
        assertEquals(List.of("..", "crt-easymode.glslp"), displayPaths(picker));
        assertSame(current, picker.selectedItem().ref());
    }

    @Test
    void typedQueryFiltersByPathAndCategoryWhileKeepingOffVisible() {
        DisplayShaderPickerController picker = pickerWith(
                preset("libretro-glsl/crt/crt-easymode.glslp"),
                preset("scanlines/zebra.glslp"));

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_C, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_R, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_T, DisplayShaderPresetRef.OFF);

        assertEquals("crt", picker.query());
        assertEquals(List.of("Off"), displayPaths(picker));
    }

    @Test
    void typingSupportsSimpleFilenameCharactersAndBackspace() {
        DisplayShaderPickerController picker = pickerWith(preset("crt/a-1.test.glslp"));

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_A, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_MINUS, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_1, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_PERIOD, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_SLASH, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_SPACE, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_BACKSPACE, DisplayShaderPresetRef.OFF);

        assertEquals("a-1./", picker.query());
    }

    @Test
    void shiftMinusTypesUnderscore() {
        DisplayShaderPickerController picker = pickerWith(preset("crt/a_test.glslp"));
        InputHandler input = new InputHandler();

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_MINUS, GLFW_PRESS);
        picker.update(input, DisplayShaderPresetRef.OFF);

        assertEquals("_", picker.query());
        input.handleKeyEvent(GLFW_KEY_MINUS, GLFW_RELEASE);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
    }

    @Test
    void arrowsWrapWithinVisibleItems() {
        DisplayShaderPresetRef first = preset("a.glsl");
        DisplayShaderPresetRef second = preset("b.glsl");
        DisplayShaderPickerController picker = pickerWith(first, second);

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_UP, DisplayShaderPresetRef.OFF);
        assertSame(second, picker.selectedItem().ref());

        press(picker, GLFW_KEY_DOWN, DisplayShaderPresetRef.OFF);
        assertSame(DisplayShaderPresetRef.OFF, picker.selectedItem().ref());
    }

    @Test
    void enterReturnsSelectedRefAndCloses() {
        DisplayShaderPresetRef first = preset("a.glsl");
        DisplayShaderPresetRef second = preset("b.glsl");
        DisplayShaderPickerController picker = pickerWith(first, second);

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_DOWN, DisplayShaderPresetRef.OFF);
        DisplayShaderPickerController.Action action = press(picker, GLFW_KEY_ENTER, DisplayShaderPresetRef.OFF);

        assertEquals(DisplayShaderPickerController.ActionType.ACTIVATE, action.type());
        assertSame(first, action.ref());
        assertFalse(picker.isOpen());
    }

    @Test
    void escapeClosesWithoutSelection() {
        DisplayShaderPickerController picker = pickerWith(preset("a.glsl"));

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        DisplayShaderPickerController.Action action = press(picker, GLFW_KEY_ESCAPE, DisplayShaderPresetRef.OFF);

        assertEquals(DisplayShaderPickerController.ActionType.NONE, action.type());
        assertNull(action.ref());
        assertFalse(picker.isOpen());
    }

    @Test
    void f5RequestsLibretroDownloadWithoutClosingPicker() {
        DisplayShaderPickerController picker = pickerWith(preset("a.glsl"));

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        DisplayShaderPickerController.Action action = press(picker, GLFW_KEY_F5, DisplayShaderPresetRef.OFF);

        assertEquals(DisplayShaderPickerController.ActionType.DOWNLOAD_LIBRETRO_GLSL, action.type());
        assertNull(action.ref());
        assertTrue(picker.isOpen());
    }

    @Test
    void enterOnFolderNavigatesWithoutClosingPicker() {
        DisplayShaderPresetRef crt = preset("libretro-glsl/crt/crt-easymode.glslp");
        DisplayShaderPickerController picker = pickerWith(crt, preset("libretro-glsl/scanlines/zebra.glslp"));

        press(picker, PICKER_KEY, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_DOWN, DisplayShaderPresetRef.OFF);
        DisplayShaderPickerController.Action firstEnter = press(picker, GLFW_KEY_ENTER, DisplayShaderPresetRef.OFF);
        press(picker, GLFW_KEY_DOWN, DisplayShaderPresetRef.OFF);
        DisplayShaderPickerController.Action secondEnter = press(picker, GLFW_KEY_ENTER, DisplayShaderPresetRef.OFF);

        assertEquals(DisplayShaderPickerController.ActionType.NONE, firstEnter.type());
        assertEquals(DisplayShaderPickerController.ActionType.NONE, secondEnter.type());
        assertTrue(picker.isOpen());
        assertEquals("libretro-glsl/crt", picker.currentFolder());
        assertEquals(List.of("..", "crt-easymode.glslp"), displayPaths(picker));
    }

    @Test
    void enterOnParentFolderNavigatesUp() {
        DisplayShaderPickerController picker = pickerWith(preset("libretro-glsl/crt/crt-easymode.glslp"));

        press(picker, PICKER_KEY, preset("libretro-glsl/crt/crt-easymode.glslp"));
        press(picker, GLFW_KEY_UP, DisplayShaderPresetRef.OFF);
        DisplayShaderPickerController.Action action = press(picker, GLFW_KEY_ENTER, DisplayShaderPresetRef.OFF);

        assertEquals(DisplayShaderPickerController.ActionType.NONE, action.type());
        assertEquals("libretro-glsl", picker.currentFolder());
        assertEquals(List.of("..", "crt/"), displayPaths(picker));
    }

    @Test
    void backspaceWithEmptyQueryNavigatesUpFromFolder() {
        DisplayShaderPickerController picker = pickerWith(preset("libretro-glsl/crt/crt-easymode.glslp"));

        press(picker, PICKER_KEY, preset("libretro-glsl/crt/crt-easymode.glslp"));
        DisplayShaderPickerController.Action action = press(picker, GLFW_KEY_BACKSPACE, DisplayShaderPresetRef.OFF);

        assertEquals(DisplayShaderPickerController.ActionType.NONE, action.type());
        assertTrue(picker.isOpen());
        assertEquals("", picker.query());
        assertEquals("libretro-glsl", picker.currentFolder());
        assertEquals(List.of("..", "crt/"), displayPaths(picker));
    }

    @Test
    void exposesLibretroDownloadHintForPickerRenderer() {
        assertEquals(
                "Press F5 to download libretro shader pack",
                DisplayShaderPickerController.downloadHintText());
    }

    private static DisplayShaderPickerController pickerWith(DisplayShaderPresetRef... refs) {
        List<DisplayShaderPresetRef> entries = new java.util.ArrayList<>();
        entries.add(DisplayShaderPresetRef.OFF);
        entries.addAll(List.of(refs));
        return new DisplayShaderPickerController(new DisplayShaderSelectionModel(entries), PICKER_KEY);
    }

    private static DisplayShaderPresetRef preset(String relativePath) {
        return new DisplayShaderPresetRef(
                DisplayShaderPresetRef.Kind.GLSLP,
                relativePath,
                Path.of("display-shaders").resolve(relativePath));
    }

    private static DisplayShaderPickerController.Action press(
            DisplayShaderPickerController picker,
            int key,
            DisplayShaderPresetRef currentRef) {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        return picker.update(input, currentRef);
    }

    private static List<String> displayPaths(DisplayShaderPickerController picker) {
        return picker.visibleItems().stream()
                .map(DisplayShaderSelectionModel.SelectionItem::displayPath)
                .toList();
    }
}
