package com.openggf.game.sonic2.objects;

import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic2ObjectCoordinateParity {

    @Test
    void fallingPillarTriggerUsesPlayerRomCentrePosition() {
        int pillarX = 0x1400;
        FallingPillarObjectInstance child = new FallingPillarObjectInstance(
                new ObjectSpawn(pillarX, 0x0300, 0x23, 0, 0, false, 0), "FallingPillar")
                .createChild();
        child.setServices(new StubObjectServices());

        TestablePlayableSprite player = standingPlayer();
        player.setCentreX((short) (pillarX - 0x7F));
        player.setCentreY((short) 0x0330);

        child.update(1, player);
        child.update(2, player);

        assertEquals(pillarX + 1, child.getX(),
                "Obj23 proximity uses player x_pos, not the top-left sprite bound");
    }

    @Test
    void pipeExitSpringTubeCheckUsesPlayerRomCentrePosition() throws Exception {
        int springX = 0x1800;
        int springY = 0x0400;
        PipeExitSpringObjectInstance spring = new PipeExitSpringObjectInstance(
                new ObjectSpawn(springX, springY, 0x7B, 0, 0, false, 0), "PipeExitSpring");

        TestablePlayableSprite player = standingPlayer();
        player.setCentreX((short) (springX - 0x10));
        player.setCentreY((short) springY);

        Method check = PipeExitSpringObjectInstance.class
                .getDeclaredMethod("isPlayerInTubeBelow", com.openggf.sprites.playable.AbstractPlayableSprite.class);
        check.setAccessible(true);

        assertTrue((Boolean) check.invoke(spring, player),
                "Obj7B tube bounds compare against player x_pos/y_pos centre coordinates");
    }

    private static TestablePlayableSprite standingPlayer() {
        TestablePlayableSprite player = new TestablePlayableSprite("sonic", (short) 0, (short) 0);
        player.setWidth(18);
        player.setHeight(38);
        return player;
    }
}
