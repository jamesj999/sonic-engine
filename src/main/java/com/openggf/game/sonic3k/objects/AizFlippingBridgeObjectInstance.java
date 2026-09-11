package com.openggf.game.sonic3k.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * Object 0x2B - AIZ Flipping Bridge (Sonic 3 &amp; Knuckles).
 * <p>
 * A bridge composed of 8 segments that cyclically flip through mapping frames,
 * creating a cascading wave effect. Each segment is only solid when its mapping
 * frame is &ge; 5; frames 0-4 represent the "flipped away" state.
 * <p>
 * Subtype byte encoding:
 * <ul>
 *   <li>Bit 7: height table selection. 0 = steep ({@code word_2AAF2}), 1 = shallow ({@code word_2AB72})</li>
 *   <li>Bits 6-4: animation period (timer reload value)</li>
 *   <li>Bits 3-0: added to 0x10 to form the max frame wrap limit</li>
 * </ul>
 * If the object's status bit 0 (X-flip) is set, the animation direction is
 * reversed (-1) and the max frame is decremented by 1.
 * <p>
 * ROM references: Obj_AIZFlippingBridge (sonic3k.asm:58872), loc_2AA56,
 * sub_2AA7E, sub_2ABF2, sub_2AC08, SolidObjSloped2.
 */
public class AizFlippingBridgeObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, RewindRecreatable {

    // ===== Constants =====
    private static final int SEGMENT_COUNT = 8;
    private static final int SEGMENT_SPACING = 0x20;    // 32px between segments
    private static final int HALF_WIDTH = 0x80;          // 128px half-width (width_pixels)
    private static final int HEIGHT_PIXELS = 4;
    private static final int PRIORITY = 4;               // $200 → bucket 4
    private static final int SOLID_FRAME_THRESHOLD = 5;  // frames < 5 are non-solid

    // ===== Height tables from ROM (word_2AAF2 and word_2AB72) =====
    // 128 bytes each: 8 segments × 16 bytes per segment.
    // Indexed by (playerRelativeX / 2). Values are signed Y-offsets from obj_y:
    // surface_y = obj_y - height_value.

    // @formatter:off
    // word_2AAF2: steep slope, step = 8px per segment, range -28 to +28
    private static final byte[] HEIGHT_TABLE_STEEP = buildHeightTable(
            new int[]{-28, -20, -12, -4, 4, 12, 20, 28});

    // word_2AB72: shallow slope, step = 4px per segment, range -12 to +16
    private static final byte[] HEIGHT_TABLE_SHALLOW = buildHeightTable(
            new int[]{-12, -8, -4, 0, 4, 8, 12, 16});
    // @formatter:on

    private static byte[] negateTable(byte[] src) {
        byte[] dst = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            dst[i] = (byte) -src[i];
        }
        return dst;
    }

    private static byte[] buildHeightTable(int[] segmentValues) {
        byte[] table = new byte[128];
        for (int seg = 0; seg < 8; seg++) {
            byte val = (byte) segmentValues[seg];
            for (int i = 0; i < 16; i++) {
                table[seg * 16 + i] = val;
            }
        }
        return table;
    }

    // ===== Instance fields =====
    private int x;
    private int y;
    private boolean hFlip;
    private final byte[] heightTable;
    private final byte[] negatedHeightTable;
    private int animPeriod;        // ROM: $25(a0) — timer reload value
    private int maxFrame;          // ROM: $37(a0) — wrap limit
    private int animDirection;     // ROM: $36(a0) — +1 or -1

    private int animTimer;               // ROM: anim_frame_timer
    private final int[] segmentX = new int[SEGMENT_COUNT];
    private final int[] segmentY = new int[SEGMENT_COUNT];
    private final int[] segmentFrames = new int[SEGMENT_COUNT];
    private final List<PlayableEntity> standingPlayers = new ArrayList<>(2);
    private boolean childSlotReserved;

    public AizFlippingBridgeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "AIZFlippingBridge");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;

        int subtype = spawn.subtype() & 0xFF;

        // Bit 7: height table selection
        // ROM: bpl.s loc_2A98E; move.l #word_2AB72,$32(a0)
        this.heightTable = (subtype & 0x80) != 0 ? HEIGHT_TABLE_SHALLOW : HEIGHT_TABLE_STEEP;
        this.negatedHeightTable = negateTable(this.heightTable);

        // Bits 6-4: animation period
        // ROM: lsr.b #4,d1; andi.w #7,d1; move.b d1,$25(a0)
        this.animPeriod = (subtype >> 4) & 7;
        // ROM object RAM starts clear; sub_2AA7E immediately decrements
        // anim_frame_timer from 0 to -1 on the first loc_2AA56 update and
        // advances the child map frames before reloading $25(a0)
        // (sonic3k.asm:58946-58969).
        this.animTimer = 0;

        // Bits 3-0: max frame = low_nib + 16
        // ROM: andi.w #$F,d0; addi.w #$10,d0; move.b d0,$37(a0)
        int mf = (subtype & 0x0F) + 0x10;

        // ROM: if x-flipped, direction = -1 and maxFrame -= 1
        if (hFlip) {
            this.animDirection = -1;
            this.maxFrame = mf - 1;
        } else {
            this.animDirection = 1;
            this.maxFrame = mf;
        }

        initSegments(subtype);
    }

    @Override
    public AizFlippingBridgeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new AizFlippingBridgeObjectInstance(ctx.spawn());
    }

    private void initSegments(int subtype) {
        boolean isSteep = (subtype & 0x80) == 0;

        // ROM: subi.w #$70,d3 → x_start = x - 112
        int xStart = x - 0x70;
        // ROM: addi.w #$20,d2 → y_start = y + 32 (steep) or y + 16 (shallow)
        int yStart = isSteep ? (y + 0x20) : (y - 0x10 + 0x20); // y+32 steep, y+16 shallow
        int yStep = isSteep ? 8 : 4;

        // ROM: initial frames: 0, 2, 4, 6, 8, 10, 12, 14 (wrapping at 16)
        int frame = 0;
        for (int i = 0; i < SEGMENT_COUNT; i++) {
            segmentX[i] = xStart + i * SEGMENT_SPACING;
            segmentY[i] = yStart - i * yStep;
            segmentFrames[i] = frame;
            frame = (frame + 2) & 0x0F; // ROM: addq.w #2,d1; andi.w #$F,d1
        }
    }

    // ===== SolidObjectProvider / SlopedSolidProvider =====

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(HALF_WIDTH, HEIGHT_PIXELS, HEIGHT_PIXELS + 1);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        // ROM sub_2AC08's fresh landing branch gates against current x_pos
        // using d1=$80, samples the slope, then checks the segment map frame.
        // It does not re-narrow through Solid_Landed's width_pixels path.
        return true;
    }

    @Override
    public byte[] getSlopeData() {
        // The ROM height table uses the convention: surface_y = obj_y - height_value,
        // where negative values mean the surface is ABOVE obj_y (higher on screen).
        // The engine's SlopedSolidProvider convention is the opposite: negative slope
        // values = surface is LOWER. Negate to match the engine convention.
        // (Cf. Sonic1BridgeObjectInstance.updateSlopeData() which also negates.)
        return negatedHeightTable;
    }

    @Override
    public boolean isSlopeFlipped() {
        return hFlip;
    }

    @Override
    public int getSlopeBaseline() {
        // Height table values are absolute offsets from obj_y, not relative to a flat baseline.
        return 0;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        // ROM: sub_2AC08 checks segment's mapping frame >= 5 before landing/riding.
        // Determine which segment the player is over and check solidity.
        int relX = player.getCentreX() - x + HALF_WIDTH;
        if (relX < 0 || relX >= HALF_WIDTH * 2) {
            return false;
        }
        // ROM: lsr.w #5,d4 → segment_index = relative_x / 32
        int segIndex = relX >> 5;
        if (segIndex >= SEGMENT_COUNT) {
            segIndex = SEGMENT_COUNT - 1;
        }
        return segmentFrames[segIndex] >= SOLID_FRAME_THRESHOLD;
    }

    // ===== SolidObjectListener =====

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (contact.standing() && !standingPlayers.contains(player)) {
            standingPlayers.add(player);
        }
    }

    // ===== Update =====

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        reserveNativeMultispriteSlot();
        updateAnimation(vIntRunCount);

        // Check riding players: eject any standing on non-solid segments.
        // ROM: sub_2AC08 riding check — if segment frame < 5, eject.
        for (int i = standingPlayers.size() - 1; i >= 0; i--) {
            PlayableEntity player = standingPlayers.get(i);
            if (player.getAir()) {
                standingPlayers.remove(i);
                continue;
            }
            int relX = player.getCentreX() - x + HALF_WIDTH;
            if (relX < 0 || relX >= HALF_WIDTH * 2) {
                ejectPlayer(player);
                standingPlayers.remove(i);
                continue;
            }
            int segIndex = Math.min(relX >> 5, SEGMENT_COUNT - 1);
            if (segmentFrames[segIndex] < SOLID_FRAME_THRESHOLD) {
                ejectPlayer(player);
                standingPlayers.remove(i);
            }
        }
    }

    private void reserveNativeMultispriteSlot() {
        if (childSlotReserved) {
            return;
        }
        childSlotReserved = true;
        if (services().objectManager() != null) {
            // Obj_AIZFlippingBridge uses AllocateObjectAfterCurrent for the
            // loc_2AA78 eight-subsprite draw owner. The Java bridge renders the
            // segments itself, but that child still occupies one native SST slot.
            services().objectManager().allocateChildSlotsAfter(spawn, 1, getSlotIndex());
        }
    }

    /**
     * Updates the animation timer and cycles segment mapping frames.
     * ROM: sub_2AA7E
     */
    private void updateAnimation(int vIntRunCount) {
        // ROM: subq.b #1,anim_frame_timer(a0); bpl.s return
        animTimer--;
        if (animTimer >= 0) {
            return;
        }
        // ROM: move.b $25(a0),anim_frame_timer(a0)
        animTimer = animPeriod;

        for (int i = 0; i < SEGMENT_COUNT; i++) {
            if (animDirection >= 0) {
                // ROM: add.b d1,(a2); cmp.b (a2),d2; bhi.s skip; move.b #0,(a2)
                segmentFrames[i] += animDirection;
                if (segmentFrames[i] >= maxFrame) {
                    segmentFrames[i] = 0;
                }
            } else {
                // ROM: add.b d1,(a2); bcs.s skip; move.b d2,(a2)
                segmentFrames[i] += animDirection;
                if (segmentFrames[i] < 0) {
                    segmentFrames[i] = maxFrame;
                }
            }
        }

        // ROM: play sfx_GlideLand every 8th frame when on-screen.
        // Condition: (Level_frame_counter_low_byte + 3) & 7 == 0, i.e., frameCounter & 7 == 5
        if (isOnScreen(0) && ((vIntRunCount + 3) & 7) == 0) {
            services().playSfx(Sonic3kSfx.GLIDE_LAND.id);
        }
    }

    /**
     * Ejects a player from the bridge, setting them airborne.
     * ROM: loc_2AC2A — bclr Status_OnObj, bset Status_InAir
     */
    private void ejectPlayer(PlayableEntity player) {
        player.setOnObject(false);
        player.setPushing(false);
        player.setAir(true);
    }

    // ===== Rendering =====

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.AIZ_FLIPPING_BRIDGE);
        if (renderer == null || !renderer.isReady()) return;

        for (int i = 0; i < SEGMENT_COUNT; i++) {
            renderer.drawFrameIndex(segmentFrames[i], segmentX[i], segmentY[i], hFlip, false);
        }
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
    }
}
