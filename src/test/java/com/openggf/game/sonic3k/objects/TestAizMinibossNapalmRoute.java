package com.openggf.game.sonic3k.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.events.Sonic3kAIZEvents;
import com.openggf.game.sonic3k.runtime.AizZoneRuntimeState;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.boss.AbstractBossInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Production ObjectManager route and allocation tests for AIZ FallingShot. */
class TestAizMinibossNapalmRoute {
    private static final int PARENT_BITS = 0x38;
    private static final int BARREL_ACTIVATE_BIT = 1 << 1;

    @BeforeEach
    void setUp() {
        GraphicsManager.getInstance().initHeadless();
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void knucklesGateIsSetAtStartOfThirtyFrameFlameDelayOnly() throws Exception {
        for (PlayerCharacter character : List.of(PlayerCharacter.KNUCKLES, PlayerCharacter.SONIC_ALONE)) {
            AizMinibossInstance boss = new AizMinibossInstance(
                    new ObjectSpawn(0x1100, 0x300, 0x91, 0, 0, false, 0));
            ZoneRuntimeRegistry registry = new ZoneRuntimeRegistry();
            registry.install(new AizZoneRuntimeState(0, character, new Sonic3kAIZEvents(null)));
            boss.setServices(new TestObjectServices().withZoneRuntimeRegistry(registry));

            invokePrivate(boss, "onSwingPrepComplete");

            int bits = boss.getCustomFlag(PARENT_BITS);
            if (character == PlayerCharacter.KNUCKLES) {
                assertEquals(BARREL_ACTIVATE_BIT, bits & BARREL_ACTIVATE_BIT,
                        "loc_68AFE sets bit 1 during the 30-frame flame delay for Knuckles");
            } else {
                assertEquals(0, bits & BARREL_ACTIVATE_BIT,
                        "loc_68AFE leaves bit 1 clear for Sonic/Tails");
            }
            assertEquals(30, readIntField(boss, "waitTimer"),
                    "the activation bit is set when the native 30-frame delay starts");
        }
    }

    @Test
    void existingBarrelsAllocateFlareThenFallingShotAfterTheirOwnSlots() {
        RouteHarness harness = RouteHarness.create(4, 3);
        harness.boss().setCustomFlag(PARENT_BITS, BARREL_ACTIVATE_BIT);
        List<RouteObservation> observations = new ArrayList<>();

        for (int frame = 0; frame < 120; frame++) {
            harness.manager().update(0, null, List.of(), frame, false);
            for (AizMinibossNapalmProjectile projectile : live(harness.manager(),
                    AizMinibossNapalmProjectile.class)) {
                if (observations.stream().anyMatch(observation -> observation.projectile() == projectile)) {
                    continue;
                }
                AizMinibossFlameBarrelChild barrel = readObjectField(projectile, "barrel",
                        AizMinibossFlameBarrelChild.class);
                AizMinibossBarrelShotFlareChild flare = live(harness.manager(),
                        AizMinibossBarrelShotFlareChild.class).stream()
                        .filter(candidate -> readObjectField(candidate, "anchor",
                                AbstractObjectInstance.class) == barrel)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("FallingShot was not preceded by its flare"));
                observations.add(new RouteObservation(projectile, barrel,
                        flare, flare.getSlotIndex(), projectile.getSlotIndex()));
            }
        }

        List<AizMinibossNapalmProjectile> projectiles = live(harness.manager(),
                AizMinibossNapalmProjectile.class);
        assertEquals(3, projectiles.size(),
                "each existing barrel must route one native FallingShot child");
        assertEquals(3, observations.size(),
                "each FallingShot route must allocate its native flare sibling first");

        for (RouteObservation observation : observations) {
            AizMinibossNapalmProjectile projectile = observation.projectile();
            assertEquals(2, projectile.getChildSubtype(),
                    "CreateChild1_Normal assigns FallingShot child subtype $02");
            assertEquals(2, projectile.getSpawn().subtype(),
                    "ObjectSpawn subtype must remain the FallingShot child subtype");
            AizMinibossFlameBarrelChild barrel = observation.barrel();
            assertNotNull(barrel);
            assertSame(harness.boss(), readObjectField(projectile, "parent", AbstractBossInstance.class));
            assertTrue(projectile.getBarrelSubtype() == 0
                            || projectile.getBarrelSubtype() == 2
                            || projectile.getBarrelSubtype() == 4,
                    "barrel subtype remains the parent barrel's 0/2/4 value");
            assertTrue(observation.flareSlot() > barrel.getSlotIndex(),
                    "flare uses AllocateObjectAfterCurrent after its barrel");
            assertTrue(observation.projectileSlot() > observation.flareSlot(),
                    "FallingShot follows the flare in the native child pair");
        }
    }

    @Test
    void afterCurrentExhaustionKeepsFlareAndDropsFallingShotInNativeOrder() {
        RouteHarness harness = RouteHarness.create(90, 1);
        harness.manager().addDynamicObjectAtSlot(new SlotFillerObject(
                new ObjectSpawn(0, 0, 0x7F, 0, 0, false, 0)), 91);
        // Replace the first automatically-created barrel with one at slot 92
        // so only slot 93 remains when its native pair is allocated.
        AizMinibossFlameBarrelChild oldBarrel = harness.barrels().getFirst();
        harness.manager().removeDynamicObject(oldBarrel);
        AizMinibossFlameBarrelChild barrel = new AizMinibossFlameBarrelChild(harness.boss(), 0, false);
        harness.manager().addDynamicObjectAtSlot(barrel, 92);
        harness.boss().setCustomFlag(PARENT_BITS, BARREL_ACTIVATE_BIT);

        for (int frame = 0; frame < 90; frame++) {
            harness.manager().update(0, null, List.of(), frame, false);
            if (!live(harness.manager(), AizMinibossBarrelShotFlareChild.class).isEmpty()) {
                break;
            }
        }

        List<AizMinibossBarrelShotFlareChild> flares = live(harness.manager(),
                AizMinibossBarrelShotFlareChild.class);
        assertEquals(1, flares.size(),
                "the first child consumes the only after-current slot");
        assertEquals(0, live(harness.manager(), AizMinibossNapalmProjectile.class).size(),
                "the second child is discarded when AllocateObjectAfterCurrent is exhausted");
        assertEquals(93, flares.getFirst().getSlotIndex(),
                "the flare keeps the only remaining SST slot");
        assertFalse(barrel.isDestroyed(), "slot exhaustion must not destroy the spawning barrel");
    }

    private static void invokePrivate(Object target, String methodName) throws Exception {
        Method method = target.getClass().getDeclaredMethod(methodName);
        method.setAccessible(true);
        method.invoke(target);
    }

    private static int readIntField(Object target, String name) throws Exception {
        return findField(target.getClass(), name).getInt(target);
    }

    @SuppressWarnings("unchecked")
    private static <T> T readObjectField(Object target, String name, Class<T> type) {
        try {
            Object value = findField(target.getClass(), name).get(target);
            return value == null ? null : (T) type.cast(value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Unable to read " + name, e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
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

    private static <T extends ObjectInstance> List<T> live(ObjectManager manager, Class<T> type) {
        return manager.getActiveObjects().stream()
                .filter(object -> type.isInstance(object) && !object.isDestroyed())
                .map(type::cast)
                .toList();
    }

    private record RouteObservation(
            AizMinibossNapalmProjectile projectile,
            AizMinibossFlameBarrelChild barrel,
            AizMinibossBarrelShotFlareChild flare,
            int flareSlot,
            int projectileSlot) {
    }

    private record RouteHarness(
            ObjectManager manager,
            RouteBoss boss,
            List<AizMinibossFlameBarrelChild> barrels) {

        static RouteHarness create(int bossSlot, int barrelCount) {
            ObjectManager[] holder = new ObjectManager[1];
            Camera camera = mock(Camera.class);
            when(camera.getX()).thenReturn((short) 0);
            when(camera.getY()).thenReturn((short) 0);
            when(camera.getWidth()).thenReturn((short) 320);
            when(camera.getHeight()).thenReturn((short) 224);
            when(camera.isVerticalWrapEnabled()).thenReturn(false);
            ObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public Camera camera() { return camera; }
            };
            ObjectManager manager = new ObjectManager(
                    List.of(), new Sonic3kObjectRegistry(), 0, null, null,
                    GraphicsManager.getInstance(), camera, services);
            holder[0] = manager;
            RouteBoss boss = new RouteBoss(new ObjectSpawn(0, 0, 0x91, 0, 0, false, 0));
            manager.addDynamicObjectAtSlot(boss, bossSlot);
            List<AizMinibossFlameBarrelChild> barrels = new java.util.ArrayList<>();
            for (int index = 0; index < barrelCount; index++) {
                int barrelIndex = index;
                barrels.add(manager.createDynamicObject(
                        () -> new AizMinibossFlameBarrelChild(boss, barrelIndex, false)));
            }
            return new RouteHarness(manager, boss, barrels);
        }
    }

    private static final class RouteBoss extends AbstractBossInstance {
        private RouteBoss(ObjectSpawn spawn) {
            super(spawn, "AIZRouteBoss");
        }

        @Override protected void initializeBossState() {
            state.routine = 0;
            state.hitCount = 6;
        }

        @Override protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        }

        @Override protected int getInitialHitCount() { return 6; }
        @Override protected void onHitTaken(int remainingHits) { }
        @Override protected int getCollisionSizeIndex() { return 0x0F; }
        @Override public void appendRenderCommands(List<GLCommand> commands) { }
        @Override protected int getBossHitSfxId() { return 0; }
        @Override protected int getBossExplosionSfxId() { return 0; }
    }

    private static final class SlotFillerObject extends AbstractObjectInstance {
        private SlotFillerObject(ObjectSpawn spawn) {
            super(spawn, "AIZSlotFiller");
        }

        @Override public void update(int vIntRunCount, PlayableEntity player) { }
        @Override public void appendRenderCommands(List<GLCommand> commands) { }
    }
}
