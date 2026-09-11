package com.openggf.game.sonic3k.objects;

import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.bosses.HczEndBossGeyserCutscene;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestS3kObjectKosOwnerRewind {

    @Test
    void largeFanRebindsPendingAndReadyArtByCapturedOrdinal()
            throws Exception {
        OwnerContext context = ownerContext();
        HCZLargeFanObjectInstance fan = new HCZLargeFanObjectInstance(
                new ObjectSpawn(0x200, 0x200, 0x39, 0, 0, false, 0));
        fan.setServices(context.services());
        invoke(fan, "queueFanArt");

        verifyPendingAndReadyRestore(
                fan, context,
                List.of("artQueue", "artHandle"),
                () -> invoke(fan, "rebindArtAfterRestore"),
                () -> {
                    setField(fan, "phase", 1);
                    fan.update(1, null);
                });
    }

    @Test
    void waterWallRebindsPendingAndReadyArtByCapturedOrdinal()
            throws Exception {
        OwnerContext context = ownerContext();
        HCZWaterWallObjectInstance wall = new HCZWaterWallObjectInstance(
                new ObjectSpawn(0x200, 0x200, 0x3B, 0, 0, false, 0));
        wall.setServices(context.services());
        invoke(wall, "queueArtIfNeeded",
                new Class<?>[] {int.class},
                Sonic3kConstants.ART_KOSM_HCZ_GEYSER_HORZ_ADDR);

        verifyPendingAndReadyRestore(
                wall, context,
                List.of("artQueue", "artHandle"),
                () -> invoke(wall, "rebindArtAfterRestore"),
                () -> assertTrue((boolean) invoke(
                        wall, "queueArtIfNeeded",
                        new Class<?>[] {int.class},
                        Sonic3kConstants.ART_KOSM_HCZ_GEYSER_HORZ_ADDR)));
    }

    @Test
    void endGeyserRebindsPendingAndReadyArtByCapturedOrdinal()
            throws Exception {
        OwnerContext context = ownerContext();
        HczEndBossGeyserCutscene geyser =
                new HczEndBossGeyserCutscene(0x200, 0x200);
        geyser.setServices(context.services());
        invoke(geyser, "serviceQueuedArt");

        verifyPendingAndReadyRestore(
                geyser, context,
                List.of("artQueue", "artHandle"),
                () -> invoke(geyser, "rebindArtAfterRestore"),
                () -> invoke(geyser, "serviceQueuedArt"));
    }

    @Test
    void planeIntroRebindsBothPendingAndReadyArtOrdinals()
            throws Exception {
        OwnerContext context = ownerContext();
        AizPlaneIntroInstance intro = new AizPlaneIntroInstance(
                new ObjectSpawn(0x60, 0x30, 0x00, 0, 0, false, 0));
        intro.setServices(context.services());
        invoke(intro, "queueIntroSpriteArt");

        verifyPendingAndReadyRestore(
                intro, context,
                List.of("introSpriteArtQueue",
                        "planeArtHandle", "emeraldArtHandle"),
                () -> invoke(intro, "rebindIntroSpriteArtAfterRestore"),
                () -> {
                    invoke(intro, "rebindIntroSpriteArtAfterRestore");
                    invoke(intro, "claimIntroSpriteArtIfReady");
                });
    }

    private static void verifyPendingAndReadyRestore(
            AbstractObjectInstance owner,
            OwnerContext context,
            List<String> transientFields,
            ThrowingRunnable rebind,
            ThrowingRunnable consume) throws Exception {
        HardwareTimingService timing = context.timing();
        CompositeSnapshot pendingSnapshot = context.rewindRegistry().capture();
        List<HardwareWorkHandle> expectedHandles =
                List.copyOf(timing.pendingHandles());
        long nextOrdinal = nextKosOrdinal(timing);
        clear(owner, transientFields);
        context.rewindRegistry().restore(pendingSnapshot);
        rebind.run();
        assertEquals(expectedHandles, timing.pendingHandles());
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "pending restore must not submit replacement work");

        drain(context);
        CompositeSnapshot readySnapshot = context.rewindRegistry().capture();
        clear(owner, transientFields);
        context.rewindRegistry().restore(readySnapshot);
        consume.run();
        assertTrue(timing.pendingHandles().isEmpty(),
                "ready restore must claim the original work");
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "ready restore must not submit replacement work");
    }

    private static OwnerContext ownerContext() {
        var gameplayMode = TestEnvironment.activeGameplayMode();
        ObjectServices services = TestEnvironment.objectServices();
        return new OwnerContext(
                services.hardwareTiming(),
                services.runtimeArtCoordinator(),
                gameplayMode.getRewindRegistry(),
                services);
    }

    private static void drain(OwnerContext context) {
        HardwareTimingService timing = context.timing();
        for (int frame = 0;
                frame < 4096
                        && timing.incompleteCount(
                        HardwareWorkKind.KOS_MODULE_QUEUE) > 0;
                frame++) {
            HardwareBoundaryPump.service(timing, context.runtimeArtCoordinator(),
                    HardwareServiceBoundary.PRE_MAIN_LOOP);
            HardwareBoundaryPump.service(timing, context.runtimeArtCoordinator(),
                    HardwareServiceBoundary.POST_OBJECTS);
        }
        assertEquals(0,
                timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    private static long nextKosOrdinal(HardwareTimingService timing) {
        return timing.capture().nextOrdinals().getOrDefault(
                HardwareWorkKind.KOS_MODULE_QUEUE, 0L);
    }

    private static void clear(Object owner, List<String> fields)
            throws Exception {
        for (String field : fields) {
            setField(owner, field, null);
        }
    }

    private static Object invoke(Object target, String name)
            throws Exception {
        return invoke(target, name, new Class<?>[0]);
    }

    private static Object invoke(
            Object target, String name, Class<?>[] parameterTypes,
            Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(
                name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private record OwnerContext(
            HardwareTimingService timing,
            RuntimeArtCoordinator runtimeArtCoordinator,
            RewindRegistry rewindRegistry,
            ObjectServices services) {
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
