package com.openggf.capture;

import java.util.List;
import java.util.Locale;

/**
 * ffmpeg encoder arguments for the configured capture codecs.
 *
 * <h2>Why the H.264 and H.265 settings look unusual</h2>
 *
 * <p>{@code -crf 0} and {@code -x265-params lossless=1} make the <em>codec</em>
 * mathematically lossless, but that is not the same as reproducing the frames
 * the engine handed over. The recorder submits RGBA, and encoding it as YUV —
 * even 4:4:4, which loses no chroma resolution — round-trips through a colour
 * conversion that does not return the original bytes. Measured on random pixel
 * data at 64x48x12 frames, decoding back to RGB:
 *
 * <pre>
 *   ffv1                                        byte-exact
 *   libx264rgb -crf 0 -pix_fmt rgb24            byte-exact
 *   libx264    -crf 0 -pix_fmt yuv444p          43,896 bytes differ
 *   libx264    -crf 0 -pix_fmt yuv420p         109,697 bytes differ
 *   libx265 lossless=1 -pix_fmt gbrp            byte-exact
 *   libx265 lossless=1 -pix_fmt yuv444p         43,896 bytes differ
 * </pre>
 *
 * <p>So H.264 uses the RGB-native {@code libx264rgb} encoder and H.265 uses
 * planar RGB ({@code gbrp}). {@code TestCaptureCodecLosslessness} re-measures
 * this rather than trusting the flags.
 *
 * <p>The trade-off is player compatibility: RGB H.264/H.265 is legal but less
 * widely supported than the YUV forms. FFV1 remains the default for that
 * reason as much as for size.
 */
public final class CaptureCodecs {

    /** Encoder arguments and whether the result reproduces the submitted frames. */
    public record Codec(String name, List<String> arguments, boolean lossless) {
        public Codec {
            arguments = List.copyOf(arguments);
        }
    }

    private CaptureCodecs() {
    }

    /**
     * @param name {@code ffv1}, {@code h264}, {@code h265} or
     *        {@code dnxhr-sq}, case-insensitive
     * @throws IllegalArgumentException on an unknown name, so a typo in
     *         {@code config.yaml} fails at capture start rather than producing
     *         a recording in a codec the user did not ask for
     */
    public static Codec video(String name) {
        return video(name, 0, "");
    }

    /**
     * @param threads value for ffmpeg's {@code -threads}; {@code 0} lets ffmpeg
     *        choose, which is normally one thread per core. FFV1 additionally
     *        needs the frame split into slices before threads can do anything,
     *        so that is added here rather than left as a trap.
     * @param preset x264/x265 speed preset, or blank for the encoder's own
     *        default. Ignored for FFV1, which has no preset option — passing one
     *        makes ffmpeg warn about an unused option. Presets trade encode
     *        speed for file size and do NOT affect losslessness: {@code -crf 0}
     *        and {@code lossless=1} stay lossless at every preset.
     *        <p>
     *        This matters most for H.265: libx265 defaults to {@code medium},
     *        which cannot keep up with lossless RGB at a full window resolution
     *        in real time, and because libx265 already threads internally, more
     *        threads will not rescue it — the preset is the lever.
     */
    public static Codec video(String name, int threads, String preset) {
        List<String> threadArgs = threadArguments(threads);
        List<String> presetArgs = presetArguments(preset);
        return switch (normalize(name)) {
            // FFV1 is single-threaded unless the frame is sliced, so -threads
            // alone would silently do nothing.
            case "ffv1" -> new Codec("ffv1",
                    concat(List.of("-c:v", "ffv1"),
                            threads == 1 ? List.of() : List.of("-slices", "16"),
                            threadArgs),
                    true);
            // RGB-native H.264: the only libx264 form that returns the exact
            // submitted pixels. See the class javadoc.
            case "h264" -> new Codec("h264",
                    concat(List.of("-c:v", "libx264rgb", "-crf", "0", "-pix_fmt", "rgb24"),
                            presetArgs, threadArgs),
                    true);
            // Planar RGB H.265, for the same reason.
            case "h265" -> new Codec("h265",
                    concat(List.of("-c:v", "libx265", "-x265-params", "lossless=1",
                            "-pix_fmt", "gbrp"), presetArgs, threadArgs),
                    true);
            // Resolve-compatible intermediate. DNxHR SQ is visually lossy and
            // uses 8-bit 4:2:2 YUV, so do not advertise the byte-exact guarantee
            // made by the RGB-native codecs above.
            case "dnxhr-sq" -> new Codec("dnxhr-sq",
                    concat(List.of("-c:v", "dnxhd", "-profile:v", "dnxhr_sq",
                            "-pix_fmt", "yuv422p"), threadArgs),
                    false);
            default -> throw new IllegalArgumentException(
                    "unknown capture video codec '" + name
                            + "'; expected ffv1, h264, h265 or dnxhr-sq");
        };
    }

    /** Valid x264/x265 speed presets, slowest to fastest. */
    public static final List<String> PRESETS = List.of(
            "placebo", "veryslow", "slower", "slow", "medium", "fast",
            "faster", "veryfast", "superfast", "ultrafast");

    private static List<String> threadArguments(int threads) {
        return threads <= 0 ? List.of() : List.of("-threads", String.valueOf(threads));
    }

    private static List<String> presetArguments(String preset) {
        String normalized = normalize(preset);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (!PRESETS.contains(normalized)) {
            throw new IllegalArgumentException(
                    "unknown capture encoder preset '" + preset + "'; expected one of "
                            + PRESETS);
        }
        return List.of("-preset", normalized);
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        List<String> all = new java.util.ArrayList<>();
        for (List<String> part : parts) {
            all.addAll(part);
        }
        return all;
    }

    /**
     * @param name {@code flac}, {@code pcm-s24le}, {@code aac} or {@code mp3},
     *        case-insensitive
     * @throws IllegalArgumentException on an unknown name
     */
    public static Codec audio(String name) {
        return switch (normalize(name)) {
            case "flac" -> new Codec("flac", List.of("-c:a", "flac"), true);
            case "pcm-s24le" -> new Codec("pcm-s24le",
                    List.of("-c:a", "pcm_s24le"), true);
            case "aac" -> new Codec("aac", List.of("-c:a", "aac"), false);
            case "mp3" -> new Codec("mp3", List.of("-c:a", "libmp3lame"), false);
            default -> throw new IllegalArgumentException(
                    "unknown capture audio codec '" + name
                            + "'; expected flac, pcm-s24le, aac or mp3");
        };
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }
}
