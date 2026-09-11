package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.boss.AbstractBossInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestCnzMinibossInstanceBase {

    private static ObjectSpawn spawn() {
        return new ObjectSpawn(0x3240, 0x02B8, Sonic3kObjectIds.CNZ_MINIBOSS,
                0, 0, false, 0);
    }

    @Test
    void extendsAbstractBossInstance() {
        CnzMinibossInstance boss = new CnzMinibossInstance(spawn());
        assertTrue(boss instanceof AbstractBossInstance,
                "CnzMinibossInstance must extend AbstractBossInstance after workstream-D T3");
    }

    @Test
    void reportsRomHitCount() {
        CnzMinibossInstance boss = new CnzMinibossInstance(spawn());
        assertEquals(0x04,
                boss.getRemainingHits(),
                "state.hitCount must initialise from the real $45 four-hit counter, not collision_property(a0)");
        assertEquals(0x04, Sonic3kConstants.CNZ_MINIBOSS_HIT_COUNT,
                "CNZ_MINIBOSS_HIT_COUNT should be an alias for the real damage counter");
    }

    @Test
    void preservesArenaChunkHook() {
        CnzMinibossInstance boss = new CnzMinibossInstance(spawn());
        int originalY = boss.getCentreY();
        boss.onArenaChunkDestroyed();
        assertEquals(originalY, boss.getCentreY(),
                "CNZMiniboss_MoveDown arms Lower2 from the Events_bg+$04 row signal; "
                        + "it must not jump centreY by a full row immediately");
    }

    @Test
    void initialStateMatchesSpawn() {
        CnzMinibossInstance boss = new CnzMinibossInstance(spawn());
        assertEquals(0x3240, boss.getCentreX(),
                "centreX mirrors spawn x (0x3240)");
        assertEquals(0x02B8, boss.getCentreY(),
                "centreY mirrors spawn y (0x02B8)");
    }
}
