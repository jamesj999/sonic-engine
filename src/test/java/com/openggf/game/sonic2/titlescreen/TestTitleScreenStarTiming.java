package com.openggf.game.sonic2.titlescreen;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class TestTitleScreenStarTiming {
    @Test
    void allSparklesPreserveRomAnimationAndBlankIntervals() throws Exception {
        TitleScreenManager manager = new TitleScreenManager();
        Object sprite = field(manager, "flashingStarSprite").get(manager);
        field(sprite, "active").setBoolean(sprite, true);
        field(sprite, "mappingFrame").setInt(sprite, 0xC);
        field(manager, "flashingStarWaitCounter").setInt(manager, 4);
        // Sound delivery is covered by TestTitleScreenAudioRegression.
        Arrays.fill((boolean[]) field(manager, "sparklePlayedAt").get(manager), true);
        Method update = TitleScreenManager.class.getDeclaredMethod("updateFlashingStar");
        update.setAccessible(true);

        // Ani_obj0E_FlashingStar + AnimateSprite: five two-update frames, then
        // $FA changes routine but Obj0E_Animate still submits the last mapping.
        int[] frames = {0xC, 0xC, 0xD, 0xD, 0xE, 0xE, 0xD, 0xD, 0xC, 0xC, 0xC};
        for (int sparkle = 0; sparkle < 10; sparkle++) {
            for (int tick = 0; tick < frames.length; tick++) {
                update.invoke(manager);
                assertTrue(manager.isFlashingStarVisible(), "sparkle " + sparkle + " tick " + tick);
                assertEquals(frames[tick], field(sprite, "mappingFrame").getInt(sprite));
                assertEquals(sparkle, field(manager, "flashingStarPosIndex").getInt(manager));
            }
            // Counter 4/6 decrements through zero; Move consumes another blank update.
            int wait = sparkle == 0 ? 5 : 7;
            for (int tick = 0; tick < wait; tick++) {
                update.invoke(manager);
                assertFalse(manager.isFlashingStarVisible(), "Wait must not submit a sprite");
                assertEquals(sparkle, field(manager, "flashingStarPosIndex").getInt(manager));
            }
            update.invoke(manager);
            assertFalse(manager.isFlashingStarVisible(), "Move must not submit a sprite");
            assertEquals(sparkle < 9, field(sprite, "active").getBoolean(sprite));
        }
    }

    private static Field field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
