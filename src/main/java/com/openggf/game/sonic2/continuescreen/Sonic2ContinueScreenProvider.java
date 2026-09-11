package com.openggf.game.sonic2.continuescreen;

import com.openggf.data.RomByteReader;
import com.openggf.game.ContinueScreenProvider;
import com.openggf.game.GameServices;
import com.openggf.game.common.CommonSpriteDataLoader;
import com.openggf.game.continuescreen.ContinueScreenArtwork;
import com.openggf.game.sonic2.S2SpriteDataLoader;
import com.openggf.game.sonic2.Sonic2PlayerArt;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic2.audio.Sonic2Sfx;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.level.Pattern;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;
import com.openggf.util.PatternDecompressor;
import java.io.IOException;

/** ContinueScreen and ObjDA/ObjDB, shipped Sonic 2 REV01. */
public final class Sonic2ContinueScreenProvider implements ContinueScreenProvider {
    // ContinueScreen LEAs $78B6/$78CA/$78D8; ObjDA_Init mapping pointer $7A82.
    static final int ART_TAILS = 0x7BDBE, ART_MINI_SONIC = 0x7C0AA, ART_MINI_TAILS = 0x7C2F2;
    static final int MAP = 0x7CB6, PALETTE = 0x31C2, LETTERS = 0x7A5E;
    private ContinueScreenArtwork art;
    private ContinueScreenArtwork.Character sonic, tails;
    private PatternSpriteRenderer icons;
    private ObjectAnimationState nagging;
    private Pattern[] digits;
    private int timer, vint, sonicX, tailsX, sonicInertia, tailsInertia, count, iconFrame;
    private boolean accepted, finished, usedIconDeleted;
    private final Boolean tailsOnly;

    public Sonic2ContinueScreenProvider() { tailsOnly = null; }
    public Sonic2ContinueScreenProvider(boolean tailsOnly) { this.tailsOnly = tailsOnly; }
    @Override public void initialize(int continues) { initialize(continues, 0); }

    @Override public void initialize(int continues, int vintRunCount) {
        var sprites = GameServices.spritesOrNull();
        var main = sprites != null ? sprites.getMainPlayable() : null;
        boolean retainedSuperFlag = main != null && main.isSuperSonic();
        boolean miniTails = tailsOnly != null ? tailsOnly : "tails".equalsIgnoreCase(
                GameServices.configuration().getString(com.openggf.configuration.SonicConfiguration.MAIN_CHARACTER_CODE));
        reset();
        // One Vint_Menu between RunObjects and Pal_FadeFromBlack decrements the initial 659.
        timer = 658;
        count = continues & 0xFF;
        vint = vintRunCount + 1; // Initial Vint_Menu before the palette fade.
        sonicX = 0x9C;
        tailsX = 0xB8;
        iconFrame = 4 ^ ((vintRunCount & 15) == 0 ? 1 : 0); // Initial ExecuteObjects V-int gate.
        try {
            var rom = GameServices.rom().getRom();
            var reader = RomByteReader.fromRom(rom);
            art = new ContinueScreenArtwork(rom, S2SpriteDataLoader.loadMappingFrames(reader, MAP),
                    Sonic2Constants.SONIC_TAILS_PALETTE_ADDR, 2);
            // PalPtr_SS1 targets line 3 only. Preserve the preceding mode's other target lines.
            var levelManager = GameServices.levelOrNull();
            var level = levelManager == null ? null : levelManager.getCurrentLevel();
            if (level != null) {
                for (int line = 0; line < 3; line++) art.setPalette(line, level.getPalette(line));
            }
            var stagePalette = new com.openggf.level.Palette();
            stagePalette.fromSegaFormat(rom.readBytes(PALETTE, 32));
            art.setPalette(3, stagePalette);
            art.loadNemesis(rom, Sonic2Constants.ART_NEM_TITLE_CARD_ADDR, 0x80);
            Pattern[] additional = PatternDecompressor.nemesis(rom, Sonic2Constants.ART_NEM_TITLE_CARD2_ADDR);
            int destination = 0x90;
            for (int address = LETTERS; reader.readU8(address) < 0x80; address += 2) {
                int length = reader.readU8(address + 1);
                art.copyPatterns(additional, reader.readU8(address), length, destination);
                destination += length;
            }
            art.loadNemesis(rom, ART_TAILS, 0);
            art.loadNemesis(rom, miniTails ? ART_MINI_TAILS : ART_MINI_SONIC, 0x24);
            digits = PatternDecompressor.fromBytes(rom.readBytes(Sonic2Constants.ART_UNC_HUD_NUMBERS_ADDR, 640));
            art.setCountdown(10, digits);
            art.cache();
            icons = art.withTileOffset(0x24, 0x200);
            nagging = new ObjectAnimationState(CommonSpriteDataLoader.loadAnimationSet(reader, 0x7CB0, 1), 0, 2);
            var playerArt = new Sonic2PlayerArt(reader);
            var sonicArt = playerArt.loadSonic();
            var sonicSprite = new Sonic("sonic", (short) 0, (short) 0);
            if (retainedSuperFlag) {
                // fixBugs=0: ContinueScreen leaves Super_Sonic_flag set. Sonic_Animate indexes
                // past SuperSonicAniData's 32 entries for Lying/LieDown; preserve those ROM reads.
                // The fixed branch clears the flag, selecting the normal scripts instead.
                var animations = playerArt.loadSuperSonicAnimationSet();
                int base = Sonic2Constants.SUPER_SONIC_ANIM_DATA_ADDR;
                for (int id = 0x20; id <= 0x21; id++) {
                    animations.addScript(id, CommonSpriteDataLoader.parseAnimationScript(reader,
                            base + reader.readS16BE(base + id * 2)));
                }
                sonicArt = new com.openggf.sprites.art.SpriteArtSet(sonicArt.artTiles(),
                        sonicArt.mappingFrames(), sonicArt.dplcFrames(), sonicArt.paletteIndex(),
                        sonicArt.basePatternIndex(), sonicArt.frameDelay(), sonicArt.bankSize(),
                        sonicArt.animationProfile(), animations);
                sonicSprite.setSuperSonic(true);
            }
            sonic = new ContinueScreenArtwork.Character(sonicArt, sonicSprite, 0x400, 0x20);
            tails = new ContinueScreenArtwork.Character(playerArt.loadTails(),
                    new Tails("tails", (short) 0, (short) 0), 0x600, 0);
            // RunObjects once before fade. ObjDB always creates both characters, even in Tails-only mode.
            sonic.update(vint, 0);
            nagging.update();
            GameServices.audio().playMusic(Sonic2Music.CONTINUE.id);
        } catch (IOException e) { throw new IllegalStateException("Cannot load S2 continue screen from ROM", e); }
    }

    @Override public void update(boolean startPressed, boolean start2Pressed) {
        if (finished || art == null) return;
        vint++;
        if (timer > 0) timer--;
        if (!accepted) {
            art.setCountdown(timer / 60, digits);
            if (startPressed) {
                accepted = true;
                sonic.setAnimation(0x21);
                // Both ObjDB character slots submit the same sound request, as in RunObjects.
                GameServices.audio().playSfx(Sonic2Sfx.SPINDASH_CHARGE.id);
                GameServices.audio().playSfx(Sonic2Sfx.SPINDASH_CHARGE.id);
            }
        }
        if (accepted) {
            if (sonicInertia == 0x800) sonicX += 16;
            else sonicInertia += 0x20;
            if (tailsInertia == 0x720) tailsX += 16;
            else tailsInertia += 0x18;
            sonic.update(vint, sonicInertia);
            tails.update(vint, tailsInertia);
            if (tailsX >= 384) finished = true;
        } else {
            sonic.update(vint, 0);
            nagging.update();
            if (timer == 0) finished = true;
        }
        if ((vint & 15) == 0) iconFrame ^= 1;
        if (accepted && sonicX != 0x9C && (vint & 1) == 0) usedIconDeleted = true;
    }

    @Override public void draw() {
        if (art == null) return;
        int offset = (Math.max(320, GameServices.graphics().getProjectionWidth()) - 320) / 2;
        art.cache();
        sonic.draw(sonicX + offset, 0x19C - 256);
        if (accepted) tails.draw(tailsX + offset, 0x1A0 - 256);
        else art.draw(nagging.getMappingFrame(), tailsX + offset, 0x1A0 - 256);
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
        art = null; sonic = tails = null; icons = null; nagging = null; digits = null;
        accepted = finished = usedIconDeleted = false;
        timer = vint = sonicX = tailsX = sonicInertia = tailsInertia = count = 0;
    }
}
