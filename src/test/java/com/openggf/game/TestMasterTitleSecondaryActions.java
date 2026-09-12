package com.openggf.game;

import com.openggf.InputBindingFactory;
import com.openggf.ModManagerScreenHost;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.graphics.PixelFont;
import com.openggf.mods.EffectiveModCatalog;
import com.openggf.mods.ModCatalog;
import com.openggf.mods.ModDependency;
import com.openggf.mods.ModDescriptor;
import com.openggf.mods.ModEligibility;
import com.openggf.mods.ModManifest;
import com.openggf.mods.ModRuntimeFindingStore;
import com.openggf.mods.ModState;
import com.openggf.mods.ModStateStore;
import com.openggf.mods.ModType;
import com.openggf.mods.PendingModStateEditor;
import com.openggf.mods.SemanticVersion;
import com.openggf.mods.VersionRange;
import com.openggf.mods.ui.ModManagerScreen;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_A;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_DPAD_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_GAMEPAD_BUTTON_LAST;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_M;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestMasterTitleSecondaryActions {

    @TempDir
    Path tempDir;

    @Test
    void masterTitleConsumesFocusTransitionAndOpensThroughHandlerExactlyOnce() {
        MasterTitleScreen screen = activeScreen(SonicConfigurationService.createStandalone(tempDir));
        AtomicInteger opened = new AtomicInteger();
        screen.setModManagerOpenHandler(opened::incrementAndGet);
        InputHandler input = new InputHandler();

        focusMods(screen, input);
        assertTrue(screen.isModsFocusedForTest());
        assertFalse(screen.isGameSelected(), "down+accept must enter the row before handling accept");
        assertEquals(0, opened.get());

        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        screen.update(input);
        input.setLogicalOverride(logical(0, InputActionMasks.ACTION_A));
        screen.update(input);
        screen.update(input);

        assertEquals(1, opened.get());
        assertFalse(screen.isGameSelected());
    }

    @Test
    void masterTitleChangesGameWhilePreservingFocusedAction() {
        MasterTitleScreen screen = activeScreen(SonicConfigurationService.createStandalone(tempDir));
        InputHandler input = new InputHandler();

        focusMods(screen, input);
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        screen.update(input);
        input.setLogicalOverride(logical(AbstractPlayableSprite.INPUT_RIGHT, 0));
        screen.update(input);

        assertEquals("s3k", screen.getSelectedGameId());
        assertTrue(screen.isModsFocusedForTest());
    }

    @Test
    void cleanRawMOpensModsButModifiedMDoesNot() {
        MasterTitleScreen screen = activeScreen(SonicConfigurationService.createStandalone(tempDir));
        AtomicInteger opened = new AtomicInteger();
        screen.setModManagerOpenHandler(opened::incrementAndGet);
        InputHandler input = new InputHandler();

        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        input.handleKeyEvent(GLFW_KEY_M, GLFW_PRESS);
        screen.update(input);
        assertEquals(0, opened.get());

        input.handleKeyEvent(GLFW_KEY_M, GLFW_RELEASE);
        input.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        input.update();
        screen.update(input);
        input.handleKeyEvent(GLFW_KEY_M, GLFW_PRESS);
        screen.update(input);
        screen.update(input);

        assertEquals(1, opened.get());
    }

    @Test
    void keyboardAndGamepadUseTheSameLogicalDownAndAcceptPath() {
        SonicConfigurationService keyboardConfig = SonicConfigurationService.createStandalone(tempDir.resolve("keyboard"));
        MasterTitleScreen keyboardScreen = activeScreen(keyboardConfig);
        AtomicInteger keyboardOpened = new AtomicInteger();
        keyboardScreen.setModManagerOpenHandler(keyboardOpened::incrementAndGet);
        InputHandler keyboard = new InputHandler(InputBindingFactory.supplier(keyboardConfig));

        TestMasterTitleHub.press(keyboardScreen, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        for (int i = 0; i < 4; i++) TestMasterTitleHub.press(keyboardScreen, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN);
        TestMasterTitleHub.press(keyboardScreen, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);

        SonicConfigurationService gamepadConfig = SonicConfigurationService.createStandalone(tempDir.resolve("gamepad"));
        gamepadConfig.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        gamepadConfig.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        gamepadConfig.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        MasterTitleScreen gamepadScreen = activeScreen(gamepadConfig);
        AtomicInteger gamepadOpened = new AtomicInteger();
        gamepadScreen.setModManagerOpenHandler(gamepadOpened::incrementAndGet);
        FakeGamepadStateSource source = new FakeGamepadStateSource();
        InputHandler gamepad = new InputHandler(InputBindingFactory.supplier(gamepadConfig), source);

        source.setButtons(GLFW_GAMEPAD_BUTTON_A);
        gamepad.refreshLogicalSnapshot();
        gamepadScreen.update(gamepad);
        for (int i = 0; i < 4; i++) {
            source.setButtons(); gamepad.refreshLogicalSnapshot(); gamepadScreen.update(gamepad);
            source.setButtons(GLFW_GAMEPAD_BUTTON_DPAD_DOWN); gamepad.refreshLogicalSnapshot(); gamepadScreen.update(gamepad);
        }
        source.setButtons(); gamepad.refreshLogicalSnapshot(); gamepadScreen.update(gamepad);
        source.setButtons(GLFW_GAMEPAD_BUTTON_A); gamepad.refreshLogicalSnapshot(); gamepadScreen.update(gamepad);

        assertEquals(1, keyboardOpened.get());
        assertEquals(1, gamepadOpened.get());
        assertTrue(keyboardScreen.isModsFocusedForTest());
        assertTrue(gamepadScreen.isModsFocusedForTest());
    }

    @Test
    void titleOwnsManagerLifecycleAndSuppressesOpeningAcceptUntilNeutral() {
        MasterTitleScreen title = activeScreen(SonicConfigurationService.createStandalone(tempDir));
        RecordingFont managerFont = new RecordingFont();
        ModManagerScreen manager = managerScreen(managerFont);
        AtomicInteger factoryCalls = new AtomicInteger();
        title.setModManagerScreenFactory(font -> {
            factoryCalls.incrementAndGet();
            return new ModManagerScreenHost(manager);
        });
        InputHandler input = new InputHandler();

        focusMods(title, input);
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        title.update(input);
        LogicalInputSnapshot accept = logical(0, InputActionMasks.ACTION_A);
        input.setLogicalOverride(accept);
        title.update(input);

        assertTrue(title.isModManagerOpenForTest());
        assertEquals(1, factoryCalls.get());
        title.update(input);
        assertFalse(manager.pendingState().entries().getFirst().enabled(),
                "the accept that opened the manager must not toggle its first row");

        title.draw();
        assertTrue(managerFont.drawn.contains("MOD MANAGER"),
                "open manager rendering must replace the title presentation");

        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        title.update(input);
        input.setLogicalOverride(logical(AbstractPlayableSprite.INPUT_RIGHT, 0));
        title.update(input);
        assertEquals("s2", title.getSelectedGameId(),
                "manager input must not leak into stock game navigation");

        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        title.update(input);
        input.setLogicalOverride(logical(0, InputActionMasks.ACTION_C));
        title.update(input);
        assertTrue(title.isModManagerOpenForTest(), "Back returns from visible actions to the mod list");
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        title.update(input);
        input.setLogicalOverride(logical(0, InputActionMasks.ACTION_C));
        title.update(input);
        assertFalse(title.isModManagerOpenForTest());
        assertFalse(title.isGameSelected());
    }

    @Test
    void managerSaveFailureRemainsOpenAndRendersBannerThroughTitleLifecycle() throws Exception {
        MasterTitleScreen title = activeScreen(SonicConfigurationService.createStandalone(tempDir));
        RecordingFont managerFont = new RecordingFont();
        Path invalidRoot = tempDir.resolve("occupied");
        Files.writeString(invalidRoot, "not a directory");
        ModManagerScreen manager = managerScreen(managerFont, invalidRoot);
        title.setModManagerScreenFactory(font -> new ModManagerScreenHost(manager));
        InputHandler input = new InputHandler();

        focusMods(title, input);
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        title.update(input);
        input.setLogicalOverride(logical(0, InputActionMasks.ACTION_A));
        title.update(input);
        input.setLogicalOverride(LogicalInputSnapshot.neutral());
        title.update(input);
        input.setLogicalOverride(logical(0, InputActionMasks.ACTION_C));
        title.update(input);

        assertTrue(title.isModManagerOpenForTest());
        title.draw();
        assertTrue(managerFont.drawn.stream().anyMatch(line -> line.startsWith("Save failed:")));
    }

    private static void focusMods(MasterTitleScreen screen, InputHandler input) {
        input.setLogicalOverride(logical(0, InputActionMasks.ACTION_A));
        screen.update(input);
        for (int i = 0; i < 4; i++) {
            input.setLogicalOverride(LogicalInputSnapshot.neutral()); screen.update(input);
            input.setLogicalOverride(logical(AbstractPlayableSprite.INPUT_DOWN, 0)); screen.update(input);
        }
        input.setLogicalOverride(LogicalInputSnapshot.neutral()); screen.update(input);
    }

    private static LogicalInputSnapshot logical(int direction, int action) {
        return LogicalInputSnapshot.ofPlayers(
                PlayerInputState.of(direction, direction, action, action, false, false),
                PlayerInputState.neutral());
    }

    private static MasterTitleScreen activeScreen(SonicConfigurationService config) {
        MasterTitleScreen screen = new MasterTitleScreen(config);
        screen.setStateForTest(MasterTitleScreen.State.ACTIVE);
        screen.setRomAvailableForTest(MasterTitleScreen.GameEntry.SONIC_2, true);
        return screen;
    }

    private ModManagerScreen managerScreen(PixelFont font) {
        return managerScreen(font, tempDir.resolve("mods"));
    }

    private ModManagerScreen managerScreen(PixelFont font, Path root) {
        ModManifest manifest = new ModManifest(1, "pack-title", "Title Pack",
                SemanticVersion.parse("1.0.0"), List.of("Alice"), "Title lifecycle fixture",
                VersionRange.parse(">=0.7.0 <0.8.0"), ModType.PATCH, "s2", null,
                List.<ModDependency>of(), Map.of(), Map.of(), null, OptionalInt.empty());
        ModDescriptor descriptor = new ModDescriptor(Path.of("pack-title.jar"), manifest,
                "a".repeat(64), false, List.of());
        ModCatalog catalog = new ModCatalog(List.of(descriptor), EffectiveModCatalog.EMPTY,
                Map.of("pack-title", new ModEligibility("pack-title", ModEligibility.Status.DISABLED,
                        List.of(new ModEligibility.Reason("DISABLED", "Disabled", List.of())))));
        ModState state = new ModState(ModState.CURRENT_FORMAT_VERSION,
                List.of(new ModState.Entry("pack-title", false, 0)));
        PendingModStateEditor editor = new PendingModStateEditor(state, catalog.scanned(),
                new ModStateStore(root.toAbsolutePath().normalize()));
        return new ModManagerScreen(catalog, editor, new ModRuntimeFindingStore(),
                ModManagerScreenHost.textSink(font));
    }

    private static final class RecordingFont extends PixelFont {
        private final List<String> drawn = new ArrayList<>();

        @Override
        public void beginMegaBatch() { }

        @Override
        public void endMegaBatch() { }

        @Override
        public void drawText(String text, int x, int y, float scale,
                             float r, float g, float b, float a) {
            drawn.add(text);
        }
    }

    private static final class FakeGamepadStateSource implements GamepadStateSource {
        private final List<DeviceState> devices = new ArrayList<>();

        void setButtons(int... pressedButtons) {
            boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
            for (int button : pressedButtons) {
                buttons[button] = true;
            }
            devices.clear();
            devices.add(DeviceState.connected(0, "pad", buttons, 0f, 0f));
        }

        @Override
        public List<DeviceState> pollDevices() {
            return List.copyOf(devices);
        }
    }
}
