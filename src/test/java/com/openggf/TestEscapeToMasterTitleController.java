package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestEscapeToMasterTitleController {

    @Test
    void promptStaysVisibleForAtLeastTwoSecondsAfterEscapePress() {
        InputHandler input = new InputHandler();
        EscapeToMasterTitleController controller = new EscapeToMasterTitleController(() -> false, () -> {});

        assertEquals(120, EscapeToMasterTitleController.HOLD_FRAMES);
        assertEquals(120, EscapeToMasterTitleController.PROMPT_MINIMUM_FRAMES);

        press(input, GLFW_KEY_ESCAPE);
        controller.update(GameMode.LEVEL, input);
        assertTrue(controller.visible());
        assertEquals("hold ESC 2sec to return to title", controller.message());

        release(input, GLFW_KEY_ESCAPE);
        for (int frame = 0; frame < EscapeToMasterTitleController.PROMPT_MINIMUM_FRAMES - 1; frame++) {
            controller.update(GameMode.LEVEL, input);
            assertTrue(controller.visible(), "prompt should remain visible through frame " + frame);
        }

        controller.update(GameMode.LEVEL, input);
        assertFalse(controller.visible());
    }

    @Test
    void holdProgressResetsWhenEscapeIsReleased() {
        InputHandler input = new InputHandler();
        EscapeToMasterTitleController controller = new EscapeToMasterTitleController(() -> false, () -> {});

        press(input, GLFW_KEY_ESCAPE);
        for (int frame = 0; frame < 40; frame++) {
            controller.update(GameMode.LEVEL, input);
            input.update();
        }
        assertEquals(40 / (double) EscapeToMasterTitleController.HOLD_FRAMES,
                controller.progress(), 0.0001);

        release(input, GLFW_KEY_ESCAPE);
        controller.update(GameMode.LEVEL, input);

        assertEquals(0.0, controller.progress(), 0.0001);
        assertTrue(controller.visible(), "prompt remains visible even though progress resets");
    }

    @Test
    void holdingEscapeForTwoSecondsTriggersTransitionOnce() {
        InputHandler input = new InputHandler();
        AtomicInteger transitions = new AtomicInteger();
        EscapeToMasterTitleController controller =
                new EscapeToMasterTitleController(() -> false, transitions::incrementAndGet);

        press(input, GLFW_KEY_ESCAPE);
        for (int frame = 0; frame < EscapeToMasterTitleController.HOLD_FRAMES - 1; frame++) {
            controller.update(GameMode.LEVEL, input);
            input.update();
        }
        assertEquals(0, transitions.get());
        assertEquals((EscapeToMasterTitleController.HOLD_FRAMES - 1)
                        / (double) EscapeToMasterTitleController.HOLD_FRAMES,
                controller.progress(), 0.0001);

        controller.update(GameMode.LEVEL, input);
        assertEquals(1, transitions.get());
        assertEquals(1.0, controller.progress(), 0.0001);

        for (int frame = 0; frame < 20; frame++) {
            controller.update(GameMode.LEVEL, input);
            input.update();
        }
        assertEquals(1, transitions.get());
    }

    @Test
    void masterTitleModeShowsExitPromptAndTriggersExitAction() {
        InputHandler input = new InputHandler();
        AtomicInteger returnTransitions = new AtomicInteger();
        AtomicInteger exitTransitions = new AtomicInteger();
        EscapeToMasterTitleController controller =
                new EscapeToMasterTitleController(() -> false,
                        returnTransitions::incrementAndGet, exitTransitions::incrementAndGet);

        press(input, GLFW_KEY_ESCAPE);
        controller.update(GameMode.MASTER_TITLE_SCREEN, input);

        assertTrue(controller.visible());
        assertEquals("hold ESC 2sec to exit OpenGGF", controller.message());

        input.update();
        for (int frame = 1; frame < EscapeToMasterTitleController.HOLD_FRAMES; frame++) {
            controller.update(GameMode.MASTER_TITLE_SCREEN, input);
            input.update();
        }

        assertEquals(0, returnTransitions.get());
        assertEquals(1, exitTransitions.get());
    }

    @Test
    void modeChangeClearsHeldProgressBeforeMasterTitleCanStartExitHold() {
        InputHandler input = new InputHandler();
        AtomicInteger returnTransitions = new AtomicInteger();
        AtomicInteger exitTransitions = new AtomicInteger();
        EscapeToMasterTitleController controller =
                new EscapeToMasterTitleController(() -> false,
                        returnTransitions::incrementAndGet, exitTransitions::incrementAndGet);

        press(input, GLFW_KEY_ESCAPE);
        for (int frame = 0; frame < EscapeToMasterTitleController.HOLD_FRAMES - 1; frame++) {
            controller.update(GameMode.LEVEL, input);
            input.update();
        }
        assertEquals((EscapeToMasterTitleController.HOLD_FRAMES - 1)
                        / (double) EscapeToMasterTitleController.HOLD_FRAMES,
                controller.progress(), 0.0001);

        controller.update(GameMode.MASTER_TITLE_SCREEN, input);

        assertEquals(0, returnTransitions.get());
        assertEquals(0, exitTransitions.get());
        assertEquals(1 / (double) EscapeToMasterTitleController.HOLD_FRAMES,
                controller.progress(), 0.0001);
        assertEquals(EscapeToMasterTitleController.EXIT_MESSAGE, controller.message());
    }

    @Test
    void titleCardModePreservesReturnToTitleProgress() {
        InputHandler input = new InputHandler();
        EscapeToMasterTitleController controller = new EscapeToMasterTitleController(() -> false, () -> {});

        press(input, GLFW_KEY_ESCAPE);
        for (int frame = 0; frame < 40; frame++) {
            controller.update(GameMode.LEVEL, input);
            input.update();
        }

        controller.update(GameMode.TITLE_CARD, input);

        assertTrue(controller.visible());
        assertEquals(41 / (double) EscapeToMasterTitleController.HOLD_FRAMES,
                controller.progress(), 0.0001);
        assertEquals(EscapeToMasterTitleController.RETURN_MESSAGE, controller.message());
    }

    @Test
    void ineligibleModesClearPromptAndProgress() {
        InputHandler input = new InputHandler();
        EscapeToMasterTitleController controller = new EscapeToMasterTitleController(() -> false, () -> {});

        press(input, GLFW_KEY_ESCAPE);
        controller.update(GameMode.LEVEL, input);
        assertTrue(controller.visible());

        controller.update(GameMode.EDITOR, input);

        assertFalse(controller.visible());
        assertEquals(0.0, controller.progress(), 0.0001);
    }

    @Test
    void activeFadeBlocksTransitionStart() {
        InputHandler input = new InputHandler();
        AtomicInteger transitions = new AtomicInteger();
        EscapeToMasterTitleController controller =
                new EscapeToMasterTitleController(() -> true, transitions::incrementAndGet);

        press(input, GLFW_KEY_ESCAPE);
        for (int frame = 0; frame < EscapeToMasterTitleController.HOLD_FRAMES + 10; frame++) {
            controller.update(GameMode.LEVEL, input);
            input.update();
        }

        assertEquals(0, transitions.get());
        assertEquals(1.0, controller.progress(), 0.0001);
    }

    private static void press(InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_PRESS);
    }

    private static void release(InputHandler input, int key) {
        input.handleKeyEvent(key, GLFW_RELEASE);
    }
}
