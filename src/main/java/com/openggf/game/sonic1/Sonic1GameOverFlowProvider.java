package com.openggf.game.sonic1;

import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.game.sonic1.objects.Sonic1GameOverCardObjectInstance;
import com.openggf.game.sonic1.resources.Sonic1PlcService;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;
import com.openggf.level.objects.ObjectServices;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Sonic 1 {@code Sonic_HandleDeath} game-over / time-over branch
 * (docs/s1disasm/_incObj/01 Sonic.asm:2019-2049):
 * <pre>
 *   move.b  #id_GameOverCard,(v_gameovertext1).w   ; GAME (or TIME, frame 2)
 *   move.b  #id_GameOverCard,(v_gameovertext2).w   ; OVER (frame 1, or 3)
 *   ...
 *   move.w  #bgm_GameOver,d0 ; QueueSound1
 *   moveq   #plcid_GameOver,d0 ; AddPLC
 * </pre>
 */
public final class Sonic1GameOverFlowProvider implements GameOverFlowProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic1GameOverFlowProvider.class.getName());

    @Override
    public void beginGameOverCard(ObjectServices services, boolean timeOver) {
        AbstractGameOverCardObjectInstance.spawnPair(
                services,
                timeOver,
                Sonic1GameOverCardObjectInstance::new,
                Sonic1Constants.SST_SLOT_GAME_OVER_WORD,
                Sonic1Constants.SST_SLOT_GAME_OVER_OVER);
        services.playMusic(Sonic1Music.GAME_OVER.id);
        Sonic1PlcService plc = services.gameService(Sonic1PlcService.class);
        if (plc != null) {
            try {
                plc.append(Sonic1Constants.PLC_GAME_OVER);
            } catch (IOException | IllegalStateException e) {
                LOGGER.warning("Unable to queue plcid_GameOver: " + e.getMessage());
            }
        }
    }
}
