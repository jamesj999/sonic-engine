package com.openggf.testmode;

import com.openggf.control.InputHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTracePlaybackSpeedController {

    private static final int LEFT = 263;  // GLFW_KEY_LEFT
    private static final int RIGHT = 262; // GLFW_KEY_RIGHT

    private InputHandler input;
    private TracePlaybackSpeedController controller;

    @BeforeEach
    void setup() {
        input = mock(InputHandler.class);
        controller = new TracePlaybackSpeedController();
    }

    private void press(int key) {
        when(input.isKeyPressed(LEFT)).thenReturn(key == LEFT);
        when(input.isKeyPressed(RIGHT)).thenReturn(key == RIGHT);
        controller.handleInput(input, false, LEFT, RIGHT);
    }

    private void pressBlocked(int key) {
        when(input.isKeyPressed(LEFT)).thenReturn(key == LEFT);
        when(input.isKeyPressed(RIGHT)).thenReturn(key == RIGHT);
        controller.handleInput(input, true, LEFT, RIGHT);
    }

    @Test
    void startsAtRealTimeAndClimbsTheLadderOnRight() {
        assertEquals(1.0, controller.rate());
        assertFalse(controller.isFastForwarding());

        press(RIGHT);
        assertEquals(1.5, controller.rate());
        assertTrue(controller.isFastForwarding());

        press(RIGHT);
        assertEquals(2.0, controller.rate());
        press(RIGHT);
        assertEquals(3.0, controller.rate());
        press(RIGHT);
        assertEquals(5.0, controller.rate());
    }

    @Test
    void ladderSaturatesAtBothEnds() {
        for (int i = 0; i < 10; i++) {
            press(RIGHT);
        }
        assertEquals(5.0, controller.rate());

        for (int i = 0; i < 10; i++) {
            press(LEFT);
        }
        assertEquals(1.0, controller.rate());
        assertFalse(controller.isFastForwarding());
    }

    @Test
    void blockedInputLeavesTheLadderWhereItIs() {
        press(RIGHT);
        press(RIGHT);

        pressBlocked(RIGHT);
        pressBlocked(LEFT);

        assertEquals(2.0, controller.rate());
    }

    @Test
    void realTimeAsksForNoExtraSteps() {
        for (int frame = 0; frame < 10; frame++) {
            assertEquals(0, controller.consumeExtraSteps());
        }
    }

    @Test
    void wholeRatesAskForTheSameExtraStepsEveryFrame() {
        press(RIGHT);
        press(RIGHT); // 2.0
        for (int frame = 0; frame < 5; frame++) {
            assertEquals(1, controller.consumeExtraSteps());
        }

        press(RIGHT); // 3.0
        for (int frame = 0; frame < 5; frame++) {
            assertEquals(2, controller.consumeExtraSteps());
        }
    }

    @Test
    void fractionalRateAlternatesAndAveragesOutOverTime() {
        press(RIGHT); // 1.5
        assertEquals(0, controller.consumeExtraSteps());
        assertEquals(1, controller.consumeExtraSteps());
        assertEquals(0, controller.consumeExtraSteps());
        assertEquals(1, controller.consumeExtraSteps());

        int total = 4; // 4 outer frames already consumed above
        int extra = 2;
        for (int frame = 0; frame < 96; frame++) {
            extra += controller.consumeExtraSteps();
            total++;
        }
        assertEquals(150, total + extra,
                "100 outer frames at 1.5x must advance 150 gameplay steps");
    }

    @Test
    void resetDropsBackToRealTimeAndDiscardsTheCarriedFraction() {
        press(RIGHT); // 1.5
        controller.consumeExtraSteps(); // leaves 0.5 carried

        controller.reset();

        assertEquals(1.0, controller.rate());
        assertEquals(0, controller.consumeExtraSteps(),
                "the carried half-step must not survive the reset");
    }

    @Test
    void labelsDropTheDecimalOnWholeRates() {
        press(RIGHT);
        assertEquals("1.5x", controller.label());
        press(RIGHT);
        assertEquals("2x", controller.label());
        press(RIGHT);
        assertEquals("3x", controller.label());
        press(RIGHT);
        assertEquals("5x", controller.label());
    }

    @Test
    void rateDisplayCarriesTheLeftRightAffordanceAtBothEndsOfTheLadder() {
        assertEquals("< 1x >", controller.rateDisplay());
        press(RIGHT);
        assertEquals("< 1.5x >", controller.rateDisplay());
        for (int i = 0; i < 10; i++) {
            press(RIGHT);
        }
        assertEquals("< 5x >", controller.rateDisplay(),
                "both arrows stay drawn at the top rung so the line keeps its width");
    }

    @Test
    void noLadderEntryExceedsTheDeclaredExtraStepCeiling() {
        for (int i = 0; i < 10; i++) {
            press(RIGHT);
        }
        for (int frame = 0; frame < 20; frame++) {
            assertTrue(controller.consumeExtraSteps()
                    <= TracePlaybackSpeedController.maxExtraStepsPerFrame());
        }
    }
}
