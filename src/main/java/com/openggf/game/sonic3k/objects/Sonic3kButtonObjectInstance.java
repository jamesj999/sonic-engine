package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugColor;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectConstructionContext;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Button (Object 0x33) - Floor-mounted trigger button.
 * <p>
 * Activates when a player stands on it, setting a bit in the
 * {@link Sonic3kLevelTriggerManager Level_trigger_array} which other objects monitor.
 * Uses zone-specific art and mappings.
 * <p>
 * ROM reference: sonic3k.asm Obj_Button (lines 60721-60895)
 * <ul>
 *   <li>SolidObjectFull collision (default): d1=$1B, d2=4, d3=5</li>
 *   <li>SolidObjectTop collision (subtype bit 5): d1=$10, d3=6</li>
 *   <li>Frame 0: unpressed, Frame 1: pressed</li>
 *   <li>Sound: sfx_Switch ($5B) on press transition</li>
 *   <li>Init: y_pos += 4 (addq.w #4,y_pos)</li>
 * </ul>
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>Bits 0-3 (0x0F): Trigger index into Level_trigger_array (0-15)</li>
 *   <li>Bit 4 (0x10): Toggle mode — trigger stays set when player steps off</li>
 *   <li>Bit 5 (0x20): Top-solid only (SolidObjectTop instead of SolidObjectFull)</li>
 *   <li>Bit 6 (0x40): Use bit 7 instead of bit 0 in the trigger byte</li>
 * </ul>
 */
public class Sonic3kButtonObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code Obj_Button} is installed from the S3K object pointer table at
     * {@code $0002C518} (table read from the user-supplied ROM; the
     * label is defined at docs/skdisasm/sonic3k.asm:60726).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0002}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0002;
    }


    // ROM: move.b #$10,width_pixels(a0)
    private static final int WIDTH_PIXELS = 0x10;

    // ROM: addq.w #4,y_pos(a0) — Y offset applied during init
    private static final int INIT_Y_OFFSET = 4;

    // ROM: move.w #$200,priority(a0) → bucket 4
    private static final int PRIORITY = 4;

    // SolidObjectFull: d1=$1B, d2=4, d3=5
    private static final SolidObjectParams SOLID_PARAMS_FULL =
            SolidObjectParams.of(0x1B, 4, 5);

    // SolidObjectTop: d1=$10, d3=6
    private static final SolidObjectParams SOLID_PARAMS_TOP =
            SolidObjectParams.of(0x10, 6, 6);

    // Frame indices
    private static final int FRAME_UNPRESSED = 0;
    private static final int FRAME_PRESSED = 1;

    // Subtype-derived state
    private int triggerIndex;   // subtype & 0x0F
    private int triggerBit;     // 0 or 7
    private boolean toggleMode; // subtype bit 4
    private boolean topSolid;   // subtype bit 5

    // Zone-resolved art key
    private final String artKey;

    // Adjusted Y position (after init offset)
    private int adjustedY;

    // Standing detection via SolidObjectListener callback
    private boolean contactStanding;

    // Current mapping frame
    private int mappingFrame = FRAME_UNPRESSED;

    public Sonic3kButtonObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Button");

        int subtype = spawn.subtype();

        // ROM: andi.w #$F,d0
        this.triggerIndex = subtype & 0x0F;

        // ROM: btst #6,subtype(a0) / beq.s + / moveq #7,d3
        this.triggerBit = ((subtype & 0x40) != 0) ? 7 : 0;

        // ROM: btst #4,subtype(a0) — if set, trigger stays set when player leaves
        this.toggleMode = (subtype & 0x10) != 0;

        // ROM: btst #5,subtype(a0) — selects SolidObjectTop path
        this.topSolid = (subtype & 0x20) != 0;

        // ROM: addq.w #4,y_pos(a0)
        this.adjustedY = spawn.y() + INIT_Y_OFFSET;

        // Resolve zone-specific art key
        this.artKey = resolveArtKey();
    }

    @Override
    public Sonic3kButtonObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return ObjectConstructionContext.construct(ctx.objectServices(),
                () -> new Sonic3kButtonObjectInstance(ctx.spawn()));
    }

    /**
     * Resolves the art key based on Current_zone and Current_act.
     * ROM: sonic3k.asm lines 60724-60787 (zone-specific branch chain)
     */
    private String resolveArtKey() {
        try {
            int zone = services().romZoneId();
            int act = services().currentAct();
            return switch (zone) {
                case Sonic3kZoneIds.ZONE_HCZ -> Sonic3kObjectArtKeys.HCZ_BUTTON;
                case Sonic3kZoneIds.ZONE_CNZ -> Sonic3kObjectArtKeys.CNZ_BUTTON;
                case Sonic3kZoneIds.ZONE_FBZ -> Sonic3kObjectArtKeys.FBZ_BUTTON;
                case Sonic3kZoneIds.ZONE_LRZ -> act == 0
                        ? Sonic3kObjectArtKeys.LRZ_BUTTON
                        : Sonic3kObjectArtKeys.LRZ2_BUTTON;
                default -> Sonic3kObjectArtKeys.BUTTON;
            };
        } catch (Exception e) {
            return Sonic3kObjectArtKeys.BUTTON;
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM: move.b #0,mapping_frame(a0) — reset to unpressed each frame
        mappingFrame = FRAME_UNPRESSED;

        // Read and clear the standing flag set by onSolidContact
        // ROM: move.b status(a0),d0 / andi.b #standing_mask,d0
        boolean standing = contactStanding;
        contactStanding = false;

        if (!standing) {
            // Player not on button
            if (!toggleMode) {
                // ROM: bclr d3,(a3) — momentary: clear trigger when nobody is standing
                Sonic3kLevelTriggerManager.clearBit(triggerIndex, triggerBit);
            }
            // Toggle mode (bit 4 set): trigger stays set, skip clear
        } else {
            pressButton();
        }
    }

    @Override
    public int getY() {
        return adjustedY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ========================================================================================
    // SolidObjectProvider
    // ========================================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return topSolid ? SOLID_PARAMS_TOP : SOLID_PARAMS_FULL;
    }

    @Override
    public boolean isTopSolidOnly() {
        return topSolid;
    }

    @Override
    public boolean rejectsZeroDistanceTopSolidLanding(PlayableEntity player) {
        // ROM SolidObjectTop_1P loc_1E45A accepts the negative overlap window
        // only: d0 == 0 reaches cmpi.w #-$10,d0 / blo locret_1E4D4.
        return topSolid;
    }

    @Override
    public boolean usesInclusiveRightEdge() {
        // SolidObjectFull's unsigned broad-X comparison rejects only values
        // above d1*2 (bhi), so a player exactly on the right edge remains a
        // valid zero-distance side contact. SolidObjectTop uses the same
        // inclusive horizontal gate before its landing-only checks.
        return true;
    }

    // ========================================================================================
    // SolidObjectListener
    // ========================================================================================

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        if (contact.standing()) {
            contactStanding = true;
            pressButton();
        }
    }

    private void pressButton() {
        // Player standing on button
        // ROM: tst.b (a3) / bne.s + — play SFX only if entire trigger byte was zero
        if (!Sonic3kLevelTriggerManager.testAny(triggerIndex)) {
            services().playSfx(Sonic3kSfx.SWITCH.id);
        }

        // ROM: bset d3,(a3)
        Sonic3kLevelTriggerManager.setBit(triggerIndex, triggerBit);

        // ROM: move.b #1,mapping_frame(a0)
        mappingFrame = FRAME_PRESSED;
    }

    // ========================================================================================
    // Rendering
    // ========================================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer != null) {
            renderer.drawFrameIndex(mappingFrame, spawn.x(), adjustedY, false, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = spawn.x();
        SolidObjectParams params = getSolidParams();

        // Collision bounds
        ctx.drawRect(x, adjustedY, params.halfWidth(),
                topSolid ? params.airHalfHeight() : params.groundHalfHeight(),
                0.9f, 0.9f, 0.2f);

        // Spawn position marker
        ctx.drawCross(x, spawn.y(), 3, 0.5f, 0.5f, 0.5f);

        // State label
        boolean pressed = mappingFrame == FRAME_PRESSED;
        boolean triggered = Sonic3kLevelTriggerManager.testBit(triggerIndex, triggerBit);
        String label = String.format("Btn t=%d b=%d %s%s",
                triggerIndex, triggerBit,
                pressed ? "ON" : "off",
                toggleMode ? " latch" : "");
        ctx.drawWorldLabel(x, adjustedY, -2, label,
                triggered ? DebugColor.GREEN : DebugColor.YELLOW);
    }
}
