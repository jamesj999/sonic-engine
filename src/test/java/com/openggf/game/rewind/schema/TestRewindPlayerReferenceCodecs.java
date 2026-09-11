package com.openggf.game.rewind.schema;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.identity.PlayerRefId;
import com.openggf.game.rewind.identity.RewindIdentityTable;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindPlayerReferenceCodecs {
    @BeforeEach
    void setUpRuntime() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
    }

    @AfterEach
    void clearRegistry() {
        RewindSchemaRegistry.clearForTest();
        SessionManager.clear();
    }

    @Test
    void restoresDirectPlayerReferencesThroughIdentityTable() {
        TestablePlayableSprite oldMain = player("old-main");
        TestablePlayableSprite oldSidekick = player("old-sidekick");
        PlayerReferenceFixture fixture = new PlayerReferenceFixture(oldMain, oldSidekick);

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture, context(oldMain, oldSidekick));

        TestablePlayableSprite newMain = player("new-main");
        TestablePlayableSprite newSidekick = player("new-sidekick");
        fixture.playable = null;
        fixture.sprite = null;
        CompactFieldCapturer.restore(fixture, blob, context(newMain, newSidekick));

        assertSame(newMain, fixture.playable);
        assertSame(newSidekick, fixture.sprite);
    }

    @Test
    void restoresPlayerReferenceCollectionsAndMapsThroughIdentityTable() {
        TestablePlayableSprite oldMain = player("old-main");
        TestablePlayableSprite oldSidekick = player("old-sidekick");
        PlayerCollectionFixture fixture = new PlayerCollectionFixture(oldMain, oldSidekick);

        RewindObjectStateBlob blob = CompactFieldCapturer.capture(fixture, context(oldMain, oldSidekick));

        TestablePlayableSprite newMain = player("new-main");
        TestablePlayableSprite newSidekick = player("new-sidekick");
        fixture.players.clear();
        fixture.states.clear();
        CompactFieldCapturer.restore(fixture, blob, context(newMain, newSidekick));

        assertEquals(List.of(newMain, newSidekick), new ArrayList<>(fixture.players));
        assertEquals(List.of(newMain, newSidekick), new ArrayList<>(fixture.states.keySet()));
        assertEquals(7, fixture.states.get(newMain));
        assertEquals(9, fixture.states.get(newSidekick));
    }

    @Test
    void playerReferenceCaptureRequiresIdentityContext() {
        PlayerReferenceFixture fixture = new PlayerReferenceFixture(player("main"), null);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> CompactFieldCapturer.capture(fixture));

        assertTrue(failure.getMessage().contains("RewindIdentityTable"));
    }

    private static RewindCaptureContext context(AbstractPlayableSprite main, AbstractPlayableSprite sidekick) {
        RewindIdentityTable table = new RewindIdentityTable();
        table.registerPlayer(main, PlayerRefId.mainPlayer());
        if (sidekick != null) {
            table.registerPlayer(sidekick, PlayerRefId.sidekick(0));
        }
        return RewindCaptureContext.withIdentityTable(table);
    }

    private static TestablePlayableSprite player(String code) {
        return new TestablePlayableSprite(code, (short) 0, (short) 0);
    }

    private static final class PlayerReferenceFixture {
        PlayableEntity playable;
        AbstractPlayableSprite sprite;

        private PlayerReferenceFixture(PlayableEntity playable, AbstractPlayableSprite sprite) {
            this.playable = playable;
            this.sprite = sprite;
        }
    }

    private static final class PlayerCollectionFixture {
        Set<AbstractPlayableSprite> players = new LinkedHashSet<>();
        Map<AbstractPlayableSprite, Integer> states = new LinkedHashMap<>();

        private PlayerCollectionFixture(AbstractPlayableSprite main, AbstractPlayableSprite sidekick) {
            players.add(main);
            players.add(sidekick);
            states.put(main, 7);
            states.put(sidekick, 9);
        }
    }

}
