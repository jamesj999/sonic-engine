package com.openggf.game.sonic2.objects;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.Sonic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestMTZSpinTubeObjectInstance {

    @BeforeEach
    void setUp() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void captureSetsRollAnimationWithoutSettingRollingStatusBit() {
        ObjectSpawn spawn = new ObjectSpawn(0x07D8, 0x0330, 0x67, 0x08, 0, false, 0x0330);
        MTZSpinTubeObjectInstance tube = new MTZSpinTubeObjectInstance(spawn);
        Sonic sonic = new Sonic("sonic", (short) 0x07D8, (short) 0x0330);
        sonic.setSubpixelRaw(0x7F00, 0x9800);

        tube.update(0, sonic);

        assertFalse(sonic.getRolling(),
                "Obj67 writes AniIDSonAni_Roll but does not set status.player.rolling");
        assertEquals(Sonic2AnimationIds.ROLL.id(), sonic.getAnimationId());
        assertTrue(sonic.getAir());
        assertTrue(sonic.isObjectControlled());
        assertEquals(0x0800, sonic.getGSpeed() & 0xFFFF);
        assertEquals(0x7F00, sonic.getXSubpixelRaw());
        assertEquals(0x9800, sonic.getYSubpixelRaw());
    }
}
