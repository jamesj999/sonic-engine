package com.openggf.audio;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.driver.SmpsDriverServiceObserver;
import com.openggf.audio.presentation.AudioPresentationParityProbe;
import com.openggf.audio.presentation.AudioPresentationProducer;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.session.SmpsDriverSession;
import com.openggf.audio.smps.DacData;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.function.Function;

/** Test-source bridge for package-private audio diagnostics. */
public final class AudioManagerTestDiagnostics {
    private AudioManagerTestDiagnostics() {
    }

    public static AudioPresentationParityProbe.Snapshot shadowParitySnapshot(
            AudioManager audio) {
        return audio.shadowParitySnapshot();
    }

    public static LiveCaptureAudioHandle attachPresentationCapture(
            AudioManager audio, int frameRate) {
        return audio.attachShadowCaptureForTesting(frameRate);
    }

    public static AudioPresentationProducer.TransactionFingerprint
            producerFingerprint(AudioManager audio) {
        return audio.releaseStateForTesting().producer();
    }

    /**
     * Number of logical audio restores actually published to the presentation
     * producer. Held rewind must defer every per-frame restore and publish
     * exactly one at release.
     */
    public static int logicalRestorePublications(AudioManager audio) {
        return audio.logicalRestorePublicationsForTesting();
    }

    public static void resetLogicalRestorePublications(AudioManager audio) {
        audio.resetLogicalRestorePublicationsForTesting();
    }

    /**
     * Makes the next reverse-release throw exactly once, just before the one
     * irreversible producer commit. The producer is the only restore owner, so
     * a test backend can no longer fail the publication step; this keeps the
     * retained-and-retried release contract testable from a production host.
     */
    public static void failNextReverseRelease(AudioManager audio) {
        failNextReverseRelease(audio,
                AudioManager.ReverseReleaseFailurePoint
                        .BEFORE_PRODUCER_COMMIT);
    }

    /**
     * Makes the next reverse-release throw exactly once at {@code point}. The
     * two sides of {@code shadowProducer.endReverse(...)} carry different
     * contracts: before it the release must be exactly retryable, after it the
     * reverse session no longer exists and the release must complete instead.
     */
    public static void failNextReverseRelease(
            AudioManager audio,
            AudioManager.ReverseReleaseFailurePoint point) {
        audio.failNextReverseReleaseForTesting(point);
    }

    /**
     * Pre-decodes a fallback WAV SFX asset into the presentation source
     * factory's cache so {@code playSfx(name)} resolves a sample voice without
     * a packaged classpath asset.
     */
    public static void registerFallbackSfxAsset(
            AudioManager audio, String assetId, byte[] unsigned8Mono,
            int sourceSampleRate) {
        audio.shadowFactoryForTesting().registerUnsigned8Mono(
                assetId, unsigned8Mono, sourceSampleRate);
    }

    /**
     * Primes the <em>already admitted</em> session-owned SMPS device so a stub
     * (data-free) asset still synthesizes audible FM/PSG/DAC output.
     *
     * <p>This deliberately does <em>not</em> drain the pending presentation
     * command queue: admission is production behaviour that
     * {@code presentFrame(...)} performs at the owner boundary, so callers must
     * present once first. Admitting here instead would make every "the voice
     * reached the offline registry" assertion self-satisfied. Present
     * {@code SILENT} for that first drain: it is the same production command
     * drain, but it does not render, so a data-free stub voice is not swept as
     * complete before the caller can prime it.
     *
     * @return the primed logical snapshot, or {@code null} when no SMPS music
     *         has been admitted
     */
    public static SmpsDriverSnapshot primeAdmittedSmpsMusic(
            AudioManager audio) {
        SmpsDriverSession session = field(
                audio, "shadowSmpsSession", SmpsDriverSession.class);
        SmpsDriver driver = sessionDriver(session);
        SmpsDriverSnapshot before = driver.captureSnapshot();
        if (before.sequencers().stream().noneMatch(entry -> !entry.sfx())) {
            return null;
        }
        withSessionEpoch(session, driver, () -> primeSynth(driver));
        return driver.captureSnapshot();
    }

    /** Mirrors the source-parity fixture's synthesis priming. */
    private static void primeSynth(SmpsDriver driver) {
        var musicSource = driver.captureSnapshot().sequencers().stream()
                .filter(entry -> !entry.sfx())
                .findFirst().orElseThrow().source();
        driver.selectDac(musicSource, new DacData(
                Map.of(1, new byte[] {0, 24, 64, 127}),
                Map.of(0x81, new DacData.DacEntry(1, 4)), 295));
        driver.writeFm(driver, 0, 0x22, 0x0B);
        driver.writeFm(driver, 0, 0x2B, 0x80);
        driver.setInstrument(driver, 0, new byte[] {
                0x32, 0x71, 0x0D, 0x33, 0x01, 0x5F, 0x5F, 0x5F,
                0x5F, 0x14, 0x0E, 0x0E, 0x0E, 0x08, 0x08, 0x08,
                0x08, 0x0F, 0x0F, 0x0F, 0x0F, 0x1B, 0x16, 0x1F,
                0x00
        });
        driver.writeFm(driver, 0, 0xA4, 0x22);
        driver.writeFm(driver, 0, 0xA0, 0x69);
        driver.writeFm(driver, 0, 0xB4, 0xC7);
        driver.writeFm(driver, 0, 0x28, 0xF0);
        driver.playDac(driver, 0x81);
        driver.writePsg(driver, 0x84);
        driver.writePsg(driver, 0x12);
        driver.writePsg(driver, 0x92);
    }

    private static SmpsDriver sessionDriver(SmpsDriverSession session) {
        try {
            Method method = SmpsDriverSession.class.getDeclaredMethod(
                    "logicalDriverForTesting");
            method.setAccessible(true);
            return (SmpsDriver) method.invoke(session);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "cannot read the session-owned SMPS driver", failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static void withSessionEpoch(
            SmpsDriverSession session,
            SmpsDriver driver,
            Runnable action) {
        try {
            Method method = SmpsDriverSession.class.getDeclaredMethod(
                    "withPort",
                    SmpsDriverServiceObserver.DriverIdentity.class,
                    Function.class);
            method.setAccessible(true);
            method.invoke(session, driver.diagnosticIdentity(),
                    (Function<Object, Object>) ignored -> {
                        action.run();
                        return null;
                    });
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(
                    "session SMPS priming failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "cannot open a scoped session SMPS epoch", failure);
        }
    }

    private static <T> T field(AudioManager audio, String name, Class<T> type) {
        try {
            Field field = AudioManager.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(audio));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(
                    "cannot read AudioManager." + name, failure);
        }
    }
}
