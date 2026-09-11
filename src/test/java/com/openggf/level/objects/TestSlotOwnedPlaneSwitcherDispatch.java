package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.objects.Sonic2LayerSwitcherObjectInstance;
import com.openggf.game.sonic3k.objects.Sonic3kPathSwapObjectInstance;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSlotOwnedPlaneSwitcherDispatch {

    @Test
    void sonic2OccupantDispatchesNativePlayersOnceInRomOrder() {
        ObjectSpawn spawn = switcherSpawn(0x03);
        Sonic2LayerSwitcherObjectInstance switcher =
                new Sonic2LayerSwitcherObjectInstance(spawn, "LayerSwitcher");
        DispatchHarness harness = new DispatchHarness(switcher, true);

        switcher.update(0, harness.p1);

        harness.assertP1ThenP2(spawn);
    }

    @Test
    void sonic3kOccupantDispatchesNativePlayersOnceInRomOrder() {
        ObjectSpawn spawn = switcherSpawn(0x02);
        Sonic3kPathSwapObjectInstance switcher = new Sonic3kPathSwapObjectInstance(spawn);
        DispatchHarness harness = new DispatchHarness(switcher, true);

        switcher.update(0, harness.p1);

        harness.assertP1ThenP2(spawn);
    }

    @Test
    void absentNativeP2IsANoOp() {
        ObjectSpawn spawn = switcherSpawn(0x02);
        Sonic3kPathSwapObjectInstance switcher = new Sonic3kPathSwapObjectInstance(spawn);
        DispatchHarness harness = new DispatchHarness(switcher, false);

        switcher.update(0, harness.p1);

        verify(harness.manager).applyPlaneSwitcher(spawn, harness.p1);
        verify(harness.manager, never()).applyPlaneSwitcher(spawn, harness.p2);
    }

    private static ObjectSpawn switcherSpawn(int objectId) {
        return new ObjectSpawn(0x06A8, 0x02C8, objectId, 0x11, 0, false, 0);
    }

    private static final class DispatchHarness {
        private final ObjectManager manager = mock(ObjectManager.class);
        private final PlayableEntity p1 = mock(PlayableEntity.class);
        private final PlayableEntity p2 = mock(PlayableEntity.class);

        private DispatchHarness(AbstractObjectInstance switcher, boolean includeP2) {
            ObjectServices services = mock(ObjectServices.class);
            when(services.objectManager()).thenReturn(manager);
            when(services.playerQuery()).thenReturn(new ObjectPlayerQuery(
                    () -> p1,
                    () -> includeP2 ? List.of(p2) : List.of()));
            switcher.setServices(services);
        }

        private void assertP1ThenP2(ObjectSpawn spawn) {
            InOrder order = inOrder(manager);
            order.verify(manager).applyPlaneSwitcher(spawn, p1);
            order.verify(manager).applyPlaneSwitcher(spawn, p2);
            order.verifyNoMoreInteractions();
        }
    }
}
