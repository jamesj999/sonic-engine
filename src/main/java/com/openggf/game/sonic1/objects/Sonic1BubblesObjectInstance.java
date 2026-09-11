package com.openggf.game.sonic1.objects;
import com.openggf.game.PlayableEntity;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.sonic1.constants.Sonic1ObjectIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x64 - Bubbles (Labyrinth Zone).
 * <p>
 * Dual-mode object: regular rising bubbles OR static bubble maker spawner.
 * <p>
 * <b>Regular bubble</b> (subtype 0x00-0x7F): Rises upward through water with
 * horizontal wobble. Grows from small to full-size through animation. Once
 * full-size (mapping frame 6), can be inhaled by Sonic to restore air.
 * Bursts when reaching the water surface.
 * <p>
 * <b>Bubble maker</b> (subtype 0x80+): Static spawner on the level floor.
 * Periodically creates small and large bubble child objects. Frequency
 * controlled by lower 7 bits of subtype. Renders as animated floor vent.
 * <p>
 * Reference: docs/s1disasm/_incObj/64 Bubbles.asm
 */
public class Sonic1BubblesObjectInstance extends AbstractObjectInstance
        implements SpawnRewindRecreatable {

    // ========================================================================
    // Routine IDs (from Bub_Index dispatch table)
    // ========================================================================

    private static final int ROUTINE_INIT     = 0;
    private static final int ROUTINE_ANIMATE  = 2;
    private static final int ROUTINE_CHKWATER = 4;
    private static final int ROUTINE_DISPLAY  = 6;
    private static final int ROUTINE_DELETE   = 8;
    private static final int ROUTINE_MAKER    = 10;

    // ========================================================================
    // Animation Constants
    // ========================================================================

    /** Animation end action: advance obRoutine by 2 (afRoutine = 0xFC). */
    private static final int AF_ROUTINE = 0xFC;
    /** Animation end action: loop from beginning (afEnd = 0xFF). */
    private static final int AF_END = 0xFF;

    /**
     * Animation scripts from docs/s1disasm/_anim/Bubbles.asm.
     * Each entry: {duration, frame0, frame1, ..., end_action}.
     */
    private static final int[][] ANIM_SCRIPTS = {
        // Anim 0 (.small):  duration $E, frames 0,1,2, afRoutine
        {0x0E, 0, 1, 2, AF_ROUTINE},
        // Anim 1 (.medium): duration $E, frames 1,2,3,4, afRoutine
        {0x0E, 1, 2, 3, 4, AF_ROUTINE},
        // Anim 2 (.large):  duration $E, frames 2,3,4,5,6, afRoutine
        {0x0E, 2, 3, 4, 5, 6, AF_ROUTINE},
        // Anim 3 (.incroutine): duration 4, afRoutine
        {4, AF_ROUTINE},
        // Anim 4 (.incroutine): duration 4, afRoutine
        {4, AF_ROUTINE},
        // Anim 5 (.burst): duration 4, frames 6,7,8, afRoutine
        {4, 6, 7, 8, AF_ROUTINE},
        // Anim 6 (.bubmaker): duration $F, frames $13,$14,$15, afEnd
        {0x0F, 0x13, 0x14, 0x15, AF_END},
    };

    // ========================================================================
    // Physics Constants
    // ========================================================================

    /** Rise velocity in 8.8 fixed point. From disassembly: move.w #-$88,obVelY(a0) */
    private static final int RISE_VELOCITY = -0x88;

    /**
     * Wobble data table (128 bytes, signed) matching Drown_WobbleData.
     * Creates smooth horizontal sinusoidal oscillation as bubble rises.
     * S1 REV01 uses positive peak +3, negative peak -4.
     */
    private static final int[] WOBBLE_DATA = {
        0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2,
        2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2,
        2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0,
        0, -1, -1, -1, -1, -1, -2, -2, -2, -2, -2, -3, -3, -3, -3, -3,
        -3, -3, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4,
        -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -4, -3,
        -3, -3, -3, -3, -3, -3, -2, -2, -2, -2, -2, -1, -1, -1, -1, -1
    };

    // ========================================================================
    // Collision Constants (Bub_ChkSonic)
    // ========================================================================

    /**
     * Collision box: X range is [bubbleX - 0x10, bubbleX + 0x10] (32px wide).
     * From disassembly: subi.w #$10,d1 ... addi.w #$20,d1
     */
    private static final int COLLISION_X_RANGE = 0x10;

    /**
     * Collision box: Y range is [bubbleY, bubbleY + 0x10] (16px tall, downward only).
     * From disassembly: cmp.w d0,d1 ... addi.w #$10,d1
     */
    private static final int COLLISION_Y_RANGE = 0x10;

    // ========================================================================
    // Bubble Maker Constants
    // ========================================================================

    /**
     * Bubble type production sequence from Bub_BblTypes.
     * 0 = small bubble, 1 = large bubble.
     * From disassembly: dc.b 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0
     */
    private static final int[] BUBBLE_TYPE_TABLE = {
        0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0
    };

    /** Active width for bubble render. From disassembly: move.b #$10,obActWid(a0) */
    private static final int ACTIVE_WIDTH = 0x10;
    /** S1 BuildSprites .assumeHeight band when obRender bit 4 is clear. */
    private static final int ASSUME_HEIGHT_RENDER_MARGIN = 0x20;

    /** Render priority. From disassembly: move.b #1,obPriority(a0) */
    private static final int PRIORITY = 1;

    /** Debug color for bubble maker (blue). */
    private static final DebugColor DEBUG_COLOR_MAKER = new DebugColor(50, 100, 200);
    /** Debug color for regular bubble (light blue). */
    private static final DebugColor DEBUG_COLOR_BUBBLE = new DebugColor(100, 180, 255);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Current routine index (0, 2, 4, 6, 8, 10). */
    private int routine;

    /** Whether this is a bubble maker (subtype >= 0x80). */
    private boolean isMaker;

    // ---- Regular bubble state ----

    /** Position as 16.16 fixed point for sub-pixel accuracy. */
    private int posX16;
    private int posY16;

    /** Original X position for wobble offset calculation. (bub_origX) */
    private int origX;

    /** Current display coordinates (after wobble applied). */
    private int displayX;
    private int displayY;

    /** Wobble angle counter (0-255, indexed via & 0x7F). (obAngle) */
    private int wobbleAngle;

    /** Flag set when bubble reaches full size and can be inhaled. (bub_inhalable) */
    private boolean inhalable;

    // ---- Animation state ----

    /** Current animation ID. (obAnim) */
    private int animId;
    /** Previous animation ID for change detection. (obPrevAnim) */
    private int prevAnimId = -1;
    /** Current position within animation frame sequence. (obAnimFrame) */
    private int animFrameIndex;
    /** Animation timer countdown. (obTimeFrame) */
    private int animTimer;
    /** Current mapping frame to render. (obFrame) */
    private int mappingFrame;
    private transient int lastCollisionPlayerX = Integer.MIN_VALUE;
    private transient int lastCollisionPlayerY = Integer.MIN_VALUE;
    private transient boolean lastCollisionPlayerInWater;
    private transient boolean lastCollisionHit;
    private boolean regularBubbleNeedsInitialRandom;
    private int spawnSubtype;
    private final String spawnDebug;

    // ---- Bubble maker state ----

    /** Spawn timer countdown. (bub_time) */
    private int spawnTime;
    /** Spawn frequency reset value. (bub_freq) */
    private int spawnFreq;
    /** Bubble type counter within production sequence. (objoff_34) */
    private int typeCounter;
    /** Production state flags. Bit 7 = production active, bit 6 = large-spawned flag. (objoff_36) */
    private int productionFlags;
    /** Delay counter between spawn bursts. (objoff_38) */
    private int delayCounter;
    /** Current offset into BUBBLE_TYPE_TABLE. (objoff_3C) */
    private int typeTableOffset;
    /**
     * ROM Bub_Main writes obRender=$84 before falling through to the first
     * bubble or maker update, so the first tick passes the render gate even
     * before BuildSprites has established the live on-screen bit.
     */
    private boolean initialRenderGate;
    /**
     * Retained obRender bit 7 as last written by the sprite build path. Obj64
     * maker/child routines read this previous render flag before the next
     * DisplaySprite call can refresh it.
     */
    private boolean renderOnScreen;
    private boolean makerHasDisplayed;

    // ========================================================================
    // Constructor
    // ========================================================================

    public Sonic1BubblesObjectInstance(ObjectSpawn spawn) {
        this(spawn, "");
    }

    private Sonic1BubblesObjectInstance(ObjectSpawn spawn, String spawnDebug) {
        super(spawn, "Bubbles");

        int subtype = spawn.subtype();
        this.spawnSubtype = subtype;
        this.spawnDebug = spawnDebug;

        if (subtype >= 0x80) {
            // ---- Bubble Maker mode ----
            isMaker = true;
            // andi.w #$7F,d0 ; read only last 7 bits
            int freq = subtype & 0x7F;
            spawnFreq = freq;
            spawnTime = freq;
            // move.b #6,obAnim(a0)
            animId = 6;
            initialRenderGate = true;

            displayX = spawn.x();
            displayY = spawn.y();
            origX = spawn.x();
            posX16 = spawn.x() << 16;
            posY16 = spawn.y() << 16;

            // ObjPosLoad only writes the SST entry; Bub_Main promotes this
            // to the maker routine on the object's first ExecuteObjects tick.
            renderOnScreen = true;
            routine = ROUTINE_INIT;
        } else {
            // ---- Regular Bubble mode ----
            isMaker = false;
            spawnFreq = 0;

            // move.b d0,obAnim(a0) ; subtype selects animation (0=small, 1=medium, 2=large)
            animId = subtype;

            // move.w obX(a0),bub_origX(a0)
            origX = spawn.x();

            // move.w #-$88,obVelY(a0) ; float bubble upwards
            posX16 = spawn.x() << 16;
            posY16 = spawn.y() << 16;

            displayX = spawn.x();
            displayY = spawn.y();

            // S1 Obj64 calls RandomNumber in Bub_Main, when the child slot
            // actually executes. FindFreeObj can allocate a lower slot than the
            // maker, so constructor-time RNG would run one object pass early.
            wobbleAngle = 0;
            regularBubbleNeedsInitialRandom = true;

            // ObjPosLoad/FindFreeObj children begin at Bub_Main and run the
            // inline setup when ExecuteObjects reaches their slot.
            initialRenderGate = true;
            renderOnScreen = true;
            routine = ROUTINE_INIT;
        }
    }

    // ========================================================================
    // Update
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (routine) {
            case ROUTINE_INIT -> updateInit(player);
            case ROUTINE_ANIMATE -> updateAnimate(player);
            case ROUTINE_CHKWATER -> updateChkWater(player, consumeInitialRenderGate());
            case ROUTINE_DISPLAY -> updateDisplay();
            case ROUTINE_DELETE -> setDestroyed(true);
            case ROUTINE_MAKER -> updateBubbleMaker(player);
        }
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (isDestroyed()) {
            return;
        }
        if (isMaker && !makerHasDisplayed) {
            // Bub_BblMaker only refreshes obRender bit 7 when it reaches the
            // DisplaySprite tail (docs/s1disasm/s1disasm/_incObj/64 LZ Air
            // Bubbles.asm:211-219). While the maker is above water, the ROM's
            // initial obRender=$84 persists until that first display pass.
            return;
        }
        if (routine == ROUTINE_DISPLAY || getWaterLevel() < displayY) {
            updateRenderOnScreenFlag();
        }
    }

    private void updateInit(AbstractPlayableSprite player) {
        if (isMaker) {
            // docs/s1disasm/_incObj/64 LZ Air Bubbles.asm: Bub_Main sets
            // obRoutine=$0A, obAnim=6, bub_time/freq, then branches directly
            // to Bub_BblMaker on the same object tick.
            routine = ROUTINE_MAKER;
            updateBubbleMaker(player);
        } else {
            // Non-maker bubbles fall through from Bub_Main to Bub_Animate.
            routine = ROUTINE_ANIMATE;
            updateAnimate(player);
        }
    }

    // ========================================================================
    // Routine 2: Bub_Animate
    // ========================================================================

    private void updateAnimate(AbstractPlayableSprite player) {
        boolean initialGate = consumeInitialRenderGate();
        if (regularBubbleNeedsInitialRandom) {
            // ROM: Bub_Main jsr (RandomNumber).l / move.b d0,obAngle(a0)
            regularBubbleNeedsInitialRandom = false;
            wobbleAngle = services().rng().nextByte();
        }

        // AnimateSprite with Ani_Bub
        animateSprite();

        // cmpi.b #6,obFrame(a0) ; is bubble full-size?
        // bne.s Bub_ChkWater
        if (mappingFrame == 6) {
            // move.b #1,bub_inhalable(a0)
            inhalable = true;
        }

        // Fall through to Bub_ChkWater
        updateChkWater(player, initialGate);
    }

    // ========================================================================
    // Routine 4: Bub_ChkWater
    // ========================================================================

    private void updateChkWater(AbstractPlayableSprite player, boolean initialGate) {
        int waterY = getWaterLevel();

        // cmp.w obY(a0),d0 ; is bubble at/above water?
        // blo.s .wobble ; branch if waterY < bubbleY (bubble underwater)
        if (waterY >= displayY) {
            // Bubble has reached water surface → burst
            startBurstAndDisplay();
            return;
        }

        // ---- Wobble logic (bubble is underwater) ----

        // move.b obAngle(a0),d0 / addq.b #1,obAngle(a0)
        int wobbleIndex = wobbleAngle & 0x7F;
        wobbleAngle = (wobbleAngle + 1) & 0xFF;

        // lea (Drown_WobbleData).l,a1 / move.b (a1,d0.w),d0
        int wobbleOffset = WOBBLE_DATA[wobbleIndex];

        // ext.w d0 / add.w bub_origX(a0),d0 / move.w d0,obX(a0)
        displayX = origX + wobbleOffset;

        // Check for Sonic collision if bubble is inhalable
        if (inhalable && player != null) {
            if (checkSonicCollision(player)) {
                // Player collected the bubble
                onBubbleCollected(player);
                startBurstAndDisplay();
                return;
            }
        }

        // SpeedToPos - apply velocity to position (16.16 fixed point)
        // Only Y velocity is set (rising upward)
        posY16 += RISE_VELOCITY << 8;
        displayY = posY16 >> 16;

        // tst.b obRender(a0) / bpl.s .delete
        if (!initialGate && !renderOnScreen) {
            setDestroyed(true);
            return;
        }

        // DisplaySprite (render will be handled by appendRenderCommands)
        updateRenderOnScreenFlag();
    }

    // ========================================================================
    // Routine 6: Bub_Display (burst animation)
    // ========================================================================

    private void updateDisplay() {
        // AnimateSprite with burst animation
        animateSprite();

        // tst.b obRender(a0) / bpl.s .delete
        if (!renderOnScreen) {
            setDestroyed(true);
            return;
        }
        updateRenderOnScreenFlag();
    }

    // ========================================================================
    // Routine A: Bub_BblMaker
    // ========================================================================

    private void updateBubbleMaker(AbstractPlayableSprite player) {
        int waterY = getWaterLevel();
        var rng = services().rng();
        boolean initialGate = consumeInitialRenderGate();

        if (productionFlags != 0) {
            // Already in production - continue spawn cycle
            delayCounter--;
            if (delayCounter >= 0) {
                // Still waiting - animate and display
                animateMaker(waterY);
                return;
            }
        } else {
            // Not in production yet
            // cmp.w obY(a0),d0 ; is bubble maker underwater?
            // bhs.w .chkdel ; branch if waterY >= makerY (not underwater)
            if (waterY >= displayY) {
                // Not underwater (water at or below maker) - just check deletion
                checkMakerDeletion(waterY);
                return;
            }

            // tst.b obRender(a0) / bpl.w .chkdel
            if (!initialGate && !renderOnScreen) {
                checkMakerDeletion(waterY);
                return;
            }

            // subq.w #1,objoff_38(a0) / bpl.w .loc_12914
            delayCounter--;
            if (delayCounter >= 0) {
                animateMaker(waterY);
                return;
            }

            // move.w #1,objoff_36(a0) ; start production
            productionFlags = 1;

            // jsr (RandomNumber).l / move.w d0,d1
            // ROM extracts both typeCounter and tableOffset from SAME random call
            int rawRng;
            do {
                rawRng = rng.nextRaw();
            } while ((rawRng & 7) >= 6);

            typeCounter = rawRng & 7;

            // docs/s1disasm/_incObj/64 Bubbles.asm:141-151 copies the
            // same RandomNumber word to d1, then uses andi.w #$C,d1 for the
            // Bub_BblTypes pointer. Do not shift; bits 2-3 select offsets
            // 0, 4, 8, or 12, while bits 0-2 still seed objoff_34.
            typeTableOffset = rawRng & 0x0C;

            // subq.b #1,bub_time(a0)
            spawnTime--;
            if (spawnTime < 0) {
                // move.b bub_freq(a0),bub_time(a0)
                spawnTime = spawnFreq;
                // bset #7,objoff_36(a0) ; mark large spawn enabled
                productionFlags |= 0x80;
            }
        }

        // ---- Spawn a bubble ----
        spawnBubble();

        // Check if spawn counter exhausted
        typeCounter--;
        if (typeCounter < 0) {
            // Add random long delay before next production cycle
            // jsr (RandomNumber).l / andi.w #$7F,d0 / addi.w #$80,d0
            int extraDelay = rng.nextBits(0x7F) + 0x80;
            delayCounter += extraDelay;
            productionFlags = 0;
        }

        // Animate and display
        animateMaker(waterY);
    }

    private boolean consumeInitialRenderGate() {
        boolean gate = initialRenderGate;
        initialRenderGate = false;
        return gate;
    }

    /**
     * Spawns a single bubble child object.
     * From Bub_BblMaker spawn section (lines 165-196 of disassembly).
     */
    private void spawnBubble() {
        if (services().objectManager() == null) {
            return;
        }

        // jsr (RandomNumber).l / andi.w #$1F,d0
        var rng = services().rng();
        delayCounter = rng.nextBits(0x1F);

        // docs/s1disasm/_incObj/64 LZ Air Bubbles.asm:169-170:
        //   bsr.w FindFreeObj / bne.s .fail
        // The ROM consumes the spawn-delay RandomNumber above (objoff_38), then
        // tests FindFreeObj. When the dynamic SST pool is full it branches to
        // .fail WITHOUT consuming the xOffset (line 173) or the large-bubble
        // 25%-override (line 184) RandomNumber and without spawning a child; the
        // caller's objoff_34 decrement (+ optional long-delay RandomNumber) still
        // runs. The engine's spawnFreeChild silently drops the bubble when the
        // pool is full but still consumed those extra RandomNumber draws here,
        // pushing the shared GameRng ahead of the ROM and mispositioning later
        // maker bubbles (LZ air-bubble production-cadence drift, pitfall P24).
        // Mirror the ROM bail: return before the xOffset/override RNG and the
        // spawn, leaving the caller to run the unconditional .fail tail.
        if (!hasFreeDynamicSlot()) {
            return;
        }

        // Determine bubble subtype from type table
        int tableIndex = typeTableOffset + typeCounter;
        int tableSubtype;
        int bubbleSubtype;
        if (tableIndex >= 0 && tableIndex < BUBBLE_TYPE_TABLE.length) {
            tableSubtype = BUBBLE_TYPE_TABLE[tableIndex];
        } else {
            tableSubtype = 0;
        }
        bubbleSubtype = tableSubtype;

        // docs/s1disasm/_incObj/64 Bubbles.asm:166-177 consumes the spawn
        // delay random word, then the X-offset random word, before any
        // large-bubble override test at lines 182-196. Keep that order so
        // the subtype-2 cadence stays aligned with the ROM.
        int xOffset = rng.nextBits(0x0F) - 8;
        int spawnX = origX + xOffset;
        int productionFlagsBeforeOverride = productionFlags;
        int overrideRoll = -1;
        String overrideReason = "none";

        // Check for large bubble override
        // btst #7,objoff_36(a0) / beq.s .fail
        if ((productionFlags & 0x80) != 0) {
            // docs/s1disasm/_incObj/64 Bubbles.asm:181-187 masks the
            // RandomNumber word with #3, so only the low two bits drive this
            // 25% large-bubble override. Consuming/testing three bits shifts
            // later Obj64 subtype cadence.
            overrideRoll = rng.nextBits(0x03);
            if (overrideRoll == 0) {
                if ((productionFlags & 0x40) == 0) {
                    productionFlags |= 0x40;
                    bubbleSubtype = 2;
                    overrideReason = "rng";
                }
            }

            // docs/s1disasm/_incObj/64 Bubbles.asm:182-196 keeps the
            // forced last-bubble type-2 check inside the bit-7 large-mode
            // branch. Ordinary bursts can end on their table subtype.
            if (typeCounter == 0 && (productionFlags & 0x40) == 0) {
                productionFlags |= 0x40;
                bubbleSubtype = 2;
                overrideReason = "last";
            }
        }

        ObjectSpawn childSpawn = new ObjectSpawn(
                spawnX, spawn.y(),
                Sonic1ObjectIds.BUBBLES,
                bubbleSubtype,
                0, false, 0);
        int typeCounterBeforeDecrement = typeCounter;
        int productionFlagsAfterOverride = productionFlags;
        String childDebug = String.format(
                "maker=@%04X,%04X tblOff=%02X type=%d idx=%02X table=%d flags=%02X->%02X roll=%d override=%s xoff=%d",
                origX & 0xFFFF,
                spawn.y() & 0xFFFF,
                typeTableOffset & 0xFF,
                typeCounterBeforeDecrement,
                tableIndex & 0xFF,
                tableSubtype,
                productionFlagsBeforeOverride & 0xFF,
                productionFlagsAfterOverride & 0xFF,
                overrideRoll,
                overrideReason,
                xOffset);
        spawnFreeChild(() -> new Sonic1BubblesObjectInstance(childSpawn, childDebug));
    }

    /**
     * ROM FindFreeObj probe (docs/s1disasm/_incObj/64 LZ Air Bubbles.asm:169):
     * true when a free dynamic SST slot is available. Returns true when no
     * object pool is present (e.g. headless physics tests) so the spawn path
     * proceeds as before.
     */
    private boolean hasFreeDynamicSlot() {
        var om = services().objectManager();
        return om == null || om.hasFreeDynamicSlot();
    }

    /**
     * Animate the bubble maker sprite and check for deletion.
     */
    private void animateMaker(int waterY) {
        // Animate with Ani_Bub (anim 6 = bubmaker)
        animateSprite();

        // out_of_range.w DeleteObject
        if (!isInRange()) {
            setDestroyed(true);
            return;
        }

        // cmp.w obY(a0),d0 / blo.w DisplaySprite
        // Only display if maker is underwater
        // (if above water, just rts - don't render)
        if (waterY < displayY) {
            makerHasDisplayed = true;
            updateRenderOnScreenFlag();
        }
    }

    /**
     * Check maker deletion when not in production.
     */
    private void checkMakerDeletion(int waterY) {
        if (!isInRange()) {
            setDestroyed(true);
            return;
        }
        if (waterY < displayY) {
            makerHasDisplayed = true;
            updateRenderOnScreenFlag();
        }
    }

    private void updateRenderOnScreenFlag() {
        renderOnScreen = isWithinRenderSpriteBounds(
                ACTIVE_WIDTH, ASSUME_HEIGHT_RENDER_MARGIN);
    }

    // ========================================================================
    // Burst / Collection
    // ========================================================================

    /**
     * Start burst animation when bubble reaches water surface or is collected.
     * From disassembly .burst section.
     */
    private void startBurst() {
        // move.b #6,obRoutine(a0) ; goto Bub_Display next
        routine = ROUTINE_DISPLAY;
        // addq.b #3,obAnim(a0) ; burst animation
        animId += 3;
        if (animId >= ANIM_SCRIPTS.length) {
            // Safety: if animation ID is out of range, just delete
            setDestroyed(true);
            return;
        }
        // Force animation reset
        prevAnimId = -1;
    }

    private void startBurstAndDisplay() {
        startBurst();
        if (!isDestroyed()) {
            // docs/s1disasm/s1disasm/_incObj/64 LZ Air Bubbles.asm:
            // the .burst path writes routine 6, increments obAnim, then bra.w Bub_Display.
            updateDisplay();
        }
    }

    /**
     * Handle Sonic collecting the bubble (restoring air).
     * From disassembly lines 82-100 of 64 Bubbles.asm.
     */
    private void onBubbleCollected(AbstractPlayableSprite player) {
        // bsr.w ResumeMusic ; cancel countdown music
        // move.w #sfx_Bubble,d0 / jsr (QueueSound2).l
        try {
            services().playSfx(Sonic1Sfx.BUBBLE.id);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }

        // clr.w obVelX(a1) / clr.w obVelY(a1) / clr.w obInertia(a1)
        // move.b #id_GetAir,obAnim(a1) ; use bubble-collecting animation
        // ... additional status bit clearing
        player.replenishAir();
    }

    // ========================================================================
    // Sonic Collision Check (Bub_ChkSonic)
    // ========================================================================

    /**
     * Check if Sonic has touched this bubble.
     * From Bub_ChkSonic subroutine (lines 226-251 of disassembly).
     * <p>
     * X range: [bubbleX - 0x10, bubbleX + 0x10]
     * Y range: [bubbleY, bubbleY + 0x10] (downward only from bubble position)
     */
    private boolean checkSonicCollision(AbstractPlayableSprite player) {
        // tst.b (f_playerctrl).w / bmi.s .no_collision
        // (skip if player is in a controlled state - we approximate this)

        lastCollisionPlayerX = player.getCentreX();
        lastCollisionPlayerY = player.getCentreY();
        lastCollisionPlayerInWater = player.isInWater();
        lastCollisionHit = false;

        if (!lastCollisionPlayerInWater) {
            return false;
        }

        // S1 Obj64 runs Bub_ChkSonic from the bubble object's ExecuteObjects
        // slot and reads v_player+obX/obY before the bubble's own SpeedToPos
        // step (docs/s1disasm/s1disasm/_incObj/64 LZ Air Bubbles.asm:77-105,
        // 226-251). In the engine's S1 inline order, player physics has already
        // run before dynamic object slots, so the live centre is the ROM
        // v_player obX/obY sample; the delayed follower-history buffer is only
        // for Sonic_RecordPosition-style consumers.
        int playerX = lastCollisionPlayerX;
        int playerY = lastCollisionPlayerY;

        // X check: subi.w #$10,d1 / cmp.w d0,d1 / bhs.s .no
        //   bhs branches when bubbleLeft >= playerX → no collision
        //   addi.w #$20,d1 / cmp.w d0,d1 / blo.s .no
        //   blo branches when bubbleRight < playerX → no collision
        // Collision: playerX > bubbleLeft AND playerX <= bubbleRight
        int bubbleLeft = displayX - COLLISION_X_RANGE;
        int bubbleRight = displayX + COLLISION_X_RANGE;
        if (playerX <= bubbleLeft || playerX > bubbleRight) {
            return false;
        }

        // Y check: cmp.w d0,d1 / bhs.s .no
        //   bhs branches when bubbleY >= playerY → no collision
        //   addi.w #$10,d1 / cmp.w d0,d1 / blo.s .no
        //   blo branches when bubbleY+16 < playerY → no collision
        // Collision: playerY > bubbleY AND playerY <= bubbleY + 0x10
        if (playerY <= displayY || playerY > displayY + COLLISION_Y_RANGE) {
            return false;
        }

        lastCollisionHit = true;
        return true;
    }

    @Override
    public String traceDebugDetails() {
        StringBuilder sb = new StringBuilder();
        if (isMaker) {
            return String.format("r=%02X anim=%d freq=%d time=%d prod=%02X type=%d delay=%d tbl=%02X render=%s",
                    routine, animId, spawnFreq, spawnTime, productionFlags & 0xFF, typeCounter,
                    delayCounter, typeTableOffset & 0xFF, renderOnScreen);
        }
        sb.append(String.format("r=%02X anim=%d frm=%d idx=%d t=%d inh=%s",
                routine, animId, mappingFrame, animFrameIndex, animTimer, inhalable));
        sb.append(String.format(" sub=%d", spawnSubtype));
        if (!spawnDebug.isBlank()) {
            sb.append(" ").append(spawnDebug);
        }
        if (lastCollisionPlayerX != Integer.MIN_VALUE) {
            sb.append(String.format(" col=%s p=@%04X,%04X water=%s",
                    lastCollisionHit ? "hit" : "miss",
                    lastCollisionPlayerX & 0xFFFF,
                    lastCollisionPlayerY & 0xFFFF,
                    lastCollisionPlayerInWater));
        }
        return sb.toString();
    }

    // ========================================================================
    // Animation Engine (AnimateSprite equivalent)
    // ========================================================================

    /**
     * Processes one frame of animation, matching ROM AnimateSprite behavior.
     * Decrements timer; when expired, advances to next frame in current script.
     * Handles afRoutine (advance obRoutine) and afEnd (loop) commands.
     */
    private void animateSprite() {
        if (animId < 0 || animId >= ANIM_SCRIPTS.length) {
            return;
        }

        int[] script = ANIM_SCRIPTS[animId];
        int duration = script[0];

        // Check for animation change
        if (animId != prevAnimId) {
            prevAnimId = animId;
            animFrameIndex = 0;
            animTimer = 0;
        }

        // Decrement timer
        animTimer--;
        if (animTimer >= 0) {
            return; // Still displaying current frame
        }

        // Timer expired - reload and advance
        animTimer = duration;

        // Read next frame byte from script
        int frameDataIndex = 1 + animFrameIndex;
        if (frameDataIndex >= script.length) {
            // Shouldn't happen with well-formed scripts, treat as end
            return;
        }

        int frameByte = script[frameDataIndex];

        if (frameByte == AF_ROUTINE) {
            // Advance to next routine
            routine += 2;
            return;
        }

        if (frameByte == AF_END) {
            // Loop back to first frame
            animFrameIndex = 0;
            if (script.length > 1) {
                mappingFrame = script[1];
            }
            return;
        }

        // Normal frame - set mapping frame and advance
        mappingFrame = frameByte;
        animFrameIndex++;
    }

    // ========================================================================
    // Water Level Helper
    // ========================================================================

    /**
     * Gets the current water level Y position.
     * Returns Integer.MAX_VALUE if no water (bubble should always be "underwater").
     */
    private int getWaterLevel() {
        if (services().currentLevel() == null) {
            return Integer.MAX_VALUE;
        }

        var waterSystem = services().waterSystem();
        // Use feature zone/act so SBZ3 (ROM zone LZ act 3) resolves to the
        // water config stored under ZONE_SBZ act 2.
        int zoneId = services().featureZoneId();
        int actId = services().featureActId();

        if (waterSystem.hasWater(zoneId, actId)) {
            // Every water read in Obj64 is `move.w (v_waterpos1).w,d0` -- the
            // height *including* the surface sway: Bub_ChkWater
            // (docs/s1disasm/_incObj/64 LZ Air Bubbles.asm:66), Bub_BblMaker's
            // underwater gate (:154) and the shared display tail (:241).
            // v_waterpos1 is v_waterpos2 plus `(v_oscillate+2) >> 1`
            // (docs/s1disasm/_inc/LZWaterFeatures.asm:23-28), which is what
            // getGameplayWaterLevelY returns; getWaterLevelY is v_waterpos2
            // alone and runs up to fifteen pixels low. The oscillator byte
            // overshoots its $10 middle value before the direction flips, so it
            // spans 0..31 across the recorded lz1_2 stream and the shifted term
            // spans 0..15.
            return waterSystem.getGameplayWaterLevelY(zoneId, actId);
        }
        return Integer.MAX_VALUE;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        // Bubble maker only renders when underwater
        // cmp.w obY(a0),d0 / blo.w DisplaySprite ; display if waterY < makerY
        if (isMaker) {
            int waterY = getWaterLevel();
            if (waterY >= displayY) {
                // Not underwater (water at or below maker) - don't render
                return;
            }
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_BUBBLES);
        if (renderer == null) return;

        renderer.drawFrameIndex(mappingFrame, displayX, displayY, false, false);
    }

    @Override
    public int getX() {
        return displayX;
    }

    @Override
    public int getY() {
        return displayY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        DebugColor color = isMaker ? DEBUG_COLOR_MAKER : DEBUG_COLOR_BUBBLE;

        if (isMaker) {
            ctx.drawCross(displayX, displayY, 8, 0.2f, 0.4f, 0.8f);
            ctx.drawWorldLabel(displayX, displayY, -1,
                    String.format("BubMaker freq=%d prod=%02X", spawnFreq, productionFlags),
                    color);
        } else {
            ctx.drawCross(displayX, displayY, 4, 0.4f, 0.7f, 1.0f);
            String state = inhalable ? "INHALABLE" : "growing";
            ctx.drawWorldLabel(displayX, displayY, -1,
                    String.format("Bubble r=%d f=%d %s", routine, mappingFrame, state),
                    color);

            // Draw collision box when inhalable (centered at bubble, half-widths)
            if (inhalable) {
                ctx.drawRect(
                        displayX,
                        displayY + COLLISION_Y_RANGE / 2,
                        COLLISION_X_RANGE,
                        COLLISION_Y_RANGE / 2,
                        0.4f, 0.7f, 1.0f);
            }
        }
    }
}
