package com.openggf.game.startup;

import com.openggf.game.GameId;
import com.openggf.game.GameModule;
import com.openggf.game.TitleScreenProvider.TitleScreenAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestStartupRouteResolver {

    private final StartupRouteResolver resolver = new StartupRouteResolver();

    @Test
    void onePlayerRoutesToDataSelectOnlyWhenPresentationResolvesToS3k() {
        TitleActionRoute s3kRoute = resolver.resolveTitleAction(
                hostModule(GameId.S2),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                false,
                TitleScreenAction.ONE_PLAYER);

        TitleActionRoute nonS3kRoute = resolver.resolveTitleAction(
                hostModule(GameId.S2),
                new DataSelectPresentationResolution(true, GameId.S2),
                true,
                false,
                TitleScreenAction.ONE_PLAYER);

        assertEquals(TitleActionRoute.DATA_SELECT, s3kRoute);
        assertEquals(TitleActionRoute.LEVEL, nonS3kRoute);
    }

    @Test
    void s1OnePlayerRoutesToDataSelectWhenDonorResolvesToS3k() {
        TitleActionRoute route = resolver.resolveTitleAction(
                hostModule(GameId.S1),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                false,
                TitleScreenAction.ONE_PLAYER);

        assertEquals(TitleActionRoute.DATA_SELECT, route);
    }

    @Test
    void s2OnePlayerRoutesToDataSelectWhenDonorResolvesToS3k() {
        TitleActionRoute route = resolver.resolveTitleAction(
                hostModule(GameId.S2),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                false,
                TitleScreenAction.ONE_PLAYER);

        assertEquals(TitleActionRoute.DATA_SELECT, route);
    }

    @Test
    void levelSelectOverridesDataSelect() {
        TitleActionRoute route = resolver.resolveTitleAction(
                hostModule(GameId.S3K),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                true,
                TitleScreenAction.ONE_PLAYER);

        assertEquals(TitleActionRoute.LEVEL_SELECT, route);
    }

    @Test
    void twoPlayerAndOptionsDoNotRouteToDataSelect() {
        TitleActionRoute twoPlayerRoute = resolver.resolveTitleAction(
                hostModule(GameId.S3K),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                false,
                TitleScreenAction.TWO_PLAYER);
        TitleActionRoute optionsRoute = resolver.resolveTitleAction(
                hostModule(GameId.S3K),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                false,
                TitleScreenAction.OPTIONS);

        assertEquals(TitleActionRoute.TWO_PLAYER, twoPlayerRoute);
        assertEquals(TitleActionRoute.OPTIONS, optionsRoute);
    }

    @Test
    void otherIsTheSafeDefaultAndDoesNotRouteToDataSelect() {
        TitleActionRoute route = resolver.resolveTitleAction(
                hostModule(GameId.S3K),
                new DataSelectPresentationResolution(true, GameId.S3K),
                true,
                false,
                TitleScreenAction.OTHER);

        assertEquals(TitleActionRoute.OTHER, route);
    }

    @Test
    void donatedDataSelectDoesNotRouteWhenCrossGameFeaturesAreDisabled() {
        TitleActionRoute route = resolver.resolveTitleAction(
                hostModule(GameId.S1),
                new DataSelectPresentationResolution(false, GameId.S3K),
                true,
                false,
                TitleScreenAction.ONE_PLAYER);

        assertEquals(TitleActionRoute.LEVEL, route);
    }

    private static GameModule hostModule(GameId gameId) {
        GameModule module = mock(GameModule.class);
        when(module.getGameId()).thenReturn(gameId);
        return module;
    }
}
