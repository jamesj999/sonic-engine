package com.openggf;

import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.audio.AudioManager;
import com.openggf.audio.GameMusic;
import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.SpecialStageStartupPolicy;
import com.openggf.game.GameStateManager;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic1.specialstage.Sonic1SpecialStageProvider;
import com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageProvider;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_2)
class TestGameLoopSpecialStageEntryPresentation {

    private GameLoop loop;
    private FadeManager fade;
    private AudioManager audio;

    @BeforeEach
    void setUp() throws Exception {
        // The entry tests below reach the real module provider's
        // initializeStage, whose palette setup calls straight into GL. Without
        // headless mode GraphicsManager issues glGenTextures with no current
        // context, which aborts the whole forked JVM rather than failing a test.
        GraphicsManager.getInstance().resetState();
        GraphicsManager.getInstance().initHeadless();
        loop = new GameLoop(new InputHandler());
        fade = mock(FadeManager.class);
        audio = mock(AudioManager.class);
        when(audio.playMusic(any(GameMusic.class))).thenReturn(true);
        setField(loop, "fadeManager", fade);
        setField(loop, "audioManager", audio);
    }

    @AfterEach
    void tearDown() throws Exception {
        setActiveTraceSession(null);
        SessionManager.clear();
    }

    @Test
    void normalReadyEntryUsesFastPolicyAndPreservesMusicRevealListenerOrder() throws Exception {
        SpecialStageProvider provider = readyProvider();
        GameLoop.GameModeChangeListener listener = mock(GameLoop.GameModeChangeListener.class);
        loop.setGameModeChangeListener(listener);

        loop.doEnterSpecialStage(provider, 2, false);

        InOrder order = inOrder(provider, audio, fade, listener);
        order.verify(provider).initializeStage(2, SpecialStageStartupPolicy.FAST);
        order.verify(audio).setSpeedShoes(false);
        order.verify(audio).setSpeedMultiplier(1);
        order.verify(audio).playMusic(GameMusic.SPECIAL_STAGE);
        order.verify(fade).startFadeFromWhite(any());
        order.verify(listener).onGameModeChanged(GameMode.LEVEL, GameMode.SPECIAL_STAGE);
        verify(fade, never()).holdWhite();
        assertEquals(GameMode.SPECIAL_STAGE, loop.getCurrentGameMode());
    }

    @Test
    void specialStageMusicClearsGameplayTempoBeforeLoadingTheSong()
            throws Exception {
        SpecialStageProvider provider = readyProvider();

        loop.doEnterSpecialStage(provider, 2, false);

        InOrder order = inOrder(audio);
        order.verify(audio).setSpeedShoes(false);
        order.verify(audio).setSpeedMultiplier(1);
        order.verify(audio).playMusic(GameMusic.SPECIAL_STAGE);
    }

    @Test
    void alreadyFadedSpecialStageEntryDoesNotReplayTransitionSfx() {
        assertTrue(GameLoop.shouldPlaySpecialStageEntrySfx(false));
        assertFalse(GameLoop.shouldPlaySpecialStageEntrySfx(true));
    }

    @Test
    void normalEntryEmitsTransitionSfxExactlyOnce() throws Exception {
        loop.setGameMode(GameMode.LEVEL);
        setField(loop, "spriteManager", new SpriteManager());
        setField(loop, "gameState", new GameStateManager());
        when(fade.isActive()).thenReturn(false);

        loop.enterSpecialStage();

        verify(audio, times(1)).playSfx(anyInt());
        assertEquals(GameMode.SPECIAL_STAGE, loop.getCurrentGameMode(),
                "the provider owns the entry fade after the immediate mode change");
    }

    @Test
    void heldWhiteEntryDoesNotEmitGenericTransitionSfxAgain() throws Exception {
        loop.setGameMode(GameMode.LEVEL);
        setField(loop, "spriteManager", new SpriteManager());
        setField(loop, "gameState", new GameStateManager());
        when(fade.isActive()).thenReturn(true);
        when(fade.getState()).thenReturn(FadeManager.FadeState.HOLD_WHITE);

        GameLoop dispatch = spy(loop);
        doNothing().when(dispatch).doEnterSpecialStage(
                any(SpecialStageProvider.class), anyInt(), eq(false));

        dispatch.enterSpecialStage();

        verify(audio, never()).playSfx(anyInt());
    }

    @Test
    void heldBlackEntryRetainsGenericTransitionSfxOwnership() throws Exception {
        loop.setGameMode(GameMode.LEVEL);
        setField(loop, "spriteManager", new SpriteManager());
        setField(loop, "gameState", new GameStateManager());
        when(fade.isActive()).thenReturn(true);
        when(fade.getState()).thenReturn(FadeManager.FadeState.HOLD_BLACK);

        GameLoop dispatch = spy(loop);
        doNothing().when(dispatch).doEnterSpecialStage(
                any(SpecialStageProvider.class), anyInt(), eq(true));

        dispatch.enterSpecialStage();

        verify(audio, times(1)).playSfx(anyInt());
    }

    @Test
    void suppressedRunLevelRowStillConsumesPendingSpecialStageRequest()
            throws Exception {
        LevelManager levelManager = mock(LevelManager.class);
        when(levelManager.consumeSpecialStageRequest()).thenReturn(true);
        setField(loop, "levelManager", levelManager);
        loop.setGameMode(GameMode.LEVEL);
        GameLoop dispatch = spy(loop);
        doNothing().when(dispatch).enterSpecialStage();

        assertTrue(dispatch.consumeSpecialStageRequestDuringSuppressedRunRow());

        verify(levelManager).consumeSpecialStageRequest();
        verify(dispatch).enterSpecialStage();
    }

    @Test
    void accurateBlackEntryHoldsOpaqueThenStartsMusicAndRevealExactlyOnce() throws Exception {
        setActiveTraceSession(mock(TraceSessionLauncher.class));
        AtomicBoolean ready = new AtomicBoolean(false);
        SpecialStageProvider provider = providerWithReadiness(ready);

        loop.doEnterSpecialStage(provider, 0, true, SpecialStageStartupPolicy.TRACE_ACCURATE);

        verify(fade).holdBlack();
        verify(audio, never()).playMusic(GameMusic.SPECIAL_STAGE);
        assertEquals(GameMode.SPECIAL_STAGE, loop.getCurrentGameMode());

        ready.set(true);
        invokeUpdateSpecialStageMode();
        invokeUpdateSpecialStageMode();

        InOrder order = inOrder(audio, fade);
        order.verify(audio).setSpeedShoes(false);
        order.verify(audio).setSpeedMultiplier(1);
        order.verify(audio).playMusic(GameMusic.SPECIAL_STAGE);
        order.verify(fade).startFadeFromBlack(any());
        verify(audio, times(1)).playMusic(GameMusic.SPECIAL_STAGE);
        verify(fade, times(1)).startFadeFromBlack(any());
    }

    @Test
    void accurateWhiteEntryFadesLevelToWhiteThenStartsMusicAndRevealExactlyOnce() throws Exception {
        setActiveTraceSession(mock(TraceSessionLauncher.class));
        AtomicBoolean ready = new AtomicBoolean(false);
        SpecialStageProvider provider = providerWithReadiness(ready);

        loop.doEnterSpecialStage(provider, 0, false, SpecialStageStartupPolicy.TRACE_ACCURATE);

        verify(fade).startFadeToWhite(isNull(), eq(Integer.MAX_VALUE));
        verify(fade).deferFirstStepToNextVint();
        verify(fade, never()).holdWhite();
        verify(audio, never()).playMusic(GameMusic.SPECIAL_STAGE);
        assertEquals(GameMode.SPECIAL_STAGE, loop.getCurrentGameMode());

        ready.set(true);
        invokeUpdateSpecialStageMode();
        invokeUpdateSpecialStageMode();

        InOrder order = inOrder(audio, fade);
        order.verify(audio).setSpeedShoes(false);
        order.verify(audio).setSpeedMultiplier(1);
        order.verify(audio).playMusic(GameMusic.SPECIAL_STAGE);
        order.verify(fade).startFadeFromWhite(any());
        verify(audio, times(1)).playMusic(GameMusic.SPECIAL_STAGE);
        verify(fade, times(1)).startFadeFromWhite(any());
    }

    @Test
    void leavingSpecialStageClearsDeferredPresentation() throws Exception {
        setActiveTraceSession(mock(TraceSessionLauncher.class));
        AtomicBoolean ready = new AtomicBoolean(false);
        SpecialStageProvider provider = providerWithReadiness(ready);
        loop.doEnterSpecialStage(provider, 0, false, SpecialStageStartupPolicy.TRACE_ACCURATE);

        loop.changeGameModeForBoundary(GameMode.TITLE_SCREEN);
        ready.set(true);
        invokeUpdateSpecialStageMode();

        verify(audio, never()).playMusic(GameMusic.SPECIAL_STAGE);
        assertFalse(entryPresentationPending());
    }

    @Test
    void concreteS1AndS3kProvidersRetainImmediateWhiteAndBlackEntry() throws Exception {
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(fade).startFadeFromWhite(any());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(fade).startFadeFromBlack(any());

        Sonic1SpecialStageProvider s1 = spy(new Sonic1SpecialStageProvider());
        doNothing().when(s1).reset();
        doNothing().when(s1).initializeStage(anyInt(), eq(SpecialStageStartupPolicy.FAST));
        doReturn(true).when(s1).isEntryPresentationReady();
        doReturn(false).when(s1).supportsRewind();
        doReturn(Optional.empty()).when(s1).rewindAdapter();

        loop.doEnterSpecialStage(s1, 0, false);
        verify(fade).startFadeFromWhite(any());
        verify(fade, never()).holdWhite();

        loop.changeGameModeForBoundary(GameMode.LEVEL);
        Sonic3kSpecialStageProvider s3k = spy(new Sonic3kSpecialStageProvider());
        doNothing().when(s3k).reset();
        doNothing().when(s3k).initializeStage(anyInt(), eq(SpecialStageStartupPolicy.FAST));
        doReturn(true).when(s3k).isEntryPresentationReady();
        doReturn(false).when(s3k).supportsRewind();
        doReturn(Optional.empty()).when(s3k).rewindAdapter();

        loop.doEnterSpecialStage(s3k, 0, true);
        verify(fade).startFadeFromBlack(any());
        verify(fade, never()).holdBlack();
        assertTrue(s1.isEntryPresentationReady());
        assertTrue(s3k.isEntryPresentationReady());
    }

    private SpecialStageProvider readyProvider() throws Exception {
        return providerWithReadiness(new AtomicBoolean(true));
    }

    private SpecialStageProvider providerWithReadiness(AtomicBoolean ready) throws Exception {
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        when(provider.isEntryPresentationReady()).thenAnswer(ignored -> ready.get());
        when(provider.getStageMusic()).thenReturn(GameMusic.SPECIAL_STAGE);
        when(provider.getStageMusicId()).thenReturn(-1);
        when(provider.rewindAdapter()).thenReturn(Optional.empty());
        when(provider.isFinished()).thenReturn(false);
        when(provider.specialStagePlcLifecyclePhase())
                .thenReturn(PlcLifecyclePhase.SPECIAL_STAGE);
        return provider;
    }

    private void invokeUpdateSpecialStageMode() throws Exception {
        GameLoopTestStep.invoke(loop, "updateSpecialStageMode", new Class<?>[0]);
    }

    private boolean entryPresentationPending() throws Exception {
        Object controller = getField(loop, "specialStageEntryPresentation");
        Method method = controller.getClass().getMethod("isPending");
        return (boolean) method.invoke(controller);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setActiveTraceSession(TraceSessionLauncher session) throws Exception {
        Field field = TraceSessionLauncher.class.getDeclaredField("activeSession");
        field.setAccessible(true);
        field.set(null, session);
    }
}
