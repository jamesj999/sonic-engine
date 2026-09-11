package com.openggf.game.sonic1.objects.bosses;

import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.BossStateContext;
import com.openggf.tests.TestEnvironment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestSonic1FzBossEscapeHitCue {

    @BeforeEach
    public void setUp() {
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    public void tearDown() {
        SessionManager.clear();
    }

    @Test
    public void escapeHitClearsCollisionFlagsAndEnablesDamagedCue() throws Exception {
        Sonic1FZBossInstance boss = new Sonic1FZBossInstance(
                new ObjectSpawn(0, 0, Sonic1ObjectIds.FZ_BOSS, 0, 0, false, 0));
        boss.setServices(new TestObjectServices().withCamera(GameServices.camera()));

        BossStateContext state = (BossStateContext) getFieldValue(boss, "state");
        state.routineSecondary = 14; // STATE_FINAL_FLIGHT
        state.defeated = true;

        setFieldValue(boss, "escapeHittable", true);
        setFieldValue(boss, "escapeCollisionFlags", 0xCF);
        setFieldValue(boss, "escapeHitTimer", 0);
        setFieldValue(boss, "showDamaged", false);

        assertEquals(0xCF, boss.getCollisionFlags());

        boss.onPlayerAttack(null, new TouchResponseResult(0, 0, 0, TouchCategory.BOSS));

        assertEquals(0, boss.getCollisionFlags());
        assertFalse((boolean) getFieldValue(boss, "escapeHittable"));
        assertEquals(0x1E, getFieldValue(boss, "escapeHitTimer"));
        assertEquals(0, getFieldValue(boss, "escapeCollisionFlags"));
        assertTrue((boolean) getFieldValue(boss, "showDamaged"));
    }

    @Test
    public void damagedCuePersistsAfterEscapeHitTimerEnds() throws Exception {
        Sonic1FZBossInstance boss = new Sonic1FZBossInstance(
                new ObjectSpawn(0, 0, Sonic1ObjectIds.FZ_BOSS, 0, 0, false, 0));
        boss.setServices(new TestObjectServices().withCamera(GameServices.camera()));

        BossStateContext state = (BossStateContext) getFieldValue(boss, "state");
        state.routineSecondary = 14; // STATE_FINAL_FLIGHT
        state.x = 0;
        state.y = 0;
        state.xFixed = 0;
        state.yFixed = 0;

        setFieldValue(boss, "escapeHittable", false);
        setFieldValue(boss, "escapeCollisionFlags", 0);
        setFieldValue(boss, "escapeHitTimer", 1);
        setFieldValue(boss, "showDamaged", true);

        Method updateFinalFlight = Sonic1FZBossInstance.class.getDeclaredMethod(
                "updateFinalFlight", AbstractPlayableSprite.class, int.class);
        updateFinalFlight.setAccessible(true);
        updateFinalFlight.invoke(boss, null, 1);

        assertEquals(0, getFieldValue(boss, "escapeHitTimer"));
        assertTrue((boolean) getFieldValue(boss, "showDamaged"));
    }

    private static Object getFieldValue(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setFieldValue(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}


