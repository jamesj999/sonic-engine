package com.openggf.graphics.shaderlib;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT_BRACKET;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestDisplayShaderController {

    private static final int NEXT_KEY = GLFW_KEY_RIGHT_BRACKET;
    private static final int PREVIOUS_KEY = GLFW_KEY_LEFT_BRACKET;

    @TempDir
    Path tempDir;

    @Test
    void nextAdvancesPersistsAndNotifies() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<DisplayShaderPresetRef> activated = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> {
                    activated.add(ref);
                    return true;
                });

        press(controller, NEXT_KEY);

        assertEquals("a.glsl", controller.currentRef().relativePath());
        assertEquals("a.glsl", persisted.get());
        assertEquals(List.of(library.at(1)), activated);
        assertEquals("Shader: a", controller.notificationText());
    }

    @Test
    void notificationClearsAfterTimeout() throws IOException {
        DisplayShaderController controller = new DisplayShaderController(
                libraryWithShaders(),
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                selection -> {
                },
                ref -> true);

        press(controller, NEXT_KEY);
        assertEquals("Shader: a", controller.notificationText());

        InputHandler input = new InputHandler();
        for (int i = 0; i < DisplayShaderController.NOTIFICATION_FRAMES; i++) {
            controller.update(input);
            input.update();
        }

        assertNull(controller.notificationText());
    }

    @Test
    void heldNextKeyDoesNotCycleRepeatedlyWithoutNewEdge() throws IOException {
        DisplayShaderController controller = new DisplayShaderController(
                libraryWithShaders(),
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                selection -> {
                },
                ref -> true);
        InputHandler input = new InputHandler();

        input.handleKeyEvent(NEXT_KEY, GLFW_PRESS);
        controller.update(input);
        input.update();
        controller.update(input);

        assertEquals("a.glsl", controller.currentRef().relativePath());
    }

    @Test
    void previousFromOffWrapsToLastEntry() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> true);

        press(controller, PREVIOUS_KEY);

        assertEquals("b.glsl", controller.currentRef().relativePath());
        assertEquals("b.glsl", persisted.get());
        assertEquals("Shader: b", controller.notificationText());
    }

    @Test
    void failedShaderIsSkippedOnNextCycleAndReportsFailure() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<String> attempts = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> {
                    attempts.add(ref.label());
                    return ref.kind() == DisplayShaderPresetRef.Kind.OFF || !"a.glsl".equals(ref.relativePath());
                });

        press(controller, NEXT_KEY);

        assertEquals(DisplayShaderPresetRef.OFF, controller.currentRef());
        assertNull(persisted.get());
        assertEquals(List.of("a"), attempts);
        assertEquals("Shader failed: a", controller.notificationText());

        press(controller, NEXT_KEY);

        assertEquals("b.glsl", controller.currentRef().relativePath());
        assertEquals("b.glsl", persisted.get());
        assertEquals(List.of("a", "b"), attempts);
        assertEquals("Shader: b", controller.notificationText());
    }

    @Test
    void unboundKeysAreIgnored() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<DisplayShaderPresetRef> activated = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                -1,
                -1,
                persisted::set,
                ref -> {
                    activated.add(ref);
                    return true;
                });

        press(controller, GLFW_KEY_V);

        assertEquals(DisplayShaderPresetRef.OFF, controller.currentRef());
        assertNull(persisted.get());
        assertEquals(List.of(), activated);
        assertNull(controller.notificationText());
    }

    @Test
    void savedSelectionResolvesToEntry() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "./b.glsl",
                NEXT_KEY,
                PREVIOUS_KEY,
                selection -> {
                },
                ref -> true);

        assertEquals("b.glsl", controller.currentRef().relativePath());
        assertNull(controller.notificationText());
    }

    @Test
    void silentBootActivatesWithoutPersistingOrNotifying() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<DisplayShaderPresetRef> activated = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "b.glsl",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> {
                    activated.add(ref);
                    return true;
                });

        controller.applySavedSelectionSilently();

        assertEquals("b.glsl", controller.currentRef().relativePath());
        assertNull(persisted.get());
        assertEquals(List.of(library.at(2)), activated);
        assertNull(controller.notificationText());
    }

    @Test
    void silentBootFallsBackToOffWhenSavedShaderFails() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<DisplayShaderPresetRef> activated = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "b.glsl",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> {
                    activated.add(ref);
                    return ref.kind() == DisplayShaderPresetRef.Kind.OFF;
                });

        controller.applySavedSelectionSilently();

        assertEquals(DisplayShaderPresetRef.OFF, controller.currentRef());
        assertNull(persisted.get());
        assertEquals(List.of(library.at(2), DisplayShaderPresetRef.OFF), activated);
        assertNull(controller.notificationText());
    }

    @Test
    void allFailedRealShadersStillAllowCyclingToOff() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<String> attempts = new ArrayList<>();
        Predicate<DisplayShaderPresetRef> failRealShaders = ref -> {
            attempts.add(ref.label());
            return ref.kind() == DisplayShaderPresetRef.Kind.OFF;
        };
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                failRealShaders);

        press(controller, NEXT_KEY);
        press(controller, NEXT_KEY);
        press(controller, NEXT_KEY);

        assertEquals(DisplayShaderPresetRef.OFF, controller.currentRef());
        assertEquals("OFF", persisted.get());
        assertEquals(List.of("a", "b", "Off"), attempts);
        assertEquals("Shader: Off", controller.notificationText());
    }

    @Test
    void explicitSelectionPersistsAndNotifies() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<DisplayShaderPresetRef> activated = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> {
                    activated.add(ref);
                    return true;
                });

        boolean selected = controller.select(library.at(2));

        assertEquals(true, selected);
        assertEquals("b.glsl", controller.currentRef().relativePath());
        assertEquals("b.glsl", persisted.get());
        assertEquals(List.of(library.at(2)), activated);
        assertEquals("Shader: b", controller.notificationText());
    }

    @Test
    void nestedSelectionNotificationUsesBasenameOnly() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("libretro-glsl/crt/crt-easymode.glslp"));
        DisplayShaderLibrary library = DisplayShaderLibrary.scan(root);
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                selection -> {
                },
                ref -> true);

        controller.select(library.at(1));

        assertEquals("Shader: crt-easymode", controller.notificationText());
    }

    @Test
    void explicitSelectionFailureDoesNotPersistOrChangeCurrentRef() throws IOException {
        DisplayShaderLibrary library = libraryWithShaders();
        AtomicReference<String> persisted = new AtomicReference<>();
        List<DisplayShaderPresetRef> activated = new ArrayList<>();
        DisplayShaderController controller = new DisplayShaderController(
                library,
                "OFF",
                NEXT_KEY,
                PREVIOUS_KEY,
                persisted::set,
                ref -> {
                    activated.add(ref);
                    return false;
                });

        boolean selected = controller.select(library.at(1));

        assertEquals(false, selected);
        assertEquals(DisplayShaderPresetRef.OFF, controller.currentRef());
        assertNull(persisted.get());
        assertEquals(List.of(library.at(1)), activated);
        assertEquals("Shader failed: a", controller.notificationText());
    }

    private DisplayShaderLibrary libraryWithShaders() throws IOException {
        Path root = tempDir.resolve("display-shaders");
        write(root.resolve("a.glsl"));
        write(root.resolve("b.glsl"));
        return DisplayShaderLibrary.scan(root);
    }

    private static void press(DisplayShaderController controller, int key) {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(key, GLFW_PRESS);
        controller.update(input);
    }

    private static void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "void main() {}\n");
    }
}
