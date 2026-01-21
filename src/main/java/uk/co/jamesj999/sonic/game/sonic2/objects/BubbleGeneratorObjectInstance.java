package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.WaterSystem;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.Random;

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
public class BubbleGeneratorObjectInstance extends AbstractObjectInstance {

    // Bubble sequence table (byte_1FAF0 from ROM)
    // 4 sequences of up to 6 entries each, indexed by (sequence_offset + bubble_index)
    // Values 0,1,2 correspond to bubble subtypes (0=tiny, 1=small, 2=large/breathable)
    private static final int[] BUBBLE_SEQUENCE_TABLE = {
        0, 1, 0, 0,  // Sequence 0 (offset 0)
        0, 0, 1, 0,  // Sequence 1 (offset 4)
        0, 0, 0, 1,  // Sequence 2 (offset 8)
        0, 1, 0, 0,  // Sequence 3 (offset 12)
        1, 0         // Overflow area (offset 16-17)
    };

    private final Random random = new Random();

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
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // ROM: Check if generator is above water (only spawn when underwater)
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager != null && levelManager.getCurrentLevel() != null) {
            WaterSystem waterSystem = WaterSystem.getInstance();
            int zoneId = levelManager.getCurrentLevel().getZoneIndex();
            int actId = levelManager.getCurrentAct();

            if (waterSystem.hasWater(zoneId, actId)) {
                int waterY = waterSystem.getWaterLevelY(zoneId, actId);
                // ROM: cmp.w y_pos(a0),d0 / bhs.w loc_1FACE
                if (spawn.y() <= waterY) {
                    // Generator is above or at water level - don't spawn
                    return;
                }
            }
        }

        // ROM: Check if on screen (only spawn when visible)
        if (!isOnScreen(640)) {
            return;
        }

        // ROM state machine logic (loc_1F9C0)
        if ((stateFlags & FLAG_ACTIVE_BURST) == 0) {
            // No active burst - check if timer expired to start new burst
            // ROM: tst.w objoff_36(a0) / bne.s loc_1FA22
            if (frameTimer > 0) {
                frameTimer--;
                return;
            }
            // Timer expired, start new burst
            startNewBurst();
        } else {
            // Active burst - continue spawning
            // ROM: loc_1FA22: subq.w #1,objoff_38(a0) / bpl.w loc_1FAC2
            frameTimer--;
            if (frameTimer >= 0) {
                return;
            }
            // Timer expired, spawn next bubble
            spawnNextBubble();
        }
    }

    /**
     * Starts a new bubble burst sequence.
     * ROM: loc_1F9E8 (lines 44844-44867)
     */
    private void startNewBurst() {
        // ROM: jsr (RandomNumber).l / move.w d0,d1 / andi.w #7,d0 / cmpi.w #6,d0 / bhs.s loc_1F9E8
        int bubbleCount;
        do {
            bubbleCount = random.nextInt(8);
        } while (bubbleCount >= 6);

        // ROM: move.b d0,objoff_34(a0)
        bubblesRemainingInBurst = bubbleCount;

        // ROM: andi.w #$C,d1 / lea (byte_1FAF0).l,a1 / adda.w d1,a1 / move.l a1,objoff_3C(a0)
        sequenceOffset = random.nextInt(4) * 4; // 0, 4, 8, or 12

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
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        // ROM: jsr (RandomNumber).l / andi.w #$1F,d0 / move.w d0,objoff_38(a0)
        // Set timer for next bubble (0-31 frames)
        frameTimer = random.nextInt(32);

        // ROM: Calculate spawn position with random X offset
        // jsr (RandomNumber).l / andi.w #$F,d0 / subq.w #8,d0 / add.w d0,x_pos(a1)
        int spawnX = spawn.x() + (random.nextInt(16) - 8);
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
            if (random.nextInt(4) == 0) {
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

        // Create bubble with appropriate size
        // Subtype 0 = tiny, 1 = small, 2 = large (breathable)
        int bubbleSize = bubbleSubtype;
        if (bubbleSubtype == 2) {
            bubbleSize = 5; // Large breathable bubble needs size >= 3
        }

        int wobbleAngle = random.nextInt(256);
        BubbleObjectInstance bubble = new BubbleObjectInstance(spawnX, spawnY, bubbleSize, wobbleAngle);
        levelManager.getObjectManager().addDynamicObject(bubble);

        // ROM: Decrement bubble counter
        // subq.b #1,objoff_34(a0) / bpl.s loc_1FAC2
        bubblesRemainingInBurst--;

        if (bubblesRemainingInBurst < 0) {
            // Burst complete - add long delay before next burst
            // ROM: jsr (RandomNumber).l / andi.w #$7F,d0 / addi.w #$80,d0 / add.w d0,objoff_38(a0)
            frameTimer += (random.nextInt(128) + 128);

            // ROM: clr.w objoff_36(a0)
            stateFlags = 0; // Clear all flags including large mode and large spawned
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object - no rendering
    }

    @Override
    public int getPriorityBucket() {
        return 1; // ROM: move.b #1,priority(a0) at line 44742
    }
}
