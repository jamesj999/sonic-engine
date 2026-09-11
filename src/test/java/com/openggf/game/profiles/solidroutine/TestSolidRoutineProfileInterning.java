package com.openggf.game.profiles.solidroutine;

import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The solid path rebuilds a routine profile per solid object per player per
 * frame, so the profiles are interned. These tests pin the properties that make
 * that sound — above all that it caches rather than freezes: an object whose
 * solidity genuinely changes must still get the new profile, or a monitor would
 * stay solid after being broken.
 */
class TestSolidRoutineProfileInterning {

    /** Mutable provider so a test can change solidity between calls. */
    private static final class MutableProvider implements SolidObjectProvider {
        private boolean topSolidOnly;
        private boolean monitorSolidity;
        private int monitorVerticalOffset;
        private boolean stickyContactBuffer;

        @Override
        public SolidObjectParams getSolidParams() {
            return new SolidObjectParams(16, 16, 16);
        }

        @Override
        public boolean isTopSolidOnly() {
            return topSolidOnly;
        }

        @Override
        public boolean hasMonitorSolidity() {
            return monitorSolidity;
        }

        @Override
        public int getMonitorSolidObjectVerticalOffset() {
            return monitorVerticalOffset;
        }

        @Override
        public boolean usesStickyContactBuffer() {
            return stickyContactBuffer;
        }
    }

    @Test
    void repeatedCallsForAnUnchangedProviderReturnTheSameInstance() {
        MutableProvider provider = new MutableProvider();

        SolidRoutineProfile first = SolidRoutineProfile.fromProvider(provider);
        SolidRoutineProfile second = SolidRoutineProfile.fromProvider(provider);

        assertSame(first, second, "an unchanged provider must not allocate a second profile");
    }

    @Test
    void aProviderWhoseSolidityChangesGetsANewProfile() {
        // The correctness property the whole optimisation rests on. A monitor
        // that stops being solid, or a platform that flips to top-solid, must
        // not keep serving its previous cached profile.
        MutableProvider provider = new MutableProvider();
        SolidRoutineProfile beforeChange = SolidRoutineProfile.fromProvider(provider);
        assertEquals(SolidRoutineKind.FULL_SOLID, beforeChange.kind());

        provider.topSolidOnly = true;
        SolidRoutineProfile afterChange = SolidRoutineProfile.fromProvider(provider);

        assertEquals(SolidRoutineKind.TOP_SOLID_ONLY, afterChange.kind());
        assertTrue(afterChange.topSolidOnly());
        assertNotEquals(beforeChange, afterChange);
    }

    @Test
    void changingBackReturnsTheOriginalProfileAgain() {
        MutableProvider provider = new MutableProvider();
        SolidRoutineProfile original = SolidRoutineProfile.fromProvider(provider);

        provider.monitorSolidity = true;
        SolidRoutineProfile monitor = SolidRoutineProfile.fromProvider(provider);
        provider.monitorSolidity = false;
        SolidRoutineProfile restored = SolidRoutineProfile.fromProvider(provider);

        assertEquals(SolidRoutineKind.MONITOR_SOLID, monitor.kind());
        assertEquals(original, restored);
    }

    @Test
    void monitorVerticalOffsetIsPartOfTheIdentity() {
        // The offset is the one non-boolean field; packing it into the high half
        // of the key is what keeps two offsets from aliasing onto one entry.
        MutableProvider provider = new MutableProvider();
        provider.monitorSolidity = true;
        provider.monitorVerticalOffset = 12;
        SolidRoutineProfile twelve = SolidRoutineProfile.fromProvider(provider);

        provider.monitorVerticalOffset = 13;
        SolidRoutineProfile thirteen = SolidRoutineProfile.fromProvider(provider);

        assertEquals(12, twelve.monitorVerticalOffset());
        assertEquals(13, thirteen.monitorVerticalOffset());
    }

    @Test
    void negativeMonitorOffsetsRoundTrip() {
        MutableProvider provider = new MutableProvider();
        provider.monitorSolidity = true;
        provider.monitorVerticalOffset = -20;

        SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(provider);

        assertEquals(-20, profile.monitorVerticalOffset());
        assertSame(profile, SolidRoutineProfile.fromProvider(provider));
    }

    @Test
    void everyDistinctFlagCombinationKeepsItsOwnProfile() {
        // Exhaustive over the flags this provider can vary: proves no two
        // distinct field sets collapse onto one cached entry.
        MutableProvider provider = new MutableProvider();
        for (int mask = 0; mask < 8; mask++) {
            provider.topSolidOnly = (mask & 1) != 0;
            provider.monitorSolidity = (mask & 2) != 0;
            provider.stickyContactBuffer = (mask & 4) != 0;

            SolidRoutineProfile profile = SolidRoutineProfile.fromProvider(provider);

            assertEquals(provider.topSolidOnly, profile.topSolidOnly(), "mask " + mask);
            assertEquals(provider.monitorSolidity, profile.monitorSolidity(), "mask " + mask);
            assertEquals(provider.stickyContactBuffer, profile.stickyContactBuffer(), "mask " + mask);
        }
    }

    @Test
    void signatureDistinguishesEveryFieldItCovers() {
        long base = SolidRoutineProfileInterner.signature(
                false, false, 0, false, false, false, false, false, false, false, false, false, false);
        assertNotEquals(0L, base, "the all-false signature must still be non-zero");

        for (int flag = 0; flag < 12; flag++) {
            boolean[] flags = new boolean[12];
            flags[flag] = true;
            long signature = SolidRoutineProfileInterner.signature(
                    flags[0], flags[1], 0, flags[2], flags[3], flags[4], flags[5],
                    flags[6], flags[7], flags[8], flags[9], flags[10], flags[11]);
            assertNotEquals(base, signature, "flag " + flag + " must change the signature");
        }
        assertNotEquals(base, SolidRoutineProfileInterner.signature(
                false, false, 1, false, false, false, false, false, false, false, false, false, false),
                "the monitor offset must change the signature");
    }

    @Test
    void theLevelSideViewIsInternedToo() {
        MutableProvider provider = new MutableProvider();

        com.openggf.level.objects.SolidRoutineProfile first =
                com.openggf.level.objects.SolidRoutineProfile.fromProvider(provider);
        com.openggf.level.objects.SolidRoutineProfile second =
                com.openggf.level.objects.SolidRoutineProfile.fromProvider(provider);

        assertSame(first, second, "the converted view must be cached as well");
    }

    @Test
    void theLevelSideViewCarriesTheSameValuesAsTheCanonicalProfile() {
        MutableProvider provider = new MutableProvider();
        provider.monitorSolidity = true;
        provider.monitorVerticalOffset = 7;
        provider.stickyContactBuffer = true;

        SolidRoutineProfile canonical = SolidRoutineProfile.fromProvider(provider);
        com.openggf.level.objects.SolidRoutineProfile view =
                com.openggf.level.objects.SolidRoutineProfile.fromProvider(provider);

        assertEquals(canonical.monitorSolidity(), view.monitorSolidity());
        assertEquals(canonical.monitorVerticalOffset(), view.monitorVerticalOffset());
        assertEquals(canonical.stickyContactBuffer(), view.stickyContactBuffer());
        assertEquals(canonical, view.toCanonical(), "round-tripping must preserve every field");
    }

    @Test
    void anotherThreadGetsAnEqualProfileFromItsOwnCache() throws Exception {
        // The cache is thread-confined, so a second thread builds its own entry.
        // Value equality across threads is what makes that safe; identity is not
        // promised and nothing in the engine relies on it.
        MutableProvider provider = new MutableProvider();
        provider.stickyContactBuffer = true;
        SolidRoutineProfile onThisThread = SolidRoutineProfile.fromProvider(provider);

        AtomicReference<SolidRoutineProfile> other = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            other.set(SolidRoutineProfile.fromProvider(provider));
            done.countDown();
        });
        worker.start();
        assertTrue(done.await(10, TimeUnit.SECONDS), "worker thread did not finish");
        worker.join();

        assertNotNull(other.get());
        assertEquals(onThisThread, other.get(), "profiles must be value-equal across threads");
    }
}
