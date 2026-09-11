package com.openggf.game.sonic3k.objects;

import com.openggf.game.BonusStageProvider;
import com.openggf.game.rewind.DeletedDynamicRewindCodecs;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import com.openggf.game.sonic3k.Sonic3kBonusStageCoordinator;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotBonusStageRuntime;
import com.openggf.game.sonic3k.bonusstage.slots.S3kSlotStageController;
import com.openggf.game.sonic3k.objects.Sonic3kObjectRegistry;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.DynamicObjectRecreateContext;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRewindDynamicCodecs;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guard that verifies each slot-machine bonus-stage dynamic object uses Phase-2
 * generic recreate rather than a hand-written dynamic codec.
 *
 * <p>The old codecs existed only to supply the live {@link S3kSlotStageController}.
 * The {@link RewindRecreatable} path must now resolve that same controller via
 * {@link ObjectServices#bonusStageProviderOrNull()} and reconstruct the object
 * from the captured spawn; all mutable scalar state is then reapplied by the
 * generic field capturer.
 */
class TestRewindFixS3KSlotCodecs {

    private ObjectManager objectManager;
    private S3kSlotStageController controller;

    @BeforeEach
    void setUp() throws Exception {
        GraphicsManager.getInstance().initHeadless();

        Sonic3kBonusStageCoordinator coordinator = new Sonic3kBonusStageCoordinator();
        S3kSlotBonusStageRuntime runtime = new S3kSlotBonusStageRuntime();
        runtime.bootstrap();
        installSlotRuntime(coordinator, runtime);
        controller = runtime.stageController();
        assertNotNull(controller, "slot runtime bootstrap must create the stage controller");

        ObjectManager[] holder = new ObjectManager[1];
        SlotRewindServices services = new SlotRewindServices(coordinator) {
            @Override
            public ObjectManager objectManager() {
                return holder[0];
            }
        };
        objectManager = new ObjectManager(
                List.of(),
                new Sonic3kObjectRegistry(),
                0,
                null,
                null,
                GraphicsManager.getInstance(),
                null,
                services);
        holder[0] = objectManager;
        objectManager.reset(0);
    }

    @AfterEach
    void tearDown() {
        GraphicsManager.getInstance().resetState();
    }

    @Test
    void slotBonusCageUsesGenericRecreateWithLiveSlotController() throws Exception {
        assertUsesGenericRecreateWithLiveSlotController(S3kSlotBonusCageObjectInstance.class);
    }

    @Test
    void slotRingRewardUsesGenericRecreateWithLiveSlotController() throws Exception {
        assertUsesGenericRecreateWithLiveSlotController(S3kSlotRingRewardObjectInstance.class);
    }

    @Test
    void slotSpikeRewardUsesGenericRecreateWithLiveSlotController() throws Exception {
        assertUsesGenericRecreateWithLiveSlotController(S3kSlotSpikeRewardObjectInstance.class);
    }

    private void assertUsesGenericRecreateWithLiveSlotController(
            Class<? extends AbstractObjectInstance> targetClass) throws Exception {
        assertFalse(DeletedDynamicRewindCodecs.hasRegisteredDynamicCodec(targetClass.getName()),
                targetClass.getSimpleName() + " must not keep its hand-written dynamic codec");
        assertTrue(RewindRecreatable.class.isAssignableFrom(targetClass),
                targetClass.getSimpleName() + " must opt into generic recreate");

        ObjectInstance recreated = ObjectRewindDynamicCodecs.genericRecreate(
                dynamicEntry(targetClass),
                new DynamicObjectRecreateContext(objectManager));

        assertNotNull(recreated, "generic recreate must rebuild " + targetClass.getSimpleName());
        assertEquals(targetClass, recreated.getClass());
        assertSame(controller, controllerField(recreated),
                "recreated slot object must use the live controller from the active S3K slot runtime");
    }

    private static ObjectManagerSnapshot.DynamicObjectEntry dynamicEntry(Class<?> targetClass) {
        ObjectSpawn spawn = new ObjectSpawn(0x460, 0x430, 0, 0, 0, false, 0);
        PerObjectRewindSnapshot state = new PerObjectRewindSnapshot(
                false, false, false, 0, 0, 0, 0, false, 0, false, false, 0, -1, null, null, null);
        return new ObjectManagerSnapshot.DynamicObjectEntry(targetClass.getName(), spawn, 0, state);
    }

    private static Object controllerField(ObjectInstance instance) throws ReflectiveOperationException {
        Field field = instance.getClass().getDeclaredField("controller");
        field.setAccessible(true);
        return field.get(instance);
    }

    private static void installSlotRuntime(
            Sonic3kBonusStageCoordinator coordinator,
            S3kSlotBonusStageRuntime runtime) throws ReflectiveOperationException {
        Field field = Sonic3kBonusStageCoordinator.class.getDeclaredField("slotRuntime");
        field.setAccessible(true);
        field.set(coordinator, runtime);
    }

    private static class SlotRewindServices extends TestObjectServices {
        private final BonusStageProvider provider;

        SlotRewindServices(BonusStageProvider provider) {
            this.provider = provider;
        }

        @Override
        public BonusStageProvider bonusStageProviderOrNull() {
            return provider;
        }
    }
}
