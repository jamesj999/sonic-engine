package com.openggf.game.sonic2;

import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.objects.Sonic2GameOverCardObjectInstance;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;
import com.openggf.level.objects.ObjectServices;

import java.io.IOException;
import java.util.logging.Logger;

/**
 * Sonic 2 {@code CheckGameOver} game-over branch and {@code Obj01_ResetLevel}
 * time-over branch (docs/s2disasm/s2.asm:38284-38316):
 * <pre>
 *   move.b  #ObjID_GameOver,(GameOver_GameText+id).w ; frame 0 / 2 (TIME)
 *   move.b  #ObjID_GameOver,(GameOver_OverText+id).w ; frame 1 / 3
 *   ...
 *   move.w  #MusID_GameOver,d0 ; PlayMusic
 *   moveq   #PLCID_GameOver,d0 ; LoadPLC
 * </pre>
 * {@code Obj02_CheckGameOver} reaches the same code for Tails alone
 * (docs/s2disasm/s2.asm:41141-41143).
 */
public final class Sonic2GameOverFlowProvider implements GameOverFlowProvider {
    private static final Logger LOGGER = Logger.getLogger(Sonic2GameOverFlowProvider.class.getName());

    @Override
    public void beginGameOverCard(ObjectServices services, boolean timeOver) {
        AbstractGameOverCardObjectInstance.spawnPair(
                services,
                timeOver,
                Sonic2GameOverCardObjectInstance::new,
                Sonic2Constants.SST_SLOT_GAME_OVER_WORD,
                Sonic2Constants.SST_SLOT_GAME_OVER_OVER);
        services.playMusic(Sonic2Music.GAME_OVER.id);
        Sonic2PlcService plc = services.gameService(Sonic2PlcService.class);
        if (plc != null) {
            try {
                plc.transact(Sonic2PlcService.appendOperation(Sonic2Constants.PLC_GAME_OVER));
            } catch (IOException | IllegalStateException e) {
                LOGGER.warning("Unable to queue PLCID_GameOver: " + e.getMessage());
            }
        }
    }
}
