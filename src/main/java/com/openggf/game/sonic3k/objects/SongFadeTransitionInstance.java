package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Lightweight persistent object that fades out the current music, waits a
 * specified number of frames, then plays a new track and destroys itself.
 *
 * ROM equivalent: Obj_Song_Fade_Transition / Obj_Song_Fade_ToLevelMusic
 * (sonic3k.asm line 180305). The ROM spawns this as an independent object so
 * that the music transition survives the destruction of the cutscene object
 * that initiated it.
 */
public class SongFadeTransitionInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // Non-final: delayFrames/musicId are not derivable from the dummy
    // ObjectSpawn (the ctor passes ObjectSpawn(0,0,0,0,0,false,0) to super).
    // Generic rewind recreate constructs with placeholder (0, 0), then the
    // GenericFieldCapturer reapplies these captured values after recreate.

    /** Number of frames to wait after fade-out starts before playing new music. */
    private int delayFrames;

    /** Music ID to play when the delay expires. */
    private int musicId;

    /** Frame counter since creation. */
    private int timer;

    /** Whether the initial fade-out has been issued. */
    private boolean fadeStarted;

    /** Whether the ROM delay countdown starts on the update after fade initialization. */
    private boolean deferCountdownOnFadeStart;

    /** Whether initialization must wait until the object pass after allocation. */
    private boolean deferSameFrameUpdateAfterSpawn;

    /**
     * @param delayFrames frames to wait after fade-out before playing new music
     * @param musicId     music ID to play when the delay expires
     */
    public SongFadeTransitionInstance(int delayFrames, int musicId) {
        this(delayFrames, musicId, false);
    }

    SongFadeTransitionInstance(int delayFrames, int musicId, boolean deferCountdownOnFadeStart) {
        this(delayFrames, musicId, deferCountdownOnFadeStart, false);
    }

    public SongFadeTransitionInstance(int delayFrames, int musicId,
                                      boolean deferCountdownOnFadeStart,
                                      boolean deferSameFrameUpdateAfterSpawn) {
        super(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), "SongFadeTransition");
        this.delayFrames = delayFrames;
        this.musicId = musicId;
        this.timer = 0;
        this.fadeStarted = false;
        this.deferCountdownOnFadeStart = deferCountdownOnFadeStart;
        this.deferSameFrameUpdateAfterSpawn = deferSameFrameUpdateAfterSpawn;
    }

    SongFadeTransitionInstance(ObjectSpawn spawn) {
        this(0, 0);
    }

    int getMusicIdForTest() {
        return musicId;
    }

    @Override
    public int getX() {
        return 0;
    }

    @Override
    public int getY() {
        return 0;
    }

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    protected boolean skipsSameFrameUpdateAfterSpawn() {
        return deferSameFrameUpdateAfterSpawn;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!fadeStarted) {
            // The S3K profile owns 28h/6 (Sound/Z80 Sound Driver.asm:2306-2311);
            // this is cmd_FadeOut, not a fade with parameters of its own.
            services().audioManager().fadeOutMusic();
            fadeStarted = true;
            if (deferCountdownOnFadeStart) {
                return;
            }
        }
        timer++;
        if (timer >= delayFrames) {
            services().playMusic(musicId);
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object — no rendering
    }
}
