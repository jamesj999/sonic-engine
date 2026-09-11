package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestS2OozBadnikParity {

    @Test
    void aquisClearRenderFlagProjectileSpawnsLeftAndMovesLeft() throws Exception {
        AquisBadnikInstance aquis = new AquisBadnikInstance(spawn(Sonic2ObjectIds.AQUIS, 0x300, 0x180));

        BadnikProjectileInstance projectile = fireSingleAquisProjectile(aquis);

        assertEquals(0x300 - 0x10, projectile.getSpawn().x());
        assertEquals(0x180 - 0x0A, projectile.getSpawn().y());
        assertEquals(-0x300, getInt(projectile, "xVelocity"));
        assertEquals(0x200, getInt(projectile, "yVelocity"));
        assertFalse(getBoolean(projectile, "hFlip"));
    }

    @Test
    void aquisSetRenderFlagProjectileSpawnsRightAndMovesRight() throws Exception {
        AquisBadnikInstance aquis = new AquisBadnikInstance(spawn(Sonic2ObjectIds.AQUIS, 0x300, 0x180, 1));

        BadnikProjectileInstance projectile = fireSingleAquisProjectile(aquis);

        assertEquals(0x300 + 0x10, projectile.getSpawn().x());
        assertEquals(0x180 - 0x0A, projectile.getSpawn().y());
        assertEquals(0x300, getInt(projectile, "xVelocity"));
        assertEquals(0x200, getInt(projectile, "yVelocity"));
        assertTrue(getBoolean(projectile, "hFlip"));
    }

    @Test
    void aquisIneligibleShotConsumesShootingWindow() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        AquisBadnikInstance aquis = new AquisBadnikInstance(spawn(Sonic2ObjectIds.AQUIS, 0x300, 0x180));
        aquis.setServices(servicesWithObjectManager(objectManager));

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreY()).thenReturn((short) 0x170, (short) 0x190);
        setField(aquis, "timer", 0x20);

        invoke(aquis, "updateShooting", new Class<?>[] { AbstractPlayableSprite.class }, player);
        assertTrue(getBoolean(aquis, "shootingFlag"));

        invoke(aquis, "updateShooting", new Class<?>[] { AbstractPlayableSprite.class }, player);
        verify(objectManager, never()).addDynamicObject(any(ObjectInstance.class));
    }

    @Test
    void aquisTimerExpiryClearsFlagAndShootsBeforeNextChase() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));

        AquisBadnikInstance aquis = new AquisBadnikInstance(spawn(Sonic2ObjectIds.AQUIS, 0x300, 0x180));
        aquis.setServices(servicesWithObjectManager(objectManager));
        setField(aquis, "timer", 0);
        setField(aquis, "shotsRemaining", 1);
        setField(aquis, "shootingFlag", true);

        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.isDebugMode()).thenReturn(false);
        when(player.getCentreY()).thenReturn((short) 0x190);

        invoke(aquis, "updateShooting", new Class<?>[] { AbstractPlayableSprite.class }, player);

        assertEquals(1, spawned.size(),
                "Obj50_WaitForNextShot clears the flag, then Obj50_Shooting still calls Obj50_ChkIfShoot");
        assertEquals(0, getInt(aquis, "shotsRemaining"),
                "Obj50_WaitForNextShot branches to escape only when the decremented byte is negative");
        assertEquals(0x80, getInt(aquis, "timer"));
        assertEquals(-0x100, getInt(aquis, "yVelocity"));
        assertTrue(getBoolean(aquis, "shootingFlag"));
    }

    @Test
    void aquisBulletUsesRomAnimationAndPriority() throws Exception {
        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn(Sonic2ObjectIds.AQUIS, 0x300, 0x180),
                BadnikProjectileInstance.ProjectileType.AQUIS_BULLET,
                0x2F0,
                0x176,
                -0x300,
                0x200,
                false,
                false);

        assertEquals(3, projectile.getPriorityBucket());
        assertAquisRenderedFrame(projectile, 5);

        updateProjectile(projectile, 4);
        assertAquisRenderedFrame(projectile, 6);

        updateProjectile(projectile, 4);
        assertAquisRenderedFrame(projectile, 7);

        updateProjectile(projectile, 4);
        assertAquisRenderedFrame(projectile, 6);

        updateProjectile(projectile, 4);
        assertAquisRenderedFrame(projectile, 5);
    }

    @Test
    void octusInitializesFloorAnchorFromNegativeFloorDistance() throws Exception {
        try (MockedStatic<ObjectTerrainUtils> terrain = mockStatic(ObjectTerrainUtils.class)) {
            terrain.when(() -> ObjectTerrainUtils.checkFloorDist(0x240, 0x190, 0x0B))
                    .thenReturn(new TerrainCheckResult(-6, (byte) 0, 0x123));

            OctusBadnikInstance octus = new OctusBadnikInstance(spawn(Sonic2ObjectIds.OCTUS, 0x240, 0x190));
            // Obj4A_Init does the ObjCheckFloorDist anchor on its own routine-0
            // dispatch (s2.asm:60380-60401), not at spawn. Setup only; the
            // assertions below are unchanged.
            octus.testRunInitDispatch(null);

            assertEquals(0x190 - 6, getInt(octus, "startY"));
            assertEquals(0x190 - 6, getInt(octus, "currentY"));
            terrain.verify(() -> ObjectTerrainUtils.checkFloorDist(0x240, 0x190, 0x0B));
        }
    }

    @Test
    void octusBulletUsesCoarseMarkObjGoneUnloadWindow() {
        AbstractObjectInstance.updateCameraBounds(0x0FFE, 0, 0x0FFE + 320, 224, 0);
        try {
            BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                    spawn(Sonic2ObjectIds.OCTUS, 0x1000, 0x0233),
                    BadnikProjectileInstance.ProjectileType.OCTUS_BULLET,
                    0x0F62,
                    0x0233,
                    -0x0200,
                    0,
                    false,
                    false);

            projectile.update(0, null);

            assertFalse(projectile.isDestroyed(),
                    "Obj4A_Bullet tails through MarkObjGone, so it survives while "
                            + "(x_pos&$FF80)-Camera_X_pos_coarse is inside $280");
        } finally {
            AbstractObjectInstance.resetCameraBoundsForTests();
        }
    }

    private static BadnikProjectileInstance fireSingleAquisProjectile(AquisBadnikInstance aquis) throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        List<ObjectInstance> spawned = new ArrayList<>();
        doAnswer(invocation -> {
            spawned.add(invocation.getArgument(0));
            return null;
        }).when(objectManager).addDynamicObject(any(ObjectInstance.class));
        aquis.setServices(servicesWithObjectManager(objectManager));

        invoke(aquis, "fireProjectile", new Class<?>[0]);

        assertEquals(1, spawned.size());
        return assertInstanceOf(BadnikProjectileInstance.class, spawned.get(0));
    }

    private static ObjectServices servicesWithObjectManager(ObjectManager objectManager) {
        ObjectServices services = mock(ObjectServices.class);
        when(services.objectManager()).thenReturn(objectManager);
        return services;
    }

    private static ObjectSpawn spawn(int objectId, int x, int y) {
        return spawn(objectId, x, y, 0);
    }

    private static ObjectSpawn spawn(int objectId, int x, int y, int renderFlags) {
        return new ObjectSpawn(x, y, objectId, 0, renderFlags, false, y);
    }

    private static void updateProjectile(BadnikProjectileInstance projectile, int frames) {
        for (int i = 0; i < frames; i++) {
            projectile.update(i, (PlayableEntity) null);
        }
    }

    private static void assertAquisRenderedFrame(BadnikProjectileInstance projectile, int expectedFrame)
            throws Exception {
        int animFrame = getInt(projectile, "animFrame");
        int[] frames = (int[]) getField(BadnikProjectileInstance.class, "AQUIS_BULLET_FRAMES").get(null);
        assertEquals(expectedFrame, frames[animFrame % frames.length]);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args)
            throws Exception {
        Method method = findMethod(target.getClass(), name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static int getInt(Object target, String name) throws Exception {
        return getField(target.getClass(), name).getInt(target);
    }

    private static boolean getBoolean(Object target, String name) throws Exception {
        return getField(target.getClass(), name).getBoolean(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        getField(target.getClass(), name).set(target, value);
    }

    private static Field getField(Class<?> type, String name) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) throws Exception {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
    }
}
