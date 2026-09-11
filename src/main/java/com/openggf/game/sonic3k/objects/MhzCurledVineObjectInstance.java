package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * S3K SKL object $09 - MHZ curled vine.
 *
 * <p>ROM reference: {@code Obj_MHZCurledVine}. This ports the display-child
 * footprint and the initial top-solid range used before rider pressure uncurls
 * the generated segment surface.
 */
public final class MhzCurledVineObjectInstance extends AbstractObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, SpawnRewindRecreatable,
        RomObjectCodePointerProvider {
    // Obj_MHZCurledVine installs its main code pointer loc_3E8A2 (and display
    // child loc_3E9A6) at 0x0003Exxx, so word 0 of the stood-on object SST is
    // high word 0x0003 (docs/skdisasm/sonic3k.asm:82778-82797). S3K sub_13EFC
    // compares this against Tails_CPU_interact (sonic3k.asm:26816-26843).
    private static final int ROM_CODE_POINTER_HIGH_WORD = 0x0003;
    private static final int INITIAL_CURVE_STATE = 0xFFF40000;
    private static final int INITIAL_RANGE_WIDTH = 0x40;
    private static final int DISPLAY_HALF_HEIGHT = 0x30;
    private static final int SEGMENT_COUNT = 8;
    private static final int INITIAL_SEGMENT_X_OFFSET = 0x38;
    private static final int PRIORITY_BUCKET = 5;
    // Covers the widest ROM landing window (rangeWidth up to $80): the shared
    // resolver samples sampleX = (relX & rangeWidth-bound) >> 1, so a $80 window
    // reaches sampleX $3F.
    private static final int SLOPE_SAMPLE_COUNT = 0x40;
    private static final int[] CURVE_TARGETS = {
            0xFFF40000, 0xFFFA0000, 0xFFFB0000, 0xFFFC0000,
            0xFFFD0000, 0xFFFE0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000
    };
    private static final int[] RANGE_WIDTHS = {
            0x40, 0x40, 0x40, 0x40, 0x50, 0x60, 0x70, 0x80, 0x80
    };

    private final Map<AbstractPlayableSprite, Integer> standingSegmentIndices = new IdentityHashMap<>();
    private final int[] segmentXs = new int[SEGMENT_COUNT];
    private final int[] segmentYs = new int[SEGMENT_COUNT];
    private boolean hFlip;
    private boolean displayChildSlotReserved;
    private int curveState = INITIAL_CURVE_STATE;
    private int rangeWidth = INITIAL_RANGE_WIDTH;

    public MhzCurledVineObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MHZCurledVine");
        hFlip = (spawn.renderFlags() & 0x01) != 0;
        updateSegmentPositions();
    }

    @Override
    public int romObjectCodePointerHighWord() {
        return ROM_CODE_POINTER_HIGH_WORD;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        reserveDisplayChildSlot();
        int tableIndex = selectStandingTableIndex();
        rangeWidth = RANGE_WIDTHS[tableIndex];
        curveState = approachCurveState(curveState, CURVE_TARGETS[tableIndex]);
        updateSegmentPositions();
    }

    @Override
    public int getReservedChildSlotCount() {
        return 1;
    }

    private void reserveDisplayChildSlot() {
        if (displayChildSlotReserved || getSlotIndex() < 0) {
            return;
        }
        ObjectServices objectServices = tryServices();
        if (objectServices == null || objectServices.objectManager() == null) {
            return;
        }
        displayChildSlotReserved = true;
        // AllocateObjectAfterCurrent creates loc_3E9A6 in its own SST entry.
        // Rendering remains consolidated here, but that ROM slot must stay
        // occupied so later FindFree/FindNextFree allocations see the same pool.
        objectServices.objectManager().allocateChildSlotsAfter(
                spawn, getReservedChildSlotCount(), getSlotIndex());
    }

    @Override
    public SolidObjectParams getSolidParams() {
        int halfWidth = rangeWidth / 2;
        // ROM sub_3E9C6 (sonic3k.asm:82949-82953) accepts a standing player when
        // 0 <= (playerX - vineX + $40) < rangeWidth, i.e. the window
        // [vineX-$40, vineX-$40+rangeWidth) -- offset $40 LEFT of vineX, not
        // centred on it. The window's centre therefore sits at
        // vineX + (halfWidth - $40).
        int offsetX = halfWidth - INITIAL_RANGE_WIDTH;
        return SolidObjectParams.of(halfWidth, 0, 0, offsetX, 0);
    }

    /**
     * Per-x surface heights for new-landing detection, sampled by the shared
     * sloped-solid resolver at {@code sampleX = (playerX - vineX + $40) >> 1}.
     * <p>
     * ROM sub_3E9C6 -> loc_3EA1E (sonic3k.asm:82980-82996) lands a falling player
     * on the generated curl segment: {@code d0 = $1A(a2,(d0>>4)*6)} then
     * {@code subq.w #8,d0}, i.e. surface = {@code segmentY[d0>>4] - 8}. Encoding
     * each sample as {@code vineY - surface} (with {@link #getSlopeBaseline()} 0)
     * makes the resolver's {@code baseY = anchorY - slopeSample} reproduce that
     * per-segment surface, so the falling player lands on the raised curl instead
     * of passing through a flat surface at spawn.y. Continued riding still contours
     * through {@link #onSolidContact} (loc_3E9FA), which the resolver defers to once
     * the player is riding.
     */
    @Override
    public byte[] getSlopeData() {
        byte[] data = new byte[SLOPE_SAMPLE_COUNT];
        for (int sampleX = 0; sampleX < SLOPE_SAMPLE_COUNT; sampleX++) {
            int segment = segmentIndexForSample(sampleX);
            int surfaceY = segmentYs[segment] - 8;
            data[sampleX] = (byte) (spawn.y() - surfaceY);
        }
        return data;
    }

    @Override
    public int getSlopeBaseline() {
        // loc_3EA1E samples absolute segment heights, so no baseline is subtracted.
        return 0;
    }

    @Override
    public boolean isSlopeFlipped() {
        // Obj_MHZCurledVine generates its segment table in a single direction with
        // no render-flag mirroring (sonic3k.asm:82861-82893). The engine folds any
        // display h-flip into segmentIndexForSample() so the sampled surface stays
        // consistent with onSolidContact's contour index; the shared resolver must
        // not additionally mirror the sample.
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity player, SolidContact contact, int frameCounter) {
        if (!contact.standing() || !(player instanceof AbstractPlayableSprite sprite)) {
            return;
        }
        int romD0 = sprite.getCentreX() - spawn.x() + INITIAL_RANGE_WIDTH;
        int segmentIndex = segmentIndexForRomD0(romD0);
        // ROM gates the surface Y write on the object's per-player standing bit
        // (sub_3E9C6 `btst d6,status(a0)`, sonic3k.asm:82943):
        //   - bit already set (continued ride): loc_3E9FA (82963-82977) contours
        //     to y_pos = segmentY - 8 - y_radius every frame.
        //   - bit still clear (establishing frame): the fall-through loc_3EA1E
        //     landing jumps into loc_1E45A (sonic3k.asm:42004-42028), which snaps
        //     y_pos = surface - y_radius - 1 (surface = $1A(a2,d0*6) - 8), i.e.
        //     one pixel higher than the continued contour.
        // The shared sloped resolver applies its continued MvSonicOnSlope snap
        // (segmentY - 8 - y_radius) even on the establishing frame, so own the Y
        // here to reproduce loc_1E45A's -1 on the landing frame exactly.
        boolean continuedRide = standingSegmentIndices.containsKey(sprite);
        standingSegmentIndices.put(sprite, segmentIndex);
        int surfaceY = segmentYs[segmentIndex] - 8;
        int contourY = surfaceY - sprite.getYRadius() - (continuedRide ? 0 : 1);
        NativePositionOps.writeYPosPreserveSubpixel(sprite, contourY);
    }

    @Override
    public void onSolidContactCleared(PlayableEntity player, int frameCounter) {
        if (player instanceof AbstractPlayableSprite sprite) {
            standingSegmentIndices.remove(sprite);
        }
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean usesCollisionHalfWidthForTopLanding() {
        return true;
    }

    @Override
    public boolean usesPlatformObjectLandingSnap() {
        return false;
    }

    @Override
    public boolean usesInstanceSolidStateLatchKey() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return rangeWidth;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return DISPLAY_HALF_HEIGHT;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MHZ_CURLED_VINE);
        if (renderer != null) {
            for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
                renderer.drawFrameIndex(0, segmentXs[segment], segmentYs[segment], hFlip, false);
            }
        }
    }

    /**
     * World Y of a generated curl segment (0-7), matching the values ROM stores
     * at {@code sub2_x_pos(a1)+$1A+index*6} (the display child's per-segment
     * position table populated in {@code loc_3E918}, sonic3k.asm:82861-82893).
     */
    int segmentY(int segmentIndex) {
        return segmentYs[segmentIndex];
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " curve=$" + Integer.toHexString(curveState).toUpperCase()
                + " range=$" + Integer.toHexString(rangeWidth).toUpperCase()
                + " hflip=" + hFlip;
    }

    private void updateSegmentPositions() {
        int curveStep = curveState;
        int halfStep = curveStep >> 1;
        int x = spawn.x() + (hFlip ? INITIAL_SEGMENT_X_OFFSET : -INITIAL_SEGMENT_X_OFFSET);
        int y = spawn.y();
        int phase = 0x000A0000;
        int xDirection = hFlip ? -1 : 1;

        for (int segment = 0; segment < SEGMENT_COUNT; segment++) {
            segmentXs[segment] = x;
            segmentYs[segment] = y;
            if (segment == SEGMENT_COUNT - 1) {
                break;
            }

            phase += curveStep;
            int angle = highWord(phase) & 0xFF;
            int sin = TrigLookupTable.sinHex(angle);
            int cos = TrigLookupTable.cosHex(angle);
            x += xDirection * (cos >> 4);
            y += sin >> 4;
            curveStep += halfStep;
        }
    }

    private static int approachCurveState(int current, int target) {
        if (current == target) {
            return current;
        }
        if (Integer.compareUnsigned(current, target) > 0) {
            return current - 0x10000;
        }
        return current + 0x10000;
    }

    private int selectStandingTableIndex() {
        if (standingSegmentIndices.isEmpty()) {
            return 0;
        }
        // loc_3E8A2 compares P2's $37 against P1's $36 and retains the larger
        // segment before incrementing for its range/curve tables (sonic3k.asm:82810-82823).
        int maximumSegment = 0;
        for (int segmentIndex : standingSegmentIndices.values()) {
            maximumSegment = Math.max(maximumSegment, segmentIndex);
        }
        return Math.min(maximumSegment + 1, RANGE_WIDTHS.length - 1);
    }

    /**
     * Curl segment index for a player at ROM {@code d0 = playerX - vineX + $40}.
     * <p>
     * ROM sub_3E9C6 indexes the segment table with {@code d0 >> 4} and applies no
     * render-flag mirroring (sonic3k.asm:82949-82966). The engine generates its
     * {@code segmentYs} in the display-flip direction, so a display h-flip mirrors
     * the index here to keep the sampled surface aligned with the rendered curl.
     */
    private int segmentIndexForRomD0(int romD0) {
        int index = hFlip ? (2 * INITIAL_RANGE_WIDTH - romD0) : romD0;
        return clamp(index >> 4, 0, SEGMENT_COUNT - 1);
    }

    /**
     * Segment index for a shared-resolver slope sample. The resolver passes
     * {@code sampleX = (playerX - vineX + $40) >> 1}, so the ROM {@code d0} is
     * {@code sampleX << 1} (its low bit is below the 16px segment granularity).
     */
    private int segmentIndexForSample(int sampleX) {
        return segmentIndexForRomD0(sampleX << 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int highWord(int value) {
        return (short) (value >> 16);
    }
}
