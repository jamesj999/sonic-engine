package com.openggf.graphics;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestPatternAtlasDynamicRanges {
    private static final int MOD_BASE = PatternAtlasRange.CONTINUE_SCREEN.endExclusive();
    private static final int WINDOW_SIZE = 0x8000;

    @Test
    void dynamicallyRegisteredAlignedRangeGovernsItsPatternIds() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        atlas.registerRange(MOD_BASE, WINDOW_SIZE, "mod:one");

        assertNotNull(atlas.cachePatternHeadless(new Pattern(), MOD_BASE));
        assertNotNull(atlas.cachePatternHeadless(new Pattern(), MOD_BASE + WINDOW_SIZE - 1));
        assertThrows(IllegalArgumentException.class,
                () -> atlas.cachePatternHeadless(new Pattern(), MOD_BASE + WINDOW_SIZE));
    }

    @Test
    void dynamicRangesRequirePositiveFourKilopatternAlignment() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        assertThrows(IllegalArgumentException.class,
                () -> atlas.registerRange(MOD_BASE + 1, WINDOW_SIZE, "misaligned-base"));
        assertThrows(IllegalArgumentException.class,
                () -> atlas.registerRange(MOD_BASE, WINDOW_SIZE - 1, "misaligned-size"));
        assertThrows(IllegalArgumentException.class,
                () -> atlas.registerRange(MOD_BASE, 0, "empty"));
        assertDoesNotThrow(() -> atlas.registerRange(MOD_BASE, WINDOW_SIZE, "aligned"));
    }

    @Test
    void dynamicRangesCannotClaimStaticRangeOwnership() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        assertThrows(IllegalArgumentException.class,
                () -> atlas.registerRange(PatternAtlasRange.MGZ_ZOOM_CUES.base(),
                        WINDOW_SIZE, "mod:collision"));
    }

    @Test
    void rawExactStaticRangeSpoofIsRejectedWithoutPoisoningLegitimateRegistration() {
        PatternAtlas atlas = new PatternAtlas(256, 256);
        PatternAtlasRange reserved = PatternAtlasRange.MGZ_ZOOM_CUES;

        assertThrows(IllegalArgumentException.class,
                () -> atlas.registerRange(reserved.base(), reserved.size(),
                        reserved.category()));
        assertDoesNotThrow(() -> atlas.registerRange(reserved));
        assertEquals(List.of(new PatternAtlas.PatternRange(reserved.base(), reserved.size(),
                        reserved.category())),
                atlas.registeredRangesForTesting());
    }

    @Test
    void typedRegistrationRetainsVirtualDispatchThroughRawOverload() {
        RawOnlyRecordingAtlas atlas = new RawOnlyRecordingAtlas();

        atlas.registerRange(PatternAtlasRange.OBJECTS);

        assertEquals(1, atlas.rawRegistrations);
    }

    @Test
    void typedRegistrationAuthorityIsClearedWhenSubclassThrows() {
        ThrowingRawAtlas atlas = new ThrowingRawAtlas();
        PatternAtlasRange reserved = PatternAtlasRange.OBJECTS;

        assertThrows(IllegalStateException.class, () -> atlas.registerRange(reserved));
        assertThrows(IllegalArgumentException.class,
                () -> atlas.registerRange(reserved.base(), reserved.size(), reserved.category()));
        assertEquals(List.of(), atlas.registeredRangesForTesting());
    }

    @Test
    void nestedTypedRegistrationsUseIndependentAuthority() {
        ReentrantRawAtlas atlas = new ReentrantRawAtlas();

        atlas.registerRange(PatternAtlasRange.OBJECTS);

        assertEquals(List.of(
                        new PatternAtlas.PatternRange(PatternAtlasRange.HUD.base(),
                                PatternAtlasRange.HUD.size(), PatternAtlasRange.HUD.category()),
                        new PatternAtlas.PatternRange(PatternAtlasRange.OBJECTS.base(),
                                PatternAtlasRange.OBJECTS.size(),
                                PatternAtlasRange.OBJECTS.category())),
                atlas.registeredRangesForTesting());
    }

    private static final class RawOnlyRecordingAtlas extends PatternAtlas {
        private int rawRegistrations;

        private RawOnlyRecordingAtlas() {
            super(256, 256);
        }

        @Override
        public void registerRange(int base, int size, String category) {
            rawRegistrations++;
            super.registerRange(base, size, category);
        }
    }

    private static final class ThrowingRawAtlas extends PatternAtlas {
        private boolean throwNext = true;

        private ThrowingRawAtlas() {
            super(256, 256);
        }

        @Override
        public void registerRange(int base, int size, String category) {
            if (throwNext) {
                throwNext = false;
                throw new IllegalStateException("injected failure");
            }
            super.registerRange(base, size, category);
        }
    }

    private static final class ReentrantRawAtlas extends PatternAtlas {
        private boolean nested;

        private ReentrantRawAtlas() {
            super(256, 256);
        }

        @Override
        public void registerRange(int base, int size, String category) {
            if (!nested && base == PatternAtlasRange.OBJECTS.base()) {
                nested = true;
                registerRange(PatternAtlasRange.HUD);
            }
            super.registerRange(base, size, category);
        }
    }

}
