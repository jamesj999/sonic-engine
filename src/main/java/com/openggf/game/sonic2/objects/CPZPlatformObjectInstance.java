package com.openggf.game.sonic2.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.scroll.Sonic2ZoneConstants;
import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 19 - Moving platform from CPZ, OOZ and WFZ.
 * Implements all movement behaviors from the disassembly (Obj19).
 *
 * Subtype structure:
 * - Bits 0-3: Movement type (0-F)
 * - Bits 4-7: Size/frame index lookup (>>3 & 0x1E)
 *
 * Movement types:
 * - 0: Horizontal oscillation (amplitude $40)
 * - 1: Horizontal oscillation (amplitude $60)
 * - 2: Vertical oscillation (amplitude $80)
 * - 3: Trigger on standing (increments subtype)
 * - 4: Auto rise with oscillation
 * - 5: Stationary (no movement)
 * - 6,7: Continuous rise
 * - 8-B: Circular motion (variants)
 * - C-F: Circular motion reversed (variants)
 */
public class CPZPlatformObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(CPZPlatformObjectInstance.class.getName());

    // Subtype properties: width_pixels, mapping_frame (from
    // Obj19_SubtypeProperties)
    private static final int[][] SUBTYPE_PROPERTIES = {
            { 0x20, 0 }, // Index 0: 32px width, frame 0
            { 0x18, 1 }, // Index 1: 24px width, frame 1
            { 0x40, 2 }, // Index 2: 64px width, frame 2
            { 0x20, 3 }, // Index 3: 32px width, frame 3
    };

    private static final int HALF_HEIGHT = 0x11; // d3 = $11 in Obj19_Main

    private int x;
    private int y;
    private int baseX; // objoff_30
    private int baseY; // objoff_32
    private int widthPixels;
    private int mappingFrame;
    private int moveType;
    private int yVel;
    /**
     * Sub-pixel fraction of the platform's vertical position (the low word of
     * the ROM's 32-bit {@code y_pos:y_sub} longword). ObjectMove integrates
     * velocity into this 16.16 fixed-point value (s2.asm:30185-30198), so the
     * auto-rise routines (Obj19_MoveRoutine5/6) only cross a pixel boundary once
     * enough sub-pixels accumulate. Keeping this fraction is what stops the
     * platform descending a full pixel every frame.
     */
    private int ySub;
    private boolean xFlip;
    public CPZPlatformObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        init();
    }

    @Override
    public CPZPlatformObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CPZPlatformObjectInstance(ctx.spawn(), getName());
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
        // Apply movement based on subtype
        applyMovement(player);

        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKeyForRomZone(services().currentZone()));
        if (renderer == null) return;

        boolean hFlip = xFlip;
        boolean vFlip = false;

        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, vFlip);
    }

    public static String artKeyForRomZone(int romZone) {
        return romZone == Sonic2ZoneConstants.ROM_ZONE_WFZ
                ? Sonic2ObjectArtKeys.WFZ_PLATFORM
                : Sonic2ObjectArtKeys.CPZ_PLATFORM;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(widthPixels, HALF_HEIGHT, HALF_HEIGHT);
    }

    /**
     * ROM Obj19 stores its subtype-driven {@code width_pixels} ($20/$18/$40,
     * see SUBTYPE_PROPERTIES) in the SST byte the player balance routines read
     * (s2.asm:36586/39707 {@code move.b width_pixels(a1),d1}). The shared
     * default of 16 px would place a balancing rider on the wrong object edge,
     * so report the platform's actual {@code width_pixels} for balance.
     */
    @Override
    public int getBalanceWidthPixels() {
        return widthPixels;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        return SolidRoutineProfile.topSolid(usesStickyContactBuffer());
    }

    @Override
    public int getOutOfRangeReferenceX() {
        return baseX;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Platform state is driven via ObjectManager standing checks.
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    private void init() {
        // Extract size index from subtype bits 4-7: (subtype >> 3) & 0x1E
        int sizeIndex = (spawn.subtype() >> 3) & 0x1E;
        sizeIndex /= 2; // Convert to table index
        if (sizeIndex < 0) {
            sizeIndex = 0;
        }
        if (sizeIndex >= SUBTYPE_PROPERTIES.length) {
            sizeIndex = SUBTYPE_PROPERTIES.length - 1;
        }

        widthPixels = SUBTYPE_PROPERTIES[sizeIndex][0];
        mappingFrame = SUBTYPE_PROPERTIES[sizeIndex][1];

        // Movement type from bits 0-3
        moveType = spawn.subtype() & 0x0F;

        // Store base positions for oscillation reference
        baseX = spawn.x();
        baseY = spawn.y();
        x = baseX;
        y = baseY;

        // X-flip from status/render flags
        xFlip = (spawn.renderFlags() & 0x1) != 0;

        // Special initialization for subtypes 3 and 7
        // From disassembly: if subtype is 3 or 7 and x-flipped, subtract $C0 from y_pos
        if ((moveType == 3 && xFlip) || moveType == 7) {
            y -= 0xC0;
            // baseY (objoff_32) is NOT modified in the ROM - only y_pos is offset
        }

        yVel = 0;
        ySub = 0; // ROM y_sub starts at 0 alongside the pixel y_pos.

        updateDynamicSpawn(x, y);
    }

    /**
     * Applies movement based on movement type (subtype & 0x0F).
     * Ported from Obj19_Move and Obj19_MoveRoutine1-8.
     */
    private void applyMovement(AbstractPlayableSprite player) {
        switch (moveType) {
            case 0 -> applyHorizontalOscillation(0x08, 0x40); // Oscillating_Data+8, amplitude $40
            case 1 -> applyHorizontalOscillation(0x0C, 0x60); // Oscillating_Data+$C, amplitude $60
            case 2 -> applyVerticalOscillation(0x1C, 0x80); // Oscillating_Data+$1C, amplitude $80
            case 3 -> applyTriggerOnStanding();
            case 4 -> applyAutoRise(true);
            case 5 -> {
                /* Stationary - no movement */ }
            case 6, 7 -> applyAutoRise(false);
            case 8, 9, 0xA, 0xB -> applyCircularMotion(moveType, false);
            case 0xC, 0xD, 0xE, 0xF -> applyCircularMotion(moveType, true);
        }
    }

    /**
     * Movement routine 1 & 2: Horizontal oscillation.
     * x_pos = objoff_30 - oscillation_value (adjusted for x-flip)
     */
    private void applyHorizontalOscillation(int oscOffset, int amplitude) {
        int oscValue = OscillationManager.getByte(oscOffset);

        if (xFlip) {
            // Negate and add amplitude
            oscValue = -oscValue + amplitude;
        }

        x = baseX - oscValue;
    }

    /**
     * Movement routine 3: Vertical oscillation.
     * y_pos = objoff_32 - oscillation_value (adjusted for x-flip)
     */
    private void applyVerticalOscillation(int oscOffset, int amplitude) {
        int oscValue = OscillationManager.getByte(oscOffset);

        if (xFlip) {
            // Negate and add amplitude
            oscValue = -oscValue + amplitude;
        }

        y = baseY - oscValue;
    }

    /**
     * Movement routine 4: Trigger on standing.
     * When player stands on platform, increment subtype (move to next routine).
     */
    private void applyTriggerOnStanding() {
        if (isPlayerRiding()) {
            moveType = (moveType + 1) & 0x0F;
        }
    }

    /**
     * Movement routine 5 & 6: Auto rise with or without subtype increment.
     * Moves toward y - $60, accelerates at 8 per frame.
     */
    private void applyAutoRise(boolean incrementSubtype) {
        // ObjectMove (s2.asm:30185-30198): y_pos:y_sub is a 32-bit longword
        // (high word = pixel y, low word = sub-pixel) and the update is
        //   y_pos:y_sub += sign_extend(y_vel) << 8
        // so a small velocity (e.g. y_vel=-24=0xFFE8) accumulates sub-pixels and
        // only steps the pixel y every several frames. The old `y += yVel >> 8`
        // dropped the sub-pixel and moved a full pixel every frame.
        int yPosFixed = (y << 16) | (ySub & 0xFFFF);
        // ext.l + asl.l #8: sign-extend the 16-bit y_vel to a longword, then <<8.
        int yVelFixed = ((short) yVel) << 8;
        yPosFixed += yVelFixed;
        // arithmetic >>16 keeps the (signed) pixel y; mask preserves y_sub.
        y = yPosFixed >> 16;
        ySub = yPosFixed & 0xFFFF;

        // Calculate target (objoff_32 - $60)
        int targetY = baseY - 0x60;

        // Determine acceleration direction. ROM compares the POST-ObjectMove
        // pixel y (s2.asm:48040-48044): cmp.w y_pos(a0),d0 / bhs.s + / neg.w d1.
        // The compare is unsigned (bhs); d0 = target. bhs branches (accel stays
        // +8) when target >= y_pos, i.e. accel goes -8 only when target < y_pos.
        int accel = 8;
        if ((targetY & 0xFFFF) < (y & 0xFFFF)) {
            accel = -8;
        }

        yVel = (short) (yVel + accel); // add.w: 16-bit signed wrap

        // For routine 5: increment subtype when velocity becomes 0.
        // ROM `add.w d1,y_vel(a0) / bne.s` tests the 16-bit word result, so the
        // increment fires only when the word-truncated y_vel is exactly 0.
        if (incrementSubtype && (yVel & 0xFFFF) == 0) {
            moveType = (moveType + 1) & 0x0F;
        }
    }

    /**
     * Movement routine 7 & 8: Circular motion.
     * Uses Oscillating_Data+$38 and +$3C for X and Y components.
     *
     * Bit flags in moveType:
     * - Bit 1: Swap X/Y and negate X
     * - Bit 2: Negate both X and Y
     *
     * Routine 8 (reversed) also negates X before adding to position.
     */
    private void applyCircularMotion(int subtype, boolean reversed) {
        // ROM doubles the subtype before bit testing in Obj19_Move:
        //   move.b  subtype(a0),d0
        //   andi.w  #$F,d0
        //   add.w   d0,d0              ; d0 = subtype * 2
        // This creates 4 distinct quadrants for types 8-11:
        //   Type 8 → d0=16 (0b10000) → bit2=0, bit1=0
        //   Type 9 → d0=18 (0b10010) → bit2=0, bit1=1
        //   Type 10 → d0=20 (0b10100) → bit2=1, bit1=0
        //   Type 11 → d0=22 (0b10110) → bit2=1, bit1=1
        int doubledSubtype = subtype * 2;

        // Get circular motion components
        int d1 = OscillationManager.getByte(0x38) - 0x40; // Sign-extend
        int d2 = OscillationManager.getByte(0x3C) - 0x40;

        // Sign-extend from byte to signed value
        d1 = (byte) d1;
        d2 = (byte) d2;

        // Apply bit 2: negate both (use DOUBLED subtype)
        if ((doubledSubtype & 0x04) != 0) {
            d1 = -d1;
            d2 = -d2;
        }

        // Apply bit 1: swap and negate X (use DOUBLED subtype)
        if ((doubledSubtype & 0x02) != 0) {
            d1 = -d1;
            int temp = d1;
            d1 = d2;
            d2 = temp;
        }

        // Routine 8 (reversed): additional negation of d1
        if (reversed) {
            d1 = -d1;
        }

        x = baseX + d1;
        y = baseY + d2;
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int halfWidth = widthPixels;
        int halfHeight = HALF_HEIGHT;
        int left = x - halfWidth;
        int right = x + halfWidth;
        int top = y - halfHeight;
        int bottom = y + halfHeight;

        ctx.drawLine(left, top, right, top, 0.8f, 0.5f, 0.2f);
        ctx.drawLine(right, top, right, bottom, 0.8f, 0.5f, 0.2f);
        ctx.drawLine(right, bottom, left, bottom, 0.8f, 0.5f, 0.2f);
        ctx.drawLine(left, bottom, left, top, 0.8f, 0.5f, 0.2f);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("sub=%X base=%04X,%04X vel=%04X w=%02X",
                moveType & 0xF, baseX & 0xFFFF, baseY & 0xFFFF,
                yVel & 0xFFFF, widthPixels & 0xFF);
    }

}
