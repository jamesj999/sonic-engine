package com.openggf.game.sonic3k.events;

import org.junit.jupiter.api.Test;
import com.openggf.camera.Camera;
import static org.junit.jupiter.api.Assertions.*;

public class TestSonic3kZoneEvents {

    // Concrete subclass for testing
    static class TestableZoneEvents extends Sonic3kZoneEvents {
        int updateCallCount = 0;

        TestableZoneEvents() {
        }

        @Override
        public void update(int act, int frameCounter) {
            updateCallCount++;
        }
    }

    @Test
    public void initResetsEventRoutine() {
        var events = new TestableZoneEvents();
        events.setEventRoutine(6);
        events.init(0);
        assertEquals(0, events.getEventRoutine());
    }

    @Test
    public void eventRoutineGetterSetter() {
        var events = new TestableZoneEvents();
        events.setEventRoutine(4);
        assertEquals(4, events.getEventRoutine());
    }
}


