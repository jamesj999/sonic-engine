package com.openggf.game.sonic2.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.solid.PlayerSolidContactResult;
import com.openggf.game.solid.SolidCheckpointBatch;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.BoxObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SlopedSolidProvider;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidExecutionMode;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Arrays;
import java.util.List;

/**
 * Bridge object (0x11) - EHZ/HPZ log bridge.
 *
 * <p>ROM reference: {@code Obj11}, {@code Obj11_Depress}, and
 * {@code PlatformObject11_cont} in {@code docs/s2disasm/s2.asm}. The Sonic 2
 * bridge keeps separate standing state for Sonic and Tails, uses the main
 * player's stored log index as the depression centre, and pulls that centre
 * one log at a time toward the sidekick index while Tails is standing.
 */
public class BridgeObjectInstance extends BoxObjectInstance
        implements SlopedSolidProvider, SolidObjectListener, RewindRecreatable {

    private static final int LOG_WIDTH = 16;
    private static final int LOG_HALF_HEIGHT = 8;
    private static final int COLLISION_X_OFFSET = -8;
    private static final int MAX_LOGS = 16;
    /** {@code Obj11_Init}: {@code move.b #$80,width_pixels(a0)} (s2.asm:21951). */
    private static final int ROM_BALANCE_WIDTH_PIXELS = 0x80;
    /**
     * {@code Obj11_Init}: {@code move.w #8,d1} before the first
     * {@code Obj11_MakeBdgSegment} call (docs/s2disasm/s2.asm:21969-21970).
     */
    private static final int ROM_FIRST_SEGMENT_LOGS = 8;
    private static final int MAX_DEPRESSION_ANGLE = 0x40;
    private static final int DEPRESSION_RATE = 4;

    // S2's shipped table only retains rows 8..16, but the disassembly comment
    // notes it is the same bridge data as Sonic 1 with the short rows removed.
    // Keep the full table so the lookup remains total; retail EHZ bridge
    // content still uses the original 8+ log rows.
    // @formatter:off
    private static final int[][] DEPRESSION_MAX_DEPTHS = {
            { 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02, 0x00 },
            { 0x02, 0x04, 0x06, 0x08, 0x0A, 0x0C, 0x0E, 0x10, 0x10, 0x0E, 0x0C, 0x0A, 0x08, 0x06, 0x04, 0x02 },
    };

    private static final int[][] DEPRESSION_WEIGHTS = {
            { 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0xB5, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x7E, 0xDB, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x61, 0xB5, 0xEC, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x4A, 0x93, 0xCD, 0xF3, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x3E, 0x7E, 0xB0, 0xDB, 0xF6, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x38, 0x6D, 0x9D, 0xC5, 0xE4, 0xF8, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x31, 0x61, 0x8E, 0xB5, 0xD4, 0xEC, 0xFB, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x2B, 0x56, 0x7E, 0xA2, 0xC1, 0xDB, 0xEE, 0xFB, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x25, 0x4A, 0x73, 0x93, 0xB0, 0xCD, 0xE1, 0xF3, 0xFC, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x1F, 0x44, 0x67, 0x88, 0xA7, 0xBD, 0xD4, 0xE7, 0xF4, 0xFD, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00 },
            { 0x1F, 0x3E, 0x5C, 0x7E, 0x98, 0xB0, 0xC9, 0xDB, 0xEA, 0xF6, 0xFD, 0xFF, 0x00, 0x00, 0x00, 0x00 },
            { 0x19, 0x38, 0x56, 0x73, 0x8E, 0xA7, 0xBD, 0xD1, 0xE1, 0xEE, 0xF8, 0xFE, 0xFF, 0x00, 0x00, 0x00 },
            { 0x19, 0x38, 0x50, 0x6D, 0x83, 0x9D, 0xB0, 0xC5, 0xD8, 0xE4, 0xF1, 0xF8, 0xFE, 0xFF, 0x00, 0x00 },
            { 0x19, 0x31, 0x4A, 0x67, 0x7E, 0x93, 0xA7, 0xBD, 0xCD, 0xDB, 0xE7, 0xF3, 0xF9, 0xFE, 0xFF, 0x00 },
            { 0x19, 0x31, 0x4A, 0x61, 0x78, 0x8E, 0xA2, 0xB5, 0xC5, 0xD4, 0xE1, 0xEC, 0xF4, 0xFB, 0xFE, 0xFF },
    };
    // @formatter:on

    private int logCount;
    private final int[] logYOffsets;

    /**
     * {@code Obj11_child1} / {@code Obj11_child2} — {@code objoff_30} /
     * {@code objoff_34} (docs/s2disasm/s2.asm:21913-21914). Real SST occupants
     * allocated once by {@code Obj11_Init}; see {@link BridgeSegmentObjectInstance}.
     *
     * <p>Rewind: the link is re-established from the child side by
     * {@link BridgeSegmentObjectInstance#recreateForRewind} calling
     * {@link #adoptSegmentForRewind}, the same parent-driven relink used by
     * {@code ARZRotPformsObjectInstance}'s Obj83 slot children and
     * {@code EggPrisonObjectInstance}'s routine-6/8 slot children.
     */
    @RewindTransient(reason = "structural Obj11 child slot link is recreated by the segment's "
            + "recreate path calling adoptSegmentForRewind")
    private BridgeSegmentObjectInstance segment1;
    @RewindTransient(reason = "structural Obj11 child slot link is recreated by the segment's "
            + "recreate path calling adoptSegmentForRewind")
    private BridgeSegmentObjectInstance segment2;
    private boolean segmentsAllocated;

    private byte[] slopeData;
    private int depressionAngle;
    private int mainLogIndex;
    private int sidekickLogIndex;
    private boolean mainStanding;
    private boolean sidekickStanding;

    public BridgeObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 32, LOG_HALF_HEIGHT, 0.6f, 0.4f, 0.2f, false);
        this.logCount = Math.max(1, Math.min(MAX_LOGS, spawn.subtype() & 0x1F));
        this.logYOffsets = new int[logCount];
        this.slopeData = new byte[getHalfWidth() + 1];
    }

    @Override
    public BridgeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new BridgeObjectInstance(ctx.spawn(), getName());
    }

    @Override
    protected int getHalfWidth() {
        return (logCount * LOG_WIDTH) / 2;
    }

    /**
     * {@code Obj11_Init} writes a fixed {@code width_pixels = $80} regardless of
     * how many logs the subtype asks for (docs/s2disasm/s2.asm:21951), and the
     * object-edge balance branches of {@code Sonic_Move} / {@code Tails_Move}
     * read that SST byte for {@code d1 = x_pos(a0) + width_pixels(a1) -
     * x_pos(a1)} (docs/s2disasm/s2.asm:36586-36601, :39707-39722). The bridge's
     * standable span is only {@code logCount * 16} wide, so with the ROM's
     * $80 the whole span sits strictly inside the (shift, 2*width - shift)
     * window and neither player ever balances on a bridge. Deriving the balance
     * width from the real log geometry instead shrank that window onto the
     * bridge itself and made Tails balance where the ROM leaves him standing.
     */
    @Override
    public int getBalanceWidthPixels() {
        return ROM_BALANCE_WIDTH_PIXELS;
    }

    @Override
    protected int getHalfHeight() {
        return LOG_HALF_HEIGHT;
    }

    @Override
    public boolean isSolidFor(PlayableEntity playerEntity) {
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM Obj11 passes d1 = subtype*8 + 8 and d2 = subtype*16 into
        // PlatformObject11_cont, with the collision span centered 8px left of
        // the object origin and the surface fixed at obY-8.
        return SolidObjectParams.of(getHalfWidth(), 0, 0, COLLISION_X_OFFSET, -8);
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean forceAirOnRideExit() {
        return false;
    }

    @Override
    public byte[] getSlopeData() {
        return slopeData;
    }

    @Override
    public boolean isSlopeFlipped() {
        return false;
    }

    @Override
    public int getSlopeBaseline() {
        return 0;
    }

    @Override
    public boolean usesSlopeForNewLanding() {
        // ROM parity: Obj11_EHZ computes/depresses the log child Y values before
        // sub_F872 (docs/s2disasm/s2.asm:21995-22032), but non-standing players
        // enter PlatformObject11_cont (22160-22172). That helper lands against
        // y_pos(a0)-d3 (35692-35712), not the child log Y table. The depressed
        // child Y is used only after the standing bit is already set (22120-22155).
        return false;
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        // Standing state is latched from the explicit checkpoint batch, matching
        // the bridge's in-object PlatformObject11_cont flow.
    }

    @Override
    public SolidExecutionMode solidExecutionMode() {
        return SolidExecutionMode.MANUAL_CHECKPOINT;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        releaseDeadSegments();
        allocateSegmentsOnce();
        updateDepressionState();
        rebuildBridgeShape();
        updateSlopeData();

        AbstractPlayableSprite mainPlayer = playerEntity instanceof AbstractPlayableSprite playable
                ? playable
                : null;
        latchStandingState(mainPlayer, checkpointAll());
    }

    /**
     * {@code Obj11_Init}'s subsprite-object allocation (docs/s2disasm/s2.asm:21966-21988).
     *
     * <p>{@code d3} starts at the bridge's left edge:
     * {@code move.w x_pos(a0),d3 / move.b (a2),d1 / move.w d1,d0 / lsr.w #1,d0 /
     * lsl.w #4,d0 / sub.w d0,d3} (s2.asm:21955-21962), i.e.
     * {@code x_pos - (subtype div 2) * $10}. The first
     * {@code Obj11_MakeBdgSegment} call always passes {@code move.w #8,d1}
     * (s2.asm:21969) — eight child sprites even for a shorter bridge — and its
     * write loop advances {@code d3} by {@code $10} per log
     * (s2.asm:22001-22007), so the second segment (when
     * {@code subtype - 8 > 0}: {@code subq.w #8,d1 / bls.s +}, s2.asm:21975-21978)
     * starts {@code $80} further right and takes the remainder as its child
     * count.
     *
     * <p>Both calls go through {@code JmpTo_AllocateObjectAfterCurrent}
     * (s2.asm:21992), which scans for the first slot with {@code id == 0}
     * strictly after the running object (s2.asm:33705-33724) — the engine's
     * {@code spawnChild} contract. Slot <em>order</em> is the load-bearing part:
     * {@code TailsCPU_CheckDespawn} (s2.asm:39408-39434) reads back the slot
     * index Tails recorded when he landed and compares the id living there.
     */
    /**
     * Drops {@code Obj11_child1/2} pointers whose object has already left the
     * live set. The ROM pointers can only ever be stale between
     * {@code Obj11_Unload}'s two {@code DeleteObject2} calls and its own
     * {@code DeleteObject} (s2.asm:22069-22076); this keeps the engine's
     * object-reference closure from seeing a dangling link if anything else
     * retires a segment.
     */
    private void releaseDeadSegments() {
        if (segment1 != null && segment1.isDestroyed()) {
            segment1 = null;
        }
        if (segment2 != null && segment2.isDestroyed()) {
            segment2 = null;
        }
    }

    private void allocateSegmentsOnce() {
        if (segmentsAllocated) {
            return;
        }
        segmentsAllocated = true;
        if (services().objectManager() == null) {
            return;
        }

        int leftEdgeX = romLeftEdgeX();
        int segmentY = spawn.y();
        segment1 = spawnChild(() ->
                new BridgeSegmentObjectInstance(this, leftEdgeX, segmentY, ROM_FIRST_SEGMENT_LOGS));

        int remainder = romSubtype() - ROM_FIRST_SEGMENT_LOGS;
        if (remainder <= 0) {
            return;
        }
        int secondStartX = leftEdgeX + ROM_FIRST_SEGMENT_LOGS * LOG_WIDTH;
        segment2 = spawnChild(() ->
                new BridgeSegmentObjectInstance(this, secondStartX, segmentY, remainder));
    }

    /** Raw {@code subtype(a0)} byte, as {@code Obj11_Init} reads it (s2.asm:21957). */
    private int romSubtype() {
        return spawn.subtype() & 0xFF;
    }

    /** {@code d3} at s2.asm:21962: {@code x_pos - (subtype div 2) * $10}. */
    private int romLeftEdgeX() {
        return spawn.x() - ((romSubtype() >> 1) * LOG_WIDTH);
    }

    /**
     * {@code Obj11_Unload} (docs/s2disasm/s2.asm:22054-22076): when the bridge
     * scrolls out of range it runs {@code DeleteObject2} on
     * {@code Obj11_child1}, then on {@code Obj11_child2} when
     * {@code subtype > 8} ({@code cmpi.b #8,subtype(a0) / bls.s +}), and only
     * then {@code DeleteObject}s itself. The children have no unload path of
     * their own, so they must go with the parent.
     */
    @Override
    public void onUnload() {
        if (segment1 != null) {
            segment1.setDestroyed(true);
            segment1 = null;
        }
        if (segment2 != null) {
            segment2.setDestroyed(true);
            segment2 = null;
        }
        segmentsAllocated = false;
    }

    /**
     * Rewind reconstruction: a recreated {@link BridgeSegmentObjectInstance}
     * identifies its parent by the segment's first-log X, which is fixed by the
     * parent's spawn position and subtype (see {@link #allocateSegmentsOnce()}).
     */
    boolean acceptsRewindSegmentAt(int firstLogX) {
        int leftEdgeX = romLeftEdgeX();
        if (firstLogX == leftEdgeX) {
            return segment1 == null;
        }
        int remainder = romSubtype() - ROM_FIRST_SEGMENT_LOGS;
        if (remainder > 0 && firstLogX == leftEdgeX + ROM_FIRST_SEGMENT_LOGS * LOG_WIDTH) {
            return segment2 == null;
        }
        return false;
    }

    void adoptSegmentForRewind(BridgeSegmentObjectInstance segment) {
        segmentsAllocated = true;
        if (segment.getFirstLogX() == romLeftEdgeX()) {
            segment1 = segment;
        } else {
            segment2 = segment;
        }
    }

    private void updateDepressionState() {
        if (!mainStanding && !sidekickStanding) {
            depressionAngle = Math.max(0, depressionAngle - DEPRESSION_RATE);
            return;
        }

        if (sidekickStanding && mainLogIndex != sidekickLogIndex) {
            if (mainLogIndex < sidekickLogIndex) {
                mainLogIndex++;
            } else {
                mainLogIndex--;
            }
        }

        depressionAngle = Math.min(MAX_DEPRESSION_ANGLE, depressionAngle + DEPRESSION_RATE);
    }

    private void rebuildBridgeShape() {
        Arrays.fill(logYOffsets, 0);
        if (depressionAngle <= 0) {
            return;
        }

        int depressionCentre = clampLogIndex(mainLogIndex);
        int maxDepth = DEPRESSION_MAX_DEPTHS[Math.min(logCount, MAX_LOGS)][depressionCentre];
        int sinValue = TrigLookupTable.sinHex(depressionAngle);

        int leftRow = Math.min(depressionCentre, MAX_LOGS - 1);
        for (int i = 0; i <= depressionCentre && i < logCount; i++) {
            logYOffsets[i] = weightedOffset(DEPRESSION_WEIGHTS[leftRow][i], maxDepth, sinValue);
        }

        int logsRight = logCount - 1 - depressionCentre;
        if (logsRight <= 0) {
            return;
        }

        int rightRow = Math.min(logsRight, MAX_LOGS - 1);
        for (int i = depressionCentre + 1; i < logCount; i++) {
            int mirrorIndex = logCount - 1 - i;
            logYOffsets[i] = weightedOffset(DEPRESSION_WEIGHTS[rightRow][mirrorIndex], maxDepth, sinValue);
        }
    }

    private static int weightedOffset(int weight, int maxDepth, int sinValue) {
        return (int) ((((long) weight + 1L) * maxDepth * sinValue) >> 16);
    }

    private void updateSlopeData() {
        if (slopeData == null || slopeData.length != getHalfWidth() + 1) {
            slopeData = new byte[getHalfWidth() + 1];
        }

        int samplesPerLog = LOG_WIDTH / 2;
        for (int i = 0; i < slopeData.length; i++) {
            int logIndex = i / samplesPerLog;
            if (logIndex >= logCount) {
                logIndex = logCount - 1;
            }
            slopeData[i] = (byte) -logYOffsets[logIndex];
        }
    }

    private void latchStandingState(AbstractPlayableSprite mainPlayer, SolidCheckpointBatch batch) {
        mainStanding = false;
        sidekickStanding = false;

        PlayerSolidContactResult mainResult = mainPlayer != null ? batch.perPlayer().get(mainPlayer) : null;
        if (mainResult != null && mainResult.standingNow()) {
            mainStanding = true;
            mainLogIndex = computeLogIndex(mainPlayer);
        }

        AbstractPlayableSprite sidekick = firstTrackedSidekick();
        PlayerSolidContactResult sidekickResult = sidekick != null ? batch.perPlayer().get(sidekick) : null;
        if (sidekickResult != null && sidekickResult.standingNow()) {
            sidekickStanding = true;
            sidekickLogIndex = computeLogIndex(sidekick);
        }
    }

    private AbstractPlayableSprite firstTrackedSidekick() {
        return services().playerQuery().nativeP2OrNull() instanceof AbstractPlayableSprite sidekick
                ? sidekick
                : null;
    }

    private int computeLogIndex(AbstractPlayableSprite player) {
        int relX = player.getCentreX() - spawn.x() + (logCount * 8) + 8;
        return clampLogIndex(relX >> 4);
    }

    private int clampLogIndex(int index) {
        if (index < 0) {
            return 0;
        }
        return Math.min(index, logCount - 1);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }
        PatternSpriteRenderer bridgeRenderer = renderManager.getBridgeRenderer();
        ObjectSpriteSheet bridgeSheet = renderManager.getBridgeSheet();

        if (bridgeRenderer != null && bridgeSheet != null && bridgeRenderer.isReady()) {
            int startX = spawn.x() - ((logCount >> 1) * LOG_WIDTH);
            for (int i = 0; i < logCount; i++) {
                int x = startX + (i * LOG_WIDTH);
                int y = spawn.y() + logYOffsets[i];
                bridgeRenderer.drawFrameIndex(0, x, y, false, false);
            }
        } else {
            super.appendRenderCommands(commands);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }
}
