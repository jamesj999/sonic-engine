package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.objects.bosses.LbzFinalBoss1Instance;
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
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestLbzFinalBossKosOwnerRewind {

    @Test
    void pendingRestoreRebindsDeathEggMiniatureParentWithoutReplacementWork() throws Exception {
        Harness harness = queueOwner();
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = ownerHandle(harness.boss(), timing);
        long nextOrdinal = nextKosOrdinal(timing);
        CompositeSnapshot snapshot = harness.fixture().gameplayMode().getRewindRegistry().capture();

        GameServices.level().getObjectManager().removeDynamicObject(harness.boss());
        harness.fixture().gameplayMode().getRewindRegistry().restore(snapshot);
        LbzFinalBoss1Instance restored = activeBoss();

        restored.update(1, harness.fixture().sprite());

        assertEquals(expected, field(restored, "deathEggSmallArtHandle"));
        assertEquals(expected, ownerHandle(restored, timing));
        assertEquals(nextOrdinal, nextKosOrdinal(timing),
                "pending restore must rebind the original Death Egg miniature KosM parent");
    }

    @Test
    void readyRestoreClaimsDeathEggMiniatureParentWithoutReplacementWork() throws Exception {
        Harness harness = queueOwner();
        HardwareTimingService timing = GameServices.hardwareTiming();
        HardwareWorkHandle expected = ownerHandle(harness.boss(), timing);
        long nextOrdinal = nextKosOrdinal(timing);
        drain(timing);
        CompositeSnapshot snapshot = harness.fixture().gameplayMode().getRewindRegistry().capture();

        GameServices.level().getObjectManager().removeDynamicObject(harness.boss());
        harness.fixture().gameplayMode().getRewindRegistry().restore(snapshot);
        LbzFinalBoss1Instance restored = activeBoss();

        restored.update(1, harness.fixture().sprite());

        assertFalse(timing.isPending(expected));
        assertTrue((boolean) field(restored, "deathEggSmallArtLoaded"));
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
        LbzFinalBoss1Instance restored = activeBoss();
        long nextOrdinal = nextKosOrdinal(timing);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> restored.update(1, harness.fixture().sprite()));

        assertTrue(thrown.getMessage().contains("cannot find Death Egg miniature KosM ordinal"));
        assertEquals(nextOrdinal, nextKosOrdinal(timing));
    }

    private static Harness queueOwner() throws Exception {
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(Sonic3kZoneIds.ZONE_LBZ, 1)
                .build();
        GameServices.level().getObjectManager().setRewindInPlaceRestoreEnabledForTest(false);
        LbzFinalBoss1Instance boss = GameServices.level().getObjectManager()
                .createDynamicObject(() -> new LbzFinalBoss1Instance(new ObjectSpawn(
                        0x44A0, 0x0780, Sonic3kObjectIds.LBZ_FINAL_BOSS_1,
                        0, 0, false, 0)));
        invoke(boss, "queueDeathEggSmallArt");
        return new Harness(fixture, boss);
    }

    private static LbzFinalBoss1Instance activeBoss() {
        return GameServices.level().getObjectManager().getActiveObjects().stream()
                .filter(LbzFinalBoss1Instance.class::isInstance)
                .map(LbzFinalBoss1Instance.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("restored LBZ final boss is absent"));
    }

    private static HardwareWorkHandle ownerHandle(
            LbzFinalBoss1Instance boss, HardwareTimingService timing) throws Exception {
        long ordinal = (long) field(boss, "deathEggSmallArtOrdinal");
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

    private static void invoke(Object target, String name) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(target);
    }

    private record Harness(HeadlessTestFixture fixture, LbzFinalBoss1Instance boss) {
    }
}
