package com.openggf.game.sonic3k.scroll;

import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.runtime.FbzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.level.scroll.AbstractZoneScrollHandler;
import com.openggf.level.scroll.compose.DeformationPlan;
import com.openggf.level.scroll.compose.ScrollEffectComposer;
import com.openggf.level.scroll.compose.ScrollValueTable;

import java.util.AbstractList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.openggf.level.scroll.M68KMath.negWord;

/** ROM-accurate FBZ indoor/outdoor and Act 2 cloud deformation. */
public final class SwScrlFbz extends AbstractZoneScrollHandler implements FbzCloudPositionSource {
    private static final int[] INDOOR_HEIGHTS = {
            0x80, 0x40, 0x20, 8, 8, 8, 8, 0x28, 0x30, 8, 4, 4,
            0xB8, 0x30, 0x10, 0x10, 0x30, 0x28, 0x18, 0x10, 0x30,
            0x30, 0x10, 0x10, 0x30, 0x28, 0x18, 0x10, 0xB0, 0x30,
            0x28, 0x40, 0x18, 0x30, 0x7FFF
    };
    private static final int[] OUTDOOR_HEIGHTS = {
            0x30, 0x20, 0x30, 0x10, 0x10, 0x10, 0x10, 0x10, 0x7FFF
    };
    // Byte offsets from FBZ_InBGDeformIndex, converted to word indices and kept
    // grouped by the nine successive camera-X/16 speed levels.
    private static final int[][] INDOOR_SCATTER_GROUPS = {
            {6}, {5, 11}, {4, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28},
            {3, 9, 31}, {35}, {2, 8, 15, 19, 23, 27, 29, 32},
            {7, 17, 25, 30}, {0, 34}, {1, 13, 21, 33}
    };
    private static final int[] OUTDOOR_SCATTER = {7, 1, 5, 3, 6, 2, 4, 0, 8};
    private static final int[] CLOUD_SCATTER = {2, 7, 4, 8, 1, 6, 3, 9, 5, 0};
    private static final int[][] CLOUD_POSITION_FRAME_DATA = {
            {0x1E0, 0xEC, 1}, {0x144, 0xC8, 2}, {0x60, 0xB4, 3},
            {0xC4, 0xA0, 2}, {0x140, 0x84, 1}, {0x1A0, 0x6C, 3},
            {0xF0, 0x54, 1}, {0x160, 0x3C, 3}, {0x7C, 0x28, 2},
            {0x20, 0x0C, 1}
    };

    private final Supplier<FbzZoneRuntimeState> stateSupplier;
    private final ScrollEffectComposer composer = new ScrollEffectComposer();
    private final ScrollValueTable hScrollTable = ScrollValueTable.ofLength(256);
    private final CloudPosition[] cloudPositionSlots = createCloudPositionSlots();
    private final List<CloudPosition> cloudPositions = new AbstractList<>() {
        @Override
        public CloudPosition get(int index) {
            return cloudPositionSlots[Objects.checkIndex(index, cloudPositionCount)];
        }

        @Override
        public int size() {
            return cloudPositionCount;
        }
    };
    private int cloudPositionCount;
    private int lastAct = -1;
    private short vscrollFactorFG;

    public SwScrlFbz() {
        this(() -> GameServices.hasRuntime()
                ? S3kRuntimeStates.currentFbz(GameServices.zoneRuntimeRegistry()).orElse(null)
                : null);
    }

    public SwScrlFbz(Supplier<FbzZoneRuntimeState> stateSupplier) {
        this.stateSupplier = stateSupplier;
    }

    @Override
    public void init(int actId, int cameraX, int cameraY) {
        cloudPositionCount = 0;
        lastAct = actId;
    }

    @Override
    public void update(int[] horizScrollBuf, int cameraX, int cameraY, int frameCounter, int actId) {
        if (actId != lastAct || frameCounter == 0 && lastAct < 0) {
            init(actId, cameraX, cameraY);
        }
        composer.reset();
        hScrollTable.clear();
        FbzZoneRuntimeState state = stateSupplier.get();
        // FBZ2BGE_BossEvent is Events_routine_bg=$10. Stages 4/8/12 are
        // ordinary traversal/redraw states and must never select CloudDeform.
        if (actId == 1 && state != null && state.bossBackgroundStage() == 0x10) {
            updateCloudBoss(cameraX, cameraY, frameCounter, state);
        } else if (state != null && state.backgroundOutdoor()) {
            updateOutdoor(cameraX, frameCounter, state);
        } else {
            updateIndoor(cameraX, cameraY);
        }
        composer.copyPackedScrollWordsTo(horizScrollBuf);
        minScrollOffset = composer.getMinScrollOffset();
        maxScrollOffset = composer.getMaxScrollOffset();
        vscrollFactorBG = composer.getVscrollFactorBG();
        vscrollFactorFG = composer.getVscrollFactorFG();
    }

    private void updateIndoor(int cameraX, int cameraY) {
        int bgY = (short) cameraY;
        bgY >>= 1;
        bgY -= bgY >> 5;
        int fixed = ((short) cameraX) << 16;
        fixed >>= 4;
        int step = fixed;
        for (int[] group : INDOOR_SCATTER_GROUPS) {
            short value = (short) (fixed >> 16);
            for (int index : group) hScrollTable.set(index, value);
            fixed += step;
        }
        composer.setVscrollFactorBG((short) bgY);
        DeformationPlan.applyTableBands(composer, bgY, negWord(cameraX), hScrollTable,
                INDOOR_HEIGHTS, 0, value -> negWord(value));
        cloudPositionCount = 0;
    }

    private void updateOutdoor(int cameraX, int frameCounter, FbzZoneRuntimeState state) {
        int bgY = (short) (0x16 + state.outdoorBobOffset());
        int fixed = ((short) cameraX) << 16;
        fixed >>= 4;
        int step = fixed >> 1;
        int drift = state.sampleOutdoorHScrollAccumulator(frameCounter);
        for (int index : OUTDOOR_SCATTER) {
            fixed += drift;
            hScrollTable.set(index, (short) (fixed >> 16));
            fixed += step;
        }
        composer.setVscrollFactorBG((short) bgY);
        DeformationPlan.applyTableBands(composer, bgY, negWord(cameraX), hScrollTable,
                OUTDOOR_HEIGHTS, 0, value -> negWord(value));
        cloudPositionCount = 0;
    }

    private void updateCloudBoss(int cameraX, int cameraY, int frameCounter, FbzZoneRuntimeState state) {
        int shake = state.screenShakeLastOffset();
        int bgY = (short) (cameraY - 0x300 + state.bossBackgroundOffsetY() + shake);
        int bgX = (short) (cameraX - 0x2600 - state.bossBackgroundOffsetX());
        int fixed = ((short) bgX) << 16;
        fixed >>= 4;
        int step = fixed >> 2;
        int drift = state.sampleBossHScrollAccumulator(frameCounter);
        for (int index : CLOUD_SCATTER) {
            fixed += drift;
            hScrollTable.set(index, (short) (fixed >> 16));
            fixed += step;
        }
        int cloudY = (short) (bgY - shake);
        cloudY >>= 1;
        cloudY = (short) (-(cloudY + shake) + state.outdoorBobOffset());
        for (int addressSlot = 0; addressSlot < CLOUD_POSITION_FRAME_DATA.length; addressSlot++) {
            // SetUp_FBZ2BossEvent allocates selectors 9..0 into address slots
            // 0..9. CloudDeform then consumes HScroll words in address order.
            int selector = 9 - addressSlot;
            int[] data = CLOUD_POSITION_FRAME_DATA[selector];
            int x = ((data[0] - hScrollTable.get(addressSlot)) & 0x1FF) + 0x54;
            int y = ((data[1] + cloudY) & 0xFF) + 0x74;
            cloudPositionSlots[addressSlot].setPosition(x, y);
        }
        cloudPositionCount = CLOUD_POSITION_FRAME_DATA.length;
        composer.setVscrollFactorBG((short) bgY);
        composer.setVscrollFactorFG((short) state.bossForegroundVScroll());
        composer.fillPackedScrollWords(0, composer.visibleLineCount(), negWord(bgX), negWord(cameraX));
    }

    @Override public short getVscrollFactorFG() { return vscrollFactorFG; }

    public List<CloudPosition> cloudPositions() {
        return cloudPositions;
    }

    @Override
    public CloudPosition cloudPositionAtAddressSlot(int addressSlot) {
        // Obj_FBZCloud can run in initial Process_Sprites before CloudDeform.
        // Its address still names the allocated RAM slot before publication.
        return cloudPositionSlots[Objects.checkIndex(addressSlot, cloudPositionSlots.length)];
    }

    private static CloudPosition[] createCloudPositionSlots() {
        CloudPosition[] slots = new CloudPosition[CLOUD_POSITION_FRAME_DATA.length];
        for (int i = 0; i < slots.length; i++) {
            int selector = 9 - i;
            slots[i] = new CloudPosition(0, 0, CLOUD_POSITION_FRAME_DATA[selector][2], selector, i);
        }
        return slots;
    }

    public static int cloudMappingFrameForSelector(int selector) {
        return CLOUD_POSITION_FRAME_DATA[Objects.checkIndex(selector, CLOUD_POSITION_FRAME_DATA.length)][2];
    }

    /** Pure oracle helper for one native address-table cloud. */
    public static CloudPosition computeBossCloudPosition(int selector, int addressSlot,
                                                          int hScrollValue, int bgY,
                                                          int shake, int bob) {
        Objects.checkIndex(selector, CLOUD_POSITION_FRAME_DATA.length);
        Objects.checkIndex(addressSlot, CLOUD_POSITION_FRAME_DATA.length);
        int[] data = CLOUD_POSITION_FRAME_DATA[selector];
        int effectiveY = (short) (bgY - shake);
        effectiveY >>= 1;
        effectiveY = (short) (-(effectiveY + shake) + bob);
        int x = ((data[0] - hScrollValue) & 0x1FF) + 0x54;
        int y = ((data[1] + effectiveY) & 0xFF) + 0x74;
        return new CloudPosition(x, y, data[2], selector, addressSlot);
    }

    /** Mutable, preallocated output slot; consumers retain the slot identity across frames. */
    public static final class CloudPosition {
        private int x;
        private int y;
        private final int mappingFrame;
        private final int selector;
        private final int addressSlot;

        public CloudPosition(int x, int y, int mappingFrame) {
            this(x, y, mappingFrame, -1, -1);
        }

        public CloudPosition(int x, int y, int mappingFrame, int selector, int addressSlot) {
            this.x = x;
            this.y = y;
            this.mappingFrame = mappingFrame;
            this.selector = selector;
            this.addressSlot = addressSlot;
        }

        private void setPosition(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int mappingFrame() {
            return mappingFrame;
        }

        public int selector() { return selector; }
        public int addressSlot() { return addressSlot; }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof CloudPosition position
                    && x == position.x && y == position.y && mappingFrame == position.mappingFrame;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(x);
            result = 31 * result + Integer.hashCode(y);
            return 31 * result + Integer.hashCode(mappingFrame);
        }

        @Override
        public String toString() {
            return "CloudPosition[x=" + x + ", y=" + y + ", mappingFrame=" + mappingFrame + ']';
        }
    }
}
