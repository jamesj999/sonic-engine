package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.Sonic1SwitchManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
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
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x31 - Chained Stompers (MZ).
 * <p>
 * Metal blocks on chains that stomp downward in Marble Zone. The main solid block
 * hangs from a ceiling anchor by a chain, with spikes on the underside. The block
 * can be switch-controlled (Type 0), auto-cycling (Type 1), proximity-triggered
 * (Type 3), or stationary (Type 2/4/5/6).
 * <p>
 * The object is compound: a single placement spawns 4 sub-objects:
 * <ul>
 *   <li>Main block (routine 2): Solid, crushes player, renders wide/medium/small block</li>
 *   <li>Spike piece (routine 4): Follows main block Y, renders spike row</li>
 *   <li>Chain piece (routine 8): Renders chain links between ceiling and block</li>
 *   <li>Ceiling piece (routine 6): Static ceiling mount, renders ceiling frame</li>
 * </ul>
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Bit 7: Switch mode - indexes CStom_SwchNums for switch number</li>
 *   <li>Bits 4-6: Speed group index (upper nybble indexes word_B6A4 for max fall distance)</li>
 *   <li>Bits 0-3: Type index (0-6) selecting behavior from CStom_TypeIndex</li>
 * </ul>
 * <p>
 * <b>Types:</b>
 * <ul>
 *   <li>Type 0 (CStom_Type00): Switch-controlled. Rises when switch pressed, falls with gravity when released.</li>
 *   <li>Type 1 (CStom_Type01): Auto-cycling. Falls with gravity, waits $3C frames at bottom, rises back up.</li>
 *   <li>Type 2: Same as Type 1 (shared code path in disassembly).</li>
 *   <li>Type 3 (CStom_Type03): Proximity trigger. When player is within $90 pixels, advances subtype and falls.</li>
 *   <li>Type 4: Same as Type 1.</li>
 *   <li>Type 5: Same as Type 3.</li>
 *   <li>Type 6: Same as Type 1.</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/31 Chained Stompers.asm
 */
public class Sonic1ChainedStomperObjectInstance extends AbstractObjectInstance
        implements SolidObjectProvider, SolidObjectListener, TouchResponseProvider, SpawnRewindRecreatable {

    // ---- Sub-object configuration from CStom_Var ----
    // dc.b routine, y-offset, frame
    // { 2, 0, 0 }     -> main block:  routine 2, Y+0,    frame 0 (wideblock)
    // { 4, $1C, 1 }   -> spikes:      routine 4, Y+$1C,  frame 1 (spikes)
    // { 8, $CC, 3 }   -> chain:       routine 8, Y-$34,  frame 3 (chain1)
    // { 6, $F0, 2 }   -> ceiling:     routine 6, Y-$10,  frame 2 (ceiling)

    // Y offsets for sub-objects (sign-extended from byte: $CC=-$34, $F0=-$10)
    private static final int SPIKE_Y_OFFSET = 0x1C;
    private static final int CHAIN_Y_OFFSET = -0x34;   // $CC sign-extended
    private static final int CEILING_Y_OFFSET = -0x10;  // $F0 sign-extended

    // Mapping frame indices from CStom_Var
    private static final int FRAME_WIDE_BLOCK = 0;
    private static final int FRAME_SPIKES = 1;
    private static final int FRAME_CEILING = 2;
    private static final int FRAME_CHAIN_BASE = 3; // Chain frames 3-7 (1-5 links)

    // Chained Stomper spike rendering uses 5 single-spike pieces from Map_CStom .spikes:
    // spritePiece -$2C, -$10, 1, 4, $21F, 0, 1, 0, 0
    // spritePiece -$18, -$10, 1, 4, $21F, 0, 1, 0, 0
    // spritePiece -4,   -$10, 1, 4, $21F, 0, 1, 0, 0
    // spritePiece  $10, -$10, 1, 4, $21F, 0, 1, 0, 0
    // spritePiece  $24, -$10, 1, 4, $21F, 0, 1, 0, 0
    // We render using spike frame 2 (single upward spike at x=-4) and shift origins.
    private static final int SPIKE_SINGLE_FRAME = 2;
    private static final int[] STOMPER_SPIKE_RENDER_X_OFFSETS = {-0x28, -0x14, 0x00, 0x14, 0x28};

    // Block size variants from CStom_Var2 (indexed by upper nybble bits 4-6)
    // dc.b width, frame
    // { $38, 0 } -> wide block
    // { $30, 9 } -> medium block
    // { $10, $A } -> small block
    private static final int[][] BLOCK_SIZE_TABLE = {
            {0x38, FRAME_WIDE_BLOCK},  // subtype bits 4-6 = 0
            {0x30, 9},                  // subtype bits 4-6 = 1 (medium)
            {0x10, 10},                 // subtype bits 4-6 = 2 (small)
    };

    // Max fall distances from word_B6A4 (indexed by lower nybble)
    // dc.w $7000, $A000, $5000, $7800, $3800, $5800, $B800
    private static final int[] MAX_FALL_DISTANCES = {
            0x7000, 0xA000, 0x5000, 0x7800, 0x3800, 0x5800, 0xB800
    };

    // Switch number table from CStom_SwchNums (2 bytes each: switch#, obj#)
    // dc.b 0, 0 / dc.b 1, 0
    private static final int[][] SWITCH_TABLE = {
            {0, 0},
            {1, 0}
    };

    // Solid params: d1=$B+obActWid, d2=$C, d3=$D
    private static final int SOLID_AIR_HALF_HEIGHT = 0x0C;
    private static final int SOLID_GROUND_HALF_HEIGHT = 0x0D;

    // From disassembly: move.b #4,obPriority(a0) for sub-objects
    private static final int PRIORITY = 4;
    // Last sub-object (ceiling): move.b #3,obPriority(a1)
    private static final int CEILING_PRIORITY = 3;

    // Gravity acceleration: addi.w #$70,obVelY(a0)
    private static final int FALL_GRAVITY = 0x70;

    // Rise speed: subi.w #$80,objoff_32(a0)
    private static final int RISE_SPEED = 0x80;

    // Type 1 wait timer: move.w #$3C,objoff_38(a0)
    private static final int WAIT_TIMER = 0x3C;

    // Type 3 proximity range: cmpi.w #$90,d0
    private static final int PROXIMITY_RANGE = 0x90;

    // Crush threshold: cmpi.b #$10,objoff_32(a0)
    private static final int CRUSH_THRESHOLD = 0x10;

    // Spike collision: move.b #$90,obColType(a0) = HURT($80) + size $10
    private static final int SPIKE_COLLISION_TYPE = 0x90;

    // Spike active width: move.b #$38,obActWid(a1)
    private static final int SPIKE_ACTIVE_WIDTH = 0x38;

    private static final TouchResponseProfile MULTI_REGION_HURT_PROFILE = hurtProfile(
            true, TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_MAIN_ONLY);
    private static final TouchResponseProfile SINGLE_REGION_HURT_PROFILE = hurtProfile(
            false, TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    // Sound effect play interval: andi.b #$F,d0 / bne.s (skip sound)
    private static final int SOUND_INTERVAL_MASK = 0x0F;

    private static TouchResponseProfile hurtProfile(boolean multiRegionSource,
            TouchOverlapStopPolicy stopPolicy) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                multiRegionSource,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                stopPolicy);
    }

    // ---- Instance state ----

    // Position (center coords)
    private int x;
    private int y;

    // Original Y position of main block (objoff_30 for sub-objects, spawn Y for main)
    private int origY;

    // Current Y offset from origY (objoff_32) - in subpixels for fall distance tracking
    private int yOffset;

    // Max fall distance for this instance (objoff_34)
    private int maxFallDistance;

    // Y velocity (obVelY, 16-bit signed)
    private int yVelocity;

    // Behavior type (low nybble of subtype, 0-6)
    private int behaviorType;

    // Live subtype value (modified during gameplay)
    private int subtype;

    // Switch number for Type 0 (CStom_switch = objoff_3A)
    private int switchNumber;

    // Type 1 state: whether block has hit bottom (objoff_36)
    private boolean hasHitBottom;

    // Type 1 wait timer (objoff_38)
    private int waitTimer;

    // Block rendering: active width and mapping frame for the main block
    private int blockActiveWidth;
    private int blockFrame;

    // Whether spikes have collision (only for wide/medium blocks, not when subtype $20)
    private boolean spikesHaveCollision;

    // Spike sub-object Y position (follows block)
    private int spikeY;

    // Chain sub-object base Y and current Y (routine 8: dynamic chain frame)
    private int chainBaseY;
    private int chainY;

    // Ceiling sub-object Y (routine 6: static display)
    private int ceilingY;

    // ROM: CStom_Main's CStom_Loop runs with d1=3 -> 4 iterations. The first
    // iteration writes the main block into the object's own SST slot (a1=a0);
    // the remaining iterations each call FindNextFreeObj to create one child
    // sub-object (spike routine 4, chain routine 8, ceiling routine 6).
    //
    // So a placement normally allocates 3 CHILD slots. BUT when the spike piece
    // (obFrame == 1) belongs to a stomper whose subtype upper nybble == $20,
    // the ROM does `subq.w #1,d1` and then `beq.s CStom_MakeStomper`, re-running
    // the body WITHOUT a fresh FindNextFreeObj — the spike reuses the previous
    // sub-object's slot instead of consuming a new one. The net effect is only
    // 2 child SST slots for $20-subtype stompers (no separate, collision-less
    // spike object). 31 MZ Chained Stompers.asm: cmpi.b #1,obFrame(a1) /
    // subq.w #1,d1 / cmpi.w #$20,d0 / beq.s CStom_MakeStomper.
    //
    // Derived from spikesHaveCollision (which encodes the same subtype==$20 test)
    // instead of stored in a field, so the fix introduces no new rewind-captured
    // scalar.
    private int childSlotCount() {
        return spikesHaveCollision ? 3 : 2;
    }

    /** True once child slots have been allocated (second update, matching CStom_MakeParts). */
    private boolean childSlotsAllocated;

    private final TouchRegion[] spikeTouchRegion = new TouchRegion[1];

    public Sonic1ChainedStomperObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ChainedStomper");

        this.x = spawn.x();
        this.origY = spawn.y();
        this.y = spawn.y();

        int rawSubtype = spawn.subtype() & 0xFF;

        // Process bit 7: switch mode
        // bpl.s loc_B6CE ; if subtype >= 0 (bit 7 clear), skip
        if ((rawSubtype & 0x80) != 0) {
            int switchIndex = rawSubtype & 0x7F;
            if (switchIndex < SWITCH_TABLE.length) {
                this.switchNumber = SWITCH_TABLE[switchIndex][0];
                rawSubtype = SWITCH_TABLE[switchIndex][1]; // Replace subtype with obj number
            } else {
                this.switchNumber = 0;
                rawSubtype = 0;
            }
        } else {
            this.switchNumber = 0;
        }

        this.subtype = rawSubtype;

        // Determine max fall distance from lower nybble
        // andi.b #$F,d0 / add.w d0,d0 / move.w word_B6A4(pc,d0.w),d2
        int lowerNybble = rawSubtype & 0x0F;
        if (lowerNybble < MAX_FALL_DISTANCES.length) {
            this.maxFallDistance = MAX_FALL_DISTANCES[lowerNybble];
        } else {
            this.maxFallDistance = MAX_FALL_DISTANCES[0];
        }

        // For index 0: tst.w d0 / bne.s loc_B6E0 / move.w d2,objoff_32(a0)
        // When lower nybble is 0, start at maxFallDistance (already fallen)
        if (lowerNybble == 0) {
            this.yOffset = this.maxFallDistance;
        } else {
            this.yOffset = 0;
        }

        this.yVelocity = 0;
        this.hasHitBottom = false;
        this.waitTimer = 0;

        // Determine behavior type from lower nybble
        this.behaviorType = lowerNybble;

        // Determine block size from CStom_Var2
        // lsr.w #3,d0 / andi.b #$E,d0 -> ((subtype & 0xF0) >> 3) & 0x0E = (subtype >> 3) & 0x0E
        int sizeIndex = (rawSubtype >> 3) & 0x0E;
        // sizeIndex is 0, 2, or 4 (word-sized entries)
        int sizeEntry = sizeIndex >> 1;
        if (sizeEntry >= BLOCK_SIZE_TABLE.length) {
            sizeEntry = 0;
        }
        this.blockActiveWidth = BLOCK_SIZE_TABLE[sizeEntry][0];
        this.blockFrame = BLOCK_SIZE_TABLE[sizeEntry][1];

        // Check if spikes have collision
        // From disasm: cmpi.b #1,obFrame(a1) / bne.s loc_B76A
        // If spike sub-object frame == 1: check subtype $20 condition
        // cmpi.w #$20,d0 -> if subtype & $F0 == $20, skip collision setup
        // move.b #$38,obActWid(a1) / move.b #$90,obColType(a1)
        int upperNybble = rawSubtype & 0xF0;
        this.spikesHaveCollision = (upperNybble != 0x20);

        // Calculate sub-object positions
        // Spike: spawn.y() + SPIKE_Y_OFFSET
        this.spikeY = this.y + SPIKE_Y_OFFSET;
        // Chain: spawn.y() + CHAIN_Y_OFFSET (chain base Y = objoff_30)
        this.chainBaseY = spawn.y() + CHAIN_Y_OFFSET;
        this.chainY = this.chainBaseY;
        // Ceiling: spawn.y() + CEILING_Y_OFFSET
        this.ceilingY = spawn.y() + CEILING_Y_OFFSET;

        // Apply initial Y offset
        updatePositions();
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
    public int getReservedChildSlotCount() {
        return childSlotCount();
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;

        // ROM parity: CStom_MakeParts runs on the first ExecuteObjects pass.
        // It calls FindNextFreeObj 3 times to allocate 3 child SST slots
        // (spike, ceiling, chain). The engine doesn't render these as separate
        // objects, but the slots must be occupied to keep the SST bitmap
        // aligned with the ROM (affecting d7 timing gates for other objects).
        if (!childSlotsAllocated) {
            childSlotsAllocated = true;
            var objectManager = services().objectManager();
            if (objectManager != null && getSlotIndex() >= 0) {
                objectManager.allocateChildSlotsAfter(spawn, childSlotCount(), getSlotIndex());
            }
        }

        // Main block behavior: loc_B798 (routine 2)
        // bsr.w CStom_Types
        updateBehavior(vIntRunCount, player);

        // Update all sub-object positions based on current yOffset
        updatePositions();
        updateDynamicSpawn(x, y);
    }

    /**
     * CStom_Types: Dispatch to type-specific behavior.
     * <pre>
     * move.b obSubtype(a0),d0
     * andi.w #$F,d0
     * add.w  d0,d0
     * move.w CStom_TypeIndex(pc,d0.w),d1
     * jmp    CStom_TypeIndex(pc,d1.w)
     * </pre>
     */
    private void updateBehavior(int vIntRunCount, AbstractPlayableSprite player) {
        int typeIndex = subtype & 0x0F;
        switch (typeIndex) {
            case 0 -> updateType00(vIntRunCount);
            case 1, 2, 4, 6 -> updateType01(vIntRunCount);
            case 3, 5 -> updateType03(player);
            default -> updateRestart();
        }
    }

    /**
     * CStom_Type00: Switch-controlled behavior.
     * <p>
     * When switch is pressed: rises (subtracts $80 from offset).
     * When switch not pressed: falls with gravity until maxFallDistance.
     */
    private void updateType00(int vIntRunCount) {
        // In ROM: tst.b (f_switch+switchNumber) / beq.s loc_B8A8
        boolean switchPressed = services().gameService(Sonic1SwitchManager.class).isPressed(switchNumber);

        if (switchPressed) {
            // Rising behavior
            riseBlock(vIntRunCount);
        } else {
            // Fall behavior: loc_B8A8
            fallBlock(vIntRunCount);
        }
        updateRestart();
    }

    /**
     * CStom_Type01: Auto-cycling fall/wait/rise behavior.
     * <p>
     * Falls with gravity. On hitting bottom, waits $3C frames, then rises back up.
     * <pre>
     * tst.w  objoff_36(a0) / beq.s loc_B938  ; if not hit bottom, fall
     * tst.w  objoff_38(a0) / beq.s loc_B902  ; if timer expired, rise
     * subq.w #1,objoff_38(a0) ; decrement timer
     * </pre>
     */
    private void updateType01(int vIntRunCount) {
        if (hasHitBottom) {
            if (waitTimer > 0) {
                // Waiting at bottom
                waitTimer--;
            } else {
                // Rising: loc_B902
                riseBlockType01(vIntRunCount);
            }
        } else {
            // Falling: loc_B938
            fallBlockType01(vIntRunCount);
        }
        updateRestart();
    }

    /**
     * CStom_Type03: Proximity trigger.
     * <p>
     * When player is within $90 pixels horizontally, increments subtype
     * (advancing to the next type, typically Type 1 auto-cycle behavior).
     * <pre>
     * move.w (v_player+obX).w,d0
     * sub.w  obX(a0),d0
     * bcc.s  loc_B98C / neg.w d0
     * cmpi.w #$90,d0
     * bhs.s  loc_B996  ; if not in range, skip
     * addq.b #1,obSubtype(a0)  ; advance to next type
     * </pre>
     */
    private void updateType03(AbstractPlayableSprite player) {
        if (player != null) {
            int distance = Math.abs(player.getCentreX() - x);
            if (distance < PROXIMITY_RANGE) {
                // addq.b #1,obSubtype(a0) - advance lower nybble
                subtype = (subtype & 0xF0) | (((subtype & 0x0F) + 1) & 0x0F);
            }
        }
        updateRestart();
    }

    /**
     * Fall with gravity until maxFallDistance (used by Type 0).
     * <pre>
     * move.w objoff_34(a0),d1         ; max fall distance
     * cmp.w  objoff_32(a0),d1         ; compare with current offset
     * beq.s  CStom_Restart            ; if at max, done
     * move.w obVelY(a0),d0
     * addi.w #$70,obVelY(a0)          ; add gravity
     * add.w  d0,objoff_32(a0)         ; apply velocity to offset
     * cmp.w  objoff_32(a0),d1
     * bhi.s  CStom_Restart            ; if not past max, continue
     * move.w d1,objoff_32(a0)         ; clamp to max
     * move.w #0,obVelY(a0)            ; stop
     * </pre>
     */
    private void fallBlock(int vIntRunCount) {
        if (yOffset >= maxFallDistance) {
            return;
        }
        int oldVel = yVelocity;
        yVelocity += FALL_GRAVITY;
        yOffset += oldVel;
        if (yOffset >= maxFallDistance) {
            yOffset = maxFallDistance;
            yVelocity = 0;
            // Play stomp sound when on-screen
            if (isOnScreen(128)) {
                services().playSfx(Sonic1Sfx.CHAIN_STOMP.id);
            }
        }
    }

    /**
     * Rise block (Type 0 switch-controlled rise).
     * <pre>
     * subi.w #$80,objoff_32(a0)
     * bcc.s  CStom_Restart            ; if not past 0, continue
     * move.w #0,objoff_32(a0)         ; clamp to 0
     * move.w #0,obVelY(a0)
     * </pre>
     */
    private void riseBlock(int vIntRunCount) {
        if (yOffset <= 0) {
            yVelocity = 0;
            return;
        }
        // Play rise sound every 16 frames when on-screen
        if ((vIntRunCount & SOUND_INTERVAL_MASK) == 0 && isOnScreen(128)) {
            services().playSfx(Sonic1Sfx.CHAIN_RISE.id);
        }
        yOffset -= RISE_SPEED;
        if (yOffset < 0) {
            yOffset = 0;
        }
        yVelocity = 0;
    }

    /**
     * Fall with gravity for Type 1 auto-cycling.
     * Same as fallBlock but sets hasHitBottom and waitTimer on landing.
     */
    private void fallBlockType01(int vIntRunCount) {
        if (yOffset >= maxFallDistance) {
            return;
        }
        int oldVel = yVelocity;
        yVelocity += FALL_GRAVITY;
        yOffset += oldVel;
        if (yOffset >= maxFallDistance) {
            yOffset = maxFallDistance;
            yVelocity = 0;
            hasHitBottom = true;
            waitTimer = WAIT_TIMER;
            // Play stomp sound when on-screen
            if (isOnScreen(128)) {
                services().playSfx(Sonic1Sfx.CHAIN_STOMP.id);
            }
        }
    }

    /**
     * Rise block for Type 1 auto-cycling.
     * <pre>
     * subi.w #$80,objoff_32(a0)
     * bcc.s  loc_B97C                 ; if not past 0, continue
     * move.w #0,objoff_32(a0)
     * move.w #0,obVelY(a0)
     * move.w #0,objoff_36(a0)         ; clear hit-bottom flag
     * </pre>
     */
    private void riseBlockType01(int vIntRunCount) {
        // Play rise sound every 16 frames when on-screen
        if ((vIntRunCount & SOUND_INTERVAL_MASK) == 0 && isOnScreen(128)) {
            services().playSfx(Sonic1Sfx.CHAIN_RISE.id);
        }
        yOffset -= RISE_SPEED;
        if (yOffset < 0) {
            yOffset = 0;
            yVelocity = 0;
            hasHitBottom = false;
        }
    }

    /**
     * CStom_Restart: Update Y position from offset.
     * <pre>
     * moveq  #0,d0
     * move.b objoff_32(a0),d0         ; reads HIGH byte of word (68k big-endian)
     * add.w  objoff_30(a0),d0         ; add original Y
     * move.w d0,obY(a0)
     * </pre>
     * Note: move.b from a word field reads the high byte on 68000 (big-endian).
     * So the pixel offset is yOffset >> 8.
     */
    private void updateRestart() {
        int d0 = (yOffset >> 8) & 0xFF; // move.b objoff_32(a0),d0 - high byte of word
        y = origY + d0;
    }

    /**
     * Update all sub-object positions based on current main block Y.
     */
    private void updatePositions() {
        // Spike Y follows main block: objoff_30 + objoff_32
        // From routine 4: move.b objoff_32(a1),d0 / add.w objoff_30(a0),d0
        // move.b reads high byte of parent's objoff_32 (big-endian)
        int pixelOffset = (yOffset >> 8) & 0xFF;
        spikeY = y + SPIKE_Y_OFFSET;

        // Chain Y: routine 8 reads objoff_32 from parent and adds to its own objoff_30
        // move.b objoff_32(a1),d0 / add.w objoff_30(a0),d0 / move.w d0,obY(a0)
        chainY = chainBaseY + pixelOffset;

        // Ceiling Y is static (routine 6 just displays)
        // Already set in constructor
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer stomperRenderer = getRenderer(ObjectArtKeys.MZ_CHAINED_STOMPER);
        if (stomperRenderer == null) return;

        // Render ceiling anchor (routine 6, priority 3 = behind everything else)
        stomperRenderer.drawFrameIndex(FRAME_CEILING, x, ceilingY, false, false);

        // Render chain (routine 8)
        // Chain frame is computed from objoff_32: lsr.b #5,d0 / addq.b #3,d0
        // -> frame = (yOffset >> 5) + 3, clamped to available frames 3-7
        int chainFrameIndex = (((yOffset >> 8) & 0xFF) >> 5) + FRAME_CHAIN_BASE;
        if (chainFrameIndex > 7) {
            chainFrameIndex = 7;
        }
        stomperRenderer.drawFrameIndex(chainFrameIndex, x, chainY, false, false);

        // Render main block (routine 2, priority 4)
        stomperRenderer.drawFrameIndex(blockFrame, x, y, false, false);

        // Render spikes (routine 4)
        // Use exact Map_CStom .spikes spacing by drawing five single-spike pieces.
        // All pieces use yflip=1 in disassembly.
        if (spikesHaveCollision) {
            PatternSpriteRenderer spikeRenderer = getRenderer(ObjectArtKeys.SPIKE);
            if (spikeRenderer != null) {
                for (int xOffset : STOMPER_SPIKE_RENDER_X_OFFSETS) {
                    spikeRenderer.drawFrameIndex(SPIKE_SINGLE_FRAME, x + xOffset, spikeY, false, true);
                }
            }
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ---- Solid Object ----

    /**
     * Solid collision for the main block.
     * <pre>
     * moveq  #0,d1
     * move.b obActWid(a0),d1
     * addi.w #$B,d1            ; d1 = halfWidth + $B
     * move.w #$C,d2            ; air half-height
     * move.w #$D,d3            ; ground half-height
     * move.w obX(a0),d4
     * bsr.w  SolidObject
     * </pre>
     */
    @Override
    public SolidObjectParams getSolidParams() {
        int halfWidth = blockActiveWidth + 0x0B;
        return SolidObjectParams.of(halfWidth, SOLID_AIR_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public SolidRoutineProfile getSolidRoutineProfile() {
        // CStom_MainBlock calls S1 SolidObject. Solid_ChkCollision rejects the
        // right edge with `bhi`, so equality remains in the side-contact path.
        return SolidRoutineProfile.fullSolid(false, true, false);
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        // The oscillating block rebuilds dynamicSpawn as its Y changes, while
        // the native standing/pushing bits remain owned by the live Obj31 SST.
        return true;
    }

    @Override
    public int getBalanceWidthPixels() {
        // Sonic_Balance reads the SST obActWid byte, while the SolidObject call
        // extends its horizontal collision check by $B (CStom_MainBlock,
        // _incObj/31 MZ Chained Stompers.asm). Feeding that extension back into
        // balance makes the player appear to stand at an edge while centered.
        return blockActiveWidth;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Crush check from disassembly:
        // btst #3,obStatus(a0) -> check if player is standing on the block
        // beq.s CStom_Display  -> if not standing, skip crush
        // cmpi.b #$10,objoff_32(a0) -> if high byte of offset >= $10, no crush
        // bhs.s CStom_Display
        // jsr (KillSonic).l -> instant death (ignores shields/rings/iframes)
        if (contact.standing() && ((yOffset >> 8) & 0xFF) < CRUSH_THRESHOLD) {
            // KillSonic: direct kill, bypasses ring/shield protection
            // hadRings=false forces applyDeath path; ignoring iframes for ROM accuracy
            player.applyHurtOrDeathIgnoringIFrames(x, false, false);
        }
    }

    // ---- Touch Response (spikes hurt the player) ----

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(getMultiTouchRegions() != null);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return multiRegionSource ? MULTI_REGION_HURT_PROFILE : SINGLE_REGION_HURT_PROFILE;
    }

    @Override
    public int getCollisionFlags() {
        // Spikes have obColType = $90 (HURT $80 + size $10)
        // Only active if spikes have collision
        return spikesHaveCollision ? SPIKE_COLLISION_TYPE : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchRegion[] getMultiTouchRegions() {
        if (!spikesHaveCollision) {
            return null;
        }
        // Only the underside spike row should hurt; the block top remains safe to stand on.
        spikeTouchRegion[0] = new TouchRegion(x, spikeY, SPIKE_COLLISION_TYPE);
        return spikeTouchRegion;
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        return !isDestroyed() && isOnScreenX(128);
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        // Main block solid bounds
        int halfWidth = blockActiveWidth + 0x0B;
        ctx.drawRect(x, y, halfWidth, SOLID_AIR_HALF_HEIGHT, 0.0f, 1.0f, 0.5f);

        // Spike position
        if (spikesHaveCollision) {
            ctx.drawRect(x, spikeY, SPIKE_ACTIVE_WIDTH, 0x10, 1.0f, 0.3f, 0.0f);
        }

        // Chain and ceiling positions
        ctx.drawCross(x, chainY, 3, 0.5f, 0.5f, 1.0f);
        ctx.drawCross(x, ceilingY, 3, 0.5f, 0.5f, 1.0f);

        // Origin Y marker
        ctx.drawLine(x - 6, origY, x + 6, origY, 1.0f, 1.0f, 0.0f);

        // Label with type and offset info
        int typeIndex = subtype & 0x0F;
        String label = String.format("CStom:T%d off=%d/%d v=%d",
                typeIndex, (yOffset >> 8) & 0xFF, (maxFallDistance >> 8) & 0xFF, yVelocity);
        ctx.drawWorldLabel(x, y, -2, label, DebugColor.CYAN);
    }
}
