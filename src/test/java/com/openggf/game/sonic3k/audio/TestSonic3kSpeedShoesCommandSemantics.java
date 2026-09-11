package com.openggf.game.sonic3k.audio;

import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.audio.rewind.AudioCommandTimeline;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.objects.Sonic3kMonitorObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TestObjectServices;
import com.openggf.level.objects.TouchCategory;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.TestablePlayableSprite;
import com.openggf.timer.Timer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic3kSpeedShoesCommandSemantics {
    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
    }

    @AfterEach
    void tearDown() {
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void shippedCommandIdsResolveFromEitherMailboxExactlyOnce() {
        AudioManager audio = AudioManager.getInstance();
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(new Sonic3kAudioProfile());
        AudioCommandTimeline timeline = audio.commandTimeline();

        int before = timeline.entryCount();
        audio.playMusic(Sonic3kSmpsConstants.CMD_STOP);
        assertEquals(before + 1, timeline.entryCount());
        AudioCommand.RetainGlobalStop e0 = assertInstanceOf(
                AudioCommand.RetainGlobalStop.class,
                timeline.entryAt(before).command());
        assertEquals(0xE0, e0.sourceCommandId());

        before = timeline.entryCount();
        assertTrue(audio.playSfx(Sonic3kSmpsConstants.CMD_STOP_ALL));
        assertEquals(before + 1, timeline.entryCount());
        AudioCommand.RetainGlobalStop e2 = assertInstanceOf(
                AudioCommand.RetainGlobalStop.class,
                timeline.entryAt(before).command());
        assertEquals(0xE2, e2.sourceCommandId());

        before = timeline.entryCount();
        audio.playMusic(Sonic3kSmpsConstants.CMD_FADE_OUT);
        assertEquals(before + 1, timeline.entryCount());
        assertInstanceOf(AudioCommand.FadeOutMusic.class,
                timeline.entryAt(before).command());

        before = timeline.entryCount();
        assertTrue(audio.playSfx(Sonic3kSmpsConstants.CMD_FADE_OUT_ALT));
        assertEquals(before + 1, timeline.entryCount());
        assertInstanceOf(AudioCommand.FadeOutMusic.class,
                timeline.entryAt(before).command());

        before = timeline.entryCount();
        audio.playMusic(Sonic3kSmpsConstants.CMD_STOP_SFX);
        assertEquals(before + 1, timeline.entryCount());
        assertInstanceOf(AudioCommand.StopSmpsSfx.class,
                timeline.entryAt(before).command());
    }

    @Test
    void e3RoutesToPsgSilenceAndNeverSpeedControl() {
        AudioManager audio = AudioManager.getInstance();
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(new Sonic3kAudioProfile());
        int before = audio.commandTimeline().entryCount();

        assertTrue(audio.playSfx(Sonic3kSmpsConstants.CMD_PSG_SILENCE));

        assertEquals(before + 1, audio.commandTimeline().entryCount());
        AudioCommand command =
                audio.commandTimeline().entryAt(before).command();
        assertFalse(command instanceof AudioCommand.ReferenceLimitation,
                "the shipped E3 command is a supported PSG-silence operation");
        AudioCommand.SilencePsg silence = assertInstanceOf(
                AudioCommand.SilencePsg.class, command);
        assertEquals(0xE3, silence.sourceCommandId());
        assertEquals(1, audio.captureLogicalSnapshot().presentation()
                .speedMultiplier());
    }

    @Test
    void monitorPickupAndTimerExpiryUseSemanticEightAndOneWithoutSystemCommands() {
        AudioManager audio = AudioManager.getInstance();
        audio.setBackend(new NullAudioBackend());
        audio.setAudioProfile(new Sonic3kAudioProfile());
        Sonic3kMonitorObjectInstance monitor =
                new Sonic3kMonitorObjectInstance(
                        new ObjectSpawn(0x0100, 0x0050,
                                0x01, 0x04, 0, false, 0));
        monitor.setServices(new TestObjectServices()
                .withAudioManager(audio));
        TestablePlayableSprite player = new TestablePlayableSprite(
                "sonic", (short) 0x0100, (short) 0x0050);
        player.setAnimationId(Sonic3kAnimationIds.ROLL);
        player.setRolling(true);
        player.setYSpeed((short) 0x05A0);

        monitor.update(0, player);
        monitor.onTouchResponse(player,
                new TouchResponseResult(
                        0, 0x0E, 0x0E, TouchCategory.SPECIAL),
                1);
        for (int frame = 0; frame < 33; frame++) {
            monitor.update(frame, player);
        }

        String timerCode = "SpeedShoes-" + player.getCode();
        Timer timer = GameServices.timers().getTimerForCode(timerCode);
        assertTrue(player.hasSpeedShoes());
        assertNotNull(timer,
                "production monitor pickup must register SpeedShoesTimer");

        int commandCountAfterPickup = audio.commandTimeline().entryCount();
        AudioCommand.SetSpeedMultiplier on = assertInstanceOf(
                AudioCommand.SetSpeedMultiplier.class,
                audio.commandTimeline().entryAt(
                        commandCountAfterPickup - 1).command());
        assertEquals(8, on.multiplier());

        // S3K's countdown is a byte timer the ROM decrements only on level
        // frames whose counter is divisible by eight, read from Sonic_Display
        // (docs/skdisasm/sonic3k.asm:22103-22111). Drive whole level frames --
        // counter advance plus display step -- as the live frame step does, so
        // the gate fires once per eight frames.
        var levelManager = player.currentLevelManagerIfAvailable();
        assertNotNull(levelManager, "test gameplay mode must provide a level manager");
        int levelFrame = 0;
        int guard = 0;
        while (GameServices.timers().getTimerForCode(timerCode) != null && guard++ < 4000) {
            levelManager.setFrameCounter(levelFrame++);
            GameServices.timers().updateDisplayPhaseTimersFor(player);
            if (GameServices.timers().getTimerForCode(timerCode) != null) {
                assertSame(timer, GameServices.timers().getTimerForCode(timerCode),
                        "timer must remain registered before expiry");
                assertTrue(player.hasSpeedShoes());
                assertEquals(commandCountAfterPickup,
                        audio.commandTimeline().entryCount(),
                        "countdown updates must preserve semantic multiplier 8");
            }
        }
        assertNull(GameServices.timers().getTimerForCode(timerCode),
                "TimerManager must remove the completed production timer");
        assertFalse(player.hasSpeedShoes());
        assertTrue(guard >= 1190 && guard <= 1205,
                "S3K speed shoes last ~1200 level frames via 150 every-eighth-frame "
                        + "decrements (docs/skdisasm/sonic3k.asm:40858); was " + guard);

        int count = audio.commandTimeline().entryCount();
        AudioCommand.SetSpeedMultiplier off = assertInstanceOf(
                AudioCommand.SetSpeedMultiplier.class,
                audio.commandTimeline().entryAt(count - 1).command());
        assertEquals(commandCountAfterPickup + 1, count);
        assertEquals(1, off.multiplier());
    }
}
