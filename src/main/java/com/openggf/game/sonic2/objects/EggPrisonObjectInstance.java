package com.openggf.game.sonic2.objects;
import com.openggf.audio.GameMusic;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ExplosionObjectInstance;

import com.openggf.camera.Camera;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.EggPrisonAnimalInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Egg Prison / Capsule (Object 0x3E).
 * <p>
 * ROM-accurate implementation based on s2.asm loc_3F1E4 - loc_3F436.
 * <p>
 * Structure from Obj3E_ObjLoadData (4 sub-objects):
 * <ul>
 *   <li>Main body: routine=2, y_offset=0, width=$20, priority=4, frame=0</li>
 *   <li>Button: routine=4, y_offset=$28 (40) up, width=$10, priority=5, frame=4</li>
 *   <li>Lock: routine=6, y_offset=$18 (24) up, width=8, priority=3, frame=5</li>
 *   <li>Broken piece: routine=8, y_offset=0, width=$20, priority=4, frame=0</li>
 * </ul>
 * <p>
 * Button collision (loc_3F354):
 * - SolidObject with d1=$1B (27), d2=8, d3=8
 * - When standing_mask is set, button depresses 8 pixels and sets trigger flag
 * <p>
 * Breaking sequence (loc_3F2B4):
 * - Spawns explosion at lock position
 * - Lock flies with y_vel=-$400, x_vel=$800
 * - After $1D (29) frame delay, spawns 8 initial animals
 * <p>
 * Animal spawning (loc_3F3A8):
 * - Every 8 frames (Vint_runcount & 7 == 0), spawn one animal
 * - Random x offset: (random & $1F) - 6, optionally negated
 * - Animal delay: $C (12) frames
 * - After $B4 (180) frames, advance to final state
 * <p>
 * End state (loc_3F406):
 * - Loops through object RAM looking for animals
 * - When none remain, calls Load_EndOfAct
 */
public class EggPrisonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(EggPrisonObjectInstance.class.getName());

    // === ROM Constants ===

    // Sub-object Y offsets from Obj3E_ObjLoadData (subtracted from main Y)
    private static final int BUTTON_Y_OFFSET = 0x28;  // 40 pixels above main
    private static final int LOCK_Y_OFFSET = 0x18;    // 24 pixels above main

    // SolidObject parameters for body (loc_3F278): d1=$2B, d2=$18, d3=$18
    private static final int BODY_HALF_WIDTH = 0x2B;  // 43 pixels
    private static final int BODY_HALF_HEIGHT = 0x18; // 24 pixels

    // SolidObject parameters for button (loc_3F354): d1=$1B, d2=8, d3=8
    private static final int BUTTON_HALF_WIDTH = 0x1B;  // 27 pixels
    private static final int BUTTON_HALF_HEIGHT = 8;

    // Button depression amount when stood on
    private static final int BUTTON_DEPRESS = 8;

    // Lock physics when flying off (loc_3F2B4)
    private static final int LOCK_X_VEL = 0x800;  // Fixed-point velocity
    private static final int LOCK_Y_VEL = -0x400;
    private static final int GRAVITY = 0x38;      // From ObjectMoveAndFall

    // Timing constants
    private static final int BREAK_DELAY = 0x1D;        // 29 frames before spawning initial animals
    private static final int INITIAL_ANIMAL_COUNT = 8;
    private static final int INITIAL_ANIMAL_DELAY_BASE = 0x9A;  // 154 frames for first animal
    private static final int INITIAL_ANIMAL_DELAY_STEP = 8;
    private static final int INITIAL_ANIMAL_X_OFFSET_START = -0x1C;  // -28
    private static final int INITIAL_ANIMAL_X_OFFSET_STEP = 7;
    private static final int SPAWN_ANIMAL_DELAY = 0xC;  // 12 frames delay for random animals
    private static final int SPAWN_PHASE_DURATION = 0xB4;  // 180 frames of random spawning

    // Animation frames (from obj3E.asm mappings)
    private static final int FRAME_BODY_CLOSED = 0;
    private static final int FRAME_BODY_OPEN_1 = 1;
    private static final int FRAME_BODY_OPEN_2 = 2;
    private static final int FRAME_BODY_OPEN_3 = 3;
    private static final int FRAME_BUTTON = 4;
    private static final int FRAME_LOCK = 5;

    // Animation script from Ani_obj3E
    // Script 0: $F, 0, $FF (hold frame 0 forever)
    // Script 1: 3, 0, 1, 2, 3, $FE, 1 (3 frame delay, frames 0-1-2-3, loop from frame 1)
    private static final int ANIM_FRAME_DELAY = 3;

    // === State Machine ===

    // Body routine_secondary states (mirroring ROM off_3F2AE)
    private static final int BODY_STATE_IDLE = 0;            // loc_3F2B4 - waiting for button
    private static final int BODY_STATE_BREAK_DELAY = 2;     // loc_3F2FC - waiting to spawn animals
    private static final int BODY_STATE_DONE = 4;            // return_3F352 - done

    // Broken piece routine_secondary states (mirroring ROM loc_3F3A8)
    private static final int BROKEN_STATE_WAITING = 0;       // Before button pressed
    private static final int BROKEN_STATE_SPAWNING = 2;      // Spawning random animals
    private static final int BROKEN_STATE_FINAL = 4;         // Waiting for animals to leave

    // === Instance State ===

    // Body state
    private int bodyRoutineSecondary = BODY_STATE_IDLE;
    private int bodyAnim = 0;
    private int bodyAnimFrame = FRAME_BODY_CLOSED;
    private int bodyAnimTimer = 0;
    private int breakDelayTimer = 0;

    // Button state (now managed by separate button object)
    private EggPrisonButtonObjectInstance buttonObject;
    @RewindTransient(reason = "structural Obj3E routine-6 slot child is recreated from the parent update path")
    private EggPrisonLockSlotChild lockSlotChild;
    @RewindTransient(reason = "structural Obj3E routine-8 slot child is recreated from the parent update path")
    private EggPrisonBrokenSlotChild brokenSlotChild;
    private boolean buttonTriggered = false;  // objoff_32
    private boolean buttonSpawned = false;
    private boolean componentSlotsSpawned = false;

    // Lock state
    private int lockX;
    private int lockY;
    private int lockXVel = 0;          // Fixed-point (8.8)
    private int lockYVel = 0;          // Fixed-point (8.8)
    private int lockXSub = 0;          // Sub-pixel accumulator
    private int lockYSub = 0;
    private int lockRoutineSecondary = 0;
    private boolean lockVisible = true;

    // Broken piece state (spawns random animals)
    private int brokenRoutineSecondary = BROKEN_STATE_WAITING;
    private int brokenAnimDuration = 0;
    /**
     * True while the broken piece's SST slot has already been passed by the
     * ExecuteObjects walk that armed it, so {@code loc_3F3A8} does not run
     * again until the next frame. Derived from slot order, not from a fixed
     * one-frame skip -- see {@link #armBrokenPieceSpawning()}.
     */
    private boolean brokenPieceAlreadyPassedThisFrame = false;

    // Player reference for results screen
    private AbstractPlayableSprite lastPlayer;
    private boolean resultsTriggered = false;

    // Frame counter for spawn timing
    private int vIntRunCount = 0;

    public EggPrisonObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // Initialize lock position (24 pixels above main body)
        this.lockX = spawn.x();
        this.lockY = spawn.y() - LOCK_Y_OFFSET;
    }

    @Override
    public EggPrisonObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new EggPrisonObjectInstance(ctx.spawn(), "EggPrison");
    }

    /**
     * Spawns the ROM-visible Obj3E component slots.
     * <p>
     * ROM loc_3F212 initializes the body in a0, then AllocateObject creates
     * the routine-4 button, routine-6 lock, and routine-8 broken/end-checker
     * from Obj3E_ObjLoadData (docs/s2disasm/s2.asm:84798-84829). The Java
     * parent still owns lock physics and animal spawning, but these children
     * preserve the SST slot pressure later AllocateObject scans observe.
     * <p>
     * Divergence: {@code spawnChild} has FindNextFreeObj semantics (scan forward
     * from the parent slot), while {@code loc_3F220} calls {@code AllocateObject},
     * which restarts its scan at {@code Dynamic_Object_RAM}
     * (docs/s2disasm/s2.asm:84841, 33681-33695) and can therefore land a piece
     * BELOW the body. The two coincide whenever every slot under the body is
     * already taken, which is what both recorded capsules show -- EHZ2 body 16 /
     * button 17 / lock 18 / broken 19, ARZ2 body 17 / button 18 / lock 19 /
     * broken 20, both matching the engine. Switching to {@code spawnFreeChild}
     * was tried and changed no recorded slot, so the ROM allocator is documented
     * here rather than swapped in blind; {@link #armBrokenPieceSpawning()} reads
     * whatever order results instead of assuming one.
     */
    private void spawnComponentSlotObjects() {
        if (componentSlotsSpawned || services().objectManager() == null) {
            return;
        }

        buttonSpawned = true;
        buttonObject = spawnChild(() -> new EggPrisonButtonObjectInstance(spawn, this));
        lockSlotChild = spawnChild(() -> new EggPrisonLockSlotChild(
                buildComponentSpawn(LOCK_Y_OFFSET), this));
        brokenSlotChild = spawnChild(() -> new EggPrisonBrokenSlotChild(
                buildComponentSpawn(0), this));
        componentSlotsSpawned = true;
    }

    private ObjectSpawn buildComponentSpawn(int yOffset) {
        return new ObjectSpawn(
                spawn.x(),
                spawn.y() - yOffset,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                false,
                spawn.rawYWord(),
                spawn.layoutIndex());
    }

    private void attachLockSlotChildForRewind(EggPrisonLockSlotChild child) {
        lockSlotChild = child;
        componentSlotsSpawned = buttonSpawned && lockSlotChild != null && brokenSlotChild != null;
    }

    private void attachBrokenSlotChildForRewind(EggPrisonBrokenSlotChild child) {
        brokenSlotChild = child;
        componentSlotsSpawned = buttonSpawned && lockSlotChild != null && brokenSlotChild != null;
    }

    /**
     * Called by button when player lands on it.
     * Public callback method for EggPrisonButtonObjectInstance.
     */
    public void onButtonTriggered() {
        if (!buttonTriggered) {
            buttonTriggered = true;

            // Pause level timer (ROM: clr.b Update_HUD_timer)
            var levelGamestate = services().levelGamestate();
            if (levelGamestate != null) {
                levelGamestate.pauseTimer();
            }
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        this.lastPlayer = player;
        this.vIntRunCount = vIntRunCount;
        spawnComponentSlotObjects();

        // Update each sub-object according to its routine
        updateBody(player);
        updateLock();
        updateBrokenPiece(vIntRunCount);
    }

    /**
     * Body routine (loc_3F278).
     * Checks if button was triggered and handles breaking sequence.
     */
    private void updateBody(AbstractPlayableSprite player) {
        switch (bodyRoutineSecondary) {
            case BODY_STATE_IDLE -> updateBodyIdle();
            case BODY_STATE_BREAK_DELAY -> updateBodyBreakDelay();
            case BODY_STATE_DONE -> { /* Nothing */ }
        }

        // Update body animation
        updateBodyAnimation();
    }

    /**
     * loc_3F2B4 - Waiting for button to be triggered.
     */
    private void updateBodyIdle() {
        // Check if button was triggered (objoff_32 set by button routine)
        if (!buttonTriggered) {
            return;
        }

        // Mark as remembered so capsule never respawns (ROM: RememberState="true")
        ObjectManager objectManager = services().objectManager();
        ObjectLifetimeOps.markSpawnRemembered(objectManager, spawn);

        // Spawn explosion at lock position (plays explosion SFX on init, matching ROM)
        spawnExplosion(lockX, lockY);

        // Lock starts flying off
        lockXVel = LOCK_X_VEL;
        lockYVel = LOCK_Y_VEL;
        lockRoutineSecondary = 2;

        // Set break delay timer
        breakDelayTimer = BREAK_DELAY;

        // Advance body state
        bodyRoutineSecondary = BODY_STATE_BREAK_DELAY;

        LOGGER.fine("Egg Prison triggered at X=" + spawn.x());
    }

    /**
     * loc_3F2FC - Wait for delay, then spawn initial animals.
     */
    private void updateBodyBreakDelay() {
        // ROM: subq.w #1,objoff_34(a0) / bpl.s return_3F352
        // (docs/s2disasm/s2.asm:84909-84910). bpl branches on a result of ZERO
        // as well as positive, so the counter seeded with $1D at loc_3F2B4
        // (s2.asm:84902) is decremented 30 times -- 29 down to 0 all return, and
        // only the pass that takes it to -1 falls through. Testing `> 0` here
        // would fire on the 29th pass, one frame early, which shifts every
        // downstream (Vint_runcount+3) & 7 spawn decision by one.
        breakDelayTimer--;
        if (breakDelayTimer >= 0) {
            return;
        }

        // Switch to opening animation (anim=1)
        bodyAnim = 1;
        bodyAnimFrame = FRAME_BODY_CLOSED;
        bodyAnimTimer = ANIM_FRAME_DELAY;

        // Spawn 8 initial animals with staggered delays
        spawnInitialAnimals();

        // Tell broken piece to start spawning random animals after $B4 frames
        brokenAnimDuration = SPAWN_PHASE_DURATION;
        brokenRoutineSecondary = BROKEN_STATE_SPAWNING;
        armBrokenPieceSpawning();

        // Body is done
        bodyRoutineSecondary = BODY_STATE_DONE;
    }

    /**
     * Decides whether the broken piece's {@code loc_3F3A8} routine still runs on
     * the frame the body arms it.
     * <p>
     * ROM {@code loc_3F2FC} arms the piece by writing {@code $B4} to
     * {@code anim_frame_duration(a2)} and {@code addq.b #2,routine_secondary(a2)}
     * on the object in {@code objoff_3C(a0)} (docs/s2disasm/s2.asm:84928-84930).
     * That is a different SST slot from the body's, and {@code ExecuteObjects}
     * walks the slots in ascending order, so the piece runs again this frame only
     * if its slot sits after the body's. When it does, {@code loc_3F3A8} sees the
     * routine_secondary it was just given and its {@code Vint_runcount+3 & 7}
     * spawn gate (docs/s2disasm/s2.asm:84972-84974) is live on the arming frame;
     * when it sits before the body's slot the walk has already passed it and the
     * gate first runs next frame.
     * <p>
     * This is deliberately a slot comparison rather than an unconditional
     * one-frame skip: the ROM allocates the piece with {@code AllocateObject},
     * which scans from the start of {@code Dynamic_Object_RAM}
     * (docs/s2disasm/s2.asm:84841, 33681-33695), so the relative order depends on
     * which slots were free when the capsule initialised, not on a constant.
     */
    private void armBrokenPieceSpawning() {
        brokenPieceAlreadyPassedThisFrame =
                brokenSlotChild == null
                        || brokenSlotChild.getSlotIndex() <= getSlotIndex();
    }

    /**
     * Updates body animation (Ani_obj3E).
     * Script 0: Hold frame 0 forever
     * Script 1: Frames 0,1,2,3 with 3 frame delay, then hold on frame 3
     * ($FE, 1 means branch back 1 byte, landing on frame 3 - hold forever)
     */
    private void updateBodyAnimation() {
        if (bodyAnim == 0) {
            bodyAnimFrame = FRAME_BODY_CLOSED;
            return;
        }

        // Anim 1: Opening animation - play frames 0,1,2,3 then hold on 3
        if (bodyAnimFrame >= FRAME_BODY_OPEN_3) {
            // Already at final frame - hold it
            return;
        }

        bodyAnimTimer--;
        if (bodyAnimTimer <= 0) {
            bodyAnimTimer = ANIM_FRAME_DELAY;
            bodyAnimFrame++;
            // Clamp to max frame (hold on frame 3)
            if (bodyAnimFrame > FRAME_BODY_OPEN_3) {
                bodyAnimFrame = FRAME_BODY_OPEN_3;
            }
        }
    }

    /**
     * Lock routine (loc_3F38E).
     * When triggered, flies off using ObjectMoveAndFall physics.
     */
    private void updateLock() {
        if (lockRoutineSecondary == 0) {
            // Not triggered yet
            return;
        }

        // ROM ObjectMoveAndFall reads old y_vel for this frame's position
        // update, then stores y_vel+$38 for the next frame
        // (docs/s2disasm/s2.asm:30165-30177; Obj3E call at 84920-84927).
        int oldLockYVel = lockYVel;
        lockYVel += GRAVITY;
        lockXSub += lockXVel;
        lockYSub += oldLockYVel;

        // Convert from fixed-point to pixels
        lockX += (lockXSub >> 8);
        lockY += (lockYSub >> 8);
        lockXSub &= 0xFF;
        lockYSub &= 0xFF;

        // Check if lock is off screen
        Camera camera = services().camera();
        if (camera != null) {
            int screenRight = camera.getX() + viewportWidth() + 64;
            int screenBottom = camera.getY() + viewportHeight() + 64;
            if (lockX > screenRight || lockY > screenBottom) {
                lockVisible = false;
                expireSlotChild(lockSlotChild);
                lockSlotChild = null;
            }
        }
    }

    /**
     * Broken piece routine (loc_3F3A8).
     * Spawns random animals at regular intervals.
     */
    private void updateBrokenPiece(int vIntRunCount) {
        if (brokenRoutineSecondary == BROKEN_STATE_WAITING) {
            return;
        }

        if (brokenRoutineSecondary == BROKEN_STATE_SPAWNING) {
            // ROM loc_3F3A8 samples (Vint_runcount+3) and spawns only when
            // its low three bits are zero (docs/s2disasm/s2.asm:84969-84976).
            if (!brokenPieceAlreadyPassedThisFrame && (vIntRunCount & 7) == 0) {
                spawnRandomAnimal();
            }
            brokenPieceAlreadyPassedThisFrame = false;

            // Count down duration
            brokenAnimDuration--;
            if (brokenAnimDuration <= 0) {
                // ROM writes $B4 to anim_frame_duration when entering routine
                // $0A, but loc_3F406 scans for Obj28 every frame and never
                // decrements that field (docs/s2disasm/s2.asm:84957-84979).
                brokenRoutineSecondary = BROKEN_STATE_FINAL;
            }
            return;
        }

        if (brokenRoutineSecondary == BROKEN_STATE_FINAL) {
            // loc_3F406: check if any animals remain
            if (!areAnimalsPresent()) {
                triggerEndOfAct();
            }
            // If animals still present, keep checking every frame (don't reset timer)
        }
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // ROM Obj3E allocates each capsule piece (body, button, lock, broken
        // half) into its own SST slot via AllocateObject, and copies only the
        // per-piece load data into it (docs/s2disasm/s2.asm:84832-84865). Each
        // piece therefore owns a separate status(a0) byte, and
        // SolidObject_TestClearPush releases the player's push bit only when
        // the CALLING object's own pushing bit is set -- otherwise it branches
        // straight to SolidObject_NoCollision without touching status(a1)
        // (docs/s2disasm/s2.asm:35462-35466,35483-35490). Keying the engine's
        // push/standing latch on the shared ObjectSpawn instead lets a sibling
        // piece's no-contact pass clear the body's push mark inside the same
        // object pass, so Sonic's next Sonic_Animate never sees Status_Push and
        // publishes a walk mapping frame where ROM publishes SonAni_Push.
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM: SolidObject(d1=$2B, d2=$18, d3=$18) at loc_3F278
        // Body is always solid (width=43px, height=24px)
        return SolidObjectParams.of(
            BODY_HALF_WIDTH,    // 0x2B = 43 pixels
            BODY_HALF_HEIGHT,   // 0x18 = 24 pixels (air)
            BODY_HALF_HEIGHT    // 0x18 = 24 pixels (ground)
        );
    }

    @Override
    public int getBalanceWidthPixels() {
        // Obj3E_ObjLoadData stores width_pixels=$20 for the body. The separate
        // SolidObject call expands only collision d1 to $2B.
        return 0x20;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Always solid, even after breaking
        return true;
    }

    @Override
    public boolean preservesEdgeSubpixelMotion() {
        // S2 Obj3E body calls SolidObject at loc_3F278; SolidObject_AtEdge
        // preserves x_vel/g_speed when exact-edge contact only sets pushing.
        return true;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // Obj3E calls the standard S2 SolidObject helper with d1=$2B.
        // Its BHI range check keeps relX==2*d1 as an exact-edge side contact.
        return true;
    }

    @Override
    public boolean preservesSidekickCpuPushGraceFromInteractSlot(PlayableEntity player) {
        // TailsCPU_Normal reads current Status_Push before Obj3E's later
        // SolidObject body pass refreshes the live object status byte.
        return isGroundedCpuSidekickAtBodyEdge(player);
    }

    @Override
    public boolean preservesMovingSidekickCpuPushAtZeroGraceFromInteractSlot(PlayableEntity player) {
        // HTZ2 capsule edge: ROM Tails interact still points at Obj3E when
        // TailsCPU_Normal tests Status_Push (s2.asm:39297-39300), while the
        // body SolidObject call is Obj3E loc_3F278.
        return isGroundedCpuSidekickAtBodyEdge(player);
    }

    @Override
    public boolean publishesSidekickCpuPushFromInteractSlot(PlayableEntity player) {
        return isGroundedCpuSidekickAtBodyEdge(player);
    }

    @Override
    public int sidekickCpuPushGraceMinimumFramesFromInteractSlot(PlayableEntity player) {
        return preservesSidekickCpuPushGraceFromInteractSlot(player) ? 0 : Integer.MAX_VALUE;
    }

    @Override
    public int sidekickCpuPushGraceMaximumFramesFromInteractSlot(PlayableEntity player) {
        return preservesSidekickCpuPushGraceFromInteractSlot(player) ? 0 : Integer.MIN_VALUE;
    }

    private boolean isGroundedCpuSidekickAtBodyEdge(PlayableEntity player) {
        if (player == null || !player.isCpuControlled() || player.getAir()) {
            return false;
        }
        int dx = Math.abs(player.getCentreX() - spawn.x());
        int dy = Math.abs(player.getCentreY() - spawn.y());
        return dx == BODY_HALF_WIDTH && dy <= BODY_HALF_HEIGHT;
    }

    @Override
    public boolean shouldStayActiveWhenRemembered() {
        // Capsule needs to stay active to complete its animation sequence
        // (lock flying off, capsule opening, animals spawning, results screen)
        // It will self-destruct when results screen is triggered
        return true;
    }

    /**
     * Spawns 8 initial animals with staggered delay timers.
     * ROM: loc_3F2FC loop with d6=7, d5=$9A, d4=-$1C
     */
    private void spawnInitialAnimals() {
        int baseX = spawn.x();
        int baseY = spawn.y();
        int xOffset = INITIAL_ANIMAL_X_OFFSET_START;
        int delay = INITIAL_ANIMAL_DELAY_BASE;

        for (int i = 0; i < INITIAL_ANIMAL_COUNT; i++) {
            final ObjectSpawn animalSpawn = new ObjectSpawn(
                    baseX + xOffset, baseY,
                    0x28, 0, 0, false, 0
            );
            // Obj28_InitRandom runs as the animal's first ExecuteObjects pass
            // and only then branches to Obj28_Prison for later frames
            // (docs/s2disasm/s2.asm:24596-24636,84943-84955).
            // The Java constructor folds that routine-0 setup into state, and
            // these lower-slot initial children are visible to the manager on
            // the allocation frame, so preserve the ROM frame before
            // Obj28_Prison decrements objoff_36.
            // +1, not +2: the ROM writes d5 straight into objoff_36 and the
            // animal's first ExecuteObjects pass runs Obj28_InitRandom without
            // decrementing it, so folding that pass into the constructor costs
            // exactly one frame. The extra +1 this carried until now was
            // absorbing the loc_3F2FC break-delay off-by-one now fixed in
            // updateBodyBreakDelay, not a second folded pass.
            final int animalDelay = delay + 1;
            final int artVariant = Sonic2Rng.nextAnimalArtVariant(services().rng());
            // spawnFreeChild matches the previous addDynamicObject (FindFreeObj /
            // lowest-slot) semantics, but also sets the construction context so the
            // animal can resolve its sprite renderer in its constructor — without it
            // the released animals are spawned but render invisibly.
            spawnFreeChild(() -> new EggPrisonAnimalInstance(animalSpawn, animalDelay, artVariant));

            xOffset += INITIAL_ANIMAL_X_OFFSET_STEP;
            delay -= INITIAL_ANIMAL_DELAY_STEP;
        }
    }

    /**
     * Spawns a random animal at the capsule position.
     * ROM: loc_3F3A8 random spawn logic
     */
    private void spawnRandomAnimal() {
        int baseX = spawn.x();
        int baseY = spawn.y();

        // ROM: jsr RandomNumber / andi.w #$1F,d0 / subq.w #6,d0 / tst.w d1 / optional neg
        int randomOffset = Sonic2Rng.nextEggPrisonAnimalXOffset(services().rng());

        final ObjectSpawn animalSpawn = new ObjectSpawn(
                baseX + randomOffset, baseY,
                0x28, 0, 0, false, 0
        );
        final int artVariant = Sonic2Rng.nextAnimalArtVariant(services().rng());
        // spawnFreeChild sets the construction context so the animal resolves its
        // sprite renderer (see spawnInitialAnimals). The constructor folds Obj28
        // routine-0 setup into Java state, so preserve the ROM's first visible
        // routine-0 pass before Obj28_Prison starts decrementing objoff_36
        // (docs/s2disasm/s2.asm:24596-24636,84943-84955).
        spawnFreeChild(() -> new EggPrisonAnimalInstance(animalSpawn, SPAWN_ANIMAL_DELAY + 1, artVariant));
    }

    /**
     * Spawns an explosion at the given position.
     */
    private void spawnExplosion(int x, int y) {
        ObjectManager objectManager = services().objectManager();
        ObjectRenderManager renderManager = services().renderManager();
        if (objectManager == null || renderManager == null) {
            return;
        }

        spawnFreeChild(() -> new ExplosionObjectInstance(0x27, x, y, renderManager, Sonic2Sfx.EXPLOSION.id));
    }

    /**
     * Checks if any animals are still present in object RAM.
     * ROM: loc_3F406 loop
     */
    private boolean areAnimalsPresent() {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return false;
        }

        for (var obj : objectManager.getActiveObjects()) {
            if (obj instanceof AnimalObjectInstance || obj instanceof EggPrisonAnimalInstance) {
                if (!obj.isDestroyed()) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Triggers end of act sequence (Load_EndOfAct).
     */
    private void triggerEndOfAct() {
        if (resultsTriggered) {
            return;
        }
        if (!queueResultsPlc()) {
            return;
        }
        resultsTriggered = true;

        LOGGER.info("All animals gone, triggering Load_EndOfAct");

        // Play stage clear music
        try {
            services().playMusic(GameMusic.ACT_CLEAR);
        } catch (Exception e) {
            LOGGER.warning("Failed to play stage clear music: " + e.getMessage());
        }

        // Spawn results screen
        var levelGamestate = services().levelGamestate();
        int elapsedSeconds = levelGamestate != null ? levelGamestate.getElapsedSeconds() : 0;
        int ringCount = lastPlayer != null ? lastPlayer.getRingCount() : 0;
        int actNumber = services().currentAct() + 1;
        boolean allRingsCollected = services().areAllRingsCollected();

        ObjectManager objectManager = services().objectManager();
        if (objectManager != null) {
            spawnFreeChild(() -> new ResultsScreenObjectInstance(
                    elapsedSeconds, ringCount, actNumber, allRingsCollected));
        }

        // ROM deletes the routine-$A end-checker sub-object at loc_3F406, not
        // the routine-2 body. The body keeps running loc_3F278 and therefore
        // keeps the player's continued SolidObject ride attached through the
        // results sequence.
        ObjectManager objectManagerForDelete = services().objectManager();
        if (objectManagerForDelete != null && brokenSlotChild != null) {
            objectManagerForDelete.releaseSlot(brokenSlotChild);
        }
        expireSlotChild(brokenSlotChild);
        brokenSlotChild = null;
    }

    private boolean queueResultsPlc() {
        try {
            Sonic2PlcService plc = services().gameModule().getGameService(Sonic2PlcService.class);
            if (plc != null) {
                Sonic2LevelEventManager events = services().gameModule()
                        .getGameService(Sonic2LevelEventManager.class);
                plc.transact(Sonic2PlcService.replaceOperation(events != null && events.getPlayerCharacter() == PlayerCharacter.TAILS_ALONE
                        ? 66 : 38));
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    @Override
    public void onUnload() {
        expireSlotChild(lockSlotChild);
        expireSlotChild(brokenSlotChild);
        lockSlotChild = null;
        brokenSlotChild = null;
        super.onUnload();
    }

    private static void expireSlotChild(AbstractObjectInstance child) {
        if (child != null && !child.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(child);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        PatternSpriteRenderer renderer = renderManager != null
                ? renderManager.getEggPrisonRenderer()
                : null;

        if (renderer == null || !renderer.isReady()) {
            renderPlaceholder(commands);
            return;
        }

        int bodyX = spawn.x();
        int bodyY = spawn.y();

        // Draw main body (priority 4)
        renderer.drawFrameIndex(bodyAnimFrame, bodyX, bodyY, false, false);

        // Button now renders itself as a separate object

        // Draw lock (priority 3) if still visible
        if (lockVisible) {
            renderer.drawFrameIndex(FRAME_LOCK, lockX, lockY, false, false);
        }
    }

    private void renderPlaceholder(List<GLCommand> commands) {
        int x = spawn.x();
        int y = spawn.y();

        // Body
        appendBox(commands, x, y, BODY_HALF_WIDTH, BODY_HALF_HEIGHT, 0.8f, 0.6f, 0.2f);

        // Button renders itself as a separate object

        // Lock
        if (lockVisible) {
            appendBox(commands, lockX, lockY, 8, 8, 0.5f, 0.5f, 0.5f);
        }
    }

    private void appendBox(List<GLCommand> commands, int cx, int cy, int hw, int hh,
                           float r, float g, float b) {
        int left = cx - hw;
        int right = cx + hw;
        int top = cy - hh;
        int bottom = cy + hh;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public int getX() {
        return spawn.x();
    }

    @Override
    public int getY() {
        return spawn.y();
    }

    private static EggPrisonObjectInstance nearestParentForRewind(
            RewindRecreateContext ctx,
            ObjectSpawn childSpawn) {
        ObjectManager manager = ctx.objectManager();
        if (manager == null && ctx.objectServices() != null) {
            manager = ctx.objectServices().objectManager();
        }
        if (manager == null) {
            return null;
        }
        return manager.getActiveObjects().stream()
                .filter(EggPrisonObjectInstance.class::isInstance)
                .map(EggPrisonObjectInstance.class::cast)
                .filter(parent -> !parent.isDestroyed())
                .min((a, b) -> Integer.compare(
                        distanceFromChildSpawn(a, childSpawn),
                        distanceFromChildSpawn(b, childSpawn)))
                .orElse(null);
    }

    private static int distanceFromChildSpawn(EggPrisonObjectInstance parent, ObjectSpawn childSpawn) {
        return Math.abs(parent.spawn.x() - childSpawn.x()) + Math.abs(parent.spawn.y() - childSpawn.y());
    }

    private static final class EggPrisonLockSlotChild extends AbstractObjectInstance
            implements RewindRecreatable {
        @RewindTransient(reason = "structural Obj3E component link is restored by parent lookup")
        private final EggPrisonObjectInstance parent;

        private EggPrisonLockSlotChild(ObjectSpawn spawn) {
            this(spawn, null);
        }

        private EggPrisonLockSlotChild(ObjectSpawn spawn, EggPrisonObjectInstance parent) {
            super(spawn, "EggPrison Lock");
            this.parent = parent;
        }

        @Override
        public EggPrisonLockSlotChild recreateForRewind(RewindRecreateContext ctx) {
            EggPrisonObjectInstance restoredParent = nearestParentForRewind(ctx, ctx.spawn());
            EggPrisonLockSlotChild child = new EggPrisonLockSlotChild(ctx.spawn(), restoredParent);
            if (restoredParent != null) {
                restoredParent.attachLockSlotChildForRewind(child);
            }
            return child;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent == null || parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Slot-pressure child only; the parent renders the lock sprite.
        }

        @Override
        public int getX() {
            return parent != null ? parent.lockX : spawn.x();
        }

        @Override
        public int getY() {
            return parent != null ? parent.lockY : spawn.y();
        }
    }

    private static final class EggPrisonBrokenSlotChild extends AbstractObjectInstance
            implements RewindRecreatable {
        @RewindTransient(reason = "structural Obj3E component link is restored by parent lookup")
        private final EggPrisonObjectInstance parent;

        private EggPrisonBrokenSlotChild(ObjectSpawn spawn) {
            this(spawn, null);
        }

        private EggPrisonBrokenSlotChild(ObjectSpawn spawn, EggPrisonObjectInstance parent) {
            super(spawn, "EggPrison Broken Slot");
            this.parent = parent;
        }

        @Override
        public EggPrisonBrokenSlotChild recreateForRewind(RewindRecreateContext ctx) {
            EggPrisonObjectInstance restoredParent = nearestParentForRewind(ctx, ctx.spawn());
            EggPrisonBrokenSlotChild child = new EggPrisonBrokenSlotChild(ctx.spawn(), restoredParent);
            if (restoredParent != null) {
                restoredParent.attachBrokenSlotChildForRewind(child);
            }
            return child;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent == null || parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // Slot-pressure child only; the parent owns the broken/end-checker routine effects.
        }

        @Override
        public int getX() {
            return parent != null ? parent.spawn.x() : spawn.x();
        }

        @Override
        public int getY() {
            return parent != null ? parent.spawn.y() : spawn.y();
        }
    }
}
