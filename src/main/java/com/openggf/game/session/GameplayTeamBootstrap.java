package com.openggf.game.session;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameModule;
import com.openggf.game.SidekickSpawnOffset;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.Knuckles;
import com.openggf.sprites.playable.KnucklesRespawnStrategy;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.SonicRespawnStrategy;
import com.openggf.sprites.playable.Tails;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared gameplay-team bootstrap used by both runtime startup and headless tests.
 *
 * <p>This mirrors the engine's pre-level-load team construction: create the
 * active main character, register any configured sidekicks with chained CPU
 * controllers, then let the level-load path place them at the actual spawn.
 */
public final class GameplayTeamBootstrap {
    public static final short DEFAULT_MAIN_X = 100;
    public static final short DEFAULT_MAIN_Y = 624;

    private static final int SIDEKICK_SCREEN_SPACING = 0x20;
    private static final int SIDEKICK_Y_OFFSET = 4;

    private GameplayTeamBootstrap() {
    }

    public static BootstrappedTeam registerActiveTeam(GameModule module,
                                                      SpriteManager spriteManager,
                                                      SonicConfigurationService configService) {
        return registerActiveTeam(module, spriteManager, configService,
                DEFAULT_MAIN_X, DEFAULT_MAIN_Y);
    }

    public static BootstrappedTeam registerActiveTeam(GameModule module,
                                                      SpriteManager spriteManager,
                                                      SonicConfigurationService configService,
                                                      short mainX,
                                                      short mainY) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(spriteManager, "spriteManager");
        Objects.requireNonNull(configService, "configService");

        String mainCharacter = ActiveGameplayTeamResolver.resolveMainCharacterCode(configService);
        AbstractPlayableSprite mainSprite = createPlayable(mainCharacter, mainCharacter, mainX, mainY);
        spriteManager.addSprite(mainSprite);

        List<AbstractPlayableSprite> sidekicks = new ArrayList<>();
        if (module.supportsSidekick() || CrossGameFeatureProvider.isActive()) {
            AbstractPlayableSprite previousLeader = mainSprite;
            List<String> sidekickNames = ActiveGameplayTeamResolver.resolveSidekicks(configService);
            for (int i = 0; i < sidekickNames.size(); i++) {
                String characterName = sidekickNames.get(i);
                String code = characterName + "_p" + (i + 2);
                int desiredX = mainSprite.getX() - SIDEKICK_SCREEN_SPACING * (i + 1);
                int spawnX = Math.max(0, desiredX);
                boolean offScreen = desiredX < 0;

                AbstractPlayableSprite sidekick = createPlayable(
                        characterName,
                        code,
                        (short) spawnX,
                        (short) (mainSprite.getY() + SIDEKICK_Y_OFFSET));
                sidekick.setCpuControlled(true);

                SidekickCpuController controller = new SidekickCpuController(sidekick, previousLeader);
                controller.setSidekickCount(sidekickNames.size());
                if (offScreen) {
                    controller.setInitialState(SidekickCpuController.State.SPAWNING);
                }
                if ("knuckles".equalsIgnoreCase(characterName)) {
                    controller.setRespawnStrategy(new KnucklesRespawnStrategy(controller));
                } else if (!"tails".equalsIgnoreCase(characterName)) {
                    controller.setRespawnStrategy(new SonicRespawnStrategy(controller));
                }
                sidekick.setCpuController(controller);

                // Wire any game-specific sidekick carry trigger (S3K CNZ1 Tails carry).
                // S1/S2 modules return null from getSidekickCarryTrigger(), so this is
                // a no-op branch for those games and for S3K zones outside CNZ1.
                controller.setCarryTrigger(module.getSidekickCarryTrigger());

                spriteManager.addSprite(sidekick, characterName);
                sidekicks.add(sidekick);
                previousLeader = sidekick;
            }
        }

        return new BootstrappedTeam(mainSprite, List.copyOf(sidekicks));
    }

    public static void repositionRegisteredSidekicks(GameModule module, LevelManager levelManager) {
        Objects.requireNonNull(module, "module");
        Objects.requireNonNull(levelManager, "levelManager");

        SidekickSpawnOffset offset = module.getLevelInitProfile().sidekickSpawnOffset();
        levelManager.spawnSidekicks(offset.xOffset(), offset.yOffset());
    }

    private static AbstractPlayableSprite createPlayable(String characterName,
                                                         String code,
                                                         short x,
                                                         short y) {
        if ("tails".equalsIgnoreCase(characterName)) {
            return new Tails(code, x, y);
        }
        if ("knuckles".equalsIgnoreCase(characterName)) {
            return new Knuckles(code, x, y);
        }
        return new Sonic(code, x, y);
    }

    public record BootstrappedTeam(AbstractPlayableSprite mainSprite,
                                   List<AbstractPlayableSprite> sidekicks) {
        public BootstrappedTeam {
            sidekicks = List.copyOf(sidekicks);
        }
    }
}
