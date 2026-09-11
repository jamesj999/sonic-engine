package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGumballMachineExitTrigger {
    private static final int TRIGGER_X = 0x1000;
    private static final int TRIGGER_Y = 0x2000;

    @Test
    void inclusiveRomRangeRequestsExitAtEveryBoundary() {
        int[][] boundaryOffsets = {
                {-0x100, 0},
                {0x200, 0},
                {0, -0x10},
                {0, 0x40}
        };

        for (int[] offset : boundaryOffsets) {
            CountingExitServices services = new CountingExitServices();
            GumballMachineObjectInstance.ExitTriggerChild trigger = trigger(services);

            trigger.update(0, playerAt(offset[0], offset[1]));

            assertEquals(1, services.exitRequests,
                    "expected inclusive exit at dx=" + offset[0] + ", dy=" + offset[1]);
        }
    }

    @Test
    void rejectsEveryPositionImmediatelyOutsideRomRange() {
        int[][] outsideOffsets = {
                {-0x101, 0},
                {0x201, 0},
                {0, -0x11},
                {0, 0x41}
        };

        for (int[] offset : outsideOffsets) {
            CountingExitServices services = new CountingExitServices();
            GumballMachineObjectInstance.ExitTriggerChild trigger = trigger(services);

            trigger.update(0, playerAt(offset[0], offset[1]));

            assertEquals(0, services.exitRequests,
                    "expected no exit at dx=" + offset[0] + ", dy=" + offset[1]);
        }
    }

    @Test
    void requestsExitExactlyOnceWhilePlayerRemainsInRange() {
        CountingExitServices services = new CountingExitServices();
        GumballMachineObjectInstance.ExitTriggerChild trigger = trigger(services);
        PlayableEntity player = playerAt(0, 0);

        trigger.update(0, player);
        trigger.update(1, player);

        assertEquals(1, services.exitRequests);
    }

    private static GumballMachineObjectInstance.ExitTriggerChild trigger(
            CountingExitServices services) {
        GumballMachineObjectInstance.ExitTriggerChild trigger =
                new GumballMachineObjectInstance.ExitTriggerChild(
                        new ObjectSpawn(TRIGGER_X, TRIGGER_Y, 0, 0, 0, false, 0));
        trigger.setServices(services);
        return trigger;
    }

    private static PlayableEntity playerAt(int dx, int dy) {
        PlayableEntity player = mock(PlayableEntity.class);
        when(player.getCentreX()).thenReturn((short) (TRIGGER_X + dx));
        when(player.getCentreY()).thenReturn((short) (TRIGGER_Y + dy));
        return player;
    }

    private static final class CountingExitServices extends TestObjectServices {
        private int exitRequests;

        @Override
        public void requestBonusStageExit() {
            exitRequests++;
        }
    }
}
