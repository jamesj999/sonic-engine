package com.openggf.sprites.playable;

import com.openggf.graphics.GLCommand;
import com.openggf.physics.Direction;
import com.openggf.physics.GroundSensor;
import com.openggf.physics.Sensor;

import static org.lwjgl.opengl.GL11.GL_TRIANGLE_FAN;

public class Sonic extends AbstractPlayableSprite {

	public Sonic(String code, short x, short y) {
		super(code, x, y);
		// width in pixels
		setWidth(20);
		setHeight(runHeight);
		setRenderOffsets((short) 0, (short) 0);
	}

	public void draw() {
		// ROM: move.b #id_Null,(v_player+obAnim).w - Giant Ring flash hides Sonic
		if (isHidden()) {
			return;
		}
		// ROM: During hurt bounce (routine 4), DisplaySprite is called directly
		// (always visible). Flashing only occurs after landing (routine 2) via
		// Sonic_Display: lsr.w #3,d0 / bcc = visible when (timer & 0x04) != 0.
		if (!shouldRefreshRenderFlagThisFrame()) {
			// Still draw spindash dust even when blinking
			if (getSpindashDustController() != null) {
				getSpindashDustController().draw();
			}
			return; // Invisible this frame
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
		return SecondaryAbility.INSTA_SHIELD;
	}

	@Override
	public void defineSpeeds() {
		// Base values - speed shoes boost is applied dynamically in getters
		runAccel = 12;
		runDecel = 128;
		friction = 12;
		max = 1536;
		jump = 1664;
		angle = 0;
		slopeRunning = 32;
		slopeRollingDown = 80;  // Full slope factor when rolling downhill (with gravity)
		slopeRollingUp = 20;    // Reduced factor (80 >> 2) when rolling uphill (against gravity)
		rollDecel = 32;
		minStartRollSpeed = 128; // SPG: 0.5 pixels (128 subpixels) in S1/S2
		minRollSpeed = 128;
		maxRoll = 4096;
		rollHeight = 28;
		runHeight = 38;
		standXRadius = 9;
		standYRadius = 19;
		rollXRadius = 7;
		rollYRadius = 14;
	}

	@Override
	protected void createSensorLines() {
		// Ground Sensors - Y offset matches standYRadius (0x13 = 19)
		groundSensors = new Sensor[2];
		groundSensors[0] = new GroundSensor(this, Direction.DOWN, (byte) -9, (byte) 19, true);
		groundSensors[1] = new GroundSensor(this, Direction.DOWN, (byte) 9, (byte) 19, true);

		// Ceiling Sensors - Y offset matches -standYRadius (-0x13 = -19)
		ceilingSensors = new Sensor[2];
		ceilingSensors[0] = new GroundSensor(this, Direction.UP, (byte) -9, (byte) -19, false);
		ceilingSensors[1] = new GroundSensor(this, Direction.UP, (byte) 9, (byte) -19, false);

		// Push Sensors
		pushSensors = new Sensor[2];
		pushSensors[0] = new GroundSensor(this, Direction.LEFT, (byte) -10, (byte) 0, false);
		pushSensors[1] = new GroundSensor(this, Direction.RIGHT, (byte) 10, (byte) 0, false);
	}
}
