package com.openggf.game.sonic1.titlescreen;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.game.TitleScreenProvider;
import com.openggf.game.titlescreen.SegaPaletteFade;
import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GM_Title fades the assembled title screen in with PaletteFadeIn: 22 VBlank
 * periods of FadeIn_AddColor (blue, then green, then red) with ExecuteObjects,
 * DeformLayers and PalCycle_Title frozen until Tit_MainLoop. The "SONIC TEAM
 * PRESENTS" screen uses the same PaletteFadeIn / PaletteFadeOut pair.
 */
class TestSonic1TitleScreenPaletteFade {
    private static final int FADE_FRAMES = SegaPaletteFade.ROM_FADE_FRAMES;

    @Test
    void titleFadeInLastsTwentyTwoFramesAndFreezesTheMainScreen() throws Exception {
        Sonic1TitleScreenManager manager = managerInState(TitleScreenProvider.State.FADE_IN);
        setIntField(manager, "sonicRoutine", 2);
        setIntField(manager, "sonicDelayTimer", 28);
        InputHandler input = new InputHandler();

        for (int frame = 1; frame < FADE_FRAMES; frame++) {
            manager.update(input);
            assertEquals(TitleScreenProvider.State.FADE_IN, manager.getState(),
                    "frame " + frame + " must still be inside PaletteFadeIn");
            assertEquals(frame - 1, invokeInt(manager, "paletteFadeSteps"),
                    "frame " + frame + " shows the steps applied before its VBlank");
            assertEquals(SegaPaletteFade.Mode.FROM_BLACK, invoke(manager, "paletteFadeMode"));
            assertEquals(0, intField(manager, "bgCameraX"), "no scroll during the fade loop");
            assertEquals(28, intField(manager, "sonicDelayTimer"), "TSon_Delay does not tick during the fade loop");
        }

        manager.update(input);

        assertEquals(TitleScreenProvider.State.ACTIVE, manager.getState(),
                "Tit_MainLoop starts after the 22nd fade frame");
        assertEquals(SegaPaletteFade.Mode.NONE, invoke(manager, "paletteFadeMode"));
        assertEquals(0, intField(manager, "bgCameraX"));
    }

    @Test
    void introTextFadesThroughThePaletteInBothDirections() throws Exception {
        Sonic1TitleScreenManager manager = managerInState(TitleScreenProvider.State.INTRO_TEXT_FADE_IN);
        InputHandler input = new InputHandler();

        manager.update(input);
        assertEquals(SegaPaletteFade.Mode.FROM_BLACK, invoke(manager, "paletteFadeMode"));
        assertEquals(0, invokeInt(manager, "paletteFadeSteps"), "first fade-in frame is black");

        setField(manager, "state", TitleScreenProvider.State.INTRO_TEXT_FADE_OUT);
        setIntField(manager, "introTextTimer", 0);
        manager.update(input);
        assertEquals(SegaPaletteFade.Mode.TO_BLACK, invoke(manager, "paletteFadeMode"));
        assertEquals(0, invokeInt(manager, "paletteFadeSteps"), "first fade-out frame still shows the full palette");

        setField(manager, "state", TitleScreenProvider.State.INTRO_TEXT_HOLD);
        assertEquals(SegaPaletteFade.Mode.NONE, invoke(manager, "paletteFadeMode"));
    }

    @Test
    void titleFadeStartsBlueShiftedLikeFadeInAddColor() {
        Palette white = new Palette();
        for (int i = 0; i < Palette.PALETTE_SIZE; i++) {
            white.colors[i].r = (byte) 0xEE;
            white.colors[i].g = (byte) 0xEE;
            white.colors[i].b = (byte) 0xEE;
        }
        Palette step3 = SegaPaletteFade.fromBlack(white, 3);
        assertEquals(0, Byte.toUnsignedInt(step3.colors[0].r), "red waits for blue and green");
        assertEquals(0, Byte.toUnsignedInt(step3.colors[0].g), "green waits for blue");
        assertTrue(Byte.toUnsignedInt(step3.colors[0].b) > 0, "blue rises first");
        // 21 steps reach the target; compare against the helper's own Genesis
        // re-quantisation (a zero-step fade-out) rather than the 0xEE input bytes.
        Palette full = SegaPaletteFade.fromBlack(white, FADE_FRAMES - 1);
        Palette target = SegaPaletteFade.toBlack(white, 0);
        assertEquals(target.colors[0].r, full.colors[0].r);
        assertEquals(target.colors[0].g, full.colors[0].g);
        assertEquals(target.colors[0].b, full.colors[0].b);
    }

    @Test
    void titleScreenNoLongerDrawsBlackOverlays() throws Exception {
        String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/openggf/game/sonic1/titlescreen/Sonic1TitleScreenManager.java"));
        assertFalse(source.contains("GLCommand.CommandType.RECTI"),
                "S1 title fades must go through the palette, not an alpha overlay");
    }

    private static Sonic1TitleScreenManager managerInState(TitleScreenProvider.State state) throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.JUMP, "SPACE");
        Sonic1TitleScreenManager manager = new Sonic1TitleScreenManager(config);
        setField(manager, "state", state);
        setIntField(manager, "fadeTimer", 0);
        setIntField(manager, "introTextTimer", 0);
        setIntField(manager, "bgCameraX", 0);
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

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static int invokeInt(Object target, String name) throws Exception {
        return (Integer) invoke(target, name);
    }
}
