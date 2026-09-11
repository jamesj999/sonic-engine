package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.physics.TrigLookupTable;

public final class S3kSlotCollisionSystem {
    static final int LAYOUT_STRIDE = 0x20;
    static final int EXPANDED_STRIDE = 0x80;
    static final int CELL_SIZE = 0x18;
    static final int COLLISION_Y_OFFSET = 0x44;
    static final int COLLISION_X_OFFSET = 0x14;
    static final int RING_Y_OFFSET = 0x50;
    static final int RING_X_OFFSET = 0x20;
    private static final int BUMPER_LAUNCH_SPEED = 0x700;
    private static final int SPIKE_THROTTLE_FRAMES = 0x1E;

    private final S3kSlotRenderBuffers renderBuffers;
    private final S3kSlotStageState stageState;

    public S3kSlotCollisionSystem(S3kSlotRenderBuffers renderBuffers, S3kSlotStageState stageState) {
        this.renderBuffers = renderBuffers;
        this.stageState = stageState;
    }

    public S3kSlotRenderBuffers renderBuffers() {
        return renderBuffers;
    }

    public void tickFrameState() {
        stageState.tickSpikeThrottleTimer();
        stageState.tickSlotWallThrottleTimer();
    }

    public Collision checkCollision(int xPixel, int yPixel) {
        // ROM sub_4BD5A (sonic3k.asm:99081-99110) unconditionally samples all four
        // corners (top-left, top-right, bottom-left, bottom-right, in that order --
        // (a1)+, (a1)+, adda #$7E, (a1)+, (a1)+) via sub_4BDA2 for every call, and
        // sub_4BDA2 only ever WRITES $30(a0)/$32(a0) when it finds a "special"
        // (1-6, non-7) tile -- it never clears them for a plain-solid or empty
        // corner. So the last special corner scanned wins, independent of which
        // corner (if any) triggers the overall solid/collision result. Returning
        // on the first solid corner (as this used to) could both stop before ever
        // reaching the real special tile, and — even when a special tile was seen
        // first — get silently overridden if this method were called again for a
        // second probe (X then Y, mirroring sub_4BCB0's two sub_4BD5A calls) whose
        // last corner is plain-solid: ROM would keep the earlier special tile in
        // that case, since a plain-solid corner never clears $30(a0)/$32(a0).
        byte[] expandedLayout = renderBuffers.expandedLayout();
        if (expandedLayout == null || expandedLayout.length < renderBuffers.layoutRows() * renderBuffers.layoutStrideBytes()) {
            return Collision.NONE;
        }

        int baseRow = Math.floorDiv(yPixel + COLLISION_Y_OFFSET, CELL_SIZE);
        int baseCol = Math.floorDiv(xPixel + COLLISION_X_OFFSET, CELL_SIZE);
        boolean anySolid = false;
        boolean lastSpecial = false;
        int lastTileId = 0;
        int lastLayoutIndex = -1;
        int lastExpandedIndex = -1;
        for (int dr = 0; dr <= 1; dr++) {
            for (int dc = 0; dc <= 1; dc++) {
                int row = baseRow + dr;
                int col = baseCol + dc;
                if (row < 0 || row >= renderBuffers.layoutRows()
                        || col < 0 || col >= renderBuffers.layoutColumns()) {
                    continue;
                }

                int expandedIndex = row * renderBuffers.layoutStrideBytes() + col;
                int compactIndex = renderBuffers.expandedToCompactIndex(expandedIndex);
                int tileId = expandedLayout[expandedIndex] & 0xFF;
                if (!isSolid(tileId)) {
                    continue;
                }

                anySolid = true;
                if (isSpecial(tileId) && compactIndex >= 0) {
                    stageState.setLastCollision(tileId, compactIndex);
                    lastSpecial = true;
                    lastTileId = tileId;
                    lastLayoutIndex = compactIndex;
                    lastExpandedIndex = expandedIndex;
                }
            }
        }

        if (!anySolid) {
            return Collision.NONE;
        }
        return new Collision(true, lastSpecial, lastTileId, lastLayoutIndex, lastExpandedIndex);
    }

    public RingCheck checkRingPickup(int xPixel, int yPixel) {
        byte[] layout = renderBuffers.layout();
        byte[] expandedLayout = renderBuffers.expandedLayout();
        if (layout == null || expandedLayout == null
                || layout.length < S3kSlotRomData.SLOT_LAYOUT_SIZE * S3kSlotRomData.SLOT_LAYOUT_SIZE
                || expandedLayout.length < renderBuffers.layoutRows() * renderBuffers.layoutStrideBytes()) {
            return RingCheck.NONE;
        }

        int row = Math.floorDiv(yPixel + RING_Y_OFFSET, CELL_SIZE);
        int col = Math.floorDiv(xPixel + RING_X_OFFSET, CELL_SIZE);
        if (row < 0 || row >= renderBuffers.layoutRows()
                || col < 0 || col >= renderBuffers.layoutColumns()) {
            return RingCheck.NONE;
        }

        int compactIndex = renderBuffers.expandedToCompactIndex(row, col);
        int expandedIndex = row * renderBuffers.layoutStrideBytes() + col;
        int tileId = expandedLayout[expandedIndex] & 0xFF;
        if (tileId == 8 && compactIndex >= 0) {
            return new RingCheck(true, compactIndex, expandedIndex, tileId);
        }
        if (tileId != 0) {
            return new RingCheck(false, compactIndex, expandedIndex, tileId);
        }
        return RingCheck.NONE;
    }

    public void consumeRing(RingCheck ring) {
        if (ring == null || !ring.foundRing()) {
            return;
        }
        byte[] layout = renderBuffers.layout();
        byte[] expandedLayout = renderBuffers.expandedLayout();
        if (layout == null || expandedLayout == null) {
            return;
        }
        if (ring.layoutIndex() >= 0 && ring.layoutIndex() < layout.length) {
            layout[ring.layoutIndex()] = 0;
        }
        if (ring.expandedLayoutIndex() >= 0 && ring.expandedLayoutIndex() < expandedLayout.length) {
            expandedLayout[ring.expandedLayoutIndex()] = 0;
        }
    }

    public TileResponse resolveTileResponse(int tileId, short playerX, short playerY,
                                            short tileCenterX, short tileCenterY) {
        return switch (tileId) {
            case 5 -> bumperResponse(playerX, playerY, tileCenterX, tileCenterY);
            case 4 -> new TileResponse(Effect.GOAL_EXIT, (short) 0, (short) 0);
            case 6 -> spikeResponse();
            case 1, 2, 3 -> slotReelResponse();
            default -> TileResponse.NONE;
        };
    }

    /**
     * ROM sub_4BE3A's bumper branch (sonic3k.asm:99214-99223) recovers the grid
     * index from the stored tile pointer: {@code $32(a0)} holds {@code a1} as it
     * stood right after sub_4BDA2's post-increment read ({@code move.b (a1)+,d4},
     * sonic3k.asm:99099/99101/99104/99106 -- {@code a1 = TABLE + idx + 1} for the
     * corner at grid index {@code idx}). {@code subi.l #-$CFFF,d1} (sonic3k.asm:
     * 99215) adds {@code $CFFF} back: with {@code TABLE = RAM_start+$3000} (RAM_start's
     * low word is 0), the low word of {@code TABLE + idx + 1 + $CFFF} is
     * {@code ($3000 + $CFFF + 1 + idx) & $FFFF = ($10000 + idx) & $FFFF = idx} --
     * the stored "+1" from the post-increment is exactly cancelled by the
     * {@code $CFFF} (not {@code $D000}) adjustment. So the recovered grid index
     * equals the ORIGINAL {@code idx} that was hit, not {@code idx+1}; adding 1
     * here would shift the reconstructed tile one column to the right of the
     * tile that actually triggered the response, producing a wrong bumper/launch
     * direction while keeping the correct 0x700 launch magnitude (confirmed
     * against src/test/resources/traces/s3k/bonus_slots frame 445/446: the +1
     * variant reproduced the ROM's exact launch magnitude but the wrong angle).
     */
    static short tileResponseAnchorX(int expandedLayoutIndex) {
        if (expandedLayoutIndex < 0) {
            return 0;
        }
        return (short) (((expandedLayoutIndex & 0x7F) * CELL_SIZE) - COLLISION_X_OFFSET);
    }

    static short tileResponseAnchorY(int expandedLayoutIndex) {
        if (expandedLayoutIndex < 0) {
            return 0;
        }
        return (short) ((((expandedLayoutIndex >>> 7) & 0x7F) * CELL_SIZE) - COLLISION_Y_OFFSET);
    }

    private TileResponse bumperResponse(short playerX, short playerY, short tileCenterX, short tileCenterY) {
        int dx = tileCenterX - playerX;
        int dy = tileCenterY - playerY;
        if (dx == 0 && dy == 0) {
            return new TileResponse(Effect.BUMPER_LAUNCH, (short) 0, (short) -BUMPER_LAUNCH_SPEED);
        }
        int angle = TrigLookupTable.calcAngle((short) dx, (short) dy);
        int sin = TrigLookupTable.sinHex(angle);
        int cos = TrigLookupTable.cosHex(angle);
        short launchX = (short) ((cos * -BUMPER_LAUNCH_SPEED) >> 8);
        short launchY = (short) ((sin * -BUMPER_LAUNCH_SPEED) >> 8);
        return new TileResponse(Effect.BUMPER_LAUNCH, launchX, launchY);
    }

    private TileResponse spikeResponse() {
        if (stageState.spikeThrottleTimer() > 0) {
            return TileResponse.NONE;
        }
        stageState.setSpikeThrottleTimer(SPIKE_THROTTLE_FRAMES);
        stageState.negateScalarIndex1();
        return new TileResponse(Effect.SPIKE_REVERSAL, (short) 0, (short) 0);
    }

    private TileResponse slotReelResponse() {
        return new TileResponse(Effect.SLOT_REEL_INCREMENT, (short) 0, (short) 0);
    }

    static boolean isSolid(int tileId) {
        if (tileId == 0 || tileId == 8) {
            return false;
        }
        return tileId >= 1 && tileId <= 15;
    }

    static boolean isSpecial(int tileId) {
        return tileId >= 1 && tileId <= 6;
    }

    public enum Effect {
        NONE, BUMPER_LAUNCH, GOAL_EXIT, SPIKE_REVERSAL, SLOT_REEL_INCREMENT
    }

    public record Collision(boolean solid, boolean special, int tileId,
                             int layoutIndex, int expandedLayoutIndex) {
        public static final Collision NONE = new Collision(false, false, 0, -1, -1);
    }

    public record RingCheck(boolean foundRing, int layoutIndex, int expandedLayoutIndex, int tileId) {
        public static final RingCheck NONE = new RingCheck(false, -1, -1, 0);
    }

    public record TileResponse(Effect effect, short launchXVel, short launchYVel) {
        public static final TileResponse NONE = new TileResponse(Effect.NONE, (short) 0, (short) 0);
    }
}
