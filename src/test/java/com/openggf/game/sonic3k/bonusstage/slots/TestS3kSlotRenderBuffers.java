package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestS3kSlotRenderBuffers {

    @Test
    void retainedFrameIsUnchangedWhileNextDeferredFrameBuilds() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = stagedBuffers(renderer);
        S3kSlotRenderBuffers.VisibleCells frameN = renderer.buildVisibleCells(buffers);
        int[] frameNSnapshot = snapshot(frameN);

        buffers.stageViewport(BOOTSTRAP_CAMERA_X + 0x18, BOOTSTRAP_CAMERA_Y);
        buffers.stagePointGrid(renderer.buildPointGrid(0, BOOTSTRAP_CAMERA_X + 0x18, BOOTSTRAP_CAMERA_Y));
        S3kSlotRenderBuffers.VisibleCells frameNPlusOne = renderer.buildVisibleCells(buffers);

        assertNotSame(frameN, frameNPlusOne);
        assertArrayEquals(frameNSnapshot, snapshot(frameN));
    }

    @Test
    void visibleCellScansReuseTwoPrimitiveBackingBuffersAcross600FramesAndGrowSafely() {
        S3kSlotLayoutRenderer renderer = new S3kSlotLayoutRenderer();
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        buffers.stageViewport(0, 0);
        short[] points = new short[16 * 16 * 2];
        buffers.stagePointGrid(points);

        S3kSlotRenderBuffers.VisibleCells first = renderer.buildVisibleCells(buffers);
        S3kSlotRenderBuffers.VisibleCells second = renderer.buildVisibleCells(buffers);
        assertPrimitiveView(first);
        assertPrimitiveView(second);
        Map<S3kSlotRenderBuffers.VisibleCells, Object[]> initialBackings = new IdentityHashMap<>();
        initialBackings.put(first, primitiveBackings(first));
        initialBackings.put(second, primitiveBackings(second));

        Arrays.fill(buffers.expandedLayout(), (byte) 1);
        S3kSlotRenderBuffers.VisibleCells grownFirst = renderer.buildVisibleCells(buffers);
        S3kSlotRenderBuffers.VisibleCells grownSecond = renderer.buildVisibleCells(buffers);
        assertSame(first, grownFirst);
        assertSame(second, grownSecond);
        assertEquals(16 * 16, grownFirst.size());
        assertEquals(16 * 16, grownSecond.size());
        assertTrue(arrayLength(primitiveBackings(grownFirst)[0]) >= 16 * 16);
        assertTrue(arrayLength(primitiveBackings(grownSecond)[0]) >= 16 * 16);
        assertNotSame(initialBackings.get(first)[0], primitiveBackings(grownFirst)[0]);
        assertNotSame(initialBackings.get(second)[0], primitiveBackings(grownSecond)[0]);

        Object[] firstGrownBackings = primitiveBackings(grownFirst);
        Object[] secondGrownBackings = primitiveBackings(grownSecond);
        Set<S3kSlotRenderBuffers.VisibleCells> frameIdentities =
                Collections.newSetFromMap(new IdentityHashMap<>());
        for (int frame = 0; frame < 600; frame++) {
            S3kSlotRenderBuffers.VisibleCells visible = renderer.buildVisibleCells(buffers);
            frameIdentities.add(visible);
            Object[] expected = visible == first ? firstGrownBackings : secondGrownBackings;
            Object[] actual = primitiveBackings(visible);
            assertEquals(expected.length, actual.length);
            for (int backing = 0; backing < expected.length; backing++) {
                assertSame(expected[backing], actual[backing],
                        "Backing " + backing + " changed on steady frame " + frame);
            }
        }
        assertEquals(2, frameIdentities.size());
    }

    @Test
    void slotWallAnimationCommitsNextPermanentTileIntoCompactAndExpandedLayout() {
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        int layoutIndex = findFirstTile(buffers.layout(), 0x01);
        int expandedIndex = buffers.compactToExpandedIndex(layoutIndex);

        assertTrue(layoutIndex >= 0);
        assertTrue(buffers.startSlotWallAnimationAt(layoutIndex, 0x02));
        // ROM loc_4BF30 (sonic3k.asm:99283-99300) only claims the slot; the resting
        // tile is still in the layout until loc_4B65A's first sub_4B592 pass
        // (sonic3k.asm:98499-98513) publishes byte_4B688[0] = $D.
        assertEquals(0x01, buffers.layout()[layoutIndex] & 0xFF);
        buffers.tickTransientAnimations();
        assertEquals(0x0D, buffers.layout()[layoutIndex] & 0xFF);
        assertEquals(0x0D, buffers.expandedLayout()[expandedIndex] & 0xFF);

        // loc_4B65A reloads its countdown with #1 and tests it for negative, so each
        // of the remaining flash colours costs 2 passes, and the table's 0 terminator
        // -- one entry past the 24 colours -- is what restores the advanced tile.
        int period = S3kSlotRomData.SLOT_WALL_COLOR_DELAY + 1;
        for (int i = 0; i < S3kSlotRomData.SLOT_WALL_COLOR_FRAMES.length * period; i++) {
            buffers.tickTransientAnimations();
        }

        assertEquals(0x02, buffers.layout()[layoutIndex] & 0xFF);
        assertEquals(0x02, buffers.expandedLayout()[expandedIndex] & 0xFF);
    }

    private static int findFirstTile(byte[] layout, int tileId) {
        for (int i = 0; i < layout.length; i++) {
            if ((layout[i] & 0xFF) == tileId) {
                return i;
            }
        }
        return -1;
    }

    private static final int BOOTSTRAP_CAMERA_X = S3kSlotRomData.SLOT_BONUS_PLAYER_START_X - 0xA0;
    private static final int BOOTSTRAP_CAMERA_Y = S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y - 0x70;

    private static S3kSlotRenderBuffers stagedBuffers(S3kSlotLayoutRenderer renderer) {
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();
        buffers.stageViewport(BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y);
        buffers.stagePointGrid(renderer.buildPointGrid(0, BOOTSTRAP_CAMERA_X, BOOTSTRAP_CAMERA_Y));
        return buffers;
    }

    private static void assertPrimitiveView(S3kSlotRenderBuffers.VisibleCells visible) {
        assertEquals(3, primitiveBackings(visible).length);
    }

    private static Object[] primitiveBackings(S3kSlotRenderBuffers.VisibleCells visible) {
        try {
            return Arrays.stream(visible.getClass().getDeclaredFields())
                    .filter(field -> field.getType().isArray() && field.getType().getComponentType().isPrimitive())
                    .peek(field -> field.setAccessible(true))
                    .map(field -> read(field, visible))
                    .toArray();
        } catch (RuntimeException e) {
            throw new AssertionError("Visible view must own private primitive backing arrays", e);
        }
    }

    private static Object read(Field field, Object target) {
        try {
            return field.get(target);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static int arrayLength(Object array) {
        return java.lang.reflect.Array.getLength(array);
    }

    private static int[] snapshot(S3kSlotRenderBuffers.VisibleCells visible) {
        int[] values = new int[visible.size() * 3];
        for (int i = 0; i < visible.size(); i++) {
            values[i * 3] = visible.cellIdAt(i);
            values[i * 3 + 1] = visible.worldXAt(i);
            values[i * 3 + 2] = visible.worldYAt(i);
        }
        return values;
    }
}
