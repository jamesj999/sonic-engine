package com.openggf.game.sonic2.objects;

import com.openggf.game.GameRng;
import com.openggf.level.objects.TestObjectServices;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestBubbleObjectInstance {
    @Test
    void romInitializedBubbleConsumesAngleRngOnFirstObjectPass() {
        GameRng control = new GameRng(GameRng.Flavour.S1_S2, 0xB4BD7B3AL);
        control.nextRaw();

        GameRng actual = new GameRng(GameRng.Flavour.S1_S2, 0xB4BD7B3AL);
        BubbleObjectInstance bubble = new BubbleObjectInstance(0x0C9B, 0x076A, 1, 0, true);
        bubble.setServices(new TestObjectServices().withRng(actual));

        assertEquals(0xB4BD7B3AL, actual.getSeed());

        bubble.update(0, null);

        assertEquals(control.getSeed(), actual.getSeed());
    }
}
