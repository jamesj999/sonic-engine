package com.openggf.game.sonic2.objects;

import com.openggf.camera.Camera;
import com.openggf.game.PlayableEntity;
import com.openggf.game.save.SaveReason;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.sonic2.Sonic2LevelEventManager;
import com.openggf.game.sonic2.Sonic2ZoneFeatureProvider;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.level.Level;
import com.openggf.level.ParallaxManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0xB2 - Tornado (SCZ/WFZ scripted biplane sequence).
 *
 * Disassembly reference:
 * - ObjB2: docs/s2disasm/s2.asm (ObjB2, loc_3A79E onward)
 * - ObjC3 smoke child behavior: docs/s2disasm/s2.asm (ObjC3)
 */
public class TornadoObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, TouchResponseProvider, TouchResponseListener,
        RewindRecreatable {

    // ------------------------------------------------------------------------
    // Subtypes / routines (routine = subtype - 0x4E)
    // ------------------------------------------------------------------------

    private static final int SUBTYPE_SCZ_MAIN = 0x50;      // routine 2
    private static final int SUBTYPE_WFZ_START = 0x52;     // routine 4
    private static final int SUBTYPE_WFZ_END = 0x54;       // routine 6
    private static final int SUBTYPE_INVISIBLE_GRABBER = 0x56; // routine 8
    private static final int SUBTYPE_BLINKER = 0x58;       // routine A
    private static final int SUBTYPE_UNUSED_MOVER = 0x5A;  // routine C
    private static final int SUBTYPE_THRUSTER = 0x5C;      // routine E

    private static final int ROUTINE_SCZ_MAIN = 0x02;
    private static final int ROUTINE_WFZ_START = 0x04;
    private static final int ROUTINE_WFZ_END = 0x06;
    private static final int ROUTINE_INVISIBLE_GRABBER = 0x08;
    private static final int ROUTINE_BLINKER = 0x0A;
    private static final int ROUTINE_UNUSED_MOVER = 0x0C;
    private static final int ROUTINE_THRUSTER = 0x0E;

    // ------------------------------------------------------------------------
    // Shared movement / collision constants
    // ------------------------------------------------------------------------

    private static final SolidObjectParams TORNADO_SOLID_PARAMS = new SolidObjectParams(0x1B, 8, 9);
    private static final int SCZ_CAMERA_FINISH_X = 0x1400;
    private static final int SCZ_PLAYER_FINISH_X = 0x1568;
    private static final int SCZ_PLAYER_PUSH_MARGIN = 0x11;
    private static final int SCZ_CAMERA_MAX_OFFSET = 0x40;

    private static final int VERTICAL_LIMIT_UP_OFFSET = 0x34;
    private static final int VERTICAL_LIMIT_DOWN_OFFSET = 0xA8;

    private static final int PLAYER_HORIZONTAL_CLAMP = 0x10;
    private static final int PLAYER_INERTIA_CLAMP = 0x900;
    // ------------------------------------------------------------------------
    // WFZ start constants (routine 4)
    // ------------------------------------------------------------------------

    private static final int WFZ_START_MAIN_TIMER = 0xC0;
    private static final int WFZ_SHOT_DOWN_TIMER = 0x60;
    private static final int WFZ_SMOKE_PERIOD = 0x0E;
    private static final int WFZ_SCATTER_SFX_MASK = 0x1F;

    // ------------------------------------------------------------------------
    // WFZ end constants (routine 6)
    // ------------------------------------------------------------------------

    private static final int WFZ_WAIT_PLAYER_Y = 0x5EC;
    private static final int WFZ_WAIT_FRAMES = 0x40;
    private static final int WFZ_LEADER_EDGE_X = 0x2E30;
    private static final int WFZ_PLANE_WAIT_BG_X = 0x37E;
    private static final int WFZ_PREPARE_TO_JUMP_FRAMES = 0x30;
    private static final int WFZ_JUMP_TIMER_NORMAL = 0x38;
    private static final int WFZ_JUMP_TIMER_SUPER = 0x28;
    private static final int WFZ_LANDED_WAIT_FRAMES = 0x100;
    private static final int WFZ_LANDED_PLAYER_Y_OFFSET = 0x1C;
    private static final int WFZ_JUMP_TO_SHIP_START = 0x437;
    private static final int WFZ_JUMP_TO_SHIP_END = 0x447;
    private static final int WFZ_SPAWN_EXTRA_CHILDREN_AT = 0x460;
    private static final int WFZ_START_DEZ_AT = 0x9C0;
    private static final int WFZ_GRABBER_COLLISION_FLAGS = 0x40 | 0x07;

    // Docking velocity script (word_3AC16 / byte_3AC2A).
    private static final int[] WFZ_DOCK_THRESHOLDS = {
            0x1E0, 0x260, 0x2A0, 0x2C0, 0x300, 0x3A0, 0x3F0, 0x460, 0x4A0, 0x580
    };
    private static final byte[] WFZ_DOCK_VELOCITY_BYTES = {
            (byte) 0xFF, (byte) 0xFF,
            0x01, 0x00,
            0x00, 0x01,
            0x01, (byte) 0xFF,
            0x01, 0x01,
            0x01, (byte) 0xFF,
            (byte) 0xFF, 0x01,
            (byte) 0xFF, (byte) 0xFF,
            (byte) 0xFF, 0x01,
            (byte) 0xFE, 0x00,
            0x00, 0x00
    };

    // Level layout patch written when Sonic lands on the plane in WFZ end.
    private static final int LAYOUT_ROW_BYTES = 256;
    private static final int LAYOUT_LAYER_WIDTH = 128;
    private static final int[] LAYOUT_PATCH_OFFSETS = {0x0D2, 0x1D2, 0xBD6, 0xCD6};
    private static final int[][] LAYOUT_PATCH_BYTES = {
            {0x50, 0x1F, 0x00, 0x25},
            {0x25, 0x00, 0x1F, 0x50},
            {0x50, 0x1F, 0x00, 0x25},
            {0x25, 0x00, 0x1F, 0x50}
    };

    // Animation scripts (Ani_objB2_a / Ani_objB2_b).
    private static final int[] MAIN_ANIM_A = {0, 1, 2, 3};
    private static final int[] MAIN_ANIM_B = {4, 5, 6, 7};
    private static final int[] THRUSTER_ANIM = {1, 2};

    // Input masks for scripted control (Ctrl_1_Logical writes).
    private static final int INPUT_RIGHT = AbstractPlayableSprite.INPUT_RIGHT;
    private static final int INPUT_UP = AbstractPlayableSprite.INPUT_UP;
    private static final int INPUT_DOWN = AbstractPlayableSprite.INPUT_DOWN;
    private static final int INPUT_JUMP = AbstractPlayableSprite.INPUT_JUMP;

    // ------------------------------------------------------------------------
    // State
    // ------------------------------------------------------------------------

    private int subtype;
    private final TornadoObjectInstance parent;

    private int routine;
    private int routineSecondary;
    /** ROM {@code routine == 0}: {@code ObjB2_Init} has not executed yet. */
    private boolean initRoutinePending;

    private int currentX;
    private int currentY;
    private int xPosFixed8;
    private int yPosFixed8;
    private int xVel;
    private int yVel;

    private int mappingFrame;
    private int animId;
    private int animFrameIndex;

    /**
     * Read-only view of ObjB2's SST in ROM byte layout, for comparison against a
     * recorded {@code s2_tornado_state} row. Comparison-only.
     *
     * <p>The engine models the ROM's reused scratch region semantically. In the
     * SCZ routine, {@code objoff2E} is the {@code p1_standing} transition byte,
     * {@code objoff2F}/{@code objoff30} are {@code st.b}/{@code clr.b} flags,
     * and {@code objoff31} is the vertical countdown (s2.asm:78827-78839,
     * 79382-79390). In the WFZ-end routine, {@code objoff2E}/{@code objoff2F}
     * are instead the high/low bytes of one word: the leader-wait counter, then
     * the jump countdown (s2.asm:78972-78979, 79041-79068).
     */
    public record Snapshot(
            int x, int y, int ySub, int yVel,
            int routine, int routineSecondary, int statusByte,
            int objoff2E, int objoff2F, int objoff30, int objoff31) {}

    private static final int P1_STANDING_BIT = 0x08;

    public Snapshot snapshot() {
        return new Snapshot(
                currentX & 0xFFFF,
                currentY & 0xFFFF,
                (yPosFixed8 & 0xFF) << 8,
                yVel & 0xFFFF,
                initRoutinePending ? 0 : routine & 0xFF,
                routineSecondary & 0xFF,
                lastMainStanding ? P1_STANDING_BIT : 0,
                snapshotObjoff2E(),
                snapshotObjoff2F(),
                moveVert2Active ? 0xFF : 0,
                moveVertTimer & 0xFF);
    }

    private int snapshotObjoff2E() {
        if (routine == ROUTINE_WFZ_END) {
            return (wfzEndObjoff2EWord() >>> 8) & 0xFF;
        }
        return standingTransition ? P1_STANDING_BIT : 0;
    }

    private int snapshotObjoff2F() {
        if (routine == ROUTINE_WFZ_END) {
            return wfzEndObjoff2EWord() & 0xFF;
        }
        return moveVertActive ? 0xFF : 0;
    }

    private int wfzEndObjoff2EWord() {
        // ObjB2_Wait_Leader_position increments the word at objoff_2E until
        // $40. ObjB2_Prepare_to_jump later replaces that same word with the
        // jump countdown as it enters state 8 (s2.asm:78972-78979,
        // 79042-79052). The Java model keeps those two lifetimes separately.
        return routineSecondary < 8 ? leaderWaitCounter : jumpTimer;
    }

    // SCZ movement helpers (objoff_2E/$2F/$30/$31/$38 equivalents).
    private boolean standingTransition;
    private boolean moveVertActive;
    private boolean moveVert2Active;
    private int moveVertTimer;
    private int smoothOffsetX;
    private boolean lastMainStanding;

    // WFZ start / end scratch fields.
    private int scriptTimer;
    private int smokeSpawnTimer;
    private int leaderWaitCounter;
    private int jumpTimer;
    private int dockVelocityIndex;
    private short previousWfzDockPlayerXSpeed;
    private short previousWfzDockPlayerYSpeed;
    private short lastWfzDockPlayerXSpeed;
    private short lastWfzDockPlayerYSpeed;

    // ObjB2_Animate_Pilot state (s2.asm:79538-79556):
    // objoff_37 = 9-frame countdown, objoff_36 = pilot-frame table cursor.
    private int pilotTimer;
    private int pilotTableCursor;

    // Render/solid flags for current frame.
    private boolean renderThisFrame;
    private boolean solidActive;
    private boolean highPriority;

    // Script control ownership/cleanup.
    private boolean ownsPlayerControl;
    private boolean sczTransitionRequested;
    private boolean dezTransitionRequested;
    private boolean levelLayoutPatched;
    private boolean spawnedWfzDockChildren;
    private boolean grabberCollisionProperty;
    private boolean blinkerMiscBit;
    private TornadoObjectInstance thrusterFollowerChild;

    public TornadoObjectInstance(ObjectSpawn spawn) {
        this(spawn, null);
    }

    private TornadoObjectInstance(ObjectSpawn spawn, TornadoObjectInstance parent) {
        super(spawn, "Tornado");
        this.subtype = spawn.subtype() & 0xFF;
        this.parent = parent;

        this.currentX = spawn.x();
        this.currentY = spawn.y();
        syncFixedFromPosition();

        // ROM init: routine = subtype - $4E (ObjB2_Init, docs/s2disasm/s2.asm:78799-78813).
        // A freshly allocated SST slot has routine 0, so ROM spends the object's
        // first executed frame in ObjB2_Init and only reaches the main routine on
        // the next frame. This flag is that routine-0 state; the derived routine is
        // stored up front so lifecycle classification (persistence, priority) sees
        // the object's real kind from the moment it is created.
        this.initRoutinePending = true;
        this.routine = Math.max(0, subtype - 0x4E);
        this.routineSecondary = 0;
        this.mappingFrame = 0;
        this.animId = 0;
        this.animFrameIndex = 0;
        this.blinkerMiscBit = false;
    }

    @Override
    public TornadoObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn rewindSpawn = ctx.spawn();
        int rewindSubtype = rewindSpawn.subtype() & 0xFF;
        if (isParentLinkedChildSubtype(rewindSubtype)) {
            TornadoObjectInstance liveParent = nearestLiveTornadoParent(ctx);
            return liveParent == null ? null : new TornadoObjectInstance(rewindSpawn, liveParent);
        }
        return new TornadoObjectInstance(rewindSpawn);
    }

    private static boolean isParentLinkedChildSubtype(int subtype) {
        return subtype == SUBTYPE_INVISIBLE_GRABBER || subtype == SUBTYPE_THRUSTER;
    }

    private static TornadoObjectInstance nearestLiveTornadoParent(RewindRecreateContext ctx) {
        ObjectManager manager = ctx.objectServices() != null ? ctx.objectServices().objectManager() : null;
        if (manager == null) {
            return null;
        }
        TornadoObjectInstance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ObjectInstance object : manager.getActiveObjects()) {
            if (object instanceof TornadoObjectInstance tornado
                    && !tornado.isDestroyed()
                    && !isParentLinkedChildSubtype(tornado.subtype)) {
                int distance = Math.abs(tornado.getX() - ctx.spawn().x());
                if (distance < nearestDistance) {
                    nearest = tornado;
                    nearestDistance = distance;
                }
            }
        }
        return nearest;
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isHighPriority() {
        return highPriority;
    }

    @Override
    public int getPriorityBucket() {
        if (routine == ROUTINE_THRUSTER) {
            return RenderPriority.clamp(3);
        }
        if (routine == ROUTINE_UNUSED_MOVER) {
            return RenderPriority.clamp(6);
        }
        if (routine == ROUTINE_INVISIBLE_GRABBER) {
            return RenderPriority.clamp(RenderPriority.MIN);
        }
        return RenderPriority.clamp(4);
    }

    @Override
    public boolean isPersistent() {
        return routine == ROUTINE_SCZ_MAIN
                || routine == ROUTINE_WFZ_START
                || routine == ROUTINE_WFZ_END
                || routine == ROUTINE_INVISIBLE_GRABBER
                || routine == ROUTINE_BLINKER
                || routine == ROUTINE_THRUSTER;
    }

    public boolean isRideStartPreludeObject() {
        return isSczRideStartPreludeObject() || isWfzStartRideStartPreludeObject();
    }

    public boolean isSczRideStartPreludeObject() {
        return routine == ROUTINE_SCZ_MAIN;
    }

    public boolean isWfzStartRideStartPreludeObject() {
        return routine == ROUTINE_WFZ_START;
    }

    /**
     * Native bootstrap for SCZ/WFZ level-select trace starts where the title-card
     * object prelude has already placed Sonic on the Tornado before the first
     * compared Level_MainLoop frame.
     *
     * <p>ROM relation at the replay seed point: ObjB2 is one pixel ahead of
     * Sonic and its center is $1C below Sonic's center (s2.asm:78301-78305,
     * 78378-78385 call SolidObject with d1=$1B,d2=8,d3=9; frame 0 then carries
     * the rider via the normal platform delta). This method primes only that
     * native pre-frame relation; it does not read recorded object snapshots.
     */
    public void primeRideStart(short playerStartX, short playerStartY) {
        primeRideStart(playerStartX, playerStartY, 0);
    }

    public void primeRideStart(short playerStartX, short playerStartY, int ySubpixel) {
        currentX = (playerStartX & 0xFFFF) + 1;
        currentY = (playerStartY & 0xFFFF) + 0x1C;
        xPosFixed8 = currentX << 8;
        yPosFixed8 = (currentY << 8) | (ySubpixel & 0xFF);
        yVel = 0;
        standingTransition = false;
        lastMainStanding = true;
        moveVertActive = false;
        moveVert2Active = false;
        // ROM ObjB2_Move_vert/ObjB2_Move_vert2 treat objoff_31 as an expired
        // byte countdown: subq.b #1 followed by bpl (s2.asm:79388-79390,
        // 79460-79463). The native ride-start prelude therefore exposes -1
        // (0xFF) until a vertical move loads $14 or $2B.
        moveVertTimer = 0xFF;
    }

    /**
     * Compensates for the engine collapsing the inner
     * {@code ObjB2_Main_WFZ_Start} {@code routine_secondary} init step
     * (s2.asm:78879-78893, 78896-78902) into a single engine init frame. During
     * the S2 title-card object prelude ROM runs one fewer main-routine move than
     * the engine, leaving {@link #scriptTimer} one less than the ROM's
     * frame-(-1) snapshot value. This restores parity so the WFZ_Start_main
     * transition fires on the same trace frame as ROM (s2.asm:78903-78924).
     *
     * <p>Only relevant for the WFZ_START routine; SCZ_MAIN does not use a
     * {@code routine_secondary} init step and has no timer to compensate.
     */
    public void compensateForCollapsedWfzInit() {
        if (routine == ROUTINE_WFZ_START && routineSecondary == 2) {
            scriptTimer++;
        }
    }

    /**
     * {@code ObjB2_Init} (docs/s2disasm/s2.asm:78799-78813). The object's first
     * executed frame runs the routine-0 entry only: it derives
     * {@code routine = subtype - $4E}, applies the {@code Player_mode == 2}
     * mapping/animation patch, then tail-jumps to {@code DisplaySprite}. No main
     * routine body — and therefore no {@code ObjB2_Animate_Pilot} tick — runs on
     * that frame, so the pilot's 9-frame DPLC cadence starts one frame after the
     * object appears.
     *
     * @return {@code true} when this frame was consumed by the init routine.
     */
    private boolean runPendingInitRoutine() {
        if (!initRoutinePending) {
            return false;
        }
        initRoutinePending = false;

        // cmpi.w #2,(Player_mode).w / cmpi.b #8,d0 / bhs — the mapping patch
        // applies only to the visible plane routines below $8 (s2.asm:78805-78811).
        if (routine < ROUTINE_INVISIBLE_GRABBER
                && com.openggf.game.session.ActiveGameplayTeamResolver
                        .resolvePlayerCharacter(services().configuration())
                        == com.openggf.game.PlayerCharacter.TAILS_ALONE) {
            mappingFrame = 4;
            animId = 1;
        }
        // jmpto JmpTo45_DisplaySprite (s2.asm:78812-78813).
        renderThisFrame = true;
        return true;
    }

    // ------------------------------------------------------------------------
    // ObjB2_Animate_Pilot (docs/s2disasm/s2.asm:79536-79599)
    // ------------------------------------------------------------------------

    /**
     * Pilot DPLC frame tables, {@code Sonic_pilot_frames} (4 entries) and
     * {@code Tails_pilot_frames} (24 entries), docs/s2disasm/s2.asm:79566-79599.
     * The table byte is a DPLC frame index handed straight to
     * {@code LoadSonicDynPLC_Part2} / {@code LoadTailsDynPLC_Part2}.
     */
    private static final int[] SONIC_PILOT_FRAMES = {0x2D, 0x2E, 0x2F, 0x30};
    private static final int[] TAILS_PILOT_FRAMES = {
            0x10, 0x10, 0x10, 0x10, 0x01, 0x02, 0x03, 0x02,
            0x01, 0x01, 0x10, 0x10, 0x10, 0x10, 0x01, 0x02,
            0x03, 0x02, 0x01, 0x01, 0x04, 0x04, 0x01, 0x01
    };

    /**
     * {@code ObjB2_Animate_Pilot} (docs/s2disasm/s2.asm:79536-79565). Runs as the
     * first instruction of {@code ObjB2_Main_SCZ} (s2.asm:78815-78816),
     * {@code ObjB2_Main_WFZ_Start} (s2.asm:78879-78880) and
     * {@code ObjB2_Main_WFZ_End} (s2.asm:78951-78952) — this is the object's own
     * routine value gating it, exactly as the ROM does.
     *
     * <p>Every ninth frame it advances {@code objoff_36} through the pilot frame
     * table and tail-jumps into the character bank's {@code *_Part2} DPLC entry
     * point with the table byte in d0. That entry point dedupes against the
     * bank's single {@code Sonic_LastLoadedDPLC} / {@code Tails_LastLoadedDPLC}
     * word (s2.asm:41659-41697, 38829-38862, 26039-26041), which is why the
     * submission goes through the playable bank's shared owner and not a
     * pilot-private one — the Tornado and a live Tails coexist on the WFZ->DEZ
     * handoff and must not double-submit.
     *
     * <p>In SCZ/WFZ/DEZ {@code InitPlayers} omits Obj02 entirely
     * (s2.asm:5177-5198), so the pilot is the only submitter into the Tails bank
     * there. That is a consequence of ROM object placement, not a zone rule.
     */
    private void animatePilot() {
        pilotTimer--;
        if (pilotTimer >= 0) {
            return;
        }
        pilotTimer = 8;

        // cmpi.w #2,(Player_mode).w — Player_mode 2 is the Tails-alone team,
        // where the Tornado's pilot is Sonic and uses the Sonic art bank
        // (s2.asm:79549-79552, 79561-79565).
        boolean tailsAlone = com.openggf.game.session.ActiveGameplayTeamResolver
                .resolvePlayerCharacter(services().configuration())
                == com.openggf.game.PlayerCharacter.TAILS_ALONE;
        int[] table = tailsAlone ? SONIC_PILOT_FRAMES : TAILS_PILOT_FRAMES;

        int cursor = pilotTableCursor + 1;
        if (cursor >= table.length) {
            cursor = 0;
        }
        pilotTableCursor = cursor;

        var owner = services().levelManager()
                .playerArtDplcOwner(tailsAlone ? "sonic" : "tails");
        if (owner != null) {
            owner.observe(table[cursor]);
        }
    }

    /**
     * Advances the pilot animation for one title-card iteration the engine
     * replays without a full object pass.
     *
     * <p>The ROM's title-card loop body is {@code jsr (RunObjects).l}
     * (docs/s2disasm/s2.asm:5060-5066), so every iteration executes
     * {@code ObjB2_Main_SCZ}, whose first instruction is
     * {@code ObjB2_Animate_Pilot} (docs/s2disasm/s2.asm:78815-78816,
     * 79536-79556). An iteration that reproduces only the player-side effects
     * still owes the pilot its tick, or the whole 9-frame cadence -- and the
     * dynamic-art submissions it drives -- stays that many frames late.
     *
     */
    public void advanceOmittedPresentationPilotFrame() {
        animatePilot();
    }

    /**
     * Consumes the object's routine-0 frame ({@code ObjB2_Init},
     * docs/s2disasm/s2.asm:78799-78813) for a level-load-present Tornado whose
     * first execution happened during the title-card object prelude, before the
     * first replayed object pass. The init frame reaches no main routine and so
     * owes no {@code ObjB2_Animate_Pilot} tick; without this the first replayed
     * pass would spend itself on init and start the pilot cadence a frame late.
     * A Tornado spawned mid-level by the object-position loader keeps its init
     * frame and runs it on its own first pass.
     */
    public void consumePendingInitRoutine() {
        initRoutinePending = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        renderThisFrame = false;
        solidActive = false;
        highPriority = false;

        if (runPendingInitRoutine()) {
            return;
        }

        switch (routine) {
            // ObjB2_Animate_Pilot is the first instruction of each of these three
            // routine bodies (s2.asm:78815-78816, 78879-78880, 78951-78952).
            case ROUTINE_SCZ_MAIN -> {
                animatePilot();
                updateSczMain(player);
            }
            case ROUTINE_WFZ_START -> {
                animatePilot();
                updateWfzStart(vIntRunCount, player);
                applyDeleteOffScreenCulling();
            }
            case ROUTINE_WFZ_END -> {
                animatePilot();
                updateWfzEnd(player);
            }
            case ROUTINE_INVISIBLE_GRABBER -> updateInvisibleGrabber(player);
            case ROUTINE_BLINKER -> updateBlinker();
            case ROUTINE_UNUSED_MOVER -> updateUnusedMover();
            case ROUTINE_THRUSTER -> updateThrusterFollower();
            default -> {
                // Unknown subtype: keep object inert.
            }
        }
    }

    // ------------------------------------------------------------------------
    // Routine 2: ObjB2_Main_SCZ
    // ------------------------------------------------------------------------

    private void updateSczMain(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        advanceMainAnimation();
        renderThisFrame = true;
        solidActive = true;
        highPriority = player.isHighPriority();

        // ObjB2_Move_with_player reads Sonic's live Status_OnObj bit before
        // the inline SolidObject call refreshes this object's own standing bit
        // (s2.asm:78298-78306, 78816-78823). The engine can still expose a
        // marker left by another solid in that pass, while lastMainStanding
        // records only ObjB2's own checkpoint result. For an airborne player
        // with no horizontal launch velocity, model the release shape with
        // Move_below_player and its decaying objoff_38 relation. A moving
        // airborne player remains on the live Status_OnObj path; the split is
        // derived from the ROM jump state (s2.asm:37056-37058), not a trace
        // route or frame.
        boolean stationarySolidRelease = player.isOnObject() && player.getAir()
                && !lastMainStanding && player.getXSpeed() == 0;
        if (stationarySolidRelease) {
            smoothOffsetX = currentX - player.getCentreX();
        }
        boolean playerOnObjectAtEntry = (player.isOnObject() && !stationarySolidRelease)
                || lastMainStanding;
        boolean objectStandingBeforeCheckpoint = lastMainStanding;
        moveWithPlayer(player, playerOnObjectAtEntry);
        if (stationarySolidRelease) {
            // ObjB2's next below-player pass has already consumed the
            // release offset; the ROM's objoff_38 then decays from its
            // cleared value rather than repeating this one-frame alignment.
            smoothOffsetX = 0;
        }

        PlayerSolidContactResult contact = checkpoint(player);
        boolean mainStandingNow = contact.standingNow();
        standingTransition = (mainStandingNow != objectStandingBeforeCheckpoint);

        // ObjB2_Move_obbey_player rereads Sonic's live Status_OnObj bit after
        // SolidObject (s2.asm:78881-78886). Another object may own that bit,
        // while lastMainStanding must still track ObjB2's own contact latch.
        boolean playerOnObjectAfterCheckpoint = player.isOnObject() || mainStandingNow;
        moveObeyPlayer(player, playerOnObjectAfterCheckpoint);
        lastMainStanding = mainStandingNow;

        // ROM: Camera_Min_X_pos = Camera_X_pos
        Camera camera = services().camera();
        int cameraX = camera.getX();
        camera.setMinX((short) cameraX);

        // Keep player near front edge of camera while riding Tornado.
        int playerX = player.getCentreX();
        if (playerX <= cameraX + SCZ_PLAYER_PUSH_MARGIN) {
            NativePositionOps.writeXPosPreserveSubpixel(player, playerX + 1);
            playerX++;
        }

        if (cameraX >= SCZ_CAMERA_FINISH_X) {
            if (playerX >= SCZ_PLAYER_FINISH_X) {
                if (!sczTransitionRequested) {
                    services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
                    services().requestZoneAndAct(Sonic2ZoneConstants.ZONE_WFZ, 0, true);
                    sczTransitionRequested = true;
                }
            } else {
                applyScriptInput(player, INPUT_RIGHT, true);
            }
            camera.setMaxX((short) cameraX);
            return;
        }

        clearScriptInput(player);
        camera.setMaxX((short) (cameraX - SCZ_CAMERA_MAX_OFFSET));
    }

    // ------------------------------------------------------------------------
    // Routine 4: ObjB2_Main_WFZ_Start
    // ------------------------------------------------------------------------

    private void updateWfzStart(int vIntRunCount, AbstractPlayableSprite player) {
        advanceMainAnimation();
        renderThisFrame = true;
        solidActive = true;
        highPriority = true;

        switch (routineSecondary) {
            case 0 -> {
                // ObjB2_Main_WFZ_Start_init
                routineSecondary = 2;
                scriptTimer = WFZ_START_MAIN_TIMER;
                xVel = 0x100;
            }
            case 2 -> {
                // ObjB2_Main_WFZ_Start_main
                scriptTimer--;
                if (scriptTimer > 0) {
                    objectMove();
                    applyTornadoParallaxVelocity();
                    checkpoint(player);
                    clampClosestPlayerHorizontal();
                    return;
                }
                routineSecondary = 4;
                scriptTimer = WFZ_SHOT_DOWN_TIMER;
                smokeSpawnTimer = 1;
                xVel = 0x100;
                yVel = 0x100;
            }
            case 4 -> {
                // ObjB2_Main_WFZ_Start_shot_down: ROM plays SndID_Scatter ($EB) every
                // $20 frames as the Tornado is gunned down (s2.asm:78890-78895), not the
                // ring-loss/RingSpill sound ($C6). SndID_Scatter aliases SndID_LaserFloor.
                if ((vIntRunCount & WFZ_SCATTER_SFX_MASK) == 0) {
                    services().playSfx(Sonic2Sfx.LASER_FLOOR.id);
                }

                scriptTimer--;
                if (scriptTimer >= 0) {
                    alignPlaneAndSolid();
                    checkpoint(player);
                    updateShotDownSmoke();
                    return;
                }

                routineSecondary = 6;
                releasePlayersFromPlatform(player);
            }
            case 6 -> {
                // ObjB2_Main_WFZ_Start_fall_down
                objectMove();         // Extra ObjectMove before shared align path (ROM exact flow)
                alignPlaneAndSolid();
                checkpoint(player);
                updateShotDownSmoke();
            }
            default -> {
                // No-op.
            }
        }
    }

    private void updateShotDownSmoke() {
        smokeSpawnTimer--;
        if (smokeSpawnTimer != 0) {
            return;
        }
        smokeSpawnTimer = WFZ_SMOKE_PERIOD;
        spawnSmokeObject();
    }

    // ------------------------------------------------------------------------
    // Routine 6: ObjB2_Main_WFZ_End
    // ------------------------------------------------------------------------

    private void updateWfzEnd(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        advanceMainAnimation();
        highPriority = true;
        previousWfzDockPlayerXSpeed = lastWfzDockPlayerXSpeed;
        previousWfzDockPlayerYSpeed = lastWfzDockPlayerYSpeed;
        lastWfzDockPlayerXSpeed = player.getXSpeed();
        lastWfzDockPlayerYSpeed = player.getYSpeed();

        switch (routineSecondary) {
            case 0 -> wfzWaitLeaderPosition(player);
            case 2 -> wfzMoveLeaderEdge(player);
            case 4 -> wfzWaitForPlane(player);
            case 6 -> wfzPrepareToJump(player);
            case 8 -> wfzJumpToPlane(player);
            case 0x0A -> wfzLandedOnPlane(player);
            case 0x0C -> wfzApproachingShip(player);
            case 0x0E -> wfzJumpToShip(player);
            case 0x10 -> wfzDockOnDez();
            default -> {
                // Unknown state.
            }
        }
    }

    private void wfzWaitLeaderPosition(AbstractPlayableSprite player) {
        if (player.getCentreY() < WFZ_WAIT_PLAYER_Y) {
            return;
        }

        applyScriptInput(player, 0, true);
        leaderWaitCounter++;
        if (leaderWaitCounter < WFZ_WAIT_FRAMES) {
            return;
        }

        routineSecondary = 2;
        currentX = 0x2E58;
        currentY = 0x66C;
        syncFixedFromPosition();
        applyWaitingAnimation(player);

        // LoadChildObject sequence from ObjB2_Wait_Leader_position.
        TornadoObjectInstance child56 = spawnTornadoChild(SUBTYPE_INVISIBLE_GRABBER, 0x3118, 0x03F0);
        if (child56 != null) {
            // No additional setup.
        }
        spawnTornadoChild(SUBTYPE_BLINKER, 0x3070, 0x03B0);
        spawnTornadoChild(SUBTYPE_BLINKER, 0x3070, 0x0430);
        thrusterFollowerChild = spawnTornadoChild(SUBTYPE_THRUSTER, 0x0000, 0x0000);
    }

    private void wfzMoveLeaderEdge(AbstractPlayableSprite player) {
        if (player.getCentreX() < WFZ_LEADER_EDGE_X) {
            applyScriptInput(player, INPUT_RIGHT, true);
            return;
        }

        routineSecondary = 4;
        applyScriptInput(player, 0, true);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        applyWaitingAnimation(player);
    }

    private void wfzWaitForPlane(AbstractPlayableSprite player) {
        int bgX = getWfzBgXOffset();
        if (bgX < WFZ_PLANE_WAIT_BG_X) {
            applyWaitingAnimation(player);
            return;
        }

        routineSecondary = 6;
        xVel = 0x100;
        yVel = -0x100;
        scriptTimer = 0;
        applyWaitingAnimation(player);
    }

    private void wfzPrepareToJump(AbstractPlayableSprite player) {
        applyWaitingAnimation(player);
        scriptTimer++;
        if (scriptTimer == WFZ_PREPARE_TO_JUMP_FRAMES) {
            routineSecondary = 8;
            jumpTimer = player.isSuperSonic() ? WFZ_JUMP_TIMER_SUPER : WFZ_JUMP_TIMER_NORMAL;
            // ObjB2_Prepare_to_jump writes Ctrl_1_Logical here, after Sonic's player step
            // has already run for this frame (docs/s2disasm/s2.asm:79007-79023).
            player.setControlLocked(true);
            ownsPlayerControl = true;
        }

        alignPlaneAndSolid();
        checkpoint(player);
        renderThisFrame = true;
    }

    private void wfzJumpToPlane(AbstractPlayableSprite player) {
        scriptTimer++;
        if (jumpTimer >= 0) {
            applyScriptInput(player, INPUT_RIGHT | INPUT_JUMP, true);
        } else {
            applyScriptInput(player, 0, true);
        }
        jumpTimer--;

        solidActive = true;
        if (checkpoint(player).standingNow()) {
            routineSecondary = 0x0A;
            jumpTimer = 0x20;
            applyJumpToPlaneLayoutPatch();
        }

        // The engine reaches this state with the Tornado already at the ROM
        // frame's visible plane position. Resolve the manual landing checkpoint
        // there, then advance ObjB2 for the next frame's Landed_on_plane state.
        alignPlaneAndSolid();
        renderThisFrame = true;
    }

    private void wfzLandedOnPlane(AbstractPlayableSprite player) {
        scriptTimer++;
        if (scriptTimer >= WFZ_LANDED_WAIT_FRAMES) {
            routineSecondary = 0x0C;
            if (thrusterFollowerChild != null) {
                thrusterFollowerChild.routineSecondary = 2;
            }
        }

        // ROM ObjB2_Landed_on_plane writes Sonic's x_pos/y_pos and clears his
        // movement state before ObjB2_Align_plane moves the Tornado
        // (docs/s2disasm/s2.asm:79047-79071).
        placePlayerOnWfzPlane(player);
        alignPlaneAndSolid();
        renderThisFrame = true;
    }

    private void wfzApproachingShip(AbstractPlayableSprite player) {
        applyWaitingAnimation(player);
        if (scriptTimer >= WFZ_JUMP_TO_SHIP_START) {
            routineSecondary = 0x0E;
        }
        wfzJumpToShipCommon();
    }

    private void wfzJumpToShip(AbstractPlayableSprite player) {
        // ROM ObjB2_Jump_to_ship (s2.asm:79082-79085) forces NO animation in this
        // state, so the invisible grabber's HANG (set once on catch) survives through
        // the DEZ ascent. State $C (wfzApproachingShip) still applies WAIT; re-forcing
        // WAIT here every frame overwrote the hang animation with the standing pose.
        wfzJumpToShipCommon();
    }

    private void wfzJumpToShipCommon() {
        AbstractPlayableSprite player = getMainPlayer();
        // ROM writes Ctrl_1_Logical from ObjB2_Jump_to_ship after Sonic's player
        // step for that frame has already run (docs/s2disasm/s2.asm:79075-79089).
        // Engine forced input persists into the next player step, so latch it one
        // ObjB2 tick later than the ROM counter compare.
        boolean jumpingToShip = scriptTimer > WFZ_JUMP_TO_SHIP_START && scriptTimer < WFZ_JUMP_TO_SHIP_END;
        if (jumpingToShip) {
            applyScriptInput(player, INPUT_JUMP, true);
        } else {
            applyScriptInput(player, 0, true);
        }

        if (scriptTimer >= WFZ_SPAWN_EXTRA_CHILDREN_AT && !spawnedWfzDockChildren) {
            spawnedWfzDockChildren = true;
            ((Sonic2LevelEventManager) services().levelEventProvider()).setEventRoutine(6);
            routineSecondary = 0x10;
            spawnTornadoChild(SUBTYPE_BLINKER, 0x3090, 0x03D0);
            spawnTornadoChild(SUBTYPE_BLINKER, 0x30C0, 0x03F0);
            spawnTornadoChild(SUBTYPE_BLINKER, 0x3090, 0x0410);
        }

        boolean keepPlayerOnPlane = scriptTimer <= WFZ_JUMP_TO_SHIP_START + 1;
        if (keepPlayerOnPlane) {
            placePlayerOnWfzPlane(player);
        }
        wfzDockOnDez();
    }

    private void wfzDockOnDez() {
        if (scriptTimer >= WFZ_START_DEZ_AT) {
            if (!dezTransitionRequested) {
                services().requestSessionSave(SaveReason.PROGRESSION_SAVE);
                services().requestZoneAndAct(Sonic2ZoneConstants.ZONE_DEZ, 0, true);
                dezTransitionRequested = true;
            }
            return;
        }

        scriptTimer++;

        int thresholdIndex = dockVelocityIndex / 2;
        if (thresholdIndex < WFZ_DOCK_THRESHOLDS.length
                && scriptTimer >= WFZ_DOCK_THRESHOLDS[thresholdIndex]) {
            dockVelocityIndex += 2;
            if (dockVelocityIndex + 1 < WFZ_DOCK_VELOCITY_BYTES.length) {
                // ROM move.b to x_vel/y_vel writes signed integer speed into the high byte.
                xVel = WFZ_DOCK_VELOCITY_BYTES[dockVelocityIndex] << 8;
                yVel = WFZ_DOCK_VELOCITY_BYTES[dockVelocityIndex + 1] << 8;
            }
        }

        alignPlaneAndSolid();
        renderThisFrame = true;
    }

    private void placePlayerOnWfzPlane(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAir(false);
        // ROM clears the rolling status bit and writes y_radius after y_pos,
        // but radius changes do not move ROM centre coordinates. The engine's
        // height/radius update affects centre-derived accessors, so apply it
        // before the native position write to leave the final centre at the
        // ObjB2_Landed_on_plane value (s2.asm:79003-79014).
        player.setRolling(false);
        NativePositionOps.writeXPosPreserveSubpixel(player, currentX);
        NativePositionOps.writeYPosPreserveSubpixel(player, currentY - WFZ_LANDED_PLAYER_Y_OFFSET);
        applyWaitingAnimation(player);
    }

    // ------------------------------------------------------------------------
    // Routine 8: ObjB2_Invisible_grabber
    // ------------------------------------------------------------------------

    private void updateInvisibleGrabber(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        if (routineSecondary < 4) {
            if (grabberCollisionProperty) {
                routineSecondary += 2;
                catchPlayerWithInvisibleGrabber(player);
            }
            return;
        }

        // loc_3ACF2: keep player attached while hanging.
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        NativePositionOps.writeXPosResetSubpixel(player, currentX - 0x10);
    }

    private void catchPlayerWithInvisibleGrabber(AbstractPlayableSprite player) {
        services().camera().setYPosBias((short) ((224 / 2) + 8));

        if (player.getAir()) {
            short catchXSpeed = parent != null ? parent.previousWfzDockPlayerXSpeed : player.getXSpeed();
            short catchYSpeed = parent != null ? parent.previousWfzDockPlayerYSpeed : player.getYSpeed();
            player.move(catchXSpeed, catchYSpeed);
            if (routineSecondary == 2) {
                player.shiftY(-1);
                player.setSubpixelRaw(player.getXSubpixelRaw(), 0xEF00);
            }
        }
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        NativePositionOps.writeXPosPreserveSubpixel(player, currentX - 0x10);
        player.setDirection(Direction.LEFT);
        player.setAir(false);
        player.setRolling(false);
        player.setAnimationId(Sonic2AnimationIds.HANG);
        // ROM writes obj_control = 1 (bit 0 only, bit 7 CLEAR) here (s2.asm:79217): movement
        // is suppressed but TouchResponse still runs, so the ship plating (ObjC1) can still
        // grab the hanging player. nativeBit7FullControl set bit 7, which suppressed the whole
        // touch pass and stopped the ObjC1 grab from ever firing.
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        if (services().zoneFeatureProvider() instanceof Sonic2ZoneFeatureProvider sonic2) {
            sonic2.setWfzWindTunnelHolding(true);
        }
        player.setControlLocked(true);
        ownsPlayerControl = true;
    }

    // ------------------------------------------------------------------------
    // Routine A: loc_3AD0C (blinking child)
    // ------------------------------------------------------------------------

    private void updateBlinker() {
        // ROM bchg toggles status.npc.misc, then bne skips display when the old bit was set.
        boolean wasSet = blinkerMiscBit;
        blinkerMiscBit = !blinkerMiscBit;
        renderThisFrame = !wasSet;
    }

    // ------------------------------------------------------------------------
    // Routine C: loc_3AD2A (simple mover)
    // ------------------------------------------------------------------------

    private void updateUnusedMover() {
        objectMove();
        if (checkMarkObjGone()) {
            setDestroyed(true);
            renderThisFrame = false;
            return;
        }
        renderThisFrame = true;
    }

    // ------------------------------------------------------------------------
    // Routine E: loc_3AD42 (thruster follower child)
    // ------------------------------------------------------------------------

    private void updateThrusterFollower() {
        if (parent == null || parent.isDestroyed()) {
            setDestroyed(true);
            return;
        }

        currentX = parent.currentX - 0x0C;
        currentY = parent.currentY + 0x28;
        syncFixedFromPosition();

        renderThisFrame = true;
        if (routineSecondary >= 2) {
            advanceThrusterAnimation();
        }
    }

    // ------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!renderThisFrame || isDestroyed()) {
            return;
        }

        String key = resolveRenderArtKey();
        if (key == null) {
            return;
        }

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(key);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (routine == ROUTINE_INVISIBLE_GRABBER) {
            ctx.drawRect(currentX, currentY, 0x10, 0x20, 0.9f, 0.2f, 0.9f);
        } else {
            ctx.drawCross(currentX, currentY, 6, 0.3f, 1.0f, 1.0f);
        }
        ctx.drawWorldLabel(currentX, currentY, -1,
                String.format("B2 sub%02X r%X s%X f%d", subtype, routine, routineSecondary, mappingFrame),
                DebugColor.CYAN);
    }

    @Override
    public String traceDebugDetails() {
        if (routine == ROUTINE_WFZ_END) {
            int bgX = getWfzBgXOffset();
            return String.format("sec=%02X timer=%04X wait=%02X jump=%02X bgX=%04X xvel=%04X yvel=%04X solid=%d",
                    routineSecondary & 0xFF,
                    scriptTimer & 0xFFFF,
                    leaderWaitCounter & 0xFF,
                    jumpTimer & 0xFF,
                    bgX & 0xFFFF,
                    xVel & 0xFFFF,
                    yVel & 0xFFFF,
                    solidActive ? 1 : 0);
        }
        if (routine != ROUTINE_SCZ_MAIN) {
            return String.format("sec=%02X timer=%04X xvel=%04X yvel=%04X solid=%d",
                    routineSecondary & 0xFF,
                    scriptTimer & 0xFFFF,
                    xVel & 0xFFFF,
                    yVel & 0xFFFF,
                    solidActive ? 1 : 0);
        }
        return String.format("yfrac=%02X yv=%04X mt=%02X mv=%d mv2=%d tr=%d last=%d off=%d",
                yPosFixed8 & 0xFF,
                yVel & 0xFFFF,
                moveVertTimer & 0xFF,
                moveVertActive ? 1 : 0,
                moveVert2Active ? 1 : 0,
                standingTransition ? 1 : 0,
                lastMainStanding ? 1 : 0,
                smoothOffsetX);
    }

    private int getWfzBgXOffset() {
        if (services().levelEventProvider() instanceof Sonic2LevelEventManager events) {
            return events.getWfzEvents().getBgXOffset();
        }
        return services().parallaxManager() != null ? services().parallaxManager().getCameraBgXOffset() : 0;
    }

    // ------------------------------------------------------------------------
    // SolidObject interfaces
    // ------------------------------------------------------------------------

    @Override
    public SolidObjectParams getSolidParams() {
        return TORNADO_SOLID_PARAMS;
    }

    @Override
    public int getBalanceWidthPixels() {
        return 0x60; // ObjB2_SubObjData width_pixels, s2.asm:79602-79603
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return solidActive;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // ROM uses direct SolidObject calls from ObjB2 routines. The unified pipeline
        // handles contact resolution; this callback is intentionally no-op.
    }

    // ------------------------------------------------------------------------
    // TouchResponse interfaces
    // ------------------------------------------------------------------------

    @Override
    public int getCollisionFlags() {
        return routine == ROUTINE_INVISIBLE_GRABBER && routineSecondary < 4
                ? WFZ_GRABBER_COLLISION_FLAGS
                : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public boolean requiresContinuousTouchCallbacks() {
        // The invisible WFZ grabber polls collision_property every object pass,
        // while Touch_Special republishes it for a sustained overlap.
        return true;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        TouchResponseProfile profile = TouchResponseProvider.super.getTouchResponseProfile(multiRegionSource);
        return new TouchResponseProfile(
                profile.categoryDecodeMode(),
                profile.continuousCallbacks(),
                false,
                profile.multiRegionSource(),
                profile.shieldDeflectCapability(),
                profile.shieldReactionFlags(),
                profile.enablesPostSpecialTouchAirborneSideVelocityPreservation(),
                profile.attackBouncePolicy(),
                profile.actorContextPolicy(),
                profile.stopAfterFirstOverlapPolicy());
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (routine == ROUTINE_INVISIBLE_GRABBER && routineSecondary < 4) {
            grabberCollisionProperty = true;
        }
    }

    // ------------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------------

    @Override
    public void onUnload() {
        if (!ownsPlayerControl) {
            return;
        }
        AbstractPlayableSprite player = getMainPlayer();
        if (player != null) {
            player.clearForcedInputMask();
            player.setControlLocked(false);
            if (player.isObjectControlled()) {
                ObjectControlState.none().applyTo(player);
            }
        }
        ownsPlayerControl = false;
    }

    // ------------------------------------------------------------------------
    // Helpers: movement / script control / child spawning
    // ------------------------------------------------------------------------

    private void moveWithPlayer(AbstractPlayableSprite player, boolean mainStandingNow) {
        if (mainStandingNow) {
            moveVert();
            applyVerticalLimit();
            objectMove();
            applyTornadoParallaxVelocity();
            return;
        }

        int anchorX = player.getCentreX();
        if (standingTransition) {
            Orientation orientation = getOrientationToClosestPlayer(player);
            smoothOffsetX = orientation.signedDistanceX();
            anchorX = orientation.target().getCentreX();
        }

        if (smoothOffsetX != 0) {
            smoothOffsetX += (smoothOffsetX < 0) ? 1 : -1;
        }

        currentX = anchorX + smoothOffsetX;
        syncFixedXFromPosition();
        applyTornadoParallaxVelocity();
    }

    private void moveObeyPlayer(AbstractPlayableSprite player, boolean playerOnObjectNow) {
        if (playerOnObjectNow) {
            if (!moveVertActive) {
                yVel = 0;
                int requestedVel = 0;
                if (player.isDownPressed()) {
                    requestedVel = 0x80;
                } else if (player.isUpPressed()) {
                    requestedVel = -0x80;
                }
                if (requestedVel != 0) {
                    yVel = requestedVel;
                    applyVerticalLimit();
                    objectMove();
                }
            }
        }

        // ROM: ObjB2_Move_obbey_player (loc_3AE94) moves TORNADO to follow PLAYER.
        // move.w x_pos(a1),d1 / add.w d3,d1 / move.w d1,x_pos(a0)
        // d3 = ±16 based on player orientation relative to tornado.
        // The TORNADO follows the PLAYER (not vice versa).
        if (playerOnObjectNow) {
            Orientation orientation = getOrientationToClosestPlayer(player);
            if (orientation.absDistanceX() >= PLAYER_HORIZONTAL_CLAMP
                    && Math.abs(orientation.target().getGSpeed()) < PLAYER_INERTIA_CLAMP) {
                int targetX = orientation.target().getCentreX()
                        + (orientation.playerIsRight() ? -PLAYER_HORIZONTAL_CLAMP : PLAYER_HORIZONTAL_CLAMP);
                currentX = targetX;
                syncFixedXFromPosition();

                // Refresh SolidContacts tracking position so the follow delta isn't
                // double-applied as a riding delta. In the ROM, SolidObject runs inline
                // before the horizontal follow, so it never sees this delta.
                ObjectManager objectManager = services().objectManager();
                if (objectManager != null) {
                    objectManager.refreshRidingTrackingPosition(this);
                }
            }
        }
        if (playerOnObjectNow) {
            return;
        }

        moveVert2();
    }

    private void moveVert() {
        if (!moveVertActive) {
            if (!standingTransition) {
                return;
            }
            moveVertActive = true;
            moveVert2Active = false;
            yVel = 0x200;
            moveVertTimer = 0x14;
        }

        moveVertTimer--;
        if (moveVertTimer < 0) {
            moveVertActive = false;
            yVel = 0;
            return;
        }

        if (yVel > -0x100) {
            yVel -= 0x20;
        }
    }

    private void moveVert2() {
        if (!moveVert2Active) {
            if (!standingTransition) {
                return;
            }
            moveVert2Active = true;
            moveVertActive = false;
            yVel = 0x200;
            moveVertTimer = 0x2B;
        }

        moveVertTimer--;
        if (moveVertTimer < 0) {
            moveVert2Active = false;
            yVel = 0;
            return;
        }

        if (yVel > -0x100) {
            yVel -= 0x20;
        }
        applyVerticalLimit();
        objectMove();
    }

    private void applyVerticalLimit() {
        if (yVel == 0) {
            return;
        }

        int cameraY = services().camera().getY();
        if (yVel < 0) {
            int upper = cameraY + VERTICAL_LIMIT_UP_OFFSET;
            if (currentY < upper) {
                yVel = 0;
            }
            return;
        }

        int lower = cameraY + VERTICAL_LIMIT_DOWN_OFFSET;
        if (currentY >= lower) {
            yVel = 0;
        }
    }

    private void clampClosestPlayerHorizontal() {
        AbstractPlayableSprite main = getMainPlayer();
        if (main == null) {
            return;
        }
        Orientation orientation = getOrientationToClosestPlayer(main);
        if (orientation.absDistanceX() < PLAYER_HORIZONTAL_CLAMP) {
            return;
        }
        int targetX = currentX + (orientation.playerIsRight() ? PLAYER_HORIZONTAL_CLAMP : -PLAYER_HORIZONTAL_CLAMP);
        NativePositionOps.writeXPosPreserveSubpixel(orientation.target(), targetX);
    }

    private void alignPlaneAndSolid() {
        objectMove();
        applyTornadoParallaxVelocity();
        solidActive = true;
    }

    private void applyTornadoParallaxVelocity() {
        ParallaxManager pm = services().parallaxManager();
        int vx = pm.getTornadoVelocityX();
        int vy = pm.getTornadoVelocityY();
        currentX += vx;
        currentY += vy;
        xPosFixed8 += (vx << 8);
        yPosFixed8 += (vy << 8);
    }

    private void objectMove() {
        xPosFixed8 += xVel;
        yPosFixed8 += yVel;
        currentX = xPosFixed8 >> 8;
        currentY = yPosFixed8 >> 8;
    }

    private void applyDeleteOffScreenCulling() {
        if (!checkMarkObjGone()) {
            return;
        }
        setDestroyed(true);
        renderThisFrame = false;
        solidActive = false;
    }

    /**
     * Replicates Obj_DeleteOffScreen/MarkObjGone X-range deletion:
     * ((x_pos & $FF80) - Camera_X_pos_coarse_back) > $280 (unsigned compare).
     */
    private boolean checkMarkObjGone() {
        return !isInRangeAt(currentX);
    }

    private void syncFixedFromPosition() {
        xPosFixed8 = currentX << 8;
        yPosFixed8 = currentY << 8;
    }

    private void syncFixedXFromPosition() {
        xPosFixed8 = currentX << 8;
    }

    private void advanceMainAnimation() {
        int[] script = (animId == 0) ? MAIN_ANIM_A : MAIN_ANIM_B;
        mappingFrame = script[animFrameIndex];
        animFrameIndex++;
        if (animFrameIndex >= script.length) {
            animFrameIndex = 0;
        }
    }

    private void advanceThrusterAnimation() {
        mappingFrame = THRUSTER_ANIM[animFrameIndex];
        animFrameIndex++;
        if (animFrameIndex >= THRUSTER_ANIM.length) {
            animFrameIndex = 0;
        }
    }

    private void applyWaitingAnimation(AbstractPlayableSprite player) {
        // ObjB2 writes the combined mapping/anim state before setting
        // duration=$0100: mapping_frame=1, anim_frame=0, anim=prev_anim=Wait
        // (docs/s2disasm/s2.asm, ObjB2 waiting-player helpers).
        player.setMappingFrame(1);
        player.setAnimationId(Sonic2AnimationIds.WAIT);
        player.getAnimationManager().publishPreviousAnimationId(Sonic2AnimationIds.WAIT.id());
        player.setAnimationFrameIndex(0);
        player.setAnimationTick(1);
    }

    private void applyScriptInput(AbstractPlayableSprite player, int forcedMask, boolean lock) {
        if (player == null) {
            return;
        }
        player.setControlLocked(lock);
        if (forcedMask == 0) {
            player.clearForcedInputMask();
        } else {
            player.setForcedInputMask(forcedMask & (INPUT_UP | INPUT_DOWN | INPUT_RIGHT | INPUT_JUMP));
        }
        if (lock || forcedMask != 0) {
            ownsPlayerControl = true;
        }
    }

    private void clearScriptInput(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        player.clearForcedInputMask();
        player.setControlLocked(false);
    }

    private boolean isMainPlayerStanding(AbstractPlayableSprite player) {
        ObjectManager objectManager = services().objectManager();
        return objectManager != null && objectManager.isRidingObject(player, this);
    }

    private PlayerSolidContactResult checkpoint(AbstractPlayableSprite player) {
        PlayerSolidContactResult contact = services().solidExecution().resolveSolidNow(player);
        // ObjB2's inline SolidObject calls leave p1_standing in status(a0):
        // RideObject_SetRide sets the bit on a top landing, and SolidObject's
        // standing path clears it on release (s2.asm:35014-35044, 35986-36045).
        // Keep the object-owned latch for every ObjB2 routine that reaches the
        // shared manual checkpoint, not only ObjB2_Main_SCZ.
        lastMainStanding = contact.standingNow();
        return contact;
    }

    private void releasePlayersFromPlatform(AbstractPlayableSprite updatePlayer) {
        ObjectManager objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        for (PlayableEntity player : playerQuery(updatePlayer)
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            releasePlayerFromPlatform(objectManager, player);
        }
    }

    private void releasePlayerFromPlatform(ObjectManager objectManager, PlayableEntity player) {
        if (objectManager.isRidingObject(player, this)) {
            objectManager.clearRidingObject(player);
            player.setOnObject(false);
            player.setAir(true);
        }
    }

    private Orientation getOrientationToClosestPlayer(AbstractPlayableSprite mainPlayer) {
        AbstractPlayableSprite closest = null;
        int closestSignedDistance = 0;
        int closestAbsDistance = Integer.MAX_VALUE;

        for (PlayableEntity player : playerQuery(mainPlayer)
                .playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (!(player instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            if (player != mainPlayer && player.getDead()) {
                continue;
            }
            int signedDistance = currentX - sprite.getCentreX();
            int absDistance = Math.abs(signedDistance);
            if (absDistance < closestAbsDistance) {
                closest = sprite;
                closestSignedDistance = signedDistance;
                closestAbsDistance = absDistance;
            }
        }

        if (closest != null) {
            return new Orientation(closest, closestSignedDistance);
        }

        return new Orientation(mainPlayer, currentX - mainPlayer.getCentreX());
    }

    private ObjectPlayerQuery playerQuery(AbstractPlayableSprite updatePlayer) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(
                () -> {
                    PlayableEntity main = query.mainPlayerOrNull();
                    return main != null ? main : updatePlayer;
                },
                query::sidekicks);
    }

    private void spawnSmokeObject() {
        ObjectManager manager = services().objectManager();
        if (manager == null) {
            return;
        }
        ObjectSpawn smokeSpawn = new ObjectSpawn(
                currentX, currentY, Sonic2ObjectIds.TORNADO_SMOKE, 0x90, spawn.renderFlags(), false, spawn.rawYWord());
        int randomOffset = Sonic2Rng.nextTornadoSmokeOffset(services().rng());
        spawnFreeChild(() -> new TornadoSmokeObjectInstance(smokeSpawn, randomOffset));
    }

    private TornadoObjectInstance spawnTornadoChild(int childSubtype, int x, int y) {
        ObjectSpawn childSpawn = new ObjectSpawn(
                x, y, Sonic2ObjectIds.TORNADO, childSubtype, spawn.renderFlags(), false, spawn.rawYWord());
        return spawnChild(() -> new TornadoObjectInstance(childSpawn, this));
    }

    private void applyJumpToPlaneLayoutPatch() {
        if (levelLayoutPatched) {
            return;
        }
        Level level = services().currentLevel();
        if (level == null) {
            return;
        }
        levelLayoutPatched = true;
        services().zoneLayoutMutationPipeline().queue(context -> {
            MutationEffects effects = MutationEffects.NONE;
            for (int i = 0; i < LAYOUT_PATCH_OFFSETS.length; i++) {
                final int baseOffset = LAYOUT_PATCH_OFFSETS[i];
                final int[] patchBytes = LAYOUT_PATCH_BYTES[i];
                for (int j = 0; j < patchBytes.length; j++) {
                    final int offset = baseOffset + j;
                    final int withinRow = offset % LAYOUT_ROW_BYTES;
                    final int layer = withinRow >= LAYOUT_LAYER_WIDTH ? 1 : 0;
                    final int x = withinRow & (LAYOUT_LAYER_WIDTH - 1);
                    final int y = offset / LAYOUT_ROW_BYTES;
                    // Level_LoadBlockMap stores each row as 128 foreground bytes followed by
                    // 128 background bytes (docs/s2disasm/s2.asm:20145-20158). ObjB2 writes
                    // raw Level_Layout offsets here (s2.asm:79035-79042), so decode the
                    // interleaved RAM address before mutating the engine's split map layers.
                    effects = context.surface().setBlockInMapWithoutRedraw(
                            layer, x, y, patchBytes[j] & 0xFF);
                }
            }
            return effects;
        });
    }

    private AbstractPlayableSprite getMainPlayer() {
        return services().camera().getFocusedSprite();
    }

    private String resolveRenderArtKey() {
        return switch (subtype) {
            case SUBTYPE_SCZ_MAIN, SUBTYPE_WFZ_START, SUBTYPE_WFZ_END -> Sonic2ObjectArtKeys.TORNADO;
            case SUBTYPE_BLINKER -> Sonic2ObjectArtKeys.WFZ_THRUST;
            case SUBTYPE_UNUSED_MOVER -> Sonic2ObjectArtKeys.CLOUDS;
            case SUBTYPE_THRUSTER -> Sonic2ObjectArtKeys.TORNADO_THRUSTER;
            default -> null;
        };
    }

    private record Orientation(AbstractPlayableSprite target, int signedDistanceX) {
        int absDistanceX() {
            return Math.abs(signedDistanceX);
        }

        boolean playerIsRight() {
            return signedDistanceX < 0;
        }
    }

}
