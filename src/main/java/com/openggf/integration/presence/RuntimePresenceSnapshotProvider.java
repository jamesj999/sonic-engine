package com.openggf.integration.presence;

import com.openggf.GameLoop;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameId;
import com.openggf.game.GameModule;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.LevelState;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.level.LevelManager;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class RuntimePresenceSnapshotProvider implements PresenceSnapshotProvider {
    private final GameLoop gameLoop;
    private final SonicConfigurationService configService;

    public RuntimePresenceSnapshotProvider(GameLoop gameLoop,
                                           SonicConfigurationService configService) {
        this.gameLoop = Objects.requireNonNull(gameLoop, "gameLoop");
        this.configService = Objects.requireNonNull(configService, "configService");
    }

    @Override
    public PresenceSnapshot capture() {
        GameMode mode = gameLoop.getCurrentGameMode();
        if (!isGameplayMode(mode)) {
            return PresenceSnapshot.menu(menuName(mode), menuGameName(mode));
        }

        WorldSession world = SessionManager.getCurrentWorldSession();
        if (world == null || world.getGameModule() == null) {
            return PresenceSnapshot.menu();
        }

        String gameName = displayGameName(world.getGameModule().getGameId());
        String zoneName = world.getGameModule().getZoneRegistry()
                .getZoneName(world.getCurrentZone());
        int actNumber = world.getApparentAct() + 1;
        String teamName = displayTeamName();
        LevelState levelState = resolveLevelState();
        String displayTime = levelState != null ? levelState.getDisplayTime() : null;
        long timerFrames = levelState != null ? levelState.getTimerFrames() : 0L;

        return PresenceSnapshot.gameplay(gameName, zoneName, actNumber, teamName,
                displayTime, timerFrames);
    }

    private static boolean isGameplayMode(GameMode mode) {
        return mode == GameMode.LEVEL
                || mode == GameMode.TITLE_CARD
                || mode == GameMode.SPECIAL_STAGE
                || mode == GameMode.SPECIAL_STAGE_RESULTS
                || mode == GameMode.BONUS_STAGE
                || mode == GameMode.CREDITS_DEMO;
    }

    private static String menuName(GameMode mode) {
        return switch (mode) {
            case MASTER_TITLE_SCREEN -> "Master Title";
            case TITLE_SCREEN -> "Title Screen";
            case LEVEL_SELECT -> "Level Select";
            case DATA_SELECT -> "Data Select";
            case LEGAL_DISCLAIMER -> "Legal Disclaimer";
            case EDITOR -> "Level Editor";
            case CREDITS_TEXT -> "Credits";
            case TRY_AGAIN_END -> "Try Again";
            case ENDING_CUTSCENE -> "Ending";
            default -> null;
        };
    }

    private static String menuGameName(GameMode mode) {
        if (mode == GameMode.MASTER_TITLE_SCREEN || mode == GameMode.LEGAL_DISCLAIMER) {
            return null;
        }
        GameModule module = GameServices.currentOrBootstrapGameModule();
        return module != null ? displayGameName(module.getGameId()) : null;
    }

    private static String displayGameName(GameId gameId) {
        return switch (gameId) {
            case S1 -> "Sonic the Hedgehog";
            case S2 -> "Sonic 2";
            case S3K -> "Sonic 3 & Knuckles";
        };
    }

    private String displayTeamName() {
        String main = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        List<String> sidekicks = ActiveGameplayTeamResolver.resolveSidekicks(configService);
        if (sidekicks.isEmpty()) {
            return displayCharacterName(main);
        }
        StringBuilder builder = new StringBuilder(displayCharacterName(main));
        for (String sidekick : sidekicks) {
            builder.append(" & ").append(displayCharacterName(sidekick));
        }
        return builder.toString();
    }

    private static String displayCharacterName(String code) {
        if (code == null || code.isBlank()) {
            return "Sonic";
        }
        String lower = code.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "tails" -> "Tails";
            case "knuckles" -> "Knuckles";
            default -> "Sonic";
        };
    }

    private static LevelState resolveLevelState() {
        LevelManager level = GameServices.levelOrNull();
        return level != null ? level.getLevelGamestate() : null;
    }
}
