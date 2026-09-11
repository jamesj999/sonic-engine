package com.openggf.game.sonic2.objects.bosses;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcRequests;
import com.openggf.game.sonic2.objects.EggPrisonObjectInstance;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnConstructionContextRewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.objects.boss.BossChildComponent;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.function.Supplier;

/**
 * EHZ Act 2 Boss (Object 0x56) - Drill car boss with 6 child components.
 * ROM Reference: s2.asm:62743 (Obj56_Init)
 *
 * State Machine (routine_secondary):
 * - SUB0: Diagonal approach to X=0x29D0
 * - SUB2: Descend to Y=0x41E, wait 60 frames (with tertiary sub-states)
 * - SUB4: Active battle - oscillate between X boundaries
 * - SUB6: Defeated falling with explosions
 * - SUB8: Idle after fall (12 frames)
 * - SUBA: Flying off sequence (with tertiary sub-states)
 */
public class Sonic2EHZBossInstance extends AbstractBossInstance
        implements SpawnConstructionContextRewindRecreatable {

    // State machine constants
    private static final int SUB0_APPROACH_DIAGONAL = 0x00;
    private static final int SUB2_DESCEND_VERTICAL = 0x02;
    private static final int SUB4_ACTIVE_BATTLE = 0x04;
    private static final int SUB6_DEFEATED_FALLING = 0x06;
    private static final int SUB8_IDLE_POST_FALL = 0x08;
    private static final int SUBA_FLYING_OFF = 0x0A;

    // Position constants
    private static final int INITIAL_X = 0x29D0;
    private static final int INITIAL_Y = 0x0426;
    private static final int START_X = 0x2AF0;
    private static final int START_Y = 0x02F8;
    private static final int TARGET_Y = 0x041E;
    private static final int BOUNDARY_LEFT = 0x28A0;
    private static final int BOUNDARY_RIGHT = 0x2B08;
    private static final int CAMERA_MAX_X_TARGET = 0x2AB0;

    // Velocity constants (8.8 fixed-point)
    private static final int VELOCITY_LEFT = -0x200;
    private static final int VELOCITY_UP_FLEE = -1;
    private static final int VELOCITY_RIGHT_FLEE = 6;

    // Physics constants
    // GRAVITY inherited from AbstractBossInstance
    private static final int MAIN_Y_RADIUS = 0x14;

    // Timing constants
    private static final int DESCEND_WAIT_FRAMES = 60;
    private static final int POST_FALL_WAIT_FRAMES = 12;
    private static final int FLEE_UP_DURATION = 96;
    private static final int FLOOR_Y = 0x48C; // Boss floor during defeat

    // Custom memory offsets (objoff_XX pattern from ROM)
    private static final int OBJOFF_FLAGS = 0x2D;
    private static final int OBJOFF_WHEEL_Y_ACCUM = 0x2E;
    private static final int OBJOFF_INITIAL_X = 0x30;
    private static final int OBJOFF_INITIAL_Y = 0x38;

    // Bitflags for OBJOFF_FLAGS
    private static final int FLAG_GROUNDED = 0x01;
    private static final int FLAG_ACTIVE = 0x02;
    private static final int FLAG_FLYING_OFF = 0x04;
    private static final int FLAG_SPIKE_SEPARATED = 0x08;
    private static final int FLAG_FINISHED = 0x10;

    // Wheel Y accumulator
    private int wheelYAccumulator;
    private int waitTimer;
    private int defeatTimer;
    private int currentVIntRunCount;

    /**
     * ROM {@code Obj56_Init} occupies the object's whole first executed frame: it
     * advances {@code routine(a0)} from 0 to 2, allocates the vehicle top, ground
     * vehicle, wheels and spike children, sets the main object to
     * {@code x_pos=$2AF0 / y_pos=$2F8}, and then {@code rts}
     * (docs/s2disasm/s2.asm:63256-63325). The routine-2 dispatch to
     * {@code loc_2F262} therefore does not run until the following
     * {@code RunObjects} pass.
     *
     * <p>The engine performs the whole init in the constructor, so without this
     * flag the boss's first {@code update()} already executes the routine-2
     * diagonal step and the entire boss — vehicle, wheels and spike — runs one
     * frame ahead of ROM for the rest of the fight. Measured against
     * {@code seg7_ehz2}'s {@code object_near} slot-20 stream, the engine's vehicle
     * X equalled the ROM's next-frame value on every compared row before this was
     * modelled.
     */
    private boolean initRoutineFrameConsumed;

    public Sonic2EHZBossInstance(ObjectSpawn spawn) {
        super(spawn, "EHZ Boss");
    }

    @Override
    protected void initializeBossState() {
        // Store initial position
        setCustomFlag(OBJOFF_INITIAL_X, INITIAL_X);
        setCustomFlag(OBJOFF_INITIAL_Y, INITIAL_Y);

        // CRITICAL: Initialize flags to 0 (no flags set during approach)
        setCustomFlag(OBJOFF_FLAGS, 0);

        // Initialize state machine
        state.routineSecondary = SUB0_APPROACH_DIAGONAL;
        state.routineTertiary = 0;

        // Initialize position
        state.x = START_X;
        state.y = START_Y;
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;

        // Initialize wheel accumulator
        wheelYAccumulator = 0;
        waitTimer = 0;
        defeatTimer = 0;

        // Spawn child components
        spawnChildComponents();
    }

    private void spawnChildComponents() {
        spawnBossChild(() -> new EHZBossVehicleTop(this));
        spawnBossChild(() -> new EHZBossGroundVehicle(this));
        spawnBossChild(() -> new EHZBossPropeller(this));
        spawnBossChild(() -> new EHZBossWheel(this, 0, 0x1C, 3));  // Front wheel: +28 (near side - in front of body)
        spawnBossChild(() -> new EHZBossWheel(this, 1, -0x0C, 3)); // Front wheel: -12 (near side - in front of body)
        spawnBossChild(() -> new EHZBossWheel(this, 2, -0x2C, 5)); // Rear wheel: -44 (far side - behind body)
        spawnBossChild(() -> new EHZBossSpike(this));
    }

    private <T extends AbstractBossChild> T spawnBossChild(Supplier<T> factory) {
        T child = spawnChild(factory);
        childComponents.add(child);
        return child;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        currentVIntRunCount = vIntRunCount;
        if (!initRoutineFrameConsumed) {
            // ROM: Obj56_Init ends in rts (docs/s2disasm/s2.asm:63325) after
            // addq.b #2,routine(a0) (:63278), so the first executed frame runs the
            // init only. The children it allocates sit in higher SST slots and do
            // still run in that same pass, which is why only the main object's
            // routine dispatch is withheld here.
            initRoutineFrameConsumed = true;
            return;
        }
        // Run state machine
        switch (state.routineSecondary) {
            case SUB0_APPROACH_DIAGONAL -> updateSub0ApproachDiagonal();
            case SUB2_DESCEND_VERTICAL -> updateSub2DescendVertical();
            case SUB4_ACTIVE_BATTLE -> updateSub4ActiveBattle();
            case SUB6_DEFEATED_FALLING -> updateSub6DefeatedFalling();
            case SUB8_IDLE_POST_FALL -> updateSub8IdlePostFall();
            case SUBA_FLYING_OFF -> updateSubAFlyingOff();
        }
    }

    // ROM: s2.asm:62922-62934 (loc_2F27C - SUB0: Approaching diagonally)
    private void updateSub0ApproachDiagonal() {
        // ROM loc_2F27C tests the arrival BEFORE it moves, and the arrival frame
        // is spent entirely on the snap-and-advance branch (loc_2F29A) with no
        // diagonal step (docs/s2disasm/s2.asm:63434-63447). Moving first and
        // testing afterwards folds that frame away and starts the descent one
        // frame early, which carries all the way to the spike's contact frame.
        // ROM: s2.asm:63437 - cmpi.w #$29D0,x_pos(a0) / ble.s loc_2F29A
        if (state.x <= INITIAL_X) {
            // ROM: s2.asm:63444-63445 - move.w #$29D0,x_pos(a0) / addq.b #2,routine_secondary(a0)
            state.x = INITIAL_X;
            syncFixedFromPosition();
            state.routineSecondary = SUB2_DESCEND_VERTICAL;
            state.routineTertiary = 0;
            return;
        }
        // ROM: s2.asm:63439 - subi_.w #1,x_pos(a0)
        state.x--;
        // ROM: s2.asm:63440 - addi_.w #1,y_pos(a0)
        state.y++;
        syncFixedFromPosition();
    }

    // ROM: s2.asm:62937-62969 (loc_2F2A8 - SUB2: Descending vertically/waiting)
    private void updateSub2DescendVertical() {
        switch (state.routineTertiary) {
            case 0 -> {
                // ROM loc_2F2BA tests the target height BEFORE it descends, and the
                // frame that reaches it is spent on loc_2F2CC alone, with no further
                // step down and no clamp of y_pos (docs/s2disasm/s2.asm:63465-63478).
                // ROM: s2.asm:63466 - cmpi.w #$41E,y_pos(a0) / bge.s loc_2F2CC
                if (state.y >= TARGET_Y) {
                    // ROM: s2.asm:63473 - addq.b #2,objoff_2C(a0)
                    state.routineTertiary = 2;
                    // ROM: s2.asm:63475 - move.w #60,objoff_2A(a0)
                    waitTimer = DESCEND_WAIT_FRAMES;
                    // ROM: s2.asm:63474 - bset #0,objoff_2D(a0)
                    setCustomFlag(OBJOFF_FLAGS, getCustomFlag(OBJOFF_FLAGS) | FLAG_GROUNDED);
                    return;
                }
                // ROM: s2.asm:63468 - addi_.w #1,y_pos(a0)
                state.y++;
                syncFixedFromPosition();
            }
            case 2 -> {
                // ROM: s2.asm:62962-62969 (loc_2F2E0 - Sub2_2: waiting)
                // ROM: s2.asm:62963 - subi_.w #1,objoff_2A(a0)
                waitTimer--;
                if (waitTimer < 0) {
                    // ROM: s2.asm:62965 - move.w #-$200,x_vel(a0)
                    state.routineSecondary = SUB4_ACTIVE_BATTLE;
                    state.xVel = VELOCITY_LEFT;
                    // ROM: s2.asm:62968 - bset #1,objoff_2D(a0)
                    setCustomFlag(OBJOFF_FLAGS, getCustomFlag(OBJOFF_FLAGS) | FLAG_ACTIVE);
                }
            }
        }
    }

    // ROM: s2.asm:62972-62986 (loc_2F304 - SUB4: Moving back and forth)
    private void updateSub4ActiveBattle() {
        // ROM: s2.asm:62974 - bsr.w loc_2F484 (boundary check)
        if (state.x <= BOUNDARY_LEFT || state.x >= BOUNDARY_RIGHT) {
            state.renderFlags ^= 1;
            state.xVel = -state.xVel;
        }

        // ROM: s2.asm:62975-62978 - Calculate Y position from wheel support
        state.y = (wheelYAccumulator >> 1) - 0x14;
        state.yFixed = state.y << 16;
        wheelYAccumulator = 0;

        // ROM: s2.asm:62980-62985 - Apply velocity (16.16 fixed-point)
        state.xFixed += (state.xVel << 8);
        state.updatePositionFromFixed();
    }

    // ROM: s2.asm:62989-63007 (loc_2F336 - SUB6: Boss defeated, falling/lying on ground)
    private void updateSub6DefeatedFalling() {
        defeatTimer--;
        if (defeatTimer < 0) {
            state.xVel = 0;
            state.routineSecondary = SUB8_IDLE_POST_FALL;
            defeatTimer = -0x26;
            waitTimer = POST_FALL_WAIT_FRAMES;
            return;
        }

        if ((currentVIntRunCount & 7) == 0) {
            spawnDefeatExplosion();
        }

        applyObjectMoveAndFall();
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(state.x, state.y, MAIN_Y_RADIUS);
        if (floor.hasCollision() && floor.distance() < 0) {
            state.y += floor.distance();
            state.yFixed = state.y << 16;
            state.yVel = 0;
        }
    }

    // ROM: s2.asm:63010-63015 (loc_2F374 - SUB8: Boss idle for $C frames)
    private void updateSub8IdlePostFall() {
        // ROM: s2.asm:63011 - subq.w #1,objoff_2A(a0)
        waitTimer--;
        if (waitTimer < 0) {
            // ROM: s2.asm:63013 - addq.b #2,routine_secondary(a0)
            state.routineSecondary = SUBA_FLYING_OFF;
            // ROM: s2.asm:63014 - move.b #0,objoff_2C(a0)
            state.routineTertiary = 0;
        }
    }

    // ROM: s2.asm:63018-63100 (loc_2F38A - SUBA: Flying off, moving camera)
    private void updateSubAFlyingOff() {
        switch (state.routineTertiary) {
            case 0 -> {
                // ROM: s2.asm:63031-63057 (loc_2F3A2 - SubA_0: Initialize propeller)
                // ROM: s2.asm:63032 - bclr #0,objoff_2D(a0)
                // Clear grounded flag
                setCustomFlag(OBJOFF_FLAGS, getCustomFlag(OBJOFF_FLAGS) & ~FLAG_GROUNDED);
                reloadPropeller();

                state.routineTertiary = 2;
                // ROM: s2.asm:63052 - move.w #$32,objoff_2A(a0)
                waitTimer = 0x32;
                // ROM: Current_Boss_ID is NEVER cleared in Sonic 2. It is written only by
                // the boss-arena setup routines (`move.b #N,(Current_Boss_ID).w`, ids 1-9)
                // and read by `tst.b`; docs/s2disasm/s2.asm contains no `clr.b` or
                // `move.b #0` for it, so it resets only via the level-load RAM clear and
                // persists to the end of the act. Sonic_Boundary's right-hand test widens
                // the side boundary by $40 only when it is zero (s2.asm:37243-37251), so
                // clearing it here let the character run 64px past the ROM's clamp.
                // Contrast S1, which DOES clear at the Egg Prison
                // (s1disasm/_incObj/3E Prison Capsule.asm:97), and S3K, which clears
                // Boss_flag at 31 sites. S2 is the exception.
                services().playMusic(Sonic2Music.EMERALD_HILL.id);
            }
            case 2 -> {
                // ROM: s2.asm:63060-63068 (loc_2F424 - SubA_2: Waiting)
                // ROM: s2.asm:63061 - subi_.w #1,objoff_2A(a0)
                waitTimer--;
                if (waitTimer < 0) {
                    if (!Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_ANIMALS_EHZ,
                            Sonic2Constants.PLC_EXPLOSION)) return;
                    state.routineTertiary = 4;
                    // ROM: s2.asm:63063 - bset #2,objoff_2D(a0)
                    setCustomFlag(OBJOFF_FLAGS, getCustomFlag(OBJOFF_FLAGS) | FLAG_FLYING_OFF);
                    // ROM: s2.asm:63064 - move.w #$60,objoff_2A(a0)
                    waitTimer = FLEE_UP_DURATION;
                }
            }
            case 4 -> {
                // ROM: s2.asm:63071-63087 (loc_2F442 - SubA_4: Flying off)
                waitTimer--;
                if (waitTimer < 0) {
                    state.renderFlags |= 1;
                    state.x += VELOCITY_RIGHT_FLEE;
                } else {
                    state.y += VELOCITY_UP_FLEE;
                }
                syncFixedFromPosition();

                Camera camera = services().camera();
                if (camera.getMaxX() < CAMERA_MAX_X_TARGET) {
                    // ROM: s2.asm:63596-63599 (loc_2F460) -
                    //   cmpi.w #$2AB0,(Camera_Max_X_pos).w / bhs.s loc_2F46E
                    //   addq.w #2,(Camera_Max_X_pos).w
                    // The ROM adds 2 to the boundary word ITSELF from inside the boss's own
                    // object pass; Camera_Max_X_pos carries no target/easing indirection here.
                    // Routing this through setMaxXTarget() instead deferred every +2 step to
                    // the NEXT frame's boundary easing (Camera.updateBoundaryEasing runs ahead
                    // of the object pass), leaving the engine's right boundary - and hence
                    // camera_x - a permanent one-step (2px) behind the ROM for the whole flee.
                    // Write the boundary immediately, exactly as the ROM does.
                    camera.setMaxX((short) (camera.getMaxX() + 2));
                } else if (!isOnScreen()) {
                    // ROM: s2.asm:63094-63100 (loc_2F46E) - once the camera has eased to
                    // its target max-X and the flying body is off-screen, the ROM calls
                    // DeleteObject on the main body (and its top part). Destroying the body
                    // cascades to the flying children (top, propeller, and the separated
                    // spike, which the ROM likewise self-deletes once its parent slot is no
                    // longer the boss). The wrecked ground vehicle is intentionally NOT
                    // deleted - it persists as debris (see EHZBossGroundVehicle).
                    // FLAG_FINISHED stops body rendering on this final frame before removal.
                    setCustomFlag(OBJOFF_FLAGS, getCustomFlag(OBJOFF_FLAGS) | FLAG_FINISHED);
                    setDestroyed(true);
                }
            }
        }
    }

    private void syncFixedFromPosition() {
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
    }

    // applyObjectMoveAndFall() and spawnDefeatExplosion() inherited from AbstractBossInstance

    private void reloadPropeller() {
        for (BossChildComponent child : childComponents) {
            if (child instanceof EHZBossPropeller propeller && !propeller.isDestroyed()) {
                propeller.reload();
                return;
            }
        }
        spawnBossChild(() -> new EHZBossPropeller(this));
    }

    private void spawnEggPrison() {
        if (services().objectManager() == null) {
            return;
        }
        ObjectSpawn prisonSpawn = new ObjectSpawn(
                INITIAL_X,
                FLOOR_Y - 0x20,
                Sonic2ObjectIds.EGG_PRISON,
                0,
                0,
                false,
                0);
        spawnChild(() -> new EggPrisonObjectInstance(prisonSpawn, "Egg Prison"));
    }

    @Override
    protected int getInitialHitCount() {
        return 8; // Standard Sonic 2 boss hit count
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        // No-op: spike separation handled by spike component.
    }

    @Override
    protected int getCollisionSizeIndex() {
        // ROM: s2.asm:62753 - move.b #$F,collision_flags(a0)
        // Touch_Sizes table index $0F (s2.asm:84590): 24x24 pixels
        return 0x0F;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    /**
     * Obj56 is a read-once-at-head dispatcher, so its defeat routine first runs next frame.
     *
     * <p>{@code loc_2F262} (docs/s2disasm/s2.asm:63420-63424) reads
     * {@code routine_secondary(a0)} exactly once into d0 and {@code jmp}s through
     * {@code off_2F270}; the selected handler never re-reads the field. The defeat write
     * {@code move.b #6,routine_secondary(a0)} (docs/s2disasm/s2.asm:63665, in
     * {@code loc_2F4EE}) is reached from {@code loc_2F4A6} (:63632-63636), which
     * {@code loc_2F304} / Sub4 calls at :63486 - i.e. strictly downstream of that head
     * read. So the ROM finishes the frame in Sub4 and only dispatches Sub6 on the
     * following frame.
     *
     * <p>The engine runs touch responses before this object's own {@code update()}, so
     * without the deferral the defeat sub-routine ran on the defeat frame itself. That
     * pulled the whole defeat chain - and with it the {@code LoadPLC_AnimalExplosion}
     * submission at {@code loc_2F424} (docs/s2disasm/s2.asm:63579 ->
     * :21893-21901) - one frame early relative to the ROM.
     */
    @Override
    protected boolean defeatDeferralAppliesToThisBoss() {
        return true;
    }

    @Override
    protected void onDefeatStarted() {
        if (!isDefeatEntryPrepared() && !Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE)) return;
        // ROM: s2.asm:63149-63162 (loc_2F4EE - boss defeated)
        // ROM: s2.asm:63152 - move.b #6,routine_secondary(a0)
        state.routineSecondary = SUB6_DEFEATED_FALLING;
        // ROM: s2.asm:63154 - move.w #-$180,y_vel(a0)
        state.yVel = -0x180;
        // ROM: s2.asm:63153 - move.w #0,x_vel(a0)
        state.xVel = 0;
        defeatTimer = DEFEAT_TIMER_START;

        // Clear FLAG_ACTIVE and set FLAG_SPIKE_SEPARATED
        int flags = getCustomFlag(OBJOFF_FLAGS);
        flags &= ~FLAG_ACTIVE;  // Clear FLAG_ACTIVE (boss no longer active)
        flags |= FLAG_SPIKE_SEPARATED;  // Set spike separated flag
        setCustomFlag(OBJOFF_FLAGS, flags);

        for (BossChildComponent child : childComponents) {
            if (child instanceof EHZBossVehicleTop top) {
                top.setFlyingOff();
                break;
            }
        }
    }

    @Override
    protected boolean prepareDefeatEntry() {
        return Sonic2PlcRequests.append(services(), Sonic2Constants.PLC_CAPSULE);
    }

    /**
     * Called by wheels to contribute their Y position.
     */
    public void addToWheelYAccumulator(int wheelY) {
        wheelYAccumulator += wheelY;
    }

    public int getInitialY() {
        return INITIAL_Y;
    }

    @Override
    public int getCollisionFlags() {
        // ROM: SUB0 sets collision_flags to 0 every frame (s2.asm:62922)
        // ROM: SUB2 doesn't change collision_flags (stays 0)
        // ROM: SUB2→SUB4 transition sets collision_flags to $F (s2.asm:62992)
        // ROM: SUB4 keeps collision_flags at $F unless hit

        // Explicitly check state - no collision during defeat sequence
        if (state.routineSecondary >= 0x06) {  // SUB6 or later (defeated/fleeing)
            return 0;
        }

        // Check defeated FIRST
        if (state.defeated) {
            return 0;
        }

        // Check invulnerable
        if (state.invulnerable) {
            return 0;
        }

        // Check FLAG_ACTIVE
        int flags = getCustomFlag(OBJOFF_FLAGS);
        boolean active = (flags & FLAG_ACTIVE) != 0;

        if (!active) {
            return 0;
        }

        // Active and vulnerable - enable collision
        return 0xC0 | (getCollisionSizeIndex() & 0x3F);
    }

    @Override
    public int getPriorityBucket() {
        return 5;  // Behind ground vehicle (4) and Sonic (2)
    }

    @Override
    public boolean isPersistent() {
        // The boss oscillates between BOUNDARY_LEFT/RIGHT inside the locked arena,
        // travelling past the visible screen edge each pass. It is event-spawned
        // (not respawn-tracked), so if the generic out-of-range cull unloaded it
        // — or any of its children (the leading drillcone crosses the threshold
        // first) — nothing would rebuild the missing parts. Stay active for the
        // whole fight; child parts inherit this via AbstractBossChild.
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Don't render main body if finished/off-screen
        if ((getCustomFlag(OBJOFF_FLAGS) & FLAG_FINISHED) != 0) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Render main flying vehicle bottom (frame 15)
        boolean flipped = (state.renderFlags & 1) != 0;
        renderer.drawFrameIndex(15, state.x, state.y, flipped, false);
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic2Sfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic2Sfx.BOSS_EXPLOSION.id;
    }

    @Override
    protected int getBossExplosionObjectId() {
        return com.openggf.game.sonic2.constants.Sonic2ObjectIds.BOSS_EXPLOSION;
    }
}
