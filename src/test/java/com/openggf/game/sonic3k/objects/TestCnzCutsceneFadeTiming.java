package com.openggf.game.sonic3k.objects;

import com.openggf.audio.AudioManager;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestCnzCutsceneFadeTiming {
    @Test
    void knucklesThemeTransitionUsesNinetyOnePostInitTicks() throws Exception {
        int delay = staticInt(CutsceneKnucklesCnz2BInstance.class, "KNUCKLES_MUSIC_FADE_FRAMES");
        assertEquals(91, delay);
        assertTransitionFiresOnTick(delay);
    }

    @Test
    void levelMusicTransitionsUseOneHundredTwentyOnePostInitTicks() throws Exception {
        int firstDelay = staticInt(CutsceneKnucklesCnz2AInstance.class, "LEVEL_MUSIC_FADE_FRAMES");
        int secondDelay = staticInt(CutsceneKnucklesCnz2BInstance.class, "LEVEL_MUSIC_FADE_FRAMES");
        assertEquals(121, firstDelay);
        assertEquals(121, secondDelay);
        assertTransitionFiresOnTick(firstDelay);
    }

    private static void assertTransitionFiresOnTick(int delay) {
        List<Integer> played = new ArrayList<>();
        TestObjectServices services = new TestObjectServices() {
            @Override public void playMusic(int musicId) { played.add(musicId); }
        }.withAudioManager(mock(AudioManager.class));
        SongFadeTransitionInstance transition = new SongFadeTransitionInstance(delay, 0x8A, true);
        transition.setServices(services);

        transition.update(0, null);
        assertTrue(played.isEmpty(), "fade initialization must not consume a delay tick");

        for (int tick = 1; tick < delay; tick++) {
            transition.update(tick, null);
        }
        assertTrue(played.isEmpty());
        assertFalse(transition.isDestroyed());

        transition.update(delay, null);

        assertEquals(List.of(0x8A), played);
        assertTrue(transition.isDestroyed());
    }

    private static int staticInt(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(null);
    }
}
