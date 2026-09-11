package com.openggf.game;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.level.LevelManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Models the ROM's {@code SpawnLevelMainSprites_SpawnPlayers} for the special
 * stage return, where the engine fakes a level re-init it does not perform.
 *
 * <p>Both S2 ({@code InitPlayers}) and S3K do a full level re-init on a special
 * stage return, and the S3K routine positions the players in two branches that
 * each carry their own {@code +4}:
 * <ul>
 *   <li>{@code Player_mode == 0} (Sonic and Tails): the sidekick spawns at
 *       {@code Player_1 - $20, + 4} (docs/skdisasm/sonic3k.asm:8367).</li>
 *   <li>{@code Player_mode == 2} (Tails alone): Tails is Player_1 and
 *       <em>his own</em> {@code y_pos} is raised by 4
 *       (docs/skdisasm/sonic3k.asm:8388).</li>
 * </ul>
 *
 * <p>Both writes are unconditional -- the routine carries no {@code FixBugs}
 * conditional on either arm -- and Tails needs the offset in either role because
 * his {@code y_radius} is {@code $F} against Sonic's {@code $13}
 * (sonic3k.asm:26102, :21904), so an unadjusted centre leaves him 4px high.
 *
 * <p><b>Scope.</b> The ROM applies {@code :8388} on <em>every</em> level load for
 * Tails-as-Player_1, not only on this return. The engine only fakes the re-init
 * here, so this is the site modelled today; the general level-load case is a
 * separate known gap recorded in {@code docs/status/trace-frontier-log.md}.
 */
public final class SpecialStageReturnSpawn {

    private SpecialStageReturnSpawn() {
    }

    /** Applies the Player_mode == 2 arm: Tails as Player_1 spawns 4px lower. */
    public static void applyMainCharacterSpawnOffset(
            AbstractPlayableSprite playable, SonicConfigurationService configService) {
        if (ActiveGameplayTeamResolver.resolvePlayerCharacter(configService)
                == PlayerCharacter.TAILS_ALONE) {
            playable.setCentreY((short) (playable.getCentreY() + 4));
        }
    }

    /** Applies the Player_mode == 0 arm: the sidekick spawns at -$20, +4. */
    public static void respawnSidekicks(
            AbstractPlayableSprite playable,
            List<AbstractPlayableSprite> sidekicks,
            LevelManager levelManager) {
        for (AbstractPlayableSprite sidekick : sidekicks) {
            sidekick.setX((short) (playable.getX() - 0x20));
            sidekick.setY((short) (playable.getY() + 4));
            sidekick.setXSpeed((short) 0);
            sidekick.setYSpeed((short) 0);
            sidekick.setGSpeed((short) 0);
            sidekick.setAir(false);
            sidekick.setDead(false);
            sidekick.setDeathCountdown(0);
            sidekick.setHurt(false);
            sidekick.setHidden(false);
            sidekick.setObjectControlled(false);
            sidekick.setRolling(false);
            sidekick.setDirection(playable.getDirection());
            if (sidekick.getCpuController() != null) {
                sidekick.getCpuController().reset();
                // ROM LevelSizeLoad re-writes Tails_Min/Max_X_pos and
                // Tails_Min/Max_Y_pos from the LevelSize table on every entry to
                // the Level: routine, and the special-stage return re-runs that
                // routine in full (docs/s2disasm/s2.asm:14695-14706). reset()
                // clears the controller's copies of those words, so restore them
                // exactly as the level-load path does; without this
                // Tails_Max_Y_pos stays unset and Obj02_CheckGameOver's kill
                // plane (s2.asm:41146-41155) can never fire after a
                // special-stage return.
                levelManager.applySidekickLevelBounds(sidekick);
            }
        }
    }
}
