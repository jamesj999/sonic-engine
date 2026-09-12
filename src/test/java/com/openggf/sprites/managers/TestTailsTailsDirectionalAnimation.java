package com.openggf.sprites.managers;

import com.openggf.physics.Direction;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestTailsTailsDirectionalAnimation {

    @Test
    void zeroVelocityDirectionalFrameUsesTheSameAdjustedAngleForBankAndFlips() {
        TestablePlayableSprite tails = new TestablePlayableSprite("tails", (short) 0, (short) 0);
        PlayerSpriteRenderer renderer = mock(PlayerSpriteRenderer.class);
        TailsTailsController controller = new TailsTailsController(tails, renderer, false);
        tails.setAnimationId(2);
        tails.setDirection(Direction.RIGHT);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0);

        controller.update();
        controller.draw();

        // CalcAngle_Zero is $40. Right-facing Obj05 complements it and adds $10,
        // producing $CF: bank $08, then both render flip bits (s2.asm:41485-41516).
        verify(renderer).drawFrame(eq(0x51), anyInt(), anyInt(), eq(true), eq(true));
    }
}
