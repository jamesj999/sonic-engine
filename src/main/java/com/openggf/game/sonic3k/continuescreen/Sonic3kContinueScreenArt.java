package com.openggf.game.sonic3k.continuescreen;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.S3kFrontendPaletteUploader;
import com.openggf.game.sonic3k.S3kSpriteDataLoader;
import com.openggf.game.sonic3k.Sonic3kPlayerArt;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.GraphicsManager;
import com.openggf.graphics.PatternAtlasRange;
import com.openggf.level.Pattern;
import com.openggf.level.PatternDesc;
import com.openggf.level.objects.ObjectSpriteSheet;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.sprites.art.SpriteArtSet;
import com.openggf.sprites.render.PlayerSpriteRenderer;
import com.openggf.util.PatternDecompressor;

import java.io.IOException;
import java.util.List;

/** ROM-only graphics for ContinueScreen ($5C2E0); no disassembly runtime access. */
final class Sonic3kContinueScreenArt {
    // Verified against locked-on CRC32 63522553: ContinueScreen LEA operands and
    // byte-for-byte compressed/palette blobs. Mappings are S3K six-byte pieces.
    static final int PALETTE = 0x5CBCA;
    static final int MAP_SPRITES = 0x5CC4A;
    static final int MAP_ICONS = 0x5CD00;
    static final int ART_SPRITES = 0x5CD66;
    static final int ART_ICONS = 0x5D3C6;
    static final int ART_DIGITS = 0x5D788;
    static final int ART_TEXT = 0xDDE34;
    static final int TEXT_MAPPING = 0x5B566;
    private static final int[] ICON_X = {288, 312, 264, 336, 240, 360, 216, 384, 192};
    private static final int BASE = PatternAtlasRange.CONTINUE_SCREEN.base();
    private final GraphicsManager graphics;
    private final RomByteReader reader;
    private final PatternDesc desc = new PatternDesc();
    private final PatternSpriteRenderer sprites;
    private final PatternSpriteRenderer knucklesIdle;
    private final PatternSpriteRenderer punctuation;
    private final PatternSpriteRenderer icons;
    private final PatternSpriteRenderer knucklesIcons;
    private final PatternSpriteRenderer eggRobo;
    private final PlayerSpriteRenderer sonic;
    private final PlayerSpriteRenderer tails;
    private final PlayerSpriteRenderer tail;
    private final PlayerSpriteRenderer knuckles;
    private final List<Integer> sonicRun;
    private final List<Integer> tailsRun;

    static Sonic3kContinueScreenArt load(boolean retainedSuper) {
        try {
            return new Sonic3kContinueScreenArt(GameServices.rom().getRom(), GameServices.graphics(), retainedSuper);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load S3K Continue screen ROM assets", e);
        }
    }

    Sonic3kContinueScreenArt(Rom rom, GraphicsManager graphics, boolean retainedSuper) throws IOException {
        this.graphics = graphics;
        reader = RomByteReader.fromRom(rom);
        for (int line = 0; line < 4; line++) {
            S3kFrontendPaletteUploader.cacheLineFromBytes(graphics, reader.slice(PALETTE + line * 32, 32), line);
        }
        var spriteFrames = S3kSpriteDataLoader.loadMappingFrames(reader, MAP_SPRITES);
        var iconFrames = S3kSpriteDataLoader.loadMappingFrames(reader, MAP_ICONS);
        Pattern[] spriteArt = PatternDecompressor.nemesis(rom, ART_SPRITES);
        Pattern[] iconArt = PatternDecompressor.nemesis(rom, ART_ICONS);
        sprites = sheet(spriteArt, spriteFrames, 0, BASE);
        knucklesIdle = sheet(spriteArt, spriteFrames, 3, BASE);
        punctuation = sheet(spriteArt, spriteFrames, 2, BASE);
        icons = sheet(iconArt, iconFrames, 0, BASE + 0x100);
        knucklesIcons = sheet(iconArt, iconFrames, 3, BASE + 0x100);
        cache(PatternDecompressor.nemesis(rom, ART_DIGITS), BASE + 0x200);
        cache(PatternDecompressor.nemesis(rom, ART_TEXT), BASE + 0x300);
        Sonic3kPlayerArt players = new Sonic3kPlayerArt(reader);
        SpriteArtSet sonicArt = players.loadSonic();
        SpriteArtSet tailsArt = players.loadTails();
        if (retainedSuper) {
            var superArt = players.loadSuperSonicArtSet();
            // Shipped Continue sets Map_Sonic but Sonic_Load_PLC still selects
            // PLC_SuperSonic. Preserve that mismatch rather than fixing the ROM.
            sonicArt = new SpriteArtSet(sonicArt.artTiles(), sonicArt.mappingFrames(),
                    superArt.dplcFrames(), 0, sonicArt.basePatternIndex(), 1,
                    Math.max(sonicArt.bankSize(), superArt.bankSize()),
                    sonicArt.animationProfile(), superArt.animationSet());
        }
        sonicRun = sonicArt.animationSet().getScript(retainedSuper ? 0 : 1).frames();
        tailsRun = tailsArt.animationSet().getScript(1).frames();
        sonic = player(sonicArt, BASE + 0x500, 0);
        tails = player(tailsArt, BASE + 0x600, 0);
        tail = player(players.loadTailsTail(), BASE + 0x700, 0);
        knuckles = player(players.loadKnuckles(), BASE + 0x800, 3);
        // ObjDat3_919A6 points to Map_EggRobo ($184F34); loc_5C972
        // queues ArtKosM_EggRoboBadnik ($17B17E).
        eggRobo = sheet(PatternDecompressor.kosinskiModuled(rom, 0x17B17E),
                S3kSpriteDataLoader.loadMappingFrames(reader, 0x184F34), 0, BASE + 0x900);
    }

    private PatternSpriteRenderer sheet(Pattern[] art, List<SpriteMappingFrame> frames, int palette, int base) {
        var renderer = new PatternSpriteRenderer(new ObjectSpriteSheet(art, frames, palette, 1), graphics);
        renderer.ensurePatternsCached(graphics, base);
        return renderer;
    }

    private PlayerSpriteRenderer player(SpriteArtSet art, int base, int palette) {
        return new PlayerSpriteRenderer(new SpriteArtSet(art.artTiles(), art.mappingFrames(), art.dplcFrames(),
                palette, base, art.frameDelay(), art.bankSize(), art.animationProfile(), art.animationSet()), graphics);
    }

    private void cache(Pattern[] patterns, int base) {
        for (int i = 0; i < patterns.length; i++) {
            graphics.cachePatternTexture(patterns[i], base + i);
        }
    }

    void draw(Sonic3kContinueScreenProvider screen) {
        int width = Math.max(320, graphics.getProjectionWidth());
        int ox = (width - 320) / 2;
        graphics.registerCommand(new GLCommand(GLCommand.CommandType.RECTI, -1,
                0, 0, 0, 0, 0, width, 224));
        graphics.beginPatternBatch();
        drawText(ox);
        // sub_5CAAE: two columns at Plane A+$726, both 8x16 digits.
        tile(BASE + 0x200, 0, ox + 152, 112);
        tile(BASE + 0x200, 1, ox + 152, 120);
        tile(BASE + 0x200, screen.countdown() * 2, ox + 160, 112);
        tile(BASE + 0x200, screen.countdown() * 2 + 1, ox + 160, 120);
        punctuation.drawFrameIndex(7, ox + 160, 117);
        int blink = (screen.vintRunCount() >>> 4) & 1;
        int mode = screen.playerMode();
        for (int i = 0; i < screen.iconCount(); i++) {
            int frame = (mode == 3 ? 7 : mode == 2 ? 2 : 0) + blink;
            (mode == 3 ? knucklesIcons : icons).drawFrameIndex(frame, ox + ICON_X[i] - 128, 88);
            if (mode == 2) {
                // Obj_5CA78 / byte_5CBBB: separate Tails icon's swishing tail.
                icons.drawFrameIndex(screen.iconTailFrame(), ox + ICON_X[i] - 128, 88);
            }
        }
        drawActors(screen, ox);
        graphics.flushPatternBatch();
    }

    private void drawText(int ox) {
        int x = ox + 72; // ContinueScreen d2=$292: plane row5,column9.
        for (int i = 0; i < 15; i++) {
            int character = reader.readU8(0x5CB9E + i); // aCONTINUE, including spaces
            if (character == 0) {
                break;
            }
            if (character == ' ') {
                x += 8;
                continue;
            }
            int address = TEXT_MAPPING + (character - 'A') * 8;
            int columns = character == 'I' ? 1 : 2;
            for (int col = 0; col < columns; col++) {
                tile(BASE + 0x300, reader.readU16BE(address + col * 2), x + col * 8, 40);
                tile(BASE + 0x300, reader.readU16BE(address + (columns + col) * 2), x + col * 8, 48);
            }
            x += columns * 8;
        }
    }

    private void tile(int base, int word, int x, int y) {
        desc.set(word);
        graphics.renderPatternWithId(base + (word & 0x7ff), desc, x, y);
    }

    private void drawActors(Sonic3kContinueScreenProvider screen, int ox) {
        int age = screen.acceptedAge();
        if (screen.playerMode() == 3) {
            if (age > 1) {
                drawEggRobo(screen, ox);
            }
            if (age < 49) {
                knucklesIdle.drawFrameIndex(screen.knucklesFrame(), ox + 156, 160);
            } else {
                int x = 284 + Math.max(0, age - 49) * 6;
                knuckles.drawFrame(screen.knucklesFrame(), ox + x - 128, 160, false, false);
            }
            return;
        }
        // This offscreen Knuckles actor exists even for Sonic/Tails Player_mode.
        int knuxX = Math.min(484, 64 + age * 6);

        if (screen.skAlone()) {
            sonic.drawFrame(screen.soloFrame(), ox + 160 + Math.max(0, age - 47) * 6, 160, false, false);
            knuckles.drawFrame(screen.knucklesFrame(), ox + knuxX - 128, 160, false, false);
            return;
        }
        if (age == 0) {
            sprites.drawFrameIndex((screen.vintRunCount() >>> 4) & 1, ox + 152, 160);
            sprites.drawFrameIndex(5 + ((screen.vintRunCount() >>> 5) & 1), ox + 172, 160);
            knuckles.drawFrame(screen.knucklesFrame(), ox + knuxX - 128, 160, false, false);
            return;
        }
        int sonicFrame;
        boolean sonicFlip = false;
        if (age < 36) {
            // RawAni_5C622 advances every seven calls; first entry's flip is
            // deliberately not applied by loc_5C5AC.
            int entry = (age - 1) / 7;
            sonicFrame = reader.readU8(0x5C622 + entry * 2);
            sonicFlip = entry > 0 && reader.readU8(0x5C623 + entry * 2) != 0;
        } else if (age == 36) {
            sonicFrame = 0x57;
        } else {
            sonicFrame = runFrame(sonicRun, age - 37);
        }
        sonic.drawFrame(sonicFrame, ox + 152 + Math.max(0, age - 52) * 6, 160, sonicFlip, false);
        int tailsX = ox + 172 + Math.max(0, age - 60) * 6;
        tails.drawFrame(age < 41 ? 0xAD : runFrame(tailsRun, age - 41), tailsX, 164, false, false);
        // Obj_Tails_Tail selects Swish during Wait; Walk switches to blank.
        tail.drawFrame(age < 41 ? 0x22 + ((age - 1) / 8) % 5 : 0, tailsX, 164, false, false);
        knuckles.drawFrame(screen.knucklesFrame(), ox + knuxX - 128, 160, false, false);
    }

    private static int runFrame(List<Integer> frames, int elapsed) {
        // Animate_Sonic/Tails: ($800-$600)>>8=2, decrement-until-negative.
        return frames.get(((elapsed + 2) / 3) % frames.size());
    }

    private void drawEggRobo(Sonic3kContinueScreenProvider screen, int ox) {
        int age = screen.acceptedAge();
        int velocity = screen.eggVelocity();
        int x = ox - 32 + (age - 1) * 6;
        int drawY = screen.eggY() - 128;
        eggRobo.drawFrameIndex((screen.vintRunCount() & 1) == 0 ? 1 : 3, x, drawY, true, false);
        eggRobo.drawFrameIndex(velocity < 0 ? 6 : velocity < 0x20 ? 5 : 4, x + 12, drawY + 28, true, false);
        eggRobo.drawFrameIndex(2, x + 28, drawY - 4, true, false);
    }
}
