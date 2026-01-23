package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.physics.CollisionSystem;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.physics.TrigLookupTable;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;
import uk.co.jamesj999.sonic.timer.TimerManager;
import uk.co.jamesj999.sonic.timer.timers.ControlLockTimer;

import java.util.logging.Logger;

public class PlayableSpriteMovement extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private static final Logger LOGGER = Logger.getLogger(PlayableSpriteMovement.class.getName());

	private final CollisionSystem collisionSystem = CollisionSystem.getInstance();
	private final AudioManager audioManager = AudioManager.getInstance();

	// These values don't change with speed shoes, so we cache them
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

	// Tracks when the down button was held while crouching.
	// When the player crouches (standing still with down held) and then presses
	// left/right, the down button should be "locked" and not trigger rolling.
	// The down key must be released and pressed again to trigger a roll.
	private boolean downLocked;

	private boolean testKeyPressed;

	public PlayableSpriteMovement(AbstractPlayableSprite sprite) {
		super(sprite);
		// Note: max, runAccel, runDecel, and friction are read dynamically from sprite
		// to support speed shoes power-up which modifies these values at runtime
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
		// Store input state for objects (like flippers, Grabber) to query
		sprite.setJumpInputPressed(jump);
		sprite.setDirectionalInputPressed(left, right);

		// DEBUG MODE: When debug mode is active, use simple directional movement
		// with no collision, physics, or damage.
		if (sprite.isDebugMode()) {
			int debugMoveSpeed = 3; // pixels per frame
			if (left) {
				sprite.setX((short) (sprite.getX() - debugMoveSpeed));
			}
			if (right) {
				sprite.setX((short) (sprite.getX() + debugMoveSpeed));
			}
			if (up) {
				sprite.setY((short) (sprite.getY() - debugMoveSpeed));
			}
			if (down) {
				sprite.setY((short) (sprite.getY() + debugMoveSpeed));
			}
			// Skip all physics, collision, and damage processing
			return;
		}

		// OBJECT CONTROLLED MODE: When an object has full control (like spin tubes,
		// corkscrews), skip all physics processing. The controlling object handles
		// position updates directly. This matches ROM's obj_control = $81 behavior.
		if (sprite.isObjectControlled()) {
			// Skip all physics, collision, and movement processing
			// The controlling object is responsible for moving the player
			return;
		}

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

		// Decrement moveLockTimer each frame (ROM: move_lock countdown)
		// This is used by air bubble collection, springs, etc.
		int moveLock = sprite.getMoveLockTimer();
		if (moveLock > 0) {
			sprite.setMoveLockTimer(moveLock - 1);
		}

		// Control is locked by either the timer system or the moveLockTimer counter
		boolean controlLocked = GameServices.timers().getTimerForCode("ControlLock-" + sprite.getCode()) != null
				|| sprite.getMoveLockTimer() > 0;

		// SPG: Store raw button state before control lock modifies it.
		// During control lock, friction is only applied when NO buttons are pressed.
		// If left/right is pressed during lock, no friction is applied (faster slip).
		boolean rawLeft = left;
		boolean rawRight = right;

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

		// Save crouching state before clearing it, so we can detect transitions
		// from crouching to moving (for down key locking)
		boolean wasCrouching = sprite.getCrouching();
		sprite.setCrouching(false);

		// First thing to do is run this additional method to find out if the jump
		// button has been released recently
		// in order to shorten the jump.
		if (jumpPressed) {
			jumpHandler(jump);
		}

		// SPG: When a spring launches the player, reset jumpPressed to prevent
		// the jump velocity cap from applying to the spring launch. Without this,
		// if the player jumped onto the spring and released jump, the velocity
		// would be capped after the springing state ends (15 frames).
		if (sprite.getSpringing()) {
			jumpPressed = false;
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
				calculateGSpeed(sprite, left, right, rawLeft, rawRight);
				// Since this will update the gSpeed, we now need to update the X/Y from this.
				calculateXYFromGSpeed(sprite);
				// Note: Slip/fall check (Sonic_SlopeRepel) moved to after terrain collision
				// to match original game order - it must use the NEW angle from terrain collision
			}
		}

		sprite.updateSensors(originalX, originalY);

		boolean inAir = sprite.getAir();
		if (!inAir) {
			doWallCollision(sprite);
		}

		// ROM order: Sonic_LevelBound is called BEFORE ObjectMove
		// It uses predictive position (x_pos + x_vel) to check boundaries
		doLevelBoundary(sprite);

		// Now, move the sprite as per the air movement or GSpeed rules:
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());

		// SPG: Gravity is applied AFTER position update for accurate jump heights.
		// This must happen before collision checks but after movement.
		if (inAir) {
			applyAirGravity(sprite);
		}

		// Has the sprite slowed down enough to stop rolling? Do we need to start
		// rolling?
		// (Only applicable if not in the air)
		if (!sprite.getAir()) {
			calculateRoll(sprite, down);
			updateCrouchState(sprite, down, left, right, wasCrouching);
		}

		// Store some attributes in case we need to 'reset' the terrain collision:
		short yBeforeTerrainCollision = sprite.getY();
		inAir = sprite.getAir();
		boolean isRoll = sprite.getRolling();

		if (inAir) {
			doWallCollision(sprite);
		}

		// Perform terrain checks via unified collision pipeline
		SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");

		doTerrainCollision(sprite, groundResult);
		doCeilingCollision(sprite, ceilingResult);

		// Sonic_SlopeRepel (s2.asm:37432): Slip/fall check runs AFTER terrain collision
		// so it uses the NEW angle. In the original, this is called after AnglePos.
		// Only check if grounded and controls not locked.
		if (!sprite.getAir() && !controlLocked) {
			short absGSpeed = (short) Math.abs(sprite.getGSpeed());
			int angle = sprite.getAngle() & 0xFF;

			// Original check: addi.b #$20,d0; andi.b #$C0,d0; beq.s return
			// This skips when angle is in flat range: 0x00-0x1F or 0xE0-0xFF
			// SPG: If too slow (speed < 2.5 pixels = 640 subpixels) and on a steep slope
			// (hex angle 32-223, which is ~45°-315° - steep slopes, walls, ceiling)
			boolean angleInFlatRange = (angle <= 0x1F) || (angle >= 0xE0);
			if (absGSpeed < 640 && !angleInFlatRange) {
				// Detach from floor
				sprite.setAir(true);
				// SPG: Only Ground Speed is set to 0, X/Y Speed are PRESERVED
				// This allows player to maintain momentum and land correctly
				sprite.setGSpeed((short) 0);
				// Lock controls for 30 frames (0x1E)
				GameServices.timers()
						.registerTimer(new ControlLockTimer("ControlLock-" + sprite.getCode(), 30, sprite));
			}
		}

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

		// Pit death detection: if Sonic falls below the camera's viewable area, trigger
		// death
		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getY() + camera.getHeight()) {
			sprite.applyPitDeath();
		}

		// Update sprite ground mode for next tick:

		// Update active sensors
		sprite.updateSensors(originalX, originalY);
	}

	private void doCeilingCollision(AbstractPlayableSprite sprite, SensorResult[] results) {
		// Skip ceiling collision when springing - the player was just launched by a spring
		// or tube and needs time to clear the launch point. This matches how objects like
		// PipeExitSpring use getSpringing() to prevent immediate re-collision.
		// ROM: Springs set a temporary immunity period; we use the springing frames.
		if (sprite.getSpringing()) {
			return;
		}

		// Only check ceiling collision if we are moving upwards (ySpeed < 0)
		if (sprite.getYSpeed() < 0) {
			SensorResult lowestResult = findLowestSensorResult(results);
			if (lowestResult != null) {
				// Distance is positive if we are below the ceiling, negative if we have
				// penetrated it.
				// If distance < 0, we have hit the ceiling.
				if (lowestResult.distance() < 0) {
					// We hit the ceiling - correct position
					moveForSensorResult(sprite, lowestResult);

					// ROM: Sonic_DoLevelCollision (s2.asm:37540-37733)
					// The original uses CalcAngle to determine movement direction, then
					// only the 0x80 quadrant (mostly upward) goes to HitCeilingAndWalls
					// which allows ceiling landing. Other quadrants just zero Y velocity.
					//
					// Movement quadrant calculation:
					// moveQuadrant = (CalcAngle(xSpeed, ySpeed) - 0x20) & 0xC0
					// 0x80 quadrant covers movement angles 0xA0-0xDF (mostly upward)
					int moveQuadrant = calculateMovementQuadrant(sprite.getXSpeed(), sprite.getYSpeed());

					if (moveQuadrant == 0x80) {
						// ROM: Sonic_HitCeilingAndWalls (s2.asm:37657-37691)
						// Check if the ceiling is sloped enough to land on.
						// The check (angle + 0x20) & 0x40 determines if surface is suitable for landing:
						// - Angles 0x20-0x5F (32-95): sloped right wall to moderate ceiling-right -> LAND
						// - Angles 0xA0-0xDF (160-223): sloped left wall to moderate ceiling-left -> LAND
						// - Angles 0x60-0x9F (96-159): flat ceiling range -> just bonk (zero Y vel)
						// - Other angles: floor-like, handled elsewhere
						int ceilingAngle = lowestResult.angle() & 0xFF;
						boolean canLandOnCeiling = ((ceilingAngle + 0x20) & 0x40) != 0;

						if (canLandOnCeiling) {
							// Land on the sloped ceiling!
							// ROM: loc_1B02C in Sonic_HitCeilingAndWalls
							sprite.setAngle(lowestResult.angle());

							// Reset sprite to grounded state (Sonic_ResetOnFloor)
							sprite.setAir(false);
							sprite.setRolling(false);
							sprite.setPinballMode(false);

							// ROM: move.w y_vel(a0),inertia(a0)
							// gSpeed is set from Y velocity
							short gSpeed = sprite.getYSpeed();

							// ROM: tst.b d3 / bpl.s return / neg.w inertia(a0)
							// If angle >= 128 (0x80), negate the ground speed
							// This ensures correct running direction on the ceiling
							if ((lowestResult.angle() & 0x80) != 0) {
								gSpeed = (short) -gSpeed;
							}

							sprite.setGSpeed(gSpeed);
							sprite.setYSpeed((short) 0);

							// Update ground mode based on new angle
							updateGroundMode(sprite);
						} else {
							// Flat ceiling - just stop upward movement
							// ROM: move.w #0,y_vel(a0)
							sprite.setYSpeed((short) 0);
						}
					} else {
						// Not moving mostly upward - just stop Y velocity
						// ROM: Sonic_HitCeiling/Sonic_HitCeiling2 paths
						sprite.setYSpeed((short) 0);
					}
				}
			}
		}
	}

	/**
	 * Calculates the movement quadrant from velocity, matching ROM's CalcAngle logic.
	 * ROM: Sonic_DoLevelCollision (s2.asm:37547-37557)
	 *
	 * The quadrant determines which collision path to take:
	 * - 0x00: Moving mostly right/down-right -> floor path
	 * - 0x40: Moving mostly down/down-left -> HitLeftWall path
	 * - 0x80: Moving mostly up/up-left -> HitCeilingAndWalls path (can land on ceiling!)
	 * - 0xC0: Moving mostly up-right/right -> HitRightWall path
	 *
	 * @param xSpeed X velocity (subpixels)
	 * @param ySpeed Y velocity (subpixels)
	 * @return Movement quadrant (0x00, 0x40, 0x80, or 0xC0)
	 */
	private int calculateMovementQuadrant(short xSpeed, short ySpeed) {
		// Handle zero velocity case
		if (xSpeed == 0 && ySpeed == 0) {
			return 0x00;
		}

		// CalcAngle: atan2(y, x) converted to Sonic angle convention
		// MD convention: 0=right, 0x40=down, 0x80=left, 0xC0=up
		// Note: Y is inverted in screen coords (positive = down)
		double radians = Math.atan2(ySpeed, xSpeed);

		// Convert radians to 0-255 angle range
		// atan2 returns -PI to PI, we need 0 to 255
		int moveAngle = (int) Math.round((radians / (2.0 * Math.PI)) * 256.0);
		if (moveAngle < 0) {
			moveAngle += 256;
		}
		moveAngle &= 0xFF;

		// ROM: subi.b #$20,d0 / andi.b #$C0,d0
		return ((moveAngle - 0x20) & 0xC0) & 0xFF;
	}

	/**
	 * Enforces level boundaries, preventing Sonic from leaving the playable area.
	 * ROM: Sonic_LevelBound (s2.asm:36890-36944)
	 *
	 * Key ROM behaviors:
	 * 1. Uses Camera_Min_X/Max_X (not level bounds) - these can be locked by signpost/boss
	 * 2. Calculates predictive position: x_pos + x_vel BEFORE movement is applied
	 * 3. Only adds +64 right buffer when Current_Boss_ID == 0
	 * 4. Checks bottom boundary and kills player if y_pos > Camera_Max_Y + 224
	 *
	 * When a side boundary is hit:
	 * - X position is clamped to the boundary value
	 * - X subpixel is cleared
	 * - X velocity is zeroed
	 * - Ground speed (inertia) is zeroed
	 *
	 * @param sprite The player sprite to check
	 */
	private void doLevelBoundary(AbstractPlayableSprite sprite) {
		Camera camera = Camera.getInstance();

		// ROM constants for boundary calculation
		final int SCREEN_WIDTH = 320;
		final int SONIC_WIDTH = 24;
		final int LEFT_OFFSET = 16;
		final int RIGHT_EXTRA_BUFFER = 64; // Only added when not in boss fight

		// ROM: Calculate predicted position BEFORE movement
		// move.l x_pos(a0),d1 / move.w x_vel(a0),d0 / ext.l d0 / asl.l #8,d0 / add.l d0,d1 / swap d1
		// This is: predicted_x = (x_pos.l + (x_vel << 8)) >> 16 (integer part after adding velocity)
		int xTotal = (sprite.getX() * 256) + (sprite.getXSubpixel() & 0xFF);
		xTotal += sprite.getXSpeed();
		int predictedX = xTotal / 256;

		// ROM: Use Camera_Min_X_pos and Camera_Max_X_pos (which can be locked by signpost/boss)
		int cameraMinX = camera.getMinX();
		int cameraMaxX = camera.getMaxX();

		// Calculate left boundary: Camera_Min_X + 16
		int leftBoundary = cameraMinX + LEFT_OFFSET;

		// Calculate right boundary: Camera_Max_X + screen_width - Sonic_width
		int rightBoundary = cameraMaxX + SCREEN_WIDTH - SONIC_WIDTH;

		// ROM: Only add +64 buffer when not in boss fight (Current_Boss_ID == 0)
		if (!GameServices.gameState().isBossFightActive()) {
			rightBoundary += RIGHT_EXTRA_BUFFER;
		}

		// Check left boundary (ROM: cmp.w d1,d0 / bhi.s Sonic_Boundary_Sides)
		if (predictedX < leftBoundary) {
			sprite.setX((short) leftBoundary); // setX also clears subpixel
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}
		// Check right boundary (ROM: cmp.w d1,d0 / bls.s Sonic_Boundary_Sides)
		else if (predictedX > rightBoundary) {
			sprite.setX((short) rightBoundary); // setX also clears subpixel
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}

		// ROM: Sonic_Boundary_CheckBottom - check if fallen below camera bounds
		// move.w (Camera_Max_Y_pos).w,d0 / addi.w #224,d0 / cmp.w y_pos(a0),d0 / blt.s Sonic_Boundary_Bottom
		int bottomBoundary = camera.getMaxY() + 224;
		if (sprite.getY() > bottomBoundary) {
			// ROM: JmpTo_KillCharacter - player has fallen off the level
			sprite.applyPitDeath();
		}
	}

	private void doWallCollision(AbstractPlayableSprite sprite) {
		if (!sprite.getAir()) {
			doWallCollisionGround(sprite);
		} else {
			doWallCollisionAir(sprite);
		}
	}

	/**
	 * Grounded wall collision using angle-based collision selection.
	 * ROM: Obj01_CheckWallsOnGround (s2.asm:36486)
	 *
	 * The original algorithm rotates the terrain angle by ±0x40 and uses the
	 * resulting quadrant to select which collision check to perform. However,
	 * for quadrants 0x00 (floor) and 0x80 (ceiling), the terrain collision
	 * system already handles those cases. We only need to perform explicit
	 * wall checks for horizontal quadrants (0x40 left, 0xC0 right).
	 *
	 * This prevents false wall detections on curved terrain where the rotated
	 * check would hit the walking surface rather than an actual wall.
	 */
	private void doWallCollisionGround(AbstractPlayableSprite sprite) {
		int angle = sprite.getAngle() & 0xFF;
		short gSpeed = sprite.getGSpeed();

		// Skip condition 1: angle in range 0x40-0xBF (steep slopes, walls, ceiling)
		// ROM: addi.b #$40,d0; bmi.s return
		// On steep terrain, wall collision is handled by terrain collision
		boolean angleInSkipRange = ((angle + 0x40) & 0x80) != 0;
		if (angleInSkipRange) {
			sprite.setPushing(false);
			return;
		}

		// Skip condition 2: not moving
		if (gSpeed == 0) {
			sprite.setPushing(false);
			return;
		}

		// Calculate rotated angle based on movement direction
		// ROM: Moving right (gSpeed > 0): angle - 0x40 (counterclockwise)
		// ROM: Moving left (gSpeed < 0): angle + 0x40 (clockwise)
		int rotatedAngle;
		if (gSpeed >= 0) {
			rotatedAngle = (angle - 0x40) & 0xFF;
		} else {
			rotatedAngle = (angle + 0x40) & 0xFF;
		}

		// Round to quadrant: (angle + 0x20) & 0xC0
		int quadrant = (rotatedAngle + 0x20) & 0xC0;

		// Only perform wall collision for horizontal quadrants (0x40, 0xC0)
		// For floor/ceiling quadrants (0x00, 0x80), the terrain collision
		// system already handles surface collision - we don't want to
		// interfere with that by detecting the walking surface as a "wall"
		if (quadrant != 0x40 && quadrant != 0xC0) {
			sprite.setPushing(false);
			return;
		}

		// Project position by velocity (ROM: CalcRoomInFront)
		short projectedDx = (short) (sprite.getXSpeed() >> 8);
		short projectedDy = (short) (sprite.getYSpeed() >> 8);

		// Select the appropriate push sensor based on quadrant
		// 0x40 = left wall (sensor 0), 0xC0 = right wall (sensor 1)
		int sensorIndex = (quadrant == 0x40) ? 0 : 1;
		SensorResult result = sprite.getPushSensors()[sensorIndex].scan(projectedDx, projectedDy);

		if (result == null) {
			sprite.setPushing(false);
			return;
		}

		byte distance = result.distance();
		int wallAngle = result.angle() & 0xFF;

		// Filter out curved terrain that might be detected as walls.
		// On slopes, if the detected "wall" angle is similar to the terrain angle,
		// it's likely curved terrain (floor wrapping around), not a real wall.
		// Real walls have angles perpendicular to the terrain.
		boolean onSlope = (angle > 0x10 && angle < 0xF0);
		if (onSlope) {
			int angleDiff = Math.abs(wallAngle - angle);
			if (angleDiff > 128) {
				angleDiff = 256 - angleDiff;
			}
			// If angles are within ~45 degrees, it's likely curved terrain
			if (angleDiff < 0x30) {
				sprite.setPushing(false);
				return;
			}
		}

		// Check if we're moving toward the wall (for distance == 0 case)
		boolean movingTowards = (quadrant == 0x40 && sprite.getXSpeed() < 0) ||
								(quadrant == 0xC0 && sprite.getXSpeed() > 0);

		// Apply collision response if penetrating OR exactly at wall and moving toward it
		if (distance < 0 || (distance == 0 && movingTowards)) {
			// Calculate the exact movement needed to reach the wall surface (not past it).
			// distance is the penetration at PROJECTED position.
			// We want to move to the wall, so: allowedMove = projectedDx + distance (for RIGHT)
			// or allowedMove = projectedDx - distance (for LEFT)
			//
			// Example (RIGHT wall): projectedDx = 6, distance = -3
			//   allowedMove = 6 + (-3) = 3 pixels (move right to wall surface)
			// Example (LEFT wall): projectedDx = -6, distance = -3
			//   allowedMove = -6 - (-3) = -3 pixels (move left to wall surface)
			int allowedMovePixels;
			if (quadrant == 0x40) {
				allowedMovePixels = projectedDx - distance;  // LEFT wall
			} else {
				allowedMovePixels = projectedDx + distance;  // RIGHT wall
			}

			// Convert to subpixels, accounting for current subpixel position
			// This ensures pixel-perfect wall placement
			short subX = (short) (sprite.getXSubpixel() & 0xFF);
			sprite.setXSpeed((short) ((allowedMovePixels * 256) - subX));

			sprite.setPushing(true);
			sprite.setGSpeed((short) 0);
		} else {
			sprite.setPushing(false);
		}
	}

	/**
	 * Airborne wall collision using movement quadrant selection.
	 * ROM: Sonic_DoLevelCollision (s2.asm:37548)
	 *
	 * Selectively checks wall sensors based on movement direction to avoid
	 * redundant checks. Still finds the closest collision and applies once.
	 */
	private void doWallCollisionAir(AbstractPlayableSprite sprite) {
		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();

		// Calculate movement quadrant using CalcAngle
		int quadrant = TrigLookupTable.calcMovementQuadrant(xSpeed, ySpeed);

		Sensor[] pushSensors = sprite.getPushSensors();
		SensorResult[] results = new SensorResult[2];

		// Selectively check sensors based on movement quadrant
		// This is an optimization - checking only relevant sensors
		switch (quadrant) {
			case 0x00 -> {
				// Down-right: check both walls
				results[0] = pushSensors[0].scan((short) 0, (short) 0);
				results[1] = pushSensors[1].scan((short) 0, (short) 0);
			}
			case 0x40 -> {
				// Down-left: check left wall only
				results[0] = pushSensors[0].scan((short) 0, (short) 0);
			}
			case 0x80 -> {
				// Up-left: check both walls
				results[0] = pushSensors[0].scan((short) 0, (short) 0);
				results[1] = pushSensors[1].scan((short) 0, (short) 0);
			}
			case 0xC0 -> {
				// Up-right: check right wall only
				results[1] = pushSensors[1].scan((short) 0, (short) 0);
			}
		}

		// Find the closest (most negative distance) result
		SensorResult lowestResult = findLowestSensorResult(results);

		if (lowestResult != null) {
			byte distance = lowestResult.distance();
			Direction dir = lowestResult.direction();

			boolean collision = distance < 0;
			if (!collision && distance == 0) {
				// Edge case: exactly touching wall and moving toward it
				boolean movingTowards = (dir == Direction.LEFT && xSpeed < 0) ||
										(dir == Direction.RIGHT && xSpeed > 0);
				collision = movingTowards;
			}

			if (collision) {
				// Apply position correction if actually penetrating
				// ROM: sub.w d1,x_pos (left wall) / add.w d1,x_pos (right wall)
				// Uses raw sensor distance with no extra offset
				if (distance < 0) {
					moveForSensorResult(sprite, lowestResult);
				}
				// Zero velocities to stop movement into wall
				sprite.setXSpeed((short) 0);
				sprite.setGSpeed((short) 0);
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
				// Disasm (SolidObject_Landed): If Sonic is moving upwards, don't land.
				// This is a critical check from the original game that prevents landing
				// while jumping, even if sensors detect terrain penetration.
				if (sprite.getYSpeed() < 0) {
					return;
				}

				// Work out the ySpeed threshold required to land.
				// REV01 uses the *pixel* y-speed (high byte) with a +8 buffer,
				// then negates it to compare against the signed floor distance.
				// CRITICAL: Must use arithmetic right shift (>>8) not division,
				// because Java division rounds toward zero, but M68K move.b gets
				// the actual high byte. For negative values, >>8 gives correct result.
				short ySpeedPixels = (short) (sprite.getYSpeed() >> 8);
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
			// ROM ACCURACY: AnglePos simply selects the sensor with the smaller distance.
			// See docs/sonic2_rev01_player_collision_sensors.md section on AnglePos:
			// "It selects the closer surface (the smaller distance), adopts the
			// corresponding angle, and returns the chosen distance in d1."

			// ROM ACCURACY: AnglePos uses fixed 0x0E threshold for ground glue.
			// If distance >= 14 (0x0E), treat as "no floor" - Sonic detaches from ground.
			int threshold = 14;

			// BUT: if player is standing on a solid object (bridge, platform),
			// don't set to air based on terrain alone.
			if (lowestResult == null || lowestResult.distance() >= threshold) {
				// Check if player is standing on a solid object before setting to air
				var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
				if (objectManager != null && (objectManager.isRidingObject()
						|| objectManager.hasStandingContact(sprite))) {
					// Player is on an object, don't detach based on terrain
					return;
				}
				sprite.setAir(true);
				return;
			}
			// Use lowestResult (closest sensor) for position and angle updates
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
			// SPG: spinrev is increased by 2, up to a maximum of 8
			float newConstant = sprite.getSpindashConstant() + 2f;
			if (newConstant > 8f) {
				newConstant = 8f;
			}
			sprite.setSpindashConstant(newConstant);
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
		// ROM: Horiz_scroll_delay_val is set to 32 - spinrev (where spinrev is 0-8)
		// This gives a delay range of 24-32 frames for horizontal scroll only.
		// Vertical scroll continues normally (ROM: ScrollVerti doesn't check delay).
		int spinrevForDelay = Math.min(8, (int) Math.floor(sprite.getSpindashConstant()));
		Camera.getInstance().setHorizScrollDelay(32 - spinrevForDelay);

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
		// SPG: spinrev -= ((spinrev div 0.125) / 256)
		// "div" is integer division ignoring any remainder.
		// This is equivalent to: spinrev -= spinrev / 32 (using integer div)
		// Since we store as float but want integer behavior, convert to int for the
		// calc
		float spindashConstant = sprite.getSpindashConstant();
		// Multiply by 256 to work in subpixels, apply integer division, convert back
		int subpixels = (int) (spindashConstant * 256);
		// Integer division: subpixels / 32 (which is (spinrev / 0.125) / 256)
		int decay = subpixels / 32;
		subpixels -= decay;
		spindashConstant = subpixels / 256.0f;
		if (spindashConstant < 0.01f) {
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
	 *                 The sprite in question
	 * @param left
	 *                 Whether or not the left key is pressed (after control lock)
	 * @param right
	 *                 Whether or not the right key is pressed (after control lock)
	 * @param rawLeft
	 *                 Raw left button state (before control lock), for friction
	 *                 check
	 * @param rawRight
	 *                 Raw right button state (before control lock), for friction
	 *                 check
	 */
	private void calculateGSpeed(AbstractPlayableSprite sprite, boolean left,
			boolean right, boolean rawLeft, boolean rawRight) {
		short gSpeed = sprite.getGSpeed();
		int hexAngle = getHexAngle(sprite);

		short friction;
		short slopeRunningVariant;
		short maxSpeed;
		short accel;
		short decel;
		// Read values dynamically from sprite to support speed shoes power-up
		short runAccel = sprite.getRunAccel();
		short runDecel = sprite.getRunDecel();
		short max = sprite.getMax();
		if (!sprite.getRolling()) {
			friction = sprite.getFriction();
			slopeRunningVariant = slopeRunning;
			maxSpeed = max;
			accel = runAccel;
			decel = runDecel;
		} else {
			friction = (short) (sprite.getFriction() / 2);
			// Rolling slope physics matches original Sonic 2 "Sonic_RollRepel" (s2.asm:37393)
			// Original uses 0x50 (80) and divides by 4 when rolling "uphill"
			// Downhill = gSpeed and sin have same sign direction
			// Critical: when gSpeed == 0, original treats as "moving right" (>= 0 branch)
			// Using Math.signum() was wrong because signum(0) == 0 which never matches ±1
			double sinValue = TrigLookupTable.sinHex(hexAngle);
			// goingDownhill when: (moving right AND slope down-right) OR (moving left AND slope down-left)
			boolean goingDownhill = (gSpeed >= 0) == (sinValue >= 0);
			slopeRunningVariant = goingDownhill ? (short) 80 : (short) 20;
			accel = 0;
			decel = rollDecel;
			maxSpeed = maxRoll;
		}
		// Running or rolling on the ground
		// SPG: In Sonic 1/2, walking/running Slope Factor doesn't get subtracted
		// if the Player is stopped (Ground Speed is 0). Rolling slope factor
		// has no check for if Ground Speed is 0 in any of the games.
		// SPG: Slope Factor * sin(Ground Angle) using hex-angle trig
		//
		// ROM ACCURACY FIX: Use pure integer math matching the ROM's calculation:
		// (slopeRunningVariant * sin * 256) >> 8
		// sinHex returns -256 to 256, so (slopeRunningVariant * sinHex) >> 8 gives
		// the correct result. Using floating-point sinHexNormalized compounds
		// rounding errors from the velocity conversion fix.
		if (sprite.getRolling() || gSpeed != 0) {
			int slopeEffect = (slopeRunningVariant * TrigLookupTable.sinHex(hexAngle)) >> 8;
			gSpeed += slopeEffect;
		}
		if (left) {
			if (gSpeed > 0) {
				gSpeed -= decel;
				// SPG Rolling: Friction is ALSO applied during deceleration
				// Unlike running, friction is still in effect while decelerating when rolling.
				// So while decelerating, Ground Speed slows by roll_decel + roll_friction.
				if (sprite.getRolling()) {
					gSpeed -= friction;
				}
				// SPG: At any time deceleration results in Ground Speed changing sign,
				// Ground Speed is set to 0.5 (128 subpixels) in the opposite direction.
				if (gSpeed <= 0) {
					gSpeed = (short) -128;
				}
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
				// SPG Rolling: Friction is ALSO applied during deceleration
				if (sprite.getRolling()) {
					gSpeed += friction; // Add friction (reduce magnitude of negative speed)
				}
				// SPG: At any time deceleration results in Ground Speed changing sign,
				// Ground Speed is set to 0.5 (128 subpixels) in the opposite direction.
				if (gSpeed >= 0) {
					gSpeed = (short) 128;
				}
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
		// SPG: Friction check uses RAW button state. During control lock, if player
		// is pressing left/right (even though input is locked), friction is NOT
		// applied.
		// This means pressing buttons during slip causes faster sliding.
		if ((!rawLeft && !rawRight) || (sprite.getRolling() && left && gSpeed < 0)
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
		// CRITICAL: Calculate angle BEFORE setAir(true), because setAir(true) resets
		// the angle to 0. We need the terrain angle from the current ground position
		// to calculate the correct jump direction for slopes in all ground modes.
		int hexAngle = getHexAngle(sprite);

		// SPG/Disasm: CalcRoomOverHead check - ensure there's at least 6 pixels of
		// headroom in the "overhead" direction (perpendicular to ground) before jumping.
		// This prevents jumping when pressed against a solid object on a slope.
		if (!hasEnoughHeadroom(sprite, hexAngle)) {
			return;
		}

		// Clear solid object riding state to prevent the 16-pixel sticky tolerance
		// from keeping the player grounded on the next frame after jumping.
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		if (objectManager != null) {
			objectManager.clearRidingObject();
		}

		// SPG: In S1/S2/S3K, air control is locked when jumping while rolling.
		// Must capture this BEFORE setAir(true) which could affect state.
		boolean wasRolling = sprite.getRolling();
		sprite.setRollingJump(wasRolling);
		sprite.setAir(true);
		sprite.setRolling(true);
		audioManager.playSfx(GameSound.JUMP);
		jumpPressed = true;
		// SPG: X Speed -= jump_force * sin(Ground Angle)
		// SPG: Y Speed -= jump_force * cos(Ground Angle)
		// Note: Due to Mega Drive clockwise angle system + screen Y-down coordinates,
		// the signs work out such that we ADD sin for X and SUBTRACT cos for Y.
		sprite.setXSpeed((short) (sprite.getXSpeed() + sprite.getJump()
				* TrigLookupTable.sinHexNormalized(hexAngle)));
		sprite.setYSpeed((short) (sprite.getYSpeed() - sprite.getJump()
				* TrigLookupTable.cosHexNormalized(hexAngle)));
	}

	/**
	 * Checks if there's enough headroom for the sprite to jump.
	 * Based on the original game's CalcRoomOverHead routine.
	 * The original game requires at least 6 pixels of room in the "overhead"
	 * direction (perpendicular to the current ground angle) to allow a jump.
	 *
	 * This checks BOTH terrain collision AND solid objects.
	 *
	 * @param sprite The sprite attempting to jump
	 * @param hexAngle The current ground angle in hex format (0-255)
	 * @return true if there's enough headroom, false otherwise
	 */
	private boolean hasEnoughHeadroom(AbstractPlayableSprite sprite, int hexAngle) {
		// Check terrain headroom using ceiling sensors
		int terrainDistance = getTerrainHeadroomDistance(sprite, hexAngle);

		// Check solid object headroom
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		int objectDistance = (objectManager != null)
				? objectManager.getHeadroomDistance(sprite, hexAngle)
				: Integer.MAX_VALUE;

		// Require at least 6 pixels of clearance from BOTH
		return Math.min(terrainDistance, objectDistance) >= 6;
	}

	/**
	 * Gets terrain headroom distance by scanning ceiling sensors.
	 * Based on the original CalcRoomOverHead routine which uses FindFloor/FindWall
	 * to check terrain in the "overhead" direction.
	 *
	 * @param sprite The playable sprite
	 * @param hexAngle The current ground angle (0-255)
	 * @return Distance in pixels to nearest terrain overhead, or Integer.MAX_VALUE if none
	 */
	private int getTerrainHeadroomDistance(AbstractPlayableSprite sprite, int hexAngle) {
		// Determine overhead direction quadrant like the original game:
		// CalcRoomOverHead: add 0x80 to get overhead angle, then add 0x20 and mask to 0xC0
		int overheadAngle = (hexAngle + 0x80) & 0xFF;
		int quadrant = (overheadAngle + 0x20) & 0xC0;

		// For standard ground (quadrant 0x80 = UP), use ceiling sensors
		if (quadrant == 0x80) {
			int minDistance = Integer.MAX_VALUE;
			Sensor[] ceilingSensors = sprite.getCeilingSensors();
			if (ceilingSensors == null) {
				return Integer.MAX_VALUE;
			}
			for (Sensor sensor : ceilingSensors) {
				boolean wasActive = sensor.isActive();
				sensor.setActive(true);
				SensorResult result = sensor.scan();
				sensor.setActive(wasActive);
				if (result != null) {
					// Positive distance = clearance above, negative = penetrating terrain
					int clearance = result.distance() >= 0 ? result.distance() : 0;
					if (clearance < minDistance) {
						minDistance = clearance;
					}
				}
			}
			return minDistance;
		}

		// For slopes (quadrant 0x40 = LEFT, 0xC0 = RIGHT), could add push sensor checks
		// For now, return max value (no terrain obstruction) for edge cases
		return Integer.MAX_VALUE;
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
		// Read values dynamically from sprite to support speed shoes power-up
		short runAccel = sprite.getRunAccel();
		short max = sprite.getMax();
		// In the air
		// SPG: In Sonic 1, 2, 3, and Knuckles, you can't control the Player's
		// trajectory through the air with the buttons if you jump while rolling.
		// SPG: Additionally, air control is disabled while in the hurt/knockback state
		// (after taking damage but before landing on the ground).
		if (!sprite.getRollingJump() && !sprite.isHurt()) {
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
		}
		// Air drag: Sonic 2 applies drag only when ySpeed is in [-1024, 0)
		// subpixels/frame (SPG: Y Speed < 0 AND Y Speed >= -4 pixels).
		// (near jump apex, still moving up). Drag is NOT applied while falling or while
		// hurt.
		// Formula: xSpeed = xSpeed - (xSpeed / 32), using integer division (rounds
		// toward zero).
		// This naturally stops when abs(xSpeed) < 32 (since xSpeed/32 becomes 0).
		// Original: cmpi.w #-$400,y_vel; blo.s skip - includes -$400 in drag range.
		if (ySpeed < 0 && ySpeed >= -1024 && !sprite.isHurt()) {
			xSpeed = (short) (xSpeed - (xSpeed / 32));
		}
		// SPG: Gravity is applied AFTER position update, not here.
		// See applyAirGravity() which is called after sprite.move()
		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);

		// SPG: Ground Angle smoothly returns toward 0 by 2 hex units per frame while airborne.
		// This affects the visual rotation of the sprite.
		sprite.returnAngleToZero();
	}

	/**
	 * Applies gravity to airborne sprite. SPG specifies this must happen
	 * AFTER position update for accurate jump heights.
	 */
	private void applyAirGravity(AbstractPlayableSprite sprite) {
		short ySpeed = sprite.getYSpeed();
		ySpeed += sprite.getGravity();
		// Note: Sonic 2 has NO terminal velocity cap for falling.
		// The cap was added in Sonic CD (1993). Sonic 2 (1992) allows
		// unlimited falling speed, matching Sonic 1 behavior.
		sprite.setYSpeed(ySpeed);
	}

	private void applyDeathMovement(AbstractPlayableSprite sprite) {
		short ySpeed = sprite.getYSpeed();
		ySpeed += sprite.getGravity();
		// No terminal velocity cap - matches Sonic 2 behavior
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		sprite.setYSpeed(ySpeed);

		// Check if player has fallen 256 pixels below the camera view to start death
		// countdown
		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getY() + camera.getHeight() + 256) {
			sprite.startDeathCountdown();
		}

		// Tick death countdown and trigger respawn request when it reaches 0
		// GameLoop will handle the fade-to-black transition before respawning
		if (sprite.tickDeathCountdown()) {
			GameServices.gameState().loseLife();
			uk.co.jamesj999.sonic.level.LevelManager.getInstance().requestRespawn();
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
		sprite.setAir(false);
		// Sonic_ResetOnFloor (s2.asm:37744): Check pinball_mode first - if set, skip clearing rolling
		// This allows spin tubes and other "must roll" areas to preserve rolling state on landing
		boolean hadPinballMode = sprite.getPinballMode();
		if (!hadPinballMode) {
			sprite.setRolling(false);
		} else {
			LOGGER.fine("calculateLanding: pinballMode protected rolling, pos=(" + sprite.getX() + "," + sprite.getY() + ")");
		}
		// Clear pinball mode after the landing check - it only needs to protect one landing
		// The autoroll triggers in ROM would clear this at area exits, but we do it here
		if (hadPinballMode) {
			LOGGER.fine("calculateLanding: clearing pinballMode after landing protection");
		}
		sprite.setPinballMode(false);
		short ySpeed = sprite.getYSpeed();
		short xSpeed = sprite.getXSpeed();
		short gSpeed = sprite.getGSpeed();
		int hexAngle = getHexAngle(sprite);
		if (ySpeed > 0) {
			byte originalAngle = sprite.getAngle();
			int unsignedAngle = originalAngle & 0xFF;
			// SPG Landing Angle Ranges (when falling downward):
			// Flat: 0xF0-0xFF (240-255) and 0x00-0x0F (0-15) -> gSpeed = xSpeed
			// Slope: 0xE0-0xFF (224-255) and 0x00-0x1F (0-31) -> depends on movement
			// Steep: Anything else -> depends on movement
			boolean isFlat = (unsignedAngle >= 0xF0) || (unsignedAngle <= 0x0F);
			boolean isSlope = (unsignedAngle >= 0xE0) || (unsignedAngle <= 0x1F);

			if (isFlat) {
				// SPG Flat: Ground Speed is set to the value of X Speed
				gSpeed = xSpeed;
			} else if (isSlope) {
				// SPG Slope: Use X Speed if moving mostly horizontal, else Y Speed * 0.5
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					// SPG: Ground Speed = Y Speed * 0.5 * -sign(sin(Ground Angle))
					// Note: Due to MD clockwise angles, sign is inverted
					gSpeed = (short) (ySpeed * 0.5 * Math.signum(
							TrigLookupTable.sinHex(hexAngle)));
				}
			} else {
				// SPG Steep: Use X Speed if moving mostly horizontal, else full Y Speed
				if (Math.abs(xSpeed) > Math.abs(ySpeed)) {
					gSpeed = xSpeed;
				} else {
					// SPG: Ground Speed = Y Speed * -sign(sin(Ground Angle))
					// Note: Due to MD clockwise angles, sign is inverted
					gSpeed = (short) (ySpeed * Math.signum(
							TrigLookupTable.sinHex(hexAngle)));
				}
			}
		}
		sprite.setGSpeed(gSpeed);
	}

	private void calculateRoll(AbstractPlayableSprite sprite, boolean down) {
		short gSpeed = sprite.getGSpeed();

		// If the player is pressing down, we're not in the air, we're not
		// currently rolling, down is not locked, and our ground speed is greater
		// than the minimum speed, start rolling:
		if (down && !downLocked && !sprite.getAir() && !sprite.getRolling()
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
		// speed then check if we should stop rolling:
		if (sprite.getRolling()
				&& ((gSpeed < minRollSpeed && gSpeed >= 0) || (gSpeed > -minRollSpeed && gSpeed <= 0))) {
			// Sonic_CheckRollStop (s2.asm:36712): If pinball_mode is set, give a boost
			// instead of stopping. This keeps the player rolling in "must roll" areas.
			if (sprite.getPinballMode()) {
				// Sonic_KeepRolling: move.w #$400,inertia(a0) / neg if facing left
				short boost = 0x400; // 1024 subpixels = 4 pixels/frame
				if (sprite.getDirection() == uk.co.jamesj999.sonic.physics.Direction.LEFT) {
					boost = (short) -boost;
				}
				sprite.setGSpeed(boost);
				LOGGER.fine("calculateRoll: pinballMode boost applied, gSpeed was " + gSpeed + ", now " + boost);
			} else {
				LOGGER.fine("calculateRoll: STOPPING ROLL - gSpeed=" + gSpeed + ", pinballMode=false" +
						", pos=(" + sprite.getX() + "," + sprite.getY() + ")");
				sprite.setRolling(false);
			}
		}
	}

	private void updateCrouchState(AbstractPlayableSprite sprite, boolean down, boolean left, boolean right,
			boolean wasCrouching) {

		boolean crouching = down
				&& !left
				&& !right
				&& !sprite.getAir()
				&& !sprite.getRolling()
				&& !sprite.getSpindash()
				&& sprite.getGSpeed() == 0;
		sprite.setCrouching(crouching);

		// If the player was crouching (standing still with down held) and now
		// presses left or right, lock the down key to prevent rolling from
		// triggering. The down key must be released and pressed again to trigger
		// a roll. This prevents the oscillating roll/stop-rolling bug.
		if (wasCrouching && (left || right)) {
			downLocked = true;
		}

		// Unlock the down key when it is released
		if (!down) {
			downLocked = false;
		}
	}

	/**
	 * Gets the hex angle from the sprite.
	 * SPG: Uses 256-step hex angles directly without converting to degrees.
	 *
	 * @param sprite
	 *               The sprite in question
	 * @return The hex angle (0x00-0xFF), suitable for use with
	 *         TrigLookupTable.sinHex/cosHex
	 */
	private int getHexAngle(AbstractPlayableSprite sprite) {
		return sprite.getAngle() & 0xFF;
	}

	/**
	 * Legacy method for compatibility - converts hex angle to degrees.
	 * 
	 * @deprecated Use getHexAngle with hex-based trig functions instead.
	 *
	 * @param sprite
	 *               The sprite in question
	 * @return The correct angle, based on 360 degree rotation.
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
		int hexAngle = getHexAngle(sprite);
		// SPG: X Speed = Ground Speed * cos(Ground Angle)
		// SPG: Y Speed = Ground Speed * -sin(Ground Angle)
		// Note: In screen coords (Y+ down) and MD clockwise angles, the sign works out
		// such that Y Speed = gSpeed * sin(angle) gives correct direction.
		//
		// ROM ACCURACY FIX: Use integer math with arithmetic shift right (>>8) instead
		// of Math.round(). The ROM uses asr.l #8 which rounds toward negative infinity.
		// Math.round() rounds to nearest, causing 1-subpixel differences per frame
		// that accumulate on curves. sinHex/cosHex return -256 to 256, so we multiply
		// and shift: (gSpeed * trig) >> 8
		sprite.setXSpeed((short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8));
		sprite.setYSpeed((short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8));
	}

	private void jumpHandler(boolean jump) {
		// SPG: Jump release cap is -4 pixels (-1024 subpixels) normally,
		// but -2 pixels (-512 subpixels) when underwater
		short ySpeedConstant = sprite.isInWater() ? (short) (2 * 256) : (short) (4 * 256);
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

