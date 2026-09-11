package com.openggf.game.sonic2.titlecard;

import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.CompositeSnapshot;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.game.sonic2.Sonic2ObjectArtProvider;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2TitleCardManagerRewind {

    @Test
    void gameplaySessionRegistersLiveTitleManager() {
        startLevel();

        var keys = TestEnvironment.activeGameplayMode()
                .getRewindRegistry().capture().entries().keySet();

        assertTrue(keys.contains(TitleCardManager.REWIND_KEY));
    }

    @Test
    void rewindBeforeOmittedTailPublicationReplaysItsExitPlcs() {
        startLevel();
        Sonic2TitleCardManagerFixture fixture = new Sonic2TitleCardManagerFixture();

        fixture.title().beginOmittedPresentationExitTail(0, 0);
        CompositeSnapshot beforePublication = fixture.registry().capture();
        int epochBefore = fixture.provider().capture().loadEpoch();

        runUntilComplete(fixture.title());
        assertEquals(epochBefore + 1, fixture.provider().capture().loadEpoch());

        fixture.registry().restore(beforePublication);
        assertFalse(fixture.title().isComplete(),
                "restoring before the tail boundary must restore the live title owner");
        assertEquals(epochBefore, fixture.provider().capture().loadEpoch());

        runUntilComplete(fixture.title());
        assertEquals(epochBefore + 1, fixture.provider().capture().loadEpoch(),
                "replaying the restored title owner must publish its PLCs again");
    }

    private static void runUntilComplete(TitleCardManager title) {
        for (int frame = 0; frame < 100 && !title.isComplete(); frame++) {
            title.update();
        }
        assertTrue(title.isComplete(), "title-card omitted tail did not complete");
    }

    private static void startLevel() {
        TestEnvironment.resetAll();
        SessionManager.clear();
        EngineServices.configure(
                EngineContext.fromLegacySingletonsForBootstrap());
        GameModuleRegistry.setCurrent(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
    }

    private static final class Sonic2TitleCardManagerFixture {
        private final TitleCardManager title =
                (TitleCardManager) GameServices.module().getTitleCardProvider();
        private final Sonic2ObjectArtProvider provider =
                (Sonic2ObjectArtProvider) GameServices.module().getObjectArtProvider();
        private final RewindRegistry registry =
                TestEnvironment.activeGameplayMode().getRewindRegistry();

        TitleCardManager title() {
            return title;
        }

        Sonic2ObjectArtProvider provider() {
            return provider;
        }

        RewindRegistry registry() {
            return registry;
        }
    }
}
