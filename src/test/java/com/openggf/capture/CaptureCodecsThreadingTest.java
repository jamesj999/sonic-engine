package com.openggf.capture;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Threading and preset arguments. Losslessness across these settings is
 * measured separately by {@link TestCaptureCodecLosslessness} rather than
 * assumed here.
 */
class CaptureCodecsThreadingTest {

    @Test
    void defaultsAddNeitherThreadsNorPreset() {
        assertEquals(List.of("-c:v", "ffv1", "-slices", "16"),
                CaptureCodecs.video("ffv1").arguments());
        assertEquals(List.of("-c:v", "libx264rgb", "-crf", "0", "-pix_fmt", "rgb24"),
                CaptureCodecs.video("h264").arguments());
        assertEquals(List.of("-c:v", "libx265", "-x265-params", "lossless=1",
                        "-pix_fmt", "gbrp"),
                CaptureCodecs.video("h265").arguments());
    }

    @Test
    void ffv1IsSlicedSoThreadsCanActuallyBeUsed() {
        List<String> args = CaptureCodecs.video("ffv1", 8, "").arguments();

        assertTrue(args.containsAll(List.of("-slices", "16")),
                "FFV1 encodes single-threaded unless the frame is sliced, so -threads"
                        + " without -slices would silently do nothing");
        assertTrue(args.containsAll(List.of("-threads", "8")));
    }

    @Test
    void ffv1SingleThreadedDropsSlicingEntirely() {
        assertEquals(List.of("-c:v", "ffv1", "-threads", "1"),
                CaptureCodecs.video("ffv1", 1, "").arguments());
    }

    @Test
    void ffv1IgnoresPresetBecauseItHasNone() {
        List<String> args = CaptureCodecs.video("ffv1", 0, "ultrafast").arguments();

        assertFalse(args.contains("-preset"),
                "passing a preset to FFV1 only makes ffmpeg warn about an unused option");
    }

    @Test
    void h265TakesThePresetThatDecidesWhetherItKeepsUp() {
        List<String> args = CaptureCodecs.video("h265", 0, "ultrafast").arguments();

        assertEquals(List.of("-c:v", "libx265", "-x265-params", "lossless=1",
                "-pix_fmt", "gbrp", "-preset", "ultrafast"), args);
    }

    @Test
    void h264TakesBothPresetAndThreads() {
        List<String> args = CaptureCodecs.video("h264", 4, "veryfast").arguments();

        assertEquals(List.of("-c:v", "libx264rgb", "-crf", "0", "-pix_fmt", "rgb24",
                "-preset", "veryfast", "-threads", "4"), args);
    }

    @Test
    void dnxhrSqSelectsTheResolveCompatibleProfileAndPixelFormat() {
        assertEquals(List.of("-c:v", "dnxhd", "-profile:v", "dnxhr_sq",
                        "-pix_fmt", "yuv422p", "-threads", "4"),
                CaptureCodecs.video("dnxhr-sq", 4, "veryfast").arguments(),
                "DNxHR has no x26x speed preset, but should still honour the"
                        + " configured ffmpeg thread count");
    }

    @Test
    void nonPositiveThreadsLeaveTheChoiceToFfmpeg() {
        assertFalse(CaptureCodecs.video("h265", 0, "").arguments().contains("-threads"));
        assertFalse(CaptureCodecs.video("h265", -1, "").arguments().contains("-threads"));
    }

    @Test
    void presetIsCaseAndWhitespaceInsensitive() {
        assertEquals(CaptureCodecs.video("h265", 0, "ultrafast").arguments(),
                CaptureCodecs.video("h265", 0, "  UltraFast ").arguments());
    }

    @Test
    void anUnknownPresetFailsAtCaptureStartRatherThanSilently() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CaptureCodecs.video("h265", 0, "turbo"));

        assertTrue(failure.getMessage().contains("turbo"), failure.getMessage());
        assertTrue(failure.getMessage().contains("ultrafast"),
                "the message must list what is accepted: " + failure.getMessage());
    }

    /**
     * Resolved against an EMPTY config directory, not the process singleton:
     * the singleton reads the developer's real config.yaml, which is exactly
     * how this suite's sibling factory test used to pass or fail depending on
     * whose machine ran it.
     */
    @Test
    void shippedPresetDefaultIsFastAndReachesOnlyTheX26xCodecs(@TempDir Path emptyConfigDir) {
        String shipped = SonicConfigurationService.createStandalone(emptyConfigDir)
                .getString(SonicConfiguration.CAPTURE_ENCODER_PRESET);

        assertEquals("fast", shipped,
                "libx265's own medium default has little headroom for high-motion"
                        + " content such as fast-forwarded trace playback");
        assertTrue(CaptureCodecs.PRESETS.contains(shipped));
        assertTrue(CaptureCodecs.video("h265", 0, shipped).arguments()
                .containsAll(List.of("-preset", "fast")));
        assertFalse(CaptureCodecs.video("ffv1", 0, shipped).arguments().contains("-preset"));
    }

    @Test
    void everyDocumentedPresetIsAccepted() {
        for (String preset : CaptureCodecs.PRESETS) {
            assertEquals(List.of("-preset", preset),
                    CaptureCodecs.video("h265", 0, preset).arguments().subList(6, 8),
                    preset + " must be accepted");
        }
    }
}
