package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnCoordinateZeroPairRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * ARZ Bubble (Object 0x24 routine 4) - Rising breathable bubble.
 * Spawned by BubbleGeneratorObjectInstance.
 * <p>
 * Based on Obj24_Rising from s2.asm (lines 44868-44987).
 * <p>
 * Physics:
 * - Rises upward at -0x88 velocity (8.8 fixed point = ~0.53 pixels/frame)
 * - Horizontal sine-wave wobble using 128-byte lookup table
 * - Pops when reaching water surface
 * <p>
 * Player Interaction:
 * - Large bubbles (mapping_frame >= 6) can be breathed by player
 * - When touched, restores player's air and plays inhaling sound
 */
public class BubbleObjectInstance extends AbstractObjectInstance
        implements SpawnCoordinateZeroPairRewindRecreatable {

    // Rise velocity in 8.8 fixed point (-0x88 = ~-0.53 pixels/frame upward)
    private static final int RISE_VELOCITY = -0x88;

    // Wobble data table (128 bytes, signed) - matches ROM Obj24_WobbleData
    // This creates a smooth horizontal oscillation as the bubble rises
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

    // Touch collision half-sizes for breathable bubbles (ROM Touch_Sizes entry)
    private static final int COLLISION_HALF_WIDTH = 16;
    private static final int COLLISION_HALF_HEIGHT = 16;

    /**
     * ROM {@code objoff_2E} condition: {@code loc_1F924} runs AnimateSprite and
     * then {@code cmpi.b #6,mapping_frame(a0) / bne / move.b #1,objoff_2E(a0)}
     * (docs/s2disasm/s2.asm:45231-45236). Mapping frame 6 is the only thing that
     * makes a bubble inhalable, and only one animation script ever reaches it.
     */
    private static final int BREATHABLE_FRAME_THRESHOLD = 6;

    /**
     * {@code Ani_obj24} (docs/s2disasm/s2.asm:45478-45496). Each entry is the
     * ROM script: element 0 is the frame duration byte, the rest are mapping
     * frames, and the script ends at the ROM's {@code $FC} (advance routine) --
     * after which {@code mapping_frame} simply holds its last value.
     * <p>
     * Only script 2 -- the large bubble, the one the maker's
     * {@code move.b #2,subtype(a1)} override produces -- runs up to frame 6, and
     * it takes four frame steps at {@code $E} to get there. That climb is the
     * ROM's entire inhalable delay; there is no timer anywhere that encodes it.
     */
    private static final int[][] ANI_OBJ24 = {
            {0x0E, 0, 1, 2},          // 0: small
            {0x0E, 1, 2, 3, 4},       // 1: medium
            {0x0E, 2, 3, 4, 5, 6},    // 2: large / inhalable
    };

    // Position as 16.16 fixed point
    private int posX16;
    private int posY16;

    // Base X position for wobble calculation
    private int baseX;

    // Wobble angle (0-255, wraps around) - ROM uses only low byte
    private int wobbleAngle;
    private boolean wobbleAnglePendingRng;

    // Current display position
    private int displayX;
    private int displayY;

    /**
     * ROM {@code anim(a0)}, written from {@code subtype} by
     * {@code loc_1F90A} ({@code move.b d0,anim(a0)}, docs/s2disasm/s2.asm:45217).
     * 0 = small, 1 = medium, 2 = large. This is the ROM's own subtype byte, not
     * a size scale.
     */
    private int animId;

    // Mapping frame for rendering (ROM mapping_frame(a0))
    private int mappingFrame;

    /** ROM {@code anim_frame} -- index into the current script's frame list. */
    private int animFrameIndex;

    /** ROM {@code anim_frame_duration}. */
    private int animTimer;

    /** ROM {@code objoff_2E} -- set once, when mapping_frame reaches 6. */
    private boolean inhalable;

    // Whether this bubble has been breathed (collected)
    private boolean breathed;

    // ROM render_flags bit 7 as observed by Obj24 after ObjectMove. Obj24_Init
    // starts with render_flags=$84 (docs/s2disasm/s2.asm:45209), so a
    // just-allocated offscreen bubble survives one execution before the next
    // Render_Sprites result can delete it.
    private boolean romRenderOnScreen;

    /**
     * Whether the previous pass reached {@code DisplaySprite} at {@code loc_1F988}
     * (docs/s2disasm/s2.asm:45265-45267). {@code BuildSprites} only rewrites
     * {@code render_flags} bit 7 for objects that queued themselves that frame,
     * so a bubble that has not executed yet -- allocated during one object pass
     * but first run on the next -- has never been queued and keeps
     * {@code Obj24_Init}'s {@code $84} until its own first pass draws it.
     */
    private boolean romDisplayedLastPass;

    /**
     * Creates a rising bubble at the specified position.
     *
     * @param x           X position (world coordinates)
     * @param y           Y position (world coordinates)
     * @param bubbleSize  ROM {@code subtype}: 0 = small, 1 = medium, 2 = large
     * @param wobbleAngle Initial wobble angle (0-255)
     */
    public BubbleObjectInstance(int x, int y, int bubbleSize, int wobbleAngle) {
        this(x, y, bubbleSize, wobbleAngle, false);
    }

    BubbleObjectInstance(int x, int y, int bubbleSize, int wobbleAngle, boolean wobbleAnglePendingRng) {
        super(createDummySpawn(x, y), "Bubble");

        // Store position as 16.16 fixed point
        this.posX16 = x << 16;
        this.posY16 = y << 16;
        this.baseX = x;

        this.wobbleAngle = wobbleAngle & 0xFF;
        this.wobbleAnglePendingRng = wobbleAnglePendingRng;
        this.animId = bubbleSize;
        this.breathed = false;
        this.romRenderOnScreen = true;
        this.inhalable = false;

        // ROM Obj24_Init only writes anim(a0); AnimateSprite establishes
        // mapping_frame from the script on the bubble's first animation tick,
        // and every script's first frame equals its own index.
        this.animFrameIndex = 0;
        this.mappingFrame = script()[1];
        this.animTimer = 0;

        // Initial display position
        this.displayX = x;
        this.displayY = y;
    }

    private static ObjectSpawn createDummySpawn(int x, int y) {
        return new ObjectSpawn(x, y, 0x24, 0, 0, false, 0);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        boolean observedRomRenderOnScreen = romRenderOnScreen;
        if (breathed) {
            setDestroyed(true);
            return;
        }

        if (wobbleAnglePendingRng) {
            // Obj24_Init consumes RandomNumber for the child bubble's angle on
            // the child's own object pass, not in the generator that allocated it.
            wobbleAngle = services().rng().nextByte();
            wobbleAnglePendingRng = false;
        }

        // ROM routine 2 IS loc_1F924: AnimateSprite over Ani_obj24 and then the
        // cmpi.b #6,mapping_frame test that sets objoff_2E
        // (docs/s2disasm/s2.asm:45231-45236). It runs FIRST, before the water
        // check, the wobble and the collect box at loc_1F956, so the pass that
        // lifts mapping_frame to 6 is also the pass whose
        // `tst.b objoff_2E(a0) / beq` (:45257-45258) can already see it. Running
        // it at the end of update() instead delayed every inhale by one frame.
        animateSprite();

        // ROM loc_1F93E (docs/s2disasm/s2.asm:45238-45244) tests the water
        // surface against y_pos BEFORE the wobble and BEFORE ObjectMove, so the
        // value compared is the position the bubble held on entry to this pass.
        // Use getFeatureZoneId/ActId to match the keys WaterSystem stores configs
        // under (important for S1 SBZ3 which remaps from LZ).
        if (services().currentLevel() != null) {
            WaterSystem waterSystem = services().waterSystem();
            int zoneId = services().featureZoneId();
            int actId = services().featureActId();

            if (waterSystem.hasWater(zoneId, actId)) {
                int waterY = waterSystem.getWaterLevelY(zoneId, actId);
                if (displayY <= waterY) {
                    // Bubble reached surface - pop it
                    setDestroyed(true);
                    return;
                }
            }
        }

        // Apply wobble to X position
        // ROM loc_1F956 (docs/s2disasm/s2.asm:45247-45256):
        //   move.b angle(a0),d0 / addq.b #1,angle(a0) / andi.w #$7F,d0
        //   lea (Obj0A_WobbleData).l,a1 / move.b (a1,d0.w),d0 / ext.w d0
        //   add.w objoff_30(a0),d0 / move.w d0,x_pos(a0)
        // The table is indexed with the angle the pass STARTED with; the
        // increment lands in the SST for the next pass. Incrementing first put
        // every bubble one wobble step ahead of the ROM for its whole life.
        int wobbleIndex = wobbleAngle & 0x7F; // 128-entry table
        wobbleAngle = (wobbleAngle + 1) & 0xFF;
        int wobbleOffset = WOBBLE_DATA[wobbleIndex];
        displayX = baseX + wobbleOffset;

        if (!observedRomRenderOnScreen) {
            setDestroyed(true);
            return;
        }

        // ROM loc_1F956 (docs/s2disasm/s2.asm:45257-45259) runs the collect box
        // -- tst.b objoff_2E(a0) / beq.s loc_1F988 / bsr.w loc_1FB02 -- while
        // y_pos still holds this pass's entry position. ObjectMove only runs
        // afterwards, at loc_1F988 (:45263-45264), so the box the ROM tests is
        // always one rise step behind the position the frame ends on. Applying
        // the rise first made every inhale one frame late.
        if (player != null && isBreathable()) {
            checkPlayerCollision(player);
        }

        // ROM loc_1F988: jsrto JmpTo3_ObjectMove (docs/s2disasm/s2.asm:45263-45264).
        // ROM: move.w y_vel(a0),d0 / ext.l d0 / asl.l #8,d0 / add.l d0,y_pos(a0)
        if (!isDestroyed() && !breathed) {
            posY16 += RISE_VELOCITY << 8;

            // Calculate display Y from 16.16 fixed point
            displayY = posY16 >> 16;
        }

        // ROM loc_1F988 reaches DisplaySprite only on a pass that survived the
        // render_flags test (:45265-45267); that queueing is what lets the next
        // BuildSprites rewrite the bit.
        romDisplayedLastPass = true;
    }

    /**
     * {@code BuildSprites} runs after {@code RunObjects} and after
     * {@code DeformBgLayer} has scrolled the camera and republished
     * {@code Camera_X_pos_copy} (docs/s2disasm/s2.asm:5094-5110, 15174-15179),
     * so the verdict a pass reads was taken against that frame's camera at the
     * object's post-{@code ObjectMove} position. Publishing from inside
     * {@code update()} instead used the camera the object pass still held, one
     * deform older.
     * <p>
     * The guard is the other half: {@code BuildSprites} only rewrites the bit
     * for objects in the sprite queue, so a bubble allocated during one object
     * pass and first run on the next must keep {@code Obj24_Init}'s {@code $84}
     * through the frame it has not executed in. Without it the hook judges a
     * bubble that has never drawn, and every off-screen bubble dies one pass
     * early.
     */
    @Override
    public void refreshPostCameraRenderState() {
        if (!romDisplayedLastPass) {
            return;
        }
        romDisplayedLastPass = false;
        romRenderOnScreen = isWithinRenderSpriteBounds(
                getOnScreenHalfWidth(), getOnScreenHalfHeight());
    }

    /** The bubble's {@code Ani_obj24} script, clamped to the defined entries. */
    private int[] script() {
        return ANI_OBJ24[animId >= 0 && animId < ANI_OBJ24.length ? animId : 0];
    }

    /**
     * ROM {@code objoff_2E}. Set by {@code loc_1F924} when AnimateSprite has
     * advanced {@code mapping_frame} to 6 (docs/s2disasm/s2.asm:45231-45236), and
     * tested by {@code loc_1F956}'s {@code tst.b objoff_2E(a0) / beq} before the
     * collect box runs at all (:45257-45259).
     * <p>
     * The previous engine condition was {@code mappingFrame >= 6 || bubbleSize >= 3}.
     * Its first half was unreachable -- {@code mappingFrame} was seeded as
     * {@code min(bubbleSize, 5)} and only ever grew while {@code < 5} -- so the
     * live condition was a size test that is true the moment the bubble is
     * created. That made every large bubble inhalable on spawn and skipped the
     * ROM's entire inflate climb.
     */
    private boolean isBreathable() {
        return inhalable;
    }

    /**
     * {@code AnimateSprite} over {@code Ani_obj24}
     * (docs/s2disasm/s2.asm:45232). The duration byte is reloaded when the
     * counter runs out and the frame index advances; the ROM's {@code $FC}
     * terminator advances the routine to {@code loc_1F93E}, which no longer
     * animates, so the engine simply holds the last frame.
     */
    private void animateSprite() {
        int[] anim = script();
        // Anim_Run: subq.b #1,anim_frame_duration / bpl Anim_Wait
        // (docs/s2disasm/s2.asm:45xxx -> AnimateSprite, s2.asm:22867-22884).
        if (--animTimer >= 0) {
            return;
        }
        // move.b (a1),anim_frame_duration(a0) -- reload from the script head.
        animTimer = anim[0];
        // move.b 1(a1,d1.w),d0 with d1 = anim_frame: the frame list starts at
        // script byte 1, so anim_frame indexes from there.
        int idx = 1 + animFrameIndex;
        if (idx >= anim.length) {
            // The ROM's $FC terminator advances routine(a0) to loc_1F93E, which
            // no longer animates, so mapping_frame holds its last value.
            return;
        }
        mappingFrame = anim[idx];
        // addq.b #1,anim_frame(a0)
        animFrameIndex++;
        if (mappingFrame >= BREATHABLE_FRAME_THRESHOLD) {
            inhalable = true;
        }
    }

    /**
     * Checks for collision with the player and handles air restoration.
     * ROM uses an asymmetric collision box: 16 either side horizontally,
     * downward-only from bubble Y.
     * <p>
     * ROM collision logic ({@code loc_1FB0C}, docs/s2disasm/s2.asm:45422-45436):
     * X: (bubble_x - 16) < player_x <= (bubble_x + 16)
     * Y: bubble_y < player_y <= (bubble_y + 16)
     * <p>
     * The player's center must be BELOW the bubble (bubble above player),
     * but within 16 pixels. This is a downward-only collision box.
     */
    private void checkPlayerCollision(AbstractPlayableSprite player) {
        // Only interact if player is underwater
        if (!player.isInWater()) {
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM asymmetric box collision:
        // X: ±16 from bubble center (move.w x_pos(a0),d1 / subi.w #$10,d1 ... addi.w #$20,d1)
        // Y: downward-only - player must be BELOW bubble but within 16px
        //    ROM: cmp.w y_pos(a0),d1 / blo.s (bubble_y < player_y required)
        //    ROM: addi.w #$10,d1 / cmp.w d1,d0 (player_y < bubble_y + 16)
        int bubbleLeft = displayX - COLLISION_HALF_WIDTH;
        int bubbleRight = displayX + COLLISION_HALF_WIDTH;

        // ROM loc_1FB0C (docs/s2disasm/s2.asm:45422-45436). Every bound is a
        // signed word compare against a branch that RETURNS on failure, so the
        // surviving window is:
        //   subi.w #$10,d1 / cmp.w d0,d1 / bhs -> bubble_x - $10 <  player_x
        //   addi.w #$20,d1 / cmp.w d0,d1 / blo -> player_x <= bubble_x + $10
        //   cmp.w d0,d1    / bhs         -> bubble_y      <  player_y
        //   addi.w #$10,d1 / cmp.w d0,d1 / blo -> player_y <= bubble_y + $10
        // Both upper bounds are inclusive and both lower bounds are exclusive;
        // an exclusive upper Y bound loses exactly the frame where the player
        // sits on the bottom edge of the box, which is where this inhale lands.
        if (playerX > bubbleLeft && playerX <= bubbleRight &&
            playerY > displayY && playerY <= displayY + COLLISION_HALF_HEIGHT) {

            // Player touched the bubble - restore air
            player.replenishAir();

            // ROM also clears player velocity and locks movement for 35 frames
            // (lines 44966-44998), but this requires additional player state access
            // which we defer to replenishAir() for minimal invasiveness

            // Play inhaling sound
            try {
                services().playSfx(Sonic2Sfx.INHALING_BUBBLE.id);
            } catch (Exception e) {
                // Don't let audio failure break game logic
            }

            // Mark bubble as breathed (will be destroyed next frame)
            breathed = true;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || breathed) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.BUBBLES);
        if (renderer == null) return;

        // Clamp frame to valid range (0-5 for visual bubbles)
        int frameToRender = Math.min(mappingFrame, 5);

        renderer.drawFrameIndex(frameToRender, displayX, displayY, false, false);
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
        return 1; // ROM: move.b #1,priority(a1)
    }

    /**
     * Returns true if this bubble was breathed by the player.
     */
    public boolean wasBreathed() {
        return breathed;
    }
}
