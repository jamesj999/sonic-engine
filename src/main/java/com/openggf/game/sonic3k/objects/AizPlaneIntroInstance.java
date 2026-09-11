package com.openggf.game.sonic3k.objects;

import com.openggf.game.sonic3k.resources.S3kRuntimeArtCoordinator;

import com.openggf.data.RomByteReader;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kPlayerArt;
import com.openggf.game.sonic3k.Sonic3kSuperStateController;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.physics.SwingMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.render.PlayerSpriteRenderer;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Object for the AIZ1 intro cinematic - master state machine.
 *
 * Disassembly reference: s3.asm Obj_intPlane (loc_45888).
 *
 * The ROM uses stride-2 routine dispatch (0x00, 0x02, 0x04, ..., 0x1A).
 * We mirror that convention directly so routine values match the disassembly.
 *
 * Routine overview (ROM-accurate):
 *   0x00 - Init: set position (0x60,0x30), lock player, timer=0x40, load art
 *   0x02 - Wait: Obj_Wait 65 frames, then spawn plane child + set velocity
 *   0x04 - Descent: y_vel -= 0x18/frame, MoveSprite2. When y_vel==0 -> Swing_Setup1
 *   0x06 - Swing+Wait: Swing_UpAndDown + MoveSprite2 + Obj_Wait(0x5F)
 *   0x08 - Lift off: x_vel=0x400 y_vel=-0x400, x_vel-=0x40, MoveSprite(+gravity). Land at y>=0x130
 *   0x0A - Ground decel: x_vel -= 0x40, MoveSprite2. When x<0x40 -> Super setup
 *   0x0C - Super flash: secondaryTimer countdown (0x3F=63 frames)
 *   0x0E - Walk right: x+=4/frame, wave spawns. Until x>=0x200
 *   0x10 - Wait: secondaryTimer countdown (0x1F=31 frames)
 *   0x12 - Walk left: x-=4/frame. Until x<=0x120
 *   0x14 - Wait: secondaryTimer countdown (0x1F=31 frames)
 *   0x16 - Monitor Knux: wait until player.x>=0x918, spawn Knuckles
 *   0x18 - Monitor approach: wait until player.x>=0x1240, y_pos-=0x20
 *   0x1A - Explosion: wait until player.x>=0x13D0, release player, scatter emeralds, delete
 */
public class AizPlaneIntroInstance extends AbstractObjectInstance implements RewindRecreatable {

    private static final Logger LOG = Logger.getLogger(AizPlaneIntroInstance.class.getName());

    // -----------------------------------------------------------------------
    // Constants from ROM (s3.asm Obj_intPlane)
    // -----------------------------------------------------------------------

    /** Frames between wave child spawns. */
    static final int WAVE_SPAWN_INTERVAL = 5;

    /** X coordinate at which Knuckles is spawned (routine 0x16 trigger). */
    static final int KNUCKLES_SPAWN_X = 0x918;

    /** X coordinate for plane vertical adjustment (routine 0x18). */
    static final int PLANE_ADJUST_X = 0x1240;

    /** X coordinate at which explosion is triggered (routine 0x1A). */
    static final int EXPLOSION_TRIGGER_X = 0x13D0;

    /** Target X for walk right (routine 0x0E). */
    static final int WALK_RIGHT_TARGET = 0x200;

    /** Target X for walk left (routine 0x12). */
    static final int WALK_LEFT_TARGET = 0x120;

    /** Pixels per frame for walk during cutscene. */
    static final int WALK_SPEED = 4;

    /** Y velocity deceleration per frame during descent (subpixels). */
    static final int DESCENT_DECEL = 0x18;

    /** X velocity deceleration per frame during ground decel and lift-off (subpixels). */
    static final int HORIZ_DECEL = 0x40;

    /** Y coordinate of the ground level. */
    static final int GROUND_Y = 0x130;

    /** Standard S3K gravity in subpixels per frame. */
    static final int GRAVITY = SubpixelMotion.S3K_GRAVITY;

    /** Initial wait timer value for routine 0x00 ($2E timer = 0x40). */
    static final int INIT_WAIT_TIMER = 0x40;

    /** Screen scroll speed value ($40 from ROM). */
    static final int SCROLL_SPEED = 8;

    /** Swing acceleration per frame (ROM: Swing_Setup1). */
    static final int SWING_ACCEL = 0x10;

    /** Swing max velocity (ROM: Swing_Setup1). */
    static final int SWING_MAX_VEL = 0xC0;

    /** Initial X velocity for descent (ROM: loc_458F0). */
    static final int DESCENT_X_VEL = 0x300;

    /** Initial Y velocity for descent (ROM: loc_458F0). */
    static final int DESCENT_Y_VEL = 0x600;

    /** Swing+wait timer (ROM: 0x5F = 95 frames). */
    static final int SWING_WAIT_TIMER = 0x5F;

    /** Lift-off X velocity (ROM: loc_45970). */
    static final int LIFTOFF_X_VEL = 0x400;

    /** Lift-off Y velocity (ROM: loc_45970, negative = upward). */
    static final int LIFTOFF_Y_VEL = -0x400;

    /** Super flash timer (ROM: $3A = 0x3F = 63 frames). */
    static final int SUPER_FLASH_TIMER = 0x3F;

    /** Short wait timer (ROM: 0x1F = 31 frames). */
    static final int SHORT_WAIT_TIMER = 0x1F;

    /** Minimum X for wave spawning. */
    static final int WAVE_SPAWN_MIN_X = 0x80;
    /** Intro mapping frame used before the Super Sonic phase. */
    static final int INTRO_MAPPING_FRAME = 0xBA;
    /** Super Sonic intro mapping frame base (sub_679B8 alternates 0x21/0x22). */
    static final int SUPER_MAPPING_FRAME_BASE = 0x21;

    /**
     * ROM byte_45E60: animation sequence for lift-off (routine 8).
     * Timer reset = 3, frame count = 8, then 8 Map_Sonic frame indices.
     */
    private static final int[] LIFTOFF_ANIM_FRAMES = {0x97, 0x96, 0x98, 0x96, 0x99, 0x96, 0x9A, 0x96};
    private static final int LIFTOFF_ANIM_TIMER_RESET = 3;

    // -----------------------------------------------------------------------
    // Mutable state
    // -----------------------------------------------------------------------

    private int currentX;
    private int currentY;

    /** Fractional X accumulator (subpixels, 0-255). */
    private int xSub;

    /** Fractional Y accumulator (subpixels, 0-255). */
    private int ySub;

    /** Routine counter (stride-2: 0, 2, 4, ..., 26). */
    private int routine;

    /** X velocity in subpixels (256 = 1 pixel). */
    private int xVel;

    /** Y velocity in subpixels (256 = 1 pixel). */
    private int yVel;

    /** General-purpose countdown timer ($2E in ROM). */
    private int waitTimer;

    /** Wave spawn countdown (resets to WAVE_SPAWN_INTERVAL). */
    private int waveTimer;

    /** Secondary timer ($3A in ROM) for flash/wait sequences. */
    private int secondaryTimer;

    /** Swing velocity for parent bob motion (routine 6). */
    private int swingVelocity;

    /** Swing direction for parent (true=down/positive). */
    private boolean swingDirectionDown;

    /** When true, plane child stops following parent position. */
    private boolean planeDetached;

    /** When true, plane child walks left off-screen. */
    private boolean planeWalkLeft;

    /** Whether Super Sonic visual mode is active. */
    private boolean superSonicActive;

    /** Lift-off animation frame countdown timer (ROM $30 callback). */
    private int liftoffAnimTimer;

    /** Current index into LIFTOFF_ANIM_FRAMES. */
    private int liftoffAnimIndex;

    /** Palette cycler for Super Sonic visual effect (routines 0x0C+). */
    private AizIntroPaletteCycler paletteCycler;

    /** Whether this object currently owns player control lock. */
    private boolean ownsPlayerControl;

    /** Reference to the plane child sprite (biplane visual). */
    private AizIntroPlaneChild planeChild;

    /** Reference to the Knuckles cutscene object. */
    private CutsceneKnucklesAiz1Instance knuckles;

    /** List of active wave children for cleanup. */
    private final ArrayList<AizIntroWaveChild> activeWaves = new ArrayList<>();

    /** List of scattered emeralds. */
    private final ArrayList<AizEmeraldScatterInstance> emeralds = new ArrayList<>();

    private int mappingFrame;
    private int lastFrameCounter;
    private PlayerSpriteRenderer sonicRenderer;
    private PlayerSpriteRenderer superSonicRenderer;
    private boolean renderersLoaded;
    private S3kKosModuleQueue introSpriteArtQueue;
    private HardwareWorkHandle planeArtHandle;
    private HardwareWorkHandle emeraldArtHandle;
    private long planeArtOrdinal = -1;
    private long emeraldArtOrdinal = -1;

    /** ROM $40 field — scroll speed. Changes at routine transitions. */
    private int scrollSpeed = SCROLL_SPEED;

    /** ROM Events_fg_1 accumulator — starts at 0xE918 (-5864), gates FG scroll. */
    private int eventsFg1 = (short) 0xE918;

    /** Current intro scroll offset for SwScrlAiz BG parallax. */
    private static int introScrollOffset = 0;
    /** Set once intro transitions to the post-$1400 main-level phase. */
    private static boolean mainLevelPhaseActive = false;
    private static AizPlaneIntroInstance activeIntroInstance;

    /** Returns the current Events_fg_1 accumulator value for BG parallax. */
    public static int getIntroScrollOffset() { return introScrollOffset; }
    public static boolean isMainLevelPhaseActive() { return mainLevelPhaseActive; }
    public static void setMainLevelPhaseActive(boolean active) { mainLevelPhaseActive = active; }
    public static AizPlaneIntroInstance getActiveIntroInstance() { return activeIntroInstance; }
    public static void adoptActiveIntroInstance(AizPlaneIntroInstance instance) { activeIntroInstance = instance; }
    public static void resetIntroPhaseState() {
        introScrollOffset = 0;
        mainLevelPhaseActive = false;
        activeIntroInstance = null;
    }

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    public AizPlaneIntroInstance(ObjectSpawn spawn) {
        super(spawn, "AIZPlaneIntro");
        activeIntroInstance = this;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.routine = 0;
        this.xVel = 0;
        this.yVel = 0;
        this.xSub = 0;
        this.ySub = 0;
        this.waitTimer = 0;
        this.waveTimer = 0;
        this.secondaryTimer = 0;
        this.swingVelocity = 0;
        this.swingDirectionDown = false;
        this.planeDetached = false;
        this.planeWalkLeft = false;
        this.superSonicActive = false;
        this.ownsPlayerControl = false;
        this.mappingFrame = INTRO_MAPPING_FRAME;
        this.lastFrameCounter = 0;
        this.renderersLoaded = false;
    }

    @Override
    public AizPlaneIntroInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizPlaneIntroInstance(ctx.spawn());
    }

    // -----------------------------------------------------------------------
    // ObjectInstance interface
    // -----------------------------------------------------------------------

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        rebindIntroSpriteArtAfterRestore();
        claimIntroSpriteArtIfReady();
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        lastFrameCounter = vIntRunCount;
        AbstractPlayableSprite trackedPlayer = resolveTrackedPlayer(player);

        // ROM: routine dispatch FIRST (s3.asm line 81188-81195)
        switch (routine) {
            case 0  -> routine0Init(trackedPlayer);
            case 2  -> routine2Wait(trackedPlayer);
            case 4  -> routine4Descent(trackedPlayer);
            case 6  -> routine6SwingWait(trackedPlayer);
            case 8  -> routine8LiftOff(trackedPlayer);
            case 10 -> routine10GroundDecel(trackedPlayer);
            case 12 -> routine12SuperFlash(trackedPlayer);
            case 14 -> routine14WalkRight(trackedPlayer);
            case 16 -> routine16Wait(trackedPlayer);
            case 18 -> routine18WalkLeft(trackedPlayer);
            case 20 -> routine20Wait(trackedPlayer);
            case 22 -> routine22MonitorKnux(trackedPlayer);
            case 24 -> routine24MonitorApproach(trackedPlayer);
            case 26 -> routine26Explode(trackedPlayer);
            default -> {
                // Invalid routine - no-op
            }
        }

        // ROM: sub_45DE4 (scrollVelocity) runs AFTER routine dispatch.
        // S3K Go_Delete_Sprite returns via rts — scrollVelocity still executes
        // on the explosion frame with scrollSpeed intact, adding 16px to player X.
        scrollVelocity(trackedPlayer);

        // ROM: main-level phase transition is managed by the FG/BG event
        // handlers (Sonic3kAIZEvents), not by Obj_intPlane. Do NOT call
        // maybeActivateMainLevelPhase() here — it would double-decrement
        // the decompression countdown since events already call
        // updateMainLevelPhaseForCameraX() once per frame.
    }

    /**
     * Resolve the intro-controlled player.
     *
     * Prefer the camera-focused sprite because that's what camera follow uses.
     * This prevents intro progression from stalling when the caller passes null
     * (or a non-focused sprite) during object updates.
     */
    private AbstractPlayableSprite resolveTrackedPlayer(AbstractPlayableSprite candidate) {
        try {
            AbstractPlayableSprite focused = services().camera().getFocusedSprite();
            if (focused != null) {
                return focused;
            }
        } catch (Exception e) {
            LOG.fine(() -> "AizPlaneIntroInstance.resolveTrackedPlayer: " + e.getMessage());
        }
        return candidate;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ensureIntroSonicRenderersLoaded();
        PlayerSpriteRenderer renderer = superSonicActive ? superSonicRenderer : sonicRenderer;
        if (renderer == null) {
            return;
        }
        // ROM render_flags bit 2 clear uses screen-space coordinates in the
        // sprite table domain where (128,128) maps to top-left screen origin.
        // Convert that to our world-space render input.
        int renderX = currentX;
        int renderY = currentY;
        try {
            var camera = services().camera();
            renderX += camera.getX() - 128;
            renderY += camera.getY() - 128;
        } catch (Exception e) {
            LOG.fine(() -> "AizPlaneIntroInstance.appendRenderCommands: " + e.getMessage());
        }
        renderer.drawFrame(mappingFrame, renderX, renderY, false, false);
    }

    @Override
    public int getPriorityBucket() {
        // ROM: loc_674AC writes priority(a0) = $280.
        return 5;
    }

    @Override
    public void onUnload() {
        if (activeIntroInstance == this) {
            activeIntroInstance = null;
        }
        // Safety net: release player control if we still own it.
        if (ownsPlayerControl) {
            try {
                var focusedSprite = services().camera().getFocusedSprite();
                if (focusedSprite instanceof AbstractPlayableSprite ps) {
                    ps.setControlLocked(false);
                    ObjectControlState.none().applyTo(ps);
                    ps.setHidden(false);
                }
            } catch (Exception e) {
                LOG.fine(() -> "AizPlaneIntroInstance.onUnload: " + e.getMessage());
            }
            ownsPlayerControl = false;
        }
        activeWaves.clear();
        emeralds.clear();
        // Art reset moved to CutsceneKnucklesAiz1Instance.onUnload() —
        // Knuckles (and emeralds, rock child) still need the shared art data
        // after the plane parent destroys itself mid-cutscene.
    }

    // -----------------------------------------------------------------------
    // Routine accessors (for test and external use)
    // -----------------------------------------------------------------------

    public int getRoutine() {
        return routine;
    }

    public short getReplayXSpeed() {
        return (short) xVel;
    }

    public short getReplayYSpeed() {
        return (short) yVel;
    }

    public int getReplayXSubpixelRaw() {
        return (xSub & 0xFF) << 8;
    }

    public int getReplayYSubpixelRaw() {
        return (ySub & 0xFF) << 8;
    }

    int getScrollSpeed() {
        return scrollSpeed;
    }

    /** Returns whether intro Super Sonic visual mode is currently active. */
    public boolean isSuperSonicVisualActive() {
        return superSonicActive;
    }

    /** Returns the current intro mapping frame used for rendering. */
    public int getMappingFrame() {
        return mappingFrame;
    }

    public void advanceRoutine() {
        routine += 2;
    }

    /** Returns true when plane child should stop following parent. */
    public boolean isPlaneDetached() {
        return planeDetached;
    }

    /** Returns true when plane child should walk left off-screen. */
    public boolean isPlaneShouldWalkLeft() {
        return planeWalkLeft;
    }

    private void ensureIntroSonicRenderersLoaded() {
        if (renderersLoaded) {
            return;
        }
        try {
            var rom = services().rom();
            Sonic3kPlayerArt art = new Sonic3kPlayerArt(RomByteReader.fromRom(rom));
            sonicRenderer = new PlayerSpriteRenderer(art.loadSonic());
            superSonicRenderer = new PlayerSpriteRenderer(art.loadSuperSonicArtSet());
            renderersLoaded = true;
        } catch (Exception e) {
            LOG.fine("Could not load AIZ intro Sonic renderers: " + e.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // MoveSprite helpers (subpixel position updates)
    // -----------------------------------------------------------------------

    /** Shared state object for SubpixelMotion calls — avoids per-frame allocation. */
    private final SubpixelMotion.State motionState = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    /** Syncs local fields into the shared motion state before a SubpixelMotion call. */
    private void syncToMotionState() {
        motionState.x = currentX; motionState.y = currentY;
        motionState.xSub = xSub;  motionState.ySub = ySub;
        motionState.xVel = xVel;  motionState.yVel = yVel;
    }

    /** Syncs shared motion state back into local fields after a SubpixelMotion call. */
    private void syncFromMotionState() {
        currentX = motionState.x; currentY = motionState.y;
        xSub = motionState.xSub;  ySub = motionState.ySub;
        xVel = motionState.xVel;  yVel = motionState.yVel;
    }

    /** MoveSprite2: apply x_vel and y_vel to position with subpixel accumulation. No gravity. */
    private void moveSprite2() {
        syncToMotionState();
        SubpixelMotion.moveSprite2(motionState);
        syncFromMotionState();
    }

    /** MoveSprite: add old velocity to position, then apply gravity to y_vel. */
    private void moveSprite() {
        syncToMotionState();
        SubpixelMotion.moveSprite(motionState, GRAVITY);
        syncFromMotionState();
    }

    /**
     * ROM sub_67A08: scroll velocity helper.
     * Accumulates scrollSpeed into eventsFg1 (starts at -5864). While negative,
     * the BG scrolls via introScrollOffset but the player stays still.
     * Once eventsFg1 reaches >= 0 (~733 frames at 8px/frame), the player
     * starts scrolling rightward and the camera naturally follows.
     *
     * ROM only touches Events_fg_1 and Player_1+x_pos — no camera writes.
     */
    private void scrollVelocity(AbstractPlayableSprite player) {
        if (eventsFg1 < 0) {
            eventsFg1 += scrollSpeed;
            introScrollOffset = eventsFg1;
            return;
        }
        // Gate reached: stop BG intro parallax, start moving player.
        introScrollOffset = 0;
        if (player != null) {
            player.setCentreX((short) (player.getCentreX() + scrollSpeed));
        }
    }

    private AizIntroPaletteCycler paletteCycler() {
        if (paletteCycler == null) {
            paletteCycler = new AizIntroPaletteCycler(services());
        }
        return paletteCycler;
    }

    // -----------------------------------------------------------------------
    // Wave spawn helper
    // -----------------------------------------------------------------------

    private void tickWaveSpawn() {
        waveTimer--;
        if (waveTimer < 0) {
            waveTimer = WAVE_SPAWN_INTERVAL;
            if (currentX >= WAVE_SPAWN_MIN_X) {
                ObjectSpawn waveSpawn = new ObjectSpawn(
                        currentX, currentY + 0x18, 0, 0, 0, false, 0);
                AizIntroWaveChild wave = new AizIntroWaveChild(waveSpawn, this);
                spawnDynamicObject(wave);
                activeWaves.add(wave);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Super Sonic palette animation helper (sub_45D94 equivalent)
    // -----------------------------------------------------------------------

    private void superSonicPaletteAnim() {
        AizIntroPaletteCycler cycler = paletteCycler();
        cycler.advance();
        cycler.applyToGpu();
        mappingFrame = cycler.getMappingFrame(lastFrameCounter);
    }

    // -----------------------------------------------------------------------
    // Routine 0x00: Init
    // -----------------------------------------------------------------------

    private void routine0Init(AbstractPlayableSprite player) {
        LOG.fine("Routine 0: initializing intro sequence");
        resetIntroPhaseState();
        activeIntroInstance = this;

        // ROM: set position (0x60, 0x30)
        currentX = 0x60;
        currentY = 0x30;

        // ROM: timer = 0x40 (wait happens in routine 2)
        waitTimer = INIT_WAIT_TIMER;
        waveTimer = WAVE_SPAWN_INTERVAL;
        paletteCycler().init();
        superSonicActive = false;
        mappingFrame = INTRO_MAPPING_FRAME;
        ensureIntroSonicRenderersLoaded();

        // Lock player control for the duration of the intro.
        // The strict intro replay window shows the hidden player body staying
        // frozen at the live spawn position until the cutscene releases it, so
        // AIZ needs full object-controlled suppression here.
        if (player != null) {
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setSubpixelRaw(0, 0);
            // Obj_AIZPlaneIntro init writes mapping_frame=0 and
            // object_control=$53. Bit 1 keeps Animate_Sonic from replacing
            // that frame while the hidden player slot is owned by the intro
            // (sonic3k.asm:135492-135495,22067-22076).
            player.setMappingFrame(0);
            player.setObjectMappingFrameControl(true);
            player.setAir(false);
            player.setControlLocked(true);
            ObjectControlState.engineScriptedPreserveCpuMovementSuppressed().applyTo(player);
            player.setHidden(true);
            ownsPlayerControl = true;
        }

        // Load all intro art.
        try {
            AizIntroArtLoader.loadAllIntroArt(services());
        } catch (Exception e) {
            LOG.fine("Could not load intro art (test env?): " + e.getMessage());
        }

        // ROM: SpawnLevelMainSprites clears Level_started_flag for AIZ1 intro.
        // This gates intro/HUD/start-state flow; camera scrolling still runs.
        // CutsceneKnucklesAiz1Instance sets the flag back to $91 on exit.
        try {
            services().camera().setLevelStarted(false);
        } catch (Exception e) {
            LOG.fine(() -> "AizPlaneIntroInstance.routine0Init: " + e.getMessage());
        }

        // Do NOT spawn plane child here — that happens after the wait in routine 2.
        // Do NOT set velocity here — that happens after the wait in routine 2.

        advanceRoutine();
    }

    // -----------------------------------------------------------------------
    // Routine 0x02: Wait (Obj_Wait 65 frames, then spawn child + set velocity)
    // -----------------------------------------------------------------------

    private void routine2Wait(AbstractPlayableSprite player) {
        waitTimer--;
        if (waitTimer < 0) {
            queueIntroSpriteArt();
            // Wait complete — spawn plane child and set descent velocity
            LOG.fine("Routine 2: wait complete, spawning plane child");

            // Set descent velocity
            xVel = DESCENT_X_VEL;
            yVel = DESCENT_Y_VEL;

            // Spawn the plane child (biplane visual)
            ObjectSpawn planeSpawn = new ObjectSpawn(
                    currentX - 0x22, currentY + 0x2C, 0, 0, 0, false, 0);
            planeChild = new AizIntroPlaneChild(planeSpawn, this);
            spawnDynamicObject(planeChild);

            // CreateChild1_Normal also allocates the two animated plane pieces
            // in their own following SST slots (sonic3k.asm:135702-135718,
            // 135741-135819). They must remain real dynamic objects so later
            // AllocateObject calls observe the same slot pressure.
            ObjectSpawn glow1Spawn = new ObjectSpawn(
                    planeChild.getX() + 0x38, planeChild.getY() + 0x04,
                    0, 0, 0, false, 0);
            AizIntroEmeraldGlowChild glow1 =
                    new AizIntroEmeraldGlowChild(glow1Spawn, planeChild, 0);
            spawnDynamicObject(glow1);
            ObjectSpawn glow2Spawn = new ObjectSpawn(
                    planeChild.getX() + 0x18, planeChild.getY() + 0x18,
                    0, 1, 0, false, 0);
            AizIntroEmeraldGlowChild glow2 =
                    new AizIntroEmeraldGlowChild(glow2Spawn, planeChild, 1);
            spawnDynamicObject(glow2);
            planeChild.setGlowChildren(glow1, glow2);

            advanceRoutine();
        }
    }

    private void queueIntroSpriteArt() {
        if (planeArtHandle != null || emeraldArtHandle != null
                || planeArtOrdinal >= 0 || emeraldArtOrdinal >= 0) {
            return;
        }
        try {
            introSpriteArtQueue =
                    S3kRuntimeArtCoordinator.from(services()).moduleQueue();
            planeArtHandle = introSpriteArtQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_AIZ_INTRO_PLANE_ADDR,
                    Sonic3kConstants.ARTTILE_AIZ_INTRO_PLANE);
            planeArtOrdinal = planeArtHandle.ordinal();
            emeraldArtHandle = introSpriteArtQueue.queue(
                    services().rom(),
                    Sonic3kConstants.ART_KOSM_AIZ_INTRO_EMERALDS_ADDR,
                    Sonic3kConstants.ARTTILE_AIZ_INTRO_EMERALDS);
            emeraldArtOrdinal = emeraldArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue AIZ intro sprite KosM art", e);
        }
    }

    private void claimIntroSpriteArtIfReady() {
        if (introSpriteArtQueue == null) {
            return;
        }
        if (planeArtHandle != null
                && introSpriteArtQueue.isReady(planeArtHandle)) {
            introSpriteArtQueue.claim(planeArtHandle);
            planeArtHandle = null;
            planeArtOrdinal = -1;
        }
        if (emeraldArtHandle != null
                && introSpriteArtQueue.isReady(emeraldArtHandle)) {
            introSpriteArtQueue.claim(emeraldArtHandle);
            emeraldArtHandle = null;
            emeraldArtOrdinal = -1;
        }
        if (planeArtHandle == null && emeraldArtHandle == null) {
            introSpriteArtQueue = null;
        }
    }

    private void rebindIntroSpriteArtAfterRestore() {
        if ((planeArtOrdinal < 0 && emeraldArtOrdinal < 0)
                || introSpriteArtQueue != null) {
            return;
        }
        introSpriteArtQueue = S3kRuntimeArtCoordinator.from(services()).moduleQueue();
        if (planeArtOrdinal >= 0) {
            planeArtHandle = restoredIntroArtHandle(
                    planeArtOrdinal, "plane");
        }
        if (emeraldArtOrdinal >= 0) {
            emeraldArtHandle = restoredIntroArtHandle(
                    emeraldArtOrdinal, "emerald");
        }
    }

    private HardwareWorkHandle restoredIntroArtHandle(long ordinal, String name) {
        return services().hardwareTiming().pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE, ordinal)
                .orElseThrow(() -> new IllegalStateException(
                        "Missing restored AIZ intro " + name
                                + " KosM job " + ordinal));
    }

    // -----------------------------------------------------------------------
    // Routine 0x04: Descent (y_vel -= 0x18/frame, MoveSprite2)
    // -----------------------------------------------------------------------

    private void routine4Descent(AbstractPlayableSprite player) {
        // ROM: y_vel -= 0x18 each frame
        yVel -= DESCENT_DECEL;

        // ROM: beq.s Swing_Setup1 — branch BEFORE MoveSprite2 when yVel == 0
        // DESCENT_Y_VEL (0x600) is a multiple of DESCENT_DECEL (0x18): 1536/24 = 64 exactly,
        // so yVel always hits 0 precisely, matching ROM's beq (branch if equal).
        if (yVel == 0) {
            // Swing_Setup1: skip MoveSprite2 on this frame
            swingVelocity = SWING_MAX_VEL;
            swingDirectionDown = false;
            scrollSpeed = SWING_ACCEL;
            xVel = 0; // Stop horizontal movement during swing

            // Set swing+wait timer
            waitTimer = SWING_WAIT_TIMER;
            LOG.fine("Routine 4: descent complete at y=" + currentY + ", advancing to swing");
            advanceRoutine();
            return;
        }

        // ROM: jmp (MoveSprite2).l — only when yVel != 0
        moveSprite2();
    }

    // -----------------------------------------------------------------------
    // Routine 0x06: Swing + Wait (Swing_UpAndDown + MoveSprite2 + Obj_Wait)
    // -----------------------------------------------------------------------

    private void routine6SwingWait(AbstractPlayableSprite player) {
        // Swing_UpAndDown on parent
        SwingMotion.Result result = SwingMotion.update(
                SWING_ACCEL, swingVelocity, SWING_MAX_VEL, swingDirectionDown);
        swingVelocity = result.velocity();
        swingDirectionDown = result.directionDown();

        // Apply swing velocity as y_vel for MoveSprite2
        yVel = swingVelocity;
        moveSprite2();

        // Obj_Wait: decrement timer
        waitTimer--;
        if (waitTimer < 0) {
            // Set lift-off velocity
            xVel = LIFTOFF_X_VEL;
            yVel = LIFTOFF_Y_VEL;

            // Detach plane child from parent tracking
            planeDetached = true;

            // ROM: bset #3,$38(a0) + move.l #byte_45E60,$30(a0)
            // Initialize lift-off animation (loc_45DFC callback)
            liftoffAnimTimer = 0; // trigger immediately on first frame
            liftoffAnimIndex = 0;
            mappingFrame = LIFTOFF_ANIM_FRAMES[0];

            LOG.fine("Routine 6: swing complete, advancing to lift-off");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x08: Lift off (x_vel-=0x40, MoveSprite with gravity)
    // -----------------------------------------------------------------------

    private void routine8LiftOff(AbstractPlayableSprite player) {
        // ROM loc_45DFC: animation callback runs BEFORE movement
        updateLiftOffAnimation();

        // ROM: x_vel -= 0x40 each frame (decelerate, then go negative = leftward)
        xVel -= HORIZ_DECEL;

        // MoveSprite (with gravity)
        moveSprite();

        // ROM: check y >= 0x130 (landed)
        if (currentY >= GROUND_Y) {
            currentY = GROUND_Y;
            yVel = 0;
            LOG.fine("Routine 8: landed at ground, advancing to ground decel");
            advanceRoutine();
        }
    }

    /**
     * ROM loc_45DFC: lift-off animation callback.
     * Cycles through LIFTOFF_ANIM_FRAMES at LIFTOFF_ANIM_TIMER_RESET interval.
     */
    private void updateLiftOffAnimation() {
        liftoffAnimTimer--;
        if (liftoffAnimTimer < 0) {
            liftoffAnimTimer = LIFTOFF_ANIM_TIMER_RESET;
            liftoffAnimIndex++;
            if (liftoffAnimIndex >= LIFTOFF_ANIM_FRAMES.length) {
                liftoffAnimIndex = 0;
            }
            mappingFrame = LIFTOFF_ANIM_FRAMES[liftoffAnimIndex];
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x0A: Ground decel (x_vel -= 0x40, MoveSprite2)
    // -----------------------------------------------------------------------

    private void routine10GroundDecel(AbstractPlayableSprite player) {
        // ROM: x_vel -= 0x40
        xVel -= HORIZ_DECEL;

        // MoveSprite2 (no gravity — on ground)
        yVel = 0;
        moveSprite2();

        // ROM: check x < 0x40
        if (currentX < 0x40) {
            currentX = 0x40;

            // Set up Super Sonic
            setupSuperSonic();

            // Set flash timer
            secondaryTimer = SUPER_FLASH_TIMER;

            LOG.fine("Routine 10: ground decel complete, advancing to super flash");
            advanceRoutine();
        }
    }

    /**
     * ROM loc_45D72: Set up Super Sonic visuals.
     * Initiates palette cycling. The actual visual switching
     * (Map_SuperSonic frames) is handled by the palette cycler.
     */
    private void setupSuperSonic() {
        superSonicActive = true;
        paletteCycler().init();
        mappingFrame = SUPER_MAPPING_FRAME_BASE;
    }

    // -----------------------------------------------------------------------
    // Routine 0x0C: Super flash (wait 63 frames)
    // -----------------------------------------------------------------------

    private void routine12SuperFlash(AbstractPlayableSprite player) {
        // ROM: $3A countdown, no position movement
        secondaryTimer--;
        if (secondaryTimer < 0) {
            // Set up for walk right
            waveTimer = WAVE_SPAWN_INTERVAL;
            LOG.fine("Routine 12: super flash complete, advancing to walk right");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x0E: Walk right (x += 4/frame + wave spawns)
    // -----------------------------------------------------------------------

    private void routine14WalkRight(AbstractPlayableSprite player) {
        superSonicPaletteAnim();

        // ROM: Obj_Wait callback (wave spawn) runs BEFORE the walk increment.
        // This places the wave at the pre-increment X, 4px behind Super Sonic.
        tickWaveSpawn();

        // Walk right
        currentX += WALK_SPEED;

        // ROM: when x >= 0x200
        if (currentX >= WALK_RIGHT_TARGET) {
            secondaryTimer = SHORT_WAIT_TIMER;
            LOG.fine("Routine 14: walk right complete, advancing to wait");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x10: Wait (31 frames)
    // -----------------------------------------------------------------------

    private void routine16Wait(AbstractPlayableSprite player) {
        superSonicPaletteAnim();
        tickWaveSpawn();

        secondaryTimer--;
        if (secondaryTimer < 0) {
            // ROM: scroll speed increases to 0x0C at routine 0x12
            scrollSpeed = 0x0C;
            // ROM (loc_45A2A): bset #2,$38(a0) — signal plane child to walk left
            planeWalkLeft = true;
            LOG.fine("Routine 16: wait complete, advancing to walk left");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x12: Walk left (x -= 4/frame)
    // -----------------------------------------------------------------------

    private void routine18WalkLeft(AbstractPlayableSprite player) {
        superSonicPaletteAnim();
        tickWaveSpawn();

        // Walk left
        currentX -= WALK_SPEED;

        // ROM: when x <= 0x120
        if (currentX <= WALK_LEFT_TARGET) {
            // ROM: scroll speed increases to 0x10 at routine 0x14
            scrollSpeed = 0x10;
            secondaryTimer = SHORT_WAIT_TIMER;
            LOG.fine("Routine 18: walk left complete, advancing to wait");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x14: Wait (31 frames)
    // -----------------------------------------------------------------------

    private void routine20Wait(AbstractPlayableSprite player) {
        superSonicPaletteAnim();
        tickWaveSpawn();

        secondaryTimer--;
        if (secondaryTimer < 0) {
            LOG.fine("Routine 20: wait complete, advancing to monitor Knux");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x16: Monitor Knuckles trigger
    // -----------------------------------------------------------------------

    private void routine22MonitorKnux(AbstractPlayableSprite player) {
        superSonicPaletteAnim();

        // ROM: check Player_1.x_pos >= 0x918
        int checkX = currentX; // fallback if no player (test env)
        if (player != null) {
            checkX = player.getCentreX();
        }

        if (checkX >= KNUCKLES_SPAWN_X) {
            // Spawn Knuckles
            ObjectSpawn knuxSpawn = new ObjectSpawn(
                    CutsceneKnucklesAiz1Instance.INIT_X,
                    CutsceneKnucklesAiz1Instance.INIT_Y,
                    0, 0, 0, false, 0);
            knuckles = new CutsceneKnucklesAiz1Instance(knuxSpawn);
            spawnDynamicObject(knuckles);
            // Rock child is spawned by Knuckles' own routine0Init

            LOG.fine("Routine 22: spawned Knuckles");
            advanceRoutine();
        } else {
            tickWaveSpawn();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x18: Monitor approach
    // -----------------------------------------------------------------------

    private void routine24MonitorApproach(AbstractPlayableSprite player) {
        superSonicPaletteAnim();

        // ROM: check Player_1.x_pos >= 0x1240
        int checkX = currentX;
        if (player != null) {
            checkX = player.getCentreX();
        }

        if (checkX >= PLANE_ADJUST_X) {
            // ROM: y_pos -= 0x20
            currentY -= 0x20;

            LOG.fine("Routine 24: triggered approach adjustment");
            advanceRoutine();
        }
    }

    // -----------------------------------------------------------------------
    // Routine 0x1A: Explosion (release player, scatter emeralds, delete)
    // -----------------------------------------------------------------------

    private void routine26Explode(AbstractPlayableSprite player) {
        superSonicPaletteAnim();

        // ROM: check Player_1.x_pos >= 0x13D0
        int checkX = currentX;
        if (player != null) {
            checkX = player.getCentreX();
        }

        if (checkX >= EXPLOSION_TRIGGER_X) {
            // ROM routine 0x1A latches hurt state immediately, then Go_Delete_Sprite.
            // Sonic has already updated for this frame, so the new velocities only
            // affect movement on the next frame, but the end-of-frame trace already
            // shows hurt routine/air/velocity on the release frame.
            if (player != null) {
                player.setHidden(false);
                ObjectControlState.none().applyTo(player);
                ownsPlayerControl = false;
                // The object runs after the player slot. Retail publishes the
                // Hurt byte immediately but leaves the already displayed intro
                // frame intact until the next Animate_Sonic pass
                // (sonic3k.asm:135609-135619).
                player.setObjectMappingFrameControl(false);
                player.setAnimationId(Sonic3kAnimationIds.HURT);
                player.setYSpeed((short) -0x400);
                player.setXSpeed((short) -0x200);
                player.setGSpeed((short) 0);
                player.setHurt(true);
                player.setAir(true);
            }

            // ROM: clr.b (Super_Sonic_Knux_flag) + move.b #2,(Super_palette_status)
            //       + move.w #$1E,(Palette_frame)
            // Hand off palette revert to the global SuperSonic_PalCycle routine
            // (Sonic3kSuperStateController), which runs every frame via tickStatus().
            if (player.getSuperStateController()
                    instanceof Sonic3kSuperStateController s3kSuper) {
                s3kSuper.beginPaletteRevert(0x1E);
            }

            // Apply emerald palette now (overwrites intro palette on line 3)
            AizIntroArtLoader.applyEmeraldPalette(services());

            // Spawn 7 emeralds.
            // ROM: CreateChild6_Simple places emeralds in later object slots.
            // The emerald init code (loc_67900) reads Player_1.x_pos when that
            // slot is first processed — AFTER this object's scrollVelocity has
            // already added scrollSpeed to Player_1. We account for this by
            // adding scrollSpeed to the spawn X here, since our constructor
            // runs before scrollVelocity.
            int spawnX = currentX;
            int spawnY = currentY;
            if (player != null) {
                spawnX = player.getCentreX() + scrollSpeed;
                spawnY = player.getCentreY();
            }

            for (int i = 0; i < 7; i++) {
                int subtype = i * 2;
                ObjectSpawn emeraldSpawn = new ObjectSpawn(spawnX, spawnY, 0, subtype, 0, false, 0);
                AizEmeraldScatterInstance emerald = new AizEmeraldScatterInstance(emeraldSpawn);
                emerald.setKnuckles(knuckles);
                spawnDynamicObject(emerald);
                emeralds.add(emerald);

                // Compensate for pendingDynamicAdditions delay: ROM processes each
                // emerald's first movement in the same frame via CreateChild6_Simple.
                emerald.update(0, player);
            }

            // Trigger Knuckles (ROM: btst #7,status(parent) — signals rock child to break)
            if (knuckles != null) {
                knuckles.trigger();
            }

            setDestroyed(true);
        }
    }
}
