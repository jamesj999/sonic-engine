package com.openggf.game.launch;

import com.openggf.game.MasterTitleScreen;
import org.junit.jupiter.api.Test;

import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_1;
import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_2;
import static com.openggf.game.MasterTitleScreen.GameEntry.SONIC_3K;
import static com.openggf.game.launch.LaunchProfile.Row.CROSS_GAME;
import static com.openggf.game.launch.LaunchProfile.Row.DEBUG_TOOLS;
import static com.openggf.game.launch.LaunchProfile.Row.MAIN_CHARACTER;
import static com.openggf.game.launch.LaunchProfile.Row.REWIND;
import static com.openggf.game.launch.LaunchProfile.Row.SIDEKICK;
import static com.openggf.game.launch.LaunchProfile.Row.WIDESCREEN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLaunchProfile {

    @Test
    void stockProfilesMatchEachGameDefaults() {
        assertStock(SONIC_1, new LaunchProfile(false, "off", false, "global", "sonic", "none"));
        assertStock(SONIC_2, new LaunchProfile(false, "off", false, "global", "sonic", "tails"));
        assertStock(SONIC_3K, new LaunchProfile(false, "off", false, "global", "sonic", "tails"));
    }

    @Test
    void rowCyclingUsesPlanOrderAndWrapsBothDirections() {
        LaunchProfile s1 = LaunchProfile.stockFor(SONIC_1);
        assertEquals(true, s1.withNext(REWIND, SONIC_1).rewind());
        assertEquals(true, s1.withPrevious(REWIND, SONIC_1).rewind());

        assertEquals("s2", s1.withNext(CROSS_GAME, SONIC_1).crossGameSource());
        assertEquals("s3k", s1.withPrevious(CROSS_GAME, SONIC_1).crossGameSource());
        assertEquals("s1", LaunchProfile.stockFor(SONIC_2).withNext(CROSS_GAME, SONIC_2).crossGameSource());
        assertEquals("s2", LaunchProfile.stockFor(SONIC_3K).withPrevious(CROSS_GAME, SONIC_3K).crossGameSource());

        assertEquals(true, s1.withNext(DEBUG_TOOLS, SONIC_1).debugTools());
        assertEquals(true, s1.withPrevious(DEBUG_TOOLS, SONIC_1).debugTools());

        assertEquals("NATIVE_4_3", s1.withNext(WIDESCREEN, SONIC_1).aspect());
        assertEquals("SUPER_32_9", s1.withPrevious(WIDESCREEN, SONIC_1).aspect());

        assertEquals("sonic", s1.withNext(MAIN_CHARACTER, SONIC_1).mainCharacter());
        assertEquals("sonic", s1.withPrevious(MAIN_CHARACTER, SONIC_1).mainCharacter());

        assertEquals("sonic", s1.withNext(SIDEKICK, SONIC_1).sidekick());
        assertEquals("sonic", s1.withPrevious(SIDEKICK, SONIC_1).sidekick());
        LaunchProfile s2 = LaunchProfile.stockFor(SONIC_2);
        assertEquals("none", s2.withNext(SIDEKICK, SONIC_2).sidekick());
        assertEquals("sonic", s2.withPrevious(SIDEKICK, SONIC_2).sidekick());
    }

    @Test
    void characterRowsOnlyOfferCharactersAvailableFromCurrentDonor() {
        LaunchProfile s1UsingS2Donor = new LaunchProfile(false, "s2", false, "global", "sonic", "none");
        assertEquals("tails", s1UsingS2Donor.withNext(MAIN_CHARACTER, SONIC_1).mainCharacter());
        assertEquals("tails", s1UsingS2Donor.withPrevious(MAIN_CHARACTER, SONIC_1).mainCharacter());
        assertEquals("tails", s1UsingS2Donor.withNext(SIDEKICK, SONIC_1).sidekick());
        assertEquals("sonic", s1UsingS2Donor.withPrevious(SIDEKICK, SONIC_1).sidekick());

        LaunchProfile s1S2DonorWithSonicSidekick =
                new LaunchProfile(false, "s2", false, "global", "sonic", "sonic");
        assertEquals("none", s1S2DonorWithSonicSidekick.withNext(SIDEKICK, SONIC_1).sidekick());
        assertEquals("tails", s1S2DonorWithSonicSidekick.withPrevious(SIDEKICK, SONIC_1).sidekick());

        LaunchProfile s2UsingS1Donor = new LaunchProfile(false, "s1", false, "global", "sonic", "none");
        assertEquals("sonic", s2UsingS1Donor.withNext(MAIN_CHARACTER, SONIC_2).mainCharacter());
        assertEquals("sonic", s2UsingS1Donor.withPrevious(MAIN_CHARACTER, SONIC_2).mainCharacter());
        assertEquals("sonic", s2UsingS1Donor.withNext(SIDEKICK, SONIC_2).sidekick());
    }

    @Test
    void crossGameCyclingClampsCharactersUnavailableFromNewDonor() {
        LaunchProfile s1UsingS3kCharacters =
                new LaunchProfile(false, "s3k", false, "global", "knuckles", "knuckles");

        LaunchProfile s1StockDonor = s1UsingS3kCharacters.withNext(CROSS_GAME, SONIC_1);
        assertEquals("off", s1StockDonor.crossGameSource());
        assertEquals("sonic", s1StockDonor.mainCharacter());
        assertEquals("none", s1StockDonor.sidekick());

        LaunchProfile s1S2Donor = s1UsingS3kCharacters.withPrevious(CROSS_GAME, SONIC_1);
        assertEquals("s2", s1S2Donor.crossGameSource());
        assertEquals("sonic", s1S2Donor.mainCharacter());
        assertEquals("none", s1S2Donor.sidekick());
    }

    @Test
    void stockAndEnabledCountAreBasedOnStockProfile() {
        LaunchProfile stock = LaunchProfile.stockFor(SONIC_3K);
        assertEquals(0, stock.enabledCount(SONIC_3K));
        assertTrue(stock.isStock(SIDEKICK, SONIC_3K));

        LaunchProfile tailsAlone = new LaunchProfile(false, "off", false, "global", "tails", "none");
        assertEquals(2, tailsAlone.enabledCount(SONIC_3K));
        assertFalse(tailsAlone.isStock(MAIN_CHARACTER, SONIC_3K));
        assertFalse(tailsAlone.isStock(SIDEKICK, SONIC_3K));
        assertEquals(stock, tailsAlone.withStock(SONIC_3K));
    }

    @Test
    void s3kDonationEnabledCountIgnoresDataSelectOwnedCharacterRows() {
        LaunchProfile profile = new LaunchProfile(false, "s3k", false, "global", "knuckles", "none");

        assertEquals(1, profile.enabledCount(SONIC_2));
    }

    @Test
    void nonStandardRowsFollowOriginalGamePossibilityRules() {
        assertTrue(new LaunchProfile(true, "off", false, "global", "sonic", "none")
                .isNonStandard(REWIND, SONIC_1));
        assertTrue(new LaunchProfile(false, "s2", false, "global", "sonic", "none")
                .isNonStandard(CROSS_GAME, SONIC_1));
        assertTrue(new LaunchProfile(false, "off", true, "global", "sonic", "none")
                .isNonStandard(DEBUG_TOOLS, SONIC_1));

        assertFalse(new LaunchProfile(false, "off", false, "global", "sonic", "none")
                .isNonStandard(WIDESCREEN, SONIC_1));
        assertFalse(new LaunchProfile(false, "off", false, "NATIVE_4_3", "sonic", "none")
                .isNonStandard(WIDESCREEN, SONIC_1));
        for (String aspect : new String[] {"WIDE_16_10", "WIDE_16_9", "ULTRA_21_9", "SUPER_32_9"}) {
            assertTrue(new LaunchProfile(false, "off", false, aspect, "sonic", "none")
                    .isNonStandard(WIDESCREEN, SONIC_1));
        }

        assertCharacterPairStandard(SONIC_1, "sonic", "none");
        assertCharacterPairNonStandard(SONIC_1, "sonic", "tails");
        assertCharacterPairNonStandard(SONIC_1, "tails", "none");

        assertCharacterPairStandard(SONIC_2, "sonic", "none");
        assertCharacterPairStandard(SONIC_2, "sonic", "tails");
        assertCharacterPairStandard(SONIC_2, "tails", "none");
        assertCharacterPairNonStandard(SONIC_2, "knuckles", "none");

        assertCharacterPairStandard(SONIC_3K, "sonic", "none");
        assertCharacterPairStandard(SONIC_3K, "sonic", "tails");
        assertCharacterPairStandard(SONIC_3K, "tails", "none");
        assertCharacterPairStandard(SONIC_3K, "knuckles", "none");
        assertCharacterPairNonStandard(SONIC_3K, "tails", "knuckles");
    }

    @Test
    void displayValuesAreStableForUiRows() {
        LaunchProfile profile = new LaunchProfile(true, "s3k", true, "WIDE_16_9", "knuckles", "none");
        assertEquals("On", profile.displayValue(REWIND, SONIC_1));
        assertEquals("Sonic 3K", profile.displayValue(CROSS_GAME, SONIC_1));
        assertEquals("On", profile.displayValue(DEBUG_TOOLS, SONIC_1));
        assertEquals("16:9", profile.displayValue(WIDESCREEN, SONIC_1));
        assertEquals("Knuckles", profile.displayValue(MAIN_CHARACTER, SONIC_1));
        assertEquals("None", profile.displayValue(SIDEKICK, SONIC_1));
    }

    @Test
    void invalidAspectNormalizesToGlobalLaunchDefault() {
        LaunchProfile profile = new LaunchProfile(false, "off", false, "CINEMASCOPE", "sonic", "none");

        assertEquals("global", profile.aspect());
        assertTrue(profile.isStock(WIDESCREEN, SONIC_1));
        assertFalse(profile.isNonStandard(WIDESCREEN, SONIC_1));
    }

    private static void assertStock(MasterTitleScreen.GameEntry entry, LaunchProfile expected) {
        LaunchProfile actual = LaunchProfile.stockFor(entry);
        assertEquals(expected, actual);
        assertEquals(0, actual.enabledCount(entry));
    }

    private static void assertCharacterPairStandard(MasterTitleScreen.GameEntry entry, String main, String sidekick) {
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", main, sidekick);
        assertTrue(profile.isCharacterPairStandard(entry));
        assertFalse(profile.isNonStandard(MAIN_CHARACTER, entry));
        assertFalse(profile.isNonStandard(SIDEKICK, entry));
    }

    private static void assertCharacterPairNonStandard(MasterTitleScreen.GameEntry entry, String main, String sidekick) {
        LaunchProfile profile = new LaunchProfile(false, "off", false, "global", main, sidekick);
        assertFalse(profile.isCharacterPairStandard(entry));
        assertTrue(profile.isNonStandard(MAIN_CHARACTER, entry));
        assertTrue(profile.isNonStandard(SIDEKICK, entry));
    }
}
