package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x5A -- Platforms moving in circles (SLZ).
 * <p>
 * A top-solid platform that moves in a circle driven by two oscillation
 * values (v_oscillate+$22 and v_oscillate+$26). The subtype byte controls
 * the direction and phase of the circular motion.
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bits 2-3: Movement type (0 = type00, 1 = type04)</li>
 *   <li>Bit 0: Negate both X and Y offsets (180-degree phase shift)</li>
 *   <li>Bit 1: Negate X and exchange X/Y (90-degree rotation)</li>
 * </ul>
 * <p>
 * <b>Type difference:</b>
 * <ul>
 *   <li>type00: Standard circular motion</li>
 *   <li>type04: Same as type00 but with X offset negated (mirror about Y axis)</li>
 * </ul>
 * <p>
 * <b>Disassembly reference:</b> docs/s1disasm/_incObj/5A SLZ Circling Platform.asm
 */
public class Sonic1CirclingPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRewindRecreatable {

    // From disassembly: move.b #$18,obActWid(a0)
    private static final int HALF_WIDTH = 0x18;

    // Platform surface height. ROM Circ_Action continued-ride seats the rider via
    // MvSonicOnPtfm2 (obY-9, docs/s1disasm/_incObj/sub MvSonicOnPtfm.asm:18-41),
    // so the riding surface half-height is 9 (matching Obj 18). The first-landing
    // PlatformObject detect (obY-8) is recovered via getTopLandingSnapAdjustment.
    private static final int HALF_HEIGHT = 9;

    // From disassembly: move.b #4,obPriority(a0)
    private static final int PRIORITY = 4;

    // Oscillation centre offset: subi.b #$50,d1 / subi.b #$50,d2
    private static final int OSC_CENTRE = 0x50;

    // Oscillation data offsets (v_oscillate+$22 -> getByte(0x20), v_oscillate+$26 -> getByte(0x24))
    // v_oscillate has a 2-byte control prefix, so OscillationManager offset = ROM offset - 2
    private static final int OSC_X_OFFSET = 0x20; // v_oscillate+$22 = oscillator 8 value high byte
    private static final int OSC_Y_OFFSET = 0x24; // v_oscillate+$26 = oscillator 9 value high byte

    // Saved original positions (circ_origX = objoff_32, circ_origY = objoff_30)
    private int origX;
    private int origY;

    // Current dynamic position
    private int x;
    private int y;

    // Subtype configuration
    private boolean negateBoth;   // Bit 0: negate both offsets
    private boolean rotated;      // Bit 1: negate X, exchange X/Y
    private boolean type04;       // Bits 2-3: type04 additionally negates X

    public Sonic1CirclingPlatformObjectInstance(ObjectSpawn spawn) {
        super(spawn, "CirclingPlatform");

        int subtype = spawn.subtype() & 0xFF;

        // Disasm: move.w obX(a0),circ_origX(a0) / move.w obY(a0),circ_origY(a0)
        this.origX = spawn.x();
        this.origY = spawn.y();

        // Subtype bit decoding:
        // andi.w #$C,d0 / lsr.w #1,d0 → type index (0 or 2 in jump table = type00 or type04)
        int typeIndex = (subtype & 0x0C) >> 1;
        this.type04 = (typeIndex >= 2);

        // btst #0,obSubtype(a0) — negate both d1 and d2
        this.negateBoth = (subtype & 0x01) != 0;

        // btst #1,obSubtype(a0) — negate d1 and exchange d1/d2 (90-degree rotation)
        this.rotated = (subtype & 0x02) != 0;

        // Set initial position
        this.x = origX;
        this.y = origY;
        updatePosition();
        updateDynamicSpawn(x, y);
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
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }
        updatePosition();
        updateDynamicSpawn(x, y);
    }

    /**
     * Calculates the platform position from oscillation values.
     * <p>
     * From disassembly Circ_Types:
     * <pre>
     *   move.b  (v_oscillate+$22).w,d1   ; X oscillation
     *   subi.b  #$50,d1                  ; centre around $50
     *   ext.w   d1
     *   move.b  (v_oscillate+$26).w,d2   ; Y oscillation
     *   subi.b  #$50,d2
     *   ext.w   d2
     *
     *   ; Bit 0: negate both
     *   btst    #0,obSubtype(a0)
     *   beq.s   .noshift
     *   neg.w   d1
     *   neg.w   d2
     *
     *   ; Bit 1: negate d1, exchange d1/d2
     *   btst    #1,obSubtype(a0)
     *   beq.s   .norotate
     *   neg.w   d1
     *   exg     d1,d2
     *
     *   ; Type04 additionally negates d1 before adding to origX
     *   ; (.type04: neg.w d1 at line 106)
     *
     *   add.w   circ_origX(a0),d1
     *   move.w  d1,obX(a0)
     *   add.w   circ_origY(a0),d2
     *   move.w  d2,obY(a0)
     * </pre>
     */
    private void updatePosition() {
        // Read oscillation values as unsigned bytes, subtract centre, sign-extend
        // move.b (v_oscillate+$22).w,d1 / subi.b #$50,d1 / ext.w d1
        int d1 = (byte) ((OscillationManager.getByte(OSC_X_OFFSET) & 0xFF) - OSC_CENTRE);
        // move.b (v_oscillate+$26).w,d2 / subi.b #$50,d2 / ext.w d2
        int d2 = (byte) ((OscillationManager.getByte(OSC_Y_OFFSET) & 0xFF) - OSC_CENTRE);

        // btst #0,obSubtype(a0) — negate both
        if (negateBoth) {
            d1 = -d1;
            d2 = -d2;
        }

        // btst #1,obSubtype(a0) — negate d1, exchange
        if (rotated) {
            d1 = -d1;
            int tmp = d1;
            d1 = d2;
            d2 = tmp;
        }

        // type04: neg.w d1 (additional X negation)
        if (type04) {
            d1 = -d1;
        }

        // add.w circ_origX(a0),d1 / move.w d1,obX(a0)
        x = origX + d1;
        // add.w circ_origY(a0),d2 / move.w d2,obY(a0)
        y = origY + d2;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SLZ_CIRCLING_PLATFORM);
        if (renderer == null) return;

        // Render platform at current position (single frame: frame 0)
        renderer.drawFrameIndex(0, x, y, false, false);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- SolidObjectProvider ----

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HALF_HEIGHT, HALF_HEIGHT);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM Circ_Platform (routine 2) passes obActWid (= $18) directly as
        // PlatformObject's d1 (docs/s1disasm/_incObj/5A SLZ Circling
        // Platform.asm:31-34), so the full collision half-width is the standable
        // landing width and must NOT receive the generic SolidObject "-$B"
        // narrowing. Without this, the top-landing X window shrank from $18 to
        // $0D, so a player arcing onto the platform near its inner edge was not
        // caught until he had moved several pixels further in — landing a few
        // frames late (SLZ2 f3332: a rolling-jump Sonic lands on the circling
        // platform; ROM catches him at relX=8 / f3332, the engine's narrowed
        // window rejected him until relX=12 / f3335). Same as Obj 18
        // (Sonic1PlatformObjectInstance), whose Plat_Solid also passes obActWid
        // straight to PlatformObject.
        return true;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding() {
        // ROM PlatformObject gates the land band with an UNSIGNED cmpi.w #-16,d0 /
        // blo, rejecting the exact-touch case d0=0 (standable band is d0 in
        // [-16,-1], strict penetration). Same as Obj 18; without it the engine
        // caught a frame early (SLZ2 f3331 vs ROM f3332).
        return true;
    }

    @Override
    public int getTopLandingSnapAdjustment(PlayableEntity player, int solidTopYRadius) {
        // PlatformObject builds its first-landing entry surface from obY-8, while
        // continued riding (Circ_Action -> MvSonicOnPtfm2) uses obY-9. With
        // HALF_HEIGHT=9 modelling the obY-9 ride surface, this -1 recovers the
        // obY-8 first-landing detect/snap. Same as Obj 18.
        return -1;
    }

    @Override
    public boolean carriesAirborneRiderAfterExitPlatform() {
        // ROM Circ_Action (routine 4, docs/s1disasm/_incObj/5A SLZ Circling
        // Platform.asm:38-45) calls ExitPlatform first -- which clears the rider's
        // on-object bit when he passes the pre-move X edge (docs/s1disasm/_incObj/
        // sub ExitPlatform.asm:24-29) -- then runs Circ_Types to move the platform,
        // then UNCONDITIONALLY calls MvSonicOnPtfm2 (asm:45). MvSonicOnPtfm2 does
        // not test the on-object bit, so on the exit frame it still pulls the
        // rider's y_pos to platformY-9-obHeight using the platform's post-move
        // position and carries the platform's X delta (docs/s1disasm/_incObj/sub
        // MvSonicOnPtfm.asm:18-41). This is structurally identical to Obj18
        // Plat_Action2 / Obj52 MBlock_StandOn / Obj59 Elev_Action, which all opt in.
        //
        // Without this, when the descending circling platform's edge slides past
        // the rider on the exit frame, the engine drops the ride before the final
        // seat, leaving the rider 1px high (SLZ2 f3353: platformY post-move 0x013C,
        // ROM seats centre 0x013C-9-0x13 = 0x0120; the engine kept the pre-exit
        // 0x011F). The carry is applied in
        // ObjectSolidContactController.processInlineRidingObject's exit branch.
        return true;
    }

    @Override
    public boolean usesPreUpdatePositionForSolidContact(PlayableEntity player) {
        // ROM Circ_Platform (routine 2) runs PlatformObject before Circ_Types
        // moves the platform (docs/s1disasm/_incObj/5A SLZ Circling Platform.asm:
        // 28-34), so first-landing detection sees the pre-move surface — the same
        // ExitPlatform-before-move order as Obj 18 (18 Platforms.asm:54-67). This
        // is the 5th Obj 18 landing-family override; it fixes the continued-ride
        // seat phase on the circling platform's descent (SLZ2 ride-seat frames).
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Standing state is managed by ObjectManager
    }

    // ---- Persistence ----

    /**
     * ROM {@code CirclingPlatform} ends with
     * {@code out_of_range.w DeleteObject,circ_origX(a0)}
     * (docs/s1disasm/_incObj/5A SLZ Circling Platform.asm:11), so the delete
     * window is measured against the stored circle ORIGIN (circ_origX =
     * objoff_32), never the live swung-out {@code obX}.
     * <p>
     * The origin anchor previously only fed {@code isPersistent()}, which is a
     * keep-alive veto layered ON TOP of the shared out_of_range unload — and that
     * unload still used the default live-X reference, so the platform survived
     * whenever EITHER anchor was in window. A platform circling back toward the
     * camera (X offset down to {@code -$50}) therefore chunk-aligned one $80
     * block nearer than its origin and was retained after ROM had deleted it,
     * occupying an SST slot ROM had freed (SLZ1 f3015: the engine holds slot 115
     * with the origin 768 px out of the 640 px window while ROM holds nothing).
     * Same shape as Obj 6A's {@code saw_origX} reference.
     */
    @Override
    public int getOutOfRangeReferenceX() {
        return origX;
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw origin anchor point (yellow cross)
        ctx.drawLine(origX - 4, origY, origX + 4, origY, 1.0f, 1.0f, 0.0f);
        ctx.drawLine(origX, origY - 4, origX, origY + 4, 1.0f, 1.0f, 0.0f);

        // Draw line from origin to current position (cyan)
        ctx.drawLine(origX, origY, x, y, 0.0f, 1.0f, 1.0f);

        // Draw collision box (green for solid platform)
        int left = x - HALF_WIDTH;
        int right = x + HALF_WIDTH;
        int top = y - HALF_HEIGHT;
        int bottom = y + HALF_HEIGHT;
        ctx.drawLine(left, top, right, top, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, top, right, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(right, bottom, left, bottom, 0.0f, 1.0f, 0.0f);
        ctx.drawLine(left, bottom, left, top, 0.0f, 1.0f, 0.0f);

        // Draw platform center (red cross)
        ctx.drawLine(x - 4, y, x + 4, y, 1.0f, 0.0f, 0.0f);
        ctx.drawLine(x, y - 4, x, y + 4, 1.0f, 0.0f, 0.0f);
    }


}
