package com.openggf.game.sonic3k.bonusstage.slots;

import java.util.Arrays;

public final class S3kSlotRenderBuffers {
    private final byte[] layout;
    private final byte[] expandedLayout;
    private final int layoutStrideBytes;
    private final int layoutRows;
    private final int layoutColumns;
    private final TransientAnimationSlot[] transientAnimationSlots;
    private short[] stagedPointGrid = new short[0];
    private int stagedCameraX;
    private int stagedCameraY;
    private final VisibleCells[] visibleCellFrames = {new VisibleCells(16), new VisibleCells(16)};
    private int nextVisibleCellFrame;

    private S3kSlotRenderBuffers(byte[] layout, byte[] expandedLayout,
                                 int layoutStrideBytes, int layoutRows, int layoutColumns) {
        this.layout = layout;
        this.expandedLayout = expandedLayout;
        this.layoutStrideBytes = layoutStrideBytes;
        this.layoutRows = layoutRows;
        this.layoutColumns = layoutColumns;
        this.transientAnimationSlots = new TransientAnimationSlot[S3kSlotRomData.TRANSIENT_SLOT_COUNT];
        for (int i = 0; i < transientAnimationSlots.length; i++) {
            transientAnimationSlots[i] = new TransientAnimationSlot();
        }
    }

    public static S3kSlotRenderBuffers fromRomData() {
        return new S3kSlotRenderBuffers(
                S3kSlotRomData.SLOT_BONUS_LAYOUT.clone(),
                S3kSlotRomData.buildExpandedLayoutBuffer(),
                S3kSlotRomData.SLOT_EXPANDED_STRIDE,
                S3kSlotRomData.SLOT_EXPANDED_STRIDE,
                S3kSlotRomData.SLOT_EXPANDED_STRIDE);
    }

    public byte[] layout() {
        return layout;
    }

    public byte[] expandedLayout() {
        return expandedLayout;
    }

    public int layoutStrideBytes() {
        return layoutStrideBytes;
    }

    public int layoutRows() {
        return layoutRows;
    }

    public int layoutColumns() {
        return layoutColumns;
    }

    public void stagePointGrid(short[] pointGrid) {
        stagedPointGrid = pointGrid != null ? pointGrid : new short[0];
    }

    short[] stagedPointGrid() {
        return stagedPointGrid;
    }

    public void stageViewport(int cameraX, int cameraY) {
        stagedCameraX = cameraX;
        stagedCameraY = cameraY;
    }

    public int stagedCameraX() {
        return stagedCameraX;
    }

    public int stagedCameraY() {
        return stagedCameraY;
    }

    VisibleCells beginVisibleCellFrame() {
        VisibleCells frame = visibleCellFrames[nextVisibleCellFrame];
        nextVisibleCellFrame ^= 1;
        frame.clear();
        return frame;
    }

    public boolean startRingAnimationAt(int layoutIndex) {
        return startTransientAnimation(layoutIndex,
                S3kSlotRomData.RING_SPARKLE_FRAMES,
                S3kSlotRomData.RING_SPARKLE_DELAY,
                (byte) 0x00);
    }

    public boolean startBumperAnimationAt(int layoutIndex) {
        return startTransientAnimation(layoutIndex,
                S3kSlotRomData.BUMPER_BOUNCE_FRAMES,
                S3kSlotRomData.BUMPER_BOUNCE_DELAY,
                (byte) 0x05);
    }

    public boolean startSpikeAnimationAt(int layoutIndex) {
        return startTransientAnimation(layoutIndex,
                S3kSlotRomData.SPIKE_ANIMATION_FRAMES,
                S3kSlotRomData.SPIKE_ANIMATION_DELAY,
                (byte) 0x06);
    }

    public boolean startSlotWallAnimationAt(int layoutIndex, int finalTileId) {
        return startTransientAnimation(layoutIndex,
                S3kSlotRomData.SLOT_WALL_COLOR_FRAMES,
                S3kSlotRomData.SLOT_WALL_COLOR_DELAY,
                (byte) finalTileId);
    }

    public void tickTransientAnimations() {
        for (TransientAnimationSlot slot : transientAnimationSlots) {
            slot.tick(this);
        }
    }

    public boolean hasActiveTransientAnimationAt(int layoutIndex) {
        for (TransientAnimationSlot slot : transientAnimationSlots) {
            if (slot.active && slot.layoutIndex == layoutIndex) {
                return true;
            }
        }
        return false;
    }

    public int renderCellIdAt(int row, int col) {
        if (row < 0 || row >= layoutRows || col < 0 || col >= layoutColumns) {
            return 0;
        }
        int expandedIndex = row * layoutStrideBytes + col;
        if (expandedIndex < 0 || expandedIndex >= expandedLayout.length) {
            return 0;
        }
        return expandedLayout[expandedIndex] & 0xFF;
    }

    public int compactToExpandedIndex(int compactIndex) {
        if (compactIndex < 0 || compactIndex >= layout.length) {
            return -1;
        }
        int compactRow = compactIndex / S3kSlotRomData.SLOT_LAYOUT_SIZE;
        int compactCol = compactIndex % S3kSlotRomData.SLOT_LAYOUT_SIZE;
        int expandedRow = compactRow + S3kSlotRomData.SLOT_LAYOUT_WORLD_OFFSET;
        int expandedCol = compactCol + S3kSlotRomData.SLOT_LAYOUT_WORLD_OFFSET;
        return expandedRow * layoutStrideBytes + expandedCol;
    }

    public int expandedToCompactIndex(int expandedIndex) {
        if (expandedIndex < 0 || expandedIndex >= expandedLayout.length) {
            return -1;
        }
        return expandedToCompactIndex(expandedIndex / layoutStrideBytes, expandedIndex % layoutStrideBytes);
    }

    public int expandedToCompactIndex(int expandedRow, int expandedCol) {
        int compactRow = expandedRow - S3kSlotRomData.SLOT_LAYOUT_WORLD_OFFSET;
        int compactCol = expandedCol - S3kSlotRomData.SLOT_LAYOUT_WORLD_OFFSET;
        if (compactRow < 0 || compactRow >= S3kSlotRomData.SLOT_LAYOUT_SIZE
                || compactCol < 0 || compactCol >= S3kSlotRomData.SLOT_LAYOUT_SIZE) {
            return -1;
        }
        return compactRow * S3kSlotRomData.SLOT_LAYOUT_SIZE + compactCol;
    }

    private boolean startTransientAnimation(int layoutIndex, byte[] frames, int reload, byte restoreTile) {
        if (layoutIndex < 0 || layoutIndex >= layout.length || frames == null || frames.length == 0) {
            return false;
        }
        for (TransientAnimationSlot slot : transientAnimationSlots) {
            if (!slot.active) {
                slot.start(this, layoutIndex, frames, reload, restoreTile);
                return true;
            }
        }
        return false;
    }

    private void setCompactTile(int compactIndex, byte tileId) {
        if (compactIndex >= 0 && compactIndex < layout.length) {
            layout[compactIndex] = tileId;
        }
        int expandedIndex = compactToExpandedIndex(compactIndex);
        if (expandedIndex >= 0 && expandedIndex < expandedLayout.length) {
            expandedLayout[expandedIndex] = tileId;
        }
    }

    private static final class TransientAnimationSlot {
        private boolean active;
        private int layoutIndex = -1;
        private byte[] frames = new byte[0];
        private int delay;
        private int timer;
        private int frameIndex;
        private byte restoreTile;

        /**
         * Claims a free slot exactly as the ROM's {@code sub_4B57C} scan does
         * ({@code sonic3k.asm:98376-98390}): the creating branch stores only the
         * animation type, the target layout-byte pointer and the restore id --
         * see {@code loc_4BF30} ({@code sonic3k.asm:99283-99300}) and its siblings.
         * Every slot is released with {@code clr.l (a0) / clr.l 4(a0)}
         * ({@code sonic3k.asm:98511-98512}), so a freshly claimed slot always
         * starts with countdown 0 and frame index 0, and <b>the layout byte is not
         * written by the creating branch at all</b>. The first animation frame is
         * published by the first {@code sub_4B592} pass, which runs later in the
         * same game frame from {@code Slots_RenderLayout}
         * ({@code sonic3k.asm:98159-98161}).
         */
        private void start(S3kSlotRenderBuffers buffers, int layoutIndex, byte[] frames, int reload, byte restoreTile) {
            active = true;
            this.layoutIndex = layoutIndex;
            this.frames = frames;
            this.delay = reload;
            this.timer = 0;
            this.frameIndex = 0;
            this.restoreTile = restoreTile;
        }

        /**
         * One {@code sub_4B592} pass over this slot
         * ({@code sonic3k.asm:98397-98411} dispatching to {@code loc_4B5C2} /
         * {@code loc_4B5F2} / {@code loc_4B65A} / {@code loc_4B626},
         * {@code sonic3k.asm:98420-98513}). All four handlers share one idiom:
         *
         * <pre>
         *     subq.b  #1,2(a0)          ; countdown
         *     bpl.s   (return)          ; still counting: 0 is non-negative, so a
         *                               ; reload of N yields N waiting passes
         *     move.b  #N,2(a0)          ; reload
         *     ... publish frames[index++] ...
         *     bne.s   (return)          ; the table's 0 terminator falls through
         *     move.b  <restore id>,(a1) ; and restores the resting tile
         * </pre>
         *
         * <p>The countdown is therefore pre-decrement-and-test-for-negative, and a
         * newly claimed slot publishes its first frame on the very pass that
         * follows its creation -- the same frame. Writing {@code frames[0]} in
         * {@link #start} and <em>also</em> letting that frame's pass decrement the
         * countdown consumed the creation pass twice, so every subsequent step,
         * and the final restore, landed one frame early. For the 24-frame reel
         * flash ({@code byte_4B688}, {@code sonic3k.asm:98517-98545}) that restored
         * the advanced reel tile on frame 47 instead of the ROM's frame 48 -- and
         * when the advanced tile is {@code 4}, the goal, the goal-exit routine bump
         * at {@code loc_4BED0} ({@code sonic3k.asm:99247-99253}) fired a frame
         * before the ROM's.
         */
        private void tick(S3kSlotRenderBuffers buffers) {
            if (!active) {
                return;
            }
            if (--timer >= 0) {
                return;
            }
            timer = delay;
            int index = frameIndex++;
            if (index >= frames.length) {
                buffers.setCompactTile(layoutIndex, restoreTile);
                active = false;
                layoutIndex = -1;
                frames = new byte[0];
                return;
            }
            buffers.setCompactTile(layoutIndex, frames[index]);
        }
    }

    /**
     * Read-only published view of one slot-layout visibility frame.
     *
     * <p>The two instances alternate. A view published by build N remains valid
     * throughout build N+1, covering the runtime's deferred update-to-render
     * handoff. It may be cleared and overwritten when build N+2 starts, so callers
     * must not retain it beyond the following visibility build.</p>
     */
    static final class VisibleCells {
        private static final VisibleCells EMPTY = new VisibleCells(0);

        private byte[] cellIds;
        private int[] worldXs;
        private int[] worldYs;
        private int count;

        VisibleCells(int initialCapacity) {
            cellIds = new byte[initialCapacity];
            worldXs = new int[initialCapacity];
            worldYs = new int[initialCapacity];
        }

        static VisibleCells empty() {
            return EMPTY;
        }

        int size() {
            return count;
        }

        boolean isEmpty() {
            return count == 0;
        }

        int cellIdAt(int index) {
            checkIndex(index);
            return cellIds[index] & 0xFF;
        }

        int worldXAt(int index) {
            checkIndex(index);
            return worldXs[index];
        }

        int worldYAt(int index) {
            checkIndex(index);
            return worldYs[index];
        }

        void add(int cellId, int worldX, int worldY) {
            ensureCapacity(count + 1);
            cellIds[count] = (byte) cellId;
            worldXs[count] = worldX;
            worldYs[count] = worldY;
            count++;
        }

        private void clear() {
            count = 0;
        }

        private void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity <= cellIds.length) {
                return;
            }
            int newCapacity = Math.max(requiredCapacity, Math.max(1, cellIds.length << 1));
            cellIds = Arrays.copyOf(cellIds, newCapacity);
            worldXs = Arrays.copyOf(worldXs, newCapacity);
            worldYs = Arrays.copyOf(worldYs, newCapacity);
        }

        private void checkIndex(int index) {
            if (index < 0 || index >= count) {
                throw new IndexOutOfBoundsException(index);
            }
        }
    }
}
