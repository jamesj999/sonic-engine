package com.openggf.game.sonic1.specialstage;

import com.openggf.audio.GameMusic;
import com.openggf.audio.GameSound;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.sonic1.Sonic1PlayerArt;
import com.openggf.game.sonic1.Sonic1RingArt;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommandable;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.physics.TrigLookupTable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.logging.Logger;

import static com.openggf.game.sonic1.constants.Sonic1Constants.*;
import static com.openggf.sprites.playable.AbstractPlayableSprite.*;

/**
 * Sonic 1 Special Stage runtime - rotating maze gameplay.
 *
 * Physics and collision logic faithfully translated from
 * "_incObj/09 Sonic in Special Stage.asm" (Obj09).
 *
 * <p>Coordinate system: 16.16 fixed-point for position (top 16 bits = pixels),
 * 16-bit for velocity and inertia. ssAngle is 16-bit where the top byte
 * is used as a 256-step hex angle for trig lookups.
 */
public final class Sonic1SpecialStageManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic1SpecialStageManager.class.getName());

    // Pattern atlas base for SS art (above normal level art range)
    private static final int SS_PATTERN_BASE = PatternAtlasRange.SONIC1_SPECIAL_STAGE.base();
    private static final int SS_ROLL_SPEED_SWITCH = 0x600;
    private static final int DEBUG_MOVE_SPEED = 3;
    private final BackgroundCommandPool backgroundCommandPool = new BackgroundCommandPool();

    // Collection animation buffer (matches v_ssitembuffer from SS_AniRingSparks)
    private static final int SS_ANIM_BUFFER_SIZE = 8;
    private static final int ANIM_NONE = 0;
    private static final int ANIM_RING_SPARKLE = 1;
    private static final int ANIM_BUMPER = 2; // SS_AniBumper (ss_ani_id=2)
    private static final int ANIM_REVERSE = 4; // SS_AniReverse (ss_ani_id=4)
    private static final int ANIM_GLASS_BLOCK = 6;
    private static final int ANIM_EMERALD_SPARKLE = 5; // SS_AniEmeraldSparks (ss_ani_id=5)
    private static final int[] ANIM_RING_SPARKLE_DATA = {0x42, 0x43, 0x44, 0x45, 0};
    private static final int ANIM_RING_PERIOD = 6; // 5+1 frames per step (ROM: move.b #5,2(a0))
    /**
     * SS_AniEmerData (docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:460):
     * {@code dc.b id_SS_Emerald_Ani1, id_SS_Emerald_Ani2, id_SS_Emerald_Ani3,
     * id_SS_Emerald_Ani4, 0}, driven by the same 5-tick delay as the ring
     * sparkle script (asm:440, {@code move.b #5,ss_ani_delay(a0)}).
     */
    private static final int[] ANIM_EMERALD_SPARKLE_DATA = {0x46, 0x47, 0x48, 0x49, 0};
    private static final int ANIM_EMERALD_PERIOD = 6; // 5+1 frames per step (ROM: move.b #5,ss_ani_delay(a0))
    private static final int[] ANIM_GLASS_DATA = {0x4B, 0x4C, 0x4D, 0x4E, 0x4B, 0x4C, 0x4D, 0x4E, 0};
    private static final int ANIM_GLASS_PERIOD = 2; // 1+1 frames per step (ROM: move.b #1,2(a0))
    /**
     * SS_AniBumpData (docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:381):
     * {@code dc.b id_SS_Bumper_Ani1, id_SS_Bumper_Ani2, id_SS_Bumper_Ani1, id_SS_Bumper_Ani2, 0}.
     * While this script is running, the touched block reads as $32/$33 (still
     * solid -- both ids are below id_SS_Ring/$3A -- but NOT $25), so a second
     * bounce off the same still-flashing bumper cannot fire until the script
     * hits its terminator and explicitly restores id_SS_Bumper ($25).
     */
    private static final int[] ANIM_BUMPER_DATA = {0x32, 0x33, 0x32, 0x33, 0};
    private static final int ANIM_BUMPER_PERIOD = 8; // 7+1 frames per step (ROM: move.b #7,ss_ani_delay(a0))
    // SS_AniRevData (docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:433)
    private static final int[] ANIM_REVERSE_DATA = {0x2B, 0x31, 0x2B, 0x31, 0};
    private static final int ANIM_REVERSE_PERIOD = 8; // 7+1 frames per step (ROM: move.b #7,ss_ani_delay(a0))

    // byte_4A3C: SS BG state table (32 entries, 4 fields each)
    // {time, anim, bgPlaneSelect (0=Plane_6, 1=Plane_5), palette-cycle selector byte}
    // Decoded from SSBGData macro in sonic.asm.
    private static final int[][] SS_BG_STATE_TABLE = {
            {3, 0, 0, 0x92}, {3, 0, 0, 0x90}, {3, 0, 0, 0x8E}, {3, 0, 0, 0x8C}, {3, 0, 0, 0x8B},
            {3, 0, 0, 0x80}, {3, 0, 0, 0x82}, {3, 0, 0, 0x84}, {3, 0, 0, 0x86}, {3, 0, 0, 0x88},
            {7, 8, 0, 0x00}, {7, 10, 0, 0x0C}, {-1, 12, 0, 0x18}, {-1, 12, 0, 0x18}, {7, 10, 0, 0x0C}, {7, 8, 0, 0x00},
            {3, 0, 1, 0x88}, {3, 0, 1, 0x86}, {3, 0, 1, 0x84}, {3, 0, 1, 0x82}, {3, 0, 1, 0x81},
            {3, 0, 1, 0x8A}, {3, 0, 1, 0x8C}, {3, 0, 1, 0x8E}, {3, 0, 1, 0x90}, {3, 0, 1, 0x92},
            {7, 2, 1, 0x24}, {7, 4, 1, 0x30}, {-1, 6, 1, 0x3C}, {-1, 6, 1, 0x3C}, {7, 4, 1, 0x30}, {7, 2, 1, 0x24}
    };

    /**
     * ROM-observable pre-physics hold before Obj09's first {@code ExecuteObjects}
     * tick. {@code GM_Special} (sonic.asm:3221-3299) never calls
     * {@code ExecuteObjects} during entry: it runs {@code PaletteWhiteOut}
     * (22-VBlank fade, "Palette Fading.asm":313-326), then an instant
     * (non-VBlank-waiting) setup block that loads the layout and sets
     * {@code v_ssangle}/{@code v_ssrotate}/{@code v_rings} (sonic.asm:3238-3291),
     * then {@code PaletteWhiteIn} (22-VBlank fade, "Palette Fading.asm":212-236)
     * before {@code SS_MainLoop} finally calls {@code ExecuteObjects}
     * (sonic.asm:3299-3306). Both fades use {@code move.w #22-1,d4} + {@code dbf}
     * (22 iterations each; Palette Fading.asm:227,233,316,322), so Obj09's
     * globals stay frozen at whatever value they held before the special-stage
     * mode switch for 44 consecutive VBlank ticks. See
     * {@link #advanceToEntryPresentation()} for the FAST-policy bypass and
     * {@code docs/architecture/plans/2026-07-18-...} design note for the S2
     * {@code SpecialStageStartupPolicy} precedent this mirrors.
     */
    private static final int SS_STARTUP_HOLD_TICKS = 44;

    /**
     * Length of the {@code PaletteWhiteOut} half of the hold ({@code
     * "_inc/Palette Fading.asm":313-326}, {@code move.w #22-1,d4} + {@code dbf}).
     * GM_Special's instant (non-VBlank-waiting) setup block -- {@code clr.w
     * (v_ssangle).w} / {@code move.w #ss_rotatespeed,(v_ssrotate).w}
     * (sonic.asm:3267-3268) -- runs right as the fade-out ends and PaletteWhiteIn
     * begins, i.e. after exactly this many hold ticks have been consumed. Until
     * then {@code v_ssangle}/{@code v_ssrotate} hold whatever they had before
     * the {@code $0C -> $10} mode switch: this engine simulates each special
     * stage visit standalone (no multi-stage chaining yet), so the pre-setup
     * value is modeled as the Java field default (0), matching a first-ever
     * special-stage visit's cold RAM. {@code x_pos}/{@code y_pos}/velocity/
     * inertia/status bits have the same "not yet written" property but live in
     * {@code v_objspace} instead, which genuinely carries the *previous zone's*
     * Sonic state during this window (leftover-normal-Sonic root, not
     * reproducible without chaining a prior trace segment) -- left untouched
     * here; see the frozen values already installed by {@link #initialize}.
     */
    private static final int SS_WHITEOUT_TICKS = 22;

    private boolean initialized;
    private boolean finished;
    private boolean emeraldCollected;
    private boolean debugMode;
    private int currentStage;
    private int ringsCollected;

    // ROM-observable pre-physics hold (see SS_STARTUP_HOLD_TICKS).
    private int startupHoldTicksRemaining;
    // One-shot Obj09_Main flag init (09 Sonic in Special Stage.asm:41-53),
    // applied on the object's first real ExecuteObjects tick, not at manager
    // initialize() time.
    private boolean objInitPending;

    // Layout
    private byte[] layout;

    // Rotation
    private int ssAngle;       // 16-bit rotation angle (top byte = hex angle)
    private int ssRotate;      // 16-bit rotation speed
    private int debugSavedAngle;
    private int debugSavedRotate;

    // Player position (16.16 fixed-point)
    private long sonicPosX;    // 32-bit: top 16 = pixel X
    private long sonicPosY;    // 32-bit: top 16 = pixel Y
    private int sonicVelX;     // 16-bit velocity X
    private int sonicVelY;     // 16-bit velocity Y
    private int sonicInertia;  // 16-bit inertia (speed along ground)
    private boolean sonicAirborne;  // obStatus bit 1
    private boolean sonicFacingLeft; // obStatus bit 0

    // Camera
    private int cameraX;
    private int cameraY;

    // Item interaction state
    private int ghostState;         // 0=none, 1=passed ghost, 2=solidify ghosts
    private int upDownCooldown;     // UP/DOWN block cooldown timer
    private int reverseCooldown;    // R block cooldown timer
    private int lastCollisionBlockId; // objoff_30 equivalent

    // Collision scratch: address of colliding block (for item interaction)
    private int lastCollisionRow;
    private int lastCollisionCol;

    // Animation
    private int wallRotFrame;       // 0-15, computed from ssAngle
    private int ringAnimFrame;      // 0-7, cycled via timer
    private int ringAnimTimer;
    private int wallVramAnimFrame;  // ani0: 8-frame decrementing cycle
    private int wallVramAnimTimer;
    private int[][] ssAnimBuffer;   // [slot][4: type, timer, frameIndex, layoutIndex]
    private int[] ssAnimGlassFinalBlock; // Final post-animation state for glass hits (Obj09_GlassUpdate)
    private int sonicAnimId;
    private int sonicAnimFrameIndex;
    private int sonicAnimFrameTimer;
    private int palSsTime;
    private int palSsNum;
    private int palSsIndex;
    private int ani2Frame;   // 0-1, period 8 (GOAL/UP/DOWN/emerald animation)
    private int ani2Timer;
    private int ani3Frame;   // 0-3, period 5 (glass block animation)
    private int ani3Timer;

    // Exit sequence
    private boolean exitTriggered;
    private int exitPhase;
    private int exitTimer;
    private boolean exitFadeStarted;
    private int exitFadeTimer;

    // Input state (set by handleInput, consumed by update)
    private int heldButtons;
    private int pressedButtons;
    private boolean debugSpeedUp;
    private boolean debugSlowDown;

    // BG animation state (SS_BGAnimate from sonic.asm)
    private int bgAnimState;           // v_ssbganim (0,2,4,6,8,10,12)
    private byte[][] fgPlaneTilemaps;  // SS FG namespaces plane1..4 (64x64 each)
    private byte[] bgPlane5Tilemap;    // SS BG namespace plane5 (64x64)
    private byte[] bgPlane6Tilemap;    // SS BG namespace plane6 (64x64)
    private boolean bgUsingPlane6;     // true=Plane_6 active, false=Plane_5 active
    private int fgAnimPlaneIndex;      // 0..3 => FG plane 1..4
    private int fgYScroll;             // v_scrposy_vdp from byte_4ABC (0 or 0x100)
    private int bgYScroll;             // v_bgscreenposy (vertical scroll offset, wraps at 256)
    private int bgExtraScrollX;        // v_bg3screenposx (FG uniform scroll component)
    private int[] bgSineBuffer;        // v_ngfx_buffer: 10 entries × 2 words [scroll, phase]
    private int[] bgBandBuffer;        // v_ssscroll_buffer: 7 entries × 2 words [pos_hi, pos_lo]
    private int[] bgHScrollData;       // 224-entry per-scanline H-scroll output

    // Subsystems
    private Sonic1SpecialStageDataLoader dataLoader;
    private Sonic1SpecialStageRenderer renderer;
    private GraphicsManager graphicsManager;
    private PlayerSpriteRenderer sonicSpriteRenderer;
    private int sonicSpriteFrame;
    private SpriteAnimationScript sonicRollScript;
    private SpriteAnimationScript sonicRoll2Script;
    private Sonic1SpecialStageBackgroundRenderer bgRenderer;
    private Sonic1SpecialStageBackgroundRenderer fgRenderer;
    private int bgCloudBase;
    private int bgFishBase;
    private Palette[] ssPalettes;
    /** Whether GM_Special's setup-block palette load has happened (see loadPalettes). */
    private boolean stagePalettesUploaded;
    private byte[] ssPaletteCycle1;
    private byte[] ssPaletteCycle2;

    public void initialize(int stageIndex) throws IOException {
        this.currentStage = Math.max(0, Math.min(stageIndex, SS_STAGE_COUNT - 1));
        this.ringsCollected = 0;
        this.emeraldCollected = false;
        this.finished = false;
        this.debugMode = false;

        // Initialize subsystems
        Rom rom = GameServices.rom().getRom();
        this.graphicsManager = GameServices.graphics();
        this.graphicsManager.setUseWaterShader(false);
        this.graphicsManager.setUseSpritePriorityShader(false);
        this.graphicsManager.setCurrentSpriteHighPriority(false);
        this.graphicsManager.setWaterEnabled(false);
        // Special stages always use their own palette; avoid stale underwater tint state.
        this.graphicsManager.setUseUnderwaterPaletteForBackground(false);
        this.dataLoader = new Sonic1SpecialStageDataLoader(rom);
        this.renderer = new Sonic1SpecialStageRenderer(graphicsManager);

        // Load layout
        layout = dataLoader.getStageLayout(currentStage);

        // Load start position
        int[] startPos = dataLoader.getStartPosition(currentStage);
        sonicPosX = (long) startPos[0] << 16;
        sonicPosY = (long) startPos[1] << 16;

        // Load palette
        loadPalette();

        // Load and cache art patterns
        loadArt();
        loadSonicSprite();

        // Initialize background renderer
        initBgRenderer();

        // Initialize rotation. v_ssangle/v_ssrotate are NOT written by GM_Special
        // until its instant setup block, which fires mid-hold after the
        // PaletteWhiteOut fade completes (see SS_WHITEOUT_TICKS) -- ssRotate
        // stays at the Java default (0) here and is set to SS_INIT_ROTATION at
        // that exact ROM-timeline point in update().
        ssAngle = 0;
        ssRotate = 0;
        debugSavedAngle = 0;
        debugSavedRotate = 0;

        // Initialize physics
        sonicVelX = 0;
        sonicVelY = 0;
        sonicInertia = 0;
        // obStatus bit 1 (in-air) is NOT set here: object RAM is zero-cleared by
        // GM_Special's clearRAM v_objspace (sonic.asm:3243) and stays zero
        // through both palette fades. Obj09_Main only sets it on the object's
        // first real ExecuteObjects tick (09 Sonic in Special Stage.asm:53),
        // which is deferred to objInitPending below.
        sonicAirborne = false;
        sonicFacingLeft = false;

        // Arm the ROM-observable pre-physics hold (see SS_STARTUP_HOLD_TICKS).
        startupHoldTicksRemaining = SS_STARTUP_HOLD_TICKS;
        objInitPending = true;

        // Initialize camera
        updateCamera();

        // Initialize interaction state
        ghostState = 0;
        upDownCooldown = 0;
        reverseCooldown = 0;
        lastCollisionBlockId = 0;

        // Initialize animation
        wallRotFrame = 0;
        ringAnimFrame = 0;
        ringAnimTimer = 0;
        wallVramAnimFrame = 0;
        wallVramAnimTimer = 0;
        ssAnimBuffer = new int[SS_ANIM_BUFFER_SIZE][4];
        ssAnimGlassFinalBlock = new int[SS_ANIM_BUFFER_SIZE];
        sonicAnimId = Sonic1AnimationIds.ROLL.id();
        sonicAnimFrameIndex = 0;
        sonicAnimFrameTimer = 0;

        // Initialize exit state
        exitTriggered = false;
        exitPhase = 0;
        exitTimer = 0;
        exitFadeStarted = false;
        exitFadeTimer = 0;

        // Initialize palette cycle state (PalCycle_SS)
        palSsTime = 0;
        palSsNum = 0;
        palSsIndex = 0;

        // Initialize GOAL/UP/DOWN/emerald and glass animation counters (SS_AniWallsRings ani2, ani3)
        ani2Frame = 0;
        ani2Timer = 0;
        ani3Frame = 0;
        ani3Timer = 0;

        // Clear input
        heldButtons = 0;
        pressedButtons = 0;
        debugSpeedUp = false;
        debugSlowDown = false;

        this.initialized = true;
        LOGGER.info("Special Stage " + (currentStage + 1) + " initialized");
    }

    public void update() {
        if (!initialized || finished) {
            return;
        }

        if (startupHoldTicksRemaining > 0) {
            // PaletteWhiteOut/instant-setup/PaletteWhiteIn never call
            // ExecuteObjects (see SS_STARTUP_HOLD_TICKS javadoc) -- hold every
            // Obj09-owned field frozen and consume only the input edge latch,
            // matching v_jpadpress2's per-VBlank hardware refresh.
            startupHoldTicksRemaining--;
            pressedButtons = 0;
            // The harness captures comparison state AFTER update() advances
            // (capture(frame) reflects frame+1 completed update() calls), so
            // the trigger fires one tick later than the raw whiteout/whitein
            // split to land the visible transition on the correct frame.
            if (startupHoldTicksRemaining == SS_STARTUP_HOLD_TICKS - SS_WHITEOUT_TICKS - 1) {
                // GM_Special's instant setup block (sonic.asm:3238-3291) runs
                // here, between the PaletteWhiteOut and PaletteWhiteIn fades:
                // bsr.w PalCycle_SS (sonic.asm:3266) fires ONE time to advance
                // the SS palette/BG-anim cycle before clr.w (v_ssangle).w and
                // move.w #ss_rotatespeed,(v_ssrotate).w (sonic.asm:3267-3268).
                // VBlank_SpecialStage -- the only other PalCycle_SS caller
                // ("_inc/Special Stage Background & Palette Cycle.asm":79-135,
                // wired at sonic.asm:888) -- never runs during either fade:
                // both PaletteWhiteOut/PaletteWhiteIn set v_vblank_routine to
                // id_VBlank_PaletteFade ($12), not id_VBlank_SpecialStage
                // ("_inc/Palette Fading.asm":238,278), so this setup-time call
                // is v_palss_time/v_palss_num's ONLY advance before SS_MainLoop.
                // Skipping it left the engine's palette-cycle table position
                // one full entry (byte_4A3C index 0, 4-frame duration) behind
                // the ROM for the rest of the stage, surfacing as a constant
                // 4-frame-late bg_anim transition (trace frame 137: expected
                // v_ssbganim=8, engine=0).
                uploadStagePalettes();
                updateSpecialStagePaletteCycle();
                ssAngle = 0;
                ssRotate = SS_INIT_ROTATION;
            }
            return;
        }

        if (objInitPending) {
            objInitPending = false;
            // SonicSS_Main (09 Sonic in Special Stage.asm:52-53): the object's
            // one-shot first-tick init sets the rolling + in-air flags before
            // falling through into SonicSS_Control on the SAME tick.
            sonicAirborne = true;
        }

        if (exitTriggered) {
            updateExit();
        } else if (debugMode) {
            processDebugMove();
            updateCamera();
            ssAngle = (ssAngle + ssRotate) & 0xFFFF;
            updateAnimCounters();
            updateBgAnimate();
        } else {
            // Process input
            lastCollisionBlockId = 0;

            // On ground: check jump, then move, then fall
            // In air: move, then fall
            if (!sonicAirborne) {
                processJump();
            }
            processMove();
            processFall();

            // Check items at Sonic's position
            checkItems();
            processItemInteraction();

            // Apply velocity (SpeedToPos)
            sonicPosX += (long) sonicVelX << 8;
            sonicPosY += (long) sonicVelY << 8;

            // Update camera
            updateCamera();

            // Rotate stage
            ssAngle = (ssAngle + ssRotate) & 0xFFFF;

            // Update animation counters
            updateAnimCounters();

            // Update background animation (sine wave / band scroll)
            updateBgAnimate();
        }

        // Clear pressed buttons (held persist until next handleInput call)
        pressedButtons = 0;
    }

    private void processDebugMove() {
        int moveSpeed = getDebugMoveSpeed();
        if ((heldButtons & INPUT_LEFT) != 0) {
            sonicPosX -= (long) moveSpeed << 16;
            sonicFacingLeft = true;
        }
        if ((heldButtons & INPUT_RIGHT) != 0) {
            sonicPosX += (long) moveSpeed << 16;
            sonicFacingLeft = false;
        }
        if ((heldButtons & INPUT_UP) != 0) {
            sonicPosY -= (long) moveSpeed << 16;
        }
        if ((heldButtons & INPUT_DOWN) != 0) {
            sonicPosY += (long) moveSpeed << 16;
        }

        // Keep movement deterministic when leaving debug mode.
        sonicVelX = 0;
        sonicVelY = 0;
        sonicInertia = 0;
        sonicAirborne = true;
        lastCollisionBlockId = 0;
    }

    private int getDebugMoveSpeed() {
        double multiplier = 1.0;
        if (debugSpeedUp) {
            multiplier *= 2.0;
        }
        if (debugSlowDown) {
            multiplier *= 0.5;
        }
        return (int) Math.max(1, Math.round(DEBUG_MOVE_SPEED * multiplier));
    }

    // ---- Physics (from Obj09) ----

    /**
     * SonicSS_Jump's angle transform (09 Sonic in Special Stage.asm:299-302):
     * <pre>
     *   move.b (v_ssangle).w,d0   ; d0 = high byte of ssAngle
     *   andi.b #$FC,d0            ; snap to nearest multiple of 4
     *   neg.b  d0                 ; negate -- 8-bit op, wraps mod 256
     *   subi.b #$40,d0            ; rotate perpendicular -- 8-bit op, wraps mod 256
     * </pre>
     * All three operations are byte-sized 68000 ops, so each intermediate
     * result must be truncated to 8 bits (mod 256) in the order the ROM
     * performs them: mask, THEN negate, THEN subtract. A prior version of
     * this method negated before masking (Java's unary-minus binds tighter
     * than {@code &}), which is a different value whenever the stage-angle's
     * top byte isn't already a multiple of 4 -- e.g. at trace frame 173
     * (S1 maze round-trip capture) ssAngle=0x12C0 going into the jump:
     * correct = ((-(0x12&0xFC))&0xFF - 0x40)&0xFF = 0xB0, but the old
     * mask-after-negate order produced 0xAC, corrupting the jump's
     * vel_x/vel_y by a fixed offset that then persisted for the rest of the
     * flight (gravity accumulates identically on both sides afterward).
     */
    private int ssJumpAngle() {
        int masked = (ssAngle >> 8) & 0xFC;
        int negated = (-masked) & 0xFF;
        return (negated - 0x40) & 0xFF;
    }

    /**
     * SonicSS_AngleSpeed's angle transform (09 Sonic in Special Stage.asm:
     * 178-181), used to convert ground inertia into world-space movement:
     * <pre>
     *   move.b (v_ssangle).w,d0   ; d0 = high byte of ssAngle
     *   addi.b #$20,d0            ; rotate 45 degrees -- 8-bit op, wraps mod 256
     *   andi.b #$C0,d0            ; snap to nearest multiple of 90 degrees
     *   neg.b  d0                 ; negate -- 8-bit op, wraps mod 256
     * </pre>
     * Same byte-truncation-order requirement as {@link #ssJumpAngle()}: add,
     * mask, THEN negate -- not negate-then-add-then-mask.
     */
    private int ssMoveAngle() {
        int added = ((ssAngle >> 8) + 0x20) & 0xFF;
        int masked = added & 0xC0;
        return (-masked) & 0xFF;
    }

    /**
     * Obj09_Jump: check for jump button press while on ground.
     * ROM tests {@code andi.b #btnABC,d0} on {@code v_jpadpress2} — any of the
     * A, B, or C buttons triggers the jump, not the engine's internal
     * INPUT_JUMP bit (which GameLoop does not set for special-stage input;
     * it forwards Mega Drive A/B/C button bits directly instead).
     */
    private void processJump() {
        if ((pressedButtons & SS_JUMP_BUTTONS) == 0) {
            return;
        }

        int angle = ssJumpAngle();
        int sinVal = TrigLookupTable.sinHex(angle & 0xFF);
        int cosVal = TrigLookupTable.cosHex(angle & 0xFF);

        // velX = cos * 0x680 >> 8, velY = sin * 0x680 >> 8
        sonicVelX = (short) ((cosVal * SS_JUMP_FORCE) >> 8);
        sonicVelY = (short) ((sinVal * SS_JUMP_FORCE) >> 8);
        sonicAirborne = true;
        playSfx(Sonic1Sfx.JUMP);
    }

    /**
     * Obj09_Move: horizontal movement along the maze surface.
     */
    private void processMove() {
        boolean leftHeld = (heldButtons & INPUT_LEFT) != 0;
        boolean rightHeld = (heldButtons & INPUT_RIGHT) != 0;

        if (leftHeld) {
            moveLeft();
        }
        if (rightHeld) {
            moveRight();
        }

        // Decelerate when no input
        if (!leftHeld && !rightHeld) {
            if (sonicInertia != 0) {
                if (sonicInertia > 0) {
                    sonicInertia -= SS_ACCEL;
                    if (sonicInertia < 0) sonicInertia = 0;
                } else {
                    sonicInertia += SS_ACCEL;
                    if (sonicInertia > 0) sonicInertia = 0;
                }
            }
        }

        // Convert inertia to world movement
        int angle = ssMoveAngle();
        int sinVal = TrigLookupTable.sinHex(angle & 0xFF);
        int cosVal = TrigLookupTable.cosHex(angle & 0xFF);

        long dx = (long) cosVal * sonicInertia;
        long dy = (long) sinVal * sonicInertia;

        // Save position for collision revert
        long savedX = sonicPosX;
        long savedY = sonicPosY;

        sonicPosX += dx;
        sonicPosY += dy;

        // Collision check
        if (checkCollision()) {
            sonicPosX = savedX;
            sonicPosY = savedY;
            sonicInertia = 0;
        }
    }

    private void moveLeft() {
        sonicFacingLeft = true;
        if (sonicInertia > 0) {
            // Braking
            sonicInertia -= SS_BRAKE;
            if (sonicInertia < 0) {
                // nop in original - allows crossing zero
            }
        } else {
            // Accelerate left
            sonicInertia -= SS_ACCEL;
            if (sonicInertia < -SS_MAX_SPEED) {
                sonicInertia = -SS_MAX_SPEED;
            }
        }
    }

    private void moveRight() {
        sonicFacingLeft = false;
        if (sonicInertia < 0) {
            // Braking
            sonicInertia += SS_BRAKE;
            if (sonicInertia > 0) {
                // nop in original - allows crossing zero
            }
        } else {
            // Accelerate right
            sonicInertia += SS_ACCEL;
            if (sonicInertia > SS_MAX_SPEED) {
                sonicInertia = SS_MAX_SPEED;
            }
        }
    }

    /**
     * Obj09_Fall: gravity and velocity-based movement with collision.
     *
     * This is the core physics routine. It applies gravity along the rotated
     * axis, then tests X and Y movement independently for collision.
     */
    private void processFall() {
        long savedX = sonicPosX;
        long savedY = sonicPosY;

        // CalcSine with byte angle from ssAngle (masked to 0xFC)
        int byteAngle = (ssAngle >> 8) & 0xFC;
        int sinVal = TrigLookupTable.sinHex(byteAngle);
        int cosVal = TrigLookupTable.cosHex(byteAngle);

        // Apply gravity to velocity (shifted by 8)
        long velXShifted = (long) sonicVelX << 8;
        long velYShifted = (long) sonicVelY << 8;

        // d0 = sin * 0x2A + velX<<8, d1 = cos * 0x2A + velY<<8
        long d0 = (long) sinVal * SS_GRAVITY + velXShifted;
        long d1 = (long) cosVal * SS_GRAVITY + velYShifted;

        // Try X movement first using temporary probe positions.
        long probeX = savedX + d0;
        long probeY = savedY;

        if (checkCollisionAt(probeX, probeY)) {
            // X blocked: clear X velocity, clear airborne
            d0 = 0;
            sonicVelX = 0;
            sonicAirborne = false;

            // Now try Y
            probeY = savedY + d1;
            if (checkCollisionAt(savedX, probeY)) {
                // Y also blocked: clear Y velocity
                d1 = 0;
                sonicVelY = 0;
            }
            sonicVelX = (short) (d0 >> 8);
            sonicVelY = (short) (d1 >> 8);
        } else {
            // X succeeded: try Y
            probeY = savedY + d1;
            if (checkCollisionAt(probeX, probeY)) {
                // Y blocked: clear Y velocity, clear airborne
                d1 = 0;
                sonicVelY = 0;
                sonicAirborne = false;
                sonicVelX = (short) (d0 >> 8);
                sonicVelY = (short) (d1 >> 8);
            } else {
                // Both succeeded: airborne, extract velocities
                sonicVelX = (short) (d0 >> 8);
                sonicVelY = (short) (d1 >> 8);
                sonicAirborne = true;
            }
        }
    }

    // ---- Collision (from sub_1BCE8) ----

    /**
     * Checks if Sonic's current position collides with solid blocks.
     * Tests a 2x2 grid of cells around Sonic's position.
     *
     * From sub_1BCE8: the position is offset by (+0x44, +0x14) before
     * dividing by block size to get grid coordinates, then checks
     * [row,col], [row,col+1], [row+1,col], [row+1,col+1].
     */
    private boolean checkCollision() {
        return checkCollisionAt(sonicPosX, sonicPosY);
    }

    /**
     * SonicSS_FindWall (09 Sonic in Special Stage.asm:520-583): checks the
     * four block-buffer cells around a probe position and reports whether
     * any of them is solid.
     *
     * <p><b>All four cells are visited unconditionally -- no early return.</b>
     * The ROM's {@code SonicSS_FindWall_CheckType} (sub_1BD30, lines
     * 561-583) is called once per cell -- top-left, top-right, bottom-left,
     * bottom-right, in that order (lines 547-555) -- and every solid hit
     * unconditionally overwrites {@code sonss_touchedblock_id}/
     * {@code sonss_touchedblock_ram} (lines 579-580); the "collision found"
     * flag {@code d5} is only tested once, after all four cells have been
     * visited (line 557). So when two of the four cells are solid, the LAST
     * one visited in scan order -- not the first -- is the one whose id
     * survives into {@code sonss_touchedblock_id} and therefore drives
     * {@code Obj09_ChkItems2}'s block-specific reaction (bumper bounce, GOAL,
     * UP/DOWN, glass, ...).
     *
     * <p>A prior version of this method returned as soon as it found the
     * FIRST solid cell in scan order, which is observably different: at the
     * S1 maze round-trip capture's trace frame 319, Sonic's fall probe hits a
     * plain wall block ($19) at the top-right cell while a bumper block
     * ($25) sits solid at the bottom-right cell one row below. The ROM's
     * unconditional-last-wins scan lands on the bumper, so
     * {@code Obj09_ChkItems2} (see the "Bumper ($25)" branch in
     * {@link #processItemInteraction()}) fires {@link #processBumper()} that
     * same tick, producing the recorded bounce (large outward vel_x/vel_y,
     * in-air flag set). The early-return version never saw the bumper cell,
     * so it stayed grounded against the wall with the fall's clamped-to-zero
     * velocity -- a divergence that then cascaded through position/velocity/
     * airborne for the rest of the recorded segment.
     */
    private boolean checkCollisionAt(long posXFixed, long posYFixed) {
        int posX = (int) (posXFixed >> 16);
        int posY = (int) (posYFixed >> 16);

        int gridCol = (posX + 0x14) / SS_BLOCK_SIZE_PX;
        int gridRow = (posY + 0x44) / SS_BLOCK_SIZE_PX;

        boolean solidFound = false;

        // Check all 2x2 cells -- top-left, top-right, bottom-left,
        // bottom-right -- with NO early return (see javadoc above): a later
        // solid cell must overwrite the earlier one's touched-block id/row/
        // col, matching the ROM's unconditional four-cell scan.
        for (int dr = 0; dr < 2; dr++) {
            for (int dc = 0; dc < 2; dc++) {
                int r = gridRow + dr;
                int c = gridCol + dc;
                int bufIndex = r * SS_LAYOUT_STRIDE + c;
                if (bufIndex < 0 || bufIndex >= layout.length) continue;

                int blockId = layout[bufIndex] & 0xFF;
                if (Sonic1SpecialStageBlockType.isSolid(blockId)) {
                    lastCollisionBlockId = blockId;
                    lastCollisionRow = r;
                    lastCollisionCol = c;
                    solidFound = true;
                }
            }
        }
        return solidFound;
    }

    // ---- Item Checks (from Obj09_ChkItems / Obj09_ChkItems2) ----

    /**
     * Obj09_ChkItems: checks for collectible items at Sonic's feet.
     * Item check uses a different offset than collision: (+0x50, +0x20).
     */
    private void checkItems() {
        int posX = (int) (sonicPosX >> 16);
        int posY = (int) (sonicPosY >> 16);

        int gridCol = (posX + 0x20) / SS_BLOCK_SIZE_PX;
        int gridRow = (posY + 0x50) / SS_BLOCK_SIZE_PX;
        int bufIndex = gridRow * SS_LAYOUT_STRIDE + gridCol;

        if (bufIndex < 0 || bufIndex >= layout.length) return;
        int blockId = layout[bufIndex] & 0xFF;

        if (blockId == 0) {
            // Empty cell - check ghost solidification
            if (ghostState == 2) {
                makeGhostsSolid();
                ghostState = 0;
            }
            return;
        }

        // Ring (0x3A)
        if (blockId == 0x3A) {
            ringsCollected++;
            playSfx(GameSound.RING);
            startItemAnimation(bufIndex);
            return;
        }

        // 1UP (0x28)
        if (blockId == 0x28) {
            layout[bufIndex] = 0;
            playMusic(GameMusic.EXTRA_LIFE);
            return;
        }

        // Emerald (0x3B-0x40): SonicSS_ChkEmerald/SonicSS_GetEmerald
        // (docs/s1disasm/_incObj/09 Sonic in Special Stage.asm:670-701)
        // increments (v_emeralds).w and queues the emerald jingle immediately
        // on touch -- that part is instant, matching emeraldCollected below.
        // The exit routine, however, is NOT armed here and NOT gated solely
        // behind SonicSS_ChkGOAL's GOAL-block `addq.b #2,obRoutine(a0)`
        // (asm:823-832): SS_AniEmeraldSparks -- the same collection-animation
        // queue entry the ring sparkle uses (docs/s1disasm/_inc/Special Stage
        // Loading & Drawing.asm:437-458), registered here via
        // {@link SS_FindFreeAnimationSlot} exactly like the ring branch above
        // -- writes `move.b #4,(v_player+obRoutine).w` itself once its 4-frame,
        // 5-tick-per-frame script (id_SS_Emerald_Ani1..4, asm:460) finishes
        // (asm:453). So the exit is deferred by the sparkle animation's
        // ~24-tick run, not by GOAL. A prior version of this branch set
        // exitTriggered synchronously here, which desynced the S1 maze
        // round-trip capture at trace frame 2973 by freezing physics one
        // tick after the emerald pickup instead of waiting out the sparkle;
        // that regression is fixed by routing through
        // {@link #startEmeraldAnimation(int)} below instead of an immediate
        // clear + trigger.
        if (blockId >= 0x3B && blockId <= 0x40) {
            emeraldCollected = true;
            playMusic(GameMusic.EMERALD);
            startEmeraldAnimation(bufIndex);
            return;
        }

        // Ghost block (0x41) - mark as passed
        if (blockId == 0x41) {
            ghostState = 1;
            return;
        }

        // Ghost switch (0x4A) - trigger solidification if passed
        if (blockId == 0x4A) {
            if (ghostState == 1) {
                ghostState = 2;
            }
            return;
        }
    }

    /**
     * Obj09_ChkItems2: processes collision-based interactions (bumper, GOAL, UP/DOWN, R, glass).
     * These use lastCollisionBlockId set during collision detection.
     */
    private void processItemInteraction() {
        int blockId = lastCollisionBlockId;
        if (blockId == 0) {
            // Decrement cooldowns
            if (upDownCooldown > 0) upDownCooldown--;
            if (reverseCooldown > 0) reverseCooldown--;
            return;
        }

        // Bumper (0x25)
        if (blockId == 0x25) {
            processBumper();
            return;
        }

        // GOAL (0x27)
        if (blockId == 0x27) {
            exitTriggered = true;
            playSfx(Sonic1Sfx.SS_GOAL);
            return;
        }

        // UP block (0x29)
        if (blockId == 0x29) {
            if (upDownCooldown == 0) {
                upDownCooldown = SS_UP_DOWN_COOLDOWN;
                // If rotation is slow (bit 6 of low byte set), double speed
                if ((ssRotate & 0x40) != 0) {
                    ssRotate <<= 1;
                    // Only a block that ACTUALLY sped the stage up becomes a
                    // DOWN block: SonicSS_ChkUP's beq skips the asl.w AND the
                    // move.b #id_SS_DOWN together (09 Sonic in Special
                    // Stage.asm:853-862), so a rotation already at fast speed
                    // leaves the block an UP block. Converting it regardless
                    // put the layout one toggle out of step with the ROM for
                    // the rest of the stage.
                    flipUpDownBlock(0x2A);
                }
                playSfx(Sonic1Sfx.SS_ITEM);
            }
            return;
        }

        // DOWN block (0x2A)
        if (blockId == 0x2A) {
            if (upDownCooldown == 0) {
                upDownCooldown = SS_UP_DOWN_COOLDOWN;
                // If rotation is fast (bit 6 not set), halve speed
                if ((ssRotate & 0x40) == 0) {
                    ssRotate >>= 1;
                    // Same gate as the UP block above: SonicSS_ChkDOWN's bne
                    // skips the asr.w and the move.b #id_SS_UP together
                    // (09 Sonic in Special Stage.asm:888-897).
                    flipUpDownBlock(0x29);
                }
                playSfx(Sonic1Sfx.SS_ITEM);
            }
            return;
        }

        // R block (0x2B)
        if (blockId == 0x2B) {
            if (reverseCooldown == 0) {
                reverseCooldown = SS_UP_DOWN_COOLDOWN;
                int idx = lastCollisionRow * SS_LAYOUT_STRIDE + lastCollisionCol;
                startReverseAnimation(idx);
                ssRotate = -ssRotate; // Reverse rotation
                playSfx(Sonic1Sfx.SS_ITEM);
            }
            return;
        }

        // Glass blocks (0x2D-0x30)
        if (blockId >= 0x2D && blockId <= 0x30) {
            int idx = lastCollisionRow * SS_LAYOUT_STRIDE + lastCollisionCol;
            if (idx >= 0 && idx < layout.length) {
                int nextState = blockId + 1;
                if (nextState > 0x30) {
                    nextState = 0; // Glass destroyed
                }
                startGlassAnimation(idx, nextState);
            }
            playSfx(Sonic1Sfx.SS_GLASS);
        }
    }

    /**
     * Rewrites the just-touched UP/DOWN block as its counterpart, the way the
     * ROM's {@code movea.l sonss_touchedblock_ram(a0),a1 / subq.l #1,a1 /
     * move.b #id_SS_*,(a1)} tail does (09 Sonic in Special Stage.asm:859-862,
     * 894-897). The touched cell is the one {@code SonicSS_FindWall_CheckType}
     * last recorded, which is what {@link #lastCollisionRow}/
     * {@link #lastCollisionCol} track.
     */
    private void flipUpDownBlock(int replacementId) {
        int idx = lastCollisionRow * SS_LAYOUT_STRIDE + lastCollisionCol;
        if (idx >= 0 && idx < layout.length) {
            layout[idx] = (byte) replacementId;
        }
    }

    /**
     * Processes bumper bounce from Obj09_ChkBumper.
     * Calculates angle from bumper center to Sonic, applies outward velocity.
     */
    private void processBumper() {
        // Bumper center: convert grid position back to world coordinates
        int bumperX = lastCollisionCol * SS_BLOCK_SIZE_PX - 0x14;
        int bumperY = lastCollisionRow * SS_BLOCK_SIZE_PX - 0x44;

        int sonicPixelX = (int) (sonicPosX >> 16);
        int sonicPixelY = (int) (sonicPosY >> 16);

        // Calculate direction from bumper to Sonic
        short dx = (short) (bumperX - sonicPixelX);
        short dy = (short) (bumperY - sonicPixelY);

        int angle = TrigLookupTable.calcAngle(dx, dy);
        int sinVal = TrigLookupTable.sinHex(angle);
        int cosVal = TrigLookupTable.cosHex(angle);

        // Apply outward velocity (negative = away from bumper)
        sonicVelX = (short) ((cosVal * -SS_BUMPER_FORCE) >> 8);
        sonicVelY = (short) ((sinVal * -SS_BUMPER_FORCE) >> 8);
        sonicAirborne = true;

        // Obj09_ChkBumper (docs/s1disasm/_incObj/09 Sonic in Special Stage.asm:
        // 810-816): register the SS_AniBumper animation on the touched block
        // so it flashes through $32/$33 and only reverts to the solid,
        // re-triggerable $25 id once the script's terminator runs. Without
        // this, a bumper that Sonic has just bounced off keeps reading as
        // $25 forever, so re-touching it while the real ROM's flash is still
        // playing wrongly fires a second bounce instead of behaving like an
        // inert wall.
        int touchedIdx = lastCollisionRow * SS_LAYOUT_STRIDE + lastCollisionCol;
        startBumperAnimation(touchedIdx);

        playSfx(Sonic1Sfx.BUMPER);
    }

    /**
     * Replaces all ghost blocks (0x41) with solid blocks (0x2C).
     */
    private void makeGhostsSolid() {
        for (int row = 0; row < SS_BLOCKBUFFER_ROWS; row++) {
            int rowOff = SS_BLOCKBUFFER_OFFSET + row * SS_LAYOUT_STRIDE;
            for (int col = 0; col < SS_LAYOUT_COLS; col++) {
                int idx = rowOff + col;
                if (idx < layout.length && (layout[idx] & 0xFF) == 0x41) {
                    layout[idx] = 0x2C;
                }
            }
        }
    }

    // ---- Item Animation (from SS_AniRingSparks) ----

    private void startItemAnimation(int layoutIndex) {
        if (ssAnimBuffer == null) {
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = 0;
            }
            return;
        }
        for (int i = 0; i < SS_ANIM_BUFFER_SIZE; i++) {
            if (ssAnimBuffer[i][0] == ANIM_NONE) {
                ssAnimBuffer[i][0] = ANIM_RING_SPARKLE;
                ssAnimBuffer[i][1] = ANIM_RING_PERIOD;
                ssAnimBuffer[i][2] = 0;
                ssAnimBuffer[i][3] = layoutIndex;
                // Write first sparkle frame immediately
                if (layoutIndex >= 0 && layoutIndex < layout.length) {
                    layout[layoutIndex] = (byte) ANIM_RING_SPARKLE_DATA[0];
                }
                return;
            }
        }
        // No free slot - just clear the cell
        if (layoutIndex >= 0 && layoutIndex < layout.length) {
            layout[layoutIndex] = 0;
        }
    }

    /**
     * SS_AniEmeraldSparks registration (SonicSS_ChkEmerald,
     * docs/s1disasm/_incObj/09 Sonic in Special Stage.asm:676-679): allocates
     * an animation slot via {@code SS_FindFreeAnimationSlot}. When that fails
     * (all 8 slots busy), the ROM skips registration entirely (falls straight
     * through to {@code SonicSS_GetEmerald}) and leaves the touched block's
     * byte untouched -- unlike the ring helper above, there is no "clear to
     * 0" fallback here to stay faithful to that ROM branch.
     *
     * <p>Delay starts at 0 (not {@link #ANIM_EMERALD_PERIOD}), matching the
     * {@link #startBumperAnimation(int)}/{@link #startGlassAnimation(int, int)}
     * pattern rather than the ring branch's manual immediate write: a freshly
     * claimed animation slot's memory is zero-cleared in the ROM (either from
     * stage init or the previous occupant's {@code clr.l (a0)} on
     * completion), and {@code SS_ExecuteAnimationQueue} (called from
     * {@code SS_ShowLayout}, AFTER {@code ExecuteObjects} in the SAME
     * {@code SS_MainLoop} iteration -- sonic.asm:3306-3309) runs this same
     * game tick and sees the fresh slot's delay=0, so the FIRST script entry
     * fires on this same tick via the natural {@link #updateEmeraldAnimation(int)}
     * call below rather than a manual pre-write. Manually pre-writing (as the
     * ring branch does) plus also letting the same-tick
     * {@code updateItemAnimations()} call decrement double-counts one tick,
     * which pushed the 4-entry sparkle script's completion -- and therefore
     * the {@code obRoutine=4} exit arm (asm:453) -- one recorded frame early:
     * the S1 maze round-trip capture's single lag frame at trace frame 2976
     * (one non-stepped update() call between the emerald pickup at frame 2972
     * and the exit-arm frame 2997) makes the completion tick land on trace
     * frame 2997 only when the FIRST script write is same-tick, matching
     * this delay=0 pattern; a same-tick pre-write plus an extra decrement
     * completed the script (and froze physics) a full tick early at frame
     * 2996, one before the recorded ROM exit ramp actually starts (frame 2998).
     */
    private void startEmeraldAnimation(int layoutIndex) {
        if (ssAnimBuffer == null) {
            return;
        }
        for (int i = 0; i < SS_ANIM_BUFFER_SIZE; i++) {
            if (ssAnimBuffer[i][0] == ANIM_NONE) {
                ssAnimBuffer[i][0] = ANIM_EMERALD_SPARKLE;
                ssAnimBuffer[i][1] = 0; // Trigger first frame immediately on next animation tick
                ssAnimBuffer[i][2] = 0;
                ssAnimBuffer[i][3] = layoutIndex;
                return;
            }
        }
        // ROM behavior when no slot is free: leave the block untouched.
    }

    private void startGlassAnimation(int layoutIndex, int finalBlockId) {
        if (ssAnimBuffer == null || ssAnimGlassFinalBlock == null) {
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = (byte) finalBlockId;
            }
            return;
        }
        for (int i = 0; i < SS_ANIM_BUFFER_SIZE; i++) {
            if (ssAnimBuffer[i][0] == ANIM_NONE) {
                ssAnimBuffer[i][0] = ANIM_GLASS_BLOCK;
                ssAnimBuffer[i][1] = 0; // Trigger first frame immediately on next animation tick
                ssAnimBuffer[i][2] = 0;
                ssAnimBuffer[i][3] = layoutIndex;
                ssAnimGlassFinalBlock[i] = finalBlockId;
                return;
            }
        }
        // ROM behavior when no slot is free: skip the transition this frame.
    }

    /**
     * Obj09_ChkBumper's animation registration (docs/s1disasm/_incObj/09 Sonic
     * in Special Stage.asm:810-816): {@code SS_FindFreeAnimationSlot}; if none
     * is free, the ROM branches straight to the bump sound and never writes
     * an animation entry -- the touched block is left at $25 unmodified (a
     * rare, ROM-faithful edge case when all 8 slots are already busy).
     */
    private void startBumperAnimation(int layoutIndex) {
        if (ssAnimBuffer == null) {
            return;
        }
        for (int i = 0; i < SS_ANIM_BUFFER_SIZE; i++) {
            if (ssAnimBuffer[i][0] == ANIM_NONE) {
                ssAnimBuffer[i][0] = ANIM_BUMPER;
                ssAnimBuffer[i][1] = 0; // Trigger first frame immediately on next animation tick
                ssAnimBuffer[i][2] = 0;
                ssAnimBuffer[i][3] = layoutIndex;
                return;
            }
        }
        // ROM behavior when no slot is free: skip the animation this frame.
    }

    /**
     * SS_AniReverse registration (SonicSS_ChkR, docs/s1disasm/_incObj/09 Sonic
     * in Special Stage.asm:907-920): claim a queue slot for animation ID 4
     * before reversing the stage rotation. If all slots are occupied, the ROM
     * still reverses the stage and simply skips the visual animation.
     */
    private void startReverseAnimation(int layoutIndex) {
        if (ssAnimBuffer == null) {
            return;
        }
        for (int i = 0; i < SS_ANIM_BUFFER_SIZE; i++) {
            if (ssAnimBuffer[i][0] == ANIM_NONE) {
                ssAnimBuffer[i][0] = ANIM_REVERSE;
                ssAnimBuffer[i][1] = 0; // Trigger the first script entry on the animation tick
                ssAnimBuffer[i][2] = 0;
                ssAnimBuffer[i][3] = layoutIndex;
                return;
            }
        }
        // ROM behavior when no slot is free: reverse the stage without flashing the block.
    }

    private void updateItemAnimations() {
        if (ssAnimBuffer == null) return;
        for (int i = 0; i < SS_ANIM_BUFFER_SIZE; i++) {
            int type = ssAnimBuffer[i][0];
            if (type == ANIM_NONE) continue;
            if (type == ANIM_RING_SPARKLE) {
                updateRingAnimation(i);
                continue;
            }
            if (type == ANIM_BUMPER) {
                updateBumperAnimation(i);
                continue;
            }
            if (type == ANIM_REVERSE) {
                updateReverseAnimation(i);
                continue;
            }
            if (type == ANIM_GLASS_BLOCK) {
                updateGlassAnimation(i);
                continue;
            }
            if (type == ANIM_EMERALD_SPARKLE) {
                updateEmeraldAnimation(i);
                continue;
            }
            ssAnimBuffer[i][0] = ANIM_NONE;
        }
    }

    private void updateRingAnimation(int slot) {
        ssAnimBuffer[slot][1]--;
        if (ssAnimBuffer[slot][1] > 0) return;
        ssAnimBuffer[slot][1] = ANIM_RING_PERIOD;
        ssAnimBuffer[slot][2]++;
        int frameIdx = ssAnimBuffer[slot][2];
        int layoutIndex = ssAnimBuffer[slot][3];

        int nextBlockId = (frameIdx < ANIM_RING_SPARKLE_DATA.length)
                ? ANIM_RING_SPARKLE_DATA[frameIdx] : 0;
        if (nextBlockId == 0) {
            // Animation complete - clear cell and free slot
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = 0;
            }
            ssAnimBuffer[slot][0] = ANIM_NONE;
            return;
        }
        if (layoutIndex >= 0 && layoutIndex < layout.length) {
            layout[layoutIndex] = (byte) nextBlockId;
        }
    }

    /**
     * SS_AniEmeraldSparks (docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:
     * 437-458): structurally identical to {@link #updateRingAnimation(int)}
     * (same 5-tick delay, same 4-entry-then-terminator script shape), except
     * that reaching the terminator ALSO arms the special-stage exit --
     * `move.b #4,(v_player+obRoutine).w` (asm:453) sets Obj09's routine
     * straight to {@code SonicSS_ExitStage}, and `move.w #sfx_SSGoal,d0` /
     * `jsr (QueueSound2).l` (asm:454-455) plays the same GOAL jingle the
     * GOAL-block branch in {@link #processItemInteraction()} plays. This is
     * how a single-emerald special stage (like the recorded GHZ round-trip
     * maze) ends without ever touching an {@code id_SS_GOAL} ($27) block.
     */
    private void updateEmeraldAnimation(int slot) {
        ssAnimBuffer[slot][1]--;
        if (ssAnimBuffer[slot][1] > 0) return;
        ssAnimBuffer[slot][1] = ANIM_EMERALD_PERIOD;

        // Read the script index BEFORE advancing it (SS_AniEmeraldSparks:
        // `move.b ss_ani_frame(a0),d0` reads, THEN `addq.b #1,ss_ani_frame(a0)`
        // advances -- asm:443-444), matching the bumper/glass helpers'
        // pre-increment read rather than the ring branch's post-increment one.
        int frameIdx = ssAnimBuffer[slot][2];
        ssAnimBuffer[slot][2] = frameIdx + 1;
        int layoutIndex = ssAnimBuffer[slot][3];
        int nextBlockId = (frameIdx < ANIM_EMERALD_SPARKLE_DATA.length)
                ? ANIM_EMERALD_SPARKLE_DATA[frameIdx] : 0;

        if (nextBlockId != 0) {
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = (byte) nextBlockId;
            }
            return;
        }

        // Terminator (asm:446-455): the ROM writes the terminator's 0 to the
        // block unconditionally via the same `move.b d0,(a1)` as every other
        // script entry, THEN frees the slot and arms the exit.
        if (layoutIndex >= 0 && layoutIndex < layout.length) {
            layout[layoutIndex] = 0;
        }
        ssAnimBuffer[slot][0] = ANIM_NONE;
        exitTriggered = true;
        playSfx(Sonic1Sfx.SS_GOAL);
    }

    /**
     * SS_AniBumper (docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:
     * 356-382): decrements the 7-frame delay; on expiry, reads
     * {@code ANIM_BUMPER_DATA[frameIdx]} and either writes the flashing
     * $32/$33 frame ("still recovering", not equal to id_SS_Bumper so a
     * touch cannot re-fire {@link #processBumper()}) or, once the script's
     * terminator is hit, frees the slot and explicitly restores id_SS_Bumper
     * ($25, {@code move.b #id_SS_Bumper,(a1)}) so the block becomes
     * re-triggerable again.
     */
    private void updateBumperAnimation(int slot) {
        ssAnimBuffer[slot][1]--;
        if (ssAnimBuffer[slot][1] > 0) return;
        ssAnimBuffer[slot][1] = ANIM_BUMPER_PERIOD;

        int frameIdx = ssAnimBuffer[slot][2];
        ssAnimBuffer[slot][2] = frameIdx + 1;
        int layoutIndex = ssAnimBuffer[slot][3];
        int nextBlockId = (frameIdx < ANIM_BUMPER_DATA.length) ? ANIM_BUMPER_DATA[frameIdx] : 0;

        if (nextBlockId != 0) {
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = (byte) nextBlockId;
            }
            return;
        }

        if (layoutIndex >= 0 && layoutIndex < layout.length) {
            layout[layoutIndex] = (byte) 0x25; // id_SS_Bumper: reset to idle, re-triggerable
        }
        ssAnimBuffer[slot][0] = ANIM_NONE;
    }

    /**
     * SS_AniReverse (docs/s1disasm/_inc/Special Stage Loading & Drawing.asm:
     * 408-435): flash the touched R block through the ROM's four-entry script,
     * then restore the idle R mapping when the terminator is reached.
     */
    private void updateReverseAnimation(int slot) {
        ssAnimBuffer[slot][1]--;
        if (ssAnimBuffer[slot][1] > 0) return;
        ssAnimBuffer[slot][1] = ANIM_REVERSE_PERIOD;

        int frameIdx = ssAnimBuffer[slot][2];
        ssAnimBuffer[slot][2] = frameIdx + 1;
        int layoutIndex = ssAnimBuffer[slot][3];
        int nextBlockId = (frameIdx < ANIM_REVERSE_DATA.length)
                ? ANIM_REVERSE_DATA[frameIdx] : 0;

        if (nextBlockId != 0) {
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = (byte) nextBlockId;
            }
            return;
        }

        if (layoutIndex >= 0 && layoutIndex < layout.length) {
            layout[layoutIndex] = 0x2B; // id_SS_R: reset R block to idle
        }
        ssAnimBuffer[slot][0] = ANIM_NONE;
    }

    private void updateGlassAnimation(int slot) {
        ssAnimBuffer[slot][1]--;
        if (ssAnimBuffer[slot][1] > 0) return;
        ssAnimBuffer[slot][1] = ANIM_GLASS_PERIOD;

        int frameIdx = ssAnimBuffer[slot][2];
        ssAnimBuffer[slot][2] = frameIdx + 1;
        int layoutIndex = ssAnimBuffer[slot][3];
        int nextBlockId = (frameIdx < ANIM_GLASS_DATA.length) ? ANIM_GLASS_DATA[frameIdx] : 0;

        if (nextBlockId != 0) {
            if (layoutIndex >= 0 && layoutIndex < layout.length) {
                layout[layoutIndex] = (byte) nextBlockId;
            }
            return;
        }

        if (layoutIndex >= 0 && layoutIndex < layout.length) {
            layout[layoutIndex] = (byte) ssAnimGlassFinalBlock[slot];
        }
        ssAnimBuffer[slot][0] = ANIM_NONE;
        ssAnimGlassFinalBlock[slot] = 0;
    }

    // ---- Exit Sequence (from Obj09_ExitStage / Obj09_Exit2) ----

    private void updateExit() {
        // Accelerate rotation (Obj09_ExitStage: add $40 to v_ssrotate)
        ssRotate += 0x40;

        // At rotation threshold, start concurrent fade (SS_ChkEnd / SS_Finish)
        // ROM: v_ssrotate == $1800 sets v_gamemode = id_Level, triggering SS_Finish
        // which runs WhiteOut_ToWhite alongside ExecuteObjects for 60 frames.
        boolean enteredFinishLoop = false;
        if (ssRotate >= 0x1800 && !exitFadeStarted) {
            exitFadeStarted = true;
            exitFadeTimer = 60; // v_generictimer = 60
            GameServices.fade().startFadeToWhite(null, Integer.MAX_VALUE);
            enteredFinishLoop = true;
        }

        // Count down fade timer (SS_FinLoop: dbf d1,SS_FinLoop)
        if (exitFadeStarted && !enteredFinishLoop) {
            exitFadeTimer = advanceFinishLoopTimer(
                    exitFadeTimer, enteredFinishLoop);
            if (exitFadeTimer <= 0) {
                finished = true;
            }
        }

        // Keep rotating (spin continues even after fade starts)
        ssAngle = (ssAngle + ssRotate) & 0xFFFF;

        // Update camera during exit
        updateCamera();

        // Update animation
        updateAnimCounters();
        updateBgAnimate();
    }

    static int advanceFinishLoopTimer(
            int currentTimer, boolean enteredFinishLoop) {
        return enteredFinishLoop ? currentTimer : currentTimer - 1;
    }

    // ---- Camera (from SS_FixCamera) ----

    private void updateCamera() {
        int sonicPixelX = (int) (sonicPosX >> 16);
        int sonicPixelY = (int) (sonicPosY >> 16);

        // cameraX = sonicX - 0xA0 (but not if negative)
        int newCamX = sonicPixelX - 0xA0;
        if (newCamX >= 0) {
            cameraX = newCamX;
        }

        // cameraY = sonicY - 0x70 (but not if negative)
        int newCamY = sonicPixelY - 0x70;
        if (newCamY >= 0) {
            cameraY = newCamY;
        }
    }

    // ---- Animation ----

    private void updateAnimCounters() {
        // Wall rotation frame from angle
        wallRotFrame = Sonic1SpecialStageBlockType.getWallRotationFrame(ssAngle);

        // Ring animation (ROM ani1): timer counts down, wraps to 7, frame advances 0..3.
        ringAnimTimer--;
        if (ringAnimTimer < 0) {
            ringAnimTimer = 7;
            ringAnimFrame = (ringAnimFrame + 1) & 0x3;
        }

        // Wall VRAM palette animation (SS_AniWallsRings ani0)
        wallVramAnimTimer--;
        if (wallVramAnimTimer < 0) {
            wallVramAnimTimer = 7;
            wallVramAnimFrame = (wallVramAnimFrame - 1) & 0x7;
        }

        // GOAL/UP/DOWN/emerald animation (SS_AniWallsRings ani2): 2-frame cycle, period 8
        ani2Timer--;
        if (ani2Timer < 0) {
            ani2Timer = 7;
            ani2Frame = (ani2Frame + 1) & 0x1;
        }

        // Glass block rotation animation (SS_AniWallsRings ani3): 4-frame cycle, period 5
        ani3Timer--;
        if (ani3Timer < 0) {
            ani3Timer = 4;
            ani3Frame = (ani3Frame + 1) & 0x3;
        }

        // Item collection animations (ring sparkle etc.)
        updateItemAnimations();

        updateSonicAnimation();
        updateSpecialStagePaletteCycle();
    }

    // ---- Art Loading ----

    private void loadPalette() throws IOException {
        // Resolve the Special Stage palette entry in a ROM-revision-safe way.
        // REV00: Sonic=3, Special=10. REV01 shifts both down by 1.
        Rom rom = GameServices.rom().getRom();
        int verifiedAddr = resolveSpecialPaletteAddress(rom);
        if (verifiedAddr != PAL_SS_ADDR) {
            LOGGER.fine("PAL_SS_ADDR mismatch: constant=0x" + Integer.toHexString(PAL_SS_ADDR)
                    + ", palette table says=0x" + Integer.toHexString(verifiedAddr)
                    + " - using verified address");
        }

        byte[] palData = dataLoader.getSSPalette(verifiedAddr);
        ssPaletteCycle1 = dataLoader.getSSPaletteCycle1();
        ssPaletteCycle2 = dataLoader.getSSPaletteCycle2();

        // palData is 128 bytes = 4 palette lines x 16 colors x 2 bytes each (MD format)
        ssPalettes = new Palette[4];
        for (int line = 0; line < 4; line++) {
            ssPalettes[line] = new Palette();
            byte[] lineData = new byte[Palette.PALETTE_SIZE_IN_ROM];
            int srcOffset = line * Palette.PALETTE_SIZE_IN_ROM;
            System.arraycopy(palData, srcOffset, lineData, 0,
                    Math.min(lineData.length, palData.length - srcOffset));
            ssPalettes[line].fromSegaFormat(lineData);
        }
        // GM_Special loads the palette in its instant setup block
        // (`moveq #palid_Special,d0` / PalLoad, sonic.asm:3253), after
        // PaletteWhiteOut has faded the level's last frame; until then the
        // level's palette stays in the shared lines. See update().
        stagePalettesUploaded = false;
    }

    /** {@code palid_Special} load: the stage palette replaces the level's. */
    private void uploadStagePalettes() {
        stagePalettesUploaded = true;
        if (ssPalettes == null || graphicsManager == null) {
            return;
        }
        for (int i = 0; i < ssPalettes.length; i++) {
            graphicsManager.cachePaletteTexture(ssPalettes[i], i);
        }
    }

    private int resolveSpecialPaletteAddress(Rom rom) throws IOException {
        final int disasmSonicPaletteId = 3;
        final int disasmSpecialPaletteId = 10;
        final int specialOffsetFromSonic = disasmSpecialPaletteId - disasmSonicPaletteId;
        int sonicPaletteId = findSonicPaletteId(rom);
        int specialPaletteId = sonicPaletteId + specialOffsetFromSonic;
        return rom.read32BitAddr(PALETTE_TABLE_ADDR + specialPaletteId * 8) & 0x00FFFFFF;
    }

    private int findSonicPaletteId(Rom rom) throws IOException {
        // Scan range that contains Sonic in both REV00 and REV01 tables.
        for (int id = 2; id < 10; id++) {
            int entryAddr = PALETTE_TABLE_ADDR + id * 8;
            int dest = rom.read16BitAddr(entryAddr + 4) & 0xFFFF;
            int countWord = rom.read16BitAddr(entryAddr + 6) & 0xFFFF;
            int byteCount = (countWord + 1) * 4;
            if (dest == 0xFB00 && byteCount == 32) {
                return id;
            }
        }
        return 3;
    }

    private void loadArt() throws IOException {
        int nextBase = SS_PATTERN_BASE;

        // Wall art
        Pattern[] walls = dataLoader.getWallPatterns();
        int wallBase = nextBase;
        for (int i = 0; i < walls.length; i++) {
            graphicsManager.cachePatternTexture(walls[i], nextBase + i);
        }
        nextBase += walls.length;

        // Bumper art
        Pattern[] bumpers = dataLoader.getBumperPatterns();
        int bumperBase = nextBase;
        for (int i = 0; i < bumpers.length; i++) {
            graphicsManager.cachePatternTexture(bumpers[i], nextBase + i);
        }
        nextBase += bumpers.length;

        // GOAL art
        Pattern[] goals = dataLoader.getGoalPatterns();
        int goalBase = nextBase;
        for (int i = 0; i < goals.length; i++) {
            graphicsManager.cachePatternTexture(goals[i], nextBase + i);
        }
        nextBase += goals.length;

        // UP/DOWN art
        Pattern[] upDowns = dataLoader.getUpDownPatterns();
        int upDownBase = nextBase;
        for (int i = 0; i < upDowns.length; i++) {
            graphicsManager.cachePatternTexture(upDowns[i], nextBase + i);
        }
        nextBase += upDowns.length;

        // R block art
        Pattern[] rBlocks = dataLoader.getRBlockPatterns();
        int rBlockBase = nextBase;
        for (int i = 0; i < rBlocks.length; i++) {
            graphicsManager.cachePatternTexture(rBlocks[i], nextBase + i);
        }
        nextBase += rBlocks.length;

        // 1UP art
        Pattern[] oneUps = dataLoader.getOneUpPatterns();
        int oneUpBase = nextBase;
        for (int i = 0; i < oneUps.length; i++) {
            graphicsManager.cachePatternTexture(oneUps[i], nextBase + i);
        }
        nextBase += oneUps.length;

        // Emerald stars art
        Pattern[] emStars = dataLoader.getEmStarsPatterns();
        int emStarsBase = nextBase;
        for (int i = 0; i < emStars.length; i++) {
            graphicsManager.cachePatternTexture(emStars[i], nextBase + i);
        }
        nextBase += emStars.length;

        // Red-white art
        Pattern[] redWhites = dataLoader.getRedWhitePatterns();
        int redWhiteBase = nextBase;
        for (int i = 0; i < redWhites.length; i++) {
            graphicsManager.cachePatternTexture(redWhites[i], nextBase + i);
        }
        nextBase += redWhites.length;

        // Ghost art
        Pattern[] ghosts = dataLoader.getGhostPatterns();
        int ghostBase = nextBase;
        for (int i = 0; i < ghosts.length; i++) {
            graphicsManager.cachePatternTexture(ghosts[i], nextBase + i);
        }
        nextBase += ghosts.length;

        // W block art
        Pattern[] wBlocks = dataLoader.getWBlockPatterns();
        int wBlockBase = nextBase;
        for (int i = 0; i < wBlocks.length; i++) {
            graphicsManager.cachePatternTexture(wBlocks[i], nextBase + i);
        }
        nextBase += wBlocks.length;

        // Glass art
        Pattern[] glasses = dataLoader.getGlassPatterns();
        int glassBase = nextBase;
        for (int i = 0; i < glasses.length; i++) {
            graphicsManager.cachePatternTexture(glasses[i], nextBase + i);
        }
        nextBase += glasses.length;

        // Emerald art
        Pattern[] emeralds = dataLoader.getEmeraldPatterns();
        int emeraldBase = nextBase;
        for (int i = 0; i < emeralds.length; i++) {
            graphicsManager.cachePatternTexture(emeralds[i], nextBase + i);
        }
        nextBase += emeralds.length;

        // Ring art (reuse from normal level ring art already loaded)
        // The ring uses ArtTile_Ring which is already cached by the engine
        int ringBase = nextBase;
        // Load ring art for special stage if not already available
        Pattern[] rings = loadRingPatterns();
        if (rings != null) {
            for (int i = 0; i < rings.length; i++) {
                graphicsManager.cachePatternTexture(rings[i], nextBase + i);
            }
            nextBase += rings.length;
        }

        // Zone number art (1-6)
        int[] zoneBases = new int[6];
        for (int zoneIndex = 0; zoneIndex < 6; zoneIndex++) {
            Pattern[] zonePatterns = dataLoader.getZonePatterns(zoneIndex);
            zoneBases[zoneIndex] = nextBase;
            for (int i = 0; i < zonePatterns.length; i++) {
                graphicsManager.cachePatternTexture(zonePatterns[i], nextBase + i);
            }
            nextBase += zonePatterns.length;
        }

        // BG cloud art
        Pattern[] bgClouds = dataLoader.getBgCloudPatterns();
        bgCloudBase = nextBase;
        for (int i = 0; i < bgClouds.length; i++) {
            graphicsManager.cachePatternTexture(bgClouds[i], nextBase + i);
        }
        nextBase += bgClouds.length;

        // BG fish art
        Pattern[] bgFish = dataLoader.getBgFishPatterns();
        bgFishBase = nextBase;
        for (int i = 0; i < bgFish.length; i++) {
            graphicsManager.cachePatternTexture(bgFish[i], nextBase + i);
        }
        nextBase += bgFish.length;

        // Set pattern bases on renderer
        renderer.setPatternBases(wallBase, bumperBase, goalBase, upDownBase,
                rBlockBase, oneUpBase, emStarsBase, redWhiteBase, ghostBase,
                wBlockBase, glassBase, emeraldBase, ringBase,
                zoneBases[0], zoneBases[1], zoneBases[2],
                zoneBases[3], zoneBases[4], zoneBases[5],
                bgCloudBase, bgFishBase);

        LOGGER.fine("Loaded " + (nextBase - SS_PATTERN_BASE) + " SS art patterns");
    }

    private void loadSonicSprite() throws IOException {
        Rom rom = GameServices.rom().getRom();
        SpriteArtSet sonicArt = new Sonic1PlayerArt(RomByteReader.fromRom(rom)).loadSonic();
        if (sonicArt == null) {
            sonicSpriteRenderer = null;
            sonicSpriteFrame = 0;
            sonicRollScript = null;
            sonicRoll2Script = null;
            return;
        }

        sonicSpriteRenderer = new PlayerSpriteRenderer(sonicArt);
        sonicSpriteRenderer.ensureCached(graphicsManager);
        sonicRollScript = sonicArt.animationSet() != null
                ? sonicArt.animationSet().getScript(Sonic1AnimationIds.ROLL)
                : null;
        sonicRoll2Script = sonicArt.animationSet() != null
                ? sonicArt.animationSet().getScript(Sonic1AnimationIds.ROLL2)
                : null;

        sonicAnimId = Sonic1AnimationIds.ROLL.id();
        sonicAnimFrameIndex = 0;
        sonicAnimFrameTimer = 0;
        sonicSpriteFrame = resolveSpecialStageSonicFrame(sonicArt);
        // No dynamic-art publication here: GM_Special
        // (docs/s1disasm/sonic.asm:3222-3292) never runs Sonic_LoadGfx while
        // setting the stage up. It clears v_levelvariables
        // (sonic.asm:3245), and v_sonframenum -- the "frame already in VRAM"
        // latch Sonic_LoadGfx compares against
        // (docs/s1disasm/_incObj/01 Sonic.asm:2394-2398) -- lives inside that
        // block (docs/s1disasm/_Variables.asm:174, 225, 296). So the SS Sonic
        // object's first main-loop Sonic_LoadGfx always sees a changed frame
        // and sets f_sonframechg (01 Sonic.asm:2408) for VBlank_SpecialStage
        // to DMA (sonic.asm:890-894). Publishing the initial frame here would
        // consume that first change and swallow the ROM's first transfer.
    }

    private int resolveSpecialStageSonicFrame(SpriteArtSet sonicArt) {
        if (sonicArt == null || sonicArt.animationSet() == null) {
            return 0;
        }
        SpriteAnimationScript rollScript = sonicArt.animationSet().getScript(Sonic1AnimationIds.ROLL);
        if (rollScript != null && rollScript.frames() != null && !rollScript.frames().isEmpty()) {
            return rollScript.frames().get(0);
        }
        return 0;
    }

    private void updateSonicAnimation() {
        SpriteAnimationScript rollScript = sonicRollScript;
        if (rollScript == null || rollScript.frames() == null || rollScript.frames().isEmpty()) {
            return;
        }

        int speed = Math.abs(sonicInertia);
        boolean useRoll2 = speed >= SS_ROLL_SPEED_SWITCH
                && sonicRoll2Script != null
                && sonicRoll2Script.frames() != null
                && !sonicRoll2Script.frames().isEmpty();
        int targetAnimId = useRoll2 ? Sonic1AnimationIds.ROLL2.id() : Sonic1AnimationIds.ROLL.id();
        SpriteAnimationScript activeScript = useRoll2 ? sonicRoll2Script : rollScript;

        if (sonicAnimId != targetAnimId) {
            // SAnim_RollJump selects SonAni_Roll/SonAni_Roll2 without changing
            // obAniFrame or obTimeFrame. Both are special six-position
            // animations specifically shaped so the script can change at the
            // $600 speed threshold without restarting the roll cycle
            // (01 Sonic.asm SAnim_RollJump; _anim/Sonic.asm special-animation
            // contract). Restarting here duplicated fr_Roll1 at the threshold
            // and delayed every subsequent S1 DPLC transfer by one frame.
            sonicAnimId = targetAnimId;
        }

        if (sonicAnimFrameTimer > 0) {
            sonicAnimFrameTimer--;
            return;
        }

        int delay = ((0x400 - speed) < 0 ? 0 : (0x400 - speed)) >> 8;
        sonicAnimFrameTimer = delay;

        if (sonicAnimFrameIndex < 0 || sonicAnimFrameIndex >= activeScript.frames().size()) {
            sonicAnimFrameIndex = 0;
        }

        sonicSpriteFrame = activeScript.frames().get(sonicAnimFrameIndex);
        publishSonicDynamicArt();
        advanceSonicFrameIndex(activeScript);
    }

    private void publishSonicDynamicArt() {
        DynamicArtLifecycleService lifecycle =
                GameServices.dynamicArtLifecycleOrNull();
        if (lifecycle == null || !lifecycle.isRunActive()
                || sonicSpriteRenderer == null) {
            return;
        }
        DynamicArtLifecycleService.ArtUpdate update =
                lifecycle.observePlayerDplc(
                        com.openggf.game.GameId.S1,
                        "sonic",
                        sonicSpriteFrame,
                        sonicSpriteRenderer.dplcFrame(sonicSpriteFrame));
        sonicSpriteRenderer.applyRuntimeArtUpdate(sonicSpriteFrame, update);
        lifecycle.completePlayerDplc(
                com.openggf.game.GameId.S1, "sonic", update);
    }

    private void advanceSonicFrameIndex(SpriteAnimationScript script) {
        int next = sonicAnimFrameIndex + 1;
        if (next < script.frames().size()) {
            sonicAnimFrameIndex = next;
            return;
        }

        switch (script.endAction()) {
            case HOLD -> sonicAnimFrameIndex = script.frames().size() - 1;
            case LOOP_BACK -> sonicAnimFrameIndex = resolveLoopBackIndex(script);
            case SWITCH -> {
                int nextAnimId = script.endParam();
                sonicAnimId = nextAnimId;
                sonicAnimFrameIndex = 0;
            }
            case LOOP -> sonicAnimFrameIndex = 0;
            default -> sonicAnimFrameIndex = 0;
        }
    }

    private int resolveLoopBackIndex(SpriteAnimationScript script) {
        int loopBack = script.endParam();
        if (loopBack <= 0) {
            return 0;
        }
        int target = script.frames().size() - loopBack;
        return Math.max(0, target);
    }

    private void updateSpecialStagePaletteCycle() {
        if (ssPalettes == null || ssPaletteCycle1 == null || ssPaletteCycle2 == null) {
            return;
        }

        palSsTime--;
        if (palSsTime >= 0) {
            return;
        }

        int[] entry = SS_BG_STATE_TABLE[palSsNum & 0x1F];
        palSsNum++;
        palSsTime = entry[0] < 0 ? 0x1FF : entry[0];

        // Extract anim and namespace selection fields.
        bgAnimState = entry[1];
        updateFgStateFromAnim(bgAnimState);

        boolean wantPlane5 = entry[2] == 1;
        if (wantPlane5 == bgUsingPlane6) {
            bgUsingPlane6 = !wantPlane5;
            if (bgRenderer != null) {
                bgRenderer.setTilemap(bgUsingPlane6 ? bgPlane6Tilemap : bgPlane5Tilemap);
            }
        }

        int d0 = entry[3] & 0xFF;
        boolean[] touched = new boolean[4];

        if ((d0 & 0x80) == 0) {
            writePaletteBytes(ssPaletteCycle1, d0, 0x4E, 12, touched);
            recacheTouchedPalettes(touched);
            markBackgroundLayersDirtyIfPaletteTouched(touched);
            return;
        }

        int d1 = palSsIndex;
        if (d0 >= 0x8A) {
            d1++;
        }
        int base = d1 * 0x2A;
        int idx = d0 & 0x7F;
        // ROM: bclr #0,d0 / beq.s loc_4A18
        // bclr clears bit 0 and sets Z if bit was already 0.
        // beq skips the write if bit was 0 (even index).
        boolean bit0WasSet = (idx & 1) != 0;
        idx &= ~1;  // bclr #0,d0

        if (bit0WasSet) {
            writePaletteBytes(ssPaletteCycle2, base, 0x6E, 12, touched);
        }

        int src = base + 0x0C;
        int dest = 0x5A;
        if (idx >= 0x0A) {
            idx -= 0x0A;
            dest = 0x7A;
        }
        src += idx * 3;
        writePaletteBytes(ssPaletteCycle2, src, dest, 6, touched);
        recacheTouchedPalettes(touched);
        markBackgroundLayersDirtyIfPaletteTouched(touched);
    }

    private void markBackgroundLayersDirtyIfPaletteTouched(boolean[] touchedLines) {
        if (touchedLines == null) {
            return;
        }
        boolean touched = false;
        for (boolean lineTouched : touchedLines) {
            if (lineTouched) {
                touched = true;
                break;
            }
        }
        if (!touched) {
            return;
        }
        if (bgRenderer != null) {
            bgRenderer.markDirty();
        }
        if (fgRenderer != null) {
            fgRenderer.markDirty();
        }
    }

    /**
     * Mirrors byte_4ABC indirection used by PalCycle_SS:
     * anim values (0,2,4,6,8,10,12) select FG plane namespace and Y scroll.
     */
    private void updateFgStateFromAnim(int animState) {
        int planeIndex;
        int y;
        switch (animState) {
            case 2 -> {
                planeIndex = 1; // Plane 2
                y = 0;
            }
            case 4 -> {
                planeIndex = 1; // Plane 2
                y = 0x100;
            }
            case 6 -> {
                planeIndex = 2; // Plane 3
                y = 0;
            }
            case 8 -> {
                planeIndex = 2; // Plane 3
                y = 0x100;
            }
            case 10 -> {
                planeIndex = 3; // Plane 4
                y = 0;
            }
            case 12 -> {
                planeIndex = 3; // Plane 4
                y = 0x100;
            }
            default -> {
                planeIndex = 0; // Plane 1
                y = 0x100;
            }
        }

        fgAnimPlaneIndex = planeIndex;
        fgYScroll = y;

        if (fgRenderer != null && fgPlaneTilemaps != null && planeIndex >= 0 && planeIndex < fgPlaneTilemaps.length) {
            fgRenderer.setTilemap(fgPlaneTilemaps[planeIndex]);
        }
    }

    private void writePaletteBytes(byte[] source, int sourceOffset, int destByteOffset, int byteCount,
                                   boolean[] touchedLines) {
        if (source == null || byteCount <= 0 || sourceOffset < 0 || sourceOffset + byteCount > source.length) {
            return;
        }
        int colorCount = byteCount / 2;
        int paletteColorIndex = destByteOffset / 2;
        for (int i = 0; i < colorCount; i++) {
            int globalColor = paletteColorIndex + i;
            int line = globalColor / Palette.PALETTE_SIZE;
            int colorInLine = globalColor % Palette.PALETTE_SIZE;
            if (line < 0 || line >= ssPalettes.length) {
                continue;
            }
            ssPalettes[line].getColor(colorInLine).fromSegaFormat(source, sourceOffset + (i * 2));
            if (touchedLines != null && line < touchedLines.length) {
                touchedLines[line] = true;
            }
        }
    }

    private void recacheTouchedPalettes(boolean[] touchedLines) {
        if (touchedLines == null || ssPalettes == null || graphicsManager == null
                || !stagePalettesUploaded) {
            return;
        }
        for (int i = 0; i < touchedLines.length && i < ssPalettes.length; i++) {
            if (touchedLines[i]) {
                graphicsManager.cachePaletteTexture(ssPalettes[i], i);
            }
        }
    }

    /**
     * SS_BGAnimate (sonic.asm lines 3806-3892): Per-scanline H-scroll animation.
     *
     * Two paths based on bgAnimState:
     * - ANIM 0-7 (sine wave): 10 oscillators with independent amplitude/speed/phase
     * - ANIM 8-12 (band scroll): 7 bands with independent 16.16 fixed-point positions
     *
     * Both paths fill bgHScrollData (224 scanlines) via band widths, wrapping at 256 scanlines.
     */
    private void updateBgAnimate() {
        if (bgHScrollData == null) {
            return;
        }

        if (bgAnimState < 8) {
            // Sine wave path
            if (bgAnimState == 0) {
                bgYScroll = 0;
            }
            if (bgAnimState == 6) {
                bgExtraScrollX++;
                bgYScroll++;
            }
            // Update 10 sine oscillators (v_ngfx_buffer)
            // ROM: CalcSine(phase) → sin * amplitude >> 8 → store scroll; phase += speed
            if (bgSineBuffer != null) {
                for (int i = 0; i < 10; i++) {
                    int phase = bgSineBuffer[i * 2 + 1];
                    int sinVal = TrigLookupTable.sinHex(phase & 0xFF);
                    int amplitude = SS_BG_SINE_AMPLITUDES[i];
                    int scroll = (sinVal * amplitude) >> 8;
                    bgSineBuffer[i * 2] = scroll;
                    bgSineBuffer[i * 2 + 1] = phase + SS_BG_SINE_SPEEDS[i];
                }
                fillHScrollFromBands(bgSineBuffer, SS_SINE_BAND_WIDTHS);
            }
        } else {
            // Band scroll path
            if (bgAnimState == 12 && bgBandBuffer != null) {
                bgExtraScrollX--;
                // Update band speeds: first band gets $18000, each subsequent $2000 less
                int speed = 0x18000;
                for (int i = 0; i < 7; i++) {
                    long val = ((long) bgBandBuffer[i * 2] << 16) | (bgBandBuffer[i * 2 + 1] & 0xFFFF);
                    val -= speed;
                    bgBandBuffer[i * 2] = (int) (val >> 16);
                    bgBandBuffer[i * 2 + 1] = (int) (val & 0xFFFF);
                    speed -= 0x2000;
                }
            }
            if (bgBandBuffer != null) {
                fillHScrollFromBands(bgBandBuffer, SS_SCROLL_BAND_WIDTHS);
            }
        }
    }

    /**
     * Common H-scroll fill routine for both sine and band scroll paths.
     * Mirrors the ROM code at loc_4C7E-loc_4CA4.
     *
     * The ROM writes 32-bit entries (FG|BG) to the H-scroll table. We only
     * need the BG portion (low word), which is the per-band scroll value.
     * The FG scroll (high word = -bg3screenposx) is irrelevant to our rendering.
     *
     * @param scrollBuffer Array of [scroll, phase/frac] pairs (stride 2)
     * @param bandWidths   First element = band count - 1, remaining = scanline heights
     */
    private void fillHScrollFromBands(int[] scrollBuffer, int[] bandWidths) {
        // ROM: scanline offset = (-bgscreenposy & $FF) * 4, wrapping at $3FC
        // We use & 0xFF since our array is indexed by scanline, not by 4-byte stride
        int scanline = (-bgYScroll) & 0xFF;
        int bandCount = bandWidths[0] + 1;
        for (int band = 0; band < bandCount; band++) {
            int scroll = scrollBuffer[band * 2]; // BG scroll value (high word for bands, sine value for sine)
            int height = bandWidths[band + 1];
            for (int j = 0; j < height; j++) {
                int idx = scanline & 0xFF;
                if (idx < 224) {
                    bgHScrollData[idx] = scroll;
                }
                scanline++;
            }
        }
    }

    private Pattern[] loadRingPatterns() throws IOException {
        RingSpriteSheet ringSheet = new Sonic1RingArt(GameServices.rom().getRom()).load();
        if (ringSheet == null || ringSheet.getPatterns() == null) {
            return new Pattern[0];
        }
        return ringSheet.getPatterns();
    }

    // ---- Background Renderer ----

    private void initBgRenderer() {
        // Load disassembly-equivalent FG/BG namespaces regardless of headless mode.
        try {
            fgPlaneTilemaps = new byte[4][];
            for (int i = 0; i < 4; i++) {
                fgPlaneTilemaps[i] = dataLoader.getFgPlaneTilemap(i + 1);
            }
            bgPlane5Tilemap = dataLoader.getBgPlane5Tilemap();
            bgPlane6Tilemap = dataLoader.getBgPlane6Tilemap();
        } catch (Exception e) {
            LOGGER.warning("Failed to load SS BG planes: " + e.getMessage());
            fgPlaneTilemaps = null;
            bgPlane5Tilemap = null;
            bgPlane6Tilemap = null;
        }

        // Initialize BG animation state.
        bgUsingPlane6 = true;
        bgAnimState = 0;
        bgYScroll = 0;
        bgExtraScrollX = 0;
        bgSineBuffer = new int[20]; // 10 entries x 2 words [scroll, phase]
        bgBandBuffer = new int[14]; // 7 entries x 2 words [pos_hi, pos_lo]
        bgHScrollData = new int[224];
        updateFgStateFromAnim(0); // byte_4ABC default for anim 0 => plane 1, y=$100

        if (fgPlaneTilemaps == null || bgPlane5Tilemap == null || bgPlane6Tilemap == null) {
            bgRenderer = null;
            fgRenderer = null;
            return;
        }

        if (graphicsManager.isHeadlessMode()) {
            bgRenderer = null;
            fgRenderer = null;
            return;
        }
        try {
            bgRenderer = new Sonic1SpecialStageBackgroundRenderer(graphicsManager);
            bgRenderer.init();
            bgRenderer.setPatternBases(bgCloudBase, bgFishBase);
            bgRenderer.setTilemap(bgPlane6Tilemap); // BG starts on plane 6.
            bgRenderer.setFillTransparentWithBackdrop(true);

            fgRenderer = new Sonic1SpecialStageBackgroundRenderer(graphicsManager);
            fgRenderer.init();
            fgRenderer.setPatternBases(bgCloudBase, bgFishBase);
            fgRenderer.setFillTransparentWithBackdrop(false);
            if (fgPlaneTilemaps != null && fgPlaneTilemaps.length > 0) {
                fgRenderer.setTilemap(fgPlaneTilemaps[0]); // FG starts on plane 1.
            }
            LOGGER.fine("S1 SS background renderers initialized");
        } catch (Exception e) {
            LOGGER.warning("Failed to init S1 SS background renderer, using fallback: " + e.getMessage());
            bgRenderer = null;
            fgRenderer = null;
        }
    }

    // ---- Drawing ----

    public void draw() {
        if (!initialized || renderer == null || layout == null) {
            return;
        }
        graphicsManager.setUseWaterShader(false);
        graphicsManager.setUseSpritePriorityShader(false);
        graphicsManager.setCurrentSpriteHighPriority(false);
        graphicsManager.setWaterEnabled(false);
        graphicsManager.setUseUnderwaterPaletteForBackground(false);

        // Update backdrop color from palette (CRAM[0] equivalent)
        Palette.Color backdrop = getBackdropColor();
        float bdR = 0, bdG = 0, bdB = 0;
        if (backdrop != null) {
            bdR = backdrop.rFloat();
            bdG = backdrop.gFloat();
            bdB = backdrop.bFloat();
        }
        renderer.setBackdropColor(bdR, bdG, bdB);
        if (bgRenderer != null) {
            bgRenderer.setBackdropColor(bdR, bdG, bdB);
        }
        if (fgRenderer != null) {
            fgRenderer.setBackdropColor(bdR, bdG, bdB);
        }

        if (bgRenderer != null && bgRenderer.isInitialized()
                && fgRenderer != null && fgRenderer.isInitialized()) {
            drawWithBgRenderers();
        } else {
            // Fallback: solid color background + maze
            renderer.render(layout, ssAngle, cameraX, cameraY,
                    (int) (sonicPosX >> 16), (int) (sonicPosY >> 16),
                    wallRotFrame, ringAnimFrame, wallVramAnimFrame,
                    ani2Frame, ani3Frame, sonicFacingLeft);
        }

        if (sonicSpriteRenderer != null) {
            int sonicScreenX = Sonic1SpecialStageRenderer.SCREEN_CENTER_OFFSET +
                    (int) (sonicPosX >> 16) - cameraX;
            int sonicScreenY = (int) (sonicPosY >> 16) - cameraY;
            sonicSpriteRenderer.drawFrame(sonicSpriteFrame, sonicScreenX, sonicScreenY, sonicFacingLeft, false);
        }
    }

    private void drawWithBgRenderers() {
        renderLayerToFbo(bgRenderer);
        renderLayerToFbo(fgRenderer);

        // BG pass: per-scanline H-scroll + BG V-scroll.
        graphicsManager.registerCommand(backgroundCommandPool.obtainScroll(
                bgRenderer, bgHScrollData, (float) bgYScroll));

        // FG pass: uniform H-scroll (-v_bg3screenposx) + FG V-scroll from byte_4ABC.
        graphicsManager.registerCommand(backgroundCommandPool.obtainUniform(
                fgRenderer, -bgExtraScrollX, (float) fgYScroll));

        // Maze pass - every frame.
        renderer.renderMaze(layout, ssAngle, cameraX, cameraY, wallRotFrame, ringAnimFrame, wallVramAnimFrame,
                ani2Frame, ani3Frame);
    }

    private void renderLayerToFbo(Sonic1SpecialStageBackgroundRenderer layerRenderer) {
        if (layerRenderer == null || !layerRenderer.needsRedraw()) {
            return;
        }

        layerRenderer.beginFBOProjection();
        graphicsManager.registerCommand(backgroundCommandPool.obtainBegin(layerRenderer));
        graphicsManager.beginPatternBatch();
        layerRenderer.renderTilesToFBO(graphicsManager);
        graphicsManager.flushPatternBatch();
        layerRenderer.endFBOProjection();
        graphicsManager.registerCommand(backgroundCommandPool.obtainEnd(layerRenderer));
    }

    /** Bounded owner for deferred special-stage background commands. */
    static final class BackgroundCommandPool {
        private final ArrayDeque<BackgroundCommand> available = new ArrayDeque<>();
        private Sonic1SpecialStageBackgroundRenderer activeTilePassRenderer;

        BackgroundCommand obtainScroll(Sonic1SpecialStageBackgroundRenderer renderer,
                                       int[] hScroll, float vScroll) {
            BackgroundCommand command = obtain();
            command.configureScroll(renderer, hScroll, vScroll);
            return command;
        }

        BackgroundCommand obtainUniform(Sonic1SpecialStageBackgroundRenderer renderer,
                                        int hScroll, float vScroll) {
            BackgroundCommand command = obtain();
            command.configureUniform(renderer, hScroll, vScroll);
            return command;
        }

        BackgroundCommand obtainBegin(Sonic1SpecialStageBackgroundRenderer renderer) {
            BackgroundCommand command = obtain();
            command.configure(renderer, BackgroundCommand.Kind.BEGIN);
            return command;
        }

        BackgroundCommand obtainEnd(Sonic1SpecialStageBackgroundRenderer renderer) {
            BackgroundCommand command = obtain();
            command.configure(renderer, BackgroundCommand.Kind.END);
            return command;
        }

        private BackgroundCommand obtain() {
            BackgroundCommand command = available.pollLast();
            if (command == null) {
                command = new BackgroundCommand(this);
            }
            command.leased = true;
            return command;
        }

        private void release(BackgroundCommand command) {
            available.addLast(command);
        }

        private void armTilePass(Sonic1SpecialStageBackgroundRenderer renderer) {
            activeTilePassRenderer = renderer;
        }

        private boolean isTilePassArmed(Sonic1SpecialStageBackgroundRenderer renderer) {
            return activeTilePassRenderer == renderer;
        }

        private void disarmTilePass(Sonic1SpecialStageBackgroundRenderer renderer) {
            if (activeTilePassRenderer == renderer) {
                activeTilePassRenderer = null;
            }
        }
    }

    static final class BackgroundCommand implements GLCommandable {
        private enum Kind { SCROLL, UNIFORM, BEGIN, END }

        private final BackgroundCommandPool owner;
        private int[] hScroll;
        private Sonic1SpecialStageBackgroundRenderer renderer;
        private Kind kind;
        private int uniformHScroll;
        private float vScroll;
        private boolean leased;

        private BackgroundCommand(BackgroundCommandPool owner) {
            this.owner = owner;
        }

        private void configureScroll(Sonic1SpecialStageBackgroundRenderer renderer,
                                     int[] source, float vScroll) {
            configure(renderer, Kind.SCROLL);
            int length = Sonic1SpecialStageBackgroundRenderer.SCREEN_HEIGHT;
            if (hScroll == null || hScroll.length < length) {
                hScroll = new int[length];
            }
            int copied = source == null ? 0 : Math.min(source.length, length);
            if (copied > 0) {
                System.arraycopy(source, 0, hScroll, 0, copied);
            }
            if (copied < length) {
                Arrays.fill(hScroll, copied, length, 0);
            }
            this.vScroll = vScroll;
        }

        private void configureUniform(Sonic1SpecialStageBackgroundRenderer renderer,
                                      int hScroll, float vScroll) {
            configure(renderer, Kind.UNIFORM);
            this.uniformHScroll = hScroll;
            this.vScroll = vScroll;
        }

        private void configure(Sonic1SpecialStageBackgroundRenderer renderer, Kind kind) {
            this.renderer = renderer;
            this.kind = kind;
        }

        @Override
        public void execute(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            try {
                if (renderer == null) {
                    return;
                }
                switch (kind) {
                    case SCROLL -> {
                        renderer.setHScrollData(hScroll);
                        renderer.renderWithShader(vScroll);
                    }
                    case UNIFORM -> {
                        renderer.setUniformHScroll(uniformHScroll);
                        renderer.renderWithShader(vScroll);
                    }
                    case BEGIN -> {
                        renderer.beginTilePass(Sonic1SpecialStageRenderer.H32_HEIGHT);
                        owner.armTilePass(renderer);
                    }
                    case END -> {
                        try {
                            renderer.endTilePass();
                        } finally {
                            owner.disarmTilePass(renderer);
                        }
                    }
                }
            } finally {
                discard();
            }
        }

        @Override
        public void unwindAfterFailure(int cameraX, int cameraY, int cameraWidth, int cameraHeight) {
            if (kind == Kind.END && owner.isTilePassArmed(renderer)) {
                execute(cameraX, cameraY, cameraWidth, cameraHeight);
            } else {
                discard();
            }
        }

        @Override
        public void discard() {
            if (!leased) {
                return;
            }
            leased = false;
            renderer = null;
            owner.release(this);
        }

        int hScrollAt(int index) {
            return hScroll[index];
        }

        float vScroll() {
            return vScroll;
        }

        Object hScrollBackingIdentity() {
            return hScroll;
        }
    }

    // ---- Input ----

    public void handleInput(int heldButtons, int pressedButtons) {
        handleInput(heldButtons, pressedButtons, false, false);
    }

    public void handleInput(int heldButtons, int pressedButtons,
                            boolean debugSpeedUp, boolean debugSlowDown) {
        this.heldButtons = heldButtons;
        this.pressedButtons |= pressedButtons;
        this.debugSpeedUp = debugSpeedUp;
        this.debugSlowDown = debugSlowDown;
    }

    public boolean isDebugMode() {
        return debugMode;
    }

    public void toggleDebugMode() {
        if (!debugMode) {
            debugSavedAngle = ssAngle;
            debugSavedRotate = ssRotate;
            ssAngle = 0;
            ssRotate = 0;
            debugMode = true;
        } else {
            ssAngle = debugSavedAngle;
            ssRotate = debugSavedRotate;
            debugMode = false;
        }
        sonicVelX = 0;
        sonicVelY = 0;
        sonicInertia = 0;
        sonicAirborne = true;
        lastCollisionBlockId = 0;
    }

    // ---- State queries ----

    public boolean isFinished() {
        return finished;
    }

    /**
     * Returns whether GM_Special has reached its reveal boundary: the
     * {@code PaletteWhiteOut} half of the hold has elapsed, so {@code bgm_SS}
     * (sonic.asm:3272) and {@code PaletteWhiteIn} may start while the second
     * half still keeps Obj09 frozen (see {@link #SS_STARTUP_HOLD_TICKS}).
     * Mirrors {@code Sonic2SpecialStageManager.isEntryPresentationReady()}.
     */
    public boolean isEntryPresentationReady() {
        return initialized && startupHoldTicksRemaining <= SS_STARTUP_HOLD_TICKS - SS_WHITEOUT_TICKS;
    }

    /**
     * Returns whether {@code PaletteWhiteOut} (the first {@link #SS_WHITEOUT_TICKS}
     * of the hold) is still running: the display shows the level's last frame
     * until GM_Special's instant setup block replaces it (sonic.asm:3238-3291).
     */
    public boolean isEntryFadeToWhiteActive() {
        return initialized && startupHoldTicksRemaining > SS_STARTUP_HOLD_TICKS - SS_WHITEOUT_TICKS;
    }

    /**
     * Compresses the ROM's whole observable pre-physics hold (both fades and
     * the instant setup block) by stepping {@link #update()} until Obj09's
     * first real tick is next, preserving the normal per-tick update path
     * rather than hand-skipping fields. Gameplay no longer uses this -- both
     * startup policies step the hold frame by frame because its first half
     * is the visible fade over the level and its second half is the stage
     * reveal -- so it serves tests that assert on Obj09's first tick. Mirrors
     * {@code Sonic2SpecialStageManager.advanceToEntryPresentation()}.
     */
    public void advanceToEntryPresentation() {
        advanceToEntryPresentation(SS_STARTUP_HOLD_TICKS + 1);
    }

    void advanceToEntryPresentation(int maxUpdates) {
        for (int i = 0; i < maxUpdates && startupHoldTicksRemaining > 0; i++) {
            update();
        }
        if (startupHoldTicksRemaining > 0) {
            throw new IllegalStateException(
                    "Special-stage startup did not reach Obj09's first tick within " + maxUpdates + " updates");
        }
    }

    /**
     * Read-only comparison snapshot for trace replay (multi-stage trace run
     * spec addition #2). Pure read — no state mutation, no caching.
     */
    public Sonic1SpecialStageComparisonState captureComparisonState() {
        return new Sonic1SpecialStageComparisonState(
                sonicPosX, sonicPosY, sonicVelX, sonicVelY, sonicInertia,
                sonicAirborne, sonicFacingLeft, ssAngle, ssRotate, bgAnimState,
                ringsCollected, emeraldCollected, exitTriggered, finished,
                currentStage);
    }

    Sonic1SpecialStageSnapshot captureRewindSnapshot() {
        return new Sonic1SpecialStageSnapshot(
                initialized,
                finished,
                emeraldCollected,
                debugMode,
                startupHoldTicksRemaining,
                objInitPending,
                currentStage,
                ringsCollected,
                ssAngle,
                ssRotate,
                debugSavedAngle,
                debugSavedRotate,
                sonicPosX,
                sonicPosY,
                sonicVelX,
                sonicVelY,
                sonicInertia,
                sonicAirborne,
                sonicFacingLeft,
                cameraX,
                cameraY,
                ghostState,
                upDownCooldown,
                reverseCooldown,
                ringAnimFrame,
                ringAnimTimer,
                wallVramAnimFrame,
                wallVramAnimTimer,
                sonicAnimId,
                sonicAnimFrameIndex,
                sonicAnimFrameTimer,
                palSsTime,
                palSsNum,
                palSsIndex,
                ani2Frame,
                ani2Timer,
                ani3Frame,
                ani3Timer,
                sonicSpriteFrame,
                exitTriggered,
                exitPhase,
                exitTimer,
                exitFadeStarted,
                exitFadeTimer,
                heldButtons,
                pressedButtons,
                debugSpeedUp,
                debugSlowDown,
                bgAnimState,
                bgUsingPlane6,
                fgAnimPlaneIndex,
                fgYScroll,
                bgYScroll,
                bgExtraScrollX,
                layout,
                ssAnimBuffer,
                ssAnimGlassFinalBlock,
                bgSineBuffer,
                bgBandBuffer,
                ssPalettes);
    }

    void restoreRewindSnapshot(Sonic1SpecialStageSnapshot snapshot) {
        initialized = snapshot.initialized;
        finished = snapshot.finished;
        emeraldCollected = snapshot.emeraldCollected;
        debugMode = snapshot.debugMode;
        startupHoldTicksRemaining = snapshot.startupHoldTicksRemaining;
        // The setup-block palette load has happened once the whiteout half of
        // the hold is behind the restored state.
        stagePalettesUploaded =
                startupHoldTicksRemaining < SS_STARTUP_HOLD_TICKS - SS_WHITEOUT_TICKS;
        if (stagePalettesUploaded) {
            uploadStagePalettes();
        }
        objInitPending = snapshot.objInitPending;
        currentStage = snapshot.currentStage;
        ringsCollected = snapshot.ringsCollected;
        ssAngle = snapshot.ssAngle;
        ssRotate = snapshot.ssRotate;
        debugSavedAngle = snapshot.debugSavedAngle;
        debugSavedRotate = snapshot.debugSavedRotate;
        sonicPosX = snapshot.sonicPosX;
        sonicPosY = snapshot.sonicPosY;
        sonicVelX = snapshot.sonicVelX;
        sonicVelY = snapshot.sonicVelY;
        sonicInertia = snapshot.sonicInertia;
        sonicAirborne = snapshot.sonicAirborne;
        sonicFacingLeft = snapshot.sonicFacingLeft;
        cameraX = snapshot.cameraX;
        cameraY = snapshot.cameraY;
        ghostState = snapshot.ghostState;
        upDownCooldown = snapshot.upDownCooldown;
        reverseCooldown = snapshot.reverseCooldown;
        ringAnimFrame = snapshot.ringAnimFrame;
        ringAnimTimer = snapshot.ringAnimTimer;
        wallVramAnimFrame = snapshot.wallVramAnimFrame;
        wallVramAnimTimer = snapshot.wallVramAnimTimer;
        sonicAnimId = snapshot.sonicAnimId;
        sonicAnimFrameIndex = snapshot.sonicAnimFrameIndex;
        sonicAnimFrameTimer = snapshot.sonicAnimFrameTimer;
        palSsTime = snapshot.palSsTime;
        palSsNum = snapshot.palSsNum;
        palSsIndex = snapshot.palSsIndex;
        ani2Frame = snapshot.ani2Frame;
        ani2Timer = snapshot.ani2Timer;
        ani3Frame = snapshot.ani3Frame;
        ani3Timer = snapshot.ani3Timer;
        sonicSpriteFrame = snapshot.sonicSpriteFrame;
        exitTriggered = snapshot.exitTriggered;
        exitPhase = snapshot.exitPhase;
        exitTimer = snapshot.exitTimer;
        exitFadeStarted = snapshot.exitFadeStarted;
        exitFadeTimer = snapshot.exitFadeTimer;
        heldButtons = snapshot.heldButtons;
        pressedButtons = snapshot.pressedButtons;
        debugSpeedUp = snapshot.debugSpeedUp;
        debugSlowDown = snapshot.debugSlowDown;
        bgAnimState = snapshot.bgAnimState;
        bgUsingPlane6 = snapshot.bgUsingPlane6;
        fgAnimPlaneIndex = snapshot.fgAnimPlaneIndex;
        fgYScroll = snapshot.fgYScroll;
        bgYScroll = snapshot.bgYScroll;
        bgExtraScrollX = snapshot.bgExtraScrollX;
        layout = Sonic1SpecialStageSnapshot.cloneByteArray(snapshot.layout);
        ssAnimBuffer = Sonic1SpecialStageSnapshot.cloneIntMatrix(snapshot.ssAnimBuffer);
        ssAnimGlassFinalBlock = Sonic1SpecialStageSnapshot.cloneIntArray(snapshot.ssAnimGlassFinalBlock);
        bgSineBuffer = Sonic1SpecialStageSnapshot.cloneIntArray(snapshot.bgSineBuffer);
        bgBandBuffer = Sonic1SpecialStageSnapshot.cloneIntArray(snapshot.bgBandBuffer);
        ssPalettes = Sonic1SpecialStageSnapshot.clonePalettes(snapshot.ssPalettes);

        reestablishRewindRenderState();
    }

    private void reestablishRewindRenderState() {
        wallRotFrame = Sonic1SpecialStageBlockType.getWallRotationFrame(ssAngle);

        if (bgSineBuffer != null || bgBandBuffer != null) {
            bgHScrollData = new int[224];
            if (bgAnimState < 8 && bgSineBuffer != null) {
                fillHScrollFromBands(bgSineBuffer, SS_SINE_BAND_WIDTHS);
            } else if (bgBandBuffer != null) {
                fillHScrollFromBands(bgBandBuffer, SS_SCROLL_BAND_WIDTHS);
            }
        } else {
            bgHScrollData = null;
        }

        if (graphicsManager != null && ssPalettes != null) {
            boolean[] touched = new boolean[ssPalettes.length];
            java.util.Arrays.fill(touched, true);
            recacheTouchedPalettes(touched);
        }

        if (bgRenderer != null) {
            bgRenderer.setTilemap(bgUsingPlane6 ? bgPlane6Tilemap : bgPlane5Tilemap);
            if (bgHScrollData != null) {
                bgRenderer.setHScrollData(bgHScrollData);
            }
            bgRenderer.markDirty();
        }

        if (fgRenderer != null) {
            if (fgPlaneTilemaps != null && fgAnimPlaneIndex >= 0 && fgAnimPlaneIndex < fgPlaneTilemaps.length) {
                fgRenderer.setTilemap(fgPlaneTilemaps[fgAnimPlaneIndex]);
            }
            fgRenderer.setUniformHScroll(-bgExtraScrollX);
            fgRenderer.markDirty();
        }
    }

    public void reset() {
        GraphicsManager gm = graphicsManager;
        if (bgRenderer != null) {
            bgRenderer.cleanup();
            bgRenderer = null;
        }
        if (fgRenderer != null) {
            fgRenderer.cleanup();
            fgRenderer = null;
        }
        initialized = false;
        finished = false;
        emeraldCollected = false;
        debugMode = false;
        startupHoldTicksRemaining = 0;
        objInitPending = false;
        debugSavedAngle = 0;
        debugSavedRotate = 0;
        ringsCollected = 0;
        currentStage = 0;
        layout = null;
        dataLoader = null;
        renderer = null;
        sonicSpriteRenderer = null;
        sonicSpriteFrame = 0;
        sonicRollScript = null;
        sonicRoll2Script = null;
        ssPalettes = null;
        ssPaletteCycle1 = null;
        ssPaletteCycle2 = null;
        sonicAnimFrameIndex = 0;
        sonicAnimFrameTimer = 0;
        exitFadeStarted = false;
        exitFadeTimer = 0;
        sonicAnimId = Sonic1AnimationIds.ROLL.id();
        wallVramAnimFrame = 0;
        wallVramAnimTimer = 0;
        ssAnimBuffer = null;
        ssAnimGlassFinalBlock = null;
        bgCloudBase = 0;
        bgFishBase = 0;
        bgAnimState = 0;
        fgPlaneTilemaps = null;
        bgPlane5Tilemap = null;
        bgPlane6Tilemap = null;
        bgUsingPlane6 = true;
        fgAnimPlaneIndex = 0;
        fgYScroll = 0;
        bgYScroll = 0;
        bgExtraScrollX = 0;
        bgSineBuffer = null;
        bgBandBuffer = null;
        bgHScrollData = null;
        palSsTime = 0;
        palSsNum = 0;
        palSsIndex = 0;
        ani2Frame = 0;
        ani2Timer = 0;
        ani3Frame = 0;
        ani3Timer = 0;
        heldButtons = 0;
        pressedButtons = 0;
        debugSpeedUp = false;
        debugSlowDown = false;
        if (gm != null) {
            gm.setUseWaterShader(false);
            gm.setUseSpritePriorityShader(false);
            gm.setCurrentSpriteHighPriority(false);
            gm.setWaterEnabled(false);
            gm.setUseUnderwaterPaletteForBackground(false);
        }
        graphicsManager = null;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the SS backdrop color (palette line 0, color 0).
     * On the Mega Drive, CRAM[0] fills all unpainted/transparent areas.
     */
    public Palette.Color getBackdropColor() {
        if (ssPalettes != null && ssPalettes.length > 0) {
            return ssPalettes[0].getColor(0);
        }
        return null;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public boolean isEmeraldCollected() {
        return emeraldCollected;
    }

    public void setEmeraldCollected(boolean collected) {
        this.emeraldCollected = collected;
    }

    public int getRingsCollected() {
        return ringsCollected;
    }

    public void setRingsCollected(int ringsCollected) {
        this.ringsCollected = Math.max(0, ringsCollected);
    }

    public void markFinished() {
        this.finished = true;
    }

    private void playSfx(Sonic1Sfx sfx) {
        if (sfx != null) {
            GameServices.audio().playSfx(sfx.id);
        }
    }

    private void playSfx(GameSound sfx) {
        if (sfx != null) {
            GameServices.audio().playSfx(sfx);
        }
    }

    private void playMusic(GameMusic music) {
        if (music != null) {
            GameServices.audio().playMusic(music);
        }
    }
}
