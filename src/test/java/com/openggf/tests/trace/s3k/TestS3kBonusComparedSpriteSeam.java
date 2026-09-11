package com.openggf.tests.trace.s3k;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageType;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level (no ROM, no engine boot) coverage for
 * {@link AbstractS3kBonusStageTraceReplayTest#selectComparedSprite}, the pure
 * selection rule behind the {@code comparedSprite()} seam: prefer the
 * camera-focused sprite when present (the slot runtime swaps it onto the
 * dedicated slot-machine playable), else fall back to the fixture's primary
 * sprite (gumball/pachinko, which never move camera focus).
 */
class TestS3kBonusComparedSpriteSeam {

    @BeforeEach
    void setUp() {
        // Only needed to make AbstractPlayableSprite construction resolve a
        // physics profile; selectComparedSprite itself touches no services.
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_3K);
    }

    @Test
    void prefersFocusedSpriteWhenPresent() {
        AbstractPlayableSprite focused = new Tails("tails", (short) 0x460, (short) 0x430);
        AbstractPlayableSprite fixtureSprite = new Sonic("sonic", (short) 0x420, (short) 0x430);

        AbstractPlayableSprite result =
                AbstractS3kBonusStageTraceReplayTest.selectComparedSprite(focused, fixtureSprite);

        assertSame(focused, result);
    }

    @Test
    void fallsBackToFixtureSpriteWhenFocusedIsNull() {
        AbstractPlayableSprite fixtureSprite = new Sonic("sonic", (short) 0x420, (short) 0x430);

        AbstractPlayableSprite result =
                AbstractS3kBonusStageTraceReplayTest.selectComparedSprite(null, fixtureSprite);

        assertSame(fixtureSprite, result);
    }

    @Test
    void terminalBoundaryComesFromLiveBonusProviderCompletion() {
        AbstractBonusStageCoordinator provider = new AbstractBonusStageCoordinator() {
            @Override
            public BonusStageType selectBonusStage(int ringCount) {
                return BonusStageType.GUMBALL;
            }

            @Override
            public int getZoneId(BonusStageType type) {
                return 0;
            }

            @Override
            public int getMusicId(BonusStageType type) {
                return 0;
            }
        };
        provider.requestExit();

        assertTrue(AbstractS3kBonusStageTraceReplayTest.hasReachedTerminalBoundary(provider));
    }
}
