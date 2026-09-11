package com.openggf.game;

import com.openggf.audio.GameAudioProfile;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic1.Sonic1GameModule;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.session.SessionManager;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.PlaneSwitcherConfig;
import com.openggf.level.objects.TouchResponseTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests sidekick capability gating per game module.
 * S1: no sidekick support (no Tails art/logic).
 * S2/S3K: sidekick support (Tails available).
 */
class TestSidekickGating {

    @BeforeEach
    void setUp() {
        SessionManager.clear();
        CrossGameFeatureProvider.getInstance().resetState();
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
    }

    @AfterEach
    void tearDown() {
        CrossGameFeatureProvider.getInstance().resetState();
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    static Stream<Arguments> sidekickProvider() {
        return Stream.of(
                Arguments.of(new Sonic1GameModule(), false, "S1"),
                Arguments.of(new Sonic2GameModule(), true, "S2"),
                Arguments.of(new Sonic3kGameModule(), true, "S3K")
        );
    }

    @ParameterizedTest(name = "{2} supportsSidekick = {1}")
    @MethodSource("sidekickProvider")
    void sidekickSupportMatchesModule(GameModule module, boolean expected, String label) {
        assertEquals(expected, module.supportsSidekick(),
                label + " sidekick support");
    }

    /**
     * Verifies the combined gating logic used by Engine.initializeGame():
     * sidekickAllowed = module.supportsSidekick() || CrossGameFeatureProvider.isActive()
     *
     * When S1 is the active module and cross-game features are NOT active,
     * sidekicks must be blocked.
     */
    @Test
    void s1WithoutCrossGameFeatures_blocksSidekick() {
        GameModuleRegistry.setCurrent(new Sonic1GameModule());
        // CrossGameFeatureProvider is not initialized, so isActive() returns false
        CrossGameFeatureProvider.getInstance().resetState();

        boolean sidekickAllowed = GameModuleRegistry.getCurrent().supportsSidekick()
                || CrossGameFeatureProvider.isActive();
        assertFalse(sidekickAllowed,
                "S1 without cross-game features must block sidekick spawning");
    }

    @Test
    void s2NativelySupportsSidekick_regardlessOfCrossGame() {
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        CrossGameFeatureProvider.getInstance().resetState();

        boolean sidekickAllowed = GameModuleRegistry.getCurrent().supportsSidekick()
                || CrossGameFeatureProvider.isActive();
        assertTrue(sidekickAllowed,
                "S2 natively supports sidekick even without cross-game features");
    }

    @Test
    void defaultMethod_returnsFalse() {
        GameModule anonymousModule = new GameModule() {
            public String getIdentifier() { return "test"; }
            public Game createGame(Rom rom) { return null; }
            public ObjectRegistry createObjectRegistry() { return null; }
            public GameAudioProfile getAudioProfile() { return null; }
            public TouchResponseTable createTouchResponseTable(
                    RomByteReader r) { return null; }
            public int getPlaneSwitcherObjectId() { return 0; }
            public PlaneSwitcherConfig getPlaneSwitcherConfig() { return null; }
            public LevelEventProvider getLevelEventProvider() { return null; }
            public RespawnState createRespawnState() { return null; }
            public LevelState createLevelState() { return null; }
            public ZoneRegistry getZoneRegistry() { return null; }
            public ScrollHandlerProvider getScrollHandlerProvider() { return null; }
            public ZoneFeatureProvider getZoneFeatureProvider() { return null; }
            public RomOffsetProvider getRomOffsetProvider() { return null; }
            public DebugModeProvider getDebugModeProvider() { return null; }
            public DebugOverlayProvider getDebugOverlayProvider() { return null; }
            public ZoneArtProvider getZoneArtProvider() { return null; }
            public ObjectArtProvider getObjectArtProvider() { return null; }
            public com.openggf.game.PhysicsProvider getPhysicsProvider() { return null; }
            public GameId getGameId() { return null; }
        };
        assertFalse(anonymousModule.supportsSidekick(),
                "Default supportsSidekick() should be false");
    }
}


