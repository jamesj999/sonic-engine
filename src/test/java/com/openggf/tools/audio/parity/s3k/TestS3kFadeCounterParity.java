package com.openggf.tools.audio.parity.s3k;

import com.openggf.audio.driver.SmpsDriver;
import com.openggf.audio.rewind.SmpsDriverSnapshot;
import com.openggf.audio.smps.SmpsSequencer;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kFadeCounterParity {

    @Test
    void normalizerProjectsDriverFadeCountersWithoutActiveMusic() {
        SmpsDriverSnapshot snapshot = new SmpsDriverSnapshot(
                SmpsSequencer.Region.NTSC, SmpsDriver.ReadMode.SAMPLE_ACCURATE,
                0, false, 0, 5, List.of(), new int[6], new int[4],
                List.of(), null, 6, 4, 0x28, 0x40, true);

        S3kAudioTick.GlobalState global =
                S3kAudioStateNormalizer.normalize(12, List.of(0, 0, 0), snapshot).global();

        assertEquals(0x28, global.fadeOutTimeout());
        assertEquals(0x40, global.fadeInTimeout());
    }

    @Test
    void registryMakesBothDriverOwnedFadeCountersGates() {
        assertEquals(S3kAudioFieldRegistry.Comparison.GATE,
                field("fadeOutTimeout").comparison());
        assertEquals("SmpsDriverSnapshot.fadeOutTimeout",
                field("fadeOutTimeout").engineSource());
        assertEquals(S3kAudioFieldRegistry.Comparison.GATE,
                field("fadeInTimeout").comparison());
        assertEquals("SmpsDriverSnapshot.fadeInTimeout",
                field("fadeInTimeout").engineSource());
    }

    @Test
    void comparatorReportsChangedFadeOutCounterAtExactService() {
        assertFadeMismatch("fadeOutTimeout", 0x28, 0x27);
    }

    @Test
    void comparatorReportsChangedFadeInCounterAtExactService() {
        assertFadeMismatch("fadeInTimeout", 0x40, 0x3f);
    }

    private static void assertFadeMismatch(String field, int referenceValue,
            int engineValue) {
        List<S3kAudioTick> reference = new ArrayList<>();
        List<S3kAudioTick> engine = new ArrayList<>();
        for (int service = 0; service < 37; service++) {
            reference.add(tick(service, 0, 0));
            engine.add(tick(service, 0, 0));
        }
        reference.add(tick(37,
                field.equals("fadeOutTimeout") ? referenceValue : 0,
                field.equals("fadeInTimeout") ? referenceValue : 0));
        engine.add(tick(37,
                field.equals("fadeOutTimeout") ? engineValue : 0,
                field.equals("fadeInTimeout") ? engineValue : 0));

        S3kAudioParityComparator.Report report = S3kAudioParityComparator.compare(
                reference, engine);

        assertEquals(S3kAudioParityComparator.Report.Kind.GLOBAL_STATE_MISMATCH,
                report.kind());
        assertEquals(37, report.tick());
        assertEquals("GLOBAL", report.role());
        assertEquals(field, report.field());
        assertEquals(Integer.toString(referenceValue), report.reference());
        assertEquals(Integer.toString(engineValue), report.openggf());
    }

    private static S3kAudioFieldRegistry.Field field(String name) {
        return S3kAudioFieldRegistry.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst().orElseThrow();
    }

    private static S3kAudioTick tick(int ordinal, int fadeOut, int fadeIn) {
        List<S3kAudioTrackState> tracks = new ArrayList<>();
        for (String role : S3kAudioParitySchema.ROLES) {
            tracks.add(S3kAudioTrackState.idle(role));
        }
        return new S3kAudioTick(ordinal, false, List.of(0, 0, 0),
                new S3kAudioTick.GlobalState(0, 0, 0, 0, null,
                        fadeOut, 0, 0, fadeIn, null, null, null, 5),
                tracks, List.of());
    }
}
