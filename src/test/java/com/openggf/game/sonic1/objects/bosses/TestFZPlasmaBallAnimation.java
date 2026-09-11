package com.openggf.game.sonic1.objects.bosses;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestFZPlasmaBallAnimation {

    @Test
    public void chasePhaseUsesDisassemblyShortAnimationSequence() throws Exception {
        // Spawn at the launcher Y so the ball has enough chase frames before delete at boss_fz_y+$D0.
        FZPlasmaBall ball = new FZPlasmaBall(null,  0x2500, 0x53C, 0x2500);

        int safety = 0;
        while (!readBooleanField(ball, "hasCollision") && safety < 500) {
            ball.update(0, null);
            safety++;
        }
        assertTrue(readBooleanField(ball, "hasCollision"), "Ball should enter chase (obColType=$9A) before timeout");

        // Ani_Plasma.short from docs/s1disasm/_anim/Plasma Balls.asm:
        // dc.b 0, 6, 5, 1, 5, 7, 5, 1, 5, afEnd
        int[] expectedFrames = {6, 5, 1, 5, 7, 5, 1, 5, 6, 5};
        for (int expected : expectedFrames) {
            assertEquals(expected, readIntField(ball, "animFrame"), "Downward chase should use Ani_Plasma.short sequence");
            ball.update(0, null);
        }
    }

    private static int readIntField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static boolean readBooleanField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }
}


