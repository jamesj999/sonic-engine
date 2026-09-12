package com.openggf.audio.debug;

import com.openggf.audio.AudioManager;
import com.openggf.audio.ChannelType;
import com.openggf.audio.output.AudioPresentationSink;
import com.openggf.audio.presentation.AudioPresentationFrameView;
import com.openggf.audio.presentation.PresentationVoiceSnapshot;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsCoordFlagHandlerOwner;
import com.openggf.audio.smps.SmpsCoordFlagRuntimeState;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSmpsData;
import com.openggf.game.sonic3k.audio.smps.Sonic3kSfxData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSoundTestPresentationHost {
    @Test
    void directBackendPlaybackCallsAreAbsentFromSoundTestApp()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/audio/debug/SoundTestApp.java"));
        assertFalse(source.contains("AudioBackend"));
        assertFalse(source.contains("backend.play"));
        assertFalse(source.contains("backend.update"));
        assertFalse(source.contains("backend.toggle"));
        assertTrue(source.contains(
                "StandaloneAudioPresentationHost"));
    }

    @Test
    void noDeviceModeStillAdvancesAndOwnsMuteSoloSpeedCommands() {
        try (StandaloneAudioPresentationHost host =
                     StandaloneAudioPresentationHost.open(
                             "s2",
                             SonicConfigurationService.createStandalone(),
                             null, true)) {
            host.toggleMute(ChannelType.FM, 1);
            host.toggleSolo(ChannelType.PSG, 2);
            host.setSpeedShoes(true);
            host.presentFrame();

            assertTrue(host.isMuted(ChannelType.FM, 1));
            assertTrue(host.isSoloed(ChannelType.PSG, 2));
        }
    }

    @Test
    void explicitCliGameOverrideIsTheBoundGameId() {
        try (StandaloneAudioPresentationHost host =
                     StandaloneAudioPresentationHost.open(
                             "s3k",
                             SonicConfigurationService.createStandalone(),
                             null, true)) {
            assertEquals("s3k", host.boundGameId());
        }
    }

    @Test
    void consoleAndInteractivePathsTickTheSameHostOwnedProducer()
            throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/openggf/audio/debug/SoundTestApp.java"));
        assertTrue(source.contains(
                "runInteractiveWindow(options, loader, dacData, host"));
        assertTrue(source.contains(
                "runConsole(options, loader, dacData, host"));
        assertTrue(source.contains(
                "exec.scheduleAtFixedRate(host::presentFrame"));
        assertTrue(source.contains("host.presentFrame();"));
        // Both entry paths receive the same production host. That host now
        // owns its producer executor; the UI executor only serializes commands.
        // interactiveExecutorCommandsRunOnTheHostProducerOwner exercises the
        // cross-thread dispatch contract rather than requiring app-side creation.
        assertTrue(source.contains(
                "new InteractiveState(startSongId, loader, dacData, host"));
        assertTrue(source.contains("catalog, seqConfig, validSfx, exec"));
    }

    @Test
    void muteSoloAndSpeedReachAnActiveSmpsComposite() throws Exception {
        HostFixture fixture = fixture();
        try (StandaloneAudioPresentationHost host = fixture.host()) {
            host.playMusic(persistentS3kMusic(0x81), fixture.dac());
            host.playSfx(persistentS3kSfx(0xBC), fixture.dac(), 1.0f);
            host.toggleMute(ChannelType.FM, 1);
            host.toggleSolo(ChannelType.PSG, 2);
            host.setSpeedShoes(true);
            host.presentFrame();

            var presentation = host.managerForTesting()
                    .captureLogicalSnapshot().presentation();
            assertEquals(1 << 1, presentation.fmMuteMask());
            assertEquals(1 << 2, presentation.psgSoloMask());
            assertTrue(presentation.speedShoesEnabled());

            // S3K's driver owns the SEGA chant itself (Sound/Z80 Sound
            // Driver.asm:4372-4424), so raw PCM is not a presentation voice
            // on this profile: it is the driver's own bus-holding transport,
            // visible as the DAC enable and the sample bytes it writes.
            List<String> writes = new ArrayList<>();
            host.managerForTesting().setChipWriteObserver(
                    new com.openggf.audio.synth.ChipWriteObserver() {
                        @Override
                        public void onYm2612Write(
                                int port, int register, int value) {
                            writes.add("ym:" + port + ":" + register
                                    + ":" + value);
                        }

                        @Override
                        public void onPsgWrite(int value) {
                        }
                    });
            submitRawPcm(host.managerForTesting());
            host.presentFrame();
            assertNull(host.managerForTesting().captureLogicalSnapshot()
                    .presentation().rawPcmVoiceId());
            assertTrue(writes.contains("ym:0:43:128"),
                    "the driver enables the DAC for its own transport");
            assertTrue(writes.contains("ym:0:42:255"),
                    "the sample bytes reach the DAC data register");

            host.stopPlayback();
            host.presentFrame();
            assertTrue(host.managerForTesting().captureLogicalSnapshot()
                    .presentation().voices().isEmpty());
        }
    }

    @Test
    void closeDestroysTheManagerOwnedProducerAndSinkExactlyOnce() {
        HostFixture fixture = fixture();
        StandaloneAudioPresentationHost host = fixture.host();

        host.close();
        host.close();

        assertEquals(1, fixture.sink().closeCount.get());
    }

    @Test
    void interactiveExecutorCommandsRunOnTheHostProducerOwner() throws Exception {
        try (StandaloneAudioPresentationHost host =
                     StandaloneAudioPresentationHost.open(
                             "s1",
                             SonicConfigurationService.createStandalone(),
                             null, true);
             ExecutorService executor = Executors.newSingleThreadExecutor()) {
            assertDoesNotThrow(() -> executor.submit(() -> {
                host.toggleMute(ChannelType.FM, 1);
                host.presentFrame();
            }).get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void productionOwnerIsDaemonWhenADeadlineLeavesLateCleanup() throws Exception {
        try (StandaloneAudioPresentationHost host =
                     StandaloneAudioPresentationHost.open(
                             "s1",
                             SonicConfigurationService.createStandalone(),
                             null, true)) {
            Field ownerField = StandaloneAudioPresentationHost.class
                    .getDeclaredField("ownerThread");
            ownerField.setAccessible(true);
            Thread owner = (Thread) ownerField.get(host);

            assertTrue(owner.isDaemon(),
                    "late open or cleanup must not keep the JVM alive");
        }
    }

    @Test
    void rejectedOffOwnerCloseRemainsRetryableUntilOwnerCloses() throws Exception {
        HostFixture fixture = fixture();
        StandaloneAudioPresentationHost host = fixture.host();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            ExecutionException rejection = assertThrows(ExecutionException.class,
                    () -> executor.submit(host::close).get(5, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, rejection.getCause());
        }

        host.close();

        assertEquals(1, fixture.sink().closeCount.get(),
                "an off-owner rejection must not hide the retryable producer");
    }

    @Test
    void concurrentProductionCloseRequestsShareOneOwnerCleanup() throws Exception {
        StandaloneAudioPresentationHost host =
                StandaloneAudioPresentationHost.open(
                        "s1",
                        SonicConfigurationService.createStandalone(),
                        null, true);
        try (ExecutorService closers = Executors.newFixedThreadPool(2)) {
            Future<?> first = closers.submit(host::close);
            Future<?> second = closers.submit(host::close);
            assertDoesNotThrow(() -> first.get(5, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> second.get(5, TimeUnit.SECONDS));
        }
        host.close();
    }

    @Test
    void closeDeadlineLeavesOwnerCleanupAliveForASecondAttempt() throws Exception {
        ExecutorService ownerExecutor = Executors.newSingleThreadExecutor();
        BlockingSink sink = new BlockingSink();
        try {
            Future<StandaloneAudioPresentationHost> created = ownerExecutor.submit(() -> {
                AudioManager manager = AudioManager.createStandalonePresentation(
                        "s3k", new Sonic3kAudioProfile(),
                        SonicConfigurationService.createStandalone(), null,
                        sink, new SmpsCoordFlagHandlerOwner(
                                new SmpsCoordFlagRuntimeState()));
                return StandaloneAudioPresentationHost.fromManagerForTesting(
                        "s3k", manager, ownerExecutor);
            });
            StandaloneAudioPresentationHost host = created.get(5, TimeUnit.SECONDS);

            assertThrows(IllegalStateException.class, host::close,
                    "the caller deadline must remain bounded while owner cleanup continues");
            assertTrue(sink.closeStarted.await(1, TimeUnit.SECONDS),
                    "owner cleanup must have reached the sink before the deadline");

            sink.release.countDown();
            assertDoesNotThrow(() -> host.close(),
                    "a later close must observe the original cleanup completion");
            assertEquals(1, sink.closeCount.get());
            assertTrue(ownerExecutor.awaitTermination(1, TimeUnit.SECONDS),
                    "successful cleanup must release its private owner executor");
        } finally {
            // Keep a failed assertion from stranding this deliberately
            // blocked non-production executor in the Surefire fork.
            sink.release.countDown();
            ownerExecutor.shutdownNow();
            try {
                ownerExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Test
    void sonic3kMusicSfxResetAndSnapshotRecreationShareOneSessionCounter()
            throws Exception {
        HostFixture fixture = fixture();
        try (StandaloneAudioPresentationHost host = fixture.host()) {
            SmpsCoordFlagHandlerOwner owner = fixture.owner();
            owner.state().setSpindashRevCounter(7);
            host.playMusic(persistentS3kMusic(0x81), fixture.dac());
            host.presentFrame();
            host.playSfx(persistentS3kSfx(0xBC), fixture.dac(), 1.0f);
            host.presentFrame();
            var sequencers = host.managerForTesting()
                    .captureLogicalSnapshot().presentation().smpsLogical()
                    .sequencers();
            assertEquals(2, sequencers.size());
            var musicHandler = sequencers.get(0)
                    .config().getCoordFlagHandler();
            var sfxHandler = sequencers.get(1)
                    .config().getCoordFlagHandler();
            assertSame(owner.handlerFor("s3k"), musicHandler);
            assertSame(musicHandler, sfxHandler);
            sfxHandler.onSfxStart(0);
            assertEquals(0, owner.state().spindashRevCounter());
            owner.state().setSpindashRevCounter(7);
            var snapshot = host.managerForTesting().captureLogicalSnapshot();
            assertEquals(7, snapshot.presentation()
                    .coordFlagRuntimeState().spindashRevCounter());
            assertEquals(owner, presentationOwner(host.managerForTesting()));

            owner.state().setSpindashRevCounter(13);
            host.managerForTesting().restoreLogicalSnapshot(snapshot);

            assertEquals(7, owner.state().spindashRevCounter());
            assertEquals(owner, presentationOwner(host.managerForTesting()));
            var recreatedSequencers = host.managerForTesting()
                    .captureLogicalSnapshot().presentation().smpsLogical()
                    .sequencers();
            assertEquals(2, recreatedSequencers.size());
            assertSame(owner.handlerFor("s3k"),
                    recreatedSequencers.get(0).config()
                            .getCoordFlagHandler());
            var recreatedSfxHandler = recreatedSequencers.get(1)
                    .config().getCoordFlagHandler();
            assertSame(owner.handlerFor("s3k"), recreatedSfxHandler);
            recreatedSfxHandler.onSfxStart(0);
            assertEquals(0, owner.state().spindashRevCounter());
        }
    }

    private static HostFixture fixture() {
        CountingSink sink = new CountingSink();
        SmpsCoordFlagHandlerOwner owner = new SmpsCoordFlagHandlerOwner(
                new SmpsCoordFlagRuntimeState());
        AudioManager manager = AudioManager.createStandalonePresentation(
                "s3k", new Sonic3kAudioProfile(),
                SonicConfigurationService.createStandalone(),
                null, sink, owner);
        return new HostFixture(
                StandaloneAudioPresentationHost.fromManagerForTesting(
                        "s3k", manager),
                sink, owner, new DacData(Map.of(), Map.of(), 297));
    }

    private static SmpsCoordFlagHandlerOwner presentationOwner(
            AudioManager manager) throws Exception {
        Method method = AudioManager.class.getDeclaredMethod(
                "presentationCoordFlagHandlersForTesting");
        method.setAccessible(true);
        return (SmpsCoordFlagHandlerOwner) method.invoke(manager);
    }

    private static void submitRawPcm(AudioManager manager)
            throws Exception {
        Method method = AudioManager.class.getDeclaredMethod(
                "submitShadowRawPcmForTesting",
                byte[].class, int.class);
        method.setAccessible(true);
        method.invoke(manager, new byte[] {0, (byte) 0xFF}, 48_000);
    }

    private static AbstractSmpsData persistentS3kMusic(int id) {
        byte[] data = persistentS3kData(2);
        Sonic3kSmpsData source = new Sonic3kSmpsData(data, 0);
        source.setId(id);
        return source;
    }

    private static AbstractSmpsData persistentS3kSfx(int id) {
        byte[] data = new byte[0x4_000];
        setLe16(data, 0, 0x80);
        data[2] = 1;
        data[3] = 1;
        data[4] = (byte) 0x80;
        data[5] = 0x02;
        setLe16(data, 6, 0x100);
        writePersistentTrack(data);
        Sonic3kSfxData source = new Sonic3kSfxData(data, 0, 0, 0);
        source.setId(id);
        return source;
    }

    private static byte[] persistentS3kData(int channels) {
        byte[] data = new byte[0x4_000];
        setLe16(data, 0, 0x80);
        data[2] = (byte) channels;
        data[3] = 0;
        data[4] = 1;
        data[5] = (byte) 0x80;
        setLe16(data, 6, 0);
        setLe16(data, 10, 0x100);
        writePersistentTrack(data);
        return data;
    }

    private static void writePersistentTrack(byte[] data) {
        data[0x100] = (byte) 0x80;
        data[0x101] = 0x7F;
        data[0x102] = (byte) 0xF6;
        setLe16(data, 0x103, 0x100);
    }

    private static void setLe16(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private record HostFixture(
            StandaloneAudioPresentationHost host,
            CountingSink sink,
            SmpsCoordFlagHandlerOwner owner,
            DacData dac) {
    }

    private static final class CountingSink
            implements AudioPresentationSink {
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override public int sampleRate() {
            return 48_000;
        }

        @Override public void accept(AudioPresentationFrameView frame) {
        }

        @Override public void onReverseBoundary() {
        }

        @Override public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class BlockingSink implements AudioPresentationSink {
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override public int sampleRate() {
            return 48_000;
        }

        @Override public void accept(AudioPresentationFrameView frame) {
        }

        @Override public void onReverseBoundary() {
        }

        @Override public void close() {
            closeCount.incrementAndGet();
            closeStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
