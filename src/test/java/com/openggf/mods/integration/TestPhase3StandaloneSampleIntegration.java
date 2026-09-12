package com.openggf.mods.integration;

import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.ModSubsystem;
import com.openggf.SessionExternalContentView;
import com.openggf.audio.AudioManager;
import com.openggf.audio.AbstractSmpsAudioBackend;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.StreamedMusicPort;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.game.CharacterKey;
import com.openggf.game.GameDataSource;
import com.openggf.game.GameMode;
import com.openggf.game.GameModule;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.MasterTitleEntry;
import com.openggf.game.MusicReference;
import com.openggf.game.PhysicsProfile;
import com.openggf.tests.TestSessionOutputPaths;
import com.openggf.io.ModInputLimits;
import com.openggf.level.ModLevel;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRegistry;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.StubObjectServices;
import com.openggf.mods.*;
import com.openggf.mods.code.ModClassLoaderFactory;
import com.openggf.mods.code.ModFaultBoundary;
import com.openggf.mods.code.ModRuntime;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Isolated
class TestPhase3StandaloneSampleIntegration {
    private static final String OWNER = "phase3-standalone";
    private static final String RUNNER = OWNER + ":runner";
    private static final String FRIEND = OWNER + ":friend";
    @TempDir Path temp;
    private String previousSaveRoot;
    private EngineContext previousEngineContext;

    @BeforeEach void resetState() { TestEnvironment.resetAll(); }

    @AfterEach void cleanup() {
        SessionManager.clear();
        GameModuleRegistry.reset();
        ModSubsystem.clearProcess();
        AudioManager.getInstance().resetState();
        if (previousEngineContext != null) {
            EngineServices.configure(previousEngineContext);
            previousEngineContext = null;
        }
        if (previousSaveRoot == null) System.clearProperty("openggf.saveRoot");
        else System.setProperty("openggf.saveRoot", previousSaveRoot);
        TestEnvironment.resetAll();
    }

    @Test
    void realPackagedStandaloneLoadsWithoutRomAndExercisesCharacterLevelObjectAudioAndTerminalTopology()
            throws Exception {
        Path jar = buildSample();
        try (Fixture fixture = load(jar)) {
            GameModule module = fixture.runtime.prepareStandaloneModule(OWNER).orElseThrow();
            assertEquals(OWNER, module.getIdentifier());
            assertEquals(com.openggf.game.GameId.STANDALONE, module.getGameId());
            assertEquals(1, module.getZoneRegistry().getZoneCount());
            assertEquals(com.openggf.game.ZoneProgressionPlan.Credits.INSTANCE,
                    module.getZoneRegistry().progressionPlan().next(
                            module.getZoneRegistry().progressionTopology(), 0, 0));

            var assets = fixture.runtime.standaloneAssetSnapshot(OWNER);
            GameDataSource source = new GameDataSource() {
                @Override public Optional<com.openggf.data.Rom> rom() { return Optional.empty(); }
                @Override public java.io.InputStream openAsset(String path) throws java.io.IOException {
                    return new ByteArrayInputStream(assets.readBounded(path, assets.limits().maxAssetBytes()));
                }
                @Override public String identity() { return "standalone:" + OWNER + ":acceptance"; }
            };
            var game = module.createGame(source);
            assertNull(game.getRom());
            assertInstanceOf(ModLevel.class, game.loadLevel(0x400));
            ModLevel level = (ModLevel) game.loadLevel(0x400);
            assertEquals(0x400, module.getZoneRegistry().getLevelDataForZone(0).getFirst().levelIndex(),
                    "Every declared descriptor must route through ModGame.loadLevel by its exact index");
            assertEquals("phase3-standalone:walker", level.getObjects().getFirst().objectKey());
            assertEquals(new MusicReference.Namespaced(OWNER, "zone-theme"),
                    module.getLevelMusicReference(0, 0));

            CharacterKey runnerKey = CharacterKey.parsePersisted(RUNNER);
            var definition = module.getPlayableCharacterRegistry().find(runnerKey).orElseThrow();
            assertEquals(com.openggf.sprites.playable.SecondaryAbility.NONE,
                    definition.secondaryAbility());
            assertFalse(definition.supportsSuperForm());
            assertFalse(definition.artSupplier().load(RUNNER).mappingFrames().isEmpty());
            assertNotNull(definition.paletteSupplier());
            assertEquals(0x500, module.getPhysicsProvider().getProfile(RUNNER).max());
            assertNotEquals(PhysicsProfile.SONIC_2_SONIC,
                    module.getPhysicsProvider().getProfile(RUNNER));

            GameModuleRegistry.setCurrent(module);
            AbstractPlayableSprite player = definition.spriteFactory().create(RUNNER, 32, 96);
            assertEquals(runnerKey, player.characterKey());
            assertEquals(0x500, player.getPhysicsProfile().max(),
                    "The sample's literal profile must drive the constructed player");

            NullAudioBackend backend = new NullAudioBackend();
            AudioManager audio = AudioManager.getInstance();
            audio.resetState();
            audio.setBackend(backend);
            audio.installStreamedMusicPort(
                    preparePackagedAudioPort(fixture, audio.outputSampleRate()));
            ObjectRegistry registry = module.createObjectRegistry();
            ObjectManager[] holder = new ObjectManager[1];
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public AudioManager audioManager() { return audio; }
            };
            holder[0] = new ObjectManager(List.of(), registry, 0, null, null, null, null, services);
            ObjectSpawn spawn = level.getObjects().getFirst();
            ObjectInstance badnik = holder[0].createDynamicObject(() -> registry.create(spawn));
            int before = badnik.getX();
            badnik.update(1, player);
            badnik.update(2, player);
            assertNotEquals(before, badnik.getX());
            StreamedMusicPort.SfxRef hit = new StreamedMusicPort.SfxRef(OWNER, "hit");
            assertTrue(audio.commandTimeline().entries().stream()
                            .anyMatch(entry -> new AudioCommand.PlayNamespacedSfx(hit)
                                    .equals(entry.command())),
                    "The normal injected ObjectServices path must record the exact namespaced SFX key");

            ModAudioManifest audioManifest;
            try (JarFile packed = new JarFile(jar.toFile())) {
                byte[] yaml = packed.getInputStream(packed.getJarEntry("audio/audio-manifest.yaml"))
                        .readAllBytes();
                audioManifest = new ModAudioManifestParser(OWNER).parse(yaml);
                assertNotNull(packed.getJarEntry("art/runner.ggfp"));
                assertNotNull(packed.getJarEntry("levels/sample/level.json"));
            }
            assertEquals(List.of(new TrackKey(OWNER, "zone-theme")),
                    audioManifest.tracks().stream().map(ModAudioTrack::key).toList());
            assertEquals(List.of(new SfxKey(OWNER, "hit")),
                    audioManifest.sfx().stream().map(ModAudioSfx::key).toList());
            assertPackagedSfxTraversesBoundedPcmPool(fixture, audioManifest);

            exerciseMasterTitleNewCompleteAndContinue(fixture, module, backend);
        }
    }

    private static com.openggf.ModStreamedMusicPort preparePackagedAudioPort(
            Fixture fixture, int outputRate) throws Exception {
        ModAudioManifest manifest;
        try (JarFile packed = new JarFile(fixture.descriptor.jarPath().toFile())) {
            byte[] yaml = packed.getInputStream(packed.getJarEntry("audio/audio-manifest.yaml"))
                    .readAllBytes();
            manifest = new ModAudioManifestParser(OWNER).parse(yaml);
        }
        ModTrackRegistry tracks = new ModTrackRegistry(manifest.tracks());
        ModSfxRegistry sfx = new ModSfxRegistry(manifest.sfx());
        ModAudioPreparer preparer = new ModAudioPreparer(
                fixture.descriptor.jarPath().getParent().toAbsolutePath().normalize(),
                ModInputLimits.production(), new ModRuntimeFindingStore(),
                owners -> new ModStateSaveResult.Saved());
        PreparedAudioSession session = preparer.prepare(
                fixture.catalog.effective(), tracks, sfx, outputRate);
        PreparedModMusic music = PreparedModMusic.build(
                fixture.catalog.effective(), tracks, sfx, session, outputRate, OWNER);
        return new com.openggf.ModStreamedMusicPort(
                music, new StreamedMusicPlayer(outputRate), OWNER);
    }

    private void assertPackagedSfxTraversesBoundedPcmPool(Fixture fixture,
                                                           ModAudioManifest manifest) {
        ModTrackRegistry tracks = new ModTrackRegistry(manifest.tracks());
        ModSfxRegistry sfx = new ModSfxRegistry(manifest.sfx());
        ModAudioPreparer preparer = new ModAudioPreparer(
                fixture.descriptor.jarPath().getParent().toAbsolutePath().normalize(),
                ModInputLimits.production(), new ModRuntimeFindingStore(),
                owners -> new ModStateSaveResult.Saved());
        PreparedAudioSession session = preparer.prepare(
                fixture.catalog.effective(), tracks, sfx, 8_000);
        PreparedModMusic music = PreparedModMusic.build(fixture.catalog.effective(), tracks, sfx,
                session, 8_000, OWNER);
        PcmPoolBackend backend = new PcmPoolBackend();
        StreamedMusicPort port = new com.openggf.ModStreamedMusicPort(
                music, new StreamedMusicPlayer(8_000), OWNER);
        backend.installStreamedMusicPort(port);
        backend.update();
        StreamedMusicPort.SfxRef hit = new StreamedMusicPort.SfxRef(OWNER, "hit");
        assertTrue(backend.tryPlayStreamedSfx(hit));

        // The presentation layer now owns one-shot playback, so the bounded pool's
        // job ends at handing out decoded PCM. Assert that boundary directly rather
        // than through a device upload the backend no longer performs.
        StreamedMusicPort.SfxPcm pcm = port.sfxPcm(hit).orElseThrow();
        assertEquals(8_000, pcm.sampleRate());
        assertTrue(java.util.stream.IntStream.range(0, pcm.samples().length)
                        .anyMatch(index -> pcm.samples()[index] != 0),
                "The packaged WAV must decode to non-zero PCM through the bounded pool");
        backend.destroy();
    }

    private void exerciseMasterTitleNewCompleteAndContinue(Fixture fixture, GameModule module,
                                                            NullAudioBackend backend) throws Exception {
        previousSaveRoot = System.getProperty("openggf.saveRoot");
        Path saveRoot = temp.resolve("saves");
        System.setProperty("openggf.saveRoot", saveRoot.toString());
        EngineContext previous = EngineServices.current();
        previousEngineContext = previous;
        SonicConfigurationService config = SonicConfigurationService.createStandalone(temp.resolve("config"));
        config.setConfigValue(SonicConfiguration.AUDIO_ENABLED, false);
        config.setConfigValue(SonicConfiguration.CROSS_GAME_FEATURES_ENABLED, false);
        EngineContext services = new EngineContext(config, previous.graphics(), previous.audio(),
                previous.roms(), previous.profiler(), previous.debugOverlay(), previous.playbackDebug(),
                previous.romDetection(), previous.crossGameFeatures(), previous.moduleResolutionService());
        EngineServices.configure(services);
        services.graphics().initHeadless();
        AudioManager audio = services.audio();
        audio.resetState();
        audio.setBackend(backend);
        ModSubsystem.installProcess(new ModSubsystem(fixture.catalog, new ModRuntimeFindingStore(),
                (rate, game) -> {
                    try {
                        return new SessionExternalContentView(
                                ModMusicResolver.EMPTY,
                                preparePackagedAudioPort(fixture, rate));
                    } catch (Exception failure) {
                        throw new IllegalStateException(
                                "Failed to prepare packaged standalone audio", failure);
                    }
                }, ModSubsystem.SessionAudioBoundary.audioManager(audio)));
        Engine engine = new Engine(services);
        setField(engine, "modRuntime", fixture.runtime);
        installUninitializedTitleScreen(engine);
        MasterTitleEntry.Standalone standalone = standaloneEntry(engine);
        assertFalse(standalone.continueAvailable());
        launchThroughTitleScreen(engine, MasterTitleEntry.Action.NEW_GAME);

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertTrue(SessionManager.getCurrentWorldSession().getDataSource().rom().isEmpty());
        var saveContext = SessionManager.getCurrentWorldSession().getSaveSessionContext();
        assertEquals(1, saveContext.activeSlot().orElseThrow());
        assertEquals(RUNNER, saveContext.selectedTeam().mainCharacter());
        assertTrue(saveContext.selectedTeam().sidekicks().isEmpty());
        assertEquals(0, GameServices.level().getCurrentZone());
        StreamedMusicPort.TrackRef zoneTheme =
                new StreamedMusicPort.TrackRef(OWNER, "zone-theme");
        assertTrue(audio.commandTimeline().entries().stream()
                        .anyMatch(entry -> new AudioCommand.PlayNamespacedMusic(zoneTheme)
                                .equals(entry.command())),
                "Standalone launch must record its exact namespaced level track");
        assertTrue(Files.isRegularFile(saveRoot.resolve(OWNER).resolve("slot1.json")),
                "New Game must reserve and write namespaced slot 1");

        GameServices.level().advanceToNextLevel();
        assertEquals(1, GameServices.level().getCurrentZone(),
                "Terminal completion retains the out-of-range sentinel until returning to title");
        GameLoop loop = (GameLoop) getField(engine, "gameLoop");
        loop.setInputHandler(new InputHandler());
        AtomicBoolean returnedToTitle = new AtomicBoolean();
        invoke(loop, "setReturnToMasterTitleHandler", new Class<?>[] { Runnable.class },
                (Runnable) () -> {
                    returnedToTitle.set(true);
                    headlessReturnToMasterTitle(engine);
                });
        loop.step();
        for (int i = 0; i < 100 && !returnedToTitle.get(); i++) {
            GameServices.fade().update();
        }
        assertTrue(returnedToTitle.get(), "Credits requested by the real game-loop step must finish at title");
        assertEquals(GameMode.MASTER_TITLE_SCREEN, engine.getCurrentGameMode());
        assertNull(SessionManager.getCurrentWorldSession());

        var saved = new com.openggf.game.save.SaveManager(saveRoot)
                .readSlotSummary(OWNER, 1);
        assertTrue(saved.isLoadable());
        assertEquals(RUNNER, saved.payload().get("mainCharacter"));
        assertEquals(0, ((Number) saved.payload().get("zone")).intValue());

        new com.openggf.game.save.SaveManager(saveRoot).writeSlot(OWNER, 1,
                Map.of("zone", 0, "act", 0, "mainCharacter", RUNNER,
                        "sidekicks", List.of(FRIEND)));

        MasterTitleEntry.Standalone continued = standaloneEntry(engine);
        assertTrue(continued.continueAvailable());
        invoke(engine, "exitStandaloneMasterTitle",
                new Class<?>[] { MasterTitleEntry.Launch.class },
                new MasterTitleEntry.Launch(continued, MasterTitleEntry.Action.CONTINUE));
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertEquals(0, GameServices.level().getCurrentZone());
        assertEquals(0, GameServices.level().getCurrentAct());
        assertEquals(RUNNER, SessionManager.getCurrentWorldSession().getSaveSessionContext()
                .selectedTeam().mainCharacter());
        assertEquals(List.of(FRIEND), SessionManager.getCurrentWorldSession().getSaveSessionContext()
                .selectedTeam().sidekicks());
        assertEquals(FRIEND, config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));

        SessionManager.clear();
        assertContinueHidden(fixture, services, saveRoot,
                Map.of("zone", 99, "act", 0, "mainCharacter", RUNNER, "sidekicks", List.of()),
                "A semantically invalid slot must hide Continue");
        assertContinueHidden(fixture, services, saveRoot,
                Map.of("zone", 0.5d, "act", 0, "mainCharacter", RUNNER, "sidekicks", List.of()),
                "A fractional zone must hide Continue instead of truncating");
        assertContinueHidden(fixture, services, saveRoot,
                Map.of("zone", 4_294_967_296L, "act", 0, "mainCharacter", RUNNER,
                        "sidekicks", List.of()),
                "An overflowing zone must hide Continue instead of wrapping");
        EngineServices.configure(previous);
        previousEngineContext = null;
    }

    private static void assertContinueHidden(Fixture fixture, EngineContext services, Path saveRoot,
                                             Map<String, Object> payload, String message) throws Exception {
        new com.openggf.game.save.SaveManager(saveRoot).writeSlot(OWNER, 1, payload);
        Engine corruptEngine = new Engine(services);
        setField(corruptEngine, "modRuntime", fixture.runtime);
        installUninitializedTitleScreen(corruptEngine);
        assertFalse(standaloneEntry(corruptEngine).continueAvailable(), message);
    }

    private static void launchThroughTitleScreen(Engine engine, MasterTitleEntry.Action action)
            throws Exception {
        com.openggf.game.MasterTitleScreen screen = engine.getMasterTitleScreen();
        List<MasterTitleEntry> entries = entries(screen);
        int index = java.util.stream.IntStream.range(0, entries.size())
                .filter(i -> entries.get(i) instanceof MasterTitleEntry.Standalone standalone
                        && OWNER.equals(standalone.owner()))
                .findFirst().orElseThrow();
        screen.setSelectedIndexForTest(index);
        InputHandler input = new InputHandler();
        GameLoop loop = (GameLoop) getField(engine, "gameLoop");
        loop.setInputHandler(input);

        pressTitleKey(loop, input, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER); // Enter action pane.
        assertFalse(screen.isGameSelected(), "Opening actions must not launch the standalone");
        pressTitleKey(loop, input, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER); // Start opens New Game/Continue.
        assertFalse(screen.isGameSelected(), "Opening the standalone chooser must not launch yet");
        if (action == MasterTitleEntry.Action.CONTINUE) {
            pressTitleKey(loop, input,
                    EngineServices.current().configuration().getInt(SonicConfiguration.DOWN));
        }
        pressTitleKey(loop, input, org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER);
        assertTrue(screen.isGameSelected(), "The real title chooser must publish a launch");
        assertEquals(action, screen.getSelectedLaunch().action());

        for (int i = 0; i < 100 && engine.getCurrentGameMode() == GameMode.MASTER_TITLE_SCREEN; i++) {
            EngineServices.current().graphics().getFadeManager().update();
        }
        for (int i = 0; i < 100 && engine.getCurrentGameMode() == GameMode.LEVEL
                && GameServices.fade().isActive(); i++) {
            GameServices.fade().update();
        }
    }

    private static void pressTitleKey(GameLoop loop, InputHandler input, int key) {
        input.handleKeyEvent(key, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        loop.step();
        input.handleKeyEvent(key, org.lwjgl.glfw.GLFW.GLFW_RELEASE);
        loop.step();
    }

    private Path buildSample() throws Exception {
        Path engine = temp.resolve("engine-local.jar");
        Path sdk = temp.resolve("openggf-mod-sdk-local.jar");
        Path sessionClasses = TestSessionOutputPaths.compiledClasses();
        createJar(sessionClasses, engine, entry -> !entry.startsWith("com/openggf/tools/modsdk/")
                && !entry.startsWith("META-INF/openggf-mod-sdk/"));
        createJar(sessionClasses, sdk, entry -> entry.startsWith("com/openggf/tools/modsdk/")
                || entry.startsWith("META-INF/openggf-mod-sdk/"));
        Path source = Path.of("src/test/resources/mods/sample-standalone-src").toAbsolutePath();
        Path project = temp.resolve("sample-standalone-project");
        List<String> command = System.getProperty("os.name", "").startsWith("Windows")
                ? List.of("powershell.exe", "-NoProfile", "-File", source.resolve("build.ps1").toString(),
                engine.toString(), sdk.toString(), project.toString())
                : List.of("sh", source.resolve("build.sh").toString(), engine.toString(), sdk.toString(),
                project.toString());
        runProcess(command);
        Path jar = project.resolve("target/phase3-standalone-mod.jar");
        assertTrue(Files.isRegularFile(jar));
        return jar;
    }

    private Fixture load(Path jar) throws Exception {
        Path repo = temp.resolve("repo");
        Files.createDirectories(repo);
        Path packed = repo.resolve(jar.getFileName());
        Files.copy(jar, packed);
        var scanned = new DefaultModRepositoryScanner().scan(repo.toAbsolutePath().normalize());
        var validated = new ModCatalogValidator(repo.toAbsolutePath().normalize(),
                ModInputLimits.production(), (game, id) -> true).validate(scanned);
        ModDescriptor descriptor = (ModDescriptor) validated.entries().getFirst();
        assertFalse(descriptor.hasErrors(), descriptor.findings()::toString);
        ModState state = new ModState(1, List.of(new ModState.Entry(OWNER, true, 0, true,
                descriptor.sha256())));
        ModCatalog catalog = new EffectiveCatalogBuilder().build(validated.entries(), state);
        ModRuntime runtime = new ModClassLoaderFactory(getClass().getClassLoader())
                .create(catalog.effective(), Set.of(OWNER));
        runtime.installFaultBoundary(new ModFaultBoundary(Map.of(), new ModRuntimeFindingStore(),
                owners -> new ModStateSaveResult.Saved(), owners -> { }));
        return new Fixture(runtime, descriptor, catalog);
    }

    private static void runProcess(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private static void createJar(Path root, Path jar, Predicate<String> include) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             var paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
                String entry = root.relativize(file).toString().replace('\\', '/');
                if (!include.test(entry)) continue;
                out.putNextEntry(new JarEntry(entry));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
    }

    private static Object getField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }

    @SuppressWarnings("unchecked")
    private static List<MasterTitleEntry> entries(com.openggf.game.MasterTitleScreen screen)
            throws Exception {
        var method = screen.getClass().getDeclaredMethod("entriesForTest");
        method.setAccessible(true);
        return (List<MasterTitleEntry>) method.invoke(screen);
    }

    private static MasterTitleEntry.Standalone standaloneEntry(Engine engine) throws Exception {
        return entries(engine.getMasterTitleScreen()).stream()
                .filter(MasterTitleEntry.Standalone.class::isInstance)
                .map(MasterTitleEntry.Standalone.class::cast)
                .filter(entry -> OWNER.equals(entry.owner())).findFirst().orElseThrow();
    }

    private static void installUninitializedTitleScreen(Engine engine) throws Exception {
        Object screen = invoke(engine, "createMasterTitleScreen", new Class<?>[0]);
        setField(engine, "masterTitleScreen", screen);
        setField(screen, "state", com.openggf.game.MasterTitleScreen.State.ACTIVE);
        ((GameLoop) getField(engine, "gameLoop")).setGameMode(GameMode.MASTER_TITLE_SCREEN);
    }

    private static void headlessReturnToMasterTitle(Engine engine) {
        try {
            invoke(engine, "resetForGameplayFromMasterTitle", new Class<?>[0]);
            GameLoop loop = (GameLoop) getField(engine, "gameLoop");
            loop.setGameplayMode(null);
            setField(engine, "gameplayMode", null);
            installUninitializedTitleScreen(engine);
        } catch (Exception failure) {
            throw new AssertionError("Headless master-title return failed", failure);
        }
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }

    private static Object invoke(Object target, String name, Class<?>[] types, Object... args) throws Exception {
        var method = target.getClass().getDeclaredMethod(name, types); method.setAccessible(true);
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException failure) { throw (Exception) failure.getCause(); }
    }

    private record Fixture(ModRuntime runtime, ModDescriptor descriptor, ModCatalog catalog)
            implements AutoCloseable {
        @Override public void close() throws Exception { runtime.close(); }
    }

    private static final class PcmPoolBackend extends AbstractSmpsAudioBackend {

        private PcmPoolBackend() {
            super(SonicConfigurationService.createStandalone(), null);
        }

        @Override protected int getDeviceSampleRate() { return 8_000; }
        @Override protected void hookInitDevice() { }
        @Override protected void hookDestroyDevice() { }
        @Override protected void hookStartStream() { }
        @Override protected void hookStopStreamSource() { }
        @Override protected void hookUpdateStream() { }
        @Override protected void hookStopAndClearMusicSource() { }
        @Override protected void hookStopAndUnqueueAllMusicBuffers() { }
        @Override protected void hookStopAndClearAllMusicBuffers() { }
        @Override protected void hookRestartStreamIfDry() { }
        @Override protected void hookPlayWavSfx(String name, float pitch) { }
        @Override protected void hookStopAndDeleteWavSfxSources() { }
        @Override protected void hookCleanupStoppedWavSfx() { }
        @Override protected void hookPause() { }
        @Override protected void hookResume() { }
    }
}
