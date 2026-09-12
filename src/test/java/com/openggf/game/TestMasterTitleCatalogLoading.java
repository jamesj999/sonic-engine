package com.openggf.game;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.launch.LaunchProfileStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;

class TestMasterTitleCatalogLoading {
    @TempDir Path directory;

    @Test
    void cancellingSlowLoadKeepsLateResultOutOfReplacementScreen() throws Exception {
        var config = SonicConfigurationService.createStandalone(directory);
        var screen = screen(config);
        var input = new InputHandler();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        List<String> accepted = new ArrayList<>();
        try {
            load(screen, () -> {
                started.countDown();
                try {
                    // Simulate a filesystem read that cannot stop immediately on interruption.
                    boolean done = false;
                    while (!done) {
                        try { release.await(); done = true; }
                        catch (InterruptedException ignored) { }
                    }
                    return "stale";
                } finally { finished.countDown(); }
            }, accepted::add);
            assertTrue(started.await(2, TimeUnit.SECONDS));
            screen.update(input);
            assertTrue(accepted.isEmpty());
            input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
            screen.update(input);
            assertNull(field(screen, "catalogLoad"));
            input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_RELEASE);
            input.update();
            screen.update(input);
            var replacement = load(screen, () -> "replacement", accepted::add);
            assertEquals("replacement", replacement.result());
            screen.update(input);
            assertEquals(List.of("replacement"), accepted);
            release.countDown();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            screen.update(input);
            assertEquals(List.of("replacement"), accepted);
        } finally {
            release.countDown();
            screen.cleanup();
        }
    }

    @Test
    void failedAutomaticTraceLoadCanBeAcknowledgedWithoutImmediateRetry() throws Exception {
        var config = SonicConfigurationService.createStandalone(directory);
        config.setConfigValue(SonicConfiguration.TEST_MODE_ENABLED, true);
        var screen = screen(config);
        var input = new InputHandler();
        try {
            var failed = load(screen, () -> { throw new java.io.IOException("Unreadable catalog"); },
                    value -> fail("A failed read must not publish a result"));
            assertThrows(ExecutionException.class, failed::result);
            screen.update(input);
            assertEquals(MasterTitleScreen.State.ERROR_DISPLAY, field(screen, "state"));
            assertFalse(config.getBoolean(SonicConfiguration.TEST_MODE_ENABLED));
            input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_PRESS);
            screen.update(input);
            input.handleKeyEvent(GLFW_KEY_ESCAPE, GLFW_RELEASE);
            input.update();
            screen.update(input);
            assertEquals(MasterTitleScreen.State.ACTIVE, field(screen, "state"));
            assertNull(field(screen, "catalogLoad"));
        } finally { screen.cleanup(); }
    }

    private MasterTitleScreen screen(SonicConfigurationService config) {
        var screen = new MasterTitleScreen(config, new LaunchProfileStore(config));
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        return screen;
    }

    private static <T> MenuLoadTask<?> load(MasterTitleScreen screen, Callable<T> read,
                                           Consumer<T> loaded) throws Exception {
        Method method = MasterTitleScreen.class.getDeclaredMethod("loadCatalog", String.class,
                Callable.class, Consumer.class);
        method.setAccessible(true);
        method.invoke(screen, "TEST CATALOG", read, loaded);
        return (MenuLoadTask<?>) field(screen, "catalogLoad");
    }

    private static Object field(MasterTitleScreen screen, String name) throws Exception {
        Field field = MasterTitleScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(screen);
    }
}
