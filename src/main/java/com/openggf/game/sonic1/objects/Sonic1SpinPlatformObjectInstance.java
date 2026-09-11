package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Sonic 1 Object 0x69 - SBZ Spinning Platforms and Trapdoors.
 * <p>
 * Two variants controlled by subtype bit 7:
 * <ul>
 *   <li>Subtype &lt; 0x80: Trapdoor - large solid platform that opens/closes on a timer.
 *       Uses Map_Trap mappings, ArtTile_SBZ_Trap_Door palette line 2.
 *       Solid dimensions: d1=$4B, d2=$C, d3=$D.
 *       Timer period = (subtype &amp; $F) * $3C frames.</li>
 *   <li>Subtype &gt;= 0x80: Spinning platform - small spinning disc, solid only when flat (frame 0).
 *       Uses Map_Spin mappings, ArtTile_SBZ_Spinning_Platform palette line 0.
 *       Solid dimensions: d1=$1B, d2=$7, d3=$8.
 *       Timer period = (subtype &amp; $F) * 6 frames.
 *       Frame counter trigger mask = (((subtype &amp; $70) + $10) &lt;&lt; 2) - 1.</li>
 * </ul>
 * Both variants release Sonic when they become non-solid (frame != 0), matching
 * the disassembly's explicit btst #3,obStatus / bclr / clr.b obSolid logic.
 * <p>
 * Animation reference: docs/s1disasm/_anim/SBZ Spinning Platforms.asm (Ani_Spin)
 * <p>
 * ROM reference: docs/s1disasm/_incObj/69 SBZ Spinning Platforms.asm
 */
public class Sonic1SpinPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // ---- Trapdoor solid params (Spin_Trapdoor) ----
    // move.w #$4B,d1 / move.w #$C,d2 / move.w d2,d3 / addq.w #1,d3
    private static final SolidObjectParams TRAPDOOR_SOLID = new SolidObjectParams(0x4B, 0xC, 0xD);

    // ---- Spinner solid params (Spin_Spinner) ----
    // move.w #$1B,d1 / move.w #7,d2 / move.w d2,d3 / addq.w #1,d3
    private static final SolidObjectParams SPINNER_SOLID = new SolidObjectParams(0x1B, 0x7, 0x8);

    // Spin_Main: move.b #256/2,obActWid(a0) on the shipped FixBugs = 0 branch
    // (69 SBZ Spinning Platforms and Trapdoors.asm:28-31). Kept by the trapdoor.
    private static final int TRAPDOOR_ACT_WIDTH = 0x80;

    // Spin_Main spinner path: move.b #32/2,obActWid(a0) (:49), overwriting the above.
    private static final int SPINNER_ACT_WIDTH = 0x10;

    // ---- Trapdoor animation (Ani_Spin entries 0 and 1) ----
    // .trapopen:  dc.b 3, 0, 1, 2, afBack, 1  (plays 0->1->2, holds on frame 2 = fully open)
    // .trapclose: dc.b 3, 2, 1, 0, afBack, 1  (plays 2->1->0, holds on frame 0 = closed/solid)
    private static final int TRAPDOOR_FRAME_DURATION = 3;
    private static final int[] TRAP_OPEN_SEQUENCE = {0, 1, 2};
    private static final int[] TRAP_CLOSE_SEQUENCE = {2, 1, 0};

    // ---- Spinner animation (Ani_Spin entries 2 and 3, identical sequences) ----
    // .spin1/.spin2: dc.b 1, 0,1,2,3,4, $43,$42,$41,$40, $61,$62,$63,$64, $23,$22,$21, 0, afBack,1
    //
    // Raw frame byte bit encoding (from AnimateSprite Anim_Next):
    //   bits 0-4: mapping frame index (0-4)
    //   bit 5:    hFlip flag (XOR'd into obRender bit 0 via rol.b #3)
    //   bit 6:    vFlip flag (XOR'd into obRender bit 1 via rol.b #3)
    //
    // Full rotation cycle:
    //   0-4:          normal orientation (top-down spin)
    //   $43-$40:      vFlip (upside-down rotation, bit 6 set)
    //   $61-$64:      hFlip+vFlip (both flips, bits 5+6 set)
    //   $23-$21, 0:   hFlip only (bit 5 set), then back to normal frame 0
    private static final int SPINNER_FRAME_DURATION = 1;
    private static final int[] SPINNER_RAW_SEQUENCE = {
            0, 1, 2, 3, 4,
            0x43, 0x42, 0x41, 0x40,
            0x61, 0x62, 0x63, 0x64,
            0x23, 0x22, 0x21, 0
    };

    private boolean isSpinner;

    // Timer state
    // spin_timer = objoff_30: countdown until next animation toggle
    // spin_timelen = objoff_32: reload value for timer
    private int spinTimer;
    private int spinTimelen;

    // Animation state
    // obAnim: current animation index
    //   Trapdoor: toggles 0 (.trapopen) / 1 (.trapclose) via bchg #0
    //   Spinner:  toggles 2 (.spin1) / 3 (.spin2) via bchg #0 (sequences are identical)
    private int animationId;
    private int animFrameIndex;
    private int animTimer;
    private int mappingFrame;

    // Spinner-specific: frame counter mask for periodic triggering
    // objoff_36: mask applied to v_framecount to gate trigger
    private int frameCounterMask;
    // objoff_34: trigger flag (set when frame counter matches, cleared on timer expiry)
    private boolean spinnerTriggered;

    // Track whether object is currently solid (frame 0 displayed)
    private boolean solidActive;

    public Sonic1SpinPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "SpinPlatform");
        int subtype = spawn.subtype() & 0xFF;
        this.isSpinner = (subtype & 0x80) != 0;

        if (isSpinner) {
            // Spin_Main spinner path:
            // move.b #$10,obActWid(a0)
            // move.b #2,obAnim(a0)
            this.animationId = 2;

            // andi.w #$F,d0 / mulu.w #6,d0 -> timer = lowNyb * 6
            int lowNyb = subtype & 0xF;
            int timerVal = lowNyb * 6;
            this.spinTimer = timerVal;
            this.spinTimelen = timerVal;

            // andi.w #$70,d1 / addi.w #$10,d1 / lsl.w #2,d1 / subq.w #1,d1
            int bits70 = subtype & 0x70;
            int maskVal = ((bits70 + 0x10) << 2) - 1;
            this.frameCounterMask = maskVal;

            this.spinnerTriggered = false;
        } else {
            // Spin_Main trapdoor path:
            // move.b #$80,obActWid(a0)
            // andi.w #$F,d0 / mulu.w #$3C,d0 -> timelen = lowNyb * 60 frames
            int lowNyb = subtype & 0xF;
            this.spinTimelen = lowNyb * 0x3C;
            // spin_timer starts at 0 (RAM zeroed); immediately expires on first update
            this.spinTimer = 0;
            this.frameCounterMask = 0;
            this.spinnerTriggered = false;

            // obAnim starts at 0 (RAM zeroed, .trapopen sequence)
            this.animationId = 0;
        }

        this.animFrameIndex = 0;
        this.animTimer = 0;
        this.mappingFrame = 0;
        this.solidActive = true;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isSpinner) {
            updateSpinner(vIntRunCount, player);
        } else {
            updateTrapdoor(player);
        }
    }

    // ========================================
    // Trapdoor logic (Spin_Trapdoor, routine 2)
    // ========================================

    private void updateTrapdoor(AbstractPlayableSprite player) {
        // subq.w #1,spin_timer(a0)
        spinTimer--;
        if (spinTimer < 0) {
            // bpl.s .animate (not taken)
            // move.w spin_timelen(a0),spin_timer(a0)
            spinTimer = spinTimelen;
            // bchg #0,obAnim(a0)
            animationId ^= 1;
            resetAnimation();
            // tst.b obRender(a0) / bpl.s .animate - play sound only if on-screen
            if (isOnScreen()) {
                // move.w #sfx_Door,d0 / jsr (QueueSound2).l
                services().playSfx(Sonic1Sfx.DOOR.id);
            }
        }

        animateTrapdoor();

        // tst.b obFrame(a0) / bne.s .notsolid
        boolean wasSolid = solidActive;
        solidActive = (mappingFrame == 0);

        // .notsolid: if player is standing on the trapdoor, detach them
        if (!solidActive && wasSolid) {
            detachRidingPlayer(player);
        }
    }

    /**
     * Animate trapdoor using Ani_Spin entries 0/1.
     * <p>
     * .trapopen:  dc.b 3, 0, 1, 2, afBack, 1 (hold on frame 2)
     * .trapclose: dc.b 3, 2, 1, 0, afBack, 1 (hold on frame 0)
     */
    private void animateTrapdoor() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }
        animTimer = TRAPDOOR_FRAME_DURATION;

        int[] sequence = (animationId & 1) == 0 ? TRAP_OPEN_SEQUENCE : TRAP_CLOSE_SEQUENCE;
        if (animFrameIndex >= sequence.length) {
            // afBack,1: sub 1 from obAniFrame and re-read -> holds on last frame
            animFrameIndex = sequence.length - 1;
        }
        mappingFrame = sequence[animFrameIndex];
        animFrameIndex++;
    }

    // ========================================
    // Spinner logic (Spin_Spinner, routine 4)
    // ========================================

    private void updateSpinner(int vIntRunCount, AbstractPlayableSprite player) {
        // ROM Spin_Spinner reads (v_framecount).w (the Level frame counter), NOT
        // the VBla clock ObjectManager passes into update(); the VBla gate runs
        // the spin cycle ~14 frames out of phase (docs/s1disasm/_incObj/69 SBZ
        // Spinning Platforms and Trapdoors.asm:96). Route through the trace-seeded
        // Level frame counter (same pattern as the Electrocuter f1925 fix).
        int vfc = resolveVFrameCounter(vIntRunCount);
        // move.w (v_framecount).w,d0 / and.w objoff_36(a0),d0 / bne.s .delay
        if ((vfc & frameCounterMask) == 0) {
            // move.b #1,objoff_34(a0)
            spinnerTriggered = true;
        }

        // tst.b objoff_34(a0) / beq.s .animate
        if (spinnerTriggered) {
            // subq.w #1,spin_timer(a0)
            spinTimer--;
            if (spinTimer < 0) {
                // bpl.s .animate (not taken)
                // move.w spin_timelen(a0),spin_timer(a0)
                spinTimer = spinTimelen;
                // clr.b objoff_34(a0)
                spinnerTriggered = false;
                // bchg #0,obAnim(a0)
                animationId ^= 1;
                resetAnimation();
            }
        }

        animateSpinner();

        // tst.b obFrame(a0) / bne.s .notsolid2
        boolean wasSolid = solidActive;
        solidActive = (mappingFrame == 0);

        // .notsolid2: if player is standing, detach them
        if (!solidActive && wasSolid) {
            detachRidingPlayer(player);
        }
    }

    /**
     * Animate spinner using Ani_Spin entries 2/3 (both identical sequences).
     * <p>
     * .spin1/.spin2: dc.b 1, 0,1,2,3,4, $43,$42,$41,$40, $61,$62,$63,$64, $23,$22,$21, 0, afBack,1
     */
    private void animateSpinner() {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }
        animTimer = SPINNER_FRAME_DURATION;

        if (animFrameIndex >= SPINNER_RAW_SEQUENCE.length) {
            // afBack,1: holds on last frame (which is 0 = flat)
            animFrameIndex = SPINNER_RAW_SEQUENCE.length - 1;
        }
        int rawFrame = SPINNER_RAW_SEQUENCE[animFrameIndex];
        // andi.b #$1F,d0 -> extract mapping frame index (bits 0-4)
        mappingFrame = rawFrame & 0x1F;
        animFrameIndex++;
    }

    /**
     * Resolves the ROM {@code v_framecount} (Level frame counter). The engine's
     * canonical Level_frame_counter is {@code LevelManager.frameCounter} (the
     * trace-seeded gameplay_frame_counter); it has not yet been incremented for
     * the current frame at object-execution time, so +1 gives the current
     * frame's v_framecount. Mirrors
     * {@code Sonic1ElectrocuterObjectInstance.resolveVFrameCounter}.
     */
    private int resolveVFrameCounter(int vIntRunCount) {
        LevelManager lm = services().levelManager();
        if (lm != null) {
            return lm.getFrameCounter();
        }
        return vIntRunCount;
    }

    /**
     * Reset animation state, matching AnimateSprite's detection of obAnim != obPrevAni.
     */
    private void resetAnimation() {
        animFrameIndex = 0;
        animTimer = 0;
    }

    /**
     * Detach player if riding this object, matching the disassembly's .notsolid sections:
     * <pre>
     *   btst    #3,obStatus(a0)     ; is Sonic standing on the object?
     *   beq.s   .display            ; if not, branch
     *   lea     (v_player).w,a1
     *   bclr    #3,obStatus(a1)     ; clear player standing-on-object bit
     *   bclr    #3,obStatus(a0)     ; clear object standing-on bit
     *   clr.b   obSolid(a0)         ; clear solid contact byte
     * </pre>
     * The engine's SolidContacts system also handles detach via isSolidFor() returning false,
     * but explicit detach ensures immediate single-frame accuracy.
     */
    private void detachRidingPlayer(AbstractPlayableSprite player) {
        ObjectManager objectManager = services().objectManager();
        if (objectManager != null && objectManager.isAnyPlayerRiding(this)) {
            objectManager.clearRidingObject(player);
            // ROM .notsolid2 does bclr #3,obStatus(a1) + clr.b obSolid(a0): the
            // platform fully releases the rider when it stops being solid
            // (docs/s1disasm/_incObj/69 SBZ Spinning Platforms and Trapdoors.asm:124-130).
            // It clears the on-object/standing bits but does NOT set Status_InAir —
            // ROM's player only becomes airborne on the NEXT frame, when his own
            // grounded floor check (which ran before the platform this frame, since
            // Obj01 precedes the platform in ExecuteObjects) finds no floor. So
            // release support (clearRidingObject + drop the SolidObject latch on THIS
            // instance so finalizeInlinePlayer clears Status_OnObj) but let the
            // player's next-frame grounded collision set air, rather than forcing it
            // the same frame (which goes airborne one frame early — SBZ1 f5531).
            if (player.getLatchedSolidObjectInstance() == this) {
                player.setLatchedSolidObjectId(0);
            }
        }
    }

    // ========================================
    // Rendering
    // ========================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        String key = isSpinner ? ObjectArtKeys.SBZ_SPINNING_PLATFORM : ObjectArtKeys.SBZ_TRAP_DOOR;
        PatternSpriteRenderer renderer = renderManager.getRenderer(key);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        if (isSpinner) {
            // Extract flip flags from the current raw frame byte.
            // AnimateSprite Anim_Next: rol.b #3,d1 rotates bits 5-6 to bits 0-1:
            //   bit 5 -> obRender bit 0 (hFlip)
            //   bit 6 -> obRender bit 1 (vFlip)
            int rawFrame = getCurrentRawFrame();
            boolean hFlip = (rawFrame & 0x20) != 0; // bit 5 -> hFlip
            boolean vFlip = (rawFrame & 0x40) != 0; // bit 6 -> vFlip
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, vFlip);
        } else {
            // Trapdoor: ori.b #4,obRender(a0) sets coordinate mode only;
            // animation frames (0,1,2) have no flip bits set, so always render unflipped.
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
        }
    }

    /**
     * Returns the raw frame byte for the currently displayed spinner animation frame.
     * Used to extract flip flags for rendering.
     */
    private int getCurrentRawFrame() {
        // animFrameIndex points to the NEXT frame to read; the current displayed
        // frame is at animFrameIndex - 1 (since it was post-incremented in animateSpinner).
        if (animFrameIndex > 0 && animFrameIndex <= SPINNER_RAW_SEQUENCE.length) {
            return SPINNER_RAW_SEQUENCE[animFrameIndex - 1];
        }
        return 0;
    }

    // ========================================
    // Debug rendering
    // ========================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        SolidObjectParams params = isSpinner ? SPINNER_SOLID : TRAPDOOR_SOLID;
        float r, g, b;
        if (solidActive) {
            r = 0.2f; g = 0.8f; b = 0.2f; // green = solid
        } else {
            r = 0.8f; g = 0.2f; b = 0.2f; // red = non-solid
        }
        ctx.drawRect(getX(), getY(), params.halfWidth(), params.groundHalfHeight(), r, g, b);

        String variant = isSpinner ? "Spin" : "Trap";
        String state = solidActive ? "SOLID" : "OPEN";
        String label = String.format("0x69 %s f%d %s t%d", variant, mappingFrame, state, spinTimer);
        ctx.drawWorldLabel(getX(), getY() - params.groundHalfHeight() - 8, 0, label, DebugColor.CYAN);
    }

    // ========================================
    // Solid object interface
    // ========================================

    @Override
    public SolidObjectParams getSolidParams() {
        return isSpinner ? SPINNER_SOLID : TRAPDOOR_SOLID;
    }

    /**
     * Obj69's ROM {@code obActWid}, which differs by variant.
     *
     * <p>{@code Spin_Main} writes {@code move.b #256/2,obActWid(a0)} = 128 for
     * every Obj69 and only then overwrites it with {@code #32/2} = 16 on the
     * spinning-platform path
     * (docs/s1disasm/_incObj/69 SBZ Spinning Platforms and Trapdoors.asm:28-31,49).
     * The trapdoor keeps the 128. That first write sits on a {@code FixBugs}
     * conditional: with {@code FixBugs = 1} the ROM would write {@code #128/2}
     * = 64, the listing calling 128 "way too big, resulting in screen wrapping
     * issues". The engine takes the {@code FixBugs = 0} branch
     * (docs/s1disasm/sonic.asm:20) because that is what the shipped ROM does and
     * what every trace records.
     *
     * <p>The variant split is the subtype's bit 7, the same ROM byte the class
     * already reads for {@link #isSpinner} -- {@code tst.b obSubtype(a0) /
     * bpl.s Spin_Trapdoor} at {@code :46}.
     *
     * <p>Supplied here rather than at {@link #getBalanceWidthPixels()} because
     * both ROM consumers want the byte: {@code BuildSprites}' horizontal cull
     * (docs/s1disasm/_inc/BuildSprites.asm:49-58) and {@code Sonic_Balance}
     * (docs/s1disasm/_incObj/01 Sonic.asm:423). Obj69 is full-solid, so the
     * balance accessor inherits this one. The collision widths are authored
     * separately -- {@code #128/2+sonic_solid_width} = {@code $4B} for the
     * trapdoor at {@code :85} -- and are unchanged.
     *
     * <p>Without the override the inherited 16 made the player balance anywhere
     * more than 12px from the centre of a 128-wide trapdoor, where the ROM's
     * window of {@code d1 < 4 || d1 >= 252} balances essentially nowhere on it.
     */
    @Override
    public int getOnScreenHalfWidth() {
        return isSpinner ? SPINNER_ACT_WIDTH : TRAPDOOR_ACT_WIDTH;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return solidActive;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // No extra per-contact behavior needed.
        // The SolidObject call in the disassembly handles standard push/stand/ceiling;
        // the engine's SolidContacts system replicates this automatically.
    }

    // ========================================
    // Persistence
    // ========================================

    @Override
    public boolean isPersistent() {
        // Spin_Trapdoor/.display and Spin_Spinner both end in RememberState
        // (docs/s1disasm/_incObj/69 SBZ Spinning Platforms and Trapdoors.asm:80,
        // 92,121), whose off-screen test is the out_of_range macro
        // (docs/s1disasm/Macros.asm:273-289): chunk-align obX and (v_screenposx
        // -128), and delete when the unsigned distance exceeds 128+320+192 (=640).
        // The platform/trapdoor is stationary so getX() (= spawn obX) is the live
        // obX the macro reads. The previous symmetric isOnScreenX(160) approximated
        // this and kept objects ~160px left of the viewport that the ROM has
        // already deleted off the left edge (SBZ2: the x=0x05F0 trapdoor lingered
        // in slot 83 after the camera reached 0x0688, drifting OST occupancy).
        return !isDestroyed() && isInRange();
    }
}
