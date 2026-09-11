package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.LbzEndBossInstance;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestLbzEndBossKosOwnerRewind {

    @Test
    void pendingRestoreRebindsConstructionParentWithoutReplacementWork() throws Exception {
        Harness harness = queueOwner();
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = ownerHandle(harness.boss(), timing);
        long nextOrdinal = nextKosOrdinal(timing);
        CompositeSnapshot snapshot = harness.fixture().gameplayMode().getRewindRegistry().capture();

        GameServices.level().getObjectManager().removeDynamicObject(harness.boss());
        harness.fixture().gameplayMode().getRewindRegistry().restore(snapshot);
        LbzEndBossInstance restored = activeBoss();

        restored.update(1, harness.fixture().sprite());

        assertEquals(expected, field(restored, "bossArtHandle"));
        assertEquals(expected, ownerHandle(restored, timing));
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "rewind recreation must not rerun the live construction submission");
    }

    @Test
    void readyRestoreClaimsConstructionParentWithoutReplacementWork() throws Exception {
        Harness harness = queueOwner();
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = ownerHandle(harness.boss(), timing);
        long nextOrdinal = nextKosOrdinal(timing);
        drain(timing);
        CompositeSnapshot snapshot = harness.fixture().gameplayMode().getRewindRegistry().capture();

        GameServices.level().getObjectManager().removeDynamicObject(harness.boss());
        harness.fixture().gameplayMode().getRewindRegistry().restore(snapshot);
        LbzEndBossInstance restored = activeBoss();

        restored.update(1, harness.fixture().sprite());

        assertFalse(timing.isPending(expected));
        assertTrue((boolean) field(restored, "bossArtLoaded"));
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "ready restore must claim the original parent instead of resubmitting it");
    }

    @Test
    void restoredOrdinalWithoutPendingParentFailsClosed() throws Exception {
        Harness harness = queueOwner();
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = ownerHandle(harness.boss(), timing);
        drain(timing);
        S3kRuntimeArtCoordinator.from(GameServices.runtimeArtCoordinator())
                .moduleQueue().claim(expected);
        CompositeSnapshot snapshot = harness.fixture().gameplayMode().getRewindRegistry().capture();

        GameServices.level().getObjectManager().removeDynamicObject(harness.boss());
        harness.fixture().gameplayMode().getRewindRegistry().restore(snapshot);
        LbzEndBossInstance restored = activeBoss();
        long nextOrdinal = nextKosOrdinal(timing);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> restored.update(1, harness.fixture().sprite()));

        assertTrue(thrown.getMessage().contains("cannot find KosM ordinal"));
        assertEquals(nextOrdinal, nextKosOrdinal(timing));
    }

    private static Harness queueOwner() {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 1)
                .build();
        GameServices.level().getObjectManager().setRewindInPlaceRestoreEnabledForTest(false);
        LbzEndBossInstance boss = GameServices.level().getObjectManager()
                .createDynamicObject(() -> new LbzEndBossInstance(new ObjectSpawn(
                        0x3B00, 0x05F8, 0xCB, 0, 0, false, 0x05F8)));
        return new Harness(fixture, boss);
    }

    private static LbzEndBossInstance activeBoss() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(LbzEndBossInstance.class::isInstance)
                .map(LbzEndBossInstance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("restored LBZ end boss is absent"));
    }

    private static HardwareWorkHandle ownerHandle(
            LbzEndBossInstance boss, HardwareTimingService timing) throws Exception {
        long ordinal = (long) field(boss, "bossArtOrdinal");
        return timing.pendingHandle(HardwareWorkKind.KOS_MODULE_QUEUE, ordinal).orElseThrow();
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

    private record Harness(HeadlessTestFixture fixture, LbzEndBossInstance boss) {
    }
}
