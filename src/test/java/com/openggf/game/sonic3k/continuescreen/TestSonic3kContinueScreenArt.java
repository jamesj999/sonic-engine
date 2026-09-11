package com.openggf.game.sonic3k.continuescreen;

import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.sonic3k.S3kSpriteDataLoader;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.util.PatternDecompressor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

@RequiresRom(SonicGame.SONIC_3K)
@ExtendWith(SingletonResetExtension.class)
class TestSonic3kContinueScreenArt {
    @BeforeEach
    void setUp() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.graphics().initHeadless();
    }

    @Test
    void screenOperandsResolveToLoadableArtAndBoundedMappings() throws Exception {
        var rom = GameServices.rom().getRom();
        var reader = RomByteReader.fromRom(rom);
        assertEquals(Sonic3kContinueScreenArt.MAP_SPRITES, reader.readU32BE(0x5C52C));
        assertEquals(Sonic3kContinueScreenArt.MAP_ICONS, reader.readU32BE(0x5CA1C));
        assertEquals(77, PatternDecompressor.nemesis(rom, Sonic3kContinueScreenArt.ART_SPRITES).length);
        assertEquals(46, PatternDecompressor.nemesis(rom, Sonic3kContinueScreenArt.ART_ICONS).length);
        assertEquals(20, PatternDecompressor.nemesis(rom, Sonic3kContinueScreenArt.ART_DIGITS).length);
        var frames = S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kContinueScreenArt.MAP_SPRITES);
        assertEquals(8, frames.size());
        for (var frame : frames) {
            for (var piece : frame.pieces()) {
                assertTrue(piece.tileIndex() + piece.widthTiles() * piece.heightTiles() <= 77);
            }
        }
        assertEquals(9, S3kSpriteDataLoader.loadMappingFrames(reader, Sonic3kContinueScreenArt.MAP_ICONS).size());
    }

    @Test
    void shippedSuperFlagMismatchSurvivesScreenInitializationAndDeparture() {
        var main = new com.openggf.sprites.playable.Sonic("sonic", (short) 0, (short) 0);
        main.setSuperSonic(true);
        GameServices.sprites().addSprite(main, "sonic");
        var screen = new Sonic3kContinueScreenProvider();
        screen.initialize(3);
        assertTrue(screen.retainedSuper());
        screen.update(true, false);
        while (!screen.isFinished()) {
            screen.update(false, false);
            screen.draw();
        }
        assertTrue(main.isSuperSonic());
        assertTrue(screen.isAccepted());
    }

    @Test
    void everyCharacterSceneLoadsAndDrawsThroughDepartureWithoutGl() {
        for (int mode = 0; mode < 4; mode++) {
            var screen = new Sonic3kContinueScreenProvider(mode, false);
            screen.initialize(10, 31);
            screen.draw();
            screen.update(true, false);
            while (!screen.isFinished()) {
                screen.update(false, false);
                screen.draw();
            }
        }
    }
}
