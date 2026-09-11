package com.openggf.level.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.snapshot.ObjectManagerSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestObjectSolidContactController {

    @Test
    void resetClearsInlineSupportAndStaleSupportLossState() {
        ObjectSolidContactController controller =
                new ObjectSolidContactController(mock(ObjectManager.class));
        PlayableEntity player = mock(PlayableEntity.class);

        controller.markObjectSupportThisFrame(player);
        controller.forceAirOnStaleObjectSupportLoss(player);
        ObjectManagerSnapshot.SolidContactState before = controller.captureRewindState();
        assertEquals(1, before.inlineSupportedPlayers().size());
        assertEquals(1, before.forceAirOnStaleSupportLoss().size());

        controller.reset();

        ObjectManagerSnapshot.SolidContactState after = controller.captureRewindState();
        assertTrue(after.inlineSupportedPlayers().isEmpty(),
                "reset must clear per-frame inline support players");
        assertTrue(after.forceAirOnStaleSupportLoss().isEmpty(),
                "reset must clear stale-support force-air players");
    }
}
