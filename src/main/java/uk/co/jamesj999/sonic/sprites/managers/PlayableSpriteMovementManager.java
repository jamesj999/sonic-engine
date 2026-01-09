package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.physics.TerrainCollisionManager;
import uk.co.jamesj999.sonic.physics.TrigLookupTable;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.timer.timers.ControlLockTimer;
import uk.co.jamesj999.sonic.timer.timers.SpindashCameraTimer;

public class PlayableSpriteMovementManager extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private final TerrainCollisionManager terrainCollisionManager = TerrainCollisionManager
			.getInstance();
	private final AudioManager audioManager = AudioManager.getInstance();

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
	private boolean skidding;

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
			} else if (GroundMode.RIGHTWALL.equals(sprite.getGroundMode())) {
				sprite.setGroundMode(GroundMode.CEILING);
			} else if (GroundMode.CEILING.equals(sprite.getGroundMode())) {
				sprite.setGroundMode(GroundMode.LEFTWALL);
			} else {
				sprite.setGroundMode(GroundMode.GROUND);
			}
		}

		if (testKeyPressed && !testKey) {
			testKeyPressed = false;
		}

		boolean controlLocked = TimerManager.getInstance().getTimerForCode("ControlLock-" + sprite.getCode()) != null;

		if (controlLocked || sprite.getSpringing()) {
			left = false;
			right = false;
		}

		short originalX = sprite.getX();
		short originalY = sprite.getY();
		if (sprite.getDead()) {
			applyDeathMovement(sprite);
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			sprite.updateSensors(originalX, originalY);
			return;
		}
		sprite.setCrouching(false);

		// First thing to do is run this additional method to find out if the jump
		// button has been released recently
		// in order to shorten the jump.
		if (jumpPressed) {
			jumpHandler(jump);
		}

		if (sprite.getSpindash()) {
			sprite.setCrouching(false);
			// A little bit of logic to make sure holding jump doesn't accelerate the
			// spindash on each frame.
			if (jumpHeld && jump) {
				jump = false;
			} else if (jumpHeld && !jump) {
				jumpHeld = false;
			} else if (!jumpHeld && jump) {
				jumpHeld = true;
			}

			if (!down) {
				releaseSpindash(sprite);
				// Can't jump straight out of a spindash:
				jump = false;

				// This won't actually work IRL as it will cause the sprite to be able to clip
				// through walls etc.
				// (It's just here for testing until I can work out a better way to do this):
				return;
			}

			if (down && !jump) {
				spindashCooldown(sprite);
				return;
			}
		}

		// Detect the start of spindash or deal with 'revving'.
		if (down && !left && !right && sprite.getGSpeed() == 0 && jump && !sprite.getAir()) {
			handleSpindash(sprite);
			return;
		}

		// Next thing to do is calculate movement and acceleration.
		if (sprite.getAir()) {
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

				if (!controlLocked) {
					// Check for slip/fall condition
					short absGSpeed = (short) Math.abs(sprite.getGSpeed());
					int angle = sprite.getAngle() & 0xFF;

					// If too slow (speed < 2.5 pixels) and on a steep slope (angle between 33 and
					// 223)
					if (absGSpeed < 640 && (angle >= 33 && angle <= 223)) {
						// Detach
						sprite.setAir(true);
						// Lock controls
						sprite.setGSpeed((short) 0);
						sprite.setXSpeed((short) 0);
						sprite.setYSpeed((short) 0);

						TimerManager.getInstance()
								.registerTimer(new ControlLockTimer("ControlLock-" + sprite.getCode(), 30, sprite));
					}
				}
			}
		}

		sprite.updateSensors(originalX, originalY);

		boolean inAir = sprite.getAir();
		if (!inAir) {
			doWallCollision(sprite);
		}

		// Now, move the sprite as per the air movement or GSpeed rules:
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());

		// Has the sprite slowed down enough to stop rolling? Do we need to start
		// rolling?
		// (Only applicable if not in the air)
		if (!sprite.getAir()) {
			calculateRoll(sprite, down);
			updateCrouchState(sprite, down, left, right);
		}

		// Store some attributes in case we need to 'reset' the terrain collision:
		short yBeforeTerrainCollision = sprite.getY();
		inAir = sprite.getAir();
		boolean isRoll = sprite.getRolling();

		if (inAir) {
			doWallCollision(sprite);
		}

		// Perform terrain checks - results are updated directly into the sprite
		SensorResult[] groundResult = terrainCollisionManager.getSensorResult(sprite.getGroundSensors());
		SensorResult[] ceilingResult = terrainCollisionManager.getSensorResult(sprite.getCeilingSensors());

		doTerrainCollision(sprite, groundResult);
		doCeilingCollision(sprite, ceilingResult);

		if (left && !right) {
			if (!sprite.getAir() && sprite.getGSpeed() > 0) {
				sprite.setDirection(Direction.RIGHT);
			} else {
				sprite.setDirection(Direction.LEFT);
			}
		} else if (right && !left) {
			if (!sprite.getAir() && sprite.getGSpeed() < 0) {
				sprite.setDirection(Direction.LEFT);
			} else {
				sprite.setDirection(Direction.RIGHT);
			}
		}

		// Temporary 'death' detection - just resets X/Y of sprite.
		// TODO - This no longer works. y <= 0 would put sonic above the viewport. Needs
		// to work based on level height once merged.
		/*
		 * if (sprite.getY() <= 0) {
		 * sprite.setX((short) 50);
		 * sprite.setY((short) 50);
		 * sprite.setXSpeed((short) 0);
		 * sprite.setYSpeed((short) 0);
		 * sprite.setGSpeed((short) 0);
		 * }
		 */
		// Update sprite ground mode for next tick:

		// Update active sensors
		sprite.updateSensors(originalX, originalY);
	}

	private void doCeilingCollision(AbstractPlayableSprite sprite, SensorResult[] results) {
		// Only check ceiling collision if we are moving upwards (ySpeed < 0)
		if (sprite.getYSpeed() < 0) {
			SensorResult lowestResult = findLowestSensorResult(results);
			if (lowestResult != null) {
				// Distance is positive if we are below the ceiling, negative if we have
				// penetrated it.
				// If distance < 0, we have hit the ceiling.
				// A small threshold might be needed, but < 0 is standard for penetration.
				if (lowestResult.distance() < 0) {
					// We hit the ceiling.
					// 1. Correct position.
					// distance is negative (e.g. -5). We need to move DOWN by 5.
					// moveForSensorResult handles direction UP: y - distance => y - (-5) => y + 5.
					// Correct.
					moveForSensorResult(sprite, lowestResult);

					// 2. Stop vertical movement
					sprite.setYSpeed((short) 0);
				}
			}
		}
	}

	private void doWallCollision(AbstractPlayableSprite sprite) {
		SensorResult[] pushResult = new SensorResult[sprite.getPushSensors().length];
		// If grounded, we need to check if we're going to hit a wall based on our
		// xSpeed.
		// If we are, we need to stop moving.
		if (!sprite.getAir()) {
			// Grounded collision
			// TODO: This really should be xSpeed and ySpeed but we only care about xSpeed
			// for now.
			// We scan for the wall at the position we will be at next frame.
			// If we find a wall, we move to it and stop.
			for (int i = 0; i < sprite.getPushSensors().length; i++) {
				pushResult[i] = sprite.getPushSensors()[i].scan((short) (sprite.getXSpeed() / 256),
						(short) (sprite.getYSpeed() / 256));
			}
			SensorResult lowestResult = findLowestSensorResult(pushResult);
			if (lowestResult != null) {
				byte distance = lowestResult.distance();
				Direction dir = lowestResult.direction();

				boolean movingTowards = false;
				if (dir == Direction.LEFT && sprite.getXSpeed() < 0)
					movingTowards = true;
				else if (dir == Direction.RIGHT && sprite.getXSpeed() > 0)
					movingTowards = true;
				else if (dir == Direction.UP && sprite.getYSpeed() < 0)
					movingTowards = true;
				else if (dir == Direction.DOWN && sprite.getYSpeed() > 0)
					movingTowards = true;

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
			for (int i = 0; i < sprite.getPushSensors().length; i++) {
				pushResult[i] = sprite.getPushSensors()[i].scan((short) 0, (short) 0);
			}
			SensorResult lowestResult = findLowestSensorResult(pushResult);
			if (lowestResult != null) {
				byte distance = lowestResult.distance();
				Direction dir = lowestResult.direction();
				boolean collision = distance < 0;
				if (!collision && distance == 0) {
					if (dir == Direction.RIGHT && (sprite.getXSubpixel() & 0xFF) > 0)
						collision = true;
					else if (dir == Direction.DOWN && (sprite.getYSubpixel() & 0xFF) > 0)
						collision = true;
				}

				if (collision) {
					// Add a 1px buffer to prevent sticking to walls
					if (dir == Direction.RIGHT) {
						lowestResult = new SensorResult(lowestResult.angle(), (byte) (lowestResult.distance() - 1),
								lowestResult.tileId(), lowestResult.direction());
					} else if (dir == Direction.LEFT) {
						lowestResult = new SensorResult(lowestResult.angle(), (byte) (lowestResult.distance() - 1),
								lowestResult.tileId(), lowestResult.direction());
					}
					moveForSensorResult(sprite, lowestResult);
					sprite.setXSpeed((short) 0);
					sprite.setGSpeed((short) 0);
				}
			}
		}
	}

	private void doTerrainCollision(AbstractPlayableSprite sprite, SensorResult[] results) {
		SensorResult lowestResult = findLowestSensorResult(results);

		if (sprite.getAir()) {
			// We are in the air and haven't landed unless we have a distance < 0 as our
			// lowest result.
			if (lowestResult == null || lowestResult.distance() >= 0) {
				// We haven't landed, no more to do here since no terrain collision has
				// occurred.
				return;
			} else {
				// Work out the ySpeed threshold required to land.
				// REV01 uses the *pixel* y-speed (high byte) with a +8 buffer,
				// then negates it to compare against the signed floor distance.
				short ySpeedPixels = (short) (sprite.getYSpeed() / 256);
				short requiredSpeed = (short) (-(ySpeedPixels + 8));
				// Check whether
				if (results[0].distance() >= requiredSpeed || results[1].distance() >= requiredSpeed) {
					// sonic has collided with the ground. Work out which ground mode we are in to
					// work out how to move Sonic.
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
			// AnglePos-style grounded glue: use fixed 0x0E cutoff.
			// BUT: if player is standing on a solid object (bridge, platform),
			// don't set to air based on terrain alone.
			if (lowestResult == null || lowestResult.distance() >= 14) {
				// Check if player is standing on a solid object before setting to air
				var solidManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getSolidObjectManager();
				if (solidManager != null && solidManager.isRidingObject()) {
					// Player is on an object, don't detach based on terrain
					return;
				}
				sprite.setAir(true);
				return;
			}
			moveForSensorResult(sprite, lowestResult);
			if (lowestResult.angle() == (byte) 0xFF) {
				sprite.setAngle((byte) ((sprite.getAngle() + 0x20) & 0xC0));
			} else {
				sprite.setAngle(lowestResult.angle());
			}
			updateGroundMode(sprite);
		}
		//
		//
		//
		// if (result != null) {
		// if (sprite.getAir()) {
		// if (result.distance() >= 0) {
		// return;
		// }
		//
		// }
		// }
		//
		//
		// if (result == null || result.distance() >= 16) {
		// // No terrain found. We should now consider sonic to be in the air if he's
		// not already:
		// sprite.setAir(true);
		// } else {
		// // Tile found within range, check if we need to land:
		// if(!sprite.getAir()) {
		// // We are already on the ground. Set Y based on returned distance and update
		// angle:
		// switch (sprite.getGroundMode()) {
		// case GROUND -> {
		// sprite.setY((short) (sprite.getY() + result.distance()));
		// }
		// case RIGHTWALL -> {
		// sprite.setX((short) (sprite.getX() + result.distance()));
		// }
		// case CEILING -> {
		// sprite.setY((short) (sprite.getY() - result.distance()));
		// }
		// case LEFTWALL -> {
		// sprite.setX((short) (sprite.getX() - result.distance()));
		// }
		// }
		// sprite.setAngle(result.angle());
		// }
		// else if (sprite.getYSpeed() > 0) {
		// // We are in the air, moving towards the terrain and have a collision
		// //sprite.setY((short) (sprite.getY() + result.distance()));
		// sprite.setAngle(result.angle());
		// calculateLanding(sprite);
		// }
		// }
		// Old code - to remove, but may be useful in the meantime
		// if(terrainHeight == -1) {
		// // This means Sonic is now in the air...
		// sprite.setAir(true);
		// } else if(terrainHeight > -1) {
		// // This means that sonic is on the ground
		// if (sprite.getAir() && sprite.getYSpeed() > 0 && (sprite.getCentreY() +
		// (sprite.getHeight() / 2)) > terrainHeight) {
		// // This sprite currently in the air, moving to the ground so we need to reset
		// its X/Y speeds:
		// calculateLanding(sprite);
		// }
		//
		// // Check again if we're in the air - we may have just landed.
		// if(!sprite.getAir()) {
		// // TODO: Figure out why the 20 is here...
		// sprite.setY((short) (terrainHeight - (sprite.getHeight() / 2)));
		// }
		// }
	}

	private void handleSpindash(AbstractPlayableSprite sprite) {
		if (!sprite.getSpindash()) {
			sprite.setSpindash(true);
			sprite.setSpindashConstant(2f);
		} else {
			sprite.setSpindashConstant(sprite.getSpindashConstant() + 2f);
		}
		float pitch = 1.0f + (sprite.getSpindashConstant() / 24.0f);
		audioManager.playSfx(GameSound.SPINDASH_CHARGE, pitch);
	}

	private void releaseSpindash(AbstractPlayableSprite sprite) {
		sprite.setSpindash(false);
		sprite.setCrouching(false);
		sprite.setRolling(true);
		forceRollAnimation(sprite);
		audioManager.playSfx(GameSound.SPINDASH_RELEASE);
		short spindashGSpeed = (short) ((8 + ((Math.floor(sprite.getSpindashConstant()) / 2))) * 256);
		if (Direction.LEFT.equals(sprite.getDirection())) {
			sprite.setGSpeed((short) (0 - spindashGSpeed));
		} else if (Direction.RIGHT.equals(sprite.getDirection())) {
			sprite.setGSpeed(spindashGSpeed);
		}
		Camera.getInstance().setFrozen(true);
		TimerManager.getInstance()
				.registerTimer(new SpindashCameraTimer("spindash", (32 - (int) sprite.getSpindashConstant())));

		sprite.setSpindashConstant(0f);
	}

	private void forceRollAnimation(AbstractPlayableSprite sprite) {
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			int rollId = velocityProfile.getRollAnimId();
			sprite.setAnimationId(rollId);
			sprite.setAnimationFrameIndex(0);
			sprite.setAnimationTick(0);
		}
	}

	private void spindashCooldown(AbstractPlayableSprite sprite) {
		float spindashConstant = sprite.getSpindashConstant();
		spindashConstant -= ((spindashConstant / 0.125) / 256);
		if (spindashConstant < 0.01) {
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
	 *               The sprite in question
	 * @param left
	 *               Whether or not the left key is pressed
	 * @param right
	 *               Whether or not the right key is pressed
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
			double angleSign = Math.signum(TrigLookupTable.sinDeg(angle));
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
		gSpeed += slopeRunningVariant * TrigLookupTable.sinDeg(angle);
		if (left) {
			if (gSpeed > 0) {
				gSpeed -= decel;
				if (!sprite.getRolling() && gSpeed >= 4 * 256 && !skidding) {
					audioManager.playSfx(GameSound.SKID);
					skidding = true;
				}
			} else if (!sprite.getRolling()) {
				skidding = false;
				if (gSpeed > -maxSpeed) {
					gSpeed -= accel;
					if (gSpeed < -maxSpeed) {
						gSpeed = (short) -maxSpeed;
					}
				}
			}
		}
		if (right) {
			if (gSpeed < 0) {
				gSpeed += decel;
				if (!sprite.getRolling() && gSpeed <= -4 * 256 && !skidding) {
					audioManager.playSfx(GameSound.SKID);
					skidding = true;
				}
			} else if (!sprite.getRolling()) {
				skidding = false;
				if (gSpeed < maxSpeed) {
					gSpeed = (short) (gSpeed + accel);
					if (gSpeed > maxSpeed) {
						gSpeed = maxSpeed;
					}
				}
			}
		}
		if (!left && !right) {
			skidding = false;
		}
		if ((!left && !right) || (sprite.getRolling() && left && gSpeed < 0)
				|| (sprite.getRolling() && right && gSpeed > 0)) {
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
	 *               The sprite in question
	 */
	private void jump(AbstractPlayableSprite sprite) {
		sprite.setAir(true);
		sprite.setRolling(true);
		audioManager.playSfx(GameSound.JUMP);
		int angle = calculateAngle(sprite);
		jumpPressed = true;
		sprite.setXSpeed((short) (sprite.getXSpeed() + sprite.getJump()
				* TrigLookupTable.sinDeg(angle)));
		sprite.setYSpeed((short) (sprite.getYSpeed() - sprite.getJump()
				* TrigLookupTable.cosDeg(angle)));
	}

	/**
	 * Calculates air movement for sprite based on gravity and current
	 * left/right keypresses. Only to be used when sprite is in the air.
	 *
	 * @param sprite
	 *               The sprite in question
	 * @param left
	 *               Whether or not the left key is pressed
	 * @param right
	 *               Whether or not the right key is pressed
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
		// Air drag: Sonic 2 applies drag only when ySpeed is in [-1024, 0)
		// subpixels/frame
		// (near jump apex, still moving up). Drag is NOT applied while falling or while
		// hurt.
		// Formula: xSpeed = xSpeed - (xSpeed / 32), using integer division (rounds
		// toward zero).
		// This naturally stops when abs(xSpeed) < 32 (since xSpeed/32 becomes 0).
		if (ySpeed < 0 && ySpeed >= -1024 && !sprite.isHurt()) {
			xSpeed = (short) (xSpeed - (xSpeed / 32));
		}
		ySpeed += sprite.getGravity();

		if (ySpeed > 4096) {
			ySpeed = 4096;
		}
		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);
	}

	private void applyDeathMovement(AbstractPlayableSprite sprite) {
		short ySpeed = sprite.getYSpeed();
		ySpeed += sprite.getGravity();
		if (ySpeed > 4096) {
			ySpeed = 4096;
		}
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		sprite.setYSpeed(ySpeed);

		// Check if player has fallen below camera + 256 pixels to start death countdown
		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getMaxY() + 256) {
			sprite.startDeathCountdown();
		}

		// Tick death countdown and trigger level reload when it reaches 0
		if (sprite.tickDeathCountdown()) {
			uk.co.jamesj999.sonic.level.LevelManager.getInstance().loadCurrentLevel();
		}
	}

	/**
	 * Calculates gSpeed from x/y speed and angle when sprite lands. This should
	 * only be used in the frame the sprite regains contact with the ground,
	 * that is when at the start of the tick the sprite is in the air, but a
	 * height >0 is returned from the Terrain Collision Manager.
	 *
	 * @param sprite
	 *               The sprite in question
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
					gSpeed = (short) (ySpeed * 0.5 * (Math.signum(
							TrigLookupTable.sinDeg(angle))));
				}
			} else if ((originalAngle >= (byte) 0xC0 && originalAngle <= (byte) 0xDF)
					|| (originalAngle >= (byte) 0x20 && originalAngle <= (byte) 0x3F)) {
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					gSpeed = (short) (ySpeed * (Math.signum(
							TrigLookupTable.sinDeg(angle))));
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
			// TODO we should only play this if it's not immediately the result of a
			// spindash
			audioManager.playSfx(GameSound.ROLLING);
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

	private void updateCrouchState(AbstractPlayableSprite sprite, boolean down, boolean left, boolean right) {
		boolean crouching = down
				&& !left
				&& !right
				&& !sprite.getAir()
				&& !sprite.getRolling()
				&& !sprite.getSpindash()
				&& sprite.getGSpeed() == 0;
		sprite.setCrouching(crouching);
	}

	/**
	 *
	 * @param sprite
	 *               The sprite in question
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
				* TrigLookupTable.cosDeg(angle)));

		sprite.setYSpeed((short) Math.round(gSpeed
				* TrigLookupTable.sinDeg(angle)));
	}

	private void jumpHandler(boolean jump) {
		short ySpeedConstant = (4 * 256);
		if (sprite.getYSpeed() < -ySpeedConstant) {
			// Don't cap velocity if player is springing - let spring force apply fully
			if (!jump && !sprite.getSpringing()) {
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
			if (result != null) {
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
