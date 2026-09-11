package com.openggf.graphics.color;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestDisplayColorProfileController {

    @Test
    void update_cyclesProfilePersistsRefreshesAndShowsNotification() {
        AtomicReference<DisplayColorProfile> persisted = new AtomicReference<>();
        AtomicInteger refreshCount = new AtomicInteger();
        DisplayColorProfileController controller = new DisplayColorProfileController(
                DisplayColorProfile.RAW_RGB,
                GLFW_KEY_V,
                persisted::set,
                refreshCount::incrementAndGet);
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_V, GLFW_PRESS);
        controller.update(input);

        assertEquals(DisplayColorProfile.MD_ANALOG, controller.currentProfile());
        assertEquals(DisplayColorProfile.MD_ANALOG, persisted.get());
        assertEquals(1, refreshCount.get());
        assertEquals("Color: MD Analog", controller.notificationText());
    }

    @Test
    void update_appliesProfileBeforeRefreshingPalettes() {
        AtomicReference<DisplayColorProfile> applied = new AtomicReference<>(DisplayColorProfile.RAW_RGB);
        AtomicReference<DisplayColorProfile> profileAtRefresh = new AtomicReference<>();
        DisplayColorProfileController controller = new DisplayColorProfileController(
                DisplayColorProfile.RAW_RGB,
                GLFW_KEY_V,
                profile -> {
                },
                applied::set,
                () -> profileAtRefresh.set(applied.get()));
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_V, GLFW_PRESS);
        controller.update(input);

        assertEquals(DisplayColorProfile.MD_ANALOG, applied.get());
        assertEquals(DisplayColorProfile.MD_ANALOG, profileAtRefresh.get());
    }


    @Test
    void update_hidesNotificationAfterTimeout() {
        DisplayColorProfileController controller = new DisplayColorProfileController(
                DisplayColorProfile.RAW_RGB,
                GLFW_KEY_V,
                profile -> {
                },
                () -> {
                });
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_V, GLFW_PRESS);
        controller.update(input);
        input.handleKeyEvent(GLFW_KEY_V, GLFW_RELEASE);
        input.update();

        for (int i = 0; i < DisplayColorProfileController.NOTIFICATION_FRAMES; i++) {
            controller.update(input);
        }

        assertNull(controller.notificationText());
    }

    @Test
    void update_ignoresUnboundToggleKey() {
        AtomicReference<DisplayColorProfile> persisted = new AtomicReference<>();
        AtomicInteger refreshCount = new AtomicInteger();
        DisplayColorProfileController controller = new DisplayColorProfileController(
                DisplayColorProfile.RAW_RGB,
                -1,
                persisted::set,
                refreshCount::incrementAndGet);
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_V, GLFW_PRESS);
        controller.update(input);

        assertEquals(DisplayColorProfile.RAW_RGB, controller.currentProfile());
        assertNull(persisted.get());
        assertEquals(0, refreshCount.get());
        assertNull(controller.notificationText());
    }
}
