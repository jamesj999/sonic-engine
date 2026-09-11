package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchResponseProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestNebulaBadnikInstance {
    @Test
    void nebulaUsesOwnDeleteBehindScreenInsteadOfManagerOutOfRange() {
        NebulaBadnikInstance nebula = new NebulaBadnikInstance(
                new ObjectSpawn(0x0560, 0x0070, 0x99, 0x12, 0, false, 0));

        assertTrue(nebula.isPersistent());
    }

    @Test
    void exposesStandardEnemyTouchResponseProfileWhileKeepingDynamicFlags() {
        NebulaBadnikInstance nebula = new NebulaBadnikInstance(
                new ObjectSpawn(0x0560, 0x0070, 0x99, 0x12, 0, false, 0));

        assertEquals(TouchResponseProfile.standardEnemy(), nebula.getTouchResponseProfile());
        assertEquals(0x06, nebula.getCollisionFlags());
    }
}
