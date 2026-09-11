package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossInstance;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossGradualMaxXExtender;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleButton;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossEggCapsuleInstance;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestHczEndBossInstance {
    @Test
    void capsuleButtonRetainsItsSeparateSolidObjectFullGeometry() {
        HczEndBossEggCapsuleInstance capsule = new HczEndBossEggCapsuleInstance(0x4250, 0x07E0);
        HczEndBossEggCapsuleButton button = new HczEndBossEggCapsuleButton(
                capsule, 0x4250, 0x07BC);

        assertEquals(0x4250, button.getX());
        assertEquals(0x07BC, button.getY());
        assertEquals(0x1B, button.getSolidParams().halfWidth());
        assertEquals(4, button.getSolidParams().airHalfHeight());
        assertEquals(6, button.getSolidParams().groundHalfHeight());
    }

    @Test
    void gradualMaxXHelperUsesNativeQuarterPixelAcceleration() {
        TestObjectServices services = new TestObjectServices()
                .withConfiguration(SonicConfigurationService.getInstance())
                .withCamera(new Camera(SonicConfigurationService.getInstance()));
        services.camera().setMaxX((short) 0x4050);
        HczEndBossGradualMaxXExtender extender = ObjectConstructionContext.construct(
                services, () -> new HczEndBossGradualMaxXExtender(0x4178, 0x07E0, 0x4180));
        extender.setServices(services);

        extender.update(0, null);
        extender.update(1, null);
        extender.update(2, null);
        assertEquals(0x4050, services.camera().getMaxX() & 0xFFFF);

        extender.update(3, null);
        assertEquals(0x4051, services.camera().getMaxX() & 0xFFFF,
                "Obj_IncLevEndXGradual adds the swapped $4000 accumulator");
    }

    @Test
    void fleeUsesConstantMoveSpriteVelocityAndObjWaitUnderflow() throws Exception {
        TestObjectServices services = new TestObjectServices()
                .withConfiguration(SonicConfigurationService.getInstance());
        HczEndBossInstance boss = ObjectConstructionContext.construct(
                services,
                () -> new HczEndBossInstance(
                        new ObjectSpawn(0x4050, 0x0738, 0x9A, 0, 0, false, 0x0738)));
        boss.setServices(services);
        boss.getState().routine = 16;
        boss.getState().yVel = -0x200;
        setIntField(boss, "fleeTimer", 2);

        Method updateFlee = HczEndBossInstance.class.getDeclaredMethod("updateFlee");
        updateFlee.setAccessible(true);
        updateFlee.invoke(boss);

        assertEquals(-0x200, boss.getState().yVel,
                "loc_6B0CC calls MoveSprite without gravity");
        assertEquals(1, getIntField(boss, "fleeTimer"));
    }
    @Test
    void preAttackSetupDefersTheCallbackWithoutAddingASwingStep() throws Exception {
        TestObjectServices services = new TestObjectServices()
                .withConfiguration(SonicConfigurationService.getInstance());
        HczEndBossInstance boss = ObjectConstructionContext.construct(
                services,
                () -> new HczEndBossInstance(
                        new ObjectSpawn(0x4050, 0x0738, 0x9A, 0, 0, false, 0x0738)));
        boss.setServices(services);

        Method callback = HczEndBossInstance.class.getDeclaredMethod("onStRiseComplete");
        callback.setAccessible(true);
        callback.invoke(boss);

        Field waitTimer = HczEndBossInstance.class.getDeclaredField("waitTimer");
        waitTimer.setAccessible(true);
        assertEquals(0xFF, waitTimer.getInt(boss),
                "loc_6B01E stores the native $FF Obj_Wait countdown");
        Field stateY = boss.getState().getClass().getField("y");
        int setupY = stateY.getInt(boss.getState());
        waitTimer.setInt(boss, 0);

        Method tickWait = HczEndBossInstance.class.getDeclaredMethod("tickWait");
        tickWait.setAccessible(true);
        tickWait.invoke(boss);

        Field pending = HczEndBossInstance.class.getDeclaredField("pendingAttackSetupDispatch");
        pending.setAccessible(true);
        assertTrue(pending.getBoolean(boss));
        assertEquals(10, boss.getState().routine);

        Method update = HczEndBossInstance.class.getDeclaredMethod(
                "updateBossLogic", int.class, com.openggf.game.PlayableEntity.class);
        update.setAccessible(true);
        update.invoke(boss, 0, null);

        assertFalse(pending.getBoolean(boss));
        assertEquals(12, boss.getState().routine);
        assertEquals(setupY, stateY.getInt(boss.getState()),
                "the setup-only dispatch must not add an extra swing movement");
    }

    @Test
    void bossPersistsWhileFleeingSoDefeatHandoffCanUnlockCamera() {
        TestObjectServices services = new TestObjectServices()
                .withConfiguration(SonicConfigurationService.getInstance());
        HczEndBossInstance boss = ObjectConstructionContext.construct(
                services,
                () -> new HczEndBossInstance(
                        new ObjectSpawn(0x4050, 0x0738, 0x9A, 0, 0, false, 0x0738)));
        boss.setServices(services);

        assertFalse(boss.isPersistent(), "inactive HCZ boss should keep normal placement lifetime");

        boss.getState().routine = 16;

        assertTrue(boss.isPersistent(),
                "ROUTINE_FLEE must survive off-screen culling until loc_6B0E8 clears Boss_flag, "
                        + "widens the camera, and spawns the egg capsule");
    }

    private static void setIntField(Object target, String name, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static int getIntField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
