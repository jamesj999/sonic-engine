package com.openggf.game.sonic2.objects.badniks;

import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestChopChopBadnikInstance {

    @Test
    void detectionRejectsExactUpperHorizontalBoundary() throws Exception {
        ProbeChopChop chopChop = newChopChop();
        AbstractPlayableSprite player = playerAt(0x0A7F, 0x0560);

        chopChop.step(player);
        chopChop.step(player);

        assertEquals("PATROLLING", stateName(chopChop),
                "Obj91_TestHorizontalDist uses cmpi.w #$A0 then blo, so exactly 0xA0 must not charge");
    }

    @Test
    void detectionAcceptsOnePixelInsideUpperHorizontalBoundary() throws Exception {
        ProbeChopChop chopChop = newChopChop();
        AbstractPlayableSprite player = playerAt(0x0A80, 0x0560);

        chopChop.step(player);
        chopChop.step(player);

        assertEquals("WAITING", stateName(chopChop),
                "Obj91 should prepare a charge when the post-ObjectMove distance is 0x9F");
    }

    @Test
    void initFrameReturnsBeforeMainObjectMove() {
        ProbeChopChop chopChop = newChopChop();
        int initialX = chopChop.getX();

        chopChop.step(playerAt(0x0A80, 0x0560));

        assertEquals(initialX, chopChop.getX(),
                "Obj91_Init sets timers/x_vel and returns before Obj91_Main can call ObjectMove");
    }

    private static ProbeChopChop newChopChop() {
        ProbeChopChop chopChop = new ProbeChopChop(new ObjectSpawn(
                0x0B20, 0x0560, Sonic2ObjectIds.CHOP_CHOP, 0, 0, false, 0));
        chopChop.setServices(new TestObjectServices());
        return chopChop;
    }

    private static AbstractPlayableSprite playerAt(int x, int y) {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.getCentreX()).thenReturn((short) x);
        when(player.getCentreY()).thenReturn((short) y);
        return player;
    }

    private static String stateName(ChopChopBadnikInstance chopChop) throws Exception {
        Field field = ChopChopBadnikInstance.class.getDeclaredField("state");
        field.setAccessible(true);
        return field.get(chopChop).toString();
    }

    private static final class ProbeChopChop extends ChopChopBadnikInstance {
        private ProbeChopChop(ObjectSpawn spawn) {
            super(spawn);
        }

        private void step(AbstractPlayableSprite player) {
            updateMovement(0, player);
        }
    }
}
