package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestCutsceneKnucklesLbz1CollapseChild {

    @Test
    void bossExplosionControlPlaysExplodeSfxWhenEmittingChild() {
        CutsceneKnucklesLbz1Instance parent = new CutsceneKnucklesLbz1Instance(new ObjectSpawn(
                0x3BF4, 0x00EC, 0, 0x14, 0, false, 0));
        CutsceneKnucklesLbz1CollapseChild child = new CutsceneKnucklesLbz1CollapseChild(parent, 0);
        CapturingServices services = new CapturingServices();
        child.setServices(services);

        child.update(0, null);

        assertEquals(Sonic3kSfx.EXPLODE.id, services.lastSfx,
                "sonic3k.asm Obj_BossExplosion1 plays sfx_Explode when the controller emits a child.");
    }

    private static final class CapturingServices extends TestObjectServices {
        private int lastSfx = -1;

        @Override
        public void playSfx(int soundId) {
            lastSfx = soundId;
        }
    }
}
