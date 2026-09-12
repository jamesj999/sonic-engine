package com.openggf.audio.synth;

import com.openggf.tests.AudioReferenceFixtures;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.audio.synth.fast.FastYm2612Dsp;
import com.openggf.audio.synth.nuked.NukedOpn2ScriptRunner;

import java.io.BufferedReader;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Judges the clean-room fast core against the cycle-exact Nuked-OPN2 facade
 * on the bit-exact register scripts: both facades receive the same register
 * writes at the same frame positions and render at the internal rate; the
 * mono sums are compared by normalised cross-correlation (best of lags
 * ±{@value #LAG_SEARCH} frames, since the fast facade lands writes at frame
 * start) and by RMS level ratio. This is a tolerance contract, not
 * sample equality: the fast core is register-level by design.
 *
 * <p>Thresholds can be relaxed for a diagnostic run with
 * {@code -Dopenggf.fastfm.minCorrelation=0 -Dopenggf.fastfm.maxLevelRatio=1e9};
 * every script's metrics are printed either way.
 */
// Synthetic scripts remain public; captured game scripts require audio-reference.
@Tag("slow-suite")
class TestFastFmCoreTolerance {
    private static final int LAG_SEARCH = 64;

    /**
     * Scripts that exercise the LSI test registers or bus-edge behaviour the
     * fast core does not model by contract (design document). They are excluded from default acceptance. Opt-in metrics are available
     * with -Dopenggf.fastfm.outOfScopeDiagnostics=true; the accurate-core
     * 183-script oracle remains unchanged.
     */
    private static final java.util.Set<String> OUT_OF_SCOPE = java.util.Set.of(
            "fuzz-s0", "fuzz-s1", "fuzz-s2", "test-regs", "bus-edges");
    private static final double MIN_CORRELATION =
            Double.parseDouble(System.getProperty("openggf.fastfm.minCorrelation", "0.9"));
    private static final double MAX_LEVEL_RATIO =
            Double.parseDouble(System.getProperty("openggf.fastfm.maxLevelRatio", "1.25"));
    /*
     * The RMS-envelope and zero-crossing contours below are diagnostics only.
     * A contour-based acceptance for feedback-heavy scripts was tried and
     * withdrawn: a second accurate core with a one-frame write delay still
     * correlates at 0.88-1.0 on those scripts (so they are not chaotic), and
     * the RMS contour passed a semitone-wrong negative control. See the
     * control modes in CONTROL.
     */

    /** Scripts quieter than this RMS on the accurate core are judged on level only. */
    private static final double SILENCE_RMS = 40.0;
    private static final int MAX_FRAMES = Integer.getInteger("openggf.fastfm.maxFrames", 160_000);

    @TestFactory
    Stream<DynamicTest> scriptsStayWithinTolerance() throws IOException, URISyntaxException {
        return scripts(false).filter(script -> !OUT_OF_SCOPE.contains(scriptName(script)))
                .map(script -> DynamicTest.dynamicTest(scriptName(script), () -> compare(script, false)));
    }

    @TestFactory
    @Tag("audio-reference")
    Stream<DynamicTest> capturedScriptsStayWithinTolerance() throws IOException, URISyntaxException {
        return scripts(true).map(script -> DynamicTest.dynamicTest(scriptName(script), () -> compare(script, false)));
    }

    @TestFactory
    Stream<DynamicTest> outOfScopeDiagnostics() throws IOException, URISyntaxException {
        if (!Boolean.getBoolean("openggf.fastfm.outOfScopeDiagnostics")) return Stream.empty();
        return scripts(false).filter(script -> OUT_OF_SCOPE.contains(scriptName(script)))
                .map(script -> DynamicTest.dynamicTest("diagnostic: " + scriptName(script),
                        () -> compare(script, true)));
    }

    private static String scriptName(Path script) {
        return script.getFileName().toString().replace(".txt.gz", "");
    }

    private static boolean isCaptured(String name) {
        return name.startsWith("s1-") || name.startsWith("s2-") || name.startsWith("s3k-");
    }

    private static Stream<Path> scripts(boolean captured) throws IOException, URISyntaxException {
        Path directory = captured
                ? AudioReferenceFixtures.require("audio/nuked-opn2/port/expected.txt").getParent()
                : Path.of(TestFastFmCoreTolerance.class
                        .getResource("/audio/nuked-opn2/port/expected.txt").toURI()).getParent();
        List<Path> scripts;
        try (Stream<Path> listing = Files.list(directory)) {
            scripts = listing.filter(p -> p.toString().endsWith(".txt.gz")).sorted().toList();
        }
        assertTrue(!scripts.isEmpty(), "No FM scripts in " + directory);
        assertTrue(scripts.stream().allMatch(p -> isCaptured(scriptName(p)) == captured),
                "FM scripts belong to the wrong fixture partition: " + directory);
        java.util.Set<String> expectedNames = Files.readAllLines(directory.resolve("expected.txt")).stream()
                .map(line -> line.trim().split("\\s+")[0]).collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedNames, scripts.stream().map(TestFastFmCoreTolerance::scriptName)
                .collect(java.util.stream.Collectors.toSet()), "FM script inventory must match expected.txt");
        String extra = System.getProperty("openggf.fastfm.extraDir");
        if (extra != null && !extra.isBlank()) {
            try (Stream<Path> listing = Files.list(Path.of(extra))) {
                scripts = Stream.concat(scripts.stream(),
                        listing.filter(p -> p.toString().endsWith(".txt.gz"))
                                .filter(p -> isCaptured(scriptName(p)) == captured).sorted()).toList();
            }
        }
        String only = System.getProperty("openggf.fastfm.only");
        if (only != null && !only.isBlank()) {
            java.util.Set<String> wanted = java.util.Arrays.stream(only.split(",", -1))
                    .map(String::trim).collect(java.util.stream.Collectors.toSet());
            assertTrue(wanted.stream().noneMatch(String::isEmpty), "openggf.fastfm.only contains an empty name");
            assertTrue(wanted.stream().allMatch(name -> isCaptured(name) == captured),
                    "Requested FM scripts belong to another fixture partition; use -Paudio-reference "
                            + "with -Dopenggf.audio.fixtures=<root> for captured game scripts, "
                            + "or the ordinary public suite for synthetic scripts");
            java.util.Set<String> available = scripts.stream().map(TestFastFmCoreTolerance::scriptName)
                    .collect(java.util.stream.Collectors.toSet());
            java.util.Set<String> missing = new java.util.TreeSet<>(wanted);
            missing.removeAll(available);
            assertTrue(missing.isEmpty(), "Requested FM scripts are missing: " + missing);
            scripts = scripts.stream().filter(p -> wanted.contains(scriptName(p))).toList();
        }
        return scripts.stream();
    }

    /**
     * Validation modes for the contour criterion (diagnostic only):
     * {@code accurateDelay} replaces the fast arm with a second accurate core
     * whose writes land one frame late to measure sensitivity to write timing;
     * agreement on contours alone does not establish fidelity or chaos.
     * {@code pitch}, {@code routing} and
     * {@code feedback} corrupt the fast arm's writes (F-number +1 semitone,
     * algorithm bit flipped, feedback reduced by one) so a criterion that still
     * passes them is too weak.
     */
    private static final String CONTROL = System.getProperty("openggf.fastfm.control", "");

    private static void compare(Path script, boolean diagnosticOnly) throws IOException {
        Ym2612Chip accurate = new Ym2612Chip();
        accurate.setOutputSampleRate(Ym2612Chip.getInternalRate());
        // The oracle runs as chip type 1 (YM3438-style output, no ladder): the
        // YM2612's analogue ladder is out of the fast core's scope and its DC
        // floor distorts low-level comparisons (eg-ar/eg-dr level ratios).
        accurate.setChipType(Integer.getInteger("openggf.fastfm.chipType", 1));
        FmChip candidate;
        if (CONTROL.equals("accurateDelay")) {
            Ym2612Chip delayed = new Ym2612Chip();
            delayed.setOutputSampleRate(Ym2612Chip.getInternalRate());
            delayed.setChipType(Integer.getInteger("openggf.fastfm.chipType", 1));
            candidate = delayed;
        } else {
            FastYm2612Chip fast = new FastYm2612Chip(new FastYm2612Dsp());
            fast.setOutputSampleRate(Ym2612Chip.getInternalRate());
            candidate = fast;
        }
        Rendering rendering = new Rendering(accurate, candidate);
        try (BufferedReader reader = NukedOpn2ScriptRunner.open(script)) {
            String line;
            while ((line = reader.readLine()) != null && rendering.frames < MAX_FRAMES) {
                rendering.execute(line);
            }
        }
        Metrics metrics = rendering.metrics();
        if (Boolean.getBoolean("openggf.fastfm.dump")) {
            rendering.dumpWindows(script.getFileName().toString().replace(".txt.gz", ""));
        }
        // The digest of the fast core's raw output lets a refactor prove it is bit-identical.
        System.out.printf(Locale.ROOT, "fastfm %-16s frames=%7d rmsAccurate=%8.1f rmsFast=%8.1f ratio=%6.3f corr=%6.3f lag=%+.3f envCorr=%6.3f zcErr=%6.4f fb=%d fastDigest=%016x%n",
                script.getFileName().toString().replace(".txt.gz", ""), rendering.frames,
                metrics.rmsAccurate, metrics.rmsFast, metrics.ratio, metrics.correlation, metrics.lag,
                metrics.envelopeCorrelation, metrics.zeroCrossingError, rendering.maxFeedback, rendering.fastDigest());
        if (diagnosticOnly) return;
        String name = scriptName(script);
        if (metrics.rmsAccurate < SILENCE_RMS) {
            assertTrue(metrics.rmsFast < SILENCE_RMS * MAX_LEVEL_RATIO,
                    name + ": accurate core is silent but fast core is not: " + metrics.rmsFast);
            return;
        }
        assertTrue(metrics.ratio <= MAX_LEVEL_RATIO && metrics.ratio >= 1.0 / MAX_LEVEL_RATIO,
                name + ": level ratio fast/accurate out of range: " + metrics.ratio);
        assertTrue(metrics.correlation >= MIN_CORRELATION,
                name + ": correlation below threshold: " + metrics.correlation);
    }

    private static final class Rendering {
        private final Ym2612Chip accurate;
        private final FmChip fast;
        private final java.util.ArrayDeque<int[]> delayedWrites = new java.util.ArrayDeque<>();
        private final int[] latchedHigh = new int[2];
        private final int[] left = new int[1024];
        private final int[] right = new int[1024];
        private final List<int[]> accurateChunks = new ArrayList<>();
        private final List<int[]> fastChunks = new ArrayList<>();
        private long cycles;
        private int frames;
        private int paceAddress;
        private int paceData;
        /** Highest feedback value written to 0xB0–0xB2 on either bank. */
        int maxFeedback;

        Rendering(Ym2612Chip accurate, FmChip fast) {
            this.accurate = accurate;
            this.fast = fast;
        }

        void execute(String raw) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') {
                return;
            }
            String[] fields = line.split("\\s+");
            switch (fields[0]) {
                case "type" -> {
                    int type = Integer.parseInt(fields[1]);
                    accurate.setChipType(type);
                    fast.setChipType(type);
                }
                case "pace" -> {
                    paceAddress = Integer.parseInt(fields[1]);
                    paceData = Integer.parseInt(fields[2]);
                }
                case "write" -> {
                    int busPort = Integer.parseInt(fields[1]);
                    int value = Integer.parseInt(fields[2]);
                    if ((busPort & 1) == 0) {
                        accurate.writeAddress(busPort >> 1, value);
                        if (CONTROL.equals("accurateDelay")) {
                            delayedWrites.add(new int[] {2, busPort >> 1, value});
                        } else {
                            fast.writeAddress(busPort >> 1, value);
                        }
                    } else {
                        accurate.writeData(busPort >> 1, value);
                        if (CONTROL.equals("accurateDelay")) {
                            delayedWrites.add(new int[] {3, busPort >> 1, value});
                        } else {
                            fast.writeData(busPort >> 1, value);
                        }
                    }
                }
                case "reg" -> {
                    int part = Integer.parseInt(fields[1]);
                    int register = Integer.parseInt(fields[2]);
                    int value = Integer.parseInt(fields[3]);
                    if (register >= 0xB0 && register <= 0xB2) {
                        maxFeedback = Math.max(maxFeedback, (value >> 3) & 7);
                    }
                    accurate.write(part, register, value);
                    writeCandidate(part, register, value);
                    clock(paceAddress + paceData);
                }
                case "clock" -> clock(Long.parseLong(fields[1]));
                case "at" -> {
                    long target = Long.parseLong(fields[1]) * 24L;
                    if (target > cycles) {
                        clock(target - cycles);
                    }
                }
                default -> {
                    // status / irq / dump lines are side observations
                }
            }
        }

        /** Routes a register write to the candidate arm, applying the control or negative mode. */
        private void writeCandidate(int part, int register, int value) {
            if (CONTROL.equals("accurateDelay")) {
                delayedWrites.add(new int[] {1, part, register, value});
                return;
            }
            int port = part & 1;
            switch (CONTROL) {
                case "pitch" -> {
                    if (register >= 0xA4 && register <= 0xA6) {
                        latchedHigh[port] = value;
                    } else if (register >= 0xA0 && register <= 0xA2) {
                        int fnum = ((latchedHigh[port] & 7) << 8) | value;
                        int block = (latchedHigh[port] >> 3) & 7;
                        int wrong = Math.min(0x7FF, (int) Math.round(fnum * Math.pow(2.0, 1.0 / 12.0)));
                        fast.write(part, register + 4, (block << 3) | (wrong >> 8));
                        fast.write(part, register, wrong & 0xFF);
                        return;
                    }
                }
                case "routing" -> {
                    if (register >= 0xB0 && register <= 0xB2) {
                        value ^= 1;
                    }
                }
                case "feedback" -> {
                    if (register >= 0xB0 && register <= 0xB2 && ((value >> 3) & 7) > 0) {
                        value -= 8;
                    }
                }
                default -> {
                }
            }
            fast.write(part, register, value);
        }

        private void clock(long count) {
            cycles += count;
            long due = cycles / 24 - frames;
            while (due > 0) {
                int chunk = (int) Math.min(due, left.length);
                accurateChunks.add(render(accurate, chunk));
                if (CONTROL.equals("accurateDelay")) {
                    // Land the writes queued before this frame one frame late.
                    java.util.List<int[]> landing = new ArrayList<>(delayedWrites);
                    delayedWrites.clear();
                    fastChunks.add(render(fast, 1));
                    for (int[] w : landing) {
                        switch (w[0]) {
                            case 1 -> fast.write(w[1], w[2], w[3]);
                            case 2 -> fast.writeAddress(w[1], w[2]);
                            default -> fast.writeData(w[1], w[2]);
                        }
                    }
                    if (chunk > 1) {
                        fastChunks.add(render(fast, chunk - 1));
                    }
                } else {
                    fastChunks.add(render(fast, chunk));
                }
                frames += chunk;
                due -= chunk;
            }
        }

        private int[] render(FmChip chip, int chunk) {
            java.util.Arrays.fill(left, 0, chunk, 0);
            java.util.Arrays.fill(right, 0, chunk, 0);
            chip.renderStereo(left, right, chunk);
            int[] mono = new int[chunk];
            for (int i = 0; i < chunk; i++) {
                mono[i] = left[i] + right[i];
            }
            return mono;
        }

        /** Diagnostic: RMS of each core per 2048-frame window, and their correlation, to locate a divergence. */
        void dumpWindows(String name) {
            double[] a = flatten(accurateChunks);
            double[] f = flatten(fastChunks);
            int window = Integer.getInteger("openggf.fastfm.dumpWindow", 2048);
            for (int start = 0; start < a.length; start += window) {
                int end = Math.min(a.length, start + window);
                double[] wa = java.util.Arrays.copyOfRange(a, start, end);
                double[] wf = java.util.Arrays.copyOfRange(f, start, end);
                double c = correlation(removeMean(wa), removeMean(wf), 0);
                System.out.printf(Locale.ROOT, "fastfm-window %s %7d acc=%8.1f fast=%8.1f corr=%6.3f hzAcc=%9.3f hzFast=%9.3f zcAcc=%d zcFast=%d%n",
                        name, start, rms(wa), rms(wf), c, frequencyHz(wa), frequencyHz(wf),
                        crossings(removeMean(wa), 0, wa.length), crossings(removeMean(wf), 0, wf.length));
            }
        }

        long fastDigest() {
            long hash = 0xcbf29ce484222325L;
            for (int[] chunk : fastChunks) {
                for (int v : chunk) {
                    hash ^= v;
                    hash *= 0x100000001b3L;
                }
            }
            return hash;
        }

        Metrics metrics() {
            double[] a = removeMean(flatten(accurateChunks));
            double[] f = removeMean(flatten(fastChunks));
            double rmsA = rms(a);
            double rmsF = rms(f);
            // One coherent shift for the whole waveform: integer frames first,
            // then (only when still below the threshold) a bounded fractional
            // refinement within one frame of the winner (CS's validated helper:
            // it rejects semitone and routing negatives). Levels stay unfiltered.
            FastFmWaveformAlignment.Alignment alignment =
                    FastFmWaveformAlignment.integer(a, f, LAG_SEARCH);
            if (alignment.correlation() < MIN_CORRELATION) {
                alignment = FastFmWaveformAlignment.refine(a, f, LAG_SEARCH, alignment);
            }
            return new Metrics(rmsA, rmsF, rmsA == 0 ? (rmsF == 0 ? 1 : Double.POSITIVE_INFINITY) : rmsF / rmsA,
                    alignment.correlation(), alignment.lag(),
                    envelopeCorrelation(a, f, 256), zeroCrossingContourError(a, f, 256));
        }

        /**
         * Correlation of the two cores' RMS envelopes over {@code window}-frame
         * windows. This is a diagnostic only; contour agreement cannot establish
         * waveform fidelity, including for maximal-feedback sounds.
         */
        private static double envelopeCorrelation(double[] a, double[] f, int window) {
            int n = Math.min(a.length, f.length) / window;
            if (n < 2) {
                return 0;
            }
            double[] ea = new double[n];
            double[] ef = new double[n];
            for (int w = 0; w < n; w++) {
                ea[w] = rms(java.util.Arrays.copyOfRange(a, w * window, (w + 1) * window));
                ef[w] = rms(java.util.Arrays.copyOfRange(f, w * window, (w + 1) * window));
            }
            return correlation(removeMean(ea), removeMean(ef), 0);
        }

        /**
         * Mean relative difference of the zero-crossing rate per window: a
         * pitch/timbre probe that the RMS contour is blind to (a tone a
         * semitone off has the same envelope but 6 % more crossings).
         */
        private static double zeroCrossingContourError(double[] a, double[] f, int window) {
            int n = Math.min(a.length, f.length) / window;
            double sum = 0;
            int counted = 0;
            for (int w = 0; w < n; w++) {
                // Per-window mean removal: the accurate core's ladder DC offset otherwise
                // swallows every crossing of a quiet tail.
                double[] wa = removeMean(java.util.Arrays.copyOfRange(a, w * window, (w + 1) * window));
                double[] wf = removeMean(java.util.Arrays.copyOfRange(f, w * window, (w + 1) * window));
                if (rms(wa) < SILENCE_RMS) {
                    continue; // both quiet: no pitch to compare
                }
                int za = crossings(wa, 0, wa.length);
                int zf = crossings(wf, 0, wf.length);
                if (za >= 8) {
                    sum += Math.abs(zf - za) / (double) za;
                    counted++;
                }
            }
            return counted == 0 ? 0 : sum / counted;
        }

        private static int crossings(double[] x, int from, int to) {
            int count = 0;
            for (int i = from + 1; i < to; i++) {
                if ((x[i - 1] < 0) != (x[i] < 0)) {
                    count++;
                }
            }
            return count;
        }

        private static double[] flatten(List<int[]> chunks) {
            int total = chunks.stream().mapToInt(c -> c.length).sum();
            double[] out = new double[total];
            int at = 0;
            for (int[] chunk : chunks) {
                for (int v : chunk) {
                    out[at++] = v;
                }
            }
            return out;
        }

        /** The accurate core models the YM2612's per-channel DC offsets (ladder effect); judge the AC signal. */
        private static double[] removeMean(double[] x) {
            double mean = 0;
            for (double v : x) {
                mean += v;
            }
            mean = x.length == 0 ? 0 : mean / x.length;
            double[] out = new double[x.length];
            for (int i = 0; i < x.length; i++) {
                out[i] = x[i] - mean;
            }
            return out;
        }

        /** Mean frequency of a steady tone from interpolated rising zero crossings. */
        private static double frequencyHz(double[] x) {
            double first = -1;
            double last = -1;
            int cycles = 0;
            for (int i = 1; i < x.length; i++) {
                if (x[i - 1] < 0 && x[i] >= 0) {
                    double t = (i - 1) + (-x[i - 1]) / (x[i] - x[i - 1]);
                    if (first < 0) {
                        first = t;
                    } else {
                        cycles++;
                    }
                    last = t;
                }
            }
            return cycles == 0 ? 0 : cycles / (last - first) * (7670453.0 / 144.0);
        }

        private static double rms(double[] x) {
            double sum = 0;
            for (double v : x) {
                sum += v * v;
            }
            return x.length == 0 ? 0 : Math.sqrt(sum / x.length);
        }

        private static double correlation(double[] a, double[] f, int lag) {
            double num = 0;
            double da = 0;
            double df = 0;
            for (int i = Math.max(0, -lag); i < a.length && i + lag < f.length; i++) {
                double x = a[i];
                double y = f[i + lag];
                num += x * y;
                da += x * x;
                df += y * y;
            }
            return da == 0 || df == 0 ? 0 : num / Math.sqrt(da * df);
        }
    }

    private record Metrics(double rmsAccurate, double rmsFast, double ratio, double correlation, double lag,
                           double envelopeCorrelation, double zeroCrossingError) {
    }
}
