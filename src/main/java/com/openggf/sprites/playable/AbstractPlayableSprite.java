package com.openggf.sprites.playable;

import com.openggf.camera.Camera;
import com.openggf.game.AnimationId;
import com.openggf.game.CanonicalAnimation;
import com.openggf.game.CollisionModel;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.GameServices;
import com.openggf.game.InstaShieldHandle;
import com.openggf.game.PhysicsModifiers;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.PhysicsProvider;
import com.openggf.game.PowerUpObject;
import com.openggf.game.PowerUpSpawner;
import com.openggf.game.GroundMode;
import com.openggf.game.ShieldType;
import com.openggf.game.DamageCause;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameStateManager;
import com.openggf.game.LevelState;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.PlayerCapabilityRules;
import com.openggf.game.rules.PlayerMovementRules;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.timer.Timer;
import com.openggf.timer.TimerManager;

import com.openggf.audio.GameAudioProfile;

import java.util.Objects;
import java.util.logging.Logger;

import com.openggf.audio.AudioManager;
import com.openggf.audio.GameSound;
import com.openggf.level.LevelManager;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.PerObjectRewindSnapshot;
import com.openggf.level.objects.PerObjectRewindSnapshot.PlayerRewindExtra;
import com.openggf.level.objects.PerObjectRewindSnapshot.SidekickCpuRewindExtra;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.Direction;
import com.openggf.physics.Sensor;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.managers.SpriteMovementManager;
import com.openggf.sprites.managers.TailsTailsController;
import com.openggf.sprites.managers.TailsFlightController;
import com.openggf.sprites.AbstractSprite;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.SensorConfiguration;
import com.openggf.sprites.managers.PlayableSpriteAnimation;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.graphics.RenderPriority;
import com.openggf.sprites.animation.ScriptedVelocityAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationProfile;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.managers.SpindashDustController;
import com.openggf.timer.timers.SpeedShoesTimer;

/**
 * Movement speeds are in subpixels (256 subpixels per pixel...).
 * 
 * @author james
 * 
 */
public abstract class AbstractPlayableSprite extends AbstractSprite implements com.openggf.game.PlayableEntity {
        private static final Logger LOGGER = Logger.getLogger(AbstractPlayableSprite.class.getName());

        @RewindTransient(reason = "playable controller is structural; mutable controller state is captured explicitly")
        protected final PlayableSpriteController controller;

        protected GroundMode runningMode = GroundMode.GROUND;

        /**
         * gSpeed is the speed this sprite is moving across the 'ground'.
         * Calculations will be performed against this and 'angle' to calculate new
         * x/y values for each step.
         */
        protected short gSpeed = 0;

        /**
         * Current angle of the terrain this sprite is on.
         */
        protected byte angle;

        /**
         * Chunk entry solidity bit indices (REV01 defaults).
         */
        protected byte topSolidBit = 0x0C;
        protected byte lrbSolidBit = 0x0D;
        /** ROM status_tertiary bit-field, mirrored so objects can coordinate across frames. */
        private byte statusTertiary = 0;

        /**
         * Sonic 1 loop plane state. When true, the player is on the "low plane"
         * and collision uses alternate block data (block index + 1) for loop tiles.
         * Toggled by Sonic1LoopManager based on position within 256x256 blocks and ground angle.
         */
        protected boolean loopLowPlane = false;

        /**
         * Speed (in subpixels) at which this sprite walks
         */
        protected short jump = 0;

        protected short xSpeed = 0;
        protected short ySpeed = 0;

        // ROM: Sonic_Pos_Record_Buf is $100 bytes (256 bytes / 4 bytes per entry = 64 entries)
        // Used for spindash camera lag and Tails CPU following
        private short[] xHistory = new short[64];
        private short[] yHistory = new short[64];

        // ROM: Sonic_Stat_Record_Buf is $100 bytes (256 bytes / 4 bytes per entry = 64 entries)
        // Records input buttons and status flags each frame for Tails CPU input replay.
        // Entry format matches ROM: word 0 = Ctrl_1_Logical (input), word 1 = status
        private short[] inputHistory = new short[64];
        private byte[] jumpPressHistory = new byte[64];
        private byte[] statusHistory = new byte[64];
        private boolean followerHistoryRecordedThisTick;
        /** Current frame logical controller state (ROM: Ctrl_1_Logical). */
        private short logicalInputState = 0;
        /** Current frame logical jump press bit (ROM: low byte of Ctrl_1_Logical). */
        private boolean logicalJumpPressState = false;

        // Input bitmask constants (matching Mega Drive controller layout)
        public static final int INPUT_UP    = 0x01;
        public static final int INPUT_DOWN  = 0x02;
        public static final int INPUT_LEFT  = 0x04;
        public static final int INPUT_RIGHT = 0x08;
        public static final int INPUT_JUMP  = 0x10;

        // Status flag constants (matching ROM status byte)
        public static final byte STATUS_FACING_LEFT          = 0x01;
        public static final byte STATUS_IN_AIR               = 0x02;
        public static final byte STATUS_ROLLING              = 0x04;
        public static final byte STATUS_ON_OBJECT            = 0x08;
        public static final byte STATUS_ROLLING_JUMP         = 0x10;
        public static final byte STATUS_PUSHING              = 0x20;
        public static final byte STATUS_UNDERWATER           = 0x40;
        public static final byte STATUS_PREVENT_TAILS_RESPAWN = (byte) 0x80;

        // ROM: Sonic_Pos_Record_Index - wraps at 256 (64 entries * 4 bytes)
        private byte historyPos = 0;

        /** Whether this sprite is controlled by CPU AI (e.g., Tails follower) */
        private boolean cpuControlled = false;

        /** The CPU controller for AI-driven sprites */
        @RewindTransient(reason = "sidekick CPU controller is structural; mutable CPU state is captured explicitly")
        private SidekickCpuController cpuController;

        /**
         * Whether or not this sprite is rolling
         */
        protected boolean rolling = false;

        /**
         * Pinball mode flag - when set, prevents rolling from being cleared on landing
         * and prevents rolling from stopping when speed reaches 0 (gives a boost instead).
         * This matches ROM's pinball_mode (spindash_flag when rolling) behavior used by
         * spin tubes, S-curves, and other "must roll" areas.
         * See s2.asm lines 36712, 37745 for usage in rolling/landing logic.
         */
        protected boolean pinballMode = false;

        /**
         * When true, the entire roll speed routine (input, friction, deceleration)
         * is skipped — only slope gravity modifies ground_vel. Matches ROM behaviour
         * of spin_dash_flag bit 7 (value 0x81) which causes {@code Sonic_RollSpeed}
         * to jump directly to velocity conversion (sonic3k.asm line 22936: bmi.w loc_115C6).
         * <p>
         * Set by AutoSpin objects when subtype bit 7 is active. Distinct from
         * {@link #pinballMode} which only prevents uncurling at low speed.
         */
        protected boolean pinballSpeedLock = false;

        /**
         * One-shot object handoff flag for ROM paths that keep a player curled
         * through the next floor touch without enabling general pinball-mode
         * movement while airborne. Set by the owning object and consumed by
         * {@code PlayableSpriteMovement.resetOnFloor()}.
         */
        protected boolean preserveRollingOnNextLanding = false;

        /**
         * One-shot object handoff flag for ROM paths that leave the player curled
         * at zero ground speed without enabling pinball-mode's forced speed boost.
         * Set by the owning object and consumed by roll-stop handling.
         */
        protected boolean preserveRollingOnNextRollStop = false;

        /**
         * One-frame companion to {@link #preserveRollingOnNextRollStop}. Set when
         * an object-preserved zero-speed roll seeds the stopper-chamber boost so
         * the next roll-speed tick applies natural friction only.
         */
        protected boolean objectPreservedRollBoostFollowup = false;

        /**
         * One-frame ground-wall probe extension after an object-preserved roll
         * boost. Used by S2 Obj85 stopper chambers where the ROM reaches the
         * chamber wall one frame after the zero-speed keep-rolling push.
         */
        protected boolean objectPreservedRollWallProbe = false;

        /**
         * Object-scoped velocity carry after a preserved zero-speed roll is
         * converted into a solid-wall push by the owning object.
         */
        protected boolean objectPreservedRollVelocityCarry = false;

        /**
         * Whether the player is in a roll-tunnel section (S1 GHZ S-tubes).
         * Suppresses the S2-derived ground wall check which falsely detects
         * narrow tunnel walls. Set by Sonic1LoopManager each frame on tunnel tiles.
         */
        protected boolean tunnelMode = false;

        /**
         * Whether the current jump originated from a rolling state.
         * In Sonic 1, 2, 3 & K, air control is locked when jumping while rolling.
         * Reset to false when landing.
         */
        protected boolean rollingJump = false;

        /**
         * Whether or not this sprite is in the air
         */
        protected boolean air = false;

        /**
         * Pre-physics snapshot of physics state, captured at the start of
         * each {@code handleMovement} tick (before any physics mutates the
         * player). Used by per-object hooks that run AFTER physics in the
         * engine's frame order but BEFORE physics in the ROM frame order
         * (e.g. {@code CnzWireCageObjectInstance} which captures the player
         * based on the airborne state ROM saw before player physics gated
         * via {@code object_control} bit 0). The fields snapshot
         * {@code air}, {@code angle}, {@code groundVel}, {@code xSpeed},
         * {@code ySpeed} as ROM would have read them at the start of
         * {@code Tails_Control}/{@code Sonic_Control} dispatch.
         */
        protected boolean prePhysicsAir = false;
        protected byte prePhysicsAngle = 0;
        protected short prePhysicsGSpeed = 0;
        /** Ground velocity after player physics but before late zone-feature updates. */
        protected short preZoneFeatureGSpeed = 0;
        protected short prePhysicsXSpeed = 0;
        protected short prePhysicsYSpeed = 0;
        protected short prePhysicsCentreX = 0;
        protected short prePhysicsCentreY = 0;

        /** Per-frame ground-wall collision response (pre-control inertia snapshot for the
         * wall probe, deferred velocity, terrain push provenance). All fields are
         * recomputed/cleared each frame, so this holder is
         * not persisted by the explicit rewind snapshot. */
        @RewindTransient(reason = "ground-wall response state is recomputed from terrain collision each frame")
        protected final GroundWallResponseState groundWallResponse = new GroundWallResponseState();

        /**
         * Whether this sprite is currently jumping (ROM: jumping(a0) status bit).
         * Distinct from 'air' - you can be airborne without having jumped
         * (e.g., walked off an edge, hit by enemy, launched by spring).
         * Set when jump starts, cleared on landing.
         */
        protected boolean jumping = false;

        /**
         * ROM: $2F double_jump_flag. For Sonic: 0=available, 1=used.
         * For Knuckles: 0=available, 1=gliding, 2=falling from glide,
         * 3=sliding on ground, 4=wall climbing, 5=climbing ledge.
         * Reset on landing and on damage.
         */
        protected int doubleJumpFlag = 0;

        /**
         * ROM: double_jump_property(a0). For Knuckles glide: angle byte controlling
         * glide direction. 0x00 = facing right, 0x80 (-128) = facing left.
         * Updated per-frame based on left/right input during glide.
         */
        protected byte doubleJumpProperty = 0;

        /**
         * X position when Knuckles grabbed a wall during glide (ROM: x_pos+2).
         * Used by wall climb to maintain horizontal position against the wall.
         */
        protected short wallClimbX = 0;

        /**
         * Whether this sprite is standing on a solid object (platform, moving block).
         * ROM: status.player.on_object
         * When true, AnglePos skips terrain collision - the object handles positioning.
         */
        protected boolean onObject = false;

        /**
         * Snapshot of {@link #onObject} captured at the START of the current
         * playable frame (before any player tick has run). Refreshed by
         * {@link #captureOnObjectAtFrameStart()} from {@code SpriteManager.beginPlayableFrame}.
         *
         * <p>ROM analog: in {@code Tails_CPU_Control} (sonic3k.asm:26688-26700,
         * S2 s2.asm:38933+), the follow-steering logic reads the leader's
         * {@code Status_OnObj} bit MID-FRAME, before solid-object processing
         * (sub_1FF1E sonic3k.asm:44306-44319, loc_1FFC4 sonic3k.asm:44369-44381)
         * has cleared it for jumpers. {@code Sonic_Jump} (sonic3k.asm:23288-23354)
         * sets {@code Status_InAir} but does NOT touch {@code Status_OnObj}; the
         * bit only clears later when objects run. The engine's
         * {@code PlayableSpriteMovement.doJump} and air-unseat paths clear
         * {@code onObject} EARLIER in the player tick, so by the time the
         * Tails CPU runs (next playable in {@code SpriteManager.update}), the
         * live {@code isOnObject()} value already reflects the leader's
         * post-tick state. Reading {@link #getOnObjectAtFrameStart()} preserves
         * the pre-tick (mid-frame, ROM-equivalent) view.
         */
        /**
         * ROM-style latched solid interaction object id.
         * Mirrors the object id resolved from the player's SST {@code interact} slot,
         * which persists even after {@code status.on_object} is cleared.
         */
        protected int latchedSolidObjectId = 0;

        /**
         * ROM SST {@code interact(a0)} (s2.constants.asm:69 "last object stood
         * on"): the SST <em>slot index</em> of the last object the sprite stood
         * on. This is the persistent slot reference written only by
         * {@code RideObject_SetRide} (docs/s2disasm/s2.asm:35980-36006,
         * S3K docs/skdisasm/sonic3k.asm:41982-42015) and is NEVER cleared on
         * dismount, despawn, or death. Unlike {@link #latchedSolidObjectId}
         * (which is an instance-resolved id byte), this is the raw slot index so
         * the sidekick despawn comparator can re-dereference whatever live
         * object currently occupies that slot every frame — exactly the ROM
         * {@code a3 = Object_RAM + interact(a0)*object_size} indirection — rather
         * than holding a stale {@code ObjectInstance} reference. {@code -1} means
         * "no slot recorded yet" (sprite has never stood on an object). This
         * field is ROM-universal (S2 and S3K sidekicks both have it).
         */
        protected int interactSlotIndex = -1;

        /**
         * Engine-only interact marker for ROM support objects hosted by manager
         * code instead of live {@code ObjectInstance}s. The latched object id
         * remains the ROM id byte that CPU despawn logic re-dereferences.
         */
        public static final int SYNTHETIC_INTERACT_SLOT = -2;

        /**
         * Set when {@code Player_SlopeRepel} slipped the player into air on the
         * current physics frame (sonic3k.asm:23929 {@code bset #Status_InAir}).
         * Cleared at the start of each player update tick. Used by per-object
         * release-vs-restore decisions (e.g. {@code CnzWireCageObjectInstance})
         * to distinguish "slope-repel just slipped, honour the air state" from
         * "stale terrain probe spuriously set air, restore the on-object
         * status".
         */
        protected boolean slopeRepelJustSlipped = false;

        /**
         * Whether to stick to convex surfaces even at low speeds.
         * ROM: stick_to_convex status bit
         * When true, prevents slope repel/detachment on convex terrain.
         */
        protected boolean stickToConvex = false;

        /**
         * Whether this sprite is on an oil slide in OOZ.
         * ROM: status_secondary.sliding
         */
        protected boolean sliding = false;

        /**
         * Whether or not this sprite is pushing a solid object.
         */
        protected boolean pushing = false;

        /**
         * Whether or not this sprite is currently skidding (braking).
         * ROM: Set when pressing opposite direction from movement at speed >= 0x400.
         */
        protected boolean skidding = false;

        /**
         * Timer for skid dust spawning. ROM spawns dust every 4 frames while skidding.
         * Decrements each frame; when < 0, spawn dust and reset to 3.
         */
        protected int skidDustTimer = 0;
        protected boolean fixedSkidDustActive = false;

        /**
         * Frames remaining for post-hit invulnerability.
         */
        protected int invulnerableFrames = 0;
        private boolean suppressNextInvulnerabilityDecrement = false;
        private boolean invulnerabilityDisplayTimerTickedThisFrame = false;
        /**
         * Set for the frame on which the hurt routine ran, including the frame it
         * hands control back on landing. ROM {@code Obj01_Hurt} / {@code Obj02_Hurt}
         * end in an UNCONDITIONAL {@code jmp (DisplaySprite)}
         * (docs/s2disasm/s2.asm:38193 and :41076) — the invulnerability blink test
         * lives only in {@code Sonic_Display} / {@code Tails_Display}
         * (s2.asm:36280-36285, 39015-39020), which the hurt routine never reaches.
         * {@code Sonic_HurtStop} / {@code Tails_HurtStop} write
         * {@code invulnerable_time = $78} and restore routine 2 (s2.asm:38225,
         * :41112) from inside that same hurt frame, so the landing frame is still
         * drawn unconditionally and BuildSprites still refreshes
         * {@code render_flags.on_screen} for it. Without this latch the engine
         * applies the blink gate one frame too early and leaves a stale on-screen
         * bit, which the sidekick CPU then reads (TailsCPU_CheckDespawn,
         * s2.asm:39408-39440). S1 has the identical shape
         * (docs/s1disasm/_incObj/"01 Sonic.asm":1912 Sonic_Hurt tail).
         */
        private boolean hurtRoutineOwnedDisplayThisFrame = false;

        /**
         * Frames remaining for invincibility power-up.
         */
        protected int invincibleFrames = 0;

        /**
         * Whether or not this sprite is in the spring animation state.
         */
        protected boolean springing = false;

        /**
         * Frames remaining for springing state.
         */
        protected int springingFrames = 0;

        /**
         * Whether or not this sprite is dead.
         */
        protected boolean dead = false;

        /**
         * Whether the player is in the drowning pre-death phase.
         * ROM: 120-frame countdown where controls are locked, ySpeed increases by $10/frame,
         * and the drown animation (0x17) plays. After 120 frames, transitions to dead=true.
         * ROM ref: s2.asm:41729-41768, s1disasm/0A Drowning Countdown.asm:229-264
         */
        protected boolean drowningDeath = false;

        /**
         * Timer for the drowning pre-death phase. Counts down from 120 frames.
         * When it reaches 0, the player transitions to the dead state.
         */
        protected int drownPreDeathTimer = 0;

        /**
         * Whether or not this sprite is in the hurt/knockback state.
         * Mirrors ROM routine=4 check. Invulnerability is set when landing from hurt.
         */
        protected boolean hurt = false;

        /**
         * Raw ROM {@code routine(a0)} byte for player objects whose dispatch has been
         * temporarily swapped out for a custom ROM object (e.g. {@code
         * Obj_Sonic_RotatingSlotBonus}, sonic3k.asm:98656) that reuses {@code Player_1}'s
         * {@code routine} field for its own state machine (values 0/2/4 selecting the
         * object's init/main-loop/goal-exit handlers, sonic3k.asm:98700-98703) rather than
         * the standard Sonic control routine values {@link #hurt}/{@link #dead} already
         * model. When non-null, {@code TraceCharacterState.routineFromSprite} reports this
         * value verbatim instead of deriving one from hurt/dead/CPU state, matching a
         * hardware trace recorder that samples the raw memory offset regardless of which
         * object code is running there. Null (the default) preserves the existing
         * hurt/dead-derived heuristic for ordinary player control.
         */
        protected Integer objectRoutineOverride = null;

        /**
         * The ROM's {@code restartime}: frames before the level restarts after
         * death. Armed with 60 when the corpse falls past the death row, then
         * decremented each frame; the restart flag is written on the decrement
         * that reaches zero. A zero value means "do not restart the level" and
         * is never counted down (S1 {@code Sonic_ResetLevel},
         * docs/s1disasm/_incObj/01 Sonic.asm:2065-2073).
         */
        protected int deathCountdown = 0;

        /**
         * Whether the corpse has reached the ROM's post-fall death routine
         * (S1 routine 8 {@code Sonic_ResetLevel}, S2 {@code Obj01_Gone},
         * S3K {@code loc_1257C}). That routine only counts {@code restartime}
         * down, so this — not a non-zero {@link #deathCountdown} — is what
         * stops gravity being applied to the corpse. The two differ on the
         * game-over and time-over paths, which enter the routine with
         * {@code restartime} deliberately left at zero.
         */
        protected boolean deathRestartRoutineActive = false;

        /**
         * Whether or not this sprite is preparing for a spindash.
         */
        protected boolean spindash = false;
        /**
         * Whether or not this sprite is crouching.
         */
        protected boolean crouching = false;

        /**
         * Whether or not this sprite is looking up (holding up while standing still).
         */
        protected boolean lookingUp = false;

        /**
         * ROM: Balance animation state when standing at a ledge edge.
         * 0 = not balancing
         * 1 = BALANCE (0x06) - safe distance, facing toward edge
         * 2 = BALANCE2 (0x0C) - closer to edge, facing toward edge
         * 3 = BALANCE3 (0x1D) - safe distance, facing away from edge
         * 4 = BALANCE4 (0x1E) - closer to edge, facing away from edge
         * See s2.asm:36246-36373 for the balance detection logic.
         */
        protected int balanceState = 0;

        /**
         * ROM-accurate spindash counter (spindash_counter).
         * Range: 0x000 to 0x800 (0 to 2048).
         * Speed table is indexed by counter >> 8 (gives 0-8).
         */
        protected short spindashCounter = 0;

        /**
         * ROM: Sonic_Look_delay_counter / Tails_Look_delay_counter
         * Counter for look up/down camera pan delay. Increments each frame while
         * up/down is held. Camera only starts panning after this reaches 0x78 (120 frames).
         * Reset to 0 when neither up nor down is pressed.
         */
        protected short lookDelayCounter = 0;

        /**
         * ROM: player byte at $41(a0), used by Player_WalkVertR as a short-lived
         * countdown after deep right-wall penetration in zone/act 0.
         */
        protected int rightWallPenetrationTimer = 0;

        private PlayerSpriteRenderer spriteRenderer;
        private int mappingFrame = 0;
        private int renderFlagWidthPixels = 0x18;
        private boolean renderFlagOnScreen = true;
        private boolean renderFlagOnScreenValid = false;
        private int animationFrameCount = 0;
        private SpriteAnimationProfile animationProfile;
        private SpriteAnimationSet animationSet;
        private int animationId = 0;
        /** When >= 0, overrides profile-based animation resolution (e.g., Tails CPU fly anim). */
        private int forcedAnimationId = -1;
        /** Resolved native animation ID for CanonicalAnimation.BUBBLE. -1 if unsupported. */
        private int bubbleAnimId = -1;
        /**
         * When true, object code owns mapping_frame updates for this player.
         * Animation manager still handles flip state and controllers, but does not
         * overwrite mapping_frame.
         */
        private boolean objectMappingFrameControl = false;
        private int animationFrameIndex = 0;
        private int animationTick = 0;
        private boolean renderHFlip = false;
        private boolean renderVFlip = false;
        private boolean highPriority = false;
        private int priorityBucket = RenderPriority.PLAYER_DEFAULT;

        protected boolean shield = false;
        private ShieldType shieldType = null;
        private PowerUpObject shieldObject;
        private InstaShieldHandle instaShieldObject;
        private boolean instaShieldRegistered = false;
        private PowerUpObject invincibilityObject;
        private PowerUpSpawner powerUpSpawner;
        protected boolean speedShoes = false;
        /**
         * Super Sonic state flag.
         * Exposed for object scripts that branch on Super Sonic (e.g. ObjB2 jump timing).
         */
        protected boolean superSonic = false;

        // Physics provider fields — populated from GameModule when available
        private PhysicsProfile physicsProfile;
        private GameModule runtimeBoundStateModule;
        private PhysicsModifiers physicsModifiers;
        private GameRules gameRules;

        /**
         * Canonical "reset" profile — the base used for water/shoes modifier math.
         * For S3K, this is SONIC_2_SONIC ($600/$C/$80), which differs from the init profile.
         * For S1/S2, this is null (init profile equals canonical).
         */
        private PhysicsProfile canonicalProfile;
        /**
         * When true, the Character_Speeds init values (from {@code PhysicsProvider.getInitProfile()})
         * are active in the mutable speed fields. Cleared on first water, speed shoes, or Super
         * transition — those events reset the mutable fields to the canonical profile values.
         * <p>ROM ref: sonic3k.asm:202288 (Character_Speeds table loaded at init/respawn).
         */
        private boolean initPhysicsActive;

        /**
         * When true, forces right input regardless of actual keyboard input.
         * Used for end-of-act walk-off sequences (Control_Locked + button_right_mask in
         * ROM).
         */
        protected boolean forceInputRight = false;
        /**
         * ROM-style forced logical input bits set by object scripts.
         */
        protected int forcedInputMask = 0;
        /**
         * When true, BK2 playback detected a new action button press edge (A/B/C cycling).
         */
        protected boolean forcedJumpPress = false;
        protected boolean suppressNextJumpPress = false;
        protected boolean deferredObjectControlRelease = false;
        private boolean suppressNextGravityStep = false;
        private int suppressedObjectMoveAndFallAxes = 0;
        /**
         * When true, user inputs are ignored (Control_Locked in ROM).
         */
        protected boolean controlLocked = false;
        private boolean hasQueuedControlLockedState = false;
        private boolean queuedControlLocked = false;
        private boolean hasQueuedForceInputRightState = false;
        private boolean queuedForceInputRight = false;
        /**
         * Movement lock timer (ROM: move_lock). When > 0, player input is ignored
         * and the player cannot move. Decremented each frame.
         * Used by: air bubble collection (35 frames), springs, hurt state, etc.
         */
        protected int moveLockTimer = 0;
        /**
         * When true, an object has full control of the player and normal physics
         * (gravity, movement, collision) are skipped. This matches the ROM's
         * obj_control = $81 behavior used by spin tubes, corkscrews, etc.
         */
        protected boolean objectControlled = false;
        /**
         * Companion flag to {@link #objectControlled}: when {@code true} the
         * controlling object owns physics (object_control bits 0-6 in ROM) but
         * the sidekick CPU AI dispatcher is allowed to run, matching ROM's
         * {@code bmi.w} (bit 7) check at {@code sonic3k.asm:26672}. Default
         * {@code false} preserves existing engine behaviour for the bit-7
         * (flight / despawn / super state / debug) callers. Cleared whenever
         * {@link #setObjectControlled(boolean)} is set to {@code false}.
         */
        protected boolean objectControlAllowsCpu = false;
        /**
         * ROM {@code object_control} bit 0 equivalent. S3K uses this bit to skip
         * the normal player movement dispatch, independently from the bit-7 CPU
         * and touch-response gates. Defaulting this to true when object control is
         * asserted preserves existing engine semantics for legacy callers.
         */
        protected boolean objectControlSuppressesMovement = false;
        /**
         * When true, airborne terrain collision is suppressed for this frame.
         * Set by zone feature providers (e.g., HCZ vertical water tunnels) to
         * prevent false collision contacts from stalling the player.  Cleared
         * each frame by the zone handler that sets it.
         */
        protected boolean suppressAirCollision = false;
        /**
         * ROM object_control bit 6 for objects that own curved/loop movement
         * while still letting normal player movement run. Sonic_WalkSpeed skips
         * CalcRoomInFront when this bit is set.
         */
        protected boolean suppressGroundWallCollision = false;
        /**
         * When true, the airborne floor check in quadrants 0x40/0xC0 runs even
         * when ySpeed &lt; 0.  ROM equivalent: {@code WindTunnel_flag} gating
         * at sonic3k.asm:24204/24299.  Set by zone feature providers (e.g.,
         * HCZ horizontal water tunnels) so pipe walls constrain the player
         * vertically even when the tunnel pushes upward.
         */
        protected boolean forceFloorCheck = false;
        /**
         * When true, the sprite is not rendered. Used by the Giant Ring flash
         * to make Sonic invisible during the special stage entry sequence.
         * ROM: move.b #id_Null,(v_player+obAnim).w
         */
        protected boolean hidden = false;
        /**
         * Whether the ROM's native player SST slot still exists. The engine keeps
         * the structural sprite instance across transitions, so object code that
         * clears the native slot must clear this independently of visibility and
         * object-control flags.
         */
        private boolean nativeSlotPresent = true;
        /**
         * Frame number when the player was last released from object control.
         * Used to prevent immediate re-capture by nearby objects (e.g., spin tubes).
         */
        protected int objectControlReleasedFrame = Integer.MIN_VALUE;
        /**
         * Tracks whether the jump button is currently pressed this frame.
         * Set by movement manager, used by objects (like flippers) to detect jump
         * input.
         */
        protected boolean jumpInputPressed = false;
        /**
         * Tracks whether jump transitioned from not-pressed to pressed this frame,
         * including forced/demo input, matching the ROM's logical-pad low-byte checks.
         */
        protected boolean jumpInputJustPressed = false;
        /**
         * Previous frame combined jump state (player input OR forced input).
         */
        protected boolean jumpInputPressedPreviousFrame = false;
        /**
         * Tracks whether the up button is currently pressed this frame.
         * Set by SpriteManager, used by objects (like VineSwitch) to detect directional input.
         */
        protected boolean upInputPressed = false;
        /**
         * Tracks whether the down button is currently pressed this frame.
         * Set by SpriteManager, used by objects (like VineSwitch) to detect directional input.
         */
        protected boolean downInputPressed = false;
        /**
         * Tracks whether the left button is currently pressed this frame.
         * Set by SpriteManager, used by objects (like Grabber) to detect directional input.
         */
        protected boolean leftInputPressed = false;
        /**
         * Tracks whether the right button is currently pressed this frame.
         * Set by SpriteManager, used by objects (like Grabber) to detect directional input.
         */
        protected boolean rightInputPressed = false;
        /**
         * Tracks whether the player is actively pressing a movement direction this frame,
         * after filtering for control locks and move locks. Used by animation system to
         * determine walk vs idle animation per ROM behavior (s2.asm:36558, 36619).
         * ROM: Walking animation is set in Sonic_MoveLeft/MoveRight, which are only called
         * when directional input passes control lock checks.
         */
        protected boolean movementInputActive = false;
        private int spiralActiveFrame = Integer.MIN_VALUE;
        private byte flipAngle = 0;
        private byte flipType = 0;
        private byte flipSpeed = 0;
        private byte flipsRemaining = 0;
        private boolean flipTurned = false;

        /**
         * Whether this sprite is currently underwater.
         * Affects physics constants and triggers entry/exit speed changes.
         */
        protected boolean inWater = false;
        /**
         * Tracks the ROM speed-constant block independently from Status_Underwater.
         * Direct status-byte writes can clear the underwater bit without running
         * the water-exit routine that restores max speed, acceleration, and
         * deceleration.
         */
        protected boolean waterPhysicsActive = false;
        protected boolean preventTailsRespawn = false;
        /**
         * Previous frame's water state, used for detecting transitions.
         */
        protected boolean wasInWater = false;
        /**
         * When true, the player is running across the water surface (HCZ skim).
         * Prevents onEnterWater() from triggering while feet are at water level.
         * Set by {@link com.openggf.game.sonic3k.features.HCZWaterSkimHandler}.
         */
        protected boolean waterSkimActive = false;
        /**
         * Manages drowning mechanics while underwater (air countdown, bubbles, etc.).
         */

        /**
         * Sets the power-up spawner used to create shield, invincibility, splash
         * and insta-shield objects. Injected by {@code LevelManager} during player
         * initialization so the sprite does not need to know the concrete object types.
         */
        public void setPowerUpSpawner(PowerUpSpawner spawner) {
                this.powerUpSpawner = spawner;
                ensurePersistentInstaShieldObject();
        }

        /**
         * Returns the current power-up spawner, or {@code null} if not yet injected.
         */
        public PowerUpSpawner getPowerUpSpawner() {
                return powerUpSpawner;
        }

        /**
         * Clears all active power-ups (shield, invincibility, speed shoes).
         * Called when entering special stage to remove power-up effects.
         */
        public void clearPowerUps() {
                // Clear shield
                this.shield = false;
                this.shieldType = null;
                if (this.shieldObject != null) {
                        this.shieldObject.destroy();
                        this.shieldObject = null;
                }
                // Clear invincibility
                if (this.invincibilityObject != null) {
                        this.invincibilityObject.destroy();
                        this.invincibilityObject = null;
                }
                this.invincibleFrames = 0;
                // Clear speed shoes
                if (this.speedShoes) {
                        this.speedShoes = false;
                        currentTimerManager().removeTimerForCode("SpeedShoes-" + getCode());
                        defineSpeeds(); // Reset speeds to default
                }
                // Clear Super state
                this.superSonic = false;
                controller.resetSuperState();
        }

        public void resetState() {
                controller.clearCarryAndReleaseMain();
                this.shield = false;
                this.shieldType = null;
                if (this.shieldObject != null) {
                        this.shieldObject.destroy();
                        this.shieldObject = null;
                }
                if (this.invincibilityObject != null) {
                        this.invincibilityObject.destroy();
                        this.invincibilityObject = null;
                }
                this.speedShoes = false;
                // Cancel any active speed shoes timer
                currentTimerManager().removeTimerForCode("SpeedShoes-" + getCode());
                this.invincibleFrames = 0;
                this.invulnerableFrames = 0;
                // Ring count is managed by LevelGamestate and reset by LevelManager
                this.dead = false;
                this.drowningDeath = false;
                this.drownPreDeathTimer = 0;
                this.hurt = false;
                this.deathCountdown = 0;
                this.deathRestartRoutineActive = false;
                this.air = false;
                this.jumping = false;
                this.doubleJumpFlag = 0;
                this.doubleJumpProperty = 0;
                this.objectMappingFrameControl = false;
                // Level clears Object_RAM before Obj01_Main creates Sonic. In
                // particular obAnim, obFrame, obAniFrame and obTimeFrame all
                // begin at zero before the pre-fade BuildSprites pass
                // (sonic.asm Level / Sonic_Main). OpenGGF reuses this object,
                // so explicitly discard the previous scene's running frame.
                this.animationId = 0;
                this.mappingFrame = 0;
                this.animationFrameIndex = 0;
                this.animationTick = 0;
                forceAnimationRestart();
                this.onObject = false;
                this.latchedSolidObjectId = 0;
                this.sliding = false;
                this.stickToConvex = false;
                this.suppressGroundWallCollision = false;
                // Reset ground mode to GROUND - critical for sensor direction on level load.
                // Without this, if player was on a wall/ceiling when previous level ended,
                // sensors would point in wrong direction and collision detection would fail.
                this.runningMode = GroundMode.GROUND;
                this.springing = false;
                this.springingFrames = 0;
                this.rolling = false;
                this.rollingJump = false;
                this.pinballMode = false;
                this.pinballSpeedLock = false;
                this.preserveRollingOnNextLanding = false;
                this.preserveRollingOnNextRollStop = false;
                this.objectPreservedRollBoostFollowup = false;
                this.objectPreservedRollWallProbe = false;
                this.objectPreservedRollVelocityCarry = false;
                this.tunnelMode = false;
                this.spindash = false;
                this.lookDelayCounter = 0;
                this.rightWallPenetrationTimer = 0;
                this.pushing = false;
                this.skidding = false;
                this.skidDustTimer = 0;
                this.fixedSkidDustActive = false;
                this.crouching = false;
                this.lookingUp = false;
                this.balanceState = 0;
                // The ROM holds no balance state; Sonic_Balance re-derives it every
                // grounded standing frame from tilt/next_tilt, which the player tail
                // copies out of Primary_Angle/Secondary_Angle (sonic3k.asm:21999-22000
                // Sonic, :26243-26244 Tails). A level load zeroes those SST bytes with
                // the rest of Object_RAM, so the first Sonic_Balance of a new act can
                // never see the empty-tile sentinel 3 left by the previous act.
                controller.getMovement().resetGroundAngleLatches();
                this.highPriority = false;
                this.priorityBucket = RenderPriority.PLAYER_DEFAULT;
                this.forceInputRight = false;
                this.forcedInputMask = 0;
                this.forcedAnimationId = -1;
                this.controlLocked = false;
                this.hasQueuedControlLockedState = false;
                this.queuedControlLocked = false;
                this.hasQueuedForceInputRightState = false;
                this.queuedForceInputRight = false;
                this.moveLockTimer = 0;
                this.objectControlled = false;
                this.objectControlAllowsCpu = false;
                this.objectControlSuppressesMovement = false;
                this.suppressedObjectMoveAndFallAxes = 0;
                controller.clearObjectControlledSolidContactOwner();
                this.hidden = false;
                this.nativeSlotPresent = true;
                this.objectControlReleasedFrame = Integer.MIN_VALUE;
                this.jumpInputPressed = false;
                this.jumpInputJustPressed = false;
                this.jumpInputPressedPreviousFrame = false;
                this.upInputPressed = false;
                this.downInputPressed = false;
                this.leftInputPressed = false;
                this.rightInputPressed = false;
                this.logicalInputState = 0;
                this.renderFlagWidthPixels = 0x18;
                this.renderFlagOnScreen = true;
                this.renderFlagOnScreenValid = false;
                this.movementInputActive = false;
                this.spiralActiveFrame = Integer.MIN_VALUE;
                this.flipAngle = 0;
                this.flipType = 0;
                this.flipSpeed = 0;
                this.flipsRemaining = 0;
                this.flipTurned = false;
                this.inWater = false;
                this.wasInWater = false;
                this.waterPhysicsActive = false;
                this.waterSkimActive = false;
                this.preventTailsRespawn = false;
                this.superSonic = false;
                controller.resetSuperState();
                // Reset collision path to Path 0 (primary collision).
                // Without this, if player was on Path 1 in previous level,
                // solidity bits would remain 0x0E/0x0F causing collision checks
                // against the wrong collision map, making player fall through floors.
                this.topSolidBit = 0x0C;
                this.lrbSolidBit = 0x0D;
                this.loopLowPlane = false;
                this.statusTertiary = 0;
                defineSpeeds(); // Reset speeds to default
                instaShieldRegistered = false; // Force re-registration with new ObjectManager on level load
                resolvePhysicsProfile();
                // ROM: Obj01_Init unconditionally sets y_radius=$13, x_radius=9.
                // Since we reuse the sprite rather than recreating it, we must
                // explicitly restore standing dimensions and sensor offsets here.
                setHeight(runHeight);
                applyStandingRadii(false);
        }

        // -----------------------------------------------------------------------
        // Rewind state capture / restore
        // -----------------------------------------------------------------------

        /** Captures the mutable playable-sprite surface for rewind restore. */
        public PerObjectRewindSnapshot captureRewindState() {
                return captureRewindState(true);
        }

        public PerObjectRewindSnapshot captureRewindState(boolean includeFollowHistory) {
                SidekickCpuRewindExtra sidekickCpuExtra =
                        cpuController != null ? cpuController.captureRewindState() : null;
                PlayerRewindExtra extra = new PlayerRewindExtra(
                        // AbstractSprite base fields
                        xPixel, yPixel,
                        xSubpixel, ySubpixel,
                        width, height,
                        direction, layer,
                        runningMode, xRadius, yRadius,
                        // Movement / physics
                        gSpeed, xSpeed, ySpeed, jump,
                        angle, statusTertiary, loopLowPlane,
                        topSolidBit, lrbSolidBit,
                        prePhysicsAir, prePhysicsAngle,
                        prePhysicsGSpeed, prePhysicsXSpeed, prePhysicsYSpeed,
                        preZoneFeatureGSpeed,
                        prePhysicsCentreX, prePhysicsCentreY,
                        air, rolling, jumping, rollingJump,
                        pinballMode, pinballSpeedLock, preserveRollingOnNextLanding,
                        preserveRollingOnNextRollStop, objectPreservedRollBoostFollowup,
                        objectPreservedRollWallProbe, objectPreservedRollVelocityCarry, tunnelMode,
                        onObject, controller.isOnObjectAtFrameStart(), controller.isOnObjectAtPreviousFrameStart(),
                        controller.isPushingAtFrameStart(), controller.isHurtAtFrameStart(),
                        controller.isHurtRecoveryCompletedThisFrame(),
                        latchedSolidObjectId, interactSlotIndex, slopeRepelJustSlipped,
                        stickToConvex, sliding, pushing,
                        skidding, skidDustTimer, fixedSkidDustActive,
                        controller.getMovement().captureLastFixedSkidDustTickFrame(),
                        wallClimbX, rightWallPenetrationTimer,
                        balanceState,
                        springing, springingFrames,
                        dead, drowningDeath, drownPreDeathTimer,
                        hurt, deathCountdown, deathRestartRoutineActive,
                        invulnerableFrames, suppressNextInvulnerabilityDecrement, invincibleFrames,
                        spindash, spindashCounter,
                        crouching, lookingUp, lookDelayCounter,
                        doubleJumpFlag, doubleJumpProperty,
                        shield, shieldType, instaShieldRegistered,
                        speedShoes, currentSpeedShoesRemainingTicks(), superSonic,
                        forceInputRight, forcedInputMask,
                        forcedJumpPress, suppressNextJumpPress,
                        deferredObjectControlRelease,
                        controlLocked, hasQueuedControlLockedState, queuedControlLocked,
                        hasQueuedForceInputRightState, queuedForceInputRight,
                        moveLockTimer,
                        objectControlled, objectControlAllowsCpu, objectControlSuppressesMovement,
                        objectControlReleasedFrame,
                        suppressAirCollision, suppressGroundWallCollision, forceFloorCheck,
                        suppressedObjectMoveAndFallAxes,
                        hidden, nativeSlotPresent,
                        renderFlagOnScreen, renderFlagOnScreenValid,
                        renderHFlip, renderVFlip,
                        controller.isSpringHandoffPending(),
                        controller.getSpringHandoffXVelocity(),
                        controller.getSpringHandoffYVelocity(),
                        jumpInputPressed, jumpInputJustPressed, jumpInputPressedPreviousFrame,
                        upInputPressed, downInputPressed,
                        leftInputPressed, rightInputPressed,
                        movementInputActive,
                        logicalInputState, logicalJumpPressState,
                        cpuControlled, historyPos, followerHistoryRecordedThisTick,
                        spiralActiveFrame, flipAngle, flipType, flipSpeed,
                        flipsRemaining, flipTurned,
                        inWater, waterPhysicsActive, wasInWater, waterSkimActive,
                        preventTailsRespawn,
                        badnikChainCounter,
                        bubbleAnimId,
                        initPhysicsActive,
                        objectMappingFrameControl,
                        mappingFrame,
                        animationId,
                        forcedAnimationId,
                        animationFrameIndex,
                        animationTick,
                        debugMode,
                        controller.captureRewindState(),
                        sidekickCpuExtra,
                        includeFollowHistory ? xHistory : null,
                        includeFollowHistory ? yHistory : null,
                        includeFollowHistory ? inputHistory : null,
                        includeFollowHistory ? jumpPressHistory : null,
                        includeFollowHistory ? statusHistory : null);
                // Player snapshots use a stub PerObjectRewindSnapshot (no badnikExtra; playerExtra holds everything).
                return new PerObjectRewindSnapshot(
                        false, false,       // destroyed, destroyedRespawnable
                        false, 0, 0,         // hasDynamicSpawn, dynamicSpawnX, dynamicSpawnY
                        0, 0, false, 0,      // preUpdateX/Y, preUpdateValid, preUpdateCollisionFlags
                        false, false,        // skipTouchThisFrame, solidContactFirstFrame
                        0, -1,               // slotIndex, respawnStateIndex
                        null,                // badnikExtra
                        null,                // badnikSubclassExtra
                        extra                // playerExtra
                );
        }

        /**
         * Restores the full mutable gameplay surface of this playable sprite from a
         * {@link PerObjectRewindSnapshot}.  Throws {@link IllegalStateException} if the
         * snapshot has no {@link PlayerRewindExtra} — every snapshot for a player object
         * must have been produced by this class's {@link #captureRewindState()}.
         */
        public void restoreRewindState(PerObjectRewindSnapshot s) {
                PlayerRewindExtra extra = s.playerExtra();
                if (extra == null) {
                        throw new IllegalStateException(
                                "AbstractPlayableSprite.restoreRewindState requires PlayerRewindExtra");
                }
                // AbstractSprite base fields
                this.xPixel = extra.xPixel();
                this.yPixel = extra.yPixel();
                this.xSubpixel = extra.xSubpixel();
                this.ySubpixel = extra.ySubpixel();
                this.width = extra.width();
                this.height = extra.height();
                this.direction = extra.direction();
                this.layer = extra.layer();
                this.runningMode = extra.runningMode();
                setCollisionRadii(extra.xRadius(), extra.yRadius(), false);
                // Movement / physics
                this.gSpeed = extra.gSpeed();
                this.xSpeed = extra.xSpeed();
                this.ySpeed = extra.ySpeed();
                this.jump = extra.jump();
                this.angle = extra.angle();
                this.statusTertiary = extra.statusTertiary();
                this.loopLowPlane = extra.loopLowPlane();
                this.topSolidBit = extra.topSolidBit();
                this.lrbSolidBit = extra.lrbSolidBit();
                this.prePhysicsAir = extra.prePhysicsAir();
                this.prePhysicsAngle = extra.prePhysicsAngle();
                this.prePhysicsGSpeed = extra.prePhysicsGSpeed();
                this.prePhysicsXSpeed = extra.prePhysicsXSpeed();
                this.prePhysicsYSpeed = extra.prePhysicsYSpeed();
                this.preZoneFeatureGSpeed = extra.preZoneFeatureGSpeed();
                this.prePhysicsCentreX = extra.prePhysicsCentreX();
                this.prePhysicsCentreY = extra.prePhysicsCentreY();
                this.air = extra.air();
                this.rolling = extra.rolling();
                this.jumping = extra.jumping();
                this.rollingJump = extra.rollingJump();
                this.pinballMode = extra.pinballMode();
                this.pinballSpeedLock = extra.pinballSpeedLock();
                this.preserveRollingOnNextLanding = extra.preserveRollingOnNextLanding();
                this.preserveRollingOnNextRollStop = extra.preserveRollingOnNextRollStop();
                this.objectPreservedRollBoostFollowup = extra.objectPreservedRollBoostFollowup();
                this.objectPreservedRollWallProbe = extra.objectPreservedRollWallProbe();
                this.objectPreservedRollVelocityCarry = extra.objectPreservedRollVelocityCarry();
                this.tunnelMode = extra.tunnelMode();
                this.onObject = extra.onObject();
                controller.restoreFrameStartState(extra.onObjectAtFrameStart(),
                                extra.onObjectAtPreviousFrameStart(), extra.pushingAtFrameStart(),
                                extra.hurtAtFrameStart(), extra.hurtRecoveryCompletedThisFrame());
                this.latchedSolidObjectId = extra.latchedSolidObjectId();
                this.interactSlotIndex = extra.interactSlotIndex();
                this.slopeRepelJustSlipped = extra.slopeRepelJustSlipped();
                this.stickToConvex = extra.stickToConvex();
                this.sliding = extra.sliding();
                this.pushing = extra.pushing();
                this.skidding = extra.skidding();
                this.skidDustTimer = extra.skidDustTimer();
                this.fixedSkidDustActive = extra.fixedSkidDustActive();
                controller.getMovement().restoreLastFixedSkidDustTickFrame(
                                extra.lastFixedSkidDustTickFrame());
                this.wallClimbX = extra.wallClimbX();
                this.rightWallPenetrationTimer = extra.rightWallPenetrationTimer();
                this.balanceState = extra.balanceState();
                this.springing = extra.springing();
                this.springingFrames = extra.springingFrames();
                this.dead = extra.dead();
                this.drowningDeath = extra.drowningDeath();
                this.drownPreDeathTimer = extra.drownPreDeathTimer();
                this.hurt = extra.hurt();
                this.deathCountdown = extra.deathCountdown();
                this.deathRestartRoutineActive = extra.deathRestartRoutineActive();
                this.invulnerableFrames = extra.invulnerableFrames();
                this.suppressNextInvulnerabilityDecrement = extra.suppressNextInvulnerabilityDecrement();
                this.invincibleFrames = extra.invincibleFrames();
                this.spindash = extra.spindash();
                this.spindashCounter = extra.spindashCounter();
                this.crouching = extra.crouching();
                this.lookingUp = extra.lookingUp();
                this.lookDelayCounter = extra.lookDelayCounter();
                this.doubleJumpFlag = extra.doubleJumpFlag();
                this.doubleJumpProperty = extra.doubleJumpProperty();
                this.shield = extra.shield();
                this.shieldType = extra.shieldType();
                this.instaShieldRegistered = extra.instaShieldRegistered();
                this.speedShoes = extra.speedShoes();
                restoreSpeedShoesTimer(extra.speedShoesRemainingTicks());
                this.superSonic = extra.superSonic();
                this.forceInputRight = extra.forceInputRight();
                this.forcedInputMask = extra.forcedInputMask();
                this.forcedJumpPress = extra.forcedJumpPress();
                this.suppressNextJumpPress = extra.suppressNextJumpPress();
                this.deferredObjectControlRelease = extra.deferredObjectControlRelease();
                this.controlLocked = extra.controlLocked();
                this.hasQueuedControlLockedState = extra.hasQueuedControlLockedState();
                this.queuedControlLocked = extra.queuedControlLocked();
                this.hasQueuedForceInputRightState = extra.hasQueuedForceInputRightState();
                this.queuedForceInputRight = extra.queuedForceInputRight();
                this.moveLockTimer = extra.moveLockTimer();
                this.objectControlled = extra.objectControlled();
                this.objectControlAllowsCpu = extra.objectControlAllowsCpu();
                this.objectControlSuppressesMovement = extra.objectControlSuppressesMovement();
                this.objectControlReleasedFrame = extra.objectControlReleasedFrame();
                this.suppressAirCollision = extra.suppressAirCollision();
                this.suppressGroundWallCollision = extra.suppressGroundWallCollision();
                this.forceFloorCheck = extra.forceFloorCheck();
                this.suppressedObjectMoveAndFallAxes = extra.suppressedObjectMoveAndFallAxes();
                this.hidden = extra.hidden();
                this.nativeSlotPresent = extra.nativeSlotPresent();
                this.renderFlagOnScreen = extra.renderFlagOnScreen();
                this.renderFlagOnScreenValid = extra.renderFlagOnScreenValid();
                this.renderHFlip = extra.renderHFlip();
                this.renderVFlip = extra.renderVFlip();
                controller.restoreSpringHandoff(extra.mgzTopPlatformSpringHandoffPending(),
                                extra.mgzTopPlatformSpringHandoffXVel(), extra.mgzTopPlatformSpringHandoffYVel());
                this.jumpInputPressed = extra.jumpInputPressed();
                this.jumpInputJustPressed = extra.jumpInputJustPressed();
                this.jumpInputPressedPreviousFrame = extra.jumpInputPressedPreviousFrame();
                this.upInputPressed = extra.upInputPressed();
                this.downInputPressed = extra.downInputPressed();
                this.leftInputPressed = extra.leftInputPressed();
                this.rightInputPressed = extra.rightInputPressed();
                this.movementInputActive = extra.movementInputActive();
                this.logicalInputState = extra.logicalInputState();
                this.logicalJumpPressState = extra.logicalJumpPressState();
                this.cpuControlled = extra.cpuControlled();
                this.historyPos = extra.historyPos();
                this.followerHistoryRecordedThisTick = extra.followerHistoryRecordedThisTick();
                this.spiralActiveFrame = extra.spiralActiveFrame();
                this.flipAngle = extra.flipAngle();
                this.flipType = extra.flipType();
                this.flipSpeed = extra.flipSpeed();
                this.flipsRemaining = extra.flipsRemaining();
                this.flipTurned = extra.flipTurned();
                this.inWater = extra.inWater();
                this.waterPhysicsActive = extra.waterPhysicsActive();
                this.wasInWater = extra.wasInWater();
                this.waterSkimActive = extra.waterSkimActive();
                this.preventTailsRespawn = extra.preventTailsRespawn();
                this.badnikChainCounter = extra.badnikChainCounter();
                this.bubbleAnimId = extra.bubbleAnimId();
                this.initPhysicsActive = extra.initPhysicsActive();
                this.objectMappingFrameControl = extra.objectMappingFrameControl();
                this.mappingFrame = extra.mappingFrame();
                this.animationId = extra.animationId();
                this.forcedAnimationId = extra.forcedAnimationId();
                this.animationFrameIndex = extra.animationFrameIndex();
                this.animationTick = extra.animationTick();
                this.debugMode = extra.debugMode();
                // Carry is shared player state. Restore it before CPU sequencing,
                // whose restored routine may consume the carry context immediately.
                controller.restoreRewindState(extra.controllerState());
                if (extra.sidekickCpuExtra() != null) {
                        if (cpuController == null) {
                                throw new IllegalStateException(
                                        "Cannot restore SidekickCpuController state without a live controller");
                        }
                        cpuController.restoreRewindState(extra.sidekickCpuExtra());
                }
                // Sidekick follow-history circular buffers. Without restoring these,
                // the follower reads stale leader-position history and diverges on
                // the very first replay step.
                if (extra.xHistory() != null) {
                        System.arraycopy(extra.xHistory(), 0, this.xHistory, 0,
                                Math.min(extra.xHistory().length, this.xHistory.length));
                }
                if (extra.yHistory() != null) {
                        System.arraycopy(extra.yHistory(), 0, this.yHistory, 0,
                                Math.min(extra.yHistory().length, this.yHistory.length));
                }
                if (extra.inputHistory() != null) {
                        System.arraycopy(extra.inputHistory(), 0, this.inputHistory, 0,
                                Math.min(extra.inputHistory().length, this.inputHistory.length));
                }
                if (extra.jumpPressHistory() != null) {
                        System.arraycopy(extra.jumpPressHistory(), 0, this.jumpPressHistory, 0,
                                Math.min(extra.jumpPressHistory().length, this.jumpPressHistory.length));
                }
                if (extra.statusHistory() != null) {
                        System.arraycopy(extra.statusHistory(), 0, this.statusHistory, 0,
                                Math.min(extra.statusHistory().length, this.statusHistory.length));
                }
                // Sensor offsets are derived from restored radii plus air/angle/running mode.
                // Recompute after direct field hydration so rewind does not keep offsets from
                // the pre-restore sprite state.
                updateSensorOffsetsFromRadii();
        }

        /**
         * Recreates power-up visuals after all rewind adapters have restored. The
         * object manager restores after sprites, so visual rebinding must be
         * deferred until the registry's post-restore phase.
         */
        public void refreshPowerUpObjectsAfterRewindRestore() {
                if (!shield || shieldType == null) {
                        if (shieldObject != null) {
                                shieldObject.destroy();
                                shieldObject = null;
                        }
                        shield = false;
                        shieldType = null;
                } else {
                        PowerUpObject liveShield = resolveLiveShieldObjectAfterRewindRestore();
                        if (liveShield != null) {
                                shieldObject = liveShield;
                                if (invincibleFrames > 0) {
                                        shieldObject.setVisible(false);
                                }
                                shieldObject.refreshArtAfterRewindRestore();
                        } else {
                                if (shieldObject != null && !shieldObject.isDestroyed()) {
                                        shieldObject.destroy();
                                }
                                shieldObject = null;
                                if (powerUpSpawner != null) {
                                        shieldObject = powerUpSpawner.spawnShield(this, shieldType);
                                        if (shieldObject != null && invincibleFrames > 0) {
                                                shieldObject.setVisible(false);
                                        }
                                        if (shieldObject != null) {
                                                shieldObject.refreshArtAfterRewindRestore();
                                        }
                                }
                        }
                }

                refreshInvincibilityStarsAfterRewindRestore();
                refreshPersistentInstaShieldAfterRewindRestore();
        }

        private void refreshInvincibilityStarsAfterRewindRestore() {
                if (invincibleFrames <= 0) {
                        if (invincibilityObject != null) {
                                invincibilityObject.destroy();
                                invincibilityObject = null;
                        }
                        if (shieldObject != null) {
                                shieldObject.setVisible(true);
                        }
                        return;
                }

                PowerUpObject liveStars = resolveLiveInvincibilityObjectAfterRewindRestore();
                if (liveStars != null) {
                        invincibilityObject = liveStars;
                        invincibilityObject.refreshArtAfterRewindRestore();
                } else {
                        if (invincibilityObject != null && !invincibilityObject.isDestroyed()) {
                                invincibilityObject.destroy();
                        }
                        invincibilityObject = null;
                        if (powerUpSpawner != null) {
                                invincibilityObject = powerUpSpawner.spawnInvincibilityStars(this);
                                if (invincibilityObject != null) {
                                        invincibilityObject.refreshArtAfterRewindRestore();
                                }
                        }
                }

                if (shieldObject != null) {
                        shieldObject.setVisible(false);
                }
        }

        private void refreshPersistentInstaShieldAfterRewindRestore() {
                if (!hasPersistentInstaShieldAbility() || powerUpSpawner == null) {
                        return;
                }
                if (instaShieldObject == null || instaShieldObject.isDestroyed()) {
                        instaShieldObject = powerUpSpawner.createInstaShield(this);
                }
                if (instaShieldObject == null) {
                        return;
                }

                ObjectManager objectManager = currentObjectManagerIfAvailable();
                if (objectManager != null && isObjectManagerLivePowerUp(objectManager, instaShieldObject)) {
                        instaShieldRegistered = true;
                        instaShieldObject.invalidateDplcCache();
                        return;
                }

                powerUpSpawner.registerObject(instaShieldObject);
                instaShieldRegistered = true;
                instaShieldObject.invalidateDplcCache();
        }

        private boolean hasPersistentInstaShieldAbility() {
                PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
                return capabilityRules != null
                                && capabilityRules.instaShieldEnabled()
                                && getSecondaryAbility() == SecondaryAbility.INSTA_SHIELD;
        }

        private PowerUpObject resolveLiveShieldObjectAfterRewindRestore() {
                ObjectManager objectManager = currentObjectManagerIfAvailable();
                if (isMatchingLiveShield(shieldObject)
                                && (objectManager == null || isObjectManagerLivePowerUp(objectManager, shieldObject))) {
                        return shieldObject;
                }
                if (objectManager == null) {
                        return null;
                }
                for (ObjectInstance object : objectManager.getActiveObjects()) {
                        if (object instanceof PowerUpObject candidate && isMatchingLiveShield(candidate)) {
                                return candidate;
                        }
                }
                return null;
        }

        private PowerUpObject resolveLiveInvincibilityObjectAfterRewindRestore() {
                ObjectManager objectManager = currentObjectManagerIfAvailable();
                if (invincibilityObject != null
                                && !invincibilityObject.isDestroyed()
                                && (objectManager == null
                                                || isObjectManagerLivePowerUp(objectManager, invincibilityObject))) {
                        return invincibilityObject;
                }
                return null;
        }

        private boolean isMatchingLiveShield(PowerUpObject candidate) {
                return candidate != null
                                && !candidate.isDestroyed()
                                && candidate.isShieldFor(this, shieldType);
        }

        private boolean isObjectManagerLivePowerUp(ObjectManager objectManager, PowerUpObject candidate) {
                return candidate instanceof ObjectInstance object
                                && objectManager.getActiveObjects().contains(object);
        }

        private ObjectManager currentObjectManagerIfAvailable() {
                LevelManager levelManager = currentLevelManagerIfAvailable();
                return levelManager != null ? levelManager.getObjectManager() : null;
        }

        public void giveShield() {
                giveShield(ShieldType.BASIC);
        }

        public void giveShield(ShieldType type) {
                // S2: Super Sonic cannot pick up shields
                if (superSonic) {
                        return;
                }
                // Remove existing shield before granting new one
                if (hasShield()) {
                        if (shieldObject != null) {
                                shieldObject.destroy();
                                shieldObject = null;
                        }
                }
                this.shield = true;
                this.shieldType = type;
                LOGGER.fine("DEBUG: Shield flag set to true, type=" + type);
                // Bubble shield replenishes air (s3.asm:34877 Player_ResetAirTimer)
                if (type == ShieldType.BUBBLE && controller != null && controller.getDrowning() != null) {
                        controller.getDrowning().replenishAir();
                }
                try {
                        this.shieldObject = powerUpSpawner != null
                                ? powerUpSpawner.spawnShield(this, type)
                                : null;
                        LOGGER.fine("DEBUG: ShieldObjectInstance created successfully: " + shieldObject);
                        // If picked up while invincible, hide shield until invincibility ends
                        if (shieldObject != null && invincibleFrames > 0) {
                                shieldObject.setVisible(false);
                        }
                } catch (Exception e) {
                        LOGGER.fine("DEBUG: Failed to create/add ShieldObjectInstance: " + e.getMessage());
                        throw e;
                }
        }

        public ShieldType getShieldType() {
                return shieldType;
        }

        /**
         * Removes the current shield (if any) without affecting other power-ups.
         * Used by objects that strip shields (e.g., LBZ2 water tunnels).
         */
        public void removeShield() {
                if (!shield) return;
                shield = false;
                shieldType = null;
                if (shieldObject != null) {
                        shieldObject.destroy();
                        shieldObject = null;
                }
        }

        public PowerUpObject getShieldObject() {
                return shieldObject;
        }

        public InstaShieldHandle getInstaShieldObject() {
                return instaShieldObject;
        }

        public void setInstaShieldObject(InstaShieldHandle obj) {
                this.instaShieldObject = obj;
        }

        /**
         * Marks the insta-shield for re-registration with the current ObjectManager.
         * Must be called after seamless transitions that rebuild the ObjectManager,
         * since the old manager (which held the dynamic object) is replaced.
         */
        public void markInstaShieldForReregistration() {
                this.instaShieldRegistered = false;
        }

        public PowerUpObject getInvincibilityObject() {
                return invincibilityObject;
        }

        public void giveSpeedShoes() {
                // S3K: shoes activation uses absolute values from canonical base ($C00/$18/$80),
                // not 2x of Character_Speeds init values (sonic3k.asm:40823-40825)
                clearInitOverride();
                this.speedShoes = true;
                // Register speed shoes timer using the existing timer framework
                // Duration is 1200 frames (20 seconds @ 60fps) per SPG Sonic 2
                currentTimerManager().registerTimer(
                                new SpeedShoesTimer("SpeedShoes-" + getCode(), this));
        }

        /**
         * Called by SpeedShoesTimer when the effect expires.
         * Deactivates speed shoes and resets physics values.
         * ROM: sets absolute canonical values ($600/$C/$80 normal, $A00/$30/$100 Super Sonic).
         */
        public void deactivateSpeedShoes() {
                clearInitOverride();
                this.speedShoes = false;
        }

        /**
         * Reads the current remaining duration of this sprite's speed-shoes
         * timer, for capture into {@link PlayerRewindExtra#speedShoesRemainingTicks()}.
         * Zero when speed shoes are not active.
         */
        private int currentSpeedShoesRemainingTicks() {
                if (!speedShoes) {
                        return 0;
                }
                Timer timer = currentTimerManager().getTimerForCode("SpeedShoes-" + getCode());
                return timer != null ? timer.getTicks() : 0;
        }

        /**
         * Re-establishes a fully behavioral speed-shoes timer after a rewind
         * restore. {@link TimerManager}'s own generic snapshot only preserves
         * (code, ticks) pairs, not timer type or the sprite reference a
         * {@link SpeedShoesTimer}'s expiry callback needs — restoring through
         * that path alone leaves the countdown as a behavior-inert placeholder
         * whose expiry never calls back into this sprite, so speed shoes never
         * turn off (they last indefinitely). {@code remainingTicks} comes from
         * this sprite's own captured {@link PlayerRewindExtra} rather than
         * TimerManager's live state, so this is correct regardless of
         * RewindRegistry's cross-subsystem restore ordering.
         */
        private void restoreSpeedShoesTimer(int remainingTicks) {
                TimerManager timerManager = currentTimerManager();
                String code = "SpeedShoes-" + getCode();
                if (!speedShoes) {
                        timerManager.removeTimerForCode(code);
                        return;
                }
                SpeedShoesTimer replacement = new SpeedShoesTimer(code, this);
                replacement.setTicks(remainingTicks > 0 ? remainingTicks : SpeedShoesTimer.ROM_DURATION_FRAMES);
                timerManager.registerTimer(replacement);
        }

        public void giveInvincibility() {
                setInvincibleFrames(1200); // 20 seconds @ 60fps
                if (shieldObject != null) {
                        shieldObject.setVisible(false);
                }
                if (invincibilityObject == null && powerUpSpawner != null) {
                        invincibilityObject = powerUpSpawner.spawnInvincibilityStars(this);
                }
        }

        public boolean hasShield() {
                return shield;
        }

        /**
         * Shows or hides the shield object without removing the shield power-up.
         * Used by Super Sonic (hides shield while active, re-shows on revert).
         */
        public void setShieldVisible(boolean visible) {
                if (shieldObject != null) {
                        shieldObject.setVisible(visible);
                }
        }

        public boolean hasSpeedShoes() {
                return speedShoes;
        }

        public boolean isSuperSonic() {
                return superSonic;
        }

        public final Camera currentCamera() { return PlayableSpriteRuntimeServices.camera(); }
        public final LevelManager currentLevelManager() { return PlayableSpriteRuntimeServices.level(); }
        public final LevelManager currentLevelManagerIfAvailable() { return PlayableSpriteRuntimeServices.levelOrNull(); }
        public final GameModule currentGameModule() { return PlayableSpriteRuntimeServices.currentOrBootstrapGameModule(); }
        public final CrossGameFeatureProvider currentCrossGameFeatures() { return PlayableSpriteRuntimeServices.crossGameFeatures(); }

        public final int resolveAnimationId(CanonicalAnimation animation) {
                refreshRuntimeBoundStateIfNeeded();
                return PlayableSpriteRuntimeServices.resolveAnimationId(currentGameModule(), animation);
        }

        public final LevelState currentLevelState() { return PlayableSpriteRuntimeServices.levelState(currentLevelManagerIfAvailable()); }
        public final TimerManager currentTimerManager() { return PlayableSpriteRuntimeServices.timers(); }
        public final GameStateManager currentGameState() { return PlayableSpriteRuntimeServices.gameState(); }
        public final GameStateManager currentGameStateOrNull() { return PlayableSpriteRuntimeServices.gameStateOrNull(); }
        public final CollisionSystem currentCollisionSystem() { return PlayableSpriteRuntimeServices.collision(); }
        public final CollisionSystem currentCollisionSystemOrNull() { return PlayableSpriteRuntimeServices.collisionOrNull(); }
        public final AudioManager currentAudioManager() { return PlayableSpriteRuntimeServices.audio(); }
        public final com.openggf.game.GameRng currentRng() { return PlayableSpriteRuntimeServices.rng(); }
        public final com.openggf.game.GameRng currentRngOrNull() { return PlayableSpriteRuntimeServices.rngOrNull(); }

        public final DrowningController getDrowningController() { return controller != null ? controller.getDrowning() : null; }
        public final TailsFlightController getTailsFlightController() {
                return controller != null ? controller.getTailsFlight() : null;
        }
        public final TailsCarryController getTailsCarryController() {
                return controller != null ? controller.getTailsCarry() : null;
        }
        public final WaterSystem currentWaterSystem() { return PlayableSpriteRuntimeServices.water(); }
        public final com.openggf.sprites.managers.SpriteManager currentSpriteManagerOrNull() {
                return PlayableSpriteRuntimeServices.spritesOrNull();
        }
        public final int currentGameplayFrameCounter() { return PlayableSpriteRuntimeServices.gameplayFrameCounter(); }

        /**
         * Returns this character's secondary (double-jump) ability.
         * ROM: character_id determines Sonic=insta-shield, Tails=fly, Knuckles=glide.
         * Subclasses override to declare their ability; default is NONE.
         */
        public SecondaryAbility getSecondaryAbility() {
                return SecondaryAbility.NONE;
        }

        /**
         * Whether this character runs the ROM's <em>Tails_RollSpeed</em> subroutine rather
         * than <em>Sonic_RollSpeed</em>/<em>Knux_RollSpeed</em>. Sonic 2 keeps a separate,
         * outdated copy for Tails whose controlled roll deceleration differs; see
         * {@code PlayerMovementRules#tailsRollSpeedUsesEffectiveDecelQuarter}.
         */
        public boolean usesTailsRollSpeedRoutine() {
                return false;
        }

        public void setSuperSonic(boolean superSonic) {
                this.superSonic = superSonic;
        }

        public int getRingCount() {
                var levelState = currentLevelState();
                return levelState != null ? levelState.getRings() : 0;
        }

        public void setRingCount(int ringCount) {
                var levelState = currentLevelState();
                if (levelState != null) {
                        levelState.setRings(ringCount);
                }
        }

        public void addRings(int delta) {
                var levelState = currentLevelState();
                if (levelState != null) {
                        levelState.addRings(delta);
                }
        }

        public PlayerSpriteRenderer getSpriteRenderer() {
                return spriteRenderer;
        }

        public void setSpriteRenderer(PlayerSpriteRenderer spriteRenderer) {
                this.spriteRenderer = spriteRenderer;
        }

        public int getMappingFrame() {
                return mappingFrame;
        }

        public void setMappingFrame(int mappingFrame) {
                this.mappingFrame = Math.max(0, mappingFrame);
        }

        /**
         * ROM parity helper for BuildSprites / render_flags.on_screen culling.
         * Sonic 2 Obj01/Obj02 init both set width_pixels(a0) = $18.
         */
        public int getRenderFlagWidthPixels() {
                return renderFlagWidthPixels;
        }

        public void setRenderFlagWidthPixels(int renderFlagWidthPixels) {
                this.renderFlagWidthPixels = Math.max(0, renderFlagWidthPixels);
        }

        public boolean isRenderFlagOnScreen() {
                return renderFlagOnScreen;
        }

        public boolean shouldRefreshRenderFlagThisFrame() {
                if (isHidden()) {
                        return false;
                }
                return isHurt()
                        || hurtRoutineOwnedDisplayThisFrame
                        || invulnerableFrames <= 0
                        || ((invulnerableFrames + 1) & 0x04) != 0;
        }

        /**
         * Consumed by the BuildSprites-equivalent render-flag refresh at the end of
         * the frame, after which the hurt routine no longer owns the display.
         */
        public void clearHurtRoutineOwnedDisplayLatch() {
                hurtRoutineOwnedDisplayThisFrame = false;
        }
        public boolean hasRenderFlagOnScreenState() {
                return renderFlagOnScreenValid;
        }

        public void setRenderFlagOnScreen(boolean renderFlagOnScreen) {
                this.renderFlagOnScreen = renderFlagOnScreen;
                this.renderFlagOnScreenValid = true;
        }

        public int getAnimationFrameCount() {
                return animationFrameCount;
        }

        public void setAnimationFrameCount(int animationFrameCount) {
                this.animationFrameCount = Math.max(0, animationFrameCount);
        }

        public SpriteAnimationProfile getAnimationProfile() {
                return animationProfile;
        }

        public void setAnimationProfile(SpriteAnimationProfile animationProfile) {
                this.animationProfile = animationProfile;
        }

        public SpriteAnimationSet getAnimationSet() {
                return animationSet;
        }

        public void setAnimationSet(SpriteAnimationSet animationSet) {
                this.animationSet = animationSet;
        }

        public int getAnimationId() {
                return animationId;
        }

        @Override
        public void forceAnimationRestart() {
                PlayableSpriteAnimation anim = getAnimationManager();
                if (anim != null) {
                        anim.resetLastAnimationId();
                }
        }

        /** Publishes the ROM's {@code prev_anim=Run} sentinel. */
        public void publishRunAsPreviousAnimation() { controller.publishRunAsPreviousAnimation(); }

        public void setAnimationId(int animationId) {
                this.animationId = Math.max(0, animationId);
        }

        public void setAnimationId(AnimationId animationId) {
                this.animationId = Math.max(0, animationId.id());
        }

        public int getForcedAnimationId() {
                return forcedAnimationId;
        }

        public void setForcedAnimationId(int forcedAnimationId) {
                this.forcedAnimationId = forcedAnimationId;
        }

        public void setForcedAnimationId(AnimationId animationId) {
                this.forcedAnimationId = animationId.id();
        }

        public boolean isObjectMappingFrameControl() {
                return objectMappingFrameControl;
        }

        public void setObjectMappingFrameControl(boolean objectMappingFrameControl) {
                this.objectMappingFrameControl = objectMappingFrameControl;
        }

        public int getAnimationFrameIndex() {
                return animationFrameIndex;
        }

        public void setAnimationFrameIndex(int animationFrameIndex) {
                this.animationFrameIndex = Math.max(0, animationFrameIndex);
        }

        public int getAnimationTick() {
                return animationTick;
        }

        public void setAnimationTick(int animationTick) {
                this.animationTick = Math.max(0, animationTick);
        }

        public SpindashDustController getSpindashDustController() {
                return controller.getSpindashDust();
        }

        public void setSpindashDustController(SpindashDustController spindashDustController) {
                controller.setSpindashDust(spindashDustController);
        }

        public TailsTailsController getTailsTailsController() {
                return controller.getTailsTails();
        }

        public void setTailsTailsController(TailsTailsController tailsTailsController) {
                controller.setTailsTails(tailsTailsController);
        }

        public SuperStateController getSuperStateController() {
                return controller.getSuperState();
        }

        public void setSuperStateController(SuperStateController superStateController) {
                controller.setSuperState(superStateController);
        }

        public boolean getRenderHFlip() {
                return renderHFlip;
        }

        public boolean getRenderVFlip() {
                return renderVFlip;
        }

        public void setRenderFlips(boolean hFlip, boolean vFlip) {
                this.renderHFlip = hFlip;
                this.renderVFlip = vFlip;
        }

        public boolean isHighPriority() {
                return highPriority;
        }

        public void setHighPriority(boolean highPriority) {
                this.highPriority = highPriority;
        }

        public int getPriorityBucket() {
                return priorityBucket;
        }

        public void setPriorityBucket(int bucket) {
                this.priorityBucket = RenderPriority.clamp(bucket);
        }

        public boolean getAir() {
                return air;
        }

        public void setAir(boolean air) {
                boolean landed = !air && this.air;
                // HurtCharacter/HurtStop do not write art_tile. Preserve any priority bit
                // owned by a path switcher or scripted sequence (notably AIZ2's waterfall
                // arena) when the hurt routine lands.
                // (invulnerableFrames already set in applyHurt() per ROM behavior)
                if (!air && this.air && hurt) {
                        hurt = false;
                        forcedAnimationId = -1;
                        // HurtStop's direct draw path delays decrementing the reset timer by one frame.
                        invulnerableFrames = 0x78;
                        suppressNextInvulnerabilityDecrement = true;
                        // ...and that same direct draw is unconditional, so this
                        // frame's BuildSprites still refreshes render_flags.on_screen
                        // even though the blink counter was just reloaded
                        // (docs/s2disasm/s2.asm:41076, :41112).
                        hurtRoutineOwnedDisplayThisFrame = true;
                }
                // Reset rolling jump flag when landing
                if (!air && this.air) {
                        rollingJump = false;
                }
                // Clear jumping flag and double-jump flag when landing
                if (!air && this.air) {
                        jumping = false;
                        // Restore standing radii if coming out of a glide state
                        // (glide uses custom 10x10 radii that won't be restored by
                        // setRolling(false) since rolling was already false)
                        if (doubleJumpFlag > 0 && !rolling) {
                                applyStandingRadii(false);
                                objectMappingFrameControl = false;
                                forcedAnimationId = -1;
                        }
                        doubleJumpFlag = 0;
                        doubleJumpProperty = 0;
                        // S1 ROM parity (Sonic_ResetOnFloor):
                        // move.w #0,(v_itembonus).w ; clear enemy/block score chain.
                        currentGameState().resetItemBonus();
                }
                this.air = air;
                if (landed) {
                        controller.publishLandingAnimationWrite();
                }
                // SPG: Push sensor Y offset changes based on air state
                updatePushSensorYOffset();
                if (air) {
                        setGroundMode(GroundMode.GROUND);
                        // SPG: Angle should gradually return to 0 while airborne,
                        // NOT immediately reset. See returnAngleToZero() called during air updates.
                } else {
                        // Reset badnik chain when landing
                        resetBadnikChain();
                }
        }

        /**
         * Applies a ROM routine's direct {@code bclr #Status_InAir,status(a0)}
         * without synthesising the engine's terrain-landing side effects.
         *
         * <p>This is intentionally narrower than {@link #setAir(boolean)}:
         * control-restoration routines clear the native status bit but do not
         * run {@code Sonic_ResetOnFloor}, reset the item-bonus chain, or claim
         * a landing animation transition.
         */
        public void clearAirForNativeControlRestore() {
                this.air = false;
                updatePushSensorYOffset();
        }

        /**
         * Object/platform solid landings can clear Status_InAir while Sonic is
         * still in routine 4. ROM then runs Sonic_HurtStop on the next player
         * update and only there clears routine 4 plus velocities.
         */
        public void setAirAfterObjectHurtLanding() {
                if (this.air) {
                        rollingJump = false;
                        jumping = false;
                        if (doubleJumpFlag > 0 && !rolling) {
                                applyStandingRadii(false);
                                objectMappingFrameControl = false;
                                forcedAnimationId = -1;
                        }
                        doubleJumpFlag = 0;
                        doubleJumpProperty = 0;
                        currentGameState().resetItemBonus();
                }
                this.air = false;
                updatePushSensorYOffset();
                resetBadnikChain();
        }

        public void completeHurtLandingRecovery() {
                hurt = false;
                forcedAnimationId = -1;
                controller.markHurtRecoveryCompleted();
                invulnerableFrames = 0x78;
                suppressNextInvulnerabilityDecrement = true;
                setXSpeed((short) 0);
                setYSpeed((short) 0);
                setGSpeed((short) 0);
                setSpindash(false);
        }

        public boolean isJumping() {
                return jumping;
        }

        public void setJumping(boolean jumping) {
                if (controller != null) {
                        // The ROM jumping byte is also the Sonic_JumpHeight latch.
                        // Object releases that set jumping need the release-height cap,
                        // while springs clear it so external launches are not capped.
                        if (jumping && !this.jumping) {
                                controller.getMovement().setJumpHeightLatch();
                        } else if (!jumping) {
                                controller.getMovement().clearJumpHeightLatch();
                        }
                }
                this.jumping = jumping;
        }

        public int getDoubleJumpFlag() {
                return doubleJumpFlag;
        }

        public void setDoubleJumpFlag(int doubleJumpFlag) {
                this.doubleJumpFlag = doubleJumpFlag;
        }

        public byte getDoubleJumpProperty() {
                return doubleJumpProperty;
        }

        public void setDoubleJumpProperty(byte doubleJumpProperty) {
                this.doubleJumpProperty = doubleJumpProperty;
        }

        public short getWallClimbX() {
                return wallClimbX;
        }

        public void setWallClimbX(short wallClimbX) {
                this.wallClimbX = wallClimbX;
        }

        public boolean isOnObject() {
                return onObject;
        }

        public void setOnObject(boolean onObject) {
                this.onObject = onObject;
        }

        /**
         * Captures the current {@link #onObject} value into the frame-start
         * snapshot. Called by {@code SpriteManager.beginPlayableFrame} before
         * any playable's per-frame tick runs, so consumers reading
         * {@link #getOnObjectAtFrameStart()} see the value as it was at the
         * top of the frame, matching the ROM's mid-frame view when one
         * playable's logic reads another playable's status.
         */
        public void captureOnObjectAtFrameStart() {
                controller.captureFrameStartState();
        }

        /**
         * Returns the {@link #onObject} value as it was at the START of the
         * current playable frame (before any player tick ran). Use this in
         * cross-playable reads where the ROM accesses another sprite's
         * {@code Status_OnObj} bit BEFORE solid-object processing has run for
         * the frame (e.g. {@code Tails_CPU_Control} follow-steering at
         * sonic3k.asm:26688-26700 / s2.asm:38933+).
         */
        public boolean getOnObjectAtFrameStart() {
                return controller.isOnObjectAtFrameStart();
        }

        /**
         * Returns the {@code Status_OnObj} snapshot from the preceding playable
         * frame. This distinguishes a just-released solid ride from a terrain
         * AnglePos detach after grounded movement has already selected animation.
         */
        public boolean getOnObjectAtPreviousFrameStart() {
                return controller.isOnObjectAtPreviousFrameStart();
        }

        public boolean getPushingAtFrameStart() {
                return controller.isPushingAtFrameStart();
        }

        public boolean getAirAtFrameStart() {
                return controller.isAirAtFrameStart();
        }

        public boolean getHurtAtFrameStart() {
                return controller.isHurtAtFrameStart();
        }

        public boolean getHurtRecoveryCompletedThisFrame() {
                return controller.isHurtRecoveryCompletedThisFrame();
        }

        public int getLatchedSolidObjectId() {
                return latchedSolidObjectId;
        }

        public void setLatchedSolidObjectId(int latchedSolidObjectId) {
                this.latchedSolidObjectId = latchedSolidObjectId & 0xFF;
                if (this.latchedSolidObjectId == 0) {
                        this.latchedSolidObjectInstance = null;
                }
        }

        /**
         * ROM analog: tracks the {@link com.openggf.level.objects.ObjectInstance}
         * the sprite was last latched onto via the SolidObject framework.
         * Used by {@link SidekickCpuController#checkDespawn()} to detect when
         * the latched instance has been deleted (mirroring ROM
         * {@code sub_13EFC} sonic3k.asm:26823 reading {@code (a3)=0} from a
         * slot freed by {@code Delete_Referenced_Sprite} sonic3k.asm:36116).
         *
         * <p>{@code latchedSolidObjectId} is sticky across destruction
         * (it's an 8-bit ID with no instance identity), so the instance
         * reference is needed for unambiguous "did the actual ride disappear"
         * detection. The reference is cleared when the controller despawns,
         * inits, or the engine clears latched state.
         */
        @com.openggf.game.rewind.RewindDeferred(reason = "latched solid contact needs stable object identity snapshot")
        protected com.openggf.level.objects.ObjectInstance latchedSolidObjectInstance;

        public com.openggf.level.objects.ObjectInstance getLatchedSolidObjectInstance() {
                return latchedSolidObjectInstance;
        }

        public void setLatchedSolidObjectInstance(com.openggf.level.objects.ObjectInstance instance) {
                this.latchedSolidObjectInstance = instance;
        }

        /**
         * Convenience: set both the latched id and instance atomically. Mirrors
         * the engine's SolidObject paths in
         * {@link com.openggf.level.objects.ObjectManager} that resolve a
         * standing/touching contact and bind the sprite to the live instance.
         */
        public void setLatchedSolidObject(int latchedSolidObjectId,
                        com.openggf.level.objects.ObjectInstance instance) {
                this.latchedSolidObjectId = latchedSolidObjectId & 0xFF;
                this.latchedSolidObjectInstance = instance;
                // ROM RideObject_SetRide writes interact(a1) = slot index of the
                // ridden object (s2.asm:36005-36006). Record the slot so the
                // sidekick despawn comparator can re-dereference the live slot.
                if (instance instanceof com.openggf.level.objects.AbstractObjectInstance aoi) {
                        int slot = aoi.getSlotIndex();
                        if (slot >= 0) {
                                this.interactSlotIndex = slot;
                        }
                }
        }

        /**
         * Records a ROM support contact whose behaviour is hosted by a manager
         * rather than an {@code ObjectInstance}. This preserves the
         * {@code RideObject_SetRide} contract for later CPU despawn checks:
         * the live dereference should read this ROM object id, while the
         * specific SST slot is intentionally synthetic.
         */
        public void setSyntheticLatchedSolidObject(int latchedSolidObjectId) {
                this.latchedSolidObjectId = latchedSolidObjectId & 0xFF;
                this.latchedSolidObjectInstance = null;
                this.interactSlotIndex = SYNTHETIC_INTERACT_SLOT;
        }

        /**
         * Returns the persistent ROM {@code interact(a0)} SST slot index — the
         * slot of the last object this sprite stood on, never cleared on
         * dismount. {@code -1} if the sprite has never stood on an object.
         */
        public int getInteractSlotIndex() {
                return interactSlotIndex;
        }

        /**
         * Sets the ROM {@code interact(a0)} slot index directly. Used by rewind
         * restore and the sidekick controller's bootstrap; gameplay normally
         * routes through {@link #setLatchedSolidObject}.
         */
        public void setInteractSlotIndex(int slot) {
                this.interactSlotIndex = slot;
        }

        /** True when {@code Player_SlopeRepel} slipped the player into air on
         * the current physics tick. Cleared at the start of each tick. */
        public boolean isSlopeRepelJustSlipped() {
                return slopeRepelJustSlipped;
        }

        public void setSlopeRepelJustSlipped(boolean value) {
                this.slopeRepelJustSlipped = value;
        }

        /**
         * Captures the player's state at the start of the current physics
         * tick. Called by {@link com.openggf.sprites.managers.PlayableSpriteMovement#handleMovement}
         * before any physics mutations. Per-object hooks running after
         * physics (e.g. {@code CnzWireCageObjectInstance}) read these
         * snapshots to make ROM-correct decisions based on the state ROM
         * would have observed before player physics ran in slot order.
         */
        public void capturePrePhysicsSnapshot() {
                this.prePhysicsAir = this.air;
                this.prePhysicsAngle = this.angle;
                this.prePhysicsGSpeed = this.gSpeed;
                this.prePhysicsXSpeed = (short) this.xSpeed;
                this.prePhysicsYSpeed = (short) this.ySpeed;
                this.prePhysicsCentreX = getCentreX();
                this.prePhysicsCentreY = getCentreY();
        }

        /** Pre-physics air state from {@link #capturePrePhysicsSnapshot()}. */
        public boolean wasPrePhysicsAir() {
                return prePhysicsAir;
        }

        /** Pre-physics angle from {@link #capturePrePhysicsSnapshot()}. */
        public byte getPrePhysicsAngle() {
                return prePhysicsAngle;
        }

        /** Pre-physics ground velocity from {@link #capturePrePhysicsSnapshot()}. */
        public short getPrePhysicsGSpeed() {
                return prePhysicsGSpeed;
        }

        /** Captures the phase immediately before late zone-feature velocity writes. */
        public void capturePreZoneFeatureSnapshot() {
                this.preZoneFeatureGSpeed = this.gSpeed;
        }

        /** Ground velocity after player physics and before late zone-feature updates. */
        public short getPreZoneFeatureGSpeed() {
                return preZoneFeatureGSpeed;
        }

        /** Pre-physics X velocity from {@link #capturePrePhysicsSnapshot()}. */
        public short getPrePhysicsXSpeed() {
                return prePhysicsXSpeed;
        }

        /** Pre-physics Y velocity from {@link #capturePrePhysicsSnapshot()}. */
        public short getPrePhysicsYSpeed() {
                return prePhysicsYSpeed;
        }

        /** Pre-physics centre X from {@link #capturePrePhysicsSnapshot()}. */
        public short getPrePhysicsCentreX() {
                return prePhysicsCentreX;
        }

        /** Pre-physics centre Y from {@link #capturePrePhysicsSnapshot()}. */
        public short getPrePhysicsCentreY() {
                return prePhysicsCentreY;
        }

        /** Captures CPU-sidekick state before {@code SidekickCpuController.update}. */
        public void capturePreCpuControlSnapshot() { groundWallResponse.capturePreControlGSpeed(this.gSpeed); }

        /** Ground speed captured before the CPU controller ran this frame. */
        public short getPreCpuControlGSpeed() { return groundWallResponse.preControlGSpeed(); }

        public void deferGroundWallVelocityResponse(int mode, int distance) { groundWallResponse.defer(mode, distance); }

        public boolean hasDeferredGroundWallVelocityResponse() { return groundWallResponse.hasDeferred(); }

        public int getDeferredGroundWallVelocityMode() { return groundWallResponse.mode(); }

        public int getDeferredGroundWallVelocityDistance() { return groundWallResponse.distance(); }

        public void clearDeferredGroundWallVelocityResponse() { groundWallResponse.clearDeferred(); }

        public boolean isSliding() {
                return sliding;
        }

        public void setSliding(boolean sliding) {
                this.sliding = sliding;
        }

        public boolean isStickToConvex() {
                return stickToConvex;
        }

        public void setStickToConvex(boolean stickToConvex) {
                this.stickToConvex = stickToConvex;
        }

        /**
         * SPG: While airborne, Ground Angle smoothly returns toward 0 by 2 hex units per frame.
         * This affects the visual rotation of the sprite during air time.
         * Call this once per frame while airborne.
         */
        public void returnAngleToZero() {
                int currentAngle = angle & 0xFF;
                if (currentAngle == 0) {
                        return; // Already at 0
                }
                // ROM: Sonic_JumpAngle (s2.asm:37465-37486)
                // ROM uses bpl (branch if positive, i.e., bit 7 clear) to determine direction
                // Angles 0x01-0x7F (1-127): bit 7 clear = positive, subtract 2
                // Angles 0x80-0xFF (128-255): bit 7 set = negative, add 2
                if (currentAngle < 128) {
                        // Positive range (0x01-0x7F): decrease toward 0
                        currentAngle -= 2;
                        if (currentAngle < 0) {
                                currentAngle = 0;
                        }
                } else {
                        // Negative range (0x80-0xFF): increase toward 0 (wrapping through 256)
                        currentAngle += 2;
                        if (currentAngle >= 256) {
                                currentAngle = 0;
                        }
                }
                angle = (byte) currentAngle;
        }

        private int badnikChainCounter = 0;

        public void resetBadnikChain() {
                badnikChainCounter = 0;
        }

        public int incrementBadnikChain() {
                badnikChainCounter++;
                // 1st: 100, 2nd: 200, 3rd: 500, 4th+: 1000
                return switch (badnikChainCounter) {
                        case 1 -> 100;
                        case 2 -> 200;
                        case 3 -> 500;
                        default -> 1000;
                };
        }

        public byte getTopSolidBit() {
                return topSolidBit;
        }

        public void setTopSolidBit(byte topSolidBit) {
                if (gameRules != null && gameRules.collision().collisionModel() != CollisionModel.DUAL_PATH) {
                        return;
                }
                this.topSolidBit = topSolidBit;
        }

        public byte getLrbSolidBit() {
                return lrbSolidBit;
        }

        public void setLrbSolidBit(byte lrbSolidBit) {
                if (gameRules != null && gameRules.collision().collisionModel() != CollisionModel.DUAL_PATH) {
                        return;
                }
                this.lrbSolidBit = lrbSolidBit;
        }

        // ROM status_tertiary ($37) bit layout (SCHG "wall cling"):
        //   bit 7 — wall-cling active (only MGZ Top Platform raises this in stock S3K).
        //   bit 6 — SolidObjectFull side-hit feedback while bit 7 is set.
        //   bit 5 — SolidObjectFull ceiling-hit feedback while bit 7 is set.
        private static final int WALL_CLING_BIT = 1 << 7;
        private static final int WALL_CLING_SIDE_BIT = 1 << 6;
        private static final int WALL_CLING_TOP_BIT = 1 << 5;

        public boolean isWallCling() {
                return (statusTertiary & WALL_CLING_BIT) != 0;
        }

        public void setWallCling(boolean active) {
                statusTertiary = (byte) ((statusTertiary & ~WALL_CLING_BIT)
                        | (active ? WALL_CLING_BIT : 0));
        }

        public boolean hasWallClingSideContact() {
                return (statusTertiary & WALL_CLING_SIDE_BIT) != 0;
        }

        public void setWallClingSideContact(boolean active) {
                statusTertiary = (byte) ((statusTertiary & ~WALL_CLING_SIDE_BIT)
                        | (active ? WALL_CLING_SIDE_BIT : 0));
        }

        public boolean consumeWallClingSideContact() {
                boolean set = hasWallClingSideContact();
                if (set) {
                        setWallClingSideContact(false);
                }
                return set;
        }

        public boolean hasWallClingTopContact() {
                return (statusTertiary & WALL_CLING_TOP_BIT) != 0;
        }

        public void setWallClingTopContact(boolean active) {
                statusTertiary = (byte) ((statusTertiary & ~WALL_CLING_TOP_BIT)
                        | (active ? WALL_CLING_TOP_BIT : 0));
        }

        public boolean consumeWallClingTopContact() {
                boolean set = hasWallClingTopContact();
                if (set) {
                        setWallClingTopContact(false);
                }
                return set;
        }

        public void clearWallClingState() {
                statusTertiary = 0;
        }

        public boolean isLoopLowPlane() {
                return loopLowPlane;
        }

        public void setLoopLowPlane(boolean loopLowPlane) {
                this.loopLowPlane = loopLowPlane;
        }

        public short getJump() {
                if (physicsModifiers != null) {
                        return physicsModifiers.effectiveJump(jump, inWater);
                }
                // Fallback: Water reduced jump force (ROM s2.asm line 37019: 0x380 vs normal 0x680)
                if (inWater) {
                        return 0x380;
                }
                return jump;
        }

        public boolean getSpindash() {
                return spindash;
        }

        public void setSpindash(boolean spindash) {
                this.spindash = spindash;
        }

        public boolean getCrouching() {
                return crouching;
        }

        public void setCrouching(boolean crouching) {
                this.crouching = crouching;
        }

        public boolean getLookingUp() {
                return lookingUp;
        }

        public void setLookingUp(boolean lookingUp) {
                this.lookingUp = lookingUp;
        }

        public int getBalanceState() {
                return balanceState;
        }

        public void setBalanceState(int balanceState) {
                this.balanceState = balanceState;
        }

        public boolean isBalancing() {
                return balanceState > 0;
        }

        public boolean getPushing() {
                return pushing;
        }

        public void setPushing(boolean pushing) {
                this.pushing = pushing;
                if (!pushing) {
                        groundWallResponse.clearPushState();
                }
        }

        /** Marks the live push bit as set this cycle by a terrain ground-wall collision. */
        public void markPushFromGroundWallCollision() { groundWallResponse.markPushFromGroundWallCollision(); }

        /** @return true when the live push bit came from a terrain ground-wall collision. */
        public boolean isPushFromGroundWallCollision() { return groundWallResponse.isPushFromGroundWallCollision(); }

        public boolean getSkidding() {
                return skidding;
        }

        public void setSkidding(boolean skidding) {
                this.skidding = skidding;
                if (!skidding
                                && (gameRules == null
                                                || gameRules.powerUp() == null
                                                || !gameRules.powerUp().fixedSkidDustAllocatesAfterDynamicObjectPass())) {
                        this.skidDustTimer = 0;
                        this.fixedSkidDustActive = false;
                }
        }

        public int getSkidDustTimer() {
                return skidDustTimer;
        }

        public void setSkidDustTimer(int timer) {
                this.skidDustTimer = timer;
        }

        public boolean isFixedSkidDustActive() {
                return fixedSkidDustActive;
        }

        public void setFixedSkidDustActive(boolean active) {
                this.fixedSkidDustActive = active;
        }

        public boolean getInvulnerable() {
                // Debug mode and Super Sonic make player completely invulnerable
                return debugMode || superSonic || invulnerableFrames > 0 || invincibleFrames > 0 || hurt;
        }

        public int getInvulnerableFrames() {
                return invulnerableFrames;
        }

        public void setInvulnerableFrames(int frames) {
                invulnerableFrames = Math.max(0, frames);
                if (invulnerableFrames == 0) {
                        suppressNextInvulnerabilityDecrement = false;
                        invulnerabilityDisplayTimerTickedThisFrame = false;
                }
        }

        public int getInvincibleFrames() {
                return invincibleFrames;
        }

        public void setInvincibleFrames(int frames) {
                invincibleFrames = Math.max(0, frames);
        }

        public boolean getSpringing() {
                return springing;
        }

        public boolean getDead() {
                return dead;
        }

        public void setDead(boolean dead) {
                this.dead = dead;
                controller.clearTailsFlightIf(dead && getSecondaryAbility() == SecondaryAbility.FLY);
        }

        /**
         * Whether the player is in the drowning pre-death phase (120-frame sink before death).
         */
        public boolean isDrowningPreDeath() {
                return drowningDeath && drownPreDeathTimer > 0;
        }

        /**
         * Whether this death is a drowning death (used for animation selection).
         * True during both the pre-death sink phase and the subsequent dead fall.
         */
        public boolean isDrowningDeath() {
                return drowningDeath;
        }

        public void clearDrowningDeathState() {
                drowningDeath = false;
                drownPreDeathTimer = 0;
        }

        /**
         * Ticks the drown pre-death timer. Returns true when the timer expires (transition to dead).
         */
        public boolean tickDrownPreDeath() {
                if (drownPreDeathTimer > 0) {
                        drownPreDeathTimer--;
                        return drownPreDeathTimer <= 0;
                }
                return false;
        }

        public boolean isHurt() {
                return hurt;
        }

        public void setHurt(boolean hurt) {
                if (!hurt && this.hurt) {
                        forcedAnimationId = -1;
                }
                this.hurt = hurt;
        }

        /**
         * See {@link #objectRoutineOverride}.
         */
        public Integer getObjectRoutineOverride() {
                return objectRoutineOverride;
        }

        /**
         * See {@link #objectRoutineOverride}. Pass {@code null} to restore the default
         * hurt/dead-derived routine heuristic.
         */
        public void setObjectRoutineOverride(Integer objectRoutineOverride) {
                this.objectRoutineOverride = objectRoutineOverride;
        }

        public int getDeathCountdown() {
                return deathCountdown;
        }

        public void setDeathCountdown(int frames) {
                this.deathCountdown = Math.max(0, frames);
                if (frames <= 0) {
                        // Every caller that zeroes the countdown is clearing the whole
                        // death state (respawn, sidekick despawn, rewind reset), so the
                        // ROM routine number goes back with it. The one caller that means
                        // "enter the routine but never restart" uses
                        // enterDeathRestartRoutine(0) instead.
                        this.deathRestartRoutineActive = false;
                }
        }

        /**
         * Enters the ROM's post-fall death routine and arms {@code restartime}.
         *
         * <p>S1 {@code Sonic_HandleDeath} writes {@code addq.b #2,obRoutine}
         * (to routine 8) and {@code move.w #60,restartime} together, then
         * rewrites {@code restartime} to zero for a game over or a time over
         * (docs/s1disasm/_incObj/01 Sonic.asm:2011-2045). The routine number is
         * written either way, so a zero delay still stops the corpse falling.
         */
        public void enterDeathRestartRoutine(int restartDelayFrames) {
                if (!dead || deathRestartRoutineActive) {
                        return;
                }
                deathRestartRoutineActive = true;
                deathCountdown = Math.max(0, restartDelayFrames);
        }

        /** Whether the corpse is in the ROM's post-fall death routine. */
        public boolean isInDeathRestartRoutine() {
                return dead && deathRestartRoutineActive;
        }

        /**
         * Decrements death countdown and returns true if level should reload.
         */
        public boolean tickDeathCountdown() {
                if (deathCountdown > 0) {
                        deathCountdown--;
                        if (deathCountdown == 0) {
                                return true; // Time to reload level
                        }
                }
                return false;
        }

        public void setSpringing(int frames) {
                if (frames <= 0) {
                        springing = false;
                        springingFrames = 0;
                        return;
                }
                springing = true;
                springingFrames = frames;
        }

        public void tickInvulnerabilityDisplayTimerBeforeTouchResponse() {
                tickInvulnerabilityDisplayTimer();
        }

        private void tickInvulnerabilityDisplayTimer() {
                if (invulnerabilityDisplayTimerTickedThisFrame) {
                        return;
                }
                invulnerabilityDisplayTimerTickedThisFrame = true;
                // ROM: invulnerable_time only decrements in Sonic_Display (routine 2).
                // During hurt routine (routine 4), DisplaySprite is called directly,
                // so the timer stays frozen until Sonic lands.
                if (invulnerableFrames > 0 && !hurt) {
                        if (suppressNextInvulnerabilityDecrement) {
                                suppressNextInvulnerabilityDecrement = false;
                        } else {
                                invulnerableFrames--;
                        }
                }
        }

        public void tickStatus() {
                refreshRuntimeBoundStateIfNeeded();
                tickInvulnerabilityDisplayTimer();
                if (invincibleFrames > 0) {
                        invincibleFrames--;
                        if (invincibleFrames == 0) {
                                if (invincibilityObject != null) {
                                        invincibilityObject.destroy();
                                        invincibilityObject = null;
                                }
                                if (shieldObject != null) {
                                        shieldObject.setVisible(true);
                                }
                                restoreLevelMusicAfterInvincibility();
                        }
                }
                if (springingFrames > 0) {
                        springingFrames--;
                        if (springingFrames == 0) {
                                springing = false;
                        }
                }
                // Speed shoes countdown is a display-phase timer driven by
                // SpriteManager at the ROM Sonic_Display point.

                // Update Super Sonic state (ring drain, palette cycling, transformation)
                if (controller != null && controller.getSuperState() != null) {
                        controller.getSuperState().update();
                }
                // Lazy-register insta-shield with ObjectManager if not yet done (e.g. created before level load).
                // When registered, ObjectManager drives update(); explicit call only needed for headless tests.
                if (instaShieldObject != null && !instaShieldRegistered) {
                        if (powerUpSpawner != null) {
                                powerUpSpawner.registerObject(instaShieldObject);
                                instaShieldRegistered = true;
                        } else {
                                instaShieldObject.update(0, this);
                        }
                }
        }

        /**
         * Resumes the level music when invincibility ends.
         *
         * <p>ROM: {@code Sonic_ChkInvin} re-issues {@code Current_music} rather
         * than restoring a saved song — the sound driver's single save slot
         * belongs to the 1-up jingle alone. It leaves the music alone during a
         * boss fight, and while the drowning countdown owns playback
         * ({@code air_left} below the countdown threshold). The Super revert
         * reaches this path by setting {@code invincibility_timer} to 1 rather
         * than playing music itself.
         */
        private void restoreLevelMusicAfterInvincibility() {
                if (bossOwnsMusic() || drowningCountdownOwnsMusic()) {
                        return;
                }
                LevelManager levelManager = currentLevelManagerIfAvailable();
                if (levelManager == null) {
                        return;
                }
                int musicId = levelManager.getCurrentLevelMusicId();
                if (musicId >= 0) {
                        currentAudioManager().playMusic(musicId);
                }
        }

        /** ROM: {@code tst.b (Boss_flag).w} — a boss fight owns the music. */
        private boolean bossOwnsMusic() {
                return PlayableSpriteRuntimeServices.levelEventsOrNull()
                                instanceof AbstractLevelEventManager events
                                && events.isBossActive();
        }

        /** ROM: {@code cmpi.b #12,air_left(a0)} — the drowning countdown owns the music. */
        private boolean drowningCountdownOwnsMusic() {
                DrowningController drowning = getDrowningController();
                return drowning != null && drowning.isCountdownOwningMusic();
        }

        public boolean applyHurt(int sourceX) {
                return applyHurt(sourceX, false);
        }

        public boolean applyHurt(int sourceX, boolean spikeHit) {
                DamageCause cause = spikeHit ? DamageCause.SPIKE : DamageCause.NORMAL;
                return applyHurt(sourceX, cause);
        }

        public boolean applyHurt(int sourceX, DamageCause cause) {
                return applyHurt(sourceX, cause, false);
        }

        /**
         * Applies hurt while ignoring post-hit invulnerability frames.
         * <p>
         * This still respects debug invulnerability and invincibility power-up.
         * Intended for Sonic 1 spike behavior.
         */
        public boolean applyHurtIgnoringIFrames(int sourceX, boolean spikeHit) {
                DamageCause cause = spikeHit ? DamageCause.SPIKE : DamageCause.NORMAL;
                return applyHurt(sourceX, cause, true);
        }

        public boolean applyHurtIgnoringIFrames(int sourceX, DamageCause cause) {
                return applyHurt(sourceX, cause, true);
        }

        private boolean applyHurt(int sourceX, DamageCause cause, boolean ignoreIFrames) {
                if (isDamageBlocked(ignoreIFrames)) {
                        return false;
                }

                // Fire shield blocks fire damage (s3.asm shield_reaction bit 4)
                PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
                if (cause == DamageCause.FIRE && shield && shieldType == ShieldType.FIRE
                                && capabilityRules != null && capabilityRules.elementalShieldsEnabled()) {
                        return false;
                }

                if (shield) {
                        LOGGER.fine("DEBUG: applyHurt called. removing shield.");
                        shield = false;
                        shieldType = null;
                        if (shieldObject != null) {
                                shieldObject.destroy();
                                shieldObject = null;
                        }
                        // Shield loss sound overrides generic hurt sound if desired, but often it's
                        // just HURT.
                        // However, we MUST prevent death logic if we had no rings.
                        // Handled in applyHurtOrDeath.
                } else {
                        LOGGER.fine("DEBUG: applyHurt called. No shield.");
                }

                hurt = true;
                doubleJumpFlag = 0;
                doubleJumpProperty = 0;
                objectMappingFrameControl = false;
                forcedAnimationId = -1;
                setInvulnerableFrames(0x78); // Set invulnerability immediately (ROM: s2.asm line 84954)
                setSpringing(0);
                setSpindash(false);

                // ROM: Sonic_ResetOnFloor adjusts Y when transitioning from rolling to standing.
                // s1.asm: "subq.w #5,obY(a0)" — subtracts radius diff from y_pos word only,
                // preserving the subpixel fraction. This keeps feet at the same position when
                // yRadius changes from 14 (rolling) to 19 (standing).
                //
                // Use setY() (not setCentreY) to modify only yPixel and preserve ySubpixel,
                // matching the ROM's word-only modification. getRollHeightAdjustment() returns
                // the full height difference (e.g. 10 for Sonic), which when subtracted from
                // yPixel produces the same centreY shift as the ROM's radius-based subtraction.
                // S3K HurtCharacter calls Player_TouchFloor, whose Tails branch
                // restores default radii before testing Status_Roll. S2's 1P sidekick
                // hurt path instead branches to Hurt_Sidekick and preserves a split
                // status/radius state (observed by the HTZ2 trace). Keep that ROM
                // distinction in the movement profile rather than a game-name branch.
                PlayableResetOnFloorRadiusTransition.applyForHurt(this);

                setCrouching(false);
                // HurtCharacter calls the reset-on-floor tail before setting InAir;
                // that reset clears Status_Push, Status_RollJump, and jumping
                // while leaving Status_OnObj to solids (S1 Sonic ReactToItem.asm:390-392;
                // S2 s2.asm:85468-85471, 41033-41037; S3K sonic3k.asm:21090-21093,
                // 24365-24369).
                setPushing(false);
                setRollingJump(false);
                setJumping(false);
                setAir(true);
                setGSpeed((short) 0);
                int dir = (getCentreX() >= sourceX) ? 1 : -1;
                // ROM s2.asm lines 84936-84941: knockback is halved underwater
                if (inWater) {
                        setXSpeed((short) (0x100 * dir));
                        setYSpeed((short) -0x200);
                } else {
                        setXSpeed((short) (0x200 * dir));
                        setYSpeed((short) -0x400);
                }
                // HurtCharacter runs from the touch-response tail after the
                // normal animation pass, then writes anim=$1A immediately. The
                // raw animation byte therefore changes on the damage frame while
                // the already-selected mapping remains displayed until next tick
                // (S1 Sonic ReactToItem.asm:390-410; S2 s2.asm:85497-85519;
                // S3K sonic3k.asm:21090-21110).
                controller.publishRawAnimation(CanonicalAnimation.HURT);
                currentAudioManager().playSfx(resolveDamageSound(cause));
                return true;
        }

        public boolean applyHurtOrDeath(int sourceX, boolean spikeHit, boolean hadRings) {
                DamageCause cause = spikeHit ? DamageCause.SPIKE : DamageCause.NORMAL;
                return applyHurtOrDeath(sourceX, cause, hadRings);
        }

        public boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings) {
                return applyHurtOrDeath(sourceX, cause, hadRings, false);
        }

        /**
         * Applies hurt/death while ignoring post-hit invulnerability frames.
         * <p>
         * This still respects debug invulnerability and invincibility power-up.
         * Intended for Sonic 1 spike behavior.
         */
        public boolean applyHurtOrDeathIgnoringIFrames(int sourceX, boolean spikeHit, boolean hadRings) {
                DamageCause cause = spikeHit ? DamageCause.SPIKE : DamageCause.NORMAL;
                return applyHurtOrDeath(sourceX, cause, hadRings, true);
        }

        public boolean applyHurtOrDeathIgnoringIFrames(int sourceX, DamageCause cause, boolean hadRings) {
                return applyHurtOrDeath(sourceX, cause, hadRings, true);
        }

        private boolean applyHurtOrDeath(int sourceX, DamageCause cause, boolean hadRings, boolean ignoreIFrames) {
                if (isDamageBlocked(ignoreIFrames)) {
                        return false;
                }
                if (!hadRings && !shield) {
                        return applyDeath(cause);
                }
                return applyHurt(sourceX, cause, ignoreIFrames);
        }

        private boolean isDamageBlocked(boolean ignoreIFrames) {
                if (debugMode || invincibleFrames > 0 || isSuperSonic()) {
                        return true;
                }
                // ROM Touch_Hurt (sonic3k.asm:21044-21047, s2.asm Touch_Hurt) gates
                // purely on a NONZERO invulnerability_timer: `tst.b
                // invulnerability_timer(a0); bne.s Touch_ChkHurt_Return`. The timer is
                // decremented earlier in the same object slot by *_Display
                // (Sonic_Display sonic3k.asm:22038-22041, Tails_Display
                // sonic3k.asm:26279-26282), which the engine mirrors via
                // tickInvulnerabilityDisplayTimerBeforeTouchResponse() before
                // applyTouchResponses (SpriteManager.java:1414). So invulnerableFrames at
                // touch time already holds the post-decrement value, and any nonzero
                // value blocks the hit -- matching the ROM `bne` exactly. This applies
                // identically to the CPU sidekick, whose Tails_Display performs the same
                // pre-touch decrement.
                return !ignoreIFrames && invulnerableFrames > 0;
        }

        /**
         * Initiates the ROM-accurate drowning death sequence.
         * ROM ref: s2.asm:41729-41768 (KillCharacter_Drown / Obj01_ChkDrown)
         *
         * Instead of immediate death, enters a 120-frame pre-death phase:
         * - Controls locked, velocities zeroed
         * - Gentle gravity ($10/frame) causes slow sinking
         * - Drowning animation (0x17) plays throughout
         * - After 120 frames, transitions to dead (no upward bounce)
         */
        public boolean applyDrownDeath() {
                if (dead || drownPreDeathTimer > 0) {
                        return false;
                }
                drowningDeath = true;
                controlLocked = true;
                gSpeed = 0;
                xSpeed = 0;
                ySpeed = 0;
                setAir(true);
                setHighPriority(true);
                setSpringing(0);
                setSpindash(false);
                setRolling(false);
                setCrouching(false);
                setPushing(false);
                drownPreDeathTimer = 120;
                // Lock camera - prevent following the sinking player
                if (!cpuControlled) {
                        currentCamera().setFrozen(true);
                }
                // ROM order: resume zone music first, then play drown SFX.
                // playMusic() replaces the SMPS driver, so any SFX queued on the
                // old driver would be lost. Playing SFX after ensures it lands on
                // the new driver.
                if (controller.getDrowning() != null) {
                        controller.getDrowning().onDrown();
                }
                currentAudioManager().playSfx(GameSound.DROWN);
                return true;
        }

        /**
         * Applies instant death from OOZ oil suffocation.
         * ROM: JmpTo3_KillCharacter (s2.asm:49694) - standard instant kill, NOT the
         * water drowning pre-death sequence. Uses DROWN sound.
         */
        public boolean applyOilSuffocateDeath() {
                return applyDeath(DamageCause.DROWN);
        }

        public boolean applyPitDeath() {
                return applyDeath(DamageCause.PIT);
        }

        /**
         * Applies instant death from being crushed between a solid object and terrain.
         * Matches ROM's KillCharacter call in SolidObject_Squash (s2.asm:35348-35356).
         * Unconditional death - bypasses rings, shields, and invulnerability frames.
         */
        public boolean applyCrushDeath() {
                return applyDeath(DamageCause.CRUSH);
        }

        private boolean applyDeath(DamageCause cause) {
                if (dead) {
                        return false;
                }
                dead = true;
                // Lock camera when dying - prevent following the falling corpse
                // Only freeze camera for the main player, not for CPU sidekick
                if (!cpuControlled) {
                        currentCamera().setFrozen(true);
                }
                setInvulnerableFrames(0);
                setInvincibleFrames(0);
                setSpringing(0);
                setSpindash(false);
                // The kill routines reach the reset-on-floor tail before they force
                // the airborne bit, so a player killed mid-roll gets the standing
                // radii AND the accompanying y_pos lift, not just the roll bit
                // cleared (S1 KillSonic, docs/s1disasm/_incObj/Sonic
                // ReactToItem.asm:454-459; S2 KillCharacter, docs/s2disasm/s2.asm
                // :85544-85551; S3K Kill_Character, docs/skdisasm/sonic3k.asm
                // :21136-21151). Without the lift the taller standing shape pushed
                // the centre 5px DOWN where the ROM raises it 5px, so a rolling
                // pit death landed 10px low and never re-converged (S1 MZ1 row
                // 3,261: ROM y $03CB, engine $03D5).
                PlayableResetOnFloorRadiusTransition.applyForDeath(this);
                setCrouching(false);
                setPushing(false);
                setAir(true); setOnObject(onObject || controller.isOnObjectAtFrameStart());
                setGSpeed((short) 0);
                setXSpeed((short) 0);
                setYSpeed((short) -0x700);
                setHighPriority(true);
                // Kill_Character writes anim=Death in the kill call itself.
                // Preserve the mapping/frame/timer selected earlier this frame;
                // Animate_* consumes the new raw byte on the next player pass.
                controller.publishRawAnimation(CanonicalAnimation.DEATH);
                GameSound sound = resolveDamageSound(cause);
                if (sound != null) {
                        currentAudioManager().playSfx(sound);
                }
                return true;
        }

        private GameSound resolveDamageSound(DamageCause cause) {
                return switch (cause) {
                        case SPIKE -> GameSound.HURT_SPIKE;
                        case DROWN, TIME_OVER -> GameSound.DROWN; // Time over usually uses Drown or specific logic,
                                                                  // checking s2 asm... usually it's just game over
                                                                  // music. but for damage sound?
                        case PIT -> GameSound.HURT;
                        default -> GameSound.HURT;
                };
        }

        /**
         * Get the spindash counter (ROM: spindash_counter).
         * Range: 0x000 to 0x800. Speed index = counter >> 8.
         */
        public short getSpindashCounter() {
                return spindashCounter;
        }

        /**
         * Set the spindash counter (ROM: spindash_counter).
         * @param spindashCounter Value 0x000 to 0x800
         */
        public void setSpindashCounter(short spindashCounter) {
                this.spindashCounter = spindashCounter;
        }

        /**
         * Get the look delay counter (ROM: Sonic_Look_delay_counter).
         * Camera panning starts when this reaches 0x78 (120 frames).
         */
        public short getLookDelayCounter() {
                return lookDelayCounter;
        }

        /**
         * Set the look delay counter (ROM: Sonic_Look_delay_counter).
         * @param lookDelayCounter Counter value (capped at 0x78)
         */
        public void setLookDelayCounter(short lookDelayCounter) {
                this.lookDelayCounter = lookDelayCounter;
        }

        public int getRightWallPenetrationTimer() {
                return rightWallPenetrationTimer;
        }

        public void setRightWallPenetrationTimer(int rightWallPenetrationTimer) {
                this.rightWallPenetrationTimer = Math.max(0, rightWallPenetrationTimer);
        }

        public boolean isForceInputRight() {
                return forceInputRight;
        }

        public void setForceInputRight(boolean forceInputRight) {
                this.forceInputRight = forceInputRight;
                if (forceInputRight) {
                        this.forcedInputMask |= INPUT_RIGHT;
                } else {
                        this.forcedInputMask &= ~INPUT_RIGHT;
                }
        }

        public int getForcedInputMask() {
                return forcedInputMask;
        }

        public void setForcedInputMask(int forcedInputMask) {
                this.forcedInputMask = forcedInputMask & (INPUT_UP | INPUT_DOWN | INPUT_LEFT | INPUT_RIGHT | INPUT_JUMP);
                this.forceInputRight = (this.forcedInputMask & INPUT_RIGHT) != 0;
        }

        public void clearForcedInputMask() {
                this.forcedInputMask = 0;
                this.forceInputRight = false;
                this.forcedJumpPress = false;
                this.suppressNextJumpPress = false;
                this.deferredObjectControlRelease = false;
        }

        public void setForcedJumpPress(boolean forcedJumpPress) {
                this.forcedJumpPress = forcedJumpPress;
        }

        public boolean isForcedJumpPress() {
                return forcedJumpPress;
        }

        public void suppressNextJumpPress() {
                this.suppressNextJumpPress = true;
        }

        public boolean consumeSuppressNextJumpPress() {
                boolean suppress = suppressNextJumpPress;
                suppressNextJumpPress = false;
                return suppress;
        }

        public boolean isForcedInputActive(int inputBit) {
                return (forcedInputMask & inputBit) != 0;
        }

        public boolean isControlLocked() {
                return controlLocked;
        }

        public void setControlLocked(boolean controlLocked) {
                this.controlLocked = controlLocked;
        }

        /**
         * Queues a Control_Locked state change for the next playable frame.
         * Use this for ROM globals written by later SST slots after Sonic's
         * own control routine has already run this frame.
         */
        public void queueControlLockedForNextFrame(boolean controlLocked) {
                this.hasQueuedControlLockedState = true;
                this.queuedControlLocked = controlLocked;
        }

        /**
         * Queues the signpost-style forced-right latch for the next playable frame.
         * This mirrors scripts that overwrite Ctrl_1_Logical after Sonic has
         * already consumed input for the current frame.
         */
        public void queueForceInputRightForNextFrame(boolean forceInputRight) {
                this.hasQueuedForceInputRightState = true;
                this.queuedForceInputRight = forceInputRight;
        }

        /**
         * Applies any queued control/input latch changes at the start of the
         * current playable frame.
         */
        public void applyQueuedControlStateForFrameStart() {
                if (hasQueuedControlLockedState) {
                        this.controlLocked = queuedControlLocked;
                        hasQueuedControlLockedState = false;
                }
                if (hasQueuedForceInputRightState) {
                        setForceInputRight(queuedForceInputRight);
                        hasQueuedForceInputRightState = false;
                }
        }

        public void clearQueuedControlState() {
                hasQueuedControlLockedState = false;
                queuedControlLocked = false;
                hasQueuedForceInputRightState = false;
                queuedForceInputRight = false;
        }

        /**
         * Gets the movement lock timer (ROM: move_lock).
         * When > 0, player input is ignored.
         */
        public int getMoveLockTimer() {
                return moveLockTimer;
        }

        /**
         * Sets the movement lock timer (ROM: move_lock).
         * The timer is decremented each frame by the movement manager.
         */
        public void setMoveLockTimer(int moveLockTimer) {
                this.moveLockTimer = Math.max(0, moveLockTimer);
        }

        public boolean isHidden() {
                return hidden;
        }

        public boolean isNativeSlotPresent() { return nativeSlotPresent; }

        public void setNativeSlotPresent(boolean nativeSlotPresent) { this.nativeSlotPresent = nativeSlotPresent; }

        public void setHidden(boolean hidden) {
                this.hidden = hidden;
        }

        /**
         * Returns whether an object has full control of the player (physics disabled).
         * When true, the movement manager skips all physics processing.
         */
        public boolean isObjectControlled() {
                return objectControlled;
        }

        /**
         * Sets whether an object has full control of the player.
         * When true, normal physics (gravity, movement, collision) are skipped.
         * The controlling object is responsible for updating the player's position.
         */
        public void setObjectControlled(boolean objectControlled) {
                this.objectControlled = objectControlled;
                if (objectControlled) {
                        controller.clearTailsFlightIf(getSecondaryAbility() == SecondaryAbility.FLY);
                        this.deferredObjectControlRelease = false;
                        this.objectControlSuppressesMovement = true;
                } else {
                        controller.setObjectControlledSolidContactOwner(null);
                        clearMgzTopPlatformSpringHandoff();
                        this.objectControlAllowsCpu = false;
                        this.objectControlSuppressesMovement = false;
                }
        }

        /**
         * ROM-bit-7 ({@code bmi.w}) test for {@link SidekickCpuController#updateNormal}'s
         * early-out gate. When {@code true}, the controlling object holds the player via
         * ROM {@code object_control} bits 0-6 only (e.g. CNZ wire cage's bits 1+6, MGZ
         * twisting loop's bit 0+1+6) — ROM lets {@code Tails_CPU_Control} keep generating
         * input in this case (sonic3k.asm:26672 {@code bmi.w} only branches when the sign
         * bit is set). When {@code false} (default), the controlling object is the
         * ROM-bit-7 case (flight, super state, despawn marker, debug) and the engine's
         * CPU controller skips its NORMAL state body to match ROM's {@code bmi.w} skip.
         *
         * <p>Cleared automatically when {@link #setObjectControlled(boolean)} is set to
         * {@code false}; the controlling object must re-assert the flag every time it
         * re-asserts {@link #setObjectControlled}{@code (true)}.
         */
        public boolean isObjectControlAllowsCpu() {
                return objectControlAllowsCpu;
        }

        public void setObjectControlAllowsCpu(boolean objectControlAllowsCpu) {
                this.objectControlAllowsCpu = objectControlAllowsCpu;
        }

        /**
         * Returns true when normal movement, gravity, boundary checks, and terrain
         * collision should be skipped by ROM {@code object_control} bit 0.
         * Some object routines set only this movement gate while still allowing
         * TouchResponse and later SolidObject checks in the same ExecuteObjects pass.
         */
        public boolean isObjectControlSuppressesMovement() {
                return objectControlSuppressesMovement;
        }

        public void setObjectControlSuppressesMovement(boolean objectControlSuppressesMovement) {
                this.objectControlSuppressesMovement = objectControlSuppressesMovement;
        }

        public void applyObjectControlState(ObjectControlState state) {
                Objects.requireNonNull(state, "state");
                setObjectControlled(state.objectControlled());
                if (state.objectControlled() && state.writesObjectControlAllowsCpu()) {
                        setObjectControlAllowsCpu(state.objectControlAllowsCpu());
                }
                setObjectControlSuppressesMovement(state.objectControlSuppressesMovement());
        }

        /**
         * Returns true when this sprite should skip the per-frame touch-response
         * collision pass — ROM's {@code object_control} bit-7 gate at the call
         * sites listed in {@link com.openggf.game.PlayableEntity#isTouchResponseSuppressedByObjectControl()}.
         * <p>
         * The engine encodes ROM bit-7-style {@code object_control} (flight $81,
         * super $83, despawn marker $81, debug $83) as
         * {@code objectControlled && !objectControlAllowsCpu} (see comment block
         * on {@link #setObjectControlAllowsCpu(boolean)}). Bits 0-6 only — e.g.
         * CNZ wire cage's $42, MGZ twisting loop's $43 — keep
         * {@code objectControlAllowsCpu} true so the engine still runs
         * TouchResponse, mirroring the ROM dispatcher leaving
         * {@code Tails_CPU_Control} active for those callers.
         */
        @Override
        public boolean isTouchResponseSuppressedByObjectControl() {
                return objectControlled && !objectControlAllowsCpu;
        }

        /**
         * Returns whether solid-object contacts should still be evaluated while
         * object-controlled. This is an explicit MGZ top-platform carry seam, not a
         * generic wall-cling rule.
         */
        public boolean allowsSolidContactsWhileObjectControlled(ObjectInstance candidate) {
                return controller.allowsObjectControlledSolidContact(candidate);
        }

        public void notifyObjectControlledSolidContact(ObjectInstance candidate, SolidContact contact) {
                controller.notifyObjectControlledSolidContact(candidate, contact);
        }

        public Short getObjectControlledSolidContactProjectedXSpeed(ObjectInstance candidate) {
                return controller.projectedObjectControlledSolidContactXSpeed(candidate);
        }

        public void notifyObjectControlledSolidContactInvalidated(ObjectInstance candidate) {
                controller.notifyObjectControlledSolidContactInvalidated(candidate);
        }

        public void setObjectControlledSolidContactObject(ObjectInstance instance) {
                controller.setObjectControlledSolidContactOwner(instance);
        }

        /** Legacy MGZ name retained for source compatibility. */
        public void setMgzTopPlatformCarrySolidContactObject(ObjectInstance instance) {
                setObjectControlledSolidContactObject(instance);
        }

        public boolean isMgzTopPlatformCarryOwnedBy(ObjectInstance instance) {
                return controller.isObjectControlledSolidContactOwnedBy(instance);
        }

        public void recordMgzTopPlatformSpringHandoff(int xVel, int yVel) {
                controller.recordSpringHandoff(xVel, yVel);
        }

        public boolean hasMgzTopPlatformSpringHandoffPending() {
                return controller.isSpringHandoffPending();
        }

        public int getMgzTopPlatformSpringHandoffXVel() {
                return controller.getSpringHandoffXVelocity();
        }

        public int getMgzTopPlatformSpringHandoffYVel() {
                return controller.getSpringHandoffYVelocity();
        }

        public void clearMgzTopPlatformSpringHandoff() {
                controller.clearSpringHandoff();
        }

        /**
         * Returns whether the current hurt path should suppress generic lost-ring
         * spawning for the active MGZ top-platform carry state. This stays tied to
         * the explicit MGZ ownership seam instead of the generic wall-cling bit so
         * future status_tertiary users do not inherit the exception accidentally.
         */
        public boolean suppressesLostRingSpawnOnHurt() {
                return isWallCling() && controller.hasObjectControlledSolidContactOwner();
        }

        public boolean isSuppressAirCollision() {
                return suppressAirCollision;
        }

        public void setSuppressAirCollision(boolean suppress) {
                this.suppressAirCollision = suppress;
        }

        public boolean isSuppressGroundWallCollision() {
                return suppressGroundWallCollision;
        }

        public void setSuppressGroundWallCollision(boolean suppress) {
                this.suppressGroundWallCollision = suppress;
        }

        public boolean isForceFloorCheck() {
                return forceFloorCheck;
        }

        public void setForceFloorCheck(boolean force) {
                this.forceFloorCheck = force;
        }

        /**
         * Suppresses the next generic {@code ObjectMoveAndFall} step after an external
         * ROM routine has already applied its own position displacement.
         */
        public void suppressNextObjectMoveAndFall() {
                this.suppressedObjectMoveAndFallAxes = 0x3;
        }

        public void suppressNextObjectMoveAndFallY() {
                this.suppressedObjectMoveAndFallAxes |= 0x2;
        }

        public int consumeSuppressedObjectMoveAndFallAxes() {
                int axes = suppressedObjectMoveAndFallAxes;
                suppressedObjectMoveAndFallAxes = 0;
                return axes;
        }

        /**
         * Defers object-control release until the end of the current frame.
         * This matches ROM object ordering where Sonic's routine has already
         * run before a later object clears {@code f_playerctrl}.
         *
         * <p>For S3K handoffs such as AIZ vines, the deferred marker keeps
         * object-control ordering visible without preserving ROM
         * {@code object_control} bit 0 movement ownership. ROM
         * {@code Player_AnglePos} still runs for non-bit-0 states and takes the
         * ground walkoff branch when no floor is found
         * (docs/skdisasm/sonic3k.asm:18728, 18839-18842).
         */
        public void deferObjectControlRelease() {
                this.deferredObjectControlRelease = true;
                this.objectControlSuppressesMovement = false;
        }

        public void suppressNextGravityStep() {
                this.suppressNextGravityStep = true;
        }

        public boolean consumeSuppressNextGravityStep() {
                boolean suppress = suppressNextGravityStep;
                suppressNextGravityStep = false;
                return suppress;
        }

        /**
         * Releases the player from object control and records the frame number.
         * Use this instead of setObjectControlled(false) when exiting a controlling object
         * to enable the cooldown period that prevents immediate re-capture.
         */
        public void releaseFromObjectControl(int frameCounter) {
                this.objectControlled = false;
                this.objectControlAllowsCpu = false;
                this.objectControlSuppressesMovement = false;
                controller.clearObjectControlledSolidContactOwner();
                this.objectControlReleasedFrame = frameCounter;
        }

        /**
         * Returns true if the player was recently released from object control.
         * Used by objects like spin tubes to prevent immediate re-capture after exit.
         * @param frameCounter Current frame number
         * @param cooldownFrames Number of frames to wait before allowing re-capture
         */
        public boolean wasRecentlyObjectControlled(int frameCounter, int cooldownFrames) {
                if (objectControlReleasedFrame == Integer.MIN_VALUE) {
                        return false;
                }
                return (frameCounter - objectControlReleasedFrame) < cooldownFrames;
        }

        /**
         * Returns whether the jump button is currently pressed.
         * Used by objects (like CNZ flippers) to detect jump input for triggering.
         * Also checks forcedInputMask so demo playback input is visible to objects,
         * matching ROM behavior where jpadhold1 contains demo data during demos.
         */
        public boolean isJumpPressed() {
                return jumpInputPressed || isForcedInputActive(INPUT_JUMP);
        }

        /**
         * Returns whether jump was freshly pressed this frame, including forced/demo input.
         */
        public boolean isJumpJustPressed() {
                return jumpInputJustPressed || forcedJumpPress;
        }

        /**
         * Returns the RAW controller jump just-pressed bit as an object routine
         * would see it when reading the per-controller press word.
         * <p>
         * The ROM distinguishes the raw controller word ({@code (Ctrl_1)} /
         * {@code (Ctrl_2)}) from the logical/CPU-written word
         * ({@code Ctrl_1_Logical} / {@code Ctrl_2_Logical}). Object routines such
         * as Obj7F (MCZ vine switch, s2.asm:56489-56491) read the RAW word:
         * {@code (Ctrl_1)} for the MainCharacter and {@code (Ctrl_2)} for the
         * Sidekick. In 1-player Sonic+Tails mode no second controller is plugged
         * in, so {@code (Ctrl_2)} press bits are always 0 -- the CPU's buffered
         * follow jump is written only to {@code Ctrl_2_Logical} (consumed by the
         * sidekick's own movement) and never appears here.
         * <p>
         * For a human-controlled sprite this is simply {@link #isJumpJustPressed()}.
         * For a CPU-controlled sidekick this returns the raw second-controller
         * press (the manual P2 override), NOT the synthesized follow-steering jump.
         */
        public boolean isRawControllerJumpJustPressed() {
                if (isCpuControlled() && cpuController != null) {
                        return cpuController.isRawController2JumpJustPressed();
                }
                return isJumpJustPressed();
        }

        /**
         * Returns the ROM-visible logical jump press bit published for this
         * playable's current update. Unlike {@link #isJumpPressed()}, this does
         * not read the live held/forced latch; objects that receive
         * Ctrl_1_logical/Ctrl_2_logical from ROM routines should use this edge
         * bit.
         */
        public boolean isLogicalJumpPressActive() {
                return logicalJumpPressState;
        }

        /**
         * Sets the jump input state for this frame.
         * Called by movement manager each frame with the current jump button state.
         */
        public void setJumpInputPressed(boolean pressed) {
                setJumpInputPressed(pressed,
                        (pressed || isForcedInputActive(INPUT_JUMP)) && !jumpInputPressedPreviousFrame);
        }

        /**
         * Sets the jump input state with an explicit raw press edge.
         * ROM object routines read Ctrl_1_pressed before movement has consumed
         * the controller state, even while movement control is locked.
         */
        public void setJumpInputPressed(boolean pressed, boolean justPressed) {
                this.jumpInputPressed = pressed;
                boolean combinedJumpPressed = pressed || isForcedInputActive(INPUT_JUMP);
                this.jumpInputJustPressed = justPressed
                        || (isForcedInputActive(INPUT_JUMP) && !jumpInputPressedPreviousFrame);
                this.jumpInputPressedPreviousFrame = combinedJumpPressed;
        }

        /**
         * Returns whether the up button is currently pressed.
         * Used by objects (like VineSwitch) to detect directional input for release delay.
         * Also checks forcedInputMask so demo playback input is visible to objects,
         * matching ROM behavior where jpadhold1 contains demo data during demos.
         */
        public boolean isUpPressed() {
                return upInputPressed || isForcedInputActive(INPUT_UP);
        }

        /**
         * Returns whether the down button is currently pressed.
         * Used by objects (like VineSwitch) to detect directional input for release delay.
         * Also checks forcedInputMask so demo playback input is visible to objects,
         * matching ROM behavior where jpadhold1 contains demo data during demos.
         */
        public boolean isDownPressed() {
                return downInputPressed || isForcedInputActive(INPUT_DOWN);
        }

        /**
         * Returns whether the left button is currently pressed.
         * Used by objects (like Grabber) to detect directional input for escape mechanism.
         * Also checks forcedInputMask so demo playback input is visible to objects,
         * matching ROM behavior where jpadhold1 contains demo data during demos.
         */
        public boolean isLeftPressed() {
                return leftInputPressed || isForcedInputActive(INPUT_LEFT);
        }

        /**
         * Returns whether the right button is currently pressed.
         * Used by objects (like Grabber) to detect directional input for escape mechanism.
         * Also checks forcedInputMask so demo playback input is visible to objects,
         * matching ROM behavior where jpadhold1 contains demo data during demos.
         */
        public boolean isRightPressed() {
                return rightInputPressed || isForcedInputActive(INPUT_RIGHT);
        }

        /**
         * Sets the directional input state for this frame.
         * Called by SpriteManager each frame with the current button states.
         */
        public void setDirectionalInputPressed(boolean up, boolean down, boolean left, boolean right) {
                this.upInputPressed = up;
                this.downInputPressed = down;
                this.leftInputPressed = left;
                this.rightInputPressed = right;
        }

        /**
         * Publishes the logical pad state used by movement this frame.
         * ROM ref: Sonic_RecordPos stores Ctrl_1_Logical, not the raw held-button state.
         *
         * <p>ROM-faithful Ctrl_1_locked latch: when
         * {@link PlayerMovementRules#controlLockLatchesLogicalInput()} is true and
         * {@link #isControlLocked()} is set, the write is skipped so the
         * previous frame's logical pad state persists.
         * Mirrors {@code Sonic_Control} (S3K sonic3k.asm:21541-21545
         * {@code loc_10760}):
         * <pre>
         *   tst.b   (Ctrl_1_locked).w
         *   bne.s   loc_10780               ; if locked, SKIP the copy
         *   move.w  (Ctrl_1).w,(Ctrl_1_logical).w
         * </pre>
         * Without the latch the engine zeroed {@code logicalInputState} the
         * moment any in-level object set {@code controlLocked=true}, which
         * propagated through {@link #endOfTick()} into {@code inputHistory}
         * and corrupted the Sidekick CPU's $40-frame-delayed leader input
         * read ({@code Tails_CPU_Control}, sonic3k.asm:26683-26689).
         *
         * <p>The latch is gated per-game because the previous universal
         * implementation (commit f3347ea89, reverted in 9793e4617)
         * regressed S2 EHZ trace replay from PASS to F5121: S2's existing
         * {@code setControlLocked(true)} sites (FlipperObjectInstance,
         * CPZSpinTubeObjectInstance, Sonic2DeathEggRobotInstance,
         * SignpostObjectInstance) expect the post-lock zero state for
         * animation gating. The S2 ROM has the same short-circuit
         * (s2.asm:35933-35935 {@code Obj01_Control}); flipping S2 to
         * {@code true} requires re-validating those call sites and the
         * EHZ trace baseline.
         */
        public void setLogicalInputState(boolean up, boolean down, boolean left, boolean right, boolean jump) {
                setLogicalInputState(up, down, left, right, jump, isJumpJustPressed());
        }

        /**
         * Publishes the logical pad state plus the low-byte jump press bit.
         */
        public void setLogicalInputState(boolean up, boolean down, boolean left, boolean right, boolean jump,
                        boolean jumpPress) {
                // Latch logical input during ROM control locks unless an explicit forced
                // write is active; forced writes intentionally replace the latched word.
                PlayerMovementRules movementRules = playerMovementRulesOrNull();
                if (isControlLocked()
                                && getForcedInputMask() == 0
                                && movementRules != null
                                && movementRules.controlLockLatchesLogicalInput()) {
                        return;
                }
                if (hurt
                                && movementRules != null
                                && movementRules.hurtRoutineLatchesLogicalInput()) {
                        return;
                }
                short input = 0;
                if (up) input |= INPUT_UP;
                if (down) input |= INPUT_DOWN;
                if (left) input |= INPUT_LEFT;
                if (right) input |= INPUT_RIGHT;
                if (jump) input |= INPUT_JUMP;
                this.logicalInputState = input;
                this.logicalJumpPressState = jumpPress;
        }

        public void clearLogicalInputState() {
                this.logicalInputState = 0;
                this.logicalJumpPressState = false;
        }

        public int getLogicalInputState() {
                return logicalInputState & 0xFFFF;
        }

        /** Mirrors a late ROM logical-input write into the current follower-history slot. */
        public void writeLogicalInputAndCurrentFollowerHistory(int inputMask, boolean jumpPress) {
                logicalInputState = (short) inputMask;
                logicalJumpPressState = jumpPress;
                inputHistory[historyPos] = logicalInputState;
                jumpPressHistory[historyPos] = (byte) (logicalJumpPressState ? 1 : 0);
        }

        /**
         * Returns whether the player is actively pressing a movement direction this frame,
         * after all control lock filtering. Used by animation to match ROM behavior.
         */
        public boolean isMovementInputActive() {
                return movementInputActive;
        }

        /**
         * Sets whether movement directional input is active this frame.
         * Called by movement manager after control lock/move lock filtering.
         */
        public void setMovementInputActive(boolean active) {
                this.movementInputActive = active;
        }

        public void markSpiralActive(int frameCounter) {
                spiralActiveFrame = frameCounter;
        }

        public boolean wasSpiralActive(int frameCounter) {
                return spiralActiveFrame == frameCounter || spiralActiveFrame == frameCounter - 1;
        }

        public boolean isSpiralActiveThisFrame(int frameCounter) {
                return spiralActiveFrame == frameCounter;
        }

        public void clearSpiralActive() {
                spiralActiveFrame = Integer.MIN_VALUE;
        }

        public int getFlipAngle() {
                return flipAngle & 0xFF;
        }

        public void setFlipAngle(int value) {
                this.flipAngle = (byte) (value & 0xFF);
        }

        public int getFlipType() {
                return flipType & 0xFF;
        }

        public void setFlipType(int value) {
                this.flipType = (byte) (value & 0xFF);
        }

        public int getFlipSpeed() {
                return flipSpeed & 0xFF;
        }

        public void setFlipSpeed(int value) {
                this.flipSpeed = (byte) (value & 0xFF);
        }

        public int getFlipsRemaining() {
                return flipsRemaining & 0xFF;
        }

        public void setFlipsRemaining(int value) {
                this.flipsRemaining = (byte) (value & 0xFF);
        }

        public boolean isFlipTurned() {
                return flipTurned;
        }

        public void setFlipTurned(boolean flipTurned) {
                this.flipTurned = flipTurned;
        }

        public short getXSpeed() {
                return xSpeed;
        }

        public void setXSpeed(short xSpeed) {
                this.xSpeed = xSpeed;
        }

        public short getYSpeed() {
                return ySpeed;
        }

        public void setYSpeed(short ySpeed) {
                this.ySpeed = ySpeed;
        }

        /**
         * The amount this sprite's speed is effected by when running down/up a
         * slope.
         */
        protected short slopeRunning;
        /**
         * The amount this sprite's speed is effected by when rolling up a slope.
         */
        protected short slopeRollingUp;
        /**
         * The amount this sprite's speed is effected by when rolling down a slope.
         */
        protected short slopeRollingDown;
        /**
         * The speed at which this sprite accelerates when running.
         */

        protected short runAccel;
        /**
         * The speed at which this sprite decelerates when the opposite direction is
         * pressed.
         */
        protected short runDecel;
        /**
         * The speed at which this sprite slows down while running with no
         * directional keys pressed.
         */
        protected short friction;
        /**
         * Maximum rolling speed of this Sprite per step.
         */
        protected short maxRoll;
        /**
         * Maximum running speed of this Sprite per step.
         */
        protected short max;

        /**
         * The speed at which this sprite slows down while rolling with no
         * directional keys pressed.
         */
        protected short rollDecel;

        /**
         * Minimum speed required to start rolling.
         */
        protected short minStartRollSpeed;

        /**
         * Speed at which to stop rolling
         */
        protected short minRollSpeed;

        /**
         * Height when rolling
         */
        protected short rollHeight;

        /**
         * Height when running
         */
        protected short runHeight;

        /**
         * Collision radii (standing/rolling) used for sensor placement.
         */
        protected short standXRadius = 9;
        protected short standYRadius = 19;
        protected short rollXRadius = 7;
        protected short rollYRadius = 14;

        protected short xRadius = standXRadius;
        protected short yRadius = standYRadius;

        /**
         * Visual render offsets (do not affect collision).
         */
        protected short renderXOffset = 0;
        protected short renderYOffset = 0;

        /**
         * When true, debug movement mode is active.
         * Player can fly freely with direction keys, ignores collision/damage.
         */
        protected boolean debugMode = false;

        protected AbstractPlayableSprite(String code, short x, short y) {
                super(code, x, y);
                // Must define speeds before creating Manager (it will read speeds upon
                // instantiation).
                defineSpeeds();
                resolvePhysicsProfile();

                applyStandingRadii(false);

                // ROM stores delayed player history in centre coordinates. Keeping the
                // engine buffer in the same space avoids replay drift when the current
                // hitbox size differs from the historical one (for example after rolling).
                for (short i = 0; i < 64; i++) {
                        xHistory[i] = getCentreX();
                        yHistory[i] = getCentreY();
                        inputHistory[i] = 0;
                        jumpPressHistory[i] = 0;
                        statusHistory[i] = 0;
                }
                // Always use PlayableSpriteController - it checks debugMode internally
                controller = new PlayableSpriteController(this);
        }

        /**
         * Resolves physics profile, modifiers, and game rules from the active GameModule.
         * Overwrites the protected speed fields set by defineSpeeds() with values from the profile.
         * Falls back gracefully if no provider is available (defineSpeeds() values remain).
         */
        private void resolvePhysicsProfile() {
                resolvePhysicsProfile(bootstrapSafeGameModule());
        }

        private void resolvePhysicsProfile(GameModule module) {
                runtimeBoundStateModule = module;
                try {
                        PhysicsProvider provider = module != null ? module.getPhysicsProvider() : null;
                        if (provider == null) {
                                bubbleAnimId = module != null ? module.resolveAnimationId(CanonicalAnimation.BUBBLE) : -1;
                                return;
                        }
                        String charType;
                        if (this instanceof Tails) charType = "tails";
                        else if (this instanceof Knuckles) charType = "knuckles";
                        else charType = "sonic";
                        PhysicsProfile profile = provider.getProfile(charType);
                        if (profile != null) {
                                this.physicsProfile = profile;
                                applyProfileToFields(profile);
                        }
                        this.physicsModifiers = provider.getModifiers();
                        this.gameRules = provider.getRules();

                        // S1 (UNIFIED collision) uses d5=$D for ALL terrain probes.
                        // After Sonic1Level.convertS1BlockData() maps S1→S2 chunk format,
                        // S1's raw bit 13 → S2 bit 12, S1's raw bit 14 → S2 bit 13.
                        // Default topSolidBit=0x0C (bit 12) is already correct for floor.
                        // Default lrbSolidBit=0x0D (bit 13) stays for ceiling/wall probes:
                        // although S1 ROM uses d5=$D for all probes, the conversion maps
                        // S1 solidity types so that top-solid (type 1) only sets bit 12
                        // and lrb-solid (type 2) only sets bit 13. Using 0x0D for
                        // ceiling/wall means top-solid-only tiles remain one-way platforms.

                        // S3K init override: Character_Speeds table provides different init-time
                        // values that persist until the first water or speed shoes event.
                        // ROM ref: sonic3k.asm:21467-21474 (Character_Speeds loaded at player init)
                        PhysicsProfile initProfile = provider.getInitProfile(charType);
                        if (initProfile != null && profile != null) {
                                this.canonicalProfile = profile;
                                // Overwrite only the fields that Character_Speeds sets (max, accel, decel)
                                this.runAccel = initProfile.runAccel();
                                this.runDecel = initProfile.runDecel();
                                this.max = initProfile.max();
                                this.initPhysicsActive = true;
                        } else {
                                this.canonicalProfile = null;
                                this.initPhysicsActive = false;
                        }
                } catch (Exception e) {
                        // Graceful fallback: defineSpeeds() values remain
                        LOGGER.fine("PhysicsProvider unavailable, using defineSpeeds() values: " + e.getMessage());
                }
                // Cross-game donation: override only typed rules with donated capabilities.
                if (CrossGameFeatureProvider.isActive()) {
                        this.gameRules = currentCrossGameFeatures().getHybridRules();
                }
                ensurePersistentInstaShieldObject();
                bubbleAnimId = module != null ? module.resolveAnimationId(CanonicalAnimation.BUBBLE) : -1;
        }

        private void ensurePersistentInstaShieldObject() {
                // ROM: SpawnLevelMainSprites_SpawnPlayers creates the persistent Sonic-only
                // insta-shield object after player setup. In the engine, powerUpSpawner can
                // arrive later than physics resolution, so re-check when either dependency changes.
                if (instaShieldObject != null || powerUpSpawner == null) {
                        return;
                }
                PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
                if (capabilityRules == null
                        || !capabilityRules.instaShieldEnabled()
                        || getSecondaryAbility() != SecondaryAbility.INSTA_SHIELD) {
                        return;
                }
                try {
                        instaShieldObject = powerUpSpawner.createInstaShield(this);
                } catch (IllegalStateException e) {
                        // Services not yet available (e.g., prepareForLevel before ObjectManager).
                        // Deferred until the next level-load or spawner-injection pass.
                }
                // Registration remains deferred to tickStatus() to avoid double-add when
                // resolvePhysicsProfile() and tickStatus() run on the same frame.
        }

        private void refreshRuntimeBoundStateIfNeeded() {
                GameModule module = currentGameModule();
                if (module == null || module == runtimeBoundStateModule) {
                        return;
                }
                resolvePhysicsProfile(module);
        }

        private GameModule bootstrapSafeGameModule() {
                return GameServices.bootstrapGameModule();
        }

        /**
         * Clears the S3K Character_Speeds init override and resets the mutable speed fields
         * to the canonical profile values. Called on water entry/exit, speed shoes give/expire,
         * and Super state transitions — matching ROM behavior where any of these events
         * overwrites the Character_Speeds values with hardcoded constants.
         * <p>No-op if no init override was active (S1/S2, or already cleared).
         */
        private void clearInitOverride() {
                if (!initPhysicsActive) return;
                initPhysicsActive = false;
                resetSpeedConstantsToCanonical();
        }

        private void resetSpeedConstantsToCanonical() {
                if (canonicalProfile != null) {
                        this.runAccel = canonicalProfile.runAccel();
                        this.runDecel = canonicalProfile.runDecel();
                        this.max = canonicalProfile.max();
                } else if (physicsProfile != null) {
                        this.runAccel = physicsProfile.runAccel();
                        this.runDecel = physicsProfile.runDecel();
                        this.max = physicsProfile.max();
                }
        }

        /**
         * Returns the resolved per-character physics profile, or {@code null} if
         * none has been bound yet (early bootstrap before
         * {@link #resolvePhysicsProfile()}).
         */
        public PhysicsProfile getPhysicsProfile() {
                return physicsProfile;
        }

        /**
         * Applies an external physics profile, overwriting current speed values.
         * Used by SuperStateController to swap between normal and Super physics.
         * Also clears the S3K init override since Super transitions reset constants.
         */
        public void applyExternalPhysicsProfile(PhysicsProfile profile) {
                if (profile == null) return;
                clearInitOverride();
                this.physicsProfile = profile;
                applyProfileToFields(profile);
        }

        /** Writes all 18 profile values to the mutable speed fields. */
        private void applyProfileToFields(PhysicsProfile profile) {
                this.runAccel = profile.runAccel();
                this.runDecel = profile.runDecel();
                this.friction = profile.friction();
                this.max = profile.max();
                this.jump = profile.jump();
                this.slopeRunning = profile.slopeRunning();
                this.slopeRollingUp = profile.slopeRollingUp();
                this.slopeRollingDown = profile.slopeRollingDown();
                this.rollDecel = profile.rollDecel();
                this.minStartRollSpeed = profile.minStartRollSpeed();
                this.minRollSpeed = profile.minRollSpeed();
                this.maxRoll = profile.maxRoll();
                this.rollHeight = profile.rollHeight();
                this.runHeight = profile.runHeight();
                this.standXRadius = profile.standXRadius();
                this.standYRadius = profile.standYRadius();
                this.rollXRadius = profile.rollXRadius();
                this.rollYRadius = profile.rollYRadius();
        }

        @Override
        public GameRules getGameRules() {
                return gameRules;
        }

        private PlayerMovementRules playerMovementRulesOrNull() {
                GameRules rules = getGameRules();
                if (rules != null && rules.playerMovement() != null) {
                        return rules.playerMovement();
                }
                return null;
        }

        private PlayerCapabilityRules playerCapabilityRulesOrNull() {
                GameRules rules = getGameRules();
                if (rules != null && rules.playerCapability() != null) {
                        return rules.playerCapability();
                }
                return null;
        }

        /** Package-private for testing. */
        protected void setGameRulesForTest(GameRules rules) {
                this.gameRules = rules;
        }

        /** Sets shield state directly without spawning a shield object. For testing only. */
        protected void setShieldState(boolean hasShield, ShieldType type) {
                this.shield = hasShield;
                this.shieldType = type;
        }

        /**
         * Returns the physics modifiers (water/speed shoes rules) for the current game.
         * May be null if no GameModule provider is active.
         */
        public PhysicsModifiers getPhysicsModifiers() {
                return physicsModifiers;
        }

        /**
         * Returns whether debug movement mode is active.
         */
        public boolean isDebugMode() {
                return debugMode;
        }

        /**
         * Toggles debug movement mode on/off.
         * Resets interaction states to prevent getting stuck in object-controlled states.
         */
        public void toggleDebugMode() {
                debugMode = !debugMode;
                // Reset ALL interaction states when entering or leaving debug mode
                // This prevents getting stuck on LauncherSprings or in other locked states
                controlLocked = false;
                pinballMode = false;
                pinballSpeedLock = false;
                objectControlled = false;
                objectControlAllowsCpu = false;
                objectControlSuppressesMovement = false;
                controller.clearObjectControlledSolidContactOwner();
                onObject = false;           // Clear "standing on object" flag
                latchedSolidObjectId = 0;
                stickToConvex = false;      // Clear slope adhesion flag (set by slope-mode launches)
                suppressGroundWallCollision = false;
                suppressedObjectMoveAndFallAxes = 0;
        }

        /**
         * Sets debug movement mode.
         */
        public void setDebugMode(boolean debugMode) {
                this.debugMode = debugMode;
        }

        public short getGSpeed() {
                return gSpeed;
        }

        public void setGSpeed(short gSpeed) {
                this.gSpeed = gSpeed;
        }

        public short getRunAccel() {
                // ROM: Speed shoes have no physics effect when Super Sonic is active.
                // The shoes timer/music still run, but Super profile values take priority.
                // (s2.asm:36014 — expiry code restores Super values, confirming this design)
                boolean effectiveShoes = hasSpeedShoes() && !isSuperSonic();
                if (physicsModifiers != null) {
                        return physicsModifiers.effectiveAccel(runAccel, waterPhysicsActive, effectiveShoes);
                }
                // Fallback: Water overrides shoes (ROM sets absolute values on water entry)
                if (waterPhysicsActive) {
                        return (short) (runAccel / 2);
                }
                if (effectiveShoes) {
                        return (short) (runAccel * 2);
                }
                return runAccel;
        }

        public short getRunDecel() {
                boolean effectiveShoes = hasSpeedShoes() && !isSuperSonic();
                if (physicsModifiers != null) {
                        return physicsModifiers.effectiveDecel(runDecel, waterPhysicsActive, effectiveShoes);
                }
                // Fallback: Water overrides shoes
                if (waterPhysicsActive) {
                        return (short) (runDecel / 2);
                }
                return runDecel;
        }

        public short getSlopeRunning() {
                return slopeRunning;
        }

        public short getSlopeRollingUp() {
                return slopeRollingUp;
        }

        public short getSlopeRollingDown() {
                return slopeRollingDown;
        }

        public short getFriction() {
                boolean effectiveShoes = hasSpeedShoes() && !isSuperSonic();
                if (physicsModifiers != null) {
                        return physicsModifiers.effectiveFriction(friction, waterPhysicsActive, effectiveShoes);
                }
                // Fallback: Water overrides shoes (ROM sets absolute values on water entry)
                if (waterPhysicsActive) {
                        return (short) (friction / 2);
                }
                if (effectiveShoes) {
                        return (short) (friction * 2);
                }
                return friction;
        }

        public short getMax() {
                boolean effectiveShoes = hasSpeedShoes() && !isSuperSonic();
                if (physicsModifiers != null) {
                        return physicsModifiers.effectiveMax(max, waterPhysicsActive, effectiveShoes);
                }
                // Fallback: Water overrides shoes (ROM sets absolute values on water entry)
                if (waterPhysicsActive) {
                        return (short) (max / 2);
                }
                if (effectiveShoes) {
                        return (short) (max * 2);
                }
                return max;
        }

        /**
         * Returns the base gravity value applied by ObjectMoveAndFall.
         * This method returns the FULL gravity regardless of water state.
         * Underwater gravity reduction is handled separately in PlayableSpriteMovement.modeAirborne()
         * by subtracting a reduction AFTER applying gravity, matching ROM behavior exactly:
         *
         * Normal airborne:
         *   - ROM: ObjectMoveAndFall adds 0x38 to y_vel (s2.asm:29950)
         *   - ROM: Then Obj01_MdAir subtracts 0x28 if underwater (s2.asm:36170)
         *   - Net underwater gravity = 0x38 - 0x28 = 0x10
         *
         * Hurt airborne:
         *   - ROM: Obj01_Hurt adds 0x30 to y_vel (s2.asm:37799)
         *   - ROM: Then subtracts 0x20 if underwater (s2.asm:37802)
         *   - Net hurt underwater gravity = 0x30 - 0x20 = 0x10
         *
         * Normal: 0x38 (56 subpixels)
         * Hurt: 0x30 (48 subpixels)
         *
         * @see docs/s2disasm/s2.asm lines 29950 (ObjectMoveAndFall), 36170 (normal underwater), 37799-37802 (hurt + underwater)
         */
        @Override
        public float getGravity() {
                if (hurt) {
                        return 0x30; // Reduced hurt gravity (SPG: 0.1875 = 48 subpixels)
                }
                return gravity; // Normal gravity (0x38)
        }

        public byte getAngle() {
                return angle;
        }

        public void setAngle(byte angle) {
                this.angle = angle;
        }

        public short[] getXHistory() {
                return xHistory;
        }

        public short[] getYHistory() {
                return yHistory;
        }

        /**
         * Diagnostic-only writeback for ROM-style follower history buffers.
         *
         * <p><strong>Trace replay must not call this on a normal green run.</strong>
         * Recorded history snapshots are comparison-only diagnostics. This
         * accessor is kept for focused unit diagnostics and must not be wired
         * into committed trace replay bootstrap or per-frame replay loops.
         */
        public void hydrateRecordedHistory(short[] xHistory, short[] yHistory,
                                    short[] inputHistory, byte[] statusHistory,
                                    int historyPos) {
                if (xHistory == null || yHistory == null || inputHistory == null || statusHistory == null) {
                        throw new IllegalArgumentException("History buffers must be non-null");
                }
                if (xHistory.length != this.xHistory.length
                        || yHistory.length != this.yHistory.length
                        || inputHistory.length != this.inputHistory.length
                        || statusHistory.length != this.statusHistory.length) {
                        throw new IllegalArgumentException("History buffers must all have length "
                                + this.xHistory.length);
                }
                System.arraycopy(xHistory, 0, this.xHistory, 0, this.xHistory.length);
                System.arraycopy(yHistory, 0, this.yHistory, 0, this.yHistory.length);
                System.arraycopy(inputHistory, 0, this.inputHistory, 0, this.inputHistory.length);
                for (int i = 0; i < this.jumpPressHistory.length; i++) {
                        this.jumpPressHistory[i] = 0;
                }
                System.arraycopy(statusHistory, 0, this.statusHistory, 0, this.statusHistory.length);
                this.historyPos = (byte) historyPos;
        }

        /**
         * Read-only snapshot of the ROM-style follower history rings, for the
         * trace replay bootstrap comparator (frame-0 assertion). Returns
         * defensive copies so the caller cannot mutate engine state.
         */
        public short[] copyXHistory() {
                return xHistory.clone();
        }

        public short[] copyYHistory() {
                return yHistory.clone();
        }

        public short[] copyInputHistory() {
                return inputHistory.clone();
        }

        public byte[] copyStatusHistory() {
                return statusHistory.clone();
        }

        public int historyPos() {
                return historyPos & 0xFF;
        }

        public boolean isCpuControlled() {
                return cpuControlled;
        }

        public void setCpuControlled(boolean cpuControlled) {
                this.cpuControlled = cpuControlled;
        }

        public SidekickCpuController getCpuController() {
                return cpuController;
        }

        public void setCpuController(SidekickCpuController cpuController) {
                this.cpuController = cpuController;
        }

        public boolean getRolling() {
                return rolling;
        }

        /**
         * Returns the Y position adjustment needed when transitioning between standing and rolling.
         *
         * The ROM uses center-based coordinates where y_pos is the sprite center, so it adjusts by
         * the RADIUS difference (standYRadius - rollYRadius = 19 - 14 = 5 pixels for Sonic).
         *
         * This engine uses top-left coordinates. When height changes (floor/ceiling orientation),
         * we add the full HEIGHT difference (runHeight - rollHeight = 10) to the top-left Y so
         * the center shifts by 5 (matching ROM). When on a WALL (left/right), setRolling() adjusts
         * WIDTH instead of HEIGHT, so height stays constant — we only need to shift top by 5 to
         * move center by 5.
         *
         * When entering roll: setY(getY() + getRollHeightAdjustment()) - moves top down, feet stay planted
         * When exiting roll: setY(getY() - getRollHeightAdjustment()) - moves top up, feet stay planted
         *
         * @return the top-left Y adjustment needed (orientation-aware)
         */
        public short getRollHeightAdjustment() {
                int fullDiff = runHeight - rollHeight;
                // On walls, setRolling() changes WIDTH, not HEIGHT. Since height stays constant,
                // adjusting top by fullDiff would move centre by fullDiff, overshooting the ROM's
                // centre+=5 by 5px. Use half (the radius diff) to match ROM centre behaviour.
                if (GroundMode.LEFTWALL.equals(runningMode) || GroundMode.RIGHTWALL.equals(runningMode)) {
                        return (short) (fullDiff / 2);
                }
                return (short) fullDiff;
        }

        /**
         * Sets the rolling state and handles hitbox changes.
         *
         * IMPORTANT: This method changes the rolling flag, hitbox radii, and visual dimensions.
         * It does NOT adjust Y position. The Y position adjustment must be done by the caller using
         * getRollHeightAdjustment() because:
         * 1. ROM does the adjustment in specific contexts (jump, spindash, roll start/end)
         * 2. Some callers (like applyHurt) should NOT adjust Y position
         *
         * @param rolling true to enter rolling state, false to exit
         */
        public void setRolling(boolean rolling) {
                if (this.rolling == rolling) {
                        if (rolling) {
                                applyRollAnimationFromProfile(); setSkidding(false);
                        }
                        return;
                }

                // Update visual dimensions (no position adjustment)
                if (GroundMode.CEILING.equals(runningMode) || GroundMode.GROUND.equals(runningMode)) {
                        int newHeight = rolling ? rollHeight : runHeight;
                        setHeight(newHeight);
                } else {
                        int newWidth = rolling ? rollHeight : runHeight;
                        setWidth(newWidth);
                }

                // Apply appropriate collision radii
                if (rolling) {
                        applyRollingRadii(false);
                } else {
                        applyStandingRadii(false);
                }

                this.rolling = rolling;
                if (rolling) {
                        applyRollAnimationFromProfile(); setSkidding(false);
                }
        }

        private void applyRollAnimationFromProfile() {
                if (animationProfile instanceof ScriptedVelocityAnimationProfile profile) {
                        setAnimationId(profile.getRollAnimId());
                }
        }

        /**
         * Clears only the status rolling bit for ROM paths that write the
         * status byte directly without touching collision radii or dimensions.
         */
        public void clearRollingFlagPreserveRadii() {
                this.rolling = false;
        }

        /**
         * Writes only the status rolling bit for ROM paths that already updated
         * radii/dimensions explicitly and must not apply the generic box change.
         */
        public void setRollingFlagPreserveRadii(boolean rolling) {
                this.rolling = rolling;
                if (rolling) {
                        applyRollAnimationFromProfile(); setSkidding(false);
                }
        }

        public boolean getRollingJump() {
                return rollingJump;
        }

        public void setRollingJump(boolean rollingJump) {
                this.rollingJump = rollingJump;
                if (rollingJump) {
                        // ROM Sonic_Jump restores default_y_radius/default_x_radius before
                        // branching to Sonic_RollJump for an already-rolling jump.
                        applyStandingRadii(false);
                }
        }

        /**
         * Returns whether pinball mode is active.
         * When true, rolling cannot be cleared on landing and rolling cannot stop at 0 speed.
         */
        public boolean getPinballMode() {
                return pinballMode;
        }

        /**
         * Sets pinball mode. When true, the player must continue rolling -
         * rolling won't be cleared on landing and if speed reaches 0, a boost is given.
         */
        public void setPinballMode(boolean pinballMode) {
                this.pinballMode = pinballMode;
        }

        public boolean getPinballSpeedLock() {
                return pinballSpeedLock;
        }

        public void setPinballSpeedLock(boolean pinballSpeedLock) {
                this.pinballSpeedLock = pinballSpeedLock;
        }

        public void preserveRollingOnNextLanding() {
                this.preserveRollingOnNextLanding = true;
        }

        public boolean consumePreserveRollingOnNextLanding() {
                boolean preserve = preserveRollingOnNextLanding;
                preserveRollingOnNextLanding = false;
                return preserve;
        }

        public void preserveRollingOnNextRollStop() {
                this.preserveRollingOnNextRollStop = true;
        }

        public boolean consumePreserveRollingOnNextRollStop() {
                boolean preserve = preserveRollingOnNextRollStop;
                preserveRollingOnNextRollStop = false;
                return preserve;
        }

        public boolean shouldPreserveRollingOnNextRollStop() {
                return preserveRollingOnNextRollStop;
        }

        public void clearObjectPreservedRollingHandoff() {
                preserveRollingOnNextRollStop = false;
                objectPreservedRollBoostFollowup = false;
                objectPreservedRollWallProbe = false;
                objectPreservedRollVelocityCarry = false;
        }

        public void markObjectPreservedRollBoostFollowup() {
                this.objectPreservedRollBoostFollowup = true;
        }

        public boolean consumeObjectPreservedRollBoostFollowup() {
                boolean followup = objectPreservedRollBoostFollowup;
                objectPreservedRollBoostFollowup = false;
                return followup;
        }

        public void markObjectPreservedRollWallProbe() {
                this.objectPreservedRollWallProbe = true;
        }

        public boolean shouldApplyObjectPreservedRollWallProbe() {
                return objectPreservedRollWallProbe;
        }

        public boolean consumeObjectPreservedRollWallProbe() {
                boolean probe = objectPreservedRollWallProbe;
                objectPreservedRollWallProbe = false;
                return probe;
        }

        public void markObjectPreservedRollVelocityCarry() {
                this.objectPreservedRollVelocityCarry = true;
        }

        public boolean shouldApplyObjectPreservedRollVelocityCarry() {
                return objectPreservedRollVelocityCarry;
        }

        public void clearObjectPreservedRollVelocityCarry() {
                this.objectPreservedRollVelocityCarry = false;
        }

        public boolean isTunnelMode() {
                return tunnelMode;
        }

        public void setTunnelMode(boolean tunnelMode) {
                this.tunnelMode = tunnelMode;
        }

        @Override
        public void setHeight(int height) {
                super.setHeight(height);
        }

        public short getRollDecel() {
                return rollDecel;
        }

        public short getMaxRoll() {
                return maxRoll;
        }

        public short getMinStartRollSpeed() {
                return minStartRollSpeed;
        }

        public short getMinRollSpeed() {
                return minRollSpeed;
        }

        public short getXRadius() {
                return xRadius;
        }

        public short getYRadius() {
                return yRadius;
        }

        public short getStandYRadius() {
                return standYRadius;
        }

        public short getStandXRadius() {
                return standXRadius;
        }

        public short getRollYRadius() {
                return rollYRadius;
        }

        /**
         * Apply standing hitbox radii (y_radius=19, x_radius=9).
         * ROM: Used when unrolling or landing.
         * @param adjustY If true, adjust Y position for height change (not used - always pass false)
         */
        public void applyStandingRadii(boolean adjustY) {
                setCollisionRadii(standXRadius, standYRadius, adjustY);
        }

        /**
         * Apply rolling hitbox radii (y_radius=14, x_radius=7).
         * ROM: Used when starting roll, spindash release, or jumping.
         * @param adjustY If true, adjust Y position for height change (not used - always pass false)
         */
        public void applyRollingRadii(boolean adjustY) {
                setCollisionRadii(rollXRadius, rollYRadius, adjustY);
        }

        /**
         * Apply custom collision radii (e.g., Knuckles glide: 10x10).
         * ROM: Knux_Test_For_Glide sets y_radius=x_radius=$0A.
         */
        public void applyCustomRadii(int newXRadius, int newYRadius) {
                setCollisionRadii((short) newXRadius, (short) newYRadius, false);
        }

        /**
         * Restore standing collision radii (default_y_radius, default_x_radius).
         * ROM: Knuckles uses this when exiting glide/climb states.
         */
        public void restoreDefaultRadii() {
                setCollisionRadii(standXRadius, standYRadius, false);
        }

        protected void setCollisionRadii(short newXRadius, short newYRadius, boolean adjustY) {
                this.xRadius = newXRadius;
                this.yRadius = newYRadius;
                updateSensorOffsetsFromRadii();
        }

        private void updateSensorOffsetsFromRadii() {
                if (groundSensors == null || ceilingSensors == null || pushSensors == null) {
                        return;
                }

                byte xRad = (byte) xRadius;
                byte yRad = (byte) yRadius;
                // SPG: Push sensors always use x = +/-10, regardless of rolling state
                byte push = 10;

                if (groundSensors != null && groundSensors.length >= 2) {
                        groundSensors[0].setOffset((byte) -xRad, yRad);
                        groundSensors[1].setOffset(xRad, yRad);
                }

                if (ceilingSensors != null && ceilingSensors.length >= 2) {
                        ceilingSensors[0].setOffset((byte) -xRad, (byte) -yRad);
                        ceilingSensors[1].setOffset(xRad, (byte) -yRad);
                }

                if (pushSensors != null && pushSensors.length >= 2) {
                        pushSensors[0].setOffset((byte) -push, (byte) 0);
                        pushSensors[1].setOffset(push, (byte) 0);
                }
                // Update push sensor Y offset based on current ground state
                updatePushSensorYOffset();
        }

        /**
         * SPG: Push sensors shift down by +8 on flat ground so low steps are pushed
         * against instead of being stepped onto. In air or on slopes, Y offset is 0.
         */
        public void updatePushSensorYOffset() {
                if (pushSensors == null || pushSensors.length < 2) {
                        return;
                }
                // ROM: Y offset = +8 when (angle & 0x38) == 0, i.e., near-flat angles (0-7, 248-255)
                // This allows the offset on slight slopes, not just strictly flat ground.
                // See s2.asm:43517-43519 in CalcRoomInFront
                boolean onFlatGround = !air && runningMode == GroundMode.GROUND && (angle & 0x38) == 0;
                byte yOffset = onFlatGround ? (byte) 8 : (byte) 0;
                // SPG: Push sensors always use x = +/-10, regardless of rolling state
                byte push = 10;
                pushSensors[0].setOffset((byte) -push, yOffset);
                pushSensors[1].setOffset(push, yOffset);
        }

        public SpriteMovementManager getMovementManager() {
                return controller.getMovement();
        }

        @Override
        public void applyPostObjectLandingAbilities(int savedDoubleJumpFlag) {
                controller.getMovement().applyPostObjectLandingAbilities(this, savedDoubleJumpFlag);
        }

        public PlayableSpriteAnimation getAnimationManager() {
                return controller.getAnimation();
        }

        protected abstract void defineSpeeds();

        public final void move() {
                move(xSpeed, ySpeed);
        }

        public GroundMode getGroundMode() {
                return runningMode;
        }

        public void setGroundMode(GroundMode groundMode) {
                if (this.runningMode != groundMode) {
                        updateSpriteShapeForRunningMode(groundMode, this.runningMode);
                        this.runningMode = groundMode;
                        // SPG: Push sensor Y offset changes based on ground mode
                        updatePushSensorYOffset();
                }
        }

        protected void updateSpriteShapeForRunningMode(GroundMode newRunningMode, GroundMode oldRunningMode) {
                // Best if statement ever...
                if (((GroundMode.CEILING.equals(newRunningMode) || GroundMode.GROUND.equals(newRunningMode)) &&
                                (GroundMode.LEFTWALL.equals(oldRunningMode)
                                                || GroundMode.RIGHTWALL.equals(oldRunningMode)))
                                ||
                                ((GroundMode.RIGHTWALL.equals(newRunningMode)
                                                || GroundMode.LEFTWALL.equals(newRunningMode)) &&
                                                ((GroundMode.CEILING.equals(oldRunningMode)
                                                                || GroundMode.GROUND.equals(oldRunningMode))))) {
                        int oldHeight = getHeight();
                        int oldWidth = getWidth();

                        short oldCentreX = getCentreX();
                        short oldCentreY = getCentreY();

                        setHeight(oldWidth);
                        setWidth(oldHeight);

                        setX((short) (oldCentreX - (getWidth() / 2)));
                        setY((short) (oldCentreY - (getHeight() / 2)));
                }
        }

        public final short getCentreX(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += xHistory.length;
                }
                return xHistory[desired];
        }

        public final short getCentreY(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += yHistory.length;
                }
                return yHistory[desired];
        }

        /**
         * Fills position history with current position. Used by paths that
         * want to flush the camera-delay buffer ONLY (no input/status clear),
         * such as the engine-internal sidekick respawn seeding and the
         * spindash-release scroll-delay reset where ROM only manipulates
         * `H_scroll_frame_offset` without touching Stat_table.
         *
         * Use {@link #resetPositionAndStatTableHistory()} for the full
         * ROM `Reset_Player_Position_Array` semantics (Pos_table refill +
         * Stat_table clear).
         */
        public void resetPositionHistory() {
                short currentX = getCentreX();
                short currentY = getCentreY();
                for (int i = 0; i < xHistory.length; i++) {
                        xHistory[i] = currentX;
                        yHistory[i] = currentY;
                }
                followerHistoryRecordedThisTick = false;
        }

        /**
         * Mirrors ROM `Reset_Player_Position_Array` (sonic3k.asm:22166-22193):
         * writes Pos_table = (x_pos, y_pos) for all 64 entries AND clears all
         * Stat_table 32-bit slots to 0 (`move.l #0, (a2)+`). Called by
         * Sonic_FireShield (sonic3k.asm:23428), Sonic_HyperDash
         * (sonic3k.asm:23521), and the player-init / death-respawn paths
         * (sonic3k.asm:21525, 21938). Subsequent delayed Tails_CPU_Control
         * reads of Stat_table return ZERO for any slot not yet refilled by
         * Sonic_RecordPos.
         *
         * AIZ trace F7381 motivation: Sonic activates Fire Shield Dash at
         * F7366 which runs Reset_Player_Position_Array. Without clearing
         * inputHistory and statusHistory the engine retained Sonic's true
         * F7350-F7365 LEFT input bits, while ROM Stat_table read zeros for
         * any slot not yet refilled in the 16 frames after the reset. The
         * stale LEFT input drove Tails CPU to a -0x18 x_speed where ROM
         * holds 0x0000 (sonic3k.asm:26683-26705,26755-26785).
         */
        public void resetPositionAndStatTableHistory() {
                short currentX = getCentreX();
                short currentY = getCentreY();
                for (int i = 0; i < xHistory.length; i++) {
                        xHistory[i] = currentX;
                        yHistory[i] = currentY;
                        inputHistory[i] = 0;
                        jumpPressHistory[i] = 0;
                        statusHistory[i] = 0;
                }
                historyPos = 0;
                followerHistoryRecordedThisTick = false;
        }

        /**
         * Pre-fills the position history ring with the leader's centre offset
         * by the given delta, mirroring ROM Obj01_Init's
         * {@code subi.w #$20,x_pos / addi_.w #4,y_pos / Sonic_RecordPos x 64 /
         * addi.w #$20,x_pos / subi_.w #4,y_pos} sequence (s2.asm:35907-35918,
         * sonic3k.asm:21936-21940). That sequence seeds Sonic_Pos_Record_Buf
         * with Tails' spawn-offset position so the first ~16 frames of
         * Tails_CPU_Normal read targetX = Tails_x (no acceleration) before
         * the live Sonic_RecordPos writes the actual Sonic centre into the
         * ring.
         *
         * <p>Used by the sidekick CPU bootstrap to recreate the same pre-fill
         * profile when entering the title-card prelude. Input/jump/status
         * history are left unchanged (ROM Init does not touch Stat_Record_Buf).
         *
         * <p>Also resets {@code historyPos} so the next
         * {@link #recordFollowerHistoryForTick()} call writes to slot 0 — ROM
         * {@code Sonic_Pos_Record_Index} is reset to 0 just before the 64-entry
         * fill loop (s2.asm:35909), and the post-fill {@code addq.b #4,...}
         * wraparound leaves it back at 0 so the first live Sonic_RecordPos
         * write goes to slot 0.
         */
        public void prefillPositionHistoryWithOffset(int xOffset, int yOffset) {
                prefillPositionHistoryWithCentre(
                                (short) (getCentreX() + xOffset),
                                (short) (getCentreY() + yOffset));
        }

        /**
         * Fills the entire delayed Pos_table ring with an explicit centre
         * coordinate (rather than the live centre {@link #resetPositionHistory()}
         * uses). Mirrors ROM filling {@code Sonic_Pos_Record_Buf} with the
         * leader's spawn position at level-load
         * (SpawnLevelMainSprites / Reset_Player_Position_Array,
         * sonic3k.asm:8359-8369,22166-22193) before the leader's first physics
         * tick moves it. Used by the deferred sidekick placement so the
         * delayed-follow target reproduces the ROM "frozen for 16 frames"
         * spawn-anchored ring even when the controller first ticks after the
         * leader has already moved.
         */
        /**
         * Mirrors ROM {@code Obj01_Init_Continued} (S2, s2.asm:36201-36217) and
         * its S3K twin {@code Sonic_Init_Continued} -> {@code Reset_Player_Position_Array}
         * (sonic3k.asm:21931-21941, 22166-22178): with the leader's position
         * temporarily offset by {@code (-$20, +4)}, {@code Sonic_Pos_Record_Index}
         * is zeroed and {@code Sonic_RecordPos} is called 64 times, each iteration
         * immediately re-zeroing the {@code Sonic_Stat_Record_Buf} entry it just
         * wrote ({@code subq.w #4,a1 / move.l #0,(a1)}). The result is a Pos_table
         * entirely filled with the offset spawn coordinate and a completely zeroed
         * Stat_table, with the record index wrapped back to 0.
         *
         * <p>This runs on EVERY level (re)init, including a star-post restart and
         * a return from a special stage: the {@code tst.b (Last_star_pole_hit).w /
         * bne.s Obj01_Init_Continued} branch above it skips only the art / saved-position
         * block, never the refill itself.
         *
         * <p>Distinct from {@link #prefillPositionHistoryWithCentre}, which fills the
         * Pos_table only; the ROM sequence also clears the Stat_table, so a delayed
         * {@code Tails_CPU_Control} read cannot see the previous level's recorded
         * leader input or status bits.
         */
        public void resetPositionAndStatTableHistoryAtCentre(short prefillX, short prefillY) {
                for (int i = 0; i < xHistory.length; i++) {
                        xHistory[i] = prefillX;
                        yHistory[i] = prefillY;
                        inputHistory[i] = 0;
                        jumpPressHistory[i] = 0;
                        statusHistory[i] = 0;
                }
                // ROM leaves Sonic_Pos_Record_Index at 0 after the 64-iteration wrap,
                // so the next live Sonic_RecordPos writes slot 0. The engine's
                // recordFollowerHistoryForTick() increments before writing, so park
                // the cursor one slot earlier.
                historyPos = 63;
                followerHistoryRecordedThisTick = false;
        }

        public void prefillPositionHistoryWithCentre(short prefillX, short prefillY) {
                for (int i = 0; i < xHistory.length; i++) {
                        xHistory[i] = prefillX;
                        yHistory[i] = prefillY;
                }
                // Engine's recordFollowerHistoryForTick() increments-then-writes,
                // so set historyPos to 63 (one slot before slot 0) so the next
                // record call writes to slot 0 matching ROM's first live write.
                historyPos = 63;
                followerHistoryRecordedThisTick = false;
        }

        /**
         * Clears the per-tick gate that prevents
         * {@link #recordFollowerHistoryForTick()} from running twice in the
         * same playable tick. Normal frames call this from
         * {@link #endOfTick()}; bootstrap paths that record follower history
         * outside the standard playable tick (e.g. the title-card prelude
         * which only ticks sidekicks but still needs Sonic_RecordPos parity)
         * call it explicitly so the next prelude frame can record again.
         */
        public void clearFollowerHistoryRecordedFlag() {
                followerHistoryRecordedThisTick = false;
        }

        /**
         * Returns the recorded input bitmask from framesBehind frames ago.
         * ROM: Reads from Sonic_Stat_Record_Buf for Tails CPU input replay.
         * Use INPUT_UP/DOWN/LEFT/RIGHT/JUMP constants to test individual bits.
         */
        public final short getInputHistory(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += inputHistory.length;
                }
                return inputHistory[desired];
        }

        /**
         * Returns whether the delayed logical controller word carried a jump
         * press bit. ROM Tails CPU consumes the low byte of Ctrl_1_Logical
         * together with the delayed held buttons when copying to Ctrl_2_logical.
         */
        public final boolean getJumpPressHistory(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += jumpPressHistory.length;
                }
                return jumpPressHistory[desired] != 0;
        }

        /**
         * Returns the circular history slot index used by delayed follower reads.
         * Diagnostic-only: replay reports use this to compare engine history
         * selection against ROM Stat_table/Pos_table evidence.
         */
        public final int getHistorySlotIndex(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += inputHistory.length;
                }
                return desired;
        }

        /**
         * Returns the recorded status flags from framesBehind frames ago.
         * ROM: Reads from Sonic_Stat_Record_Buf for Tails CPU input replay.
         * Use STATUS_FACING_LEFT/IN_AIR/ROLLING/PUSHING constants to test individual bits.
         */
        public final byte getStatusHistory(int framesBehind) {
                int desired = historyPos - framesBehind;
                if (desired < 0) {
                        desired += statusHistory.length;
                }
                return statusHistory[desired];
        }

        /**
         * Updates sensor active states based on movement direction and ground mode.
         * Refactored to avoid per-frame array allocations by directly setting sensor states.
         */
        public void updateSensors(short originalX, short originalY) {
                Sensor groundA = groundSensors[0];
                Sensor groundB = groundSensors[1];
                Sensor ceilingC = ceilingSensors[0];
                Sensor ceilingD = ceilingSensors[1];
                Sensor pushE = pushSensors[0];
                Sensor pushF = pushSensors[1];

                if (getAir()) {
                        // Use ROM-accurate angle calculation via TrigLookupTable.calcAngle
                        // ROM: Sonic_DoLevelCollision (s2.asm:37547-37557)
                        int motionAngle = TrigLookupTable.calcAngle(xSpeed, ySpeed);

                        // ROM quadrant calculation: subi.b #$20,d0 / andi.b #$C0,d0
                        // This creates quadrants offset by 32 degrees:
                        // - 0xC0: Angles 0-31 or 224-255 (mostly right)
                        // - 0x00: Angles 32-95 (mostly down)
                        // - 0x40: Angles 96-159 (mostly left)
                        // - 0x80: Angles 160-223 (mostly up)
                        int quadrant = ((motionAngle - 0x20) & 0xC0) & 0xFF;

                        switch (quadrant) {
                                case 0xC0 -> {
                                        // Mostly Right (angles 0-31, 224-255): A, B, C, D, F active; E inactive
                                        groundA.setActive(true);
                                        groundB.setActive(true);
                                        ceilingC.setActive(true);
                                        ceilingD.setActive(true);
                                        pushE.setActive(false);
                                        pushF.setActive(true);
                                }
                                case 0x40 -> {
                                        // Mostly Left (angles 96-159): A, B, C, D, E active; F inactive
                                        groundA.setActive(true);
                                        groundB.setActive(true);
                                        ceilingC.setActive(true);
                                        ceilingD.setActive(true);
                                        pushE.setActive(true);
                                        pushF.setActive(false);
                                }
                                case 0x80 -> {
                                        // Mostly Up (angles 160-223): C, D, E, F active; A, B inactive
                                        groundA.setActive(false);
                                        groundB.setActive(false);
                                        ceilingC.setActive(true);
                                        ceilingD.setActive(true);
                                        pushE.setActive(true);
                                        pushF.setActive(true);
                                }
                                default -> {
                                        // 0x00: Mostly Down (angles 32-95): A, B, E, F active; C, D inactive
                                        groundA.setActive(true);
                                        groundB.setActive(true);
                                        ceilingC.setActive(false);
                                        ceilingD.setActive(false);
                                        pushE.setActive(true);
                                        pushF.setActive(true);
                                }
                        }
                } else {
                        // Ground sensors always active when grounded
                        groundA.setActive(true);
                        groundB.setActive(true);
                        // Ceiling sensors always inactive when grounded
                        ceilingC.setActive(false);
                        ceilingD.setActive(false);

                        // Push sensors active on floor/ceiling, disabled on walls
                        boolean pushActive = (runningMode == GroundMode.GROUND || runningMode == GroundMode.CEILING);
                        // Use gSpeed (speed along surface) instead of xSpeed for direction
                        if (gSpeed > 0) {
                                pushE.setActive(false);
                                pushF.setActive(pushActive);
                        } else if (gSpeed < 0) {
                                pushE.setActive(pushActive);
                                pushF.setActive(false);
                        } else {
                                pushE.setActive(false);
                                pushF.setActive(false);
                        }
                }
        }

        public Sensor[] getAllSensors() {
                Sensor[] sensors = new Sensor[6];
                sensors[0] = groundSensors[0];
                sensors[1] = groundSensors[1];
                sensors[2] = ceilingSensors[0];
                sensors[3] = ceilingSensors[1];
                sensors[4] = pushSensors[0];
                sensors[5] = pushSensors[1];

                return sensors;
        }

        public void moveForGroundModeAndDirection(byte distance, Direction direction) {
                SensorConfiguration sensorConfiguration = SpriteManager
                                .getSensorConfigurationForGroundModeAndDirection(getGroundMode(), direction);
                switch (sensorConfiguration.direction()) {
                        case DOWN -> {
                                yPixel = (short) (yPixel + distance);
                        }
                        case RIGHT -> {
                                xPixel = (short) (xPixel + distance);
                        }
                        case UP -> {
                                yPixel = (short) (yPixel - distance);
                        }
                        case LEFT -> {
                                xPixel = (short) (xPixel - distance);
                        }
                }
        }

        /**
         * Mirrors the ROM follower-history write point (`Sonic_RecordPos` in S3K
         * sonic3k.asm:22119-22136): movement/collision has run, but animation,
         * touch response, and later object-side rewrites have not. Sidekick CPU
         * reads this table later in the same Process_Sprites pass.
         */
        public void recordFollowerHistoryForTick() {
                if (followerHistoryRecordedThisTick) {
                        return;
                }
                // ROM: Sonic_Pos_Record_Index wraps at 256 bytes (64 entries * 4 bytes per entry)
                if (historyPos == 63) {
                        historyPos = 0;
                } else {
                        historyPos++;
                }
                xHistory[historyPos] = getCentreX();
                yHistory[historyPos] = getCentreY();

                inputHistory[historyPos] = logicalInputState;
                jumpPressHistory[historyPos] = (byte) (logicalJumpPressState ? 1 : 0);

                byte status = 0;
                if (getDirection() == Direction.LEFT) status |= STATUS_FACING_LEFT;
                if (air) status |= STATUS_IN_AIR;
                if (rolling) status |= STATUS_ROLLING;
                if (onObject) status |= STATUS_ON_OBJECT;
                if (rollingJump) status |= STATUS_ROLLING_JUMP;
                if (pushing) status |= STATUS_PUSHING;
                if (inWater) status |= STATUS_UNDERWATER;
                if (preventTailsRespawn) status |= STATUS_PREVENT_TAILS_RESPAWN;
                statusHistory[historyPos] = status;
                followerHistoryRecordedThisTick = true;
        }

        /**
         * Completes per-frame cleanup. If a path did not reach the normal
         * Sonic_RecordPos-equivalent point, record history here as a fallback.
         */
        public void endOfTick() {
                if (deferredObjectControlRelease) {
                        objectControlled = false;
                        objectControlAllowsCpu = false;
                        objectControlSuppressesMovement = false;
                        controller.clearObjectControlledSolidContactOwner();
                        deferredObjectControlRelease = false;
                }
                suppressNextGravityStep = false;
                recordFollowerHistoryForTick();
                // Forced BK2/action press edges are frame-local. Keep them
                // through the Sonic_RecordPos-equivalent history write, then
                // clear so the low-byte Ctrl_1_Logical press cannot leak into
                // the next Stat_Record slot.
                forcedJumpPress = false;
                followerHistoryRecordedThisTick = false;
                invulnerabilityDisplayTimerTickedThisFrame = false;
        }

        public short getRenderCentreX() {
                return (short) (getCentreX() + renderXOffset);
        }

        public short getRenderCentreY() {
                return (short) (getCentreY() + renderYOffset);
        }

        public void setRenderOffsets(short xOffset, short yOffset) {
                this.renderXOffset = xOffset;
                this.renderYOffset = yOffset;
        }

        // ==================== Water Physics ====================

        /**
         * Updates water state based on player Y position relative to water level.
         *
         * @param waterLevelY Water surface Y position in world coordinates (pixels)
         */
        public void updateWaterState(int waterLevelY) {
                wasInWater = inWater;

                // ROM compares y_pos (center Y) with Water_Level_1:
                //   cmp.w y_pos(a0),d0 ; is Sonic above the water?
                //   bge.s Obj01_OutWater
                // Player is in water when center Y > water level
                int playerCenterY = getCentreY();

                // When skimming across the water surface (HCZ), the player's feet
                // are at the water level but they are NOT underwater. The skim
                // handler pins the player above the surface. Suppress water entry
                // so the speed-halving and drowning timer don't activate.
                if (waterSkimActive) {
                        inWater = false;
                        return;
                }

                inWater = playerCenterY > waterLevelY;

                // Detect transitions
                if (!wasInWater && inWater) {
                        onEnterWater();
                } else if (wasInWater && !inWater) {
                        onExitWater();
                }

                // Update drowning manager each frame while underwater
                // Skip during drowning pre-death phase to prevent re-triggering
                if (inWater && !dead && !isDrowningPreDeath() && controller.getDrowning() != null) {
                        // Bubble shield prevents drowning (s3.asm: Player_ResetAirTimer)
                        PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
                        if (capabilityRules != null && capabilityRules.elementalShieldsEnabled()
                                        && shield && shieldType == ShieldType.BUBBLE) {
                                controller.getDrowning().replenishAir();
                        } else if (fixedLevelObjectOwnsDrowningBubbleCadence()) {
                                // S3K installs Breathing_bubbles/Breathing_bubbles_P2
                                // in fixed object RAM. Their Obj_AirCountdown cadence
                                // owns Random_Number/AllocateObject timing; keep the
                                // generic controller from double-consuming RNG.
                        } else {
                                boolean shouldDrown = controller.getDrowning().update();
                                if (shouldDrown) {
                                        applyDrownDeath();
                                }
                        }
                }
        }

        /**
         * Updates the ROM Status_Underwater mirror while object_control bit 0 is
         * suppressing movement. S3K Tails' dispatcher skips Tails_Modes under
         * object_control (sonic3k.asm:26220-26248), but still falls through to
         * Tails_Water. Tails_Water sets/clears Status_Underwater and speed
         * constants before checking object_control; when object_control is set
         * and CPU routine is not 4, it returns before the velocity quarter/double
         * paths (sonic3k.asm:27416-27470).
         */
        /**
         * Whether this game's water routine suppresses the entry/exit velocity
         * change while {@code object_control} holds the character. True for S3K
         * only; S1 and S2 apply it unconditionally.
         *
         * @see PlayerMovementRules#waterVelocityChangeGatedByObjectControl()
         */
        public boolean waterVelocityChangeGatedByObjectControl() {
                PlayerMovementRules movementRules = playerMovementRulesOrNull();
                return movementRules != null
                                && movementRules.waterVelocityChangeGatedByObjectControl();
        }

        public void updateWaterStateObjectControlled(int waterLevelY) {
                wasInWater = inWater;

                if (waterSkimActive) {
                        inWater = false;
                        return;
                }

                boolean nowInWater = getCentreY() > waterLevelY;
                if (!wasInWater && nowInWater) {
                        currentWaterSystem().incrementWaterEnteredCounter();
                        clearInitOverride();
                        resetSpeedConstantsToCanonical();
                        waterPhysicsActive = true;
                        resetAirTimerForWaterTransition(true);
                } else if (wasInWater && !nowInWater) {
                        currentWaterSystem().incrementWaterEnteredCounter();
                        clearInitOverride();
                        resetSpeedConstantsToCanonical();
                        waterPhysicsActive = false;
                        resetAirTimerForWaterTransition(false);
                }
                inWater = nowInWater;
        }

        private boolean fixedLevelObjectOwnsDrowningBubbleCadence() {
                try {
                        return GameServices.module().getLevelEventProvider()
                                        .ownsFixedDrowningBubbleCadence(this);
                } catch (IllegalStateException ex) {
                        return false;
                }
        }

        /**
         * Called when player enters water.
         * Applies instantaneous velocity changes per original game logic.
         */
        protected void onEnterWater() {
                LOGGER.fine("Player entered water");
                // Increment global Water_entered_counter so objects can detect water transitions
                currentWaterSystem().incrementWaterEnteredCounter();
                // S3K: water entry resets Character_Speeds init values to canonical
                // (sonic3k.asm:22225-22227 sets absolute values, not relative to init)
                clearInitOverride();
                resetSpeedConstantsToCanonical();
                waterPhysicsActive = true;

                // Fire and Lightning shields dissipate on water entry (s3.asm:34693, 34780)
                PlayerCapabilityRules capabilityRules = playerCapabilityRulesOrNull();
                if (shield && shieldType != null
                                && capabilityRules != null && capabilityRules.elementalShieldsEnabled()) {
                        if (shieldType == ShieldType.FIRE || shieldType == ShieldType.LIGHTNING) {
                                shield = false;
                                shieldType = null;
                                if (shieldObject != null) {
                                        shieldObject.destroy();
                                        shieldObject = null;
                                }
                        }
                }

                // ROM: asr.w x_vel(a0) - halve horizontal velocity once.
                // Sonic_Water does not modify ground_vel/inertia on water entry.
                xSpeed = (short) (xSpeed >> 1);

                // ROM: asr.w y_vel(a0) twice - divide by 4 unconditionally
                // (both upward and downward velocity)
                ySpeed = (short) (ySpeed >> 2);

                // ROM (s2.asm:36050-36110): Skip splash if y_vel is 0 after quartering
                //   tst.w   y_vel(a0)
                //   beq.s   loc_F6DE         ; Skip splash if y_vel is now 0
                if (ySpeed != 0) {
                        // Play splash sound
                        currentAudioManager().playSfx(GameSound.SPLASH);

                        // Spawn splash object at water surface
                        spawnSplash();
                }

                // Reset drowning manager for new underwater session. S3K's fixed
                // Breathing_bubbles sidecar owns bubble/RNG cadence, but
                // Sonic_Water/Tails_Water still call Player_ResetAirTimer on the
                // transition itself.
                if (controller.getDrowning() != null) {
                        resetAirTimerForWaterTransition(true);
                }
        }

        /**
         * Called when player exits water.
         * Applies velocity boost per original game logic.
         */
        protected void onExitWater() {
                LOGGER.fine("Player exited water");
                // Increment global Water_entered_counter so objects can detect water transitions
                currentWaterSystem().incrementWaterEnteredCounter();
                // S3K: water exit resets Character_Speeds init values to canonical
                // (sonic3k.asm:22253-22255 sets absolute $600/$C/$80, not relative to init)
                clearInitOverride();
                resetSpeedConstantsToCanonical();
                waterPhysicsActive = false;

                // ROM does NOT modify x_vel on water exit - only top_speed/accel/decel
                // change, which affects future acceleration but not current velocity

                // ROM: cmpi.b #4,routine(a0) - skip y_vel doubling if hurt.
                // S2/S3K additionally skip asl y_vel when already moving upward
                // faster than -$400 (s2.asm:36120-36124, sonic3k.asm:22267-22270).
                PlayerMovementRules movementRules = playerMovementRulesOrNull();
                boolean shouldDoubleYSpeed = !isHurt();
                if (shouldDoubleYSpeed && movementRules != null && movementRules.waterExitBoostSkipsFastUpwardVelocity()
                                && ySpeed < -0x400) {
                        shouldDoubleYSpeed = false;
                }
                if (shouldDoubleYSpeed) {
                        // Double y velocity (both up and down)
                        ySpeed = (short) (ySpeed * 2);
                }

                // ROM (s2.asm:36103-36104): tst.w y_vel(a0) / beq.w return_1A18C
                // If y velocity is zero after doubling, skip splash entirely
                if (ySpeed == 0) {
                        // Notify drowning manager but skip splash effects
                        if (controller.getDrowning() != null) {
                                resetAirTimerForWaterTransition(false);
                        }
                        return;
                }

                // ROM: cmpi.w #-$1000,y_vel(a0) - cap upward velocity at -$1000
                //      bgt.s +
                //      move.w #-$1000,y_vel(a0)
                if (ySpeed < -0x1000) {
                        ySpeed = -0x1000;
                }

                // Play splash sound
                currentAudioManager().playSfx(GameSound.SPLASH);

                // Spawn splash object at water surface
                spawnSplash();

                // Notify drowning manager of water exit (stops drowning music, resets state)
                if (controller.getDrowning() != null) {
                        resetAirTimerForWaterTransition(false);
                }
        }

        private void resetAirTimerForWaterTransition(boolean enteringWater) {
                if (controller.getDrowning() == null) {
                        return;
                }
                if (fixedLevelObjectOwnsDrowningBubbleCadence()) {
                        controller.getDrowning().replenishAir();
                } else if (enteringWater) {
                        controller.getDrowning().reset();
                } else {
                        controller.getDrowning().onExitWater();
                }
        }

        /**
         * Spawns a splash object at the water surface.
         * The splash appears at the player's X position at the water level Y.
         */
        private void spawnSplash() {
                if (powerUpSpawner != null) {
                        powerUpSpawner.spawnSplash(this);
                }
        }

        /**
         * Returns true if player is currently underwater.
         */
        public boolean isInWater() {
                return inWater;
        }

        /**
         * Returns true if the player is currently skimming across the water surface (HCZ).
         */
        public boolean isWaterSkimActive() {
                return waterSkimActive;
        }

        /**
         * Set by HCZWaterSkimHandler when the player enters/exits the skim state.
         * When active, updateWaterState() will not trigger water entry.
         */
        public void setWaterSkimActive(boolean waterSkimActive) {
                this.waterSkimActive = waterSkimActive;
        }

        public boolean isPreventTailsRespawn() {
                return preventTailsRespawn;
        }

        public void setPreventTailsRespawn(boolean preventTailsRespawn) {
                this.preventTailsRespawn = preventTailsRespawn;
        }

        /**
         * Replenishes air by collecting a large breathable bubble.
         * Implements full ROM behavior from S1 Obj64, S2 Obj24, and S3K
         * Bubbler collection paths:
         * <ul>
         *   <li>Clears all velocity (x_vel, y_vel, inertia)</li>
         *   <li>Sets bubble-breathing animation</li>
         *   <li>Locks movement for 35 frames (0x23)</li>
         *   <li>Clears jumping, pushing, and roll-jumping flags while preserving in-air status</li>
         *   <li>Unrolls player if rolling (adjusts hitbox)</li>
         * </ul>
         */
        public void replenishAir() {
                replenishAir(false);
        }

        /**
         * S3K {@code Obj_Bubbler} preserves the rolling status bit for every
         * playable object other than {@code Obj_Sonic}, even though it restores
         * that character's standing radii.
         */
        public void replenishAirPreservingRollingStatus() {
                replenishAir(true);
        }

        private void replenishAir(boolean preserveRollingStatus) {
                // ROM: clr.w x_vel(a1) / clr.w y_vel(a1) / clr.w inertia(a1)
                xSpeed = 0;
                ySpeed = 0;
                gSpeed = 0;

                // ROM: move.w #$23,move_lock(a1) (35 frames)
                moveLockTimer = 0x23;

                // ROM clears jumping, not Status_InAir (S1 Obj64, S2 Obj24, S3K Bubbler).
                jumping = false;

                // ROM: bclr #status.player.pushing,status(a1)
                pushing = false;

                // ROM: bclr #status.player.rolljumping,status(a1)
                rollingJump = false;

                // ROM: btst #status.player.rolling,status(a1) / beq.w loc_1FBB8
                if (rolling) {
                        // ROM restores standing radii, then subtracts the radius delta from y_pos.
                        setRolling(false);
                        setY((short) (getY() - getRollHeightAdjustment()));
                        if (preserveRollingStatus) {
                                setRollingFlagPreserveRadii(true);
                        }
                }

                // ROM: move.b #AniIDSonAni_Bubble,anim(a1). Apply this after the
                // rolling-status branch because restoring that bit selects the
                // roll animation as a normal engine side effect.
                if (bubbleAnimId >= 0) {
                        setAnimationId(bubbleAnimId);
                }

                // Delegate to drowning manager for air timer reset and music handling
                if (controller.getDrowning() != null) {
                        controller.getDrowning().replenishAir();
                }
        }

        /**
         * Sets water state directly (for loading checkpoints, testing, etc.).
         */
        public void setInWater(boolean inWater) {
                this.inWater = inWater;
                this.wasInWater = inWater;
                this.waterPhysicsActive = inWater;
        }

        /**
         * Clears only the underwater status bit for ROM routines that write
         * {@code status(a0)} directly. S3K {@code sub_13ECA} writes
         * {@code Status_InAir} (sonic3k.asm:26804-26808), so the next
         * {@code Tails_Water} call sees Status_Underwater already clear and
         * does not restore the speed constants.
         */
        public void clearUnderwaterStatusPreserveWaterPhysics() {
                this.inWater = false;
                this.wasInWater = false;
        }

        // ==================== Physics Constant Getters with Modifiers
        // ====================
        // These apply underwater and speed shoes modifiers dynamically

        /**
         * Returns effective run acceleration, accounting for underwater and speed
         * shoes.
         * Underwater: halved
         * Speed shoes: doubled
         */
        public short getEffectiveRunAccel() {
                return PlayablePhysicsValueResolver.runAcceleration(
                                runAccel, waterPhysicsActive, speedShoes);
        }

        /**
         * Returns effective run deceleration, accounting for modifiers.
         */
        public short getEffectiveRunDecel() {
                return PlayablePhysicsValueResolver.runDeceleration(runDecel, waterPhysicsActive);
        }

        /**
         * Returns effective friction, accounting for modifiers.
         */
        public short getEffectiveFriction() {
                return PlayablePhysicsValueResolver.friction(
                                friction, waterPhysicsActive, speedShoes);
        }

        /**
         * Returns effective max speed, accounting for modifiers.
         */
        public short getEffectiveMax() {
                return PlayablePhysicsValueResolver.maximumSpeed(
                                max, waterPhysicsActive, speedShoes);
        }

        /**
         * Returns effective jump force, accounting for underwater modifier.
         * ROM s2.asm line 37019: Underwater = 0x380 (896), Normal = 0x680 (1664)
         */
        public short getEffectiveJump() {
                return PlayablePhysicsValueResolver.jumpForce(jump, inWater);
        }

        /**
         * Returns effective gravity value.
         * Normal: 0x38 (56 subpixels)
         * Underwater: 0x10 (16 subpixels)
         */
        public short getEffectiveGravity() {
                return PlayablePhysicsValueResolver.gravity(inWater);
        }

        /**
         * Returns effective air drag threshold.
         * Normal: -0x400
         * Underwater: -0x200
         */
        public short getEffectiveAirDragThreshold() {
                return PlayablePhysicsValueResolver.airDragThreshold(inWater);
        }
}
