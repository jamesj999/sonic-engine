package com.openggf.game.startup;

import com.openggf.game.GameModule;
import com.openggf.game.TitleScreenProvider.TitleScreenAction;

import java.util.Objects;

public final class StartupRouteResolver {

    public TitleActionRoute resolveTitleAction(
            GameModule hostModule,
            DataSelectPresentationResolution resolution,
            boolean titleScreenEnabled,
            boolean levelSelectEnabled,
            TitleScreenAction action) {
        Objects.requireNonNull(hostModule, "hostModule");
        Objects.requireNonNull(resolution, "resolution");
        Objects.requireNonNull(action, "action");

        if (!titleScreenEnabled) {
            return levelSelectEnabled ? TitleActionRoute.LEVEL_SELECT : TitleActionRoute.LEVEL;
        }
        if (action != TitleScreenAction.ONE_PLAYER) {
            return switch (action) {
                case TWO_PLAYER -> TitleActionRoute.TWO_PLAYER;
                case OPTIONS -> TitleActionRoute.OPTIONS;
                default -> TitleActionRoute.OTHER;
            };
        }
        if (levelSelectEnabled) {
            return TitleActionRoute.LEVEL_SELECT;
        }
        if (resolution.usesS3kPresentation()) {
            return TitleActionRoute.DATA_SELECT;
        }
        return TitleActionRoute.LEVEL;
    }
}
