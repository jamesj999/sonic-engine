package com.openggf.game.sonic1.continuescreen;

import com.openggf.data.RomByteReader;
import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.GameServices;
import com.openggf.game.common.CommonSpriteDataLoader;
import com.openggf.game.continuescreen.ContinueScreenArtwork;
import com.openggf.game.sonic1.S1SpriteDataLoader;
import com.openggf.game.sonic1.Sonic1PlayerArt;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic1.audio.Sonic1SmpsConstants;
import com.openggf.game.sonic1.constants.Sonic1Constants;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.Sonic;
import com.openggf.util.PatternDecompressor;
import java.io.IOException;

/** GM_Continue / ContSonic / ContScrItem, shipped S1 REV01. */
public final class Sonic1ContinueScreenProvider implements ContinueScreenProvider {
    // Verified ROM pointers: GM_Continue $4D42/$4D56; CSI_Main $4E68; Ani_CSon $5054.
    static final int ART_CONTINUE = 0x3B39A, ART_MINI = 0x3B64A, MAP = 0x5062, PALETTE = 0x28E0;
    private ContinueScreenArtwork art;
    private ContinueScreenArtwork.Character sonic;
    private PatternSpriteRenderer icons;
    private ObjectAnimationState lying;
    private Pattern[] digits;
    private int timer, vint, x, y, inertia, count, iconFrame;
    private boolean accepted, finished, landed, usedIconDeleted;

    @Override public void initialize(int continues) { initialize(continues, 0); }

    @Override public void initialize(int continues, int vintRunCount) {
        reset();
        count = continues & 0xFF;
        timer = 659;
        vint = vintRunCount;
        x = 160;
        y = 192;
        iconFrame = 6 ^ ((vintRunCount & 15) == 0 ? 1 : 0); // Initial ExecuteObjects V-int gate.
        try {
            var rom = GameServices.rom().getRom();
            var reader = RomByteReader.fromRom(rom);
            art = new ContinueScreenArtwork(rom, S1SpriteDataLoader.loadMappingFrames(reader, MAP, 8), PALETTE, 2);
            art.loadNemesis(rom, Sonic1Constants.ART_NEM_TITLE_CARD_ADDR, 0x80);
            art.loadNemesis(rom, ART_CONTINUE, 0);
            art.loadNemesis(rom, ART_MINI, 0x51);
            digits = PatternDecompressor.fromBytes(rom.readBytes(Sonic1Constants.ART_UNC_HUD_NUMBERS_ADDR, 640));
            art.setCountdown(10, digits);
            art.cache();
            icons = art.withTileOffset(0x51, 0x200);
            lying = new ObjectAnimationState(CommonSpriteDataLoader.loadAnimationSet(reader, 0x5054, 1), 0, 1);
            sonic = new ContinueScreenArtwork.Character(new Sonic1PlayerArt(reader).loadSonic(),
                    new Sonic("sonic", (short) 0, (short) 0), 0x400, 0x1D);
            // ExecuteObjects is called once before fade-in; CSon_Main falls through to ShowFall.
            y += 4;
            sonic.update(vint, 0);
            GameServices.audio().playMusic(Sonic1Music.CONTINUE.id);
        } catch (IOException e) { throw new IllegalStateException("Cannot load S1 continue screen from ROM", e); }
    }

    @Override public void update(boolean startPressed, boolean start2Pressed) {
        if (finished || art == null) return;
        vint++;
        if (timer > 0) timer--;
        if (!accepted) art.setCountdown(timer / 60, digits);
        if (!landed) {
            if (y == 0x1A0) landed = true;
            else { y += 4; sonic.update(vint, 0); }
        }
        if (landed && !accepted) {
            if (startPressed) {
                accepted = true;
                y -= 8;
                sonic.setAnimation(0x1E);
                GameServices.audio().playMusic(Sonic1SmpsConstants.CMD_FADE_OUT);
            } else lying.update();
        }
        if (accepted) {
            if (inertia == 0x800) x += 16;
            else inertia += 0x20;
            sonic.update(vint, inertia);
            if (x >= 384) finished = true;
        } else if (timer == 0) finished = true;
        if ((vint & 15) == 0) iconFrame ^= 1;
        if (accepted && x != 160 && (vint & 1) == 0) usedIconDeleted = true;
    }

    @Override public void draw() {
        if (art == null) return;
        int offset = (Math.max(320, GameServices.graphics().getProjectionWidth()) - 320) / 2;
        art.draw(4, 160 + offset, 64);
        if (landed && !accepted) art.draw(lying.getMappingFrame(), x + offset, y - 256);
        else sonic.draw(x + offset, y - 256);
        art.draw(0, 160 + offset, 64);
        int n = Math.min(15, Math.max(0, count - 1));
        for (int i = 0; i < n; i++) {
            if (i == n - 1 && count < 16 && accepted && (usedIconDeleted || (vint & 1) == 0)) continue;
            int iconX = 160 + (i % 2 == 0 ? -1 : 1) * (10 + i / 2 * 20) - ((n & 1) == 0 ? 10 : 0);
            icons.drawFrameIndexWithPaletteBase(iconFrame, iconX + offset, 80, false, false, 0);
        }
    }
    @Override public void advanceFadeFrame() { vint++; }
    @Override public int currentVintRunCount() { return vint; }
    @Override public boolean isAccepted() { return accepted; }
    @Override public boolean isFinished() { return finished; }
    @Override public void reset() {
        art = null; sonic = null; icons = null; lying = null; digits = null;
        accepted = finished = landed = usedIconDeleted = false;
        timer = vint = x = y = inertia = count = 0;
    }
}
