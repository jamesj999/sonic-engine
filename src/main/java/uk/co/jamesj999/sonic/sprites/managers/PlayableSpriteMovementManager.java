package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.physics.TerrainCollisionManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.timer.timers.SpindashCameraTimer;

public class PlayableSpriteMovementManager extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private final TerrainCollisionManager terrainCollisionManager = TerrainCollisionManager
			.getInstance();

	private final short max;
	private final short runAccel;
	private final short runDecel;
	private final short slopeRunning;
	private final short minStartRollSpeed;
	private final short minRollSpeed;
	private final short maxRoll;
	private final short slopeRollingUp;
	private final short slopeRollingDown;
	private final short rollDecel;

	private boolean jumpPressed;
	private boolean jumpHeld;

	private boolean testKeyPressed;


	public PlayableSpriteMovementManager(AbstractPlayableSprite sprite) {
		super(sprite);
		max = sprite.getMax();
		runAccel = sprite.getRunAccel();
		runDecel = sprite.getRunDecel();
		slopeRunning = sprite.getSlopeRunning();
		minStartRollSpeed = sprite.getMinStartRollSpeed();
		minRollSpeed = sprite.getMinRollSpeed();
		maxRoll = sprite.getMaxRoll();
		slopeRollingUp = sprite.getSlopeRollingUp();
		slopeRollingDown = sprite.getSlopeRollingDown();
		rollDecel = sprite.getRollDecel();
	}
	@Override
	public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean jump, boolean testKey) {
		// A simple way to test our running modes...
		if (testKey && !testKeyPressed) {
			testKeyPressed = true;
			if ((GroundMode.GROUND.equals(sprite.getGroundMode()))) {
				sprite.setGroundMode(GroundMode.RIGHTWALL);
			} else if(GroundMode.RIGHTWALL.equals(sprite.getGroundMode())) {
				sprite.setGroundMode(GroundMode.CEILING);
			} else if(GroundMode.CEILING.equals(sprite.getGroundMode())) {
				sprite.setGroundMode(GroundMode.LEFTWALL);
			} else {
				sprite.setGroundMode(GroundMode.GROUND);
			}
		}

		if(testKeyPressed && !testKey) {
			testKeyPressed = false;
		}

		short originalX = sprite.getX();
		short originalY = sprite.getY();

		// First thing to do is run this additional method to find out if the jump button has been released recently
		// in order to shorten the jump.
		if (jumpPressed) {
			jumpHandler(jump);
		}

		if(sprite.getSpindash()) {
			//A little bit of logic to make sure holding jump doesn't accelerate the spindash on each frame.
			if(jumpHeld && jump) {
				jump = false;
			} else if(jumpHeld && !jump) {
				jumpHeld = false;
			} else if(!jumpHeld && jump) {
				jumpHeld = true;
			}

			if(!down) {
				releaseSpindash(sprite);
				// Can't jump straight out of a spindash:
				jump = false;

				// This won't actually work IRL as it will cause the sprite to be able to clip through walls etc.
				// (It's just here for testing until I can work out a better way to do this):
				return;
			}

			if(down && !jump) {
				spindashCooldown(sprite);
				return;
			}
		}

		//Detect the start of spindash or deal with 'revving'.
		if(down && !left && !right && sprite.getGSpeed() == 0 && jump && !sprite.getAir()) {
			handleSpindash(sprite);
			return;
		}

		// Next thing to do is calculate movement and acceleration.
		if(sprite.getAir()) {
			// Sonic is in the air
			calculateAirMovement(sprite, left, right);
		} else {
			// Sonic is on the ground
			if (jump && !jumpPressed) {
				// Commence jump
				jump(sprite);
			} else {
				calculateGSpeed(sprite, left, right);
				// Since this will update the gSpeed, we now need to update the X/Y from this.
				calculateXYFromGSpeed(sprite);
			}
		}

		sprite.updateSensors(originalX, originalY);

		boolean inAir = sprite.getAir();
		if(!inAir) {
			doWallCollision(sprite);
		}

		// Now, move the sprite as per the air movement or GSpeed rules:
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());

		// Has the sprite slowed down enough to stop rolling? Do we need to start rolling?
		// (Only applicable if not in the air)
		if (!sprite.getAir()) {
			calculateRoll(sprite, down);
		}

		// Store some attributes in case we need to 'reset' the terrain collision:
		short yBeforeTerrainCollision = sprite.getY();
		inAir = sprite.getAir();
		boolean isRoll = sprite.getRolling();

		if(inAir) {
			doWallCollision(sprite);
		}

		// Perform terrain checks - results are updated directly into the sprite
		SensorResult[] groundResult = terrainCollisionManager.getSensorResult(sprite.getGroundSensors());
		SensorResult[] ceilingResult = terrainCollisionManager.getSensorResult(sprite.getCeilingSensors());

		doTerrainCollision(sprite, groundResult);

		// This won't work when graphics are involved...
		if(sprite.getX() > originalX) {
			sprite.setDirection(Direction.RIGHT);
		} else if(sprite.getX() < originalX) {
			sprite.setDirection(Direction.LEFT);
		}

		// Temporary 'death' detection - just resets X/Y of sprite.
		// TODO - This no longer works. y <= 0 would put sonic above the viewport. Needs to work based on level height once merged.
        /*if (sprite.getY() <= 0) {
            sprite.setX((short) 50);
            sprite.setY((short) 50);
            sprite.setXSpeed((short) 0);
            sprite.setYSpeed((short) 0);
            sprite.setGSpeed((short) 0);
        }*/
		// Update sprite ground mode for next tick:

		// Update active sensors
		sprite.updateSensors(originalX, originalY);
	}

	private void doWallCollision(AbstractPlayableSprite sprite) {
		SensorResult[] pushResult = new SensorResult[sprite.getPushSensors().length];
		// If grounded, we need to check if we're going to hit a wall based on our xSpeed.
		// If we are, we need to stop moving.
		if(!sprite.getAir()) {
			// Grounded collision
			// TODO: This really should be xSpeed and ySpeed but we only care about xSpeed for now.
			// We scan for the wall at the position we will be at next frame.
			// If we find a wall, we move to it and stop.
			for(int i = 0; i < sprite.getPushSensors().length; i++) {
				pushResult[i] = sprite.getPushSensors()[i].scan((short) (sprite.getXSpeed() / 256), (short) (sprite.getYSpeed() / 256));
			}
			SensorResult lowestResult = findLowestSensorResult(pushResult);
			if(lowestResult != null) {
				byte distance = lowestResult.distance();
				Direction dir = lowestResult.direction();

				boolean movingTowards = false;
				if (dir == Direction.LEFT && sprite.getXSpeed() < 0) movingTowards = true;
				else if (dir == Direction.RIGHT && sprite.getXSpeed() > 0) movingTowards = true;
				else if (dir == Direction.UP && sprite.getYSpeed() < 0) movingTowards = true;
				else if (dir == Direction.DOWN && sprite.getYSpeed() > 0) movingTowards = true;

				if (distance < 0 || (distance == 0 && movingTowards)) {
					short lookaheadX = (short) (sprite.getXSpeed() / 256);
					short lookaheadY = (short) (sprite.getYSpeed() / 256);
					short subX = (short) (sprite.getXSubpixel() & 0xFF);
					short subY = (short) (sprite.getYSubpixel() & 0xFF);

					if (dir == Direction.LEFT || dir == Direction.RIGHT) {
						short move = lookaheadX;
						if (dir == Direction.LEFT) {
							move -= distance;
						} else {
							move += distance;
						}
						sprite.setXSpeed((short) ((move * 256) - subX));
					} else {
						short move = lookaheadY;
						if (dir == Direction.UP) {
							move -= distance;
						} else {
							move += distance;
						}
						sprite.setYSpeed((short) ((move * 256) - subY));
					}
					sprite.setGSpeed((short) 0);
				}
			}
		} else {
			// Airborne collision
			// We scan at current position (because we already moved).
			// If we are inside a wall, we move out.
			for(int i = 0; i < sprite.getPushSensors().length; i++) {
				pushResult[i] = sprite.getPushSensors()[i].scan((short) 0, (short) 0);
			}
			SensorResult lowestResult = findLowestSensorResult(pushResult);
			if(lowestResult != null) {
				byte distance = lowestResult.distance();
				Direction dir = lowestResult.direction();
				boolean collision = distance < 0;
				if (!collision && distance == 0) {
					if (dir == Direction.RIGHT && (sprite.getXSubpixel() & 0xFF) > 0) collision = true;
					else if (dir == Direction.DOWN && (sprite.getYSubpixel() & 0xFF) > 0) collision = true;
				}

				if (collision) {
					moveForSensorResult(sprite, lowestResult);
					sprite.setXSpeed((short) 0);
					sprite.setGSpeed((short) 0);
				}
			}
		}
	}

	private void doTerrainCollision(AbstractPlayableSprite sprite, SensorResult[] results) {
		SensorResult lowestResult = findLowestSensorResult(results);

		if(sprite.getAir()) {
			// We are in the air and haven't landed unless we have a distance < 0 as our lowest result.
			if (lowestResult == null || lowestResult.distance() >= 0) {
				// We haven't landed, no more to do here since no terrain collision has occurred.
				return;
			} else {
				// Work out the ySpeed required to make us collide.
				// TODO - this is as per the SPG but seems to make sonic fall through surfaces if he's moving faster on X than Y and is in the air...
				short requiredSpeed;
				short positiveXSpeed = (short) Math.abs(sprite.getXSpeed());
				if(positiveXSpeed < sprite.getYSpeed()) {
					// sonic is *mostly* moving down (convert ySpeed from subpixels)
					requiredSpeed = (short) (-((sprite.getYSpeed() / 256)+ 8));
				} else {
					// sonic is *mostly* moving left or right
					requiredSpeed = 0;
				}
				// Check whether
				if(results[0].distance() >= requiredSpeed || results[1].distance() >= requiredSpeed) {
					// sonic has collided with the ground. Work out which ground mode we are in to work out how to move Sonic.
					moveForSensorResult(sprite, lowestResult);
					// And set sonic's new angle based on the tile found:
					if (lowestResult.angle() == (byte) 0xFF) {
						switch (sprite.getGroundMode()) {
							case GROUND -> sprite.setAngle((byte) 0x00);
							case RIGHTWALL -> sprite.setAngle((byte) 0xC0);
							case CEILING -> sprite.setAngle((byte) 0x80);
							case LEFTWALL -> sprite.setAngle((byte) 0x40);
						}
					} else {
						sprite.setAngle(lowestResult.angle());
					}

					// And maybe run our landing code
					calculateLanding(sprite);

					updateGroundMode(sprite);
				}
			}
		} else {
			// Check if we are still on the ground:
			// Work out the speeds required to consider us still on the ground
			short speed = (short) Math.abs(sprite.getXSpeed());
			if (sprite.getGroundMode() == GroundMode.LEFTWALL || sprite.getGroundMode() == GroundMode.RIGHTWALL) {
				speed = (short) Math.abs(sprite.getYSpeed());
			}
			short requiredSpeed = (short) Math.min(speed + 4, 14);

			if(lowestResult.distance() < requiredSpeed) {
				moveForSensorResult(sprite, lowestResult);
				if (lowestResult.angle() == (byte) 0xFF) {
					sprite.setAngle((byte) ((sprite.getAngle() + 0x20) & 0xC0));
				} else {
					sprite.setAngle(lowestResult.angle());
				}
				updateGroundMode(sprite);
			} else {
				sprite.setAir(true);
			}
		}
//
//
//
//		if (result != null) {
//			if (sprite.getAir()) {
//				if (result.distance() >= 0) {
//					return;
//				}
//
//			}
//		}
//
//
//		if (result == null || result.distance() >= 16) {
//			// No terrain found. We should now consider sonic to be in the air if he's not already:
//			sprite.setAir(true);
//		} else {
//			// Tile found within range, check if we need to land:
//			if(!sprite.getAir()) {
//				// We are already on the ground. Set Y based on returned distance and update angle:
//				switch (sprite.getGroundMode()) {
//                    case GROUND -> {
//						sprite.setY((short) (sprite.getY() + result.distance()));
//                    }
//                    case RIGHTWALL -> {
//						sprite.setX((short) (sprite.getX() + result.distance()));
//                    }
//                    case CEILING -> {
//						sprite.setY((short) (sprite.getY() - result.distance()));
//                    }
//                    case LEFTWALL -> {
//						sprite.setX((short) (sprite.getX() - result.distance()));
//                    }
//                }
//				sprite.setAngle(result.angle());
//			}
//			else if (sprite.getYSpeed() > 0) {
//				// We are in the air, moving towards the terrain and have a collision
//				//sprite.setY((short) (sprite.getY() + result.distance()));
//				sprite.setAngle(result.angle());
//				calculateLanding(sprite);
//			}
//		}
		// Old code - to remove, but may be useful in the meantime
//        if(terrainHeight == -1) {
//            // This means Sonic is now in the air...
//            sprite.setAir(true);
//        } else if(terrainHeight > -1) {
//            // This means that sonic is on the ground
//            if (sprite.getAir() && sprite.getYSpeed() > 0 && (sprite.getCentreY() + (sprite.getHeight() / 2)) > terrainHeight) {
//                // This sprite currently in the air, moving to the ground so we need to reset its X/Y speeds:
//                calculateLanding(sprite);
//            }
//
//            // Check again if we're in the air - we may have just landed.
//            if(!sprite.getAir()) {
//                // TODO: Figure out why the 20 is here...
//				sprite.setY((short) (terrainHeight - (sprite.getHeight() / 2)));
//			}
//        }
	}

	private void handleSpindash(AbstractPlayableSprite sprite) {
		if (!sprite.getSpindash()) {
			sprite.setSpindash(true);
			sprite.setSpindashConstant(2f);
		} else {
			sprite.setSpindashConstant(sprite.getSpindashConstant() + 2f);
		}
	}

	private void releaseSpindash(AbstractPlayableSprite sprite) {
		sprite.setSpindash(false);
		short spindashGSpeed = (short) ((8 + ((Math.floor(sprite.getSpindashConstant()) / 2))) * 256);
		if(Direction.LEFT.equals(sprite.getDirection())) {
			sprite.setGSpeed((short) (0 - spindashGSpeed));
		} else if(Direction.RIGHT.equals(sprite.getDirection())) {
			sprite.setGSpeed(spindashGSpeed);
		}
		Camera.getInstance().setFrozen(true);
		TimerManager.getInstance().registerTimer(new SpindashCameraTimer("spindash", (32 - (int) sprite.getSpindashConstant())));

		sprite.setSpindashConstant(0f);
	}

	private void spindashCooldown(AbstractPlayableSprite sprite) {
		float spindashConstant = sprite.getSpindashConstant();
		spindashConstant -= ((spindashConstant / 0.125) / 256);
		if(spindashConstant < 0.01) {
			spindashConstant = 0f;
		}
		sprite.setSpindashConstant(spindashConstant);
	}

	/**
	 * Will calculate gSpeed for Sprite based on current terrain and left/right
	 * keypresses. Only to be used when sprite is on ground. This will only
	 * calculate gSpeed, X/Y speeds must be calculated afterwards.
	 *
	 * @param sprite
	 *            The sprite in question
	 * @param left
	 *            Whether or not the left key is pressed
	 * @param right
	 *            Whether or not the right key is pressed
	 */
	private void calculateGSpeed(AbstractPlayableSprite sprite, boolean left,
								 boolean right) {
		short gSpeed = sprite.getGSpeed();
		int angle = calculateAngle(sprite);

		short friction;
		short slopeRunningVariant;
		short maxSpeed;
		short accel;
		short decel;
		if (!sprite.getRolling()) {
			friction = sprite.getFriction();
			slopeRunningVariant = slopeRunning;
			maxSpeed = max;
			accel = runAccel;
			decel = runDecel;
		} else {
			friction = (short) (sprite.getFriction() / 2);
			double gSpeedSign = Math.signum(gSpeed);
			double angleSign = Math.signum(Math.sin(Math.toRadians(angle)));
			if (gSpeedSign == angleSign) {
				slopeRunningVariant = 80;
			} else {
				slopeRunningVariant = 20;
			}
			accel = 0;
			decel = rollDecel;
			maxSpeed = maxRoll;
		}
		// Running or rolling on the ground
		gSpeed += slopeRunningVariant * Math.sin(Math.toRadians(angle));
		if (left) {
			if (gSpeed > 0) {
				gSpeed -= decel;
			} else if (!sprite.getRolling()) {
				if (gSpeed > -maxSpeed) {
					gSpeed -= accel;
				} else {
					gSpeed = (short) -maxSpeed;
				}
			}
		}
		if (right) {
			if (gSpeed < 0) {
				gSpeed += decel;
			} else if (!sprite.getRolling()) {
				if (gSpeed < maxSpeed) {
					gSpeed = (short) (gSpeed + accel);
				} else {
					gSpeed = maxSpeed;
				}
			}
		}
		if ((!left && !right) || (sprite.getRolling() && left && gSpeed < 0) || (sprite.getRolling() && right && gSpeed > 0)) {
			if ((gSpeed < friction && gSpeed > 0) || (gSpeed > -friction)
					&& gSpeed < 0) {
				gSpeed = 0;
			} else {
				gSpeed -= Math.min(Math.abs(gSpeed), friction)
						* Math.signum(gSpeed);
			}
		}

		sprite.setGSpeed(gSpeed);
	}

	/**
	 * Causes current sprite to jump. Only to be used when sprite on the ground.
	 *
	 * @param sprite
	 *            The sprite in question
	 */
	private void jump(AbstractPlayableSprite sprite) {
		sprite.setAir(true);
		sprite.setRolling(true);
		int angle = calculateAngle(sprite);
		jumpPressed = true;
		sprite.setXSpeed((short) (sprite.getXSpeed() + sprite.getJump()
				* Math.sin(Math.toRadians(angle))));
		sprite.setYSpeed((short) (sprite.getYSpeed() - sprite.getJump()
				* Math.cos(Math.toRadians(angle))));
	}

	/**
	 * Calculates air movement for sprite based on gravity and current
	 * left/right keypresses. Only to be used when sprite is in the air.
	 *
	 * @param sprite
	 *            The sprite in question
	 * @param left
	 *            Whether or not the left key is pressed
	 * @param right
	 *            Whether or not the right key is pressed
	 */
	private void calculateAirMovement(AbstractPlayableSprite sprite,
									  boolean left, boolean right) {
		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();
		// In the air
		if (left) {
			if (xSpeed - (2 * runAccel) < -max) {
				xSpeed = (short) -max;
			} else {
				xSpeed -= (2 * runAccel);
			}
		}
		if (right) {
			if (xSpeed + (2 * runAccel) > max) {
				xSpeed = max;
			} else {
				xSpeed += (2 * runAccel);
			}
		}
		// xSpeed = gSpeed;
		if (ySpeed < 0 && ySpeed > -1024) {
			if (Math.abs(xSpeed) >= 32) {
				xSpeed *= 0.96875;
			}
		}
		ySpeed += sprite.getGravity();

		if(ySpeed > 4096) {
			ySpeed = 4096;
		}
		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);
	}

	/**
	 * Calculates gSpeed from x/y speed and angle when sprite lands. This should
	 * only be used in the frame the sprite regains contact with the ground,
	 * that is when at the start of the tick the sprite is in the air, but a
	 * height >0 is returned from the Terrain Collision Manager.
	 *
	 * @param sprite
	 *            The sprite in question
	 */
	private void calculateLanding(AbstractPlayableSprite sprite) {
		sprite.setRolling(false);
		sprite.setAir(false);
		short ySpeed = sprite.getYSpeed();
		short xSpeed = sprite.getXSpeed();
		short gSpeed = sprite.getGSpeed();
		int angle = calculateAngle(sprite);
		if (ySpeed > 0) {
			byte originalAngle = sprite.getAngle();
			if ((originalAngle >= (byte) 0xF0 && originalAngle <= (byte) 0xFF)
					|| (originalAngle >= (byte) 0x00 && originalAngle <= (byte) 0x0F)) {
				gSpeed = xSpeed;
			} else if ((originalAngle >= (byte) 0xE0 && originalAngle <= (byte) 0xEF)
					|| (originalAngle >= (byte) 0x10 && originalAngle <= (byte) 0x1F)) {
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					gSpeed = (short) (ySpeed * 0.5 * (Math.signum(Math
							.sin(Math.toRadians(angle)))));
				}
			} else if ((originalAngle >= (byte) 0xC0 && originalAngle <= (byte) 0xDF)
					|| (originalAngle >= (byte) 0x20 && originalAngle <= (byte) 0x3F)) {
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					gSpeed = (short) (ySpeed * (Math.signum(Math.sin(Math
							.toRadians(angle)))));
				}
			}
		}
		sprite.setGSpeed(gSpeed);
	}

	private void calculateRoll(AbstractPlayableSprite sprite, boolean down) {
		short gSpeed = sprite.getGSpeed();

		// If the player is pressing down, we're not in the air, we're not
		// currently rolling and our ground speed is greater than the minimum
		// speed, start rolling:
		if (down && !sprite.getAir() && !sprite.getRolling()
				&& (gSpeed > minStartRollSpeed || gSpeed < -minStartRollSpeed)) {
			sprite.setRolling(true);
			// Return here so that we don't immediately stop rolling (although
			// we shouldn't anyway).
			return;
		}

		// If we're rolling and our ground speed is less than the minimum roll
		// speed then stop rolling:
		if (sprite.getRolling()
				&& ((gSpeed < minRollSpeed && gSpeed >= 0) || (gSpeed > -minRollSpeed && gSpeed <= 0))) {
			sprite.setRolling(false);
		}
	}

	/**
	 *
	 * @param sprite
	 *            The sprite in question
	 * @return The correct angle, based on 360 degree rotation. Convert this to
	 *         radians before using Math.sin etc.
	 */
	private int calculateAngle(AbstractPlayableSprite sprite) {
		int angle = (int) ((sprite.getAngle() & 0xFF) * 1.40625);

		// Small hack to make sure the angle is between -360 and 360.
		while (angle >= 360) {
			angle -= 360;
		}
		while (angle <= -360) {
			angle += 360;
		}
		return angle;
	}

	private void calculateXYFromGSpeed(AbstractPlayableSprite sprite) {
		short gSpeed = sprite.getGSpeed();
		int angle = calculateAngle(sprite);
		sprite.setXSpeed((short) Math.round(gSpeed
				* Math.cos(Math.toRadians(angle))));

		sprite.setYSpeed((short) Math.round(gSpeed
				* Math.sin(Math.toRadians(angle))));
	}

	private void jumpHandler(boolean jump) {
		short ySpeedConstant = (4 * 256);
		if (sprite.getYSpeed() < -ySpeedConstant) {
			if (!jump) {
				sprite.setYSpeed((short) (-ySpeedConstant));
			}
		}
		if (!sprite.getAir() && !jump) {
			jumpPressed = false;
		}
	}

	private SensorResult findLowestSensorResult(SensorResult[] results) {
		SensorResult lowestResult = null;
		for (SensorResult result : results) {
			if(result != null) {
				if (lowestResult == null || result.distance() < lowestResult.distance()) {
					lowestResult = result;
				}
			}
		}
		return lowestResult;
	}

	private void moveForSensorResult(AbstractPlayableSprite sprite, SensorResult result) {
		byte distance = result.distance();
		switch (result.direction()) {
			case UP -> sprite.setY((short) (sprite.getY() - distance));
			case DOWN -> sprite.setY((short) (sprite.getY() + distance));
			case LEFT -> sprite.setX((short) (sprite.getX() - distance));
			case RIGHT -> sprite.setX((short) (sprite.getX() + distance));
		}
	}

	private void updateGroundMode(AbstractPlayableSprite sprite) {
		int angle = sprite.getAngle() & 0xFF;
		GroundMode currentMode = sprite.getGroundMode();
		GroundMode newMode = currentMode;

		if ((angle >= 0 && angle <= 32) || (angle >= 224 && angle <= 255)) {
			newMode = GroundMode.GROUND;
		} else if (angle >= 33 && angle <= 95) {
			newMode = GroundMode.LEFTWALL;
		} else if (angle >= 96 && angle <= 160) {
			newMode = GroundMode.CEILING;
		} else if (angle >= 161 && angle <= 223) {
			newMode = GroundMode.RIGHTWALL;
		}

		if (newMode != currentMode) {
			sprite.setGroundMode(newMode);
		}
	}
}
