package com.openggf;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.GamepadStateSource;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.MasterTitleScreen;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.glfw.GLFW.*;
import static org.mockito.Mockito.*;

/** Exercises physical controller input through the title, host routing and real fade callback. */
@Isolated
class TestMasterTitleQuitFlow {
    @TempDir Path root;
    private final boolean[] buttons = new boolean[GLFW_GAMEPAD_BUTTON_LAST + 1];
    private final AtomicInteger exits = new AtomicInteger();
    private GameLoop loop;
    private MasterTitleScreen title;
    private FadeManager fade;
    private AudioManager audio;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        SessionManager.clear();
        SonicConfigurationService config = SonicConfigurationService.createStandalone(root);
        config.setConfigValue(SonicConfiguration.CONTROLLER_ENABLED, true);
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER1, "auto");
        config.setConfigValue(SonicConfiguration.CONTROLLER_PLAYER2, "none");
        config.setConfigValue(SonicConfiguration.DISCORD_RICH_PRESENCE_ENABLED, false);
        EngineContext legacy = EngineContext.fromLegacySingletonsForBootstrap();
        fade = new FadeManager();
        GraphicsManager graphics = mock(GraphicsManager.class);
        when(graphics.getFadeManager()).thenReturn(fade);
        audio = mock(AudioManager.class);
        EngineContext context = new EngineContext(config, graphics, audio, legacy.roms(),
                legacy.profiler(), legacy.debugOverlay(), legacy.playbackDebug(),
                legacy.romDetection(), legacy.crossGameFeatures());
        GamepadStateSource pad = () -> List.of(GamepadStateSource.DeviceState.connected(
                0, "Quit flow controller", buttons, 0f, 0f));
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config), pad);
        loop = new GameLoop(context, input);
        title = new MasterTitleScreen(config);
        title.setStateForTest(MasterTitleScreen.State.ACTIVE);
        loop.setMasterTitleScreenSupplier(() -> title);
        loop.setApplicationExitHandler(exits::incrementAndGet);
        loop.setGameMode(GameMode.MASTER_TITLE_SCREEN);
        loop.step(); // Connect the neutral controller before generating physical edges.
    }

    @AfterEach
    void tearDown() {
        if (loop != null) loop.closePresence();
        TestEnvironment.resetAll();
    }

    @Test
    void controllerQuitReachesApplicationExitOnlyAfterFadeAndOnlyOnce() {
        tap(GLFW_GAMEPAD_BUTTON_B);
        tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        buttons[GLFW_GAMEPAD_BUTTON_A] = true;
        loop.step();

        assertEquals(FadeManager.FadeState.FADING_TO_BLACK, fade.getState());
        assertEquals(0, exits.get(), "Confirm starts the transition, not an immediate application exit");
        assertFalse(title.isGameSelected(), "Quit must not enter the game launch route");
        verify(audio).fadeOutMusic();
        advanceFrames(80); // Keep A held through the fade and after the exit callback.
        assertEquals(1, exits.get());
        verify(audio, times(1)).fadeOutMusic();
    }

    @Test
    void controllerConfirmationDefaultsToReturnAndBackOverridesAccept() {
        tap(GLFW_GAMEPAD_BUTTON_B);
        tap(GLFW_GAMEPAD_BUTTON_A); // Return is the default choice.
        assertFalse(fade.isActive());

        tap(GLFW_GAMEPAD_BUTTON_B);
        tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        buttons[GLFW_GAMEPAD_BUTTON_A] = true;
        buttons[GLFW_GAMEPAD_BUTTON_B] = true;
        loop.step();
        buttons[GLFW_GAMEPAD_BUTTON_A] = false;
        buttons[GLFW_GAMEPAD_BUTTON_B] = false;
        loop.step();
        advanceFrames(40);
        assertEquals(0, exits.get());
        assertFalse(fade.isActive());
        verify(audio, never()).fadeOutMusic();

        // Cancellation returns to a usable title; a fresh request can still exit.
        tap(GLFW_GAMEPAD_BUTTON_B);
        tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        tap(GLFW_GAMEPAD_BUTTON_A);
        advanceFrames(40);
        assertEquals(1, exits.get());
    }

    @Test
    void visibleQuitActionIsReachableWithControllerAndUsesConfirmation() {
        tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN); // Enter the action pane.
        for (int row = 0; row < 7; row++) tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        tap(GLFW_GAMEPAD_BUTTON_A); // Open Quit, leaving Return selected.
        assertFalse(fade.isActive());
        tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        tap(GLFW_GAMEPAD_BUTTON_A);
        advanceFrames(40);
        assertEquals(1, exits.get());
        assertFalse(title.isGameSelected());
    }

    @Test
    void confirmedQuitWaitsForExistingFadeWithoutLosingTheRequest() {
        fade.startFadeFromBlack(null);
        tap(GLFW_GAMEPAD_BUTTON_B);
        tap(GLFW_GAMEPAD_BUTTON_DPAD_DOWN);
        tap(GLFW_GAMEPAD_BUTTON_A);
        assertEquals(FadeManager.FadeState.FADING_FROM_BLACK, fade.getState());
        assertEquals(0, exits.get());
        verify(audio, never()).fadeOutMusic();

        advanceFrames(100);
        assertEquals(1, exits.get(), "The pending quit must survive until the existing fade finishes");
        verify(audio, times(1)).fadeOutMusic();
        assertFalse(title.isGameSelected());
    }

    private void tap(int button) {
        buttons[button] = true;
        loop.step();
        buttons[button] = false;
        loop.step();
    }

    private void advanceFrames(int count) {
        for (int frame = 0; frame < count; frame++) {
            loop.step();
            // The rendering owner advances fades separately from the game loop.
            fade.update();
        }
    }
}
