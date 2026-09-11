package com.openggf.game.rewind;

import com.openggf.InputBindingFactory;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.RecordedInputSnapshots;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;

class TestLiveRewindInputSourceStartHeld {

    @Test
    void heldStartReplaysAsHeldAfterThePressEdgeFrame() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        InputHandler input = new InputHandler(InputBindingFactory.supplier(config));
        LiveRewindInputSource source = new LiveRewindInputSource();

        input.handleKeyEvent(config.getInt(SonicConfiguration.START), GLFW_PRESS);

        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);
        input.update();

        input.refreshLogicalSnapshot();
        source.appendFrame(input, config);

        Bk2FrameInput first = source.read(1);
        Bk2FrameInput second = source.read(2);

        var firstReplay = RecordedInputSnapshots.fromBk2(first, source.read(0));
        var secondReplay = RecordedInputSnapshots.fromBk2(second, first);

        assertTrue(firstReplay.player1().startHeld());
        assertTrue(firstReplay.player1().startPressed());
        assertTrue(secondReplay.player1().startHeld(),
                "live rewind input rows must retain Start held state after the edge frame");
        assertFalse(secondReplay.player1().startPressed(),
                "pressed edge should still be derived from current held vs previous held");
    }
}
