package com.openggf.game.sonic2;

import com.openggf.camera.Camera;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.events.Sonic2WFZEvents;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic2WfzBgScroll {

    private Sonic2WFZEvents events;
    private Camera camera;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        GameServices.camera().resetState();
        camera = GameServices.camera();
        events = new Sonic2WFZEvents();
        events.init(0);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void routine2ChasesVerticalOffsetByAtMostSixteenPixels() {
        camera.setY((short) 0x180);
        events.update(0, 0);
        events.setBgYOffsetForTest(0x100);

        events.update(0, 1);

        assertEquals(0x170, events.getBgYPos());
    }

    @Test
    void routine2KeepsCameraMovementAfterRoutine0Seed() {
        camera.setY((short) 0x180);
        events.update(0, 0);
        camera.setY((short) 0x188);

        events.update(0, 1);

        assertEquals(0x188, events.getBgYPos());
    }

    @Test
    void routine2ChasesHorizontalOffsetByAtMostSixteenPixels() {
        camera.setX((short) 0x200);
        events.update(0, 0);
        events.setBgXOffsetForTest(0x40);

        events.update(0, 1);

        assertEquals(0x1F0, events.getBgXPos());
    }
}
