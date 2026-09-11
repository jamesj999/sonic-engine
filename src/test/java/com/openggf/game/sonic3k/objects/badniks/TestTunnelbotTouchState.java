package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTunnelbotTouchState {

    @Test
    void collisionResponseListDereferencesLivePostMovementPosition() {
        TunnelbotBadnikInstance tunnelbot = new TunnelbotBadnikInstance(
                new ObjectSpawn(0x0A20, 0x0C26, Sonic3kObjectIds.TUNNELBOT,
                        0, 0, false, 0));

        assertTrue(tunnelbot.usesCurrentTouchResponseState());
    }
}
