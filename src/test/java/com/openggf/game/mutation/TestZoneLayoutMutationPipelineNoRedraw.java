package com.openggf.game.mutation;

import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.Map;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TestZoneLayoutMutationPipelineNoRedraw {

    // ── Test 1 ────────────────────────────────────────────────────────────

    /**
     * applyImmediatelyWithoutRedraw must write the block into the map
     * (mutation applied) but must not publish any redraw hints to the
     * effect sink.
     */
    @Test
    void applyImmediatelyWithoutRedrawAppliesMutationButSuppressesRedrawHint() {
        StubLevel level = new StubLevel(2, 4);
        level.getMap().setValue(0, 0, 0, (byte) 7); // set initial value

        List<MutationEffects> published = new ArrayList<>();
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                published::add);

        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();

        // Intent uses the no-redraw surface method
        LayoutMutationIntent intent = ctx -> ctx.surface().setBlockInMapWithoutRedraw(0, 0, 0, 42);

        pipeline.applyImmediatelyWithoutRedraw(intent, context);

        // Mutation was applied
        assertEquals((byte) 42, level.getMap().getValue(0, 0, 0),
                "Map block should have been updated to 42");

        // No redraw hints in any published effects
        assertFalse(published.isEmpty(), "Effect sink should have been called");
        for (MutationEffects effects : published) {
            assertFalse(effects.foregroundRedrawRequired(),
                    "foregroundRedrawRequired must be false");
            assertFalse(effects.allTilemapsRedrawRequired(),
                    "allTilemapsRedrawRequired must be false");
            assertFalse(effects.dirtyRegionProcessingRequired(),
                    "dirtyRegionProcessingRequired must be false");
        }
    }

    // ── Test 2 ────────────────────────────────────────────────────────────

    /**
     * applyImmediatelyWithoutRedraw must mutate the live map data even after
     * an epoch bump (CoW path). The mutation should be visible on the live map
     * and must not affect a snapshot captured before the mutation.
     *
     * <p>This test verifies the CoW contract indirectly: after a snapshot is
     * taken and the epoch is bumped, a no-redraw mutation on the live map must
     * not clobber the snapshot's data.
     */
    @Test
    void applyImmediatelyWithoutRedrawTriggersCowOnMap() {
        StubLevel level = new StubLevel(2, 4);
        Map map = level.getMap();
        map.setValue(0, 1, 0, (byte) 5); // pre-seed a value

        // Simulate a snapshot retaining the pre-mutation backing array.
        byte[] snapshotData = map.getData();
        byte snapshotValue = snapshotData[1]; // layer 0, x 1, y 0

        // Advance the epoch — subsequent mutations must clone before writing.
        level.bumpEpoch();

        ZoneLayoutMutationPipeline pipeline = new ZoneLayoutMutationPipeline();
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                effects -> { /* no-op sink */ });

        LayoutMutationIntent intent = ctx -> ctx.surface().setBlockInMapWithoutRedraw(0, 1, 0, 99);
        pipeline.applyImmediatelyWithoutRedraw(intent, context);

        // Live map reflects the mutation
        assertEquals((byte) 99, map.getValue(0, 1, 0),
                "Map block should have been updated to 99");

        assertNotSame(snapshotData, map.getData(),
                "Live map storage should clone before mutating after an epoch bump");
        assertEquals((byte) 5, snapshotData[1],
                "Snapshot backing array should not have been affected by the mutation");
        assertEquals((byte) 5, snapshotValue,
                "Snapshot value should preserve the pre-mutation content");
    }

    // ── Test 3 ────────────────────────────────────────────────────────────

    /**
     * withoutRedrawHints() on MutationEffects strips redraw flags but
     * preserves objectResyncRequired and ringResyncRequired.
     */
    @Test
    void withoutRedrawHintsStripsOnlyRedrawFlags() {
        MutationEffects base = new MutationEffects(
                new java.util.BitSet(), true, true, true, true, true, true);

        MutationEffects stripped = base.withoutRedrawHints();

        assertFalse(stripped.dirtyRegionProcessingRequired());
        assertFalse(stripped.foregroundRedrawRequired());
        assertFalse(stripped.allTilemapsRedrawRequired());
        assertTrue(stripped.objectResyncRequired());
        assertTrue(stripped.ringResyncRequired());
    }

    // ── Minimal stub level ────────────────────────────────────────────────

    /** Minimal AbstractLevel subclass suitable for mutation surface tests. */
    private static final class StubLevel extends AbstractLevel {

        StubLevel(int layers, int mapSide) {
            super(0);
            palettes = new Palette[4];
            for (int i = 0; i < 4; i++) palettes[i] = new Palette();
            patternCount = 0;
            patterns = new Pattern[0];
            chunkCount = 0;
            chunks = new Chunk[0];
            blockCount = 0;
            blocks = new Block[0];
            solidTileCount = 0;
            solidTiles = new SolidTile[0];
            map = new Map(layers, mapSide, mapSide);
            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = mapSide * 128;
            minY = 0;
            maxY = mapSide * 128;
        }

        @Override
        public SolidTile getSolidTile(int index) { return null; }

        @Override
        public List<ObjectSpawn> getObjects() { return List.of(); }

        @Override
        public List<RingSpawn> getRings() { return List.of(); }

        @Override
        public RingSpriteSheet getRingSpriteSheet() { return null; }
    }
}
