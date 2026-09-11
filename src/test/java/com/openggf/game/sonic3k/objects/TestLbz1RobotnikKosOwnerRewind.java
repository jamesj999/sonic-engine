package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.tests.HardwareBoundaryPump;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestLbz1RobotnikKosOwnerRewind {

    @Test
    void pendingRestoreRebindsMinibossParentWithoutNewOrdinal() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueAllThreeParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = minibossHandle(controller, timing);
        long nextOrdinal = nextKosOrdinal(timing);
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();

        restored.update(1, fixture.sprite());

        assertEquals(expected, field(restored, "minibossArtHandle"));
        assertEquals(expected, minibossHandle(restored, timing));
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "pending miniboss-art restore must not submit replacement work");
    }

    @Test
    void readyRestoreClaimsMinibossParentWithoutReplacementWork() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueAllThreeParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = minibossHandle(controller, timing);
        long nextOrdinal = nextKosOrdinal(timing);
        drain(timing);
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();

        restored.update(1, fixture.sprite());

        assertTrue(!timing.isPending(expected),
                "ready miniboss-art restore must claim the original KosM parent");
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "ready miniboss-art restore must not submit replacement work");
    }

    @Test
    void restoredMinibossOrdinalWithoutPendingParentFailsClosed() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueAllThreeParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        drain(timing);
        for (HardwareWorkHandle handle : ownerHandlesWithMiniboss(controller, timing)) {
            S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator())
                    .moduleQueue().claim(handle);
        }
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();
        setField(restored, "initialBoxArtOrdinal", -1L);
        setField(restored, "collapseBoxArtOrdinal", -1L);
        setField(restored, "minibossArtHandle", null);
        long nextOrdinal = nextKosOrdinal(timing);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> restored.update(1, fixture.sprite()));

        assertTrue(thrown.getMessage().contains("Missing restored LBZ miniboss"));
        assertEquals(nextOrdinal, nextKosOrdinal(timing));
    }

    @Test
    void pendingRestoreRebindsBothMinibossBoxParentsWithoutNewOrdinals() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueBothParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        List<HardwareWorkHandle> expected = ownerHandles(controller, timing);
        long nextOrdinal = nextKosOrdinal(timing);
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();

        restored.update(1, fixture.sprite());

        assertEquals(expected, ownerHandles(restored, timing),
                "pending restore must bind the original two KosM parent handles.");
        assertNotNull(field(restored, "initialBoxArtHandle"));
        assertNotNull(field(restored, "collapseBoxArtHandle"));
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "pending restore must not submit replacement work.");
    }

    @Test
    void readyRestoreClaimsBothMinibossBoxParentsWithoutReplacementWork() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueBothParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        List<HardwareWorkHandle> expected = ownerHandles(controller, timing);
        long nextOrdinal = nextKosOrdinal(timing);
        drain(timing);
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();

        restored.update(1, fixture.sprite());

        assertTrue(expected.stream().noneMatch(timing::isPending),
                "ready restore must claim each original KosM parent exactly once.");
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "ready restore must not submit replacement work.");
    }

    @Test
    void restoredInitialOrdinalWithoutPendingParentFailsClosed() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueBothParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        drain(timing);
        for (HardwareWorkHandle handle : ownerHandles(controller, timing)) {
            S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator())
                    .moduleQueue().claim(handle);
        }
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();
        setField(restored, "initialBoxArtHandle", null);
        setField(restored, "collapseBoxArtHandle", null);
        setField(restored, "collapseBoxArtOrdinal", -1L);
        long nextOrdinal = nextKosOrdinal(timing);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> restored.update(1, fixture.sprite()));

        assertTrue(thrown.getMessage().contains("Missing restored LBZ initial"),
                "a missing initial parent must not be replaced by a new submission.");
        assertEquals(nextOrdinal, nextKosOrdinal(timing));
    }

    @Test
    void restoredCollapseOrdinalWithoutPendingParentFailsClosed() throws Exception {
        HeadlessTestFixture fixture = lbzFixture();
        Lbz1RobotnikEventController controller = queueBothParentSites(fixture);
        HardwareTimingService timing = GameServices.hardwareTiming();
        drain(timing);
        for (HardwareWorkHandle handle : ownerHandles(controller, timing)) {
            S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator())
                    .moduleQueue().claim(handle);
        }
        CompositeSnapshot snapshot = captureWithTransientOwnerStateCleared(fixture, controller);

        GameServices.level().getObjectManager().removeDynamicObject(controller);
        fixture.gameplayMode().getRewindRegistry().restore(snapshot);
        Lbz1RobotnikEventController restored = activeController();
        setField(restored, "initialBoxArtHandle", null);
        setField(restored, "initialBoxArtOrdinal", -1L);
        setField(restored, "collapseBoxArtHandle", null);
        long nextOrdinal = nextKosOrdinal(timing);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> restored.update(1, fixture.sprite()));

        assertTrue(thrown.getMessage().contains("Missing restored LBZ collapse"),
                "a missing collapse parent must not be replaced by a new submission.");
        assertEquals(nextOrdinal, nextKosOrdinal(timing));
    }

    private static HeadlessTestFixture lbzFixture() {
        return HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 0)
                .build();
    }

    private static Lbz1RobotnikEventController queueBothParentSites(
            HeadlessTestFixture fixture) throws Exception {
        GameServices.level().getObjectManager().setRewindInPlaceRestoreEnabledForTest(false);
        Lbz1RobotnikEventController controller = GameServices.level().getObjectManager()
                .createDynamicObject(() -> new Lbz1RobotnikEventController(new ObjectSpawn(
                        0x3EC0, 0x01A0, Sonic3kObjectIds.LBZ1_ROBOTNIK, 0, 0, false, 0)));
        controller.update(0, fixture.sprite());
        controller.forceRoutineForTest(0x06);
        GameServices.camera().setX((short) 0x3B40);
        fixture.sprite().setCentreY((short) 0x01C0);
        fixture.sprite().setAir(false);
        controller.update(1, fixture.sprite());
        Sonic3kLevelEventManager manager =
                (Sonic3kLevelEventManager) GameServices.module().getLevelEventProvider();
        for (int frame = 1; frame <= 609; frame++) {
            manager.getLbzEvents().update(0, frame);
        }
        controller.update(610, fixture.sprite());
        return controller;
    }

    private static Lbz1RobotnikEventController queueAllThreeParentSites(
            HeadlessTestFixture fixture) throws Exception {
        Lbz1RobotnikEventController controller = queueBothParentSites(fixture);
        controller.forceInitializedForTest(0x3EC0, 0x012C, 0x0C, 0, -0x0400, false);
        controller.update(611, fixture.sprite());
        return controller;
    }

    private static Lbz1RobotnikEventController activeController() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(Lbz1RobotnikEventController.class::isInstance)
                .map(Lbz1RobotnikEventController.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("restored LBZ Robotnik controller is absent"));
    }

    private static List<HardwareWorkHandle> ownerHandles(
            Lbz1RobotnikEventController controller, HardwareTimingService timing) throws Exception {
        return List.of(
                timing.pendingHandle(HardwareWorkKind.KOS_MODULE_QUEUE,
                        (long) field(controller, "initialBoxArtOrdinal")).orElseThrow(),
                timing.pendingHandle(HardwareWorkKind.KOS_MODULE_QUEUE,
                        (long) field(controller, "collapseBoxArtOrdinal")).orElseThrow());
    }

    private static HardwareWorkHandle minibossHandle(
            Lbz1RobotnikEventController controller, HardwareTimingService timing) throws Exception {
        return timing.pendingHandle(HardwareWorkKind.KOS_MODULE_QUEUE,
                (long) field(controller, "minibossArtOrdinal")).orElseThrow();
    }

    private static List<HardwareWorkHandle> ownerHandlesWithMiniboss(
            Lbz1RobotnikEventController controller, HardwareTimingService timing) throws Exception {
        List<HardwareWorkHandle> boxHandles = ownerHandles(controller, timing);
        return List.of(boxHandles.get(0), boxHandles.get(1), minibossHandle(controller, timing));
    }

    private static CompositeSnapshot captureWithTransientOwnerStateCleared(
            HeadlessTestFixture fixture, Lbz1RobotnikEventController controller) throws Exception {
        setField(controller, "boxRig", null);
        setField(controller, "initialBoxArtHandle", null);
        setField(controller, "collapseBoxArtHandle", null);
        setField(controller, "minibossArtHandle", null);
        return fixture.gameplayMode().getRewindRegistry().capture();
    }

    private static void drain(HardwareTimingService timing) {
        for (int frame = 0; frame < 4096
                && timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE) > 0; frame++) {
            HardwareBoundaryPump.service(timing, GameServices.runtimeArtCoordinator(),
                    HardwareServiceBoundary.PRE_MAIN_LOOP);
            HardwareBoundaryPump.service(timing, GameServices.runtimeArtCoordinator(),
                    HardwareServiceBoundary.POST_OBJECTS);
        }
        assertEquals(0, timing.incompleteCount(HardwareWorkKind.KOS_MODULE_QUEUE));
    }

    private static long nextKosOrdinal(HardwareTimingService timing) {
        return timing.capture().nextOrdinals().getOrDefault(HardwareWorkKind.KOS_MODULE_QUEUE, 0L);
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

}
