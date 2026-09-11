package com.openggf;

import com.openggf.game.ShieldType;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestBonusStageShieldRestoreOnTitleCardExit {

    @Test
    void applyPendingBonusStageShieldRestore_grantsAndClearsPendingShield() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        GameLoop loop = new GameLoop();
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);

        loop.setPendingBonusStageShieldRestoreForTest(ShieldType.BUBBLE);
        loop.applyPendingBonusStageShieldRestore(player);

        assertTrue(player.hasShield());
        assertEquals(ShieldType.BUBBLE, player.getShieldType());
        assertFalse(loop.hasPendingBonusStageShieldRestoreForTest());
    }
}


