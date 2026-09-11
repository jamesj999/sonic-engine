package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x4C - Lava Geyser Maker / Lavafall Producer (MZ).
 * <p>
 * Controller object that manages lava geyser eruptions. Uses a 6-routine state machine:
 * <ul>
 *   <li><b>Routine 0 (Main):</b> Init properties, set timer=120, fall through to Wait</li>
 *   <li><b>Routine 2 (Wait):</b> Decrement timer; on expiry check Sonic Y within 0x170 above</li>
 *   <li><b>Routine 4 (ChkType):</b> subtype==0: display+animate; subtype!=0: skip to MakeLava</li>
 *   <li><b>Routine 6 (MakeLava):</b> Spawn 0x4D child, set maker anim, launch parent block</li>
 *   <li><b>Routine 8 (Display):</b> Animate + render until child signals completion</li>
 *   <li><b>Routine A (Delete):</b> subtype==0: delete. subtype!=0: reset to routine 2</li>
 * </ul>
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>subtype 0 = geyser (erupts from below, one-shot)</li>
 *   <li>subtype != 0 = lavafall (falls from ceiling, repeating)</li>
 * </ul>
 * <p>
 * <b>Animation (from Ani_Geyser):</b>
 * <ul>
 *   <li>Anim 0 (.bubble1): {0,1,0,1,4,5,4,5} speed=2, afRoutine</li>
 *   <li>Anim 1 (.bubble2): {2,3} speed=2, afEnd (loop)</li>
 *   <li>Anim 3 (.bubble3): {2,3,0,1,0,1} speed=2, afRoutine</li>
 *   <li>Anim 4 (.blank): {19} speed=0xF, afEnd</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/4C &amp; 4D Lava Geyser Maker.asm
 */
public class Sonic1LavaGeyserMakerObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    // ========================================================================
    // Animation Data (from Ani_Geyser)
    // ========================================================================

    // Animation speeds below are the raw ROM script speed BYTES
    // (docs/s1disasm/_anim/"Lava Geyser.asm"), consumed by the ROM-faithful
    // countdown in updateAnimation(): each frame is shown for (speedByte + 1)
    // AnimateSprite calls, matching docs/s1disasm/_incObj/"sub AnimateSprite.asm".

    /** Anim 0 (.bubble1): speed byte 2, {0,1,0,1,4,5,4,5}, afRoutine. */
    private static final int[] ANIM0_FRAMES = {0, 1, 0, 1, 4, 5, 4, 5};
    private static final int ANIM0_SPEED = 2;
    private static final boolean ANIM0_ADVANCE_ROUTINE = true;

    /** Anim 1 (.bubble2): speed byte 2, {2,3}, afEnd (loop). */
    private static final int[] ANIM1_FRAMES = {2, 3};
    private static final int ANIM1_SPEED = 2;
    private static final boolean ANIM1_ADVANCE_ROUTINE = false;

    /** Anim 3 (.bubble3): speed byte 2, {2,3,0,1,0,1}, afRoutine. */
    private static final int[] ANIM3_FRAMES = {2, 3, 0, 1, 0, 1};
    private static final int ANIM3_SPEED = 2;
    private static final boolean ANIM3_ADVANCE_ROUTINE = true;

    /** Anim 4 (.blank): speed byte $F, {19}, afEnd. */
    private static final int[] ANIM4_FRAMES = {19};
    private static final int ANIM4_SPEED = 15;
    private static final boolean ANIM4_ADVANCE_ROUTINE = false;

    /** Wait timer reload value: move.w #120,gmake_time(a0). */
    private static final int TIMER_RELOAD = 120;

    /** Sonic Y proximity check range: subi.w #$170,d1. */
    private static final int PROXIMITY_RANGE = 0x170;

    /** Debug color for geyser maker (dark orange). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(200, 100, 0);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Current routine (0=Main/Wait, 2=Wait, 4=ChkType, 6=MakeLava, 8=Display, A=Delete). */
    private int routine;

    /** Timer countdown (gmake_timer / objoff_32). */
    private int timer;

    /** Timer reload value (gmake_time / objoff_34). */
    private int timerReload;

    /** Object subtype (0 = geyser, != 0 = lavafall). */
    private int subtype;

    /** Reference to parent push block (gmake_parent / objoff_3C), may be null. */
    private Sonic1PushBlockObjectInstance parentBlock;

    /** Current animation index. */
    private int currentAnim;

    /** Frame index within current animation. */
    private int animFrameIndex;

    /** Animation timer. */
    private int animTimer;

    /** Display frame set by animation. */
    private int displayFrame;

    /** Whether animation has completed (afRoutine triggered). */
    private boolean animRoutineTriggered;

    /** Whether we are visible (have a sprite to show). */
    private boolean visible;

    // ========================================================================
    // Constructors
    // ========================================================================

    public Sonic1LavaGeyserMakerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "GeyserMaker");
        this.subtype = spawn.subtype() & 0xFF;
        this.timerReload = TIMER_RELOAD;
        // GMake_Main: Routine 0 initializes then falls through to Wait
        this.timer = 0; // Will be checked as word; initial=0, first decrement wraps negative -> reload
        this.routine = 2; // Start in Wait state (routine 0 falls through)
        this.visible = false;
    }

    /**
     * Creates a GeyserMaker with a parent push block reference.
     * Used when spawned by PushB_LoadLava.
     */
    public Sonic1LavaGeyserMakerObjectInstance(int x, int y, int subtype,
                                                Sonic1PushBlockObjectInstance parentBlock) {
        super(new ObjectSpawn(x, y, 0x4C, subtype, 0, false, 0), "GeyserMaker");
        this.subtype = subtype;
        this.timerReload = TIMER_RELOAD;
        // ROM parity: a freshly spawned maker has a cleared SST slot, so
        // gmake_timer (objoff_32) = 0. GMake_Main (rt0) sets gmake_time = 120 and
        // falls through to GMake_Wait, whose first subq.w #1,gmake_timer underflows
        // 0 -> -1 and runs the proximity check on the maker's first executed frame
        // -- identical to the ObjPosLoad-created maker below. (A previous timer=1
        // seed here was compensating the old AnimateSprite off-by-one in
        // updateAnimation(); now that the animation count is ROM-exact the seed
        // must be 0 too, or the geyser/push-block eruption fires one frame late.)
        this.timer = 0;
        this.routine = 2;
        this.visible = false;
        this.parentBlock = parentBlock;
    }

    // ========================================================================
    // Package-private: called by LavaGeyser to signal anim change
    // ========================================================================

    /**
     * Called by the child LavaGeyser when it finishes rising/falling.
     * Sets the maker's animation, which drives routine advancement.
     */
    void setCurrentAnim(int anim) {
        if (anim != currentAnim) {
            currentAnim = anim;
            animFrameIndex = 0;
            animTimer = 0;
            animRoutineTriggered = false;
        }
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case 2 -> updateWait(player);
            case 4 -> updateChkType();
            case 6 -> updateMakeLava();
            case 8 -> updateDisplay();
            case 10 -> updateDelete(); // 0xA
            default -> { }
        }
    }

    /**
     * GMake_Wait (Routine 2): Decrement timer, check Sonic proximity.
     */
    private void updateWait(AbstractPlayableSprite player) {
        // subq.w #1,gmake_timer(a0) / bpl.s .cancel
        timer--;
        if (timer >= 0) {
            checkOutOfRange();
            return;
        }

        // Timer expired: reset and check Sonic
        // move.w gmake_time(a0),gmake_timer(a0)
        timer = timerReload;

        // Check Sonic Y position
        if (player == null) {
            checkOutOfRange();
            return;
        }
        // move.w (v_player+obY).w,d0
        int sonicY = player.getCentreY();
        // move.w obY(a0),d1
        int myY = spawn.y();
        // cmp.w d1,d0 / bhs.s .cancel — if sonicY >= myY, cancel (Sonic below or at geyser)
        if (sonicY >= myY) {
            checkOutOfRange();
            return;
        }
        // subi.w #$170,d1 / cmp.w d1,d0 / blo.s .cancel — if sonicY < myY-0x170, too far above
        if (sonicY < myY - PROXIMITY_RANGE) {
            checkOutOfRange();
            return;
        }

        // Sonic in range: advance to ChkType
        routine = 4;
    }

    /**
     * GMake_ChkType (Routine 4): Check subtype to decide behavior.
     * <p>
     * For subtype 0 (geyser): falls through to GMake_Display WITHOUT changing routine.
     * Stays at routine 4 while animating bubble1 (anim 0). When anim 0's afRoutine
     * triggers, routine advances 4→6 (MakeLava).
     * <p>
     * For subtype != 0 (lavafall): advances routine to 6 and returns immediately.
     */
    private void updateChkType() {
        // tst.b obSubtype(a0) / beq.s GMake_Display
        if (subtype == 0) {
            // Geyser: show bubble animation at routine 4, afRoutine will advance to 6
            visible = true;
            displayAndAnimate();
        } else {
            // Lavafall: addq.b #2,obRoutine(a0) / rts
            routine = 6;
            checkOutOfRange();
        }
    }

    /**
     * GMake_MakeLava (Routine 6): Spawn the LavaGeyser child.
     */
    private void updateMakeLava() {
        routine = 8;

        // Spawn LavaGeyser (0x4D)
        if (services().objectManager() != null) {
            final int mySlot = getSlotIndex();
            spawnFreeChild(() -> {
                ObjectSpawn geyserSpawn = new ObjectSpawn(
                        spawn.x(), spawn.y(),
                        0x4D, subtype, 0, false, 0);
                Sonic1LavaGeyserObjectInstance geyser = new Sonic1LavaGeyserObjectInstance(
                        geyserSpawn, Sonic1LavaGeyserObjectInstance.Role.HEAD,
                        null, this, false);
                // ROM: FindNextFreeObj allocates slot after maker
                ObjectLifetimeOps.assignFindNextFreeChildSlot(services().objectManager(), geyser, mySlot);
                return geyser;
            });
        }

        // Set maker animation
        if (subtype == 0) {
            // .isgeyser: set anim 1, launch parent block
            currentAnim = 1;
            animFrameIndex = 0;
            animTimer = 0;
            animRoutineTriggered = false;
            visible = true;

            // movea.l gmake_parent(a0),a1 / bset #1,obStatus(a1) / move.w #-$580,obVelY(a1)
            if (parentBlock != null) {
                parentBlock.applyLavaGeyserLaunch(-0x580);
            }
        } else {
            // Lavafall: anim 4 (blank)
            currentAnim = 4;
            animFrameIndex = 0;
            animTimer = 0;
            animRoutineTriggered = false;
            visible = true;
        }

        // ROM: both branches of GMake_MakeLava end with bra.s GMake_Display, which
        // falls straight into routine 8's own AnimateSprite call in the SAME dispatch
        // pass the new obAnim was just written (docs/s1disasm/_incObj/"4C, 4D MZ Lava
        // Geyser and Maker.asm":64-87). Without this, displayFrame keeps whatever the
        // PREVIOUS cycle's ending animation (.bubble3) last wrote until the next
        // update() call, so a repeating lavafall/geyser maker renders one frame of the
        // prior eruption's ending bubble at the instant the new eruption becomes
        // visible.
        updateAnimation();
    }

    /**
     * GMake_Display (Routine 8): Animate and display.
     * Animation's afRoutine flag advances routine by +2 (8→10 = Delete).
     */
    private void updateDisplay() {
        displayAndAnimate();
    }

    /**
     * Shared display logic for routines 4 (ChkType/geyser) and 8 (Display).
     * Handles out-of-range check, animation, and afRoutine advancement.
     * <p>
     * Using routine += 2 matches the ROM's AnimateSprite afRoutine behavior:
     * at routine 4, afRoutine → 6 (MakeLava). At routine 8, afRoutine → 10 (Delete).
     */
    private void displayAndAnimate() {
        checkOutOfRange();
        if (isDestroyed()) {
            return;
        }

        updateAnimation();

        if (animRoutineTriggered) {
            // addq.b #2,obRoutine(a0) via afRoutine
            routine += 2;
            animRoutineTriggered = false;
        }
    }

    /**
     * GMake_Delete (Routine A): Reset or delete.
     */
    private void updateDelete() {
        // move.b #0,obAnim(a0) / move.b #2,obRoutine(a0)
        currentAnim = 0;
        animFrameIndex = 0;
        animTimer = 0;
        visible = false;

        // tst.b obSubtype(a0) / beq.w DeleteObject
        if (subtype == 0) {
            // One-shot geyser: delete
            setDestroyed(true);
        } else {
            // Repeating lavafall: go back to Wait
            routine = 2;
            timer = timerReload;
            checkOutOfRange();
        }
    }

    private void checkOutOfRange() {
        if (!isInRangeAt(spawn.x())) {
            setDestroyed(true);
        }
    }

    // ========================================================================
    // Animation
    // ========================================================================

    /**
     * ROM-faithful re-implementation of {@code AnimateSprite}
     * (docs/s1disasm/_incObj/"sub AnimateSprite.asm").
     * <p>
     * {@code animTimer} is {@code obTimeFrame}, {@code animFrameIndex} is
     * {@code obAniFrame}; {@link #setCurrentAnim(int)} resets both to 0 (the
     * {@code obAnim != obPrevAni} branch). Each call does
     * {@code subq.b #1,obTimeFrame; bpl Anim_Wait} — so a frame is held for
     * {@code (speedByte + 1)} calls, and the terminator ({@code afRoutine} /
     * {@code afEnd}) is only read on the call <em>after</em> the last frame has
     * been shown for its full duration. The previous engine version advanced the
     * index one call early on the first frame (holding frame 0 for only
     * {@code speedByte} calls), which made every {@code afRoutine} fire one frame
     * early. For the MZ lavafall maker that under-counted the {@code .bubble3}
     * routine-advance by one frame per eruption, drifting the eruption schedule
     * earlier each cycle.
     */
    private void updateAnimation() {
        int[] frames;
        int speed;
        boolean advancesRoutine;

        switch (currentAnim) {
            case 0 -> { frames = ANIM0_FRAMES; speed = ANIM0_SPEED; advancesRoutine = ANIM0_ADVANCE_ROUTINE; }
            case 1 -> { frames = ANIM1_FRAMES; speed = ANIM1_SPEED; advancesRoutine = ANIM1_ADVANCE_ROUTINE; }
            case 3 -> { frames = ANIM3_FRAMES; speed = ANIM3_SPEED; advancesRoutine = ANIM3_ADVANCE_ROUTINE; }
            case 4 -> { frames = ANIM4_FRAMES; speed = ANIM4_SPEED; advancesRoutine = ANIM4_ADVANCE_ROUTINE; }
            default -> { return; }
        }

        // Anim_Run: subq.b #1,obTimeFrame / bpl.s Anim_Wait
        if (--animTimer >= 0) {
            return; // time remains: keep the current frame
        }

        // Anim_LoadNextFrame: reload duration, read next script entry
        animTimer = speed; // move.b (a1),obTimeFrame
        if (animFrameIndex >= frames.length) {
            // Script terminator reached (the byte past the last frame ID).
            if (advancesRoutine) {
                // Anim_End_FC (afRoutine): addq.b #2,obRoutine; obFrame/obAniFrame untouched.
                animRoutineTriggered = true;
                return;
            }
            // Anim_End_FF (afEnd): restart from the first frame and display it.
            animFrameIndex = 0;
        }
        displayFrame = frames[animFrameIndex];
        animFrameIndex++;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visible) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.MZ_LAVA_GEYSER);
        if (renderer == null) return;

        renderer.drawFrameIndex(displayFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(1);
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawCross(spawn.x(), spawn.y(), 8, 0.8f, 0.4f, 0.0f);
        ctx.drawWorldLabel(spawn.x(), spawn.y(), -1,
                String.format("GeyserMaker r=%d sub=%d t=%d anim=%d",
                        routine, subtype, timer, currentAnim),
                DEBUG_COLOR);
    }
}
