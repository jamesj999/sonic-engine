package com.openggf.audio;

import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.File;

/** Sonic 1's extra-life restore on the live path; see {@link OneUpRestoreLivePathSupport}. */
@RequiresRom(SonicGame.SONIC_1)
class TestS1OneUpRestoreRom extends OneUpRestoreLivePathSupport {
    @Override
    protected File romFile() {
        return RomTestUtils.ensureSonic1RomAvailable();
    }

    @Override
    protected GameAudioProfile profile() {
        return new Sonic1AudioProfile();
    }

    @Override
    protected int levelMusicId() {
        return Sonic1Music.GHZ.id;
    }

    @Override
    protected int extraLifeMusicId() {
        return Sonic1Music.EXTRA_LIFE.id;
    }
}
