package com.openggf.graphics;

import com.openggf.graphics.PatternAtlas.Entry;
import com.openggf.graphics.PatternAtlas.SparsePatternMap;
import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for the tiered, boxing-free pattern lookup introduced when the
 * sparse HashMap was replaced by per-{@link PatternAtlasRange} flat arrays plus
 * an open-addressing fallback map ({@link SparsePatternMap}).
 */
public class TestPatternAtlasLookup {

    private static Entry entry(int patternId) {
        return new Entry(patternId, 0, 0, 0, 0, 0f, 0f, 0f, 0f);
    }

    // ------------------------------------------------------------------
    // Range-array tier (IDs governed by PatternAtlasRange)
    // ------------------------------------------------------------------

    @Test
    public void rangeArrayHitMissAndReclamationParity() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        Pattern p = new Pattern();

        int objectsId = PatternAtlasRange.OBJECTS.base();
        assertNull(atlas.getEntry(objectsId), "miss before caching");

        Entry cached = atlas.cachePatternHeadless(p, objectsId);
        assertNotNull(cached);
        assertSame(cached, atlas.getEntry(objectsId), "hit after caching");
        assertNull(atlas.getEntry(objectsId + 1), "neighbouring ID stays a miss");

        // Remove, look up again, then re-cache: same observable behavior as the map
        assertTrue(atlas.removeEntry(objectsId));
        assertNull(atlas.getEntry(objectsId), "miss after removal");
        assertFalse(atlas.removeEntry(objectsId), "second removal reports not-cached");

        Entry recached = atlas.cachePatternHeadless(p, objectsId);
        assertNotNull(recached);
        assertSame(recached, atlas.getEntry(objectsId));
        // Freed physical slot is reused
        assertEquals(cached.tileX(), recached.tileX());
        assertEquals(cached.tileY(), recached.tileY());
    }

    @Test
    public void lookupResolvesAcrossDistinctRanges() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        Pattern p = new Pattern();

        int hudId = PatternAtlasRange.HUD.base() + 7;
        int sidekickId = PatternAtlasRange.SIDEKICK_BANKS.base() + 0x123;
        int titleId = PatternAtlasRange.TITLE_CARDS.base();

        atlas.cachePatternHeadless(p, hudId);
        atlas.cachePatternHeadless(p, sidekickId);
        atlas.cachePatternHeadless(p, titleId);

        assertEquals(hudId, atlas.getEntry(hudId).patternId());
        assertEquals(sidekickId, atlas.getEntry(sidekickId).patternId());
        assertEquals(titleId, atlas.getEntry(titleId).patternId());
        // A never-touched range misses without allocating anything
        assertNull(atlas.getEntry(PatternAtlasRange.WATER_SURFACE.base()));
        // Out-of-governance and negative IDs miss cleanly
        assertNull(atlas.getEntry(0x2FFF));
        assertNull(atlas.getEntry(-1));
        assertNull(atlas.getEntry(Integer.MAX_VALUE));
    }

    @Test
    public void entriesCachedBeforeRangeRegistrationStayFindable() {
        // LevelManager.initObjectArt() caches object patterns BEFORE calling
        // registerRange(); storage is keyed off the static PatternAtlasRange enum,
        // so registration timing must not affect lookups.
        PatternAtlas atlas = new PatternAtlas(256, 256);
        Pattern p = new Pattern();

        int objectsId = PatternAtlasRange.OBJECTS.base() + 5;
        Entry cached = atlas.cachePatternHeadless(p, objectsId);
        assertNotNull(cached);

        atlas.registerRange(PatternAtlasRange.OBJECTS.base(), 0x100, "Objects");
        assertSame(cached, atlas.getEntry(objectsId));
    }

    @Test
    public void tierBoundaryAtLevelTilesEndIsExact() {
        // Canary against FAST_ENTRIES_SIZE / LEVEL_TILES drift: the fast array must
        // end exactly where LEVEL_TILES ends, with the first ID past it being a
        // governance gap (cache throws) that aliases reach via the fallback map.
        PatternAtlas atlas = new PatternAtlas(256, 256);
        Pattern p = new Pattern();

        int lastFastId = PatternAtlasRange.LEVEL_TILES.endExclusive() - 1; // 0x1FFF
        int firstGapId = PatternAtlasRange.LEVEL_TILES.endExclusive(); // 0x2000

        Entry fast = atlas.cachePatternHeadless(p, lastFastId);
        assertNotNull(fast);
        assertSame(fast, atlas.getEntry(lastFastId));

        assertThrows(IllegalArgumentException.class,
                () -> atlas.cachePatternHeadless(p, firstGapId),
                "first ID past LEVEL_TILES must be outside governance");

        assertTrue(atlas.aliasEntry(firstGapId, lastFastId),
                "alias in the governance gap must route to the sparse fallback");
        Entry alias = atlas.getEntry(firstGapId);
        assertNotNull(alias);
        assertEquals(fast.slot(), alias.slot());
    }

    @Test
    public void cleanupClearsEveryLookupTier() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        Pattern p = new Pattern();

        atlas.cachePatternHeadless(p, 5); // fast array
        atlas.cachePatternHeadless(p, PatternAtlasRange.OBJECTS.base()); // range array
        atlas.aliasEntry(0x2100, PatternAtlasRange.OBJECTS.base()); // fallback map
        assertNotNull(atlas.getEntry(0x2100));

        atlas.cleanupHeadless();

        assertNull(atlas.getEntry(5));
        assertNull(atlas.getEntry(PatternAtlasRange.OBJECTS.base()));
        assertNull(atlas.getEntry(0x2100));
    }

    // ------------------------------------------------------------------
    // Fallback-map tier (IDs outside fast array and every range)
    // ------------------------------------------------------------------

    @Test
    public void unrangedAliasIdsRouteThroughFallbackMap() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        Pattern p = new Pattern();

        int targetId = PatternAtlasRange.OBJECTS.base();
        Entry target = atlas.cachePatternHeadless(p, targetId);
        assertNotNull(target);

        // 0x2100 sits in the governance gap between LEVEL_TILES (ends 0x2000) and
        // SPECIAL_STAGE_PLAYFIELD (starts 0x3000); 0x200000 is above every range.
        assertTrue(atlas.aliasEntry(0x2100, targetId));
        assertTrue(atlas.aliasEntry(0x200000, targetId));

        Entry gapAlias = atlas.getEntry(0x2100);
        Entry highAlias = atlas.getEntry(0x200000);
        assertNotNull(gapAlias);
        assertNotNull(highAlias);
        assertEquals(target.slot(), gapAlias.slot());
        assertEquals(target.slot(), highAlias.slot());

        // Removing a fallback alias must not free the shared slot
        assertTrue(atlas.removeEntry(0x2100));
        assertNull(atlas.getEntry(0x2100));
        assertNotNull(atlas.getEntry(targetId));
        assertNotNull(atlas.getEntry(0x200000));
    }

    @Test
    public void fallbackMapHandlesForcedBucketCollisions() {
        SparsePatternMap map = new SparsePatternMap();

        // Brute-force keys that all hash to the same bucket at the initial
        // capacity (16) so collision probing is exercised deterministically.
        int targetBucket = SparsePatternMap.indexFor(0x2100, 16);
        List<Integer> colliding = new ArrayList<>();
        for (int key = 0x2100; colliding.size() < 5; key++) {
            if (SparsePatternMap.indexFor(key, 16) == targetBucket) {
                colliding.add(key);
            }
        }

        for (int key : colliding) {
            assertNull(map.put(key, entry(key)));
        }
        for (int key : colliding) {
            assertEquals(key, map.get(key).patternId());
        }

        // Remove a key in the middle of the probe chain: later keys in the chain
        // must still be reachable across the tombstone.
        int removed = colliding.get(2);
        assertNotNull(map.remove(removed));
        assertNull(map.get(removed));
        for (int key : colliding) {
            if (key != removed) {
                assertEquals(key, map.get(key).patternId());
            }
        }

        // Re-inserting the removed key reuses the tombstone slot and is found again
        assertNull(map.put(removed, entry(removed)));
        assertEquals(removed, map.get(removed).patternId());

        // Overwriting an existing key returns the displaced entry
        Entry replacement = entry(colliding.get(0));
        Entry displaced = map.put(colliding.get(0), replacement);
        assertNotNull(displaced);
        assertSame(replacement, map.get(colliding.get(0)));
    }

    @Test
    public void fallbackMapSurvivesGrowthAndTombstoneChurn() {
        SparsePatternMap map = new SparsePatternMap();
        int initialCapacity = map.capacity();

        // Enough entries to force several resizes past the initial capacity
        for (int i = 0; i < 200; i++) {
            assertNull(map.put(0x200000 + i * 17, entry(0x200000 + i * 17)));
        }
        assertEquals(200, map.size());
        assertTrue(map.capacity() > initialCapacity, "table must have grown");
        for (int i = 0; i < 200; i++) {
            int key = 0x200000 + i * 17;
            assertEquals(key, map.get(key).patternId());
        }

        // Delete every other key, then verify hits and misses are exact
        for (int i = 0; i < 200; i += 2) {
            assertNotNull(map.remove(0x200000 + i * 17));
        }
        assertEquals(100, map.size());
        for (int i = 0; i < 200; i++) {
            int key = 0x200000 + i * 17;
            if (i % 2 == 0) {
                assertNull(map.get(key));
            } else {
                assertEquals(key, map.get(key).patternId());
            }
        }

        // Tombstone-heavy churn: re-add and remove repeatedly at stable size to
        // force same-capacity rehashes that purge tombstones.
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 200; i += 2) {
                int key = 0x200000 + i * 17;
                assertNull(map.put(key, entry(key)));
                assertNotNull(map.remove(key));
            }
        }
        assertEquals(100, map.size());
        for (int i = 1; i < 200; i += 2) {
            int key = 0x200000 + i * 17;
            assertEquals(key, map.get(key).patternId());
        }

        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get(0x200000 + 17));
    }

    @Test
    public void fallbackMapSupportsNegativeKeys() {
        SparsePatternMap map = new SparsePatternMap();
        assertNull(map.put(-42, entry(-42)));
        assertEquals(-42, map.get(-42).patternId());
        assertNotNull(map.remove(-42));
        assertNull(map.get(-42));
    }
}
