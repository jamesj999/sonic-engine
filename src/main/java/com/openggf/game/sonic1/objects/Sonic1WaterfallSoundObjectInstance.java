package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Sonic 1 Object 0x49 - Waterfall Sound.
 * <p>
 * Invisible object placed near waterfalls in GHZ. Plays the waterfall
 * sound effect (sfx_Waterfall, 0xD0) every 64 frames (~1.07 seconds).
 * <p>
 * From disassembly (_incObj/49 Waterfall Sound.asm):
 * <pre>
 *   WSnd_PlaySnd:
 *       move.b  (v_vbla_byte).w,d0
 *       andi.b  #$3F,d0
 *       bne.s   WSnd_ChkDel
 *       move.w  #sfx_Waterfall,d0
 *       jsr     (PlaySound_Special).l
 * </pre>
 */
public class Sonic1WaterfallSoundObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // From disassembly: andi.b #$3F,d0 — play every 64 frames
    private static final int PLAY_INTERVAL_MASK = 0x3F;

    public Sonic1WaterfallSoundObjectInstance(ObjectSpawn spawn) {
        super(spawn, "WaterfallSound");
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // WSnd_PlaySnd: play waterfall SFX every 64 frames
        if ((vIntRunCount & PLAY_INTERVAL_MASK) == 0) {
            services().playSfx(Sonic1Sfx.WATERFALL.id);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object — no rendering
    }
}
