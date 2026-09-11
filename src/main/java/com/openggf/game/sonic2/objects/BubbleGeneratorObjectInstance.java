package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2Rng;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Bubble Generator (Object 0x24) - Underwater bubble spawner.
 * Invisible stationary object positioned in water that periodically spawns
 * rising bubble objects. Large bubbles can be breathed by the player.
 * <p>
 * Based on Obj24 (loc_1F9C0) from s2.asm (lines 44828-44927).
 * <p>
 * ROM State Machine:
 * The generator uses a complex state machine with multiple counters:
 * <ul>
 *   <li>objoff_32 (byte): Burst counter - decrements each burst, controls large bubble mode</li>
 *   <li>objoff_33 (byte): Reset value for burst counter (from subtype bits 0-6)</li>
 *   <li>objoff_34 (byte): Bubble count within current burst (counts down 0-5)</li>
 *   <li>objoff_36 (word): State flag - 0=ready for new burst, non-zero=spawning, bit 7=large bubble mode</li>
 *   <li>objoff_38 (word): Frame timer between spawns (0-31 for inter-bubble, +128-255 for inter-burst)</li>
 *   <li>objoff_3C (long): Pointer to current sequence in byte_1FAF0</li>
 * </ul>
 * <p>
 * Behavior:
 * <ol>
 *   <li>When objoff_36 == 0 (no active burst) and timer expires, start new burst</li>
 *   <li>Pick random burst size (0-5 bubbles) and one of 4 sequence tables</li>
 *   <li>Decrement burst counter; if underflows, enable large bubble mode (bit 7)</li>
 *   <li>Spawn bubbles one at a time with 0-31 frame delays between each</li>
 *   <li>In large bubble mode: 25% chance for breathable bubble, extra chance on last bubble</li>
 *   <li>After burst completes, add 128-255 frame delay before next burst</li>
 * </ol>
 */
public class BubbleGeneratorObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final int GENERATOR_ANIMATION_TICKS = 16;
    private static final int[] GENERATOR_FRAMES = {14, 15};

    // Bubble sequence table (byte_1FAF0 from ROM, line 45055 of s2.asm)
    // 18-entry overlapping table with 4 sequences at offsets 0, 4, 8, 12
    // ROM uses: andi.w #$C,d1 to select offset (0, 4, 8, or 12)
    // Each sequence provides 6 bubble subtypes (0=tiny, 1=small, 2=large)
    // Sequences overlap by 2 entries to save ROM space:
    //   Seq0 @ offset 0:  positions 0-5   = {0, 1, 0, 0, 0, 0}
    //   Seq1 @ offset 4:  positions 4-9   = {0, 0, 1, 0, 0, 0}
    //   Seq2 @ offset 8:  positions 8-13  = {0, 0, 0, 1, 0, 1}
    //   Seq3 @ offset 12: positions 12-17 = {0, 1, 0, 0, 1, 0}
    private static final int[] BUBBLE_SEQUENCE_TABLE = {
        0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0
    };

    // ROM objoff_32: Burst counter (decrements each burst, triggers large bubble mode on underflow)
    private int burstCounter;

    // ROM objoff_33: Reset value for burst counter (from subtype bits 0-6)
    private int burstCounterReset;

    // ROM objoff_34: Bubble count remaining in current burst (counts down)
    private int bubblesRemainingInBurst;

    // ROM objoff_36: State flag
    // Bit 0-6: 0=ready for new burst, 1=active burst
    // Bit 7: Large bubble mode enabled
    private int stateFlags;

    // ROM objoff_38: Frame timer (counts down)
    private int frameTimer;

    // ROM objoff_3C: Current sequence table offset (0, 4, 8, or 12)
    private int sequenceOffset;
    private int displayFrame = GENERATOR_FRAMES[0];
    private boolean visible;
    private boolean romRenderOnScreen;

    /**
     * Whether the previous pass reached the ROM's {@code DisplaySprite} call at
     * {@code loc_1FACE} (docs/s2disasm/s2.asm:45368-45376). {@code BuildSprites}
     * only rewrites {@code render_flags} bit 7 for objects that queued
     * themselves, so a pass that returned without displaying leaves the flag
     * exactly as it was -- including {@code Obj24_Init}'s starting
     * {@code $84} (:45209), which is why a freshly placed generator counts down
     * on its own first pass whatever the camera is doing.
     */
    private boolean romDisplayedLastPass;

    // Bit 6 of objoff_36: Used to track if large bubble already spawned this burst
    private static final int FLAG_LARGE_SPAWNED = 0x40;
    private static final int FLAG_LARGE_MODE = 0x80;
    private static final int FLAG_ACTIVE_BURST = 0x01;

    public BubbleGeneratorObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // ROM: andi.w #$7F,d0 / move.b d0,objoff_32(a0) / move.b d0,objoff_33(a0)
        int subtypeBits = spawn.subtype() & 0x7F;
        this.burstCounterReset = subtypeBits;
        this.burstCounter = subtypeBits;

        // Initial state: ready for new burst
        this.stateFlags = 0;
        this.frameTimer = 0;
        this.bubblesRemainingInBurst = 0;
        this.sequenceOffset = 0;
        this.romRenderOnScreen = true;
    }

    @Override
    public BubbleGeneratorObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BubbleGeneratorObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        visible = false;

        // ROM Level_MainLoop (docs/s2disasm/s2.asm:5088-5111) runs RunObjects
        // (:5094) BEFORE JmpTo_DeformBgLayer (:5097) and calls BuildSprites
        // (:5110) after that deform. So the object pass at frame N and
        // BuildSprites at frame N-1 both see the same camera position, and the
        // render_flags bit 7 this pass reads is BuildSprites' verdict on that
        // camera -- not on the one the previous object pass saw. Evaluating the
        // bounds here rather than carrying the previous pass's evaluation is
        // what puts the flag in phase; carrying it left this generator one frame
        // behind the ROM every time the camera brought it back on screen.
        if (romDisplayedLastPass) {
            romRenderOnScreen = isWithinRenderSpriteBounds(
                    getOnScreenHalfWidth(), getOnScreenHalfHeight());
        }
        romDisplayedLastPass = false;
        boolean observedRomRenderOnScreen = romRenderOnScreen;

        // ROM: Check if generator is above water (only spawn when underwater)
        if (services().currentLevel() != null) {
            WaterSystem waterSystem = services().waterSystem();
            int zoneId = services().currentLevel().getZoneIndex();
            int actId = services().currentAct();

            if (waterSystem.hasWater(zoneId, actId)) {
                int waterY = waterSystem.getWaterLevelY(zoneId, actId);
                // ROM: cmp.w y_pos(a0),d0 / bhs.w loc_1FACE
                if (spawn.y() <= waterY) {
                    // Generator is above or at water level - don't spawn
                    return;
                }
            }
        }

        // ROM loc_1FACE: Obj24 generator deletes by the standard chunk-aligned
        // out_of_range window, then DisplaySprite refreshes render_flags bit 7.
        if (!isInRangeAt(spawn.x())) {
            setDestroyed(true);
            return;
        }

        displayFrame = GENERATOR_FRAMES[(vIntRunCount / GENERATOR_ANIMATION_TICKS) & 1];

        // ROM state machine logic (loc_1F9C0)
        if ((stateFlags & FLAG_ACTIVE_BURST) == 0) {
            // No active burst - check if timer expired to start new burst
            // ROM gates the ready-state timer on render_flags.on_screen from the
            // previous DisplaySprite pass. An off-screen generator still displays
            // this frame, but it does not count down or start a burst until the
            // refreshed render flag is observable on a later object pass.
            if (!observedRomRenderOnScreen) {
                refreshRomRenderFlag();
                return;
            }
            // ROM: tst.w objoff_36(a0) / bne.s loc_1FA22,
            //      subq.w #1,objoff_38(a0) / bpl.w loc_1FAC2.
            // A zero timer spends one frame counting down to -1 before a burst starts.
            frameTimer--;
            if (frameTimer >= 0) {
                refreshRomRenderFlag();
                return;
            }
            // Timer expired, start new burst
            startNewBurst();
        } else {
            // Active burst - continue spawning
            // ROM: loc_1FA22: subq.w #1,objoff_38(a0) / bpl.w loc_1FAC2
            frameTimer--;
            if (frameTimer >= 0) {
                refreshRomRenderFlag();
                return;
            }
            // Timer expired, spawn next bubble
            spawnNextBubble();
        }
        refreshRomRenderFlag();
    }

    // ROM loc_1FACE falls through to DisplaySprite while the generator is under
    // water (docs/s2disasm/s2.asm:45368-45376); the paths that return before it
    // leave render_flags untouched, so only this one arms next frame's refresh.
    private void refreshRomRenderFlag() {
        romDisplayedLastPass = true;
        visible = isWithinRenderSpriteBounds(getOnScreenHalfWidth(), getOnScreenHalfHeight());
    }

    /**
     * Starts a new bubble burst sequence.
     * ROM: loc_1F9E8 (lines 44844-44867)
     */
    private void startNewBurst() {
        // ROM: jsr (RandomNumber).l / move.w d0,d1 / andi.w #7,d0 / cmpi.w #6,d0 / bhs.s loc_1F9E8
        var rng = services().rng();
        var burstChoice = Sonic2Rng.nextBubbleBurstChoice(rng);

        // ROM: move.b d0,objoff_34(a0)
        bubblesRemainingInBurst = burstChoice.bubbleCount();

        // ROM: andi.w #$C,d1 / lea (byte_1FAF0).l,a1 / adda.w d1,a1 / move.l a1,objoff_3C(a0)
        sequenceOffset = burstChoice.sequenceOffset(); // 0, 4, 8, or 12

        // ROM: subq.b #1,objoff_32(a0) / bpl.s BranchTo_loc_1FA2A
        burstCounter--;
        if (burstCounter < 0) {
            // ROM: move.b objoff_33(a0),objoff_32(a0) / bset #7,objoff_36(a0)
            burstCounter = burstCounterReset;
            stateFlags |= FLAG_LARGE_MODE;
        }

        // Mark burst as active and start spawning
        // ROM: move.w #1,objoff_36(a0) (preserves bit 7)
        stateFlags = (stateFlags & FLAG_LARGE_MODE) | FLAG_ACTIVE_BURST;

        // Spawn first bubble immediately
        spawnNextBubble();
    }

    /**
     * Spawns the next bubble in the current burst.
     * ROM: loc_1FA2A (lines 44870-44919)
     */
    private void spawnNextBubble() {
        if (services().objectManager() == null) {
            return;
        }
        var rng = services().rng();

        // ROM: jsr (RandomNumber).l / andi.w #$1F,d0 / move.w d0,objoff_38(a0)
        // Set timer for next bubble (0-31 frames)
        frameTimer = rng.nextBits(0x1F);

        // ROM: Calculate spawn position with random X offset
        // jsr (RandomNumber).l / andi.w #$F,d0 / subq.w #8,d0 / add.w d0,x_pos(a1)
        int spawnX = spawn.x() + Sonic2Rng.nextMaskedOffset(rng, 0x0F, -8);
        int spawnY = spawn.y();

        // ROM: Get subtype from sequence table
        // moveq #0,d0 / move.b objoff_34(a0),d0 / movea.l objoff_3C(a0),a2 / move.b (a2,d0.w),subtype(a1)
        int tableIndex = sequenceOffset + bubblesRemainingInBurst;
        if (tableIndex >= BUBBLE_SEQUENCE_TABLE.length) {
            tableIndex = tableIndex % BUBBLE_SEQUENCE_TABLE.length;
        }
        int bubbleSubtype = BUBBLE_SEQUENCE_TABLE[tableIndex];

        // ROM: Check for large bubble mode (bit 7 of objoff_36)
        // btst #7,objoff_36(a0) / beq.s loc_1FAA6
        if ((stateFlags & FLAG_LARGE_MODE) != 0) {
            // ROM: 25% chance to spawn large breathable bubble
            // jsr (RandomNumber).l / andi.w #3,d0 / bne.s loc_1FA92
            if (rng.nextBits(3) == 0) {
                // ROM: bset #6,objoff_36(a0) / bne.s loc_1FAA6 / move.b #2,subtype(a1)
                if ((stateFlags & FLAG_LARGE_SPAWNED) == 0) {
                    stateFlags |= FLAG_LARGE_SPAWNED;
                    bubbleSubtype = 2; // Large breathable bubble
                }
            }

            // ROM: Extra chance for large bubble on last bubble of burst
            // tst.b objoff_34(a0) / bne.s loc_1FAA6
            if (bubblesRemainingInBurst == 0) {
                // ROM: bset #6,objoff_36(a0) / bne.s loc_1FAA6 / move.b #2,subtype(a1)
                if ((stateFlags & FLAG_LARGE_SPAWNED) == 0) {
                    stateFlags |= FLAG_LARGE_SPAWNED;
                    bubbleSubtype = 2; // Large breathable bubble
                }
            }
        }

        // ROM loc_1FA2A writes the byte_1FAF0 type list entry (or the large
        // override's #2) straight into subtype(a1), and Obj24_Init copies that
        // to anim(a0) (docs/s2disasm/s2.asm:45217). Pass the ROM subtype through
        // unchanged: the old translation of subtype 2 to a size of 5 existed only
        // to satisfy BubbleObjectInstance's since-removed `bubbleSize >= 3`
        // breathable test, and it discarded the very byte that selects the
        // Ani_obj24 script.
        int finalBubbleSize = bubbleSubtype;

        spawnFreeChild(() -> new BubbleObjectInstance(spawnX, spawnY, finalBubbleSize, 0, true));

        // ROM: Decrement bubble counter
        // subq.b #1,objoff_34(a0) / bpl.s loc_1FAC2
        bubblesRemainingInBurst--;

        if (bubblesRemainingInBurst < 0) {
            // Burst complete - add long delay before next burst
            // ROM: jsr (RandomNumber).l / andi.w #$7F,d0 / addi.w #$80,d0 / add.w d0,objoff_38(a0)
            frameTimer += rng.nextBits(0x7F) + 0x80;

            // ROM: clr.w objoff_36(a0)
            stateFlags = 0; // Clear all flags including large mode and large spawned
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visible || isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.BUBBLES);
        if (renderer == null) {
            return;
        }

        renderer.drawFrameIndex(displayFrame, spawn.x(), spawn.y(), false, false);
    }

    @Override
    public int getPriorityBucket() {
        return 1; // ROM: move.b #1,priority(a0) at line 44742
    }
}
