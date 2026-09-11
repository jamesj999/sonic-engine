package com.openggf.trace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guard: every recorded object pass in a committed fixture is labelled with a
 * frame on which the console actually polled the controller.
 *
 * <p>A {@code run_objects_end} record states the trace frame whose V-int
 * sampled the pad for that pass ({@code input_sample_frame}, and the previous
 * pass's {@code previous_input_sample_frame}). The S2 special stage samples
 * input from {@code SpecialStage_MainLoop}'s {@code WaitForVint} →
 * {@code ReadJoypads} (docs/s2disasm/s2.asm:6674-6691), i.e. from inside the
 * vertical interrupt. Meanwhile the physics payload's {@code lag} column is
 * BizHawk's {@code IInputPollable.IsLagFrame}, which the GPGX host reports as
 * "no controller read happened during this frame"
 * (tools/tracechaser/bizhawk-headless/src/Core/GpgxHost.cs:94-97).
 *
 * <p>Those two statements cannot both be true of the same frame. A V-int that
 * ran {@code ReadJoypads} polled the pad, so the frame is by definition not a
 * lag frame. Hence the invariant this guard enforces:
 *
 * <blockquote>every {@code input_sample_frame} in a {@code run_objects_end}
 * record must land on a physics row whose {@code lag} column is 0, at offset
 * +0 exactly.</blockquote>
 *
 * <p>It is a semantic invariant, not a measured one: nothing about it was
 * derived by fitting a fixture's rows. That is precisely why it is trustworthy
 * as a permanent gate, and why it caught what a whole suite of green tests did
 * not.
 *
 * <h2>Why this exists</h2>
 * The committed {@code traces/s2/special_stage} fixture carried a recorder
 * off-by-one for an entire work session: the standalone capture path set its
 * BK2 offset on the Game_Mode entry frame and then <em>also</em> wrote a row
 * for that frame, so every pass was labelled one frame early (fixed in
 * e2aa50cd5). Three rounds of engine investigation were spent reconciling
 * correct code against incorrect ground truth before anyone questioned the
 * fixture. The invariant above separates the two cases instantly and needs no
 * ROM, no engine and no replay — the miscaptured fixture violated it on 3697 of
 * its 6344 sampled frames at +0 and satisfied it exactly at +1, while the seven
 * correctly captured run segments satisfied it exactly at +0.
 *
 * <p>The check is deliberately stated as "+0, zero violations" rather than
 * "some shift works": a fixture that is uniformly off by one is exactly the
 * defect being guarded against, so a best-fit shift would license it.
 *
 * <p>Coverage is decided by measurement, never by name: a fixture is checked
 * iff its physics payload actually carries a {@code lag} column and its aux
 * payload actually carries {@code run_objects_end} records. As it happens only
 * the special-stage trace profiles record {@code lag} (39 of the 266 committed
 * payloads) and only the S2 special stage emits {@code run_objects_end}, so the
 * ten S2 special-stage fixtures are every fixture the invariant can speak
 * about. A new producer that records both is picked up with no edit here.
 *
 * <p>Comparison-only (hard rule 4): this reads two committed fixture streams
 * and compares them to each other. No engine is constructed and no engine state
 * is hydrated.
 */
class TestTraceFixtureLagPolledInputGuard {

    private static final Path PROJECT_ROOT = resolveProjectRoot();
    private static final Path FIXTURE_ROOT =
            PROJECT_ROOT.resolve("src/test/resources/traces");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The two pass fields that name a frame on which the pad was polled. */
    private static final List<String> SAMPLE_FIELDS =
            List.of("input_sample_frame", "previous_input_sample_frame");

    /**
     * Ten fixture directories carrying 37120 {@code run_objects_end} passes
     * (74240 sampled frames across the two sample fields) were covered when the
     * guard landed: the standalone S2 special stage, the two special-stage
     * detours of the EHZ half-pipe round trip, and the seven of the
     * complete-emeralds run. Floors, not equalities: adding fixtures must not
     * need a guard edit, but losing them must not silently turn the guard into
     * a no-op.
     */
    private static final int MINIMUM_COVERED_FIXTURES = 10;
    private static final int MINIMUM_CONFIRMATIONS = 37120;

    /**
     * Fixture path -> justification for a genuine exemption. An entry here is a
     * claim that the ROM really does complete an object pass whose input sample
     * frame ran no {@code ReadJoypads} — which for the S2 special stage it
     * cannot. Empty, and it should stay that way: the correct response to a
     * violation is to re-record the fixture with a fixed capture runner, never
     * to allowlist the recording.
     */
    private static final Map<String, String> ALLOWED_VIOLATIONS =
            new LinkedHashMap<>();

    @Test
    void everyRecordedObjectPassSamplesInputOnANonLagFrame() throws IOException {
        List<String> failures = new ArrayList<>();
        int covered = 0;
        long confirmations = 0;

        for (Path aux : auxPayloads()) {
            Map<Integer, Integer> lag = readLagColumn(aux.getParent());
            if (lag.isEmpty()) {
                continue;
            }
            String label = relativize(aux.getParent());
            Violations violations = scan(aux, lag);
            if (violations.passes == 0) {
                continue;
            }
            covered++;
            confirmations += violations.confirmations;

            if (violations.count == 0) {
                continue;
            }
            String justification = ALLOWED_VIOLATIONS.get(label);
            if (justification != null) {
                System.out.println("[lag-polled-input] exempt: " + label
                        + " — " + justification);
                continue;
            }
            failures.add(label + ": " + violations.count + " of "
                    + violations.samples + " sampled frames (across "
                    + violations.passes + " passes) land on a lag row"
                    + " (first: " + violations.firstDetail + ")."
                    + " Violations at a shifted offset: minus one="
                    + violations.atMinusOne + ", plus one="
                    + violations.atPlusOne + " of " + violations.samples + ".");
        }

        if (!failures.isEmpty()) {
            fail("Recorded object pass(es) claim to have sampled the controller"
                    + " on a frame BizHawk reports as a lag frame — no V-int ran"
                    + " ReadJoypads there, so the pass is labelled with the"
                    + " wrong frame. This is a capture-runner offset bug in the"
                    + " fixture, not an engine defect and not a fixture to"
                    + " relax: find the runner's entry-frame handling (compare"
                    + " against S2RunCaptureRunner, which arms and `continue`s"
                    + " so row k is emulator frame E0+1+k) and re-record."
                    + " A near-uniform violation count that drops to zero at a"
                    + " shifted offset is the signature of exactly that bug.\n  "
                    + String.join("\n  ", failures));
        }

        assertTrue(covered >= MINIMUM_COVERED_FIXTURES,
                "Lag/polled-input guard covered only " + covered
                        + " fixtures, below the " + MINIMUM_COVERED_FIXTURES
                        + " it covered when it landed — fixtures moved and the"
                        + " guard has stopped guarding.");
        assertTrue(confirmations >= MINIMUM_CONFIRMATIONS,
                "Lag/polled-input guard confirmed only " + confirmations
                        + " sampled frames, below the " + MINIMUM_CONFIRMATIONS
                        + " it confirmed when it landed.");
    }

    /**
     * Proves the guard bites, against a synthetic payload pair rather than the
     * read-only fixture tree: the same passes are clean when labelled on the
     * lag-free frames they really sampled, and rejected when every label is
     * shifted one frame early — which is the exact shape of the e2aa50cd5
     * defect. Without this, a guard that silently matched nothing would report
     * green forever.
     */
    @Test
    void scanCatchesAUniformOneFrameEarlyLabelling(@TempDir Path root)
            throws IOException {
        // Frames 0..5; 1 and 3 are lag frames, so a pass may only claim to have
        // sampled input on 0, 2, 4 or 5.
        int[] lagColumn = {0, 1, 0, 1, 0, 0};
        Path dir = root.resolve("ss");
        Files.createDirectories(dir);
        StringBuilder physics = new StringBuilder("frame,input,lag\n");
        for (int frame = 0; frame < lagColumn.length; frame++) {
            physics.append(frame).append(",00,").append(lagColumn[frame])
                    .append('\n');
        }
        Files.writeString(dir.resolve("physics.csv"), physics.toString());

        int[] correct = {2, 4, 5};
        Files.writeString(dir.resolve("aux_state.jsonl"),
                passes(correct, 0));
        Map<Integer, Integer> lag = readLagColumn(dir);
        Violations clean = scan(dir.resolve("aux_state.jsonl"), lag);
        assertEquals(3, clean.passes);
        assertEquals(3, clean.samples);
        assertEquals(0, clean.count, "correctly labelled passes must be clean");

        Files.writeString(dir.resolve("aux_state.jsonl"),
                passes(correct, -1));
        Violations skewed = scan(dir.resolve("aux_state.jsonl"), lag);
        assertEquals(3, skewed.passes);
        assertEquals(2, skewed.count,
                "a one-frame-early labelling must be caught (frames 1 and 3"
                        + " are lag frames)");
        assertEquals(0, skewed.atPlusOne,
                "and must be reported as explained by a +1 shift");
    }

    private static String passes(int[] sampleFrames, int skew) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < sampleFrames.length; i++) {
            out.append("{\"frame\":").append(sampleFrames[i] + skew)
                    .append(",\"type\":\"run_objects_end\",\"pass_sequence\":")
                    .append(i)
                    .append(",\"input_sample_frame\":")
                    .append(sampleFrames[i] + skew)
                    .append("}\n");
        }
        return out.toString();
    }

    // ---------------------------------------------------------------- scan

    private static final class Violations {
        int passes;
        int samples;
        int count;
        int atMinusOne;
        int atPlusOne;
        int confirmations;
        String firstDetail;
    }

    /**
     * Counts violations at +0 and, for diagnosis only, at the two neighbouring
     * offsets. A sampled frame with no matching physics row is neither a
     * violation nor a confirmation: the pass may legitimately reference a frame
     * outside the published row window (a pass carried in from before row 0).
     */
    private static Violations scan(Path aux, Map<Integer, Integer> lag)
            throws IOException {
        Violations violations = new Violations();
        try (BufferedReader reader = openText(aux)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.contains("\"run_objects_end\"")) {
                    continue;
                }
                JsonNode node = MAPPER.readTree(line);
                if (!"run_objects_end".equals(node.path("type").asText())) {
                    continue;
                }
                boolean counted = false;
                for (String field : SAMPLE_FIELDS) {
                    JsonNode value = node.get(field);
                    if (value == null || value.asInt(-1) < 0) {
                        continue;
                    }
                    int frame = value.asInt();
                    Integer flag = lag.get(frame);
                    if (flag == null) {
                        continue;
                    }
                    if (!counted) {
                        violations.passes++;
                        counted = true;
                    }
                    violations.samples++;
                    if (flag != 0) {
                        violations.count++;
                        if (violations.firstDetail == null) {
                            violations.firstDetail = "pass "
                                    + node.path("pass_sequence").asInt(-1)
                                    + " " + field + "=" + frame
                                    + " but that row has lag=1";
                        }
                    } else {
                        violations.confirmations++;
                    }
                    Integer before = lag.get(frame - 1);
                    if (before != null && before != 0) {
                        violations.atMinusOne++;
                    }
                    Integer after = lag.get(frame + 1);
                    if (after != null && after != 0) {
                        violations.atPlusOne++;
                    }
                }
            }
        }
        return violations;
    }

    // ---------------------------------------------------------------- io

    private static List<Path> auxPayloads() throws IOException {
        try (var tree = Files.walk(FIXTURE_ROOT)) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith("aux_state")
                                && (name.endsWith(".jsonl")
                                        || name.endsWith(".jsonl.gz"));
                    })
                    .sorted()
                    .toList();
        }
    }

    /**
     * Frame -> {@code lag} column of the directory's physics payload. Empty when
     * the directory has no physics payload, or one without a {@code lag} column.
     */
    private static Map<Integer, Integer> readLagColumn(Path dir)
            throws IOException {
        Path payload = dir.resolve("physics.csv.gz");
        if (!Files.isRegularFile(payload)) {
            payload = dir.resolve("physics.csv");
            if (!Files.isRegularFile(payload)) {
                return Map.of();
            }
        }
        try (BufferedReader reader = openText(payload)) {
            String header = reader.readLine();
            if (header == null) {
                return Map.of();
            }
            List<String> columns = List.of(header.split(",", -1));
            int frameColumn = columns.indexOf("frame");
            int lagColumn = columns.indexOf("lag");
            if (frameColumn < 0 || lagColumn < 0) {
                return Map.of();
            }
            Map<Integer, Integer> lag = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split(",", -1);
                lag.put(Integer.parseInt(parts[frameColumn].trim()),
                        Integer.parseInt(parts[lagColumn].trim()));
            }
            return lag;
        }
    }

    private static BufferedReader openText(Path path) throws IOException {
        InputStream in = Files.newInputStream(path);
        if (path.getFileName().toString().endsWith(".gz")) {
            in = new GZIPInputStream(in, 1 << 16);
        }
        return new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8), 1 << 16);
    }

    private static String relativize(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path root = PROJECT_ROOT.toAbsolutePath().normalize();
        return (absolute.startsWith(root) ? root.relativize(absolute) : absolute)
                .toString().replace('\\', '/');
    }

    private static Path resolveProjectRoot() {
        String basedir = System.getProperty("project.basedir");
        if (basedir != null && !basedir.isEmpty()) {
            return Paths.get(basedir);
        }
        return Paths.get(System.getProperty("user.dir", "."));
    }
}
