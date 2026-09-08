package com.openggf.level;

import com.openggf.camera.Camera;
import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Game;
import com.openggf.debug.DebugOverlayManager;
import com.openggf.debug.PerformanceProfiler;
import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.CrossGameFeatureProvider;
import com.openggf.game.GameDataSource;
import com.openggf.game.GameModule;
import com.openggf.game.MusicReference;
import com.openggf.game.PlayableCharacterRegistry;
import com.openggf.game.RomDataSource;
import com.openggf.game.StockGameDataSources;
import com.openggf.game.WaterDataProvider;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.ZoneProgressionPlan;
import com.openggf.game.ZoneRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.graphics.GraphicsManager;
import com.openggf.physics.CollisionSystem;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.level.objects.TouchResponseTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@Isolated
class TestGameDataSourceSharedFetches {
    @AfterEach void clearSession() { SessionManager.clear(); }

    @Test
    void sourceDefaultsRequireRomCapabilityButPreserveStockRomBytes(@TempDir Path directory) throws Exception {
        GameModule module = mock(GameModule.class, CALLS_REAL_METHODS);
        GameDataSource missing = missingSource();
        assertThrows(IllegalStateException.class, () -> module.createGame(missing));
        assertThrows(IllegalStateException.class, () -> module.createTouchResponseTable(missing));

        Path path = directory.resolve("source.gen");
        Files.write(path, new byte[] { 0x12, 0x34, 0x56 });
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(path.toString()));
            Game expectedGame = mock(Game.class);
            TouchResponseTable expectedTable = mock(TouchResponseTable.class);
            when(module.createGame(rom)).thenReturn(expectedGame);
            when(module.createTouchResponseTable(any(RomByteReader.class))).thenReturn(expectedTable);
            RomDataSource source = new RomDataSource(rom, "rom:test");

            assertSame(expectedGame, module.createGame(source));
            assertSame(expectedTable, module.createTouchResponseTable(source));
            ArgumentCaptor<RomByteReader> reader = ArgumentCaptor.forClass(RomByteReader.class);
            verify(module).createTouchResponseTable(reader.capture());
            assertArrayEquals(new byte[] { 0x12, 0x34, 0x56 }, reader.getValue().slice(0, 3));
        }
    }

    @Test
    void stockCompositionRootPinsExactRomWithStableDiagnosticIdentity() throws Exception {
        Rom rom = mock(Rom.class);
        when(rom.readAllBytes()).thenReturn(new byte[] { 1, 2, 3 });
        GameModule module = mock(GameModule.class);
        when(module.getIdentifier()).thenReturn("s2");

        RomDataSource source = StockGameDataSources.pinned(rom, module);

        assertSame(rom, source.rom().orElseThrow());
        assertEquals("rom:s2:sha256:039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                source.identity());
    }

    @Test
    void providerlessWaterInitializationNeverQueriesRomCapability() throws Exception {
        LevelManager levelManager = mock(LevelManager.class);
        levelManager.gameModule = mock(GameModule.class);
        levelManager.zoneFeatureProvider = null;

        new LevelWaterCoordinator(levelManager).initialize();

        verify(levelManager, never()).worldDataSource();
    }

    @Test
    void lazyParallaxConsumerAcceptsSessionWithoutRomCapability() {
        GameModule module = mock(GameModule.class);
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        SessionManager.openGameplaySession(module, module, missingSource(), null);

        assertFalse(new ParallaxManager().advanceCameraDrivenScroll(
                0, 0, mock(Camera.class), 0));
    }

    @Test
    void levelModuleAndAudioNullPathsUseExactSourceWithoutRomManager() throws Exception {
        GameDataSource source = spy(missingSource());
        GameModule module = mock(GameModule.class);
        Game game = mock(Game.class);
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        when(module.createGame(source)).thenReturn(game);
        when(module.getAudioProfile()).thenReturn(mock(com.openggf.audio.GameAudioProfile.class));
        when(module.getLevelMusicReference(anyInt(), anyInt())).thenReturn(new MusicReference.Stock(1));
        when(game.getSoundMap()).thenReturn(Map.of());
        ParallaxManager parallax = mock(ParallaxManager.class);
        AudioManager audio = mock(AudioManager.class);
        LevelManager levelManager = manager(source, module, parallax, audio, mock(WaterSystem.class));

        levelManager.initGameModule(0);
        levelManager.initAudio(0);

        verify(parallax).load(null);
        verify(module).createGame(source);
        verify(audio).setRom(null);
        verify(source, atLeastOnce()).rom();
    }

    @Test
    void objectInitializationUsesSourceOverloadBeforeLegacyReaderPath() throws Exception {
        GameDataSource source = missingSource();
        GameModule module = mock(GameModule.class);
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        IOException sentinel = new IOException("source overload reached");
        when(module.createTouchResponseTable(source)).thenThrow(sentinel);
        LevelManager levelManager = manager(source, module, mock(ParallaxManager.class),
                mock(AudioManager.class), mock(WaterSystem.class));
        levelManager.gameModule = module;

        assertSame(sentinel, assertThrows(IOException.class, levelManager::initObjectManager));
        verify(module).createTouchResponseTable(source);
        verify(module, never()).createTouchResponseTable(any(RomByteReader.class));
    }

    @Test
    void zoneFeatureRomLookupIsLazyAndProviderReceivesNull() throws Exception {
        GameDataSource source = spy(missingSource());
        GameModule module = mock(GameModule.class);
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        LevelManager levelManager = manager(source, module, mock(ParallaxManager.class),
                mock(AudioManager.class), mock(WaterSystem.class));

        levelManager.initializeZoneFeatureProvider(null);
        verify(source, never()).rom();

        ZoneFeatureProvider provider = mock(ZoneFeatureProvider.class);
        levelManager.initializeZoneFeatureProvider(provider);
        verify(source).rom();
        verify(provider).initZoneFeatures(isNull(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void waterProviderAndLegacyFallbackReceiveNullFromSource() throws Exception {
        GameDataSource source = spy(missingSource());
        GameModule module = mock(GameModule.class);
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        WaterSystem water = mock(WaterSystem.class);
        LevelManager levelManager = manager(source, module, mock(ParallaxManager.class),
                mock(AudioManager.class), water);
        levelManager.gameModule = module;
        WaterDataProvider provider = mock(WaterDataProvider.class);
        when(module.getWaterDataProvider()).thenReturn(provider);

        levelManager.waterCoordinator.initialize();

        verify(water).loadForLevelFromProvider(eq(provider), isNull(), anyInt(), anyInt(),
                any(), eq(false));

        reset(source, module, water);
        when(source.rom()).thenReturn(Optional.empty());
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        when(module.getWaterDataProvider()).thenReturn(null);
        ZoneFeatureProvider legacy = mock(ZoneFeatureProvider.class);
        when(legacy.hasWater(anyInt())).thenReturn(true);
        levelManager.zoneFeatureProvider = legacy;
        levelManager.level = mock(Level.class);
        when(water.hasWater(anyInt(), anyInt())).thenReturn(false);

        levelManager.waterCoordinator.initialize();

        verify(water).loadForLevel(isNull(), anyInt(), anyInt(), any());
    }

    @Test
    void romSessionPropagatesTheExactSameRomAcrossSharedConsumers() throws Exception {
        Rom rom = mock(Rom.class);
        RomDataSource source = new RomDataSource(rom, "rom:equivalence");
        GameModule module = mock(GameModule.class);
        when(module.getPlayableCharacterRegistry()).thenReturn(PlayableCharacterRegistry.empty());
        Game game = mock(Game.class);
        when(module.createGame(source)).thenReturn(game);
        when(module.getAudioProfile()).thenReturn(mock(com.openggf.audio.GameAudioProfile.class));
        when(module.getLevelMusicReference(anyInt(), anyInt())).thenReturn(new MusicReference.Stock(1));
        when(game.getSoundMap()).thenReturn(Map.of());
        ParallaxManager parallax = mock(ParallaxManager.class);
        AudioManager audio = mock(AudioManager.class);
        WaterSystem water = mock(WaterSystem.class);
        LevelManager levelManager = manager(source, module, parallax, audio, water);

        levelManager.initGameModule(0);
        levelManager.initAudio(0);
        ZoneFeatureProvider zone = mock(ZoneFeatureProvider.class);
        levelManager.initializeZoneFeatureProvider(zone);
        WaterDataProvider waterProvider = mock(WaterDataProvider.class);
        when(module.getWaterDataProvider()).thenReturn(waterProvider);
        levelManager.waterCoordinator.initialize();

        verify(parallax).load(same(rom));
        verify(audio).setRom(same(rom));
        verify(zone).initZoneFeatures(same(rom), anyInt(), anyInt(), anyInt());
        verify(water).loadForLevelFromProvider(eq(waterProvider), same(rom), anyInt(), anyInt(),
                any(), eq(false));
    }

    @Test
    void allFiveSharedFetchSitesUseTheWorldSessionSourceAndKeepGuardsLazy() throws Exception {
        String levelManager = Files.readString(Path.of(
                "src/main/java/com/openggf/level/LevelManager.java"));
        String water = Files.readString(Path.of(
                "src/main/java/com/openggf/level/LevelWaterCoordinator.java"));
        String parallax = Files.readString(Path.of(
                "src/main/java/com/openggf/level/ParallaxManager.java"));
        String engine = Files.readString(Path.of("src/main/java/com/openggf/Engine.java"));

        assertFalse(levelManager.contains("GameServices.rom().getRom()"));
        assertFalse(water.contains("GameServices.rom().getRom()"));
        assertFalse(parallax.contains("GameServices.rom().getRom()"));
        assertTrue(levelManager.contains("gameModule.createGame(source)"));
        assertTrue(levelManager.contains(
                "audioManager.setRom(worldSession.getDataSource().rom().orElse(null))"));
        assertTrue(levelManager.contains(
                "gameModule.createTouchResponseTable(worldSession.getDataSource())"));
        assertTrue(levelManager.indexOf("if (zoneFeatureProvider != null)")
                < levelManager.indexOf("Rom rom = worldSession.getDataSource().rom().orElse(null)",
                levelManager.indexOf("initializeZoneFeatureProvider")));
        assertTrue(water.indexOf("if (waterProvider != null)")
                < water.indexOf("levelManager.worldDataSource().rom().orElse(null)"));
        // The scroll provider still resolves ROM bytes through the world-session
        // data source, but must do so null-safely: a lazy scroll probe can run
        // with no active WorldSession (e.g. the missing-ROM logging test), where
        // the throwing GameServices.worldSession() accessor would break it.
        assertTrue(parallax.contains(
                "worldSession.getDataSource().rom().orElse(null)"));
        assertTrue(parallax.contains("SessionManager.getCurrentWorldSession()"),
                "scroll provider must resolve the world session null-safely so a "
                        + "probe with no active session does not throw");
        assertFalse(engine.contains(
                "SessionManager.openGameplaySession(rootModule, module, null)"));
        assertTrue(engine.contains("StockGameDataSources.pinned(rom, rootModule)"));
    }

    private static GameDataSource missingSource() {
        return new GameDataSource() {
            @Override public Optional<Rom> rom() { return Optional.empty(); }
            @Override public java.io.InputStream openAsset(String normalizedPath)
                    throws IOException {
                throw new IOException("no assets");
            }
            @Override public String identity() { return "missing:test"; }
        };
    }

    private static LevelManager manager(GameDataSource source, GameModule module,
            ParallaxManager parallax, AudioManager audio, WaterSystem water) {
        ZoneRegistry zones = mock(ZoneRegistry.class);
        when(zones.getAllZones()).thenReturn(
                java.util.List.of(java.util.List.of(mock(LevelDescriptor.class))));
        when(zones.progressionPlan()).thenReturn(ZoneProgressionPlan.LINEAR);
        when(zones.progressionTopology()).thenReturn(
                ZoneProgressionPlan.ZoneTopology.linear(1));
        when(module.getZoneRegistry()).thenReturn(zones);
        SessionManager.openGameplaySession(module, module, source, null);
        EngineContext services = mock(EngineContext.class);
        SonicConfigurationService configuration = mock(SonicConfigurationService.class);
        when(configuration.getInt(SonicConfiguration.SCREEN_WIDTH_PIXELS)).thenReturn(320);
        when(configuration.getInt(SonicConfiguration.SCREEN_HEIGHT_PIXELS)).thenReturn(224);
        when(services.graphics()).thenReturn(mock(GraphicsManager.class));
        when(services.audio()).thenReturn(audio);
        when(services.configuration()).thenReturn(configuration);
        when(services.debugOverlay()).thenReturn(mock(DebugOverlayManager.class));
        when(services.profiler()).thenReturn(mock(PerformanceProfiler.class));
        when(services.crossGameFeatures()).thenReturn(mock(CrossGameFeatureProvider.class));
        WorldSession world = SessionManager.getCurrentWorldSession();
        return new LevelManager(mock(Camera.class), mock(SpriteManager.class),
                parallax, mock(CollisionSystem.class), water,
                new com.openggf.game.GameStateManager(), services, world);
    }
}
