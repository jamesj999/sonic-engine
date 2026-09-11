package com.openggf.sprites.playable;

import com.openggf.graphics.GLCommand;
import com.openggf.physics.Direction;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.Sensor;

import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;

public class Knuckles extends AbstractPlayableSprite {

	public Knuckles(String code, short x, short y) {
		super(code, x, y);
		setWidth(20);
		setHeight(runHeight);
		setRenderOffsets((short) 0, (short) 0);
	}

	public void draw() {
		if (isHidden()) {
			return;
		}
		// ROM: During hurt bounce (routine 4), DisplaySprite is called directly
		// (always visible). Flashing only occurs after landing (routine 2).
		if (!shouldRefreshRenderFlagThisFrame()) {
			if (getSpindashDustController() != null) {
				getSpindashDustController().draw();
			}
			return;
		}
		if (getSpriteRenderer() != null) {
			if (getSpindashDustController() != null) {
				getSpindashDustController().draw();
			}
			getSpriteRenderer().drawFrame(
					getMappingFrame(),
					getRenderCentreX(),
					getRenderCentreY(),
					getRenderHFlip(),
					getRenderVFlip());
			return;
		}
		graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.RECTI,
				GL_TRIANGLE_FAN, 1, 1, 1, xPixel, yPixel, xPixel + width, yPixel
						+ height));
		graphicsManager.registerCommand(new GLCommand(GLCommand.CommandType.VERTEX2I,
				-1, 1, 0, 0, getCentreX(), getCentreY(), 0, 0));
	}

	@Override
	public SecondaryAbility getSecondaryAbility() {
		return SecondaryAbility.GLIDE;
	}

	@Override
	public void defineSpeeds() {
		// ROM: Knuckles_Init (sonic3k.asm:30361-30363)
		// Max_speed = $600, Acceleration = $C, Deceleration = $80
		// ROM: Knux_Jump (sonic3k.asm:32454) move.w #$600,d2 — lower jump than Sonic
		runAccel = 12;
		runDecel = 128;
		friction = 12;
		max = 1536;
		jump = 1536;      // $600 — lower than Sonic's $680
		angle = 0;
		slopeRunning = 32;
		slopeRollingDown = 80;
		slopeRollingUp = 20;
		rollDecel = 32;
		minStartRollSpeed = 128;
		minRollSpeed = 128;
		maxRoll = 4096;
		rollHeight = 28;
		runHeight = 38;    // ROM: y_radius = $13 (19), runHeight = 2 * 19 = 38
		standXRadius = 9;
		standYRadius = 19; // ROM: y_radius = $13
		rollXRadius = 7;
		rollYRadius = 14;
	}

	@Override
	protected void createSensorLines() {
		// Ground Sensors — Y offset matches standYRadius (0x13 = 19)
		groundSensors = new Sensor[2];
		groundSensors[0] = new GroundSensor(this, Direction.DOWN, (byte) -9, (byte) 19, true);
		groundSensors[1] = new GroundSensor(this, Direction.DOWN, (byte) 9, (byte) 19, true);

		// Ceiling Sensors — Y offset matches -standYRadius (-0x13 = -19)
		ceilingSensors = new Sensor[2];
		ceilingSensors[0] = new GroundSensor(this, Direction.UP, (byte) -9, (byte) -19, false);
		ceilingSensors[1] = new GroundSensor(this, Direction.UP, (byte) 9, (byte) -19, false);

		// Push Sensors
		pushSensors = new Sensor[2];
		pushSensors[0] = new GroundSensor(this, Direction.LEFT, (byte) -10, (byte) 0, false);
		pushSensors[1] = new GroundSensor(this, Direction.RIGHT, (byte) 10, (byte) 0, false);
	}
}
