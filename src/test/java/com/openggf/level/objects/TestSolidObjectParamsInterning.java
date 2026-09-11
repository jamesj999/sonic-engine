package com.openggf.level.objects;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SolidObjectParams#of} shares instances for equal collision boxes, which
 * is only safe if it is exact — two different boxes must never collapse onto one
 * cached entry, or an object would collide with someone else's dimensions.
 */
class TestSolidObjectParamsInterning {

    @Test
    void equalBoxesShareOneInstance() {
        assertSame(SolidObjectParams.of(19, 24, 25), SolidObjectParams.of(19, 24, 25));
        assertSame(SolidObjectParams.of(8, 16, 17, 3, -4),
                SolidObjectParams.of(8, 16, 17, 3, -4));
    }

    @Test
    void everyComponentIsPartOfTheIdentity() {
        // Each field varied on its own: the cache must not treat any of the five
        // as interchangeable, and a hash collision must be resolved by the exact
        // key comparison rather than returning the wrong box.
        SolidObjectParams base = SolidObjectParams.of(10, 20, 30, 40, 50);
        assertNotSame(base, SolidObjectParams.of(11, 20, 30, 40, 50));
        assertNotSame(base, SolidObjectParams.of(10, 21, 30, 40, 50));
        assertNotSame(base, SolidObjectParams.of(10, 20, 31, 40, 50));
        assertNotSame(base, SolidObjectParams.of(10, 20, 30, 41, 50));
        assertNotSame(base, SolidObjectParams.of(10, 20, 30, 40, 51));

        assertEquals(10, base.halfWidth());
        assertEquals(20, base.airHalfHeight());
        assertEquals(30, base.groundHalfHeight());
        assertEquals(40, base.offsetX());
        assertEquals(50, base.offsetY());
    }

    @Test
    void theThreeArgFormMatchesTheFiveArgFormWithZeroOffsets() {
        assertSame(SolidObjectParams.of(19, 24, 25), SolidObjectParams.of(19, 24, 25, 0, 0));
        assertEquals(new SolidObjectParams(19, 24, 25), SolidObjectParams.of(19, 24, 25));
    }

    @Test
    void negativeAndZeroComponentsRoundTrip() {
        SolidObjectParams negative = SolidObjectParams.of(-1, 0, -32768, -5, -6);

        assertEquals(-1, negative.halfWidth());
        assertEquals(0, negative.airHalfHeight());
        assertEquals(-32768, negative.groundHalfHeight());
        assertEquals(-5, negative.offsetX());
        assertEquals(-6, negative.offsetY());
        assertSame(negative, SolidObjectParams.of(-1, 0, -32768, -5, -6));
    }

    @Test
    void manyDistinctBoxesAllKeepTheirOwnValues() {
        // Deliberately more distinct boxes than the table has slots, so entries
        // are evicted mid-run. Eviction may cost a fresh allocation; it must
        // never hand back another box's dimensions.
        for (int i = 0; i < 5000; i++) {
            SolidObjectParams params = SolidObjectParams.of(i, i + 1, i + 2, i + 3, i + 4);
            assertEquals(i, params.halfWidth(), "box " + i);
            assertEquals(i + 1, params.airHalfHeight(), "box " + i);
            assertEquals(i + 2, params.groundHalfHeight(), "box " + i);
            assertEquals(i + 3, params.offsetX(), "box " + i);
            assertEquals(i + 4, params.offsetY(), "box " + i);
        }
    }

    @Test
    void anEvictedBoxIsStillCorrectWhenAskedForAgain() {
        SolidObjectParams first = SolidObjectParams.of(7, 7, 7);
        for (int i = 0; i < 5000; i++) {
            SolidObjectParams.of(1000 + i, i, i, i, i);
        }

        assertEquals(first, SolidObjectParams.of(7, 7, 7),
                "a box pushed out of the cache must still come back equal");
    }

    @Test
    void anotherThreadGetsAnEqualBoxFromItsOwnCache() throws Exception {
        SolidObjectParams onThisThread = SolidObjectParams.of(35, 6, 6);

        AtomicReference<SolidObjectParams> other = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            other.set(SolidObjectParams.of(35, 6, 6));
            done.countDown();
        });
        worker.start();
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker thread did not finish");
        worker.join();

        assertEquals(onThisThread, other.get(), "boxes must be value-equal across threads");
    }
}
