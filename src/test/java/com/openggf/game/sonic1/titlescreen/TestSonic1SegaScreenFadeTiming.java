package com.openggf.game.sonic1.titlescreen;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.TitleScreenProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestSonic1SegaScreenFadeTiming {
    private static final int SEGA_ACTIVE_FRAMES = 76 + 98 + 30;
    private static final int FADE_FRAMES = 22;

    @Test
    void segaLogoTimerWaitsUntilFadeFromBlackCompletes() throws Exception {
        Sonic1TitleScreenManager manager = managerInSegaState();
        InputHandler input = new InputHandler();

        for (int i = 0; i < FADE_FRAMES - 1; i++) {
            manager.update(input);
            assertEquals(0, intField(manager, "segaLogoTimer"),
                    "S1 SEGA animation must not advance during fade-from-black");
            assertEquals(TitleScreenProvider.State.SEGA_LOGO, manager.getState());
        }

        manager.update(input);

        assertEquals(1, intField(manager, "segaLogoTimer"),
                "S1 SEGA animation starts on the first frame after fade-from-black");
    }

    @Test
    void segaLogoFadesToBlackBeforeIntroText() throws Exception {
        Sonic1TitleScreenManager manager = managerInSegaState();
        setIntField(manager, "segaLogoTimer", SEGA_ACTIVE_FRAMES - 1);
        setEnumField(manager, "segaLogoFadePhase", "ACTIVE");
        InputHandler input = new InputHandler();

        manager.update(input);

        assertEquals(TitleScreenProvider.State.SEGA_LOGO, manager.getState(),
                "S1 must hold the SEGA screen while it fades to black");
        assertEquals(SEGA_ACTIVE_FRAMES, intField(manager, "segaLogoTimer"));

        for (int i = 0; i < FADE_FRAMES - 1; i++) {
            manager.update(input);
            assertEquals(TitleScreenProvider.State.SEGA_LOGO, manager.getState());
        }

        manager.update(input);

        assertEquals(TitleScreenProvider.State.INTRO_TEXT_FADE_IN, manager.getState(),
                "S1 intro text starts only after the SEGA fade-to-black finishes");
    }

    @Test
    void segaLogoFadeDoesNotUseBlackOverlayHelpers() {
        assertThrows(NoSuchMethodException.class,
                () -> method("drawSegaLogoFade", com.openggf.graphics.GraphicsManager.class));
        assertThrows(NoSuchMethodException.class,
                () -> method("segaLogoFadeAmount"));
    }

    private static Sonic1TitleScreenManager managerInSegaState() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.JUMP, "SPACE");
        Sonic1TitleScreenManager manager = new Sonic1TitleScreenManager(config);
        setField(manager, "state", TitleScreenProvider.State.SEGA_LOGO);
        setIntField(manager, "segaLogoTimer", 0);
        setBooleanField(manager, "segaPcmStarted", false);
        return manager;
    }

    private static int intField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBooleanField(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Method method(String name, Class<?>... parameterTypes) throws NoSuchMethodException {
        return Sonic1TitleScreenManager.class.getDeclaredMethod(name, parameterTypes);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setEnumField(Object target, String name, String value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, Enum.valueOf((Class<Enum>) field.getType(), value));
    }
}
