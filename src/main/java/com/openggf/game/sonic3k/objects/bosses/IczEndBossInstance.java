package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.IczFreezerObjectInstance;
import com.openggf.game.sonic3k.objects.IczSnowPileObjectInstance;
import com.openggf.game.sonic3k.objects.S3kBossExplosionChild;
import com.openggf.game.sonic3k.objects.S3kBossExplosionController;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.Level;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.GravityDebrisChild;
import com.openggf.level.objects.MultiPieceSolidProvider;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SpawnCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnCoordinateRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider.TouchRegion;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.boss.AbstractBossInstance;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.physics.Direction;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Ice Cap Zone Act 2 end boss (object 0xBD).
 *
 * <p>ROM anchor: {@code Obj_ICZEndBoss} in {@code sonic3k.asm}. This ports the
 * parent state machine around {@code loc_71C16..loc_722C6}: shared boss-camera
 * entry gate, descent, swing/frost-puff attack loop, hit flash, and the fixed
 * egg-capsule handoff after defeat.
 */
public final class IczEndBossInstance extends AbstractBossInstance
        implements MultiPieceSolidProvider, SolidObjectListener, SpawnRewindRecreatable {
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_DESCEND = 0x02;
    private static final int ROUTINE_SWING = 0x04;
    private static final int ROUTINE_DEFEAT_RISE = 0x06;

    private static final int HIT_COUNT = 8;
    private static final int COLLISION_SIZE = 0x0F;
    private static final int INVULNERABILITY_TIME = 0x20;
    private static final int BOSS_PALETTE_LINE = 1;
    private static final int BODY_PALETTE_BASE = 1;
    private static final int ROBOTNIK_SHIP_PALETTE_LINE = 0;

    private static final int CAMERA_RANGE_MIN_Y = 0x02F8;
    private static final int CAMERA_RANGE_MAX_Y = 0x06F8;
    private static final int CAMERA_RANGE_MIN_X = 0x4340;
    private static final int CAMERA_RANGE_MAX_X = 0x4490;
    private static final int ARENA_LOCK_Y = 0x05F8;
    private static final int ARENA_LOCK_X = 0x4390;
    private static final int CAPSULE_CAMERA_MAX_X = ARENA_LOCK_X + 0x130;
    private static final int BOSS_GATE_FADE_TIME = 2 * 60;

    private static final int DESCEND_TIME = 0xCF;
    private static final int SWING_WAIT_TIME = 0x3F;
    private static final int HORIZONTAL_TRAVEL_TIME = 0x17F;
    private static final int DEFEAT_RISE_TIME = 0x7F;
    private static final int DEFEAT_SHELL_RELEASE_WAIT = 0x3F;
    private static final int DEFEAT_CAPSULE_HANDOFF_WAIT = (2 * 60) - 1;
    private static final int DAMAGED_PHASE_HIT_COUNT = 2;
    private static final int DAMAGED_RISE_TIME = 0x7F;
    private static final int DAMAGED_TOP_FRAME = 4;
    private static final int DAMAGED_TOP_STEAM_DISABLED = -2;
    private static final int DAMAGED_TOP_STEAM_INITIAL_TIME = 0x0E;
    private static final int DAMAGED_TOP_STEAM_REPEAT_TIME = 0x17;
    private static final int SWING_MAX_VELOCITY = 0xC0;

    private static final int PARENT_FLAG_SWING_DOWN = 1;
    private static final int PARENT_FLAG_FROST_PUFF = 1 << 1;
    private static final int PARENT_FLAG_SIDE_TOGGLE = 1 << 2;
    private static final int PARENT_FLAG_DEFEATED = 1 << 4;
    private static final int ROBOTNIK_SHIP_FRAME = 0x09;
    private static final int ROBOTNIK_SHIP_ESCAPE_FRAME = 0x0A;
    private static final int ROBOTNIK_SHIP_FLAME_FRAME = 0x06;
    private static final int ROBOTNIK_SHIP_FLAME_DX = 0x1E;
    private static final int ROBOTNIK_HEAD_Y_OFFSET = -0x1C;
    private static final int ROBOTNIK_SHIP_ESCAPE_VELOCITY = 0x0300;
    private static final int ROBOTNIK_SHIP_ESCAPE_TIME = 0x0100;
    private static final int ROBOTNIK_SHIP_EXPLOSION_SUBTYPE = 0x04;
    private static final int BOTTOM_CHILD_DY = 0x2D;
    private static final int BOTTOM_HURT_CHILD_DY = 0x08;
    private static final int BOTTOM_HURT_FLAGS = 0x9B;
    private static final SolidObjectParams BOTTOM_SOLID_PARAMS = new SolidObjectParams(0x23, 4, 0x0A);
    // Check_PlayerInRange tables encode (start offset, extent), so
    // {-0x18, 0x30} and {-0x10, 0x20} end at +0x18 and +0x10.
    private static final int FROST_CAPTURE_NORMAL_MIN_X = -0x18;
    private static final int FROST_CAPTURE_NORMAL_MAX_X = 0x18;
    private static final int FROST_CAPTURE_NORMAL_MIN_Y = -0x18;
    private static final int FROST_CAPTURE_NORMAL_MAX_Y = 0x18;
    private static final int FROST_CAPTURE_TOP_MIN_X = -0x10;
    private static final int FROST_CAPTURE_TOP_MAX_X = 0x10;
    private static final int FROST_CAPTURE_TOP_MIN_Y = -0x10;
    private static final int FROST_CAPTURE_TOP_MAX_Y = 0x10;
    private static final int MIDDLE_CHILD_INDEX = 1;
    private static final int BOTTOM_CHILD_INDEX = 2;
    private static final int MIDDLE_CHILD_SHIFT_TIME = 0x24;
    private static final int BOTTOM_CHILD_SHIFT_TIME = 0x42;
    private static final int[] HIT_FLASH_COLOR_INDICES = {0x0A, 0x0E};
    private static final int[] HIT_FLASH_NORMAL_COLORS = {0x020, 0x644};
    private static final int[] HIT_FLASH_BRIGHT_COLORS = {0xEEE, 0xAAA};

    private static final int[] FROST_PATTERN = {
            0, 2, 4, 2, 0, 2, 4, 2, 0, 2, 0, 2, 4, 2, 2, 4
    };
    private static final int[][] STRUCTURAL_CHILD_SPECS = {
            {0x18, 0x07, 3, 0},
            {0, 0x0B, 1, MIDDLE_CHILD_SHIFT_TIME},
            {0, 0x2D, 2, BOTTOM_CHILD_SHIFT_TIME}
    };
    private static final int FOLDED_NATIVE_CHILD_SST_COUNT = 6;
    // ChildObjDat_7233E creates the middle, bottom-solid, and bottom-hurt
    // children in slots 2, 3, and 5 of the folded six-slot group. They delete
    // themselves when the parent enters its damaged rise; the ship, top body,
    // and head slots remain live for the rest of the arena.
    private static final int FOLDED_MIDDLE_CHILD_INDEX = 2;
    private static final int BOTTOM_STRUCTURAL_CHILD_RESERVED_INDEX = 3;
    private static final int FOLDED_BOTTOM_HURT_CHILD_INDEX = 5;
    private static final int EXTENDED_CAPTURE_PENDING = 1;
    private static final int EXTENDED_CAPTURE_READY = 2;
    private static final int EXTENDED_CAPTURE_PENDING_BEFORE_SOLID = 3;
    private static final int EXTENDED_CAPTURE_READY_BEFORE_SOLID = 4;
    private static final int EXTENDED_CAPTURE_PHASE = 0;
    private static final int EXTENDED_CAPTURE_SOURCE_X = 1;
    private static final int EXTENDED_CAPTURE_SOURCE_SLOT = 2;
    private static final int[][] FROST_OFFSETS_FRAME_0 = {
            {-0x50, 0x14}, {-0x40, 0x14}, {-0x48, 0x04}, {-0x40, 0x04},
            {-0x34, 0x0C}, {-0x24, 0x08}, {-0x1C, 0x04}
    };
    private static final int[][] FROST_OFFSETS_FRAME_2 = {
            {0x08, 0x40}, {0, 0x3C}, {-0x10, 0x40}, {-0x08, 0x3C},
            {-0x04, 0x34}, {-0x04, 0x28}
    };
    private static final int[][] FROST_OFFSETS_FRAME_4 = {
            {0x50, 0x14}, {0x40, 0x14}, {0x48, 0x04}, {0x40, 0x04},
            {0x34, 0x0C}, {0x24, 0x08}, {0x1C, 0x04}
    };
    private static final int[][] FROST_OFFSETS_FRAME_6 = {
            {0x18, -0x04}, {0x14, 0}, {0x10, -0x08}, {0x08, -0x04}
    };
    private static final int[] FROST_INITIAL_TIMERS_FRAME_0 = {0x11, 0x0E, 0x0B, 0x08, 0x05, 0x02, -1};
    private static final int[] FROST_INITIAL_TIMERS_FRAME_2 = {0x0E, 0x0B, 0x08, 0x05, 0x02, -1};
    private static final int[] FROST_INITIAL_TIMERS_FRAME_4 = FROST_INITIAL_TIMERS_FRAME_0;
    private static final int[] FROST_INITIAL_TIMERS_FRAME_6 = {0x08, 0x05, 0x02, -1};
    private static final int[][] FROST_SCRIPT_SMALL = {
            {0x05, 1}, {0x05, 1}, {0x06, 1}, {0x07, 2}, {0x08, 3}, {0x09, 4}, {0x0A, 5}
    };
    private static final int[][] FROST_SCRIPT_LARGE = {
            {0x0B, 2}, {0x0B, 2}, {0x0C, 3}, {0x0D, 4}, {0x0E, 5}, {0x0F, 6}
    };
    private static final int[][] FROST_SCRIPT_TOP = {
            {0x10, 1}, {0x10, 1}, {0x11, 1}, {0x12, 2}, {0x13, 2}, {0x14, 2}, {0x15, 2}
    };
    private static final int[][] DEFEAT_DEBRIS_OFFSETS = {
            {-0x14, 0x04}, {0x0C, 0x04}, {0, 0x1C}
    };
    private static final int[][] DEFEAT_DEBRIS_VELOCITIES = {
            {-0x100, -0x100}, {0x100, -0x100}, {-0x200, -0x100}
    };

    private int routineTimer;
    private int swingTimer;
    private int frostPatternIndex;
    private int parentFlags;
    private int mappingFrame;
    private int frostSelector;
    private int swingAmplitude;
    private int hitFlashTimer;
    private boolean hitFlashBright;
    private boolean arenaGateInitialized;
    private boolean arenaGateComplete;
    private S3kSharedBossCameraGate arenaCameraGate;
    private WaitCallback waitCallback;
    private StructuralChild[] structuralChildren;
    private int structuralBottomChildSlot;
    private List<EffectChild> effectChildren;
    private int defeatTimer;
    private int damagedRiseTimer;
    private int damagedTopSteamTimer;
    private int damagedTopSteamEmissionCount;
    private boolean damagedTopSteamTimerJustArmed;
    private boolean damagedFinalPhase;
    private boolean defeatStarted;
    private boolean defeatShellReleased;
    private boolean defeatHandoffComplete;
    private boolean lastSideToggle;
    private int bottomChildWholePixelDelta;
    private int[] structuralChildSlots;
    private int pendingFrostCaptureMask;
    private int readyFrostCaptureMask;
    private int pendingBeforeSolidFrostCaptureMask;
    private int readyBeforeSolidFrostCaptureMask;
    private AbstractPlayableSprite nativeP1FrostCaptureOwner;
    private AbstractPlayableSprite nativeP2FrostCaptureOwner;
    private int pendingFrostCaptureP1SourceX;
    private int pendingFrostCaptureP2SourceX;
    private int readyFrostCaptureP1SourceX;
    private int readyFrostCaptureP2SourceX;
    private int pendingFrostCaptureP1SourceSlot;
    private int pendingFrostCaptureP2SourceSlot;
    private int readyFrostCaptureP1SourceSlot;
    private int readyFrostCaptureP2SourceSlot;
    private int pendingBeforeSolidFrostCaptureP1SourceX;
    private int pendingBeforeSolidFrostCaptureP2SourceX;
    private int readyBeforeSolidFrostCaptureP1SourceX;
    private int readyBeforeSolidFrostCaptureP2SourceX;
    private int pendingBeforeSolidFrostCaptureP1SourceSlot;
    private int pendingBeforeSolidFrostCaptureP2SourceSlot;
    private int readyBeforeSolidFrostCaptureP1SourceSlot;
    private int readyBeforeSolidFrostCaptureP2SourceSlot;
    private Map<AbstractPlayableSprite, int[]> extendedFrostCaptures;
    private int robotnikShipX;
    private int robotnikShipXFixed;
    private int robotnikShipY;
    private int robotnikShipFrame;
    private int robotnikHeadFrame;
    private int robotnikHeadAnimTimer;
    private boolean robotnikShipVisible;
    private boolean robotnikShipEscaping;
    private boolean robotnikShipFlyingRight;
    private boolean robotnikShipDeleted;
    private boolean robotnikShipFlameVisible;
    private int robotnikShipEscapeTimer;
    private S3kBossExplosionController robotnikExplosionController;

    private enum WaitCallback {
        NONE,
        ENTER_SWING,
        SET_SIDE_TOGGLE,
        START_HORIZONTAL_TRAVEL,
        EMIT_FROST_PUFF,
        CLEAR_FROST_PUFF
    }

    private enum EffectAnchor {
        PARENT,
        TOP_CHILD,
        BOTTOM_CHILD
    }

    private record AnchorPoint(int x, int y, boolean flipX, boolean flipY) {
    }

    public IczEndBossInstance(ObjectSpawn spawn) {
        super(spawn, "ICZEndBoss");
    }

    @Override
    protected void initializeBossState() {
        state.x = spawn.x();
        state.y = spawn.y();
        state.xFixed = state.x << 16;
        state.yFixed = state.y << 16;
        state.xVel = 0;
        state.yVel = 0x80;
        state.routine = ROUTINE_INIT;
        state.hitCount = HIT_COUNT;
        routineTimer = DESCEND_TIME;
        swingTimer = 0;
        frostPatternIndex = 0;
        parentFlags = 0;
        mappingFrame = 0;
        frostSelector = 0;
        swingAmplitude = 0;
        hitFlashTimer = 0;
        hitFlashBright = false;
        arenaGateInitialized = false;
        arenaGateComplete = false;
        if (arenaCameraGate == null) {
            arenaCameraGate = new S3kSharedBossCameraGate();
        } else {
            arenaCameraGate.reset();
        }
        waitCallback = WaitCallback.NONE;
        structuralChildren = createStructuralChildren();
        structuralBottomChildSlot = -1;
        structuralChildSlots = null;
        effectChildren = new ArrayList<>();
        defeatTimer = 0;
        damagedRiseTimer = 0;
        damagedTopSteamTimer = DAMAGED_TOP_STEAM_DISABLED;
        damagedTopSteamEmissionCount = 0;
        damagedTopSteamTimerJustArmed = false;
        damagedFinalPhase = false;
        defeatStarted = false;
        defeatShellReleased = false;
        defeatHandoffComplete = false;
        lastSideToggle = false;
        bottomChildWholePixelDelta = 0;
        pendingFrostCaptureMask = 0;
        readyFrostCaptureMask = 0;
        pendingBeforeSolidFrostCaptureMask = 0;
        readyBeforeSolidFrostCaptureMask = 0;
        nativeP1FrostCaptureOwner = null;
        nativeP2FrostCaptureOwner = null;
        pendingFrostCaptureP1SourceX = 0;
        pendingFrostCaptureP2SourceX = 0;
        readyFrostCaptureP1SourceX = 0;
        readyFrostCaptureP2SourceX = 0;
        pendingFrostCaptureP1SourceSlot = -1;
        pendingFrostCaptureP2SourceSlot = -1;
        readyFrostCaptureP1SourceSlot = -1;
        readyFrostCaptureP2SourceSlot = -1;
        pendingBeforeSolidFrostCaptureP1SourceX = 0;
        pendingBeforeSolidFrostCaptureP2SourceX = 0;
        readyBeforeSolidFrostCaptureP1SourceX = 0;
        readyBeforeSolidFrostCaptureP2SourceX = 0;
        pendingBeforeSolidFrostCaptureP1SourceSlot = -1;
        pendingBeforeSolidFrostCaptureP2SourceSlot = -1;
        readyBeforeSolidFrostCaptureP1SourceSlot = -1;
        readyBeforeSolidFrostCaptureP2SourceSlot = -1;
        if (extendedFrostCaptures == null) {
            extendedFrostCaptures = new IdentityHashMap<>();
        } else {
            extendedFrostCaptures.clear();
        }
        robotnikShipX = state.x;
        robotnikShipXFixed = state.x << 8;
        robotnikShipY = state.y;
        robotnikShipFrame = ROBOTNIK_SHIP_FRAME;
        robotnikHeadFrame = 0;
        robotnikHeadAnimTimer = 0;
        robotnikShipVisible = false;
        robotnikShipEscaping = false;
        robotnikShipFlyingRight = false;
        robotnikShipDeleted = false;
        robotnikShipFlameVisible = false;
        robotnikShipEscapeTimer = 0;
        robotnikExplosionController = null;
    }

    @Override
    protected void updateBossLogic(int vIntRunCount, PlayableEntity player) {
        // Native smoke children execute after the bottom solid child. Promote
        // last pass's folded overlap so this pass's solid checkpoint can carry
        // the rider before the later-slot capture publishes object_control.
        int promotedCaptureMask = pendingFrostCaptureMask;
        readyFrostCaptureMask |= promotedCaptureMask;
        if ((promotedCaptureMask & 1) != 0) {
            readyFrostCaptureP1SourceX = pendingFrostCaptureP1SourceX;
            readyFrostCaptureP1SourceSlot = pendingFrostCaptureP1SourceSlot;
        }
        if ((promotedCaptureMask & 2) != 0) {
            readyFrostCaptureP2SourceX = pendingFrostCaptureP2SourceX;
            readyFrostCaptureP2SourceSlot = pendingFrostCaptureP2SourceSlot;
        }
        pendingFrostCaptureMask = 0;
        int promotedBeforeSolidMask = pendingBeforeSolidFrostCaptureMask;
        readyBeforeSolidFrostCaptureMask |= promotedBeforeSolidMask;
        if ((promotedBeforeSolidMask & 1) != 0) {
            readyBeforeSolidFrostCaptureP1SourceX = pendingBeforeSolidFrostCaptureP1SourceX;
            readyBeforeSolidFrostCaptureP1SourceSlot = pendingBeforeSolidFrostCaptureP1SourceSlot;
        }
        if ((promotedBeforeSolidMask & 2) != 0) {
            readyBeforeSolidFrostCaptureP2SourceX = pendingBeforeSolidFrostCaptureP2SourceX;
            readyBeforeSolidFrostCaptureP2SourceSlot = pendingBeforeSolidFrostCaptureP2SourceSlot;
        }
        pendingBeforeSolidFrostCaptureMask = 0;
        promoteExtendedFrostCaptures();
        applyReadyBeforeSolidFrostCaptures(player);
        updateHitFlash();
        if (!arenaGateComplete) {
            updateArenaGate();
            updateRobotnikShip();
            return;
        }

        if (defeatStarted) {
            updateDefeat();
            return;
        }

        switch (state.routine) {
            case ROUTINE_INIT -> {
                // Obj_ICZEndBoss creates ChildObjDat_72336/7233E only after
                // the shared camera/fade gate hands control to loc_71C36.
                // Reserving earlier misses the arena's already-live snow slots
                // and gives the folded children the wrong SST execution phase.
                ensureStructuralChildSlots();
                enterDescend();
            }
            case ROUTINE_DESCEND -> {
                moveWithVelocity();
                tickWait();
            }
            case ROUTINE_SWING -> updateSwingLoop();
            case ROUTINE_DEFEAT_RISE -> updateDamagedRise();
            default -> {
            }
        }
        updateStructuralChildren();
        updateEffectChildren(player);
        updateRobotnikShip();
    }

    private void updateArenaGate() {
        if (!arenaGateInitialized) {
            if (!isCameraInRange()) {
                return;
            }
            initializeArenaGate();
        }

        arenaGateComplete = arenaCameraGate.update(
                services().camera(),
                () -> services().playMusic(Sonic3kMusic.BOSS.id));
    }

    private boolean isCameraInRange() {
        if (services().camera() == null) {
            return true;
        }
        int cameraX = services().camera().getX() & 0xFFFF;
        int cameraY = services().camera().getY() & 0xFFFF;
        return cameraX >= CAMERA_RANGE_MIN_X && cameraX <= CAMERA_RANGE_MAX_X
                && cameraY >= CAMERA_RANGE_MIN_Y && cameraY <= CAMERA_RANGE_MAX_Y;
    }

    private void initializeArenaGate() {
        arenaGateInitialized = true;
        if (services().gameState() != null) {
            services().gameState().setCurrentBossId(Sonic3kObjectIds.ICZ_END_BOSS);
        }
        services().fadeOutMusic();
        installBossPalette();
        arenaCameraGate.begin(
                services().camera(),
                new S3kSharedBossCameraGate.LockBounds(
                        ARENA_LOCK_Y,
                        ARENA_LOCK_Y,
                        ARENA_LOCK_X,
                        ARENA_LOCK_X),
                BOSS_GATE_FADE_TIME);
    }

    private void stopBossSnowdustEmitter() {
        if (services().objectManager() == null) {
            return;
        }
        for (var object : services().objectManager().getActiveObjects()) {
            if (object instanceof IczSnowPileObjectInstance emitter && emitter.isSnowdustEmitter()) {
                emitter.stopSnowdustEmitter();
            }
        }
    }

    private void installBossPalette() {
        try {
            Level level = services().currentLevel();
            if (level == null) {
                return;
            }
            byte[] line = services().rom().readBytes(Sonic3kConstants.PAL_ICZ_END_BOSS_ADDR, 32);
            S3kPaletteWriteSupport.applyLine(
                    services().paletteOwnershipRegistryOrNull(),
                    level,
                    services().graphicsManager(),
                    S3kPaletteOwners.ICZ_END_BOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    BOSS_PALETTE_LINE,
                    line);
        } catch (Exception ignored) {
            // Headless unit contexts often do not install a ROM-backed level.
        }
    }

    private void enterDescend() {
        state.routine = ROUTINE_DESCEND;
        routineTimer = DESCEND_TIME;
        waitCallback = WaitCallback.ENTER_SWING;
    }

    private void enterSwing() {
        state.routine = ROUTINE_SWING;
        state.yVel = 0xC0;
        swingAmplitude = 0x10;
        parentFlags &= ~PARENT_FLAG_SWING_DOWN;
        routineTimer = SWING_WAIT_TIME;
        waitCallback = WaitCallback.SET_SIDE_TOGGLE;
        swingTimer = 0x7FFF;
    }

    private void updateSwingLoop() {
        applySwingMotion();
        if (--swingTimer < 0) {
            state.xVel = -state.xVel;
            state.renderFlags ^= 1;
            swingTimer = HORIZONTAL_TRAVEL_TIME;
        }
        moveWithVelocity();
        tickWait();
    }

    private void applySwingMotion() {
        SwingMotion.Result result = SwingMotion.update(
                swingAmplitude,
                state.yVel,
                SWING_MAX_VELOCITY,
                (parentFlags & PARENT_FLAG_SWING_DOWN) != 0);
        state.yVel = result.velocity();
        if (result.directionDown()) {
            parentFlags |= PARENT_FLAG_SWING_DOWN;
        } else {
            parentFlags &= ~PARENT_FLAG_SWING_DOWN;
        }
    }

    private void tickWait() {
        if (--routineTimer >= 0) {
            return;
        }
        runWaitCallback();
    }

    private void runWaitCallback() {
        WaitCallback callback = waitCallback;
        waitCallback = WaitCallback.NONE;
        switch (callback) {
            case ENTER_SWING -> enterSwing();
            case SET_SIDE_TOGGLE -> {
                parentFlags |= PARENT_FLAG_SIDE_TOGGLE;
                routineTimer = SWING_WAIT_TIME;
                waitCallback = WaitCallback.START_HORIZONTAL_TRAVEL;
            }
            case START_HORIZONTAL_TRAVEL -> {
                routineTimer = SWING_WAIT_TIME;
                state.xVel = -0x80;
                swingTimer = HORIZONTAL_TRAVEL_TIME;
                waitCallback = WaitCallback.EMIT_FROST_PUFF;
            }
            case EMIT_FROST_PUFF -> emitFrostPuff();
            case CLEAR_FROST_PUFF -> {
                parentFlags &= ~PARENT_FLAG_FROST_PUFF;
                routineTimer = SWING_WAIT_TIME;
                waitCallback = WaitCallback.EMIT_FROST_PUFF;
            }
            case NONE -> {
            }
        }
    }

    private void emitFrostPuff() {
        parentFlags |= PARENT_FLAG_FROST_PUFF;
        services().playSfx(Sonic3kSfx.FROST_PUFF.id);
        if (!defeatStarted && !damagedFinalPhase) {
            frostSelector = FROST_PATTERN[frostPatternIndex & 0x0F];
            frostPatternIndex = (frostPatternIndex + 1) & 0x0F;
        } else {
            frostSelector = 2;
        }
        createFrostPuffsForSelector(frostSelector, anchorForFrostSelector(frostSelector));
        waitCallback = WaitCallback.CLEAR_FROST_PUFF;
    }

    private StructuralChild[] createStructuralChildren() {
        StructuralChild[] children = new StructuralChild[STRUCTURAL_CHILD_SPECS.length];
        for (int i = 0; i < STRUCTURAL_CHILD_SPECS.length; i++) {
            int[] spec = STRUCTURAL_CHILD_SPECS[i];
            children[i] = new StructuralChild(spec[0], spec[1], spec[2], spec[3]);
        }
        return children;
    }

    private void ensureStructuralChildSlots() {
        if (structuralChildSlots != null || tryServices() == null
                || tryServices().objectManager() == null || getSlotIndex() < 0) {
            return;
        }
        // ROM loc_71C36 first creates Obj_RobotnikShip4, then three body children.
        // The ship creates its Robotnik child when slot 25 dispatches, and the
        // bottom body creates loc_720C6 when slot 28 dispatches. These six SSTs
        // remain live together (sonic3k.asm:150612-150634,150875-150908).
        // This implementation folds their rendering/behavior into the boss, but
        // must retain their allocator pressure and Process_Sprites phase.
        int[] childSlots = tryServices().objectManager().allocateChildSlotsAfter(
                spawn, FOLDED_NATIVE_CHILD_SST_COUNT, getSlotIndex());
        structuralChildSlots = childSlots;
        structuralBottomChildSlot = childSlots.length > BOTTOM_STRUCTURAL_CHILD_RESERVED_INDEX
                ? childSlots[BOTTOM_STRUCTURAL_CHILD_RESERVED_INDEX]
                : -1;
    }

    private EffectAnchor anchorForFrostSelector(int selector) {
        return switch (selector) {
            case 0, 4 -> EffectAnchor.BOTTOM_CHILD;
            case 6 -> EffectAnchor.TOP_CHILD;
            default -> EffectAnchor.PARENT;
        };
    }

    private void createFrostPuffsForSelector(int selector, EffectAnchor anchor) {
        int[][] offsets = frostOffsetsForSelector(selector);
        if (offsets.length == 0) {
            return;
        }
        // Parent loc_71D1E uses AllocateObjectAfterCurrent from the boss SST.
        // Damaged top steam is emitted by the folded top-body child at slot
        // 26, so it must retain that child's after-current allocation anchor.
        int predecessorSlot = anchor == EffectAnchor.TOP_CHILD && structuralChildSlots != null
                && structuralChildSlots.length > 1 && structuralChildSlots[1] >= 0
                        ? structuralChildSlots[1] : getSlotIndex();
        for (int i = 0; i < offsets.length; i++) {
            int[][] script = frostScriptForSubtype(selector, i);
            int nativeSlot = services().objectManager() == null
                    ? -1
                    : ObjectLifetimeOps.reserveFindNextFreeChildSlot(
                            services().objectManager(), predecessorSlot);
            if (nativeSlot >= 0) {
                predecessorSlot = nativeSlot;
            }
            effectChildren.add(new EffectChild(offsets[i][0], offsets[i][1], script, anchor,
                    frostInitialTimerForSubtype(selector, i), nativeSlot));
        }
    }

    private int[][] frostOffsetsForSelector(int selector) {
        return switch (selector) {
            case 0 -> FROST_OFFSETS_FRAME_0;
            case 2 -> FROST_OFFSETS_FRAME_2;
            case 4 -> FROST_OFFSETS_FRAME_4;
            case 6 -> FROST_OFFSETS_FRAME_6;
            default -> new int[0][0];
        };
    }

    private int frostInitialTimerForSubtype(int selector, int subtype) {
        int[] timers = switch (selector) {
            case 0 -> FROST_INITIAL_TIMERS_FRAME_0;
            case 2 -> FROST_INITIAL_TIMERS_FRAME_2;
            case 4 -> FROST_INITIAL_TIMERS_FRAME_4;
            case 6 -> FROST_INITIAL_TIMERS_FRAME_6;
            default -> new int[0];
        };
        return subtype >= 0 && subtype < timers.length ? timers[subtype] : 0;
    }

    private int[][] frostScriptForSubtype(int selector, int subtype) {
        if (selector == 6) {
            return FROST_SCRIPT_TOP;
        }
        return subtype < 4 ? FROST_SCRIPT_LARGE : FROST_SCRIPT_SMALL;
    }

    private void moveWithVelocity() {
        state.xFixed += state.xVel << 8;
        state.yFixed += state.yVel << 8;
        state.updatePositionFromFixed();
    }

    private void updateStructuralChildren() {
        if (structuralChildren == null) {
            return;
        }
        int previousBottomY = structuralChildren[BOTTOM_CHILD_INDEX].y;
        boolean flipped = (state.renderFlags & 1) != 0;
        boolean sideToggle = (parentFlags & PARENT_FLAG_SIDE_TOGGLE) != 0;
        for (StructuralChild child : structuralChildren) {
            if (child.detached) {
                child.updateDetached();
                continue;
            }
            child.updateShift();
            int dx = flipped ? -child.baseDx : child.baseDx;
            child.x = state.x + dx;
            child.y = state.y + child.baseDy + child.localYOffset;
            child.flipX = flipped;
        }
        // loc_71F92/loc_71EF6 arm the child shift on the flag-transition
        // dispatch, but only routine 4 (loc_71FDA/loc_71F10) changes $43 on
        // the following SST pass. Arm after this frame's shift/refresh work.
        if (sideToggle != lastSideToggle) {
            int velocity = sideToggle ? 1 : -1;
            structuralChildren[MIDDLE_CHILD_INDEX].startShift(velocity);
            structuralChildren[BOTTOM_CHILD_INDEX].startShift(velocity);
            lastSideToggle = sideToggle;
        }
        int currentBottomY = structuralChildren[BOTTOM_CHILD_INDEX].y;
        bottomChildWholePixelDelta = previousBottomY == 0 ? 0 : currentBottomY - previousBottomY;
        updateDamagedTopSteam();
        releaseDetachedStructuralChildSlots();
    }

    private void updateDamagedTopSteam() {
        if (!damagedFinalPhase || defeatStarted || damagedTopSteamTimer < -1) {
            return;
        }
        if (damagedTopSteamTimerJustArmed) {
            damagedTopSteamTimerJustArmed = false;
            return;
        }
        if (--damagedTopSteamTimer >= 0) {
            return;
        }
        damagedTopSteamEmissionCount++;
        createFrostPuffsForSelector(6, EffectAnchor.TOP_CHILD);
        damagedTopSteamTimer = DAMAGED_TOP_STEAM_REPEAT_TIME;
    }

    private void updateEffectChildren(PlayableEntity player) {
        if (effectChildren.isEmpty()) {
            return;
        }
        // Process_Sprites visits the real SSTs in ascending slot order. The
        // folded list is append-ordered, but AllocateObjectAfterCurrent can
        // reuse an earlier free slot on a later emission, so list order is not
        // necessarily native execution order. Preserve the public list order
        // for rewind/test inspection while using a native-order snapshot here.
        List<EffectChild> updateOrder = new ArrayList<>(effectChildren);
        updateOrder.sort((left, right) -> Integer.compare(
                nativeEffectExecutionSlot(left), nativeEffectExecutionSlot(right)));
        for (EffectChild child : updateOrder) {
            if (!effectChildren.contains(child)) {
                continue;
            }
            AnchorPoint anchor = resolveEffectAnchor(child.anchor);
            child.update(anchor);
            capturePlayersInFrostPuff(child, player);
            if (child.isFinished()) {
                releaseEffectSlot(child);
                effectChildren.remove(child);
            }
        }
    }

    private int nativeEffectExecutionSlot(EffectChild child) {
        return child.nativeSlot >= 0 ? child.nativeSlot : Integer.MAX_VALUE;
    }

    private AnchorPoint resolveEffectAnchor(EffectAnchor anchor) {
        return switch (anchor) {
            case TOP_CHILD -> structuralChildAnchor(0);
            case BOTTOM_CHILD -> structuralChildAnchor(BOTTOM_CHILD_INDEX);
            case PARENT -> new AnchorPoint(state.x, state.y, (state.renderFlags & 1) != 0,
                    (state.renderFlags & 2) != 0);
        };
    }

    private AnchorPoint structuralChildAnchor(int index) {
        if (structuralChildren == null || index < 0 || index >= structuralChildren.length) {
            return new AnchorPoint(state.x, state.y, (state.renderFlags & 1) != 0,
                    (state.renderFlags & 2) != 0);
        }
        StructuralChild child = structuralChildren[index];
        return new AnchorPoint(child.x, child.y, child.flipX, false);
    }

    private void capturePlayersInFrostPuff(EffectChild child, PlayableEntity fallbackPlayer) {
        if (!child.isCaptureActive()) {
            return;
        }
        List<PlayableEntity> participants = frostCaptureParticipants(fallbackPlayer);
        for (int index = 0; index < participants.size(); index++) {
            PlayableEntity candidate = participants.get(index);
            if (candidate instanceof AbstractPlayableSprite sprite && canFrostCapture(sprite, child)) {
                boolean beforeBottomSolid = child.nativeSlot >= 0
                        && child.nativeSlot < bottomStructuralChildSlot();
                // Native children at/after the bottom structural child run
                // after its SolidObjectFull call, so their sub_8A9C6 capture
                // belongs to this pass. The folded boss updates these children
                // before the manager reaches their native slots, so a
                // transition into the active animation range is still observed
                // during the same native pass once the solid child has been
                // culled. While that checkpoint is live, retain the native
                // pre/post-solid promotion used by the earlier arena phase.
                boolean currentSolidCheckpoint = bottomStructuralChildSlot() < 0
                        ? !beforeBottomSolid
                        : child.captureWasActiveBeforeUpdate && !beforeBottomSolid;
                if (index < 2) {
                    queueFrostCapture(index, sprite, child.x, child.nativeSlot,
                            beforeBottomSolid, currentSolidCheckpoint);
                } else {
                    queueExtendedFrostCapture(sprite, child.x, child.nativeSlot,
                            beforeBottomSolid, currentSolidCheckpoint);
                }
            }
        }
    }

    private void promoteExtendedFrostCaptures() {
        for (int[] capture : extendedFrostCaptures.values()) {
            if (capture[EXTENDED_CAPTURE_PHASE] == EXTENDED_CAPTURE_PENDING) {
                capture[EXTENDED_CAPTURE_PHASE] = EXTENDED_CAPTURE_READY;
            } else if (capture[EXTENDED_CAPTURE_PHASE] == EXTENDED_CAPTURE_PENDING_BEFORE_SOLID) {
                capture[EXTENDED_CAPTURE_PHASE] = EXTENDED_CAPTURE_READY_BEFORE_SOLID;
            }
        }
    }

    private void queueExtendedFrostCapture(AbstractPlayableSprite player, int sourceX, int sourceSlot,
            boolean beforeBottomSolid, boolean currentSolidCheckpoint) {
        if (extendedFrostCaptures.containsKey(player)) {
            return;
        }
        int phase = beforeBottomSolid
                ? EXTENDED_CAPTURE_PENDING_BEFORE_SOLID
                : currentSolidCheckpoint ? EXTENDED_CAPTURE_READY : EXTENDED_CAPTURE_PENDING;
        extendedFrostCaptures.put(player, new int[]{phase, sourceX, sourceSlot});
    }

    private int bottomStructuralChildSlot() {
        return structuralBottomChildSlot;
    }

    private void queueFrostCapture(int participantIndex, AbstractPlayableSprite player,
            int sourceX, int sourceSlot,
            boolean beforeBottomSolid, boolean currentSolidCheckpoint) {
        int bit = 1 << participantIndex;
        int allCaptureMasks = pendingFrostCaptureMask | readyFrostCaptureMask
                | pendingBeforeSolidFrostCaptureMask | readyBeforeSolidFrostCaptureMask;
        if ((allCaptureMasks & bit) != 0) {
            return;
        }
        setNativeFrostCaptureOwner(participantIndex, player);
        if (beforeBottomSolid) {
            pendingBeforeSolidFrostCaptureMask |= bit;
            if (participantIndex == 0) {
                pendingBeforeSolidFrostCaptureP1SourceX = sourceX;
                pendingBeforeSolidFrostCaptureP1SourceSlot = sourceSlot;
            } else {
                pendingBeforeSolidFrostCaptureP2SourceX = sourceX;
                pendingBeforeSolidFrostCaptureP2SourceSlot = sourceSlot;
            }
            return;
        }
        if (currentSolidCheckpoint) {
            readyFrostCaptureMask |= bit;
            if (participantIndex == 0) {
                readyFrostCaptureP1SourceX = sourceX;
                readyFrostCaptureP1SourceSlot = sourceSlot;
            } else {
                readyFrostCaptureP2SourceX = sourceX;
                readyFrostCaptureP2SourceSlot = sourceSlot;
            }
            return;
        }
        pendingFrostCaptureMask |= bit;
        if (participantIndex == 0) {
            pendingFrostCaptureP1SourceX = sourceX;
            pendingFrostCaptureP1SourceSlot = sourceSlot;
        } else {
            pendingFrostCaptureP2SourceX = sourceX;
            pendingFrostCaptureP2SourceSlot = sourceSlot;
        }
    }

    private void applyReadyBeforeSolidFrostCaptures(PlayableEntity fallbackPlayer) {
        List<PlayableEntity> participants = frostCaptureParticipants(fallbackPlayer);
        Map<AbstractPlayableSprite, Boolean> nativeCaptures = new IdentityHashMap<>();
        for (int index = 0; index < 2; index++) {
            PlayableEntity candidate = index < participants.size() ? participants.get(index) : null;
            int bit = 1 << index;
            if ((readyBeforeSolidFrostCaptureMask & bit) == 0) {
                continue;
            }
            if (!(candidate instanceof AbstractPlayableSprite sprite)
                    || sprite != nativeFrostCaptureOwner(index)) {
                readyBeforeSolidFrostCaptureMask &= ~bit;
                clearNativeFrostCaptureOwner(index);
                continue;
            }
            int[] extendedCapture = extendedFrostCaptures.get(sprite);
            if (extendedCapture != null
                    && extendedCapture[EXTENDED_CAPTURE_PHASE] == EXTENDED_CAPTURE_READY_BEFORE_SOLID) {
                // The native bit belonged to the player that occupied this
                // ROM slot when it was queued. Do not reassign it if an
                // identity-tracked extension player has since moved here.
                readyBeforeSolidFrostCaptureMask &= ~bit;
                continue;
            }
            readyBeforeSolidFrostCaptureMask &= ~bit;
            int sourceX = index == 0
                    ? readyBeforeSolidFrostCaptureP1SourceX
                    : readyBeforeSolidFrostCaptureP2SourceX;
            int sourceSlot = index == 0
                    ? readyBeforeSolidFrostCaptureP1SourceSlot
                    : readyBeforeSolidFrostCaptureP2SourceSlot;
            frostCapture(sprite, sprite.getCentreX(), sprite.getCentreY(), sourceX, sourceSlot, false);
            nativeCaptures.put(sprite, Boolean.TRUE);
            clearNativeFrostCaptureOwner(index);
        }
        applyReadyBeforeSolidExtendedFrostCaptures(participants, nativeCaptures);
    }

    private void applyReadyBeforeSolidExtendedFrostCaptures(List<PlayableEntity> participants,
            Map<AbstractPlayableSprite, Boolean> nativeCaptures) {
        for (PlayableEntity candidate : participants) {
            if (!(candidate instanceof AbstractPlayableSprite sprite)) {
                continue;
            }
            int[] capture = extendedFrostCaptures.get(sprite);
            if (capture == null
                    || capture[EXTENDED_CAPTURE_PHASE] != EXTENDED_CAPTURE_READY_BEFORE_SOLID) {
                continue;
            }
            extendedFrostCaptures.remove(sprite);
            if (!nativeCaptures.containsKey(sprite)) {
                frostCapture(sprite, sprite.getCentreX(), sprite.getCentreY(),
                        capture[EXTENDED_CAPTURE_SOURCE_X], capture[EXTENDED_CAPTURE_SOURCE_SLOT], true);
            }
        }
    }

    private void applyReadyFrostCapture(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite sprite)) {
            return;
        }
        List<PlayableEntity> participants = frostCaptureParticipants(player);
        discardReadyNativeCapturesWithChangedOwners(participants);
        int[] extendedCapture = extendedFrostCaptures.get(sprite);
        if (extendedCapture != null && extendedCapture[EXTENDED_CAPTURE_PHASE] == EXTENDED_CAPTURE_READY) {
            for (int index = 0; index < participants.size() && index < 2; index++) {
                if (participants.get(index) == player) {
                    // A vacated native slot must not replace this player's
                    // identity-keyed source or allocation-safe extension path.
                    readyFrostCaptureMask &= ~(1 << index);
                    break;
                }
            }
            extendedFrostCaptures.remove(sprite);
            frostCapture(sprite, sprite.getCentreX(), sprite.getCentreY(),
                    extendedCapture[EXTENDED_CAPTURE_SOURCE_X], extendedCapture[EXTENDED_CAPTURE_SOURCE_SLOT], true);
            return;
        }
        for (int index = 0; index < participants.size() && index < 2; index++) {
            if (participants.get(index) == player) {
                int bit = 1 << index;
                if ((readyFrostCaptureMask & bit) != 0) {
                    readyFrostCaptureMask &= ~bit;
                    int sourceX = index == 0
                            ? readyFrostCaptureP1SourceX
                            : readyFrostCaptureP2SourceX;
                    int sourceSlot = index == 0
                            ? readyFrostCaptureP1SourceSlot
                            : readyFrostCaptureP2SourceSlot;
                    frostCapture(sprite, sprite.getCentreX(), sprite.getCentreY(), sourceX, sourceSlot, false);
                    clearNativeFrostCaptureOwner(index);
                }
                return;
            }
        }
    }

    private void discardReadyNativeCapturesWithChangedOwners(List<PlayableEntity> participants) {
        for (int index = 0; index < 2; index++) {
            int bit = 1 << index;
            if ((readyFrostCaptureMask & bit) == 0) {
                continue;
            }
            PlayableEntity current = index < participants.size() ? participants.get(index) : null;
            if (current != nativeFrostCaptureOwner(index)) {
                readyFrostCaptureMask &= ~bit;
                clearNativeFrostCaptureOwner(index);
            }
        }
    }

    private AbstractPlayableSprite nativeFrostCaptureOwner(int participantIndex) {
        return participantIndex == 0 ? nativeP1FrostCaptureOwner : nativeP2FrostCaptureOwner;
    }

    private void setNativeFrostCaptureOwner(int participantIndex, AbstractPlayableSprite player) {
        if (participantIndex == 0) {
            nativeP1FrostCaptureOwner = player;
        } else {
            nativeP2FrostCaptureOwner = player;
        }
    }

    private void clearNativeFrostCaptureOwner(int participantIndex) {
        setNativeFrostCaptureOwner(participantIndex, null);
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        applyReadyFrostCapture(player);
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        applyReadyFrostCapture(player);
    }

    private List<PlayableEntity> frostCaptureParticipants(PlayableEntity fallbackPlayer) {
        try {
            return services().playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
        } catch (RuntimeException ignored) {
            return fallbackPlayer == null ? List.of() : List.of(fallbackPlayer);
        }
    }

    private boolean canFrostCapture(AbstractPlayableSprite player, EffectChild child) {
        if (player.isObjectControlled() || player.getDead() || player.isDebugMode()) {
            return false;
        }
        if (player.getInvulnerable() || player.getInvulnerableFrames() > 0 || player.getInvincibleFrames() > 0) {
            return false;
        }

        int dx = player.getCentreX() - child.x;
        int dy = player.getCentreY() - child.y;
        return dx >= child.captureMinX && dx < child.captureMaxX
                && dy >= child.captureMinY && dy < child.captureMaxY;
    }

    private void frostCapture(AbstractPlayableSprite player, int capturedX, int capturedY,
            int sourceX, int sourceSlot, boolean engineSidekickExtension) {
        boolean flipped = player.getDirection() == Direction.LEFT;
        IczFreezerObjectInstance.FrozenPlayerBlock block =
                new IczFreezerObjectInstance.FrozenPlayerBlock(player, capturedX, capturedY,
                        sourceX, flipped, true, engineSidekickExtension);
        if (engineSidekickExtension) {
            // Extra engine participants have no native player/SST pair. Keep
            // their gameplay block rewindable without changing the ROM's slot
            // pressure or depending on a free slot after the frost-puff child.
            services().objectManager().addRewindableAuxiliaryDynamicObject(block);
        }

        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setAir(true);
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        player.setAnimationId(0x1A);
        if (!engineSidekickExtension) {
            spawnFrostBlock(block, sourceSlot);
        }
    }

    private void spawnFrostBlock(IczFreezerObjectInstance.FrozenPlayerBlock block, int sourceSlot) {
        if (services().objectManager() != null && sourceSlot >= 0) {
            services().objectManager().addDynamicObjectAfterSlot(block, sourceSlot);
        } else {
            spawnDynamicObject(block);
        }
    }

    private void releaseEffectSlot(EffectChild child) {
        if (child.nativeSlot >= 0 && services().objectManager() != null) {
            services().objectManager().releaseDynamicSlot(child.nativeSlot);
            child.nativeSlot = -1;
        }
    }

    private void updateRobotnikShip() {
        robotnikShipVisible = arenaGateInitialized && !robotnikShipDeleted;
        if (robotnikShipEscaping) {
            updateRobotnikEscape();
            robotnikHeadFrame = 3;
            return;
        }
        robotnikShipX = state.x;
        robotnikShipXFixed = robotnikShipX << 8;
        robotnikShipY = state.y;
        robotnikShipFrame = ROBOTNIK_SHIP_FRAME;
        if (state.defeated) {
            robotnikHeadFrame = 3;
            tickRobotnikExplosions();
            return;
        }
        if (state.invulnerable) {
            robotnikHeadFrame = 2;
            return;
        }
        robotnikHeadAnimTimer++;
        robotnikHeadFrame = (robotnikHeadAnimTimer / 6) & 1;
    }

    private void updateHitFlash() {
        if (hitFlashTimer <= 0) {
            return;
        }
        hitFlashBright = !hitFlashBright;
        applyHitFlashPalette(hitFlashBright);
        hitFlashTimer--;
        if (hitFlashTimer == 0) {
            hitFlashBright = false;
            state.invulnerable = false;
            applyHitFlashPalette(false);
        }
    }

    private void applyHitFlashPalette(boolean bright) {
        try {
            Level level = services().currentLevel();
            if (level == null) {
                return;
            }
            S3kPaletteWriteSupport.applyColors(
                    services().paletteOwnershipRegistryOrNull(),
                    level,
                    services().graphicsManager(),
                    S3kPaletteOwners.ICZ_END_BOSS,
                    S3kPaletteOwners.PRIORITY_OBJECT_OVERRIDE,
                    BOSS_PALETTE_LINE,
                    HIT_FLASH_COLOR_INDICES,
                    bright ? HIT_FLASH_BRIGHT_COLORS : HIT_FLASH_NORMAL_COLORS);
        } catch (Exception ignored) {
            // Headless unit contexts often do not install a ROM-backed level.
        }
    }

    @Override
    public void onPlayerAttack(PlayableEntity player, TouchResponseResult result) {
        takeHit(false);
    }

    public void forceHitForTesting() {
        takeHit(true);
    }

    private void takeHit(boolean ignoreInvulnerability) {
        if (!arenaGateComplete || defeatStarted || state.defeated) {
            return;
        }
        if (state.invulnerable && !ignoreInvulnerability) {
            return;
        }
        state.hitCount--;
        state.invulnerable = true;
        state.invulnerabilityTimer = INVULNERABILITY_TIME;
        hitFlashTimer = INVULNERABILITY_TIME;
        services().playSfx(getBossHitSfxId());
        onHitTaken(state.hitCount);
        if (state.hitCount <= 0) {
            startDefeat();
        } else if (state.hitCount == DAMAGED_PHASE_HIT_COUNT && !damagedFinalPhase) {
            startDamagedFinalPhase();
        }
    }

    private void startDamagedFinalPhase() {
        damagedFinalPhase = true;
        state.routine = ROUTINE_DEFEAT_RISE;
        damagedRiseTimer = DAMAGED_RISE_TIME;
        if (structuralChildren == null) {
            return;
        }
        structuralChildren[0].frame = DAMAGED_TOP_FRAME;
        damagedTopSteamTimer = DAMAGED_TOP_STEAM_INITIAL_TIME;
        damagedTopSteamTimerJustArmed = true;
        // loc_849D8 calls Set_IndexedVelocity with d0=0. The native child
        // subtypes select rows 1 and 2 of Obj_VelocityIndex: middle is
        // (+$100,-$100), bottom-solid is (-$200,-$200), before the parent's
        // render-direction X flip is applied.
        structuralChildren[MIDDLE_CHILD_INDEX].detach(0x100, -0x100);
        structuralChildren[BOTTOM_CHILD_INDEX].detach(-0x200, -0x200);
    }

    private void releaseDetachedStructuralChildSlots() {
        if (structuralChildSlots == null || services().objectManager() == null) {
            return;
        }
        // loc_720D4 deletes the bottom hurt child as soon as the parent enters
        // the damaged phase. The middle and bottom-solid children instead run
        // Obj_FlickerMove and free their SSTs only when its native cull test
        // fires; releasing them at phase entry changes the allocation order of
        // the frost children created in the meantime.
        if (damagedFinalPhase) {
            freeStructuralReservation(FOLDED_BOTTOM_HURT_CHILD_INDEX);
        }
        if (structuralChildren == null || services().camera() == null) {
            return;
        }
        int cameraX = Short.toUnsignedInt(services().camera().getX());
        int cameraY = Short.toUnsignedInt(services().camera().getY());
        releaseStructuralChildIfGone(
                FOLDED_MIDDLE_CHILD_INDEX, structuralChildren[MIDDLE_CHILD_INDEX], cameraX, cameraY);
        releaseStructuralChildIfGone(
                BOTTOM_STRUCTURAL_CHILD_RESERVED_INDEX, structuralChildren[BOTTOM_CHILD_INDEX], cameraX, cameraY);
    }

    private void releaseStructuralChildIfGone(
            int reservedIndex, StructuralChild child, int cameraX, int cameraY) {
        if (child.detached && child.isOutsideNativeFlickerBounds(cameraX, cameraY)) {
            freeStructuralReservation(reservedIndex);
        }
    }

    private void freeStructuralReservation(int reservedIndex) {
        if (structuralChildSlots[reservedIndex] >= 0) {
            services().objectManager().freeReservedChildSlot(spawn, reservedIndex);
            structuralChildSlots[reservedIndex] = -1;
            if (reservedIndex == BOTTOM_STRUCTURAL_CHILD_RESERVED_INDEX) {
                structuralBottomChildSlot = -1;
            }
        }
    }

    private void startDefeat() {
        state.hitCount = 0;
        state.defeated = true;
        defeatStarted = true;
        state.routine = ROUTINE_DEFEAT_RISE;
        state.xVel = 0;
        state.yVel = 0;
        parentFlags |= PARENT_FLAG_DEFEATED;
        // loc_722C6 installs Wait_FadeToLevelMusic while retaining the boss's
        // live $2E=$3F wait. When that expires, loc_71D80 creates the shell
        // fragments and continues through Obj_Wait with the freshly seeded
        // (2*60)-1 timer before loc_71D9E performs the capsule handoff.
        defeatTimer = DEFEAT_SHELL_RELEASE_WAIT;
        stopBossSnowdustEmitter();
        startRobotnikDefeatExplosions();
        if (services().gameState() != null) {
            services().gameState().addScore(1000);
        }
        // ROM loc_722E6: jmp (BossDefeated_StopTimer).l (sonic3k.asm:151307).
        stopLevelTimerOnBossDefeat();
    }

    private void startRobotnikDefeatExplosions() {
        if (robotnikExplosionController != null) {
            return;
        }
        robotnikExplosionController = new S3kBossExplosionController(
                robotnikShipX, robotnikShipY, ROBOTNIK_SHIP_EXPLOSION_SUBTYPE, services().rng());
    }

    private void startRobotnikEscape() {
        if (robotnikShipEscaping || robotnikShipDeleted) {
            return;
        }
        robotnikShipEscaping = true;
        robotnikShipFlyingRight = false;
        robotnikShipFlameVisible = false;
        robotnikShipFrame = ROBOTNIK_SHIP_ESCAPE_FRAME;
        robotnikShipXFixed = robotnikShipX << 8;
        robotnikShipEscapeTimer = ROBOTNIK_SHIP_ESCAPE_TIME;
    }

    private void updateRobotnikEscape() {
        tickRobotnikExplosions();
        robotnikShipFrame = ROBOTNIK_SHIP_ESCAPE_FRAME;
        if (!robotnikShipFlyingRight) {
            int targetY = services().camera() != null
                    ? (services().camera().getY() & 0xFFFF) + 0x40
                    : robotnikShipY;
            if (targetY >= robotnikShipY) {
                robotnikShipFlyingRight = true;
                robotnikShipFlameVisible = true;
            } else {
                robotnikShipY--;
                robotnikShipFlameVisible = false;
                return;
            }
        }
        robotnikShipFlameVisible = true;
        robotnikShipXFixed += ROBOTNIK_SHIP_ESCAPE_VELOCITY;
        robotnikShipX = robotnikShipXFixed >> 8;
        if (robotnikShipEscapeTimer-- < 0) {
            robotnikShipEscaping = false;
            robotnikShipDeleted = true;
            robotnikShipVisible = false;
            robotnikShipFlameVisible = false;
        }
    }

    private void tickRobotnikExplosions() {
        if (robotnikExplosionController != null && !robotnikExplosionController.isFinished()) {
            robotnikExplosionController.tick();
            spawnPendingRobotnikExplosions();
        }
    }

    private void spawnPendingRobotnikExplosions() {
        var pending = robotnikExplosionController.drainPendingExplosions();
        for (var entry : pending) {
            if (entry.playSfx()) {
                services().playSfx(Sonic3kSfx.EXPLODE.id);
            }
            spawnChild(() -> new S3kBossExplosionChild(entry.x(), entry.y()));
        }
    }

    private void updateDamagedRise() {
        if (damagedRiseTimer-- >= 0) {
            state.yFixed += 0x8000;
            state.updatePositionFromFixed();
            tickWait();
            return;
        }
        state.routine = ROUTINE_SWING;
    }

    private void updateDefeat() {
        if (defeatHandoffComplete) {
            updateRobotnikShip();
            if (robotnikShipDeleted) {
                setDestroyed(true);
            }
            return;
        }
        // Wait_FadeToLevelMusic first decrements the retained $3F word on the
        // pass after defeat. loc_71D80 then tail-enters child creation/Obj_Wait,
        // which consumes the freshly seeded 119 word during that same dispatch;
        // the folded engine phase returns after creating the debris, so its
        // second counter must preserve that already-consumed native entry.
        boolean waiting = defeatShellReleased
                ? defeatTimer-- >= 0
                : --defeatTimer >= 0;
        if (waiting) {
            updateStructuralChildren();
            updateEffectChildren(null);
            updateRobotnikShip();
            return;
        }
        if (!defeatShellReleased) {
            defeatShellReleased = true;
            spawnDefeatDebrisChildren();
            defeatTimer = DEFEAT_CAPSULE_HANDOFF_WAIT;
            return;
        }
        completeDefeatHandoff();
    }

    private void completeDefeatHandoff() {
        defeatHandoffComplete = true;
        if (services().gameState() != null) {
            services().gameState().setCurrentBossId(0);
        }
        if (services().camera() != null) {
            services().camera().setMinX((short) (services().camera().getX() & 0xFFFF));
            services().camera().setMaxYTarget(services().camera().getMaxY());
        }
        spawnFreeChild(() -> new IczEndBossEggCapsuleInstance(0x4560, 0x06A3));
        // loc_71D9E writes Camera_stored_max_X_pos, then makes a fallible
        // AllocateObject attempt for Obj_IncLevEndXGradual. Live max X stays
        // locked if the SST pool is full; otherwise the helper advances it
        // with the shared $4000 accumulator.
        spawnFreeChild(() -> new HczEndBossGradualMaxXExtender(
                state.x, state.y, CAPSULE_CAMERA_MAX_X));
        int escapeShipX = robotnikShipX;
        int escapeShipY = robotnikShipY;
        spawnChild(() -> new IczEndBossRobotnikEscapeShip(escapeShipX, escapeShipY));
        robotnikShipVisible = false;
        robotnikShipDeleted = true;
        services().playMusic(Sonic3kMusic.ICZ2.id);
        setDestroyed(true);
    }

    private void spawnDefeatDebrisChildren() {
        for (EffectChild child : effectChildren) {
            releaseEffectSlot(child);
        }
        effectChildren.clear();
        boolean flipped = (state.renderFlags & 1) != 0;
        for (int i = 0; i < DEFEAT_DEBRIS_OFFSETS.length; i++) {
            int[] offset = DEFEAT_DEBRIS_OFFSETS[i];
            int[] velocity = DEFEAT_DEBRIS_VELOCITIES[i];
            int childX = state.x + (flipped ? -offset[0] : offset[0]);
            int childY = state.y + offset[1];
            int xVel = flipped ? -velocity[0] : velocity[0];
            int yVel = velocity[1];
            int frame = 0x16 + i;
            boolean childFlipped = flipped;
            spawnChild(() -> new IczEndBossDefeatDebrisChild(childX, childY, xVel, yVel, frame, childFlipped));
        }
    }

    @Override
    public int getCollisionFlags() {
        if (!arenaGateComplete || state.invulnerable || state.defeated || defeatStarted) {
            return 0;
        }
        return 0xC0 | (getCollisionSizeIndex() & 0x3F);
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (!arenaGateComplete || state.defeated || defeatStarted) {
            return null;
        }
        List<TouchRegion> regions = new ArrayList<>(2);
        int bodyFlags = getCollisionFlags();
        if (bodyFlags != 0) {
            regions.add(new TouchRegion(state.x, state.y, bodyFlags));
        }
        if (!damagedFinalPhase) {
            regions.add(new TouchRegion(getBottomHurtXForTesting(), getBottomHurtYForTesting(), BOTTOM_HURT_FLAGS));
        }
        return regions.toArray(TouchRegion[]::new);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TouchResponseProfile.fromProvider(this);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TouchResponseProfile.fromProvider(this, multiRegionSource);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return BOTTOM_SOLID_PARAMS;
    }

    @Override
    public int getTopLandingHalfWidth(PlayableEntity player, int collisionHalfWidth) {
        // ROM Solid_Landed / loc_1E154 (sonic3k.asm:41611-41621) re-reads
        // width_pixels(a0) for the landing X gate. The solid bottom child is
        // initialized from word_7231E with width_pixels = $18
        // (sonic3k.asm:150958-150962,151337-151339); ObjDat3_72324 belongs
        // to its later effect children, not this SolidObjectFull caller.
        // loc_71F30 passes d1 = $23 (sonic3k.asm:150928-150939), so the
        // default d1 - $B = $18 heuristic happens to match the native child.
        return 0x18;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return arenaGateComplete && !state.defeated && !defeatStarted && !damagedFinalPhase;
    }

    @Override
    public int getPieceCount() {
        return 1;
    }

    @Override
    public int getPieceX(int pieceIndex) {
        return getSolidPlatformXForTesting();
    }

    @Override
    public int getPieceY(int pieceIndex) {
        return getSolidPlatformYForTesting();
    }

    @Override
    public int getPieceFreshContactX(int pieceIndex, PlayableEntity player) {
        // Parent slot 5 has already published its next X when the engine's
        // folded solid pass runs. Native child slot 28 still uses the X saved
        // at its own loc_71F30 entry for this contact window.
        return getPreUpdateX();
    }

    @Override
    public int getPieceFreshContactY(int pieceIndex, PlayableEntity player) {
        StructuralChild bottom = structuralChildren[BOTTOM_CHILD_INDEX];
        return getPreUpdateY() + bottom.baseDy + bottom.localYOffset;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // The engine folds loc_71F30's solid child into the parent. On a fresh
        // contact the shared pre-object pass therefore sees the child's $43
        // shift one pixel later than the ROM child SST does. Both the fresh
        // Sonic's first contact reaches loc_71F30 curled; the later
        // ResetOnFloor radius restoration exposes the folded child's one-pixel
        // entry phase. Standing Tails lands directly on the ordinary surface.
        StructuralChild bottom = structuralChildren == null
                ? null
                : structuralChildren[BOTTOM_CHILD_INDEX];
        boolean childShiftRoutineActive = bottom != null
                && bottom.shiftVelocity != 0
                && bottom.shiftTimer >= 0;
        return childShiftRoutineActive && solidTopYRadius - player.getYRadius() > 1 ? 1 : 0;
    }

    @Override
    public int getContinuedRideSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // The engine publishes the folded child's new whole-pixel position
        // before its shared rider pass. Native loc_71F30 instead consumes the
        // prior child position and then publishes this displacement, so the
        // exact signed child delta is the ride-phase correction.
        return bottomChildWholePixelDelta;
    }

    @Override
    public boolean usesPreUpdateXForContinuedRide(PlayableEntity player) {
        // Parent slot 5 moves before child slot $1C calls SolidObjectFull.
        // The engine's folded solid pass runs after the parent update, so its
        // saved pre-update X is the native child's carry reference for this pass.
        return true;
    }

    @Override
    public boolean skipsCpuSidekickWhenRenderFlagOffScreen() {
        return true;
    }

    @Override
    public int getCollisionProperty() {
        return state.hitCount;
    }

    @Override
    protected boolean usesBaseHitHandler() {
        return false;
    }

    @Override
    protected boolean usesDefeatSequencer() {
        return false;
    }

    @Override
    protected int getInitialHitCount() {
        return HIT_COUNT;
    }

    @Override
    protected void onHitTaken(int remainingHits) {
        mappingFrame = 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE;
    }

    @Override
    protected int getInvulnerabilityDuration() {
        return INVULNERABILITY_TIME;
    }

    @Override
    protected int getBossHitSfxId() {
        return Sonic3kSfx.BOSS_HIT.id;
    }

    @Override
    protected int getBossExplosionSfxId() {
        return Sonic3kSfx.EXPLODE.id;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!arenaGateInitialized) {
            return;
        }
        if (defeatHandoffComplete) {
            renderRobotnikShip();
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ICZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean flipped = (state.renderFlags & 1) != 0;
        if (structuralChildren != null) {
            drawStructuralChild(renderer, BOTTOM_CHILD_INDEX);
        }
        renderer.drawFrameIndexWithPaletteBase(mappingFrame, state.x, state.y, flipped, false, BODY_PALETTE_BASE);
        if (structuralChildren != null) {
            drawStructuralChild(renderer, MIDDLE_CHILD_INDEX);
            drawStructuralChild(renderer, 0);
        }
        for (EffectChild child : effectChildren) {
            if (!child.isVisible()) {
                continue;
            }
            renderer.drawFrameIndexWithPaletteBase(child.frame, child.x, child.y, child.flipX, child.flipY,
                    BODY_PALETTE_BASE);
        }

        renderRobotnikShip();
    }

    private void drawStructuralChild(PatternSpriteRenderer renderer, int index) {
        if (index < 0 || structuralChildren == null || index >= structuralChildren.length) {
            return;
        }
        StructuralChild child = structuralChildren[index];
        renderer.drawFrameIndexWithPaletteBase(child.frame, child.x, child.y, child.flipX, false,
                BODY_PALETTE_BASE);
    }

    private void renderRobotnikShip() {
        if (!robotnikShipVisible) {
            return;
        }
        PatternSpriteRenderer shipRenderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
        if (shipRenderer == null || !shipRenderer.isReady()) {
            return;
        }
        boolean flipped = robotnikShipFlipX();
        shipRenderer.drawFrameIndex(robotnikShipFrame, robotnikShipX, robotnikShipY, flipped, false,
                ROBOTNIK_SHIP_PALETTE_LINE);
        shipRenderer.drawFrameIndex(robotnikHeadFrame, robotnikShipX, robotnikShipY + ROBOTNIK_HEAD_Y_OFFSET,
                flipped, false, ROBOTNIK_SHIP_PALETTE_LINE);
        if (robotnikShipFlameVisible) {
            int flameDx = flipped ? -ROBOTNIK_SHIP_FLAME_DX : ROBOTNIK_SHIP_FLAME_DX;
            shipRenderer.drawFrameIndex(ROBOTNIK_SHIP_FLAME_FRAME, robotnikShipX + flameDx, robotnikShipY,
                    flipped, false, ROBOTNIK_SHIP_PALETTE_LINE);
        }
    }

    private boolean robotnikShipFlipX() {
        return robotnikShipFlyingRight || (state.renderFlags & 1) != 0;
    }

    public int getCurrentRoutine() {
        return state.routine;
    }

    public int getRoutineTimerForTesting() {
        return routineTimer;
    }

    public int getYVelocityForTesting() {
        return state.yVel;
    }

    public int getXVelocityForTesting() {
        return state.xVel;
    }

    public int getSwingAmplitudeForTesting() {
        return swingAmplitude;
    }

    public boolean isFrostPuffArmedForTesting() {
        return (parentFlags & PARENT_FLAG_FROST_PUFF) != 0;
    }

    public boolean isArenaGateCompleteForTesting() {
        return arenaGateComplete;
    }

    public boolean isDefeatStartedForTesting() {
        return defeatStarted;
    }

    public int getDamagedTopSteamEmissionCountForTesting() {
        return damagedTopSteamEmissionCount;
    }

    public int getBossPaletteLineForTesting() {
        return BOSS_PALETTE_LINE;
    }

    public int getBodyPaletteBaseForTesting() {
        return BODY_PALETTE_BASE;
    }

    public int getRobotnikShipPaletteLineForTesting() {
        return ROBOTNIK_SHIP_PALETTE_LINE;
    }

    public boolean isRobotnikShipVisibleForTesting() {
        return robotnikShipVisible;
    }

    public int getRobotnikShipXForTesting() {
        return robotnikShipX;
    }

    public int getRobotnikShipYForTesting() {
        return robotnikShipY;
    }

    public int getRobotnikShipFrameForTesting() {
        return robotnikShipFrame;
    }

    public int getRobotnikHeadXForTesting() {
        return robotnikShipX;
    }

    public int getRobotnikHeadYForTesting() {
        return robotnikShipY + ROBOTNIK_HEAD_Y_OFFSET;
    }

    public int getRobotnikHeadFrameForTesting() {
        return robotnikHeadFrame;
    }

    public int getBottomHurtXForTesting() {
        return getBottomChildXForTesting();
    }

    public int getBottomHurtYForTesting() {
        return getBottomChildYForTesting() + BOTTOM_HURT_CHILD_DY;
    }

    public int getBodyMappingFrameForTesting() {
        return mappingFrame;
    }

    public int getStructuralChildCountForTesting() {
        return structuralChildren == null ? 0 : structuralChildren.length;
    }

    public int getStructuralChildFrameForTesting(int index) {
        if (structuralChildren == null || index < 0 || index >= structuralChildren.length) {
            return -1;
        }
        return structuralChildren[index].frame;
    }

    public int getStructuralChildXForTesting(int index) {
        if (structuralChildren == null || index < 0 || index >= structuralChildren.length) {
            return state.x;
        }
        return structuralChildren[index].x;
    }

    public int getStructuralChildYForTesting(int index) {
        if (structuralChildren == null || index < 0 || index >= structuralChildren.length) {
            return state.y;
        }
        return structuralChildren[index].y;
    }

    public int getFrostPuffCountForTesting() {
        return effectChildren.size();
    }

    public int getFrostPuffFrameForTesting(int index) {
        if (index < 0 || index >= effectChildren.size()) {
            return -1;
        }
        return effectChildren.get(index).frame;
    }

    public int getFrostPuffXForTesting(int index) {
        if (index < 0 || index >= effectChildren.size()) {
            return state.x;
        }
        return effectChildren.get(index).x;
    }

    public int getFrostPuffYForTesting(int index) {
        if (index < 0 || index >= effectChildren.size()) {
            return state.y;
        }
        return effectChildren.get(index).y;
    }

    public boolean isFrostPuffCaptureActiveForTesting(int index) {
        return index >= 0 && index < effectChildren.size()
                && effectChildren.get(index).isCaptureActive();
    }

    public boolean isFrostPuffVisibleForTesting(int index) {
        return index >= 0 && index < effectChildren.size()
                && effectChildren.get(index).isVisible();
    }

    public int getBottomChildLocalYOffsetForTesting() {
        if (structuralChildren == null || structuralChildren.length <= BOTTOM_CHILD_INDEX) {
            return 0;
        }
        return structuralChildren[BOTTOM_CHILD_INDEX].localYOffset;
    }

    public int getBottomChildShiftTimerForTesting() {
        if (structuralChildren == null || structuralChildren.length <= BOTTOM_CHILD_INDEX) {
            return 0;
        }
        return structuralChildren[BOTTOM_CHILD_INDEX].shiftTimer;
    }

    public int getBottomChildWholePixelDeltaForTesting() {
        return bottomChildWholePixelDelta;
    }

    public int getBottomChildXForTesting() {
        if (structuralChildren == null || structuralChildren.length <= BOTTOM_CHILD_INDEX) {
            return state.x;
        }
        StructuralChild child = structuralChildren[BOTTOM_CHILD_INDEX];
        return child.x == 0 ? state.x + child.baseDx : child.x;
    }

    public int getBottomChildYForTesting() {
        if (structuralChildren == null || structuralChildren.length <= BOTTOM_CHILD_INDEX) {
            return state.y + BOTTOM_CHILD_DY;
        }
        StructuralChild child = structuralChildren[BOTTOM_CHILD_INDEX];
        return child.y == 0 ? state.y + child.baseDy + child.localYOffset : child.y;
    }

    public int getSolidPlatformXForTesting() {
        return getBottomChildXForTesting();
    }

    public int getSolidPlatformYForTesting() {
        return getBottomChildYForTesting();
    }

    private static final class IczEndBossDefeatDebrisChild extends GravityDebrisChild
            implements SpawnCoordinateZeroScalarArgsRewindRecreatable {
        private static final int GRAVITY = 0x38;

        private int frame;
        private boolean flipX;
        private boolean visible = true;

        private IczEndBossDefeatDebrisChild(ObjectSpawn spawn) {
            this(spawn.x(), spawn.y(), 0, 0, 0, false);
        }

        private IczEndBossDefeatDebrisChild(int x, int y, int xVel, int yVel, int frame, boolean flipX) {
            super(new ObjectSpawn(x, y, Sonic3kObjectIds.ICZ_END_BOSS, 0, 0, false, 0),
                    "ICZEndBossDefeatDebris", xVel, yVel, GRAVITY);
            this.frame = frame;
            this.flipX = flipX;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            visible = !visible;
            super.update(vIntRunCount, player);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (!visible) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ICZ_END_BOSS);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndexWithPaletteBase(frame, getX(), getY(), flipX, false, BODY_PALETTE_BASE);
        }
    }

    private static final class IczEndBossRobotnikEscapeShip extends AbstractObjectInstance
            implements SpawnCoordinateRewindRecreatable {
        private static final int ESCAPE_FRAME = 0x0A;
        private static final int HEAD_FRAME_ANGRY = 3;
        private static final int HEAD_Y_OFFSET = -0x1C;
        private static final int FLAME_FRAME = 0x06;
        private static final int FLAME_DX = 0x1E;
        private static final int ESCAPE_X_VELOCITY = 0x0300;
        private static final int ESCAPE_TIME = 0x0100;

        private int x;
        private int xFixed;
        private int y;
        private int timer = ESCAPE_TIME;
        private boolean flyingRight;

        private IczEndBossRobotnikEscapeShip() {
            this(0, 0);
        }

        private IczEndBossRobotnikEscapeShip(int x, int y) {
            super(new ObjectSpawn(x, y, Sonic3kObjectIds.ICZ_END_BOSS, 0, 0, false, y),
                    "ICZEndBossRobotnikEscapeShip");
            this.x = x;
            this.xFixed = x << 8;
            this.y = y;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            if (!flyingRight) {
                int targetY = services().camera() != null
                        ? (services().camera().getY() & 0xFFFF) + 0x40
                        : y;
                if (targetY < y) {
                    y--;
                    return;
                }
                flyingRight = true;
            }

            xFixed += ESCAPE_X_VELOCITY;
            x = xFixed >> 8;
            if (timer-- < 0) {
                setDestroyed(true);
            }
        }

        @Override
        public int getX() {
            return x;
        }

        @Override
        public int getY() {
            return y;
        }

        @Override
        public boolean isPersistent() {
            return true;
        }

        @Override
        public int getPriorityBucket() {
            return RenderPriority.clamp(5);
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.ROBOTNIK_SHIP);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(ESCAPE_FRAME, x, y, true, false, ROBOTNIK_SHIP_PALETTE_LINE);
            renderer.drawFrameIndex(HEAD_FRAME_ANGRY, x, y + HEAD_Y_OFFSET, true, false,
                    ROBOTNIK_SHIP_PALETTE_LINE);
            if (flyingRight) {
                renderer.drawFrameIndex(FLAME_FRAME, x - FLAME_DX, y, true, false, ROBOTNIK_SHIP_PALETTE_LINE);
            }
        }
    }

    private static final class StructuralChild {
        private static final int DETACHED_GRAVITY = 0x38;

        private final int baseDx;
        private final int baseDy;
        private int frame;
        private final int shiftDuration;
        private int x;
        private int y;
        private boolean flipX;
        private boolean detached;
        private boolean detachedMovePending;
        private int xFixed;
        private int yFixed;
        private int xVel;
        private int yVel;
        private int localYOffset;
        private int shiftTimer;
        private int shiftVelocity;

        private StructuralChild(int baseDx, int baseDy, int frame, int shiftDuration) {
            this.baseDx = baseDx;
            this.baseDy = baseDy;
            this.frame = frame;
            this.shiftDuration = shiftDuration;
        }

        private void detach(int nativeXVelocity, int nativeYVelocity) {
            if (detached) {
                return;
            }
            detached = true;
            // loc_849D8 only changes the routine pointer during the damaged
            // phase's current child pass. Obj_FlickerMove first integrates on
            // the following pass.
            detachedMovePending = true;
            xFixed = x << 16;
            yFixed = y << 16;
            xVel = nativeXVelocity;
            yVel = nativeYVelocity;
        }

        private void updateDetached() {
            if (detachedMovePending) {
                detachedMovePending = false;
                return;
            }
            xFixed += xVel << 8;
            yFixed += yVel << 8;
            x = xFixed >> 16;
            y = yFixed >> 16;
            yVel += DETACHED_GRAVITY;
        }

        private boolean isOutsideNativeFlickerBounds(int cameraX, int cameraY) {
            return S3kBossFlickerMove.isOutsideNativeBounds(x, y, cameraX, cameraY);
        }

        private void startShift(int velocity) {
            if (shiftDuration <= 0 || detached) {
                return;
            }
            shiftVelocity = velocity;
            shiftTimer = shiftDuration;
        }

        private void updateShift() {
            if (shiftVelocity == 0 || shiftTimer < 0) {
                return;
            }
            localYOffset += shiftVelocity;
            shiftTimer--;
        }
    }

    private static final class EffectChild {
        private final int baseDx;
        private final int baseDy;
        private final int[][] script;
        private final EffectAnchor anchor;
        private final int captureMinX;
        private final int captureMaxX;
        private final int captureMinY;
        private final int captureMaxY;
        private final boolean adjustedPosition;
        private final boolean romRawMultiDelay;
        private int x;
        private int y;
        private int frame;
        private int scriptIndex;
        private int rawAnimationOffset;
        private int frameTimer;
        private boolean waitingToStart;
        private boolean waitJustCreated;
        private boolean visible;
        private boolean flipX;
        private boolean flipY;
        private boolean finished;
        private boolean captureWasActiveBeforeUpdate;
        private int nativeSlot;

        private EffectChild(int baseDx, int baseDy, int[][] script, EffectAnchor anchor) {
            this.baseDx = baseDx;
            this.baseDy = baseDy;
            this.script = script;
            this.anchor = anchor;
            this.frame = script.length == 0 ? 0 : script[0][0];
            this.scriptIndex = 0;
            this.rawAnimationOffset = 0;
            this.frameTimer = script.length == 0 ? 0 : script[0][1];
            boolean topCapture = script == FROST_SCRIPT_TOP;
            this.captureMinX = topCapture ? FROST_CAPTURE_TOP_MIN_X : FROST_CAPTURE_NORMAL_MIN_X;
            this.captureMaxX = topCapture ? FROST_CAPTURE_TOP_MAX_X : FROST_CAPTURE_NORMAL_MAX_X;
            this.captureMinY = topCapture ? FROST_CAPTURE_TOP_MIN_Y : FROST_CAPTURE_NORMAL_MIN_Y;
            this.captureMaxY = topCapture ? FROST_CAPTURE_TOP_MAX_Y : FROST_CAPTURE_NORMAL_MAX_Y;
            this.adjustedPosition = topCapture;
            this.romRawMultiDelay = false;
            this.waitingToStart = false;
            this.waitJustCreated = false;
            this.visible = true;
            this.nativeSlot = -1;
        }

        private EffectChild(int baseDx, int baseDy, int[][] script, EffectAnchor anchor,
                int initialFrameTimer, int nativeSlot) {
            this.baseDx = baseDx;
            this.baseDy = baseDy;
            this.script = script;
            this.anchor = anchor;
            this.frame = 0x05;
            this.scriptIndex = -1;
            this.rawAnimationOffset = 0;
            this.frameTimer = initialFrameTimer;
            boolean topCapture = script == FROST_SCRIPT_TOP;
            this.captureMinX = topCapture ? FROST_CAPTURE_TOP_MIN_X : FROST_CAPTURE_NORMAL_MIN_X;
            this.captureMaxX = topCapture ? FROST_CAPTURE_TOP_MAX_X : FROST_CAPTURE_NORMAL_MAX_X;
            this.captureMinY = topCapture ? FROST_CAPTURE_TOP_MIN_Y : FROST_CAPTURE_NORMAL_MIN_Y;
            this.captureMaxY = topCapture ? FROST_CAPTURE_TOP_MAX_Y : FROST_CAPTURE_NORMAL_MAX_Y;
            this.adjustedPosition = topCapture;
            this.romRawMultiDelay = true;
            this.waitingToStart = true;
            this.waitJustCreated = true;
            this.visible = false;
            this.nativeSlot = nativeSlot;
        }

        private void update(AnchorPoint anchor) {
            captureWasActiveBeforeUpdate = isCaptureActive();
            int dx = adjustedPosition && anchor.flipX() ? -baseDx : baseDx;
            int dy = adjustedPosition && anchor.flipY() ? -baseDy : baseDy;
            x = anchor.x() + dx;
            y = anchor.y() + dy;
            flipX = adjustedPosition && anchor.flipX();
            flipY = adjustedPosition && anchor.flipY();
            if (finished || script.length == 0) {
                return;
            }
            if (waitingToStart) {
                if (waitJustCreated) {
                    waitJustCreated = false;
                    return;
                }
                if (--frameTimer >= 0) {
                    return;
                }
                waitingToStart = false;
                frameTimer = -1;
                return;
            }
            if (romRawMultiDelay) {
                if (--frameTimer >= 0) {
                    return;
                }
                advanceRawMultiDelay();
                return;
            }
            if (frameTimer-- >= 0) {
                return;
            }
            advanceRawMultiDelay();
        }

        private void advanceRawMultiDelay() {
            if (scriptIndex < 0) {
                rawAnimationOffset = 2;
            } else {
                rawAnimationOffset += 2;
            }
            scriptIndex = rawAnimationOffset / 2;
            if (scriptIndex >= script.length) {
                finished = true;
                return;
            }
            frame = script[scriptIndex][0];
            frameTimer = script[scriptIndex][1];
            visible = true;
        }

        private boolean isFinished() {
            return finished;
        }

        private boolean isVisible() {
            return visible;
        }

        private boolean isCaptureActive() {
            return visible && !finished && rawAnimationOffset >= 4 && rawAnimationOffset <= 8;
        }
    }
}
