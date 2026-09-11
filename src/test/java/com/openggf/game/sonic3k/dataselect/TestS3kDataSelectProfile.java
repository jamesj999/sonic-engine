package com.openggf.game.sonic3k.dataselect;

import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import com.openggf.game.DataSelectProvider;
import com.openggf.game.session.EngineContext;
import com.openggf.game.NoOpDataSelectProvider;
import com.openggf.game.dataselect.DataSelectDestination;
import com.openggf.game.dataselect.DataSelectExitTransition;
import com.openggf.game.dataselect.DataSelectHostProfile;
import com.openggf.game.save.SelectedTeam;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TestS3kDataSelectProfile {

    @BeforeAll
    static void configureEngineServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void clearSession() {
        SessionManager.clear();
    }

    @Test
    void s3kGameModule_exposesDataSelectProvider() {
        Sonic3kGameModule module = new Sonic3kGameModule();
        DataSelectProvider provider = module.getDataSelectPresentationProvider();
        assertNotNull(provider);
        assertNotSame(NoOpDataSelectProvider.INSTANCE, provider,
                "S3K should provide a real DataSelectProvider, not the no-op stub");
        assertTrue(provider.getClass().getSimpleName().contains("DataSelect"));
    }

    @Test
    void s3kGameModule_exposesHostProfileSeparatelyFromPresentationProvider() {
        Sonic3kGameModule module = new Sonic3kGameModule();

        DataSelectHostProfile hostProfile = module.getDataSelectHostProfile();

        assertNotNull(hostProfile);
        assertEquals("s3k", hostProfile.gameCode());
        assertNotNull(module.getDataSelectPresentationProvider());
    }

    @Test
    void gameCode_returnsS3k() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        assertEquals("s3k", profile.gameCode());
    }

    @Test
    void exitTransition_usesNativeS3kEntryCueAndFadeCadence() {
        assertEquals(new DataSelectExitTransition(Sonic3kSfx.ENTER_SS.id, 0x28, 6, 1),
                new S3kDataSelectProfile().exitTransition());
    }

    @Test
    void slotCount_returnsEight() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        assertEquals(8, profile.slotCount());
    }

    @Test
    void builtInTeams_containsExpectedCharacters() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        List<SelectedTeam> teams = profile.builtInTeams();
        assertEquals(4, teams.size());
        assertEquals("sonic", teams.get(0).mainCharacter());
        assertEquals(List.of("tails"), teams.get(0).sidekicks());
        assertEquals("sonic", teams.get(1).mainCharacter());
        assertTrue(teams.get(1).sidekicks().isEmpty());
        assertEquals("tails", teams.get(2).mainCharacter());
        assertTrue(teams.get(2).sidekicks().isEmpty());
        assertEquals("knuckles", teams.get(3).mainCharacter());
        assertTrue(teams.get(3).sidekicks().isEmpty());
    }

    @Test
    void summarizeFreshSlot_returnsEmpty() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        var summary = profile.summarizeFreshSlot(3);
        assertEquals(3, summary.slot());
        assertTrue(summary.payload().isEmpty());
    }

    @Test
    void extraCombos_parseSemicolonSeparatedTeams() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        var teams = profile.parseExtraTeams("sonic,knuckles;knuckles,tails");
        assertEquals(2, teams.size());
        assertEquals("sonic", teams.get(0).mainCharacter());
        assertEquals(List.of("knuckles"), teams.get(0).sidekicks());
        assertEquals("knuckles", teams.get(1).mainCharacter());
        assertEquals(List.of("tails"), teams.get(1).sidekicks());
    }

    @Test
    void extraCombos_nullOrBlank_returnsEmpty() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        assertTrue(profile.parseExtraTeams(null).isEmpty());
        assertTrue(profile.parseExtraTeams("").isEmpty());
        assertTrue(profile.parseExtraTeams("   ").isEmpty());
    }

    @Test
    void extraCombos_singleCharacter_noSidekicks() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();
        var teams = profile.parseExtraTeams("tails");
        assertEquals(1, teams.size());
        assertEquals("tails", teams.get(0).mainCharacter());
        assertTrue(teams.get(0).sidekicks().isEmpty());
    }

    @Test
    void clearRestartDestinations_sonicWithoutAllEmeralds_stopsAtDez() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();

        Map<String, Object> payload = Map.of(
                "zone", Sonic3kZoneIds.ZONE_DEZ,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of("tails"),
                "chaosEmeralds", List.of(0, 2, 4),
                "clear", true,
                "progressCode", 13,
                "clearState", 1
        );
        List<DataSelectDestination> destinations = profile.clearRestartDestinations(payload);

        assertEquals(12, destinations.size());
        assertEquals(13, profile.clearRestartSelectionCount(payload));
        assertEquals(new DataSelectDestination(Sonic3kZoneIds.ZONE_AIZ, 0), destinations.getFirst());
        assertEquals(new DataSelectDestination(Sonic3kZoneIds.ZONE_SSZ, 0), destinations.getLast());
        assertFalse(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_DEZ, 0)));
        assertFalse(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_DDZ, 0)));
    }

    @Test
    void clearRestartDestinations_sonicWithAllEmeralds_includesDdz() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();

        Map<String, Object> payload = Map.of(
                "zone", Sonic3kZoneIds.ZONE_DDZ,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of(),
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 14,
                "clearState", 2
        );
        List<DataSelectDestination> destinations = profile.clearRestartDestinations(payload);

        assertEquals(13, destinations.size());
        assertEquals(14, profile.clearRestartSelectionCount(payload));
        assertEquals(new DataSelectDestination(Sonic3kZoneIds.ZONE_DEZ, 0), destinations.getLast());
        assertFalse(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_DDZ, 0)));
    }

    @Test
    void defaultClearRestartIndex_sonicClearStartsOnTerminalClearGraphic() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();

        Map<String, Object> payload = Map.of(
                "zone", Sonic3kZoneIds.ZONE_DDZ,
                "act", 0,
                "mainCharacter", "sonic",
                "sidekicks", List.of(),
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 14,
                "clearState", 2
        );

        assertEquals(profile.clearRestartSelectionCount(payload) - 1, profile.defaultClearRestartIndex(payload));
    }

    @Test
    void defaultClearRestartIndex_knucklesClearStartsOnTerminalClearGraphic() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();

        Map<String, Object> payload = Map.of(
                "zone", Sonic3kZoneIds.ZONE_SSZ,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of(),
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 12,
                "clearState", 2
        );

        assertEquals(profile.clearRestartSelectionCount(payload) - 1, profile.defaultClearRestartIndex(payload));
    }

    @Test
    void clearRestartDestinations_knucklesUsesRestrictedEndingPath() {
        S3kDataSelectProfile profile = new S3kDataSelectProfile();

        Map<String, Object> payload = Map.of(
                "zone", Sonic3kZoneIds.ZONE_SSZ,
                "act", 1,
                "mainCharacter", "knuckles",
                "sidekicks", List.of(),
                "chaosEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "superEmeralds", List.of(0, 1, 2, 3, 4, 5, 6),
                "clear", true,
                "progressCode", 12,
                "clearState", 2
        );
        List<DataSelectDestination> destinations = profile.clearRestartDestinations(payload);

        assertEquals(11, destinations.size());
        assertEquals(12, profile.clearRestartSelectionCount(payload));
        assertTrue(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_HPZ, 1)));
        assertEquals(new DataSelectDestination(Sonic3kZoneIds.ZONE_HPZ, 1), destinations.getLast());
        assertFalse(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_SSZ, 1)));
        assertFalse(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_DEZ, 0)));
        assertFalse(destinations.contains(new DataSelectDestination(Sonic3kZoneIds.ZONE_DDZ, 0)));
    }
}
