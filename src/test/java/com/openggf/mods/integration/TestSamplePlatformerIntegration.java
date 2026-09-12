package com.openggf.mods.integration;

import com.openggf.Engine;
import com.openggf.GameLoop;
import com.openggf.ModSubsystem;
import com.openggf.SessionExternalContentView;
import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.StreamedMusicPort;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.presentation.AudioPresentationCommand.ReplaceMusic;
import com.openggf.audio.presentation.AudioPresentationMixer;
import com.openggf.audio.presentation.AudioPresentationSourceFactory;
import com.openggf.audio.presentation.AudioVoiceRegistry;
import com.openggf.audio.presentation.StreamedMusicVoice;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
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
import com.openggf.game.ObjectArtProvider;
import com.openggf.game.PhysicsProfile;
import com.openggf.game.PlayableEntity;
import com.openggf.io.ModInputLimits;
import com.openggf.level.Level;
import com.openggf.level.ModLevel;
import com.openggf.level.objects.*;
import com.openggf.mods.*;
import com.openggf.mods.code.ModClassLoaderFactory;
import com.openggf.mods.code.ModFaultBoundary;
import com.openggf.mods.code.ModRuntime;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestSessionOutputPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.function.Predicate;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Structural clone of {@link TestPhase3StandaloneSampleIntegration} for the {@code sample-platformer}
 * gallery mod: real packaged jar, no-ROM boot, character/level/audio-manifest/terminal-topology
 * assertions, and the full master-title New Game -> save -> complete -> credits -> Continue
 * restoration flow plus corrupt-slot Continue-hiding cases.
 *
 * <p>Unlike {@code phase3-standalone} (which registers a second {@code friend} character and
 * {@code supportsSidekick() == true}), {@code sample-platformer} registers only {@code bolt} and
 * {@code PlatformerModule#supportsSidekick()} is {@code false} (see
 * {@code GameplayTeamBootstrap#registerActiveTeam}, which drops configured sidekick names entirely
 * when the module does not support them). The Continue-restoration flow below is adapted to always
 * carry an empty sidekick list rather than reusing phase-3's {@code FRIEND} second-character path.
 *
 * <p>Gameplay-level assertions (badnik/spring behavior) are deferred to later tasks: {@code ZapBug}
 * and {@code SpringPad} are still inert-body stubs at this point in the plan.
 */
@Isolated
class TestSamplePlatformerIntegration {
    private static final String OWNER = "sample-platformer";
    private static final String RUNNER = OWNER + ":bolt";
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
    void realPackagedPlatformerLoadsWithoutRomAndExercisesCharacterLevelAudioAndTerminalTopology()
            throws Exception {
        Path jar = buildSample();
        try (Fixture fixture = load(jar)) {
            GameModule module = fixture.runtime.prepareStandaloneModule(OWNER).orElseThrow();
            assertEquals(OWNER, module.getIdentifier());
            assertEquals(com.openggf.game.GameId.STANDALONE, module.getGameId());
            assertEquals(1, module.getZoneRegistry().getZoneCount());
            assertFalse(module.supportsSidekick());
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
            var acceptanceLevel = game.loadLevel(0x400);
            assertInstanceOf(ModLevel.class, acceptanceLevel);
            List<String> placedObjectKeys = acceptanceLevel.getObjects().stream()
                    .map(ObjectSpawn::objectKey).toList();
            assertTrue(placedObjectKeys.contains("sample-platformer:zapbug"),
                    "Loaded level must place the namespaced zapbug gimmick spawn");
            assertTrue(placedObjectKeys.contains("sample-platformer:springpad"),
                    "Loaded level must place the namespaced springpad gimmick spawn");
            assertEquals(0x400, module.getZoneRegistry().getLevelDataForZone(0).getFirst().levelIndex(),
                    "Every declared descriptor must route through ModGame.loadLevel by its exact index");
            // game.loadLevel routes through OwnerAwareStandaloneModule's fault boundary, which wraps
            // the mod's raw IOException into a CallbackAborted runtime failure.
            assertThrows(com.openggf.mods.code.ModFaultBoundary.CallbackAborted.class,
                    () -> game.loadLevel(0x401),
                    "Any level index other than the one declared descriptor must throw");
            assertEquals(new MusicReference.Namespaced(OWNER, "zone-theme"),
                    module.getLevelMusicReference(0, 0));

            CharacterKey runnerKey = CharacterKey.parsePersisted(RUNNER);
            var definition = module.getPlayableCharacterRegistry().find(runnerKey).orElseThrow();
            assertEquals(com.openggf.sprites.playable.SecondaryAbility.NONE,
                    definition.secondaryAbility());
            assertFalse(definition.supportsSuperForm());
            assertFalse(definition.artSupplier().load(RUNNER).mappingFrames().isEmpty());
            assertNotNull(definition.paletteSupplier());
            assertEquals(0x480, module.getPhysicsProvider().getProfile(RUNNER).max());
            assertEquals(0x780, module.getPhysicsProvider().getProfile(RUNNER).jump());
            assertNotEquals(PhysicsProfile.SONIC_2_SONIC,
                    module.getPhysicsProvider().getProfile(RUNNER));

            GameModuleRegistry.setCurrent(module);
            AbstractPlayableSprite player = definition.spriteFactory().create(RUNNER, 32, 96);
            assertEquals(runnerKey, player.characterKey());
            assertEquals(0x480, player.getPhysicsProfile().max(),
                    "The sample's literal profile must drive the constructed player");
            assertEquals(0x780, player.getPhysicsProfile().jump());
            exerciseDoubleJumpAndRewindLatch(player);

            NullAudioBackend backend = new NullAudioBackend();
            AudioManager audio = AudioManager.getInstance();
            audio.resetState();
            audio.setBackend(backend);

            ModAudioManifest audioManifest;
            try (JarFile packed = new JarFile(jar.toFile())) {
                byte[] yaml = packed.getInputStream(packed.getJarEntry("audio/audio-manifest.yaml"))
                        .readAllBytes();
                audioManifest = new ModAudioManifestParser(OWNER).parse(yaml);
                assertNotNull(packed.getJarEntry("art/bolt.ggfp"));
                assertNotNull(packed.getJarEntry("art/zapbug.ggfs"));
                assertNotNull(packed.getJarEntry("art/springpad.ggfs"));
                assertNotNull(packed.getJarEntry("art/ring.ggfs"));
                assertNotNull(packed.getJarEntry("levels/act1/level.json"));
            }
            assertEquals(List.of(new TrackKey(OWNER, "zone-theme")),
                    audioManifest.tracks().stream().map(ModAudioTrack::key).toList());
            assertEquals(List.of(new SfxKey(OWNER, "jump2"), new SfxKey(OWNER, "hit"),
                            new SfxKey(OWNER, "spring")),
                    audioManifest.sfx().stream().map(ModAudioSfx::key).toList());
            assertAllPackagedAudioAssetsTraverseBoundedPcmPool(fixture, audioManifest);

            exerciseMasterTitleNewCompleteAndContinue(fixture, module, backend);
        }
    }

    /**
     * Exercises {@code ZapBug} and {@code SpringPad} directly against a hand-built
     * {@code ObjectManager}/{@code StubObjectServices} pair -- mirroring
     * {@code TestPhase2SampleModIntegration}'s {@code AbstractBadnikInstance} unit-test
     * pattern -- rather than driving a full live gameplay session. Standalone modules no
     * longer need to hand-roll an {@code ObjectArtProvider}: the engine-owned
     * {@code OwnerAwareStandaloneModule} proxy that every standalone module is wrapped in
     * decorates whatever the delegate's {@code getObjectArtProvider()} returns with the
     * {@code registerObjectArt} sheets prepared from the mod's manifest (falling back to an
     * empty base provider when the delegate itself returns {@code null}). {@code PlatformerModule}
     * therefore declares no {@code getObjectArtProvider()} override at all -- asserted below via
     * reflection on the unwrapped delegate so this fixture can never silently regress back to
     * hand-rolling. The renderer-resolution assertions below go through the proxy-decorated
     * {@code module.getObjectArtProvider()}, and the same provider backs the
     * {@link ObjectRenderManager} wired into {@code StubObjectServices} so
     * {@code appendRenderCommands} exercises the real {@code getRenderer(...)} call.
     */
    @Test
    void zapBugPatrolsSpringPadLaunchesAndBothRecreateForRewind() throws Exception {
        Path jar = buildSample();
        try (Fixture fixture = load(jar)) {
            GameModule module = fixture.runtime.prepareStandaloneModule(OWNER).orElseThrow();
            Object boundaryHandler = java.lang.reflect.Proxy.getInvocationHandler(module);
            Object delegate = getField(boundaryHandler, "delegate");
            assertEquals("example.platformer.PlatformerModule", delegate.getClass().getName());
            assertThrows(NoSuchMethodException.class,
                    () -> delegate.getClass().getDeclaredMethod("getObjectArtProvider"),
                    "PlatformerModule must not declare its own getObjectArtProvider() -- object "
                    + "art must flow through the engine's registerObjectArt overlay decoration");
            var assets = fixture.runtime.standaloneAssetSnapshot(OWNER);
            GameDataSource source = new GameDataSource() {
                @Override public Optional<com.openggf.data.Rom> rom() { return Optional.empty(); }
                @Override public java.io.InputStream openAsset(String path) throws java.io.IOException {
                    return new ByteArrayInputStream(assets.readBounded(path, assets.limits().maxAssetBytes()));
                }
                @Override public String identity() { return "standalone:" + OWNER + ":gameplay"; }
            };
            var game = module.createGame(source);
            Level level = game.loadLevel(0x400);
            ObjectSpawn zapSpawn = level.getObjects().stream()
                    .filter(spawn -> "sample-platformer:zapbug".equals(spawn.objectKey()))
                    .findFirst().orElseThrow();
            ObjectSpawn springSpawn = level.getObjects().stream()
                    .filter(spawn -> "sample-platformer:springpad".equals(spawn.objectKey()))
                    .findFirst().orElseThrow();

            ObjectArtProvider artProvider = module.getObjectArtProvider();
            assertNotNull(artProvider,
                    "The engine's OwnerAwareStandaloneModule proxy must decorate the module with "
                    + "the registerObjectArt sheets even though PlatformerModule serves no "
                    + "provider of its own");
            assertTrue(artProvider.getRendererKeys().containsAll(
                    List.of("sample-platformer:zapbug", "sample-platformer:springpad")),
                    "the registerObjectArt-decorated provider must serve both namespaced keys");
            assertNotNull(artProvider.getRenderer("sample-platformer:zapbug"),
                    "zapbug renderer must resolve through the registerObjectArt-decorated provider");
            assertNotNull(artProvider.getRenderer("sample-platformer:springpad"),
                    "springpad renderer must resolve through the registerObjectArt-decorated provider");
            ObjectRenderManager renderManager = new ObjectRenderManager(artProvider);
            assertNotNull(renderManager.getRenderer("sample-platformer:zapbug"),
                    "zapbug renderer must resolve through the shipped module art provider");
            assertNotNull(renderManager.getRenderer("sample-platformer:springpad"),
                    "springpad renderer must resolve through the shipped module art provider");

            NullAudioBackend backend = new NullAudioBackend();
            AudioManager audio = AudioManager.getInstance();
            audio.resetState();
            audio.setBackend(backend);
            audio.installStreamedMusicPort(
                    preparePackagedAudioPort(fixture, audio.outputSampleRate()));

            ObjectRegistry registry = module.createObjectRegistry();
            ObjectManager[] holder = new ObjectManager[1];
            PlayableEntity[] mainPlayer = new PlayableEntity[1];
            StubObjectServices services = new StubObjectServices() {
                @Override public ObjectManager objectManager() { return holder[0]; }
                @Override public ObjectRenderManager renderManager() { return renderManager; }
                @Override public AudioManager audioManager() { return audio; }
            };
            services.withPlayerQuery(new ObjectPlayerQuery(() -> mainPlayer[0], List::of));
            holder[0] = new ObjectManager(List.of(), registry, 0, null, null, null, null, services);

            // --- ZapBug: patrol via PatrolMovementHelper, reverse at its bounds, render, rewind ---
            AbstractBadnikInstance zapBug = (AbstractBadnikInstance) holder[0].createDynamicObject(
                    () -> registry.create(zapSpawn));
            int startX = zapBug.getX();
            zapBug.update(1, null);
            assertNotEquals(startX, zapBug.getX(), "ZapBug must move on every patrol frame");

            int previousX = zapBug.getX();
            int direction = Integer.signum(previousX - startX);
            boolean reversed = false;
            for (int frame = 2; frame < 60 && !reversed; frame++) {
                zapBug.update(frame, null);
                int x = zapBug.getX();
                int step = Integer.signum(x - previousX);
                if (step != 0 && step != direction) {
                    reversed = true;
                } else if (step != 0) {
                    direction = step;
                }
                previousX = x;
            }
            assertTrue(reversed, "ZapBug must reverse direction once it reaches a patrol bound");
            assertDoesNotThrow(() -> zapBug.appendRenderCommands(new ArrayList<>()),
                    "ZapBug must render through the resolved zapbug PatternSpriteRenderer without throwing");

            // 2-frame walk animation: a FRESH instance (deterministic animTick=0) must hold
            // frame 0 through the first 15 updates, show frame 1 after the 16th (one
            // ANIM_PERIOD), and toggle back to frame 0 after the 32nd (a full cycle).
            AbstractBadnikInstance animBug = (AbstractBadnikInstance) holder[0].createDynamicObject(
                    () -> registry.create(zapSpawn));
            assertEquals(0, badnikAnimFrame(animBug), "ZapBug must start on animation frame 0");
            for (int frame = 1; frame <= 15; frame++) {
                animBug.update(frame, null);
                assertEquals(0, badnikAnimFrame(animBug),
                        "ZapBug must hold frame 0 for the full 16-update animation period (update " + frame + ")");
            }
            animBug.update(16, null);
            assertEquals(1, badnikAnimFrame(animBug),
                    "ZapBug must switch to frame 1 after one full animation period");
            for (int frame = 17; frame <= 32; frame++) {
                animBug.update(frame, null);
            }
            assertEquals(0, badnikAnimFrame(animBug),
                    "ZapBug must toggle back to frame 0 after a full 2-frame cycle");

            AbstractObjectInstance recreatedZapBug = ((RewindRecreatable) zapBug)
                    .recreateForRewind(new RewindRecreateContext(zapSpawn, null, services));
            assertNotSame(zapBug, recreatedZapBug, "recreateForRewind must build a fresh instance");
            assertEquals(zapBug.getClass(), recreatedZapBug.getClass(),
                    "recreateForRewind must return the same concrete class");

            // --- SpringPad: proximity + velocity launch, namespaced SFX, extended frame, rewind ---
            AbstractObjectInstance springPad = (AbstractObjectInstance) holder[0].createDynamicObject(
                    () -> registry.create(springSpawn));
            var definition = module.getPlayableCharacterRegistry()
                    .find(CharacterKey.parsePersisted(RUNNER)).orElseThrow();
            GameModuleRegistry.setCurrent(module);
            AbstractPlayableSprite faller = definition.spriteFactory().create(RUNNER, 0, 0);
            // The constructor takes top-left xPixel/yPixel, not the ROM centre -- use the
            // centre-preserving setters so getCentreX()/getCentreY() land exactly on the
            // spring's spawn point regardless of the player's half-width/half-height.
            faller.setCentreX((short) springSpawn.x());
            faller.setCentreYPreserveSubpixel((short) springSpawn.y());
            faller.setYSpeed((short) 0x100); // falling (positive = downward)
            mainPlayer[0] = faller;

            springPad.update(1, faller);
            assertEquals((short) SpringBounceHelper.STRENGTH_YELLOW, faller.getYSpeed(),
                    "SpringPad must apply the yellow spring strength verbatim (already negative = upward)");
            StreamedMusicPort.SfxRef springSfx =
                    new StreamedMusicPort.SfxRef(OWNER, "spring");
            assertTrue(audio.commandTimeline().entries().stream()
                            .anyMatch(entry -> new AudioCommand.PlayNamespacedSfx(springSfx)
                                    .equals(entry.command())),
                    "SpringPad must record its exact namespaced spring SFX on contact");
            assertDoesNotThrow(() -> springPad.appendRenderCommands(new ArrayList<>()),
                    "SpringPad must render through the resolved springpad PatternSpriteRenderer without throwing");

            // Extended pose: the contact frame shows the extended sprite, it persists for the
            // next 7 update() calls (the launched player's yspeed is now negative, so no
            // re-trigger), and the 8th post-contact update reverts to the idle frame --
            // exactly 8 rendered extended frames in total.
            assertTrue(springPadExtended(springPad),
                    "SpringPad must show the extended frame on the contact frame");
            for (int frame = 2; frame <= 8; frame++) {
                springPad.update(frame, faller);
                assertTrue(springPadExtended(springPad),
                        "SpringPad must stay extended through post-contact update " + frame);
            }
            springPad.update(9, faller);
            assertFalse(springPadExtended(springPad),
                    "SpringPad must revert to the idle frame after 8 extended updates");

            AbstractObjectInstance recreatedSpringPad = ((RewindRecreatable) springPad)
                    .recreateForRewind(new RewindRecreateContext(springSpawn, null, services));
            assertNotSame(springPad, recreatedSpringPad, "recreateForRewind must build a fresh instance");
            assertEquals(springPad.getClass(), recreatedSpringPad.getClass(),
                    "recreateForRewind must return the same concrete class");
        }
    }

    /** Reads the inherited protected {@code animFrame} animation cursor off a badnik instance. */
    private static int badnikAnimFrame(AbstractBadnikInstance value) throws Exception {
        Field field = AbstractBadnikInstance.class.getDeclaredField("animFrame");
        field.setAccessible(true);
        return field.getInt(value);
    }

    /**
     * Reads {@code SpringPad}'s private {@code extendedFramesRemaining} counter (via the mod
     * classloader's concrete class) -- {@code > 0} means the extended sprite frame is shown.
     */
    private static boolean springPadExtended(AbstractObjectInstance springPad) throws Exception {
        Field field = springPad.getClass().getDeclaredField("extendedFramesRemaining");
        field.setAccessible(true);
        return field.getInt(springPad) > 0;
    }

    /**
     * Exercises {@code BoltCharacter}'s double-jump secondary ability in isolation, without a
     * live gameplay session: {@code onAbilityActivate} fires once per airborne stretch, is
     * latched against a second mid-air press, and re-arms after the {@code draw()}-based
     * landing-reset seam runs. Air-state transitions use direct field reflection (mirroring
     * the engine's own {@code TestablePlayableSprite#setAirForTest}) rather than the public
     * {@code setAir(boolean)} setter, because the landing branch of {@code setAir} calls
     * {@code currentGameState().resetItemBonus()}, which requires an active
     * {@code GameplayModeContext} that this construction-only slice of the test does not set up.
     *
     * <p>The rewind assertion now drives the real production round-trip -- {@code BoltCharacter}
     * uses Mod API 0.7's {@code captureSubclassRewindState()} /
     * {@code restoreSubclassRewindState(...)} hooks (see {@code BoltCharacter}), so
     * {@link AbstractPlayableSprite#captureRewindState()} now packs the armed latch into a
     * mod-declared {@link PerObjectRewindSnapshot.PlayableSubclassRewindExtra} payload, and
     * {@link AbstractPlayableSprite#restoreRewindState(PerObjectRewindSnapshot)} unpacks it back
     * onto the live field just like any other engine-owned scalar -- no test-level
     * {@code GenericFieldCapturer} scaffold is needed any more. The live latch is cleared through
     * the real landing-reset seam ({@code draw()}) between capture and restore so the restore
     * assertion can only pass if the value actually round-tripped through the snapshot, and a
     * final behavioral assertion confirms the restored latch still gates a fresh mid-air press
     * rather than merely matching on the reflected field.
     */
    private void exerciseDoubleJumpAndRewindLatch(AbstractPlayableSprite player) throws Exception {
        setAirField(player, true);
        assertTrue(invokeAbilityActivate(player), "First mid-air press must fire the double jump");
        assertEquals((short) -0x600, player.getYSpeed(), "Double jump must apply the ROM impulse");

        player.setYSpeed((short) 0x0111);
        assertFalse(invokeAbilityActivate(player), "A second mid-air press before landing must be latched");
        assertEquals((short) 0x0111, player.getYSpeed(), "A latched press must not re-apply the impulse");

        Field doubleJumpUsedField = player.getClass().getDeclaredField("doubleJumpUsed");
        doubleJumpUsedField.setAccessible(true);
        assertEquals(true, doubleJumpUsedField.get(player), "Sanity: latch must be set after the ability just fired");

        // Capture a keyframe while the latch is armed -- the same production write path every
        // gameplay rewind keyframe goes through.
        PerObjectRewindSnapshot snapshot = player.captureRewindState();
        PerObjectRewindSnapshot.PlayableSubclassRewindExtra subclassExtra =
                snapshot.playerExtra().subclassExtra();
        assertNotNull(subclassExtra,
                "capture must produce a subclass payload for a character overriding captureSubclassRewindState()");
        assertEquals("BoltRewindExtra", subclassExtra.getClass().getSimpleName(),
                "the captured payload must be Bolt's own mod-declared record, resolved through the mod "
                + "classloader (the concrete type cannot be referenced directly from this engine-side test)");

        // Clear the live latch through the real landing-reset seam so the restore assertion
        // below can only pass if the value actually round-tripped through the snapshot rather
        // than observing incidental unchanged live state.
        setAirField(player, false);
        player.draw();
        assertEquals(false, doubleJumpUsedField.get(player),
                "Sanity: the landing-reset seam must have cleared the live latch before restore");

        player.restoreRewindState(snapshot);
        assertEquals(true, doubleJumpUsedField.get(player),
                "Rewind restore must bring the double-jump latch back rather than leaving the stale live value");

        // Behavioral assertion: the restored latch must actually gate gameplay, not just the
        // reflected field -- a fresh mid-air press right after restore must still be denied.
        setAirField(player, true);
        assertFalse(invokeAbilityActivate(player),
                "After restore the ability must remain latched, matching the captured mid-jump state");
    }

    private static void setAirField(AbstractPlayableSprite player, boolean value) throws Exception {
        Field field = AbstractPlayableSprite.class.getDeclaredField("air");
        field.setAccessible(true);
        field.setBoolean(player, value);
    }

    private static boolean invokeAbilityActivate(AbstractPlayableSprite player) throws Exception {
        Method method = AbstractPlayableSprite.class.getDeclaredMethod("dispatchAbilityActivate",
                boolean.class, boolean.class, boolean.class, boolean.class);
        method.setAccessible(true);
        return (boolean) method.invoke(player, false, false, false, false);
    }

    /**
     * Mirrors {@code TestPhase3StandaloneSampleIntegration#assertPackagedSfxTraversesBoundedPcmPool}
     * but exercises all four packaged audio assets (the {@code zone-theme} OGG streamed track plus
     * the {@code jump2}/{@code hit}/{@code spring} WAV one-shots), each through its own freshly
     * prepared session so no asset's playback state can leak into another's PCM check.
     */
    private void assertAllPackagedAudioAssetsTraverseBoundedPcmPool(Fixture fixture,
                                                                     ModAudioManifest manifest) {
        ModTrackRegistry tracks = new ModTrackRegistry(manifest.tracks());
        ModSfxRegistry sfx = new ModSfxRegistry(manifest.sfx());
        assertAssetDecodesNonZeroPcm(fixture, tracks, sfx, true, "zone-theme");
        for (String name : List.of("jump2", "hit", "spring")) {
            assertAssetDecodesNonZeroPcm(fixture, tracks, sfx, false, name);
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

    /**
     * Asserts a packaged asset decodes to non-zero PCM through the real streamed path.
     *
     * <p>Mixing no longer happens inside the audio backend: `develop` moved it into the
     * presentation layer, so there is no {@code hookUploadStreamBuffer} to observe. A track is
     * therefore driven as a {@link StreamedMusicVoice} through {@link AudioPresentationMixer},
     * which is the same code the running engine mixes it with. A one-shot is materialized by the
     * presentation layer from the recorded command rather than streamed through the port, so its
     * prepared PCM is checked at {@link StreamedMusicPort#sfxPcm} — the point the creator asset
     * actually becomes samples.
     */
    private void assertAssetDecodesNonZeroPcm(Fixture fixture, ModTrackRegistry tracks,
                                              ModSfxRegistry sfx, boolean isTrack, String name) {
        ModAudioPreparer preparer = new ModAudioPreparer(
                fixture.descriptor.jarPath().getParent().toAbsolutePath().normalize(),
                ModInputLimits.production(), new ModRuntimeFindingStore(),
                owners -> new ModStateSaveResult.Saved());
        PreparedAudioSession session = preparer.prepare(
                fixture.catalog.effective(), tracks, sfx, 8_000);
        PreparedModMusic music = PreparedModMusic.build(fixture.catalog.effective(), tracks, sfx,
                session, 8_000, OWNER);
        try (com.openggf.ModStreamedMusicPort port = new com.openggf.ModStreamedMusicPort(
                music, new StreamedMusicPlayer(8_000), OWNER)) {
            if (isTrack) {
                StreamedMusicPort.TrackRef track = new StreamedMusicPort.TrackRef(OWNER, name);
                assertTrue(port.hasTrack(track), name + " track must resolve through the port");
                int frames = 1_024;
                SmpsCoordFlagHandlerOwner handlers = new SmpsCoordFlagHandlerOwner(
                        new SmpsCoordFlagRuntimeState());
                AudioPresentationSourceFactory sourceFactory =
                        new AudioPresentationSourceFactory(() -> true, handlers);
                sourceFactory.installStreamedMusicPort(port);
                try {
                    AudioVoiceRegistry voices = new AudioVoiceRegistry(
                            sourceFactory, sourceFactory, handlers, ignored -> { });
                    voices.apply(new ReplaceMusic(sourceFactory.streamedTrack(1L, track)));
                    AudioPresentationMixer mixer = new AudioPresentationMixer(frames);

                    // A compressed track can open on silence, so mix until it speaks rather than
                    // asserting the first block; the bound keeps a genuinely silent asset failing.
                    boolean sounded = false;
                    for (int block = 0; block < 32 && !sounded; block++) {
                        short[] output = mixer.mix(voices, frames);
                        for (short sample : output) {
                            if (sample != 0) {
                                sounded = true;
                                break;
                            }
                        }
                    }
                    assertTrue(sounded,
                            "The packaged " + name + " track must decode and mix non-zero PCM");
                } finally {
                    sourceFactory.retireStreamedMusicPort();
                }
            } else {
                StreamedMusicPort.SfxRef ref = new StreamedMusicPort.SfxRef(OWNER, name);
                assertTrue(port.hasSfx(ref), name + " sfx must resolve through the port");
                StreamedMusicPort.SfxPcm pcm = port.sfxPcm(ref).orElseThrow(() ->
                        new AssertionError(name + " sfx must expose prepared PCM"));
                assertTrue(pcm.samples().length > 0, name + " sfx must prepare a non-empty buffer");
                assertTrue(java.util.stream.IntStream.range(0, pcm.samples().length)
                                .anyMatch(index -> pcm.samples()[index] != 0),
                        "The packaged " + name + " asset must decode to non-zero PCM");
            }
        }
    }

    /**
     * Adapted from {@code TestPhase3StandaloneSampleIntegration#exerciseMasterTitleNewCompleteAndContinue}.
     * {@code sample-platformer} has no second playable character and
     * {@code PlatformerModule#supportsSidekick()} is {@code false}, so every {@code SelectedTeam}
     * here (New Game default, written Continue payloads, and corrupt-slot payloads) carries an
     * empty sidekick list instead of phase-3's {@code FRIEND} second character.
     */
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
                                "Failed to prepare packaged sample audio", failure);
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
                        "sidekicks", List.of()));

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
        assertTrue(SessionManager.getCurrentWorldSession().getSaveSessionContext()
                .selectedTeam().sidekicks().isEmpty());
        assertEquals("", config.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE));

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

        pressTitleKey(loop, input, org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN); // Enter action pane.
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
        Path source = Path.of("src/test/resources/mods/sample-platformer-src").toAbsolutePath();
        Path project = temp.resolve("sample-platformer-project");
        List<String> command = System.getProperty("os.name", "").startsWith("Windows")
                ? List.of("powershell.exe", "-NoProfile", "-File", source.resolve("build.ps1").toString(),
                engine.toString(), sdk.toString(), project.toString())
                : List.of("sh", source.resolve("build.sh").toString(), engine.toString(), sdk.toString(),
                project.toString());
        runProcess(command);
        Path jar = project.resolve("target/sample-platformer-mod.jar");
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

}
