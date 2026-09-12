package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.control.*;
import com.openggf.graphics.PixelFont;
import com.openggf.mods.EffectiveModCatalog;
import com.openggf.mods.ModCatalog;
import com.openggf.mods.ModRuntimeFindingStore;
import com.openggf.mods.ModState;
import com.openggf.mods.ModStateStore;
import com.openggf.mods.PendingModStateEditor;
import com.openggf.mods.ui.ModManagerScreen;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestModManagerScreenHost {
    @TempDir Path temp;

    @Test
    void fixedKeysAndControllerBackReachNeutralModUi() {
        InputHandler input = new InputHandler();
        input.handleKeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        input.handleKeyEvent(org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        assertTrue(ModManagerScreenHost.menuInput(input).menuAccept());
        assertTrue(ModManagerScreenHost.menuInput(input).menuRight());
        var previous = ModManagerScreenHost.menuInput(input);
        input.setLogicalOverride(LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(0, 0, InputActionMasks.ACTION_C, InputActionMasks.ACTION_C, false, false),
                PlayerInputState.neutral()));
        assertTrue(ModManagerScreenHost.menuInput(input).menuBack());
        assertTrue(previous.menuAccept(), "Retained input must preserve the earlier confirm edge");
        org.junit.jupiter.api.Assertions.assertFalse(previous.menuBack(), "Retained input must not read the new back edge");
        org.junit.jupiter.api.Assertions.assertFalse(ModManagerScreenHost.menuInput(input).menuAccept());
    }

    @Test
    void hostMapsRealInputHandlerAndPixelFontAcrossTheNeutralModUiBoundary() {
        RecordingFont font = new RecordingFont();
        ModCatalog catalog = new ModCatalog(List.of(), EffectiveModCatalog.EMPTY, Map.of());
        PendingModStateEditor editor = new PendingModStateEditor(ModState.EMPTY, catalog.scanned(),
                new ModStateStore(temp.resolve("mods")));
        ModManagerScreen screen = new ModManagerScreen(catalog, editor,
                new ModRuntimeFindingStore(), ModManagerScreenHost.textSink(font));
        ModManagerScreenHost host = new ModManagerScreenHost(screen);
        InputHandler input = new InputHandler();

        host.update(input);
        host.render();

        assertTrue(font.drawn.contains("MOD MANAGER"));
        assertTrue(font.drawn.contains("No mods discovered."));
    }

    private static final class RecordingFont extends PixelFont {
        private final List<String> drawn = new ArrayList<>();

        @Override public void beginMegaBatch() { }
        @Override public void endMegaBatch() { }
        @Override public void drawText(String text, int x, int y, float scale,
                                       float r, float g, float b, float a) {
            drawn.add(text);
        }
    }
}
