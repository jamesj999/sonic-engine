package uk.co.jamesj999.sonic.sprites.managers;

import uk.co.jamesj999.sonic.game.GameServices;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.physics.CollisionSystem;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.physics.GroundSensor;
import uk.co.jamesj999.sonic.physics.Sensor;
import uk.co.jamesj999.sonic.physics.SensorResult;
import uk.co.jamesj999.sonic.physics.TrigLookupTable;
import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.objects.SkidDustObjectInstance;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.util.logging.Logger;

/**
 * ROM-accurate movement handler for playable sprites.
 * Implements exact order of operations from Sonic 2 ROM disassembly (s2.asm:36145-37700).
 *
 * Movement modes based on air/rolling status:
 * - Obj01_MdNormal: ground walking (air=false, rolling=false)
 * - Obj01_MdRoll: ground rolling (air=false, rolling=true)
 * - Obj01_MdAir/MdJump: airborne (air=true)
 */
public class PlayableSpriteMovement extends AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private static final Logger LOGGER = Logger.getLogger(PlayableSpriteMovement.class.getName());

	// ROM spindash speed table (s2.asm:37294) - indexed by spindash_counter >> 8
	private static final short[] SPINDASH_SPEEDS = {
		0x0800, 0x0880, 0x0900, 0x0980, 0x0A00, 0x0A80, 0x0B00, 0x0B80, 0x0C00
	};

	// Angle classification thresholds
	private static final int ANGLE_STEEP_OFFSET = 0x20;
	private static final int ANGLE_STEEP_MASK = 0x40;
	private static final int ANGLE_FLAT_OFFSET = 0x10;
	private static final int ANGLE_FLAT_MASK = 0x20;
	private static final int ANGLE_SLOPE_OFFSET = 0x20;
	private static final int ANGLE_SLOPE_MASK = 0xC0;
	private static final int ANGLE_WALL_OFFSET = 0x40;
	private static final int ANGLE_WALL_MASK = 0x80;

	// Speed thresholds
	private static final int SLOPE_REPEL_MIN_SPEED = 0x280;
	private static final int SKID_SPEED_THRESHOLD = 0x400;
	private static final int YSPEED_LANDING_CAP = 0xFC0;
	private static final int UPWARD_VELOCITY_CAP = -0xFC0;

	// Movement constants
	private static final int MOVE_LOCK_FRAMES = 0x1E;
	private static final int DEBUG_MOVE_SPEED = 3;
	private static final int CONTROLLED_ROLL_DECEL = 0x20;

	private final CollisionSystem collisionSystem = CollisionSystem.getInstance();
	private final AudioManager audioManager = AudioManager.getInstance();

	// Cached speed constants (don't change with speed shoes)
	private final short slopeRunning;
	private final short minStartRollSpeed;
	private final short maxRoll;
	private final short slopeRollingUp;
	private final short slopeRollingDown;
	private final short rollDecel;

	// Input tracking
	private boolean jumpPressed;
	private boolean jumpPrevious;
	private boolean downLocked;  // Prevents roll when transitioning from crouch
	private boolean testKeyPressed;

	// Current frame input state
	private boolean inputUp, inputDown, inputLeft, inputRight;
	private boolean inputJump, inputJumpPress;
	private boolean inputRawLeft, inputRawRight;
	private boolean wasCrouching;

	// Debug flag for loop fall-through investigation
	private static final boolean loopDebugEnabled = true;

	public PlayableSpriteMovement(AbstractPlayableSprite sprite) {
		super(sprite);
		slopeRunning = sprite.getSlopeRunning();
		minStartRollSpeed = sprite.getMinStartRollSpeed();
		maxRoll = sprite.getMaxRoll();
		slopeRollingUp = sprite.getSlopeRollingUp();
		slopeRollingDown = sprite.getSlopeRollingDown();
		rollDecel = sprite.getRollDecel();
	}

	@Override
	public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean jump, boolean testKey) {
		sprite.setJumpInputPressed(jump);
		sprite.setDirectionalInputPressed(left, right);

		if (sprite.isDebugMode()) {
			handleDebugMovement(up, down, left, right);
			return;
		}

		if (sprite.isObjectControlled()) {
			return;
		}

		handleTestKey(testKey);

		boolean controlLocked = sprite.getMoveLockTimer() > 0;
		inputRawLeft = left;
		inputRawRight = right;

		if (controlLocked || sprite.getSpringing() || sprite.isHurt()) {
			left = right = up = down = false;
		}

		updatePushingOnDirectionChange(left, right);

		short originalX = sprite.getX();
		short originalY = sprite.getY();

		if (sprite.getDead()) {
			applyDeathMovement();
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			sprite.updateSensors(originalX, originalY);
			return;
		}

		wasCrouching = sprite.getCrouching();
		sprite.setCrouching(false);

		storeInputState(up, down, left, right, jump);

		if (sprite.getSpringing()) {
			jumpPressed = false;
		}
		if (!sprite.getAir() && !inputJump) {
			jumpPressed = false;
		}

		// Mode dispatch (ROM: Obj01_MdNormal_Checks)
		if (sprite.getAir()) {
			modeAirborne();
		} else if (sprite.getRolling()) {
			modeRoll();
		} else {
			modeNormal();
		}

		updateFacingDirection();
		checkPitDeath();
		sprite.updateSensors(originalX, originalY);
	}

	// ========================================
	// MODE METHODS
	// ========================================

	/** Obj01_MdNormal: Ground walking state */
	private void modeNormal() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		if (doCheckSpindash()) return;
		if (inputJump && !jumpPressed && doJump()) {
			modeAirborne();
			return;
		}

		doSlopeResist();
		doGroundMove();
		doCheckStartRoll();
		doLevelBoundary();
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
		doAnglePosWithSensorUpdate(originalX, originalY);
		doSlopeRepel();
		updateCrouchState();
	}

	/** Obj01_MdRoll: Rolling on ground state */
	private void modeRoll() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		if (!sprite.getPinballMode() && inputJump && !jumpPressed && doJump()) {
			modeAirborne();
			return;
		}

		doRollRepel();
		doRollSpeed();
		doLevelBoundary();
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
		doAnglePosWithSensorUpdate(originalX, originalY);
		doSlopeRepel();
	}

	/** Obj01_MdAir/MdJump: Airborne state */
	private void modeAirborne() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		doJumpHeight();
		doChgJumpDir();
		doLevelBoundary();
		doObjectMoveAndFall();

		// Underwater gravity reduction (net gravity = 0x38 - 0x28 = 0x10)
		if (sprite.isInWater()) {
			sprite.setYSpeed((short) (sprite.getYSpeed() - 0x28));
		}

		sprite.returnAngleToZero();
		sprite.updateSensors(originalX, originalY);
		doLevelCollision();
	}

	// ========================================
	// SPINDASH
	// ========================================

	/** Sonic_CheckSpindash: Check for spindash initiation (s2.asm:37206) */
	private boolean doCheckSpindash() {
		if (sprite.getSpindash()) {
			return doUpdateSpindash();
		}

		int duckAnimId = getDuckAnimId();
		if (duckAnimId < 0 || sprite.getAnimationId() != duckAnimId) {
			return false;
		}
		if (!inputJumpPress) {
			return false;
		}

		setSpindashAnimation();
		audioManager.playSfx(GameSound.SPINDASH_CHARGE);
		sprite.setSpindash(true);
		sprite.setSpindashCounter((short) 0);
		doLevelBoundaryAndAnglePos();
		return true;
	}

	/** Sonic_UpdateSpindash: Handle spindash charging/release (s2.asm:37239) */
	private boolean doUpdateSpindash() {
		if (!inputDown) {
			doReleaseSpindash();
			return false;
		}

		// Decay counter every frame
		short counter = sprite.getSpindashCounter();
		if (counter != 0) {
			counter = (short) Math.max(0, counter - (counter >> 5));
			sprite.setSpindashCounter(counter);
		}

		// Add charge on jump press
		if (inputJumpPress) {
			setSpindashAnimation();
			float pitch = 1.0f + (sprite.getSpindashCounter() / 2048.0f) / 3.0f;
			audioManager.playSfx(GameSound.SPINDASH_CHARGE, pitch);
			counter = (short) Math.min(sprite.getSpindashCounter() + 0x200, 0x800);
			sprite.setSpindashCounter(counter);
		}

		doLevelBoundaryAndAnglePos();
		return true;
	}

	/** Release charged spindash (s2.asm:37244) */
	private void doReleaseSpindash() {
		sprite.applyRollingRadii(false);
		setRollAnimation();
		sprite.setY((short) (sprite.getY() + 5));
		sprite.setSpindash(false);

		int speedIndex = Math.min((sprite.getSpindashCounter() >> 8) & 0xFF, 8);
		short spindashGSpeed = SPINDASH_SPEEDS[speedIndex];

		Camera.getInstance().setHorizScrollDelay(32 - ((spindashGSpeed - 0x800) >> 7));

		if (Direction.LEFT.equals(sprite.getDirection())) {
			spindashGSpeed = (short) -spindashGSpeed;
		}
		sprite.setGSpeed(spindashGSpeed);
		sprite.setRolling(true);

		audioManager.playSfx(GameSound.SPINDASH_RELEASE);
		doLevelBoundaryAndAnglePos();
	}

	// ========================================
	// JUMP
	// ========================================

	/** Sonic_Jump: Handle jump initiation (s2.asm:36996) */
	private boolean doJump() {
		int hexAngle = sprite.getAngle() & 0xFF;

		if (!hasEnoughHeadroom(hexAngle)) {
			return false;
		}

		clearRidingObject();
		boolean wasRolling = sprite.getRolling();

		// Apply jump velocity based on terrain angle
		int xJumpChange = (TrigLookupTable.sinHex(hexAngle) * sprite.getJump()) >> 8;
		int yJumpChange = (TrigLookupTable.cosHex(hexAngle) * sprite.getJump()) >> 8;
		sprite.setXSpeed((short) (sprite.getXSpeed() + xJumpChange));
		sprite.setYSpeed((short) (sprite.getYSpeed() - yJumpChange));

		sprite.setAir(true);
		sprite.setPushing(false);
		sprite.setJumping(true);
		jumpPressed = true;
		sprite.setStickToConvex(false);
		audioManager.playSfx(GameSound.JUMP);

		if (!wasRolling) {
			sprite.applyRollingRadii(true);
			sprite.setRolling(true);
			sprite.setY((short) (sprite.getY() + 5));
		} else {
			sprite.setRollingJump(true);
		}

		return true;
	}

	/** Sonic_JumpHeight: Jump release velocity cap (s2.asm:37076) */
	private void doJumpHeight() {
		if (jumpPressed) {
			short ySpeedCap = sprite.isInWater() ? (short) 0x200 : (short) 0x400;
			if (sprite.getYSpeed() < -ySpeedCap && !inputJump && !sprite.getSpringing()) {
				sprite.setYSpeed((short) -ySpeedCap);
			}
			if (!sprite.getAir() && !inputJump) {
				jumpPressed = false;
			}
		} else {
			applyUpwardVelocityCap();
		}
	}

	// ========================================
	// GROUND MOVEMENT
	// ========================================

	/** Sonic_SlopeResist: Apply slope factor when walking (s2.asm:37360) */
	private void doSlopeResist() {
		int hexAngle = sprite.getAngle() & 0xFF;
		short gSpeed = sprite.getGSpeed();

		if (isOnSteepSurface(hexAngle) || gSpeed == 0) {
			return;
		}

		int slopeEffect = (slopeRunning * TrigLookupTable.sinHex(hexAngle)) >> 8;
		sprite.setGSpeed((short) (gSpeed + slopeEffect));
	}

	/** Sonic_Move: Ground input handling, accel/decel, wall collision (s2.asm:36220) */
	private void doGroundMove() {
		short gSpeed = sprite.getGSpeed();
		short runAccel = sprite.getRunAccel();
		short runDecel = sprite.getRunDecel();
		short friction = sprite.getFriction();
		short max = sprite.getMax();
		Camera camera = Camera.getInstance();

		// Move lock - skip input, apply traction
		if (sprite.getMoveLockTimer() > 0) {
			if (camera != null) camera.resetYBias();
			calculateXYFromGSpeed();
			doWallCollisionGround();
			return;
		}

		// Left input
		if (inputLeft) {
			if (gSpeed > 0) {
				gSpeed -= runDecel;
				if (gSpeed <= 0) gSpeed = (short) -128;
				if (isOnFlatGround() && gSpeed > SKID_SPEED_THRESHOLD) {
					handleSkid();
				}
			} else {
				sprite.setSkidding(false);
				// Only accelerate if below max - don't cap existing high speed
				if (gSpeed > -max) {
					gSpeed -= runAccel;
					if (gSpeed < -max) gSpeed = (short) -max;
				}
			}
		}

		// Right input
		if (inputRight) {
			if (gSpeed < 0) {
				gSpeed += runDecel;
				if (gSpeed >= 0) gSpeed = (short) 128;
				if (isOnFlatGround() && gSpeed < -SKID_SPEED_THRESHOLD) {
					handleSkid();
				}
			} else {
				sprite.setSkidding(false);
				// Only accelerate if below max - don't cap existing high speed
				if (gSpeed < max) {
					gSpeed += runAccel;
					if (gSpeed > max) gSpeed = max;
				}
			}
		}

		if (!inputLeft && !inputRight) {
			sprite.setSkidding(false);
		}

		// Standing still handling
		if (isOnFlatGround() && gSpeed == 0) {
			sprite.setPushing(false);
			if (camera != null) {
				if (inputUp) camera.setLookUpBias();
				else if (inputDown) camera.setLookDownBias();
				else camera.resetYBias();
			}
		} else if (camera != null) {
			camera.resetYBias();
		}

		// Friction
		if (!inputRawLeft && !inputRawRight) {
			gSpeed = applyFriction(gSpeed, friction);
		}

		sprite.setGSpeed(gSpeed);
		calculateXYFromGSpeed();
		doWallCollisionGround();
	}

	/** Sonic_Roll: Check if should start rolling (s2.asm:36954) */
	private void doCheckStartRoll() {
		short gSpeed = sprite.getGSpeed();

		if (Math.abs(gSpeed) < minStartRollSpeed) return;
		if (inputLeft || inputRight) return;
		if (!inputDown || downLocked) return;
		if (sprite.getAir() || sprite.getRolling()) return;

		sprite.setRolling(true);
		sprite.setY((short) (sprite.getY() + 5));
		audioManager.playSfx(GameSound.ROLLING);

		if (sprite.getGSpeed() == 0) {
			sprite.setGSpeed((short) 0x200);
		}
	}

	/** Sonic_RollRepel: Apply rolling slope factor 80/20 (s2.asm:37393) */
	private void doRollRepel() {
		int hexAngle = sprite.getAngle() & 0xFF;
		short gSpeed = sprite.getGSpeed();

		if (isOnSteepSurface(hexAngle)) return;

		// ROM uses $50 (80) base factor, reduced to $50 >> 2 (20) when going uphill
		boolean goingDownhill = (gSpeed >= 0) == (TrigLookupTable.sinHex(hexAngle) >= 0);
		int slopeFactor = goingDownhill ? slopeRollingDown : slopeRollingUp;
		int slopeEffect = (slopeFactor * TrigLookupTable.sinHex(hexAngle)) >> 8;

		sprite.setGSpeed((short) (gSpeed + slopeEffect));
	}

	/** Sonic_RollSpeed: Roll deceleration and velocity conversion (s2.asm:36666) */
	private void doRollSpeed() {
		short gSpeed = sprite.getGSpeed();
		Camera.getInstance().resetYBias();

		boolean inputAllowed = sprite.getMoveLockTimer() == 0;

		// Controlled deceleration
		if (inputAllowed && inputLeft && gSpeed > 0) {
			gSpeed -= CONTROLLED_ROLL_DECEL;
			if (gSpeed <= 0) gSpeed = (short) -128;
		}
		if (inputAllowed && inputRight && gSpeed < 0) {
			gSpeed += CONTROLLED_ROLL_DECEL;
			if (gSpeed >= 0) gSpeed = (short) 128;
		}

		// Natural deceleration
		if (gSpeed != 0) {
			short naturalDecel = (short) (sprite.getRunAccel() / 2);
			gSpeed = applyFriction(gSpeed, naturalDecel);
		}

		// Stop rolling check
		if (gSpeed == 0) {
			if (sprite.getPinballMode()) {
				gSpeed = (short) (sprite.getDirection() == Direction.LEFT ? -0x400 : 0x400);
			} else {
				sprite.setRolling(false);
				sprite.setY((short) (sprite.getY() - 5));
			}
		}

		sprite.setGSpeed(gSpeed);

		// Convert to X/Y with cap
		int hexAngle = sprite.getAngle() & 0xFF;
		short xVel = (short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
		short yVel = (short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8);
		xVel = (short) Math.max(-0x1000, Math.min(0x1000, xVel));

		sprite.setXSpeed(xVel);
		sprite.setYSpeed(yVel);
		doWallCollisionGround();
	}

	// ========================================
	// AIR MOVEMENT
	// ========================================

	/** Sonic_ChgJumpDir: Air control and drag (s2.asm:36815) */
	private void doChgJumpDir() {
		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();
		short runAccel = sprite.getRunAccel();
		short max = sprite.getMax();

		// Air control (skip if rolling jump)
		// ROM behavior (s2.asm:36826-36840): ALWAYS apply acceleration and cap.
		// Unlike ground movement, air control caps even high speeds from slopes/springs.
		if (!sprite.getRollingJump()) {
			if (inputLeft) {
				sprite.setDirection(Direction.LEFT);
				xSpeed -= (2 * runAccel);
				if (xSpeed < -max) xSpeed = (short) -max;
			}
			if (inputRight) {
				sprite.setDirection(Direction.RIGHT);
				xSpeed += (2 * runAccel);
				if (xSpeed > max) xSpeed = max;
			}
		}

		Camera.getInstance().resetYBias();

		// Air drag near apex (-1024 <= ySpeed < 0)
		if (ySpeed < 0 && ySpeed >= -1024) {
			int drag = xSpeed / 32;
			if (drag != 0) {
				int newXSpeed = xSpeed - drag;
				if ((xSpeed > 0 && newXSpeed < 0) || (xSpeed < 0 && newXSpeed > 0)) {
					newXSpeed = 0;
				}
				xSpeed = (short) newXSpeed;
			}
		}

		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);
	}

	/** ObjectMoveAndFall: Apply velocity and gravity */
	private void doObjectMoveAndFall() {
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
		sprite.setYSpeed((short) (sprite.getYSpeed() + sprite.getGravity()));
	}

	// ========================================
	// COLLISION
	// ========================================

	/** Sonic_LevelBound: Check level boundaries (s2.asm:36890) */
	private void doLevelBoundary() {
		Camera camera = Camera.getInstance();
		if (camera == null) return;

		final int SCREEN_WIDTH = 320, SONIC_WIDTH = 24, LEFT_OFFSET = 16, RIGHT_EXTRA = 64;

		int xTotal = (sprite.getX() * 256) + (sprite.getXSubpixel() & 0xFF) + sprite.getXSpeed();
		int predictedX = xTotal >> 8;

		int leftBoundary = camera.getMinX() + LEFT_OFFSET;
		int rightBoundary = camera.getMaxX() + SCREEN_WIDTH - SONIC_WIDTH;
		if (!GameServices.gameState().isBossFightActive()) {
			rightBoundary += RIGHT_EXTRA;
		}

		if (predictedX < leftBoundary) {
			sprite.setX((short) leftBoundary);
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		} else if (predictedX > rightBoundary) {
			sprite.setX((short) rightBoundary);
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}

		if (sprite.getY() > camera.getMaxY() + 224) {
			sprite.applyPitDeath();
		}
	}

	/** AnglePos: Ground terrain collision (s2.asm:42534) */
	private void doAnglePos() {
		if (sprite.isOnObject()) {
			sprite.setAngle((byte) 0);
			return;
		}

		updateGroundMode();

		// Debug: log every frame when in non-GROUND mode to track loop traversal
		GroundMode currentMode = sprite.getGroundMode();
		boolean inLoopMode = currentMode != GroundMode.GROUND;

		SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
		SensorResult leftSensor = groundResult[0];
		SensorResult rightSensor = groundResult[1];
		SensorResult selectedResult = selectSensorWithAngle(rightSensor, leftSensor);

		if (loopDebugEnabled && inLoopMode) {
			var sensors = sprite.getGroundSensors();
			short[] off0 = sensors[0].getRotatedOffset();
			short[] off1 = sensors[1].getRotatedOffset();
			System.out.printf("[LOOP-FRAME] mode=%s angle=0x%02X pos=(%d,%d) centre=(%d,%d)%n",
					currentMode, sprite.getAngle() & 0xFF,
					sprite.getX(), sprite.getY(), sprite.getCentreX(), sprite.getCentreY());
			System.out.printf("  S0: off=(%d,%d) scanPos=(%d,%d) dist=%d angle=0x%02X%n",
					off0[0], off0[1], sprite.getCentreX() + off0[0], sprite.getCentreY() + off0[1],
					leftSensor != null ? leftSensor.distance() : -999,
					leftSensor != null ? leftSensor.angle() & 0xFF : 0);
			System.out.printf("  S1: off=(%d,%d) scanPos=(%d,%d) dist=%d angle=0x%02X%n",
					off1[0], off1[1], sprite.getCentreX() + off1[0], sprite.getCentreY() + off1[1],
					rightSensor != null ? rightSensor.distance() : -999,
					rightSensor != null ? rightSensor.angle() & 0xFF : 0);
			System.out.printf("  selected=%s dist=%d topSolidBit=%d%n",
					selectedResult == leftSensor ? "S0" : (selectedResult == rightSensor ? "S1" : "null"),
					selectedResult != null ? selectedResult.distance() : -999,
					sprite.getTopSolidBit());
		}

		if (selectedResult == null) {
			if (!hasObjectSupport()) {
				// Debug: log airborne due to no sensor result
				if (loopDebugEnabled && sprite.getGroundMode() != GroundMode.GROUND) {
					System.out.printf("[AIR-NULL] No sensor result | mode=%s angle=0x%02X pos=(%d,%d) gSpeed=%d topSolidBit=%d%n",
							sprite.getGroundMode(), sprite.getAngle() & 0xFF,
							sprite.getX(), sprite.getY(), sprite.getGSpeed(), sprite.getTopSolidBit());
					// If on secondary layer, this is critical - dump terrain info
					if (sprite.getTopSolidBit() == 14) {
						System.out.println("[CRITICAL] Airborne on secondary layer - terrain may lack secondary collision");
						dumpTerrainAroundSprite();
						dumpPlaneSwitchers();
					}
				}
				sprite.setAir(true);
				sprite.setPushing(false);
			}
			return;
		}

		byte distance = selectedResult.distance();
		if (distance == 0) {
			// ROM: mode is determined ONCE at the start of AnglePos, not updated again
			return;
		}

		if (distance < 0) {
			if (distance >= -14) {
				moveForSensorResult(selectedResult);
			}
			// ROM: mode is determined ONCE at the start of AnglePos, not updated again
			return;
		}

		// Positive distance - speed-dependent threshold
		int speedPixels = getSpeedForThreshold();
		int positiveThreshold = Math.min(speedPixels + 4, 14);

		if (distance > positiveThreshold) {
			if (sprite.isStickToConvex()) {
				moveForSensorResult(selectedResult);
				// ROM: mode is determined ONCE at the start of AnglePos, not updated again
				return;
			}
			if (!hasObjectSupport()) {
				// Debug: log airborne due to distance > threshold
				if (loopDebugEnabled && sprite.getGroundMode() != GroundMode.GROUND) {
					System.out.printf("[AIR-DIST] dist=%d > threshold=%d | mode=%s angle=0x%02X pos=(%d,%d) gSpeed=%d xSpeed=%d ySpeed=%d speedForThreshold=%d%n",
							distance, positiveThreshold,
							sprite.getGroundMode(), sprite.getAngle() & 0xFF,
							sprite.getX(), sprite.getY(),
							sprite.getGSpeed(), sprite.getXSpeed(), sprite.getYSpeed(),
							speedPixels);
					// Extra debug: show both sensor results
					System.out.printf("  [SENSORS] L: dist=%d angle=0x%02X | R: dist=%d angle=0x%02X | selected=%s%n",
							leftSensor != null ? leftSensor.distance() : -999,
							leftSensor != null ? leftSensor.angle() & 0xFF : 0,
							rightSensor != null ? rightSensor.distance() : -999,
							rightSensor != null ? rightSensor.angle() & 0xFF : 0,
							selectedResult == leftSensor ? "L" : "R");
					// Show sensor positions (center + rotated offset)
					var sensors = sprite.getGroundSensors();
					for (int i = 0; i < sensors.length; i++) {
						short[] offset = sensors[i].getRotatedOffset();
						System.out.printf("  [SENSOR%d] offset=(%d,%d) -> scanPos=(%d,%d)%n",
								i, offset[0], offset[1],
								sprite.getCentreX() + offset[0], sprite.getCentreY() + offset[1]);
					}
					// Re-run probes with tile debug to see what's at those positions
					System.out.printf("  [LAYER] topSolidBit=%d lrbSolidBit=%d (primary=12/13, secondary=14/15)%n",
							sprite.getTopSolidBit(), sprite.getLrbSolidBit());
					System.out.printf("  [TILE-DEBUG] Sprite center=(%d,%d), re-scanning:%n",
							sprite.getCentreX(), sprite.getCentreY());
					GroundSensor.tileDebugEnabled = true;
					collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground-debug");
					GroundSensor.tileDebugEnabled = false;
					// If on secondary layer, this is critical
					if (sprite.getTopSolidBit() == 14) {
						System.out.println("[CRITICAL] Airborne on secondary layer - terrain may lack secondary collision");
						dumpTerrainAroundSprite();
						dumpPlaneSwitchers();
					}
				}
				sprite.setAir(true);
				sprite.setPushing(false);
			}
			return;
		}

		moveForSensorResult(selectedResult);
		// ROM: mode is determined ONCE at the start of AnglePos, not updated again
	}

	/** Sonic_SlopeRepel: Slip/fall check (s2.asm:37432) */
	private void doSlopeRepel() {
		if (sprite.isStickToConvex()) return;

		int moveLock = sprite.getMoveLockTimer();
		if (moveLock > 0) {
			sprite.setMoveLockTimer(moveLock - 1);
			return;
		}

		if (isOnFlatGround()) return;
		if (Math.abs(sprite.getGSpeed()) >= SLOPE_REPEL_MIN_SPEED) return;

		sprite.setGSpeed((short) 0);
		sprite.setAir(true);
		sprite.setMoveLockTimer(MOVE_LOCK_FRAMES);
	}

	/** Sonic_DoLevelCollision: Full airborne collision (s2.asm:37540) */
	private void doLevelCollision() {
		int quadrant = TrigLookupTable.calcMovementQuadrant(sprite.getXSpeed(), sprite.getYSpeed());
		switch (quadrant) {
			case 0x00 -> doLevelCollisionDown();
			case 0x40 -> doLevelCollisionLeft();
			case 0x80 -> doLevelCollisionUp();
			case 0xC0 -> doLevelCollisionRight();
		}
	}

	private void doLevelCollisionDown() {
		doWallCheckBoth();
		SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
		doTerrainCollisionAir(groundResult);
	}

	private void doLevelCollisionLeft() {
		if (doWallCheck(0)) return;
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
		if (!doCeilingCollisionInternal(ceilingResult)) {
			SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
			doTerrainCollisionAir(groundResult);
		}
	}

	private void doLevelCollisionUp() {
		doWallCheckBoth();
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
		doCeilingCollision(ceilingResult);
	}

	private void doLevelCollisionRight() {
		if (doWallCheck(1)) return;
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
		if (!doCeilingCollisionInternal(ceilingResult)) {
			SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
			doTerrainCollisionAir(groundResult);
		}
	}

	/** Check both walls (quadrants 0x00, 0x80) */
	private void doWallCheckBoth() {
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) return;

		for (int i = 0; i < 2; i++) {
			SensorResult result = pushSensors[i].scan((short) 0, (short) 0);
			if (result != null && result.distance() < 0) {
				moveForSensorResult(result);
				sprite.setXSpeed((short) 0);
			}
		}
	}

	/** Check single wall, returns true if hit (quadrants 0x40, 0xC0) */
	private boolean doWallCheck(int sensorIndex) {
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) return false;

		SensorResult result = pushSensors[sensorIndex].scan((short) 0, (short) 0);
		if (result != null && result.distance() < 0) {
			moveForSensorResult(result);
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed(sprite.getYSpeed());
			return true;
		}
		return false;
	}

	/** Ceiling collision internal - returns whether ceiling was hit */
	private boolean doCeilingCollisionInternal(SensorResult[] results) {
		if (sprite.getYSpeed() >= 0) return false;

		SensorResult lowestResult = findLowestSensorResult(results);
		if (lowestResult == null || lowestResult.distance() >= 0) return false;

		moveForSensorResult(lowestResult);
		sprite.setYSpeed((short) 0);
		return true;
	}

	/** Obj01_CheckWallsOnGround: Ground wall collision (s2.asm:36486) */
	private void doWallCollisionGround() {
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) return;

		int angle = sprite.getAngle() & 0xFF;
		short gSpeed = sprite.getGSpeed();

		if (((angle + ANGLE_WALL_OFFSET) & ANGLE_WALL_MASK) != 0 || gSpeed == 0) {
			return;
		}

		int rotatedAngle = gSpeed >= 0 ? (angle - 0x40) & 0xFF : (angle + 0x40) & 0xFF;
		int quadrant = (rotatedAngle + 0x20) & 0xC0;
		int sensorIndex = (quadrant == 0x40 || quadrant == 0x00) ? 0 : 1;

		// ROM uses integer velocity directly, not including subpixels
		short projectedDx = (short) (sprite.getXSpeed() >> 8);
		short projectedDy = (short) (sprite.getYSpeed() >> 8);

		if ((quadrant == 0x40 || quadrant == 0xC0) && (rotatedAngle & 0x38) == 0 && angle != 0) {
			projectedDy += 8;
		}

		SensorResult result = pushSensors[sensorIndex].scan(projectedDx, projectedDy);
		if (result == null || result.distance() >= 0) return;

		int delta = result.distance() << 8;
		switch (quadrant) {
			case 0x00 -> sprite.setYSpeed((short) (sprite.getYSpeed() + delta));
			case 0x40 -> { sprite.setXSpeed((short) (sprite.getXSpeed() - delta)); sprite.setPushing(true); sprite.setGSpeed((short) 0); }
			case 0x80 -> sprite.setYSpeed((short) (sprite.getYSpeed() - delta));
			default   -> { sprite.setXSpeed((short) (sprite.getXSpeed() + delta)); sprite.setPushing(true); sprite.setGSpeed((short) 0); }
		}
	}

	/** Airborne landing check */
	private void doTerrainCollisionAir(SensorResult[] results) {
		SensorResult lowestResult = findLowestSensorResult(results);
		if (lowestResult == null || lowestResult.distance() >= 0 || sprite.getYSpeed() < 0) {
			return;
		}

		short ySpeedPixels = (short) (sprite.getYSpeed() >> 8);
		short threshold = (short) (-(ySpeedPixels + 8));
		boolean canLand = (results[0] != null && results[0].distance() >= threshold)
		               || (results[1] != null && results[1].distance() >= threshold);

		if (canLand) {
			moveForSensorResult(lowestResult);
			// ROM checks bit 0 (odd = flagged tile) - use floor cardinal (0x00) for flagged tiles
			// See s2.asm:43636 - floor check explicitly uses #0
			if ((lowestResult.angle() & 0x01) != 0) {
				sprite.setAngle((byte) 0x00);
			} else {
				sprite.setAngle(lowestResult.angle());
			}
			calculateLanding(sprite);
			updateGroundMode();
		}
	}

	/** Ceiling collision with potential landing */
	private void doCeilingCollision(SensorResult[] results) {
		if (sprite.getYSpeed() >= 0) return;

		SensorResult lowestResult = findLowestSensorResult(results);
		if (lowestResult == null || lowestResult.distance() >= 0) return;

		moveForSensorResult(lowestResult);

		int ceilingAngle = lowestResult.angle() & 0xFF;
		boolean canLandOnCeiling = ((ceilingAngle + 0x20) & 0x40) != 0;

		if (canLandOnCeiling) {
			// ROM checks bit 0 (odd = flagged tile) - use ceiling cardinal (0x80) for flagged tiles
			// See s2.asm:43928 - ceiling check explicitly uses #$80
			if ((lowestResult.angle() & 0x01) != 0) {
				sprite.setAngle((byte) 0x80);
			} else {
				sprite.setAngle(lowestResult.angle());
			}
			sprite.setAir(false);
			short gSpeed = sprite.getYSpeed();
			if ((ceilingAngle & 0x80) != 0) gSpeed = (short) -gSpeed;
			sprite.setGSpeed(gSpeed);
			updateGroundMode();
		} else {
			sprite.setYSpeed((short) 0);
		}
	}

	// ========================================
	// LANDING
	// ========================================

	/** Sonic_ResetOnFloor: Clear landing-related flags (s2.asm:37744) */
	private void resetOnFloor() {
		if (sprite.getRolling() && !sprite.getPinballMode()) {
			sprite.setRolling(false);
			sprite.setY((short) (sprite.getY() - 5));
		}
		sprite.setPinballMode(false);
		sprite.setAir(false);
		sprite.setPushing(false);
		sprite.setRollingJump(false);
		sprite.setJumping(false);
	}

	/** Landing gSpeed calculation (s2.asm:37584) */
	private void calculateLanding(AbstractPlayableSprite sprite) {
		resetOnFloor();

		short ySpeed = sprite.getYSpeed();
		short xSpeed = sprite.getXSpeed();
		int angle = sprite.getAngle() & 0xFF;

		// ROM processes all landings through angle classification, no early return for ySpeed <= 0
		// Treat negative/zero ySpeed same as positive for angle-based gSpeed conversion
		short absYSpeed = (short) Math.abs(ySpeed);

		boolean isSteep = isSteepAngle(angle);

		if (isSteep) {
			// Steep angles: gSpeed from Y velocity magnitude
			sprite.setXSpeed((short) 0);
			if (absYSpeed > YSPEED_LANDING_CAP) {
				absYSpeed = YSPEED_LANDING_CAP;
			}
			short gSpeed = absYSpeed;
			if ((angle & 0x80) != 0) gSpeed = (short) -gSpeed;
			sprite.setGSpeed(gSpeed);
			sprite.setYSpeed((short) 0);
		} else {
			boolean isFlat = isFlatAngle(angle);
			if (isFlat) {
				// Flat angles: gSpeed from X velocity
				sprite.setGSpeed(xSpeed);
				sprite.setYSpeed((short) 0);
			} else {
				// Moderate angles: gSpeed from Y velocity / 2
				short halfYSpeed = (short) (absYSpeed >> 1);
				short gSpeed = halfYSpeed;
				if ((angle & 0x80) != 0) gSpeed = (short) -gSpeed;
				sprite.setGSpeed(gSpeed);
				sprite.setYSpeed((short) 0);
			}
		}
	}

	// ========================================
	// ANGLE CLASSIFICATION HELPERS
	// ========================================

	/** Steep: angles near walls (0x20-0x5F, 0xA0-0xDF) */
	private boolean isSteepAngle(int angle) {
		return ((angle + ANGLE_STEEP_OFFSET) & ANGLE_STEEP_MASK) != 0;
	}

	/** Flat: angles near horizontal (0x00-0x0F, 0xF0-0xFF) */
	private boolean isFlatAngle(int angle) {
		return ((angle + ANGLE_FLAT_OFFSET) & ANGLE_FLAT_MASK) == 0;
	}

	/** On steep surface: angles near vertical (skip slope physics) */
	private boolean isOnSteepSurface(int angle) {
		return ((angle + 0x60) & 0xFF) >= 0xC0;
	}

	/** On flat ground for crouch/skid checks */
	private boolean isOnFlatGround() {
		int angle = sprite.getAngle() & 0xFF;
		return ((angle + ANGLE_SLOPE_OFFSET) & ANGLE_SLOPE_MASK) == 0;
	}

	// ========================================
	// SENSOR/ANGLE HELPERS
	// ========================================

	/** Sonic_Angle: Select best ground sensor (s2.asm:42649) */
	private SensorResult selectSensorWithAngle(SensorResult rightSensor, SensorResult leftSensor) {
		if (rightSensor == null && leftSensor == null) return null;
		if (rightSensor == null) { applyAngleFromSensor(leftSensor.angle()); return leftSensor; }
		if (leftSensor == null) { applyAngleFromSensor(rightSensor.angle()); return rightSensor; }

		SensorResult selected = leftSensor.distance() <= rightSensor.distance() ? leftSensor : rightSensor;
		applyAngleFromSensor(selected.angle());
		return selected;
	}

	/** Apply angle with ROM's snapping logic (s2.asm:42649-42674) */
	private void applyAngleFromSensor(byte sensorAngle) {
		// Flagged angles (odd) snap to cardinal using current angle
		if ((sensorAngle & 0x01) != 0) {
			sprite.setAngle((byte) ((sprite.getAngle() + 0x20) & 0xC0));
			return;
		}

		int currentAngle = sprite.getAngle() & 0xFF;
		int newAngle = sensorAngle & 0xFF;
		int diff = Math.abs(newAngle - currentAngle);
		if (diff > 0x80) diff = 0x100 - diff;

		if (diff >= 0x20) {
			// ROM uses CURRENT angle to determine cardinal snap direction (s2.asm:42670)
			// This prevents premature ground mode transitions on sharp curves
			sprite.setAngle((byte) ((currentAngle + 0x20) & 0xC0));
		} else {
			sprite.setAngle(sensorAngle);
		}
	}

	/** ROM-accurate ground mode from angle (s2.asm:42551) */
	private void updateGroundMode() {
		int angle = sprite.getAngle() & 0xFF;
		boolean angleIsNegative = angle >= 0x80;
		int sumWith20 = (angle + 0x20) & 0xFF;
		boolean sumIsNegative = sumWith20 >= 0x80;

		int result = (angleIsNegative == sumIsNegative) ? (angle + 0x1F) & 0xFF : sumWith20;
		int modeBits = result & 0xC0;

		GroundMode newMode = switch (modeBits) {
			case 0x00 -> GroundMode.GROUND;
			case 0x40 -> GroundMode.LEFTWALL;
			case 0x80 -> GroundMode.CEILING;
			default   -> GroundMode.RIGHTWALL;
		};

		if (newMode != sprite.getGroundMode()) {
			GroundMode oldMode = sprite.getGroundMode();
			sprite.setGroundMode(newMode);
			// Debug: log ground mode transitions
			if (loopDebugEnabled) {
				System.out.printf("[MODE] %s -> %s | angle=0x%02X pos=(%d,%d) gSpeed=%d xSpeed=%d ySpeed=%d%n",
						oldMode, newMode, angle,
						sprite.getX(), sprite.getY(),
						sprite.getGSpeed(), sprite.getXSpeed(), sprite.getYSpeed());
			}
		}
	}

	private byte getCardinalAngleForGroundMode() {
		return switch (sprite.getGroundMode()) {
			case GROUND -> (byte) 0x00;
			case RIGHTWALL -> (byte) 0xC0;
			case CEILING -> (byte) 0x80;
			case LEFTWALL -> (byte) 0x40;
		};
	}

	private SensorResult findLowestSensorResult(SensorResult[] results) {
		SensorResult lowest = null;
		for (SensorResult result : results) {
			if (result != null && (lowest == null || result.distance() < lowest.distance())) {
				lowest = result;
			}
		}
		return lowest;
	}

	private void moveForSensorResult(SensorResult result) {
		byte distance = result.distance();
		switch (result.direction()) {
			case UP -> sprite.setY((short) (sprite.getY() - distance));
			case DOWN -> sprite.setY((short) (sprite.getY() + distance));
			case LEFT -> sprite.setX((short) (sprite.getX() - distance));
			case RIGHT -> sprite.setX((short) (sprite.getX() + distance));
		}
	}

	// ========================================
	// UTILITY HELPERS
	// ========================================

	private void storeInputState(boolean up, boolean down, boolean left, boolean right, boolean jump) {
		inputUp = up;
		inputDown = down;
		inputLeft = left;
		inputRight = right;
		inputJump = sprite.isHurt() ? false : jump;
		inputJumpPress = jump && !jumpPrevious;
		jumpPrevious = jump;
	}

	private void handleDebugMovement(boolean up, boolean down, boolean left, boolean right) {
		if (left) sprite.setX((short) (sprite.getX() - DEBUG_MOVE_SPEED));
		if (right) sprite.setX((short) (sprite.getX() + DEBUG_MOVE_SPEED));
		if (up) sprite.setY((short) (sprite.getY() - DEBUG_MOVE_SPEED));
		if (down) sprite.setY((short) (sprite.getY() + DEBUG_MOVE_SPEED));
	}

	private void handleTestKey(boolean testKey) {
		if (testKey && !testKeyPressed) {
			testKeyPressed = true;
			sprite.setGroundMode(switch (sprite.getGroundMode()) {
				case GROUND -> GroundMode.RIGHTWALL;
				case RIGHTWALL -> GroundMode.CEILING;
				case CEILING -> GroundMode.LEFTWALL;
				case LEFTWALL -> GroundMode.GROUND;
			});
		}
		if (!testKey) testKeyPressed = false;
	}

	private void updatePushingOnDirectionChange(boolean left, boolean right) {
		if (left && !right && sprite.getDirection() == Direction.RIGHT) {
			sprite.setPushing(false);
		} else if (right && !left && sprite.getDirection() == Direction.LEFT) {
			sprite.setPushing(false);
		}
	}

	private void handleSkid() {
		if (!sprite.getSkidding()) sprite.setSkidding(true);
		audioManager.playSfx(GameSound.SKID);

		int dustTimer = sprite.getSkidDustTimer() - 1;
		if (dustTimer < 0) {
			dustTimer = 3;
			SkidDustObjectInstance.spawn(sprite);
		}
		sprite.setSkidDustTimer(dustTimer);
	}

	private void updateFacingDirection() {
		if (inputLeft && !inputRight) {
			sprite.setDirection(!sprite.getAir() && sprite.getGSpeed() > 0 ? Direction.RIGHT : Direction.LEFT);
		} else if (inputRight && !inputLeft) {
			sprite.setDirection(!sprite.getAir() && sprite.getGSpeed() < 0 ? Direction.LEFT : Direction.RIGHT);
		}
	}

	private void checkPitDeath() {
		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getY() + camera.getHeight()) {
			sprite.applyPitDeath();
		}
	}

	private void updateCrouchState() {
		boolean crouching = inputDown && !inputLeft && !inputRight
				&& !sprite.getAir() && !sprite.getRolling() && !sprite.getSpindash()
				&& sprite.getGSpeed() == 0 && isOnFlatGround();
		sprite.setCrouching(crouching);

		if (wasCrouching && (inputLeft || inputRight)) downLocked = true;
		if (!inputDown) downLocked = false;
	}

	private void applyUpwardVelocityCap() {
		if (sprite.getAir() && !jumpPressed && !sprite.getPinballMode()) {
			if (sprite.getYSpeed() < UPWARD_VELOCITY_CAP) {
				sprite.setYSpeed((short) UPWARD_VELOCITY_CAP);
			}
		}
	}

	private void applyDeathMovement() {
		sprite.setYSpeed((short) (sprite.getYSpeed() + sprite.getGravity()));
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);

		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getY() + camera.getHeight() + 256) {
			sprite.startDeathCountdown();
		}

		if (sprite.tickDeathCountdown()) {
			GameServices.gameState().loseLife();
			uk.co.jamesj999.sonic.level.LevelManager.getInstance().requestRespawn();
		}
	}

	private short applyFriction(short speed, short friction) {
		if (speed > 0) {
			speed -= friction;
			if (speed < 0) speed = 0;
		} else if (speed < 0) {
			speed += friction;
			if (speed > 0) speed = 0;
		}
		return speed;
	}

	private void calculateXYFromGSpeed() {
		short gSpeed = sprite.getGSpeed();
		int hexAngle = sprite.getAngle() & 0xFF;
		sprite.setXSpeed((short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8));
		sprite.setYSpeed((short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8));
	}

	/**
	 * ROM-accurate speed threshold for ground attachment (s2.asm:42619, 42794, 42861).
	 * Uses X velocity for GROUND/CEILING modes, Y velocity for wall modes.
	 * SPG: "minimum(absolute(X Speed) + 4, 14)" for floor/ceiling,
	 *      "Y Speed instead" for walls.
	 *
	 * IMPORTANT: Only fall back to gSpeed when the ROM-style velocity is zero but
	 * gSpeed is non-zero. This handles loop edge cases where velocity decomposition
	 * creates zeros at certain angles. Using Math.max() unconditionally breaks slopes.
	 */
	private int getSpeedForThreshold() {
		GroundMode groundMode = sprite.getGroundMode();
		// ROM uses x_vel for GROUND/CEILING, y_vel for walls (mvabs.b instruction)
		int speedPixels = (groundMode == GroundMode.LEFTWALL || groundMode == GroundMode.RIGHTWALL)
				? Math.abs(sprite.getYSpeed() >> 8)
				: Math.abs(sprite.getXSpeed() >> 8);

		// Only fall back to gSpeed when decomposed velocity is exactly zero
		// This preserves ROM behavior on slopes while handling loop edge cases
		if (speedPixels == 0) {
			int gSpeedPixels = Math.abs(sprite.getGSpeed() >> 8);
			if (gSpeedPixels > 0) {
				return gSpeedPixels;
			}
		}
		return speedPixels;
	}

	private boolean hasEnoughHeadroom(int hexAngle) {
		int terrainDistance = getTerrainHeadroomDistance(hexAngle);
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		int objectDistance = (objectManager != null) ? objectManager.getHeadroomDistance(sprite, hexAngle) : Integer.MAX_VALUE;
		return Math.min(terrainDistance, objectDistance) >= 6;
	}

	private int getTerrainHeadroomDistance(int hexAngle) {
		int overheadAngle = (hexAngle + 0x80) & 0xFF;
		int quadrant = (overheadAngle + 0x20) & 0xC0;

		Sensor[] sensors = switch (quadrant) {
			case 0x00, 0x80 -> sprite.getCeilingSensors();
			case 0x40, 0xC0 -> sprite.getGroundSensors();
			default -> null;
		};

		if (sensors == null) return Integer.MAX_VALUE;

		int minDistance = Integer.MAX_VALUE;
		for (Sensor sensor : sensors) {
			boolean wasActive = sensor.isActive();
			sensor.setActive(true);
			SensorResult result = sensor.scan();
			sensor.setActive(wasActive);
			if (result != null) {
				int clearance = Math.max(result.distance(), 0);
				minDistance = Math.min(minDistance, clearance);
			}
		}
		return minDistance;
	}

	private boolean hasObjectSupport() {
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		return objectManager != null && (objectManager.isRidingObject() || objectManager.hasStandingContact(sprite));
	}

	private void clearRidingObject() {
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		if (objectManager != null) objectManager.clearRidingObject();
	}

	private int getDuckAnimId() {
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			return velocityProfile.getDuckAnimId();
		}
		return -1;
	}

	private void setSpindashAnimation() {
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			sprite.setAnimationId(velocityProfile.getSpindashAnimId());
			sprite.setAnimationFrameIndex(0);
			sprite.setAnimationTick(0);
		}
	}

	private void setRollAnimation() {
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			sprite.setAnimationId(velocityProfile.getRollAnimId());
			sprite.setAnimationFrameIndex(0);
			sprite.setAnimationTick(0);
		}
	}

	/** Common pattern: boundary check + sensor update + angle positioning */
	private void doLevelBoundaryAndAnglePos() {
		doLevelBoundary();
		short originalX = sprite.getX();
		short originalY = sprite.getY();
		sprite.updateSensors(originalX, originalY);
		doAnglePos();
	}

	/** Sensor update + angle positioning */
	private void doAnglePosWithSensorUpdate(short originalX, short originalY) {
		sprite.updateSensors(originalX, originalY);
		doAnglePos();
	}

	/** Debug: dump terrain collision bits around sprite position */
	private void dumpTerrainAroundSprite() {
		var levelManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance();
		int cx = sprite.getCentreX();
		int cy = sprite.getCentreY();
		System.out.printf("  [TERRAIN DUMP] around (%d,%d), topSolidBit=%d:%n", cx, cy, sprite.getTopSolidBit());
		// Check 3x3 grid of chunks around sprite
		for (int dy = -16; dy <= 16; dy += 16) {
			StringBuilder row = new StringBuilder("    ");
			for (int dx = -16; dx <= 16; dx += 16) {
				var desc = levelManager.getChunkDescAt((byte) 0, cx + dx, cy + dy);
				if (desc == null) {
					row.append("[null] ");
				} else {
					int rawVal = desc.get();
					boolean primary = (rawVal & 0x3000) != 0;
					boolean secondary = (rawVal & 0xC000) != 0;
					row.append(String.format("[%04X %s%s] ", rawVal, primary ? "P" : "-", secondary ? "S" : "-"));
				}
			}
			System.out.println(row);
		}
	}

	/** Debug: dump all active plane switchers near sprite */
	private void dumpPlaneSwitchers() {
		var levelManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance();
		var objectManager = levelManager.getObjectManager();
		if (objectManager == null) return;

		int cx = sprite.getCentreX();
		int cy = sprite.getCentreY();
		System.out.printf("  [PLANE SWITCHERS] near (%d,%d):%n", cx, cy);

		var gameModule = levelManager.getGameModule();
		if (gameModule == null) return;
		int planeSwitcherId = gameModule.getPlaneSwitcherObjectId();

		// Get all spawns from placement manager
		var spawns = objectManager.getActiveSpawns();
		if (spawns == null || spawns.isEmpty()) {
			System.out.println("    (no active spawns)");
			return;
		}

		int count = 0;
		for (var spawn : spawns) {
			if (spawn.objectId() != planeSwitcherId) continue;
			int dx = spawn.x() - cx;
			int dy = spawn.y() - cy;
			int dist = (int) Math.sqrt(dx * dx + dy * dy);
			if (dist < 300) { // Only show nearby switchers
				int subtype = spawn.subtype();
				boolean horizontal = (subtype & 0x04) != 0;
				boolean groundedOnly = (subtype & 0x80) != 0;
				int halfSpan = new int[]{0x20, 0x40, 0x80, 0x100}[subtype & 0x03];
				System.out.printf("    spawn=(%d,%d) subtype=0x%02X %s groundedOnly=%s halfSpan=%d dist=%d%n",
						spawn.x(), spawn.y(), subtype,
						horizontal ? "HORIZ" : "VERT",
						groundedOnly, halfSpan, dist);
				count++;
			}
		}
		if (count == 0) {
			System.out.println("    (no plane switchers within 300px)");
		}
	}
}
