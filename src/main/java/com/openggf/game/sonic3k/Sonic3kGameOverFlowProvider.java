package com.openggf.game.sonic3k;

import com.openggf.game.GameOverFlowProvider;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.S3kGameOverCardObjectInstance;
import com.openggf.level.objects.AbstractGameOverCardObjectInstance;
import com.openggf.level.objects.ObjectServices;

/**
 * Sonic 3&amp;K {@code loc_12432} / {@code loc_12498} (docs/skdisasm/sonic3k.asm:24588-24616):
 * <pre>
 *   move.l  #Obj_GameOver,(Reserved_object_3).w     ; frame 0 (GAME) / 2 (TIME)
 *   move.l  #Obj_GameOver,(Dynamic_object_RAM).w    ; frame 1 / 3 (OVER)
 *   ...
 *   move.w  #mus_GameOver,d0 ; Play_Music
 *   moveq   #3,d0            ; Load_PLC_2
 * </pre>
 * {@code Load_PLC_2 #3} decompresses ArtNem_GameOver over the shield tiles; the
 * engine's sheet is resident from level load, so only the music and the objects
 * are issued here.
 */
public final class Sonic3kGameOverFlowProvider implements GameOverFlowProvider {

    @Override
    public void beginGameOverCard(ObjectServices services, boolean timeOver) {
        AbstractGameOverCardObjectInstance.spawnPair(
                services,
                timeOver,
                S3kGameOverCardObjectInstance::new,
                Sonic3kConstants.SST_SLOT_GAME_OVER_WORD,
                Sonic3kConstants.SST_SLOT_GAME_OVER_OVER);
        services.playMusic(Sonic3kMusic.GAME_OVER.id);
    }
}
