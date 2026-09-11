package com.openggf.game.sonic3k;

import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;

class TestS3kSidekickIntroPresentationGate {

    @Test
    void sszDormantCpuBranchDoesNotArmAizOrIczPresentationLatch() {
        Sonic3kLevelEventManager events = new Sonic3kLevelEventManager();
        events.initLevel(Sonic3kZoneIds.ZONE_SSZ, 0);

        assertFalse(events.shouldEnterSidekickDormantMarker(mock(AbstractPlayableSprite.class)),
                "SSZ's separate ROM $0A00 branch must not acquire the AIZ/ICZ presentation gate");
    }
}
