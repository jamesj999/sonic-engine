package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.GameRng;
import org.junit.jupiter.api.Test;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class TestTurtloidBadnikInstance {

    @Test
    public void riderAttackKeepsTurtloidBaseAsPlatform() throws Exception {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectRenderManager objectRenderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer pointsRenderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        when(levelManager.getObjectRenderManager()).thenReturn(objectRenderManager);
        when(objectRenderManager.getPointsRenderer()).thenReturn(pointsRenderer);

        com.openggf.level.objects.ObjectServices services = mock(com.openggf.level.objects.ObjectServices.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(services.renderManager()).thenReturn(objectRenderManager);
        when(services.rng()).thenReturn(new GameRng(GameRng.Flavour.S1_S2));

        ObjectConstructionContext.setConstructionContext(services);
        TurtloidBadnikInstance base;
        try {
            base = new TurtloidBadnikInstance(
                    new ObjectSpawn(0x200, 0x100, Sonic2ObjectIds.TURTLOID, 0x18, 0, false, 0));
        } finally {
            ObjectConstructionContext.clearConstructionContext();
        }
        base.setServices(services);
        spawnInitialChildren(base);

        TurtloidRiderInstance rider = (TurtloidRiderInstance) getField(base, "rider");
        assertNotNull(rider, "Turtloid should spawn a rider child");

        clearInvocations(objectManager);
        setEnumField(base, "state", "PAUSE_BEFORE");
        setIntField(base, "xVelocity", 0);

        rider.onPlayerAttack(null, null);

        assertTrue(rider.isDestroyed(), "Rider should be destroyed on attack");
        assertFalse(base.isParentDestroyed(), "Turtloid base should remain alive as platform");
        assertNull(getField(base, "rider"), "Parent rider reference should be cleared");
        assertEquals(0, getIntField(base, "xVelocity"),
                "Obj9B destruction does not write parent Obj9A state; the base resumes when its own timer expires");
        assertEquals("PAUSE_BEFORE", getField(base, "state").toString(),
                "Rider destruction should not bypass the parent Obj9A pause/shoot state machine");
        verify(objectManager, times(3)).addDynamicObject(any());
    }

    @Test
    public void riderAttackSpawnsAftermathAtPreUpdateTouchPosition() throws Exception {
        LevelManager levelManager = mock(LevelManager.class);
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectRenderManager objectRenderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer pointsRenderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectManager()).thenReturn(objectManager);
        when(levelManager.getObjectRenderManager()).thenReturn(objectRenderManager);
        when(objectRenderManager.getPointsRenderer()).thenReturn(pointsRenderer);

        com.openggf.level.objects.ObjectServices services = mock(com.openggf.level.objects.ObjectServices.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(services.renderManager()).thenReturn(objectRenderManager);
        when(services.rng()).thenReturn(new GameRng(GameRng.Flavour.S1_S2));

        ObjectConstructionContext.setConstructionContext(services);
        TurtloidBadnikInstance base;
        try {
            base = new TurtloidBadnikInstance(
                    new ObjectSpawn(0x200, 0x100, Sonic2ObjectIds.TURTLOID, 0x18, 0, false, 0));
        } finally {
            ObjectConstructionContext.clearConstructionContext();
        }
        base.setServices(services);
        spawnInitialChildren(base);

        TurtloidRiderInstance rider = (TurtloidRiderInstance) getField(base, "rider");
        assertNotNull(rider, "Turtloid should spawn a rider child");
        rider.snapshotTouchResponseState();
        setIntField(rider, "currentX", 0x1D0);
        setIntField(rider, "currentY", 0x120);

        clearInvocations(objectManager);
        rider.onPlayerAttack(null, null);

        ArgumentCaptor<ObjectInstance> spawned = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager, times(3)).addDynamicObject(spawned.capture());
        List<ObjectInstance> aftermath = spawned.getAllValues();
        assertTrue(aftermath.stream().allMatch(object -> object.getX() == 0x204),
                "Touch response aftermath should use the rider's pre-update X");
        assertTrue(aftermath.stream().allMatch(object -> object.getY() == 0xE8),
                "Touch response aftermath should use the rider's pre-update Y");
    }

    @Test
    public void riderTouchResponseDoesNotRequireRenderFlag() throws Exception {
        TurtloidBadnikInstance base = createTurtloidBaseWithServices();
        TurtloidRiderInstance rider = (TurtloidRiderInstance) getField(base, "rider");

        assertNotNull(rider, "Turtloid should spawn a rider child");
        TouchResponseProfile expectedProfile = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                false,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
        assertEquals(expectedProfile, rider.getTouchResponseProfile(),
                "The rider should preserve the S2 render-flag opt-out through its touch-response profile");
        assertFalse(rider.requiresRenderFlagForTouch(),
                "S2 TouchResponse scans collision_flags directly; SCZ Turtloid rider must remain hittable before the engine on-screen touch gate catches up");
        assertEquals(expectedProfile, rider.getTouchResponseProfile(false));
    }

    @Test
    public void baseUsesPlatformObjectTopOnlySolidity() {
        TurtloidBadnikInstance base = createTurtloidBaseWithServices();

        assertTrue(base.isTopSolidOnly(),
                "Obj9A calls PlatformObject, so the base must use top-only platform solidity");
        assertTrue(base.usesCollisionHalfWidthForTopLanding(),
                "PlatformObject passes d1 as the standable width without the SolidObject +$B expansion");
        assertTrue(base.usesGroundHalfHeightForTopSolidContact(),
                "Obj9A passes its PlatformObject surface height in d3, not the d2 register");
        assertTrue(base.allowsZeroDistanceTopSolidLanding(null),
                "SCZ Obj9A accepts the exact PlatformObject_ChkYRange boundary used by the level-select trace");
    }

    @Test
    public void projectileSpawnsFromParentBodyPosition() throws Exception {
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectRenderManager objectRenderManager = mock(ObjectRenderManager.class);
        com.openggf.level.objects.ObjectServices services = mock(com.openggf.level.objects.ObjectServices.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(services.renderManager()).thenReturn(objectRenderManager);
        when(services.rng()).thenReturn(new GameRng(GameRng.Flavour.S1_S2));

        ObjectConstructionContext.setConstructionContext(services);
        TurtloidBadnikInstance base;
        try {
            base = new TurtloidBadnikInstance(
                    new ObjectSpawn(0x200, 0x100, Sonic2ObjectIds.TURTLOID, 0x18, 0, false, 0));
            base.setServices(services);
            spawnInitialChildren(base);
        } finally {
            ObjectConstructionContext.clearConstructionContext();
        }

        clearInvocations(objectManager);
        setIntField(base, "currentX", 0x133E);
        setIntField(base, "currentY", 0x0530);

        Method fireProjectile = TurtloidBadnikInstance.class.getDeclaredMethod("fireProjectile");
        fireProjectile.setAccessible(true);
        fireProjectile.invoke(base);

        ArgumentCaptor<ObjectInstance> spawned = ArgumentCaptor.forClass(ObjectInstance.class);
        verify(objectManager).addDynamicObject(spawned.capture());
        ObjectInstance projectile = spawned.getValue();
        assertEquals(0x132A, projectile.getX(),
                "Obj9A loc_37AF2 copies parent x_pos, then subtracts $14");
        assertEquals(0x053A, projectile.getY(),
                "Obj9A loc_37AF2 copies parent y_pos, then adds $A");
    }

    private static TurtloidBadnikInstance createTurtloidBaseWithServices() {
        ObjectManager objectManager = mock(ObjectManager.class);
        ObjectRenderManager objectRenderManager = mock(ObjectRenderManager.class);
        com.openggf.level.objects.ObjectServices services = mock(com.openggf.level.objects.ObjectServices.class);
        when(services.objectManager()).thenReturn(objectManager);
        when(services.renderManager()).thenReturn(objectRenderManager);
        when(services.rng()).thenReturn(new GameRng(GameRng.Flavour.S1_S2));

        ObjectConstructionContext.setConstructionContext(services);
        try {
            TurtloidBadnikInstance base = new TurtloidBadnikInstance(
                    new ObjectSpawn(0x200, 0x100, Sonic2ObjectIds.TURTLOID, 0x18, 0, false, 0));
            base.setServices(services);
            spawnInitialChildren(base);
            return base;
        } finally {
            ObjectConstructionContext.clearConstructionContext();
        }
    }

    private static void spawnInitialChildren(TurtloidBadnikInstance base) {
        try {
            Method ensureChildrenSpawned = TurtloidBadnikInstance.class.getDeclaredMethod("ensureChildrenSpawned");
            ensureChildrenSpawned.setAccessible(true);
            ensureChildrenSpawned.invoke(base);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to drive Turtloid child-spawn lifecycle", e);
        }
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int getIntField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.getInt(target);
    }

    private static void setIntField(Object target, String fieldName, int value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setEnumField(Object target, String fieldName, String enumConstant) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        Class<? extends Enum> enumType = (Class<? extends Enum>) field.getType().asSubclass(Enum.class);
        field.set(target, Enum.valueOf(enumType, enumConstant));
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


