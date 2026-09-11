package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 6C - Vanishing platforms (SBZ).
 * <p>
 * Platforms that periodically vanish and reappear, synchronized to the global
 * frame counter. Each platform's subtype controls its timing phase and period:
 * <ul>
 *   <li>Low nybble: timer interval = (lowNybble + 1) * $80</li>
 *   <li>High nybble: phase offset multiplied by (interval + $80)</li>
 * </ul>
 * <p>
 * State machine (routines):
 * <ol>
 *   <li>Routine 6 (idle): Check frame counter trigger. When triggered, go to routine 2.</li>
 *   <li>Routine 2 (vanish/appear): Count down timer. When expired, toggle between vanish
 *       and appear animations. Platform is solid when visible (frames 0/1), not solid
 *       when vanished (frames 2/3). Uses PlatformObject for initial landing, ExitPlatform
 *       + MvSonicOnPtfm2 for continued riding.</li>
 * </ol>
 * <p>
 * Animation:
 * <ul>
 *   <li>Anim 0 (vanish): frames 0,1,2,3 at speed 7, afBack to index 1</li>
 *   <li>Anim 1 (appear): frames 3,2,1,0 at speed 7, afBack to index 1</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/6C SBZ Vanishing Platforms.asm
 */
public class Sonic1VanishingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.b #$10,obActWid(a0)
    private static final int HALF_WIDTH = 0x10;

    // PlatformObject/MvSonicOnPtfm2 ground half-height. ROM VanP routine 2/4
    // (docs/s1disasm/_incObj/6C SBZ Vanishing Platforms.asm:82-93) lands the
    // first contact via PlatformObject and continued riding via MvSonicOnPtfm2,
    // the same top-solid platform family as Obj18 (Sonic1PlatformObjectInstance).
    // MvSonicOnPtfm2 uses subi.w #9 (docs/s1disasm/_incObj/sub MvSonicOnPtfm.asm:18-41),
    // so the ground half-height is 9; PlatformObject's obY-8 first-landing detection
    // is modelled by getTopLandingSnapAdjustment()=-1 below.
    private static final int HALF_HEIGHT = 9;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // Animation speed: dc.b 7 (first byte of each animation sequence)
    private static final int ANIM_FRAME_DURATION = 7;

    // Animation sequences from Ani_Van:
    // Anim 0 (.vanish): dc.b 7, 0, 1, 2, 3, afBack, 1 -> frames 0,1,2,3 then hold last (frame 3 = gone)
    // Anim 1 (.appear): dc.b 7, 3, 2, 1, 0, afBack, 1 -> frames 3,2,1,0 then hold last (frame 0 = whole)
    // afBack is relative: sub.b param,obFrame → go back 1 from end = last frame
    private static final int[][] ANIM_SEQUENCES = {
            {0, 1, 2, 3},  // anim 0: vanish
            {3, 2, 1, 0},  // anim 1: appear
    };
    // afBack parameter from disassembly (relative go-back from end of sequence)
    private static final int ANIM_LOOP_BACK = 1;

    // ---- Timing state ----

    // vanp_timer (objoff_30): countdown timer
    private int timer;
    // vanp_timelen (objoff_32): base timer length
    private int timerLength;
    // objoff_36: phase offset for frame counter trigger
    private int phaseOffset;
    // objoff_38: mask for frame counter trigger
    private int phaseMask;

    // ---- State machine ----

    // Current routine: 2 = vanish/appear, 6 = idle
    private int routine;

    // ---- Animation state ----

    // obAnim: current animation index (0 = vanish, 1 = appear)
    private int animIndex;
    // Index within current animation sequence
    private int animFrameIndex;
    // Countdown timer for animation frame display
    private int animFrameTimer;
    // obFrame: current mapping frame (0-3)
    private int currentFrame;

    // Whether player is currently standing on this platform
    private boolean playerStanding;


    public Sonic1VanishingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "VanishingPlatform");
        

        // VanP_Main: decode subtype
        int subtype = spawn.subtype() & 0xFF;

        // Low nybble: timer interval = (lowNybble + 1) * $80
        // andi.w #$F,d0 / addq.w #1,d0 / lsl.w #7,d0
        int lowNybble = subtype & 0x0F;
        int interval = (lowNybble + 1) << 7; // * $80

        // vanp_timer = interval - 1, vanp_timelen = interval - 1
        this.timerLength = interval - 1;
        this.timer = this.timerLength;

        // High nybble: phase offset calculation
        // andi.w #$F0,d0 / addi.w #$80,d1 / mulu.w d1,d0 / lsr.l #8,d0
        int highNybble = subtype & 0xF0;
        int d1 = interval + 0x80;
        long phaseCalc = ((long) highNybble * d1) & 0xFFFFFFFFL;
        this.phaseOffset = (int) (phaseCalc >>> 8) & 0xFFFF;

        // phaseMask = d1 - 1
        this.phaseMask = (d1 - 1) & 0xFFFF;

        // Start in routine 6 (idle, checking frame counter)
        // addq.b #6,obRoutine(a0) sets routine to 6
        this.routine = 6;

        // Animation starts at anim 0 (vanish), frame 0 (whole)
        this.animIndex = 0;
        this.animFrameIndex = 0;
        this.animFrameTimer = ANIM_FRAME_DURATION;
        this.currentFrame = 0;
        this.playerStanding = false;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        SolidCheckpointBatch batch = checkpointAll();
        playerStanding = hasStandingContact(batch);

        if (routine == 6) {
            updateIdle(vIntRunCount, player);
        } else {
            // Routine 2 or 4 (VanP_Vanish / VanP_Appear - same code)
            updateVanishAppear(player);
        }
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    /**
     * Routine 6 (loc_16068): Check frame counter trigger.
     * <p>
     * (v_framecount - objoff_36) AND objoff_38:
     * - If non-zero: animate and display
     * - If zero: transition to routine 2 (vanish)
     */
    private void updateIdle(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM VanP_Sync (docs/s1disasm/_incObj/6C SBZ Vanishing Platforms.asm:51-56):
        //   move.w (v_framecount).w,d0 / sub.w objoff_36,d0 / and.w objoff_38,d0
        // The routine 6->2 transition gate reads v_framecount (= Level_frame_counter),
        // NOT the V-int run count passed to update(). That V-int clock runs
        // ahead of and out of phase with v_framecount, so the platform entered its
        // vanish/appear cycle at the wrong absolute frame (SBZ1 trace: VBla 0x8500
        // multiple at vfc 2170 instead of ROM's v_framecount 0x800 multiple at vfc
        // 2048 — a 122-frame phase error), leaving the @0BB0,0648 platform half a cycle
        // out of phase (engine vanished while ROM solid) so Sonic fell through at f2268.
        // LevelManager.frameCounter is the engine's canonical Level_frame_counter;
        // objects execute pre-increment, so getFrameCounter()+1 is this frame's
        // v_framecount (same source as the SBZ Electrocuter vfc gate). The v3.5 trace's
        // object_near obj_frame confirmed the target platform is solid (obj_frame 0) at
        // f2268 in both ROM and the engine after this fix.
        LevelManager levelManager = services().levelManager();
        int vFrameCount = levelManager != null ? levelManager.getFrameCounter() : vIntRunCount;
        int check = (vFrameCount - phaseOffset) & phaseMask;
        if (check == 0) {
            // subq.b #4,obRoutine(a0) -> routine 6-4 = 2
            routine = 2;
            updateVanishAppear(player);
        } else {
            // Animate and display
            updateAnimation();
        }
    }

    /**
     * Routine 2/4 (VanP_Vanish / VanP_Appear): Shared timer-based vanish/appear logic.
     * <p>
     * Decrements timer. When expired:
     * - If vanishing (obAnim == 0): reset timer to 127
     * - If appearing (obAnim != 0): reset timer to vanp_timelen
     * - Toggle bit 0 of obAnim (switch between vanish and appear)
     * <p>
     * Then animate. If platform is solid (frame bit 1 clear), apply platform physics.
     * If not solid (frame bit 1 set) and player is standing, detach player.
     */
    private void updateVanishAppear(AbstractPlayableSprite player) {
        // subq.w #1,vanp_timer(a0) / bpl.s .wait
        timer--;
        if (timer < 0) {
            // Timer expired
            if (animIndex == 0) {
                // Vanishing: move.w #127,vanp_timer(a0)
                timer = 127;
            } else {
                // Appearing: move.w vanp_timelen(a0),vanp_timer(a0)
                timer = timerLength;
            }
            // bchg #0,obAnim(a0) - toggle animation
            animIndex ^= 1;
            // Reset animation playback for new sequence
            animFrameIndex = 0;
            animFrameTimer = ANIM_FRAME_DURATION;
            currentFrame = ANIM_SEQUENCES[animIndex][0];
        }

        // Animate
        updateAnimation();

        // btst #1,obFrame(a0) - check if platform has vanished
        boolean vanished = (currentFrame & 2) != 0;

        if (vanished) {
            // .notsolid: if player is standing, detach them
            if (playerStanding) {
                var objectManager = services().objectManager();
                if (objectManager != null && player != null) {
                    // bclr #3,obStatus(a1) - clear player standing-on-object
                    // bclr #3,obStatus(a0) - clear object standing flag
                    objectManager.clearRidingObject(player);
                }
                playerStanding = false;
                // move.b #2,obRoutine(a0) - ensure routine is 2
                routine = 2;
            }
        }
        // When not vanished, solid contact is handled by ObjectManager through
        // SolidObjectProvider/SolidObjectListener interfaces.
    }

    /**
     * AnimateSprite: advance animation frame based on timer.
     * Uses afBack end action: when sequence completes, loop back to ANIM_LOOP_INDEX.
     */
    private void updateAnimation() {
        animFrameTimer--;
        if (animFrameTimer < 0) {
            animFrameTimer = ANIM_FRAME_DURATION;
            animFrameIndex++;

            int[] sequence = ANIM_SEQUENCES[animIndex];
            if (animFrameIndex >= sequence.length) {
                // afBack: go back ANIM_LOOP_BACK from end of sequence (holds last frame)
                animFrameIndex = sequence.length - ANIM_LOOP_BACK;
            }
            currentFrame = sequence[animFrameIndex];
        }
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_VANISHING_PLATFORM);
        if (renderer == null) return;

        renderer.drawFrameIndex(currentFrame, getX(), getY(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- Solid object ----

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    // --- PlatformObject landing-surface family (ROM VanP routine 2 PlatformObject /
    // routine 4 MvSonicOnPtfm2, 6C SBZ Vanishing Platforms.asm:82-93). Same family as
    // Sonic1PlatformObjectInstance (Obj18); a vanishing platform that re-appears must
    // catch a falling player on the exact ROM frame and at the exact ROM surface Y. ---

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // PlatformObject receives obActWid directly as d1 (sub PlatformObject.asm:17),
        // so the collision half-width is the standable width with no SolidObject +$B
        // narrowing.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // PlatformObject gates the land band with an UNSIGNED blo #-16 (rejects the
        // exact-touch d0==0 case), matching the Obj18 family. Combined with the obY-8
        // detection offset this lands on the same frame as ROM.
        return true;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // PlatformObject builds its first-landing detection surface from obY-8
        // (sub PlatformObject.asm:37-38 subq.w #8,d0). HALF_HEIGHT is 9 (the
        // MvSonicOnPtfm2 ride surface), so -1 shifts the first-landing detect to obY-8.
        return -1;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform is only solid when visible (frame bit 1 clear = frames 0 and 1)
        if (isDestroyed()) {
            return false;
        }
        return (currentFrame & 2) == 0;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing state is driven via manual checkpoints in update().
    }

    // ---- Debug ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw solid collision box (only when platform is solid)
        boolean vanished = (currentFrame & 2) != 0;
        if (!vanished) {
            ctx.drawRect(getX(), getY(), HALF_WIDTH, HALF_HEIGHT, 0.3f, 0.6f, 1.0f);
        } else {
            // Draw faint outline when vanished
            ctx.drawRect(getX(), getY(), HALF_WIDTH, HALF_HEIGHT, 0.2f, 0.2f, 0.4f);
        }

        // Label with state info
        String animName = animIndex == 0 ? "vanish" : "appear";
        String stateLabel = routine == 6 ? "idle" : animName;
        ctx.drawWorldLabel(getX(), getY() - HALF_HEIGHT - 8, 0,
                String.format("VP r%d %s f%d t%d sub=%02X",
                        routine, stateLabel, currentFrame, timer, spawn.subtype() & 0xFF),
                DebugColor.CYAN);
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // RememberState: check if object is on screen
        return !isDestroyed() && isOnScreen();
    }

    // ---- Helpers ----

}
