package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCnz2CutsceneFloorContacts {
    @Test
    void firstCutsceneOnlyAppliesFloorDistanceOnTerminalLanding() throws Exception {
        CutsceneKnucklesCnz2AInstance rebound = firstCutscene();
        setInt(rebound, "currentY", 0x0280);
        setInt(rebound, "bounceIndex", 0);

        applyFloorContact(rebound, -3);

        assertEquals(0x0280, rebound.getY(),
                "loc_6237C changes velocity on intermediate contacts without add.w d1,y_pos");
        assertEquals(-0x0400, getInt(rebound, "yVel"));

        CutsceneKnucklesCnz2AInstance landing = firstCutscene();
        setInt(landing, "currentY", 0x0280);
        setInt(landing, "bounceIndex", 2);

        applyFloorContact(landing, -3);

        assertEquals(0x027D, landing.getY(),
                "loc_623B8 applies floor distance only on the terminal landing");
        assertEquals(8, landing.getRoutine());
    }

    @Test
    void secondCutsceneOnlyAppliesFloorDistanceOnTerminalLanding() throws Exception {
        CutsceneKnucklesCnz2BInstance rebound = secondCutscene();
        setInt(rebound, "currentY", 0x0720);
        setInt(rebound, "xVel", -0x0100);
        setInt(rebound, "yVel", 0x0300);

        applyFloorContact(rebound, -5);

        assertEquals(0x0720, rebound.getY(),
                "loc_620AA negates velocities on the first contact without snapping y_pos");
        assertEquals(0x0100, getInt(rebound, "xVel"));
        assertEquals(-0x0300, getInt(rebound, "yVel"));

        CutsceneKnucklesCnz2BInstance landing = secondCutscene();
        setInt(landing, "currentY", 0x0720);
        setBoolean(landing, "bounced", true);

        applyFloorContact(landing, -5);

        assertEquals(0x071B, landing.getY(),
                "loc_620D8 applies floor distance only after the bounced flag was already set");
        assertEquals(10, landing.getRoutine());
    }

    private static CutsceneKnucklesCnz2AInstance firstCutscene() {
        return new CutsceneKnucklesCnz2AInstance(new ObjectSpawn(
                0x1D00, 0x0280, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 12, 0, false, 0));
    }

    private static CutsceneKnucklesCnz2BInstance secondCutscene() {
        return new CutsceneKnucklesCnz2BInstance(new ObjectSpawn(
                0x45C0, 0x0720, Sonic3kObjectIds.CUTSCENE_KNUCKLES, 16, 0, false, 0));
    }

    private static int getInt(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void applyFloorContact(Object target, int distance) throws Exception {
        Method method = target.getClass().getDeclaredMethod("applyFloorContact", int.class);
        method.setAccessible(true);
        method.invoke(target, distance);
    }

    private static void setInt(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setBoolean(Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }
}
