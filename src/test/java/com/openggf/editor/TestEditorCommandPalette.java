package com.openggf.editor;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.game.MenuFeedback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestEditorCommandPalette {
    @Test
    void controllerCanOpenNavigateAndExecuteCommandsWithoutEditingWorld() {
        var config = SonicConfigurationService.createStandalone();
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), () -> List.of(
                GamepadStateSource.DeviceState.connected(0, "DualSense", buttons, 0, 0)));
        EditorCommandPalette palette = new EditorCommandPalette();
        List<EditorInputHandler.Action> actions = new ArrayList<>();
        input.refreshLogicalSnapshot();
        input.update();
        buttons[GLFW_GAMEPAD_BUTTON_Y] = true;
        input.refreshLogicalSnapshot();
        assertTrue(palette.update(input, actions::add));
        assertTrue(palette.isOpen());
        assertTrue(actions.isEmpty());
        input.update();
        buttons[GLFW_GAMEPAD_BUTTON_Y] = false;
        buttons[GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = true;
        input.refreshLogicalSnapshot();
        palette.update(input, actions::add);
        input.update();
        buttons[GLFW_GAMEPAD_BUTTON_DPAD_DOWN] = false;
        buttons[GLFW_GAMEPAD_BUTTON_A] = true;
        input.refreshLogicalSnapshot();
        palette.update(input, actions::add);
        assertEquals(List.of(EditorInputHandler.Action.ASCEND), actions);
        assertFalse(palette.isOpen());
    }

    @Test
    void modalSuppressesSaveShortcutAndBackDoesNotDispatch() {
        var controller = new LevelEditorController();
        AtomicInteger saves = new AtomicInteger();
        var handler = new EditorInputHandler(controller, () -> null, () -> null, saves::incrementAndGet);
        var input = new InputHandler(InputBindingFactory.supplier(SonicConfigurationService.createStandalone()));
        input.handleKeyEvent(GLFW_KEY_F1, GLFW_PRESS);
        handler.update(input);
        input.update();
        input.handleKeyEvent(GLFW_KEY_F1, GLFW_RELEASE);
        input.handleKeyEvent(GLFW_KEY_LEFT_CONTROL, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_S, GLFW_PRESS);
        handler.update(input);
        assertEquals(0, saves.get());
        input.update();
        input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        handler.update(input);
        assertFalse(EditorCommandPalette.forController(controller).isOpen());
        assertEquals(0, saves.get());
    }
    @Test
    void paletteFeedbackTracksActionsAndBackWinsOverDetails() {
        var input = new InputHandler(InputBindingFactory.supplier(SonicConfigurationService.createStandalone()));
        var palette = new EditorCommandPalette();
        List<MenuFeedback.Cue> cues = new ArrayList<>();
        List<EditorInputHandler.Action> actions = new ArrayList<>();
        palette.setFeedback(cues::add);
        paletteFrame(palette, input, actions, GLFW_KEY_F1);
        paletteFrame(palette, input, actions, GLFW_KEY_UP, GLFW_KEY_DOWN);
        assertEquals(List.of(MenuFeedback.Cue.CONFIRM), cues, "No cue when opposing directions leave selection unchanged");
        paletteFrame(palette, input, actions, GLFW_KEY_DOWN);
        paletteFrame(palette, input, actions, GLFW_KEY_ENTER);
        assertEquals(List.of(EditorInputHandler.Action.ASCEND), actions);
        paletteFrame(palette, input, actions, GLFW_KEY_F1);
        paletteFrame(palette, input, actions, GLFW_KEY_ESCAPE, GLFW_KEY_F1);
        assertFalse(palette.isOpen());
        assertEquals(List.of(MenuFeedback.Cue.CONFIRM, MenuFeedback.Cue.NAVIGATE,
                MenuFeedback.Cue.CONFIRM, MenuFeedback.Cue.CONFIRM, MenuFeedback.Cue.CANCEL), cues);
        input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_F1, GLFW_PRESS);
        input.refreshLogicalSnapshot();
        assertFalse(palette.update(input, actions::add), "Back on a closed palette remains the editor's action");
        assertFalse(palette.isOpen());
        assertEquals(5, cues.size());
    }

    private static void paletteFrame(EditorCommandPalette palette, InputHandler input,
                                     List<EditorInputHandler.Action> actions, int... keys) {
        for (int key : keys) input.handleKeyEvent(key, GLFW_PRESS);
        input.refreshLogicalSnapshot();
        palette.update(input, actions::add);
        input.update();
        for (int key : keys) input.handleKeyEvent(key, GLFW_RELEASE);
        input.refreshLogicalSnapshot();
        palette.update(input, actions::add);
        input.update();
    }

}
