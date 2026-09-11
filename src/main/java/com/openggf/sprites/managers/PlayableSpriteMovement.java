package com.openggf.sprites.managers;

import com.openggf.game.CanonicalAnimation;
import com.openggf.game.GameModule;
import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.LevelState;
import com.openggf.game.rules.CollisionRules;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PlayerAnimationRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.rules.PlayerLevelBoundaryRules;
import com.openggf.game.rules.PlayerMovementRules;
import com.openggf.game.rules.PowerUpRules;

import com.openggf.camera.Camera;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.FrameCollisionPlan;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.Sensor;
import com.openggf.physics.SensorResult;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.audio.AudioManager;
import com.openggf.audio.GameSound;
import com.openggf.level.objects.SkidDustObjectInstance;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.ShieldType;
import com.openggf.sprites.playable.SecondaryAbility;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationProfile;
import com.openggf.game.GroundMode;

import java.util.function.Consumer;

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
	private static final short YSPEED_LANDING_CAP = (short) 0xFC0;
	private static final int UPWARD_VELOCITY_CAP = -0xFC0;
	private static final short HYPER_DASH_SPEED = (short) 0x800;

	// Movement constants
	private static final int MOVE_LOCK_FRAMES = 0x1E;
	private static final int DEBUG_MOVE_SPEED = 3;
	// Controlled roll deceleration: derived per-frame from sprite.getRunDecel() >> 2
	// (s1:01 Sonic.asm:595-601 — rollDecel = decel/4 = $80/4 = $20)

	/**
	 * ROM {@code move.w #60,restartime(a0)} — the delay armed on the death row
	 * crossing when the level is going to restart (s1:01 Sonic.asm:2042,
	 * s2.asm:38281, sonic3k.asm:24583).
	 */
	private static final int DEATH_RESTART_DELAY_FRAMES = 60;

	private final CollisionSystem bootstrapCollisionSystem;
	private final AudioManager audioManager;
	private final GameStateManager bootstrapGameState;

	// Cached speed constants (don't change with speed shoes)
	private final short slopeRunning;
	private final short minStartRollSpeed;
	private final short minRollSpeed;
	private final short maxRoll;
	private final short slopeRollingUp;
	private final short slopeRollingDown;
	private final short rollDecel;

	// Input tracking
	private boolean jumpPressed;
	private boolean jumpPrevious;
	private boolean jumpReleasedSinceJump;
	private boolean testKeyPressed;

	// Current frame input state
	private boolean inputUp, inputDown, inputLeft, inputRight;
	private boolean inputJump, inputJumpPress;
	private boolean inputRawLeft, inputRawRight;
	private boolean tailsFlightVerticalUpdatedThisFrame;
	private boolean slopeResistAppliedThisFrame;
	private boolean directionalBrakeReachedZero;
	private boolean facingFlipForcesPushClearAfterGroundWall;
	private boolean deferredSpindashAnimationPushClear;
	private boolean wasCrouching;
	// ROM Sonic_Move/Tails_Move decide the standing-still duck/look-up/balance
	// animation from inertia BEFORE ground friction runs: the `tst.w inertia`
	// gate (S1 _incObj/01 Sonic.asm:373, S2 s2.asm:36568 Sonic / 39689 Tails,
	// S3K shares the convention) sits ahead of Obj01_UpdateSpeedOnGround's
	// friction (s2.asm:36768-36786). The engine applies friction inside
	// doGroundMove and then runs updateCrouchState, so the crouch decision must
	// observe this pre-friction inertia snapshot rather than the post-friction
	// g_speed. NO_PRE_FRICTION_SNAPSHOT marks "doGroundMove did not run this
	// frame" so updateCrouchState falls back to the live g_speed.
	private static final int NO_PRE_FRICTION_SNAPSHOT = Integer.MIN_VALUE;
	private int preFrictionGroundSpeed = NO_PRE_FRICTION_SNAPSHOT;
	// SonicKnux_Roll/Tails_Roll test inertia before the later SlopeRepel pass.
	// Preserve that exact value for the low-speed moving Duck write evaluated
	// after the engine's combined movement/collision pipeline has completed.
	private int preRollGroundSpeed = NO_PRE_FRICTION_SNAPSHOT;
	private boolean fixedSkidDustTickPending;
	private boolean processingFixedSkidDustTick;
	private boolean skidAnimationRefreshedThisFrame;
	private int lastFixedSkidDustTickFrame = Integer.MIN_VALUE;
	private int staleHorizontalInputRideSlotIndex;
	private int staleHorizontalInputSuppressFrames;
	private int staleHorizontalInputRideFrames;
	private boolean staleHorizontalInputPreviousHorizontal;
	private boolean preMoveBalanceEvaluated;
	private int preMoveBalanceState;
	private Direction preMoveBalanceDirection;
	/** ROM next_tilt/tilt bytes copied from Primary/Secondary_Angle after player dispatch. */
	private int latchedNextTilt;
	private int latchedTilt;

	public PlayableSpriteMovement(AbstractPlayableSprite sprite,
			CollisionSystem collisionSystem,
			GameStateManager gameState) {
		super(sprite);
		this.bootstrapCollisionSystem = collisionSystem;
		this.audioManager = sprite.currentAudioManager();
		this.bootstrapGameState = gameState;
		slopeRunning = sprite.getSlopeRunning();
		minStartRollSpeed = sprite.getMinStartRollSpeed();
		minRollSpeed = sprite.getMinRollSpeed();
		maxRoll = sprite.getMaxRoll();
		slopeRollingUp = sprite.getSlopeRollingUp();
		slopeRollingDown = sprite.getSlopeRollingDown();
		rollDecel = sprite.getRollDecel();
	}

	public PlayableSpriteMovement(AbstractPlayableSprite sprite) {
		this(sprite, sprite.currentCollisionSystemOrNull(), sprite.currentGameStateOrNull());
	}

	private PlayerMovementRules playerMovementRulesOrNull() {
		GameRules rules = sprite.getGameRules();
		if (rules != null && rules.playerMovement() != null) {
			return rules.playerMovement();
		}
		return null;
	}

	private PlayerAnimationRules playerAnimationRulesOrNull() {
		GameRules rules = sprite.getGameRules();
		if (rules != null && rules.playerAnimation() != null) {
			return rules.playerAnimation();
		}
		return null;
	}

	private PlayerCapabilityRules playerCapabilityRulesOrNull() {
		GameRules rules = sprite.getGameRules();
		if (rules != null && rules.playerCapability() != null) {
			return rules.playerCapability();
		}
		return null;
	}

	private CollisionRules collisionRulesOrNull() {
		GameRules rules = sprite.getGameRules();
		if (rules != null && rules.collision() != null) {
			return rules.collision();
		}
		return null;
	}

	private PowerUpRules powerUpRulesOrNull() {
		GameRules rules = sprite.getGameRules();
		if (rules != null && rules.powerUp() != null) {
			return rules.powerUp();
		}
		return null;
	}

	@Override
	public void resetGroundAngleLatches() {
		latchedNextTilt = 0;
		latchedTilt = 0;
	}

	@Override
	public void resetTransientState() {
		jumpPressed = false;
		jumpPrevious = false;
		jumpReleasedSinceJump = false;
		testKeyPressed = false;
		inputUp = false;
		inputDown = false;
		inputLeft = false;
		inputRight = false;
		inputJump = false;
		inputJumpPress = false;
		inputRawLeft = false;
		inputRawRight = false;
		slopeResistAppliedThisFrame = false;
		facingFlipForcesPushClearAfterGroundWall = false;
		wasCrouching = false;
		staleHorizontalInputRideSlotIndex = -1;
		staleHorizontalInputSuppressFrames = 0;
		staleHorizontalInputRideFrames = 0;
		staleHorizontalInputPreviousHorizontal = false;
		preMoveBalanceEvaluated = false;
		preMoveBalanceState = 0;
		preMoveBalanceDirection = null;
		latchedNextTilt = 0;
		latchedTilt = 0;
	}

	public RewindState captureRewindState() {
		return new RewindState(
				jumpPressed,
				jumpPrevious,
				jumpReleasedSinceJump,
				testKeyPressed,
				inputUp,
				inputDown,
				inputLeft,
				inputRight,
				inputJump,
				inputJumpPress,
				inputRawLeft,
				inputRawRight,
				facingFlipForcesPushClearAfterGroundWall,
				wasCrouching,
				staleHorizontalInputRideSlotIndex,
				staleHorizontalInputSuppressFrames,
				staleHorizontalInputRideFrames,
				staleHorizontalInputPreviousHorizontal,
				preMoveBalanceEvaluated,
				preMoveBalanceState,
				preMoveBalanceDirection,
				latchedNextTilt,
				latchedTilt);
	}

	public void restoreRewindState(RewindState state) {
		if (state == null) {
			resetTransientState();
			return;
		}
		jumpPressed = state.jumpPressed();
		jumpPrevious = state.jumpPrevious();
		jumpReleasedSinceJump = state.jumpReleasedSinceJump();
		testKeyPressed = state.testKeyPressed();
		inputUp = state.inputUp();
		inputDown = state.inputDown();
		inputLeft = state.inputLeft();
		inputRight = state.inputRight();
		inputJump = state.inputJump();
		inputJumpPress = state.inputJumpPress();
		inputRawLeft = state.inputRawLeft();
		inputRawRight = state.inputRawRight();
		facingFlipForcesPushClearAfterGroundWall = state.facingFlipForcesPushClearAfterGroundWall();
		wasCrouching = state.wasCrouching();
		staleHorizontalInputRideSlotIndex = state.staleHorizontalInputRideSlotIndex();
		staleHorizontalInputSuppressFrames = state.staleHorizontalInputSuppressFrames();
		staleHorizontalInputRideFrames = state.staleHorizontalInputRideFrames();
		staleHorizontalInputPreviousHorizontal = state.staleHorizontalInputPreviousHorizontal();
		preMoveBalanceEvaluated = state.preMoveBalanceEvaluated();
		preMoveBalanceState = state.preMoveBalanceState();
		preMoveBalanceDirection = state.preMoveBalanceDirection();
		latchedNextTilt = state.latchedNextTilt();
		latchedTilt = state.latchedTilt();
	}

	public record RewindState(
			boolean jumpPressed,
			boolean jumpPrevious,
			boolean jumpReleasedSinceJump,
			boolean testKeyPressed,
			boolean inputUp,
			boolean inputDown,
			boolean inputLeft,
			boolean inputRight,
			boolean inputJump,
			boolean inputJumpPress,
			boolean inputRawLeft,
			boolean inputRawRight,
			boolean facingFlipForcesPushClearAfterGroundWall,
			boolean wasCrouching,
			int staleHorizontalInputRideSlotIndex,
			int staleHorizontalInputSuppressFrames,
			int staleHorizontalInputRideFrames,
			boolean staleHorizontalInputPreviousHorizontal,
			boolean preMoveBalanceEvaluated,
			int preMoveBalanceState,
			Direction preMoveBalanceDirection,
			int latchedNextTilt,
			int latchedTilt
	) {}

	public void clearJumpHeightLatch() {
		jumpPressed = false;
		jumpReleasedSinceJump = false;
	}

	public void setJumpHeightLatch() {
		jumpPressed = true;
		jumpReleasedSinceJump = false;
	}

	private Camera camera() {
		try {
			return sprite.currentCamera();
		} catch (IllegalStateException e) {
			return null;
		}
	}

	private Camera playerCameraBiasController() {
		Camera camera = camera();
		// CPU sidekicks must not mutate the global vertical look bias. S3K
		// Sonic's Obj01_LookUpDown mutates (a5), the camera Y bias
		// (docs/skdisasm/sonic3k.asm:22615-22673), while CPU Tails'
		// ground/air routines do not run that reset/pan path
		// (docs/skdisasm/sonic3k.asm:25741-25746,25897-25937).
		return camera != null && !sprite.isCpuControlled() ? camera : null;
	}

	private LevelManager levelManager() {
		return sprite.currentLevelManager();
	}

	private CollisionSystem collisionSystem() {
		CollisionSystem current = sprite.currentCollisionSystemOrNull();
		return current != null ? current : bootstrapCollisionSystem;
	}

	private GameStateManager gameState() {
		GameStateManager current = sprite.currentGameStateOrNull();
		return current != null ? current : bootstrapGameState;
	}

	private boolean applyStaleRidingLogicalHorizontalInput(boolean left, boolean right) {
		boolean horizontal = left || right;
		ObjectInstance ridingObject = currentRidingSolidForStaleHorizontalInput();
		if (ridingObject == null || !(ridingObject instanceof SolidObjectProvider provider)) {
			staleHorizontalInputRideSlotIndex = -1;
			staleHorizontalInputSuppressFrames = 0;
			staleHorizontalInputRideFrames = 0;
			staleHorizontalInputPreviousHorizontal = horizontal;
			return false;
		}
		if (sprite.getGSpeed() != 0
				&& !provider.preservesStaleHorizontalInputEdgeWhileMoving(sprite)) {
			staleHorizontalInputRideSlotIndex = -1;
			staleHorizontalInputSuppressFrames = 0;
			staleHorizontalInputRideFrames = 0;
			staleHorizontalInputPreviousHorizontal = horizontal;
			return false;
		}
		int ridingSlotIndex = slotIndexForStaleHorizontalInput(ridingObject);
		if (ridingSlotIndex < 0 || ridingSlotIndex != staleHorizontalInputRideSlotIndex) {
			staleHorizontalInputRideSlotIndex = ridingSlotIndex;
			staleHorizontalInputSuppressFrames = 0;
			staleHorizontalInputRideFrames = 0;
			staleHorizontalInputPreviousHorizontal = false;
		}

		staleHorizontalInputRideFrames++;
		// Keep the held-horizontal edge latched while the rider remains on the
		// same object. S2 ObjD5/PlatformObjectD5 can zero inertia mid-ride, but
		// the next Obj01_Control still consumes the already-held Ctrl_1_Logical
		// right bit through Sonic_MoveRight (docs/s2disasm/s2.asm:35860-35874,
		// 36233-36243, 36560-36567, 36945-36962).
		if (sprite.getGSpeed() == 0
				&& horizontal
				&& !staleHorizontalInputPreviousHorizontal) {
			staleHorizontalInputSuppressFrames =
					provider.staleHorizontalLogicalInputFramesWhileRiding(sprite, staleHorizontalInputRideFrames, left, right);
		}
		staleHorizontalInputPreviousHorizontal = horizontal;

		if (!horizontal || staleHorizontalInputSuppressFrames <= 0) {
			return false;
		}
		staleHorizontalInputSuppressFrames--;
		return true;
	}

	private static int slotIndexForStaleHorizontalInput(ObjectInstance object) {
		return object instanceof AbstractObjectInstance aoi ? aoi.getSlotIndex() : -1;
	}

	private ObjectInstance currentRidingSolidForStaleHorizontalInput() {
		LevelManager manager = levelManager();
		var objectManager = manager != null ? manager.getObjectManager() : null;
		if (objectManager == null
				|| !objectManager.isRidingObject(sprite)
				|| sprite.getAir()) {
			return null;
		}
		return objectManager.getRidingObject(sprite);
	}

	/**
	 * Returns the spindash speed table from typed player capability rules,
	 * falling back to the static SPINDASH_SPEEDS constant.
	 * S3K Super/Hyper forms use a higher speed table (sonic3k.asm:23743 word_11D04).
	 * S2 Super Sonic uses SpindashSpeedsSuper (s2.asm:37305).
	 */
	private short[] getSpindashSpeedTable() {
		PlayerCapabilityRules rules = playerCapabilityRulesOrNull();
		if (sprite.isSuperSonic() && rules != null
				&& rules.superSpindashSpeedTable() != null) {
			return rules.superSpindashSpeedTable();
		}
		if (rules != null && rules.spindashSpeedTable() != null) {
			return rules.spindashSpeedTable();
		}
		return SPINDASH_SPEEDS;
	}

	@Override
	public void handleMovement(boolean up, boolean down, boolean left, boolean right, boolean jump, boolean testKey,
			boolean speedUp, boolean slowDown) {
		// Note: Raw input state for objects is now stored in SpriteManager BEFORE filtering,
		// so objects can query button state even when control is locked (ROM: obj_control).
		// The parameters here are already filtered by control lock state.

		// Reset per-tick slope-repel slip flag at the very start. Set by
		// doSlopeRepel when it slips the player into air this frame; consumed
		// by per-object hooks (e.g. CnzWireCageObjectInstance) that need to
		// distinguish "fresh slip honour the air state" from "stale move_lock
		// from an earlier slip".
		sprite.setSlopeRepelJustSlipped(false);
		tailsFlightVerticalUpdatedThisFrame = false;

		// Invalidate the pre-friction inertia snapshot at frame start; doGroundMove
		// repopulates it before updateCrouchState consumes it (see field comment).
		preFrictionGroundSpeed = NO_PRE_FRICTION_SNAPSHOT;
		slopeResistAppliedThisFrame = false;
		directionalBrakeReachedZero = false;
		skidAnimationRefreshedThisFrame = false;
		preRollGroundSpeed = NO_PRE_FRICTION_SNAPSHOT;
		sprite.getAnimationManager().clearGroundMovementAnimSpeed();
		sprite.clearDeferredGroundWallVelocityResponse();
		preMoveBalanceEvaluated = false;

		// Snapshot pre-physics state for per-object hooks running AFTER
		// physics in the engine's frame order. ROM order runs cage/object
		// updates AFTER player physics in slot order, but the cage's
		// capture decision (sonic3k.asm:69905-69921 loc_33922 → loc_3394C)
		// is based on the air/angle state ROM saw at the start of that
		// frame. Engine cage code reads these snapshots to mirror ROM's
		// branch selection.
		sprite.capturePrePhysicsSnapshot();

		if (sprite.isDebugMode()) {
			handleDebugMovement(up, down, left, right, speedUp, slowDown);
			applyScreenYWrapValueAfterControl();
			return;
		}

		// Player routine dispatch reaches Hurt/Dead independently of the normal
		// control routine's object_control gate. A late object-slot hurt can leave
		// positive object_control set until its owner runs on the following frame;
		// the recoil routine must still execute on that frame (S3K MGZ top carrier
		// into Obj_Spikes: Obj01_Hurt precedes sub_34EEC's release pass).
		if (sprite.isObjectControlSuppressesMovement()
				&& !sprite.isHurt()
				&& !sprite.getDead()) {
			applyScreenYWrapValueAfterControl();
			return;
		}

		handleTestKey(testKey);

		// ROM has two separate control mechanisms:
		// 1. move_lock (moveLockTimer) - blocks left/right only, NOT jumping
		// 2. obj_control bit 0 (controlLocked) - blocks ALL input including jumping
		//
		// The ControlLockTimer (slope slip) uses move_lock behavior in the ROM,
		// so it should only block left/right movement.
		boolean moveLocked = sprite.getMoveLockTimer() > 0;

		// Clear stale forced animation when move lock expires (e.g., glide landing crouch)
		if (!moveLocked && sprite.getForcedAnimationId() >= 0
				&& sprite.getDoubleJumpFlag() == 0 && !sprite.getAir()) {
			sprite.setForcedAnimationId(-1);
		}

		// obj_control bit 0 - when an object (flipper, etc.) has partial control
		// This blocks ALL input including jumping
		// ROM: Ctrl_2_locked only suppresses copying raw P2 input into Ctrl_2_logical
		// (sonic3k.asm:25692-25695). Tails CPU later writes Ctrl_2_logical itself
		// (sonic3k.asm:26775-26785), and Tails_InputAcceleration_Freespace consumes
		// those bits before MoveSprite_TestGravity (sonic3k.asm:27556-27559,
		// 28330-28401). Do not clear CPU-generated sidekick input here.
		// A native object may own Ctrl_*_logical while the hardware-input copy is
		// locked (for example loc_86334). An explicit forced mask is that semantic
		// ownership token: it lets only the already-filtered scripted buttons reach
		// normal movement, without reopening raw player input. Ordinary locked
		// players, which have no forced mask, remain fully immobile.
		boolean objControlLocked = controlLockBlocksScriptedMovement(sprite);

		inputRawLeft = left;
		inputRawRight = right;

		// ROM-accurate control lock behavior:
		// - move_lock only blocks input when GROUNDED (checked in Sonic_Move, not Sonic_ChgJumpDir)
		// - obj_control and hurt block input in ALL states
		//
		// move_lock is the ROM's ONLY grounded-input lock, and among the spring
		// family only the HORIZONTAL spring writes it: S2 loc_18B1C
		// `move.w #$F,move_lock(a1)` (docs/s2disasm/s2.asm:34031), S1
		// `move.w #15,locktime(a1)` (docs/s1disasm/_incObj/41 Springs.asm:144),
		// S3K loc_231BE `move.w #$F,$32(a1)` (docs/skdisasm/sonic3k.asm:47907).
		// The up, down and diagonal launches (S2 loc_189CA :33924-33966,
		// loc_18CC6 :34177-34196; S1 Spring_Up .bounceUp :88-101,
		// Spring_Down .bounceDown :193-200; S3K sub_22F98 :47700-47772) write no
		// lock of any kind, and neither do the S2 springboard Obj40 (:52262) or
		// the CPZ pipe-exit spring Obj7B (:56341). The engine's `springing`
		// timer is a marker those objects set for their own re-contact and carry
		// tests; making it also gate horizontal input invented a 15-frame
		// grounded control lock the ROM has nowhere. Each engine spring that
		// models a real move_lock already calls setMoveLockTimer alongside it,
		// so the horizontal case is unaffected.
		boolean groundedControlLock = !sprite.getAir() && moveLocked;
		if (groundedControlLock || objControlLocked || sprite.isHurt()) {
			if (!sprite.isForcedInputActive(AbstractPlayableSprite.INPUT_LEFT)) {
				left = false;
			}
			if (!sprite.isForceInputRight()) {
				right = false;
			}
		}
		// Block up/down when hurt
		if (sprite.isHurt()) {
			up = down = false;
		}
		// Block jumping ONLY when obj_control is set (not move_lock)
		// ROM: obj_control bit 0 skips the entire movement routine including Sonic_Jump
		// ROM: move_lock only blocks Sonic_Move/Sonic_RollSpeed, NOT Sonic_Jump
		if (objControlLocked && !sprite.isForcedInputActive(AbstractPlayableSprite.INPUT_JUMP)) {
			jump = false;
		}

		if (applyStaleRidingLogicalHorizontalInput(left, right)) {
			left = false;
			right = false;
		}

		facingFlipForcesPushClearAfterGroundWall = false;
		deferredSpindashAnimationPushClear = false;

		short originalX = sprite.getX();
		short originalY = sprite.getY();

		// ROM-accurate drowning pre-death: 120 frames of slow sinking before death
		// ROM ref: s2.asm:41729-41768 - addi.w #$10,y_vel; no terrain, no input
		if (sprite.isDrowningPreDeath()) {
			sprite.setYSpeed((short) (sprite.getYSpeed() + 0x10));
			if (sprite.tickDrownPreDeath()) {
				// Timer expired - transition to dead state (no upward bounce)
				sprite.setDead(true);
			}
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			sprite.updateSensors(originalX, originalY);
			applyScreenYWrapValueAfterControl();
			return;
		}

		if (sprite.getDead()) {
			SidekickCpuController cpu = sprite.getCpuController();
			if (cpu != null && cpu.applyDeferredGenericDeadDespawnIfCrossed()) {
				sprite.updateSensors(originalX, originalY);
				applyScreenYWrapValueAfterControl();
				return;
			}
			short oldYSpeed = applyDeathMovement();
			sprite.move(sprite.getXSpeed(), oldYSpeed);
			sprite.updateSensors(originalX, originalY);
			applyScreenYWrapValueAfterControl();
			return;
		}

		wasCrouching = sprite.getCrouching();
		sprite.setCrouching(false);

		storeInputState(up, down, left, right, jump);

		// ROM-accurate: Track whether effective movement input is active for animation.
		// This is the input state AFTER control lock/move lock filtering, used to determine
		// walk vs idle animation (ROM: Sonic_MoveLeft/MoveRight set walk anim when called).
		sprite.setMovementInputActive(inputLeft || inputRight);

		clearStaleCpuPushVelocityBeforeGroundMove();

		if (sprite.getSpringing()) {
			jumpPressed = false;
		}
		if (!sprite.getAir() && !inputJump) {
			jumpPressed = false;
		}

		// Recover transient desync where a concrete standing/riding solid contact
		// exists but air flag is stale. Do not treat a latched Status_OnObj owner
		// alone as grounding support: S3K Tails_Control dispatches from status
		// bits after only the object_control bit-0 gate (docs/skdisasm/sonic3k.asm:
		// 26210-26229), so CNZ cylinder release frames with both Status_InAir and
		// Status_OnObj set still run airborne movement before Obj_CNZCylinder's
		// Player_2 sub_324C0 pass (docs/skdisasm/sonic3k.asm:67656-67667).
		// Grounding those latch-only frames skips the ROM's air acceleration and
		// misses the x_sub boundary crossing seen at CNZ1 F4508.
		// S1 (UNIFIED): skip this recovery. The pre-movement solid pass is skipped for
		// S1, so riding state from the previous frame hasn't been cleaned up yet.
		// hasGroundingObjectSupport() would return true from stale riding data, incorrectly
		// forcing Sonic to ground mode. The post-movement solid pass (step 4) handles
		// the cleanup correctly for S1.
		if (!isUnifiedCollision() && sprite.getAir() && hasGroundingObjectSupport() && sprite.getYSpeed() >= 0) {
			sprite.setAir(false);
			sprite.setOnObject(true);
		}

		// ROM: Knuckles_Glide dispatch — intercepts normal mode when in glide states
		int glideState = sprite.getDoubleJumpFlag();
		if (glideState >= 3 && glideState <= 5
				&& sprite.getSecondaryAbility() == SecondaryAbility.GLIDE) {
			updateKnucklesGlide();
			// Direction is managed by glide code, not updateFacingDirection
			sprite.updateSensors(originalX, originalY);
			applyScreenYWrapValueAfterControl();
			return;
		}

		// Mode dispatch (ROM: Obj01_MdNormal_Checks)
		if (sprite.isHurt() && !sprite.getAir()) {
			// Obj01_Hurt_Normal performs ObjectMove before Sonic_HurtStop
			// clears routine 4 and zeroes velocities, even when an object
			// solid cleared Status_InAir on the prior frame (S2: s2.asm:
			// 37820-37834, 37848-37861). Do that final recoil move here,
			// then complete the hurt-stop recovery without entering normal
			// ground control this frame.
			doObjectMoveAndFall();
			if (sprite.isInWater()) {
				var modifiers = sprite.getPhysicsModifiers();
				short reduction = modifiers != null
						? modifiers.waterHurtGravityReduction()
						: 0x20;
				sprite.setYSpeed((short) (sprite.getYSpeed() - reduction));
			}
			sprite.completeHurtLandingRecovery();
			// Sonic_HurtStop's landing branch also writes the walk animation,
			// between zeroing the three speeds and dropping the routine: S1
			// `move.b #id_Walk,obAnim(a0)`
			// (docs/s1disasm/_incObj/"01 Sonic.asm":1949) and S2
			// `move.b #AniIDSonAni_Walk,anim(a0)` (docs/s2disasm/s2.asm:38223).
			// Without it the player stays in the hurt animation after touching
			// down and the hurt script keeps republishing fr_Injury, so any read
			// before normal control re-selects an animation sees the wrong frame.
			// It lives here rather than in completeHurtLandingRecovery because
			// AbstractPlayableSprite is at its release-critical size budget.
			int hurtLandingWalk = sprite.resolveAnimationId(CanonicalAnimation.WALK);
			if (hurtLandingWalk >= 0) {
				sprite.setAnimationId(hurtLandingWalk);
			}
		} else if (sprite.getAir()) {
			modeAirborne();
		} else if (sprite.getRolling()) {
			modeRoll();
		} else {
			modeNormal();
		}

		// ROM facing changes happen inside the movement branches themselves:
		// doChgJumpDir() for air, Sonic_Move/Tails_Move for normal ground,
		// and Sonic_RollLeft/Right for rolling. A generic post-pass flips
		// low-speed turn states too early and breaks Tails follow parity.
		applyScreenYWrapValueAfterControl();
		sprite.updateSensors(originalX, originalY);
	}

	private void applyScreenYWrapValueAfterControl() {
		SidekickCpuController cpu = sprite.getCpuController();
		if (sprite.isCpuControlled()
				&& sprite.getDead()
				&& cpu != null
				&& cpu.deadFallBypassesScreenYWrapValue()) {
			// S2 Obj02_Dead and the S3K Tails dead path run dead-fall movement directly,
			// without the normal Screen_Y_wrap_value mask used by control/hurt paths.
			return;
		}
		Camera camera = camera();
		if (camera != null) {
			camera.applyScreenYWrapValue(sprite);
		}
	}

	// ========================================
	// MODE METHODS
	// ========================================

	/**
	 * ROM S2 Obj01_MdNormal_Checks (s2.asm:36444-36468). Once the standing WAIT
	 * animation reaches the impatient foot-tap stage (anim_frame >= $1E), the
	 * entire grounded update (spindash/jump/move/roll/level bound/ObjectMove/
	 * AnglePos) is skipped every frame. A held direction or jump button first
	 * switches the animation to Blink -- or GetUp past the lying-down stage
	 * (anim_frame >= $AC) -- and control only resumes after that animation's
	 * $FD command switches back to walk. A fresh A/B/C press bypasses the whole
	 * check, so jumping out of deep wait responds instantly. Status_OnObj does
	 * not bypass these checks in the ROM; ordinary ridden solids therefore do
	 * not own or suppress the player's deep-wait transition. The gate is
	 * data-driven: only profiles that define a blink anim (S2 Sonic) engage it;
	 * S1 and S3K have no equivalent in their disassemblies.
	 */
	private boolean doWaitBlinkInterruptCheck() {
		if (!(sprite.getAnimationProfile()
				instanceof com.openggf.sprites.animation.ScriptedVelocityAnimationProfile velocityProfile)) {
			return false;
		}
		int blinkId = velocityProfile.getBlinkAnimId();
		if (blinkId < 0) {
			return false;
		}
		// ROM: move.b (Ctrl_1_Press_Logical).w,d0 / andi #ABC / bne Obj01_MdNormal
		if (inputJumpPress) {
			return false;
		}
		int anim = sprite.getAnimationId();
		int getUpId = velocityProfile.getGetUpAnimId();
		if (anim == blinkId || (getUpId >= 0 && anim == getUpId)) {
			return true; // cmpi Blink/GetUp -> Obj01_MdNormal_Skip
		}
		if (anim != velocityProfile.getIdleAnimId()) {
			return false;
		}
		if (sprite.getAnimationFrameIndex() < 0x1E) {
			return false; // cmpi.b #$1E,anim_frame / blo Obj01_MdNormal
		}
		// ROM: move.b (Ctrl_1_Held_Logical).w,d0 / andi #UDLR|ABC / beq Skip.
		// The riding-object stale-horizontal shim only delays Sonic_Move's
		// consumption of a fresh direction edge; it does not mutate the logical
		// control word already read here by Obj01_MdNormal_Checks. Use the
		// pre-shim horizontal sample so an ObjD5 rider can enter Blink immediately
		// while acceleration remains delayed until the object-order phase catches
		// up (S2 CNZ2: Blink at f6801, inertia begins at f6804).
		if (!(inputUp || inputDown || inputRawLeft || inputRawRight || inputJump)) {
			return true; // deep wait with nothing held still skips the frame
		}
		int next = (getUpId >= 0 && sprite.getAnimationFrameIndex() >= 0xAC) ? getUpId : blinkId;
		sprite.setAnimationId(next);
		return true;
	}


	/** Obj01_MdNormal: Ground walking state */
	private void modeNormal() {
		if (doWaitBlinkInterruptCheck()) return;

		short originalX = sprite.getX();
		short originalY = sprite.getY();
		// Sonic_Move/Tails_Move test move_lock before the ground-input and
		// balance/lookup tail. SlopeRepel decrements the live timer later in
		// this dispatch, so retain the entry decision for updateCrouchState.
		boolean moveLockActiveAtDispatch = sprite.getMoveLockTimer() > 0;

		if (doCheckSpindash()) return;
		if (inputJumpPress && doJump()) {
			// ROM: Sonic_Jump uses addq.l #4,sp to pop the return address,
			// skipping the rest of Obj01_MdNormal (SlopeResist, Move,
			// SpeedToPos, AnglePos, SlopeRepel). Air physics and position
			// update begin on the next frame when modeAirborne() runs.
			return;
		}

		doSlopeResist();
		// ROM MoveLeft/MoveRight observes inertia after Sonic_SlopeResist. A
		// slope can therefore carry inertia across zero on the same frame that
		// input flips Status_Facing and writes prev_anim=Run (S1
		// _incObj/01 Sonic.asm:283-291,634-659,704-723). Keep the restart test
		// at that same state boundary; checking before slope resistance misses
		// the facing flip even though doGroundMove subsequently applies it.
		updatePushingOnDirectionChange(inputLeft, inputRight);
		doGroundMove();
		preRollGroundSpeed = sprite.getGSpeed();
		doCheckStartRoll();
		doLevelBoundary();
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
		collisionSystem().applyDeferredGroundWallVelocityResponse(sprite);
		doAnglePosWithSensorUpdate(originalX, originalY);
		applyMissedDetachSlopeResist();
		doSlopeRepel();
		if (applyFatalBackgroundFloorOverlap()) return;
		collisionSystem().resolvePostMovementBackgroundWallClamp(
				FrameCollisionPlan.terrainOnly(), sprite);
		updateCrouchState(moveLockActiveAtDispatch);
	}

	static boolean controlLockBlocksScriptedMovement(AbstractPlayableSprite sprite) {
		return sprite.isControlLocked()
				&& !sprite.isCpuControlled()
				&& sprite.getForcedInputMask() == 0;
	}

	/** Obj01_MdRoll: Rolling on ground state */
	private void modeRoll() {
		short originalX = sprite.getX();
		short originalY = sprite.getY();

		if (inputJumpPress && !romPinballModeBlocksRollingJump() && doJump()) {
			// ROM: Sonic_Jump uses addq.l #4,sp to pop the return address,
			// skipping the rest of Obj01_MdRoll (RollRepel, RollSpeed,
			// SpeedToPos, AnglePos, SlopeRepel). Air physics and position
			// update begin on the next frame when modeAirborne() runs.
			return;
		}

		doRollRepel();
		doRollSpeed();
		doLevelBoundary();
		sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
		collisionSystem().applyDeferredGroundWallVelocityResponse(sprite);
		doAnglePosWithSensorUpdate(originalX, originalY);
		doSlopeRepel();
		if (applyFatalBackgroundFloorOverlap()) return;
		collisionSystem().resolvePostMovementBackgroundWallClamp(
				FrameCollisionPlan.terrainOnly(), sprite);
	}

	private boolean applyFatalBackgroundFloorOverlap() {
		if (!collisionSystem().hasFatalPostMovementBackgroundFloorOverlap(
				FrameCollisionPlan.terrainOnly(), sprite)) {
			return false;
		}
		SidekickCpuController cpuController = sprite.getCpuController();
		if (sprite.isCpuControlled() && cpuController != null) {
			cpuController.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);
		} else {
			sprite.applyCrushDeath();
		}
		return true;
	}

	private boolean romPinballModeBlocksRollingJump() {
		if (!sprite.getPinballMode()) {
			return false;
		}
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		if (movementRules != null
				&& movementRules.rollingJumpPinballGateRequiresSpindashFlag()
				&& !sprite.getSpindash()) {
			// S2 Obj85/Obj86 do not write pinball_mode; the engine uses
			// pinballMode there only to carry a temporary roll-preservation
			// guard. Clear it before Sonic_Jump/Tails_Jump so later airborne
			// pinball_mode tests also see the ROM byte as zero.
			sprite.setPinballMode(false);
			return false;
		}
		return true;
	}

	/** Obj01_MdAir/MdJump: Airborne state */
	private void modeAirborne() {


		short originalX = sprite.getX();
		short originalY = sprite.getY();

		SidekickCpuController sidekickCpu = sprite.getCpuController();
		if (sidekickCpu != null && sidekickCpu.isDeferredDespawnDeadFallContinuingThisFrame()) {
			// S2 Obj02_Dead (s2.asm:41131-41137) does not call Tails_LevelBound;
			// it only runs ObjectMoveAndFall before recording/displaying Tails.
			// Running the live airborne boundary path here clamps x_pos to
			// Tails_Min_X_pos+$10 when the falling corpse re-enters from the left.
			doObjectMoveAndFall();
			return;
		}

		// Knuckles glide states 1-2 use custom physics
		int glideState = sprite.getDoubleJumpFlag();
		boolean inGlide = (glideState == 1 || glideState == 2)
				&& sprite.getSecondaryAbility() == SecondaryAbility.GLIDE;

		if (inGlide && glideState == 1) {
			// Active glide — custom physics replace normal airborne.
			// ROM Knux_Glide_Freespace (sonic3k.asm:30675-30679): Move_Glide
			// (velocity), Player_LevelBound, MoveSprite2, then Knuckles_Glide
			// (collision + jump-release check).
			updateKnucklesGlide();  // Knuckles_Move_Glide (velocity only, flag stays 1)
			doLevelBoundary();
			if (isCpuLevelBoundaryKillActive()) {
				return;
			}
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());  // MoveSprite2
			// ROM: Knux_DoLevelCollision_CheckRet — custom collision for glide.
			// May transition to sliding (flag 3) / wall-climb (flag 4).
			doGlideCollision();
			// ROM Knuckles_Glide (sonic3k.asm:30708-30729): only if the collision
			// did NOT transition out of active glide, a released jump button
			// enters fall-from-glide. HitFloor/HitWall take precedence and branch
			// away before this button check.
			if (sprite.getDoubleJumpFlag() == 1 && !inputJump) {
				enterFallFromGlide();
			}
			return;
		}

		// Knuckles_Fall_From_Glide (double_jump_flag == 2). ROM dispatches this
		// via Knux_Glide_Freespace (sonic3k.asm:30675-30682): Knuckles_Move_Glide
		// is a no-op for flag != 1, then MoveSprite_TestGravity2 (== MoveSprite2
		// under normal gravity: move by the CURRENT velocity, NO gravity) runs
		// BEFORE Knuckles_Fall_From_Glide. Knuckles_Fall_From_Glide
		// (sonic3k.asm:30895-30943) then runs Knux_ChgJumpDir (air control),
		// applies +$38 gravity, and lands via collision. This move-then-control
		// ordering differs from Obj01_MdAir (which runs ChgJumpDir BEFORE the
		// move): using the post-ChgJumpDir velocity for the move biased the fall
		// by one +0x30 air-accel step per frame (~2.5px by the landing on the AIZ
		// giant-ride-vine approach). The hurt variant keeps the shared airborne
		// path below (its own recoil ordering).
		if (inGlide && glideState == 2 && !sprite.isHurt()) {
			doLevelBoundary();                    // Player_LevelBound
			if (isCpuLevelBoundaryKillActive()) {
				return;
			}
			// MoveSprite2 (no gravity): move by the pre-control velocity first.
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			doChgJumpDir();                       // Knux_ChgJumpDir (air control)
			applyGravity();                       // addi.w #$38,y_vel(a0)
			applyUnderwaterAirGravityReduction(); // btst Status_Underwater; subi #$28
			sprite.updateSensors(originalX, originalY);
			boolean wasAirBeforeFallCollision = sprite.getAir();
			doLevelCollision(sprite.isForceFloorCheck());
			if (wasAirBeforeFallCollision && !sprite.getAir()) {
				// Knuckles_Fall_From_Glide landing (sonic3k.asm:30913-30940):
				// zero ground_vel/x_vel/y_vel, play GlideLand, and on a flat
				// surface apply the 15-frame move_lock + crouch pose.
				sprite.setGSpeed((short) 0);
				sprite.setXSpeed((short) 0);
				sprite.setYSpeed((short) 0);
				audioManager.playSfx(GameSound.GLIDE_LAND);
				int hexAngle = sprite.getAngle() & 0xFF;
				int adjusted = (hexAngle + 0x20) & 0xC0;
				if (adjusted == 0) {
					sprite.setMoveLockTimer(0x0F);   // ROM: move.w #$F,move_lock(a0)
					sprite.setForcedAnimationId(0x23); // ROM: move.b #$23,anim(a0)
				}
			}
			return;
		}

		// ROM hurt routine (routine 4) has a DIFFERENT call order than
		// the normal airborne (Obj01_MdAir / Tails_Stand_Freespace) path:
		//   - Normal airborne: BOUNDARY check → MoveSprite → DoLevelCollision.
		//   - Hurt airborne:   MoveSprite → DoLevelCollision (HurtStop) → BOUNDARY.
		//
		// ROM cites:
		//   S3K Sonic loc_122D8 hurt routine (sonic3k.asm:24449-24467):
		//     jsr (MoveSprite_TestGravity2).l → addi.w #$30,y_vel → underwater
		//     subi.w #$20,y_vel → sub_12318 (HurtStop) → Player_LevelBound.
		//   S3K Tails loc_156D6 hurt routine (sonic3k.asm:29194-29209):
		//     jsr (MoveSprite_TestGravity2).l → addi.w #$30,y_vel → underwater
		//     subi.w #$20,y_vel → sub_15716 → Tails_Check_Screen_Boundaries.
		//   S2 Obj01_Hurt_Normal (s2.asm:37820-37834): jsr (ObjectMove).l →
		//     addi.w #$30,y_vel → underwater subi.w #$20,y_vel →
		//     Sonic_HurtStop → Sonic_LevelBound.
		//   S1 Sonic_Hurt (s1disasm/_incObj/01 Sonic.asm:1791-1804): jsr
		//     (SpeedToPos).l → addi.w #$30,obVelY → underwater subi.w #$20 →
		//     Sonic_HurtStop → Sonic_LevelBound.
		//
		// All three games agree on MOVE-then-BOUNDARY for hurt; the engine's
		// pre-existing BOUNDARY-then-MOVE order matched the normal airborne
		// path but produced an off-by-one frame on the right-edge clamp when
		// hurt knockback drives the sidekick into Camera_max_X_pos+$128
		// (AIZ Mini-boss F7552: ROM x=0x1208 vs engine x=0x1207).
		boolean hurt = sprite.isHurt();
		if (!hurt) {
			doJumpHeight();
			updateManualTailsFlight();
			if (!tailsFlightVerticalUpdatedThisFrame
					&& sidekickCpu != null
					&& sidekickCpu.usesFlyingCarryMovement()) {
				// Tails_FlyingSwimming calls Tails_Move_FlySwim before
				// Tails_InputAcceleration_Freespace. An apex step from
				// y_vel=-$08 to zero must precede its negative-y drag test.
				sidekickCpu.applyFlyingCarryVerticalVelocity();
				tailsFlightVerticalUpdatedThisFrame = true;
			}
			doChgJumpDir();
			doLevelBoundary();
		}
		if (!hurt && isCpuLevelBoundaryKillActive()) {
			// ROM Tails_Check_Screen_Boundaries reaches Kill_Character via
			// `jmp` (sonic3k.asm:28442-28443 loc_14F56). Kill_Character ends
			// with `rts` (sonic3k.asm:21158-21159), which unwinds to the
			// caller of Tails_Check_Screen_Boundaries — for the airborne
			// path that's `Tails_Stand_Freespace` (sonic3k.asm:27553), where
			// control resumes at `jsr (MoveSprite_TestGravity).l`
			// (sonic3k.asm:27559). MoveSprite_TestGravity falls through to
			// MoveSprite (sonic3k.asm:36032-36042) which applies gravity and
			// shifts y_pos by the freshly-written Kill_Character `y_vel = -$700`
			// (sonic3k.asm:21149). Tails_DoLevelCollision (sonic3k.asm:28871)
			// then runs and is the post-kill landing pass that produces the
			// trace's end-of-frame `(y, vels=0)` sample.
			//
			// AIZ F4679 (cp aiz1_intro_refresh_begin) trace: pre-kill
			// `(0x2D40, 0x0402, vels=(0x00F7, 0x0198))`, post-kill ROM
			// `(0x2D95, 0x040F, vels=0)`. Skipping doObjectMoveAndFall() and
			// going straight to collision left Tails 32 px below ROM because
			// the missing MoveSprite step prevents the y_vel=-$700 from
			// shifting Tails up before the collision sensors snap him to
			// ground.
			//
			// S2 deferred-despawn divergence: ROM Obj02_Dead
			// (s2.asm:40736-40742) runs ObjectMoveAndFall but NOT
			// Tails_DoLevelCollision on deferred-fall continuation frames
			// (kill frame N+1..N+threshold). The kill FRAME itself runs
			// through ROM Obj02_MdAir (s2.asm:39259-39274) which DOES end
			// in Tails_DoLevelCollision, so the collision pass remains
			// active there. SidekickCpuController flags continuation frames
			// via isDeferredDespawnDeadFallContinuingThisFrame(): when set,
			// the engine mirrors ROM by running only ObjectMoveAndFall.
			// MCZ trace F443 confirms: without this gate, the engine landed
			// dead Tails on the CollapsingPlatform below (y_speed snapped to
			// 0, position frozen), but ROM kept Tails falling through it.
			doObjectMoveAndFall();
			SidekickCpuController kc = sidekickCpu;
			boolean deferredContinuation =
					kc != null && kc.isDeferredDespawnDeadFallContinuingThisFrame();
			if (!deferredContinuation) {
				// Boundary-kill frame N still resumes inside Obj02_MdAir after
				// KillCharacter; S2 then applies the normal underwater gravity
				// reduction before Tails_DoLevelCollision (s2.asm:39616-39627).
				// Frame N+1 and later run Obj02_Dead, which only calls
				// ObjectMoveAndFall (s2.asm:41131-41137).
				applyUnderwaterAirGravityReduction();
				sprite.updateSensors(originalX, originalY);
				doLevelCollision(sprite.isForceFloorCheck());
			}
			return;
		}

		doObjectMoveAndFall();

		applyUnderwaterAirGravityReduction();

		// ROM: Sonic_JumpAngle runs in Obj01_MdAir / MdJump but NOT in
		// Obj01_Hurt (S1: 01 Sonic.asm:1410, S2: s2.asm:37806).
		// The hurt routine uses a separate code path that skips angle
		// return-to-zero, so the ground angle is preserved through recoil.
		if (!sprite.isHurt()) {
			sprite.returnAngleToZero();
			advanceAirborneFlipAngle();
		}
		sprite.updateSensors(originalX, originalY);
		boolean wasAirBeforeCollision = sprite.getAir();
		// Hurt routine 4 owns its terrain pass even if a positive object_control
		// bit remains set until a later object slot releases it. The normal
		// control routine alone is suppressed by that byte.
		// ROM Sonic_HurtStop runs its OWN bottom-boundary kill test before it
		// hands off to Sonic_Floor/DoLevelCollision, and returns without any
		// terrain pass when it fires (S1 01 Sonic.asm:1930-1941; S2
		// s2.asm:38200-38215; S3K sub_12318 sonic3k.asm:24477-24491). This is a
		// separate row from Sonic_LevelBound's kill plane below, which the hurt
		// routine reaches afterwards.
		if (hurt && applyHurtStopBottomKill()) {
			return;
		}
		if ((!sprite.isObjectControlSuppressesMovement() || hurt)
				&& !sprite.isSuppressAirCollision()) {
			doLevelCollision(sprite.isForceFloorCheck());
		}

		// Hurt airborne path runs the boundary check AFTER MoveSprite +
		// HurtStop (DoLevelCollision) — see ROM cites at the top of this
		// branch (Obj01_Hurt / loc_122D8 / loc_156D6 / Sonic_Hurt).
		// Predicted-x for the boundary clamp must include the post-move
		// position; doing the clamp pre-move loses one frame of lateral
		// motion against Camera_max_X_pos+$128, which manifested as a
		// 1-pixel right-edge gap during AIZ Mini-boss hurt knockback
		// (F7552: ROM x=0x1208 vs engine x=0x1207).
		if (hurt) {
			doLevelBoundary();
			if (isCpuLevelBoundaryKillActive()) {
				return;
			}
		}

		// ROM: Knuckles_Fall_From_Glide landing (sonic3k.asm:30913-30940).
		// When landing from fall-from-glide state (2), zero velocities,
		// play landing SFX, set move_lock, and show crouching pose.
		if (inGlide && glideState == 2 && wasAirBeforeCollision && !sprite.getAir()) {
			sprite.setGSpeed((short) 0);
			sprite.setXSpeed((short) 0);
			sprite.setYSpeed((short) 0);
			audioManager.playSfx(GameSound.GLIDE_LAND);
			int hexAngle = sprite.getAngle() & 0xFF;
			int adjusted = (hexAngle + 0x20) & 0xC0;
			if (adjusted == 0) {
				// Flat surface: crouch with move_lock
				sprite.setMoveLockTimer(0x0F);
				sprite.setForcedAnimationId(0x23);  // GLIDE_SLIDE (crouching frame)
			}
		}
	}

	/**
	 * ROM Player_JumpFlip: advances the object-published flip angle only in the
	 * normal airborne player routine, after JumpAngle and before level collision.
	 * Grounded spiral objects write the angle after the player's slot and must not
	 * have it advanced by the later animation pass.
	 */
	private void advanceAirborneFlipAngle() {
		int flipAngle = sprite.getFlipAngle();
		int flipSpeed = sprite.getFlipSpeed();
		if (flipAngle == 0 || flipSpeed == 0) {
			return;
		}

		// Native JumpFlip tests inertia (ground_vel) directly. x_vel is not a
		// fallback when inertia is zero.
		boolean movingLeft = sprite.getGSpeed() < 0;
		int flipsRemaining = sprite.getFlipsRemaining();
		if (!movingLeft || sprite.isFlipTurned()) {
			int newAngle = flipAngle + flipSpeed;
			if (newAngle > 0xFF) {
				flipsRemaining--;
				if (flipsRemaining < 0) {
					flipsRemaining = 0;
					newAngle = 0;
				} else {
					newAngle &= 0xFF;
				}
			}
			sprite.setFlipAngle(newAngle);
			sprite.setFlipsRemaining(flipsRemaining);
			return;
		}

		int newAngle = flipAngle - flipSpeed;
		if (newAngle < 0) {
			flipsRemaining--;
			if (flipsRemaining < 0) {
				flipsRemaining = 0;
				newAngle = 0;
			} else {
				newAngle = (newAngle + 0x100) & 0xFF;
			}
		}
		sprite.setFlipAngle(newAngle);
		sprite.setFlipsRemaining(flipsRemaining);
	}

	// ========================================
	// SPINDASH
	// ========================================

	/** Sonic_CheckSpindash: Check for spindash initiation (s2.asm:37206) */
	private boolean doCheckSpindash() {
		// Feature gate: skip entirely if spindash is disabled (e.g., Sonic 1)
		PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
		if (capabilityRules != null && !capabilityRules.spindashEnabled()) {
			return false;
		}

		if (sprite.getSpindash()) {
			return doUpdateSpindash();
		}

		int duckAnimId = getDuckAnimId();
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		// S3K SonicKnux_Roll/Tails_Roll writes Duck after the move_lock-gated
		// Move routine, so the following frame's CheckSpindash sees Duck before
		// Sonic_Jump (sonic3k.asm:22434,23223-23240). The engine deliberately
		// preserves the visible animation byte during move_lock, but its prior-frame
		// crouch state records that ROM-owned write and is the native gate here.
		boolean nativeMovingCrouch = movementRules != null
				&& movementRules.movingCrouchThreshold() > 0
				&& wasCrouching
				&& inputDown;
		if (duckAnimId < 0
				|| (sprite.getAnimationId() != duckAnimId && !nativeMovingCrouch)) {
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
			// ROM Obj01_Spindash_ResetScr exits Obj01_MdNormal after LevelBound +
			// AnglePos only. The actual rolling move starts next frame in Obj01_MdRoll.
			return true;
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
			// The rising rev pitch belongs to the sound driver, not here, and
			// both drivers already own it. S2 keeps zSpindashExtraFrequencyIndex
			// beside a 3Ch-frame zSpindashPlayingCounter, bumping the index on
			// each request while the previous one is still sounding, capping it
			// at 0Ch, and adding it to the track as a key offset
			// (docs/s2disasm/s2.sounddriver.asm:2152-2175, :2297-2304). S3K
			// carries the same ladder in the effect's own script through
			// smpsSpindashRev/cfSpindashRev, which transposes the track and
			// resets on smpsResetSpindashRev
			// (docs/skdisasm/Sound/SFX/AB - Spin Dash.asm:11-24).
			//
			// The engine additionally scaled playback rate by the player's
			// spindash charge counter, which is a physics value the drivers
			// never see, applied as a rate multiplier rather than a key offset.
			// That was a fabricated quantity stacked on top of a correct ROM
			// ladder, so the request goes out plain.
			audioManager.playSfx(GameSound.SPINDASH_CHARGE);
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
		sprite.setY((short) (sprite.getY() + sprite.getRollHeightAdjustment()));
		sprite.setSpindash(false);

		short[] table = getSpindashSpeedTable();
		int speedIndex = Math.min((sprite.getSpindashCounter() >> 8) & 0xFF, table.length - 1);
		short spindashGSpeed = table[speedIndex];

		Camera camera = camera();
		if (camera != null && camera.getFocusedSprite() == sprite) {
			// ROM spindash release writes H_scroll_frame_offset only; it does
			// not call Reset_Player_Position_Array. S2's own ScrollHoriz
			// comments describe the resulting old-position camera jerk
			// (docs/s2disasm/s2.asm:18044-18052), and S3K mirrors the same
			// release path (docs/skdisasm/sonic3k.asm:23715-23730).
			camera.setHorizScrollDelay(32 - ((spindashGSpeed - 0x800) >> 7));
		}

		if (Direction.LEFT.equals(sprite.getDirection())) {
			spindashGSpeed = (short) -spindashGSpeed;
		}
		sprite.setGSpeed(spindashGSpeed);

		short preReleaseXSpeed = sprite.getXSpeed();
		short preReleaseYSpeed = sprite.getYSpeed();
		short preReleaseCentreX = sprite.getCentreX();

		// Keep a temporary derived release velocity for the AnglePos threshold
		// check that still runs on the release frame.
		int hexAngle = sprite.getAngle() & 0xFF;
		sprite.setXSpeed((short) ((spindashGSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8));
		sprite.setYSpeed((short) ((spindashGSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8));

		sprite.setRolling(true);

		audioManager.playSfx(GameSound.SPINDASH_RELEASE);
		doLevelBoundaryAndAnglePos();
		if (!sprite.getAir()) {
			// ROM's release path runs LevelBound + AnglePos but not SpeedToPos;
			// the first rolling displacement happens on the next MdRoll frame.
			// Keep the temporary velocity from moving the player horizontally
			// through the engine's attachment pass.
			sprite.setCentreXPreserveSubpixel(preReleaseCentreX);
			sprite.setGSpeed(spindashGSpeed);
			sprite.setXSpeed(preReleaseXSpeed);
			sprite.setYSpeed(preReleaseYSpeed);
		}
	}

	// ========================================
	// JUMP
	// ========================================

	/** Sonic_Jump: Handle jump initiation (s2.asm:36996) */
	private boolean doJump() {
		int hexAngle = sprite.getAngle() & 0xFF;

		if (!collisionSystem().hasEnoughHeadroom(sprite, hexAngle)) {
			return false;
		}

		// ROM jump routines set Status_InAir and clear Status_Push, but leave
		// Status_OnObj and the object's standing bit for the next SolidObject pass
		// to resolve (S1 _incObj/01 Sonic.asm:1148-1150; S2 s2.asm:37056-37058,
		// 40031-40033; S3K sonic3k.asm:23328-23330, 28554-28556). Keeping the
		// riding record here lets S3K SolidObjectFull's offscreen Player_2 gate run
		// before the airborne riding unseat branch (sonic3k.asm:41006-41010).
		boolean wasRolling = sprite.getRolling();

		// Apply jump velocity based on terrain angle.
		// ROM: subi.b #$40,d0 / CalcSine / muls cos*jump>>8 add X / muls sin*jump>>8 add Y
		// Using (angle-0x40) with CalcSine matches the ROM's arithmetic shift rounding
		// for negative products. The previous approach of -cosHex(angle)*jump>>8 rounds
		// differently: -(x>>8) != (-x)>>8 when x%256 != 0 (68000 ASR rounds toward -∞).
		int adjustedAngle = (hexAngle - 0x40) & 0xFF;
		int xJumpChange = (TrigLookupTable.cosHex(adjustedAngle) * sprite.getJump()) >> 8;
		int yJumpChange = (TrigLookupTable.sinHex(adjustedAngle) * sprite.getJump()) >> 8;
		sprite.setXSpeed((short) (sprite.getXSpeed() + xJumpChange));
		sprite.setYSpeed((short) (sprite.getYSpeed() + yJumpChange));

		sprite.setAir(true);
		sprite.setPushing(false);
		sprite.setJumping(true);
		jumpPressed = true;
		jumpReleasedSinceJump = false;
		sprite.setStickToConvex(false);
		audioManager.playSfx(GameSound.JUMP);

		if (!wasRolling) {
			int preRollCentreY = sprite.getCentreY();
			sprite.applyRollingRadii(true);
			sprite.setRolling(true);
			// ROM Sonic_Jump/Tails_Jump adjusts centre y_pos by
			// default_y_radius - roll_y_radius (sonic3k.asm:28561-28577).
			// Use centre coordinates directly so preserved roll-sized dimensions
			// from marker/despawn paths do not double-count the height change.
			sprite.setCentreYPreserveSubpixel(
					(short) (preRollCentreY + sprite.getStandYRadius() - sprite.getRollYRadius()));
		} else {
			sprite.setRollingJump(true);
		}

		return true;
	}

	/** Sonic_JumpHeight: Jump release velocity cap (s2.asm:37076) */
	private void doJumpHeight() {
		// ROM gates the variable jump-height cap on the sprite's actual
		// jumping(a0) status byte, NOT on the held jump button:
		//   tst.b jumping(a0) / beq Sonic_UpVelCap
		// (docs/s2disasm/s2.asm:37411-37412 Sonic_JumpHeight;
		//  docs/s2disasm/s2.asm:40428-40429 Tails_JumpHeight;
		//  docs/s1disasm/_incObj/01 Sonic.asm:1197-1198;
		//  docs/skdisasm/sonic3k.asm:23366-23367 -- identical gate in all
		//  three games, so this is a universal correction, no per-game rule).
		// Previously this branched on the `jumpPressed` controller-loop latch.
		// That latch is set whenever a jump button is held (including the
		// A/B/C bits TailsCPU_Normal_FilterAction synthesizes into Ctrl_2
		// every ~64 frames, docs/s2disasm/s2.asm:39342-39370). A sidekick
		// launched upward by a CNZ flipper (Obj86 loc_2B290 sets in_air,
		// clears on_object, routine=2, obj_control=0, but never sets
		// jumping, docs/s2disasm/s2.asm:58366-58407) was therefore wrongly
		// receiving the -0x400 variable-height cap. ROM with jumping==0
		// takes Sonic_UpVelCap/Tails_UpVelCap instead (pinball bypass +
		// -0xFC0 cap, docs/s2disasm/s2.asm:37431-37436 / 40446-40451), so a
		// slower-than-0xFC0 flipper launch only receives gravity.
		if (sprite.isJumping()) {
			short ySpeedCap = sprite.isInWater() ? (short) 0x200 : (short) 0x400;
			if (sprite.getYSpeed() < -ySpeedCap && !inputJump) {
				sprite.setYSpeed((short) -ySpeedCap);
			}
			// Track jump release for shield ability detection
			if (!inputJump) {
				jumpReleasedSinceJump = true;
			}
			// Shield ability: re-press jump after release while airborne (docs/skdisasm/sonic3k.asm:23397).
			if (jumpReleasedSinceJump && inputJumpPress && isAirAbilityWindowOpen()) {
				if (sprite.getSecondaryAbility() == SecondaryAbility.FLY
						&& tryActivateSuperFromAirAbility()) {
					jumpReleasedSinceJump = false;
					inputJumpPress = false;
					return;
				}
				if (tryActivateTailsFlight()) {
					jumpReleasedSinceJump = false;
					inputJumpPress = false;
					return;
				}
				PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
				if (capabilityRules != null && capabilityRules.jumpRepressClearsRollJumpBeforeAbility()
						&& sprite.getSecondaryAbility() == SecondaryAbility.INSTA_SHIELD) {
					// ROM: S3K Sonic_ShieldMoves clears Status_RollJump before
					// testing Super, invincibility, elemental shields, or insta-shield
					// (docs/skdisasm/sonic3k.asm:23401-23413). S1/S2 Sonic_JumpHeight
					// has no equivalent branch (docs/s1disasm/_incObj/01 Sonic.asm:
					// 999-1025; docs/s2disasm/s2.asm:37067-37097).
					sprite.setRollingJump(false);
				}
				if (sprite.getSecondaryAbility() != SecondaryAbility.FLY
						&& tryActivateSuperFromAirAbility()) {
					jumpReleasedSinceJump = false;
					return;
				}
				if (tryShieldAbility()) {
					jumpReleasedSinceJump = false;
					return;
				}
			}
			if (!sprite.getAir() && !inputJump) {
				jumpPressed = false;
				jumpReleasedSinceJump = false;
			}
		} else {
			applyUpwardVelocityCap();
		}
	}

	private boolean isAirAbilityWindowOpen() {
		int threshold = sprite.isInWater() ? -0x200 : -0x400;
		return sprite.getDoubleJumpFlag() == 0 && sprite.getYSpeed() >= threshold;
	}

	private boolean tryActivateSuperFromAirAbility() {
		return sprite.getSuperStateController() != null
				&& sprite.getSuperStateController().activateFromAirAbility();
	}

	private boolean tryActivateTailsFlight() {
		PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
		if (capabilityRules == null
				|| !capabilityRules.tailsFlightEnabled()
				|| sprite.getSecondaryAbility() != SecondaryAbility.FLY
				|| sprite.getTailsFlightController() == null
				|| sprite.getTailsFlightController().isActive()) {
			return false;
		}
		SidekickCpuController cpu = sprite.getCpuController();
		if (sprite.isCpuControlled() && (cpu == null || !cpu.isUnderManualControl())) {
			return false;
		}
		sprite.getTailsFlightController().activate();
		return true;
	}

	private void updateManualTailsFlight() {
		if (!isManualTailsFlightActive()) {
			return;
		}
		boolean carryingMainCharacter = sprite.getTailsCarryController() != null
				&& sprite.getTailsCarryController().isCarryingMainCharacter();
		sprite.getTailsFlightController().updateVertical(
				inputJumpPress, carryingMainCharacter, romVisibleLevelFrameCounter());
		tailsFlightVerticalUpdatedThisFrame = true;
	}

	private boolean isManualTailsFlightActive() {
		PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
		if (capabilityRules == null
				|| !capabilityRules.tailsFlightEnabled()
				|| sprite.getSecondaryAbility() != SecondaryAbility.FLY
				|| sprite.getTailsFlightController() == null
				|| !sprite.getTailsFlightController().isActive()) {
			return false;
		}
		SidekickCpuController cpu = sprite.getCpuController();
		return !sprite.isCpuControlled() || (cpu != null && cpu.isUnderManualControl());
	}

	private int romVisibleLevelFrameCounter() {
		if (sprite.currentSpriteManagerOrNull() != null) {
			return sprite.currentGameplayFrameCounter();
		}
		LevelManager level = levelManager();
		return level != null ? level.getFrameCounter() : 0;
	}

	/**
	 * Sonic_ShieldMoves: Try to activate the player's shield ability (sonic3k.asm:23397-23479).
	 * @return true if an ability was activated (or suppressed by Super)
	 */
	private boolean tryShieldAbility() {
		PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
		if (capabilityRules == null) {
			return false;
		}
		// Tails_JumpHeight never enters Sonic_ShieldMoves. In particular it
		// must not clear Status_RollJump on a CPU-generated A/B/C re-press;
		// doing so exposes Sonic_ChgJumpDir air steering for a frame that the
		// ROM keeps roll-locked (sonic3k.asm:28593-28621 vs 23401-23413).
		if (sprite.getSecondaryAbility() == SecondaryAbility.FLY) {
			return false;
		}

		// ROM: Knuckles glide (Knux_Test_For_Glide, sonic3k.asm:32539-32586) is a
		// SEPARATE routine from Sonic_ShieldMoves. Unlike Sonic_FireShield it has
		// NO invincibility/shield suppression -- sonic3k.asm:23412-23413 (the
		// btst Status_Invincible gate) lives inside the Sonic-only path, so glide
		// activates on any qualifying jump re-press even while Knuckles is
		// star-invincible. Handle it before the Sonic shield/super/invincibility
		// gates below; the Super/emerald transform is handled upstream in
		// tryActivateSuperFromAirAbility (ROM Knux_Test_For_Glide loc_1785E).
		if (sprite.getSecondaryAbility() == SecondaryAbility.GLIDE) {
			activateGlide();
			return true;
		}
		boolean hasElemental = capabilityRules.elementalShieldsEnabled();
		boolean hasInsta = capabilityRules.instaShieldEnabled();
		if (!hasElemental && !hasInsta) {
			return false;
		}

		// ROM Sonic_ShieldMoves clears Status_RollJump before the shield,
		// super, and invincibility branches (sonic3k.asm:23402). That lets
		// the following Sonic_ChgJumpDir call apply same-frame air steering
		// after Bubble Shield sets x_vel to zero (AIZ trace F9661).
		sprite.setRollingJump(false);

		ShieldType shield = sprite.getShieldType();
		// ROM (sonic3k.asm:23404-23408): Super Sonic suppresses all abilities.
		// With all Super Emeralds this path becomes Sonic_HyperDash instead.
		if (sprite.isSuperSonic()) {
			if (isHyperSonic()) {
				hyperDash();
			}
			sprite.setDoubleJumpFlag(1);
			return true;
		}

		// ROM (sonic3k.asm:23412-23413): Invincibility suppresses all abilities
		if (sprite.getInvincibleFrames() > 0) {
			return false;
		}

		// ROM: Sonic_ShieldMoves is only reached by Sonic (character_id == 0).
		// Tails uses Tails_JumpHeight → flight. Knuckles uses Knux_JumpHeight → glide.
		// Neither Tails nor Knuckles ever reaches the shield ability code.
		if (sprite.getSecondaryAbility() == SecondaryAbility.INSTA_SHIELD) {
			// Sonic: elemental shield abilities OR insta-shield
			if (hasElemental && shield != null) {
				switch (shield) {
					case FIRE -> fireShieldDash();
					case LIGHTNING -> lightningShieldJump();
					case BUBBLE -> bubbleShieldBounce();
					default -> { return false; } // BASIC shield: no ability
				}
				sprite.setDoubleJumpFlag(1);
				return true;
			}
			if (hasInsta && shield == null) {
				activateInstaShield();
				return true;
			}
		}

		return false;
	}

	/** ROM: Sonic_InstaShield (sonic3k.asm:23473-23479) */
	private void activateInstaShield() {
		sprite.setDoubleJumpFlag(1);
		var instaShield = sprite.getInstaShieldObject();
		if (instaShield != null) {
			instaShield.triggerAttack();
		}
		audioManager.playSfx(GameSound.INSTA_SHIELD);
	}

	private boolean isHyperSonic() {
		GameStateManager state = sprite.currentGameStateOrNull();
		return state != null && state.hasAllSuperEmeralds();
	}

	/** ROM: Sonic_HyperDash (sonic3k.asm:23482-23523) */
	private void hyperDash() {
		int horizontal = 0;
		if (inputLeft && !inputRight) {
			horizontal = -1;
		} else if (inputRight && !inputLeft) {
			horizontal = 1;
		}

		int vertical = 0;
		if (inputUp && !inputDown) {
			vertical = -1;
		} else if (inputDown && !inputUp) {
			vertical = 1;
		}

		if (horizontal == 0 && vertical == 0) {
			horizontal = sprite.getDirection() == Direction.RIGHT ? 1 : -1;
		}

		short xVel = (short) (horizontal * HYPER_DASH_SPEED);
		short yVel = (short) (vertical * HYPER_DASH_SPEED);
		sprite.setXSpeed(xVel);
		sprite.setYSpeed(yVel);
		sprite.setGSpeed(xVel);
		sprite.resetPositionAndStatTableHistory();
		Camera camera = camera();
		if (camera != null && camera.getFocusedSprite() == sprite) {
			camera.setHorizScrollDelay(32);
		}
		audioManager.playSfx(GameSound.SPINDASH_RELEASE);
	}

	/** Fire dash: horizontal burst in facing direction (sonic3k.asm:23411-23430) */
	private void fireShieldDash() {
		int dir = sprite.getDirection() == Direction.RIGHT ? 1 : -1;
		short dashSpeed = (short) (0x800 * dir);
		sprite.setXSpeed(dashSpeed);
		// ROM (sonic3k.asm:23424-23426): the dash sets ground_vel alongside
		// x_vel so that ground_vel survives the next landing's `ground_vel =
		// x_vel` reseed and matches ROM diagnostics during the airborne dash
		// frame. Omitting this leaves the previous frame's ground_vel intact
		// (e.g. AIZ trace F7235 expected 0x0800, observed stale 0x0768).
		sprite.setGSpeed(dashSpeed);
		sprite.setYSpeed((short) 0);
		// ROM: Reset_Player_Position_Array (sonic3k.asm:23428) clears Pos_table
		// AND Stat_table - required for Tails_CPU_Control's delayed Stat_table
		// read at sonic3k.asm:26698-26700, then set H_scroll_frame_offset = $2000.
		sprite.resetPositionAndStatTableHistory();
		Camera camera = camera();
		if (camera != null && camera.getFocusedSprite() == sprite) {
			camera.setHorizScrollDelay(32);
		}
		audioManager.playSfx(GameSound.FIRE_ATTACK);
		var shield = sprite.getShieldObject();
		if (shield != null) shield.onAbilityActivated(1);
	}

	/** Lightning double jump: upward velocity boost (s3.asm:21094-21102) */
	private void lightningShieldJump() {
		sprite.setYSpeed((short) -0x580);
		// ROM Sonic_LightningShield clears jumping(a0) after writing y_vel
		// (docs/skdisasm/sonic3k.asm:23433-23440).  Without clearing the
		// engine latch, the next jump-button release re-applies the normal
		// jump-height cap and overwrites the double-jump velocity.
		sprite.setJumping(false);
		jumpPressed = false;
		jumpReleasedSinceJump = false;
		audioManager.playSfx(GameSound.LIGHTNING_ATTACK);
		var shield = sprite.getShieldObject();
		if (shield != null) shield.onAbilityActivated(1);
	}

	/** Bubble bounce: slam downward (s3.asm:21105-21114) */
	private void bubbleShieldBounce() {
		sprite.setXSpeed((short) 0);
		sprite.setGSpeed((short) 0);
		sprite.setYSpeed((short) 0x800);
		audioManager.playSfx(GameSound.BUBBLE_ATTACK);
		var shield = sprite.getShieldObject();
		if (shield != null) shield.onAbilityActivated(1);
	}

	// ========================================
	// KNUCKLES GLIDE / WALL CLIMB
	// ========================================

	/**
	 * ROM: Knux_Test_For_Glide (sonic3k.asm:32560-32586).
	 * Initiates glide from airborne state.
	 */
	private void activateGlide() {
		// ROM: Knux_Test_For_Glide (sonic3k.asm:32560-32566) writes y_radius/
		// x_radius directly and never touches y_pos -- y_pos is ROM's centre
		// coordinate and is unaffected by a radius change. This engine instead
		// derives centreY from a top-left yPixel plus a separate `height` field
		// (see AbstractSprite#getCentreY), and setRolling(false) below has the
		// side effect of resetting `height` to the STANDING value (runHeight)
		// when Knuckles was mid-roll-jump (the common case entering glide from
		// a jump). applyCustomRadii(10, 10) then overwrites the radii to the
		// glide dimensions but leaves that stale standing `height` in place, so
		// getCentreY() silently jumps by (runHeight - the prior roll height) / 2
		// before any velocity-driven movement even happens -- an engine-internal
		// bookkeeping artifact ROM has no equivalent of. Capture centreY here and
		// restore it (subpixel-preserving, matching a ROM move.w) after the
		// radius/height bookkeeping below so entering glide is a pure radius
		// change, matching ROM. Reproduced via a standalone replay of
		// traces/s3k/runs/s3-knux-multibonus-ss/aiz/: at trace frame 1562 this
		// stale-height jump alone accounted for +5px of centreY, compounding
		// with the correct +5px velocity-driven move into a +10px error that
		// desynced Knuckles' subsequent wall-glide-grab timing for the rest of
		// the segment.
		short preActivationCentreY = sprite.getCentreY();

		// Clear rolling state and set glide radii (0x0A x 0x0A)
		sprite.setRolling(false);
		sprite.setRollingJump(false);
		sprite.applyCustomRadii(10, 10);
		sprite.setCentreYPreserveSubpixel(preActivationCentreY);

		// Add 0x200 to y_vel, cap at 0 if negative result
		int newYVel = sprite.getYSpeed() + 0x200;
		if (newYVel < 0) {
			newYVel = 0;
		}
		sprite.setYSpeed((short) newYVel);

		// Set horizontal velocity: 0x400 in facing direction
		int xDir = (sprite.getDirection() == Direction.RIGHT) ? 1 : -1;
		sprite.setXSpeed((short) (0x400 * xDir));
		sprite.setGSpeed((short) 0x400);

		// Store initial direction in doubleJumpProperty
		// 0x00 = facing right, 0x80 (-128) = facing left
		byte dirProp = (sprite.getDirection() == Direction.RIGHT) ? (byte) 0 : (byte) -128;
		sprite.setDoubleJumpProperty(dirProp);

		sprite.setDoubleJumpFlag(1);
		sprite.setAngle((byte) 0);

		// Set glide animation — uses the standard walk anim slot
		// which Knuckles' animation table maps to glide frames
		setGlideAnimation();
	}

	/**
	 * ROM: Knuckles_Glide (sonic3k.asm:30687-30733).
	 * Called each frame while doubleJumpFlag >= 1 and in air.
	 * Handles the glide state machine dispatch.
	 */
	private void updateKnucklesGlide() {
		int state = sprite.getDoubleJumpFlag();
		switch (state) {
			case 1 -> updateGliding();
			case 2 -> {} // Falling from glide — normal airborne physics apply
			case 3 -> updateSliding();
			case 4 -> updateWallClimb();
			case 5 -> updateLedgeClimb();
		}
	}

	/**
	 * ROM: Knuckles_Move_Glide (sonic3k.asm:31598-31717).
	 * Active glide physics — acceleration, turning, gravity balance.
	 */
	private void updateGliding() {
		// ROM Knuckles_Move_Glide (sonic3k.asm:31598-31717) runs unconditionally
		// while gliding -- it does NOT test the jump button. The jump-release
		// check lives later, in Knuckles_Glide (sonic3k.asm:30708-30729), AFTER
		// MoveSprite2 and Knux_DoLevelCollision_CheckRet have already run. The
		// caller (modeAirborne) performs that post-move release check so the
		// release-frame move uses the full glide velocity (ROM), not the
		// prematurely /4'd fall velocity.

		// Accelerate glide speed
		int gSpeed = sprite.getGSpeed() & 0xFFFF;
		if (gSpeed < 0x400) {
			// Low speed: accelerate by 8
			gSpeed += 8;
		} else if (gSpeed < 0x1800) {
			// Medium speed: accelerate by 4 (only if not turning)
			byte prop = sprite.getDoubleJumpProperty();
			if ((prop & 0x7F) == 0) {
				gSpeed += 4;
			}
		}
		sprite.setGSpeed((short) gSpeed);

		// Handle turning based on left/right input
		byte prop = sprite.getDoubleJumpProperty();
		if (inputLeft) {
			if (prop != (byte) 0x80) {
				prop = (byte) (prop < 0 ? -prop : prop);  // make positive if negative
				prop = (byte) (prop + 2);
			}
		} else if (inputRight) {
			if (prop != 0) {
				prop = (byte) (prop > 0 ? -prop : prop);  // make negative if positive
				prop = (byte) (prop + 2);
			}
		} else {
			// No input: decay turn toward center
			int absProp = prop & 0x7F;
			if (absProp != 0) {
				prop = (byte) (prop + 2);
			}
		}
		sprite.setDoubleJumpProperty(prop);

		// Calculate x velocity from angle and ground speed
		// ROM: GetSineCosine then muls.w ground_vel, asr.l #8
		int angle = prop & 0xFF;
		int cosVal = TrigLookupTable.cosHex(angle);
		int xVel = (cosVal * gSpeed) >> 8;
		sprite.setXSpeed((short) xVel);

		// Gravity balance: converge y_vel toward ~0x80
		int yVel = sprite.getYSpeed();
		if (yVel >= 0x80) {
			// Falling fast: reduce by 0x20 (parachute effect)
			sprite.setYSpeed((short) (yVel - 0x20));
		} else {
			// Falling slow or rising: add gravity 0x20
			sprite.setYSpeed((short) (yVel + 0x20));
		}

		// Collision is checked after doLevelCollision() in modeAirborne
		setGlideAnimation();
	}

	/**
	 * ROM: sonic3k.asm:30712-30729. Player released jump button during glide.
	 * Transitions to fall state (doubleJumpFlag = 2).
	 */
	private void enterFallFromGlide() {
		sprite.setDoubleJumpFlag(2);

		// Face the direction of movement
		if (sprite.getXSpeed() >= 0) {
			sprite.setDirection(Direction.RIGHT);
		} else {
			sprite.setDirection(Direction.LEFT);
		}

		// Divide X velocity by 4
		sprite.setXSpeed((short) (sprite.getXSpeed() >> 2));

		// Restore default radii and release direct frame control
		sprite.restoreDefaultRadii();
		sprite.setObjectMappingFrameControl(false);

		// Set fall-from-glide animation (GLIDE_DROP = 0x21)
		sprite.setForcedAnimationId(0x21);
	}

	/**
	 * ROM: Knux_Gliding_HitFloor (sonic3k.asm:30736-30769).
	 * Called when ground sensors detect floor contact during glide.
	 */
	private void glideHitFloor() {
		// Face the direction of movement
		if (sprite.getXSpeed() >= 0) {
			sprite.setDirection(Direction.RIGHT);
		} else {
			sprite.setDirection(Direction.LEFT);
		}

		int hexAngle = sprite.getAngle() & 0xFF;
		int adjustedAngle = (hexAngle + 0x20) & 0xC0;

		if (adjustedAngle != 0) {
			// Non-flat surface: land normally
			sprite.setXSpeed((short) sprite.getGSpeed());
			sprite.setYSpeed((short) 0);
			sprite.restoreDefaultRadii();
			sprite.setObjectMappingFrameControl(false);
			sprite.setDoubleJumpFlag(0);
			sprite.setDoubleJumpProperty((byte) 0);
			sprite.setForcedAnimationId(-1);
			sprite.setAir(false);
			sprite.setJumping(false);
			return;
		}

		// Flat surface: ROM Knux_Gliding_HitFloor loc_1693E
		// (sonic3k.asm:30754-30769) sets double_jump_flag=3 and the sliding
		// mapping frame but NEVER clears Status_InAir -- Knux_TouchFloor is only
		// reached on the non-flat branch (sonic3k.asm:30751), so the slide runs
		// airborne-flagged and only lands via Knuckles_Sliding .getUp or
		// Knuckles_Fall_From_Glide. loc_1693E also plays no SFX here (it only
		// spawns dust clouds) and leaves x_vel/ground_vel and the 0x0A glide
		// radii untouched. Keeping Status_InAir set is required for the slide
		// then fall-from-glide velocity path to match the ROM.
		sprite.setDoubleJumpFlag(3);
		sprite.applyCustomRadii(10, 10);  // no-op: already the 0x0A glide radii
		sprite.setObjectMappingFrameControl(true);
		sprite.setMappingFrame(0xCC);  // ROM: move.b #$CC,mapping_frame(a0)
		sprite.setForcedAnimationId(-1);
	}

	/**
	 * ROM: Knuckles_Sliding (sonic3k.asm:30946-31014).
	 * Ground slide after glide — continues while jump button is held,
	 * decelerating x_vel by 0x20 per frame. Stops immediately when
	 * button released or velocity crosses zero.
	 */
	private void updateSliding() {
		// ROM Knux_Glide_Freespace (sonic3k.asm:30675-30679) runs
		// MoveSprite_TestGravity2 (== MoveSprite2 under normal gravity: move by
		// the CURRENT velocity, no gravity) BEFORE dispatching to
		// Knuckles_Sliding. The slide keeps y_vel = 0, so the move is purely
		// horizontal. Doing the move here -- BEFORE the 0x20 deceleration --
		// mirrors that order; the prior decelerate-then-move ordering used the
		// post-decel velocity and left the slide 0x20 subpixels short of the ROM
		// each frame.
		sprite.move(sprite.getXSpeed(), (short) 0);

		// ROM: Check if A/B/C button is held. If not → .getUp
		if (!inputJump) {
			slideGetUp();
			return;
		}

		// Decelerate x_vel by 0x20 toward zero
		int xVel = sprite.getXSpeed();
		if (xVel < 0) {
			// Going left: add 0x20
			xVel += 0x20;
			if (xVel >= 0) {
				// Velocity crossed zero → .getUp
				slideGetUp();
				return;
			}
		} else if (xVel > 0) {
			// Going right: subtract 0x20
			xVel -= 0x20;
			if (xVel <= 0) {
				// Velocity crossed zero → .getUp
				slideGetUp();
				return;
			}
		} else {
			// Already zero → .getUp
			slideGetUp();
			return;
		}

		sprite.setXSpeed((short) xVel);

		// ROM .continueSliding (sonic3k.asm:30992-31020):
		// Snap to floor. If floor distance >= 14,
		// Knuckles has slid off a ledge → enter fall state.
		// Probe floor distance and snap. ROM's sub_11FD6 (Sonic_CheckFloor) probes
		// both foot sensors, not just center -- see checkGlideFloorDist() javadoc.
		var floorResult = checkGlideFloorDist(
				sprite.getCentreX(), sprite.getCentreY(), sprite.getXRadius(), sprite.getYRadius());
		if (floorResult != null) {
			if (floorResult.distance() >= 14) {
				// Slid off a ledge — enter fall state
				sprite.setDoubleJumpFlag(2);
				sprite.restoreDefaultRadii();
				sprite.setObjectMappingFrameControl(false);
				sprite.setForcedAnimationId(0x21);  // GLIDE_DROP
				sprite.setAir(true);
				return;
			}
			// Snap to floor and update angle
			sprite.setY((short) (sprite.getY() + floorResult.distance()));
			sprite.setAngle(floorResult.angle());
		}
	}

	/**
	 * ROM: Knuckles_Sliding .getUp (sonic3k.asm:30969-30989).
	 * Exits sliding state — zeroes velocity, restores default radii,
	 * sets GLIDE_LAND animation, applies move_lock.
	 */
	private void slideGetUp() {
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		sprite.setYSpeed((short) 0);

		// Adjust Y position for radii change (current→default)
		int radiusDiff = sprite.getYRadius() - sprite.getStandYRadius();
		sprite.setY((short) (sprite.getY() + radiusDiff));

		sprite.restoreDefaultRadii();
		sprite.setObjectMappingFrameControl(false);
		sprite.setDoubleJumpFlag(0);
		sprite.setDoubleJumpProperty((byte) 0);

		// ROM: bsr.w Knux_TouchFloor (sonic3k.asm:30984). The slide itself runs
		// with Status_InAir still set — loc_1693E deliberately leaves it set when
		// the glide first touches ground — so .getUp is where Knuckles actually
		// becomes grounded. Knux_TouchFloor's loc_17B6A tail
		// (sonic3k.asm:32854-32864) clears Status_InAir, Status_Push and
		// Status_RollJump, and zeroes jumping, Chain_bonus_counter, flip_angle,
		// flip_type, flips_remaining, scroll_delay_counter and double_jump_flag.
		// Without the InAir clear the get-up frame stays airborne and the
		// following frames run the airborne control path, which move_lock does
		// not gate: a held direction then accelerates x_vel away from the ROM's
		// zero.
		// (This build is FixBugs = 0; the site carries no bug-fix conditional.)
		// double_jump_flag / double_jump_property are already zeroed above.
		// Status_RollJump, Chain_bonus_counter and scroll_delay_counter have no
		// modelled engine state at this site; flip_type and flips_remaining do.
		sprite.setAir(false);
		sprite.setPushing(false);
		sprite.setJumping(false);
		sprite.setFlipAngle(0);
		sprite.setFlipType(0);
		sprite.setFlipsRemaining(0);

		// ROM: move.w #$F,move_lock — 15-frame input lock
		sprite.setMoveLockTimer(0x0F);
		// ROM: move.b #$22,anim — GLIDE_LAND animation (brief get-up/crouch pose)
		sprite.setForcedAnimationId(0x22);
	}

	/**
	 * Clears any lingering glide animation state.
	 * Called when transitioning out of all glide states to normal gameplay.
	 */
	private void clearGlideAnimationState() {
		sprite.setObjectMappingFrameControl(false);
		sprite.setForcedAnimationId(-1);
		sprite.setDoubleJumpFlag(0);
		sprite.setDoubleJumpProperty((byte) 0);
	}

	/**
	 * ROM: Knuckles_Wall_Climb (sonic3k.asm:31074-31434).
	 * Wall climbing state after grabbing a wall during glide.
	 * Knuckles moves up/down on the wall with input, or jumps away.
	 * Animation cycles through frames 0xB7-0xBC every 4 frames of movement.
	 */
	private void updateWallClimb() {
		// Maintain X position against wall
		sprite.setX(sprite.getWallClimbX());

		int climbAnimDelta = 0;  // +1 = forward (climbing up), -1 = backward (climbing down)

		if (inputUp) {
			// Climbing up: check wall distance at the top to detect ledge
			boolean facingRight = sprite.getDirection() == Direction.RIGHT;
			int probeY = sprite.getCentreY() - 11;
			var wallResult = getWallDistance(probeY, facingRight);

			if (wallResult != null && wallResult.distance() >= 4) {
				// Wall gone above — climb up over ledge
				enterLedgeClimb();
				return;
			}

			if (wallResult != null && wallResult.distance() != 0) {
				// Small dip in wall — don't move
			} else {
				// Check ceiling clearance
				var ceilResult = ObjectTerrainUtils.checkCeilingDist(
						sprite.getCentreX(), sprite.getCentreY(), sprite.getYRadius());
				if (ceilResult != null && ceilResult.distance() < 0) {
					// Bumping ceiling — push out
					sprite.setY((short) (sprite.getY() - ceilResult.distance()));
				} else {
					sprite.setY((short) (sprite.getY() - 1));
				}
				climbAnimDelta = 1;
			}
		} else if (inputDown) {
			// Climbing down: check wall distance at the bottom
			boolean facingRight = sprite.getDirection() == Direction.RIGHT;
			int probeY = sprite.getCentreY() + 11;
			var wallResult = getWallDistance(probeY, facingRight);

			if (wallResult != null && wallResult.distance() != 0) {
				// Climbed off bottom of wall — let go
				letGoOfWall();
				return;
			}

			// Check floor clearance
			var floorResult = ObjectTerrainUtils.checkFloorDist(
					sprite.getCentreX(), sprite.getCentreY() + sprite.getYRadius());
			if (floorResult != null && floorResult.distance() <= 0) {
				// Reached floor
				sprite.setY((short) (sprite.getY() + floorResult.distance()));
				exitWallClimbToGround();
				return;
			}
			sprite.setY((short) (sprite.getY() + 1));
			climbAnimDelta = -1;
		}

		// ROM: Knuckles_Wall_Climb .finishMoving (sonic3k.asm:31330-31377).
		//
		// FixBugs conditional (docs/skdisasm/sonic3k.asm:38 -- the shipped ROM
		// assembles with FixBugs = 0, so THIS is the branch the engine implements).
		//
		//   Shipped (FixBugs = 0), implemented here: when neither up nor down is
		//   held, a floor probe runs through sub_F828 and its return value lands
		//   in d1 -- the same register that carries the climbing animation delta.
		//   The delta is destroyed, and the cycling code below adds the FLOOR
		//   DISTANCE to mapping_frame instead of +/-1. The distance is normally far
		//   larger than the $B7..$BC climb loop, so the `bls #$BC -> $B7` clamp
		//   fires and Knuckles snaps back to his first climbing frame every 4
		//   frames. The disassembly names that exact symptom at
		//   sonic3k.asm:31369-31373.
		//
		//   Fixed (FixBugs = 1), NOT implemented: `move.w d1,-(sp)` before the
		//   `bsr.w sub_F828` and `move.w d1,d0 / move.w (sp)+,d1` after it, so the
		//   floor distance is tested in d0 while d1 keeps its delta (0 in the
		//   no-input case). Knuckles would simply hold his current climbing frame.
		//
		// Both branches share the rest of the block: the probe is skipped entirely
		// while up or down is held (sonic3k.asm:31343-31346 -- similar code already
		// ran in those branches), and a negative probe result means Knuckles has
		// reached the floor and detaches (.reachedFloor).
		if (!inputUp && !inputDown) {
			// ROM probe point: x_pos, y_pos + 9, top_solid_bit (sonic3k.asm:31349-31352).
			int probeY = sprite.getCentreY() + 9;
			int floorDistance = romFloorProbeDistance(
					ObjectTerrainUtils.checkFloorDist(sprite.getCentreX(), probeY), probeY);
			if (floorDistance < 0) {
				// ROM .reachedFloor: add.w d1,y_pos, then detach to the ground.
				sprite.setY((short) (sprite.getY() + floorDistance));
				exitWallClimbToGround();
				return;
			}
			// The FixBugs = 0 clobber: d1 now holds the floor distance, not the delta.
			climbAnimDelta = floorDistance;
		}

		// ROM: Animation frame cycling (sonic3k.asm:31378-31403)
		// Animate every 4 frames when moving, using double_jump_property as timer
		if (climbAnimDelta != 0) {
			byte timer = (byte) (sprite.getDoubleJumpProperty() - 1);
			if (timer < 0) {
				timer = 3;
				// ROM: add.b mapping_frame(a0),d1 -- a BYTE add, so the sum wraps
				// mod 256 before the two unsigned loop compares (sonic3k.asm:31391-31401).
				int frame = (sprite.getMappingFrame() + climbAnimDelta) & 0xFF;
				// Wrap within range 0xB7-0xBC (cmpi.b/bhs then cmpi.b/bls)
				if (frame < 0xB7) frame = 0xBC;
				if (frame > 0xBC) frame = 0xB7;
				sprite.setMappingFrame(frame);
			}
			sprite.setDoubleJumpProperty(timer);
		}

		// ROM: Check for jump button to jump away (sonic3k.asm:31410-31434)
		if (inputJumpPress) {
			sprite.restoreDefaultRadii();
			sprite.setObjectMappingFrameControl(false);
			sprite.setDoubleJumpFlag(0);
			sprite.setDoubleJumpProperty((byte) 0);
			sprite.setForcedAnimationId(-1);

			// ROM: bchg #Status_Facing — flip direction (jump AWAY from wall)
			boolean wasFacingRight = sprite.getDirection() == Direction.RIGHT;
			sprite.setDirection(wasFacingRight ? Direction.LEFT : Direction.RIGHT);

			// ROM: y_vel = -$380, x_vel = $400 (toward new facing direction)
			int dir = wasFacingRight ? -1 : 1;
			sprite.setXSpeed((short) (0x400 * dir));
			sprite.setYSpeed((short) -0x380);
			sprite.setAir(true);
			sprite.setJumping(true);
			sprite.setRolling(true);
			sprite.applyRollingRadii(true);
			audioManager.playSfx(GameSound.JUMP);
			return;
		}
	}

	/**
	 * Maps an {@link ObjectTerrainUtils} floor probe onto the word the ROM's
	 * {@code FindFloor} chain actually returns in {@code d1}.
	 *
	 * <p>The engine reports "nothing solid in either probed tile" with the
	 * sentinel {@link TerrainCheckResult#NO_COLLISION}; the ROM has no such
	 * sentinel. Its empty-tile path is {@code loc_F274}
	 * ({@code add.w a3,d2 / bsr sub_F30C / addi.w #$10,d1}, sonic3k.asm:19273-19278)
	 * into {@code loc_F31C} ({@code move.w #$F,d1 / move.w d2,d0 / andi.w #$F,d0 /
	 * sub.w d0,d1}, sonic3k.asm:19306-19310), i.e. {@code $1F - (probeY & $F)} --
	 * a positive distance in $10..$1F. The exact value matters here because the
	 * FixBugs = 0 clobber feeds it straight into {@code add.b mapping_frame,d1}.
	 */
	private static int romFloorProbeDistance(TerrainCheckResult result, int probeY) {
		if (result == null || !result.foundSurface()) {
			return 0x1F - (probeY & 0x0F);
		}
		return result.distance();
	}

	/** Probes wall distance at a given Y position in the facing direction. */
	private com.openggf.physics.TerrainCheckResult getWallDistance(int probeY, boolean facingRight) {
		int probeX = facingRight
				? sprite.getCentreX() + sprite.getXRadius()
				: sprite.getCentreX() - sprite.getXRadius();
		return facingRight
				? ObjectTerrainUtils.checkRightWallDist(probeX, probeY)
				: ObjectTerrainUtils.checkLeftWallDist(probeX, probeY);
	}

	/** ROM: Knuckles_LetGoOfWall (sonic3k.asm:31449-31461) — drop off bottom of wall. */
	private void letGoOfWall() {
		sprite.setDoubleJumpFlag(2);
		sprite.restoreDefaultRadii();
		sprite.setObjectMappingFrameControl(false);
		sprite.setForcedAnimationId(0x21);  // GLIDE_DROP
	}

	/** Transition from wall climb to standing on ground (reached floor while climbing down). */
	private void exitWallClimbToGround() {
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		sprite.setYSpeed((short) 0);
		sprite.restoreDefaultRadii();
		sprite.setObjectMappingFrameControl(false);
		sprite.setDoubleJumpFlag(0);
		sprite.setDoubleJumpProperty((byte) 0);
		sprite.setForcedAnimationId(-1);
		sprite.setAir(false);
		sprite.setJumping(false);
	}

	/** ROM: Knuckles_ClimbUp (sonic3k.asm:31437-31446) — initiate ledge climb. */
	private void enterLedgeClimb() {
		sprite.setDoubleJumpFlag(5);
		sprite.setDoubleJumpProperty((byte) 0);
		doLedgeClimbAnimation();
	}

	/** ROM: Knuckles_ClimbLedge_Frames (sonic3k.asm:31503-31509) */
	private static final int[][] LEDGE_CLIMB_FRAMES = {
		// { mapping_frame, x_delta, y_delta, timer }
		{ 0xBD,  3,  -3, 6 },
		{ 0xBE,  8, -10, 6 },
		{ 0xBF, -8, -12, 6 },
		{ 0xD2,  8,  -5, 6 },
	};

	/**
	 * ROM: Knuckles_DoLedgeClimbingAnimation (sonic3k.asm:31467-31496).
	 * Advances through the ledge climb frame table one entry at a time.
	 */
	private void doLedgeClimbAnimation() {
		int index = (sprite.getDoubleJumpProperty() & 0xFF) / 4;
		if (index >= LEDGE_CLIMB_FRAMES.length) {
			// Animation complete — exit to standing
			exitWallClimbToGround();
			return;
		}

		int[] entry = LEDGE_CLIMB_FRAMES[index];
		sprite.setMappingFrame(entry[0]);

		// X delta is negated when facing left
		int xDelta = entry[1];
		if (sprite.getDirection() == Direction.LEFT) {
			xDelta = -xDelta;
		}
		sprite.setX((short) (sprite.getX() + xDelta));
		sprite.setY((short) (sprite.getY() + entry[2]));

		sprite.setDoubleJumpProperty((byte) (sprite.getDoubleJumpProperty() + 4));
	}

	/**
	 * ROM: Knuckles_Climb_Ledge (sonic3k.asm:31437).
	 * Called each frame while in state 5 — advances the ledge climb animation.
	 */
	private void updateLedgeClimb() {
		int index = (sprite.getDoubleJumpProperty() & 0xFF) / 4;
		if (index >= LEDGE_CLIMB_FRAMES.length) {
			exitWallClimbToGround();
			return;
		}
		doLedgeClimbAnimation();
	}

	/**
	 * ROM: Knux_DoLevelCollision_CheckRet (sonic3k.asm:32625-32684).
	 * Custom collision for glide state — probes walls and floor directly
	 * using ObjectTerrainUtils rather than the generic airborne collision.
	 */
	private void doGlideCollision() {
		int cx = sprite.getCentreX();
		int cy = sprite.getCentreY();
		int xRad = sprite.getXRadius();
		int yRad = sprite.getYRadius();

		// Check wall in movement direction
		// Save the movement direction BEFORE zeroing velocity (needed by glideHitWall)
		int xVel = sprite.getXSpeed();
		boolean movingRight = xVel >= 0;

		if (xVel > 0) {
			var result = ObjectTerrainUtils.checkRightWallDist(cx + xRad, cy);
			if (result != null && result.distance() < 0) {
				sprite.setX((short) (sprite.getX() + result.distance()));
				sprite.setXSpeed((short) 0);
				glideHitWall(movingRight);
				return;
			}
		} else if (xVel < 0) {
			var result = ObjectTerrainUtils.checkLeftWallDist(cx - xRad, cy);
			if (result != null && result.distance() < 0) {
				sprite.setX((short) (sprite.getX() - result.distance()));
				sprite.setXSpeed((short) 0);
				glideHitWall(movingRight);
				return;
			}
		}

		// Check floor (only when descending or level)
		if (sprite.getYSpeed() >= 0) {
			var result = checkGlideFloorDist(cx, cy, xRad, yRad);
			if (result != null && result.distance() < 0) {
				sprite.setY((short) (sprite.getY() + result.distance()));
				sprite.setAngle(result.angle());
				sprite.setYSpeed((short) 0);
				glideHitFloor();
				return;
			}
		}

		// Check opposite wall too (ROM checks both walls in some quadrants)
		if (xVel <= 0) {
			var result = ObjectTerrainUtils.checkRightWallDist(cx + xRad, cy);
			if (result != null && result.distance() < 0) {
				sprite.setX((short) (sprite.getX() + result.distance()));
			}
		}
		if (xVel >= 0) {
			var result = ObjectTerrainUtils.checkLeftWallDist(cx - xRad, cy);
			if (result != null && result.distance() < 0) {
				sprite.setX((short) (sprite.getX() - result.distance()));
			}
		}
	}

	/**
	 * ROM: Sonic_CheckFloor / {@code sub_11FD6} (sonic3k.asm:19839-19891,
	 * 24127-24135). Unlike {@link ObjectTerrainUtils}' single center-point
	 * object probes, the PLAYER floor check probes BOTH foot sensors --
	 * {@code x_pos + x_radius} ("Primary") and {@code x_pos - x_radius}
	 * ("Secondary") -- and keeps whichever found the closer floor (the
	 * smaller/more-negative distance): {@code cmp.w d0,d1; ble.s ...} picks
	 * the secondary (left) sensor's result unless the primary (right) sensor
	 * is strictly closer, in which case it swaps to that one. A center-only
	 * probe is blind to a floor edge under one foot but not the other (e.g. a
	 * staircase lip) and lands several frames late -- reproduced via a
	 * standalone replay of traces/s3k/runs/s3-knux-multibonus-ss/aiz/: at
	 * trace frame 1570 the right-foot sensor already reports floor contact
	 * (distance -4) while the center probe still reports clear air (distance
	 * +22), so {@link #doGlideCollision()}'s prior center-only check missed
	 * the landing for 6 more frames and the resulting position drift
	 * cascaded into a ~1800px trajectory divergence by the end of the
	 * segment.
	 */
	private TerrainCheckResult checkGlideFloorDist(int cx, int cy, int xRad, int yRad) {
		var right = ObjectTerrainUtils.checkFloorDist(cx + xRad, cy + yRad);
		var left = ObjectTerrainUtils.checkFloorDist(cx - xRad, cy + yRad);
		TerrainCheckResult chosen;
		if (left == null) {
			chosen = right;
		} else if (right == null) {
			chosen = left;
		} else {
			chosen = left.distance() <= right.distance() ? left : right;
		}
		if (chosen == null) {
			return null;
		}
		// ROM Sonic_CheckFloor loc_F7F0 (sonic3k.asm:19884-19888): after picking
		// the winning foot sensor, {@code btst #0,d3; beq locret; move.b d2,d3}
		// forces the returned angle to 0 whenever the tile's stored angle byte is
		// odd (bit 0 set). AIZ's flagged/curved landing tile stores angle 0xFF
		// (odd), so the ROM reads floor angle 0x00 there; without this rule the
		// glide land wrote angle 0xFF and the compared field diverged.
		if ((chosen.angle() & 1) != 0) {
			chosen = new TerrainCheckResult(chosen.distance(), (byte) 0, chosen.tileIndex());
		}
		return chosen;
	}

	/**
	 * ROM: Knuckles_Gliding_HitWall (sonic3k.asm:30772-30827).
	 * Transitions to wall climb state when hitting a wall during glide.
	 * @param wasMovingRight the movement direction at the time of wall contact
	 *                       (before x velocity was zeroed by collision)
	 */
	private void glideHitWall(boolean wasMovingRight) {
		// Face toward the wall (use saved direction since xSpeed is already zeroed)
		sprite.setDirection(wasMovingRight ? Direction.RIGHT : Direction.LEFT);

		audioManager.playSfx(GameSound.GRAB);

		// Zero all velocities
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		sprite.setYSpeed((short) 0);

		// Enter wall climb state
		sprite.setDoubleJumpFlag(4);
		sprite.setDoubleJumpProperty((byte) 3);  // ROM: double_jump_property = 3

		// Record wall X position
		sprite.setWallClimbX(sprite.getX());

		// Wall climb animation — mapping frame 0xB7
		sprite.setForcedAnimationId(-1);  // Let object mapping frame control take over
		sprite.setObjectMappingFrameControl(true);
		sprite.setMappingFrame(0xB7);
	}

	// Wall climb boundary checking is now integrated into updateWallClimb()
	// using ObjectTerrainUtils for wall/floor/ceiling probing.

	/** ROM: RawAni_Knuckles_GlideTurn (sonic3k.asm:31584-31593) */
	private static final int[] GLIDE_TURN_FRAMES = {
		0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC3, 0xC2, 0xC1
	};

	/**
	 * ROM: Knuckles_Set_Gliding_Animation (sonic3k.asm:31560-31581).
	 * Sets {@code anim(a0)} to $20 (sonic3k.asm:31563: {@code move.w
	 * #($20<<8)|$20,anim(a0) ; and prev_anim}) THEN sets mapping_frame directly
	 * from a lookup table based on glide turn angle -- the mapping_frame write
	 * bypasses the scripted animation system, but the {@code anim} byte itself
	 * is still written and is what a trace's {@code player_animation_id} field
	 * observes.
	 */
	private void setGlideAnimation() {
		// ROM sonic3k.asm:31563. Word write also sets prev_anim(a0), which this
		// engine does not model as a separate field. Must go through
		// setForcedAnimationId, not setAnimationId directly: PlayableSpriteAnimation
		// .update() recomputes animationId from the scripted velocity resolver every
		// frame BEFORE consulting isObjectMappingFrameControl(), so a plain
		// setAnimationId() here is stomped back to the resolver's idea (0) on the
		// very next animation-manager pass. forcedAnimationId is the established
		// override channel (see enterFallFromGlide()/clearGlideAnimationState()).
		sprite.setForcedAnimationId(0x20);
		// Enable direct mapping frame control (bypasses animation manager)
		sprite.setObjectMappingFrameControl(true);
		sprite.setPushing(false);

		// ROM: bclr #Status_Facing — always face RIGHT during glide
		sprite.setDirection(Direction.RIGHT);

		// Calculate frame index from double_jump_property:
		// ROM: moveq #0,d0; move.b double_jump_property,d0; addi.b #$10,d0; lsr.w #5,d0
		int prop = sprite.getDoubleJumpProperty() & 0xFF;
		int index = ((prop + 0x10) & 0xFF) >> 5;
		if (index >= GLIDE_TURN_FRAMES.length) {
			index = 0;
		}

		int frame = GLIDE_TURN_FRAMES[index];

		// ROM: If frame == $C4, set facing LEFT and use $C0 with h-flip instead
		if (frame == 0xC4) {
			sprite.setDirection(Direction.LEFT);
			frame = 0xC0;
		}

		sprite.setMappingFrame(frame);
	}

	// ========================================
	// GROUND MOVEMENT
	// ========================================

	/** Sonic_SlopeResist: Apply slope factor when walking (s2.asm:37360) */
	private void doSlopeResist() {
		applySlopeResistForAngle(sprite.getAngle() & 0xFF, false);
	}

	private boolean applySlopeResistForAngle(int hexAngle, boolean moveByVelocityDelta) {
		short gSpeed = sprite.getGSpeed();

		if (isOnSteepSurface(hexAngle)) {
			return false;
		}

		int slopeEffect = (slopeRunning * TrigLookupTable.sinHex(hexAngle)) >> 8;
		if (gSpeed == 0) {
			// S1/S2 ROM Sonic_SlopeResist (s1disasm/_incObj/01 Sonic.asm:1243-1244,
			// s2.asm:37394-37395) returns unconditionally on
			// `tst.w inertia(a0) / beq.s return_1ADCA` when stationary.
			// S3K Player_SlopeResist (sonic3k.asm:23830-23856) instead branches
			// to loc_11DDC on inertia=0 and applies the force when |force| >= $D,
			// kicking the stationary player into motion on a steep enough slope.
			PlayerMovementRules movementRules = playerMovementRulesOrNull();
			boolean s3kKickAtRest = movementRules != null && movementRules.slopeResistAppliesAtZeroInertia();
			if (!s3kKickAtRest || Math.abs(slopeEffect) < 0x0D) {
				return false;
			}
		}
		short adjustedGSpeed = (short) (gSpeed + slopeEffect);
		if (moveByVelocityDelta) {
			short oldXSpeed = (short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
			short oldYSpeed = (short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8);
			short newXSpeed = (short) ((adjustedGSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
			short newYSpeed = (short) ((adjustedGSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8);
			sprite.move((short) (newXSpeed - oldXSpeed), (short) (newYSpeed - oldYSpeed));
			sprite.setXSpeed(newXSpeed);
			sprite.setYSpeed(newYSpeed);
		}
		sprite.setGSpeed(adjustedGSpeed);
		slopeResistAppliedThisFrame = true;
		return true;
	}

	private void applyMissedDetachSlopeResist() {
		if (!sprite.getAir()
				|| slopeResistAppliedThisFrame
				|| sprite.wasPrePhysicsAir()) {
			return;
		}
		int prePhysicsAngle = sprite.getPrePhysicsAngle() & 0xFF;
		if (prePhysicsAngle == (sprite.getAngle() & 0xFF)) {
			return;
		}
		// ROM's walking slope-resist routine runs BEFORE Sonic_Move with the
		// FRAME-START inertia, and `tst.w inertia / beq return` skips the
		// resist entirely when the player was stationary at frame start
		// (S1 Sonic_SlopeResistWalk 01 Sonic.asm:1308-1309; S2 Sonic_SlopeResist
		// s2.asm:37718-37719 / Tails_SlopeResist s2.asm:40620-40621; S3K
		// Player_SlopeResist sonic3k.asm:23830-23831). This replay must observe
		// that same frame-start inertia — using the post-Move inertia here would
		// inject a slope resist ROM never applied (SYZ1 f4431: ROM lands with
		// inertia 0, holds Right, Sonic_Move sets inertia +$C and AngleSpeed
		// yields y_vel=-$A; SlopeRepel then detaches without touching velocity,
		// so the ROM-recorded y_vel stays -$A). The S3K at-rest slope kick
		// (|force| >= $D at inertia 0, sonic3k.asm:23847-23856) is owned by
		// doSlopeResist, which sets slopeResistAppliedThisFrame and short-circuits
		// this path, so the zero-inertia skip below is safe for all three games.
		if (sprite.getPrePhysicsGSpeed() == 0) {
			return;
		}
		// S1/S2/S3K run walking slope resist before SpeedToPos/MoveSprite2 and
		// before AnglePos can detach the player (S1 01 Sonic.asm:283-291,
		// 1246-1263; S2 s2.asm:36464-36477,37703-37723; S3K
		// sonic3k.asm:21620-21630,23821-23856). If engine ground attachment
		// has already switched to air on this final grounded frame, replay the
		// missing pre-move slope step using the frame-start angle and add only
		// the velocity delta that ROM would have moved with this frame.
		applySlopeResistForAngle(prePhysicsAngle, true);
	}

	/** Sonic_Move: Ground input handling, accel/decel, wall collision (s2.asm:36220) */
	private void doGroundMove() {
		short gSpeed = sprite.getGSpeed();

		// ROM: _btst #status_secondary.sliding,status_secondary(a0) / _bne.w Obj01_Traction
		// (s2.asm:36224-36225, s1.asm:309-310)
		// When sliding (water slides, wind tunnels), skip ALL input processing and
		// friction — go straight to velocity conversion and wall collision.
		if (sprite.isSliding()) {
			sprite.setGSpeed(gSpeed);
			calculateXYFromGSpeed();
			collisionSystem().resolveGroundWallCollision(FrameCollisionPlan.terrainOnly(), sprite);
			return;
		}

		short runAccel = sprite.getRunAccel();
		short runDecel = sprite.getRunDecel();
		short friction = sprite.getFriction();
		short max = sprite.getMax();
		Camera camera = playerCameraBiasController();

		// Move lock - skip input processing but still apply friction
		// ROM: When move_lock is active, branches to Obj01_ResetScr which continues
		// to friction check. It does NOT return early and skip friction.
		boolean moveLockActive = sprite.getMoveLockTimer() > 0;
		boolean directionalHelperPublishedAnimation = false;

		if (moveLockActive) {
			// Sonic_Move tests locktime before any direction or animation write,
			// then Sonic_SlopeRepel decrements it later in the grounded routine.
			// Retain that dispatch decision when the final tick reaches zero so
			// the later animation pass cannot synthesize a state the ROM skipped.
			sprite.getAnimationManager().suppressGroundMovementAnimationForFrame();
			// Camera easing during move lock
			if (camera != null) camera.easeYBiasToDefault();
		} else {
			// Left input (only when move_lock is not active)
			if (inputLeft) {
				if (gSpeed > 0) {
					gSpeed -= runDecel;
					if (gSpeed < 0) gSpeed = (short) -128;
					directionalBrakeReachedZero = gSpeed == 0;
					if (directionalBrakeReachedZero) {
						// MoveLeft has returned to the enclosing standing tail,
						// which writes Wait/Balance after the Stop threshold test.
						// The engine's skid flag represents that threshold branch,
						// so it must not outlive an exact-zero deceleration.
						sprite.setSkidding(false);
					}
					if (shouldTriggerGroundSkid(gSpeed, false)) {
						sprite.setDirection(Direction.RIGHT);
						handleSkid();
						directionalHelperPublishedAnimation = true;
					} else if (sprite.getSkidding()) {
						advanceSkidDustTimer();
					}
				} else {
					sprite.setSkidding(false);
					sprite.setDirection(Direction.LEFT);
					gSpeed = accelerateLeft(gSpeed, runAccel, max);
					directionalHelperPublishedAnimation = true;
				}
			}

			// Right input (only when move_lock is not active)
			if (inputRight) {
				if (gSpeed < 0) {
					gSpeed += runDecel;
					// ROM: add.w d4,d0 / bcc.s ... / move.w #$80,d0
					// 68000 carry flag is SET when the unsigned add overflows (e.g.
					// 0xFF80+0x80=0x10000), which happens when the result is zero
					// or positive. BCC (branch if carry clear) is NOT taken, so
					// the reset to 0x80 executes for both gSpeed==0 and gSpeed>0.
					if (gSpeed >= 0) gSpeed = (short) 128;
					if (shouldTriggerGroundSkid(gSpeed, true)) {
						sprite.setDirection(Direction.LEFT);
						handleSkid();
						directionalHelperPublishedAnimation = true;
					} else if (sprite.getSkidding()) {
						advanceSkidDustTimer();
					}
				} else {
					sprite.setSkidding(false);
					sprite.setDirection(Direction.RIGHT);
					gSpeed = accelerateRight(gSpeed, runAccel, max);
					directionalHelperPublishedAnimation = true;
				}
			}

			if (!inputLeft && !inputRight) {
				sprite.setSkidding(false);
			}
			if ((inputLeft || inputRight) && !directionalHelperPublishedAnimation
					&& gSpeed != 0) {
				// The opposite-direction deceleration tail returns without writing
				// anim, including the frame it carries inertia across zero.
				sprite.getAnimationManager().suppressGroundMovementAnimationForFrame();
			}

			// Standing still handling (ROM: Sonic_Lookup, Sonic_Duck, Obj01_ResetScr)
			// S1: no delay - camera pans immediately (s1.asm: Sonic_LookUp/Sonic_Duck)
			// S2/S3K: 120-frame (2 second) delay before panning (s2.asm:36402-36405)
			//
			// Edge-balancing suppresses looking up/down. ROM Sonic_Move runs
			// Sonic_Balance BEFORE Sonic_LookUp/Sonic_Duck, and when the player is
			// balancing on a floor or object edge it branches to Sonic_ResetScr
			// ("prevent looking up/down", docs/s1disasm/_incObj/01 Sonic.asm:435-460;
			// the standing-on-object path lines 409-431 reaches .balance -> ResetScr),
			// easing v_lookshift back to $60 instead of running Look/Duck. The engine
			// computes the balance state in updateCrouchState(), which runs AFTER this
			// doGroundMove() look code, so on the FIRST balancing frame (e.g. the frame
			// a hurt-knockback recovery lands the player edge-balancing) the look code
			// read a stale not-balancing state and ducked once (SLZ3 f1167). Compute
			// the current-frame balance predicate HERE, with no lasting side effects:
			// the actual balance state + facing are restored and recomputed at their
			// normal time by updateCrouchState(), so other traces are unaffected.
			boolean lookGateActive = isOnFlatGround() && gSpeed == 0;
			boolean balancingNow = lookGateActive && computeCurrentFrameBalancing();
			if (lookGateActive
					&& ((!inputLeft && !inputRight) || directionalBrakeReachedZero)) {
				// ROM clears Status_Push before choosing Wait/Balance/Look/Duck,
				// so a released direction exits the push display even when the
				// standing-on-object balance branch diverts to ResetScr
				// (S1 01 Sonic.asm:327-351; S2 s2.asm:36242-36271;
				// S3K sonic3k.asm:22450-22473).
				sprite.setPushing(false);
			}
			if (lookGateActive && !balancingNow) {
				short lookDelay = sprite.getLookDelayCounter();
				GameRules rules = sprite.getGameRules();
				short lookScrollDelay = rules != null && rules.camera() != null
						? rules.camera().lookScrollDelay()
						: GameRules.SONIC_2.camera().lookScrollDelay();
				if (inputUp) {
					// ROM: Sonic_Lookup (s2.asm:36398-36409, s1.asm: Sonic_LookUp)
					// Animation is set immediately, camera pan may have delay
					sprite.setLookingUp(true);
					lookDelay++;
					if (camera != null) {
						if (lookDelay >= lookScrollDelay) {
							lookDelay = lookScrollDelay;
							camera.incrementLookUpBias();
						} else {
							// During delay, bias still eases toward default
							camera.easeYBiasToDefault();
						}
					}
				} else if (inputDown) {
					// ROM: Sonic_Duck (s2.asm:36412-36423, s1.asm: Sonic_Duck)
					// Animation (crouching) is handled by updateCrouchState()
					sprite.setLookingUp(false);
					lookDelay++;
					if (camera != null) {
						if (lookDelay >= lookScrollDelay) {
							lookDelay = lookScrollDelay;
							camera.decrementLookDownBias();
						} else {
							// During delay, bias still eases toward default
							camera.easeYBiasToDefault();
						}
					}
				} else {
					// ROM: Obj01_ResetScr (s2.asm:36428-36429)
					sprite.setLookingUp(false);
					lookDelay = 0;
					if (camera != null) {
						camera.easeYBiasToDefault();
					}
				}
				sprite.setLookDelayCounter(lookDelay);
			} else {
				// Not standing still - reset state and ease bias to default
				sprite.setLookingUp(false);
				sprite.setLookDelayCounter((short) 0);
				if (camera != null) {
					camera.easeYBiasToDefault();
				}
			}
		}

		// Snapshot inertia at the ROM `tst.w inertia` point — i.e. before the
		// ground friction below. ROM Sonic_Move/Tails_Move pick the standing-still
		// duck/look-up animation from this pre-friction inertia (S1 _incObj/01
		// Sonic.asm:373, S2 s2.asm:36568/39689) and only afterwards run
		// Obj01_UpdateSpeedOnGround friction (s2.asm:36768-36786). updateCrouchState
		// consumes this so a sidekick whose inertia decays to 0 this frame does not
		// duck a frame early (S2 MCZ1 Tails spindash/jump divergence at f2362).
		preFrictionGroundSpeed = gSpeed;
		sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) gSpeed);

		// Friction
		// ROM ref: s2.asm:36443-36446 — Super Sonic uses normal friction (0x0C) not his profile friction (0x30)
		if (!inputRawLeft && !inputRawRight) {
			short effectiveFriction = sprite.isSuperSonic() ? (short) 0x0C : friction;
			gSpeed = applyFriction(gSpeed, effectiveFriction);
		}

		sprite.setGSpeed(gSpeed);
		calculateXYFromGSpeed();
		collisionSystem().resolveGroundWallCollision(FrameCollisionPlan.terrainOnly(), sprite);
		clearFacingFlipPushAfterGroundWallCollision();
	}

	/** Sonic_Roll / SonicKnux_Roll: Check if should start rolling.
	 *  S2: s2.asm:36954 (threshold 0x80). S3K: sonic3k.asm:23223 (threshold 0x100). */
	private void doCheckStartRoll() {
		short gSpeed = sprite.getGSpeed();

		// ROM S3K: sub_108E6 returns immediately when status_secondary bit 7
		// is set, so slide terrain cannot enter the manual down-roll path.
		if (sprite.isSliding()) return;

		// S3K uses movingCrouchThreshold ($100) as the roll speed threshold;
		// below that speed, down enters crouch (handled in updateCrouchState).
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		int rollThreshold = (movementRules != null && movementRules.movingCrouchThreshold() > 0)
				? movementRules.movingCrouchThreshold() : minStartRollSpeed;
		if (Math.abs(gSpeed) < rollThreshold) return;
		// ROM roll-entry tests the held controller bits directly, not the
		// move_lock-filtered left/right movement inputs. In S3K, move_lock only
		// gates Tails_InputAcceleration_Path (sonic3k.asm:27796-27797);
		// Tails_Roll still runs afterward and tests Ctrl_2_held_logical directly
		// for left/right/down (sonic3k.asm:27523-27524,28461-28472). S1/S2 use the same
		// held-left/right roll gate (docs/s1disasm/_incObj/01 Sonic.asm:
		// 899-902; docs/s2disasm/s2.asm:36960-36963,39939-39942).
		if (inputLeft || inputRight || inputRawLeft || inputRawRight) return;
		if (!inputDown) return;
		if (sprite.getAir() || sprite.getRolling()) return;

		short preRollCentreX = sprite.getCentreX();
		sprite.setRolling(true);
		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		if (animationRules != null && animationRules.animationChangeClearsPush()) {
			// Tails_Roll/SonicKnux_Roll write anim=#2 on roll entry
			// (sonic3k.asm:23259-23264,28494-28500). Animate_Tails/
			// Animate_Sonic later clears Status_Push when anim != prev_anim
			// (sonic3k.asm:29359-29364,29681-29686); the engine writes the
			// roll animation inside setRolling(), so clear the same status bit
			// at the movement transition.
			sprite.setPushing(false);
		}
		// ROM roll entry writes y_radius/x_radius and y_pos only; x_pos is not
		// modified in S1/S2/S3K (S1 01 Sonic.asm:1095-1099;
		// S2 s2.asm:37003-37008; S3K SonicKnux_Roll sonic3k.asm:23259-23264,
		// Tails_Roll sonic3k.asm:28494-28500). Preserve ROM centre X when the
		// engine's top-left sprite box changes width on wall modes.
		sprite.setCentreXPreserveSubpixel(preRollCentreX);
		sprite.setY((short) (sprite.getY() + sprite.getRollHeightAdjustment()));
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
		Camera camera = playerCameraBiasController();
		if (camera != null) {
			camera.easeYBiasToDefault();
		}

		// ROM: tst.b spin_dash_flag(a0) / bmi.w loc_115C6 (sonic3k.asm:22935-22936)
		// When pinballSpeedLock is set (spin_dash_flag bit 7 = 0x81), skip input,
		// friction, and deceleration — go straight to velocity conversion.
		// Speed is only modified by slope gravity (Player_RollRepel, called before this).
		if (sprite.getPinballSpeedLock()) {
			convertRollVelocity(gSpeed);
			return;
		}

		// ROM: tst.b (f_slidemode).w / bne.w loc_131CC (s1.asm:602-603)
		// When sliding, skip input and friction — go straight to velocity conversion.
		if (sprite.isSliding()) {
			convertRollVelocity(gSpeed);
			return;
		}

		boolean objectPreservedRollStop = sprite.shouldPreserveRollingOnNextRollStop();
		boolean skipControlledRollInput = sprite.consumeObjectPreservedRollBoostFollowup();
		boolean skipObjectPreservedRollInput = sprite.consumeObjectPreservedRollWallProbe();
		boolean objectPreservedVelocityCarry = sprite.shouldApplyObjectPreservedRollVelocityCarry();
		boolean inputAllowed = sprite.getMoveLockTimer() == 0
				&& !skipControlledRollInput
				&& !skipObjectPreservedRollInput;
		if (objectPreservedVelocityCarry
				&& objectPreservedRollStop
				&& sprite.getRolling()
				&& !sprite.getAir()
				&& gSpeed == 0
				&& sprite.getXSpeed() != 0) {
			int carriedXSpeed = Math.max(-0x1000, Math.min(0x1000, sprite.getXSpeed()));
			sprite.setXSpeed((short) carriedXSpeed);
			sprite.setYSpeed((short) 0);
			return;
		}
		if (objectPreservedVelocityCarry) {
			sprite.clearObjectPreservedRollVelocityCarry();
		}

		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		short rollDecel = (short) 0x20;
		// ASSEMBLY FLAG: fixBugs (docs/s2disasm/s2.asm:27), 0 in the shipped ROM.
		// THE ENGINE IMPLEMENTS THE SHIPPED (UN-FIXED) BRANCH for Tails_RollSpeed:
		// s2.asm:40037-40040 keeps the outdated Sonic-1 form
		//   move.w (Tails_deceleration).w,d4 / asr.w #2,d4
		// so Tails' controlled roll deceleration is decel>>2 -- $20 on land
		// ($80>>2) but only $10 underwater ($40>>2), which is why the disassembly
		// notes Tails is "much worse at this than Sonic when underwater". With
		// fixBugs = 1 the two lines become move.w #$20,d4, matching
		// Sonic_RollSpeed. Sonic 2's Sonic_RollSpeed and *both* S3K routines
		// (sonic3k.asm:22934, :28178) are unconditionally flat $20; S1's single
		// Sonic_RollSpeed uses the >>2 form for its only character.
		boolean tailsOutdatedControlledRollDecel = movementRules != null
				&& movementRules.tailsRollSpeedUsesEffectiveDecelQuarter()
				&& sprite.usesTailsRollSpeedRoutine();
		if (tailsOutdatedControlledRollDecel
				|| movementRules != null && movementRules.rollControlledDecelUsesEffectiveDecelQuarter()) {
			// S1 Sonic_RollSpeed derives d4 from v_sonspeeddec >> 2, so the
			// underwater value is $40 >> 2 = $10. S2 Sonic / S3K hardcode $20.
			rollDecel = (short) (sprite.getRunDecel() >> 2);
		}
		// AUDIT (fixBugs, s2.asm:27 `fixBugs = 0`; block at s2.asm:40032-40041): S2's
		// Sonic_RollSpeed hardcodes $20, but Tails_RollSpeed does NOT -- the shipped
		// branch keeps the outdated S1-style `move.w (Tails_deceleration).w,d4 /
		// asr.w #2,d4`. Out of water Tails_deceleration is $80, so $80>>2 = $20 and the
		// two agree; underwater it halves to $40, giving Tails $10 against Sonic's $20,
		// which is why the disassembly notes Tails is much worse at controlled rolling
		// underwater. fixBugs=1 replaces it with a flat `move.w #$20,d4` to match Sonic.
		// The engine currently gives S2 Tails $20 in all cases (the fixBugs=1 branch),
		// because this knob lives on the game-wide PlayerMovementRules and has no
		// per-character owner. Modelling the shipped branch needs that owner; recorded as
		// an audit finding rather than fixed here.
		if (inputAllowed && inputLeft) {
			if (gSpeed > 0) {
				// ROM Sonic_RollLeft.changeddirection: sub.w d4,d0 / bcc / move.w #-$80.
				// The subtraction borrows (carry set) only when the result is strictly
				// negative, so a result of exactly 0 stays 0 (s1:01 Sonic.asm:871-878;
				// s2.asm:37119-37124; sonic3k.asm sub_11608 loc_1161E).
				gSpeed -= rollDecel;
				if (gSpeed < 0) gSpeed = (short) -128;
			} else {
				sprite.setDirection(Direction.LEFT);
				publishDirectionalRollAnimation();
			}
		}
		if (inputAllowed && inputRight) {
			if (gSpeed < 0) {
				// ROM Sonic_RollRight.changedirection: add.w d4,d0 / bcc / move.w #$80.
				// The addition carries (carry set) when the result is >= 0, so a result
				// of exactly 0 clamps to +$80 -- unlike the leftward sub-borrow case
				// above (s1:01 Sonic.asm:895-899; s2.asm:37141-37146;
				// sonic3k.asm sub_1162C loc_11640).
				gSpeed += rollDecel;
				if (gSpeed >= 0) gSpeed = (short) 128;
			} else {
				sprite.setDirection(Direction.RIGHT);
				publishDirectionalRollAnimation();
			}
		}

		// Natural deceleration
		if (gSpeed != 0) {
			short naturalDecel = (short) (sprite.getRunAccel() / 2);
			gSpeed = applyFriction(gSpeed, naturalDecel);
		}

		// Stop rolling check. S1/S2 wait for inertia to reach zero; S3K compares
		// abs(ground_vel) against min_roll_speed ($80) and unrolls below it.
		// Refs: s1disasm/_incObj/01 Sonic.asm:760-768; s2.asm:37046-37055,
		// 40072-40081; sonic3k.asm:22971-22986,28216-28231.
		boolean stopRolling = movementRules != null && movementRules.rollStopsBelowMinimumSpeed()
				? Math.abs(gSpeed) < minRollSpeed
				: gSpeed == 0;
		if (stopRolling) {
			if (sprite.getPinballMode()) {
				gSpeed = (short) (sprite.getDirection() == Direction.LEFT ? -0x400 : 0x400);
			} else if (objectPreservedRollStop) {
				// Object-scoped ROM handoff: S2 Obj85 leaves Tails curled in
				// the CNZ stopper chamber. The following zero-inertia roll-stop
				// decision must not apply the generic unroll/Y-radius change;
				// when the later positive handoff appears, the next frame's wall
				// probe can turn it into the chamber push (s2.asm:39716-39745,
				// 39481-39507).
				if (inputRight) {
					gSpeed = 0x400;
					sprite.markObjectPreservedRollBoostFollowup();
				}
			} else {
				// ROM roll-stop writes y_radius/x_radius and y_pos only; x_pos is
				// unchanged (sonic3k.asm:22978-22986). On wall modes the engine
				// represents the radius change by widening the top-left sprite box,
				// so preserve the native centre X across that representation change.
				short preRollStopCentreX = sprite.getCentreX();
				sprite.setRolling(false);
				sprite.setCentreXPreserveSubpixel(preRollStopCentreX);
				sprite.setY((short) (sprite.getY() - sprite.getRollHeightAdjustment()));
				applyRollStopAnimationChange();
			}
		}

		sprite.setGSpeed(gSpeed);
		convertRollVelocity(gSpeed);
		if (skipControlledRollInput) {
			sprite.markObjectPreservedRollWallProbe();
		}
	}

	private void applyRollStopAnimationChange() {
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (!(profile instanceof ScriptedVelocityAnimationProfile velocityProfile)) {
			return;
		}
		int idleAnimId = velocityProfile.getIdleAnimId();
		// A frame that began in rolling ground mode has no normal-ground Move
		// snapshot. Preserve RollSpeed's explicit Wait write so held input cannot
		// replace it. Do not apply this to an air-start frame that lands: the ROM
		// dispatches that through air mode and ResetOnFloor writes Walk instead.
		if (!sprite.wasPrePhysicsAir()) {
			sprite.getAnimationManager().captureGroundMovementAnimSpeed((short) 0);
		}
		if (sprite.getAnimationId() == idleAnimId) {
			return;
		}
		// No Status_Push write here. The ROM roll-stop block writes only the
		// rolling bit, the radii, anim and y_pos -- S3K Sonic_RollSpeed
		// (sonic3k.asm:22979-22990), Tails_RollSpeed (sonic3k.asm:28216-28231),
		// S2 Sonic_CheckRollStop (s2.asm:37051-37061). Status_Push is cleared by
		// Animate_Sonic/Animate_Tails on anim != prev_anim
		// (sonic3k.asm:29359-29364,29681-29686; s2.asm:38033-38038,40879-40884),
		// which run AFTER Sonic_RecordPos in Obj01_Control
		// (sonic3k.asm:21995-22022). Clearing here would put a push-free byte
		// into the follower history ring one routine early.
		sprite.setAnimationId(idleAnimId);
	}

	/**
	 * Converts ground_vel to x_vel/y_vel using angle, caps x_vel to ±0x1000,
	 * then resolves ground wall collision.
	 * ROM: loc_115C6 (sonic3k.asm lines 23013-23031).
	 */
	private void convertRollVelocity(short gSpeed) {
		int hexAngle = sprite.getAngle() & 0xFF;
		short xVel = (short) ((gSpeed * TrigLookupTable.cosHex(hexAngle)) >> 8);
		short yVel = (short) ((gSpeed * TrigLookupTable.sinHex(hexAngle)) >> 8);
		xVel = (short) Math.max(-0x1000, Math.min(0x1000, xVel));
		sprite.setXSpeed(xVel);
		sprite.setYSpeed(yVel);
		collisionSystem().resolveGroundWallCollision(FrameCollisionPlan.terrainOnly(), sprite);
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
		// S1/S2 (s1:01 Sonic.asm:736-750, s2.asm:36826-36840): unconditional cap at max.
		// S3K (sonic3k.asm:23088-23121): preserves speeds already above max (undo+check).
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		boolean preserveSuperspeed = movementRules != null && movementRules.airSuperspeedPreserved();
		if (!sprite.getRollingJump()) {
			if (inputLeft) {
				sprite.setDirection(Direction.LEFT);
				short accel = (short) (2 * runAccel);
				short newSpeed = (short) (xSpeed - accel);
				short negMax = (short) -max;
				if (newSpeed > negMax) {
					xSpeed = newSpeed;
				} else if (preserveSuperspeed && xSpeed <= negMax) {
					// S3K: already past max — preserve original (don't cap)
				} else {
					xSpeed = negMax;
				}
			}
			if (inputRight) {
				sprite.setDirection(Direction.RIGHT);
				short accel = (short) (2 * runAccel);
				short newSpeed = (short) (xSpeed + accel);
				if (newSpeed < max) {
					xSpeed = newSpeed;
				} else if (preserveSuperspeed && xSpeed >= max) {
					// S3K: already past max — preserve original (don't cap)
				} else {
					xSpeed = max;
				}
			}
		}

		Camera camera = playerCameraBiasController();
		if (camera != null) {
			camera.easeYBiasToDefault();
		}

		// Air drag near apex (-1024 <= ySpeed < 0)
		// ROM: asr.w #5,d1 — arithmetic shift right rounds toward -∞.
		// Java /32 truncates toward zero, giving wrong drag for negative xSpeed.
		if (ySpeed < 0 && ySpeed >= -1024) {
			int drag = xSpeed >> 5;
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

	/** ObjectMoveAndFall: Apply velocity and gravity (s2.asm:29945-29953)
	 * ROM applies gravity to y_vel BEFORE movement, but uses the OLD y_vel for position:
	 *   move.w  y_vel(a0),d0           ; Save old y_vel in d0
	 *   addi.w  #$38,y_vel(a0)         ; Add gravity to y_vel FIRST
	 *   ext.l   d0
	 *   asl.l   #8,d0
	 *   add.l   d0,d3                  ; Position uses OLD y_vel (d0, before gravity)
	 */
	private void doObjectMoveAndFall() {
		int suppressedAxes = sprite.consumeSuppressedObjectMoveAndFallAxes();
		if (suppressedAxes == 0x3) {
			return;
		}
		SidekickCpuController cpu = sprite.getCpuController();
		if (cpu != null && cpu.usesFlyingCarryMovement()) {
			// Tails_FlyingSwimming applies Tails_Move_FlySwim before
			// MoveSprite_TestGravity2, so the carry controller owns the
			// carrier's per-frame vertical flight velocity here.
			if (!tailsFlightVerticalUpdatedThisFrame) {
				cpu.applyFlyingCarryVerticalVelocity();
			}
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			return;
		}
		if (isTailsFlightPhysicsActive(sprite)) {
			// Tails_FlyingSwimming (sonic3k.asm:27570) applies Tails_Move_FlySwim
			// before MoveSprite_TestGravity2. MoveSprite_TestGravity2 does not
			// apply +$38 air gravity (that's MoveSprite_TestGravity's job), and
			// since Tails_Move_FlySwim already advanced y_vel by +0x08, the
			// movement step uses the post-gravity y_vel.
			if (!tailsFlightVerticalUpdatedThisFrame) {
				applyGravity();
			}
			sprite.move(sprite.getXSpeed(), sprite.getYSpeed());
			return;
		}
		short oldYSpeed = sprite.getYSpeed();  // Save old y_vel before gravity
		applyGravity();                         // Gated on isObjectControlled()
		short xMoveSpeed = (suppressedAxes & 0x1) != 0 ? 0 : sprite.getXSpeed();
		short yMoveSpeed = (suppressedAxes & 0x2) != 0 ? 0 : oldYSpeed;
		sprite.move(xMoveSpeed, yMoveSpeed);  // Move using OLD y_vel
	}

	/**
	 * Apply the sprite's per-frame gravity to y_vel.
	 *
	 * <p>Skipped when {@link AbstractPlayableSprite#isObjectControlSuppressesMovement()} is true
	 * (ROM: Obj01_Control skips movement routines entirely). This gate is what
	 * lets the S3K Tails-carry driver ({@code SidekickCpuController} CARRYING
	 * state) keep a stable velocity latch on the carried Sonic; without it,
	 * gravity accumulates between the driver's latch-update and latch-compare
	 * and spuriously triggers release path C (external-vel mismatch).
	 *
	 * <p>Mirrors the ROM behaviour where {@code object_control != 0} short-circuits
	 * the entire movement dispatch in {@code Obj01_Control}.
	 *
	 * <p>While a sidekick is carrying its leader in flight, the ROM applies
	 * Tails's flight gravity (+0x08/frame, Tails_Move_FlySwim loc_1488C at
	 * sonic3k.asm:27633) instead of the standard +0x38 air gravity. The
	 * carrier sprite is not {@code object_controlled} (that flag is on the
	 * carried leader), so the regular gate doesn't cover this case.
	 */
	private void applyGravity() {
		if (sprite.isObjectControlSuppressesMovement()) {
			return;
		}
		if (sprite.consumeSuppressNextGravityStep()) {
			return;
		}
		SidekickCpuController cpu = sprite.getCpuController();
		if (cpu != null && cpu.usesFlyingCarryMovement()) {
			cpu.applyFlyingCarryVerticalVelocity();
			return;
		}
		if (isTailsFlightPhysicsActive(sprite)) {
			sprite.setYSpeed((short) (sprite.getYSpeed() + 0x08));
			return;
		}
		sprite.setYSpeed((short) (sprite.getYSpeed() + sprite.getGravity()));
	}

	/**
	 * ROM: Tails_Stand_Freespace (sonic3k.asm:27553-27555) branches to
	 * Tails_FlyingSwimming whenever {@code double_jump_flag(a0) != 0},
	 * which swaps +$38 air gravity for +$08 flight gravity (Tails_Move_FlySwim
	 * loc_1488C at sonic3k.asm:27633).
	 *
	 * <p>The flag is set by {@code loc_13FC2} when Tails picks up Sonic for the
	 * CNZ1 carry intro, and — crucially — {@code loc_14016}'s landing release
	 * does NOT clear it. Tails therefore continues flying on the frame that
	 * Sonic lands, producing the trace's observed {@code y_vel = 0x0008}
	 * (0 reset + +0x08 flight gravity) instead of {@code 0x0388}
	 * (0x0350 carry momentum + +0x38 normal gravity).
	 *
	 * <p>Gated on {@link SecondaryAbility#FLY} so Sonic's insta-shield and
	 * Knuckles's glide (which both also use {@code double_jump_flag}) keep
	 * their normal air gravity; only Tails's code path in the ROM has the
	 * Tails_Stand_Freespace -&gt; Tails_FlyingSwimming branch.
	 */
	private static boolean isTailsFlightPhysicsActive(AbstractPlayableSprite sprite) {
		return sprite.getSecondaryAbility() == SecondaryAbility.FLY
				&& sprite.getDoubleJumpFlag() != 0;
	}

	// ========================================
	// COLLISION
	// ========================================

	/** Sonic_LevelBound: Check level boundaries (s2.asm:36890) */
	private void doLevelBoundary() {
		// ROM: When object_control is set, level boundary checks are skipped
		// (Obj01_Control skips movement routines entirely).
		if (sprite.isObjectControlSuppressesMovement()) {
			return;
		}
		Camera camera = camera();
		if (camera == null) return;

		// ROM uses center coordinates for x_pos, so boundary offsets are calibrated for center
		final int SONIC_WIDTH = 24, LEFT_OFFSET = 16, RIGHT_EXTRA = 64;
		// The right level boundary is the level's design edge — Camera_Max_X_pos +
		// the NATIVE screen width (320) — NOT the render viewport. camera.getMaxX()
		// is the native ROM scroll limit (level_edge - 320), so widening this by a
		// wider viewport would let the player walk past the level's right wall into
		// the void beyond a camera lock and fall to their death where no level
		// exists. Must stay native regardless of DISPLAY_ASPECT.
		final int LEVEL_DESIGN_WIDTH = 320;

		// ROM: move.l obX(a0),d1 / ext.l d0 / asl.l #8,d0 / add.l d0,d1 / swap d1
		// Uses 32-bit position (pixel:16 | subpixel:16) + velocity << 8.
		// The subpixel byte is in bits 8-15 of xSubpixel (high byte of low word).
		int xTotal = (sprite.getCentreX() * 256) + ((sprite.getXSubpixelRaw() >> 8) & 0xFF) + sprite.getXSpeed();
		int predictedX = xTotal >> 8;

		int minX = camera.getMinX();
		int maxX = camera.getMaxX();
		// FLAG: FixBugs / fixBugs (docs/s1disasm/sonic.asm:20, docs/s2disasm/s2.asm:27
		// -- both 0 in the shipped ROMs).
		// ENGINE IMPLEMENTS: the shipped (flag = 0) branch -- the bottom kill plane is
		// the LIVE eased boundary alone (S1 v_limitbtm2, S2 Camera_Max_Y_pos,
		// S3K Camera_max_Y_pos). It never consults the easing TARGET, so falling faster
		// than the boundary can ease down kills the player (the GHZ1 S-tunnel death).
		// OTHER BRANCH (flag = 1) would take max(live, target)
		// (docs/s1disasm/_incObj/01 Sonic.asm:1084-1092 Sonic_LevelBound and 1922-1932
		// Sonic_HurtStop; docs/s2disasm/s2.asm:37255-37265 Sonic_Boundary_CheckBottom),
		// suppressing those deaths. S3K has no such conditional at all
		// (docs/skdisasm/sonic3k.asm:23193-23196) -- it only ever reads the live value --
		// so one shared expression is correct for all three games and no per-game rule
		// is needed.
		// NOT YET CORRECTED -- see the FixBugs note above. Taking the shipped branch
		// here (camera.getMaxY() alone) regresses TestS2SczLevelSelectTraceReplay,
		// which means max() is masking a second defect that must be found first.
		int maxY = Math.max(camera.getMaxY(), camera.getMaxYTarget());
		if (sprite.isCpuControlled() && sprite.getCpuController() != null) {
			minX = sprite.getCpuController().getMinXBound(minX);
			maxX = sprite.getCpuController().getMaxXBound(maxX);
			maxY = sprite.getCpuController().getMaxYBound(maxY);
		}

		int leftBoundary = minX + LEFT_OFFSET;
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		// S3K Player_Boundary_Sides/Tails_Check_Screen_Boundaries use
		// Camera_max_X_pos+$128 directly, with no normal-play +$40 extension
		// (sonic3k.asm:23183-23186, 28418-28421). This reproduces +$128 / +$128+$40
		// exactly: $128 = 320 - 24 = LEVEL_DESIGN_WIDTH - SONIC_WIDTH. The boundary
		// is viewport-independent — it tracks the level's right wall, not the screen.
		// The +64 right-boundary extension is removed during a boss/screen lock.
		// ROM gates that on different flags per game: S1 uses the persistent
		// f_lockscreen (set at boss spawn, cleared only by Egg Prison / LZ boss,
		// so it survives boss defeat in the Final Zone — s1disasm/_incObj/01
		// Sonic.asm:1047-1049); S2 uses Current_Boss_ID, i.e. boss-alive
		// (s2.asm:37247-37250). PlayerMovementRules.levelBoundaryLockUsesScreenLockFlag
		// selects which. S3K never adds the +64 (levelBoundaryRightStrict).
		boolean lockActive = (movementRules != null && movementRules.levelBoundaryLockUsesScreenLockFlag())
				? gameState().isScreenLocked()
				: gameState().isBossFightActive();
		boolean strict = (movementRules != null && movementRules.levelBoundaryRightStrict())
				|| lockActive || gameState().isEndOfLevelActive();
		if (lockActive && movementRules != null
				&& movementRules.levelBoundaryUsesPreEasedMaxXDuringBossLock()) {
			// S2 player slots run before later object/boundary-release writes reach Sonic_LevelBound.
			maxX = camera.getMaxXBeforeBoundaryEasing();
		}
		int rightBoundary = RightBoundary.compute(maxX, LEVEL_DESIGN_WIDTH, SONIC_WIDTH, RIGHT_EXTRA, strict);

		// ROM comparison: left is always bhi.s (<). S1/S2 right uses bls.s
		// (>=), while S3K uses blo.s (>), gated by PlayerMovementRules.
		if (predictedX < leftBoundary) {
			sprite.setCentreX((short) leftBoundary);
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		} else if (isPastRightLevelBoundary(predictedX, rightBoundary)) {
			sprite.setCentreX((short) rightBoundary);
			sprite.setXSpeed((short) 0);
			sprite.setGSpeed((short) 0);
		}

		// The kill plane is the LIVE eased boundary only -- see the FixBugs note on
		// the maxY assignment above; the max(live, target) form is the flag = 1 branch.
		// ROM: When Level_started_flag is clear, boundary death is suppressed
		// (camera boundaries may not reflect actual level extents during intro).
		if (camera.isLevelStarted()) {
			short effectiveMaxY = (short) maxY;
			// ROM compares the player's y_pos(a0) word, which is centre-Y, not top-left:
			//   S1 Sonic_LevelBound .bottom: cmp.w obY(a0),d0 / blt.s .bottom
			//     (s1disasm/_incObj/01 Sonic.asm:1014).
			//   S2 Sonic_LevelBound Sonic_Boundary_CheckBottom: cmp.w y_pos(a0),d0
			//     / blt.s Sonic_Boundary_Bottom (s2.asm:36950).
			//   S2 Tails_LevelBound Tails_Boundary_CheckBottom: cmp.w y_pos(a0),d0
			//     / blt.s Tails_Boundary_Bottom (s2.asm:39929).
			//   S3K Player_LevelBound Player_Boundary_CheckBottom: cmp.w y_pos(a0),d0
			//     / blt.s Player_Boundary_Bottom (sonic3k.asm:23195).
			//   S3K Tails_Check_Screen_Boundaries loc_14F30: cmp.w y_pos(a0),d0
			//     / blt.s loc_14F56 (sonic3k.asm:28430-28431).
			// PlayerMovementRules.levelBoundaryUsesCentreY gates centre-Y semantics
			// for each game. S1/S2/S3K all enable it because their ROM routines
			// compare y_pos(a0)/obY(a0), which maps to engine centre-Y.
			//
			// CPU sidekicks always use centre-Y to match ROM Tails_LevelBound
			// behavior regardless of game: MCZ1 F398 (S2 level-select trace, recorded
			// at lua_script_version 9.2-s2) records Tails crossing the kill plane at
			// centre-Y=0x0807 with maxY+screen_height=0x0800, triggering
			// JmpTo2_KillCharacter (s2.asm:39929-39939). Using top-left (Y=0x07F9) misses
			// the kill by 8 pixels and Tails keeps falling instead of dying.
			boolean useCentreY = (movementRules != null && movementRules.levelBoundaryUsesCentreY())
					|| (sprite.isCpuControlled() && sprite.getCpuController() != null);
			int playerY = useCentreY ? sprite.getCentreY() : sprite.getY();
			if (playerY > effectiveMaxY + 224) {
				GameModule module = sprite.currentGameModule();
				LevelEventProvider levelEvents = module != null ? module.getLevelEventProvider() : null;
				SidekickCpuController cpuController = sprite.getCpuController();
				if (sprite.isCpuControlled() && cpuController != null) {
					// MGZ2 boss transition starts Tails's scripted carry below
					// the normal camera bottom. ROM runs Tails_Check_Screen_Boundaries
					// in that path without returning to the generic CPU respawn state.
					if (!cpuController.usesFlyingCarryMovement()
							&& (levelEvents == null || !levelEvents.interceptPitDeath(sprite))) {
						// ROM Player_LevelBound (sonic3k.asm:23172) jumps to
						// Kill_Character (sonic3k.asm:21136) for both player and
						// sidekick when the bottom kill plane is crossed. The
						// LEVEL_BOUNDARY cause selects the engine's
						// Kill_Character-equivalent path (zero velocities + one-
						// frame DEAD_FALLING state) so the trace's end-of-frame
						// (vels=0, routine=6) sample matches at AIZ F4679.
						cpuController.despawn(
							com.openggf.sprites.playable.SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);
					}
				} else {
					// ROM: Sonic_LevelBound checks for zone-specific intercepts
					// (e.g. SBZ2 fall -> SBZ3 transition) before applying death.
					if (levelEvents == null || !levelEvents.interceptPitDeath(sprite)) {
						sprite.applyPitDeath();
					}
				}
			}
		}
	}

	/**
	 * {@code Sonic_HurtStop}'s own bottom-boundary kill test, run before the hurt
	 * routine hands off to {@code Sonic_Floor} / {@code DoLevelCollision}.
	 *
	 * <p>ROM: S1 {@code Sonic_HurtStop} (docs/s1disasm/_incObj/01 Sonic.asm:1930-1941),
	 * S2 {@code Sonic_HurtStop} (docs/s2disasm/s2.asm:38194-38215), S3K
	 * {@code sub_12318} (docs/skdisasm/sonic3k.asm:24471-24491).
	 *
	 * <p>S1 assembles with {@code FixBugs = 0} (docs/s1disasm/sonic.asm:20) and the
	 * engine implements that shipped branch, because the traces record shipped-ROM
	 * behaviour. Two things follow from the conditional:
	 * <ul>
	 *   <li>the boundary word would be {@code v_limitbtm2}, the camera's TARGET
	 *       bottom boundary, with no consideration of the eased real boundary
	 *       {@code v_limitbtm1} (S2/S3K read the live boundary instead). The
	 *       {@code FixBugs = 1} branch takes the lower of the two so that
	 *       outrunning a still-lowering boundary (the GHZ1 S-tunnel) is not an
	 *       unfair death. This site does NOT yet take the shipped branch: it runs
	 *       the same {@code max(live, target)} expression as the sibling kill plane
	 *       in {@link #doLevelBoundary}, which is deliberately held there because
	 *       removing it regresses TestS2SczLevelSelectTraceReplay. Measured: with
	 *       the shipped live-only word here, SCZ fails at frame 7109 (x_speed
	 *       expected -0x0200, actual 0x0000, 523 errors) — Sonic is killed by this
	 *       row during hurt knockback where the ROM does not kill him. The two
	 *       kill planes are the same ROM conditional and must move together;</li>
	 *   <li>the compare is the UNSIGNED {@code blo}, so a hurt Sonic who leaves the
	 *       TOP of the level — {@code y_pos} wrapped to {@code $Fxxx}, which reads
	 *       as a huge unsigned word — also dies. The {@code FixBugs = 1} branch uses
	 *       a signed {@code blt} and kills only at the bottom.</li>
	 * </ul>
	 * The signed/unsigned divergence is carried by {@link PlayerLevelBoundaryRules},
	 * not by a game name: S2 and S3K ship the signed compare, so this is a real
	 * per-game difference in the ROMs.
	 *
	 * @return true when the player was killed and the hurt routine must return
	 */
	private boolean applyHurtStopBottomKill() {
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		if (movementRules == null) {
			return false;
		}
		Camera camera = camera();
		if (camera == null || !camera.isLevelStarted()) {
			// Engine-side guard shared with doLevelBoundary: before Level_started_flag
			// is set the camera boundaries do not yet describe the level.
			return false;
		}
		// Held mask, shared with doLevelBoundary's kill plane — see the javadoc above.
		int boundary = Math.max(camera.getMaxY(), camera.getMaxYTarget());
		if (sprite.isCpuControlled() && sprite.getCpuController() != null) {
			boundary = sprite.getCpuController().getMaxYBound(boundary);
		}
		// ROM: addi.w #224,d0 / cmp.w y_pos(a0),d0 — a 16-bit word compare, so both
		// operands are masked to a word before the unsigned test.
		int killRow = (boundary + 224) & 0xFFFF;
		int playerY = sprite.getCentreY();
		boolean past = movementRules.hurtStopBottomKillUnsigned()
				? killRow < (playerY & 0xFFFF)
				: (short) killRow < (short) playerY;
		if (!past) {
			return false;
		}
		GameModule module = sprite.currentGameModule();
		LevelEventProvider levelEvents = module != null ? module.getLevelEventProvider() : null;
		SidekickCpuController cpuController = sprite.getCpuController();
		if (sprite.isCpuControlled() && cpuController != null) {
			if (cpuController.usesFlyingCarryMovement()
					|| (levelEvents != null && levelEvents.interceptPitDeath(sprite))) {
				return false;
			}
			cpuController.despawn(SidekickCpuController.DespawnCause.LEVEL_BOUNDARY);
			return true;
		}
		if (levelEvents != null && levelEvents.interceptPitDeath(sprite)) {
			return false;
		}
		sprite.applyPitDeath();
		return true;
	}

	private boolean isPastRightLevelBoundary(int predictedX, int rightBoundary) {
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		return movementRules != null && movementRules.levelBoundaryRightStrict()
				? predictedX > rightBoundary
				: predictedX >= rightBoundary;
	}

	private boolean isCpuLevelBoundaryKillActive() {
		SidekickCpuController controller = sprite.getCpuController();
		return sprite.isCpuControlled()
				&& controller != null
				&& controller.getState() == SidekickCpuController.State.DEAD_FALLING;
	}

	/** AnglePos: Ground terrain collision (s2.asm:42534) */
	private void doAnglePos() {
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		int positiveThreshold = (movementRules != null && movementRules.fixedAnglePosThreshold())
				? 14
				: Math.min(getSpeedForThreshold() + 4, 14);
		// resolveGroundAttachment handles the ROM Status_OnObj early return only
		// when a live object support path still owns the player. Stale latches must
		// fall through to terrain walk-off so the player cannot stand in mid-air.
		collisionSystem().resolveGroundAttachment(
				FrameCollisionPlan.terrainOnly(), sprite, positiveThreshold, this::hasObjectSupport);
	}

	/** Sonic_SlopeRepel: Slip/fall check (s2.asm:37432) */
	private void doSlopeRepel() {
		if (sprite.isStickToConvex()) return;
		int activeMoveLock = sprite.getMoveLockTimer();
		if (activeMoveLock > 0) {
			// ROM checks/decrements move_lock before evaluating the angle slip
			// branch (S2 Sonic_SlopeRepel s2.asm:37458-37479; S2
			// Tails_SlopeRepel s2.asm:40313-40334; S3K Player_SlopeRepel
			// sonic3k.asm:23909-23948). AnglePos may have returned early
			// because Status_OnObj was set, but SlopeRepel is still called by
			// the ground/roll dispatcher, so a prior terrain-slip lock burns
			// down while the player rides an object.
			sprite.setMoveLockTimer(activeMoveLock - 1);
			return;
		}
		// Engine object-support aggregation can leave a non-flat terrain angle while
		// an object owns ground contact. Preserve the existing guard against arming
		// a fresh slope slip from that stale terrain angle; the ROM move_lock
		// countdown has already run above.
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		boolean checksOnObject = (movementRules == null || movementRules.slopeRepelChecksOnObject());
		if (checksOnObject && (sprite.isOnObject() || collisionSystem().hasObjectSupport(sprite))) return;

		int angle = sprite.getAngle() & 0xFF;
		boolean s3kSlipKick = movementRules != null && movementRules.slopeRepelUsesS3kSlipKick();
		if (s3kSlipKick) {
			if (((angle + 0x18) & 0xFF) < 0x30) return;
			if (Math.abs(sprite.getGSpeed()) >= SLOPE_REPEL_MIN_SPEED) return;

			sprite.setMoveLockTimer(MOVE_LOCK_FRAMES);
			int slipAngle = (angle + 0x30) & 0xFF;
			if (slipAngle >= 0x60) {
				sprite.setAir(true);
				sprite.setSlopeRepelJustSlipped(true);
			} else if (slipAngle >= 0x30) {
				sprite.setGSpeed((short) (sprite.getGSpeed() + 0x80));
			} else {
				sprite.setGSpeed((short) (sprite.getGSpeed() - 0x80));
			}
			return;
		}

		if (isOnFlatGround()) return;
		if (Math.abs(sprite.getGSpeed()) >= SLOPE_REPEL_MIN_SPEED) return;

		sprite.setGSpeed((short) 0);
		sprite.setAir(true);
		sprite.setSlopeRepelJustSlipped(true);
		sprite.setMoveLockTimer(MOVE_LOCK_FRAMES);
	}

	/** Sonic_DoLevelCollision: Full airborne collision (s2.asm:37540) */
	private void doLevelCollision(boolean forceFloorCheck) {
		int quadrant = TrigLookupTable.calcMovementQuadrant(sprite.getXSpeed(), sprite.getYSpeed());
		Consumer<AbstractPlayableSprite> landingHandler =
				usesDirectHitFloorLanding(quadrant) ? this::calculateDirectFloorLanding : this::calculateLanding;
		collisionSystem().resolveAirCollision(FrameCollisionPlan.terrainOnly(), sprite, landingHandler,
				landingTiltPublisher(), forceFloorCheck);
	}

	private Consumer<SensorResult[]> landingTiltPublisher() {
		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		// The next_tilt/tilt copy from Primary_Angle/Secondary_Angle sits in the
		// character control tail, and the sidekick's tail carries the identical
		// pair as the leader's: S3K Sonic_Control sonic3k.asm:25718-25719 and
		// Tails_Control sonic3k.asm:26243-26244; S2 Obj01 s2.asm:36253-36254 and
		// Obj02 s2.asm:38988-38989. Both run unconditionally every frame, so a
		// landing frame publishes the fresh floor angles for either character.
		// Restricting this to the leader left the sidekick consuming a stale
		// airborne tilt on its first grounded control frame, which deferred the
		// Tails_InputAcceleration_Path edge-balance branch (sonic3k.asm:27837-27849)
		// by one frame - Wait (anim 5) instead of Balance (anim 6).
		return animationRules != null && animationRules.airLandingPublishesTiltAngles()
				? this::captureTiltAnglesFromLandingProbes
				: null;
	}

	/**
	 * Copies the angle bytes that the native player tail reads from the shared
	 * Primary_Angle/Secondary_Angle registers. Tails_DoLevelCollision reaches
	 * Sonic_CheckFloor during a landing, so that landing's own pair is the
	 * shared-register value copied by Tails_Control (S3K sonic3k.asm:
	 * 26243-26244, 28901-29147). Unlike grounded Player_AnglePos, the airborne
	 * Sonic_CheckFloor path does not preload those registers with {@code 3}.
	 * A completely empty current/extension tile search therefore preserves the
	 * register's prior byte (FindFloor sub_F264/sub_F30C, sonic3k.asm:19213-19331).
	 * That prior value belongs to the shared collision registers, not to either
	 * player's private next_tilt/tilt cache.
	 */
	private void captureTiltAnglesFromLandingProbes(SensorResult[] groundResults) {
		SensorResult left = groundResults != null && groundResults.length > 0 ? groundResults[0] : null;
		SensorResult right = groundResults != null && groundResults.length > 1 ? groundResults[1] : null;
		latchedNextTilt = landingAngleRegisterValue(
				right, collisionSystem().getPrimaryAngleRegister());
		latchedTilt = landingAngleRegisterValue(
				left, collisionSystem().getSecondaryAngleRegister());
	}

	private static int landingAngleRegisterValue(SensorResult result, int sharedRegisterValue) {
		return result == null || result.tileId() == 0
				? sharedRegisterValue
				: result.angle() & 0xFF;
	}

	private static boolean usesDirectHitFloorLanding(int quadrant) {
		return quadrant == 0x40 || quadrant == 0xC0;
	}

	/** Obj01_CheckWallsOnGround: Ground wall collision (s2.asm:36486) */
	private void doWallCollisionGround() {
		if (collisionSystem() != null) {
			collisionSystem().resolveGroundWallCollision(FrameCollisionPlan.terrainOnly(), sprite);
			return;
		}
		// S1 roll tunnels: suppress push sensor wall check to prevent false
		// detections against the narrow tunnel walls. S1's ROM has no separate
		// ground wall check (01 Sonic.asm: Sonic_MdNormal has no CalcRoomInFront).
		if (sprite.isTunnelMode()) {
			return;
		}
		// stick_to_convex: Sonic is on a convex loop surface (e.g. Running Disc in SBZ).
		// S1's ROM has no CalcRoomInFront, so when stick_to_convex is active the wall
		// check must be suppressed to prevent push sensors from detecting the disc's
		// curved terrain as walls — which zeros gSpeed and disrupts traversal.
		// In S2/S3K, objects that set stick_to_convex also set tunnelMode (caught above).
		if (sprite.isStickToConvex() || sprite.isSuppressGroundWallCollision()) {
			return;
		}
		Sensor[] pushSensors = sprite.getPushSensors();
		if (pushSensors == null) return;

		int angle = sprite.getAngle() & 0xFF;
		short gSpeed = sprite.getGSpeed();

		// ROM s2.asm:36487-36492 - Skip if angle is steep or not moving
		// addi.b #$40,d0 / bmi.s return - skip if (angle + 0x40) as signed byte is negative
		// ROM uses BYTE arithmetic which wraps at 256, then checks N flag (bit 7 of BYTE result)
		// tst.w inertia(a0) / beq.s return - skip if gSpeed == 0
		int angleCheck = (angle + ANGLE_WALL_OFFSET) & 0xFF;  // Byte arithmetic with wrap
		if ((angleCheck & ANGLE_WALL_MASK) != 0 || gSpeed == 0) {
			return;
		}

		// ROM: bmi.s Obj01_CheckWallsOnGround_Left - select sensor based on gSpeed direction
		int sensorIndex = gSpeed >= 0 ? 1 : 0;
		Sensor sensor = pushSensors[sensorIndex];

		// ROM s2.asm:43480-43491 (CalcRoomInFront): Velocity prediction
		// The ROM scans at PREDICTED position (current + velocity), not current position.
		// This is critical for the velocity adjustment to work correctly:
		//   predicted_pos = current_pos + velocity
		//   distance = how far predicted_pos is inside wall
		//   adjusted_velocity = velocity + distance (cancels over-penetration)
		//   final_pos = current_pos + adjusted_velocity = exactly at wall
		//
		// ROM uses the FULL position (including subpixels) for prediction:
		//   d3 = x_pos (32-bit: pixel.subpixel)
		//   d1 = x_vel << 8 (shift velocity into position format)
		//   d3 += d1 (predicted full position)
		//   swap d3 (get predicted pixel)
		//
		// Our 8-bit subpixel equivalent: predicted_pixel_delta = (subpixel + velocity) >> 8
		// This accounts for accumulated subpixels that may cause a pixel boundary crossing.
		short predictedDx = (short) (((sprite.getXSubpixel() & 0xFF) + sprite.getXSpeed()) >> 8);
		short predictedDy = (short) (((sprite.getYSubpixel() & 0xFF) + sprite.getYSpeed()) >> 8);

		// ROM: CalcRoomInFront (s2.asm:43517-43519) adds +8 to Y only when (rotatedAngle & 0x38) == 0
		// The angle is ROTATED before this check: +0x40 when moving left, -0x40 (0xC0) when moving right.
		// This must be calculated FRESH each frame, not use the stale sensor offset.
		// The sensor stores a Y offset that only updates when setAir/setGroundMode is called,
		// which doesn't happen when the angle changes on curved terrain.
		int wallRotation = (gSpeed < 0) ? 0x40 : 0xC0;  // +0x40 for left, -0x40 (0xC0) for right
		int wallRotatedAngle = (angle + wallRotation) & 0xFF;
		// Calculate dynamic offset: +8 only when (rotatedAngle & 0x38) == 0
		short dynamicYOffset = (short) (((wallRotatedAngle & 0x38) == 0) ? 8 : 0);

		// ROM doesn't use sensor active state - it checks gSpeed != 0 directly (already done above).
		// Our sensor active state is updated at end of frame, so may be stale here on first frame
		// of movement from standstill. Temporarily enable sensor to match ROM behavior.
		boolean wasActive = sensor.isActive();
		sensor.setActive(true);
		byte savedSensorY = sensor.getY();
		sensor.setOffset(sensor.getX(), (byte) 0);
		SensorResult result = sensor.scan(predictedDx, (short)(predictedDy + dynamicYOffset));
		sensor.setOffset(sensor.getX(), savedSensorY);
		sensor.setActive(wasActive);

		if (result == null || result.distance() >= 0) {
			return;
		}

		// ROM s2.asm:36503-36527: Wall collision response
		// ROM dispatches based on mode from ROTATED angle to handle velocity adjustment.
		// The angle was rotated by +/-0x40 based on direction (lines 36490-36497):
		//   gSpeed < 0 (left):  rotatedAngle = angle + 0x40
		//   gSpeed > 0 (right): rotatedAngle = angle - 0x40 (= angle + 0xC0)
		// Then mode = (rotatedAngle + 0x20) & 0xC0 (lines 36504-36505)
		//
		// Mode dispatch:
		//   Mode 0x00 (floor):     add.w d1,y_vel    - adjust Y, NO pushing, NO inertia=0
		//   Mode 0x40 (left wall): sub.w d1,x_vel    - adjust X + pushing + inertia=0
		//   Mode 0x80 (ceiling):   sub.w d1,y_vel    - adjust Y, NO pushing, NO inertia=0
		//   Mode 0xC0 (right wall): add.w d1,x_vel   - adjust X + pushing + inertia=0
		// ROM: asl.w #8,d1 — Java's byte-to-int sign-extension matches ROM's 16-bit
		// word behavior: negative distances (penetration) correctly produce negative adjustments.
		// The subsequent cast to short at usage sites truncates back to 16-bit range.
		int velocityAdjustment = result.distance() << 8;

		// Calculate rotated angle based on gSpeed direction (ROM s2.asm:36490-36497)
		int rotation = (gSpeed < 0) ? 0x40 : 0xC0;  // +0x40 for left, -0x40 (0xC0) for right
		int rotatedAngle = (angle + rotation) & 0xFF;

		// Calculate mode from rotated angle (ROM s2.asm:36504-36505)
		int mode = (rotatedAngle + 0x20) & 0xC0;

		switch (mode) {
			case 0x00:  // Floor mode: adjust Y velocity only
				// ROM s2.asm:36527 (loc_1A6BA) - add.w d1,y_vel
				sprite.setYSpeed((short) (sprite.getYSpeed() + velocityAdjustment));
				// NO gSpeed=0, NO pushing for floor mode
				break;

			case 0x40:  // Left wall mode: adjust X velocity, zero gSpeed, set pushing
				// ROM s2.asm:36521 (loc_1A6A8) - sub.w d1,x_vel
				sprite.setXSpeed((short) (sprite.getXSpeed() - velocityAdjustment));
				// ROM s2.asm:36522-36524 - bset pushing / move.w #0,inertia
				sprite.setGSpeed((short) 0);
				if (shouldSetGroundWallPush(mode)) {
					sprite.setPushing(true);
				}
				break;

			case 0x80:  // Ceiling mode: adjust Y velocity only
				// ROM s2.asm:36517 (loc_1A6A2) - sub.w d1,y_vel
				sprite.setYSpeed((short) (sprite.getYSpeed() - velocityAdjustment));
				// NO gSpeed=0, NO pushing for ceiling mode
				break;

			case 0xC0:  // Right wall mode: adjust X velocity, zero gSpeed, set pushing
				// ROM s2.asm:36511 - add.w d1,x_vel
				sprite.setXSpeed((short) (sprite.getXSpeed() + velocityAdjustment));
				// ROM s2.asm:36512-36513 - bset pushing / move.w #0,inertia
				sprite.setGSpeed((short) 0);
				if (shouldSetGroundWallPush(mode)) {
					sprite.setPushing(true);
				}
				break;
		}
	}

	private boolean shouldSetGroundWallPush(int mode) {
		CollisionRules rules = collisionRulesOrNull();
		if (rules == null || !rules.groundWallPushRequiresFacingIntoWall()) {
			return true;
		}
		boolean facingLeft = sprite.getDirection() == Direction.LEFT;
		return mode == 0x40 ? facingLeft : !facingLeft;
	}

	// ========================================
	// LANDING
	// ========================================

	/**
	 * Sonic_ResetOnFloor: clear landing-related flags (s2.asm:37744).
	 *
	 * @return whether the rolling-clear branch owned the landing's Walk write
	 */
	private boolean resetOnFloor() {
		// Don't reset states if player is controlled by an object (e.g., LauncherSpring).
		// The controlling object manages these states directly.
		if (sprite.isObjectControlSuppressesMovement()) {
			return false;
		}

		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		boolean preservePinballRoll = movementRules != null
				&& movementRules.landing().pinballLandingPreservesRoll();
		boolean preservePinballMode = movementRules != null
				&& movementRules.landing().pinballLandingPreservesPinballMode();
		boolean preserveObjectLandingRoll = sprite.consumePreserveRollingOnNextLanding();
		boolean skipLandingRollClear = sprite.getRolling()
				&& ((sprite.getPinballMode() && preservePinballRoll) || preserveObjectLandingRoll);
		boolean clearsRolling = sprite.getRolling() && !skipLandingRollClear;
		if (clearsRolling) {
			if (movementRules != null && movementRules.landing().landingRollClearUsesCurrentYRadiusDelta()) {
				int oldCentreY = sprite.getCentreY();
				int oldYRadius = sprite.getYRadius();
				sprite.setRolling(false);
				int radiusDelta = oldYRadius - sprite.getStandYRadius();
				if (((sprite.getAngle() + ANGLE_WALL_OFFSET) & ANGLE_WALL_MASK) != 0) {
					radiusDelta = -radiusDelta;
				}
				sprite.setCentreYPreserveSubpixel((short) (oldCentreY + radiusDelta));
			} else {
				sprite.setRolling(false);
				sprite.setY((short) (sprite.getY() - sprite.getRollHeightAdjustment()));
			}
			// Retail S1 writes id_Walk inside Sonic_ResetOnFloor's Status_Roll
			// branch before the word-only Y lift. Object/platform landings call
			// the same routine after Sonic_Animate, so the raw anim byte changes
			// immediately while the ball mapping remains for that frame
			// (01 Sonic.asm:1839-1864; SolidObject.asm:378-383).
			setWalkAnimationAfterRollingLanding(sprite);
		} else if (movementRules != null
				&& movementRules.landing().landingRollClearUsesCurrentYRadiusDelta()
				&& !skipLandingRollClear
				&& (sprite.getYRadius() != sprite.getStandYRadius()
				|| sprite.getXRadius() != sprite.getStandXRadius())) {
			// ROM Player_TouchFloor (sonic3k.asm:24341-24343 Sonic, 29134-29136 Tails)
			// unconditionally resets y_radius/x_radius to defaults on landing,
			// before the Status_Roll check. The roll branch only adjusts y_pos.
			// S3K Player_TouchFloor_Check_Spindash branches directly to loc_121D8
			// when spin_dash_flag is set (sonic3k.asm:24325-24327), skipping that
			// entire radius-reset body; preserve both rolling status and rolling radii.
			//
			// Engine's setRolling(false) above covers the rolling case via
			// applyStandingRadii. For non-rolling sprites whose radii were set
			// to non-default values by an object hook (e.g. CnzWireCage's
			// release path writes y_radius=$13/x_radius=9 unconditionally per
			// sonic3k.asm:69986-69987 / 70095-70096), engine landing must
			// likewise restore standing defaults so subsequent ground physics
			// uses Tails's own radii (CNZ1 trace post-F1815: ROM resets Tails
			// y_radius from $13 to $F at landing, leaving y_pos unchanged;
			// without this engine accumulates a 4-pixel y_pos drift).
			sprite.applyStandingRadii(false);
		}
		if (!(sprite.getRolling() && sprite.getPinballMode() && preservePinballMode)) {
			sprite.setPinballMode(false);
		}
		sprite.setAir(false);
		sprite.setPushing(false);
		sprite.setRollingJump(false);
		sprite.setJumping(false);
		// ROM: s2.asm:37769-37771 - reset flip/tumble state on landing
		sprite.setFlipAngle(0);
		sprite.setFlipType(0);
		sprite.setFlipTurned(false);
		sprite.setFlipsRemaining(0);
		// ROM: s2.asm:37772 - reset look delay counter on landing
		sprite.setLookDelayCounter((short) 0);
		return clearsRolling;
	}

	private void setWalkAnimationAfterRollingLanding(AbstractPlayableSprite sprite) {
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		if (movementRules != null
				&& movementRules.landingWalkWriteSkippedWhileSpindashing()
				&& sprite.getSpindash()) {
			if (movementRules.rollingJumpPinballGateRequiresSpindashFlag()) {
				if (sprite.getAnimationProfile() instanceof ScriptedVelocityAnimationProfile velocityProfile
						&& sprite.getAnimationId() == velocityProfile.getSpindashAnimId()) {
					// S2 aliases pinball_mode to spindash_flag. The engine keeps Obj84's
					// forced-roll guard separate, but an actively charging Spindash animation
					// still proves that the native byte is live, so ResetOnFloor skips Walk.
					return;
				}
			} else {
				// S3K keeps a dedicated spin_dash_flag with no pinball aliasing, so
				// Player_TouchFloor_Check_Spindash's `tst.b spin_dash_flag(a0)` is the
				// whole predicate (sonic3k.asm:24325-24329; Tails :29123-29127). No
				// animation condition: the ROM does not test anim here.
				return;
			}
		}
		int walkAnimationId = sprite.resolveAnimationId(CanonicalAnimation.WALK);
		if (walkAnimationId >= 0) {
			// Looking/crouching are engine-side projections of native anim writes,
			// not independent ROM status bits. Player_TouchFloor's explicit Walk
			// store replaces either projection on the landing frame; otherwise a
			// stale pre-air LookUp flag can mask the new byte (CNZ2 Tails f19845).
			sprite.setLookingUp(false);
			sprite.setCrouching(false);
			sprite.setAnimationId(walkAnimationId);
		}
	}

	/** Landing gSpeed calculation (s2.asm:37584) */
	private void calculateLanding(AbstractPlayableSprite sprite) {
		// ROM: Sonic_HurtStop — when landing from hurt state, zero all velocity.
		// Must check before resetOnFloor() which clears the hurt flag via setAir(false).
		boolean wasHurt = sprite.isHurt();
		int hurtFallAnimationId = sprite.resolveAnimationId(CanonicalAnimation.HURT_FALL);
		boolean forcedHurtFall = hurtFallAnimationId >= 0
				&& sprite.getForcedAnimationId() == hurtFallAnimationId;
		// Save doubleJumpFlag BEFORE resetOnFloor() clears it via setAir(false).
		// ROM (s3.asm:21849-21859) tests the flag before clearing.
		int savedDoubleJumpFlag = sprite.getDoubleJumpFlag();
		boolean resetOwnedWalkPublication = resetOnFloor();
		if (forcedHurtFall) {
			// Object/event owners use the engine forced slot to retain a native
			// HurtFall byte through the airborne player passes. Player_TouchFloor
			// now owns anim=Walk, so release only that semantic override before
			// this frame reaches Animate.
			sprite.setForcedAnimationId(-1);
		}
		// Sonic_Floor writes id_Walk immediately after ResetOnFloor on every
		// accepted floor landing, including a non-rolling fall that carried Wait
		// through the air. Object/platform landings do not pass through this
		// terrain owner and retain their separate post-animation timing (S1
		// 01 Sonic.asm:1527-1602).
		if (!resetOwnedWalkPublication && !sprite.getPinballMode()) {
			setWalkAnimationAfterRollingLanding(sprite);
		}
		if (wasHurt) {
			sprite.setGSpeed((short) 0);
			sprite.setXSpeed((short) 0);
			sprite.setYSpeed((short) 0);
			return;
		}

		short ySpeed = sprite.getYSpeed();
		short xSpeed = sprite.getXSpeed();
		int angle = sprite.getAngle() & 0xFF;

		// ROM processes all landings through angle classification, no early return for ySpeed <= 0
		boolean isSteep = isSteepAngle(angle);

		if (isSteep) {
			// Steep angles: gSpeed from signed Y velocity
			// ROM (s2.asm:37608-37612):
			//   move.w y_vel(a0),inertia(a0)  ; gSpeed = SIGNED y_vel
			//   tst.b  d3
			//   bpl.s  return_1AF8A
			//   neg.w  inertia(a0)            ; Negate if angle >= 0x80
			// Cap check uses signed comparison (ble = branch if less or equal, signed)
			sprite.setXSpeed((short) 0);
			short gSpeed = ySpeed;
			if (gSpeed > YSPEED_LANDING_CAP) {
				gSpeed = YSPEED_LANDING_CAP;
				// ROM (s2.asm:37605-37608) writes capped value back to y_vel:
				//   cmpi.w  #$FC0,y_vel(a0)
				//   ble.s   loc_1AF7C
				//   move.w  #$FC0,y_vel(a0)
				sprite.setYSpeed(gSpeed);
			}
			if ((angle & 0x80) != 0) gSpeed = (short) -gSpeed;
			// S3K's steep air-floor path calls Player_TouchFloor_Check_Spindash
			// before copying y_vel to ground_vel at loc_11FC2 (sonic3k.asm:
			// 24112-24117). BubbleShield_Bounce can rewrite y_vel there, so
			// inertia must observe the post-bounce velocity.
			applyPostLandingAbilities(sprite, savedDoubleJumpFlag);
			gSpeed = sprite.getYSpeed();
			if ((angle & 0x80) != 0) gSpeed = (short) -gSpeed;
			sprite.setGSpeed(gSpeed);
			// ROM does NOT zero y_vel for steep landings (s2.asm:37612-37615 just returns)
		} else {
			boolean isFlat = isFlatAngle(angle);
			if (isFlat) {
				// Flat angles: gSpeed from X velocity
				sprite.setGSpeed(xSpeed);
				sprite.setYSpeed((short) 0);
			} else {
				// Moderate angles: gSpeed from Y velocity / 2
				// ROM (s2.asm:37592): asr y_vel(a0) - arithmetic shift right preserves sign
				// Then (s2.asm:37609-37612): gSpeed = y_vel, negate if angle >= 0x80
				short halfYSpeed = (short) (ySpeed >> 1);
				sprite.setYSpeed(halfYSpeed);
				short gSpeed = halfYSpeed;
				if ((angle & 0x80) != 0) gSpeed = (short) -gSpeed;
				// S3K loc_11FC2 writes ground_vel after
				// Player_TouchFloor_Check_Spindash, so bubble bounce y_vel
				// changes feed inertia on moderate slopes too.
				applyPostLandingAbilities(sprite, savedDoubleJumpFlag);
				gSpeed = sprite.getYSpeed();
				if ((angle & 0x80) != 0) gSpeed = (short) -gSpeed;
				sprite.setGSpeed(gSpeed);
				// ROM does NOT zero y_vel for moderate angles - it leaves the halved value
			}
		}
		if (isFlatAngle(angle)) {
			applyPostLandingAbilities(sprite, savedDoubleJumpFlag);
		}
	}

	/**
	 * ROM direct HitFloor landing for movement quadrants 0x40/0xC0.
	 * <p>
	 * S1/S2 write {@code y_vel = 0} and {@code inertia = x_vel} after ResetOnFloor;
	 * S3K's direct floor path does the same before falling through its TouchFloor tail.
	 */
    private void calculateDirectFloorLanding(AbstractPlayableSprite sprite) {
        boolean wasHurt = sprite.isHurt();
        int savedDoubleJumpFlag = sprite.getDoubleJumpFlag();
        boolean resetOwnedWalkPublication = resetOnFloor();
        if (!resetOwnedWalkPublication && !sprite.getPinballMode()) {
            setWalkAnimationAfterRollingLanding(sprite);
        }
        if (wasHurt) {
            // ROM Sonic_HurtStop / Tails hurt-stop zeroes all velocity when the
            // hurt routine touches floor before returning to normal control
            // (sonic3k.asm:24449-24467, 29194-29209). The direct floor path
            // must not rederive inertia from the hurt knockback x_vel.
            sprite.setGSpeed((short) 0);
            sprite.setXSpeed((short) 0);
            sprite.setYSpeed((short) 0);
            return;
        }
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed(sprite.getXSpeed());
        applyPostLandingAbilities(sprite, savedDoubleJumpFlag);
    }

	private void applyPostLandingAbilities(AbstractPlayableSprite sprite, int savedDoubleJumpFlag) {
		// Bubble shield bounce check (s3.asm:21849-21859 Player_TouchFloor tail)
		// ROM: Only Sonic (character_id 0) can trigger this — Knuckles/Tails have
		// separate jump code that never sets doubleJumpFlag via bubbleShieldBounce.
		PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
		if (capabilityRules != null && capabilityRules.elementalShieldsEnabled() && savedDoubleJumpFlag != 0
				&& sprite.getSecondaryAbility() == SecondaryAbility.INSTA_SHIELD) {
			if (sprite.hasShield() && sprite.getShieldType() == ShieldType.BUBBLE) {
				applyBubbleShieldBounce(sprite);
			}
			// Flag already cleared by resetOnFloor→setAir(false), no extra clear needed
		}
	}

	public void applyPostObjectLandingAbilities(AbstractPlayableSprite sprite, int savedDoubleJumpFlag) {
		applyPostLandingAbilities(sprite, savedDoubleJumpFlag);
	}

	/**
	 * BubbleShield_Bounce: Re-launch player perpendicular to surface (s3.asm:21866-21900).
	 * On flat ground (angle 0x00): bounces straight up at 0x780 velocity.
	 * On slopes: bounce direction follows surface normal.
	 * Underwater: reduced velocity (0x400).
	 */
	private void applyBubbleShieldBounce(AbstractPlayableSprite sprite) {
		int velocity = sprite.isInWater() ? 0x400 : 0x780;
		int angle = sprite.getAngle() & 0xFF;
		// Rotate 90° CCW to get surface normal direction (s3.asm:21873: subi.b #$40,d0)
		int rotated = (angle - 0x40) & 0xFF;
		int sin = TrigLookupTable.sinHex(rotated);
		int cos = TrigLookupTable.cosHex(rotated);
		sprite.setXSpeed((short) (sprite.getXSpeed() + ((cos * velocity) >> 8)));
		sprite.setYSpeed((short) (sprite.getYSpeed() + ((sin * velocity) >> 8)));
		// Re-launch into air (s3.asm:21885-21892)
		sprite.setAir(true);
		sprite.setJumping(true);
		sprite.setPushing(false);
		sprite.setAnimationId(2);
		if (!sprite.getRolling()) {
			sprite.setRolling(true);
			sprite.setY((short) (sprite.getY() + sprite.getRollHeightAdjustment()));
		}
		audioManager.playSfx(GameSound.BUBBLE_ATTACK);
		var shield = sprite.getShieldObject();
		if (shield != null) shield.onAbilityActivated(2);
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

	private boolean shouldTriggerGroundSkid(short adjustedGSpeed, boolean turningRight) {
		int angleCheck = ((sprite.getAngle() & 0xFF) + ANGLE_SLOPE_OFFSET) & ANGLE_SLOPE_MASK;
		if (angleCheck != 0) {
			return false;
		}
		// Both S3K braking directions test signed flip_type after the speed
		// threshold and return before writing Stop or changing facing when it is
		// negative (sonic3k.asm:22840-22875, 22906-22941). Rolling drums use
		// flip_type=$80 while the ordinary ground-movement slot still executes.
		if ((sprite.getFlipType() & 0x80) != 0) {
			return false;
		}

		// Retail S1/S2/S3K use the non-FixBugs path here: after storing the
		// adjusted ground speed, the angle test writes only d0's low byte before
		// the skid threshold cmp.w. Preserve that high-byte/zero-low-byte compare
		// so marginal counter-direction movement flips facing on the same frame
		// as the ROM.
		short compareSpeed = (short) (adjustedGSpeed & 0xFF00);
		return turningRight
				? compareSpeed <= -SKID_SPEED_THRESHOLD
				: compareSpeed >= SKID_SPEED_THRESHOLD;
	}

	// ========================================
	// UTILITY HELPERS
	// ========================================

	/**
	 * Bootstrap-only seed for the jump-press edge tracker. Trace replay's
	 * title-card prelude does not invoke this manager (sidekick-only warmup),
	 * so {@code jumpPrevious} stays {@code false}. If the BK2 movie was
	 * already holding A/B/C across the title-card boundary, the first
	 * {@link #storeInputState} call on the first compared frame would
	 * compute {@code inputJumpPress = (jump && !false) = true} and fire
	 * {@code doJump} one frame before the ROM (s2.asm:36253-36260
	 * Obj01_Control reads {@code Ctrl_1_Press}; V-int's edge detector
	 * already cleared the bit because the button was held throughout
	 * title-card frames).
	 *
	 * <p>Call once during {@code TraceReplaySessionBootstrap.applyBootstrap}
	 * with the last title-card-frame BK2 jump state to mirror the ROM
	 * post-title-card edge state. Comparison-only — no trace data is read
	 * or written by this hook.
	 */
	public void primeJumpPreviousForBootstrap(boolean jumpHeldAtPriorBk2Frame) {
		this.jumpPrevious = jumpHeldAtPriorBk2Frame;
	}

	private void storeInputState(boolean up, boolean down, boolean left, boolean right, boolean jump) {
		inputUp = up;
		inputDown = down;
		inputLeft = left;
		inputRight = right;
		inputJump = sprite.isHurt() ? false : jump;
		boolean suppressJumpPress = sprite.consumeSuppressNextJumpPress();
		if (sprite.isCpuControlled()) {
			// ROM Tails CPU writes the whole delayed Ctrl_1_Logical word into
			// Ctrl_2_Logical (s2.asm:38939-38946, 39025-39027), so the held bits
			// and the press low-byte both come from the leader's delayed sample.
			// In the engine, the press edge is conveyed via forcedJumpPress
			// (set by SpriteManager when SidekickCpuController.getInputJumpPress()
			// is true). Computing a fresh (jump && !jumpPrevious) edge against
			// Tails' own jumpPrevious would manufacture a spurious press whenever
			// the leader's delayed held bit transitions from clear to set without
			// a corresponding press edge — the bootstrap held-jump case is the
			// canonical example (CNZ2 trace frame 16: leader's BK2 prelude held
			// jump, Tails reads the held bit but recorded_press=false). The CPU
			// controller is the authoritative source for Tails' Ctrl_2_Press low
			// byte, so consume the forced press signal directly.
			inputJumpPress = sprite.isForcedJumpPress() && !suppressJumpPress;
		} else {
			// The ROM never derives the press bit from the logical held bit. The
			// pressed byte is produced once per frame by Poll_Controller, straight
			// from the hardware pad and independently of any control lock:
			//   move.b (a0),d1 / eor.b d0,d1 / move.b d0,(a0)+ / and.b d0,d1
			//   / move.b d1,(a0)+            (docs/skdisasm/sonic3k.asm:1288-1305,
			// mirrored by S1 ReadJoypads and S2 s2.asm ReadJoypads).
			// Sonic_Control then copies the WHOLE word - held byte and already
			// computed pressed byte together - into Ctrl_1_logical, and skips the
			// copy entirely while Ctrl_1_locked is set
			// (docs/skdisasm/sonic3k.asm:21968-21971 loc_10BF0, :21541-21545
			// loc_10760). So a button that was already held when the lock lifted
			// contributes NO press on the unlock frame: its edge was consumed by
			// Poll_Controller several frames earlier, while control was locked.
			// Deriving the edge from the engine's filtered `jump` bit instead
			// manufactured exactly that press, because the filtered bit is forced
			// false for the whole lock (AIZ1 intro: A held from the recording's
			// frame 1095, control returns at 1097, the ROM ducks via SonicKnux_Roll
			// :23240 while the engine fired Sonic_Jump :23288 and went airborne
			// rolling 5px low).
			inputJumpPress = ((jump && sprite.isJumpJustPressed()) && !suppressJumpPress)
					|| sprite.isForcedJumpPress();
		}
		sprite.setForcedJumpPress(false); // consume one-shot signal
		jumpPrevious = jump;
	}

	private void handleDebugMovement(boolean up, boolean down, boolean left, boolean right, boolean speedUp,
			boolean slowDown) {
		double multiplier = 1.0;
		if (speedUp) {
			multiplier *= 2.0;
		}
		if (slowDown) {
			multiplier *= 0.5;
		}
		int moveSpeed = (int) Math.max(1, Math.round(DEBUG_MOVE_SPEED * multiplier));

		if (left) sprite.setX((short) (sprite.getX() - moveSpeed));
		if (right) sprite.setX((short) (sprite.getX() + moveSpeed));
		if (up) sprite.setY((short) (sprite.getY() - moveSpeed));
		if (down) sprite.setY((short) (sprite.getY() + moveSpeed));
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
		short gSpeed = sprite.getGSpeed();
		// S2/S3K MoveLeft/MoveRight clear Status_Push only after the
		// opposite-direction deceleration path is skipped. With positive
		// ground_vel, MoveLeft decelerates and returns before bset Status_Facing /
		// bclr Status_Push; with negative ground_vel, MoveRight does the same.
		// See S3K Tails MoveLeft/MoveRight at sonic3k.asm:27797-27815 and
		// 28094-28109. Do not pre-clear push while the player is still braking
		// from the opposite direction.
		//
		// On the facing flip these routines also set prev_anim=Run/1
		// (docs/s1disasm/_incObj/01 Sonic.asm:641-645,707-710;
		// sonic3k.asm:28041 sub_14C20,
		// 28109 sub_14CAC; s2 equivalents), which
		// makes the SAME frame's Animate_Sonic/Animate_Tails clear Status_Push
		// when anim != prev_anim (sonic3k.asm:29359-29364,29681-29686). That
		// frame-end animation clear is independent of whether the character was
		// already pushing when the flip happened: it removes any Status_Push the
		// ground-wall collision sets later in the same frame. Arm the post-ground-
		// wall clear on any grounded, non-rolling facing flip, not only when push
		// was already set before the wall pass. (AIZ2 underwater CPU-Tails wall
		// bounce: the flip frame's wall hit re-sets push, but ROM's prev_anim
		// sentinel still clears it that frame, so push is gone entering the next
		// no-hit frame.)
		//
		// Airborne facing changes are handled by Sonic_ChgJumpDir /
		// Tails_ChgJumpDir and S3K *_InputAcceleration_Freespace; those routines
		// change Status_Facing without clearing Status_Push (s2.asm:40184-40211,
		// sonic3k.asm:28330-28363). HTZ2 f4526 depends on that stale push bit
		// surviving after Obj30 drops CPU Tails into the air.
		boolean groundedFacingFlip = !sprite.getAir() && !sprite.getRolling();
		if (left && !right && sprite.getDirection() == Direction.RIGHT && gSpeed <= 0 && !sprite.getRolling()) {
			if (groundedFacingFlip) {
				sprite.setPushing(false);
				forceGroundFacingFlipAnimationRestart();
				facingFlipForcesPushClearAfterGroundWall = true;
			}
		} else if (right && !left && sprite.getDirection() == Direction.LEFT && gSpeed >= 0 && !sprite.getRolling()) {
			if (groundedFacingFlip) {
				sprite.setPushing(false);
				forceGroundFacingFlipAnimationRestart();
				facingFlipForcesPushClearAfterGroundWall = true;
			}
		}
	}

	private void applyUnderwaterAirGravityReduction() {
		// Underwater gravity reduction
		// Normal airborne: net gravity = 0x38 - 0x28 = 0x10 (s2.asm:39620-39623)
		// Hurt airborne:   net gravity = 0x30 - 0x20 = 0x10 (s2.asm:41066-41069, s1:01 Sonic.asm:1410)
		// The ROM hurt routine (Obj01_Hurt) uses a separate code path with $20 reduction,
		// NOT the $28 used in Obj01_MdAir/MdJump. All three games (S1/S2/S3K) are identical.
		// S3K Tails_FlyingSwimming owns its +$08 flight/swim gravity and skips
		// this generic underwater subtraction (sonic3k.asm:27570, 27633).
		if (!sprite.isInWater() || isTailsFlightPhysicsActive(sprite)) {
			return;
		}
		short reduction = 0x28;
		var modifiers = sprite.getPhysicsModifiers();
		if (modifiers != null) {
			reduction = sprite.isHurt()
					? modifiers.waterHurtGravityReduction()
					: modifiers.waterGravityReduction();
		} else if (sprite.isHurt()) {
			reduction = 0x20;
		}
		sprite.setYSpeed((short) (sprite.getYSpeed() - reduction));
	}

	private void forceGroundFacingFlipAnimationRestart() {
		// The prev_anim=Run sentinel restarts the walk script in every retail
		// game. S1's FixBugs guard only omits Animate_Sonic's Status_Push clear;
		// it does not omit MoveLeft/MoveRight's prev_anim write or the subsequent
		// obAniFrame/obTimeFrame reset
		// (docs/s1disasm/_incObj/01 Sonic.asm:634-659,704-723,2174-2182).
		sprite.publishRunAsPreviousAnimation();
	}

	private void clearFacingFlipPushAfterGroundWallCollision() {
		if (!facingFlipForcesPushClearAfterGroundWall) {
			return;
		}
		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		if (animationRules == null || !animationRules.animationChangeClearsPush()) {
			return;
		}
		// S2/S3K MoveLeft/MoveRight clear pushing and force prev_anim=Run when
		// facing flips (s2.asm:36569-36570,36632-36633,39541-39542,39604-39605;
		// sonic3k.asm:27814-27815,28108-28109). Their animation routines then
		// clear pushing when anim differs from prev_anim (s2.asm:38033-38038,
		// 40879-40884; sonic3k.asm:29359-29364,29681-29686), after ground-wall
		// collision can set Status_Push. S1 leaves the animation clear behind a
		// FixBugs guard, so PlayerAnimationRules.animationChangeClearsPush() gates it.
		sprite.setPushing(false);
	}

	private void clearStaleCpuPushVelocityBeforeGroundMove() {
		if (!sprite.isCpuControlled()
				|| sprite.getAir()
				|| !sprite.getPushing()
				|| sprite.getGSpeed() == 0
				|| inputLeft
				|| inputRight) {
			return;
		}
		CollisionRules rules = collisionRulesOrNull();
		if (rules == null || !rules.sidekickClearsStalePushVelocityBeforeGroundMove()) {
			return;
		}
		SidekickCpuController cpu = sprite.getCpuController();
		if (cpu == null || !cpu.usedObjectOrderGracePushBypassThisFrame()) {
			return;
		}
		// S3K loc_13DD0's live Status_Push bypass preserves the already-loaded
		// Ctrl_2 sample, and Tails_InputAcceleration_Path then decelerates and
		// projects ground_vel before any push-collision clear
		// (sonic3k.asm:26702-26705,26775-26785,27947-28017). Do not pre-clear
		// that ROM-visible current-push path or the same MGZ grace continuation:
		// MGZ1 F1466-F1470 needs no-input deceleration from $00E4 through $00A4
		// instead of an immediate zero. This clear is only for provider-approved
		// object-order bridges after the live push bit has locally dropped, where
		// there is no direct ROM branch and stale inertia would otherwise advance
		// before the blocking pass catches up.
		sprite.setXSpeed((short) 0);
		sprite.setGSpeed((short) 0);
	}

	private void handleSkid() {
		skidAnimationRefreshedThisFrame = true;
		if (!sprite.getSkidding()) sprite.setSkidding(true);
		audioManager.playSfx(GameSound.SKID);
		advanceSkidDustTimer();
	}

	boolean isSkidAnimationRefreshedThisFrame() {
		return skidAnimationRefreshedThisFrame;
	}

	void advanceFixedSkidDustWhileStopAnimPersists() {
		advanceFixedSkidDustWhileStopAnimPersists(Integer.MIN_VALUE);
	}

	void advanceFixedSkidDustWhileStopAnimPersists(int frameCounter) {
		PowerUpRules rules = powerUpRulesOrNull();
		if (rules == null || !rules.fixedSkidDustAllocatesAfterDynamicObjectPass()) {
			return;
		}
		if (!(sprite.getAnimationProfile() instanceof ScriptedVelocityAnimationProfile profile)) {
			return;
		}
		if (sprite.isHurt() || sprite.getDead()) {
			sprite.setFixedSkidDustActive(false);
			return;
		}
		int skidAnimId = profile.getSkidAnimId();
		if (skidAnimId < 0 || sprite.getAnimationId() != skidAnimId) {
			fixedSkidDustTickPending = false;
			sprite.setSkidDustTimer(0);
			sprite.setFixedSkidDustActive(false);
			return;
		}
		if (frameCounter != Integer.MIN_VALUE && lastFixedSkidDustTickFrame == frameCounter) {
			return;
		}
		lastFixedSkidDustTickFrame = frameCounter;
		fixedSkidDustTickPending = false;
		processingFixedSkidDustTick = true;
		try {
			advanceSkidDustTimer();
		} finally {
			processingFixedSkidDustTick = false;
		}
	}

	public int captureLastFixedSkidDustTickFrame() {
		return lastFixedSkidDustTickFrame;
	}

	public void restoreLastFixedSkidDustTickFrame(int frameCounter) {
		lastFixedSkidDustTickFrame = frameCounter;
	}

	private void advanceSkidDustTimer() {
		// ROM Obj08_CheckSkid keeps ticking the fixed Sonic_Dust/Tails_Dust
		// object while the parent remains in the Stop animation; entering
		// Sonic_TurnLeft/Right only switches the dust object into that routine
		// and seeds mapping_frame=$15 (docs/s2disasm/s2.asm:36927-36929,
		// 36988-36990, 42759-42797).
		PowerUpRules rules = powerUpRulesOrNull();
		sprite.setFixedSkidDustActive(true);
		if (!processingFixedSkidDustTick
				&& rules != null
				&& rules.fixedSkidDustAllocatesAfterDynamicObjectPass()) {
			fixedSkidDustTickPending = true;
			return;
		}
		int dustTimer = sprite.getSkidDustTimer() - 1;
		if (dustTimer < 0) {
			dustTimer = 3;
			SkidDustObjectInstance.spawn(sprite);
		}
		sprite.setSkidDustTimer(dustTimer);
	}

	private void updateCrouchState(boolean moveLockActiveAtDispatch) {
		// S3K: allow ducking while moving at speeds below the roll threshold.
		// ROM: sonic3k.asm:23223-23240 (SonicKnux_Roll) — down pressed + |gSpeed| < $100
		// + not left/right + not on object → enter duck animation.
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		short movingThreshold = (movementRules != null) ? movementRules.movingCrouchThreshold() : 0;
		boolean nativePlayerSlotOnObject = sprite.isOnObject() || sprite.getOnObjectAtFrameStart();
		int movingCrouchSpeed = preRollGroundSpeed != NO_PRE_FRICTION_SNAPSHOT
				? preRollGroundSpeed
				: sprite.getGSpeed();
		if (movingThreshold > 0 && inputDown && !inputLeft && !inputRight
				&& !sprite.getAir() && !sprite.getRolling() && !sprite.getSpindash()
				&& Math.abs(movingCrouchSpeed) < movingThreshold) {
			// Sonic_Move branches past every animation write while move_lock is
			// active. SonicKnux_Roll then tests the still-live Status_OnObj bit
			// before writing Duck (sonic3k.asm:22459,23263-23265). The engine
			// temporarily clears live object support for same-frame revalidation,
			// so use the player-slot entry snapshot for that native read.
			if (nativePlayerSlotOnObject
					&& sprite.getAnimationManager().isGroundMovementAnimationSuppressed()) {
				sprite.setCrouching(false);
				sprite.setBalanceState(0);
				return;
			}
			if (nativePlayerSlotOnObject) {
				// Without move_lock, Sonic_Move already owns the ordinary Duck
				// write even while the player stands on an object's interior.
				// Continue into the shared standing-still decision below.
			} else {
			// SonicKnux_Roll/Tails_Roll run after the ground-input routine and
			// unconditionally write Duck at low speed while Down is held. That
			// later write supersedes the Balance animation selected by
			// Sonic_Move/Tails_Move, but it does not undo that routine's facing
			// write (sonic3k.asm:23223-23240,28458-28483).
			if (preMoveBalanceEvaluated && preMoveBalanceState != 0) {
				sprite.setDirection(preMoveBalanceDirection);
			}
			sprite.setCrouching(true);
			sprite.setBalanceState(0);
			return;
			}
		}

		// ROM s2.asm:36237-36245: Balance/crouch/lookup checks happen when standing still
		// on flat ground (angle + 0x20 & 0xC0 == 0) and not moving (inertia == 0).
		// ROM tests inertia BEFORE ground friction (S1 _incObj/01 Sonic.asm:373,
		// S2 s2.asm:36568 Sonic / 39689 Tails, friction at s2.asm:36768-36786), so use
		// the pre-friction snapshot captured in doGroundMove. Without it, a player
		// whose inertia decays to 0 this frame ducks one frame early; for S2 CPU Tails
		// that early duck flips Tails_CheckSpindash ahead of Tails_Jump (s2.asm:
		// 39594-39596) so Tails spindashes instead of jumping (MCZ1 f2362).
		short standingInertia = preFrictionGroundSpeed != NO_PRE_FRICTION_SNAPSHOT
				? (short) preFrictionGroundSpeed
				: sprite.getGSpeed();
		boolean groundedMoveDispatchRan = preFrictionGroundSpeed != NO_PRE_FRICTION_SNAPSHOT;
		boolean terrainMoveDispatchOwnsDetachAnimation = groundedMoveDispatchRan
				&& !sprite.getOnObjectAtFrameStart()
				&& !sprite.getOnObjectAtPreviousFrameStart();
		boolean standingStill = standingInertia == 0 && isOnFlatGround()
				&& (terrainMoveDispatchOwnsDetachAnimation || !sprite.getAir())
				&& !sprite.getRolling() && !sprite.getSpindash();

		// Update balance state (checks for ledge edges)
		// ROM: Balance check happens before crouch/lookup in Obj01_LookUpDown
		if (!moveLockActiveAtDispatch && standingStill
				&& ((!inputLeft && !inputRight) || directionalBrakeReachedZero)) {
			if (preMoveBalanceEvaluated) {
				// doGroundMove evaluated the ROM balance branch before SpeedToPos
				// and AnglePos. Reuse that result even when it was "not balancing":
				// AnglePos has since refreshed Primary/Secondary_Angle for the
				// player-tail next_tilt/tilt copy, and recomputing here would expose
				// those new bytes one dispatch early (sonic3k.asm:27840-27849,
				// 26215-26244).
				sprite.setBalanceState(preMoveBalanceState);
				sprite.setDirection(preMoveBalanceDirection);
			} else {
				updateBalanceState();
			}
		} else {
			sprite.setBalanceState(0);
		}

		// Crouch check - only if not balancing
		// ROM: You can't crouch while balancing (balance animation takes priority)
		boolean crouching = inputDown && !inputLeft && !inputRight
				&& standingStill && !sprite.isBalancing();
		sprite.setCrouching(crouching);
	}

    private void applyUpwardVelocityCap() {
        if (sprite.getAir() && !jumpPressed && !sprite.getPinballMode()) {
            if (sprite.getYSpeed() < UPWARD_VELOCITY_CAP) {
                sprite.setYSpeed((short) UPWARD_VELOCITY_CAP);
            }
        }
    }

	private short applyDeathMovement() {
		// Once the corpse has left the screen the ROM leaves the falling routine
		// behind entirely: S1 Sonic_ResetLevel (docs/s1disasm/_incObj/01
		// Sonic.asm:2062-2073), S2 Obj01_Gone (docs/s2disasm/s2.asm:38345-38352)
		// and S3K loc_1257C (docs/skdisasm/sonic3k.asm:24697-24706) only count the
		// restart delay down. No gravity, no ObjectFall, so the recorded position,
		// sub-position and y_vel all stand still until the level restarts (S1 MZ1
		// rows 3,331-3,390).
		if (sprite.isInDeathRestartRoutine()) {
			tickRestartCountdown();
			return 0;
		}

		// Sonic_HandleDeath / CheckGameOver / sub_123C2 run BEFORE the fall, so
		// the row they compare is the position this frame's fall starts from, and
		// S1's gravity cancellation is still in y_vel when the fall applies it.
		if (hasFallenPastDeathRestartRow()) {
			PlayerMovementRules movementRules = playerMovementRulesOrNull();
			if (movementRules != null && movementRules.levelBoundary() != null
					&& movementRules.levelBoundary().deathFallRestartHandoffCancelsGravity()) {
				// S1 alone writes y_vel = -gravity so the ObjectFall that follows
				// moves the corpse back by exactly what gravity is about to add
				// and leaves it stopped (01 Sonic.asm:2010).
				sprite.setYSpeed((short) -sprite.getGravity());
			}
			enterDeathRestartRoutine();
		}

		short oldYSpeed = sprite.getYSpeed();
		applyGravity();  // Gated on isObjectControlled(); a controlled sprite never enters the death routine anyway but keep gates consistent
		sprite.setGSpeed((short) 0);
		sprite.setXSpeed((short) 0);
		return oldYSpeed;
	}

	/**
	 * ROM death-fall restart test: {@code cmp.w y_pos(a0),d0} against a
	 * camera-derived row plus {@code $100}, where the row itself is per game
	 * (see {@link PlayerLevelBoundaryRules}). The comparison is against the ROM
	 * y_pos word, i.e. centre-Y, not the render bounds.
	 */
	private boolean hasFallenPastDeathRestartRow() {
		Camera camera = camera();
		if (camera == null) {
			return false;
		}
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		PlayerLevelBoundaryRules boundaryRules =
				movementRules != null ? movementRules.levelBoundary() : null;
		int reference = boundaryRules != null
				&& boundaryRules.deathFallBottomReferenceIsCameraBottomBoundary()
				? camera.getMaxY()
				: camera.getY();
		return sprite.getCentreY() > reference + 0x100;
	}

	/**
	 * The death row crossing's own bookkeeping, identical in all three games.
	 *
	 * <p>S1 {@code Sonic_HandleDeath} (docs/s1disasm/_incObj/01
	 * Sonic.asm:2011-2049), S2 {@code CheckGameOver}
	 * (docs/s2disasm/s2.asm:38279-38316) and S3K {@code loc_12432}
	 * (docs/skdisasm/sonic3k.asm:24581-24616) all do the same three things on
	 * this one frame: enter the post-fall routine with {@code restartime} armed
	 * at 60, raise the lives-counter HUD flag and subtract the life, and then
	 * rewrite {@code restartime} to zero if that subtraction produced a game
	 * over, or if the time-over flag was already set. The life therefore comes
	 * off here, not 60 frames later, and the two zero-delay cases never restart
	 * the level at all: {@code Sonic_ResetLevel} / {@code Obj01_Gone} /
	 * {@code loc_1257C} only write the restart flag from a non-zero delay.
	 *
	 * <p>The two zero-delay cases stay distinct in the ROM even though they
	 * share the delay: the game-over branch also clears the time-over flag and
	 * shows GAME/OVER, while the time-over branch keeps it and shows TIME/OVER,
	 * which is what later lets {@code Over_Wait} restart the level after a time
	 * over but send a game over to the continue screen
	 * (docs/s1disasm/_incObj/39 Game Over.asm:57-88).
	 */
	private void enterDeathRestartRoutine() {
		if (sprite.isCpuControlled() && sprite.getCpuController() != null) {
			// A CPU sidekick's crossing despawns it and never reaches the life
			// counter: S3K sends Tails to routine 2 instead
			// (docs/skdisasm/sonic3k.asm:24572-24578).
			sprite.enterDeathRestartRoutine(DEATH_RESTART_DELAY_FRAMES);
			return;
		}
		GameStateManager gameState = gameState();
		if (gameState != null) {
			gameState.loseLife();
		}
		boolean gameOver = gameState != null && gameState.getLives() == 0;
		boolean timeOver = !gameOver && isTimeOverFlagged();
		sprite.enterDeathRestartRoutine(
				gameOver || timeOver ? 0 : DEATH_RESTART_DELAY_FRAMES);
		if (gameOver || timeOver) {
			// The same frame loads the GAME/OVER or TIME/OVER object pair, plays
			// the game over music and queues the card's art (S1 01
			// Sonic.asm:2019-2049, S2 s2.asm:38284-38316, S3K
			// sonic3k.asm:24588-24616); the pair then owns everything downstream.
			GameOverFlowProvider.begin(levelManager(), timeOver);
		}
	}

	/**
	 * The ROM's {@code Time_over_flag} / {@code f_timeover}, owned by the level's
	 * timer rather than by the player: the HUD's {@code TimeOver} routine both
	 * kills the player and raises the flag when the timer reaches 9:59:59
	 * (docs/s1disasm/_inc/HUD Update.asm:103-111).
	 */
	private boolean isTimeOverFlagged() {
		LevelState levelState = sprite.currentLevelState();
		return levelState != null && levelState.isTimeOver();
	}

	private void tickRestartCountdown() {
		if (!sprite.tickDeathCountdown()) {
			return;
		}
		if (sprite.isCpuControlled() && sprite.getCpuController() != null) {
			// CPU-controlled sprites (Tails) despawn and respawn near the main player
			// instead of causing a level reset
			sprite.getCpuController().despawn();
		} else {
			levelManager().requestRespawn();
		}
	}

	/**
	 * Accelerate left (negative direction) with per-game speed capping.
	 *
	 * S1 (inputAlwaysCapsGroundSpeed=true): Always clamps to -max, even if
	 * speed was already beyond max from slopes/springs.
	 * ROM: s1disasm/_incObj/01 Sonic.asm:507-512 — unconditional clamp.
	 *
	 * S2/S3K (inputAlwaysCapsGroundSpeed=false): Preserves speeds above max.
	 * ROM: s2.asm:36547-36556 — undo accel if was already >= max.
	 */
	private short accelerateLeft(short gSpeed, short runAccel, short max) {
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		boolean alwaysCap = movementRules != null && movementRules.inputAlwaysCapsGroundSpeed();

		if (alwaysCap) {
			// S1: sub, then unconditional clamp
			gSpeed -= runAccel;
			if (gSpeed < -max) gSpeed = (short) -max;
		} else {
			// S2/S3K: only accelerate if below max — preserve existing high speed
			if (gSpeed > -max) {
				gSpeed -= runAccel;
				if (gSpeed < -max) gSpeed = (short) -max;
			}
		}
		return gSpeed;
	}

	/**
	 * Accelerate right (positive direction) with per-game speed capping.
	 *
	 * S1 (inputAlwaysCapsGroundSpeed=true): Always clamps to max.
	 * ROM: s1disasm/_incObj/01 Sonic.asm:555-558 — unconditional clamp.
	 *
	 * S2/S3K (inputAlwaysCapsGroundSpeed=false): Preserves speeds above max.
	 * ROM: s2.asm:36610-36616 — undo accel if was already >= max.
	 */
	private short accelerateRight(short gSpeed, short runAccel, short max) {
		PlayerMovementRules movementRules = playerMovementRulesOrNull();
		boolean alwaysCap = movementRules != null && movementRules.inputAlwaysCapsGroundSpeed();

		if (alwaysCap) {
			// S1: add, then unconditional clamp
			gSpeed += runAccel;
			if (gSpeed > max) gSpeed = max;
		} else {
			// S2/S3K: only accelerate if below max — preserve existing high speed
			if (gSpeed < max) {
				gSpeed += runAccel;
				if (gSpeed > max) gSpeed = max;
			}
		}
		return gSpeed;
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

	// Legacy test hooks now delegate to the authoritative CollisionSystem path.
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
			default -> GroundMode.RIGHTWALL;
		};

		if (newMode != sprite.getGroundMode()) {
			sprite.setGroundMode(newMode);
		}
	}

	private void doTerrainCollisionAir(SensorResult[] ignored) {
		SensorResult lowestResult = null;
		for (SensorResult result : ignored) {
			if (result != null && (lowestResult == null || result.distance() < lowestResult.distance())) {
				lowestResult = result;
			}
		}
		if (sprite.getYSpeed() < 0 || lowestResult == null || lowestResult.distance() >= 0) {
			return;
		}

		short ySpeedPixels = (short) (sprite.getYSpeed() >> 8);
		short threshold = (short) (-(ySpeedPixels + 8));
		boolean canLand = (ignored[0] != null && ignored[0].distance() >= threshold)
				|| (ignored[1] != null && ignored[1].distance() >= threshold);

		if (!canLand) {
			return;
		}

		// ROM-accurate: collision adjustment uses add.w/sub.w on pixel position,
		// preserving subpixel fraction. Using shiftX/shiftY instead of setX/setY.
		byte distance = lowestResult.distance();
		switch (lowestResult.direction()) {
			case UP -> sprite.shiftY(-distance);
			case DOWN -> sprite.shiftY(distance);
			case LEFT -> sprite.shiftX(-distance);
			case RIGHT -> sprite.shiftX(distance);
		}

		if ((lowestResult.angle() & 0x01) != 0) {
			sprite.setAngle((byte) 0x00);
		} else {
			sprite.setAngle(lowestResult.angle());
		}
		calculateLanding(sprite);
		updateGroundMode();
	}

	/**
	 * ROM-accurate speed threshold for ground attachment (s2.asm:42727, 42794, 42861).
	 * Uses X velocity for GROUND/CEILING modes, Y velocity for wall modes.
	 *
	 * ROM uses mvabs.b which takes the HIGH BYTE (integer pixels) of velocity:
	 *   mvabs.b  x_vel(a0),d0  ; for ceiling
	 *   mvabs.b  y_vel(a0),d0  ; for walls
	 *
	 * No fallback to gSpeed - ROM uses raw velocity bytes directly.
	 */
	private int getSpeedForThreshold() {
		GroundMode groundMode = sprite.getGroundMode();
		// ROM uses x_vel for GROUND/CEILING, y_vel for walls (mvabs.b instruction)
		return (groundMode == GroundMode.LEFTWALL || groundMode == GroundMode.RIGHTWALL)
				? Math.abs(sprite.getYSpeed() >> 8)
				: Math.abs(sprite.getXSpeed() >> 8);
	}

	private boolean hasEnoughHeadroom(int hexAngle) {
		return collisionSystem().hasEnoughHeadroom(sprite, hexAngle);
	}

	private boolean isUnifiedCollision() {
		CollisionRules rules = collisionRulesOrNull();
		return rules != null && rules.collisionModel() == com.openggf.game.CollisionModel.UNIFIED;
	}

	private boolean hasObjectSupport() {
		return collisionSystem().hasObjectSupport(sprite);
	}

	private boolean hasGroundingObjectSupport() {
		return collisionSystem().hasGroundingObjectSupport(sprite);
	}

	private void clearRidingObject() {
		collisionSystem().clearRidingObject(sprite);
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
			int spindashAnimationId = velocityProfile.getSpindashAnimId();
			sprite.setAnimationId(spindashAnimationId);
			sprite.setAnimationFrameIndex(0);
			sprite.setAnimationTick(0);
			// Every charge writes anim/prev_anim as the word $0900. The ensuing
			// Animate_Tails/Animate_Sonic pass owns Status_Push clearing; do not
			// publish it here because Sonic_RecordPos runs between movement and
			// animation and must retain the pre-animation status byte
			// (sonic3k.asm:22006-22017,22119-22136,23642-23675,29359-29364).
			PlayerAnimationRules rules = playerAnimationRulesOrNull();
			deferredSpindashAnimationPushClear =
					rules != null && rules.animationChangeClearsPush();
			}
		}

	/**
	 * Applies Animate_Sonic/Animate_Tails' animation-change push clear after the
	 * follower status table has sampled the pre-animation byte.
	 */
	public void applyDeferredSpindashAnimationPushClear() {
		if (!deferredSpindashAnimationPushClear) {
			return;
		}
		deferredSpindashAnimationPushClear = false;
		sprite.setPushing(false);
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
		captureTiltAnglesForGroundDispatch();
		doAnglePos();
	}

	private void publishDirectionalRollAnimation() {
		SpriteAnimationProfile profile = sprite.getAnimationProfile();
		if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
			// RollLeft/RollRight write anim=Roll only when input points with the
			// current inertia (including zero). Opposite-direction deceleration
			// leaves a later object-owned anim byte untouched (S1 01 Sonic.asm:
			// 881-928; S2 s2.asm:37108-37150; S3K sonic3k.asm:23047-23082).
			sprite.setAnimationId(velocityProfile.getRollAnimId());
		}
	}

	private void captureTiltAnglesForGroundDispatch() {
		Sensor[] groundSensors = sprite.getGroundSensors();
		if (groundSensors == null || groundSensors.length < 2) {
			return;
		}
		// Player_AnglePos leaves the two FindFloor angle outputs in the global
		// Primary_Angle/Secondary_Angle bytes. The player tail copies them to
		// next_tilt/tilt only after the movement dispatch (S3K Tails:
		// sonic3k.asm:26215-26244). Airborne landing does not run this ground
		// AnglePos path, so the first grounded control frame must still consume
		// the previous values; these latches update only here, after that frame's
		// ground attachment pass. Sample before doAnglePos can snap the player:
		// the ROM globals retain the angles produced by that routine's probes.
		SensorResult left = groundSensors[0].scan();
		SensorResult right = groundSensors[1].scan();
		latchedNextTilt = right == null ? 3 : right.angle() & 0xFF;
		latchedTilt = left == null ? 3 : left.angle() & 0xFF;
		collisionSystem().publishGroundAngleRegisters(left, right);
	}

	// ========================================
	// BALANCE DETECTION (ROM s2.asm:36246-36373)
	// ========================================

	/**
	 * ROM-accurate balance detection for ledge edges.
	 * Sets sprite balance state (0 = not balancing, 1-4 = balance animation level).
	 *
	 * This implements Obj01_LookUpDown balance checks from s2.asm:36246-36373.
	 * Balance is checked when:
	 * - Sprite is standing still (gSpeed == 0)
	 * - Sprite is on flat ground (angle near 0)
	 * - Directional acceleration/braking has left inertia at zero
	 *
	 * Two types of edge detection:
	 * 1. Standing on object edge (status.player.on_object set)
	 * 2. Standing on terrain edge (ChkFloorEdge distance >= 12)
	 */
	/**
	 * Returns whether the player would be edge-balancing THIS frame, with no
	 * lasting side effects. ROM Sonic_Balance runs before Sonic_LookUp/Sonic_Duck
	 * and balancing diverts to Sonic_ResetScr (no look-up/down). The engine
	 * normally computes balance in updateCrouchState() AFTER the doGroundMove()
	 * look code, so the look code needs a current-frame read here. updateBalanceState()
	 * sets the balance level and (on an edge) the facing direction; we snapshot and
	 * restore both while retaining the decision for the later moving-crouch bridge.
	 * This preserves the ROM's pre-movement decision point without exposing the
	 * temporary state before updateCrouchState() applies it.
	 *
	 * <p>Callers gate on the standing-still-on-flat-ground precondition. Directional
	 * input suppresses the balance probe unless S1's asymmetric MoveLeft braking
	 * branch has reduced positive inertia to exactly zero. That helper returns
	 * without writing an animation, then the enclosing Move routine immediately
	 * continues through its zero-inertia Wait/Balance tail while Left remains held
	 * (_incObj/01 Sonic.asm:390-431, 634-697).
	 */
	private boolean computeCurrentFrameBalancing() {
		if ((inputLeft || inputRight) && !directionalBrakeReachedZero) {
			return false;
		}
		Direction savedDirection = sprite.getDirection();
		int savedBalanceState = sprite.getBalanceState();
		// doGroundMove has only updated its local gSpeed at this point. The exact
		// MoveLeft deceleration-to-zero case must therefore bypass the stale live
		// sprite inertia gate while probing the enclosing Move routine's tail.
		updateBalanceState(true);
		boolean balancing = sprite.isBalancing();
		preMoveBalanceEvaluated = true;
		preMoveBalanceState = sprite.getBalanceState();
		preMoveBalanceDirection = sprite.getDirection();
		// Restore: the real balance state + facing are recomputed at their normal
		// time by updateCrouchState(); this call must leave no trace.
		sprite.setBalanceState(savedBalanceState);
		sprite.setDirection(savedDirection);
		return balancing;
	}

	private void updateBalanceState() {
		updateBalanceState(false);
	}

	private void updateBalanceState(boolean standingStillAtMovementDispatch) {
		// Reset balance state first
		sprite.setBalanceState(0);

		// Balance only applies when standing still on flat ground
		if ((!standingStillAtMovementDispatch && sprite.getGSpeed() != 0) || !isOnFlatGround()) {
			return;
		}

		// Check if standing on an object - check object edge first
		if (sprite.isOnObject()) {
			checkObjectEdgeBalance();
			return;
		}

		// Otherwise check terrain edge balance
		checkTerrainEdgeBalance();
	}

	/**
	 * Check balance when standing on an object (platform, monitor, etc.).
	 *
	 * S2/S3K (extendedEdgeBalance): ROM s2.asm:36246-36318
	 * - d2 = (width * 2) - 2, left threshold < 2, right threshold >= d2
	 * - 4 balance states with precarious/facing-away checks
	 *
	 * S1 (!extendedEdgeBalance): ROM s1disasm/_incObj/01 Sonic.asm:340-351
	 * - d2 = (width * 2) - 4, left threshold < 4, right threshold >= d2
	 * - Single balance state, always forces facing toward edge
	 */
	private void checkObjectEdgeBalance() {
		// Get the object the sprite is standing on
		var objectManager = levelManager().getObjectManager();
		if (objectManager == null) {
			return;
		}
		int interactSlot = sprite.getInteractSlotIndex();
		ObjectInstance latchedObject = sprite.getLatchedSolidObjectInstance();
		if (interactSlot >= 0
				&& objectManager.objectIdInSlot(interactSlot) < 0
				&& (latchedObject == null || latchedObject.isDestroyed())) {
			checkClearedInteractSlotEdgeBalance(objectManager);
			return;
		}

		var ridingObject = objectManager.getRidingObject(sprite);
		if (ridingObject == null) {
			return;
		}

		// Object must be a SolidObjectProvider to get width for balance calculation
		if (!(ridingObject instanceof SolidObjectProvider provider)) {
			return;
		}

		if (ridingObject instanceof com.openggf.level.objects.AbstractObjectInstance objectInstance
				&& objectInstance.suppressesObjectEdgeBalance()) {
			return;
		}

		// For multi-piece solid objects (e.g., CPZ Staircase), use the piece-specific
		// position and params rather than the overall object's base position.
		// In the ROM, each piece is effectively a separate SST with its own x_pos and
		// width_pixels, so the balance check naturally uses piece-local coordinates.
		int objectX;
		SolidObjectParams params;
		int ridingPieceIndex = objectManager.getRidingPieceIndex(sprite);
		boolean useMultiPieceWidth = false;
		if (ridingPieceIndex >= 0 && ridingObject instanceof MultiPieceSolidProvider multiPiece) {
			objectX = multiPiece.getPieceX(ridingPieceIndex);
			params = multiPiece.getPieceParams(ridingPieceIndex);
			useMultiPieceWidth = true;
		} else {
			objectX = ridingObject.getX();
			params = provider.getSolidParams();
		}
		if (params == null) {
			return;
		}

		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		boolean extended = animationRules != null && animationRules.extendedEdgeBalance();
		boolean singleFacingBalanceSet = usesSingleFacingBalance(animationRules);

		// ROM Sonic_Move (s2.asm:36285) / Tails_Move (s2.asm:39359) read
		// `width_pixels(a1)` from the object's SST for the balance computation,
		// NOT the SolidObject-extended X-check width (which may be
		// `width_pixels + N` for some objects, e.g. SmashableGround uses
		// `width_pixels=$10` for balance but `width_pixels+$B=$1B` for
		// SolidObject's d1 in s2.asm:48703-48705). The engine's
		// `SolidObjectParams.halfWidth()` bakes in any per-object collision
		// extension, so using it here makes Tails report d1 outside the
		// (4, 2*width-4) Balance window on objects like SmashableGround and
		// the Tails_Lookup branch falls through to Tails_Duck, which sets
		// anim=Duck and spuriously triggers Tails_CheckSpindash the next
		// frame when the delayed Ctrl_2 jump-press latches.
		// ROM Sonic_Move / Tails_Move balance reads `width_pixels(a1)` — the
		// object's own SST width byte (s2.asm:36586/39707, sonic3k.asm:22455) —
		// which is neither the rendered on-screen footprint nor the (possibly
		// extended) SolidObject X-check width. AbstractObjectInstance exposes it
		// via getBalanceWidthPixels(); it defaults to getOnScreenHalfWidth()
		// (16 px, correct for SmashableGround and most sprites) and is overridden
		// where the balance width_pixels differs (e.g. the CPZ/WFZ moving
		// platform Obj19, whose subtype width_pixels is $20/$18/$40). Using 16
		// for the platform shifted the d1 edge test by up to 8 px, making the
		// player balance/flip facing on the wrong (left) object edge — observed
		// as a sidekick (Tails CPU) facing latch in CPZ2.
		int objectWidth;
		if (useMultiPieceWidth) {
			objectWidth = params.halfWidth();
		} else if (ridingObject instanceof com.openggf.level.objects.AbstractObjectInstance objectInstance) {
			objectWidth = objectInstance.getBalanceWidthPixels();
		} else {
			objectWidth = params.halfWidth();
		}
		int playerX = sprite.getCentreX();

		// ROM formula: d1 = player_x + width - object_x
		// This gives player position relative to left edge of object
		int d1 = playerX + objectWidth - objectX;

		// S1 (non-extended) hard-codes #4 (s1disasm/_incObj/01 Sonic.asm:392).
		// Extended (S2/S3K) reads a per-character shift from PhysicsProfile:
		// Sonic=2 (s2.asm:36287, sonic3k.asm:22465), Tails=4 (s2.asm:39361,
		// sonic3k.asm:27825), Knuckles=2 (sonic3k.asm:31810).
		int balanceShift;
		if (!extended) {
			balanceShift = 4;
		} else {
			PhysicsProfile profile = sprite.getPhysicsProfile();
			balanceShift = profile != null ? profile.onObjectBalanceShift() : 2;
		}
		int leftThreshold = balanceShift;
		int d2 = (objectWidth * 2) - balanceShift;

		applyObjectEdgeBalance(d1, d2, leftThreshold, extended, singleFacingBalanceSet);
	}

	/**
	 * ROM object-edge balance dereferences {@code interact(a0)} whenever
	 * {@code Status_OnObj} remains set, even after DeleteObject has cleared that
	 * SST. The cleared slot contributes status/width/x words of zero; retaining
	 * this read matters for the one or more player passes before another routine
	 * clears the stale status bit.
	 */
	private void checkClearedInteractSlotEdgeBalance(ObjectManager objectManager) {
		int interactSlot = sprite.getInteractSlotIndex();
		if (interactSlot < 0 || objectManager.objectIdInSlot(interactSlot) >= 0) {
			return;
		}

		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		boolean extended = animationRules != null && animationRules.extendedEdgeBalance();
		boolean singleFacingBalanceSet = usesSingleFacingBalance(animationRules);
		int balanceShift;
		if (!extended) {
			balanceShift = 4;
		} else {
			PhysicsProfile profile = sprite.getPhysicsProfile();
			balanceShift = profile != null ? profile.onObjectBalanceShift() : 2;
		}

		// Cleared SST: width_pixels=0 and x_pos=0. Keep both calculations
		// word-sized so high level coordinates retain the 68000 signed branches.
		int d1 = (short) sprite.getCentreX();
		int d2 = (short) -balanceShift;
		applyObjectEdgeBalance(d1, d2, balanceShift, extended, singleFacingBalanceSet);
	}

	private void applyObjectEdgeBalance(int d1, int d2, int leftThreshold, boolean extended,
			boolean singleFacingBalanceSet) {
		boolean facingRight = sprite.getDirection() == Direction.RIGHT;

		if (d1 < leftThreshold) {
			// On left edge of object
			if (extended) {
				// S2/S3K: 4-state balance with precarious check
				boolean precarious = d1 < -4;
				boolean facingTowardEdge = !facingRight;
				int balanceState;
				if (singleFacingBalanceSet) {
					sprite.setDirection(Direction.LEFT);
					balanceState = singleFacingBalanceState(precarious);
				} else {
					balanceState = facingTowardEdge ? (precarious ? 2 : 1) : (precarious ? 4 : 3);
					if (balanceState == 4) {
						sprite.setDirection(Direction.LEFT);
					}
				}
				sprite.setBalanceState(balanceState);
			} else {
				// S1: single state, force facing toward edge (bset #0 → face left)
				// ROM s1.asm:370-371: bset #0,obStatus(a0)
				sprite.setBalanceState(1);
				sprite.setDirection(Direction.LEFT);
			}
		} else if (d1 >= d2) {
			// On right edge of object
			if (extended) {
				// S2/S3K: 4-state balance with precarious check
				boolean precarious = d1 >= d2 + 6;
				boolean facingTowardEdge = facingRight;
				int balanceState;
				if (singleFacingBalanceSet) {
					sprite.setDirection(Direction.RIGHT);
					balanceState = singleFacingBalanceState(precarious);
				} else {
					balanceState = facingTowardEdge ? (precarious ? 2 : 1) : (precarious ? 4 : 3);
					if (balanceState == 4) {
						sprite.setDirection(Direction.RIGHT);
					}
				}
				sprite.setBalanceState(balanceState);
			} else {
				// S1: single state, force facing toward edge (bclr #0 → face right)
				// ROM s1.asm:361-362: bclr #0,obStatus(a0)
				sprite.setBalanceState(1);
				sprite.setDirection(Direction.RIGHT);
			}
		}
	}

	private boolean usesSingleFacingBalance(PlayerAnimationRules animationRules) {
		return (animationRules != null && animationRules.singleFacingBalanceAnimationSet())
				|| profileUsesSingleFacingBalance();
	}

	private boolean profileUsesSingleFacingBalance() {
		PhysicsProfile profile = sprite.getPhysicsProfile();
		return profile != null && profile.singleFacingBalance();
	}

	private int singleFacingBalanceState(boolean precarious) {
		return profileUsesSingleFacingBalance() ? 1 : (precarious ? 2 : 1);
	}

	/**
	 * Check balance when standing on terrain (level floor).
	 *
	 * S2/S3K (extendedEdgeBalance): ROM s2.asm:36322-36373 (Sonic_Balance)
	 * - ChkFloorEdge distance >= 12, next_tilt/tilt == 3
	 * - Secondary probe at ±6px for precarious check → 4 balance states
	 *
	 * S1 (!extendedEdgeBalance): ROM s1disasm/_incObj/01 Sonic.asm:354-375
	 * - ObjFloorDist distance >= 12, objoff_36/objoff_37 == 3
	 * - No precarious check, single balance state, always faces edge
	 */
	private void checkTerrainEdgeBalance() {
		// Use ground sensors to check for floor edge
		Sensor[] groundSensors = sprite.getGroundSensors();
		if (groundSensors == null || groundSensors.length < 2) {
			return;
		}

		// Ground sensors are at center ± 9 pixels (for Sonic)
		// Left sensor (index 0) is at center - 9
		// Right sensor (index 1) is at center + 9
		SensorResult leftResult = groundSensors[0].scan();
		SensorResult rightResult = groundSensors[1].scan();

		int leftDist = (leftResult == null) ? 99 : leftResult.distance();
		int rightDist = (rightResult == null) ? 99 : rightResult.distance();

		// Edge threshold - ROM uses 12 ($C) for both S1 and S2
		final int EDGE_THRESHOLD = 12;

		boolean facingRight = sprite.getDirection() == Direction.RIGHT;

		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		boolean extended = animationRules != null && animationRules.extendedEdgeBalance();

		if (!extended) {
			// S1: ObjFloorDist probes at CENTER X, not at the ±9 side sensors.
			// ROM s1.asm:354-356: jsr (ObjFloorDist).l / cmpi.w #$C,d1
			// ObjFloorDist uses obX(a0) = sprite center X.
			// Derive the centre probe from the active collision radius instead of
			// assuming Sonic's nine-pixel standing radius. Tails can use a
			// seven-pixel radius after a native shape transition.
			short centerOffset = (short) -groundSensors[0].getX();
			SensorResult centerResult = groundSensors[0].scan(centerOffset, (short) 0);
			int centerDist = (centerResult == null) ? 99 : centerResult.distance();

			if (centerDist < EDGE_THRESHOLD) {
				return; // Center still has ground — not on edge yet
			}

			// Center is over the edge. Sonic_Move reads the angle bytes copied
			// from the preceding AnglePos dispatch, not fresh side distances from
			// this position. A fresh scan can already report an empty side while
			// the native angleright/angleleft bytes still describe the prior
			// supported position (S1 01 Sonic.asm:434-455).
			if (latchedNextTilt == 3) {
				// angleright == 3 → right edge → face right
				sprite.setBalanceState(1);
				sprite.setDirection(Direction.RIGHT);
			} else if (latchedTilt == 3) {
				// angleleft == 3 → left edge → face left
				sprite.setBalanceState(1);
				sprite.setDirection(Direction.LEFT);
			}
			// Neither latched angle is the empty-tile sentinel: retain Wait.
			return;
		}

		// S2/S3K: ROM Sonic_Balance (s2.asm:36657-36660, sonic3k.asm:22531-22535)
		// first probes ChkFloorEdge at the player's CENTER X (`x_pos(a0)`, a single
		// probe — ChkFloorEdge sets d3=x_pos at s2.asm:44092-44093) and requires the
		// center floor distance >= $C before any balance; otherwise it falls through
		// to Sonic_Lookup/Sonic_Duck (`cmpi.w #$C,d1; blt Sonic_Lookup`). Only once
		// the CENTER is over a >=12px gap does it choose the left/right edge branch.
		// Without this center gate the engine balanced whenever one ±9 side sensor
		// ran off a solid edge while the center still had ground — e.g. CNZ2 Sonic
		// standing on the left lip of a wide invisible solid block (Obj74). That
		// spurious balance suppressed the Duck crouch (updateCrouchState requires
		// !isBalancing), so a held Down+B fired a normal jump instead of charging a
		// spin-dash (s2.asm:37549-37571 Sonic_CheckSpindash requires anim==Duck).
		// The S1 (!extended) branch above already applies this center gate.
		short centerOffset = (short) -groundSensors[0].getX();
		SensorResult centerResult = groundSensors[0].scan(centerOffset, (short) 0);
		int centerDist = (centerResult == null) ? 99 : centerResult.distance();
		if (centerDist < EDGE_THRESHOLD) {
			return; // Center still has ground — ROM branches to Lookup/Duck, no balance.
		}
		// ROM chooses the edge from the prior player-tail copy of
		// Primary_Angle/Secondary_Angle, not from a fresh pair of side probes
		// inside Tails_InputAcceleration_Path (sonic3k.asm:27840-27849).
		if (latchedNextTilt == 3) {
			// S2/S3K: precarious check - scan at center - 6, derived from the
			// active left sensor rather than assuming a nine-pixel radius.
			short precariousOffset = (short) (-6 - groundSensors[0].getX());
			SensorResult precariousResult = groundSensors[0].scan(precariousOffset, (short) 0);
			int precariousDist = (precariousResult == null) ? 99 : precariousResult.distance();
			boolean precarious = precariousDist >= EDGE_THRESHOLD;
			setBalanceForEdge(false, facingRight, precarious ? 0 : 6);
			return;
		}

		if (latchedTilt == 3) {
			// S2/S3K: precarious check - scan at center + 6, derived from the
			// active right sensor rather than assuming a nine-pixel radius.
			short precariousOffset = (short) (6 - groundSensors[1].getX());
			SensorResult precariousResult = groundSensors[1].scan(precariousOffset, (short) 0);
			int precariousDist = (precariousResult == null) ? 99 : precariousResult.distance();
			boolean precarious = precariousDist >= EDGE_THRESHOLD;
			setBalanceForEdge(true, facingRight, precarious ? 0 : 6);
		}
	}

	/**
	 * Scan floor at player position with X offset.
	 * Returns distance to floor (positive = gap, negative = inside floor).
	 *
	 * ROM: ChkFloorEdge_Part2 (s2.asm:43677)
	 */
	private int scanFloorEdge(int xOffset) {
		Sensor[] groundSensors = sprite.getGroundSensors();
		if (groundSensors == null || groundSensors.length < 2) {
			return 0;
		}

		// Use GroundSensor if available, otherwise use basic sensor
		Sensor sensor = groundSensors[xOffset >= 0 ? 1 : 0]; // Right or left sensor

		// Temporarily offset the scan position
		boolean wasActive = sensor.isActive();
		sensor.setActive(true);

		// Scan at offset position
		SensorResult result = sensor.scan((short) xOffset, (short) 0);
		sensor.setActive(wasActive);

		if (result == null) {
			return 99; // No floor found = edge
		}

		return result.distance();
	}

	/**
	 * Set balance state based on edge position and facing direction.
	 *
	 * ROM balance animation selection (s2.asm:36284-36318):
	 * - Facing toward edge: Balance (0x06), or Balance2 (0x0C) if closer to edge
	 * - Facing away from edge: Balance3 (0x1D), or Balance4 (0x1E) if closer to edge
	 *
	 * ROM behavior for Balance4: sprite is FLIPPED to face the edge!
	 * - Right edge + facing left + precarious → bclr x_flip → face right (toward edge)
	 * - Left edge + facing right + precarious → bset x_flip → face left (toward edge)
	 *
	 * @param isLeftEdge True if standing on left edge (floor drops to the left)
	 * @param facingRight True if sprite is facing right
	 * @param distanceFromPrecarious Distance threshold for precarious balance (< 6 = precarious)
	 */
	private void setBalanceForEdge(boolean isLeftEdge, boolean facingRight, int distanceFromPrecarious) {
		// Determine if facing toward or away from the edge
		boolean facingTowardEdge = (isLeftEdge && !facingRight) || (!isLeftEdge && facingRight);

		// Determine if precarious (closer to falling)
		boolean precarious = distanceFromPrecarious < 6;

		PlayerAnimationRules animationRules = playerAnimationRulesOrNull();
		if (usesSingleFacingBalance(animationRules)) {
			sprite.setDirection(isLeftEdge ? Direction.LEFT : Direction.RIGHT);
			sprite.setBalanceState(singleFacingBalanceState(precarious));
			return;
		}

		int balanceState;
		if (facingTowardEdge) {
			// Facing toward edge: Balance or Balance2
			balanceState = precarious ? 2 : 1;
		} else {
			// Facing away from edge: Balance3 or Balance4
			balanceState = precarious ? 4 : 3;

			// ROM: Balance4 flips the sprite to face the edge
			// s2.asm:36299 (right edge): bclr #status.player.x_flip → face right
			// s2.asm:36317 (left edge): bset #status.player.x_flip → face left
			if (precarious) {
				// Flip sprite to face the edge
				if (isLeftEdge) {
					// Left edge → face left (toward edge)
					sprite.setDirection(Direction.LEFT);
				} else {
					// Right edge → face right (toward edge)
					sprite.setDirection(Direction.RIGHT);
				}
			}
		}

		sprite.setBalanceState(balanceState);
	}
}
