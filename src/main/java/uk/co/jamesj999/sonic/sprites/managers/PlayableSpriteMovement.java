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
import uk.co.jamesj999.sonic.game.sonic2.objects.SkidDustObjectInstance;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.playable.GroundMode;

import java.util.logging.Logger;

/**
 * PlayableSpriteMovement - ROM-accurate movement handler for playable sprites.
 *
 * This class implements the exact order of operations from the Sonic 2 ROM disassembly.
 * Movement is handled through 4 distinct modes based on the air and rolling status flags:
 *
 * | in_air | rolling | Mode |
 * |--------|---------|------|
 * | false  | false   | Obj01_MdNormal (ground walking) |
 * | false  | true    | Obj01_MdRoll (ground rolling) |
 * | true   | false   | Obj01_MdAir (airborne, not rolling) |
 * | true   | true    | Obj01_MdJump (airborne, rolling/jumping) |
 *
 * Each mode calls subroutines in the exact order specified by the ROM.
 *
 * @see docs/s2disasm/s2.asm lines 36145-37700
 */
public class PlayableSpriteMovement extends
		AbstractSpriteMovementManager<AbstractPlayableSprite> {
	private static final Logger LOGGER = Logger.getLogger(PlayableSpriteMovement.class.getName());

	// ROM-accurate spindash speed table (s2.asm:37294 SpindashSpeeds)
	// Index corresponds to high byte of spindash_counter (0-8)
	private static final short[] SPINDASH_SPEEDS = {
		0x0800, // 0 - minimum (immediate release)
		0x0880, // 1
		0x0900, // 2 - after first rev
		0x0980, // 3
		0x0A00, // 4 - after second rev
		0x0A80, // 5
		0x0B00, // 6 - after third rev
		0x0B80, // 7
		0x0C00  // 8 - maximum (fully charged)
	};

	private final CollisionSystem collisionSystem = CollisionSystem.getInstance();
	private final AudioManager audioManager = AudioManager.getInstance();

	// These values don't change with speed shoes, so we cache them
	private final short slopeRunning;
	private final short minStartRollSpeed;
	private final short maxRoll;
	private final short slopeRollingUp;
	private final short slopeRollingDown;
	private final short rollDecel;

	private boolean jumpPressed;
	// ROM: Tracks the previous frame's jump button state to detect edge-press
	// (Ctrl_1_Press_Logical vs Ctrl_1_Held_Logical)
	private boolean jumpPrevious;

	// Tracks when the down button was held while crouching.
	// When the player crouches (standing still with down held) and then presses
	// left/right, the down button should be "locked" and not trigger rolling.
	// The down key must be released and pressed again to trigger a roll.
	private boolean downLocked;

	private boolean testKeyPressed;

	// Input state stored for mode methods
	private boolean inputUp;
	private boolean inputDown;
	private boolean inputLeft;
	private boolean inputRight;
	private boolean inputJump;
	// ROM: Jump edge-press (Ctrl_1_Press_Logical) - true only on the frame jump is first pressed
	private boolean inputJumpPress;
	private boolean inputRawLeft;
	private boolean inputRawRight;
	private boolean wasCrouching;

	public PlayableSpriteMovement(AbstractPlayableSprite sprite) {
		super(sprite);
		// Note: max, runAccel, runDecel, and friction are read dynamically from sprite
		// to support speed shoes power-up which modifies these values at runtime
		slopeRunning = sprite.getSlopeRunning();
		minStartRollSpeed = sprite.getMinStartRollSpeed();
		maxRoll = sprite.getMaxRoll();
		slopeRollingUp = sprite.getSlopeRollingUp();
		slopeRollingDown = sprite.getSlopeRollingDown();
		rollDecel = sprite.getRollDecel();
	}

	/**
	 * Main movement handler - dispatches to appropriate mode method based on air/rolling state.
	 *
	 * ROM: Mode selection happens at Obj01_MdNormal_Checks (s2.asm:36125)
	 * The ROM uses two status bits (in_air and rolling) to select from 4 modes.
	 */
	@Override
	public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean jump, boolean testKey) {
		// Store input state for objects (like flippers, Grabber) to query
		sprite.setJumpInputPressed(jump);
		sprite.setDirectionalInputPressed(left, right);

		// DEBUG MODE: When debug mode is active, use simple directional movement
		// with no collision, physics, or damage.
		if (sprite.isDebugMode()) {
			handleDebugMovement(up, down, left, right);
			return;
		}

		// OBJECT CONTROLLED MODE: When an object has full control (like spin tubes,
		// corkscrews), skip all physics processing. The controlling object handles
		// position updates directly. This matches ROM's obj_control = $81 behavior.
		if (sprite.isObjectControlled()) {
			return;
		}

		// Test key for cycling ground modes
		handleTestKey(testKey);

		// NOTE: move_lock is NOT decremented here. In the ROM, move_lock is only
		// decremented inside Sonic_SlopeRepel (s2.asm:37454). See doSlopeRepel().

		// Determine if control is locked
		boolean controlLocked = isControlLocked();

		// Store raw button state before control lock modifies it
		inputRawLeft = left;
		inputRawRight = right;

		// Lock directional input when control is locked, springing, or hurt
		// ROM: Obj01_Hurt (routine=4) skips all input handling - only applies ObjectMove
		// See s2.asm:37785-37813 - hurt state processes velocity/gravity but no control
		if (controlLocked || sprite.getSpringing() || sprite.isHurt()) {
			left = false;
			right = false;
			up = false;
			down = false;
		}

		// ROM: Clear pushing when direction flips
		updatePushingOnDirectionChange(left, right);

		// Store current position for sensor updates
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		// Handle death state separately
		if (sprite.getDead()) {
			applyDeathMovement(sprite);
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			sprite.updateSensors(originalX, originalY);
			return;
		}

		// Save crouching state before clearing it
		wasCrouching = sprite.getCrouching();
		sprite.setCrouching(false);

		// Store input for mode methods
		inputUp = up;
		inputDown = down;
		inputLeft = left;
		inputRight = right;
		// ROM: Obj01_Hurt skips Sonic_Jump entirely, so block jump input when hurt
		inputJump = sprite.isHurt() ? false : jump;
		// ROM: Ctrl_1_Press_Logical - true only on the frame jump is first pressed
		inputJumpPress = jump && !jumpPrevious;
		jumpPrevious = jump;

		// NOTE: Spindash is handled in modeNormal() via doCheckSpindash(), matching ROM's
		// Obj01_MdNormal which calls Sonic_CheckSpindash first.

		// SPG: When a spring launches the player, reset jumpPressed to prevent
		// the jump velocity cap from applying to the spring launch.
		if (sprite.getSpringing()) {
			jumpPressed = false;
		}

		// Reset jumpPressed when on ground and jump button is released.
		// This allows the player to jump again after landing.
		// ROM: Uses Ctrl_1_Press_Logical which is computed fresh each frame.
		if (!sprite.getAir() && !inputJump) {
			jumpPressed = false;
		}

		// ========================================
		// MODE DISPATCH - ROM: Obj01_MdNormal_Checks (s2.asm:36125-36140)
		// ========================================
		if (sprite.getAir()) {
			// Airborne modes (MdAir or MdJump - both use same logic)
			modeAirborne();
		} else if (sprite.getRolling()) {
			// Ground rolling mode (MdRoll)
			modeRoll();
		} else {
			// Ground walking mode (MdNormal)
			modeNormal();
		}

		// ========================================
		// POST-MODE PROCESSING
		// ========================================

		// Update facing direction based on input and gSpeed
		updateFacingDirection();

		// Pit death detection
		checkPitDeath();

		// Update sensors for next tick
		sprite.updateSensors(originalX, originalY);
	}

	// ========================================
	// MODE METHODS - ROM-accurate execution order
	// ========================================

	/**
	 * Mode: Obj01_MdNormal (s2.asm:36145-36154)
	 * Ground walking state - not rolling, not in air.
	 *
	 * ROM Order:
	 * 1. Sonic_CheckSpindash
	 * 2. Sonic_Jump
	 * 3. Sonic_SlopeResist
	 * 4. Sonic_Move (includes velocity conversion and wall check)
	 * 5. Sonic_Roll
	 * 6. Sonic_LevelBound
	 * 7. ObjectMove
	 * 8. AnglePos
	 * 9. Sonic_SlopeRepel
	 */
	private void modeNormal() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		// 1. Sonic_CheckSpindash
		if (doCheckSpindash()) {
			return;
		}

		// 2. Sonic_Jump - check/handle jump input
		if (inputJump && !jumpPressed && doJump()) {
			// Jump was initiated, switch to air mode for rest of frame
			modeAirborne();
			return;
		}

		// 3. Sonic_SlopeResist - apply slope factor to inertia
		doSlopeResist();

		// 4. Sonic_Move - input handling, accel/decel, velocity conversion, wall check
		doGroundMove();

		// 5. Sonic_Roll - check if should start rolling
		doCheckStartRoll();

		// 6. Sonic_LevelBound - check level boundaries
		doLevelBoundary();

		// 7. ObjectMove - apply velocity to position (no gravity)
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());

		// 8. AnglePos - ground terrain collision
		sprite.updateSensors(originalX, originalY);
		doAnglePos();

		// 9. Sonic_SlopeRepel - slip/fall check (uses NEW angle from step 8)
		doSlopeRepel();

		// Update crouch state (ROM does this in Sonic_Move via animation selection)
		updateCrouchState();
	}

	/**
	 * Mode: Obj01_MdRoll (s2.asm:36180-36191)
	 * Rolling on ground state - rolling but not in air.
	 *
	 * ROM Order:
	 * 1. [pinball check] Sonic_Jump
	 * 2. Sonic_RollRepel
	 * 3. Sonic_RollSpeed (includes velocity conversion and wall check)
	 * 4. Sonic_LevelBound
	 * 5. ObjectMove
	 * 6. AnglePos
	 * 7. Sonic_SlopeRepel
	 */
	private void modeRoll() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		// 1. Sonic_Jump - only if not in pinball mode
		if (!sprite.getPinballMode() && inputJump && !jumpPressed && doJump()) {
			// Jump was initiated, switch to air mode for rest of frame
			modeAirborne();
			return;
		}

		// 2. Sonic_RollRepel - apply rolling slope factor (80/20, not 32)
		doRollRepel();

		// 3. Sonic_RollSpeed - roll deceleration, velocity conversion, wall check
		doRollSpeed();

		// 4. Sonic_LevelBound - check level boundaries
		doLevelBoundary();

		// 5. ObjectMove - apply velocity to position (no gravity)
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());

		// 6. AnglePos - ground terrain collision
		sprite.updateSensors(originalX, originalY);
		doAnglePos();

		// 7. Sonic_SlopeRepel - slip/fall check
		doSlopeRepel();
	}

	/**
	 * Mode: Obj01_MdAir / Obj01_MdJump (s2.asm:36163-36174, 36199-36210)
	 * Airborne state - both MdAir and MdJump use identical logic.
	 *
	 * ROM Order:
	 * 1. Sonic_JumpHeight
	 * 2. Sonic_ChgJumpDir
	 * 3. Sonic_LevelBound
	 * 4. ObjectMoveAndFall
	 * 5. [underwater check]
	 * 6. Sonic_JumpAngle
	 * 7. Sonic_DoLevelCollision
	 */
	private void modeAirborne() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		// 1. Sonic_JumpHeight - jump button release velocity cap
		doJumpHeight();

		// 2. Sonic_ChgJumpDir - air control (left/right) + air drag
		doChgJumpDir();

		// 3. Sonic_LevelBound - check level boundaries
		doLevelBoundary();

		// 4. ObjectMoveAndFall - apply velocity AND add gravity
		doObjectMoveAndFall();

		// 5. Underwater gravity adjustment (ROM: s2.asm line 36170)
		// ROM applies FULL gravity (0x38) in ObjectMoveAndFall, then subtracts 0x28 here.
		// Net underwater gravity = 0x38 - 0x28 = 0x10
		// IMPORTANT: sprite.getGravity() returns 0x38 (not 0x10) - the reduction happens HERE.
		if (sprite.isInWater()) {
			sprite.setYSpeed((short) (sprite.getYSpeed() - 0x28));
		}

		// 6. Sonic_JumpAngle - return angle toward 0
		sprite.returnAngleToZero();

		// 7. Sonic_DoLevelCollision - full collision (walls, floor, ceiling)
		sprite.updateSensors(originalX, originalY);
		doLevelCollision();
	}

	// ========================================
	// ROM SUBROUTINE IMPLEMENTATIONS
	// ========================================

	/**
	 * ROM: Sonic_CheckSpindash (s2.asm:37206-37229)
	 * Check for spindash initiation.
	 *
	 * ROM Logic:
	 * 1. If spindash_flag is set, branch to Sonic_UpdateSpindash
	 * 2. Check if animation is AniIDSonAni_Duck (must be ducking)
	 * 3. Check for jump button edge-press (Ctrl_1_Press_Logical)
	 * 4. On initiation: set spindash anim, play sound, set flag, counter=0
	 * 5. Call Sonic_LevelBound and AnglePos
	 *
	 * @return true if spindash is active (caller should return from modeNormal)
	 */
	private boolean doCheckSpindash() {
		// If already in spindash, handle charging/release
		if (sprite.getSpindash()) {
			return doUpdateSpindash();
		}

		// ROM: cmpi.b #AniIDSonAni_Duck,anim(a0)
		// Must be in duck animation to initiate spindash
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		int duckAnimId = -1;
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			duckAnimId = velocityProfile.getDuckAnimId();
		}
		if (sprite.getAnimationId() != duckAnimId) {
			return false;
		}

		// ROM: Uses Ctrl_1_Press_Logical - edge-press only
		if (!inputJumpPress) {
			return false;
		}

		// Initiate spindash
		// ROM: move.b #AniIDSonAni_Spindash,anim(a0)
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			sprite.setAnimationId(velocityProfile.getSpindashAnimId());
			sprite.setAnimationFrameIndex(0);
			sprite.setAnimationTick(0);
		}

		// ROM: jsr (PlaySound).l with SndID_SpindashRev
		audioManager.playSfx(GameSound.SPINDASH_CHARGE);

		// ROM: move.b #1,spindash_flag(a0)
		sprite.setSpindash(true);

		// ROM: move.w #0,spindash_counter(a0)
		sprite.setSpindashCounter((short) 0);

		// ROM: Spawn dust if not drowning
		// TODO: Implement spindash dust spawning

		// ROM: bsr.w Sonic_LevelBound
		doLevelBoundary();

		// ROM: bsr.w AnglePos
		short originalX = sprite.getX();
		short originalY = sprite.getY();
		sprite.updateSensors(originalX, originalY);
		doAnglePos();

		return true;
	}

	/**
	 * ROM: Sonic_UpdateSpindash (s2.asm:37239-37350)
	 * Handle spindash charging, rev, and release.
	 *
	 * ROM Logic:
	 * 1. Check if down is still held - if not, release spindash
	 * 2. If down held, go to Sonic_ChargingSpindash:
	 *    a. Decay counter: counter -= counter >> 5 (every frame)
	 *    b. Check for jump edge-press to add charge (+0x200, cap 0x800)
	 * 3. Call Obj01_Spindash_ResetScr (camera Y bias adjustment)
	 * 4. Call Sonic_LevelBound and AnglePos
	 *
	 * @return true if actively charging (caller should return), false if released (caller continues)
	 */
	private boolean doUpdateSpindash() {
		// ROM: move.b (Ctrl_1_Held_Logical).w,d0 / btst #button_down,d0
		if (!inputDown) {
			// Release spindash
			doReleaseSpindash();
			// ROM: After release, Obj01_Spindash_ResetScr pops ONE return address (addq.l #4,sp)
			// and returns to Obj01_MdNormal, which CONTINUES with Sonic_Jump, Sonic_SlopeResist,
			// Sonic_Move (converts gSpeed!), ObjectMove (moves sprite!), AnglePos, etc.
			// Return false so modeNormal() continues processing.
			return false;
		}

		// ROM: Sonic_ChargingSpindash (s2.asm:37317-37336)
		// Decay counter every frame while charging
		short counter = sprite.getSpindashCounter();
		if (counter != 0) {
			// ROM: move.w spindash_counter(a0),d0 / lsr.w #5,d0 / sub.w d0,spindash_counter(a0)
			int decay = counter >> 5;
			counter = (short) (counter - decay);
			if (counter < 0) {
				counter = 0;
			}
			sprite.setSpindashCounter(counter);
		}

		// ROM: Check for jump button edge-press to add charge
		// move.b (Ctrl_1_Press_Logical).w,d0 / andi.b #button_B_mask|button_C_mask|button_A_mask,d0
		if (inputJumpPress) {
			// ROM: Reset animation to spindash (restarts the charging anim)
			// move.w #(AniIDSonAni_Spindash<<8)|(AniIDSonAni_Walk<<0),anim(a0)
			SpriteAnimationProfile profile = sprite.getAnimationProfile();
			if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
				sprite.setAnimationId(velocityProfile.getSpindashAnimId());
				sprite.setAnimationFrameIndex(0);
				sprite.setAnimationTick(0);
			}

			// ROM: Play rev sound
			// Pitch increases based on counter level
			float pitch = 1.0f + (sprite.getSpindashCounter() / 2048.0f) / 3.0f;
			audioManager.playSfx(GameSound.SPINDASH_CHARGE, pitch);

			// ROM: addi.w #$200,spindash_counter(a0)
			counter = sprite.getSpindashCounter();
			counter = (short) (counter + 0x200);

			// ROM: cmpi.w #$800,spindash_counter(a0) / blo.s + / move.w #$800,spindash_counter(a0)
			if (counter > 0x800) {
				counter = 0x800;
			}
			sprite.setSpindashCounter(counter);
		}

		// ROM: Obj01_Spindash_ResetScr - camera Y bias adjustment
		// This gradually returns camera bias toward center while charging
		// TODO: Implement camera Y bias adjustment

		// ROM: bsr.w Sonic_LevelBound
		doLevelBoundary();

		// ROM: bsr.w AnglePos
		short originalX = sprite.getX();
		short originalY = sprite.getY();
		sprite.updateSensors(originalX, originalY);
		doAnglePos();

		return true;
	}

	/**
	 * ROM: Spindash release logic (s2.asm:37244-37291)
	 * Unleash the charged spindash and start rolling.
	 */
	private void doReleaseSpindash() {
		// ROM order (s2.asm:37245-37291):
		// 1. y_radius = 0xE, x_radius = 7
		// 2. anim = Roll
		// 3. y_pos += 5 (BEFORE clearing spindash flag!)
		// 4. spindash_flag = 0
		// 5. Speed from lookup table
		// 6. Apply direction to speed
		// 7. Set rolling flag
		// 8. Clear dust, play sound

		// ROM: move.b #$E,y_radius(a0) / move.b #7,x_radius(a0)
		// Change to rolling collision radii (but don't set rolling flag yet)
		sprite.applyRollingRadii(false);

		// ROM: move.b #AniIDSonAni_Roll,anim(a0)
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			sprite.setAnimationId(velocityProfile.getRollAnimId());
			sprite.setAnimationFrameIndex(0);
			sprite.setAnimationTick(0);
		}

		// ROM: addq.w #5,y_pos(a0) - adjust for hitbox height change (19->14 = 5 pixels)
		// This happens EARLY in ROM - before clearing spindash flag!
		sprite.setY((short) (sprite.getY() + 5));

		// ROM: move.b #0,spindash_flag(a0)
		sprite.setSpindash(false);

		// ROM: Get speed from lookup table indexed by counter >> 8
		// moveq #0,d0 / move.b spindash_counter(a0),d0 / add.w d0,d0
		// move.w SpindashSpeeds(pc,d0.w),inertia(a0)
		short counter = sprite.getSpindashCounter();
		int speedIndex = (counter >> 8) & 0xFF;
		if (speedIndex > 8) {
			speedIndex = 8;
		}
		short spindashGSpeed = SPINDASH_SPEEDS[speedIndex];

		// ROM: Calculate horizontal scroll delay
		// The faster Sonic goes, the less the camera lags
		int scrollDelay = 32 - ((spindashGSpeed - 0x800) >> 7);
		Camera.getInstance().setHorizScrollDelay(scrollDelay);

		// ROM: btst #status.player.x_flip,status(a0) / beq.s + / neg.w inertia(a0)
		if (Direction.LEFT.equals(sprite.getDirection())) {
			spindashGSpeed = (short) -spindashGSpeed;
		}
		sprite.setGSpeed(spindashGSpeed);

		// ROM: bset #status.player.rolling,status(a0)
		// Note: setRolling() also applies rolling radii internally, but we already did that
		// The radii are the same, so this is harmless
		sprite.setRolling(true);

		// ROM ACCURACY: Do NOT call calculateXYFromGSpeed here!
		// The ROM does not convert gSpeed to xSpeed/ySpeed during spindash release.
		// The conversion happens on the NEXT frame when modeRoll() -> doRollSpeed() runs.
		// During release, xSpeed/ySpeed remain at their pre-spindash values (typically 0).
		// This affects the doAnglePos() threshold calculation: threshold = min(|xSpeed|/256 + 4, 14)
		// With xSpeed=0, threshold=4, which is correct ROM behavior.

		// ROM: move.b #0,(Sonic_Dust+anim).w - clear dust animation
		// TODO: Clear spindash dust

		// ROM: Play release sound
		audioManager.playSfx(GameSound.SPINDASH_RELEASE);

		// ROM ACCURACY: Do NOT reset counter here!
		// The ROM only resets the counter when initiating a NEW spindash (s2.asm:37219),
		// not during release. The counter value persists after release.

		// ROM: Then falls through to Obj01_Spindash_ResetScr which calls
		// Sonic_LevelBound and AnglePos
		doLevelBoundary();

		short originalX = sprite.getX();
		short originalY = sprite.getY();
		sprite.updateSensors(originalX, originalY);
		doAnglePos();
	}

	/**
	 * ROM: Sonic_Jump (s2.asm:36996-37054)
	 * Handle jump initiation.
	 *
	 * ROM order of operations:
	 * 1. Check jump button press (edge-press only)
	 * 2. CalcRoomOverHead - check for ceiling
	 * 3. Calculate jump velocity using CalcSine
	 * 4. Add jump velocity to x_vel and y_vel
	 * 5. Set in_air status flag
	 * 6. Clear pushing status flag
	 * 7. Set jumping flag
	 * 8. Clear stick_to_convex
	 * 9. Play jump sound
	 * 10. If NOT already rolling: apply roll hitbox, set roll anim, set rolling flag, add 5 to y_pos
	 * 11. If WAS rolling: set rolljumping flag
	 *
	 * @return true if jump was initiated
	 */
	private boolean doJump() {
		// CRITICAL: Calculate angle BEFORE setAir(true), because setAir(true) resets
		// the angle to 0. We need the terrain angle from the current ground position.
		int hexAngle = getHexAngle(sprite);

		// ROM: CalcRoomOverHead - check headroom before jumping (s2.asm:37009-37011)
		if (!hasEnoughHeadroom(sprite, hexAngle)) {
			return false;
		}

		// Clear solid object riding state
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		if (objectManager != null) {
			objectManager.clearRidingObject();
		}

		// Capture rolling state BEFORE modifying any flags
		// ROM: btst #status.player.rolling,status(a0) at line 37040
		boolean wasRolling = sprite.getRolling();

		// ROM: CalcSine + velocity modification (s2.asm:37021-37030)
		// ROM rotates angle by -0x40 before CalcSine, then uses cos for X, sin for Y
		// Due to trig identities: cos(a-90°) = sin(a), sin(a-90°) = -cos(a)
		// So ROM's approach is equivalent to: xSpeed += sin(a), ySpeed -= cos(a)
		int xJumpChange = (TrigLookupTable.sinHex(hexAngle) * sprite.getJump()) >> 8;
		int yJumpChange = (TrigLookupTable.cosHex(hexAngle) * sprite.getJump()) >> 8;
		sprite.setXSpeed((short) (sprite.getXSpeed() + xJumpChange));
		sprite.setYSpeed((short) (sprite.getYSpeed() - yJumpChange));

		// ROM: bset #status.player.in_air,status(a0) (s2.asm:37031)
		sprite.setAir(true);

		// ROM: bclr #status.player.pushing,status(a0) (s2.asm:37032)
		sprite.setPushing(false);

		// ROM: move.b #1,jumping(a0) (s2.asm:37034)
		sprite.setJumping(true);
		jumpPressed = true;

		// ROM: clr.b stick_to_convex(a0) (s2.asm:37035)
		sprite.setStickToConvex(false);

		// ROM: Play jumping sound (s2.asm:37036-37037)
		audioManager.playSfx(GameSound.JUMP);

		// ROM: Hitbox change logic (s2.asm:37038-37054)
		// First set standing radii (y_radius=0x13, x_radius=9), then...
		// If NOT already rolling: change to roll hitbox and adjust y_pos
		// If WAS rolling: just set rolljumping flag
		if (!wasRolling) {
			// ROM: move.b #$E,y_radius(a0) (s2.asm:37042)
			// ROM: move.b #7,x_radius(a0) (s2.asm:37043)
			// ROM: move.b #AniIDSonAni_Roll,anim(a0) (s2.asm:37044)
			// ROM: bset #status.player.rolling,status(a0) (s2.asm:37045)
			// ROM: addq.w #5,y_pos(a0) (s2.asm:37046)
			// Apply rolling hitbox - this changes y_radius from 19 to 14, x_radius from 9 to 7
			sprite.applyRollingRadii(true);
			sprite.setRolling(true);
			// ROM: addq.w #5,y_pos(a0) - adjust for hitbox height change (19->14 = 5 pixels)
			sprite.setY((short) (sprite.getY() + 5));
		} else {
			// ROM: Sonic_RollJump (s2.asm:37052-37054)
			// ROM: bset #status.player.rolljumping,status(a0)
			sprite.setRollingJump(true);
			// Already rolling, so rolling flag stays true
		}

		return true;
	}

	/**
	 * ROM: Sonic_SlopeResist (s2.asm:37360-37384)
	 * Apply slope factor to inertia when walking.
	 * Uses slope factor of 0x20 (32).
	 */
	private void doSlopeResist() {
		int hexAngle = getHexAngle(sprite);
		short gSpeed = sprite.getGSpeed();

		// ROM: Skip if on steep surface (angle + 0x60) >= 0xC0
		boolean onSteepSurface = ((hexAngle + 0x60) & 0xFF) >= 0xC0;
		if (onSteepSurface) {
			return;
		}

		// ROM: Skip if not moving (gSpeed == 0)
		if (gSpeed == 0) {
			return;
		}

		// ROM: slopeEffect = (0x20 * sin(angle)) >> 8
		int slopeEffect = (slopeRunning * TrigLookupTable.sinHex(hexAngle)) >> 8;
		sprite.setGSpeed((short) (gSpeed + slopeEffect));
	}

	/**
	 * ROM: Sonic_Move (s2.asm:36220-36484)
	 * Ground movement - input handling, acceleration/deceleration,
	 * velocity conversion, and wall collision.
	 *
	 * ROM flow:
	 * 1. Check move_lock - if set, branch to Obj01_ResetScr (reset camera, apply traction)
	 * 2. Process left/right input (Sonic_MoveLeft/Sonic_MoveRight)
	 * 3. If standing still on flat ground, handle look up/down or balance
	 * 4. Apply friction (Obj01_UpdateSpeedOnGround)
	 * 5. Convert gSpeed to X/Y (Obj01_Traction)
	 * 6. Wall collision (Obj01_CheckWallsOnGround)
	 */
	private void doGroundMove() {
		short gSpeed = sprite.getGSpeed();
		short runAccel = sprite.getRunAccel();
		short runDecel = sprite.getRunDecel();
		short friction = sprite.getFriction();
		short max = sprite.getMax();
		Camera camera = Camera.getInstance();

		// ROM: tst.w move_lock(a0) / bne.w Obj01_ResetScr (s2.asm:36226-36227)
		// When move_lock > 0, skip input but still apply traction and reset camera
		if (sprite.getMoveLockTimer() > 0) {
			// ROM: Obj01_ResetScr - reset camera bias toward center
			if (camera != null) {
				camera.resetYBias();
			}
			// Skip input processing, go straight to traction
			sprite.setGSpeed(gSpeed);
			calculateXYFromGSpeed(sprite);
			doWallCollisionGround();
			return;
		}

		// Left input (Sonic_MoveLeft s2.asm:36537)
		if (inputLeft) {
			if (gSpeed > 0) {
				// Decelerating (Sonic_TurnLeft s2.asm:36562)
				gSpeed -= runDecel;
				if (gSpeed <= 0) {
					gSpeed = (short) -128; // SPG: sign change = 0.5 pixel in opposite direction
				}
				// ROM: Skid slope-gate check (s2.asm:36568-36582)
				// Only allow skid on relatively flat ground: (angle + 0x20) & 0xC0 == 0
				// ROM uses bhi (branch if higher) which is strictly greater than, not >=
				int angle = sprite.getAngle() & 0xFF;
				boolean onFlatGround = ((angle + 0x20) & 0xC0) == 0;
				if (onFlatGround && gSpeed > 4 * 256) { // 0x400 - ROM: bhi, so strictly >
					handleSkid();
				}
			} else {
				// Accelerating left
				sprite.setSkidding(false);
				if (gSpeed > -max) {
					gSpeed -= runAccel;
					if (gSpeed < -max) {
						gSpeed = (short) -max;
					}
				}
			}
		}

		// Right input (Sonic_MoveRight s2.asm:36602)
		if (inputRight) {
			if (gSpeed < 0) {
				// Decelerating (Sonic_TurnRight s2.asm:36623)
				gSpeed += runDecel;
				if (gSpeed >= 0) {
					gSpeed = (short) 128; // SPG: sign change = 0.5 pixel in opposite direction
				}
				// ROM: Skid slope-gate check (s2.asm:36629-36642)
				// Only allow skid on relatively flat ground: (angle + 0x20) & 0xC0 == 0
				// ROM uses blo (branch if lower) which is strictly less than, not <=
				int angle = sprite.getAngle() & 0xFF;
				boolean onFlatGround = ((angle + 0x20) & 0xC0) == 0;
				if (onFlatGround && gSpeed < -4 * 256) { // -0x400 - ROM: blo, so strictly <
					handleSkid();
				}
			} else {
				// Accelerating right
				sprite.setSkidding(false);
				if (gSpeed < max) {
					gSpeed += runAccel;
					if (gSpeed > max) {
						gSpeed = max;
					}
				}
			}
		}

		// Clear skidding when no input
		if (!inputLeft && !inputRight) {
			sprite.setSkidding(false);
		}

		// ROM: Check standing still on flat ground for look up/down/balance (s2.asm:36238-36243)
		int angle = sprite.getAngle() & 0xFF;
		boolean onSlope = ((angle + 0x20) & 0xC0) != 0;

		if (!onSlope && gSpeed == 0) {
			// Clear pushing when standing still on flat ground
			sprite.setPushing(false);

			// ROM: Sonic_Lookup (s2.asm:36398-36409) - look up camera bias
			if (inputUp) {
				if (camera != null) {
					camera.setLookUpBias();
				}
			}
			// ROM: Sonic_Duck (s2.asm:36412-36423) - look down camera bias
			else if (inputDown) {
				if (camera != null) {
					camera.setLookDownBias();
				}
			}
			// ROM: Obj01_ResetScr (s2.asm:36428) - reset camera to center
			else {
				if (camera != null) {
					camera.resetYBias();
				}
			}
		} else {
			// ROM: Moving or on slope - Obj01_ResetScr
			if (camera != null) {
				camera.resetYBias();
			}
		}

		// Apply friction when no RAW input (ROM: Obj01_UpdateSpeedOnGround s2.asm:36442)
		if (!inputRawLeft && !inputRawRight) {
			if (gSpeed > 0) {
				gSpeed -= friction;
				if (gSpeed < 0) gSpeed = 0;
			} else if (gSpeed < 0) {
				gSpeed += friction;
				if (gSpeed > 0) gSpeed = 0;
			}
		}

		sprite.setGSpeed(gSpeed);

		// Convert gSpeed to X/Y velocities (ROM: Obj01_Traction s2.asm:36474)
		calculateXYFromGSpeed(sprite);

		// Wall collision (ROM: Obj01_CheckWallsOnGround s2.asm:36486)
		doWallCollisionGround();
	}

	/**
	 * ROM: Sonic_RollRepel (s2.asm:37393-37422)
	 * Apply rolling slope factor (80/20).
	 * Uses 80 when going downhill, 20 (80/4) when going uphill.
	 */
	private void doRollRepel() {
		int hexAngle = getHexAngle(sprite);
		short gSpeed = sprite.getGSpeed();

		// ROM: Skip if on steep surface (angle + 0x60) >= 0xC0
		boolean onSteepSurface = ((hexAngle + 0x60) & 0xFF) >= 0xC0;
		if (onSteepSurface) {
			return;
		}

		// Calculate slope effect (base = 0x50 = 80)
		int slopeEffect = (80 * TrigLookupTable.sinHex(hexAngle)) >> 8;

		// ROM: Divide by 4 when going uphill
		// Downhill = gSpeed and sin have same sign
		double sinValue = TrigLookupTable.sinHex(hexAngle);
		boolean goingDownhill = (gSpeed >= 0) == (sinValue >= 0);
		if (!goingDownhill) {
			slopeEffect >>= 2; // Divide by 4
		}

		sprite.setGSpeed((short) (gSpeed + slopeEffect));
	}

	/**
	 * ROM: Sonic_RollSpeed (s2.asm:36666-36758)
	 * Roll deceleration, velocity conversion, and wall collision.
	 *
	 * ROM uses three distinct deceleration values:
	 * - d4 = 0x20 (32) - controlled decel when pressing opposite direction (Sonic_BrakeRollingLeft/Right)
	 * - d5 = acceleration/2 - natural decel applied every frame (Sonic_ApplyRollSpeed)
	 * - d6 = top_speed*2 - max speed cap (not enforced during rolling)
	 */
	private void doRollSpeed() {
		short gSpeed = sprite.getGSpeed();

		// ROM: Obj01_Roll_ResetScr - reset camera Y bias toward default each frame while rolling
		// This is called regardless of input or speed (s2.asm:36733-36738)
		Camera.getInstance().resetYBias();

		// ROM: d4 = 0x20 - FIXED controlled roll deceleration (s2.asm:36671)
		// Note: Original comment says "should be Sonic_deceleration/4" for Tails,
		// but Sonic uses fixed 0x20
		final short controlledDecel = 0x20; // 32 subpixels

		// ROM: tst.w move_lock(a0) / bne.s Sonic_ApplyRollSpeed (s2.asm:36676)
		// Skip controlled input when move_lock is active (e.g., during slope slip recovery)
		boolean inputAllowed = sprite.getMoveLockTimer() == 0;

		// ROM: Sonic_RollLeft -> Sonic_BrakeRollingRight - Left input when rolling right (gSpeed > 0)
		// Only applies d4 deceleration, NOT friction (s2.asm:36776-36782)
		if (inputAllowed && inputLeft && gSpeed > 0) {
			gSpeed -= controlledDecel;
			// ROM: bcc.s + / move.w #-$80,d0 - if underflow, set to -0x80
			if (gSpeed <= 0) {
				gSpeed = (short) -128;
			}
		}

		// ROM: Sonic_RollRight -> Sonic_BrakeRollingLeft - Right input when rolling left (gSpeed < 0)
		// Only applies d4 deceleration, NOT friction (s2.asm:36798-36804)
		if (inputAllowed && inputRight && gSpeed < 0) {
			gSpeed += controlledDecel;
			// ROM: bcc.s + / move.w #$80,d0 - if overflow, set to 0x80
			if (gSpeed >= 0) {
				gSpeed = (short) 128;
			}
		}

		// ROM: Sonic_ApplyRollSpeed - Natural deceleration using d5 (acceleration/2)
		// Applied every frame regardless of input (s2.asm:36687-36706)
		if (gSpeed != 0) {
			short naturalDecel = (short) (sprite.getRunAccel() / 2);
			if (gSpeed > 0) {
				gSpeed -= naturalDecel;
				if (gSpeed < 0) gSpeed = 0;
			} else {
				gSpeed += naturalDecel;
				if (gSpeed > 0) gSpeed = 0;
			}
		}

		// ROM: Sonic_CheckRollStop (s2.asm:36709-36729)
		if (gSpeed == 0) {
			if (sprite.getPinballMode()) {
				// ROM: Sonic_KeepRolling - give a boost in facing direction (s2.asm:36725-36729)
				short boost = 0x400;
				if (sprite.getDirection() == Direction.LEFT) {
					boost = (short) -boost;
				}
				gSpeed = boost;
			} else {
				// ROM: Clear rolling, restore standing hitbox, adjust Y, reset animation
				// (s2.asm:36714-36718)
				// ROM: bclr #status.player.rolling,status(a0) / subq.w #5,y_pos(a0)
				sprite.setRolling(false);
				// ROM: subq.w #5,y_pos(a0) - adjust for hitbox height change (14->19 = -5 pixels)
				sprite.setY((short) (sprite.getY() - 5));
			}
		}

		sprite.setGSpeed(gSpeed);

		// Convert gSpeed to X/Y velocities (ROM: Sonic_SetRollSpeeds)
		int hexAngle = getHexAngle(sprite);
		short xVel = (short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
		short yVel = (short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8);

		// ROM: Cap X velocity at ±0x1000
		if (xVel > 0x1000) xVel = 0x1000;
		if (xVel < -0x1000) xVel = -0x1000;

		sprite.setXSpeed(xVel);
		sprite.setYSpeed(yVel);

		// Wall collision (ROM: Obj01_CheckWallsOnGround at end of Sonic_RollSpeed)
		doWallCollisionGround();
	}

	/**
	 * ROM: Sonic_Roll (s2.asm:36954-36992)
	 * Check if should start rolling.
	 *
	 * ROM order of checks:
	 * 1. Check sliding flag (skip if set)
	 * 2. Check |gSpeed| >= 0x80 (minStartRollSpeed)
	 * 3. Check left/right NOT held (must be false to roll)
	 * 4. Check down IS held (must be true to roll)
	 * 5. Check not already rolling
	 * 6. Set rolling, change hitbox, adjust Y, play sound
	 */
	private void doCheckStartRoll() {
		short gSpeed = sprite.getGSpeed();

		// ROM: mvabs.w inertia(a0),d0 / cmpi.w #$80,d0 / blo.s Obj01_NoRoll
		// Speed check: |gSpeed| must be >= 0x80 (128)
		// ROM uses blo (branch if lower), so 0x80 exactly allows rolling
		if (gSpeed < minStartRollSpeed && gSpeed > -minStartRollSpeed) {
			return;
		}

		// ROM: andi.b #button_left_mask|button_right_mask,d0 / bne.s Obj01_NoRoll
		// Left/right input guard: cannot start roll if left or right is held
		if (inputLeft || inputRight) {
			return;
		}

		// ROM: btst #button_down,(Ctrl_1_Held_Logical).w / bne.s Obj01_ChkRoll
		// Down must be held (and not locked from crouch walk)
		if (!inputDown || downLocked) {
			return;
		}

		// ROM: btst #status.player.rolling,status(a0) / beq.s Obj01_DoRoll
		// Must not already be rolling or in air
		if (sprite.getAir() || sprite.getRolling()) {
			return;
		}

		// ROM: Obj01_DoRoll - set rolling flag, change hitbox, adjust Y, play sound
		// ROM: bset #status.player.rolling,status(a0) / addq.w #5,y_pos(a0)
		sprite.setRolling(true);
		// ROM: addq.w #5,y_pos(a0) - adjust for hitbox height change (19->14 = 5 pixels)
		sprite.setY((short) (sprite.getY() + 5));
		audioManager.playSfx(GameSound.ROLLING);

		// ROM: Safety net (s2.asm:36986-36988)
		// tst.w inertia(a0) / bne.s return / move.w #$200,inertia(a0)
		// If somehow gSpeed became 0 after the threshold check, give a 0x200 boost
		if (sprite.getGSpeed() == 0) {
			sprite.setGSpeed((short) 0x200);
		}
	}

	/**
	 * ROM: Sonic_JumpHeight (s2.asm:37076-37098)
	 * Jump button release velocity cap + upward velocity cap.
	 */
	private void doJumpHeight() {
		// Jump release cap
		if (jumpPressed) {
			short ySpeedCap = sprite.isInWater() ? (short) (2 * 256) : (short) (4 * 256);
			if (sprite.getYSpeed() < -ySpeedCap) {
				if (!inputJump && !sprite.getSpringing()) {
					sprite.setYSpeed((short) (-ySpeedCap));
				}
			}
			if (!sprite.getAir() && !inputJump) {
				jumpPressed = false;
			}
		} else {
			// Sonic_UpVelCap - when not from player jump
			applyUpwardVelocityCap();
		}
	}

	/**
	 * ROM: Sonic_ChgJumpDir (s2.asm:36815-36879)
	 * Air control (left/right) + air drag at jump peak.
	 */
	private void doChgJumpDir() {
		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();
		short runAccel = sprite.getRunAccel();
		short max = sprite.getMax();

		// Air control - skip only if rolling jump (ROM: btst #status.player.rolljumping)
		// Note: ROM does NOT skip due to hurt state - hurt affects initial knockback velocity,
		// not ongoing air physics
		if (!sprite.getRollingJump()) {
			if (inputLeft) {
				// ROM: bset #status.player.x_flip,status(a0) - set facing left
				sprite.setDirection(Direction.LEFT);
				xSpeed -= (2 * runAccel);
				if (xSpeed < -max) {
					xSpeed = (short) -max;
				}
			}
			if (inputRight) {
				// ROM: bclr #status.player.x_flip,status(a0) - set facing right
				sprite.setDirection(Direction.RIGHT);
				xSpeed += (2 * runAccel);
				if (xSpeed > max) {
					xSpeed = max;
				}
			}
		}

		// ROM: Obj01_Jump_ResetScr (s2.asm:36845-36850)
		// Reset camera Y bias toward default (96) during airborne state
		Camera.getInstance().resetYBias();

		// Air drag (ROM: Sonic_JumpPeakDecelerate s2.asm:36853-36879)
		// ROM: cmpi.w #-$400,y_vel; blo.s skip (unsigned comparison)
		// Drag applies when -1024 <= ySpeed < 0 (near jump apex, rising slowly)
		// Does NOT apply when falling (ySpeed >= 0) or rising fast (ySpeed < -1024)
		if (ySpeed < 0 && ySpeed >= -1024) {
			// ROM: Sonic_JumpPeakDecelerateRight/Left (s2.asm:36862-36879)
			// ROM subtracts xSpeed/32 and clamps to 0 if crossing zero
			int drag = xSpeed / 32;
			if (drag != 0) {
				int newXSpeed = xSpeed - drag;
				// ROM: bcc.s/bcs.s checks for underflow/overflow and clamps to 0
				// If xSpeed was positive and becomes negative (or vice versa), clamp to 0
				if ((xSpeed > 0 && newXSpeed < 0) || (xSpeed < 0 && newXSpeed > 0)) {
					newXSpeed = 0;
				}
				xSpeed = (short) newXSpeed;
			}
		}

		sprite.setXSpeed(xSpeed);
		sprite.setYSpeed(ySpeed);
	}

	/**
	 * ROM: ObjectMoveAndFall
	 * Apply velocity to position AND add gravity.
	 */
	private void doObjectMoveAndFall() {
		// Apply velocity to position
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());

		// Apply gravity
		short ySpeed = sprite.getYSpeed();
		ySpeed += sprite.getGravity();
		sprite.setYSpeed(ySpeed);
	}

	/**
	 * ROM: Sonic_LevelBound (s2.asm:36890-36944)
	 * Check level boundaries.
	 */
	private void doLevelBoundary() {
		Camera camera = Camera.getInstance();
		if (camera == null) return;

		final int SCREEN_WIDTH = 320;
		final int SONIC_WIDTH = 24;
		final int LEFT_OFFSET = 16;
		final int RIGHT_EXTRA_BUFFER = 64;

		// Predictive position check
		int xTotal = (sprite.getX() * 256) + (sprite.getXSubpixel() & 0xFF);
		xTotal += sprite.getXSpeed();
		int predictedX = xTotal / 256;

		int cameraMinX = camera.getMinX();
		int cameraMaxX = camera.getMaxX();

		int leftBoundary = cameraMinX + LEFT_OFFSET;
		int rightBoundary = cameraMaxX + SCREEN_WIDTH - SONIC_WIDTH;

		if (!GameServices.gameState().isBossFightActive()) {
			rightBoundary += RIGHT_EXTRA_BUFFER;
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

		// Bottom boundary check
		int bottomBoundary = camera.getMaxY() + 224;
		if (sprite.getY() > bottomBoundary) {
			sprite.applyPitDeath();
		}
	}

	/**
	 * ROM: AnglePos (s2.asm:42534-42592)
	 * Ground terrain collision - adjust position to terrain surface.
	 *
	 * ROM order:
	 * 1. Check on_object flag - if set, set angle to 0 and return immediately
	 * 2. Probe both ground sensors (left and right foot)
	 * 3. Call Sonic_Angle to select the best sensor and update angle
	 * 4. Adjust Y position based on selected sensor distance
	 * 5. Check distance thresholds for detachment
	 */
	private void doAnglePos() {
		// ROM: btst #status.player.on_object,status(a0) / beq.s + / moveq #0,d0 / rts
		// When riding on an object, skip terrain collision entirely and set angle to 0
		if (sprite.isOnObject()) {
			sprite.setAngle((byte) 0);
			return;
		}

		// CRITICAL: ROM calculates ground mode from current angle BEFORE scanning (s2.asm:42551-42592)
		// This determines which sensors to use (floor/wall/ceiling) and which scan direction.
		// Without this, we use stale ground mode and wrong sensors, causing loop detachment.
		updateGroundMode(sprite);

		SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");

		// ROM uses both sensors and calls Sonic_Angle to merge them
		// ROM: Primary_Angle = right sensor (x_pos + x_radius), Secondary_Angle = left sensor (x_pos - x_radius)
		// Our sensors: groundSensors[0] = left (-xRadius), groundSensors[1] = right (+xRadius)
		SensorResult leftSensor = groundResult[0];
		SensorResult rightSensor = groundResult[1];

		// Use Sonic_Angle logic to select the best sensor
		SensorResult selectedResult = selectSensorWithAngle(rightSensor, leftSensor);

		// No sensor result - detach
		if (selectedResult == null) {
			// Check for solid object before detaching
			var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
			if (objectManager != null && (objectManager.isRidingObject()
					|| objectManager.hasStandingContact(sprite))) {
				return;
			}
			// ROM: bset #status.player.in_air / bclr #status.player.pushing
			sprite.setAir(true);
			sprite.setPushing(false);
			return;
		}

		byte distance = selectedResult.distance();

		// ROM: tst.w d1 / beq.s return_1E31C (line 42607-42608)
		// Distance == 0: exactly on ground, no adjustment needed
		if (distance == 0) {
			updateGroundMode(sprite);
			return;
		}

		// ROM: bpl.s loc_1E31E (line 42609)
		// Handle negative distances (embedded in terrain) vs positive distances (above ground)
		if (distance < 0) {
			// ROM: cmpi.w #-$E,d1 / blt.s return_1E31C (lines 42610-42611)
			// If distance < -14, character is too deeply embedded in terrain.
			// DON'T detach - just don't adjust. Stay grounded and let momentum carry through.
			if (distance < -14) {
				updateGroundMode(sprite);
				return;
			}
			// ROM: add.w d1,y_pos(a0) (line 42612)
			// -14 <= distance < 0: snap character UP to ground surface
			moveForSensorResult(sprite, selectedResult);
			updateGroundMode(sprite);
			return;
		}

		// Positive distance: character is above ground (floating)
		// ROM: Dynamic threshold based on speed
		// - Floor/Ceiling: uses |x_vel| (s2.asm:42619, 42794)
		// - Left/Right Wall: uses |y_vel| (s2.asm:42727, 42861)
		// Threshold = min(abs(velocity >> 8) + 4, 14)
		GroundMode groundMode = sprite.getGroundMode();
		int speedPixels;
		if (groundMode == GroundMode.LEFTWALL || groundMode == GroundMode.RIGHTWALL) {
			// Wall modes use Y velocity
			speedPixels = Math.abs(sprite.getYSpeed() >> 8);
		} else {
			// Floor/Ceiling modes use X velocity
			speedPixels = Math.abs(sprite.getXSpeed() >> 8);
		}
		// ROM ACCURACY: When xSpeed/ySpeed is 0 but gSpeed is not (e.g., just after spindash release),
		// use gSpeed for threshold to prevent incorrect detachment. The ROM's doAnglePos is called
		// after gSpeed is set but before it's decomposed to xSpeed/ySpeed, so we need to consider
		// gSpeed when the velocity component being checked is zero.
		if (speedPixels == 0 && sprite.getGSpeed() != 0) {
			speedPixels = Math.abs(sprite.getGSpeed() >> 8);
		}
		// MODIFICATION: When transitioning between ground modes at high speed, the velocity
		// component used for threshold may be small (e.g., xSpeed ~0 when transitioning to wall mode)
		// even though gSpeed is large. Use the maximum of the mode-specific velocity and gSpeed
		// to prevent premature detachment during mode transitions.
		int gSpeedPixels = Math.abs(sprite.getGSpeed() >> 8);
		if (gSpeedPixels > speedPixels) {
			speedPixels = gSpeedPixels;
		}
		int positiveThreshold = Math.min(speedPixels + 4, 14);

		// ROM: cmp.b d0,d1 / bgt.s loc_1E33C (lines 42625-42626)
		if (distance > positiveThreshold) {
			// Too far above ground (floating) - check stick_to_convex before detaching
			// ROM: loc_1E33C - tst.b stick_to_convex(a0) / bne.s loc_1E336 (lines 42633-42635)
			if (sprite.isStickToConvex()) {
				// Stay attached - adjust position and continue
				moveForSensorResult(sprite, selectedResult);
				updateGroundMode(sprite);
				return;
			}

			// Check for solid object before detaching
			var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
			if (objectManager != null && (objectManager.isRidingObject()
					|| objectManager.hasStandingContact(sprite))) {
				return;
			}

			// ROM: bset #status.player.in_air,status(a0) (line 42636)
			// ROM: bclr #status.player.pushing,status(a0) (line 42637)
			sprite.setAir(true);
			sprite.setPushing(false);
			return;
		}

		// ROM: loc_1E336 - add.w d1,y_pos(a0) (line 42629)
		// Distance is within threshold - adjust position
		moveForSensorResult(sprite, selectedResult);
		updateGroundMode(sprite);
	}

	/**
	 * ROM: Sonic_Angle (s2.asm:42649-42674)
	 * Select the best ground sensor and update the sprite's angle.
	 *
	 * ROM Logic:
	 * 1. Compare distances from both sensors
	 * 2. Use the sensor with SMALLER distance (closer to ground)
	 * 3. If selected angle is flagged (0xFF) or diff from current > 0x20, snap to cardinal
	 * 4. Otherwise, use the selected sensor's angle
	 *
	 * @param rightSensor Right foot sensor result (Primary_Angle in ROM)
	 * @param leftSensor Left foot sensor result (Secondary_Angle in ROM)
	 * @return The selected sensor result to use for position adjustment
	 */
	private SensorResult selectSensorWithAngle(SensorResult rightSensor, SensorResult leftSensor) {
		// Handle null cases
		if (rightSensor == null && leftSensor == null) {
			return null;
		}
		if (rightSensor == null) {
			applyAngleFromSensor(leftSensor.angle());
			return leftSensor;
		}
		if (leftSensor == null) {
			applyAngleFromSensor(rightSensor.angle());
			return rightSensor;
		}

		// ROM: cmp.w d0,d1 / ble.s + / move.b (Primary_Angle).w,d2 / move.w d0,d1
		// Compare distances: use sensor with SMALLER distance (closer to ground)
		// d0 = right sensor distance, d1 = left sensor distance
		// If left <= right, use left (Secondary). Otherwise use right (Primary).
		SensorResult selectedResult;
		byte selectedAngle;

		if (leftSensor.distance() <= rightSensor.distance()) {
			// Use left sensor (Secondary_Angle)
			selectedResult = leftSensor;
			selectedAngle = leftSensor.angle();
		} else {
			// Use right sensor (Primary_Angle)
			selectedResult = rightSensor;
			selectedAngle = rightSensor.angle();
		}

		applyAngleFromSensor(selectedAngle);
		return selectedResult;
	}

	/**
	 * Apply angle from sensor result, following ROM's Sonic_Angle logic.
	 *
	 * ROM: btst #0,d2 / bne.s loc_1E380 - check if bit 0 is set (odd angle = flagged)
	 * ROM: cmpi.b #$20,d0 / bhs.s loc_1E380 - if diff >= 0x20, snap to cardinal
	 * ROM: move.b d2,angle(a0) - otherwise use the new angle
	 *
	 * MODIFICATION: When diff >= 0x20, the ROM snaps to a cardinal based on the
	 * CURRENT angle. This can cause issues at high speed where terrain angle changes
	 * rapidly - the sprite angle "lags behind" the terrain. We now snap toward the
	 * TERRAIN angle to better follow curves at high speed. This improves loop
	 * traversal when moving faster than the original game typically allowed.
	 *
	 * @param sensorAngle The angle from the selected sensor
	 */
	private void applyAngleFromSensor(byte sensorAngle) {
		// ROM: btst #0,d2 / bne.s loc_1E380
		// If angle has bit 0 set (odd number), it's flagged - snap to cardinal
		// The ROM uses odd angles as flags (e.g., 0xFF, 0x03) to indicate "no valid angle"
		if ((sensorAngle & 0x01) != 0) {
			sprite.setAngle((byte) ((sprite.getAngle() + 0x20) & 0xC0));
			return;
		}

		// ROM: move.b d2,d0 / sub.b angle(a0),d0 / bpl.s + / neg.b d0
		// Calculate absolute difference between new and current angle
		int currentAngle = sprite.getAngle() & 0xFF;
		int newAngle = sensorAngle & 0xFF;
		int diff = newAngle - currentAngle;
		if (diff < 0) {
			diff = -diff;
		}
		// Handle wraparound (e.g., 0xF0 to 0x10 is only 0x20 apart, not 0xE0)
		if (diff > 0x80) {
			diff = 0x100 - diff;
		}

		// ROM: cmpi.b #$20,d0 / bhs.s loc_1E380
		// If difference >= 0x20 (32), snap to cardinal angle
		if (diff >= 0x20) {
			// MODIFICATION: Snap toward the TERRAIN angle instead of current angle.
			// This helps Sonic follow curves at high speed where terrain angle changes
			// by more than 0x20 per frame. Without this, the sprite angle lags behind
			// terrain, causing sensors to scan in wrong direction and detachment.
			sprite.setAngle((byte) ((newAngle + 0x20) & 0xC0));
		} else {
			// ROM: move.b d2,angle(a0)
			sprite.setAngle(sensorAngle);
		}
	}

	/**
	 * ROM: Sonic_SlopeRepel (s2.asm:37432-37455)
	 * Slip/fall check - detach if too slow on steep slope.
	 *
	 * ROM Logic:
	 * 1. If stick_to_convex is set, return (don't repel)
	 * 2. If move_lock > 0, decrement it and return
	 * 3. Check angle - if flat (0x00-0x1F or 0xE0-0xFF after +0x20 mask), return
	 * 4. Check |gSpeed| - if >= 0x280 (640), return
	 * 5. Otherwise: clear gSpeed, set air flag, set move_lock to 0x1E (30)
	 */
	private void doSlopeRepel() {
		// ROM: tst.b stick_to_convex(a0) / bne.s return_1AE42
		// If stick_to_convex is set, don't repel - stay attached to slope
		if (sprite.isStickToConvex()) {
			return;
		}

		// ROM: tst.w move_lock(a0) / bne.s loc_1AE44
		// If move_lock > 0, decrement and return (don't repel)
		int moveLock = sprite.getMoveLockTimer();
		if (moveLock > 0) {
			// ROM: loc_1AE44: subq.w #1,move_lock(a0) / rts
			sprite.setMoveLockTimer(moveLock - 1);
			return;
		}

		int angle = sprite.getAngle() & 0xFF;

		// ROM: move.b angle(a0),d0 / addi.b #$20,d0 / andi.b #$C0,d0 / beq.s return_1AE42
		// Skip when angle is in flat range (after adding 0x20 and masking with 0xC0)
		// This means: if ((angle + 0x20) & 0xC0) == 0, it's flat - don't repel
		boolean angleIsFlat = ((angle + 0x20) & 0xC0) == 0;
		if (angleIsFlat) {
			return;
		}

		// ROM: mvabs.w inertia(a0),d0 / cmpi.w #$280,d0 / bhs.s return_1AE42
		// If |gSpeed| >= 0x280 (640), don't repel
		short absGSpeed = (short) Math.abs(sprite.getGSpeed());
		if (absGSpeed >= 0x280) {
			return;
		}

		// ROM: clr.w inertia(a0)
		sprite.setGSpeed((short) 0);

		// ROM: bset #status.player.in_air,status(a0)
		sprite.setAir(true);

		// ROM: move.w #$1E,move_lock(a0)
		// Set move_lock to 30 frames (0x1E)
		sprite.setMoveLockTimer(0x1E);
	}

	/**
	 * ROM: Sonic_DoLevelCollision (s2.asm:37540-37733)
	 * Full airborne collision - walls, floor, and ceiling.
	 *
	 * Routes to different collision paths based on movement quadrant:
	 * - 0x00 (down/down-right): Both walls + Floor only (NO ceiling)
	 * - 0x40 (left): Left wall → Ceiling → Floor (conditional)
	 * - 0x80 (up): Both walls + Ceiling only (NO floor)
	 * - 0xC0 (right): Right wall → Ceiling → Floor (conditional)
	 */
	private void doLevelCollision() {
		short xSpeed = sprite.getXSpeed();
		short ySpeed = sprite.getYSpeed();
		int quadrant = TrigLookupTable.calcMovementQuadrant(xSpeed, ySpeed);

		switch (quadrant) {
			case 0x00 -> doLevelCollisionDown();
			case 0x40 -> doLevelCollisionLeft();
			case 0x80 -> doLevelCollisionUp();
			case 0xC0 -> doLevelCollisionRight();
		}
	}

	/**
	 * ROM: Quadrant 0x00 - Moving down/down-right (s2.asm:37558-37615)
	 * Checks both walls, then floor. NO ceiling check.
	 */
	private void doLevelCollisionDown() {
		doWallCheckBoth();

		// Floor only - no ceiling check for this quadrant
		SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
		doTerrainCollisionAir(groundResult);
	}

	/**
	 * ROM: Quadrant 0x40 - Moving left (Sonic_HitLeftWall, s2.asm:37618-37654)
	 * Checks left wall first. If hit, returns early. Otherwise ceiling, then floor.
	 */
	private void doLevelCollisionLeft() {
		// Check left wall - if hit, return early (ROM: rts after wall collision)
		if (doWallCheckLeft()) {
			return;
		}

		// Check ceiling
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
		boolean hitCeiling = doCeilingCollisionInternal(ceilingResult);

		// Check floor only if ceiling wasn't hit (ROM: Sonic_HitFloor branch)
		if (!hitCeiling) {
			SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
			doTerrainCollisionAir(groundResult);
		}
	}

	/**
	 * ROM: Quadrant 0x80 - Moving up (Sonic_HitCeilingAndWalls, s2.asm:37657-37691)
	 * Checks both walls, then ceiling. NO floor check.
	 */
	private void doLevelCollisionUp() {
		doWallCheckBoth();

		// Ceiling only - no floor check for this quadrant
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
		doCeilingCollision(ceilingResult);
	}

	/**
	 * ROM: Quadrant 0xC0 - Moving right (Sonic_HitRightWall, s2.asm:37694-37732)
	 * Checks right wall first. If hit, returns early. Otherwise ceiling, then floor.
	 */
	private void doLevelCollisionRight() {
		// Check right wall - if hit, return early (ROM: rts after wall collision)
		if (doWallCheckRight()) {
			return;
		}

		// Check ceiling
		SensorResult[] ceilingResult = collisionSystem.terrainProbes(sprite, sprite.getCeilingSensors(), "ceiling");
		boolean hitCeiling = doCeilingCollisionInternal(ceilingResult);

		// Check floor only if ceiling wasn't hit (ROM: Sonic_HitFloor2 branch)
		if (!hitCeiling) {
			SensorResult[] groundResult = collisionSystem.terrainProbes(sprite, sprite.getGroundSensors(), "ground");
			doTerrainCollisionAir(groundResult);
		}
	}

	/**
	 * ROM: CheckLeftWallDist + CheckRightWallDist (used in quadrants 0x00 and 0x80)
	 * Checks both walls, adjusts position and zeros x velocity if hit.
	 */
	private void doWallCheckBoth() {
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) {
			return;
		}

		// Check left wall
		SensorResult leftResult = pushSensors[0].scan((short) 0, (short) 0);
		if (leftResult != null && leftResult.distance() < 0) {
			moveForSensorResult(sprite, leftResult);
			sprite.setXSpeed((short) 0);
		}

		// Check right wall
		SensorResult rightResult = pushSensors[1].scan((short) 0, (short) 0);
		if (rightResult != null && rightResult.distance() < 0) {
			moveForSensorResult(sprite, rightResult);
			sprite.setXSpeed((short) 0);
		}
	}

	/**
	 * ROM: Sonic_HitLeftWall wall check (s2.asm:37618-37625)
	 * Returns true if wall was hit (caller should return early).
	 */
	private boolean doWallCheckLeft() {
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) {
			return false;
		}

		SensorResult result = pushSensors[0].scan((short) 0, (short) 0);
		if (result != null && result.distance() < 0) {
			moveForSensorResult(sprite, result);
			sprite.setXSpeed((short) 0);
			// ROM: move.w y_vel(a0),inertia(a0)
			sprite.setGSpeed(sprite.getYSpeed());
			return true;
		}
		return false;
	}

	/**
	 * ROM: Sonic_HitRightWall wall check (s2.asm:37694-37701)
	 * Returns true if wall was hit (caller should return early).
	 */
	private boolean doWallCheckRight() {
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) {
			return false;
		}

		SensorResult result = pushSensors[1].scan((short) 0, (short) 0);
		if (result != null && result.distance() < 0) {
			moveForSensorResult(sprite, result);
			sprite.setXSpeed((short) 0);
			// ROM: move.w y_vel(a0),inertia(a0)
			sprite.setGSpeed(sprite.getYSpeed());
			return true;
		}
		return false;
	}

	/**
	 * ROM: Sonic_HitCeiling / Sonic_HitCeiling2 (s2.asm:37628-37638, 37705-37715)
	 * Internal ceiling collision that returns whether ceiling was hit.
	 * Used by quadrants 0x40 and 0xC0 to determine if floor check should be skipped.
	 */
	private boolean doCeilingCollisionInternal(SensorResult[] results) {
		// ROM: Only process ceiling collision when moving upward
		if (sprite.getYSpeed() >= 0) {
			return false;
		}

		SensorResult lowestResult = findLowestSensorResult(results);
		if (lowestResult == null || lowestResult.distance() >= 0) {
			return false;
		}

		// ROM: Adjust position to ceiling surface
		moveForSensorResult(sprite, lowestResult);

		// ROM: Stop upward velocity (s2.asm:37635)
		sprite.setYSpeed((short) 0);

		return true;
	}

	/**
	 * ROM: Obj01_CheckWallsOnGround (s2.asm:36486)
	 * Ground wall collision using angle-based selection.
	 */
	private void doWallCollisionGround() {
		// Skip if no sensors (test mock)
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) {
			return;
		}

		int angle = sprite.getAngle() & 0xFF;
		short gSpeed = sprite.getGSpeed();

		// Skip on steep terrain
		boolean angleInSkipRange = ((angle + 0x40) & 0x80) != 0;
		if (angleInSkipRange) {
			return;
		}

		// Skip if not moving
		if (gSpeed == 0) {
			return;
		}

		// Calculate rotated angle
		int rotatedAngle;
		if (gSpeed >= 0) {
			rotatedAngle = (angle - 0x40) & 0xFF;
		} else {
			rotatedAngle = (angle + 0x40) & 0xFF;
		}

		int quadrant = (rotatedAngle + 0x20) & 0xC0;

		// ROM handles all 4 quadrants (s2.asm:36504-36527)
		// Quadrant determines which sensor to use and which velocity to adjust
		int sensorIndex = (quadrant == 0x40 || quadrant == 0x00) ? 0 : 1;

		// ROM: CalcRoomInFront projects the position forward by velocity to detect walls early.
		// This prevents high-speed clipping through walls and missing wall collisions on slopes.
		// The projection is the high byte of (vel + subpixel), matching the fixed-point math.
		short projectedDx = (short) ((sprite.getXSpeed() + (sprite.getXSubpixel() & 0xFF)) >> 8);
		short projectedDy = (short) ((sprite.getYSpeed() + (sprite.getYSubpixel() & 0xFF)) >> 8);

		// ROM: s2.asm:43517-43519 - for wall checks (quadrants 0x40/0xC0), add 8 to Y
		// when bits 3-5 of rotatedAngle are clear. This scans at foot level on near-flat ground.
		// The push sensor already adds 8 when angle==0 (via updatePushSensorYOffset), so we
		// only add here for slight slopes (angles 0x01-0x07) where sensor doesn't add the offset
		// but ROM does. Without this, Java scans 8px higher than ROM on slight slopes, causing
		// false wall detections on curved surfaces like loops.
		if ((quadrant == 0x40 || quadrant == 0xC0) && (rotatedAngle & 0x38) == 0 && angle != 0) {
			projectedDy += 8;
		}

		SensorResult result = pushSensors[sensorIndex].scan(projectedDx, projectedDy);

		if (result == null) {
			return;
		}

		byte distance = result.distance();

		if (distance < 0) {
			// ROM: asl.w #8,d1 - convert pixel distance to subpixel
			int delta = distance << 8;

			// ROM handles each quadrant differently (s2.asm:36504-36527)
			switch (quadrant) {
				case 0x00 -> {
					// Quadrant 0x00: add to y_vel, NO pushing, NO gSpeed clear
					sprite.setYSpeed((short) (sprite.getYSpeed() + delta));
				}
				case 0x40 -> {
					// Quadrant 0x40: sub from x_vel, SET pushing, CLEAR gSpeed
					sprite.setXSpeed((short) (sprite.getXSpeed() - delta));
					sprite.setPushing(true);
					sprite.setGSpeed((short) 0);
				}
				case 0x80 -> {
					// Quadrant 0x80: sub from y_vel, NO pushing, NO gSpeed clear
					sprite.setYSpeed((short) (sprite.getYSpeed() - delta));
				}
				default -> { // 0xC0
					// Quadrant 0xC0: add to x_vel, SET pushing, CLEAR gSpeed
					sprite.setXSpeed((short) (sprite.getXSpeed() + delta));
					sprite.setPushing(true);
					sprite.setGSpeed((short) 0);
				}
			}
		}
	}

	/**
	 * Airborne terrain collision - landing check.
	 */
	private void doTerrainCollisionAir(SensorResult[] results) {
		SensorResult lowestResult = findLowestSensorResult(results);

		if (lowestResult == null || lowestResult.distance() >= 0) {
			return;
		}

		// Don't land while moving upward
		if (sprite.getYSpeed() < 0) {
			return;
		}

		// ROM landing threshold (s2.asm:37573-37579)
		// Threshold = -(high_byte(y_vel) + 8)
		// Land if either sensor distance >= threshold
		// Note: ROM does NOT have an absXSpeed >= absYSpeed gate
		short ySpeedPixels = (short) (sprite.getYSpeed() >> 8);
		short threshold = (short) (-(ySpeedPixels + 8));
		boolean canLand = (results[0] != null && results[0].distance() >= threshold)
		               || (results[1] != null && results[1].distance() >= threshold);

		if (canLand) {
			moveForSensorResult(sprite, lowestResult);

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

			calculateLanding(sprite);
			updateGroundMode(sprite);
		}
	}

	/**
	 * ROM: Sonic_HitCeiling / Sonic_HitCeilingAndWalls (s2.asm:37695-37733)
	 * Ceiling collision - check for hitting ceiling and potentially landing on it.
	 *
	 * ROM Logic:
	 * 1. Check if moving upward (ySpeed < 0)
	 * 2. Probe ceiling sensors
	 * 3. If collision found (distance < 0):
	 *    a. Adjust position
	 *    b. Check if angle allows ceiling landing ((angle + 0x20) & 0x40 != 0)
	 *    c. If can land: set grounded, convert ySpeed to gSpeed
	 *    d. If cannot land: just stop upward velocity
	 *
	 * NOTE: ROM does NOT have a springing check or movement quadrant gate here.
	 * The quadrant routing happens at a higher level (Sonic_DoLevelCollision).
	 */
	private void doCeilingCollision(SensorResult[] results) {
		// ROM: Only process ceiling collision when moving upward
		if (sprite.getYSpeed() >= 0) {
			return;
		}

		SensorResult lowestResult = findLowestSensorResult(results);
		if (lowestResult == null || lowestResult.distance() >= 0) {
			return;
		}

		// ROM: Adjust position to ceiling surface
		moveForSensorResult(sprite, lowestResult);

		// ROM: Check if angle allows landing on ceiling (s2.asm:37712-37714)
		// Angles near 0x40 (left wall) or 0xC0 (right wall) allow landing
		// Formula: ((angle + 0x20) & 0x40) != 0
		int ceilingAngle = lowestResult.angle() & 0xFF;
		boolean canLandOnCeiling = ((ceilingAngle + 0x20) & 0x40) != 0;

		if (canLandOnCeiling) {
			// ROM: Land on ceiling (s2.asm:37715-37728)
			sprite.setAngle(lowestResult.angle());
			sprite.setAir(false);
			// ROM ACCURACY: Does NOT clear rolling or pinball mode when landing on ceiling.
			// The rolling state is preserved through the entire loop traversal.
			// Only setAir(false) is called - the player continues rolling on the ceiling.

			// ROM: Convert ySpeed to gSpeed (s2.asm:37721-37726)
			// gSpeed = ySpeed, negated if angle is in upper half
			short gSpeed = sprite.getYSpeed();
			if ((ceilingAngle & 0x80) != 0) {
				gSpeed = (short) -gSpeed;
			}
			sprite.setGSpeed(gSpeed);
			// ROM: Does NOT clear ySpeed after ceiling landing - the grounded state
			// will recalculate velocities from gSpeed on the next frame
			updateGroundMode(sprite);
		} else {
			// ROM: Hit flat ceiling - just stop upward movement (s2.asm:37729-37731)
			sprite.setYSpeed((short) 0);
		}
	}

	// ========================================
	// HELPER METHODS
	// ========================================

	private void handleDebugMovement(boolean up, boolean down, boolean left, boolean right) {
		int debugMoveSpeed = 3;
		if (left) sprite.setX((short) (sprite.getX() - debugMoveSpeed));
		if (right) sprite.setX((short) (sprite.getX() + debugMoveSpeed));
		if (up) sprite.setY((short) (sprite.getY() - debugMoveSpeed));
		if (down) sprite.setY((short) (sprite.getY() + debugMoveSpeed));
	}

	private void handleTestKey(boolean testKey) {
		if (testKey && !testKeyPressed) {
			testKeyPressed = true;
			if (GroundMode.GROUND.equals(sprite.getGroundMode())) {
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
	}

	/**
	 * ROM: move_lock check (s2.asm:36226, 36676)
	 * Returns true if horizontal control is locked (move_lock > 0).
	 * This is set by Sonic_SlopeRepel when slipping off a slope.
	 */
	private boolean isControlLocked() {
		return sprite.getMoveLockTimer() > 0;
	}

	private void updatePushingOnDirectionChange(boolean left, boolean right) {
		if (left && !right && sprite.getDirection() == Direction.RIGHT) {
			sprite.setPushing(false);
		} else if (right && !left && sprite.getDirection() == Direction.LEFT) {
			sprite.setPushing(false);
		}
	}

	private void handleSkid() {
		if (!sprite.getSkidding()) {
			sprite.setSkidding(true);
		}
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
			if (!sprite.getAir() && sprite.getGSpeed() > 0) {
				sprite.setDirection(Direction.RIGHT);
			} else {
				sprite.setDirection(Direction.LEFT);
			}
		} else if (inputRight && !inputLeft) {
			if (!sprite.getAir() && sprite.getGSpeed() < 0) {
				sprite.setDirection(Direction.LEFT);
			} else {
				sprite.setDirection(Direction.RIGHT);
			}
		}
	}

	private void checkPitDeath() {
		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getY() + camera.getHeight()) {
			sprite.applyPitDeath();
		}
	}

	private void updateCrouchState() {
		// ROM: Sonic_Duck (s2.asm:36412-36423)
		// Crouching requires:
		// - Down held, no left/right
		// - Not in air, not rolling, not spindashing
		// - gSpeed == 0 (standing still)
		// - On flat ground: ((angle + 0x20) & 0xC0) == 0
		int angle = sprite.getAngle() & 0xFF;
		boolean onFlatGround = ((angle + 0x20) & 0xC0) == 0;

		boolean crouching = inputDown
				&& !inputLeft
				&& !inputRight
				&& !sprite.getAir()
				&& !sprite.getRolling()
				&& !sprite.getSpindash()
				&& sprite.getGSpeed() == 0
				&& onFlatGround;
		sprite.setCrouching(crouching);

		if (wasCrouching && (inputLeft || inputRight)) {
			downLocked = true;
		}

		if (!inputDown) {
			downLocked = false;
		}
	}

	private void applyUpwardVelocityCap() {
		if (!sprite.getAir() || jumpPressed) {
			return;
		}
		if (sprite.getPinballMode()) {
			return;
		}
		short yVel = sprite.getYSpeed();
		if (yVel < -0xFC0) {
			sprite.setYSpeed((short) -0xFC0);
		}
	}

	private void applyDeathMovement(AbstractPlayableSprite sprite) {
		short ySpeed = sprite.getYSpeed();
		ySpeed += sprite.getGravity();
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		sprite.setYSpeed(ySpeed);

		Camera camera = Camera.getInstance();
		if (camera != null && sprite.getY() > camera.getY() + camera.getHeight() + 256) {
			sprite.startDeathCountdown();
		}

		if (sprite.tickDeathCountdown()) {
			GameServices.gameState().loseLife();
			uk.co.jamesj999.sonic.level.LevelManager.getInstance().requestRespawn();
		}
	}

	/**
	 * ROM: Sonic_ResetOnFloor (s2.asm:37744-37778)
	 * Unified landing state reset - clears all landing-related flags.
	 *
	 * ROM order of operations:
	 * 1. If NOT pinball_mode: set walk animation
	 * 2. If rolling: clear rolling flag, restore standing radii, adjust y_pos by -5, set walk anim
	 * 3. Clear in_air flag
	 * 4. Clear pushing flag
	 * 5. Clear rolljumping flag
	 * 6. Clear jumping flag
	 * 7. Reset chain bonus counter
	 * 8. Clear flip_angle, flip_turned, flips_remaining
	 * 9. Reset look delay counter
	 * 10. If hang animation: set walk animation
	 */
	private void resetOnFloor() {
		boolean hadPinballMode = sprite.getPinballMode();

		// ROM: Sonic_ResetOnFloor_Part2 - hitbox restoration (s2.asm:37749-37761)
		// If rolling and NOT pinball mode, restore standing hitbox
		if (sprite.getRolling() && !hadPinballMode) {
			// ROM: bclr #status.player.rolling,status(a0) (s2.asm:37757)
			// ROM: move.b #$13,y_radius(a0) (s2.asm:37758)
			// ROM: move.b #9,x_radius(a0) (s2.asm:37759)
			// setRolling(false) handles: clearing flag, applying standing radii, updating visual dimensions
			sprite.setRolling(false);
			// ROM: subq.w #5,y_pos(a0) (s2.asm:37761)
			// Adjust Y position for hitbox height change (y_radius 14->19 = -5 pixels)
			sprite.setY((short) (sprite.getY() - 5));
		}

		// Clear pinball mode now (after checking for hitbox restoration)
		sprite.setPinballMode(false);

		// ROM: Sonic_ResetOnFloor_Part3 (s2.asm:37763-37777)
		// ROM: bclr #status.player.in_air,status(a0) (s2.asm:37764)
		// Note: setAir(false) auto-clears jumping flag via sprite implementation
		sprite.setAir(false);

		// ROM: bclr #status.player.pushing,status(a0) (s2.asm:37765)
		sprite.setPushing(false);

		// ROM: bclr #status.player.rolljumping,status(a0) (s2.asm:37766)
		sprite.setRollingJump(false);

		// ROM: move.b #0,jumping(a0) (s2.asm:37767)
		sprite.setJumping(false);

		// ROM: move.w #0,(Chain_Bonus_counter).w (s2.asm:37768)
		// TODO: Reset chain bonus counter when implemented

		// ROM: Clear flip angles (s2.asm:37769-37771)
		// TODO: sprite.setFlipAngle(0); sprite.setFlipTurned(0); sprite.setFlipsRemaining(0);

		// ROM: Reset look delay counter (s2.asm:37772)
		// TODO: Reset look delay counter when implemented
	}

	/**
	 * ROM: Landing gSpeed calculation (s2.asm:37584-37614)
	 * Calculate ground speed when landing based on angle and velocities.
	 *
	 * ROM angle classification and gSpeed formula:
	 * - (angle + 0x20) & 0x40 != 0 → steep slope (near walls), go to loc_1AF68
	 * - (angle + 0x10) & 0x20 == 0 → flat/shallow slope, go to loc_1AF5A
	 * - Otherwise → moderate slope, halve ySpeed
	 *
	 * loc_1AF5A (flat): ySpeed = 0, gSpeed = xSpeed
	 * loc_1AF7C (moderate): gSpeed = ySpeed/2, sign from angle
	 * loc_1AF68 (steep): xSpeed = 0, ySpeed capped at 0xFC0, gSpeed = ySpeed, sign from angle
	 */
	private void calculateLanding(AbstractPlayableSprite sprite) {
		// First, reset all landing-related state (ROM calls this before gSpeed calc)
		resetOnFloor();

		short ySpeed = sprite.getYSpeed();
		short xSpeed = sprite.getXSpeed();
		short gSpeed;
		int angle = sprite.getAngle() & 0xFF;

		// ROM: Check if falling (ySpeed > 0) - only calculate gSpeed when landing from above
		// Note: ROM checks this at the caller level, but we do it here for safety
		if (ySpeed <= 0) {
			sprite.setGSpeed(xSpeed);
			sprite.setYSpeed((short) 0);
			return;
		}

		// ROM: addi.b #$20,d0 / andi.b #$40,d0 / bne.s loc_1AF68 (s2.asm:37585-37587)
		// Check for steep slope (angle near 0x40 or 0xC0 - walls)
		boolean isSteep = ((angle + 0x20) & 0x40) != 0;

		if (isSteep) {
			// ROM: loc_1AF68 - steep slope landing (s2.asm:37602-37612)
			// ROM: move.w #0,x_vel(a0) (s2.asm:37603)
			sprite.setXSpeed((short) 0);

			// ROM: cmpi.w #$FC0,y_vel(a0) / ble.s loc_1AF7C / move.w #$FC0,y_vel(a0) (s2.asm:37604-37606)
			// Cap ySpeed at 0xFC0 (prevents excessive speed gain on steep slopes)
			if (ySpeed > 0x0FC0) {
				ySpeed = 0x0FC0;
				sprite.setYSpeed(ySpeed);  // ROM writes back to y_vel
			}

			// ROM: loc_1AF7C - use ySpeed for gSpeed (s2.asm:37608-37612)
			// ROM: move.w y_vel(a0),inertia(a0) (s2.asm:37609)
			gSpeed = ySpeed;

			// ROM: tst.b d3 / bpl.s return / neg.w inertia(a0) (s2.asm:37610-37612)
			// If angle is in upper half (0x80-0xFF), negate gSpeed
			if ((angle & 0x80) != 0) {
				gSpeed = (short) -gSpeed;
			}
		} else {
			// ROM: addi.b #$10,d0 / andi.b #$20,d0 / beq.s loc_1AF5A (s2.asm:37588-37591)
			// Check for flat/shallow slope vs moderate slope
			boolean isFlat = ((angle + 0x10) & 0x20) == 0;

			if (isFlat) {
				// ROM: loc_1AF5A - flat slope landing (s2.asm:37596-37599)
				// ROM: move.w #0,y_vel(a0) (s2.asm:37597)
				// ROM: move.w x_vel(a0),inertia(a0) (s2.asm:37598)
				gSpeed = xSpeed;
				sprite.setYSpeed((short) 0);
			} else {
				// ROM: Moderate slope - halve ySpeed (s2.asm:37592-37593)
				// ROM: asr y_vel(a0) (s2.asm:37592) - modifies y_vel in memory
				ySpeed = (short) (ySpeed >> 1);
				sprite.setYSpeed(ySpeed);  // ROM writes back to y_vel

				// Then use the halved ySpeed (falls through to loc_1AF7C)
				// ROM: loc_1AF7C (s2.asm:37608-37612)
				gSpeed = ySpeed;

				// ROM: tst.b d3 / bpl.s return / neg.w inertia(a0) (s2.asm:37610-37612)
				if ((angle & 0x80) != 0) {
					gSpeed = (short) -gSpeed;
				}
			}
		}

		sprite.setGSpeed(gSpeed);
	}

	private boolean hasEnoughHeadroom(AbstractPlayableSprite sprite, int hexAngle) {
		int terrainDistance = getTerrainHeadroomDistance(sprite, hexAngle);
		var objectManager = uk.co.jamesj999.sonic.level.LevelManager.getInstance().getObjectManager();
		int objectDistance = (objectManager != null)
				? objectManager.getHeadroomDistance(sprite, hexAngle)
				: Integer.MAX_VALUE;
		return Math.min(terrainDistance, objectDistance) >= 6;
	}

	private int getTerrainHeadroomDistance(AbstractPlayableSprite sprite, int hexAngle) {
		int overheadAngle = (hexAngle + 0x80) & 0xFF;
		int quadrant = (overheadAngle + 0x20) & 0xC0;

		Sensor[] sensors;
		switch (quadrant) {
			case 0x00, 0x80 -> sensors = sprite.getCeilingSensors();
			case 0x40, 0xC0 -> sensors = sprite.getGroundSensors();
			default -> { return Integer.MAX_VALUE; }
		}

		if (sensors == null) {
			return Integer.MAX_VALUE;
		}

		int minDistance = Integer.MAX_VALUE;
		for (Sensor sensor : sensors) {
			boolean wasActive = sensor.isActive();
			sensor.setActive(true);
			SensorResult result = sensor.scan();
			sensor.setActive(wasActive);
			if (result != null) {
				int clearance = result.distance() >= 0 ? result.distance() : 0;
				if (clearance < minDistance) {
					minDistance = clearance;
				}
			}
		}
		return minDistance;
	}

	private int getHexAngle(AbstractPlayableSprite sprite) {
		return sprite.getAngle() & 0xFF;
	}

	private void calculateXYFromGSpeed(AbstractPlayableSprite sprite) {
		short gSpeed = sprite.getGSpeed();
		int hexAngle = getHexAngle(sprite);
		sprite.setXSpeed((short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8));
		sprite.setYSpeed((short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8));
	}

	private int calculateMovementQuadrant(short xSpeed, short ySpeed) {
		if (xSpeed == 0 && ySpeed == 0) {
			return 0x00;
		}
		double radians = Math.atan2(ySpeed, xSpeed);
		int moveAngle = (int) Math.round((radians / (2.0 * Math.PI)) * 256.0);
		if (moveAngle < 0) {
			moveAngle += 256;
		}
		moveAngle &= 0xFF;
		return ((moveAngle - 0x20) & 0xC0) & 0xFF;
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

	/**
	 * ROM: Ground mode calculation (s2.asm:42551-42576)
	 * The ROM uses complex rounding to prevent mode flickering at boundaries.
	 * It uses different offsets based on the sign of angle and (angle + 0x20):
	 * - When signs match: use (angle + 0x1F) & 0xC0
	 * - When signs differ: use (angle + 0x20) & 0xC0
	 *
	 * This creates the correct mode boundaries:
	 * - GROUND: 0xE0-0x20 (inclusive)
	 * - LEFTWALL: 0x21-0x5F
	 * - CEILING: 0x60-0xA0
	 * - RIGHTWALL: 0xA1-0xDF
	 */
	private void updateGroundMode(AbstractPlayableSprite sprite) {
		int angle = sprite.getAngle() & 0xFF;

		// ROM-accurate ground mode calculation (s2.asm:42551-42576)
		// The ROM uses +0x1F when signs match, +0x20 when signs differ
		boolean angleIsNegative = angle >= 0x80;
		int sumWith20 = (angle + 0x20) & 0xFF;
		boolean sumIsNegative = sumWith20 >= 0x80;

		int result;
		if (angleIsNegative == sumIsNegative) {
			// Signs match: use angle + 0x1F
			result = (angle + 0x1F) & 0xFF;
		} else {
			// Signs differ: use angle + 0x20
			result = sumWith20;
		}

		int modeBits = result & 0xC0;

		GroundMode newMode = switch (modeBits) {
			case 0x00 -> GroundMode.GROUND;
			case 0x40 -> GroundMode.LEFTWALL;
			case 0x80 -> GroundMode.CEILING;
			case 0xC0 -> GroundMode.RIGHTWALL;
			default -> sprite.getGroundMode();
		};

		if (newMode != sprite.getGroundMode()) {
			sprite.setGroundMode(newMode);
		}
	}
}
