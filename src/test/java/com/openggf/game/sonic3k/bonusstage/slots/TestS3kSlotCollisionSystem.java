package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotCollisionSystem {

    @Test
    void collisionReadsExpandedStrideAndStoresCompactLayoutIndex() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        int row = Math.floorDiv(0x2BC + S3kSlotCollisionSystem.COLLISION_Y_OFFSET, S3kSlotCollisionSystem.CELL_SIZE);
        int col = Math.floorDiv(0x2EC + S3kSlotCollisionSystem.COLLISION_X_OFFSET, S3kSlotCollisionSystem.CELL_SIZE);
        int expandedIndex = row * buffers.layoutStrideBytes() + col;
        int layoutIndex = buffers.expandedToCompactIndex(expandedIndex);
        buffers.expandedLayout()[expandedIndex] = 5;
        S3kSlotCollisionSystem system = new S3kSlotCollisionSystem(buffers, state);

        S3kSlotCollisionSystem.Collision collision = system.checkCollision(0x2EC, 0x2BC);

        assertTrue(collision.solid());
        assertTrue(collision.special());
        assertEquals(5, collision.tileId());
        assertEquals(layoutIndex, collision.layoutIndex());
        assertEquals(expandedIndex, collision.expandedLayoutIndex());
        assertEquals(5, state.lastCollisionTileId());
        assertEquals(layoutIndex, state.lastCollisionIndex());
    }

    @Test
    void ringPickupConsumesCompactAndExpandedLayoutViews() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        int row = Math.floorDiv(0x2E0 + S3kSlotCollisionSystem.RING_Y_OFFSET, S3kSlotCollisionSystem.CELL_SIZE);
        int col = Math.floorDiv(0x2E0 + S3kSlotCollisionSystem.RING_X_OFFSET, S3kSlotCollisionSystem.CELL_SIZE);
        int expandedIndex = row * buffers.layoutStrideBytes() + col;
        int layoutIndex = buffers.expandedToCompactIndex(expandedIndex);
        buffers.layout()[layoutIndex] = 8;
        buffers.expandedLayout()[expandedIndex] = 8;
        S3kSlotCollisionSystem system = new S3kSlotCollisionSystem(buffers, state);

        S3kSlotCollisionSystem.RingCheck ring = system.checkRingPickup(0x2E0, 0x2E0);
        assertTrue(ring.foundRing());
        assertEquals(layoutIndex, ring.layoutIndex());
        assertEquals(expandedIndex, ring.expandedLayoutIndex());

        system.consumeRing(ring);

        assertEquals(0, buffers.layout()[layoutIndex]);
        assertEquals(0, buffers.expandedLayout()[expandedIndex]);
    }

    @Test
    void spikeTileReversesScalarOnlyOncePerThrottleWindow() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotCollisionSystem system = new S3kSlotCollisionSystem(
                S3kSlotRenderBuffers.fromRomData(), state);

        S3kSlotCollisionSystem.TileResponse response = system.resolveTileResponse(
                6, (short) 0, (short) 0, (short) 0, (short) 0);

        assertEquals(S3kSlotCollisionSystem.Effect.SPIKE_REVERSAL, response.effect());
        assertEquals(-0x40, state.scalarIndex1());

        S3kSlotCollisionSystem.TileResponse throttled = system.resolveTileResponse(
                6, (short) 0, (short) 0, (short) 0, (short) 0);

        assertEquals(S3kSlotCollisionSystem.Effect.NONE, throttled.effect());
        assertEquals(-0x40, state.scalarIndex1());
    }
}


