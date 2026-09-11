package com.openggf.sprites.playable;

import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.EngineServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.openggf.audio.AudioManager;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.game.GameRng;
import com.openggf.game.rules.GameRules;
import com.openggf.game.sonic1.audio.Sonic1AudioProfile;
import com.openggf.game.sonic1.audio.Sonic1Music;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameServices;
import com.openggf.game.rules.DrowningBubbleRules;
import com.openggf.game.rules.GameRules;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDrowningControllerMusicSelection {

    @AfterEach
    void tearDown() {
        if (openedRom != null) {
            openedRom.close();
            openedRom = null;
        }
        AudioManager audioManager = AudioManager.getInstance();
        audioManager.setBackend(new NullAudioBackend());
        audioManager.resetState();
        SessionManager.clear();
    }

    static Stream<Arguments> drowningMusicProvider() {
        return Stream.of(
                Arguments.of(new Sonic1AudioProfile(), Sonic1Music.DROWNING.id, "Sonic 1"),
                Arguments.of(new Sonic2AudioProfile(), Sonic2Music.UNDERWATER.id, "Sonic 2"),
                Arguments.of(new Sonic3kAudioProfile(), Sonic3kMusic.DROWNING.id, "Sonic 3K")
        );
    }

    private com.openggf.data.Rom openedRom;

    /**
     * Sonic 2 resolves a request against its ROM-backed sample before the
     * driver publishes it, and rejects the whole request when no ROM is
     * installed, so this parameter needs the real ROM to reach the request at
     * all. The other two profiles publish at ingress and need nothing.
     */
    private void installRomIfRequired(GameAudioProfile profile,
            AudioManager audioManager) {
        if (!(profile instanceof Sonic2AudioProfile)) {
            return;
        }
        java.io.File romFile = com.openggf.tests.RomTestUtils
                .ensureSonic2RomAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(romFile != null,
                "Sonic 2 REV01 ROM is required to resolve an S2 request");
        openedRom = new com.openggf.data.Rom();
        assertTrue(openedRom.open(romFile.getAbsolutePath()));
        audioManager.setRom(openedRom);
    }

    @ParameterizedTest(name = "{2} drowning music")
    @MethodSource("drowningMusicProvider")
    void drowningMusicMatchesProfile(GameAudioProfile profile, int expectedMusicId, String label) {
        AudioManager audioManager = AudioManager.getInstance();
        installRomIfRequired(profile, audioManager);
        audioManager.setAudioProfile(profile);
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        LevelManager levelManager = GameServices.level();
        levelManager.resetState();

        DrowningController controller = new DrowningController(new Sonic("test", (short) 0, (short) 0));

        // Air starts at 30 and drowning music triggers when air event runs at exactly 12.
        // That is 19 air events: 30..12 inclusive.
        int updatesToTriggerMusic = (30 - 12 + 1) * 60;
        for (int i = 0; i < updatesToTriggerMusic; i++) {
            controller.update();
        }
        // Sonic 2 writes requests to the driver mailbox at ingress and turns
        // them into commands at the forward presentation boundary; the other
        // two profiles publish immediately and are unaffected by the extra
        // boundary.
        audioManager.presentFrame(
                com.openggf.audio.presentation.PresentationMode.FORWARD);

        var musicCommands = musicCommands(audioManager);
        assertEquals(1, musicCommands.size(),
                "Drowning music should be triggered exactly once");
        assertEquals(expectedMusicId, musicCommands.getFirst().musicId(),
                "Incorrect drowning music ID selected");
        assertTrue(controller.isDrowningMusicPlaying(),
                "Controller should flag drowning music as active");
    }

    @Test
    void s3kFixedCountdownAirEventTriggersDrowningMusicWithoutGenericBubbleUpdate() {
        AudioManager audioManager = AudioManager.getInstance();
        audioManager.setAudioProfile(new Sonic3kAudioProfile());
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.level().resetState();

        DrowningController controller = new DrowningController(new Sonic("test", (short) 0, (short) 0));

        for (int i = 0; i < 19; i++) {
            controller.performFixedCountdownAirEvent(true);
        }

        var musicCommands = musicCommands(audioManager);
        assertEquals(1, musicCommands.size(),
                "fixed Obj_AirCountdown should still trigger drowning music at air_left=12");
        assertEquals(Sonic3kMusic.DROWNING.id, musicCommands.getFirst().musicId());
        assertTrue(controller.isDrowningMusicPlaying());
    }

    @Test
    void genericCountdownProcessesAirEventBeforePendingBubbleTimer() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.level().resetState();

        long seed = 0x13579BDFL;
        GameRng rng = GameServices.rng();
        rng.setSeed(seed);

        DrowningController controller = new DrowningController(new Sonic("test", (short) 0, (short) 0));
        setPrivateInt(controller, "frameTimer", 1);
        setPrivateInt(controller, "bubbleFlags", 1);
        setPrivateInt(controller, "bubblesRemainingInBurst", 1);
        setPrivateInt(controller, "nextBubbleTimer", 0);

        boolean drowned = controller.update();

        GameRng expected = new GameRng(rng.flavour(), seed);
        expected.nextBits(1);      // Obj0A_Countdown: choose one- or two-bubble burst.
        expected.nextBits(0x0F);   // Obj0A_MakeBubbleNow: seed the new mouth-bubble timer.
        assertFalse(drowned);
        assertEquals(29, getPrivateInt(controller, "remainingAir"));
        assertEquals(expected.getSeed(), rng.getSeed(),
                "same-frame air events must not consume the stale pending mouth-bubble RNG first");
    }

    @Test
    void s2CountdownResetStartsFromRomSidecarZeroTimer() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        TestEnvironment.activeGameplayMode();
        GameServices.level().resetState();

        Sonic sonic = new Sonic("test", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_2);
        DrowningController controller = new DrowningController(sonic);
        controller.reset();

        assertEquals(0, getPrivateInt(controller, "frameTimer"));
        assertFalse(controller.update());
        assertEquals(29, getPrivateInt(controller, "remainingAir"),
                "S2 Obj0A_Countdown starts from zero and runs its first air event immediately");
        assertEquals(60, getPrivateInt(controller, "frameTimer"));
    }

    @Test
    void typedDrowningBubbleRulesOverrideLegacyInitialTimer() throws Exception {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.currentAudioManager()).thenReturn(AudioManager.getInstance());
        when(player.getGameRules()).thenReturn(GameRules.SONIC_2);
        when(player.getGameRules()).thenReturn(withDrowningBubbleRules(
                GameRules.SONIC_2,
                new DrowningBubbleRules(37, 8, true, -0x88)));

        DrowningController controller = new DrowningController(player);

        assertEquals(37, getPrivateInt(controller, "frameTimer"));
    }

    @Test
    void typedDrowningBubbleRulesOverrideLegacyMouthBubbleTimerBias() throws Exception {
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2, 0x2468ACE0L);
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.currentAudioManager()).thenReturn(AudioManager.getInstance());
        when(player.currentRng()).thenReturn(rng);
        when(player.getGameRules()).thenReturn(GameRules.SONIC_2);
        when(player.getGameRules()).thenReturn(withDrowningBubbleRules(
                GameRules.SONIC_2,
                new DrowningBubbleRules(0, 3, true, -0x88)));
        DrowningController controller = new DrowningController(player);
        setPrivateInt(controller, "bubbleFlags", 1);
        setPrivateInt(controller, "bubblesRemainingInBurst", 1);

        invokeSpawnRomMouthBubble(controller);

        GameRng expected = new GameRng(GameRng.Flavour.S1_S2, 0x2468ACE0L);
        assertEquals(expected.nextBits(0x0F) + 3, getPrivateInt(controller, "nextBubbleTimer"));
    }

    @Test
    void nullDrowningBubbleGroupUsesGenericDefaultTimer() throws Exception {
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.currentAudioManager()).thenReturn(AudioManager.getInstance());
        when(player.getGameRules()).thenReturn(GameRules.SONIC_2);
        when(player.getGameRules()).thenReturn(withDrowningBubbleRules(
                GameRules.SONIC_2, null));

        DrowningController controller = new DrowningController(player);

        assertEquals(60, getPrivateInt(controller, "frameTimer"));
    }

    @Test
    void s3kGenericCountdownFallbackKeepsFullSecondReset() throws Exception {
        Sonic sonic = new Sonic("test", (short) 0, (short) 0);
        sonic.setGameRulesForTest(GameRules.SONIC_3K);
        DrowningController controller = new DrowningController(sonic);
        controller.reset();

        assertEquals(60, getPrivateInt(controller, "frameTimer"));
        assertFalse(controller.update());
        assertEquals(30, getPrivateInt(controller, "remainingAir"));
        assertEquals(59, getPrivateInt(controller, "frameTimer"));
    }

    @Test
    void bubbleArtResolutionUsesRendererPresenceBeforeGpuCacheReadiness() throws Exception {
        AudioManager audioManager = AudioManager.getInstance();
        AbstractPlayableSprite player = mock(AbstractPlayableSprite.class);
        when(player.currentAudioManager()).thenReturn(audioManager);
        DrowningController controller = new DrowningController(player);

        LevelManager levelManager = mock(LevelManager.class);
        ObjectRenderManager renderManager = mock(ObjectRenderManager.class);
        PatternSpriteRenderer renderer = mock(PatternSpriteRenderer.class);
        when(levelManager.getObjectRenderManager()).thenReturn(renderManager);
        when(renderManager.getRenderer(ObjectArtKeys.LZ_BUBBLES)).thenReturn(null);
        when(renderManager.getRenderer(ObjectArtKeys.BUBBLES)).thenReturn(renderer);
        when(renderer.isReady()).thenReturn(false);

        Method method = DrowningController.class.getDeclaredMethod("resolveBubbleConfig", LevelManager.class);
        method.setAccessible(true);
        method.invoke(controller, levelManager);

        assertEquals(ObjectArtKeys.BUBBLES, getPrivateString(controller, "bubbleArtKey"),
                "Obj0A allocation should not depend on GPU pattern-cache readiness");
        verify(renderer, never()).isReady();
    }

    private static void setPrivateInt(DrowningController controller, String fieldName, int value) throws Exception {
        Field field = DrowningController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(controller, value);
    }

    private static int getPrivateInt(DrowningController controller, String fieldName) throws Exception {
        Field field = DrowningController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getInt(controller);
    }

    private static String getPrivateString(DrowningController controller, String fieldName) throws Exception {
        Field field = DrowningController.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(controller);
    }

    private static void invokeSpawnRomMouthBubble(DrowningController controller) throws Exception {
        Method method = DrowningController.class.getDeclaredMethod("spawnRomMouthBubble");
        method.setAccessible(true);
        method.invoke(controller);
    }

    private static GameRules withDrowningBubbleRules(GameRules base, DrowningBubbleRules drowningBubble) {
        return new GameRules(
                base.playerMovement(),
                base.playerCapability(),
                base.collision(),
                base.playerAnimation(),
                base.camera(),
                base.ring(),
                base.objectInteraction(),
                base.sidekickCpu(),
                base.powerUp(),
                drowningBubble);
    }

    private static java.util.List<AudioCommand.PlayMusic> musicCommands(AudioManager audioManager) {
        return audioManager.commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.PlayMusic.class::isInstance)
                .map(AudioCommand.PlayMusic.class::cast)
                .toList();
    }
}
