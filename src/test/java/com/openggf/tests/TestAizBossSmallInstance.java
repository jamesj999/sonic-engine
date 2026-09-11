package com.openggf.tests;

import com.openggf.game.sonic3k.events.AizObjectEventBridge;
import com.openggf.game.sonic3k.objects.AizBossSmallInstance;
import com.openggf.game.LevelEventProvider;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.StubObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestAizBossSmallInstance {
    @Test
    void bossSmallMovesBeforeVelocityAdjustmentAndOnlyExitsOnNextFrame() {
        RecordingAizBridge bridge = new RecordingAizBridge();
        AizBossSmallInstance boss = buildWithServices(new StubObjectServices() {
            @Override
            public LevelEventProvider levelEventProvider() {
                return bridge;
            }
        });
        setBooleanField(boss, "movementStarted", true);
        setBooleanField(boss, "decelerating", false);
        setIntField(boss, "screenX", 0x1BA);
        setIntField(boss, "xSub", 0);
        setIntField(boss, "xVel", 0x60000);

        boss.update(0, null);

        assertFalse(boss.isDestroyed(),
                "Obj_AIZ2BossSmall checks x_pos >= $240 before movement, so first crossing must survive");
        assertEquals(0, bridge.bossSmallCompleteCalls,
                "clearing Scroll_lock/Special_events_routine happens on the next object update after crossing");
        assertEquals(0x1C0, getIntField(boss, "screenX"),
                "ROM moves by the current x_vel before applying the phase-2 acceleration");
        assertEquals(0x60E80, getIntField(boss, "xVel"),
                "ROM applies addi.l #$E80,x_vel after the movement step");
    }

    private static AizBossSmallInstance buildWithServices(ObjectServices services) {
        AizBossSmallInstance boss = new AizBossSmallInstance();
        boss.setServices(services);
        return boss;
    }

    private static int getIntField(Object target, String fieldName) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.getInt(target);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setIntField(Object target, String fieldName, int value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setBooleanField(Object target, String fieldName, boolean value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setBoolean(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static final class RecordingAizBridge implements LevelEventProvider, AizObjectEventBridge {
        int bossSmallCompleteCalls;

        @Override public void initLevel(int zone, int act) {}
        @Override public void update() {}
        @Override public void setBossFlag(boolean value) {}
        @Override public void setEventsFg5(boolean value) {}
        @Override public void triggerScreenShake(int frames) {}
        @Override public void onBattleshipComplete() {}
        @Override public void onBossSmallComplete() { bossSmallCompleteCalls++; }
        @Override public boolean isFireTransitionActive() { return false; }
        @Override public boolean isAct2TransitionRequested() { return false; }
    }
}
