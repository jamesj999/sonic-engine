package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.sonic1.Sonic1FloatingBlockState;
import com.openggf.game.sonic1.Sonic1ZoneFeatureProvider;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.OscillationManager;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.SolidRoutineProfile;
import com.openggf.level.objects.SpawnRomZoneRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x56 - Floating blocks (SYZ/SLZ) and large doors (LZ).
 * <p>
 * Solid objects with multiple sizes and movement subtypes. Used extensively
 * in Spring Yard Zone (floating blocks), Star Light Zone (floating blocks),
 * and Labyrinth Zone (doors activated by switches).
 * <p>
 * Subtype encoding:
 * <ul>
 *   <li>High nybble (bits 4-6): Size/appearance index (0-7, from FBlock_Var table)</li>
 *   <li>Bit 7: When set, the high nybble selects size but the low nybble becomes a
 *       switch-activated movement type (type 05/06 pair, or type 0C/0D pair)</li>
 *   <li>Low nybble (bits 0-3): Movement behavior type (0x00-0x0D)</li>
 * </ul>
 * <p>
 * FBlock_Var table (halfWidth, halfHeight for each high-nybble variant):
 * <ul>
 *   <li>0: $10, $10 - SYZ 1x1 square block</li>
 *   <li>1: $20, $20 - SYZ 2x2 square blocks</li>
 *   <li>2: $10, $20 - SYZ 1x2 tall blocks</li>
 *   <li>3: $20, $1A - SYZ 2x2 rectangular blocks</li>
 *   <li>4: $10, $27 - SYZ 1x3 tall rectangular blocks</li>
 *   <li>5: $10, $10 - SLZ 1x1 square block</li>
 *   <li>6: $08, $20 - LZ small vertical door</li>
 *   <li>7: $40, $10 - LZ large horizontal door</li>
 * </ul>
 * <p>
 * Movement subtypes:
 * <ul>
 *   <li>00: Stationary</li>
 *   <li>01: Horizontal oscillation (v_oscillate+$A, amplitude $40)</li>
 *   <li>02: Horizontal oscillation (v_oscillate+$1E, amplitude $80)</li>
 *   <li>03: Vertical oscillation (v_oscillate+$A, amplitude $40)</li>
 *   <li>04: Vertical oscillation (v_oscillate+$1E, amplitude $80)</li>
 *   <li>05: Switch-activated: slide up when switch pressed (LZ doors)</li>
 *   <li>06: Switch-activated: slide down when switch released (LZ doors)</li>
 *   <li>07: Switch $F activated: slide right until distance $380 (LZ3 specific)</li>
 *   <li>08-0B: Square orbit motion (4 directions, different speeds/amplitudes)</li>
 *   <li>0C: Switch-activated: slide left when switch pressed (LZ horizontal door)</li>
 *   <li>0D: Switch-activated: slide right when switch released (LZ horizontal door)</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/56 Floating Blocks and Doors.asm
 */
public class Sonic1FloatingBlockObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SpawnRomZoneRewindRecreatable {

    // ---- FBlock_Var table: {halfWidth, halfHeight} indexed by (subtype >> 4) & 7 ----
    // From disassembly: dc.b $10,$10 / $20,$20 / $10,$20 / $20,$1A / $10,$27 / $10,$10 / 8,$20 / $40,$10
    private static final int[][] FBLOCK_VAR = {
            {0x10, 0x10}, // subtype 0x/8x - SYZ 1x1 square
            {0x20, 0x20}, // subtype 1x/9x - SYZ 2x2 square
            {0x10, 0x20}, // subtype 2x/Ax - SYZ 1x2 tall
            {0x20, 0x1A}, // subtype 3x/Bx - SYZ 2x2 rectangular
            {0x10, 0x27}, // subtype 4x/Cx - SYZ 1x3 tall rectangular
            {0x10, 0x10}, // subtype 5x/Dx - SLZ 1x1 square
            {0x08, 0x20}, // subtype 6x/Ex - LZ small vertical door
            {0x40, 0x10}, // subtype 7x/Fx - LZ large horizontal door
    };

    // From disassembly: move.b #3,obPriority(a0)
    private static final int PRIORITY = 3;

    // Type 01: move.w #$40,d1 — amplitude / (v_oscillate+$A) -> getByte(0x08)
    private static final int TYPE01_AMPLITUDE = 0x40;
    private static final int TYPE01_OSC_OFFSET = 0x08;

    // Type 02: move.w #$80,d1 — amplitude / (v_oscillate+$1E) -> getByte(0x1C)
    private static final int TYPE02_AMPLITUDE = 0x80;
    private static final int TYPE02_OSC_OFFSET = 0x1C;

    // Type 03: move.w #$40,d1 — amplitude (same oscillator as type 01)
    private static final int TYPE03_AMPLITUDE = 0x40;

    // Type 04: move.w #$80,d1 — amplitude (same oscillator as type 02)
    private static final int TYPE04_AMPLITUDE = 0x80;

    // Type 05: subq.w #2,fb_height(a0) — vertical movement speed per frame
    private static final int TYPE05_SPEED = 2;

    // Type 06: addq.w #2,fb_height(a0) — vertical movement speed per frame
    private static final int TYPE06_SPEED = 2;

    // Type 07: cmpi.w #$380,fb_height(a0) — total distance for LZ3 wall slide
    private static final int TYPE07_MAX_DISTANCE = 0x380;

    // Type 0C: subq.w #2,fb_height(a0) — horizontal movement speed per frame
    private static final int TYPE0C_SPEED = 2;

    // Type 0D: addq.w #2,fb_height(a0) — horizontal movement speed per frame
    private static final int TYPE0D_SPEED = 2;

    // Type 0C/0D: addi.w #$80,d0 — horizontal offset for LZ large door
    private static final int TYPE0C_OFFSET = 0x80;

    // Square orbit types (08-0B) oscillator offsets and amplitudes
    // Type 08: v_oscillate+$2A -> getByte(0x28), getWord(0x2A)
    private static final int TYPE08_OSC_BYTE = 0x28;
    private static final int TYPE08_OSC_WORD = 0x2A;
    private static final int TYPE08_AMPLITUDE = 0x10;
    // Type 09: v_oscillate+$2E -> getByte(0x2C), getWord(0x2E)
    private static final int TYPE09_OSC_BYTE = 0x2C;
    private static final int TYPE09_OSC_WORD = 0x2E;
    private static final int TYPE09_AMPLITUDE = 0x30;
    // Type 0A: v_oscillate+$32 -> getByte(0x30), getWord(0x32)
    private static final int TYPE0A_OSC_BYTE = 0x30;
    private static final int TYPE0A_OSC_WORD = 0x32;
    private static final int TYPE0A_AMPLITUDE = 0x50;
    // Type 0B: v_oscillate+$36 -> getByte(0x34), getWord(0x36)
    private static final int TYPE0B_OSC_BYTE = 0x34;
    private static final int TYPE0B_OSC_WORD = 0x36;
    private static final int TYPE0B_AMPLITUDE = 0x70;

    // Dynamic position
    private int x;
    private int y;

    // Saved base positions (fb_origX = objoff_34, fb_origY = objoff_30)
    private int origX;
    private int origY;

    // Visual properties
    private int halfWidth;   // obActWid
    private int halfHeight;  // obHeight
    private int mappingFrame; // obFrame

    // fb_height (objoff_3A): total movement distance remaining
    private int fbHeight;

    // fb_type (objoff_3C): switch index for switch-activated types
    private int fbType;

    // Current movement subtype (low nybble, may change during gameplay)
    private int moveType;

    // objoff_38: switch activation flag (set when switch triggers movement)
    private boolean activated;

    // obStatus bits 0-1: direction state for square orbit types
    private int statusDirection;

    // Whether the object is in LZ (uses door art)
    private boolean isLZ;

    // Art key for rendering
    private final String artKey;

    // Zone index
    private int zoneIndex;

    private final boolean syz3TunnelRealBlock;
    private final boolean syz3TunnelProxyBlock;

    public Sonic1FloatingBlockObjectInstance(ObjectSpawn spawn, int zoneIndex) {
        super(spawn, "FloatingBlock");
        this.zoneIndex = zoneIndex;
        this.isLZ = (zoneIndex == Sonic1Constants.ZONE_LZ);

        int fullSubtype = spawn.subtype() & 0xFF;
        this.syz3TunnelRealBlock = fullSubtype == 0x37 && spawn.x() == 0x1BB8;
        this.syz3TunnelProxyBlock = fullSubtype == 0x37 && spawn.x() == 0x1F38;

        // FBlock_Var lookup: lsr.w #3,d0 / andi.w #$E,d0 -> index = (subtype >> 4) & 7
        int varIndex = (fullSubtype >> 4) & 0x07;
        this.halfWidth = FBLOCK_VAR[varIndex][0];
        this.halfHeight = FBLOCK_VAR[varIndex][1];

        // lsr.w #1,d0 -> frame = varIndex
        this.mappingFrame = varIndex;

        this.x = spawn.x();
        this.y = spawn.y();
        this.origX = spawn.x();
        this.origY = spawn.y();

        // fb_height = obHeight * 2 (initial movement distance)
        this.fbHeight = halfHeight * 2;

        this.activated = false;
        this.fbType = 0;

        // Status direction from spawn renderFlags bit 0
        this.statusDirection = spawn.renderFlags() & 0x03;

        // Determine initial oscillation direction for SYZ/SLZ types 08-0B
        // From disassembly: subtypes 8-F check oscillator state to potentially flip status bit 0
        if (!isLZ) {
            int lowNyb = fullSubtype & 0x0F;
            if (lowNyb >= 8) {
                int oscWordOffset = getSquareOrbitOscWord(lowNyb - 8);
                if (oscWordOffset >= 0) {
                    int oscWord = OscillationManager.getWord(oscWordOffset);
                    if (oscWord < 0) { // tst.w (a2) / bpl.s
                        // bchg #0,obStatus(a0)
                        statusDirection ^= 1;
                    }
                }
            }
        }

        // Handle high-bit subtypes (bit 7 set -> switch-activated door)
        if ((fullSubtype & 0x80) != 0) {
            // andi.b #$F,d0 -> fb_type = low nybble (switch index)
            this.fbType = fullSubtype & 0x0F;
            // move.b #5,obSubtype(a0) -> moveType = 5
            this.moveType = 5;
            if (mappingFrame == 7) {
                // cmpi.b #7,obFrame(a0) / bne.s .chkstate
                // move.b #$C,obSubtype(a0)
                this.moveType = 0x0C;
                // move.w #$80,fb_height(a0)
                this.fbHeight = 0x80;
            }
        } else {
            // SYZ/SLZ: low nybble is the movement type directly
            this.moveType = fullSubtype & 0x0F;
        }

        // Select art key based on zone
        this.artKey = isLZ ? ObjectArtKeys.LZ_FLOATING_BLOCK : ObjectArtKeys.SYZ_FLOATING_BLOCK;

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
        Sonic1FloatingBlockState blockState = services().gameService(Sonic1FloatingBlockState.class);
        if (syz3TunnelRealBlock && blockState != null && blockState.isTunnelBlockAtDestination()) {
            setDestroyed(true);
            return;
        }
        if (syz3TunnelProxyBlock) {
            if (blockState == null || !blockState.isTunnelBlockAtDestination()) {
                setDestroyed(true);
                return;
            }
            // REV01 clears obSubtype so the destination proxy is stationary.
            moveType = 0;
        }
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        applyMovement();
        updateDynamicSpawn(x, y);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(artKey);
        if (renderer == null) return;
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // From disassembly:
        // moveq #0,d1 / move.b obActWid(a0),d1 / addi.w #$B,d1 -> d1 = halfWidth + 11
        // moveq #0,d2 / move.b obHeight(a0),d2 -> d2 = halfHeight
        // move.w d2,d3 / addq.w #1,d3 -> d3 = halfHeight + 1
        // bsr.w SolidObject
        return SolidObjectParams.of(halfWidth + 0x0B, halfHeight, halfHeight + 1);
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // FBlock_Solid calls S1 SolidObject, whose initial horizontal bounds
        // use `bhi`; equality at the right face remains a side collision.
        return SolidRoutineProfile.fullSolid(usesStickyContactBuffer(), true, false);
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // SolidObject stores its standing/pushing bits in the live Obj56 SST.
        // updateDynamicSpawn() rebuilds the engine placement as an oscillating
        // block moves, so that transient spawn cannot own persistent status.
        return true;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Sonic_Move reads the stood-on object's obActWid for edge balancing.
        // FBlock_Main loads that byte directly from FBlock_Var, while
        // FBlock_Solid adds $B only to the d1 passed to SolidObject. Keep that
        // collision padding out of the player's balance window.
        return halfWidth;
    }

    @Override
    public boolean isTopSolidOnly() {
        // SolidObject provides all-sides solidity
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Solid contact handled by engine's SolidContacts system
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        return !isDestroyed();
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public boolean isPersistent() {
        // out_of_range.w DeleteObject,fb_origX(a0)
        return !isDestroyed() && isInRangeAt(origX);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Draw solid collision box
        ctx.drawRect(x, y, halfWidth + 0x0B, halfHeight, 0.3f, 0.6f, 1.0f);

        // Label with type info
        String label = String.format("FB t%X", moveType);
        if (moveType == 5 || moveType == 6 || moveType == 0x0C || moveType == 0x0D) {
            Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
            boolean sw = switches.isPressed(fbType);
            label += String.format(" sw%d=%s h=%d", fbType, sw ? "ON" : "OFF", fbHeight);
        }
        ctx.drawWorldLabel(x, y - halfHeight - 8, 0, label, com.openggf.debug.DebugColor.CYAN);
    }

    // ========================================
    // Movement dispatch
    // ========================================

    private void applyMovement() {
        switch (moveType) {
            case 0x00 -> { /* Stationary (rts) */ }
            case 0x01 -> moveTypeHorizontalOsc(TYPE01_AMPLITUDE, TYPE01_OSC_OFFSET);
            case 0x02 -> moveTypeHorizontalOsc(TYPE02_AMPLITUDE, TYPE02_OSC_OFFSET);
            case 0x03 -> moveTypeVerticalOsc(TYPE03_AMPLITUDE, TYPE01_OSC_OFFSET);
            case 0x04 -> moveTypeVerticalOsc(TYPE04_AMPLITUDE, TYPE02_OSC_OFFSET);
            case 0x05 -> moveType05SwitchUp();
            case 0x06 -> moveType06SwitchDown();
            case 0x07 -> moveType07SlideRight();
            case 0x08 -> moveTypeSquareOrbit(TYPE08_AMPLITUDE, TYPE08_OSC_BYTE, TYPE08_OSC_WORD);
            case 0x09 -> moveTypeSquareOrbit(TYPE09_AMPLITUDE, TYPE09_OSC_BYTE, TYPE09_OSC_WORD);
            case 0x0A -> moveTypeSquareOrbit(TYPE0A_AMPLITUDE, TYPE0A_OSC_BYTE, TYPE0A_OSC_WORD);
            case 0x0B -> moveTypeSquareOrbit(TYPE0B_AMPLITUDE, TYPE0B_OSC_BYTE, TYPE0B_OSC_WORD);
            case 0x0C -> moveType0CSwitchLeft();
            case 0x0D -> moveType0DSwitchRight();
        }
    }

    /**
     * Types 01/02: Horizontal oscillation.
     * <pre>
     * move.w #amplitude,d1
     * move.b (v_oscillate+offset).w,d0
     * btst #0,obStatus(a0) / beq.s .noflip
     * neg.w d0 / add.w d1,d0
     * .noflip:
     * move.w fb_origX(a0),d1 / sub.w d0,d1 / move.w d1,obX(a0)
     * </pre>
     */
    private void moveTypeHorizontalOsc(int amplitude, int oscOffset) {
        int d0 = OscillationManager.getByte(oscOffset) & 0xFF;
        if ((statusDirection & 1) != 0) {
            // neg.w d0 / add.w d1,d0
            d0 = (short) ((-d0 + amplitude) & 0xFFFF);
        }
        x = origX - (short) d0;
    }

    /**
     * Types 03/04: Vertical oscillation.
     * Same math as horizontal but applied to Y position.
     */
    private void moveTypeVerticalOsc(int amplitude, int oscOffset) {
        int d0 = OscillationManager.getByte(oscOffset) & 0xFF;
        if ((statusDirection & 1) != 0) {
            d0 = (short) ((-d0 + amplitude) & 0xFFFF);
        }
        y = origY - (short) d0;
    }

    /**
     * Type 05: Switch-activated vertical slide up (LZ doors).
     * When the corresponding switch is pressed, the door slides up by
     * decrementing fb_height by 2 each frame until it reaches 0.
     * Then advances to type 06 and marks respawn state.
     * <p>
     * Also handles LZ1 water tunnel allow flag for switch 3.
     */
    private void moveType05SwitchUp() {
        if (!activated) {
            // Check LZ1 water tunnel logic
            handleLz1WaterTunnel();

            // Check switch state
            Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
            // ROM: btst #0,(f_switch+fb_type)
            if ((switches.getRaw(fbType) & 0x01) != 0) {
                // ROM (lines 239-243): once the switch is pressed, the LZ1
                // switch-3 door unconditionally re-enables the water current
                // this same frame, overriding the disable above.
                if (isLz1WaterTunnelDoor()) {
                    setWindTunnelDisabled(false);
                }
                // Switch pressed - begin activation
                activated = true;
            }
        }

        if (activated) {
            // tst.w fb_height(a0) / beq.s .done
            if (fbHeight <= 0) {
                // Door fully retracted - advance to type 06
                moveType = 6;
                activated = false;
                // Mark respawn state so door stays open on revisit
                // bset #0,2(a2,d0.w) in disassembly
                return;
            }
            // subq.w #2,fb_height(a0)
            fbHeight -= TYPE05_SPEED;
        }

        // Apply vertical offset
        applyVerticalOffset();
    }

    /**
     * Type 06: Switch-released vertical slide down (LZ doors).
     * When the switch is released (bit 7 set = negative), the door slides
     * back down by incrementing fb_height until it equals obHeight * 2.
     * Then reverts to type 05 and clears respawn state.
     */
    private void moveType06SwitchDown() {
        if (!activated) {
            // tst.b (a2,d0.w) / bpl.s .noactivate
            Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
            byte raw = switches.getRaw(fbType);
            if (raw < 0) { // bit 7 set = negative byte
                activated = true;
            }
        }

        if (activated) {
            int maxHeight = halfHeight * 2;
            if (fbHeight >= maxHeight) {
                // Door fully extended - revert to type 05
                moveType = 5;
                activated = false;
                return;
            }
            // addq.w #2,fb_height(a0)
            fbHeight += TYPE06_SPEED;
        }

        // Apply vertical offset
        applyVerticalOffset();
    }

    /**
     * Type 07: Switch $F activated - slide right until distance $380 (LZ3 specific).
     * <pre>
     * tst.b (f_switch+$F).w / beq.s .wait
     * move.b #1,objoff_38(a0) / clr.w fb_height(a0)
     * .slide:
     * addq.w #1,obX(a0) / move.w obX(a0),fb_origX(a0)
     * addq.w #1,fb_height(a0)
     * cmpi.w #$380,fb_height(a0) / bne.s .ret
     * clr.b obSubtype(a0) -> becomes type 00 (stationary)
     * </pre>
     */
    private void moveType07SlideRight() {
        if (!activated) {
            // tst.b (f_switch+$F).w — switch index $F
            Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
            if (!switches.isPressed(0x0F)) {
                return;
            }
            activated = true;
            fbHeight = 0;
        }

        // addq.w #1,obX(a0)
        x++;
        // addq.w #1,fb_height(a0)
        fbHeight++;
        // cmpi.w #$380,fb_height(a0)
        if (fbHeight >= TYPE07_MAX_DISTANCE) {
            Sonic1FloatingBlockState blockState = services().gameService(Sonic1FloatingBlockState.class);
            if (syz3TunnelRealBlock && blockState != null) {
                blockState.markTunnelBlockAtDestination();
            }
            // clr.b obSubtype(a0) -> stationary
            moveType = 0x00;
            activated = false;
        }
    }

    /**
     * Types 08-0B: Square orbit motion.
     * The block moves in a square pattern using 4 phases (statusDirection 0-3).
     * The oscillator value controls position along one edge, and when the
     * oscillator's delta crosses zero, the block advances to the next edge.
     * <pre>
     * .square:
     *   tst.w d3 / bne.s .noadvance
     *   addq.b #1,obStatus(a0) / andi.b #3,obStatus(a0)
     * .noadvance:
     *   move.b obStatus(a0),d2 / andi.b #3,d2
     *   ; d2 selects which edge of the square to compute position for
     * </pre>
     */
    private void moveTypeSquareOrbit(int amplitude, int oscByte, int oscWord) {
        // move.b (v_oscillate+byte).w,d0
        int d0 = OscillationManager.getByte(oscByte) & 0xFF;
        // Type 08: lsr.w #1,d0 (only for type 08)
        if (oscByte == TYPE08_OSC_BYTE) {
            d0 >>= 1;
        }
        // move.w (v_oscillate+word).w,d3
        int d3 = OscillationManager.getWord(oscWord);

        // tst.w d3 / bne.s .noadvance
        if (d3 == 0) {
            // addq.b #1,obStatus(a0) / andi.b #3,obStatus(a0)
            statusDirection = (statusDirection + 1) & 3;
        }

        int d1 = amplitude;
        int d2 = statusDirection & 3;

        switch (d2) {
            case 0 -> {
                // Phase 0: top edge, moving right
                // sub.w d1,d0 / add.w fb_origX,d0 -> X
                x = origX + d0 - d1;
                // neg.w d1 / add.w fb_origY,d1 -> Y
                y = origY - d1;
            }
            case 1 -> {
                // Phase 1: right edge, moving down
                // subq.w #1,d1 / sub.w d1,d0 / neg.w d0 / add.w fb_origY,d0 -> Y
                y = origY + (d1 - 1) - d0;
                // addq.w #1,d1 / add.w fb_origX,d1 -> X
                x = origX + d1;
            }
            case 2 -> {
                // Phase 2: bottom edge, moving left
                // subq.w #1,d1 / sub.w d1,d0 / neg.w d0 / add.w fb_origX,d0 -> X
                x = origX + (d1 - 1) - d0;
                // addq.w #1,d1 / add.w fb_origY,d1 -> Y
                y = origY + d1;
            }
            case 3 -> {
                // Phase 3: left edge, moving up
                // sub.w d1,d0 / add.w fb_origY,d0 -> Y
                y = origY + d0 - d1;
                // neg.w d1 / add.w fb_origX,d1 -> X
                x = origX - d1;
            }
        }
    }

    /**
     * Type 0C: Switch-activated horizontal slide left (LZ large horizontal door).
     * Decrements fb_height by 2 per frame until it reaches 0, then advances to type 0D.
     */
    private void moveType0CSwitchLeft() {
        if (!activated) {
            Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
            // ROM: btst #0,(f_switch+fb_type)
            if ((switches.getRaw(fbType) & 0x01) != 0) {
                activated = true;
            }
        }

        if (activated) {
            if (fbHeight <= 0) {
                // Door fully retracted - advance to type 0D
                moveType = 0x0D;
                activated = false;
                return;
            }
            // subq.w #2,fb_height(a0)
            fbHeight -= TYPE0C_SPEED;
        }

        // Apply horizontal offset
        applyHorizontalOffset();
    }

    /**
     * Type 0D: Switch-released horizontal slide right (LZ large horizontal door).
     * Increments fb_height by 2 per frame until it reaches $80, then reverts to type 0C.
     */
    private void moveType0DSwitchRight() {
        if (!activated) {
            Sonic1SwitchManager switches = services().gameService(Sonic1SwitchManager.class);
            byte raw = switches.getRaw(fbType);
            if (raw < 0) { // bit 7 set
                activated = true;
            }
        }

        if (activated) {
            if (fbHeight >= TYPE0C_OFFSET) {
                // Door fully extended - revert to type 0C
                moveType = 0x0C;
                activated = false;
                return;
            }
            // addq.w #2,fb_height(a0)
            fbHeight += TYPE0D_SPEED;
        }

        // Apply horizontal offset
        applyHorizontalOffset();
    }

    // ========================================
    // Position helpers
    // ========================================

    /**
     * Apply vertical offset from fb_height, respecting status direction flip.
     * <pre>
     * move.w fb_height(a0),d0
     * btst #0,obStatus(a0) / beq.s .noinvert
     * neg.w d0
     * .noinvert:
     * move.w fb_origY(a0),d1 / add.w d0,d1 / move.w d1,obY(a0)
     * </pre>
     */
    private void applyVerticalOffset() {
        int d0 = fbHeight;
        if ((statusDirection & 1) != 0) {
            d0 = -d0;
        }
        y = origY + d0;
    }

    /**
     * Apply horizontal offset from fb_height for LZ large door types (0C/0D).
     * <pre>
     * move.w fb_height(a0),d0
     * btst #0,obStatus(a0) / beq.s .noinvert
     * neg.w d0 / addi.w #$80,d0
     * .noinvert:
     * move.w fb_origX(a0),d1 / add.w d0,d1 / move.w d1,obX(a0)
     * </pre>
     */
    private void applyHorizontalOffset() {
        int d0 = fbHeight;
        if ((statusDirection & 1) != 0) {
            d0 = -d0 + TYPE0C_OFFSET;
        }
        x = origX + d0;
    }

    /**
     * Handle LZ1 water tunnel allow flag logic (ROM: f_wtunnelallow).
     * <p>
     * ROM: "56 SYZ, SLZ Floating Blocks and LZ Doors.asm" type05, lines 219-232.
     * Only the LZ1 (zone LZ, act 1) door with {@code fb_type == 3} gates the
     * tunnel: every frame before the door is triggered, the flag is cleared
     * (tunnel allowed), then re-disabled if Sonic hasn't yet passed the door's
     * X position. Without this gate the water current pushes Sonic into the
     * still-closed door with no way out.
     */
    private void handleLz1WaterTunnel() {
        // cmpi.w #(id_LZ<<8)+0,(v_zone_act).w — only in LZ act 1, fb_type==3
        if (!isLz1WaterTunnelDoor()) {
            return;
        }

        // clr.b (f_wtunnelallow).w — enable by default
        setWindTunnelDisabled(false);

        AbstractPlayableSprite player = services().camera() != null
                ? services().camera().getFocusedSprite() : null;
        if (player == null) {
            return;
        }

        // move.w (v_player+obX).w,d0 / cmp.w obX(a0),d0 / bhs.s .aaa
        // If player X >= door X (passed the door), leave the tunnel enabled.
        if (player.getCentreX() < x) {
            // move.b #1,(f_wtunnelallow).w — disable until Sonic passes the door
            setWindTunnelDisabled(true);
        }
    }

    /**
     * Whether this door is the specific LZ1 (act 1) switch-3 door that gates
     * the water tunnel's {@code f_wtunnelallow} flag.
     */
    private boolean isLz1WaterTunnelDoor() {
        return isLZ && fbType == 3 && services().currentAct() == 0;
    }

    private void setWindTunnelDisabled(boolean disabled) {
        ZoneFeatureProvider provider = services().zoneFeatureProvider();
        if (provider instanceof Sonic1ZoneFeatureProvider sonic1) {
            sonic1.setWindTunnelDisabled(disabled);
        }
    }

    /**
     * Get the oscillation word offset for square orbit subtypes.
     * @param relativeIndex 0-3 corresponding to types 08-0B
     * @return byte offset for getWord(), or -1 if invalid
     */
    private int getSquareOrbitOscWord(int relativeIndex) {
        return switch (relativeIndex) {
            case 0 -> TYPE08_OSC_WORD;
            case 1 -> TYPE09_OSC_WORD;
            case 2 -> TYPE0A_OSC_WORD;
            case 3 -> TYPE0B_OSC_WORD;
            default -> -1;
        };
    }

}
