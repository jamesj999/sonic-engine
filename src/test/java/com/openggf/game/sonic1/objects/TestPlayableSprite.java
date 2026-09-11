package com.openggf.game.sonic1.objects;

import com.openggf.game.session.EngineServices;
import com.openggf.physics.Sensor;
import com.openggf.sprites.playable.AbstractPlayableSprite;

/**
 * Lightweight {@link AbstractPlayableSprite} stub for unit tests that need a
 * player object without OpenGL, ROM, or full physics wiring.
 *
 * <p>All physics constants default to zero. Override-friendly: subclass and
 * add tracking fields / method overrides for test-specific assertions
 * (e.g. hurt callbacks).
 */
public class TestPlayableSprite extends AbstractPlayableSprite {

    static {
        ensureEngineServicesConfigured();
    }

    private static void ensureEngineServicesConfigured() {
        EngineServices.current();
    }

    /**
     * Creates a test sprite with rollHeight=0 and runHeight=0.
     */
    public TestPlayableSprite() {
        super("TEST", (short) 0, (short) 0);
        setWidth(20);
        setHeight(38);
    }

    /**
     * Creates a test sprite with custom roll/run heights.
     * These are applied after construction since {@link #defineSpeeds()} runs
     * during the super constructor before subclass fields are initialized.
     *
     * @param rollHeight value for {@code rollHeight}
     * @param runHeight  value for {@code runHeight}
     */
    public TestPlayableSprite(int rollHeight, int runHeight) {
        super("TEST", (short) 0, (short) 0);
        this.rollHeight = (short) rollHeight;
        this.runHeight = (short) runHeight;
        setWidth(20);
        setHeight(38);
    }

    @Override
    protected void defineSpeeds() {
        runAccel = 0;
        runDecel = 0;
        friction = 0;
        max = 0;
        jump = 0;
        angle = 0;
        slopeRunning = 0;
        slopeRollingDown = 0;
        slopeRollingUp = 0;
        rollDecel = 0;
        minStartRollSpeed = 0;
        minRollSpeed = 0;
        maxRoll = 0;
        rollHeight = 0;
        runHeight = 0;
        standXRadius = 9;
        standYRadius = 19;
        rollXRadius = 7;
        rollYRadius = 14;
    }

    @Override
    protected void createSensorLines() {
        groundSensors = new Sensor[0];
        ceilingSensors = new Sensor[0];
        pushSensors = new Sensor[0];
    }

    @Override
    public void draw() {
        // No-op for tests.
    }
}


