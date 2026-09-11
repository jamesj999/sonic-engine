package com.openggf.game.sonic3k.resources;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Game;
import com.openggf.data.Rom;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3k;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.Chunk;
import com.openggf.level.Pattern;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.level.resources.CompressionType;
import com.openggf.level.resources.DeferredLevelResourceDescriptor;
import com.openggf.level.resources.DeferredLevelResourceManifest;
import com.openggf.level.resources.DeferredLevelResourceLoader;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kDeferredLevelResourceLoading {
    private Object oldSkipIntros;

    @AfterEach
    void tearDown() {
        if (oldSkipIntros != null) {
            SonicConfigurationService.getInstance().setConfigValue(
                    SonicConfiguration.S3K_SKIP_INTROS, oldSkipIntros);
        }
        SessionManager.clear();
    }

    @Test
    void introLoadsEntryZeroSecondaryBytesButDefersEntryTwentySixDeclarations()
            throws Exception {
        Sonic3kLevel level = loadAiz1(false);
        Rom rom = TestEnvironment.currentRom();
        ResourceLoader loader = new ResourceLoader(rom);
        LlbSources intro = readSources(rom, 0);
        LlbSources main = readSources(
                rom, Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX);

        byte[] introArt = loader.loadSingle(
                LoadOp.kosinskiMBase(intro.secondaryArt()));
        int introArtDestination = loader.loadSingle(
                LoadOp.kosinskiMBase(intro.primaryArt())).length;
        assertTrue(patternPayloadVisible(
                level, introArt,
                introArtDestination / Pattern.PATTERN_SIZE_IN_ROM),
                "entry 0's intro secondary art must load synchronously");

        byte[] introChunks = loader.loadSingle(
                LoadOp.kosinskiBase(intro.secondaryChunks()));
        int introChunkDestination = loader.loadSingle(
                LoadOp.kosinskiBase(intro.primaryChunks())).length;
        assertTrue(chunkPayloadVisible(
                level, introChunks, introChunkDestination),
                "entry 0's intro secondary 16x16 terrain must load synchronously");

        byte[] mainArt = loader.loadSingle(
                LoadOp.kosinskiMBase(main.secondaryArt()));
        assertFalse(patternPayloadVisible(level, mainArt, 0x00BE),
                "entry 26's main-level art declaration must remain deferred");
        byte[] mainChunks = loader.loadSingle(
                LoadOp.kosinskiBase(main.secondaryChunks()));
        assertFalse(chunkPayloadVisible(level, mainChunks, 0x0268),
                "entry 26's main-level 16x16 declaration must remain deferred");
    }

    @Test
    void skippedIntroLoadsEntryTwentySixSecondaryBytesImmediately()
            throws Exception {
        Sonic3kLevel level = loadAiz1(true);
        Rom rom = TestEnvironment.currentRom();
        ResourceLoader loader = new ResourceLoader(rom);
        LlbSources main = readSources(
                rom, Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX);

        byte[] mainArt = loader.loadSingle(
                LoadOp.kosinskiMBase(main.secondaryArt()));
        assertTrue(patternPayloadVisible(level, mainArt, 0x00BE),
                "post-intro entry 26 art must load synchronously");
        byte[] mainChunks = loader.loadSingle(
                LoadOp.kosinskiBase(main.secondaryChunks()));
        assertTrue(chunkPayloadVisible(level, mainChunks, 0x0268),
                "post-intro entry 26 terrain must load synchronously");
    }

    @Test
    void icz2LoadRejectsHandoffMissingMandatoryTerrainDescriptors()
            throws Exception {
        Rom rom = TestEnvironment.currentRom();
        LlbSources icz2 = readSources(rom, 11);
        DeferredLevelResourceManifest incomplete =
                new DeferredLevelResourceManifest(java.util.List.of(
                        new DeferredLevelResourceDescriptor(
                                DeferredLevelResourceDescriptor.Kind.PATTERNS_8X8,
                                icz2.secondaryArt(),
                                CompressionType.KOSINSKI_MODULED,
                                0x0122 * Pattern.PATTERN_SIZE_IN_ROM)));

        assertThrows(IllegalStateException.class,
                () -> deferredLoader(rom).loadLevelWithDeferredResources(
                        0xC0 + 11, incomplete.newTracker()));
    }

    @Test
    void ordinaryIcz2LoadWithoutADeferredPolicySucceeds() {
        Rom rom = TestEnvironment.currentRom();

        assertDoesNotThrow(
                () -> new Sonic3k(rom).loadLevel(0xC0 + 11));
    }

    @Test
    void icz2HandoffWithAnExplicitEmptyManifestFailsMandatoryValidation() {
        Rom rom = TestEnvironment.currentRom();

        assertThrows(IllegalStateException.class,
                () -> deferredLoader(rom).loadLevelWithDeferredResources(
                        0xC0 + 11,
                        DeferredLevelResourceManifest.EMPTY.newTracker()));
    }

    @Test
    void deferredLoadingIsOwnedByTheS3kLevelLayerRatherThanBaseGame() throws Exception {
        Class<?> loaderType = Class.forName(
                "com.openggf.level.resources.DeferredLevelResourceLoader");

        assertTrue(loaderType.isAssignableFrom(Sonic3k.class),
                "S3K must expose deferred loading through the level-layer provider");
        for (Method method : Game.class.getDeclaredMethods()) {
            assertFalse(Arrays.stream(method.getParameterTypes()).anyMatch(
                            parameter -> parameter.getSimpleName()
                                    .equals("DeferredLevelResourceTracker")),
                    "base Game must not depend on level-runtime resource trackers");
        }
    }

    private Sonic3kLevel loadAiz1(boolean skipIntros) throws Exception {
        SonicConfigurationService config =
                SonicConfigurationService.getInstance();
        oldSkipIntros = config.getConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, skipIntros);
        return (Sonic3kLevel) new Sonic3k(
                TestEnvironment.currentRom()).loadLevel(0xC0);
    }

    private static DeferredLevelResourceLoader deferredLoader(Rom rom)
            throws IOException {
        return new Sonic3k(rom);
    }

    private static LlbSources readSources(Rom rom, int index)
            throws IOException {
        int entry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                + index * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
        return new LlbSources(
                rom.read32BitAddr(entry) & 0x00FF_FFFF,
                rom.read32BitAddr(entry + 4) & 0x00FF_FFFF,
                rom.read32BitAddr(entry + 8) & 0x00FF_FFFF,
                rom.read32BitAddr(entry + 12) & 0x00FF_FFFF,
                rom.read32BitAddr(entry + 20) & 0x00FF_FFFF);
    }

    private static boolean patternPayloadVisible(
            Sonic3kLevel level, byte[] payload, int destinationPattern) {
        int count = payload.length / Pattern.PATTERN_SIZE_IN_ROM;
        if (level.getPatternCount() < destinationPattern + count) {
            return false;
        }
        for (int pattern = 0; pattern < count; pattern++) {
            Pattern expected = new Pattern();
            int start = pattern * Pattern.PATTERN_SIZE_IN_ROM;
            expected.fromSegaFormat(Arrays.copyOfRange(
                    payload, start,
                    start + Pattern.PATTERN_SIZE_IN_ROM));
            Pattern actual = level.getPattern(destinationPattern + pattern);
            for (int y = 0; y < Pattern.PATTERN_HEIGHT; y++) {
                for (int x = 0; x < Pattern.PATTERN_WIDTH; x++) {
                    if (actual.getPixel(x, y)
                            != expected.getPixel(x, y)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean chunkPayloadVisible(
            Sonic3kLevel level, byte[] payload, int destinationBytes) {
        int start = destinationBytes / Chunk.CHUNK_SIZE_IN_ROM;
        int count = payload.length / Chunk.CHUNK_SIZE_IN_ROM;
        if (level.getChunkCount() < start + count) {
            return false;
        }
        for (int chunk = 0; chunk < count; chunk++) {
            int[] actual = level.getChunk(start + chunk).saveState();
            int payloadOffset = chunk * Chunk.CHUNK_SIZE_IN_ROM;
            for (int word = 0; word < Chunk.PATTERNS_PER_CHUNK; word++) {
                int byteOffset = payloadOffset + word * 2;
                int expected = ((payload[byteOffset] & 0xFF) << 8)
                        | (payload[byteOffset + 1] & 0xFF);
                if (actual[word] != expected) {
                    return false;
                }
            }
        }
        return true;
    }

    private record LlbSources(
            int primaryArt,
            int secondaryArt,
            int primaryChunks,
            int secondaryChunks,
            int secondaryBlocks) {
    }
}
