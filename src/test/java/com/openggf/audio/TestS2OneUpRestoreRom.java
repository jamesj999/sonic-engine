package com.openggf.audio;

import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.File;

/** Sonic 2's extra-life restore on the live path; see {@link OneUpRestoreLivePathSupport}. */
@RequiresRom(SonicGame.SONIC_2)
class TestS2OneUpRestoreRom extends OneUpRestoreLivePathSupport {
    @Override
    protected File romFile() {
        return RomTestUtils.ensureSonic2RomAvailable();
    }

    @Override
    protected GameAudioProfile profile() {
        return new Sonic2AudioProfile();
    }

    @Override
    protected int levelMusicId() {
        return Sonic2Music.EMERALD_HILL.id;
    }

    @Override
    protected int extraLifeMusicId() {
        return Sonic2Music.EXTRA_LIFE.id;
    }
}
