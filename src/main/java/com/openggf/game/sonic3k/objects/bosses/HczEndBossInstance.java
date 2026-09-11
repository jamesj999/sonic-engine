package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.events.S3kHczEventWriteSupport;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
// HczEndBossEggCapsuleInstance is in the same package (bosses)
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.S3kBossExplosionController;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;

import java.util.List;
import java.util.logging.Logger;

/**
 * Hydrocity Zone Act 2 end boss (object 0x9A).
 *
 * <p>ROM: Obj_HCZEndBoss (sonic3k.asm). A propeller-driven machine that
 * descends from above, patrols horizontally with spinning blades, and has
 * a water column suction attack. Defeated after 8 hits.
 *
 * <p>This implementation provides the state machine, camera lock mechanism,
 * movement routines, collision, rendering, palette flash, timer/callback
 * utilities, and custom defeat/flee sequence.
 *
 * <p>Child objects (turbine, blades, visual child) are implemented separately
 * and follow the boss through explicit parent links.
 */
public class HczEndBossInstance extends AbstractBossInstance
        implements SpawnConstructionContextRewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HczEndBossInstance.class.getName());

    // =========================================================================
    // State machine routines (ROM: routine counter values, stride 2)
    // =========================================================================
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_DESCEND = 2;
    private static final int ROUTINE_HOVER_WAIT = 4;
    private static final int ROUTINE_ST_DESCEND = 6;
    private static final int ROUTINE_ST_RISE = 8;
    private static final int ROUTINE_PRE_ATTACK = 10;
    private static final int ROUTINE_ATTACK = 12;
    private static final int ROUTINE_DEFEATED = 14;
    private static final int ROUTINE_FLEE = 16;
    private static final int ROUTINE_CAPSULE_WAIT = 18;
    private static final int ROUTINE_DONE = 20;

    // =========================================================================
    // Boss attributes
    // =========================================================================
    private static final int HIT_COUNT = 8;
    private static final int COLLISION_SIZE = 6;
    private static final int INVULN_TIME = 0x20;
    private static final int BOSS_MUSIC_WAIT = 120; // frames before boss music plays

    // =========================================================================
    // Arena bounds — Sonic/Tails (ROM: word_6AE96)
    // =========================================================================
    private static final int ST_TRIGGER_MIN_X = 0x3F00;
    private static final int ST_TRIGGER_MAX_X = 0x4100;
    private static final int ST_TRIGGER_MIN_Y = 0x0438;
    private static final int ST_TRIGGER_MAX_Y = 0x0838;
    private static final int ST_LOCK_Y_TOP = 0x0738;
    private static final int ST_LOCK_Y_BOTTOM = 0x0738;
    private static final int ST_LOCK_X_LEFT = 0x4000;
    private static final int ST_LOCK_X_RIGHT = 0x4050;

    // =========================================================================
    // Arena bounds — Knuckles (ROM: word_6AEA6)
    // =========================================================================
    private static final int KN_TRIGGER_MIN_X = 0x44E0;
    private static final int KN_TRIGGER_MAX_X = 0x4640;
    private static final int KN_TRIGGER_MIN_Y = 0x0000;
    private static final int KN_TRIGGER_MAX_Y = 0x03B8;
    private static final int KN_LOCK_Y_TOP = 0x02B8;
    private static final int KN_LOCK_Y_BOTTOM = 0x02B8;
    private static final int KN_LOCK_X_LEFT = 0x4540;
    private static final int KN_LOCK_X_RIGHT = 0x4590;

    // =========================================================================
    // Palette flash constants (ROM: word_6BC30 / word_6BC36)
    // =========================================================================
    /** Palette indices in line 1 to flash during invulnerability. */
    private static final int[] FLASH_INDICES = {0x0A, 0x0B, 0x0E};
    /** Normal colors for the flashed indices: 0x006, 0x020, 0x624. */
    private static final int[] FLASH_NORMAL = {0x006, 0x020, 0x624};
    /** Flash color for all indices: 0xEEE (white). */
    private static final int FLASH_WHITE = 0xEEE;

    // =========================================================================
    // Swing/movement constants (ROM: loc_6AF80, loc_6B064)
    // =========================================================================
    /** Per-frame swing acceleration (ROM: $40(a0)). */
    private static final int SWING_ACCEL = 0x10;
    /** Peak swing velocity magnitude (ROM: $3E(a0)). */
    private static final int SWING_AMPLITUDE = 0xC0;

    // =========================================================================
    // Child offsets (ROM: ChildObjDat_6BD8A)
    // =========================================================================
    // Robotnik ship cockpit offset is now managed by HczEndBossRobotnikShip child
    /** Visual child (propeller housing) offset from boss center. */
    private static final int VISUAL_OFFSET_X = 0;
    private static final int VISUAL_OFFSET_Y = 0x1C;
    /** Turbine offset from boss center. */
    private static final int TURBINE_OFFSET_X = 0;
    private static final int TURBINE_OFFSET_Y = 0x24;

    // =========================================================================
    // Communication flags (replaces ROM's $38 bit field)
    // =========================================================================
    private boolean propellerActive;
    private boolean bladeFireSignal;
    private boolean defeatSignal;

    // =========================================================================
    // Instance state
    // =========================================================================
    private int waitTimer = -1;
    private WaitCallback waitCallback = WaitCallback.NONE;
    /** Retains loc_6B03A for the next consolidated setup dispatch. */
    private boolean pendingAttackSetupDispatch;
    private boolean pendingDefeatDispatch;
    private boolean customFlashDirty;
    private S3kSharedBossCameraGate cameraGate;

    // Movement state (Task 6)
    private int swingVelocity;
    private boolean swingDown;
    private int hoverCentreY;
    private int savedPatrolVel = -0x100;
    private int directionCounter;
    private int attackPassCounter;
    private boolean facingRight;

    /** Character-resolved lock targets for the current fight. */
    private int targetLockYTop;
    private int targetLockYBottom;
    private int targetLockXLeft;
    private int targetLockXRight;
    private S3kBossExplosionController defeatExplosionController;

    private enum WaitCallback {
        NONE,
        DESCENT_COMPLETE,
        PRE_ATTACK_WAIT_COMPLETE,
        ST_DESCENT_COMPLETE,
        ST_RISE_COMPLETE,
        BEGIN_ATTACK_PHASE,
        ATTACK_PASS_COMPLETE,
        BEGIN_FLEE_SEQUENCE
    }

    // =========================================================================
    // Constructor
    // =========================================================================

    public HczEndBossInstance(ObjectSpawn spawn) {
        super(spawn, "HCZEndBoss");
    }

    // =========================================================================
    // AbstractBossInstance contract
    // =========================================================================

    @Override
    protected void initializeBossState() {
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        state.renderFlags = 0;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        pendingAttackSetupDispatch = false;
        customFlashDirty = false;
        if (cameraGate == null) {
            cameraGate = new S3kSharedBossCameraGate();
        } else {
            cameraGate.reset();
        }
        propellerActive = false;
        bladeFireSignal = false;
        defeatSignal = false;
        defeatExplosionController = null;
        swingVelocity = 0;
        swingDown = false;
        hoverCentreY = 0;
        savedPatrolVel = -0x100;
        directionCounter = 0;
        attackPassCounter = 0;
        facingRight = false;

        // Resolve character-specific lock targets
        boolean isKnuckles = (getPlayerCharacter() == PlayerCharacter.KNUCKLES);
        targetLockYTop = isKnuckles ? KN_LOCK_Y_TOP : ST_LOCK_Y_TOP;
        targetLockYBottom = isKnuckles ? KN_LOCK_Y_BOTTOM : ST_LOCK_Y_BOTTOM;
        targetLockXLeft = isKnuckles ? KN_LOCK_X_LEFT : ST_LOCK_X_LEFT;
        targetLockXRight = isKnuckles ? KN_LOCK_X_RIGHT : ST_LOCK_X_RIGHT;
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        customFlashDirty = true;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false; // Custom defeat logic (Task 7)
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULN_TIME;
    }

    @Override
    protected int getPaletteLineForFlash() {
        return -1; // Custom flash, not the generic base class flash
    }

    @Override
    public int getCollisionFlags() {
        // ROM: collision_flags = $C0 | 6 when active, 0 when invulnerable/defeated
        if (state.routine < ROUTINE_DESCEND || state.invulnerable || state.defeated) {
            return 0;
        }
        return 0xC0 | COLLISION_SIZE;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // loc_6AF0C runs the movement/routine handler before
        // Draw_And_Touch_Sprite publishes this boss to Collision_response_list.
        // The next player pass therefore observes the post-movement coordinate,
        // not this object's pre-update snapshot.
        return true;
    }

    @Override
    public boolean isPersistent() {
        // ROUTINE_FLEE runs off-screen for 0x77 frames before loc_6B0E8 clears
        // Boss_flag, widens the camera, and spawns Obj_EggCapsule.
        return state.routine == ROUTINE_FLEE;
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        // Obj_HCZEndBoss calls Check_CameraInRange only while its entry-point
        // routine is still the initializer. An early ObjPosLoad must therefore
        // use Delete_Sprite_If_Not_In_Range's fixed native window instead of
        // lingering in a low SST slot until the widened engine camera arrives.
        return state.routine == ROUTINE_INIT;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        int bossRounded = getX() & 0xFF80;
        int cameraBack = (cameraX - 0x80) & 0xFF80;
        return ((bossRounded - cameraBack) & 0xFFFF) > 0x280;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }

    // =========================================================================
    // Custom hit processing (overrides base class)
    // =========================================================================

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (getCollisionFlags() == 0 || state.invulnerable || state.defeated) {
            return;
        }

        state.hitCount--;
        state.invulnerabilityTimer = INVULN_TIME;
        state.invulnerable = true;
        paletteFlasher.startFlash();
        services().playSfx(Sonic3kSfx.BOSS_HIT.id);
        customFlashDirty = true;
        onHitTaken(state.hitCount);

        if (state.hitCount <= 0) {
            state.hitCount = 0;
            state.defeated = true;
            services().gameState().addScore(1000);
            onDefeatStarted();
            // The player touch pass precedes this boss slot. ROM loc_6BC1C
            // installs Wait_FadeToLevelMusic/BossDefeated at the tail of the
            // already-running loc_6AF0C dispatch, so its $3F wait cannot tick
            // until the following object pass.
            pendingDefeatDispatch = true;
        }
    }

    // =========================================================================
    // Main update loop
    // =========================================================================

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        if (pendingDefeatDispatch) {
            pendingDefeatDispatch = false;
            updateCustomFlash();
            updateDynamicSpawn(state.x, state.y);
            return;
        }
        if (pendingAttackSetupDispatch) {
            pendingAttackSetupDispatch = false;
            beginAttackPhase();
            updateCustomFlash();
            updateDynamicSpawn(state.x, state.y);
            return;
        }

        switch (state.routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_DESCEND -> updateMoveAndWait();
            case ROUTINE_HOVER_WAIT -> updateSwingAndWait();
            case ROUTINE_ST_DESCEND -> updateMoveAndWait();
            case ROUTINE_ST_RISE -> updateMoveAndWait();
            case ROUTINE_PRE_ATTACK -> updateSwingAndWait();
            case ROUTINE_ATTACK -> updateAttackPatrol();
            case ROUTINE_DEFEATED -> updateDefeated();
            case ROUTINE_FLEE -> updateFlee();
            default -> { }
        }

        updateCustomFlash();
        updateDynamicSpawn(state.x, state.y);
    }

    // =========================================================================
    // Routine: INIT (0)
    // =========================================================================

    /**
     * ROM: sub_85D6A — Check if camera is in the trigger zone for the current
     * character. If not, delete this object (same pattern as HCZ miniboss).
     * If yes, set Boss_flag=1 on the HCZ events, store current camera bounds,
     * fade music, and begin camera lock sequence.
     */
    private void updateInit() {
        if (!isCameraInRange()) {
            return; // Not yet in range; stay in INIT
        }

        // A widened viewport can materialize this placement before the native
        // Check_CameraInRange gate. Re-run the first-free allocation at the
        // gate so the boss owns the slot the ROM load pass observes.
        services().objectManager().reallocateToFirstFreeDynamicSlot(this);

        // Set Boss_flag on HCZ events
        setBossFlag(true);

        // Fade out current music
        services().fadeOutMusic();

        // Load boss palette to palette line 1
        loadBossPalette();

        // Begin camera lock — Y first, then X
        state.routine = ROUTINE_DESCEND;
        state.yVel = 0;
        state.xVel = 0;

        cameraGate.begin(
                services().camera(),
                new S3kSharedBossCameraGate.LockBounds(
                        targetLockYTop,
                        targetLockYBottom,
                        targetLockXLeft,
                        targetLockXRight),
                BOSS_MUSIC_WAIT);

        LOG.info("HCZ End Boss: triggered, starting camera lock");
    }

    // =========================================================================
    // Camera lock mechanism (ROM: sub_85D6A + loc_85CA4)
    // =========================================================================

    /**
     * ROM: loc_85CA4 — Follow-camera-then-snap lock algorithm.
     *
     * <p>Each axis follows the camera position each frame, then snaps both
     * min and max when the camera reaches the target zone. Y and X run
     * in parallel. Completion requires music played + Y locked + X locked.
     *
     * <p>Approach direction, music wait, and axis completion are handled by
     * {@link S3kSharedBossCameraGate} using the same bits as the ROM helper.
     */
    private void updateCameraLock() {
        if (cameraGate.isComplete()) {
            return;
        }
        boolean complete = cameraGate.update(services().camera(), () -> {
            services().playMusic(Sonic3kMusic.BOSS.id);
            services().gameState().setCurrentBossId(Sonic3kObjectIds.HCZ_END_BOSS);
        });
        if (complete) {
            onCameraLockComplete();
        }
    }

    /**
     * Called when camera lock is fully established.
     * Spawns children and begins descent into the arena.
     * ROM: sets y_vel = 0x80 (descend) with wait timer 0xEF (239 frames).
     */
    private void onCameraLockComplete() {
        LOG.info("HCZ End Boss: camera lock complete, spawning children");
        spawnChildren();
        // Begin descent into arena
        state.routine = ROUTINE_DESCEND;
        state.yVel = 0x80;
        setWait(0xEF, WaitCallback.DESCENT_COMPLETE);
    }

    // =========================================================================
    // Movement routines (ROM: loc_6AF80–loc_6B0AE)
    // =========================================================================

    /**
     * Move + Wait pattern: applies velocity, ticks wait timer.
     * ROM: Used by DESCEND, ST_DESCEND, ST_RISE routines.
     */
    private void updateMoveAndWait() {
        updateCameraLock();
        state.applyVelocity();
        tickWait();
    }

    /**
     * Swing + Move + Wait pattern: hover oscillation with timer.
     * ROM: Used by HOVER_WAIT, PRE_ATTACK routines.
     * Applies Swing_UpAndDown each frame then moves + ticks wait.
     */
    private void updateSwingAndWait() {
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCEL, swingVelocity, SWING_AMPLITUDE, swingDown);
        swingVelocity = result.velocity();
        swingDown = result.directionDown();
        state.yVel = swingVelocity;

        state.applyVelocity();
        tickWait();
    }

    /**
     * ROM: loc_6AF80 — After initial descent, transition to hover phase.
     * Sets routine to HOVER_WAIT, saves hover center Y, initializes swing
     * parameters, and starts a 0x3F-frame wait before pre-attack.
     * ROM: 0x9F is the initial direction counter for the first attack pass,
     * NOT a wait timer. Only a single 0x3F-frame wait is used here.
     */
    private void onDescentComplete() {
        state.routine = ROUTINE_HOVER_WAIT;
        hoverCentreY = state.y;
        swingVelocity = SWING_AMPLITUDE;
        swingDown = false;
        state.yVel = 0;
        directionCounter = 0x9F; // initial direction counter (ROM: loc_6AF80)
        setWait(0x3F, WaitCallback.PRE_ATTACK_WAIT_COMPLETE);
    }

    /**
     * ROM: loc_6AFC8 — Propeller spin-up, then branch by character.
     * Sonic/Tails: slow descent then rise.
     * Knuckles: skip straight to pre-attack wait.
     */
    private void onPreAttackWaitComplete() {
        propellerActive = true;

        if (getPlayerCharacter() != PlayerCharacter.KNUCKLES) {
            // Sonic/Tails path
            state.routine = ROUTINE_ST_DESCEND;
            state.yVel = 0x80;
            setWait(0x9F, WaitCallback.ST_DESCENT_COMPLETE);
        } else {
            // Knuckles path — skip intermediate descent/rise
            state.routine = ROUTINE_PRE_ATTACK;
            setWait(0x1FF, WaitCallback.BEGIN_ATTACK_PHASE);
        }
    }

    /**
     * ROM: loc_6B008 — Sonic/Tails: after slow descent, begin rising.
     */
    private void onStDescentComplete() {
        state.yVel = -0x100;
        state.routine = ROUTINE_ST_RISE;
        setWait(0x4F, WaitCallback.ST_RISE_COMPLETE);
    }

    /**
     * ROM: loc_6B01E — Sonic/Tails: after rise, snap back to hover center
     * and wait before attacking.
     */
    private void onStRiseComplete() {
        state.routine = ROUTINE_PRE_ATTACK;
        state.y = hoverCentreY;
        state.yFixed = hoverCentreY << 16;
        state.yVel = 0;
        setWait(0xFF, WaitCallback.BEGIN_ATTACK_PHASE);
    }

    /**
     * ROM: loc_6B03A — Begin the attack patrol phase.
     * Clears propeller, sets horizontal velocity from savedPatrolVel,
     * initializes direction counter and attack pass counter.
     * ROM: initial direction counter is 0x9F (loc_6AF80); 0x13F is only set
     * after the first reversal in updateAttackPatrol().
     */
    private void beginAttackPhase() {
        state.routine = ROUTINE_ATTACK;
        propellerActive = false;
        state.xVel = savedPatrolVel;
        facingRight = (state.xVel > 0);
        directionCounter = 0x9F;
        attackPassCounter = 8;
        setWait(0xBF, WaitCallback.ATTACK_PASS_COMPLETE);
        // Re-init swing
        swingVelocity = SWING_AMPLITUDE;
        swingDown = false;
    }

    /**
     * ROM: loc_6B064 — Attack patrol: every frame apply swing oscillation,
     * move, tick wait, and check for direction reversal.
     */
    private void updateAttackPatrol() {
        // Swing oscillation
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCEL, swingVelocity, SWING_AMPLITUDE, swingDown);
        swingVelocity = result.velocity();
        swingDown = result.directionDown();
        state.yVel = swingVelocity;

        // Apply velocity (both X and Y)
        state.applyVelocity();

        // Tick wait timer
        tickWait();

        // Direction reversal counter
        directionCounter--;
        if (directionCounter < 0) {
            state.xVel = -state.xVel;
            facingRight = !facingRight;
            directionCounter = 0x13F;
        }
    }

    /**
     * ROM: loc_6B08E — One attack pass complete.
     * Decrements pass counter; if passes remain, fires blade and waits 0x5F
     * frames before re-entering this callback. If all passes done, saves
     * patrol velocity and jumps DIRECTLY to onPreAttackWaitComplete (ROM:
     * bra.w loc_6AFC8 — same-frame jump, no wait).
     *
     * <p>ROM detail: $34 callback is always loc_6B08E (this method). After
     * blade fire, $2E is set to 0x5F and execution returns — the main loop
     * continues patrolling with the same callback. No intermediate
     * continueAttackPatrol step exists in the ROM.
     */
    private void onAttackPassComplete() {
        attackPassCounter--;
        if (attackPassCounter >= 0) {
            // More passes remaining — fire blade, wait 0x5F, same callback
            bladeFireSignal = true;
            setWait(0x5F, WaitCallback.ATTACK_PASS_COMPLETE);
        } else {
            // All passes done — save velocity, jump directly to phase decision
            // ROM: bra.w loc_6AFC8 (immediate, no wait)
            savedPatrolVel = state.xVel;
            state.xVel = 0;
            onPreAttackWaitComplete();
        }
    }

    // =========================================================================
    // Defeat & flee constants (ROM: loc_6B0B0–loc_6B154)
    // =========================================================================
    private static final int DEFEAT_WAIT = 0x3F;       // 63 frames of explosions
    private static final int FADE_MUSIC_WAIT = 0x77;
    private static final int FLEE_X_VEL = 0x200;
    private static final int FLEE_Y_VEL = -0x200;

    /** Egg capsule spawn positions — Sonic/Tails (ROM: loc_6B0E8). */
    private static final int ST_CAPSULE_X = 0x4250;
    private static final int ST_CAPSULE_Y = 0x7E0;
    /** Egg capsule spawn positions — Knuckles. */
    private static final int K_CAPSULE_X = 0x4760;
    private static final int K_CAPSULE_Y = 0x360;

    /** Camera expansion after flee complete (ROM: loc_6B0E8). */
    private static final int POST_FLEE_CAMERA_EXPAND = 0x180;

    private int fleeTimer;

    // =========================================================================
    // Defeat routines (ROM: BossDefeated → loc_6B154)
    // =========================================================================

    @Override
    protected void onDefeatStarted() {
        state.routine = ROUTINE_DEFEATED;
        state.xVel = 0;
        state.yVel = 0;
        waitTimer = -1;
        waitCallback = WaitCallback.NONE;
        propellerActive = false;
        defeatSignal = true;
        state.invulnerable = false;
        state.invulnerabilityTimer = 0;
        loadBossPalette();
        defeatExplosionController = new S3kBossExplosionController(state.x, state.y, 0);
        services().fadeOutMusic();
        services().gameState().setCurrentBossId(0);
        // ROM loc_6BC1C: jmp (BossDefeated_StopTimer).l (sonic3k.asm:142098).
        stopLevelTimerOnBossDefeat();
        // Wait DEFEAT_WAIT frames for explosions, then begin flee
        setWait(DEFEAT_WAIT, WaitCallback.BEGIN_FLEE_SEQUENCE);
    }

    /**
     * ROM: post-BossDefeated_StopTimer — tick explosion controller each frame,
     * spawn visual explosion children, and tick the defeat wait timer.
     */
    private void updateDefeated() {
        if (defeatExplosionController != null) {
            defeatExplosionController.tick();
            for (var pending : defeatExplosionController.drainPendingExplosions()) {
                if (pending.playSfx()) {
                    services().playSfx(Sonic3kSfx.EXPLODE.id);
                }
                spawnChild(() -> new S3kBossExplosionChild(pending.x(), pending.y()));
            }
        }
        tickWait();
    }

    /**
     * ROM: loc_6B0B0 — Begin the flee sequence after defeat explosions.
     * Boss flies up and to one side with a fade music wait timer.
     */
    private void beginFleeSequence() {
        state.routine = ROUTINE_FLEE;
        // Flee away from the direction the boss is facing
        state.xVel = facingRight ? -FLEE_X_VEL : FLEE_X_VEL;
        state.yVel = FLEE_Y_VEL;
        fleeTimer = FADE_MUSIC_WAIT;
        LOG.fine("HCZ End Boss: flee sequence started");
    }

    /**
     * ROM: loc_6B0CC — Each frame during flee: apply gravity, move, flicker,
     * decrement timer. When timer expires, call onFleeComplete().
     */
    private void updateFlee() {
        // loc_6B0CC calls MoveSprite, not MoveSpriteAndFall: flee velocity is
        // constant for the whole Obj_Wait countdown.
        state.applyVelocity();
        fleeTimer--;
        if (fleeTimer < 0) {
            onFleeComplete();
        }
    }

    /**
     * ROM: loc_6B0E8 — Flee complete. Hide boss, clear boss flag, expand camera,
     * resume zone music, and spawn the egg capsule.
     *
     * <p>The boss self-destructs here. The egg capsule takes responsibility for
     * polling the results-complete flag and spawning the geyser cutscene.
     * This avoids the boss being culled by the object manager's out-of-range
     * check (it has flown far off-screen during the flee) before it can poll
     * the flag.
     */
    private void onFleeComplete() {
        // Clear boss flag
        setBossFlag(false);

        // Clear boss ID
        services().gameState().setCurrentBossId(0);

        // loc_6B0E8 writes Camera_stored_max_X_pos and allocates
        // Child6_IncLevX; live Camera_max_X_pos remains locked until the
        // independent helper's accelerating $4000 accumulator advances it.
        int cameraTarget = targetLockXLeft + POST_FLEE_CAMERA_EXPAND;
        spawnChild(() -> new HczEndBossGradualMaxXExtender(
                state.x, state.y, cameraTarget));

        // Resume zone music
        services().playMusic(Sonic3kMusic.HCZ2.id);

        // Spawn Egg Capsule at character-specific position.
        // The capsule handles the geyser spawn after results complete.
        boolean isKnuckles = (getPlayerCharacter() == PlayerCharacter.KNUCKLES);
        int capsuleX = isKnuckles ? K_CAPSULE_X : ST_CAPSULE_X;
        int capsuleY = isKnuckles ? K_CAPSULE_Y : ST_CAPSULE_Y;
        spawnChild(() -> new HczEndBossEggCapsuleInstance(capsuleX, capsuleY));
        LOG.fine("HCZ End Boss: flee complete, ground capsule spawned at (" + capsuleX + ", " + capsuleY + ")");

        // Boss is done — capsule + geyser handle the rest
        setDestroyed(true);
    }

    // =========================================================================
    // Child spawning
    // =========================================================================

    /**
     * Spawns all child objects for the boss.
     * ROM: ChildObjDat_6BD8A spawns Robotnik ship, turbine, and blade children.
     *
     * <p>The water column visual child is owned by the turbine and spawned
     * lazily when the turbine enters its active state.
     */
    private void spawnChildren() {
        // Robotnik ship cockpit at offset (0, 0x0C) — Obj_RobotnikShip2 subtype 5
        spawnChild(() -> new HczEndBossRobotnikShip(this));
        // Task 4: Blade children — ROM: ChildObjDat_6BD8A blade entries
        // Subtype 0 (bottom, fires): xOffset=0x23, yOffset=0x12
        // Subtype 2 (middle):        xOffset=0x1B, yOffset=0x0A
        // Subtype 4 (top):           xOffset=0x13, yOffset=0x0A
        spawnChild(() -> new HczEndBossBlade(this, 0, 0x23, 0x12));
        spawnChild(() -> new HczEndBossBlade(this, 2, 0x1B, 0x0A));
        spawnChild(() -> new HczEndBossBlade(this, 4, 0x13, 0x0A));
        // ChildObjDat_6BD8A lists the three blades, the lower housing, and then
        // the propeller turbine, so AllocateObjectAfterCurrent assigns slots in
        // that exact order.
        spawnChild(() -> new HczEndBossLowerHousing(this));
        spawnChild(() -> new HczEndBossTurbine(this, TURBINE_OFFSET_X, TURBINE_OFFSET_Y));
        // Task 5: Water column is spawned lazily by HczEndBossTurbine when it enters ACTIVE
        LOG.fine("HCZ End Boss: ship + turbine + 3 blades spawned; water column spawned by turbine on activation");
    }

    // =========================================================================
    // Timer/callback utilities (matches HCZ miniboss pattern)
    // =========================================================================

    private void tickWait() {
        if (waitTimer < 0) {
            return;
        }
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        waitTimer = -1;
        runWaitCallback(callback);
    }

    private void setWait(int frames, WaitCallback callback) {
        waitTimer = frames;
        waitCallback = callback;
    }

    private void runWaitCallback(WaitCallback callback) {
        switch (callback) {
            case DESCENT_COMPLETE -> onDescentComplete();
            case PRE_ATTACK_WAIT_COMPLETE -> onPreAttackWaitComplete();
            case ST_DESCENT_COMPLETE -> onStDescentComplete();
            case ST_RISE_COMPLETE -> onStRiseComplete();
            case BEGIN_ATTACK_PHASE -> pendingAttackSetupDispatch = true;
            case ATTACK_PASS_COMPLETE -> onAttackPassComplete();
            case BEGIN_FLEE_SEQUENCE -> beginFleeSequence();
            case NONE -> {
            }
        }
    }

    // =========================================================================
    // Custom palette flash (ROM: word_6BC30 / word_6BC36)
    // =========================================================================

    /**
     * Updates boss palette flash during invulnerability.
     * ROM: Alternates palette indices 0x0A, 0x0B, 0x0E in line 1 between
     * normal colors and white (0xEEE) each frame based on invulnerabilityTimer bit 0.
     */
    private void updateCustomFlash() {
        if (state.defeated) {
            return;
        }
        if (!state.invulnerable) {
            if (customFlashDirty) {
                loadBossPalette();
                customFlashDirty = false;
            }
            return;
        }

        var level = services().currentLevel();
        if (level == null || level.getPaletteCount() <= 1) {
            return;
        }

        boolean useWhite = (state.invulnerabilityTimer & 1) == 0;
        int[] colors = new int[FLASH_INDICES.length];
        for (int i = 0; i < FLASH_INDICES.length; i++) {
            colors[i] = useWhite ? FLASH_WHITE : FLASH_NORMAL[i];
        }
        S3kPaletteWriteSupport.applyColors(
                services().paletteOwnershipRegistryOrNull(),
                level,
                services().graphicsManager(),
                S3kPaletteOwners.HCZ_END_BOSS,
                S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                1,
                FLASH_INDICES,
                colors);
        customFlashDirty = true;
    }

    /**
     * Loads the boss palette from ROM into palette line 1.
     * ROM: PalLoad Pal_HCZEndBoss -> Normal_palette_line_2
     */
    private void loadBossPalette() {
        try {
            byte[] line = services().rom().readBytes(Sonic3kConstants.PAL_HCZ_END_BOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    services().currentLevel(),
                    services().graphicsManager(),
                    S3kPaletteOwners.HCZ_END_BOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    1,
                    line);
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossInstance.loadBossPalette: " + e.getMessage());
        }
    }

    // =========================================================================
    // Camera range check
    // =========================================================================

    /**
     * ROM: Checks if camera position is within the trigger zone for the
     * current character (Sonic/Tails or Knuckles).
     */
    private boolean isCameraInRange() {
        var camera = services().camera();
        int cameraX = camera.getX();
        int cameraY = camera.getY();

        boolean isKnuckles = (getPlayerCharacter() == PlayerCharacter.KNUCKLES);
        int minX = isKnuckles ? KN_TRIGGER_MIN_X : ST_TRIGGER_MIN_X;
        int maxX = isKnuckles ? KN_TRIGGER_MAX_X : ST_TRIGGER_MAX_X;
        int minY = isKnuckles ? KN_TRIGGER_MIN_Y : ST_TRIGGER_MIN_Y;
        int maxY = isKnuckles ? KN_TRIGGER_MAX_Y : ST_TRIGGER_MAX_Y;

        return cameraX >= minX && cameraX <= maxX
                && cameraY >= minY && cameraY <= maxY;
    }

    // =========================================================================
    // Boss flag integration with HCZ events
    // =========================================================================

    /**
     * Sets the Boss_flag on the HCZ events handler.
     * ROM: Boss_flag gates FG events during boss fights.
     */
    private void setBossFlag(boolean value) {
        try {
            S3kHczEventWriteSupport.setBossFlag(services(), value);
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossInstance.setBossFlag: " + e.getMessage());
        }
    }

    // =========================================================================
    // Player character detection (follows AizEndBossInstance pattern)
    // =========================================================================

    /**
     * Resolves the current player character from the level event manager.
     * Falls back to SONIC_AND_TAILS if unavailable.
     */
    PlayerCharacter getPlayerCharacter() {
        return S3kRuntimeStates.resolvePlayerCharacter(
                services().zoneRuntimeRegistry(),
                services().configuration());
    }

    // =========================================================================
    // Communication flag accessors (for child objects)
    // =========================================================================

    /** Whether the propeller turbine should be active (spinning up). */
    public boolean isPropellerActive() {
        return propellerActive;
    }

    public void setPropellerActive(boolean active) {
        this.propellerActive = active;
    }

    /** Whether the bottom blade should fire (detach and attack). */
    public boolean isBladeFireSignal() {
        return bladeFireSignal;
    }

    public void setBladeFireSignal(boolean signal) {
        this.bladeFireSignal = signal;
    }

    /** Called by blade child (index 0) after it has acknowledged the fire signal. */
    public void clearBladeFireSignal() {
        this.bladeFireSignal = false;
    }

    /**
     * Whether the boss is currently facing right.
     * ROM: tracks direction based on patrol velocity sign.
     */
    public boolean isFacingRight() {
        return facingRight;
    }

    /** Whether the boss is defeated (children should clean up). */
    public boolean isDefeatSignal() {
        return defeatSignal;
    }

    /**
     * Public wrapper around the protected {@code spawnChild} for use by child objects
     * that need to spawn their own grandchildren (e.g., the turbine spawning the water column).
     *
     * @param factory supplier that constructs the child object
     * @param <T>     the child type
     * @return the constructed object, already added to the object manager
     */
    public <T extends com.openggf.level.objects.AbstractObjectInstance> T spawnDynamicChild(
            java.util.function.Supplier<T> factory) {
        return spawnChild(factory);
    }

    // =========================================================================
    // Position helpers
    // =========================================================================

    private void setBossPosition(int x, int y) {
        state.x = x;
        state.y = y;
        state.xFixed = x << 16;
        state.yFixed = y << 16;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (state.routine < ROUTINE_DESCEND && !state.defeated) {
            return; // Not yet visible
        }

        // During flee: flicker — only render on odd frames
        if (state.routine == ROUTINE_FLEE) {
            if ((fleeTimer & 1) == 0) {
                return;
            }
        }

        // After flee complete: don't render at all (boss has left the arena)
        if (state.routine >= ROUTINE_CAPSULE_WAIT) {
            return;
        }

        // Body frame 0 — hFlip reflects boss facing direction (ROM: render_flags bit 0)
        PatternSpriteRenderer bossRenderer = getRenderer(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        if (bossRenderer != null) {
            bossRenderer.drawFrameIndex(0, state.x, state.y, facingRight, false);

            // Visual child (propeller housing) at offset (0, 0x1C) — frame 1
            // X offset is 0, so flipping doesn't affect the child position
            bossRenderer.drawFrameIndex(1,
                    state.x + VISUAL_OFFSET_X, state.y + VISUAL_OFFSET_Y,
                    facingRight, false);
        }
        // Robotnik ship is now rendered by HczEndBossRobotnikShip child object
    }
}
